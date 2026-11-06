#include "page.hpp"
#include <regex>
#include <cmath>
#include "system.hpp"
static std::string urlHost(const std::string& url){auto start=url.find("://");if(start==url.npos)return {};start+=3;return url.substr(start,url.find_first_of("/:?#",start)-start);}
Page::Page(std::string source,std::atomic<bool>& stop,std::function<void()> notify,std::shared_ptr<Http> transport,Page* owner,int id):http(transport?transport:std::make_shared<Http>()),stopping(stop),changed(std::move(notify)),parent(owner),frameId(id){
  if(source.rfind("webview://",0)==0)source.erase(0,10);
  auto mark=source.find('?');if(mark!=source.npos){auto end=source.find('#',mark);auto tail=end==source.npos?std::string():source.substr(end);std::istringstream query(source.substr(mark+1,(end==source.npos?source.size():end)-mark-1));std::string param,clean;while(std::getline(query,param,'&')){auto equal=param.find('=');if(percent(param.substr(0,equal))=="ujs"){if(selection.empty()&&equal!=param.npos)selection=unbase64(percent(param.substr(equal+1)));}else{if(!clean.empty())clean+='&';clean+=param;}}source=source.substr(0,mark)+(clean.empty()?"":"?"+clean)+tail;}
  mark=source.find("#ujs=");if(mark!=source.npos){selection=unbase64(percent(source.substr(mark+5)));source.resize(mark);}if(selection.size()>65536)throw std::runtime_error("UJS exceeds 64 KiB");
  url=source;pending=!selection.empty();auto h=urlHost(url);original=h=="web.guangdianyun.tv"||h=="tv.gxtv.cn"||h=="cctv.com"||h.find(".cctv.com")!=h.npos||h.find("cntv.cn")!=h.npos||h.find("yangshipin.cn")!=h.npos||(parent&&parent->original);
}
Page::~Page(){for(auto& b:buffers)if(b.second.pipe)b.second.pipe->close();workers.clear();direct.reset();buffers.clear();children.clear();core.reset();requests.clear();}
std::string Page::take(const std::string& key){auto it=binary.find(key);if(it==binary.end())throw std::runtime_error("Binary response expired");auto value=std::move(it->second);binaryBytes-=value.size();binary.erase(it);return value;}
std::string Page::store(const std::string& data){if(binaryBytes+data.size()>16*1024*1024||binary.size()>256)throw std::runtime_error("Binary queue quota exceeded");auto key=std::to_string(++nextBinary);binary[key]=data;binaryBytes+=data.size();return key;}
Json Page::fetch(Json o){std::string body,input;if(o.contains("bodyKey"))input=take(o["bodyKey"]);auto reply=http->fetch(o,url,body,input);if(o.value("binary",false))reply["_binary"]=std::move(body);else if(o.value("kind",std::string())=="script"&&!o.value("sync",false))reply["_script"]=std::move(body);else reply["text"]=std::move(body);return reply;}
void Page::load(){auto response=fetch({{"url",url},{"byteLimit",2*1024*1024}});int status=response["status"];if(status<200||status>=400)throw std::runtime_error("Webpage HTTP "+std::to_string(status));url=response["url"];core=std::make_unique<NativeCore>(this,resource(101),url);core->load(response.value("text",std::string()));}
Json Page::ranges(){Json values=Json::object();for(auto& it:buffers)values[std::to_string(it.first)]=it.second.timeline.result();return values;}
std::string Page::append(int id,const std::string& bytes,double offset){try{auto it=buffers.find(id);if(it==buffers.end()||bytes.empty()||bytes.size()>8*1024*1024)throw std::runtime_error("Invalid SourceBuffer append");auto result=it->second.timeline.append(bytes,offset);it->second.pipe->push(bytes);return result.dump();}catch(const std::exception& e){return Json({{"error",e.what()}}).dump();}}
Decoder* Page::activeDecoder(){if(direct)return direct.get();for(auto& it:buffers)if(it.second.decoder&&it.second.decoder->videoFrames)return it.second.decoder.get();for(auto& it:children)if(auto d=it.second->activeDecoder())return d;for(auto& it:buffers)if(it.second.decoder)return it.second.decoder.get();return nullptr;}
int Page::audioFrameCount(){int total=direct?direct->audioFrames.load():0;for(auto& b:buffers)if(b.second.decoder)total+=b.second.decoder->audioFrames.load();for(auto& child:children)total+=child.second->audioFrameCount();return total;}
void Page::pause(bool value){if(direct)direct->paused=value;for(auto& b:buffers)if(b.second.decoder)b.second.decoder->paused=value;for(auto& child:children)child.second->pause(value);}
void Page::tick(){
  for(auto it=images.begin();it!=images.end();){
    if(it->second.wait_for(std::chrono::milliseconds(0))!=std::future_status::ready){++it;continue;}
    auto target=it->first;auto bytes=it->second.get();it=images.erase(it);int w=0,h=0;
    if(!bytes.empty()){
      if(!canvas)canvas=std::make_unique<NativeCanvas>();IStream* stream=nullptr;CreateStreamOnHGlobal(nullptr,TRUE,&stream);ULONG written=0;stream->Write(bytes.data(),bytes.size(),&written);LARGE_INTEGER zero{};stream->Seek(zero,STREAM_SEEK_SET,nullptr);
      {Gdiplus::Bitmap image(stream);if(image.GetLastStatus()==Gdiplus::Ok&&(uint64_t)image.GetWidth()*image.GetHeight()<=4*1024*1024){w=image.GetWidth();h=image.GetHeight();}}
      stream->Release();
    }
    imageSizes[target]={w,h};core->eval((w&&h?"__imageDone(":"__imageError(")+Json(target).dump()+(w&&h?","+std::to_string(w)+","+std::to_string(h):"")+")");
  }
  for(auto it=styles.begin();it!=styles.end();){if(it->second.second.wait_for(std::chrono::milliseconds(0))!=std::future_status::ready){++it;continue;}auto url=it->second.first;auto result=it->second.second.get();it=styles.erase(it);bool success=result.value("status",0)==200;if(success)core->css(url,result.value("text",std::string()));core->eval("__cssDone("+Json(url).dump()+","+(success?"true":"false")+")");}
  if(!navigate.empty()){auto next=std::move(navigate);navigate.clear();core.reset();buffers.clear();direct.reset();url=next;load();}
  core->eval("__tick()");
  tickWorkers();
  for(auto& it:children)if(!it.second->core){try{it.second->load();}catch(const std::exception& e){it.second->error=e.what();}}else it.second->tick();
  if(!pending&&!direct&&buffers.empty()&&!candidates.empty()){
    // Only an actual selected media element is admitted; requests alone never
    // become playback candidates. HLS keeps its webpage/MSE owner alive.
    auto target=*candidates.begin();direct=std::make_unique<Decoder>(target,"Referer: "+url+"\r\n",nullptr,changed);
  }
  if(now()>=feedbackAt)if(auto decoder=activeDecoder()){double time=decoder->position.load();if(decoder->videoFrames||decoder->audioFrames){core->eval("__mediaTime("+std::to_string(time)+",0,0)");feedbackAt=now()+100;}}
}
std::string Page::host(const std::string& request){try{return operation(Json::parse(request)).dump(-1,' ',false,Json::error_handler_t::replace);}catch(const std::exception& e){return Json({{"error",e.what()}}).dump();}}
Json Page::operation(Json o){
  std::string op=o.value("op",std::string());
#if NTV_DIAGNOSTICS
  if(op=="probeState"){trace.push_back({{"state",o.at("value")},{"workers",workers.size()}});return true;}
#endif
  if(op=="clock")return now();
  if(op=="browserInfo")return {{"userAgent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/110.0.0.0 Safari/537.36"},{"platform","Win32"},{"networkConcurrency",8},{"hardwareConcurrency",4},{"workersAvailable",!workerSlot},{"nativeHlsPlayback",!original},{"nativeHlsEmulation",!original},{"pageHost",urlHost(url)},{"selectionConfigured",!selection.empty()},{"secureContext",url.rfind("https:",0)==0},{"diagnostic",false},{"originalMediaPipeline",original}};
  if(op=="pageConfig")return {{"headless",true},{"selectionScript",selection},{"selectionRequired",pending},{"isFrame",parent!=nullptr},{"nativeWindows",false},{"originalMediaPipeline",original},{"clockOffsetMs",0},{"legacyMediaWorkers",urlHost(url).find("yangshipin.cn")!=std::string::npos}};
  if(op=="resolve")return combine(o.value("url",std::string()),o.value("base",url));
  if(op=="nativeHlsLibrary")return resource(102);
  if(op=="nativeHlsSource")return true;
  if(op=="requireSelection")return pending;
  if(op=="selectionReady"){pending=false;return true;}
  if(op=="pendingPageWork")return !requests.empty();
  if(op=="requestSync"){o["sync"]=true;auto r=fetch(o);if(r.contains("_binary")){r["binaryKey"]=store(r["_binary"]);r.erase("_binary");}return r;}
  if(op=="request"){
#if NTV_DIAGNOSTICS
    if(trace.size()<100)trace.push_back({{"request",o.value("url",std::string())},{"kind",o.value("kind",std::string())}});
#endif
    if(requests.size()>=96)throw std::runtime_error("Request queue limit");int id=o.at("id");std::string input;if(o.contains("bodyKey"))input=take(o["bodyKey"]);auto base=url;auto transport=http;
    requests.emplace(id,Request{std::async(std::launch::async,[o,base,transport,input]{try{std::string body;auto r=transport->fetch(o,base,body,input);if(o.value("binary",false))r["_binary"]=std::move(body);else if(o.value("kind",std::string())=="script")r["_script"]=std::move(body);else r["text"]=std::move(body);return r;}catch(const std::exception& e){return Json({{"error",e.what()}});}}),false});return nullptr;
  }
  if(op=="takeHttpCompletion"){
    for(auto it=requests.begin();it!=requests.end();){if(it->second.future.wait_for(std::chrono::milliseconds(0))!=std::future_status::ready){++it;continue;}int id=it->first;bool aborted=it->second.aborted;auto r=it->second.future.get();it=requests.erase(it);if(aborted)continue;for(auto pair:{std::pair<const char*,const char*>{"_binary","binaryKey"},{"_script","scriptKey"}})if(r.contains(pair.first)){r[pair.second]=store(r[pair.first]);r.erase(pair.first);}return {{"id",id},{"response",r}};}return nullptr;
  }
  if(op=="abortRequest"){auto it=requests.find(o.value("id",0));if(it!=requests.end())it->second.aborted=true;return true;}
  if(op=="discardBinary"){auto key=o.value("key",std::string());if(binary.count(key))take(key);return true;}
  if(op=="media"){auto value=o.value("value",std::string());if(!pending&&value.rfind("blob:",0)!=0&&!value.empty()&&!std::regex_search(value,std::regex("\\.m3u8(?:[?#]|$)",std::regex::icase)))candidates.insert(combine(value,url));return nullptr;}
  if(op=="mseAdd"){int id=o.at("id");if(buffers.size()>=8)throw std::runtime_error("SourceBuffer limit");auto& b=buffers[id];b.pipe=std::make_shared<MediaPipe>();b.decoder=std::make_unique<Decoder>("","",b.pipe,changed);return true;}
  if(op=="mseRanges")return ranges();
  if(op=="mseStats"){size_t bytes=0;for(auto& b:buffers){std::lock_guard<std::mutex> lock(b.second.pipe->mutex);bytes+=b.second.pipe->bytes;}return {{"bytes",bytes},{"limitBytes",16*1024*1024}};}
  if(op=="mseRemove"){int id=o.at("id");auto& timeline=buffers.at(id).timeline;double a=o.at("start"),z=o.at("end");std::vector<std::pair<double,double>> keep;for(auto p:timeline.ranges){if(p.second<=a||p.first>=z)keep.push_back(p);else {if(p.first<a)keep.push_back({p.first,a});if(p.second>z)keep.push_back({z,p.second});}}timeline.ranges=std::move(keep);return timeline.result();}
  if(op=="mseEnd"){for(auto& b:buffers)b.second.pipe->close();return {{"buffers",ranges()}};}
  if(op=="mseDetach"||op=="mseReplace"){buffers.clear();return true;}
  if(op=="mseWindow"||op=="mseAttach")return true;
  if(op=="frame"){int id=o.at("id");if(children.size()>=4||parent&&parent->parent)throw std::runtime_error("Frame depth/count limit");auto target=combine(o.at("url"),url);children[id]=std::make_unique<Page>(target,stopping,changed,http,this,id);return true;}
  if(op=="postParent"||op=="postFrame"){Page* target=op=="postParent"?parent:(children.count(o.value("id",0))?children.at(o.value("id",0)).get():nullptr);if(!target||!target->core)return false;auto origin=url.substr(0,url.find('/',url.find("://")+3));auto allowed=o.value("targetOrigin",std::string("/"));auto targetOrigin=target->url.substr(0,target->url.find('/',target->url.find("://")+3));if(allowed!="*"&&allowed!=targetOrigin&&!(allowed=="/"&&origin==targetOrigin))return false;target->core->eval("__frameMessage("+std::to_string(op=="postParent"?frameId:0)+","+Json(origin).dump()+","+o["data"].dump()+")");return true;}
  if(op=="navigate"){navigate=combine(o.at("value"),url);return nullptr;}
  if(op=="history")return url;
  if(op=="cookieGet"){DWORD n=0;InternetGetCookieExW(wide(url).c_str(),nullptr,nullptr,&n,0,nullptr);std::wstring cookies(n,0);if(n&&InternetGetCookieExW(wide(url).c_str(),nullptr,cookies.data(),&n,0,nullptr))return narrow(cookies.c_str());return "";}
  if(op=="cookieSet"){InternetSetCookieW(wide(url).c_str(),nullptr,wide(o.value("value",std::string())).c_str());return nullptr;}
  if(op=="storage"){auto action=o.value("action",std::string()),key=o.value("key",std::string());if(action=="get")return storage.count(key)?Json(storage[key]):Json();if(action=="keys"){Json result=Json::array();for(auto& p:storage)result.push_back(p.first);return result;}if(action=="set"){if(storage.size()>512)throw std::runtime_error("Storage quota");storage[key]=o.value("value",std::string());}if(action=="remove")storage.erase(key);if(action=="clear")storage.clear();return nullptr;}
  if(op=="module"){auto r=fetch({{"url",o.at("url")},{"sync",true}});if(r.at("status")!=200)throw std::runtime_error("Module HTTP failed");return r.value("text",std::string());}
  if(op=="randomBytes"){int n=o.value("length",0);if(n<0||n>65536)throw std::runtime_error("Random quota");std::string bytes(n,0);BCryptGenRandom(nullptr,(BYTE*)bytes.data(),n,BCRYPT_USE_SYSTEM_PREFERRED_RNG);return store(bytes);}
  if(op=="randomUUID"){GUID guid;CoCreateGuid(&guid);wchar_t s[40];StringFromGUID2(guid,s,40);auto v=narrow(s);return v.substr(1,v.size()-2);}
  if(op=="subtleCrypto")return crypto(o);
  if(op=="intlDate")return nativeDate(o);
  if(op=="parseXML")return nativeXml(o.at("text"));
  if(op=="idbStorage"){o["_owner"]=std::to_string((uintptr_t)this);return nativeDatabase(url.substr(0,url.find('/',url.find("://")+3)),o);}
  if(op.rfind("worker",0)==0)return workerOperation(o);
  if(op=="canvas"){if(!canvas)canvas=std::make_unique<NativeCanvas>();return canvas->call(o,[&](auto& b){return store(b);},[&](auto& k){return take(k);});}
  if(op=="css"){auto target=combine(o.at("url"),url);if(styles.size()>=32)return "";auto transport=http;styles.emplace(++nextStyle,std::make_pair(target,std::async(std::launch::async,[transport,target]{try{std::string text;auto result=transport->fetch({{"url",target}},target,text);result["text"]=std::move(text);return result;}catch(...){return Json({{"status",0}});}})));return "";}
  if(op=="image"||op=="imageSize"){
    auto target=combine(o.value("url",std::string()),o.value("base",url));auto known=imageSizes.find(target);if(known!=imageSizes.end())return {known->second.first,known->second.second};
    if(images.size()+imageSizes.size()<64&&!images.count(target)){auto transport=http;auto base=url;images[target]=std::async(std::launch::async,[transport,target,base]{try{std::string bytes;auto result=transport->fetch({{"url",target},{"kind","image"},{"byteLimit",4*1024*1024}},base,bytes);if(result["status"]==200)return bytes;}catch(...){}return std::string();});}
    return {0,0};
  }
  if(op=="viewportState")return {{"scroll",0}};
  if(op=="navigationTiming")return {{"clock",now()}};
  if(op=="error"){error=o.value("value",std::string());
#if NTV_DIAGNOSTICS
OutputDebugStringW(wide(error).c_str());
#endif
return nullptr;}
  if(op=="log"){
#if NTV_DIAGNOSTICS
if(messages.size()<50)messages.push_back(o.value("value",std::string()).substr(0,2000));
#endif
return nullptr;}
  if(op=="mseFailure"){if(messages.size()<60)messages.push_back("Media: "+o.value("value",std::string()));return nullptr;}
  if(op=="title"||op=="documentReady"||op=="scriptMetric"||op=="scroll"||op=="mediaControl"||op=="moduleDependencies"||op=="desktopPlaybackSource"||op=="mseFailure")return nullptr;
  unsupported.insert(op);return {{"error","NotSupportedError: native host operation "+op}};
}
Json Page::crypto(Json o){
  auto action=o.value("action",std::string()),algorithm=o.value("algorithm",std::string());
  auto bytesResult=[&](std::string bytes)->Json{return {{"binaryKey",store(bytes)}};};
  if(action=="digest"){
    const wchar_t* name=algorithm=="SHA-1"?BCRYPT_SHA1_ALGORITHM:algorithm=="SHA-256"?BCRYPT_SHA256_ALGORITHM:algorithm=="SHA-384"?BCRYPT_SHA384_ALGORITHM:algorithm=="SHA-512"?BCRYPT_SHA512_ALGORITHM:nullptr;if(!name)throw std::runtime_error("Unsupported digest");auto input=take(o.at("dataKey"));BCRYPT_ALG_HANDLE alg=nullptr;BCRYPT_HASH_HANDLE hash=nullptr;DWORD length=0,n=0;BCryptOpenAlgorithmProvider(&alg,name,nullptr,0);BCryptGetProperty(alg,BCRYPT_HASH_LENGTH,(BYTE*)&length,sizeof(length),&n,0);std::string output(length,0);BCryptCreateHash(alg,&hash,nullptr,0,nullptr,0,0);auto status=BCryptHashData(hash,(BYTE*)input.data(),input.size(),0);if(status>=0)status=BCryptFinishHash(hash,(BYTE*)output.data(),output.size(),0);BCryptDestroyHash(hash);BCryptCloseAlgorithmProvider(alg,0);if(status<0)throw std::runtime_error("Digest failed");return bytesResult(output);
  }
  if(action=="importKey"||action=="generateKey"){
    if(algorithm!="AES-CBC")throw std::runtime_error("Unsupported key algorithm");std::string material;if(action=="importKey")material=take(o.at("dataKey"));else{int bits=o.at("length");if(bits!=128&&bits!=192&&bits!=256)throw std::runtime_error("Invalid key length");material.resize(bits/8);BCryptGenRandom(nullptr,(BYTE*)material.data(),material.size(),BCRYPT_USE_SYSTEM_PREFERRED_RNG);}if(material.size()!=16&&material.size()!=24&&material.size()!=32)throw std::runtime_error("Invalid AES key");std::string id=std::to_string(keys.size()+1);if(keys.size()>=128)throw std::runtime_error("Key quota");keys[id]={material,o};return {{"key",{{"id",id},{"type","secret"},{"extractable",o.value("extractable",false)},{"usages",o.at("usages")},{"algorithm",{{"name","AES-CBC"},{"length",material.size()*8}}}}}};
  }
  auto& entry=keys.at(o.at("key"));if(action=="exportKey"){if(!entry.second.value("extractable",false))throw std::runtime_error("Key not extractable");return bytesResult(entry.first);}
  if(action!="encrypt"&&action!="decrypt")throw std::runtime_error("Unsupported crypto action");bool allowed=false;for(auto& u:entry.second.at("usages"))if(u==action)allowed=true;if(!allowed)throw std::runtime_error("Invalid key usage");auto iv=take(o.at("ivKey")),input=take(o.at("dataKey"));if(iv.size()!=16)throw std::runtime_error("Invalid AES IV");
  BCRYPT_ALG_HANDLE alg=nullptr;BCRYPT_KEY_HANDLE key=nullptr;BCryptOpenAlgorithmProvider(&alg,BCRYPT_AES_ALGORITHM,nullptr,0);BCryptSetProperty(alg,BCRYPT_CHAINING_MODE,(BYTE*)BCRYPT_CHAIN_MODE_CBC,sizeof(BCRYPT_CHAIN_MODE_CBC),0);BCryptGenerateSymmetricKey(alg,&key,nullptr,0,(BYTE*)entry.first.data(),entry.first.size(),0);std::string output(input.size()+16,0);ULONG n=0;auto fn=action=="encrypt"?BCryptEncrypt:BCryptDecrypt;auto status=fn(key,(BYTE*)input.data(),input.size(),nullptr,(BYTE*)iv.data(),iv.size(),(BYTE*)output.data(),output.size(),&n,BCRYPT_BLOCK_PADDING);BCryptDestroyKey(key);BCryptCloseAlgorithmProvider(alg,0);if(status<0)throw std::runtime_error("AES operation failed");output.resize(n);return bytesResult(output);
}
