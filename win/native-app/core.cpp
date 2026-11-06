#include "core.hpp"
#define NTV_DESKTOP_HOST 1
#define JNI_OnLoad ntv_unused_jni_onload
#ifdef NTV_PACKED_ASSETS
#include "protected_bridge.inc"
#else
#include "../../../browser-core/native/litebrowser/bridge.cpp"
#endif
extern "C" int ntv_host_allowed(void){return 1;}
extern "C" void ntv_host_require(JNIEnv*){}
void ntv_host_initialize(JNIEnv*,jclass,jobject){}
extern "C" int ntv_tls_register(JNIEnv*){return JNI_OK;}
NativeCore::NativeCore(NativeHost* host,const std::string& boot,const std::string& url){
  owner.host=host; NativeValue b,u;b.data=boot;u.data=url;
  handle=core_create(&env,nullptr,&owner,&b,&u,1280,720);
  if(!handle)throw std::runtime_error("Native core initialization failed");
}
NativeCore::~NativeCore(){if(handle)core_destroy(&env,nullptr,handle);}
void NativeCore::load(const std::string& html){NativeValue v;v.data=html;core_load(&env,nullptr,handle,&v);}
void NativeCore::eval(const std::string& source){NativeValue v;v.data=source;auto name=env.NewStringUTF("native-host");core_evaluate(&env,nullptr,handle,&v,name);env.DeleteLocalRef(name);}
void NativeCore::css(const std::string& url,const std::string& text){NativeValue u,t;u.data=url;t.data=text;core_css(&env,nullptr,handle,&u,&t);}
