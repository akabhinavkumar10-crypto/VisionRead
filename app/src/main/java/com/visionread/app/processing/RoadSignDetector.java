package com.visionread.app.processing;

import android.graphics.Rect;
import android.util.Log;

import com.google.mlkit.vision.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RoadSignDetector {

    private static final String TAG = "RoadSignDetector";

    public static class RoadSign {
        public final String name;
        public final String description;
        public final int confidence;
        public final String spatialPosition; // "left", "ahead", "right"
        public final Rect boundingBox;

        public RoadSign(String name, String description, int confidence, String spatialPosition, Rect boundingBox) {
            this.name = name;
            this.description = description;
            this.confidence = confidence;
            this.spatialPosition = spatialPosition;
            this.boundingBox = boundingBox;
        }
    }

    private static final Pattern SPEED_PATTERN = Pattern.compile("\\b(SPEED\\s*LIMIT|SPEED|MAX|LIMIT)\\s*([0-9]{2,3})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUM_PATTERN = Pattern.compile("^([1-9][0-9]|1[0-2][0-9])$");

    public List<RoadSign> detectSigns(Text visionText, int imageWidth, int imageHeight) {
        List<RoadSign> detectedSigns = new ArrayList<>();
        if (visionText == null) return detectedSigns;

        String fullTextUpper = visionText.getText().toUpperCase(Locale.US);

        // Check for specific road sign patterns in blocks
        for (Text.TextBlock block : visionText.getTextBlocks()) {
            String blockText = block.getText().toUpperCase(Locale.US).trim();
            Rect box = block.getBoundingBox();
            String position = determineSpatialPosition(box, imageWidth);

            // STOP SIGN
            if (blockText.matches(".*\\bSTOP\\b.*")) {
                detectedSigns.add(new RoadSign("Stop Sign", "Stop sign ahead", 95, position, box));
                continue;
            }

            // NO ENTRY / DO NOT ENTER
            if (blockText.contains("NO ENTRY") || blockText.contains("DO NOT ENTER")) {
                detectedSigns.add(new RoadSign("No Entry", "No entry sign ahead", 90, position, box));
                continue;
            }

            // ONE WAY
            if (blockText.contains("ONE WAY")) {
                detectedSigns.add(new RoadSign("One Way", "One way sign " + position, 90, position, box));
                continue;
            }

            // YIELD / GIVE WAY
            if (blockText.matches(".*\\b(YIELD|GIVE WAY)\\b.*")) {
                detectedSigns.add(new RoadSign("Yield Sign", "Yield sign " + position, 90, position, box));
                continue;
            }

            // SPEED LIMIT
            Matcher speedMatcher = SPEED_PATTERN.matcher(blockText);
            if (speedMatcher.find()) {
                String speed = speedMatcher.group(2);
                detectedSigns.add(new RoadSign("Speed Limit", "Speed limit " + speed + " sign " + position, 90, position, box));
                continue;
            }

            // PEDESTRIAN CROSSING / CROSSWALK
            if (blockText.contains("CROSSWALK") || blockText.contains("PED XING") || blockText.contains("PEDESTRIAN CROSSING") || blockText.contains("CROSSING")) {
                detectedSigns.add(new RoadSign("Pedestrian Crossing", "Pedestrian crossing sign " + position, 90, position, box));
                continue;
            }

            // SCHOOL ZONE
            if (blockText.contains("SCHOOL ZONE") || blockText.contains("SCHOOL") || blockText.contains("CHILDREN CROSSING")) {
                detectedSigns.add(new RoadSign("School Zone", "School zone warning " + position, 85, position, box));
                continue;
            }

            // CAUTION / WARNING / DANGER / MEN AT WORK
            if (blockText.contains("CAUTION") || blockText.contains("DANGER") || blockText.contains("WARNING") || blockText.contains("ROAD WORK") || blockText.contains("WORK AHEAD")) {
                detectedSigns.add(new RoadSign("Warning Sign", "Warning sign " + position, 85, position, box));
                continue;
            }

            // NO PARKING
            if (blockText.contains("NO PARKING") || blockText.contains("NO STANDING")) {
                detectedSigns.add(new RoadSign("No Parking", "No parking sign " + position, 85, position, box));
                continue;
            }

            // DEAD END
            if (blockText.contains("DEAD END") || blockText.contains("NO OUTLET")) {
                detectedSigns.add(new RoadSign("Dead End", "Dead end ahead", 85, position, box));
                continue;
            }
        }

        return detectedSigns;
    }

    private String determineSpatialPosition(Rect box, int imageWidth) {
        if (box == null || imageWidth <= 0) return "ahead";
        int centerX = box.centerX();
        float ratio = (float) centerX / imageWidth;
        if (ratio < 0.35f) {
            return "on your left";
        } else if (ratio > 0.65f) {
            return "on your right";
        } else {
            return "ahead";
        }
    }
}
