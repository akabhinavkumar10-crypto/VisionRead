package com.visionread.app.audio;

import android.os.Handler;
import android.os.Looper;

import com.visionread.app.communication.DeviceConnectionManager;

public class GlassesAudioOutput implements AudioOutput {

    private final DeviceConnectionManager connectionManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isSpeaking = false;
    private float speechRate = 0.85f;
    private float speechVolume = 1.0f;

    public GlassesAudioOutput() {
        this.connectionManager = DeviceConnectionManager.getInstance();
    }

    @Override
    public void init(Runnable onReady) {
        if (onReady != null) {
            mainHandler.post(onReady);
        }
    }

    @Override
    public void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;
        isSpeaking = true;
        connectionManager.sendAudio(text);
        // Estimate speech duration for basic state tracking
        long estimatedMs = (long) ((text.split("\\s+").length / (2.5f * speechRate)) * 1000) + 500;
        mainHandler.postDelayed(() -> isSpeaking = false, estimatedMs);
    }

    @Override
    public void speakWithCallback(String text, UtteranceCallback callback) {
        if (text == null || text.trim().isEmpty()) {
            if (callback != null) callback.onError();
            return;
        }

        isSpeaking = true;
        if (callback != null) callback.onStart();

        connectionManager.sendAudio(text);

        String[] words = text.split("\\s+");
        final int total = words.length;
        long wordIntervalMs = (long) (350 / speechRate);

        for (int i = 1; i <= total; i++) {
            final int wordIndex = i;
            final int pct = (int) ((wordIndex / (float) total) * 100);
            mainHandler.postDelayed(() -> {
                if (callback != null) {
                    callback.onProgress(wordIndex, total, pct);
                }
            }, wordIndex * wordIntervalMs);
        }

        long totalDurationMs = (total * wordIntervalMs) + 400;
        mainHandler.postDelayed(() -> {
            isSpeaking = false;
            if (callback != null) {
                callback.onDone();
            }
        }, totalDurationMs);
    }

    @Override
    public void stop() {
        isSpeaking = false;
        connectionManager.sendControl("STOP_AUDIO");
    }

    @Override
    public void setSpeechRate(float rate) {
        this.speechRate = rate;
        connectionManager.sendControl("SET_RATE:" + rate);
    }

    @Override
    public void setSpeechVolume(float volume) {
        this.speechVolume = volume;
        connectionManager.sendControl("SET_VOL:" + volume);
    }

    @Override
    public boolean isSpeaking() {
        return isSpeaking;
    }

    @Override
    public void shutdown() {
        stop();
    }

    @Override
    public String getOutputName() {
        return "Glasses Speaker";
    }
}
