package com.example.voiceinputapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.inputmethodservice.InputMethodService;

public class VoiceInputMethodService extends InputMethodService {

    private static final String TAG = "VoiceInputMethod";

    private final PcmRecorder pcmRecorder = new PcmRecorder();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView tvImeStatus;
    private Button btnImeToggle;
    private boolean isProcessing = false;

    @Override
    public View onCreateInputView() {
        View view = LayoutInflater.from(this).inflate(R.layout.input_view, null, false);
        tvImeStatus = view.findViewById(R.id.tvImeStatus);
        btnImeToggle = view.findViewById(R.id.btnImeToggle);
        btnImeToggle.setOnClickListener(v -> onToggleClicked());
        refreshUi();
        return view;
    }

    @Override
    public void onStartInputView(android.view.inputmethod.EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        refreshUi();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        if (pcmRecorder.isRecording()) {
            pcmRecorder.stop();
        }
        isProcessing = false;
        refreshUi();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        pcmRecorder.release();
        executorService.shutdownNow();
    }

    private void onToggleClicked() {
        if (!hasBaiduConfig()) {
            updateStatus(getString(R.string.status_config_missing));
            return;
        }

        if (isProcessing) {
            updateStatus(getString(R.string.error_busy));
            return;
        }

        if (pcmRecorder.isRecording()) {
            stopRecordingAndRecognize();
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        if (!hasRecordAudioPermission()) {
            updateStatus(getString(R.string.status_permission_denied));
            Toast.makeText(this, R.string.toast_grant_mic_for_ime, Toast.LENGTH_LONG).show();
            refreshUi();
            return;
        }

        try {
            pcmRecorder.start();
            updateStatus(getString(R.string.status_ime_recording));
            refreshUi();
        } catch (Exception exception) {
            Log.e(TAG, "Failed to start recorder from IME", exception);
            updateStatus(getString(R.string.status_not_supported));
            refreshUi();
        }
    }

    private void stopRecordingAndRecognize() {
        updateStatus(getString(R.string.status_ime_processing));
        byte[] audioBytes = pcmRecorder.stop();
        if (audioBytes.length == 0) {
            updateStatus(getString(R.string.error_audio));
            refreshUi();
            return;
        }

        isProcessing = true;
        refreshUi();
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
            mainHandler.post(() -> handleRecognitionSuccess(result));
        } catch (Exception exception) {
            Log.e(TAG, "IME recognition failed", exception);
            mainHandler.post(() -> handleRecognitionError(exception));
        }
    }

    private void handleRecognitionSuccess(String result) {
        isProcessing = false;
        if (TextUtils.isEmpty(result)) {
            updateStatus(getString(R.string.status_no_result));
            refreshUi();
            return;
        }

        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) {
            updateStatus(getString(R.string.status_ime_no_editor));
            refreshUi();
            return;
        }

        inputConnection.commitText(result, 1);
        updateStatus(getString(R.string.status_ime_committed));
        refreshUi();
    }

    private void handleRecognitionError(Exception exception) {
        isProcessing = false;

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
        refreshUi();
    }

    private void refreshUi() {
        if (btnImeToggle == null) {
            return;
        }

        boolean isRecording = pcmRecorder.isRecording();
        btnImeToggle.setEnabled(!isProcessing);
        btnImeToggle.setText(isRecording ? R.string.ime_action_stop : R.string.ime_action_start);

        if (tvImeStatus != null && !isRecording && !isProcessing && TextUtils.isEmpty(tvImeStatus.getText())) {
            tvImeStatus.setText(R.string.status_ime_idle);
        }
    }

    private void updateStatus(String status) {
        if (tvImeStatus != null) {
            tvImeStatus.setText(status);
        }
    }

    private boolean hasBaiduConfig() {
        return !BuildConfig.BAIDU_API_KEY.isEmpty() && !BuildConfig.BAIDU_SECRET_KEY.isEmpty();
    }

    private boolean hasRecordAudioPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }
}
