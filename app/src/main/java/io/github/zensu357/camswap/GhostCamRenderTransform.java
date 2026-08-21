package io.github.zensu357.camswap;

import android.graphics.Color;
import android.opengl.Matrix;

import org.json.JSONObject;

import java.io.File;

/**
 * Reads the manual GHOSTCAM layout file and composes a texture matrix for the
 * OpenGL renderer. This is deliberately a generic media transform: scale,
 * position, rotation, mirror and fit/fill only.
 */
public final class GhostCamRenderTransform {
    private static final String FILE_NAME = "ghostcam_transform.json";
    private static final long RELOAD_INTERVAL_MS = 400L;

    private static volatile long lastReadMs = 0L;
    private static volatile Settings cached = new Settings();

    private GhostCamRenderTransform() {}

    public static Settings get() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastReadMs >= RELOAD_INTERVAL_MS) {
            synchronized (GhostCamRenderTransform.class) {
                if (now - lastReadMs >= RELOAD_INTERVAL_MS) {
                    cached = readFile(cached);
                    lastReadMs = now;
                }
            }
        }
        return cached;
    }

    /**
     * Returns a new 4x4 texture matrix. The input is the SurfaceTexture matrix.
     * source/output dimensions are optional; pass <=0 to skip aspect correction.
     */
    public static float[] compose(
            float[] surfaceTextureMatrix,
            int sourceWidth,
            int sourceHeight,
            int outputWidth,
            int outputHeight) {
        Settings s = get();
        float[] base = new float[16];
        if (surfaceTextureMatrix != null && surfaceTextureMatrix.length >= 16) {
            System.arraycopy(surfaceTextureMatrix, 0, base, 0, 16);
        } else {
            Matrix.setIdentityM(base, 0);
        }

        float cropX = 1f;
        float cropY = 1f;
        if (sourceWidth > 0 && sourceHeight > 0 && outputWidth > 0 && outputHeight > 0
                && !"STRETCH".equals(s.fitMode)) {
            float srcAspect = (float) sourceWidth / (float) sourceHeight;
            float outAspect = (float) outputWidth / (float) outputHeight;
            if ("FILL".equals(s.fitMode)) {
                if (srcAspect > outAspect) {
                    cropX = outAspect / srcAspect;
                } else {
                    cropY = srcAspect / outAspect;
                }
            } else if ("FIT".equals(s.fitMode)) {
                // FIT needs geometry/viewport scaling to create bars. Keep the full
                // texture here; renderer may use referenceScale() for geometry.
                cropX = 1f;
                cropY = 1f;
            }
        }

        float userUvScale = 1f / Math.max(0.25f, Math.min(4f, s.scale));
        float sx = cropX * userUvScale * (s.mirrorX ? -1f : 1f);
        float sy = cropY * userUvScale;

        float[] adjust = new float[16];
        Matrix.setIdentityM(adjust, 0);
        Matrix.translateM(adjust, 0, 0.5f, 0.5f, 0f);
        Matrix.translateM(adjust, 0, s.offsetX * 0.5f, -s.offsetY * 0.5f, 0f);
        if (s.rotation != 0) {
            Matrix.rotateM(adjust, 0, -s.rotation, 0f, 0f, 1f);
        }
        Matrix.scaleM(adjust, 0, sx, sy, 1f);
        Matrix.translateM(adjust, 0, -0.5f, -0.5f, 0f);

        float[] result = new float[16];
        Matrix.multiplyMM(result, 0, base, 0, adjust, 0);
        return result;
    }

    /**
     * Geometry scale for FIT. FILL/STRETCH return 1,1 because crop is handled in UV.
     */
    public static float[] referenceScale(int sourceWidth, int sourceHeight, int outputWidth, int outputHeight) {
        Settings s = get();
        if (!"FIT".equals(s.fitMode) || sourceWidth <= 0 || sourceHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) {
            return new float[] {1f, 1f};
        }
        float srcAspect = (float) sourceWidth / (float) sourceHeight;
        float outAspect = (float) outputWidth / (float) outputHeight;
        if (srcAspect > outAspect) {
            return new float[] {1f, outAspect / srcAspect};
        }
        return new float[] {srcAspect / outAspect, 1f};
    }

    public static float[] backgroundRgba() {
        Settings s = get();
        try {
            int c = Color.parseColor(s.background);
            return new float[] {
                    Color.red(c) / 255f,
                    Color.green(c) / 255f,
                    Color.blue(c) / 255f,
                    Color.alpha(c) / 255f
            };
        } catch (Exception ignored) {
            return new float[] {0f, 0f, 0f, 1f};
        }
    }

    private static Settings readFile(Settings fallback) {
        try {
            File file = new File(ConfigManager.DEFAULT_CONFIG_DIR, FILE_NAME);
            if (!file.exists() || !file.isFile()) return fallback;
            JSONObject j = new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            Settings s = new Settings();
            s.scale = clamp((float) j.optDouble("scale", 1.0), 0.25f, 4f);
            s.offsetX = clamp((float) j.optDouble("offsetX", 0.0), -1f, 1f);
            s.offsetY = clamp((float) j.optDouble("offsetY", 0.0), -1f, 1f);
            s.rotation = normalizeRotation(j.optInt("rotation", 0));
            s.mirrorX = j.optBoolean("mirrorX", false);
            String fit = j.optString("fitMode", "FILL");
            s.fitMode = ("FIT".equals(fit) || "STRETCH".equals(fit)) ? fit : "FILL";
            s.background = j.optString("background", "#000000");
            s.referenceWidth = clampInt(j.optInt("referenceWidth", 720), 240, 4320);
            s.referenceHeight = clampInt(j.optInt("referenceHeight", 1600), 240, 4320);
            return s;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int normalizeRotation(int value) {
        int r = value % 360;
        if (r < 0) r += 360;
        // UI uses quarter turns; snap unexpected values to nearest quarter turn.
        return ((r + 45) / 90 * 90) % 360;
    }

    public static final class Settings {
        public float scale = 1f;
        public float offsetX = 0f;
        public float offsetY = 0f;
        public int rotation = 0;
        public boolean mirrorX = false;
        public String fitMode = "FILL";
        public String background = "#000000";
        public int referenceWidth = 720;
        public int referenceHeight = 1600;
    }
}
