#pragma once
#include "common.hpp"
#include "core.hpp"
#include "media.hpp"
#include <future>
#include <set>
#include <deque>
#include "canvas.hpp"
struct Page:NativeHost {
  struct WorkerSlot {
    std::atomic<bool> stop{false};std::thread thread;std::mutex mutex;
    std::deque<Json> incoming,outgoing;std::string key;size_t inputBytes=0,outputBytes=0;
    ~WorkerSlot(){stop=true;if(thread.joinable())thread.join();}
  };
  struct Request {std::future<Json> future;bool aborted=false;};
  struct Buffer {Mp4Timeline timeline;std::shared_ptr<MediaPipe> pipe;std::unique_ptr<Decoder> decoder;};
  std::shared_ptr<Http> http;std::atomic<bool>& stopping;std::function<void()> changed;
  std::string url,selection,navigate,error;bool pending=false,original=false;Page* parent=nullptr;int frameId=0;
  std::unique_ptr<NativeCore> core;std::map<std::string,std::string> binary;size_t binaryBytes=0;unsigned nextBinary=0;
  std::map<int,Request> requests;std::map<int,std::unique_ptr<Page>> children;std::map<int,Buffer> buffers;
  std::map<std::string,std::string> storage;std::set<std::string> candidates;std::unique_ptr<Decoder> direct;
  std::map<std::string,std::pair<std::string,Json>> keys;
  std::set<std::string> unsupported;
  std::unique_ptr<NativeCanvas> canvas;
  std::map<int,std::pair<std::string,std::future<Json>>> styles;int nextStyle=0;
  std::map<int,std::unique_ptr<WorkerSlot>> workers;int nextWorker=0;WorkerSlot* workerSlot=nullptr;
  std::map<std::string,std::future<std::string>> images;
  std::map<std::string,std::pair<int,int>> imageSizes;
  Json trace=Json::array(),messages=Json::array();
  int64_t feedbackAt=0;
  Page(std::string source,std::atomic<bool>& stop,std::function<void()> notify,std::shared_ptr<Http> transport={},Page* owner=nullptr,int id=0);
  ~Page();void load();void tick();
  bool cancelled()override{return stopping;}
  std::string take(const std::string& key)override;
  std::string store(const std::string& bytes)override;
  std::string append(int id,const std::string& bytes,double offset)override;
  std::string host(const std::string& request)override;
  Json operation(Json o);Json fetch(Json o);Json crypto(Json o);
  Json ranges();Decoder* activeDecoder();
  int audioFrameCount();void pause(bool value);
  Json workerOperation(Json request);void tickWorkers();
};
