package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"
)

const (
	UDPPort = 9876
	TCPPort = 9877
)

type ClipboardMessage struct {
	DeviceID string `json:"device_id"`
	Hash     string `json:"hash"`
	Content  string `json:"content"`
}

type SyncNode struct {
	deviceID     string
	lastHash     string
	lastReceived string
	mu           sync.Mutex
	peers        map[string]net.Conn
	peersLock    sync.Mutex
}

func hashContent(s string) string {
	clean := strings.TrimSpace(s)
	h := sha256.Sum256([]byte(clean))
	return hex.EncodeToString(h[:])
}

func getHostIP(addr string) string {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return addr
	}
	return host
}

func readLocalClipboard() (string, error) {
	if cmdExists("wl-paste") {
		out, err := exec.Command("wl-paste", "-n").Output()
		if err == nil {
			return string(out), nil
		}
	}
	if cmdExists("xclip") {
		out, err := exec.Command("xclip", "-selection", "clipboard", "-o").Output()
		if err == nil {
			return string(out), nil
		}
	}
	return "", fmt.Errorf("no clipboard utility found")
}

func writeLocalClipboard(text string) error {
	if cmdExists("wl-copy") {
		cmd := exec.Command("wl-copy")
		cmd.Stdin = strings.NewReader(text)
		return cmd.Run()
	}
	if cmdExists("xclip") {
		cmd := exec.Command("xclip", "-selection", "clipboard")
		cmd.Stdin = strings.NewReader(text)
		return cmd.Run()
	}
	return fmt.Errorf("no clipboard utility found")
}

func cmdExists(cmd string) bool {
	_, err := exec.LookPath(cmd)
	return err == nil
}

func NewSyncNode() *SyncNode {
	hostname, _ := os.Hostname()
	if hostname == "" {
		hostname = "device"
	}
	return &SyncNode{
		deviceID: fmt.Sprintf("%s-%d", hostname, time.Now().Unix()%10000),
		peers:    make(map[string]net.Conn),
	}
}

func (n *SyncNode) Broadcast(content string, hash string) {
	n.peersLock.Lock()
	defer n.peersLock.Unlock()

	msg := ClipboardMessage{
		DeviceID: n.deviceID,
		Hash:     hash,
		Content:  content,
	}
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

func (n *SyncNode) handlePeer(conn net.Conn) {
	hostIP := getHostIP(conn.RemoteAddr().String())

	n.peersLock.Lock()
	if existing, exists := n.peers[hostIP]; exists && existing != nil {
		n.peersLock.Unlock()
		conn.Close()
		return
	}
	n.peers[hostIP] = conn
	n.peersLock.Unlock()

	fmt.Printf("⚡ Connected with peer: %s\n", hostIP)

	defer func() {
		n.peersLock.Lock()
		if currConn, exists := n.peers[hostIP]; exists && currConn == conn {
			delete(n.peers, hostIP)
		}
		n.peersLock.Unlock()
		conn.Close()
	}()

	decoder := json.NewDecoder(conn)
	for {
		var msg ClipboardMessage
		if err := decoder.Decode(&msg); err != nil {
			return
		}

		cleanMsg := strings.TrimSpace(msg.Content)
		n.mu.Lock()
		if cleanMsg != "" && cleanMsg != strings.TrimSpace(n.lastReceived) && msg.Hash != n.lastHash {
			n.lastHash = msg.Hash
			n.lastReceived = msg.Content
			n.mu.Unlock()

			fmt.Printf("📥 [%s] Received from %s (%d bytes): %.30s...\n",
				time.Now().Format("15:04:05"), msg.DeviceID, len(msg.Content), strings.ReplaceAll(msg.Content, "\n", " "))
			_ = writeLocalClipboard(msg.Content)
		} else {
			n.mu.Unlock()
		}
	}
}

func (n *SyncNode) startTCPServer() {
	listener, err := net.Listen("tcp", fmt.Sprintf(":%d", TCPPort))
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
	n.peersLock.Lock()
	if _, exists := n.peers[ip]; exists {
		n.peersLock.Unlock()
		return
	}
	n.peersLock.Unlock()

	conn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", ip, TCPPort), 2*time.Second)
	if err != nil {
		return
	}
	go n.handlePeer(conn)
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
			if peerID != n.deviceID {
				peerIP := remoteAddr.IP.String()
				n.peersLock.Lock()
				_, alreadyConnected := n.peers[peerIP]
				n.peersLock.Unlock()

				if !alreadyConnected {
					go n.connectToPeer(peerIP)
				}
			}
		}
	}
}

func (n *SyncNode) startClipboardWatcher() {
	for {
		text, err := readLocalClipboard()
		if err == nil && text != "" {
			cleanText := strings.TrimSpace(text)
			h := hashContent(cleanText)

			n.mu.Lock()
			// STRICT ECHO GUARD: Do not broadcast if it matches what we received or sent!
			if cleanText != strings.TrimSpace(n.lastReceived) && h != n.lastHash {
				n.lastHash = h
				n.lastReceived = text
				n.mu.Unlock()

				fmt.Printf("📤 [%s] Local clipboard changed (%d bytes): %.30s...\n",
					time.Now().Format("15:04:05"), len(text), strings.ReplaceAll(text, "\n", " "))
				n.Broadcast(text, h)
			} else {
				n.mu.Unlock()
			}
		}
		time.Sleep(1000 * time.Millisecond) // 1 second is gentle on GNOME and feels instant
	}
}

func main() {
	node := NewSyncNode()
	fmt.Printf("🚀 ClipSync (with Echo Guard) started!\n")
	fmt.Printf("📱 Device ID: %s\n", node.deviceID)
	fmt.Printf("📡 Ready to sync.\n")

	go node.startTCPServer()
	go node.startUDPDiscovery()

	node.startClipboardWatcher()
}
