package xiao.bu.tv;

import android.opengl.GLES20;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** High-precision SDR gamut curves, enabled only after a local GPU comparison. */
final class HdrSdrCurve {
    private static final int SIZE = 2048;
    private static final String SHADER = "uniform highp sampler2D ntvLut;\n"
            + "float decodeCurve(float x,float row) {return dot(texture2D(ntvLut,vec2(x*0.99951171875+0.000244140625,row)).rgb,vec3(0.99609380937,0.00389105058,0.000015199185));}\n"
            + "float curveLookup(float v,float row) {float x=v*2047.0;float i=floor(x);return mix(decodeCurve(i/2047.0,row),decodeCurve(min(i+1.0,2047.0)/2047.0,row),x-i);}\n"
            + "vec3 ntvToSdr(vec3 encoded) {\n"
            + "vec3 v=clamp(encoded,0.0,1.0);\n"
            + "vec3 linear=TRANSFER == 1 ? v : vec3(curveLookup(v.r,0.25),curveLookup(v.g,0.25),curveLookup(v.b,0.25));\n"
            + "vec3 rgb=mat3(1.660491,-0.124550,-0.018151,-0.587641,1.132900,-0.100579,-0.072850,-0.008349,1.118730)*linear;\n"
            + "rgb=sqrt(clamp(rgb,0.0,1.0));\n"
            + "return vec3(curveLookup(rgb.r,0.75),curveLookup(rgb.g,0.75),curveLookup(rgb.b,0.75));}\n";
    private static final String FLOAT_LOOKUP = "float curveLookup(float v,float row){return texture2D(ntvLut,vec2(v*0.99951171875+0.000244140625,row)).r;}\n";
    private int texture;
    private boolean floating;
    private static final int PROBE_SIZE = 256;
    private static final Map<String,Integer> CHOICES = new LinkedHashMap<String,Integer>(16,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Integer> entry) { return size()>16; }
    };

    /** Cache decisions, never GL objects: a new EGL context must upload its own texture. */
    boolean select(String reference, int transfer) {
        String key = GLES20.glGetString(GLES20.GL_VENDOR)+"/"+GLES20.glGetString(GLES20.GL_RENDERER)
                +"/"+GLES20.glGetString(GLES20.GL_VERSION)+"/"+transfer;
        Integer choice;
        synchronized (CHOICES) { choice=CHOICES.get(key); }
        if (choice==null) {
            try { choice=compare(reference,transfer); }
            catch (RuntimeException | OutOfMemoryError failure) { choice=0; }
            synchronized (CHOICES) { CHOICES.put(key,choice); }
        }
        release();
        if (choice==0) return false;
        try { prepare(choice==2); return true; }
        catch (RuntimeException | OutOfMemoryError failure) {
            release();
            synchronized (CHOICES) { CHOICES.put(key,0); }
            throw failure;
        }
    }

    static boolean faster(long[] reference, long[] candidate) {
        // Alternating draw order and a conservative margin reject timer noise.
        if(reference.length!=7||candidate.length!=7)return false;
        int wins=0;
        for(int i=0;i<7;i++)if(candidate[i]>0&&candidate[i]<reference[i]*.9)wins++;
        long[] a=reference.clone(),b=candidate.clone();Arrays.sort(a);Arrays.sort(b);
        return wins>=6&&b[3]>0&&b[3]<a[3]*.85&&a[3]-b[3]>=20000;
    }

    static boolean accurate(ByteBuffer reference,ByteBuffer candidate) {
        if(reference.remaining()!=candidate.remaining()||reference.remaining()%4!=0)return false;
        for(int i=0;i<reference.remaining();i++) {
            int difference=Math.abs((reference.get(reference.position()+i)&255)-(candidate.get(candidate.position()+i)&255));
            if(difference>(i%4==3?0:1))return false;
        }
        return true;
    }

    private int compare(String reference,int transfer) {
        int[] saved=new int[4],viewport=new int[4],ids=new int[2];
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING,saved,0);
        GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM,saved,1);
        GLES20.glGetIntegerv(GLES20.GL_ARRAY_BUFFER_BINDING,saved,2);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D,saved,3);
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT,viewport,0);
        int original=0,candidate=0,best=0;long bestTime=Long.MAX_VALUE;
        // Stop starting work after 300 ms; individual driver calls cannot be
        // interrupted. Results are cached for this GPU/transfer in the process.
        long deadline=System.nanoTime()+300000000L;
        FloatBuffer quad=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);
        try {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,0);
            GLES20.glGenTextures(1,ids,0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,ids[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,PROBE_SIZE,PROBE_SIZE,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
            GLES20.glGenFramebuffers(1,ids,1);GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,ids[1]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,ids[0],0);
            if(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)!=GLES20.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Curve probe framebuffer");
            GLES20.glViewport(0,0,PROBE_SIZE,PROBE_SIZE);
            original=probeProgram(HdrDisplayPolicy.sdrShader(reference,transfer));
            ByteBuffer expected=ByteBuffer.allocateDirect(PROBE_SIZE*PROBE_SIZE*4);
            ByteBuffer actual=ByteBuffer.allocateDirect(expected.capacity());
            drawProbe(original,quad);readProbe(expected);
            boolean floatSupported=supportsFloat(GLES20.glGetString(GLES20.GL_VERSION),GLES20.glGetString(GLES20.GL_EXTENSIONS));
            for(int mode=2;mode>=1;mode--) {
                if(System.nanoTime()>deadline)break;
                if(mode==2&&!floatSupported)continue;
                release();
                try {
                    prepare(mode==2);candidate=probeProgram(programSource(transfer,false));
                    drawProbe(candidate,quad);readProbe(actual);
                    if(!accurate(expected,actual))continue;
                    long[] a=new long[7],b=new long[7];
                    // Synchronization is confined to this startup probe, never video frames.
                    boolean complete=true;
                    for(int round=0;round<7;round++) {
                        if(System.nanoTime()>deadline){complete=false;break;}
                        if((round&1)==0){a[round]=drawProbe(original,quad);b[round]=drawProbe(candidate,quad);}
                        else{b[round]=drawProbe(candidate,quad);a[round]=drawProbe(original,quad);}
                    }
                    if(complete&&faster(a,b)) {
                        long[] sorted=b.clone();Arrays.sort(sorted);
                        if(sorted[3]<bestTime){best=mode;bestTime=sorted[3];}
                    }
                } catch(RuntimeException | OutOfMemoryError unavailable) {
                    // A rejected optional texture/program must leave the formula usable.
                } finally {
                    if(candidate!=0){GLES20.glDeleteProgram(candidate);candidate=0;}
                    clearErrors();
                }
            }
            return best;
        } finally {
            release();
            if(original!=0)GLES20.glDeleteProgram(original);
            if(ids[1]!=0)GLES20.glDeleteFramebuffers(1,ids,1);
            if(ids[0]!=0)GLES20.glDeleteTextures(1,ids,0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,saved[0]);
            GLES20.glUseProgram(saved[1]);GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,saved[2]);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,saved[3]);
            GLES20.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            clearErrors();
        }
    }

    private static int probeProgram(String tone) {
        return NativeHdrOutput.linkProgram("attribute vec2 position;void main(){gl_Position=vec4(position,0.,1.);}",
                "precision highp float;"+tone+"\nvoid main(){vec2 p=gl_FragCoord.xy/"+PROBE_SIZE+".;vec3 c=vec3(p,fract(p.x*997.+p.y*991.));if(p.y<.03125)c=vec3(p.x);gl_FragColor=vec4(ntvToSdr(c),1.);}");
    }

    private static long drawProbe(int program,FloatBuffer quad) {
        GLES20.glUseProgram(program);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"ntvLut"),1);
        int position=GLES20.glGetAttribLocation(program,"position");
        GLES20.glEnableVertexAttribArray(position);quad.position(0);
        GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,quad);
        long start=System.nanoTime();
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GLES20.glFinish();
        long elapsed=System.nanoTime()-start;
        GLES20.glDisableVertexAttribArray(position);
        if(GLES20.glGetError()!=GLES20.GL_NO_ERROR)throw new IllegalStateException("Curve probe draw");
        return elapsed;
    }

    private static void readProbe(ByteBuffer pixels) {
        pixels.clear();GLES20.glReadPixels(0,0,PROBE_SIZE,PROBE_SIZE,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels);
        if(GLES20.glGetError()!=GLES20.GL_NO_ERROR)throw new IllegalStateException("Curve probe readback");
    }

    private static void clearErrors() { for(int i=0;i<16&&GLES20.glGetError()!=GLES20.GL_NO_ERROR;i++){} }

    boolean isFloating() { return floating; }
    String programSource(int transfer, boolean yuv) {
        return floating ? floatShader(transfer, yuv) : shader(transfer, yuv);
    }
    static String floatShader(int transfer, boolean yuv) {
        String packed = shader(transfer, false);
        String source = packed.substring(0, packed.indexOf("float decodeCurve(")) + FLOAT_LOOKUP
                + packed.substring(packed.indexOf("vec3 ntvToSdr("));
        return yuv ? source.replace("texture2D(", "texture(") : source;
    }
    static ByteBuffer floatPixels() {
        ByteBuffer data = ByteBuffer.allocateDirect(SIZE * 2 * 4 * 4).order(ByteOrder.nativeOrder());
        FloatBuffer values = data.asFloatBuffer();
        for (int row=0; row<2; row++) for (int i=0; i<SIZE; i++) {
            float value = (float) Math.pow(i / (double) (SIZE - 1), row==0 ? 2.4 : 5.0 / 6.0);
            values.put(value).put(value).put(value).put(1f);
        }
        return data;
    }
    static boolean supportsFloat(String version, String extensions) {
        // Sized RGBA32F is required. Some legacy drivers silently lower the
        // precision of unsized RGBA/FLOAT textures even with float extensions.
        return version != null && (version.startsWith("OpenGL ES 3.") || version.startsWith("OpenGL ES 4."))
                && extensions != null && (" " + extensions + " ").contains(" GL_OES_texture_float_linear ");
    }

    static String shader(int transfer, boolean yuv) {
        if (transfer != 1 && transfer != 3) throw new IllegalArgumentException("SDR transfer");
        String result = SHADER.replace("TRANSFER", Integer.toString(transfer));
        return yuv ? result.replace("texture2D(", "texture(") : result;
    }

    static ByteBuffer pixels() {
        ByteBuffer data = ByteBuffer.allocateDirect(SIZE * 2 * 4);
        for (int row = 0; row < 2; row++) for (int i = 0; i < SIZE; i++) {
            double x = i / (double) (SIZE - 1);
            int value = (int) Math.round(Math.pow(x, row == 0 ? 2.4 : 5.0 / 6.0) * 16777215.0);
            data.put((byte) (value >>> 16)).put((byte) (value >>> 8)).put((byte) value).put((byte) 255);
        }
        data.flip(); return data;
    }

    void bind() {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        try {
            if(texture==0)throw new IllegalStateException("SDR curve is not selected");
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        } finally { GLES20.glActiveTexture(GLES20.GL_TEXTURE0); }
    }

    private void prepare(boolean useFloat) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        try {
            int[] maximum=new int[1];GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE,maximum,0);
            if(maximum[0]<SIZE)throw new IllegalStateException("SDR curve texture size");
            upload(useFloat);
        } finally { GLES20.glActiveTexture(GLES20.GL_TEXTURE0); }
    }

    private void upload(boolean useFloat) {
        ByteBuffer data = useFloat ? floatPixels() : pixels();
        int[] id = new int[1]; GLES20.glGenTextures(1, id, 0); texture = id[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        // Packed bytes need nearest sampling and interpolation after decoding.
        // Float curves can interpolate directly without carries between channels.
        int filter = useFloat ? GLES20.GL_LINEAR : GLES20.GL_NEAREST;
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,useFloat ? 0x8814 : GLES20.GL_RGBA,SIZE,2,0,GLES20.GL_RGBA,
                useFloat ? GLES20.GL_FLOAT : GLES20.GL_UNSIGNED_BYTE,data);
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) throw new IllegalStateException("SDR curve upload");
        floating = useFloat;
    }

    void release() {
        if (texture != 0) { GLES20.glDeleteTextures(1, new int[]{texture}, 0); texture = 0; }
        floating = false;
    }
}
