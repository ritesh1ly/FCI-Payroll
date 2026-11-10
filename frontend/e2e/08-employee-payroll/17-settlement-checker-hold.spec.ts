import { expect, type Browser, type Page, test } from '@playwright/test';
import { apiProblem, decide, onSomePage, t, workAs } from '../support/app';
import { dbFixtureAvailable, lockPriorForFixture, NO_DB_FIXTURE, sql, sqlRows } from '../support/db';
import { apiJson, expectToast } from '../support/payroll-support';

/**
 * O-05 Full & Final Settlement while the Payroll Checker holds it (SRS-10-14, SRS-10-30; US-GAP-218,
 * US-GAP-219 and the Wave A / Wave C review fixes). Each test opens a case of its own, as an approved
 * separation would (the HRMS interface is module 23): a retired Delhi member separated on the last
 * day of the previous month with that month paid, the establishment no-dues still awaited.
 *
 * - The Maker computes and submits with the no-dues outstanding; while the case waits for the
 *   Checker it can be neither recomputed nor resubmitted, and the approval-held hint shows.
 * - Only the Checker records the named, reasoned exclusion of the outstanding clearance; a return in
 *   W-01 restores it to Raised (the exclusion falls with the approval it accompanied).
 * - An answer Establishment / HR records while the clearance is excluded survives the return:
 *   Received with the answer's remark.
 * - The Checker's approval is refused when the net payable is no longer the one submitted
 *   (FNF-409-CHANGED) and the item stays pending; approved only with the clearance settled.
 */
const MAKER = 'maker.delhi@fci-demo.test';
const CHECKER = 'ddo.delhi@fci-demo.test';
const HR = 'hr.delhi@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };
const checker = { role: 'PAYROLL_CHECKER', office: 'DH001' };
const hr = { role: 'ESTABLISHMENT_HR', office: 'DH001' };
const NO_DUES = 'ESTABLISHMENT_NO_DUES';

/** Today in India as YYYY-MM-01 (the period of the session). */
function currentPeriod(): string {
  return new Date(Date.now() + 5.5 * 3_600_000).toISOString().slice(0, 7);
}

/**
 * A settlement case of its own: a separated Delhi member (EIS, PAN and CPF code unused), the case
 * at stage 2 with every clearance received but the establishment no-dues (Raised), an EL balance
 * and the separation month paid by a regular run. Returns the case reference.
 */
