// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

package main

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"time"
)

func main() {
	if len(os.Args) > 1 {
		arg := os.Args[1]

		if arg == "battery" || arg == "status" {
			showPhoneStatus()
			return
		}

		if arg == "test-battery" {
			runBatteryTest()
			return
		}

		if arg == "test-call" {
			runCallTest()
			return
		}

		if arg == "pair" {
			fmt.Println("🔒 Security Status: 100% Automatic End-to-End Encrypted (AES-256-GCM)")
			fmt.Println("✓ ClipSync pairs and encrypts all traffic automatically out-of-the-box.")
			fmt.Println("✓ No manual PIN or setup needed.")
			return
		}

		if arg == "setup" || arg == "install-shortcuts" {
			fmt.Println("🔧 Configuring ClipSync Desktop Shortcuts & Integrations...")
			ensureDesktopIntegrations(true)
			fmt.Println("✓ Done! Press [Ctrl + Alt + P] to send link to Phone.")
			fmt.Println("✓ Right-click any file in Nautilus -> Scripts -> 'Send to Phone (ClipSync)'.")
			return
		}

		if arg == "send-link" {
			if len(os.Args) >= 3 {
				sendURLToPhone(os.Args[2])
			} else {
				sendClipboardLinkToPhone()
			}
			return
		}

		if arg == "send" || fileExists(arg) || strings.HasPrefix(arg, "http://") || strings.HasPrefix(arg, "https://") || strings.HasPrefix(arg, "www.") {
			targetFile := arg
			if arg == "send" {
				if len(os.Args) < 3 {
					fmt.Println("Usage: clip-sync send <file|url>")
					os.Exit(1)
				}
				targetFile = os.Args[2]
			}
			sendFileOrURLToPhone(targetFile)
			return
		}

		if arg == "version" || arg == "-v" || arg == "--version" {
			fmt.Println("ClipSync v0.0.10")
			return
		}

		if arg == "-h" || arg == "--help" {
			fmt.Println("ClipSync v0.0.10 - Seamless Linux <-> Android Continuity")
			fmt.Println("Usage:")
			fmt.Println("  clip-sync                 Start background daemon & auto-register shortcuts")
			fmt.Println("  clip-sync version         Print ClipSync version (v0.0.10)")
			fmt.Println("  clip-sync battery         Check connected phone battery percentage & status")
			fmt.Println("  clip-sync test-battery    Simulate phone low battery & full charge alerts")
			fmt.Println("  clip-sync test-call [name] Simulate incoming call, auto-dismiss, and missed call")
			fmt.Println("  clip-sync send <file|url> Send image, file, or URL link to your Phone")
			fmt.Println("  clip-sync send-link       Send copied/highlighted browser link to your Phone (Ctrl+Alt+P)")
			fmt.Println("  clip-sync pair [pin]      View or set the 6-digit security pairing PIN")
			fmt.Println("  clip-sync setup           Verify/re-register GNOME shortcut & Nautilus right-click script")
			return
		}
	}

	// Automatically ensure shortcuts & integrations are configured on daemon startup
	ensureDesktopIntegrations(false)

	node := NewSyncNode()
	fmt.Printf("🚀 ClipSync started!\n")
	fmt.Printf("📱 Device ID: %s\n", node.deviceID)
	fmt.Printf("📡 Clipboard & Notification Sync listening on TCP :%d\n", TCPPort)
	fmt.Printf("📁 File Drop receiver listening on HTTP :%d (saving to %s)\n", FilePort, getDownloadsDir())
	fmt.Printf("🔒 Security: 100%% Automatic End-to-End Encrypted (AES-256-GCM)\n")
	fmt.Printf("⚡ Global Shortcut: [Ctrl + Alt + P] (press anywhere to send link to phone)\n")
	fmt.Printf("📁 File Manager: Right-click file -> Scripts -> 'Send to Phone (ClipSync)'\n")

	go node.startTCPServer()
	go node.startFileServer()
	go node.startUDPDiscovery()

	node.startClipboardWatcher()
}

func showPhoneStatus() {
	peerIP := ""
	if data, err := os.ReadFile(PeerFile); err == nil {
		peerIP = strings.TrimSpace(string(data))
	}

	if data, err := os.ReadFile(BatteryFile); err == nil {
		var info BatteryInfo
		if err := json.Unmarshal(data, &info); err == nil {
			chargeStatus := "Discharging"
			icon := "🔋"
			if info.IsCharging {
				chargeStatus = "Charging"
				icon = "⚡"
			} else if info.Level <= 20 {
				icon = "🪫"
			}
			timeAgo := time.Since(info.UpdatedAt).Round(time.Second)
			fmt.Printf("%s Phone Battery: %d%% [%s]\n", icon, info.Level, chargeStatus)
			if peerIP != "" {
				fmt.Printf("📱 Connected Peer IP: %s\n", peerIP)
			}
			fmt.Printf("🕒 Last Updated: %s ago (%s)\n", timeAgo, info.UpdatedAt.Format("15:04:05"))
			return
		}
	}

	if peerIP != "" {
		fmt.Printf("📱 Phone connected (%s), waiting for initial battery update...\n", peerIP)
	} else {
		fmt.Println("📱 No phone connected. Ensure phone is on the same Wi-Fi and ClipSync is running.")
	}
}

func runBatteryTest() {
	node := NewSyncNode()
	fmt.Println("🧪 Testing Phone Battery Desktop Alerts...")
	fmt.Println("1. Simulating Low Battery (15%)...")
	node.handleBattery(SyncMessage{
		Type:          "battery",
		DeviceID:      "test-phone",
		BatteryLevel:  15,
		IsCharging:    false,
		BatteryStatus: "low",
	})
	time.Sleep(2 * time.Second)
	fmt.Println("2. Simulating Full Battery (100%)...")
	node.handleBattery(SyncMessage{
		Type:          "battery",
		DeviceID:      "test-phone",
		BatteryLevel:  100,
		IsCharging:    true,
		BatteryStatus: "full",
	})
	fmt.Println("✓ Done! Check your desktop notifications and run './clip-sync battery'.")
}

func runCallTest() {
	node := NewSyncNode()
	caller := "Abhishek Kumar"
	phone := "+91 98765 43210"
	if len(os.Args) >= 3 {
		caller = os.Args[2]
	}
	fmt.Printf("🧪 [1/3] Simulating Incoming Call from: %s (%s)...\n", caller, phone)
	node.handleCall(SyncMessage{
		Type:        "call",
		DeviceID:    "test-phone",
		CallState:   "ringing",
		CallerName:  caller,
		PhoneNumber: phone,
	})
	fmt.Println("   👉 Ringing alert shown on desktop. Waiting 4 seconds before answering/ending...")
	time.Sleep(4 * time.Second)

	fmt.Println("🧪 [2/3] Simulating Call Answered / Ended...")
	node.handleCall(SyncMessage{
		Type:        "call",
		DeviceID:    "test-phone",
		CallState:   "ended",
		CallerName:  caller,
		PhoneNumber: phone,
	})
	fmt.Println("   👉 Ringing alert automatically closed.")
	time.Sleep(2 * time.Second)

	fmt.Printf("🧪 [3/3] Simulating Missed Call from: %s...\n", caller)
	node.handleCall(SyncMessage{
		Type:        "call",
		DeviceID:    "test-phone",
		CallState:   "missed",
		CallerName:  caller,
		PhoneNumber: phone,
	})
	fmt.Println("   👉 Missed call notification shown.")
	fmt.Println("✓ Call simulation complete!")
}


