package xiao.bu.tv;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Composites decoded video and a transparent View snapshot entirely on the GPU. */
final class CastGlCompositor implements Closeable {
    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n"
                    + "attribute vec4 aTexCoord;\n"
                    + "uniform mat4 uTexMatrix;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main(){gl_Position=aPosition;"
                    + "vTexCoord=(uTexMatrix*aTexCoord).xy;}\n";
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "uniform samplerExternalOES uTexture;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main(){gl_FragColor=texture2D(uTexture,vTexCoord);}\n";

    private final RenderThread thread;

    CastGlCompositor(Surface encoderSurface, int width, int height, int fps,
            boolean includeVideoLayer)
            throws IOException {
        thread = new RenderThread(encoderSurface, width, height, fps,
                includeVideoLayer);
        thread.start();
        try {
            if (!thread.ready.await(5L, TimeUnit.SECONDS)) {
                thread.shutdown();
                throw new IOException("GPU 投送合成器启动超时");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            thread.shutdown();
            throw new IOException("GPU 投送合成器启动被中断", error);
        }
        if (thread.failure != null) {
            thread.shutdown();
            throw new IOException("GPU 投送合成器启动失败", thread.failure);
        }
    }

    Surface videoSurface() {
        return thread.videoSurface;
    }

    Surface uiSurface() {
        return thread.uiSurface;
    }

    /** Keep the UI thread out of Surface buffer backpressure. One fresh View
     * frame is enough; the compositor reuses it until the next slot is free. */
    boolean tryAcquireUiFrame() {
        return thread.tryAcquireUiFrame();
    }

    void releaseUiFrame() {
        thread.releaseUiFrame();
    }

    Throwable failure() {
        return thread.failure;
    }

    void setVideoSize(int width, int height, int sarNum, int sarDen) {
        thread.videoWidth = Math.max(0, width);
        thread.videoHeight = Math.max(0, height);
        thread.sarNum = Math.max(1, sarNum);
        thread.sarDen = Math.max(1, sarDen);
    }

    @Override
    public void close() {
        thread.shutdown();
    }

    private static final class RenderThread extends Thread {
        final CountDownLatch ready = new CountDownLatch(1);
        final Surface outputSurface;
        final int width;
        final int height;
        final boolean includeVideoLayer;
        final long frameIntervalNs;
        final Object frameLock = new Object();
        final FloatBuffer videoVertices = allocateVertices();
        final FloatBuffer uiVertices = allocateVertices();
        final float[] videoMatrix = new float[16];
        final float[] uiMatrix = new float[16];
        volatile boolean running = true;
        volatile boolean videoFrameAvailable;
        volatile boolean uiFrameAvailable;
        boolean uiFrameInFlight;
        volatile int videoWidth;
        volatile int videoHeight;
        volatile int sarNum = 1;
        volatile int sarDen = 1;
        volatile Surface videoSurface;
        volatile Surface uiSurface;
        volatile Throwable failure;

        EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
        SurfaceTexture videoTexture;
        SurfaceTexture uiTexture;
        HandlerThread frameEvents;
        int videoTextureId;
        int uiTextureId;
        int program;
        int positionHandle;
        int texCoordHandle;
        int matrixHandle;

        RenderThread(Surface outputSurface, int width, int height, int fps,
                boolean includeVideoLayer) {
            super("cast-gl-compositor");
            this.outputSurface = outputSurface;
            this.width = width;
            this.height = height;
            this.includeVideoLayer = includeVideoLayer;
            frameIntervalNs = 1000000000L / Math.max(1, fps);
            android.opengl.Matrix.setIdentityM(videoMatrix, 0);
            android.opengl.Matrix.setIdentityM(uiMatrix, 0);
        }

        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
            try {
                initialize();
                ready.countDown();
                long nextFrameAt = 0;
                while (running) {
                    synchronized (frameLock) {
                        while (running) {
                            long waitNs = nextFrameAt - System.nanoTime();
                            if ((uiFrameAvailable || videoFrameAvailable) && waitNs <= 0) break;
                            try {
                                // No blind duplicate-frame encoding. Recheck both the
                                // frame predicate and deadline after every wakeup.
                                if (waitNs <= 0) frameLock.wait();
                                else frameLock.wait(waitNs / 1000000L, (int)(waitNs % 1000000L));
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt(); running = false;
                            }
                        }
                    }
                    if (!running) {
                        break;
                    }
                    long began = System.nanoTime();
                    draw();
                    // For webpage-only casting the UI producer already controls
                    // the rate. A second independent limiter adds up to one frame.
                    nextFrameAt = includeVideoLayer
                            ? Math.max(began + frameIntervalNs, System.nanoTime()) : 0;
                }
            } catch (Throwable error) {
                failure = error;
                ready.countDown();
            } finally {
                release();
            }
        }

        void initialize() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] versions = new int[2];
            check(EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1),
                    "eglInitialize");
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            int[] configAttributes = new int[] {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE
            };
            check(EGL14.eglChooseConfig(eglDisplay, configAttributes, 0,
                    configs, 0, 1, count, 0) && count[0] > 0, "eglChooseConfig");
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0],
                    EGL14.EGL_NO_CONTEXT, new int[] {
                            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE }, 0);
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0],
                    outputSurface, new int[] { EGL14.EGL_NONE }, 0);
            check(eglContext != EGL14.EGL_NO_CONTEXT
                    && eglSurface != EGL14.EGL_NO_SURFACE, "eglCreateWindowSurface");
            check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext),
                    "eglMakeCurrent");
            createProgram();
            // SurfaceTexture created on a thread without a Looper otherwise
            // dispatches to the main Looper, behind both WebViews' UI work.
            frameEvents = new HandlerThread("cast-frame-events");
            frameEvents.start();
            Handler frameHandler = new Handler(frameEvents.getLooper());
            if (includeVideoLayer) {
                videoTextureId = createExternalTexture();
                videoTexture = new SurfaceTexture(videoTextureId);
                videoTexture.setDefaultBufferSize(width, height);
                videoTexture.setOnFrameAvailableListener(
                        new SurfaceTexture.OnFrameAvailableListener() {
                            @Override
                            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                                videoFrameAvailable = true;
                                signal();
                            }
                        }, frameHandler);
                videoSurface = new Surface(videoTexture);
            }
            uiTextureId = createExternalTexture();
            uiTexture = new SurfaceTexture(uiTextureId);
            uiTexture.setDefaultBufferSize(width, height);
            uiTexture.setOnFrameAvailableListener(
                    new SurfaceTexture.OnFrameAvailableListener() {
                        @Override
                        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                            uiFrameAvailable = true;
                            signal();
                        }
                    }, frameHandler);
            uiSurface = new Surface(uiTexture);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
        }

        boolean tryAcquireUiFrame() {
            synchronized (frameLock) {
                if (!running || uiFrameInFlight) return false;
                uiFrameInFlight = true;
                return true;
            }
        }

        void releaseUiFrame() {
            synchronized (frameLock) {
                uiFrameInFlight = false;
                frameLock.notifyAll();
            }
        }

        void draw() {
            if (includeVideoLayer && videoFrameAvailable) {
                videoFrameAvailable = false;
                videoTexture.updateTexImage();
                videoTexture.getTransformMatrix(videoMatrix);
            }
            if (uiFrameAvailable) {
                uiFrameAvailable = false;
                uiTexture.updateTexImage();
                uiTexture.getTransformMatrix(uiMatrix);
                releaseUiFrame();
            }
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (includeVideoLayer) {
                setVideoVertices();
                drawTexture(videoTextureId, videoMatrix, videoVertices, false);
            }
            drawTexture(uiTextureId, uiMatrix, uiVertices, true);
            long ptsNs = System.nanoTime();
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNs);
            check(EGL14.eglSwapBuffers(eglDisplay, eglSurface), "eglSwapBuffers");
            if (BuildConfig.DEBUG && BuildConfig.CAST_LATENCY_TRACE)
                android.util.Log.i("NtvCastLatency", "GL pts=" + ptsNs / 1000L
                        + " ui=" + uiTexture.getTimestamp() / 1000L
                        + " end=" + System.nanoTime() / 1000L);
        }

        void drawTexture(int textureId, float[] matrix, FloatBuffer vertices,
                boolean blend) {
            if (blend) {
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            } else {
                GLES20.glDisable(GLES20.GL_BLEND);
            }
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniformMatrix4fv(matrixHandle, 1, false, matrix, 0);
            vertices.position(0);
            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT,
                    false, 4 * 4, vertices);
            vertices.position(2);
            GLES20.glEnableVertexAttribArray(texCoordHandle);
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT,
                    false, 4 * 4, vertices);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(texCoordHandle);
        }

        void setVideoVertices() {
            float x = 1f;
            float y = 1f;
            if (videoWidth > 0 && videoHeight > 0) {
                float videoAspect = (float) videoWidth * sarNum
                        / ((float) videoHeight * sarDen);
                float outputAspect = (float) width / height;
                if (outputAspect > videoAspect) x = videoAspect / outputAspect;
                else y = outputAspect / videoAspect;
            }
            putVertices(videoVertices, x, y);
        }

        void shutdown() {
            running = false;
            signal();
            try {
                join(1500L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }

        void signal() {
            synchronized (frameLock) {
                frameLock.notifyAll();
            }
        }

        void release() {
            if (videoTexture != null) videoTexture.setOnFrameAvailableListener(null);
            if (uiTexture != null) uiTexture.setOnFrameAvailableListener(null);
            if (frameEvents != null) frameEvents.quitSafely();
            if (videoSurface != null) videoSurface.release();
            if (uiSurface != null) uiSurface.release();
            if (videoTexture != null) videoTexture.release();
            if (uiTexture != null) uiTexture.release();
            videoSurface = null;
            uiSurface = null;
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                if (videoTextureId != 0) {
                    GLES20.glDeleteTextures(1, new int[] { videoTextureId }, 0);
                    videoTextureId = 0;
                }
                if (uiTextureId != 0) {
                    GLES20.glDeleteTextures(1, new int[] { uiTextureId }, 0);
                    uiTextureId = 0;
                }
                if (program != 0) {
                    GLES20.glDeleteProgram(program);
                    program = 0;
                }
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                }
                EGL14.eglTerminate(eglDisplay);
            }
        }

        void createProgram() {
            int vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, fragment);
            GLES20.glLinkProgram(program);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            check(linked[0] != 0, "glLinkProgram");
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord");
            matrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix");
        }

        static int compile(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) throw new IllegalStateException(GLES20.glGetShaderInfoLog(shader));
            return shader;
        }

        static int createExternalTexture() {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            int value = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, value);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            return value;
        }

        static FloatBuffer allocateVertices() {
            FloatBuffer result = ByteBuffer.allocateDirect(16 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            putVertices(result, 1f, 1f);
            return result;
        }

        static void putVertices(FloatBuffer target, float x, float y) {
            target.position(0);
            target.put(new float[] {
                    -x, -y, 0f, 0f, x, -y, 1f, 0f,
                    -x, y, 0f, 1f, x, y, 1f, 1f
            }).position(0);
        }

        static void check(boolean success, String operation) {
            if (!success) throw new IllegalStateException(operation + " failed");
        }

    }
}
