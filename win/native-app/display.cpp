#include "display.hpp"
#include "media.hpp"
#include <d3d10.h>
#include <filesystem>
DisplayDevice::DisplayDevice(){
  ComPtr<IDXGIFactory1> factory;if(FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory))))return;
  std::vector<ComPtr<IDXGIAdapter1>> adapters;ComPtr<IDXGIAdapter1> adapter;
  for(UINT i=0;factory->EnumAdapters1(i,adapter.ReleaseAndGetAddressOf())!=DXGI_ERROR_NOT_FOUND;i++){DXGI_ADAPTER_DESC1 desc{};adapter->GetDesc1(&desc);if(!(desc.Flags&DXGI_ADAPTER_FLAG_SOFTWARE))adapters.push_back(adapter);}
  std::stable_sort(adapters.begin(),adapters.end(),[](auto a,auto b){DXGI_ADAPTER_DESC1 x{},y{};a->GetDesc1(&x);b->GetDesc1(&y);return x.DedicatedVideoMemory>y.DedicatedVideoMemory;});
  for(auto& a:adapters){D3D_FEATURE_LEVEL level;D3D_FEATURE_LEVEL levels[]={D3D_FEATURE_LEVEL_11_0};
    auto hr=D3D11CreateDevice(a.Get(),D3D_DRIVER_TYPE_UNKNOWN,nullptr,D3D11_CREATE_DEVICE_BGRA_SUPPORT|D3D11_CREATE_DEVICE_VIDEO_SUPPORT,levels,1,D3D11_SDK_VERSION,&device,&level,&context);
    if(SUCCEEDED(hr)&&SUCCEEDED(device.As(&videoDevice))&&SUCCEEDED(context.As(&videoContext))){ComPtr<ID3D10Multithread> multithread;if(SUCCEEDED(context.As(&multithread)))multithread->SetMultithreadProtected(TRUE);return;}
    context.Reset();device.Reset();videoDevice.Reset();videoContext.Reset();
  }
}
std::shared_ptr<DisplayDevice> displayDevice(){static auto device=std::make_shared<DisplayDevice>();return device;}
VideoDisplay::VideoDisplay(HWND hwnd):window(hwnd),wake(CreateEventW(nullptr,FALSE,FALSE,nullptr)),gpu(displayDevice()){thread=std::thread([this]{run();});}
VideoDisplay::~VideoDisplay(){stopping=true;SetEvent(wake);if(thread.joinable())thread.join();CloseHandle(wake);}
void VideoDisplay::submit(std::shared_ptr<const VideoFrame> value){{std::lock_guard<std::mutex> lock(mutex);frame=std::move(value);}SetEvent(wake);}
void VideoDisplay::resize(){SetEvent(wake);}
#if NTV_DIAGNOSTICS
void VideoDisplay::snapshot(std::wstring path){std::lock_guard<std::mutex> lock(mutex);snapshotPath=std::move(path);SetEvent(wake);}
#endif
void VideoDisplay::run(){
  CoInitializeEx(nullptr,COINIT_MULTITHREADED);
  while(!stopping){WaitForSingleObject(wake,100);if(stopping)break;std::shared_ptr<const VideoFrame> selected;{std::lock_guard<std::mutex> lock(mutex);selected=frame;}bool ok=present(selected.get());active=ok;if(!ok)InvalidateRect(window,nullptr,FALSE);else if(selected)presented++;}
  processor.Reset();enumerator.Reset();upload.Reset();swap.Reset();CoUninitialize();
}
bool VideoDisplay::present(const VideoFrame* image){
  if(!gpu->device)return false;RECT client{};GetClientRect(window,&client);int w=client.right,h=client.bottom;if(w<=0||h<=0)return true;
  HRESULT hr;
  if(!swap){ComPtr<IDXGIDevice> device;ComPtr<IDXGIAdapter> adapter;ComPtr<IDXGIFactory2> factory;gpu->device.As(&device);device->GetAdapter(&adapter);adapter->GetParent(IID_PPV_ARGS(&factory));DXGI_SWAP_CHAIN_DESC1 desc{};desc.Width=w;desc.Height=h;desc.Format=DXGI_FORMAT_B8G8R8A8_UNORM;desc.SampleDesc.Count=1;desc.BufferUsage=DXGI_USAGE_RENDER_TARGET_OUTPUT;desc.BufferCount=2;desc.Scaling=DXGI_SCALING_STRETCH;desc.SwapEffect=DXGI_SWAP_EFFECT_FLIP_DISCARD;desc.Flags=DXGI_SWAP_CHAIN_FLAG_FRAME_LATENCY_WAITABLE_OBJECT;
    hr=factory->CreateSwapChainForHwnd(gpu->device.Get(),window,&desc,nullptr,nullptr,&swap);if(FAILED(hr))return false;factory->MakeWindowAssociation(window,DXGI_MWA_NO_ALT_ENTER);ComPtr<IDXGISwapChain2> latency;if(SUCCEEDED(swap.As(&latency)))latency->SetMaximumFrameLatency(1);width=w;height=h;
  }
  if(width!=w||height!=h){std::lock_guard<std::recursive_mutex> lock(gpu->mutex);hr=swap->ResizeBuffers(0,w,h,DXGI_FORMAT_UNKNOWN,DXGI_SWAP_CHAIN_FLAG_FRAME_LATENCY_WAITABLE_OBJECT);if(FAILED(hr))return false;width=w;height=h;processor.Reset();enumerator.Reset();}
  ComPtr<IDXGISwapChain2> latency;if(SUCCEEDED(swap.As(&latency))){auto event=latency->GetFrameLatencyWaitableObject();if(event)WaitForSingleObject(event,40);}
  {
    std::lock_guard<std::recursive_mutex> lock(gpu->mutex);ComPtr<ID3D11Texture2D> output;if(FAILED(swap->GetBuffer(0,IID_PPV_ARGS(&output))))return false;
    ComPtr<ID3D11RenderTargetView> target;if(FAILED(gpu->device->CreateRenderTargetView(output.Get(),nullptr,&target)))return false;const float black[]={0,0,0,1};gpu->context->ClearRenderTargetView(target.Get(),black);
    if(image&&image->width>0){
      ID3D11Texture2D* input=(ID3D11Texture2D*)image->texture;DXGI_FORMAT format=DXGI_FORMAT_B8G8R8A8_UNORM;D3D11_TEXTURE2D_DESC inputDesc{};
      if(input){input->GetDesc(&inputDesc);format=inputDesc.Format;}
      else if(!image->pixels.empty()){
        if(!upload||sourceWidth!=image->width||sourceHeight!=image->height||sourceFormat!=format){upload.Reset();D3D11_TEXTURE2D_DESC desc{};desc.Width=image->width;desc.Height=image->height;desc.MipLevels=1;desc.ArraySize=1;desc.Format=format;desc.SampleDesc.Count=1;desc.Usage=D3D11_USAGE_DEFAULT;desc.BindFlags=D3D11_BIND_SHADER_RESOURCE;if(FAILED(gpu->device->CreateTexture2D(&desc,nullptr,&upload)))return false;}
        gpu->context->UpdateSubresource(upload.Get(),0,nullptr,image->pixels.data(),image->width*4,0);input=upload.Get();input->GetDesc(&inputDesc);
      }else return false;
      if(!processor||sourceWidth!=(int)inputDesc.Width||sourceHeight!=(int)inputDesc.Height||sourceFormat!=format){
        processor.Reset();enumerator.Reset();D3D11_VIDEO_PROCESSOR_CONTENT_DESC desc{};desc.InputFrameFormat=D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;desc.InputFrameRate={60,1};desc.InputWidth=inputDesc.Width;desc.InputHeight=inputDesc.Height;desc.OutputFrameRate={60,1};desc.OutputWidth=width;desc.OutputHeight=height;desc.Usage=D3D11_VIDEO_USAGE_PLAYBACK_NORMAL;
        if(FAILED(gpu->videoDevice->CreateVideoProcessorEnumerator(&desc,&enumerator))||FAILED(gpu->videoDevice->CreateVideoProcessor(enumerator.Get(),0,&processor)))return false;sourceWidth=inputDesc.Width;sourceHeight=inputDesc.Height;sourceFormat=format;
      }
      D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC iv{};iv.ViewDimension=D3D11_VPIV_DIMENSION_TEXTURE2D;iv.Texture2D.ArraySlice=image->textureSlice;ComPtr<ID3D11VideoProcessorInputView> inputView;if(FAILED(gpu->videoDevice->CreateVideoProcessorInputView(input,enumerator.Get(),&iv,&inputView)))return false;
      D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC ov{};ov.ViewDimension=D3D11_VPOV_DIMENSION_TEXTURE2D;ComPtr<ID3D11VideoProcessorOutputView> outputView;if(FAILED(gpu->videoDevice->CreateVideoProcessorOutputView(output.Get(),enumerator.Get(),&ov,&outputView)))return false;
      double aspect=(double)image->width*image->sar/image->height;int dw=width,dh=(int)std::lround(width/aspect);if(dh>height){dh=height;dw=(int)std::lround(height*aspect);}RECT src{0,0,image->width,image->height},dst{(width-dw)/2,(height-dh)/2,(width+dw)/2,(height+dh)/2},full{0,0,width,height};
      gpu->videoContext->VideoProcessorSetStreamAutoProcessingMode(processor.Get(),0,FALSE);gpu->videoContext->VideoProcessorSetStreamFrameFormat(processor.Get(),0,D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE);gpu->videoContext->VideoProcessorSetStreamSourceRect(processor.Get(),0,TRUE,&src);gpu->videoContext->VideoProcessorSetStreamDestRect(processor.Get(),0,TRUE,&dst);gpu->videoContext->VideoProcessorSetOutputTargetRect(processor.Get(),TRUE,&full);
      D3D11_VIDEO_PROCESSOR_COLOR_SPACE colors{};colors.YCbCr_Matrix=image->colorSpace==5||image->colorSpace==6?0:1;colors.Nominal_Range=(format==DXGI_FORMAT_B8G8R8A8_UNORM||image->fullRange)?2:1;gpu->videoContext->VideoProcessorSetStreamColorSpace(processor.Get(),0,&colors);D3D11_VIDEO_PROCESSOR_COLOR_SPACE rgb{};rgb.RGB_Range=0;gpu->videoContext->VideoProcessorSetOutputColorSpace(processor.Get(),&rgb);
      D3D11_VIDEO_PROCESSOR_STREAM stream{};stream.Enable=TRUE;stream.pInputSurface=inputView.Get();if(FAILED(gpu->videoContext->VideoProcessorBlt(processor.Get(),outputView.Get(),0,1,&stream)))return false;
    }
#if NTV_DIAGNOSTICS
    std::wstring path;{std::lock_guard<std::mutex> lock(mutex);path=std::move(snapshotPath);snapshotPath.clear();}
    if(!path.empty()){
      D3D11_TEXTURE2D_DESC desc{};output->GetDesc(&desc);desc.BindFlags=0;desc.Usage=D3D11_USAGE_STAGING;desc.CPUAccessFlags=D3D11_CPU_ACCESS_READ;desc.MiscFlags=0;ComPtr<ID3D11Texture2D> staging;
      if(SUCCEEDED(gpu->device->CreateTexture2D(&desc,nullptr,&staging))){gpu->context->CopyResource(staging.Get(),output.Get());D3D11_MAPPED_SUBRESOURCE mapped{};if(SUCCEEDED(gpu->context->Map(staging.Get(),0,D3D11_MAP_READ,0,&mapped))){BITMAPFILEHEADER file{};BITMAPINFOHEADER header{};file.bfType=0x4d42;file.bfOffBits=sizeof(file)+sizeof(header);file.bfSize=file.bfOffBits+width*height*4;header.biSize=sizeof(header);header.biWidth=width;header.biHeight=-height;header.biPlanes=1;header.biBitCount=32;header.biCompression=BI_RGB;std::ofstream outputFile(std::filesystem::path(path),std::ios::binary);outputFile.write((char*)&file,sizeof(file));outputFile.write((char*)&header,sizeof(header));for(int row=0;row<height;row++)outputFile.write((char*)mapped.pData+row*mapped.RowPitch,width*4);gpu->context->Unmap(staging.Get(),0);}}
    }
#endif
  }
  hr=swap->Present(1,0);return SUCCEEDED(hr);
}
