package com.example.voiceinputapp;

import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class AliyunTokenService {
    private static final String TAG = "AliyunTokenService";

    public interface TokenCallback {
        void onSuccess(AliyunToken token);

        void onError(String message);
    }

    private static final long TOKEN_REFRESH_GUARD_MS = TimeUnit.MINUTES.toMillis(3);
    private static final OkHttpClient CLIENT = new OkHttpClient();
    private static volatile AliyunToken cachedToken;

    private AliyunTokenService() {
    }

    public static boolean isConfigured() {
        return !BuildConfig.ALIYUN_TOKEN_ENDPOINT.isEmpty();
    }

    public static void fetchToken(TokenCallback callback) {
        AliyunToken token = cachedToken;
        if (token != null && token.isUsable()) {
            Log.d(TAG, "Using cached token");
            callback.onSuccess(token);
            return;
        }
        if (!isConfigured()) {
            Log.e(TAG, "ALIYUN_TOKEN_ENDPOINT is missing");
            callback.onError("Missing ALIYUN_TOKEN_ENDPOINT");
            return;
        }

        Log.d(TAG, "Requesting token from endpoint: " + BuildConfig.ALIYUN_TOKEN_ENDPOINT);

        Request request = new Request.Builder()
                .url(BuildConfig.ALIYUN_TOKEN_ENDPOINT)
                .get()
                .build();

        CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Token request failed", e);
                callback.onError(e.getMessage() == null ? "Token request failed" : e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful() || response.body() == null) {
                    Log.e(TAG, "Token request unsuccessful, code=" + response.code());
                    callback.onError("Token request failed: " + response.code());
                    return;
                }

                try {
                    String body = response.body().string();
                    Log.d(TAG, "Token response received, body=" + body);
                    AliyunToken token = parseToken(body);
                    cachedToken = token;
                    Log.d(TAG, "Token parsed successfully");
                    callback.onSuccess(token);
                } catch (JSONException exception) {
                    Log.e(TAG, "Token response JSON parse failed", exception);
                    callback.onError(exception.getMessage() == null
                            ? "Invalid token response"
                            : exception.getMessage());
                } catch (IOException exception) {
                    Log.e(TAG, "Token response validation failed", exception);
                    callback.onError(exception.getMessage() == null
                            ? "Invalid token response"
                            : exception.getMessage());
                }
            }
        });
    }

    private static AliyunToken parseToken(String body) throws JSONException, IOException {
        JSONObject json = new JSONObject(body);
        String token = json.optString("token", "").trim();
        if (token.isEmpty()) {
            token = json.optString("access_token", "").trim();
        }
        if (token.isEmpty()) {
            throw new IOException("Token response missing token");
        }

        long expiresIn = json.optLong("expire_time", 0L);
        if (expiresIn <= 0L) {
            expiresIn = json.optLong("expires_in", 0L);
        }

        long expireAtMs = 0L;
        if (expiresIn > 0L) {
            expireAtMs = System.currentTimeMillis()
                    + Math.max(0L, TimeUnit.SECONDS.toMillis(expiresIn) - TOKEN_REFRESH_GUARD_MS);
        }
        return new AliyunToken(token, expireAtMs);
    }
}
