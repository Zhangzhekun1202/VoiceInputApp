package com.example.voiceinputapp;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class BaiduSpeechClient {

    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
    private static final String ASR_URL = "https://vop.baidu.com/server_api";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    private final String apiKey;
    private final String secretKey;
    private final String cuid;

    public BaiduSpeechClient(String apiKey, String secretKey, String cuid) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.cuid = cuid;
    }

    public String recognize(byte[] pcmData, int sampleRate) throws Exception {
        if (pcmData == null || pcmData.length == 0) {
            throw new IllegalArgumentException("empty audio payload");
        }

        String token = requestAccessToken();
        JSONObject payload = new JSONObject();
        payload.put("format", "pcm");
        payload.put("rate", sampleRate);
        payload.put("channel", 1);
        payload.put("cuid", cuid);
        payload.put("token", token);
        payload.put("dev_pid", 1537);
        payload.put("speech", Base64.encodeToString(pcmData, Base64.NO_WRAP));
        payload.put("len", pcmData.length);

        HttpURLConnection connection = (HttpURLConnection) new URL(ASR_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");

        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(body);
        }

        String response = readResponse(connection);
        JSONObject json = new JSONObject(response);
        int errNo = json.optInt("err_no", -1);
        if (errNo != 0) {
            throw new IllegalStateException(json.optString("err_msg", "Baidu ASR error " + errNo));
        }

        JSONArray results = json.optJSONArray("result");
        if (results == null || results.length() == 0) {
            return "";
        }
        return results.optString(0, "").trim();
    }

    private String requestAccessToken() throws Exception {
        String query = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name())
                + "&client_secret=" + URLEncoder.encode(secretKey, StandardCharsets.UTF_8.name());

        HttpURLConnection connection = (HttpURLConnection) new URL(TOKEN_URL + "?" + query).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);

        String response = readResponse(connection);
        JSONObject json = new JSONObject(response);
        String token = json.optString("access_token", "");
        if (token.isEmpty()) {
            throw new IllegalStateException(json.optString("error_description", "Missing access_token"));
        }
        return token;
    }

    private String readResponse(HttpURLConnection connection) throws Exception {
        InputStream stream = connection.getResponseCode() >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();

        if (stream == null) {
            throw new IllegalStateException("Empty HTTP response");
        }

        try (InputStream inputStream = stream;
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, count);
            }
            return outputStream.toString(StandardCharsets.UTF_8.name());
        } finally {
            connection.disconnect();
        }
    }
}
