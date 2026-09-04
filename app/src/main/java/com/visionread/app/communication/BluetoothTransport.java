package com.visionread.app.communication;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class BluetoothTransport implements ConnectionTransport {

    private static final String TAG = "BluetoothTransport";
    private static final String SERVICE_NAME = "VisionReadGlasses";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private TransportListener listener;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothServerSocket serverSocket;
    private BluetoothSocket clientSocket;
    private DataInputStream inputStream;
    private DataOutputStream outputStream;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isSendingFrame = new AtomicBoolean(false);
    private ExecutorService networkExecutor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object sendLock = new Object();

    public BluetoothTransport() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    @Override
    public void setListener(TransportListener listener) {
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void startServer(int port) {
        disconnect();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            postError("Bluetooth is not enabled");
            return;
        }

        isRunning.set(true);
        if (networkExecutor == null || networkExecutor.isShutdown()) {
            networkExecutor = Executors.newCachedThreadPool();
        }

        networkExecutor.execute(() -> {
            try {
                serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID);
                postStatus("Bluetooth waiting for connection...");

                while (isRunning.get() && serverSocket != null) {
                    BluetoothSocket socket = serverSocket.accept();
                    if (socket != null) {
                        handleConnection(socket);
                        break;
                    }
                }
            } catch (IOException e) {
                if (isRunning.get()) {
                    Log.e(TAG, "BT Server error: " + e.getMessage());
                    postError("BT Server error: " + e.getMessage());
                }
            }
        });
    }

    @SuppressLint("MissingPermission")
    @Override
    public void connect(String targetDeviceAddress, int port) {
        disconnect();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            postError("Bluetooth is not enabled");
            return;
        }

        isRunning.set(true);
        if (networkExecutor == null || networkExecutor.isShutdown()) {
            networkExecutor = Executors.newCachedThreadPool();
        }

        networkExecutor.execute(() -> {
            try {
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(targetDeviceAddress);
                postStatus("Connecting to " + device.getName() + " (" + targetDeviceAddress + ")...");
                BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothAdapter.cancelDiscovery();
                socket.connect();
                handleConnection(socket);
            } catch (Exception e) {
                if (isRunning.get()) {
                    Log.e(TAG, "BT Connect error: " + e.getMessage());
                    postDisconnected("BT connect failed: " + e.getMessage());
                }
            }
        });
    }

    private void handleConnection(BluetoothSocket socket) {
        try {
            clientSocket = socket;
            inputStream = new DataInputStream(clientSocket.getInputStream());
            outputStream = new DataOutputStream(clientSocket.getOutputStream());

            String remoteName = clientSocket.getRemoteDevice().getAddress();
            postConnected("BT:" + remoteName);

            readLoop();
        } catch (IOException e) {
            Log.e(TAG, "BT Connection handler error: " + e.getMessage());
            postDisconnected(e.getMessage());
        } finally {
            closeSocket();
        }
    }

    private void readLoop() {
        try {
            while (isRunning.get() && clientSocket != null && clientSocket.isConnected()) {
                byte packetType = inputStream.readByte();
                long timestamp = inputStream.readLong();
                int length = inputStream.readInt();

                if (length < 0 || length > 10 * 1024 * 1024) {
                    Log.w(TAG, "Invalid BT packet length: " + length);
                    break;
                }

                byte[] payload = new byte[length];
                inputStream.readFully(payload);

                switch (packetType) {
                    case TYPE_FRAME:
                        postFrame(payload, timestamp);
                        break;
                    case TYPE_AUDIO:
                        String audioCmd = new String(payload, StandardCharsets.UTF_8);
                        postAudio(audioCmd, timestamp);
                        break;
                    case TYPE_BUTTON:
                        if (payload.length >= 2) {
                            int btnId = payload[0];
                            int clickType = payload[1];
                            postButtonEvent(btnId, clickType, timestamp);
                        }
                        break;
                    case TYPE_HEARTBEAT:
                        break;
                    case TYPE_CONTROL:
                        String ctrl = new String(payload, StandardCharsets.UTF_8);
                        postStatus("Remote BT: " + ctrl);
                        break;
                }
            }
        } catch (IOException e) {
            if (isRunning.get()) {
                Log.d(TAG, "BT Read loop terminated: " + e.getMessage());
                postDisconnected(e.getMessage());
            }
        }
    }

    @Override
    public void sendFrame(byte[] jpegBytes) {
        if (!isConnected() || jpegBytes == null || jpegBytes.length == 0) return;
        if (!isSendingFrame.compareAndSet(false, true)) return;

        networkExecutor.execute(() -> {
            try {
                synchronized (sendLock) {
                    if (outputStream != null) {
                        outputStream.writeByte(TYPE_FRAME);
                        outputStream.writeLong(System.currentTimeMillis());
                        outputStream.writeInt(jpegBytes.length);
                        outputStream.write(jpegBytes);
                        outputStream.flush();
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "BT Send frame failed: " + e.getMessage());
            } finally {
                isSendingFrame.set(false);
            }
        });
    }

    @Override
    public void sendAudio(String textOrAudioCmd) {
        if (!isConnected() || textOrAudioCmd == null) return;
        byte[] bytes = textOrAudioCmd.getBytes(StandardCharsets.UTF_8);

        networkExecutor.execute(() -> {
            try {
                synchronized (sendLock) {
                    if (outputStream != null) {
                        outputStream.writeByte(TYPE_AUDIO);
                        outputStream.writeLong(System.currentTimeMillis());
                        outputStream.writeInt(bytes.length);
                        outputStream.write(bytes);
                        outputStream.flush();
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "BT Send audio failed: " + e.getMessage());
            }
        });
    }

    @Override
    public void sendButtonEvent(int buttonId, int clickType) {
        if (!isConnected()) return;
        byte[] payload = new byte[]{(byte) buttonId, (byte) clickType};

        networkExecutor.execute(() -> {
            try {
                synchronized (sendLock) {
                    if (outputStream != null) {
                        outputStream.writeByte(TYPE_BUTTON);
                        outputStream.writeLong(System.currentTimeMillis());
                        outputStream.writeInt(payload.length);
                        outputStream.write(payload);
                        outputStream.flush();
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "BT Send button event failed: " + e.getMessage());
            }
        });
    }

    @Override
    public void sendControl(String message) {
        if (!isConnected() || message == null) return;
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);

        networkExecutor.execute(() -> {
            try {
                synchronized (sendLock) {
                    if (outputStream != null) {
                        outputStream.writeByte(TYPE_CONTROL);
                        outputStream.writeLong(System.currentTimeMillis());
                        outputStream.writeInt(bytes.length);
                        outputStream.write(bytes);
                        outputStream.flush();
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "BT Send control failed: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean isConnected() {
        return isRunning.get() && clientSocket != null && clientSocket.isConnected();
    }

    @Override
    public void disconnect() {
        isRunning.set(false);
        closeSocket();
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
            serverSocket = null;
        }
    }

    private void closeSocket() {
        try {
            if (inputStream != null) inputStream.close();
        } catch (IOException ignored) {}
        try {
            if (outputStream != null) outputStream.close();
        } catch (IOException ignored) {}
        try {
            if (clientSocket != null) clientSocket.close();
        } catch (IOException ignored) {}
        inputStream = null;
        outputStream = null;
        clientSocket = null;
    }

    @Override
    public String getTransportType() {
        return "Bluetooth";
    }

    private void postConnected(String addr) {
        mainHandler.post(() -> {
            if (listener != null) listener.onConnected(addr);
        });
    }

    private void postDisconnected(String reason) {
        mainHandler.post(() -> {
            if (listener != null) listener.onDisconnected(reason);
        });
    }

    private void postFrame(byte[] jpegBytes, long timestamp) {
        if (listener != null) listener.onFrameReceived(jpegBytes, timestamp);
    }

    private void postAudio(String textOrAudioCmd, long timestamp) {
        mainHandler.post(() -> {
            if (listener != null) listener.onAudioReceived(textOrAudioCmd, timestamp);
        });
    }

    private void postButtonEvent(int buttonId, int clickType, long timestamp) {
        mainHandler.post(() -> {
            if (listener != null) listener.onButtonEventReceived(buttonId, clickType, timestamp);
        });
    }

    private void postStatus(String status) {
        mainHandler.post(() -> {
            if (listener != null) listener.onStatusChanged(status);
        });
    }

    private void postError(String err) {
        mainHandler.post(() -> {
            if (listener != null) listener.onError(err);
        });
    }
}
