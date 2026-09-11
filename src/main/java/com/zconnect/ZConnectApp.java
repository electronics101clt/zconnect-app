package com.zconnect;

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
import java.util.concurrent.*;

public class ZConnectApp extends Application {

    private ConnectionManager connectionManager;
    private NetworkScanner networkScanner;
    private ScheduledExecutorService scheduler;

    // UI Components
    private Circle statusIndicator;
    private Label statusLabel;
    private Label networkLabel;
    private Label ipLabel;
    private Label uptimeLabel;
    private Button connectButton;
    private Button disconnectButton;
    private ListView<String> networkList;
    private TextArea logArea;
    private ProgressBar signalBar;

    private long connectionStartTime = 0;

    @Override
    public void start(Stage primaryStage) {
        connectionManager = new ConnectionManager(this::log);
        networkScanner = new NetworkScanner();
        scheduler = Executors.newScheduledThreadPool(2);

        primaryStage.setTitle("Z Connect");
        primaryStage.setScene(new Scene(createDashboard(), 500, 600));
        primaryStage.setResizable(false);
        primaryStage.setOnCloseRequest(e -> shutdown());
        primaryStage.show();

        // Start background tasks
        startNetworkScanning();
        startStatusUpdates();
    }

    private VBox createDashboard() {
        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #1a1a2e;");

        // Header
        Label title = new Label("Z Connect");
        title.setFont(Font.font("System", FontWeight.BOLD, 28));
        title.setTextFill(Color.WHITE);

        Label subtitle = new Label("PdaNet WiFi Direct Tethering");
        subtitle.setFont(Font.font("System", 12));
        subtitle.setTextFill(Color.GRAY);

        VBox header = new VBox(5, title, subtitle);
        header.setAlignment(Pos.CENTER);

        // Status Card
        VBox statusCard = createStatusCard();

        // Network Selection Card
        VBox networkCard = createNetworkCard();

        // Control Buttons
        HBox controls = createControlButtons();

        // Log Area
        VBox logCard = createLogCard();

        root.getChildren().addAll(header, statusCard, networkCard, controls, logCard);
        return root;
    }

    private VBox createStatusCard() {
        VBox card = new VBox(10);
        card.setPadding(new Insets(15));
        card.setStyle("-fx-background-color: #16213e; -fx-background-radius: 10;");

        // Status row
        HBox statusRow = new HBox(10);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        statusIndicator = new Circle(8);
        statusIndicator.setFill(Color.RED);

        statusLabel = new Label("Disconnected");
        statusLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        statusLabel.setTextFill(Color.WHITE);

        statusRow.getChildren().addAll(statusIndicator, statusLabel);

        // Details grid
        GridPane details = new GridPane();
        details.setHgap(20);
        details.setVgap(8);

        Label netTitle = new Label("Network:");
        netTitle.setTextFill(Color.GRAY);
        networkLabel = new Label("--");
        networkLabel.setTextFill(Color.WHITE);

        Label ipTitle = new Label("IP Address:");
        ipTitle.setTextFill(Color.GRAY);
        ipLabel = new Label("--");
        ipLabel.setTextFill(Color.WHITE);

        Label upTitle = new Label("Uptime:");
        upTitle.setTextFill(Color.GRAY);
        uptimeLabel = new Label("--");
        uptimeLabel.setTextFill(Color.WHITE);

        details.add(netTitle, 0, 0);
        details.add(networkLabel, 1, 0);
        details.add(ipTitle, 0, 1);
        details.add(ipLabel, 1, 1);
        details.add(upTitle, 0, 2);
        details.add(uptimeLabel, 1, 2);

        // Signal strength
        HBox signalRow = new HBox(10);
        signalRow.setAlignment(Pos.CENTER_LEFT);
        Label signalTitle = new Label("Signal:");
        signalTitle.setTextFill(Color.GRAY);
        signalBar = new ProgressBar(0);
        signalBar.setPrefWidth(150);
        signalBar.setStyle("-fx-accent: #4ecca3;");
        signalRow.getChildren().addAll(signalTitle, signalBar);

        card.getChildren().addAll(statusRow, new Separator(), details, signalRow);
        return card;
    }

    private VBox createNetworkCard() {
        VBox card = new VBox(10);
        card.setPadding(new Insets(15));
        card.setStyle("-fx-background-color: #16213e; -fx-background-radius: 10;");

        Label title = new Label("Available PdaNet Networks");
        title.setFont(Font.font("System", FontWeight.BOLD, 14));
        title.setTextFill(Color.WHITE);

        networkList = new ListView<>();
        networkList.setPrefHeight(100);
        networkList.setStyle("-fx-background-color: #1a1a2e; -fx-control-inner-background: #1a1a2e;");
        networkList.setPlaceholder(new Label("Scanning for networks..."));

        Button refreshBtn = new Button("Refresh");
        refreshBtn.setOnAction(e -> scanNetworks());
        refreshBtn.setStyle("-fx-background-color: #4ecca3; -fx-text-fill: black;");

        card.getChildren().addAll(title, networkList, refreshBtn);
        return card;
    }

