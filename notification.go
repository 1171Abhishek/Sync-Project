// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

package main

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"time"
)

func sendDesktopNotification(title, message, icon, urgency string) {
	sendDesktopNotificationWithID(title, message, icon, urgency, 0, 0)
}

func sendDesktopNotificationWithID(title, message, icon, urgency string, expireTimeoutMs int, replaceID uint32) uint32 {
	if !cmdExists("notify-send") {
		return 0
	}
	args := []string{"-a", "ClipSync", "-p"}
	if icon != "" {
		args = append(args, "-i", icon)
	}
	if urgency != "" {
		args = append(args, "-u", urgency)
	}
	if expireTimeoutMs > 0 {
		args = append(args, "-t", strconv.Itoa(expireTimeoutMs))
	}
	if replaceID > 0 {
		args = append(args, "-r", strconv.FormatUint(uint64(replaceID), 10))
	}
	args = append(args, title, message)
	out, err := exec.Command("notify-send", args...).Output()
	if err != nil {
		// Fallback for notify-send builds without -p
		fallbackArgs := []string{"-a", "ClipSync"}
		if icon != "" {
			fallbackArgs = append(fallbackArgs, "-i", icon)
		}
		if urgency != "" {
			fallbackArgs = append(fallbackArgs, "-u", urgency)
		}
		fallbackArgs = append(fallbackArgs, title, message)
		_ = exec.Command("notify-send", fallbackArgs...).Run()
		return 0
	}
	idStr := strings.TrimSpace(string(out))
	if id, err := strconv.ParseUint(idStr, 10, 32); err == nil {
		return uint32(id)
	}
	return 0
}

