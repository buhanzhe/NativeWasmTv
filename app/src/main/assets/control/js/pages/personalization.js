function loadTvUi() {
  api("/api/tv-ui",null,function(error,result){
    document.getElementById("tvUiStatus").textContent=error?error.message:(result.error || "离线切换，播放不中断");
    if(!error)document.getElementById("tvUiStyle").value=result.selected;
  });
}
function saveTvUiStyle() {
  var select=document.getElementById("tvUiStyle");select.disabled=true;
  api("/api/tv-ui",{id:select.value},function(error){
    select.disabled=false;if(error)toast(error.message,true);else toast("电视界面已切换");loadTvUi();
  },30000);
}
var radioUiSaving=false, radioUiSequence=0, radioUiTimer=null, radioUiOptionsSignature="";
function loadRadioUi() {
  if(radioUiSaving)return;
  var sequence=++radioUiSequence;clearTimeout(radioUiTimer);
  api("/api/radio-ui",null,function(error,result){
    if(sequence!==radioUiSequence||radioUiSaving)return;
    var status=document.getElementById("radioUiStatus"),select=document.getElementById("radioUiStyle");
    if(error){status.textContent=error.message;return;}
    var choices=result.choices||[],current=result.selected||result.style||"classic";
    var signature=JSON.stringify(choices);
    if(signature!==radioUiOptionsSignature){
      select.innerHTML="";
      for(var i=0;i<choices.length;i++){var option=document.createElement("option");option.value=choices[i].id;option.textContent=choices[i].name;select.appendChild(option);}
      radioUiOptionsSignature=signature;
    }
    select.value=current;select.disabled=false;
    status.textContent=result.error || (result.catalogRefreshing ? "正在刷新可选界面…" : result.catalogError || "已下载的界面可离线使用");
    if(result.catalogRefreshing)radioUiTimer=setTimeout(loadRadioUi,1000);
  });
}
function saveRadioUiStyle() {
  if(radioUiSaving)return;
  var select=document.getElementById("radioUiStyle");radioUiSaving=true;radioUiSequence++;clearTimeout(radioUiTimer);select.disabled=true;
  document.getElementById("radioUiStatus").textContent="正在应用界面，首次使用会下载…";
  api("/api/radio-ui",{id:select.value},function(error){
    radioUiSaving=false;select.disabled=false;
    toast(error?error.message:"电台界面已切换",!!error);loadRadioUi();
  },40000);
}

