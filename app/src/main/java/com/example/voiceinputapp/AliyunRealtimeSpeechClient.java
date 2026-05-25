package com.example.voiceinputapp;

import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

final class AliyunRealtimeSpeechClient implements SpeechRecognitionClient {
    private static final String TAG = "AliyunRealtimeAsr";
    private static final String WS_URL = "wss://nls-gateway-cn-shanghai.aliyuncs.com/ws/v1?token=%s";
    private static final String NAMESPACE = "SpeechTranscriber";

    private final String appKey;
    private final Listener listener;
    private final OkHttpClient okHttpClient;

    private WebSocket webSocket;
    private String taskId;
    private boolean ready;
    private boolean finished;

    AliyunRealtimeSpeechClient(String appKey, Listener listener) {
        this.appKey = appKey == null ? "" : appKey.trim();
        this.listener = listener;
        this.okHttpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public void connect() {
        Log.d(TAG, "connect() called");
        if (appKey.isEmpty()) {
            Log.e(TAG, "ALIYUN_APP_KEY is missing");
            listener.onError("Missing ALIYUN_APP_KEY");
            return;
        }
        if (!AliyunTokenService.isConfigured()) {
            Log.e(TAG, "ALIYUN_TOKEN_ENDPOINT is missing");
            listener.onError("Missing ALIYUN_TOKEN_ENDPOINT");
            return;
        }

        AliyunTokenService.fetchToken(new AliyunTokenService.TokenCallback() {
            @Override
            public void onSuccess(AliyunToken token) {
                Log.d(TAG, "Token acquired, opening websocket");
                openWebSocket(token);
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "Token acquisition failed: " + message);
                listener.onError(message);
            }
        });
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public boolean usesExternalAudioInput() {
        return true;
    }

    @Override
    public void sendAudio(byte[] data, int length) {
        if (webSocket == null || !ready || finished || data == null || length <= 0) {
            return;
        }
        Log.v(TAG, "Sending audio frame, bytes=" + length);
        webSocket.send(ByteString.of(data, 0, length));
    }

    @Override
    public void finish() {
        if (webSocket == null || finished) {
            return;
        }
        finished = true;
        try {
            Log.d(TAG, "Sending StopTranscription");
            webSocket.send(buildCommand("StopTranscription"));
        } catch (JSONException exception) {
            Log.e(TAG, "Failed to build StopTranscription payload", exception);
            listener.onError(exception.getMessage() == null ? "Failed to stop recognition" : exception.getMessage());
        }
    }

    @Override
    public void cancel() {
        finished = true;
        ready = false;
        Log.d(TAG, "cancel() called");
        if (webSocket != null) {
            webSocket.close(1000, "cancelled");
            webSocket = null;
        }
        okHttpClient.dispatcher().executorService().shutdown();
    }

    private void openWebSocket(AliyunToken token) {
        if (finished) {
            Log.w(TAG, "Skipping websocket open because client is already finished");
            return;
        }
        taskId = UUID.randomUUID().toString().replace("-", "");
        String url = String.format(Locale.US, WS_URL, token.getValue());
        Log.d(TAG, "Opening websocket, taskId=" + taskId + ", url=" + url);
        Request request = new Request.Builder()
                .url(url)
                .build();
        webSocket = okHttpClient.newWebSocket(request, new RealtimeWebSocketListener());
    }

    private String buildCommand(String name) throws JSONException {
        JSONObject header = new JSONObject();
        header.put("message_id", UUID.randomUUID().toString().replace("-", ""));
        header.put("task_id", taskId);
        header.put("namespace", NAMESPACE);
        header.put("name", name);
        header.put("appkey", appKey);

        JSONObject payload = new JSONObject();
        if ("StartTranscription".equals(name)) {
            payload.put("format", "pcm");
            payload.put("sample_rate", PcmRecorder.SAMPLE_RATE);
            payload.put("enable_intermediate_result", true);
            payload.put("enable_punctuation_prediction", true);
            payload.put("enable_inverse_text_normalization", true);
        }

        JSONObject command = new JSONObject();
        command.put("header", header);
        command.put("payload", payload);
        return command.toString();
    }

    private void handleEvent(String text) throws JSONException {
        Log.d(TAG, "Received websocket message: " + text);
        JSONObject json = new JSONObject(text);
        JSONObject header = json.optJSONObject("header");
        JSONObject payload = json.optJSONObject("payload");
        String name = header == null ? "" : header.optString("name", "");
        int status = header == null ? 0 : header.optInt("status", 0);
        String statusText = header == null ? "" : header.optString("status_text", "").trim();

        Log.d(TAG, "Event name=" + name + ", status=" + status + ", statusText=" + statusText);

        if (status != 0 && status != 20000000) {
            Log.e(TAG, "Aliyun ASR returned error status");
            listener.onError(statusText.isEmpty() ? ("Aliyun ASR error: " + status) : statusText);
            return;
        }

        String result = payload == null ? "" : payload.optString("result", "").trim();
        switch (name) {
            case "TranscriptionStarted":
                ready = true;
                Log.d(TAG, "TranscriptionStarted, client ready");
                listener.onReady();
                return;
            case "TranscriptionResultChanged":
                if (!result.isEmpty()) {
                    Log.d(TAG, "Partial result=" + result);
                    listener.onPartialResult(result);
                }
                return;
            case "SentenceEnd":
                if (!result.isEmpty()) {
                    Log.d(TAG, "Final result=" + result);
                    listener.onFinalResult(result);
                }
                return;
            case "TranscriptionCompleted":
                ready = false;
                Log.d(TAG, "TranscriptionCompleted received");
                if (webSocket != null) {
                    webSocket.close(1000, "completed");
                }
                return;
            case "TaskFailed":
                Log.e(TAG, "TaskFailed received");
                listener.onError(statusText.isEmpty() ? "Aliyun task failed" : statusText);
                return;
            default:
                Log.d(TAG, "Ignoring event name=" + name);
                return;
        }
    }

    private final class RealtimeWebSocketListener extends WebSocketListener {
        @Override
        public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
            try {
                Log.d(TAG, "WebSocket opened, sending StartTranscription");
                webSocket.send(buildCommand("StartTranscription"));
            } catch (JSONException exception) {
                ready = false;
                Log.e(TAG, "Failed to build StartTranscription payload", exception);
                listener.onError(exception.getMessage() == null ? "Failed to start recognition" : exception.getMessage());
            }
        }

        @Override
        public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
            try {
                handleEvent(text);
            } catch (JSONException exception) {
                listener.onError(exception.getMessage() == null ? "Invalid recognition response" : exception.getMessage());
            }
        }

        @Override
        public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            ready = false;
            Log.d(TAG, "WebSocket closing, code=" + code + ", reason=" + reason);
        }

        @Override
        public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            ready = false;
            AliyunRealtimeSpeechClient.this.webSocket = null;
            Log.d(TAG, "WebSocket closed, code=" + code + ", reason=" + reason);
            listener.onClosed();
            okHttpClient.dispatcher().executorService().shutdown();
        }

        @Override
        public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, Response response) {
            ready = false;
            String message = t.getMessage() == null ? "WebSocket failure" : t.getMessage();
            if (response != null && response.message() != null && !response.message().isEmpty()) {
                message = response.message();
            }
            Log.e(TAG, "WebSocket failure: " + message, t);
            listener.onError(message);
            okHttpClient.dispatcher().executorService().shutdown();
        }
    }
}
