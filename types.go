// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

package main

import (
	"net"
	"sync"
)

const (
	UDPPort  = 9876
	TCPPort  = 9877
	FilePort = 9878
	PeerFile = "/tmp/clipsync_peer"
)

type SyncMessage struct {
	Type      string `json:"type,omitempty"` // "clipboard", "notification" (default "clipboard")
	DeviceID  string `json:"device_id"`
	Hash      string `json:"hash,omitempty"`
	Content   string `json:"content,omitempty"`
	URL       string `json:"url,omitempty"`
	AppName   string `json:"app_name,omitempty"`
	Title     string `json:"title,omitempty"`
	OTP       string `json:"otp,omitempty"`
	Timestamp int64  `json:"timestamp,omitempty"`
}

type SyncNode struct {
	deviceID     string
	lastHash     string
	lastReceived string
	mu           sync.Mutex
	peers        map[string]net.Conn
	peersLock    sync.Mutex
	nativeClip   bool
}
