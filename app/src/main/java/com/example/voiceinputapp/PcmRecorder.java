package com.example.voiceinputapp;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;

public class PcmRecorder {

    public static final int SAMPLE_RATE = 16000;

    private AudioRecord audioRecord;
    private Thread recordThread;
    private volatile boolean recording;
    private ByteArrayOutputStream audioBuffer;

    public boolean isRecording() {
        return recording;
    }

    public void start() {
        if (recording) {
            throw new IllegalStateException("Recorder is already running");
        }

        int minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (minBufferSize <= 0) {
            throw new IllegalStateException("AudioRecord is not supported");
        }

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize * 2
        );
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            releaseInternal();
            throw new IllegalStateException("AudioRecord failed to initialize");
        }

        audioBuffer = new ByteArrayOutputStream();
        recording = true;
        audioRecord.startRecording();

        recordThread = new Thread(() -> {
            byte[] chunk = new byte[minBufferSize];
            while (recording && audioRecord != null) {
                int read = audioRecord.read(chunk, 0, chunk.length);
                if (read > 0) {
                    audioBuffer.write(chunk, 0, read);
                }
            }
        }, "pcm-recorder");
        recordThread.start();
    }

    public byte[] stop() {
        if (!recording) {
            return new byte[0];
        }

        recording = false;
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (IllegalStateException ignored) {
            }
        }

        if (recordThread != null) {
            try {
                recordThread.join(1000);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        byte[] audio = audioBuffer != null ? audioBuffer.toByteArray() : new byte[0];
        releaseInternal();
        return audio;
    }

    public void release() {
        recording = false;
        releaseInternal();
    }

    private void releaseInternal() {
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
        recordThread = null;
        audioBuffer = null;
    }
}
