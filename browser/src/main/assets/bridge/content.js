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

  port.onMessage.addListener(m => {
    let result = null;
    try {
      if (m.c === 'scroll') { if (window === window.top) scroll(m.fx, m.fy, m.dx, m.dy); }
      else if (m.c === 'media') result = media(m.a, m.s);
      else if (m.c === 'type') result = type(m.t);
    } catch (e) { result = false; }
    if (m.id) port.postMessage({ id: m.id, result: result });
  });
})();
