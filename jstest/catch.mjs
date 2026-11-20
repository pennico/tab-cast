// SPDX-License-Identifier: GPL-3.0-or-later
// Load a real page in headless Chromium the way the app's WebView would (Android Chrome user agent, inject.js at
// document start in every frame), try to start the video, and record what the app would see:
//   requests  = what WebViewClient.shouldInterceptRequest gets (url + request headers)
//   js        = messages inject.js posts to the app
//   cookies   = the browser's cookies (the app reads them from CookieManager)
// Output: out/<name>.json, replayed by CatchReplayTest (app/src/test) through the real Kotlin catch + probe code.
//
// usage: node catch.mjs <name> <url> [seconds=25]
import { chromium, devices } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const [name, url, secs = '25'] = process.argv.slice(2);
if (!name || !url) { console.error('usage: node catch.mjs <name> <url> [seconds]'); process.exit(2); }
const inject = fs.readFileSync(path.join(here, '../app/src/main/assets/inject.js'), 'utf8');
// The app's WebView UA minus the "; wv" token (see BrowserActivity), on the tablet's Android 14.
const UA = 'Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36';

const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required', '--mute-audio'] });
const ctx = await browser.newContext({ ...devices['Galaxy Tab S4'], userAgent: UA, locale: 'en-DK' });
const out = { name, pageUrl: url, requests: [], js: [], cookies: [] };
await ctx.exposeBinding('__tcPost', (_src, s) => { out.js.push(s); });
await ctx.addInitScript({ content: 'window.tabcast = { postMessage: function (s) { window.__tcPost(s); } };\n' + inject });
ctx.on('request', (r) => {
  let frame = null;
  try { frame = r.frame().url(); } catch (e) {}
  out.requests.push({ url: r.url(), headers: r.headers(), type: r.resourceType(), frame });
});

const page = await ctx.newPage();
try {
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 });
} catch (e) { console.error('goto:', e.message); }
await page.waitForTimeout(4000);

// Consent banners and big play buttons, in every frame. Best effort, like a person tapping.
const clicks = [
  'button:has-text("Accept all")', 'button:has-text("Accept")', 'button:has-text("Agree")', 'button:has-text("I agree")',
  'button:has-text("Accetta")', 'button:has-text("Tillad alle")', 'button:has-text("Accepter")',
  '.vjs-big-play-button', '.plyr__control--overlaid', '.jw-display-icon-display', '.ytp-large-play-button',
  'button[aria-label*="Play" i]', '[class*="big-play" i]', '[class*="play-button" i]',
];
for (const f of page.frames()) {
  for (const sel of clicks) {
    try { const el = await f.$(sel); if (el && await el.isVisible()) { await el.click({ timeout: 1500 }); await page.waitForTimeout(500); } } catch (e) {}
  }
}
// Then nudge any <video> that still isn't playing (muted, so autoplay rules allow it).
for (const f of page.frames()) {
  try { await f.evaluate(() => document.querySelectorAll('video').forEach((v) => { if (v.paused) { v.muted = true; v.play().catch(() => {}); } })); } catch (e) {}
}
await page.waitForTimeout(Number(secs) * 1000);
for (const f of page.frames()) { try { await f.evaluate(() => window.__tabcastScan && window.__tabcastScan()); } catch (e) {} }
await page.waitForTimeout(1000);
out.cookies = await ctx.cookies();
out.frames = page.frames().map((f) => f.url());
fs.mkdirSync(path.join(here, 'out'), { recursive: true });
fs.writeFileSync(path.join(here, 'out', name + '.json'), JSON.stringify(out, null, 1));
console.log(`${name}: ${out.requests.length} requests, ${out.js.length} js messages, ${out.frames.length} frames`);
await browser.close();
