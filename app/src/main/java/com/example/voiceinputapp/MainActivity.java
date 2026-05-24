package com.example.voiceinputapp;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "VoiceInputApp";

    private TextView tvStatus;
    private TextView tvPermissionStatus;
    private TextView tvImeEnabledStatus;
    private TextView tvImeSelectedStatus;
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
                    refreshSetupStatus();
                    startVoiceInput();
                } else {
                    Log.w(TAG, "Microphone permission denied by user");
                    updateStatus(getString(R.string.status_permission_denied));
                    showToast(getString(R.string.toast_permission_required));
                    refreshSetupStatus();
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
        refreshSetupStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSetupStatus();
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tvStatus);
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus);
        tvImeEnabledStatus = findViewById(R.id.tvImeEnabledStatus);
        tvImeSelectedStatus = findViewById(R.id.tvImeSelectedStatus);
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

        if (hasAudioPermission()) {
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

    private void refreshSetupStatus() {
        tvPermissionStatus.setText(getString(
                hasAudioPermission() ? R.string.setup_mic_granted : R.string.setup_mic_missing
        ));
        tvImeEnabledStatus.setText(getString(
                isOurImeEnabled() ? R.string.setup_ime_enabled : R.string.setup_ime_disabled
        ));
        tvImeSelectedStatus.setText(getString(
                isOurImeDefault() ? R.string.setup_ime_selected : R.string.setup_ime_not_selected
        ));
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isOurImeEnabled() {
        String enabledImeIds = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_INPUT_METHODS
        );
        if (TextUtils.isEmpty(enabledImeIds)) {
            return false;
        }

        String imeIdLong = getImeIdLong();
        String imeIdShort = getImeIdShort();
        String[] enabledItems = enabledImeIds.split(":");
        for (String item : enabledItems) {
            if (imeIdLong.equals(item) || imeIdShort.equals(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean isOurImeDefault() {
        String defaultImeId = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD
        );
        if (TextUtils.isEmpty(defaultImeId)) {
            return false;
        }
        return defaultImeId.equals(getImeIdLong()) || defaultImeId.equals(getImeIdShort());
    }

    private String getImeIdLong() {
        return new ComponentName(this, VoiceInputMethodService.class).flattenToString();
    }

    private String getImeIdShort() {
        return new ComponentName(this, VoiceInputMethodService.class).flattenToShortString();
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
