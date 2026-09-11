package com.zconnect;

import java.io.*;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Manages the full Z Connect connection lifecycle:
 * Manages the full Z Connect connection lifecycle:
 * 1. Connect to PdaNet WiFi Direct network (DIRECT-*-PdaNet)
 * 2. Create TUN interface (tun0)
 * 3. Configure routing (default via tun0)
 * 4. Start tun2socks bridge to HTTP CONNECT proxy at 192.168.49.1:8000
 * 5. Monitor connection and cleanup on disconnect
 *
 * Protocol: Android host runs HTTP CONNECT proxy (not SOCKS5)
 */
public class ConnectionManager {

    private static final String TUN_DEVICE = "tun0";
    private static final String TUN_IP = "10.1.10.1";  // Match Android VpnService
    private static final String PDANET_GATEWAY = "192.168.49.1";
    private static final int PROXY_PORT = 8000;  // HTTP CONNECT proxy (not SOCKS5)

    private final Consumer<String> logger;
    private final NetworkScanner networkScanner;

    private Process tun2socksProcess;
    private String connectedNetwork;
    private String originalDefaultRoute;
    private String wifiInterface;
    private boolean connected = false;

    public ConnectionManager(Consumer<String> logger) {
        this.logger = logger;
        this.networkScanner = new NetworkScanner();
    }

    /**
     * Connect to a PdaNet network and establish tunnel
     */
    public boolean connect(String networkDisplay) {
        try {
            // Extract SSID from display string (remove signal percentage)
            String ssid = networkDisplay.replaceAll("\\s*\\(\\d+%\\)$", "")
                                        .replaceAll("\\s*\\(saved\\)$", "")
                                        .trim();

            logger.accept("Target network: " + ssid);

            // Step 1: Save original routing
            saveOriginalRoute();

            // Step 2: Connect to WiFi
            logger.accept("Connecting to WiFi...");
            if (!connectToWifi(ssid)) {
                logger.accept("ERROR: Failed to connect to WiFi");
                return false;
            }

            // Step 3: Wait for connection and get interface
            logger.accept("Waiting for network...");
            if (!waitForNetwork()) {
                logger.accept("ERROR: Network not ready");
                disconnectWifi(ssid);
                return false;
            }

            // Step 4: Verify we can reach the gateway
            logger.accept("Verifying gateway...");
            if (!pingGateway()) {
                logger.accept("ERROR: Cannot reach PdaNet gateway");
                disconnectWifi(ssid);
                return false;
            }

            // Step 5: Setup TUN interface and routing
            logger.accept("Setting up tunnel...");
            if (!setupTunnel()) {
                logger.accept("ERROR: Failed to setup tunnel");
                cleanup();
                return false;
            }

            // Step 6: Start tun2socks
            logger.accept("Starting proxy bridge...");
            if (!startTun2Socks()) {
                logger.accept("ERROR: Failed to start tun2socks");
                cleanup();
                return false;
            }

            // Step 7: Verify connectivity
            logger.accept("Verifying connection...");
            Thread.sleep(2000);
            if (!verifyConnectivity()) {
                logger.accept("WARNING: Connectivity check failed, but tunnel is up");
            }

            connectedNetwork = ssid;
            connected = true;
            logger.accept("Connection established!");
            return true;

        } catch (Exception e) {
            logger.accept("ERROR: " + e.getMessage());
            cleanup();
            return false;
        }
    }

    /**
     * Disconnect and cleanup everything
     */
    public void disconnect() {
        logger.accept("Disconnecting...");
        cleanup();
        connected = false;
        connectedNetwork = null;
    }

    /**
     * Check if currently connected
     */
    public boolean isConnected() {
        if (!connected) return false;

        // Verify tun2socks is still running
        if (tun2socksProcess != null && !tun2socksProcess.isAlive()) {
            logger.accept("tun2socks died, cleaning up...");
            cleanup();
            connected = false;
            return false;
        }

        // Verify tun0 exists
        try {
            Process p = new ProcessBuilder("ip", "link", "show", TUN_DEVICE).start();
            int result = p.waitFor();
            if (result != 0) {
                logger.accept("TUN interface gone, cleaning up...");
                cleanup();
                connected = false;
                return false;
            }
        } catch (Exception e) {
            // Ignore
        }

        return connected;
    }

    public String getConnectedNetwork() {
        return connectedNetwork != null ? connectedNetwork : "--";
    }

