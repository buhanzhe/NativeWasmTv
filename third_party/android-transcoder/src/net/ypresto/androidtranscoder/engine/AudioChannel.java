package net.ypresto.androidtranscoder.engine;

import android.media.MediaCodec;
import android.media.MediaFormat;
import java.nio.*;
import java.util.ArrayDeque;
import net.ypresto.androidtranscoder.compat.MediaCodecBufferCompatWrapper;

/** Bounded decoder buffers, consumed incrementally without copying overflow PCM. */
class AudioChannel {
    static final int BUFFER_INDEX_END_OF_STREAM=-1;
    private final MediaCodec decoder,encoder;
    private final MediaFormat target;
    private final MediaCodecBufferCompatWrapper output;
    private final ArrayDeque<Buffer> pending=new ArrayDeque<>();
    private int rate,inputs,outputs;
    AudioChannel(MediaCodec decoder,MediaCodec encoder,MediaFormat target){this.decoder=decoder;this.encoder=encoder;this.target=target;output=new MediaCodecBufferCompatWrapper(encoder);}
    void setActualDecodedFormat(MediaFormat format){
        rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE);inputs=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);outputs=target.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        if(rate!=target.getInteger(MediaFormat.KEY_SAMPLE_RATE)||inputs<1||inputs>8||outputs<1||outputs>2)
            throw new IllegalArgumentException("Unsupported decoded audio format");
        if(format.containsKey("pcm-encoding")&&format.getInteger("pcm-encoding")!=2)throw new IllegalArgumentException("Only PCM16 is supported");
    }
    void drainDecoderBufferAndQueue(int index,long pts,ByteBuffer pcm){
        pending.add(new Buffer(index,pts,pcm==null?null:pcm.slice().order(ByteOrder.nativeOrder()).asShortBuffer()));
    }
    boolean feedEncoder(long timeout){
        Buffer in=pending.peek();if(in==null)return false;
        int index=encoder.dequeueInputBuffer(timeout);if(index<0)return false;
        if(in.index==BUFFER_INDEX_END_OF_STREAM){pending.remove();encoder.queueInputBuffer(index,0,0,in.pts,MediaCodec.BUFFER_FLAG_END_OF_STREAM);return true;}
        ByteBuffer bytes=output.getInputBuffer(index);bytes.clear();ShortBuffer out=bytes.order(ByteOrder.nativeOrder()).asShortBuffer();
        long pts=in.pts+in.data.position()/inputs*1000000L/rate;
        int frames=Math.min(in.data.remaining()/inputs,out.remaining()/outputs);
        for(int i=0;i<frames;i++) {
            if(inputs==outputs){for(int ch=0;ch<inputs;ch++)out.put(in.data.get());}
            else {int sum=0;for(int ch=0;ch<inputs;ch++)sum+=in.data.get();short mono=(short)(sum/inputs);for(int ch=0;ch<outputs;ch++)out.put(mono);}
        }
        encoder.queueInputBuffer(index,0,out.position()*2,pts,0);
        if(in.data.remaining()<inputs){pending.remove();decoder.releaseOutputBuffer(in.index,false);}
        return true;
    }
    private static final class Buffer {final int index;final long pts;final ShortBuffer data;Buffer(int i,long p,ShortBuffer d){index=i;pts=p;data=d;}}
}
