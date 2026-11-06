#include "page.hpp"
#include "display.hpp"
#include <commctrl.h>
#include <commdlg.h>
#include <shellapi.h>
#include <filesystem>
#include <iomanip>
struct Channel{std::string name,group,url,headers;};
static std::string trim(std::string s){auto a=s.find_first_not_of(" \t\r\n");if(a==s.npos)return {};return s.substr(a,s.find_last_not_of(" \t\r\n")-a+1);}
static std::vector<Channel> playlist(std::string text,const std::string& base={}){
  if(text.size()>8*1024*1024)throw std::runtime_error("Playlist exceeds 8 MiB");if(text.rfind("\xef\xbb\xbf",0)==0)text.erase(0,3);std::vector<Channel> result;std::istringstream lines(text);std::string line,group,name,headers;bool m3u=text.find("#EXTM3U")!=text.npos;
  while(std::getline(lines,line)){line=trim(line);if(line.empty())continue;
    if(line.rfind("#EXTINF:",0)==0){bool quote=false;size_t comma=line.npos;for(size_t i=0;i<line.size();i++){if(line[i]=='"')quote=!quote;if(line[i]==','&&!quote){comma=i;break;}}if(comma!=line.npos)name=line.substr(comma+1);auto at=line.find("group-title=\"");if(at!=line.npos){at+=13;group=line.substr(at,line.find('"',at)-at);}continue;}
    if(line.rfind("#EXTVLCOPT:http-referrer=",0)==0){headers+="Referer: "+line.substr(23)+"\r\n";continue;}
    if(line[0]=='#')continue;
    if(!m3u){auto comma=line.find(',');if(comma==line.npos)continue;auto value=line.substr(comma+1);if(value=="#genre#"){group=line.substr(0,comma);continue;}name=line.substr(0,comma);line=value;}
    if(line.rfind("webview://",0)!=0&&line.rfind("http://",0)!=0&&line.rfind("https://",0)!=0){if(base.empty()){headers.clear();continue;}line=combine(line,base);}
    if(line.rfind("http://",0)==0||line.rfind("https://",0)==0||line.rfind("webview://",0)==0)result.push_back({name.empty()?line:name,group,line,headers});name.clear();headers.clear();if(result.size()>10000)throw std::runtime_error("Playlist exceeds 10000 channels");
  }
  if(result.empty())throw std::runtime_error("No supported channels");return result;
}
static std::string readFile(const std::wstring& path){std::ifstream in(std::filesystem::path(path),std::ios::binary);if(!in)throw std::runtime_error("Unable to read file");return std::string(std::istreambuf_iterator<char>(in),{});}
static void saveFrame(const VideoFrame& frame,const std::wstring& path){if(!frame.width)return;BITMAPFILEHEADER file{};BITMAPINFOHEADER info{};file.bfType=0x4d42;file.bfOffBits=sizeof(file)+sizeof(info);file.bfSize=file.bfOffBits+frame.pixels.size();info.biSize=sizeof(info);info.biWidth=frame.width;info.biHeight=-frame.height;info.biPlanes=1;info.biBitCount=32;info.biCompression=BI_RGB;info.biSizeImage=frame.pixels.size();std::ofstream out(std::filesystem::path(path),std::ios::binary);out.write((char*)&file,sizeof(file));out.write((char*)&info,sizeof(info));out.write((char*)frame.pixels.data(),frame.pixels.size());}
#if NTV_DIAGNOSTICS
static int probe(const std::string& url,const std::wstring& report,int seconds){
  int64_t probeStarted=now(),totalFirstFrame=-1;std::atomic<bool> stop=false;Json info={{"native",true},{"java",false},{"url",url},{"passed",false}};std::unique_ptr<Page> page;std::unique_ptr<Decoder> direct;std::shared_ptr<const VideoFrame> latest;VideoFrame actual;int frames=0,audio=0;double position=0;
  try{if(url.rfind("webview://",0)==0){page=std::make_unique<Page>(url,stop,[]{ });page->load();}else direct=std::make_unique<Decoder>(url,"",nullptr,[]{ });auto end=now()+seconds*1000;
    while(now()<end&&!stop){if(page)page->tick();Decoder* d=page?page->activeDecoder():direct.get();if(d){frames=d->videoFrames;audio=page?page->audioFrameCount():d->audioFrames.load();position=d->position;std::lock_guard<std::mutex> lock(d->mutex);if(!d->error.empty())info["decoder_error"]=d->error;latest=d->frame;if(latest&&totalFirstFrame<0)totalFirstFrame=now()-probeStarted;info["hardware_decode"]=d->hardware.load();info["decoder_first_frame_ms"]=d->firstFrameMs.load();info["late_frames"]=d->lateFrames.load();}Sleep(20);}
    if(latest)actual=readbackFrame(*latest);
    info["total_first_frame_ms"]=totalFirstFrame;info["video_frames"]=frames;info["audio_frames"]=audio;info["position_seconds"]=position;info["width"]=actual.width;info["height"]=actual.height;info["passed"]=frames>=20&&position>=2;if(page){page->core->eval("__host(JSON.stringify({op:'probeState',value:__diagnostics()}))");info["network_trace"]=page->trace;info["console_messages"]=page->messages;info["unsupported_host_operations"]=page->unsupported;info["decoder_route"]=page->direct?"element-direct":"mse";info["selection_pending"]=page->pending;info["page_error"]=page->error;info["buffers"]=page->ranges();}
    if(actual.width){size_t p=((size_t)actual.height/2*actual.width+actual.width/2)*4;info["center_rgb"]={actual.pixels[p+2],actual.pixels[p+1],actual.pixels[p]};saveFrame(actual,report+L".bmp");}
  }catch(const std::exception& e){info["error"]=e.what();}
  stop=true;page.reset();direct.reset();info["cleanup_complete"]=true;std::ofstream out(std::filesystem::path(report),std::ios::binary);out<<info.dump(2);return info["passed"].get<bool>()?0:1;
}
#endif
static HWND window,list,combo,statusText,video;static HFONT font;static std::vector<std::vector<Channel>> lists;static std::vector<Channel>* channels=nullptr;
static std::atomic<bool> paused=false;static std::atomic<bool> shutdownApp=false;static std::thread playbackThread;static std::mutex controlMutex,frameMutex;static std::string nextUrl,nextHeaders;static bool hasCommand=false;static std::shared_ptr<const VideoFrame> displayed;static std::unique_ptr<VideoDisplay> videoDisplay;static HANDLE playbackWake=CreateEventW(nullptr,FALSE,FALSE,nullptr);
#if NTV_DIAGNOSTICS
static std::wstring renderReport;static std::string renderSource;static int renderSeconds=12;static int64_t renderStarted=0;static bool renderSnapshot=false;
#endif
static constexpr UINT WM_STATE=WM_APP+1,WM_FRAME=WM_APP+2;
static void status(std::string value){PostMessageW(window,WM_STATE,0,(LPARAM)new std::wstring(wide(value)));}
static void tune(const Channel* channel){std::lock_guard<std::mutex> lock(controlMutex);paused=false;nextUrl=channel?channel->url:"";nextHeaders=channel?channel->headers:"";hasCommand=true;SetEvent(playbackWake);status(channel?"正在打开："+channel->name:"已停止");}
static void playLoop(){
  CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);
  auto transport=std::make_shared<Http>();std::atomic<bool> stopping=false;std::unique_ptr<Page> page;std::unique_ptr<Decoder> decoder;int shown=-1;bool notified=false;
  while(!shutdownApp){std::string url,headers;bool change=false;{std::lock_guard<std::mutex> lock(controlMutex);if(hasCommand){change=true;url=nextUrl;headers=nextHeaders;hasCommand=false;}}
    if(change){stopping=true;page.reset();decoder.reset();stopping=false;shown=-1;notified=false;{std::lock_guard<std::mutex> lock(frameMutex);displayed={};}PostMessageW(window,WM_FRAME,0,0);
      try{if(url.rfind("webview://",0)==0){page=std::make_unique<Page>(url,stopping,[]{SetEvent(playbackWake);},transport);page->load();}else if(!url.empty())decoder=std::make_unique<Decoder>(url,headers,nullptr,[]{SetEvent(playbackWake);});}catch(const std::exception& e){status("频道暂时无法打开，请重试或切换频道");page.reset();}
    }
    try{if(page){page->pause(paused);page->tick();}if(decoder)decoder->paused=paused.load();auto d=page?page->activeDecoder():decoder.get();if(d){std::lock_guard<std::mutex> lock(d->mutex);if(!d->error.empty()&&!notified){status("播放暂时中断，请重试或切换频道");notified=true;}if(d->videoFrames!=shown&&d->frame&&d->frame->width){shown=d->videoFrames;{std::lock_guard<std::mutex> lock(frameMutex);displayed=d->frame;}PostMessageW(window,WM_FRAME,0,0);if(!notified){status("正在播放");notified=true;}}}}catch(const std::exception& e){status("网页加载未完成，请重试或切换频道");stopping=true;page.reset();stopping=false;}
    WaitForSingleObject(playbackWake,10);
  }
  stopping=true;page.reset();decoder.reset();CoUninitialize();
}
static void fillList(){int selected=(int)SendMessageW(combo,CB_GETCURSEL,0,0);if(selected<0||selected>=(int)lists.size())return;channels=&lists[selected];SendMessageW(list,LB_RESETCONTENT,0,0);for(auto& c:*channels){auto label=wide(c.group.empty()?c.name:c.group+" · "+c.name);SendMessageW(list,LB_ADDSTRING,0,(LPARAM)label.c_str());}status("已加载 "+std::to_string(channels->size())+" 个频道");}
static void addList(std::string name,std::vector<Channel> items){lists.push_back(std::move(items));SendMessageW(combo,CB_ADDSTRING,0,(LPARAM)wide(name).c_str());SendMessageW(combo,CB_SETCURSEL,lists.size()-1,0);fillList();}
static void licenseWindow(){
  auto raw=resource(104);std::string formatted;for(char c:raw){if(c=='\n')formatted+='\r';formatted+=c;}auto notice=wide(formatted);HWND viewer=CreateWindowExW(WS_EX_APPWINDOW,L"STATIC",L"第三方许可",WS_OVERLAPPEDWINDOW|WS_VISIBLE,CW_USEDEFAULT,CW_USEDEFAULT,820,640,window,nullptr,GetModuleHandleW(nullptr),nullptr);
  auto edit=CreateWindowExW(WS_EX_CLIENTEDGE,L"EDIT",notice.c_str(),WS_CHILD|WS_VISIBLE|ES_MULTILINE|ES_READONLY|ES_AUTOVSCROLL|WS_VSCROLL|WS_HSCROLL,0,0,800,600,viewer,nullptr,nullptr,nullptr);SendMessageW(edit,WM_SETFONT,(WPARAM)font,TRUE);
}
static void openList(){wchar_t path[32768]={};OPENFILENAMEW of{};of.lStructSize=sizeof(of);of.hwndOwner=window;of.lpstrFile=path;of.nMaxFile=32768;of.lpstrFilter=L"播放列表 (*.m3u;*.m3u8;*.txt)\0*.m3u;*.m3u8;*.txt\0All files\0*.*\0";of.Flags=OFN_FILEMUSTEXIST|OFN_EXPLORER;if(GetOpenFileNameW(&of))try{addList(narrow(std::filesystem::path(path).filename().wstring()),playlist(readFile(path)));}catch(const std::exception& e){status("播放列表加载失败，请检查文件或地址");}}
static LRESULT CALLBACK urlProc(HWND hwnd,UINT message,WPARAM w,LPARAM l){auto result=(int*)GetWindowLongPtrW(hwnd,GWLP_USERDATA);if(result&&message==WM_COMMAND){if(LOWORD(w)==1||LOWORD(w)==2)*result=LOWORD(w);return 0;}if(result&&message==WM_CLOSE){*result=2;return 0;}return DefWindowProcW(hwnd,message,w,l);}
static void openUrl(){
  // A native modal edit window avoids any browser/framework dependency.
  HWND dialog=CreateWindowExW(WS_EX_DLGMODALFRAME,L"STATIC",L"添加播放列表地址",WS_CAPTION|WS_SYSMENU|WS_POPUP|WS_VISIBLE,200,200,600,130,window,nullptr,GetModuleHandleW(nullptr),nullptr);
  int action=0;SetWindowLongPtrW(dialog,GWLP_USERDATA,(LONG_PTR)&action);SetWindowLongPtrW(dialog,GWLP_WNDPROC,(LONG_PTR)urlProc);
  HWND edit=CreateWindowExW(WS_EX_CLIENTEDGE,L"EDIT",L"https://",WS_CHILD|WS_VISIBLE|ES_AUTOHSCROLL,12,12,560,26,dialog,nullptr,nullptr,nullptr);
  HWND ok=CreateWindowW(L"BUTTON",L"确定",WS_CHILD|WS_VISIBLE|BS_DEFPUSHBUTTON,400,50,80,28,dialog,(HMENU)1,nullptr,nullptr);CreateWindowW(L"BUTTON",L"取消",WS_CHILD|WS_VISIBLE,490,50,80,28,dialog,(HMENU)2,nullptr,nullptr);SendMessageW(edit,WM_SETFONT,(WPARAM)font,TRUE);SetFocus(edit);EnableWindow(window,FALSE);bool accepted=false;MSG msg;
  while(!action&&IsWindow(dialog)&&GetMessageW(&msg,nullptr,0,0)>0){if(msg.message==WM_COMMAND&&msg.hwnd==dialog){if(LOWORD(msg.wParam)==1){accepted=true;break;}if(LOWORD(msg.wParam)==2)break;}if(msg.message==WM_KEYDOWN&&msg.wParam==VK_RETURN){accepted=true;break;}if(msg.message==WM_KEYDOWN&&msg.wParam==VK_ESCAPE)break;if(msg.message==WM_CLOSE&&msg.hwnd==dialog)break;TranslateMessage(&msg);DispatchMessageW(&msg);}
  accepted=accepted||action==1;wchar_t address[8192];GetWindowTextW(edit,address,8192);DestroyWindow(dialog);EnableWindow(window,TRUE);SetActiveWindow(window);if(accepted)try{Http http;std::string body;auto response=http.fetch({{"url",narrow(address)}},"",body);if(response["status"]!=200)throw std::runtime_error("Playlist HTTP failed");addList(narrow(address),playlist(body,response["url"]));}catch(const std::exception& e){status("播放列表加载失败，请检查文件或地址");}
}
static LRESULT CALLBACK videoProc(HWND hwnd,UINT msg,WPARAM w,LPARAM l){
  if(msg==WM_SIZE){if(videoDisplay)videoDisplay->resize();return 0;}
  if(msg==WM_ERASEBKGND)return 1;
  if(msg==WM_PAINT){PAINTSTRUCT ps;HDC dc=BeginPaint(hwnd,&ps);RECT r;GetClientRect(hwnd,&r);std::shared_ptr<const VideoFrame> selected;{std::lock_guard<std::mutex> lock(frameMutex);selected=displayed;}
    if(!displayDevice()->device||(videoDisplay&&!videoDisplay->active)){FillRect(dc,&r,(HBRUSH)GetStockObject(BLACK_BRUSH));if(selected&&selected->width){auto image=readbackFrame(*selected);double scale=std::min((double)r.right/(image.width*image.sar),(double)r.bottom/image.height);int width=std::lround(image.width*image.sar*scale),height=std::lround(image.height*scale);BITMAPINFO info{};info.bmiHeader.biSize=sizeof(BITMAPINFOHEADER);info.bmiHeader.biWidth=image.width;info.bmiHeader.biHeight=-image.height;info.bmiHeader.biPlanes=1;info.bmiHeader.biBitCount=32;info.bmiHeader.biCompression=BI_RGB;SetStretchBltMode(dc,HALFTONE);SetBrushOrgEx(dc,0,0,nullptr);StretchDIBits(dc,(r.right-width)/2,(r.bottom-height)/2,width,height,0,0,image.width,image.height,image.pixels.data(),&info,DIB_RGB_COLORS,SRCCOPY);}}
    EndPaint(hwnd,&ps);return 0;}
  return DefWindowProcW(hwnd,msg,w,l);
}
static LRESULT CALLBACK mainProc(HWND hwnd,UINT msg,WPARAM w,LPARAM l){
  switch(msg){
#if NTV_DIAGNOSTICS
    case WM_TIMER:if(w==501&&!renderReport.empty()){
      auto elapsed=now()-renderStarted;
      if(elapsed>=renderSeconds*1000&&!renderSnapshot){if(videoDisplay)videoDisplay->snapshot(renderReport+L".bmp");renderSnapshot=true;}
      if(elapsed>=renderSeconds*1000+1000){KillTimer(hwnd,501);Json result={{"passed",false},{"render_frames",videoDisplay?videoDisplay->presented.load():0},{"gpu_output",videoDisplay&&videoDisplay->active.load()}};{std::lock_guard<std::mutex> lock(frameMutex);if(displayed){result["source_width"]=displayed->width;result["source_height"]=displayed->height;result["sample_aspect_ratio"]=displayed->sar;result["passed"]=videoDisplay&&videoDisplay->active&&videoDisplay->presented>20;}}std::ofstream out(std::filesystem::path(renderReport),std::ios::binary);out<<result.dump(2);out.close();PostMessageW(hwnd,WM_CLOSE,0,0);}return 0;
    }break;
#endif
    case WM_SIZE:{int width=LOWORD(l),height=HIWORD(l);MoveWindow(combo,12,12,std::max(100,width-250),200,TRUE);MoveWindow(GetDlgItem(hwnd,102),width-228,12,100,28,TRUE);MoveWindow(GetDlgItem(hwnd,103),width-118,12,106,28,TRUE);int left=std::min(290,width/3);MoveWindow(list,12,52,left-18,std::max(50,height-108),TRUE);MoveWindow(video,left,52,std::max(50,width-left-12),std::max(50,height-108),TRUE);MoveWindow(statusText,12,height-28,width-24,22,TRUE);for(int i=0;i<4;i++)MoveWindow(GetDlgItem(hwnd,110+i),12+i*86,height-50,80,22,TRUE);return 0;}
    case WM_COMMAND:{int id=LOWORD(w),action=HIWORD(w);if(id==120){MessageBoxW(hwnd,L"网页电视 0.2.1",L"关于网页电视",MB_OK);return 0;}if(id==121){licenseWindow();return 0;}if(id==101&&action==CBN_SELCHANGE)fillList();if(id==102)openList();if(id==103)openUrl();if(id==112)tune(nullptr);if(id==113){paused=!paused.load();SetWindowTextW(GetDlgItem(hwnd,113),paused?L"继续":L"暂停");}if(id==104&&action==LBN_SELCHANGE||id==110||id==111){int index=(int)SendMessageW(list,LB_GETCURSEL,0,0);if(id==110)index--;if(id==111)index++;if(channels&&!channels->empty()){index=std::max(0,std::min((int)channels->size()-1,index));SendMessageW(list,LB_SETCURSEL,index,0);tune(&channels->at(index));}}return 0;}
    case WM_STATE:{std::unique_ptr<std::wstring> value((std::wstring*)l);SetWindowTextW(statusText,value->c_str());return 0;}
    case WM_FRAME:{std::shared_ptr<const VideoFrame> selected;{std::lock_guard<std::mutex> lock(frameMutex);selected=displayed;}if(videoDisplay)videoDisplay->submit(selected);else InvalidateRect(video,nullptr,FALSE);return 0;}
    case WM_DPICHANGED:{auto rect=(RECT*)l;SetWindowPos(hwnd,nullptr,rect->left,rect->top,rect->right-rect->left,rect->bottom-rect->top,SWP_NOZORDER|SWP_NOACTIVATE);if(videoDisplay)videoDisplay->resize();return 0;}
    case WM_CLOSE:videoDisplay.reset();DestroyWindow(hwnd);return 0;
    case WM_DESTROY:shutdownApp=true;PostQuitMessage(0);return 0;
  }return DefWindowProcW(hwnd,msg,w,l);
}
int WINAPI wWinMain(HINSTANCE instance,HINSTANCE,PWSTR,int show){
  if(!SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2))SetProcessDPIAware();CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);int argc;auto argv=CommandLineToArgvW(GetCommandLineW(),&argc);int result=0;
  try{
#if NTV_DIAGNOSTICS
    if(argc>=5&&std::wstring(argv[1])==L"--render-test"){renderSource=narrow(argv[2]);renderReport=argv[4];renderSeconds=argc>=6?_wtoi(argv[5]):12;}
    if(argc>=5&&std::wstring(argv[1])==L"--probe"){int seconds=argc>=6?_wtoi(argv[5]):12;result=probe(narrow(argv[2]),argv[4],seconds);LocalFree(argv);return result;}
    if(argc>=3&&std::wstring(argv[1])==L"--self-test"){auto channels=playlist(resource(103));if(channels.size()!=828)throw std::runtime_error("Bundled playlist channel count");auto signedUrl="webview://https://test.invalid/?token=x,y&ujs=YQ==";auto checked=playlist(std::string("分组,#genre#\n频道,")+signedUrl+"\n");if(checked.size()!=1||checked[0].url!=signedUrl)throw std::runtime_error("Signed URL changed");std::ofstream out{std::filesystem::path(argv[2])};out<<Json({{"passed",true},{"channels",channels.size()},{"native",true}}).dump(2);LocalFree(argv);return 0;}
#endif
    WNDCLASSW wc{};wc.hInstance=instance;wc.lpfnWndProc=mainProc;wc.lpszClassName=L"NativeWasmTv.Native";wc.hCursor=LoadCursor(nullptr,IDC_ARROW);wc.hbrBackground=(HBRUSH)(COLOR_WINDOW+1);RegisterClassW(&wc);wc.lpszClassName=L"NativeWasmTv.Video";wc.lpfnWndProc=videoProc;wc.hbrBackground=(HBRUSH)GetStockObject(BLACK_BRUSH);RegisterClassW(&wc);
    window=CreateWindowExW(0,L"NativeWasmTv.Native",L"网页电视",WS_OVERLAPPEDWINDOW,CW_USEDEFAULT,CW_USEDEFAULT,1080,680,nullptr,nullptr,instance,nullptr);HMENU menu=CreateMenu(),help=CreatePopupMenu();AppendMenuW(help,MF_STRING,120,L"关于");AppendMenuW(help,MF_STRING,121,L"第三方许可");AppendMenuW(menu,MF_POPUP,(UINT_PTR)help,L"帮助");SetMenu(window,menu);font=CreateFontW(-16,0,0,0,FW_NORMAL,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH,L"Microsoft YaHei UI");
    auto child=[&](const wchar_t* cls,const wchar_t* text,DWORD style,int id){auto h=CreateWindowExW(0,cls,text,WS_CHILD|WS_VISIBLE|style,0,0,1,1,window,(HMENU)(intptr_t)id,instance,nullptr);SendMessageW(h,WM_SETFONT,(WPARAM)font,TRUE);return h;};
    combo=child(L"COMBOBOX",L"",CBS_DROPDOWNLIST|WS_VSCROLL,101);child(L"BUTTON",L"打开列表",0,102);child(L"BUTTON",L"添加地址",0,103);list=child(L"LISTBOX",L"",LBS_NOTIFY|WS_VSCROLL|LBS_NOINTEGRALHEIGHT,104);video=child(L"NativeWasmTv.Video",L"",0,105);videoDisplay=std::make_unique<VideoDisplay>(video);statusText=child(L"STATIC",L"",SS_LEFT,106);child(L"BUTTON",L"上一台",0,110);child(L"BUTTON",L"下一台",0,111);child(L"BUTTON",L"停止",0,112);child(L"BUTTON",L"暂停",0,113);addList("内置频道",playlist(resource(103)));SendMessageW(window,WM_SIZE,0,MAKELPARAM(1064,641));
#if NTV_DIAGNOSTICS
    if(!renderReport.empty()){Channel c{"Test","",renderSource,""};tune(&c);renderStarted=now();SetTimer(window,501,250,nullptr);}
    ShowWindow(window,renderReport.empty()?show:SW_HIDE);
#else
    ShowWindow(window,show);
#endif
    UpdateWindow(window);playbackThread=std::thread(playLoop);MSG msg;while(GetMessageW(&msg,nullptr,0,0)>0){TranslateMessage(&msg);DispatchMessageW(&msg);}if(playbackThread.joinable())playbackThread.join();DeleteObject(font);
  }catch(const std::exception& e){
#if NTV_DIAGNOSTICS
    if(argc>=5&&std::wstring(argv[1])==L"--probe"){std::ofstream out{std::filesystem::path(argv[4])};out<<Json({{"passed",false},{"error",e.what()}}).dump(2);}else
#endif
    MessageBoxW(nullptr,L"程序暂时无法启动，请重新打开。",L"网页电视",MB_OK|MB_ICONERROR);result=1;}
  LocalFree(argv);CoUninitialize();return result;
}
