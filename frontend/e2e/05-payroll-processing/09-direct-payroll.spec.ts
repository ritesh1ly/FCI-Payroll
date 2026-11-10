import { expect, test } from '@playwright/test';
import { businessToday, decide, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql, sqlRows } from '../support/db';
import { TEST_CHECKER, TEST_MAKER, TEST_OFFICE, testChecker, testMaker } from '../support/cycle-office';
import { currentCycle } from '../support/payroll-support';

/**
 * I-19 Maintain Direct Payroll (plan 05 phase 05g; SRS-15-2, SRS-15-19, SRS-15-22, BR-CYC-018) at the cycle test office (DT001, OD-141),
 * after 03-payroll-cycle locked its current period: the MDP window of that period is opened today by the
 * database fixture (M-10 dates of the demo calendar lie elsewhere), the Payroll Maker captures a Labour Welfare
 * Fund deduction for a member of the locked run, the Payroll Checker approves it on W-01, and the register shows
 * it approved with both names. Re-runnable: each run captures a new entry.
 */
const MAKER = TEST_MAKER;
const CHECKER = TEST_CHECKER;
const maker = testMaker;
const checker = testChecker;

test.describe.serial('Direct payroll (I-19, plan 05g)', () => {
  // SC-05-0118 (UC scenario of the KB, cited here; outside the e2e_trace review scope).
  test(
    'SRS-15-19: the maker captures a permitted deduction in the MDP window and the checker approves it',
    { tag: ['@SRS-15-19', '@SRS-15-22', '@BR-CYC-018', '@positive'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'I-19');
      const { yymm, status } = await currentCycle(page);
      test.skip(status !== 'LOCKED', 'needs the locked current period that 03-payroll-cycle leaves');
      sql(
        `UPDATE payroll_calendar SET mdp_window_from = DATE '${businessToday(-1)}', mdp_window_to = DATE '${businessToday(1)}' WHERE payroll_yymm = '${yymm}'`,
      );
      const [member] = sqlRows(
        `SELECT e.emp_num FROM payroll_result r JOIN employee e ON e.employee_id = r.employee_id JOIN payroll_cycle_instance c ON c.cycle_instance_id = r.cycle_instance_id WHERE c.payroll_yymm = '${yymm}' AND c.org_unit_id = (SELECT location_id FROM org_location WHERE location_code = '${TEST_OFFICE}') AND r.is_locked = 'Y' ORDER BY e.emp_num FETCH FIRST 1 ROWS ONLY`,
      )[0] as [string];
      await page.reload();

      await page.getByRole('button', { name: t('en', 'i19.newEntry') }).click();
      const dialog = page.getByRole('dialog');
      await dialog.getByLabel(t('en', 'i19.eis')).fill(member);
      await dialog.getByLabel(t('en', 'i19.head')).selectOption('348');
      await dialog.getByLabel(t('en', 'i19.amount')).fill('25.00');
      const reason = `E2E LWF ${Date.now()}`;
      await dialog.getByLabel(t('en', 'common.reason')).fill(reason);
      await dialog.getByRole('button', { name: t('en', 'common.submit') }).click();
      await expect(page.locator('.fl-toast')).toContainText(t('en', 'i19.captured'));
      const row = page.getByRole('row', { name: new RegExp(reason) });
      await expect(row).toContainText(t('en', 'i19.status.ENTERED'));

      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, new RegExp(reason), 'APPROVE');

      await page.reload();
      await expect(page.getByRole('row', { name: new RegExp(reason) })).toContainText(
        t('en', 'i19.status.APPROVED'),
      );
      await expect(page.getByRole('row', { name: new RegExp(reason) })).toContainText(CHECKER);
    },
  );
});
