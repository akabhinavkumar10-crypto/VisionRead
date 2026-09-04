package com.visionread.app.audio;

import android.content.Context;

import com.visionread.app.communication.DeviceConnectionManager;

public class AudioOutputManager {

    private static AudioOutputManager instance;
    private final PhoneAudioOutput phoneAudio;
    private final GlassesAudioOutput glassesAudio;
    private boolean preferGlasses = true;
    private float speechRate = 0.85f;
    private float speechVolume = 1.0f;

    private AudioOutputManager(Context context) {
        phoneAudio = new PhoneAudioOutput(context.getApplicationContext());
        glassesAudio = new GlassesAudioOutput();
    }

    public static synchronized AudioOutputManager getInstance(Context context) {
        if (instance == null) {
            instance = new AudioOutputManager(context);
        }
        return instance;
    }

    public void init(Runnable onReady) {
        phoneAudio.init(onReady);
        glassesAudio.init(null);
    }

    public void setPreferGlasses(boolean prefer) {
        this.preferGlasses = prefer;
    }

    public boolean isUsingGlassesAudio() {
        return preferGlasses && DeviceConnectionManager.getInstance().isConnected();
    }

    public AudioOutput getActiveOutput() {
        if (isUsingGlassesAudio()) {
            return glassesAudio;
        }
        return phoneAudio;
    }

    public void speak(String text) {
        getActiveOutput().speak(text);
    }

    public void speakWithCallback(String text, AudioOutput.UtteranceCallback callback) {
        getActiveOutput().speakWithCallback(text, callback);
    }

    public void stop() {
        phoneAudio.stop();
        glassesAudio.stop();
    }

    public void setSpeechRate(float rate) {
        this.speechRate = rate;
        phoneAudio.setSpeechRate(rate);
        glassesAudio.setSpeechRate(rate);
    }

    public void setSpeechVolume(float volume) {
        this.speechVolume = volume;
        phoneAudio.setSpeechVolume(volume);
        glassesAudio.setSpeechVolume(volume);
    }

    public boolean isSpeaking() {
        return phoneAudio.isSpeaking() || glassesAudio.isSpeaking();
    }

    public void shutdown() {
        phoneAudio.shutdown();
        glassesAudio.shutdown();
    }

    public String getCurrentOutputName() {
        return getActiveOutput().getOutputName();
    }
}
