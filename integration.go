// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

// ensureDesktopIntegrations automatically configures:
// 1. GNOME global shortcut (Ctrl+Alt+P) -> clip-sync send-link
// 2. Nautilus right-click context script -> Send to Phone (ClipSync)
func ensureDesktopIntegrations(verbose bool) {
	execPath, err := os.Executable()
	if err != nil {
		execPath = os.Args[0]
	}
	if realPath, err := filepath.EvalSymlinks(execPath); err == nil {
		execPath = realPath
	}
	execPath, _ = filepath.Abs(execPath)

	// 1. GNOME Keybinding
	if err := ensureGNOMEShortcut(execPath, verbose); err != nil {
		if verbose {
			fmt.Printf("⚠️  GNOME shortcut check: %v\n", err)
		}
	}

	// 2. Nautilus right-click script
	if err := ensureNautilusScript(execPath, verbose); err != nil {
		if verbose {
			fmt.Printf("⚠️  Nautilus script check: %v\n", err)
		}
	}

	// 3. User systemd service
	if err := ensureUserService(execPath, verbose); err != nil {
		if verbose {
			fmt.Printf("⚠️  User systemd service check: %v\n", err)
		}
	}
}

func ensureGNOMEShortcut(execPath string, verbose bool) error {
	if !cmdExists("gsettings") {
		return nil
	}

	// Check if GNOME media-keys custom-keybindings is queryable
	cmd := exec.Command("gsettings", "get", "org.gnome.settings-daemon.plugins.media-keys", "custom-keybindings")
	out, err := cmd.Output()
	if err != nil {
		return fmt.Errorf("unable to read custom-keybindings: %w", err)
	}

	raw := strings.TrimSpace(string(out))
	re := regexp.MustCompile(`['"]([^'"]+)['"]`)
	matches := re.FindAllStringSubmatch(raw, -1)

	var currentPaths []string
	usedIndices := make(map[int]bool)
	var existingClipSyncPath string
	desiredCommand := fmt.Sprintf("%s send-link", execPath)

	for _, m := range matches {
		path := m[1]
		currentPaths = append(currentPaths, path)

		// Check index e.g. /custom0/ -> 0
		idxRe := regexp.MustCompile(`custom(\d+)`)
		if sub := idxRe.FindStringSubmatch(path); len(sub) > 1 {
			if num, err := strconv.Atoi(sub[1]); err == nil {
				usedIndices[num] = true
			}
		}

		// Inspect existing binding command
		schemaKey := fmt.Sprintf("org.gnome.settings-daemon.plugins.media-keys.custom-keybinding:%s", path)
		cmdOut, err := exec.Command("gsettings", "get", schemaKey, "command").Output()
		if err == nil {
			cmdVal := strings.Trim(strings.TrimSpace(string(cmdOut)), "'\"")
			if strings.Contains(cmdVal, "clip-sync") && strings.Contains(cmdVal, "send-link") {
				existingClipSyncPath = path
			}
		}
	}

	if existingClipSyncPath != "" {
		schemaKey := fmt.Sprintf("org.gnome.settings-daemon.plugins.media-keys.custom-keybinding:%s", existingClipSyncPath)
		// Verify if command path needs updating
		cmdOut, _ := exec.Command("gsettings", "get", schemaKey, "command").Output()
		cmdVal := strings.Trim(strings.TrimSpace(string(cmdOut)), "'\"")
		if cmdVal != desiredCommand {
			exec.Command("gsettings", "set", schemaKey, "command", desiredCommand).Run()
			if verbose {
				fmt.Printf("✓ Updated ClipSync shortcut command to: %s\n", desiredCommand)
			}
		}

		// Ensure binding exists
		bindOut, _ := exec.Command("gsettings", "get", schemaKey, "binding").Output()
		bindVal := strings.Trim(strings.TrimSpace(string(bindOut)), "'\"")
		if bindVal == "" || bindVal == "@as []" || bindVal == "disabled" {
			exec.Command("gsettings", "set", schemaKey, "binding", "<Primary><Alt>p").Run()
		}

		if verbose {
			fmt.Printf("✓ GNOME shortcut is active: [Ctrl + Alt + P] -> %s\n", desiredCommand)
		}
		return nil
	}

	// If not found, create a new custom keybinding slot
	nextIdx := 0
	for {
		if !usedIndices[nextIdx] {
			break
		}
		nextIdx++
	}

	newPath := fmt.Sprintf("/org/gnome/settings-daemon/plugins/media-keys/custom-keybindings/custom%d/", nextIdx)
	schemaKey := fmt.Sprintf("org.gnome.settings-daemon.plugins.media-keys.custom-keybinding:%s", newPath)

	// Set name, command, binding
	if err := exec.Command("gsettings", "set", schemaKey, "name", "ClipSync: Send Link to Phone").Run(); err != nil {
		return fmt.Errorf("failed setting keybinding name: %w", err)
	}
	if err := exec.Command("gsettings", "set", schemaKey, "command", desiredCommand).Run(); err != nil {
		return fmt.Errorf("failed setting keybinding command: %w", err)
	}
	if err := exec.Command("gsettings", "set", schemaKey, "binding", "<Primary><Alt>p").Run(); err != nil {
		return fmt.Errorf("failed setting keybinding binding: %w", err)
	}

	// Append to custom-keybindings list
	currentPaths = append(currentPaths, newPath)
	var formatted []string
	for _, p := range currentPaths {
		formatted = append(formatted, fmt.Sprintf("'%s'", p))
	}
	newArrayStr := fmt.Sprintf("[%s]", strings.Join(formatted, ", "))

	if err := exec.Command("gsettings", "set", "org.gnome.settings-daemon.plugins.media-keys", "custom-keybindings", newArrayStr).Run(); err != nil {
		return fmt.Errorf("failed registering keybinding in GNOME: %w", err)
	}

	fmt.Printf("⚡ Automatically registered global shortcut: [Ctrl + Alt + P] -> Send Tab/Link to Phone\n")
	return nil
}

