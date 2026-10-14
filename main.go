// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"golang.design/x/clipboard"
)

const (
	UDPPort  = 9876
	TCPPort  = 9877
	FilePort = 9878
	PeerFile = "/tmp/clipsync_peer"
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

func getDownloadsDir() string {
	home, err := os.UserHomeDir()
	if err == nil {
		downloads := filepath.Join(home, "Downloads")
		if info, err := os.Stat(downloads); err == nil && info.IsDir() {
			return downloads
		}
		return home
	}
	return "."
}

func getUniqueFilename(dir, baseName string) string {
	ext := filepath.Ext(baseName)
	nameOnly := strings.TrimSuffix(baseName, ext)
	target := filepath.Join(dir, baseName)

	counter := 1
	for {
		if _, err := os.Stat(target); os.IsNotExist(err) {
			return target
		}
		target = filepath.Join(dir, fmt.Sprintf("%s (%d)%s", nameOnly, counter, ext))
		counter++
	}
}

func formatSize(bytes int64) string {
	const unit = 1024
	if bytes < unit {
		return fmt.Sprintf("%d B", bytes)
	}
	div, exp := int64(unit), 0
	for n := bytes / unit; n >= unit; n /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %cB", float64(bytes)/float64(div), "KMGTPE"[exp])
}

func sendNotification(title, message string) {
	if cmdExists("notify-send") {
		_ = exec.Command("notify-send", "-a", "ClipSync", "-i", "document-save", title, message).Run()
	}
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

func fileExists(path string) bool {
	info, err := os.Stat(path)
	if err != nil {
		return false
	}
	return !info.IsDir()
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

func (n *SyncNode) startFileServer() {
	mux := http.NewServeMux()
	mux.HandleFunc("/upload", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}

		rawFilename := r.Header.Get("X-Filename")
		if decoded, err := url.QueryUnescape(rawFilename); err == nil && decoded != "" {
			rawFilename = decoded
		}
		if rawFilename == "" {
			rawFilename = fmt.Sprintf("received_%d", time.Now().Unix())
		}
		safeFilename := filepath.Base(filepath.Clean(rawFilename))
		if safeFilename == "." || safeFilename == "/" {
			safeFilename = fmt.Sprintf("file_%d", time.Now().Unix())
		}

		downloadsDir := getDownloadsDir()
		destPath := getUniqueFilename(downloadsDir, safeFilename)

		out, err := os.Create(destPath)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		defer out.Close()

		nBytes, err := io.Copy(out, r.Body)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		sizeStr := formatSize(nBytes)
		finalName := filepath.Base(destPath)
		fmt.Printf("📥 [%s] Received file from %s: %s (%s) -> %s\n",
			time.Now().Format("15:04:05"), getHostIP(r.RemoteAddr), finalName, sizeStr, destPath)

		sendNotification("ClipSync: File Received", fmt.Sprintf("📥 %s (%s)\nSaved to Downloads", finalName, sizeStr))

		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("OK\n"))
	})

	server := &http.Server{
		Addr:         fmt.Sprintf("0.0.0.0:%d", FilePort),
		Handler:      mux,
		ReadTimeout:  10 * time.Minute,
		WriteTimeout: 10 * time.Minute,
	}

	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		fmt.Printf("⚠️ File server warning: %v\n", err)
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

func discoverPhoneIP() string {
	// 1. Check cached peer file
	if data, err := os.ReadFile(PeerFile); err == nil {
		ip := strings.TrimSpace(string(data))
		if ip != "" {
			// Quick test connection
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

func sendFileToPhone(filePath string) {
	fileInfo, err := os.Stat(filePath)
	if err != nil {
		fmt.Printf("❌ File not found: %s\n", filePath)
		os.Exit(1)
	}
	if fileInfo.IsDir() {
		fmt.Printf("❌ Cannot send directory: %s\n", filePath)
		os.Exit(1)
	}

	fmt.Printf("🔍 Looking for connected phone on Wi-Fi...\n")
	phoneIP := discoverPhoneIP()
	if phoneIP == "" {
		fmt.Println("❌ No phone found on local Wi-Fi!")
		fmt.Println("👉 Make sure your phone is connected to the same Wi-Fi and ClipSync is running.")
		sendNotification("ClipSync Send Failed", "No phone found on local Wi-Fi")
		os.Exit(1)
	}
 
	file, err := os.Open(filePath)
	if err != nil {
		fmt.Printf("❌ Failed to open file: %v\n", err)
		os.Exit(1)
	}
	defer file.Close()

	fileName := filepath.Base(filePath)
	sizeStr := formatSize(fileInfo.Size())
	fmt.Printf("📤 Sending %s (%s) to Phone (%s)...\n", fileName, sizeStr, phoneIP)

	urlTarget := fmt.Sprintf("http://%s:%d/upload", phoneIP, FilePort)
	req, err := http.NewRequest("POST", urlTarget, file)
	if err != nil {
		fmt.Printf("❌ Failed to create request: %v\n", err)
		os.Exit(1)
	}

	req.Header.Set("X-Filename", url.QueryEscape(fileName))
	req.Header.Set("Content-Type", "application/octet-stream")
	req.ContentLength = fileInfo.Size()

	client := &http.Client{
		Timeout: 5 * time.Minute,
	}

	resp, err := client.Do(req)
	if err != nil {
		fmt.Printf("❌ Upload failed: %v\n", err)
		sendNotification("ClipSync Send Failed", fmt.Sprintf("Failed to send %s to phone", fileName))
		os.Exit(1)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusOK {
		fmt.Printf("✓ Successfully sent %s to Phone! Saved to Gallery / Downloads.\n", fileName)
		sendNotification("ClipSync: Sent to Phone", fmt.Sprintf("✓ %s (%s) sent to Phone", fileName, sizeStr))
	} else {
		fmt.Printf("⚠️ Phone responded with status: %s\n", resp.Status)
		os.Exit(1)
	}
}

func main() {
	if len(os.Args) > 1 {
		arg := os.Args[1]
		if arg == "send" || fileExists(arg) {
			targetFile := arg
			if arg == "send" {
				if len(os.Args) < 3 {
					fmt.Println("Usage: clip-sync send <file-path>")
					os.Exit(1)
				}
				targetFile = os.Args[2]
			}
			sendFileToPhone(targetFile)
			return
		}
		if arg == "-h" || arg == "--help" {
			fmt.Println("ClipSync - Seamless Linux <-> Android Continuity")
			fmt.Println("Usage:")
			fmt.Println("  clip-sync               Start background clipboard & file daemon")
			fmt.Println("  clip-sync send <file>   Send image or file to your Phone")
			return
		}
	}

	node := NewSyncNode()
	fmt.Printf("🚀 ClipSync started!\n")
	fmt.Printf("📱 Device ID: %s\n", node.deviceID)
	fmt.Printf("📡 Clipboard Sync listening on TCP :%d\n", TCPPort)
	fmt.Printf("📁 File Drop receiver listening on HTTP :%d (saving to %s)\n", FilePort, getDownloadsDir())

	go node.startTCPServer()
	go node.startFileServer()
	go node.startUDPDiscovery()

	node.startClipboardWatcher()
}
