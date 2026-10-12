package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
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
	deviceID  string
	lastHash  string
	hashLock  sync.Mutex
	peers     map[string]net.Conn
	peersLock sync.Mutex
}

func hashContent(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}

// readLocalClipboard gets clipboard text (supports Linux Wayland, X11, Termux/Android)
func readLocalClipboard() (string, error) {
	if cmdExists("wl-paste") {
		out, err := exec.Command("wl-paste", "--no-newline").Output()
		if err == nil {
			return string(out), nil
		}
	}
	if cmdExists("termux-clipboard-get") {
		out, err := exec.Command("termux-clipboard-get").Output()
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

// writeLocalClipboard sets clipboard text
func writeLocalClipboard(text string) error {
	if cmdExists("wl-copy") {
		cmd := exec.Command("wl-copy")
		cmd.Stdin = strings.NewReader(text)
		return cmd.Run()
	}
	if cmdExists("termux-clipboard-set") {
		cmd := exec.Command("termux-clipboard-set")
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
	data = append(data, '\n')

	for addr, conn := range n.peers {
		_, err := conn.Write(data)
		if err != nil {
			conn.Close()
			delete(n.peers, addr)
		}
	}
}

func (n *SyncNode) handlePeer(conn net.Conn) {
	remoteAddr := conn.RemoteAddr().String()
	n.peersLock.Lock()
	n.peers[remoteAddr] = conn
	n.peersLock.Unlock()

	defer func() {
		n.peersLock.Lock()
		delete(n.peers, remoteAddr)
		n.peersLock.Unlock()
		conn.Close()
	}()

	decoder := json.NewDecoder(conn)
	for {
		var msg ClipboardMessage
		if err := decoder.Decode(&msg); err != nil {
			if err != io.EOF {
				// connection dropped
			}
			return
		}

		n.hashLock.Lock()
		if msg.Hash != n.lastHash && msg.Content != "" {
			n.lastHash = msg.Hash
			n.hashLock.Unlock()

			fmt.Printf("📥 [%s] Received clipboard (%d bytes): %.30s...\n",
				time.Now().Format("15:04:05"), len(msg.Content), strings.ReplaceAll(msg.Content, "\n", " "))
			_ = writeLocalClipboard(msg.Content)
		} else {
			n.hashLock.Unlock()
		}
	}
}

func (n *SyncNode) startTCPServer() {
	listener, err := net.Listen("tcp", fmt.Sprintf(":%d", TCPPort))
	if err != nil {
		fmt.Printf("TCP Server error: %v\n", err)
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
	addr := fmt.Sprintf("%s:%d", ip, TCPPort)
	n.peersLock.Lock()
	if _, exists := n.peers[addr]; exists {
		n.peersLock.Unlock()
		return
	}
	n.peersLock.Unlock()

	conn, err := net.DialTimeout("tcp", addr, 2*time.Second)
	if err != nil {
		return
	}
	go n.handlePeer(conn)
}

func (n *SyncNode) startUDPDiscovery() {
	// 1. Broadcaster (Announces presence every 3 seconds)
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
			time.Sleep(3 * time.Second)
		}
	}()

	// 2. Listener (Discovers other devices on the Wi-Fi)
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
				go n.connectToPeer(remoteAddr.IP.String())
			}
		}
	}
}

func (n *SyncNode) startClipboardWatcher() {
	for {
		text, err := readLocalClipboard()
		if err == nil && text != "" {
			h := hashContent(text)
			n.hashLock.Lock()
			if h != n.lastHash {
				n.lastHash = h
				n.hashLock.Unlock()

				fmt.Printf("📤 [%s] Local clipboard changed (%d bytes): %.30s...\n",
					time.Now().Format("15:04:05"), len(text), strings.ReplaceAll(text, "\n", " "))
				n.Broadcast(text, h)
			} else {
				n.hashLock.Unlock()
			}
		}
		time.Sleep(300 * time.Millisecond)
	}
}

func main() {
	node := NewSyncNode()
	fmt.Printf("🚀 Clipboard Sync Daemon started!\n")
	fmt.Printf("📱 Device ID: %s\n", node.deviceID)
	fmt.Printf("📡 Listening on TCP :%d, Discovery UDP :%d\n", TCPPort, UDPPort)

	go node.startTCPServer()
	go node.startUDPDiscovery()

	node.startClipboardWatcher()
}
