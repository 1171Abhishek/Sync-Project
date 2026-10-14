// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

package main

import (
	"fmt"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"time"
)

func sendDesktopNotification(title, message, icon, urgency string) {
	if cmdExists("notify-send") {
		args := []string{"-a", "ClipSync"}
		if icon != "" {
			args = append(args, "-i", icon)
		}
		if urgency != "" {
			args = append(args, "-u", urgency)
		}
		args = append(args, title, message)
		_ = exec.Command("notify-send", args...).Run()
	}
}

// extractOTP smartly identifies 4-8 digit verification codes / OTPs
func extractOTP(text string) string {
	lower := strings.ToLower(text)
	keywords := []string{"otp", "code", "verification", "passcode", "one-time", "pin", "secret", "verify", "auth"}
	hasKeyword := false
	for _, kw := range keywords {
		if strings.Contains(lower, kw) {
			hasKeyword = true
			break
		}
	}
	if !hasKeyword {
		return ""
	}

	// 1. Google G-XXXXXX pattern
	if m := regexp.MustCompile(`(?i)\bG-([0-9]{6})\b`).FindStringSubmatch(text); len(m) > 1 {
		return m[1]
	}

	// 2. Keyword followed by code: e.g. "code is 123456", "OTP: 1234", "passcode: 987654"
	reNearby := regexp.MustCompile(`(?i)(?:code|otp|pin|passcode|secret)[^0-9a-zA-Z]{0,10}(?:is\s+)?([0-9]{4,8})\b`)
	if m := reNearby.FindStringSubmatch(text); len(m) > 1 {
		return m[1]
	}

	// 3. Code followed by keyword: e.g. "123456 is your code/OTP"
	reBefore := regexp.MustCompile(`\b([0-9]{4,8})\b\s+(?:is\s+your\s+)?(?:code|otp|verification|pin|passcode)`)
	if m := reBefore.FindStringSubmatch(text); len(m) > 1 {
		return m[1]
	}

	// 4. Standalone 4-8 digit numbers in message with keywords, excluding current/next years
	reDigits := regexp.MustCompile(`\b([0-9]{4,8})\b`)
	matches := reDigits.FindAllStringSubmatch(text, -1)
	currentYear := time.Now().Year()
	for _, match := range matches {
		if len(match) > 1 {
			val := match[1]
			if len(val) == 4 {
				yearVal, _ := strconv.Atoi(val)
				if yearVal >= currentYear-1 && yearVal <= currentYear+2 {
					continue
				}
			}
			return val
		}
	}

	return ""
}

func (n *SyncNode) handleNotification(msg SyncMessage) {
	appName := msg.AppName
	if appName == "" {
		appName = "Phone"
	}
	title := msg.Title
	if title == "" {
		title = "New Notification"
	}
	content := strings.TrimSpace(msg.Content)

	// Check if an OTP was provided or can be extracted
	otp := msg.OTP
	if otp == "" {
		otp = extractOTP(content)
		if otp == "" {
			otp = extractOTP(title)
		}
	}

	if otp != "" {
		// Auto-copy OTP to clipboard!
		_ = n.writeClipboard(otp)

		fmt.Printf("🔑 [%s] Auto-copied OTP '%s' from %s (%s) to Linux clipboard!\n",
			time.Now().Format("15:04:05"), otp, appName, title)

		// Urgent notification so user knows OTP is ready for Ctrl+V
		sendDesktopNotification(
			fmt.Sprintf("🔑 OTP Copied: %s", otp),
			fmt.Sprintf("%s: %s\n(Press Ctrl+V to paste)", appName, content),
			"security-high",
			"critical",
		)
		return
	}

	// Normal phone notification mirror
	fmt.Printf("🔔 [%s] Notification from %s (%s): %.40s\n",
		time.Now().Format("15:04:05"), appName, title, content)

	sendDesktopNotification(
		fmt.Sprintf("📱 %s: %s", appName, title),
		content,
		"dialog-information",
		"normal",
	)
}
