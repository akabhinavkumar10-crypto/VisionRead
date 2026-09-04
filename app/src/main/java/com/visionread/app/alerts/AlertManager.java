package com.visionread.app.alerts;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.visionread.app.audio.AudioOutputManager;
import com.visionread.app.processing.HazardDetector;
import com.visionread.app.processing.RoadSignDetector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AlertManager {

    private static final String TAG = "AlertManager";

    public enum Priority {
        CRITICAL(4),
        HIGH(3),
        MEDIUM(2),
        LOW(1);

        public final int value;
        Priority(int value) { this.value = value; }
    }

    private final AudioOutputManager audioOutputManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Map<String, Long> lastSpokenTimestamps = new HashMap<>();
    private static final long COOLDOWN_CRITICAL_MS = 3000;
    private static final long COOLDOWN_HIGH_MS = 6000;
    private static final long COOLDOWN_MED_MS = 9000;

    private Priority currentSpeakingPriority = Priority.LOW;
    private String lastAlertSpoken = "";

    public AlertManager(AudioOutputManager audioOutputManager) {
        this.audioOutputManager = audioOutputManager;
    }

    public synchronized void evaluateHazardsAndSigns(List<HazardDetector.DetectedHazard> hazards, List<RoadSignDetector.RoadSign> signs) {
        long now = System.currentTimeMillis();

        // 1. Check for Critical Hazards
        if (hazards != null) {
            for (HazardDetector.DetectedHazard h : hazards) {
                if (h.level == HazardDetector.HazardLevel.CRITICAL) {
                    handleAlert(h.alertMessage, Priority.CRITICAL, now, COOLDOWN_CRITICAL_MS);
                    return; // Critical takes absolute precedence
                }
            }
        }

        // 2. Check for High Hazards
        if (hazards != null) {
            for (HazardDetector.DetectedHazard h : hazards) {
                if (h.level == HazardDetector.HazardLevel.HIGH) {
                    if (handleAlert(h.alertMessage, Priority.HIGH, now, COOLDOWN_HIGH_MS)) {
                        return;
                    }
                }
            }
        }

        // 3. Check for Road Signs (High / Medium)
        if (signs != null && !signs.isEmpty()) {
            for (RoadSignDetector.RoadSign s : signs) {
                Priority signPriority = (s.name.contains("Stop") || s.name.contains("No Entry")) ? Priority.HIGH : Priority.MEDIUM;
                long cooldown = (signPriority == Priority.HIGH) ? COOLDOWN_HIGH_MS : COOLDOWN_MED_MS;
                if (handleAlert(s.description, signPriority, now, cooldown)) {
                    return;
                }
            }
        }

        // 4. Check for Medium Hazards
        if (hazards != null) {
            for (HazardDetector.DetectedHazard h : hazards) {
                if (h.level == HazardDetector.HazardLevel.MEDIUM) {
                    if (handleAlert(h.alertMessage, Priority.MEDIUM, now, COOLDOWN_MED_MS)) {
                        return;
                    }
                }
            }
        }
    }

    public synchronized boolean handleAlert(String message, Priority priority, long now, long cooldownMs) {
        if (message == null || message.trim().isEmpty()) return false;

        Long lastTime = lastSpokenTimestamps.get(message);
        if (lastTime != null && (now - lastTime < cooldownMs)) {
            // Still in cooldown period
            return false;
        }

        if (audioOutputManager.isSpeaking() && priority.value <= currentSpeakingPriority.value) {
            // Lower or equal priority already speaking
            return false;
        }

        // If higher priority, interrupt current speech
        if (priority.value > currentSpeakingPriority.value && audioOutputManager.isSpeaking()) {
            audioOutputManager.stop();
        }

        lastSpokenTimestamps.put(message, now);
        currentSpeakingPriority = priority;
        lastAlertSpoken = message;

        Log.d(TAG, "Alert: [" + priority.name() + "] " + message);
        audioOutputManager.speak(message);

        // Reset priority after estimated speech duration
        long duration = Math.max(1500, message.split("\\s+").length * 400L);
        mainHandler.postDelayed(() -> {
            if (currentSpeakingPriority == priority) {
                currentSpeakingPriority = Priority.LOW;
            }
        }, duration);

        return true;
    }

    public void announce(String message, Priority priority) {
        handleAlert(message, priority, System.currentTimeMillis(), 2000);
    }

    public String getLastAlertSpoken() {
        return lastAlertSpoken;
    }

    public void clearCooldowns() {
        lastSpokenTimestamps.clear();
        currentSpeakingPriority = Priority.LOW;
    }
}
