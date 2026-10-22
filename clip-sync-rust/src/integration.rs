// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

use crate::utils::cmd_exists;
use std::fs;
use std::path::PathBuf;
use std::process::Command;

pub fn ensure_desktop_integrations(verbose: bool) {
    let exec_path = match std::env::current_exe() {
        Ok(p) => match fs::canonicalize(&p) {
            Ok(cp) => cp,
            Err(_) => p,
        },
        Err(_) => PathBuf::from("clip-sync"),
    };
    let exec_str = exec_path.to_string_lossy().to_string();

    // 1. GNOME Keybinding
    if let Err(e) = ensure_gnome_shortcut(&exec_str, verbose) {
        if verbose {
            eprintln!("[Warning] GNOME shortcut check: {}", e);
        }
    }

    // 2. Nautilus script
    if let Err(e) = ensure_nautilus_script(&exec_str, verbose) {
        if verbose {
            eprintln!("[Warning] Nautilus script check: {}", e);
        }
    }

    // 3. Nautilus top-level Python extension
    if let Err(e) = ensure_nautilus_python_extension(&exec_str, verbose) {
        if verbose {
            eprintln!("[Warning] Nautilus Python extension check: {}", e);
        }
    }

    // 4. Desktop application entry (for file manager Open With)
    if let Err(e) = ensure_desktop_entry(&exec_str, verbose) {
        if verbose {
            eprintln!("[Warning] Desktop entry check: {}", e);
        }
    }

    // 5. User systemd service
    if let Err(e) = ensure_user_service(&exec_str, verbose) {
        if verbose {
            eprintln!("[Warning] User systemd service check: {}", e);
        }
    }
}

fn ensure_gnome_shortcut(exec_path: &str, verbose: bool) -> Result<(), String> {
    if !cmd_exists("gsettings") {
        return Ok(());
    }

    // Read current custom keybindings
    let output = Command::new("gsettings")
        .args([
            "get",
            "org.gnome.settings-daemon.plugins.media-keys",
            "custom-keybindings",
        ])
        .output()
        .map_err(|e| format!("Failed to run gsettings get: {}", e))?;

    if !output.status.success() {
        return Err("Unable to read custom-keybindings".to_string());
    }

    let raw = String::from_utf8_lossy(&output.stdout).trim().to_string();
    let desired_command = format!("{} send-link", exec_path);

    // Parse array e.g. ['/path1/', '/path2/']
    let mut current_paths = Vec::new();
    let mut used_indices = std::collections::HashSet::new();
    let mut existing_path = None;

    for part in raw.split(',') {
        let clean = part.trim().trim_matches(|c| c == '[' || c == ']' || c == '\'' || c == '"' || c == ' ');
        if !clean.is_empty() {
            current_paths.push(clean.to_string());

            if let Some(pos) = clean.find("custom") {
                let rest = &clean[pos + 6..];
                let num_str: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
                if let Ok(num) = num_str.parse::<usize>() {
                    used_indices.insert(num);
                }
            }

            let schema_key = format!("org.gnome.settings-daemon.plugins.media-keys.custom-keybinding:{}", clean);
            if let Ok(cmd_out) = Command::new("gsettings").args(["get", &schema_key, "command"]).output() {
                let cmd_val = String::from_utf8_lossy(&cmd_out.stdout).trim().trim_matches('\'').trim_matches('"').to_string();
                if cmd_val.contains("clip-sync") && cmd_val.contains("send-link") {
                    existing_path = Some(clean.to_string());
                }
            }
        }
    }

    if let Some(ref path) = existing_path {
        let schema_key = format!("org.gnome.settings-daemon.plugins.media-keys.custom-keybinding:{}", path);

        // Check if command is already up-to-date
        if let Ok(cmd_out) = Command::new("gsettings").args(["get", &schema_key, "command"]).output() {
            let cmd_val = String::from_utf8_lossy(&cmd_out.stdout).trim().trim_matches('\'').trim_matches('"').to_string();
            if cmd_val != desired_command {
                let _ = Command::new("gsettings")
                    .args(["set", &schema_key, "command", &desired_command])
                    .status();
            }
        }

        if verbose {
            println!("[OK] GNOME shortcut is active: [Ctrl + Alt + P] -> {}", desired_command);
        }
        return Ok(());
    }

    // Only add if not already configured
    let mut next_idx = 0;
    while used_indices.contains(&next_idx) {
        next_idx += 1;
    }

    let new_path = format!("/org/gnome/settings-daemon/plugins/media-keys/custom-keybindings/custom{}/", next_idx);
    let schema_key = format!("org.gnome.settings-daemon.plugins.media-keys.custom-keybinding:{}", new_path);

    let _ = Command::new("gsettings").args(["set", &schema_key, "name", "ClipSync: Send Link to Phone"]).status();
    let _ = Command::new("gsettings").args(["set", &schema_key, "command", &desired_command]).status();
    let _ = Command::new("gsettings").args(["set", &schema_key, "binding", "<Primary><Alt>p"]).status();

    current_paths.push(new_path);
    let formatted: Vec<String> = current_paths.iter().map(|p| format!("'{}'", p)).collect();
    let new_array_str = format!("[{}]", formatted.join(", "));

    let _ = Command::new("gsettings")
        .args([
            "set",
            "org.gnome.settings-daemon.plugins.media-keys",
            "custom-keybindings",
            &new_array_str,
        ])
        .status();

    if verbose {
        println!("[OK] Registered GNOME shortcut: [Ctrl + Alt + P] -> {}", desired_command);
    }
    Ok(())
}

