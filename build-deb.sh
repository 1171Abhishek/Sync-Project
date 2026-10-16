#!/usr/bin/env bash
set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

VERSION="0.0.10"
PACKAGE_NAME="clip-sync"
ARCH="amd64"

echo "🔨 [1/3] Compiling clip-sync binary for Linux..."
go build -ldflags="-s -w" -o clip-sync .
echo "✓ Compiled clip-sync binary successfully."

# Ensure clip-sync.service exists
if [ ! -f "clip-sync.service" ]; then
    cat <<'EOF' > clip-sync.service
[Unit]
Description=ClipSync - Cross-device Linux/Android Continuity Daemon
Documentation=https://github.com/1171Abhishek/Sync-Project
After=network.target

[Service]
Type=simple
ExecStart=/usr/bin/clip-sync
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
EOF
fi

mkdir -p dist

if command -v dpkg-deb &>/dev/null; then
    echo "📦 [2/3] Building DEB package with dpkg-deb..."
    DEB_DIR="$PROJECT_DIR/dist/deb_build/${PACKAGE_NAME}_${VERSION}-1_${ARCH}"
    rm -rf "$DEB_DIR"
    mkdir -p "$DEB_DIR"/DEBIAN
    mkdir -p "$DEB_DIR"/usr/bin
    mkdir -p "$DEB_DIR"/usr/lib/systemd/user

    # Copy binary and systemd service
    install -m 755 clip-sync "$DEB_DIR"/usr/bin/clip-sync
    install -m 644 clip-sync.service "$DEB_DIR"/usr/lib/systemd/user/clip-sync.service

    # Create Debian control file
    cat <<EOF > "$DEB_DIR"/DEBIAN/control
Package: clip-sync
Version: ${VERSION}-1
Section: net
Priority: optional
Architecture: ${ARCH}
Maintainer: Abhishek Kumar <abhishek@example.com>
Depends: wl-clipboard, libnotify-bin, xdg-utils
Recommends: xclip
Description: Seamless Linux and Android continuity daemon
 ClipSync is a high-speed, lightweight Linux and Android continuity tool.
 Features real-time clipboard sync, incoming call alerts, phone battery
 alerts, automatic OTP detection, and peer-to-peer file transfer.
EOF

    # Create post-install message script
    cat <<'EOF' > "$DEB_DIR"/DEBIAN/postinst
#!/bin/sh
set -e
echo ""
echo "=========================================================="
echo "✓ ClipSync installed successfully!"
echo "To start ClipSync automatically in the background on login:"
echo "   systemctl --user enable --now clip-sync"
echo "=========================================================="
echo ""
exit 0
EOF
    chmod 755 "$DEB_DIR"/DEBIAN/postinst

    # Build the .deb package
    dpkg-deb --build --root-owner-group "$DEB_DIR" "$PROJECT_DIR/dist/"
    rm -rf "$PROJECT_DIR/dist/deb_build"

    echo "✓ [3/3] Done! DEB package created:"
    ls -lh dist/*.deb
    echo ""
    echo "👉 To install on Ubuntu / Debian / Linux Mint / Pop!_OS:"
    echo "   sudo apt install ./dist/${PACKAGE_NAME}_${VERSION}-1_${ARCH}.deb"

elif command -v nfpm &>/dev/null; then
    echo "📦 [2/3] Building DEB package with nfpm..."
    nfpm package --config nfpm.yaml --packager deb --target dist/
    echo "✓ [3/3] Done! DEB package created:"
    ls -lh dist/*.deb
    echo ""
    echo "👉 To install on Ubuntu / Debian / Linux Mint / Pop!_OS:"
    echo "   sudo apt install ./dist/${PACKAGE_NAME}_${VERSION}-1_${ARCH}.deb"

else
    echo "⚠️  Neither 'dpkg-deb' nor 'nfpm' was found."
    echo ""
    echo "To build DEB packages on Ubuntu/Debian, install dpkg-dev:"
    echo "   sudo apt install -y dpkg-dev"
    exit 1
fi
