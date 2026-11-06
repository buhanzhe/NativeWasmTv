#pragma once
#include "common.hpp"
#include <d3d11.h>
#include <dxgi1_3.h>
#include <wrl/client.h>
using Microsoft::WRL::ComPtr;
struct VideoFrame;
struct DisplayDevice {
  ComPtr<ID3D11Device> device;
  ComPtr<ID3D11DeviceContext> context;
  ComPtr<ID3D11VideoDevice> videoDevice;
  ComPtr<ID3D11VideoContext> videoContext;
  std::recursive_mutex mutex;
  DisplayDevice();
};
std::shared_ptr<DisplayDevice> displayDevice();
class VideoDisplay {
 public:
  explicit VideoDisplay(HWND window);
  ~VideoDisplay();
  void submit(std::shared_ptr<const VideoFrame> frame);
  void resize();
  std::atomic<bool> active{false};std::atomic<unsigned> presented{0};
#if NTV_DIAGNOSTICS
  void snapshot(std::wstring path);
  std::wstring snapshotPath;
#endif
 private:
  void run();
  bool present(const VideoFrame* frame);
  HWND window;HANDLE wake;std::atomic<bool> stopping{false};std::thread thread;
  std::mutex mutex;std::shared_ptr<const VideoFrame> frame;
  std::shared_ptr<DisplayDevice> gpu;
  ComPtr<IDXGISwapChain1> swap;
  ComPtr<ID3D11VideoProcessorEnumerator> enumerator;
  ComPtr<ID3D11VideoProcessor> processor;
  ComPtr<ID3D11Texture2D> upload;
  int width=0,height=0,sourceWidth=0,sourceHeight=0;DXGI_FORMAT sourceFormat=DXGI_FORMAT_UNKNOWN;
};