function openCase(): string {
  let eis = '';
  for (let i = 0; i < 20 && eis === ''; i++) {
    const candidate = `17${String(Math.floor(Math.random() * 10_000)).padStart(4, '0')}`;
    const [[n]] = sqlRows(`SELECT COUNT(*) AS n FROM employee WHERE emp_num = '${candidate}'`) as [[string]];
    if (n === '0') eis = candidate;
  }
  const digits = eis.slice(2);
  const reference = `FNF-E2E-${eis}`;
  const separated = `ADD_MONTHS(LAST_DAY(TO_DATE(TO_CHAR(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Kolkata', 'YYYY-MM-DD'), 'YYYY-MM-DD')), -1)`;
  const self = `(SELECT employee_id FROM employee WHERE emp_num = '${eis}')`;
  const unit = `(SELECT location_id FROM org_location WHERE location_code = 'DH001')`;
  lockPriorForFixture(currentPeriod(), 'DH001');
  const prior = sqlRows(`SELECT TO_CHAR(${separated}, 'YYYYMM') AS m FROM dual`)[0]?.[0] ?? '';
  const cycle = `(SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm = '${prior}' AND org_unit_id = ${unit})`;
  const first = `DATE '${prior.slice(0, 4)}-${prior.slice(4)}-01'`;
  sql(
    [
      `INSERT INTO employee (emp_num, first_name, last_name, birth_date, gender, comp_joining_date, employee_type, emp_lbr_flag, pan_number, designation_id, grade_id, location_id, department_id, band_id, pay_pattern, pay_pattern_source, provident_fund_type, cpf_code, cpf_code_origin, parent_zone_id, employment_status, record_status, current_basic, mobile_number, email_address, bank_account_number, bank_branch_id, record_origin) VALUES ('${eis}', 'Settle', 'Retiree', DATE '1966-01-10', 'M', DATE '1996-07-01', 'STAFF', 'N', 'ABCPS${digits}Q', (SELECT designation_id FROM org_designation WHERE designation_code = 'MGR'), (SELECT grade_id FROM org_grade WHERE grade_code = 'E-4'), ${unit}, (SELECT department_id FROM org_department WHERE department_code = 'ESTB'), (SELECT band_id FROM pay_scale_band WHERE band_code = 'E-4'), 'IDA', 'MIGRATED', 'CPF', 'CPF/${eis}', 'MIGRATED', (SELECT zone_id FROM org_zone WHERE zone_code = 'NA11'), 'SEPARATED', 'ACTIVE', 62400, '90000${digits}0', 'settle.${eis}@fci-demo.test', '00000${digits}0', (SELECT branch_id FROM bank_branch WHERE ifsc_code = 'SBIN0DEMO01'), 'MIGRATED')`,
      `INSERT INTO settlement_case (case_reference, employee_id, separation_type, separation_reason, separation_date, separation_source, case_stage, cpf_final_status_info, benefits_held_flag) SELECT '${reference}', employee_id, 'RETIREMENT', 'Superannuation (E2E)', ${separated}, 'HRMS', 2, 'NOT_APPLIED', 'N' FROM employee WHERE emp_num = '${eis}'`,
      `INSERT INTO settlement_clearance (settlement_case_id, clearance_type, raised_at, received_at, clearance_status, remark) SELECT c.settlement_case_id, v.c0, CURRENT_TIMESTAMP, CASE WHEN v.c1 = 'RECEIVED' THEN CURRENT_TIMESTAMP END, v.c1, v.c2 FROM settlement_case c JOIN (SELECT 'LOANS_ADVANCES' c0, 'RECEIVED' c1, 'Nil' c2 FROM dual UNION ALL SELECT 'PENALTY_RECOVERY', 'RECEIVED', 'Nil' FROM dual UNION ALL SELECT 'ALLOWANCES', 'RECEIVED', 'Nil' FROM dual UNION ALL SELECT 'CPF_FINAL_STATUS', 'RECEIVED', 'Not applied for' FROM dual UNION ALL SELECT '${NO_DUES}', 'RAISED', NULL FROM dual) v ON 1 = 1 WHERE c.case_reference = '${reference}'`,
      `INSERT INTO final_settlement (settlement_case_id, employee_id, separation_type, separation_date, separation_source) SELECT c.settlement_case_id, c.employee_id, c.separation_type, c.separation_date, 'HRMS' FROM settlement_case c WHERE c.case_reference = '${reference}'`,
      `INSERT INTO leave_balance_snapshot (employee_id, leave_type_code, balance_days, as_of_date, balance_source) SELECT ${self}, 'EL', 30, ${separated}, 'INTF' FROM dual`,
      `INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id, payroll_type, payroll_yymm, period_date, run_type, gross_earnings, total_deductions, net_pay, result_status) SELECT ${cycle}, ${unit}, ${self}, 'STAFF', '${prior}', ${first}, 'ACTUAL', 75000, 0, 75000, 'CURRENT' FROM dual`,
      `INSERT INTO payroll_result_component (payroll_result_id, cycle_instance_id, org_unit_id, employee_id, component_id, amount, run_type, payroll_yymm, period_date) SELECT r.payroll_result_id, r.cycle_instance_id, r.org_unit_id, r.employee_id, p.component_id, CASE p.legacy_pay_code WHEN 101 THEN 50000 ELSE 25000 END, 'ACTUAL', r.payroll_yymm, r.period_date FROM payroll_result r JOIN pay_component p ON p.legacy_pay_code IN (101, 111) WHERE r.employee_id = ${self} AND r.payroll_yymm = '${prior}'`,
    ].join(';\n'),
  );
  return reference;
}

