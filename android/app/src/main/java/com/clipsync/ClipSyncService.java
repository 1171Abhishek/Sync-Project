package com.clipsync;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;

public class ClipSyncService extends AccessibilityService {
    private static final int UDP_PORT = 9876;
    private static final int TCP_PORT = 9877;
    private String deviceId;
    private String lastHash = "";
    private String lastReceived = "";
    private final ConcurrentHashMap<String, Socket> peers = new ConcurrentHashMap<>();
    private ClipboardManager clipboard;
    private Handler mainHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        deviceId = "android-" + (System.currentTimeMillis() % 10000);
        clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());

        new Thread(this::startTcpServer).start();
        new Thread(this::startUdpDiscovery).start();

        mainHandler.post(() -> {
            if (clipboard != null) {
                clipboard.addPrimaryClipChangedListener(this::checkClipboard);
            }
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        // Whenever a click, context-click, or text-selection happens:
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            type == AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED ||
            type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            
            // Check after 250ms so Android has finished committing the copied text to the clipboard!
            mainHandler.postDelayed(this::checkClipboard, 250);
            mainHandler.postDelayed(this::checkClipboard, 600);
        }
    }

    @Override
    public void onInterrupt() {}

    private void checkClipboard() {
        if (clipboard == null || !clipboard.hasPrimaryClip()) return;
        ClipData clip = clipboard.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence text = clip.getItemAt(0).getText();
            if (text != null && text.length() > 0) {
                String str = text.toString();
                String clean = str.trim();
                String hash = hash(clean);

                synchronized (this) {
                    if (!clean.isEmpty() && !clean.equals(lastReceived.trim()) && !hash.equals(lastHash)) {
                        lastHash = hash;
                        lastReceived = str;
                        new Thread(() -> broadcast(str, hash)).start();
                    }
                }
            }
        }
    }

    private void broadcast(String content, String hash) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("device_id", deviceId);
            obj.put("hash", hash);
            obj.put("content", content);
            byte[] bytes = (obj.toString() + "\n").getBytes(StandardCharsets.UTF_8);

            for (String hostIp : peers.keySet()) {
                Socket socket = peers.get(hostIp);
                if (socket != null && !socket.isClosed()) {
                    try {
                        OutputStream out = socket.getOutputStream();
                        out.write(bytes);
                        out.flush();
                    } catch (Exception e) {
                        peers.remove(hostIp);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void startTcpServer() {
        try (ServerSocket server = new ServerSocket(TCP_PORT)) {
            while (true) {
                Socket socket = server.accept();
                new Thread(() -> handlePeer(socket)).start();
            }
        } catch (Exception ignored) {}
    }

    private void handlePeer(Socket socket) {
        String hostIp = socket.getInetAddress().getHostAddress();
        Socket old = peers.put(hostIp, socket);
        if (old != null && !old.isClosed()) {
            try { old.close(); } catch (Exception ignored) {}
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JSONObject obj = new JSONObject(line);
                String hash = obj.getString("hash");
                String content = obj.getString("content");
                String clean = content.trim();

                synchronized (this) {
                    if (!clean.isEmpty() && !clean.equals(lastReceived.trim())) {
                        lastHash = hash;
                        lastReceived = content;

                        mainHandler.post(() -> {
                            if (clipboard != null) {
                                ClipData clip = ClipData.newPlainText("ClipSync", content);
                                clipboard.setPrimaryClip(clip);
                            }
                        });
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            peers.remove(hostIp);
        }
    }

    private void startUdpDiscovery() {
        new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                byte[] beacon = ("CLIP_SYNC:" + deviceId).getBytes(StandardCharsets.UTF_8);
                InetAddress bcast = InetAddress.getByName("255.255.255.255");
                while (true) {
                    socket.send(new DatagramPacket(beacon, beacon.length, bcast, UDP_PORT));
                    Thread.sleep(4000);
                }
            } catch (Exception ignored) {}
        }).start();

        try (DatagramSocket socket = new DatagramSocket(UDP_PORT)) {
            byte[] buf = new byte[1024];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                if (msg.startsWith("CLIP_SYNC:") && !msg.endsWith(deviceId)) {
                    String peerIp = packet.getAddress().getHostAddress();
                    if (!peers.containsKey(peerIp)) {
                        new Thread(() -> connectToPeer(peerIp)).start();
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void connectToPeer(String ip) {
        try {
            if (peers.containsKey(ip)) return;
            Socket socket = new Socket(ip, TCP_PORT);
            handlePeer(socket);
        } catch (Exception ignored) {}
    }

    private String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
