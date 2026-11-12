const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const s=fs.readFileSync('browser-core/litebrowser/src/main/assets/browser.js','utf8');
const main={src:'blob:current',isConnected:true,paused:false},radio={src:'https://example/radio.m3u8',isConnected:true,paused:true},old={src:'blob:old',isConnected:false,paused:true};let clicks=0,blobPolls=0;
const c={g:{},media(u){if(u.startsWith('blob:'))blobPolls++},doc:{querySelectorAll(q){if(q==='video')return [main,old];if(q==='audio')return [radio];if(q==='source')return [];if(q==='video,audio')return [main,radio,old];return [];}},clickHandlerReady(){return true;}};vm.createContext(c);
vm.runInContext(s.slice(s.indexOf('var autoControls='),s.indexOf('g.__diagnostics=')),c);
c.g.__tryPlayControl=()=>{clicks++;return true};c.g.__auto();assert.equal(clicks,0);assert.equal(blobPolls,0);
main.paused=true;c.g.__auto();assert.equal(clicks,0,'explicit pause must not cause another player to start');
main.isConnected=false;let attempts=0;c.doc.querySelectorAll=q=>q==='video'?[main,old]:q==='audio'?[radio]:q==='source'?[]:q==='video,audio'?[radio]:q==='.vjs-big-play-button'?[{}]:[];c.g.__tryPlayControl=()=>{attempts++;return true};c.g.__auto();assert.equal(attempts,1,'removed owner permits a fresh autoplay target');
console.log('PASS shipping autoplay: respects current owner and pause, releases removed owner, never reattaches old blob URLs');
