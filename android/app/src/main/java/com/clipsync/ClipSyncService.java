package com.clipsync;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.accessibility.AccessibilityEvent;
import android.webkit.MimeTypeMap;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClipSyncService extends AccessibilityService {
    private static final int UDP_PORT = 9876;
    private static final int TCP_PORT = 9877;
    private static final int FILE_PORT = 9878;
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
        new Thread(this::startFileServer).start();
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
            peers.remove(hostIp, socket);
        }
    }

    private void startFileServer() {
        try (ServerSocket server = new ServerSocket(FILE_PORT)) {
            while (true) {
                Socket socket = server.accept();
                new Thread(() -> handleIncomingFile(socket)).start();
            }
        } catch (Exception ignored) {}
    }

    private void handleIncomingFile(Socket socket) {
        try {
            socket.setSoTimeout(30000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String filename = null;
            long contentLength = -1;

            StringBuilder lineBuf = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                char c = (char) b;
                if (c == '\n') {
                    String line = lineBuf.toString().trim();
                    lineBuf.setLength(0);
                    if (line.isEmpty()) {
                        break;
                    }
                    String lower = line.toLowerCase();
                    if (lower.startsWith("x-filename:")) {
                        String raw = line.substring(line.indexOf(':') + 1).trim();
                        try {
                            filename = URLDecoder.decode(raw, StandardCharsets.UTF_8.name());
                        } catch (Exception e) {
                            filename = raw;
                        }
                    } else if (lower.startsWith("content-length:")) {
                        try {
                            contentLength = Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
                        } catch (Exception ignored) {}
                    }
                } else if (c != '\r') {
                    lineBuf.append(c);
                }
            }

            if (filename == null || filename.isEmpty()) {
                filename = "received_" + System.currentTimeMillis();
            }
            if (contentLength <= 0) {
                out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                socket.close();
                return;
            }

            saveFileToStorage(filename, in, contentLength);

            out.write("HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nOK\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void saveFileToStorage(String filename, InputStream in, long contentLength) {
        String lower = filename.toLowerCase();
        boolean isImage = lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                          lower.endsWith(".png") || lower.endsWith(".webp") ||
                          lower.endsWith(".gif");

        Uri itemUri = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);

            Uri collection;
            if (isImage) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ClipSync");
                } else {
                    collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                }
                values.put(MediaStore.Images.Media.MIME_TYPE, getMimeType(filename));
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ClipSync");
                } else {
                    collection = MediaStore.Files.getContentUri("external");
                }
                values.put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(filename));
            }

            itemUri = getContentResolver().insert(collection, values);
            if (itemUri != null) {
                try (OutputStream os = getContentResolver().openOutputStream(itemUri)) {
                    byte[] buf = new byte[32768];
                    long remaining = contentLength;
                    int read;
                    while (remaining > 0 && (read = in.read(buf, 0, (int) Math.min(buf.length, remaining))) != -1) {
                        os.write(buf, 0, read);
                        remaining -= read;
                    }
                    os.flush();
                }
            }
        } catch (Exception e) {
            try {
                File dir = isImage ?
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) :
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File target = new File(dir, filename);
                try (FileOutputStream fos = new FileOutputStream(target)) {
                    byte[] buf = new byte[32768];
                    long remaining = contentLength;
                    int read;
                    while (remaining > 0 && (read = in.read(buf, 0, (int) Math.min(buf.length, remaining))) != -1) {
                        fos.write(buf, 0, read);
                        remaining -= read;
                    }
                    fos.flush();
                }
                Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                scanIntent.setData(Uri.fromFile(target));
                sendBroadcast(scanIntent);
                itemUri = Uri.fromFile(target);
            } catch (Exception ignored) {}
        }

        showNotification(filename, isImage, itemUri);
    }

    private void showNotification(String filename, boolean isImage, Uri itemUri) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            String channelId = "clipsync_files";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                    channelId, "ClipSync Files", NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription("Notifications for files received from PC");
                nm.createNotificationChannel(channel);
            }

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, channelId);
            } else {
                builder = new Notification.Builder(this);
            }

            builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                   .setContentTitle("📥 " + filename)
                   .setContentText(isImage ? "Saved to Gallery (Pictures/ClipSync)" : "Saved to Downloads/ClipSync")
                   .setAutoCancel(true);

            if (itemUri != null) {
                Intent viewIntent = new Intent(Intent.ACTION_VIEW);
                viewIntent.setDataAndType(itemUri, getMimeType(filename));
                viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent pi = PendingIntent.getActivity(this, (int) (System.currentTimeMillis() % 100000), viewIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));
                builder.setContentIntent(pi);
            }

            nm.notify((int) (System.currentTimeMillis() % 100000), builder.build());
        } catch (Exception ignored) {}
    }

    private String getMimeType(String filename) {
        String ext = "";
        int i = filename.lastIndexOf('.');
        if (i > 0) {
            ext = filename.substring(i + 1).toLowerCase();
        }
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
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
