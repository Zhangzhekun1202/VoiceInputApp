package com.example.voiceinputapp;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
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

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "VoiceInputApp";

    private TextView tvStatus;
    private EditText etResult;
    private Button btnStart;
    private Button btnStop;
    private Button btnCopy;
    private Button btnClear;

    private SpeechRecognizer speechRecognizer;
    private Intent recognizerIntent;
    private boolean isListening = false;

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
        Log.d(TAG, "MainActivity created");

        initViews();
        initSpeechRecognizer();
        bindActions();
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tvStatus);
        etResult = findViewById(R.id.etResult);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnCopy = findViewById(R.id.btnCopy);
        btnClear = findViewById(R.id.btnClear);

        btnStop.setEnabled(false);
        updateStatus(getString(R.string.status_idle));
        Log.d(TAG, "Views initialized");
    }

    private void initSpeechRecognizer() {
        Log.d(TAG, "Checking SpeechRecognizer availability");
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition not available on this device");
            updateStatus(getString(R.string.status_not_supported));
            btnStart.setEnabled(false);
            btnStop.setEnabled(false);
            showToast(getString(R.string.toast_not_supported));
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        Log.d(TAG, "SpeechRecognizer created successfully");
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                Log.d(TAG, "onReadyForSpeech: " + params);
                updateStatus(getString(R.string.status_ready));
            }

            @Override
            public void onBeginningOfSpeech() {
                Log.d(TAG, "onBeginningOfSpeech");
                updateStatus(getString(R.string.status_listening));
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech");
                updateStatus(getString(R.string.status_processing));
            }

            @Override
            public void onError(int error) {
                isListening = false;
                refreshButtons();
                String readableError = getReadableError(error);
                Log.e(TAG, "onError: code=" + error + ", message=" + readableError);
                updateStatus(readableError + " (code=" + error + ")");
            }

            @Override
            public void onResults(Bundle results) {
                isListening = false;
                refreshButtons();
                Log.d(TAG, "onResults bundle=" + results);
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    Log.d(TAG, "onResults firstMatch=" + matches.get(0));
                    etResult.setText(matches.get(0));
                    etResult.setSelection(etResult.getText().length());
                    updateStatus(getString(R.string.status_result_ready));
                } else {
                    Log.w(TAG, "onResults returned no recognition matches");
                    updateStatus(getString(R.string.status_no_result));
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                Log.d(TAG, "onPartialResults bundle=" + partialResults);
                ArrayList<String> partialMatches =
                        partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (partialMatches != null && !partialMatches.isEmpty()) {
                    Log.d(TAG, "onPartialResults firstMatch=" + partialMatches.get(0));
                    etResult.setText(partialMatches.get(0));
                    etResult.setSelection(etResult.getText().length());
                    updateStatus(getString(R.string.status_partial));
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
                Log.d(TAG, "onEvent: type=" + eventType + ", params=" + params);
            }
        });

        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN");
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        Log.d(TAG, "RecognizerIntent initialized for zh-CN");
    }

    private void bindActions() {
        btnStart.setOnClickListener(v -> checkPermissionAndStart());
        btnStop.setOnClickListener(v -> stopVoiceInput());
        btnCopy.setOnClickListener(v -> copyText());
        btnClear.setOnClickListener(v -> clearText());
        Log.d(TAG, "Button listeners bound");
    }

    private void checkPermissionAndStart() {
        if (speechRecognizer == null) {
            Log.w(TAG, "Start requested but speechRecognizer is null");
            updateStatus(getString(R.string.status_not_supported));
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "RECORD_AUDIO permission already granted");
            startVoiceInput();
        } else {
            Log.d(TAG, "Requesting RECORD_AUDIO permission");
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void startVoiceInput() {
        if (speechRecognizer == null) {
            Log.w(TAG, "startVoiceInput called with null recognizer");
            updateStatus(getString(R.string.status_not_supported));
            return;
        }

        isListening = true;
        refreshButtons();
        updateStatus(getString(R.string.status_starting));
        Log.d(TAG, "Calling speechRecognizer.startListening");
        speechRecognizer.startListening(recognizerIntent);
    }

    private void stopVoiceInput() {
        if (speechRecognizer == null || !isListening) {
            Log.d(TAG, "Stop requested while recognizer inactive");
            updateStatus(getString(R.string.status_idle));
            return;
        }

        updateStatus(getString(R.string.status_stopping));
        Log.d(TAG, "Calling speechRecognizer.stopListening");
        speechRecognizer.stopListening();
        isListening = false;
        new Handler(Looper.getMainLooper()).postDelayed(this::refreshButtons, 300);
    }

    private void copyText() {
        String text = etResult.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            Log.d(TAG, "Copy requested but no text is available");
            showToast(getString(R.string.toast_nothing_to_copy));
            return;
        }

        ClipboardManager clipboardManager =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager != null) {
            ClipData clipData = ClipData.newPlainText(getString(R.string.copy_label), text);
            clipboardManager.setPrimaryClip(clipData);
            Log.d(TAG, "Recognized text copied to clipboard");
            updateStatus(getString(R.string.status_copied));
            showToast(getString(R.string.toast_copied));
        }
    }

    private void clearText() {
        etResult.setText("");
        Log.d(TAG, "Recognized text cleared");
        updateStatus(getString(R.string.status_cleared));
    }

    private void refreshButtons() {
        btnStart.setEnabled(!isListening);
        btnStop.setEnabled(isListening);
    }

    private void updateStatus(String status) {
        tvStatus.setText(status);
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @NonNull
    private String getReadableError(int errorCode) {
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO:
                return getString(R.string.error_audio);
            case SpeechRecognizer.ERROR_CLIENT:
                return getString(R.string.error_client);
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return getString(R.string.error_permission);
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return getString(R.string.error_network);
            case SpeechRecognizer.ERROR_NO_MATCH:
                return getString(R.string.error_no_match);
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return getString(R.string.error_busy);
            case SpeechRecognizer.ERROR_SERVER:
                return getString(R.string.error_server);
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return getString(R.string.error_speech_timeout);
            default:
                return getString(R.string.error_unknown, errorCode);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) {
            Log.d(TAG, "Destroying SpeechRecognizer");
            speechRecognizer.stopListening();
            speechRecognizer.cancel();
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }
}
