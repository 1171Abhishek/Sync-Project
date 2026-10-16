// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

package main

import (
	"os"
	"testing"
)

func TestBatteryAlertHandling(t *testing.T) {
	node := NewSyncNode()
	node.handleBattery(SyncMessage{
		Type:          "battery",
		DeviceID:      "unit-test-device",
		BatteryLevel:  18,
		IsCharging:    false,
		BatteryStatus: "low",
	})

	if node.lastBatteryLevel != 18 {
		t.Errorf("Expected battery level 18, got %d", node.lastBatteryLevel)
	}
	if node.lastBatteryCharge != false {
		t.Errorf("Expected charging false, got true")
	}

	// Verify battery file persistence
	if _, err := os.Stat(BatteryFile); err != nil {
		t.Errorf("BatteryFile was not written: %v", err)
	}
}

func TestCallAlertHandling(t *testing.T) {
	node := NewSyncNode()

	// 1. Ringing
	node.handleCall(SyncMessage{
		Type:        "call",
		DeviceID:    "unit-test-device",
		CallState:   "ringing",
		CallerName:  "Test Caller",
		PhoneNumber: "+1234567890",
	})
	if node.lastRingingKey == "" {
		t.Errorf("Expected lastRingingKey to be populated")
	}

	// 2. Ended
	node.handleCall(SyncMessage{
		Type:        "call",
		DeviceID:    "unit-test-device",
		CallState:   "ended",
		CallerName:  "Test Caller",
		PhoneNumber: "+1234567890",
	})
	if node.lastRingingKey != "" {
		t.Errorf("Expected lastRingingKey to be cleared after ended")
	}

	// 3. Missed
	node.handleCall(SyncMessage{
		Type:        "call",
		DeviceID:    "unit-test-device",
		CallState:   "missed",
		CallerName:  "Test Caller",
		PhoneNumber: "+1234567890",
	})
}