var gestureSaving = false, memorySaving=false;
var groupPasswordSaving=false,groupPasswordLoaded=false,groupPasswordReading=false,groupPasswordRevision=0;
var groupPasswordStateAfter=-1;
function loadGroupPasswordState(){
  if(groupPasswordLoaded||groupPasswordReading)return;
  groupPasswordReading=true;var revision=groupPasswordRevision;
  api("/api/state?view=advanced",null,function(error,data){
    groupPasswordReading=false;
    if(error||groupPasswordSaving||revision!==groupPasswordRevision)return;
    var value=data&&data.settings&&data.settings.groupPasswordRequired;
    if(typeof value!=="boolean")return;
    groupPasswordLoaded=true;
    if(state&&state.settings)state.settings.groupPasswordRequired=value;
    document.getElementById("groupPasswordRequired").checked=value;
  });
}
function saveGroupPasswordRequired(){
  if(groupPasswordSaving||!state||!state.settings)return;
  var input=document.getElementById("groupPasswordRequired"),value=input.checked;
  groupPasswordRevision++;groupPasswordSaving=true;input.disabled=true;
  function finish(error,actual){
    groupPasswordStateAfter=stateRequestSequence;
    groupPasswordSaving=false;input.disabled=false;
    input.checked=typeof actual==="boolean"?actual:!value;
    if(state&&state.settings)state.settings.groupPasswordRequired=input.checked;
    groupPasswordLoaded=true;
    toast(error?error.message:"分组密码设置已生效",!!error);refresh();
  }
  function confirm(data){
    var actual=data&&data.settings&&data.settings.groupPasswordRequired;
    if(typeof actual!=="boolean"){finish(new Error("当前主程序不支持分组密码，请更新 APK"));return;}
    finish(actual===value?null:new Error("分组密码设置未生效，请重试"),actual);
  }
  api("/api/settings",{groupPasswordRequired:value},function(error){
    if(error){finish(error);return;}
    api("/api/state?view=advanced",null,function(readError,data){
      if(readError){finish(new Error("无法确认分组密码设置，请重试"));return;}
      confirm(data);
    });
  });
}
function saveMemory(){
  if(memorySaving||!state||!state.settings)return;
  var keys=["rememberChannelSource","rememberChannelDecode"],patch={};

  memorySaving=true;keys.forEach(function(id){var input=document.getElementById(id);patch[id]=input.checked;input.disabled=true;});
  api("/api/settings",patch,function(error){memorySaving=false;if(!error){keys.forEach(function(id){state.settings[id]=patch[id];});}renderPageState();toast(error?error.message:"频道记忆已保存",!!error);refresh();});
}
function gestureInputs(disabled) {
  document.getElementById("leftVerticalGesture").disabled = disabled;
  document.getElementById("rightVerticalGesture").disabled = disabled;
  document.getElementById("resetGestures").disabled = disabled;
}
function renderPageState() {
  if (!state || !state.settings || gestureSaving) return;
  var settings = state.settings;
  if(!groupPasswordSaving){
    var groupInput=document.getElementById("groupPasswordRequired");groupInput.disabled=false;
    if(typeof settings.groupPasswordRequired==="boolean"){
      if(stateRequestSequence>groupPasswordStateAfter){groupInput.checked=settings.groupPasswordRequired;groupPasswordLoaded=true;}
    }
    else loadGroupPasswordState();
  }
  document.getElementById("webEngineMemoryRow").hidden=settings.liteBrowserSupported!==true;
  if(!memorySaving){["rememberChannelSource","rememberChannelDecode","rememberChannelUserAgent","rememberChannelWebEngine"].forEach(function(id){var input=document.getElementById(id);input.checked=settings[id]!==false;input.disabled=id==="rememberChannelWebEngine"&&settings.liteBrowserSupported!==true;});}
  renderBackground(settings);
  document.getElementById("leftVerticalGesture").value = settings.leftVerticalGesture || "brightness";
  document.getElementById("rightVerticalGesture").value = settings.rightVerticalGesture || "channel";
  gestureInputs(false);
  document.getElementById("gestureStatus").textContent = "";
}
function saveGestures() {
  if (gestureSaving || !state || !state.settings) return;
  var left = document.getElementById("leftVerticalGesture").value,
    right = document.getElementById("rightVerticalGesture").value;
  gestureSaving = true;
  gestureInputs(true);
  document.getElementById("gestureStatus").textContent = "正在保存…";
  api("/api/settings", {leftVerticalGesture:left, rightVerticalGesture:right}, function(error) {
    gestureSaving = false;
    if (!error) {
      state.settings.leftVerticalGesture = left;
      state.settings.rightVerticalGesture = right;
    }
    renderPageState();
    if (error) document.getElementById("gestureStatus").textContent = error.message;
    toast(error ? error.message : "滑动操作已保存", !!error);
    refresh();
  });
}
function resetGestures() {
  document.getElementById("leftVerticalGesture").value = "brightness";
  document.getElementById("rightVerticalGesture").value = "channel";
  saveGestures();
}

