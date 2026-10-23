/* LGPL-2.1-or-later. Optional one-time 3D LUT upload for the hardware bridge. */
#ifndef NTV_HDR_LUT_H
#define NTV_HDR_LUT_H
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <stddef.h>

static int ntv_hdr_upload_lut3d_size(const void *pixels,size_t size,int edge)
{
    if((edge!=64&&edge!=128)||!pixels||size<(size_t)edge*edge*edge*4u)return 0;
    PFNGLTEXIMAGE3DOESPROC upload=(PFNGLTEXIMAGE3DOESPROC)eglGetProcAddress("glTexImage3DOES");
    if(!upload)upload=(PFNGLTEXIMAGE3DOESPROC)eglGetProcAddress("glTexImage3D");
    if(!upload)return 0;
    GLint maximum=0;glGetIntegerv(GL_MAX_3D_TEXTURE_SIZE_OES,&maximum);
    if(glGetError()!=GL_NO_ERROR||maximum<edge)return 0;
    upload(GL_TEXTURE_3D_OES,0,GL_RGBA,edge,edge,edge,0,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
    return glGetError()==GL_NO_ERROR;
}
static int ntv_hdr_upload_lut3d(const void *pixels,size_t size){return ntv_hdr_upload_lut3d_size(pixels,size,64);}
#endif
