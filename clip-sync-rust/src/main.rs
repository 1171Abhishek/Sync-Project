// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

mod clipboard;
mod crypto;
mod discovery;
mod file_server;
mod integration;
mod node;
mod notifications;
mod types;
mod url_handler;
mod utils;

use crypto::CryptoManager;
use discovery::{refresh_active_peers, DiscoveryManager};
use file_server::{send_file_to_phone, start_file_server};
use integration::ensure_desktop_integrations;
use node::SyncNode;
use std::fs;
use std::path::Path;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::time::sleep;
use types::{
    get_active_peers_list, get_battery_file, BatteryInfo, SyncMessage, BATTERY_FILE, FILE_PORT,
    TCP_PORT,
};
use url_handler::{send_clipboard_link_to_phone, send_url_to_phone};
use utils::get_downloads_dir;

fn init_environment() {
    let runtime_dir = std::env::var("XDG_RUNTIME_DIR").unwrap_or_else(|_| {
        let uid = std::process::Command::new("id")
            .arg("-u")
            .output()
            .ok()
            .and_then(|o| String::from_utf8(o.stdout).ok())
            .map(|s| s.trim().to_string())
            .unwrap_or_else(|| "1000".to_string());
        let candidate = format!("/run/user/{}", uid);
        if Path::new(&candidate).is_dir() {
            std::env::set_var("XDG_RUNTIME_DIR", &candidate);
            candidate
        } else {
            String::new()
        }
    });

    if std::env::var("WAYLAND_DISPLAY").is_err() && !runtime_dir.is_empty() {
        for i in 0..4 {
            let socket = format!("{}/wayland-{}", runtime_dir, i);
            if Path::new(&socket).exists() {
                std::env::set_var("WAYLAND_DISPLAY", format!("wayland-{}", i));
                break;
            }
        }
    }

    // Only set DISPLAY if not already running pure Wayland to avoid waking XWayland unnecessarily
    if std::env::var("DISPLAY").is_err() && std::env::var("WAYLAND_DISPLAY").is_err() {
        std::env::set_var("DISPLAY", ":0");
    }

    if std::env::var("DBUS_SESSION_BUS_ADDRESS").is_err() && !runtime_dir.is_empty() {
        let bus_path = format!("{}/bus", runtime_dir);
        if Path::new(&bus_path).exists() {
            std::env::set_var("DBUS_SESSION_BUS_ADDRESS", format!("unix:path={}", bus_path));
        }
    }
}


fn read_battery_info() -> Option<BatteryInfo> {
    for path in [get_battery_file(), std::path::PathBuf::from(BATTERY_FILE)] {
        if let Ok(data) = fs::read(&path) {
            if let Ok(info) = serde_json::from_slice::<BatteryInfo>(&data) {
                return Some(info);
            }
        }
    }
    None
}

async fn show_connected_devices(force_refresh: bool) {
    let mut active_peers = get_active_peers_list();
    if force_refresh || active_peers.is_empty() {
        println!("[Refresh] Scanning local Wi-Fi for ClipSync devices in real time...");
        active_peers = refresh_active_peers(800).await;
    }

    if active_peers.is_empty() {
        println!("[Devices] Connected Devices: 0");
        println!("   No active peers connected. Ensure devices are on the same Wi-Fi.");
        return;
    }

    println!("[Devices] Connected Devices ({}):", active_peers.len());
    for (i, peer) in active_peers.iter().enumerate() {
        let name = peer.device_name.as_deref().unwrap_or("Phone");
        println!("   {}. * {} ({}) [Live]", i + 1, name, peer.ip);
    }
}

fn show_phone_status() {
    let active_peers = get_active_peers_list();
    let peer_count = active_peers.len();

    if peer_count == 0 {
        println!("[Status] Disconnected (0 devices connected)");
        println!("   Waiting for Phone on local Wi-Fi...");
        if let Some(info) = read_battery_info() {
            let now_sec = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs() as i64;
            let diff = (now_sec - info.updated_at).max(0);
            println!("   Last known phone battery: {}% ({}s ago)", info.level, diff);
        }
        return;
    }

    if peer_count == 1 {
        let name = active_peers[0].device_name.as_deref().unwrap_or("Phone");
        println!("[Connected] Connected Device: {} ({})", name, active_peers[0].ip);
    } else {
        let summaries: Vec<String> = active_peers
            .iter()
            .map(|p| format!("{} ({})", p.device_name.as_deref().unwrap_or("Device"), p.ip))
            .collect();
        println!("[Connected] Connected Devices ({}): {}", peer_count, summaries.join(", "));
    }

    if let Some(info) = read_battery_info() {
        let charge_status = if info.is_charging { "Charging" } else { "Discharging" };
        let now_sec = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs() as i64;
        let diff = (now_sec - info.updated_at).max(0);
        println!("[Battery] Phone Battery: {}% [{}]", info.level, charge_status);
        println!("Last Updated: {}s ago", diff);
        return;
    }

    println!("[Device] Device connected, waiting for battery update...");
}

