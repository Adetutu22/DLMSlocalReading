package com.meter.listener;

import com.meter.database.SqlLiteDB;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Optical polling listener.
 * Mirrors the GXDLMSPushListener interface so SmartMonitorApp can
 * treat both modes identically via the same onDataReceived callback.
 *
 * Polls the meter every POLL_INTERVAL_MS milliseconds (default 60 s).
 */
public class OpticalPollListener {

    private static final long POLL_INTERVAL_MS = 60_000;

    public interface DataCallback {
        /** Same signature as the push listener callback in SmartMonitorApp */
        void onData(String timestamp, String hexDump, List<?> values, String xmlData);
    }

    private final OpticalDLMSClient dlmsClient;
    private final DataCallback callback;
    private final SqlLiteDB database;

    private volatile boolean running = false;
    private Thread pollThread;

    /**
     * @param port          COM port, e.g. "COM3" or "/dev/ttyUSB0"
     * @param clientAddress DLMS client address (typically 16)
     * @param serverAddress DLMS server address (typically 1)
     * @param callback      fired on every successful poll
     * @param database      optional DB reference (may be null)
     * @param logCallback   line-by-line log consumer for the UI log panel
     */
    public OpticalPollListener(String port,
                               int clientAddress,
                               int serverAddress,
                               DataCallback callback,
                               SqlLiteDB database,
                               java.util.function.Consumer<String> logCallback) {
        this.callback = callback;
        this.database = database;
        this.dlmsClient = new OpticalDLMSClient(port, clientAddress, serverAddress, logCallback);
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Starts the background poll loop.
     */
    public void start() {
        running = true;
        pollThread = new Thread(this::pollLoop, "optical-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    public void close() {
        running = false;
        if (pollThread != null) pollThread.interrupt();
        dlmsClient.close();
    }

    public boolean isConnected() { return dlmsClient.isConnected(); }

    // -----------------------------------------------------------------------
    // Poll loop
    // -----------------------------------------------------------------------

    private void pollLoop() {
        // First poll immediately, then every POLL_INTERVAL_MS
        while (running) {
            try {
                poll();
            } catch (Exception e) {
                System.err.println("[OPTICAL POLL] Error during poll: " + e.getMessage());
                // If connection died, stop loop — the UI will show disconnected
                if (!dlmsClient.isConnected()) break;
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Performs a single meter read cycle: connect → read all OBIS values → disconnect.
     */
    private void poll() throws Exception {
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        try {
            if (!dlmsClient.connect()) {
                throw new RuntimeException("Failed to connect to meter");
            }
            List<Object> rawValues = dlmsClient.readAll();

            // Build a minimal hex dump string (join first-value bytes for display)
            String hexDump = buildHexDump(rawValues);

            // Fire callback — same signature the push listener uses
            if (callback != null) {
                callback.onData(timestamp, hexDump, rawValues, "");
            }
        } finally {
            dlmsClient.close(); // always disconnect, even if readAll() throws
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String buildHexDump(List<Object> values) {
        StringBuilder sb = new StringBuilder();
        for (Object v : values) {
            if (v instanceof byte[]) {
                for (byte b : (byte[]) v) sb.append(String.format("%02X", b));
            } else if (v != null) {
                // Represent numeric/string values as UTF-8 hex for consistency
                String s = v.toString();
                for (char c : s.toCharArray()) sb.append(String.format("%02X", (int) c));
            }
            sb.append(" ");
        }
        return sb.toString().trim();
    }
}