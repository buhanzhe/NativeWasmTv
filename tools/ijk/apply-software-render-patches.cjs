// LGPL-2.1-or-later. Keep software 10-bit frames on the YUV GPU path.
const fs = require('fs'), path = require('path');
function apply(root) {
    function edit(file, before, after, count = 1) {
        const target = path.join(root, file);
        const text = fs.readFileSync(target, 'utf8').replace(/\r\n/g, '\n');
        if (text.includes(after)) return;
        if (text.split(before).length - 1 !== count)
            throw Error('Unexpected software render hook: ' + file);
        fs.writeFileSync(target, text.split(before).join(after));
    }
    const sdl = 'ijkmedia/ijksdl/';
    edit(sdl + 'ijksdl_fourcc.h', '#define SDL_FCC_I444P10LE',
        "#define SDL_FCC_I420P10LE SDL_FOURCC('I', '0', '1', '0')\n#define SDL_FCC_I444P10LE");
    const overlay = sdl + 'ffmpeg/ijksdl_vout_overlay_ffmpeg.c';
    edit(overlay, '        case SDL_FCC_I444P10LE:\n            if (frame->format == AV_PIX_FMT_YUV444P10LE)',
        `        case SDL_FCC_I420P10LE:
            if (frame->format != AV_PIX_FMT_YUV420P10LE) return -1;
            use_linked_frame = 1;
            dst_format = frame->format;
            break;
        case SDL_FCC_I444P10LE:
            if (frame->format == AV_PIX_FMT_YUV444P10LE)`);
    edit(overlay, '                case AV_PIX_FMT_YUV444P10LE:',
        `                case AV_PIX_FMT_YUV420P10LE:
                    overlay_format = SDL_FCC_I420P10LE;
                    break;
                case AV_PIX_FMT_YUV444P10LE:`);
    edit(overlay, '    case SDL_FCC_I444P10LE: {',
        `    case SDL_FCC_I420P10LE: {
        ff_format = AV_PIX_FMT_YUV420P10LE;
        opaque->planes = 3;
        break;
    }
    case SDL_FCC_I444P10LE: {`);
    edit(sdl + 'gles2/renderer.c', '        case SDL_FCC_I444P10LE: renderer =',
        '        case SDL_FCC_I420P10LE:\n        case SDL_FCC_I444P10LE: renderer =');
    edit(sdl + 'android/ijksdl_vout_android_nativewindow.c', '    case SDL_FCC_I444P10LE: {',
        '    case SDL_FCC_I420P10LE:\n    case SDL_FCC_I444P10LE: {');
    edit(sdl + 'gles2/ntv_hdr_render.h', 'int ten = overlay->format == SDL_FCC_I444P10LE;',
        'int ten = overlay->format == SDL_FCC_I444P10LE || overlay->format == SDL_FCC_I420P10LE;');
    edit(sdl + 'gles2/renderer_yuv444p10le.c',
        '    const GLsizei heights[3]   = { overlay->h,              overlay->h,              overlay->h };',
        `    const GLsizei chroma_height = overlay->format == SDL_FCC_I420P10LE
            ? (overlay->h + 1) / 2 : overlay->h;
    const GLsizei heights[3]   = { overlay->h, chroma_height, chroma_height };`);
    edit(sdl + 'gles2/renderer_yuv444p10le.c', '        case SDL_FCC_I444P10LE:',
        '        case SDL_FCC_I420P10LE:\n        case SDL_FCC_I444P10LE:');
    edit(sdl + 'gles2/internal.h', '    GLint ntv_transfer, ntv_peak, ntv_yuv_offset;',
        '    GLint ntv_chroma_scale;\n    GLint ntv_transfer, ntv_peak, ntv_yuv_offset;');
    edit(sdl + 'gles2/renderer_yuv444p10le.c', '    ntv_hdr_render_uniforms(renderer,overlay);',
        `    ntv_hdr_render_uniforms(renderer,overlay);
    float chroma_width = overlay->format == SDL_FCC_I420P10LE ? (overlay->w + 1) / 2 : overlay->w;
    glUniform2f(renderer->ntv_chroma_scale,
            chroma_width * widths[0] / (widths[1] * (float)overlay->w),
            chroma_width * widths[0] / (widths[2] * (float)overlay->w));`);
    edit(sdl + 'gles2/renderer_yuv444p10le.c', '    renderer->us2_sampler[0] =',
        '    renderer->ntv_chroma_scale = glGetUniformLocation(renderer->program, "ntvChromaScale");\n    renderer->us2_sampler[0] =');
    const shader = sdl + 'gles2/fsh/yuv444p10le.fsh.c';
    edit(shader, '    uniform vec3 ntvYuvOffset;', '    uniform vec3 ntvYuvOffset;\n    uniform vec2 ntvChromaScale;');
    edit(shader, 'texture2D(us2_SamplerY, vv2_Texcoord)',
        'texture2D(us2_SamplerY, vv2_Texcoord * vec2(ntvChromaScale.x, 1.0))', 2);
    edit(shader, 'texture2D(us2_SamplerZ, vv2_Texcoord)',
        'texture2D(us2_SamplerZ, vv2_Texcoord * vec2(ntvChromaScale.y, 1.0))', 2);
    edit(shader, 'yuv = (yuv_l * 255.0 + yuv_h * 255.0 * 256.0) / (1023.0) - vec3(16.0 / 255.0, 0.5, 0.5);',
        'yuv = (yuv_l * 255.0 + yuv_h * 255.0 * 256.0) / (1023.0) - ntvYuvOffset;');

    // Allocate plane storage only at creation/size changes. Decoder pitches can
    // change independently of visible size, so cache the actual upload dimensions.
    edit(sdl + 'gles2/internal.h', '    GLuint plane_textures[IJK_GLES2_MAX_PLANE];',
        '    GLuint plane_textures[IJK_GLES2_MAX_PLANE];\n    GLsizei ntv_plane_width[IJK_GLES2_MAX_PLANE], ntv_plane_height[IJK_GLES2_MAX_PLANE];');
    for (const [file, format] of [['renderer_yuv420p.c', 'GL_LUMINANCE'],
            ['renderer_yuv444p10le.c', 'GL_LUMINANCE_ALPHA']]) {
        const begin = `        glTexImage2D(GL_TEXTURE_2D,
                     0,
                     ${format},
                     widths[plane],
                     heights[plane],
                     0,
                     ${format},
                     GL_UNSIGNED_BYTE,
                     pixels[plane]);`;
        edit(sdl + 'gles2/' + file, begin,
            `        if (renderer->ntv_plane_width[i] != widths[plane]
                || renderer->ntv_plane_height[i] != heights[plane]) {
            glTexImage2D(GL_TEXTURE_2D, 0, ${format}, widths[plane], heights[plane],
                    0, ${format}, GL_UNSIGNED_BYTE, pixels[plane]);
            renderer->ntv_plane_width[i] = widths[plane];
            renderer->ntv_plane_height[i] = heights[plane];
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, widths[plane], heights[plane],
                    ${format}, GL_UNSIGNED_BYTE, pixels[plane]);
        }`);
    }
    const player = 'ijkmedia/ijkplayer/ff_ffplay.c';
    const target = path.join(root, player);
    const text = fs.readFileSync(target, 'utf8').replace(/\r\n/g, '\n');
    if (!text.includes('/* nTv: use picture rate, not a second field-rate limit. */')) {
        const begin = text.indexOf('        if (ffp->max_fps >= 0) {');
        const end = text.indexOf('\n        if (is->is_video_high_fps)', begin);
        if (begin < 0 || end < 0 || !text.slice(begin, end).includes('double tbr ='))
            throw Error('Unexpected software FPS limit');
        const replacement = `        /* nTv: use picture rate, not a second field-rate limit. */
        is->is_video_high_fps = 0;
        if (ffp->max_fps >= 0) {
            AVRational rate = is->video_st->avg_frame_rate;
            if (rate.num <= 0 || rate.den <= 0) rate = is->video_st->r_frame_rate;
            if (rate.num > 0 && rate.den > 0) {
                double fps = av_q2d(rate);
                SDL_ProfilerReset(&is->viddec.decode_profiler, fps + 0.5);
                is->is_video_high_fps = fps > ffp->max_fps && fps < 130.0;
                av_log(ffp, AV_LOG_INFO, "nTv: picture fps %.3f, decode limit %d, high=%d\\n",
                        fps, ffp->max_fps, is->is_video_high_fps);
            }
        }
`;
        fs.writeFileSync(target, text.slice(0, begin) + replacement + text.slice(end));
    }
}
module.exports = apply;
if (require.main === module) apply(process.argv[2]);
