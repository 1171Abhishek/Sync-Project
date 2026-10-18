// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

use regex::Regex;
use sha2::{Digest, Sha256};
use std::net::{IpAddr, SocketAddr};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::{OnceLock, RwLock};
use std::time::{SystemTime, UNIX_EPOCH};

static RE_G: OnceLock<Regex> = OnceLock::new();
static RE_NEARBY: OnceLock<Regex> = OnceLock::new();
static RE_BEFORE: OnceLock<Regex> = OnceLock::new();
static RE_DIGITS: OnceLock<Regex> = OnceLock::new();
static RE_URL: OnceLock<Regex> = OnceLock::new();
static RE_DOMAIN: OnceLock<Regex> = OnceLock::new();

static LOCAL_IPS_CACHE: RwLock<(u64, Vec<String>)> = RwLock::new((0, Vec::new()));

pub fn hash_content(s: &str) -> String {
    let clean = s.trim();
    let mut hasher = Sha256::new();
    hasher.update(clean.as_bytes());
    hex::encode(hasher.finalize())
}

pub fn get_host_ip(addr_str: &str) -> String {
    let clean = addr_str.trim().trim_start_matches("::ffff:");
    if let Ok(sa) = clean.parse::<SocketAddr>() {
        return sa.ip().to_string().trim_start_matches("::ffff:").to_string();
    }
    if let Some((ip, _)) = clean.rsplit_once(':') {
        return ip.trim_matches(|c| c == '[' || c == ']').to_string();
    }
    clean.to_string()
}

pub fn is_local_ip(ip_str: &str) -> bool {
    let clean = ip_str.trim().trim_start_matches("::ffff:");
    if clean.is_empty() || clean == "127.0.0.1" || clean == "::1" || clean == "localhost" {
        return true;
    }

    if let Ok(ip) = clean.parse::<IpAddr>() {
        if ip.is_loopback() || ip.is_unspecified() {
            return true;
        }
    }

    // Inspect cached local network interface IPs (TTL: 30 seconds)
    if let Ok(addrs) = get_cached_local_ips() {
        for local_ip in addrs {
            if local_ip == clean {
                return true;
            }
        }
    }

    false
}

fn get_cached_local_ips() -> Result<Vec<String>, std::io::Error> {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();

    if let Ok(guard) = LOCAL_IPS_CACHE.read() {
        if now.saturating_sub(guard.0) < 30 && !guard.1.is_empty() {
            return Ok(guard.1.clone());
        }
    }

    let mut ips = Vec::new();
    if let Ok(output) = Command::new("hostname").arg("-I").output() {
        if output.status.success() {
            let s = String::from_utf8_lossy(&output.stdout);
            for part in s.split_whitespace() {
                ips.push(part.trim().to_string());
            }
        }
    }

    if let Ok(mut guard) = LOCAL_IPS_CACHE.write() {
        *guard = (now, ips.clone());
    }

    Ok(ips)
}

pub fn get_downloads_dir() -> PathBuf {
    if let Ok(home) = std::env::var("HOME") {
        let p = PathBuf::from(&home).join("Downloads");
        if p.is_dir() {
            return p;
        }
        return PathBuf::from(home);
    }
    PathBuf::from(".")
}

pub fn sanitize_filename(raw: &str) -> String {
    let base = Path::new(raw)
        .file_name()
        .and_then(|s| s.to_str())
        .unwrap_or(raw);

    // Filter control chars, slashes, backslashes, colons, null bytes
    let clean: String = base
        .chars()
        .filter(|c| !c.is_control() && *c != '/' && *c != '\\' && *c != '\0')
        .collect();

    // Strip leading dots to prevent hidden files in Downloads
    let trimmed = clean.trim_start_matches('.');
    if trimmed.is_empty() {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        return format!("received_{}", now);
    }

    let mut safe = trimmed.to_string();
    if safe.len() > 240 {
        safe.truncate(240);
    }
    safe
}

pub fn get_unique_filename(dir: &Path, base_name: &str) -> PathBuf {
    let path = Path::new(base_name);
    let stem = path.file_stem().and_then(|s| s.to_str()).unwrap_or(base_name);
    let ext = path.extension().and_then(|s| s.to_str());

    let target = dir.join(base_name);
    if !target.exists() {
        return target;
    }

    let mut counter = 1;
    loop {
        let new_name = match ext {
            Some(e) => format!("{} ({}).{}", stem, counter, e),
            None => format!("{} ({})", stem, counter),
        };
        let candidate = dir.join(&new_name);
        if !candidate.exists() {
            return candidate;
        }
        counter += 1;
    }
}

