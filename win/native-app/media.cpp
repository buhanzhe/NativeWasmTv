#include "media.hpp"
#include <mmsystem.h>
#include <cmath>
#include "display.hpp"
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/hwcontext_d3d11va.h>
#include <libavutil/pixdesc.h>
#include <libswscale/swscale.h>
#include <libswresample/swresample.h>
}
int MediaPipe::read(uint8_t* out,int count){std::unique_lock<std::mutex> lock(mutex);cv.wait(lock,[&]{return closed||!chunks.empty();});if(chunks.empty())return AVERROR_EOF;auto& front=chunks.front();int n=std::min<size_t>(count,front.size()-offset);memcpy(out,front.data()+offset,n);offset+=n;bytes-=n;if(offset==front.size()){chunks.pop_front();offset=0;}return n;}
Decoder::Decoder(const std::string& url,const std::string& headers,std::shared_ptr<MediaPipe> input,std::function<void()> notify):pipe(input),changed(std::move(notify)){openedAt=now();timeBeginPeriod(1);worker=std::thread([=]{run(url,headers);});}
Decoder::~Decoder(){stop=true;if(pipe)pipe->close();if(worker.joinable())worker.join();timeEndPeriod(1);}
struct AudioOutput {
  struct Block {WAVEHDR header{};std::vector<uint8_t> bytes;};HWAVEOUT out=nullptr;std::deque<std::unique_ptr<Block>> queued;
  AudioOutput(){WAVEFORMATEX f{};f.wFormatTag=WAVE_FORMAT_PCM;f.nChannels=2;f.nSamplesPerSec=48000;f.wBitsPerSample=16;f.nBlockAlign=4;f.nAvgBytesPerSec=192000;waveOutOpen(&out,WAVE_MAPPER,&f,0,0,CALLBACK_NULL);}
  void reap(){while(!queued.empty()&&(queued.front()->header.dwFlags&WHDR_DONE)){waveOutUnprepareHeader(out,&queued.front()->header,sizeof(WAVEHDR));queued.pop_front();}}
  void play(std::vector<uint8_t> data,std::atomic<bool>& stopped){if(!out)return;reap();while(queued.size()>24&&!stopped){Sleep(5);reap();}if(stopped)return;auto b=std::make_unique<Block>();b->bytes=std::move(data);b->header.lpData=(char*)b->bytes.data();b->header.dwBufferLength=b->bytes.size();if(waveOutPrepareHeader(out,&b->header,sizeof(WAVEHDR))==MMSYSERR_NOERROR){waveOutWrite(out,&b->header,sizeof(WAVEHDR));queued.push_back(std::move(b));}}
  ~AudioOutput(){if(out){waveOutReset(out);for(auto& b:queued)waveOutUnprepareHeader(out,&b->header,sizeof(WAVEHDR));waveOutClose(out);}}
};
void Decoder::run(std::string url,std::string headers){
  AVFormatContext* fmt=avformat_alloc_context();AVIOContext* io=nullptr;AVPacket* packet=av_packet_alloc();AVFrame* raw=av_frame_alloc();std::map<int,AVCodecContext*> codecs;SwsContext* scaler=nullptr;SwrContext* resampler=nullptr;AudioOutput audio;AVBufferRef* hardwareDevice=nullptr;auto gpu=displayDevice();
  auto cleanup=[&]{for(auto& it:codecs)avcodec_free_context(&it.second);av_frame_free(&raw);av_packet_free(&packet);avformat_close_input(&fmt);if(io){av_freep(&io->buffer);avio_context_free(&io);}sws_freeContext(scaler);swr_free(&resampler);av_buffer_unref(&hardwareDevice);};
  try {
    fmt->interrupt_callback={[](void* p){return ((Decoder*)p)->stop.load()?1:0;},this};
    AVDictionary* options=nullptr;av_dict_set(&options,"rw_timeout","10000000",0);av_dict_set(&options,"tls_verify","1",0);av_dict_set(&options,"protocol_whitelist","file,http,https,tcp,tls,crypto",0);av_dict_set(&options,"analyzeduration","200000",0);av_dict_set(&options,"probesize","65536",0);av_dict_set(&options,"fpsprobesize","2",0);if(!headers.empty())av_dict_set(&options,"headers",headers.c_str(),0);
    if(pipe){auto buffer=(uint8_t*)av_malloc(32768);io=avio_alloc_context(buffer,32768,0,pipe.get(),[](void* p,uint8_t* b,int n){return ((MediaPipe*)p)->read(b,n);},nullptr,nullptr);fmt->pb=io;fmt->flags|=AVFMT_FLAG_CUSTOM_IO;}
    int rc=avformat_open_input(&fmt,pipe?nullptr:url.c_str(),nullptr,&options);av_dict_free(&options);if(rc<0)throw std::runtime_error("Unable to open media ("+std::to_string(rc)+")");bool initialized=pipe&&fmt->nb_streams>0;
    for(unsigned i=0;i<fmt->nb_streams;i++){auto p=fmt->streams[i]->codecpar;if(p->codec_id==AV_CODEC_ID_NONE||(p->codec_type==AVMEDIA_TYPE_VIDEO&&(!p->width||!p->height)))initialized=false;}
    if(!initialized&&avformat_find_stream_info(fmt,nullptr)<0)throw std::runtime_error("No media tracks");
    if(gpu->device){hardwareDevice=av_hwdevice_ctx_alloc(AV_HWDEVICE_TYPE_D3D11VA);if(hardwareDevice){auto base=(AVHWDeviceContext*)hardwareDevice->data;auto d3d=(AVD3D11VADeviceContext*)base->hwctx;d3d->device=gpu->device.Get();d3d->device->AddRef();d3d->lock_ctx=&gpu->mutex;d3d->lock=[](void* p){((std::recursive_mutex*)p)->lock();};d3d->unlock=[](void* p){((std::recursive_mutex*)p)->unlock();};if(av_hwdevice_ctx_init(hardwareDevice)<0)av_buffer_unref(&hardwareDevice);}}

    for(unsigned i=0;i<fmt->nb_streams;i++){auto p=fmt->streams[i]->codecpar;if(p->codec_type!=AVMEDIA_TYPE_VIDEO&&p->codec_type!=AVMEDIA_TYPE_AUDIO)continue;auto codec=avcodec_find_decoder(p->codec_id);if(!codec)continue;auto ctx=avcodec_alloc_context3(codec);avcodec_parameters_to_context(ctx,p);ctx->thread_count=std::min(8u,std::max(2u,std::thread::hardware_concurrency()));
      if(p->codec_type==AVMEDIA_TYPE_VIDEO&&hardwareDevice){ctx->hw_device_ctx=av_buffer_ref(hardwareDevice);ctx->get_format=[](AVCodecContext*,const AVPixelFormat* formats){for(auto f=formats;*f!=AV_PIX_FMT_NONE;f++)if(*f==AV_PIX_FMT_D3D11)return *f;for(auto f=formats;*f!=AV_PIX_FMT_NONE;f++){auto desc=av_pix_fmt_desc_get(*f);if(desc&&!(desc->flags&AV_PIX_FMT_FLAG_HWACCEL))return *f;}return AV_PIX_FMT_NONE;};ctx->thread_type=FF_THREAD_SLICE;}
if(avcodec_open2(ctx,codec,nullptr)<0){avcodec_free_context(&ctx);continue;}codecs[i]=ctx;}
    if(codecs.empty())throw std::runtime_error("Unsupported media codec");double origin=NAN;int64_t start=0;
    while(!stop&&av_read_frame(fmt,packet)>=0){
      if(paused){auto at=now();if(audio.out)waveOutPause(audio.out);while(paused&&!stop)Sleep(10);if(audio.out)waveOutRestart(audio.out);start+=now()-at;}
      auto it=codecs.find(packet->stream_index);if(it==codecs.end()){av_packet_unref(packet);continue;}auto ctx=it->second;int stream=packet->stream_index;int sent=avcodec_send_packet(ctx,packet);av_packet_unref(packet);if(sent<0)continue;
      while(!stop&&avcodec_receive_frame(ctx,raw)>=0){double pts=raw->best_effort_timestamp==AV_NOPTS_VALUE?position.load():raw->best_effort_timestamp*av_q2d(fmt->streams[stream]->time_base);if(std::isnan(origin)){origin=pts;start=now();}double seconds=std::max(0.0,pts-origin);
        if(ctx->codec_type==AVMEDIA_TYPE_VIDEO){
          double elapsed=(now()-start)/1000.0;
          if(videoFrames>0&&elapsed-seconds>.12){lateFrames++;av_frame_unref(raw);continue;}
          auto target=std::chrono::steady_clock::now()+std::chrono::microseconds((int64_t)(std::max(0.0,seconds-elapsed)*1000000));
          while(!stop&&std::chrono::steady_clock::now()<target){auto left=target-std::chrono::steady_clock::now();std::this_thread::sleep_for(std::min(left,std::chrono::steady_clock::duration(std::chrono::milliseconds(5))));}
          if(stop)break;auto next=std::make_shared<VideoFrame>();next->width=raw->width;next->height=raw->height;next->pts=seconds;next->colorSpace=raw->colorspace;next->fullRange=raw->color_range==AVCOL_RANGE_JPEG;
          auto sar=raw->sample_aspect_ratio.num?raw->sample_aspect_ratio:fmt->streams[stream]->sample_aspect_ratio;if(sar.num>0&&sar.den>0)next->sar=av_q2d(sar);
          if(raw->format==AV_PIX_FMT_D3D11){
            auto retained=av_frame_clone(raw);if(!retained)throw std::bad_alloc();next->owner=std::shared_ptr<void>(retained,[](void* p){auto f=(AVFrame*)p;av_frame_free(&f);});next->texture=raw->data[0];next->textureSlice=(unsigned)(uintptr_t)raw->data[1];hardware=true;
          }else{
            next->pixels.resize((size_t)next->width*next->height*4);uint8_t* dst[]={next->pixels.data(),nullptr,nullptr,nullptr};int stride[]={next->width*4,0,0,0};
            scaler=sws_getCachedContext(scaler,raw->width,raw->height,(AVPixelFormat)raw->format,raw->width,raw->height,AV_PIX_FMT_BGRA,SWS_BICUBIC|SWS_ACCURATE_RND,nullptr,nullptr,nullptr);if(!scaler)throw std::runtime_error("Video conversion failed");sws_scale(scaler,raw->data,raw->linesize,0,raw->height,dst,stride);
          }
          {std::lock_guard<std::mutex> lock(mutex);frame=std::move(next);}if(firstFrameMs<0)firstFrameMs=now()-openedAt;position=seconds;videoFrames++;if(changed)changed();
        }
        else {if(!resampler){AVChannelLayout layout=AV_CHANNEL_LAYOUT_STEREO;if(swr_alloc_set_opts2(&resampler,&layout,AV_SAMPLE_FMT_S16,48000,&raw->ch_layout,(AVSampleFormat)raw->format,raw->sample_rate,0,nullptr)<0||swr_init(resampler)<0)throw std::runtime_error("Audio conversion failed");}int capacity=av_rescale_rnd(swr_get_delay(resampler,raw->sample_rate)+raw->nb_samples,48000,raw->sample_rate,AV_ROUND_UP);std::vector<uint8_t> pcm((size_t)capacity*4);uint8_t* output[]={pcm.data()};int n=swr_convert(resampler,output,capacity,(const uint8_t**)raw->extended_data,raw->nb_samples);if(n>0){pcm.resize((size_t)n*4);audio.play(std::move(pcm),stop);audioFrames++;if(!videoFrames)position=seconds;}}
        av_frame_unref(raw);
      }
    }
  }catch(const std::exception& e){if(!stop){std::lock_guard<std::mutex> lock(mutex);error=e.what();if(changed)changed();}}
  cleanup();
}
VideoFrame readbackFrame(const VideoFrame& image){
  if(!image.texture)return image;VideoFrame result=image;result.owner.reset();result.texture=nullptr;result.pixels.resize((size_t)image.width*image.height*4);auto raw=(AVFrame*)image.owner.get();auto cpu=av_frame_alloc();
  if(av_hwframe_transfer_data(cpu,raw,0)<0){av_frame_free(&cpu);throw std::runtime_error("Frame readback failed");}
  auto scaler=sws_getContext(image.width,image.height,(AVPixelFormat)cpu->format,image.width,image.height,AV_PIX_FMT_BGRA,SWS_BICUBIC|SWS_ACCURATE_RND,nullptr,nullptr,nullptr);if(!scaler){av_frame_free(&cpu);throw std::runtime_error("Frame readback conversion failed");}
  uint8_t* output[]={result.pixels.data()};int stride[]={image.width*4};sws_scale(scaler,cpu->data,cpu->linesize,0,image.height,output,stride);sws_freeContext(scaler);av_frame_free(&cpu);return result;
}
static uint32_t be32(const std::string& b,size_t p){if(p+4>b.size())throw std::runtime_error("Truncated MP4");return ((uint32_t)(uint8_t)b[p]<<24)|((uint8_t)b[p+1]<<16)|((uint8_t)b[p+2]<<8)|(uint8_t)b[p+3];}
static uint64_t be64(const std::string& b,size_t p){return ((uint64_t)be32(b,p)<<32)|be32(b,p+4);}
struct Box {size_t start,p,end;std::string type;};
static std::vector<Box> boxes(const std::string& b,size_t start,size_t end){std::vector<Box> r;for(size_t p=start;p+8<=end;){uint64_t n=be32(b,p);size_t header=8;if(n==1){n=be64(b,p+8);header=16;}if(!n)n=end-p;if(n<header||n>end-p)throw std::runtime_error("Incomplete MP4 box");r.push_back({p,p+header,p+(size_t)n,b.substr(p+4,4)});p+=n;}return r;}
Json Mp4Timeline::result() const {Json pairs=Json::array();for(auto p:ranges)pairs.push_back({p.first,p.second});int video=0,audio=0;for(auto& t:tracks){if(t.second.video)video++;else audio++;}return {{"ranges",pairs},{"metadata",{{"initialized",initialized},{"videoTracks",video},{"audioTracks",audio},{"width",width},{"height",height},{"duration",nullptr}}}};}
Json Mp4Timeline::append(const std::string& b,double offset){
  for(auto root:boxes(b,0,b.size())){
    if(root.type=="moov"){
      for(auto trak:boxes(b,root.p,root.end))if(trak.type=="trak"){
        uint32_t id=0;Track t;
        for(auto box:boxes(b,trak.p,trak.end)){
          if(box.type=="tkhd"){id=be32(b,box.p+((uint8_t)b[box.p]==1?20:12));t.width=be32(b,box.end-8)>>16;t.height=be32(b,box.end-4)>>16;}
          if(box.type=="mdia")for(auto md:boxes(b,box.p,box.end)){if(md.type=="mdhd")t.scale=be32(b,md.p+((uint8_t)b[md.p]==1?20:12));if(md.type=="hdlr")t.video=b.substr(md.p+8,4)=="vide";}
        }
        if(id&&t.scale){tracks[id]=t;if(t.video){width=t.width;height=t.height;}}
      }
      for(auto mvex:boxes(b,root.p,root.end))if(mvex.type=="mvex")for(auto trex:boxes(b,mvex.p,mvex.end))if(trex.type=="trex"){auto id=be32(b,trex.p+4);if(tracks.count(id))tracks[id].defaultDuration=be32(b,trex.p+12);}
      initialized=!tracks.empty();
    }
    if(root.type=="moof")for(auto traf:boxes(b,root.p,root.end))if(traf.type=="traf"){
      uint32_t id=0,duration=0;uint64_t decode=0;
      for(auto box:boxes(b,traf.p,traf.end)){
        if(box.type=="tfhd"){auto flags=be32(b,box.p)&0xffffff;id=be32(b,box.p+4);size_t p=box.p+8;if(flags&1)p+=8;if(flags&2)p+=4;duration=(flags&8)?be32(b,p):(tracks.count(id)?tracks[id].defaultDuration:0);}
        if(box.type=="tfdt")decode=(uint8_t)b[box.p]==1?be64(b,box.p+4):be32(b,box.p+4);
      }
      if(!tracks.count(id))throw std::runtime_error("Fragment before initialization");auto& t=tracks[id];double begin=INFINITY,end=-INFINITY;
      for(auto run:boxes(b,traf.p,traf.end))if(run.type=="trun"){
        auto flags=be32(b,run.p)&0xffffff,count=be32(b,run.p+4);if(count>100000)throw std::runtime_error("MP4 sample count limit");size_t p=run.p+8;if(flags&1)p+=4;if(flags&4)p+=4;
        for(uint32_t i=0;i<count;i++){uint32_t d=duration;if(flags&0x100){d=be32(b,p);p+=4;}if(flags&0x200)p+=4;if(flags&0x400)p+=4;int64_t cto=0;if(flags&0x800){uint32_t v=be32(b,p);cto=(uint8_t)b[run.p]==1?(int32_t)v:(int64_t)v;p+=4;}if(p>run.end||!d)throw std::runtime_error("Invalid MP4 sample");double a=((double)decode+cto)/t.scale+offset,z=a+(double)d/t.scale;begin=std::min(begin,a);end=std::max(end,z);decode+=d;}
      }
      if(std::isfinite(begin)&&end>begin){minimum=std::min(minimum,begin);maximum=std::max(maximum,end);ranges.emplace_back(begin,end);}
    }
  }
  std::sort(ranges.begin(),ranges.end());std::vector<std::pair<double,double>> merged;for(auto p:ranges){if(!merged.empty()&&p.first<=merged.back().second+.05)merged.back().second=std::max(merged.back().second,p.second);else merged.push_back(p);}ranges=std::move(merged);return result();
}
