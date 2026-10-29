# ⚡ ClipSync

<p align="center">
  <strong>Seamless, private, and lightning-fast local continuity between Linux and Android.</strong>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License: GPL v3"></a>
  <a href="https://www.rust-lang.org"><img src="https://img.shields.io/badge/Rust-1.75+-dea584?logo=rust&logoColor=white" alt="Rust Version"></a>
  <a href="https://android.com"><img src="https://img.shields.io/badge/Android-7.0%2B%20(API%2024%2B)-3DDC84?logo=android&logoColor=white" alt="Android Version"></a>
  <img src="https://img.shields.io/badge/Security-AES--256--GCM-success" alt="E2E Encrypted">
  <img src="https://img.shields.io/badge/Display-Wayland%20%7C%20X11-orange" alt="Wayland & X11 Supported">
  <img src="https://img.shields.io/badge/Status-Paused%20%2F%20Stable%20v0.0.11-brightgreen" alt="Status">
</p>

---

**ClipSync** is a lightweight, zero-bloat alternative to tools like KDE Connect for cross-device continuity. Built with a high-performance **Rust** daemon on Linux and a native Android background service, it operates strictly over your local Wi-Fi with **100% automatic end-to-end encryption (AES-256-GCM)**, zero cloud dependency, and virtually 0% idle CPU usage.

---

## 📌 Project Status & Future Restart Guide

> **Current Milestone: v0.0.11 (Maintenance / Stable)**
> 
> The core Linux ⟷ Android continuity features, FIFO active device switching, and automated GitHub Actions CI/CD pipeline are completed and stable. The project is currently paused to focus on other primary priorities.

### 📋 Checklist for Resuming the Project Later

When reopening or commercializing ClipSync in the future, follow this quick-start checklist:

1. **Google Play Store Publishing ($25 Developer Account)**:
   - Eliminates all "Blocked by Play Protect" prompts permanently on all user devices via official **Play App Signing**.
   - **Accessibility Declaration**: Google requires an Accessibility Declaration Form in Play Console. State:
     > *"ClipSync is a cross-device continuity tool. On Android 10+, background clipboard access is restricted by Android OS; an Accessibility Service is used solely to monitor clipboard copy actions to sync them to the paired Linux desktop (similar to KDE Connect)."*
   - Google usually requests a 30-second unlisted YouTube video demonstrating the clipboard sync feature.
   - The signing keystore is already configured in GitHub Actions via the `KEYSTORE_BASE64` secret.

2. **Personal / Daily Use Sideloading (No $25 Fee Needed)**:
   - For your personal phone, you do not need Google Play.
   - Simply download the latest signed APK from GitHub Actions / Releases.
   - If Android Play Protect shows *"Blocked by Play Protect"* (because automated scanners evaluate each newly compiled binary hash), tap **"More details" ➔ "Install anyway"**. Once installed, it runs cleanly with zero prompts.

3. **F-Droid / IzzyOnDroid Submission (Alternative Open Source Route)**:
   - F-Droid builds directly from source repositories and is 100% free with no developer fee.
   - Suitable if you want wider public distribution without a Google Play Console account.

---

## ✨ Features

- 📋 **Bidirectional Clipboard Sync**
  - Instant, real-time synchronization between Linux and Android.
  - Native Wayland (`wl-clipboard`) and X11 (`xclip`) support with in-process event watchers (0 process forks on idle).
  - Compatible with Android 10 through 15+ via an optimized Accessibility Service.

- 📱 **Active Device Target Model (FIFO)**
  - Enforces strict **Linux ⟷ Android** continuity (ignores Linux-to-Linux and Android-to-Android pairing).
  - When multiple Android devices connect to Linux, the first-connected device becomes `[ACTIVE]` by default; additional devices stay in `[Standby]`.
  - Switch active targets anytime with `clip-sync switch <name|index>` or via the CLI device menu.

- 🔑 **Smart OTP & 2FA Verification Alerts**
  - Automatically captures verification codes from incoming SMS, 2FA Authenticator apps, and Android copy toasts.
  - Instantly notifies your Linux desktop with a one-click copy prompt.

- 🌐 **Instant Web Page / URL Push**
  - **Linux ➔ Android:** Run `clip-sync send <url>` or press <kbd>Ctrl</kbd> + <kbd>Alt</kbd> + <kbd>P</kbd> to open any URL on the active phone.
  - **Android ➔ Linux:** Tap **Share** in Chrome or Firefox, choose **"Send to PC (ClipSync)"**, and the link opens automatically in your default Linux browser (`xdg-open` / `gio open`).

- 📁 **P2P Fast File Drop**
  - **Linux ➔ Android:** Right-click any file in Nautilus (GNOME Files) ➔ **Scripts ➔ Send to Phone (ClipSync)**, or run `clip-sync send <file>`. Photos land in `Pictures/ClipSync`, and files in `Downloads/ClipSync`.
  - **Android ➔ Linux:** Share any image or document from your phone to **"Send to PC"**; files are saved straight to `~/Downloads` with desktop notifications.

