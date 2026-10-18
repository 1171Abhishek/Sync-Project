// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

use crate::utils::cmd_exists;
use std::process::Command;

pub fn send_desktop_notification(title: &str, message: &str, icon: &str, urgency: &str) {
    send_desktop_notification_with_id(title, message, icon, urgency, 0, 0);
}

pub fn send_desktop_notification_with_id(
    title: &str,
    message: &str,
    icon: &str,
    urgency: &str,
    expire_timeout_ms: i32,
    replace_id: u32,
) -> u32 {
    if !cmd_exists("notify-send") {
        return 0;
    }

    let mut cmd = Command::new("notify-send");
    cmd.args(["-a", "ClipSync", "-p"]);

    if !icon.is_empty() {
        cmd.args(["-i", icon]);
    }
    if !urgency.is_empty() {
        cmd.args(["-u", urgency]);
    }
    if expire_timeout_ms > 0 {
        cmd.args(["-t", &expire_timeout_ms.to_string()]);
    }
    if replace_id > 0 {
        cmd.args(["-r", &replace_id.to_string()]);
    }
    cmd.arg(title).arg(message);

    if let Ok(output) = cmd.output() {
        let out_str = String::from_utf8_lossy(&output.stdout).trim().to_string();
        if let Ok(id) = out_str.parse::<u32>() {
            return id;
        }
    }

    // Fallback if notify-send doesn't support -p
    let mut fallback = Command::new("notify-send");
    fallback.args(["-a", "ClipSync"]);
    if !icon.is_empty() {
        fallback.args(["-i", icon]);
    }
    if !urgency.is_empty() {
        fallback.args(["-u", urgency]);
    }
    fallback.arg(title).arg(message);
    let _ = fallback.status();

    0
}

pub fn close_desktop_notification(id: u32) {
    if id == 0 {
        return;
    }
    if cmd_exists("gdbus") {
        let _ = Command::new("gdbus")
            .args([
                "call",
                "--session",
                "--dest",
                "org.freedesktop.Notifications",
                "--object-path",
                "/org/freedesktop/Notifications",
                "--method",
                "org.freedesktop.Notifications.CloseNotification",
                &id.to_string(),
            ])
            .status();
    }
}
