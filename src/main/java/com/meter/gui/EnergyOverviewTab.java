package com.meter.gui;

import com.meter.database.PushDataQueries;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Energy Overview" tab — grouped bar chart showing imported and exported
 * energy per month, week, or year, with an analytics summary panel.
 */
public class EnergyOverviewTab {

    private static final String COLOR_IMPORT = "#e8572a";
    private static final String COLOR_EXPORT = "#29b6f6";

    // ── Dependencies ─────────────────────────────────────────────────────────
    private final PushDataQueries query;
    private final SerialResolver serialResolver;

    @FunctionalInterface
    public interface SerialResolver {
        String resolve(String typed);
    }

    // ── UI state ─────────────────────────────────────────────────────────────
    private TextField serialField;

    // Monthly / Yearly pickers
    private Spinner<Integer> fromYearSpinner;
    private Spinner<Integer> toYearSpinner;

    // Weekly pickers
    private Spinner<Integer> weekYearSpinner;
    private ComboBox<String>  weekMonthCombo;

    // Container that holds either the range pickers or the weekly pickers
    private HBox rangePickerBox;
    private HBox weeklyPickerBox;

    private ToggleButton btnMonthly;
    private ToggleButton btnWeekly;
    private ToggleButton btnYearly;

    private Label chartTitleLabel;
    private Label chartSubtitleLabel;
    private VBox chartContainer;
    private VBox analyticsContainer;

    private static final String[] MONTH_NAMES = {
            "January","February","March","April","May","June",
            "July","August","September","October","November","December"
    };

    public EnergyOverviewTab(PushDataQueries query, SerialResolver serialResolver) {
        this.query = query;
        this.serialResolver = serialResolver;
    }

    // =========================================================================
    // Build
    // =========================================================================

    public Tab build() {
        Tab tab = new Tab("Energy Overview");
        tab.setClosable(false);

        ScrollPane scroll = new ScrollPane(buildContent());
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setPannable(true);
        scroll.setStyle("-fx-background: white; -fx-background-color: white;");
        tab.setContent(scroll);
        return tab;
    }

    private VBox buildContent() {
        VBox root = new VBox(12);
        root.setPadding(new Insets(16));
        root.setStyle("-fx-background-color: #FFFFFF;");

        Label title = new Label("Energy Overview");
        title.setFont(Font.font("System", FontWeight.BOLD, 22));
        title.setTextFill(Color.web("#1E40AF"));

        root.getChildren().addAll(
                title,
                new Separator(),
                buildFilterBar(),
                buildDashboardArea()
        );

        refresh();
        return root;
    }

    // ── Filter bar ────────────────────────────────────────────────────────────

    private Node buildFilterBar() {
        int currentYear  = LocalDate.now().getYear();
        int currentMonth = LocalDate.now().getMonthValue();

        // ── Month / Year range pickers (used by Monthly + Yearly modes) ──────
        fromYearSpinner = new Spinner<>(2000, currentYear, currentYear - 1);
        fromYearSpinner.setEditable(true);
        fromYearSpinner.setPrefWidth(90);
        fromYearSpinner.valueProperty().addListener((o, ov, nv) -> { if (isRangeMode()) refresh(); });

        toYearSpinner = new Spinner<>(2000, currentYear, currentYear);
        toYearSpinner.setEditable(true);
        toYearSpinner.setPrefWidth(90);
        toYearSpinner.valueProperty().addListener((o, ov, nv) -> { if (isRangeMode()) refresh(); });

        rangePickerBox = new HBox(8,
                new Label("From year:"), fromYearSpinner,
                new Label("To year:"),   toYearSpinner
        );
        rangePickerBox.setAlignment(Pos.CENTER_LEFT);

        // ── Weekly pickers ────────────────────────────────────────────────────
        weekYearSpinner = new Spinner<>(2000, currentYear, currentYear);
        weekYearSpinner.setEditable(true);
        weekYearSpinner.setPrefWidth(90);
        weekYearSpinner.valueProperty().addListener((o, ov, nv) -> { if (isWeeklyMode()) refresh(); });

        weekMonthCombo = new ComboBox<>();
        weekMonthCombo.getItems().addAll(MONTH_NAMES);
        weekMonthCombo.getSelectionModel().select(currentMonth - 1);
        weekMonthCombo.setPrefWidth(130);
        weekMonthCombo.valueProperty().addListener((o, ov, nv) -> { if (isWeeklyMode()) refresh(); });

        weeklyPickerBox = new HBox(8,
                new Label("Year:"),  weekYearSpinner,
                new Label("Month:"), weekMonthCombo
        );
        weeklyPickerBox.setAlignment(Pos.CENTER_LEFT);
        weeklyPickerBox.setVisible(false);
        weeklyPickerBox.setManaged(false);

        // ── Toggle group ──────────────────────────────────────────────────────
        ToggleGroup grp = new ToggleGroup();
        btnMonthly = styledToggle("Monthly", grp);
        btnWeekly  = styledToggle("Weekly",  grp);
        btnYearly  = styledToggle("Yearly",  grp);
        btnMonthly.setSelected(true);

        grp.selectedToggleProperty().addListener((o, ov, nv) -> {
            if (nv == null && ov != null) { ov.setSelected(true); return; }
            boolean weekly = (nv == btnWeekly);
            rangePickerBox.setVisible(!weekly);
            rangePickerBox.setManaged(!weekly);
            weeklyPickerBox.setVisible(weekly);
            weeklyPickerBox.setManaged(weekly);
            refresh();
        });

        // ── Serial field ──────────────────────────────────────────────────────
        Label serialLbl = new Label("Serial:");
        serialField = new TextField();
        serialField.setPromptText("Auto / last detected");
        serialField.setPrefWidth(180);
        serialField.setOnAction(e -> refresh());

        Button refreshBtn = new Button("Refresh");
        refreshBtn.setStyle("""
            -fx-background-color: #2563EB;
            -fx-text-fill: white;
            -fx-background-radius: 10;
            -fx-padding: 8 16;
            -fx-font-weight: bold;
        """);
        refreshBtn.setOnAction(e -> refresh());

        HBox row = new HBox(10,
                new HBox(4, btnMonthly, btnWeekly, btnYearly),
                rangePickerBox,
                weeklyPickerBox,
                serialLbl, serialField,
                refreshBtn
        );
        row.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(row);
        card.setPadding(new Insets(10));
        card.setStyle(cardStyle());
        return card;
    }

