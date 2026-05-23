package com.example.voiceinputapp;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.view.inputmethod.InputMethodManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "VoiceInputApp";

    private TextView tvStatus;
    private EditText etResult;
    private Button btnStart;
    private Button btnStop;
    private Button btnCopy;
    private Button btnClear;
    private Button btnEnableIme;
    private Button btnSwitchIme;

    private final PcmRecorder pcmRecorder = new PcmRecorder();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private boolean isProcessing = false;

    private final ActivityResultLauncher<String> audioPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Log.d(TAG, "RECORD_AUDIO permission granted");
                    startVoiceInput();
                } else {
                    Log.w(TAG, "Microphone permission denied by user");
                    updateStatus(getString(R.string.status_permission_denied));
                    showToast(getString(R.string.toast_permission_required));
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        bindActions();
        updateStatus(hasBaiduConfig()
                ? getString(R.string.status_idle)
                : getString(R.string.status_config_missing));
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tvStatus);
        etResult = findViewById(R.id.etResult);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnCopy = findViewById(R.id.btnCopy);
        btnClear = findViewById(R.id.btnClear);
        btnEnableIme = findViewById(R.id.btnEnableIme);
        btnSwitchIme = findViewById(R.id.btnSwitchIme);

        refreshButtons();
    }

    private void bindActions() {
        btnStart.setOnClickListener(v -> checkPermissionAndStart());
        btnStop.setOnClickListener(v -> stopVoiceInput());
        btnCopy.setOnClickListener(v -> copyText());
        btnClear.setOnClickListener(v -> clearText());
        btnEnableIme.setOnClickListener(v -> openImeSettings());
        btnSwitchIme.setOnClickListener(v -> showImePicker());
    }

    private void checkPermissionAndStart() {
        if (!hasBaiduConfig()) {
            updateStatus(getString(R.string.status_config_missing));
            showToast(getString(R.string.error_missing_config));
            return;
        }

        if (isProcessing) {
            updateStatus(getString(R.string.error_busy));
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startVoiceInput();
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void startVoiceInput() {
        try {
            updateStatus(getString(R.string.status_starting));
            pcmRecorder.start();
            updateStatus(getString(R.string.status_listening));
            refreshButtons();
        } catch (Exception exception) {
            Log.e(TAG, "Failed to start recorder", exception);
            updateStatus(getString(R.string.status_not_supported));
            showToast(getString(R.string.toast_not_supported));
            refreshButtons();
        }
    }

    private void stopVoiceInput() {
        if (!pcmRecorder.isRecording()) {
            updateStatus(getString(R.string.status_idle));
            refreshButtons();
            return;
        }

        updateStatus(getString(R.string.status_stopping));
        byte[] audioBytes = pcmRecorder.stop();
        refreshButtons();

        if (audioBytes.length == 0) {
            updateStatus(getString(R.string.error_audio));
            return;
        }

        isProcessing = true;
        refreshButtons();
        updateStatus(getString(R.string.status_processing));
        executorService.execute(() -> recognizeAudio(audioBytes));
    }

    private void recognizeAudio(byte[] audioBytes) {
        try {
            BaiduSpeechClient speechClient = new BaiduSpeechClient(
                    BuildConfig.BAIDU_API_KEY,
                    BuildConfig.BAIDU_SECRET_KEY,
                    getPackageName()
            );
            String result = speechClient.recognize(audioBytes, PcmRecorder.SAMPLE_RATE);
            runOnUiThread(() -> handleRecognitionSuccess(result));
        } catch (Exception exception) {
            Log.e(TAG, "Baidu ASR request failed", exception);
            runOnUiThread(() -> handleRecognitionError(exception));
        }
    }

    private void handleRecognitionSuccess(String result) {
        isProcessing = false;
        refreshButtons();
        if (TextUtils.isEmpty(result)) {
            updateStatus(getString(R.string.status_no_result));
            return;
        }

        etResult.setText(result);
        etResult.setSelection(etResult.getText().length());
        updateStatus(getString(R.string.status_result_ready));
    }

    private void handleRecognitionError(Exception exception) {
        isProcessing = false;
        refreshButtons();

        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase();
        if (message.contains("network") || message.contains("timeout")) {
            updateStatus(getString(R.string.error_network));
        } else if (message.contains("speech quality error")
                || message.contains("empty audio")
                || message.contains("too short")) {
            updateStatus(getString(R.string.error_no_match));
        } else if (message.contains("access_token")
                || message.contains("api key")
                || message.contains("secret")) {
            updateStatus(getString(R.string.status_config_missing));
        } else {
            updateStatus(getString(R.string.error_unknown, exception.getMessage()));
        }
    }

    private void copyText() {
        String text = etResult.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            showToast(getString(R.string.toast_nothing_to_copy));
            return;
        }

        ClipboardManager clipboardManager =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager != null) {
            ClipData clipData = ClipData.newPlainText(getString(R.string.copy_label), text);
            clipboardManager.setPrimaryClip(clipData);
            updateStatus(getString(R.string.status_copied));
            showToast(getString(R.string.toast_copied));
        }
    }

    private void clearText() {
        etResult.setText("");
        updateStatus(getString(R.string.status_cleared));
    }

    private void openImeSettings() {
        startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
        showToast(getString(R.string.toast_open_ime_settings));
    }

    private void showImePicker() {
        InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.showInputMethodPicker();
            updateStatus(getString(R.string.status_ime_guide));
        }
    }

    private void refreshButtons() {
        boolean isRecording = pcmRecorder.isRecording();
        btnStart.setEnabled(!isRecording && !isProcessing);
        btnStop.setEnabled(isRecording);
    }

    private boolean hasBaiduConfig() {
        return !BuildConfig.BAIDU_API_KEY.isEmpty() && !BuildConfig.BAIDU_SECRET_KEY.isEmpty();
    }

    private void updateStatus(String status) {
        tvStatus.setText(status);
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pcmRecorder.release();
        executorService.shutdownNow();
    }
}
