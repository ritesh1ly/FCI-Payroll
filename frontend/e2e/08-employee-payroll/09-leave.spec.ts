import { expect, test } from '@playwright/test';
import { apiStatus, decide, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql, sqlRows } from '../support/db';

/**
 * Module 19 Leave (plan 19 phases 19a / 19b) on the demo stack: M-13 shows the leave types from HRMS
 * with the pay effect M-20 holds; the Administrator (Rule Administrator at HQ) proposes an M-20
 * version that the HQ Payroll Checker approves on W-01, a Payroll Maker cannot propose; I-11 shows a
 * leave event received from HRMS to the unit's desk and not to the Administrator. Re-runnable: each
 * run proposes a version from the day after the latest one and receives its own sanction reference.
 */
const MAKER = 'maker.delhi@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };
const ADMIN = 'su.hq@fci-demo.test';
const admin = { role: 'RULE_ADMINISTRATOR', office: 'HQ001' };

test.describe.serial('Leave (M-13, M-20, I-11)', () => {
  const stamp = Date.now();

  test(
    'SRS-LV-24: M-13 lists the leave types from HRMS with their pay effect, read-only',
    { tag: ['@SRS-LV-24', '@SRS-LV-27', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'M-13');
      const table = page.getByRole('table', { name: t('en', 'screen.M-13') });
      await expect(table.getByRole('row', { name: /Leave Without Pay/ })).toContainText(
        t('en', 'lv.effect.FULL_DEDUCTION'),
      );
      await expect(table.getByRole('row', { name: /Earned Leave/ })).toContainText(t('en', 'common.yes'));
      // Read-only: no action on any leave type (BR-LEAVE-003); each row only opens its change
      // history (X-04, US-GAP-24).
      const history = table.getByRole('button', { name: /^Change history of / });
      await expect(history.first()).toBeVisible();
      await expect(table.getByRole('button')).toHaveCount(await history.count());
    },
  );

  test(
    'SRS-LV-14: the Administrator proposes an M-20 version and the HQ Payroll Checker approves it',
    { tag: ['@SRS-LV-14', '@SRS-G-1', '@positive'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const [from] = (
        sqlRows(
          `SELECT TO_CHAR(GREATEST(COALESCE(MAX(effective_from), CURRENT_DATE), CURRENT_DATE) + 1, 'YYYY-MM-DD') AS next_day FROM leave_pay_parameter WHERE parameter_code = 'ENCASH_DIVISOR' AND leave_type_code IS NULL`,
        )[0] ?? []
      ).map(String);
      const reason = `E2E M-20 ${stamp}`;
      const page = await browser.newPage();
      await workAs(page, 'en', ADMIN, admin, 'M-20');
      await page
        .getByTestId('m20-register')
        .getByRole('button', { name: t('en', 'm20.newVersion') })
        .click();
      await page.locator('#m20-code').selectOption('ENCASH_DIVISOR');
      await page.locator('#m20-value').fill('30');
      await page.locator('#m20-from').fill(String(from));
      await page.locator('#m20-reason').fill(reason);
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(page.getByRole('status').filter({ hasText: 'ENCASH_DIVISOR' })).toBeVisible();

      const checker = await browser.newPage();
      await workAs(
        checker,
        'en',
        'checker.hq@fci-demo.test',
        { role: 'PAYROLL_CHECKER', office: 'HQ001' },
        'W-01',
      );
      await decide(checker, new RegExp(reason), 'APPROVE');

      await page.reload();
      await page.locator('[data-testid="m20-register"] > summary').click();
      await expect(
        page
          .getByRole('table', { name: t('en', 'screen.M-20') })
          .locator(`tr[data-reference="ENCASH_DIVISOR||${String(from)}"]`),
      ).toContainText(t('en', 'm20.status.ACTIVE'));
    },
  );

  test(
    'SRS-LV-14: a Payroll Maker sees M-20 but cannot propose, by the screen or the API',
    { tag: ['@SRS-LV-14', '@SRS-G-25', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'M-20');
      const action = page
        .locator('.toolbar')
        .first()
        .getByRole('button', { name: t('en', 'm20w.newVersion') });
      await expect(action).toBeDisabled();
      await expect(action).toHaveAttribute('data-why', t('en', 'm20w.why.ruleAdmin'));
      expect(
        await apiStatus(page, '/v1/leave-pay-parameters', 'POST', {
          code: 'ENCASH_DIVISOR',
          number: 30,
          effectiveFrom: '2030-01-01',
          reason: 'forged',
        }),
      ).toBe(403);
    },
  );

  test(
    'SRS-LV-26 / SRS-LV-14: I-11 shows the unit its leave events; the Administrator has no access',
    { tag: ['@SRS-LV-26', '@SRS-LV-14', '@SRS-G-29', '@positive'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const ref = `E2E/LV/${stamp}`;
      // A sanction as HRMS sends it (the interface, module 23, is not built): the I-21 task is not
      // needed to list it.
      sql(
        `INSERT INTO leave_application (employee_id, leave_type_code, applied_from, applied_to, days_applied, hrms_sanction_ref, decision, source_code) SELECT employee_id, 'CL', CURRENT_DATE, CURRENT_DATE, 1, '${ref}', 'SANCTIONED', 'HRMS' FROM employee WHERE emp_num = '104882'`,
      );
      await workAs(page, 'en', MAKER, maker, 'I-11');
      await page.getByLabel(t('en', 'm01.eis')).fill('104882');
      const row = page
        .getByRole('table', { name: t('en', 'screen.I-11') })
        .locator(`tr[data-reference="${ref}"]`);
      await expect(row).toContainText('CL - Casual Leave');
      await expect(row).toContainText(t('en', 'i11.status.PENDING'));

      await workAs(page, 'en', ADMIN, admin, 'M-13');
      expect(await apiStatus(page, '/v1/leave-events')).toBe(403);
    },
  );
});
