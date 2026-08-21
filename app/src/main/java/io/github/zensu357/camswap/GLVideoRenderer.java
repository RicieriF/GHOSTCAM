package io.github.zensu357.camswap;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import io.github.zensu357.camswap.utils.LogUtil;
import io.github.zensu357.camswap.utils.VideoManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** GPU renderer used between the media source and the camera target Surface. */
public class GLVideoRenderer implements SurfaceTexture.OnFrameAvailableListener {
    private static final String KEY_ZOOM = "ghost_frame_zoom_pct";
    private static final String KEY_X = "ghost_frame_x_pct";
    private static final String KEY_Y = "ghost_frame_y_pct";
    private static final String KEY_MIRROR = "ghost_frame_mirror";

    private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface mEGLSurface = EGL14.EGL_NO_SURFACE;

    private int mProgram;
    private int mTextureId;
    private int maPositionHandle;
    private int maTextureHandle;
    private int muSTMatrixHandle;
    private int muRotMatrixHandle;

    private SurfaceTexture mInputSurfaceTexture;
    private Surface mInputSurface;
    private Surface mTargetSurface;

    private final float[] mSTMatrix = new float[16];
    private final float[] mRotMatrix = new float[16];

    private volatile int mRotationDegrees = 0;
    private volatile boolean mReleased = false;
    private boolean mInitialized = false;
    private volatile int mSurfaceWidth = 0;
    private volatile int mSurfaceHeight = 0;

    private HandlerThread mGLThread;
    private Handler mGLHandler;
    private final String mTag;
    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTexCoordBuffer;
    private ByteBuffer mCaptureBuffer;
    private int mCaptureBufferSize;

