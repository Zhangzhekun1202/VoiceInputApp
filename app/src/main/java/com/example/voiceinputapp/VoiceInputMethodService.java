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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.util.List;

public class VoiceInputMethodService extends InputMethodService {

    private static final String TAG = "VoiceInputMethod";
    private static final long MIN_RECORDING_DURATION_MS = 500L;
    private static final long INITIAL_SILENCE_TIMEOUT_MS = 3000L;
    private static final long POST_SPEECH_SILENCE_TIMEOUT_MS = 5000L;
    private static final long MAX_RECORDING_DURATION_MS = 60000L;
    private static final long FINISH_TIMEOUT_MS = 4000L;
    private static final long BACKSPACE_REPEAT_INITIAL_DELAY_MS = 350L;
    private static final long BACKSPACE_REPEAT_INTERVAL_MS = 70L;

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

    private enum PanelMode {
        VOICE,
        EDIT
    }

    private enum EditInputMode {
        PINYIN,
        ENGLISH,
        SYMBOLS
    }

    // Legacy symbol paging state kept temporarily until the old dead methods are fully removed.
    private enum SymbolBoard {
        BASIC,
        EXTENDED
    }

    private enum SymbolCategory {
        COMMON,
        CHINESE,
        ENGLISH,
        MATH
    }

    private final PcmRecorder pcmRecorder = new PcmRecorder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable pendingLongPressStart = this::startRecordingFromLongPress;
    private final Runnable silenceTimeoutRunnable = this::handleSilenceTimeout;
    private final Runnable maxRecordingTimeoutRunnable = this::handleMaxDurationTimeout;
    private final Runnable finishTimeoutRunnable = this::handleFinishTimeout;
    private final Runnable backspaceRepeatRunnable = new Runnable() {
        @Override
        public void run() {
            deleteFromEditor();
            mainHandler.postDelayed(this, BACKSPACE_REPEAT_INTERVAL_MS);
        }
    };

    private TextView tvImeStatus;
    private TextView tvPinyinBuffer;
    private Button btnImeToggle;
    private Button btnImeMode;
    private Button btnShift;
    private Button btnLangToggle;
    private Button btnMode123;
    private Button btnQuickSymbol;
    private LinearLayout layoutCandidateBar;
    private LinearLayout layoutEditActions;
    private final StringBuilder pinyinBuffer = new StringBuilder();
    private final Button[] alphaKeyButtons = new Button[26];

    private SessionState state = SessionState.IDLE;
    private PanelMode panelMode = PanelMode.VOICE;
    private EditInputMode editInputMode = EditInputMode.PINYIN;
    private EditInputMode lastAlphaMode = EditInputMode.PINYIN;
    private SymbolBoard symbolBoard = SymbolBoard.BASIC;
    private SymbolCategory symbolCategory = SymbolCategory.COMMON;
    private boolean englishUppercase;
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
        SimplePinyinDecoder.preloadAsync(
                this,
                () -> mainHandler.post(this::refreshPinyinUi),
                () -> mainHandler.post(this::refreshPinyinUi)
        );
        tvImeStatus = view.findViewById(R.id.tvImeStatus);
        tvPinyinBuffer = view.findViewById(R.id.tvPinyinBuffer);
        btnImeToggle = view.findViewById(R.id.btnImeToggle);
        btnImeMode = view.findViewById(R.id.btnImeMode);
        btnShift = view.findViewById(R.id.btnShift);
        btnLangToggle = view.findViewById(R.id.btnLangToggle);
        btnMode123 = view.findViewById(R.id.btnMode123);
        btnQuickSymbol = view.findViewById(R.id.btnQuickSymbol);
        layoutCandidateBar = view.findViewById(R.id.layoutCandidateBar);
        layoutEditActions = view.findViewById(R.id.layoutEditActions);
        btnImeToggle.setOnTouchListener((v, event) -> handleRecordButtonTouch(event));
        btnImeMode.setOnClickListener(v -> togglePanelMode());
        bindEditKeyboard(view);
        refreshPinyinUi();
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

