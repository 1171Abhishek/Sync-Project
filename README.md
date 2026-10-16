# ⚡ ClipSync

<p align="center">
  <strong>Seamless, private, and lightning-fast local continuity between Linux and Android.</strong>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License: GPL v3"></a>
  <a href="https://golang.org"><img src="https://img.shields.io/badge/Go-1.22+-00ADD8?logo=go&logoColor=white" alt="Go Version"></a>
  <a href="https://android.com"><img src="https://img.shields.io/badge/Android-7.0%2B%20(API%2024%2B)-3DDC84?logo=android&logoColor=white" alt="Android Version"></a>
  <img src="https://img.shields.io/badge/Security-AES--256--GCM-success" alt="E2E Encrypted">
  <img src="https://img.shields.io/badge/Display-Wayland%20%7C%20X11-orange" alt="Wayland & X11 Supported">
</p>

---

**ClipSync** is a lightweight, zero-bloat alternative to tools like KDE Connect and scrcpy for seamless cross-device continuity. Built with a pure Go daemon on Linux and a native Android service, it operates entirely over your local Wi-Fi with **100% automatic end-to-end encryption (AES-256-GCM)**, zero cloud dependency, and virtually 0% idle CPU usage.

---

## ✨ Features

- 📋 **Bidirectional Clipboard Sync**
  - Instant, real-time synchronization between Linux and Android.
  - Native Wayland (`wl-clipboard`) and X11 (`xclip`) support with in-process event watchers (0 process forks on idle).
  - Works with Android 10, 11, 12, 13, and 14+ via an optimized Accessibility Service.

- 🔑 **Smart OTP & 2FA Code Auto-Extraction**
  - Automatically captures verification codes from incoming SMS, 2FA Authenticator apps (Google Authenticator, 2FAS, Aegis, Authy, Bitwarden), and Android copy toasts.
  - Instantly notifies your Linux desktop with a one-click copy prompt.

- 🌐 **Instant Web Page / Tab Push**
  - **Linux ➔ Android:** Press <kbd>Ctrl</kbd> + <kbd>Alt</kbd> + <kbd>P</kbd> or run `clip-sync send <url>` to open any webpage on your phone with heads-up notifications.
  - **Android ➔ Linux:** Tap **Share** in Chrome or Firefox, choose **"Send to PC (ClipSync)"**, and the link opens automatically in a new tab in your default Linux browser (`xdg-open`).

- 📁 **P2P Fast File Drop**
  - **Linux ➔ Android:** Right-click any file in Nautilus (GNOME Files) ➔ **Scripts ➔ Send to Phone (ClipSync)**, or run `clip-sync send <file>`. Photos land straight in your phone's Gallery (`Pictures/ClipSync`), and files in `Downloads/ClipSync`.
  - **Android ➔ Linux:** Share any image or document from your phone to **"Send to PC"**; files are saved straight to `~/Downloads` with desktop notifications.

- 🪫 **Phone Battery Alert on Desktop**
  - Real-time event-driven battery monitoring with virtually 0% idle CPU and network impact.
  - Smart desktop notifications when phone battery is low (20%, 10%, and 5%) and when fully charged (100%).
  - Inspect your phone's battery level and charging status directly from your terminal anytime via `clip-sync battery`.

- 📞 **Incoming Call Alert (Read-Only)**
  - Instant heads-up desktop notifications when your phone rings, displaying caller name and phone number.
  - Seamless support for regular phone calls and VoIP apps (WhatsApp, Telegram, etc.).
  - Read-only notification automatically closes when answered or changes to a "Missed Call" alert if unanswered.

- 🔒 **Zero-Config End-to-End Encryption**
  - All communication (clipboard, files, URLs, and notifications) is automatically encrypted with **AES-256-GCM** using PBKDF2/HMAC-SHA256 key derivation.
  - File transfers are authenticated with time-bound cryptographic HMAC signatures.
  - Zero manual PINs or pairing codes required out-of-the-box (custom PINs supported).

- 📡 **Zero-Configuration Discovery**
  - Broadcasts lightweight UDP beacons on port `9876`.
  - Handles Wi-Fi router AP isolation with ARP neighbor probing and active reconnection watchdogs.

---

