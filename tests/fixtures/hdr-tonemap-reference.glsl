// Shared by the hardware SurfaceTexture path and IJK's software YUV renderer.
// Input: nonlinear BT.2020 RGB. Output: SDR BT.709 (BT.1886 display gamma).
uniform float ntvTransfer;
uniform float ntvPeak;
// Hardware BT.709 override: undo the decoder's reported YUV matrix in the
// encoded domain, then reinterpret the original YUV as BT.709 (no tone mapping).
// Native software output already uses BT.709 YUV directly; this uniform stays 0.
uniform float ntvBt709Source;
float ntvShoulder(float value, float peak) {
    // Mobius shoulder: unity below 55% SDR display light, continuous slope at
    // the knee and peak -> 1. Scale RGB together to preserve colour ratios.
    float knee = 0.55;
    if (value <= knee) return value;
    peak = max(peak, 1.0001);
    float a = -knee * knee * (peak - 1.0) / (knee * knee - 2.0 * knee + peak);
    float b = (knee * knee - 2.0 * knee * peak + peak) / (peak - 1.0);
    return min((b + knee) * (b + knee) / (b - a) * (value + a) / (value + b), 1.0);
}
vec3 ntvToSdr(vec3 encoded) {
    if (ntvBt709Source > 0.5) {
        if (ntvBt709Source < 1.5) return clamp(encoded, 0.0, 1.0);
        float kr = ntvBt709Source > 5.5 ? 0.2627 : 0.299;
        float kb = ntvBt709Source > 5.5 ? 0.0593 : 0.114;
        float y = dot(encoded, vec3(kr, 1.0 - kr - kb, kb));
        float u = (encoded.b - y) / (2.0 * (1.0 - kb));
        float v = (encoded.r - y) / (2.0 * (1.0 - kr));
        float r = y + 1.5748 * v;
        float b = y + 1.8556 * u;
        return clamp(vec3(r, (y - 0.2126 * r - 0.0722 * b) / 0.7152, b), 0.0, 1.0);
    }
    if (ntvTransfer < 0.5) return encoded;
    vec3 v = clamp(encoded, 0.0, 1.0);
    vec3 linear;
    float peak = 1.0;
    if (ntvTransfer < 5.5) {
        // SDR video is display-referred here. Using the camera inverse OETF
        // followed by sRGB lifted neutral greys. Matching display EOTF and
        // encoding leaves neutral BT.2020 SDR values unchanged.
        linear = ntvTransfer < 1.5 ? v : pow(v, vec3(2.4));
    } else if (ntvTransfer < 6.5) {
        vec3 p = pow(v, vec3(1.0 / 78.84375));
        linear = 10000.0 * pow(max(p - 0.8359375, 0.0)
                / max(18.8515625 - 18.6875 * p, 0.000001), vec3(1.0 / 0.1593017578125));
        // Match PQ to HLG at the 1000-nit bridge, then use the same SDR
        // viewing conditions as HLG below (291 nits, system gamma 1.03).
        float hdrLuma = max(dot(linear, vec3(0.2627, 0.6780, 0.0593)) / 1000.0, 0.000001);
        linear *= 0.00291 * pow(hdrLuma, 1.03 / 1.2 - 1.0);
        peak = 2.91 * pow(max(ntvPeak, 100.0) / 1000.0, 1.03 / 1.2);
    } else {
        vec3 low = v * v / 3.0;
        vec3 high = (exp((v - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;
        vec3 scene = mix(low, high, step(vec3(0.5), v));
        float sceneLuma = max(dot(scene, vec3(0.2627, 0.6780, 0.0593)), 0.000001);
        // BT.2446 method-B reference viewing conditions for SDR adaptation.
        // HLG is scene-relative: MaxCLL must not change its display gamma/white.
        linear = 2.91 * scene * pow(sceneLuma, 0.03);
        peak = 2.91;
    }
    // BT.2020 -> BT.709 in linear light, before SDR encoding.
    vec3 rgb = mat3(1.660491, -0.124550, -0.018151,
                    -0.587641, 1.132900, -0.100579,
                    -0.072850, -0.008349, 1.118730) * linear;
    // Gamut reduction: clip negative (unrepresentable) components, and compress
    // the largest positive component with one common RGB multiplier. Pulling
    // every clipped colour toward luma made bright reds/blues visibly grey.
    rgb = max(rgb, 0.0);
    float signal = max(max(rgb.r, rgb.g), rgb.b);
    if (ntvTransfer > 5.5 && signal > 0.000001)
        rgb *= ntvShoulder(signal, peak) / signal;
    return pow(clamp(rgb, 0.0, 1.0), vec3(1.0 / 2.4));
}
