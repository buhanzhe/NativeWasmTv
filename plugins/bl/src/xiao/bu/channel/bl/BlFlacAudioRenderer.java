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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.jflac.FLACDecoder;
import org.jflac.frame.Frame;
import org.jflac.metadata.StreamInfo;
import org.jflac.util.ByteData;

/** Decodes FLAC samples in DASH MP4, including on devices without a FLAC MediaCodec. */
final class BlFlacAudioRenderer extends SimpleDecoderAudioRenderer {
    private static final int STREAM_INFO_END = 42;
    private Format output;

    BlFlacAudioRenderer(Handler handler,AudioRendererEventListener listener,AudioSink sink){
        super(handler,listener,null,false,sink);
    }

    @Override protected int supportsFormatInternal(DrmSessionManager<ExoMediaCrypto> drm,Format format){
        if(!MimeTypes.AUDIO_FLAC.equals(format.sampleMimeType))return FORMAT_UNSUPPORTED_TYPE;
        if(format.drmInitData!=null)return FORMAT_UNSUPPORTED_DRM;
        try{
            StreamInfo info=readInfo(header(format));
            int pcm=encoding(info.getBitsPerSample());
            return pcm!=C.ENCODING_INVALID&&supportsOutput(info.getChannels(),pcm)?FORMAT_HANDLED:FORMAT_UNSUPPORTED_SUBTYPE;
        }catch(IOException invalid){return FORMAT_UNSUPPORTED_SUBTYPE;}
    }

    @Override protected SimpleDecoder<DecoderInputBuffer,SimpleOutputBuffer,AudioDecoderException> createDecoder(
            Format format,ExoMediaCrypto crypto)throws AudioDecoderException{
        try{
            byte[] metadata=header(format);
            StreamInfo info=readInfo(metadata);
            output=Format.createAudioSampleFormat(null,MimeTypes.AUDIO_RAW,null,Format.NO_VALUE,Format.NO_VALUE,
                    info.getChannels(),info.getSampleRate(),encoding(info.getBitsPerSample()),null,null,0,null);
            return new Decoder(metadata);
        }catch(IOException invalid){throw new AudioDecoderException("无效的 FLAC 初始化信息",invalid);}
    }

    @Override protected Format getOutputFormat(){return output;}

    private static byte[] header(Format format)throws IOException{
        byte[] metadata=format.initializationData.isEmpty()?new byte[0]:format.initializationData.get(0);
        if(metadata.length<STREAM_INFO_END||metadata[0]!='f'||metadata[1]!='L'||metadata[2]!='a'||metadata[3]!='C'
                ||(metadata[4]&0x7f)!=0||metadata[5]!=0||metadata[6]!=0||metadata[7]!=34)
            throw new IOException("缺少 FLAC STREAMINFO");
        // STREAMINFO alone is sufficient to decode an independent MP4 audio sample.
        byte[] header=Arrays.copyOf(metadata,STREAM_INFO_END);
        header[4]=(byte)0x80;
        return header;
    }

    private static StreamInfo readInfo(byte[] header)throws IOException{
        return new FLACDecoder(new ByteArrayInputStream(header)).readStreamInfo();
    }

    private static int encoding(int bits){
        switch(bits){
            case 8:return C.ENCODING_PCM_8BIT;
            case 16:return C.ENCODING_PCM_16BIT;
            case 24:return C.ENCODING_PCM_24BIT;
            default:return C.ENCODING_INVALID;
        }
    }

    private static final class Decoder extends SimpleDecoder<DecoderInputBuffer,SimpleOutputBuffer,AudioDecoderException>{
        private final byte[] metadata;
        private ByteData pcm;

        Decoder(byte[] metadata){
            super(new DecoderInputBuffer[4],new SimpleOutputBuffer[4]);
            this.metadata=metadata;
        }

        @Override public String getName(){return "jFLAC";}
        @Override protected DecoderInputBuffer createInputBuffer(){
            return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
        }
        @Override protected SimpleOutputBuffer createOutputBuffer(){return new SimpleOutputBuffer(this);}
        @Override protected AudioDecoderException createUnexpectedDecodeException(Throwable error){
            return new AudioDecoderException("FLAC 解码失败",error);
        }
        @Override protected AudioDecoderException decode(DecoderInputBuffer input,SimpleOutputBuffer result,boolean reset){
            ByteBuffer data=input.data;
            ByteArrayInputStream sample=new ByteArrayInputStream(data.array(),data.arrayOffset()+data.position(),data.remaining());
            try(SequenceInputStream stream=new SequenceInputStream(new ByteArrayInputStream(metadata),sample)){
                FLACDecoder decoder=new FLACDecoder(stream);
                decoder.readStreamInfo();
                Frame frame=decoder.readNextFrame();
                if(frame==null)throw new IOException("FLAC 音频帧不完整");
                pcm=decoder.decodeFrame(frame,pcm);
                ByteBuffer buffer=result.init(input.timeUs,pcm.getLen());
                buffer.put(pcm.getData(),0,pcm.getLen());
                buffer.flip();
                return null;
            }catch(IOException invalid){return new AudioDecoderException("FLAC 解码失败",invalid);}
        }
    }
}
