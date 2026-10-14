package com.clipsync;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Parcelable;
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

    private static volatile String lastNotifHash = "";
    private static volatile long lastNotifTime = 0;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        // 1. Ignore ongoing sticky notifications (e.g. music playback, background downloads, WhatsApp voice calls)
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
        if (titleChars != null && titleChars.length() > 0) {
            title = titleChars.toString().trim();
        }
        if (title == null || title.isEmpty()) {
            CharSequence bigTitle = extras.getCharSequence(Notification.EXTRA_TITLE_BIG);
            if (bigTitle != null && bigTitle.length() > 0) {
                title = bigTitle.toString().trim();
            }
        }
        if (title == null || title.isEmpty()) {
            CharSequence convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
            if (convTitle != null && convTitle.length() > 0) {
                title = convTitle.toString().trim();
            }
        }

        // Extract message text with full support for WhatsApp / MessagingStyle
        String text = null;

        // A. WhatsApp / Telegram / Signal / Google Messages: MessagingStyle (android.messages)
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null && messages.length > 0) {
            // Find the most recent message with non-empty text
            for (int i = messages.length - 1; i >= 0; i--) {
                Parcelable p = messages[i];
                if (p instanceof Bundle) {
                    Bundle b = (Bundle) p;
                    CharSequence msgText = b.getCharSequence("text");
                    if (msgText != null && msgText.length() > 0) {
                        text = msgText.toString().trim();

                        CharSequence sender = b.getCharSequence("sender");
                        if (sender != null && sender.length() > 0) {
                            String senderStr = sender.toString().trim();
                            if (title != null && !title.isEmpty() && !title.equalsIgnoreCase(senderStr)) {
                                title = senderStr + " in " + title;
                            } else {
                                title = senderStr;
                            }
                        }
                        break;
                    }
                }
            }
        }

        // B. InboxStyle lines (e.g. stacked notifications, multiple messages)
        if (text == null || text.isEmpty()) {
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null && lines.length > 0) {
                for (int i = lines.length - 1; i >= 0; i--) {
                    if (lines[i] != null && lines[i].length() > 0) {
                        text = lines[i].toString().trim();
                        break;
                    }
                }
            }
        }

        // C. BigTextStyle (e.g. emails, full SMS)
        if (text == null || text.isEmpty()) {
            CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (bigText != null && bigText.length() > 0) {
                text = bigText.toString().trim();
            }
        }

        // D. Standard Notification Text
        if (text == null || text.isEmpty()) {
            CharSequence textChars = extras.getCharSequence(Notification.EXTRA_TEXT);
            if (textChars != null && textChars.length() > 0) {
                text = textChars.toString().trim();
            }
        }

        // E. Fallback Ticker Text
        if (text == null || text.isEmpty()) {
            if (notification.tickerText != null && notification.tickerText.length() > 0) {
                text = notification.tickerText.toString().trim();
            }
        }

        // If both title and text are empty, ignore empty noise
        if ((title == null || title.isEmpty()) && (text == null || text.isEmpty())) {
            return;
        }

        // Friendly App Name (e.g. "WhatsApp", "Messages", "Gmail")
        String appName = packageName;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            appName = pm.getApplicationLabel(appInfo).toString();
        } catch (Exception ignored) {}

        String fullContent = ((title != null ? title : "") + " " + (text != null ? text : "")).trim();
        String otp = extractOtpStatic(fullContent);

        // Deduplicate rapid re-posts by OS / WhatsApp
        long now = System.currentTimeMillis();
        String currentHash = appName + ":" + title + ":" + text;
        if (currentHash.equals(lastNotifHash) && (now - lastNotifTime < 2500)) {
            return;
        }
        lastNotifHash = currentHash;
        lastNotifTime = now;

        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "notification");
            obj.put("app_name", appName);
            obj.put("title", (title != null && !title.isEmpty()) ? title : appName);
            obj.put("content", (text != null) ? text : "");
            if (otp != null && !otp.isEmpty()) {
                obj.put("otp", otp);
            }
            obj.put("timestamp", sbn.getPostTime() > 0 ? sbn.getPostTime() : now);

            // Broadcast directly to peers using static method independent of ClipSyncService instance
            ClipSyncService.broadcastJson(this, obj);
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
