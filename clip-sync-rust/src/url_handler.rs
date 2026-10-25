// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

use crate::clipboard::ClipboardManager;
use crate::crypto::CryptoManager;
use crate::discovery::select_target_devices;
use crate::notifications::send_desktop_notification;
use crate::types::{SyncMessage, TCP_PORT};
use crate::utils::{cmd_exists, extract_url};
use std::process::{Command, Stdio};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::io::AsyncWriteExt;
use tokio::net::TcpStream;
use tokio::time::sleep;

pub fn open_browser_tab(url_str: &str) -> Result<(), String> {
    let clean = url_str.trim();
    if clean.starts_with('-') {
        return Err("Invalid URL: argument begins with dash".to_string());
    }

    if !clean.starts_with("http://") && !clean.starts_with("https://") {
        return Err("Invalid URL scheme: only http and https are allowed".to_string());
    }

    if let Ok(parsed) = url::Url::parse(clean) {
        if parsed.scheme() != "http" && parsed.scheme() != "https" {
            return Err("Disallowed URL scheme".to_string());
        }
        if parsed.host().is_none() {
            return Err("Missing URL host".to_string());
        }
    } else {
        return Err("Malformed URL".to_string());
    }

    // 1. Preferred modern Linux approach: XDG Desktop Portal via busctl
    // Delegates opening to the user's active graphical session, bypassing systemd cgroup & browser zygote IPC pipe issues.
    if cmd_exists("busctl") {
        let status = Command::new("busctl")
            .args([
                "--user",
                "call",
                "org.freedesktop.portal.Desktop",
                "/org/freedesktop/portal/desktop",
                "org.freedesktop.portal.OpenURI",
                "OpenURI",
                "ssa{sv}",
                "",
                clean,
                "0",
            ])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();

        if let Ok(s) = status {
            if s.success() {
                return Ok(());
            }
        }
    }

    // 2. gio open with detached stdio
    if cmd_exists("gio") {
        let res = Command::new("gio")
            .args(["open", clean])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn();
        if res.is_ok() {
            return Ok(());
        }
    }

    // 3. xdg-open with detached stdio
    if cmd_exists("xdg-open") {
        let res = Command::new("xdg-open")
            .arg(clean)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn();
        if res.is_ok() {
            return Ok(());
        }
    }

    // 4. Direct browser binary fallbacks
    for b in &["brave-origin-stable", "brave-browser", "brave", "google-chrome", "firefox", "chromium"] {
        if cmd_exists(b) {
            let res = Command::new(b)
                .arg(clean)
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn();
            if res.is_ok() {
                return Ok(());
            }
        }
    }

    Err("No browser launcher found (portal / gio / xdg-open)".to_string())
}

pub async fn handle_url(msg: &SyncMessage, clipboard: &ClipboardManager) {
    let mut target_url = msg.url.clone().unwrap_or_default();
    if target_url.is_empty() {
        if let Some(ref content) = msg.content {
            if let Some(u) = extract_url(content) {
                target_url = u;
            }
        }
    }

    if target_url.is_empty() {
        return;
    }

    // 1. Copy URL to Linux clipboard
    let _ = clipboard.write_clipboard(&target_url).await;

    // 2. Open as a new tab in the default browser
    if let Err(e) = open_browser_tab(&target_url) {
        eprintln!("[Warning] Failed to open browser tab: {}", e);
    } else {
        println!("[URL] Opened new browser tab from {}: {}", msg.device_id, target_url);
    }

    // 3. Desktop notification
    send_desktop_notification(
        "Tab Opened from Phone",
        &target_url,
        "web-browser",
        "normal",
    );
}

