package xiao.bu.tv;

/** The decoder's Main10 support alone cannot establish an HDR display path. */
final class HdrDisplayPolicy {
    // Display.HdrCapabilities constants, kept here without an API 24 class dependency.
    private static final int HDR10 = 2, HLG = 3, HDR10_PLUS = 4;

    static boolean supportsTransfer(int transfer, int[] hdrTypes) {
        if (hdrTypes == null) return false;
        for (int type : hdrTypes) {
            if (transfer == 6 && (type == HDR10 || type == HDR10_PLUS)) return true;
            if (transfer == 7 && type == HLG) return true;
        }
        return false;
    }

    static boolean passthrough(int mode, int transfer, int[] hdrTypes, int sdk) {
        // IJK uses non-tunneled MediaCodec. Android 7 advertises the display's
        // HDR capabilities but only guarantees the tunneled HDR path. Sending
        // HLG directly can leave the TV in SDR mode and produce washed-out video.
        // Preserve hardware decoding, with our SDR bridge on these devices.
        return sdk >= 26 && mode == 1 && supportsTransfer(transfer, hdrTypes);
    }

    static boolean decoderToneMapping(int mode, int sdk) {
        return mode != 3 && sdk >= 31;
    }

    static boolean rawYuv(int sdk,String version,String extensions){
        return sdk>=29 && version!=null && (version.startsWith("OpenGL ES 3.")||version.startsWith("OpenGL ES 4."))
                && extensions!=null && (" "+extensions+" ").contains(" GL_EXT_YUV_target ");
    }

    static final String BT709_SHADER = "uniform mat3 ntvBt709Matrix;\n"
            + "vec3 ntvFastToSdr(vec3 encoded){return clamp(ntvBt709Matrix*encoded,0.0,1.0);}\n";

    /** Keep the same SDR gamut formula while allowing legacy GLSL compilers to
     * eliminate the unused PQ/HLG branches and their register allocation. */
    static String sdrShader(String reference, int transfer) {
        if (transfer != 1 && transfer != 3) throw new IllegalArgumentException("SDR transfer");
        return reference.replace("uniform float ntvTransfer;", "const float ntvTransfer = " + transfer + ".0;")
                .replace("uniform float ntvBt709Source;", "const float ntvBt709Source = 0.0;");
    }

    /** Undo the decoder RGB matrix and reinterpret YUV as 709, once per format.
     * Raw YUV has already used the 709 matrix, so it must not be converted twice. */
    static float[] bt709Reinterpretation(int standard, boolean rawYuv) {
        float[] matrix = new float[]{1,0,0,0,1,0,0,0,1};
        if (rawYuv || standard <= 1) return matrix;
        double kr = standard > 5 ? .2627 : .299;
        double kb = standard > 5 ? .0593 : .114;
        double[] luma = {kr,1-kr-kb,kb};
        for (int column=0;column<3;column++) {
            double y=luma[column];
            double u=((column==2?1:0)-y)/(2*(1-kb));
            double v=((column==0?1:0)-y)/(2*(1-kr));
            double r=y+1.5748*v, b=y+1.8556*u;
            matrix[column*3]=(float)r;
            matrix[column*3+1]=(float)((y-.2126*r-.0722*b)/.7152);
            matrix[column*3+2]=(float)b;
        }
        return matrix;
    }
    /** Column-major digital YUV -> encoded RGB plus digital offsets (8/10/12 bit). */
    static float[] yuvColor(int standard,int range,int depth){
        depth=depth==10||depth==12?depth:8;float shift=1<<(depth-8),max=(1<<depth)-1;
        float yScale=range==1?1:max/(219f*shift),cScale=range==1?1:max/(224f*shift);
        float kr=standard==6?.2627f:standard==1?.2126f:.299f,kb=standard==6?.0593f:standard==1?.0722f:.114f,kg=1-kr-kb;
        return new float[]{yScale,yScale,yScale,0,-2*kb*(1-kb)/kg*cScale,2*(1-kb)*cScale,
                2*(1-kr)*cScale,-2*kr*(1-kr)/kg*cScale,0,range==1?0:16*shift/max,128*shift/max,128*shift/max};
    }

    private HdrDisplayPolicy() {}
}
