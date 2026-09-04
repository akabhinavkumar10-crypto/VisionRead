package com.visionread.app.communication;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class DeviceConnectionManager implements ConnectionTransport.TransportListener {

    private static final String TAG = "DeviceConnectionManager";
    private static DeviceConnectionManager instance;

    public enum TransportMode {
        WIFI,
        BLUETOOTH
    }

    public interface ConnectionCallback {
        void onConnected(String address, String transportType);
        void onDisconnected(String reason);
        void onFrameReceived(byte[] jpegBytes, long latencyMs);
        void onAudioReceived(String textOrAudioCmd);
        void onButtonEvent(int buttonId, int clickType);
        void onStatusUpdate(String status);
    }

    private ConnectionTransport activeTransport;
    private TransportMode currentMode = TransportMode.WIFI;
    private ConnectionCallback callback;
    private boolean isServerMode = false;
    private long lastLatencyMs = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private DeviceConnectionManager() {
        setTransportMode(TransportMode.WIFI);
    }

    public static synchronized DeviceConnectionManager getInstance() {
        if (instance == null) {
            instance = new DeviceConnectionManager();
        }
        return instance;
    }

    public void setCallback(ConnectionCallback callback) {
        this.callback = callback;
    }

    public void setTransportMode(TransportMode mode) {
        if (activeTransport != null) {
            activeTransport.disconnect();
        }
        currentMode = mode;
        if (mode == TransportMode.WIFI) {
            activeTransport = new WifiTransport();
        } else {
            activeTransport = new BluetoothTransport();
        }
        activeTransport.setListener(this);
    }

    public TransportMode getTransportMode() {
        return currentMode;
    }

    public void startServer(int port) {
        isServerMode = true;
        if (activeTransport != null) {
            activeTransport.startServer(port);
        }
    }

    public void connect(String targetAddress, int port) {
        isServerMode = false;
        if (activeTransport != null) {
            activeTransport.connect(targetAddress, port);
        }
    }

    public void disconnect() {
        if (activeTransport != null) {
            activeTransport.disconnect();
        }
    }

    public boolean isConnected() {
        return activeTransport != null && activeTransport.isConnected();
    }

    public void sendFrame(byte[] jpegBytes) {
        if (activeTransport != null) {
            activeTransport.sendFrame(jpegBytes);
        }
    }

    public void sendAudio(String textOrAudioCmd) {
        if (activeTransport != null) {
            activeTransport.sendAudio(textOrAudioCmd);
        }
    }

    public void sendButtonEvent(int buttonId, int clickType) {
        if (activeTransport != null) {
            activeTransport.sendButtonEvent(buttonId, clickType);
        }
    }

    public void sendControl(String message) {
        if (activeTransport != null) {
            activeTransport.sendControl(message);
        }
    }

    public long getLastLatencyMs() {
        return lastLatencyMs;
    }

    public String getTransportName() {
        return activeTransport != null ? activeTransport.getTransportType() : "None";
    }

    // ── TransportListener implementation ──

    @Override
    public void onConnected(String remoteAddress) {
        Log.d(TAG, "Connected to: " + remoteAddress + " via " + getTransportName());
        if (callback != null) {
            callback.onConnected(remoteAddress, getTransportName());
        }
    }

    @Override
    public void onDisconnected(String reason) {
        Log.d(TAG, "Disconnected: " + reason);
        if (callback != null) {
            callback.onDisconnected(reason);
        }
    }

    @Override
    public void onFrameReceived(byte[] jpegBytes, long timestamp) {
        long now = System.currentTimeMillis();
        lastLatencyMs = Math.max(0, now - timestamp);
        if (callback != null) {
            callback.onFrameReceived(jpegBytes, lastLatencyMs);
        }
    }

    @Override
    public void onAudioReceived(String textOrAudioCmd, long timestamp) {
        if (callback != null) {
            callback.onAudioReceived(textOrAudioCmd);
        }
    }

    @Override
    public void onButtonEventReceived(int buttonId, int clickType, long timestamp) {
        if (callback != null) {
            callback.onButtonEvent(buttonId, clickType);
        }
    }

    @Override
    public void onStatusChanged(String status) {
        if (callback != null) {
            callback.onStatusUpdate(status);
        }
    }

    @Override
    public void onError(String error) {
        if (callback != null) {
            callback.onStatusUpdate("Error: " + error);
        }
    }
}
