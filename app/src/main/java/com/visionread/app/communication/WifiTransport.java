package com.visionread.app.communication;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class WifiTransport implements ConnectionTransport {

    private static final String TAG = "WifiTransport";
    public static final int DEFAULT_PORT = 8998;

    private TransportListener listener;
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private DataInputStream inputStream;
    private DataOutputStream outputStream;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isSendingFrame = new AtomicBoolean(false);
    private ExecutorService networkExecutor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Object sendLock = new Object();

    @Override
    public void setListener(TransportListener listener) {
        this.listener = listener;
    }

    @Override
    public void startServer(int port) {
        disconnect();
        isRunning.set(true);
        if (networkExecutor == null || networkExecutor.isShutdown()) {
            networkExecutor = Executors.newCachedThreadPool();
        }

        networkExecutor.execute(() -> {
            try {
                serverSocket = new ServerSocket(port > 0 ? port : DEFAULT_PORT);
                Log.d(TAG, "Server listening on port " + serverSocket.getLocalPort());
                postStatus("Listening on port " + serverSocket.getLocalPort());

                while (isRunning.get() && !serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    Log.d(TAG, "Accepted connection from " + socket.getRemoteSocketAddress());
                    handleConnection(socket);
                }
            } catch (IOException e) {
                if (isRunning.get()) {
                    Log.e(TAG, "Server error: " + e.getMessage());
                    postError("Server error: " + e.getMessage());
                }
            }
        });
    }

    @Override
    public void connect(String targetHost, int port) {
        disconnect();
        isRunning.set(true);
        if (networkExecutor == null || networkExecutor.isShutdown()) {
            networkExecutor = Executors.newCachedThreadPool();
        }

        networkExecutor.execute(() -> {
            try {
                int targetPort = port > 0 ? port : DEFAULT_PORT;
                Log.d(TAG, "Connecting to " + targetHost + ":" + targetPort);
                postStatus("Connecting to " + targetHost + ":" + targetPort);

                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(targetHost, targetPort), 5000);
                handleConnection(socket);
            } catch (IOException e) {
                if (isRunning.get()) {
                    Log.e(TAG, "Connect error: " + e.getMessage());
                    postDisconnected("Failed to connect: " + e.getMessage());
                }
            }
        });
    }

    private void handleConnection(Socket socket) {
        try {
            clientSocket = socket;
            clientSocket.setTcpNoDelay(true);
            clientSocket.setKeepAlive(true);

            inputStream = new DataInputStream(clientSocket.getInputStream());
            outputStream = new DataOutputStream(clientSocket.getOutputStream());

            String remoteAddr = clientSocket.getRemoteSocketAddress().toString();
            postConnected(remoteAddr);

            readLoop();
        } catch (IOException e) {
            Log.e(TAG, "Connection handler error: " + e.getMessage());
            postDisconnected(e.getMessage());
        } finally {
            closeSocket();
        }
    }

    private void readLoop() {
        try {
            while (isRunning.get() && clientSocket != null && !clientSocket.isClosed()) {
                byte packetType = inputStream.readByte();
                long timestamp = inputStream.readLong();
                int length = inputStream.readInt();

                if (length < 0 || length > 10 * 1024 * 1024) {
                    Log.w(TAG, "Invalid packet length: " + length);
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
                        // Heartbeat response
                        break;
                    case TYPE_CONTROL:
                        String ctrl = new String(payload, StandardCharsets.UTF_8);
                        postStatus("Remote: " + ctrl);
                        break;
                }
            }
        } catch (IOException e) {
            if (isRunning.get()) {
                Log.d(TAG, "Read loop terminated: " + e.getMessage());
                postDisconnected(e.getMessage());
            }
        }
    }

    @Override
    public void sendFrame(byte[] jpegBytes) {
        if (!isConnected() || jpegBytes == null || jpegBytes.length == 0) return;
        // Drop frame if previous is still sending to maintain real-time low latency
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
                Log.e(TAG, "Send frame failed: " + e.getMessage());
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
                Log.e(TAG, "Send audio failed: " + e.getMessage());
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
                Log.e(TAG, "Send button event failed: " + e.getMessage());
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
                Log.e(TAG, "Send control failed: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean isConnected() {
        return isRunning.get() && clientSocket != null && clientSocket.isConnected() && !clientSocket.isClosed();
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
        return "Wi-Fi";
    }

    // ── Dispatchers to main thread ──
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
