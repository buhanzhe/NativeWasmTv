#pragma once
// Source compatibility for the existing core bridge. These are ordinary C++
// values and direct virtual calls; no JVM, Java DLL or JNI runtime is linked.
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include <cstdarg>
#include <algorithm>
using jint=int; using jlong=int64_t; using jbyte=signed char; using jboolean=unsigned char;
using jfloat=float; using jdouble=double; using jsize=int;
struct NativeHost {
  virtual std::string host(const std::string&)=0;
  virtual std::string take(const std::string&)=0;
  virtual std::string store(const std::string&)=0;
  virtual std::string append(int,const std::string&,double)=0;
  virtual bool cancelled()=0;
  virtual ~NativeHost()=default;
};
struct NativeValue { std::string data; std::vector<float> floats; NativeHost* host=nullptr; int refs=1; };
using jobject=NativeValue*; using jstring=jobject; using jclass=jobject;
using jbyteArray=jobject; using jfloatArray=jobject; using jmethodID=const char*;
#define JNIEXPORT
#define JNICALL
#define JNI_OK 0
#define JNI_ERR -1
#define JNI_ABORT 2
#define JNI_VERSION_1_6 0x00010006
struct JNINativeMethod { char* name; char* signature; void* fnPtr; };
inline NativeValue* nativeValue(std::string s={}) { auto v=new NativeValue; v->data=std::move(s); return v; }
struct JNIEnv {
  bool failed=false;
  jclass FindClass(const char*) { return nativeValue(); }
  jclass GetObjectClass(jobject) { return nativeValue(); }
  jmethodID GetMethodID(jclass,const char* name,const char*) { return name; }
  jobject NewGlobalRef(jobject o) { if(o)o->refs++; return o; }
  void DeleteLocalRef(jobject o) { if(o && !--o->refs)delete o; }
  void DeleteGlobalRef(jobject o) { DeleteLocalRef(o); }
  jstring NewStringUTF(const char* s) { return nativeValue(s?s:""); }
  jbyteArray NewByteArray(jsize n) { return nativeValue(std::string(n,'\0')); }
  jsize GetArrayLength(jobject a) { return a?(a->floats.empty()?a->data.size():a->floats.size()):0; }
  void SetByteArrayRegion(jobject a,int at,int n,const jbyte* p) { if(n)std::memcpy(a->data.data()+at,p,n); }
  void GetByteArrayRegion(jobject a,int at,int n,jbyte* p) { if(n)std::memcpy(p,a->data.data()+at,n); }
  jbyte* GetByteArrayElements(jobject a,jboolean*) { return (jbyte*)a->data.data(); }
  void ReleaseByteArrayElements(jobject,jbyte*,int) {}
  void GetFloatArrayRegion(jobject a,int at,int n,float* p) { std::copy_n(a->floats.data()+at,n,p); }
  bool ExceptionCheck() { return failed; }
  void ExceptionClear() { failed=false; }
  int ThrowNew(jclass,const char*) { failed=true; return -1; }
  int RegisterNatives(jclass,JNINativeMethod*,int) { return -1; }
  jobject NewObject(jclass,jmethodID,jobject bytes,jobject) { return nativeValue(bytes->data); }
  jobject CallObjectMethod(jobject obj,jmethodID method,...) {
    va_list args;va_start(args,method);jobject result=nullptr;
    try {
      std::string m=method;
      if(m=="getBytes")result=nativeValue(obj->data);
      else if(m=="host"){auto s=va_arg(args,jobject);result=nativeValue(obj->host->host(s->data));}
      else if(m=="takeBinary"){auto s=va_arg(args,jobject);result=nativeValue(obj->host->take(s->data));}
      else if(m=="storeBinary"){auto s=va_arg(args,jobject);result=nativeValue(obj->host->store(s->data));}
      else if(m=="appendMedia"){int id=va_arg(args,int);auto b=va_arg(args,jobject);double offset=va_arg(args,double);result=nativeValue(obj->host->append(id,b->data,offset));}
      else if(m=="metrics"){double size=va_arg(args,double);result=nativeValue();result->floats={(float)size*.8f,(float)size*.2f,(float)size*.5f,(float)size*.5f};}
    }catch(...){failed=true;}
    va_end(args);return result;
  }
  jint CallIntMethod(jobject,jmethodID method,...) { return std::string(method)=="dateTimezoneOffset"?-480:6000; }
  jlong CallLongMethod(jobject,jmethodID,...) { return 96LL*1024*1024; }
  jboolean CallBooleanMethod(jobject obj,jmethodID method,...) { return std::string(method)=="isCancelled"&&obj->host->cancelled(); }
  jfloat CallFloatMethod(jobject,jmethodID,jobject text,double size,int,int) { return text->data.size()*size*.5f; }
};
struct JavaVM { int GetEnv(void**,int){return JNI_ERR;} };
