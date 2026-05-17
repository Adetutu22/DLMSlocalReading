package com.meter.gui;

import com.meter.database.PushDataQueries;
import com.meter.database.SqlLiteDB;
import com.meter.listener.GXDLMSPushListener;
import com.meter.listener.OpticalPollListener;
import com.meter.parser.EGDObisParser;
import com.meter.parser.EGDObisParser.Reading;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

import java.util.List;
import java.util.Map;

public class SmartMonitorApp extends Application {

    // -----------------------------------------------------------------------
    // Connection mode
    // -----------------------------------------------------------------------
    private enum ConnectionMode { RS485_PUSH, OPTICAL_POLL }
    private ConnectionMode currentMode = ConnectionMode.RS485_PUSH;

    // -----------------------------------------------------------------------
    // UI controls — connection panel
    // -----------------------------------------------------------------------
    private ToggleButton modeRs485Btn;
    private ToggleButton modeOpticalBtn;
    private ComboBox<String> manufacturerDropdown;
    private ComboBox<String> portDropdown;
    private Button connectBtn;
    private Button refreshPortsBtn;

    // Optical-only controls
    private HBox opticalSettingsRow;
    private TextField clientAddrField;
    private TextField serverAddrField;

    // -----------------------------------------------------------------------
    // UI controls — status / stats
    // -----------------------------------------------------------------------
    private Circle statusDot;
    private Label statusLabel;
    private Label lastUpdateLabel;

    private Label powerImportValue, powerExportValue;
    private Label energyImportValue, energyExportValue;
    private Label powerL1Value, powerL2Value, powerL3Value;
    private Label exportL1Value, exportL2Value, exportL3Value;

    private TextArea hexArea;
    private TextArea translatedArea;

    // -----------------------------------------------------------------------
    // Listeners
    // -----------------------------------------------------------------------
    private GXDLMSPushListener pushListener;
    private OpticalPollListener opticalListener;
    private boolean isConnected = false;

    // -----------------------------------------------------------------------
    // Data layer
    // -----------------------------------------------------------------------
    private SqlLiteDB database;
    private PushDataQueries query;
    private String lastSerial;

    private static SmartMonitorApp instance;
    public static SmartMonitorApp getInstance() { return instance; }

