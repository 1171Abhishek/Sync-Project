// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os/exec"
	"strings"
	"time"

	"golang.design/x/clipboard"
)

func hashContent(s string) string {
	clean := strings.TrimSpace(s)
	h := sha256.Sum256([]byte(clean))
	return hex.EncodeToString(h[:])
}

func (n *SyncNode) writeClipboard(text string) error {
	if cmdExists("wl-copy") {
		cmd := exec.Command("wl-copy")
		cmd.Stdin = strings.NewReader(text)
		_ = cmd.Run()

		cmdP := exec.Command("wl-copy", "-p")
		cmdP.Stdin = strings.NewReader(text)
		_ = cmdP.Run()
	}

	if n.nativeClip {
		_, _ = clipboard.Write(context.Background(), clipboard.FmtText, []byte(text))
	}

	if cmdExists("xclip") {
		cmd := exec.Command("xclip", "-selection", "clipboard")
		cmd.Stdin = strings.NewReader(text)
		_ = cmd.Run()

		cmdP := exec.Command("xclip", "-selection", "primary")
		cmdP.Stdin = strings.NewReader(text)
		_ = cmdP.Run()
	}
	return nil
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
				n.BroadcastClipboard(text, h)
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
						n.BroadcastClipboard(text, h)
					} else {
						n.mu.Unlock()
					}
				}
			}
		}
		time.Sleep(2 * time.Second)
	}
}
