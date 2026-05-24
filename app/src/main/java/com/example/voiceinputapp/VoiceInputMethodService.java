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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

public class VoiceInputMethodService extends InputMethodService {

    private static final String TAG = "VoiceInputMethod";
    private static final long MIN_RECORDING_DURATION_MS = 500L;
    private static final long INITIAL_SILENCE_TIMEOUT_MS = 3000L;
    private static final long POST_SPEECH_SILENCE_TIMEOUT_MS = 7000L;
    private static final long MAX_RECORDING_DURATION_MS = 60000L;
    private static final long FINISH_TIMEOUT_MS = 4000L;

    private enum SessionState {
        IDLE,
        PRESSING,
        RECORDING,
        PROCESSING
    }

    private enum StopReason {
        RELEASED,
        SILENCE_TIMEOUT,
        MAX_DURATION,
        ABORTED
    }

    private final PcmRecorder pcmRecorder = new PcmRecorder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable pendingLongPressStart = this::startRecordingFromLongPress;
    private final Runnable silenceTimeoutRunnable = this::handleSilenceTimeout;
    private final Runnable maxRecordingTimeoutRunnable = this::handleMaxDurationTimeout;
    private final Runnable finishTimeoutRunnable = this::handleFinishTimeout;

    private TextView tvImeStatus;
    private Button btnImeToggle;

    private SessionState state = SessionState.IDLE;
    private boolean pointerDown;
    private boolean finalResultCommitted;
    private boolean speechDetectedInSession;
    private boolean preserveIdleStatusMessage;
    private String latestPartialText = "";
    private StopReason lastStopReason = StopReason.RELEASED;
    private BaiduRealtimeSpeechClient realtimeSpeechClient;

    @Override
    public View onCreateInputView() {
        View view = LayoutInflater.from(this).inflate(R.layout.input_view, null, false);
        tvImeStatus = view.findViewById(R.id.tvImeStatus);
        btnImeToggle = view.findViewById(R.id.btnImeToggle);
        btnImeToggle.setOnTouchListener((v, event) -> handleRecordButtonTouch(event));
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
        abortSession("finishInputView");
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        abortSession("windowHidden");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        abortSession("destroy");
        pcmRecorder.release();
    }

    private boolean handleRecordButtonTouch(MotionEvent event) {
        if (btnImeToggle == null) {
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return handleActionDown();
            case MotionEvent.ACTION_MOVE:
                return handleActionMove(event);
            case MotionEvent.ACTION_UP:
                return handleActionUpOrCancel("up");
            case MotionEvent.ACTION_CANCEL:
                return handleActionUpOrCancel("cancel");
            default:
                return true;
        }
    }

    private boolean handleActionDown() {
        logState("ACTION_DOWN");
        preserveIdleStatusMessage = false;
        if (state == SessionState.IDLE && pcmRecorder.isRecording()) {
            Log.w(TAG, "Recorder still active while state is IDLE; forcing cleanup before new press");
            abortSession("stale recorder on action down");
        }
        if (!hasBaiduConfig()) {
            updateStatus(getString(R.string.status_config_missing));
            return true;
        }
        if (state == SessionState.PROCESSING) {
            updateStatus(getString(R.string.error_busy));
            return true;
        }

        pointerDown = true;
        if (state == SessionState.IDLE) {
            transitionTo(SessionState.PRESSING, "press start");
            btnImeToggle.setPressed(true);
            mainHandler.postDelayed(pendingLongPressStart, ViewConfiguration.getLongPressTimeout());
            updateStatus(getString(R.string.status_ime_hold_to_talk));
        }
        return true;
    }

    private boolean handleActionMove(MotionEvent event) {
        if ((state == SessionState.PRESSING || state == SessionState.RECORDING) && !isTouchInsideButton(event)) {
            btnImeToggle.setPressed(false);
            pointerDown = false;
            if (state == SessionState.PRESSING) {
                cancelPendingLongPress();
                transitionTo(SessionState.IDLE, "press moved outside before long press");
                updateIdleStatus();
                refreshUi();
            } else if (state == SessionState.RECORDING) {
                stopRecordingAndRecognize(StopReason.RELEASED);
            }
        }
        return true;
    }

