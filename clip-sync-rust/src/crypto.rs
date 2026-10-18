// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
use base64::prelude::*;
use hmac::{Hmac, Mac};
use rand::RngCore;
use sha2::Sha256;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};
use subtle::ConstantTimeEq;

type HmacSha256 = Hmac<Sha256>;

pub const SALT_STRING: &str = "ClipSync-Salt-v1";
pub const KDF_ROUNDS: usize = 50000;
pub const DEFAULT_PIN: &str = "ClipSync-Auto-Key-v1";

#[derive(Clone)]
pub struct CryptoManager {
    key: [u8; 32],
    pin: String,
}

pub fn derive_key(pin: &str) -> [u8; 32] {
    let salt = SALT_STRING.as_bytes();
    let key_bytes = pin.trim().as_bytes();

    let mut mac = <HmacSha256 as Mac>::new_from_slice(salt).expect("HMAC can take key of any size");
    mac.update(key_bytes);
    let mut res = mac.finalize().into_bytes();

    for _ in 1..KDF_ROUNDS {
        let mut next_mac = <HmacSha256 as Mac>::new_from_slice(salt).expect("HMAC can take key of any size");
        next_mac.update(&res);
        res = next_mac.finalize().into_bytes();
    }

    let mut out = [0u8; 32];
    out.copy_from_slice(&res);
    out
}

pub fn get_crypto_config_dir() -> PathBuf {
    if let Ok(home) = std::env::var("HOME") {
        let dir = PathBuf::from(home).join(".config").join("clipsync");
        let _ = fs::create_dir_all(&dir);
        return dir;
    }
    let dir = PathBuf::from(".").join(".config").join("clipsync");
    let _ = fs::create_dir_all(&dir);
    dir
}

impl CryptoManager {
    pub fn load_or_create() -> Self {
        let dir = get_crypto_config_dir();
        let key_file = dir.join("pairing.key");
        let pin_file = dir.join("pin.txt");

        if let Ok(key_data) = fs::read(&key_file) {
            if key_data.len() == 32 {
                let pin = fs::read_to_string(&pin_file)
                    .unwrap_or_default()
                    .trim()
                    .to_string();
                let mut key = [0u8; 32];
                key.copy_from_slice(&key_data);
                return Self { key, pin };
            }
        }

        let pin = DEFAULT_PIN.to_string();
        let key = derive_key(&pin);

        let _ = fs::write(&key_file, key);
        let _ = fs::write(&pin_file, &pin);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = fs::set_permissions(&key_file, fs::Permissions::from_mode(0o600));
            let _ = fs::set_permissions(&pin_file, fs::Permissions::from_mode(0o600));
        }

        Self { key, pin }
    }

    pub fn set_pin(&mut self, pin: &str) {
        let clean = pin.trim().to_string();
        let key = derive_key(&clean);
        let dir = get_crypto_config_dir();
        let key_file = dir.join("pairing.key");
        let pin_file = dir.join("pin.txt");
        let _ = fs::write(&key_file, key);
        let _ = fs::write(&pin_file, &clean);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = fs::set_permissions(&key_file, fs::Permissions::from_mode(0o600));
            let _ = fs::set_permissions(&pin_file, fs::Permissions::from_mode(0o600));
        }
        self.key = key;
        self.pin = clean;
    }

    pub fn get_pin(&self) -> &str {
        &self.pin
    }

    pub fn encrypt(&self, plaintext: &[u8]) -> Result<String, String> {
        let cipher = Aes256Gcm::new_from_slice(&self.key)
            .map_err(|e| format!("Invalid cipher key: {}", e))?;

        let mut nonce_bytes = [0u8; 12];
        rand::thread_rng().fill_bytes(&mut nonce_bytes);
        let nonce = Nonce::from_slice(&nonce_bytes);

        let ciphertext = cipher
            .encrypt(nonce, plaintext)
            .map_err(|e| format!("Encryption error: {}", e))?;

        // Prepend 12-byte nonce to ciphertext
        let mut combined = Vec::with_capacity(12 + ciphertext.len());
        combined.extend_from_slice(&nonce_bytes);
        combined.extend_from_slice(&ciphertext);

        Ok(BASE64_STANDARD.encode(combined))
    }

    pub fn decrypt(&self, encoded: &str) -> Result<Vec<u8>, String> {
        let data = BASE64_STANDARD
            .decode(encoded.trim())
            .map_err(|e| format!("Base64 decode error: {}", e))?;

        if data.len() < 12 {
            return Err("Ciphertext too short".to_string());
        }

        let (nonce_bytes, ciphertext) = data.split_at(12);
        let cipher = Aes256Gcm::new_from_slice(&self.key)
            .map_err(|e| format!("Invalid cipher key: {}", e))?;
        let nonce = Nonce::from_slice(nonce_bytes);

        cipher
            .decrypt(nonce, ciphertext)
            .map_err(|e| format!("Decryption error: {}", e))
    }

    pub fn generate_auth_token(&self, filename: &str, timestamp: i64) -> String {
        let message = format!("{}:{}", filename, timestamp);
        let mut mac = <HmacSha256 as Mac>::new_from_slice(&self.key)
            .expect("HMAC can take key of any size");
        mac.update(message.as_bytes());
        hex::encode(mac.finalize().into_bytes())
    }

    pub fn verify_auth_token(&self, filename: &str, token: &str, timestamp: i64) -> bool {
        if token.is_empty() {
            return false;
        }

        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs() as i64;

        if timestamp < now - 900 || timestamp > now + 900 {
            return false;
        }

        let expected = self.generate_auth_token(filename, timestamp);
        let token_bytes = token.as_bytes();
        let expected_bytes = expected.as_bytes();
        if token_bytes.len() == expected_bytes.len() && token_bytes.ct_eq(expected_bytes).into() {
            return true;
        }

        if let Some(clean) = Path::new(filename).file_name().and_then(|s| s.to_str()) {
            if clean != filename {
                let expected_clean = self.generate_auth_token(clean, timestamp);
                let exp_clean_bytes = expected_clean.as_bytes();
                if token_bytes.len() == exp_clean_bytes.len() && token_bytes.ct_eq(exp_clean_bytes).into() {
                    return true;
                }
            }
        }

        false
    }
}
