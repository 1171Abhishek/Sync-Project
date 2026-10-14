// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

package main

import (
	"fmt"
	"os"
	"strings"
)

func main() {
	if len(os.Args) > 1 {
		arg := os.Args[1]

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

		if arg == "send" || fileExists(arg) || strings.HasPrefix(arg, "http://") || strings.HasPrefix(arg, "https://") {
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

		if arg == "-h" || arg == "--help" {
			fmt.Println("ClipSync - Seamless Linux <-> Android Continuity")
			fmt.Println("Usage:")
			fmt.Println("  clip-sync                 Start background daemon & auto-register shortcuts")
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
