package com.ivac.otpsupport;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.PowerManager;
import android.telephony.SmsMessage;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SmsReceiver extends BroadcastReceiver {

    private static final String API_URL = "https://script.google.com/macros/s/AKfycbzvVVH198hLFoqU5RHBFfpha6AwxErbFWjGaeoTG8QfMApv6XfJZt24WaNRZE8eoO15/exec";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;

        SharedPreferences prefs = context.getSharedPreferences("IVAC_OTP_PREFS", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("is_active", true)) return;

        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        Object[] pdus = (Object[]) bundle.get("pdus");
        if (pdus == null || pdus.length == 0) return;

        String format = bundle.getString("format");
        StringBuilder fullMessage = new StringBuilder();
        String sender = "";

        for (Object pdu : pdus) {
            SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu, format);
            if (sms != null) {
                if (sms.getOriginatingAddress() != null && sender.isEmpty()) {
                    sender = sms.getOriginatingAddress();
                }
                if (sms.getMessageBody() != null) {
                    fullMessage.append(sms.getMessageBody());
                }
            }
        }

        String messageBody = fullMessage.toString();
        if (messageBody.isEmpty()) return;

        // শুধুমাত্র IVAC মেসেজ ফিল্টার
        String upperSender = sender.toUpperCase(Locale.ROOT);
        String upperBody = messageBody.toUpperCase(Locale.ROOT);
        if (!upperSender.contains("IVAC") && !upperBody.contains("IVAC")) {
            return;
        }

        String extractedOtp = extractOtpFromText(messageBody);
        String finalMessageToSend = extractedOtp.isEmpty() ? messageBody : extractedOtp;

        int slot = bundle.getInt("slot", -1);
        if (slot == -1) slot = bundle.getInt("simSlot", -1);
        if (slot == -1) slot = bundle.getInt("android.telephony.extra.SLOT_INDEX", -1);
        if (slot == -1) slot = bundle.getInt("phone", -1);

        String sim1 = prefs.getString("sim1_number", "").trim();
        String sim2 = prefs.getString("sim2_number", "").trim();

        String targetDevice = sim1;
        if (slot == 1 && !sim2.isEmpty()) {
            targetDevice = sim2;
        } else if (targetDevice.isEmpty() && !sim2.isEmpty()) {
            targetDevice = sim2;
        }

        final String finalDevice = targetDevice.isEmpty() ? "default" : targetDevice;
        final String displayMsg = extractedOtp.isEmpty() ? messageBody : "OTP: " + extractedOtp + " (" + messageBody + ")";

        final PendingResult pendingResult = goAsync();
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wakeLock = pm != null ? pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IVAC:OtpWakeLock") : null;
        if (wakeLock != null) wakeLock.acquire(30000L);

        new Thread(() -> {
            try {
                boolean sent = sendToServerWithRetry(finalDevice, finalMessageToSend);
                String statusTag = sent ? "[✅ Sent to PC]" : "[❌ Net Error]";
                appendLog(context, finalDevice, statusTag + " " + displayMsg);
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                pendingResult.finish();
            }
        }).start();
    }

    public static void triggerManualTest(Context context, String device) {
        new Thread(() -> {
            boolean sent = sendToServerWithRetry(device, "TEST_CONNECTION_OK");
            String statusTag = sent ? "[✅ Server Connected!]" : "[❌ Server Failed!]";
            appendLog(context, device, statusTag + " Manual Test Ping");
        }).start();
    }

    private static synchronized void appendLog(Context context, String device, String logText) {
        SharedPreferences prefs = context.getSharedPreferences("IVAC_OTP_PREFS", Context.MODE_PRIVATE);
        String time = new SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(new Date());
        String newLog = "[" + time + "] (" + device + "):\n" + logText + "\n\n";
        String oldLogs = prefs.getString("sms_logs", "");
        int count = prefs.getInt("log_count", 0) + 1;
        prefs.edit().putString("sms_logs", newLog + oldLogs).putInt("log_count", count).apply();

        Intent updateIntent = new Intent("IVAC_LOG_UPDATED");
        updateIntent.setPackage(context.getPackageName());
        context.sendBroadcast(updateIntent);
    }

    private String extractOtpFromText(String msg) {
        Matcher digitMatcher = Pattern.compile("\\b\\d{4,6}\\b").matcher(msg);
        if (digitMatcher.find()) {
            return digitMatcher.group(0);
        }

        String cleanMsg = msg.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        Matcher wordMatcher = Pattern.compile("(zero|one|two|three|four|five|six|seven|eight|nine)").matcher(cleanMsg);
        StringBuilder otp = new StringBuilder();

        while (wordMatcher.find()) {
            switch (wordMatcher.group(1)) {
                case "zero": otp.append("0"); break;
                case "one": otp.append("1"); break;
                case "two": otp.append("2"); break;
                case "three": otp.append("3"); break;
                case "four": otp.append("4"); break;
                case "five": otp.append("5"); break;
                case "six": otp.append("6"); break;
                case "seven": otp.append("7"); break;
                case "eight": otp.append("8"); break;
                case "nine": otp.append("9"); break;
            }
        }

        if (otp.length() >= 4) {
            return otp.substring(0, Math.min(6, otp.length()));
        }
        return "";
    }

    private static boolean sendToServerWithRetry(String device, String message) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            HttpURLConnection conn = null;
            try {
                String query = "device=" + URLEncoder.encode(device, "UTF-8") +
                               "&message=" + URLEncoder.encode(message, "UTF-8");
                URL url = new URL(API_URL + "?" + query);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                OutputStream os = conn.getOutputStream();
                os.write(query.getBytes("UTF-8"));
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                if (code >= 200 && code < 400) {
                    return true;
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (conn != null) conn.disconnect();
            }
            try {
                Thread.sleep(1500); // নেট সমস্যা হলে ১.৫ সেকেন্ড পর আবার চেষ্টা করবে
            } catch (InterruptedException ignored) {}
        }
        return false;
    }
}
