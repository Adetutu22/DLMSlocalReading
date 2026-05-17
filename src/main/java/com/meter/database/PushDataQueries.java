package com.meter.database;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class PushDataQueries {
    private final Connection conn;

    public record HourlyEnergyPoint(long hourMsUtc, double kwh) {}
    public record InstantPowerPoint(long tsMsUtc, long watts) {}
    public record TimedLongPoint(long tsMsUtc, long value) {}
    public record TimedValuePoint(long tsMsUtc, double value) {}

    /** One bar in a monthly or yearly energy chart. */
    public record PeriodEnergyPoint(
            int year,
            int month,   // 1–12, or 0 when this is a yearly total
            double kwh
    ) {}

    /**
     * One bar in a weekly energy chart.
     * weekOfMonth is 1-based (1 = first week of the month).
     * label is a range like "1–7 Apr".
     */
    public record WeeklyEnergyPoint(
            int weekOfMonth,
            String label,
            double kwh
    ) {}

    public PushDataQueries(Connection conn) {
        this.conn = conn;
    }

    // =========================================================================
    // Weekly energy aggregation
    // =========================================================================

    /**
     * Returns imported energy grouped into calendar weeks (Mon–Sun) that
     * overlap with the given year/month.
     */
    public List<WeeklyEnergyPoint> getWeeklyImportEnergy(
            String serial, int year, int month
    ) throws SQLException {
        return getWeeklyEnergy(serial, year, month, "e_import_wh");
    }

    /** Returns exported energy grouped into calendar weeks for the given month. */
    public List<WeeklyEnergyPoint> getWeeklyExportEnergy(
            String serial, int year, int month
    ) throws SQLException {
        return getWeeklyEnergy(serial, year, month, "e_export_wh");
    }

    /**
     * Groups push_data rows into ISO-style Monday-anchored weeks that overlap
     * with the requested calendar month. Weeks are numbered 1...N in
     * month-order; each record carries a readable date-range label.
     *
     * All weeks are always returned, even when a week has no data —
     * those slots are filled with kwh = 0.0 so the chart always shows
     * every bar.
     */
    private List<WeeklyEnergyPoint> getWeeklyEnergy(
            String serial, int year, int month, String col
    ) throws SQLException {
        LocalDate monthStart = LocalDate.of(year, month, 1);
        LocalDate monthEnd   = monthStart.withDayOfMonth(monthStart.lengthOfMonth());

        // ── Step 1: enumerate all week slots for this month ───────────────────
        // A slot's Monday is the first Monday on or before each day of the month.
        // Collect unique Mondays in order.
        List<LocalDate> weekMondays = new ArrayList<>();
        {
            // Walk day-by-day through the month; record each new Monday
            LocalDate day = monthStart;
            while (!day.isAfter(monthEnd)) {
                // Monday of the ISO week containing 'day'
                LocalDate mon = day.minusDays(day.getDayOfWeek().getValue() - 1); // DayOfWeek.MONDAY == 1
                if (!weekMondays.contains(mon)) weekMondays.add(mon);
                day = day.plusDays(1);
            }
        }

        // ── Step 2: query DB for whatever data exists ─────────────────────────
        String sql = """
            WITH base AS (
              SELECT
                date(datetime(push_ts),
                     '-' || ((CAST(strftime('%%w', datetime(push_ts)) AS INTEGER) + 6) %% 7)
                     || ' days') AS week_mon,
                %s AS val
              FROM push_data
              WHERE device_serial = ?
                AND datetime(push_ts) >= datetime(?)
                AND datetime(push_ts) <  datetime(?)
                AND %s IS NOT NULL
            )
            SELECT
              week_mon,
              (MAX(val) - MIN(val)) / 1000.0 AS kwh
            FROM base
            GROUP BY week_mon
            ORDER BY week_mon;
            """.formatted(col, col);

        // Map week-Monday string -> kwh from DB
        java.util.Map<String, Double> dbData = new java.util.HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            // Query window covers all weeks that touch the month
            LocalDate queryFrom = weekMondays.get(0);
            LocalDate queryTo   = weekMondays.get(weekMondays.size() - 1).plusDays(7);

            ps.setString(1, serial);
            ps.setString(2, queryFrom.atStartOfDay().toString().replace('T', ' '));
            ps.setString(3, queryTo.atStartOfDay().toString().replace('T', ' '));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    dbData.put(rs.getString("week_mon"), rs.getDouble("kwh"));
                }
            }
        }

        // ── Step 3: merge — every slot gets a value, missing ones are 0 ───────
        List<WeeklyEnergyPoint> out = new ArrayList<>();
        int weekIdx = 1;
        for (LocalDate mon : weekMondays) {
            LocalDate sun = mon.plusDays(6);
            String label  = buildWeekLabel(mon.toString(), sun.toString(), monthStart, monthEnd);
            double kwh    = dbData.getOrDefault(mon.toString(), 0.0);
            out.add(new WeeklyEnergyPoint(weekIdx++, label, kwh));
        }
        return out;
    }

    /**
     * Formats a week label like "1-7 Apr" or "29 Apr-5 May", clipping to the
     * month boundary when the week partially overlaps an adjacent month.
     */
    private static String buildWeekLabel(
            String weekMonStr, String weekSunStr,
            LocalDate monthStart, LocalDate monthEnd
    ) {
        LocalDate mon = LocalDate.parse(weekMonStr);
        LocalDate sun = LocalDate.parse(weekSunStr);

        LocalDate dispStart = mon.isBefore(monthStart) ? monthStart : mon;
        LocalDate dispEnd   = sun.isAfter(monthEnd)    ? monthEnd   : sun;

        String[] MONTHS = {"","Jan","Feb","Mar","Apr","May","Jun",
                "Jul","Aug","Sep","Oct","Nov","Dec"};

        int startM = dispStart.getMonthValue();
        int endM   = dispEnd.getMonthValue();

        if (startM == endM) {
            return dispStart.getDayOfMonth() + "\u2013" + dispEnd.getDayOfMonth()
                    + " " + MONTHS[startM];
        } else {
            return dispStart.getDayOfMonth() + " " + MONTHS[startM]
                    + "\u2013" + dispEnd.getDayOfMonth() + " " + MONTHS[endM];
        }
    }

    // =========================================================================
    // Monthly energy aggregation
    // =========================================================================

    public List<PeriodEnergyPoint> getMonthlyImportEnergy(
            String serial, LocalDate from, LocalDate to
    ) throws SQLException {
        return getMonthlyEnergy(serial, from, to, "e_import_wh");
    }

    public List<PeriodEnergyPoint> getMonthlyExportEnergy(
            String serial, LocalDate from, LocalDate to
    ) throws SQLException {
        return getMonthlyEnergy(serial, from, to, "e_export_wh");
    }

    private List<PeriodEnergyPoint> getMonthlyEnergy(
            String serial, LocalDate from, LocalDate to, String col
    ) throws SQLException {
        String sql = """
            WITH t AS (
              SELECT
                CAST(strftime('%%Y', datetime(push_ts)) AS INTEGER) AS yr,
                CAST(strftime('%%m', datetime(push_ts)) AS INTEGER) AS mo,
                %s AS val
              FROM push_data
              WHERE device_serial = ?
                AND datetime(push_ts) >= datetime(?)
                AND datetime(push_ts) < datetime(?)
                AND %s IS NOT NULL
            )
            SELECT yr, mo,
                   (MAX(val) - MIN(val)) / 1000.0 AS kwh
            FROM t
            GROUP BY yr, mo
            ORDER BY yr, mo;
            """.formatted(col, col);

        List<PeriodEnergyPoint> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindDateRange(ps, serial, from, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PeriodEnergyPoint(
                            rs.getInt("yr"),
                            rs.getInt("mo"),
                            rs.getDouble("kwh")
                    ));
                }
            }
        }
        return out;
    }

    // =========================================================================
    // Yearly energy aggregation
    // =========================================================================

    public List<PeriodEnergyPoint> getYearlyImportEnergy(
            String serial, LocalDate from, LocalDate to
    ) throws SQLException {
        return getYearlyEnergy(serial, from, to, "e_import_wh");
    }

    public List<PeriodEnergyPoint> getYearlyExportEnergy(
            String serial, LocalDate from, LocalDate to
    ) throws SQLException {
        return getYearlyEnergy(serial, from, to, "e_export_wh");
    }

    private List<PeriodEnergyPoint> getYearlyEnergy(
            String serial, LocalDate from, LocalDate to, String col
    ) throws SQLException {
        String sql = """
            WITH t AS (
              SELECT
                CAST(strftime('%%Y', datetime(push_ts)) AS INTEGER) AS yr,
                %s AS val
              FROM push_data
              WHERE device_serial = ?
                AND datetime(push_ts) >= datetime(?)
                AND datetime(push_ts) < datetime(?)
                AND %s IS NOT NULL
            )
            SELECT yr,
                   (MAX(val) - MIN(val)) / 1000.0 AS kwh
            FROM t
            GROUP BY yr
            ORDER BY yr;
            """.formatted(col, col);

        List<PeriodEnergyPoint> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindDateRange(ps, serial, from, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PeriodEnergyPoint(
                            rs.getInt("yr"),
                            0,
                            rs.getDouble("kwh")
                    ));
                }
            }
        }
        return out;
    }

    // =========================================================================
    // Hourly absolute register value
    // =========================================================================

    public List<HourlyEnergyPoint> getHourlyImportRegisterBetween(
            String serial, LocalDate from, LocalDate to
    ) throws SQLException {
        return getHourlyMaxRegister(serial, from, to, "e_import_wh");
    }

    public List<HourlyEnergyPoint> getHourlyExportRegisterBetween(
            String serial, LocalDate from, LocalDate to
    ) throws SQLException {
        return getHourlyMaxRegister(serial, from, to, "e_export_wh");
    }

    private List<HourlyEnergyPoint> getHourlyMaxRegister(
            String serial, LocalDate from, LocalDate to, String col
    ) throws SQLException {
        String sql = """
        WITH t AS (
          SELECT datetime(push_ts) AS ts,
                 %s AS e_wh
          FROM push_data
          WHERE device_serial = ?
            AND datetime(push_ts) >= datetime(?)
            AND datetime(push_ts) < datetime(?)
            AND %s IS NOT NULL
        )
        SELECT
          (strftime('%%s', substr(ts, 1, 13) || ':00:00') * 1000) AS hour_ms,
          MAX(e_wh) / 1000.0 AS kwh
        FROM t
        GROUP BY substr(ts, 1, 13)
        ORDER BY hour_ms;
        """.formatted(col, col);

        List<HourlyEnergyPoint> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindDateRange(ps, serial, from, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new HourlyEnergyPoint(
                            rs.getLong("hour_ms"),
                            rs.getDouble("kwh")
                    ));
                }
            }
        }
        return out;
    }

    // =========================================================================
    // Hourly export energy
    // =========================================================================

    public List<HourlyEnergyPoint> getHourlyExportEnergyBetween(String serial, LocalDate from, LocalDate to) throws SQLException {
        String sql = """
        WITH t AS (
          SELECT datetime(push_ts) AS ts, e_export_wh
          FROM push_data
          WHERE device_serial = ?
            AND datetime(push_ts) >= datetime(?)
            AND datetime(push_ts) < datetime(?)
            AND e_export_wh IS NOT NULL
        )
        SELECT
          (strftime('%s', substr(ts, 1, 13) || ':00:00') * 1000) AS hour_ms,
          (MAX(e_export_wh) - MIN(e_export_wh)) / 1000.0 AS kwh
        FROM t
        GROUP BY substr(ts, 1, 13)
        ORDER BY hour_ms;
        """;

        List<HourlyEnergyPoint> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindDateRange(ps, serial, from, to);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new HourlyEnergyPoint(rs.getLong("hour_ms"), rs.getDouble("kwh")));
                }
            }
        }
        return out;
    }

    // =========================================================================
    // Hourly import energy
    // =========================================================================

    public List<HourlyEnergyPoint> getHourlyImportEnergyBetween(String serial, LocalDate from, LocalDate to, String tariff) throws SQLException {
        String col = importEnergyColumnForTariff(tariff);

        String sql = """
        WITH t AS (
          SELECT datetime(push_ts) AS ts, %s AS e_wh
          FROM push_data
          WHERE device_serial = ?
            AND datetime(push_ts) >= datetime(?)
            AND datetime(push_ts) < datetime(?)
            AND %s IS NOT NULL
        )
        SELECT
          (strftime('%%s', substr(ts, 1, 13) || ':00:00') * 1000) AS hour_ms,
          (MAX(e_wh) - MIN(e_wh)) / 1000.0 AS kwh
        FROM t
        GROUP BY substr(ts, 1, 13)
        ORDER BY hour_ms;
        """.formatted(col, col);

        List<HourlyEnergyPoint> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindDateRange(ps, serial, from, to);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new HourlyEnergyPoint(rs.getLong("hour_ms"), rs.getDouble("kwh")));
                }
            }
        }
        return out;
    }

    // =========================================================================
    // Average helpers
    // =========================================================================

    public Double getAvgImportPowerInstant(String serial, LocalDate from, LocalDate to) throws SQLException {
        return queryAvgDoubleColumn("p_import_w", serial, from, to);
    }

    public Double getAvgExportPowerInstant(String serial, LocalDate from, LocalDate to) throws SQLException {
        return queryAvgDoubleColumn("p_export_w", serial, from, to);
    }

    public Double getAvgImportPowerInstantPhase(String serial, LocalDate from, LocalDate to, int phase) throws SQLException {
        return queryAvgDoubleColumn(importPowerPhaseColumn(phase), serial, from, to);
    }

    public Double getAvgExportPowerInstantPhase(String serial, LocalDate from, LocalDate to, int phase) throws SQLException {
        return queryAvgDoubleColumn(exportPowerPhaseColumn(phase), serial, from, to);
    }

    private Double queryAvgDoubleColumn(String column, String serial, LocalDate from, LocalDate to) throws SQLException {
        String sql = """
        SELECT AVG(%s)
        FROM push_data
        WHERE device_serial = ?
          AND datetime(push_ts) >= datetime(?)
          AND datetime(push_ts) < datetime(?)
          AND %s IS NOT NULL;
    """.formatted(column, column);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindDateRange(ps, serial, from, to);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return 0.0;
                double v = rs.getDouble(1);
                return rs.wasNull() ? 0.0 : v;
            }
        }
    }

    public Double getAvgHourlyExportEnergyBetween(String serial, LocalDate from, LocalDate to) throws SQLException {
        String sql = """
        WITH hourly AS (
          SELECT (MAX(e_export_wh) - MIN(e_export_wh)) / 1000.0 AS kwh
          FROM push_data
          WHERE device_serial = ?
            AND datetime(push_ts) >= datetime(?)
            AND datetime(push_ts) < datetime(?)
            AND e_export_wh IS NOT NULL
          GROUP BY substr(datetime(push_ts), 1, 13)
        )
        SELECT AVG(kwh) FROM hourly;
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindDateRange(ps, serial, from, to);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return 0.0;
                double v = rs.getDouble(1);
                return rs.wasNull() ? 0.0 : v;
            }
        }
    }

    public Double getAvgHourlyImportEnergyBetween(String serial, LocalDate from, LocalDate to, String tariff) throws SQLException {
        String col = importEnergyColumnForTariff(tariff);

        String sql = """
        WITH hourly AS (
          SELECT (MAX(%s) - MIN(%s)) / 1000.0 AS kwh
          FROM push_data
          WHERE device_serial = ?
            AND datetime(push_ts) >= datetime(?)
            AND datetime(push_ts) < datetime(?)
            AND %s IS NOT NULL
          GROUP BY substr(datetime(push_ts), 1, 13)
        )
        SELECT AVG(kwh) FROM hourly;
        """.formatted(col, col, col);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindDateRange(ps, serial, from, to);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return 0.0;
                double v = rs.getDouble(1);
                return rs.wasNull() ? 0.0 : v;
            }
        }
    }

    // =========================================================================
    // Min / Max helpers
    // =========================================================================

    public TimedValuePoint getMinImportPowerTotal(String serial, LocalDate from, LocalDate to) throws SQLException {
        return queryMinHourlyEnergyPoint("e_import_wh", serial, from, to);
    }

    public TimedValuePoint getMaxImportPowerTotal(String serial, LocalDate from, LocalDate to) throws SQLException {
        return queryMaxHourlyEnergyPoint("e_import_wh", serial, from, to);
    }

    public TimedValuePoint getMinExportPowerTotal(String serial, LocalDate from, LocalDate to) throws SQLException {
        return queryMinHourlyEnergyPoint("e_export_wh", serial, from, to);
    }

    public TimedValuePoint getMaxExportPowerTotal(String serial, LocalDate from, LocalDate to) throws SQLException {
        return queryMaxHourlyEnergyPoint("e_export_wh", serial, from, to);
    }

    public TimedLongPoint getMinImportPowerInstant(String serial, LocalDate from, LocalDate to) throws SQLException {
        return queryMinTimedLongPoint("p_import_w", serial, from, to);
    }

    public TimedLongPoint getMaxImportPowerInstant(String serial, LocalDate from, LocalDate to) throws SQLException {
        return queryMaxTimedLongPoint("p_import_w", serial, from, to);
    }

    public TimedLongPoint getMinExportPowerInstant(String serial, LocalDate from, LocalDate to) throws SQLException {
        return queryMinTimedLongPoint("p_export_w", serial, from, to);
    }

    public TimedLongPoint getMaxExportPowerInstant(String serial, LocalDate from, LocalDate to) throws SQLException {
        return queryMaxTimedLongPoint("p_export_w", serial, from, to);
    }

    private TimedLongPoint queryMinTimedLongPoint(String column, String serial, LocalDate from, LocalDate to) throws SQLException {
        String sql = """
        SELECT
          (strftime('%%s', datetime(push_ts)) * 1000) AS ts_ms,
          %s AS val
        FROM push_data
        WHERE device_serial = ?
          AND datetime(push_ts) >= datetime(?)
          AND datetime(push_ts) < datetime(?)
          AND %s IS NOT NULL
          AND %s = (
              SELECT MIN(%s)
              FROM push_data
              WHERE device_serial = ?
                AND datetime(push_ts) >= datetime(?)
                AND datetime(push_ts) < datetime(?)
                AND %s IS NOT NULL
          )
        ORDER BY datetime(push_ts) ASC LIMIT 1;
    """.formatted(column, column, column, column, column, column);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serial);
            ps.setString(2, from.atStartOfDay().toString());
            ps.setString(3, to.plusDays(1).atStartOfDay().toString());
            ps.setString(4, serial);
            ps.setString(5, from.atStartOfDay().toString());
            ps.setString(6, to.plusDays(1).atStartOfDay().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new TimedLongPoint(rs.getLong("ts_ms"), rs.getLong("val"));
            }
        }
    }

    private TimedLongPoint queryMaxTimedLongPoint(String column, String serial, LocalDate from, LocalDate to) throws SQLException {
        String sql = """
        SELECT
          (strftime('%%s', datetime(push_ts)) * 1000) AS ts_ms,
          %s AS val
        FROM push_data
        WHERE device_serial = ?
          AND datetime(push_ts) >= datetime(?)
          AND datetime(push_ts) < datetime(?)
          AND %s IS NOT NULL
          AND %s = (
              SELECT MAX(%s)
              FROM push_data
              WHERE device_serial = ?
                AND datetime(push_ts) >= datetime(?)
                AND datetime(push_ts) < datetime(?)
                AND %s IS NOT NULL
          )
        ORDER BY datetime(push_ts) ASC LIMIT 1;
    """.formatted(column, column, column, column, column, column);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serial);
            ps.setString(2, from.atStartOfDay().toString());
            ps.setString(3, to.plusDays(1).atStartOfDay().toString());
            ps.setString(4, serial);
            ps.setString(5, from.atStartOfDay().toString());
            ps.setString(6, to.plusDays(1).atStartOfDay().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new TimedLongPoint(rs.getLong("ts_ms"), rs.getLong("val"));
            }
        }
    }

    private TimedValuePoint queryMinHourlyEnergyPoint(String energyColumn, String serial, LocalDate from, LocalDate to) throws SQLException {
        String sql = """
        WITH hourly AS (
          SELECT
            (strftime('%%s', substr(datetime(push_ts), 1, 13) || ':00:00') * 1000) AS hour_ms,
            (MAX(%s) - MIN(%s)) / 1000.0 AS kwh
          FROM push_data
          WHERE device_serial = ?
            AND datetime(push_ts) >= datetime(?)
            AND datetime(push_ts) < datetime(?)
            AND %s IS NOT NULL
          GROUP BY substr(datetime(push_ts), 1, 13)
        )
        SELECT hour_ms, kwh FROM hourly
        WHERE kwh = (SELECT MIN(kwh) FROM hourly)
        ORDER BY hour_ms ASC LIMIT 1;
    """.formatted(energyColumn, energyColumn, energyColumn);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindDateRange(ps, serial, from, to);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new TimedValuePoint(rs.getLong("hour_ms"), rs.getDouble("kwh"));
            }
        }
    }

    private TimedValuePoint queryMaxHourlyEnergyPoint(String energyColumn, String serial, LocalDate from, LocalDate to) throws SQLException {
        String sql = """
        WITH hourly AS (
          SELECT
            (strftime('%%s', substr(datetime(push_ts), 1, 13) || ':00:00') * 1000) AS hour_ms,
            (MAX(%s) - MIN(%s)) / 1000.0 AS kwh
          FROM push_data
          WHERE device_serial = ?
            AND datetime(push_ts) >= datetime(?)
            AND datetime(push_ts) < datetime(?)
            AND %s IS NOT NULL
          GROUP BY substr(datetime(push_ts), 1, 13)
        )
        SELECT hour_ms, kwh FROM hourly
        WHERE kwh = (SELECT MAX(kwh) FROM hourly)
        ORDER BY hour_ms ASC LIMIT 1;
    """.formatted(energyColumn, energyColumn, energyColumn);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindDateRange(ps, serial, from, to);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new TimedValuePoint(rs.getLong("hour_ms"), rs.getDouble("kwh"));
            }
        }
    }

    // =========================================================================
    // Instantaneous power — import
    // =========================================================================

    public List<InstantPowerPoint> getInstantImportPowerByPhaseBetween(String serial, LocalDate from, LocalDate to, int phase) throws SQLException {
        String col = importPowerPhaseColumn(phase);

        String sql = """
            SELECT
              (strftime('%%s', datetime(push_ts)) * 1000) AS ts_ms,
              %s AS watts
            FROM push_data
            WHERE device_serial = ?
              AND datetime(push_ts) >= datetime(?)
              AND datetime(push_ts) < datetime(?)
              AND %s IS NOT NULL
            ORDER BY datetime(push_ts);
        """.formatted(col, col);

        List<InstantPowerPoint> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindDateRange(ps, serial, from, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new InstantPowerPoint(rs.getLong("ts_ms"), rs.getLong("watts")));
                }
            }
        }
        return out;
    }

    public List<InstantPowerPoint> getInstantImportPowerTotalBetween(String serial, LocalDate from, LocalDate to, String tariff) throws SQLException {
        String sql = """
            SELECT
              CAST(strftime('%s', datetime(push_ts)) AS INTEGER) * 1000 AS ts_ms,
              p_import_w AS watts
            FROM push_data
            WHERE device_serial = ?
              AND datetime(push_ts) >= datetime(?)
              AND datetime(push_ts) < datetime(?)
              AND p_import_w IS NOT NULL
              AND active_energy_tariff = ?
            ORDER BY datetime(push_ts)
        """;

        List<InstantPowerPoint> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serial);
            ps.setString(2, from.atStartOfDay().toString());
            ps.setString(3, to.plusDays(1).atStartOfDay().toString());
            ps.setString(4, tariff);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new InstantPowerPoint(
                            rs.getLong("ts_ms"),
                            rs.getLong("watts")
                    ));
                }
            }
        }

        return rows;
    }

    public List<InstantPowerPoint> getInstantImportPowerTotalBetween(String serial, LocalDate from, LocalDate to) throws SQLException {
        String sql = """
            SELECT
              (strftime('%s', datetime(push_ts)) * 1000) AS ts_ms,
              p_import_w AS watts
            FROM push_data
            WHERE device_serial = ?
              AND datetime(push_ts) >= datetime(?)
              AND datetime(push_ts) < datetime(?)
              AND p_import_w IS NOT NULL
            ORDER BY datetime(push_ts);
        """;

        List<InstantPowerPoint> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindDateRange(ps, serial, from, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new InstantPowerPoint(rs.getLong("ts_ms"), rs.getLong("watts")));
                }
            }
        }
        return out;
    }

    // =========================================================================
    // Instantaneous power — export
    // =========================================================================

    public List<InstantPowerPoint> getInstantExportPowerByPhaseBetween(String serial, LocalDate from, LocalDate to, int phase) throws SQLException {
        String col = exportPowerPhaseColumn(phase);

        String sql = """
        SELECT
          (strftime('%%s', datetime(push_ts)) * 1000) AS ts_ms,
          %s AS watts
        FROM push_data
        WHERE device_serial = ?
          AND datetime(push_ts) >= datetime(?)
          AND datetime(push_ts) < datetime(?)
          AND %s IS NOT NULL
        ORDER BY datetime(push_ts);
        """.formatted(col, col);

        List<InstantPowerPoint> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindDateRange(ps, serial, from, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new InstantPowerPoint(rs.getLong("ts_ms"), rs.getLong("watts")));
                }
            }
        }
        return out;
    }

    public List<InstantPowerPoint> getInstantExportPowerTotalBetween(String serial, LocalDate from, LocalDate to) throws SQLException {
        String sql = """
        SELECT
          (strftime('%s', datetime(push_ts)) * 1000) AS ts_ms,
          p_export_w AS watts
        FROM push_data
        WHERE device_serial = ?
          AND datetime(push_ts) >= datetime(?)
          AND datetime(push_ts) < datetime(?)
          AND p_export_w IS NOT NULL
        ORDER BY datetime(push_ts);
        """;

        List<InstantPowerPoint> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindDateRange(ps, serial, from, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new InstantPowerPoint(rs.getLong("ts_ms"), rs.getLong("watts")));
                }
            }
        }
        return out;
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    private void bindDateRange(PreparedStatement ps, String serial, LocalDate from, LocalDate to) throws SQLException {
        if (from == null || to == null) {
            throw new IllegalArgumentException("From and To dates must not be null.");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("From date must be before or equal to To date.");
        }

        String fromTs = from.atStartOfDay().toString().replace('T', ' ');
        String toExclusiveTs = to.plusDays(1).atStartOfDay().toString().replace('T', ' ');

        ps.setString(1, serial);
        ps.setString(2, fromTs);
        ps.setString(3, toExclusiveTs);
    }

    /**
     * Tariff mapping for database schema.
     * "All" uses total e_import_wh, otherwise tariff registers e_import_r{1..4}_wh.
     */
    private String importEnergyColumnForTariff(String tariff) {
        if (tariff == null) return "e_import_wh";
        return switch (tariff) {
            case "T1" -> "e_import_r1_wh";
            case "T2" -> "e_import_r2_wh";
            case "T3" -> "e_import_r3_wh";
            case "T4" -> "e_import_r4_wh";
            case "All" -> "e_import_wh";
            default -> "e_import_wh";
        };
    }

    private String importPowerPhaseColumn(int phase) {
        return switch (phase) {
            case 1 -> "p_import_l1_w";
            case 2 -> "p_import_l2_w";
            case 3 -> "p_import_l3_w";
            default -> throw new IllegalArgumentException("Phase must be 1,2,3. Got: " + phase);
        };
    }

    private String exportPowerPhaseColumn(int phase) {
        return switch (phase) {
            case 1 -> "p_export_l1_w";
            case 2 -> "p_export_l2_w";
            case 3 -> "p_export_l3_w";
            default -> throw new IllegalArgumentException("Phase must be 1,2,3. Got: " + phase);
        };
    }

    // =========================================================================
    // Debug / test helpers
    // =========================================================================

    public Long getMaxImportPowerW(String serial, LocalDate from, LocalDate to) throws SQLException {
        String sql = """
            SELECT MAX(p_import_w)
            FROM push_data
            WHERE device_serial = ?
              AND datetime(push_ts) >= datetime(?)
              AND datetime(push_ts) < datetime(?);
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindDateRange(ps, serial, from, to);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long v = rs.getLong(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    public void printDailyConsumptionKwh(String serial, LocalDate from, LocalDate to) throws SQLException {
        String sql = """
            SELECT substr(push_ts, 1, 10) AS day,
                   (MAX(e_import_wh) - MIN(e_import_wh)) / 1000.0 AS kwh
            FROM push_data
            WHERE device_serial = ?
              AND datetime(push_ts) >= datetime(?)
              AND datetime(push_ts) < datetime(?)
            GROUP BY day
            ORDER BY day;
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindDateRange(ps, serial, from, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    System.out.println(rs.getString("day") + " -> " + rs.getDouble("kwh") + " kWh");
                }
            }
        }
    }

    public long getTotalPushCount() throws SQLException {
        String sql = """
        SELECT COUNT(*)
        FROM push_data;
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0L;
        }
    }

    public void debugSerials() throws SQLException {
        String sql = """
        SELECT '[' || device_serial || ']' AS serial_debug,
               COUNT(*) AS cnt
        FROM push_data
        GROUP BY device_serial;
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            System.out.println("---- SERIAL DEBUG ----");

            while (rs.next()) {
                String serial = rs.getString("serial_debug");
                long count = rs.getLong("cnt");

                System.out.println(serial + " -> rows=" + count);
            }
        }
    }

    public void debugTimestamps(int limit) throws SQLException {
        String sql = """
        SELECT
          '[' || push_ts || ']' AS push_ts_raw,
          '[' || datetime(push_ts) || ']' AS push_ts_parsed
        FROM push_data
        ORDER BY push_ts DESC
        LIMIT ?;
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);

            try (ResultSet rs = ps.executeQuery()) {
                System.out.println("---- TIMESTAMP DEBUG (latest " + limit + ") ----");
                while (rs.next()) {
                    System.out.println(
                            rs.getString("push_ts_raw") +
                                    "  parsed=" +
                                    rs.getString("push_ts_parsed")
                    );
                }
            }
        }
    }
}