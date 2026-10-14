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

// extractURL finds the first http:// or https:// URL or domain-like string in text
func extractURL(text string) string {
	re := regexp.MustCompile(`https?://[^\s<>"{}|\\^` + "`" + `]+`)
	if match := re.FindString(text); match != "" {
		return match
	}
	clean := strings.TrimSpace(text)
	if strings.HasPrefix(clean, "www.") {
		return "https://" + clean
	}
	// Check standard domain pattern like google.com or domain.tld/path
	domainRe := regexp.MustCompile(`(?i)^[a-z0-9-]+(\.[a-z0-9-]+)+(/[^\s]*)?$`)
	if domainRe.MatchString(clean) && !fileExists(clean) {
		return "https://" + clean
	}
	return ""
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
		cleanURL = strings.TrimSpace(urlStr)
		if !strings.HasPrefix(cleanURL, "http://") && !strings.HasPrefix(cleanURL, "https://") {
			cleanURL = "https://" + cleanURL
		}
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
	innerData, _ := json.Marshal(msg)

	crypto := LoadOrCreateCrypto()
	var data []byte
	if payload, err := crypto.Encrypt(innerData); err == nil {
		encMsg := SyncMessage{
			Type:     "encrypted",
			DeviceID: "linux-cli",
			Payload:  payload,
		}
		data, _ = json.Marshal(encMsg)
	} else {
		data = innerData
	}
	data = append(data, byte(10))

	_, err = conn.Write(data)
	if err != nil {
		fmt.Printf("❌ Failed to send URL to phone: %v\n", err)
		sendDesktopNotification("ClipSync Send Failed", fmt.Sprintf("Failed to send URL: %v", err), "dialog-warning", "normal")
		os.Exit(1)
	}

	// Flush delay so OS TCP stack delivers data before socket close
	time.Sleep(150 * time.Millisecond)

	fmt.Printf("✓ Sent link to Phone (%s)! Opened in browser: %s\n", phoneIP, cleanURL)
	sendDesktopNotification("🌐 Opening Tab on Phone", cleanURL, "web-browser", "normal")
}

func sendClipboardLinkToPhone() {
	var targetURL string
	var fromPrimary bool

	// 1. Priority 1: Check Standard Clipboard (what the user actively copied via Ctrl+C, Ctrl+L, or Copy Link)
	if cmdExists("wl-paste") {
		ctx, cancel := context.WithTimeout(context.Background(), 800*time.Millisecond)
		defer cancel()
		cmd := exec.CommandContext(ctx, "wl-paste", "-n")
		if out, err := cmd.Output(); err == nil {
			targetURL = extractURL(string(out))
		}
	}

	if targetURL == "" {
		_ = clipboard.Init()
		if data, err := clipboard.Read(context.Background(), clipboard.FmtText); err == nil && len(data) > 0 {
			targetURL = extractURL(string(data))
		}
	}

	if targetURL == "" && cmdExists("xclip") {
		ctx, cancel := context.WithTimeout(context.Background(), 800*time.Millisecond)
		defer cancel()
		cmd := exec.CommandContext(ctx, "xclip", "-selection", "clipboard", "-o")
		if out, err := cmd.Output(); err == nil {
			targetURL = extractURL(string(out))
		}
	}

	// 2. Priority 2: Fallback to Primary Selection (mouse highlight) only if clipboard has no link
	if targetURL == "" {
		if cmdExists("wl-paste") {
			ctx, cancel := context.WithTimeout(context.Background(), 800*time.Millisecond)
			defer cancel()
			cmd := exec.CommandContext(ctx, "wl-paste", "-p", "-n")
			if out, err := cmd.Output(); err == nil {
				if u := extractURL(string(out)); u != "" {
					targetURL = u
					fromPrimary = true
				}
			}
		}
		if targetURL == "" && cmdExists("xclip") {
			ctx, cancel := context.WithTimeout(context.Background(), 800*time.Millisecond)
			defer cancel()
			cmd := exec.CommandContext(ctx, "xclip", "-selection", "primary", "-o")
			if out, err := cmd.Output(); err == nil {
				if u := extractURL(string(out)); u != "" {
					targetURL = u
					fromPrimary = true
				}
			}
		}
	}

	if targetURL == "" {
		fmt.Println("⚠️ No URL found in clipboard or mouse selection.")
		fmt.Println("👉 To send a tab: In Chrome press Ctrl+L then Ctrl+C, or highlight the link with mouse, then press Ctrl+Alt+P")
		sendDesktopNotification(
			"⚠️ No Link Found",
			"To send a tab: In Chrome press Ctrl+L then Ctrl+C, or highlight the link with mouse, then press Ctrl+Alt+P",
			"dialog-warning",
			"normal",
		)
		return
	}

	// If sent from mouse highlight (primary selection), clear primary selection so it doesn't linger stale
	if fromPrimary && cmdExists("wl-copy") {
		_ = exec.Command("wl-copy", "-p", "-c").Run()
	}

	sendURLToPhone(targetURL)
}
