package main

import (
	"context"
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

	"golang.design/x/clipboard"
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
	nativeClip   bool
}

func hashContent(s string) string {
	clean := strings.TrimSpace(s)
	h := sha256.Sum256([]byte(clean))
	return hex.EncodeToString(h[:])
}

func getHostIP(addr string) string {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		host = addr
	}
	host = strings.TrimPrefix(host, "::ffff:")
	return host
}

func (n *SyncNode) writeClipboard(text string) error {
	if n.nativeClip {
		_, err := clipboard.Write(context.Background(), clipboard.FmtText, []byte(text))
		if err == nil {
			return nil
		}
	}
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
	if existing, exists := n.peers[hostIP]; exists && existing != nil && existing != conn {
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
		fmt.Printf("❌ Disconnected from: %s\n", hostIP)
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
			_ = n.writeClipboard(msg.Content)
		} else {
			n.mu.Unlock()
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

func (n *SyncNode) startClipboardWatcher() {
	if n.nativeClip {
		fmt.Printf("⚡ In-Process Event Watcher Active (0%% CPU, 0 Process Forks)\n")
		ch := clipboard.Watch(context.Background())
		for data := range ch {
			text := string(data.Bytes)
			cleanText := strings.TrimSpace(text)
			if cleanText == "" {
				continue
			}
			h := hashContent(cleanText)

			n.mu.Lock()
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
		return
	}

	fmt.Printf("⚠️ Falling back to gentle polling\n")
	for {
		if cmdExists("wl-paste") {
			out, err := exec.Command("wl-paste", "-n").Output()
			if err == nil {
				text := string(out)
				cleanText := strings.TrimSpace(text)
				if cleanText != "" {
					h := hashContent(cleanText)
					n.mu.Lock()
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
			}
		}
		time.Sleep(2 * time.Second)
	}
}

func main() {
	node := NewSyncNode()
	fmt.Printf("🚀 ClipSync started!\n")
	fmt.Printf("📱 Device ID: %s\n", node.deviceID)
	fmt.Printf("📡 Ready to sync.\n")

	go node.startTCPServer()
	go node.startUDPDiscovery()

	node.startClipboardWatcher()
}