    public String getLocalIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.getName().equals(wifiInterface)) {
                    Enumeration<InetAddress> addresses = ni.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr.getHostAddress().startsWith("192.168.49.")) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return "192.168.49.x";
    }

    public double getSignalStrength() {
        if (connectedNetwork != null) {
            return networkScanner.getSignalStrength(connectedNetwork);
        }
        return 0;
    }

    // --- Private methods ---

    private void saveOriginalRoute() {
        try {
            Process p = new ProcessBuilder("ip", "route", "show", "default").start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            originalDefaultRoute = reader.readLine();
            p.waitFor();

            // Get WiFi interface
            if (originalDefaultRoute != null && originalDefaultRoute.contains("dev ")) {
                String[] parts = originalDefaultRoute.split("dev ");
                if (parts.length > 1) {
                    wifiInterface = parts[1].split(" ")[0].trim();
                }
            }

            logger.accept("Original route: " + originalDefaultRoute);
            logger.accept("WiFi interface: " + wifiInterface);
        } catch (Exception e) {
            logger.accept("Warning: Could not save original route");
        }
    }

    private boolean connectToWifi(String ssid) {
        try {
            // Try to connect using nmcli
            ProcessBuilder pb = new ProcessBuilder("nmcli", "connection", "up", ssid);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                logger.accept("nmcli: " + line);
            }

            int result = p.waitFor();
            if (result == 0) return true;

            // If that fails, try connecting as new network
            logger.accept("Trying as new connection...");
            pb = new ProcessBuilder("nmcli", "device", "wifi", "connect", ssid);
            pb.redirectErrorStream(true);
            p = pb.start();

            reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            while ((line = reader.readLine()) != null) {
                logger.accept("nmcli: " + line);
            }

            return p.waitFor() == 0;
        } catch (Exception e) {
            logger.accept("WiFi connect error: " + e.getMessage());
            return false;
        }
    }

    private void disconnectWifi(String ssid) {
        try {
            new ProcessBuilder("nmcli", "connection", "down", ssid).start().waitFor();
        } catch (Exception e) {
            // Ignore
        }
    }

    private boolean waitForNetwork() {
        for (int i = 0; i < 15; i++) {
            try {
                Process p = new ProcessBuilder("ip", "route", "show", "default").start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = reader.readLine();
                p.waitFor();

                if (line != null && line.contains("192.168.49")) {
                    // Update interface name
                    if (line.contains("dev ")) {
                        String[] parts = line.split("dev ");
                        if (parts.length > 1) {
                            wifiInterface = parts[1].split(" ")[0].trim();
                        }
                    }
                    return true;
                }

                Thread.sleep(1000);
            } catch (Exception e) {
                // Ignore
            }
        }
        return false;
    }

    private boolean pingGateway() {
        try {
            Process p = new ProcessBuilder("ping", "-c", "1", "-W", "2", PDANET_GATEWAY).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean setupTunnel() {
        try {
            // Match Android VpnService approach exactly:
            // - TUN at 10.1.10.1/32
            // - Route all IPs EXCEPT 192.168.0.0/16 through TUN (avoid proxy loop)
            // - DNS handled by tun2socks (no UDP bypass needed)
            String script = String.format(
                "ip tuntap add mode tun dev %s && " +
                "ip addr add %s/32 dev %s && " +
                "ip link set dev %s up && " +
                "ip route del default 2>/dev/null; " +
                // Route all traffic EXCEPT 192.168.0.0/16 through TUN (matches Android)
                // This prevents routing loop - proxy at 192.168.49.1 stays on WiFi
                "ip route add 0.0.0.0/1 dev %s && " +      // 0.0.0.0 - 127.255.255.255
                "ip route add 128.0.0.0/2 dev %s && " +    // 128.0.0.0 - 191.255.255.255
                "ip route add 192.0.0.0/9 dev %s && " +    // 192.0.0.0 - 192.127.255.255
                "ip route add 192.128.0.0/11 dev %s && " + // 192.128.0.0 - 192.159.255.255
                "ip route add 192.160.0.0/13 dev %s && " + // 192.160.0.0 - 192.167.255.255
                // Skip 192.168.0.0/16 (proxy subnet stays on wlan)
                "ip route add 192.169.0.0/16 dev %s && " +
                "ip route add 192.170.0.0/15 dev %s && " +
                "ip route add 192.172.0.0/14 dev %s && " +
                "ip route add 192.176.0.0/12 dev %s && " +
                "ip route add 192.192.0.0/10 dev %s && " +
                "ip route add 193.0.0.0/8 dev %s && " +
                "ip route add 194.0.0.0/7 dev %s && " +
                "ip route add 196.0.0.0/6 dev %s && " +
                "ip route add 200.0.0.0/5 dev %s && " +
                "ip route add 208.0.0.0/4 dev %s && " +
                "ip route add 224.0.0.0/3 dev %s && " +
                "sysctl -w net.ipv4.conf.all.rp_filter=0",
                TUN_DEVICE, TUN_IP, TUN_DEVICE, TUN_DEVICE,
                // All route destinations go to TUN_DEVICE
                TUN_DEVICE, TUN_DEVICE, TUN_DEVICE, TUN_DEVICE, TUN_DEVICE,
                TUN_DEVICE, TUN_DEVICE, TUN_DEVICE, TUN_DEVICE, TUN_DEVICE,
                TUN_DEVICE, TUN_DEVICE, TUN_DEVICE, TUN_DEVICE, TUN_DEVICE,
                TUN_DEVICE
            );

            ProcessBuilder pb = new ProcessBuilder("sudo", "bash", "-c", script);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                logger.accept("setup: " + line);
            }

            return p.waitFor() == 0;
        } catch (Exception e) {
            logger.accept("Tunnel setup error: " + e.getMessage());
            return false;
        }
    }

    private boolean startTun2Socks() {
        try {
            // Check if tun2socks is installed
            Process check = new ProcessBuilder("which", "tun2socks").start();
            if (check.waitFor() != 0) {
                logger.accept("ERROR: tun2socks not installed! Run: sudo apt install tun2socks");
                return false;
            }

            // Start tun2socks with sudo (needs root for TUN)
            ProcessBuilder pb = new ProcessBuilder(
                "sudo", "tun2socks",
                "-device", "tun://" + TUN_DEVICE,
                "-interface", wifiInterface,
                "-proxy", "http://" + PDANET_GATEWAY + ":" + PROXY_PORT
            );
            pb.redirectErrorStream(true);

            tun2socksProcess = pb.start();

            // Read output in background
            new Thread(() -> {
                try {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(tun2socksProcess.getInputStream())
                    );
                    String line;
                    while ((line = reader.readLine()) != null) {
                        final String msg = line;
                        logger.accept("tun2socks: " + msg);
                    }
                } catch (Exception e) {
                    // Process ended
                }
            }).start();

            // Give it a moment to start
            Thread.sleep(1500);
            return tun2socksProcess.isAlive();

        } catch (Exception e) {
            logger.accept("tun2socks error: " + e.getMessage());
            return false;
        }
    }

    private boolean verifyConnectivity() {
        try {
            // Try to connect to a reliable server
            Process p = new ProcessBuilder("curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                                          "--connect-timeout", "5", "https://www.google.com").start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String response = reader.readLine();
            p.waitFor();

            return response != null && (response.equals("200") || response.equals("301") || response.equals("302"));
        } catch (Exception e) {
            return false;
        }
    }

    private void cleanup() {
        logger.accept("Cleaning up...");

        // Stop tun2socks
        if (tun2socksProcess != null && tun2socksProcess.isAlive()) {
            tun2socksProcess.destroy();
            try {
                tun2socksProcess.waitFor(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                tun2socksProcess.destroyForcibly();
            }
        }

        // Cleanup with sudo - remove all routes and TUN interface
        try {
            String script =
                "killall -9 tun2socks 2>/dev/null; " +
                // Remove all routes we added (matching Android approach)
                "ip route del 0.0.0.0/1 dev " + TUN_DEVICE + " 2>/dev/null; " +
                "ip route del 128.0.0.0/2 dev " + TUN_DEVICE + " 2>/dev/null; " +
                "ip route del 192.0.0.0/9 dev " + TUN_DEVICE + " 2>/dev/null; " +
                "ip route del 192.128.0.0/11 dev " + TUN_DEVICE + " 2>/dev/null; " +
                "ip route del 192.160.0.0/13 dev " + TUN_DEVICE + " 2>/dev/null; " +
                "ip route del 192.169.0.0/16 dev " + TUN_DEVICE + " 2>/dev/null; " +
                "ip route del 192.170.0.0/15 dev " + TUN_DEVICE + " 2>/dev/null; " +
                "ip route del 192.172.0.0/14 dev " + TUN_DEVICE + " 2>/dev/null; " +
                "ip route del 192.176.0.0/12 dev " + TUN_DEVICE + " 2>/dev/null; " +
                "ip route del 192.192.0.0/10 dev " + TUN_DEVICE + " 2>/dev/null; " +
                "ip route del 193.0.0.0/8 dev " + TUN_DEVICE + " 2>/dev/null; " +
                "ip route del 194.0.0.0/7 dev " + TUN_DEVICE + " 2>/dev/null; " +
                "ip route del 196.0.0.0/6 dev " + TUN_DEVICE + " 2>/dev/null; " +
                "ip route del 200.0.0.0/5 dev " + TUN_DEVICE + " 2>/dev/null; " +
                "ip route del 208.0.0.0/4 dev " + TUN_DEVICE + " 2>/dev/null; " +
                "ip route del 224.0.0.0/3 dev " + TUN_DEVICE + " 2>/dev/null; " +
                // Remove TUN interface
                "ip link set dev " + TUN_DEVICE + " down 2>/dev/null; " +
                "ip link delete " + TUN_DEVICE + " 2>/dev/null; " +
                "sysctl -w net.ipv4.conf.all.rp_filter=1";

            ProcessBuilder pb = new ProcessBuilder("sudo", "bash", "-c", script);
            pb.start().waitFor();
        } catch (Exception e) {
            logger.accept("Cleanup error: " + e.getMessage());
        }

        // Disconnect from PdaNet WiFi
        if (connectedNetwork != null) {
            disconnectWifi(connectedNetwork);
        }

        tun2socksProcess = null;
        logger.accept("Cleanup complete");
    }
}