async function open(page: Page, reference: string) {
  const row = page
    .getByRole('table', { name: t('en', 'screen.O-05') })
    .locator(`tr[data-reference="${reference}"]`);
  await expect(row).toBeVisible();
  await row.getByRole('button', { name: t('en', 'common.view') }).click();
  await expect(page.getByText(new RegExp(reference)).first()).toBeVisible();
  // The functions the drawing leaves out stand behind the closed Case actions section.
  await page.getByTestId('o05-case-actions').locator('summary').click();
}

/** The no-dues line of the drawn checklist (OD-121): its title, its answer and its status pill. */
function noDuesLine(page: Page) {
  return page
    .getByTestId('o05-clearances')
    .locator('.lst')
    .filter({ has: page.locator('b', { hasText: new RegExp(`^${t('en', `o05.clearance.${NO_DUES}`)}`) }) });
}

/** The Maker computes and submits the case; returns the Maker's page on the open case. */
async function computeAndSubmit(browser: Browser, reference: string): Promise<Page> {
  const page = await browser.newPage();
  await workAs(page, 'en', MAKER, maker, 'O-05');
  await open(page, reference);
  await page.getByRole('button', { name: t('en', 'action.o05.compute') }).click();
  await expectToast(page, t('en', 'o05.computed'));
  await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
  await expectToast(page, t('en', 'o05.submitted'));
  return page;
}

/** The Checker records the exclusion of the no-dues on O-05 with its reason. */
async function exclude(browser: Browser, reference: string, reason: string) {
  const page = await browser.newPage();
  await workAs(page, 'en', CHECKER, checker, 'O-05');
  await open(page, reference);
  await expect(page.getByText(t('en', 'o05.approvalHeld'))).toBeVisible();
  // Recompute and submit are the Maker's: the Checker's buttons say so, with the reason Wireframes v6.1 draws (OD-121).
  const compute = page.getByRole('button', { name: t('en', 'action.o05.compute') });
  await expect(compute).toBeDisabled();
  await expect(compute).toHaveAttribute('title', t('en', 'o05.why.makerOnly'));
  await page.getByRole('button', { name: t('en', 'action.o05.exclude') }).click();
  const form = page.getByLabel(t('en', 'action.o05.exclude'), { exact: true }).last();
  const record = page.getByRole('button', { name: t('en', 'action.o05.recordExclusion') });
  // SRS-10-14: a named reason - none, or blanks only, and the exclusion cannot be recorded.
  await expect(record).toBeDisabled();
  await form.getByLabel(t('en', 'o05.exclusionReason')).fill('   ');
  await expect(record).toBeDisabled();
  await form.getByLabel(t('en', 'o05.exclusionReason')).fill(reason);
  await record.click();
  await expectToast(page, t('en', 'o05.exclusionSent'));
  await expect(noDuesLine(page)).toContainText(t('en', 'o05.clearanceStatus.EXCLUDED'));
  await page.close();
}

