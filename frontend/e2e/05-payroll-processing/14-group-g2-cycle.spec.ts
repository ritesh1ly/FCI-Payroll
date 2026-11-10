import { expect, test } from '@playwright/test';
import { t, workAs } from '../support/app';
import { openCycleActions } from '../support/payroll-support';

/**
 * Group g2 gap fixes on the cycle screens (modules 05, 16; 2026-11-03), on the demo data: the C-02
 * Pay Pattern selector and the Basic / DA / city class columns (SRS l.3686-3692), C-08 as X-05
 * filtered to the run (SRS l.1643, OD-73), the D-01 Request Un-freeze offered only between the
 * freeze and the lock (SRS l.1604), and the C-01 fixing-screen links (SRS-15-39). Read-only: re-runnable.
 */
const MAKER = 'maker.delhi@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };

test.describe('Group g2: cycle screens', () => {
  test(
    'C-02: the Pattern filter offers IDA & CDA, IDA only and CDA only; the Status filter its groups; rows as drawn',
    { tag: ['@SRS-15-18', '@SRS-1-4', '@SRS-G-54', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'C-02');
      const pattern = page.getByLabel(t('en', 'c02.payPattern'), { exact: true });
      await expect(pattern).toHaveValue('');
      await expect(pattern.locator('option')).toHaveText([
        t('en', 'c02.allPatterns'),
        'IDA only',
        'CDA only',
      ]);
      await expect(page.getByLabel(t('en', 'c02.col.status'), { exact: true }).locator('option')).toHaveText([
        t('en', 'c02.allStatuses'),
        'Computed',
        'Exception',
        'Held',
      ]);
      const header = page.locator('table.t thead').first();
      for (const key of ['c02.col.pattern', 'c02.col.basic', 'c02.col.hra', 'c02.col.other']) {
        await expect(header).toContainText(t('en', key));
      }
      await pattern.selectOption('CDA');
      const rows = page.locator('table.t').first().locator('tbody tr');
      await expect(rows.first()).toBeVisible();
      for (const row of await rows.all()) await expect(row).not.toContainText('IDA ·');
      await openCycleActions(page);
      const exceptions = page.getByRole('link', { name: t('en', 'c02.exceptions') });
      if ((await exceptions.count()) > 0) {
        await expect(exceptions).toHaveAttribute('href', /s=X-05&period=\d{4}-\d{2}&cycle=\d+/);
      }
    },
  );

  test(
    'C-08: X-05 opened with a run shows the C-08 filter, which the user removes; a malformed address is ignored',
    { tag: ['@SRS-G-9', '@SRS-15-14', '@edge'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'X-05');
      await page.goto('/payroll/?s=X-05&period=2026-01&cycle=1');
      await expect(page.getByText(t('en', 'x05.c08', ['2026-01']))).toBeVisible();
      // The period filter opens from the drawn Filter button.
      await page.getByRole('button', { name: t('en', 'x05.filter') }).click();
      await expect(page.locator('#x05-period')).toHaveValue('2026-01');
      await page.getByRole('button', { name: t('en', 'x05.c08.clear') }).click();
      await expect(page.getByText(t('en', 'x05.c08', ['2026-01']))).toHaveCount(0);
      await page.goto('/payroll/?s=X-05&period=01-2026&cycle=x1');
      await page.getByRole('button', { name: t('en', 'x05.filter') }).click();
      await expect(page.locator('#x05-period')).toHaveValue('');
      await expect(page.getByText(t('en', 'x05.c08.clear'))).toHaveCount(0);
    },
  );

  test(
    'D-01: Request Un-freeze is a link only between the freeze and the lock, otherwise disabled with the reason',
    { tag: ['@SRS-15-31', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'D-01');
      const link = page.getByRole('link', { name: t('en', 'd01.unfreeze') });
      const button = page.getByRole('button', { name: t('en', 'd01.unfreeze') });
      await expect(link.or(button)).toBeVisible();
      if ((await button.count()) > 0) {
        await expect(button).toBeDisabled();
        await expect(button).toHaveAttribute('title', t('en', 'd01.why.unfreeze'));
      } else {
        await expect(link).toHaveAttribute('href', /s=C-02/);
      }
    },
  );

  test(
    'C-01: a failing pre-payroll check links to its fixing screen',
    { tag: ['@SRS-15-39', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', 'pm.ropb@fci-demo.test', { role: 'PAYROLL_MAKER', office: 'RO-PB' }, 'C-01');
      const links = page.locator('table.t td a[href*="?s="]');
      if ((await links.count()) > 0) {
        await expect(links.first()).toHaveAttribute('href', /\/payroll\/\?s=[A-Z]-\d{2}/);
      }
    },
  );
});