    // ── Dashboard area ────────────────────────────────────────────────────────

    private Node buildDashboardArea() {
        chartTitleLabel = new Label("Energy Overview");
        chartTitleLabel.setFont(Font.font("System", FontWeight.BOLD, 18));
        chartTitleLabel.setTextFill(Color.web("#0F172A"));

        chartSubtitleLabel = new Label("");
        chartSubtitleLabel.setFont(Font.font("System", 12));
        chartSubtitleLabel.setTextFill(Color.web("#64748B"));

        chartContainer = new VBox();
        chartContainer.setAlignment(Pos.CENTER);
        chartContainer.setPadding(new Insets(2));
        chartContainer.setMinHeight(320);
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
        VBox.setVgrow(chartCard, Priority.ALWAYS);

        Label analyticsTitle = new Label("Summary");
        analyticsTitle.setFont(Font.font("System", FontWeight.BOLD, 16));
        analyticsTitle.setTextFill(Color.web("#0F172A"));

        analyticsContainer = new VBox(6);
        analyticsContainer.setPadding(new Insets(0));

        VBox analyticsCard = new VBox(6, analyticsTitle, analyticsContainer);
        analyticsCard.setPadding(new Insets(10, 12, 12, 12));
        analyticsCard.setStyle(cardStyle());
        analyticsCard.setPrefWidth(340);
        analyticsCard.setMinWidth(300);

        HBox main = new HBox(12, chartCard, analyticsCard);
        main.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(chartCard, Priority.ALWAYS);
        VBox.setVgrow(main, Priority.ALWAYS);
        return main;
    }

    // =========================================================================
    // Data loading
    // =========================================================================

    private boolean isWeeklyMode() {
        return btnWeekly != null && btnWeekly.isSelected();
    }

    private boolean isRangeMode() {
        return !isWeeklyMode();
    }

    private void refresh() {
        if (fromYearSpinner == null) return;

        String serial = serialResolver.resolve(serialField == null ? "" : serialField.getText());

        if (isWeeklyMode()) {
            refreshWeekly(serial);
        } else if (btnYearly != null && btnYearly.isSelected()) {
            refreshYearly(serial);
        } else {
            refreshMonthly(serial);
        }
    }

    private void refreshMonthly(String serial) {
        int fromYear = fromYearSpinner.getValue();
        int toYear   = toYearSpinner.getValue();
        if (fromYear > toYear) return;

        final int finalFrom = fromYear;
        final int finalTo   = toYear;

        LocalDate from = LocalDate.of(fromYear, 1, 1);
        LocalDate to   = LocalDate.of(toYear,  12, 31);

        chartTitleLabel.setText("Monthly Energy");
        chartSubtitleLabel.setText("Meter: " + serial
                + "   |   " + fromYear + (fromYear == toYear ? "" : " \u2013 " + toYear));
        chartContainer.getChildren().setAll(placeholder("Loading\u2026"));

        Task<Void> task = new Task<>() {
            List<PushDataQueries.PeriodEnergyPoint> importPts;
            List<PushDataQueries.PeriodEnergyPoint> exportPts;

            @Override protected Void call() throws Exception {
                importPts = query.getMonthlyImportEnergy(serial, from, to);
                exportPts = query.getMonthlyExportEnergy(serial, from, to);
                return null;
            }

            @Override protected void succeeded() {
                renderMonthlyChart(importPts, exportPts, finalFrom, finalTo);
            }

            @Override protected void failed() {
                getException().printStackTrace();
                chartContainer.getChildren().setAll(placeholder("Failed to load data"));
            }
        };
        daemonThread(task, "energy-monthly-loader");
    }

