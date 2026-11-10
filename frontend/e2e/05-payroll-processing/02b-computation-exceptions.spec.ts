import { expect, test } from '@playwright/test';
import { t, workAs } from '../support/app';

/**
 * C-08 Payroll Computation - Exceptions Review (SRS-G-9, SRS l.1641-1678, US-05-08, US-GAP-100; demo rows V081_11) and the
 * read gates of C-01 for the Administrator (SRS l.1755-1767): the drawn fields, the HRMS-owned row, Establishment / HR
 * read-only without X-05. Read-only: nothing here changes a record.
 */
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };

test.describe('Computation exceptions (C-08)', () => {
  test(
    'SRS-G-9: the Maker reads the run by condition with its eight fields',
    { tag: ['@SRS-G-9', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', 'maker.delhi@fci-demo.test', maker, 'C-08');
      for (const tile of ['c08.tile.total', 'c08.tile.fixable', 'c08.tile.hrms', 'c08.tile.system']) {
        await expect(page.locator('.tile').filter({ hasText: t('en', tile) })).toBeVisible();
      }
      const table = page.getByRole('table', { name: t('en', 'c08.worklist') });
      const row = table.getByRole('row').filter({ hasText: 'RUN_NEGATIVE_NET' });
      await expect(row).toContainText(t('en', 'c08.type.STAFF'));
      await expect(row).toContainText('Net pay');
      await expect(row).toContainText(t('en', 'x05.yes'));
    },
  );

  test(
    'SRS-G-45: a condition on an HRMS-owned field is corrected in HRMS, Edit disabled with the reason',
    { tag: ['@SRS-G-45', '@SRS-G-9', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', 'maker.delhi@fci-demo.test', maker, 'C-08');
      const row = page.getByRole('row').filter({ hasText: 'RUN_BANK_INVALID' });
      await expect(row).toContainText(t('en', 'c08.fix.upstream'));
      await expect(
        row.getByRole('button', { name: t('en', 'x05.editOf', ['RUN_BANK_INVALID']) }),
      ).toHaveAttribute('data-why', t('en', 'c08.why.hrmsOwned'));
    },
  );

  test(
    'CLR-46: the drawn severity grades say why when chosen and filter nothing until FCI maps them',
    { tag: ['@SRS-G-9', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', 'maker.delhi@fci-demo.test', maker, 'C-08');
      const drawn = page.getByRole('combobox', { name: t('en', 'c08.severity') }).first();
      // Drawn enabled (OD-121): choosing a grade says why and sends no filtered read (no severity is guessed).
      await expect(drawn).toHaveAttribute('data-why', t('en', 'c08.why.severity'));
      const filtered: string[] = [];
      page.on('request', (r) => {
        if (r.url().includes('/api/') && /[?&]severity=/.test(r.url())) filtered.push(r.url());
      });
      await drawn.selectOption({ index: 1 });
      await expect(page.getByRole('status')).toContainText(t('en', 'c08.why.severity'));
      await expect(drawn).toHaveValue('');
      await expect(page.locator('.oq').filter({ hasText: t('en', 'c08.filtered') })).toHaveCount(0);
      expect(filtered).toEqual([]);
    },
  );

  test(
    'US-GAP-100: Establishment / HR reads the run and acts on nothing',
    { tag: ['@SRS-G-9', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'hr.delhi@fci-demo.test',
        { role: 'ESTABLISHMENT_HR', office: 'DH001' },
        'C-08',
      );
      await expect(page.getByRole('table', { name: t('en', 'c08.worklist') })).toBeVisible();
      await expect(page.getByRole('button', { name: t('en', 'c08.assign') })).toBeDisabled();
      await expect(page.getByRole('button', { name: t('en', 'c08.rerun') })).toBeDisabled();
      await expect(page.getByRole('button', { name: '✔' })).toHaveCount(0);
    },
  );
});

test.describe('C-01 for the Administrator', () => {
  test(
    'SRS-15-10: the Administrator reads the screen on C-01 VIEW; Run and Verify stay disabled with the reason',
    { tag: ['@SRS-15-10', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'sysadmin@fci-demo.test',
        { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' },
        'C-01',
      );
      await expect(page.getByRole('heading', { name: t('en', 'c01w.periods') })).toBeVisible();
      await expect(page.getByRole('heading', { name: t('en', 'c01w.runState') })).toBeVisible();
      await expect(page.getByRole('button', { name: t('en', 'c01w.run') })).toHaveAttribute(
        'data-why',
        t('en', 'c01w.why.maker'),
      );
      await expect(page.getByRole('button', { name: t('en', 'c01w.verifyLock') })).toHaveAttribute(
        'data-why',
        t('en', 'c01w.why.checker'),
      );
      await expect(page.getByRole('link', { name: t('en', 'c01w.undo') })).toHaveAttribute('href', /s=W-01/);
    },
  );
});
