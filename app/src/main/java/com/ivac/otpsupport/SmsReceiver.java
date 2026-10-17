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

        // শুধুমাত্র IVAC মেসেজ ফিল্টার (যাতে অন্য কোনো ওটিপি সার্ভারে না যায়)
        String upperSender = sender.toUpperCase(Locale.ROOT);
        String upperBody = messageBody.toUpperCase(Locale.ROOT);
        if (!upperSender.contains("IVAC") && !upperBody.contains("IVAC")) {
            return;
        }

        String extractedOtp = extractOtpFromText(messageBody);
        String finalMessageToSend = extractedOtp.isEmpty() ? messageBody : extractedOtp;

        // 🎯 ডুয়াল সিম ১০০% সেপারেট করার সুপার লজিক
        int slotIndex = -1;
        if (bundle.containsKey("android.telephony.extra.SLOT_INDEX")) {
            slotIndex = bundle.getInt("android.telephony.extra.SLOT_INDEX", -1);
        } else if (bundle.containsKey("slot")) {
            slotIndex = bundle.getInt("slot", -1);
        } else if (bundle.containsKey("simSlot")) {
            slotIndex = bundle.getInt("simSlot", -1);
        } else if (bundle.containsKey("phone")) {
            slotIndex = bundle.getInt("phone", -1);
        } else if (bundle.containsKey("subscription")) {
            slotIndex = bundle.getInt("subscription", -1);
        }

        String sim1 = prefs.getString("sim1_number", "").trim();
        String sim2 = prefs.getString("sim2_number", "").trim();

        // ডিফল্ট হিসেবে টার্গেট ডিভাইস সিম ১-ই থাকবে, তবে স্লট ইনডেক্স ১ হলে (মানে ২য় সিম) টার্গেট ডিভাইস সিম ২ হবে।
        String targetDevice = sim1;
        if (slotIndex == 1 && !sim2.isEmpty()) {
            targetDevice = sim2;
        } else if (targetDevice.isEmpty() && !sim2.isEmpty()) {
            targetDevice = sim2; // যদি সিম ১ সেট করা না থাকে, কিন্তু সিম ২ থাকে
        }

        final String finalDevice = targetDevice.isEmpty() ? "default" : targetDevice;
        final String displayMsg = extractedOtp.isEmpty() ? messageBody : "OTP: " + extractedOtp + " (" + messageBody + ")";

        // 🚀 WakeLock দিয়ে ফোনকে জাগিয়ে রেখে সাথে সাথে রিকোয়েস্ট পাঠানো
        final PendingResult pendingResult = goAsync();
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wakeLock = pm != null ? pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IVAC:OtpWakeLock") : null;
        if (wakeLock != null) wakeLock.acquire(10000L); // ১০ সেকেন্ডের জন্য লক

        new Thread(() -> {
            try {
                // ⚡ কোনো ডিলে ছাড়াই সরাসরি ফাস্ট রিকোয়েস্ট
                boolean sent = sendToServerSuperFast(finalDevice, finalMessageToSend);
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
            boolean sent = sendToServerSuperFast(device, "TEST_CONNECTION_OK");
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

    // 🚀 ডিলে বাদ দিয়ে ফাস্ট নেটওয়ার্ক রিকোয়েস্ট
    private static boolean sendToServerSuperFast(String device, String message) {
        for (int attempt = 1; attempt <= 2; attempt++) { // সর্বোচ্চ ২ বার চেষ্টা করবে
            HttpURLConnection conn = null;
            try {
                String query = "device=" + URLEncoder.encode(device, "UTF-8") +
                               "&message=" + URLEncoder.encode(message, "UTF-8");
                URL url = new URL(API_URL + "?" + query);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(4000); // ⚡ কানেকশন স্পিড ৮ সেকেন্ড থেকে কমিয়ে ৪ সেকেন্ড করা হলো
                conn.setReadTimeout(4000);
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
                if (attempt == 1) Thread.sleep(300); // ⚡ ১.৫ সেকেন্ডের ডিলে কমিয়ে মাত্র ৩০০ মিলিসেকেন্ড করা হলো
            } catch (InterruptedException ignored) {}
        }
        return false;
    }
}