    private void refreshYearly(String serial) {
        int fromYear = fromYearSpinner.getValue();
        int toYear   = toYearSpinner.getValue();
        if (fromYear > toYear) return;

        final int finalFrom = fromYear;
        final int finalTo   = toYear;

        LocalDate from = LocalDate.of(fromYear, 1, 1);
        LocalDate to   = LocalDate.of(toYear,  12, 31);

        chartTitleLabel.setText("Yearly Energy");
        chartSubtitleLabel.setText("Meter: " + serial
                + "   |   " + fromYear + (fromYear == toYear ? "" : " \u2013 " + toYear));
        chartContainer.getChildren().setAll(placeholder("Loading\u2026"));

        Task<Void> task = new Task<>() {
            List<PushDataQueries.PeriodEnergyPoint> importPts;
            List<PushDataQueries.PeriodEnergyPoint> exportPts;

            @Override protected Void call() throws Exception {
                importPts = query.getYearlyImportEnergy(serial, from, to);
                exportPts = query.getYearlyExportEnergy(serial, from, to);
                return null;
            }

            @Override protected void succeeded() {
                renderYearlyChart(importPts, exportPts, finalFrom, finalTo);
            }

            @Override protected void failed() {
                getException().printStackTrace();
                chartContainer.getChildren().setAll(placeholder("Failed to load data"));
            }
        };
        daemonThread(task, "energy-yearly-loader");
    }

    private void refreshWeekly(String serial) {
        int year  = weekYearSpinner.getValue();
        int month = weekMonthCombo.getSelectionModel().getSelectedIndex() + 1; // 1-based
        if (month < 1 || month > 12) return;

        String monthName = MONTH_NAMES[month - 1];

        chartTitleLabel.setText("Weekly Energy \u2014 " + monthName + " " + year);
        chartSubtitleLabel.setText("Meter: " + serial + "   |   Weeks in " + monthName + " " + year);
        chartContainer.getChildren().setAll(placeholder("Loading\u2026"));

        final int finalYear  = year;
        final int finalMonth = month;

        Task<Void> task = new Task<>() {
            List<PushDataQueries.WeeklyEnergyPoint> importPts;
            List<PushDataQueries.WeeklyEnergyPoint> exportPts;

            @Override protected Void call() throws Exception {
                importPts = query.getWeeklyImportEnergy(serial, finalYear, finalMonth);
                exportPts = query.getWeeklyExportEnergy(serial, finalYear, finalMonth);
                return null;
            }

            @Override protected void succeeded() {
                renderWeeklyChart(importPts, exportPts);
            }

            @Override protected void failed() {
                getException().printStackTrace();
                chartContainer.getChildren().setAll(placeholder("Failed to load data"));
            }
        };
        daemonThread(task, "energy-weekly-loader");
    }

    private static void daemonThread(Task<?> task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }

    // =========================================================================
    // Chart renderers
    // =========================================================================

    /**
     * Monthly chart — x-axis labels are "Jan 2024", "Feb 2024" etc.
     */
    private void renderMonthlyChart(
            List<PushDataQueries.PeriodEnergyPoint> importPts,
            List<PushDataQueries.PeriodEnergyPoint> exportPts,
            int fromYear, int toYear
    ) {
        CategoryAxis xAxis = new CategoryAxis();
        xAxis.setLabel("Month");
        xAxis.setTickLabelRotation(45);

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Energy (kWh)");
        yAxis.setForceZeroInRange(true);

        BarChart<String, Number> chart = buildBarChart(xAxis, yAxis, 4, 2);

        XYChart.Series<String, Number> importSeries = new XYChart.Series<>();
        importSeries.setName("Import (kWh)");

        XYChart.Series<String, Number> exportSeries = new XYChart.Series<>();
        exportSeries.setName("Export (kWh)");

        for (int y = fromYear; y <= toYear; y++) {
            for (int m = 1; m <= 12; m++) {
                String label = monthLabel(y, m, fromYear != toYear);
                importSeries.getData().add(new XYChart.Data<>(label, findValue(importPts, y, m)));
                exportSeries.getData().add(new XYChart.Data<>(label, findValue(exportPts, y, m)));
            }
        }

        chart.getData().addAll(importSeries, exportSeries);
        applyBarColors(chart);
        chartContainer.getChildren().setAll(chart);
        renderMonthlyAnalytics(importPts, exportPts);
    }

