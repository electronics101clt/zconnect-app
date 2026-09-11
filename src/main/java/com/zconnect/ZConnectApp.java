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

/**
 * Z Connect Linux - Auto-connecting PdaNet client
 *
 * Behavior (matches ZLauncher):
 * - Auto-connects when PdaNet network detected
 * - Auto-disconnects when window closed
 * - No password prompts (uses sudoers)
 */
public class ZConnectApp extends Application {

    private ConnectionManager connectionManager;
    private NetworkScanner networkScanner;
    private ScheduledExecutorService scheduler;
    private volatile boolean autoConnectInProgress = false;
    private volatile String lastSeenNetwork = null;

    // UI Components
    private Circle statusIndicator;
    private Label statusLabel;
    private Label networkLabel;
    private Label ipLabel;
    private Label uptimeLabel;
    private TextArea logArea;
    private ProgressBar signalBar;
    private CheckBox autoConnectCheckbox;

    private long connectionStartTime = 0;

    @Override
    public void start(Stage primaryStage) {
        connectionManager = new ConnectionManager(this::log);
        networkScanner = new NetworkScanner();
        scheduler = Executors.newScheduledThreadPool(2);

        primaryStage.setTitle("Z Connect");
        primaryStage.setScene(new Scene(createDashboard(), 450, 500));
        primaryStage.setResizable(false);

        // Auto-disconnect on window close
        primaryStage.setOnCloseRequest(e -> {
            shutdown();
        });

        primaryStage.show();

        log("Z Connect started - scanning for networks...");

        // Start background tasks
        startNetworkMonitoring();
        startStatusUpdates();
    }

    private VBox createDashboard() {
        VBox root = new VBox(12);
        root.setPadding(new Insets(15));
        root.setStyle("-fx-background-color: #1a1a2e;");

        // Header
        Label title = new Label("Z Connect");
        title.setFont(Font.font("System", FontWeight.BOLD, 24));
        title.setTextFill(Color.WHITE);

        Label subtitle = new Label("PdaNet WiFi Direct Tethering");
        subtitle.setFont(Font.font("System", 11));
        subtitle.setTextFill(Color.GRAY);

        VBox header = new VBox(3, title, subtitle);
        header.setAlignment(Pos.CENTER);

        // Status Card
        VBox statusCard = createStatusCard();

        // Auto-connect toggle
        autoConnectCheckbox = new CheckBox("Auto-connect when network detected");
        autoConnectCheckbox.setSelected(true);
        autoConnectCheckbox.setTextFill(Color.WHITE);
        autoConnectCheckbox.setStyle("-fx-font-size: 12;");

        // Log Area
        VBox logCard = createLogCard();

        // Manual disconnect button (for emergency)
        Button forceDisconnect = new Button("Force Disconnect");
        forceDisconnect.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
        forceDisconnect.setOnAction(e -> forceDisconnect());

        root.getChildren().addAll(header, statusCard, autoConnectCheckbox, logCard, forceDisconnect);
        return root;
    }

    private VBox createStatusCard() {
        VBox card = new VBox(8);
        card.setPadding(new Insets(12));
        card.setStyle("-fx-background-color: #16213e; -fx-background-radius: 10;");

        // Status row
        HBox statusRow = new HBox(8);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        statusIndicator = new Circle(8);
        statusIndicator.setFill(Color.RED);

        statusLabel = new Label("Scanning...");
        statusLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        statusLabel.setTextFill(Color.WHITE);

        statusRow.getChildren().addAll(statusIndicator, statusLabel);

        // Details grid
        GridPane details = new GridPane();
        details.setHgap(15);
        details.setVgap(5);

        Label netTitle = new Label("Network:");
        netTitle.setTextFill(Color.GRAY);
        networkLabel = new Label("--");
        networkLabel.setTextFill(Color.WHITE);

        Label ipTitle = new Label("IP:");
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
        HBox signalRow = new HBox(8);
        signalRow.setAlignment(Pos.CENTER_LEFT);
        Label signalTitle = new Label("Signal:");
        signalTitle.setTextFill(Color.GRAY);
        signalBar = new ProgressBar(0);
        signalBar.setPrefWidth(120);
        signalBar.setStyle("-fx-accent: #4ecca3;");
        signalRow.getChildren().addAll(signalTitle, signalBar);

        card.getChildren().addAll(statusRow, new Separator(), details, signalRow);
        return card;
    }