    public GLVideoRenderer(Surface targetSurface, String tag) {
        mTag = tag;
        mTargetSurface = targetSurface;
        Matrix.setIdentityM(mRotMatrix, 0);
        Matrix.setIdentityM(mSTMatrix, 0);

        mGLThread = new HandlerThread("GLRenderer-" + tag);
        mGLThread.start();
        mGLHandler = new Handler(mGLThread.getLooper());

        CountDownLatch latch = new CountDownLatch(1);
        mGLHandler.post(() -> {
            try {
                initEGL(targetSurface);
                initGL();
                mInitialized = true;
                LogUtil.log("【GHOSTCAM】【GL】" + mTag + " initialized");
            } catch (Exception e) {
                LogUtil.log("【GHOSTCAM】【GL】" + mTag + " init failed: " + e);
                mInitialized = false;
            }
            latch.countDown();
        });

        try {
            if (!latch.await(3000, TimeUnit.MILLISECONDS)) {
                LogUtil.log("【GHOSTCAM】【GL】" + mTag + " init timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isInitialized() {
        return mInitialized && !mReleased;
    }

    public Surface getInputSurface() {
        return mInputSurface;
    }

    public int getSurfaceWidth() {
        return mSurfaceWidth;
    }

    public int getSurfaceHeight() {
        return mSurfaceHeight;
    }

    public void setRotation(int degrees) {
        mRotationDegrees = ((degrees % 360) + 360) % 360;
    }

    public int getRotation() {
        return mRotationDegrees;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        if (mReleased || !mInitialized) return;
        mGLHandler.post(this::drawFrame);
    }

    private void drawFrame() {
        if (mReleased || !mInitialized) return;
        if (mTargetSurface != null && !mTargetSurface.isValid()) {
            mReleased = true;
            return;
        }
        try {
            renderToBackBuffer();
            if (!EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface)) {
                int err = EGL14.eglGetError();
                if (err == EGL14.EGL_BAD_SURFACE || err == EGL14.EGL_BAD_NATIVE_WINDOW) {
                    mReleased = true;
                }
            }
        } catch (Exception e) {
            LogUtil.log("【GHOSTCAM】【GL】" + mTag + " draw failed: " + e);
        }
    }

    private void renderToBackBuffer() {
        if (!EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) return;

        mInputSurfaceTexture.updateTexImage();
        mInputSurfaceTexture.getTransformMatrix(mSTMatrix);

        Matrix.setIdentityM(mRotMatrix, 0);
        if (mRotationDegrees != 0) {
            Matrix.rotateM(mRotMatrix, 0, -mRotationDegrees, 0f, 0f, 1f);
        }

        // GHOSTCAM Studio transform. Values are persisted in the shared module config,
        // so every new camera Surface gets the same framing without re-encoding video.
        try {
            ConfigManager cfg = VideoManager.getConfig();
            int zoomPct = clamp(cfg.getInt(KEY_ZOOM, 100), 50, 200);
            int xPct = clamp(cfg.getInt(KEY_X, 0), -100, 100);
            int yPct = clamp(cfg.getInt(KEY_Y, 0), -100, 100);
            boolean mirror = cfg.getBoolean(KEY_MIRROR, false);

            float scale = zoomPct / 100f;
            float sx = mirror ? -scale : scale;
            float tx = xPct / 100f;
            float ty = -(yPct / 100f); // positive Studio Y means move down on screen

            Matrix.translateM(mRotMatrix, 0, tx, ty, 0f);
            Matrix.scaleM(mRotMatrix, 0, sx, scale, 1f);
        } catch (Throwable ignored) {
            // Keep identity framing if configuration is temporarily unavailable.
        }

        int[] width = new int[1];
        int[] height = new int[1];
        EGL14.eglQuerySurface(mEGLDisplay, mEGLSurface, EGL14.EGL_WIDTH, width, 0);
        EGL14.eglQuerySurface(mEGLDisplay, mEGLSurface, EGL14.EGL_HEIGHT, height, 0);
        if (width[0] > 0) mSurfaceWidth = width[0];
        if (height[0] > 0) mSurfaceHeight = height[0];
        if (width[0] > 0 && height[0] > 0) GLES20.glViewport(0, 0, width[0], height[0]);

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(mProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureId);
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);
        GLES20.glUniformMatrix4fv(muRotMatrixHandle, 1, false, mRotMatrix, 0);

        mVertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(maPositionHandle);
        GLES20.glVertexAttribPointer(maPositionHandle, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer);
        mTexCoordBuffer.position(0);
        GLES20.glEnableVertexAttribArray(maTextureHandle);
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, 0, mTexCoordBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    public Bitmap captureFrame(int width, int height) {
        return captureFrameWithRotation(width, height, -1);
    }

    public Bitmap captureFrameWithRotation(int width, int height, int rotationDegrees) {
        if (!isInitialized() || mReleased) return null;
        final Bitmap[] result = { null };
        CountDownLatch latch = new CountDownLatch(1);
        mGLHandler.post(() -> {
            int savedRotation = mRotationDegrees;
            try {
                if (rotationDegrees >= 0) {
                    mRotationDegrees = ((rotationDegrees % 360) + 360) % 360;
                }
                renderToBackBuffer();
                mRotationDegrees = savedRotation;

                int bufSize = width * height * 4;
                if (mCaptureBuffer == null || mCaptureBufferSize != bufSize) {
                    mCaptureBuffer = ByteBuffer.allocateDirect(bufSize).order(ByteOrder.nativeOrder());
                    mCaptureBufferSize = bufSize;
                }
                mCaptureBuffer.clear();
                GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, mCaptureBuffer);
                mCaptureBuffer.rewind();

                Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bmp.copyPixelsFromBuffer(mCaptureBuffer);
                android.graphics.Matrix matrix = new android.graphics.Matrix();
                matrix.postScale(1, -1);
                result[0] = Bitmap.createBitmap(bmp, 0, 0, width, height, matrix, true);
                bmp.recycle();
            } catch (Exception e) {
                mRotationDegrees = savedRotation;
                LogUtil.log("【GHOSTCAM】【GL】capture failed: " + e);
            }
            latch.countDown();
        });
        try {
            latch.await(2000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result[0];
    }

    private void initEGL(Surface targetSurface) {
        mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("eglGetDisplay failed");

        int[] version = new int[2];
        if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) throw new RuntimeException("eglInitialize failed");

        int[] attribList = {
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            throw new RuntimeException("No matching EGL config");
        }

        int[] contextAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        mEGLContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (mEGLContext == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("eglCreateContext failed");

        mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], targetSurface, new int[] { EGL14.EGL_NONE }, 0);
        if (mEGLSurface == EGL14.EGL_NO_SURFACE) throw new RuntimeException("eglCreateWindowSurface failed: " + EGL14.eglGetError());
        if (!EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) throw new RuntimeException("eglMakeCurrent failed");

        int[] width = new int[1];
        int[] height = new int[1];
        EGL14.eglQuerySurface(mEGLDisplay, mEGLSurface, EGL14.EGL_WIDTH, width, 0);
        EGL14.eglQuerySurface(mEGLDisplay, mEGLSurface, EGL14.EGL_HEIGHT, height, 0);
        mSurfaceWidth = width[0];
        mSurfaceHeight = height[0];
        if (width[0] <= 1 && height[0] <= 1) throw new RuntimeException("EGL Surface too small");
    }

    private void initGL() {
        int vertexShader = GLHelper.loadShader(GLES20.GL_VERTEX_SHADER, GLHelper.VERTEX_SHADER);
        int fragmentShader = GLHelper.loadShader(GLES20.GL_FRAGMENT_SHADER, GLHelper.FRAGMENT_SHADER);
        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, vertexShader);
        GLES20.glAttachShader(mProgram, fragmentShader);
        GLES20.glLinkProgram(mProgram);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(mProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            String error = GLES20.glGetProgramInfoLog(mProgram);
            GLES20.glDeleteProgram(mProgram);
            throw new RuntimeException("Program link failed: " + error);
        }

        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
        muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
        muRotMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uRotMatrix");

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        mTextureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        mInputSurfaceTexture = new SurfaceTexture(mTextureId);
        mInputSurfaceTexture.setOnFrameAvailableListener(this);
        mInputSurface = new Surface(mInputSurfaceTexture);
        mVertexBuffer = GLHelper.createFloatBuffer(GLHelper.VERTICES);
        mTexCoordBuffer = GLHelper.createFloatBuffer(GLHelper.TEX_COORDS);
    }

