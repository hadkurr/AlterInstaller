/*
 * SPDX-FileCopyrightText: 2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.alterinstaller;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String CONFIG_PATH = "/data/local/tmp/AlterInstaller.json";
    private static final String PACKAGES_XML_PATH = "/data/system/packages.xml";
    private static final String DEFAULT_CONFIG = "{\n" +
            "    \"com.example.app\": {\n" +
            "        \"installer\": \"com.android.vending\",\n" +
            "        \"updateOwner\": \"com.android.vending\"\n" +
            "    }\n" +
            "}";

    private EditText editConfig;
    private TextView textLog;
    private ScrollView scrollLog;
    private Button btnSaveConfig;
    private Button btnApplyNow;
    private Button btnLoadConfig;
    private Button btnClearLog;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        editConfig = findViewById(R.id.edit_config);
        textLog = findViewById(R.id.text_log);
        scrollLog = findViewById(R.id.scroll_log);
        btnSaveConfig = findViewById(R.id.btn_save_config);
        btnApplyNow = findViewById(R.id.btn_apply_now);
        btnLoadConfig = findViewById(R.id.btn_load_config);
        btnClearLog = findViewById(R.id.btn_clear_log);

        btnSaveConfig.setOnClickListener(v -> saveConfig());
        btnApplyNow.setOnClickListener(v -> applyNow());
        btnLoadConfig.setOnClickListener(v -> loadConfig());
        btnClearLog.setOnClickListener(v -> textLog.setText(""));

        loadConfig();
        checkRootAccess();
    }

    private void setButtonsEnabled(boolean enabled) {
        btnSaveConfig.setEnabled(enabled);
        btnApplyNow.setEnabled(enabled);
        btnLoadConfig.setEnabled(enabled);
    }

    private void appendLog(String message) {
        mainHandler.post(() -> {
            textLog.append(message + "\n");
            scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void checkRootAccess() {
        executor.execute(() -> {
            appendLog("Checking root access...");
            try {
                Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
                String output = readStream(process.getInputStream());
                int exitCode = process.waitFor();
                if (exitCode == 0 && output.contains("uid=0")) {
                    appendLog("Root access: GRANTED");
                } else {
                    appendLog("Root access: DENIED — Apply will not work without root.");
                }
            } catch (Exception e) {
                appendLog("Root access: ERROR — " + e.getMessage());
            }
        });
    }

    private void loadConfig() {
        executor.execute(() -> {
            appendLog("Loading config from " + CONFIG_PATH + " ...");
            try {
                Process process = Runtime.getRuntime().exec(
                        new String[]{"su", "-c", "cat " + CONFIG_PATH});
                String content = readStream(process.getInputStream());
                int exitCode = process.waitFor();

                if (exitCode == 0 && !content.trim().isEmpty()) {
                    final String finalContent = content;
                    mainHandler.post(() -> editConfig.setText(finalContent));
                    appendLog("Config loaded successfully.");
                } else {
                    mainHandler.post(() -> editConfig.setText(DEFAULT_CONFIG));
                    appendLog("Config file not found. Showing default template.");
                }
            } catch (Exception e) {
                mainHandler.post(() -> editConfig.setText(DEFAULT_CONFIG));
                appendLog("Failed to load config: " + e.getMessage());
            }
        });
    }

    private void saveConfig() {
        String json = editConfig.getText().toString().trim();
        if (json.isEmpty()) {
            Toast.makeText(this, "Config is empty", Toast.LENGTH_SHORT).show();
            return;
        }

        setButtonsEnabled(false);
        executor.execute(() -> {
            appendLog("Saving config to " + CONFIG_PATH + " ...");
            try {
                File tempFile = File.createTempFile("AlterInstaller", ".json", getCacheDir());
                try (FileWriter fw = new FileWriter(tempFile)) {
                    fw.write(json);
                }

                String cmd = "cp " + tempFile.getAbsolutePath() + " " + CONFIG_PATH +
                        " && chmod 644 " + CONFIG_PATH;
                Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                String stderr = readStream(process.getErrorStream());
                int exitCode = process.waitFor();

                tempFile.delete();

                if (exitCode == 0) {
                    appendLog("Config saved to " + CONFIG_PATH);
                } else {
                    appendLog("Failed to save config (exit " + exitCode + "): " + stderr);
                }
            } catch (Exception e) {
                appendLog("Error saving config: " + e.getMessage());
            } finally {
                mainHandler.post(() -> setButtonsEnabled(true));
            }
        });
    }

    private void applyNow() {
        String json = editConfig.getText().toString().trim();
        if (json.isEmpty()) {
            Toast.makeText(this, "Config is empty", Toast.LENGTH_SHORT).show();
            return;
        }

        setButtonsEnabled(false);
        executor.execute(() -> {
            appendLog("--- Starting Apply ---");

            try {
                // Step 1: Write config to /data/local/tmp/AlterInstaller.json
                appendLog("Step 1: Writing config to " + CONFIG_PATH);
                File tempFile = File.createTempFile("AlterInstaller", ".json", getCacheDir());
                try (FileWriter fw = new FileWriter(tempFile)) {
                    fw.write(json);
                }

                String saveCmd = "cp " + tempFile.getAbsolutePath() + " " + CONFIG_PATH +
                        " && chmod 644 " + CONFIG_PATH;
                Process saveProcess = Runtime.getRuntime().exec(new String[]{"su", "-c", saveCmd});
                String saveErr = readStream(saveProcess.getErrorStream());
                int saveExit = saveProcess.waitFor();
                tempFile.delete();

                if (saveExit != 0) {
                    appendLog("Failed to save config: " + saveErr);
                    mainHandler.post(() -> setButtonsEnabled(true));
                    return;
                }
                appendLog("Config written successfully.");

                // Step 2: Get APK path
                appendLog("Step 2: Getting APK path...");
                Process pmProcess = Runtime.getRuntime().exec(
                        new String[]{"su", "-c", "pm path " + getPackageName()});
                String pmOut = readStream(pmProcess.getInputStream()).trim();
                pmProcess.waitFor();

                String apkPath = "";
                if (pmOut.startsWith("package:")) {
                    apkPath = pmOut.substring("package:".length()).trim();
                }

                if (apkPath.isEmpty()) {
                    appendLog("Could not determine APK path. Falling back to installed path.");
                    apkPath = getPackageCodePath();
                }
                appendLog("APK path: " + apkPath);

                // Step 3: Invoke Main.apply via app_process with root
                appendLog("Step 3: Applying changes to " + PACKAGES_XML_PATH);
                String applyCmd = "CLASSPATH=" + apkPath + " app_process / " +
                        "com.chiller3.alterinstaller.Main apply " +
                        CONFIG_PATH + " " +
                        PACKAGES_XML_PATH + " " +
                        PACKAGES_XML_PATH;

                appendLog("Running: su -c \"" + applyCmd + "\"");
                Process applyProcess = Runtime.getRuntime().exec(new String[]{"su", "-c", applyCmd});

                String stdout = readStream(applyProcess.getInputStream());
                String stderr = readStream(applyProcess.getErrorStream());
                int applyExit = applyProcess.waitFor();

                if (!stdout.trim().isEmpty()) {
                    appendLog("stdout: " + stdout.trim());
                }
                if (!stderr.trim().isEmpty()) {
                    appendLog("logcat/stderr: " + stderr.trim());
                }

                if (applyExit == 0) {
                    appendLog("Apply completed successfully!");
                    appendLog("Note: Changes to packages.xml take full effect after reboot.");
                    mainHandler.post(() ->
                            Toast.makeText(MainActivity.this,
                                    "Applied! Reboot for full effect.", Toast.LENGTH_LONG).show());
                } else {
                    appendLog("Apply failed (exit code " + applyExit + ").");
                }

            } catch (Exception e) {
                appendLog("Error during apply: " + e.getMessage());
            } finally {
                appendLog("--- Apply Finished ---");
                mainHandler.post(() -> setButtonsEnabled(true));
            }
        });
    }

    private String readStream(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
