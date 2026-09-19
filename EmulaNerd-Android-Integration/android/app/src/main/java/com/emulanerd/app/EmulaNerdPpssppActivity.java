package com.emulanerd.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Choreographer;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * PPSSPP entry point owned by EmulaNerd. Android scoped storage does not allow
 * the upstream default (/storage/emulated/0/PSP) to be written reliably, so we
 * give PPSSPP a private, writable memory-stick directory before it initializes.
 */
public class EmulaNerdPpssppActivity extends org.ppsspp.ppsspp.PpssppActivity {
    private static final String TAG = "EmulaNerdPPSSPP";
    private final Choreographer.FrameCallback frameCallback = this::onFrame;
    private boolean frameMonitorRunning;
    private long monitorStartedAtNanos;
    private long lastFrameAtNanos;
    private long maxFrameGapNanos;
    private int frameCount;
    private int gapOver33Ms;
    private int gapOver100Ms;
    private boolean nativeFailed;

    @Override
    public void onCreate(Bundle state) {
        try {
            prepareWritableMemstick();
            super.onCreate(state);
        } catch (Throwable error) {
            nativeFailed = true;
            Log.e(TAG, "Falha ao criar a sessão PPSSPP nativa", error);
            Intent result = new Intent()
                    .putExtra("emulanerdNativeState", "failed")
                    .putExtra("telemetryReason", "native-init-failed")
                    .putExtra("nativeErrorMessage", "O PPSSPP nativo não conseguiu iniciar este jogo. O EmulaNerd tentará o modo compatível.");
            setResult(RESULT_CANCELED, result);
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nativeFailed) return;
        startFrameMonitor();
    }

    @Override
    protected void onPause() {
        stopFrameMonitor();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopFrameMonitor();
        if (!nativeFailed) publishFrameMetrics();
        super.onDestroy();
    }

    private void startFrameMonitor() {
        if (frameMonitorRunning) return;
        frameMonitorRunning = true;
        if (monitorStartedAtNanos == 0L) monitorStartedAtNanos = System.nanoTime();
        lastFrameAtNanos = 0L;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void stopFrameMonitor() {
        if (!frameMonitorRunning) return;
        frameMonitorRunning = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        lastFrameAtNanos = 0L;
    }

    private void onFrame(long frameTimeNanos) {
        if (!frameMonitorRunning) return;
        if (lastFrameAtNanos > 0L) {
            long gap = frameTimeNanos - lastFrameAtNanos;
            maxFrameGapNanos = Math.max(maxFrameGapNanos, gap);
            if (gap > 33_000_000L) gapOver33Ms++;
            if (gap > 100_000_000L) gapOver100Ms++;
        }
        lastFrameAtNanos = frameTimeNanos;
        frameCount++;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void publishFrameMetrics() {
        if (monitorStartedAtNanos == 0L || frameCount < 2) return;
        long durationMs = Math.max(1L, (System.nanoTime() - monitorStartedAtNanos) / 1_000_000L);
        int expectedFrames = Math.max(1, Math.round(durationMs / (1000f / 60f)));
        int droppedFrames = Math.max(0, expectedFrames - frameCount);
        Intent result = new Intent();
        result.putExtra("emulanerdTelemetry", true);
        result.putExtra("telemetryDurationMs", durationMs);
        result.putExtra("telemetryFrames", frameCount);
        result.putExtra("telemetryFps", frameCount * 1000d / durationMs);
        result.putExtra("telemetryExpectedFrames", expectedFrames);
        result.putExtra("telemetryDroppedFrames", droppedFrames);
        result.putExtra("telemetryMaxFrameGapMs", maxFrameGapNanos / 1_000_000d);
        result.putExtra("telemetryGapOver33Ms", gapOver33Ms);
        result.putExtra("telemetryGapOver100Ms", gapOver100Ms);
        setResult(RESULT_OK, result);
        Log.i(TAG, "Gameplay frame metrics: fps=" + (frameCount * 1000d / durationMs)
                + ", dropped=" + droppedFrames + ", maxGapMs=" + (maxFrameGapNanos / 1_000_000d));
    }

    private void prepareWritableMemstick() {
        File filesDir = getFilesDir();
        File externalFilesDir = getExternalFilesDir(null);
        if (filesDir == null || externalFilesDir == null) {
            Log.e(TAG, "PPSSPP storage directories are unavailable");
            return;
        }

        File memstick = new File(externalFilesDir, "ppsspp-memstick");
        File stateDir = new File(memstick, "PSP/PPSSPP_STATE");
        File systemDir = new File(memstick, "PSP/SYSTEM");
        if (!stateDir.isDirectory() && !stateDir.mkdirs()) {
            Log.e(TAG, "Could not create PPSSPP savestate directory: " + stateDir);
            return;
        }
        if (!systemDir.isDirectory() && !systemDir.mkdirs()) {
            Log.e(TAG, "Could not create PPSSPP system directory: " + systemDir);
            return;
        }

        File marker = new File(filesDir, "memstick_dir.txt");
        try (FileOutputStream output = new FileOutputStream(marker, false)) {
            output.write(memstick.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
            output.write('\n');
        } catch (Exception error) {
            Log.e(TAG, "Could not configure PPSSPP writable memstick", error);
        }
    }
}
