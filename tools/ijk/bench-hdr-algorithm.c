/* Isolated GLES2 mapping benchmark. No Activity, MediaCodec or playback. */
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static void fail(const char *message) { fprintf(stderr,"FAIL %s EGL=%x GL=%x\n",message,eglGetError(),glGetError()); exit(1); }
static double now(void) { struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec+t.tv_nsec*1e-9; }
static char *load(const char *directory,const char *name) {
    char path[1024];snprintf(path,sizeof(path),"%s/%s",directory,name);
    FILE *f=fopen(path,"rb");if(!f)fail(path);
    fseek(f,0,SEEK_END);long size=ftell(f);rewind(f);
    if(size<1||size>65536)fail("shader file size");
    char *data=calloc(size+1,1);if(!data||fread(data,1,size,f)!=(size_t)size)fail("shader read");fclose(f);return data;
}
static GLuint shader(GLenum type,const char *text) {
    GLuint id=glCreateShader(type);glShaderSource(id,1,&text,NULL);glCompileShader(id);
    GLint valid;glGetShaderiv(id,GL_COMPILE_STATUS,&valid);
    if(!valid){char log[8192];glGetShaderInfoLog(id,sizeof(log),NULL,log);fail(log);}return id;
}
static GLuint program(const char *tone,const char *entry) {
    const char *vertex="attribute vec2 pos;void main(){gl_Position=vec4(pos,0.,1.);}";
    char *fragment=calloc(strlen(tone)+2048,1);
    sprintf(fragment,"%sprecision highp float;uniform vec2 size;\n%s\n"
            "void main(){vec2 p=gl_FragCoord.xy/size;"
            "vec3 c=vec3(p.x,p.y,fract(p.x*3.0+p.y*2.0));"
            "gl_FragColor=vec4(%s(c),1.);}",strstr(tone,"sampler3D")?"#extension GL_OES_texture_3D : require\n":"",tone,entry);
    GLuint vs=shader(GL_VERTEX_SHADER,vertex),fs=shader(GL_FRAGMENT_SHADER,fragment),id=glCreateProgram();
    glAttachShader(id,vs);glAttachShader(id,fs);glBindAttribLocation(id,0,"pos");glLinkProgram(id);
    GLint valid;glGetProgramiv(id,GL_LINK_STATUS,&valid);
    if(!valid){char log[8192];glGetProgramInfoLog(id,sizeof(log),NULL,log);fail(log);}
    glDeleteShader(vs);glDeleteShader(fs);free(fragment);return id;
}
static void setup(GLuint p,int width,int height,int transfer,int standard) {
    glUseProgram(p);glUniform2f(glGetUniformLocation(p,"size"),width,height);
    glUniform1f(glGetUniformLocation(p,"ntvTransfer"),transfer);
    glUniform1f(glGetUniformLocation(p,"ntvPeak"),1000);
    glUniform1f(glGetUniformLocation(p,"ntvBt709Source"),standard);
    // Same BT.2020 RGB -> YUV -> BT.709 transform as production's CPU setup.
    const double kr=.2627,kb=.0593,luma[3]={.2627,.678,.0593};GLfloat matrix[9];
    for(int col=0;col<3;col++) {
        double y=luma[col],u=((col==2?1:0)-y)/(2*(1-kb)),v=((col==0?1:0)-y)/(2*(1-kr));
        double r=y+1.5748*v,b=y+1.8556*u;
        matrix[col*3]=r;matrix[col*3+1]=(y-.2126*r-.0722*b)/.7152;matrix[col*3+2]=b;
    }
    glUniformMatrix3fv(glGetUniformLocation(p,"ntvBt709Matrix"),1,GL_FALSE,matrix);
}
static double batch(int frames) {
    glFinish();double start=now();
    for(int i=0;i<frames;i++)glDrawArrays(GL_TRIANGLE_STRIP,0,4);
    glFinish();double elapsed=(now()-start)*1000/frames;
    if(glGetError()!=GL_NO_ERROR)fail("benchmark draw");return elapsed;
}
int main(int argc,char **argv) {
    if(argc<4){fprintf(stderr,"usage: bench shader-directory width height [rounds=7] [frames=4]\n");return 1;}
    int width=atoi(argv[2]),height=atoi(argv[3]),rounds=argc>4?atoi(argv[4]):7,frames=argc>5?atoi(argv[5]):4;
    if(width<=0||height<=0||rounds<3||frames<1)fail("arguments");
    EGLDisplay display=eglGetDisplay(EGL_DEFAULT_DISPLAY);if(!eglInitialize(display,NULL,NULL))fail("EGL init");
    EGLint attrs[]={EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,
        EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,EGL_NONE};
    EGLConfig config;EGLint count;if(!eglChooseConfig(display,attrs,&config,1,&count)||!count)fail("config");
    EGLint contextAttrs[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE},surfaceAttrs[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};
    EGLContext context=eglCreateContext(display,config,EGL_NO_CONTEXT,contextAttrs);
    EGLSurface surface=eglCreatePbufferSurface(display,config,surfaceAttrs);
    if(!eglMakeCurrent(display,surface,surface,context))fail("make current");
    GLint maxTexture;glGetIntegerv(GL_MAX_TEXTURE_SIZE,&maxTexture);if(width>maxTexture||height>maxTexture)fail("texture limit");
    GLuint texture,fbo;glGenTextures(1,&texture);glBindTexture(GL_TEXTURE_2D,texture);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
    glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,width,height,0,GL_RGBA,GL_UNSIGNED_BYTE,NULL);
    glGenFramebuffers(1,&fbo);glBindFramebuffer(GL_FRAMEBUFFER,fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);
    if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)fail("FBO");
    glViewport(0,0,width,height);glDisable(GL_DITHER);glDisable(GL_BLEND);glDisable(GL_DEPTH_TEST);
    const GLfloat vertices[]={-1,-1,1,-1,-1,1,1,1};
    glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,0,vertices);glEnableVertexAttribArray(0);
    char *old=load(argv[1],"before.glsl"),*fresh=load(argv[1],"after.glsl"),*tiny=load(argv[1],"bt709.glsl");
    GLuint programs[]={program(old,"ntvToSdr"),program(fresh,"ntvToSdr"),program(tiny,"ntvFastToSdr")};
    printf("{\"renderer\":\"%s\",\"version\":\"%s\",\"width\":%d,\"height\":%d,\"frames_per_batch\":%d,\"results\":[\n",
           glGetString(GL_RENDERER),glGetString(GL_VERSION),width,height,frames);fflush(stdout);
    const char *names[]={"PQ","HLG","BT709"};int transfers[]={6,7,0};
    for(int mode=0;mode<3;mode++) {
        GLuint pair[]={programs[0],programs[mode==2?2:1]};int standard=mode==2?6:0;
        for(int variant=0;variant<2;variant++){setup(pair[variant],width,height,transfers[mode],standard);batch(4);}
        printf("%s{\"mode\":\"%s\",\"before_ms\":[",mode?",\n":"",names[mode]);
        double before[rounds],after[rounds];
        for(int round=0;round<rounds;round++) {
            // AB/BA ordering prevents a consistent second-run/temperature advantage.
            for(int step=0;step<2;step++) {
                int variant=(round+step)&1;
                setup(pair[variant],width,height,transfers[mode],standard);
                double ms=batch(frames);if(variant)after[round]=ms;else before[round]=ms;
            }
        }
        for(int i=0;i<rounds;i++)printf("%s%.6f",i?",":"",before[i]);
        printf("],\"after_ms\":[");for(int i=0;i<rounds;i++)printf("%s%.6f",i?",":"",after[i]);printf("]}");fflush(stdout);
    }
    printf("\n],\"playback_test\":false}\n");
    for(int i=0;i<3;i++)glDeleteProgram(programs[i]);free(old);free(fresh);free(tiny);
    glDeleteFramebuffers(1,&fbo);glDeleteTextures(1,&texture);
    eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
    eglDestroySurface(display,surface);eglDestroyContext(display,context);eglTerminate(display);return 0;
}
