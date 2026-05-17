package com.meter.listener;

import java.util.List;

import com.meter.database.SqlLiteDB;
import gurux.common.GXCommon;
import gurux.common.IGXMediaListener;
import gurux.common.MediaStateEventArgs;
import gurux.common.PropertyChangedEventArgs;
import gurux.common.ReceiveEventArgs;
import gurux.common.TraceEventArgs;
import gurux.dlms.enums.Authentication;
import gurux.dlms.enums.InterfaceType;
import gurux.dlms.secure.GXDLMSSecureClient;
import gurux.io.BaudRate;
import gurux.io.Parity;
import gurux.io.StopBits;
import gurux.serial.GXSerial;

/**
 * Listens for DLMS/COSEM push notifications from an energy meter over a serial port.
 * This class opens a serial connection, collects incoming bytes via IdleGapPushCollector,
 * reassembles complete APDU frames, parses them using the Gurux DLMS library, and
 * delivers the decoded values to a DataReceivedCallback.
 */
public class GXDLMSPushListener implements IGXMediaListener, AutoCloseable {

    private boolean trace = true;
    private GXSerial media;
    private GXDLMSSecureClient client;
    private DataReceivedCallback callback;
    private IdleGapPushCollector collector;

    /**
     * Callback interface invoked when a complete push message is received and parsed.
     * Callback to SmartMonitorApp to update GUI and database with received data.
     */
    public interface DataReceivedCallback {
        void onDataReceived(String pushTs, String hexData, List<?> values, String xmlData);
    }

    /**
     * Full constructor. Opens the serial port, configures the DLMS client, and starts
     * listening for push messages.
     *
     * The serial port is configured for 9600 baud, 8 data bits, no parity, 1 stop bit —
     * the standard settings for DLMS push over serial.
     *
     * @param portName  serial port name (e.g., "COM3" or "/dev/ttyUSB0")
     * @param callback  handler invoked when a complete push message is parsed
     * @param database  database instance available for use by the caller
     * @throws Exception if the serial port cannot be opened or the DLMS client fails to initialize
     */
    public GXDLMSPushListener(String portName, DataReceivedCallback callback, SqlLiteDB database) throws Exception {
        this.callback = callback;

        // Create a DLMS client in server-push mode (no authentication, PDU framing)
        client = new GXDLMSSecureClient(true, 1, 1, Authentication.NONE, null, InterfaceType.PDU);

        // Set up the idle-gap collector with a 200 ms gap threshold.
        // When no new bytes arrive for 200 ms, the accumulated buffer is treated as one complete APDU.
        collector = new IdleGapPushCollector(
                200,
                client,
                new IdleGapPushCollector.PushHandler() {
                    @Override
                    public void onPush(byte[] apdu, List<?> values, String xml, String pushTs) {
                        String hex = GXCommon.bytesToHex(apdu);
                        System.out.println("[PUSH] FULL APDU: " + hex);
                        System.out.println("[PUSH] values=" + values.size());
                        System.out.println("[PUSH] timestamp=" + pushTs);
                        System.out.println(xml);

                        // Forward to the registered callback
                        DataReceivedCallback cb = GXDLMSPushListener.this.callback;
                        if (cb != null) {
                            cb.onDataReceived(pushTs, hex, values, xml);
                        }
                    }

                    @Override
                    public void onDropped(byte[] raw, String reason) {
                        System.err.println("[PUSH] DROPPED: " + reason);
                        System.err.println("[PUSH] RAW: " + GXCommon.bytesToHex(raw));
                    }
                }
        );
        // Configure and open the serial port
        media = new GXSerial();
        media.setPortName(portName);
        media.setBaudRate(BaudRate.BAUD_RATE_9600);
        media.setDataBits(8);
        media.setParity(Parity.NONE);
        media.setStopBits(StopBits.ONE);

        media.addListener(this);
        media.open();

        System.out.println("[LISTENER] Serial port " + portName + " opened!");
        System.out.println("[LISTENER] Waiting for push messages (every 60 sec)...");
    }

    public void setCallback(DataReceivedCallback callback) {
        this.callback = callback;
    }

    /**
     * Shuts down the collector and closes the serial port.
     * Exceptions during individual cleanup steps are suppressed to ensure all
     * resources are released even if one step fails.
     */
    @Override
    public void close() {
        try {
            if (collector != null) collector.shutdown();
        } catch (Exception ignored) {}

        if (media != null) {
            try {
                media.removeListener(this);
            } catch (Exception ignored) {}
            try {
                if (media.isOpen()) media.close();
            } catch (Exception ignored) {}
        }
    }

    public boolean isConnected() {
        return media != null && media.isOpen();
    }

    /**
     * Called by the Gurux media layer when a transport-level error occurs.
     * Logs the error message to stderr.
     */
    @Override
    public void onError(Object sender, Exception ex) {
        System.err.println("[LISTENER] Error: " + ex.getMessage());
    }

    private static void printData(final Object value) {
        if (value instanceof Object[]) {
            System.out.println("+++++++++++++++++++++++++++++++++++++++++");
            for (Object it : (Object[]) value) {
                printData(it);
            }
            System.out.println("+++++++++++++++++++++++++++++++++++++++++");
        } else if (value instanceof byte[]) {
            System.out.println(GXCommon.bytesToHex((byte[]) value));
        } else if (value instanceof List<?>) {
            System.out.println("+++++++++++++++++++++++++++++++++++++++++");
            for (Object it : (List<?>) value) {
                printData(it);
            }
            System.out.println("+++++++++++++++++++++++++++++++++++++++++");
        } else {
            System.out.println(String.valueOf(value));
        }
    }

    /**
     * Called by the Gurux media layer each time a new chunk of bytes arrives on the serial port.
     * The raw bytes are forwarded to the collector, which buffers them until a complete APDU
     * is detected via an idle gap.
     */
    @Override
    public void onReceived(Object sender, ReceiveEventArgs e) {
        byte[] chunk = (byte[]) e.getData();
        collector.onBytes(chunk);
    }

    /**
     * Called by the Gurux media layer when the serial port connection state changes
     * (e.g., opened or closed). Logs the new state to stdout.
     */
    @Override
    public void onMediaStateChange(Object sender, MediaStateEventArgs e) {
        System.out.println("[LISTENER] State: " + e.getState());
    }

    @Override
    public void onTrace(Object sender, TraceEventArgs e) {
    }

    @Override
    public void onPropertyChanged(Object sender, PropertyChangedEventArgs e) {
    }
}