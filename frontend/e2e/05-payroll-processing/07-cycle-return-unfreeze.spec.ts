import { expect, type Page, test } from '@playwright/test';
import { apiProblem, decide, errorAlert, otpFromLog, t, workAs } from '../support/app';
import { dbFixtureAvailable, lockPriorForFixture, NO_DB_FIXTURE, sql, sqlRows } from '../support/db';
import { currentCycle, openCycleActions } from '../support/payroll-support';

/**
 * C-02 corrections before lock (plan 05 phase 05d) at the Chennai DO (CH001, parent RO-TN), after
 * 06-cycle-lease left its cycle INITIATED: the maker freezes with OTP; a computed run (database fixture
 * standing in for C-13, which the lock spec already proves) makes it COMPUTED; the Payroll Checker returns
 * it with a reason (SRS-15-30) and withdraws the return raised in error, then returns it again; the maker
 * asks for an un-freeze (SRS-15-31), the unit's checker checks it and the Payroll Checker of the Regional
 * Office approves it with the OTP (SRS-G-21, plan 05 D-8): the cycle is INITIATED and must be frozen again.
 * Re-runnable: it ends where it started.
 */
const MAKER = 'maker.chennai@fci-demo.test';
const CHECKER = 'ddo.chennai@fci-demo.test';
const REGION = 'pc.rotn@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'CH001' };
const checker = { role: 'PAYROLL_CHECKER', office: 'CH001' };
const region = { role: 'PAYROLL_CHECKER', office: 'RO-TN' };

const instance = (yymm: string) =>
  `(SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm = '${yymm}' AND org_unit_id = (SELECT location_id FROM org_location WHERE location_code = 'CH001'))`;

async function status(page: Page): Promise<string | null> {
  return (await currentCycle(page)).status;
}

/** A completed Actual run of record with three results, as a C-13 computation leaves it. */
function computedRun(yymm: string) {
  const cycle = instance(yymm);
  sql(
    [
      `INSERT INTO computation_run (cycle_instance_id, run_type, run_no, payroll_type, run_status, started_by, started_at, completed_at, parameter_hash, parameters_json, rule_version_set_hash, result_count) SELECT ${cycle}, 'ACTUAL', COALESCE(MAX(run_no), 0) + 1, 'STAFF', 'COMPLETE', 'e2e.fixture', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'e2e', '{}', 'e2e', 3 FROM computation_run WHERE cycle_instance_id = ${cycle}`,
      `INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id, payroll_type, payroll_yymm, period_date, run_type, run_id, gross_earnings, total_deductions, net_pay, result_status, is_locked) SELECT ${cycle}, (SELECT location_id FROM org_location WHERE location_code = 'CH001'), e.employee_id, 'STAFF', '${yymm}', TO_DATE('${yymm}01', 'YYYYMMDD'), 'ACTUAL', (SELECT MAX(run_id) FROM computation_run WHERE cycle_instance_id = ${cycle}), 30000, 0, 30000, 'CURRENT', 'N' FROM employee e ORDER BY e.emp_num OFFSET 40 ROWS FETCH NEXT 3 ROWS ONLY`,
      `UPDATE payroll_cycle_instance SET cycle_status = 'COMPUTED', current_run_id = (SELECT MAX(run_id) FROM computation_run WHERE cycle_instance_id = ${cycle}) WHERE cycle_instance_id = ${cycle} AND cycle_status = 'FROZEN'`,
      // As CycleRuns.close does for a run of record: an open return of an earlier run is recomputed (an aborted
      // earlier run of this spec may have left one open).
      `UPDATE cycle_return SET return_status = 'RECOMPUTED', recompute_run_id = (SELECT MAX(run_id) FROM computation_run WHERE cycle_instance_id = ${cycle}) WHERE cycle_instance_id = ${cycle} AND return_status = 'OPEN' AND run_id < (SELECT MAX(run_id) FROM computation_run WHERE cycle_instance_id = ${cycle})`,
    ].join(';\n'),
  );
}

