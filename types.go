// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

package main

import (
	"net"
	"sync"
	"time"
)

const (
	UDPPort         = 9876
	TCPPort         = 9877
	FilePort        = 9878
	PeerFile        = "/tmp/clipsync_peer"
	ActivePeersFile = "/tmp/clipsync_active_peers.json"
	BatteryFile     = "/tmp/clipsync_battery"
)

type SyncMessage struct {
	Type      string `json:"type,omitempty"` // "clipboard", "notification", "url", "encrypted", "battery", "call"
	DeviceID  string `json:"device_id"`
	Hash      string `json:"hash,omitempty"`
	Content   string `json:"content,omitempty"`
	URL       string `json:"url,omitempty"`
	AppName   string `json:"app_name,omitempty"`
	Title     string `json:"title,omitempty"`
	OTP       string `json:"otp,omitempty"`
	Timestamp int64  `json:"timestamp,omitempty"`
	Payload   string `json:"payload,omitempty"` // Base64 ciphertext for AES-256-GCM

	// Device info
	DeviceName string `json:"device_name,omitempty"`

	// Battery Fields
	BatteryLevel  int    `json:"battery_level,omitempty"`
	IsCharging    bool   `json:"is_charging,omitempty"`
	BatteryStatus string `json:"battery_status,omitempty"` // "low", "critical", "full", "status"

	// Call Fields
	CallState   string `json:"call_state,omitempty"`   // "ringing", "missed", "ended"
	CallerName  string `json:"caller_name,omitempty"`  // e.g. "Mom"
	PhoneNumber string `json:"phone_number,omitempty"` // e.g. "+1234567890"
}

type PeerDevice struct {
	IP         string `json:"ip"`
	DeviceID   string `json:"device_id,omitempty"`
	DeviceName string `json:"device_name,omitempty"`
}

type SyncNode struct {
	deviceID     string
	deviceName   string
	lastHash     string
	lastReceived string
	mu           sync.Mutex
	peers        map[string]net.Conn
	peerNames    map[string]string
	peersLock    sync.Mutex
	nativeClip   bool
	crypto       *CryptoManager

	// Battery Tracking
	lastBatteryLevel  int
	lastBatteryCharge bool
	lastBatteryStatus string
	lastBatteryTime   time.Time
	batteryMu         sync.Mutex

	// Call Tracking
	activeCallNotifID uint32
	lastRingingKey    string
	callMu            sync.Mutex
}

