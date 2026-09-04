package com.visionread.app.processing;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class VisionProcessor {

    private static final String TAG = "VisionProcessor";

    public static class VisionResult {
        public final Text ocrText;
        public final List<RoadSignDetector.RoadSign> roadSigns;
        public final List<HazardDetector.DetectedHazard> hazards;
        public final int imageWidth;
        public final int imageHeight;
        public final long processingTimeMs;

        public VisionResult(Text ocrText, List<RoadSignDetector.RoadSign> roadSigns, List<HazardDetector.DetectedHazard> hazards, int imageWidth, int imageHeight, long processingTimeMs) {
            this.ocrText = ocrText;
            this.roadSigns = roadSigns != null ? roadSigns : new ArrayList<>();
            this.hazards = hazards != null ? hazards : new ArrayList<>();
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.processingTimeMs = processingTimeMs;
        }
    }

    public interface VisionResultListener {
        void onVisionResult(VisionResult result);
        void onError(Exception e);
    }

    private final TextRecognizer textRecognizer;
    private final RoadSignDetector roadSignDetector;
    private final HazardDetector hazardDetector;

    private VisionResultListener listener;
    private final AtomicBoolean isBusy = new AtomicBoolean(false);
    private boolean roadModeEnabled = false;
    private long lastHazardCheckTime = 0;
    private static final long HAZARD_INTERVAL_MS = 400; // ~2.5 FPS for hazard detection to save power
    private final ExecutorService processingExecutor = Executors.newSingleThreadExecutor();

    public VisionProcessor() {
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        roadSignDetector = new RoadSignDetector();
        hazardDetector = new HazardDetector();
    }

    public void setListener(VisionResultListener listener) {
        this.listener = listener;
    }

    public void setRoadModeEnabled(boolean enabled) {
        this.roadModeEnabled = enabled;
    }

    public boolean isRoadModeEnabled() {
        return roadModeEnabled;
    }

    public boolean isBusy() {
        return isBusy.get();
    }

    public void processFrame(InputImage image) {
        if (image == null) return;
        if (!isBusy.compareAndSet(false, true)) {
            // Drop frame if still processing previous
            return;
        }

        long startTime = System.currentTimeMillis();
        int width = image.getWidth();
        int height = image.getHeight();

        textRecognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    List<RoadSignDetector.RoadSign> signs = roadSignDetector.detectSigns(visionText, width, height);

                    long now = System.currentTimeMillis();
                    if (roadModeEnabled && (now - lastHazardCheckTime >= HAZARD_INTERVAL_MS)) {
                        lastHazardCheckTime = now;
                        hazardDetector.detectHazards(image, new HazardDetector.HazardCallback() {
                            @Override
                            public void onHazardsDetected(List<HazardDetector.DetectedHazard> hazards) {
                                long duration = System.currentTimeMillis() - startTime;
                                VisionResult result = new VisionResult(visionText, signs, hazards, width, height, duration);
                                isBusy.set(false);
                                if (listener != null) listener.onVisionResult(result);
                            }

                            @Override
                            public void onError(Exception e) {
                                long duration = System.currentTimeMillis() - startTime;
                                VisionResult result = new VisionResult(visionText, signs, new ArrayList<>(), width, height, duration);
                                isBusy.set(false);
                                if (listener != null) listener.onVisionResult(result);
                            }
                        });
                    } else {
                        long duration = System.currentTimeMillis() - startTime;
                        VisionResult result = new VisionResult(visionText, signs, new ArrayList<>(), width, height, duration);
                        isBusy.set(false);
                        if (listener != null) listener.onVisionResult(result);
                    }
                })
                .addOnFailureListener(e -> {
                    isBusy.set(false);
                    Log.e(TAG, "OCR Process failed: " + e.getMessage());
                    if (listener != null) listener.onError(e);
                });
    }

    public void processJpegBytes(byte[] jpegBytes) {
        if (jpegBytes == null || jpegBytes.length == 0) return;
        if (isBusy.get()) return;

        processingExecutor.execute(() -> {
            try {
                Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
                if (bitmap != null) {
                    InputImage image = InputImage.fromBitmap(bitmap, 0);
                    processFrame(image);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to decode remote JPEG frame: " + e.getMessage());
            }
        });
    }

    public void close() {
        if (textRecognizer != null) textRecognizer.close();
        if (hazardDetector != null) hazardDetector.close();
        processingExecutor.shutdown();
    }
}
