package xiao.bu.channel.bl;

import android.os.Handler;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.audio.AudioDecoderException;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.audio.SimpleDecoderAudioRenderer;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.decoder.SimpleDecoder;
import com.google.android.exoplayer2.decoder.SimpleOutputBuffer;
import com.google.android.exoplayer2.drm.DrmSessionManager;
import com.google.android.exoplayer2.drm.ExoMediaCrypto;
import com.google.android.exoplayer2.util.MimeTypes;
import java.nio.Buffer;
import java.nio.ByteBuffer;

/** Sends demuxed E-AC-3 access units unchanged to a compressed AudioTrack. */
final class BlDolbyPassthroughRenderer extends SimpleDecoderAudioRenderer {
    private Format output;
    volatile boolean active;

    BlDolbyPassthroughRenderer(Handler handler, AudioRendererEventListener listener, AudioSink sink) {
        super(handler, listener, null, false, sink);
    }

    static boolean isEac3(String mime) {
        return MimeTypes.AUDIO_E_AC3.equals(mime) || MimeTypes.AUDIO_E_AC3_JOC.equals(mime);
    }

    private int outputEncoding(Format format) {
        if (format.channelCount <= 0 || format.sampleRate <= 0) return C.ENCODING_INVALID;
        if (MimeTypes.AUDIO_E_AC3_JOC.equals(format.sampleMimeType)
                && supportsOutput(format.channelCount, C.ENCODING_E_AC3_JOC)) return C.ENCODING_E_AC3_JOC;
        return supportsOutput(format.channelCount, C.ENCODING_E_AC3) ? C.ENCODING_E_AC3 : C.ENCODING_INVALID;
    }

    @Override protected int supportsFormatInternal(DrmSessionManager<ExoMediaCrypto> drm, Format format) {
        if (!isEac3(format.sampleMimeType)) return FORMAT_UNSUPPORTED_TYPE;
        if (format.drmInitData != null) return FORMAT_UNSUPPORTED_DRM;
        return outputEncoding(format) == C.ENCODING_INVALID ? FORMAT_UNSUPPORTED_SUBTYPE : FORMAT_HANDLED;
    }

    @Override protected SimpleDecoder<DecoderInputBuffer,SimpleOutputBuffer,AudioDecoderException> createDecoder(
            Format format, ExoMediaCrypto crypto) throws AudioDecoderException {
        int encoding = outputEncoding(format);
        if (!isEac3(format.sampleMimeType) || crypto != null || encoding == C.ENCODING_INVALID)
            throw new AudioDecoderException("当前音频输出不支持 E-AC-3 直通");
        // Exo 2.10 reads the sink's encoding from pcmEncoding even for compressed output.
        output = Format.createAudioSampleFormat(null, format.sampleMimeType, null, Format.NO_VALUE,
                Format.NO_VALUE, format.channelCount, format.sampleRate, encoding, null, null, 0, null);
        return new Packets();
    }

    @Override protected Format getOutputFormat() { return output; }

    @Override protected void onAudioSessionId(int audioSessionId) { active = true; }

    @Override protected void onDisabled() {
        try { super.onDisabled(); }
        finally { active = false; output = null; }
    }

    private static final class Packets extends SimpleDecoder<DecoderInputBuffer,SimpleOutputBuffer,AudioDecoderException> {
        Packets() { super(new DecoderInputBuffer[2], new SimpleOutputBuffer[2]); }
        @Override public String getName() { return "E-AC-3 passthrough"; }
        @Override protected DecoderInputBuffer createInputBuffer() {
            return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
        }
        @Override protected SimpleOutputBuffer createOutputBuffer() { return new SimpleOutputBuffer(this); }
        @Override protected AudioDecoderException createUnexpectedDecodeException(Throwable error) {
            return new AudioDecoderException("E-AC-3 直通帧读取失败", error);
        }
        @Override protected AudioDecoderException decode(DecoderInputBuffer input, SimpleOutputBuffer output, boolean reset) {
            if (input.isEncrypted() || input.data == null || input.data.remaining() < 5)
                return new AudioDecoderException("E-AC-3 直通帧无效");
            ByteBuffer bytes = output.init(input.timeUs, input.data.remaining());
            bytes.put(input.data);
            ((Buffer) bytes).flip();
            return null;
        }
    }
}