        if (state == SessionState.RECORDING && pointerDown) {
            onSpeechDetected();
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
            if (state != SessionState.RECORDING || !pointerDown) {
                finishRecognitionSession("final result without editor");
            }
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
        mainHandler.removeCallbacks(backspaceRepeatRunnable);
        clearRecordingTimeouts();
        clearFinishTimeout();
        cleanupRecognitionSession(true);
        transitionTo(SessionState.IDLE, reason);
        refreshUi();
    }

    private void abortSession(String reason) {
        logState("abortSession reason=" + reason);
        mainHandler.removeCallbacks(backspaceRepeatRunnable);
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
        if (btnImeToggle == null || btnImeMode == null) {
            return;
        }

        boolean isVoiceMode = panelMode == PanelMode.VOICE;

        btnImeToggle.setEnabled(state != SessionState.PROCESSING);
        btnImeToggle.setText(state == SessionState.RECORDING
                ? R.string.ime_action_recording
                : R.string.ime_action_hold);
        btnImeToggle.setVisibility(isVoiceMode ? View.VISIBLE : View.GONE);
        layoutEditActions.setVisibility(isVoiceMode ? View.GONE : View.VISIBLE);
        btnImeMode.setText(isVoiceMode ? R.string.ime_action_edit : R.string.ime_action_voice);
        tvImeStatus.setVisibility(isVoiceMode ? View.VISIBLE : View.GONE);
        refreshPinyinUi();
        applyKeyboardVisualState();

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

    private void togglePanelMode() {
        if (state == SessionState.RECORDING) {
            updateStatus(getString(R.string.status_ime_finish_recording_first));
            return;
        }
        panelMode = panelMode == PanelMode.VOICE ? PanelMode.EDIT : PanelMode.VOICE;
        refreshUi();
    }

    private void bindEditKeyboard(View root) {
        int[] keyIds = new int[] {
                R.id.keyQ, R.id.keyW, R.id.keyE, R.id.keyR, R.id.keyT, R.id.keyY, R.id.keyU, R.id.keyI, R.id.keyO, R.id.keyP,
                R.id.keyA, R.id.keyS, R.id.keyD, R.id.keyF, R.id.keyG, R.id.keyH, R.id.keyJ, R.id.keyK, R.id.keyL,
                R.id.keyZ, R.id.keyX, R.id.keyC, R.id.keyV, R.id.keyB, R.id.keyN, R.id.keyM
        };
        for (int index = 0; index < keyIds.length; index++) {
            Button keyButton = root.findViewById(keyIds[index]);
            alphaKeyButtons[index] = keyButton;
            int keyIndex = index;
            keyButton.setOnClickListener(v -> handleEditorKeyPress(keyIndex));
        }
        btnShift.setOnClickListener(v -> toggleEditorCase());
        btnLangToggle.setOnClickListener(v -> toggleEditorLanguage());
        btnMode123.setOnClickListener(v -> toggleEditorNumberMode());
        btnQuickSymbol.setOnClickListener(v -> toggleExtendedSymbolBoard());
        root.findViewById(R.id.keyBackspace).setOnTouchListener((v, event) -> handleBackspaceTouch(event));
        root.findViewById(R.id.keySpace).setOnClickListener(v -> handleSpace());
        root.findViewById(R.id.keyEnter).setOnClickListener(v -> handleEnter());
        applyKeyboardVisualState();
    }

    private boolean handleBackspaceTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                deleteFromEditor();
                mainHandler.removeCallbacks(backspaceRepeatRunnable);
                mainHandler.postDelayed(backspaceRepeatRunnable, BACKSPACE_REPEAT_INITIAL_DELAY_MS);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mainHandler.removeCallbacks(backspaceRepeatRunnable);
                return true;
            default:
                return true;
        }
    }

    private void commitDirectText(String text) {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) {
            return;
        }
        inputConnection.commitText(text, 1);
    }

    private void appendPinyin(String text) {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) {
            return;
        }
        pinyinBuffer.append(text);
        inputConnection.setComposingText(pinyinBuffer.toString(), 1);
        refreshPinyinUi();
    }

    private void handleSpace() {
        if (editInputMode == EditInputMode.PINYIN && pinyinBuffer.length() > 0) {
            commitBestCandidate(true);
            return;
        }
        commitDirectText(" ");
    }

    private void handleEnter() {
        if (editInputMode == EditInputMode.PINYIN && pinyinBuffer.length() > 0) {
            commitBestCandidate(false);
        }
        commitDirectText("\n");
    }

    private void handleQuickSymbol() {
        if (editInputMode == EditInputMode.PINYIN) {
            commitCurrentPinyinIfNeeded();
            commitDirectText("，");
        } else if (editInputMode == EditInputMode.ENGLISH) {
            commitDirectText(",");
        } else {
            commitDirectText("。");
        }
    }

    private void commitBestCandidate(boolean appendSpace) {
        if (pinyinBuffer.length() == 0) {
            if (appendSpace) {
                commitDirectText(" ");
            }
            return;
        }

        List<String> candidates = SimplePinyinDecoder.getCandidates(this, pinyinBuffer.toString());
        String text = candidates.isEmpty() ? pinyinBuffer.toString() : candidates.get(0);
        selectCandidate(text, appendSpace);
    }

    private void selectCandidate(String candidate, boolean appendSpace) {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) {
            return;
        }
        inputConnection.commitText(appendSpace ? candidate + " " : candidate, 1);
        pinyinBuffer.setLength(0);
        inputConnection.finishComposingText();
        refreshPinyinUi();
    }

    private void deleteFromEditor() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) {
            return;
        }
        if (pinyinBuffer.length() > 0) {
            pinyinBuffer.deleteCharAt(pinyinBuffer.length() - 1);
            if (pinyinBuffer.length() == 0) {
                inputConnection.finishComposingText();
            } else {
                inputConnection.setComposingText(pinyinBuffer.toString(), 1);
            }
            refreshPinyinUi();
            return;
        }
        inputConnection.deleteSurroundingText(1, 0);
    }

    private void refreshPinyinUi() {
        if (tvPinyinBuffer == null || layoutCandidateBar == null) {
            return;
        }

        if (editInputMode == EditInputMode.SYMBOLS) {
            tvPinyinBuffer.setText(getSymbolHeaderText());
            tvPinyinBuffer.setTextColor(getColor(R.color.text_primary));
            layoutCandidateBar.removeAllViews();
            for (SymbolCategory category : SymbolCategory.values()) {
                layoutCandidateBar.addView(createSymbolCategoryButton(category));
            }
            applyKeyboardVisualState();
            return;
        }

        if (editInputMode != EditInputMode.PINYIN) {
            tvPinyinBuffer.setText("");
            tvPinyinBuffer.setTextColor(getColor(R.color.text_secondary));
            layoutCandidateBar.removeAllViews();
            applyKeyboardVisualState();
            return;
        }

        if (pinyinBuffer.length() == 0) {
            tvPinyinBuffer.setText("");
            tvPinyinBuffer.setTextColor(getColor(R.color.text_secondary));
        } else {
            tvPinyinBuffer.setText(pinyinBuffer.toString());
            tvPinyinBuffer.setTextColor(getColor(R.color.text_primary));
        }

        layoutCandidateBar.removeAllViews();
        List<String> candidates = SimplePinyinDecoder.getCandidates(this, pinyinBuffer.toString());
        for (int index = 0; index < candidates.size(); index++) {
            String candidate = candidates.get(index);
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setText(getString(R.string.ime_candidate_item, index + 1, candidate));
            button.setMinHeight(dpToPx(34));
            button.setMinimumHeight(dpToPx(34));
            button.setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5));
            button.setTextSize(13f);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMarginEnd(dpToPx(6));
            button.setLayoutParams(params);
            button.setOnClickListener(v -> selectCandidate(candidate, false));
            layoutCandidateBar.addView(button);
        }
        applyKeyboardVisualState();
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private Button createSymbolCategoryButton(SymbolCategory category) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setMinHeight(dpToPx(30));
        button.setMinimumHeight(dpToPx(30));
        button.setTextSize(12f);
        button.setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4));
        button.setText(getSymbolCategoryLabel(category));
        float alpha = category == symbolCategory ? 1f : 0.72f;
        button.setAlpha(alpha);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMarginEnd(dpToPx(6));
        button.setLayoutParams(params);
        button.setOnClickListener(v -> {
            symbolCategory = category;
            refreshPinyinUi();
        });
        return button;
    }

    private String getSymbolHeaderText() {
        switch (symbolCategory) {
            case CHINESE:
                return "\u4E2D\u6587\u7B26\u53F7";
            case ENGLISH:
                return "\u82F1\u6587\u7B26\u53F7";
            case MATH:
                return "\u6570\u5B66\u7B26\u53F7";
            case COMMON:
            default:
                return "\u5E38\u7528\u7B26\u53F7";
        }
    }

    private String getSymbolCategoryLabel(SymbolCategory category) {
        switch (category) {
            case CHINESE:
                return "2.\u4E2D\u6587";
            case ENGLISH:
                return "3.\u82F1\u6587";
            case MATH:
                return "4.\u6570\u5B66";
            case COMMON:
            default:
                return "1.\u5E38\u7528";
        }
    }

    private void commitCurrentPinyinIfNeeded() {
        if (editInputMode == EditInputMode.PINYIN && pinyinBuffer.length() > 0) {
            commitBestCandidate(false);
        }
    }

    private void refreshEditKeyboardUi() {
        String[] labels;
        if (editInputMode == EditInputMode.SYMBOLS) {
            labels = getSymbolKeys();
        } else if (editInputMode == EditInputMode.ENGLISH && englishUppercase) {
            labels = getUpperAlphabetKeys();
        } else {
            labels = getLowerAlphabetKeys();
        }

        for (int index = 0; index < alphaKeyButtons.length; index++) {
            if (alphaKeyButtons[index] != null) {
                alphaKeyButtons[index].setText(labels[index]);
            }
        }

        if (btnShift != null) {
            btnShift.setEnabled(editInputMode == EditInputMode.ENGLISH);
            btnShift.setAlpha(editInputMode == EditInputMode.ENGLISH ? 1f : 0.5f);
            btnShift.setText(englishUppercase ? "⇧" : "↑");
        }
        if (btnLangToggle != null) {
            btnLangToggle.setText(editInputMode == EditInputMode.ENGLISH ? "中" : "英");
        }
        if (btnMode123 != null) {
            btnMode123.setText(editInputMode == EditInputMode.SYMBOLS ? "ABC" : "123");
        }
        if (btnQuickSymbol != null) {
            btnQuickSymbol.setText(editInputMode == EditInputMode.ENGLISH ? ".,?" : "，。？");
        }
    }

    private String[] getLowerAlphabetKeys() {
        return new String[] {
                "q", "w", "e", "r", "t", "y", "u", "i", "o", "p",
                "a", "s", "d", "f", "g", "h", "j", "k", "l",
                "z", "x", "c", "v", "b", "n", "m"
        };
    }

    private String[] getUpperAlphabetKeys() {
        return new String[] {
                "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P",
                "A", "S", "D", "F", "G", "H", "J", "K", "L",
                "Z", "X", "C", "V", "B", "N", "M"
        };
    }

    private void handleEditorKeyPress(int index) {
        String[] lowerKeys = getLowerAlphabetKeys();
        String[] upperKeys = getUpperAlphabetKeys();
        String[] symbolKeys = getSymbolKeysForActiveCategory();
        switch (editInputMode) {
            case PINYIN:
                appendPinyin(lowerKeys[index]);
                break;
            case ENGLISH:
                commitDirectText(englishUppercase ? upperKeys[index] : lowerKeys[index]);
                break;
            case SYMBOLS:
                commitDirectText(symbolKeys[index]);
                break;
        }
    }

    private void toggleEditorCase() {
        if (editInputMode == EditInputMode.SYMBOLS) {
            return;
        }
        englishUppercase = !englishUppercase;
        applyKeyboardVisualState();
    }

    private void toggleEditorLanguage() {
        commitCurrentPinyinIfNeeded();
        if (editInputMode == EditInputMode.SYMBOLS) {
            editInputMode = lastAlphaMode == EditInputMode.PINYIN
                    ? EditInputMode.ENGLISH
                    : EditInputMode.PINYIN;
            lastAlphaMode = editInputMode;
        } else if (editInputMode == EditInputMode.ENGLISH) {
            editInputMode = EditInputMode.PINYIN;
            lastAlphaMode = EditInputMode.PINYIN;
        } else {
            editInputMode = EditInputMode.ENGLISH;
            lastAlphaMode = EditInputMode.ENGLISH;
        }
        refreshPinyinUi();
    }

    private void toggleEditorNumberMode() {
        commitCurrentPinyinIfNeeded();
        if (editInputMode == EditInputMode.SYMBOLS) {
            editInputMode = lastAlphaMode;
        } else {
            lastAlphaMode = editInputMode;
            editInputMode = EditInputMode.SYMBOLS;
            symbolCategory = SymbolCategory.COMMON;
        }
        refreshPinyinUi();
    }

    private void toggleExtendedSymbolBoard() {
        commitCurrentPinyinIfNeeded();
        if (editInputMode != EditInputMode.SYMBOLS) {
            lastAlphaMode = editInputMode;
            editInputMode = EditInputMode.SYMBOLS;
            symbolCategory = SymbolCategory.CHINESE;
        } else {
            switch (symbolCategory) {
                case COMMON:
                    symbolCategory = SymbolCategory.CHINESE;
                    break;
                case CHINESE:
                    symbolCategory = SymbolCategory.ENGLISH;
                    break;
                case ENGLISH:
                    symbolCategory = SymbolCategory.MATH;
                    break;
                case MATH:
                default:
                    symbolCategory = SymbolCategory.COMMON;
                    break;
            }
        }
        refreshPinyinUi();
    }

    private void applyKeyboardVisualState() {
        String[] labels;
        if (editInputMode == EditInputMode.SYMBOLS) {
            labels = getSymbolKeysForActiveCategory();
        } else if (editInputMode == EditInputMode.ENGLISH && englishUppercase) {
            labels = getUpperAlphabetKeys();
        } else {
            labels = getLowerAlphabetKeys();
        }

        for (int index = 0; index < alphaKeyButtons.length; index++) {
            if (alphaKeyButtons[index] != null && index < labels.length) {
                alphaKeyButtons[index].setText(labels[index]);
            }
        }

        if (btnShift != null) {
            btnShift.setEnabled(editInputMode != EditInputMode.SYMBOLS);
            btnShift.setAlpha(editInputMode == EditInputMode.SYMBOLS ? 0.45f : 1f);
            btnShift.setText(englishUppercase ? "\u21E7" : "\u2191");
        }
        if (btnLangToggle != null) {
            boolean englishActive = editInputMode == EditInputMode.ENGLISH
                    || (editInputMode == EditInputMode.SYMBOLS
                    && lastAlphaMode == EditInputMode.ENGLISH);
            btnLangToggle.setText(englishActive ? "\u82F1" : "\u4E2D");
        }
        if (btnMode123 != null) {
            btnMode123.setText(editInputMode == EditInputMode.SYMBOLS ? "abc" : "123");
        }
        if (btnQuickSymbol != null) {
            btnQuickSymbol.setText(editInputMode == EditInputMode.SYMBOLS
                    ? "\u5206\u7C7B"
                    : "\u7B26");
        }
    }

    private String[] getSymbolKeysForActiveCategory() {
        switch (symbolCategory) {
            case CHINESE:
                return new String[] {
                        "\uFF0C", "\u3002", "\u3001", "\uFF1B", "\uFF1A", "\uFF1F", "\uFF01", "\u2014", "\u2026", "\u301C",
                        "\u300C", "\u300D", "\u300E", "\u300F", "\uFF08", "\uFF09", "\u3010", "\u3011", "\u300A",
                        "\u300B", "\u3008", "\u3009", "\u201C", "\u201D", "\u00B7", "\uFF5E"
                };
            case ENGLISH:
                return new String[] {
                        "[", "]", "{", "}", "<", ">", "=", "+", "-", "_",
                        "@", "#", "$", "%", "^", "&", "*", "/", "\\",
                        "|", "~", "`", "\"", "'", ":", ";"
                };
            case MATH:
                return new String[] {
                        "+", "-", "\u00D7", "\u00F7", "=", "\u2260", ">", "<", "\u2265", "\u2264",
                        "\u00B1", "\u221E", "\u221A", "\u03C0", "\u03A3", "\u2206", "\u2208", "\u2229", "\u222A",
                        "\u222B", "\u2220", "\u2234", "\u2235", "\u2261", "\u2248", "\u2202"
                };
            case COMMON:
            default:
                return new String[] {
                        "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
                        "\uFF0C", "\u3002", "\uFF1F", "\uFF01", "@", "#", "&", "*", "/",
                        "(", ")", "-", "_", "+", "=", "%"
                };
        }
    }

    // Legacy keyboard helpers retained only as a temporary compatibility block.
    // The active code path uses applyKeyboardVisualState() and getSymbolKeysForActiveCategory().
    private String[] getSymbolKeys() {
        return getSymbolKeysForActiveCategory();
    }

    private void applyEditKeyboardState() {
        String[] labels;
        if (editInputMode == EditInputMode.SYMBOLS) {
            labels = symbolBoard == SymbolBoard.BASIC
                    ? getBasicSymbolKeys()
                    : getExtendedSymbolKeys();
        } else if (editInputMode == EditInputMode.ENGLISH && englishUppercase) {
            labels = getUpperAlphabetKeys();
        } else {
            labels = getLowerAlphabetKeys();
        }

        for (int index = 0; index < alphaKeyButtons.length; index++) {
            if (alphaKeyButtons[index] != null) {
                alphaKeyButtons[index].setText(labels[index]);
            }
        }

        if (btnShift != null) {
            btnShift.setEnabled(editInputMode != EditInputMode.SYMBOLS);
            btnShift.setAlpha(editInputMode == EditInputMode.SYMBOLS ? 0.45f : 1f);
            btnShift.setText(englishUppercase ? "⇧" : "↑");
        }
        if (btnLangToggle != null) {
            boolean englishActive = editInputMode == EditInputMode.ENGLISH
                    || (editInputMode == EditInputMode.SYMBOLS
                    && lastAlphaMode == EditInputMode.ENGLISH);
            btnLangToggle.setText(englishActive ? "英" : "中");
        }
        if (btnMode123 != null) {
            btnMode123.setText(editInputMode == EditInputMode.SYMBOLS ? "abc" : "123");
        }
        if (btnQuickSymbol != null) {
            btnQuickSymbol.setText(editInputMode == EditInputMode.SYMBOLS && symbolBoard == SymbolBoard.EXTENDED
                    ? "123"
                    : "符");
        }
    }

    private String[] getBasicSymbolKeys() {
        return new String[] {
                "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
                "@", "#", "%", "&", "-", "+", "(", ")", "/",
                "*", "\"", "'", ":", ";", "?", "!"
        };
    }

    private String[] getExtendedSymbolKeys() {
        return new String[] {
                "~", "`", "|", "•", "√", "€", "£", "¥", "^", "_",
                "[", "]", "{", "}", "<", ">", "=", "\\", "·",
                "…", "—", "，", "。", "；", "：", "！"
        };
    }
}
