package com.zconnect;

import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * Z Connect Linux - System tray PdaNet client
 *
 * Behavior:
 * - Runs in system tray only (no window)
 * - Auto-connects when PdaNet network detected
 * - Exit from tray menu cleanly disconnects
 * - Starts at boot via autostart
 */
public class ZConnectApp {

    private ConnectionManager connectionManager;
    private NetworkScanner networkScanner;
    private ScheduledExecutorService scheduler;
    private volatile boolean autoConnectInProgress = false;
    private volatile String lastSeenNetwork = null;
    private long connectionStartTime = 0;

    private TrayIcon trayIcon;
    private MenuItem statusItem;
    private MenuItem networkItem;
    private MenuItem uptimeItem;

    public static void main(String[] args) {
        // Check system tray support
        if (!SystemTray.isSupported()) {
            System.err.println("System tray not supported");
            System.exit(1);
        }

        new ZConnectApp().start();
    }

    private void start() {
        connectionManager = new ConnectionManager(this::log);
        networkScanner = new NetworkScanner();
        scheduler = Executors.newScheduledThreadPool(2);

        setupTray();
        log("Z Connect started - scanning for networks...");

        startNetworkMonitoring();
        startStatusUpdates();
    }

    private void setupTray() {
        try {
            SystemTray tray = SystemTray.getSystemTray();

            // Create icon (simple colored circle)
            Image icon = createTrayIcon(Color.GRAY);

            // Popup menu
            PopupMenu popup = new PopupMenu();

            statusItem = new MenuItem("Scanning...");
            statusItem.setEnabled(false);
            popup.add(statusItem);

            networkItem = new MenuItem("Network: --");
            networkItem.setEnabled(false);
            popup.add(networkItem);

            uptimeItem = new MenuItem("Uptime: --");
            uptimeItem.setEnabled(false);
            popup.add(uptimeItem);

            popup.addSeparator();

            MenuItem exitItem = new MenuItem("Exit");
            exitItem.addActionListener(e -> shutdown());
            popup.add(exitItem);

            trayIcon = new TrayIcon(icon, "Z Connect", popup);
            trayIcon.setImageAutoSize(true);

            tray.add(trayIcon);
        } catch (Exception e) {
            System.err.println("Failed to setup tray: " + e.getMessage());
            System.exit(1);
        }
    }

    private Image createTrayIcon(Color color) {
        int size = 16;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
            size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(color);
        g.fillOval(2, 2, size - 4, size - 4);
        g.dispose();
        return img;
    }

    private void updateTrayIcon(Color color) {
        if (trayIcon != null) {
            trayIcon.setImage(createTrayIcon(color));
        }
    }

    private void startNetworkMonitoring() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                List<String> networks = networkScanner.scanForPdaNetNetworks();

                if (connectionManager.isConnected()) {
                    String connectedNet = connectionManager.getConnectedNetwork();
                    boolean stillAvailable = networks.stream()
                        .anyMatch(n -> n.contains(connectedNet) || connectedNet.contains(extractSsid(n)));

                    if (!stillAvailable) {
                        log("Network disappeared - disconnecting...");
                        disconnect();
                    }
                } else if (!networks.isEmpty() && !autoConnectInProgress) {
                    String network = networks.get(0);
                    if (!network.equals(lastSeenNetwork)) {
                        lastSeenNetwork = network;
                        log("Found: " + extractSsid(network));
                        autoConnect(network);
                    }
                } else if (networks.isEmpty()) {
                    lastSeenNetwork = null;
                    updateStatus("No PdaNet networks", Color.ORANGE);
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
        updateStatus("Connecting...", Color.YELLOW);

        CompletableFuture.runAsync(() -> {
            boolean success = connectionManager.connect(network);
            autoConnectInProgress = false;
            if (success) {
                connectionStartTime = System.currentTimeMillis();
                log("Connected!");
            } else {
                log("Connection failed - will retry");
                lastSeenNetwork = null;
            }
        });
    }

    private void disconnect() {
        if (!connectionManager.isConnected()) {
            return;
        }

        log("Disconnecting...");
        CompletableFuture.runAsync(() -> {
            connectionManager.disconnect();
            connectionStartTime = 0;
            lastSeenNetwork = null;
            log("Disconnected");
        });
    }

    private void startStatusUpdates() {
        scheduler.scheduleAtFixedRate(() -> {
            if (connectionManager.isConnected()) {
                updateStatus("Connected", Color.GREEN);
                networkItem.setLabel("Network: " + connectionManager.getConnectedNetwork());

                if (connectionStartTime > 0) {
                    long seconds = (System.currentTimeMillis() - connectionStartTime) / 1000;
                    uptimeItem.setLabel("Uptime: " + formatUptime(seconds));
                }

                trayIcon.setToolTip("Z Connect - Connected to " + connectionManager.getConnectedNetwork());
            } else if (!autoConnectInProgress) {
                if (!"Connecting...".equals(statusItem.getLabel()) &&
                    !"No PdaNet networks".equals(statusItem.getLabel())) {
                    updateStatus("Disconnected", Color.RED);
                }
                networkItem.setLabel("Network: --");
                uptimeItem.setLabel("Uptime: --");
                trayIcon.setToolTip("Z Connect - Disconnected");
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void updateStatus(String status, Color color) {
        statusItem.setLabel(status);
        updateTrayIcon(color);
    }

    private String formatUptime(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }

    private void log(String message) {
        String timestamp = java.time.LocalTime.now().toString().substring(0, 8);
        System.out.println("[" + timestamp + "] " + message);
    }

    private void shutdown() {
        log("Shutting down - disconnecting...");
        if (connectionManager.isConnected()) {
            connectionManager.disconnect();
        }
        scheduler.shutdownNow();
        SystemTray.getSystemTray().remove(trayIcon);
        System.exit(0);
    }
}
