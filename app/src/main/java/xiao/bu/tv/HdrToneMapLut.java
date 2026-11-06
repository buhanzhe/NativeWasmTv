package xiao.bu.tv;

import android.opengl.GLES20;
import java.nio.ByteBuffer;

/** Cached colour LUT. The centered 128-cubed profile is an opt-in test build. */
final class HdrToneMapLut {
    // Production uses a separate program: legacy drivers can reserve registers
    // for the entire reference formula even when its uniform branch is off.
    // Correct the cached RGB nodes once; keep the original per-frame shaders.
    static final String LOOKUP_ONLY = "\nuniform sampler2D ntvLut;\nvec2 ntvLutUv(vec2 rg,float b){return (vec2(mod(b,8.0),floor(b/8.0))*64.0+rg*63.0+0.5)/512.0;}\nvec3 ntvFastToSdr(vec3 c){vec3 p=clamp(c,0.0,1.0);float z=p.b*63.0;float b=floor(z);return mix(texture2D(ntvLut,ntvLutUv(p.rg,b)).rgb,texture2D(ntvLut,ntvLutUv(p.rg,min(b+1.0,63.0))).rgb,z-b);}\n";
    // Precombine scale/bias and let S/T/R CLAMP_TO_EDGE enforce the same
    // half-texel bounds. Keep highp coordinates: mediump changes dark colours
    // on legacy drivers. No modulo, division or explicit RGB clamp per pixel.
    static final String LOOKUP_3D = "uniform highp sampler3D ntvLut;\n"
            + "vec3 ntvFastToSdr(vec3 c){return texture3D(ntvLut,c*0.984375+0.0078125).rgb;}\n";
    static final String LOOKUP = "\nuniform sampler2D ntvLut;uniform float ntvUseLut;\nvec2 ntvLutUv(vec2 rg,float b){return (vec2(mod(b,8.0),floor(b/8.0))*64.0+rg*63.0+0.5)/512.0;}\nvec3 ntvFastToSdr(vec3 c){if(ntvUseLut<0.5)return ntvToSdr(c);vec3 p=clamp(c,0.0,1.0);float z=p.b*63.0;float b=floor(z);return mix(texture2D(ntvLut,ntvLutUv(p.rg,b)).rgb,texture2D(ntvLut,ntvLutUv(p.rg,min(b+1.0,63.0))).rgb,z-b);}\n";
    static final String GENERATOR = "\nvoid main(){vec2 p=floor(gl_FragCoord.xy);"
            + "float b=floor(p.x/64.0)+8.0*floor(p.y/64.0);"
            + "vec3 c=vec3(mod(p.x,64.0),mod(p.y,64.0),b)/63.0;"
            + "gl_FragColor=vec4(ntvToSdr(c),1.0);}";
    static final String LOOKUP_3D_128 = "uniform highp sampler3D ntvLut;\n"
            + "vec3 ntvFastToSdr(vec3 c){return texture3D(ntvLut,c).rgb;}\n";
    static final String LOOKUP_ONLY_128 = "uniform sampler2D ntvLut;\n"
            + "vec2 ntvLutUv128(vec2 rg,float b){return vec2(mod(b,8.0)*0.125,floor(b*0.125)*0.0625)+rg*vec2(0.125,0.0625);}\n"
            + "vec3 ntvFastToSdr(vec3 c){vec2 rg=clamp(c.rg,0.00390625,0.99609375);float z=clamp(c.b*128.0-0.5,0.0,127.0);float b=floor(z);"
            + "return mix(texture2D(ntvLut,ntvLutUv128(rg,b)).rgb,texture2D(ntvLut,ntvLutUv128(rg,min(b+1.0,127.0))).rgb,z-b); }\n";
    static final String GENERATOR_128 = "\nvoid main(){vec2 p=floor(gl_FragCoord.xy);"
            + "float b=floor(p.x/128.0)+8.0*floor(p.y/128.0);vec3 i=vec3(mod(p.x,128.0),mod(p.y,128.0),b);"
            + "vec3 c=(i+0.5)/128.0;c=mix(c,vec3(0.0),vec3(1.0)-step(vec3(0.5),i));c=mix(c,vec3(1.0),step(vec3(127.0),i));"
            + "gl_FragColor=vec4(ntvToSdr(c),1.0);}";
    private static final int SIZE=BuildConfig.HDR_LUT_TEST_128?128:64;
    private static final int WIDTH=SIZE*8,HEIGHT=SIZE*(SIZE/8),BYTES=SIZE*SIZE*SIZE*4;
    private int texture, volumeTexture, framebuffer, program;
    private boolean volumeDisabled;
    private float lastTransfer=-1,lastPeak=-1;
    private final String reference;
    HdrToneMapLut(String reference) {this.reference=reference;}
    int update(float transfer,float peak) {
        // HLG reference viewing conditions do not depend on MaxCLL.
        if(transfer==7)peak=1000;
        if((texture!=0||volumeTexture!=0) && lastTransfer==transfer && lastPeak==peak)
            return volumeTexture!=0?volumeTexture:texture;
        if(program==0)program=NativeHdrOutput.linkProgram(
                "attribute vec2 position;void main(){gl_Position=vec4(position,0.,1.);}",
                "precision highp float;\n"+reference+(BuildConfig.HDR_LUT_TEST_128?GENERATOR_128:GENERATOR));
        if(texture==0){
            int[] ids=new int[1];GLES20.glGenTextures(1,ids,0);texture=ids[0];
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,WIDTH,HEIGHT,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
            if(framebuffer==0){GLES20.glGenFramebuffers(1,ids,0);framebuffer=ids[0];}
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer);
        try {
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,texture,0);
            if(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)!=GLES20.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("HDR LUT framebuffer unavailable");
            GLES20.glViewport(0,0,WIDTH,HEIGHT);GLES20.glUseProgram(program);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"ntvTransfer"),transfer);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"ntvPeak"),peak);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"ntvBt709Source"),0);
            java.nio.FloatBuffer vertices=java.nio.ByteBuffer.allocateDirect(32).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
            vertices.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);
            int position=GLES20.glGetAttribLocation(program,"position");
            GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,vertices);
            GLES20.glEnableVertexAttribArray(position);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
            if(GLES20.glGetError()!=GLES20.GL_NO_ERROR)throw new IllegalStateException("HDR LUT generation failed");
            ByteBuffer corrected=null;
            if(transfer==6){
                try{corrected=ByteBuffer.allocateDirect(BYTES);}
                catch(OutOfMemoryError failure){throw new IllegalStateException("PQ LUT correction memory",failure);}
                GLES20.glReadPixels(0,0,WIDTH,HEIGHT,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,corrected);
                if(GLES20.glGetError()!=GLES20.GL_NO_ERROR)throw new IllegalStateException("PQ LUT readback");
                repairNeutralAxis(corrected);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,WIDTH,HEIGHT,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,corrected);
                if(GLES20.glGetError()!=GLES20.GL_NO_ERROR)throw new IllegalStateException("PQ LUT correction upload");
            }
            promoteVolume(corrected);
            lastTransfer=transfer;lastPeak=peak;return volumeTexture!=0?volumeTexture:texture;
        } finally {GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);GLES20.glActiveTexture(GLES20.GL_TEXTURE0);}
    }
    boolean isVolume(){return volumeTexture!=0;}
    int target(){return isVolume()?0x806F:GLES20.GL_TEXTURE_2D;}
    void disableVolume(){
        volumeDisabled=true;
        if(volumeTexture!=0){GLES20.glDeleteTextures(1,new int[]{volumeTexture},0);volumeTexture=0;}
        lastTransfer=lastPeak=-1;
    }
    private void promoteVolume(ByteBuffer corrected){
        if(volumeDisabled)return;
        String extensions=GLES20.glGetString(GLES20.GL_EXTENSIONS);
        String version=GLES20.glGetString(GLES20.GL_VERSION);
        if(!supportsVolume(version,extensions)){volumeDisabled=true;return;}
        try {
            ByteBuffer atlas=corrected;
            if(atlas==null){atlas=ByteBuffer.allocateDirect(BYTES);
                GLES20.glReadPixels(0,0,WIDTH,HEIGHT,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,atlas);}
            if(GLES20.glGetError()!=GLES20.GL_NO_ERROR)throw new IllegalStateException("LUT readback");
            ByteBuffer cube=packVolume(atlas);
            if(volumeTexture==0){int[] id=new int[1];GLES20.glGenTextures(1,id,0);volumeTexture=id[0];}
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);GLES20.glBindTexture(0x806F,volumeTexture);
            GLES20.glTexParameteri(0x806F,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(0x806F,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(0x806F,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(0x806F,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(0x806F,0x8072,GLES20.GL_CLAMP_TO_EDGE);
            if(!(BuildConfig.HDR_LUT_TEST_128?NativeHdrOutput.uploadLut3d128(cube):NativeHdrOutput.uploadLut3d(cube)))throw new IllegalStateException("LUT volume upload");
            // The 2D atlas is only a generation scratch target, not a second cache.
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,0,0);
            GLES20.glDeleteTextures(1,new int[]{texture},0);texture=0;
        }catch(RuntimeException|LinkageError|OutOfMemoryError failure){
            disableVolume();
            // A failed optional upload must not poison the working 2D fallback.
            for(int i=0;i<16&&GLES20.glGetError()!=GLES20.GL_NO_ERROR;i++){}
            xiao.bu.tv.AppLog.w("nTvHdr","3D LUT unavailable; keep 2D lookup",failure);
        }
    }
    private static int atlasOffset(int size,int r,int g,int b){return (((b/8)*size+g)*(size*8)+(b%8)*size+r)*4;}
    static void repairNeutralAxis(ByteBuffer atlas){repairNeutralAxis(atlas,SIZE);}
    static void repairNeutralAxis(ByteBuffer atlas,int size){
        if(atlas.capacity()<size*size*size*4)throw new IllegalArgumentException("LUT atlas size");
        // Along each grey cell diagonal, trilinear interpolation is a cubic
        // Bezier curve. Set the sums of its two groups of three off-axis
        // corners to 2*A+D and A+2*D. This reproduces the straight grey curve.
        // Project onto byte bounds with the smallest squared RGB adjustment;
        // Only 6*(size-1) nodes can change. No extra per-pixel work.
        int[] offsets=new int[3],original=new int[3],value=new int[3];
        for(int k=0;k<size-1;k++)for(int n=1;n<=2;n++){
            int base=n==1?k:k+1,d=n==1?1:-1;
            offsets[0]=atlasOffset(size,base+d,base,base);
            offsets[1]=atlasOffset(size,base,base+d,base);
            offsets[2]=atlasOffset(size,base,base,base+d);
            for(int channel=0;channel<3;channel++){
                int remaining=(atlas.get(atlasOffset(size,k,k,k)+channel)&255)*(3-n)
                        +(atlas.get(atlasOffset(size,k+1,k+1,k+1)+channel)&255)*n;
                for(int i=0;i<3;i++){original[i]=value[i]=atlas.get(offsets[i]+channel)&255;remaining-=value[i];}
                int direction=remaining>0?1:-1;
                while(remaining!=0){
                    int best=-1;
                    for(int i=0;i<3;i++)if(value[i]+direction>=0&&value[i]+direction<=255
                            &&(best<0||Math.abs(value[i]-original[i])<Math.abs(value[best]-original[best])))best=i;
                    if(best<0)throw new IllegalStateException("PQ neutral correction bounds");
                    value[best]+=direction;remaining-=direction;
                }
                for(int i=0;i<3;i++)atlas.put(offsets[i]+channel,(byte)value[i]);
            }
        }
    }
    static boolean supportsVolume(String version,String extensions){
        return (version!=null&&(version.startsWith("OpenGL ES 3.")||version.startsWith("OpenGL ES 4.")))
                ||(extensions!=null&&(" "+extensions+" ").contains(" GL_OES_texture_3D "));
    }
    static ByteBuffer packVolume(ByteBuffer atlas){return packVolume(atlas,SIZE);}
    static ByteBuffer packVolume(ByteBuffer atlas,int size){
        if(size!=64&&size!=128)throw new IllegalArgumentException("LUT edge size");
        if(atlas.capacity()<size*size*size*4)throw new IllegalArgumentException("LUT atlas size");
        ByteBuffer cube=ByteBuffer.allocateDirect(size*size*size*4);
        ByteBuffer source=atlas.duplicate();source.clear();byte[] row=new byte[size*4];
        for(int z=0;z<size;z++)for(int y=0;y<size;y++){
            source.position(atlasOffset(size,0,y,z));source.get(row);cube.put(row);
        }
        cube.flip();return cube;
    }
    void release(){
        if(texture!=0)GLES20.glDeleteTextures(1,new int[]{texture},0);
        if(volumeTexture!=0)GLES20.glDeleteTextures(1,new int[]{volumeTexture},0);
        if(framebuffer!=0)GLES20.glDeleteFramebuffers(1,new int[]{framebuffer},0);
        if(program!=0)GLES20.glDeleteProgram(program);
        texture=volumeTexture=framebuffer=program=0;lastTransfer=lastPeak=-1;
    }
}
