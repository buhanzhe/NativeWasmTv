#pragma once
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#ifndef UNICODE
#define UNICODE
#endif
#include <windows.h>
#include <objbase.h>
#include <wininet.h>
#include <wincrypt.h>
#include <bcrypt.h>
#include <string>
#include <vector>
#include <map>
#include <memory>
#include <mutex>
#include <atomic>
#include <thread>
#include <chrono>
#include <stdexcept>
#include <fstream>
#include <sstream>
#include <functional>
#include "vendor/json.hpp"
using Json=nlohmann::json;
inline std::wstring wide(const std::string& s){int n=MultiByteToWideChar(CP_UTF8,0,s.data(),s.size(),nullptr,0);std::wstring r(n,0);MultiByteToWideChar(CP_UTF8,0,s.data(),s.size(),r.data(),n);return r;}
inline std::string narrow(const std::wstring& s){int n=WideCharToMultiByte(CP_UTF8,0,s.data(),s.size(),nullptr,0,nullptr,nullptr);std::string r(n,0);WideCharToMultiByte(CP_UTF8,0,s.data(),s.size(),r.data(),n,nullptr,nullptr);return r;}
inline int64_t now(){return GetTickCount64();}
std::string resource(int id);
inline std::string combine(const std::string& url,const std::string& base){
  if(url.find(':')<url.find('/'))return url;
  DWORD size=32768;std::wstring out(size,0);if(!InternetCombineUrlW(wide(base).c_str(),wide(url).c_str(),out.data(),&size,ICU_NO_ENCODE))throw std::runtime_error("Invalid URL");out.resize(size);return narrow(out);
}
inline std::string percent(const std::string& s){std::string r;for(size_t i=0;i<s.size();i++){if(s[i]=='%'&&i+2<s.size()){char h[]={s[i+1],s[i+2],0};char* end;int v=strtol(h,&end,16);if(*end==0){r+=(char)v;i+=2;continue;}}r+=s[i];}return r;}
inline std::string unbase64(std::string s){for(char& c:s){if(c=='-')c='+';if(c=='_')c='/';}while(s.size()%4)s+='=';DWORD n=0;if(!CryptStringToBinaryA(s.c_str(),s.size(),CRYPT_STRING_BASE64,nullptr,&n,nullptr,nullptr))throw std::runtime_error("Invalid UJS base64");std::string r(n,0);CryptStringToBinaryA(s.c_str(),s.size(),CRYPT_STRING_BASE64,(BYTE*)r.data(),&n,nullptr,nullptr);r.resize(n);return r;}
struct InternetHandle{HINTERNET value=nullptr;explicit InternetHandle(HINTERNET p=nullptr):value(p){}~InternetHandle(){if(value)InternetCloseHandle(value);}InternetHandle(const InternetHandle&)=delete;};
struct Http {
  InternetHandle session;
  Http():session(InternetOpenW(L"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/110.0.0.0 Safari/537.36",INTERNET_OPEN_TYPE_PRECONFIG,nullptr,nullptr,0)){
    if(!session.value)throw std::runtime_error("InternetOpen failed");DWORD timeout=10000;InternetSetOptionW(session.value,INTERNET_OPTION_RECEIVE_TIMEOUT,&timeout,sizeof(timeout));InternetSetOptionW(session.value,INTERNET_OPTION_CONNECT_TIMEOUT,&timeout,sizeof(timeout));BOOL decode=TRUE;InternetSetOptionW(session.value,INTERNET_OPTION_HTTP_DECODING,&decode,sizeof(decode));
  }
  Json fetch(const Json& o,const std::string& base,std::string& body,const std::string& binaryBody={}){
    auto url=combine(o.value("url",std::string()),base);auto w=wide(url);URL_COMPONENTSW u{};u.dwStructSize=sizeof(u);u.dwHostNameLength=u.dwUrlPathLength=u.dwExtraInfoLength=(DWORD)-1;
    if(!InternetCrackUrlW(w.c_str(),w.size(),0,&u)||!(u.nScheme==INTERNET_SCHEME_HTTP||u.nScheme==INTERNET_SCHEME_HTTPS))throw std::runtime_error("HTTP(S) required");
    InternetHandle conn(InternetConnectW(session.value,std::wstring(u.lpszHostName,u.dwHostNameLength).c_str(),u.nPort,nullptr,nullptr,INTERNET_SERVICE_HTTP,0,0));
    std::wstring path(u.lpszUrlPath,u.dwUrlPathLength);if(u.dwExtraInfoLength)path.append(u.lpszExtraInfo,u.dwExtraInfoLength);if(path.empty())path=L"/";
    auto method=wide(o.value("method",std::string("GET")));auto kind=o.value("kind",std::string());bool staticAsset=method==L"GET"&&(kind=="script"||kind=="module"||kind=="style"||url.substr(0,url.find('?')).find(".wasm")!=std::string::npos);
    InternetHandle req(HttpOpenRequestW(conn.value,method.c_str(),path.c_str(),nullptr,wide(base).c_str(),nullptr,(staticAsset?0:INTERNET_FLAG_RELOAD|INTERNET_FLAG_NO_CACHE_WRITE)|(u.nScheme==INTERNET_SCHEME_HTTPS?INTERNET_FLAG_SECURE:0),0));
    if(!req.value)throw std::runtime_error("HTTP request failed");std::string headers;
    if(o.contains("headers")&&o["headers"].is_object())for(auto& it:o["headers"].items()){auto v=it.value().get<std::string>();if(v.find_first_of("\r\n")!=v.npos||it.key().find_first_of("\r\n:")!=std::string::npos)throw std::runtime_error("Invalid HTTP header");headers+=it.key()+": "+v+"\r\n";}
    std::string data=o.value("body",binaryBody);if(!binaryBody.empty())data=binaryBody;auto wh=wide(headers);
    if(!HttpSendRequestW(req.value,wh.data(),wh.size(),data.empty()?nullptr:data.data(),data.size()))throw std::runtime_error("HTTP transport error "+std::to_string(GetLastError()));
    DWORD status=0,n=sizeof(status);HttpQueryInfoW(req.value,HTTP_QUERY_STATUS_CODE|HTTP_QUERY_FLAG_NUMBER,&status,&n,nullptr);
    wchar_t finalUrl[32768];n=sizeof(finalUrl);if(InternetQueryOptionW(req.value,INTERNET_OPTION_URL,finalUrl,&n))url=narrow(finalUrl);
    DWORD len=0;HttpQueryInfoW(req.value,HTTP_QUERY_RAW_HEADERS_CRLF,nullptr,&len,nullptr);std::wstring hs(len/2,0);if(len&&HttpQueryInfoW(req.value,HTTP_QUERY_RAW_HEADERS_CRLF,hs.data(),&len,nullptr))hs.resize(len/2);
    Json parsed=Json::object();std::istringstream lines(narrow(hs));std::string line;while(std::getline(lines,line)){auto at=line.find(':');if(at==line.npos)continue;auto key=line.substr(0,at);for(char& c:key)c=(char)tolower((unsigned char)c);auto value=line.substr(at+1);while(!value.empty()&&(value.front()==' '||value.front()=='\t'))value.erase(0,1);if(!value.empty()&&value.back()=='\r')value.pop_back();if(key!="set-cookie")parsed[key]=value;}
    body.clear();char block[32768];DWORD got=0;size_t limit=std::min<size_t>(o.value("byteLimit",8*1024*1024),8*1024*1024);
    while(InternetReadFile(req.value,block,sizeof(block),&got)&&got){if(body.size()+got>limit)throw std::runtime_error("HTTP response exceeds 8 MiB");body.append(block,got);}
    return {{"url",url},{"status",status},{"headers",parsed}};
  }
};
