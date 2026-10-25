package com.clipsync;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class CryptoUtils {
    private static final String PREF_NAME = "clipsync_crypto";
    private static final String KEY_PIN = "pairing_pin";
    public static final String DEFAULT_PIN = "ClipSync-Auto-Key-v1";
    private static final String SALT = "ClipSync-Salt-v1";
    private static final int KDF_ROUNDS = 50000;

    public static byte[] deriveKey(String pin) {
        if (pin == null || pin.trim().isEmpty()) return null;
        try {
            byte[] salt = SALT.getBytes(StandardCharsets.UTF_8);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            byte[] res = mac.doFinal(pin.trim().getBytes(StandardCharsets.UTF_8));
            for (int i = 1; i < KDF_ROUNDS; i++) {
                res = mac.doFinal(res);
            }
            return res;
        } catch (Exception e) {
            return null;
        }
    }

    public static String getSavedPIN(Context ctx) {
        if (ctx == null) return "";
        SharedPreferences prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_PIN, "");
    }

    public static void savePIN(Context ctx, String pin) {
        if (ctx == null) return;
        SharedPreferences prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_PIN, pin != null ? pin.trim() : "").apply();
    }

    public static byte[] getSecretKey(Context ctx) {
        String pin = getSavedPIN(ctx);
        if (pin.isEmpty()) {
            pin = DEFAULT_PIN;
        }
        return deriveKey(pin);
    }

    public static String encrypt(String plaintext, byte[] key) {
        if (key == null || key.length != 32 || plaintext == null) return null;
        try {
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    public static String decrypt(String base64Payload, byte[] key) {
        if (key == null || key.length != 32 || base64Payload == null) return null;
        try {
            byte[] combined = Base64.decode(base64Payload, Base64.DEFAULT);
            if (combined.length < 28) return null; // 12 IV + 16 Tag min

            byte[] iv = new byte[12];
            System.arraycopy(combined, 0, iv, 0, 12);

            byte[] ct = new byte[combined.length - 12];
            System.arraycopy(combined, 12, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] pt = cipher.doFinal(ct);

            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static String generateAuthToken(String filename, long timestamp, byte[] key) {
        if (key == null || key.length != 32) return "";
        try {
            String message = filename + ":" + timestamp;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] hash = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean verifyAuthToken(String filename, String token, long timestamp, byte[] key) {
        if (key == null || token == null || token.isEmpty()) return false;
        long now = System.currentTimeMillis() / 1000L;
        if (timestamp < now - 900 || timestamp > now + 900) {
            return false;
        }
        String expected = generateAuthToken(filename, timestamp, key);
        if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        if (filename != null) {
            int slash = filename.lastIndexOf('/');
            if (slash >= 0 && slash < filename.length() - 1) {
                String base = filename.substring(slash + 1);
                String expBase = generateAuthToken(base, timestamp, key);
                return MessageDigest.isEqual(expBase.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
            }
        }
        return false;
    }

    public static boolean isLocalIp(String ip) {
        if (ip == null || ip.trim().isEmpty()) return true;
        String clean = ip.trim().replaceFirst("^::ffff:", "");
        if (clean.equals("127.0.0.1") || clean.equals("::1") || clean.equalsIgnoreCase("localhost") || clean.equals("0.0.0.0")) {
            return true;
        }
        try {
            InetAddress addr = InetAddress.getByName(clean);
            if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()) {
                return true;
            }
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress ifaceAddr : Collections.list(iface.getInetAddresses())) {
                    String host = ifaceAddr.getHostAddress();
                    if (host != null) {
                        host = host.replaceFirst("^::ffff:", "");
                        int pct = host.indexOf("%");
                        if (pct >= 0) host = host.substring(0, pct);
                        if (host.equalsIgnoreCase(clean)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static List<InetAddress> getBroadcastAddresses() {
        List<InetAddress> broadcastList = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress bcast = ia.getBroadcast();
                    if (bcast != null && !broadcastList.contains(bcast)) {
                        broadcastList.add(bcast);
                    }
                }
            }
        } catch (Exception ignored) {}
        try {
            InetAddress global = InetAddress.getByName("255.255.255.255");
            if (!broadcastList.contains(global)) {
                broadcastList.add(global);
            }
        } catch (Exception ignored) {}
        return broadcastList;
    }

    public static Set<String> getKnownPeerIps(Context ctx) {
        Set<String> result = new HashSet<>();
        if (ctx == null) return result;
        try {
            SharedPreferences prefs = ctx.getSharedPreferences("clipsync_prefs", Context.MODE_PRIVATE);
            String lastIp = prefs.getString("last_peer_ip", null);
            if (lastIp != null && !lastIp.isEmpty() && !isLocalIp(lastIp)) {
                result.add(lastIp.trim());
            }
            Set<String> set = prefs.getStringSet("known_peer_ips", null);
            if (set != null) {
                for (String ip : set) {
                    if (ip != null && !ip.isEmpty() && !isLocalIp(ip)) {
                        result.add(ip.trim());
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    public static void addKnownPeerIp(Context ctx, String ip) {
        if (ctx == null || ip == null || ip.isEmpty() || isLocalIp(ip)) return;
        try {
            String clean = ip.trim().replaceFirst("^::ffff:", "");
            SharedPreferences prefs = ctx.getSharedPreferences("clipsync_prefs", Context.MODE_PRIVATE);
            Set<String> set = new HashSet<>(getKnownPeerIps(ctx));
            set.add(clean);
            prefs.edit()
                 .putString("last_peer_ip", clean)
                 .putStringSet("known_peer_ips", set)
                 .apply();
        } catch (Exception ignored) {}
    }
}