async fn send_file_or_url(target: &str, device_filter: Option<&str>) {
    let clean = target.trim();
    if clean.starts_with("http://")
        || clean.starts_with("https://")
        || clean.starts_with("www.")
        || (!Path::new(clean).exists() && clean.contains('.'))
    {
        send_url_to_phone(clean, device_filter).await;
    } else {
        send_file_to_phone(Path::new(clean), device_filter).await;
    }
}

async fn run_battery_test() {
    let node = SyncNode::new();
    println!("[Test] Testing Phone Battery Desktop Alerts...");
    println!("1. Simulating Low Battery (15%)...");
    node.broadcast_message(SyncMessage {
        msg_type: Some("battery".to_string()),
        device_id: "test-phone".to_string(),
        battery_level: Some(15),
        is_charging: Some(false),
        battery_status: Some("low".to_string()),
        ..Default::default()
    })
    .await;
    sleep(Duration::from_secs(2)).await;

    println!("2. Simulating Full Battery (100%)...");
    node.broadcast_message(SyncMessage {
        msg_type: Some("battery".to_string()),
        device_id: "test-phone".to_string(),
        battery_level: Some(100),
        is_charging: Some(true),
        battery_status: Some("full".to_string()),
        ..Default::default()
    })
    .await;
    println!("[OK] Done! Check your desktop notifications and run 'clip-sync battery'.");
}

async fn run_call_test(caller_arg: Option<String>) {
    let node = SyncNode::new();
    let caller = caller_arg.unwrap_or_else(|| "Abhishek Kumar".to_string());
    let phone = "+91 98765 43210".to_string();

    println!("[Test] [1/3] Simulating Incoming Call from: {} ({})...", caller, phone);
    node.broadcast_message(SyncMessage {
        msg_type: Some("call".to_string()),
        device_id: "test-phone".to_string(),
        call_state: Some("ringing".to_string()),
        caller_name: Some(caller.clone()),
        phone_number: Some(phone.clone()),
        ..Default::default()
    })
    .await;

    println!("   -> Ringing alert shown on desktop. Waiting 4 seconds before answering/ending...");
    sleep(Duration::from_secs(4)).await;

    println!("[Test] [2/3] Simulating Call Answered / Ended...");
    node.broadcast_message(SyncMessage {
        msg_type: Some("call".to_string()),
        device_id: "test-phone".to_string(),
        call_state: Some("ended".to_string()),
        caller_name: Some(caller.clone()),
        phone_number: Some(phone.clone()),
        ..Default::default()
    })
    .await;
    println!("   -> Ringing alert automatically closed.");
    sleep(Duration::from_secs(2)).await;

    println!("[Test] [3/3] Simulating Missed Call from: {}...", caller);
    node.broadcast_message(SyncMessage {
        msg_type: Some("call".to_string()),
        device_id: "test-phone".to_string(),
        call_state: Some("missed".to_string()),
        caller_name: Some(caller),
        phone_number: Some(phone),
        ..Default::default()
    })
    .await;
    println!("   -> Missed call notification shown.");
    println!("[OK] Call simulation complete!");
}

