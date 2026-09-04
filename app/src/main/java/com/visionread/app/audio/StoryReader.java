package com.visionread.app.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/** Story Mode controller: Gemini TTS generation + local audio playback. */
public class StoryReader {
    private static final String TAG = "StoryReader";
    private static final int SAMPLE_RATE = 24000;

    public interface Callback {
        void onGenerating();
        void onStarted();
        void onDone();
        void onError(String message);
    }

    private final Context context;
    private final GeminiTtsService geminiTts;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MediaPlayer mediaPlayer;
    private File cachedWav;
    private String lastText = "";
    private float playbackSpeed = 0.85f;
    private float volume = 1.0f;
    private final AtomicLong generationToken = new AtomicLong(0);

    public StoryReader(Context context) {
        this.context = context.getApplicationContext();
        this.geminiTts = new GeminiTtsService();
    }

    public void read(String text, Callback callback) {
        if (text == null || text.trim().isEmpty()) {
            if (callback != null) callback.onError("No text was detected.");
            return;
        }

        stop();
        final long token = generationToken.incrementAndGet();
        lastText = text.trim();
        if (callback != null) callback.onGenerating();

        geminiTts.synthesize(lastText, new GeminiTtsService.Callback() {
            @Override public void onSuccess(byte[] pcmData) {
                if (token != generationToken.get()) return;
                try {
                    cachedWav = writeWav(pcmData);
                    playFile(cachedWav, callback);
                } catch (Exception e) {
                    Log.e(TAG, "Could not prepare Story Mode audio", e);
                    if (callback != null) callback.onError("Could not play generated narration.");
                }
            }

            @Override public void onError(String message) {
                if (token != generationToken.get()) return;
                if (callback != null) callback.onError(message);
            }
        });
    }

    public void repeat(Callback callback) {
        if (cachedWav == null || !cachedWav.exists()) {
            if (lastText.isEmpty()) {
                if (callback != null) callback.onError("No story audio is available yet.");
            } else {
                read(lastText, callback);
            }
            return;
        }
        stopPlayerOnly();
        playFile(cachedWav, callback);
    }

    private void playFile(File file, Callback callback) {
        try {
            MediaPlayer player = new MediaPlayer();
            mediaPlayer = player;
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setDataSource(file.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                applyPlaybackParams(mp);
                mp.start();
                if (callback != null) callback.onStarted();
            });
            player.setOnCompletionListener(mp -> {
                if (callback != null) callback.onDone();
                releasePlayer(mp);
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Story audio playback error: " + what + "/" + extra);
                releasePlayer(mp);
                if (callback != null) callback.onError("Story audio playback failed.");
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Story playback setup failed", e);
            if (callback != null) callback.onError("Story audio could not be started.");
        }
    }

    public void pause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause();
    }

    public void resume() {
        if (mediaPlayer != null) mediaPlayer.start();
    }

    public boolean isPaused() {
        return mediaPlayer != null && !mediaPlayer.isPlaying();
    }

    public boolean isSpeaking() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    public void stop() {
        generationToken.incrementAndGet();
        stopPlayerOnly();
    }

    private void stopPlayerOnly() {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            releasePlayer(mediaPlayer);
            mediaPlayer = null;
        }
    }

    public void setPlaybackSpeed(float speed) {
        playbackSpeed = Math.max(0.5f, Math.min(2.0f, speed));
        if (mediaPlayer != null && android.os.Build.VERSION.SDK_INT >= 23) {
            try { applyPlaybackParams(mediaPlayer); } catch (Exception ignored) {}
        }
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
        if (mediaPlayer != null) mediaPlayer.setVolume(this.volume, this.volume);
    }

    private void applyPlaybackParams(MediaPlayer player) {
        player.setVolume(volume, volume);
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            PlaybackParams params = player.getPlaybackParams();
            params.setSpeed(playbackSpeed);
            player.setPlaybackParams(params);
        }
    }

    private File writeWav(byte[] pcm) throws IOException {
        File file = new File(context.getCacheDir(), "visionread_story.wav");
        try (FileOutputStream out = new FileOutputStream(file)) {
            writeWavHeader(out, pcm.length, SAMPLE_RATE, 1, 16);
            out.write(pcm);
        }
        return file;
    }

    private void writeWavHeader(FileOutputStream out, long dataLength, long sampleRate,
                                int channels, int bitsPerSample) throws IOException {
        long byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        long chunkSize = 36 + dataLength;
        out.write(new byte[]{'R','I','F','F'});
        writeIntLE(out, chunkSize);
        out.write(new byte[]{'W','A','V','E'});
        out.write(new byte[]{'f','m','t',' '});
        writeIntLE(out, 16);
        writeShortLE(out, (short)1);
        writeShortLE(out, (short)channels);
        writeIntLE(out, sampleRate);
        writeIntLE(out, byteRate);
        writeShortLE(out, (short)blockAlign);
        writeShortLE(out, (short)bitsPerSample);
        out.write(new byte[]{'d','a','t','a'});
        writeIntLE(out, dataLength);
    }

    private void writeIntLE(FileOutputStream out, long value) throws IOException {
        out.write((int)(value & 0xff));
        out.write((int)((value >> 8) & 0xff));
        out.write((int)((value >> 16) & 0xff));
        out.write((int)((value >> 24) & 0xff));
    }

    private void writeShortLE(FileOutputStream out, short value) throws IOException {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    private void releasePlayer(MediaPlayer player) {
        try { player.reset(); } catch (Exception ignored) {}
        try { player.release(); } catch (Exception ignored) {}
        if (mediaPlayer == player) mediaPlayer = null;
    }

    public String getLastText() { return lastText; }

    public void shutdown() {
        stop();
        geminiTts.shutdown();
        if (cachedWav != null) cachedWav.delete();
    }
}
