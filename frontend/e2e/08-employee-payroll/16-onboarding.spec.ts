import { expect, test } from '@playwright/test';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql } from '../support/db';
import { apiStatus, decide, t, workAs } from '../support/app';
import { CHECKER, MAKER, checker, maker } from '../support/payroll-support';

/**
 * M-01 onboarding of a new staff record (docs/plan/16-*.md D16-34 ... D16-37; SRS-1-11, SRS-1-12):
 * Establishment / HR submits a record in Payroll Setup Pending with a remark and the Payroll
 * Checker approves it on W-01, after which it is Active. Negatives: without a remark nothing is
 * sent; the Payroll Maker never submits a record.
 *
 * Demo data V008_1: 106512 Vinod Kulkarni at DH001 in Payroll Setup Pending. Re-runnable: the
 * record is put back in Payroll Setup Pending first (DB fixture).
 */
const HR = 'hr.delhi@fci-demo.test';
const hr = { role: 'ESTABLISHMENT_HR', office: 'DH001' };
const EIS = '106512';
const run = String(Math.floor(Math.random() * 9000) + 1000);

test.describe.serial('M-01 onboarding', () => {
  test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);

  test.beforeAll(() => {
    sql(`UPDATE employee SET record_status = 'PAYROLL_SETUP_PENDING' WHERE emp_num = '${EIS}'`);
  });

  test(
    'SRS-1-11: HR submits a record in Payroll Setup Pending and the Payroll Checker approves it',
    { tag: ['@SRS-1-11', '@SRS-1-12', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', HR, hr, 'M-01');
      await page.goto(`/payroll/?s=M-01&eis=${EIS}`);
      await expect(page.getByText(t('en', 'm01.setupPendingNote'))).toBeVisible();
      const send = page.locator('button.btn.primary', { hasText: t('en', 'action.m01.onboard') });
      await expect(send).toBeDisabled();
      await page.getByLabel(new RegExp(`^${t('en', 'm01.onboardReason')}`)).fill(`E2E onboarding ${run}`);
      await send.click();
      await expect(page.getByText(t('en', 'm01.onboardSent'))).toBeVisible();

      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(`E2E onboarding ${run}`), 'APPROVE');
      await workAs(page, 'en', HR, hr, 'M-01');
      await page.goto(`/payroll/?s=M-01&eis=${EIS}`);
      await expect(page.getByText(t('en', 'm01.setupPendingNote'))).toHaveCount(0);
    },
  );

  test(
    'SRS-1-11: the Payroll Maker never submits a record for onboarding',
    { tag: ['@SRS-1-11', '@negative'] },
    async ({ page }) => {
      sql(`UPDATE employee SET record_status = 'PAYROLL_SETUP_PENDING' WHERE emp_num = '${EIS}'`);
      await workAs(page, 'en', MAKER, maker, 'M-01');
      await page.goto(`/payroll/?s=M-01&eis=${EIS}`);
      const send = page.locator('button.btn.primary', { hasText: t('en', 'action.m01.onboard') });
      await expect(send).toBeDisabled();
      await expect(send).toHaveAttribute('title', t('en', 'reason.hrOnly'));
      expect(
        await apiStatus(page, `/v1/employees/${EIS}/onboarding-submissions`, 'POST', { reason: 'E2E' }),
      ).toBe(403);
    },
  );
});