func closeDesktopNotification(id uint32) {
	if id == 0 {
		return
	}
	if cmdExists("gdbus") {
		_ = exec.Command("gdbus", "call", "--session",
			"--dest", "org.freedesktop.Notifications",
			"--object-path", "/org/freedesktop/Notifications",
			"--method", "org.freedesktop.Notifications.CloseNotification",
			strconv.FormatUint(uint64(id), 10)).Run()
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

	displayBody := content
	if displayBody == "" {
		displayBody = title
	}
	sendDesktopNotification(
		fmt.Sprintf("📱 %s: %s", appName, title),
		displayBody,
		"dialog-information",
		"normal",
	)
}

type BatteryInfo struct {
	Level      int       `json:"level"`
	IsCharging bool      `json:"is_charging"`
	Status     string    `json:"status"`
	DeviceID   string    `json:"device_id"`
	UpdatedAt  time.Time `json:"updated_at"`
}

func (n *SyncNode) handleBattery(msg SyncMessage) {
	n.batteryMu.Lock()
	prevLevel := n.lastBatteryLevel
	prevCharge := n.lastBatteryCharge
	stateChanged := (msg.BatteryLevel != prevLevel || msg.IsCharging != prevCharge)
	n.lastBatteryLevel = msg.BatteryLevel
	n.lastBatteryCharge = msg.IsCharging
	n.lastBatteryStatus = msg.BatteryStatus
	n.lastBatteryTime = time.Now()
	n.batteryMu.Unlock()

	// Persist to BatteryFile for CLI queries
	info := BatteryInfo{
		Level:      msg.BatteryLevel,
		IsCharging: msg.IsCharging,
		Status:     msg.BatteryStatus,
		DeviceID:   msg.DeviceID,
		UpdatedAt:  time.Now(),
	}
	if data, err := json.Marshal(info); err == nil {
		_ = os.WriteFile(BatteryFile, data, 0644)
	}

	timeStr := time.Now().Format("15:04:05")
	chargeStr := "Discharging"
	if msg.IsCharging {
		chargeStr = "Charging"
	}

	switch msg.BatteryStatus {
	case "critical":
		fmt.Printf("🪫 [%s] Phone Battery CRITICAL: %d%% (%s)\n", timeStr, msg.BatteryLevel, chargeStr)
		title := fmt.Sprintf("🪫 Phone Battery Critical: %d%%", msg.BatteryLevel)
		body := fmt.Sprintf("Phone battery is at %d%%! Please connect your charger immediately.", msg.BatteryLevel)
		if msg.Content != "" {
			body = msg.Content
		}
		sendDesktopNotification(title, body, "battery-caution", "critical")

	case "low":
		fmt.Printf("🪫 [%s] Phone Battery Low: %d%% (%s)\n", timeStr, msg.BatteryLevel, chargeStr)
		title := fmt.Sprintf("🪫 Phone Battery Low: %d%%", msg.BatteryLevel)
		body := fmt.Sprintf("Phone battery is at %d%%. Please connect your charger.", msg.BatteryLevel)
		if msg.Content != "" {
			body = msg.Content
		}
		sendDesktopNotification(title, body, "battery-low", "normal")

	case "full":
		fmt.Printf("🔋 [%s] Phone Battery Full: %d%% (%s)\n", timeStr, msg.BatteryLevel, chargeStr)
		title := fmt.Sprintf("🔋 Phone Fully Charged (%d%%)", msg.BatteryLevel)
		body := "Your phone is fully charged. You can unplug the charger now."
		if msg.Content != "" {
			body = msg.Content
		}
		sendDesktopNotification(title, body, "battery-full", "normal")

	default:
		// Regular battery state update: only log when percentage or charging state actually changes
		if stateChanged {
			fmt.Printf("🔋 [%s] Phone Battery: %d%% [%s]\n", timeStr, msg.BatteryLevel, chargeStr)
		}
	}
}

func (n *SyncNode) handleCall(msg SyncMessage) {
	caller := strings.TrimSpace(msg.CallerName)
	number := strings.TrimSpace(msg.PhoneNumber)

	displayName := caller
	if displayName == "" {
		displayName = number
	} else if number != "" && !strings.Contains(caller, number) {
		displayName = fmt.Sprintf("%s (%s)", caller, number)
	}
	if displayName == "" {
		displayName = "Unknown Caller"
	}

	timeStr := time.Now().Format("15:04:05")

	n.callMu.Lock()
	defer n.callMu.Unlock()

	switch msg.CallState {
	case "ringing":
		fmt.Printf("📞 [%s] Incoming Call from: %s\n", timeStr, displayName)
		title := "📞 Incoming Call"
		body := fmt.Sprintf("%s is calling...", displayName)
		if msg.Content != "" {
			body = msg.Content
		}

		// Show notification with critical urgency and replace previous ID if still ringing
		newID := sendDesktopNotificationWithID(
			title,
			body,
			"call-start",
			"critical",
			0, // Do not auto-expire while ringing
			n.activeCallNotifID,
		)
		n.activeCallNotifID = newID
		n.lastRingingKey = displayName

	case "ended":
		fmt.Printf("📞 [%s] Call ended (%s)\n", timeStr, displayName)
		if n.activeCallNotifID > 0 {
			// Replace with brief 2-second low-urgency dismissed notification or close it
			_ = sendDesktopNotificationWithID(
				"📞 Call Ended",
				displayName,
				"call-stop",
				"low",
				2000,
				n.activeCallNotifID,
			)
			closeDesktopNotification(n.activeCallNotifID)
			n.activeCallNotifID = 0
		}
		n.lastRingingKey = ""

	case "missed":
		fmt.Printf("📞 [%s] Missed Call from: %s\n", timeStr, displayName)
		if n.activeCallNotifID > 0 {
			closeDesktopNotification(n.activeCallNotifID)
			n.activeCallNotifID = 0
		}
		title := "📞 Missed Call"
		body := fmt.Sprintf("Missed call from %s", displayName)
		sendDesktopNotificationWithID(
			title,
			body,
			"call-missed",
			"normal",
			10000, // Show for 10 seconds
			0,
		)
		n.lastRingingKey = ""
	}
}

