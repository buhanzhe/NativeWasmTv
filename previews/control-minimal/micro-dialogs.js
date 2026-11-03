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
