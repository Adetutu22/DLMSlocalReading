package com.meter.listener;

import gurux.common.ReceiveParameters;
import gurux.dlms.GXByteBuffer;
import gurux.dlms.GXDLMSClient;
import gurux.dlms.GXReplyData;
import gurux.dlms.enums.Authentication;
import gurux.dlms.enums.InterfaceType;
import gurux.dlms.objects.*;
import gurux.serial.GXSerial;
import gurux.io.BaudRate;
import gurux.io.Parity;
import gurux.io.StopBits;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Optical port DLMS client.
 * Implements IEC 62056-21 Mode E handshake followed by DLMS/HDLC reads.
 */
public class OpticalDLMSClient {

    // -----------------------------------------------------------------------
    // OBIS definitions from EGDObisParser — class ID, OBIS, attribute
    // -----------------------------------------------------------------------
    private static final Object[][] OBIS_READ_MAP = {
            // { classId, obisCode, attribute, index }
            {1,  "0.0.42.0.0.255",  2,  1},   // COSEM logical device name
            {40, "0.2.25.9.0.255",  1,  2},   // Push setup
            {1,  "0.0.96.1.0.255",  2,  3},   // Serial number
            {70, "0.0.96.3.10.255", 3,  4},   // Disconnect status
            {71, "0.0.17.0.0.255",  3,  5},   // Power limiter value
            {70, "0.1.96.3.10.255", 3,  6},   // Relay 1
            {70, "0.2.96.3.10.255", 3,  7},   // Relay 2
            {70, "0.3.96.3.10.255", 3,  8},   // Relay 3
            {70, "0.4.96.3.10.255", 3,  9},   // Relay 4
            {70, "0.5.96.3.10.255", 3, 10},   // Relay 5
            {70, "0.6.96.3.10.255", 3, 11},   // Relay 6
            {1,  "0.0.96.14.0.255", 2, 12},   // Active tariff
            {3,  "1.0.1.7.0.255",   2, 13},   // Power import total
            {3,  "1.0.21.7.0.255",  2, 14},   // Power import L1
            {3,  "1.0.41.7.0.255",  2, 15},   // Power import L2
            {3,  "1.0.61.7.0.255",  2, 16},   // Power import L3
            {3,  "1.0.2.7.0.255",   2, 17},   // Power export total
            {3,  "1.0.22.7.0.255",  2, 18},   // Power export L1
            {3,  "1.0.42.7.0.255",  2, 19},   // Power export L2
            {3,  "1.0.62.7.0.255",  2, 20},   // Power export L3
            {3,  "1.0.1.8.0.255",   2, 21},   // Energy import total
            {3,  "1.0.1.8.1.255",   2, 22},   // Energy import T1
            {3,  "1.0.1.8.2.255",   2, 23},   // Energy import T2
            {3,  "1.0.1.8.3.255",   2, 24},   // Energy import T3
            {3,  "1.0.1.8.4.255",   2, 25},   // Energy import T4
            {3,  "1.0.2.8.0.255",   2, 26},   // Energy export total
            {1,  "0.0.96.13.0.255", 2, 27},   // Consumer message text
    };

    private GXSerial serial;
    private GXDLMSClient client;

    private final String port;
    private final int clientAddress;
    private final int serverAddress;
    private final Consumer<String> logger;

    private volatile boolean running = false;
    private volatile boolean connected = false;