var backgroundDurationEditing=false,backgroundModeDraft=null;
var backgroundSaving=false,backgroundPreviewKey="",backgroundTimer=null,backgroundStarted=0;
var bingLoading=false;
window.addEventListener("pagehide",function(){clearTimeout(backgroundTimer);clearTimeout(radioUiTimer);radioUiSequence++;},false);
function renderBackground(settings){
  if(backgroundSaving)return;
  var b=settings.personalBackground||{},select=document.getElementById("personalBackground"),preview=document.getElementById("backgroundPreview");
  select.value=backgroundModeDraft||(b.mode||"off");select.disabled=false;document.getElementById("backgroundFile").disabled=false;
  document.getElementById("backgroundStatus").textContent=backgroundModeDraft||b.mode==="off"?"":b.status||"";
  document.getElementById("backgroundCopyright").textContent=b.copyright||"";
  ["backgroundImageUrl","saveBackgroundUrl","backgroundDuration","saveBackgroundDuration"].forEach(function(id){document.getElementById(id).disabled=false;});
  var urlInput=document.getElementById("backgroundImageUrl");
  if(!urlInput._dirty&&document.activeElement!==urlInput)urlInput.value=b.imageUrl||"";
  var seconds=String(b.displaySeconds||1),duration=document.getElementById("backgroundDuration");
  if(!backgroundDurationEditing&&document.activeElement!==duration&&document.activeElement!==document.getElementById("backgroundCustomSeconds")){
    duration.value=["1","3","5"].indexOf(seconds)>=0?seconds:"custom";
    document.getElementById("backgroundCustomSeconds").value=seconds;
    document.getElementById("customDurationRow").style.display=duration.value==="custom"?"":"none";
  }
  updateBackgroundSourceUI(select.value);
  renderBingSelection(b);
  clearTimeout(backgroundTimer);
  if(b.status==="正在载入…"){
    if(!backgroundStarted)backgroundStarted=Date.now();
    if(Date.now()-backgroundStarted<60000)backgroundTimer=setTimeout(function(){if(pageActive&&!backgroundSaving)refresh();},1000);
  }else backgroundStarted=0;
  var key=(b.mode||"off")+":"+(b.revision||0)+":"+!!b.imageAvailable;
  if(key!==backgroundPreviewKey){backgroundPreviewKey=key;if(b.imageAvailable){preview.style.display="block";preview.src="/api/background/image?v="+encodeURIComponent(key);}else{preview.style.display="none";preview.removeAttribute("src");}}
}
function backgroundBusy(value){backgroundSaving=value;["backgroundImageUrl","saveBackgroundUrl","backgroundDuration","backgroundCustomSeconds","saveBackgroundDuration"].forEach(function(id){document.getElementById(id).disabled=value;});document.getElementById("personalBackground").disabled=value;document.getElementById("backgroundFile").disabled=value;document.getElementById("backgroundUploadTrigger").disabled=value;renderBingSelection(state&&state.settings&&state.settings.personalBackground||{});}
function renderBingSelection(background){
  document.getElementById("bingGalleryButton").disabled=backgroundSaving||bingLoading;
  document.getElementById("bingAutoButton").disabled=backgroundSaving;
  document.getElementById("bingAutoButton").checked=background.mode==="bing"&&(typeof background.bingAutoUpdate==="boolean"?background.bingAutoUpdate:!background.bingChoice);
  var buttons=document.getElementById("bingGallery").querySelectorAll("button");
  for(var i=0;i<buttons.length;i++){
    buttons[i].disabled=backgroundSaving;
    buttons[i].setAttribute("aria-pressed",String(background.mode==="bing"&&buttons[i].getAttribute("data-bing-id")===background.bingChoice));
  }
}
function loadBingWallpapers(){
  if(bingLoading||backgroundSaving)return;
  bingLoading=true;document.getElementById("bingGalleryButton").disabled=true;
  document.getElementById("bingGalleryStatus").textContent="正在获取 Bing 壁纸…";
  api("/api/background/bing",null,function(error,result){
    bingLoading=false;
    if(error){document.getElementById("bingGalleryStatus").textContent=error.message;renderBingSelection(state&&state.settings&&state.settings.personalBackground||{});return;}
    var bingImages=result.images||[];
    var gallery=document.getElementById("bingGallery");gallery.textContent="";
    bingImages.forEach(function(image){
      var button=document.createElement("button"),thumbnail=document.createElement("img"),caption=document.createElement("span"),date=document.createElement("small");
      button.type="button";button.className="bing-card";button.setAttribute("data-bing-id",image.id);
      button.setAttribute("aria-label","选择壁纸："+image.title);button.title=image.copyright||image.title;
      thumbnail.src=image.thumbnail;thumbnail.alt=image.title;thumbnail.loading="lazy";
      caption.textContent=image.title;date.textContent=(image.date||"").replace(/^(\d{4})(\d{2})(\d{2})$/,"$1-$2-$3");caption.appendChild(date);
      button.appendChild(thumbnail);button.appendChild(caption);button.onclick=function(){chooseBingWallpaper(image.id);};gallery.appendChild(button);
    });
    document.getElementById("bingGalleryStatus").textContent=(result.stale?"网络不可用，显示已缓存图库。":"")+"共 "+bingImages.length+" 张，点击选择";
    renderBingSelection(state&&state.settings&&state.settings.personalBackground||{});
  });
}
function saveBackgroundPatch(patch,message,onSaved){
  if(backgroundSaving||!state||!state.settings)return;
  backgroundBusy(true);
  api("/api/settings",patch,function(error){
    if(!error){
      var current=state.settings.personalBackground||(state.settings.personalBackground={});
      if(patch.personalBackground){current.mode=patch.personalBackground;backgroundModeDraft=null;}
      if(patch.backgroundDisplaySeconds)current.displaySeconds=patch.backgroundDisplaySeconds;
      if(typeof patch.bingWallpaper==="string"){current.mode="bing";current.bingChoice=patch.bingWallpaper;current.bingAutoUpdate=!patch.bingWallpaper;}
      if(typeof patch.bingAutoUpdate==="boolean"){current.bingAutoUpdate=patch.bingAutoUpdate;if(patch.bingAutoUpdate){current.mode="bing";current.bingChoice="";}}

      if(patch.backgroundImageUrl){current.imageUrl=patch.backgroundImageUrl;current.mode="url";backgroundModeDraft=null;}
      if(onSaved)onSaved();
    }
    backgroundBusy(false);
    if(error)renderBackground(state.settings);
    toast(error?error.message:message,!!error);refresh();
  });
}
function chooseBingWallpaper(id){
  saveBackgroundPatch({bingWallpaper:id},id?"Bing 壁纸已选定，下次启动显示":"已开启每日自动更新");
}
function toggleBingAutoUpdate(){
  if(backgroundSaving||!state||!state.settings)return;
  var b=state.settings.personalBackground||{},enabled=document.getElementById("bingAutoButton").checked;
  if(typeof b.bingAutoUpdate==="boolean"){
    saveBackgroundPatch({bingAutoUpdate:enabled},enabled?"已开启每日更新":"已关闭每日更新，保留当前壁纸");
  }else if(enabled)chooseBingWallpaper("");
  else freezeLegacyBingWallpaper(b);
}
// Older installed apps lack the toggle API. Preserve their exact cached image as a local splash.
function freezeLegacyBingWallpaper(background){
  if(!background.imageAvailable){renderBingSelection(background);toast("壁纸尚未就绪，请稍后再试",true);return;}
  backgroundBusy(true);document.getElementById("backgroundStatus").textContent="正在固定当前壁纸…";
  var read=new XMLHttpRequest(),upload=null,finished=false;
  function finish(error){
    if(finished)return;finished=true;
    if(!error){var b=state.settings.personalBackground;b.mode="custom";b.customAvailable=true;b.bingAutoUpdate=false;backgroundModeDraft=null;}
    backgroundBusy(false);renderBackground(state.settings);
    toast(error||"已停止每日更新，当前壁纸已保留",!!error);
    if(!error)refresh();
  }
  read.open("GET","/api/background/image?v="+encodeURIComponent(background.revision||0),true);
  read.timeout=20000;
  try{read.responseType="arraybuffer";}catch(error){finish("浏览器不支持固定壁纸，请先更新应用");return;}
  read.onload=function(){
    if(finished||upload)return;
    if(read.status!==200||!read.response||!read.response.byteLength){finish("读取当前壁纸失败，请重试");return;}
    upload=new XMLHttpRequest();upload.open("POST","/api/background/upload",true);upload.timeout=30000;
    upload.setRequestHeader("Content-Type","application/octet-stream");
    upload.onreadystatechange=function(){
      if(upload.readyState!==4||finished)return;
      var result;try{result=JSON.parse(upload.responseText);}catch(ignored){}
      finish(upload.status===200&&result&&result.ok?"":result&&result.message||"固定壁纸失败，请重试");
    };
    upload.onerror=function(){finish("固定壁纸失败，请检查连接");};
    upload.ontimeout=function(){finish("固定壁纸超时，请重试");};
    upload.onabort=function(){finish("固定壁纸已取消");};
    try{upload.send(read.response);}catch(error){finish("固定壁纸失败，请重试");}
  };
  read.onerror=function(){finish("读取当前壁纸失败，请检查连接");};
  read.ontimeout=function(){finish("读取当前壁纸超时，请重试");};
  read.onabort=function(){finish("固定壁纸已取消");};
  try{read.send(null);}catch(error){finish("读取当前壁纸失败，请重试");}
}
function updateBackgroundSourceUI(mode){
  document.getElementById("backgroundBingTools").hidden=mode!=="bing";
  document.getElementById("backgroundUploadTools").hidden=mode!=="custom";
  document.getElementById("backgroundUrlTools").hidden=mode!=="url";
  document.getElementById("backgroundUploadTrigger").disabled=backgroundSaving||document.getElementById("backgroundFile").disabled;
}
function saveBackground(){
  if(backgroundSaving||!state||!state.settings)return;
  var mode=document.getElementById("personalBackground").value;
  var current=state.settings.personalBackground||{};
  // URL and a new local image are drafts until their content is submitted.
  if(mode==="url"||(mode==="custom"&&!current.customAvailable)){
    backgroundModeDraft=mode;
    updateBackgroundSourceUI(mode);
    document.getElementById("backgroundStatus").textContent="";
    return;
  }
  backgroundModeDraft=null;
  updateBackgroundSourceUI(mode);
  saveBackgroundPatch({personalBackground:mode},"闪屏设置已保存");
}
function uploadBackground(){
  var input=document.getElementById("backgroundFile"),file=input.files&&input.files[0];if(!file||backgroundSaving)return;
  if(!file.size){toast("请选择非空图片",true);input.value="";return;}
  backgroundBusy(true);document.getElementById("backgroundStatus").textContent="正在上传…";
  var xhr=new XMLHttpRequest();xhr.open("POST","/api/background/upload",true);xhr.setRequestHeader("Content-Type","application/octet-stream");xhr.timeout=0;
  function done(error){if(!error)backgroundModeDraft=null;backgroundBusy(false);input.value="";toast(error||"闪屏图片已保存",!!error);refresh();}
  xhr.onload=function(){var result;try{result=JSON.parse(xhr.responseText);}catch(e){}done(xhr.status===200&&result&&result.ok?"":result&&result.message||"图片上传失败");};
  xhr.onerror=function(){done("图片上传失败，请检查连接");};xhr.ontimeout=function(){done("图片上传超时，请重试");};xhr.send(file);
}

