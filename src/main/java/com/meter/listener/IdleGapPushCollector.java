package com.meter.listener;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.*;

import gurux.dlms.GXByteBuffer;
import gurux.dlms.GXDateTime;
import gurux.dlms.GXReplyData;
import gurux.dlms.GXDLMSTranslator;
import gurux.dlms.enums.TranslatorOutputType;
import gurux.dlms.secure.GXDLMSSecureClient;

/**
 * Collects raw serial bytes and reassembles complete DLMS/COSEM push APDUs
 * using an idle-gap detection strategy.
 *
 * This collector waits for a configurable period of silence on the serial line.
 * When no new bytes arrive within that window, the accumulated buffer is treated
 * as one complete APDU and handed off for parsing.
 *
 * The collector operates in two states:
 *   HUNT    - discarding bytes until the APDU start byte (0x0F) is seen
 *   COLLECT - buffering bytes and resetting the idle timer on each new chunk
 *
 * This class is thread-safe by a single lock.
 * The idle-gap timer is a dedicated thread ("push-idle-gap").
 */
public final class IdleGapPushCollector {
    private static final DateTimeFormatter SQLITE_DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final byte APDU_START = (byte) 0x0F;
    private static final int MAX_BUFFER_BYTES = 20000;

    private enum State { HUNT, COLLECT }

