// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

use crate::clipboard::ClipboardManager;
use crate::crypto::CryptoManager;
use crate::notifications::{
    close_desktop_notification, send_desktop_notification, send_desktop_notification_with_id,
};
use crate::types::{
    get_active_peers_file, get_battery_file, get_peer_file, secure_write_file, BatteryInfo,
    PeerDevice, SyncMessage, ACTIVE_PEERS_FILE, BATTERY_FILE, PEER_FILE, TCP_PORT,
};
use crate::url_handler::handle_url;
use crate::utils::{extract_otp, get_host_ip, is_local_ip, is_otp};
use futures_util::StreamExt;
use std::collections::HashMap;
use std::fs;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::io::AsyncWriteExt;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, Mutex, RwLock};
use tokio_util::codec::{FramedRead, LinesCodec};

pub struct SyncNode {
    pub device_id: String,
    pub device_name: String,
    pub peers: Arc<RwLock<HashMap<String, mpsc::Sender<Vec<u8>>>>>,
    pub peer_names: Arc<RwLock<HashMap<String, String>>>,
    pub crypto: Arc<CryptoManager>,
    pub clipboard: Arc<ClipboardManager>,

    // Clipboard tracking
    pub last_hash: Arc<Mutex<String>>,
    pub last_received: Arc<Mutex<String>>,

    // Battery state
    last_battery_level: Arc<Mutex<i32>>,
    last_battery_charge: Arc<Mutex<bool>>,

    // Call tracking
    active_call_notif_id: Arc<Mutex<u32>>,
}

impl SyncNode {
    pub fn new() -> Arc<Self> {
        let hostname = std::env::var("HOSTNAME")
            .ok()
            .filter(|s| !s.is_empty())
            .or_else(|| {
                fs::read_to_string("/etc/hostname")
                    .ok()
                    .map(|s| s.trim().to_string())
            })
            .unwrap_or_else(|| "device".to_string());

        let now_sec = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();

        let device_id = format!("{}-{}", hostname, now_sec % 10000);
        let crypto = Arc::new(CryptoManager::load_or_create());
        let clipboard = Arc::new(ClipboardManager::new());

        let _ = fs::remove_file(get_peer_file());
        let _ = fs::remove_file(PEER_FILE);
        let _ = secure_write_file(get_active_peers_file(), b"[]");
        let _ = secure_write_file(ACTIVE_PEERS_FILE, b"[]");

        let node = Arc::new(Self {
            device_id,
            device_name: hostname,
            peers: Arc::new(RwLock::new(HashMap::new())),
            peer_names: Arc::new(RwLock::new(HashMap::new())),
            crypto,
            clipboard,
            last_hash: Arc::new(Mutex::new(String::new())),
            last_received: Arc::new(Mutex::new(String::new())),
            last_battery_level: Arc::new(Mutex::new(-1)),
            last_battery_charge: Arc::new(Mutex::new(false)),
            active_call_notif_id: Arc::new(Mutex::new(0)),
        });

        // Start background maintenance loops
        let n1 = node.clone();
        tokio::spawn(async move { n1.start_heartbeat().await });

        let n2 = node.clone();
        tokio::spawn(async move { n2.start_reconnect_watchdog().await });

        node
    }

    pub async fn sync_peers_file(&self) {
        let peers_guard = self.peers.read().await;
        let names_guard = self.peer_names.read().await;

        let mut devices = Vec::new();
        for ip in peers_guard.keys() {
            let name = names_guard
                .get(ip)
                .cloned()
                .unwrap_or_else(|| "Phone".to_string());
            devices.push(PeerDevice {
                ip: ip.clone(),
                device_id: None,
                device_name: Some(name),
            });
        }

        if let Ok(data) = serde_json::to_vec(&devices) {
            let _ = secure_write_file(get_active_peers_file(), &data);
            let _ = secure_write_file(ACTIVE_PEERS_FILE, &data);
        }

        if !devices.is_empty() {
            let _ = secure_write_file(get_peer_file(), devices[0].ip.as_bytes());
            let _ = secure_write_file(PEER_FILE, devices[0].ip.as_bytes());
        } else {
            let _ = fs::remove_file(get_peer_file());
            let _ = fs::remove_file(PEER_FILE);
        }
    }