    private boolean handleActionUpOrCancel(String source) {
        logState("ACTION_" + source.toUpperCase());
        btnImeToggle.setPressed(false);
        pointerDown = false;
        if (state == SessionState.IDLE && pcmRecorder.isRecording()) {
            Log.w(TAG, "Recorder still active on " + source + " while state is IDLE; forcing cleanup");
            abortSession("stale recorder on action " + source);
            return true;
        }
        if (state == SessionState.PRESSING) {
            cancelPendingLongPress();
            transitionTo(SessionState.IDLE, "press released before long press");
            updateIdleStatus();
            refreshUi();
            return true;
        }
        if (state == SessionState.RECORDING) {
            stopRecordingAndRecognize(StopReason.RELEASED);
        }
        return true;
    }

    private void startRecordingFromLongPress() {
        if (state != SessionState.PRESSING || !pointerDown) {
            return;
        }
        if (pcmRecorder.isRecording()) {
            Log.w(TAG, "Recorder unexpectedly active before long-press start; forcing cleanup");
            abortSession("stale recorder before long press start");
            if (!pointerDown) {
                return;
            }
            transitionTo(SessionState.PRESSING, "resume press after stale recorder cleanup");
        }
        if (!hasRecordAudioPermission()) {
            transitionTo(SessionState.IDLE, "missing record permission");
            updateStatus(getString(R.string.status_ime_permission_missing));
            Toast.makeText(this, R.string.toast_grant_mic_for_ime, Toast.LENGTH_LONG).show();
            refreshUi();
            return;
        }

        try {
            cleanupRecognitionSession(false);
            finalResultCommitted = false;
            speechDetectedInSession = false;
            latestPartialText = "";
            lastStopReason = StopReason.RELEASED;
            realtimeSpeechClient = createRealtimeClient();
            realtimeSpeechClient.connect();
            pcmRecorder.startStreaming((chunk, length, hasSpeech) -> {
                BaiduRealtimeSpeechClient client = realtimeSpeechClient;
                if (client != null && client.isReady()) {
                    client.sendAudio(chunk, length);
                }
                if (state == SessionState.RECORDING && pointerDown && hasSpeech) {
                    onSpeechDetected();
                }
            });

            transitionTo(SessionState.RECORDING, "long press confirmed");
            scheduleInitialSilenceTimeout();
            scheduleMaxDurationTimeout();
            updateStatus(getString(R.string.status_ime_recording));
            refreshUi();
        } catch (Exception exception) {
            Log.e(TAG, "Failed to start recorder from IME", exception);
            cleanupRecordingOnly();
            transitionTo(SessionState.IDLE, "start failed");
            updateStatus(getString(R.string.status_not_supported));
            refreshUi();
        }
    }

    private void stopRecordingAndRecognize(StopReason reason) {
        if (state != SessionState.RECORDING) {
            return;
        }

        logState("stopRecording reason=" + reason);
        lastStopReason = reason;
        cancelPendingLongPress();
        clearRecordingTimeouts();
        btnImeToggle.setPressed(false);

        byte[] audioBytes = pcmRecorder.stop();
        long durationMs = pcmRecorder.getRecordingDurationMs();
        boolean hasSpeech = pcmRecorder.hasDetectedSpeech();
        boolean hadSpeechInSession = speechDetectedInSession || hasSpeech;
        if (audioBytes.length == 0) {
            cleanupRecognitionSession(true);
            transitionTo(SessionState.IDLE, "empty audio");
            updateStatus(getString(R.string.error_audio));
            preserveIdleStatusMessage = true;
            refreshUi();
            return;
        }
        if (durationMs < MIN_RECORDING_DURATION_MS || !hasSpeech) {
            cleanupRecognitionSession(true);
            transitionTo(SessionState.IDLE, "audio too short or no speech");
            if (reason == StopReason.SILENCE_TIMEOUT) {
                updateStatus(getString(
                        hadSpeechInSession
                                ? R.string.error_speech_timeout
                                : R.string.error_no_voice_detected
                ));
            } else {
                updateStatus(getString(R.string.error_recording_too_short));
            }
            preserveIdleStatusMessage = true;
            refreshUi();
            return;
        }

        BaiduRealtimeSpeechClient client = realtimeSpeechClient;
        if (client == null) {
            transitionTo(SessionState.IDLE, "missing realtime client");
            updateStatus(getString(R.string.error_client));
            refreshUi();
            return;
        }

        transitionTo(SessionState.PROCESSING, "recording stopped, waiting final result");
        updateStatus(getString(R.string.status_ime_processing));
        refreshUi();
        client.finish();
        mainHandler.postDelayed(finishTimeoutRunnable, FINISH_TIMEOUT_MS);
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
                        mainHandler.post(VoiceInputMethodService.this::handleRealtimeClosed);
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

        if (state == SessionState.RECORDING && pointerDown) {
            onSpeechDetected();
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
            finishRecognitionSession("final result without editor");
            return;
        }

        inputConnection.commitText(finalText, 1);
        inputConnection.finishComposingText();
        latestPartialText = "";
        finalResultCommitted = true;
        updateStatus(getString(
                pointerDown && state == SessionState.RECORDING
                        ? R.string.status_ime_recording
                        : R.string.status_ime_committed
        ));
        if (state != SessionState.RECORDING || !pointerDown) {
            finishRecognitionSession("final result committed");
        }
    }

