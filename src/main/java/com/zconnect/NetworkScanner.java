package com.zconnect;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans for PdaNet WiFi Direct networks using nmcli
 */
public class NetworkScanner {

    // Pattern to match PdaNet networks: DIRECT-*-*-PdaNet or DIRECT-*PdaNet*
    private static final Pattern PDANET_PATTERN = Pattern.compile(
        "DIRECT-.*PdaNet.*|DIRECT-.*-PdaNet",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Scan for available WiFi networks that match PdaNet pattern
     */
    public List<String> scanForPdaNetNetworks() {
        List<String> pdaNetworks = new ArrayList<>();

        try {
            // First, trigger a rescan
            runCommand("nmcli", "device", "wifi", "rescan");
            Thread.sleep(1000);

            // Get list of available networks
            ProcessBuilder pb = new ProcessBuilder(
                "nmcli", "-t", "-f", "SSID,SIGNAL,SECURITY", "device", "wifi", "list"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );

            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(":");
                if (parts.length >= 1) {
                    String ssid = parts[0].trim();
                    if (!ssid.isEmpty() && isPdaNetNetwork(ssid)) {
                        // Include signal strength if available
                        String display = ssid;
                        if (parts.length >= 2) {
                            display += " (" + parts[1] + "%)";
                        }
                        if (!pdaNetworks.contains(display)) {
                            pdaNetworks.add(display);
                        }
                    }
                }
            }

            process.waitFor();

            // Also check saved connections that might be PdaNet
            addSavedPdaNetConnections(pdaNetworks);

        } catch (Exception e) {
            System.err.println("Error scanning networks: " + e.getMessage());
        }

        return pdaNetworks;
    }

    /**
     * Check if SSID matches PdaNet pattern
     */
    public boolean isPdaNetNetwork(String ssid) {
        if (ssid == null || ssid.isEmpty()) {
            return false;
        }
        Matcher matcher = PDANET_PATTERN.matcher(ssid);
        return matcher.matches() || ssid.toLowerCase().contains("pdanet");
    }

    /**
     * Add saved NetworkManager connections that are PdaNet
     */
    private void addSavedPdaNetConnections(List<String> networks) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "nmcli", "-t", "-f", "NAME,TYPE", "connection", "show"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );

            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(":");
                if (parts.length >= 2 && parts[1].contains("wireless")) {
                    String name = parts[0].trim();
                    if (isPdaNetNetwork(name)) {
                        String display = name + " (saved)";
                        if (!networks.stream().anyMatch(n -> n.startsWith(name))) {
                            networks.add(display);
                        }
                    }
                }
            }

            process.waitFor();
        } catch (Exception e) {
            System.err.println("Error checking saved connections: " + e.getMessage());
        }
    }

    /**
     * Get signal strength for a connected network (0.0 - 1.0)
     */
    public double getSignalStrength(String ssid) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "nmcli", "-t", "-f", "SSID,SIGNAL", "device", "wifi", "list"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );

            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(":");
                if (parts.length >= 2 && parts[0].trim().equals(ssid)) {
                    try {
                        int signal = Integer.parseInt(parts[1].trim());
                        return signal / 100.0;
                    } catch (NumberFormatException e) {
                        return 0.5;
                    }
                }
            }

            process.waitFor();
        } catch (Exception e) {
            // Ignore
        }
        return 0.5;
    }

    /**
     * Get the currently connected WiFi network SSID
     */
    public String getConnectedNetwork() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "nmcli", "-t", "-f", "ACTIVE,SSID", "device", "wifi"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("yes:")) {
                    return line.substring(4).trim();
                }
            }

            process.waitFor();
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private void runCommand(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor();
        } catch (Exception e) {
            // Ignore
        }
    }
}
