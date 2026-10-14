package com.clipsync;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 60, 60, 60);

        TextView title = new TextView(this);
        title.setText("ClipSync");
        title.setTextSize(26);
        layout.addView(title);

        TextView desc = new TextView(this);
        desc.setText("\nSeamless local continuity with your Linux PC.\nTo enable features, please grant the permissions below:\n");
        desc.setTextSize(15);
        layout.addView(desc);

        Button a11yBtn = new Button(this);
        a11yBtn.setText("1. Turn ON Clipboard Sync\n(Accessibility Settings)");
        a11yBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });
        layout.addView(a11yBtn);

        TextView spacer = new TextView(this);
        spacer.setText("\n");
        layout.addView(spacer);

        Button notifBtn = new Button(this);
        notifBtn.setText("2. Turn ON Notification & OTP Sync\n(Device & App Notifications)");
        notifBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        });
        layout.addView(notifBtn);

        setContentView(layout);
    }
}
