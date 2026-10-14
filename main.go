// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

package main

import (
	"fmt"
	"os"
)

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
			fmt.Println("  clip-sync               Start background clipboard, notification & file daemon")
			fmt.Println("  clip-sync send <file>   Send image or file to your Phone")
			return
		}
	}

	node := NewSyncNode()
	fmt.Printf("🚀 ClipSync started!\n")
	fmt.Printf("📱 Device ID: %s\n", node.deviceID)
	fmt.Printf("📡 Clipboard & Notification Sync listening on TCP :%d\n", TCPPort)
	fmt.Printf("📁 File Drop receiver listening on HTTP :%d (saving to %s)\n", FilePort, getDownloadsDir())

	go node.startTCPServer()
	go node.startFileServer()
	go node.startUDPDiscovery()

	node.startClipboardWatcher()
}
