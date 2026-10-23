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
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import android.webkit.MimeTypeMap;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.content.pm.ServiceInfo;
import org.json.JSONObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.BatteryManager;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClipSyncService extends AccessibilityService {
    private static final int UDP_PORT = 9876;
    private static final int TCP_PORT = 9877;
    private static final int FILE_PORT = 9878;
    private String deviceId;
    private String lastHash = "";
    private String lastReceived = "";
    private volatile String lastReceivedFromPeer = "";
    private volatile long lastReceivedFromPeerTime = 0;
    private volatile String lastBroadcastedContent = "";
    private volatile long lastBroadcastedTime = 0;
    private static final ConcurrentHashMap<String, Socket> peers = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> peerNames = new ConcurrentHashMap<>();
    private static volatile String lastPeerIp = null;
    private static volatile ClipSyncService instance = null;
    private ClipboardManager clipboard;
    private Handler mainHandler;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private String lastSelectedText = null;
    private WifiManager.MulticastLock multicastLock;
    private BroadcastReceiver batteryReceiver;
    private int lastAlertedThreshold = 100;
    private boolean lastAlertedFull = false;
    private int lastReportedBatteryLevel = -1;
    private boolean lastReportedCharging = false;
    private long lastBatteryBroadcastTime = 0;

    public static final String ACTION_PAUSE_SYNC = "com.clipsync.ACTION_PAUSE_SYNC";
    public static final String ACTION_RESUME_SYNC = "com.clipsync.ACTION_RESUME_SYNC";
    public static final String ACTION_TOGGLE_SYNC = "com.clipsync.ACTION_TOGGLE_SYNC";
    public static final String SYNC_STATE_CHANGED = "com.clipsync.SYNC_STATE_CHANGED";
    private static final String PREF_SYNC_PAUSED = "sync_paused";
    private BroadcastReceiver controlReceiver;

    public static boolean isSyncPaused(Context ctx) {
        if (ctx == null) return false;
        try {
            return ctx.getSharedPreferences("clipsync_prefs", Context.MODE_PRIVATE)
                      .getBoolean(PREF_SYNC_PAUSED, false);
        } catch (Exception e) {
            return false;
        }
    }

    public static void setSyncPaused(Context ctx, boolean paused) {
        if (ctx == null) return;
        try {
            ctx.getSharedPreferences("clipsync_prefs", Context.MODE_PRIVATE)
               .edit()
               .putBoolean(PREF_SYNC_PAUSED, paused)
               .apply();
        } catch (Exception ignored) {}

        ClipSyncService service = instance;
        if (service != null) {
            service.onSyncPauseStateChanged(paused);
        }

        try {
            Intent intent = new Intent(SYNC_STATE_CHANGED);
            intent.setPackage(ctx.getPackageName());
            ctx.sendBroadcast(intent);
        } catch (Exception ignored) {}
    }

    private void onSyncPauseStateChanged(boolean paused) {
        if (paused) {
            disconnectAllPeers();
            try {
                if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
                if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            } catch (Exception ignored) {}
        } else {
            try {
                if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
                if (multicastLock != null && !multicastLock.isHeld()) multicastLock.acquire();
            } catch (Exception ignored) {}
            new Thread(this::sendDiscoveryProbe).start();
        }
        updateForegroundNotification();
        notifyPeerStateChanged();
    }

    private void disconnectAllPeers() {
        for (java.util.Map.Entry<String, Socket> entry : peers.entrySet()) {
            try {
                Socket s = entry.getValue();
                if (s != null && !s.isClosed()) {
                    s.close();
                }
            } catch (Exception ignored) {}
        }
        peers.clear();
        lastPeerIp = null;
    }

    private void sendDiscoveryProbe() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            byte[] beacon = ("CLIP_SYNC:" + deviceId + ":" + getDeviceName(this)).getBytes(StandardCharsets.UTF_8);
            InetAddress bcast = InetAddress.getByName("255.255.255.255");
            socket.send(new DatagramPacket(beacon, beacon.length, bcast, UDP_PORT));
        } catch (Exception ignored) {}
    }

    public void acquireWakeLockBriefly(long millis) {
        try {
            if (wakeLock != null) {
                wakeLock.acquire(millis);
            }
        } catch (Exception ignored) {}
    }

    public static Set<String> getActivePeers() {
        return peers.keySet();
    }

    public static String getLastPeerIp() {
        return lastPeerIp;
    }

    public static String getDeviceName(Context ctx) {
        if (ctx != null) {
            try {
                String custom = ctx.getSharedPreferences("clipsync_prefs", Context.MODE_PRIVATE)
                                   .getString("custom_device_name", null);
                if (custom != null && !custom.trim().isEmpty()) {
                    return custom.trim();
                }
            } catch (Exception ignored) {}
        }
        String model = Build.MODEL;
        if (model == null || model.trim().isEmpty()) {
            return "Android Phone";
        }
        return model.trim();
    }

    public static void setCustomDeviceName(Context ctx, String name) {
        if (ctx != null) {
            try {
                ctx.getSharedPreferences("clipsync_prefs", Context.MODE_PRIVATE)
                   .edit()
                   .putString("custom_device_name", name != null && !name.trim().isEmpty() ? name.trim() : null)
                   .apply();
            } catch (Exception ignored) {}
        }
        try {
            JSONObject handshake = new JSONObject();
            handshake.put("type", "handshake");
            handshake.put("device_name", getDeviceName(ctx));
            broadcastJson(ctx, handshake);
        } catch (Exception ignored) {}
        notifyPeerStateChanged();
    }

    public static String getConnectedPeerName(String ip) {
        return peerNames.get(ip);
    }

    public static Map<String, String> getConnectedPeersMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String ip : peers.keySet()) {
            String name = peerNames.get(ip);
            map.put(ip, name != null && !name.isEmpty() ? name : "Linux PC");
        }
        return map;
    }

    public static boolean isConnectedToPeer() {
        return !peers.isEmpty();
    }

    public static int getConnectedPeerCount() {
        return peers.size();
    }

    public static String getConnectedPeerSummary() {
        if (peers.isEmpty()) {
            return null;
        }
        if (peers.size() == 1) {
            String ip = peers.keySet().iterator().next();
            String name = peerNames.get(ip);
            return (name != null && !name.isEmpty()) ? name + " (" + ip + ")" : ip;
        }
        List<String> list = new ArrayList<>();
        for (String ip : peers.keySet()) {
            String name = peerNames.get(ip);
            list.add((name != null && !name.isEmpty()) ? name : ip);
        }
        return peers.size() + " Devices (" + android.text.TextUtils.join(", ", list) + ")";
    }

    public static void notifyPeerStateChanged() {
        ClipSyncService service = instance;
        if (service != null) {
            try {
                service.updateForegroundNotification();
                Intent intent = new Intent("com.clipsync.PEER_STATE_CHANGED");
                intent.setPackage(service.getPackageName());
                service.sendBroadcast(intent);
            } catch (Exception ignored) {}
        }
    }

    public static void broadcastText(String text) {
        ClipSyncService service = instance;
        if (service != null) {
            service.processAndBroadcast(text);
        }
    }

    public static void broadcastJson(JSONObject json) {
        broadcastJson(instance, json);
    }

    public static void broadcastJson(Context ctx, JSONObject json) {
        Context context = ctx != null ? ctx : instance;
        if (isSyncPaused(context)) return;
        new Thread(() -> {
            try {
                String devId = (instance != null && instance.deviceId != null) ? instance.deviceId : "android-" + (System.currentTimeMillis() % 10000);
                if (!json.has("device_id")) {
                    json.put("device_id", devId);
                }
                if (!json.has("device_name")) {
                    json.put("device_name", getDeviceName(context));
                }

                byte[] secretKey = context != null ? CryptoUtils.getSecretKey(context) : null;
                String wireString;
                if (secretKey != null) {
                    String payload = CryptoUtils.encrypt(json.toString(), secretKey);
                    if (payload != null) {
                        JSONObject enc = new JSONObject();
                        enc.put("type", "encrypted");
                        enc.put("device_id", devId);
                        enc.put("payload", payload);
                        wireString = enc.toString() + "\n";
                    } else {
                        wireString = json.toString() + "\n";
                    }
                } else {
                    wireString = json.toString() + "\n";
                }
                byte[] bytes = wireString.getBytes(StandardCharsets.UTF_8);

                // If currently no peers connected, try to reconnect to last known peer IP
                if (peers.isEmpty() && context != null) {
                    try {
                        SharedPreferences prefs = context.getSharedPreferences("clipsync_prefs", Context.MODE_PRIVATE);
                        String lastIp = prefs.getString("last_peer_ip", null);
                        if (lastIp != null && !lastIp.isEmpty() && !CryptoUtils.isLocalIp(lastIp)) {
                            Socket s = new Socket();
                            s.connect(new InetSocketAddress(lastIp, TCP_PORT), 2000);
                            s.setKeepAlive(true);
                            s.setTcpNoDelay(true);
                            peers.put(lastIp, s);
                            if (instance != null) {
                                new Thread(() -> instance.handlePeer(s)).start();
                            }
                        }
                    } catch (Exception ignored) {}
                }

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
        }).start();
    }

    public void sendJsonToPeers(JSONObject json) {
        broadcastJson(this, json);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        deviceId = "android-" + (System.currentTimeMillis() % 10000);
        clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClipSync:WakeLock");
                wakeLock.setReferenceCounted(false);
                // No permanent 24h wake lock held! Saves phone CPU battery.
            }
        } catch (Exception ignored) {}

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                // Use standard WIFI_MODE_FULL instead of HIGH_PERF to allow Wi-Fi power saving
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "ClipSync:WifiLock");
                wifiLock.setReferenceCounted(false);

                multicastLock = wm.createMulticastLock("ClipSync:MulticastLock");
                multicastLock.setReferenceCounted(false);

                if (!isSyncPaused(this)) {
                    wifiLock.acquire();
                    multicastLock.acquire();
                }
            }
        } catch (Exception ignored) {}

        setupControlReceiver();
        startForegroundServiceNotification();

        new Thread(this::startTcpServer).start();
        new Thread(this::startFileServer).start();
        new Thread(this::startUdpDiscovery).start();
        startHeartbeat();
        startReconnectWatchdog();
        setupBatteryMonitoring();
        setupTelephonyListener();

        mainHandler.post(() -> {
            if (clipboard != null) {
                clipboard.addPrimaryClipChangedListener(this::checkClipboard);
            }
        });
    }

    private void setupControlReceiver() {
        controlReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (ACTION_PAUSE_SYNC.equals(action)) {
                    setSyncPaused(context, true);
                } else if (ACTION_RESUME_SYNC.equals(action)) {
                    setSyncPaused(context, false);
                } else if (ACTION_TOGGLE_SYNC.equals(action)) {
                    setSyncPaused(context, !isSyncPaused(context));
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_PAUSE_SYNC);
            filter.addAction(ACTION_RESUME_SYNC);
            filter.addAction(ACTION_TOGGLE_SYNC);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(controlReceiver, filter);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        try {
            if (controlReceiver != null) {
                unregisterReceiver(controlReceiver);
            }
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
            if (batteryReceiver != null) {
                unregisterReceiver(batteryReceiver);
            }
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || isSyncPaused(this)) return;
        int eventType = event.getEventType();
        CharSequence pkgCs = event.getPackageName();
        String pkg = pkgCs != null ? pkgCs.toString().toLowerCase() : "";

        // 1. Text Selection Changed: cache only (do not broadcast prematurely)
        if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            try {
                List<CharSequence> texts = event.getText();
                int from = event.getFromIndex();
                int to = event.getToIndex();
                if (texts != null && !texts.isEmpty() && from >= 0 && to > from) {
                    String full = texts.get(0).toString();
                    if (to <= full.length()) {
                        lastSelectedText = full.substring(from, to);
                    }
                }
            } catch (Exception ignored) {}
            return;
        }

        // 2. Android 13+ System UI Clipboard Preview Overlay
        if ("com.android.systemui".equals(pkg) &&
            (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
             eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
             eventType == AccessibilityEvent.TYPE_VIEW_CLICKED)) {
            try {
                AccessibilityNodeInfo source = event.getSource();
                if (source != null) {
                    extractAndBroadcastFromSystemUi(source);
                    source.recycle();
                }
            } catch (Exception ignored) {}
        }

        // 3. View Clicked or Long Clicked: Tapping / long-pressing items (Copy button, Authenticator codes)
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {

            boolean isAuthApp = pkg.contains("authenticator") ||
                                pkg.contains("twofas") ||
                                pkg.contains("aegis") ||
                                pkg.contains("authy") ||
                                pkg.contains("otp") ||
                                pkg.contains("bitwarden") ||
                                pkg.contains("1password") ||
                                pkg.contains("keeper") ||
                                pkg.contains("dashlane") ||
                                pkg.contains("pass");

            AccessibilityNodeInfo source = event.getSource();
            boolean handled = false;

            if (source != null) {
                try {
                    String text = source.getText() != null ? source.getText().toString().trim() : "";
                    String desc = source.getContentDescription() != null ? source.getContentDescription().toString().trim() : "";
                    String viewId = source.getViewIdResourceName() != null ? source.getViewIdResourceName().toLowerCase() : "";

                    boolean isCopyButton = text.equalsIgnoreCase("copy") ||
                                           text.toLowerCase().contains("copy") ||
                                           desc.toLowerCase().contains("copy") ||
                                           viewId.contains("copy");

                    if (isCopyButton) {
                        // User clicked a "Copy" button in standard text selection
                        if (lastSelectedText != null && !lastSelectedText.trim().isEmpty()) {
                            processAndBroadcast(lastSelectedText);
                            lastSelectedText = null;
                            handled = true;
                        } else {
                            AccessibilityNodeInfo parent = source.getParent();
                            if (parent != null) {
                                String siblingText = extractTextFromSiblings(source);
                                if (siblingText != null && !siblingText.isEmpty()) {
                                    processAndBroadcast(siblingText);
                                    handled = true;
                                }
                                parent.recycle();
                            }
                        }
                    }

                    // In Authenticator apps, tapping the code card/row copies the TOTP code
                    if (!handled && isAuthApp) {
                        AccessibilityNodeInfo curr = source;
                        for (int depth = 0; depth < 5 && curr != null; depth++) {
                            String otp = extractOtpFromNode(curr);
                            if (otp != null && !otp.isEmpty()) {
                                processAndBroadcast(otp);
                                handled = true;
                                break;
                            }
                            AccessibilityNodeInfo p = curr.getParent();
                            if (curr != source) {
                                curr.recycle();
                            }
                            curr = p;
                        }
                        if (curr != null && curr != source) {
                            curr.recycle();
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    source.recycle();
                }
            }

            mainHandler.postDelayed(this::checkClipboard, 150);
            mainHandler.postDelayed(this::checkClipboard, 400);
        }

        // 4. Notification / Toast state changed (e.g. "Verification code copied to clipboard")
        if (eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            try {
                if (pkg != null && !"com.clipsync".equals(pkg)) {
                    List<CharSequence> texts = event.getText();
                    if (texts != null && !texts.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (CharSequence cs : texts) {
                            if (sb.length() > 0) sb.append(" ");
                            sb.append(cs);
                        }
                        String alertContent = sb.toString().trim();
                        if (!alertContent.isEmpty()) {
                            String lower = alertContent.toLowerCase();
                            boolean isCopiedToast = lower.contains("copied") ||
                                                   lower.contains("clipboard");

                            if (isCopiedToast) {
                                if (lastSelectedText != null && !lastSelectedText.trim().isEmpty()) {
                                    processAndBroadcast(lastSelectedText);
                                    lastSelectedText = null;
                                } else {
                                    try {
                                        AccessibilityNodeInfo root = getRootInActiveWindow();
                                        if (root != null) {
                                            String windowOtp = extractOtpFromNode(root);
                                            if (windowOtp != null && !windowOtp.isEmpty()) {
                                                processAndBroadcast(windowOtp);
                                            }
                                            root.recycle();
                                        }
                                    } catch (Exception ignored) {}
                                }
                                mainHandler.post(this::checkClipboard);
                                mainHandler.postDelayed(this::checkClipboard, 200);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void extractAndBroadcastFromSystemUi(AccessibilityNodeInfo node) {
        if (node == null) return;
        try {
            String viewId = node.getViewIdResourceName();
            if (viewId != null && (viewId.toLowerCase().contains("preview") || viewId.toLowerCase().contains("clipboard"))) {
                CharSequence cs = node.getText();
                if (cs != null && cs.length() > 0) {
                    processAndBroadcast(cs.toString());
                    return;
                }
                CharSequence desc = node.getContentDescription();
                if (desc != null && desc.length() > 0 && !desc.toString().equalsIgnoreCase("copy")) {
                    processAndBroadcast(desc.toString());
                    return;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    extractAndBroadcastFromSystemUi(child);
                    child.recycle();
                }
            }
        } catch (Exception ignored) {}
    }

    public static String extractOtp(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String t = text.trim();

        // 1. Spaced or punctuated 6 digits: e.g. "123 456", "123-456", "123 456", "123 456"
        Pattern p33 = Pattern.compile("(?<!\\d)(\\d{3})[\\s\\u00A0\\u2009\\-.,/]+(\\d{3})(?!\\d)");
        Matcher m33 = p33.matcher(t);
        if (m33.find()) {
            return m33.group(1) + m33.group(2);
        }

        // 2. Spaced or punctuated 8 digits: e.g. "1234 5678", "1234-5678"
        Pattern p44 = Pattern.compile("(?<!\\d)(\\d{4})[\\s\\u00A0\\u2009\\-.,/]+(\\d{4})(?!\\d)");
        Matcher m44 = p44.matcher(t);
        if (m44.find()) {
            return m44.group(1) + m44.group(2);
        }

        // 3. Spaced-out digits (accessibility: "1, 2, 3, 4, 5, 6" or "1 2 3 4 5 6")
        Pattern pSpaced = Pattern.compile("(?<!\\d)(\\d)[\\s\\u00A0\\u2009,]+(\\d)[\\s\\u00A0\\u2009,]+(\\d)[\\s\\u00A0\\u2009,]+(\\d)[\\s\\u00A0\\u2009,]+(\\d)[\\s\\u00A0\\u2009,]+(\\d)(?!\\d)");
        Matcher mSpaced = pSpaced.matcher(t);
        if (mSpaced.find()) {
            return mSpaced.group(1) + mSpaced.group(2) + mSpaced.group(3) + mSpaced.group(4) + mSpaced.group(5) + mSpaced.group(6);
        }

        // 4. Standalone 6 to 8 continuous digits: e.g. "123456" or "12345678"
        Pattern p68 = Pattern.compile("(?<!\\d)(\\d{6,8})(?!\\d)");
        Matcher m68 = p68.matcher(t);
        if (m68.find()) {
            return m68.group(1);
        }

        // Note: 4-digit pattern removed so account numbers/labels like "1171" are NEVER matched as OTP codes!
        return null;
    }

    private String extractOtpFromNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        List<String> allTexts = new ArrayList<>();
        collectAllNodeTexts(node, allTexts, 0);

        for (String text : allTexts) {
            String otp = extractOtp(text);
            if (otp != null && !otp.isEmpty()) {
                return otp;
            }
        }
        return null;
    }

    private void collectAllNodeTexts(AccessibilityNodeInfo node, List<String> texts, int depth) {
        if (node == null || depth > 8) return;
        CharSequence cs = node.getText();
        if (cs != null && cs.length() > 0) {
            texts.add(cs.toString().trim());
        }
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) {
            texts.add(desc.toString().trim());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectAllNodeTexts(child, texts, depth + 1);
                child.recycle();
            }
        }
    }

    private String extractTextFromSiblings(AccessibilityNodeInfo node) {
        if (node == null || node.getParent() == null) return null;
        AccessibilityNodeInfo parent = node.getParent();
        List<String> texts = new ArrayList<>();
        collectAllNodeTexts(parent, texts, 0);
        parent.recycle();
        for (String t : texts) {
            if (!t.equalsIgnoreCase("copy") && !t.isEmpty()) {
                return t;
            }
        }
        return null;
    }

    @Override
    public void onInterrupt() {}

    private void checkClipboard() {
        if (isSyncPaused(this)) return;
        try {
            if (clipboard == null || !clipboard.hasPrimaryClip()) return;
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).coerceToText(this);
                if (text != null && text.length() > 0) {
                    processAndBroadcast(text.toString());
                }
            }
        } catch (Exception ignored) {}
    }

    private void processAndBroadcast(String str) {
        if (isSyncPaused(this)) return;
        if (str == null) return;
        String clean = str.trim();
        if (clean.isEmpty()) return;

        long now = System.currentTimeMillis();
        // Prevent echo loop of what we just received from Linux within the last 3 seconds
        if (clean.equals(lastReceivedFromPeer) && (now - lastReceivedFromPeerTime < 3000)) {
            return;
        }

        // Prevent rapid duplicate broadcasting of the same text within 1 second
        if (clean.equals(lastBroadcastedContent) && (now - lastBroadcastedTime < 1000)) {
            return;
        }

        acquireWakeLockBriefly(3000);
        lastBroadcastedContent = clean;
        lastBroadcastedTime = now;
        String hash = hash(clean);

        new Thread(() -> broadcast(str, hash)).start();
    }

    private void broadcast(String content, String hash) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "clipboard");
            obj.put("device_id", deviceId);
            obj.put("hash", hash);
            obj.put("content", content);
            sendJsonToPeers(obj);
        } catch (Exception ignored) {}
    }

    private void startTcpServer() {
        while (true) {
            try (ServerSocket server = new ServerSocket(TCP_PORT)) {
                while (true) {
                    Socket socket = server.accept();
                    if (isSyncPaused(this)) {
                        try { socket.close(); } catch (Exception ignored) {}
                        continue;
                    }
                    new Thread(() -> handlePeer(socket)).start();
                }
            } catch (Exception e) {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void handlePeer(Socket socket) {
        String hostIp = socket.getInetAddress().getHostAddress();
        if (hostIp != null) hostIp = hostIp.replaceFirst("^::ffff:", "");
        if (CryptoUtils.isLocalIp(hostIp)) {
            try { socket.close(); } catch (Exception ignored) {}
            return;
        }
        lastPeerIp = hostIp;
        try {
            SharedPreferences prefs = getSharedPreferences("clipsync_prefs", Context.MODE_PRIVATE);
            prefs.edit().putString("last_peer_ip", hostIp).apply();
        } catch (Exception ignored) {}

        try {
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);
        } catch (Exception ignored) {}

        boolean isPrimary = false;
        Socket existing = peers.get(hostIp);
        if (existing == null || existing.isClosed() || !existing.isConnected()) {
            peers.put(hostIp, socket);
            isPrimary = true;
            notifyPeerStateChanged();
        }
        if (isPrimary) {
            try {
                JSONObject handshake = new JSONObject();
                handshake.put("type", "handshake");
                handshake.put("device_id", deviceId);
                handshake.put("device_name", getDeviceName(this));
                sendJsonToPeers(handshake);
            } catch (Exception ignored) {}
            sendCurrentBatteryStatus();
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                acquireWakeLockBriefly(3000);
                JSONObject obj = new JSONObject(line);
                String type = obj.optString("type", "clipboard");

                if ("encrypted".equals(type)) {
                    byte[] secretKey = CryptoUtils.getSecretKey(this);
                    if (secretKey == null) {
                        continue;
                    }
                    String payload = obj.optString("payload");
                    String decrypted = CryptoUtils.decrypt(payload, secretKey);
                    if (decrypted == null) {
                        continue;
                    }
                    obj = new JSONObject(decrypted);
                    type = obj.optString("type", "clipboard");
                }

                if (obj.has("device_name")) {
                    String devName = obj.optString("device_name", "").trim();
                    if (!devName.isEmpty() && !devName.equals(peerNames.get(hostIp))) {
                        peerNames.put(hostIp, devName);
                        notifyPeerStateChanged();
                    }
                }

                if ("handshake".equals(type)) {
                    continue;
                }

                if ("ping".equals(type)) {
                    JSONObject pong = new JSONObject();
                    pong.put("type", "pong");
                    sendJsonToPeers(pong);
                    continue;
                }
                if ("pong".equals(type)) {
                    continue;
                }

                if ("url".equals(type)) {
                    String url = obj.optString("url");
                    if (url == null || url.isEmpty()) {
                        url = obj.optString("content");
                    }
                    if (url != null && !url.trim().isEmpty()) {
                        String rawUrl = url.trim();
                        if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                            rawUrl = "https://" + rawUrl;
                        }
                        final String targetUrl = rawUrl;

                        // 1. Copy URL to phone clipboard
                        mainHandler.post(() -> {
                            try {
                                if (clipboard != null) {
                                    ClipData clip = ClipData.newPlainText("ClipSync", targetUrl);
                                    clipboard.setPrimaryClip(clip);
                                }
                            } catch (Exception ignored) {}
                        });

                        // 2. High-priority Heads-up Notification (bypasses Android 10-14 background activity start restrictions)
                        showUrlNotification(targetUrl);

                        // 3. Attempt direct browser launch
                        mainHandler.post(() -> {
                            try {
                                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl));
                                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                browserIntent.putExtra(android.provider.Browser.EXTRA_APPLICATION_ID, getPackageName());
                                browserIntent.putExtra("create_new_tab", true);
                                startActivity(browserIntent);
                            } catch (Exception ignored) {}
                        });
                    }
                    continue;
                }

                if ("clipboard".equals(type) || type.isEmpty()) {
                    String hash = obj.optString("hash", "");
                    String contentStr = obj.optString("content", "");
                    String clean = contentStr.trim();

                    if (!clean.isEmpty()) {
                        lastReceivedFromPeer = clean;
                        lastReceivedFromPeerTime = System.currentTimeMillis();
                        lastHash = hash;
                        lastReceived = contentStr;

                        mainHandler.post(() -> {
                            try {
                                if (clipboard != null) {
                                    ClipData clip = ClipData.newPlainText("ClipSync", contentStr);
                                    clipboard.setPrimaryClip(clip);
                                }
                            } catch (Exception ignored) {}
                        });
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (isPrimary) {
                peers.remove(hostIp, socket);
                peerNames.remove(hostIp);
                notifyPeerStateChanged();
            }
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void startFileServer() {
        while (true) {
            try (ServerSocket server = new ServerSocket(FILE_PORT)) {
                while (true) {
                    Socket socket = server.accept();
                    if (isSyncPaused(this)) {
                        try { socket.close(); } catch (Exception ignored) {}
                        continue;
                    }
                    new Thread(() -> handleIncomingFile(socket)).start();
                }
            } catch (Exception e) {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void handleIncomingFile(Socket socket) {
        acquireWakeLockBriefly(30000);
        try {
            socket.setSoTimeout(30000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String filename = null;
            String authHeader = null;
            long timestamp = 0;
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
                    } else if (lower.startsWith("x-clipsync-auth:")) {
                        authHeader = line.substring(line.indexOf(':') + 1).trim();
                    } else if (lower.startsWith("x-clipsync-time:")) {
                        try {
                            timestamp = Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
                        } catch (Exception ignored) {}
                    }
                } else if (c != '\r') {
                    lineBuf.append(c);
                }
            }

            byte[] secretKey = CryptoUtils.getSecretKey(this);
            if (secretKey != null) {
                if (authHeader == null || !CryptoUtils.verifyAuthToken(filename, authHeader, timestamp, secretKey)) {
                    out.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    socket.close();
                    return;
                }
            }

            if (filename == null || filename.isEmpty()) {
                filename = "received_" + System.currentTimeMillis();
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
                    if (contentLength > 0) {
                        long remaining = contentLength;
                        int read;
                        while (remaining > 0 && (read = in.read(buf, 0, (int) Math.min(buf.length, remaining))) != -1) {
                            os.write(buf, 0, read);
                            remaining -= read;
                        }
                    } else {
                        int read;
                        while ((read = in.read(buf)) != -1) {
                            os.write(buf, 0, read);
                        }
                    }
                    os.flush();
                }
            }
        } catch (Exception e) {
            try {
                File dir = isImage ?
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) :
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (dir != null && !dir.exists()) {
                    dir.mkdirs();
                }
                File target = new File(dir, filename);
                try (FileOutputStream fos = new FileOutputStream(target)) {
                    byte[] buf = new byte[32768];
                    if (contentLength > 0) {
                        long remaining = contentLength;
                        int read;
                        while (remaining > 0 && (read = in.read(buf, 0, (int) Math.min(buf.length, remaining))) != -1) {
                            fos.write(buf, 0, read);
                            remaining -= read;
                        }
                    } else {
                        int read;
                        while ((read = in.read(buf)) != -1) {
                            fos.write(buf, 0, read);
                        }
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

    private void showUrlNotification(String targetUrl) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            String channelId = "clipsync_urls";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                    channelId, "ClipSync Web Links", NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Notifications when a web page is sent from PC");
                channel.enableVibration(true);
                nm.createNotificationChannel(channel);
            }

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, channelId);
            } else {
                builder = new Notification.Builder(this);
            }

            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl));
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(this, (int) (System.currentTimeMillis() % 100000), browserIntent, flags);

            builder.setSmallIcon(android.R.drawable.ic_menu_compass)
                   .setContentTitle("🌐 Web Page Received")
                   .setContentText(targetUrl)
                   .setContentIntent(pi)
                   .setAutoCancel(true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.setPriority(Notification.PRIORITY_HIGH);
            }

            Notification.Action openAction = new Notification.Action.Builder(
                android.R.drawable.ic_menu_view, "Open in Browser", pi).build();
            builder.addAction(openAction);

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
            while (true) {
                try (DatagramSocket socket = new DatagramSocket()) {
                    socket.setBroadcast(true);
                    InetAddress bcast = InetAddress.getByName("255.255.255.255");
                    while (true) {
                        if (!isSyncPaused(this)) {
                            byte[] beacon = ("CLIP_SYNC:" + deviceId + ":" + getDeviceName(this)).getBytes(StandardCharsets.UTF_8);
                            socket.send(new DatagramPacket(beacon, beacon.length, bcast, UDP_PORT));
                        }
                        if (isSyncPaused(this)) {
                            Thread.sleep(4000);
                        } else if (!peers.isEmpty()) {
                            // Already connected to PC! Slow down beaconing to save battery
                            Thread.sleep(15000);
                        } else {
                            Thread.sleep(4000);
                        }
                    }
                } catch (Exception e) {
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                }
            }
        }).start();

        new Thread(() -> {
            while (true) {
                try (DatagramSocket socket = new DatagramSocket(UDP_PORT)) {
                    byte[] buf = new byte[1024];
                    while (true) {
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        socket.receive(packet);
                        if (isSyncPaused(this)) {
                            continue;
                        }
                        String peerIp = packet.getAddress().getHostAddress();
                        if (peerIp != null) peerIp = peerIp.replaceFirst("^::ffff:", "");
                        if (CryptoUtils.isLocalIp(peerIp)) {
                            continue;
                        }

                        String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);

                        // If probe or ping, reply immediately with device ID and name so PC can discover phone instantly
                        if (msg.startsWith("CLIP_SYNC:probe") || msg.startsWith("CLIP_SYNC:ping")) {
                            try (DatagramSocket replySock = new DatagramSocket()) {
                                byte[] reply = ("CLIP_SYNC:" + deviceId + ":" + getDeviceName(this)).getBytes(StandardCharsets.UTF_8);
                                replySock.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
                            } catch (Exception ignored) {}
                            continue;
                        }

                        if (msg.startsWith("CLIP_SYNC:")) {
                            String[] parts = msg.split(":", 3);
                            String senderId = parts.length > 1 ? parts[1] : "";
                            String senderName = parts.length > 2 ? parts[2] : "";

                            if (senderId.equals(deviceId)) {
                                continue;
                            }
                            if (!senderName.isEmpty() && !senderName.equals(peerNames.get(peerIp))) {
                                peerNames.put(peerIp, senderName);
                                notifyPeerStateChanged();
                            }
                            final String finalIp = peerIp;
                            if (!peers.containsKey(finalIp)) {
                                new Thread(() -> connectToPeer(finalIp)).start();
                            }
                        }
                    }
                } catch (Exception e) {
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                }
            }
        }).start();
    }

    private void connectToPeer(String ip) {
        if (isSyncPaused(this)) return;
        if (ip == null || ip.trim().isEmpty()) return;
        String cleanIp = ip.replaceFirst("^::ffff:", "");
        if (CryptoUtils.isLocalIp(cleanIp)) return;
        try {
            Socket existing = peers.get(cleanIp);
            if (existing != null && !existing.isClosed() && existing.isConnected()) return;
            Socket socket = new Socket(cleanIp, TCP_PORT);
            new Thread(() -> handlePeer(socket)).start();
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

    private void startForegroundServiceNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            String channelId = "clipsync_persistent";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                    channelId, "ClipSync Service", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Keeps ClipSync permanently connected to your Linux PC");
                if (nm != null) nm.createNotificationChannel(channel);
            }

            Intent intent = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, channelId);
            } else {
                builder = new Notification.Builder(this);
            }

            boolean paused = isSyncPaused(this);
            boolean connected = isConnectedToPeer();
            String peerSummary = getConnectedPeerSummary();

            String title;
            String text;
            if (paused) {
                title = "ClipSync Paused";
                text = "⏸️ Background sync paused • Tap Resume to activate";
            } else if (connected && peerSummary != null) {
                title = "ClipSync Active";
                text = "🟢 Connected to " + peerSummary + " (AES-256)";
            } else {
                title = "ClipSync Searching";
                text = "📡 Searching for Linux PC on Wi-Fi...";
            }

            builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                   .setContentTitle(title)
                   .setContentText(text)
                   .setOngoing(true)
                   .setContentIntent(pi);

            Intent actionIntent = new Intent(paused ? ACTION_RESUME_SYNC : ACTION_PAUSE_SYNC);
            actionIntent.setPackage(getPackageName());
            PendingIntent actionPi = PendingIntent.getBroadcast(this, 102, actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                Notification.Action action = new Notification.Action.Builder(
                    paused ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause,
                    paused ? "Resume Sync" : "Pause Sync",
                    actionPi
                ).build();
                builder.addAction(action);
            }

            Notification notification = builder.build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(1001, notification);
            }

            if (nm != null) {
                nm.notify(1001, notification);
            }
        } catch (Exception ignored) {}
    }

    public void updateForegroundNotification() {
        startForegroundServiceNotification();
    }

    private void startHeartbeat() {
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(15000);
                    if (!isSyncPaused(this) && !peers.isEmpty()) {
                        JSONObject ping = new JSONObject();
                        ping.put("type", "ping");
                        sendJsonToPeers(ping);
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private void startReconnectWatchdog() {
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(8000);
                    if (!isSyncPaused(this) && peers.isEmpty()) {
                        SharedPreferences prefs = getSharedPreferences("clipsync_prefs", Context.MODE_PRIVATE);
                        String lastIp = prefs.getString("last_peer_ip", null);
                        if (lastIp != null && !lastIp.isEmpty()) {
                            connectToPeer(lastIp);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private void setupBatteryMonitoring() {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);

            batteryReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    handleBatteryIntent(context, intent);
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(batteryReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(batteryReceiver, filter);
            }
        } catch (Exception ignored) {}
    }

    private void handleBatteryIntent(Context context, Intent intent) {
        if (intent == null) return;
        try {
            String action = intent.getAction();
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int percent = (level >= 0 && scale > 0) ? (int) ((level / (float) scale) * 100) : -1;
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                  status == BatteryManager.BATTERY_STATUS_FULL);
            if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                isCharging = true;
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                isCharging = false;
            }
            if (percent < 0) return;

            long now = System.currentTimeMillis();
            boolean shouldAlert = false;
            String batteryStatus = "status";
            String title = "";
            String content = "";

            if (!isCharging) {
                lastAlertedFull = false;
                if (percent <= 5 && lastAlertedThreshold > 5) {
                    lastAlertedThreshold = 5;
                    shouldAlert = true;
                    batteryStatus = "critical";
                    title = "🪫 Phone Battery Critical (" + percent + "%)";
                    content = "Phone battery is at " + percent + "%! Connect charger immediately.";
                } else if (percent <= 10 && lastAlertedThreshold > 10) {
                    lastAlertedThreshold = 10;
                    shouldAlert = true;
                    batteryStatus = "critical";
                    title = "🪫 Phone Battery Very Low (" + percent + "%)";
                    content = "Phone battery is at " + percent + "%. Please connect your charger.";
                } else if (percent <= 20 && lastAlertedThreshold > 20) {
                    lastAlertedThreshold = 20;
                    shouldAlert = true;
                    batteryStatus = "low";
                    title = "🪫 Phone Battery Low (" + percent + "%)";
                    content = "Phone battery is at " + percent + "%. Please connect your charger.";
                }
            } else {
                lastAlertedThreshold = 100;
                if ((percent >= 100 || status == BatteryManager.BATTERY_STATUS_FULL) && !lastAlertedFull) {
                    lastAlertedFull = true;
                    shouldAlert = true;
                    batteryStatus = "full";
                    title = "🔋 Phone Fully Charged (100%)";
                    content = "Phone is fully charged. You can unplug the charger now.";
                }
            }

            boolean chargingChanged = (isCharging != lastReportedCharging);
            boolean levelSignificantChange = Math.abs(percent - lastReportedBatteryLevel) >= 5 && (now - lastBatteryBroadcastTime > 60000);

            if (shouldAlert || chargingChanged || levelSignificantChange) {
                lastReportedBatteryLevel = percent;
                lastReportedCharging = isCharging;
                lastBatteryBroadcastTime = now;

                JSONObject obj = new JSONObject();
                obj.put("type", "battery");
                obj.put("battery_level", percent);
                obj.put("is_charging", isCharging);
                obj.put("battery_status", batteryStatus);
                if (!title.isEmpty()) obj.put("title", title);
                if (!content.isEmpty()) obj.put("content", content);
                obj.put("timestamp", now);

                broadcastJson(context != null ? context : this, obj);
            }
        } catch (Exception ignored) {}
    }

    public void sendCurrentBatteryStatus() {
        try {
            Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent != null) {
                int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int percent = (level >= 0 && scale > 0) ? (int) ((level / (float) scale) * 100) : -1;
                int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                      status == BatteryManager.BATTERY_STATUS_FULL);
                if (percent >= 0) {
                    JSONObject obj = new JSONObject();
                    obj.put("type", "battery");
                    obj.put("battery_level", percent);
                    obj.put("is_charging", isCharging);
                    obj.put("battery_status", "status");
                    obj.put("timestamp", System.currentTimeMillis());
                    sendJsonToPeers(obj);
                }
            }
        } catch (Exception ignored) {}
    }

    private void setupTelephonyListener() {
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    tm.registerTelephonyCallback(getMainExecutor(), new Api31CallCallback(this));
                }
            } else {
                tm.listen(new android.telephony.PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int state, String incomingNumber) {
                        if (state == android.telephony.TelephonyManager.CALL_STATE_IDLE) {
                            ClipSyncNotificationService.clearAllActiveCalls(ClipSyncService.this);
                        }
                    }
                }, android.telephony.PhoneStateListener.LISTEN_CALL_STATE);
            }
        } catch (Exception ignored) {}
    }

    @androidx.annotation.RequiresApi(api = Build.VERSION_CODES.S)
    private static class Api31CallCallback extends android.telephony.TelephonyCallback implements android.telephony.TelephonyCallback.CallStateListener {
        private final Context context;
        Api31CallCallback(Context ctx) {
            this.context = ctx;
        }
        @Override
        public void onCallStateChanged(int state) {
            if (state == android.telephony.TelephonyManager.CALL_STATE_IDLE) {
                ClipSyncNotificationService.clearAllActiveCalls(context);
            }
        }
    }

}

