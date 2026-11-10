import { expect, test } from '@playwright/test';
import { t, workAs } from '../support/app';

/**
 * I-04 loans register at the larger page sizes and its export (SRS-NF-3: lists page on the server at
 * 10 / 20 / 50 / 100 rows; SRS-12-26 / SRS-G-13: the Loan Recovery & Outstanding Report exports).
 * Read only on the demo data (V077_28 gives Delhi its loans): re-runnable.
 */
const MAKER = 'maker.delhi@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };

test.describe('I-04 loans register: page sizes and export', () => {
  test(
    'SRS-NF-3: the loans register asks the server for 50 and then 100 rows and shows at most that many',
    { tag: ['@SRS-NF-3', '@SRS-12-26', '@edge'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'I-04');
      const register = page.getByRole('table', { name: t('en', 'i04l.loans') });
      await expect(register).toBeVisible();
      const card = page.locator('.card').filter({ has: register });
      for (const size of ['50', '100']) {
        const asked = page.waitForRequest(
          (r) => r.method() === 'GET' && /\/v1\/loans\?/.test(r.url()) && r.url().includes(`size=${size}`),
        );
        await card.getByLabel(t('en', 'page.size')).selectOption(size);
        await asked;
        await expect(card.getByLabel(t('en', 'page.size'))).toHaveValue(size);
        expect(await register.locator('tbody tr').count()).toBeLessThanOrEqual(Number(size));
      }
    },
  );

  test(
    'SRS-12-26: the Loan Recovery & Outstanding Report exports for a range of months as CSV',
    { tag: ['@SRS-12-26', '@SRS-G-13', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'I-04');
      const report = page
        .locator('.card')
        .filter({ hasText: t('en', 'i04l.report') })
        .last();
      await report.getByRole('button', { name: t('en', 'i04l.reportExport') }).click();
      const dialog = page.getByRole('dialog');
      await dialog.locator('input[value="CSV"]').check();
      const link = dialog.getByRole('link', { name: t('en', 'export.download') });
      await expect(link).toHaveAttribute(
        'href',
        /\/v1\/loans\/report\?from=\d{4}-?\d{2}&to=\d{4}-?\d{2}.*format=CSV/,
      );
      const downloading = page.waitForEvent('download');
      await link.click();
      expect((await downloading).suggestedFilename()).toMatch(/\.csv$/);
    },
  );
});
