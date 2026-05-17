package com.meter.chart;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.*;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * Chart builders for compare mode (two-period side-by-side analysis).
 * All three granularities overlay both periods on a SHARED relative x-axis:
 *
 *   DAY   – x = hour of day  (0–23),   tick labels "00" … "23"
 *   WEEK  – x = day-of-week  (0–6),    tick labels "Mon" … "Sun"
 *   MONTH – x = day-of-month (1–31),   tick labels "1" … "31"
 *
 * For instant-power charts the x-axis is seconds-since-period-start so both
 * periods are overlaid on the same 0…(periodDays×86400) axis.
 */
public class CompareChartWindow {

    // ── Colors ────────────────────────────────────────────────────────────────

    private static final String COLOR_TOTAL    = "#e8572a";
    private static final String COLOR_T1       = "#f9a825";
    private static final String COLOR_T2       = "#4caf50";
    private static final String COLOR_T3       = "#29b6f6";
    private static final String COLOR_T4       = "#3949ab";

    private static final String COLOR_DAY2_TOTAL = "#9c27b0";
    private static final String COLOR_DAY2_T1    = "#e91e63";
    private static final String COLOR_DAY2_T2    = "#00897b";
    private static final String COLOR_DAY2_T3    = "#0d47a1";
    private static final String COLOR_DAY2_T4    = "#5e35b1";

    private static final String COLOR_L1       = COLOR_T1;
    private static final String COLOR_L2       = COLOR_T2;
    private static final String COLOR_L3       = COLOR_T3;
    private static final String COLOR_DAY2_L1  = COLOR_DAY2_T1;
    private static final String COLOR_DAY2_L2  = COLOR_DAY2_T2;
    private static final String COLOR_DAY2_L3  = COLOR_DAY2_T3;

    // ── Public enums ──────────────────────────────────────────────────────────

    /** Granularity of the selected compare periods. */
    public enum PeriodType { DAY, WEEK, MONTH }

    public enum InstantCompareMode { TOTAL, PHASES, TARIFFS }

    public enum ToggleMode { PHASES_ONLY, TARIFFS_ONLY }

    public enum HourlyChartMode { BAR, LINE }

    // ── Gap thresholds ────────────────────────────────────────────────────────

    /** Single-day: gap > 1 h triggers zero-fill. */
    private static final long GAP_SINGLE_DAY_SEC = 60 * 60 + 1;
    /** Multi-day: gap > 3 h triggers zero-fill. */
    private static final long GAP_MULTI_DAY_SEC  = 3 * 60 * 60 + 1;

    // =========================================================================
    // HOURLY ENERGY – Togglable Bar / Line node
    // =========================================================================