fn ensure_nautilus_script(exec_path: &str, verbose: bool) -> Result<(), String> {
    let home = std::env::var("HOME").map_err(|e| e.to_string())?;
    let scripts_dir = PathBuf::from(home).join(".local").join("share").join("nautilus").join("scripts");
    let _ = fs::create_dir_all(&scripts_dir);

    let script_path = scripts_dir.join("Send to Phone (ClipSync)");
    let desired_content = format!(
        r#"#!/bin/bash
# ClipSync Nautilus Right-Click Action
# Sends selected file(s) directly to your paired Phone over local Wi-Fi.

if [ -z "$NAUTILUS_SCRIPT_SELECTED_FILE_PATHS" ]; then
    notify-send -a "ClipSync" -u low "ClipSync" "No file selected."
    exit 0
fi

while IFS= read -r file_path; do
    if [ -n "$file_path" ] && [ -f "$file_path" ]; then
        "{}" send "$file_path"
    fi
done <<< "$NAUTILUS_SCRIPT_SELECTED_FILE_PATHS"
"#,
        exec_path
    );

    if let Ok(existing) = fs::read_to_string(&script_path) {
        if existing == desired_content {
            return Ok(());
        }
    }

    fs::write(&script_path, desired_content).map_err(|e| e.to_string())?;

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = fs::set_permissions(&script_path, fs::Permissions::from_mode(0o755));
    }

    if verbose {
        println!("[Action] Configured Nautilus right-click action: Right-click file -> Scripts -> 'Send to Phone (ClipSync)'");
    }
    Ok(())
}

fn ensure_user_service(exec_path: &str, verbose: bool) -> Result<(), String> {
    let home = std::env::var("HOME").map_err(|e| e.to_string())?;
    let user_systemd_dir = PathBuf::from(home).join(".config").join("systemd").join("user");
    let _ = fs::create_dir_all(&user_systemd_dir);

    let service_file = user_systemd_dir.join("clip-sync.service");
    let desired_content = format!(
        r#"[Unit]
Description=ClipSync - Cross-device Linux/Android Continuity Daemon
Documentation=https://github.com/1171Abhishek/Sync-Project
PartOf=graphical-session.target
After=graphical-session.target network.target

[Service]
Type=simple
ExecStart={}
Restart=on-failure
RestartSec=3

[Install]
WantedBy=graphical-session.target
"#,
        exec_path
    );

    if let Ok(existing) = fs::read_to_string(&service_file) {
        if existing == desired_content {
            return Ok(());
        }
    }

    fs::write(&service_file, desired_content).map_err(|e| e.to_string())?;
    let _ = Command::new("systemctl").args(["--user", "daemon-reload"]).status();

    if verbose {
        println!("[Systemd] Registered user systemd service: {:?}", service_file);
    }
    Ok(())
}

fn ensure_nautilus_python_extension(exec_path: &str, verbose: bool) -> Result<(), String> {
    let home = std::env::var("HOME").map_err(|e| e.to_string())?;
    let ext_dir = PathBuf::from(home).join(".local").join("share").join("nautilus-python").join("extensions");
    let _ = fs::create_dir_all(&ext_dir);

    let ext_file = ext_dir.join("clip_sync_nautilus.py");
    let desired_content = format!(
        r#"# ClipSync Top-Level Nautilus Menu Extension
import os
import subprocess
from gi.repository import Nautilus, GObject

class ClipSyncExtension(GObject.GObject, Nautilus.MenuProvider):
    def get_file_items(self, *args):
        files = args[-1]
        if not files:
            return []

        item = Nautilus.MenuItem(
            name="ClipSync::SendToPhone",
            label="Send to Phone (ClipSync)",
            tip="Send file directly to your paired phone via ClipSync",
            icon="phone"
        )
        item.connect("activate", self.menu_activate, files)
        return [item]

    def menu_activate(self, menu, files):
        for f in files:
            try:
                path = f.get_location().get_path()
                if path and os.path.exists(path):
                    subprocess.Popen(["{}", "send", path])
            except Exception:
                pass
"#,
        exec_path
    );

    if let Ok(existing) = fs::read_to_string(&ext_file) {
        if existing == desired_content {
            return Ok(());
        }
    }

    fs::write(&ext_file, desired_content).map_err(|e| e.to_string())?;
    if verbose {
        println!("[Action] Configured Nautilus top-level context menu extension: {:?}", ext_file);
    }
    Ok(())
}

fn ensure_desktop_entry(exec_path: &str, verbose: bool) -> Result<(), String> {
    let home = std::env::var("HOME").map_err(|e| e.to_string())?;
    let apps_dir = PathBuf::from(home).join(".local").join("share").join("applications");
    let _ = fs::create_dir_all(&apps_dir);

    let desktop_file = apps_dir.join("clip-sync-send.desktop");
    let desired_content = format!(
        r#"[Desktop Entry]
Type=Application
Name=Send to Phone (ClipSync)
Comment=Send selected file to Android phone via ClipSync
Exec={} send %F
Icon=phone
Terminal=false
NoDisplay=false
MimeType=all/allfiles;application/octet-stream;video/*;image/*;audio/*;text/*;application/pdf;
Categories=Utility;
"#,
        exec_path
    );

    if let Ok(existing) = fs::read_to_string(&desktop_file) {
        if existing == desired_content {
            return Ok(());
        }
    }

    fs::write(&desktop_file, desired_content).map_err(|e| e.to_string())?;
    let _ = Command::new("update-desktop-database").arg(apps_dir).status();
    if verbose {
        println!("[Action] Configured desktop application entry: {:?}", desktop_file);
    }
    Ok(())
}
