package main

// 浏览器单页。
//
// 这一页不是"顺手做的 demo"，它是没有装 App 的人唯一的入口 ——
// 路由器上跑中继的人不会为了开电脑去装个安卓 App，
// 他会在手机浏览器里存一个书签。所以这一页必须自己就能完成
// "选设备 -> 发 -> 等结果"的完整闭环，包括那个秒数。

const pageHTML = `<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#0f1115">
<title>WOL 中继</title><style>
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
background:#0f1115;color:#e8eaed;font-family:-apple-system,"PingFang SC",system-ui,sans-serif;
padding:20px}
.c{width:100%;max-width:420px;text-align:center}
h1{font-size:20px;margin:0 0 6px;font-weight:650}
.s{font-size:13px;color:#8b93a1;margin-bottom:20px}
.sel{width:100%;padding:14px 16px;margin-bottom:14px;font-size:15px;color:#e8eaed;
background:#1a1e26;border:1px solid #2a2f3a;border-radius:12px;appearance:none;
text-align:center}
button{width:100%;padding:20px;font-size:19px;font-weight:650;color:#fff;border:0;
border-radius:16px;background:linear-gradient(135deg,#31c48d,#1e8f66);cursor:pointer;
transition:transform .12s,opacity .2s}
button:active{transform:scale(.97)}
button:disabled{opacity:.55}
#m{margin-top:18px;font-size:15px;min-height:24px;line-height:1.5}
#sub{margin-top:6px;font-size:12px;color:#8b93a1;min-height:18px}
#bar{height:3px;background:#1a1e26;border-radius:2px;margin-top:16px;overflow:hidden;
opacity:0;transition:opacity .3s}
#bar.on{opacity:1}
#bar i{display:block;height:100%;width:0;background:#31c48d;border-radius:2px;
transition:width .4s linear}
.ok{color:#31c48d}.bad{color:#f05252}.wait{color:#8b93a1}
footer{margin-top:30px;font-size:11px;color:#4b515c}
</style></head><body>
<div class="c">
<h1>WOL 中继</h1>
<div class="s">向家里局域网广播，然后盯着它到底醒没醒</div>
<select class="sel" id="dev"></select>
<button id="b">唤 醒</button>
<div id="m"></div><div id="sub"></div><div id="bar"><i></i></div>
<footer>神仙道 · QQ 1974147300</footer>
</div>
<script>
var TOKEN = new URLSearchParams(location.search).get('token') || '';
function q(url){ return TOKEN ? url + (url.indexOf('?')<0?'?':'&') + 'token=' + encodeURIComponent(TOKEN) : url; }
var dev=document.getElementById('dev'), b=document.getElementById('b'),
    m=document.getElementById('m'), sub=document.getElementById('sub'),
    bar=document.getElementById('bar'), fill=bar.querySelector('i');
var pollTimer=null;

function el(ms){ return (ms/1000).toFixed(1).replace(/\.0$/,'') + ' 秒'; }

fetch(q('/devices')).then(function(r){return r.json()}).then(function(j){
  if(!j.devices || !j.devices.length){ dev.style.display='none'; return; }
  if(j.devices.length<2){ dev.style.display='none'; }
  j.devices.forEach(function(d){
    var o=document.createElement('option');
    o.value=d.name; o.textContent=d.name; dev.appendChild(o);
  });
}).catch(function(){
  dev.style.display='none';
});

b.onclick=function(){
  if(pollTimer){ clearInterval(pollTimer); pollTimer=null; }
  b.disabled=true; b.textContent='发送中…';
  m.className='wait'; m.textContent='正在广播魔术包';
  sub.textContent=''; bar.className='on'; fill.style.width='2%';

  fetch(q('/wake'+(dev.value?'?name='+encodeURIComponent(dev.value):'')),{method:'POST'})
  .then(function(r){return r.json()})
  .then(function(j){
    b.disabled=false; b.textContent='再发一次';
    if(!j.ok){
      m.className='bad'; m.textContent='没发出去';
      sub.textContent=j.error || '未知原因';
      bar.className='';
      return;
    }
    m.className='wait'; m.textContent='已广播，等它起来';
    sub.textContent='发出的目标 ' + j.sent + '/' + j.total + '，最多等 ' + j.confirmTimeout + ' 秒';
    watch(j.confirm, j.confirmTimeout);
  }).catch(function(e){
    b.disabled=false; b.textContent='再发一次';
    m.className='bad'; m.textContent='连不上中继';
    sub.textContent=e.message;
    bar.className='';
  });
};

function watch(id, limit){
  var t0=Date.now();
  pollTimer=setInterval(function(){
    var pct=Math.min(96,(Date.now()-t0)/(limit*1000)*100);
    fill.style.width=pct+'%';
    fetch(q('/confirm?id='+encodeURIComponent(id))).then(function(r){return r.json()})
    .then(function(j){
      if(!j.ok) return;
      var v=j.result;
      if(v.state==='online'){
        clearInterval(pollTimer); pollTimer=null;
        fill.style.width='100%';
        if(v.already){
          m.className='ok'; m.textContent='它本来就是开着的';
          sub.textContent='没发唤醒，直接就连上了';
        }else{
          m.className='ok'; m.textContent='已开机 · ' + el((v.onlineIn||0)*1000);
          sub.textContent=(v.ip||'') + (v.port?'   端口 '+v.port:'');
        }
        bar.className='';
      }else if(v.state==='timeout'){
        clearInterval(pollTimer); pollTimer=null;
        m.className='bad'; m.textContent='等了 ' + limit + ' 秒，没等到它起来';
        // 按中继给的 cause 分两路，不要一律甩给 BIOS。
        // 网卡压根没在 ARP 里出现过，说明包没到这个网段或者网卡没上电，
        // 这时候让人去关快速启动是南辕北辙。
        if(v.cause==='no_arp'){
          sub.textContent='中继没在 ARP 表里看到它的网卡 —— 先查网络这一侧：' +
            '中继和电脑在不在同一个网段、网线/交换机通不通、路由器有没有隔离广播。';
        }else if(v.cause==='arp_only'){
          sub.textContent='网卡上电了但系统没起来 —— 问题在电脑这一侧：' +
            'BIOS 里的网络唤醒开关、Windows 快速启动、硬盘或内存自检没过。';
        }else {
          sub.textContent=(v.note||'') ||
            '包已经发出去了，但没等到它起来：快速启动没关 / BIOS 关了网络唤醒 / 网线没插';
        }
        bar.className='';
      }else if(v.arpIn && !sub.dataset.arp){
        sub.dataset.arp='1';
        sub.textContent='网卡已上线（' + el(v.arpIn*1000) + '），等系统起来';
      }
    }).catch(function(){});
  }, 1000);
}
</script></body></html>`
