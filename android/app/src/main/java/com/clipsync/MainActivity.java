package com.clipsync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.Map;

public class MainActivity extends AppCompatActivity {

    // Header
    private TextView tvDeviceNameSubtitle;

    // Hero Overview
    private MaterialCardView cardHeroStatus;
    private ImageView ivHeroIcon;
    private TextView tvHeroTitle;
    private TextView tvHeroSubtitle;

    // Clipboard Sync
    private TextView tvClipboardActiveBadge;
    private MaterialButton btnClipboardEnable;

    // Calls & Notifications
    private TextView tvNotifActiveBadge;
    private MaterialButton btnNotifEnable;

    // Battery / Background Persistence
    private TextView tvBatteryActiveBadge;
    private MaterialButton btnBatteryEnable;

    // Master Sync Switch
    private com.google.android.material.materialswitch.MaterialSwitch switchMasterSync;

    // Real-time peer connection tracking
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRefresher = new Runnable() {
        @Override
        public void run() {
            updateUI();
            statusHandler.postDelayed(this, 1500);
        }
    };

    private final BroadcastReceiver peerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateUI();
        }
    };
    private boolean isReceiverRegistered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Android 13+ runtime permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            java.util.List<String> perms = new java.util.ArrayList<>();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    perms.add(android.Manifest.permission.POST_NOTIFICATIONS);
                }
            }
            if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                perms.add(android.Manifest.permission.READ_PHONE_STATE);
            }
            if (!perms.isEmpty()) {
                requestPermissions(perms.toArray(new String[0]), 101);
            }
        }

        initViews();
        setupClickListeners();
    }

    private void initViews() {
        tvDeviceNameSubtitle = findViewById(R.id.tvDeviceNameSubtitle);
        switchMasterSync = findViewById(R.id.switchMasterSync);

        cardHeroStatus = findViewById(R.id.cardHeroStatus);
        ivHeroIcon = findViewById(R.id.ivHeroIcon);
        tvHeroTitle = findViewById(R.id.tvHeroTitle);
        tvHeroSubtitle = findViewById(R.id.tvHeroSubtitle);

        tvClipboardActiveBadge = findViewById(R.id.tvClipboardActiveBadge);
        btnClipboardEnable = findViewById(R.id.btnClipboardEnable);

        tvNotifActiveBadge = findViewById(R.id.tvNotifActiveBadge);
        btnNotifEnable = findViewById(R.id.btnNotifEnable);

        tvBatteryActiveBadge = findViewById(R.id.tvBatteryActiveBadge);
        btnBatteryEnable = findViewById(R.id.btnBatteryEnable);
    }

    private void setupClickListeners() {
        if (tvDeviceNameSubtitle != null) {
            tvDeviceNameSubtitle.setOnClickListener(v -> showRenameDeviceDialog());
        }

        if (cardHeroStatus != null) {
            cardHeroStatus.setOnClickListener(v -> showConnectedDevicesDialog());
        }

        if (switchMasterSync != null) {
            switchMasterSync.setChecked(!ClipSyncService.isSyncPaused(this));
            switchMasterSync.setOnCheckedChangeListener((buttonView, isChecked) -> {
                ClipSyncService.setSyncPaused(MainActivity.this, !isChecked);
                updateUI();
            });
        }

        btnClipboardEnable.setOnClickListener(v -> {
            if (!isAccessibilityServiceEnabled()) {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                startActivity(intent);
            }
        });

        btnNotifEnable.setOnClickListener(v -> {
            if (!isNotificationServiceEnabled()) {
                Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                startActivity(intent);
            }
        });

        btnBatteryEnable.setOnClickListener(v -> {
            if (isBatteryOptimized()) {
                requestIgnoreBatteryOptimization();
            }
        });
    }

    private void showRenameDeviceDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename Device");

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(ClipSyncService.getDeviceName(this));
        input.setSelection(input.getText().length());

        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        int margin = (int) (18 * getResources().getDisplayMetrics().density);
        params.setMargins(margin, 0, margin, 0);
        input.setLayoutParams(params);
        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty()) {
                ClipSyncService.setCustomDeviceName(this, newName);
                updateUI();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private String pendingTargetIp = null;
    private String pendingTargetName = null;
    private static final int REQUEST_CODE_PICK_FILE = 2001;

    private void showConnectedDevicesDialog() {
        Map<String, String> peersMap = ClipSyncService.getConnectedPeersMap();
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Connected Devices (" + peersMap.size() + ")");

        if (peersMap.isEmpty()) {
            builder.setMessage("No devices currently connected.\n\nEnsure your PC or other phones are connected to the same Wi-Fi network and ClipSync is active.");
            builder.setPositiveButton("Search Now", (d, w) -> {
                ClipSyncService.sendDiscoveryProbe(this);
                Toast.makeText(this, "Searching for ClipSync devices...", Toast.LENGTH_SHORT).show();
            });
            builder.setNegativeButton("Close", null);
        } else {
            java.util.List<Map.Entry<String, String>> entries = new java.util.ArrayList<>(peersMap.entrySet());
            String[] items = new String[entries.size()];
            for (int i = 0; i < entries.size(); i++) {
                Map.Entry<String, String> entry = entries.get(i);
                String name = entry.getValue();
                String ip = entry.getKey();
                String lower = name.toLowerCase();
                String icon = (lower.contains("linux") || lower.contains("pc") || lower.contains("desktop") || lower.contains("laptop") || lower.contains("computer")) ? "💻 " : "📱 ";
                items[i] = icon + name + "\n" + ip + " • Live";
            }

            builder.setItems(items, (dialog, which) -> {
                if (which >= 0 && which < entries.size()) {
                    Map.Entry<String, String> selected = entries.get(which);
                    showDeviceActionDialog(selected.getKey(), selected.getValue());
                }
            });
            builder.setNegativeButton("Close", null);
        }

        builder.show();
    }

    private void showDeviceActionDialog(String peerIp, String peerName) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(peerName);

        String[] actions = new String[] {
            "📤 Send File to this Device",
            "📋 Send Current Clipboard",
            "🔌 Disconnect Device"
        };

        builder.setItems(actions, (dialog, which) -> {
            if (which == 0) {
                pendingTargetIp = peerIp;
                pendingTargetName = peerName;
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    startActivityForResult(intent, REQUEST_CODE_PICK_FILE);
                } catch (Exception e) {
                    Toast.makeText(this, "Could not open file picker", Toast.LENGTH_SHORT).show();
                }
            } else if (which == 1) {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence cs = cm.getPrimaryClip().getItemAt(0).getText();
                    if (cs != null && cs.length() > 0) {
                        ClipSyncService.broadcastText(cs.toString(), java.util.Collections.singleton(peerIp));
                        Toast.makeText(this, "✓ Clipboard sent to " + peerName, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Clipboard has no text", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show();
                }
            } else if (which == 2) {
                ClipSyncService.disconnectPeer(peerIp);
                Toast.makeText(this, "Disconnected from " + peerName, Toast.LENGTH_SHORT).show();
                updateUI();
            }
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            if (pendingTargetIp != null) {
                Intent sendIntent = new Intent(this, ShareActivity.class);
                sendIntent.setAction(Intent.ACTION_SEND);
                sendIntent.putExtra(Intent.EXTRA_STREAM, uri);
                sendIntent.putExtra("target_ip", pendingTargetIp);
                sendIntent.putExtra("target_name", pendingTargetName);
                sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(sendIntent);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isReceiverRegistered) {
            try {
                IntentFilter filter = new IntentFilter();
                filter.addAction("com.clipsync.PEER_STATE_CHANGED");
                filter.addAction("com.clipsync.SYNC_STATE_CHANGED");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(peerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(peerReceiver, filter);
                }
                isReceiverRegistered = true;
            } catch (Exception ignored) {}
        }
        statusHandler.post(statusRefresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        statusHandler.removeCallbacks(statusRefresher);
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(peerReceiver);
            } catch (Exception ignored) {}
            isReceiverRegistered = false;
        }
    }

    private void updateUI() {
        boolean isPaused = ClipSyncService.isSyncPaused(this);

        if (tvDeviceNameSubtitle != null) {
            tvDeviceNameSubtitle.setText("Device: " + ClipSyncService.getDeviceName(this));
        }

        // Keep switch in sync with background state without triggering recursive callbacks
        if (switchMasterSync != null && switchMasterSync.isChecked() == isPaused) {
            switchMasterSync.setOnCheckedChangeListener(null);
            switchMasterSync.setChecked(!isPaused);
            switchMasterSync.setOnCheckedChangeListener((buttonView, isChecked) -> {
                ClipSyncService.setSyncPaused(MainActivity.this, !isChecked);
                updateUI();
            });
        }

        boolean a11yActive = isAccessibilityServiceEnabled();
        boolean notifActive = isNotificationServiceEnabled();
        boolean batteryUnrestricted = !isBatteryOptimized();

        // 1. Clipboard Card
        if (a11yActive) {
            tvClipboardActiveBadge.setVisibility(View.VISIBLE);
            btnClipboardEnable.setVisibility(View.GONE);
        } else {
            tvClipboardActiveBadge.setVisibility(View.GONE);
            btnClipboardEnable.setVisibility(View.VISIBLE);
        }

        // 2. Notification & Calls Card
        if (notifActive) {
            tvNotifActiveBadge.setVisibility(View.VISIBLE);
            btnNotifEnable.setVisibility(View.GONE);
        } else {
            tvNotifActiveBadge.setVisibility(View.GONE);
            btnNotifEnable.setVisibility(View.VISIBLE);
        }

        // 3. Battery Optimization Card
        if (batteryUnrestricted) {
            tvBatteryActiveBadge.setVisibility(View.VISIBLE);
            btnBatteryEnable.setVisibility(View.GONE);
        } else {
            tvBatteryActiveBadge.setVisibility(View.GONE);
            btnBatteryEnable.setVisibility(View.VISIBLE);
        }

        // 4. Hero Status Card (Real-Time Device Connection State)
        if (isPaused) {
            // State 0: ⏸️ Paused by user!
            cardHeroStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_paused_bg));
            cardHeroStatus.setStrokeColor(ContextCompat.getColor(this, R.color.status_paused_border));
            ivHeroIcon.setImageResource(R.drawable.ic_pause);
            ivHeroIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_paused_text));
            tvHeroTitle.setText("⏸️ Sync Paused");
            tvHeroTitle.setTextColor(ContextCompat.getColor(this, R.color.status_paused_text));
            tvHeroSubtitle.setText("Background sync paused • Conserving battery");
            tvHeroSubtitle.setTextColor(ContextCompat.getColor(this, R.color.status_paused_text));
        } else {
            int missingCount = (a11yActive ? 0 : 1) + (notifActive ? 0 : 1) + (batteryUnrestricted ? 0 : 1);
            if (missingCount > 0) {
                // State 1: Permissions need attention
                cardHeroStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_warning_bg));
                cardHeroStatus.setStrokeColor(ContextCompat.getColor(this, R.color.status_warning_border));
                ivHeroIcon.setImageResource(R.drawable.ic_bolt);
                ivHeroIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_warning_text));
                tvHeroTitle.setText("Setup Incomplete");
                tvHeroTitle.setTextColor(ContextCompat.getColor(this, R.color.status_warning_text));
                tvHeroSubtitle.setText(missingCount + (missingCount == 1 ? " permission needed" : " permissions needed") + " for background sync");
                tvHeroSubtitle.setTextColor(ContextCompat.getColor(this, R.color.status_warning_text));
            } else {
                // Permissions are all granted! Check real-time active connection:
                boolean connected = ClipSyncService.isConnectedToPeer();
                int peerCount = ClipSyncService.getConnectedPeerCount();
                String peerIp = ClipSyncService.getConnectedPeerSummary();

                if (connected && peerIp != null && !peerIp.isEmpty()) {
                    // State 2: 🟢 Connected in real time!
                    cardHeroStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_success_bg));
                    cardHeroStatus.setStrokeColor(ContextCompat.getColor(this, R.color.status_success_border));
                    ivHeroIcon.setImageResource(R.drawable.ic_check);
                    ivHeroIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_success_text));
                    if (peerCount > 1) {
                        tvHeroTitle.setText("🟢 Connected to " + peerCount + " Devices");
                        tvHeroSubtitle.setText("Live mesh active • " + peerIp);
                    } else {
                        Map<String, String> currentPeers = ClipSyncService.getConnectedPeersMap();
                        String peerName = null;
                        if (!currentPeers.isEmpty()) {
                            peerName = currentPeers.values().iterator().next();
                        }
                        tvHeroTitle.setText("🟢 Connected to " + (peerName != null && !peerName.isEmpty() ? peerName : "Device"));
                        tvHeroSubtitle.setText("Live sync active • " + peerIp);
                    }
                    tvHeroSubtitle.setTextColor(ContextCompat.getColor(this, R.color.status_success_text));
                } else {
                    // State 3: 📡 Searching for devices on Wi-Fi
                    cardHeroStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_searching_bg));
                    cardHeroStatus.setStrokeColor(ContextCompat.getColor(this, R.color.status_searching_border));
                    ivHeroIcon.setImageResource(R.drawable.ic_bolt);
                    ivHeroIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_searching_text));
                    tvHeroTitle.setText("📡 Searching for Devices...");
                    tvHeroTitle.setTextColor(ContextCompat.getColor(this, R.color.status_searching_text));
                    tvHeroSubtitle.setText("Waiting for PC or phone on local Wi-Fi");
                    tvHeroSubtitle.setTextColor(ContextCompat.getColor(this, R.color.status_searching_text));
                }
            }
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        String serviceName = getPackageName() + "/" + ClipSyncService.class.getName();
        String enabledServices = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices != null) {
            String[] parts = enabledServices.split(":");
            for (String part : parts) {
                if (part.equalsIgnoreCase(serviceName) || part.contains(getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isNotificationServiceEnabled() {
        String pkgName = getPackageName();
        String enabledListeners = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (enabledListeners != null && !enabledListeners.isEmpty()) {
            String[] parts = enabledListeners.split(":");
            for (String part : parts) {
                if (part.contains(pkgName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isBatteryOptimized() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                return !pm.isIgnoringBatteryOptimizations(getPackageName());
            }
        }
        return false;
    }

    private void requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                try {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
    }
}