    pub async fn broadcast_message(&self, msg: SyncMessage) {
        let inner_data = match serde_json::to_vec(&msg) {
            Ok(d) => d,
            Err(_) => return,
        };

        let wire_data = match self.crypto.encrypt(&inner_data) {
            Ok(payload) => {
                let enc_msg = SyncMessage {
                    msg_type: Some("encrypted".to_string()),
                    device_id: self.device_id.clone(),
                    payload: Some(payload),
                    ..Default::default()
                };
                serde_json::to_vec(&enc_msg).unwrap_or(inner_data)
            }
            Err(_) => inner_data,
        };

        // Extract senders and drop read lock immediately before awaiting channels (prevents deadlock)
        let senders: Vec<_> = {
            let peers_guard = self.peers.read().await;
            peers_guard.values().cloned().collect()
        };
        for sender in senders {
            let _ = sender.send(wire_data.clone()).await;
        }
    }

    pub async fn broadcast_clipboard(&self, content: String, hash: String) {
        self.broadcast_message(SyncMessage {
            msg_type: Some("clipboard".to_string()),
            device_id: self.device_id.clone(),
            hash: Some(hash),
            content: Some(content),
            ..Default::default()
        })
        .await;
    }

    pub async fn start_tcp_server(self: Arc<Self>) {
        let addr = format!("0.0.0.0:{}", TCP_PORT);
        let listener = match TcpListener::bind(&addr).await {
            Ok(l) => l,
            Err(e) => {
                eprintln!("[Warning] TCP server bind error on {}: {}", addr, e);
                return;
            }
        };

        while let Ok((stream, _)) = listener.accept().await {
            let node = self.clone();
            tokio::spawn(async move {
                node.handle_peer(stream).await;
            });
        }
    }

    pub async fn connect_to_peer(self: Arc<Self>, ip: String) {
        let clean_ip = ip.trim().trim_start_matches("::ffff:").to_string();
        if is_local_ip(&clean_ip) {
            return;
        }

        {
            let peers = self.peers.read().await;
            if peers.contains_key(&clean_ip) {
                return;
            }
        }

        let target_addr = format!("{}:{}", clean_ip, TCP_PORT);
        if let Ok(Ok(stream)) = tokio::time::timeout(Duration::from_secs(2), TcpStream::connect(&target_addr)).await {
            let node = self.clone();
            tokio::spawn(async move {
                node.handle_peer(stream).await;
            });
        }
    }

    pub async fn handle_peer(self: Arc<Self>, stream: TcpStream) {
        let host_ip = match stream.peer_addr() {
            Ok(a) => get_host_ip(&a.to_string()),
            Err(_) => return,
        };

        if is_local_ip(&host_ip) {
            return;
        }

        {
            let peers = self.peers.read().await;
            if peers.contains_key(&host_ip) {
                return;
            }
        }

        let (tx, mut rx) = mpsc::channel::<Vec<u8>>(64);
        {
            let mut peers = self.peers.write().await;
            peers.insert(host_ip.clone(), tx);
        }
        self.sync_peers_file().await;

        println!("[Connected] Connected with peer: {}", host_ip);

        // Send handshake
        self.broadcast_message(SyncMessage {
            msg_type: Some("handshake".to_string()),
            device_id: self.device_id.clone(),
            device_name: Some(self.device_name.clone()),
            ..Default::default()
        })
        .await;

        let (reader, mut writer) = stream.into_split();
        let mut lines = FramedRead::new(reader, LinesCodec::new());

        // Outbound socket write task
        let write_task = tokio::spawn(async move {
            while let Some(mut msg_bytes) = rx.recv().await {
                msg_bytes.push(b'\n');
                if writer.write_all(&msg_bytes).await.is_err() {
                    break;
                }
            }
        });

        // Inbound message read loop
        let node_ref = self.clone();
        let peer_ip = host_ip.clone();

        while let Some(line_res) = lines.next().await {
            let line = match line_res {
                Ok(l) => l,
                Err(_) => continue,
            };
            let mut msg: SyncMessage = match serde_json::from_str(&line) {
                Ok(m) => m,
                Err(_) => continue,
            };

            // Decrypt if encrypted
            if msg.msg_type.as_deref() == Some("encrypted") {
                if let Some(ref payload) = msg.payload {
                    match node_ref.crypto.decrypt(payload) {
                        Ok(decrypted) => {
                            if let Ok(inner) = serde_json::from_slice::<SyncMessage>(&decrypted) {
                                msg = inner;
                            } else {
                                continue;
                            }
                        }
                        Err(_) => {
                            println!("[Security] Rejected untrusted message from {} (wrong key / tampered)", peer_ip);
                            continue;
                        }
                    }
                }
            }

            // Update device name if provided
            if let Some(ref d_name) = msg.device_name {
                let mut names = node_ref.peer_names.write().await;
                if names.get(&peer_ip) != Some(d_name) {
                    names.insert(peer_ip.clone(), d_name.clone());
                    drop(names);
                    node_ref.sync_peers_file().await;
                }
            }

            match msg.msg_type.as_deref().unwrap_or("") {
                "ping" | "pong" | "handshake" => continue,
                "url" => {
                    handle_url(&msg, &node_ref.clipboard).await;
                }
                "notification" => {
                    node_ref.handle_notification(&msg).await;
                }
                "battery" => {
                    node_ref.handle_battery(&msg).await;
                }
                "call" => {
                    node_ref.handle_call(&msg).await;
                }
                "clipboard" | "" => {
                    if let Some(ref content) = msg.content {
                        let clean = content.trim();
                        let incoming_hash = msg.hash.unwrap_or_else(|| crate::utils::hash_content(clean));

                        let should_update = {
                            let mut lh = node_ref.last_hash.lock().await;
                            let mut lr = node_ref.last_received.lock().await;
                            if !clean.is_empty() && clean != lr.trim() && incoming_hash != *lh {
                                *lh = incoming_hash;
                                *lr = content.clone();
                                true
                            } else {
                                false
                            }
                        };

                        if should_update {
                            println!(
                                "[Clipboard] Received clipboard from {} ({} bytes): {:.30}...",
                                msg.device_id,
                                content.len(),
                                content.replace('\n', " ")
                            );

                            let _ = node_ref.clipboard.write_clipboard(content).await;

                            if is_otp(clean) {
                                send_desktop_notification("OTP Copied to Clipboard", clean, "dialog-password", "normal");
                            } else {
                                let mut preview = clean.to_string();
                                if preview.len() > 50 {
                                    preview.truncate(50);
                                    preview.push_str("...");
                                }
                                send_desktop_notification("Clipboard Synced from Phone", &preview, "edit-copy", "low");
                            }
                        }
                    }
                }
                _ => {}
            }
        }

        // Cleanup on disconnect
        write_task.abort();
        {
            let mut peers = self.peers.write().await;
            peers.remove(&host_ip);
            let mut names = self.peer_names.write().await;
            names.remove(&host_ip);
        }
        self.sync_peers_file().await;

        println!("[Disconnected] Disconnected from: {}", host_ip);
    }

