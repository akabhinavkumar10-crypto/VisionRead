package com.visionread.app.communication;

public interface ConnectionTransport {

    byte TYPE_FRAME = 1;
    byte TYPE_AUDIO = 2;
    byte TYPE_BUTTON = 3;
    byte TYPE_HEARTBEAT = 4;
    byte TYPE_CONTROL = 5;

    int BUTTON_ACTION = 1;
    int BUTTON_REPEAT = 2;
    int BUTTON_EMERGENCY = 3;

    int CLICK_SHORT = 0;
    int CLICK_LONG = 1;

    interface TransportListener {
        void onConnected(String remoteAddress);
        void onDisconnected(String reason);
        void onFrameReceived(byte[] jpegBytes, long timestamp);
        void onAudioReceived(String textOrAudioCmd, long timestamp);
        void onButtonEventReceived(int buttonId, int clickType, long timestamp);
        void onStatusChanged(String status);
        void onError(String error);
    }

    void setListener(TransportListener listener);

    void startServer(int port);

    void connect(String targetHostOrAddress, int port);

    void disconnect();

    boolean isConnected();

    void sendFrame(byte[] jpegBytes);

    void sendAudio(String textOrAudioCmd);

    void sendButtonEvent(int buttonId, int clickType);

    void sendControl(String message);

    String getTransportType();
}
