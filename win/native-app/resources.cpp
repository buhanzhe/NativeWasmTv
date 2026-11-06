#include "common.hpp"
#include <compressapi.h>
#ifdef NTV_PACKED_ASSETS
#include "asset_key.hpp"
#endif
std::string resource(int id){
  static std::mutex mutex;static std::map<int,std::string> cache;std::lock_guard<std::mutex> guard(mutex);auto found=cache.find(id);if(found!=cache.end())return found->second;
  auto r=FindResourceW(nullptr,MAKEINTRESOURCEW(id),RT_RCDATA);if(!r)throw std::runtime_error("资源加载失败");auto loaded=LoadResource(nullptr,r);std::string bytes((char*)LockResource(loaded),SizeofResource(nullptr,r));
#ifdef NTV_PACKED_ASSETS
  if(bytes.size()>=28&&bytes.compare(0,4,"NA01")==0){
    uint32_t plainSize=0,compressedSize=0;memcpy(&plainSize,bytes.data()+4,4);memcpy(&compressedSize,bytes.data()+8,4);if(plainSize>8*1024*1024||compressedSize>8*1024*1024)throw std::runtime_error("资源校验失败");
    unsigned char key[32],iv[16];for(unsigned i=0;i<32;i++)key[i]=assetFirst[i]^assetSecond[31-i];memcpy(iv,bytes.data()+12,16);
    BCRYPT_ALG_HANDLE algorithm=nullptr;BCRYPT_KEY_HANDLE handle=nullptr;std::string compressed(bytes.size()-28,0);ULONG written=0;
    auto status=BCryptOpenAlgorithmProvider(&algorithm,BCRYPT_AES_ALGORITHM,nullptr,0);if(status>=0)status=BCryptSetProperty(algorithm,BCRYPT_CHAINING_MODE,(BYTE*)BCRYPT_CHAIN_MODE_CBC,sizeof(BCRYPT_CHAIN_MODE_CBC),0);if(status>=0)status=BCryptGenerateSymmetricKey(algorithm,&handle,nullptr,0,key,sizeof(key),0);if(status>=0)status=BCryptDecrypt(handle,(BYTE*)bytes.data()+28,bytes.size()-28,nullptr,iv,16,(BYTE*)compressed.data(),compressed.size(),&written,BCRYPT_BLOCK_PADDING);
    SecureZeroMemory(key,sizeof(key));if(handle)BCryptDestroyKey(handle);if(algorithm)BCryptCloseAlgorithmProvider(algorithm,0);if(status<0||written!=compressedSize)throw std::runtime_error("资源校验失败");
    DECOMPRESSOR_HANDLE decompressor=nullptr;if(!CreateDecompressor(COMPRESS_ALGORITHM_XPRESS_HUFF,nullptr,&decompressor))throw std::runtime_error("资源加载失败");std::string plain(plainSize,0);SIZE_T actual=0;BOOL ok=Decompress(decompressor,compressed.data(),written,plain.data(),plain.size(),&actual);CloseDecompressor(decompressor);SecureZeroMemory(compressed.data(),compressed.size());if(!ok||actual!=plainSize)throw std::runtime_error("资源校验失败");bytes=std::move(plain);
  }
#endif
  cache[id]=bytes;return bytes;
}
