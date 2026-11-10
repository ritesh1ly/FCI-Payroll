#!/usr/bin/env node
/**
 * Screenshots of the login wireframe beside the deployed sign-in page, at the viewports the page must
 * fit (OD-40), for a side-by-side check after a change to the page.
 * Run: node scripts/compare-login.mjs <wireframe.html> [page base URL, default https://localhost:8743/nginx] [out dir]
 */
import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { chromium } from '@playwright/test';

const [wireframe, base = 'https://localhost:8743/nginx', out = 'test-results/login-compare'] =
  process.argv.slice(2);
if (!wireframe) {
  console.error('usage: node scripts/compare-login.mjs <wireframe.html> [base URL] [out dir]');
  process.exit(2);
}
const VIEWPORTS = [
  ['1920x1080', 1920, 1080],
  ['1366x768', 1366, 768],
  ['1280x720', 1280, 720],
  ['1024x768', 1024, 768],
  ['768x1024', 768, 1024],
  ['390x844', 390, 844],
];
mkdirSync(out, { recursive: true });
const browser = await chromium.launch();
try {
  for (const [name, width, height] of VIEWPORTS) {
    const context = await browser.newContext({ viewport: { width, height }, ignoreHTTPSErrors: true });
    const page = await context.newPage();
    const problems = [];
    page.on('console', (m) => m.type() === 'error' && problems.push(m.text()));
    await page.goto(pathToFileURL(resolve(wireframe)).href);
    await page.waitForTimeout(1500);
    await page.screenshot({ path: `${out}/${name}-wireframe.png` });
    problems.length = 0;
    await page.goto(base);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1500);
    await page.screenshot({ path: `${out}/${name}-app.png` });
    const fit = await page.evaluate(() => ({
      scrollH: document.documentElement.scrollHeight,
      innerH: window.innerHeight,
      scrollW: document.documentElement.scrollWidth,
      innerW: window.innerWidth,
    }));
    console.log(
      `${name}: page ${fit.scrollW}x${fit.scrollH} in ${fit.innerW}x${fit.innerH}` +
        `${fit.scrollH > fit.innerH ? ' SCROLLS' : ''}${fit.scrollW > fit.innerW ? ' H-SCROLL' : ''}` +
        `${problems.length ? ` console errors: ${problems.join(' | ')}` : ''}`,
    );
    await context.close();
  }
} finally {
  await browser.close();
}
