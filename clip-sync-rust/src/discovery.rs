// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

use crate::types::{get_peer_file, secure_write_file, FILE_PORT, PEER_FILE, TCP_PORT, UDP_PORT};
use crate::utils::is_local_ip;
use std::fs;
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

pub async fn discover_phone_ip() -> Option<String> {
    // 1. Check cached peer file
    let peer_path = get_peer_file();
    if let Ok(data) = fs::read_to_string(&peer_path) {
        let cached = data.trim().to_string();
        if !cached.is_empty() && !is_local_ip(&cached) {
            let addr_file = format!("{}:{}", cached, FILE_PORT);
            if let Ok(Ok(_)) = tokio::time::timeout(Duration::from_millis(400), TcpStream::connect(&addr_file)).await {
                return Some(cached);
            }
            let addr_tcp = format!("{}:{}", cached, TCP_PORT);
            if let Ok(Ok(_)) = tokio::time::timeout(Duration::from_millis(400), TcpStream::connect(&addr_tcp)).await {
                return Some(cached);
            }
        }
    }

    // 2. Active UDP probe
    if let Ok(socket) = UdpSocket::bind("0.0.0.0:0").await {
        let _ = socket.set_broadcast(true);
        let beacon = b"CLIP_SYNC:probe";
        let b_addr: SocketAddr = format!("255.255.255.255:{}", UDP_PORT).parse().unwrap();
        let _ = socket.send_to(beacon, b_addr).await;

        for neighbor in get_arp_neighbors() {
            if let Ok(addr) = format!("{}:{}", neighbor, UDP_PORT).parse::<SocketAddr>() {
                let _ = socket.send_to(beacon, addr).await;
            }
        }

        let mut buf = [0u8; 1024];
        let timeout_fut = tokio::time::timeout(Duration::from_millis(1500), async {
            loop {
                if let Ok((len, remote_addr)) = socket.recv_from(&mut buf).await {
                    let msg = String::from_utf8_lossy(&buf[..len]);
                    if msg.starts_with("CLIP_SYNC:") {
                        let ip = remote_addr.ip().to_string().trim_start_matches("::ffff:").to_string();
                        if !is_local_ip(&ip) {
                            let _ = secure_write_file(get_peer_file(), ip.as_bytes());
                            let _ = secure_write_file(PEER_FILE, ip.as_bytes());
                            return Some(ip);
                        }
                    }
                }
            }
        });

        if let Ok(Some(found_ip)) = timeout_fut.await {
            return Some(found_ip);
        }
    }

    // 3. Fallback: Quick direct TCP check on ARP neighbors
    for ip in get_arp_neighbors() {
        let addr_tcp = format!("{}:{}", ip, TCP_PORT);
        if let Ok(Ok(_)) = tokio::time::timeout(Duration::from_millis(500), TcpStream::connect(&addr_tcp)).await {
            let _ = secure_write_file(get_peer_file(), ip.as_bytes());
            let _ = secure_write_file(PEER_FILE, ip.as_bytes());
            return Some(ip);
        }
        let addr_file = format!("{}:{}", ip, FILE_PORT);
        if let Ok(Ok(_)) = tokio::time::timeout(Duration::from_millis(500), TcpStream::connect(&addr_file)).await {
            let _ = secure_write_file(get_peer_file(), ip.as_bytes());
            let _ = secure_write_file(PEER_FILE, ip.as_bytes());
            return Some(ip);
        }
    }

    None
}