    /**
     * Returns a VBox with a Bar/Line toggle above the compare chart.
     * All three PeriodTypes overlay both series on a shared relative x-axis.
     * DAY   → hour-of-day  buckets  (0–23)
     * WEEK  → day-of-week  buckets  (Mon=0 … Sun=6)
     * MONTH → day-of-month buckets  (1–31)
     *
     * @param period1Start  first day of period 1 (used to resolve day-of-week/month labels)
     * @param period2Start  first day of period 2
     */
    public static VBox buildHourlyCompareNode(
            List<EnergyChartWindow.HourlyEnergyPoint> p1Points,
            List<EnergyChartWindow.HourlyEnergyPoint> p2Points,
            String labelP1,
            String labelP2,
            String yAxisLabel,
            PeriodType periodType,
            LocalDate period1Start,
            LocalDate period2Start,
            java.util.function.Consumer<HourlyChartMode> onModeChange
    ) {
        ToggleGroup group   = new ToggleGroup();
        ToggleButton btnBar  = styledToggle("Bar",  group);
        ToggleButton btnLine = styledToggle("Line", group);
        btnBar.setSelected(true);

        HBox toggleRow = new HBox(4, btnBar, btnLine);
        toggleRow.setAlignment(Pos.CENTER_LEFT);
        toggleRow.setPadding(new Insets(0, 0, 4, 2));

        VBox chartHolder = new VBox();
        chartHolder.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(chartHolder, Priority.ALWAYS);

        Runnable rebuild = () -> {
            HourlyChartMode mode = (group.getSelectedToggle() instanceof ToggleButton tb
                    && "Line".equals(tb.getText()))
                    ? HourlyChartMode.LINE : HourlyChartMode.BAR;

            javafx.scene.Node chart = (mode == HourlyChartMode.LINE)
                    ? buildHourlyLineChart(p1Points, p2Points, labelP1, labelP2,
                    yAxisLabel, periodType, period1Start, period2Start)
                    : buildHourlyBarChart (p1Points, p2Points, labelP1, labelP2,
                    yAxisLabel, periodType, period1Start, period2Start);

            VBox.setVgrow(chart, Priority.ALWAYS);
            if (chart instanceof XYChart<?, ?> xy) xy.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            chartHolder.getChildren().setAll(chart);

            // Fix legend symbol colors — JavaFX ignores series stroke/fill colors
            if (mode == HourlyChartMode.LINE && chart instanceof LineChart<?, ?> lc) {
                javafx.application.Platform.runLater(() ->
                        applyHourlyLineLegendColors((LineChart<Number, Number>) lc,
                                labelP1, labelP2));
            } else if (mode == HourlyChartMode.BAR && chart instanceof BarChart<?, ?> bc) {
                javafx.application.Platform.runLater(() ->
                        applyHourlyBarLegendColors((BarChart<String, Number>) bc,
                                labelP1, labelP2));
            }

            if (onModeChange != null) onModeChange.accept(mode);
        };

        rebuild.run();
        group.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            if (newT == null) { if (oldT != null) oldT.setSelected(true); return; }
            rebuild.run();
        });

        VBox root = new VBox(6, toggleRow, chartHolder);
        root.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(chartHolder, Priority.ALWAYS);
        return root;
    }

    // ── Backwards-compatible overload (DAY granularity, no period-start needed) ──

    public static VBox buildHourlyCompareNode(
            List<EnergyChartWindow.HourlyEnergyPoint> p1Points,
            List<EnergyChartWindow.HourlyEnergyPoint> p2Points,
            String labelP1,
            String labelP2,
            String yAxisLabel,
            java.util.function.Consumer<HourlyChartMode> onModeChange
    ) {
        return buildHourlyCompareNode(p1Points, p2Points, labelP1, labelP2, yAxisLabel,
                PeriodType.DAY, LocalDate.now(), LocalDate.now(), onModeChange);
    }

    // ── Hourly Bar Chart ──────────────────────────────────────────────────────

    private static BarChart<String, Number> buildHourlyBarChart(
            List<EnergyChartWindow.HourlyEnergyPoint> p1Points,
            List<EnergyChartWindow.HourlyEnergyPoint> p2Points,
            String labelP1,
            String labelP2,
            String yAxisLabel,
            PeriodType periodType,
            LocalDate period1Start,
            LocalDate period2Start
    ) {
        int slots = periodSlots(periodType);

        CategoryAxis xAxis = new CategoryAxis();
        xAxis.setLabel(xAxisLabel(periodType));

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel(yAxisLabel);
        yAxis.setForceZeroInRange(true);

        BarChart<String, Number> chart = new BarChart<>(xAxis, yAxis);
        chart.setAnimated(false);
        chart.setLegendVisible(true);
        chart.setCategoryGap(periodType == PeriodType.DAY ? 6 : 8);
        chart.setBarGap(2);
        chart.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        XYChart.Series<String, Number> s1 = new XYChart.Series<>();
        s1.setName(labelP1);
        XYChart.Series<String, Number> s2 = new XYChart.Series<>();
        s2.setName(labelP2);

        double[] b1 = bucketHourly(p1Points, periodType, period1Start, slots);
        double[] b2 = bucketHourly(p2Points, periodType, period2Start, slots);

        for (int i = 0; i < slots; i++) {
            String lbl = slotLabel(periodType, i, period1Start);
            s1.getData().add(new XYChart.Data<>(lbl, b1[i]));
            s2.getData().add(new XYChart.Data<>(lbl, b2[i]));
        }

        chart.getData().addAll(s1, s2);
        applyBarColor(s1, COLOR_TOTAL,      0.85);
        applyBarColor(s2, COLOR_DAY2_TOTAL, 0.85);
        styleLegendHorizontal(chart);
        return chart;
    }

    // ── Hourly Line Chart ─────────────────────────────────────────────────────

    private static LineChart<Number, Number> buildHourlyLineChart(
            List<EnergyChartWindow.HourlyEnergyPoint> p1Points,
            List<EnergyChartWindow.HourlyEnergyPoint> p2Points,
            String labelP1,
            String labelP2,
            String yAxisLabel,
            PeriodType periodType,
            LocalDate period1Start,
            LocalDate period2Start
    ) {
        int slots = periodSlots(periodType);

        NumberAxis xAxis = new NumberAxis(xAxisMin(periodType), xAxisMax(periodType), 1);
        xAxis.setLabel(xAxisLabel(periodType));
        xAxis.setForceZeroInRange(false);
        xAxis.setAnimated(false);
        if (periodType == PeriodType.MONTH && slots > 15) xAxis.setTickLabelRotation(45);
        xAxis.setTickLabelFormatter(new StringConverter<>() {
            @Override public String toString(Number v) {
                return slotLabel(periodType, v.intValue(), period1Start);
            }
            @Override public Number fromString(String s) { return 0; }
        });

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel(yAxisLabel);
        yAxis.setForceZeroInRange(true);
        yAxis.setAnimated(false);

        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setAnimated(false);
        chart.setCreateSymbols(false);   // no dots
        chart.setLegendVisible(true);
        chart.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        double[] b1 = bucketHourly(p1Points, periodType, period1Start, slots);
        double[] b2 = bucketHourly(p2Points, periodType, period2Start, slots);

        XYChart.Series<Number, Number> s1 = new XYChart.Series<>();
        s1.setName(labelP1);
        XYChart.Series<Number, Number> s2 = new XYChart.Series<>();
        s2.setName(labelP2);

        for (int i = 0; i < slots; i++) {
            s1.getData().add(new XYChart.Data<>(xAxisMin(periodType) + i, b1[i]));
            s2.getData().add(new XYChart.Data<>(xAxisMin(periodType) + i, b2[i]));
        }

        chart.getData().addAll(s1, s2);
        applyStrokeStyle(s1, "-fx-stroke: " + COLOR_TOTAL      + "; -fx-stroke-width: 2px;");
        applyStrokeStyle(s2, "-fx-stroke: " + COLOR_DAY2_TOTAL + "; -fx-stroke-width: 2px;");
        return chart;
    }

    // =========================================================================
    // INSTANTANEOUS POWER – Overlay Line Chart + Toggle Bar
    // =========================================================================

    /** Bundles all pre-loaded instant-power series for one period. */
    public record InstantDayData(
            List<EnergyChartWindow.InstantPowerPoint> total,
            List<EnergyChartWindow.InstantPowerPoint> l1OrT1,
            List<EnergyChartWindow.InstantPowerPoint> l2OrT2,
            List<EnergyChartWindow.InstantPowerPoint> l3OrT3,
            List<EnergyChartWindow.InstantPowerPoint> t4
    ) {}

    /**
     * Builds the toggle-bar + overlay line chart node for instant power.
     *
     * DAY   → x = seconds-since-midnight (0–86 400)
     * WEEK  → x = seconds-since-Monday   (0–7×86 400)
     * MONTH → x = seconds-since-month-start (0–N×86 400)
     *
     * @param period1Start  first day of period 1  (used to compute offset for each timestamp)
     * @param period2Start  first day of period 2
     */
    public static VBox buildInstantCompareNode(
            InstantDayData day1,
            InstantDayData day2,
            String labelDay1,
            String labelDay2,
            String chartTitle,
            String yAxisLabel,
            ToggleMode toggleMode,
            InstantCompareMode initialMode,
            PeriodType periodType,
            LocalDate period1Start,
            LocalDate period2Start,
            java.util.function.Consumer<InstantCompareMode> onModeChange
    ) {
        ToggleGroup group = new ToggleGroup();
        ToggleButton btnTotal   = styledToggle("Total",   group);
        ToggleButton btnPhases  = styledToggle("Phases",  group);
        ToggleButton btnTariffs = styledToggle("Tariffs", group);

        HBox toggleRow = (toggleMode == ToggleMode.TARIFFS_ONLY)
                ? new HBox(4, btnTotal, btnTariffs)
                : new HBox(4, btnTotal, btnPhases);
        toggleRow.setAlignment(Pos.CENTER_LEFT);
        toggleRow.setPadding(new Insets(0, 0, 4, 2));

        VBox chartHolder = new VBox();
        chartHolder.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(chartHolder, Priority.ALWAYS);

        Runnable rebuild = () -> {
            InstantCompareMode mode = currentMode(group);
            LineChart<Number, Number> chart = buildInstantCompareLineChart(
                    day1, day2, labelDay1, labelDay2,
                    chartTitle, yAxisLabel, mode,
                    periodType, period1Start, period2Start);
            VBox.setVgrow(chart, Priority.ALWAYS);
            chartHolder.getChildren().setAll(chart);
            javafx.application.Platform.runLater(() ->
                    applyCompareLegendColors(chart, labelDay1, labelDay2));
            if (onModeChange != null) onModeChange.accept(mode);
        };

        selectToggle(group, btnTotal, btnPhases, btnTariffs, initialMode);
        rebuild.run();
        group.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            if (newT == null) { if (oldT != null) oldT.setSelected(true); return; }
            rebuild.run();
        });

        VBox root = new VBox(6, toggleRow, chartHolder);
        root.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(chartHolder, Priority.ALWAYS);
        return root;
    }

    // ── Instant line chart builder ────────────────────────────────────────────

    public static LineChart<Number, Number> buildInstantCompareLineChart(
            InstantDayData day1,
            InstantDayData day2,
            String labelDay1,
            String labelDay2,
            String chartTitle,
            String yAxisLabel,
            InstantCompareMode mode,
            PeriodType periodType,
            LocalDate period1Start,
            LocalDate period2Start
    ) {
        // X-axis spans 0 … periodDays × 86400 seconds
        int periodDays   = instantPeriodDays(periodType, period1Start, period2Start);
        long axisMax     = (long) periodDays * 86_400L;
        long tickUnit    = instantTickUnit(periodType);

        NumberAxis xAxis = new NumberAxis(0, axisMax, tickUnit);
        xAxis.setLabel(periodType == PeriodType.DAY ? "Time of day" : "Time");
        xAxis.setForceZeroInRange(true);
        xAxis.setAnimated(false);
        xAxis.setTickLabelRotation(periodType == PeriodType.DAY ? 0 : 35);
        xAxis.setTickLabelFormatter(instantTickFormatter(periodType));

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel(yAxisLabel);
        yAxis.setForceZeroInRange(true);
        yAxis.setAnimated(false);

        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle(chartTitle);
        chart.setCreateSymbols(false);   // no dots
        chart.setAnimated(false);
        chart.setLegendVisible(true);
        chart.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        long gapSec = (periodType == PeriodType.DAY) ? GAP_SINGLE_DAY_SEC : GAP_MULTI_DAY_SEC;

        switch (mode) {
            case TOTAL -> {
                addInstantSeries(chart, day1.total(),  labelDay1 + " Total", COLOR_TOTAL,      period1Start, gapSec);
                addInstantSeries(chart, day2.total(),  labelDay2 + " Total", COLOR_DAY2_TOTAL, period2Start, gapSec);
            }
            case PHASES -> {
                addInstantSeries(chart, day1.total(),  labelDay1 + " Total", COLOR_TOTAL,      period1Start, gapSec);
                addInstantSeries(chart, day1.l1OrT1(), labelDay1 + " L1",    COLOR_L1,         period1Start, gapSec);
                addInstantSeries(chart, day1.l2OrT2(), labelDay1 + " L2",    COLOR_L2,         period1Start, gapSec);
                addInstantSeries(chart, day1.l3OrT3(), labelDay1 + " L3",    COLOR_L3,         period1Start, gapSec);
                addInstantSeries(chart, day2.total(),  labelDay2 + " Total", COLOR_DAY2_TOTAL, period2Start, gapSec);
                addInstantSeries(chart, day2.l1OrT1(), labelDay2 + " L1",    COLOR_DAY2_L1,    period2Start, gapSec);
                addInstantSeries(chart, day2.l2OrT2(), labelDay2 + " L2",    COLOR_DAY2_L2,    period2Start, gapSec);
                addInstantSeries(chart, day2.l3OrT3(), labelDay2 + " L3",    COLOR_DAY2_L3,    period2Start, gapSec);
            }
            case TARIFFS -> {
                addInstantSeries(chart, day1.total(),  labelDay1 + " Total", COLOR_TOTAL,      period1Start, gapSec);
                addInstantSeries(chart, day1.l1OrT1(), labelDay1 + " T1",    COLOR_T1,         period1Start, gapSec);
                addInstantSeries(chart, day1.l2OrT2(), labelDay1 + " T2",    COLOR_T2,         period1Start, gapSec);
                addInstantSeries(chart, day1.l3OrT3(), labelDay1 + " T3",    COLOR_T3,         period1Start, gapSec);
                if (day1.t4() != null)
                    addInstantSeries(chart, day1.t4(), labelDay1 + " T4",    COLOR_T4,         period1Start, gapSec);
                addInstantSeries(chart, day2.total(),  labelDay2 + " Total", COLOR_DAY2_TOTAL, period2Start, gapSec);
                addInstantSeries(chart, day2.l1OrT1(), labelDay2 + " T1",    COLOR_DAY2_T1,    period2Start, gapSec);
                addInstantSeries(chart, day2.l2OrT2(), labelDay2 + " T2",    COLOR_DAY2_T2,    period2Start, gapSec);
                addInstantSeries(chart, day2.l3OrT3(), labelDay2 + " T3",    COLOR_DAY2_T3,    period2Start, gapSec);
                if (day2.t4() != null)
                    addInstantSeries(chart, day2.t4(), labelDay2 + " T4",    COLOR_DAY2_T4,    period2Start, gapSec);
            }
        }

        return chart;
    }

    /**
     * Adds one instant-power series to a chart.
     * Timestamps are converted to seconds-since-period-start so both periods
     * share the same 0…axisMax x-axis.
     */
    private static void addInstantSeries(
            LineChart<Number, Number> chart,
            List<EnergyChartWindow.InstantPowerPoint> points,
            String label,
            String color,
            LocalDate periodStart,
            long gapSec
    ) {
        if (points == null || points.isEmpty()) return;

        long periodStartSec = periodStart.atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond();
        String strokeStyle = "-fx-stroke: " + color + "; -fx-stroke-width: 2px;";

        long[] relSecs = new long[points.size()];
        for (int i = 0; i < points.size(); i++) {
            relSecs[i] = points.get(i).tsMsUtc() / 1000L - periodStartSec;
            if (relSecs[i] < 0) relSecs[i] = 0;   // clamp: shouldn't happen
        }

        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(label);
        series.getData().add(new XYChart.Data<>(relSecs[0], points.get(0).watts()));

        for (int i = 1; i < points.size(); i++) {
            if (relSecs[i] - relSecs[i - 1] > gapSec) {
                series.getData().add(new XYChart.Data<>(relSecs[i - 1] + 1, 0L));
                series.getData().add(new XYChart.Data<>(relSecs[i]     - 1, 0L));
            }
            series.getData().add(new XYChart.Data<>(relSecs[i], points.get(i).watts()));
        }

        chart.getData().add(series);
        applyStrokeStyle(series, strokeStyle);
    }

    // =========================================================================
    // REGISTER COMPARE – cumulative line chart
    // =========================================================================

    /**
     * Builds a single overlay line chart with four series:
     *   Period-1 Import  (COLOR_TOTAL)
     *   Period-2 Import  (COLOR_DAY2_TOTAL)
     *   Period-1 Export  (COLOR_T3)
     *   Period-2 Export  (COLOR_DAY2_T3)
     * The x-axis is seconds-since-period-start (0 … periodDays × 86 400) so
     * both periods are overlaid on the same relative timeline.
     */
    public static javafx.scene.chart.LineChart<Number, Number> buildRegisterCompareChart(
            List<EnergyChartWindow.HourlyEnergyPoint> importRaw1,
            List<EnergyChartWindow.HourlyEnergyPoint> importRaw2,
            List<EnergyChartWindow.HourlyEnergyPoint> exportRaw1,
            List<EnergyChartWindow.HourlyEnergyPoint> exportRaw2,
            String labelP1,
            String labelP2,
            PeriodType periodType,
            LocalDate period1Start,
            LocalDate period2Start
    ) {
        int  periodDays = instantPeriodDays(periodType, period1Start, period2Start);
        long axisMax    = (long) periodDays * 86_400L;
        long tickUnit   = instantTickUnit(periodType);

        NumberAxis xAxis = new NumberAxis(0, axisMax, tickUnit);
        xAxis.setLabel(periodType == PeriodType.DAY ? "Time of day" : "Time");
        xAxis.setForceZeroInRange(true);
        xAxis.setAnimated(false);
        xAxis.setTickLabelRotation(periodType == PeriodType.DAY ? 0 : 35);
        xAxis.setTickLabelFormatter(instantTickFormatter(periodType));

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Register (kWh)");
        yAxis.setForceZeroInRange(false);
        yAxis.setAnimated(false);

        javafx.scene.chart.LineChart<Number, Number> chart =
                new javafx.scene.chart.LineChart<>(xAxis, yAxis);
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        chart.setLegendVisible(true);
        chart.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        addRegisterSeries(chart, importRaw1, labelP1 + " Import", COLOR_TOTAL,      period1Start);
        addRegisterSeries(chart, importRaw2, labelP2 + " Import", COLOR_DAY2_TOTAL, period2Start);
        addRegisterSeries(chart, exportRaw1, labelP1 + " Export", COLOR_T3,         period1Start);
        addRegisterSeries(chart, exportRaw2, labelP2 + " Export", COLOR_DAY2_T3,    period2Start);

        // Fix legend colors after the chart is in the scene graph
        javafx.application.Platform.runLater(() -> {
            chart.applyCss();
            chart.layout();
            java.util.Map<String, String> colors = java.util.Map.of(
                    labelP1 + " Import", COLOR_TOTAL,
                    labelP2 + " Import", COLOR_DAY2_TOTAL,
                    labelP1 + " Export", COLOR_T3,
                    labelP2 + " Export", COLOR_DAY2_T3
            );
            for (javafx.scene.Node node : chart.lookupAll(".chart-legend-item")) {
                if (!(node instanceof javafx.scene.control.Label lbl)) continue;
                String color = colors.get(lbl.getText());
                if (color == null) continue;
                javafx.scene.Node sym = lbl.lookup(".chart-legend-item-symbol");
                if (sym != null) sym.setStyle("-fx-background-color: " + color + ", white;");
            }
        });

        return chart;
    }

    /**
     * Timestamps are converted to seconds-since-period-start.
     * The first point's kWh value is subtracted from every
     * subsequent point so the line always starts at 0.
     */
    private static void addRegisterSeries(
            javafx.scene.chart.LineChart<Number, Number> chart,
            List<EnergyChartWindow.HourlyEnergyPoint> raw,
            String label,
            String color,
            LocalDate periodStart
    ) {
        if (raw == null || raw.isEmpty()) return;

        long   periodStartSec = periodStart.atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond();
        String strokeStyle    = "-fx-stroke: " + color + "; -fx-stroke-width: 2px;";

        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(label);

        for (EnergyChartWindow.HourlyEnergyPoint p : raw) {
            long relSec = p.hourMsUtc() / 1000L - periodStartSec;
            if (relSec < 0) relSec = 0;
            series.getData().add(new XYChart.Data<>(relSec, p.kwh()));
        }

        chart.getData().add(series);
        applyStrokeStyle(series, strokeStyle);
    }

    // =========================================================================
    // Period / axis helpers
    // =========================================================================

    /** Number of x-axis slots for each granularity. */
    private static int periodSlots(PeriodType pt) {
        return switch (pt) {
            case DAY   -> 24;
            case WEEK  -> 7;
            case MONTH -> 31;
        };
    }

    private static int xAxisMin(PeriodType pt) {
        return pt == PeriodType.DAY ? 0 : (pt == PeriodType.MONTH ? 1 : 0);
    }

    private static int xAxisMax(PeriodType pt) {
        return switch (pt) {
            case DAY   -> 23;
            case WEEK  -> 6;
            case MONTH -> 31;
        };
    }

    private static String xAxisLabel(PeriodType pt) {
        return switch (pt) {
            case DAY   -> "Hour of day";
            case WEEK  -> "Day of week";
            case MONTH -> "Day of month";
        };
    }

    /**
     * Returns the tick label for slot i (0-based for DAY/WEEK, 1-based for MONTH).
     * For WEEK the label is always Mon/Tue/… regardless of which calendar week was selected.
     */
    private static String slotLabel(PeriodType pt, int i, LocalDate periodStart) {
        return switch (pt) {
            case DAY   -> String.format("%02d", i);
            case WEEK  -> DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            case MONTH -> String.valueOf(i + 1);   // i is 0-based index; display as 1-based day-of-month
        };
    }

    /**
     * Buckets hourly energy points into slots relative to the period start.
     * DAY   → slot = hour-of-day (0–23)
     * WEEK  → slot = dayOfWeek - 1  (Mon=0 … Sun=6), independent of calendar week
     * MONTH → slot = dayOfMonth - 1  (day 1 → index 0), into a 31-element array
     */
    private static double[] bucketHourly(
            List<EnergyChartWindow.HourlyEnergyPoint> points,
            PeriodType periodType,
            LocalDate periodStart,
            int slots
    ) {
        double[] buckets = new double[slots];
        if (points == null) return buckets;
        for (EnergyChartWindow.HourlyEnergyPoint p : points) {
            java.time.ZonedDateTime zdt = java.time.Instant.ofEpochMilli(p.hourMsUtc())
                    .atZone(java.time.ZoneOffset.UTC);
            int slot = switch (periodType) {
                case DAY   -> zdt.getHour();
                case WEEK  -> zdt.getDayOfWeek().getValue() - 1;   // Mon=0..Sun=6
                case MONTH -> zdt.getDayOfMonth() - 1;             // day1=0..day31=30
            };
            if (slot >= 0 && slot < slots) buckets[slot] += p.kwh();
        }
        return buckets;
    }

    /**
     * The x-axis for the instant compare chart spans 0 … periodDays × 86400.
     * The larger length of the two period lengths is used so both fit.
     */
    private static int instantPeriodDays(PeriodType pt, LocalDate start1, LocalDate start2) {
        return switch (pt) {
            case DAY   -> 1;
            case WEEK  -> 7;
            case MONTH -> {
                // Use the larger of the two months' lengths
                int m1 = start1.getMonth().length(java.time.Year.isLeap(start1.getYear()));
                int m2 = start2.getMonth().length(java.time.Year.isLeap(start2.getYear()));
                yield Math.max(m1, m2);
            }
        };
    }

    /**
     * Tick interval in seconds for the instant compare x-axis.
     * DAY   → every 1 h  (3 600 s)
     * WEEK  → every 1 day (86 400 s)
     * MONTH → every 2 days (172 800 s) to avoid overcrowding
     */
    private static long instantTickUnit(PeriodType pt) {
        return switch (pt) {
            case DAY   -> 3_600L;
            case WEEK  -> 86_400L;
            case MONTH -> 172_800L;
        };
    }

    /**
     * Tick label formatter for the instant compare x-axis.
     * DAY   → "HH:mm"
     * WEEK  → "EEE HH:mm" (e.g. "Mon 00:00")
     * MONTH → "d MMM"
     */
    private static StringConverter<Number> instantTickFormatter(PeriodType pt) {
        return switch (pt) {
            case DAY -> new StringConverter<>() {
                @Override public String toString(Number v) {
                    long s = v.longValue();
                    return String.format("%02d:%02d", s / 3600, (s % 3600) / 60);
                }
                @Override public Number fromString(String s) { return 0; }
            };
            case WEEK -> new StringConverter<>() {
                private static final String[] DAYS = {"Mon","Tue","Wed","Thu","Fri","Sat","Sun"};
                @Override public String toString(Number v) {
                    long s     = v.longValue();
                    int  dayIdx = (int)(s / 86_400);
                    long secInDay = s % 86_400;
                    String dayName = (dayIdx >= 0 && dayIdx < DAYS.length) ? DAYS[dayIdx] : "";
                    // Only show time label at midnight to keep x-axis readable
                    return dayName;
                }
                @Override public Number fromString(String s) { return 0; }
            };
            case MONTH -> new StringConverter<>() {
                private final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("d MMM");
                @Override public String toString(Number v) {
                    long s      = v.longValue();
                    int  dayNum = (int)(s / 86_400) + 1;   // 1-based day-of-month
                    // Show the number, not the date — both periods share the same axis
                    return String.valueOf(dayNum);
                }
                @Override public Number fromString(String s) { return 0; }
            };
        };
    }

    // =========================================================================
    // Style / color helpers
    // =========================================================================

    private static void applyStrokeStyle(
            XYChart.Series<Number, Number> series,
            String strokeStyle
    ) {
        series.nodeProperty().addListener((obs, old, n) -> {
            if (n != null) n.setStyle(strokeStyle);
        });
        if (series.getNode() != null) series.getNode().setStyle(strokeStyle);
    }

    private static void applyBarColor(
            XYChart.Series<String, Number> series,
            String hexColor,
            double opacity
    ) {
        String style = String.format("-fx-bar-fill: %s; -fx-opacity: %.2f;", hexColor, opacity);
        for (XYChart.Data<String, Number> d : series.getData()) {
            if (d.getNode() != null) {
                d.getNode().setStyle(style);
            } else {
                d.nodeProperty().addListener((obs, o, n) -> {
                    if (n != null) n.setStyle(style);
                });
            }
        }
    }

    private static void styleLegendHorizontal(Chart chart) {
        chart.legendSideProperty().set(javafx.geometry.Side.BOTTOM);
        chart.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                javafx.application.Platform.runLater(() -> {
                    javafx.scene.Node legend = chart.lookup(".chart-legend");
                    if (legend instanceof javafx.scene.layout.FlowPane fp) {
                        fp.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
                        fp.setHgap(16);
                        fp.setVgap(0);
                        fp.setPrefWrapLength(Double.MAX_VALUE);
                    }
                });
            }
        });
    }

    // =========================================================================
    // Toggle helpers
    // =========================================================================

    private static ToggleButton styledToggle(String text, ToggleGroup group) {
        ToggleButton btn = new ToggleButton(text);
        btn.setToggleGroup(group);
        String base = """
                -fx-background-color: #E2E8F0;
                -fx-text-fill: #334155;
                -fx-background-radius: 6;
                -fx-padding: 4 12;
                -fx-font-size: 12px;
                -fx-cursor: hand;
                """;
        String active = """
                -fx-background-color: #2563EB;
                -fx-text-fill: white;
                -fx-background-radius: 6;
                -fx-padding: 4 12;
                -fx-font-size: 12px;
                -fx-cursor: hand;
                """;
        btn.setStyle(base);
        btn.selectedProperty().addListener((obs, was, is) -> btn.setStyle(is ? active : base));
        return btn;
    }

    private static void selectToggle(
            ToggleGroup group,
            ToggleButton btnTotal,
            ToggleButton btnPhases,
            ToggleButton btnTariffs,
            InstantCompareMode mode
    ) {
        switch (mode) {
            case TOTAL   -> btnTotal.setSelected(true);
            case PHASES  -> btnPhases.setSelected(true);
            case TARIFFS -> btnTariffs.setSelected(true);
        }
    }

    private static InstantCompareMode currentMode(ToggleGroup group) {
        Toggle t = group.getSelectedToggle();
        if (t instanceof ToggleButton tb) {
            return switch (tb.getText()) {
                case "Phases"  -> InstantCompareMode.PHASES;
                case "Tariffs" -> InstantCompareMode.TARIFFS;
                default        -> InstantCompareMode.TOTAL;
            };
        }
        return InstantCompareMode.TOTAL;
    }

    // =========================================================================
    // Legend color helpers
    // =========================================================================

    /**
     * Fixes legend symbol colors for the two-series hourly line chart.
     * Series are matched by name, they are look up directly.
     * Fixes legend swatch colors for the two-series hourly bar chart.
     * JavaFX default color style must be overridden directly.
     */
    private static void applyHourlyBarLegendColors(
            BarChart<String, Number> chart,
            String labelP1,
            String labelP2
    ) {
        chart.applyCss();
        chart.layout();
        for (javafx.scene.Node node : chart.lookupAll(".chart-legend-item")) {
            if (!(node instanceof javafx.scene.control.Label lbl)) continue;
            String text = lbl.getText();
            if (text == null) continue;
            String color;
            if      (text.equals(labelP1)) color = COLOR_TOTAL;
            else if (text.equals(labelP2)) color = COLOR_DAY2_TOTAL;
            else continue;
            // Bar chart legend items use a rectangle node with class chart-legend-item-symbol
            javafx.scene.Node symbol = lbl.lookup(".chart-legend-item-symbol");
            if (symbol != null) {
                symbol.setStyle("-fx-background-color: " + color + "; -fx-opacity: 0.85;");
            }
        }
    }

    private static void applyHourlyLineLegendColors(
            LineChart<Number, Number> chart,
            String labelP1,
            String labelP2
    ) {
        chart.applyCss();
        chart.layout();
        for (javafx.scene.Node node : chart.lookupAll(".chart-legend-item")) {
            if (!(node instanceof javafx.scene.control.Label lbl)) continue;
            String text = lbl.getText();
            if (text == null) continue;
            String color;
            if      (text.equals(labelP1)) color = COLOR_TOTAL;
            else if (text.equals(labelP2)) color = COLOR_DAY2_TOTAL;
            else continue;
            javafx.scene.Node symbol = lbl.lookup(".chart-legend-item-symbol");
            if (symbol != null) symbol.setStyle("-fx-background-color: " + color + ", white;");
        }
    }

    private static void applyCompareLegendColors(
            LineChart<Number, Number> chart,
            String labelDay1,
            String labelDay2
    ) {
        chart.applyCss();
        chart.layout();
        for (javafx.scene.Node node : chart.lookupAll(".chart-legend-item")) {
            if (!(node instanceof javafx.scene.control.Label lbl)) continue;
            String text = lbl.getText();
            if (text == null) continue;
            boolean isDay2 = text.startsWith(labelDay2 + " ");
            boolean isDay1 = text.startsWith(labelDay1 + " ");
            if (!isDay1 && !isDay2) continue;
            String color = null;
            if      (text.endsWith(" Total")) color = isDay2 ? COLOR_DAY2_TOTAL : COLOR_TOTAL;
            else if (text.endsWith(" T1"))    color = isDay2 ? COLOR_DAY2_T1    : COLOR_T1;
            else if (text.endsWith(" T2"))    color = isDay2 ? COLOR_DAY2_T2    : COLOR_T2;
            else if (text.endsWith(" T3"))    color = isDay2 ? COLOR_DAY2_T3    : COLOR_T3;
            else if (text.endsWith(" T4"))    color = isDay2 ? COLOR_DAY2_T4    : COLOR_T4;
            else if (text.endsWith(" L1"))    color = isDay2 ? COLOR_DAY2_L1    : COLOR_L1;
            else if (text.endsWith(" L2"))    color = isDay2 ? COLOR_DAY2_L2    : COLOR_L2;
            else if (text.endsWith(" L3"))    color = isDay2 ? COLOR_DAY2_L3    : COLOR_L3;
            if (color == null) continue;
            javafx.scene.Node symbol = lbl.lookup(".chart-legend-item-symbol");
            if (symbol != null) symbol.setStyle("-fx-background-color: " + color + ", white;");
        }
    }
}
