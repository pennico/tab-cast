// SPDX-License-Identifier: GPL-3.0-or-later
// Screenshots of the start page as the Tab S2 shows it (2048x1536 at 2x = 1024x768 CSS px), light and dark, landscape
// and portrait. Input: app/build/start-page/*.html (written by StartPageTest). Output: out/start-*.png
// usage: node shot.mjs
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const dir = path.join(here, '../app/build/start-page');
fs.mkdirSync(path.join(here, 'out'), { recursive: true });
const browser = await chromium.launch();
for (const f of fs.readdirSync(dir).filter((n) => n.endsWith('.html'))) {
  const html = fs.readFileSync(path.join(dir, f), 'utf8');
  for (const [scheme, w, h] of [['dark', 1024, 768], ['light', 1024, 768], ['dark', 768, 1024]]) {
    const ctx = await browser.newContext({ viewport: { width: w, height: h }, deviceScaleFactor: 1, colorScheme: scheme, hasTouch: true, isMobile: true });
    const page = await ctx.newPage();
    await page.setContent(html, { waitUntil: 'load', timeout: 20000 }).catch(() => {});
    await page.waitForTimeout(1500);
    const name = `start-${f.replace('.html', '')}-${scheme}-${w}x${h}.png`;
    await page.screenshot({ path: path.join(here, 'out', name), fullPage: true });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
    console.log(name, overflow ? 'HORIZONTAL OVERFLOW' : 'ok');
    await ctx.close();
  }
}
await browser.close();
