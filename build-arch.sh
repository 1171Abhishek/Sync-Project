#!/usr/bin/env bash
set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

VERSION="1.0.0"
PACKAGE_NAME="clip-sync"

echo "🔨 [1/3] Compiling clip-sync binary for Arch Linux..."
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

if command -v makepkg &>/dev/null; then
    echo "📦 [2/3] Building Arch package with makepkg..."
    ARCH_DIR="$PROJECT_DIR/dist/arch_build"
    rm -rf "$ARCH_DIR"
    mkdir -p "$ARCH_DIR"

    cp clip-sync "$ARCH_DIR/"
    cp clip-sync.service "$ARCH_DIR/"

    cat <<'EOF' > "$ARCH_DIR"/PKGBUILD
# Maintainer: Abhishek Kumar <abhishek@example.com>
pkgname=clip-sync
pkgver=1.0.0
pkgrel=1
pkgdesc="Seamless Linux and Android continuity daemon"
arch=('x86_64')
url="https://github.com/1171Abhishek/Sync-Project"
license=('GPL-3.0-only')
depends=('wl-clipboard' 'libnotify' 'xdg-utils')
optdepends=('xclip: for X11 clipboard fallback')

package() {
  install -Dm755 "${startdir}/clip-sync" "${pkgdir}/usr/bin/clip-sync"
  install -Dm644 "${startdir}/clip-sync.service" "${pkgdir}/usr/lib/systemd/user/clip-sync.service"
}
EOF

    cd "$ARCH_DIR"
    if [ "$(id -u)" = "0" ]; then
        useradd -m builder 2>/dev/null || true
        chown -R builder:builder "$ARCH_DIR"
        su builder -c "makepkg -f --nodeps"
    else
        makepkg -f --nodeps
    fi
    cd "$PROJECT_DIR"

    find "$ARCH_DIR" -name "*.pkg.tar.*" -exec cp {} "$PROJECT_DIR/dist/" \;
    rm -rf "$ARCH_DIR"

    echo "✓ [3/3] Done! Arch package created:"
    ls -lh dist/*.pkg.tar.*
    echo ""
    echo "👉 To install on Arch Linux / Manjaro / EndeavourOS:"
    echo "   sudo pacman -U $(ls -1 dist/*.pkg.tar.* | head -n 1)"

else
    echo "⚠️  'makepkg' was not found on this system (specific to Arch Linux)."
    echo "GitHub Actions will build the Arch Linux package automatically on every push!"
    exit 1
fi
