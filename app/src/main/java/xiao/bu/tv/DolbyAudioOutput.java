package xiao.bu.tv;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import java.nio.ByteBuffer;

/** Compressed AudioTrack sink called by the IJK audio thread, never by the UI thread. */
public final class DolbyAudioOutput {
    private static final String TAG = "nTvDolby";
    private static volatile Context application;
    private final AudioTrack track;
    private final int encoding, sampleRate, channels;
    private ByteBuffer packet;
    private final TrueHdFrames trueHd = new TrueHdFrames();
    private long lastInputAt;
    private boolean playing;
    private long lastHead, wraps, routeCheckedAt;

    static void initialize(Context context) {
        application = context.getApplicationContext();
    }

    private DolbyAudioOutput(AudioTrack track, int encoding, int sampleRate, int channels) {
        this.track = track;
        this.encoding = encoding;
        this.sampleRate = sampleRate;
        this.channels = channels;
    }

    // JNI entry points. Failure means FFmpeg -> PCM, not a silent audio track.
    public static DolbyAudioOutput open(int encoding, int sampleRate, int channels) {
        if (Build.VERSION.SDK_INT < 21 || application == null || sampleRate <= 0) return null;
        if (encoding != AudioFormat.ENCODING_AC3 && encoding != AudioFormat.ENCODING_E_AC3
                && !(Build.VERSION.SDK_INT >= 23 && encoding == AudioFormat.ENCODING_DOLBY_TRUEHD)) return null;
        AudioTrack output = null;
        try {
            if (!supports(encoding, sampleRate, channels)) return null;
            int mask = channelMask(channels);
            AudioFormat format = new AudioFormat.Builder().setEncoding(encoding)
                    .setSampleRate(sampleRate).setChannelMask(mask).build();
            int minimum = AudioTrack.getMinBufferSize(sampleRate, mask, encoding);
            if (minimum <= 0 || minimum > 512 * 1024) return null;
            output = new AudioTrack(attributes(), format, Math.max(minimum, 32768),
                    AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (output.getState() != AudioTrack.STATE_INITIALIZED) {
                output.release();
                return null;
            }
            Log.i(TAG, "Passthrough encoding=" + encoding + " rate=" + sampleRate + " channels=" + channels);
            return new DolbyAudioOutput(output, encoding, sampleRate, channels);
        } catch (RuntimeException error) {
            if (output != null) output.release();
            Log.w(TAG, "Passthrough unavailable; use FFmpeg PCM", error);
            return null;
        }
    }

    private static AudioAttributes attributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
    }

    private static int channelMask(int count) {
        switch (count) {
            case 1: return AudioFormat.CHANNEL_OUT_MONO;
            case 2: return AudioFormat.CHANNEL_OUT_STEREO;
            case 6: return AudioFormat.CHANNEL_OUT_5POINT1;
            case 8: return AudioFormat.CHANNEL_OUT_7POINT1_SURROUND;
            default: return 0;
        }
    }

    private static boolean contains(int[] values, int value) {
        if (values != null) for (int candidate : values) if (candidate == value) return true;
        return false;
    }

    static boolean supports(int encoding, int rate, int count) {
        if (Build.VERSION.SDK_INT < 21 || application == null || channelMask(count) == 0) return false;
        AudioManager manager = (AudioManager) application.getSystemService(Context.AUDIO_SERVICE);
        // Before API 33 direct-playback queries can report an inactive HDMI route.
        if (manager == null || manager.isBluetoothA2dpOn() || manager.isBluetoothScoOn()
                || manager.isWiredHeadsetOn()) return false;
        Intent hdmi = application.registerReceiver(null, new IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG));
        boolean advertised = hdmi != null && hdmi.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 0) == 1
                && contains(hdmi.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS), encoding)
                && count <= hdmi.getIntExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, 2);
        if (!advertised) return false;
        if (Build.VERSION.SDK_INT >= 29) {
            AudioFormat format = new AudioFormat.Builder().setEncoding(encoding).setSampleRate(rate)
                    .setChannelMask(channelMask(count)).build();
            return AudioTrack.isDirectPlaybackSupported(format, attributes());
        }
        return true;
    }

    /** Nonblocking partial writes keep stop, pause and seek responsive. */
    public int write(byte[] data, int offset, int length) {
        try {
            if (encoding == AudioFormat.ENCODING_DOLBY_TRUEHD) {
                int drained = drainTrueHd();
                if (drained < 0) return -1;
                if (packet != null && packet.hasRemaining()) return 0;
                lastInputAt = SystemClock.elapsedRealtime();
                if (trueHd.add(data, length)) packet = ByteBuffer.wrap(trueHd.take());
                return length;
            }
            if (offset == 0) packet = ByteBuffer.wrap(data, 0, length);
            if (packet == null) return -1;
            return track.write(packet, length, AudioTrack.WRITE_NON_BLOCKING);
        } catch (RuntimeException error) {
            Log.w(TAG, "Encoded write failed; use FFmpeg PCM", error);
            return -1;
        }
    }

    /** Playback head in decoded sample time; -1 requests a PCM fallback. */
    public long position(boolean paused) {
        try {
            long now = SystemClock.elapsedRealtime();
            if (now - routeCheckedAt >= 500) {
                routeCheckedAt = now;
                if (!supports(encoding, sampleRate, channels)) return -1;
                if (Build.VERSION.SDK_INT >= 23) {
                    AudioDeviceInfo route = track.getRoutedDevice();
                    if (route != null && !contains(route.getEncodings(), encoding)) return -1;
                }
            }
            if (playing == paused) {
                if (paused) track.pause(); else track.play();
                playing = !paused;
            }
            if (encoding == AudioFormat.ENCODING_DOLBY_TRUEHD && !paused) {
                if ((packet == null || !packet.hasRemaining()) && trueHd.hasPending()
                        && now - lastInputAt >= 50) packet = ByteBuffer.wrap(trueHd.take());
                if (drainTrueHd() < 0) return -1;
            }
            long head = track.getPlaybackHeadPosition() & 0xffffffffL;
            if (head < lastHead) {
                if (lastHead - head > 0x80000000L) wraps++;
                else return -1; // A driver reset is not a 32-bit counter wrap.
            }
            lastHead = head;
            return ((wraps << 32) + head) * 1000000L / sampleRate;
        } catch (RuntimeException error) {
            Log.w(TAG, "Encoded output lost; use FFmpeg PCM", error);
            return -1;
        }
    }

    public void flush() {
        track.pause();
        playing = false;
        track.flush();
        packet = null;
        trueHd.reset();
        lastHead = wraps = 0;
    }

    public void close() {
        try { track.pause(); } catch (RuntimeException ignored) {}
        try { track.flush(); } catch (RuntimeException ignored) {}
        track.release();
        packet = null;
        trueHd.reset();
    }

    private int drainTrueHd() {
        return packet == null || !packet.hasRemaining() ? 0
                : track.write(packet, packet.remaining(), AudioTrack.WRITE_NON_BLOCKING);
    }
}