async function returnWhole(page: Page, reason: string) {
  await page.getByRole('button', { name: t('en', 'cyc.action.return') }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel(t('en', 'common.reason')).fill(reason);
  await dialog.getByRole('button', { name: t('en', 'cyc.action.return') }).click();
  await expect(page.getByRole('status')).toContainText(t('en', 'cyc.done.return'));
}

/** The maker freezes the INITIATED cycle with the OTP. */
async function freeze(page: Page) {
  const since = Date.now();
  await page.getByRole('button', { name: t('en', 'cyc.action.freeze') }).click();
  await page
    .getByRole('textbox', { name: t('en', 'otp.code') })
    .fill(await otpFromLog('CYCLE_FREEZE_LOCK', since));
  await page.getByRole('button', { name: t('en', 'otp.verify') }).click();
  await expect(page.getByRole('status')).toContainText(t('en', 'cyc.done.freeze'));
}

/** The return dialog with a scope other than the whole instance. */
async function returnScoped(page: Page, scope: 'ORG_UNIT' | 'MEMBERS', value: string, reason: string) {
  await page.getByRole('button', { name: t('en', 'cyc.action.return') }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('radio', { name: t('en', `cyc.returnScope.${scope}`) }).check();
  await dialog
    .getByLabel(t('en', scope === 'MEMBERS' ? 'cyc.returnMembers' : 'cyc.returnOffice'))
    .fill(value);
  await dialog.getByLabel(t('en', 'common.reason')).fill(reason);
  await dialog.getByRole('button', { name: t('en', 'cyc.action.return') }).click();
}

/** The cycle back to INITIATED as the first test leaves it: un-freeze checked by the unit, approved by the RO. */
async function unfreeze(browser: import('@playwright/test').Browser, page: Page, ddo: Page, why: string) {
  await page.reload();
  await page.getByRole('button', { name: t('en', 'cyc.action.unfreeze') }).click();
  await page.getByRole('dialog').getByRole('textbox').fill(why);
  await page
    .getByRole('dialog')
    .getByRole('button', { name: t('en', 'common.submit') })
    .click();
  await expect(page.getByRole('status')).toContainText(t('en', 'cyc.done.unfreeze'));
  await ddo.goto('/payroll/?s=W-01');
  await decide(ddo, new RegExp(why), 'APPROVE', '', undefined, 'PENDING_APPROVAL');
  const ro = await browser.newPage();
  await workAs(ro, 'en', REGION, region, 'W-01');
  await decide(ro, new RegExp(why), 'APPROVE', '', 'CYCLE_UNFREEZE_STUCK_RELEASE');
  await expect.poll(() => status(page), { timeout: 20_000 }).toBe('INITIATED');
}

test.describe.serial('Cycle return and un-freeze before lock (C-02, plan 05d)', () => {
  test(
    'SRS-15-30 / SRS-15-31: the checker returns and withdraws; the maker asks an un-freeze, checked by the unit and approved with OTP by the Regional Office',
    { tag: ['@SRS-15-30', '@SRS-15-31', '@BR-CYC-016', '@BR-CYC-017', '@SRS-G-21', '@positive'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'C-02');
      await openCycleActions(page);
      if ((await status(page)) === null) {
        lockPriorForFixture((await currentCycle(page)).period, 'CH001');
        await page.getByRole('button', { name: t('en', 'cyc.action.initiate') }).click();
        await expect(page.getByRole('status')).toContainText(t('en', 'cyc.done.initiate'));
      }
      test.skip((await status(page)) !== 'INITIATED', 'the Chennai cycle moved past INITIATED: redeploy');
      const { yymm } = await currentCycle(page);
      const since = Date.now();
      await page.getByRole('button', { name: t('en', 'cyc.action.freeze') }).click();
      await page
        .getByRole('textbox', { name: t('en', 'otp.code') })
        .fill(await otpFromLog('CYCLE_FREEZE_LOCK', since));
      await page.getByRole('button', { name: t('en', 'otp.verify') }).click();
      await expect(page.getByRole('status')).toContainText(t('en', 'cyc.done.freeze'));
      computedRun(yymm);
      await page.reload();
      // The maker never returns a cycle.
      await expect(page.getByRole('button', { name: t('en', 'cyc.action.unfreeze') })).toBeEnabled();
      await expect(page.getByRole('button', { name: t('en', 'cyc.action.return') })).toHaveCount(0);

      // The checker returns it with a reason, then withdraws the return raised in error.
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'C-02');
      await openCycleActions(ddo);
      await returnWhole(ddo, `E2E wrong month ${Date.now()}`);
      await expect(ddo.getByTestId('cycle-return')).toContainText(t('en', 'cyc.returnScope.INSTANCE'));
      expect(await status(ddo)).toBe('FROZEN');
      await ddo.getByRole('button', { name: t('en', 'cyc.action.withdraw') }).click();
      await ddo.getByRole('dialog').getByRole('textbox').fill('Returned the wrong month');
      await ddo
        .getByRole('dialog')
        .getByRole('button', { name: t('en', 'common.submit') })
        .click();
      await expect(ddo.getByRole('status')).toContainText(t('en', 'cyc.done.withdraw'));
      expect(await status(ddo)).toBe('COMPUTED');
      await expect(ddo.getByTestId('cycle-return')).toHaveCount(0);

      // SRS-15-28 / SRS l.1734 (C-02 review item 1): Verify waits while another action holds the lease, and
      // while a re-computation is still RUNNING (its lease lapsed), whose results would replace the ones
      // verified; the server refuses the same. Fixtures: a lease row, then a RUNNING run that then fails.
      const verify = ddo.getByRole('button', { name: t('en', 'cyc.action.verify') });
      await expect(verify).toBeEnabled();
      const holder = 'e2e.verify.lease@fci-demo.test';
      sql(
        `INSERT INTO cycle_lease (cycle_instance_id, lease_action, held_by, acquired_at, heartbeat_at) VALUES (${instance(yymm)}, 'COMPUTE', '${holder}', CURRENT_TIMESTAMP, DATEADD('MINUTE', 60, CURRENT_TIMESTAMP))`,
      );
      await ddo.reload();
      await expect(verify).toBeDisabled();
      await expect(verify).toHaveAttribute('title', new RegExp(holder.replace(/\./g, '\\.')));
      const period = (await currentCycle(ddo)).period;
      expect(await apiProblem(ddo, `/v1/cycles/${period}/verify`, 'POST', {})).toEqual({
        status: 409,
        errorCode: 'CYC-409-LEASE',
      });
      sql(
        [
          `DELETE FROM cycle_lease WHERE cycle_instance_id = ${instance(yymm)}`,
          `INSERT INTO computation_run (cycle_instance_id, run_type, run_no, payroll_type, run_status, started_by, started_at, parameter_hash, parameters_json, rule_version_set_hash) SELECT ${instance(yymm)}, 'ACTUAL', COALESCE(MAX(run_no), 0) + 1, 'STAFF', 'RUNNING', 'e2e.fixture', CURRENT_TIMESTAMP, 'e2e', '{}', 'e2e' FROM computation_run WHERE cycle_instance_id = ${instance(yymm)}`,
        ].join(';\n'),
      );
      await ddo.reload();
      await expect(verify).toBeDisabled();
      await expect(verify).toHaveAttribute('title', new RegExp(t('en', 'error.CYC-409-RUNNING', ['\\d+'])));
      expect(await apiProblem(ddo, `/v1/cycles/${period}/verify`, 'POST', {})).toEqual({
        status: 409,
        errorCode: 'CYC-409-RUNNING',
      });
      expect(await status(ddo)).toBe('COMPUTED');
      // The run fails: Verify is open again (on the run of record), nothing was verified meanwhile.
      sql(
        `UPDATE computation_run SET run_status = 'FAILED', completed_at = CURRENT_TIMESTAMP WHERE cycle_instance_id = ${instance(yymm)} AND run_status = 'RUNNING'`,
      );
      await ddo.reload();
      await expect(verify).toBeEnabled();

      // Returned for good: the maker sees why and that a recompute is due.
      const reason = `E2E totals do not tally ${Date.now()}`;
      await returnWhole(ddo, reason);
      await page.reload();
      await expect(page.getByTestId('cycle-return')).toContainText(reason);
      await expect(page.getByTestId('cycle-return')).toContainText(t('en', 'cyc.recomputeHint'));

      // The maker asks for an un-freeze; a second request waits for the first.
      const why = `E2E late increment order ${Date.now()}`;
      await page.getByRole('button', { name: t('en', 'cyc.action.unfreeze') }).click();
      await page.getByRole('dialog').getByRole('textbox').fill(why);
      await page
        .getByRole('dialog')
        .getByRole('button', { name: t('en', 'common.submit') })
        .click();
      await expect(page.getByRole('status')).toContainText(t('en', 'cyc.done.unfreeze'));
      await expect(page.getByRole('button', { name: t('en', 'cyc.action.unfreeze') })).toBeDisabled();
      await expect(page.getByRole('button', { name: t('en', 'cyc.action.unfreeze') })).toHaveAttribute(
        'title',
        t('en', 'cyc.blocked.unfreezePending'),
      );

      // Level 2: the unit's checker checks it (no OTP); level 3: the Regional Office, with OTP.
      await ddo.goto('/payroll/?s=W-01');
      await decide(ddo, new RegExp(why), 'APPROVE', '', undefined, 'PENDING_APPROVAL');
      expect(await status(page)).toBe('FROZEN');
      const ro = await browser.newPage();
      await workAs(ro, 'en', REGION, region, 'W-01');
      await decide(ro, new RegExp(why), 'APPROVE', '', 'CYCLE_UNFREEZE_STUCK_RELEASE');

      await expect.poll(() => status(page), { timeout: 20_000 }).toBe('INITIATED');
      await page.reload();
      await expect(page.getByRole('button', { name: t('en', 'cyc.action.freeze') })).toBeEnabled();
      await expect(page.getByRole('button', { name: t('en', 'cyc.action.unfreeze') })).toHaveCount(0);
    },
  );

  test(
    'E2E-04 / SRS-15-30: a return names members or an office; the run is superseded and the cycle is verified again only after a fresh computation',
    {
      tag: ['@E2E-04', '@SRS-15-30', '@BR-CYC-016', '@SRS-G-1', '@negative'],
    },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'C-02');
      await openCycleActions(page);
      test.skip(
        (await status(page)) !== 'INITIATED',
        'the Chennai cycle is not INITIATED: run the spec from its start',
      );
      const { yymm, period } = await currentCycle(page);
      await freeze(page);
      computedRun(yymm);
      // The run's id, read once: a return clears the cycle's current run (the next computation sets it).
      const run = sqlRows(
        `SELECT current_run_id FROM payroll_cycle_instance WHERE cycle_instance_id = ${instance(yymm)}`,
      )[0]?.[0];
      expect(run).toBeTruthy();
      const [[eis]] = sqlRows(
        `SELECT e.emp_num FROM payroll_result r JOIN employee e ON e.employee_id = r.employee_id WHERE r.run_id = ${run} ORDER BY e.emp_num FETCH FIRST 1 ROWS ONLY`,
      ) as [[string]];

      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'C-02');
      await openCycleActions(ddo);
      expect(await status(ddo)).toBe('COMPUTED');
      // A member the run did not compute is refused, naming the scope; nothing is returned.
      await returnScoped(ddo, 'MEMBERS', '999999', `E2E unknown member ${Date.now()}`);
      await expect(errorAlert(ddo)).toContainText(t('en', 'error.CYC-422-SCOPE', ['§']).split('§')[0] ?? '');
      expect(await status(ddo)).toBe('COMPUTED');
      // So is an office that is not beneath the unit.
      await ddo.reload();
      await returnScoped(ddo, 'ORG_UNIT', 'DH001', `E2E office outside ${Date.now()}`);
      await expect(errorAlert(ddo)).toContainText(t('en', 'error.CYC-422-SCOPE', ['§']).split('§')[0] ?? '');

      // Named members: the return is kept with its scope; the run's results are superseded, never changed.
      const reason = `E2E member pay wrong ${Date.now()}`;
      await ddo.reload();
      await returnScoped(ddo, 'MEMBERS', eis, reason);
      await expect(ddo.getByRole('status')).toContainText(t('en', 'cyc.done.return'));
      await expect(ddo.getByTestId('cycle-return')).toContainText(t('en', 'cyc.returnedMembers', [eis]));
      expect(await status(ddo)).toBe('FROZEN');
      const statuses = sqlRows(`SELECT DISTINCT result_status FROM payroll_result WHERE run_id = ${run}`).map(
        (r) => r[0],
      );
      expect(statuses).toEqual(['SUPERSEDED']);
      expect(
        sqlRows(`SELECT COUNT(*) FROM payroll_result WHERE run_id = ${run} AND net_pay = 30000`)[0]?.[0],
      ).toBe('3');

      // The maker sees the scope and that a recompute is due.
      await page.reload();
      await expect(page.getByTestId('cycle-return')).toContainText(reason);
      await expect(page.getByTestId('cycle-return')).toContainText(t('en', 'cyc.recomputeHint'));
      // Resubmission without a fresh computation is refused: no Verify, and the forged call names the state.
      await ddo.reload();
      await expect(ddo.getByRole('button', { name: t('en', 'cyc.action.verify') })).toHaveCount(0);
      expect(await apiProblem(ddo, `/v1/cycles/${period}/verify`, 'POST', {})).toEqual({
        status: 409,
        errorCode: 'CYC-409-STATE',
      });

      // A fresh computation answers the return: COMPUTED again, the return closed, Verify offered.
      computedRun(yymm);
      expect(
        sqlRows(
          `SELECT return_status FROM cycle_return WHERE cycle_instance_id = ${instance(yymm)} AND reason = '${reason}'`,
        )[0]?.[0],
      ).toBe('RECOMPUTED');
      await ddo.reload();
      expect(await status(ddo)).toBe('COMPUTED');
      await expect(ddo.getByTestId('cycle-return')).toHaveCount(0);
      await expect(ddo.getByRole('button', { name: t('en', 'cyc.action.verify') })).toBeEnabled();

      // Re-runnable: back to INITIATED through a whole return and the controlled un-freeze.
      await returnWhole(ddo, `E2E reset ${Date.now()}`);
      await unfreeze(browser, page, ddo, `E2E reset un-freeze ${Date.now()}`);
    },
  );
});