pub async fn send_url_to_phone(url_str: &str, device_filter: Option<&str>) {
    let clean_url = match extract_url(url_str) {
        Some(u) => u,
        None => {
            let t = url_str.trim();
            if !t.starts_with("http://") && !t.starts_with("https://") {
                format!("https://{}", t)
            } else {
                t.to_string()
            }
        }
    };

    println!("[Discovery] Looking for connected device(s) on Wi-Fi...");
    let targets = select_target_devices(device_filter).await;
    if targets.is_empty() {
        eprintln!("[Error] No device found or operation cancelled.");
        send_desktop_notification(
            "ClipSync Send Cancelled",
            "No device selected or reachable on local Wi-Fi",
            "dialog-warning",
            "normal",
        );
        std::process::exit(1);
    }

    let mut success_count = 0;
    for target in &targets {
        let phone_ip = &target.ip;
        let target_name = target.device_name.as_deref().unwrap_or("Phone");
        let target_addr = format!("{}:{}", phone_ip, TCP_PORT);

        let mut stream = match tokio::time::timeout(
            Duration::from_secs(3),
            TcpStream::connect(&target_addr),
        )
        .await
        {
            Ok(Ok(s)) => s,
            _ => {
                eprintln!("[Error] Failed to connect to {} at {}", target_name, target_addr);
                send_desktop_notification(
                    "ClipSync Send Failed",
                    &format!("Failed to connect to {} at {}", target_name, target_addr),
                    "dialog-warning",
                    "normal",
                );
                continue;
            }
        };

        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs() as i64;

        let inner_msg = SyncMessage {
            msg_type: Some("url".to_string()),
            device_id: "linux-cli".to_string(),
            url: Some(clean_url.clone()),
            content: Some(clean_url.clone()),
            timestamp: Some(now),
            ..Default::default()
        };

        let inner_data = serde_json::to_vec(&inner_msg).unwrap_or_default();
        let crypto = CryptoManager::load_or_create();

        let wire_data = match crypto.encrypt(&inner_data) {
            Ok(payload) => {
                let enc_msg = SyncMessage {
                    msg_type: Some("encrypted".to_string()),
                    device_id: "linux-cli".to_string(),
                    payload: Some(payload),
                    ..Default::default()
                };
                serde_json::to_vec(&enc_msg).unwrap_or(inner_data)
            }
            Err(_) => inner_data,
        };

        let mut final_payload = wire_data;
        final_payload.push(b'\n');

        if let Err(e) = stream.write_all(&final_payload).await {
            eprintln!("[Error] Failed to send URL to {}: {}", target_name, e);
            send_desktop_notification(
                "ClipSync Send Failed",
                &format!("Failed to send URL to {}: {}", target_name, e),
                "dialog-warning",
                "normal",
            );
            continue;
        }

        let _ = stream.flush().await;
        let _ = stream.shutdown().await;
        sleep(Duration::from_millis(350)).await;

        println!(
            "[OK] Sent link to {} ({})! Opened in browser: {}",
            target_name, phone_ip, clean_url
        );
        send_desktop_notification(
            &format!("Opening Tab on {}", target_name),
            &clean_url,
            "web-browser",
            "normal",
        );
        success_count += 1;
    }

    if success_count == 0 {
        std::process::exit(1);
    }
}

pub async fn send_clipboard_link_to_phone(clipboard: &ClipboardManager, device_filter: Option<&str>) {
    if let Some(text) = clipboard.read_clipboard() {
        if let Some(url) = extract_url(&text) {
            send_url_to_phone(&url, device_filter).await;
            return;
        }
    }

    // Try primary selection if Wayland or X11
    if cmd_exists("wl-paste") {
        if let Ok(out) = Command::new("wl-paste").args(["-p", "-n"]).output() {
            if out.status.success() {
                let s = String::from_utf8_lossy(&out.stdout);
                if let Some(url) = extract_url(&s) {
                    send_url_to_phone(&url, device_filter).await;
                    return;
                }
            }
        }
    } else if cmd_exists("xclip") {
        if let Ok(out) = Command::new("xclip").args(["-selection", "primary", "-o"]).output() {
            if out.status.success() {
                let s = String::from_utf8_lossy(&out.stdout);
                if let Some(url) = extract_url(&s) {
                    send_url_to_phone(&url, device_filter).await;
                    return;
                }
            }
        }
    }

    eprintln!("[Error] No valid link found in your clipboard or active selection!");
    println!("-> Copy a link with [Ctrl + C] or highlight a URL, then try again.");
    send_desktop_notification(
        "ClipSync: No Link Found",
        "Copy or highlight a URL link first, then press Ctrl+Alt+P.",
        "dialog-information",
        "low",
    );
}
