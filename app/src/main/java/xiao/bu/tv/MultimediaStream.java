package xiao.bu.tv;

import android.graphics.*;
import android.media.*;
import android.os.Build;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.ypresto.androidtranscoder.engine.*;

/** File decoder -> H.264/AAC encoder -> paced RTSP, without screen/audio capture. */
final class MultimediaStream implements Closeable {
    interface Listener { void ended(String error); }
    private final File file;
    private final int width, height, fps, bitrate;
    private final Listener listener;
    private final ArrayBlockingQueue<Packet> packets = new ArrayBlockingQueue<>(32);
    private volatile boolean running = true, encodedEnd, ready;
    private volatile long positionUs;
    private volatile String error = "";
    private Thread encoderThread, senderThread;
    final RtspCastServer server;
    private final boolean image;
    private final MediaExtractor extractor;
    private int videoTrack = -1, audioTrack = -1;
    private MediaFormat videoFormat, audioFormat;
    private final boolean preserveAudio;
    private int rotation;

    MultimediaStream(File file, int maxWidth, int maxHeight, int fps, int bitrate, boolean preserveAudio, Listener listener) throws Exception {
        if (Build.VERSION.SDK_INT < 18) throw new IOException("多媒体转码需要 Android 4.3 或以上的手机");
        this.preserveAudio=preserveAudio;
        this.file = file; this.listener = listener; this.fps = Math.min(60, Math.max(15, fps));
        this.bitrate = Math.min(12000000, Math.max(1000000, bitrate));
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds); image = bounds.outWidth > 0;
        int sourceWidth = bounds.outWidth, sourceHeight = bounds.outHeight;
        if(image) try {
            int orientation=new ExifInterface(file.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);
            rotation=orientation==6?90:orientation==3?180:orientation==8?270:0;
        } catch(IOException ignored) {}
        MediaExtractor source = null;
        try {
            if (!image) {
                source = new MediaExtractor(); source.setDataSource(file.getPath());
                for (int i=0;i<source.getTrackCount();i++) {
                    MediaFormat format = source.getTrackFormat(i); String mime = format.getString(MediaFormat.KEY_MIME);
                    if (videoTrack < 0 && mime.startsWith("video/")) { videoTrack=i; videoFormat=format; }
                    if (audioTrack < 0 && mime.startsWith("audio/")) { audioTrack=i; audioFormat=format; }
                }
                if (videoFormat == null) throw new IOException("手机无法识别此图片或视频格式");
                if(videoFormat.containsKey("rotation-degrees")) rotation=videoFormat.getInteger("rotation-degrees");
                else {
                    MediaMetadataRetriever metadata=new MediaMetadataRetriever();
                    try {metadata.setDataSource(file.getPath());String angle=metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);if(angle!=null)rotation=Integer.parseInt(angle);} finally {metadata.release();}
                }
                sourceWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH); sourceHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT);
                if (Build.VERSION.SDK_INT >= 24 && videoFormat.containsKey("color-transfer")
                        && videoFormat.getInteger("color-transfer") >= 6)
                    throw new IOException("此 HDR 视频暂不支持正确转换为 SDR，请选择 SDR 视频");
            }
            if(rotation==90||rotation==270){int swap=sourceWidth;sourceWidth=sourceHeight;sourceHeight=swap;}
            if(preserveAudio && audioFormat!=null) {
                String mime=audioFormat.getString(MediaFormat.KEY_MIME);
                if(!"audio/mp4a-latm".equals(mime)&&!"audio/ac3".equals(mime))throw new IOException("当前 RTSP 暂不支持原样发送 "+mime+"，请关闭“保留源音频编码发送”");
            }
            float scale = Math.min(1f, Math.min((float)maxWidth/sourceWidth, (float)maxHeight/sourceHeight));
            width = Math.max(16, Math.round(sourceWidth*scale) / 16 * 16);
            height = Math.max(16, Math.round(sourceHeight*scale) / 16 * 16);
            server = new RtspCastServer(audioTrack >= 0, this.bitrate, "h264", image ? 2 : this.fps);
            if (audioFormat != null) {
                String audioMime=audioFormat.getString(MediaFormat.KEY_MIME);
                if(preserveAudio && !"audio/mp4a-latm".equals(audioMime) && !"audio/ac3".equals(audioMime))
                    throw new IOException("当前 RTSP 暂不支持原样发送 "+audioMime+"，请关闭“保留源音频编码发送”");
                server.setAc3Audio(preserveAudio && "audio/ac3".equals(audioMime));
                int rate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels = preserveAudio ? audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : Math.min(2, audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                server.setAudioFormat(rate, channels);
                int[] rates = {96000,88200,64000,48000,44100,32000,24000,22050,16000,12000,11025,8000,7350};
                for (int i=0;i<rates.length;i++) if (rates[i]==rate) {
                    int config = (2<<11)|(i<<7)|(channels<<3);
                    server.setAudioConfig(ByteBuffer.wrap(new byte[]{(byte)(config>>8),(byte)config})); break;
                }
            }
            if(preserveAudio && audioFormat!=null && audioFormat.containsKey("csd-0"))server.setAudioConfig(audioFormat.getByteBuffer("csd-0"));
            extractor = source;
        } catch (Exception failure) { if (source != null) source.release(); throw failure; }
    }
    void start() {
        server.start();
        senderThread = new Thread(this::send, "multimedia-rtsp-send"); senderThread.start();
        encoderThread = new Thread(() -> {
            try { if (image) encodeImage(); else encodeVideo(); }
            catch (Throwable failure) { if (running) { android.util.Log.e("MultimediaStream","Transcode failed",failure); error = failure.getMessage() == null ? "手机解码或编码失败" : failure.getMessage(); } }
            finally { if (extractor != null) extractor.release(); encodedEnd = true; }
        }, "multimedia-codec"); encoderThread.start();
    }
    boolean ready() { return ready; }
    boolean isImage() { return image; }
    String error() { return error; }
    long positionMs() { return positionUs/1000; }
    private MediaFormat avcFormat(int color) {
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, color); format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        // Qualcomm 7.x asserts in setBFrames when configured with very low fps.
        // Configure a conventional rate; image input timestamps still run at 2 fps.
        format.setInteger(MediaFormat.KEY_FRAME_RATE, image ? 30 : fps); format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, image ? 0 : 1);
        // Some API 21-25 Qualcomm encoders reject a profile without a paired level.
        // The platform's default AVC profile is accepted by the receiver.
        return format;
    }
    private final QueuedMuxer.StreamSink sink = new QueuedMuxer.StreamSink() {
        @Override public void format(QueuedMuxer.SampleType type, MediaFormat format) {
            if (type == QueuedMuxer.SampleType.VIDEO) { server.setVideoConfig(format.getByteBuffer("csd-0"), format.getByteBuffer("csd-1")); ready = true; }
            else { server.setAudioFormat(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)); server.setAudioConfig(format.getByteBuffer("csd-0")); }
        }
        @Override public void sample(QueuedMuxer.SampleType type, ByteBuffer data, MediaCodec.BufferInfo info) {
            if (info.size <= 0) return;
            if (info.size > 8*1024*1024) throw new IllegalStateException("编码帧过大");
            byte[] bytes = new byte[info.size]; ByteBuffer copy = data.duplicate(); copy.position(info.offset); copy.limit(info.offset+info.size); copy.get(bytes);
            try { while (running && !packets.offer(new Packet(type == QueuedMuxer.SampleType.VIDEO, bytes, info.presentationTimeUs, info.flags), 100, TimeUnit.MILLISECONDS)) { } }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("已停止投送"); }
            if (!running) throw new IllegalStateException("已停止投送");
        }
    };
    private void encodeVideo() throws Exception {
        QueuedMuxer output = new QueuedMuxer(sink);
        VideoTrackTranscoder video = new VideoTrackTranscoder(extractor, videoTrack, avcFormat(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface), output);
        AudioTrackTranscoder audio = null;
        ByteBuffer originalAudio=preserveAudio?ByteBuffer.allocate(65536):null;
        try {
            video.setRotation(rotation); video.setup();
            if (audioTrack >= 0 && preserveAudio) extractor.selectTrack(audioTrack);
            if (audioTrack >= 0 && !preserveAudio) {
                MediaFormat aac = MediaFormat.createAudioFormat("audio/mp4a-latm", audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE), Math.min(2,audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)));
                aac.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC); aac.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
                audio = new AudioTrackTranscoder(extractor, audioTrack, aac, output); audio.setup();
            }
            while (running && !(video.isFinished() && (audio == null || audio.isFinished()))) {
                boolean busy = video.stepPipeline(); if (audio != null) busy |= audio.stepPipeline();
                if(preserveAudio && audioTrack>=0 && extractor.getSampleTrackIndex()==audioTrack) {
                    ByteBuffer original=originalAudio;original.clear();int size=extractor.readSampleData(original,0);
                    if(size>0) {MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();info.set(0,size,extractor.getSampleTime(),0);sink.sample(QueuedMuxer.SampleType.AUDIO,original,info);}
                    extractor.advance();busy=true;
                }
                if (!busy) Thread.sleep(5);
            }
        } finally { try { video.release(); } finally { if (audio != null) audio.release(); } }
    }
    private void encodeImage() throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds=true; BitmapFactory.decodeFile(file.getPath(), options);
        int sample=1; while (options.outWidth/sample > width*2 || options.outHeight/sample > height*2) sample*=2;
        options.inJustDecodeBounds=false; options.inSampleSize=sample;
        Bitmap original=BitmapFactory.decodeFile(file.getPath(), options); if (original==null) throw new IOException("手机无法解码图片");
        if(rotation!=0){Matrix matrix=new Matrix();matrix.postRotate(rotation);Bitmap rotated=Bitmap.createBitmap(original,0,0,original.getWidth(),original.getHeight(),matrix,true);if(rotated!=original)original.recycle();original=rotated;}
        Bitmap scaled=Bitmap.createScaledBitmap(original,width,height,true); if(scaled!=original) original.recycle();
        int[] pixels=new int[width*height]; scaled.getPixels(pixels,0,width,0,0,width,height); scaled.recycle();
        MediaCodec codec=MediaCodec.createEncoderByType("video/avc");
        try {
            int color=0;
            for(int candidate:codec.getCodecInfo().getCapabilitiesForType("video/avc").colorFormats)
                if(candidate==21 || candidate==19) { color=candidate; break; }
            if(color==0) throw new IOException("手机编码器不支持图片输入");
            byte[] yuv=toYuv(pixels,width,height,color==21);
            codec.configure(avcFormat(color),null,null,MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start();
            ByteBuffer[] inputs=codec.getInputBuffers(), outputs=codec.getOutputBuffers();
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo(); long frame=0;
            while(running) {
                int index=codec.dequeueInputBuffer(1000);
                if(index>=0) { inputs[index].clear(); inputs[index].put(yuv); codec.queueInputBuffer(index,0,yuv.length,frame++*500000L,0); }
                while(running) {
                    int out=codec.dequeueOutputBuffer(info,1000);
                    if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) sink.format(QueuedMuxer.SampleType.VIDEO,codec.getOutputFormat());
                    else if(out==MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) outputs=codec.getOutputBuffers();
                    else if(out>=0) { if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) sink.sample(QueuedMuxer.SampleType.VIDEO,outputs[out],info); codec.releaseOutputBuffer(out,false); }
                    else break;
                }
            }
        } finally { try { codec.stop(); } catch(Exception ignored) {} codec.release(); }
    }
    static byte[] toYuv(int[] pixels,int width,int height,boolean semi) {
        int area=width*height, uv=area, u=area, v=area+area/4; byte[] result=new byte[area*3/2];
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
            int pixel=pixels[y*width+x],r=(pixel>>16)&255,g=(pixel>>8)&255,b=pixel&255;
            result[y*width+x]=(byte)Math.max(16,Math.min(235,((66*r+129*g+25*b+128)>>8)+16));
            if((y&1)==0&&(x&1)==0) { int cb=Math.max(16,Math.min(240,((-38*r-74*g+112*b+128)>>8)+128)); int cr=Math.max(16,Math.min(240,((112*r-94*g-18*b+128)>>8)+128));
                if(semi) {result[uv++]=(byte)cb;result[uv++]=(byte)cr;} else {result[u++]=(byte)cb;result[v++]=(byte)cr;} }
        } return result;
    }
    private void send() {
        long baseNs=0, basePts=0, missingSince=System.nanoTime();
        try {
            while(running) {
                if(!server.hasVideoClient()) {
                    if(System.nanoTime()-missingSince>20_000_000_000L) throw new IOException("电视未连接 RTSP 串流");
                    Thread.sleep(25); continue;
                }
                missingSince=System.nanoTime();
                Packet packet=packets.poll(100,TimeUnit.MILLISECONDS);
                if(packet==null) {if(encodedEnd)break;continue;}
                if(baseNs==0) {baseNs=System.nanoTime();basePts=packet.pts;}
                long target=baseNs+Math.max(0,packet.pts-basePts)*1000L;
                while(running && target>System.nanoTime()) TimeUnit.NANOSECONDS.sleep(Math.max(1L,Math.min(20_000_000L,target-System.nanoTime())));
                if(!running)break;
                if(packet.video) {server.sendVideo(packet.bytes,packet.pts,packet.flags);positionUs=packet.pts;} else server.sendAudio(packet.bytes,packet.pts);
            }
        } catch(Exception failure) {if(running)error=failure.getMessage();}
        finally { if(running) listener.ended(error); }
    }
    @Override public void close() {
        running=false;server.close();packets.clear();
        if(encoderThread!=null)encoderThread.interrupt();if(senderThread!=null)senderThread.interrupt();
    }
    private static final class Packet {
        final boolean video;final byte[] bytes;final long pts;final int flags;
        Packet(boolean video,byte[] bytes,long pts,int flags){this.video=video;this.bytes=bytes;this.pts=pts;this.flags=flags;}
    }
}