    async fn handle_notification(&self, msg: &SyncMessage) {
        let app_name = msg.app_name.as_deref().unwrap_or("Phone");
        let title = msg.title.as_deref().unwrap_or("New Notification");
        let content = msg.content.as_deref().unwrap_or("").trim();

        let otp = msg.otp.clone().or_else(|| {
            extract_otp(content).or_else(|| extract_otp(title))
        });

        if let Some(ref code) = otp {
            let _ = self.clipboard.write_clipboard(code).await;
            println!("[OTP] Auto-copied OTP '{}' from {} ({}) to Linux clipboard!", code, app_name, title);

            send_desktop_notification(
                &format!("OTP Copied: {}", code),
                &format!("{}: {}\n(Press Ctrl+V to paste)", app_name, content),
                "security-high",
                "critical",
            );
            return;
        }

        println!("[Notification] Notification from {} ({}): {:.40}", app_name, title, content);
        let display_body = if !content.is_empty() { content } else { title };

        send_desktop_notification(
            &format!("{}: {}", app_name, title),
            display_body,
            "dialog-information",
            "normal",
        );
    }

    async fn handle_battery(&self, msg: &SyncMessage) {
        let level = msg.battery_level.unwrap_or(0);
        let charging = msg.is_charging.unwrap_or(false);
        let status = msg.battery_status.as_deref().unwrap_or("status");

        let state_changed = {
            let mut last_lvl = self.last_battery_level.lock().await;
            let mut last_chg = self.last_battery_charge.lock().await;
            let changed = *last_lvl != level || *last_chg != charging;
            *last_lvl = level;
            *last_chg = charging;
            changed
        };

        // Persist to BATTERY_FILE for CLI queries
        let now_sec = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs();
        let info = BatteryInfo {
            level,
            is_charging: charging,
            status: status.to_string(),
            device_id: msg.device_id.clone(),
            updated_at: now_sec as i64,
        };
        if let Ok(data) = serde_json::to_vec(&info) {
            let _ = secure_write_file(get_battery_file(), &data);
            let _ = secure_write_file(BATTERY_FILE, &data);
        }

        let charge_str = if charging { "Charging" } else { "Discharging" };

        match status {
            "critical" => {
                println!("[Battery] Phone Battery CRITICAL: {}% ({})", level, charge_str);
                let title = format!("Phone Battery Critical: {}%", level);
                let body = format!("Phone battery is at {}%! Please connect your charger immediately.", level);
                send_desktop_notification(&title, &body, "battery-caution", "critical");
            }
            "low" => {
                println!("[Battery] Phone Battery Low: {}% ({})", level, charge_str);
                let title = format!("Phone Battery Low: {}%", level);
                let body = format!("Phone battery is at {}%. Please connect your charger.", level);
                send_desktop_notification(&title, &body, "battery-low", "normal");
            }
            "full" => {
                println!("[Battery] Phone Battery Full: {}% ({})", level, charge_str);
                let title = format!("Phone Fully Charged ({}%)", level);
                let body = "Your phone is fully charged. You can unplug the charger now.";
                send_desktop_notification(&title, body, "battery-full", "normal");
            }
            _ => {
                if state_changed {
                    println!("[Battery] Phone Battery: {}% [{}]", level, charge_str);
                }
            }
        }
    }

