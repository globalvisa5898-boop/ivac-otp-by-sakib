package com.ivac.otpsupport;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.telephony.SmsMessage;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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
        for (Object pdu : pdus) {
            SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu, format);
            if (sms != null && sms.getMessageBody() != null) {
                fullMessage.append(sms.getMessageBody());
            }
        }

        String messageBody = fullMessage.toString();
        if (messageBody.isEmpty()) return;

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

        String time = new SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(new Date());
        String newLog = "[" + time + "] (" + (targetDevice.isEmpty() ? "No-Num" : targetDevice) + "): " + messageBody + "\n\n";
        String oldLogs = prefs.getString("sms_logs", "");
        int count = prefs.getInt("log_count", 0) + 1;
        prefs.edit().putString("sms_logs", newLog + oldLogs).putInt("log_count", count).apply();

        Intent updateIntent = new Intent("IVAC_LOG_UPDATED");
        updateIntent.setPackage(context.getPackageName());
        context.sendBroadcast(updateIntent);

        final String finalDevice = targetDevice.isEmpty() ? "default" : targetDevice;
        new Thread(() -> sendToServer(finalDevice, messageBody)).start();
    }

    private void sendToServer(String device, String message) {
        try {
            String query = "device=" + URLEncoder.encode(device, "UTF-8") +
                           "&message=" + URLEncoder.encode(message, "UTF-8");
            URL url = new URL(API_URL + "?" + query);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            OutputStream os = conn.getOutputStream();
            os.write(query.getBytes("UTF-8"));
            os.flush();
            os.close();

            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
