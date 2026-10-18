// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

use crate::utils::{cmd_exists, hash_content};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tokio::process::Command as TokioCommand;
use tokio::sync::Mutex;
use tokio::time::{sleep, Duration};

#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum DisplayBackend {
    Wayland,
    X11,
    Unknown,
}

pub fn detect_display_backend() -> DisplayBackend {
    if std::env::var("WAYLAND_DISPLAY").is_ok() {
        DisplayBackend::Wayland
    } else if std::env::var("DISPLAY").is_ok() {
        DisplayBackend::X11
    } else {
        DisplayBackend::Unknown
    }
}

pub struct ClipboardManager {
    backend: DisplayBackend,
    last_hash: Arc<Mutex<String>>,
    last_received: Arc<Mutex<String>>,
    is_writing: Arc<AtomicBool>,
}

impl ClipboardManager {
    pub fn new() -> Self {
        let backend = detect_display_backend();
        Self {
            backend,
            last_hash: Arc::new(Mutex::new(String::new())),
            last_received: Arc::new(Mutex::new(String::new())),
            is_writing: Arc::new(AtomicBool::new(false)),
        }
    }

    /// Writes text to the clipboard without causing UI fluctuation or Wayland/XWayland race conditions.
    pub async fn write_clipboard(&self, text: &str) -> Result<(), String> {
        let clean = text.trim();
        if clean.is_empty() {
            return Ok(());
        }

        // Set writing flag to prevent feedback loops in watchers
        self.is_writing.store(true, Ordering::SeqCst);
        let h = hash_content(clean);

        {
            let mut lh = self.last_hash.lock().await;
            let mut lr = self.last_received.lock().await;
            *lh = h;
            *lr = text.to_string();
        }

        // Prevent UI flicker: Use strictly the matching backend.
        // Never call both wl-copy and xclip simultaneously!
        let res = match self.backend {
            DisplayBackend::Wayland => {
                if cmd_exists("wl-copy") {
                    let mut child = TokioCommand::new("wl-copy")
                        .stdin(Stdio::piped())
                        .stdout(Stdio::null())
                        .stderr(Stdio::null())
                        .spawn()
                        .map_err(|e| format!("Failed to spawn wl-copy: {}", e))?;

                    if let Some(mut stdin) = child.stdin.take() {
                        use tokio::io::AsyncWriteExt;
                        let _ = stdin.write_all(text.as_bytes()).await;
                    }
                    let _ = tokio::time::timeout(Duration::from_millis(500), child.wait()).await;
                    Ok(())
                } else {
                    Err("wl-copy not found".to_string())
                }
            }
            DisplayBackend::X11 | DisplayBackend::Unknown => {
                if cmd_exists("xclip") {
                    let mut child = TokioCommand::new("xclip")
                        .args(["-selection", "clipboard"])
                        .stdin(Stdio::piped())
                        .stdout(Stdio::null())
                        .stderr(Stdio::null())
                        .spawn()
                        .map_err(|e| format!("Failed to spawn xclip: {}", e))?;

                    if let Some(mut stdin) = child.stdin.take() {
                        use tokio::io::AsyncWriteExt;
                        let _ = stdin.write_all(text.as_bytes()).await;
                    }
                    let _ = tokio::time::timeout(Duration::from_millis(500), child.wait()).await;
                    Ok(())
                } else if cmd_exists("wl-copy") {
                    let mut child = TokioCommand::new("wl-copy")
                        .stdin(Stdio::piped())
                        .stdout(Stdio::null())
                        .stderr(Stdio::null())
                        .spawn()
                        .map_err(|e| format!("Failed to spawn wl-copy: {}", e))?;

                    if let Some(mut stdin) = child.stdin.take() {
                        use tokio::io::AsyncWriteExt;
                        let _ = stdin.write_all(text.as_bytes()).await;
                    }
                    let _ = tokio::time::timeout(Duration::from_millis(500), child.wait()).await;
                    Ok(())
                } else {
                    Err("No clipboard tool found (install wl-clipboard or xclip)".to_string())
                }
            }
        };

        // Allow clipboard event loop to settle before resetting flag
        sleep(Duration::from_millis(250)).await;
        self.is_writing.store(false, Ordering::SeqCst);

        res
    }

    /// Reads current clipboard text (strictly plain text, rejecting binary images)
    pub fn read_clipboard(&self) -> Option<String> {
        match self.backend {
            DisplayBackend::Wayland => {
                if cmd_exists("wl-paste") {
                    if let Ok(out) = Command::new("wl-paste").args(["-n", "--type", "text/plain;charset=utf-8"]).output() {
                        if out.status.success() && is_valid_text_clipboard(&out.stdout) {
                            return String::from_utf8(out.stdout).ok();
                        }
                    }
                    if let Ok(out) = Command::new("wl-paste").args(["-n", "--type", "text/plain"]).output() {
                        if out.status.success() && is_valid_text_clipboard(&out.stdout) {
                            return String::from_utf8(out.stdout).ok();
                        }
                    }
                }
            }
            DisplayBackend::X11 | DisplayBackend::Unknown => {
                if cmd_exists("xclip") {
                    if let Ok(out) = Command::new("xclip").args(["-selection", "clipboard", "-o", "-t", "UTF8_STRING"]).output() {
                        if out.status.success() && is_valid_text_clipboard(&out.stdout) {
                            return String::from_utf8(out.stdout).ok();
                        }
                    }
                } else if cmd_exists("wl-paste") {
                    if let Ok(out) = Command::new("wl-paste").args(["-n", "--type", "text/plain"]).output() {
                        if out.status.success() && is_valid_text_clipboard(&out.stdout) {
                            return String::from_utf8(out.stdout).ok();
                        }
                    }
                }
            }
        }
        None
    }

