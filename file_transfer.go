// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

package main

import (
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

func getDownloadsDir() string {
	home, err := os.UserHomeDir()
	if err == nil {
		downloads := filepath.Join(home, "Downloads")
		if info, err := os.Stat(downloads); err == nil && info.IsDir() {
			return downloads
		}
		return home
	}
	return "."
}

func getUniqueFilename(dir, baseName string) string {
	ext := filepath.Ext(baseName)
	nameOnly := strings.TrimSuffix(baseName, ext)
	target := filepath.Join(dir, baseName)

	counter := 1
	for {
		if _, err := os.Stat(target); os.IsNotExist(err) {
			return target
		}
		target = filepath.Join(dir, fmt.Sprintf("%s (%d)%s", nameOnly, counter, ext))
		counter++
	}
}

func (n *SyncNode) startFileServer() {
	mux := http.NewServeMux()
	mux.HandleFunc("/upload", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}

		rawFilename := r.Header.Get("X-Filename")
		if decoded, err := url.QueryUnescape(rawFilename); err == nil && decoded != "" {
			rawFilename = decoded
		}
		if rawFilename == "" {
			rawFilename = fmt.Sprintf("received_%d", time.Now().Unix())
		}
		authHeader := r.Header.Get("X-ClipSync-Auth")
		timeHeader := r.Header.Get("X-ClipSync-Time")
		ts, _ := strconv.ParseInt(timeHeader, 10, 64)

		crypto := LoadOrCreateCrypto()
		if !crypto.VerifyAuthToken(rawFilename, authHeader, ts) {
			fmt.Printf("🔒 [Security] Rejected unauthenticated file upload (%s) from %s\n", rawFilename, r.RemoteAddr)
			http.Error(w, "Unauthorized: Pairing required", http.StatusUnauthorized)
			return
		}

		safeFilename := filepath.Base(filepath.Clean(rawFilename))
		if safeFilename == "." || safeFilename == "/" {
			safeFilename = fmt.Sprintf("file_%d", time.Now().Unix())
		}

		downloadsDir := getDownloadsDir()
		destPath := getUniqueFilename(downloadsDir, safeFilename)

		out, err := os.Create(destPath)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		defer out.Close()

		nBytes, err := io.Copy(out, r.Body)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		sizeStr := formatSize(nBytes)
		finalName := filepath.Base(destPath)
		fmt.Printf("📥 [%s] Received file from %s: %s (%s) -> %s\n",
			time.Now().Format("15:04:05"), getHostIP(r.RemoteAddr), finalName, sizeStr, destPath)

		sendDesktopNotification("ClipSync: File Received", fmt.Sprintf("📥 %s (%s)\nSaved to Downloads", finalName, sizeStr), "document-save", "normal")

		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("OK\n"))
	})

	server := &http.Server{
		Addr:         fmt.Sprintf("0.0.0.0:%d", FilePort),
		Handler:      mux,
		ReadTimeout:  10 * time.Minute,
		WriteTimeout: 10 * time.Minute,
	}

	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		fmt.Printf("⚠️ File server warning: %v\n", err)
	}
}

func sendFileOrURLToPhone(target string) {
	if strings.HasPrefix(target, "http://") || strings.HasPrefix(target, "https://") {
		sendURLToPhone(target)
		return
	}
	sendFileToPhone(target)
}

func sendFileToPhone(filePath string) {
	fileInfo, err := os.Stat(filePath)
	if err != nil {
		fmt.Printf("❌ File not found: %s\n", filePath)
		os.Exit(1)
	}
	if fileInfo.IsDir() {
		fmt.Printf("❌ Cannot send directory: %s\n", filePath)
		os.Exit(1)
	}

	fmt.Printf("🔍 Looking for connected phone on Wi-Fi...\n")
	phoneIP := discoverPhoneIP()
	if phoneIP == "" {
		fmt.Println("❌ No phone found on local Wi-Fi!")
		fmt.Println("👉 Make sure your phone is connected to the same Wi-Fi and ClipSync is running.")
		sendDesktopNotification("ClipSync Send Failed", "No phone found on local Wi-Fi", "dialog-warning", "normal")
		os.Exit(1)
	}

	file, err := os.Open(filePath)
	if err != nil {
		fmt.Printf("❌ Failed to open file: %v\n", err)
		os.Exit(1)
	}
	defer file.Close()

	fileName := filepath.Base(filePath)
	sizeStr := formatSize(fileInfo.Size())
	fmt.Printf("📤 Sending %s (%s) to Phone (%s)...\n", fileName, sizeStr, phoneIP)

	urlTarget := fmt.Sprintf("http://%s:%d/upload", phoneIP, FilePort)
	req, err := http.NewRequest("POST", urlTarget, file)
	if err != nil {
		fmt.Printf("❌ Failed to create request: %v\n", err)
		os.Exit(1)
	}

	crypto := LoadOrCreateCrypto()
	now := time.Now().Unix()
	authToken := crypto.GenerateAuthToken(fileName, now)

	req.Header.Set("X-ClipSync-Auth", authToken)
	req.Header.Set("X-ClipSync-Time", fmt.Sprintf("%d", now))
	req.Header.Set("X-Filename", url.QueryEscape(fileName))
	req.Header.Set("Content-Type", "application/octet-stream")
	req.ContentLength = fileInfo.Size()

	client := &http.Client{
		Timeout: 5 * time.Minute,
	}

	resp, err := client.Do(req)
	if err != nil {
		fmt.Printf("❌ Upload failed: %v\n", err)
		sendDesktopNotification("ClipSync Send Failed", fmt.Sprintf("Failed to send %s to phone", fileName), "dialog-warning", "normal")
		os.Exit(1)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusOK {
		fmt.Printf("✓ Successfully sent %s to Phone! Saved to Gallery / Downloads.\n", fileName)
		sendDesktopNotification("ClipSync: Sent to Phone", fmt.Sprintf("✓ %s (%s) sent to Phone", fileName, sizeStr), "document-save", "normal")
	} else {
		fmt.Printf("⚠️ Phone responded with status: %s\n", resp.Status)
		os.Exit(1)
	}
}
