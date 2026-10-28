// Copyright (C) 2026 Abhishek Kumar
// Licensed under the GNU General Public License v3.0

use clip_sync::node::SyncNode;
use clip_sync::types::{get_selected_target, set_selected_target};
use tokio::sync::mpsc;

#[tokio::test]
async fn test_active_device_fifo_and_switch() {
    let node = SyncNode::new();

    // Simulate connecting Device 1 (e.g. 192.168.1.101)
    let ip1 = "192.168.1.101".to_string();
    let (tx1, mut rx1) = mpsc::channel(16);
    {
        let mut peers = node.peers.write().await;
        peers.insert(ip1.clone(), tx1);
        let mut order = node.connected_order.write().await;
        order.push(ip1.clone());
    }
    node.sync_peers_file().await;

    // Verify ip1 was automatically made the active device
    let active = get_selected_target();
    assert_eq!(active.as_deref(), Some("192.168.1.101"), "First connected device should be active");

    // Simulate connecting Device 2 (e.g. 192.168.1.102)
    let ip2 = "192.168.1.102".to_string();
    let (tx2, mut rx2) = mpsc::channel(16);
    {
        let mut peers = node.peers.write().await;
        peers.insert(ip2.clone(), tx2);
        let mut order = node.connected_order.write().await;
        order.push(ip2.clone());
    }
    node.sync_peers_file().await;

    // Verify ip1 remains the active device (FIFO / first-connected rule)
    let active_after_second = get_selected_target();
    assert_eq!(active_after_second.as_deref(), Some("192.168.1.101"), "First device must remain active when second connects");

    // Test clipboard send: only active device should receive the message!
    node.broadcast_clipboard("Hello Pixel".to_string(), "hash123".to_string()).await;

    // rx1 should receive the message
    let received_by_1 = rx1.try_recv();
    assert!(received_by_1.is_ok(), "Active device 1 should have received clipboard data");

    // rx2 should NOT receive the message (it is on standby)
    let received_by_2 = rx2.try_recv();
    assert!(received_by_2.is_err(), "Standby device 2 should NOT receive clipboard broadcast");

    // Now switch active device to Device 2
    let _ = set_selected_target(&ip2);
    let active_switched = get_selected_target();
    assert_eq!(active_switched.as_deref(), Some("192.168.1.102"), "Switched device should now be active");

    // Send another clipboard update
    node.broadcast_clipboard("Hello Galaxy".to_string(), "hash456".to_string()).await;

    // Now rx2 must receive and rx1 must not
    assert!(rx2.try_recv().is_ok(), "New active device 2 should receive clipboard data");
    assert!(rx1.try_recv().is_err(), "Previous device 1 is now on standby and should not receive data");
}
