// Glas Bridge: runs in every page and frame and carries out what the browser app asks:
// scroll the part under the pointer, play/pause or seek the main video, write text into a field.
// It also tells the app when a video plays, so the TV stays awake.
'use strict';
(function () {
  let port;
  try { port = browser.runtime.connectNative('browser'); } catch (e) { return; }

  function playingNow() {
    return Array.from(document.querySelectorAll('video,audio')).some(e => !e.paused && !e.ended);
  }
  let last = null;
  function report() {
    const p = playingNow();
    if (p !== last) { last = p; port.postMessage({ playing: p }); }
  }
  ['play', 'playing', 'pause', 'ended', 'emptied'].forEach(n => document.addEventListener(n, report, true));

  function scroll(fx, fy, dx, dy) {
    let e = document.elementFromPoint(fx * innerWidth, fy * innerHeight);
    while (e && e !== document.body && e !== document.documentElement) {
      const s = getComputedStyle(e);
      const canY = dy && e.scrollHeight > e.clientHeight + 2 && /(auto|scroll)/.test(s.overflowY);
      const canX = dx && e.scrollWidth > e.clientWidth + 2 && /(auto|scroll)/.test(s.overflowX);
      if (canY || canX) {
        const t = e.scrollTop, l = e.scrollLeft;
        e.scrollBy(dx, dy);
        if (e.scrollTop !== t || e.scrollLeft !== l) return;
      }
      e = e.parentElement;
    }
    window.scrollBy(dx, dy);
  }

  function mainVideo() {
    const v = Array.from(document.querySelectorAll('video'));
    return v.find(x => !x.paused) || v.sort((a, b) => b.clientWidth * b.clientHeight - a.clientWidth * a.clientHeight)[0];
  }

  function media(a, s) {
    const e = mainVideo();
    if (!e) return false;
    if (a === 'toggle') { if (e.paused) e.play(); else e.pause(); }
    else e.currentTime = Math.max(0, e.currentTime + s);
    return true;
  }

  function type(t) {
    const ok = x => x && (x.isContentEditable || /^(INPUT|TEXTAREA)$/.test(x.tagName));
    let e = document.activeElement;
    if (!ok(e)) {
      e = document.querySelector('input[type=search],input[name=q],input[name=search_query],input[type=text],textarea');
      if (e) e.focus();
    }
    if (!ok(e)) return false;
    if (e.isContentEditable) {
      document.execCommand('selectAll');
      document.execCommand('insertText', false, t);
    } else {
      const d = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e), 'value');
      if (d && d.set) d.set.call(e, t); else e.value = t;
      e.dispatchEvent(new Event('input', { bubbles: true }));
      e.dispatchEvent(new Event('change', { bubbles: true }));
    }
    return true;
  }

  // ---- Video quality: at most what the TV handles well ----
  // Video sites ask the browser which formats it can play. Answering "no" for formats above the chosen
  // resolution (and for AV1/HDR unless 4K is chosen) makes them send a lighter stream. TV video chips
  // often show a green picture after a while with 4K.
  let cfg = { maxHeight: 1080, autoFull: true };
  const page = window.wrappedJSObject || window;
  const exp = typeof exportFunction === 'function' ? exportFunction : (f, o, opt) => { o[opt.defineAs] = f; };
  const clone = typeof cloneInto === 'function' ? cloneInto : x => x;

  function tooMuch(type, width, height) {
    const t = String(type || '').toLowerCase();
    const w = width || +((/width=(\d+)/.exec(t) || [])[1] || 0);
    const h = height || +((/height=(\d+)/.exec(t) || [])[1] || 0);
    const maxW = Math.round(cfg.maxHeight * 16 / 9);
    if (h > cfg.maxHeight || w > maxW) return true;
    if (cfg.maxHeight < 2160) {
      if (/av01|av1/.test(t)) return true;
      if (/vp09\.02|eotf=smpte2084|eotf=arib|hdr/.test(t)) return true;
    }
    return false;
  }

  function wrapIsTypeSupported(MS) {
    if (!MS || typeof MS.isTypeSupported !== 'function') return;
    const orig = MS.isTypeSupported;
    exp(function (type) { return tooMuch(type) ? false : orig.call(MS, type); }, MS, { defineAs: 'isTypeSupported' });
  }
  try {
    wrapIsTypeSupported(page.MediaSource);
    wrapIsTypeSupported(page.ManagedMediaSource);
    const proto = page.HTMLMediaElement && page.HTMLMediaElement.prototype;
    if (proto && proto.canPlayType) {
      const origCan = proto.canPlayType;
      exp(function (type) { return tooMuch(type) ? '' : origCan.call(this, type); }, proto, { defineAs: 'canPlayType' });
    }
    const mc = page.navigator && page.navigator.mediaCapabilities;
    if (mc && mc.decodingInfo) {
      const origInfo = mc.decodingInfo;
      exp(function (config) {
        const v = config && config.video;
        if (v && tooMuch(v.contentType, v.width, v.height)) {
          return page.Promise.resolve(clone({ supported: false, smooth: false, powerEfficient: false }, page));
        }
        return origInfo.call(mc, config);
      }, mc, { defineAs: 'decodingInfo' });
    }
  } catch (e) { /* the page keeps its normal formats */ }

  // ---- Videos in full screen by themselves ----
  const left = new WeakSet();   // videos whose full screen the user left: they stay small
  let film = null;              // video enlarged by the page style when real full screen isn't allowed
  let filmStyle = null;

  function big(v) {
    const r = v.getBoundingClientRect();
    return r.width >= innerWidth * 0.3 || r.height >= innerHeight * 0.3;
  }

  // The player box around the video (with its buttons), as long as it isn't much larger than the video
  function playerBox(v) {
    const r = v.getBoundingClientRect();
    let best = v, el = v;
    while (el.parentElement && el.parentElement !== document.body && el.parentElement !== document.documentElement) {
      el = el.parentElement;
      const q = el.getBoundingClientRect();
      if (q.width <= r.width * 1.15 + 4 && q.height <= r.height * 1.35 + 4) best = el; else break;
    }
    return best;
  }

  function filmOn(v) {
    if (film) return;
    if (!filmStyle) {
      filmStyle = document.createElement('style');
      filmStyle.textContent =
        '.glas-film{position:fixed!important;left:0!important;top:0!important;right:0!important;bottom:0!important;' +
        'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;' +
        'z-index:2147483647!important;background:#000!important;object-fit:contain!important;transform:none!important}' +
        '.glas-film-up{transform:none!important;filter:none!important;perspective:none!important;contain:none!important;' +
        'will-change:auto!important;z-index:2147483647!important;overflow:visible!important}' +
        'html.glas-film-on,html.glas-film-on body{overflow:hidden!important}';
    }
    (document.head || document.documentElement).appendChild(filmStyle);
    film = v;
    v.classList.add('glas-film');
    for (let el = v.parentElement; el && el !== document.documentElement; el = el.parentElement) el.classList.add('glas-film-up');
    document.documentElement.classList.add('glas-film-on');
    port.postMessage({ film: true });
  }

  function filmOff(byUser) {
    if (!film) return;
    if (byUser) left.add(film);
    film.classList.remove('glas-film');
    document.querySelectorAll('.glas-film-up').forEach(el => el.classList.remove('glas-film-up'));
    document.documentElement.classList.remove('glas-film-on');
    film = null;
    port.postMessage({ film: false });
  }

  let fullVideo = null;
  function goFull(v) {
    const box = playerBox(v);
    let p = null;
    try { p = box.requestFullscreen && box.requestFullscreen(); } catch (e) { p = null; }
    if (p && p.then) { fullVideo = v; p.catch(() => { fullVideo = null; filmOn(v); }); } else filmOn(v);
  }

  document.addEventListener('play', e => {
    const v = e.target;
    if (!cfg.autoFull || !(v instanceof HTMLVideoElement)) return;
    if (v.muted || v.volume === 0 || left.has(v) || film || document.fullscreenElement) return;
    // Wait a moment: the size is right once the player is laid out, and short previews are over
    setTimeout(() => {
      if (v.paused || v.muted || film || document.fullscreenElement || left.has(v) || !big(v)) return;
      if (isFinite(v.duration) && v.duration > 0 && v.duration < 12) return;
      goFull(v);
    }, 600);
  }, true);

  document.addEventListener('fullscreenchange', () => {
    // Left full screen (Back): this video stays small from now on
    if (!document.fullscreenElement && fullVideo) { left.add(fullVideo); fullVideo = null; }
  });

  port.onMessage.addListener(m => {
    let result = null;
    try {
      if (m.c === 'config') { cfg = Object.assign(cfg, m); }
      else if (m.c === 'exitFilm') filmOff(true);
      else if (m.c === 'scroll') { if (window === window.top) scroll(m.fx, m.fy, m.dx, m.dy); }
      else if (m.c === 'media') result = media(m.a, m.s);
      else if (m.c === 'type') result = type(m.t);
    } catch (e) { result = false; }
    if (m.id) port.postMessage({ id: m.id, result: result });
  });
})();
