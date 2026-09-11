# Z Connect Linux

Native Linux desktop application for connecting to PdaNet WiFi Direct hotspots.

## Overview

This is a JavaFX desktop application that provides a GUI for connecting to Android phones running Z Connect Host (PdaNet-compatible WiFi Direct tethering).

## Origin Projects

This application was built by combining and adapting code from:

### 1. zconnect-host-android
**Location:** `~/zconnect-host-android/`

The Android host application that creates the WiFi Direct hotspot. Key components reused:

- **Protocol specification**: HTTP CONNECT proxy on `192.168.49.1:8000`
- **SSID naming convention**: `DIRECT-xx-[device]-PdaNet`
- **Network configuration**: `192.168.49.0/24` subnet
- **DeviceNameHelper.kt**: PdaNet suffix naming logic

### 2. zconnect (Linux scripts)
**Location:** `~/zconnect/`

The original shell script and Python tray implementation. Components adapted:

- **zconnect.sh**: TUN interface setup, routing configuration, tun2socks integration
- **zconnect-tray-control.py**: NetworkManager WiFi connection logic (replaced with Java equivalent)
- **HOW-ZCONNECT-WORKS.md**: Architecture documentation

### 3. lawnchair-android8-compatible
**Location:** `~/lawnchair-android8-compatible/`

Referenced for Android WiFi Direct patterns (not directly used in Linux app).

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Z Connect Linux App                       │
│                     (JavaFX Desktop)                         │
├─────────────────────────────────────────────────────────────┤
│  NetworkScanner.java    │  ConnectionManager.java            │
│  - Scans for DIRECT-    │  - Connects to WiFi via nmcli      │
│    *PdaNet* networks    │  - Creates tun0 interface          │
│  - Uses nmcli           │  - Configures routing              │
│                         │  - Starts tun2socks (HTTP proxy)   │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     tun2socks                                │
│            TUN interface ↔ HTTP CONNECT proxy                │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              Android Phone (Z Connect Host)                  │
│  WiFi Direct: DIRECT-xx-device-PdaNet                        │
│  HTTP Proxy:  192.168.49.1:8000                              │
│  Network:     192.168.49.0/24                                │
└─────────────────────────────────────────────────────────────┘
```

## Protocol Details

| Component | Value |
|-----------|-------|
| WiFi Direct SSID | `DIRECT-xx-[device]-PdaNet` |
| Network Subnet | `192.168.49.0/24` |
| Phone/Gateway IP | `192.168.49.1` |
| Proxy Type | HTTP CONNECT (not SOCKS5) |
| Proxy Port | `8000` |
| TUN Interface | `tun0` at `192.168.1.1/24` |

## Requirements

- Java 21+
- JavaFX
- tun2socks (`sudo apt install tun2socks` or download from GitHub)
- NetworkManager (nmcli)
- Root access (for TUN interface creation)

## Installation

```bash
./install.sh
```

Or manually:
```bash
# Build
/path/to/gradle build

# Install desktop entry
cp zconnect.desktop ~/.local/share/applications/

# Enable autostart
cp zconnect.desktop ~/.config/autostart/
```

## Usage

1. Launch "Z Connect" from application menu
2. Available PdaNet networks will be scanned automatically
3. Select a network and click "Connect"
4. Enter sudo password when prompted (for TUN setup)
5. Click "Disconnect" to cleanly tear down the connection

## Files

```
zconnect-app/
├── src/main/java/com/zconnect/
│   ├── ZConnectApp.java         # Main JavaFX UI
│   ├── NetworkScanner.java      # WiFi network scanning
│   └── ConnectionManager.java   # TUN/routing/tun2socks
├── build.gradle                 # Gradle build config
├── zconnect.sh                  # Launch script
├── zconnect.desktop             # Desktop entry
├── install.sh                   # Installation script
└── icon.svg                     # App icon
```

## What Was Changed from Original

### From zconnect-tray-control.py:
- **Removed**: Hardcoded SSID (`DIRECT-30-moto g power-PdaNet`)
- **Added**: Dynamic network scanning for any `DIRECT-*PdaNet*` pattern
- **Removed**: External shell script dependency
- **Added**: Built-in connection management
- **Removed**: GTK3/AppIndicator (deprecated)
- **Added**: Modern JavaFX dashboard UI

### From zconnect.sh:
- **Fixed**: Protocol changed from SOCKS5 to HTTP CONNECT (matches Android app)
- **Added**: Proper error handling and logging
- **Added**: Connection health monitoring

### From Android app:
- **Matched**: HTTP CONNECT proxy protocol (not SOCKS5)
- **Matched**: Network addressing (192.168.49.x)
- **Matched**: SSID detection pattern

## Troubleshooting

### App won't launch
```bash
# Check Java
java -version  # Need 21+

# Check JavaFX
dpkg -l | grep openjfx

# Run manually with output
cd ~/zconnect-app
/home/jonathan/gradle-8.5/bin/gradle run
```

### Can't find PdaNet networks
```bash
# Rescan WiFi manually
nmcli device wifi rescan
nmcli device wifi list | grep -i pdanet
```

### Connection fails
```bash
# Check if tun2socks is installed
which tun2socks

# Install if missing
# Option 1: apt
sudo apt install tun2socks

# Option 2: Download from GitHub
wget https://github.com/xjasonlyu/tun2socks/releases/download/v2.5.2/tun2socks-linux-amd64.zip
unzip tun2socks-linux-amd64.zip
sudo mv tun2socks-linux-amd64 /usr/local/bin/tun2socks
sudo chmod +x /usr/local/bin/tun2socks
```

### Manual connection (if app fails)
```bash
# 1. Connect to WiFi
nmcli device wifi connect "DIRECT-xx-device-PdaNet"

# 2. Wait for IP
ip addr show | grep 192.168.49

# 3. Create TUN interface
sudo ip tuntap add mode tun dev tun0
sudo ip addr add 192.168.1.1/24 dev tun0
sudo ip link set dev tun0 up

# 4. Setup routing
sudo ip route del default
sudo ip route add default via 192.168.1.1 dev tun0 metric 1
sudo ip route add default via 192.168.49.1 dev wlp3s0 metric 10

# 5. Disable rp_filter
sudo sysctl -w net.ipv4.conf.all.rp_filter=0

# 6. Start tun2socks (HTTP proxy, not SOCKS5!)
sudo tun2socks -device tun://tun0 -interface wlp3s0 -proxy http://192.168.49.1:8000
```

### Manual cleanup
```bash
# Kill tun2socks
sudo killall tun2socks

# Remove TUN interface
sudo ip link delete tun0

# Restore rp_filter
sudo sysctl -w net.ipv4.conf.all.rp_filter=1

# Disconnect WiFi
nmcli connection down "DIRECT-xx-device-PdaNet"
```

### Autostart not working
```bash
# Check desktop file
cat ~/.config/autostart/zconnect.desktop

# Check if script is executable
ls -la ~/zconnect-app/zconnect.sh

# Make executable if needed
chmod +x ~/zconnect-app/zconnect.sh
```

## License

Internal project - see original component licenses.