    public SmartMonitorApp() {
        instance = this;
        try {
            this.database = new SqlLiteDB("meter.db");
            this.query = new PushDataQueries(this.database.connection());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -----------------------------------------------------------------------
    // JavaFX entry point
    // -----------------------------------------------------------------------

    @Override
    public void start(Stage stage) {
        stage.setTitle("Smart Meter Monitor");

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab monitorTab = new Tab("Monitor");
        monitorTab.setContent(createMonitorTabContent());

        ChartsAnalyticsTab charts = new ChartsAnalyticsTab(query, this::resolveSerial);
        Tab chartsTab = charts.build();

        EnergyOverviewTab overview = new EnergyOverviewTab(query, this::resolveSerial);
        Tab overviewTab = overview.build();

        tabPane.getTabs().addAll(monitorTab, chartsTab, overviewTab);

        Scene scene = new Scene(tabPane, 1200, 900);
        stage.setScene(scene);
        stage.show();
        refreshSerialPorts();
    }

    // -------------------------------------------------------------------------
    // TAB 1: MONITOR
    // -------------------------------------------------------------------------

    private VBox createMonitorTabContent() {
        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #FFFFFF;");

        root.getChildren().addAll(
                createHeader(),
                createModeSelector(),
                createConnectionPanel(),
                createOpticalSettingsRow(),
                createStatisticsGrid(),
                createMessagePanels()
        );

        VBox.setVgrow(root.getChildren().get(root.getChildren().size() - 1), Priority.ALWAYS);
        return root;
    }

    // -----------------------------------------------------------------------
    // MODE SELECTOR
    // -----------------------------------------------------------------------

    private HBox createModeSelector() {
        HBox row = new HBox(0);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(0, 0, 0, 0));

        Label modeLabel = new Label("Connection mode:");
        modeLabel.setFont(Font.font("System", FontWeight.MEDIUM, 13));
        modeLabel.setTextFill(Color.web("#334155"));
        modeLabel.setPadding(new Insets(0, 12, 0, 0));

        ToggleGroup group = new ToggleGroup();

        modeRs485Btn = new ToggleButton("RS-485 Push");
        modeRs485Btn.setToggleGroup(group);
        modeRs485Btn.setSelected(true);
        styleToggle(modeRs485Btn, true, "left");

        modeOpticalBtn = new ToggleButton("Optical Poll");
        modeOpticalBtn.setToggleGroup(group);
        styleToggle(modeOpticalBtn, false, "right");

        // Prevent deselection
        group.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) {
                oldVal.setSelected(true);
                return;
            }
            boolean isOptical = (newVal == modeOpticalBtn);
            currentMode = isOptical ? ConnectionMode.OPTICAL_POLL : ConnectionMode.RS485_PUSH;
            styleToggle(modeRs485Btn, !isOptical, "left");
            styleToggle(modeOpticalBtn, isOptical, "right");
            opticalSettingsRow.setVisible(isOptical);
            opticalSettingsRow.setManaged(isOptical);
            updateManufacturerVisibility();
        });

        row.getChildren().addAll(modeLabel, modeRs485Btn, modeOpticalBtn);
        return row;
    }

    private void styleToggle(ToggleButton btn, boolean selected, String side) {
        String radius = side.equals("left")
                ? "-fx-background-radius: 6 0 0 6; -fx-border-radius: 6 0 0 6;"
                : "-fx-background-radius: 0 6 6 0; -fx-border-radius: 0 6 6 0;";

        if (selected) {
            btn.setStyle(
                    "-fx-background-color: #1E40AF; -fx-text-fill: white; " +
                            "-fx-font-weight: bold; -fx-font-size: 13; -fx-padding: 8 20; " +
                            "-fx-border-color: #1E40AF; " + radius);
        } else {
            btn.setStyle(
                    "-fx-background-color: #F1F5F9; -fx-text-fill: #64748B; " +
                            "-fx-font-weight: bold; -fx-font-size: 13; -fx-padding: 8 20; " +
                            "-fx-border-color: #CBD5E1; " + radius);
        }
    }

    // -----------------------------------------------------------------------
    // OPTICAL SETTINGS ROW (client/server address)
    // -----------------------------------------------------------------------

    private HBox createOpticalSettingsRow() {
        opticalSettingsRow = new HBox(12);
        opticalSettingsRow.setAlignment(Pos.CENTER_LEFT);
        opticalSettingsRow.setPadding(new Insets(6, 15, 6, 15));
        opticalSettingsRow.setStyle(
                "-fx-background-color: #FFF7ED; -fx-background-radius: 8; " +
                        "-fx-border-color: #FED7AA; -fx-border-radius: 8;");
        opticalSettingsRow.setVisible(false);
        opticalSettingsRow.setManaged(false);

        Label clientLabel = new Label("Client address:");
        clientLabel.setFont(Font.font("System", FontWeight.MEDIUM, 13));
        clientLabel.setTextFill(Color.web("#92400E"));

        clientAddrField = new TextField("16");
        clientAddrField.setPrefWidth(70);
        clientAddrField.setStyle("-fx-background-color: white; -fx-border-color: #FED7AA; -fx-border-radius: 4;");

        Label serverLabel = new Label("Server address:");
        serverLabel.setFont(Font.font("System", FontWeight.MEDIUM, 13));
        serverLabel.setTextFill(Color.web("#92400E"));

        serverAddrField = new TextField("1");
        serverAddrField.setPrefWidth(70);
        serverAddrField.setStyle("-fx-background-color: white; -fx-border-color: #FED7AA; -fx-border-radius: 4;");

        Label hint = new Label("IEC 62056-21 Mode E → DLMS/HDLC  |  Polls every 60 s");
        hint.setFont(Font.font("System", 11));
        hint.setTextFill(Color.web("#B45309"));

        opticalSettingsRow.getChildren().addAll(
                clientLabel, clientAddrField, serverLabel, serverAddrField, hint);
        return opticalSettingsRow;
    }

    // -----------------------------------------------------------------------
    // HEADER
    // -----------------------------------------------------------------------

    private HBox createHeader() {
        HBox header = new HBox(15);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 15, 10, 15));
        header.setStyle(
                "-fx-background-color: #F8FAFC; -fx-background-radius: 8; " +
                        "-fx-border-color: #E2E8F0; -fx-border-radius: 8;");

        Label title = new Label("⚡ Smart Meter Monitor");
        title.setFont(Font.font("System", FontWeight.BOLD, 22));
        title.setTextFill(Color.web("#1E40AF"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        statusDot = new Circle(8, Color.web("#EF4444"));
        statusLabel = new Label("Disconnected");
        statusLabel.setFont(Font.font("System", FontWeight.MEDIUM, 14));
        statusLabel.setTextFill(Color.web("#64748B"));

        lastUpdateLabel = new Label("");
        lastUpdateLabel.setFont(Font.font("System", 12));
        lastUpdateLabel.setTextFill(Color.web("#94A3B8"));

        header.getChildren().addAll(title, spacer, statusDot, statusLabel, lastUpdateLabel);
        return header;
    }

    // -----------------------------------------------------------------------
    // CONNECTION PANEL
    // -----------------------------------------------------------------------

    private HBox createConnectionPanel() {
        HBox panel = new HBox(15);
        panel.setAlignment(Pos.CENTER_LEFT);
        panel.setPadding(new Insets(12, 15, 12, 15));
        panel.setStyle("-fx-background-color: #F1F5F9; -fx-background-radius: 8; -fx-border-color: #CBD5E1; -fx-border-radius: 8;");

        Label mfgLabel = new Label("Manufacturer:");
        mfgLabel.setFont(Font.font("System", FontWeight.MEDIUM, 13));
        mfgLabel.setTextFill(Color.web("#334155"));

        manufacturerDropdown = new ComboBox<>();
        manufacturerDropdown.getItems().addAll("Meter & Control ST402D", "ZPA - AM175", "Sagemcom XT211", "Other");
        manufacturerDropdown.setValue("Meter & Control ST402D");
        manufacturerDropdown.setStyle("-fx-background-color: white;");
        manufacturerDropdown.setPrefWidth(200);

        Label portLabel = new Label("Serial Port:");
        portLabel.setFont(Font.font("System", FontWeight.MEDIUM, 13));
        portLabel.setTextFill(Color.web("#334155"));

        portDropdown = new ComboBox<>();
        portDropdown.setStyle("-fx-background-color: white;");
        portDropdown.setPrefWidth(130);

        connectBtn = new Button("CONNECT");
        connectBtn.setFont(Font.font("System", FontWeight.BOLD, 13));
        connectBtn.setStyle("-fx-background-color: #2563EB; -fx-text-fill: white; -fx-background-radius: 6; -fx-padding: 8 25;");
        connectBtn.setOnAction(e -> toggleConnection());

        refreshPortsBtn = new Button("REFRESH");
        refreshPortsBtn.setFont(Font.font("System", FontWeight.BOLD, 12));
        refreshPortsBtn.setStyle("-fx-background-color: #0F172A; -fx-text-fill: white; -fx-background-radius: 6; -fx-padding: 8 14;");
        refreshPortsBtn.setOnAction(e -> refreshSerialPorts());

        panel.getChildren().addAll(mfgLabel, manufacturerDropdown, portLabel, portDropdown, connectBtn, refreshPortsBtn);
        return panel;
    }

    private void updateManufacturerVisibility() {
        boolean show = (currentMode == ConnectionMode.RS485_PUSH);
        if (manufacturerDropdown != null) {
            manufacturerDropdown.setVisible(show);
            manufacturerDropdown.setManaged(show);
        }
    }

    private void refreshSerialPorts() {
        if (portDropdown == null) return;

        portDropdown.getItems().clear();

        try {
            String[] ports = jssc.SerialPortList.getPortNames();

            if (ports != null && ports.length > 0) {

                portDropdown.setDisable(false);
                portDropdown.setStyle("-fx-background-color: white;");

                if (connectBtn != null) {
                    connectBtn.setDisable(false);
                }

                for (String port : ports) {
                    portDropdown.getItems().add(port);
                }

                portDropdown.setValue(portDropdown.getItems().get(0));

            } else {
                showNoPortsState();
            }

        } catch (Exception ex) {
            showNoPortsState();
        }
    }

    private void showNoPortsState() {
        if (portDropdown == null) return;

        portDropdown.getItems().clear();
        portDropdown.setValue(null);
        portDropdown.setPromptText("No ports found");

        portDropdown.setStyle("""
        -fx-background-color: #FEF2F2;
        -fx-border-color: #DC2626;
        -fx-border-width: 2;
        -fx-border-radius: 4;
        """);

        portDropdown.setDisable(true);

        if (connectBtn != null) {
            connectBtn.setDisable(true);
        }
    }

    // -----------------------------------------------------------------------
    // STATISTICS GRID
    // -----------------------------------------------------------------------

    private GridPane createStatisticsGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(10);
        grid.setPadding(new Insets(15));
        grid.setStyle("-fx-background-color: #FAFAFA; -fx-background-radius: 8; -fx-border-color: #E5E7EB; -fx-border-radius: 8;");

        VBox powerBox = createStatCard("⚡ POWER", "#059669");
        powerImportValue = createValueLabel("0 W", "#059669");
        powerExportValue = createValueLabel("0 W", "#DC2626");
        powerBox.getChildren().addAll(
                createStatRow("Import (+A):", powerImportValue),
                createStatRow("Export (-A):", powerExportValue)
        );

        VBox energyBox = createStatCard("📊 ENERGY", "#7C3AED");
        energyImportValue = createValueLabel("0 Wh", "#059669");
        energyExportValue = createValueLabel("0 Wh", "#DC2626");
        energyBox.getChildren().addAll(
                createStatRow("Import:", energyImportValue),
                createStatRow("Export:", energyExportValue)
        );

        VBox phaseBox = createStatCard("🔌 POWER/PHASE", "#0891B2");
        powerL1Value = createValueLabel("0 W", "#0891B2");
        powerL2Value = createValueLabel("0 W", "#0891B2");
        powerL3Value = createValueLabel("0 W", "#0891B2");
        phaseBox.getChildren().addAll(
                createStatRow("L1:", powerL1Value),
                createStatRow("L2:", powerL2Value),
                createStatRow("L3:", powerL3Value)
        );

        VBox exportBox = createStatCard("📤 EXPORT/PHASE", "#D97706");
        exportL1Value = createValueLabel("0 W", "#D97706");
        exportL2Value = createValueLabel("0 W", "#D97706");
        exportL3Value = createValueLabel("0 W", "#D97706");
        exportBox.getChildren().addAll(
                createStatRow("L1:", exportL1Value),
                createStatRow("L2:", exportL2Value),
                createStatRow("L3:", exportL3Value)
        );

        grid.add(powerBox, 0, 0);
        grid.add(energyBox, 1, 0);
        grid.add(phaseBox, 2, 0);
        grid.add(exportBox, 3, 0);

        for (int i = 0; i < 4; i++) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPercentWidth(25);
            grid.getColumnConstraints().add(col);
        }

        return grid;
    }

    private VBox createStatCard(String title, String color) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(12));
        card.setStyle("-fx-background-color: white; -fx-background-radius: 6; -fx-border-color: #E5E7EB; -fx-border-radius: 6;");

        Label titleLabel = new Label(title);
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
        titleLabel.setTextFill(Color.web(color));

        card.getChildren().add(titleLabel);
        return card;
    }

    private HBox createStatRow(String label, Label valueLabel) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);

        Label nameLabel = new Label(label);
        nameLabel.setFont(Font.font("System", 12));
        nameLabel.setTextFill(Color.web("#6B7280"));
        nameLabel.setMinWidth(70);

        row.getChildren().addAll(nameLabel, valueLabel);
        return row;
    }

    private Label createValueLabel(String text, String color) {
        Label label = new Label(text);
        label.setFont(Font.font("Consolas", FontWeight.BOLD, 14));
        label.setTextFill(Color.web(color));
        return label;
    }

    // -----------------------------------------------------------------------
    // MESSAGE PANELS
    // -----------------------------------------------------------------------

    private HBox createMessagePanels() {
        HBox panels = new HBox(15);

        VBox hexPanel = new VBox(8);
        hexPanel.setPadding(new Insets(12));
        hexPanel.setStyle("-fx-background-color: #F0FDF4; -fx-background-radius: 8; -fx-border-color: #86EFAC; -fx-border-radius: 8;");
        HBox.setHgrow(hexPanel, Priority.ALWAYS);

        Label hexTitle = new Label("📡 RAW HEX MESSAGE");
        hexTitle.setFont(Font.font("System", FontWeight.BOLD, 12));
        hexTitle.setTextFill(Color.web("#166534"));

        hexArea = new TextArea();
        hexArea.setEditable(false);
        hexArea.setWrapText(false);
        hexArea.setFont(Font.font("Consolas", 11));
        hexArea.setStyle("-fx-control-inner-background: white; -fx-text-fill: #166534;");
        hexArea.setPromptText("Waiting for meter data...");
        VBox.setVgrow(hexArea, Priority.ALWAYS);

        hexPanel.getChildren().addAll(hexTitle, hexArea);

        // Right: translated data
        VBox translatedPanel = new VBox(8);
        translatedPanel.setPadding(new Insets(12));
        translatedPanel.setStyle("-fx-background-color: #EFF6FF; -fx-background-radius: 8; -fx-border-color: #93C5FD; -fx-border-radius: 8;");
        HBox.setHgrow(translatedPanel, Priority.ALWAYS);

        Label translatedTitle = new Label("✓ TRANSLATED DATA");
        translatedTitle.setFont(Font.font("System", FontWeight.BOLD, 12));
        translatedTitle.setTextFill(Color.web("#1E40AF"));

        translatedArea = new TextArea();
        translatedArea.setEditable(false);
        translatedArea.setWrapText(false);
        translatedArea.setFont(Font.font("Consolas", 11));
        translatedArea.setStyle("-fx-control-inner-background: white; -fx-text-fill: #1E3A8A;");
        translatedArea.setPromptText("Parsed meter readings will appear here...");
        VBox.setVgrow(translatedArea, Priority.ALWAYS);

        translatedPanel.getChildren().addAll(translatedTitle, translatedArea);

        panels.getChildren().addAll(hexPanel, translatedPanel);
        return panels;
    }

    // -----------------------------------------------------------------------
    // CONNECTION LOGIC — dispatches to RS-485 or Optical
    // -----------------------------------------------------------------------

    private void toggleConnection() {
        if (!isConnected) connect();
        else disconnect();
    }

    private void connect() {
        String port = portDropdown.getValue();
        if (port == null || port.isBlank()) {
            showAlert("No Serial Port Selected", "Please select an available serial port.");
            return;
        }

        updateStatus("Connecting...", "#F59E0B");
        lockUI(true);

        if (currentMode == ConnectionMode.RS485_PUSH) {
            connectRs485(port);
        } else {
            connectOptical(port);
        }
    }

    private void connectRs485(String port) {
        new Thread(() -> {
            try {
                pushListener = new GXDLMSPushListener(port, this::onDataReceived, this.database);
                Platform.runLater(() -> onConnected("RS-485 Push — waiting for data…"));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    updateStatus("Error: " + e.getMessage(), "#EF4444");
                    lockUI(false);
                });
            }
        }).start();
    }

    private void connectOptical(String port) {
        int clientAddr, serverAddr;
        try {
            clientAddr = Integer.parseInt(clientAddrField.getText().trim());
            serverAddr = Integer.parseInt(serverAddrField.getText().trim());
        } catch (NumberFormatException ex) {
            showAlert("Invalid Address", "Client and server addresses must be integers.");
            lockUI(false);
            return;
        }

        new Thread(() -> {
            try {
                opticalListener = new OpticalPollListener(
                        port, clientAddr, serverAddr,
                        this::onDataReceived,
                        this.database,
                        msg -> Platform.runLater(() -> appendHexLog("[LOG] " + msg))
                );
                opticalListener.start();
                Platform.runLater(() -> onConnected("Optical Poll — polling every 60 s…"));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    updateStatus("Optical error: " + e.getMessage(), "#EF4444");
                    lockUI(false);
                });
            }
        }).start();
    }

    private void onConnected(String statusMsg) {
        isConnected = true;
        connectBtn.setText("DISCONNECT");
        connectBtn.setStyle(
                "-fx-background-color: #DC2626; -fx-text-fill: white; " +
                        "-fx-background-radius: 6; -fx-padding: 8 25;");
        modeRs485Btn.setDisable(true);
        modeOpticalBtn.setDisable(true);
        updateStatus(statusMsg, "#22C55E");
    }

    private void disconnect() {
        if (pushListener != null) {
            pushListener.close(); pushListener = null;
        }
        if (opticalListener != null) {
            opticalListener.close(); opticalListener = null;
        }

        isConnected = false;
        connectBtn.setText("CONNECT");
        connectBtn.setStyle(
                "-fx-background-color: #2563EB; -fx-text-fill: white; " +
                        "-fx-background-radius: 6; -fx-padding: 8 25;");
        modeRs485Btn.setDisable(false);
        modeOpticalBtn.setDisable(false);
        lockUI(false);
        updateStatus("Disconnected", "#EF4444");
    }

    private void lockUI(boolean connected) {
        manufacturerDropdown.setDisable(connected);
        portDropdown.setDisable(connected);
        refreshPortsBtn.setDisable(connected);
        clientAddrField.setDisable(connected);
        serverAddrField.setDisable(connected);
    }

    // -----------------------------------------------------------------------
    // DATA RECEIVED (identical for both modes)
    // -----------------------------------------------------------------------

    private void onDataReceived(String pushTs, String hexData, List<?> values, String xmlData) {
        Platform.runLater(() -> {
            lastUpdateLabel.setText("Last update: " + pushTs);
            updateStatus("Data received!", "#22C55E");

            hexArea.setText(formatHex(hexData));

            List<Reading> readings = EGDObisParser.parseValues(values);

            // Skip DB insert if any reading has a null value
            boolean hasNulls = false;
            for (Reading r : readings) {
                if (r.value == null) {
                    hasNulls = true;
                    break;
                }
            }

            if (hasNulls) {
                System.out.println("[DB] Skipping insert — one or more readings are null.");
            } else {
                try {
                    database.insertPush(pushTs, readings);
                    System.out.println("[DB] Push stored successfully.");
                } catch (Exception ex) {
                    System.err.println("[DB ERROR] " + ex.getMessage());
                }
            }

            if (readings.size() >= 3) {
                lastSerial = String.valueOf(readings.get(2).value);
            }

            translatedArea.setText(buildTranslatedTable(readings));
            updateStatistics(readings);
        });
    }

    // -----------------------------------------------------------------------
    // STATISTICS UPDATE
    // -----------------------------------------------------------------------

    private void updateStatistics(List<Reading> readings) {
        Map<String, Object> power = EGDObisParser.getPowerReadings(readings);
        Map<String, Object> energy = EGDObisParser.getEnergyReadings(readings);

        if (power.containsKey("powerImport")) powerImportValue.setText(power.get("powerImport") + " W");
        if (power.containsKey("powerExport")) powerExportValue.setText(power.get("powerExport") + " W");
        if (power.containsKey("powerL1")) powerL1Value.setText(power.get("powerL1") + " W");
        if (power.containsKey("powerL2")) powerL2Value.setText(power.get("powerL2") + " W");
        if (power.containsKey("powerL3")) powerL3Value.setText(power.get("powerL3") + " W");
        if (power.containsKey("exportL1")) exportL1Value.setText(power.get("exportL1") + " W");
        if (power.containsKey("exportL2")) exportL2Value.setText(power.get("exportL2") + " W");
        if (power.containsKey("exportL3")) exportL3Value.setText(power.get("exportL3") + " W");

        if (energy.containsKey("energyImport")) energyImportValue.setText(energy.get("energyImport") + " Wh");
        if (energy.containsKey("energyExport")) energyExportValue.setText(energy.get("energyExport") + " Wh");
    }

    // -----------------------------------------------------------------------
    // FORMATTING HELPERS
    // -----------------------------------------------------------------------

    private String formatHex(String hex) {
        if (hex == null || hex.isBlank()) return "";
        String cleaned = hex.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        StringBuilder sb = new StringBuilder();
        int byteCount = 0;
        for (int i = 0; i < cleaned.length(); i += 2) {
            int end = Math.min(i + 2, cleaned.length());
            sb.append(cleaned, i, end);
            if (end < cleaned.length()) sb.append(" ");
            byteCount++;
            if (byteCount % 16 == 0 && end < cleaned.length()) sb.append("\n");
        }
        return sb.toString();
    }

    private void appendHexLog(String line) {
        if (hexArea != null) {
            hexArea.appendText(line + "\n");
        }
    }

    private String buildTranslatedTable(List<Reading> readings) {
        int idxW = 5, paramW = 42, valW = 18, unitW = 10;
        String hr = "+" + "-".repeat(idxW+2) + "+" + "-".repeat(paramW+2) + "+"
                + "-".repeat(valW+2) + "+" + "-".repeat(unitW+2) + "+\n";
        StringBuilder sb = new StringBuilder();
        sb.append(hr);
        sb.append(row("Idx", "Parameter", "Value", "Unit", idxW, paramW, valW, unitW));
        sb.append(hr);
        for (Reading r : readings) {
            sb.append(row(
                    String.valueOf(r.index),
                    clip(String.valueOf(r.name), paramW),
                    clip(String.valueOf(r.value), valW),
                    clip(r.unit == null ? "" : r.unit, unitW),
                    idxW, paramW, valW, unitW));
        }
        sb.append(hr);
        return sb.toString();
    }

    private String row(String a, String b, String c, String d,
                       int aw, int bw, int cw, int dw) {
        return String.format("| %-"+aw+"s | %-"+bw+"s | %-"+cw+"s | %-"+dw+"s |\n", a, b, c, d);
    }

    private String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max-3)) + "...";
    }

    private void updateStatus(String text, String color) {
        Platform.runLater(() -> {
            statusLabel.setText(text);
            statusDot.setFill(Color.web(color));
        });
    }

    private void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private String resolveSerial(String typed) {
        if (typed != null && !typed.isBlank()) return typed.trim();
        if (lastSerial != null && !lastSerial.isBlank()) return lastSerial;
        return "0001410695";
    }

    @Override
    public void stop() {
        if (pushListener != null) pushListener.close();
        if (opticalListener != null) opticalListener.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}