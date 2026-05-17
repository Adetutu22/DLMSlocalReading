package com.meter.chart;

import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.util.StringConverter;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EnergyChartWindow {
    private static final long TARIFF_GAP_MS = 60 * 60 * 1000L + 1;

    private static final String COLOR_IMPORT = "#e8572a";
    private static final String COLOR_EXPORT = "#29b6f6";

    private static final String COLOR_TOTAL = "#e8572a";
    private static final String COLOR_T1    = "#f9a825";
    private static final String COLOR_T2    = "#4caf50";
    private static final String COLOR_T3    = "#29b6f6";
    private static final String COLOR_T4    = "#3949ab";

    public record HourlyEnergyPoint(long hourMsUtc, double kwh) {}
    public record InstantPowerPoint(long tsMsUtc, long watts) {}

    private static String sn(String serial) {
        return "SN: " + serial;
    }

    // =========================================================================
    // CSV row converters – used by ChartsAnalyticsTab to build export data
    // =========================================================================

    private static final DateTimeFormatter HOURLY_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(java.time.ZoneOffset.UTC);

    private static final DateTimeFormatter INSTANT_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(java.time.ZoneOffset.UTC);

    /**
     * Converts a single HourlyEnergyPoint list to CSV rows.
     * Columns: EpochMs, DateTime (UTC), kWh
     */
    public static List<String[]> hourlyPointsToRows(List<HourlyEnergyPoint> pts) {
        if (pts == null) return List.of();
        return pts.stream()
                .map(p -> new String[]{
                        String.valueOf(p.hourMsUtc()),
                        HOURLY_FMT.format(Instant.ofEpochMilli(p.hourMsUtc())),
                        String.format("%.4f", p.kwh())
                })
                .toList();
    }

    /**
     * Merges multiple named HourlyEnergyPoint lists into CSV rows,
     * adding a "Series" column so the caller knows which tariff/label each
     * row belongs to.
     * Columns: EpochMs, DateTime (UTC), Series, kWh
     */
    @SafeVarargs
    public static List<String[]> hourlyMultiSeriesToRows(
            Map.Entry<String, List<HourlyEnergyPoint>>... namedLists
    ) {
        List<String[]> rows = new ArrayList<>();
        for (var entry : namedLists) {
            String label = entry.getKey();
            List<HourlyEnergyPoint> pts = entry.getValue();
            if (pts == null) continue;
            for (HourlyEnergyPoint p : pts) {
                rows.add(new String[]{
                        String.valueOf(p.hourMsUtc()),
                        HOURLY_FMT.format(Instant.ofEpochMilli(p.hourMsUtc())),
                        label,
                        String.format("%.4f", p.kwh())
                });
            }
        }
        rows.sort(java.util.Comparator.comparing(r -> r[0]));
        return rows;
    }

    /**
     * Merges multiple named InstantPowerPoint lists into CSV rows,
     * adding a "Series" column.
     * Columns: EpochMs, DateTime (UTC), Series, Watts
     */
    @SafeVarargs
    public static List<String[]> instantMultiSeriesToRows(
            Map.Entry<String, List<InstantPowerPoint>>... namedLists
    ) {
        List<String[]> rows = new ArrayList<>();
        for (var entry : namedLists) {
            String label = entry.getKey();
            List<InstantPowerPoint> pts = entry.getValue();
            if (pts == null) continue;
            for (InstantPowerPoint p : pts) {
                rows.add(new String[]{
                        String.valueOf(p.tsMsUtc()),
                        INSTANT_FMT.format(Instant.ofEpochMilli(p.tsMsUtc())),
                        label,
                        String.valueOf(p.watts())
                });
            }
        }
        rows.sort(java.util.Comparator.comparing(r -> r[0]));
        return rows;
    }

    // =========================================================================
    // Data-driven axis bound helpers
    // =========================================================================

    /**
     * Returns the smallest hourMsUtc across all non-null, non-empty
     * hourly-point lists, snapped DOWN to the nearest whole hour boundary.
     * Falls back to fallbackMs when no data is present.
     */
    @SafeVarargs
    static long dataMinHourMs(long fallbackMs, List<HourlyEnergyPoint>... lists) {
        long min = Long.MAX_VALUE;
        for (var list : lists) {
            if (list == null || list.isEmpty()) continue;
            long v = list.get(0).hourMsUtc();
            if (v < min) min = v;
        }
        if (min == Long.MAX_VALUE) return fallbackMs;
        long oneHour = 60L * 60L * 1000L;
        return (min / oneHour) * oneHour;
    }

    /**
     * Returns the largest hourMsUtc across all non-null, non-empty
     * hourly-point lists, snapped UP to the next whole hour boundary.
     * Falls back to fallbackMs when no data is present.
     */
    @SafeVarargs
    static long dataMaxHourMs(long fallbackMs, List<HourlyEnergyPoint>... lists) {
        long max = Long.MIN_VALUE;
        for (var list : lists) {
            if (list == null || list.isEmpty()) continue;
            long v = list.get(list.size() - 1).hourMsUtc();
            if (v > max) max = v;
        }
        if (max == Long.MIN_VALUE) return fallbackMs;
        long oneHour = 60L * 60L * 1000L;
        return ((max + oneHour - 1) / oneHour) * oneHour;
    }

    /**
     * Returns the smallest tsMsUtc across all non-null, non-empty
     * instant-point lists, snapped DOWN to the nearest whole hour boundary.
     * Falls back to fallbackMs when no data is present.
     */
    @SafeVarargs
    static long dataMinInstantMs(long fallbackMs, List<InstantPowerPoint>... lists) {
        long min = Long.MAX_VALUE;
        for (var list : lists) {
            if (list == null || list.isEmpty()) continue;
            long v = list.get(0).tsMsUtc();
            if (v < min) min = v;
        }
        if (min == Long.MAX_VALUE) return fallbackMs;
        long oneHour = 60L * 60L * 1000L;
        return (min / oneHour) * oneHour;
    }

    /**
     * Returns the largest tsMsUtc across all non-null, non-empty
     * instant-point lists, snapped UP to the next whole hour boundary.
     * Falls back to fallbackMs when no data is present.
     */
    @SafeVarargs
    static long dataMaxInstantMs(long fallbackMs, List<InstantPowerPoint>... lists) {
        long max = Long.MIN_VALUE;
        for (var list : lists) {
            if (list == null || list.isEmpty()) continue;
            long v = list.get(list.size() - 1).tsMsUtc();
            if (v > max) max = v;
        }
        if (max == Long.MIN_VALUE) return fallbackMs;
        long oneHour = 60L * 60L * 1000L;
        return ((max + oneHour - 1) / oneHour) * oneHour;
    }

    // =========================================================================
    // Axis padding helper
    // =========================================================================

    /**
     * Pads axisStart/axisEnd outward by one tick unit on each side but never
     * beyond the caller-supplied selected range [rangeStartMs, rangeEndMs].
     * This prevents charts from looking over-zoomed when data does not fill
     * the full selected range.
     * Returns a two-element array: { paddedStart, paddedEnd }.
     */
    static long[] paddedAxisBounds(long axisStart, long axisEnd,
                                   long rangeStartMs, long rangeEndMs) {
        long oneHour  = 60L * 60L * 1000L;
        long oneDay   = 24L * oneHour;
        long rawRange = Math.max(axisEnd - axisStart, oneHour);
        long tickUnit = rawRange <= oneDay
                ? oneHour
                : (long) chooseTimeTickUnit(rawRange, 12);

        long paddedStart = Math.max(axisStart - tickUnit, rangeStartMs);
        long paddedEnd   = Math.min(axisEnd   + tickUnit, rangeEndMs);
        return new long[]{ paddedStart, paddedEnd };
    }

    // =========================================================================
    // Time axis
    // =========================================================================

    /**
     * Chooses a display pattern based on the visible time range.
     * All formatting is done in the system default zone so wall-clock
     * hours are always shown correctly regardless of how timestamps
     * are stored in the database.
     */
    private static void applyTimeFormatter(NumberAxis xAxis, long visibleRangeMs) {
        xAxis.setLabel("Time");
        xAxis.setForceZeroInRange(false);
        xAxis.setTickLabelRotation(30);

        long hour = 60L * 60L * 1000L;
        long day = 24L * hour;
        long year = 365L * day;

        String pattern;
        if      (visibleRangeMs <= 3L * day)        pattern = "dd HH:mm";
        else if (visibleRangeMs <= 6L * 30L * day)  pattern = "yyyy-MM-dd";
        else if (visibleRangeMs <= 2L * year)        pattern = "yyyy-MM";
        else                                         pattern = "yyyy";

        // Format ticks in UTC so that the displayed hour matches the stored
        // wall-clock hour exactly, with no timezone shift applied.
        DateTimeFormatter formatter = DateTimeFormatter
                .ofPattern(pattern)
                .withZone(java.time.ZoneOffset.UTC);

        xAxis.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number value) {
                return formatter.format(Instant.ofEpochMilli(value.longValue()));
            }
            @Override
            public Number fromString(String string) { return 0; }
        });
    }

    private static double chooseTimeTickUnit(long rangeMs, int targetTickCount) {
        long hour = 60L * 60L * 1000L;
        long day = 24L * hour;

        double ideal = (double) rangeMs / Math.max(targetTickCount, 2);

        double[] allowed = {
                1.0  * hour,  2.0  * hour,  3.0  * hour,  4.0  * hour,
                6.0  * hour,  8.0  * hour, 12.0  * hour,
                1.0  * day,   2.0  * day,   3.0  * day,   7.0  * day,
                14.0 * day,  30.0  * day,  90.0  * day,  180.0 * day,
                365.0 * day
        };

        for (double unit : allowed) {
            if (unit >= ideal) {
                return unit;
            }
        }

        return 365.0 * day;
    }

    /**
     * Creates a time-axis whose lower/upper bounds are epoch-ms values and
     * whose tick labels are formatted in the system default timezone.
     */
    public static NumberAxis createTimeAxis(long rangeStartMs, long rangeEndMs) {
        long oneHour = 60L * 60L * 1000L;
        long oneDay = 24L * oneHour;

        long rawRange = Math.max(rangeEndMs - rangeStartMs, oneHour);

        long tickUnit = rawRange <= oneDay
                ? oneHour
                : (long) chooseTimeTickUnit(rawRange, 12);

        // Snap the lower bound DOWN to the nearest tick boundary so that the
        // first tick (e.g. 00:00) is always visible at the left edge.
        long snappedStart = (rangeStartMs / tickUnit) * tickUnit;
        // Snap the upper bound UP likewise so the last tick is always visible.
        long snappedEnd   = ((rangeEndMs + tickUnit - 1) / tickUnit) * tickUnit;

        NumberAxis xAxis = new NumberAxis();
        xAxis.setAutoRanging(false);
        xAxis.setLowerBound(snappedStart);
        xAxis.setUpperBound(snappedEnd);
        xAxis.setForceZeroInRange(false);
        xAxis.setAnimated(false);
        xAxis.setMinorTickVisible(true);
        xAxis.setMinorTickCount(2);
        xAxis.setTickUnit(tickUnit);

        applyTimeFormatter(xAxis, rawRange);
        return xAxis;
    }

    // =========================================================================
    // Generic single-series line chart
    // =========================================================================

    public static LineChart<Number, Number> buildLineChart(
            String chartTitle,
            String yLabel,
            String seriesName,
            List<? extends XYChart.Data<Number, Number>> dataPoints,
            long rangeStartMs,
            long rangeEndMs
    ) {
        // Narrow bounds to actual data extent (snapped to whole hours); fall
        // back to the caller-supplied range when the list is empty.
        long oneHour = 60L * 60L * 1000L;
        long axisStart, axisEnd;
        if (dataPoints == null || dataPoints.isEmpty()) {
            axisStart = rangeStartMs;
            axisEnd   = rangeEndMs;
        } else {
            long rawMin = dataPoints.get(0).getXValue().longValue();
            long rawMax = dataPoints.get(dataPoints.size() - 1).getXValue().longValue();
            axisStart = (rawMin / oneHour) * oneHour;
            axisEnd   = ((rawMax + oneHour - 1) / oneHour) * oneHour;
            // Pad by one tick on each side, clamped to the selected range
            long[] padded = paddedAxisBounds(axisStart, axisEnd, rangeStartMs, rangeEndMs);
            axisStart = padded[0];
            axisEnd   = padded[1];
        }

        NumberAxis xAxis = createTimeAxis(axisStart, axisEnd);

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel(yLabel);

        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle(chartTitle);
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        chart.setLegendVisible(true);
        chart.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(seriesName);
        series.getData().addAll(dataPoints);

        chart.getData().add(series);
        return chart;
    }

    // =========================================================================
    // Hourly export energy
    // =========================================================================

    public static LineChart<Number, Number> buildHourlyExportEnergyChart(
            List<HourlyEnergyPoint> points,
            String serial,
            String metaText,
            long rangeStartMs,
            long rangeEndMs
    ) {
        var data = points.stream()
                .map(p -> new XYChart.Data<Number, Number>(p.hourMsUtc(), p.kwh()))
                .toList();

        return buildLineChart(
                "Exported Energy per Hour — " + sn(serial) + " — " + metaText,
                "Export Energy (kWh)",
                "Export kWh/h",
                data,
                rangeStartMs,
                rangeEndMs
        );
    }

    // =========================================================================
    // Hourly import – multi-line tariff chart
    // =========================================================================

    /**
     * Inserts zero-value data points at the boundaries of each gap larger than
     * #TARIFF_GAP_MS so the line drops to zero instead of bridging across hours
     * where a tariff was not active.
     */
    private static List<HourlyEnergyPoint> zeroFillHourlyGaps(List<HourlyEnergyPoint> pts) {
        if (pts == null || pts.size() < 2) return pts == null ? List.of() : pts;

        List<HourlyEnergyPoint> out = new ArrayList<>();
        out.add(pts.get(0));

        for (int i = 1; i < pts.size(); i++) {
            HourlyEnergyPoint prev = pts.get(i - 1);
            HourlyEnergyPoint curr = pts.get(i);
            if (curr.hourMsUtc() - prev.hourMsUtc() > TARIFF_GAP_MS) {
                // Insert a zero just after the previous point and just before the current
                out.add(new HourlyEnergyPoint(prev.hourMsUtc() + 1, 0.0));
                out.add(new HourlyEnergyPoint(curr.hourMsUtc() - 1, 0.0));
            }
            out.add(curr);
        }
        return out;
    }

    /**
     * Adds a zero-filled hourly series to the chart.
     * Gaps larger than TARIFF_GAP_MS are bridged with zero-value sentinel
     * points so the line dips to zero rather than jumping across the gap.
     * Inline stroke color is applied directly to the Path node.
     */
    private static void addZeroFilledHourlySeries(
            LineChart<Number, Number> chart,
            List<HourlyEnergyPoint> points,
            String label,
            String color
    ) {
        if (points == null || points.isEmpty()) return;

        List<HourlyEnergyPoint> filled = zeroFillHourlyGaps(points);

        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(label);

        for (HourlyEnergyPoint p : filled) {
            series.getData().add(new XYChart.Data<>(p.hourMsUtc(), p.kwh()));
        }
        chart.getData().add(series);

        final String stroke = color;
        series.nodeProperty().addListener((obs, oldNode, newNode) -> {
            if (newNode != null)
                newNode.setStyle("-fx-stroke: " + stroke + "; -fx-stroke-width: 2px;");
        });
        if (series.getNode() != null)
            series.getNode().setStyle("-fx-stroke: " + color + "; -fx-stroke-width: 2px;");
    }

    public static LineChart<Number, Number> buildHourlyImportTariffMultiLineChart(
            List<HourlyEnergyPoint> allPoints,
            List<HourlyEnergyPoint> t1Points,
            List<HourlyEnergyPoint> t2Points,
            List<HourlyEnergyPoint> t3Points,
            List<HourlyEnergyPoint> t4Points,
            String chartTitle,
            long rangeStartMs,
            long rangeEndMs
    ) {
        long axisStart = dataMinHourMs(rangeStartMs, allPoints, t1Points, t2Points, t3Points, t4Points);
        long axisEnd   = dataMaxHourMs(rangeEndMs,   allPoints, t1Points, t2Points, t3Points, t4Points);
        // Pad by one tick on each side, clamped to the selected range
        long[] padded  = paddedAxisBounds(axisStart, axisEnd, rangeStartMs, rangeEndMs);
        axisStart = padded[0];
        axisEnd   = padded[1];
        NumberAxis xAxis = createTimeAxis(axisStart, axisEnd);

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Import Energy (kWh)");

        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle(chartTitle);
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        chart.setLegendVisible(true);
        chart.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        addZeroFilledHourlySeries(chart, allPoints, "Total", COLOR_TOTAL);
        addZeroFilledHourlySeries(chart, t1Points,  "T1",    COLOR_T1);
        addZeroFilledHourlySeries(chart, t2Points,  "T2",    COLOR_T2);
        addZeroFilledHourlySeries(chart, t3Points,  "T3",    COLOR_T3);
        addZeroFilledHourlySeries(chart, t4Points,  "T4",    COLOR_T4);

        fixLegendColors(chart, Map.of(
                "Total", COLOR_TOTAL,
                "T1",    COLOR_T1,
                "T2",    COLOR_T2,
                "T3",    COLOR_T3,
                "T4",    COLOR_T4
        ));

        return chart;
    }

    // =========================================================================
    // Instantaneous power – shared helpers
    // =========================================================================

    /**
     * Inserts zero-value data points at the boundaries of each gap larger than
     * TARIFF_GAP_MS so the line drops to zero instead of bridging across silent
     * periods.
     */
    private static List<InstantPowerPoint> zeroFillInstantGaps(List<InstantPowerPoint> pts) {
        if (pts == null || pts.size() < 2) return pts == null ? List.of() : pts;

        List<InstantPowerPoint> out = new ArrayList<>();
        out.add(pts.get(0));

        for (int i = 1; i < pts.size(); i++) {
            InstantPowerPoint prev = pts.get(i - 1);
            InstantPowerPoint curr = pts.get(i);
            if (curr.tsMsUtc() - prev.tsMsUtc() > TARIFF_GAP_MS) {
                out.add(new InstantPowerPoint(prev.tsMsUtc() + 1, 0L));
                out.add(new InstantPowerPoint(curr.tsMsUtc() - 1, 0L));
            }
            out.add(curr);
        }
        return out;
    }

    /**
     * Adds a zero-filled instant-power series to the chart.
     * Gaps larger than TARIFF_GAP_MS are bridged with zero-value sentinel
     * points so the line dips to zero rather than jumping across the gap.
     * Inline stroke color is applied directly to the Path node.
     */
    private static void addZeroFilledSeries(
            LineChart<Number, Number> chart,
            List<InstantPowerPoint> points,
            String label,
            String color
    ) {
        if (points == null || points.isEmpty()) return;

        List<InstantPowerPoint> filled = zeroFillInstantGaps(points);

        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(label);

        for (InstantPowerPoint p : filled) {
            series.getData().add(new XYChart.Data<>(p.tsMsUtc(), p.watts()));
        }
        chart.getData().add(series);

        final String stroke = color;
        series.nodeProperty().addListener((obs, oldNode, newNode) -> {
            if (newNode != null)
                newNode.setStyle("-fx-stroke: " + stroke + "; -fx-stroke-width: 2px;");
        });
        if (series.getNode() != null)
            series.getNode().setStyle("-fx-stroke: " + color + "; -fx-stroke-width: 2px;");
    }

    // =========================================================================
    // Instantaneous import power charts
    // =========================================================================

    public static LineChart<Number, Number> buildInstantImportPowerMultiLineChart(
            List<InstantPowerPoint> totalPoints,
            List<InstantPowerPoint> l1Points,
            List<InstantPowerPoint> l2Points,
            List<InstantPowerPoint> l3Points,
            String chartTitle,
            long rangeStartMs,
            long rangeEndMs
    ) {
        long axisStart = dataMinInstantMs(rangeStartMs, totalPoints, l1Points, l2Points, l3Points);
        long axisEnd   = dataMaxInstantMs(rangeEndMs,   totalPoints, l1Points, l2Points, l3Points);
        // Pad by one tick on each side, clamped to the selected range
        long[] padded  = paddedAxisBounds(axisStart, axisEnd, rangeStartMs, rangeEndMs);
        axisStart = padded[0];
        axisEnd   = padded[1];
        NumberAxis xAxis = createTimeAxis(axisStart, axisEnd);

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Active Power (W)");

        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle(chartTitle);
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        chart.setLegendVisible(true);
        chart.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        addZeroFilledSeries(chart, totalPoints, "Total", COLOR_TOTAL);
        addZeroFilledSeries(chart, l1Points,    "L1",    COLOR_T1);
        addZeroFilledSeries(chart, l2Points,    "L2",    COLOR_T2);
        addZeroFilledSeries(chart, l3Points,    "L3",    COLOR_T3);

        fixLegendColors(chart, Map.of(
                "Total", COLOR_TOTAL,
                "L1",    COLOR_T1,
                "L2",    COLOR_T2,
                "L3",    COLOR_T3
        ));

        return chart;
    }

    public static LineChart<Number, Number> buildInstantImportPowerTariffMultiLineChart(
            List<InstantPowerPoint> allPoints,
            List<InstantPowerPoint> t1Points,
            List<InstantPowerPoint> t2Points,
            List<InstantPowerPoint> t3Points,
            List<InstantPowerPoint> t4Points,
            String chartTitle,
            long rangeStartMs,
            long rangeEndMs
    ) {
        long axisStart = dataMinInstantMs(rangeStartMs, allPoints, t1Points, t2Points, t3Points, t4Points);
        long axisEnd   = dataMaxInstantMs(rangeEndMs,   allPoints, t1Points, t2Points, t3Points, t4Points);
        // Pad by one tick on each side, clamped to the selected range
        long[] padded  = paddedAxisBounds(axisStart, axisEnd, rangeStartMs, rangeEndMs);
        axisStart = padded[0];
        axisEnd   = padded[1];
        NumberAxis xAxis = createTimeAxis(axisStart, axisEnd);

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Active Power (W)");

        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle(chartTitle);
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        chart.setLegendVisible(true);
        chart.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        addZeroFilledSeries(chart, allPoints, "Total", COLOR_TOTAL);
        addZeroFilledSeries(chart, t1Points,  "T1",    COLOR_T1);
        addZeroFilledSeries(chart, t2Points,  "T2",    COLOR_T2);
        addZeroFilledSeries(chart, t3Points,  "T3",    COLOR_T3);
        addZeroFilledSeries(chart, t4Points,  "T4",    COLOR_T4);

        fixLegendColors(chart, Map.of(
                "Total", COLOR_TOTAL,
                "T1",    COLOR_T1,
                "T2",    COLOR_T2,
                "T3",    COLOR_T3,
                "T4",    COLOR_T4
        ));

        return chart;
    }

    // =========================================================================
    // Instantaneous export power chart
    // =========================================================================

    public static LineChart<Number, Number> buildInstantExportPowerMultiLineChart(
            List<InstantPowerPoint> totalPoints,
            List<InstantPowerPoint> l1Points,
            List<InstantPowerPoint> l2Points,
            List<InstantPowerPoint> l3Points,
            String chartTitle,
            long rangeStartMs,
            long rangeEndMs
    ) {
        long axisStart = dataMinInstantMs(rangeStartMs, totalPoints, l1Points, l2Points, l3Points);
        long axisEnd   = dataMaxInstantMs(rangeEndMs,   totalPoints, l1Points, l2Points, l3Points);
        // Pad by one tick on each side, clamped to the selected range
        long[] padded  = paddedAxisBounds(axisStart, axisEnd, rangeStartMs, rangeEndMs);
        axisStart = padded[0];
        axisEnd   = padded[1];
        NumberAxis xAxis = createTimeAxis(axisStart, axisEnd);

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Active Power (W)");

        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle(chartTitle);
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        chart.setLegendVisible(true);
        chart.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        addZeroFilledSeries(chart, totalPoints, "Total", COLOR_TOTAL);
        addZeroFilledSeries(chart, l1Points,    "L1",    COLOR_T1);
        addZeroFilledSeries(chart, l2Points,    "L2",    COLOR_T2);
        addZeroFilledSeries(chart, l3Points,    "L3",    COLOR_T3);

        fixLegendColors(chart, Map.of(
                "Total", COLOR_TOTAL,
                "L1",    COLOR_T1,
                "L2",    COLOR_T2,
                "L3",    COLOR_T3
        ));

        return chart;
    }

    // =========================================================================
    // Legend color fix – matches by series name, not by default colorN index
    // =========================================================================

    /**
     * Corrects legend symbol colors by series name rather than by
     * default colorN index.
     */
    private static void fixLegendColors(
            LineChart<Number, Number> chart,
            Map<String, String> nameToColor
    ) {
        Runnable apply = () -> javafx.application.Platform.runLater(
                () -> applyLegendColors(chart, nameToColor));

        if (chart.getScene() != null) {
            // Chart is already in a scene
            apply.run();
        } else {
            // Chart is not yet in a scene — wait until it is
            chart.sceneProperty().addListener((obs, oldScene, newScene) -> {
                if (newScene != null) apply.run();
            });
        }
    }

    /**
     * Applies legend symbol colors by matching series name.
     * JavaFX default color style must be overridden directly.
     */
    private static void applyLegendColors(
            LineChart<Number, Number> chart,
            Map<String, String> nameToColor
    ) {
        chart.applyCss();
        chart.layout();
        for (javafx.scene.Node node : chart.lookupAll(".chart-legend-item")) {
            if (!(node instanceof javafx.scene.control.Label label)) continue;
            String color = nameToColor.get(label.getText());
            if (color == null) continue;
            javafx.scene.Node symbol = label.lookup(".chart-legend-item-symbol");
            if (symbol != null) symbol.setStyle("-fx-background-color: " + color + ", white;");
        }
    }

    // =========================================================================
    // Shared time utilities
    // =========================================================================

    /**
     * Returns the epoch-ms of local midnight for the given date in the
     * system default timezone. Use as axis lower/upper bound for
     * createTimeAxis only — the formatter handles UTC-to-local
     * display conversion for tick labels automatically.
     */
    public static long midnightMs(java.time.LocalDate date) {
        // The DB stores timestamps as local wall-clock time encoded as UTC.
        // Both axis bounds and tick formatter must use UTC so that "00:00"
        // ticks land exactly on the stored midnight values.
        return date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
    }
}