    /**
     * Weekly chart — x-axis labels are "1 Week", "2 Week", etc.
     */
    private void renderWeeklyChart(
            List<PushDataQueries.WeeklyEnergyPoint> importPts,
            List<PushDataQueries.WeeklyEnergyPoint> exportPts
    ) {
        CategoryAxis xAxis = new CategoryAxis();
        xAxis.setLabel("Week");

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Energy (kWh)");
        yAxis.setForceZeroInRange(true);

        BarChart<String, Number> chart = buildBarChart(xAxis, yAxis, 20, 6);

        XYChart.Series<String, Number> importSeries = new XYChart.Series<>();
        importSeries.setName("Import (kWh)");

        XYChart.Series<String, Number> exportSeries = new XYChart.Series<>();
        exportSeries.setName("Export (kWh)");

        // Collect all distinct week labels from both series in order
        java.util.LinkedHashMap<Integer, String> weekLabels = new java.util.LinkedHashMap<>();
        importPts.forEach(p -> weekLabels.put(p.weekOfMonth(), p.weekOfMonth() + " Week"));
        exportPts.forEach(p -> weekLabels.putIfAbsent(p.weekOfMonth(), p.weekOfMonth() + " Week"));

        for (java.util.Map.Entry<Integer, String> entry : weekLabels.entrySet()) {
            int idx   = entry.getKey();
            String lbl = entry.getValue();
            double imp = importPts.stream().filter(p -> p.weekOfMonth() == idx)
                    .mapToDouble(PushDataQueries.WeeklyEnergyPoint::kwh).sum();
            double exp = exportPts.stream().filter(p -> p.weekOfMonth() == idx)
                    .mapToDouble(PushDataQueries.WeeklyEnergyPoint::kwh).sum();
            importSeries.getData().add(new XYChart.Data<>(lbl, imp));
            exportSeries.getData().add(new XYChart.Data<>(lbl, exp));
        }

        chart.getData().addAll(importSeries, exportSeries);
        applyBarColors(chart);
        chartContainer.getChildren().setAll(chart);
        renderWeeklyAnalytics(importPts, exportPts);
    }

    /**
     * Yearly chart — one pair of bars per year.
     */
    private void renderYearlyChart(
            List<PushDataQueries.PeriodEnergyPoint> importPts,
            List<PushDataQueries.PeriodEnergyPoint> exportPts,
            int fromYear, int toYear
    ) {
        CategoryAxis xAxis = new CategoryAxis();
        xAxis.setLabel("Year");

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Energy (kWh)");
        yAxis.setForceZeroInRange(true);

        BarChart<String, Number> chart = buildBarChart(xAxis, yAxis, 10, 3);

        XYChart.Series<String, Number> importSeries = new XYChart.Series<>();
        importSeries.setName("Import (kWh)");

        XYChart.Series<String, Number> exportSeries = new XYChart.Series<>();
        exportSeries.setName("Export (kWh)");

        for (int y = fromYear; y <= toYear; y++) {
            final int year = y;
            String label = String.valueOf(year);
            double imp = importPts.stream().filter(p -> p.year() == year).mapToDouble(PushDataQueries.PeriodEnergyPoint::kwh).sum();
            double exp = exportPts.stream().filter(p -> p.year() == year).mapToDouble(PushDataQueries.PeriodEnergyPoint::kwh).sum();
            importSeries.getData().add(new XYChart.Data<>(label, imp));
            exportSeries.getData().add(new XYChart.Data<>(label, exp));
        }

        chart.getData().addAll(importSeries, exportSeries);
        applyBarColors(chart);
        chartContainer.getChildren().setAll(chart);
        renderYearlyAnalytics(importPts, exportPts);
    }

    private BarChart<String, Number> buildBarChart(
            CategoryAxis xAxis, NumberAxis yAxis,
            double catGap, double barGap
    ) {
        BarChart<String, Number> chart = new BarChart<>(xAxis, yAxis);
        chart.setAnimated(false);
        chart.setLegendVisible(true);
        chart.setCategoryGap(catGap);
        chart.setBarGap(barGap);
        chart.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(chart, Priority.ALWAYS);
        return chart;
    }

    // =========================================================================
    // Analytics panels
    // =========================================================================

    private void renderMonthlyAnalytics(
            List<PushDataQueries.PeriodEnergyPoint> importPts,
            List<PushDataQueries.PeriodEnergyPoint> exportPts
    ) {
        double totalImport = importPts.stream().mapToDouble(PushDataQueries.PeriodEnergyPoint::kwh).sum();
        double totalExport = exportPts.stream().mapToDouble(PushDataQueries.PeriodEnergyPoint::kwh).sum();

        var maxImport = importPts.stream().max(java.util.Comparator.comparingDouble(PushDataQueries.PeriodEnergyPoint::kwh)).orElse(null);
        var minImport = importPts.stream().filter(p -> p.kwh() > 0).min(java.util.Comparator.comparingDouble(PushDataQueries.PeriodEnergyPoint::kwh)).orElse(null);
        var maxExport = exportPts.stream().max(java.util.Comparator.comparingDouble(PushDataQueries.PeriodEnergyPoint::kwh)).orElse(null);
        var minExport = exportPts.stream().filter(p -> p.kwh() > 0).min(java.util.Comparator.comparingDouble(PushDataQueries.PeriodEnergyPoint::kwh)).orElse(null);

        analyticsContainer.getChildren().setAll(
                metricCard("Total Import",  formatKwh(totalImport),  COLOR_IMPORT),
                metricCard("Total Export",  formatKwh(totalExport),  COLOR_EXPORT),
                metricCard("Net (E - I)", formatKwh(totalExport - totalImport), "#334155"),
                sectionHeader("Best / Worst Month"),
                metricCard("Peak Import",
                        maxImport == null ? "--" : formatKwh(maxImport.kwh()) + "  " + monthLabel(maxImport.year(), maxImport.month(), true),
                        COLOR_IMPORT),
                metricCard("Min Import",
                        minImport == null ? "--" : formatKwh(minImport.kwh()) + "  " + monthLabel(minImport.year(), minImport.month(), true),
                        "#94a3b8"),
                metricCard("Peak Export",
                        maxExport == null ? "--" : formatKwh(maxExport.kwh()) + "  " + monthLabel(maxExport.year(), maxExport.month(), true),
                        COLOR_EXPORT),
                metricCard("Min Export",
                        minExport == null ? "--" : formatKwh(minExport.kwh()) + "  " + monthLabel(minExport.year(), minExport.month(), true),
                        "#94a3b8"),
                buildMonthlyBreakdownHeader(importPts, exportPts),
                monthlyBreakdownTable(importPts, exportPts)
        );
    }

