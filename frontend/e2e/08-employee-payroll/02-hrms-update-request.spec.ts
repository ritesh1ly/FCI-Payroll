import { expect, type Page, test } from '@playwright/test';
import { t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql, sqlRows } from '../support/db';

/**
 * HRMS Update Request (plan 16 phase 16c; SRS-1-70) at Delhi HQ (DH001) and in eSeva. The local stack has no HRMS
 * interface yet (module 23): a request for a change HRMS already sent links that pending I-21 task; any other request
 * records the fetch as "not configured", which the Maker can fetch again; the employee raises their own in eSeva.
 * Re-runnable.
 */
const MAKER = 'maker.delhi@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };

/** The EIS code of the Delhi member at {@code offset} (past those of the other I-21 specs). */
function member(offset: number): string {
  const rows = sqlRows(
    `SELECT emp_num FROM (SELECT e.emp_num, ROW_NUMBER() OVER (ORDER BY e.emp_num) - 1 AS n FROM employee e JOIN org_location l ON l.location_id = e.location_id WHERE l.location_code = 'DH001' AND e.record_status = 'ACTIVE') x WHERE x.n = ${offset}`,
  );
  return String(rows[0]?.[0]);
}

/** A pending HRMS change of the member's field, as the HRMS interface would record it. */
function pendingChange(eis: string, field: string, value: string): void {
  const stamp = `${Date.now()}`.slice(-9);
  sql(
    `INSERT INTO hrms_change_task (task_no, employee_id, pay_effect_field_id, task_source, hrms_request_id, new_value, effective_date, received_at, paying_unit_id, target_payroll_yymm, received_before_freeze, task_status, otp_required) SELECT 'HCA-E2E-U${stamp}', e.employee_id, f.pay_effect_field_id, 'HRMS_PUSH', 'E2E-U-${stamp}', '${value}', CURRENT_DATE, CURRENT_TIMESTAMP, e.location_id, TO_CHAR(ADD_MONTHS(CURRENT_DATE, 1), 'YYYYMM'), 'N', 'PENDING_ORDER_DETAILS', f.otp_required FROM employee e JOIN hrms_pay_effect_field f ON f.field_code = '${field}' WHERE e.emp_num = '${eis}'`,
  );
}

async function raise(page: Page, field: string, eis?: string) {
  const form = page.getByRole('form', { name: t('en', 'i21.upd.raise') });
  // The update requests stay in the closed section of the drawn I-21 (OD-121).
  if (!(await form.isVisible())) await page.getByText(t('en', 'i21.w.closed')).click();
  if (eis) await form.getByLabel(t('en', 'i21.upd.eis')).fill(eis);
  await form.getByLabel(t('en', 'i21.field')).selectOption(field);
  await form.getByLabel(t('en', 'i21.effective')).fill('2026-10-01');
  // E-01 draws its own button (G22); the I-21 desk names the employee and keeps its own.
  await form.getByRole('button', { name: t('en', eis ? 'i21.upd.submit' : 'e01w.hrms.raise') }).click();
}

test.describe.serial('HRMS Update Request (I-21 and eSeva, plan 16c)', () => {
  test(
    'SRS-1-70: a request for a change HRMS already sent links the pending I-21 task',
    { tag: ['@SRS-1-70', '@positive'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const eis = member(27);
      pendingChange(eis, 'EPS_NUMBER', 'EPS-DL-E2E01');
      await workAs(page, 'en', MAKER, maker, 'I-21');
      await raise(page, 'EPS_NUMBER', eis);
      await expect(page.getByRole('status').filter({ hasText: 'HUR-' })).toContainText(
        t('en', 'i21.upd.status.LINKED_EXISTING'),
      );
      const list = page.getByRole('table', { name: t('en', 'i21.upd.list') });
      await expect(list.getByRole('row').nth(1)).toContainText('HCA-E2E-U');
    },
  );

  test(
    'SRS-1-70: without the HRMS interface the fetch is recorded as not configured and can be fetched again',
    { tag: ['@SRS-1-70', '@edge'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      test.skip(process.env.E2E_HRMS_LIVE === '1', 'This stack calls the HRMS mock: the next test runs.');
      await workAs(page, 'en', MAKER, maker, 'I-21');
      await raise(page, 'BASIC_PAY', member(28));
      await expect(page.getByRole('status').filter({ hasText: 'HUR-' })).toContainText(
        t('en', 'i21.upd.status.FETCH_FAILED'),
      );
      const row = page
        .getByRole('table', { name: t('en', 'i21.upd.list') })
        .getByRole('row')
        .nth(1);
      await expect(row).toContainText(t('en', 'i21.upd.msg.HRMS_FETCH_NOT_CONFIGURED'));
      await row.getByRole('button', { name: t('en', 'i21.upd.refetch') }).click();
      await expect(row).toContainText(t('en', 'i21.upd.status.FETCH_FAILED'));
    },
  );

  test(
    'SRS-1-70: with the HRMS interface answering, the fetch opens the I-21 task',
    { tag: ['@SRS-1-70', '@positive'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      test.skip(process.env.E2E_HRMS_LIVE !== '1', 'Needs a stack that calls the HRMS mock.');
      await workAs(page, 'en', MAKER, maker, 'I-21');
      await raise(page, 'BASIC_PAY', member(28));
      await expect(page.getByRole('status').filter({ hasText: 'HUR-' })).toContainText(/HUR-\d{4}-\d+/);
      await expect(page.getByRole('table', { name: t('en', 'i21.upd.list') })).toContainText(member(28));
    },
  );

  test(
    'SRS-1-70: the employee raises an HRMS Update Request in eSeva and sees it in their requests',
    { tag: ['@SRS-1-70', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', 'emp.rsharma@fci-demo.test', { role: 'EMPLOYEE_ESEVA' }, 'E-01');
      await expect(
        page.getByRole('form', { name: t('en', 'i21.upd.raise') }).getByLabel(t('en', 'i21.upd.eis')),
      ).toHaveCount(0);
      await raise(page, 'BASIC_PAY');
      await expect(page.getByRole('status').filter({ hasText: 'HUR-' })).toBeVisible();
      await expect(page.getByRole('table', { name: t('en', 'i21.upd.list') })).toContainText(
        t('en', 'i21.upd.channel.ESEVA'),
      );
    },
  );
});
