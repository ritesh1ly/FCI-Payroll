import { expect, test } from '@playwright/test';
import { businessToday, decide, t, workAs, pickerMonth } from '../support/app';
import { expectToast } from '../support/payroll-support';

/**
 * I-09 CPF Adjustment / Recovery Adjustment through the UI (docs/plan/07-cpf.md phase 07g;
 * SRS-6-27 … 6-30): Manager (CPF) North records a contribution receipt for the demo deputed member
 * 871901 (seed V043_2_9) with the remittance evidence, AGM (CPF) North approves it in the Approvals
 * Inbox. Re-runnable: each run uses a month further ahead (the deputation is open-ended).
 */
const MANAGER = 'cpf.manager.north@fci-demo.test';
const manager = { role: 'CPF_MANAGER', office: 'NA11' };
const AGM = 'agm.cpf.north@fci-demo.test';
const agm = { role: 'AGM_CPF', office: 'NA11' };

/** A month far enough ahead that each run records its own (YYYYMM). */
function runMonth(): string {
  const offset = 24 + (Math.floor(Date.now() / 1000) % 600);
  const today = businessToday();
  const d = new Date(Date.UTC(Number(today.slice(0, 4)), Number(today.slice(5, 7)) - 1 + offset, 1));
  return `${d.getUTCFullYear()}${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
}

test.describe.serial('Module 07 I-09 deputation adjustment', () => {
  test(
    'SRS-6-30: a receipt recorded by the Manager (CPF) is approved by the AGM (CPF)',
    { tag: ['@SRS-6-27', '@SRS-6-30', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MANAGER, manager, 'I-09');
      await page.getByLabel(t('en', 'i09.eis')).fill('871901');
      await page.getByRole('button', { name: t('en', 'i09.find') }).click();
      await expect(page.getByLabel(t('en', 'i09.deputationHead'))).toBeVisible();
      await page
        .getByLabel(t('en', 'i09.organisation'))
        .selectOption({ label: 'Demo Public Sector Undertaking One (DEMO-PSU-1)' });
      await page.getByLabel(t('en', 'i09.month')).fill(pickerMonth(runMonth()));
      await page.getByLabel(t('en', 'i09.employee')).fill('1200');
      await page.getByLabel(t('en', 'i09.employer')).fill('1200');
      await page.getByLabel(t('en', 'ord.type')).selectOption('OTHER');
      await page.getByLabel(t('en', 'ord.date')).fill(businessToday());
      await page.getByLabel(t('en', 'ord.authority')).fill('Demo PSU accounts');
      await page.getByLabel(t('en', 'ord.file')).setInputFiles({
        name: 'remittance.pdf',
        mimeType: 'application/pdf',
        buffer: Buffer.from('%PDF-1.7 remittance'),
      });
      await page.getByRole('button', { name: t('en', 'i09.submit') }).click();
      await expectToast(page, t('en', 'i09.submitted'));

      await workAs(page, 'en', AGM, agm, 'W-01');
      await decide(page, /I-09 CONTRIBUTION_RECEIPT 871901/, 'APPROVE');
    },
  );

  test(
    'SRS-6-27: a month outside the deputation cannot be submitted',
    { tag: ['@SRS-6-27', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MANAGER, manager, 'I-09');
      await page.getByLabel(t('en', 'i09.eis')).fill('871901');
      await page.getByRole('button', { name: t('en', 'i09.find') }).click();
      await page.getByLabel(t('en', 'i09.month')).fill('2000-01');
      const submit = page.getByRole('button', { name: t('en', 'i09.submit') });
      await expect(submit).toBeDisabled();
      await expect(page.getByText(t('en', 'i09.bad.month'))).toBeVisible();
    },
  );
});
