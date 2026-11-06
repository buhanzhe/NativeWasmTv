#pragma once
#include "jni.h"
#include <stdexcept>
struct NativeCore {
  JNIEnv env; NativeValue owner; int64_t handle=0;
  explicit NativeCore(NativeHost* host,const std::string& boot,const std::string& url);
  ~NativeCore();
  void load(const std::string& html);
  void eval(const std::string& source);
  void css(const std::string& url,const std::string& text);
};
