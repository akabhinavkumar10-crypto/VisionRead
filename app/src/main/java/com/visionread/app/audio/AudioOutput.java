package com.visionread.app.audio;

public interface AudioOutput {

    interface UtteranceCallback {
        void onStart();
        void onProgress(int wordIndex, int totalWords, int percent);
        void onDone();
        void onError();
    }

    void init(Runnable onReady);

    void speak(String text);

    void speakWithCallback(String text, UtteranceCallback callback);

    void stop();

    void setSpeechRate(float rate);

    void setSpeechVolume(float volume);

    boolean isSpeaking();

    void shutdown();

    String getOutputName();
}
