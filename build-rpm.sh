#!/usr/bin/env bash
set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

echo "🔨 [1/3] Compiling clip-sync binary..."
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

if command -v rpmbuild &>/dev/null; then
    echo "📦 [2/3] Building RPM package with rpmbuild..."

    RPM_TOPDIR="$PROJECT_DIR/dist/rpmbuild"
    rm -rf "$RPM_TOPDIR"
    mkdir -p "$RPM_TOPDIR"/{BUILD,RPMS,SOURCES,SPECS,SRPMS,tmp}

    # Copy files to SOURCES & SPECS
    cp clip-sync "$RPM_TOPDIR/SOURCES/"
    cp clip-sync.service "$RPM_TOPDIR/SOURCES/"
    cp packaging/clip-sync.spec "$RPM_TOPDIR/SPECS/"

    rpmbuild -bb \
        --define "_topdir $RPM_TOPDIR" \
        --define "_tmppath $RPM_TOPDIR/tmp" \
        "$RPM_TOPDIR/SPECS/clip-sync.spec"

    # Move RPM to dist/
    find "$RPM_TOPDIR/RPMS" -name "*.rpm" -exec cp {} "$PROJECT_DIR/dist/" \;
    echo "✓ [3/3] Done! RPM package created:"
    ls -lh dist/*.rpm
    echo ""
    echo "👉 To install on Fedora:"
    echo "   sudo dnf install $(ls -1 dist/*.rpm | head -n 1)"

elif command -v nfpm &>/dev/null; then
    echo "📦 [2/3] Building RPM package with nfpm..."
    nfpm package --config nfpm.yaml --packager rpm --target dist/
    echo "✓ [3/3] Done! RPM package created:"
    ls -lh dist/*.rpm
    echo ""
    echo "👉 To install on Fedora:"
    echo "   sudo dnf install $(ls -1 dist/*.rpm | head -n 1)"

else
    echo "⚠️  Neither 'rpmbuild' nor 'nfpm' was found."
    echo ""
    echo "To build the RPM on Fedora, install rpm-build:"
    echo "   sudo dnf install -y rpm-build"
    echo ""
    echo "Then rerun this script:"
    echo "   ./build-rpm.sh"
    exit 1
fi