    /**
     * Single-threaded scheduler used to fire the idle-gap flush after silence on the line.
     */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "push-idle-gap");
                t.setDaemon(true);
                return t;
            });

    /** Mutex protecting all access to the byte buffer and collector state. */
    private final Object lock = new Object();

    private byte[] buffer = new byte[4096];
    private int bufferLen = 0;

    /** Handle to the currently pending idle-gap flush task. Canceled and rescheduled on each new chunk.*/
    private ScheduledFuture<?> idleTask;
    private final long idleMillis;

    private State state = State.HUNT;

    private final GXDLMSSecureClient client;
    private final GXDLMSTranslator translator =
            new GXDLMSTranslator(TranslatorOutputType.SIMPLE_XML);

    /**
     * Callback interface notified when a complete APDU is parsed or when data is discarded.
     */
    public interface PushHandler {
        /**
         * Called when a complete push APDU has been successfully parsed.
         *
         * @param apdu       raw bytes of the complete APDU frame
         * @param values     decoded data object values in positional order
         * @param xml        XML representation of the parsed APDU
         * @param pushTsIso  UTC timestamp from the meter, or system time if unavailable,
         *                   formatted as "yyyy-MM-dd HH:mm:ss"
         */
        void onPush(byte[] apdu, List<?> values, String xml, String pushTsIso);

        /**
         * Called when a buffered frame is discarded without being parsed.
         * This may occur if the frame is malformed, incomplete, or exceeds the buffer limit.
         *
         * @param raw    the raw bytes that were dropped
         * @param reason explanation of why the data was discarded
         */
        void onDropped(byte[] raw, String reason);
    }

    private final PushHandler handler;

    // Constructs a new IdleGapPushCollector
    public IdleGapPushCollector(long idleMillis, GXDLMSSecureClient client, PushHandler handler) {
        this.idleMillis = idleMillis;
        this.client = client;
        this.handler = handler;
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * Feeds a new chunk of raw bytes from the serial port into the collector.
     *
     * In HUNT state, bytes are discarded until the APDU start byte (0x0F) is found,
     * at which point the collector transitions to COLLECT and begins buffering.
     *
     * In COLLECT state, bytes are appended to the buffer and the idle timer is reset.
     * If the buffer exceeds MAX_BUFFER_BYTES, it is discarded and the collector resets to HUNT.
     *
     * This method is thread-safe (lock) and may be called from any thread.
     *
     * @param chunk raw bytes received from the serial port
     */
    public void onBytes(byte[] chunk) {
        synchronized (lock) {
            if (state == State.HUNT) {
                int idx = indexOf(chunk, APDU_START);
                if (idx < 0) {
                    // Drop everything until a start byte.
                    return;
                }
                // Start collecting from the first 0x0F. Drop any bytes before it.
                resetBuffer();
                append(chunk, idx, chunk.length - idx);
                state = State.COLLECT;
                scheduleFlushLocked();
                return;
            }

            // COLLECT
            append(chunk, 0, chunk.length);

            if (bufferLen > MAX_BUFFER_BYTES) {
                byte[] dropped = Arrays.copyOf(buffer, bufferLen);
                resetToHuntLocked();
                handler.onDropped(dropped, "Buffer exceeded max; dropped + resync.");
                return;
            }

            scheduleFlushLocked();
        }
    }

    /**
     * Cancels any existing idle-gap task and schedules a new one to fire after idleMillis.
     * Must be called while holding the lock.
     */
    private void scheduleFlushLocked() {
        if (idleTask != null) {
            idleTask.cancel(false);
        }
        idleTask = scheduler.schedule(this::flush, idleMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Attempts to parse the accumulated buffer as a complete DLMS push APDU.
     *
     * Called automatically by the idle-gap scheduler after a period of serial silence.
     * After the attempt (whether successful or not), the collector always resets to HUNT,
     * ready to receive the next push message.
     *
     * On success, handler.onPush() is called with the parsed values and metadata.
     * On failure, handler.onDropped() is called with the raw bytes and an error message.
     */
    private void flush() {
        byte[] frame;
        synchronized (lock) {
            if (state != State.COLLECT || bufferLen == 0) {
                return;
            }
            frame = Arrays.copyOf(buffer, bufferLen);
            resetToHuntLocked(); // After flush attempt, always go back to HUNT
        }

        // Must start with 0x0F
        if (frame[0] != APDU_START) {
            handler.onDropped(frame, "Frame did not start with 0x0F; dropped + resync.");
            return;
        }

        try {
            GXReplyData notify = new GXReplyData();
            client.getData(new GXByteBuffer(frame), notify);

            if (!notify.isComplete() || notify.isMoreData()) {
                handler.onDropped(frame, "APDU not complete (or more-data) after idle gap; dropped + resync.");
                return;
            }

            String pushTsIso = extractMeterTimestampOrNow(notify);

            String xml = translator.pduToXml(notify.getData());

            Object v = notify.getValue();
            List<?> values;
            if (v instanceof List<?>) values = (List<?>) v;
            else if (v instanceof Object[]) values = Arrays.asList((Object[]) v);
            else values = List.of(v);

            handler.onPush(frame, values, xml, pushTsIso);

        } catch (Exception ex) {
            handler.onDropped(frame, "Parse error: " + ex.getMessage() + "; dropped + resync.");
        }
    }

    /** Extracts the meter's own timestamp from the parsed push reply */
    private static String extractMeterTimestampOrNow(GXReplyData notify) {
        GXDateTime gxTime = notify.getTime();

        if (gxTime != null) {
            try {
                Calendar cal = gxTime.getMeterCalendar();
                if (cal != null) {
                    return SQLITE_DT.format(cal.toInstant());
                }
            } catch (Exception ex) {
                System.err.println("[COLLECTOR] DateTime extraction failed: " + ex.getMessage());
            }
        } else {
            System.err.println("[COLLECTOR] DateTime not received in push");
        }

        return SQLITE_DT.format(Instant.now());
    }

    // ----- buffer helpers -----

    /**
     * Resets the collector to HUNT state, clears the buffer, and cancels any pending flush task.
     * Must be called while holding the lock.
     */
    private void resetToHuntLocked() {
        resetBuffer();
        state = State.HUNT;
        if (idleTask != null) {
            idleTask.cancel(false);
            idleTask = null;
        }
    }

    private void resetBuffer() {
        bufferLen = 0;
    }

    /**
     * Appends a slice of a byte array to the internal buffer, growing it if necessary.
     * The buffer capacity is doubled each time it needs to expand.
     *
     * @param chunk  source byte array
     * @param off    starting offset within chunk
     * @param count  number of bytes to copy from chunk
     */
    private void append(byte[] chunk, int off, int count) {
        if (count <= 0) return;
        if (bufferLen + count > buffer.length) {
            int newCap = Math.max(buffer.length * 2, bufferLen + count);
            buffer = Arrays.copyOf(buffer, newCap);
        }
        System.arraycopy(chunk, off, buffer, bufferLen, count);
        bufferLen += count;
    }

    /**
     * Returns the index of the first occurrence of a byte value in an array,
     * or -1 if the value is not found.
     */
    private static int indexOf(byte[] arr, byte value) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == value) return i;
        }
        return -1;
    }
}