    async fn handle_call(&self, msg: &SyncMessage) {
        let caller = msg.caller_name.as_deref().unwrap_or("").trim();
        let number = msg.phone_number.as_deref().unwrap_or("").trim();

        let display_name = if !caller.is_empty() && !number.is_empty() && !caller.contains(number) {
            format!("{} ({})", caller, number)
        } else if !caller.is_empty() {
            caller.to_string()
        } else if !number.is_empty() {
            number.to_string()
        } else {
            "Unknown Caller".to_string()
        };

        let mut active_id = self.active_call_notif_id.lock().await;

        match msg.call_state.as_deref().unwrap_or("") {
            "ringing" => {
                println!("[Call] Incoming Call from: {}", display_name);
                let title = "Incoming Call";
                let body = format!("{} is calling...", display_name);

                let new_id = send_desktop_notification_with_id(
                    title,
                    &body,
                    "call-start",
                    "critical",
                    0,
                    *active_id,
                );
                *active_id = new_id;
            }
            "ended" => {
                println!("[Call] Call ended ({})", display_name);
                if *active_id > 0 {
                    let _ = send_desktop_notification_with_id(
                        "Call Ended",
                        &display_name,
                        "call-stop",
                        "low",
                        2000,
                        *active_id,
                    );
                    close_desktop_notification(*active_id);
                    *active_id = 0;
                }
            }
            "missed" => {
                println!("[Call] Missed Call from: {}", display_name);
                if *active_id > 0 {
                    close_desktop_notification(*active_id);
                    *active_id = 0;
                }
                send_desktop_notification_with_id(
                    "Missed Call",
                    &format!("Missed call from {}", display_name),
                    "call-missed",
                    "normal",
                    10000,
                    0,
                );
            }
            _ => {}
        }
    }

    async fn start_heartbeat(&self) {
        let mut interval = tokio::time::interval(Duration::from_secs(15));
        loop {
            interval.tick().await;

            let peers_count = {
                let p = self.peers.read().await;
                p.len()
            };

            if peers_count > 0 {
                self.broadcast_message(SyncMessage {
                    msg_type: Some("ping".to_string()),
                    device_id: self.device_id.clone(),
                    ..Default::default()
                })
                .await;
            }
        }
    }

    async fn start_reconnect_watchdog(&self) {
        let mut interval = tokio::time::interval(Duration::from_secs(4));
        loop {
            interval.tick().await;

            let peers_count = {
                let p = self.peers.read().await;
                p.len()
            };

            if peers_count == 0 {
                if let Ok(data) = fs::read_to_string(PEER_FILE) {
                    let last_ip = data.trim().to_string();
                    if !last_ip.is_empty() && !is_local_ip(&last_ip) {
                        let self_node = self.clone_node();
                        self_node.connect_to_peer(last_ip).await;
                    }
                }
            }
        }
    }

    fn clone_node(&self) -> Arc<Self> {
        Arc::new(Self {
            device_id: self.device_id.clone(),
            device_name: self.device_name.clone(),
            peers: self.peers.clone(),
            peer_names: self.peer_names.clone(),
            crypto: self.crypto.clone(),
            clipboard: self.clipboard.clone(),
            last_hash: self.last_hash.clone(),
            last_received: self.last_received.clone(),
            last_battery_level: self.last_battery_level.clone(),
            last_battery_charge: self.last_battery_charge.clone(),
            active_call_notif_id: self.active_call_notif_id.clone(),
        })
    }
}
