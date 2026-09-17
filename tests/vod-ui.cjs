const { chromium } = require('../.codex-tmp/ui/node_modules/playwright');
const fs = require('fs');
const assert = require('assert');
(async () => {
  const systemChrome = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
  const browser = await chromium.launch({executablePath:process.env.CHROME_PATH || (fs.existsSync(systemChrome) ? systemChrome : undefined),headless:true});
  try {
    const page = await browser.newPage({viewport:{width:390,height:844}});
    const errors=[];page.on('pageerror',e=>errors.push(e.message));
    const html=fs.readFileSync('app/src/main/res/raw/vod.html','utf8');
    const state={ok:true,sites:[{id:0,name:'测试源',supported:true}],results:[],running:false,cancelled:false,total:0,library:[]};
    const requests=[];
    await page.route('http://ntv.test/**', async route=>{
      if(route.request().method()==='GET')return route.fulfill({contentType:'text/html',body:html});
      const data=route.request().postDataJSON();requests.push(data);let r=state;
      if(data.action==='search')r={ok:true,page:data.page,pagecount:2,items:[{id:'555',name:'测试电视剧',remarks:'完结'}]};
      if(data.action==='detail')r={ok:true,name:'测试电视剧',lines:[{name:'m3u8',skipped:0,episodes:[{name:'第01集',url:'https://media.example/1.m3u8'}]},{name:'网页',skipped:1,episodes:[]}]};
      if(data.action==='import'){state.library=[{key:'test',group:'点播 · 测试电视剧',count:1}];r={ok:true,group:'点播 · 测试电视剧',count:1};}
      if(data.action==='check'){state.running=true;state.total=1;}
      if(data.action==='cancel'){state.running=false;state.cancelled=true;}
      if(data.action==='remove')state.library=[];
      await route.fulfill({contentType:'application/json',body:JSON.stringify(r)});
    });
    await page.goto('http://ntv.test/vod.html');
    await page.getByText('已连接电视。',{exact:true}).waitFor();
    await page.locator('#subscription').fill('https://example.test/config?token=SECRET');
    await page.locator('#subscribe').click();
    await page.getByText('订阅已导入，共 1 个源。',{exact:true}).waitFor();
    assert.equal(await page.locator('#subscription').inputValue(),'');
    await page.locator('#keyword').fill('测试');await page.locator('#search').click();
    await page.getByRole('button',{name:'测试电视剧 完结'}).click();
    await page.getByRole('button',{name:'投递整条线路'}).first().click();
    await page.locator('#library').getByText('点播 · 测试电视剧 · 1 集',{exact:true}).waitFor();
    assert.equal(await page.getByRole('button',{name:'投递整条线路'}).nth(1).isDisabled(),true);
    await page.locator('#checkAll').click();await page.getByText('检测已启动，可随时停止。',{exact:true}).waitFor();
    await page.locator('#cancel').click();await page.getByText('已请求停止检测。',{exact:true}).waitFor();
    await page.locator('#next').click();await page.waitForFunction(()=>document.getElementById('page').textContent==='2 / 2 ');
    assert(requests.some(r=>r.action==='search'&&r.page===2));
    page.on('dialog',d=>d.accept());await page.getByRole('button',{name:'移除分组'}).click();await page.getByText('暂无点播分组。',{exact:true}).waitFor();
    assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
    assert.deepEqual(errors,[]);
    await page.screenshot({path:'.codex-tmp/vod-mobile.png',fullPage:true});
    console.log('PASS browser: subscription/search/pagination/detail/import/check/cancel/remove/mobile layout; no page errors');
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
