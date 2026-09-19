package com.emulanerd.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.util.Base64;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.PluginMethod;

import org.ppsspp.ppsspp.PpssppActivity;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Streams a catalog ROM into the app cache and opens it in the bundled PPSSPP
 * activity. The ROM never has to be represented as one huge JS base64 string.
 */
@CapacitorPlugin(name = "PpssppNative")
public class PpssppNativePlugin extends Plugin {
    private static final long MAX_ROM_BYTES = 8L * 1024L * 1024L * 1024L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "emulanerd-psp-io");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<String, RomSession> SESSIONS = new ConcurrentHashMap<>();

    private static final class RomSession {
        final File file;
        final File marker;
        final FileOutputStream output;
        final long expectedBytes;
        final String sha256;
        final boolean reused;
        long writtenBytes;

        RomSession(File file, File marker, FileOutputStream output, long expectedBytes,
                   String sha256, boolean reused) {
            this.file = file;
            this.marker = marker;
            this.output = output;
            this.expectedBytes = expectedBytes;
            this.sha256 = sha256;
            this.reused = reused;
            this.writtenBytes = reused ? expectedBytes : 0L;
        }
    }

    @PluginMethod
    public void isAvailable(PluginCall call) {
        JSObject result = new JSObject();
        result.put("available", PpssppActivity.libraryLoaded);
        call.resolve(result);
    }

    @PluginMethod
    public void startRom(PluginCall call) {
        String gameId = safeSegment(call.getString("gameId", "game"), "game");
        String fileName = safeFileName(call.getString("fileName", "game.iso"));
        long expectedBytes = Math.max(0L, call.getLong("sizeBytes", 0L));
        String sha256 = safeHash(call.getString("sha256", ""));
        if (expectedBytes > MAX_ROM_BYTES) {
            call.reject("A ROM excede o limite de tamanho suportado pelo APK.");
            return;
        }

        try {
            File directory = new File(new File(getActivity().getFilesDir(), "native-roms/psp"), gameId);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IllegalStateException("Não foi possível preparar o armazenamento nativo do PSP.");
            }
            File file = new File(directory, fileName);
            File marker = new File(file.getAbsolutePath() + ".complete");
            boolean reusable = expectedBytes > 0L && file.isFile() && file.length() == expectedBytes
                    && markerMatches(marker, expectedBytes, sha256);
            if (!reusable && marker.exists()) marker.delete();
            FileOutputStream output = reusable ? null : new FileOutputStream(file, false);
            String sessionId = UUID.randomUUID().toString();
            SESSIONS.put(sessionId, new RomSession(file, marker, output, expectedBytes, sha256, reusable));

            JSObject result = new JSObject();
            result.put("sessionId", sessionId);
            result.put("fileName", fileName);
            result.put("reused", reusable);
            call.resolve(result);
        } catch (Exception error) {
            call.reject(message(error, "Não foi possível preparar a ROM para o PPSSPP."), error);
        }
    }

    @PluginMethod
    public void writeChunk(PluginCall call) {
        String sessionId = call.getString("sessionId", "");
        String encoded = call.getString("data", "");
        RomSession session = SESSIONS.get(sessionId);
        if (session == null || session.reused || session.output == null || encoded.isEmpty()) {
            call.reject("Sessão de transferência da ROM inválida.");
            return;
        }

        IO.execute(() -> {
            try {
                byte[] chunk = Base64.decode(encoded, Base64.DEFAULT);
                if (chunk.length == 0 || session.writtenBytes + chunk.length > MAX_ROM_BYTES) {
                    throw new IllegalStateException("A ROM excede o limite de tamanho suportado pelo APK.");
                }
                session.output.write(chunk);
                session.writtenBytes += chunk.length;
                JSObject result = new JSObject();
                result.put("writtenBytes", session.writtenBytes);
                call.resolve(result);
            } catch (Exception error) {
                call.reject(message(error, "Não foi possível gravar a ROM no cache do APK."), error);
            }
        });
    }

    @PluginMethod
    public void finishRom(PluginCall call) {
        String sessionId = call.getString("sessionId", "");
        RomSession session = SESSIONS.remove(sessionId);
        if (session == null) {
            call.reject("Sessão de transferência da ROM não encontrada.");
            return;
        }

        IO.execute(() -> {
            boolean fileReady = false;
            try {
                if (!session.reused) {
                    session.output.flush();
                    session.output.close();
                }
                if (session.writtenBytes <= 0L
                        || (session.expectedBytes > 0L && session.writtenBytes != session.expectedBytes)) {
                    throw new IllegalStateException("A transferência da ROM terminou incompleta.");
                }

                if (!session.file.isFile() || !session.file.canRead()) {
                    throw new IllegalStateException("A ROM PSP não ficou disponível no armazenamento local do APK.");
                }
                if (!session.reused && !session.sha256.isEmpty()
                        && !session.sha256.equalsIgnoreCase(sha256(session.file))) {
                    throw new IllegalStateException("A integridade da ROM PSP não confere.");
                }
                if (!session.reused) {
                    writeMarker(session.marker, session.writtenBytes + "\n" + session.sha256 + "\n");
                }
                fileReady = true;

                // PPSSPP's Android content-URI bridge only understands URIs from
                // the Storage Access Framework (content://.../document/...). A
                // FileProvider URI points to /cache/... instead, so PPSSPP
                // treats it as a missing file. The bundled activity runs in the
                // same application and can safely receive this private absolute
                // path through its shortcut contract.
                String localPath = session.file.getAbsolutePath();
                Intent intent = new Intent(getActivity(), EmulaNerdPpssppActivity.class);
                intent.setAction(Intent.ACTION_MAIN);
                if (shouldForceOpenGlForLegacyVirtualDevice()) {
                    // BlueStacks Android 9 exposes a Vulkan device, but its
                    // virtualized Vulkan path can terminate the emulator when
                    // PPSSPP creates its render surface. Feed the normal
                    // PPSSPP command-line override before NativeInit so the
                    // C++ side creates OpenGL from the beginning.
                    intent.putExtra(PpssppActivity.ARGS_EXTRA_KEY,
                            "--graphics=opengl " + localPath);
                    android.util.Log.i("EmulaNerdPPSSPP",
                            "Legacy virtual device detected; launching PSP with OpenGL");
                } else {
                    intent.putExtra(PpssppActivity.SHORTCUT_EXTRA_KEY, localPath);
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                android.util.Log.i("EmulaNerdPPSSPP", "Launching local PSP ROM: "
                        + localPath + " (" + session.writtenBytes + " bytes)");

                getActivity().runOnUiThread(() -> {
                    try {
                        startActivityForResult(call, intent, "onPpssppActivityResult");
                    } catch (Exception error) {
                        call.reject(message(error, "Não foi possível abrir o PPSSPP no APK."), error);
                    }
                });
            } catch (Exception error) {
                closeQuietly(session);
                // Preserve a verified ROM when only the native activity failed
                // to open. The next attempt can use the web fallback without
                // forcing the user to download the same file again.
                if (!fileReady) {
                    if (session.file.exists()) session.file.delete();
                    if (session.marker.exists()) session.marker.delete();
                }
                call.reject(message(error, "Não foi possível finalizar a ROM para o PPSSPP."), error);
            }
        });
    }

    @PluginMethod
    public void cancelRom(PluginCall call) {
        String sessionId = call.getString("sessionId", "");
        RomSession session = SESSIONS.remove(sessionId);
        if (session != null && !session.reused) {
            IO.execute(() -> {
                closeQuietly(session);
                if (session.file.exists()) session.file.delete();
            });
        }
        call.resolve(new JSObject());
    }

    @ActivityCallback
    public void onPpssppActivityResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        JSObject response = new JSObject();
        int resultCode = result == null ? Activity.RESULT_CANCELED : result.getResultCode();
        Intent data = result == null ? null : result.getData();
        String state = data == null ? null : data.getStringExtra("emulanerdNativeState");
        if (state == null || state.trim().isEmpty()) {
            state = resultCode == Activity.RESULT_OK ? "closed" : "failed";
        }
        response.put("state", state);
        response.put("resultCode", resultCode);
        putIfPresent(response, "reason", data == null ? null : data.getStringExtra("telemetryReason"));
        putIfPresent(response, "errorMessage", data == null ? null : data.getStringExtra("nativeErrorMessage"));
        if (data != null && data.getBooleanExtra("emulanerdTelemetry", false)) {
            response.put("telemetry", true);
            response.put("durationMs", data.getLongExtra("telemetryDurationMs", 0L));
            response.put("frames", data.getIntExtra("telemetryFrames", 0));
            response.put("fps", data.getDoubleExtra("telemetryFps", 0d));
            response.put("expectedFrames", data.getIntExtra("telemetryExpectedFrames", 0));
            response.put("droppedFrames", data.getIntExtra("telemetryDroppedFrames", 0));
            response.put("maxFrameGapMs", data.getDoubleExtra("telemetryMaxFrameGapMs", 0d));
            response.put("gapOver33Ms", data.getIntExtra("telemetryGapOver33Ms", 0));
            response.put("gapOver100Ms", data.getIntExtra("telemetryGapOver100Ms", 0));
        }
        call.resolve(response);
    }

    private static void putIfPresent(JSObject object, String key, String value) {
        if (value != null && !value.trim().isEmpty()) object.put(key, value);
    }

    private static String safeSegment(String value, String fallback) {
        String normalized = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (normalized.isEmpty()) normalized = fallback;
        return normalized.substring(0, Math.min(normalized.length(), 100));
    }

    private static String safeFileName(String value) {
        String normalized = safeSegment(value, "game.iso");
        if (!normalized.toLowerCase().endsWith(".iso")
                && !normalized.toLowerCase().endsWith(".cso")
                && !normalized.toLowerCase().endsWith(".chd")
                && !normalized.toLowerCase().endsWith(".pbp")) {
            normalized += ".iso";
        }
        return normalized;
    }

    private static String safeHash(String value) {
        return value != null && value.matches("[A-Fa-f0-9]{64}") ? value.toLowerCase() : "";
    }

    private static boolean markerMatches(File marker, long size, String hash) {
        if (!marker.isFile()) return false;
        try (FileInputStream input = new FileInputStream(marker)) {
            byte[] bytes = new byte[(int) Math.min(Math.max(marker.length(), 1L), 4096L)];
            int length = input.read(bytes);
            if (length <= 0) return false;
            String[] lines = new String(bytes, 0, length, StandardCharsets.UTF_8).split("\\R", -1);
            if (lines.length == 0 || !Long.toString(size).equals(lines[0].trim())) return false;
            return hash.isEmpty() || (lines.length > 1 && hash.equalsIgnoreCase(lines[1].trim()));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void writeMarker(File marker, String value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(marker, false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[1024 * 1024];
            int length;
            while ((length = input.read(buffer)) >= 0) {
                if (length > 0) digest.update(buffer, 0, length);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static boolean shouldForceOpenGlForLegacyVirtualDevice() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P || Build.SUPPORTED_ABIS == null) {
            return false;
        }

        boolean x86_64 = false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if (abi != null && "x86_64".equalsIgnoreCase(abi)) {
                x86_64 = true;
                break;
            }
        }
        if (!x86_64) return false;

        String fingerprint = Build.FINGERPRINT == null
                ? ""
                : Build.FINGERPRINT.toLowerCase(java.util.Locale.US);
        String hardware = Build.HARDWARE == null
                ? ""
                : Build.HARDWARE.toLowerCase(java.util.Locale.US);
        return fingerprint.contains("p3sxxx")
                || fingerprint.contains("generic")
                || fingerprint.contains("emulator")
                || hardware.contains("goldfish")
                || hardware.contains("ranchu");
    }

    private static void closeQuietly(RomSession session) {
        if (session.output == null) return;
        try {
            session.output.close();
        } catch (Exception ignored) {
            // Cleanup is best effort after a failed transfer.
        }
    }

    private static String message(Exception error, String fallback) {
        return error.getMessage() == null || error.getMessage().isEmpty()
                ? fallback
                : error.getMessage();
    }
}
