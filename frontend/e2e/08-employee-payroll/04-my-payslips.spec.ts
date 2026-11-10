import { expect, test } from '@playwright/test';
import { businessToday, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql } from '../support/db';

/**
 * E-01 My payslips (plan 16 phase 16e-3; SRS-1-10, SRS-17-1, SRS-17-7): the eSeva member sees the
 * payslip of a locked month (a fixture result 27 years before today at the member's office, far behind
 * every demo month, G67) on the drawn Pay Slip document, with earnings and deductions and the PAN
 * masked; an open month has no payslip.
 */
const MEMBER = 'emp.rsharma@fci-demo.test';
const YEAR = Number(businessToday().slice(0, 4)) - 27;
const MM = businessToday().slice(5, 7);
const YYMM = `${YEAR}${MM}`;
const PERIOD = `DATE '${YEAR}-${MM}-01'`;
const START = Number(MM) >= 4 ? YEAR : YEAR - 1;
const FIN_YEAR = `${START}-${String((START + 1) % 100).padStart(2, '0')}`;

function lockedMonthFixture() {
  const self = `(SELECT employee_id FROM app_user WHERE login_id = '${MEMBER}')`;
  const unit = `(SELECT location_id FROM employee WHERE employee_id = ${self})`;
  sql(
    [
      `INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level, fin_year, cycle_status, initiated_by) SELECT '${YYMM}', ${unit}, 'DO', '${FIN_YEAR}', 'COMPUTED', 'e2e.fixture' FROM dual WHERE NOT EXISTS (SELECT 1 FROM payroll_cycle_instance WHERE payroll_yymm = '${YYMM}' AND org_unit_id = ${unit})`,
      `INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id, payroll_type, payroll_yymm, period_date, run_type, gross_earnings, total_deductions, net_pay, result_status, is_locked) SELECT (SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm = '${YYMM}' AND org_unit_id = ${unit}), ${unit}, ${self}, 'STAFF', '${YYMM}', ${PERIOD}, 'ACTUAL', 45000, 5400, 39600, 'CURRENT', 'Y' FROM dual WHERE NOT EXISTS (SELECT 1 FROM payroll_result WHERE employee_id = ${self} AND payroll_yymm = '${YYMM}')`,
      `INSERT INTO payroll_result_component (payroll_result_id, cycle_instance_id, org_unit_id, employee_id, component_id, amount, run_type, is_locked, payroll_yymm, period_date, legacy_pay_code) SELECT r.payroll_result_id, r.cycle_instance_id, r.org_unit_id, r.employee_id, c.component_id, CASE c.legacy_pay_code WHEN 101 THEN 30000 WHEN 111 THEN 15000 ELSE -5400 END, 'ACTUAL', 'Y', '${YYMM}', ${PERIOD}, c.legacy_pay_code FROM payroll_result r JOIN pay_component c ON c.legacy_pay_code IN (101, 111, 904) WHERE r.employee_id = ${self} AND r.payroll_yymm = '${YYMM}' AND NOT EXISTS (SELECT 1 FROM payroll_result_component x WHERE x.payroll_result_id = r.payroll_result_id)`,
    ].join(';\n'),
  );
}

test.describe.serial('My payslips (E-01, plan 16e-3)', () => {
  test(
    'SRS-17-1: the member opens the payslip of a locked month with earnings, deductions and the PAN masked',
    { tag: ['@SRS-17-1', '@SRS-1-10', '@SRS-17-7', '@positive'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      lockedMonthFixture();
      await workAs(page, 'en', MEMBER, { role: 'EMPLOYEE_ESEVA' }, 'E-01');
      const months = page.getByRole('table', { name: t('en', 'e01p.months') });
      await months.getByRole('button', { name: new RegExp(String(YEAR)) }).click();
      const slip = page.getByRole('dialog', { name: new RegExp(String(YEAR)) });
      const lines = slip.getByRole('table', { name: t('en', 'e01w.doc.lines') });
      await expect(lines).toContainText('Basic Pay');
      await expect(lines).toContainText('5,400');
      await expect(slip).toContainText('39,600');
      // R-PII (PiiMasks, BMAD TR-035): the PAN and the bank account both show their last four only.
      const masked = (key: string) =>
        slip.locator('th', { hasText: t('en', key) }).locator('xpath=following-sibling::td[1]');
      await expect(masked('e01p.pan')).toHaveText(/^\*{6}[A-Z0-9]{4}$/);
      await expect(masked('e01w.doc.bank')).toHaveText(/^\*+\d{4}$/);
      // OD-144: Download PDF gives the server's PDF of the same locked month.
      const download = page.waitForEvent('download');
      await slip.getByRole('button', { name: t('en', 'e01w.doc.download') }).click();
      expect((await download).suggestedFilename()).toBe(`payslip-${YYMM}.pdf`);
    },
  );
});
