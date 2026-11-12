package xiao.bu.tv;

/** Three short audible windows, bounded attenuation; silence never sets a level. */
final class PlaybackVolumeBalance {
    double level = Double.NaN;
    float gain = 1f;
    int samples, segments, windows;
    double segmentPower; int segmentSamples;
    static final long WINDOW_MS=750;
    final double[] recent = new double[3];
    long startedAt=-1, firstAudibleAt=-1, windowAt=-1, lastObservation=-1;
    void resetMeasurement() {
        level=Double.NaN;samples=segments=windows=segmentSamples=0;segmentPower=0;
        startedAt=firstAudibleAt=windowAt=lastObservation=-1;
        java.util.Arrays.fill(recent,0);
    }
    void observe(long power,long now) {
        if(startedAt<0)startedAt=now;
        if(windowAt<0||lastObservation>=0&&now-lastObservation>1000){windowAt=now;segmentPower=0;segmentSamples=0;}
        lastObservation=now;
        if(now-windowAt>=WINDOW_MS){
            windows++;
            if(segmentSamples>=2)finishSegment();else{segmentPower=0;segmentSamples=0;}
            windowAt=now;
        }
        sample(power);if(firstAudibleAt<0&&!Double.isNaN(level)){
            firstAudibleAt=now;windows=0;windowAt=now;
        }
    }
    long pollDelay(long now) { return Double.isNaN(level)&&startedAt>=0&&now-startedAt>=3000?500:150; }
    private double measuredLevel(){
        int count=Math.min(3,segments);if(count==0)return level;
        double power=0;for(int i=0;i<count;i++)power+=recent[i];
        return 10*Math.log10(power/count/1073741824.0);
    }
    void finishSegment() {
        if(segmentSamples > 0) { recent[segments % 3] = segmentPower / segmentSamples; segments++; }
        segmentPower=0; segmentSamples=0;
    }
    boolean ready() { return segments >= 3 || windows>=3&&segments>0&&samples>=3; }
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
        return Math.max(-60, Math.min(0, measuredLevel() + 20 * Math.log10(gain) + systemDb(volume, maximum)));
    }
    float adjust(double target, int volume, int maximum) {
        if (!ready() || volume <= 0 || Double.isNaN(level)) return gain;
        level=measuredLevel();
        if(Double.isNaN(target)||Double.isInfinite(target))target=-30;
        float wanted = (float)Math.pow(10, (target - level - systemDb(volume, maximum)) / 20);
        wanted = Math.max(.1f, Math.min(1f, wanted));
        gain = wanted;
        return gain;
    }
}
