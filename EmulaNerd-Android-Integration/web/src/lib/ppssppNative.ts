type PpssppPlugin = {
  isAvailable: () => Promise<{
    available?: boolean;
    abi?: string;
    reason?: string;
  }>;
  startRom: (options: {
    gameId: string;
    fileName: string;
    sizeBytes: number;
    sha256?: string;
  }) => Promise<{ sessionId: string; reused?: boolean }>;
  writeChunk: (options: {
    sessionId: string;
    data: string;
  }) => Promise<{ writtenBytes?: number }>;
  finishRom: (options: { sessionId: string }) => Promise<{
    state?: string;
    resultCode?: number;
    reason?: string;
    errorMessage?: string;
    telemetry?: boolean;
    durationMs?: number;
    frames?: number;
    fps?: number;
    expectedFrames?: number;
    droppedFrames?: number;
    maxFrameGapMs?: number;
    gapOver33Ms?: number;
    gapOver100Ms?: number;
  }>;
  cancelRom: (options: { sessionId: string }) => Promise<void>;
};

type CapacitorWindow = Window & {
  Capacitor?: {
    Plugins?: {
      PpssppNative?: PpssppPlugin;
    };
  };
};

const CHUNK_SIZE = 256 * 1024;

function nativePlugin(): PpssppPlugin | undefined {
  if (typeof window === "undefined") return undefined;
  return (window as CapacitorWindow).Capacitor?.Plugins?.PpssppNative;
}

export type PpssppNativeAvailability = {
  available: boolean;
  abi?: string;
  reason?: string;
};

/**
 * Probes the actual PPSSPP JNI library instead of treating the Capacitor
 * plugin registration as proof that a native engine is usable.
 */
export async function getPpssppNativeAvailability(): Promise<PpssppNativeAvailability> {
  const plugin = nativePlugin();
  if (!plugin) {
    return { available: false, reason: "ponte PPSSPP não instalada" };
  }
  try {
    const result = await plugin.isAvailable();
    return {
      available: result.available === true,
      abi: result.abi,
      reason: result.reason,
    };
  } catch {
    return { available: false, reason: "não foi possível verificar a biblioteca PPSSPP" };
  }
}

export function isPpssppNativeAvailable(): boolean {
  return Boolean(nativePlugin());
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  const sliceSize = 0x8000;
  for (let offset = 0; offset < bytes.length; offset += sliceSize) {
    binary += String.fromCharCode(
      ...bytes.subarray(offset, Math.min(offset + sliceSize, bytes.length)),
    );
  }
  return btoa(binary);
}

export async function launchNativePspRom(
  blob: Blob,
  gameId: string,
  fileName: string,
  sha256: string | undefined,
  onProgress?: (progress: number) => void,
): Promise<{
  state?: string;
  resultCode?: number;
  reason?: string;
  errorMessage?: string;
  telemetry?: boolean;
  durationMs?: number;
  frames?: number;
  fps?: number;
  expectedFrames?: number;
  droppedFrames?: number;
  maxFrameGapMs?: number;
  gapOver33Ms?: number;
  gapOver100Ms?: number;
}> {
  const plugin = nativePlugin();
  if (!plugin) {
    throw new Error("O motor nativo do PPSSPP não está instalado neste APK.");
  }

  const availability = await plugin.isAvailable();
  if (availability.available !== true) {
    throw new Error("O motor nativo do PPSSPP não conseguiu carregar neste aparelho.");
  }

  const { sessionId, reused } = await plugin.startRom({
    gameId,
    fileName,
    sizeBytes: blob.size,
    sha256,
  });

  try {
    if (reused) {
      onProgress?.(100);
    } else {
      for (let offset = 0; offset < blob.size; offset += CHUNK_SIZE) {
        const chunk = new Uint8Array(
          await blob.slice(offset, Math.min(offset + CHUNK_SIZE, blob.size)).arrayBuffer(),
        );
        await plugin.writeChunk({
          sessionId,
          data: bytesToBase64(chunk),
        });
        onProgress?.(Math.round(((offset + chunk.length) / blob.size) * 100));
      }
    }
    return await plugin.finishRom({ sessionId });
  } catch (error) {
    await plugin.cancelRom({ sessionId }).catch(() => undefined);
    throw error;
  }
}
