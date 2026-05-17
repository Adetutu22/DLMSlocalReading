package com.meter.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for EGD meter push data using 27 OBIS codes.
 * Each entry in OBIS_DEFINITIONS maps an index to its OBIS code, name, class, and unit.
 */
public class EGDObisParser {

    public static final String[][] OBIS_DEFINITIONS = {
            {"1", "COSEM logical device name", "1, 0-0:42.0.0.255", "2", ""},
            {"2", "Push setup - on schedule 2", "40, 0-2:25.9.0.255", "1", ""},
            {"3", "Serial number", "1, 0-0:96.1.0.255", "2", ""},
            {"4", "Disconnect status", "70, 0-0:96.3.10.255", "3", ""},
            {"5", "Power limiter value", "71, 0-0:17.0.0.255", "3", "W"},
            {"6", "Relay 1 status", "70, 0-1:96.3.10.255", "3", ""},
            {"7", "Relay 2 status", "70, 0-2:96.3.10.255", "3", ""},
            {"8", "Relay 3 status", "70, 0-3:96.3.10.255", "3", ""},
            {"9", "Relay 4 status", "70, 0-4:96.3.10.255", "3", ""},
            {"10", "Relay 5 status", "70, 0-5:96.3.10.255", "3", ""},
            {"11", "Relay 6 status", "70, 0-6:96.3.10.255", "3", ""},
            {"12", "Currently Active Energy Tariff", "1, 0-0:96.14.0.255", "2", ""},
            {"13", "Instantaneous active power import (+A)", "3, 1-0:1.7.0.255", "2", "W"},
            {"14", "Instantaneous active power import (+A) L1", "3, 1-0:21.7.0.255", "2", "W"},
            {"15", "Instantaneous active power import (+A) L2", "3, 1-0:41.7.0.255", "2", "W"},
            {"16", "Instantaneous active power import (+A) L3", "3, 1-0:61.7.0.255", "2", "W"},
            {"17", "Instantaneous active power export (-A)", "3, 1-0:2.7.0.255", "2", "W"},
            {"18", "Instantaneous active power export (-A) L1", "3, 1-0:22.7.0.255", "2", "W"},
            {"19", "Instantaneous active power export (-A) L2", "3, 1-0:42.7.0.255", "2", "W"},
            {"20", "Instantaneous active power export (-A) L3", "3, 1-0:62.7.0.255", "2", "W"},
            {"21", "Cumulative active import energy (+A)", "3, 1-0:1.8.0.255", "2", "Wh"},
            {"22", "Cumulative active import energy (+A) rate 1", "3, 1-0:1.8.1.255", "2", "Wh"},
            {"23", "Cumulative active import energy (+A) rate 2", "3, 1-0:1.8.2.255", "2", "Wh"},
            {"24", "Cumulative active import energy (+A) rate 3", "3, 1-0:1.8.3.255", "2", "Wh"},
            {"25", "Cumulative active import energy (+A) rate 4", "3, 1-0:1.8.4.255", "2", "Wh"},
            {"26", "Cumulative active export energy (-A)", "3, 1-0:2.8.0.255", "2", "Wh"},
            {"27", "Consumer message text", "1, 0-0:96.13.0.255", "2", ""}
    };

    /**
     * Represents a single parsed meter reading, combining metadata from OBIS_DEFINITIONS
     * with a concrete value decoded from the device's data push.
     */
    public static class Reading {
        public int index;
        public String name;
        public String obisCode;
        public Object value;
        public String unit;

        // Constructs a new Reading with all fields.
        public Reading(int index, String name, String obisCode, Object value, String unit) {
            this.index = index;
            this.name = name;
            this.obisCode = obisCode;
            this.value = value;
            this.unit = unit;
        }

        // Formatted string representation of this reading
        @Override
        public String toString() {
            return String.format("[%d] %s = %s %s", index, name, value, unit);
        }
    }

    /**
     * Parses a list of raw values received from the meter into structured Reading objects.
     * Values are matched positionally against OBIS_DEFINITIONS. Parsing stops at
     * whichever limit is reached first: the end of values or the end of OBIS_DEFINITIONS.
     *
     * @param values raw values from the meter data push (may contain numeric types or byte arrays)
     * @return list of parsed Reading objects; empty list if values is null or empty
     */
    public static List<Reading> parseValues(List<?> values) {
        List<Reading> readings = new ArrayList<>();

        if (values == null || values.isEmpty()) {
            return readings;
        }

        System.out.println("[PARSER] Parsing " + values.size() + " values...");

        for (int i = 0; i < values.size() && i < OBIS_DEFINITIONS.length; i++) {
            Object val = values.get(i);
            String[] def = OBIS_DEFINITIONS[i];

            int index = Integer.parseInt(def[0]);
            String name = def[1];
            String obis = def[2];
            String unit = def[4];

            if (val instanceof byte[]) {
                val = bytesToString((byte[]) val);
            }

            Reading reading = new Reading(index, name, obis, val, unit);
            readings.add(reading);

            System.out.println("[PARSER] " + reading);
        }

        return readings;
    }

    // Converts a byte array to a human-readable string.
    private static String bytesToString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        boolean isAscii = true;

        for (byte b : bytes) {
            if (b >= 32 && b < 127) {
                // Printable ASCII character — append directly
                sb.append((char) b);
            } else if (b == 0) {
                // Null byte — skip silently (common padding in fixed-length fields)
            } else {
                // Non-printable, non-null byte found — fall back to hex output
                isAscii = false;
                break;
            }
        }

        if (isAscii && sb.length() > 0) {
            return sb.toString();
        }

        // Build hex dump (e.g., "4D 45 54 45 52")
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02X ", b));
        }
        return hex.toString().trim();
    }

    /**
     * Extracts instantaneous power readings (import and export, total and per phase)
     * from a list of parsed readings.
     */
    public static Map<String, Object> getPowerReadings(List<Reading> readings) {
        Map<String, Object> power = new LinkedHashMap<>();

        for (Reading r : readings) {
            switch (r.index) {
                case 13: power.put("powerImport", r.value); break;
                case 14: power.put("powerL1", r.value); break;
                case 15: power.put("powerL2", r.value); break;
                case 16: power.put("powerL3", r.value); break;
                case 17: power.put("powerExport", r.value); break;
                case 18: power.put("exportL1", r.value); break;
                case 19: power.put("exportL2", r.value); break;
                case 20: power.put("exportL3", r.value); break;
            }
        }

        return power;
    }

    /**
     * Extracts cumulative energy readings (import and export, total and per tariff rate)
     * from a list of parsed readings.
     */
    public static Map<String, Object> getEnergyReadings(List<Reading> readings) {
        Map<String, Object> energy = new LinkedHashMap<>();

        for (Reading r : readings) {
            switch (r.index) {
                case 21: energy.put("energyImport", r.value); break;
                case 22: energy.put("energyT1", r.value); break;
                case 23: energy.put("energyT2", r.value); break;
                case 24: energy.put("energyT3", r.value); break;
                case 25: energy.put("energyT4", r.value); break;
                case 26: energy.put("energyExport", r.value); break;
            }
        }

        return energy;
    }
}