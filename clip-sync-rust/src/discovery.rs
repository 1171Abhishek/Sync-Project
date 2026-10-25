// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

use crate::types::{
    get_active_peers_file, get_active_peers_list, get_peer_file, get_selected_target,
    get_target_file, secure_write_file, set_selected_target, PeerDevice, ACTIVE_PEERS_FILE,
    FILE_PORT, PEER_FILE, TARGET_FILE, TCP_PORT, UDP_PORT,
};
use crate::utils::{cmd_exists, is_local_ip};
use std::fs;
use std::io::{self, Write};
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;
use tokio::net::{TcpStream, UdpSocket};
use tokio::time::sleep;

pub fn get_arp_neighbors() -> Vec<String> {
    let mut neighbors = Vec::new();
    if let Ok(content) = fs::read_to_string("/proc/net/arp") {
        for (i, line) in content.lines().enumerate() {
            if i == 0 {
                continue; // skip header
            }
            let fields: Vec<&str> = line.split_whitespace().collect();
            if !fields.is_empty() {
                let ip = fields[0].trim().to_string();
                if fields.len() > 3 && fields[3] == "00:00:00:00:00:00" {
                    continue; // incomplete entry
                }
                if !is_local_ip(&ip) && ip != "0.0.0.0" && !neighbors.contains(&ip) {
                    neighbors.push(ip);
                }
            }
        }
    }
    neighbors
}

pub struct DiscoveryManager {
    device_id: String,
    device_name: String,
}

impl DiscoveryManager {
    pub fn new(device_id: String, device_name: String) -> Self {
        Self {
            device_id,
            device_name,
        }
    }

    pub async fn start_udp_discovery<F>(self: Arc<Self>, on_peer_discovered: F)
    where
        F: Fn(String) + Send + Sync + 'static,
    {
        let this_broadcaster = self.clone();
        tokio::spawn(async move {
            let socket = match UdpSocket::bind("0.0.0.0:0").await {
                Ok(s) => s,
                Err(_) => return,
            };
            let _ = socket.set_broadcast(true);

            let beacon = format!("CLIP_SYNC:{}:{}", this_broadcaster.device_id, this_broadcaster.device_name);
            let b_addr: SocketAddr = format!("255.255.255.255:{}", UDP_PORT).parse().unwrap();

            loop {
                let _ = socket.send_to(beacon.as_bytes(), b_addr).await;

                // Also send unicast to ARP neighbors (handles Wi-Fi router AP isolation)
                for neighbor in get_arp_neighbors() {
                    if let Ok(addr) = format!("{}:{}", neighbor, UDP_PORT).parse::<SocketAddr>() {
                        let _ = socket.send_to(beacon.as_bytes(), addr).await;
                    }
                }

                sleep(Duration::from_secs(4)).await;
            }
        });

        // UDP Listener
        let this_listener = self.clone();
        tokio::spawn(async move {
            let bind_addr: SocketAddr = format!("0.0.0.0:{}", UDP_PORT).parse().unwrap();
            let socket = match UdpSocket::bind(bind_addr).await {
                Ok(s) => s,
                Err(e) => {
                    eprintln!("[Warning] UDP Discovery bind error on {}: {}", bind_addr, e);
                    return;
                }
            };

            let mut buf = [0u8; 1024];
            loop {
                if let Ok((len, remote_addr)) = socket.recv_from(&mut buf).await {
                    let msg = String::from_utf8_lossy(&buf[..len]);
                    if let Some(payload) = msg.strip_prefix("CLIP_SYNC:") {
                        let parts: Vec<&str> = payload.split(':').collect();
                        let peer_id = parts[0];
                        let remote_ip = remote_addr.ip().to_string().trim_start_matches("::ffff:").to_string();

                        if is_local_ip(&remote_ip) {
                            continue;
                        }

                        // Respond to probe/ping immediately
                        if peer_id == "probe" || peer_id == "ping" {
                            let reply = format!("CLIP_SYNC:{}:{}", this_listener.device_id, this_listener.device_name);
                            let _ = socket.send_to(reply.as_bytes(), remote_addr).await;
                            continue;
                        }

                        if peer_id != this_listener.device_id {
                            on_peer_discovered(remote_ip);
                        }
                    }
                }
            }
        });
    }
}