    private HBox createControlButtons() {
        HBox controls = new HBox(15);
        controls.setAlignment(Pos.CENTER);

        connectButton = new Button("Connect");
        connectButton.setPrefWidth(120);
        connectButton.setPrefHeight(40);
        connectButton.setFont(Font.font("System", FontWeight.BOLD, 14));
        connectButton.setStyle("-fx-background-color: #4ecca3; -fx-text-fill: black; -fx-background-radius: 20;");
        connectButton.setOnAction(e -> connect());

        disconnectButton = new Button("Disconnect");
        disconnectButton.setPrefWidth(120);
        disconnectButton.setPrefHeight(40);
        disconnectButton.setFont(Font.font("System", FontWeight.BOLD, 14));
        disconnectButton.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white; -fx-background-radius: 20;");
        disconnectButton.setOnAction(e -> disconnect());
        disconnectButton.setDisable(true);

        controls.getChildren().addAll(connectButton, disconnectButton);
        return controls;
    }

    private VBox createLogCard() {
        VBox card = new VBox(10);
        card.setPadding(new Insets(15));
        card.setStyle("-fx-background-color: #16213e; -fx-background-radius: 10;");

        Label title = new Label("Activity Log");
        title.setFont(Font.font("System", FontWeight.BOLD, 14));
        title.setTextFill(Color.WHITE);

        logArea = new TextArea();
        logArea.setPrefHeight(120);
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setStyle("-fx-control-inner-background: #1a1a2e; -fx-text-fill: #4ecca3; -fx-font-family: monospace;");

        card.getChildren().addAll(title, logArea);
        return card;
    }

    private void startNetworkScanning() {
        scheduler.scheduleAtFixedRate(() -> {
            if (!connectionManager.isConnected()) {
                scanNetworks();
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    private void scanNetworks() {
        CompletableFuture.supplyAsync(() -> networkScanner.scanForPdaNetNetworks())
            .thenAccept(networks -> Platform.runLater(() -> {
                networkList.getItems().clear();
                networkList.getItems().addAll(networks);
                if (!networks.isEmpty() && networkList.getSelectionModel().isEmpty()) {
                    networkList.getSelectionModel().selectFirst();
                }
            }));
    }

    private void startStatusUpdates() {
        scheduler.scheduleAtFixedRate(() -> {
            Platform.runLater(this::updateStatus);
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void updateStatus() {
        if (connectionManager.isConnected()) {
            statusIndicator.setFill(Color.LIMEGREEN);
            statusLabel.setText("Connected");
            connectButton.setDisable(true);
            disconnectButton.setDisable(false);

            networkLabel.setText(connectionManager.getConnectedNetwork());
            ipLabel.setText(connectionManager.getLocalIp());

            if (connectionStartTime > 0) {
                long seconds = (System.currentTimeMillis() - connectionStartTime) / 1000;
                uptimeLabel.setText(formatUptime(seconds));
            }

            double signal = connectionManager.getSignalStrength();
            signalBar.setProgress(signal);
        } else {
            statusIndicator.setFill(Color.RED);
            statusLabel.setText("Disconnected");
            connectButton.setDisable(false);
            disconnectButton.setDisable(true);

            networkLabel.setText("--");
            ipLabel.setText("--");
            uptimeLabel.setText("--");
            signalBar.setProgress(0);
        }
    }

    private String formatUptime(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }

    private void connect() {
        String selectedNetwork = networkList.getSelectionModel().getSelectedItem();
        if (selectedNetwork == null || selectedNetwork.isEmpty()) {
            log("ERROR: No network selected");
            showAlert("Please select a PdaNet network first");
            return;
        }

        connectButton.setDisable(true);
        log("Connecting to " + selectedNetwork + "...");

        CompletableFuture.runAsync(() -> {
            boolean success = connectionManager.connect(selectedNetwork);
            Platform.runLater(() -> {
                if (success) {
                    connectionStartTime = System.currentTimeMillis();
                    log("Connected successfully!");
                } else {
                    connectButton.setDisable(false);
                    log("Connection failed!");
                    showAlert("Failed to connect. Check logs for details.");
                }
            });
        });
    }

    private void disconnect() {
        disconnectButton.setDisable(true);
        log("Disconnecting...");

        CompletableFuture.runAsync(() -> {
            connectionManager.disconnect();
            Platform.runLater(() -> {
                connectionStartTime = 0;
                log("Disconnected");
                scanNetworks();
            });
        });
    }

    private void log(String message) {
        Platform.runLater(() -> {
            String timestamp = java.time.LocalTime.now().toString().substring(0, 8);
            logArea.appendText("[" + timestamp + "] " + message + "\n");
            logArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Z Connect");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void shutdown() {
        log("Shutting down...");
        if (connectionManager.isConnected()) {
            connectionManager.disconnect();
        }
        scheduler.shutdownNow();
        Platform.exit();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