test.describe.serial('O-05 settlement held by the Payroll Checker (SRS-10-14, SRS-10-30)', () => {
  test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);

  test(
    'SRS-10-30: while the case waits for the Checker it is neither recomputed nor resubmitted; a return restores the excluded clearance',
    { tag: ['@SRS-10-30', '@SRS-10-14', '@US-GAP-218', '@US-GAP-219', '@negative'] },
    async ({ browser }) => {
      const reference = openCase();
      const page = await computeAndSubmit(browser, reference);
      // SRS-10-14: the clearance outstanding held neither the computation nor the submission ...
      await expect(page.getByText(t('en', 'o05.approvalHeld'))).toBeVisible();
      // ... but the item submitted stands for the settlement until the Checker returns it.
      const compute = page.getByRole('button', { name: t('en', 'action.o05.compute') });
      await expect(compute).toBeDisabled();
      await expect(compute).toHaveAttribute('title', t('en', 'o05.why.compute'));
      const submit = page.getByRole('button', { name: t('en', 'common.submitForApproval') });
      await expect(submit).toBeDisabled();
      await expect(submit).toHaveAttribute('title', t('en', 'o05.why.submit'));
      // The exclusion is never the Maker's.
      const excludeButton = page.getByRole('button', { name: t('en', 'action.o05.exclude') });
      await expect(excludeButton).toBeDisabled();
      await expect(excludeButton).toHaveAttribute('title', t('en', 'reason.checkerOnly'));
      // A forged recomputation is refused by the server as well.
      const id = await apiJson<{ items: { caseId: number; caseReference: string }[] }>(
        page,
        '/v1/settlement-cases?size=100',
      ).then((b) => b.items.find((c) => c.caseReference === reference)?.caseId);
      expect(await apiProblem(page, `/v1/settlement-cases/${id}/computations`, 'POST', {})).toEqual({
        status: 409,
        errorCode: 'FNF-409-APPROVAL',
      });

      await exclude(browser, reference, 'Quarters never allotted (E2E)');

      // Returned in W-01: the exclusion falls with the approval it accompanied.
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(
        ddo,
        new RegExp(`Full & final settlement ${reference}`),
        'RETURN',
        'Recheck the quarters (E2E)',
      );
      await page.reload();
      await open(page, reference);
      await expect(noDuesLine(page)).toContainText(t('en', 'o05.clearanceStatus.RAISED'));
      await expect(page.getByRole('button', { name: t('en', 'action.o05.compute') })).toBeEnabled();
    },
  );

  test(
    'SRS-10-14: an answer Establishment / HR records while the clearance is excluded is kept after the return',
    { tag: ['@SRS-10-14', '@SRS-10-27', '@edge'] },
    async ({ browser }) => {
      const reference = openCase();
      const page = await computeAndSubmit(browser, reference);
      await exclude(browser, reference, 'Awaited from the estate office (E2E)');

      // The excluded clearance is no longer outstanding: HR's screen offers no no-dues entry, and
      // the answer HR's office sends meanwhile is recorded beside the exclusion.
      const desk = await browser.newPage();
      await workAs(desk, 'en', HR, hr, 'O-05');
      await open(desk, reference);
      await expect(desk.getByRole('button', { name: t('en', 'action.o05.noDues') })).toHaveCount(0);
      const id = await apiJson<{ items: { caseId: number; caseReference: string }[] }>(
        desk,
        '/v1/settlement-cases?size=100',
      ).then((b) => b.items.find((c) => c.caseReference === reference)?.caseId);
      await apiJson(desk, `/v1/settlement-cases/${id}/no-dues`, 'POST', {
        dues: 0,
        remark: 'No quarters dues (E2E)',
      });
      await desk.reload();
      await open(desk, reference);
      await expect(noDuesLine(desk)).toContainText(t('en', 'o05.clearanceStatus.EXCLUDED'));

      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, new RegExp(`Full & final settlement ${reference}`), 'RETURN', 'Answer arrived (E2E)');
      await page.reload();
      await open(page, reference);
      await expect(noDuesLine(page)).toContainText(t('en', 'o05.clearanceStatus.RECEIVED'));
      await expect(page.getByText(t('en', 'o05.approvalHeld'))).toHaveCount(0);
    },
  );

  test(
    'SRS-10-30: the approval is refused when the net payable is no longer the one submitted (FNF-409-CHANGED); approved once resubmitted',
    { tag: ['@SRS-10-30', '@SRS-10-14', '@negative'] },
    async ({ browser }) => {
      const reference = openCase();
      const page = await computeAndSubmit(browser, reference);
      await exclude(browser, reference, 'Quarters never allotted (E2E)');
      const caseRow = `(SELECT settlement_case_id FROM settlement_case WHERE case_reference = '${reference}')`;
      // The settlement changes behind the submitted item (stale data): the Checker did not see it.
      sql(`UPDATE final_settlement SET net_payable = net_payable + 1 WHERE settlement_case_id = ${caseRow}`);
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      // As drawn (OD-121): the item may be on a later page; View opens its detail, which carries Approve, and the
      // reason dialog opens over it (as decide() does).
      const item = await onSomePage(
        ddo,
        ddo.getByRole('row', { name: new RegExp(`Full & final settlement ${reference}`) }).first(),
      );
      await item.getByRole('button', { name: t('en', 'wf.view') }).click();
      await ddo
        .getByRole('dialog')
        .first()
        .getByRole('button', { name: t('en', 'w01.APPROVE') })
        .click();
      await ddo
        .getByRole('dialog')
        .last()
        .getByRole('button', { name: t('en', 'w01.APPROVE') })
        .click();
      // Refused before any OTP is asked, and the item stays pending.
      // W-01 shows a refused decision as its notice (toast).
      await expectToast(ddo, /The settlement was submitted at a net payable of 75000 and now shows 75001/);
      await expect(ddo.getByRole('textbox', { name: t('en', 'otp.code') })).toHaveCount(0);
      expect(
        sqlRows(
          `SELECT status FROM approval_queue WHERE entity_key = '${reference}' ORDER BY approval_id DESC FETCH FIRST 1 ROWS ONLY`,
        )[0]?.[0],
      ).toBe('PENDING_APPROVAL');

      // Returned, recomputed and resubmitted at the true net, then approved with the OTP.
      sql(`UPDATE final_settlement SET net_payable = net_payable - 1 WHERE settlement_case_id = ${caseRow}`);
      await ddo.reload();
      await decide(ddo, new RegExp(`Full & final settlement ${reference}`), 'RETURN', 'Net changed (E2E)');
      await page.reload();
      await open(page, reference);
      await page.getByRole('button', { name: t('en', 'action.o05.compute') }).click();
      await expectToast(page, t('en', 'o05.computed'));
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expectToast(page, t('en', 'o05.submitted'));
      // The no-dues is outstanding again: approval refused until it is excluded anew.
      await exclude(browser, reference, 'Quarters never allotted (E2E)');
      await ddo.reload();
      await decide(ddo, new RegExp(`Full & final settlement ${reference}`), 'APPROVE', '', 'FNF_APPROVAL');
    },
  );

  test(
    'SRS-10-19 / 10-29 (E2E-12, E2E-13): no-dues typed, computed with each recovery period, approved with OTP, released by Finance and closed read-only',
    { tag: ['@SRS-10-19', '@SRS-10-29', '@SRS-10-21', '@SRS-10-27', '@SRS-10-4', '@positive'] },
    async ({ browser }) => {
      const reference = openCase();
      // Establishment / HR: a nil clearance is typed as 0 (SRS-10-27), never assumed.
      const desk = await browser.newPage();
      await workAs(desk, 'en', HR, hr, 'O-05');
      await open(desk, reference);
      const record = desk.getByRole('button', { name: t('en', 'action.o05.noDues') });
      await desk.getByLabel(t('en', 'common.reason')).fill('No quarters dues (E2E)');
      await expect(record).toBeDisabled();
      await desk.getByLabel(t('en', 'o05.dues')).fill('0');
      await record.click();
      await expectToast(desk, t('en', 'o05.noDuesRecorded'));
      await desk.close();

      const page = await computeAndSubmit(browser, reference);
      await expect(page.getByText(t('en', 'o05.invoice.NOT_RAISED'))).toBeVisible();
      // The Checker decides in the Approvals Inbox, linked from the case (SRS-10-30).
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'O-05');
      await open(ddo, reference);
      await expect(ddo.getByRole('link', { name: t('en', 'o05.decideInInbox') })).toBeVisible();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, new RegExp(`Full & final settlement ${reference}`), 'APPROVE', '', 'FNF_APPROVAL');

      // Finance initiates the release; the Maker cannot (SRS-10-19).
      await page.reload();
      await open(page, reference);
      await expect(page.getByRole('button', { name: t('en', 'action.o05.release') })).toHaveAttribute(
        'title',
        t('en', 'reason.financeOnly'),
      );
      const finance = await browser.newPage();
      await workAs(finance, 'en', 'finance.hq@fci-demo.test', { role: 'FINANCE', office: 'HQ001' }, 'O-05');
      await open(finance, reference);
      await finance.getByRole('button', { name: t('en', 'action.o05.release') }).click();
      await expectToast(finance, t('en', 'o05.releaseSent'));
      await ddo.reload();
      await decide(ddo, new RegExp(reference), 'APPROVE', '', 'DISBURSEMENT_RELEASE');

      // SRS-10-29: posted and closed; the case is read-only.
      await page.reload();
      await open(page, reference);
      await expect(
        page.getByText(t('en', 'o05.step', [8, t('en', 'o05.stepText.CLOSED')])).first(),
      ).toBeVisible();
      await expect(page.getByRole('button', { name: t('en', 'action.o05.compute') })).toBeDisabled();
      const id = await apiJson<{ items: { caseId: number; caseReference: string }[] }>(
        page,
        '/v1/settlement-cases?size=100',
      ).then((b) => b.items.find((c) => c.caseReference === reference)?.caseId);
      expect(await apiProblem(page, `/v1/settlement-cases/${id}/computations`, 'POST', {})).toEqual({
        status: 409,
        errorCode: 'FNF-409-STAGE',
      });
      // SRS-10-21: the CPF final payment is never a recovery source, whatever is sent.
      const refused = await apiProblem(
        page,
        `/v1/settlement-cases/${id}/recoveries/1/source?source=CPF_FINAL`,
        'PUT',
        {},
      );
      expect([400, 415]).toContain(refused.status);
    },
  );

  // SC-11-0067 (scenario of a module after 07: cited, not tagged)
  test(
    'SRS-10-6 / 10-23 (E2E-13): a net recovery is flagged; its release first raises the further approval, then waits for the credit memo',
    { tag: ['@SRS-10-6', '@SRS-10-23', '@E2E-13', '@negative'] },
    async ({ browser }) => {
      const reference = openCase();
      // Establishment / HR itemises dues above the payroll dues (75,000): a net recovery of 25,000.
      const desk = await browser.newPage();
      await workAs(desk, 'en', HR, hr, 'O-05');
      await open(desk, reference);
      await desk.getByLabel(t('en', 'common.reason')).fill('Arrears of rent (E2E)');
      await desk.getByLabel(t('en', 'o05.dues')).fill('100000');
      await desk.getByRole('button', { name: t('en', 'action.o05.noDues') }).click();
      await expectToast(desk, t('en', 'o05.noDuesRecorded'));
      await desk.close();

      const page = await computeAndSubmit(browser, reference);
      await expect(page.getByText(t('en', 'o05.netRecovery'))).toBeVisible();
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, new RegExp(`Full & final settlement ${reference}`), 'APPROVE', '', 'FNF_APPROVAL');

      // Finance's release of a net recovery raises the further approval (X-01), not the posting.
      const finance = await browser.newPage();
      await workAs(finance, 'en', 'finance.hq@fci-demo.test', { role: 'FINANCE', office: 'HQ001' }, 'O-05');
      await open(finance, reference);
      await finance.getByRole('button', { name: t('en', 'action.o05.release') }).click();
      await expectToast(finance, t('en', 'o05.releaseSent'));
      expect(
        sqlRows(
          `SELECT status FROM approval_queue WHERE entity_name = 'final_settlement_net_recovery' AND entity_key = '${reference}'`,
        ),
      ).toEqual([['PENDING_APPROVAL']]);
      const hqChecker = await browser.newPage();
      await workAs(
        hqChecker,
        'en',
        'checker.hq@fci-demo.test',
        { role: 'PAYROLL_CHECKER', office: 'HQ001' },
        'W-01',
      );
      await decide(hqChecker, /Net recovery of 25000 from the member/, 'APPROVE');

      // Approved, the posting still waits for the credit memo to APPS (module 23): refused, not posted.
      const id = await apiJson<{ items: { caseId: number; caseReference: string }[] }>(
        finance,
        '/v1/settlement-cases?size=100',
      ).then((b) => b.items.find((c) => c.caseReference === reference)?.caseId);
      expect(await apiProblem(finance, `/v1/settlement-cases/${id}/releases`, 'POST', {})).toEqual({
        status: 409,
        errorCode: 'FNF-409-APPROVAL',
      });
      expect(
        sqlRows(
          `SELECT s.settlement_status FROM final_settlement s JOIN settlement_case c ON c.settlement_case_id = s.settlement_case_id WHERE c.case_reference = '${reference}'`,
        ),
      ).toEqual([['APPROVED']]);
    },
  );
});
