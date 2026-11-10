import { expect, test } from '@playwright/test';
import { apiStatus, t, workAs } from '../support/app';

/**
 * I-24 Leave Encashment Arrear (plan 13 phase 13d) on the demo order DEMO/DA-REV/LEA-01 and the demo encashment of
 * Rakesh Sharma (EIS 104882; seed V048_7_9). The Payroll Maker runs the arrear for the member and submits the calculated
 * record with a remark (once: a re-run counts the submitted record as paid and writes no new one); Finance reads the
 * records but neither runs nor submits, on the screen or by the API.
 */
const office = { office: 'DH001' };
const EIS = '104882';

test.describe.serial('Leave encashment arrear (I-24)', () => {
  test(
    'SRS-8-23 / 8-24: the Maker runs the arrear against the revision order and submits the record',
    { tag: ['@SRS-8-23', '@SRS-8-24', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', 'maker.delhi@fci-demo.test', { role: 'PAYROLL_MAKER', ...office }, 'I-24');
      // Not drawn, kept behind the drawn button (WF-187): the run opens with "Submit LE arrears".
      await page.getByRole('button', { name: t('en', 'i24.act.submit') }).click();
      const run = page.getByRole('button', { name: t('en', 'action.i24.run') });
      await expect(run).toBeDisabled();
      await page
        .getByLabel(t('en', 'i24.revisionOrder'))
        .selectOption({ label: 'DEMO/DA-REV/LEA-01 - DA revision (demo order for I-24)' });
      const effective = new Date();
      effective.setDate(1);
      effective.setMonth(effective.getMonth() - 3);
      await page
        .getByLabel(t('en', 'i24.effective'))
        .fill(`${effective.getFullYear()}-${String(effective.getMonth() + 1).padStart(2, '0')}-01`);
      await page.getByLabel(t('en', 'i24.runEis')).fill(EIS);
      await page.getByLabel(t('en', 'common.reason')).last().fill('DA revision order (E2E)');
      await run.click();
      await expect(page.getByRole('status').filter({ hasText: 'Members recalculated: 1' })).toBeVisible();
      await page.getByRole('button', { name: t('en', 'common.close') }).click();

      await page.getByRole('button', { name: t('en', 'i24.act.report') }).click();
      await page.getByLabel(t('en', 'm01.eis')).fill(EIS);
      await page.getByRole('button', { name: t('en', 'common.close') }).click();
      const table = page.getByRole('table', { name: t('en', 'screen.I-24') });
      await expect(table.locator('tbody tr').first()).toBeVisible();
      const calculated = table
        .locator('tbody tr')
        .filter({ hasText: t('en', 'i24.invoiceStatus.CALCULATED') });
      if ((await calculated.count()) > 0) {
        await calculated.first().getByRole('checkbox').check();
        await page.getByRole('button', { name: t('en', 'i24.act.submit') }).click();
        await page.getByLabel(t('en', 'common.reason')).first().fill('LE arrear (E2E)');
        await page.getByRole('button', { name: new RegExp(t('en', 'action.i24.submit')) }).click();
        await expect(page.getByRole('status').filter({ hasText: t('en', 'i24.submitted') })).toBeVisible();
        await page.getByRole('button', { name: t('en', 'common.close') }).click();
      }
      await expect(table).toContainText(t('en', 'i24.invoiceStatus.SUBMITTED'));
    },
  );

  test(
    "SRS-G-25: running and submitting are the Payroll Maker's; Finance is refused on the screen and by the API",
    { tag: ['@SRS-G-25', '@SRS-8-24', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', 'finance.hq@fci-demo.test', { role: 'FINANCE', office: 'HQ001' }, 'I-24');
      await expect(page.getByRole('table', { name: t('en', 'screen.I-24') })).toBeVisible();
      await expect(page.getByRole('button', { name: t('en', 'action.i24.run') })).toHaveCount(0);
      const submit = page.getByRole('button', { name: t('en', 'i24.act.submit') });
      await expect(submit).toBeDisabled();
      await expect(submit).toHaveAttribute('title', t('en', 'i24.guard.submit'));
      expect(
        await apiStatus(page, '/v1/leave-encashment-arrears/runs', 'POST', {
          circularId: 1,
          revisionEffective: '2026-07-01',
          reason: 'x',
        }),
      ).toBe(403);
      expect(
        await apiStatus(page, '/v1/leave-encashment-arrears/submissions', 'POST', { ids: [1], remark: 'x' }),
      ).toBe(403);
    },
  );

  test(
    'SRS l.3129-3141 (group g2): old pay, TDS, invoice status and the calculation sheet per record; no invoice before APPS',
    { tag: ['@SRS-1-43', '@SRS-8-23', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', 'maker.delhi@fci-demo.test', { role: 'PAYROLL_MAKER', ...office }, 'I-24');
      await page.getByRole('button', { name: t('en', 'i24.act.report') }).click();
      const send = page.getByRole('button', { name: t('en', 'i24.sendInvoices') });
      await expect(send).toBeDisabled();
      await expect(send).toHaveAttribute('title', t('en', 'i24.why.invoices'));
      await page.getByLabel(t('en', 'm01.eis')).fill(EIS);
      const table = page.getByRole('table', { name: t('en', 'screen.I-24') });
      const header = table.locator('thead');
      for (const key of ['i24.col.oldPay', 'i24.col.revisedPay', 'i24.col.tds', 'i24.col.invoiceStatus']) {
        await expect(header).toContainText(t('en', key));
      }
      const first = table.locator('tbody tr').first();
      await expect(first).toContainText(EIS);
      const download = page.waitForEvent('download');
      await page.getByRole('button', { name: /calculation sheet/i }).click();
      const file = await download;
      expect(file.suggestedFilename()).toMatch(/^LEA-.*-calculation-sheet\.csv$/);
    },
  );
});
