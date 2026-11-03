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
