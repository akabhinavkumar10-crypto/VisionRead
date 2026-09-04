package com.visionread.app.processing;

import android.graphics.Rect;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;

import java.util.ArrayList;
import java.util.List;

public class HazardDetector {

    private static final String TAG = "HazardDetector";

    public enum HazardLevel {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW
    }

    public static class DetectedHazard {
        public final String label;
        public final String alertMessage;
        public final HazardLevel level;
        public final String spatialPosition; // "on your left", "ahead", "on your right"
        public final Rect boundingBox;
        public final float confidence;
        public final boolean isClose;

        public DetectedHazard(String label, String alertMessage, HazardLevel level, String spatialPosition, Rect boundingBox, float confidence, boolean isClose) {
            this.label = label;
            this.alertMessage = alertMessage;
            this.level = level;
            this.spatialPosition = spatialPosition;
            this.boundingBox = boundingBox;
            this.confidence = confidence;
            this.isClose = isClose;
        }
    }

    public interface HazardCallback {
        void onHazardsDetected(List<DetectedHazard> hazards);
        void onError(Exception e);
    }

    private final ObjectDetector detector;

    public HazardDetector() {
        ObjectDetectorOptions options = new ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableClassification()
                .enableMultipleObjects()
                .build();
        detector = ObjectDetection.getClient(options);
    }

    public void detectHazards(InputImage image, HazardCallback callback) {
        if (image == null || detector == null) {
            if (callback != null) callback.onHazardsDetected(new ArrayList<>());
            return;
        }

        final int imgWidth = image.getWidth();
        final int imgHeight = image.getHeight();

        detector.process(image)
                .addOnSuccessListener(detectedObjects -> {
                    List<DetectedHazard> hazards = new ArrayList<>();
                    for (DetectedObject obj : detectedObjects) {
                        Rect box = obj.getBoundingBox();
                        String spatialPos = determineSpatialPosition(box, imgWidth);
                        boolean isClose = isObjectClose(box, imgWidth, imgHeight);

                        String label = "Obstacle";
                        float conf = 0.8f;

                        if (!obj.getLabels().isEmpty()) {
                            DetectedObject.Label firstLabel = obj.getLabels().get(0);
                            label = firstLabel.getText();
                            conf = firstLabel.getConfidence();
                        }

                        HazardLevel level = determineHazardLevel(label, isClose, spatialPos);
                        String alertMsg = buildAlertMessage(label, spatialPos, isClose);

                        hazards.add(new DetectedHazard(label, alertMsg, level, spatialPos, box, conf, isClose));
                    }
                    if (callback != null) callback.onHazardsDetected(hazards);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Hazard detection failed: " + e.getMessage());
                    if (callback != null) callback.onError(e);
                });
    }

    private String determineSpatialPosition(Rect box, int imageWidth) {
        if (box == null || imageWidth <= 0) return "ahead";
        int centerX = box.centerX();
        float ratio = (float) centerX / imageWidth;
        if (ratio < 0.33f) {
            return "on your left";
        } else if (ratio > 0.67f) {
            return "on your right";
        } else {
            return "ahead";
        }
    }

    private boolean isObjectClose(Rect box, int imageWidth, int imageHeight) {
        if (box == null || imageWidth <= 0 || imageHeight <= 0) return false;
        float areaRatio = (float) (box.width() * box.height()) / (imageWidth * imageHeight);
        return areaRatio > 0.12f; // Takes up > 12% of screen area
    }

    private HazardLevel determineHazardLevel(String label, boolean isClose, String spatialPos) {
        String l = label.toLowerCase();
        boolean isAhead = "ahead".equals(spatialPos);

        if (isClose && isAhead) {
            return HazardLevel.CRITICAL;
        }

        if (l.contains("person") || l.contains("human") || l.contains("pedestrian")) {
            return isClose ? HazardLevel.CRITICAL : HazardLevel.HIGH;
        }

        if (l.contains("vehicle") || l.contains("car") || l.contains("bus") || l.contains("truck") || l.contains("motorcycle")) {
            return isClose ? HazardLevel.CRITICAL : (isAhead ? HazardLevel.HIGH : HazardLevel.MEDIUM);
        }

        if (isClose) {
            return HazardLevel.HIGH;
        }

        return HazardLevel.MEDIUM;
    }

    private String buildAlertMessage(String label, String spatialPos, boolean isClose) {
        String l = label.toLowerCase();
        String item = "Obstacle";
        if (l.contains("person") || l.contains("human")) item = "Person";
        else if (l.contains("car") || l.contains("vehicle")) item = "Vehicle";
        else if (l.contains("bicycle") || l.contains("bike")) item = "Bicycle";
        else if (l.contains("food") || l.contains("goods")) item = "Object";

        if (isClose && "ahead".equals(spatialPos)) {
            return "Caution, " + item.toLowerCase() + " close ahead";
        } else {
            return item + " " + spatialPos;
        }
    }

    public void close() {
        if (detector != null) {
            detector.close();
        }
    }
}