    private void renderWeeklyAnalytics(
            List<PushDataQueries.WeeklyEnergyPoint> importPts,
            List<PushDataQueries.WeeklyEnergyPoint> exportPts
    ) {
        double totalImport = importPts.stream().mapToDouble(PushDataQueries.WeeklyEnergyPoint::kwh).sum();
        double totalExport = exportPts.stream().mapToDouble(PushDataQueries.WeeklyEnergyPoint::kwh).sum();

        var maxImport = importPts.stream().max(java.util.Comparator.comparingDouble(PushDataQueries.WeeklyEnergyPoint::kwh)).orElse(null);
        var minImport = importPts.stream().filter(p -> p.kwh() > 0).min(java.util.Comparator.comparingDouble(PushDataQueries.WeeklyEnergyPoint::kwh)).orElse(null);
        var maxExport = exportPts.stream().max(java.util.Comparator.comparingDouble(PushDataQueries.WeeklyEnergyPoint::kwh)).orElse(null);
        var minExport = exportPts.stream().filter(p -> p.kwh() > 0).min(java.util.Comparator.comparingDouble(PushDataQueries.WeeklyEnergyPoint::kwh)).orElse(null);

        analyticsContainer.getChildren().setAll(
                metricCard("Total Import",  formatKwh(totalImport),  COLOR_IMPORT),
                metricCard("Total Export",  formatKwh(totalExport),  COLOR_EXPORT),
                metricCard("Net (E - I)", formatKwh(totalExport - totalImport), "#334155"),
                sectionHeader("Best / Worst Week"),
                metricCard("Peak Import",
                        maxImport == null ? "--" : formatKwh(maxImport.kwh()) + "  " + maxImport.weekOfMonth() + " Week",
                        COLOR_IMPORT),
                metricCard("Min Import",
                        minImport == null ? "--" : formatKwh(minImport.kwh()) + "  " + minImport.weekOfMonth() + " Week",
                        "#94a3b8"),
                metricCard("Peak Export",
                        maxExport == null ? "--" : formatKwh(maxExport.kwh()) + "  " + maxExport.weekOfMonth() + " Week",
                        COLOR_EXPORT),
                metricCard("Min Export",
                        minExport == null ? "--" : formatKwh(minExport.kwh()) + "  " + minExport.weekOfMonth() + " Week",
                        "#94a3b8"),
                buildWeeklyBreakdownHeader(importPts, exportPts),
                weeklyBreakdownTable(importPts, exportPts)
        );
    }

    private void renderYearlyAnalytics(
            List<PushDataQueries.PeriodEnergyPoint> importPts,
            List<PushDataQueries.PeriodEnergyPoint> exportPts
    ) {
        double totalImport = importPts.stream().mapToDouble(PushDataQueries.PeriodEnergyPoint::kwh).sum();
        double totalExport = exportPts.stream().mapToDouble(PushDataQueries.PeriodEnergyPoint::kwh).sum();

        var maxImport = importPts.stream().max(java.util.Comparator.comparingDouble(PushDataQueries.PeriodEnergyPoint::kwh)).orElse(null);
        var minImport = importPts.stream().filter(p -> p.kwh() > 0).min(java.util.Comparator.comparingDouble(PushDataQueries.PeriodEnergyPoint::kwh)).orElse(null);
        var maxExport = exportPts.stream().max(java.util.Comparator.comparingDouble(PushDataQueries.PeriodEnergyPoint::kwh)).orElse(null);
        var minExport = exportPts.stream().filter(p -> p.kwh() > 0).min(java.util.Comparator.comparingDouble(PushDataQueries.PeriodEnergyPoint::kwh)).orElse(null);

        analyticsContainer.getChildren().setAll(
                metricCard("Total Import", formatKwh(totalImport), COLOR_IMPORT),
                metricCard("Total Export", formatKwh(totalExport), COLOR_EXPORT),
                metricCard("Net (E - I)", formatKwh(totalExport - totalImport), "#334155"),
                sectionHeader("Best / Worst Year"),
                metricCard("Peak Import",
                        maxImport == null ? "--" : maxImport.year() + "  " + formatKwh(maxImport.kwh()),
                        COLOR_IMPORT),
                metricCard("Min Import",
                        minImport == null ? "--" : minImport.year() + "  " + formatKwh(minImport.kwh()),
                        "#94a3b8"),
                metricCard("Peak Export",
                        maxExport == null ? "--" : maxExport.year() + "  " + formatKwh(maxExport.kwh()),
                        COLOR_EXPORT),
                metricCard("Min Export",
                        minExport == null ? "--" : minExport.year() + "  " + formatKwh(minExport.kwh()),
                        "#94a3b8"),
                buildYearlyBreakdownHeader(importPts, exportPts),
                yearlyBreakdownTable(importPts, exportPts)
        );
    }

