// ==UserScript==
// @name 斗鱼移动版直播
// @version 1.0.1
// @match https://www.douyu.com/*
// @match https://douyu.com/*
// @match https://m.douyu.com/*
// @user-agent Mozilla/5.0 (iPad; CPU OS 17_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Mobile/15E148 Safari/604.1
// @run-at document-start
// @grant none
// ==/UserScript==
(function(){
    'use strict';
    var page=new URL(location.href);
    if((page.hostname==='www.douyu.com'||page.hostname==='douyu.com')&&/^\/[0-9]+\/?$/.test(page.pathname)&&!page.port&&!page.username&&!page.password){
        page.hostname='m.douyu.com';
        location.replace(page.href);
        return;
    }
    // The headless engine hands the selected HLS to the native player. Portal
    // CSS makes layout of this mobile room prohibitively slow on old ARM CPUs.
    // Keep the original room DOM, scripts, requests and SDK; only omit its visual
    // styles in this background mode. Interactive WebViews retain all styling.
    var config=typeof __host==='function'?__host(JSON.stringify({op:'pageConfig'})):null;
    if(page.hostname!=='m.douyu.com'||!/^\/[0-9]+\/?$/.test(page.pathname)||!config||!config.headless)return;
    // Let the original iPad player use its native HLS branch. Transmuxing the
    // same selected HLS through JS/MSE cannot keep pace on old ARM devices.
    window.MediaSource=undefined;
    window.WebKitMediaSource=undefined;
    window.ManagedMediaSource=undefined;
    function trimStyles(){
        var nodes=document.querySelectorAll('link[rel="stylesheet"],style');
        for(var i=0;i<nodes.length;i++)if(nodes[i].parentNode)nodes[i].parentNode.removeChild(nodes[i]);
    }
    new MutationObserver(trimStyles).observe(document,{childList:true,subtree:true});
    trimStyles();
})();
