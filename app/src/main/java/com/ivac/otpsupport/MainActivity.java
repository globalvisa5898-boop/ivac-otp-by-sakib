package com.ivac.otpsupport;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    EditText etSim1, etSim2;
    Button btnSaveSim1, btnSaveSim2, btnToggleActive, btnTestServer, btnClearLogs;
    TextView tvStatus, tvLogTitle, tvLogs;
    SharedPreferences prefs;

    private final BroadcastReceiver logUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadLogs();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etSim1 = findViewById(R.id.etSim1);
        etSim2 = findViewById(R.id.etSim2);
        btnSaveSim1 = findViewById(R.id.btnSaveSim1);
        btnSaveSim2 = findViewById(R.id.btnSaveSim2);
        btnToggleActive = findViewById(R.id.btnToggleActive);
        btnTestServer = findViewById(R.id.btnTestServer);
        btnClearLogs = findViewById(R.id.btnClearLogs);
        tvStatus = findViewById(R.id.tvStatus);
        tvLogTitle = findViewById(R.id.tvLogTitle);
        tvLogs = findViewById(R.id.tvLogs);

        prefs = getSharedPreferences("IVAC_OTP_PREFS", MODE_PRIVATE);

        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS,
                    Manifest.permission.READ_PHONE_STATE
            }, 101);
        }

        requestBatteryOptimizationBypass();

        etSim1.setText(prefs.getString("sim1_number", ""));
        etSim2.setText(prefs.getString("sim2_number", ""));
        updateStatusUI(prefs.getBoolean("is_active", true));
        loadLogs();

        btnSaveSim1.setOnClickListener(v -> {
            prefs.edit().putString("sim1_number", etSim1.getText().toString().trim()).apply();
            Toast.makeText(this, "SIM1 Number Saved!", Toast.LENGTH_SHORT).show();
        });

        btnSaveSim2.setOnClickListener(v -> {
            prefs.edit().putString("sim2_number", etSim2.getText().toString().trim()).apply();
            Toast.makeText(this, "SIM2 Number Saved!", Toast.LENGTH_SHORT).show();
        });

        btnToggleActive.setOnClickListener(v -> {
            boolean current = prefs.getBoolean("is_active", true);
            boolean nextState = !current;
            prefs.edit().putBoolean("is_active", nextState).apply();
            updateStatusUI(nextState);
        });

        btnTestServer.setOnClickListener(v -> {
            String sim1 = prefs.getString("sim1_number", "").trim();
            String sim2 = prefs.getString("sim2_number", "").trim();
            String testDevice = !sim1.isEmpty() ? sim1 : (!sim2.isEmpty() ? sim2 : "Test-Phone");
            Toast.makeText(this, "Testing connection for " + testDevice + "...", Toast.LENGTH_SHORT).show();
            SmsReceiver.triggerManualTest(getApplicationContext(), testDevice);
        });

        btnClearLogs.setOnClickListener(v -> {
            prefs.edit().putString("sms_logs", "").putInt("log_count", 0).apply();
            loadLogs();
            Toast.makeText(this, "Logs Cleared!", Toast.LENGTH_SHORT).show();
        });
    }

    private void requestBatteryOptimizationBypass() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        } catch (Exception ignored) {}
    }

    private void updateStatusUI(boolean isActive) {
        if (isActive) {
            tvStatus.setText("● Online (Active in Background)");
            tvStatus.setTextColor(0xFF4CAF50);
            btnToggleActive.setText("DEACTIVATE");
            btnToggleActive.setBackgroundTintList(ColorStateList.valueOf(0xFF4CAF50));
        } else {
            tvStatus.setText("● Offline");
            tvStatus.setTextColor(0xFFF44336);
            btnToggleActive.setText("ACTIVATE");
            btnToggleActive.setBackgroundTintList(ColorStateList.valueOf(0xFFF44336));
        }
    }

    private void loadLogs() {
        int count = prefs.getInt("log_count", 0);
        String logs = prefs.getString("sms_logs", "");
        tvLogTitle.setText("IVAC SMS Logs (" + count + ")");
        tvLogs.setText(logs);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter("IVAC_LOG_UPDATED");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(logUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logUpdateReceiver, filter);
        }
        loadLogs();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(logUpdateReceiver);
        } catch (Exception ignored) {}
    }
}