function saveBackgroundUrl(){
  if(backgroundSaving||!state||!state.settings)return;
  var url=document.getElementById("backgroundImageUrl").value.trim();
  if(!/^https?:\/\/[^\s/]+(?:\/|$)/i.test(url)){toast("请输入 HTTP 或 HTTPS 图片链接",true);return;}
  saveBackgroundPatch({backgroundImageUrl:url},"图片链接已保存，正在下载缓存",function(){document.getElementById("backgroundImageUrl")._dirty=false;});
}
function changeBackgroundDuration(){
  var custom=document.getElementById("backgroundDuration").value==="custom";backgroundDurationEditing=custom;
  document.getElementById("customDurationRow").style.display=custom?"":"none";
  if(!custom)saveBackgroundDuration();
}
function saveBackgroundDuration(){
  if(backgroundSaving||!state||!state.settings)return;
  var value=document.getElementById("backgroundDuration").value;
  var seconds=Number(value==="custom"?document.getElementById("backgroundCustomSeconds").value:value);
  if(!isFinite(seconds)||Math.floor(seconds)!==seconds||seconds<1||seconds>120){toast("开屏时间须为 1–120 秒",true);return;}
  saveBackgroundPatch({backgroundDisplaySeconds:seconds},"开屏时间已保存，下次启动生效",function(){backgroundDurationEditing=false;});
}
document.getElementById("backgroundImageUrl").oninput=function(){this._dirty=true;};
startPage();

loadRadioUi();
loadTvUi();
