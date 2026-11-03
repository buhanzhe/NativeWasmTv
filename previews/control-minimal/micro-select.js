/* Shared anchored picker. ES5, no dependencies, native selects remain the data source. */
(function(){
 'use strict';
 var layer=null,menu=null,target=null,anchor=null,priorFocus=null,touchX=0,touchY=0,moved=false;
 function close(){
  if(!layer)return;
  var old=layer;old.className+=' is-closing';old.style.pointerEvents='none';
  setTimeout(function(){if(old.parentNode)old.parentNode.removeChild(old);},130);layer=null;menu=null;target=null;
  if(anchor)anchor.setAttribute('aria-expanded','false');
  if(priorFocus&&document.documentElement.contains(priorFocus))priorFocus.focus();
  anchor=null;priorFocus=null;
  if(window.NtvNavigation)NtvNavigation.overlayClosed('micro-select');
 }
 function choose(index){
  var select=target,option=select&&select.options[index];
  if(!select||select.disabled||!option||option.disabled)return;
  var changed=select.selectedIndex!==index;select.selectedIndex=index;
  if(select._legacyButton)select._legacyButton.textContent=option.textContent;
  close();
  if(changed){var event=document.createEvent('HTMLEvents');event.initEvent('change',true,false);select.dispatchEvent(event);}
 }
 function open(select,trigger){
  if(!select||select.disabled)return;
  if(target===select)return;
  close();target=select;anchor=trigger||select;priorFocus=document.activeElement;
  layer=document.createElement('div');layer.className='micro-select-layer';
  menu=document.createElement('div');menu.className='micro-select-menu';menu.setAttribute('role','listbox');
  var label=select.getAttribute('aria-label'),labels=select.id&&document.querySelector('label[for="'+select.id+'"]');
  menu.setAttribute('aria-label',label||(labels&&labels.textContent)||'选择设置');
  anchor.setAttribute('aria-expanded','true');anchor.setAttribute('aria-haspopup','listbox');
  var focus=null,maxLength=0;
  for(var i=0;i<select.options.length;i++){
   var option=select.options[i];if(option.hidden)continue;
   var button=document.createElement('button');button.type='button';button.className='micro-select-option';
   button.setAttribute('role','option');button.setAttribute('aria-selected',i===select.selectedIndex?'true':'false');
   button.disabled=option.disabled||(option.parentNode.tagName==='OPTGROUP'&&option.parentNode.disabled);
   button.textContent=option.textContent;button._optionIndex=i;
   maxLength=Math.max(maxLength,option.textContent.length);
   if(i===select.selectedIndex){button.className+=' is-selected';button.innerHTML='';button.appendChild(document.createTextNode(option.textContent));var check=document.createElement('span');check.className='micro-select-check';check.setAttribute('aria-hidden','true');check.textContent='✓';button.appendChild(check);focus=button;}
   button.onclick=function(){choose(this._optionIndex);};menu.appendChild(button);
  }
  layer.appendChild(menu);document.body.appendChild(layer);
  var rect=anchor.getBoundingClientRect(),vw=document.documentElement.clientWidth,vh=window.innerHeight||document.documentElement.clientHeight;
  var width=Math.min(vw-24,Math.max(190,Math.min(260,maxLength*10+52)));
  menu.style.width=width+'px';menu.style.maxHeight=Math.min(340,vh-24)+'px';
  var height=menu.offsetHeight,left=Math.max(12,Math.min(vw-width-12,rect.right-width));
  var top=Math.max(12,Math.min(vh-height-12,rect.top-6));
  menu.style.left=left+'px';menu.style.top=top+'px';
  layer.onclick=function(e){if(e.target===layer)close();};
  layer.addEventListener('touchmove',function(e){if(e.target===layer)e.preventDefault();},false);
  if(window.NtvNavigation)NtvNavigation.overlayOpen('micro-select',close);
  if(focus&&!focus.disabled)focus.focus();
 }
 function isSelect(el){return el&&el.tagName==='SELECT'&&!el.multiple&&el.size<=1;}
 document.addEventListener('mousedown',function(e){if(isSelect(e.target))e.preventDefault();},true);
 document.addEventListener('click',function(e){if(isSelect(e.target)){e.preventDefault();e.stopPropagation();open(e.target);}},true);
 document.addEventListener('touchstart',function(e){if(isSelect(e.target)&&e.touches.length===1){touchX=e.touches[0].clientX;touchY=e.touches[0].clientY;moved=false;}},true);
 document.addEventListener('touchmove',function(e){if(isSelect(e.target)&&e.touches.length===1&&Math.abs(e.touches[0].clientX-touchX)+Math.abs(e.touches[0].clientY-touchY)>10)moved=true;},true);
 document.addEventListener('touchend',function(e){if(isSelect(e.target)&&!moved){e.preventDefault();open(e.target);}},true);
 document.addEventListener('keydown',function(e){
  var key=e.key||e.keyCode;
  if(layer){
   if(key==='Escape'||key===27){e.preventDefault();e.stopPropagation();close();return;}
   if(key==='ArrowDown'||key==='ArrowUp'||key===40||key===38){e.preventDefault();var buttons=menu.querySelectorAll('button:not(:disabled)'),at=0;for(var j=0;j<buttons.length;j++)if(buttons[j]===document.activeElement)at=j;at=(at+((key==='ArrowDown'||key===40)?1:-1)+buttons.length)%buttons.length;if(buttons[at])buttons[at].focus();}
   if(key==='Tab'||key===9)close();
  }else if(isSelect(e.target)&&(key==='Enter'||key===' '||key==='ArrowDown'||key===13||key===32||key===40)){e.preventDefault();open(e.target);}
 },true);
 window.addEventListener('resize',close,false);
 window.addEventListener('scroll',function(e){if(layer&&(e.target===document||e.target===window))close();},false);
 window.addEventListener('pagehide',close,false);
 // Replace the older full-screen picker used by three playback fields.
 if(typeof window.openLegacySelectPicker==='function')window.openLegacySelectPicker=function(select){open(select,select._legacyButton);};
 window.NtvMicroSelect={open:open,close:close};
}());
