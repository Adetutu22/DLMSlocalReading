package com.meter.gui;

import com.meter.chart.CompareChartWindow;
import com.meter.chart.EnergyChartWindow;
import javafx.concurrent.Task;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;

import com.meter.database.PushDataQueries;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

public class ChartsAnalyticsTab {

    private final PushDataQueries query;
    private final SerialResolver serialResolver;

    // ── Filter bar widgets ────────────────────────────────────────────────────
    private TextField serialField;
    private Label fromLabel;
    private Label toLabel;
    private CheckBox compareModeCheckBox;

    // ── Day-mode pickers (shown when granularity == DAYS or normal mode) ──────
    private DatePicker fromDatePicker;
    private DatePicker toDatePicker;

    // ── Week-mode pickers ─────────────────────────────────────────────────────
    private DatePicker week1Picker;
    private DatePicker week2Picker;
    private Label week1RangeLabel;
    private Label week2RangeLabel;

    // ── Month-mode pickers ────────────────────────────────────────────────────
    private ComboBox<Month> month1Combo;
    private Spinner<Integer> year1Spinner;
    private ComboBox<Month> month2Combo;
    private Spinner<Integer> year2Spinner;

    // ── Picker swap container ─────────────────────────────────────────────────
    private HBox pickerArea;

    // ── Compare granularity ───────────────────────────────────────────────────
    private enum CompareGranularity { DAYS, WEEKS, MONTHS }
    private CompareGranularity compareGranularity = CompareGranularity.DAYS;
    private HBox granularityBar;

    // ── View selector & chart area ────────────────────────────────────────────
    private ComboBox<DashboardView> viewSelector;
    private VBox chartContainer;
    private VBox analyticsContainer;
    private Label descriptionLabel;
    private Label chartTitleLabel;
    private Label chartSubtitleLabel;

    /** Price rate */
    private static final double CZK_PER_KWH = 6.16;

    @FunctionalInterface
    public interface SerialResolver {
        String resolve(String typedSerial);
    }

    public ChartsAnalyticsTab(PushDataQueries query, SerialResolver serialResolver) {
        this.query = query;
        this.serialResolver = serialResolver;
    }

    public Tab build() {
        Tab tab = new Tab("Charts & Analytics");
        tab.setClosable(false);

        VBox content = buildContent();

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setPannable(true);
        scrollPane.setStyle("-fx-background: white; -fx-background-color: white;");

        tab.setContent(scrollPane);
        return tab;
    }

    // =========================================================================
    // Layout
    // =========================================================================

    private VBox buildContent() {
        VBox root = new VBox(12);
        root.setPadding(new Insets(16));
        root.setStyle("-fx-background-color: #FFFFFF;");

        Label mainTitle = new Label("Smart Meter Analytics Dashboard");
        mainTitle.setFont(Font.font("System", FontWeight.BOLD, 22));
        mainTitle.setTextFill(Color.web("#1E40AF"));

        Separator separator = new Separator();

        Node filterBar     = buildFilterBar();
        granularityBar     = buildGranularityBar();
        granularityBar.setVisible(false);
        granularityBar.setManaged(false);

        Node viewBar         = buildViewSelectorBar();
        Node dashboardArea   = buildDashboardArea();
        Node descriptionArea = buildDescriptionArea();

        VBox.setVgrow(dashboardArea, Priority.ALWAYS);

        root.getChildren().addAll(
                mainTitle, separator,
                filterBar, granularityBar, viewBar,
                dashboardArea, descriptionArea
        );

        refreshDashboard();
        return root;
    }

    // ── Filter bar ────────────────────────────────────────────────────────────

    private Node buildFilterBar() {
        fromDatePicker = new DatePicker(LocalDate.now().minusDays(7));
        fromDatePicker.setPrefWidth(140);
        toDatePicker   = new DatePicker(LocalDate.now());
        toDatePicker.setPrefWidth(140);
        fromDatePicker.setOnAction(e -> refreshDashboard());
        toDatePicker.setOnAction(e  -> refreshDashboard());

        week1Picker = new DatePicker(mondayOf(LocalDate.now().minusWeeks(1)));
        week1Picker.setPrefWidth(130);
        week2Picker = new DatePicker(mondayOf(LocalDate.now()));
        week2Picker.setPrefWidth(130);
        week1RangeLabel = rangeLabel(weekRangeText(week1Picker.getValue()));
        week2RangeLabel = rangeLabel(weekRangeText(week2Picker.getValue()));

        week1Picker.setOnAction(e -> {
            week1Picker.setValue(mondayOf(week1Picker.getValue()));
            week1RangeLabel.setText(weekRangeText(week1Picker.getValue()));
            refreshDashboard();
        });
        week2Picker.setOnAction(e -> {
            week2Picker.setValue(mondayOf(week2Picker.getValue()));
            week2RangeLabel.setText(weekRangeText(week2Picker.getValue()));
            refreshDashboard();
        });

        int currentYear = LocalDate.now().getYear();
        month1Combo  = monthCombo(LocalDate.now().minusMonths(1).getMonth());
        year1Spinner = yearSpinner(currentYear);
        month2Combo  = monthCombo(LocalDate.now().getMonth());
        year2Spinner = yearSpinner(currentYear);

        month1Combo.setOnAction(e  -> refreshDashboard());
        year1Spinner.valueProperty().addListener((o, ov, nv) -> refreshDashboard());
        month2Combo.setOnAction(e  -> refreshDashboard());
        year2Spinner.valueProperty().addListener((o, ov, nv) -> refreshDashboard());

        fromLabel = new Label("From:");
        toLabel   = new Label("To:");

        pickerArea = new HBox(8);
        pickerArea.setAlignment(Pos.CENTER_LEFT);
        populateDayPickerArea();

        compareModeCheckBox = new CheckBox("Compare Mode");
        compareModeCheckBox.setStyle("-fx-font-size: 13px;");
        compareModeCheckBox.setOnAction(e -> {
            boolean on = compareModeCheckBox.isSelected();
            granularityBar.setVisible(on);
            granularityBar.setManaged(on);
            if (on && fromDatePicker.getValue() != null
                    && fromDatePicker.getValue().equals(toDatePicker.getValue())) {
                toDatePicker.setValue(fromDatePicker.getValue().minusDays(1));
            }
            updateDateLabels();
            swapPickerArea();
            refreshDashboard();
        });

        Label serialLbl = new Label("Serial Number:");
        serialField = new TextField();
        serialField.setPromptText("Auto / last detected");
        serialField.setPrefWidth(180);
        serialField.setOnAction(e -> refreshDashboard());

        Button useLastBtn = new Button("Use Last");
        useLastBtn.setOnAction(e -> {
            serialField.setText(serialResolver.resolve(""));
            refreshDashboard();
        });

        Button refreshBtn = new Button("Refresh");
        stylePrimaryButton(refreshBtn);
        refreshBtn.setOnAction(e -> refreshDashboard());

        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().addAll(
                pickerArea,
                compareModeCheckBox,
                serialLbl, serialField,
                useLastBtn, refreshBtn
        );

        VBox card = new VBox(row);
        card.setPadding(new Insets(10));
        card.setStyle(cardStyle());

        updateDateLabels();
        return card;
    }

    // ── Picker area population helpers ────────────────────────────────────────

    private void populateDayPickerArea() {
        pickerArea.getChildren().setAll(fromLabel, fromDatePicker, toLabel, toDatePicker);
    }

    private void populateWeekPickerArea() {
        pickerArea.getChildren().setAll(
                fromLabel, week1Picker, week1RangeLabel,
                toLabel,   week2Picker, week2RangeLabel
        );
    }

    private void populateMonthPickerArea() {
        pickerArea.getChildren().setAll(
                fromLabel, month1Combo, year1Spinner,
                toLabel,   month2Combo, year2Spinner
        );
    }

    private void swapPickerArea() {
        if (!isCompareMode() || compareGranularity == CompareGranularity.DAYS) {
            populateDayPickerArea();
        } else if (compareGranularity == CompareGranularity.WEEKS) {
            populateWeekPickerArea();
        } else {
            populateMonthPickerArea();
        }
    }

    // ── Granularity bar ───────────────────────────────────────────────────────

