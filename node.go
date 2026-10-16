// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

package main

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"strings"
	"time"

	"golang.design/x/clipboard"
)

func NewSyncNode() *SyncNode {
	hostname, _ := os.Hostname()
	if hostname == "" {
		hostname = "device"
	}

	hasNative := false
	if err := clipboard.Init(); err == nil {
		hasNative = true
	}

	crypto := LoadOrCreateCrypto()

	node := &SyncNode{
		deviceID:   fmt.Sprintf("%s-%d", hostname, time.Now().Unix()%10000),
		peers:      make(map[string]net.Conn),
		nativeClip: hasNative,
		crypto:     crypto,
	}
	_ = os.Remove(PeerFile)
	_ = os.WriteFile(ActivePeersFile, []byte("[]"), 0644)

	go node.startHeartbeat()
	go node.startReconnectWatchdog()
	return node
}

func (n *SyncNode) syncPeersFileLocked() {
	ips := make([]string, 0, len(n.peers))
	for ip := range n.peers {
		ips = append(ips, ip)
	}
	data, _ := json.Marshal(ips)
	_ = os.WriteFile(ActivePeersFile, data, 0644)
	if len(ips) > 0 {
		_ = os.WriteFile(PeerFile, []byte(ips[0]), 0644)
	} else {
		_ = os.Remove(PeerFile)
	}
}

func (n *SyncNode) BroadcastMessage(msg SyncMessage) {
	n.peersLock.Lock()
	defer n.peersLock.Unlock()

	innerData, err := json.Marshal(msg)
	if err != nil {
		return
	}

	var data []byte
	if n.crypto != nil {
		payload, err := n.crypto.Encrypt(innerData)
		if err == nil {
			encMsg := SyncMessage{
				Type:     "encrypted",
				DeviceID: n.deviceID,
				Payload:  payload,
			}
			data, _ = json.Marshal(encMsg)
		} else {
			data = innerData
		}
	} else {
		data = innerData
	}
	data = append(data, byte(10))

	for host, conn := range n.peers {
		_, err := conn.Write(data)
		if err != nil {
			conn.Close()
			delete(n.peers, host)
		}
	}
}

func (n *SyncNode) BroadcastClipboard(content, hash string) {
	n.BroadcastMessage(SyncMessage{
		Type:     "clipboard",
		DeviceID: n.deviceID,
		Hash:     hash,
		Content:  content,
	})
}

func (n *SyncNode) handlePeer(conn net.Conn) {
	if tcpConn, ok := conn.(*net.TCPConn); ok {
		_ = tcpConn.SetKeepAlive(true)
		_ = tcpConn.SetKeepAlivePeriod(15 * time.Second)
		_ = tcpConn.SetNoDelay(true)
	}
	hostIP := getHostIP(conn.RemoteAddr().String())
	if isLocalIP(hostIP) {
		conn.Close()
		return
	}

	n.peersLock.Lock()
	if existing, exists := n.peers[hostIP]; exists && existing != nil && existing != conn {
		n.peersLock.Unlock()
		conn.Close()
		return
	}
	n.peers[hostIP] = conn
	n.syncPeersFileLocked()
	n.peersLock.Unlock()

	fmt.Printf("⚡ Connected with peer: %s\n", hostIP)

	defer func() {
		n.peersLock.Lock()
		if currConn, exists := n.peers[hostIP]; exists && currConn == conn {
			delete(n.peers, hostIP)
		}
		n.syncPeersFileLocked()
		n.peersLock.Unlock()
		conn.Close()
		fmt.Printf("❌ Disconnected from: %s\n", hostIP)
	}()

	decoder := json.NewDecoder(conn)
	for {
		var msg SyncMessage
		if err := decoder.Decode(&msg); err != nil {
			return
		}

		if msg.Type == "encrypted" {
			if n.crypto == nil {
				fmt.Printf("⚠️  Received encrypted message from %s but no encryption key loaded\n", hostIP)
				continue
			}
			decrypted, err := n.crypto.Decrypt(msg.Payload)
			if err != nil {
				fmt.Printf("🔒 [Security] Rejected untrusted message from %s (wrong key / tampered)\n", hostIP)
				continue
			}
			var innerMsg SyncMessage
			if err := json.Unmarshal(decrypted, &innerMsg); err != nil {
				continue
			}
			msg = innerMsg
		}

		switch msg.Type {
		case "ping", "pong":
			continue

		case "url":
			n.handleURL(msg)

		case "notification":
			n.handleNotification(msg)

		case "battery":
			n.handleBattery(msg)

		case "call":
			n.handleCall(msg)

		case "clipboard", "":
			cleanMsg := strings.TrimSpace(msg.Content)
			n.mu.Lock()
			if cleanMsg != "" && cleanMsg != strings.TrimSpace(n.lastReceived) && msg.Hash != n.lastHash {
				n.lastHash = msg.Hash
				n.lastReceived = msg.Content
				n.mu.Unlock()

				fmt.Printf("📥 [%s] Received clipboard from %s (%d bytes): %.30s...\n",
					time.Now().Format("15:04:05"), msg.DeviceID, len(msg.Content), strings.ReplaceAll(msg.Content, "\n", " "))
				_ = n.writeClipboard(msg.Content)

				if isOTP(cleanMsg) {
					sendDesktopNotification("🔑 OTP Copied to Clipboard", cleanMsg, "dialog-password", "normal")
				} else {
					preview := cleanMsg
					if len(preview) > 50 {
						preview = preview[:50] + "..."
					}
					sendDesktopNotification("📋 Clipboard Synced from Phone", preview, "edit-copy", "low")
				}
			} else {
				n.mu.Unlock()
			}
		}
	}
}

