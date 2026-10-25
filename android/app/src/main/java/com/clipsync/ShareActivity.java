package com.clipsync;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShareActivity extends Activity {
    private static final int FILE_PORT = 9878;
    private static final int UDP_PORT = 9876;
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>\"{}|\\\\^`]+", Pattern.CASE_INSENSITIVE);

    private static String extractUrl(String text) {
        if (text == null) return null;
        Matcher m = URL_PATTERN.matcher(text);
        if (m.find()) {
            return m.group();
        }
        return null;
    }
    private Handler mainHandler;
    private String lastError = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());

        Intent intent = getIntent();
        if (intent == null) {
            finish();
            return;
        }

        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            handleSend(intent);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            handleSendMultiple(intent);
        } else {
            finish();
        }
    }

    private void handleSend(Intent intent) {
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri != null) {
            List<Uri> uris = new ArrayList<>();
            uris.add(uri);
            sendFiles(uris);
            return;
        }

        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text != null && !text.trim().isEmpty()) {
            String url = extractUrl(text);
            if (url != null) {
                sendUrl(url, text);
            } else {
                ClipSyncService.broadcastText(text);
                Toast.makeText(this, "✓ Copied to clipboard on connected devices!", Toast.LENGTH_SHORT).show();
                finish();
            }
            return;
        }

        Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void handleSendMultiple(Intent intent) {
        ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (uris != null && !uris.isEmpty()) {
            sendFiles(uris);
        } else {
            Toast.makeText(this, "No files selected", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void sendFiles(List<Uri> uris) {
        Toast.makeText(this, "Connecting to ClipSync device...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            Set<String> targetIps = getTargetIps();

            if (targetIps.isEmpty()) {
                mainHandler.post(() -> {
                    Toast.makeText(getApplicationContext(), "❌ No ClipSync device found on Wi-Fi", Toast.LENGTH_LONG).show();
                    finish();
                });
                return;
            }

            int successCount = 0;
            for (Uri uri : uris) {
                String fileName = getFileName(uri);
                for (String ip : targetIps) {
                    if (uploadFile(ip, uri, fileName)) {
                        successCount++;
                    }
                }
            }

            final int finalSuccess = successCount;
            mainHandler.post(() -> {
                if (finalSuccess > 0) {
                    Toast.makeText(getApplicationContext(), "✓ Successfully sent " + finalSuccess + " file(s)!", Toast.LENGTH_SHORT).show();
                } else {
                    String msg = lastError.isEmpty() ? "Check if device is reachable on Wi-Fi" : lastError;
                    Toast.makeText(getApplicationContext(), "❌ Send failed: " + msg, Toast.LENGTH_LONG).show();
                }
                finish();
            });
        }).start();
    }

    private void sendUrl(String url, String text) {
        Toast.makeText(this, "🌐 Sharing link...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                JSONObject obj = new JSONObject();
                obj.put("type", "url");
                obj.put("url", url);
                obj.put("content", text);
                obj.put("device_id", "android-share");

                // 1. Try active peer connections from ClipSyncService
                ClipSyncService.broadcastJson(this, obj);

                if (ClipSyncService.getActivePeers().isEmpty()) {
                    // 2. Direct standalone fallback if ClipSyncService is idle or disconnected
                    Set<String> targets = getTargetIps();
                    byte[] secretKey = CryptoUtils.getSecretKey(this);
                    String wireString;
                    if (secretKey != null) {
                        String payload = CryptoUtils.encrypt(obj.toString(), secretKey);
                        if (payload != null) {
                            JSONObject enc = new JSONObject();
                            enc.put("type", "encrypted");
                            enc.put("device_id", "android-share");
                            enc.put("payload", payload);
                            wireString = enc.toString() + "\n";
                        } else {
                            wireString = obj.toString() + "\n";
                        }
                    } else {
                        wireString = obj.toString() + "\n";
                    }
                    byte[] bytes = wireString.getBytes(StandardCharsets.UTF_8);

                    for (String ip : targets) {
                        try (Socket s = new Socket()) {
                            s.connect(new InetSocketAddress(ip, 9877), 2500);
                            OutputStream out = s.getOutputStream();
                            out.write(bytes);
                            out.flush();
                            break;
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
            mainHandler.post(this::finish);
        }).start();
    }

    private Set<String> getTargetIps() {
        Set<String> ips = new HashSet<>();
        for (String peer : ClipSyncService.getActivePeers()) {
            if (peer != null) {
                String clean = peer.replaceFirst("^::ffff:", "");
                if (!CryptoUtils.isLocalIp(clean)) {
                    ips.add(clean);
                }
            }
        }
        if (!ips.isEmpty()) return ips;

        // Try known peer IPs from previous connections
        Set<String> knownPeers = CryptoUtils.getKnownPeerIps(this);
        for (String cached : knownPeers) {
            try (Socket test = new Socket()) {
                test.connect(new InetSocketAddress(cached, 9877), 400);
                ips.add(cached);
            } catch (Exception ignored) {
                try (Socket test = new Socket()) {
                    test.connect(new InetSocketAddress(cached, FILE_PORT), 400);
                    ips.add(cached);
                } catch (Exception ignored2) {}
            }
        }
        if (!ips.isEmpty()) return ips;

        // Active UDP probe to find all local ClipSync devices
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(1200);
            byte[] beacon = "CLIP_SYNC:probe".getBytes(StandardCharsets.UTF_8);
            for (InetAddress bcast : CryptoUtils.getBroadcastAddresses()) {
                try {
                    socket.send(new DatagramPacket(beacon, beacon.length, bcast, UDP_PORT));
                } catch (Exception ignored) {}
            }

            byte[] buf = new byte[1024];
            long deadline = System.currentTimeMillis() + 1200;
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(packet);
                    String peerIp = packet.getAddress().getHostAddress();
                    if (peerIp != null) {
                        peerIp = peerIp.replaceFirst("^::ffff:", "");
                        if (!CryptoUtils.isLocalIp(peerIp)) {
                            ips.add(peerIp);
                            CryptoUtils.addKnownPeerIp(this, peerIp);
                        }
                    }
                } catch (SocketTimeoutException e) {
                    break;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        // Fallback to cached peers if broadcast was filtered by router
        if (ips.isEmpty()) {
            ips.addAll(knownPeers);
        }

        return ips;
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        result = cursor.getString(index);
                    }
                }
            } catch (Exception ignored) {}
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        if (result == null || result.trim().isEmpty()) {
            result = "file_" + System.currentTimeMillis();
        }
        return result;
    }

    private long getFileSize(Uri uri) {
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd != null) {
                return pfd.getStatSize();
            }
        } catch (Exception ignored) {}

        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0) {
                    return cursor.getLong(index);
                }
            }
        } catch (Exception ignored) {}

        return -1;
    }

    private boolean uploadFile(String hostIp, Uri uri, String filename) {
        // Attempt 1: Standard HttpURLConnection
        if (uploadViaHttp(hostIp, uri, filename)) {
            return true;
        }
        // Attempt 2: Direct raw Socket stream fallback
        return uploadViaRawSocket(hostIp, uri, filename);
    }

    private boolean uploadViaHttp(String hostIp, Uri uri, String filename) {
        HttpURLConnection conn = null;
        try {
            long size = getFileSize(uri);
            URL url = new URL("http://" + hostIp + ":" + FILE_PORT + "/upload");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestProperty("X-Filename", URLEncoder.encode(filename, StandardCharsets.UTF_8.name()));
            conn.setRequestProperty("Content-Type", "application/octet-stream");

            long now = System.currentTimeMillis() / 1000L;
            byte[] secretKey = CryptoUtils.getSecretKey(this);
            String authToken = CryptoUtils.generateAuthToken(filename, now, secretKey);
            conn.setRequestProperty("X-ClipSync-Auth", authToken);
            conn.setRequestProperty("X-ClipSync-Time", String.valueOf(now));

            if (size > 0) {
                conn.setFixedLengthStreamingMode(size);
                conn.setRequestProperty("Content-Length", String.valueOf(size));
            } else {
                conn.setChunkedStreamingMode(32768);
            }

            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = conn.getOutputStream()) {
                if (in == null) return false;
                byte[] buffer = new byte[32768];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                out.flush();
            }

            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean uploadViaRawSocket(String hostIp, Uri uri, String filename) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(hostIp, FILE_PORT), 4000);
            socket.setSoTimeout(120000);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            long size = getFileSize(uri);
            String encodedName = URLEncoder.encode(filename, StandardCharsets.UTF_8.name());

            StringBuilder req = new StringBuilder();
            req.append("POST /upload HTTP/1.1\r\n");
            req.append("Host: ").append(hostIp).append(":").append(FILE_PORT).append("\r\n");
            req.append("X-Filename: ").append(encodedName).append("\r\n");
            req.append("Content-Type: application/octet-stream\r\n");

            long nowRaw = System.currentTimeMillis() / 1000L;
            byte[] secretKeyRaw = CryptoUtils.getSecretKey(this);
            String authTokenRaw = CryptoUtils.generateAuthToken(filename, nowRaw, secretKeyRaw);
            req.append("X-ClipSync-Auth: ").append(authTokenRaw).append("\r\n");
            req.append("X-ClipSync-Time: ").append(nowRaw).append("\r\n");
            if (size > 0) {
                req.append("Content-Length: ").append(size).append("\r\n");
            } else {
                req.append("Connection: close\r\n");
            }
            req.append("\r\n");

            out.write(req.toString().getBytes(StandardCharsets.UTF_8));

            try (InputStream fileIn = getContentResolver().openInputStream(uri)) {
                if (fileIn == null) return false;
                byte[] buffer = new byte[32768];
                int bytesRead;
                while ((bytesRead = fileIn.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                out.flush();
            }

            try {
                socket.shutdownOutput();
            } catch (Exception ignored) {}

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String statusLine = reader.readLine();
            return statusLine != null && statusLine.contains("200");
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            return false;
        }
    }
}
