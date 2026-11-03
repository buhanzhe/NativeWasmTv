package xiao.bu.tv;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaFormat;
import android.media.MediaCodec;
import android.media.MediaCrypto;
import java.util.LinkedHashMap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.Display;
import android.view.View;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.lang.ref.WeakReference;

/** API 17+ GPU tone mapping/BT.709 bridge. Hardware decoding and SurfaceView remain. */
public final class NativeHdrOutput implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "nTvHdr";
    private static volatile Context context;
    private static volatile Display targetDisplay;
    private static volatile WeakReference<DirectVideoView> outputView = new WeakReference<>(null);
    private static volatile NativeHdrOutput active;
    private static volatile String outputRoute = "";
    private static final LinkedHashMap<String,Boolean> rejectedMapping = new LinkedHashMap<>();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final HandlerThread thread = new HandlerThread("nTv HDR output", Process.THREAD_PRIORITY_DISPLAY);
    private Handler handler;
    private final Surface target;
    private final int width, height, transfer;
    private final int configuredStandard, configuredRange, sourceDepth;
    private boolean rawYuv;
    private int yuvMatrix, yuvOffset;
    private final boolean forceBt709;
    private final float peak;
    private volatile boolean closed;
    private volatile Throwable failure;
    private Surface input;
    private SurfaceTexture texture;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private int program, textureId, position, texcoord, transform;
    private int referenceProgram, lutProgram, sdrProgram, sdrTransfer;
    private HdrSdrCurve sdrCurve;
    private boolean sdrCurveFailed, sdrProgramUsesCurve;
    private volatile boolean sdrCurveActive, sdrCurveFloating;
    private String glVersion = "", glRenderer = "";
    private volatile boolean lutProgramVolume;
    private String toneReference;
    private HdrToneMapLut toneLut;
    private boolean lutFailed;
    private volatile boolean lutActive;
    private volatile int renderWidth, renderHeight;
    private int swapInterval = 1;
    private final float[] matrix = new float[16];
    private final int[] windowSize = new int[1];
    private boolean drawStateDirty=true, viewportDirty=true;
    private long nextWindowCheck;
    private final FloatBuffer vertices = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private long lastTextureTimestamp, rateStart, rateFrames;
    private volatile long renderedFrames, lastRenderedAt;
    private volatile float renderedRate;
    private long timingLatch, timingSubmit, timingSwap, timingCount, timingStart;
    private volatile double latchMs, submitMs, swapMs;
    private volatile String decoderColor = "";
    private volatile int[] decoderGeometry = new int[0];

    static void initialize(Context value, View output) {
        context = value.getApplicationContext();
        // Bind the display that owns this SurfaceView, including external displays.
        // Called on the UI thread; native configure can query the saved Display later.
        targetDisplay = output == null ? null : output.getDisplay();
        outputView = new WeakReference<>(output instanceof DirectVideoView ? (DirectVideoView) output : null);
    }

    private static int[] supportedHdrTypes() {
        if (Build.VERSION.SDK_INT < 24) return null;
        Display display = targetDisplay;
        if (display == null) return null;
        try {
            if (!display.isValid()) return null;
            return DisplayApi24.supportedHdrTypes(display);
        } catch (RuntimeException error) {
            Log.w(TAG, "HDR display capability query failed; use SDR mapping", error);
            return null;
        }
    }

    private static final class DisplayApi24 {
        static int[] supportedHdrTypes(Display display) {
            return display.getHdrCapabilities().getSupportedHdrTypes();
        }
    }

    /** Called by IJK before configure; null means native HDR/normal SDR output. */
    public static synchronized NativeHdrOutput create(MediaFormat format, Surface surface) {
        NativeHdrOutput previous = active;
        if (previous != null && previous.target == surface) {
            previous.release();
            try {
                if (!previous.stopped.await(1500,TimeUnit.MILLISECONDS))
                    throw new IllegalStateException("Previous HDR output has not released the Surface");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); throw new IllegalStateException(error);
            }
        }
        int transfer = integer(format, "color-transfer", 0);
        int mode = integer(format, "ntv-hdr-mode", HdrMode.nativeValue(HdrMode.DEFAULT));
        if(mode==3||mode==2||integer(format,"ntv-force-gpu",0)!=0)format.setInteger("color-transfer-request",0);
        int sourceTransfer = integer(format,"ntv-source-transfer",transfer);
        int sourceStandard = integer(format,"ntv-source-standard",1);
        boolean forceBt709 = mode == 2 && (sourceTransfer == 6 || sourceTransfer == 7
                || sourceStandard != 0 && sourceStandard != 1);
        boolean wideGamut = !forceBt709 && sourceStandard == 6 && transfer != 6 && transfer != 7;
        outputRoute = "SDR直出";
        if ((!forceBt709 && !wideGamut && transfer != 6 && transfer != 7) || surface == null) return null;
        int[] hdrTypes = supportedHdrTypes();
        if (HdrDisplayPolicy.passthrough(mode, transfer, hdrTypes, Build.VERSION.SDK_INT)) {
            outputRoute = "HDR直出";
            Log.i(TAG, "HDR hardware passthrough transfer=" + transfer);
            return null;
        }
        if (mode == 1 && !wideGamut) Log.i(TAG, "HDR display fallback: transfer=" + transfer
                + " hdrTypes=" + java.util.Arrays.toString(hdrTypes)
                + " sdk=" + Build.VERSION.SDK_INT + " -> SDR mapping");
        Context ctx = context;
        if (ctx == null) throw new IllegalStateException("HDR output context is unavailable");
        // Do not silently tag PQ/HLG as SDR. New codecs may perform real tone mapping.
        if (!forceBt709 && !wideGamut
                && integer(format,"ntv-force-gpu",0)==0
                && HdrDisplayPolicy.decoderToneMapping(mode, Build.VERSION.SDK_INT)) {
            format.setInteger("color-transfer-request", 3);
            outputRoute = "解码器→SDR";
            Log.i(TAG, "Request decoder HDR -> SDR transfer=" + transfer);
            return null;
        }
        float peak = peak(format,1000f);
        // Missing transfer is not proof of HDR. Convert wide-gamut SDR using the
        // SDR display curve, without changing the source metadata in the HUD.
        int renderTransfer = forceBt709 ? 0 : wideGamut ? (transfer == 1 ? 1 : 3) : transfer;
        NativeHdrOutput bridge = new NativeHdrOutput(surface, integer(format, "width", 0),
                integer(format, "height", 0), renderTransfer, peak, forceBt709,
                integer(format, "color-standard", sourceStandard),integer(format,"color-range",2),
                integer(format,"ntv-source-depth","video/hevc".equals(format.getString("mime"))
                        && integer(format,"profile",0)==2?10:8));
        if (wideGamut) Log.i(TAG, "BT.2020 -> BT.709 gamut conversion; source transfer=" + sourceTransfer);
        Log.i(TAG, "GPU HDR output mode=" + mode + " transfer=" + transfer
                + " display=" + (targetDisplay == null ? "unknown" : targetDisplay.getDisplayId()));
        active = bridge;
        outputRoute = forceBt709 ? "GPU BT.709" : "GPU→SDR";
        return bridge;
    }

    /** Configure once, or retry the same hardware codec with GPU mapping if its SDR request is rejected. */
    public static NativeHdrOutput configure(MediaCodec codec,MediaFormat format,Surface surface,MediaCrypto crypto,int flags) {
        String key=null;
        if(Build.VERSION.SDK_INT>=31){
            key=codec.getName()+":"+format.getString("mime")+":"+integer(format,"color-standard",0)+":"+integer(format,"color-range",0)
                    +":"+integer(format,"ntv-source-transfer",integer(format,"color-transfer",0))
                    +":"+integer(format,"profile",0)+":"+integer(format,"width",0)+"x"+integer(format,"height",0);
            synchronized(rejectedMapping){if(rejectedMapping.containsKey(key))format.setInteger("ntv-force-gpu",1);}
        }
        NativeHdrOutput bridge=create(format,surface);
        boolean requested=integer(format,"color-transfer-request",0)==3;
        try{
            codec.configure(format,bridge==null?surface:bridge.getSurface(),crypto,flags);
            verifyToneMapping(codec,format);
            return bridge;
        }catch(RuntimeException original){
            if(bridge!=null)bridge.release();
            if(!requested||bridge!=null||Build.VERSION.SDK_INT<31)throw original;
            // Do not turn a missing colour-conversion capability into a software decoder fallback.
            synchronized(rejectedMapping){
                if(rejectedMapping.size()>=32)rejectedMapping.remove(rejectedMapping.keySet().iterator().next());
                rejectedMapping.put(key,Boolean.TRUE);
            }
            Log.w(TAG,"Codec SDR mapping rejected; retry hardware with GPU output",original);
            format.setInteger("color-transfer-request",0);format.setInteger("ntv-force-gpu",1);
            try{
                codec.reset();
                bridge=create(format,surface);
                if(bridge==null)throw new IllegalStateException("HDR GPU fallback unavailable");
                codec.configure(format,bridge.getSurface(),crypto,flags);
                return bridge;
            }catch(RuntimeException fallback){if(bridge!=null)bridge.release();fallback.addSuppressed(original);throw fallback;}
        }
    }

    private static float peak(MediaFormat format, float fallback) {
        float peak = fallback;
        try {
            ByteBuffer info = format.getByteBuffer("hdr-static-info");
            if (info != null && info.remaining() >= 25) {
                ByteBuffer copy = info.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                int start = copy.position();
                int mastering = copy.getShort(start + 17) & 0xffff;
                int content = copy.getShort(start + 21) & 0xffff;
                if (content > 0) peak = content;
                else if (mastering > 0) peak = mastering;
            }
        } catch (RuntimeException ignored) { }
        return Math.max(100f, Math.min(10000f, peak));
    }

    private static int integer(MediaFormat format, String key, int fallback) {
        try { return format.containsKey(key) ? format.getInteger(key) : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private NativeHdrOutput(Surface surface, int w, int h, int trc, float max, boolean bt709, int standard, int range, int depth) {
        target = surface; width = w; height = h; transfer = trc; peak = max;
        configuredStandard = standard;configuredRange=range;sourceDepth=depth;
        forceBt709 = bt709;
        vertices.put(new float[]{-1,-1, 0,0, 1,-1, 1,0, -1,1, 0,1, 1,1, 1,1}).position(0);
        thread.start(); handler = new Handler(thread.getLooper());
        CountDownLatch ready = new CountDownLatch(1);
        handler.post(() -> {
            try { if (!closed) initializeGl(); }
            catch (Throwable error) { failure = error; destroyGl(); }
            finally { ready.countDown(); if (closed) destroyGl(); }
        });
        boolean completed = false;
        try { completed = ready.await(2500, TimeUnit.MILLISECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        if (!completed || failure != null || input == null) {
            release();
            throw new IllegalStateException("HDR GPU output initialization failed", failure);
        }
        Log.i(TAG, (forceBt709 ? "GPU BT.709 override " : transfer < 6 ? "GPU BT.2020 -> BT.709 " : "GPU HDR -> SDR ")
                + width + "x" + height + " transfer=" + transfer + " peak=" + peak
                + " sampling=" + (rawYuv ? "raw YUV" : "RGB compatibility"));
    }

    public Surface getSurface() { return input; }

    /** Optional native GL extension entry point; called only while building a LUT. */
    public static native boolean uploadLut3d(ByteBuffer pixels);
    public static native boolean uploadLut3d128(ByteBuffer pixels);

    public static void prepareSoftwareOutput() {
        NativeHdrOutput previous=active;
        if (previous == null) return;
        previous.release();
        try {
            if (!previous.stopped.await(1500,TimeUnit.MILLISECONDS))
                throw new IllegalStateException("HDR output is still connected");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(error);
        }
    }

    public static void verifyToneMapping(MediaCodec codec, MediaFormat format) {
        if (Build.VERSION.SDK_INT >= 31 && integer(format,"color-transfer-request",0) == 3) {
            int accepted = integer(codec.getInputFormat(),"color-transfer-request",0);
            if (accepted != 3) throw new IllegalStateException("Decoder does not support HDR -> SDR tone mapping");
        }
    }

    public void outputFormat(MediaFormat format) {
        Log.i(TAG,"HDR decoder output " + format);
        updateOutputGeometry(format);
        int reportedTransfer = integer(format,"color-transfer",0);
        int reportedStandard = integer(format,"color-standard",0);
        int outputStandard = knownStandard(reportedStandard) ? reportedStandard : configuredStandard;
        boolean knownTransfer = reportedTransfer == 1 || reportedTransfer == 3
                || reportedTransfer == 6 || reportedTransfer == 7;
        boolean incompleteSdr = (transfer == 6 || transfer == 7)
                && (reportedTransfer == 1 || reportedTransfer == 3) && !knownStandard(reportedStandard);
        // Missing/unspecified output tags do not demonstrate HDR -> SDR conversion.
        // Keep the validated source transfer until the codec reports a complete
        // SDR colour description. Otherwise a legacy partial SDR tag bypasses
        // the HLG/PQ shader while its pixels are still HDR.
        final int outputTransfer = !knownTransfer || incompleteSdr ? transfer : reportedTransfer;
        decoderColor = "standard=" + integer(format,"color-standard",0)
                + " transfer=" + integer(format,"color-transfer",0)
                + " range=" + integer(format,"color-range",0);
        if (incompleteSdr) {
            decoderColor += " (source fallback=" + configuredStandard + "/" + transfer + ")";
            Log.w(TAG,"Incomplete SDR output tags; keep source HDR transfer=" + transfer
                    + " standard=" + configuredStandard);
        }
        float outputPeak = peak(format,peak);
        if (!closed) handler.post(() -> {
            if (closed || program == 0) return;
            GLES20.glUseProgram(program);
            if (forceBt709) {
                updateYuvColor(1,integer(format,"color-range",configuredRange));
                updateBt709Color(outputStandard);
                return;
            }
            refreshToneLut((outputTransfer == 1 || outputTransfer == 3) && outputStandard != 6
                    ? 0 : outputTransfer, outputPeak);
            updateYuvColor(outputStandard,integer(format,"color-range",configuredRange));
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"ntvPeak"),outputPeak);
            if (outputTransfer == 6 || outputTransfer == 7) {
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"ntvTransfer"),outputTransfer);
            } else if (outputTransfer == 3 || outputTransfer == 1) {
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"ntvTransfer"),
                        outputStandard == 6 ? outputTransfer : 0f);
                Log.i(TAG,"Decoder SDR output; gamut conversion=" + (outputStandard == 6));
            }
        });
    }

    private static boolean knownStandard(int value) {
        return value == 1 || value == 2 || value == 4 || value == 6;
    }

    private void updateOutputGeometry(MediaFormat format) {
        int w=integer(format,"width",0),h=integer(format,"height",0);
        int left=integer(format,"crop-left",-1),top=integer(format,"crop-top",-1);
        int right=integer(format,"crop-right",-1),bottom=integer(format,"crop-bottom",-1);
        boolean valid=w>0&&h>0&&left>=0&&top>=0&&right>=left&&bottom>=top&&right<w&&bottom<h;
        int cw=valid?right-left+1:0,ch=valid?bottom-top+1:0;
        decoderGeometry=new int[]{w,h,cw,ch};
        // Some codecs downsample their GPU output even though the coded size is
        // 4K. Match that real input; rendering an enlarged copy wastes bandwidth.
        // Require a substantial, proportional reduction, not ordinary edge crop.
        boolean reduced=valid && (long)cw*4<= (long)w*3 && (long)ch*4<= (long)h*3
                && Math.abs((double)cw*h/(ch*(double)w)-1)<0.02;
        requestBufferSize(target,reduced?cw:0,reduced?ch:0,this);
    }

    private static void requestBufferSize(Surface surface,int width,int height,NativeHdrOutput expected) {
        DirectVideoView view=outputView.get();
        if(view==null)return;
        view.post(()->{
            if(view.getVideoSurface()!=surface)return;
            NativeHdrOutput current=active;
            if(expected!=null ? current!=expected||expected.closed
                    : current!=null&&!current.closed&&current.target==surface)return;
            view.setGpuBufferSize(width,height);
        });
    }

    static float renderedFrameRate() {
        NativeHdrOutput output = active;
        if (output == null || output.closed) return Float.NaN;
        return SystemClock.elapsedRealtime()-output.lastRenderedAt > 1000 ? 0f : output.renderedRate;
    }

    static String outputRoute(boolean hardware) {
        return hardware ? outputRoute : "软解→SDR";
    }

    static String decoderColor() {
        NativeHdrOutput output = active;
        return output == null || output.closed ? "" : output.decoderColor
                + (output.lutActive ? " mapping=LUT"+(BuildConfig.HDR_LUT_TEST_128?"128-center":"64")+(output.lutProgramVolume?"-3D":"-2D") : "");
    }

    static org.json.JSONObject performance() throws org.json.JSONException {
        NativeHdrOutput v=active;
        org.json.JSONObject result=new org.json.JSONObject().put("available",v!=null&&!v.closed);
        if(v==null||v.closed)return result;
        int[] geometry=v.decoderGeometry;
        if(geometry.length==4)result.put("decoderWidth",geometry[0]).put("decoderHeight",geometry[1])
                .put("decoderCropWidth",geometry[2]).put("decoderCropHeight",geometry[3]);
        return result.put("latchMs",v.latchMs).put("submitMs",v.submitMs).put("swapMs",v.swapMs)
                .put("lut",v.lutActive).put("lut3d",v.lutActive&&v.lutProgramVolume).put("frames",v.renderedFrames)
                .put("inputWidth",v.width).put("inputHeight",v.height)
                .put("outputWidth",v.renderWidth).put("outputHeight",v.renderHeight)
                .put("swapInterval",v.swapInterval)
                .put("sdrCurve",v.sdrCurveActive).put("sdrCurveFloat",v.sdrCurveFloating)
                .put("glVersion",v.glVersion).put("glRenderer",v.glRenderer);
    }

    public static long renderedFrameCount() {
        NativeHdrOutput output=active;return output==null ? 0 : output.renderedFrames;
    }

    private void initializeGl() throws Exception {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new IllegalStateException("eglInitialize");
        EGLConfig config = createOutputContext();
        window = EGL14.eglCreateWindowSurface(display, config, target, new int[]{EGL14.EGL_NONE}, 0);
        if (window == EGL14.EGL_NO_SURFACE || eglContext == EGL14.EGL_NO_CONTEXT
                || !EGL14.eglMakeCurrent(display, window, window, eglContext))
            throw new IllegalStateException("HDR EGL window error " + EGL14.eglGetError());
        configurePresentation(config);
        int[] size = new int[1];
        EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, size, 0); renderWidth = size[0];
        EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, size, 0); renderHeight = size[0];
        String extensions=GLES20.glGetString(GLES20.GL_EXTENSIONS);
        String versionString=GLES20.glGetString(GLES20.GL_VERSION);
        glVersion=versionString;glRenderer=GLES20.glGetString(GLES20.GL_RENDERER);
        rawYuv=HdrDisplayPolicy.rawYuv(Build.VERSION.SDK_INT,versionString,extensions);
        String tone = "";
        if (!forceBt709) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream source = context.getResources().openRawResource(R.raw.hdr_tonemap)) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = source.read(buffer)) > 0) bytes.write(buffer, 0, n);
            }
            tone=bytes.toString("UTF-8");
            toneLut=new HdrToneMapLut(tone);
        }
        toneReference=tone;
        try{referenceProgram=buildProgram(tone,rawYuv,false);}catch(RuntimeException failure){
            if(!rawYuv)throw failure;
            Log.w(TAG,"Raw YUV shader unavailable; use legacy RGB compatibility output",failure);
            rawYuv=false;referenceProgram=buildProgram(tone,false,false);
        }
        selectProgram(referenceProgram);
        refreshToneLut(transfer,peak);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"ntvTransfer"), transfer);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"ntvBt709Source"),forceBt709 ? 1f : 0f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"ntvPeak"), peak);
        if(forceBt709)updateBt709Color(1);
        updateYuvColor(forceBt709?1:configuredStandard,configuredRange);
        int[] textures = new int[1]; GLES20.glGenTextures(1, textures, 0); textureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        texture = new SurfaceTexture(textureId); texture.setDefaultBufferSize(width,height);
        texture.setOnFrameAvailableListener(this); input = new Surface(texture);
    }

    private EGLConfig createOutputContext() {
        EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
        boolean es3=Build.VERSION.SDK_INT>=18;
        int[] attrs={EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT | (es3 ? 0x40 : 0),
                EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,EGL14.EGL_NONE};
        // EGL_OPENGL_ES3_BIT_KHR is 0x40. Require both bits so a rejected
        // ES3 context can safely fall back to ES2 using this same configuration.
        boolean chosen=EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0)&&count[0]>0;
        if(!chosen&&es3){
            es3=false;attrs[7]=EGL14.EGL_OPENGL_ES2_BIT;
            chosen=EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0)&&count[0]>0;
        }
        if(!chosen)throw new IllegalStateException("eglChooseConfig");
        if(es3)eglContext=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
        if(eglContext==EGL14.EGL_NO_CONTEXT)eglContext=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
        return configs[0];
    }

    private void configurePresentation(EGLConfig config) {
        // IJK releases these frames against its A/V clock already. Avoid adding
        // another refresh-period wait after GPU conversion when EGL permits it.
        int[] minimum = {1};
        if (EGL14.eglGetConfigAttrib(display, config, EGL14.EGL_MIN_SWAP_INTERVAL, minimum, 0))
            swapInterval = Math.max(0, minimum[0]);
        if (!EGL14.eglSwapInterval(display, swapInterval)) {
            swapInterval = 1;
            EGL14.eglSwapInterval(display, swapInterval);
        }
    }

    private int buildProgram(String tone,boolean yuv,boolean lookup){
        if(forceBt709)tone=HdrDisplayPolicy.BT709_SHADER;
        else if(lookup){
            tone=toneLut.isVolume()
                    ?BuildConfig.HDR_LUT_TEST_128?HdrToneMapLut.LOOKUP_3D_128:HdrToneMapLut.LOOKUP_3D
                    :BuildConfig.HDR_LUT_TEST_128?HdrToneMapLut.LOOKUP_ONLY_128:HdrToneMapLut.LOOKUP_ONLY;
            if(yuv)tone=tone.replace("texture2D(","texture(").replace("texture3D(","texture(");
        }
        else tone+="\nvec3 ntvFastToSdr(vec3 c){return ntvToSdr(c);}\n";
        String vertex=yuv?"#version 300 es\nin vec2 position;in vec2 texcoord;uniform mat4 transform;out vec2 uv;void main(){gl_Position=vec4(position,0.,1.);uv=(transform*vec4(texcoord,0.,1.)).xy;}":
                "attribute vec2 position;attribute vec2 texcoord;uniform mat4 transform;varying vec2 uv;void main(){gl_Position=vec4(position,0.,1.);uv=(transform*vec4(texcoord,0.,1.)).xy;}";
        String fragment=yuv?"#version 300 es\n#extension GL_EXT_YUV_target : require\nprecision highp float;uniform highp __samplerExternal2DY2YEXT video;uniform mat3 yuvMatrix;uniform vec3 yuvOffset;in vec2 uv;out vec4 pixel;\n"+tone+"\nvoid main(){pixel=vec4(ntvFastToSdr(yuvMatrix*(texture(video,uv).xyz-yuvOffset)),1.);}":
                "#extension GL_OES_EGL_image_external : require\n"
                +(lookup&&toneLut.isVolume()?"#extension GL_OES_texture_3D : require\n":"")
                +"precision highp float;uniform samplerExternalOES video;varying vec2 uv;\n"+tone+"\nvoid main(){gl_FragColor=vec4(ntvFastToSdr(texture2D(video,uv).rgb),1.);}";
        return linkProgram(vertex,fragment);
    }

    static int linkProgram(String vertex,String fragment) {
        int vs=0,fs=0,id=0;
        try{
            vs=shader(GLES20.GL_VERTEX_SHADER,vertex);fs=shader(GLES20.GL_FRAGMENT_SHADER,fragment);
            id=GLES20.glCreateProgram();GLES20.glAttachShader(id,vs);GLES20.glAttachShader(id,fs);GLES20.glLinkProgram(id);
            int[] linked=new int[1];GLES20.glGetProgramiv(id,GLES20.GL_LINK_STATUS,linked,0);
            if(linked[0]==0)throw new IllegalStateException(GLES20.glGetProgramInfoLog(id));return id;
        }catch(RuntimeException error){if(id!=0)GLES20.glDeleteProgram(id);throw error;}
        finally{if(vs!=0)GLES20.glDeleteShader(vs);if(fs!=0)GLES20.glDeleteShader(fs);}
    }
    private void refreshToneLut(float trc,float maximum) {
        if(forceBt709)return;
        boolean use = !forceBt709 && (trc == 6 || trc == 7) && !lutFailed;
        if (use) try {
            int lut = toneLut.update(trc,maximum);
            if(lutProgram==0||lutProgramVolume!=toneLut.isVolume()){
                int next;
                try{next=buildProgram(toneReference,rawYuv,true);}
                catch(RuntimeException error){
                    if(!toneLut.isVolume())throw error;
                    Log.w(TAG,"3D LUT shader unavailable; keep 2D lookup",error);
                    toneLut.disableVolume();lut=toneLut.update(trc,maximum);
                    next=buildProgram(toneReference,rawYuv,true);
                }
                if(lutProgram!=0)GLES20.glDeleteProgram(lutProgram);
                lutProgram=next;lutProgramVolume=toneLut.isVolume();
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(toneLut.target(),lut);
        } catch (RuntimeException error) {
            lutFailed=true;use=false;toneLut.release();
            Log.w(TAG,"HDR LUT unavailable; retain reference tone mapping",error);
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        int selected = use ? lutProgram : referenceProgram;
        if (trc == 1 || trc == 3) {
            if (sdrProgram == 0 || sdrTransfer != (int) trc) {
                try {
                    int next;
                    boolean curve = false;
                    // Selecting another transfer can replace the curve texture.
                    if (sdrProgramUsesCurve && sdrProgram != 0) {
                        if (program == sdrProgram) selectProgram(referenceProgram);
                        GLES20.glDeleteProgram(sdrProgram); sdrProgram = 0; sdrProgramUsesCurve = false;
                    }
                    try {
                        if (!sdrCurveFailed) {
                            if (sdrCurve == null) sdrCurve = new HdrSdrCurve();
                            curve = sdrCurve.select(toneReference, (int) trc);
                        }
                        next = buildProgram(curve ? sdrCurve.programSource((int) trc, rawYuv)
                                : HdrDisplayPolicy.sdrShader(toneReference, (int) trc), rawYuv, false);
                    } catch (RuntimeException | OutOfMemoryError error) {
                        curve = false;
                        if (!sdrCurveFailed) Log.w(TAG,"SDR curve unavailable; retain specialized formula",error);
                        sdrCurveFailed = true;
                        if (sdrCurve != null) { sdrCurve.release(); sdrCurve = null; }
                        for (int i=0; i<16 && GLES20.glGetError()!=GLES20.GL_NO_ERROR; i++) {}
                        next = buildProgram(HdrDisplayPolicy.sdrShader(toneReference, (int) trc), rawYuv, false);
                    }
                    sdrProgramUsesCurve = curve;
                    if (sdrProgram != 0) GLES20.glDeleteProgram(sdrProgram);
                    sdrProgram = next; sdrTransfer = (int) trc;
                } catch (RuntimeException | OutOfMemoryError error) {
                    Log.w(TAG, "Specialized SDR shader unavailable; retain reference", error);
                }
            }
            if (sdrProgram != 0 && sdrTransfer == (int) trc) {
                if (sdrProgramUsesCurve) sdrCurve.bind();
                selected = sdrProgram;
            }
        }
        selectProgram(selected);
        sdrCurveActive = selected == sdrProgram && sdrProgramUsesCurve;
        sdrCurveFloating = sdrCurveActive && sdrCurve != null && sdrCurve.isFloating();
        lutActive=use;
        // LUT generation changes vertex pointers and viewport in this GL context.
        drawStateDirty=true;viewportDirty=true;
    }

    private void selectProgram(int selected) {
        program=selected;GLES20.glUseProgram(program);
        position=GLES20.glGetAttribLocation(program,"position");
        texcoord=GLES20.glGetAttribLocation(program,"texcoord");
        transform=GLES20.glGetUniformLocation(program,"transform");
        yuvMatrix=GLES20.glGetUniformLocation(program,"yuvMatrix");
        yuvOffset=GLES20.glGetUniformLocation(program,"yuvOffset");
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"video"),0);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"ntvLut"),1);
        drawStateDirty=true;
    }

    static void surfaceSizeChanged(Surface surface) {
        NativeHdrOutput v=active;
        if(v!=null&&!v.closed&&v.target==surface)v.handler.post(()->{
            if(!v.closed)v.viewportDirty=true;
        });
    }

    private void prepareDrawState(long now) {
        // Surface callbacks invalidate immediately. The one-second query also
        // covers vendor window changes that arrive without a holder callback.
        if(viewportDirty||now>=nextWindowCheck){
            if(EGL14.eglQuerySurface(display,window,EGL14.EGL_WIDTH,windowSize,0)&&windowSize[0]>0)renderWidth=windowSize[0];
            if(EGL14.eglQuerySurface(display,window,EGL14.EGL_HEIGHT,windowSize,0)&&windowSize[0]>0)renderHeight=windowSize[0];
            GLES20.glViewport(0,0,renderWidth,renderHeight);
            viewportDirty=false;nextWindowCheck=now+1000000000L;
        }
        if(drawStateDirty){
            GLES20.glUseProgram(program);
            vertices.position(0);GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,16,vertices);
            vertices.position(2);GLES20.glVertexAttribPointer(texcoord,2,GLES20.GL_FLOAT,false,16,vertices);
            GLES20.glEnableVertexAttribArray(position);GLES20.glEnableVertexAttribArray(texcoord);
            drawStateDirty=false;
        }
    }

    private void updateYuvColor(int standard,int range){
        if(!rawYuv)return;
        float[] color=HdrDisplayPolicy.yuvColor(standard,range,sourceDepth);
        GLES20.glUniformMatrix3fv(yuvMatrix,1,false,color,0);GLES20.glUniform3fv(yuvOffset,1,color,9);
    }

    private void updateBt709Color(int standard) {
        GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(program,"ntvBt709Matrix"),
                1,false,HdrDisplayPolicy.bt709Reinterpretation(standard,rawYuv),0);
    }

    private static int shader(int kind, String text) {
        int id = GLES20.glCreateShader(kind); GLES20.glShaderSource(id,text); GLES20.glCompileShader(id);
        int[] valid = new int[1]; GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,valid,0);
        if (valid[0] == 0) {
            String error = GLES20.glGetShaderInfoLog(id); GLES20.glDeleteShader(id);
            throw new IllegalStateException(error);
        }
        return id;
    }

    @Override public void onFrameAvailable(SurfaceTexture value) {
        if (closed) return;
        // Created on this HandlerThread; SurfaceTexture delivers callbacks on its Looper.
        try {
            long timingBegin=System.nanoTime();
            texture.updateTexImage();
            long latched=System.nanoTime();
            long timestamp=texture.getTimestamp();
            if (timestamp == lastTextureTimestamp && renderedFrames > 0) return;
            lastTextureTimestamp=timestamp; texture.getTransformMatrix(matrix);
            prepareDrawState(latched);
            GLES20.glUniformMatrix4fv(transform,1,false,matrix,0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
            long submitted=System.nanoTime();
            if (!EGL14.eglSwapBuffers(display,window)) throw new IllegalStateException("HDR swap failed");
            long swapped=System.nanoTime();
            timingLatch+=latched-timingBegin;timingSubmit+=submitted-latched;timingSwap+=swapped-submitted;timingCount++;
            if(timingStart==0)timingStart=swapped;
            if(swapped-timingStart>=1000000000L){
                double divisor=timingCount*1000000.0;
                latchMs=timingLatch/divisor;submitMs=timingSubmit/divisor;swapMs=timingSwap/divisor;
                timingLatch=timingSubmit=timingSwap=timingCount=0;timingStart=swapped;
            }
            long now=SystemClock.elapsedRealtime();
            renderedFrames++; lastRenderedAt=now;
            if (rateStart == 0) {rateStart=now;rateFrames=renderedFrames;}
            if (now-rateStart >= 500) {
                renderedRate=(renderedFrames-rateFrames)*1000f/(now-rateStart);
                rateStart=now;rateFrames=renderedFrames;
            }
        } catch (RuntimeException error) {
            failure = error; Log.e(TAG,"HDR output failed",error); release();
        }
    }

    public void release() {
        if (closed) return; closed = true;
        requestBufferSize(target,0,0,null);
        Runnable close = () -> {
            try { destroyGl(); }
            finally { stopped.countDown(); thread.quit(); if (active==this) active=null; }
        };
        if (Looper.myLooper()==thread.getLooper()) close.run();
        else handler.post(close);
    }

    private void destroyGl() {
        if (texture != null) texture.setOnFrameAvailableListener(null);
        if (input != null) { input.release(); input = null; }
        if (texture != null) { texture.release(); texture = null; }
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (toneLut != null) {toneLut.release();toneLut=null;}
            if (sdrCurve != null) {sdrCurve.release();sdrCurve=null;}
            if (referenceProgram != 0) GLES20.glDeleteProgram(referenceProgram);
            if (lutProgram != 0) GLES20.glDeleteProgram(lutProgram);
            if (sdrProgram != 0) GLES20.glDeleteProgram(sdrProgram);
            if (textureId != 0) GLES20.glDeleteTextures(1,new int[]{textureId},0);
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,window);
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,eglContext);
            EGL14.eglTerminate(display); EGL14.eglReleaseThread();
        }
        display = EGL14.EGL_NO_DISPLAY; window = EGL14.EGL_NO_SURFACE; eglContext = EGL14.EGL_NO_CONTEXT;
        program = referenceProgram = lutProgram = sdrProgram = textureId = 0;
    }

}
