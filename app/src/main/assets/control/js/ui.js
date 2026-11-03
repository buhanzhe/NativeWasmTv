/* Native document scroll; only two title opacities change per frame. ES5. */
(function(){
 'use strict';
 var main=document.querySelector('main.secondary-page');
 if(!main||document.querySelector('body.media-page-shell'))return;
 var bar=main.querySelector('.top'),title=bar&&bar.querySelector('.page-title'),back=bar&&bar.querySelector('.back-button');
 if(!bar||!title||!back)return;
 main.className+=' has-scroll-header';
 var heading=document.createElement('h1');heading.className='scroll-page-heading';heading.textContent=title.textContent;
 main.insertBefore(heading,bar.nextSibling);
 var hero=main.querySelector('.config-hero h2');if(hero)hero.className+=' replaced-page-heading';
 title.setAttribute('aria-hidden','true');
 back.setAttribute('aria-label','返回');
 back.innerHTML='<svg width="20" height="24" viewBox="0 0 20 24" aria-hidden="true" focusable="false"><path d="m12.5 5-7 7 7 7" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>';
 if(main.id==='flyMousePage'){
  main.className+=' static-page-header';document.body.className+=' flymouse-static';
  heading.parentNode.removeChild(heading);title.removeAttribute('aria-hidden');title.style.opacity='1';
  return;
 }
 var pending=false,last=-1,raf=window.requestAnimationFrame||window.webkitRequestAnimationFrame||function(fn){return setTimeout(fn,16);};
 function paint(){
  pending=false;
  var y=window.pageYOffset||document.documentElement.scrollTop||0;
  var progress=Math.max(0,Math.min(1,(y-18)/38));
  if(progress===last)return;last=progress;
  title.style.opacity=String(progress);
  heading.style.opacity=String(1-progress);
 }
 function scroll(){if(!pending){pending=true;raf(paint);}}
 window.addEventListener('scroll',scroll,false);
 window.addEventListener('pageshow',scroll,false);
 window.addEventListener('resize',scroll,false);
 paint();
}());

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
 window.NtvMicroSelect={open:open,close:close};
}());

/* Small async confirmation cards; never replace synchronous window.confirm globally. */
(function(){
 'use strict';var layer=null,callback=null,focus=null;
 function finish(ok){if(!layer)return;var old=layer,done=callback;layer=null;callback=null;old.className+=' is-closing';old.style.pointerEvents='none';setTimeout(function(){if(old.parentNode)old.parentNode.removeChild(old);},130);if(focus&&document.documentElement.contains(focus))focus.focus();focus=null;if(window.NtvNavigation)NtvNavigation.overlayClosed('micro-confirm');if(done)done(ok===true);}
 function confirm(message,done){
  if(layer)finish(false);if(window.NtvMicroSelect)NtvMicroSelect.close();focus=document.activeElement;callback=done;
  layer=document.createElement('div');layer.className='micro-confirm-layer';
  var card=document.createElement('div');card.className='micro-confirm-card';card.setAttribute('role','dialog');card.setAttribute('aria-modal','true');card.setAttribute('aria-labelledby','microConfirmTitle');
  var title=document.createElement('h2');title.id='microConfirmTitle';title.textContent='请确认';card.appendChild(title);
  var copy=document.createElement('p');copy.textContent=message;card.appendChild(copy);
  var actions=document.createElement('div');actions.className='micro-confirm-actions';var cancel=document.createElement('button'),accept=document.createElement('button');cancel.type=accept.type='button';cancel.textContent='取消';accept.textContent='确定';accept.className='micro-confirm-accept';cancel.onclick=function(){finish(false);};accept.onclick=function(){finish(true);};actions.appendChild(cancel);actions.appendChild(accept);card.appendChild(actions);layer.appendChild(card);document.body.appendChild(layer);
  layer.onclick=function(e){if(e.target===layer)finish(false);};
  if(window.NtvNavigation)NtvNavigation.overlayOpen('micro-confirm',function(){finish(false);});cancel.focus();
 }
 document.addEventListener('keydown',function(e){if(!layer)return;if(e.key==='Escape'||e.keyCode===27){e.preventDefault();e.stopPropagation();finish(false);}else if(e.key==='Tab'||e.keyCode===9){var buttons=layer.querySelectorAll('button');e.preventDefault();(document.activeElement===buttons[0]?buttons[1]:buttons[0]).focus();}},true);
 window.addEventListener('pagehide',function(){finish(false);},false);
 window.NtvDialogs={confirm:confirm};
}());
