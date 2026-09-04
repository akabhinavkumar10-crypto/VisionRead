package com.visionread.app.glasses;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.visionread.app.R;
import com.visionread.app.communication.ConnectionTransport;
import com.visionread.app.communication.DeviceConnectionManager;
import com.visionread.app.communication.WifiTransport;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class GlassesActivity extends AppCompatActivity implements DeviceConnectionManager.ConnectionCallback {

    private static final String TAG = "GlassesActivity";
    private static final int CAMERA_PERMISSION_CODE = 200;

    private PreviewView cameraPreview;
    private TextView statusTitle, ipLabel, streamingStats, camBadge, statusIcon;
    private Button btnAction, btnRepeat, btnEmergency, exitGlassesBtn;

    private DeviceConnectionManager connectionManager;
    private TextToSpeech glassesTts;
    private ExecutorService cameraExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final AtomicLong framesSentCount = new AtomicLong(0);
    private long lastFrameSentTimestamp = 0;
    private static final long MIN_FRAME_INTERVAL_MS = 80; // ~12 FPS throttle for power & bandwidth efficiency

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_glasses);

        bindViews();
        initTts();
        initCommunication();
        setupButtonListeners();

        cameraExecutor = Executors.newSingleThreadExecutor();

        if (checkCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
        }
    }

    private void bindViews() {
        cameraPreview    = findViewById(R.id.glassesCameraPreview);
        statusTitle      = findViewById(R.id.glassesStatusTitle);
        ipLabel          = findViewById(R.id.glassesIpLabel);
        streamingStats   = findViewById(R.id.glassesStreamingStats);
        camBadge         = findViewById(R.id.glassesCamBadge);
        statusIcon       = findViewById(R.id.glassesStatusIcon);
        btnAction        = findViewById(R.id.btnGlassesAction);
        btnRepeat        = findViewById(R.id.btnGlassesRepeat);
        btnEmergency     = findViewById(R.id.btnGlassesEmergency);
        exitGlassesBtn   = findViewById(R.id.exitGlassesBtn);

        exitGlassesBtn.setOnClickListener(v -> finish());
    }

    private void initTts() {
        glassesTts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                glassesTts.setLanguage(Locale.US);
            }
        });
    }

    private void initCommunication() {
        connectionManager = DeviceConnectionManager.getInstance();
        connectionManager.setCallback(this);
        connectionManager.setTransportMode(DeviceConnectionManager.TransportMode.WIFI);
        connectionManager.startServer(WifiTransport.DEFAULT_PORT);

        String ip = getLocalIpAddress();
        ipLabel.setText("Glasses IP: " + (ip != null ? ip : "Unknown") + " : " + WifiTransport.DEFAULT_PORT);
    }

    private void setupButtonListeners() {
        // Button 1 — Action / Capture
        btnAction.setOnClickListener(v -> {
            connectionManager.sendButtonEvent(ConnectionTransport.BUTTON_ACTION, ConnectionTransport.CLICK_SHORT);
            Toast.makeText(this, "Button 1 Pressed: Action / Capture", Toast.LENGTH_SHORT).show();
        });
        btnAction.setOnLongClickListener(v -> {
            connectionManager.sendButtonEvent(ConnectionTransport.BUTTON_ACTION, ConnectionTransport.CLICK_LONG);
            Toast.makeText(this, "Button 1 Long Pressed", Toast.LENGTH_SHORT).show();
            return true;
        });

        // Button 2 — Repeat
        btnRepeat.setOnClickListener(v -> {
            connectionManager.sendButtonEvent(ConnectionTransport.BUTTON_REPEAT, ConnectionTransport.CLICK_SHORT);
            Toast.makeText(this, "Button 2 Pressed: Repeat Last", Toast.LENGTH_SHORT).show();
        });
        btnRepeat.setOnLongClickListener(v -> {
            connectionManager.sendButtonEvent(ConnectionTransport.BUTTON_REPEAT, ConnectionTransport.CLICK_LONG);
            Toast.makeText(this, "Button 2 Long Pressed", Toast.LENGTH_SHORT).show();
            return true;
        });

        // Button 3 — Emergency
        btnEmergency.setOnClickListener(v -> {
            connectionManager.sendButtonEvent(ConnectionTransport.BUTTON_EMERGENCY, ConnectionTransport.CLICK_SHORT);
            Toast.makeText(this, "Button 3 Pressed: Emergency Alert", Toast.LENGTH_SHORT).show();
        });
        btnEmergency.setOnLongClickListener(v -> {
            connectionManager.sendButtonEvent(ConnectionTransport.BUTTON_EMERGENCY, ConnectionTransport.CLICK_LONG);
            Toast.makeText(this, "Button 3 Long Pressed: Urgent Alert", Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            connectionManager.sendButtonEvent(ConnectionTransport.BUTTON_ACTION, ConnectionTransport.CLICK_SHORT);
            Toast.makeText(this, "Volume Down -> Button 1 (Action)", Toast.LENGTH_SHORT).show();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            connectionManager.sendButtonEvent(ConnectionTransport.BUTTON_REPEAT, ConnectionTransport.CLICK_SHORT);
            Toast.makeText(this, "Volume Up -> Button 2 (Repeat)", Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::processAndStreamFrame);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera init error: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @androidx.camera.core.ExperimentalGetImage
    private void processAndStreamFrame(@NonNull ImageProxy imageProxy) {
        try {
            long now = System.currentTimeMillis();
            if (now - lastFrameSentTimestamp < MIN_FRAME_INTERVAL_MS || !connectionManager.isConnected()) {
                imageProxy.close();
                return;
            }

            Image image = imageProxy.getImage();
            if (image == null) {
                imageProxy.close();
                return;
            }

            byte[] jpeg = imageToJpegByteArray(imageProxy);
            if (jpeg != null && jpeg.length > 0) {
                connectionManager.sendFrame(jpeg);
                lastFrameSentTimestamp = now;
                long count = framesSentCount.incrementAndGet();

                if (count % 15 == 0) {
                    mainHandler.post(() -> streamingStats.setText("Frames Sent: " + count + " | Speaker: Ready"));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Frame streaming error: " + e.getMessage());
        } finally {
            imageProxy.close();
        }
    }

    private byte[] imageToJpegByteArray(ImageProxy imageProxy) {
        try {
            @SuppressLint("UnsafeOptInUsageError")
            Image image = imageProxy.getImage();
            if (image == null) return null;

            Image.Plane[] planes = image.getPlanes();
            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();

            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();

            byte[] nv21 = new byte[ySize + uSize + vSize];
            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);

            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, imageProxy.getWidth(), imageProxy.getHeight(), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, imageProxy.getWidth(), imageProxy.getHeight()), 65, out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        if (sAddr != null && sAddr.indexOf(':') < 0) {
                            return sAddr;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    // ── ConnectionCallback ──

    @Override
    public void onConnected(String address, String transportType) {
        statusIcon.setText("🔗");
        statusTitle.setText("Connected to Brain Phone (" + transportType + ")");
        camBadge.setText("● STREAMING LIVE TO BRAIN (" + transportType + ")");
        Toast.makeText(this, "Brain Phone connected!", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDisconnected(String reason) {
        statusIcon.setText("📡");
        statusTitle.setText("Waiting for Brain Phone...");
        camBadge.setText("● STREAMING IDLE (AWAITING CONNECTION)");
        Toast.makeText(this, "Brain Phone disconnected", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onFrameReceived(byte[] jpegBytes, long latencyMs) {
        // Device B transmits frames, does not process incoming frames
    }

    @Override
    public void onAudioReceived(String textOrAudioCmd) {
        if (textOrAudioCmd == null || textOrAudioCmd.isEmpty()) return;

        if (textOrAudioCmd.startsWith("SET_RATE:")) {
            try {
                float rate = Float.parseFloat(textOrAudioCmd.substring("SET_RATE:".length()));
                if (glassesTts != null) glassesTts.setSpeechRate(rate);
            } catch (Exception ignored) {}
            return;
        }

        if (textOrAudioCmd.equals("STOP_AUDIO")) {
            if (glassesTts != null) glassesTts.stop();
            return;
        }

        // Play spoken text directly through Glasses speaker
        if (glassesTts != null) {
            glassesTts.stop();
            glassesTts.speak(textOrAudioCmd, TextToSpeech.QUEUE_FLUSH, null, "g_tts_" + System.currentTimeMillis());
        }
    }

    @Override
    public void onButtonEvent(int buttonId, int clickType) {
        // Device B sends buttons, does not receive them
    }

    @Override
    public void onStatusUpdate(String status) {
        Log.d(TAG, "Status: " + status);
    }

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (glassesTts != null) {
            glassesTts.stop();
            glassesTts.shutdown();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (connectionManager != null) {
            connectionManager.disconnect();
        }
    }
}