func ensureNautilusScript(execPath string, verbose bool) error {
	homeDir, err := os.UserHomeDir()
	if err != nil {
		return err
	}

	scriptsDir := filepath.Join(homeDir, ".local", "share", "nautilus", "scripts")
	if err := os.MkdirAll(scriptsDir, 0755); err != nil {
		return err
	}

	scriptPath := filepath.Join(scriptsDir, "Send to Phone (ClipSync)")
	desiredContent := fmt.Sprintf("#!/bin/bash\nfor f in \"$@\"; do\n    \"%s\" send \"$f\"\ndone\n", execPath)

	// Check if already up to date
	if existing, err := os.ReadFile(scriptPath); err == nil {
		if string(existing) == desiredContent {
			if verbose {
				fmt.Printf("✓ Nautilus right-click script is up to date: %s\n", scriptPath)
			}
			return nil
		}
	}

	if err := os.WriteFile(scriptPath, []byte(desiredContent), 0755); err != nil {
		return err
	}

	if verbose {
		fmt.Printf("📁 Automatically created Nautilus right-click action: Right-click file -> Scripts -> \"Send to Phone (ClipSync)\"\n")
	}
	return nil
}

func ensureUserService(execPath string, verbose bool) error {
	homeDir, err := os.UserHomeDir()
	if err != nil {
		return err
	}
	userSystemdDir := filepath.Join(homeDir, ".config", "systemd", "user")
	if err := os.MkdirAll(userSystemdDir, 0755); err != nil {
		return err
	}

	serviceFile := filepath.Join(userSystemdDir, "clip-sync.service")
	desiredContent := fmt.Sprintf(`[Unit]
Description=ClipSync - Cross-device Linux/Android Continuity Daemon
Documentation=https://github.com/1171Abhishek/Sync-Project
PartOf=graphical-session.target
After=graphical-session.target network.target

[Service]
Type=simple
ExecStart=%s
Restart=on-failure
RestartSec=3

[Install]
WantedBy=graphical-session.target
`, execPath)

	if existing, err := os.ReadFile(serviceFile); err == nil {
		if string(existing) == desiredContent {
			if verbose {
				fmt.Printf("✓ User systemd service is up to date: %s\n", serviceFile)
			}
			return nil
		}
	}

	if err := os.WriteFile(serviceFile, []byte(desiredContent), 0644); err != nil {
		return err
	}

	_ = exec.Command("systemctl", "--user", "daemon-reload").Run()
	if verbose {
		fmt.Printf("⚙️ Automatically registered user systemd service: %s\n", serviceFile)
	}
	return nil
}