pub async fn refresh_active_peers(timeout_ms: u64) -> Vec<PeerDevice> {
    let mut discovered: Vec<PeerDevice> = Vec::new();
    let mut seen_ips = std::collections::HashSet::new();

    // 1. Fast check currently cached peers
    for peer in get_active_peers_list() {
        if is_local_ip(&peer.ip) {
            continue;
        }
        let addr_tcp = format!("{}:{}", peer.ip, TCP_PORT);
        let addr_file = format!("{}:{}", peer.ip, FILE_PORT);
        let is_alive = tokio::time::timeout(Duration::from_millis(300), TcpStream::connect(&addr_tcp)).await.is_ok_and(|r| r.is_ok())
            || tokio::time::timeout(Duration::from_millis(300), TcpStream::connect(&addr_file)).await.is_ok_and(|r| r.is_ok());

        if is_alive {
            seen_ips.insert(peer.ip.clone());
            discovered.push(peer);
        }
    }

    // 2. Active UDP probe burst to broadcast & ARP neighbors
    if let Ok(socket) = UdpSocket::bind("0.0.0.0:0").await {
        let _ = socket.set_broadcast(true);
        let beacon = b"CLIP_SYNC:probe";
        let b_addr: SocketAddr = format!("255.255.255.255:{}", UDP_PORT).parse().unwrap();

        for _ in 0..2 {
            let _ = socket.send_to(beacon, b_addr).await;
            for neighbor in get_arp_neighbors() {
                if let Ok(addr) = format!("{}:{}", neighbor, UDP_PORT).parse::<SocketAddr>() {
                    let _ = socket.send_to(beacon, addr).await;
                }
            }
            sleep(Duration::from_millis(40)).await;
        }

        let mut buf = [0u8; 1024];
        let end_time = tokio::time::Instant::now() + Duration::from_millis(timeout_ms);
        while tokio::time::Instant::now() < end_time {
            let remaining = end_time.saturating_duration_since(tokio::time::Instant::now());
            if remaining.is_zero() {
                break;
            }
            if let Ok(Ok((len, remote_addr))) = tokio::time::timeout(remaining, socket.recv_from(&mut buf)).await {
                let msg = String::from_utf8_lossy(&buf[..len]);
                if let Some(payload) = msg.strip_prefix("CLIP_SYNC:") {
                    let ip = remote_addr.ip().to_string().trim_start_matches("::ffff:").to_string();
                    if !is_local_ip(&ip) && !seen_ips.contains(&ip) {
                        let parts: Vec<&str> = payload.split(':').collect();
                        let dev_id = parts.first().filter(|s| !s.is_empty()).map(|s| s.to_string());
                        let dev_name = parts.get(1).filter(|s| !s.is_empty()).map(|s| s.to_string()).or_else(|| Some("Phone".to_string()));

                        seen_ips.insert(ip.clone());
                        discovered.push(PeerDevice {
                            ip,
                            device_id: dev_id,
                            device_name: dev_name,
                        });
                    }
                }
            } else {
                break;
            }
        }
    }

    // 3. Parallel quick check on ARP neighbors that might filter UDP broadcast
    let mut check_handles = Vec::new();
    for ip in get_arp_neighbors() {
        if !seen_ips.contains(&ip) && !is_local_ip(&ip) {
            let check_ip = ip.clone();
            check_handles.push(tokio::spawn(async move {
                let addr_tcp = format!("{}:{}", check_ip, TCP_PORT);
                if let Ok(Ok(_)) = tokio::time::timeout(Duration::from_millis(300), TcpStream::connect(&addr_tcp)).await {
                    return Some(check_ip);
                }
                let addr_file = format!("{}:{}", check_ip, FILE_PORT);
                if let Ok(Ok(_)) = tokio::time::timeout(Duration::from_millis(300), TcpStream::connect(&addr_file)).await {
                    return Some(check_ip);
                }
                None
            }));
        }
    }

    for handle in check_handles {
        if let Ok(Some(found_ip)) = handle.await {
            if !seen_ips.contains(&found_ip) {
                seen_ips.insert(found_ip.clone());
                discovered.push(PeerDevice {
                    ip: found_ip,
                    device_id: None,
                    device_name: Some("Phone".to_string()),
                });
            }
        }
    }

    // Persist discovered devices
    if let Ok(data) = serde_json::to_vec(&discovered) {
        let _ = secure_write_file(get_active_peers_file(), &data);
        let _ = secure_write_file(ACTIVE_PEERS_FILE, &data);
    }
    if !discovered.is_empty() {
        let current_target = get_selected_target();
        let target_to_save = if let Some(ref curr) = current_target {
            if curr.eq_ignore_ascii_case("all") || discovered.iter().any(|p| p.ip == *curr) {
                curr.clone()
            } else {
                discovered[0].ip.clone()
            }
        } else {
            discovered[0].ip.clone()
        };
        let _ = set_selected_target(&target_to_save);
    } else {
        let _ = std::fs::remove_file(get_peer_file());
        let _ = std::fs::remove_file(PEER_FILE);
        let _ = std::fs::remove_file(get_target_file());
        let _ = std::fs::remove_file(TARGET_FILE);
    }

    discovered
}

