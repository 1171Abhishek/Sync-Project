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
        layout.setPadding(50, 50, 50, 50);

        TextView title = new TextView(this);
        title.setText("ClipSync");
        title.setTextSize(24);
        layout.addView(title);

        TextView desc = new TextView(this);
        desc.setText("\nTo enable automatic background sync with your Linux PC, please turn ON ClipSync in Accessibility Settings.\n");
        desc.setTextSize(16);
        layout.addView(desc);

        Button btn = new Button(this);
        btn.setText("Open Accessibility Settings");
        btn.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });
        layout.addView(btn);

        setContentView(layout);
    }
}
