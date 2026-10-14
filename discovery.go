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

// isLocalIP returns true if the IP address belongs to the local machine or loopback
func isLocalIP(ipStr string) bool {
	clean := strings.TrimPrefix(strings.TrimSpace(ipStr), "::ffff:")
	if clean == "" || clean == "127.0.0.1" || clean == "::1" || clean == "localhost" {
		return true
	}
	parsed := net.ParseIP(clean)
	if parsed == nil || parsed.IsLoopback() || parsed.IsUnspecified() {
		return true
	}

	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return false
	}
	for _, a := range addrs {
		if ipNet, ok := a.(*net.IPNet); ok {
			if ipNet.IP.Equal(parsed) {
				return true
			}
		}
	}
	return false
}

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
			remoteIP := strings.TrimPrefix(remoteAddr.IP.String(), "::ffff:")

			// Ignore discovery beacons coming from ourselves
			if isLocalIP(remoteIP) {
				continue
			}

			// If phone probes us (e.g. ShareActivity "CLIP_SYNC:probe"), reply immediately
			if peerID == "probe" || peerID == "ping" {
				replyConn, err := net.DialUDP("udp", nil, remoteAddr)
				if err == nil {
					_, _ = replyConn.Write([]byte(fmt.Sprintf("CLIP_SYNC:%s", n.deviceID)))
					replyConn.Close()
				}
				continue
			}

			if peerID != n.deviceID {
				n.peersLock.Lock()
				_, alreadyConnected := n.peers[remoteIP]
				n.peersLock.Unlock()

				if !alreadyConnected {
					go n.connectToPeer(remoteIP)
				}
			}
		}
	}
}

func getArpNeighbors() []string {
	var neighbors []string
	data, err := os.ReadFile("/proc/net/arp")
	if err != nil {
		return neighbors
	}
	lines := strings.Split(string(data), "\n")
	for i, line := range lines {
		if i == 0 {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) >= 4 {
			ip := fields[0]
			flags := fields[2]
			mac := fields[3]
			if flags == "0x2" && mac != "00:00:00:00:00:00" && !isLocalIP(ip) {
				neighbors = append(neighbors, ip)
			}
		}
	}
	return neighbors
}

func discoverPhoneIP() string {
	var cachedIP string
	// 1. Check cached peer file
	if data, err := os.ReadFile(PeerFile); err == nil {
		cachedIP = strings.TrimSpace(string(data))
		if cachedIP != "" && !isLocalIP(cachedIP) {
			if conn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", cachedIP, FilePort), 400*time.Millisecond); err == nil {
				conn.Close()
				return cachedIP
			}
			if conn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", cachedIP, TCPPort), 400*time.Millisecond); err == nil {
				conn.Close()
				return cachedIP
			}
		}
	}

	// 2. Active UDP probe to find Phone
	broadcastAddr, err := net.ResolveUDPAddr("udp", fmt.Sprintf("255.255.255.255:%d", UDPPort))
	if err == nil {
		conn, err := net.ListenUDP("udp", &net.UDPAddr{Port: 0})
		if err == nil {
			defer conn.Close()

			beacon := []byte("CLIP_SYNC:probe")
			_, _ = conn.WriteTo(beacon, broadcastAddr)

			// Also unicast probe to cached IP and ARP neighbors (handles Wi-Fi router broadcast filtering)
			targets := getArpNeighbors()
			if cachedIP != "" && !isLocalIP(cachedIP) {
				targets = append(targets, cachedIP)
			}
			for _, target := range targets {
				if rAddr, err := net.ResolveUDPAddr("udp", fmt.Sprintf("%s:%d", target, UDPPort)); err == nil {
					_, _ = conn.WriteTo(beacon, rAddr)
				}
			}

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
					if !isLocalIP(ip) {
						// Cache valid phone IP
						_ = os.WriteFile(PeerFile, []byte(ip), 0644)
						return ip
					}
				}
			}
		}
	}

	// 3. Fallback: Quick direct TCP check on candidates
	candidates := getArpNeighbors()
	if cachedIP != "" && !isLocalIP(cachedIP) {
		candidates = append(candidates, cachedIP)
	}
	for _, ip := range candidates {
		if conn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", ip, TCPPort), 600*time.Millisecond); err == nil {
			conn.Close()
			_ = os.WriteFile(PeerFile, []byte(ip), 0644)
			return ip
		}
		if conn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", ip, FilePort), 600*time.Millisecond); err == nil {
			conn.Close()
			_ = os.WriteFile(PeerFile, []byte(ip), 0644)
			return ip
		}
	}

	return ""
}
