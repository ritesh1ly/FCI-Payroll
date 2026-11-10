import { expect, type Page, test } from '@playwright/test';
import { t, workAs } from '../support/app';

/**
 * The workspace on a touch phone (docs/architecture/RESPONSIVE_AND_PWA.md): no sideways scrolling,
 * every control reachable from the compact top bar and the drawer, touch-sized targets, no input that
 * makes iOS zoom.
 */
test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });

async function noSidewaysScroll(page: Page) {
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth),
  ).toBe(true);
}

test.describe('Phone layout', () => {
  test(
    'the login page fits and its controls are touch-sized',
    { tag: ['@SRS-NF-6', '@positive'] },
    async ({ page }) => {
      await page.goto('/');
      await noSidewaysScroll(page);
      const small = await page.evaluate(() => {
        const short = (e: Element) => {
          const r = e.getBoundingClientRect();
          return r.width > 0 && r.height < 36;
        };
        // A check box is tapped through its label: the label is the target that must be touch-sized.
        const boxes = [...document.querySelectorAll('input[type=checkbox], input[type=radio]')]
          .map((e) => e.closest('label'))
          .filter((l): l is HTMLLabelElement => l !== null);
        return [
          ...document.querySelectorAll(
            'button, input:not([type=checkbox]):not([type=radio]), select, a[href]',
          ),
          ...boxes,
        ].filter(short).length;
      });
      expect(small).toBe(0);
    },
  );

  test(
    'top bar, drawer and search work on a phone',
    { tag: ['@SRS-NF-6', '@positive'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'M-01',
      );
      await noSidewaysScroll(page);
      // The top bar keeps ☰, 🔎 and the account on screen.
      for (const selector of ['.navtoggle', '.searchtoggle', '.me']) {
        const box = await page.locator(selector).boundingBox();
        expect(box && box.x >= 0 && box.x + box.width <= 390).toBe(true);
      }
      // Office and role live in the drawer.
      await page.locator('.navtoggle').click();
      await expect(
        page.locator('.side-ctx').getByLabel(t('en', 'top.office'), { exact: true }),
      ).toBeVisible();
      await expect(page.locator('.side-ctx').getByLabel(t('en', 'top.role'), { exact: true })).toHaveValue(
        'PAYROLL_MAKER@DH001',
      );
      await page.keyboard.press('Escape');
      await expect(page.locator('.wf.navopen')).toHaveCount(0);
      // Search opens over the bar with the cursor in it and navigates.
      await page.locator('.searchtoggle').click();
      await expect(page.getByRole('searchbox', { name: t('en', 'top.search') })).toBeFocused();
      await page.keyboard.type('rate');
      await page.getByRole('option', { name: /M-04/ }).click();
      await expect(page).toHaveURL(/s=M-04/);
    },
  );

  test(
    'screens fit, inputs never make iOS zoom, controls are touch-sized',
    { tag: ['@SRS-NF-6', '@SRS-NF-3', '@edge'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'W-02',
      );
      // Screens of the Delhi maker's own role (R-01 came only from an A-06 per-user grant, retired by D-1).
      for (const screen of ['W-02', 'M-01', 'I-17', 'C-02', 'C-13', 'X-05', 'M-04']) {
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
          return {
            zoom: fields.filter((e) => parseFloat(getComputedStyle(e).fontSize) < 16).length,
            small: controls.filter((e) => e.getBoundingClientRect().height < 36).length,
          };
        });
        expect(issues, screen).toEqual({ zoom: 0, small: 0 });
      }
    },
  );
});