    // ── Expandable breakdown headers ──────────────────────────────────────────────

    private Node buildBreakdownHeader(String title, Runnable onOpen) {
        Label lbl = new Label(title);
        lbl.setFont(Font.font("System", FontWeight.BOLD, 12));
        lbl.setTextFill(Color.web("#334155"));

        Button maxBtn = new Button("⛶");
        maxBtn.setStyle("""
        -fx-background-color: transparent;
        -fx-text-fill: #2563EB;
        -fx-font-size: 14px;
        -fx-padding: 0 4;
        -fx-cursor: hand;
    """);
        maxBtn.setTooltip(new Tooltip("Open in window"));
        maxBtn.setOnAction(e -> onOpen.run());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(4, lbl, spacer, maxBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(6, 2, 2, 2));
        return header;
    }

    private Node buildMonthlyBreakdownHeader(
            List<PushDataQueries.PeriodEnergyPoint> importPts,
            List<PushDataQueries.PeriodEnergyPoint> exportPts
    ) {
        return buildBreakdownHeader("Monthly Breakdown", () -> {
            VBox rows = new VBox(2);
            rows.getChildren().add(breakdownHeaderRow("Month", "Import", "Export", "Net"));

            java.util.Set<String> keys = new java.util.LinkedHashSet<>();
            importPts.forEach(p -> keys.add(p.year() + "-" + String.format("%02d", p.month())));
            exportPts.forEach(p -> keys.add(p.year() + "-" + String.format("%02d", p.month())));

            for (String key : keys) {
                int y = Integer.parseInt(key.split("-")[0]);
                int m = Integer.parseInt(key.split("-")[1]);
                double imp = findValue(importPts, y, m);
                double exp = findValue(exportPts, y, m);
                rows.getChildren().add(breakdownDataRow(
                        monthLabel(y, m, true), formatKwh(imp), formatKwh(exp), formatKwh(imp - exp)));
            }
            openBreakdownWindow("Monthly Breakdown", rows);
        });
    }

    private Node buildWeeklyBreakdownHeader(
            List<PushDataQueries.WeeklyEnergyPoint> importPts,
            List<PushDataQueries.WeeklyEnergyPoint> exportPts
    ) {
        return buildBreakdownHeader("Weekly Breakdown", () -> {
            VBox rows = new VBox(2);
            rows.getChildren().add(breakdownHeaderRow("Week", "Import", "Export", "Net"));

            java.util.LinkedHashMap<Integer, String> weekLabels = new java.util.LinkedHashMap<>();
            importPts.forEach(p -> weekLabels.put(p.weekOfMonth(), p.weekOfMonth() + " Week"));
            exportPts.forEach(p -> weekLabels.putIfAbsent(p.weekOfMonth(), p.weekOfMonth() + " Week"));

            for (java.util.Map.Entry<Integer, String> entry : weekLabels.entrySet()) {
                int idx = entry.getKey();
                String lbl = entry.getValue();
                double imp = importPts.stream().filter(p -> p.weekOfMonth() == idx)
                        .mapToDouble(PushDataQueries.WeeklyEnergyPoint::kwh).sum();
                double exp = exportPts.stream().filter(p -> p.weekOfMonth() == idx)
                        .mapToDouble(PushDataQueries.WeeklyEnergyPoint::kwh).sum();
                rows.getChildren().add(breakdownDataRow(lbl, formatKwh(imp), formatKwh(exp), formatKwh(imp - exp)));
            }
            openBreakdownWindow("Weekly Breakdown", rows);
        });
    }

    private Node buildYearlyBreakdownHeader(
            List<PushDataQueries.PeriodEnergyPoint> importPts,
            List<PushDataQueries.PeriodEnergyPoint> exportPts
    ) {
        return buildBreakdownHeader("Per-year Breakdown", () -> {
            VBox rows = new VBox(2);
            rows.getChildren().add(breakdownHeaderRow("Year", "Import", "Export", "Net"));

            java.util.Set<Integer> years = new java.util.TreeSet<>();
            importPts.forEach(p -> years.add(p.year()));
            exportPts.forEach(p -> years.add(p.year()));

            for (int y : years) {
                final int year = y;
                double imp = importPts.stream().filter(p -> p.year() == year)
                        .mapToDouble(PushDataQueries.PeriodEnergyPoint::kwh).sum();
                double exp = exportPts.stream().filter(p -> p.year() == year)
                        .mapToDouble(PushDataQueries.PeriodEnergyPoint::kwh).sum();
                rows.getChildren().add(breakdownDataRow(
                        String.valueOf(year), formatKwh(imp), formatKwh(exp), formatKwh(imp - exp)));
            }
            openBreakdownWindow("Per-year Breakdown", rows);
        });
    }

