package com.visionread.app;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
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
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.visionread.app.alerts.AlertManager;
import com.visionread.app.audio.AudioOutput;
import com.visionread.app.audio.AudioOutputManager;
import com.visionread.app.audio.StoryReader;
import com.visionread.app.communication.ConnectionTransport;
import com.visionread.app.communication.DeviceConnectionManager;
import com.visionread.app.communication.WifiTransport;
import com.visionread.app.glasses.GlassesActivity;
import com.visionread.app.input.GlassesButtonManager;
import com.visionread.app.processing.HazardDetector;
import com.visionread.app.processing.RoadSignDetector;
import com.visionread.app.processing.VisionProcessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements DeviceConnectionManager.ConnectionCallback, GlassesButtonManager.ButtonEventListener, VisionProcessor.VisionResultListener {

    private static final String TAG = "VisionRead";
    private static final int CAMERA_PERMISSION_CODE = 100;
    private static final int RECORD_AUDIO_PERMISSION_CODE = 101;
    private static final int BLUETOOTH_PERMISSION_CODE = 102;

    // ── Views ──
    private PreviewView cameraPreview;
    private ImageView remoteCameraPreview;
    private ScanOverlayView scanOverlay;
    private LinearLayout cameraPlaceholder;
    private TextView camStatusBadge;
    private ProgressBar confidenceBar;
    private TextView statusIcon, statusTitle, statusDesc;
    private LinearLayout guidanceCard;
    private LinearLayout resultCard;
    private TextView ocrResultText;
    private LinearLayout ttsProgressLayout;
    private ProgressBar ttsProgressBar;
    private TextView ttsPercentLabel;
    private Button startBtn, settingsBtn, stopBtn;
    private Button navModeBtn, glassesPanelToggleBtn, storyModeBtn;
    private LinearLayout settingsPanel, smartGlassesPanel;
    private SeekBar rateSlider, volSlider, threshSlider;
    private TextView rateVal, volVal, threshVal;
    private ImageButton copyBtn, replayBtn;
    private LinearLayout postScanControls;
    private Button restartBtn, backToMenuBtn;
    private TextView voiceStatusLabel;

    // ── Smart Glasses & Diagnostics Views ──
    private Button launchSimulatorBtn, connectGlassesBtn, transportModeBtn, cameraSourceToggleBtn;
    private EditText glassesIpEdit;
    private TextView diagConn, diagTrans, diagCam, diagAudio, diagOcr, diagSign, diagHazard, diagVoice, diagBtn, diagPerf;

    // ── Arrow TextViews ──
    private TextView arrow_ul, arrow_u, arrow_ur, arrow_l, arrow_c,
            arrow_r, arrow_dl, arrow_d, arrow_dr;

    // ── Core Managers ──
    private AudioOutputManager audioOutputManager;
    private DeviceConnectionManager connectionManager;
    private GlassesButtonManager buttonManager;
    private VisionProcessor visionProcessor;
    private AlertManager alertManager;
    private StoryReader storyReader;

    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;
    private SpeechRecognizer speechRecognizer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── State ──
    private boolean scanning = false;
    private boolean roadNavMode = false;
    private boolean useRemoteCamera = false;
    private boolean cameraStarted = false;
    private boolean hasAutoCapture = false;
    private boolean voiceListening = false;
    private boolean storyModeEnabled = false;
    private boolean ttsReady = false;
    private int guidanceStep = 0;
    private long lastGuideTime = 0;
    private int stableFrameCount = 0;
    private String lastStableText = "";
    private String lastDetectedText = "";
    private float speechRate = 0.85f;
    private float speechVolume = 1.0f;
    private int captureThreshold = 40;

    // ── Diagnostics Stats ──
    private long lastFpsCalcTime = 0;
    private int framesProcessedInWindow = 0;
    private int currentFps = 0;
    private List<HazardDetector.DetectedHazard> latestHazards = new ArrayList<>();
    private List<RoadSignDetector.RoadSign> latestSigns = new ArrayList<>();

    private final HashMap<String, String> guidanceMessages = new HashMap<String, String>() {{
        put("u",  "Move the phone upward");
        put("d",  "Move the phone downward");
        put("l",  "Move the phone to the left");
        put("r",  "Move the phone to the right");
        put("ul", "Move up and to the left");
        put("ur", "Move up and to the right");
        put("dl", "Move down and to the left");
        put("dr", "Move down and to the right");
        put("c",  "Hold still, text detected");
    }};

    // ──────────────────────────────────────────────────────────────────────────
    //  LIFECYCLE
    // ──────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupSettings();
        initCoreArchitecture();
        initSpeechRecognizer();
        cameraExecutor = Executors.newSingleThreadExecutor();

        setupListeners();
    }

    private void setupListeners() {
        startBtn.setOnClickListener(v -> {
            if (scanning) return;
            if (checkCameraPermission()) beginScanning();
            else requestCameraPermission();
        });

        stopBtn.setOnClickListener(v -> stopScanning());

        settingsBtn.setOnClickListener(v -> {
            boolean visible = settingsPanel.getVisibility() == View.VISIBLE;
            settingsPanel.setVisibility(visible ? View.GONE : View.VISIBLE);
        });

        navModeBtn.setOnClickListener(v -> toggleNavigationMode());

        glassesPanelToggleBtn.setOnClickListener(v -> {
            boolean visible = smartGlassesPanel.getVisibility() == View.VISIBLE;
            smartGlassesPanel.setVisibility(visible ? View.GONE : View.VISIBLE);
        });

        storyModeBtn.setOnClickListener(v -> toggleStoryMode());

        launchSimulatorBtn.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, GlassesActivity.class);
            startActivity(intent);
        });

        connectGlassesBtn.setOnClickListener(v -> {
            if (connectionManager.isConnected()) {
                connectionManager.disconnect();
                updateDiagnosticsUI();
            } else {
                String ip = glassesIpEdit.getText().toString().trim();
                if (ip.isEmpty()) {
                    ip = "127.0.0.1";
                }
                connectGlasses(ip);
            }
        });

        transportModeBtn.setOnClickListener(v -> {
            if (connectionManager.getTransportMode() == DeviceConnectionManager.TransportMode.WIFI) {
                connectionManager.setTransportMode(DeviceConnectionManager.TransportMode.BLUETOOTH);
                transportModeBtn.setText("Transport: Bluetooth");
            } else {
                connectionManager.setTransportMode(DeviceConnectionManager.TransportMode.WIFI);
                transportModeBtn.setText("Transport: Wi-Fi");
            }
            updateDiagnosticsUI();
        });

        cameraSourceToggleBtn.setOnClickListener(v -> {
            if (useRemoteCamera) {
                switchToPhoneCamera();
            } else {
                switchToGlassesCamera();
            }
        });

        copyBtn.setOnClickListener(v -> copyText());
        replayBtn.setOnClickListener(v -> {
            if (storyModeEnabled && storyReader != null && !storyReader.getLastText().isEmpty()) {
                storyReader.repeat(createStoryCallback(storyReader.getLastText()));
            } else if (!lastDetectedText.isEmpty()) {
                speakWithProgress(lastDetectedText);
            }
        });
        restartBtn.setOnClickListener(v -> restartScan());
        backToMenuBtn.setOnClickListener(v -> goToMenu());
        voiceStatusLabel.setOnClickListener(v -> {
            if (!voiceListening) startVoiceListening();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateDiagnosticsUI();
        mainHandler.postDelayed(() -> {
            if (ttsReady && !voiceListening && !audioOutputManager.isSpeaking() && (storyReader == null || !storyReader.isSpeaking())) {
                startVoiceListening();
            }
        }, 2500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (scanning && !roadNavMode) {
            scanning = false;
        }
        if (audioOutputManager != null) {
            audioOutputManager.stop();
        }
        if (storyReader != null) storyReader.stop();
        stopVoiceListening();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (storyReader != null) storyReader.shutdown();
        if (audioOutputManager != null) audioOutputManager.shutdown();
        if (visionProcessor != null) visionProcessor.close();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (speechRecognizer != null) speechRecognizer.destroy();
        if (connectionManager != null) connectionManager.disconnect();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  BIND VIEWS
    // ──────────────────────────────────────────────────────────────────────────
    private void bindViews() {
        cameraPreview          = findViewById(R.id.cameraPreview);
        remoteCameraPreview    = findViewById(R.id.remoteCameraPreview);
        scanOverlay            = findViewById(R.id.scanOverlay);
        cameraPlaceholder      = findViewById(R.id.cameraPlaceholder);
        camStatusBadge         = findViewById(R.id.camStatusBadge);
        confidenceBar          = findViewById(R.id.confidenceBar);
        statusIcon             = findViewById(R.id.statusIcon);
        statusTitle            = findViewById(R.id.statusTitle);
        statusDesc             = findViewById(R.id.statusDesc);
        guidanceCard           = findViewById(R.id.guidanceCard);
        resultCard             = findViewById(R.id.resultCard);
        ocrResultText          = findViewById(R.id.ocrResultText);
        ttsProgressLayout      = findViewById(R.id.ttsProgressLayout);
        ttsProgressBar         = findViewById(R.id.ttsProgressBar);
        ttsPercentLabel        = findViewById(R.id.ttsPercentLabel);
        startBtn               = findViewById(R.id.startBtn);
        stopBtn                = findViewById(R.id.stopBtn);
        settingsBtn            = findViewById(R.id.settingsBtn);
        navModeBtn             = findViewById(R.id.navModeBtn);
        glassesPanelToggleBtn  = findViewById(R.id.glassesPanelToggleBtn);
        storyModeBtn           = findViewById(R.id.storyModeBtn);
        settingsPanel          = findViewById(R.id.settingsPanel);
        smartGlassesPanel      = findViewById(R.id.smartGlassesPanel);
        rateSlider             = findViewById(R.id.rateSlider);
        rateVal                = findViewById(R.id.rateVal);
        volSlider              = findViewById(R.id.volSlider);
        volVal                 = findViewById(R.id.volVal);
        threshSlider           = findViewById(R.id.threshSlider);
        threshVal              = findViewById(R.id.threshVal);
        copyBtn                = findViewById(R.id.copyBtn);
        replayBtn              = findViewById(R.id.replayBtn);
        postScanControls       = findViewById(R.id.postScanControls);
        restartBtn             = findViewById(R.id.restartBtn);
        backToMenuBtn          = findViewById(R.id.backToMenuBtn);
        voiceStatusLabel       = findViewById(R.id.voiceStatusLabel);

        // Smart Glasses & Diagnostics
        launchSimulatorBtn     = findViewById(R.id.launchSimulatorBtn);
        connectGlassesBtn      = findViewById(R.id.connectGlassesBtn);
        transportModeBtn       = findViewById(R.id.transportModeBtn);
        cameraSourceToggleBtn  = findViewById(R.id.cameraSourceToggleBtn);
        glassesIpEdit          = findViewById(R.id.glassesIpEdit);

        diagConn    = findViewById(R.id.diagConn);
        diagTrans   = findViewById(R.id.diagTrans);
        diagCam     = findViewById(R.id.diagCam);
        diagAudio   = findViewById(R.id.diagAudio);
        diagOcr     = findViewById(R.id.diagOcr);
        diagSign    = findViewById(R.id.diagSign);
        diagHazard  = findViewById(R.id.diagHazard);
        diagVoice   = findViewById(R.id.diagVoice);
        diagBtn     = findViewById(R.id.diagBtn);
        diagPerf    = findViewById(R.id.diagPerf);

        arrow_ul = findViewById(R.id.arrow_ul);
        arrow_u  = findViewById(R.id.arrow_u);
        arrow_ur = findViewById(R.id.arrow_ur);
        arrow_l  = findViewById(R.id.arrow_l);
        arrow_c  = findViewById(R.id.arrow_c);
        arrow_r  = findViewById(R.id.arrow_r);
        arrow_dl = findViewById(R.id.arrow_dl);
        arrow_d  = findViewById(R.id.arrow_d);
        arrow_dr = findViewById(R.id.arrow_dr);
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  CORE ARCHITECTURE INITIALIZATION
    // ──────────────────────────────────────────────────────────────────────────
    private void initCoreArchitecture() {
        audioOutputManager = AudioOutputManager.getInstance(this);
        audioOutputManager.init(() -> {
            ttsReady = true;
            speakThenListen("Vision Read is ready. Say Scan to start, or tap the Start button.");
        });

        connectionManager = DeviceConnectionManager.getInstance();
        connectionManager.setCallback(this);

        buttonManager = new GlassesButtonManager();
        buttonManager.setButtonEventListener(this);

        visionProcessor = new VisionProcessor();
        visionProcessor.setListener(this);

        alertManager = new AlertManager(audioOutputManager);
        storyReader = new StoryReader(this);
        updateDiagnosticsUI();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  SPEECH & AUDIO
    // ──────────────────────────────────────────────────────────────────────────
    private void toggleStoryMode() {
        storyModeEnabled = !storyModeEnabled;
        if (storyModeEnabled) {
            storyModeBtn.setText("📖  Story Mode: ON");
            setStatus("📖", "Story Mode", "Books and long text will use expressive Gemini narration.");
            speakThenListen("Story mode on. Point the camera at a book page and say Scan.");
        } else {
            if (storyReader != null) storyReader.stop();
            storyModeBtn.setText("📖  Story Mode: OFF");
            setStatus("💤", "Normal Mode", "Short text and signs use the existing local reader.");
            speakThenListen("Story mode off. Normal reading restored.");
        }
    }

    private StoryReader.Callback createStoryCallback(final String fallbackText) {
        return new StoryReader.Callback() {
            @Override public void onGenerating() {
                mainHandler.post(() -> {
                    ttsProgressLayout.setVisibility(View.VISIBLE);
                    ttsProgressBar.setProgress(0);
                    ttsPercentLabel.setText("AI");
                    setStatus("🎧", "Preparing story narration", "Generating an expressive audiobook-style voice.");
                });
                stopVoiceListening();
            }

            @Override public void onStarted() {
                mainHandler.post(() -> {
                    ttsProgressLayout.setVisibility(View.VISIBLE);
                    ttsProgressBar.setProgress(50);
                    ttsPercentLabel.setText("PLAYING");
                    setStatus("📖", "Story Mode reading", "Say Pause, Resume, Repeat, Faster, or Stop.");
                });
            }

            @Override public void onDone() {
                mainHandler.post(() -> {
                    ttsProgressBar.setProgress(100);
                    ttsPercentLabel.setText("100%");
                    setStatus("✅", "Story finished", "Say Repeat, Scan Again, or Start Story Mode.");
                    mainHandler.postDelayed(() -> startVoiceListening(), 600);
                });
            }

            @Override public void onError(String message) {
                Log.e(TAG, "Story Mode: " + message);
                mainHandler.post(() -> {
                    // Keep the app usable: fall back to the existing Android TTS.
                    setStatus("🔊", "Story Mode fallback", "Gemini was unavailable. Using the normal reader.");
                    speakWithProgress(fallbackText);
                });
            }
        };
    }

    private void speak(String text) {
        if (audioOutputManager == null) return;
        audioOutputManager.speak(text);
    }

    private void speakThenListen(String text) {
        if (audioOutputManager == null) return;
        stopVoiceListening();
        audioOutputManager.speakWithCallback(text, new AudioOutput.UtteranceCallback() {
            @Override public void onStart() {}
            @Override public void onProgress(int wordIndex, int totalWords, int percent) {}
            @Override public void onDone() {
                mainHandler.postDelayed(() -> startVoiceListening(), 400);
            }
            @Override public void onError() {
                mainHandler.postDelayed(() -> startVoiceListening(), 400);
            }
        });
    }

    private void speakWithProgress(String text) {
        if (audioOutputManager == null || text == null || text.isEmpty()) return;
        lastDetectedText = text;
        stopVoiceListening();
        audioOutputManager.setSpeechRate(speechRate);

        ttsProgressLayout.setVisibility(View.VISIBLE);
        ttsProgressBar.setMax(100);
        ttsProgressBar.setProgress(0);
        ttsPercentLabel.setText("0%");

        audioOutputManager.speakWithCallback(text, new AudioOutput.UtteranceCallback() {
            @Override public void onStart() {}

            @Override
            public void onProgress(int wordIndex, int totalWords, int percent) {
                mainHandler.post(() -> {
                    ttsProgressBar.setProgress(percent);
                    ttsPercentLabel.setText(percent + "%");
                });
            }

            @Override
            public void onDone() {
                mainHandler.post(() -> {
                    ttsProgressBar.setProgress(100);
                    ttsPercentLabel.setText("100%");
                    setStatus("✅", "Done reading", "Say 'Repeat', 'Scan Again', or 'Menu'.");
                    mainHandler.postDelayed(() -> startVoiceListening(), 600);
                });
            }

            @Override
            public void onError() {
                mainHandler.postDelayed(() -> startVoiceListening(), 600);
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  SPEECH RECOGNIZER & VOICE COMMANDS
    // ──────────────────────────────────────────────────────────────────────────
    private void initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) {
                mainHandler.post(() -> {
                    voiceListening = true;
                    voiceStatusLabel.setText("🎤  Listening...");
                    voiceStatusLabel.setTextColor(0xFF00FF88);
                });
            }
            @Override public void onResults(Bundle results) {
                voiceListening = false;
                mainHandler.post(() -> {
                    voiceStatusLabel.setText("🎤  Tap here to speak a command");
                    voiceStatusLabel.setTextColor(0xFF00E5FF);
                });
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    handleVoiceCommand(matches.get(0).toLowerCase(Locale.US).trim());
                } else {
                    mainHandler.postDelayed(() -> startVoiceListening(), 1000);
                }
            }
            @Override public void onError(int error) {
                voiceListening = false;
                mainHandler.post(() -> {
                    voiceStatusLabel.setText("🎤  Tap here to speak a command");
                    voiceStatusLabel.setTextColor(0xFF00E5FF);
                });
                if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS &&
                    error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    mainHandler.postDelayed(() -> {
                        if (audioOutputManager != null && !audioOutputManager.isSpeaking()) {
                            startVoiceListening();
                        }
                    }, 1500);
                }
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float r) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onPartialResults(Bundle p) {}
            @Override public void onEvent(int t, Bundle p) {}
        });
    }

    private void startVoiceListening() {
        if (speechRecognizer == null || voiceListening) return;
        if (audioOutputManager != null && audioOutputManager.isSpeaking()) return;
        if (storyReader != null && storyReader.isSpeaking()) return;
        if (!checkAudioPermission()) {
            requestAudioPermission();
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        try {
            speechRecognizer.startListening(intent);
        } catch (Exception e) {
            Log.e(TAG, "Listen failed: " + e.getMessage());
        }
    }

    private void stopVoiceListening() {
        if (speechRecognizer != null && voiceListening) {
            speechRecognizer.stopListening();
            voiceListening = false;
        }
        mainHandler.post(() -> {
            if (voiceStatusLabel != null) {
                voiceStatusLabel.setText("🎤  Tap here to speak a command");
                voiceStatusLabel.setTextColor(0xFF00E5FF);
            }
        });
    }

    private void handleVoiceCommand(String cmd) {
        Log.d(TAG, "Voice: " + cmd);

        // STORY MODE COMMANDS
        if (containsAny(cmd, "start story mode", "story mode", "book mode", "story reading")) {
            if (!storyModeEnabled) {
                storyModeEnabled = true;
                mainHandler.post(() -> storyModeBtn.setText("📖  Story Mode: ON"));
                speakThenListen("Story mode on. Say Scan to read a book page.");
            } else {
                speakThenListen("Story mode is already on. Say Scan to read a page.");
            }
            return;
        }
        if (containsAny(cmd, "normal mode", "regular mode", "exit story mode", "stop story mode")) {
            storyModeEnabled = false;
            if (storyReader != null) storyReader.stop();
            mainHandler.post(() -> storyModeBtn.setText("📖  Story Mode: OFF"));
            speakThenListen("Normal mode restored.");
            return;
        }
        if (storyModeEnabled && containsAny(cmd, "resume", "resume reading", "continue reading", "continue")) {
            if (storyReader != null) storyReader.resume();
            speakThenListen("Resuming story.");
            return;
        }
        if (storyModeEnabled && containsAny(cmd, "pause", "pause reading")) {
            if (storyReader != null) storyReader.pause();
            speakThenListen("Story paused. Say Resume to continue.");
            return;
        }

        // NAVIGATION / ROAD MODE
        if (containsAny(cmd, "start navigation", "navigation mode", "road mode", "start road")) {
            if (!roadNavMode) toggleNavigationMode();
            else speakThenListen("Road navigation mode is already running.");
            return;
        }
        if (containsAny(cmd, "stop navigation", "stop road", "exit navigation")) {
            if (roadNavMode) toggleNavigationMode();
            else speakThenListen("Road navigation is not active.");
            return;
        }

        // SMART GLASSES CONNECTION COMMANDS
        if (containsAny(cmd, "connect glasses", "pair glasses", "connect")) {
            String ip = glassesIpEdit.getText().toString().trim();
            if (ip.isEmpty()) ip = "127.0.0.1";
            speak("Searching for VisionRead glasses.");
            connectGlasses(ip);
            return;
        }
        if (containsAny(cmd, "disconnect glasses", "unpair glasses", "disconnect")) {
            if (connectionManager.isConnected()) {
                connectionManager.disconnect();
                speakThenListen("Glasses disconnected. Switching to phone camera.");
            } else {
                speakThenListen("No glasses are currently connected.");
            }
            return;
        }
        if (containsAny(cmd, "use glasses camera", "glasses camera")) {
            switchToGlassesCamera();
            return;
        }
        if (containsAny(cmd, "use phone camera", "phone camera")) {
            switchToPhoneCamera();
            return;
        }

        // SURROUNDINGS & SIGNS QUERY
        if (containsAny(cmd, "what is ahead", "what's ahead", "describe surroundings", "surroundings")) {
            describeSurroundings();
            return;
        }
        if (containsAny(cmd, "detect signs", "read signs", "traffic signs", "signs")) {
            readDetectedSigns();
            return;
        }

        // STANDARD OCR COMMANDS
        if (containsAny(cmd, "scan", "start", "begin", "read text", "read this", "read", "camera", "open")) {
            if (!scanning) {
                mainHandler.post(() -> {
                    if (checkCameraPermission()) beginScanning();
                    else requestCameraPermission();
                });
            } else {
                speakThenListen("Already scanning. Hold camera over printed text.");
            }
            return;
        }
        if (containsAny(cmd, "stop", "pause", "cancel", "halt")) {
            mainHandler.post(this::stopScanning);
            return;
        }
        if (containsAny(cmd, "again", "restart", "rescan", "another", "new scan")) {
            mainHandler.post(this::restartScan);
            return;
        }
        if (containsAny(cmd, "menu", "home", "back", "main")) {
            mainHandler.post(this::goToMenu);
            return;
        }
        if (containsAny(cmd, "repeat", "replay", "read again", "say again", "once more")) {
            if (storyModeEnabled && storyReader != null && !storyReader.getLastText().isEmpty()) {
                mainHandler.post(() -> storyReader.repeat(createStoryCallback(storyReader.getLastText())));
            } else if (!lastDetectedText.isEmpty()) {
                mainHandler.post(() -> speakWithProgress(lastDetectedText));
            } else if (!alertManager.getLastAlertSpoken().isEmpty()) {
                speakThenListen(alertManager.getLastAlertSpoken());
            } else {
                speakThenListen("No text or alert recorded yet. Say Scan to start.");
            }
            return;
        }
        if (containsAny(cmd, "copy", "clipboard", "save text")) {
            mainHandler.post(this::copyText);
            return;
        }
        if (containsAny(cmd, "faster", "speed up", "quick", "fast")) {
            speechRate = Math.min(2.0f, speechRate + 0.2f);
            if (audioOutputManager != null) audioOutputManager.setSpeechRate(speechRate);
            if (storyReader != null) storyReader.setPlaybackSpeed(speechRate);
            speakThenListen("Speed increased to " + String.format(Locale.US, "%.1f", speechRate));
            return;
        }
        if (containsAny(cmd, "slower", "slow down", "slow", "too fast")) {
            speechRate = Math.max(0.5f, speechRate - 0.2f);
            if (audioOutputManager != null) audioOutputManager.setSpeechRate(speechRate);
            if (storyReader != null) storyReader.setPlaybackSpeed(speechRate);
            speakThenListen("Speed reduced to " + String.format(Locale.US, "%.1f", speechRate));
            return;
        }
        if (containsAny(cmd, "increase volume", "louder")) {
            speechVolume = Math.min(1.0f, speechVolume + 0.2f);
            if (audioOutputManager != null) audioOutputManager.setSpeechVolume(speechVolume);
            if (storyReader != null) storyReader.setVolume(speechVolume);
            speakThenListen("Volume increased.");
            return;
        }
        if (containsAny(cmd, "decrease volume", "softer", "lower volume")) {
            speechVolume = Math.max(0.2f, speechVolume - 0.2f);
            if (audioOutputManager != null) audioOutputManager.setSpeechVolume(speechVolume);
            if (storyReader != null) storyReader.setVolume(speechVolume);
            speakThenListen("Volume decreased.");
            return;
        }
        if (containsAny(cmd, "settings", "options", "preferences")) {
            mainHandler.post(() -> settingsPanel.setVisibility(View.VISIBLE));
            speakThenListen("Settings opened.");
            return;
        }
        if (containsAny(cmd, "help", "commands", "what can", "instructions")) {
            speakThenListen("Voice commands available: Scan, Stop, Repeat, What is ahead?, Describe surroundings, Start navigation, Stop navigation, Detect signs, Connect glasses, Disconnect glasses, Use phone camera, Use glasses camera, Start Story Mode, Normal Mode, Pause, Resume, Copy, Faster, Slower, and Help.");
            return;
        }

        // Unknown — re-listen
        mainHandler.postDelayed(() -> startVoiceListening(), 800);
    }

    private boolean containsAny(String input, String... keywords) {
        for (String kw : keywords) if (input.contains(kw)) return true;
        return false;
    }

    private void describeSurroundings() {
        StringBuilder sb = new StringBuilder();
        if (latestHazards.isEmpty() && latestSigns.isEmpty()) {
            sb.append("Clear path ahead. No immediate hazards or road signs detected.");
        } else {
            if (!latestHazards.isEmpty()) {
                sb.append("Detected: ");
                for (HazardDetector.DetectedHazard h : latestHazards) {
                    sb.append(h.alertMessage).append(". ");
                }
            }
            if (!latestSigns.isEmpty()) {
                sb.append("Signs: ");
                for (RoadSignDetector.RoadSign s : latestSigns) {
                    sb.append(s.description).append(". ");
                }
            }
        }
        speakThenListen(sb.toString().trim());
    }

    private void readDetectedSigns() {
        if (latestSigns.isEmpty()) {
            speakThenListen("No road signs currently in view.");
        } else {
            StringBuilder sb = new StringBuilder("Road signs: ");
            for (RoadSignDetector.RoadSign s : latestSigns) {
                sb.append(s.description).append(". ");
            }
            speakThenListen(sb.toString().trim());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  NAVIGATION / ROAD ASSISTANCE MODE
    // ──────────────────────────────────────────────────────────────────────────
    private void toggleNavigationMode() {
        roadNavMode = !roadNavMode;
        visionProcessor.setRoadModeEnabled(roadNavMode);

        if (roadNavMode) {
            navModeBtn.setText("🧭  Road Mode: ON");
            navModeBtn.setTextColor(0xFF00FF88);
            setStatus("🧭", "Road Assistance Active", "Observing environment for hazards, road signs, and crossings.");
            camStatusBadge.setText("ROAD ASSISTANCE ACTIVE");
            camStatusBadge.setVisibility(View.VISIBLE);

            if (!useRemoteCamera && !cameraStarted) {
                cameraStarted = true;
                startCamera();
            } else if (useRemoteCamera) {
                remoteCameraPreview.setVisibility(View.VISIBLE);
                cameraPlaceholder.setVisibility(View.GONE);
            }

            alertManager.announce("Road assistance mode started. Observing environment.", AlertManager.Priority.HIGH);
        } else {
            navModeBtn.setText("🧭  Road Mode: OFF");
            navModeBtn.setTextColor(0xFF00E5FF);
            setStatus("💤", "Road Assistance Stopped", "Tap Start Scanning or say 'Scan'.");
            camStatusBadge.setVisibility(View.GONE);
            alertManager.clearCooldowns();
            speakThenListen("Road assistance stopped.");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  SMART GLASSES CONNECTION & REMOTE CAMERA
    // ──────────────────────────────────────────────────────────────────────────
    private void connectGlasses(String host) {
        connectGlassesBtn.setEnabled(false);
        connectGlassesBtn.setText("Connecting…");
        connectionManager.connect(host, WifiTransport.DEFAULT_PORT);
    }

    private void switchToGlassesCamera() {
        if (!connectionManager.isConnected()) {
            speakThenListen("Cannot switch to glasses camera. Smart glasses are not connected.");
            return;
        }
        useRemoteCamera = true;
        cameraPreview.setVisibility(View.GONE);
        remoteCameraPreview.setVisibility(View.VISIBLE);
        cameraPlaceholder.setVisibility(View.GONE);
        cameraSourceToggleBtn.setText("Cam: Glasses");
        glassesPanelToggleBtn.setText("👓  Glasses: Remote");
        updateDiagnosticsUI();
        speakThenListen("Glasses camera active.");
    }

    private void switchToPhoneCamera() {
        useRemoteCamera = false;
        remoteCameraPreview.setVisibility(View.GONE);
        if (cameraStarted) {
            cameraPreview.setVisibility(View.VISIBLE);
            cameraPlaceholder.setVisibility(View.GONE);
        } else {
            cameraPlaceholder.setVisibility(View.VISIBLE);
        }
        cameraSourceToggleBtn.setText("Cam: Phone");
        glassesPanelToggleBtn.setText("👓  Glasses: Phone");
        updateDiagnosticsUI();
        speakThenListen("Phone camera active.");
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  DEVICE CONNECTION CALLBACKS
    // ──────────────────────────────────────────────────────────────────────────
    @Override
    public void onConnected(String address, String transportType) {
        connectGlassesBtn.setEnabled(true);
        connectGlassesBtn.setText("Disconnect");
        connectGlassesBtn.setBackgroundResource(R.drawable.btn_secondary);
        glassesPanelToggleBtn.setText("👓  Glasses: ON");
        glassesPanelToggleBtn.setTextColor(0xFF00FF88);

        // Auto-switch to glasses camera and glasses speaker
        switchToGlassesCamera();
        audioOutputManager.setPreferGlasses(true);

        updateDiagnosticsUI();
        speak("Glasses connected. Camera and speaker are ready.");
    }

    @Override
    public void onDisconnected(String reason) {
        connectGlassesBtn.setEnabled(true);
        connectGlassesBtn.setText("Connect");
        connectGlassesBtn.setBackgroundResource(R.drawable.btn_primary);
        glassesPanelToggleBtn.setText("👓  Glasses: OFF");
        glassesPanelToggleBtn.setTextColor(0xFF9CA3AF);

        // Seamless automatic fallback to Phone Mode
        if (useRemoteCamera) {
            useRemoteCamera = false;
            remoteCameraPreview.setVisibility(View.GONE);
            if (cameraStarted) {
                cameraPreview.setVisibility(View.VISIBLE);
            } else {
                cameraPlaceholder.setVisibility(View.VISIBLE);
            }
            cameraSourceToggleBtn.setText("Cam: Phone");
        }

        audioOutputManager.setPreferGlasses(false);
        updateDiagnosticsUI();

        speakThenListen("Glasses disconnected. Switching to phone camera.");
    }

    @Override
    public void onFrameReceived(byte[] jpegBytes, long latencyMs) {
        if (!useRemoteCamera || jpegBytes == null) return;

        // Decode and show remote preview
        try {
            Bitmap bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
            if (bmp != null) {
                mainHandler.post(() -> remoteCameraPreview.setImageBitmap(bmp));
            }
        } catch (Exception ignored) {}

        // Feed to unified VisionProcessor
        visionProcessor.processJpegBytes(jpegBytes);
    }

    @Override
    public void onAudioReceived(String textOrAudioCmd) {
        // Device A (Brain) coordinates TTS, this is a placeholder if Device B sends audio voice
    }

    @Override
    public void onButtonEvent(int buttonId, int clickType) {
        buttonManager.handleButtonEvent(buttonId, clickType);
    }

    @Override
    public void onStatusUpdate(String status) {
        Log.d(TAG, "Connection Status: " + status);
        updateDiagnosticsUI();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GLASSES BUTTON LISTENER IMPLEMENTATION
    // ──────────────────────────────────────────────────────────────────────────
    @Override
    public void onActionCapture(boolean isLongPress) {
        Toast.makeText(this, "Glasses Button 1 (Action) received", Toast.LENGTH_SHORT).show();
        if (isLongPress) {
            toggleNavigationMode();
        } else {
            if (!scanning) beginScanning();
            else speakThenListen("Scanning in progress.");
        }
    }

    @Override
    public void onRepeatMessage(boolean isLongPress) {
        Toast.makeText(this, "Glasses Button 2 (Repeat) received", Toast.LENGTH_SHORT).show();
        if (!lastDetectedText.isEmpty()) {
            speakWithProgress(lastDetectedText);
        } else if (!alertManager.getLastAlertSpoken().isEmpty()) {
            speak(alertManager.getLastAlertSpoken());
        } else {
            describeSurroundings();
        }
    }

    @Override
    public void onEmergencyHelp(boolean isLongPress) {
        Toast.makeText(this, "Glasses Button 3 (Emergency) received", Toast.LENGTH_SHORT).show();
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));
            else vibrator.vibrate(500);
        }
        alertManager.announce("Emergency alert triggered. User assistance requested.", AlertManager.Priority.CRITICAL);
    }

    @Override
    public void onCustomButton(int buttonId, boolean isLongPress) {
        Toast.makeText(this, "Custom Button " + buttonId + " received", Toast.LENGTH_SHORT).show();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  VISION PROCESSOR RESULTS (OCR + SIGNS + HAZARDS)
    // ──────────────────────────────────────────────────────────────────────────
    @Override
    public void onVisionResult(VisionProcessor.VisionResult result) {
        framesProcessedInWindow++;
        long now = System.currentTimeMillis();
        if (now - lastFpsCalcTime >= 1000) {
            currentFps = framesProcessedInWindow;
            framesProcessedInWindow = 0;
            lastFpsCalcTime = now;
            updateDiagnosticsUI();
        }

        latestHazards = result.hazards;
        latestSigns = result.roadSigns;

        // 1. If in Road Navigation Mode: evaluate hazards and signs for priority audio alerts
        if (roadNavMode) {
            alertManager.evaluateHazardsAndSigns(result.hazards, result.roadSigns);
        }

        // 2. If in OCR Scanning Mode: handle text stability and auto-capture
        if (scanning && !hasAutoCapture) {
            handleRecognitionResult(result.ocrText);
        }
    }

    @Override
    public void onError(Exception e) {
        Log.e(TAG, "Vision processor error: " + e.getMessage());
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  DIAGNOSTICS & STATUS SCREEN UPDATE
    // ──────────────────────────────────────────────────────────────────────────
    private void updateDiagnosticsUI() {
        mainHandler.post(() -> {
            boolean connected = connectionManager.isConnected();
            diagConn.setText("Connection: " + (connected ? "CONNECTED" : "DISCONNECTED"));
            diagConn.setTextColor(connected ? 0xFF00FF88 : 0xFF6B7280);

            diagTrans.setText("Transport: " + connectionManager.getTransportName());
            diagCam.setText("Camera: " + (useRemoteCamera ? "GLASSES (REMOTE)" : "PHONE CAMERA"));
            diagAudio.setText("Audio: " + audioOutputManager.getCurrentOutputName().toUpperCase(Locale.US));

            diagBtn.setText("Buttons: " + (connected ? "CONNECTED" : "NOT CONNECTED"));
            diagBtn.setTextColor(connected ? 0xFF00FF88 : 0xFF6B7280);

            diagPerf.setText("Processing: " + currentFps + " FPS | Latency: " + connectionManager.getLastLatencyMs() + " ms");
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  CAMERA & ANALYSIS (LOCAL CAMERAX)
    // ──────────────────────────────────────────────────────────────────────────
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, new ImageAnalysis.Analyzer() {
                    @Override
                    @androidx.camera.core.ExperimentalGetImage
                    public void analyze(@NonNull ImageProxy imageProxy) {
                        if (useRemoteCamera || (!scanning && !roadNavMode) || visionProcessor.isBusy() || hasAutoCapture) {
                            imageProxy.close();
                            return;
                        }

                        try {
                            if (imageProxy.getImage() != null) {
                                InputImage image = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());
                                visionProcessor.processFrame(image);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Image analyze error: " + e.getMessage());
                        } finally {
                            imageProxy.close();
                        }
                    }
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);

                mainHandler.post(() -> {
                    if (!useRemoteCamera) {
                        cameraPreview.setVisibility(View.VISIBLE);
                        cameraPlaceholder.setVisibility(View.GONE);
                    }
                    scanOverlay.setVisibility(View.VISIBLE);
                    confidenceBar.setVisibility(View.VISIBLE);
                    scanOverlay.setScanning(true);
                });
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera init failed: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  RECOGNITION RESULT & STABILITY CHECK
    // ──────────────────────────────────────────────────────────────────────────
    private void handleRecognitionResult(Text visionText) {
        if (visionText == null) return;
        String fullText = visionText.getText().trim();
        int wordCount = countWords(fullText);
        int blockCount = visionText.getTextBlocks().size();
        int lineCount = 0;
        for (Text.TextBlock b : visionText.getTextBlocks()) lineCount += b.getLines().size();

        int conf = Math.min(100, wordCount * 3 + lineCount * 5 + blockCount * 8);
        final int finalConf = conf;
        mainHandler.post(() -> {
            confidenceBar.setProgress(finalConf);
            camStatusBadge.setText("SCANNING… " + finalConf + "%");
            camStatusBadge.setVisibility(View.VISIBLE);
        });

        boolean looksLikePage = wordCount >= 15 && lineCount >= 4 && blockCount >= 2;

        if (looksLikePage) {
            int sim = getSimilarityScore(fullText, lastStableText);
            if (sim >= 60) stableFrameCount++;
            else { stableFrameCount = 1; lastStableText = fullText; }

            int pct = Math.min(100, stableFrameCount * 33);
            mainHandler.post(() -> {
                camStatusBadge.setText("HOLD STILL… " + pct + "%");
                confidenceBar.setProgress(pct);
            });

            if (stableFrameCount >= 3) {
                String textToRead = fullText.length() > lastStableText.length() ? fullText : lastStableText;
                triggerAutoCapture(textToRead, conf);
            } else {
                long now = System.currentTimeMillis();
                if (now - lastGuideTime > 2000) {
                    lastGuideTime = now;
                    speak("Good, hold still");
                }
            }
        } else {
            stableFrameCount = 0;
            lastStableText = "";
            long now = System.currentTimeMillis();
            if (now - lastGuideTime > 3500) {
                lastGuideTime = now;
                guideUser(wordCount, lineCount);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ──────────────────────────────────────────────────────────────────────────
    private int countWords(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        return text.trim().split("\\s+").length;
    }

    private int getSimilarityScore(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        Set<String> setA = new HashSet<>(Arrays.asList(a.toLowerCase(Locale.US).split("\\s+")));
        Set<String> setB = new HashSet<>(Arrays.asList(b.toLowerCase(Locale.US).split("\\s+")));
        Set<String> inter = new HashSet<>(setA);
        inter.retainAll(setB);
        int larger = Math.max(setA.size(), setB.size());
        return larger == 0 ? 0 : (int)((inter.size() / (float) larger) * 100);
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  GUIDANCE
    // ──────────────────────────────────────────────────────────────────────────
    private void guideUser(int wordCount, int lineCount) {
        String dir, msg;
        if (wordCount == 0 && lineCount == 0) {
            String[] sweep = {"l", "r", "l", "r", "u", "d"};
            dir = sweep[guidanceStep % sweep.length];
            msg = guidanceMessages.containsKey(dir) ? guidanceMessages.get(dir) : "Move camera slowly";
        } else if (wordCount > 0 && lineCount < 3) {
            String[] expand = {"u", "d", "l", "r"};
            dir = expand[guidanceStep % expand.length];
            msg = "Move " + getDirectionName(dir) + " to show more of the page";
        } else {
            dir = "c";
            msg = "Point the camera straight at the page";
        }
        guidanceStep++;
        final String fd = dir, fm = msg;
        mainHandler.post(() -> {
            guidanceCard.setVisibility(View.VISIBLE);
            highlightArrow(fd);
            setStatus("🧭", "Guiding you", fm);
        });
        speak(msg);
    }

    private String getDirectionName(String dir) {
        switch (dir) {
            case "u": return "up"; case "d": return "down";
            case "l": return "left"; case "r": return "right";
            default: return "the camera";
        }
    }

    private void highlightArrow(String dir) {
        TextView[] all = {arrow_ul, arrow_u, arrow_ur, arrow_l, arrow_c, arrow_r, arrow_dl, arrow_d, arrow_dr};
        for (TextView tv : all) { tv.setBackgroundResource(R.drawable.arrow_cell_normal); tv.setAlpha(0.4f); }
        TextView target = null;
        switch (dir) {
            case "ul": target = arrow_ul; break; case "u": target = arrow_u; break;
            case "ur": target = arrow_ur; break; case "l": target = arrow_l; break;
            case "c":  target = arrow_c;  break; case "r": target = arrow_r; break;
            case "dl": target = arrow_dl; break; case "d": target = arrow_d; break;
            case "dr": target = arrow_dr; break;
        }
        if (target != null) {
            target.setBackgroundResource(R.drawable.arrow_cell_active);
            target.setAlpha(1f);
            final TextView ft = target;
            ft.animate().scaleX(1.2f).scaleY(1.2f).setDuration(150)
                    .withEndAction(() -> ft.animate().scaleX(1f).scaleY(1f).setDuration(150).start()).start();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  AUTO-CAPTURE
    // ──────────────────────────────────────────────────────────────────────────
    private void triggerAutoCapture(String text, int confidence) {
        hasAutoCapture = true;
        scanning = false;
        mainHandler.post(() -> {
            scanOverlay.flashDetected();
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                    vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
                else vibrator.vibrate(200);
            }
            guidanceCard.setVisibility(View.GONE);
            camStatusBadge.setText("✓ TEXT FOUND — " + confidence + "%");
            confidenceBar.setProgress(100);
            resultCard.setVisibility(View.VISIBLE);
            ocrResultText.setText(text);
            setStatus("🔊", "Reading aloud", "Say 'Repeat', 'Scan Again', or 'Menu'.");
            startBtn.setText("📷  Start Scanning");
            startBtn.setEnabled(true);
            stopBtn.setVisibility(View.GONE);
            postScanControls.setVisibility(View.VISIBLE);
        });
        if (storyModeEnabled && storyReader != null) {
            speak("Text detected. Preparing story narration.");
            mainHandler.postDelayed(() -> storyReader.read(text, createStoryCallback(text)), 900);
        } else {
            speak("Text detected. Reading now.");
            mainHandler.postDelayed(() -> speakWithProgress(text), 1400);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  SCAN CONTROL
    // ──────────────────────────────────────────────────────────────────────────
    private void beginScanning() {
        scanning = true;
        hasAutoCapture = false;
        guidanceStep = 0;
        lastGuideTime = 0;
        stableFrameCount = 0;
        lastStableText = "";
        stopVoiceListening();
        if (audioOutputManager != null) audioOutputManager.stop();
        ttsProgressLayout.setVisibility(View.GONE);
        resultCard.setVisibility(View.GONE);
        guidanceCard.setVisibility(View.GONE);
        postScanControls.setVisibility(View.GONE);
        startBtn.setEnabled(false);
        startBtn.setText("⏳  Starting…");
        stopBtn.setVisibility(View.VISIBLE);
        setStatus("🔍", "Scanning", "Searching for text. I will guide you by voice.");

        if (!useRemoteCamera) {
            if (!cameraStarted) {
                cameraStarted = true;
                startCamera();
            } else {
                scanOverlay.setScanning(true);
            }
        } else {
            remoteCameraPreview.setVisibility(View.VISIBLE);
            cameraPlaceholder.setVisibility(View.GONE);
            scanOverlay.setScanning(true);
        }

        speak("Scanning started. Point the camera at printed text.");
    }

    private void stopScanning() {
        scanning = false;
        if (storyReader != null) storyReader.stop();
        if (audioOutputManager != null) audioOutputManager.stop();
        scanOverlay.setScanning(false);
        camStatusBadge.setVisibility(View.GONE);
        guidanceCard.setVisibility(View.GONE);
        ttsProgressLayout.setVisibility(View.GONE);
        stopBtn.setVisibility(View.GONE);
        startBtn.setEnabled(true);
        startBtn.setText("📷  Start Scanning");
        setStatus("💤", "Stopped", "Say 'Scan' or tap Start Scanning to begin.");
        speakThenListen("Scanning stopped. Say Scan to start again.");
    }

    private void restartScan() {
        postScanControls.setVisibility(View.GONE);
        resultCard.setVisibility(View.GONE);
        ttsProgressLayout.setVisibility(View.GONE);
        hasAutoCapture = false;
        stableFrameCount = 0;
        lastStableText = "";
        beginScanning();
    }

    private void goToMenu() {
        stopScanning();
        postScanControls.setVisibility(View.GONE);
        resultCard.setVisibility(View.GONE);
        ttsProgressLayout.setVisibility(View.GONE);
        hasAutoCapture = false;
        stableFrameCount = 0;
        lastStableText = "";
        speakThenListen("Back to main menu. Say Scan or tap Start Scanning when ready.");
    }

    private void copyText() {
        String text = ocrResultText.getText().toString();
        if (text.isEmpty() || text.equals("Awaiting text detection...")) {
            speakThenListen("No text to copy yet.");
            return;
        }
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("OCR Text", text));
        Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show();
        speakThenListen("Text copied to clipboard.");
    }

    private void setStatus(String icon, String title, String desc) {
        statusIcon.setText(icon);
        statusTitle.setText(title);
        statusDesc.setText(desc);
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  SETTINGS
    // ──────────────────────────────────────────────────────────────────────────
    private void setupSettings() {
        rateSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean f) {
                speechRate = 0.5f + p * 0.1f;
                rateVal.setText(String.format(Locale.US, "%.1f×", speechRate));
                if (audioOutputManager != null) audioOutputManager.setSpeechRate(speechRate);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        volSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean f) {
                speechVolume = p / 10.0f;
                volVal.setText((int)(speechVolume * 100) + "%");
                if (audioOutputManager != null) audioOutputManager.setSpeechVolume(speechVolume);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        threshSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean f) {
                captureThreshold = 10 + p * 9;
                threshVal.setText(captureThreshold + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  PERMISSIONS
    // ──────────────────────────────────────────────────────────────────────────
    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
    }

    private void requestAudioPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, RECORD_AUDIO_PERMISSION_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                beginScanning();
            } else {
                Toast.makeText(this, "Camera permission required.", Toast.LENGTH_LONG).show();
                speak("Camera permission is required. Please grant it in settings.");
            }
        } else if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceListening();
            }
        }
    }
}
