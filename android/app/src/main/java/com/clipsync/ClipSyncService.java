package com.clipsync;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClipSyncService extends AccessibilityService {
    private static final int UDP_PORT = 9876;
    private static final int TCP_PORT = 9877;
    private String deviceId;
    private String lastHash = "";
    private String lastReceived = "";
    private static final ConcurrentHashMap<String, Socket> peers = new ConcurrentHashMap<>();
    private static volatile String lastPeerIp = null;
    private static volatile ClipSyncService instance = null;
    private ClipboardManager clipboard;
    private Handler mainHandler;

    public static Set<String> getActivePeers() {
        return peers.keySet();
    }

    public static String getLastPeerIp() {
        return lastPeerIp;
    }

    public static void broadcastText(String text) {
        ClipSyncService service = instance;
        if (service != null) {
            service.processAndBroadcast(text);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
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
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 1. Direct text extraction from Accessibility event (bypasses Android 10 clipboard lock)
        try {
            int eventType = event.getEventType();
            if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                List<CharSequence> texts = event.getText();
                int from = event.getFromIndex();
                int to = event.getToIndex();
                if (texts != null && !texts.isEmpty() && from >= 0 && to > from) {
                    String full = texts.get(0).toString();
                    if (to <= full.length()) {
                        String selected = full.substring(from, to);
                        processAndBroadcast(selected);
                    }
                }
            }
        } catch (Exception ignored) {}

        // 2. Also check system clipboard with standard debounce
        mainHandler.postDelayed(this::checkClipboard, 200);
        mainHandler.postDelayed(this::checkClipboard, 500);
    }

    @Override
    public void onInterrupt() {}

    private void checkClipboard() {
        try {
            if (clipboard == null || !clipboard.hasPrimaryClip()) return;
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).getText();
                if (text != null && text.length() > 0) {
                    processAndBroadcast(text.toString());
                }
            }
        } catch (Exception ignored) {}
    }

    private void processAndBroadcast(String str) {
        String clean = str.trim();
        if (clean.isEmpty()) return;
        String hash = hash(clean);

        synchronized (this) {
            if (!clean.equals(lastReceived.trim()) && !hash.equals(lastHash)) {
                lastHash = hash;
                lastReceived = str;
                new Thread(() -> broadcast(str, hash)).start();
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
                        peers.remove(hostIp, socket);
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
        lastPeerIp = hostIp;
        try {
            SharedPreferences prefs = getSharedPreferences("clipsync_prefs", Context.MODE_PRIVATE);
            prefs.edit().putString("last_peer_ip", hostIp).apply();
        } catch (Exception ignored) {}

        Socket old = peers.put(hostIp, socket);
        if (old != null && old != socket && !old.isClosed()) {
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
                            try {
                                if (clipboard != null) {
                                    ClipData clip = ClipData.newPlainText("ClipSync", content);
                                    clipboard.setPrimaryClip(clip);
                                }
                            } catch (Exception ignored) {}
                        });
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            // ONLY remove if this exact socket instance is still in the map!
            peers.remove(hostIp, socket);
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
