package com.clipsync;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private Button a11yBtn;
    private Button notifBtn;
    private Button batteryBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 60, 60, 60);

        LinearLayout headerLayout = new LinearLayout(this);
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);
        headerLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        int logoSize = (int) (44 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(logoSize, logoSize);
        logoParams.setMargins(0, 0, (int) (12 * getResources().getDisplayMetrics().density), 0);
        logo.setLayoutParams(logoParams);
        headerLayout.addView(logo);

        TextView title = new TextView(this);
        title.setText("ClipSync");
        title.setTextSize(26);
        title.setTextColor(Color.BLACK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        headerLayout.addView(title);

        layout.addView(headerLayout);

        TextView desc = new TextView(this);
        desc.setText("\nSeamless local continuity with your Linux PC.\nTo enable features, please grant the permissions below:\n");
        desc.setTextSize(15);
        layout.addView(desc);

        // 1. Accessibility
        a11yBtn = new Button(this);
        updateAccessibilityButton();
        a11yBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });
        layout.addView(a11yBtn);

        TextView spacer1 = new TextView(this);
        spacer1.setText("\n");
        layout.addView(spacer1);

        // 2. Notification Access
        notifBtn = new Button(this);
        updateNotificationButton();
        notifBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        });
        layout.addView(notifBtn);

        TextView spacer2 = new TextView(this);
        spacer2.setText("\n");
        layout.addView(spacer2);

        // 3. Battery Optimization
        batteryBtn = new Button(this);
        updateBatteryButton();
        batteryBtn.setOnClickListener(v -> {
            requestIgnoreBatteryOptimization();
        });
        layout.addView(batteryBtn);

        TextView spacer3 = new TextView(this);
        spacer3.setText("\n");
        layout.addView(spacer3);

        // 4. Automatic Security & Encryption Status
        TextView securityHeader = new TextView(this);
        securityHeader.setText("4. Security & Encryption");
        securityHeader.setTextSize(18);
        securityHeader.setTextColor(Color.BLACK);
        layout.addView(securityHeader);

        TextView securityStatus = new TextView(this);
        securityStatus.setText("🔒 100% Automatic End-to-End Encrypted (AES-256-GCM)\nAll clipboard, notifications, URLs, and file drops are automatically encrypted with zero setup required.\n");
        securityStatus.setTextSize(14);
        securityStatus.setTextColor(Color.rgb(0, 120, 0));
        layout.addView(securityStatus);

        scrollView.addView(layout);
        setContentView(scrollView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccessibilityButton();
        updateNotificationButton();
        updateBatteryButton();
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

    private void updateAccessibilityButton() {
        if (a11yBtn == null) return;
        if (isAccessibilityServiceEnabled()) {
            a11yBtn.setText("1. Turn ON Clipboard Sync\n✓ Already Allowed (Active)");
            a11yBtn.setEnabled(false);
        } else {
            a11yBtn.setText("1. Turn ON Clipboard Sync\n👉 Tap to Allow (Accessibility Settings)");
            a11yBtn.setEnabled(true);
        }
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

    private void updateNotificationButton() {
        if (notifBtn == null) return;
        if (isNotificationServiceEnabled()) {
            notifBtn.setText("2. Turn ON Notification & OTP Sync\n✓ Already Allowed (Active)");
            notifBtn.setEnabled(false);
        } else {
            notifBtn.setText("2. Turn ON Notification & OTP Sync\n👉 Tap to Allow (Device & App Notifications)");
            notifBtn.setEnabled(true);
        }
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

    private void updateBatteryButton() {
        if (batteryBtn == null) return;
        if (!isBatteryOptimized()) {
            batteryBtn.setText("3. Keep Running Always (Allow for Always)\n✓ Already Allowed (Unrestricted)");
            batteryBtn.setEnabled(false);
        } else {
            batteryBtn.setText("3. Keep Running Always (Allow for Always)\n👉 Tap to Allow Always (No Disconnects)");
            batteryBtn.setEnabled(true);
        }
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