pub fn format_size(bytes: u64) -> String {
    const KB: f64 = 1024.0;
    const MB: f64 = KB * 1024.0;
    const GB: f64 = MB * 1024.0;

    let b = bytes as f64;
    if b >= GB {
        format!("{:.2} GB", b / GB)
    } else if b >= MB {
        format!("{:.1} MB", b / MB)
    } else if b >= KB {
        format!("{:.1} KB", b / KB)
    } else {
        format!("{} B", bytes)
    }
}

pub fn cmd_exists(cmd: &str) -> bool {
    if let Ok(path) = std::env::var("PATH") {
        for p in std::env::split_paths(&path) {
            let full = p.join(cmd);
            if full.is_file() {
                return true;
            }
        }
    }
    false
}

pub fn is_otp(s: &str) -> bool {
    let clean = s.trim();
    if (4..=8).contains(&clean.len()) && clean.chars().all(|c| c.is_ascii_digit()) {
        return true;
    }
    false
}

pub fn extract_otp(text: &str) -> Option<String> {
    let lower = text.to_lowercase();
    let keywords = [
        "otp", "code", "verification", "passcode", "one-time", "pin", "secret", "verify", "auth",
    ];
    let has_keyword = keywords.iter().any(|&kw| lower.contains(kw));
    if !has_keyword {
        return None;
    }

    // 1. Google G-XXXXXX pattern
    let re_g = RE_G.get_or_init(|| Regex::new(r"(?i)\bG-([0-9]{6})\b").unwrap());
    if let Some(caps) = re_g.captures(text) {
        if let Some(m) = caps.get(1) {
            return Some(m.as_str().to_string());
        }
    }

    // 2. Keyword followed by code: e.g. "code is 123456", "OTP: 1234"
    let re_nearby = RE_NEARBY.get_or_init(|| {
        Regex::new(r"(?i)(?:code|otp|pin|passcode|secret)[^0-9a-zA-Z]{0,10}(?:is\s+)?([0-9]{4,8})\b")
            .unwrap()
    });
    if let Some(caps) = re_nearby.captures(text) {
        if let Some(m) = caps.get(1) {
            return Some(m.as_str().to_string());
        }
    }

    // 3. Code followed by keyword: e.g. "123456 is your code/OTP"
    let re_before = RE_BEFORE.get_or_init(|| {
        Regex::new(r"\b([0-9]{4,8})\b\s+(?:is\s+your\s+)?(?:code|otp|verification|pin|passcode)").unwrap()
    });
    if let Some(caps) = re_before.captures(text) {
        if let Some(m) = caps.get(1) {
            return Some(m.as_str().to_string());
        }
    }

    // 4. Standalone 4-8 digit numbers excluding years
    let current_year = chrono_year_approx();
    let re_digits = RE_DIGITS.get_or_init(|| Regex::new(r"\b([0-9]{4,8})\b").unwrap());
    for caps in re_digits.captures_iter(text) {
        if let Some(m) = caps.get(1) {
            let val = m.as_str();
            if val.len() == 4 {
                if let Ok(y) = val.parse::<i32>() {
                    if y >= current_year - 1 && y <= current_year + 2 {
                        continue;
                    }
                }
            }
            return Some(val.to_string());
        }
    }

    None
}

fn chrono_year_approx() -> i32 {
    let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs();
    (1970 + now / 31557600) as i32
}

pub fn extract_url(text: &str) -> Option<String> {
    let re_url = RE_URL.get_or_init(|| Regex::new(r#"https?://[^\s<>"{}|\\^`]+"#).unwrap());
    if let Some(m) = re_url.find(text) {
        return Some(m.as_str().to_string());
    }

    let clean = text.trim();
    if clean.starts_with("www.") {
        return Some(format!("https://{}", clean));
    }

    let re_domain = RE_DOMAIN.get_or_init(|| {
        Regex::new(r"(?i)^[a-z0-9-]+(\.[a-z0-9-]+)+(/[^\s]*)?$").unwrap()
    });
    if re_domain.is_match(clean) && !Path::new(clean).exists() {
        return Some(format!("https://{}", clean));
    }

    None
}