- 🪫 **Phone Battery Monitoring**
  - Real-time battery status monitoring with virtually 0% idle CPU and network impact.
  - Desktop notifications when phone battery is low (20%, 10%, 5%) and when fully charged (100%).
  - Inspect battery percentage anytime via `clip-sync battery`.

- 📞 **Incoming Call Alerts (Read-Only)**
  - Instant heads-up desktop notifications when your phone rings, displaying caller name/number.
  - Notification automatically closes when answered or changes to a "Missed Call" alert if unanswered.

- 🔒 **Zero-Config End-to-End Encryption**
  - All communication (clipboard, files, URLs, notifications) is encrypted with **AES-256-GCM** using PBKDF2/HMAC-SHA256 key derivation.
  - File transfers are authenticated with time-bound cryptographic HMAC signatures.

---

## 🚀 Quick Start

### 1. Linux Setup

#### Install from Source (Rust)
Prerequisites:
- **Rust & Cargo** (1.75+)
- Clipboard tools:
  ```bash
  # Fedora / RHEL
  sudo dnf install wl-clipboard libnotify xdg-utils
  
  # Ubuntu / Debian
  sudo apt install wl-clipboard libnotify-bin xdg-utils xclip
  
  # Arch Linux
  sudo pacman -S wl-clipboard libnotify xdg-utils
  ```

Build and install:
```bash
git clone https://github.com/1171Abhishek/Sync-Project.git
cd Sync-Project/clip-sync-rust
cargo build --release
sudo cp target/release/clip-sync /usr/local/bin/
clip-sync setup       # Registers GNOME shortcut (Ctrl+Alt+P) & Nautilus right-click script
clip-sync             # Start daemon
```

#### Install via Native Packages (RPM / DEB / Arch)
Package build scripts are included in the repository root:
```bash
./build-rpm.sh    # Fedora / RHEL / openSUSE -> dist/clip-sync-*.x86_64.rpm
./build-deb.sh    # Ubuntu / Debian / Mint   -> dist/clip-sync_*_amd64.deb
./build-arch.sh   # Arch / Manjaro           -> dist/clip-sync-*-x86_64.pkg.tar.zst
```

Enable and start the user systemd service:
```bash
systemctl --user enable --now clip-sync
```

---

### 2. Android Setup

1. Download `app-release.apk` from the latest [GitHub Releases](https://github.com/1171Abhishek/Sync-Project/releases) or the GitHub Actions build artifact.
2. Install the APK:
   - On phone: Open the downloaded APK and tap **"More details" ➔ "Install anyway"**.
   - Or via ADB:
     ```bash
     adb install -r android/app/build/outputs/apk/release/app-release.apk
     ```
3. Open **ClipSync** on your phone and enable:
   - **Accessibility Service:** For real-time clipboard sync and OTP capture across Android 10+.
   - **Notification Access:** For mirroring notifications and 2FA alerts to Linux.
   - **Battery Optimization Exemption:** Ensures background connectivity over Wi-Fi.

---

## ⌨️ CLI Command Reference

| Command | Action |
|---|---|
| `clip-sync` | Start the background daemon |
| `clip-sync devices` | List all discovered devices with `[ACTIVE]` and `[Standby]` status |
| `clip-sync switch <name\|index>` | Switch the active Android target device |
| `clip-sync battery` | Check active phone battery percentage & charging status |
| `clip-sync send <file\|url>` | Send any local file, image, or URL link to active phone |
| `clip-sync send-link [url]` | Send copied link or tab to active phone |
| `clip-sync setup` | Re-register GNOME shortcut (`Ctrl+Alt+P`) and Nautilus script |
| `clip-sync pair [pin]` | Inspect or update the AES-256-GCM pairing PIN |

---

## 📡 Network Architecture & Ports

ClipSync operates strictly on your local area network (LAN):

| Port | Protocol | Description |
|---|---|---|
| `9876` | UDP | Device auto-discovery beacons (`CLIP_SYNC:<id>:<name>:<os>`) & active probes |
| `9877` | TCP | E2E encrypted clipboard sync, notifications, and URLs |
| `9878` | HTTP/TCP | HMAC-authenticated P2P file drop receiver |

---

## 🛠️ Repository Structure

```
Sync-Project/
├── clip-sync-rust/          # Linux daemon and CLI (Rust)
│   ├── src/main.rs          # CLI entry point and daemon runner
│   ├── src/discovery.rs     # UDP beacon discovery & OS filtering
│   ├── src/node.rs          # Encrypted TCP connection & FIFO active routing
│   ├── src/file_server.rs   # P2P HTTP file transfer server
│   ├── src/url_handler.rs   # Browser & desktop portal URL launcher
│   └── tests/               # Integration tests (active_device_test.rs)
├── android/                 # Native Android application
│   ├── app/src/main/        # Java source, Manifest, layouts & resources
│   └── build.gradle         # Android build config (v0.0.11 / versionCode 11)
├── .github/workflows/       # GitHub Actions CI/CD (signed APK & native packages)
├── packaging/               # Systemd services, desktop entries, packaging specs
├── build-rpm.sh             # Linux RPM builder
├── build-deb.sh             # Linux DEB builder
└── build-arch.sh            # Linux Arch package builder
```

---

## 📄 License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)**. See the [LICENSE](LICENSE) file for details.
