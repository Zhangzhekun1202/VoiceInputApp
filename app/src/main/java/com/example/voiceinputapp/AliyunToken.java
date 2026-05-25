package com.example.voiceinputapp;

public final class AliyunToken {
    private final String value;
    private final long expireAtMs;

    public AliyunToken(String value, long expireAtMs) {
        this.value = value == null ? "" : value.trim();
        this.expireAtMs = expireAtMs;
    }

    public String getValue() {
        return value;
    }

    public boolean isUsable() {
        return !value.isEmpty()
                && (expireAtMs <= 0L || System.currentTimeMillis() < expireAtMs);
    }
}
