package com.example.voiceinputapp;

import android.text.TextUtils;

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

public class BaiduRealtimeSpeechClient {

    public interface Listener {
        void onReady();

        void onPartialResult(String text);

        void onFinalResult(String text);

        void onError(String message);

        void onClosed();
    }

    private static final String WS_URL = "wss://vop.baidu.com/realtime_asr?sn=%s";
    private static final int DEV_PID = 15372;

    private final String appId;
    private final String appKey;
    private final String cuid;
    private final Listener listener;
    private final OkHttpClient okHttpClient;

    private WebSocket webSocket;
    private boolean ready;
    private boolean finished;

    public BaiduRealtimeSpeechClient(String appId, String appKey, String cuid, Listener listener) {
        this.appId = appId;
        this.appKey = appKey;
        this.cuid = cuid;
        this.listener = listener;
        this.okHttpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    public void connect() {
        if (TextUtils.isEmpty(appId) || TextUtils.isEmpty(appKey)) {
            listener.onError("Missing BAIDU_APP_ID or BAIDU_API_KEY");
            return;
        }

        String sn = UUID.randomUUID().toString();
        Request request = new Request.Builder()
                .url(String.format(Locale.US, WS_URL, sn))
                .build();
        webSocket = okHttpClient.newWebSocket(request, new RealtimeWebSocketListener());
    }

    public boolean isReady() {
        return ready;
    }

    public void sendAudio(byte[] data, int length) {
        if (webSocket == null || !ready || finished || data == null || length <= 0) {
            return;
        }
        webSocket.send(okio.ByteString.of(data, 0, length));
    }

    public void finish() {
        if (webSocket == null || finished) {
            return;
        }
        finished = true;
        webSocket.send("{\"type\":\"FINISH\"}");
    }

    public void cancel() {
        finished = true;
        ready = false;
        if (webSocket != null) {
            webSocket.send("{\"type\":\"CANCEL\"}");
            webSocket.close(1000, "cancelled");
            webSocket = null;
        }
        okHttpClient.dispatcher().executorService().shutdown();
    }

    private String buildStartPayload() throws JSONException {
        JSONObject data = new JSONObject();
        data.put("appid", Integer.parseInt(appId));
        data.put("appkey", appKey);
        data.put("dev_pid", DEV_PID);
        data.put("cuid", cuid);
        data.put("format", "pcm");
        data.put("sample", PcmRecorder.SAMPLE_RATE);

        JSONObject payload = new JSONObject();
        payload.put("type", "START");
        payload.put("data", data);
        return payload.toString();
    }

    private final class RealtimeWebSocketListener extends WebSocketListener {
        @Override
        public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
            try {
                webSocket.send(buildStartPayload());
                ready = true;
                listener.onReady();
            } catch (Exception exception) {
                ready = false;
                listener.onError(exception.getMessage());
            }
        }

        @Override
        public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
            try {
                JSONObject json = new JSONObject(text);
                String type = json.optString("type", "");
                if ("HEARTBEAT".equals(type)) {
                    return;
                }

                int errNo = json.optInt("err_no", 0);
                if (errNo != 0) {
                    listener.onError(json.optString("err_msg", "Baidu realtime ASR error"));
                    return;
                }

                String result = json.optString("result", "").trim();
                if ("MID_TEXT".equals(type)) {
                    listener.onPartialResult(result);
                } else if ("FIN_TEXT".equals(type)) {
                    listener.onFinalResult(result);
                }
            } catch (JSONException exception) {
                listener.onError(exception.getMessage());
            }
        }

        @Override
        public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            ready = false;
        }

        @Override
        public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            ready = false;
            listener.onClosed();
            okHttpClient.dispatcher().executorService().shutdown();
        }

        @Override
        public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, Response response) {
            ready = false;
            listener.onError(t.getMessage() == null ? "WebSocket failure" : t.getMessage());
            okHttpClient.dispatcher().executorService().shutdown();
        }
    }
}
