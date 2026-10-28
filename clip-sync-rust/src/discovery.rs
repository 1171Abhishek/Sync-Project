// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

use crate::types::{
    get_active_peers_file, get_active_peers_list, get_peer_file, get_selected_target,
    get_target_file, secure_write_file, set_selected_target, PeerDevice, ACTIVE_PEERS_FILE,
    PEER_FILE, TARGET_FILE, TCP_PORT, UDP_PORT,
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

            let beacon = format!("CLIP_SYNC:{}:{}:linux", this_broadcaster.device_id, this_broadcaster.device_name);
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
                            let probe_os = parts.get(3).copied().or_else(|| parts.get(2).copied());
                            if let Some(os) = probe_os {
                                if os.eq_ignore_ascii_case("linux") {
                                    continue; // Ignore probe from another Linux system
                                }
                            }
                            let reply = format!("CLIP_SYNC:{}:{}:linux", this_listener.device_id, this_listener.device_name);
                            let _ = socket.send_to(reply.as_bytes(), remote_addr).await;
                            continue;
                        }

                        if peer_id == "offline" {
                            let mut list = get_active_peers_list();
                            let orig_len = list.len();
                            list.retain(|p| p.ip != remote_ip);
                            if list.len() != orig_len {
                                if let Ok(data) = serde_json::to_vec(&list) {
                                    let _ = secure_write_file(get_active_peers_file(), &data);
                                    let _ = secure_write_file(ACTIVE_PEERS_FILE, &data);
                                }
                                if list.is_empty() {
                                    let _ = std::fs::remove_file(get_peer_file());
                                    let _ = std::fs::remove_file(PEER_FILE);
                                    let _ = std::fs::remove_file(get_target_file());
                                    let _ = std::fs::remove_file(TARGET_FILE);
                                }
                            }
                            continue;
                        }

                        // Strictly Linux-to-Android: ignore any Linux peer beacons
                        let peer_os = parts.get(2).copied();
                        if let Some(os) = peer_os {
                            if os.eq_ignore_ascii_case("linux") {
                                continue;
                            }
                        }
                        if !peer_id.starts_with("android") && peer_os.is_none() {
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

pub async fn probe_peer_live(ip: &str, timeout_ms: u64) -> bool {
    let clean_ip = ip.trim().trim_start_matches("::ffff:");
    if is_local_ip(clean_ip) || clean_ip.is_empty() {
        return false;
    }
    let addr = format!("{}:{}", clean_ip, TCP_PORT);
    let Ok(Ok(mut stream)) = tokio::time::timeout(Duration::from_millis(timeout_ms), TcpStream::connect(&addr)).await else {
        return false;
    };

    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let ping = b"{\"type\":\"ping\",\"os\":\"linux\"}\n";
    if stream.write_all(ping).await.is_err() {
        return false;
    }

    let mut buf = [0u8; 512];
    if let Ok(Ok(n)) = tokio::time::timeout(Duration::from_millis(timeout_ms), stream.read(&mut buf)).await {
        if n > 0 {
            let s = String::from_utf8_lossy(&buf[..n]);
            if !s.contains("\"disconnect\"") {
                // If peer is another Linux system, reject (Linux-to-Linux sync disabled)
                if s.contains("\"os\":\"linux\"") {
                    return false;
                }
                return true;
            }
        }
    }
    false
}

pub async fn refresh_active_peers(timeout_ms: u64) -> Vec<PeerDevice> {
    let mut discovered: Vec<PeerDevice> = Vec::new();
    let mut seen_ips = std::collections::HashSet::new();

    // 1. Fast check currently cached peers
    for peer in get_active_peers_list() {
        if is_local_ip(&peer.ip) {
            continue;
        }
        if probe_peer_live(&peer.ip, 300).await {
            seen_ips.insert(peer.ip.clone());
            discovered.push(peer);
        }
    }

    // 2. Active UDP probe burst to broadcast & ARP neighbors
    if let Ok(socket) = UdpSocket::bind("0.0.0.0:0").await {
        let _ = socket.set_broadcast(true);
        let beacon = b"CLIP_SYNC:probe:probe:Linux:linux";
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
                        if dev_id.as_deref() == Some("offline") {
                            continue;
                        }
                        let dev_name = parts.get(1).filter(|s| !s.is_empty()).map(|s| s.to_string()).or_else(|| Some("Phone".to_string()));
                        let dev_os = parts.get(2).filter(|s| !s.is_empty()).map(|s| s.to_string());

                        // Strictly Linux-to-Android: ignore other Linux systems
                        if dev_os.as_deref() == Some("linux") {
                            continue;
                        }
                        if !dev_id.as_deref().unwrap_or("").starts_with("android") && dev_os.as_deref() != Some("android") {
                            continue;
                        }

                        seen_ips.insert(ip.clone());
                        discovered.push(PeerDevice {
                            ip,
                            device_id: dev_id,
                            device_name: dev_name,
                            os: Some("android".to_string()),
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
                if probe_peer_live(&check_ip, 300).await {
                    Some(check_ip)
                } else {
                    None
                }
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
                    os: Some("android".to_string()),
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
            if discovered.iter().any(|p| p.ip == *curr) {
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
        println!("[Refresh] Discovering ClipSync Android devices on local Wi-Fi...");
        peers = refresh_active_peers(800).await;
    }

    if peers.is_empty() {
        println!("[Switch] No Android devices found on the local network.");
        println!("   Ensure your Android phone has ClipSync running and is on the same Wi-Fi.");
        return;
    }

    if let Some(arg) = target_arg {
        let clean = arg.trim();

        // Try numeric index (1-based)
        if let Ok(num) = clean.parse::<usize>() {
            if num >= 1 && num <= peers.len() {
                let chosen = &peers[num - 1];
                let _ = set_selected_target(&chosen.ip);
                println!(
                    "[Switch] ✓ Switched active device to: {} ({})",
                    chosen.device_name.as_deref().unwrap_or("Phone"),
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
                matched.device_name.as_deref().unwrap_or("Phone"),
                matched.ip
            );
            return;
        }

        println!("[Error] Android device '{}' not found among connected devices.", clean);
        println!("Available Android devices:");
        for (i, p) in peers.iter().enumerate() {
            println!(
                "   {}) {} ({})",
                i + 1,
                p.device_name.as_deref().unwrap_or("Phone"),
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
            "--text=Select active Android device to sync with:",
            "--column=Pick",
            "--column=IP",
            "--column=Device Name",
        ]);

        for p in &peers {
            let is_sel = if p.ip == current_active { "TRUE" } else { "FALSE" };
            cmd.arg(is_sel);
            cmd.arg(&p.ip);
            cmd.arg(p.device_name.as_deref().unwrap_or("Phone"));
        }

        cmd.args(["--print-column=2", "--hide-column=2", "--width=460", "--height=320"]);

        if let Ok(output) = cmd.output() {
            if output.status.success() {
                let chosen = String::from_utf8_lossy(&output.stdout).trim().to_string();
                if let Some(selected) = peers.iter().find(|p| p.ip == chosen) {
                    let _ = set_selected_target(&selected.ip);
                    println!(
                        "[Switch] ✓ Switched active device to: {} ({})",
                        selected.device_name.as_deref().unwrap_or("Phone"),
                        selected.ip
                    );
                    return;
                }
            }
        }
    }

    // CLI fallback
    println!("\n[ClipSync] Select Active Android Device:");
    for (i, p) in peers.iter().enumerate() {
        let is_active = if p.ip == current_active { " [ACTIVE]" } else { "" };
        let radio = if p.ip == current_active { "(*)" } else { "( )" };
        println!(
            "  {}) {} {} ({}){}",
            i + 1,
            radio,
            p.device_name.as_deref().unwrap_or("Phone"),
            p.ip,
            is_active
        );
    }
    print!("Choose device [1-{}]: ", peers.len());
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
                    chosen.device_name.as_deref().unwrap_or("Phone"),
                    chosen.ip
                );
                return;
            }
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
                matched.device_name.as_deref().unwrap_or("Phone"),
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
            return vec![matched[0].clone()];
        }
    }

    // 2. Active selected device
    if let Some(target) = get_selected_target() {
        let clean = target.trim();
        if let Some(active) = peers.iter().find(|p| p.ip == clean) {
            return vec![active.clone()];
        }
    }

    // 3. Default to first connected device
    let first = peers[0].clone();
    let _ = set_selected_target(&first.ip);
    vec![first]
}