    private VBox createLogCard() {
        VBox card = new VBox(8);
        card.setPadding(new Insets(12));
        card.setStyle("-fx-background-color: #16213e; -fx-background-radius: 10;");

        Label title = new Label("Activity");
        title.setFont(Font.font("System", FontWeight.BOLD, 12));
        title.setTextFill(Color.WHITE);

        logArea = new TextArea();
        logArea.setPrefHeight(100);
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setStyle("-fx-control-inner-background: #1a1a2e; -fx-text-fill: #4ecca3; -fx-font-family: monospace; -fx-font-size: 10;");

        card.getChildren().addAll(title, logArea);
        return card;
    }

    /**
     * Monitor for PdaNet networks and auto-connect/disconnect
     */
    private void startNetworkMonitoring() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                List<String> networks = networkScanner.scanForPdaNetNetworks();

                if (connectionManager.isConnected()) {
                    // Check if our network is still available
                    String connectedNet = connectionManager.getConnectedNetwork();
                    boolean stillAvailable = networks.stream()
                        .anyMatch(n -> n.contains(connectedNet) || connectedNet.contains(extractSsid(n)));

                    if (!stillAvailable) {
                        log("Network disappeared - disconnecting...");
                        Platform.runLater(() -> disconnect());
                    }
                } else if (!networks.isEmpty() && autoConnectCheckbox.isSelected() && !autoConnectInProgress) {
                    // Auto-connect to first available network
                    String network = networks.get(0);
                    if (!network.equals(lastSeenNetwork)) {
                        lastSeenNetwork = network;
                        log("Found: " + extractSsid(network));
                        Platform.runLater(() -> autoConnect(network));
                    }
                } else if (networks.isEmpty()) {
                    lastSeenNetwork = null;
                    Platform.runLater(() -> {
                        statusLabel.setText("No PdaNet networks");
                        statusIndicator.setFill(Color.ORANGE);
                    });
                }
            } catch (Exception e) {
                // Ignore scan errors
            }
        }, 0, 3, TimeUnit.SECONDS);
    }

    private String extractSsid(String networkDisplay) {
        return networkDisplay.replaceAll("\\s*\\(\\d+%\\)$", "")
                            .replaceAll("\\s*\\(saved\\)$", "")
                            .trim();
    }

    private void autoConnect(String network) {
        if (autoConnectInProgress || connectionManager.isConnected()) {
            return;
        }

        autoConnectInProgress = true;
        String ssid = extractSsid(network);
        log("Auto-connecting to " + ssid + "...");
        statusLabel.setText("Connecting...");
        statusIndicator.setFill(Color.YELLOW);

        CompletableFuture.runAsync(() -> {
            boolean success = connectionManager.connect(network);
            Platform.runLater(() -> {
                autoConnectInProgress = false;
                if (success) {
                    connectionStartTime = System.currentTimeMillis();
                    log("Connected!");
                } else {
                    log("Connection failed - will retry");
                    lastSeenNetwork = null; // Allow retry
                }
            });
        });
    }

    private void disconnect() {
        if (!connectionManager.isConnected()) {
            return;
        }

        log("Disconnecting...");
        CompletableFuture.runAsync(() -> {
            connectionManager.disconnect();
            Platform.runLater(() -> {
                connectionStartTime = 0;
                lastSeenNetwork = null;
                log("Disconnected");
            });
        });
    }

    private void forceDisconnect() {
        log("Force disconnect...");
        autoConnectInProgress = false;
        CompletableFuture.runAsync(() -> {
            connectionManager.disconnect();
            Platform.runLater(() -> {
                connectionStartTime = 0;
                lastSeenNetwork = null;
                log("Force disconnected");
            });
        });
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

            networkLabel.setText(connectionManager.getConnectedNetwork());
            ipLabel.setText(connectionManager.getLocalIp());

            if (connectionStartTime > 0) {
                long seconds = (System.currentTimeMillis() - connectionStartTime) / 1000;
                uptimeLabel.setText(formatUptime(seconds));
            }

            double signal = connectionManager.getSignalStrength();
            signalBar.setProgress(signal);
        } else if (!autoConnectInProgress) {
            statusIndicator.setFill(Color.RED);
            if (!"Connecting...".equals(statusLabel.getText()) &&
                !"No PdaNet networks".equals(statusLabel.getText())) {
                statusLabel.setText("Disconnected");
            }

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

    private void log(String message) {
        Platform.runLater(() -> {
            String timestamp = java.time.LocalTime.now().toString().substring(0, 8);
            logArea.appendText("[" + timestamp + "] " + message + "\n");
            logArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    private void shutdown() {
        log("Shutting down - disconnecting...");
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
