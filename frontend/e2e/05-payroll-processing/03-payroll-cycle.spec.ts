import { expect, type Page, test } from '@playwright/test';
import { apiStatus, decide, errorAlert, otpFromLog, shiftPeriod, t, workAs } from '../support/app';
import {
  dbFixtureAvailable,
  lockForFixture,
  lockPriorForFixture,
  NO_DB_FIXTURE,
  sql,
  sqlRows,
} from '../support/db';
import { TEST_CHECKER, TEST_MAKER, TEST_OFFICE, testChecker, testMaker } from '../support/cycle-office';
import { currentCycle, openCycleActions } from '../support/payroll-support';

/**
 * C-02 Payroll cycle through the UI (target DDL v4.0 cycle model): initiate, freeze with OTP, the
 * ACTUAL computation as a C-13 job approved by the checker and run by the batch instance, verify by the
 * checker (never the maker), C-01 naming the gates that hold the lock and the lock refused while they
 * do, then (with the database fixture standing in for the modules that feed the gates) the lock requested
 * with OTP and approved with OTP, and nothing changes it.
 * A locked cycle is never reopened, so this runs once per fresh deployment (skipped when already locked).
 */
// OD-141: in the cycle test office DT001, never DH001 (whose months are the evaluators').
const MAKER = TEST_MAKER;
const CHECKER = TEST_CHECKER;
const maker = testMaker;
const checker = testChecker;

async function cycle(page: Page): Promise<{ status: string | null; lockApprovalStatus: string | null }> {
  return page.evaluate(async () => {
    const me = (await (await fetch('/nginx/fci-payroll/auth/me', { credentials: 'same-origin' })).json()) as {
      period: string;
    };
    const r = await fetch(`/nginx/fci-payroll/v1/cycles/${me.period}`, { credentials: 'same-origin' });
    return (await r.json()) as { status: string | null; lockApprovalStatus: string | null };
  });
}

async function cycleStatus(page: Page): Promise<string | null> {
  return (await cycle(page)).status;
}

