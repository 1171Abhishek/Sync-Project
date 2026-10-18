// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

use crate::clipboard::ClipboardManager;
use crate::crypto::CryptoManager;
use crate::discovery::discover_phone_ip;
use crate::notifications::send_desktop_notification;
use crate::types::{SyncMessage, TCP_PORT};
use crate::utils::{cmd_exists, extract_url};
use std::process::Command;
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

    if cmd_exists("xdg-open") {
        Command::new("xdg-open")
            .arg("--")
            .arg(clean)
            .spawn()
            .map_err(|e| format!("Failed to spawn xdg-open: {}", e))?;
        return Ok(());
    }
    Err("xdg-open utility not found".to_string())
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

pub async fn send_url_to_phone(url_str: &str) {
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

    println!("[Discovery] Looking for connected phone on Wi-Fi...");
    let phone_ip = match discover_phone_ip().await {
        Some(ip) => ip,
        None => {
            eprintln!("[Error] No phone found on local Wi-Fi!");
            eprintln!("-> Make sure your phone is connected to the same Wi-Fi and ClipSync is running.");
            send_desktop_notification(
                "ClipSync Send Failed",
                "No phone found on local Wi-Fi",
                "dialog-warning",
                "normal",
            );
            std::process::exit(1);
        }
    };

    let target_addr = format!("{}:{}", phone_ip, TCP_PORT);
    let mut stream = match tokio::time::timeout(Duration::from_secs(3), TcpStream::connect(&target_addr)).await {
        Ok(Ok(s)) => s,
        _ => {
            eprintln!("[Error] Failed to connect to phone at {}", target_addr);
            send_desktop_notification(
                "ClipSync Send Failed",
                &format!("Failed to connect to phone at {}", target_addr),
                "dialog-warning",
                "normal",
            );
            std::process::exit(1);
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
        eprintln!("[Error] Failed to send URL to phone: {}", e);
        send_desktop_notification(
            "ClipSync Send Failed",
            &format!("Failed to send URL: {}", e),
            "dialog-warning",
            "normal",
        );
        std::process::exit(1);
    }

    // Brief flush delay so OS TCP stack delivers before socket close
    sleep(Duration::from_millis(150)).await;

    println!("[OK] Sent link to Phone ({})! Opened in browser: {}", phone_ip, clean_url);
    send_desktop_notification("Opening Tab on Phone", &clean_url, "web-browser", "normal");
}

pub async fn send_clipboard_link_to_phone(clipboard: &ClipboardManager) {
    if let Some(text) = clipboard.read_clipboard() {
        if let Some(url) = extract_url(&text) {
            send_url_to_phone(&url).await;
            return;
        }
    }

    // Try primary selection if Wayland or X11
    if cmd_exists("wl-paste") {
        if let Ok(out) = Command::new("wl-paste").args(["-p", "-n"]).output() {
            if out.status.success() {
                let s = String::from_utf8_lossy(&out.stdout);
                if let Some(url) = extract_url(&s) {
                    send_url_to_phone(&url).await;
                    return;
                }
            }
        }
    } else if cmd_exists("xclip") {
        if let Ok(out) = Command::new("xclip").args(["-selection", "primary", "-o"]).output() {
            if out.status.success() {
                let s = String::from_utf8_lossy(&out.stdout);
                if let Some(url) = extract_url(&s) {
                    send_url_to_phone(&url).await;
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
