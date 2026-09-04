package com.visionread.app.input;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.visionread.app.communication.ConnectionTransport;

public class GlassesButtonManager {

    private static final String TAG = "GlassesButtonManager";

    public interface ButtonEventListener {
        void onActionCapture(boolean isLongPress);
        void onRepeatMessage(boolean isLongPress);
        void onEmergencyHelp(boolean isLongPress);
        void onCustomButton(int buttonId, boolean isLongPress);
    }

    private ButtonEventListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void setButtonEventListener(ButtonEventListener listener) {
        this.listener = listener;
    }

    public void handleButtonEvent(int buttonId, int clickType) {
        boolean isLongPress = (clickType == ConnectionTransport.CLICK_LONG);
        Log.d(TAG, "Button received: ID=" + buttonId + " Long=" + isLongPress);

        mainHandler.post(() -> {
            if (listener == null) return;
            switch (buttonId) {
                case ConnectionTransport.BUTTON_ACTION:
                    listener.onActionCapture(isLongPress);
                    break;
                case ConnectionTransport.BUTTON_REPEAT:
                    listener.onRepeatMessage(isLongPress);
                    break;
                case ConnectionTransport.BUTTON_EMERGENCY:
                    listener.onEmergencyHelp(isLongPress);
                    break;
                default:
                    listener.onCustomButton(buttonId, isLongPress);
                    break;
            }
        });
    }
}
