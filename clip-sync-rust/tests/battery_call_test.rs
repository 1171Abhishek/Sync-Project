// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

use clip_sync::node::SyncNode;
use clip_sync::types::{get_battery_file, SyncMessage, BATTERY_FILE};
use std::path::Path;

#[tokio::test]
async fn test_battery_alert_handling() {
    let node = SyncNode::new();

    node.handle_battery(&SyncMessage {
        msg_type: Some("battery".to_string()),
        device_id: "unit-test-device".to_string(),
        battery_level: Some(18),
        is_charging: Some(false),
        battery_status: Some("low".to_string()),
        ..Default::default()
    })
    .await;

    let last_level = *node.last_battery_level.lock().await;
    assert_eq!(last_level, 18, "Expected battery level 18, got {}", last_level);

    let last_charge = *node.last_battery_charge.lock().await;
    assert!(!last_charge, "Expected charging false, got true");

    // Verify battery file persistence
    let battery_file = get_battery_file();
    assert!(
        Path::new(&battery_file).exists() || Path::new(BATTERY_FILE).exists(),
        "BatteryFile was not written"
    );
}

#[tokio::test]
async fn test_call_alert_handling() {
    let node = SyncNode::new();

    // 1. Ringing
    node.handle_call(&SyncMessage {
        msg_type: Some("call".to_string()),
        device_id: "unit-test-device".to_string(),
        call_state: Some("ringing".to_string()),
        caller_name: Some("Test Caller".to_string()),
        phone_number: Some("+1234567890".to_string()),
        ..Default::default()
    })
    .await;

    let active_id = *node.active_call_notif_id.lock().await;
    assert!(active_id > 0, "Expected active_call_notif_id to be populated");

    // 2. Ended
    node.handle_call(&SyncMessage {
        msg_type: Some("call".to_string()),
        device_id: "unit-test-device".to_string(),
        call_state: Some("ended".to_string()),
        caller_name: Some("Test Caller".to_string()),
        phone_number: Some("+1234567890".to_string()),
        ..Default::default()
    })
    .await;

    let active_id = *node.active_call_notif_id.lock().await;
    assert_eq!(active_id, 0, "Expected active_call_notif_id to be cleared after ended");

    // 3. Missed
    node.handle_call(&SyncMessage {
        msg_type: Some("call".to_string()),
        device_id: "unit-test-device".to_string(),
        call_state: Some("missed".to_string()),
        caller_name: Some("Test Caller".to_string()),
        phone_number: Some("+1234567890".to_string()),
        ..Default::default()
    })
    .await;
}