    private void handleSilenceTimeout() {
        Log.d(TAG, "Silence timeout fired [state=" + state
                + ", pointerDown=" + pointerDown
                + ", recording=" + pcmRecorder.isRecording()
                + ", speechDetected=" + speechDetectedInSession
                + ", lastSpeechAtMs=" + pcmRecorder.getLastSpeechAtMs() + "]");
        stopRecordingAndRecognize(StopReason.SILENCE_TIMEOUT);
    }

    private void handleMaxDurationTimeout() {
        Log.d(TAG, "Max duration timeout fired [state=" + state
                + ", pointerDown=" + pointerDown
                + ", recording=" + pcmRecorder.isRecording()
                + ", durationMs=" + pcmRecorder.getRecordingDurationMs() + "]");
        stopRecordingAndRecognize(StopReason.MAX_DURATION);
    }

    private void handleRealtimeClosed() {
        clearFinishTimeout();
        if (state != SessionState.PROCESSING) {
            cleanupRecognitionSession(false);
            return;
        }

        if (!finalResultCommitted && TextUtils.isEmpty(latestPartialText)) {
            if (lastStopReason == StopReason.MAX_DURATION) {
                updateStatus(getString(R.string.error_recording_max_duration));
            } else if (lastStopReason == StopReason.SILENCE_TIMEOUT) {
                updateStatus(getString(
                        speechDetectedInSession
                                ? R.string.error_speech_timeout
                                : R.string.error_no_voice_detected
                ));
            } else {
                updateStatus(getString(R.string.status_no_result));
            }
            preserveIdleStatusMessage = true;
        }
        finishRecognitionSession("websocket closed");
    }

    private void handleRecognitionError(Exception exception) {
        clearRecordingTimeouts();
        clearFinishTimeout();
        cleanupRecordingOnly();
        cleanupRecognitionSession(false);
        transitionTo(SessionState.IDLE, "recognition error");

        String message = RecognitionError.messageOf(exception);
        int statusRes = RecognitionErrorMapper.toStatusMessageRes(message);
        if (statusRes != 0) {
            updateStatus(getString(statusRes));
        } else {
            updateStatus(getString(R.string.error_unknown, message));
        }
        refreshUi();
    }

    private void handleFinishTimeout() {
        if (state != SessionState.PROCESSING) {
            return;
        }
        Log.w(TAG, "Realtime ASR session did not close in time; forcing cleanup");
        if (!finalResultCommitted) {
            if (lastStopReason == StopReason.MAX_DURATION) {
                updateStatus(getString(R.string.error_recording_max_duration));
            } else if (lastStopReason == StopReason.SILENCE_TIMEOUT) {
                updateStatus(getString(
                        speechDetectedInSession
                                ? R.string.error_speech_timeout
                                : R.string.error_no_voice_detected
                ));
            } else {
                updateStatus(getString(R.string.status_no_result));
            }
            preserveIdleStatusMessage = true;
        }
        finishRecognitionSession("finish timeout");
    }

    private void finishRecognitionSession(String reason) {
        clearRecordingTimeouts();
        clearFinishTimeout();
        cleanupRecognitionSession(true);
        transitionTo(SessionState.IDLE, reason);
        refreshUi();
    }