## 🚀 Quick Start

### 1. Linux Setup

#### Option A: Install via RPM (Fedora, RHEL, openSUSE)
If you built or downloaded the RPM package:
```bash
sudo dnf install dist/clip-sync-1.0.0-1.fc44.x86_64.rpm
```
Enable and start the background user service:
```bash
systemctl --user enable --now clip-sync
```

#### Option B: Install via DEB (Ubuntu, Debian, Linux Mint, Pop!_OS)
If you built or downloaded the DEB package:
```bash
sudo apt install ./dist/clip-sync_1.0.0-1_amd64.deb
```
Enable and start the background user service:
```bash
systemctl --user enable --now clip-sync
```

#### Option C: Build & Run from Source
Prerequisites:
- **Go** (1.20+)
- Wayland or X11 clipboard tools:
  ```bash
  # Fedora
  sudo dnf install wl-clipboard libnotify xdg-utils
  
  # Ubuntu / Debian
  sudo apt install wl-clipboard libnotify-bin xdg-utils xclip
  
  # Arch Linux
  sudo pacman -S wl-clipboard libnotify xdg-utils
  ```

Build and run:
```bash
git clone https://github.com/1171Abhishek/Sync-Project.git
cd Sync-Project
go build -o clip-sync .
./clip-sync setup       # Registers GNOME shortcut (Ctrl+Alt+P) & Nautilus right-click script
./clip-sync             # Start daemon
```

---

### 2. Android Setup

1. Build the APK or download `app-release.apk` from the latest GitHub Release / GitHub Actions artifact.
2. Install the APK on your Android device:
   ```bash
   adb install -r android/app/build/outputs/apk/release/app-release.apk
   ```
3. Open the **ClipSync** app and grant the necessary permissions:
   - **Accessibility Service:** Required for real-time clipboard synchronization and OTP capture.
   - **Notification Access:** Required for mirroring notifications and 2FA alerts to Linux.
   - **Battery Optimization Exemption:** Keeps ClipSync connected in the background.

---

## ⌨️ Shortcuts & CLI Usage

| Command | Action |
|---|---|
| `clip-sync` | Start the background daemon (auto-configures desktop integration) |
| `clip-sync battery` | Check connected phone battery percentage & charging status |
| `clip-sync send <file\|url>` | Send any local file, image, or URL link to your phone |
| `clip-sync send-link [url]` | Send highlighted link or copied tab to your phone |
| `clip-sync setup` | Re-register GNOME shortcut (`Ctrl+Alt+P`) and Nautilus script |
| `clip-sync pair [pin]` | Inspect or update the AES-256-GCM pairing PIN |

### Desktop Shortcuts:
- **<kbd>Ctrl</kbd> + <kbd>Alt</kbd> + <kbd>P</kbd>**: Push your currently copied or highlighted browser link directly to your phone.
- **File Manager Context Menu**: Right-click any file in Nautilus ➔ **Scripts ➔ Send to Phone (ClipSync)**.

---

## 📦 Building Packages

### Build RPM Package (Fedora / RHEL / openSUSE)
Compile stripped binary and generate RPM:
```bash
./build-rpm.sh
```
Result: `dist/clip-sync-*.x86_64.rpm`

### Build DEB Package (Ubuntu / Debian / Linux Mint)
Compile stripped binary and generate DEB:
```bash
./build-deb.sh
```
Result: `dist/clip-sync_*_amd64.deb`

### Build Android APK
Make sure Android SDK / JDK 17 are installed:
```bash
cd android
./gradlew assembleRelease
```
*Tip: Push to any branch on GitHub to automatically trigger signed builds for **APK**, **RPM**, and **DEB** via GitHub Actions.*

---

## 📡 Network & Ports

ClipSync operates strictly on your local area network (LAN):

| Port | Protocol | Description |
|---|---|---|
| `9876` | UDP | Device auto-discovery beacon & active probing |
| `9877` | TCP | E2E encrypted clipboard sync, notifications, and URLs |
| `9878` | HTTP/TCP | HMAC-authenticated P2P file drop receiver |

---

## 📄 License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)**. See the [LICENSE](LICENSE) file for details.

Copyright (C) 2026 Abhishek Kumar.
