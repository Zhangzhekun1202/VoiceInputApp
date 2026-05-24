package com.example.voiceinputapp;

final class RecognitionErrorMapper {

    private RecognitionErrorMapper() {
    }

    static int toStatusMessageRes(String rawMessage) {
        String message = rawMessage == null ? "" : rawMessage.toLowerCase();
        if (message.contains("network") || message.contains("timeout")) {
            return R.string.error_network;
        }
        if (message.contains("not find effective speech")
                || message.contains("info:-4")
                || message.contains("speech quality error")
                || message.contains("empty audio")
                || message.contains("too short")) {
            return R.string.error_speech_timeout;
        }
        if (message.contains("access_token")
                || message.contains("api key")
                || message.contains("secret")) {
            return R.string.status_config_missing;
        }
        return 0;
    }
}
