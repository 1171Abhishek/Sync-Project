package com.clipsync;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClipSyncNotificationService extends NotificationListenerService {

    private static final Pattern GOOGLE_PATTERN = Pattern.compile("(?i)\\bG-([0-9]{6})\\b");
    private static final Pattern NEARBY_PATTERN = Pattern.compile("(?i)(?:code|otp|pin|passcode|secret)[^0-9a-zA-Z]{0,10}(?:is\\s+)?([0-9]{4,8})\\b");
    private static final Pattern BEFORE_PATTERN = Pattern.compile("(?i)\\b([0-9]{4,8})\\b\\s+(?:is\\s+your\\s+)?(?:code|otp|verification|pin|passcode)");
    private static final Pattern GENERAL_DIGITS = Pattern.compile("\\b([0-9]{4,8})\\b");

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        // 1. Ignore ongoing sticky notifications (e.g. music playback, background downloads)
        if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
            return;
        }

        String packageName = sbn.getPackageName();
        if (packageName == null) return;

        // 2. Ignore self & internal system noise
        if ("com.clipsync".equals(packageName) || "android".equals(packageName) || "com.android.systemui".equals(packageName)) {
            return;
        }

        Bundle extras = notification.extras;
        if (extras == null) return;

        // Extract title
        String title = null;
        CharSequence titleChars = extras.getCharSequence(Notification.EXTRA_TITLE);
        if (titleChars != null) {
            title = titleChars.toString();
        }

        // Extract text / message content
        String text = null;
        CharSequence bigTextChars = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (bigTextChars != null) {
            text = bigTextChars.toString();
        } else {
            CharSequence textChars = extras.getCharSequence(Notification.EXTRA_TEXT);
            if (textChars != null) {
                text = textChars.toString();
            }
        }

        if ((title == null || title.trim().isEmpty()) && (text == null || text.trim().isEmpty())) {
            return;
        }

        // Friendly App Name (e.g. "Messages", "WhatsApp", "HDFC Bank")
        String appName = packageName;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            appName = pm.getApplicationLabel(appInfo).toString();
        } catch (Exception ignored) {}

        String fullContent = ((title != null ? title : "") + " " + (text != null ? text : "")).trim();
        String otp = extractOtpStatic(fullContent);

        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "notification");
            obj.put("app_name", appName);
            obj.put("title", title != null ? title : appName);
            obj.put("content", text != null ? text : "");
            if (otp != null && !otp.isEmpty()) {
                obj.put("otp", otp);
            }
            obj.put("timestamp", sbn.getPostTime());

            ClipSyncService.broadcastJson(obj);
        } catch (Exception ignored) {}
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {}

    public static String extractOtpStatic(String text) {
        if (text == null || text.trim().isEmpty()) return null;

        String lower = text.toLowerCase();
        boolean hasKeyword = lower.contains("otp") || lower.contains("code") ||
                lower.contains("verification") || lower.contains("passcode") ||
                lower.contains("one-time") || lower.contains("pin") ||
                lower.contains("secret") || lower.contains("verify") ||
                lower.contains("auth");

        if (!hasKeyword) return null;

        // 1. Google G-XXXXXX pattern
        Matcher mGoogle = GOOGLE_PATTERN.matcher(text);
        if (mGoogle.find()) {
            return mGoogle.group(1);
        }

        // 2. Keyword followed by number: e.g. "OTP: 123456"
        Matcher mNearby = NEARBY_PATTERN.matcher(text);
        if (mNearby.find()) {
            return mNearby.group(1);
        }

        // 3. Number followed by keyword: e.g. "123456 is your verification code"
        Matcher mBefore = BEFORE_PATTERN.matcher(text);
        if (mBefore.find()) {
            return mBefore.group(1);
        }

        // 4. Standalone 4-8 digits
        Matcher mDigits = GENERAL_DIGITS.matcher(text);
        while (mDigits.find()) {
            String val = mDigits.group(1);
            if (val != null) {
                if (val.length() == 4) {
                    try {
                        int num = Integer.parseInt(val);
                        if (num >= 2024 && num <= 2028) continue; // skip years
                    } catch (Exception ignored) {}
                }
                return val;
            }
        }

        return null;
    }
}
