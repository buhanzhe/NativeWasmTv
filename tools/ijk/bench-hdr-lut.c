/* Hardware bridge's LUT shader workload; isolated from video decoding. */
#define main ntv_formula_bench_main
#include "bench-hdr-algorithm.c"
#undef main
#include <GLES2/gl2ext.h>
#include "ntv_hdr_lut.h"

static GLuint generator(const char *directory,const char *reference) {
    char *body=load(directory,"lut-generator.glsl");
    char *fragment=calloc(strlen(reference)+strlen(body)+64,1);
    sprintf(fragment,"precision highp float;\n%s\n%s",reference,body);free(body);
    GLuint vs=shader(GL_VERTEX_SHADER,"attribute vec2 pos;void main(){gl_Position=vec4(pos,0.,1.);}"),
        fs=shader(GL_FRAGMENT_SHADER,fragment),p=glCreateProgram();
    glAttachShader(p,vs);glAttachShader(p,fs);glBindAttribLocation(p,0,"pos");glLinkProgram(p);
    GLint valid;glGetProgramiv(p,GL_LINK_STATUS,&valid);if(!valid)fail("LUT generator link");
    glDeleteShader(vs);glDeleteShader(fs);free(fragment);return p;
}
static GLuint probeProgram(const char *tone){
    char *source=calloc(strlen(tone)+160,1);sprintf(source,"%s\nuniform vec3 probe;vec3 ntvProbe(vec3 c){return ntvFastToSdr(probe);}",tone);
    GLuint p=program(source,"ntvProbe");free(source);return p;
}
static int atlasOffset(int r,int g,int b){return (((b/8)*64+g)*512+(b%8)*64+r)*4;}
static void repairNeutralAxis(unsigned char *atlas){
    for(int k=0;k<63;k++)for(int n=1;n<=2;n++){
        int base=n==1?k:k+1,d=n==1?1:-1;
        int offsets[]={atlasOffset(base+d,base,base),atlasOffset(base,base+d,base),atlasOffset(base,base,base+d)};
        for(int channel=0;channel<3;channel++){
            int original[3],value[3],remaining=atlas[atlasOffset(k,k,k)+channel]*(3-n)+atlas[atlasOffset(k+1,k+1,k+1)+channel]*n;
            for(int i=0;i<3;i++){original[i]=value[i]=atlas[offsets[i]+channel];remaining-=value[i];}
            int direction=remaining>0?1:-1;
            while(remaining){
                int best=-1;for(int i=0;i<3;i++)if(value[i]+direction>=0&&value[i]+direction<=255&&(best<0||abs(value[i]-original[i])<abs(value[best]-original[best])))best=i;
                if(best<0)fail("PQ correction bounds");value[best]+=direction;remaining-=direction;
            }
            for(int i=0;i<3;i++)atlas[offsets[i]+channel]=value[i];
        }
    }
}
static GLuint fixTextures[2];
static void bindFixTexture(int variant){
    if(!fixTextures[variant])return;
    glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_3D_OES,fixTextures[variant]);glActiveTexture(GL_TEXTURE0);
}
int main(int argc,char **argv) {
    if(argc<4)return 1;
    int width=atoi(argv[2]),height=atoi(argv[3]),rounds=argc>4?atoi(argv[4]):7,frames=argc>5?atoi(argv[5]):4;
    if(width<256||height<256||rounds<3||frames<1)fail("arguments");
    EGLDisplay display=eglGetDisplay(EGL_DEFAULT_DISPLAY);if(!eglInitialize(display,NULL,NULL))fail("init");
    EGLint attrs[]={EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,EGL_NONE};
    EGLConfig config;EGLint count;if(!eglChooseConfig(display,attrs,&config,1,&count)||!count)fail("config");
    EGLContext context=eglCreateContext(display,config,EGL_NO_CONTEXT,(EGLint[]){EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE});
    EGLSurface surface=eglCreatePbufferSurface(display,config,(EGLint[]){EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE});
    if(!eglMakeCurrent(display,surface,surface,context))fail("current");
    if(argc>6&&!strcmp(argv[6],"probe")){printf("%s\n",glGetString(GL_EXTENSIONS));return 0;}
    int addressOnly=argc>6&&!strcmp(argv[6],"address");
    int neutralFix=addressOnly||(argc>6&&!strcmp(argv[6],"fix"));
    int volume=neutralFix||(argc>6&&!strcmp(argv[6],"3d"));GLuint volumeTexture=0;
    PFNGLTEXIMAGE3DOESPROC image3d=(PFNGLTEXIMAGE3DOESPROC)eglGetProcAddress("glTexImage3DOES");
    unsigned char *atlas=malloc(512*512*4),*cube=NULL;
    if(volume){
        if(!strstr((const char*)glGetString(GL_EXTENSIONS),"GL_OES_texture_3D")||!image3d)fail("No 3D lookup support");
        glGenTextures(1,&volumeTexture);glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_3D_OES,volumeTexture);
        glTexParameteri(GL_TEXTURE_3D_OES,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_3D_OES,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D_OES,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_3D_OES,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D_OES,GL_TEXTURE_WRAP_R_OES,GL_CLAMP_TO_EDGE);
        cube=malloc(64*64*64*4);
        if(neutralFix){
            fixTextures[1]=volumeTexture;glGenTextures(1,&fixTextures[0]);
            glBindTexture(GL_TEXTURE_3D_OES,fixTextures[0]);
            glTexParameteri(GL_TEXTURE_3D_OES,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_3D_OES,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
            glTexParameteri(GL_TEXTURE_3D_OES,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_3D_OES,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_3D_OES,GL_TEXTURE_WRAP_R_OES,GL_CLAMP_TO_EDGE);
        }
    }
    GLuint textures[2],fbo;glGenTextures(2,textures);glGenFramebuffers(1,&fbo);glBindFramebuffer(GL_FRAMEBUFFER,fbo);
    for(int i=0;i<2;i++) {
        glActiveTexture(GL_TEXTURE0+i);glBindTexture(GL_TEXTURE_2D,textures[i]);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,i?512:width,i?512:height,0,GL_RGBA,GL_UNSIGNED_BYTE,NULL);
    }
    glDisable(GL_DITHER);glDisable(GL_BLEND);glDisable(GL_DEPTH_TEST);
    const GLfloat vertices[]={-1,-1,1,-1,-1,1,1,1};glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,0,vertices);glEnableVertexAttribArray(0);
    char *reference=load(argv[1],"after.glsl"),*combined=load(argv[1],neutralFix?"lookup-before.glsl":volume?"lookup-only.glsl":"lookup-combined.glsl"),*pure=load(argv[1],volume?"lookup-3d.glsl":"lookup-only.glsl");
    GLuint generate=generator(argv[1],reference),programs[]={program(combined,"ntvFastToSdr"),program(pure,"ntvFastToSdr")};
    GLuint formula=volume?program(reference,"ntvToSdr"):0;
    GLuint probes[2]={0,0};if(volume){probes[0]=probeProgram(combined);probes[1]=probeProgram(pure);}
    unsigned char *colors[2]={malloc(256*256*4),malloc(256*256*4)};
    printf("{\"renderer\":\"%s\",\"baseline\":\"%s\",\"candidate\":\"%s\",\"width\":%d,\"height\":%d,\"frames_per_batch\":%d,\"results\":[\n",glGetString(GL_RENDERER),addressOnly?"3D-original-address":neutralFix?"3D-before":volume?"2D":"combined",addressOnly?"3D-hardware-clamp":neutralFix?"3D-neutral-fix":volume?"3D":"2D",width,height,frames);fflush(stdout);
    for(int mode=0;mode<2;mode++) {
        int transfer=6+mode;
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,textures[1],0);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)fail("LUT FBO");
        glViewport(0,0,512,512);setup(generate,512,512,transfer,0);batch(1);
        glReadPixels(0,0,512,512,GL_RGBA,GL_UNSIGNED_BYTE,atlas);
        if(addressOnly&&transfer==6)repairNeutralAxis(atlas);
        if(neutralFix){
            for(int z=0;z<64;z++)for(int y=0;y<64;y++)memcpy(cube+(z*64+y)*64*4,atlas+(((z/8)*64+y)*512+(z%8)*64)*4,64*4);
            bindFixTexture(0);glActiveTexture(GL_TEXTURE1);
            if(!ntv_hdr_upload_lut3d(cube,64*64*64*4))fail("Baseline volume upload");
        }
        if(transfer==6){
            repairNeutralAxis(atlas);
            glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,textures[1]);
            glTexSubImage2D(GL_TEXTURE_2D,0,0,0,512,512,GL_RGBA,GL_UNSIGNED_BYTE,atlas);
        }
        if(volume){
            for(int z=0;z<64;z++)for(int y=0;y<64;y++)memcpy(cube+(z*64+y)*64*4,atlas+(((z/8)*64+y)*512+(z%8)*64)*4,64*4);
            glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_3D_OES,volumeTexture);
            if(!ntv_hdr_upload_lut3d(cube,64*64*64*4))fail("Production 3D upload");
            if(glGetError()!=GL_NO_ERROR)fail("3D upload");
        }
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,textures[0],0);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)fail("output FBO");
        for(int v=0;v<2;v++) {
            setup(programs[v],256,256,transfer,0);
            bindFixTexture(v);
            glUniform1i(glGetUniformLocation(programs[v],"ntvLut"),1);
            glUniform1f(glGetUniformLocation(programs[v],"ntvUseLut"),1);
            glViewport(0,0,256,256);batch(1);glReadPixels(0,0,256,256,GL_RGBA,GL_UNSIGNED_BYTE,colors[v]);
        }
        int maxError=0;for(int i=0;i<256*256*4;i++){int delta=abs(colors[0][i]-colors[1][i]);if(delta>maxError)maxError=delta;}
        if(addressOnly&&maxError>1)fail("addressing colour regression");
        if(!neutralFix&&maxError>(volume?2:0)){
            for(int i=0;i<256*256*4;i++)if(abs(colors[0][i]-colors[1][i])==maxError){
                fprintf(stderr,"colour delta=%d offset=%d baseline=%d candidate=%d first=%d/%d\n",maxError,i,colors[0][i],colors[1][i],colors[0][1024],colors[1][1024]);break;
            }
            fail("new LUT differs from baseline LUT");
        }
        double referenceMean[2]={0,0};int referenceMax[2]={0,0};
        if(volume){
            unsigned char *expected=malloc(256*256*4);
            setup(formula,256,256,transfer,0);batch(1);glReadPixels(0,0,256,256,GL_RGBA,GL_UNSIGNED_BYTE,expected);
            for(int v=0;v<2;v++)for(int i=0;i<256*256*4;i++)if(i%4!=3){int delta=abs(colors[v][i]-expected[i]);referenceMean[v]+=delta;if(delta>referenceMax[v])referenceMax[v]=delta;}
            for(int v=0;v<2;v++)referenceMean[v]/=256*256*3;
            free(expected);
            if(referenceMean[1]>referenceMean[0]+.05||referenceMax[1]>referenceMax[0]+1){
                fprintf(stderr,"Reference errors mean %.6f/%.6f max %d/%d\n",referenceMean[0],referenceMean[1],referenceMax[0],referenceMax[1]);
                fail("3D worsens reference formula accuracy");
            }
            glViewport(0,0,1,1);
            const GLfloat primaries[][3]={{.70853657,.26654934,.12982403},{.52480767,.74436791,.27190927},{.23091570,.11827064,.81335211},{.75,.75,.75},{.58068888,.58068888,.58068888}};
            for(int sample=0;sample<70;sample++){
                GLfloat value=sample/64.f,color[3]={value,value,value};
                if(sample>=65)memcpy(color,primaries[sample-65],sizeof(color));
                unsigned char pixel[2][4];
                for(int v=0;v<2;v++){
                    setup(probes[v],1,1,transfer,0);glUniform1i(glGetUniformLocation(probes[v],"ntvLut"),1);
                    bindFixTexture(v);
                    glUniform3fv(glGetUniformLocation(probes[v],"probe"),1,color);glDrawArrays(GL_TRIANGLE_STRIP,0,4);
                    glReadPixels(0,0,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel[v]);
                }
                for(int channel=0;channel<3;channel++)if(!neutralFix&&abs(pixel[0][channel]-pixel[1][channel])>2)fail("Reference colour regression");
                if(sample<=64){
                    int spread[2];for(int v=0;v<2;v++){
                        int low=pixel[v][0],high=low;for(int channel=1;channel<3;channel++){if(pixel[v][channel]<low)low=pixel[v][channel];if(pixel[v][channel]>high)high=pixel[v][channel];}spread[v]=high-low;
                    }
                    if(spread[1]>spread[0]+1)fail("Grey neutrality regression");
                }
            }
        }
        glViewport(0,0,width,height);
        for(int v=0;v<2;v++){setup(programs[v],width,height,transfer,0);bindFixTexture(v);batch(4);}
        double before[rounds],after[rounds];
        for(int r=0;r<rounds;r++)for(int step=0;step<2;step++) {
            int v=(r+step)&1;setup(programs[v],width,height,transfer,0);bindFixTexture(v);double ms=batch(frames);
            if(v)after[r]=ms;else before[r]=ms;
        }
        printf("%s{\"mode\":\"%s\",\"max_colour_difference\":%d,\"reference_mean\":[%.6f,%.6f],\"reference_max\":[%d,%d],\"combined_ms\":[",mode?",\n":"",mode?"HLG":"PQ",maxError,referenceMean[0],referenceMean[1],referenceMax[0],referenceMax[1]);
        for(int i=0;i<rounds;i++)printf("%s%.6f",i?",":"",before[i]);printf("],\"pure_ms\":[");
        for(int i=0;i<rounds;i++)printf("%s%.6f",i?",":"",after[i]);printf("]}");fflush(stdout);
    }
    printf("\n],\"playback_test\":false,\"lut_generation_in_timing\":false}\n");
    free(reference);free(combined);free(pure);free(colors[0]);free(colors[1]);free(atlas);free(cube);
    if(fixTextures[0])glDeleteTextures(1,&fixTextures[0]);
    if(volumeTexture)glDeleteTextures(1,&volumeTexture);
    glDeleteProgram(generate);glDeleteProgram(programs[0]);glDeleteProgram(programs[1]);
    if(formula)glDeleteProgram(formula);
    for(int i=0;i<2;i++)if(probes[i])glDeleteProgram(probes[i]);
    glDeleteFramebuffers(1,&fbo);glDeleteTextures(2,textures);
    eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);eglDestroySurface(display,surface);eglDestroyContext(display,context);eglTerminate(display);return 0;
}
