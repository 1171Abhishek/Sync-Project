// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

use crate::crypto::CryptoManager;
use crate::discovery::select_target_devices;
use crate::notifications::send_desktop_notification;
use crate::types::FILE_PORT;
use crate::utils::{format_size, get_downloads_dir, get_host_ip, get_unique_filename, sanitize_filename};
use axum::{
    body::Body,
    extract::ConnectInfo,
    http::{HeaderMap, StatusCode},
    response::IntoResponse,
    routing::post,
    Router,
};
use futures_util::StreamExt;
use std::net::SocketAddr;
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::fs::File;
use tokio::io::AsyncWriteExt;

async fn upload_handler(
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    body: Body,
) -> impl IntoResponse {
    let raw_filename = headers
        .get("X-Filename")
        .and_then(|v| v.to_str().ok())
        .map(|s| {
            percent_encoding::percent_decode_str(s)
                .decode_utf8_lossy()
                .to_string()
        })
        .unwrap_or_else(|| {
            let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs();
            format!("received_{}", now)
        });

    let auth_header = headers
        .get("X-ClipSync-Auth")
        .and_then(|v| v.to_str().ok())
        .unwrap_or_default();

    let time_header = headers
        .get("X-ClipSync-Time")
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.parse::<i64>().ok())
        .unwrap_or_default();

    let crypto = CryptoManager::load_or_create();
    if !crypto.verify_auth_token(&raw_filename, auth_header, time_header) {
        println!(
            "[Security] Rejected unauthenticated file upload ({}) from {}",
            raw_filename, addr
        );
        return (StatusCode::UNAUTHORIZED, "Unauthorized: Pairing required\n");
    }

    let safe_filename = sanitize_filename(&raw_filename);

    let downloads_dir = get_downloads_dir();
    let dest_path = get_unique_filename(&downloads_dir, &safe_filename);

    let mut file = match File::create(&dest_path).await {
        Ok(f) => f,
        Err(e) => {
            eprintln!("[Error] Failed to create destination file {:?}: {}", dest_path, e);
            return (StatusCode::INTERNAL_SERVER_ERROR, "Failed to create file\n");
        }
    };

    let mut stream = body.into_data_stream();
    let mut total_bytes: u64 = 0;

    while let Some(chunk_res) = stream.next().await {
        match chunk_res {
            Ok(chunk) => {
                total_bytes += chunk.len() as u64;
                if let Err(e) = file.write_all(&chunk).await {
                    eprintln!("[Error] Error writing file chunk: {}", e);
                    return (StatusCode::INTERNAL_SERVER_ERROR, "Write error\n");
                }
            }
            Err(e) => {
                eprintln!("[Error] Error reading upload stream: {}", e);
                return (StatusCode::INTERNAL_SERVER_ERROR, "Stream read error\n");
            }
        }
    }

    let _ = file.flush().await;

    let size_str = format_size(total_bytes);
    let final_name = dest_path.file_name().and_then(|s| s.to_str()).unwrap_or(&safe_filename);

    println!(
        "[File] Received file from {}: {} ({}) -> {:?}",
        get_host_ip(&addr.to_string()),
        final_name,
        size_str,
        dest_path
    );

    send_desktop_notification(
        "ClipSync: File Received",
        &format!("[Received] {} ({})\nSaved to Downloads", final_name, size_str),
        "document-save",
        "normal",
    );

    (StatusCode::OK, "OK\n")
}

pub async fn start_file_server() {
    let app = Router::new().route("/upload", post(upload_handler));
    let addr: SocketAddr = format!("0.0.0.0:{}", FILE_PORT).parse().unwrap();

    let listener = match tokio::net::TcpListener::bind(addr).await {
        Ok(l) => l,
        Err(e) => {
            eprintln!("[Warning] File server bind error on {}: {}", addr, e);
            return;
        }
    };

    let _ = axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .await;
}

pub async fn send_file_to_phone(file_path: &Path, device_filter: Option<&str>) {
    let metadata = match tokio::fs::metadata(file_path).await {
        Ok(m) => m,
        Err(_) => {
            eprintln!("[Error] File not found: {:?}", file_path);
            std::process::exit(1);
        }
    };

    if metadata.is_dir() {
        eprintln!("[Error] Cannot send directory: {:?}", file_path);
        std::process::exit(1);
    }

    println!("[Discovery] Looking for connected device(s) on Wi-Fi...");
    let targets = select_target_devices(device_filter).await;
    if targets.is_empty() {
        eprintln!("[Error] No device found or operation cancelled.");
        send_desktop_notification(
            "ClipSync Send Cancelled",
            "No device selected or reachable on local Wi-Fi",
            "dialog-warning",
            "normal",
        );
        std::process::exit(1);
    }

    let file_name = file_path
        .file_name()
        .and_then(|s| s.to_str())
        .unwrap_or("sent_file");
    let size_str = format_size(metadata.len());

    let mut success_count = 0;
    for target in &targets {
        let phone_ip = &target.ip;
        let target_name = target.device_name.as_deref().unwrap_or("Phone");
        println!(
            "[Send] Sending {} ({}) to {} ({})...",
            file_name, size_str, target_name, phone_ip
        );

        let file = match tokio::fs::File::open(file_path).await {
            Ok(f) => f,
            Err(e) => {
                eprintln!("[Error] Failed to open file {:?}: {}", file_path, e);
                continue;
            }
        };

        let crypto = CryptoManager::load_or_create();
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs() as i64;
        let auth_token = crypto.generate_auth_token(file_name, now);

        let url_target = format!("http://{}:{}/upload", phone_ip, FILE_PORT);
        let client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(300))
            .build()
            .unwrap_or_default();

        let stream = tokio_util::io::ReaderStream::new(file);
        let body = reqwest::Body::wrap_stream(stream);
        let encoded_filename = percent_encoding::utf8_percent_encode(
            file_name,
            percent_encoding::NON_ALPHANUMERIC,
        )
        .to_string();

        let res = client
            .post(&url_target)
            .header("X-ClipSync-Auth", auth_token)
            .header("X-ClipSync-Time", now.to_string())
            .header("X-Filename", encoded_filename)
            .header("Content-Type", "application/octet-stream")
            .header("Content-Length", metadata.len().to_string())
            .body(body)
            .send()
            .await;

        match res {
            Ok(resp) if resp.status().is_success() => {
                println!(
                    "[OK] Successfully sent {} to {}! Saved to Gallery / Downloads.",
                    file_name, target_name
                );
                send_desktop_notification(
                    "ClipSync: Sent File",
                    &format!("{} ({}) sent to {}", file_name, size_str, target_name),
                    "document-save",
                    "normal",
                );
                success_count += 1;
            }
            Ok(resp) => {
                eprintln!(
                    "[Warning] {} responded with status: {}",
                    target_name,
                    resp.status()
                );
                send_desktop_notification(
                    "ClipSync Send Failed",
                    &format!(
                        "{} returned {} while sending {}",
                        target_name,
                        resp.status(),
                        file_name
                    ),
                    "dialog-warning",
                    "normal",
                );
            }
            Err(e) => {
                eprintln!("[Error] Upload failed to {}: {}", target_name, e);
                send_desktop_notification(
                    "ClipSync Send Failed",
                    &format!("Failed to send {} to {}", file_name, target_name),
                    "dialog-warning",
                    "normal",
                );
            }
        }
    }

    if success_count == 0 {
        std::process::exit(1);
    }
}
