/* Compare LUT capacity, colour error and real GLES mapping cost. No decoder. */
#define main ntv_formula_bench_main
#include "bench-hdr-algorithm.c"
#undef main
#include <GLES2/gl2ext.h>

static int columns(int n){return n>128?16:8;}
static int offset(int n,int r,int g,int b){int cols=columns(n);return (((b/cols)*n+g)*(cols*n)+(b%cols)*n+r)*4;}
static void repair(unsigned char *atlas,int n){
    for(int k=0;k<n-1;k++)for(int group=1;group<=2;group++){
        int base=group==1?k:k+1,d=group==1?1:-1;
        int ids[]={offset(n,base+d,base,base),offset(n,base,base+d,base),offset(n,base,base,base+d)};
        for(int c=0;c<3;c++){
            int old[3],v[3],left=atlas[offset(n,k,k,k)+c]*(3-group)+atlas[offset(n,k+1,k+1,k+1)+c]*group;
            for(int j=0;j<3;j++){old[j]=v[j]=atlas[ids[j]+c];left-=v[j];}
            int sign=left>0?1:-1;
            while(left){
                int best=-1;for(int j=0;j<3;j++)if(v[j]+sign>=0&&v[j]+sign<=255&&(best<0||abs(v[j]-old[j])<abs(v[best]-old[best])))best=j;
                if(best<0)fail("repair bounds");v[best]+=sign;left-=sign;
            }
            for(int j=0;j<3;j++)atlas[ids[j]+c]=v[j];
        }
    }
}
static char *lookup(int n){
    char *s=calloc(512,1);
    sprintf(s,"uniform highp sampler3D ntvLut;vec3 ntvFastToSdr(vec3 c){return texture3D(ntvLut,(clamp(c,0.,1.)*%d.+.5)/%d.).rgb;}",n-1,n);return s;
}
static const char *addressNames[]={"original","fused","hardware-clamp","gbr","brg","rbg"};
static const int axisOrder[6][3]={{0,1,2},{0,1,2},{0,1,2},{1,2,0},{2,0,1},{0,2,1}};
static char *addressLookup(int n,int variant){
    if(!variant)return lookup(n);
    char *s=calloc(512,1);const char *swizzle=variant==3?"gbr":variant==4?"brg":variant==5?"rbg":"rgb";
    sprintf(s,"uniform highp sampler3D ntvLut;vec3 ntvFastToSdr(vec3 c){return texture3D(ntvLut,%s.%s*%.12f+%.12f).rgb;}",variant==1?"clamp(c,0.,1.)":"c",swizzle,(n-1.)/n,.5/n);return s;
}
static const char *precisionNames[]={"current-highp","mediump-sampler","lowp-sampler","lowp-result","mediump-result","mediump-coordinates"};
static char *precisionLookup(int n,int variant){
    char *s=calloc(768,1);
    const char *sampler=variant==1?"mediump":variant==2?"lowp":"highp";
    const char *result=variant==3?"lowp":variant==4?"mediump":"highp";
    if(variant==5)sprintf(s,"uniform highp sampler3D ntvLut;vec3 ntvFastToSdr(vec3 c){mediump vec3 p=c*%.12f+%.12f;return texture3D(ntvLut,p).rgb;}",(n-1.)/n,.5/n);
    else sprintf(s,"uniform %s sampler3D ntvLut;%s vec3 ntvFastToSdr(vec3 c){return texture3D(ntvLut,c*%.12f+%.12f).rgb;}",sampler,result,(n-1.)/n,.5/n);
    return s;
}
static const char *centerNames[]={"current-64","center-64","center-96","center-128"};
static GLuint centeredGenerator(const char *reference,int n){
    char *s=calloc(strlen(reference)+1024,1);
    sprintf(s,"%s\nvec3 makeNode(vec3 unused){vec2 p=floor(gl_FragCoord.xy);float b=floor(p.x/%d.)+%d.*floor(p.y/%d.);vec3 i=vec3(mod(p.x,%d.),mod(p.y,%d.),b);vec3 c=(i+.5)/%d.;c=mix(c,vec3(0.),vec3(1.)-step(vec3(.5),i));c=mix(c,vec3(1.),step(vec3(%d.),i));return ntvToSdr(c);}",reference,n,columns(n),n,n,n,n,n-1);
    GLuint p=program(s,"makeNode");free(s);return p;
}
static GLuint generateProgram(const char *reference,int n){
    char *s=calloc(strlen(reference)+512,1);
    sprintf(s,"%s\nvec3 makeNode(vec3 unused){vec2 p=floor(gl_FragCoord.xy);float b=floor(p.x/%d.)+%d.*floor(p.y/%d.);return ntvToSdr(vec3(mod(p.x,%d.),mod(p.y,%d.),b)/%d.);}",reference,n,columns(n),n,n,n,n-1);
    GLuint p=program(s,"makeNode");free(s);return p;
}
static GLuint probeProgram(const char *s,int reference){
    char *text=calloc(strlen(s)+200,1);
    sprintf(text,"%s\nuniform vec3 probe;vec3 probeColor(vec3 unused){return %s(probe);}",s,reference?"ntvToSdr":"ntvFastToSdr");
    GLuint p=program(text,"probeColor");free(text);return p;
}
static void bindLut(GLuint p,GLuint texture,int w,int h,int transfer){
    setup(p,w,h,transfer,0);glUniform1i(glGetUniformLocation(p,"ntvLut"),1);
    glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_3D_OES,texture);glActiveTexture(GL_TEXTURE0);
}
int main(int argc,char **argv){
    if(argc<4)return 1;
    int w=atoi(argv[2]),h=atoi(argv[3]),rounds=argc>4?atoi(argv[4]):9,frames=argc>5?atoi(argv[5]):4;
    int precision=argc>6&&!strcmp(argv[6],"precision");
    int centered=argc>6&&!strcmp(argv[6],"center");
    int addressing=precision||(argc>6&&!strcmp(argv[6],"address")),addressSize=argc>7?atoi(argv[7]):64;
    if(addressing&&addressSize!=64&&addressSize!=128)fail("address size");
    if(w<256||h<256||rounds<3||frames<1)fail("arguments");
    EGLDisplay display=eglGetDisplay(EGL_DEFAULT_DISPLAY);if(!eglInitialize(display,NULL,NULL))fail("init");
    EGLConfig config;EGLint count;
    EGLint attrs[]={EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,EGL_NONE};
    if(!eglChooseConfig(display,attrs,&config,1,&count)||!count)fail("config");
    EGLContext ctx=eglCreateContext(display,config,EGL_NO_CONTEXT,(EGLint[]){EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE});
    EGLSurface surface=eglCreatePbufferSurface(display,config,(EGLint[]){EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE});
    if(!eglMakeCurrent(display,surface,surface,ctx))fail("current");
    if(!strstr((const char*)glGetString(GL_EXTENSIONS),"GL_OES_texture_3D"))fail("3D unavailable");
    PFNGLTEXIMAGE3DOESPROC image3d=(PFNGLTEXIMAGE3DOESPROC)eglGetProcAddress("glTexImage3DOES");if(!image3d)fail("3D upload");
    GLint max3d,max2d;glGetIntegerv(GL_MAX_3D_TEXTURE_SIZE_OES,&max3d);glGetIntegerv(GL_MAX_TEXTURE_SIZE,&max2d);
    if(max3d<192||max2d<3072||max2d<w||max2d<h)fail("texture limits");
    glDisable(GL_DITHER);glDisable(GL_BLEND);glDisable(GL_DEPTH_TEST);
    GLfloat vertices[]={-1,-1,1,-1,-1,1,1,1};glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,0,vertices);glEnableVertexAttribArray(0);
    GLuint output,fbo;glGenTextures(1,&output);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,output);
    glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,NULL);
    glGenFramebuffers(1,&fbo);glBindFramebuffer(GL_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,output,0);
    if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)fail("output FBO");
    char *ref=load(argv[1],"after.glsl");GLuint formula=program(ref,"ntvToSdr"),refProbe=probeProgram(ref,1);
    enum {maximumVariants=6};int variants=addressing?6:4;
    int sizes[]={64,96,128,192,64,64};if(addressing)for(int i=0;i<variants;i++)sizes[i]=addressSize;
    if(centered){sizes[0]=64;sizes[1]=64;sizes[2]=96;sizes[3]=128;}
    GLuint textures[maximumVariants],kernels[maximumVariants],probes[maximumVariants];
    glGenTextures(variants,textures);
    for(int i=0;i<variants;i++){
        char *s=centered?precisionLookup(sizes[i],0):precision?precisionLookup(sizes[i],i):addressing?addressLookup(sizes[i],i):lookup(sizes[i]);
        if(centered&&i){free(s);s=strdup("uniform highp sampler3D ntvLut;vec3 ntvFastToSdr(vec3 c){return texture3D(ntvLut,c).rgb;}");}
        kernels[i]=program(s,"ntvFastToSdr");probes[i]=probeProgram(s,0);free(s);
        glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_3D_OES,textures[i]);
        glTexParameteri(GL_TEXTURE_3D_OES,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_3D_OES,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D_OES,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_3D_OES,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_3D_OES,GL_TEXTURE_WRAP_R_OES,GL_CLAMP_TO_EDGE);
    }
    printf("{\"renderer\":\"%s\",\"width\":%d,\"height\":%d,\"frames\":%d,\"cases\":[",glGetString(GL_RENDERER),w,h,frames);fflush(stdout);
    for(int mode=0;mode<2;mode++){
        int transfer=6+mode;unsigned char expected[256*256*4],actual[256*256*4],baseline[256*256*4],baselineProbe[76][4];
        glViewport(0,0,256,256);setup(formula,256,256,transfer,0);batch(1);glReadPixels(0,0,256,256,GL_RGBA,GL_UNSIGNED_BYTE,expected);
        double means[maximumVariants]={0},nearMeans[maximumVariants]={0};int maximum[maximumVariants]={0},nearMax[maximumVariants]={0},greyMax[maximumVariants]={0},nodeMax[maximumVariants]={0},addressMax[maximumVariants]={0};
        for(int i=0;i<variants;i++){
            int n=sizes[i],aw=n*columns(n),ah=n*(n/columns(n));size_t bytes=(size_t)n*n*n*4;
            unsigned char *atlas=malloc(bytes),*cube=malloc(bytes);if(!atlas||!cube)fail("allocation");
            GLuint scratch,gen=centered&&i?centeredGenerator(ref,n):generateProgram(ref,n);glGenTextures(1,&scratch);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,scratch);
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,aw,ah,0,GL_RGBA,GL_UNSIGNED_BYTE,NULL);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,scratch,0);
            if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)fail("generation FBO");
            glViewport(0,0,aw,ah);setup(gen,aw,ah,transfer,0);batch(1);glReadPixels(0,0,aw,ah,GL_RGBA,GL_UNSIGNED_BYTE,atlas);
            // Distinguish interpolation error from large-atlas generation
            // precision on legacy drivers. The CPU copy is already captured.
            glViewport(0,0,1,1);
            for(int j=0;j<64;j++){
                int r=(j*53+13)%n,g=(j*71+17)%n,b=(j*29+19)%n;
                GLfloat color[]={r/(float)(n-1),g/(float)(n-1),b/(float)(n-1)};unsigned char pixel[4];
                if(centered&&i){int indices[]={r,g,b};for(int c=0;c<3;c++)color[c]=indices[c]==0?0:indices[c]==n-1?1:(indices[c]+.5f)/n;}
                setup(refProbe,1,1,transfer,0);glUniform3fv(glGetUniformLocation(refProbe,"probe"),1,color);batch(1);glReadPixels(0,0,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
                for(int c=0;c<3;c++){int delta=abs(atlas[offset(n,r,g,b)+c]-pixel[c]);if(delta>nodeMax[i])nodeMax[i]=delta;}
            }
            if(transfer==6)repair(atlas,n);
            for(int z=0;z<n;z++)for(int y=0;y<n;y++)memcpy(cube+((size_t)z*n+y)*n*4,atlas+offset(n,0,y,z),n*4);
            if(addressing&&!precision&&i>=3)for(int b=0;b<n;b++)for(int g=0;g<n;g++)for(int r=0;r<n;r++){
                int c[]={r,g,b};size_t target=((size_t)c[axisOrder[i][2]]*n+c[axisOrder[i][1]])*n+c[axisOrder[i][0]];
                memcpy(cube+target*4,atlas+offset(n,r,g,b),4);
            }
            glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_3D_OES,textures[i]);image3d(GL_TEXTURE_3D_OES,0,GL_RGBA,n,n,n,0,GL_RGBA,GL_UNSIGNED_BYTE,cube);
            if(glGetError()!=GL_NO_ERROR)fail("upload");
            free(atlas);free(cube);glDeleteProgram(gen);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,output,0);glDeleteTextures(1,&scratch);
            glViewport(0,0,256,256);bindLut(kernels[i],textures[i],256,256,transfer);batch(1);glReadPixels(0,0,256,256,GL_RGBA,GL_UNSIGNED_BYTE,actual);
            if(!i)memcpy(baseline,actual,sizeof baseline);
            if(addressing||centered)for(int j=0;j<256*256*4;j++)if(j%4!=3){int delta=abs(baseline[j]-actual[j]);if(delta>addressMax[i])addressMax[i]=delta;}
            for(int j=0;j<256*256*4;j++)if(j%4!=3){int d=abs(expected[j]-actual[j]);means[i]+=d;if(d>maximum[i])maximum[i]=d;}means[i]/=256*256*3;
            glViewport(0,0,1,1);
            for(int sample=0;sample<(addressing||centered?76:72);sample++){
                GLfloat color[3];if(sample<24)for(int c=0;c<3;c++)color[c]=sample/23.f;
                else if(sample>=72){const GLfloat edge[4][3]={{-.2f,.5f,1.2f},{1.3f,-.1f,.8f},{0,1,0},{1,0,1}};memcpy(color,edge[sample-72],sizeof color);}
                else{int level=(sample-24)/6,kind=(sample-24)%6;for(int c=0;c<3;c++)color[c]=.5f+.05f*level;color[kind%3]+=kind<3?.006f:-.006f;}
                unsigned char a[4],b[4];setup(refProbe,1,1,transfer,0);glUniform3fv(glGetUniformLocation(refProbe,"probe"),1,color);batch(1);glReadPixels(0,0,1,1,GL_RGBA,GL_UNSIGNED_BYTE,a);
                bindLut(probes[i],textures[i],1,1,transfer);glUniform3fv(glGetUniformLocation(probes[i],"probe"),1,color);batch(1);glReadPixels(0,0,1,1,GL_RGBA,GL_UNSIGNED_BYTE,b);
                if(!i)memcpy(baselineProbe[sample],b,4);
                for(int c=0;c<3;c++){
                    int d=abs(a[c]-b[c]);if(sample<24){if(d>greyMax[i])greyMax[i]=d;}else if(sample<72){nearMeans[i]+=d;if(d>nearMax[i])nearMax[i]=d;}
                    if(addressing||centered){int delta=abs(baselineProbe[sample][c]-b[c]);if(delta>addressMax[i])addressMax[i]=delta;}
                }
            }
            nearMeans[i]/=48*3;
            if(addressing&&!precision&&addressMax[i]>2)fail("addressing changes colours");
        }
        glViewport(0,0,w,h);for(int i=0;i<variants;i++){bindLut(kernels[i],textures[i],w,h,transfer);batch(8);}
        double times[variants][rounds];for(int r=0;r<rounds;r++)for(int step=0;step<variants;step++){
            int i=(r+step)%variants;bindLut(kernels[i],textures[i],w,h,transfer);times[i][r]=batch(frames);
        }
        for(int i=0;i<variants;i++){
            printf("%s{\"transfer\":%d,\"size\":%d,\"address\":\"%s\",\"address_max\":%d,\"bytes\":%d,\"mean_codes\":%.6f,\"max_codes\":%d,\"grey_max\":%d,\"node_max\":%d,\"near_mean\":%.6f,\"near_max\":%d,\"ms\":[",mode||i?",":"",transfer,sizes[i],centered?centerNames[i]:precision?precisionNames[i]:addressing?addressNames[i]:"original",addressMax[i],sizes[i]*sizes[i]*sizes[i]*4,means[i],maximum[i],greyMax[i],nodeMax[i],nearMeans[i],nearMax[i]);
            for(int r=0;r<rounds;r++)printf("%s%.6f",r?",":"",times[i][r]);printf("]}");fflush(stdout);
        }
    }
    printf("],\"playback_test\":false,\"generation_timed\":false}\n");
    glDeleteTextures(variants,textures);glDeleteTextures(1,&output);glDeleteFramebuffers(1,&fbo);
    for(int i=0;i<variants;i++){glDeleteProgram(kernels[i]);glDeleteProgram(probes[i]);}glDeleteProgram(formula);glDeleteProgram(refProbe);free(ref);
    eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);eglDestroySurface(display,surface);eglDestroyContext(display,ctx);eglTerminate(display);return 0;
}
