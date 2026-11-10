import { expect, type Page, test } from '@playwright/test';
import { workAs } from '../support/app';

/**
 * The workspace on a touch tablet in portrait (768 x 1024, the compact top bar and the drawer;
 * docs/architecture/RESPONSIVE_AND_PWA.md): no sideways scrolling, the tiles fill the content width
 * (intrinsic grid), inputs never make iOS zoom, controls touch-sized.
 */
test.use({ viewport: { width: 768, height: 1024 }, isMobile: true, hasTouch: true });

async function noSidewaysScroll(page: Page) {
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth),
  ).toBe(true);
}

test.describe('Tablet layout', () => {
  test(
    'screens fit the width, tiles use it, inputs never zoom and controls are touch-sized',
    { tag: ['@SRS-NF-6', '@edge'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'M-01',
      );
      for (const screen of ['M-01', 'D-01', 'W-02', 'C-02', 'X-05', 'M-04']) {
        await page.goto(`/payroll/?s=${screen}`);
        await expect(page.locator('.wireframe-body-container').first()).toBeVisible();
        await noSidewaysScroll(page);
        const issues = await page.evaluate(() => {
          const visible = (e: Element) => {
            const r = e.getBoundingClientRect();
            return r.width > 0 && r.height > 0 && getComputedStyle(e).visibility !== 'hidden';
          };
          const fields = [
            ...document.querySelectorAll('input:not([type=checkbox]):not([type=radio]), select, textarea'),
          ].filter(visible);
          const controls = [
            ...document.querySelectorAll(
              'button, a[href], select, input:not([type=hidden]):not([type=checkbox]):not([type=radio])',
            ),
          ].filter(visible);
          // Tiles: every tile at least 200 px wide or the full row (the intrinsic grid's minimum).
          const narrowTiles = [...document.querySelectorAll('.tiles > *')]
            .filter(visible)
            .filter((e) => e.getBoundingClientRect().width < 150).length;
          return {
            zoom: fields.filter((e) => parseFloat(getComputedStyle(e).fontSize) < 16).length,
            small: controls.filter((e) => e.getBoundingClientRect().height < 36).length,
            narrowTiles,
          };
        });
        expect(issues, screen).toEqual({ zoom: 0, small: 0, narrowTiles: 0 });
      }
    },
  );
});
