package com.bu.cc.tv;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class SharpVideoView extends GLSurfaceView {
    private static final String TAG = "SharpVideoView";

    private final VideoRenderer renderer;
    private SurfaceCallback callback;

    public SharpVideoView(Context context) {
        this(context, null);
    }

    public SharpVideoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        renderer = new VideoRenderer(this);
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    public void setSurfaceCallback(SurfaceCallback callback) {
        this.callback = callback;
    }

    public void setVideoSize(final int width, final int height, final int sarNum, final int sarDen) {
        queueEvent(new Runnable() {
            @Override
            public void run() {
                renderer.setVideoSize(width, height, sarNum, sarDen);
            }
        });
        requestRender();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Surface surface = renderer.getVideoSurface();
        if (callback != null && surface != null) {
            callback.onVideoSurfaceDestroyed(surface);
        }
        queueEvent(new Runnable() {
            @Override
            public void run() {
                renderer.releaseInputSurface();
            }
        });
        super.surfaceDestroyed(holder);
    }

    private void notifySurfaceCreated(final Surface surface) {
        post(new Runnable() {
            @Override
            public void run() {
                if (callback != null) {
                    callback.onVideoSurfaceCreated(surface);
                }
            }
        });
    }

    public interface SurfaceCallback {
        void onVideoSurfaceCreated(Surface surface);

        void onVideoSurfaceDestroyed(Surface surface);
    }

    private static final class VideoRenderer implements Renderer, SurfaceTexture.OnFrameAvailableListener {
        private static final float BASE_SHARPEN_STRENGTH = 0.10f;
        private static final float UPSCALE_SHARPEN_STRENGTH = 0.18f;

        private static final String VERTEX_SHADER =
                "attribute vec4 aPosition;\n"
                        + "attribute vec4 aTexCoord;\n"
                        + "uniform mat4 uTexMatrix;\n"
                        + "varying vec2 vTexCoord;\n"
                        + "void main() {\n"
                        + "  gl_Position = aPosition;\n"
                        + "  vTexCoord = (uTexMatrix * aTexCoord).xy;\n"
                        + "}\n";

        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n"
                        + "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
                        + "precision highp float;\n"
                        + "#else\n"
                        + "precision mediump float;\n"
                        + "#endif\n"
                        + "uniform samplerExternalOES uTexture;\n"
                        + "uniform vec2 uTexelSize;\n"
                        + "uniform float uSharpness;\n"
                        + "varying vec2 vTexCoord;\n"
                        + "void main() {\n"
                        + "  vec4 c = texture2D(uTexture, vTexCoord);\n"
                        + "  vec4 l = texture2D(uTexture, vTexCoord + vec2(-uTexelSize.x, 0.0));\n"
                        + "  vec4 r = texture2D(uTexture, vTexCoord + vec2( uTexelSize.x, 0.0));\n"
                        + "  vec4 t = texture2D(uTexture, vTexCoord + vec2(0.0, -uTexelSize.y));\n"
                        + "  vec4 b = texture2D(uTexture, vTexCoord + vec2(0.0,  uTexelSize.y));\n"
                        + "  vec3 rgb = c.rgb * (1.0 + 4.0 * uSharpness) - (l.rgb + r.rgb + t.rgb + b.rgb) * uSharpness;\n"
                        + "  gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);\n"
                        + "}\n";

        private final SharpVideoView view;
        private final float[] texMatrix = new float[16];
        private final FloatBuffer vertexBuffer;

        private int program;
        private int textureId;
        private int positionHandle;
        private int texCoordHandle;
        private int texMatrixHandle;
        private int texelSizeHandle;
        private int sharpnessHandle;
        private int viewWidth;
        private int viewHeight;
        private int videoWidth;
        private int videoHeight;
        private int sarNum = 1;
        private int sarDen = 1;
        private float sharpness = BASE_SHARPEN_STRENGTH;
        private boolean frameAvailable;
        private SurfaceTexture surfaceTexture;
        private volatile Surface videoSurface;

        VideoRenderer(SharpVideoView view) {
            this.view = view;
            vertexBuffer = ByteBuffer.allocateDirect(16 * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord");
            texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix");
            texelSizeHandle = GLES20.glGetUniformLocation(program, "uTexelSize");
            sharpnessHandle = GLES20.glGetUniformLocation(program, "uSharpness");
            createInputSurface();
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            viewWidth = width;
            viewHeight = height;
            GLES20.glViewport(0, 0, width, height);
            updateVertices();
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (surfaceTexture == null || program == 0) {
                return;
            }
            synchronized (this) {
                if (frameAvailable) {
                    surfaceTexture.updateTexImage();
                    frameAvailable = false;
                }
            }
            surfaceTexture.getTransformMatrix(texMatrix);

            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0);
            GLES20.glUniform2f(texelSizeHandle,
                    videoWidth > 0 ? 1f / videoWidth : 1f / 1280f,
                    videoHeight > 0 ? 1f / videoHeight : 1f / 720f);
            GLES20.glUniform1f(sharpnessHandle, sharpness);

            vertexBuffer.position(0);
            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 4 * 4,
                    vertexBuffer);
            vertexBuffer.position(2);
            GLES20.glEnableVertexAttribArray(texCoordHandle);
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 4 * 4,
                    vertexBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(texCoordHandle);
        }

        @Override
        public synchronized void onFrameAvailable(SurfaceTexture surfaceTexture) {
            frameAvailable = true;
            view.requestRender();
        }

        Surface getVideoSurface() {
            return videoSurface;
        }

        void setVideoSize(int width, int height, int sarNum, int sarDen) {
            videoWidth = Math.max(0, width);
            videoHeight = Math.max(0, height);
            this.sarNum = sarNum > 0 ? sarNum : 1;
            this.sarDen = sarDen > 0 ? sarDen : 1;
            updateVertices();
            Log.i(TAG, "GL video layout source=" + videoWidth + "x" + videoHeight
                    + " sar=" + this.sarNum + "/" + this.sarDen
                    + " view=" + viewWidth + "x" + viewHeight);
        }

        void releaseInputSurface() {
            if (videoSurface != null) {
                videoSurface.release();
                videoSurface = null;
            }
            if (surfaceTexture != null) {
                surfaceTexture.release();
                surfaceTexture = null;
            }
        }

        private void createInputSurface() {
            releaseInputSurface();
            textureId = createExternalTexture();
            surfaceTexture = new SurfaceTexture(textureId);
            surfaceTexture.setOnFrameAvailableListener(this);
            videoSurface = new Surface(surfaceTexture);
            view.notifySurfaceCreated(videoSurface);
            Log.i(TAG, "Created GL video input surface");
        }

        private void updateVertices() {
            float x = 1f;
            float y = 1f;
            if (viewWidth > 0 && viewHeight > 0 && videoWidth > 0 && videoHeight > 0) {
                float videoAspect = (float) videoWidth / (float) videoHeight
                        * (float) sarNum / (float) sarDen;
                float viewAspect = (float) viewWidth / (float) viewHeight;
                if (viewAspect > videoAspect) {
                    x = videoAspect / viewAspect;
                } else {
                    y = viewAspect / videoAspect;
                }
                float outputWidth = viewWidth * x;
                float outputHeight = viewHeight * y;
                sharpness = outputWidth > videoWidth * 1.05f || outputHeight > videoHeight * 1.05f
                        ? UPSCALE_SHARPEN_STRENGTH : BASE_SHARPEN_STRENGTH;
            } else {
                sharpness = BASE_SHARPEN_STRENGTH;
            }

            float[] vertices = new float[] {
                    -x, -y, 0f, 0f,
                     x, -y, 1f, 0f,
                    -x,  y, 0f, 1f,
                     x,  y, 1f, 1f
            };
            vertexBuffer.position(0);
            vertexBuffer.put(vertices);
            vertexBuffer.position(0);
        }

        private static int createExternalTexture() {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            int texture = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            return texture;
        }

        private static int createProgram(String vertexShader, String fragmentShader) {
            int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader);
            int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader);
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, fragment);
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] == 0) {
                String error = GLES20.glGetProgramInfoLog(program);
                GLES20.glDeleteProgram(program);
                throw new IllegalStateException("Could not link GL program: " + error);
            }
            return program;
        }

        private static int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                String error = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                throw new IllegalStateException("Could not compile GL shader: " + error);
            }
            return shader;
        }
    }
}
