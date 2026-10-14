// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	SaltString = "ClipSync-Salt-v1"
	KDFRounds  = 50000
	DefaultPIN = "ClipSync-Auto-Key-v1"
)

type CryptoManager struct {
	key []byte
	pin string
}

func getCryptoConfigDir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		home = "."
	}
	dir := filepath.Join(home, ".config", "clipsync")
	_ = os.MkdirAll(dir, 0700)
	return dir
}

// DeriveKey derives a 32-byte AES-256 key from a PIN using HMAC-SHA256 with 50,000 rounds
func DeriveKey(pin string) []byte {
	salt := []byte(SaltString)
	key := []byte(strings.TrimSpace(pin))
	h := hmac.New(sha256.New, salt)
	h.Write(key)
	res := h.Sum(nil)
	for i := 1; i < KDFRounds; i++ {
		h.Reset()
		h.Write(res)
		res = h.Sum(nil)
	}
	return res
}

func generateRandomPIN() string {
	max := big.NewInt(900000)
	n, err := rand.Int(rand.Reader, max)
	if err != nil {
		return "834921"
	}
	return fmt.Sprintf("%06d", n.Int64()+100000)
}

func LoadOrCreateCrypto() *CryptoManager {
	dir := getCryptoConfigDir()
	keyFile := filepath.Join(dir, "pairing.key")
	pinFile := filepath.Join(dir, "pin.txt")

	if keyData, err := os.ReadFile(keyFile); err == nil && len(keyData) == 32 {
		pin := ""
		if pinData, err := os.ReadFile(pinFile); err == nil {
			pin = strings.TrimSpace(string(pinData))
		}
		return &CryptoManager{key: keyData, pin: pin}
	}

	pin := DefaultPIN
	key := DeriveKey(pin)

	_ = os.WriteFile(keyFile, key, 0600)
	_ = os.WriteFile(pinFile, []byte(pin), 0600)

	return &CryptoManager{key: key, pin: pin}
}

func (c *CryptoManager) SetPIN(pin string) {
	clean := strings.TrimSpace(pin)
	key := DeriveKey(clean)
	dir := getCryptoConfigDir()
	_ = os.WriteFile(filepath.Join(dir, "pairing.key"), key, 0600)
	_ = os.WriteFile(filepath.Join(dir, "pin.txt"), []byte(clean), 0600)
	c.key = key
	c.pin = clean
}

func (c *CryptoManager) GetPIN() string {
	return c.pin
}

// Encrypt encrypts plaintext bytes using AES-256-GCM and returns a base64 string
func (c *CryptoManager) Encrypt(plaintext []byte) (string, error) {
	if len(c.key) != 32 {
		return "", fmt.Errorf("invalid crypto key")
	}

	block, err := aes.NewCipher(c.key)
	if err != nil {
		return "", err
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}

	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return "", err
	}

	ciphertext := gcm.Seal(nonce, nonce, plaintext, nil)
	return base64.StdEncoding.EncodeToString(ciphertext), nil
}

// Decrypt decrypts a base64 AES-256-GCM payload and returns the plaintext bytes
func (c *CryptoManager) Decrypt(encoded string) ([]byte, error) {
	if len(c.key) != 32 {
		return nil, fmt.Errorf("invalid crypto key")
	}

	data, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return nil, err
	}

	block, err := aes.NewCipher(c.key)
	if err != nil {
		return nil, err
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}

	nonceSize := gcm.NonceSize()
	if len(data) < nonceSize {
		return nil, fmt.Errorf("ciphertext too short")
	}

	nonce, ciphertext := data[:nonceSize], data[nonceSize:]
	return gcm.Open(nil, nonce, ciphertext, nil)
}

// GenerateAuthToken creates an HMAC token for file transfer authentication
func (c *CryptoManager) GenerateAuthToken(filename string, timestamp int64) string {
	message := fmt.Sprintf("%s:%d", filename, timestamp)
	mac := hmac.New(sha256.New, c.key)
	mac.Write([]byte(message))
	return hex.EncodeToString(mac.Sum(nil))
}

// VerifyAuthToken validates the file transfer HMAC token within a 5-minute window
func (c *CryptoManager) VerifyAuthToken(filename, token string, timestamp int64) bool {
	if token == "" || len(c.key) != 32 {
		return false
	}
	now := time.Now().Unix()
	if timestamp < now-300 || timestamp > now+300 {
		return false
	}
	expected := c.GenerateAuthToken(filename, timestamp)
	return hmac.Equal([]byte(expected), []byte(token))
}