    private HBox buildGranularityBar() {
        Label lbl = new Label("Compare by:");
        lbl.setFont(Font.font("System", FontWeight.SEMI_BOLD, 13));

        ToggleGroup group = new ToggleGroup();
        ToggleButton btnDays   = granularityToggle("Days",   group);
        ToggleButton btnWeeks  = granularityToggle("Weeks",  group);
        ToggleButton btnMonths = granularityToggle("Months", group);
        btnDays.setSelected(true);

        group.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            if (newT == null) { if (oldT != null) oldT.setSelected(true); return; }
            CompareGranularity prev = compareGranularity;
            if (newT == btnDays)        compareGranularity = CompareGranularity.DAYS;
            else if (newT == btnWeeks)  compareGranularity = CompareGranularity.WEEKS;
            else                        compareGranularity = CompareGranularity.MONTHS;

            snapPickersOnGranularityChange(prev, compareGranularity);
            updateDateLabels();
            swapPickerArea();
            refreshDashboard();
        });

        HBox bar = new HBox(8, lbl, btnDays, btnWeeks, btnMonths);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 10, 6, 10));
        bar.setStyle(cardStyle());
        return bar;
    }

    private void snapPickersOnGranularityChange(CompareGranularity from, CompareGranularity to) {
        LocalDate anchor1 = getAnchor1(from);
        LocalDate anchor2 = getAnchor2(from);

        switch (to) {
            case DAYS -> {
                if (anchor1 != null) fromDatePicker.setValue(anchor1);
                if (anchor2 != null) toDatePicker.setValue(anchor2);
            }
            case WEEKS -> {
                if (anchor1 != null) {
                    LocalDate mon1 = mondayOnOrAfter(anchor1);
                    week1Picker.setValue(mon1);
                    week1RangeLabel.setText(weekRangeText(mon1));
                }
                if (anchor2 != null) {
                    LocalDate mon2 = mondayOnOrAfter(anchor2);
                    week2Picker.setValue(mon2);
                    week2RangeLabel.setText(weekRangeText(mon2));
                }
            }
            case MONTHS -> {
                if (anchor1 != null) {
                    month1Combo.setValue(anchor1.getMonth());
                    year1Spinner.getValueFactory().setValue(anchor1.getYear());
                }
                if (anchor2 != null) {
                    month2Combo.setValue(anchor2.getMonth());
                    year2Spinner.getValueFactory().setValue(anchor2.getYear());
                }
            }
        }
    }

    private LocalDate getAnchor1(CompareGranularity g) {
        return switch (g) {
            case DAYS   -> fromDatePicker.getValue();
            case WEEKS  -> week1Picker.getValue();
            case MONTHS -> LocalDate.of(
                    year1Spinner.getValue(),
                    month1Combo.getValue() != null ? month1Combo.getValue() : Month.JANUARY,
                    1);
        };
    }

    private LocalDate getAnchor2(CompareGranularity g) {
        return switch (g) {
            case DAYS   -> toDatePicker.getValue();
            case WEEKS  -> week2Picker.getValue();
            case MONTHS -> LocalDate.of(
                    year2Spinner.getValue(),
                    month2Combo.getValue() != null ? month2Combo.getValue() : Month.JANUARY,
                    1);
        };
    }

    // ── View selector ─────────────────────────────────────────────────────────

    private Node buildViewSelectorBar() {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);

        Label viewLbl = new Label("View:");
        viewLbl.setFont(Font.font("System", FontWeight.SEMI_BOLD, 13));

        viewSelector = new ComboBox<>();
        viewSelector.getItems().addAll(
                DashboardView.HOURLY_IMPORT_ENERGY,
                DashboardView.HOURLY_EXPORT_ENERGY,
                DashboardView.TOTAL_CONSUMPTION_IMPORT,
                DashboardView.HOURLY_REGISTER_OVERVIEW,
                DashboardView.INSTANT_IMPORT_POWER_PHASE,
                DashboardView.INSTANT_IMPORT_POWER_TARIFF,
                DashboardView.INSTANT_EXPORT_POWER
        );
        viewSelector.setValue(DashboardView.HOURLY_IMPORT_ENERGY);
        viewSelector.setPrefWidth(300);
        viewSelector.setOnAction(e -> refreshDashboard());

        row.getChildren().addAll(viewLbl, viewSelector);

        VBox card = new VBox(row);
        card.setPadding(new Insets(14));
        card.setStyle(cardStyle());
        return card;
    }

    // ── Dashboard area ────────────────────────────────────────────────────────

    private Node buildDashboardArea() {
        chartTitleLabel = new Label("Chart Title");
        chartTitleLabel.setFont(Font.font("System", FontWeight.BOLD, 18));
        chartTitleLabel.setTextFill(Color.web("#0F172A"));

        chartSubtitleLabel = new Label("Chart subtitle");
        chartSubtitleLabel.setFont(Font.font("System", 12));
        chartSubtitleLabel.setTextFill(Color.web("#64748B"));

        chartContainer = new VBox();
        chartContainer.setAlignment(Pos.CENTER);
        chartContainer.setPadding(new Insets(2));
        chartContainer.setMinHeight(300);
        chartContainer.setPrefHeight(340);
        chartContainer.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(chartContainer, Priority.ALWAYS);
        chartContainer.setStyle("""
            -fx-background-color: white;
            -fx-border-color: #E2E8F0;
            -fx-border-radius: 10;
            -fx-background-radius: 10;
        """);

        VBox chartCard = new VBox(10, chartTitleLabel, chartSubtitleLabel, chartContainer);
        chartCard.setPadding(new Insets(12));
        chartCard.setStyle(cardStyle());
        chartCard.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(chartCard, Priority.ALWAYS);
        VBox.setVgrow(chartContainer, Priority.ALWAYS);

        Label analyticsTitle = new Label("Analytics Summary");
        analyticsTitle.setFont(Font.font("System", FontWeight.BOLD, 16));
        analyticsTitle.setTextFill(Color.web("#0F172A"));

        analyticsContainer = new VBox(6);
        analyticsContainer.setPadding(new Insets(0));

        VBox analyticsCard = new VBox(6, analyticsTitle, analyticsContainer);
        analyticsCard.setPadding(new Insets(10, 12, 12, 12));
        analyticsCard.setStyle(cardStyle());
        analyticsCard.setPrefWidth(260);
        analyticsCard.setMinWidth(240);

        HBox main = new HBox(12, chartCard, analyticsCard);
        main.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(chartCard, Priority.ALWAYS);
        return main;
    }

    private Node buildDescriptionArea() {
        descriptionLabel = new Label();
        descriptionLabel.setWrapText(true);
        descriptionLabel.setTextFill(Color.web("#475569"));
        descriptionLabel.setFont(Font.font("System", 12));
        VBox box = new VBox(descriptionLabel);
        box.setPadding(new Insets(8, 4, 0, 4));
        return box;
    }

    // =========================================================================
    // CSV export – central helper
    // =========================================================================

    /**
     * Places chart into chartContainer, wrapping it in a StackPane that
     * overlays a Save CSV button in the top-right corner.
     *
     * @param chart   the fully-built chart or wrapper node to display
     * @param headers CSV column headers (first row)
     * @param rows    CSV data rows; each array must be the same length as headers
     */
    private void setChart(Node chart, List<String> headers, List<String[]> rows) {
        Button btn = new Button("Save CSV");
        btn.setStyle("""
                -fx-background-color: rgba(255,255,255,0.88);
                -fx-text-fill: #334155;
                -fx-background-radius: 6;
                -fx-padding: 4 10;
                -fx-font-size: 11px;
                -fx-cursor: hand;
                -fx-border-color: #cbd5e1;
                -fx-border-radius: 6;
                -fx-border-width: 1px;
                """);

        btn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Save chart data as CSV");
            fc.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("CSV files", "*.csv"));
            fc.setInitialFileName("chart-data.csv");

            Window window = chartContainer.getScene() != null
                    ? chartContainer.getScene().getWindow() : null;
            File file = fc.showSaveDialog(window);
            if (file == null) return;

            try (PrintWriter pw = new PrintWriter(file)) {
                pw.println(String.join(",", headers));
                for (String[] row : rows) pw.println(String.join(",", row));
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        });

        if (chart instanceof Region r) r.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(chart, Priority.ALWAYS);

        StackPane.setAlignment(btn, Pos.TOP_RIGHT);
        StackPane.setMargin(btn, new Insets(8, 12, 0, 0));

        StackPane pane = new StackPane(chart, btn);
        pane.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(pane, Priority.ALWAYS);

        chartContainer.getChildren().setAll(pane);
    }

    // =========================================================================
    // Range resolution
    // =========================================================================

    private LocalDate[] range1() {
        if (!isCompareMode()) {
            return new LocalDate[]{ fromDatePicker.getValue(), toDatePicker.getValue() };
        }
        return resolveRange(getAnchor1(compareGranularity), compareGranularity);
    }

    private LocalDate[] range2() {
        return resolveRange(getAnchor2(compareGranularity), compareGranularity);
    }

    private LocalDate[] resolveRange(LocalDate anchor, CompareGranularity gran) {
        if (anchor == null) return new LocalDate[]{ LocalDate.now(), LocalDate.now() };
        return switch (gran) {
            case DAYS   -> new LocalDate[]{ anchor, anchor };
            case WEEKS  -> new LocalDate[]{ mondayOf(anchor), sundayOf(anchor) };
            case MONTHS -> {
                YearMonth ym = YearMonth.of(anchor.getYear(), anchor.getMonth());
                yield new LocalDate[]{ ym.atDay(1), ym.atEndOfMonth() };
            }
        };
    }

    // =========================================================================
    // Dispatch
    // =========================================================================

    private void refreshDashboard() {
        DashboardView selectedView = viewSelector == null
                ? DashboardView.HOURLY_IMPORT_ENERGY
                : viewSelector.getValue();
        if (selectedView == null) return;

        switch (selectedView) {
            case HOURLY_IMPORT_ENERGY        -> loadHourlyImportEnergyView();
            case HOURLY_EXPORT_ENERGY        -> loadHourlyExportEnergyView();
            case HOURLY_REGISTER_OVERVIEW    -> loadTotalProductionExportView();
            case INSTANT_IMPORT_POWER_PHASE  -> loadInstantImportPowerPhaseView();
            case INSTANT_IMPORT_POWER_TARIFF -> loadInstantImportPowerTariffView();
            case INSTANT_EXPORT_POWER        -> loadInstantExportPowerView();
            case TOTAL_CONSUMPTION_IMPORT    -> loadTotalConsumptionImportView();
        }
    }

    // =========================================================================
    // HOURLY IMPORT ENERGY
    // =========================================================================

    private void loadHourlyImportEnergyView() {
        chartTitleLabel.setText("Hourly Imported Energy");
        chartSubtitleLabel.setText(buildSubtitle());
        descriptionLabel.setText(isCompareMode()
                ? "Grouped bar chart comparing hourly imported energy for the two selected "
                + compareGranularity.name().toLowerCase() + "."
                + "Each pair of bars shows Period 1 vs Period 2 for that hour."
                : "Multi-line view of hourly imported energy across all tariffs in the selected range. "
                + "Analytics summarize total consumption, extrema with timestamps, and tariff contribution."
        );

        if (!validateDates()) return;

        String serial    = serial();
        LocalDate[] r1   = range1();
        LocalDate start1 = r1[0], end1 = r1[1];

        chartContainer.getChildren().setAll(createChartPlaceholder("Loading chart…"));

        if (isCompareMode()) {
            LocalDate[] r2   = range2();
            LocalDate start2 = r2[0], end2 = r2[1];

            Task<Void> task = new Task<>() {
                List<EnergyChartWindow.HourlyEnergyPoint> pts1, pts2;
                double total1, total2, avg1, avg2;
                PushDataQueries.TimedValuePoint minPt1, maxPt1, minPt2, maxPt2;

                @Override protected Void call() throws Exception {
                    pts1 = convertHourly(query.getHourlyImportEnergyBetween(serial, start1, end1, "All"));
                    pts2 = convertHourly(query.getHourlyImportEnergyBetween(serial, start2, end2, "All"));
                    total1 = pts1.stream().mapToDouble(EnergyChartWindow.HourlyEnergyPoint::kwh).sum();
                    total2 = pts2.stream().mapToDouble(EnergyChartWindow.HourlyEnergyPoint::kwh).sum();
                    avg1   = query.getAvgHourlyImportEnergyBetween(serial, start1, end1, "All");
                    avg2   = query.getAvgHourlyImportEnergyBetween(serial, start2, end2, "All");
                    minPt1 = query.getMinImportPowerTotal(serial, start1, end1);
                    maxPt1 = query.getMaxImportPowerTotal(serial, start1, end1);
                    minPt2 = query.getMinImportPowerTotal(serial, start2, end2);
                    maxPt2 = query.getMaxImportPowerTotal(serial, start2, end2);
                    return null;
                }

                @Override protected void succeeded() {
                    String lbl1 = periodLabel(start1, end1);
                    String lbl2 = periodLabel(start2, end2);

                    VBox compareNode = CompareChartWindow.buildHourlyCompareNode(
                            pts1, pts2, lbl1, lbl2, "Import Energy (kWh)",
                            toPeriodType(), start1, start2, mode -> {});
                    VBox.setVgrow(compareNode, Priority.ALWAYS);
                    List<String[]> csvRowsHIC = new java.util.ArrayList<>();
                    for (EnergyChartWindow.HourlyEnergyPoint p : pts1)
                        csvRowsHIC.add(new String[]{ String.valueOf(p.hourMsUtc()), lbl1, String.format("%.4f", p.kwh()) });
                    for (EnergyChartWindow.HourlyEnergyPoint p : pts2)
                        csvRowsHIC.add(new String[]{ String.valueOf(p.hourMsUtc()), lbl2, String.format("%.4f", p.kwh()) });
                    setChart(compareNode, List.of("EpochMs", "Period", "kWh"), csvRowsHIC);

                    analyticsContainer.getChildren().setAll(
                            createCompareSectionHeader(lbl1, lbl2),
                            createCompareRow("Total Import",
                                    formatKwh(total1), formatKwh(total2), deltaKwh(total1, total2)),
                            createCompareRow("Minimum",
                                    minPt1 == null ? "--" : formatKwh(minPt1.value()),
                                    minPt2 == null ? "--" : formatKwh(minPt2.value()), ""),
                            createCompareRow("Min Time",
                                    minPt1 == null ? "--" : formatDateTime(minPt1.tsMsUtc()),
                                    minPt2 == null ? "--" : formatDateTime(minPt2.tsMsUtc()), ""),
                            createCompareRow("Maximum",
                                    maxPt1 == null ? "--" : formatKwh(maxPt1.value()),
                                    maxPt2 == null ? "--" : formatKwh(maxPt2.value()), ""),
                            createCompareRow("Max Time",
                                    maxPt1 == null ? "--" : formatDateTime(maxPt1.tsMsUtc()),
                                    maxPt2 == null ? "--" : formatDateTime(maxPt2.tsMsUtc()), ""),
                            createCompareRow("Average/h",
                                    formatKwh(avg1), formatKwh(avg2), deltaKwh(avg1, avg2))
                    );
                }

                @Override protected void failed() {
                    getException().printStackTrace();
                    chartContainer.getChildren().setAll(createChartPlaceholder("Failed to load chart"));
                }
            };
            runTask(task, "compare-hourly-import");

        } else {
            LocalDate end = end1;

            Task<Void> task = new Task<>() {
                List<EnergyChartWindow.HourlyEnergyPoint> pAll, p1, p2, p3, p4;
                double total, avg, t1Sum, t2Sum, t3Sum, t4Sum;
                PushDataQueries.TimedValuePoint minPoint, maxPoint;
                long startMs, endMs;

                @Override protected Void call() throws Exception {
                    pAll = convertHourly(query.getHourlyImportEnergyBetween(serial, start1, end, "All"));
                    p1   = convertHourly(query.getHourlyImportEnergyBetween(serial, start1, end, "T1"));
                    p2   = convertHourly(query.getHourlyImportEnergyBetween(serial, start1, end, "T2"));
                    p3   = convertHourly(query.getHourlyImportEnergyBetween(serial, start1, end, "T3"));
                    p4   = convertHourly(query.getHourlyImportEnergyBetween(serial, start1, end, "T4"));
                    startMs = EnergyChartWindow.midnightMs(start1);
                    endMs   = EnergyChartWindow.midnightMs(end.plusDays(1));
                    t1Sum = p1.stream().mapToDouble(EnergyChartWindow.HourlyEnergyPoint::kwh).sum();
                    t2Sum = p2.stream().mapToDouble(EnergyChartWindow.HourlyEnergyPoint::kwh).sum();
                    t3Sum = p3.stream().mapToDouble(EnergyChartWindow.HourlyEnergyPoint::kwh).sum();
                    t4Sum = p4.stream().mapToDouble(EnergyChartWindow.HourlyEnergyPoint::kwh).sum();
                    total = pAll.stream().mapToDouble(EnergyChartWindow.HourlyEnergyPoint::kwh).sum();
                    avg   = query.getAvgHourlyImportEnergyBetween(serial, start1, end, "All");
                    minPoint = query.getMinImportPowerTotal(serial, start1, end);
                    maxPoint = query.getMaxImportPowerTotal(serial, start1, end);
                    return null;
                }

                @Override protected void succeeded() {
                    Node chartNode = EnergyChartWindow.buildHourlyImportTariffMultiLineChart(
                            pAll, p1, p2, p3, p4,
                            "Hourly Import Energy (All + T1–T4)", startMs, endMs);
                    double safeTotal = (t1Sum + t2Sum + t3Sum + t4Sum);
                    if (safeTotal <= 0) safeTotal = 1.0;
                    List<String[]> csvRowsHI = EnergyChartWindow.hourlyMultiSeriesToRows(
                            Map.entry("Total", pAll), Map.entry("T1", p1),
                            Map.entry("T2", p2), Map.entry("T3", p3), Map.entry("T4", p4));
                    setChart(chartNode, List.of("EpochMs", "DateTime (UTC)", "Series", "kWh"), csvRowsHI);
                    analyticsContainer.getChildren().setAll(
                            createMetricCard("Total Import", formatKwh(total)),
                            createMetricCard("Minimum", minPoint == null ? "-- kWh" : formatKwh(minPoint.value())),
                            createMetricCard("Min Time", minPoint == null ? "--" : formatDateTime(minPoint.tsMsUtc())),
                            createMetricCard("Maximum", maxPoint == null ? "-- kWh" : formatKwh(maxPoint.value())),
                            createMetricCard("Max Time", maxPoint == null ? "--" : formatDateTime(maxPoint.tsMsUtc())),
                            createMetricCard("Average/h", formatKwh(avg)),
                            createSectionHeader("Tariff Contribution"),
                            createContributionRow("T1", formatKwh(t1Sum), formatPercent(t1Sum / safeTotal)),
                            createContributionRow("T2", formatKwh(t2Sum), formatPercent(t2Sum / safeTotal)),
                            createContributionRow("T3", formatKwh(t3Sum), formatPercent(t3Sum / safeTotal)),
                            createContributionRow("T4", formatKwh(t4Sum), formatPercent(t4Sum / safeTotal))
                    );
                }

                @Override protected void failed() {
                    getException().printStackTrace();
                    chartContainer.getChildren().setAll(createChartPlaceholder("Failed to load chart"));
                }
            };
            runTask(task, "hourly-import-dashboard");
        }
    }

    // =========================================================================
    // HOURLY EXPORT ENERGY
    // =========================================================================

    private void loadHourlyExportEnergyView() {
        chartTitleLabel.setText("Hourly Exported Energy");
        chartSubtitleLabel.setText(buildSubtitle());
        descriptionLabel.setText(isCompareMode()
                ? "Grouped bar chart comparing hourly exported energy for the two selected "
                + compareGranularity.name().toLowerCase() + "."
                : "Time-series view of hourly exported energy in the selected range."
        );

        if (!validateDates()) return;

        String serial    = serial();
        LocalDate[] r1   = range1();
        LocalDate start1 = r1[0], end1 = r1[1];

        chartContainer.getChildren().setAll(createChartPlaceholder("Loading chart…"));

        if (isCompareMode()) {
            LocalDate[] r2   = range2();
            LocalDate start2 = r2[0], end2 = r2[1];

            Task<Void> task = new Task<>() {
                List<EnergyChartWindow.HourlyEnergyPoint> pts1, pts2;
                double total1, total2, avg1, avg2;
                PushDataQueries.TimedValuePoint minPt1, maxPt1, minPt2, maxPt2;

                @Override protected Void call() throws Exception {
                    pts1 = convertHourly(query.getHourlyExportEnergyBetween(serial, start1, end1));
                    pts2 = convertHourly(query.getHourlyExportEnergyBetween(serial, start2, end2));
                    total1 = pts1.stream().mapToDouble(EnergyChartWindow.HourlyEnergyPoint::kwh).sum();
                    total2 = pts2.stream().mapToDouble(EnergyChartWindow.HourlyEnergyPoint::kwh).sum();
                    avg1   = query.getAvgHourlyExportEnergyBetween(serial, start1, end1);
                    avg2   = query.getAvgHourlyExportEnergyBetween(serial, start2, end2);
                    minPt1 = query.getMinExportPowerTotal(serial, start1, end1);
                    maxPt1 = query.getMaxExportPowerTotal(serial, start1, end1);
                    minPt2 = query.getMinExportPowerTotal(serial, start2, end2);
                    maxPt2 = query.getMaxExportPowerTotal(serial, start2, end2);
                    return null;
                }

                @Override protected void succeeded() {
                    String lbl1 = periodLabel(start1, end1);
                    String lbl2 = periodLabel(start2, end2);

                    VBox compareNode = CompareChartWindow.buildHourlyCompareNode(
                            pts1, pts2, lbl1, lbl2, "Export Energy (kWh)",
                            toPeriodType(), start1, start2, mode -> {});
                    VBox.setVgrow(compareNode, Priority.ALWAYS);
                    List<String[]> csvRowsHEC = new java.util.ArrayList<>();
                    for (EnergyChartWindow.HourlyEnergyPoint p : pts1)
                        csvRowsHEC.add(new String[]{ String.valueOf(p.hourMsUtc()), lbl1, String.format("%.4f", p.kwh()) });
                    for (EnergyChartWindow.HourlyEnergyPoint p : pts2)
                        csvRowsHEC.add(new String[]{ String.valueOf(p.hourMsUtc()), lbl2, String.format("%.4f", p.kwh()) });
                    setChart(compareNode, List.of("EpochMs", "Period", "kWh"), csvRowsHEC);

                    analyticsContainer.getChildren().setAll(
                            createCompareSectionHeader(lbl1, lbl2),
                            createCompareRow("Total Export",
                                    formatKwh(total1), formatKwh(total2), deltaKwh(total1, total2)),
                            createCompareRow("Minimum",
                                    minPt1 == null ? "--" : formatKwh(minPt1.value()),
                                    minPt2 == null ? "--" : formatKwh(minPt2.value()), ""),
                            createCompareRow("Min Time",
                                    minPt1 == null ? "--" : formatDateTime(minPt1.tsMsUtc()),
                                    minPt2 == null ? "--" : formatDateTime(minPt2.tsMsUtc()), ""),
                            createCompareRow("Maximum",
                                    maxPt1 == null ? "--" : formatKwh(maxPt1.value()),
                                    maxPt2 == null ? "--" : formatKwh(maxPt2.value()), ""),
                            createCompareRow("Max Time",
                                    maxPt1 == null ? "--" : formatDateTime(maxPt1.tsMsUtc()),
                                    maxPt2 == null ? "--" : formatDateTime(maxPt2.tsMsUtc()), ""),
                            createCompareRow("Average/h",
                                    formatKwh(avg1), formatKwh(avg2), deltaKwh(avg1, avg2))
                    );
                }

                @Override protected void failed() {
                    getException().printStackTrace();
                    chartContainer.getChildren().setAll(createChartPlaceholder("Failed to load chart"));
                }
            };
            runTask(task, "compare-hourly-export");

        } else {
            LocalDate end = end1;

            Task<Void> task = new Task<>() {
                List<EnergyChartWindow.HourlyEnergyPoint> points;
                double total, avg;
                PushDataQueries.TimedValuePoint minPoint, maxPoint;
                long startMs, endMs;

                @Override protected Void call() throws Exception {
                    points   = convertHourly(query.getHourlyExportEnergyBetween(serial, start1, end));
                    startMs  = EnergyChartWindow.midnightMs(start1);
                    endMs    = EnergyChartWindow.midnightMs(end.plusDays(1));
                    total    = points.stream().mapToDouble(EnergyChartWindow.HourlyEnergyPoint::kwh).sum();
                    avg      = query.getAvgHourlyExportEnergyBetween(serial, start1, end);
                    minPoint = query.getMinExportPowerTotal(serial, start1, end);
                    maxPoint = query.getMaxExportPowerTotal(serial, start1, end);
                    return null;
                }

                @Override protected void succeeded() {
                    Node chartNode = EnergyChartWindow.buildHourlyExportEnergyChart(
                            points, serial, start1 + " to " + end, startMs, endMs);
                    setChart(chartNode,
                            List.of("EpochMs", "DateTime (UTC)", "kWh"),
                            EnergyChartWindow.hourlyPointsToRows(points));
                    analyticsContainer.getChildren().setAll(
                            createMetricCard("Total Export", formatKwh(total)),
                            createMetricCard("Minimum", minPoint == null ? "--" : formatKwh(minPoint.value())),
                            createMetricCard("Min Time", minPoint == null ? "--" : formatDateTime(minPoint.tsMsUtc())),
                            createMetricCard("Maximum", maxPoint == null ? "--" : formatKwh(maxPoint.value())),
                            createMetricCard("Max Time", maxPoint == null ? "--" : formatDateTime(maxPoint.tsMsUtc())),
                            createMetricCard("Average/h", formatKwh(avg))
                    );
                }

                @Override protected void failed() {
                    getException().printStackTrace();
                    chartContainer.getChildren().setAll(createChartPlaceholder("Failed to load chart"));
                }
            };
            runTask(task, "hourly-export-dashboard");
        }
    }

    // =========================================================================
    // TOTAL PRODUCTION (EXPORT)
    // =========================================================================

    /**
     * Shows the cumulative export register (MAX reading per hour) so the line
     * represents the true running total kWh produced/exported.
     * An in-chart Y-Axis toggle switches between kWh and CZK.
     */
    private void loadTotalProductionExportView() {
        chartTitleLabel.setText("Total Production (Export)");
        chartSubtitleLabel.setText(buildSubtitle());
        descriptionLabel.setText(isCompareMode()
                ? "Overlay line chart comparing the cumulative export register for two "
                + compareGranularity.name().toLowerCase() + ". "
                + "Use the Y-Axis toggle to switch between energy (kWh) and estimated "
                + "earnings (CZK)."
                : "Cumulative export register (total kWh produced/exported) for the selected range. "
                + "Use the Y-Axis toggle to switch between energy (kWh) and estimated "
                + "earnings (CZK)."
        );

        if (!validateDates()) return;

        String serial    = serial();
        LocalDate[] r1   = range1();
        LocalDate start1 = r1[0], end1 = r1[1];

        chartContainer.getChildren().setAll(createChartPlaceholder("Loading chart…"));

        if (isCompareMode()) {
            // ── COMPARE MODE ──────────────────────────────────────────────────
            LocalDate[] r2   = range2();
            LocalDate start2 = r2[0], end2 = r2[1];

            Task<Void> task = new Task<>() {
                List<EnergyChartWindow.HourlyEnergyPoint> raw1, raw2;
                double exportStart1, exportEnd1, exportStart2, exportEnd2;
                double periodTotal1, periodTotal2;

                @Override protected Void call() throws Exception {
                    raw1 = convertHourly(query.getHourlyExportRegisterBetween(serial, start1, end1));
                    raw2 = convertHourly(query.getHourlyExportRegisterBetween(serial, start2, end2));
                    exportStart1  = raw1.isEmpty() ? 0 : raw1.get(0).kwh();
                    exportEnd1    = raw1.isEmpty() ? 0 : raw1.get(raw1.size() - 1).kwh();
                    exportStart2  = raw2.isEmpty() ? 0 : raw2.get(0).kwh();
                    exportEnd2    = raw2.isEmpty() ? 0 : raw2.get(raw2.size() - 1).kwh();
                    periodTotal1  = exportEnd1 - exportStart1;
                    periodTotal2  = exportEnd2 - exportStart2;
                    return null;
                }

                @Override protected void succeeded() {
                    String lbl1 = periodLabel(start1, end1);
                    String lbl2 = periodLabel(start2, end2);

                    final boolean[] showPrice = { false };

                    ToggleGroup axisGroup  = new ToggleGroup();
                    ToggleButton btnEnergy = axisToggle("Energy (kWh)", axisGroup);
                    ToggleButton btnPrice  = axisToggle("Earnings (CZK)", axisGroup);
                    btnEnergy.setSelected(true);

                    VBox chartHolder = new VBox();
                    VBox.setVgrow(chartHolder, Priority.ALWAYS);
                    chartHolder.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

                    // Capture final values
                    final double fExportStart1 = exportStart1, fExportEnd1 = exportEnd1;
                    final double fExportStart2 = exportStart2, fExportEnd2 = exportEnd2;
                    final double fPeriodTotal1 = periodTotal1, fPeriodTotal2 = periodTotal2;

                    Runnable rebuildCompare = () -> {
                        boolean pm = showPrice[0];

                        List<EnergyChartWindow.HourlyEnergyPoint> dp1 = pm ? scaleToCzk(raw1) : raw1;
                        List<EnergyChartWindow.HourlyEnergyPoint> dp2 = pm ? scaleToCzk(raw2) : raw2;

                        // Export-only: pass empty lists for the import series
                        javafx.scene.chart.LineChart<Number, Number> chart =
                                CompareChartWindow.buildRegisterCompareChart(
                                        java.util.List.of(), java.util.List.of(),
                                        dp1, dp2,
                                        lbl1, lbl2,
                                        toPeriodType(), start1, start2);
                        chart.getYAxis().setLabel(pm ? "Earnings (CZK)" : "Total Export (kWh)");
                        chart.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
                        VBox.setVgrow(chart, Priority.ALWAYS);
                        chartHolder.getChildren().setAll(chart);

                        analyticsContainer.getChildren().setAll(
                                createCompareSectionHeader(lbl1, lbl2),
                                createCompareRow("Start Value",
                                        formatKwh(fExportStart1), formatKwh(fExportStart2), ""),
                                createCompareRow("End Value",
                                        formatKwh(fExportEnd1), formatKwh(fExportEnd2), ""),
                                createCompareRow("Period total (kWh)",
                                        formatKwh(fPeriodTotal1), formatKwh(fPeriodTotal2),
                                        deltaKwh(fPeriodTotal1, fPeriodTotal2)),
                                createCompareRow("Period total (CZK)",
                                        formatCzk(fPeriodTotal1 * CZK_PER_KWH),
                                        formatCzk(fPeriodTotal2 * CZK_PER_KWH),
                                        deltaCzk(fPeriodTotal1 * CZK_PER_KWH, fPeriodTotal2 * CZK_PER_KWH)),
                                createSectionHeader("Rate"),
                                createMetricCard("Price rate", CZK_PER_KWH * 1000 + " CZK / MWh"),
                                createMetricCard("= per kWh",  CZK_PER_KWH + " CZK / kWh")
                        );
                    };

                    axisGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
                        if (newT == null) { if (oldT != null) oldT.setSelected(true); return; }
                        showPrice[0] = (newT == btnPrice);
                        rebuildCompare.run();
                    });

                    rebuildCompare.run();

                    HBox toolbar = buildAxisToolbar(btnEnergy, btnPrice);
                    VBox wrapper = new VBox(6, toolbar, chartHolder);
                    VBox.setVgrow(chartHolder, Priority.ALWAYS);
                    VBox.setVgrow(wrapper, Priority.ALWAYS);
                    List<String[]> csvRowsTPEC = EnergyChartWindow.hourlyMultiSeriesToRows(
                            Map.entry(lbl1 + " Export", raw1),
                            Map.entry(lbl2 + " Export", raw2));
                    setChart(wrapper, List.of("EpochMs", "DateTime (UTC)", "Series", "kWh"), csvRowsTPEC);
                }

                @Override protected void failed() {
                    getException().printStackTrace();
                    chartContainer.getChildren().setAll(createChartPlaceholder("Failed to load chart"));
                }
            };
            runTask(task, "compare-total-production-export");

        } else {
            // ── NORMAL (RANGE) MODE ───────────────────────────────────────────
            LocalDate end = end1;

            Task<Void> task = new Task<>() {
                List<EnergyChartWindow.HourlyEnergyPoint> points;
                double exportStart, exportEnd, periodTotal;
                long startMs, endMs;

                @Override protected Void call() throws Exception {
                    points      = convertHourly(query.getHourlyExportRegisterBetween(serial, start1, end));
                    startMs     = EnergyChartWindow.midnightMs(start1);
                    endMs       = EnergyChartWindow.midnightMs(end.plusDays(1));
                    exportStart = points.isEmpty() ? 0 : points.get(0).kwh();
                    exportEnd   = points.isEmpty() ? 0 : points.get(points.size() - 1).kwh();
                    periodTotal = exportEnd - exportStart;
                    return null;
                }

                @Override protected void succeeded() {

                    final boolean[] showPrice = { false };

                    ToggleGroup axisGroup  = new ToggleGroup();
                    ToggleButton btnEnergy = axisToggle("Energy (kWh)", axisGroup);
                    ToggleButton btnPrice  = axisToggle("Earnings (CZK)", axisGroup);
                    btnEnergy.setSelected(true);

                    VBox chartHolder = new VBox();
                    VBox.setVgrow(chartHolder, Priority.ALWAYS);
                    chartHolder.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

                    // Capture final values
                    final double fExportStart = exportStart;
                    final double fExportEnd   = exportEnd;
                    final double fPeriodTotal = periodTotal;

                    Runnable rebuildChart = () -> {
                        boolean pm = showPrice[0];

                        List<EnergyChartWindow.HourlyEnergyPoint> displayPts =
                                pm ? scaleToCzk(points) : points;

                        String yLabel = pm ? "Earnings (CZK)" : "Total Export (kWh)";
                        String title  = pm
                                ? "Total Export Earnings (CZK)"
                                : "Total Production (Export, kWh)";

                        var data = displayPts.stream()
                                .map(p -> new javafx.scene.chart.XYChart.Data<Number, Number>(
                                        p.hourMsUtc(), p.kwh()))
                                .collect(java.util.stream.Collectors.toList());

                        javafx.scene.chart.LineChart<Number, Number> chart =
                                EnergyChartWindow.buildLineChart(
                                        title, yLabel, "Export",
                                        data, startMs, endMs);
                        chart.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
                        VBox.setVgrow(chart, Priority.ALWAYS);
                        chartHolder.getChildren().setAll(chart);

                        // Analytics always shows both units
                        analyticsContainer.getChildren().setAll(
                                createMetricCard("Start value",        formatKwh(fExportStart)),
                                createMetricCard("End value",          formatKwh(fExportEnd)),
                                createMetricCard("Period total (kWh)", formatKwh(fPeriodTotal)),
                                createMetricCard("Period total (CZK)", formatCzk(fPeriodTotal * CZK_PER_KWH)),
                                createSectionHeader("Rate"),
                                createMetricCard("Price rate", CZK_PER_KWH * 1000 + " CZK / MWh"),
                                createMetricCard("= per kWh",  CZK_PER_KWH + " CZK / kWh")
                        );
                    };

                    axisGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
                        if (newT == null) { if (oldT != null) oldT.setSelected(true); return; }
                        showPrice[0] = (newT == btnPrice);
                        rebuildChart.run();
                    });

                    rebuildChart.run();

                    HBox toolbar = buildAxisToolbar(btnEnergy, btnPrice);
                    VBox wrapper = new VBox(6, toolbar, chartHolder);
                    VBox.setVgrow(chartHolder, Priority.ALWAYS);
                    VBox.setVgrow(wrapper, Priority.ALWAYS);
                    setChart(wrapper,
                            List.of("EpochMs", "DateTime (UTC)", "kWh"),
                            EnergyChartWindow.hourlyPointsToRows(points));
                }

                @Override protected void failed() {
                    getException().printStackTrace();
                    chartContainer.getChildren().setAll(createChartPlaceholder("Failed to load chart"));
                }
            };
            runTask(task, "total-production-export");
        }
    }

    // =========================================================================
    // INSTANT IMPORT POWER – PHASE VIEW
    // =========================================================================

    private void loadInstantImportPowerPhaseView() {
        chartTitleLabel.setText("Instant Import Power by Phase");
        chartSubtitleLabel.setText(buildSubtitle());
        descriptionLabel.setText(isCompareMode()
                ? "Overlay line chart comparing instantaneous imported power for two "
                + compareGranularity.name().toLowerCase() + " on a shared time-of-day axis. "
                + "Toggle between Total and Phases."
                : "Multi-line chart of instantaneous imported active power for total load and individual phases."
        );

        if (!validateDates()) return;

        String serial    = serial();
        LocalDate[] r1   = range1();
        LocalDate start1 = r1[0], end1 = r1[1];

        chartContainer.getChildren().setAll(createChartPlaceholder("Loading chart…"));

        if (isCompareMode()) {
            LocalDate[] r2   = range2();
            LocalDate start2 = r2[0], end2 = r2[1];

            Task<Void> task = new Task<>() {
                CompareChartWindow.InstantDayData data1, data2;
                double totalAvg1, totalAvg2, l1Avg1, l2Avg1, l3Avg1, l1Avg2, l2Avg2, l3Avg2;
                PushDataQueries.TimedLongPoint minPt1, maxPt1, minPt2, maxPt2;

                @Override protected Void call() throws Exception {
                    data1 = new CompareChartWindow.InstantDayData(
                            convertInstant(query.getInstantImportPowerTotalBetween(serial, start1, end1)),
                            convertInstant(query.getInstantImportPowerByPhaseBetween(serial, start1, end1, 1)),
                            convertInstant(query.getInstantImportPowerByPhaseBetween(serial, start1, end1, 2)),
                            convertInstant(query.getInstantImportPowerByPhaseBetween(serial, start1, end1, 3)),
                            null
                    );
                    data2 = new CompareChartWindow.InstantDayData(
                            convertInstant(query.getInstantImportPowerTotalBetween(serial, start2, end2)),
                            convertInstant(query.getInstantImportPowerByPhaseBetween(serial, start2, end2, 1)),
                            convertInstant(query.getInstantImportPowerByPhaseBetween(serial, start2, end2, 2)),
                            convertInstant(query.getInstantImportPowerByPhaseBetween(serial, start2, end2, 3)),
                            null
                    );
                    totalAvg1 = query.getAvgImportPowerInstant(serial, start1, end1);
                    l1Avg1    = query.getAvgImportPowerInstantPhase(serial, start1, end1, 1);
                    l2Avg1    = query.getAvgImportPowerInstantPhase(serial, start1, end1, 2);
                    l3Avg1    = query.getAvgImportPowerInstantPhase(serial, start1, end1, 3);
                    totalAvg2 = query.getAvgImportPowerInstant(serial, start2, end2);
                    l1Avg2    = query.getAvgImportPowerInstantPhase(serial, start2, end2, 1);
                    l2Avg2    = query.getAvgImportPowerInstantPhase(serial, start2, end2, 2);
                    l3Avg2    = query.getAvgImportPowerInstantPhase(serial, start2, end2, 3);
                    minPt1 = query.getMinImportPowerInstant(serial, start1, end1);
                    maxPt1 = query.getMaxImportPowerInstant(serial, start1, end1);
                    minPt2 = query.getMinImportPowerInstant(serial, start2, end2);
                    maxPt2 = query.getMaxImportPowerInstant(serial, start2, end2);
                    return null;
                }

                @Override protected void succeeded() {
                    String d1 = periodLabel(start1, end1);
                    String d2 = periodLabel(start2, end2);
                    double safe1 = totalAvg1 <= 0 ? 1.0 : totalAvg1;
                    double safe2 = totalAvg2 <= 0 ? 1.0 : totalAvg2;

                    java.util.List<Node> baseRows = java.util.List.of(
                            createCompareSectionHeader(d1, d2),
                            createCompareRow("Average",
                                    formatWatts(totalAvg1), formatWatts(totalAvg2),
                                    deltaWatts(totalAvg1, totalAvg2)),
                            createCompareRow("Minimum",
                                    minPt1 == null ? "--" : formatWatts(minPt1.value()),
                                    minPt2 == null ? "--" : formatWatts(minPt2.value()), ""),
                            createCompareRow("Min Time",
                                    minPt1 == null ? "--" : formatDateTime(minPt1.tsMsUtc()),
                                    minPt2 == null ? "--" : formatDateTime(minPt2.tsMsUtc()), ""),
                            createCompareRow("Maximum",
                                    maxPt1 == null ? "--" : formatWatts(maxPt1.value()),
                                    maxPt2 == null ? "--" : formatWatts(maxPt2.value()), ""),
                            createCompareRow("Max Time",
                                    maxPt1 == null ? "--" : formatDateTime(maxPt1.tsMsUtc()),
                                    maxPt2 == null ? "--" : formatDateTime(maxPt2.tsMsUtc()), "")
                    );
                    java.util.List<Node> phaseRows = java.util.List.of(
                            createSectionHeader("Phase Avg"),
                            createCompareContributionRow("L1",
                                    formatWatts(l1Avg1), formatPercent(l1Avg1 / safe1),
                                    formatWatts(l1Avg2), formatPercent(l1Avg2 / safe2)),
                            createCompareContributionRow("L2",
                                    formatWatts(l2Avg1), formatPercent(l2Avg1 / safe1),
                                    formatWatts(l2Avg2), formatPercent(l2Avg2 / safe2)),
                            createCompareContributionRow("L3",
                                    formatWatts(l3Avg1), formatPercent(l3Avg1 / safe1),
                                    formatWatts(l3Avg2), formatPercent(l3Avg2 / safe2))
                    );

                    VBox compareNode = CompareChartWindow.buildInstantCompareNode(
                            data1, data2, d1, d2,
                            "Instant Import Power — Phase View", "Active Power (W)",
                            CompareChartWindow.ToggleMode.PHASES_ONLY,
                            CompareChartWindow.InstantCompareMode.TOTAL,
                            toPeriodType(), start1, start2,
                            mode -> {
                                var rows = new java.util.ArrayList<>(baseRows);
                                if (mode == CompareChartWindow.InstantCompareMode.PHASES)
                                    rows.addAll(phaseRows);
                                analyticsContainer.getChildren().setAll(rows);
                            }
                    );
                    VBox.setVgrow(compareNode, Priority.ALWAYS);
                    List<String[]> csvRowsIIPC = EnergyChartWindow.instantMultiSeriesToRows(
                            Map.entry(d1 + " Total", data1.total()),
                            Map.entry(d1 + " L1", data1.l1OrT1()),
                            Map.entry(d1 + " L2", data1.l2OrT2()),
                            Map.entry(d1 + " L3", data1.l3OrT3()),
                            Map.entry(d2 + " Total", data2.total()),
                            Map.entry(d2 + " L1", data2.l1OrT1()),
                            Map.entry(d2 + " L2", data2.l2OrT2()),
                            Map.entry(d2 + " L3", data2.l3OrT3()));
                    setChart(compareNode, List.of("EpochMs", "DateTime (UTC)", "Series", "Watts"), csvRowsIIPC);
                }

                @Override protected void failed() {
                    getException().printStackTrace();
                    chartContainer.getChildren().setAll(createChartPlaceholder("Failed to load chart"));
                }
            };
            runTask(task, "compare-instant-import-phase");

        } else {
            LocalDate end = end1;

            Task<Void> task = new Task<>() {
                List<EnergyChartWindow.InstantPowerPoint> totalPts, l1Pts, l2Pts, l3Pts;
                double totalAvg, l1Avg, l2Avg, l3Avg;
                PushDataQueries.TimedLongPoint minPoint, maxPoint;
                long startMs, endMs;

                @Override protected Void call() throws Exception {
                    totalPts = convertInstant(query.getInstantImportPowerTotalBetween(serial, start1, end));
                    l1Pts    = convertInstant(query.getInstantImportPowerByPhaseBetween(serial, start1, end, 1));
                    l2Pts    = convertInstant(query.getInstantImportPowerByPhaseBetween(serial, start1, end, 2));
                    l3Pts    = convertInstant(query.getInstantImportPowerByPhaseBetween(serial, start1, end, 3));
                    startMs  = EnergyChartWindow.midnightMs(start1);
                    endMs    = EnergyChartWindow.midnightMs(end.plusDays(1));
                    totalAvg = query.getAvgImportPowerInstant(serial, start1, end);
                    l1Avg    = query.getAvgImportPowerInstantPhase(serial, start1, end, 1);
                    l2Avg    = query.getAvgImportPowerInstantPhase(serial, start1, end, 2);
                    l3Avg    = query.getAvgImportPowerInstantPhase(serial, start1, end, 3);
                    minPoint = query.getMinImportPowerInstant(serial, start1, end);
                    maxPoint = query.getMaxImportPowerInstant(serial, start1, end);
                    return null;
                }

                @Override protected void succeeded() {
                    Node chartNode = EnergyChartWindow.buildInstantImportPowerMultiLineChart(
                            totalPts, l1Pts, l2Pts, l3Pts,
                            "Instant Import Power (Total + L1–L3)", startMs, endMs);
                    double safeTotal = totalAvg <= 0 ? 1.0 : totalAvg;
                    List<String[]> csvRowsIIP = EnergyChartWindow.instantMultiSeriesToRows(
                            Map.entry("Total", totalPts), Map.entry("L1", l1Pts),
                            Map.entry("L2", l2Pts), Map.entry("L3", l3Pts));
                    setChart(chartNode, List.of("EpochMs", "DateTime (UTC)", "Series", "Watts"), csvRowsIIP);
                    analyticsContainer.getChildren().setAll(
                            createMetricCard("Minimum", minPoint == null ? "--" : formatWatts(minPoint.value())),
                            createMetricCard("Min Time", minPoint == null ? "--" : formatDateTime(minPoint.tsMsUtc())),
                            createMetricCard("Maximum", maxPoint == null ? "--" : formatWatts(maxPoint.value())),
                            createMetricCard("Max Time", maxPoint == null ? "--" : formatDateTime(maxPoint.tsMsUtc())),
                            createMetricCard("Average", formatWatts(totalAvg)),
                            createSectionHeader("Phase Contribution (Avg)"),
                            createContributionRow("Total", formatWatts(totalAvg), "100.0 %"),
                            createContributionRow("L1", formatWatts(l1Avg), formatPercent(l1Avg / safeTotal)),
                            createContributionRow("L2", formatWatts(l2Avg), formatPercent(l2Avg / safeTotal)),
                            createContributionRow("L3", formatWatts(l3Avg), formatPercent(l3Avg / safeTotal))
                    );
                }

                @Override protected void failed() {
                    getException().printStackTrace();
                    chartContainer.getChildren().setAll(createChartPlaceholder("Failed to load chart"));
                }
            };
            runTask(task, "instant-import-phase-dashboard");
        }
    }

    // =========================================================================
    // INSTANT IMPORT POWER – TARIFF VIEW
    // =========================================================================

    private void loadInstantImportPowerTariffView() {
        chartTitleLabel.setText("Instant Import Power by Tariff");
        chartSubtitleLabel.setText(buildSubtitle());
        descriptionLabel.setText(isCompareMode()
                ? "Overlay line chart comparing instantaneous imported power by tariff for two "
                + compareGranularity.name().toLowerCase() + "."
                + "Use the toggle to switch between Total and Tariff views."
                : "Multi-line chart of instantaneous imported active power grouped by tariff."
        );

        if (!validateDates()) return;

        String serial    = serial();
        LocalDate[] r1   = range1();
        LocalDate start1 = r1[0], end1 = r1[1];

        chartContainer.getChildren().setAll(createChartPlaceholder("Loading chart…"));

        if (isCompareMode()) {
            LocalDate[] r2   = range2();
            LocalDate start2 = r2[0], end2 = r2[1];

            Task<Void> task = new Task<>() {
                CompareChartWindow.InstantDayData data1, data2;
                double totalAvg1, totalAvg2;
                double t1Avg1, t2Avg1, t3Avg1, t4Avg1;
                double t1Avg2, t2Avg2, t3Avg2, t4Avg2;
                PushDataQueries.TimedLongPoint minPt1, maxPt1, minPt2, maxPt2;

                @Override protected Void call() throws Exception {
                    var all1 = convertInstant(query.getInstantImportPowerTotalBetween(serial, start1, end1));
                    var t1d1 = convertInstant(query.getInstantImportPowerTotalBetween(serial, start1, end1, "T1"));
                    var t2d1 = convertInstant(query.getInstantImportPowerTotalBetween(serial, start1, end1, "T2"));
                    var t3d1 = convertInstant(query.getInstantImportPowerTotalBetween(serial, start1, end1, "T3"));
                    var t4d1 = convertInstant(query.getInstantImportPowerTotalBetween(serial, start1, end1, "T4"));
                    var all2 = convertInstant(query.getInstantImportPowerTotalBetween(serial, start2, end2));
                    var t1d2 = convertInstant(query.getInstantImportPowerTotalBetween(serial, start2, end2, "T1"));
                    var t2d2 = convertInstant(query.getInstantImportPowerTotalBetween(serial, start2, end2, "T2"));
                    var t3d2 = convertInstant(query.getInstantImportPowerTotalBetween(serial, start2, end2, "T3"));
                    var t4d2 = convertInstant(query.getInstantImportPowerTotalBetween(serial, start2, end2, "T4"));
                    data1 = new CompareChartWindow.InstantDayData(all1, t1d1, t2d1, t3d1, t4d1);
                    data2 = new CompareChartWindow.InstantDayData(all2, t1d2, t2d2, t3d2, t4d2);
                    totalAvg1 = query.getAvgImportPowerInstant(serial, start1, end1);
                    totalAvg2 = query.getAvgImportPowerInstant(serial, start2, end2);
                    t1Avg1 = avg(t1d1); t2Avg1 = avg(t2d1);
                    t3Avg1 = avg(t3d1); t4Avg1 = avg(t4d1);
                    t1Avg2 = avg(t1d2); t2Avg2 = avg(t2d2);
                    t3Avg2 = avg(t3d2); t4Avg2 = avg(t4d2);
                    minPt1 = query.getMinImportPowerInstant(serial, start1, end1);
                    maxPt1 = query.getMaxImportPowerInstant(serial, start1, end1);
                    minPt2 = query.getMinImportPowerInstant(serial, start2, end2);
                    maxPt2 = query.getMaxImportPowerInstant(serial, start2, end2);
                    return null;
                }

                @Override protected void succeeded() {
                    String d1 = periodLabel(start1, end1);
                    String d2 = periodLabel(start2, end2);
                    double safe1 = (t1Avg1 + t2Avg1 + t3Avg1 + t4Avg1);
                    double safe2 = (t1Avg2 + t2Avg2 + t3Avg2 + t4Avg2);
                    if (safe1 <= 0) safe1 = 1.0;
                    if (safe2 <= 0) safe2 = 1.0;
                    final double fs1 = safe1, fs2 = safe2;

                    java.util.List<Node> baseRows = java.util.List.of(
                            createCompareSectionHeader(d1, d2),
                            createCompareRow("Average",
                                    formatWatts(totalAvg1), formatWatts(totalAvg2),
                                    deltaWatts(totalAvg1, totalAvg2)),
                            createCompareRow("Minimum",
                                    minPt1 == null ? "--" : formatWatts(minPt1.value()),
                                    minPt2 == null ? "--" : formatWatts(minPt2.value()), ""),
                            createCompareRow("Min Time",
                                    minPt1 == null ? "--" : formatDateTime(minPt1.tsMsUtc()),
                                    minPt2 == null ? "--" : formatDateTime(minPt2.tsMsUtc()), ""),
                            createCompareRow("Maximum",
                                    maxPt1 == null ? "--" : formatWatts(maxPt1.value()),
                                    maxPt2 == null ? "--" : formatWatts(maxPt2.value()), ""),
                            createCompareRow("Max Time",
                                    maxPt1 == null ? "--" : formatDateTime(maxPt1.tsMsUtc()),
                                    maxPt2 == null ? "--" : formatDateTime(maxPt2.tsMsUtc()), "")
                    );
                    java.util.List<Node> tariffRows = java.util.List.of(
                            createSectionHeader("Tariff Avg"),
                            createCompareContributionRow("T1",
                                    formatWatts(t1Avg1), formatPercent(t1Avg1 / fs1),
                                    formatWatts(t1Avg2), formatPercent(t1Avg2 / fs2)),
                            createCompareContributionRow("T2",
                                    formatWatts(t2Avg1), formatPercent(t2Avg1 / fs1),
                                    formatWatts(t2Avg2), formatPercent(t2Avg2 / fs2)),
                            createCompareContributionRow("T3",
                                    formatWatts(t3Avg1), formatPercent(t3Avg1 / fs1),
                                    formatWatts(t3Avg2), formatPercent(t3Avg2 / fs2)),
                            createCompareContributionRow("T4",
                                    formatWatts(t4Avg1), formatPercent(t4Avg1 / fs1),
                                    formatWatts(t4Avg2), formatPercent(t4Avg2 / fs2))
                    );

                    VBox compareNode = CompareChartWindow.buildInstantCompareNode(
                            data1, data2, d1, d2,
                            "Instant Import Power — Tariff View", "Active Power (W)",
                            CompareChartWindow.ToggleMode.TARIFFS_ONLY,
                            CompareChartWindow.InstantCompareMode.TOTAL,
                            toPeriodType(), start1, start2,
                            mode -> {
                                var rows = new java.util.ArrayList<>(baseRows);
                                if (mode == CompareChartWindow.InstantCompareMode.TARIFFS)
                                    rows.addAll(tariffRows);
                                analyticsContainer.getChildren().setAll(rows);
                            }
                    );
                    VBox.setVgrow(compareNode, Priority.ALWAYS);
                    List<String[]> csvRowsIITC = EnergyChartWindow.instantMultiSeriesToRows(
                            Map.entry(d1 + " Total", data1.total()),
                            Map.entry(d1 + " T1", data1.l1OrT1()),
                            Map.entry(d1 + " T2", data1.l2OrT2()),
                            Map.entry(d1 + " T3", data1.l3OrT3()),
                            Map.entry(d1 + " T4", data1.t4()),
                            Map.entry(d2 + " Total", data2.total()),
                            Map.entry(d2 + " T1", data2.l1OrT1()),
                            Map.entry(d2 + " T2", data2.l2OrT2()),
                            Map.entry(d2 + " T3", data2.l3OrT3()),
                            Map.entry(d2 + " T4", data2.t4()));
                    setChart(compareNode, List.of("EpochMs", "DateTime (UTC)", "Series", "Watts"), csvRowsIITC);
                }

                @Override protected void failed() {
                    getException().printStackTrace();
                    chartContainer.getChildren().setAll(createChartPlaceholder("Failed to load chart"));
                }
            };
            runTask(task, "compare-instant-import-tariff");

        } else {
            LocalDate end = end1;

            Task<Void> task = new Task<>() {
                List<EnergyChartWindow.InstantPowerPoint> allPts, t1Pts, t2Pts, t3Pts, t4Pts;
                double totalAvg, t1Avg, t2Avg, t3Avg, t4Avg;
                PushDataQueries.TimedLongPoint minPoint, maxPoint;
                long startMs, endMs;

                @Override protected Void call() throws Exception {
                    allPts = convertInstant(query.getInstantImportPowerTotalBetween(serial, start1, end));
                    t1Pts  = convertInstant(query.getInstantImportPowerTotalBetween(serial, start1, end, "T1"));
                    t2Pts  = convertInstant(query.getInstantImportPowerTotalBetween(serial, start1, end, "T2"));
                    t3Pts  = convertInstant(query.getInstantImportPowerTotalBetween(serial, start1, end, "T3"));
                    t4Pts  = convertInstant(query.getInstantImportPowerTotalBetween(serial, start1, end, "T4"));
                    startMs  = EnergyChartWindow.midnightMs(start1);
                    endMs    = EnergyChartWindow.midnightMs(end.plusDays(1));
                    totalAvg = query.getAvgImportPowerInstant(serial, start1, end);
                    t1Avg = avg(t1Pts); t2Avg = avg(t2Pts);
                    t3Avg = avg(t3Pts); t4Avg = avg(t4Pts);
                    minPoint = query.getMinImportPowerInstant(serial, start1, end);
                    maxPoint = query.getMaxImportPowerInstant(serial, start1, end);
                    return null;
                }

                @Override protected void succeeded() {
                    Node chartNode = EnergyChartWindow.buildInstantImportPowerTariffMultiLineChart(
                            allPts, t1Pts, t2Pts, t3Pts, t4Pts,
                            "Instant Import Power by Tariff", startMs, endMs);
                    double tariffSum = t1Avg + t2Avg + t3Avg + t4Avg;
                    double safeTotal = tariffSum <= 0 ? 1.0 : tariffSum;
                    List<String[]> csvRowsIIT = EnergyChartWindow.instantMultiSeriesToRows(
                            Map.entry("Total", allPts), Map.entry("T1", t1Pts),
                            Map.entry("T2", t2Pts), Map.entry("T3", t3Pts), Map.entry("T4", t4Pts));
                    setChart(chartNode, List.of("EpochMs", "DateTime (UTC)", "Series", "Watts"), csvRowsIIT);
                    analyticsContainer.getChildren().setAll(
                            createMetricCard("Minimum", minPoint == null ? "--" : formatWatts(minPoint.value())),
                            createMetricCard("Min Time", minPoint == null ? "--" : formatDateTime(minPoint.tsMsUtc())),
                            createMetricCard("Maximum", maxPoint == null ? "--" : formatWatts(maxPoint.value())),
                            createMetricCard("Max Time", maxPoint == null ? "--" : formatDateTime(maxPoint.tsMsUtc())),
                            createMetricCard("Average", formatWatts(totalAvg)),
                            createSectionHeader("Tariff Contribution (Avg)"),
                            createContributionRow("T1", formatWatts(t1Avg), formatPercent(t1Avg / safeTotal)),
                            createContributionRow("T2", formatWatts(t2Avg), formatPercent(t2Avg / safeTotal)),
                            createContributionRow("T3", formatWatts(t3Avg), formatPercent(t3Avg / safeTotal)),
                            createContributionRow("T4", formatWatts(t4Avg), formatPercent(t4Avg / safeTotal))
                    );
                }

                @Override protected void failed() {
                    getException().printStackTrace();
                    chartContainer.getChildren().setAll(createChartPlaceholder("Failed to load chart"));
                }
            };
            runTask(task, "instant-import-tariff-dashboard");
        }
    }

    // =========================================================================
    // INSTANT EXPORT POWER
    // =========================================================================

    private void loadInstantExportPowerView() {
        chartTitleLabel.setText("Instant Export Power");
        chartSubtitleLabel.setText(buildSubtitle());
        descriptionLabel.setText(isCompareMode()
                ? "Overlay line chart comparing instantaneous exported power for two "
                + compareGranularity.name().toLowerCase() + " on a shared time-of-day axis. "
                + "Toggle between Total and Phases."
                : "Multi-line chart of instantaneous exported active power for total load and individual phases."
        );

        if (!validateDates()) return;

        String serial    = serial();
        LocalDate[] r1   = range1();
        LocalDate start1 = r1[0], end1 = r1[1];

        chartContainer.getChildren().setAll(createChartPlaceholder("Loading chart…"));

        if (isCompareMode()) {
            LocalDate[] r2   = range2();
            LocalDate start2 = r2[0], end2 = r2[1];

            Task<Void> task = new Task<>() {
                CompareChartWindow.InstantDayData data1, data2;
                double totalAvg1, totalAvg2, l1Avg1, l2Avg1, l3Avg1, l1Avg2, l2Avg2, l3Avg2;
                PushDataQueries.TimedLongPoint minPt1, maxPt1, minPt2, maxPt2;

                @Override protected Void call() throws Exception {
                    data1 = new CompareChartWindow.InstantDayData(
                            convertInstant(query.getInstantExportPowerTotalBetween(serial, start1, end1)),
                            convertInstant(query.getInstantExportPowerByPhaseBetween(serial, start1, end1, 1)),
                            convertInstant(query.getInstantExportPowerByPhaseBetween(serial, start1, end1, 2)),
                            convertInstant(query.getInstantExportPowerByPhaseBetween(serial, start1, end1, 3)),
                            null
                    );
                    data2 = new CompareChartWindow.InstantDayData(
                            convertInstant(query.getInstantExportPowerTotalBetween(serial, start2, end2)),
                            convertInstant(query.getInstantExportPowerByPhaseBetween(serial, start2, end2, 1)),
                            convertInstant(query.getInstantExportPowerByPhaseBetween(serial, start2, end2, 2)),
                            convertInstant(query.getInstantExportPowerByPhaseBetween(serial, start2, end2, 3)),
                            null
                    );
                    totalAvg1 = query.getAvgExportPowerInstant(serial, start1, end1);
                    l1Avg1    = query.getAvgExportPowerInstantPhase(serial, start1, end1, 1);
                    l2Avg1    = query.getAvgExportPowerInstantPhase(serial, start1, end1, 2);
                    l3Avg1    = query.getAvgExportPowerInstantPhase(serial, start1, end1, 3);
                    totalAvg2 = query.getAvgExportPowerInstant(serial, start2, end2);
                    l1Avg2    = query.getAvgExportPowerInstantPhase(serial, start2, end2, 1);
                    l2Avg2    = query.getAvgExportPowerInstantPhase(serial, start2, end2, 2);
                    l3Avg2    = query.getAvgExportPowerInstantPhase(serial, start2, end2, 3);
                    minPt1 = query.getMinExportPowerInstant(serial, start1, end1);
                    maxPt1 = query.getMaxExportPowerInstant(serial, start1, end1);
                    minPt2 = query.getMinExportPowerInstant(serial, start2, end2);
                    maxPt2 = query.getMaxExportPowerInstant(serial, start2, end2);
                    return null;
                }

                @Override protected void succeeded() {
                    String d1 = periodLabel(start1, end1);
                    String d2 = periodLabel(start2, end2);
                    double safe1 = totalAvg1 <= 0 ? 1.0 : totalAvg1;
                    double safe2 = totalAvg2 <= 0 ? 1.0 : totalAvg2;

                    java.util.List<Node> baseRows = java.util.List.of(
                            createCompareSectionHeader(d1, d2),
                            createCompareRow("Average",
                                    formatWatts(totalAvg1), formatWatts(totalAvg2),
                                    deltaWatts(totalAvg1, totalAvg2)),
                            createCompareRow("Minimum",
                                    minPt1 == null ? "--" : formatWatts(minPt1.value()),
                                    minPt2 == null ? "--" : formatWatts(minPt2.value()), ""),
                            createCompareRow("Min Time",
                                    minPt1 == null ? "--" : formatDateTime(minPt1.tsMsUtc()),
                                    minPt2 == null ? "--" : formatDateTime(minPt2.tsMsUtc()), ""),
                            createCompareRow("Maximum",
                                    maxPt1 == null ? "--" : formatWatts(maxPt1.value()),
                                    maxPt2 == null ? "--" : formatWatts(maxPt2.value()), ""),
                            createCompareRow("Max Time",
                                    maxPt1 == null ? "--" : formatDateTime(maxPt1.tsMsUtc()),
                                    maxPt2 == null ? "--" : formatDateTime(maxPt2.tsMsUtc()), "")
                    );
                    java.util.List<Node> phaseRows = java.util.List.of(
                            createSectionHeader("Phase Avg"),
                            createCompareContributionRow("L1",
                                    formatWatts(l1Avg1), formatPercent(l1Avg1 / safe1),
                                    formatWatts(l1Avg2), formatPercent(l1Avg2 / safe2)),
                            createCompareContributionRow("L2",
                                    formatWatts(l2Avg1), formatPercent(l2Avg1 / safe1),
                                    formatWatts(l2Avg2), formatPercent(l2Avg2 / safe2)),
                            createCompareContributionRow("L3",
                                    formatWatts(l3Avg1), formatPercent(l3Avg1 / safe1),
                                    formatWatts(l3Avg2), formatPercent(l3Avg2 / safe2))
                    );

                    VBox compareNode = CompareChartWindow.buildInstantCompareNode(
                            data1, data2, d1, d2,
                            "Instant Export Power — Phase View", "Active Power (W)",
                            CompareChartWindow.ToggleMode.PHASES_ONLY,
                            CompareChartWindow.InstantCompareMode.TOTAL,
                            toPeriodType(), start1, start2,
                            mode -> {
                                var rows = new java.util.ArrayList<>(baseRows);
                                if (mode == CompareChartWindow.InstantCompareMode.PHASES)
                                    rows.addAll(phaseRows);
                                analyticsContainer.getChildren().setAll(rows);
                            }
                    );
                    VBox.setVgrow(compareNode, Priority.ALWAYS);
                    List<String[]> csvRowsIEC = EnergyChartWindow.instantMultiSeriesToRows(
                            Map.entry(d1 + " Total", data1.total()),
                            Map.entry(d1 + " L1", data1.l1OrT1()),
                            Map.entry(d1 + " L2", data1.l2OrT2()),
                            Map.entry(d1 + " L3", data1.l3OrT3()),
                            Map.entry(d2 + " Total", data2.total()),
                            Map.entry(d2 + " L1", data2.l1OrT1()),
                            Map.entry(d2 + " L2", data2.l2OrT2()),
                            Map.entry(d2 + " L3", data2.l3OrT3()));
                    setChart(compareNode, List.of("EpochMs", "DateTime (UTC)", "Series", "Watts"), csvRowsIEC);
                }

                @Override protected void failed() {
                    getException().printStackTrace();
                    chartContainer.getChildren().setAll(createChartPlaceholder("Failed to load chart"));
                }
            };
            runTask(task, "compare-instant-export");

        } else {
            LocalDate end = end1;

            Task<Void> task = new Task<>() {
                List<EnergyChartWindow.InstantPowerPoint> totalPts, l1Pts, l2Pts, l3Pts;
                double totalAvg, l1Avg, l2Avg, l3Avg;
                PushDataQueries.TimedLongPoint minPoint, maxPoint;
                long startMs, endMs;

                @Override protected Void call() throws Exception {
                    totalPts = convertInstant(query.getInstantExportPowerTotalBetween(serial, start1, end));
                    l1Pts    = convertInstant(query.getInstantExportPowerByPhaseBetween(serial, start1, end, 1));
                    l2Pts    = convertInstant(query.getInstantExportPowerByPhaseBetween(serial, start1, end, 2));
                    l3Pts    = convertInstant(query.getInstantExportPowerByPhaseBetween(serial, start1, end, 3));
                    startMs  = EnergyChartWindow.midnightMs(start1);
                    endMs    = EnergyChartWindow.midnightMs(end.plusDays(1));
                    totalAvg = query.getAvgExportPowerInstant(serial, start1, end);
                    l1Avg    = query.getAvgExportPowerInstantPhase(serial, start1, end, 1);
                    l2Avg    = query.getAvgExportPowerInstantPhase(serial, start1, end, 2);
                    l3Avg    = query.getAvgExportPowerInstantPhase(serial, start1, end, 3);
                    minPoint = query.getMinExportPowerInstant(serial, start1, end);
                    maxPoint = query.getMaxExportPowerInstant(serial, start1, end);
                    return null;
                }

                @Override protected void succeeded() {
                    Node chartNode = EnergyChartWindow.buildInstantExportPowerMultiLineChart(
                            totalPts, l1Pts, l2Pts, l3Pts,
                            "Instant Export Power (Total + L1–L3)", startMs, endMs);
                    double safeTotal = totalAvg <= 0 ? 1.0 : totalAvg;
                    List<String[]> csvRowsIE = EnergyChartWindow.instantMultiSeriesToRows(
                            Map.entry("Total", totalPts), Map.entry("L1", l1Pts),
                            Map.entry("L2", l2Pts), Map.entry("L3", l3Pts));
                    setChart(chartNode, List.of("EpochMs", "DateTime (UTC)", "Series", "Watts"), csvRowsIE);
                    analyticsContainer.getChildren().setAll(
                            createMetricCard("Minimum", minPoint == null ? "--" : formatWatts(minPoint.value())),
                            createMetricCard("Min Time", minPoint == null ? "--" : formatDateTime(minPoint.tsMsUtc())),
                            createMetricCard("Maximum", maxPoint == null ? "--" : formatWatts(maxPoint.value())),
                            createMetricCard("Max Time", maxPoint == null ? "--" : formatDateTime(maxPoint.tsMsUtc())),
                            createMetricCard("Average", formatWatts(totalAvg)),
                            createSectionHeader("Phase Contribution (Avg)"),
                            createContributionRow("Total", formatWatts(totalAvg), "100.0 %"),
                            createContributionRow("L1", formatWatts(l1Avg), formatPercent(l1Avg / safeTotal)),
                            createContributionRow("L2", formatWatts(l2Avg), formatPercent(l2Avg / safeTotal)),
                            createContributionRow("L3", formatWatts(l3Avg), formatPercent(l3Avg / safeTotal))
                    );
                }

                @Override protected void failed() {
                    getException().printStackTrace();
                    chartContainer.getChildren().setAll(createChartPlaceholder("Failed to load chart"));
                }
            };
            runTask(task, "instant-export-dashboard");
        }
    }

    // =========================================================================
    // TOTAL CONSUMPTION (IMPORT)
    // =========================================================================

    /**
     * Shows the cumulative import register (MAX reading per hour) so the line
     * represents the true running total kWh consumed.
     * An in-chart Y-Axis toggle switches between kWh and CZK.
     */
    private void loadTotalConsumptionImportView() {
        chartTitleLabel.setText("Total Consumption (Import)");
        chartSubtitleLabel.setText(buildSubtitle());
        descriptionLabel.setText(isCompareMode()
                ? "Overlay line chart comparing the cumulative import register for two "
                + compareGranularity.name().toLowerCase() + ". "
                + "Use the Y-Axis toggle to switch between energy (kWh) and estimated "
                + "cost (CZK)."
                : "Cumulative import register (total kWh consumed) for the selected range. "
                + "Use the Y-Axis toggle to switch between energy (kWh) and estimated "
                + "cost (CZK)."
        );

        if (!validateDates()) return;

        String serial    = serial();
        LocalDate[] r1   = range1();
        LocalDate start1 = r1[0], end1 = r1[1];

        chartContainer.getChildren().setAll(createChartPlaceholder("Loading chart…"));

        if (isCompareMode()) {
            // ── COMPARE MODE ──────────────────────────────────────────────────
            // Use buildRegisterCompareChart which plots raw cumulative register
            // values on a seconds-since-period-start x-axis.
            LocalDate[] r2   = range2();
            LocalDate start2 = r2[0], end2 = r2[1];

            Task<Void> task = new Task<>() {
                List<EnergyChartWindow.HourlyEnergyPoint> raw1, raw2;
                double importStart1, importEnd1, importStart2, importEnd2;
                double periodTotal1, periodTotal2;

                @Override protected Void call() throws Exception {
                    raw1 = convertHourly(query.getHourlyImportRegisterBetween(serial, start1, end1));
                    raw2 = convertHourly(query.getHourlyImportRegisterBetween(serial, start2, end2));
                    importStart1 = raw1.isEmpty() ? 0 : raw1.get(0).kwh();
                    importEnd1   = raw1.isEmpty() ? 0 : raw1.get(raw1.size() - 1).kwh();
                    importStart2 = raw2.isEmpty() ? 0 : raw2.get(0).kwh();
                    importEnd2   = raw2.isEmpty() ? 0 : raw2.get(raw2.size() - 1).kwh();
                    periodTotal1 = importEnd1 - importStart1;
                    periodTotal2 = importEnd2 - importStart2;
                    return null;
                }

                @Override protected void succeeded() {
                    String lbl1 = periodLabel(start1, end1);
                    String lbl2 = periodLabel(start2, end2);

                    final boolean[] showPrice = { false };

                    ToggleGroup axisGroup  = new ToggleGroup();
                    ToggleButton btnEnergy = axisToggle("Energy (kWh)", axisGroup);
                    ToggleButton btnPrice  = axisToggle("Price (CZK)",  axisGroup);
                    btnEnergy.setSelected(true);

                    VBox chartHolder = new VBox();
                    VBox.setVgrow(chartHolder, Priority.ALWAYS);
                    chartHolder.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

                    final double fImportStart1 = importStart1, fImportEnd1 = importEnd1;
                    final double fImportStart2 = importStart2, fImportEnd2 = importEnd2;

                    Runnable rebuildCompare = () -> {
                        boolean pm = showPrice[0];

                        // Scale register points to CZK when in price mode
                        List<EnergyChartWindow.HourlyEnergyPoint> dp1 = pm ? scaleToCzk(raw1) : raw1;
                        List<EnergyChartWindow.HourlyEnergyPoint> dp2 = pm ? scaleToCzk(raw2) : raw2;

                        // Pass empty lists for export (import-only view).
                        javafx.scene.chart.LineChart<Number, Number> chart =
                                CompareChartWindow.buildRegisterCompareChart(
                                        dp1, dp2,
                                        java.util.List.of(), java.util.List.of(),
                                        lbl1, lbl2,
                                        toPeriodType(), start1, start2);
                        // Override the  y-axis label with the correct unit for the current toggle mode.
                        chart.getYAxis().setLabel(pm ? "Cost (CZK)" : "Total Import (kWh)");
                        chart.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
                        VBox.setVgrow(chart, Priority.ALWAYS);
                        chartHolder.getChildren().setAll(chart);

                        analyticsContainer.getChildren().setAll(
                                createCompareSectionHeader(lbl1, lbl2),
                                createCompareRow("Start Value",
                                        formatKwh(fImportStart1), formatKwh(fImportStart2), ""),
                                createCompareRow("End Value",
                                        formatKwh(fImportEnd1), formatKwh(fImportEnd2), ""),
                                createCompareRow("Period total (kWh)",
                                        formatKwh(periodTotal1), formatKwh(periodTotal2),
                                        deltaKwh(periodTotal1, periodTotal2)),
                                createCompareRow("Period total (CZK)",
                                        formatCzk(periodTotal1 * CZK_PER_KWH),
                                        formatCzk(periodTotal2 * CZK_PER_KWH),
                                        deltaCzk(periodTotal1 * CZK_PER_KWH, periodTotal2 * CZK_PER_KWH)),
                                createSectionHeader("Rate"),
                                createMetricCard("Price rate",  new StringBuilder().append(CZK_PER_KWH * 1000).append(" CZK / MWh").toString()),
                                createMetricCard("= per kWh",   new StringBuilder().append(CZK_PER_KWH).append(" CZK / kWh").toString())
                        );
                    };

                    axisGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
                        if (newT == null) { if (oldT != null) oldT.setSelected(true); return; }
                        showPrice[0] = (newT == btnPrice);
                        rebuildCompare.run();
                    });

                    rebuildCompare.run();

                    HBox toolbar = buildAxisToolbar(btnEnergy, btnPrice);
                    VBox wrapper = new VBox(6, toolbar, chartHolder);
                    VBox.setVgrow(chartHolder, Priority.ALWAYS);
                    VBox.setVgrow(wrapper, Priority.ALWAYS);
                    List<String[]> csvRowsTCIC = EnergyChartWindow.hourlyMultiSeriesToRows(
                            Map.entry(lbl1 + " Import", raw1),
                            Map.entry(lbl2 + " Import", raw2));
                    setChart(wrapper, List.of("EpochMs", "DateTime (UTC)", "Series", "kWh"), csvRowsTCIC);
                }

                @Override protected void failed() {
                    getException().printStackTrace();
                    chartContainer.getChildren().setAll(createChartPlaceholder("Failed to load chart"));
                }
            };
            runTask(task, "compare-total-consumption-import");

        } else {
            // ── NORMAL (RANGE) MODE ───────────────────────────────────────────
            LocalDate end = end1;

            Task<Void> task = new Task<>() {
                List<EnergyChartWindow.HourlyEnergyPoint> points;
                // Period total = last register value − first register value
                double importStart, importEnd, periodTotal;
                long startMs, endMs;

                @Override protected Void call() throws Exception {
                    points      = convertHourly(query.getHourlyImportRegisterBetween(serial, start1, end));
                    startMs     = EnergyChartWindow.midnightMs(start1);
                    endMs       = EnergyChartWindow.midnightMs(end.plusDays(1));
                    importStart = points.isEmpty() ? 0 : points.get(0).kwh();
                    importEnd   = points.isEmpty() ? 0 : points.get(points.size() - 1).kwh();
                    periodTotal = importEnd - importStart;
                    return null;
                }

                @Override protected void succeeded() {

                    final boolean[] showPrice = { false };

                    ToggleGroup axisGroup  = new ToggleGroup();
                    ToggleButton btnEnergy = axisToggle("Energy (kWh)", axisGroup);
                    ToggleButton btnPrice  = axisToggle("Price (CZK)",  axisGroup);
                    btnEnergy.setSelected(true);

                    VBox chartHolder = new VBox();
                    VBox.setVgrow(chartHolder, Priority.ALWAYS);
                    chartHolder.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

                    final double fImportStart = importStart;
                    final double fImportEnd   = importEnd;
                    final double fPeriodTotal = periodTotal;

                    Runnable rebuildChart = () -> {
                        boolean pm = showPrice[0];

                        List<EnergyChartWindow.HourlyEnergyPoint> displayPts =
                                pm ? scaleToCzk(points) : points;

                        String yLabel = pm ? "Cost (CZK)" : "Total Import (kWh)";
                        String title  = pm
                                ? "Total Import Cost (CZK)"
                                : "Total Import Consumption (kWh)";

                        var data = displayPts.stream()
                                .map(p -> new javafx.scene.chart.XYChart.Data<Number, Number>(
                                        p.hourMsUtc(), p.kwh()))
                                .collect(java.util.stream.Collectors.toList());

                        javafx.scene.chart.LineChart<Number, Number> chart =
                                EnergyChartWindow.buildLineChart(
                                        title, yLabel, "Import",
                                        data, startMs, endMs);
                        chart.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
                        VBox.setVgrow(chart, Priority.ALWAYS);
                        chartHolder.getChildren().setAll(chart);

                        // Analytics panel always shows both units
                        analyticsContainer.getChildren().setAll(
                                createMetricCard("Start value",        formatKwh(fImportStart)),
                                createMetricCard("End value",          formatKwh(fImportEnd)),
                                createMetricCard("Period total (kWh)", formatKwh(fPeriodTotal)),
                                createMetricCard("Period total (CZK)", formatCzk(fPeriodTotal * CZK_PER_KWH)),
                                createSectionHeader("Rate"),
                                createMetricCard("Price rate",  new StringBuilder().append(CZK_PER_KWH * 1000).append(" CZK / MWh").toString()),
                                createMetricCard("= per kWh",   new StringBuilder().append(CZK_PER_KWH).append(" CZK / kWh").toString())
                        );
                    };

                    axisGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
                        if (newT == null) { if (oldT != null) oldT.setSelected(true); return; }
                        showPrice[0] = (newT == btnPrice);
                        rebuildChart.run();
                    });

                    rebuildChart.run();

                    HBox toolbar = buildAxisToolbar(btnEnergy, btnPrice);
                    VBox wrapper = new VBox(6, toolbar, chartHolder);
                    VBox.setVgrow(chartHolder, Priority.ALWAYS);
                    VBox.setVgrow(wrapper, Priority.ALWAYS);
                    setChart(wrapper,
                            List.of("EpochMs", "DateTime (UTC)", "kWh"),
                            EnergyChartWindow.hourlyPointsToRows(points));
                }

                @Override protected void failed() {
                    getException().printStackTrace();
                    chartContainer.getChildren().setAll(createChartPlaceholder("Failed to load chart"));
                }
            };
            runTask(task, "total-consumption-import");
        }
    }

    // ── Axis toggle helpers (shared by register-based views) ──────────────────

    /**
     * Styled ToggleButton for the Y-axis selector bar, matching the look of the
     * Total / Phases toggle used in the instant-power views.
     */
    private ToggleButton axisToggle(String text, ToggleGroup group) {
        ToggleButton btn = new ToggleButton(text);
        btn.setToggleGroup(group);
        String base = """
                -fx-background-color: #E2E8F0;
                -fx-text-fill: #334155;
                -fx-background-radius: 6;
                -fx-padding: 4 14;
                -fx-font-size: 12px;
                -fx-cursor: hand;
                """;
        String active = """
                -fx-background-color: #1E40AF;
                -fx-text-fill: white;
                -fx-background-radius: 6;
                -fx-padding: 4 14;
                -fx-font-size: 12px;
                -fx-cursor: hand;
                """;
        btn.setStyle(base);
        btn.selectedProperty().addListener((obs, was, is) -> btn.setStyle(is ? active : base));
        return btn;
    }

    /** Labeled toolbar row that sits above the chart. */
    private HBox buildAxisToolbar(ToggleButton btnEnergy, ToggleButton btnPrice) {
        Label lbl = new Label("Y-Axis:");
        lbl.setFont(Font.font("System", FontWeight.SEMI_BOLD, 12));
        lbl.setTextFill(Color.web("#334155"));
        HBox bar = new HBox(8, lbl, btnEnergy, btnPrice);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(0, 0, 4, 0));
        return bar;
    }

    /**
     * Returns a new list of EnergyChartWindow.HourlyEnergyPoint with
     * each kWh value multiplied by #CZK_PER_KWH.
     * The hourMsUtc timestamp is preserved unchanged so the time axis
     * remains correct.
     */
    private static List<EnergyChartWindow.HourlyEnergyPoint> scaleToCzk(
            List<EnergyChartWindow.HourlyEnergyPoint> pts) {
        return pts.stream()
                .map(p -> new EnergyChartWindow.HourlyEnergyPoint(
                        p.hourMsUtc(), p.kwh() * CZK_PER_KWH))
                .toList();
    }

    // =========================================================================
    // UI component factories
    // =========================================================================

    private Node createChartPlaceholder(String text) {
        Label placeholder = new Label(text);
        placeholder.setFont(Font.font("System", FontWeight.SEMI_BOLD, 15));
        placeholder.setTextFill(Color.web("#64748B"));
        StackPane pane = new StackPane(placeholder);
        pane.setMinHeight(290);
        pane.setPrefHeight(330);
        pane.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(pane, Priority.ALWAYS);
        HBox.setHgrow(pane, Priority.ALWAYS);
        pane.setStyle("""
            -fx-background-color: #F8FAFC;
            -fx-border-color: #CBD5E1;
            -fx-border-radius: 8;
            -fx-background-radius: 8;
        """);
        return pane;
    }

    private Node createMetricCard(String label, String value) {
        Label left  = new Label(label);
        left.setFont(Font.font("System", 12));
        left.setTextFill(Color.web("#475569"));
        Label right = new Label(value);
        right.setFont(Font.font("System", FontWeight.BOLD, 12));
        right.setTextFill(Color.web("#0F172A"));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, left, spacer, right);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10));
        row.setStyle("""
            -fx-background-color: #F8FAFC;
            -fx-border-color: #E2E8F0;
            -fx-border-radius: 8;
            -fx-background-radius: 8;
        """);
        return row;
    }

    private Node createSectionHeader(String text) {
        Label label = new Label(text);
        label.setFont(Font.font("System", FontWeight.BOLD, 13));
        label.setTextFill(Color.web("#334155"));
        VBox box = new VBox(label);
        box.setPadding(new Insets(8, 2, 2, 2));
        return box;
    }

    private Node createContributionRow(String name, String value, String percent) {
        Label left  = new Label(name);
        left.setFont(Font.font("System", 12));
        left.setTextFill(Color.web("#334155"));
        Label mid   = new Label(value);
        mid.setFont(Font.font("System", FontWeight.SEMI_BOLD, 12));
        mid.setTextFill(Color.web("#0F172A"));
        Label right = new Label(percent);
        right.setFont(Font.font("System", 12));
        right.setTextFill(Color.web("#2563EB"));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, left, spacer, mid, right);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 10, 8, 10));
        row.setStyle("""
            -fx-background-color: #FFFFFF;
            -fx-border-color: #E2E8F0;
            -fx-border-radius: 6;
            -fx-background-radius: 6;
        """);
        return row;
    }

    private Node createCompareContributionRow(
            String name, String val1, String pct1, String val2, String pct2) {
        Label lbl = new Label(name);
        lbl.setFont(Font.font("System", FontWeight.SEMI_BOLD, 12));
        lbl.setTextFill(Color.web("#334155"));
        Label v1 = new Label(val1);
        v1.setFont(Font.font("System", FontWeight.BOLD, 12));
        v1.setTextFill(Color.web("#e8572a"));
        Label p1 = new Label(pct1);
        p1.setFont(Font.font("System", 11));
        p1.setTextFill(Color.web("#2563EB"));
        Region sp1 = new Region();
        HBox.setHgrow(sp1, Priority.ALWAYS);
        HBox row1 = new HBox(6, v1, sp1, p1);
        row1.setAlignment(Pos.CENTER_LEFT);
        Label v2 = new Label(val2);
        v2.setFont(Font.font("System", FontWeight.BOLD, 12));
        v2.setTextFill(Color.web("#c2410c"));
        Label p2 = new Label(pct2);
        p2.setFont(Font.font("System", 11));
        p2.setTextFill(Color.web("#3b82f6"));
        Region sp2 = new Region();
        HBox.setHgrow(sp2, Priority.ALWAYS);
        HBox row2 = new HBox(6, v2, sp2, p2);
        row2.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(2, lbl, row1, row2);
        card.setPadding(new Insets(6, 10, 6, 10));
        card.setStyle("""
            -fx-background-color: #FFFFFF;
            -fx-border-color: #E2E8F0;
            -fx-border-radius: 6;
            -fx-background-radius: 6;
        """);
        return card;
    }

    private Node createCompareSectionHeader(String labelDay1, String labelDay2) {
        Label title = new Label("Compare");
        title.setFont(Font.font("System", FontWeight.BOLD, 13));
        title.setTextFill(Color.web("#334155"));

        Label d1 = new Label(labelDay1);
        d1.setFont(Font.font("System", FontWeight.BOLD, 11));
        d1.setTextFill(Color.web("#e8572a"));
        Label vs = new Label(" vs ");
        vs.setFont(Font.font("System", 11));
        vs.setTextFill(Color.web("#64748B"));
        Label d2 = new Label(labelDay2);
        d2.setFont(Font.font("System", FontWeight.BOLD, 11));
        d2.setTextFill(Color.web("#9c27b0"));

        HBox datesRow = new HBox(2, d1, vs, d2);
        datesRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(2, title, datesRow);
        box.setPadding(new Insets(2, 2, 4, 2));
        return box;
    }

    private Node createCompareRow(String label, String val1, String val2, String delta) {
        Label lbl = new Label(label);
        lbl.setFont(Font.font("System", FontWeight.SEMI_BOLD, 12));
        lbl.setTextFill(Color.web("#475569"));
        HBox line1 = new HBox(lbl);
        line1.setAlignment(Pos.CENTER_LEFT);
        if (delta != null && !delta.isBlank()) {
            boolean positive = delta.startsWith("▲");
            Label dLbl = new Label(delta);
            dLbl.setFont(Font.font("System", FontWeight.BOLD, 11));
            dLbl.setTextFill(positive ? Color.web("#16a34a") : Color.web("#dc2626"));
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            line1.getChildren().addAll(spacer, dLbl);
        }
        Label v1 = new Label(val1);
        v1.setFont(Font.font("System", FontWeight.BOLD, 12));
        v1.setTextFill(Color.web("#e8572a"));
        Label v2 = new Label(val2);
        v2.setFont(Font.font("System", FontWeight.BOLD, 12));
        v2.setTextFill(Color.web("#9c27b0"));
        VBox card = new VBox(2, line1, v1, v2);
        card.setPadding(new Insets(6, 10, 6, 10));
        card.setStyle("""
            -fx-background-color: #F8FAFC;
            -fx-border-color: #E2E8F0;
            -fx-border-radius: 6;
            -fx-background-radius: 6;
        """);
        return card;
    }

    // =========================================================================
    // Widget helpers
    // =========================================================================

    private Label rangeLabel(String text) {
        Label lbl = new Label(text);
        lbl.setFont(Font.font("System", 11));
        lbl.setTextFill(Color.web("#475569"));
        lbl.setStyle("""
            -fx-background-color: #EFF6FF;
            -fx-border-color: #BFDBFE;
            -fx-border-radius: 4;
            -fx-background-radius: 4;
            -fx-padding: 2 6;
        """);
        return lbl;
    }

    private ComboBox<Month> monthCombo(Month initial) {
        ComboBox<Month> cb = new ComboBox<>();
        cb.getItems().addAll(Month.values());
        cb.setValue(initial);
        cb.setPrefWidth(115);
        cb.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Month m) {
                return m == null ? "" : m.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            }
            @Override public Month fromString(String s) { return null; }
        });
        return cb;
    }

    private Spinner<Integer> yearSpinner(int initialYear) {
        Spinner<Integer> sp = new Spinner<>(2000, 2100, initialYear);
        sp.setPrefWidth(85);
        sp.setEditable(true);
        return sp;
    }

    private ToggleButton granularityToggle(String text, ToggleGroup group) {
        ToggleButton btn = new ToggleButton(text);
        btn.setToggleGroup(group);
        String base = """
                -fx-background-color: #E2E8F0;
                -fx-text-fill: #334155;
                -fx-background-radius: 6;
                -fx-padding: 4 14;
                -fx-font-size: 12px;
                -fx-cursor: hand;
                """;
        String active = """
                -fx-background-color: #1E40AF;
                -fx-text-fill: white;
                -fx-background-radius: 6;
                -fx-padding: 4 14;
                -fx-font-size: 12px;
                -fx-cursor: hand;
                """;
        btn.setStyle(base);
        btn.selectedProperty().addListener((obs, was, is) -> btn.setStyle(is ? active : base));
        return btn;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean validateDates() {
        LocalDate[] r1 = range1();
        if (r1[0] == null || r1[1] == null) {
            chartContainer.getChildren().setAll(createChartPlaceholder("Please select dates"));
            return false;
        }
        if (!isCompareMode() && r1[0].isAfter(r1[1])) {
            chartContainer.getChildren().setAll(createChartPlaceholder("Invalid date range"));
            return false;
        }
        return true;
    }

    private void runTask(Task<Void> task, String threadName) {
        Thread thread = new Thread(task, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    private String buildSubtitle() {
        String ser = serial();
        if (!isCompareMode()) {
            LocalDate[] r = range1();
            return "Meter: " + ser + "   |   Range: " + r[0] + " to " + r[1];
        }
        LocalDate[] r1 = range1();
        LocalDate[] r2 = range2();
        String p1   = periodLabel(r1[0], r1[1]);
        String p2   = periodLabel(r2[0], r2[1]);
        String kind = switch (compareGranularity) {
            case DAYS   -> "Comparing days";
            case WEEKS  -> "Comparing weeks";
            case MONTHS -> "Comparing months";
        };
        return "Meter: " + ser + "   |   " + kind + ": " + p1 + " vs " + p2;
    }

    private String periodLabel(LocalDate start, LocalDate end) {
        if (start == null) return "--";
        if (start.equals(end)) {
            return start.format(DateTimeFormatter.ofPattern("d MMM yyyy"));
        }
        if (start.getDayOfMonth() == 1 && end.equals(YearMonth.from(start).atEndOfMonth())) {
            return start.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                    + " " + start.getYear();
        }
        if (start.getYear() == end.getYear()) {
            return start.format(DateTimeFormatter.ofPattern("d MMM"))
                    + "–" + end.format(DateTimeFormatter.ofPattern("d MMM yyyy"));
        }
        return start.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
                + "–" + end.format(DateTimeFormatter.ofPattern("d MMM yyyy"));
    }

    private String serial() {
        return serialResolver.resolve(serialField == null ? "" : serialField.getText());
    }

    private boolean isCompareMode() {
        return compareModeCheckBox != null && compareModeCheckBox.isSelected();
    }

    private CompareChartWindow.PeriodType toPeriodType() {
        return switch (compareGranularity) {
            case DAYS   -> CompareChartWindow.PeriodType.DAY;
            case WEEKS  -> CompareChartWindow.PeriodType.WEEK;
            case MONTHS -> CompareChartWindow.PeriodType.MONTH;
        };
    }

    private void updateDateLabels() {
        if (fromLabel == null || toLabel == null) return;
        if (isCompareMode()) {
            fromLabel.setText(switch (compareGranularity) {
                case DAYS   -> "Day 1:";
                case WEEKS  -> "Week 1:";
                case MONTHS -> "Month 1:";
            });
            toLabel.setText(switch (compareGranularity) {
                case DAYS   -> "Day 2:";
                case WEEKS  -> "Week 2:";
                case MONTHS -> "Month 2:";
            });
        } else {
            fromLabel.setText("From:");
            toLabel.setText("To:");
        }
    }

    // ── Date math ─────────────────────────────────────────────────────────────

    private static LocalDate mondayOf(LocalDate date) {
        if (date == null) return LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static LocalDate mondayOnOrAfter(LocalDate date) {
        if (date == null) return LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        return date.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }

    private static LocalDate sundayOf(LocalDate date) {
        if (date == null) return LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        return date.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
    }

    private static String weekRangeText(LocalDate monday) {
        if (monday == null) return "";
        LocalDate sunday = monday.plusDays(6);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("d MMM");
        return monday.format(fmt) + " – " + sunday.format(fmt);
    }

    // ── Style helpers ─────────────────────────────────────────────────────────

    private void stylePrimaryButton(Button btn) {
        btn.setStyle("""
            -fx-background-color: #2563EB;
            -fx-text-fill: white;
            -fx-background-radius: 10;
            -fx-padding: 10 18;
            -fx-font-size: 13px;
            -fx-font-weight: bold;
        """);
        btn.setMinHeight(38);
    }

    private String cardStyle() {
        return """
            -fx-background-color: #F8FAFC;
            -fx-border-color: #E2E8F0;
            -fx-border-radius: 10;
            -fx-background-radius: 10;
        """;
    }

    // ── Conversion helpers ────────────────────────────────────────────────────

    private List<EnergyChartWindow.HourlyEnergyPoint> convertHourly(
            List<PushDataQueries.HourlyEnergyPoint> raw) {
        return raw.stream()
                .map(p -> new EnergyChartWindow.HourlyEnergyPoint(p.hourMsUtc(), p.kwh()))
                .toList();
    }

    private List<EnergyChartWindow.InstantPowerPoint> convertInstant(
            List<PushDataQueries.InstantPowerPoint> raw) {
        return raw.stream()
                .map(p -> new EnergyChartWindow.InstantPowerPoint(p.tsMsUtc(), p.watts()))
                .toList();
    }

    private double avg(List<EnergyChartWindow.InstantPowerPoint> pts) {
        return pts.stream().mapToLong(EnergyChartWindow.InstantPowerPoint::watts).average().orElse(0);
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    private String formatWatts(double v)   { return String.format("%.0f W", v); }
    private String formatKwh(double v)     { return String.format("%.3f kWh", v); }
    private String formatCzk(double v)     { return String.format("%.2f CZK", v); }
    private String formatPercent(double v) { return String.format("%.1f %%", v * 100.0); }

    private String formatDateTime(long ts) {
        if (ts == 0) return "--";
        return java.time.Instant.ofEpochMilli(ts)
                .atZone(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    private String deltaKwh(double v1, double v2) {
        double diff = v2 - v1;
        return diff >= 0
                ? String.format("▲ %.3f kWh", diff)
                : String.format("▼ %.3f kWh", Math.abs(diff));
    }

    private String deltaCzk(double v1, double v2) {
        double diff = v2 - v1;
        return diff >= 0
                ? String.format("▲ %.2f CZK", diff)
                : String.format("▼ %.2f CZK", Math.abs(diff));
    }

    private String deltaWatts(double v1, double v2) {
        double diff = v2 - v1;
        return diff >= 0
                ? String.format("▲ %.0f W", diff)
                : String.format("▼ %.0f W", Math.abs(diff));
    }

    // ── View enum ─────────────────────────────────────────────────────────────

    private enum DashboardView {
        HOURLY_IMPORT_ENERGY("Hourly Import Energy"),
        HOURLY_EXPORT_ENERGY("Hourly Export Energy"),
        HOURLY_REGISTER_OVERVIEW("Total Production (Export)"),
        INSTANT_IMPORT_POWER_PHASE("Instant Import Power - Phase View"),
        INSTANT_IMPORT_POWER_TARIFF("Instant Import Power - Tariff View"),
        INSTANT_EXPORT_POWER("Instant Export Power"),
        TOTAL_CONSUMPTION_IMPORT("Total Consumption (Import)");

        private final String label;
        DashboardView(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
}