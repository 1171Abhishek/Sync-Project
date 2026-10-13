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

public class ShareActivity extends Activity {
    private static final int FILE_PORT = 9878;
    private static final int UDP_PORT = 9876;
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
            ClipSyncService.broadcastText(text);
            Toast.makeText(this, "✓ Copied to PC clipboard!", Toast.LENGTH_SHORT).show();
            finish();
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
        Toast.makeText(this, "Connecting to PC...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            Set<String> targetIps = getTargetIps();

            if (targetIps.isEmpty()) {
                mainHandler.post(() -> {
                    Toast.makeText(getApplicationContext(), "❌ No PC found on Wi-Fi (make sure clip-sync is running on PC)", Toast.LENGTH_LONG).show();
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
                    Toast.makeText(getApplicationContext(), "✓ Successfully sent " + finalSuccess + " file(s) to PC!", Toast.LENGTH_SHORT).show();
                } else {
                    String msg = lastError.isEmpty() ? "Check if PC is reachable on Wi-Fi" : lastError;
                    Toast.makeText(getApplicationContext(), "❌ Send failed: " + msg, Toast.LENGTH_LONG).show();
                }
            });
        }).start();

        finish();
    }

    private Set<String> getTargetIps() {
        Set<String> ips = new HashSet<>(ClipSyncService.getActivePeers());
        if (!ips.isEmpty()) return ips;

        SharedPreferences prefs = getSharedPreferences("clipsync_prefs", Context.MODE_PRIVATE);
        String cached = prefs.getString("last_peer_ip", null);
        if (cached != null && !cached.trim().isEmpty()) {
            ips.add(cached.trim());
            return ips;
        }

        // Active UDP probe to find PC
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(1200);
            byte[] beacon = "CLIP_SYNC:probe".getBytes(StandardCharsets.UTF_8);
            InetAddress bcast = InetAddress.getByName("255.255.255.255");
            socket.send(new DatagramPacket(beacon, beacon.length, bcast, UDP_PORT));

            byte[] buf = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(packet);
                String peerIp = packet.getAddress().getHostAddress();
                if (peerIp != null) {
                    ips.add(peerIp);
                }
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}

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

            if (size > 0 && size < Integer.MAX_VALUE) {
                conn.setFixedLengthStreamingMode((int) size);
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

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String statusLine = reader.readLine();
            return statusLine != null && statusLine.contains("200");
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            return false;
        }
    }
}
