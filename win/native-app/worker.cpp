#include "page.hpp"
Json Page::workerOperation(Json o){
  std::string op=o.at("op");
  if(op=="workerCreate"){
    if(workerSlot||workers.size()>=4)throw std::runtime_error("Worker nesting/count limit");auto target=o.value("url",url),base=o.value("base",url);bool supplied=o.contains("source")&&o["source"].is_string();std::string source=supplied?o["source"].get<std::string>():"";if(source.size()>4*1024*1024)throw std::runtime_error("Worker source budget");auto origin=[](const std::string& u){return u.substr(0,u.find('/',u.find("://")+3));};if(!supplied&&origin(target)!=origin(url))throw std::runtime_error("Worker origin mismatch");
    int id=++nextWorker;auto slot=std::make_unique<WorkerSlot>();slot->key=o.at("key");auto raw=slot.get();auto transport=http;workers[id]=std::move(slot);
    raw->thread=std::thread([raw,transport,base,target,source,supplied]{
      CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);
      try{
        Page child(target.rfind("blob:",0)==0?base:target,raw->stop,[]{},transport);child.workerSlot=raw;child.core=std::make_unique<NativeCore>(&child,resource(101),child.url);child.core->load("<!doctype html><body></body>");child.core->eval("__desktopWorkerEnter("+Json(child.url).dump()+")");auto text=source;
        if(!supplied){auto response=child.fetch({{"url",target},{"kind","script"},{"sync",true},{"byteLimit",4*1024*1024}});if(response["status"]!=200)throw std::runtime_error("Worker script HTTP failed");text=response["text"];}
        child.core->eval(text);
        while(!raw->stop){std::deque<Json> pending;{std::lock_guard<std::mutex> guard(raw->mutex);pending.swap(raw->incoming);raw->inputBytes=0;}for(auto& packet:pending){if(raw->stop)break;child.core->eval("__desktopWorkerReceive("+packet.dump()+")");}child.tick();if(!child.error.empty())throw std::runtime_error(child.error);Sleep(10);}
      }catch(const std::exception& e){std::lock_guard<std::mutex> guard(raw->mutex);raw->outgoing.push_back({{"kind","error"},{"data",e.what()}});}
      CoUninitialize();
    });return id;
  }
  if(op=="workerSend"){
    auto it=workers.find(o.value("id",0));if(it==workers.end())return false;auto& slot=*it->second;auto data=o.at("data");size_t bytes=data.dump().size();std::lock_guard<std::mutex> guard(slot.mutex);if(bytes>24*1024*1024||slot.incoming.size()>=1024||slot.inputBytes+bytes>32*1024*1024)throw std::runtime_error("Worker queue budget");slot.inputBytes+=bytes;slot.incoming.push_back(data);return true;
  }
  if(op=="workerTerminate"){workers.erase(o.value("id",0));return true;}
  if(!workerSlot)throw std::runtime_error("Not in worker realm");
  if(op=="workerClose"){workerSlot->stop=true;return true;}
  if(op=="workerImport"){auto response=fetch({{"url",o.at("url")},{"sync",true},{"byteLimit",4*1024*1024}});if(response["status"]!=200)throw std::runtime_error("Worker import HTTP failed");return {{"text",response["text"]}};}
  if(op=="workerEmit"){auto data=o.at("data");size_t bytes=data.dump().size();std::lock_guard<std::mutex> guard(workerSlot->mutex);if(bytes>24*1024*1024||workerSlot->outgoing.size()>=1024||workerSlot->outputBytes+bytes>32*1024*1024)throw std::runtime_error("Worker response queue budget");workerSlot->outputBytes+=bytes;workerSlot->outgoing.push_back({{"kind","message"},{"data",data}});return true;}
  throw std::runtime_error("Unknown worker operation");
}
void Page::tickWorkers(){for(auto& entry:workers){auto& slot=*entry.second;std::deque<Json> pending;{std::lock_guard<std::mutex> guard(slot.mutex);pending.swap(slot.outgoing);slot.outputBytes=0;}for(auto& message:pending)core->eval("__desktopWorkerDelivery("+Json(slot.key).dump()+","+message.at("kind").dump()+","+message.at("data").dump()+")");}}
