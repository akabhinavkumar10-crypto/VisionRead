package com.visionread.app.audio;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.visionread.app.BuildConfig;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small REST client for Gemini TTS used only by Story Mode.
 * Gemini returns raw PCM: 24 kHz, mono, signed 16-bit.
 */
public class GeminiTtsService {
    private static final String TAG = "GeminiTtsService";
    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-tts-preview:generateContent";
    private static final String MODEL = "gemini-3.1-flash-tts-preview";
    private static final String VOICE = "Kore";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onSuccess(byte[] pcmData);
        void onError(String message);
    }

    public void synthesize(String text, Callback callback) {
        if (text == null || text.trim().isEmpty()) {
            postError(callback, "There is no text to narrate.");
            return;
        }
        if (BuildConfig.GEMINI_API_KEY == null || BuildConfig.GEMINI_API_KEY.trim().isEmpty()) {
            postError(callback, "Gemini API key is not configured.");
            return;
        }

        final String cleanText = text.trim();
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(ENDPOINT);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(60000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY);

                JSONObject voice = new JSONObject();
                voice.put("voiceName", VOICE);

                JSONObject prebuilt = new JSONObject();
                prebuilt.put("prebuiltVoiceConfig", voice);

                JSONObject voiceConfig = new JSONObject();
                voiceConfig.put("voiceConfig", prebuilt);

                JSONObject speechConfig = new JSONObject();
                speechConfig.put("voiceConfig", prebuilt);

                JSONObject generationConfig = new JSONObject();
                generationConfig.put("responseModalities", new org.json.JSONArray().put("AUDIO"));
                generationConfig.put("speechConfig", speechConfig);

                String instruction =
                        "You are a warm, natural audiobook narrator helping a visually impaired listener. " +
                        "Read the supplied text exactly as written. Do not summarize, add, remove, or invent content. " +
                        "Use natural storytelling: comfortable pacing, meaningful pauses, gentle emphasis, and expressive " +
                        "intonation for questions and exclamations. Distinguish quoted dialogue subtly from narration. " +
                        "Do not overact. Treat this as professional audiobook narration.\n\nTEXT:\n" + cleanText;

                JSONObject textPart = new JSONObject();
                textPart.put("text", instruction);
                JSONObject parts = new JSONObject();
                parts.put("parts", new org.json.JSONArray().put(textPart));
                org.json.JSONArray contents = new org.json.JSONArray().put(parts);

                JSONObject body = new JSONObject();
                body.put("contents", contents);
                body.put("generationConfig", generationConfig);
                body.put("model", MODEL);

                byte[] requestBytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(requestBytes);
                }

                int code = connection.getResponseCode();
                InputStream input = code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String response = readAll(input);

                if (code < 200 || code >= 300) {
                    Log.e(TAG, "Gemini HTTP " + code + ": " + response);
                    postError(callback, "Gemini request failed (HTTP " + code + ").");
                    return;
                }

                JSONObject root = new JSONObject(response);
                org.json.JSONArray candidates = root.optJSONArray("candidates");
                if (candidates == null || candidates.length() == 0) {
                    postError(callback, "Gemini returned no audio.");
                    return;
                }

                JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
                org.json.JSONArray responseParts = content == null ? null : content.optJSONArray("parts");
                if (responseParts == null || responseParts.length() == 0) {
                    postError(callback, "Gemini returned no speech data.");
                    return;
                }

                String encoded = null;
                for (int i = 0; i < responseParts.length(); i++) {
                    JSONObject part = responseParts.optJSONObject(i);
                    if (part == null) continue;
                    JSONObject inlineData = part.optJSONObject("inlineData");
                    if (inlineData != null) {
                        encoded = inlineData.optString("data", null);
                        if (encoded != null && !encoded.isEmpty()) break;
                    }
                }

                if (encoded == null || encoded.isEmpty()) {
                    postError(callback, "Gemini returned an empty audio payload.");
                    return;
                }

                byte[] pcm = Base64.decode(encoded, Base64.DEFAULT);
                if (pcm.length == 0) {
                    postError(callback, "Gemini returned empty audio.");
                    return;
                }
                mainHandler.post(() -> callback.onSuccess(pcm));
            } catch (Exception e) {
                Log.e(TAG, "Gemini TTS error", e);
                postError(callback, "Could not generate Story Mode audio.");
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void postError(Callback callback, String message) {
        if (callback != null) mainHandler.post(() -> callback.onError(message));
    }

    private String readAll(InputStream input) throws IOException {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