    private void abortSession(String reason) {
        logState("abortSession reason=" + reason);
        cancelPendingLongPress();
        clearRecordingTimeouts();
        clearFinishTimeout();
        cleanupRecordingOnly();
        cleanupRecognitionSession(true);
        transitionTo(SessionState.IDLE, "abort:" + reason);
        refreshUi();
    }

    private void cleanupRecordingOnly() {
        if (pcmRecorder.isRecording()) {
            pcmRecorder.stop();
        }
        pointerDown = false;
    }

    private void cleanupRecognitionSession(boolean resetUiFlags) {
        BaiduRealtimeSpeechClient client = realtimeSpeechClient;
        realtimeSpeechClient = null;
        if (client != null) {
            client.cancel();
        }
        if (resetUiFlags) {
            finalResultCommitted = false;
            speechDetectedInSession = false;
            latestPartialText = "";
            lastStopReason = StopReason.RELEASED;
        }
    }

    private void cancelPendingLongPress() {
        mainHandler.removeCallbacks(pendingLongPressStart);
    }

    private void scheduleInitialSilenceTimeout() {
        mainHandler.removeCallbacks(silenceTimeoutRunnable);
        Log.d(TAG, "Schedule initial silence timeout in " + INITIAL_SILENCE_TIMEOUT_MS
                + "ms [recording=" + pcmRecorder.isRecording() + "]");
        mainHandler.postDelayed(silenceTimeoutRunnable, INITIAL_SILENCE_TIMEOUT_MS);
    }

    private void onSpeechDetected() {
        speechDetectedInSession = true;
        mainHandler.removeCallbacks(silenceTimeoutRunnable);
        Log.d(TAG, "Speech detected, reset silence timeout to " + POST_SPEECH_SILENCE_TIMEOUT_MS
                + "ms [recordingDurationMs=" + pcmRecorder.getRecordingDurationMs()
                + ", firstSpeechAtMs=" + pcmRecorder.getFirstSpeechAtMs()
                + ", lastSpeechAtMs=" + pcmRecorder.getLastSpeechAtMs()
                + ", latestPartialEmpty=" + TextUtils.isEmpty(latestPartialText) + "]");
        mainHandler.postDelayed(silenceTimeoutRunnable, POST_SPEECH_SILENCE_TIMEOUT_MS);
    }

    private void scheduleMaxDurationTimeout() {
        mainHandler.removeCallbacks(maxRecordingTimeoutRunnable);
        Log.d(TAG, "Schedule max duration timeout in " + MAX_RECORDING_DURATION_MS + "ms");
        mainHandler.postDelayed(maxRecordingTimeoutRunnable, MAX_RECORDING_DURATION_MS);
    }

    private void clearRecordingTimeouts() {
        mainHandler.removeCallbacks(silenceTimeoutRunnable);
        mainHandler.removeCallbacks(maxRecordingTimeoutRunnable);
    }

    private void clearFinishTimeout() {
        mainHandler.removeCallbacks(finishTimeoutRunnable);
    }

    private void refreshUi() {
        if (btnImeToggle == null) {
            return;
        }

        btnImeToggle.setEnabled(state != SessionState.PROCESSING);
        btnImeToggle.setText(state == SessionState.RECORDING
                ? R.string.ime_action_recording
                : R.string.ime_action_hold);

        if (state == SessionState.IDLE) {
            updateIdleStatus();
        }
    }

    private void updateStatus(String status) {
        if (tvImeStatus != null) {
            tvImeStatus.setText(status);
        }
    }

    private void updateIdleStatus() {
        if (tvImeStatus == null || state != SessionState.IDLE || preserveIdleStatusMessage) {
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

    private boolean isTouchInsideButton(MotionEvent event) {
        if (btnImeToggle == null) {
            return false;
        }
        float x = event.getX();
        float y = event.getY();
        return x >= 0 && x <= btnImeToggle.getWidth() && y >= 0 && y <= btnImeToggle.getHeight();
    }

    private void transitionTo(SessionState newState, String reason) {
        if (state == newState) {
            return;
        }
        Log.d(TAG, "State " + state + " -> " + newState + " (" + reason + ")");
        state = newState;
        if (newState != SessionState.IDLE) {
            preserveIdleStatusMessage = false;
        }
    }

    private void logState(String message) {
        Log.d(TAG, message + " [state=" + state + ", pointerDown=" + pointerDown
                + ", recording=" + pcmRecorder.isRecording() + "]");
    }
}