    private void openBreakdownWindow(String title, VBox rows) {
        ScrollPane sp = new ScrollPane(rows);
        sp.setFitToWidth(true);
        sp.setPadding(new Insets(10));
        sp.setStyle("-fx-background-color: white;");

        Stage stage = new Stage();
        stage.setTitle(title);
        stage.setScene(new Scene(sp, 600, 500));
        stage.setResizable(true);
        stage.show();
    }

    // ── Breakdown tables ──────────────────────────────────────────────────────

    private Node monthlyBreakdownTable(
            List<PushDataQueries.PeriodEnergyPoint> importPts,
            List<PushDataQueries.PeriodEnergyPoint> exportPts
    ) {
        VBox rows = new VBox(2);
        rows.getChildren().add(breakdownHeaderRow("Month", "Import", "Export", "Net"));

        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        importPts.forEach(p -> keys.add(p.year() + "-" + String.format("%02d", p.month())));
        exportPts.forEach(p -> keys.add(p.year() + "-" + String.format("%02d", p.month())));

        for (String key : keys) {
            int y = Integer.parseInt(key.split("-")[0]);
            int m = Integer.parseInt(key.split("-")[1]);
            double imp = findValue(importPts, y, m);
            double exp = findValue(exportPts, y, m);
            rows.getChildren().add(breakdownDataRow(
                    monthLabel(y, m, true), formatKwh(imp), formatKwh(exp), formatKwh(imp - exp)));
        }
        return scrolledTable(rows);
    }

    private Node weeklyBreakdownTable(
            List<PushDataQueries.WeeklyEnergyPoint> importPts,
            List<PushDataQueries.WeeklyEnergyPoint> exportPts
    ) {
        VBox rows = new VBox(2);
        rows.getChildren().add(breakdownHeaderRow("Week", "Import", "Export", "Net"));

        java.util.LinkedHashMap<Integer, String> weekLabels = new java.util.LinkedHashMap<>();
        importPts.forEach(p -> weekLabels.put(p.weekOfMonth(), p.weekOfMonth() + " Week"));
        exportPts.forEach(p -> weekLabels.putIfAbsent(p.weekOfMonth(), p.weekOfMonth() + " Week"));

        for (java.util.Map.Entry<Integer, String> entry : weekLabels.entrySet()) {
            int idx = entry.getKey();
            String lbl = entry.getValue();
            double imp = importPts.stream().filter(p -> p.weekOfMonth() == idx)
                    .mapToDouble(PushDataQueries.WeeklyEnergyPoint::kwh).sum();
            double exp = exportPts.stream().filter(p -> p.weekOfMonth() == idx)
                    .mapToDouble(PushDataQueries.WeeklyEnergyPoint::kwh).sum();
            rows.getChildren().add(breakdownDataRow(lbl, formatKwh(imp), formatKwh(exp), formatKwh(imp - exp)));
        }
        return scrolledTable(rows);
    }

    private Node yearlyBreakdownTable(
            List<PushDataQueries.PeriodEnergyPoint> importPts,
            List<PushDataQueries.PeriodEnergyPoint> exportPts
    ) {
        VBox rows = new VBox(2);
        rows.getChildren().add(breakdownHeaderRow("Year", "Import", "Export", "Net"));

        java.util.Set<Integer> years = new java.util.TreeSet<>();
        importPts.forEach(p -> years.add(p.year()));
        exportPts.forEach(p -> years.add(p.year()));

        for (int y : years) {
            final int year = y;
            double imp = importPts.stream().filter(p -> p.year() == year).mapToDouble(PushDataQueries.PeriodEnergyPoint::kwh).sum();
            double exp = exportPts.stream().filter(p -> p.year() == year).mapToDouble(PushDataQueries.PeriodEnergyPoint::kwh).sum();
            rows.getChildren().add(breakdownDataRow(String.valueOf(year), formatKwh(imp), formatKwh(exp), formatKwh(imp - exp)));
        }
        return scrolledTable(rows);
    }

    private ScrollPane scrolledTable(VBox rows) {
        ScrollPane sp = new ScrollPane(rows);
        sp.setFitToWidth(true);
        sp.setPrefHeight(220);
        sp.setMaxHeight(220);
        sp.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        return sp;
    }

    private Node breakdownHeaderRow(String c1, String c2, String c3, String c4) {
        return breakdownRow(c1, c2, c3, c4, true);
    }

    private Node breakdownDataRow(String c1, String c2, String c3, String c4) {
        return breakdownRow(c1, c2, c3, c4, false);
    }

