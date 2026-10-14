// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

package main

import (
	"fmt"
	"net"
	"os"
	"strings"
	"time"
)

func (n *SyncNode) startUDPDiscovery() {
	go func() {
		broadcastAddr, err := net.ResolveUDPAddr("udp", fmt.Sprintf("255.255.255.255:%d", UDPPort))
		if err != nil {
			return
		}
		conn, err := net.DialUDP("udp", nil, broadcastAddr)
		if err != nil {
			return
		}
		defer conn.Close()

		beacon := []byte(fmt.Sprintf("CLIP_SYNC:%s", n.deviceID))
		for {
			_, _ = conn.Write(beacon)
			time.Sleep(4 * time.Second)
		}
	}()

	addr, err := net.ResolveUDPAddr("udp", fmt.Sprintf(":%d", UDPPort))
	if err != nil {
		return
	}
	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		return
	}
	defer conn.Close()

	buf := make([]byte, 1024)
	for {
		nBytes, remoteAddr, err := conn.ReadFromUDP(buf)
		if err != nil {
			continue
		}
		msg := string(buf[:nBytes])
		if strings.HasPrefix(msg, "CLIP_SYNC:") {
			peerID := strings.TrimPrefix(msg, "CLIP_SYNC:")
			if peerID != n.deviceID {
				peerIP := remoteAddr.IP.String()
				cleanIP := strings.TrimPrefix(peerIP, "::ffff:")
				n.peersLock.Lock()
				_, alreadyConnected := n.peers[cleanIP]
				n.peersLock.Unlock()

				if !alreadyConnected {
					go n.connectToPeer(cleanIP)
				}
			}
		}
	}
}

func discoverPhoneIP() string {
	// 1. Check cached peer file
	if data, err := os.ReadFile(PeerFile); err == nil {
		ip := strings.TrimSpace(string(data))
		if ip != "" {
			conn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", ip, FilePort), 800*time.Millisecond)
			if err == nil {
				conn.Close()
				return ip
			}
		}
	}

	// 2. Fallback: Quick 1.5-second UDP discovery
	broadcastAddr, err := net.ResolveUDPAddr("udp", fmt.Sprintf("255.255.255.255:%d", UDPPort))
	if err != nil {
		return ""
	}
	conn, err := net.ListenUDP("udp", &net.UDPAddr{Port: 0})
	if err != nil {
		return ""
	}
	defer conn.Close()

	beacon := []byte("CLIP_SYNC:ping")
	_, _ = conn.WriteTo(beacon, broadcastAddr)
	_ = conn.SetReadDeadline(time.Now().Add(1500 * time.Millisecond))

	buf := make([]byte, 1024)
	for {
		n, remoteAddr, err := conn.ReadFromUDP(buf)
		if err != nil {
			break
		}
		msg := string(buf[:n])
		if strings.HasPrefix(msg, "CLIP_SYNC:") {
			ip := strings.TrimPrefix(remoteAddr.IP.String(), "::ffff:")
			return ip
		}
	}
	return ""
}
