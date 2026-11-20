// SPDX-License-Identifier: GPL-3.0-or-later
// Does a page's own video play in a browser like the app's WebView?
// Loads <url> with the tablet's user agent, with or without inject.js, under an autoplay policy, taps the video once
// like a person, and reports whether currentTime moves. Prints one JSON line.
//
// usage: node play.mjs <url> [inject=1|0] [policy=gesture|free] [seconds=12]
//   policy gesture = WebView mediaPlaybackRequiresUserGesture=true (v0.1.0), free = false (v0.1.2)
import { chromium, devices } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const [url, inj = '1', policy = 'gesture', secs = '12'] = process.argv.slice(2);
if (!url) { console.error('usage: node play.mjs <url> [inject=1|0] [policy=gesture|free] [seconds]'); process.exit(2); }
const inject = fs.readFileSync(path.join(here, '../app/src/main/assets/inject.js'), 'utf8');
const UA = 'Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36';
const autoplay = policy === 'free' ? 'no-user-gesture-required' : 'user-gesture-required';

const browser = await chromium.launch({ args: [`--autoplay-policy=${autoplay}`, '--mute-audio'] });
const ctx = await browser.newContext({ ...devices['Galaxy Tab S4'], userAgent: UA, locale: 'en-DK' });
const posted = [];
await ctx.exposeBinding('__tcPost', (_src, s) => { posted.push(s); });
if (inj === '1') await ctx.addInitScript({ content: 'window.tabcast = { postMessage: function (s) { window.__tcPost(s); } };\n' + inject });
const page = await ctx.newPage();
const errors = [];
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text().slice(0, 200)); });
page.on('pageerror', (e) => errors.push('pageerror: ' + String(e.message).slice(0, 200)));
try { await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 }); } catch (e) { errors.push('goto: ' + e.message); }
await page.waitForTimeout(5000);
const state = () => page.evaluate(() => [...document.querySelectorAll('video')].map((v) => ({
  t: Math.round(v.currentTime * 10) / 10, paused: v.paused, rs: v.readyState, err: v.error && v.error.code, src: (v.currentSrc || '').slice(0, 40),
})));
const before = await state();
// One tap on the first visible video, like the user pressing play.
let tapped = false;
try { const v = await page.$('video'); if (v && await v.isVisible()) { await v.tap(); tapped = true; } } catch (e) { errors.push('tap: ' + e.message); }
await page.waitForTimeout(800);
// If the tap only showed the controls, a second tap on the centre presses play (as on the tablet).
try { const s = await state(); if (s[0] && s[0].paused) { const v = await page.$('video'); await v.tap(); } } catch (e) {}
await page.waitForTimeout(Number(secs) * 1000);
const after = await state();
const codecs = await page.evaluate(() => ({
  avc1: !!(window.MediaSource && MediaSource.isTypeSupported('video/mp4; codecs="avc1.42E01E,mp4a.40.2"')),
  vp9: !!(window.MediaSource && MediaSource.isTypeSupported('video/webm; codecs="vp9"')),
}));
console.log(JSON.stringify({ url, inject: inj === '1', policy, tapped, codecs, before, after,
  playing: after.some((v, i) => !v.paused && v.t > ((before[i] && before[i].t) || 0) + 1),
  diag: posted.filter((s) => /"diag"/.test(s)).slice(0, 8), errors: errors.slice(0, 6) }));
await browser.close();