test.describe.serial('Payroll cycle: initiate, freeze, compute, verify, lock (C-02)', () => {
  test.beforeAll(async ({ browser }) => {
    const page = await browser.newPage();
    await workAs(page, 'en', MAKER, maker, 'C-02');
    await openCycleActions(page);
    test.skip(
      (await cycleStatus(page)) === 'LOCKED',
      'The cycle of the period is already locked: redeploy for a fresh run.',
    );
    await page.close();
  });

  test(
    'SRS-15-32: a freeze without the acting user’s OTP is refused and the cycle stays initiated',
    { tag: ['@SRS-15-32', '@SRS-G-21', '@SC-05-0032', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'C-02');
      await openCycleActions(page);
      if ((await cycleStatus(page)) === null) {
        lockPriorForFixture((await currentCycle(page)).period, TEST_OFFICE);
        await page.getByRole('button', { name: t('en', 'cyc.action.initiate') }).click();
        await expect(page.getByRole('status')).toContainText(t('en', 'cyc.done.initiate'));
      }
      test.skip((await cycleStatus(page)) !== 'INITIATED', 'an earlier run already froze this cycle');
      const { period } = await currentCycle(page);
      // 428: the OTP step is due; nothing moves until it is answered.
      expect(await apiStatus(page, `/v1/cycles/${period}/freeze`, 'POST', {})).toBe(428);
      expect(await cycleStatus(page)).toBe('INITIATED');
    },
  );

  test(
    'the maker initiates and freezes with an OTP; the checker cannot verify a frozen cycle',
    {
      tag: [
        '@BR-CYC-008',
        '@SRS-G-21',
        '@SC-01-0021',
        '@SC-05-0001',
        '@SC-05-0008',
        '@SC-05-0032',
        '@SC-05-0086',
        '@SC-05-0100',
        '@positive',
      ],
    },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'C-02');
      await openCycleActions(page);
      if ((await cycleStatus(page)) === null) {
        lockPriorForFixture((await currentCycle(page)).period, TEST_OFFICE);
        await page.getByRole('button', { name: t('en', 'cyc.action.initiate') }).click();
        await expect(page.getByRole('status')).toContainText(t('en', 'cyc.done.initiate'));
      }
      if ((await cycleStatus(page)) === 'INITIATED') {
        const since = Date.now();
        await page.getByRole('button', { name: t('en', 'cyc.action.freeze') }).click();
        await page
          .getByRole('textbox', { name: t('en', 'otp.code') })
          .fill(await otpFromLog('CYCLE_FREEZE_LOCK', since));
        await page.getByRole('button', { name: t('en', 'otp.verify') }).click();
        await expect(page.getByRole('status')).toContainText(t('en', 'cyc.done.freeze'));
      }
      // Resumable: a run interrupted earlier may have taken the cycle further already.
      expect(['FROZEN', 'COMPUTED', 'VERIFIED']).toContain(await cycleStatus(page));
      await expect(page.getByRole('button', { name: t('en', 'cyc.action.verify') })).toHaveCount(0);
    },
  );

  // SC-16-0087 (UC scenario of the KB, cited here; outside the e2e_trace review scope).
  test(
    'the computation runs only after the checker approves it, then the cycle is COMPUTED',
    { tag: ['@BR-CYC-008', '@SRS-G-1', '@SRS-NF-2', '@SC-05-0024', '@positive'] },
    async ({ browser }) => {
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'C-02');
      await openCycleActions(page);
      if ((await cycleStatus(page)) === 'FROZEN') {
        await page.getByRole('button', { name: t('en', 'cyc.action.compute') }).click();
        await expect(page).toHaveURL(/s=C-13/);
        const reason = `E2E computation ${Date.now()}`;
        await page.getByRole('tab', { name: t('en', 'c13.tab.submit') }).click();
        await page.locator('#job-type').selectOption('PAYROLL_COMPUTATION');
        await page.locator('#job-reason').fill(reason);
        await page.getByRole('button', { name: t('en', 'c13.submit.review') }).click();
        await page
          .getByRole('dialog')
          .getByRole('button', { name: t('en', 'c13.submit.confirm') })
          .click();
        await expect(page.getByRole('status')).toContainText(/Job \d+ submitted/);
        expect(await cycleStatus(page)).toBe('FROZEN');

        const ddo = await browser.newPage();
        await workAs(ddo, 'en', CHECKER, checker, 'W-01');
        await decide(ddo, new RegExp(reason), 'APPROVE');
        await expect.poll(() => cycleStatus(page), { timeout: 90_000, intervals: [2_000] }).toBe('COMPUTED');
      }
      await page.goto('/payroll/?s=C-02#c02-cycle');
      const title = t('en', 'cyc.resultsActual', ['RUN'])
        .replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
        .replace('RUN', '\\d+');
      await expect(page.getByText(new RegExp(title))).toBeVisible();
      await expect(page.locator('table.t').last().locator('tbody tr').first()).toBeVisible();
    },
  );

  test(
    'the checker verifies; C-01 names the unmet gates and the lock is refused naming them',
    {
      tag: [
        '@SC-05-0008',
        '@SRS-15-11',
        '@SRS-15-14',
        '@SRS-15-18',
        '@BR-CYC-005',
        '@SC-05-0011',
        '@SC-05-0014',
        '@SC-05-0015',
        '@SC-05-0017',
        '@SC-05-0018',
        '@SC-05-0040',
        '@SC-05-0089',
        '@SC-05-0103',
        '@SC-05-0110',
        '@negative',
      ],
    },
    async ({ browser }) => {
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'C-02');
      await openCycleActions(ddo);
      if ((await cycleStatus(ddo)) === 'COMPUTED') {
        await ddo.getByRole('button', { name: t('en', 'cyc.action.verify') }).click();
        await expect(ddo.getByRole('status')).toContainText(t('en', 'cyc.done.verify'));
      }
      expect(await cycleStatus(ddo)).toBe('VERIFIED');
      // The checker holds no C-02 LOCK: the request is the maker's.
      await expect(ddo.getByRole('button', { name: t('en', 'cyc.action.lock') })).toHaveCount(0);

      // C-01: every gate evaluated from its source; the hard gates are marked.
      await ddo.goto('/payroll/?s=C-01');
      // The acceptance detail is an undrawn function, kept in a closed section (WF-217).
      await ddo.getByTestId('c01-decisions').locator('summary').click();
      for (const gate of ['G0', 'G1', 'G2', 'G3', 'G4', 'G5', 'G6']) {
        await expect(ddo.getByRole('cell', { name: new RegExp(`^${gate}\\b`) })).toBeVisible();
      }
      await expect(ddo.getByText(t('en', 'c01.blocking', ['§']).split('§')[0] ?? '')).toBeVisible();
      await expect(ddo.getByRole('cell', { name: 'HRMS_CHANGE_PENDING' })).toBeVisible();

      // No module produces the control totals or statutory status yet: the lock is refused (plan 05 D-1).
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'C-02');
      await openCycleActions(page);
      if ((await cycle(page)).lockApprovalStatus !== 'PENDING_APPROVAL') {
        await page.getByRole('button', { name: t('en', 'cyc.action.lock') }).click();
        await page.getByRole('dialog').getByRole('textbox').fill('E2E: month verified');
        await page.getByRole('dialog').getByRole('button').last().click();
        await expect(errorAlert(page)).toContainText(
          t('en', 'error.CYC-422-GATES', ['§']).split('§')[0] ?? '',
        );
        expect(await cycleStatus(page)).toBe('VERIFIED');
      }
    },
  );

  // SC-16-0161 (UC scenario of the KB, cited here; outside the e2e_trace review scope).
  test(
    'E2E-06 / SRS-15-40: a change received before the freeze and pending on I-21 fails HRMS_CHANGE_PENDING with its count and the I-21 link; one received after the freeze does not count',
    { tag: ['@E2E-06', '@SRS-15-40', '@SRS-1-69', '@negative'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'C-01');
      await ddo.getByTestId('c01-decisions').locator('summary').click();
      const { yymm } = await currentCycle(ddo);
      const cycleId = `(SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm = '${yymm}' AND org_unit_id = (SELECT location_id FROM org_location WHERE location_code = '${TEST_OFFICE}'))`;
      const pending = () =>
        Number(
          sqlRows(
            `SELECT COALESCE(SUM(pending_task_count), 0) FROM v_cycle_pending_hrms_change WHERE cycle_instance_id = ${cycleId}`,
          )[0]?.[0] ?? '0',
        );
      const before = pending();
      const stamp = `${Date.now()}`.slice(-9);
      const task = (n: number, beforeFreeze: 'Y' | 'N') =>
        `INSERT INTO hrms_change_task (task_no, employee_id, pay_effect_field_id, task_source, hrms_request_id, new_value, effective_date, received_at, paying_unit_id, target_payroll_yymm, received_before_freeze, task_status, otp_required) SELECT 'HCA-E2E-06${beforeFreeze}${stamp}', e.employee_id, f.pay_effect_field_id, 'HRMS_PUSH', 'E2E-06-${stamp}-${n}', '10077766${n}555', CURRENT_DATE, CURRENT_TIMESTAMP, l.location_id, '${yymm}', '${beforeFreeze}', 'PENDING_ORDER_DETAILS', f.otp_required FROM org_location l JOIN hrms_pay_effect_field f ON f.field_code = 'UAN' JOIN (SELECT employee_id, location_id, ROW_NUMBER() OVER (PARTITION BY location_id ORDER BY emp_num) - 1 AS n FROM employee WHERE record_status = 'ACTIVE') e ON e.location_id = l.location_id AND e.n = ${20 + n} WHERE l.location_code = '${TEST_OFFICE}'`;
      try {
        sql([task(1, 'Y'), task(2, 'N')].join(';\n'));
        // Only the change received before the freeze holds the lock (the other is paid next month).
        expect(pending()).toBe(before + 1);
        await ddo.reload();
        await ddo.getByTestId('c01-decisions').locator('summary').click();
        const check = ddo
          .getByRole('row')
          .filter({ has: ddo.getByRole('cell', { name: 'HRMS_CHANGE_PENDING' }) });
        await expect(check).toContainText(t('en', 'c01.fail', [before + 1]));
        await expect(check).toContainText(
          `${before + 1} HRMS changes received before the freeze pending on I-21`,
        );
        await expect(check.getByRole('link', { name: 'I-21' })).toHaveAttribute('href', /s=I-21/);
        // The lock is refused while it holds (the gates of the cycle are not all met either).
        await expect(ddo.getByText(t('en', 'c01.blocking', ['§']).split('§')[0] ?? '')).toBeVisible();
      } finally {
        sql(
          `UPDATE hrms_change_task SET task_status = 'CANCELLED' WHERE task_no IN ('HCA-E2E-06Y${stamp}', 'HCA-E2E-06N${stamp}')`,
        );
      }
      expect(pending()).toBe(before);
    },
  );

  test(
    'the checker accepts G5 only with a reason, never a hard gate, and acknowledges a failed Warn check, never a Block one',
    {
      tag: [
        '@SRS-15-15',
        '@SRS-15-16',
        '@SRS-15-39',
        '@SC-05-0016',
        '@SC-05-0039',
        '@SC-05-0090',
        '@negative',
      ],
    },
    async ({ browser }) => {
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'C-01');
      await ddo.getByTestId('c01-decisions').locator('summary').click();
      const gateRow = (code: string) =>
        ddo.getByRole('row').filter({ has: ddo.getByRole('cell', { name: new RegExp(`^${code}\\b`) }) });
      // SRS-15-15: no override for a hard gate, for any role.
      for (const hard of ['G0', 'G1', 'G3', 'G4']) {
        await expect(gateRow(hard)).toContainText(t('en', 'c01.hard'));
        await expect(gateRow(hard).getByRole('button', { name: t('en', 'action.acceptGate') })).toHaveCount(
          0,
        );
      }
      // SRS-15-16 / SRS l.1524 (BMAD TR-010): G5 is accepted by the Checker with a reason, only for named
      // members legitimately excluded. No module feeds the statutory status yet (PENDING 18-g5): the fixture
      // records the four processes complete with one member left uncomputed, as C-07 will.
      const g5 = gateRow('G5');
      const accept = g5.getByRole('button', { name: t('en', 'action.acceptGate') });
      if (await accept.count()) {
        test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
        const { yymm } = await currentCycle(ddo);
        const cycleId = `(SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm = '${yymm}' AND org_unit_id = (SELECT location_id FROM org_location WHERE location_code = '${TEST_OFFICE}'))`;
        sql(
          `INSERT INTO cycle_statutory_status (cycle_instance_id, statutory_process, process_state, members_computed, members_uncomputed, completed_at) SELECT ${cycleId}, p.c, 'COMPLETE', 10, CASE WHEN p.c = 'CPF_PF' THEN 1 ELSE 0 END, CURRENT_TIMESTAMP FROM (SELECT 'CPF_PF' c FROM dual UNION ALL SELECT 'INCOME_TAX' FROM dual UNION ALL SELECT 'PROFESSIONAL_TAX' FROM dual UNION ALL SELECT 'NPS_DEDUCTION' FROM dual) p WHERE NOT EXISTS (SELECT 1 FROM cycle_statutory_status s WHERE s.cycle_instance_id = ${cycleId} AND s.statutory_process = p.c)`,
        );
        const [[eis]] = sqlRows(
          `SELECT MIN(e.emp_num) FROM employee e JOIN org_location l ON l.location_id = e.location_id WHERE l.location_code = '${TEST_OFFICE}' AND e.employee_type = 'STAFF' AND e.record_status = 'ACTIVE'`,
        ) as [[string]];
        // Without a named member the acceptance is refused, naming why.
        await accept.click();
        let dialog = ddo.getByRole('dialog');
        await expect(dialog).toContainText(t('en', 'c01.g5MembersHint'));
        await dialog.getByLabel(t('en', 'common.reason')).fill('E2E: statutory returns reconciled offline');
        await dialog.getByRole('button').last().click();
        await expect(errorAlert(ddo)).toContainText(
          t('en', 'error.CYC-422-GATE-MEMBERS', ['G5', '§']).split('§')[0] ?? '',
        );
        await expect(g5).toContainText(t('en', 'c01.unmet'));
        // Naming the member: accepted, the name recorded with the reason.
        await accept.click();
        dialog = ddo.getByRole('dialog');
        const confirm = dialog.getByRole('button').last();
        await expect(confirm).toBeDisabled();
        await dialog.getByLabel(t('en', 'c01.g5Members')).fill(eis);
        await dialog.getByLabel(t('en', 'common.reason')).fill('E2E: statutory returns reconciled offline');
        await confirm.click();
        // SRS-15-17: the Gate Override & Undo / Reversal Register (newest first) shows the new
        // override at once, without a reload.
        const register = ddo
          .locator('.card')
          .filter({ has: ddo.getByRole('heading', { name: t('en', 'c01w.registerTitle') }) });
        await expect(
          register.getByRole('row').filter({ hasText: 'E2E: statutory returns reconciled offline' }).first(),
        ).toBeVisible();
        await expect(register.getByRole('row').nth(1)).toContainText(t('en', 'c01.reg.gate', ['G5']));
        await expect(g5).toContainText(`Excluded members ${eis}`);
      }
      await expect(g5).toContainText(t('en', 'c01.acceptedBy', [CHECKER]));
      await expect(g5).toContainText('E2E: statutory returns reconciled offline');

      // SRS-15-39: a failed Warn check may be acknowledged; a Block check never.
      const checks = ddo.getByRole('table', { name: t('en', 'c01.checks'), exact: true }).locator('tbody tr');
      const warnFailed = checks.filter({ hasText: t('en', 'c01.severity.WARN') });
      const ack = warnFailed.getByRole('button', { name: t('en', 'action.acknowledge') }).first();
      if (await ack.count()) {
        await ack.click();
        await ddo.getByRole('dialog').getByRole('textbox').fill('E2E: reviewed with the DDO');
        await ddo.getByRole('dialog').getByRole('button').last().click();
        await expect(ddo.getByText(t('en', 'c01.acknowledgedBy', [CHECKER])).first()).toBeVisible();
      }
      const blockChecks = checks.filter({ hasText: t('en', 'c01.severity.BLOCK') });
      expect(await blockChecks.count()).toBeGreaterThan(0);
      await expect(blockChecks.getByRole('button', { name: t('en', 'action.acknowledge') })).toHaveCount(0);

      // The hard gates still hold the lock.
      await expect(ddo.getByText(t('en', 'c01.blocking', ['§']).split('§')[0] ?? '')).toBeVisible();
    },
  );

  test(
    'a locked period for the screens that need one comes from a fixture; a locked cycle offers no action',
    {
      tag: [
        '@BR-CYC-002',
        '@SRS-15-10',
        '@SC-01-0003',
        '@SC-05-0002',
        '@SC-05-0010',
        '@SC-05-0012',
        '@SC-05-0095',
        '@SC-05-0101',
        '@SC-05-0102',
        '@positive',
      ],
    },
    async ({ browser }) => {
      // The lock itself needs every gate met (CycleIT proves it with every source fed). Here the demo
      // members whose computation fails keep G0 unmet, and no module feeds G4 / G5 yet, so the locked
      // period I-20, I-23 and X-06 need is made by the database fixture (plan 05 D-1), never by a
      // weakened rule.
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'C-02');
      await openCycleActions(page);
      const { yymm } = await currentCycle(page);
      if ((await cycleStatus(page)) !== 'LOCKED') {
        lockForFixture(yymm, TEST_OFFICE);
      }
      await page.reload();
      expect(await cycleStatus(page)).toBe('LOCKED');
      await expect(
        page.locator(`table.t button[data-why="${t('en', 'c02.why.locked')}"]`).first(),
      ).toBeAttached();
      // SRS-15-12: Posted to EBS and the supplementary window are flags beside the status. Nothing has
      // posted this period or opened its window, so both read No.
      for (const flag of ['cyc.postedToEbs', 'cyc.supplementaryOpen']) {
        await expect(
          page.getByText(t('en', flag), { exact: true }).locator('xpath=following-sibling::span[1]'),
        ).toHaveText(t('en', 'common.no'));
      }
      // Forward only: nothing left to do on a locked cycle.
      for (const action of ['initiate', 'freeze', 'compute', 'verify', 'lock']) {
        await expect(page.getByRole('button', { name: t('en', `cyc.action.${action}`) })).toHaveCount(0);
      }
      // C-01 offers no acceptance on a locked cycle.
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'C-01');
      await expect(ddo.locator('.pband')).toContainText(t('en', 'd01.state.LOCKED'));
      await expect(ddo.getByRole('button', { name: t('en', 'action.acceptGate') })).toHaveCount(0);
    },
  );

  test(
    'SRS-15-10: a locked cycle is never reopened, not even by a forged transition; only the Maker initiates',
    { tag: ['@SRS-15-10', '@SRS-15-1', '@BR-CYC-002', '@SC-05-0010', '@SC-05-0001', '@negative'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'C-02');
      await openCycleActions(page);
      const { period } = await currentCycle(page);
      expect(await cycleStatus(page)).toBe('LOCKED');
      // Every step the screen no longer offers is refused by the server too (a client error, never
      // a change and never a 5xx), and the cycle stays locked.
      for (const step of ['initiate', 'freeze', 'verify', 'lock']) {
        const status = await apiStatus(page, `/v1/cycles/${period}/${step}`, 'POST', {});
        expect(status, step).toBeGreaterThanOrEqual(400);
        expect(status, step).toBeLessThan(500);
      }
      expect(await cycleStatus(page)).toBe('LOCKED');
      // SRS-15-1: initiating is the Payroll Maker's; the Checker is refused by permission.
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'C-02');
      await openCycleActions(ddo);
      expect(await apiStatus(ddo, `/v1/cycles/${shiftPeriod(period, 1)}/initiate`, 'POST', {})).toBe(403);
    },
  );
});
