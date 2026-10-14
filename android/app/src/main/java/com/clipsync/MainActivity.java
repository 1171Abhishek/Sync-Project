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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private Button batteryBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 60, 60, 60);

        TextView title = new TextView(this);
        title.setText("ClipSync");
        title.setTextSize(26);
        title.setTextColor(Color.BLACK);
        layout.addView(title);

        TextView desc = new TextView(this);
        desc.setText("
Seamless local continuity with your Linux PC.
To enable features, please grant the permissions below:
");
        desc.setTextSize(15);
        layout.addView(desc);

        // 1. Accessibility
        Button a11yBtn = new Button(this);
        a11yBtn.setText("1. Turn ON Clipboard Sync
(Accessibility Settings)");
        a11yBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });
        layout.addView(a11yBtn);

        TextView spacer1 = new TextView(this);
        spacer1.setText("
");
        layout.addView(spacer1);

        // 2. Notification Access
        Button notifBtn = new Button(this);
        notifBtn.setText("2. Turn ON Notification & OTP Sync
(Device & App Notifications)");
        notifBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        });
        layout.addView(notifBtn);

        TextView spacer2 = new TextView(this);
        spacer2.setText("
");
        layout.addView(spacer2);

        // 3. Battery Optimization
        batteryBtn = new Button(this);
        updateBatteryButton();
        batteryBtn.setOnClickListener(v -> {
            requestIgnoreBatteryOptimization();
        });
        layout.addView(batteryBtn);

        TextView spacer3 = new TextView(this);
        spacer3.setText("
");
        layout.addView(spacer3);

        // 4. Automatic Security & Encryption Status
        TextView securityHeader = new TextView(this);
        securityHeader.setText("4. Security & Encryption");
        securityHeader.setTextSize(18);
        securityHeader.setTextColor(Color.BLACK);
        layout.addView(securityHeader);

        TextView securityStatus = new TextView(this);
        securityStatus.setText("🔒 100% Automatic End-to-End Encrypted (AES-256-GCM)
All clipboard, notifications, URLs, and file drops are automatically encrypted with zero setup required.
");
        securityStatus.setTextSize(14);
        securityStatus.setTextColor(Color.rgb(0, 120, 0));
        layout.addView(securityStatus);

        scrollView.addView(layout);
        setContentView(scrollView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateBatteryButton();
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
            batteryBtn.setText("3. Keep Running Always (Allow for Always)
✓ Already Allowed (Unrestricted)");
            batteryBtn.setEnabled(false);
        } else {
            batteryBtn.setText("3. Keep Running Always (Allow for Always)
👉 Tap to Allow Always (No Disconnects)");
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
