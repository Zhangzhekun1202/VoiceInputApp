package com.example.voiceinputapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
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

public class VoiceInputMethodService extends InputMethodService {

    private static final String TAG = "VoiceInputMethod";

    private final PcmRecorder pcmRecorder = new PcmRecorder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView tvImeStatus;
    private Button btnImeToggle;
    private boolean isProcessing = false;
    private boolean finalResultCommitted = false;
    private String latestPartialText = "";
    private BaiduRealtimeSpeechClient realtimeSpeechClient;

    @Override
    public View onCreateInputView() {
        View view = LayoutInflater.from(this).inflate(R.layout.input_view, null, false);
        tvImeStatus = view.findViewById(R.id.tvImeStatus);
        btnImeToggle = view.findViewById(R.id.btnImeToggle);
        btnImeToggle.setOnClickListener(v -> onToggleClicked());
        updateIdleStatus();
        refreshUi();
        return view;
    }

    @Override
    public void onStartInputView(android.view.inputmethod.EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        updateIdleStatus();
        refreshUi();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        if (pcmRecorder.isRecording()) {
            pcmRecorder.stop();
        }
        if (realtimeSpeechClient != null) {
            realtimeSpeechClient.cancel();
            realtimeSpeechClient = null;
        }
        isProcessing = false;
        updateIdleStatus();
        refreshUi();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (realtimeSpeechClient != null) {
            realtimeSpeechClient.cancel();
            realtimeSpeechClient = null;
        }
        pcmRecorder.release();
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
            updateStatus(getString(R.string.status_ime_permission_missing));
            Toast.makeText(this, R.string.toast_grant_mic_for_ime, Toast.LENGTH_LONG).show();
            refreshUi();
            return;
        }

        try {
            finalResultCommitted = false;
            latestPartialText = "";
            realtimeSpeechClient = createRealtimeClient();
            realtimeSpeechClient.connect();
            pcmRecorder.startStreaming((chunk, length) -> {
                BaiduRealtimeSpeechClient client = realtimeSpeechClient;
                if (client != null && client.isReady()) {
                    client.sendAudio(chunk, length);
                }
            });
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
        BaiduRealtimeSpeechClient client = realtimeSpeechClient;
        if (client != null) {
            client.finish();
        } else {
            isProcessing = false;
            updateStatus(getString(R.string.error_client));
            refreshUi();
        }
    }

    private BaiduRealtimeSpeechClient createRealtimeClient() {
        return new BaiduRealtimeSpeechClient(
                BuildConfig.BAIDU_APP_ID,
                BuildConfig.BAIDU_API_KEY,
                getPackageName(),
                new BaiduRealtimeSpeechClient.Listener() {
                    @Override
                    public void onReady() {
                        Log.d(TAG, "Realtime ASR websocket ready");
                    }

                    @Override
                    public void onPartialResult(String text) {
                        mainHandler.post(() -> handlePartialResult(text));
                    }

                    @Override
                    public void onFinalResult(String text) {
                        mainHandler.post(() -> handleFinalResult(text));
                    }

                    @Override
                    public void onError(String message) {
                        Log.e(TAG, "Realtime ASR error: " + message);
                        mainHandler.post(() -> handleRecognitionError(new IllegalStateException(message)));
                    }

                    @Override
                    public void onClosed() {
                        mainHandler.post(() -> handleRealtimeClosed());
                    }
                }
        );
    }

    private void handlePartialResult(String result) {
        latestPartialText = result == null ? "" : result.trim();
        if (TextUtils.isEmpty(latestPartialText)) {
            return;
        }

        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) {
            updateStatus(getString(R.string.status_ime_no_editor));
            return;
        }

        inputConnection.setComposingText(latestPartialText, 1);
        updateStatus(getString(R.string.status_ime_streaming));
    }

    private void handleFinalResult(String result) {
        String finalText = result == null ? "" : result.trim();
        if (TextUtils.isEmpty(finalText)) {
            return;
        }

        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) {
            updateStatus(getString(R.string.status_ime_no_editor));
            return;
        }

        inputConnection.commitText(finalText, 1);
        inputConnection.finishComposingText();
        latestPartialText = "";
        finalResultCommitted = true;
        updateStatus(getString(R.string.status_ime_committed));
    }

    private void handleRealtimeClosed() {
        if (!isProcessing && !pcmRecorder.isRecording()) {
            return;
        }

        isProcessing = false;
        refreshUi();
        if (!finalResultCommitted && TextUtils.isEmpty(latestPartialText)) {
            updateStatus(getString(R.string.status_no_result));
        }
        realtimeSpeechClient = null;
        latestPartialText = "";
        finalResultCommitted = false;
    }

    private void handleRecognitionError(Exception exception) {
        isProcessing = false;
        realtimeSpeechClient = null;

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

        if (!isRecording && !isProcessing) {
            updateIdleStatus();
        }
    }

    private void updateStatus(String status) {
        if (tvImeStatus != null) {
            tvImeStatus.setText(status);
        }
    }

    private void updateIdleStatus() {
        if (tvImeStatus == null || pcmRecorder.isRecording() || isProcessing) {
            return;
        }

        if (!hasBaiduConfig()) {
            tvImeStatus.setText(R.string.status_config_missing);
            return;
        }
        if (!hasRecordAudioPermission()) {
            tvImeStatus.setText(R.string.status_ime_permission_missing);
            return;
        }
        tvImeStatus.setText(R.string.status_ime_idle);
    }

    private boolean hasBaiduConfig() {
        return !BuildConfig.BAIDU_APP_ID.isEmpty() && !BuildConfig.BAIDU_API_KEY.isEmpty();
    }

    private boolean hasRecordAudioPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }
}