    private Node breakdownRow(String c1, String c2, String c3, String c4, boolean header) {
        FontWeight w = header ? FontWeight.BOLD : FontWeight.SEMI_BOLD;

        Label l1 = styledCell(c1, w, header ? "#334155" : "#0F172A", 55);
        Label l2 = styledCell(c2, w, COLOR_IMPORT, 80);
        Label l3 = styledCell(c3, w, COLOR_EXPORT, 80);

        boolean negNet = !header && c4.startsWith("-");
        Label l4 = styledCell(c4, w, header ? "#334155" : (negNet ? "#dc2626" : "#16a34a"), 75);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(4, l1, spacer, l2, l3, l4);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 8, 4, 8));
        if (!header) {
            row.setStyle("""
                -fx-background-color: #F8FAFC;
                -fx-border-color: #E2E8F0;
                -fx-border-radius: 4;
                -fx-background-radius: 4;
            """);
        }
        return row;
    }

    private Label styledCell(String text, FontWeight weight, String color, double minWidth) {
        Label l = new Label(text);
        l.setFont(Font.font("System", weight, 11));
        l.setTextFill(Color.web(color));
        l.setMinWidth(minWidth);
        return l;
    }

    // =========================================================================
    // UI component helpers
    // =========================================================================

    private Node placeholder(String text) {
        Label lbl = new Label(text);
        lbl.setFont(Font.font("System", FontWeight.SEMI_BOLD, 15));
        lbl.setTextFill(Color.web("#64748B"));
        StackPane pane = new StackPane(lbl);
        pane.setMinHeight(280);
        pane.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(pane, Priority.ALWAYS);
        pane.setStyle("""
            -fx-background-color: #F8FAFC;
            -fx-border-color: #CBD5E1;
            -fx-border-radius: 8;
            -fx-background-radius: 8;
        """);
        return pane;
    }

    private Node metricCard(String label, String value, String valueColor) {
        Label lbl = new Label(label);
        lbl.setFont(Font.font("System", 11));
        lbl.setTextFill(Color.web("#475569"));

        Label val = new Label(value);
        val.setFont(Font.font("System", FontWeight.BOLD, 13));
        val.setTextFill(Color.web(valueColor));

        VBox card = new VBox(1, lbl, val);
        card.setPadding(new Insets(6, 10, 6, 10));
        card.setStyle("""
            -fx-background-color: #F8FAFC;
            -fx-border-color: #E2E8F0;
            -fx-border-radius: 6;
            -fx-background-radius: 6;
        """);
        return card;
    }

    private Node sectionHeader(String text) {
        Label lbl = new Label(text);
        lbl.setFont(Font.font("System", FontWeight.BOLD, 12));
        lbl.setTextFill(Color.web("#334155"));
        VBox box = new VBox(lbl);
        box.setPadding(new Insets(6, 2, 2, 2));
        return box;
    }

    private ToggleButton styledToggle(String text, ToggleGroup group) {
        ToggleButton btn = new ToggleButton(text);
        btn.setToggleGroup(group);
        btn.setStyle("""
            -fx-background-color: #E2E8F0;
            -fx-text-fill: #334155;
            -fx-background-radius: 6;
            -fx-padding: 4 12;
            -fx-font-size: 12px;
        """);
        btn.selectedProperty().addListener((obs, wasSelected, isSelected) ->
                btn.setStyle(isSelected
                        ? """
                    -fx-background-color: #2563EB;
                    -fx-text-fill: white;
                    -fx-background-radius: 6;
                    -fx-padding: 4 12;
                    -fx-font-size: 12px;
                    """
                        : """
                    -fx-background-color: #E2E8F0;
                    -fx-text-fill: #334155;
                    -fx-background-radius: 6;
                    -fx-padding: 4 12;
                    -fx-font-size: 12px;
                    """)
        );
        return btn;
    }

    // =========================================================================
    // Bar + legend coloring
    // =========================================================================

    private static void applyBarColors(BarChart<String, Number> chart) {
        Map<String, String> seriesColors = Map.of(
                "Import (kWh)", COLOR_IMPORT,
                "Export (kWh)", COLOR_EXPORT
        );

        // Apply bar fill color directly to each bar node by series name.
        // If the node isn't rendered yet, attach a listener to catch it when it appears.
        for (XYChart.Series<String, Number> series : chart.getData()) {
            String color = seriesColors.get(series.getName());
            if (color == null) continue;
            String style = "-fx-bar-fill: " + color + ";";
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

        // Fix legend symbol colors after layout.
        // JavaFX default color style must be overridden directly.
        javafx.application.Platform.runLater(() -> {
            chart.applyCss();
            chart.layout();
            for (javafx.scene.Node node : chart.lookupAll(".chart-legend-item")) {
                if (!(node instanceof Label label)) continue;
                String color = seriesColors.get(label.getText());
                if (color == null) continue;
                javafx.scene.Node symbol = label.lookup(".chart-legend-item-symbol");
                if (symbol != null) symbol.setStyle("-fx-background-color: " + color + ";");
            }
        });
    }

    // =========================================================================
    // Utility helpers
    // =========================================================================

    private static double findValue(List<PushDataQueries.PeriodEnergyPoint> pts, int year, int month) {
        return pts.stream()
                .filter(p -> p.year() == year && p.month() == month)
                .mapToDouble(PushDataQueries.PeriodEnergyPoint::kwh)
                .sum();
    }

    private static String monthLabel(int year, int month, boolean includeYear) {
        String abbr = Month.of(month).getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        return includeYear ? abbr + " " + year : abbr;
    }

    private static String formatKwh(double v) {
        if (Math.abs(v) >= 1000) return String.format("%.1f MWh", v / 1000.0);
        return String.format("%.2f kWh", v);
    }

    private static String cardStyle() {
        return """
            -fx-background-color: #F8FAFC;
            -fx-border-color: #E2E8F0;
            -fx-border-radius: 10;
            -fx-background-radius: 10;
        """;
    }
}