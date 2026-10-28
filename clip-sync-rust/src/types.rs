// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

use serde::{Deserialize, Serialize};

use std::path::{Path, PathBuf};

pub const UDP_PORT: u16 = 9876;
pub const TCP_PORT: u16 = 9877;
pub const FILE_PORT: u16 = 9878;

pub const PEER_FILE: &str = "/tmp/clipsync_peer";
pub const TARGET_FILE: &str = "/tmp/clipsync_target";
pub const ACTIVE_PEERS_FILE: &str = "/tmp/clipsync_active_peers.json";
pub const BATTERY_FILE: &str = "/tmp/clipsync_battery";

pub fn get_runtime_dir() -> PathBuf {
    if let Ok(runtime_dir) = std::env::var("XDG_RUNTIME_DIR") {
        let p = PathBuf::from(runtime_dir).join("clipsync");
        let _ = std::fs::create_dir_all(&p);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = std::fs::set_permissions(&p, std::fs::Permissions::from_mode(0o700));
        }
        return p;
    }
    PathBuf::from("/tmp")
}

pub fn get_peer_file() -> PathBuf {
    let secure = get_runtime_dir().join("clipsync_peer");
    if secure.exists() {
        return secure;
    }
    PathBuf::from(PEER_FILE)
}

pub fn get_target_file() -> PathBuf {
    let secure = get_runtime_dir().join("clipsync_target");
    if secure.exists() {
        return secure;
    }
    PathBuf::from(TARGET_FILE)
}

pub fn get_active_peers_file() -> PathBuf {
    let secure = get_runtime_dir().join("clipsync_active_peers.json");
    if secure.exists() {
        return secure;
    }
    PathBuf::from(ACTIVE_PEERS_FILE)
}

pub fn get_battery_file() -> PathBuf {
    let secure = get_runtime_dir().join("clipsync_battery");
    if secure.exists() {
        return secure;
    }
    PathBuf::from(BATTERY_FILE)
}

pub fn secure_write_file<P: AsRef<Path>>(path: P, data: &[u8]) -> std::io::Result<()> {
    let p = path.as_ref();
    if let Ok(meta) = std::fs::symlink_metadata(p) {
        if meta.file_type().is_symlink() {
            let _ = std::fs::remove_file(p);
        }
    }
    std::fs::write(p, data)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(p, std::fs::Permissions::from_mode(0o600));
    }
    Ok(())
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct SyncMessage {
    #[serde(rename = "type", skip_serializing_if = "Option::is_none")]
    pub msg_type: Option<String>,

    pub device_id: String,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub os: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub hash: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub content: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub url: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub app_name: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub otp: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub timestamp: Option<i64>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub payload: Option<String>, // Base64 ciphertext for AES-256-GCM

    #[serde(skip_serializing_if = "Option::is_none")]
    pub device_name: Option<String>,

    // Battery Fields
    #[serde(skip_serializing_if = "Option::is_none")]
    pub battery_level: Option<i32>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub is_charging: Option<bool>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub battery_status: Option<String>, // "low", "critical", "full", "status"

    // Call Fields
    #[serde(skip_serializing_if = "Option::is_none")]
    pub call_state: Option<String>, // "ringing", "missed", "ended"

    #[serde(skip_serializing_if = "Option::is_none")]
    pub caller_name: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub phone_number: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PeerDevice {
    pub ip: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub device_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub device_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub os: Option<String>,
}

pub fn get_active_peers_list() -> Vec<PeerDevice> {
    for path in [get_active_peers_file(), PathBuf::from(ACTIVE_PEERS_FILE)] {
        if let Ok(data) = std::fs::read(&path) {
            if let Ok(peers) = serde_json::from_slice::<Vec<PeerDevice>>(&data) {
                return peers;
            }
        }
    }

    for path in [get_peer_file(), PathBuf::from(PEER_FILE)] {
        if let Ok(data) = std::fs::read_to_string(&path) {
            let ip = data.trim().to_string();
            if !ip.is_empty() {
                return vec![PeerDevice {
                    ip,
                    device_id: None,
                    device_name: Some("Phone".to_string()),
                    os: Some("android".to_string()),
                }];
            }
        }
    }

    Vec::new()
}

pub fn get_selected_target() -> Option<String> {
    for path in [
        get_target_file(),
        PathBuf::from(TARGET_FILE),
        get_peer_file(),
        PathBuf::from(PEER_FILE),
    ] {
        if let Ok(data) = std::fs::read_to_string(&path) {
            let target = data.trim().to_string();
            if !target.is_empty() {
                return Some(target);
            }
        }
    }
    None
}

pub fn set_selected_target(target: &str) -> std::io::Result<()> {
    secure_write_file(get_target_file(), target.as_bytes())?;
    secure_write_file(TARGET_FILE, target.as_bytes())?;
    let _ = secure_write_file(get_peer_file(), target.as_bytes());
    let _ = secure_write_file(PEER_FILE, target.as_bytes());
    Ok(())
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatteryInfo {
    pub level: i32,
    pub is_charging: bool,
    pub status: String,
    pub device_id: String,
    pub updated_at: i64, // Unix timestamp in seconds
}