func (n *SyncNode) startTCPServer() {
	listener, err := net.Listen("tcp", fmt.Sprintf("0.0.0.0:%d", TCPPort))
	if err != nil {
		return
	}
	defer listener.Close()

	for {
		conn, err := listener.Accept()
		if err != nil {
			continue
		}
		go n.handlePeer(conn)
	}
}

func (n *SyncNode) connectToPeer(ip string) {
	cleanIP := strings.TrimPrefix(ip, "::ffff:")
	if isLocalIP(cleanIP) {
		return
	}
	n.peersLock.Lock()
	if _, exists := n.peers[cleanIP]; exists {
		n.peersLock.Unlock()
		return
	}
	n.peersLock.Unlock()

	conn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", cleanIP, TCPPort), 2*time.Second)
	if err != nil {
		return
	}
	go n.handlePeer(conn)
}

func isOTP(s string) bool {
	clean := strings.TrimSpace(s)
	if len(clean) >= 4 && len(clean) <= 8 {
		for _, r := range clean {
			if r < '0' || r > '9' {
				return false
			}
		}
		return true
	}
	return false
}

func (n *SyncNode) startHeartbeat() {
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		n.peersLock.Lock()
		if len(n.peers) > 0 {
			pingMsg := SyncMessage{
				Type:     "ping",
				DeviceID: n.deviceID,
			}
			innerData, _ := json.Marshal(pingMsg)
			var data []byte
			if n.crypto != nil {
				if enc, err := n.crypto.Encrypt(innerData); err == nil {
					data, _ = json.Marshal(SyncMessage{
						Type:     "encrypted",
						DeviceID: n.deviceID,
						Payload:  enc,
					})
				} else {
					data = innerData
				}
			} else {
				data = innerData
			}
			data = append(data, byte(10))

			for host, conn := range n.peers {
				_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
				if _, err := conn.Write(data); err != nil {
					conn.Close()
					delete(n.peers, host)
				}
				_ = conn.SetWriteDeadline(time.Time{})
			}
		}
		n.peersLock.Unlock()
	}
}

func (n *SyncNode) startReconnectWatchdog() {
	ticker := time.NewTicker(4 * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		n.peersLock.Lock()
		peerCount := len(n.peers)
		n.peersLock.Unlock()

		if peerCount == 0 {
			if data, err := os.ReadFile(PeerFile); err == nil {
				lastIP := strings.TrimSpace(string(data))
				if lastIP != "" && !isLocalIP(lastIP) {
					n.connectToPeer(lastIP)
				}
			}
		}
	}
}
