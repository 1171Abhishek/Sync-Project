// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"time"

	"golang.design/x/clipboard"
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
	cleanURL := extractURL(urlStr)
	if cleanURL == "" {
		cleanURL = urlStr
	}

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
		sendDesktopNotification("ClipSync Send Failed", fmt.Sprintf("Failed to connect to phone: %v", err), "dialog-warning", "normal")
		os.Exit(1)
	}
	defer conn.Close()

	msg := SyncMessage{
		Type:      "url",
		DeviceID:  "linux-cli",
		URL:       cleanURL,
		Content:   cleanURL,
		Timestamp: time.Now().Unix(),
	}
	data, _ := json.Marshal(msg)
	data = append(data, byte(10))

	_, err = conn.Write(data)
	if err != nil {
		fmt.Printf("❌ Failed to send URL to phone: %v\n", err)
		sendDesktopNotification("ClipSync Send Failed", fmt.Sprintf("Failed to send URL: %v", err), "dialog-warning", "normal")
		os.Exit(1)
	}

	fmt.Printf("✓ Sent link to Phone (%s)! Opened in browser: %s\n", phoneIP, cleanURL)
	sendDesktopNotification("🌐 Opening Tab on Phone", cleanURL, "web-browser", "normal")
}

func sendClipboardLinkToPhone() {
	var candidateText string

	// 1. Native clipboard
	if data, err := clipboard.Read(context.Background(), clipboard.FmtText); err == nil && len(data) > 0 {
		candidateText = string(data)
	}

	// 2. wl-paste standard clipboard
	if extractURL(candidateText) == "" && cmdExists("wl-paste") {
		if out, err := exec.Command("wl-paste", "-n").Output(); err == nil {
			candidateText = string(out)
		}
	}

	// 3. wl-paste primary selection (highlighted text with mouse)
	if extractURL(candidateText) == "" && cmdExists("wl-paste") {
		if out, err := exec.Command("wl-paste", "-p", "-n").Output(); err == nil {
			candidateText = string(out)
		}
	}

	// 4. xclip fallback
	if extractURL(candidateText) == "" && cmdExists("xclip") {
		if out, err := exec.Command("xclip", "-selection", "clipboard", "-o").Output(); err == nil {
			candidateText = string(out)
		}
	}

	urlStr := extractURL(candidateText)
	if urlStr == "" {
		sendDesktopNotification(
			"⚠️ No Link Found in Clipboard",
			"Copy a link first (Ctrl+C), then press Ctrl+Alt+P",
			"dialog-warning",
			"normal",
		)
		return
	}

	sendURLToPhone(urlStr)
}
