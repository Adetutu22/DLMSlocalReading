package com.meter.database;

import com.meter.parser.EGDObisParser;

import java.sql.*;
import java.util.List;

public class SqlLiteDB implements AutoCloseable {

    /* Connection to the database */
    private final Connection conn;

    /* Initialize database */
    public SqlLiteDB(String dbFile) throws SQLException {
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        this.conn.setAutoCommit(true);
        createSchema();
    }

    public Connection connection() {
        return conn;
    }

    /*
    * Create SQL table in database for push message (27 values) and timestamp.
    **/
    private void createSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {

            st.execute("""
                CREATE TABLE IF NOT EXISTS push_data (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,

                  push_ts TEXT NOT NULL,

                  cosem_ldn TEXT,
                  push_setup_schedule TEXT,
                  device_serial TEXT NOT NULL,
                  consumer_message TEXT,

                  disconnect_status INTEGER,
                  power_limiter_w INTEGER,

                  relay1_status INTEGER,
                  relay2_status INTEGER,
                  relay3_status INTEGER,
                  relay4_status INTEGER,
                  relay5_status INTEGER,
                  relay6_status INTEGER,

                  active_energy_tariff INTEGER,

                  p_import_w INTEGER,
                  p_import_l1_w INTEGER,
                  p_import_l2_w INTEGER,
                  p_import_l3_w INTEGER,

                  p_export_w INTEGER,
                  p_export_l1_w INTEGER,
                  p_export_l2_w INTEGER,
                  p_export_l3_w INTEGER,

                  e_import_wh INTEGER,
                  e_import_r1_wh INTEGER,
                  e_import_r2_wh INTEGER,
                  e_import_r3_wh INTEGER,
                  e_import_r4_wh INTEGER,

                  e_export_wh INTEGER,

                  UNIQUE(device_serial, push_ts)
                );
            """);
            // Index to search for serial number with timestamp
            st.execute("""
                CREATE INDEX IF NOT EXISTS idx_push_serial_ts
                ON push_data(device_serial, push_ts);
            """);
        }
    }

    // ------------------------------------------------------------------------
    // Insert one full push (27 OBIS values)
    // ------------------------------------------------------------------------

    public void insertPush(String pushTs, List<EGDObisParser.Reading> readings) throws SQLException {
        if (readings == null || readings.size() < 27) {
            throw new IllegalArgumentException("Expected 27 readings");
        }

        // Helper to fetch by index (your Reading.index is 1..27)
        EGDObisParser.Reading[] r = new EGDObisParser.Reading[28];
        for (EGDObisParser.Reading it : readings) {
            if (it.index >= 1 && it.index <= 27) r[it.index] = it;
        }

        String deviceSerial  = asText(r[3] != null ? r[3].value : null);      // Serial number
        String cosemLdn      = asText(r[1] != null ? r[1].value : null);      // COSEM logical device name
        String pushSetup     = asText(r[2] != null ? r[2].value : null);      // Push setup
        String message       = asText(r[27] != null ? r[27].value : null);    // Consumer message
        String activeTariff  = asText(r[12] != null ? r[12].value : null);    // Currently Active Energy Tariff
        String upperTariff = activeTariff != null ? activeTariff.toUpperCase() : null;

        String sql = """
        INSERT OR IGNORE INTO push_data (
          push_ts,
          cosem_ldn, push_setup_schedule, device_serial, consumer_message,
          disconnect_status, power_limiter_w,
          relay1_status, relay2_status, relay3_status, relay4_status, relay5_status, relay6_status,
          active_energy_tariff,
          p_import_w, p_import_l1_w, p_import_l2_w, p_import_l3_w,
          p_export_w, p_export_l1_w, p_export_l2_w, p_export_l3_w,
          e_import_wh, e_import_r1_wh, e_import_r2_wh, e_import_r3_wh, e_import_r4_wh,
          e_export_wh
        ) VALUES (
          ?,
          ?, ?, ?, ?,
          ?, ?,
          ?, ?, ?, ?, ?, ?,
          ?,
          ?, ?, ?, ?,
          ?, ?, ?, ?,
          ?, ?, ?, ?, ?,
          ?
        );
    """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, pushTs);

            ps.setString(i++, cosemLdn);
            ps.setString(i++, pushSetup);
            ps.setString(i++, deviceSerial);
            ps.setString(i++, message);

            setInt(ps, i++, r[4] != null ? r[4].value : null);
            setInt(ps, i++, r[5] != null ? r[5].value : null);

            setInt(ps, i++, r[6] != null ? r[6].value : null);
            setInt(ps, i++, r[7] != null ? r[7].value : null);
            setInt(ps, i++, r[8] != null ? r[8].value : null);
            setInt(ps, i++, r[9] != null ? r[9].value : null);
            setInt(ps, i++, r[10] != null ? r[10].value : null);
            setInt(ps, i++, r[12] != null ? r[11].value : null);

            ps.setString(i++, upperTariff);

            setInt(ps, i++, r[13] != null ? r[13].value : null);
            setInt(ps, i++, r[14] != null ? r[14].value : null);
            setInt(ps, i++, r[15] != null ? r[15].value : null);
            setInt(ps, i++, r[16] != null ? r[16].value : null);

            setInt(ps, i++, r[17] != null ? r[17].value : null);
            setInt(ps, i++, r[18] != null ? r[18].value : null);
            setInt(ps, i++, r[19] != null ? r[19].value : null);
            setInt(ps, i++, r[20] != null ? r[20].value : null);

            setLong(ps, i++, r[21] != null ? r[21].value : null);
            setLong(ps, i++, r[22] != null ? r[22].value : null);
            setLong(ps, i++, r[23] != null ? r[23].value : null);
            setLong(ps, i++, r[24] != null ? r[24].value : null);
            setLong(ps, i++, r[25] != null ? r[25].value : null);

            setLong(ps, i++, r[26] != null ? r[26].value : null);

            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------------
    // HELPERS - set object as datatype
    // ------------------------------------------------------------------------

    private static void setInt(PreparedStatement ps, int idx, Object o) throws SQLException {
        if (o == null) {
            ps.setNull(idx, Types.INTEGER);
        } else if (o instanceof Number) {
            ps.setInt(idx, ((Number) o).intValue());
        } else {
            try {
                ps.setInt(idx, Integer.parseInt(o.toString()));
            } catch (Exception e) {
                ps.setNull(idx, Types.INTEGER);
            }
        }
    }

    private static void setLong(PreparedStatement ps, int idx, Object o) throws SQLException {
        if (o == null) {
            ps.setNull(idx, Types.BIGINT);
        } else if (o instanceof Number) {
            ps.setLong(idx, ((Number) o).longValue());
        } else {
            try {
                ps.setLong(idx, Long.parseLong(o.toString()));
            } catch (Exception e) {
                ps.setNull(idx, Types.BIGINT);
            }
        }
    }

    private static String asText(Object o) {
        return o == null ? null : o.toString();
    }

    @Override
    public void close() throws SQLException {
        if (conn != null) conn.close();
    }
}
