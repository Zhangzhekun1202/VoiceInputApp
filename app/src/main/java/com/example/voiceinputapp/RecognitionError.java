package com.example.voiceinputapp;

final class RecognitionError {

    private RecognitionError() {
    }

    static String messageOf(Throwable throwable) {
        return throwable == null || throwable.getMessage() == null ? "" : throwable.getMessage();
    }
}
