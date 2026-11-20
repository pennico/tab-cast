// SPDX-License-Identifier: GPL-3.0-or-later
// Screenshots of Tab Cast TV's web remote (tv/src/main/assets/remote/index.html) against a mocked TV API, as an
// iPhone (390x844, light and dark) and a Mac (1280x800) show it: unpaired, paired with a page, banners, the
// bookmarklet's "Open … on the TV?" page. Output: out/remote-*.png. Usage: ./remote-shot.sh
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const html = fs.readFileSync(path.join(here, '../tv/src/main/assets/remote/index.html'));
const pic = fs.readFileSync(path.join(here, 'out/start-full-dark-1024x768.png'));
fs.mkdirSync(path.join(here, 'out'), { recursive: true });

const base = {
  url: 'https://www.ted.com/talks/a_talk_about_something_long', title: 'A talk about something long enough to wrap on a phone',
  mode: 'page', playing: 'true', pos: '125.4', dur: '1012.0', vol: '0.7', caught: '3', loading: 'false',
  ads: 'standard', adsglobal: 'standard', adssite: '', adsblocked: '23', adssuggest: 'false',
};
const states = {
  idle: { mode: 'idle', vol: '0.7', ads: 'standard', adsglobal: 'standard', adsready: 'true' },
  unpaired: null,
  page: base,
  banners: { ...base, adssuggest: 'true', blocked: 'https://pop.example-ads.net/land', playing: 'false' },
  player: { ...base, mode: 'player', video: 'HLS · 1080p · 16:52 · ▶ playing · cdn.ted.com' },
  open: base,
};
const browser = await chromium.launch();
for (const [name, st] of Object.entries(states)) {
  for (const [scheme, w, h, mobile] of [['light', 390, 844, true], ['dark', 390, 844, true], ['light', 1280, 800, false]]) {
    const ctx = await browser.newContext({ viewport: { width: w, height: h }, deviceScaleFactor: 2, colorScheme: scheme, hasTouch: mobile, isMobile: mobile });
    const page = await ctx.newPage();
    await page.route('http://tv.test/**', async (route) => {
      const u = new URL(route.request().url());
      if (!u.pathname.startsWith('/api/')) return route.fulfill({ status: 200, contentType: 'text/html', body: html });
      if (u.pathname === '/api/state') return st ? route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ tv: 'Living room TV', device: 'iPhone', status: st, caught: ['HLS · 1080p · 16:52 · ▶ playing · cdn.ted.com', 'MP4 · 720p · 16:52 · download.ted.com', 'MP4 · 360p · ads.example ad?'] }) })
        : route.fulfill({ status: 401, contentType: 'application/json', body: '{"ok":false}' });
      if (u.pathname === '/api/start') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
        sites: [{ title: 'DR TV', url: 'https://www.dr.dk/drtv' }, { title: 'RaiPlay', url: 'https://www.raiplay.it/' }, { title: 'YouTube', url: 'https://m.youtube.com/' }, { title: 'Arte', url: 'https://www.arte.tv/' }],
        recent: [{ title: 'hls.js demo page', url: 'https://hlsjs.video-dev.org/demo/' }, { title: 'Big Buck Bunny', url: 'https://archive.org/details/BigBuckBunny_124' }],
        tests: [{ title: 'HLS stream (hls.js demo)', url: 'x' }, { title: 'MP4 file (archive.org)', url: 'x' }, { title: 'PeerTube video', url: 'x' }, { title: 'TED talk', url: 'x' }] }) });
      if (u.pathname === '/api/preview.jpg') return route.fulfill({ status: 200, contentType: 'image/png', body: pic });
      if (u.pathname === '/api/devices') return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify([{ id: 'a', name: 'iPhone', kind: 'web', lastSeen: 1794700000000, you: true }, { id: 'b', name: 'samsung SM-T715', kind: 'tablet', lastSeen: 1794690000000 }]) });
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{"ok":true}' });
    });
    const url = name === 'open' ? 'http://tv.test/open?url=' + encodeURIComponent('https://www.dr.dk/nyheder/x') : 'http://tv.test/';
    await page.goto(url);
    await page.waitForTimeout(1200);
    if (name === 'banners') { await page.click('#adsBtn'); await page.click('#videosBtn'); }
    const file = `remote-${name}-${scheme}-${w}.png`;
    await page.screenshot({ path: path.join(here, 'out', file), fullPage: true });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
    console.log(file, overflow ? 'HORIZONTAL OVERFLOW' : 'ok');
    await ctx.close();
  }
}
await browser.close();