#[allow(dead_code)]
pub async fn discover_phone_ip() -> Option<String> {
    let peers = refresh_active_peers(800).await;
    peers.first().map(|p| p.ip.clone())
}

pub async fn switch_active_device(target_arg: Option<&str>) {
    let mut peers = get_active_peers_list();
    if peers.is_empty() {
        println!("[Refresh] Discovering ClipSync devices on local Wi-Fi...");
        peers = refresh_active_peers(800).await;
    }

    if peers.is_empty() {
        println!("[Switch] No devices found on the local network.");
        println!("   Ensure your phone or PC has ClipSync running and is on the same Wi-Fi.");
        return;
    }

    if let Some(arg) = target_arg {
        let clean = arg.trim();
        if clean.eq_ignore_ascii_case("all") {
            let _ = set_selected_target("ALL");
            println!("[Switch] ✓ Switched active target to: 🌐 All Connected Devices (Broadcast)");
            return;
        }

        // Try numeric index (1-based)
        if let Ok(num) = clean.parse::<usize>() {
            if num >= 1 && num <= peers.len() {
                let chosen = &peers[num - 1];
                let _ = set_selected_target(&chosen.ip);
                println!(
                    "[Switch] ✓ Switched active device to: {} ({})",
                    chosen.device_name.as_deref().unwrap_or("Device"),
                    chosen.ip
                );
                return;
            }
        }

        // Try matching by IP or Name
        if let Some(matched) = peers.iter().find(|p| {
            p.ip.eq_ignore_ascii_case(clean)
                || p.device_name
                    .as_deref()
                    .unwrap_or("")
                    .to_lowercase()
                    .contains(&clean.to_lowercase())
        }) {
            let _ = set_selected_target(&matched.ip);
            println!(
                "[Switch] ✓ Switched active device to: {} ({})",
                matched.device_name.as_deref().unwrap_or("Device"),
                matched.ip
            );
            return;
        }

        println!("[Error] Device '{}' not found among connected devices.", clean);
        println!("Available devices:");
        for (i, p) in peers.iter().enumerate() {
            println!(
                "   {}) {} ({})",
                i + 1,
                p.device_name.as_deref().unwrap_or("Device"),
                p.ip
            );
        }
        return;
    }

    // Interactive switch
    let current_active = get_selected_target().unwrap_or_default();

    if cmd_exists("zenity")
        && (std::env::var("DISPLAY").is_ok() || std::env::var("WAYLAND_DISPLAY").is_ok())
    {
        let mut cmd = std::process::Command::new("zenity");
        cmd.args([
            "--list",
            "--radiolist",
            "--title=ClipSync - Switch Active Device",
            "--text=Select active device to interact and sync with:",
            "--column=Pick",
            "--column=IP",
            "--column=Device Name",
        ]);

        for p in &peers {
            let is_sel = if p.ip == current_active { "TRUE" } else { "FALSE" };
            cmd.arg(is_sel);
            cmd.arg(&p.ip);
            cmd.arg(p.device_name.as_deref().unwrap_or("Device"));
        }
        let is_all = if current_active.eq_ignore_ascii_case("all") { "TRUE" } else { "FALSE" };
        cmd.arg(is_all);
        cmd.arg("ALL");
        cmd.arg("🌐 All Connected Devices");

        cmd.args(["--print-column=2", "--hide-column=2", "--width=460", "--height=320"]);

        if let Ok(output) = cmd.output() {
            if output.status.success() {
                let chosen = String::from_utf8_lossy(&output.stdout).trim().to_string();
                if chosen.eq_ignore_ascii_case("ALL") {
                    let _ = set_selected_target("ALL");
                    println!("[Switch] ✓ Switched active target to: 🌐 All Connected Devices");
                    return;
                }
                if let Some(selected) = peers.iter().find(|p| p.ip == chosen) {
                    let _ = set_selected_target(&selected.ip);
                    println!(
                        "[Switch] ✓ Switched active device to: {} ({})",
                        selected.device_name.as_deref().unwrap_or("Device"),
                        selected.ip
                    );
                    return;
                }
            }
        }
    }

    // CLI fallback
    println!("\n[ClipSync] Select Active Device:");
    for (i, p) in peers.iter().enumerate() {
        let is_active = if p.ip == current_active { " [ACTIVE]" } else { "" };
        println!(
            "  {}) {} ({}){}",
            i + 1,
            p.device_name.as_deref().unwrap_or("Device"),
            p.ip,
            is_active
        );
    }
    let all_active = if current_active.eq_ignore_ascii_case("all") { " [ACTIVE]" } else { "" };
    println!("  {}) 🌐 All Devices{}", peers.len() + 1, all_active);
    print!("Choose device [1-{}]: ", peers.len() + 1);
    let _ = io::stdout().flush();

    let mut line = String::new();
    if io::stdin().read_line(&mut line).is_ok() {
        let choice = line.trim();
        if let Ok(num) = choice.parse::<usize>() {
            if num >= 1 && num <= peers.len() {
                let chosen = &peers[num - 1];
                let _ = set_selected_target(&chosen.ip);
                println!(
                    "[Switch] ✓ Switched active device to: {} ({})",
                    chosen.device_name.as_deref().unwrap_or("Device"),
                    chosen.ip
                );
                return;
            } else if num == peers.len() + 1 {
                let _ = set_selected_target("ALL");
                println!("[Switch] ✓ Switched active target to: 🌐 All Connected Devices");
                return;
            }
        }
        if choice.eq_ignore_ascii_case("all") {
            let _ = set_selected_target("ALL");
            println!("[Switch] ✓ Switched active target to: 🌐 All Connected Devices");
            return;
        }
        if let Some(matched) = peers.iter().find(|p| {
            p.ip.eq_ignore_ascii_case(choice)
                || p.device_name
                    .as_deref()
                    .unwrap_or("")
                    .to_lowercase()
                    .contains(&choice.to_lowercase())
        }) {
            let _ = set_selected_target(&matched.ip);
            println!(
                "[Switch] ✓ Switched active device to: {} ({})",
                matched.device_name.as_deref().unwrap_or("Device"),
                matched.ip
            );
        }
    }
}

