package com.example.voiceinputapp;

public interface SpeechRecognitionClient {

    interface Listener {
        void onReady();

        void onPartialResult(String text);

        void onFinalResult(String text);

        void onError(String message);

        void onClosed();
    }

    void connect();

    boolean isReady();

    boolean usesExternalAudioInput();

    void sendAudio(byte[] data, int length);

    void finish();

    void cancel();
}
