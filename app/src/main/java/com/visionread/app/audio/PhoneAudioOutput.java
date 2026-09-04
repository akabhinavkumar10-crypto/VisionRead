package com.visionread.app.audio;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.HashMap;
import java.util.Locale;

public class PhoneAudioOutput implements AudioOutput {

    private static final String TAG = "PhoneAudioOutput";

    private final Context context;
    private TextToSpeech tts;
    private boolean isReady = false;
    private float speechRate = 0.85f;
    private float speechVolume = 1.0f;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private UtteranceCallback currentCallback;

    public PhoneAudioOutput(Context context) {
        this.context = context;
    }

    @Override
    public void init(Runnable onReady) {
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                tts.setSpeechRate(speechRate);
                isReady = true;
                if (onReady != null) {
                    mainHandler.post(onReady);
                }
            } else {
                Log.e(TAG, "TTS Initialization failed with status: " + status);
            }
        });
    }

    @Override
    public void speak(String text) {
        if (tts == null || !isReady || text == null || text.trim().isEmpty()) return;
        tts.stop();
        String id = "phone_s_" + System.currentTimeMillis();
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, speechVolume);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id);
    }

    @Override
    public void speakWithCallback(String text, UtteranceCallback callback) {
        if (tts == null || !isReady || text == null || text.trim().isEmpty()) {
            if (callback != null) callback.onError();
            return;
        }

        this.currentCallback = callback;
        tts.stop();
        tts.setSpeechRate(speechRate);

        String[] words = text.split("\\s+");
        final int total = words.length;

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            int wordCount = 0;

            @Override
            public void onStart(String utteranceId) {
                mainHandler.post(() -> {
                    if (currentCallback != null) currentCallback.onStart();
                });
            }

            @Override
            public void onRangeStart(String utteranceId, int start, int end, int frame) {
                wordCount++;
                int pct = Math.min(100, (int) ((wordCount / (float) Math.max(1, total)) * 100));
                final int currentWord = wordCount;
                mainHandler.post(() -> {
                    if (currentCallback != null) currentCallback.onProgress(currentWord, total, pct);
                });
            }

            @Override
            public void onDone(String utteranceId) {
                mainHandler.post(() -> {
                    if (currentCallback != null) {
                        currentCallback.onProgress(total, total, 100);
                        currentCallback.onDone();
                    }
                });
            }

            @Override
            public void onError(String utteranceId) {
                mainHandler.post(() -> {
                    if (currentCallback != null) currentCallback.onError();
                });
            }
        });

        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, speechVolume);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "cb_" + System.currentTimeMillis());
    }

    @Override
    public void stop() {
        if (tts != null) {
            tts.stop();
        }
    }

    @Override
    public void setSpeechRate(float rate) {
        this.speechRate = rate;
        if (tts != null && isReady) {
            tts.setSpeechRate(rate);
        }
    }

    @Override
    public void setSpeechVolume(float volume) {
        this.speechVolume = volume;
    }

    @Override
    public boolean isSpeaking() {
        return tts != null && tts.isSpeaking();
    }

    @Override
    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }

    @Override
    public String getOutputName() {
        return "Phone Speaker";
    }
}