    public void release() {
        if (mReleased) return;
        mReleased = true;
        CountDownLatch releaseLatch = new CountDownLatch(1);
        if (mGLHandler != null) {
            mGLHandler.post(() -> {
                try { releaseInternal(); }
                finally { releaseLatch.countDown(); }
            });
        } else {
            releaseLatch.countDown();
        }
        try {
            releaseLatch.await(1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (mGLThread != null) {
            mGLThread.quitSafely();
            try { mGLThread.join(1000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        mGLHandler = null;
        mGLThread = null;
    }

    private void releaseInternal() {
        if (mInputSurface != null) { mInputSurface.release(); mInputSurface = null; }
        if (mInputSurfaceTexture != null) { mInputSurfaceTexture.release(); mInputSurfaceTexture = null; }
        if (mProgram != 0) { GLES20.glDeleteProgram(mProgram); mProgram = 0; }
        if (mTextureId != 0) { GLES20.glDeleteTextures(1, new int[] { mTextureId }, 0); mTextureId = 0; }
        if (mEGLSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
            mEGLSurface = EGL14.EGL_NO_SURFACE;
        }
        if (mEGLContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
            mEGLContext = EGL14.EGL_NO_CONTEXT;
        }
        if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglTerminate(mEGLDisplay);
            mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static GLVideoRenderer createSafely(Surface targetSurface, String tag) {
        if (targetSurface == null || !targetSurface.isValid()) return null;
        try {
            GLVideoRenderer renderer = new GLVideoRenderer(targetSurface, tag);
            if (renderer.isInitialized()) return renderer;
            renderer.release();
            return null;
        } catch (Exception e) {
            LogUtil.log("【GHOSTCAM】【GL】" + tag + " create failed: " + e);
            return null;
        }
    }

    public static void releaseSafely(GLVideoRenderer renderer) {
        if (renderer != null) {
            try { renderer.release(); }
            catch (Exception e) { LogUtil.log("【GHOSTCAM】【GL】release failed: " + e); }
        }
    }
}