    public OpticalDLMSClient(String port, int clientAddress, int serverAddress, Consumer<String> logger) {
        this.port = port;
        this.clientAddress = clientAddress;
        this.serverAddress = serverAddress;
        this.logger = logger;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public boolean connect() {
        log("=== Opening optical port at 300 baud 7E1 ===");
        try {
            serial = new GXSerial();
            serial.setPortName(port);
            serial.setBaudRate(BaudRate.BAUD_RATE_300);
            serial.setDataBits(7);
            serial.setParity(Parity.EVEN);
            serial.setStopBits(StopBits.ONE);
            serial.open();
            log("✓ Port opened: " + port);
            Thread.sleep(500);

            int newBaud = iecModeEHandshake();
            if (newBaud <= 0) {
                log("✗ IEC handshake failed");
                close();
                return false;
            }

            // Switch to HDLC parameters
            log("=== Switching to " + newBaud + " baud 8N1 ===");
            serial.close();
            Thread.sleep(1000);
            serial.setBaudRate(baudRateFromInt(newBaud));
            serial.setDataBits(8);
            serial.setParity(Parity.NONE);
            serial.setStopBits(StopBits.ONE);
            serial.open();
            log("✓ Switched to " + newBaud + " baud 8N1");
            Thread.sleep(1000);

            // Build DLMS client
            client = new GXDLMSClient(true, clientAddress, serverAddress,
                    Authentication.NONE, null, InterfaceType.HDLC);

            // SNRM
            log("--- SNRM Request ---");
            GXReplyData reply = new GXReplyData();
            byte[] snrm = client.snrmRequest();
            readDlmsPacket(snrm, reply);
            client.parseUAResponse(reply.getData());
            log("✓ UA response - HDLC established");

            // AARQ
            log("--- AARQ Request ---");
            reply.clear();
            readDataBlock(client.aarqRequest(), reply);
            client.parseAareResponse(reply.getData());
            log("✓ AARE response - Association established");
            log("✓✓✓ CONNECTION SUCCESSFUL ✓✓✓");

            connected = true;
            return true;

        } catch (Exception e) {
            log("✗ Connection error: " + e.getMessage());
            close();
            return false;
        }
    }

    /**
     * Read all 27 OBIS values and return them as a plain Object list,
     * matching the order of EGDObisParser.OBIS_DEFINITIONS so the same
     * parseValues() call works unchanged.
     */
    public List<Object> readAll() throws Exception {
        List<Object> values = new ArrayList<>();

        for (Object[] entry : OBIS_READ_MAP) {
            int classId   = (int) entry[0];
            String obis   = (String) entry[1];
            int attribute = (int) entry[2];
            int idx       = (int) entry[3];

            try {
                Object val = readObject(classId, obis, attribute);
                values.add(val);
                log("[" + idx + "] " + obis + " = " + val);
            } catch (Exception e) {
                log("⚠ Failed to read [" + idx + "] " + obis + ": " + e.getMessage());
                values.add(null);
            }
        }

        return values;
    }

    public void close() {
        connected = false;
        try {
            if (client != null && serial != null && serial.isOpen()) {
                GXReplyData reply = new GXReplyData();
                byte[] disc = client.disconnectRequest();
                readDlmsPacket(disc, reply);
            }
        } catch (Exception ignored) {}

        try {
            if (serial != null && serial.isOpen()) serial.close();
        } catch (Exception ignored) {}

        log("✓ Disconnected");
    }

    public boolean isConnected() { return connected; }

    // -----------------------------------------------------------------------
    // IEC 62056-21 Mode E handshake
    // -----------------------------------------------------------------------

    private int iecModeEHandshake() throws Exception {
        log("--- IEC Mode E Handshake ---");

        byte[] signOn = "/?!\r\n".getBytes("ASCII");
        log("TX sign-on: " + bytesToHex(signOn));

        ReceiveParameters<byte[]> rp = new ReceiveParameters<>(byte[].class);
        rp.setEop((byte) '\n');
        rp.setWaitTime(5000);
        rp.setAllData(false);

        boolean received;
        synchronized (serial.getSynchronous()) {
            serial.send(signOn, port);
            received = serial.receive(rp);

            if (!received || rp.getReply() == null || rp.getReply().length == 0) {
                log("✗ No IEC identification response");
                return -1;
            }

            byte[] ident = rp.getReply();
            log("RX ident: " + bytesToHex(ident) + " [" + safeAscii(ident) + "]");

            if (ident.length < 5) {
                log("⚠ Short response, defaulting to 9600");
                return 9600;
            }

            char baudChar = (char) (ident[4] & 0xFF);
            int targetBaud = parseBaudChar(baudChar);
            log("Baud char '" + baudChar + "' → " + targetBaud + " baud");

            // ACK: 0x06 + "252" + CR LF  (Mode E, 9600)
            byte[] ack = new byte[]{0x06, '2', (byte) baudChar, '2', '\r', '\n'};
            log("TX ACK: " + bytesToHex(ack));
            serial.send(ack, port);
            Thread.sleep(500);

            log("✓ IEC handshake complete");
            return targetBaud;
        }
    }

    // -----------------------------------------------------------------------
    // DLMS read helpers
    // -----------------------------------------------------------------------

    private Object readObject(int classId, String obisCode, int attribute) throws Exception {
        GXDLMSObject obj = createObject(classId, obisCode);
        byte[][] data = client.read(obj, attribute);

        GXReplyData reply = new GXReplyData();
        readDataBlock(data, reply);

        client.updateValue(obj, attribute, reply.getValue());

        return getObjectValue(obj, attribute);
    }

    private void readDataBlock(byte[][] packets, GXReplyData reply) throws Exception {
        if (packets == null) return;
        for (byte[] packet : packets) {
            readDlmsPacket(packet, reply);
        }
        while (reply.isMoreData()) {
            byte[] next = client.receiverReady(reply.getMoreData());
            readDlmsPacket(next, reply);
        }
    }

    private void readDlmsPacket(byte[] data, GXReplyData reply) throws Exception {
        if (data == null || data.length == 0) return;

        log("TX: " + bytesToHex(data));

        GXByteBuffer rd = new GXByteBuffer();
        GXReplyData notify = new GXReplyData();
        reply.setError((short) 0);

        synchronized (serial.getSynchronous()) {
            serial.send(data, port);

            int msgPos = 0;
            while (!client.getData(rd, reply, notify)) {
                if (notify.getData() != null && notify.getData().size() != 0) {
                    if (!notify.isMoreData()) notify.clear();
                    msgPos = rd.position();
                    continue;
                }
                rd.position(msgPos);

                ReceiveParameters<byte[]> rp = new ReceiveParameters<>(byte[].class);
                rp.setEop((byte) 0x7E);
                rp.setAllData(false);
                rp.setWaitTime(5000);

                if (!serial.receive(rp) || rp.getReply() == null || rp.getReply().length == 0) {
                    throw new RuntimeException("Timeout waiting for DLMS reply");
                }
                rd.set(rp.getReply());
            }
        }

        log("RX: " + bytesToHex(rd.array()));

        if (reply.getError() != 0) {
            throw new RuntimeException("DLMS error code: " + reply.getError());
        }
    }

    // -----------------------------------------------------------------------
    // Object factory
    // -----------------------------------------------------------------------

    private GXDLMSObject createObject(int classId, String obis) {
        GXDLMSObject obj;
        switch (classId) {
            case 1:  obj = new GXDLMSData(); break;
            case 3:  obj = new GXDLMSRegister(); break;
            case 4:  obj = new GXDLMSExtendedRegister(); break;
            case 40: obj = new GXDLMSPushSetup(); break;
            case 70: obj = new GXDLMSDisconnectControl(); break;
            case 71: obj = new GXDLMSLimiter(); break;
            default: obj = new GXDLMSData(); break;
        }
        obj.setLogicalName(obis);
        return obj;
    }

    private Object getObjectValue(GXDLMSObject obj, int attribute) {
        if (obj instanceof GXDLMSData)              return ((GXDLMSData) obj).getValue();
        if (obj instanceof GXDLMSRegister)          return ((GXDLMSRegister) obj).getValue();
        if (obj instanceof GXDLMSExtendedRegister)  return ((GXDLMSExtendedRegister) obj).getValue();
        if (obj instanceof GXDLMSDisconnectControl) return ((GXDLMSDisconnectControl) obj).getControlState();
        if (obj instanceof GXDLMSLimiter)           return ((GXDLMSLimiter) obj).getEmergencyProfile();
        return null;
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private static int parseBaudChar(char c) {
        switch (c) {
            case '0': return 300;
            case '1': return 600;
            case '2': return 1200;
            case '3': return 2400;
            case '4': return 4800;
            case '5': return 9600;
            case '6': return 19200;
            case '7': return 38400;
            case '8': return 57600;
            case '9': return 115200;
            default:  return 9600;
        }
    }

    private static BaudRate baudRateFromInt(int baud) {
        switch (baud) {
            case 300:    return BaudRate.BAUD_RATE_300;
            case 600:    return BaudRate.BAUD_RATE_600;
            case 1800:   return BaudRate.BAUD_RATE_1800;
            case 2400:   return BaudRate.BAUD_RATE_2400;
            case 4800:   return BaudRate.BAUD_RATE_4800;
            case 9600:   return BaudRate.BAUD_RATE_9600;
            case 19200:  return BaudRate.BAUD_RATE_19200;
            case 38400:  return BaudRate.BAUD_RATE_38400;
            case 57600:  return BaudRate.BAUD_RATE_57600;
            case 115200: return BaudRate.BAUD_RATE_115200;
            default:     return BaudRate.BAUD_RATE_9600;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }

    private static String safeAscii(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append((b >= 32 && b < 127) ? (char) b : '.');
        }
        return sb.toString();
    }

    private void log(String msg) {
        System.out.println("[OPTICAL] " + msg);
        if (logger != null) logger.accept(msg);
    }
}