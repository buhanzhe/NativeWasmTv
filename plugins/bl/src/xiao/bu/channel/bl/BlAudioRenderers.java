package xiao.bu.channel.bl;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.audio.AudioCapabilities;
import com.google.android.exoplayer2.audio.AudioProcessor;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.audio.DefaultAudioSink;
import com.google.android.exoplayer2.audio.MediaCodecAudioRenderer;
import com.google.android.exoplayer2.drm.DrmSessionManager;
import com.google.android.exoplayer2.drm.FrameworkMediaCrypto;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.util.Util;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

final class BlAudioRenderers extends DefaultRenderersFactory {
    private static final int[] DOLBY={C.ENCODING_AC3,C.ENCODING_E_AC3,C.ENCODING_E_AC3_JOC};
    private final AudioCapabilities capabilities;
    volatile boolean passthroughActive;

    BlAudioRenderers(Context context,boolean allowPassthrough){
        super(context);
        capabilities=allowPassthrough?outputCapabilities(context):AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES;
    }

    @Override protected void buildAudioRenderers(Context context,int mode,MediaCodecSelector selector,
            DrmSessionManager<FrameworkMediaCrypto> drm,boolean playClear,boolean decoderFallback,
            AudioProcessor[] processors,Handler handler,AudioRendererEventListener listener,
            ArrayList<Renderer> renderers){
        renderers.add(new BlFlacAudioRenderer(handler,listener,new DefaultAudioSink(capabilities,processors,true)));
        renderers.add(new MediaCodecAudioRenderer(context,selector,drm,playClear,true,handler,listener,
                new DefaultAudioSink(capabilities,processors)){
            @Override protected List<MediaCodecInfo> getDecoderInfos(MediaCodecSelector codecs,Format format,boolean secure)
                    throws MediaCodecUtil.DecoderQueryException{
                List<MediaCodecInfo> available=super.getDecoderInfos(codecs,format,secure);
                passthroughActive=!available.isEmpty()&&available.get(0).passthrough;
                return available;
            }
        });
    }

    private static AudioCapabilities outputCapabilities(Context context){
        AudioManager manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        if(Build.VERSION.SDK_INT<21||manager==null||manager.isBluetoothA2dpOn()||manager.isBluetoothScoOn()
                ||manager.isWiredHeadsetOn())return AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES;
        AudioCapabilities advertised=AudioCapabilities.getCapabilities(context);
        if(Build.VERSION.SDK_INT<29)return advertised;
        try{
            return directCapabilities(advertised);
        }catch(Exception unavailable){return advertised;}
    }

    /** Uses the routed AudioTrack query where available, while compiling against the API-14 stub. */
    private static AudioCapabilities directCapabilities(AudioCapabilities advertised)throws Exception{
        Class<?> format=Class.forName("android.media.AudioFormat");
        Class<?> attributes=Class.forName("android.media.AudioAttributes");
        Class<?> formatBuilder=Class.forName("android.media.AudioFormat$Builder");
        Class<?> attributesBuilder=Class.forName("android.media.AudioAttributes$Builder");
        Object usage=attributesBuilder.newInstance();
        attributesBuilder.getMethod("setUsage",int.class).invoke(usage,C.USAGE_MEDIA);
        attributesBuilder.getMethod("setContentType",int.class).invoke(usage,C.CONTENT_TYPE_MOVIE);
        Object media=attributesBuilder.getMethod("build").invoke(usage);
        Method supported=AudioTrack.class.getMethod("isDirectPlaybackSupported",format,attributes);
        ArrayList<Integer> encodings=new ArrayList<>();
        encodings.add(C.ENCODING_PCM_16BIT);
        int maxChannels=2;
        for(int encoding:DOLBY){
            // Before Android 13 a positive query may describe a connected but inactive HDMI route.
            if(Build.VERSION.SDK_INT<33&&!advertised.supportsEncoding(encoding))continue;
            for(int channels:new int[]{8,6,2}){
                if(Build.VERSION.SDK_INT<33&&channels>advertised.getMaxChannelCount())continue;
                Object builder=formatBuilder.newInstance();
                formatBuilder.getMethod("setEncoding",int.class).invoke(builder,encoding);
                formatBuilder.getMethod("setSampleRate",int.class).invoke(builder,48000);
                formatBuilder.getMethod("setChannelMask",int.class).invoke(builder,Util.getAudioTrackChannelConfig(channels));
                if(Boolean.TRUE.equals(supported.invoke(null,formatBuilder.getMethod("build").invoke(builder),media))){
                    encodings.add(encoding);
                    maxChannels=Math.max(maxChannels,channels);
                    break;
                }
            }
        }
        int[] supportedEncodings=new int[encodings.size()];
        for(int i=0;i<supportedEncodings.length;i++)supportedEncodings[i]=encodings.get(i);
        return new AudioCapabilities(supportedEncodings,maxChannels);
    }
}