pub async fn select_target_devices(filter: Option<&str>) -> Vec<PeerDevice> {
    let mut peers = get_active_peers_list();
    if peers.is_empty() {
        peers = refresh_active_peers(800).await;
    }

    if peers.is_empty() {
        return Vec::new();
    }

    // 1. Explicit CLI filter passed by user or script
    if let Some(f) = filter {
        let clean = f.trim();
        if clean.eq_ignore_ascii_case("all") {
            return peers;
        }
        let matched: Vec<PeerDevice> = peers
            .iter()
            .filter(|p| {
                p.ip == clean
                    || p.device_name
                        .as_deref()
                        .unwrap_or("")
                        .to_lowercase()
                        .contains(&clean.to_lowercase())
                    || p.device_id
                        .as_deref()
                        .unwrap_or("")
                        .to_lowercase()
                        .contains(&clean.to_lowercase())
            })
            .cloned()
            .collect();
        if !matched.is_empty() {
            return matched;
        }
    }

    // 2. Active selected device
    if let Some(target) = get_selected_target() {
        let clean = target.trim();
        if clean.eq_ignore_ascii_case("all") {
            return peers;
        }
        if let Some(active) = peers.iter().find(|p| p.ip == clean) {
            return vec![active.clone()];
        }
    }

    // 3. Default to first connected device
    let first = peers[0].clone();
    let _ = set_selected_target(&first.ip);
    vec![first]
}

