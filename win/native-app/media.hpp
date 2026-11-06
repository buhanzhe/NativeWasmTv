#pragma once
#include "common.hpp"
#include <deque>
#include <condition_variable>
#include <cmath>
struct MediaPipe {
  std::mutex mutex;std::condition_variable cv;std::deque<std::string> chunks;size_t offset=0,bytes=0;bool closed=false;
  void push(const std::string& data){std::lock_guard<std::mutex> lock(mutex);if(closed)throw std::runtime_error("Media closed");if(bytes+data.size()>16*1024*1024)throw std::runtime_error("QuotaExceededError");chunks.push_back(data);bytes+=data.size();cv.notify_all();}
  void close(){std::lock_guard<std::mutex> lock(mutex);closed=true;cv.notify_all();}
  int read(uint8_t* out,int count);
};
struct VideoFrame {int width=0,height=0;std::vector<uint8_t> pixels;double pts=0,sar=1;void* texture=nullptr;unsigned textureSlice=0;std::shared_ptr<void> owner;int colorSpace=1;bool fullRange=false;};
VideoFrame readbackFrame(const VideoFrame& frame);
struct Decoder {
  std::atomic<bool> stop{false},paused{false};std::atomic<double> position{0};std::atomic<int> videoFrames{0},audioFrames{0};
  std::shared_ptr<MediaPipe> pipe;std::thread worker;std::mutex mutex;std::shared_ptr<const VideoFrame> frame;std::string error;
  std::atomic<int64_t> firstFrameMs{-1};std::atomic<int> lateFrames{0};int64_t openedAt=0;std::atomic<bool> hardware{false};
  std::function<void()> changed;
  Decoder(const std::string& url,const std::string& headers,std::shared_ptr<MediaPipe> input,std::function<void()> notify);
  ~Decoder();
  void run(std::string url,std::string headers);
};
struct Mp4Timeline {
  struct Track {uint32_t scale=0,defaultDuration=0;bool video=false;int width=0,height=0;};
  std::map<uint32_t,Track> tracks;std::vector<std::pair<double,double>> ranges;double minimum=INFINITY,maximum=-INFINITY;bool initialized=false;int width=0,height=0;
  Json append(const std::string& data,double offset);
  Json result() const;
};
