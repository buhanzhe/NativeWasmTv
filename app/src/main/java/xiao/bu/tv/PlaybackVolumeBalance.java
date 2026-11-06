package xiao.bu.tv;

/** Three segment intervals, bounded attenuation; quiet passages never cause a boost. */
final class PlaybackVolumeBalance {
    double level = Double.NaN;
    float gain = 1f;
    int samples, segments;
    double segmentPower; int segmentSamples;
    final double[] recent = new double[3];
    void finishSegment() {
        if(segmentSamples > 0) { recent[segments % 3] = segmentPower / segmentSamples; segments++; }
        segmentPower=0; segmentSamples=0;
    }
    boolean ready() { return segments >= 3; }
    void sample(long power) {
        if (power <= 0) return;
        double db = 10 * Math.log10(power / 1073741824.0);
        if (db < -50 || db > 0) return;
        level = Double.isNaN(level) ? db : level * .8 + db * .2;
        samples++;
        segmentPower += power; segmentSamples++;
    }
    static double systemDb(int volume, int maximum) {
        return 20 * Math.log10(Math.max(1, volume) / (double)Math.max(1, maximum));
    }
    double learn(int volume, int maximum) {
        return Math.max(-60, Math.min(0, level + 20 * Math.log10(gain) + systemDb(volume, maximum)));
    }
    float adjust(double target, int volume, int maximum) {
        if (!ready() || volume <= 0 || Double.isNaN(level)) return gain;
        level=10*Math.log10((recent[0]+recent[1]+recent[2])/3 / 1073741824.0);
        float wanted = (float)Math.pow(10, (target - level - systemDb(volume, maximum)) / 20);
        wanted = Math.max(.1f, Math.min(1f, wanted));
        gain = wanted;
        return gain;
    }
}
