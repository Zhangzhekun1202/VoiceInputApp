package com.example.voiceinputapp;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public class PcmRecorder {

    public static final int SAMPLE_RATE = 16000;
    public static final int STREAM_FRAME_BYTES = 5120;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int CHANNEL_COUNT = 1;
    private static final int SPEECH_AMPLITUDE_THRESHOLD = 900;

    public interface AudioChunkListener {
        void onAudioChunk(byte[] chunk, int length, boolean hasSpeech);
    }

    private AudioRecord audioRecord;
    private Thread recordThread;
    private volatile boolean recording;
    private ByteArrayOutputStream audioBuffer;
    private long recordingStartedAtMs;
    private long recordedBytes;
    private volatile long firstSpeechAtMs;
    private volatile long lastSpeechAtMs;

    public synchronized boolean isRecording() {
        return recording;
    }

    public long getRecordingDurationMs() {
        long bytes = recordedBytes;
        long bytesPerSecond = (long) SAMPLE_RATE * BYTES_PER_SAMPLE * CHANNEL_COUNT;
        if (bytesPerSecond <= 0) {
            return 0L;
        }
        return bytes * 1000L / bytesPerSecond;
    }

    public boolean hasDetectedSpeech() {
        return firstSpeechAtMs > 0L;
    }

    public long getFirstSpeechAtMs() {
        return firstSpeechAtMs;
    }

    public long getLastSpeechAtMs() {
        return lastSpeechAtMs;
    }

    public synchronized void start() {
        startInternal(null);
    }

    public synchronized void startStreaming(AudioChunkListener listener) {
        startInternal(listener);
    }

    private void startInternal(AudioChunkListener listener) {
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
        recordedBytes = 0L;
        firstSpeechAtMs = 0L;
        lastSpeechAtMs = 0L;
        recordingStartedAtMs = SystemClock.elapsedRealtime();
        recording = true;
        audioRecord.startRecording();

        recordThread = new Thread(() -> {
            byte[] chunk = new byte[minBufferSize];
            ByteArrayOutputStream streamingBuffer = listener == null ? null : new ByteArrayOutputStream();
            while (recording && audioRecord != null) {
                int read = audioRecord.read(chunk, 0, chunk.length);
                if (read > 0) {
                    audioBuffer.write(chunk, 0, read);
                    recordedBytes += read;
                    boolean hasSpeech = containsSpeech(chunk, read);
                    if (hasSpeech) {
                        long now = SystemClock.elapsedRealtime();
                        if (firstSpeechAtMs == 0L) {
                            firstSpeechAtMs = now;
                        }
                        lastSpeechAtMs = now;
                    }
                    if (listener != null) {
                        streamingBuffer.write(chunk, 0, read);
                        while (streamingBuffer.size() >= STREAM_FRAME_BYTES) {
                            byte[] full = streamingBuffer.toByteArray();
                            byte[] frame = Arrays.copyOfRange(full, 0, STREAM_FRAME_BYTES);
                            listener.onAudioChunk(frame, frame.length, containsSpeech(frame, frame.length));

                            streamingBuffer.reset();
                            if (full.length > STREAM_FRAME_BYTES) {
                                streamingBuffer.write(full, STREAM_FRAME_BYTES, full.length - STREAM_FRAME_BYTES);
                            }
                        }
                    }
                }
            }

            if (listener != null && streamingBuffer != null && streamingBuffer.size() > 0) {
                byte[] tail = streamingBuffer.toByteArray();
                listener.onAudioChunk(tail, tail.length, containsSpeech(tail, tail.length));
            }
        }, "pcm-recorder");
        recordThread.start();
    }

    public synchronized byte[] stop() {
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

    public synchronized void release() {
        if (recording) {
            stop();
            return;
        }
        releaseInternal();
    }

    private void releaseInternal() {
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
        recordThread = null;
        audioBuffer = null;
        recordingStartedAtMs = 0L;
    }

    private boolean containsSpeech(byte[] buffer, int length) {
        for (int index = 0; index + 1 < length; index += 2) {
            int sample = (buffer[index] & 0xFF) | (buffer[index + 1] << 8);
            if (Math.abs((short) sample) >= SPEECH_AMPLITUDE_THRESHOLD) {
                return true;
            }
        }
        return false;
    }
}
