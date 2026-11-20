// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * Tab Cast page script. Injected at document start into EVERY frame (androidx.webkit addDocumentStartJavaScript),
 * so it also sees players inside cross-origin iframes. It never changes what the page does; it only watches:
 *  - fetch / XHR responses that are HLS or DASH manifests (by Content-Type or by sniffing the body), which finds the
 *    stream behind players that play `blob:` URLs through MediaSource (hls.js, shaka, dash.js, video.js, ...);
 *  - <video>/<audio>/<source> URLs and the `playing` event (which video actually plays, its duration and size);
 *  - og:video / JSON-LD VideoObject metadata, iframe URLs (embedded players yt-dlp may know), page title and image;
 *  - site-rule scripts registered in window.__tabcastRules (see SiteRules.kt).
 * Messages go to the app through the `tabcast` object (WebMessageListener) or, on old WebViews, `TabCastJs`.
 */
(function () {
  'use strict';
  if (window.__tabcastInstalled) return;
  window.__tabcastInstalled = true;

  var isTop = (function () { try { return window.top === window; } catch (e) { return false; } })();
  var queue = [];
  window.__tabcastQueue = queue;

  function post(o) {
    o.frame = location.href;
    o.top = isTop;
    var s;
    try { s = JSON.stringify(o); } catch (e) { return; }
    try {
      var b = window.tabcast, l = window.TabCastJs;
      if (b && b.postMessage) b.postMessage(s);
      else if (l && l.postMessage) l.postMessage(s);
      else queue.push(s);
    } catch (e) { queue.push(s); }
  }

  var sent = {};
  function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return null; } }
  function isSegmentish(u) { return /\.(ts|m4s|m4f|cmfv|cmfa|aac|vtt)(\?|#|$)|[\/_-](seg|segment|frag|fragment|chunk|init)[-_]?\d*\.(mp4|m4v|m4a|webm)/i.test(u || ''); }

  function report(url, via, extra) {
    if (!url || typeof url !== 'string') return;
    if (url.indexOf('blob:') === 0 || url.indexOf('mediasource:') === 0) {
      if (!sent.mse) { sent.mse = 1; post({ t: 'media', via: 'mse' }); }
      return;
    }
    if (url.indexOf('data:') === 0) return;
    var a = abs(url);
    if (!a || !/^https?:/i.test(a)) return;
    var key = via + '|' + a + '|' + ((extra && (extra.ct || extra.sniff || extra.dur)) || '');
    if (sent[key]) return;
    sent[key] = 1;
    var o = { t: 'media', url: a, via: via };
    if (extra) for (var k in extra) if (extra[k] != null) o[k] = extra[k];
    post(o);
  }

  var MANIFEST_CT = /mpegurl|dash\+xml/i;
  var MEDIA_CT = /^(video|audio)\//i;
  var MAYBE_TEXT_CT = /^$|text\/plain|octet-stream|\/xml|binary/i;
  function maybeManifestUrl(u) { return /m3u8|\.mpd|manifest|playlist|master|\/hls\/|\/dash\//i.test(u || ''); }

  function sniffText(t) {
    if (typeof t !== 'string') return null;
    var s = t.slice(0, 600).replace(/^\uFEFF/, '').replace(/^\s+/, '');
    if (s.indexOf('#EXTM3U') === 0) return 'hls';
    if (s.indexOf('<MPD') >= 0 || (s.indexOf('<?xml') === 0 && t.slice(0, 3000).indexOf('<MPD') >= 0)) return 'dash';
    return null;
  }
  function sniffBuf(buf) {
    try {
      var b = new Uint8Array(buf, 0, Math.min(buf.byteLength, 600)), s = '';
      for (var i = 0; i < b.length; i++) s += String.fromCharCode(b[i]);
      return sniffText(s);
    } catch (e) { return null; }
  }

  // --- fetch -------------------------------------------------------------------------------------------------------
  try {
    var origFetch = window.fetch;
    if (typeof origFetch === 'function') {
      window.fetch = function (input, init) {
        var p = origFetch.apply(this, arguments);
        try {
          p.then(function (r) {
            try {
              var ct = (r.headers && r.headers.get('content-type')) || '';
              var u = r.url || (typeof input === 'string' ? input : (input && input.url));
              if (MANIFEST_CT.test(ct)) { report(u, 'fetch', { ct: ct }); return; }
              if (MEDIA_CT.test(ct)) { if (!isSegmentish(u) && /\.(mp4|m4v|webm|mkv|mov|mp3|m4a)(\?|$)/i.test(u)) report(u, 'fetch', { ct: ct }); return; }
              var len = +((r.headers && r.headers.get('content-length')) || 0);
              if (MAYBE_TEXT_CT.test(ct) && (maybeManifestUrl(u) || (len > 0 && len < 200000)) && (!len || len < 3000000) && r.clone) {
                r.clone().text().then(function (t) { var sn = sniffText(t); if (sn) report(u, 'fetch', { sniff: sn, ct: ct }); }, function () {});
              }
            } catch (e) {}
          }, function () {});
        } catch (e) {}
        return p;
      };
    }
  } catch (e) {}

  // --- XMLHttpRequest ----------------------------------------------------------------------------------------------
  try {
    var XP = XMLHttpRequest.prototype, origOpen = XP.open, origSend = XP.send;
    XP.open = function (m, u) { try { this.__tcUrl = u; } catch (e) {} return origOpen.apply(this, arguments); };
    XP.send = function () {
      var x = this;
      try {
        x.addEventListener('load', function () {
          try {
            var u = x.responseURL || abs(x.__tcUrl);
            var ct = x.getResponseHeader('content-type') || '';
            if (MANIFEST_CT.test(ct)) { report(u, 'xhr', { ct: ct }); return; }
            if (MEDIA_CT.test(ct)) return; // MediaSource segments
            var rt = x.responseType, sn = null;
            if (rt === '' || rt === 'text') { var t = x.responseText; if (t && t.length < 3000000) sn = sniffText(t); }
            else if (rt === 'arraybuffer' && x.response && x.response.byteLength < 3000000) sn = sniffBuf(x.response);
            if (sn) report(u, 'xhr', { sniff: sn, ct: ct });
          } catch (e) {}
        });
      } catch (e) {}
      return origSend.apply(this, arguments);
    };
  } catch (e) {}

  // --- MediaSource -------------------------------------------------------------------------------------------------
  try {
    var origCreate = URL.createObjectURL;
    URL.createObjectURL = function (o) {
      var r = origCreate.apply(this, arguments);
      try {
        if ((window.MediaSource && o instanceof window.MediaSource) || (window.ManagedMediaSource && o instanceof window.ManagedMediaSource)) {
          if (!sent.mse) { sent.mse = 1; post({ t: 'media', via: 'mse' }); }
        }
      } catch (e) {}
      return r;
    };
  } catch (e) {}

  // --- media elements ----------------------------------------------------------------------------------------------
  function dur(v) {
    var d = v.duration;
    if (d === Infinity) return 1e9; // live
    return isFinite(d) && d > 0 ? d : null;
  }
  function scanEl(v) {
    try {
      var extra = { dur: dur(v), w: v.videoWidth || 0, h: v.videoHeight || 0, poster: v.poster ? abs(v.poster) : null };
      if (v.currentSrc) report(v.currentSrc, 'element', extra);
      if (v.src && v.src !== v.currentSrc) report(v.src, 'element', extra);
      var ss = v.querySelectorAll ? v.querySelectorAll('source') : [];
      for (var i = 0; i < ss.length; i++) {
        var s = ss[i];
        if (s.src) report(s.src, 'source', { ct: s.type || null, poster: extra.poster });
      }
    } catch (e) {}
  }
  function isMedia(t) { return t && (t.tagName === 'VIDEO' || t.tagName === 'AUDIO'); }
  function onPlaying(e) {
    var v = e.target;
    if (!isMedia(v)) return;
    scanEl(v);
    var d = dur(v);
    // Muted loops and short muted previews are decoration, not the video the user wants.
    if (v.muted && (v.loop || (d != null && d < 45))) return;
    post({ t: 'play', url: v.currentSrc || v.src || '', w: v.videoWidth || 0, h: v.videoHeight || 0, dur: d,
      poster: v.poster ? abs(v.poster) : null });
  }
  function onMeta(e) { if (isMedia(e.target)) scanEl(e.target); }

  // --- diagnostics: why a page's own video doesn't start (the app logs these, a few per page) --------------------
  var diagN = 0;
  function diag(msg, v) { if (diagN++ < 15) post({ t: 'diag', msg: String(msg).slice(0, 180), src: (v && (v.currentSrc || v.src)) || '' }); }
  try {
    var MP = HTMLMediaElement.prototype, origPlay = MP.play;
    MP.play = function () {
      var r = origPlay.apply(this, arguments), el = this;
      try { if (r && r.catch) r.catch(function (err) { diag('play() refused: ' + ((err && err.name) || '') + ' ' + ((err && err.message) || ''), el); }); } catch (e) {}
      return r;
    };
  } catch (e) {}
  document.addEventListener('error', function (e) {
    var v = e.target;
    if (isMedia(v) && v.error) diag('video error ' + v.error.code + (v.error.message ? ': ' + v.error.message : ''), v);
  }, true);
  document.addEventListener('playing', onPlaying, true);
  document.addEventListener('loadedmetadata', onMeta, true);
  document.addEventListener('loadstart', onMeta, true);

  // --- page metadata, iframes, rules ------------------------------------------------------------------------------
  function metaContent(sel) { var m = document.querySelector(sel); return m && m.getAttribute('content'); }
  var lastPage = '';
  function walkLd(o, out) {
    if (!o || typeof o !== 'object') return;
    if (Array.isArray(o)) { for (var i = 0; i < o.length; i++) walkLd(o[i], out); return; }
    var t = o['@type'];
    if (t === 'VideoObject' || (Array.isArray(t) && t.indexOf('VideoObject') >= 0)) {
      if (typeof o.contentUrl === 'string') report(o.contentUrl, 'meta');
      if (typeof o.embedUrl === 'string') out.push(o.embedUrl);
    }
    for (var k in o) if (k !== '@context' && o[k] && typeof o[k] === 'object') walkLd(o[k], out);
  }
  function ruleMatches(re) {
    try {
      var r = new RegExp(re, 'i');
      if (r.test(location.hostname)) return true;
      var ao = location.ancestorOrigins;
      if (ao) for (var i = 0; i < ao.length; i++) { try { if (r.test(new URL(ao[i]).hostname)) return true; } catch (e) {} }
    } catch (e) {}
    return false;
  }
  function runRules() {
    var rules = window.__tabcastRules || [];
    for (var i = 0; i < rules.length; i++) {
      var r = rules[i];
      if (!ruleMatches(r.m)) continue;
      try {
        var res = r.f();
        if (typeof res === 'string') res = [res];
        if (res && res.length) for (var j = 0; j < res.length; j++) if (typeof res[j] === 'string') report(res[j], 'rule', { rule: r.n });
      } catch (e) {}
    }
  }
  function scanPage() {
    try {
      var vs = document.querySelectorAll('video, audio');
      for (var i = 0; i < vs.length; i++) scanEl(vs[i]);
      var metaEmbeds = [];
      var metas = document.querySelectorAll('meta[property^="og:video"], meta[name^="og:video"], meta[property="twitter:player:stream"], meta[name="twitter:player:stream"]');
      for (var m = 0; m < metas.length; m++) {
        var p = (metas[m].getAttribute('property') || metas[m].getAttribute('name') || '').toLowerCase();
        var c = metas[m].getAttribute('content');
        if (c && /^https?:/i.test(c) && (/url$|secure_url$|stream$|^og:video$/.test(p))) {
          // og:video is often the embeddable player page, not the file: hand those to yt-dlp as an embed.
          if (/\.(m3u8|mpd|mp4|m4v|webm|mkv|mov|mp3|m4a)(\?|#|$)/i.test(c)) report(c, 'meta'); else metaEmbeds.push(c);
        }
      }
      var embeds = metaEmbeds.slice(0, 5);
      var lds = document.querySelectorAll('script[type="application/ld+json"]');
      for (var l = 0; l < lds.length && l < 20; l++) { try { walkLd(JSON.parse(lds[l].textContent), embeds); } catch (e) {} }
      var ifr = document.querySelectorAll('iframe[src]');
      for (var f = 0; f < ifr.length && embeds.length < 30; f++) { var s = ifr[f].src; if (/^https?:/i.test(s)) embeds.push(s); }
      var page = {
        t: 'page',
        title: isTop ? (metaContent('meta[property="og:title"]') || document.title || '') : '',
        poster: isTop ? (metaContent('meta[property="og:image"]') || '') : '',
        iframes: embeds,
      };
      var key = page.title + '|' + page.poster + '|' + embeds.join(',');
      if (key !== lastPage) { lastPage = key; post(page); }
      runRules();
    } catch (e) {}
  }
  window.__tabcastScan = scanPage;

  // --- control of the page's own player (Tab Cast TV: remote, "full screen in page") --------------------------
  // The main video of this frame: the one playing, else the largest one that has a duration.
  function mainVideo() {
    var vs = document.querySelectorAll('video'), best = null, area = 0;
    for (var i = 0; i < vs.length; i++) {
      var v = vs[i];
      if (!v.paused && !v.ended && v.readyState > 1) return v;
      var a = (v.clientWidth || 0) * (v.clientHeight || 0);
      if (dur(v) != null && a >= area) { best = v; area = a; }
    }
    return best;
  }
  var maxStyle = 'position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;' +
    'max-width:none!important;max-height:none!important;z-index:2147483647!important;background:#000!important;' +
    'object-fit:contain!important;margin:0!important;border:0!important;transform:none!important';
  function maximize(el) { if (el) { el.setAttribute('data-tabcast-max', el.getAttribute('style') || ''); el.setAttribute('style', maxStyle); } }
  function restore() {
    var els = document.querySelectorAll('[data-tabcast-max]');
    for (var i = 0; i < els.length; i++) { els[i].setAttribute('style', els[i].getAttribute('data-tabcast-max')); els[i].removeAttribute('data-tabcast-max'); }
  }
  var stateTimer = null;
  function reportState() {
    var v = mainVideo();
    if (!v || v.paused) { if (stateTimer && (!v || v.paused)) { post({ t: 'state', pos: v ? v.currentTime : 0, dur: v ? dur(v) : null, paused: true }); } return; }
    post({ t: 'state', pos: v.currentTime, dur: dur(v), paused: false, muted: v.muted });
  }
  document.addEventListener('playing', function (e) {
    if (vol != null && isMedia(e.target)) { try { e.target.volume = vol; } catch (x) {} }
    if (!stateTimer) stateTimer = setInterval(reportState, 1000);
  }, true);
  var vol = null; // Tab Cast TV's own level (its HDMI volume is fixed for apps), 0..1
  document.addEventListener('pause', reportState, true);

  // --- element hiding (ad blocking): the app sends this frame's CSS; at Aggressive it also asks for the classes and
  // ids on the page, and answers with the generic rules for just those (uBlock Origin's cheap way).
  function addCss(css) {
    try {
      var st = document.createElement('style');
      st.setAttribute('data-tabcast', 'hide');
      st.textContent = css;
      (document.head || document.documentElement).appendChild(st);
    } catch (e) {}
  }
  var keysSeen = null, keyTimer = null, keyRuns = 0;
  function collectKeys() {
    keyTimer = null;
    if (!keysSeen || keyRuns++ > 25) return;
    var out = [];
    try {
      var els = document.querySelectorAll('[id],[class]');
      for (var i = 0; i < els.length && out.length < 1500; i++) {
        var el = els[i];
        if (el.id && !keysSeen['#' + el.id]) { keysSeen['#' + el.id] = 1; out.push('#' + el.id); }
        var cl = el.classList;
        if (cl) for (var j = 0; j < cl.length; j++) { var k = '.' + cl[j]; if (!keysSeen[k]) { keysSeen[k] = 1; out.push(k); } }
      }
    } catch (e) {}
    if (out.length) post({ t: 'keys', host: location.hostname, k: out });
  }
  function scheduleKeys() { if (keysSeen && !keyTimer) keyTimer = setTimeout(collectKeys, 700); }
  function startKeys() {
    if (keysSeen) return;
    keysSeen = {};
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', collectKeys); else collectKeys();
    try { new MutationObserver(scheduleKeys).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'id'] }); } catch (e) {}
  }
  if (/^https?:$/.test(location.protocol)) post({ t: 'css?', host: location.hostname });

  function onCommand(data) {
    if (typeof data !== 'string') return;
    if (data.indexOf('css:') === 0) { addCss(data.slice(4)); return; }
    if (data === 'keys:on') { startKeys(); return; }
    var c = data, arg = '', k = data.indexOf(':');
    if (k > 0) { c = data.slice(0, k); arg = data.slice(k + 1); }
    var v;
    try {
      if (c === 'pause') {
        var vs = document.querySelectorAll('video, audio');
        for (var i = 0; i < vs.length; i++) { try { vs[i].pause(); } catch (e) {} }
      } else if (c === 'scan') {
        scanPage();
      } else if (c === 'play') {
        v = mainVideo(); if (v) { v.muted = false; var r = v.play(); if (r && r.catch) r.catch(function () {}); }
      } else if (c === 'toggle') {
        v = mainVideo(); if (v) { if (v.paused) { var r2 = v.play(); if (r2 && r2.catch) r2.catch(function () {}); } else v.pause(); }
      } else if (c === 'seek') {
        v = mainVideo(); if (v) v.currentTime = Math.max(0, parseFloat(arg) || 0);
      } else if (c === 'seekby') {
        v = mainVideo(); if (v) v.currentTime = Math.max(0, v.currentTime + (parseFloat(arg) || 0));
      } else if (c === 'maximize') {
        // In the frame that plays: the video fills the frame. In the top page: the iframe of that origin fills the
        // screen (arg = its origin). No user gesture needed, unlike requestFullscreen.
        restore();
        if (arg && isTop) {
          var fr = document.querySelectorAll('iframe');
          for (var j = 0; j < fr.length; j++) { try { if (new URL(fr[j].src, location.href).origin === arg) { maximize(fr[j]); break; } } catch (e) {} }
        } else maximize(mainVideo());
      } else if (c === 'restore') {
        restore();
      } else if (c === 'volume') {
        vol = Math.max(0, Math.min(1, parseFloat(arg)));
        if (isNaN(vol)) vol = null;
        var ms = document.querySelectorAll('video, audio');
        for (var m = 0; m < ms.length; m++) { try { if (vol != null) ms[m].volume = vol; } catch (x) {} }
      }
    } catch (e) {}
    if (c !== 'scan' && c !== 'restore') setTimeout(reportState, 300);
  }
  try { if (window.tabcast) window.tabcast.onmessage = function (ev) { onCommand(ev && ev.data); }; } catch (e) {}
  window.__tabcastCommand = onCommand;

  var n = 0;
  function tick() { scanPage(); if (++n < 12) setTimeout(tick, n < 4 ? 1500 : 4000); }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', tick); else tick();
  window.addEventListener('load', scanPage);
})();
