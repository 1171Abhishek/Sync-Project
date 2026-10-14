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

	return &SyncNode{
		deviceID:   fmt.Sprintf("%s-%d", hostname, time.Now().Unix()%10000),
		peers:      make(map[string]net.Conn),
		nativeClip: hasNative,
	}
}

func (n *SyncNode) BroadcastMessage(msg SyncMessage) {
	n.peersLock.Lock()
	defer n.peersLock.Unlock()

	data, err := json.Marshal(msg)
	if err != nil {
		return
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
	hostIP := getHostIP(conn.RemoteAddr().String())

	n.peersLock.Lock()
	if existing, exists := n.peers[hostIP]; exists && existing != nil && existing != conn {
		n.peersLock.Unlock()
		conn.Close()
		return
	}
	n.peers[hostIP] = conn
	n.peersLock.Unlock()

	// Cache active peer IP for CLI tools
	_ = os.WriteFile(PeerFile, []byte(hostIP), 0644)

	fmt.Printf("⚡ Connected with peer: %s\n", hostIP)

	defer func() {
		n.peersLock.Lock()
		if currConn, exists := n.peers[hostIP]; exists && currConn == conn {
			delete(n.peers, hostIP)
		}
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

		switch msg.Type {
		case "url":
			n.handleURL(msg)

		case "notification":
			n.handleNotification(msg)

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
