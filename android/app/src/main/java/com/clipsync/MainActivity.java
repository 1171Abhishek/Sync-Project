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
import android.widget.TextView;

public class MainActivity extends Activity {
    private Button batteryBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 60, 60, 60);

        TextView title = new TextView(this);
        title.setText("ClipSync");
        title.setTextSize(26);
        title.setTextColor(Color.BLACK);
        layout.addView(title);

        TextView desc = new TextView(this);
        desc.setText("\nSeamless local continuity with your Linux PC.\nTo keep ClipSync connected 24/7 without disconnecting, please grant permissions below:\n");
        desc.setTextSize(15);
        layout.addView(desc);

        Button a11yBtn = new Button(this);
        a11yBtn.setText("1. Turn ON Clipboard Sync\n(Accessibility Settings)");
        a11yBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });
        layout.addView(a11yBtn);

        TextView spacer1 = new TextView(this);
        spacer1.setText("\n");
        layout.addView(spacer1);

        Button notifBtn = new Button(this);
        notifBtn.setText("2. Turn ON Notification & OTP Sync\n(Device & App Notifications)");
        notifBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        });
        layout.addView(notifBtn);

        TextView spacer2 = new TextView(this);
        spacer2.setText("\n");
        layout.addView(spacer2);

        batteryBtn = new Button(this);
        updateBatteryButton();
        batteryBtn.setOnClickListener(v -> {
            requestIgnoreBatteryOptimization();
        });
        layout.addView(batteryBtn);

        setContentView(layout);
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