    /// Starts an asynchronous clipboard watcher with zero UI fluctuation.
    /// Strictly filters MIME types so images/screenshots never trigger compositor overhead or terminal corruption.
    pub async fn start_watcher<F, Fut>(&self, on_change: F)
    where
        F: Fn(String, String) -> Fut + Send + Sync + 'static,
        Fut: std::future::Future<Output = ()> + Send + 'static,
    {
        let is_writing = self.is_writing.clone();
        let last_hash = self.last_hash.clone();
        let last_received = self.last_received.clone();
        let backend = self.backend;

        tokio::spawn(async move {
            async_clipboard_watcher_loop(backend, is_writing, last_hash, last_received, on_change).await;
        });
    }
}

pub fn is_valid_text_clipboard(bytes: &[u8]) -> bool {
    if bytes.is_empty() {
        return false;
    }
    // Reject binary image/document signatures
    if bytes.starts_with(b"\x89PNG")
        || bytes.starts_with(b"GIF8")
        || bytes.starts_with(b"\xff\xd8\xff")
        || bytes.starts_with(b"%PDF")
        || bytes.starts_with(b"PK\x03\x04")
    {
        return false;
    }

    // Check for null bytes or excessive control characters
    let mut control_count = 0;
    for &b in bytes {
        if b == 0 {
            return false;
        }
        if b < 32 && b != b'\n' && b != b'\r' && b != b'\t' {
            control_count += 1;
        }
    }

    if bytes.len() > 20 && control_count * 20 > bytes.len() {
        return false;
    }

    true
}

async fn fetch_wayland_text() -> Option<String> {
    if !cmd_exists("wl-paste") {
        return None;
    }

    if let Ok(out) = TokioCommand::new("wl-paste")
        .args(["-n", "--type", "text/plain;charset=utf-8"])
        .output()
        .await
    {
        if out.status.success() && is_valid_text_clipboard(&out.stdout) {
            return String::from_utf8(out.stdout).ok();
        }
    }

    if let Ok(out) = TokioCommand::new("wl-paste")
        .args(["-n", "--type", "text/plain"])
        .output()
        .await
    {
        if out.status.success() && is_valid_text_clipboard(&out.stdout) {
            return String::from_utf8(out.stdout).ok();
        }
    }

    None
}

async fn fetch_x11_text() -> Option<String> {
    if cmd_exists("xclip") {
        if let Ok(out) = TokioCommand::new("xclip")
            .args(["-selection", "clipboard", "-o", "-t", "UTF8_STRING"])
            .output()
            .await
        {
            if out.status.success() && is_valid_text_clipboard(&out.stdout) {
                return String::from_utf8(out.stdout).ok();
            }
        }
        if let Ok(out) = TokioCommand::new("xclip")
            .args(["-selection", "clipboard", "-o", "-t", "text/plain"])
            .output()
            .await
        {
            if out.status.success() && is_valid_text_clipboard(&out.stdout) {
                return String::from_utf8(out.stdout).ok();
            }
        }
    } else if cmd_exists("wl-paste") {
        return fetch_wayland_text().await;
    }

    None
}

async fn async_clipboard_watcher_loop<F, Fut>(
    backend: DisplayBackend,
    is_writing: Arc<AtomicBool>,
    last_hash: Arc<Mutex<String>>,
    last_received: Arc<Mutex<String>>,
    on_change: F,
) where
    F: Fn(String, String) -> Fut + Send + Sync + 'static,
    Fut: std::future::Future<Output = ()> + Send + 'static,
{
    loop {
        sleep(Duration::from_millis(1500)).await;

        if is_writing.load(Ordering::SeqCst) {
            continue;
        }

        let current_text = match backend {
            DisplayBackend::Wayland => fetch_wayland_text().await,
            DisplayBackend::X11 | DisplayBackend::Unknown => fetch_x11_text().await,
        };

        if let Some(text) = current_text {
            let clean = text.trim();
            if clean.is_empty() {
                continue;
            }

            let h = hash_content(clean);
            let should_broadcast = {
                let mut lh = last_hash.lock().await;
                let mut lr = last_received.lock().await;
                if clean != lr.trim() && h != *lh {
                    *lh = h.clone();
                    *lr = text.clone();
                    true
                } else {
                    false
                }
            };

            if should_broadcast {
                on_change(text, h).await;
            }
        }
    }
}
