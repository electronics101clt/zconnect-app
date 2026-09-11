#!/bin/bash
set -e

echo "=== Z Connect Installer ==="
echo ""

# Check dependencies
echo "Checking dependencies..."

# Check Java
if ! command -v java &> /dev/null; then
    echo "Installing Java..."
    sudo apt install -y openjdk-21-jdk
fi

# Check JavaFX
if ! dpkg -l | grep -q openjfx; then
    echo "Installing JavaFX..."
    sudo apt install -y openjfx
fi

# Check tun2socks
if ! command -v tun2socks &> /dev/null; then
    echo "Installing tun2socks..."
    # Try apt first
    if apt-cache show tun2socks &> /dev/null; then
        sudo apt install -y tun2socks
    else
        # Download from GitHub releases
        echo "Downloading tun2socks from GitHub..."
        TUN2SOCKS_VERSION="v2.5.2"
        wget -q "https://github.com/xjasonlyu/tun2socks/releases/download/${TUN2SOCKS_VERSION}/tun2socks-linux-amd64.zip" -O /tmp/tun2socks.zip
        unzip -o /tmp/tun2socks.zip -d /tmp/
        sudo mv /tmp/tun2socks-linux-amd64 /usr/local/bin/tun2socks
        sudo chmod +x /usr/local/bin/tun2socks
        rm /tmp/tun2socks.zip
    fi
fi

# Check gradle
if ! command -v gradle &> /dev/null; then
    echo "Installing Gradle..."
    sudo apt install -y gradle
fi

echo "Dependencies OK!"
echo ""

# Build the app
echo "Building Z Connect..."
cd "$(dirname "$0")"

# Use gradle wrapper if available, otherwise system gradle
if [ -f "./gradlew" ]; then
    ./gradlew build -x test
else
    gradle build -x test
fi

echo ""
echo "Build complete!"

# Make launcher executable
chmod +x zconnect.sh

# Install desktop entry
echo "Installing desktop entry..."
cp zconnect.desktop ~/.local/share/applications/
update-desktop-database ~/.local/share/applications/ 2>/dev/null || true

echo ""
echo "=== Installation Complete ==="
echo ""
echo "You can now:"
echo "  1. Launch from Applications menu (search 'Z Connect')"
echo "  2. Run ./zconnect.sh from this directory"
echo "  3. Pin to taskbar from the application menu"
echo ""
