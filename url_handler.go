// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

package main

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"time"
)

// extractURL finds the first http:// or https:// URL in a text string
func extractURL(text string) string {
	re := regexp.MustCompile(`https?://[^\s<>"{}|\\^` + "`" + `]+`)
	return re.FindString(text)
}

// openBrowserTab launches the default browser with the URL in a new tab
func openBrowserTab(urlStr string) error {
	if cmdExists("xdg-open") {
		cmd := exec.Command("xdg-open", urlStr)
		return cmd.Start()
	}
	return fmt.Errorf("xdg-open utility not found")
}

func (n *SyncNode) handleURL(msg SyncMessage) {
	targetURL := strings.TrimSpace(msg.URL)
	if targetURL == "" {
		targetURL = extractURL(msg.Content)
	}
	if targetURL == "" {
		return
	}

	// 1. Copy URL to Linux clipboard
	_ = n.writeClipboard(targetURL)

	// 2. Open as a new tab in the default browser
	err := openBrowserTab(targetURL)
	if err != nil {
		fmt.Printf("⚠️ Failed to open browser tab: %v\n", err)
	} else {
		fmt.Printf("🌐 [%s] Opened new browser tab from %s: %s\n",
			time.Now().Format("15:04:05"), msg.DeviceID, targetURL)
	}

	// 3. Desktop notification
	sendDesktopNotification(
		"🌐 Tab Opened from Phone",
		targetURL,
		"web-browser",
		"normal",
	)
}

func sendURLToPhone(urlStr string) {
	fmt.Printf("🔍 Looking for connected phone on Wi-Fi...\n")
	phoneIP := discoverPhoneIP()
	if phoneIP == "" {
		fmt.Println("❌ No phone found on local Wi-Fi!")
		fmt.Println("👉 Make sure your phone is connected to the same Wi-Fi and ClipSync is running.")
		sendDesktopNotification("ClipSync Send Failed", "No phone found on local Wi-Fi", "dialog-warning", "normal")
		os.Exit(1)
	}

	conn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", phoneIP, TCPPort), 3*time.Second)
	if err != nil {
		fmt.Printf("❌ Failed to connect to phone at %s:%d: %v\n", phoneIP, TCPPort, err)
		os.Exit(1)
	}
	defer conn.Close()

	msg := SyncMessage{
		Type:      "url",
		DeviceID:  "linux-cli",
		URL:       urlStr,
		Content:   urlStr,
		Timestamp: time.Now().Unix(),
	}
	data, _ := json.Marshal(msg)
	data = append(data, byte(10))

	_, err = conn.Write(data)
	if err != nil {
		fmt.Printf("❌ Failed to send URL to phone: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("✓ Sent link to Phone (%s)! Opened in browser.\n", phoneIP)
	sendDesktopNotification("ClipSync: Link Sent", fmt.Sprintf("Opened %s on Phone", urlStr), "web-browser", "normal")
}
