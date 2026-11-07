# -*- coding: utf-8 -*-
"""播放页模板（手机 + TV 双适配）。

设计要点
- 触屏优先：进度条用 Pointer/Touch 事件拖动，命中区高 30px+，拖动时气泡显示时间；
- 双击左右 1/3 快退/快进 10 秒，双击中间暂停/继续，单击唤出/收起控制栏；
- 全屏：优先真·全屏 API（带横屏锁定），电视/投影仪不支持时用「伪全屏」CSS 铺满；
- 选集：手机为底部抽屉，电视为右侧面板，遥控 ▲▼ 移动、OK 选中、返回键关闭；
- 自动连播：可开关（记忆在 localStorage），倒计时 5 秒，可立即播放或取消。
"""

PLAY_TEMPLATE = r"""<!DOCTYPE html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="theme-color" content="#05070b">
<title>{{TITLE}}</title>
<style>
  *{box-sizing:border-box}
  html,body{margin:0;padding:0;height:100%;background:#05070b;color:#e8eef7;
    font-family:system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;
    overflow:hidden;-webkit-user-select:none;user-select:none;-webkit-tap-highlight-color:transparent}

  /* ---------- 视频 ---------- */
  .stage{position:relative;width:100%;height:100vh;height:100dvh;background:#000;
    display:flex;align-items:center;justify-content:center;overflow:hidden;
    touch-action:none}
  video{display:block;width:100%;height:100%;object-fit:contain;background:#000;outline:none}
  body.pfs video{position:fixed;inset:0;width:100vw;height:100vh;z-index:1;object-fit:contain}

  /* 亮度遮罩：盖在视频上，透明度越高画面越暗（模拟调暗） */
  .dim-layer{position:absolute;inset:0;background:#000;opacity:0;pointer-events:none;z-index:2}
  body.pfs .dim-layer{position:fixed;inset:0;z-index:2}

  /* 中央大播放/暂停指示：暂停时显示▶，播放点击时闪现⏸ */
  .bigplay{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);z-index:3;
    width:88px;height:88px;border-radius:50%;background:#0000008c;backdrop-filter:blur(3px);
    display:flex;align-items:center;justify-content:center;color:#fff;pointer-events:none;
    opacity:0;transition:opacity .18s,transform .18s}
  .bigplay.show{opacity:1}
  .bigplay svg{width:42px;height:42px;display:none}
  .bigplay.state-play .bp-play{display:block}
  .bigplay.state-pause .bp-pause{display:block}
  body.pfs .bigplay{position:fixed;z-index:3}

  .spinner{position:fixed;left:50%;top:50%;width:44px;height:44px;margin:-22px 0 0 -22px;
    border:3px solid #ffffff26;border-top-color:#22d3ee;border-radius:50%;z-index:8;
    animation:spin .8s linear infinite;opacity:0;transition:opacity .2s;pointer-events:none}
  .spinner.on{opacity:1}
  @keyframes spin{to{transform:rotate(360deg)}}

  /* ---------- 顶部标题 ---------- */
  .topbar{position:fixed;left:0;right:0;top:0;z-index:6;display:flex;align-items:center;gap:10px;
    padding:calc(10px + env(safe-area-inset-top)) 14px 18px;
    background:linear-gradient(#000e,#000a 55%,#0000);
    transition:opacity .22s,transform .22s}
  .topbar.hide{opacity:0;transform:translateY(-10px);pointer-events:none}
  .back{flex:none;display:inline-flex;align-items:center;gap:6px;background:#ffffff1a;color:#e8eef7;
    text-decoration:none;padding:9px 14px;border-radius:999px;font-size:13px;font-weight:600;border:0;cursor:pointer}
  .back:active{background:#ffffff30}
  .name{flex:1;min-width:0;font-size:14px;font-weight:600;color:#dbe7f5;
    overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .epcount{flex:none;font-size:12px;color:#9fb0c6;background:#ffffff14;padding:5px 10px;border-radius:999px}

  /* ---------- 底部控制 ---------- */
  .bottom{position:fixed;left:0;right:0;bottom:0;z-index:6;
    padding:6px 14px calc(10px + env(safe-area-inset-bottom));
    background:linear-gradient(transparent,#000c 40%,#000 100%);
    display:flex;flex-direction:column;gap:6px;
    transition:opacity .22s,transform .22s}
  .bottom.hide{opacity:0;transform:translateY(12px);pointer-events:none}

  .timeline{position:relative;padding:16px 0 10px;touch-action:none;cursor:pointer}
  .tl-track{position:relative;height:4px;border-radius:99px;background:#ffffff3a}
  .tl-buf{position:absolute;left:0;top:0;bottom:0;width:0;border-radius:99px;background:#ffffff3a}
  .tl-fill{position:absolute;left:0;top:0;bottom:0;width:0;border-radius:99px;background:#22d3ee}
  .tl-dot{position:absolute;top:50%;left:0;width:13px;height:13px;border-radius:50%;
    background:#fff;transform:translate(-50%,-50%);box-shadow:0 0 8px #22d3eecc;
    transition:width .12s,height .12s}
  body.dragging .tl-dot{width:19px;height:19px}
  .tl-tip{position:absolute;bottom:28px;left:0;transform:translateX(-50%) scale(.92);opacity:0;
    background:#0b1220f2;border:1px solid #ffffff1f;color:#e8eef7;font-size:12px;font-weight:700;
    padding:5px 9px;border-radius:8px;pointer-events:none;white-space:nowrap;
    font-variant-numeric:tabular-nums;transition:opacity .12s,transform .12s}
  .tl-tip.on{opacity:1;transform:translateX(-50%) scale(1)}

  .ctrl-main{display:flex;align-items:center;justify-content:center;gap:8px}
  .btn{flex:none;display:flex;align-items:center;justify-content:center;width:42px;height:42px;
    padding:0;border:0;border-radius:50%;background:#ffffff17;color:#e8eef7;font-size:17px;
    line-height:1;cursor:pointer;transition:background .15s,transform .1s}
  .btn:active{background:#22d3ee33;color:#22d3ee;transform:scale(.94)}
  .btn:focus{outline:none;box-shadow:0 0 0 2px #22d3ee}
  .btn:disabled{opacity:.28}
  .btn svg{display:block;pointer-events:none}
  .btn .ten{font-size:13px;font-weight:800;letter-spacing:-.3px;font-variant-numeric:tabular-nums;pointer-events:none}
  body.tv .btn .ten{font-size:16px}
  .btn.primary{width:56px;height:56px;font-size:22px;background:#22d3ee;color:#04121a}
  .btn.primary:active{background:#67e8f9;color:#04121a}

  .ctrl-sub{display:flex;align-items:center;gap:6px;padding-bottom:2px}
  .time{flex:1;min-width:0;font-size:12px;color:#8fa1b8;font-variant-numeric:tabular-nums;
    overflow:hidden;white-space:nowrap}
  .time .cur{color:#e8eef7;font-weight:700}
  .sub-btn{flex:none;height:32px;padding:0 11px;border:0;border-radius:999px;background:#ffffff14;
    color:#cfdcea;font-size:12px;font-weight:600;cursor:pointer;
    display:inline-flex;align-items:center;gap:4px;transition:.15s;
    max-width:5.4em;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .sub-btn:active{background:#ffffff28}
  .sub-btn.on{background:#22d3ee26;color:#7ee7f7}
  .sub-btn:focus{outline:none;box-shadow:0 0 0 2px #22d3ee}
  .sub-btn[hidden]{display:none}

  /* ---------- 字幕样式（webvtt 只能这样调） ---------- */
  video::cue{
    font-family:system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;
    font-size:5.2vh;line-height:1.32;color:#fff;
    background:rgba(0,0,0,.55);
    text-shadow:0 1px 4px rgba(0,0,0,.9)
  }
  body.tv video::cue{font-size:4.4vh}
  @media (orientation:landscape){video::cue{font-size:4.6vh}}

  /* ---------- 选集面板 ---------- */
  .ep-mask{position:fixed;inset:0;z-index:21;background:#000a;opacity:0;pointer-events:none;
    transition:opacity .22s}
  .ep-mask.on{opacity:1;pointer-events:auto}

  .eppanel{position:fixed;left:0;right:0;bottom:0;z-index:22;display:flex;flex-direction:column;
    max-height:76vh;background:#0c1220f7;-webkit-backdrop-filter:blur(18px);backdrop-filter:blur(18px);
    border-top:1px solid #ffffff1a;border-radius:18px 18px 0 0;
    padding-bottom:env(safe-area-inset-bottom);
    transform:translateY(101%);transition:transform .26s cubic-bezier(.22,.8,.28,1)}
  .eppanel.open{transform:translateY(0)}
  .ephead{flex:none;display:flex;align-items:center;justify-content:space-between;
    padding:14px 16px 10px;border-bottom:1px solid #ffffff12}
  .ephead h3{margin:0;font-size:15px;font-weight:700;letter-spacing:.3px}
  .epclose{width:32px;height:32px;border-radius:50%;background:#ffffff16;border:0;
    color:#dfe9f5;font-size:15px;cursor:pointer}
  .eplist{flex:1;overflow-y:auto;-webkit-overflow-scrolling:touch;padding:8px 10px 18px}
  .eplist::-webkit-scrollbar{width:5px}
  .eplist::-webkit-scrollbar-thumb{background:#2a3a50;border-radius:99px}
  .epitem{display:flex;align-items:center;gap:10px;min-height:50px;padding:8px 10px;
    border-radius:11px;border:1px solid transparent;cursor:pointer;transition:background .12s}
  .epitem:active{background:#ffffff12}
  .epitem.current{background:#22d3ee1c;border-color:#22d3ee4d}
  .epitem:focus{outline:none;background:#22d3ee22;border-color:#22d3ee66}
  .epnum{flex:none;width:38px;height:38px;border-radius:9px;background:#ffffff12;color:#9fb0c6;
    display:flex;align-items:center;justify-content:center;font-size:14px;font-weight:700}
  .epitem.current .epnum{background:#22d3ee;color:#04121a}
  .epinfo{flex:1;min-width:0}
  .epname{font-size:13.5px;font-weight:600;line-height:1.35;color:#e8eef7;
    display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;
    overflow:hidden;word-break:break-word}
  .epsize{font-size:11px;color:#5f7186;margin-top:2px}
  .playing{flex:none;font-size:10.5px;color:#22d3ee;background:#22d3ee1f;padding:3px 8px;border-radius:99px}

  /* ---------- 自动连播 ---------- */
  .next-overlay{position:fixed;inset:0;z-index:30;background:#000c;display:none;
    align-items:center;justify-content:center;padding:24px;backdrop-filter:blur(3px)}
  .next-overlay.on{display:flex}
  .next-card{display:flex;flex-direction:column;align-items:center;gap:14px;
    background:#0b1220f7;border:1px solid #ffffff20;border-radius:18px;
    padding:22px 26px;max-width:min(430px,88vw);text-align:center;
    box-shadow:0 20px 60px #000b}
  .nlabel{font-size:12px;color:#8fa1b8;letter-spacing:1px}
  .ntitle{font-size:19px;font-weight:700;line-height:1.35}
  .ncount{font-size:13px;color:#7ee7f7}
  .next-bar{width:100%;min-width:180px;height:4px;background:#ffffff1f;border-radius:99px;overflow:hidden}
  .nfill{height:100%;width:0;background:#22d3ee;transition:width .95s linear}
  .next-actions{display:flex;gap:12px;margin-top:2px}
  .next-actions button{padding:11px 26px;border:0;border-radius:11px;font-size:14px;font-weight:700;
    cursor:pointer;font-family:inherit}
  .play-now{background:#22d3ee;color:#04121a}
  .cancel-btn{background:#ffffff26;color:#e8eef7}
  .cancel-btn:active{background:#ffffff38}

  .toast{position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);z-index:40;
    background:#000d;color:#fff;padding:14px 26px;border-radius:14px;font-size:19px;font-weight:700;
    opacity:0;pointer-events:none;transition:opacity .2s;max-width:80vw;text-align:center}
  .toast.show{opacity:1}

  /* ---------- 亮度 / 音量调节面板（底部弹出） ---------- */
  .adj-mask{position:fixed;inset:0;z-index:21;background:#000a;opacity:0;pointer-events:none;
    transition:opacity .22s}
  .adj-mask.on{opacity:1;pointer-events:auto}
  .adjpanel{position:fixed;left:0;right:0;bottom:0;z-index:22;display:flex;flex-direction:column;
    background:#0c1220f7;-webkit-backdrop-filter:blur(18px);backdrop-filter:blur(18px);
    border-top:1px solid #ffffff1a;border-radius:18px 18px 0 0;
    padding:6px 18px calc(12px + env(safe-area-inset-bottom));
    transform:translateY(101%);transition:transform .26s cubic-bezier(.22,.8,.28,1)}
  .adjpanel.open{transform:translateY(0)}
  .adjhead{flex:none;display:flex;align-items:center;justify-content:space-between;
    padding:12px 0 6px;border-bottom:1px solid #ffffff12}
  .adjhead h3{margin:0;font-size:15px;font-weight:700;letter-spacing:.3px}
  .adjclose{width:32px;height:32px;border-radius:50%;background:#ffffff16;border:0;
    color:#dfe9f5;font-size:15px;cursor:pointer}
  .adjrow{flex:none;display:flex;align-items:center;gap:12px;padding:14px 2px}
  .adjrow + .adjrow{border-top:1px solid #ffffff0d}
  .adj-ico{flex:none;width:38px;height:38px;border-radius:10px;background:#ffffff12;
    display:flex;align-items:center;justify-content:center;font-size:18px}
  .adj-ico svg{display:block}
  .adj-label{flex:none;width:40px;font-size:13px;font-weight:700;color:#dbe7f5}
  .adj-track{flex:1;position:relative;height:34px;display:flex;align-items:center;
    touch-action:none;cursor:pointer}
  .adj-track .bar{position:relative;width:100%;height:5px;border-radius:99px;background:#ffffff30}
  .adj-track .fill{position:absolute;left:0;top:0;bottom:0;width:0;border-radius:99px;background:#22d3ee}
  .adj-track .dot{position:absolute;top:50%;left:0;width:17px;height:17px;border-radius:50%;
    background:#fff;transform:translate(-50%,-50%);box-shadow:0 0 8px #22d3eecc}
  .adj-val{flex:none;width:46px;text-align:right;font-size:13px;font-weight:700;
    color:#e8eef7;font-variant-numeric:tabular-nums}
  body.tv .adjpanel{left:auto;right:0;bottom:0;top:auto;width:min(420px,46vw);
    border-radius:18px 0 0 0;border-top:1px solid #ffffff15;border-left:1px solid #ffffff15;
    transform:translateY(101%)}
  body.tv .adjpanel.open{transform:translateY(0)}
  body.tv .adjhead h3{font-size:20px}
  body.tv .adj-ico{width:46px;height:46px;font-size:22px}
  body.tv .adj-label{font-size:16px;width:52px}
  body.tv .adj-val{font-size:16px;width:58px}
  body.tv .adj-track{height:44px}
  body.tv .adjrow{padding:18px 2px}

  /* ---------- 电视 / 投影仪 ---------- */
  body.tv .topbar{padding:18px 24px 24px}
  body.tv .back{font-size:17px;padding:11px 20px}
  body.tv .name{font-size:21px}
  body.tv .epcount{font-size:15px;padding:7px 14px}
  body.tv .btn{width:54px;height:54px;font-size:22px}
  body.tv .btn.primary{width:68px;height:68px;font-size:28px}
  body.tv .ctrl-main{gap:14px}
  body.tv .time{font-size:16px}
  body.tv .sub-btn{height:42px;font-size:15px;padding:0 16px}
  body.tv .timeline{padding:18px 0 14px}
  body.tv .tl-track{height:6px}
  body.tv .tl-dot{width:16px;height:16px}
  body.tv .tl-tip{font-size:16px;padding:7px 12px;bottom:34px}
  body.tv .ephead{padding:20px 20px 14px}
  body.tv .ephead h3{font-size:22px}
  body.tv .epitem{min-height:60px}
  body.tv .epnum{width:48px;height:48px;font-size:18px;border-radius:11px}
  body.tv .epname{font-size:17px}
  body.tv .epsize{font-size:13px}
  body.tv .playing{font-size:14px}
  body.tv .ntitle{font-size:28px}
  body.tv .nlabel{font-size:16px}
  body.tv .ncount{font-size:19px}
  body.tv .next-actions button{font-size:18px;padding:14px 30px}
  body.tv .toast{font-size:28px;padding:20px 36px}

  /* 电视端选集：右侧抽屉 */
  body.tv .eppanel{top:0;left:auto;right:0;bottom:0;width:min(420px,46vw);max-height:none;
    border-top:0;border-left:1px solid #ffffff15;border-radius:0;padding-bottom:0;
    transform:translateX(101%)}
  body.tv .eppanel.open{transform:translateX(0)}

  /* 手机横屏 / 矮屏：压缩控制栏高度 */
  @media (orientation:landscape) and (max-height:520px){
    .timeline{padding:8px 0 4px}
    .bottom{gap:2px;padding-top:2px}
    .btn{width:34px;height:34px;font-size:15px}
    .btn.primary{width:44px;height:44px;font-size:19px}
    .ctrl-main{gap:10px}
    .sub-btn{height:26px;font-size:11px;padding:0 8px}
    .time{font-size:10.5px}
    body.tv .btn{width:44px;height:44px;font-size:19px}
    body.tv .btn.primary{width:54px;height:54px;font-size:23px}
  }
</style></head>
<body>
<div class="stage" id="stage">
  <video id="v" autoplay playsinline webkit-playsinline preload="metadata"
         x5-video-player-type="h5-page" src="/media/{{SRC}}">{{SUB}}</video>
  <div class="dim-layer" id="dimLayer"></div>
  <div class="bigplay" id="bigPlay" hidden>
    <svg class="bp-play" viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5.5v13a1 1 0 0 0 1.5.87l11-6.5a1 1 0 0 0 0-1.74l-11-6.5A1 1 0 0 0 8 5.5z" fill="currentColor"/></svg>
    <svg class="bp-pause" viewBox="0 0 24 24" aria-hidden="true"><rect x="6" y="5" width="4.4" height="14" rx="1.2" fill="currentColor"/><rect x="13.6" y="5" width="4.4" height="14" rx="1.2" fill="currentColor"/></svg>
  </div>
</div>
<div class="spinner" id="spinner"></div>

<div class="topbar" id="topbar">
  <a class="back" id="backBtn" href="{{BACK}}">← 返回</a>
  <div class="name" id="vname">{{NAME}}</div>
  <div class="epcount" id="epcount"></div>
</div>

<div class="bottom" id="bottom">
  <div class="timeline" id="timeline">
    <div class="tl-track">
      <div class="tl-buf" id="tlBuf"></div>
      <div class="tl-fill" id="tlFill"></div>
      <div class="tl-dot" id="tlDot"></div>
    </div>
    <div class="tl-tip" id="tlTip">00:00</div>
  </div>
  <div class="ctrl-main">
    <button class="btn" id="btnPrev" title="上一集" aria-label="上一集">
      <svg viewBox="0 0 24 24" width="21" height="21" aria-hidden="true"><path d="M18.6 5.2v13.6L8.7 12.1z" fill="currentColor"/><rect x="4.6" y="5.4" width="2.5" height="13.2" rx="1.1" fill="currentColor"/></svg>
    </button>
    <button class="btn" id="btnBack10" title="后退10秒" aria-label="后退10秒">
      <span class="ten">−10</span>
    </button>
    <button class="btn primary" id="btnPlay" title="播放/暂停" aria-label="播放/暂停">
      <svg class="ic-play" viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"><path d="M8 5.5v13a1 1 0 0 0 1.5.87l11-6.5a1 1 0 0 0 0-1.74l-11-6.5A1 1 0 0 0 8 5.5z" fill="currentColor"/></svg>
      <svg class="ic-pause" viewBox="0 0 24 24" width="24" height="24" aria-hidden="true" style="display:none"><rect x="6" y="5" width="4.4" height="14" rx="1.2" fill="currentColor"/><rect x="13.6" y="5" width="4.4" height="14" rx="1.2" fill="currentColor"/></svg>
    </button>
    <button class="btn" id="btnFwd10" title="前进10秒" aria-label="前进10秒">
      <span class="ten">+10</span>
    </button>
    <button class="btn" id="btnNext" title="下一集" aria-label="下一集">
      <svg viewBox="0 0 24 24" width="21" height="21" aria-hidden="true"><path d="M5.4 5.2v13.6L15.3 12.1z" fill="currentColor"/><rect x="16.9" y="5.4" width="2.5" height="13.2" rx="1.1" fill="currentColor"/></svg>
    </button>
  </div>
  <div class="ctrl-sub">
    <div class="time"><span class="cur" id="timeCur">00:00</span> / <span id="timeTotal">00:00</span></div>
    <button class="sub-btn" id="btnSub" hidden>字幕关</button>
    <button class="sub-btn" id="btnAudio" hidden>音轨</button>
    <button class="sub-btn" id="btnEps">选集</button>
    <button class="sub-btn" id="btnSpeed">1×</button>
    <button class="sub-btn" id="btnAuto">连播 开</button>
    <button class="sub-btn" id="btnAdj">亮度/音量</button>
    <button class="sub-btn" id="btnFs">全屏</button>
  </div>
</div>

<div class="ep-mask" id="epMask"></div>
<div class="eppanel" id="eppanel">
  <div class="ephead">
    <h3>选集</h3>
    <button class="epclose" id="epClose">✕</button>
  </div>
  <div class="eplist" id="eplist"></div>
</div>

<div class="adj-mask" id="adjMask"></div>
<div class="adjpanel" id="adjpanel">
  <div class="adjhead">
    <h3>亮度 / 音量</h3>
    <button class="adjclose" id="adjClose">✕</button>
  </div>
  <div class="adjrow" id="rowBright">
    <div class="adj-ico">
      <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="#e8eef7" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>
    </div>
    <div class="adj-label">亮度</div>
    <div class="adj-track" id="brightTrack"><div class="bar"><div class="fill" id="brightFill"></div></div><div class="dot" id="brightDot"></div></div>
    <div class="adj-val" id="brightVal">100%</div>
  </div>
  <div class="adjrow" id="rowVol">
    <div class="adj-ico">
      <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="#e8eef7" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5L6 9H2v6h4l5 4V5z"/><path d="M15.5 8.5a5 5 0 010 7"/><path d="M18.5 5.5a9 9 0 010 13"/></svg>
    </div>
    <div class="adj-label">音量</div>
    <div class="adj-track" id="volTrack"><div class="bar"><div class="fill" id="volFill"></div></div><div class="dot" id="volDot"></div></div>
    <div class="adj-val" id="volVal">100%</div>
  </div>
</div>

<div class="next-overlay" id="nextOverlay">
  <div class="next-card">
    <div class="nlabel" id="nextLabel">即将播放</div>
    <div class="ntitle" id="nextTitle">下一集</div>
    <div class="ncount" id="nextCountBox"><span id="nextCountdown">5</span> 秒后自动播放</div>
    <div class="next-bar" id="nextBar"><div class="nfill" id="nextFill"></div></div>
    <div class="next-actions">
      <button class="play-now" id="nextPlayNow">立即播放</button>
      <button class="cancel-btn" id="nextCancel">取消</button>
    </div>
  </div>
</div>

<div class="toast" id="toast"></div>

<script>
(function(){
  "use strict";

  var $ = function(id){ return document.getElementById(id); };

  /* ---------- 设备判定 ---------- */
  var ua = navigator.userAgent || "";
  var isTV = /TV|BRAVIA|MiBOX|NVIDIA SHIELD|Xming|Formovie|AFT|SmartTV|Web0S|NetCast|Android TV|Google ?TV/i.test(ua);
  var isPhone = !isTV && (/iPhone|iPod|Android.*Mobile|Windows Phone/i.test(ua) ||
                (navigator.maxTouchPoints > 0 && Math.min(screen.width, screen.height) < 820));
  if (isTV) document.body.classList.add('tv');
  if (isPhone) document.body.classList.add('mobile');

  var v = $('v'), stage = $('stage'), topbar = $('topbar'), bottom = $('bottom');
  var spinner = $('spinner'), toast = $('toast');
  var eppanel = $('eppanel'), eplist = $('eplist'), epMask = $('epMask');
  var nextOverlay = $('nextOverlay');
  var tlFill = $('tlFill'), tlBuf = $('tlBuf'), tlDot = $('tlDot'), tlTip = $('tlTip');
  var timeCur = $('timeCur'), timeTotal = $('timeTotal'), epcount = $('epcount');
  var btnPlay = $('btnPlay'), btnPrev = $('btnPrev'), btnNext = $('btnNext');
  var bigPlay = $('bigPlay');
  var btnSpeed = $('btnSpeed'), btnAuto = $('btnAuto'), btnFs = $('btnFs');
  var btnSub = $('btnSub');
  var btnAdj = $('btnAdj'), adjpanel = $('adjpanel'), adjMask = $('adjMask');
  var dimLayer = $('dimLayer');
  var brightTrack = $('brightTrack'), brightFill = $('brightFill'), brightDot = $('brightDot'), brightVal = $('brightVal');
  var volTrack = $('volTrack'), volFill = $('volFill'), volDot = $('volDot'), volVal = $('volVal');

  // 遥控器上 ±10 秒要按十几次才跳过片头，电视用 ±60；手机保留 ±10 更精细
  var SEEK = isTV ? 60 : 10;
  var SEEK_FINE = 10;
  $('btnBack10').querySelector('.ten').textContent = '−' + SEEK;
  $('btnFwd10').querySelector('.ten').textContent = '+' + SEEK;
  $('btnBack10').title = '后退' + SEEK + '秒';
  $('btnBack10').setAttribute('aria-label', '后退' + SEEK + '秒');
  $('btnFwd10').title = '前进' + SEEK + '秒';
  $('btnFwd10').setAttribute('aria-label', '前进' + SEEK + '秒');

  var episodes = [], currentIdx = -1;
  var hideTimer = null, nextTimer = null, nextLeft = 5, toastTimer = null;
  var dragging = false, dragPct = 0, lastTap = 0, tapTimer = null;

  /* ---------- 自动连播偏好 ---------- */
  var autoNext = true;
  try { autoNext = localStorage.getItem('ms.autonext') !== '0'; } catch(e){}
  function paintAuto(){
    btnAuto.textContent = autoNext ? '连播 开' : '连播 关';
    btnAuto.classList.toggle('on', autoNext);
  }

  /* ---------- 小工具 ---------- */
  function fmt(s){
    if (!isFinite(s) || s < 0) s = 0;
    var h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = Math.floor(s % 60);
    function p(n){ return n < 10 ? '0' + n : '' + n; }
    return h > 0 ? h + ':' + p(m) + ':' + p(sec) : p(m) + ':' + p(sec);
  }
  function showToast(msg, dur){
    toast.textContent = msg;
    toast.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function(){ toast.classList.remove('show'); }, dur || 1400);
  }
  function esc(s){
    return String(s).replace(/[&<>"']/g, function(c){
      return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
    });
  }

  /* ---------- 控制栏显隐 ---------- */
  function showBars(){
    topbar.classList.remove('hide');
    bottom.classList.remove('hide');
    clearTimeout(hideTimer);
    hideTimer = setTimeout(function(){
      if (dragging) return;
      if (eppanel.classList.contains('open') || nextOverlay.classList.contains('on') ||
          adjpanel.classList.contains('open')) return;
      topbar.classList.add('hide');
      bottom.classList.add('hide');
    }, 4200);
  }
  function hideBars(){
    clearTimeout(hideTimer);
    topbar.classList.add('hide');
    bottom.classList.add('hide');
  }
  function toggleBars(){
    if (bottom.classList.contains('hide')) showBars(); else hideBars();
  }

  /* ---------- 集数 ---------- */
  function loadEpisodes(){
    fetch('/api/episodes?path={{SRC}}')
      .then(function(r){ return r.json(); })
      .then(function(data){
        episodes = data.episodes || [];
        for (var i = 0; i < episodes.length; i++){
          if (episodes[i].current){ currentIdx = i; break; }
        }
        renderEpisodes();
        paintEpCount();
        paintNav();
      }).catch(function(){});
  }

  function paintEpCount(){
    if (episodes.length > 1 && currentIdx >= 0){
      epcount.textContent = '第 ' + (currentIdx + 1) + ' / ' + episodes.length + ' 集';
    } else {
      epcount.textContent = '';
    }
  }

  function paintNav(){
    btnPrev.disabled = currentIdx <= 0;
    btnNext.disabled = currentIdx < 0 || currentIdx >= episodes.length - 1;
    var both = episodes.length <= 1;
    btnPrev.style.display = both ? 'none' : '';
    btnNext.style.display = both ? 'none' : '';
  }

  function renderEpisodes(){
    eplist.innerHTML = '';
    episodes.forEach(function(ep, i){
      var item = document.createElement('div');
      item.className = 'epitem' + (i === currentIdx ? ' current' : '');
      item.tabIndex = 0;
      item.innerHTML =
        '<div class="epnum">' + (i + 1) + '</div>' +
        '<div class="epinfo">' +
          '<div class="epname">' + esc(ep.title || ep.name) + '</div>' +
          '<div class="epsize">' + esc(ep.size_text || '') + '</div>' +
        '</div>' +
        (i === currentIdx ? '<div class="playing">播放中</div>' : '');
      item.addEventListener('click', function(ev){
        ev.stopPropagation();
        switchEpisode(i);
      });
      eplist.appendChild(item);
    });
  }

  function switchEpisode(idx){
    if (idx < 0 || idx >= episodes.length) return;
    if (idx === currentIdx && !v.ended){ closeEps(); return; }
    saveProgress(true);          // 换集前先把这一集看到哪存下来
    cancelNext();
    currentIdx = idx;
    var ep = episodes[idx];
    try {
      history.replaceState({p: ep.path}, '',
        '/play?path=' + encodeURIComponent(ep.path) + '&title=' + encodeURIComponent(ep.title || ep.name));
    } catch(e){}
    pPath = ep.path;
    loadProgress();
    v.src = '/media/' + encodeURIComponent(ep.path);
    v.load();
    var pr = v.play();
    if (pr && pr.catch) pr.catch(function(){ showToast('点 ▶ 继续播放'); });
    $('vname').textContent = ep.title || ep.name;
    renderEpisodes();
    paintEpCount();
    paintNav();
    closeEps();
    showBars();
    showToast('▶ ' + (ep.title || ep.name), 1600);
  }
  function nextEpisode(){ if (currentIdx >= 0 && currentIdx < episodes.length - 1) switchEpisode(currentIdx + 1); }
  function prevEpisode(){ if (currentIdx > 0) switchEpisode(currentIdx - 1); }

  function openEps(){
    eppanel.classList.add('open');
    epMask.classList.add('on');
    showBars();
    var cur = eplist.querySelector('.epitem.current');
    if (cur){
      try { cur.focus({preventScroll:true}); } catch(e){ cur.focus(); }
      setTimeout(function(){
        var top = cur.offsetTop - eplist.clientHeight / 2 + cur.offsetHeight / 2;
        eplist.scrollTop = Math.max(0, top);
      }, 60);
    }
  }
  function closeEps(){
    eppanel.classList.remove('open');
    epMask.classList.remove('on');
    showBars();
    try { v.focus(); } catch(e){}
  }

  /* ---------- 播放控制 ---------- */
  var _bigPlayTimer = null;
  function paintPlay(playing){
    // 底部按钮图标切换
    var icP = btnPlay.querySelector('.ic-play');
    var icPa = btnPlay.querySelector('.ic-pause');
    if (icP) icP.style.display = playing ? 'none' : 'block';
    if (icPa) icPa.style.display = playing ? 'block' : 'none';
    // 中央大图标：暂停时常亮，播放时点击后短暂闪现
    if (!playing) {
      bigPlay.classList.add('show');
      bigPlay.classList.remove('state-pause');
      bigPlay.classList.add('state-play');
    } else {
      bigPlay.classList.remove('state-play');
      bigPlay.classList.add('state-pause');
    }
  }
  function flashBigPlay(){
    // 播放中点击：闪现一下 ⏸ 再淡出
    if (v.paused) return;
    bigPlay.classList.remove('state-play');
    bigPlay.classList.add('state-pause');
    bigPlay.classList.add('show');
    if (_bigPlayTimer) clearTimeout(_bigPlayTimer);
    _bigPlayTimer = setTimeout(function(){
      bigPlay.classList.remove('show');
    }, 500);
  }
  function togglePlay(){
    if (v.paused){ var p = v.play(); if (p && p.catch) p.catch(function(){}); }
    else v.pause();
    if (!v.paused) flashBigPlay();
    showBars();
  }
  function seekBy(sec){
    if (isFinite(v.duration) && v.duration > 0){
      v.currentTime = Math.max(0, Math.min(v.duration - 0.3, v.currentTime + sec));
      showToast((sec > 0 ? '快进 ' : '快退 ') + Math.abs(sec) + ' 秒', 700);
    }
    showBars();
  }

  btnPlay.addEventListener('click', togglePlay);
  btnPrev.addEventListener('click', function(e){ e.stopPropagation(); prevEpisode(); });
  btnNext.addEventListener('click', function(e){ e.stopPropagation(); nextEpisode(); });
  $('btnBack10').addEventListener('click', function(e){ e.stopPropagation(); seekBy(-SEEK); });
  $('btnFwd10').addEventListener('click', function(e){ e.stopPropagation(); seekBy(SEEK); });
  $('btnEps').addEventListener('click', function(e){
    e.stopPropagation();
    if (eppanel.classList.contains('open')) closeEps(); else openEps();
  });
  $('epClose').addEventListener('click', function(e){ e.stopPropagation(); closeEps(); });
  epMask.addEventListener('click', closeEps);

  /* ---------- 倍速 ---------- */
  var speeds = [1, 1.25, 1.5, 2];
  var speedIdx = 0;
  btnSpeed.addEventListener('click', function(e){
    e.stopPropagation();
    speedIdx = (speedIdx + 1) % speeds.length;
    v.playbackRate = speeds[speedIdx];
    btnSpeed.textContent = speeds[speedIdx] + '×';
    btnSpeed.classList.toggle('on', speeds[speedIdx] !== 1);
    try { localStorage.setItem('ms.speed', String(speedIdx)); } catch(err){}
    showToast('倍速 ' + speeds[speedIdx] + '×', 900);
  });
  try {
    var savedSpeed = parseInt(localStorage.getItem('ms.speed') || '0', 10);
    if (savedSpeed > 0 && savedSpeed < speeds.length){
      speedIdx = savedSpeed;
      v.playbackRate = speeds[speedIdx];
      btnSpeed.textContent = speeds[speedIdx] + '×';
      btnSpeed.classList.toggle('on', speeds[speedIdx] !== 1);
    }
  } catch(err){}

  /* ---------- 字幕（内嵌轨由服务端抽成 VTT） ---------- */
  var subOff = false;
  try { subOff = localStorage.getItem('ms.suboff') === '1'; } catch(err){}
  var subIdx = subOff ? -1 : 0;

  function subList(){
    var out = [], list = v.textTracks;
    for (var i = 0; i < list.length; i++) out.push(list[i]);
    return out;
  }
  function paintSub(){
    var list = subList();
    if (!list.length){ btnSub.hidden = true; return; }
    btnSub.hidden = false;
    for (var i = 0; i < list.length; i++){
      try { list[i].mode = (i === subIdx) ? 'showing' : 'disabled'; } catch(err){}
    }
    if (subIdx >= 0 && list[subIdx]){
      btnSub.textContent = list[subIdx].label || '字幕';
      btnSub.classList.add('on');
    } else {
      btnSub.textContent = '字幕关';
      btnSub.classList.remove('on');
    }
  }
  btnSub.addEventListener('click', function(e){
    e.stopPropagation();
    var n = subList().length;
    if (!n) return;
    subIdx = (subIdx + 1 >= n) ? -1 : subIdx + 1;
    subOff = subIdx < 0;
    try { localStorage.setItem('ms.suboff', subOff ? '1' : '0'); } catch(err){}
    paintSub();
    if (subIdx < 0) showToast('字幕关闭', 900);
    else showToast('字幕：' + (subList()[subIdx].label || '中文'), 1100);
    showBars();
  });
  // 轨道是异步加载的：addtrack 事件 + 起播后几次兜底刷新，确保字幕按钮出现
  function wireSubRefresh(){
    try {
      v.textTracks.addEventListener('addtrack', function(){ setTimeout(paintSub, 80); });
    } catch(err){}
    [120, 600, 1600, 3200].forEach(function(ms){
      setTimeout(function(){ if (!btnSub.hidden || subList().length) paintSub(); }, ms);
    });
  }
  wireSubRefresh();
  function primeSubtitle(){
    paintSub();
  }

  /* ---------- 音轨切换（浏览器只读得到 audioTracks，名字靠服务端 ffprobe） ---------- */
  var AUDIO_LABELS = {{AUDIO}} || [];
  var btnAudio = $('btnAudio');
  var audioIdx = 0;

  function paintAudio(){
    if (btnAudio.hidden) return;
    if (!AUDIO_LABELS.length){
      btnAudio.hidden = true;
      return;
    }
    if (audioIdx >= 0 && AUDIO_LABELS[audioIdx]){
      btnAudio.textContent = '音轨 ' + AUDIO_LABELS[audioIdx].label;
      btnAudio.classList.toggle('on', AUDIO_LABELS.length > 1);
    }
  }
  btnAudio.addEventListener('click', function(e){
    e.stopPropagation();
    var tracks = v.audioTracks || [];
    if (!tracks.length || !AUDIO_LABELS.length) return;
    audioIdx = (audioIdx + 1) % AUDIO_LABELS.length;
    // audioTracks 顺序可能和 ffprobe 顺序不一致，这里按 index 映射
    var wanted = AUDIO_LABELS[audioIdx];
    var applied = false;
    for (var i = 0; i < tracks.length; i++){
      var t = tracks[i];
      if (wanted && t.id !== undefined && String(t.id) === String(wanted.index)){
        try { t.enabled = true; applied = true; } catch(err){}
      } else {
        try { t.enabled = false; } catch(err){}
      }
    }
    // 万一 id 对不上，退而求其次：按数组顺序切
    if (!applied){
      var pos = audioIdx % tracks.length;
      for (var j = 0; j < tracks.length; j++){
        try { tracks[j].enabled = (j === pos); } catch(err){}
      }
    }
    paintAudio();
    showToast('音轨：' + (wanted ? wanted.label : ''), 1100);
    showBars();
  });
  paintAudio();

  /* ---------- 播放记忆（存服务端，手机和投影仪共用） ---------- */
  var pPath = decodeURIComponent('{{SRC}}');
  var pendingSeek = 0, lastSent = 0, progressLoaded = false;

  function loadProgress(){
    progressLoaded = false;
    pendingSeek = 0;
    lastSent = 0;
    fetch('/api/progress?path=' + encodeURIComponent(pPath))
      .then(function(r){ return r.json(); })
      .then(function(d){
        progressLoaded = true;
        if (!d || !d.pos) return;
        var pos = Number(d.pos) || 0;
        if (pos < 10) return;
        if (d.dur && pos > d.dur - 8) return;     // 上次已经看到片尾了
        pendingSeek = pos;
        if (v.readyState >= 1) applyResume();
      }).catch(function(){ progressLoaded = true; });
  }
  function applyResume(){
    if (!pendingSeek) return;
    var pos = pendingSeek;
    pendingSeek = 0;
    if (!isFinite(v.duration) || v.duration <= 0 || pos > v.duration - 8) return;
    try { v.currentTime = pos; } catch(err){}
    showToast('继续上次 ' + fmt(pos), 1800);
  }
  function saveProgress(force, ended){
    if (!isFinite(v.duration) || v.duration <= 0) return;
    var now = Date.now();
    if (!force && now - lastSent < 8000) return;
    lastSent = now;
    var payload = JSON.stringify({
      path: pPath,
      pos: ended ? v.duration : v.currentTime,
      dur: v.duration
    });
    try {
      if (ended && navigator.sendBeacon){
        navigator.sendBeacon('/api/progress', new Blob([payload], {type:'application/json'}));
        return;
      }
    } catch(err){}
    try {
      fetch('/api/progress', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: payload,
        keepalive: true
      }).catch(function(){});
    } catch(err){}
  }
  document.addEventListener('visibilitychange', function(){
    if (document.visibilityState === 'hidden') saveProgress(true);
  });
  window.addEventListener('pagehide', function(){ saveProgress(true); });

  /* ---------- 自动连播开关 ---------- */
  btnAuto.addEventListener('click', function(e){
    e.stopPropagation();
    autoNext = !autoNext;
    try { localStorage.setItem('ms.autonext', autoNext ? '1' : '0'); } catch(err){}
    paintAuto();
    showToast(autoNext ? '自动连播：开' : '自动连播：关', 1100);
  });

  /* ---------- 进度条拖动 ---------- */
  function ratioFrom(clientX){
    var r = $('timeline').getBoundingClientRect();
    if (!r.width) return 0;
    return Math.max(0, Math.min(1, (clientX - r.left) / r.width));
  }
  function paintAt(p){
    dragPct = p;
    tlFill.style.width = (p * 100) + '%';
    tlDot.style.left = (p * 100) + '%';
    tlTip.style.left = (p * 100) + '%';
  }
  function previewAt(p){
    paintAt(p);
    if (isFinite(v.duration)) tlTip.textContent = fmt(p * v.duration);
    tlTip.classList.add('on');
  }
  function startDrag(clientX){
    if (!isFinite(v.duration) || v.duration <= 0) return;
    dragging = true;
    document.body.classList.add('dragging');
    showBars();
    clearTimeout(hideTimer);
    previewAt(ratioFrom(clientX));
  }
  function moveDrag(clientX){ if (dragging) previewAt(ratioFrom(clientX)); }
  function endDrag(clientX){
    if (!dragging) return;
    var p = ratioFrom(clientX);
    dragging = false;
    document.body.classList.remove('dragging');
    tlTip.classList.remove('on');
    if (isFinite(v.duration) && v.duration > 0) v.currentTime = p * v.duration;
    paintAt(p);
    showBars();
  }

  var tl = $('timeline');
  if (window.PointerEvent){
    tl.addEventListener('pointerdown', function(e){
      try { tl.setPointerCapture(e.pointerId); } catch(err){}
      startDrag(e.clientX);
      e.preventDefault();
    });
    tl.addEventListener('pointermove', function(e){ moveDrag(e.clientX); });
    tl.addEventListener('pointerup', function(e){ endDrag(e.clientX); });
    tl.addEventListener('pointercancel', function(e){ endDrag(e.clientX); });
  } else {
    tl.addEventListener('touchstart', function(e){
      startDrag(e.touches[0].clientX); e.preventDefault();
    }, {passive:false});
    tl.addEventListener('touchmove', function(e){
      moveDrag(e.touches[0].clientX); e.preventDefault();
    }, {passive:false});
    tl.addEventListener('touchend', function(e){ endDrag(e.changedTouches[0].clientX); });
    tl.addEventListener('mousedown', function(e){ startDrag(e.clientX); e.preventDefault(); });
    document.addEventListener('mousemove', function(e){ moveDrag(e.clientX); });
    document.addEventListener('mouseup', function(e){ endDrag(e.clientX); });
  }

  /* ---------- 视频事件 ---------- */
  v.addEventListener('timeupdate', function(){
    timeCur.textContent = fmt(v.currentTime);
    if (!dragging && isFinite(v.duration) && v.duration > 0){
      var p = v.currentTime / v.duration;
      tlFill.style.width = (p * 100) + '%';
      tlDot.style.left = (p * 100) + '%';
    }
    try {
      if (v.buffered.length && isFinite(v.duration) && v.duration > 0){
        tlBuf.style.width = (v.buffered.end(v.buffered.length - 1) / v.duration * 100) + '%';
      }
    } catch(e){}
    saveProgress(false);
  });
  v.addEventListener('loadedmetadata', function(){
    timeTotal.textContent = fmt(v.duration);
    applyResume();
    primeSubtitle();
  });
  v.addEventListener('durationchange', function(){ timeTotal.textContent = fmt(v.duration); });
  v.addEventListener('play', function(){
    paintPlay(true);
    spinner.classList.remove('on');
    cancelNext();
    setTimeout(primeSubtitle, 40);
  });
  v.addEventListener('pause', function(){
    paintPlay(false);
    saveProgress(true);
  });
  v.addEventListener('playing', function(){ spinner.classList.remove('on'); });
  v.addEventListener('waiting', function(){ spinner.classList.add('on'); });
  v.addEventListener('stalled', function(){ spinner.classList.add('on'); });
  v.addEventListener('error', function(){ spinner.classList.remove('on'); showToast('视频加载失败，检查网络或文件', 2200); });
  v.addEventListener('ended', function(){
    saveProgress(true, true);
    if (currentIdx >= 0 && currentIdx < episodes.length - 1) showNext();
    else { showToast('已是最后一集', 2400); showBars(); }
  });

  /* ---------- 自动连播 ---------- */
  function showNext(){
    var ep = episodes[currentIdx + 1];
    if (!ep) return;
    $('nextTitle').textContent = ep.title || ep.name;
    nextOverlay.classList.add('on');
    hideBars();
    var countBox = $('nextCountBox'), bar = $('nextBar');

    if (!autoNext){
      $('nextLabel').textContent = '下一集';
      countBox.style.display = 'none';
      bar.style.display = 'none';
      return;
    }
    $('nextLabel').textContent = '即将播放';
    countBox.style.display = '';
    bar.style.display = '';
    nextLeft = 5;
    $('nextCountdown').textContent = nextLeft;
    $('nextFill').style.width = '0%';

    clearInterval(nextTimer);
    nextTimer = setInterval(function(){
      nextLeft--;
      $('nextCountdown').textContent = Math.max(0, nextLeft);
      $('nextFill').style.width = ((5 - nextLeft) / 5 * 100) + '%';
      if (nextLeft <= 0){
        clearInterval(nextTimer);
        nextOverlay.classList.remove('on');
        nextEpisode();
      }
    }, 1000);
  }
  function cancelNext(){
    clearInterval(nextTimer);
    nextOverlay.classList.remove('on');
  }
  $('nextPlayNow').addEventListener('click', function(){ cancelNext(); nextEpisode(); });
  $('nextCancel').addEventListener('click', function(){ cancelNext(); showBars(); });

  /* ---------- 全屏 ---------- */
  function fsOn(){
    return !!(document.fullscreenElement || document.webkitFullscreenElement) ||
           document.body.classList.contains('pfs');
  }
  function paintFs(){
    btnFs.textContent = fsOn() ? '退出' : '全屏';
    btnFs.classList.toggle('on', fsOn());
  }
  function lockLandscape(){
    try {
      if (screen.orientation && screen.orientation.lock){
        var p = screen.orientation.lock('landscape');
        if (p && p.catch) p.catch(function(){});
      }
    } catch(e){}
  }
  function unlockOrientation(){
    try { if (screen.orientation && screen.orientation.unlock) screen.orientation.unlock(); } catch(e){}
  }
  function pseudoFs(){
    document.body.classList.add('pfs');
    try { window.scrollTo(0, 1); } catch(e){}
    lockLandscape();
    paintFs();
    showToast('全屏模式', 1000);
  }
  function exitFs(){
    var d = document;
    if (d.fullscreenElement || d.webkitFullscreenElement){
      (d.exitFullscreen || d.webkitExitFullscreen || function(){}).call(d);
    }
    document.body.classList.remove('pfs');
    unlockOrientation();
    paintFs();
    showBars();
  }
  function toggleFs(){
    if (fsOn()){ exitFs(); return; }
    var el = document.documentElement;
    var req = el.requestFullscreen || el.webkitRequestFullscreen ||
              el.mozRequestFullScreen || el.msRequestFullscreen;
    if (req){
      try {
        var p = req.call(el, {navigationUI: 'hide'});
        if (p && p.then){
          p.then(function(){ lockLandscape(); paintFs(); })
           .catch(function(){ pseudoFs(); });
        } else {
          lockLandscape(); paintFs();
        }
        setTimeout(function(){ if (!fsOn()) pseudoFs(); }, 450);
        return;
      } catch(e){}
    }
    pseudoFs();
  }
  btnFs.addEventListener('click', function(e){ e.stopPropagation(); toggleFs(); });
  document.addEventListener('fullscreenchange', paintFs);
  document.addEventListener('webkitfullscreenchange', paintFs);

  /* ---------- 亮度 / 音量调节（下方按钮 + 滑块，避免手势误触） ---------- */
  // 亮度：遮罩透明度 = 1 - 亮度值（0.3~1.0），只能调暗（浏览器无法提升屏幕亮度）
  var brightness = 1.0;
  try {
    var sb = parseFloat(localStorage.getItem('ms.brightness'));
    if (isFinite(sb) && sb >= 0.3 && sb <= 1.0) brightness = sb;
  } catch(err){}

  function paintBright(){
    dimLayer.style.opacity = String(1 - brightness);
    var pct = Math.round(brightness * 100);
    brightFill.style.width = pct + '%';
    brightDot.style.left = pct + '%';
    brightVal.textContent = pct + '%';
  }
  function setBrightness(b){
    brightness = Math.max(0.3, Math.min(1.0, b));
    try { localStorage.setItem('ms.brightness', String(brightness)); } catch(err){}
    paintBright();
  }
  // 音量
  function paintVol(){
    var pct = Math.round(v.volume * 100);
    volFill.style.width = pct + '%';
    volDot.style.left = pct + '%';
    volVal.textContent = pct + '%';
  }
  function setVolume(vol){
    var nv = Math.max(0, Math.min(1, vol));
    v.volume = nv;
    try { localStorage.setItem('ms.volume', String(nv)); } catch(err){}
    paintVol();
    showToast('音量 ' + Math.round(nv * 100) + '%', 700);
  }

  // 面板开关
  function openAdj(){
    adjpanel.classList.add('open');
    adjMask.classList.add('on');
    showBars();
  }
  function closeAdj(){
    adjpanel.classList.remove('open');
    adjMask.classList.remove('on');
    showBars();
    try { v.focus(); } catch(e){}
  }
  btnAdj.addEventListener('click', function(e){ e.stopPropagation(); openAdj(); });
  $('adjClose').addEventListener('click', function(e){ e.stopPropagation(); closeAdj(); });
  adjMask.addEventListener('click', closeAdj);

  // 滑块拖动：通用处理（指针 + 触摸）
  function trackRatio(track, clientX){
    var r = track.getBoundingClientRect();
    if (!r.width) return 0;
    return Math.max(0, Math.min(1, (clientX - r.left) / r.width));
  }
  function wireSlider(track, onMove){
    function ratio(cx){ return trackRatio(track, cx); }
    if (window.PointerEvent){
      var down = false;
      track.addEventListener('pointerdown', function(e){
        down = true;
        try { track.setPointerCapture(e.pointerId); } catch(err){}
        onMove(ratio(e.clientX)); e.preventDefault();
      });
      track.addEventListener('pointermove', function(e){
        if (down) onMove(ratio(e.clientX));
      });
      track.addEventListener('pointerup', function(e){ down = false; });
      track.addEventListener('pointercancel', function(e){ down = false; });
    } else {
      var md = false;
      track.addEventListener('touchstart', function(e){
        md = true; onMove(ratio(e.touches[0].clientX)); e.preventDefault();
      }, {passive:false});
      track.addEventListener('touchmove', function(e){
        if (md) onMove(ratio(e.touches[0].clientX)); e.preventDefault();
      }, {passive:false});
      track.addEventListener('touchend', function(e){ md = false; });
      track.addEventListener('mousedown', function(e){ md = true; onMove(ratio(e.clientX)); });
      document.addEventListener('mousemove', function(e){
        if (md) onMove(ratio(e.clientX));
      });
      document.addEventListener('mouseup', function(e){ md = false; });
    }
  }
  wireSlider(brightTrack, function(r){
    setBrightness(0.3 + r * 0.7);
  });
  wireSlider(volTrack, function(r){
    setVolume(r);
  });

  // 恢复上次的音量（记忆）
  try {
    var sv = parseFloat(localStorage.getItem('ms.volume'));
    if (isFinite(sv) && sv >= 0 && sv <= 1) v.volume = sv;
  } catch(err){}

  paintBright();
  paintVol();

  /* ---------- 点按 / 双击 ---------- */
  stage.addEventListener('click', function(e){
    if (e.target && e.target.closest && e.target.closest('button,a')) return;
    var now = Date.now();
    var r = stage.getBoundingClientRect();
    var x = e.clientX - r.left;
    if (now - lastTap < 300 && now - lastTap > 40){
      lastTap = 0;
      clearTimeout(tapTimer);
      if (x < r.width * 0.35) seekBy(-SEEK_FINE);
      else if (x > r.width * 0.65) seekBy(SEEK_FINE);
      else togglePlay();
      return;
    }
    lastTap = now;
    // 记下点击那一刻的状态：触摸会自动补发 mousemove，可能已经把控制栏唤出来了
    var wasHidden = bottom.classList.contains('hide');
    clearTimeout(tapTimer);
    tapTimer = setTimeout(function(){
      if (wasHidden) showBars(); else hideBars();
    }, 270);
  });

  /* ---------- 键盘 / 遥控器 ---------- */
  document.addEventListener('keydown', function(e){
    var code = e.keyCode;
    // 子元素（如集数项）已经处理过这个按键，别再重复触发播放/暂停
    if (e.defaultPrevented) return;
    var ae = document.activeElement;
    var onBtn = ae && (ae.tagName === 'BUTTON' || ae.tagName === 'A' || ae.classList.contains('epitem'));

    if (adjpanel.classList.contains('open')){
      // 上下调音量，左右调亮度；返回/OK 关闭
      if (code === 38){ setVolume(v.volume + 0.1); e.preventDefault(); return; }
      if (code === 40){ setVolume(v.volume - 0.1); e.preventDefault(); return; }
      if (code === 39){ setBrightness(brightness + 0.1); e.preventDefault(); return; }
      if (code === 37){ setBrightness(brightness - 0.1); e.preventDefault(); return; }
      if (code === 27 || code === 4 || code === 8 || code === 13 || code === 32){
        closeAdj(); e.preventDefault(); return;
      }
      return;
    }

    if (eppanel.classList.contains('open')){
      var items = eplist.querySelectorAll('.epitem');
      var cur = -1;
      for (var i = 0; i < items.length; i++){ if (items[i] === document.activeElement){ cur = i; break; } }
      if (code === 38 || code === 37){ if (cur > 0) items[cur - 1].focus(); e.preventDefault(); return; }
      if (code === 40 || code === 39){ if (cur < items.length - 1) items[cur + 1].focus(); e.preventDefault(); return; }
      if (code === 13 || code === 32){ if (cur >= 0) items[cur].click(); e.preventDefault(); return; }
      if (code === 27 || code === 4 || code === 8){ closeEps(); e.preventDefault(); return; }
      return;
    }

    if (nextOverlay.classList.contains('on')){
      if (code === 13 || code === 32){ cancelNext(); nextEpisode(); }
      else if (code === 27 || code === 4 || code === 8){ cancelNext(); showBars(); }
      e.preventDefault();
      return;
    }

    // 焦点在按钮上时，让浏览器自己处理 OK/Enter
    if ((code === 13 || code === 32) && onBtn) return;

    showBars();
    switch (code){
      case 13: case 32: case 179: case 126:
        e.preventDefault(); togglePlay(); break;
      case 37: case 415:
        e.preventDefault(); seekBy(-SEEK); break;
      case 39: case 19:
        e.preventDefault(); seekBy(SEEK); break;
      case 38:
        e.preventDefault(); prevEpisode(); break;
      case 40:
        e.preventDefault(); nextEpisode(); break;
      case 77: case 82:
        e.preventDefault();
        if (eppanel.classList.contains('open')) closeEps(); else openEps();
        break;
      case 70:
        e.preventDefault(); toggleFs(); break;
      case 67:   // C：切换字幕
        e.preventDefault(); btnSub.click(); break;
      case 65:   // A：切换音轨
        e.preventDefault(); btnAudio.click(); break;
      case 27: case 4: case 8:
        e.preventDefault();
        if (fsOn()) exitFs(); else history.back();
        break;
    }
  });

  // 鼠标移动唤出控制栏（桌面 / 电视接鼠标）。手机上不绑：触摸会补发 mousemove，
  // 会把控制栏反复唤醒，导致「单击收起」失灵。
  if (!isPhone) {
    document.addEventListener('mousemove', function(){ if (!dragging) showBars(); });
  }

  // 抑制 iOS 双指缩放
  document.addEventListener('touchmove', function(e){
    if (e.touches && e.touches.length > 1) e.preventDefault();
  }, {passive:false});

  /* ---------- 启动 ---------- */
  paintAuto();
  paintFs();
  showBars();
  loadProgress();
  primeSubtitle();
  try { v.focus(); } catch(e){}
  loadEpisodes();
})();
</script>
</body></html>"""