#[tokio::main]
async fn main() {
    init_environment();

    let args: Vec<String> = std::env::args().collect();
    if args.len() > 1 {
        let arg = &args[1];

        if arg == "battery" || arg == "status" {
            show_phone_status();
            return;
        }

        if arg == "refresh" || arg == "scan" {
            println!("[Refresh] Scanning local Wi-Fi for ClipSync devices in real time...");
            let start = std::time::Instant::now();
            let peers = refresh_active_peers(800).await;
            let elapsed = start.elapsed();
            if peers.is_empty() {
                println!("[Refresh] 0 devices discovered ({:.2}s). Ensure devices are on the same Wi-Fi.", elapsed.as_secs_f32());
            } else {
                println!("[Refresh] Found {} active device(s) ({:.2}s):", peers.len(), elapsed.as_secs_f32());
                for (i, peer) in peers.iter().enumerate() {
                    let name = peer.device_name.as_deref().unwrap_or("Phone");
                    println!("   {}. 📱 {} ({}) [Live]", i + 1, name, peer.ip);
                }
                println!("[OK] Active device list updated in real time.");
            }
            return;
        }

        if arg == "devices" || arg == "peers" {
            let force = args.iter().any(|a| a == "--refresh" || a == "-r" || a == "refresh");
            show_connected_devices(force).await;
            return;
        }

        if arg == "test-battery" {
            run_battery_test().await;
            return;
        }

        if arg == "test-call" {
            let caller = args.get(2).cloned();
            run_call_test(caller).await;
            return;
        }

        if arg == "pair" {
            let mut crypto = CryptoManager::load_or_create();
            if args.len() >= 3 {
                crypto.set_pin(&args[2]);
                println!("[Security] Security PIN updated to: {}", args[2]);
            } else {
                println!("[Security] Security Status: 100% Automatic End-to-End Encrypted (AES-256-GCM)");
                println!("[OK] ClipSync pairs and encrypts all traffic automatically out-of-the-box.");
                println!("[OK] Current Pairing Key: Active ({})", crypto.get_pin());
            }
            return;
        }

        if arg == "setup" || arg == "install-shortcuts" {
            println!("[Setup] Configuring ClipSync Desktop Shortcuts & Integrations...");
            ensure_desktop_integrations(true);
            println!("[OK] Done! Press [Ctrl + Alt + P] to send link to Phone.");
            println!("[OK] Right-click any file in Nautilus -> Scripts -> 'Send to Phone (ClipSync)'.");
            return;
        }

        if arg == "send-link" {
            let device_arg = if args.len() >= 4 { Some(args[3].as_str()) } else { None };
            if args.len() >= 3 && !args[2].starts_with('-') {
                send_url_to_phone(&args[2], device_arg).await;
            } else {
                let clipboard = clipboard::ClipboardManager::new();
                send_clipboard_link_to_phone(&clipboard, device_arg).await;
            }
            return;
        }

        if arg == "send"
            || Path::new(arg).exists()
            || arg.starts_with("http://")
            || arg.starts_with("https://")
            || arg.starts_with("www.")
        {
            let (target, device_arg) = if arg == "send" {
                if args.len() < 3 {
                    eprintln!("Usage: clip-sync send <file|url> [target_device]");
                    std::process::exit(1);
                }
                let dev = if args.len() >= 4 { Some(args[3].as_str()) } else { None };
                (&args[2], dev)
            } else {
                let dev = if args.len() >= 3 { Some(args[2].as_str()) } else { None };
                (arg, dev)
            };
            send_file_or_url(target, device_arg).await;
            return;
        }

        if arg == "version" || arg == "-v" || arg == "--version" {
            println!("ClipSync v0.0.10 (Rust native build)");
            return;
        }

        if arg == "-h" || arg == "--help" || arg == "help" {
            println!("ClipSync v0.0.10 (Rust Edition) - Seamless Linux <-> Android Continuity");
            println!("Usage:");
            println!("  clip-sync                 Start background daemon");
            println!("  clip-sync version         Print ClipSync version");
            println!("  clip-sync battery         Check connected phone battery percentage & status");
            println!("  clip-sync devices [-r]    Show active connected peers (optional -r to force refresh)");
            println!("  clip-sync refresh         Scan local Wi-Fi and refresh connected devices in real time");
            println!("  clip-sync test-battery    Simulate phone low battery & full charge alerts");
            println!("  clip-sync test-call [name] Simulate incoming call, auto-dismiss, and missed call");
            println!("  clip-sync send <file|url> [device] Send image, file, or URL link to device(s)");
            println!("  clip-sync send-link [url] [device] Send copied/highlighted link to device(s) (Ctrl+Alt+P)");
            println!("  clip-sync pair [pin]      View or set the 6-digit security pairing PIN");
            println!("  clip-sync setup           Configure GNOME shortcut & Nautilus right-click script");
            return;
        }
    }

    // Automatically configure desktop integrations (GNOME shortcut, Nautilus script/extension, .desktop entry)
    ensure_desktop_integrations(false);

    let node = SyncNode::new();
    println!("ClipSync (Rust Edition) started!");
    println!("[Device] Device ID: {}", node.device_id);
    println!("[Network] Clipboard & Notification Sync listening on TCP :{}", TCP_PORT);
    println!(
        "[Files] File Drop receiver listening on HTTP :{} (saving to {:?})",
        FILE_PORT,
        get_downloads_dir()
    );
    println!("[Security] Security: 100% Automatic End-to-End Encrypted (AES-256-GCM)");
    println!("[Shortcut] Global Shortcut: [Ctrl + Alt + P] (press anywhere to send link to phone)");
    println!("[File Manager] Right-click file -> Scripts -> 'Send to Phone (ClipSync)'");

    let n_tcp = node.clone();
    tokio::spawn(async move {
        n_tcp.start_tcp_server().await;
    });

    tokio::spawn(async move {
        start_file_server().await;
    });

    let discovery = Arc::new(DiscoveryManager::new(
        node.device_id.clone(),
        node.device_name.clone(),
    ));

    let n_disc = node.clone();
    discovery
        .start_udp_discovery(move |peer_ip| {
            let n = n_disc.clone();
            tokio::spawn(async move {
                n.connect_to_peer(peer_ip).await;
            });
        })
        .await;

    // Start clipboard watcher with event broadcast
    let n_clip = node.clone();
    node.clipboard
        .start_watcher(move |content, hash| {
            let n = n_clip.clone();
            async move {
                let clean_preview: String = content
                    .chars()
                    .filter(|c| !c.is_control() || *c == ' ')
                    .take(40)
                    .collect();
                println!(
                    "[Clipboard] Local clipboard changed ({} bytes): {}...",
                    content.len(),
                    clean_preview
                );
                n.broadcast_clipboard(content, hash).await;
            }
        })
        .await;

    // Keep daemon alive
    loop {
        sleep(Duration::from_secs(3600)).await;
    }
}
