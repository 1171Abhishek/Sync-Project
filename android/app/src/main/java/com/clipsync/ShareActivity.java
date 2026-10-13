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
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ShareActivity extends Activity {
    private static final int FILE_PORT = 9878;
    private Handler mainHandler;

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
        // 1. Check if sharing a file/stream (Photo, Video, PDF, etc.)
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri != null) {
            List<Uri> uris = new ArrayList<>();
            uris.add(uri);
            sendFiles(uris);
            return;
        }

        // 2. Check if sharing plain text or URL link
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
        Set<String> targetIps = new HashSet<>(ClipSyncService.getActivePeers());
        if (targetIps.isEmpty()) {
            SharedPreferences prefs = getSharedPreferences("clipsync_prefs", Context.MODE_PRIVATE);
            String cached = prefs.getString("last_peer_ip", null);
            if (cached != null && !cached.isEmpty()) {
                targetIps.add(cached);
            }
        }

        if (targetIps.isEmpty()) {
            Toast.makeText(this, "No PC connected on Wi-Fi!", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        Toast.makeText(this, "Sending " + uris.size() + " file(s) to PC...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            int successCount = 0;
            for (Uri uri : uris) {
                String fileName = getFileName(uri);
                for (String ip : targetIps) {
                    if (uploadStream(ip, uri, fileName)) {
                        successCount++;
                    }
                }
            }

            final int finalSuccess = successCount;
            mainHandler.post(() -> {
                if (finalSuccess > 0) {
                    Toast.makeText(getApplicationContext(), "✓ Sent " + finalSuccess + " file(s) to PC!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getApplicationContext(), "Failed to send (check if PC is on & clip-sync running)", Toast.LENGTH_LONG).show();
                }
            });
        }).start();

        // Finish immediately so the share menu closes gracefully
        finish();
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
            result = "shared_" + System.currentTimeMillis();
        }
        return result;
    }

    private boolean uploadStream(String hostIp, Uri uri, String filename) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + hostIp + ":" + FILE_PORT + "/upload");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(120000); // 2 minutes
            conn.setDoOutput(true);
            conn.setChunkedStreamingMode(32768);
            conn.setRequestProperty("X-Filename", URLEncoder.encode(filename, StandardCharsets.UTF_8.name()));
            conn.setRequestProperty("Content-Type", "application/octet-stream");

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
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
