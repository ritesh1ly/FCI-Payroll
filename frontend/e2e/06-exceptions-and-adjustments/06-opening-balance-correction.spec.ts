import { expect, type Page, test } from '@playwright/test';
import { apiMultipart, apiStatus, decide, errorAlert, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql, sqlRows } from '../support/db';
import { apiAll, expectToast, fillOrder, row } from '../support/payroll-support';

/**
 * I-22 PF / CPF Opening Balance Correction through the UI (docs/plan/07-cpf.md phase 07d;
 * SRS-6-43, SRS-6-44): Manager (CPF) raises a correction with its evidence, AGM (CPF) checks it
 * with OTP and the Headquarters CPF approver approves it with OTP, three different users; the
 * corrected opening shows on the screen and in the register. The screen's own refusals, a return
 * by the AGM, and who may do what.
 *
 * The demo stack has no CPF ledger yet (the contribution posting arrives in phase 07e), so each test
 * opens a migrated ledger of its own through the database fixture, in a financial year far in the
 * future picked from the minutes since 2026: re-runnable, and no earlier run's correction is in the way.
 */
const MANAGER = 'cpf.manager.north@fci-demo.test';
const AGM = 'agm.cpf.north@fci-demo.test';
const HQ = 'cpf.approver.hq@fci-demo.test';
const manager = { role: 'CPF_MANAGER', office: 'NA11' };
const agm = { role: 'AGM_CPF', office: 'NA11' };
const hq = { role: 'HQ_CPF_APPROVER', office: 'HQ001' };
const cpfDivision = { role: 'CPF_DIVISION', office: 'NA11' };
const EIS = '104882';
const OTP = 'CPF_OPENING_BALANCE_CORRECTION';

const BASE = 2101 + 3 * (Math.floor((Date.now() - Date.UTC(2026, 0, 1)) / 60_000) % 2_600);
const RUN = `E2E ${Date.now()}`;

/** The as-at date of a test's own ledger year ({@code offset} 0, 1, 2 …). */
function asAt(offset: number): string {
  return `${BASE + offset}-04-01`;
}

const memberId = (eis: string) =>
  `(SELECT e.employee_id FROM employee e JOIN org_zone z ON z.zone_id = e.parent_zone_id WHERE e.emp_num = '${eis}' AND z.zone_code = 'NA11')`;

/** Opens a migrated ledger of the member for the financial year of {@code day}. */
function openLedger(day: string, employee: string, employer: string, vpf: string, eis = EIS) {
  const first = Number(day.slice(0, 4));
  const year = `${first}-${String((first + 1) % 100).padStart(2, '0')}`;
  const member = memberId(eis);
  sql(
    `INSERT INTO employee_pf_header (employee_id, fin_year, zone_id, opening_balance_employee, opening_balance_employer, opening_balance_vpf, closing_balance_employee, closing_balance_employer, closing_balance_vpf, opening_balance_source) SELECT ${member}, '${year}', (SELECT zone_id FROM org_zone WHERE zone_code = 'NA11'), ${employee}, ${employer}, ${vpf}, ${employee}, ${employer}, ${vpf}, 'MIGRATION' FROM dual WHERE NOT EXISTS (SELECT 1 FROM employee_pf_header WHERE employee_id = ${member} AND fin_year = '${year}')`,
  );
}

async function showMember(page: Page, day: string, eis = EIS) {
  await page.getByLabel(t('en', 'i22.by.eis')).fill(eis);
  await page.getByLabel(t('en', 'i22.asAt')).fill(day);
  await page.getByRole('button', { name: t('en', 'i22.show') }).click();
  await expect(page.getByLabel(t('en', 'i22.current'))).toContainText(eis);
}

/** Raises a correction of the employee part through the form; returns its request number. */
async function raise(page: Page, day: string, employee: string, eis = EIS): Promise<string> {
  await workAs(page, 'en', MANAGER, manager, 'I-22');
  await showMember(page, day, eis);
  await page.getByRole('button', { name: t('en', 'action.newCorrection') }).click();
  await page.getByLabel(t('en', 'i22.correctedOf', [t('en', 'i22.part.EMPLOYEE')])).fill(employee);
  await page.getByLabel(t('en', 'i22.reason')).selectOption('CLERICAL_ERROR');
  // SRS l.2240: the Effective Date for Interest is the Maker's own, never pre-filled.
  await expect(page.getByLabel(t('en', 'i22.interestDate'))).toHaveValue('');
  await page.getByLabel(t('en', 'i22.interestDate')).fill(day);
  await page.getByLabel(t('en', 'i22.remarks')).fill(`${RUN} clerical error in the migrated balance`);
  await fillOrder(page);
  await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
  const toast = await expectToast(page, /OB-\d{4}-\d{6}/);
  return /OB-\d{4}-\d{6}/.exec(toast)?.[0] ?? '';
}

test.describe.serial('I-22 PF / CPF opening-balance correction', () => {
  test(
    'SRS-6-43: Manager (CPF) raises, AGM (CPF) and the HQ CPF approver each approve with OTP; the corrected opening shows',
    {
      tag: [
        '@SRS-6-43',
        '@SRS-6-44',
        '@BR-CPF-027',
        '@SC-07-0032',
        '@SC-07-0067',
        '@SC-07-0072',
        '@SC-07-0074',
        '@SC-07-0075',
        '@SC-07-0076',
        '@SC-07-0078',
        '@SC-07-0079',
        '@SC-07-0080',
        '@SC-07-0081',
        '@SC-07-0082',
        '@positive',
      ],
    },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const day = asAt(0);
      openLedger(day, '1000.00', '500.00', '0');
      const ref = await raise(page, day, '1100.00');
      await expect(row(page, ref)).toContainText(t('en', 'i22.status.PENDING_AGM_CPF'));
      await expect(row(page, ref)).toContainText('+100.00');

      await workAs(page, 'en', AGM, agm, 'W-01');
      // Tier 1 of two (SRS-6-43): the item goes on to the HQ CPF approver.
      await decide(page, new RegExp(ref), 'APPROVE', '', OTP, 'PENDING_APPROVAL');
      await workAs(page, 'en', HQ, hq, 'W-01');
      await decide(page, new RegExp(ref), 'APPROVE', '', OTP);

      await workAs(page, 'en', MANAGER, manager, 'I-22');
      const applied = row(page, ref);
      await expect(applied).toContainText(t('en', 'i22.status.APPLIED'));
      await expect(applied).toContainText(AGM);
      await expect(applied).toContainText(HQ);
      await expect(applied.getByRole('link', { name: t('en', 'i22.evidence') })).toBeVisible();
      await showMember(page, day);
      await expect(page.getByLabel(t('en', 'i22.current'))).toContainText('1,100');
    },
  );

  test(
    'SRS-6-44: the form names a correction that changes nothing or goes below zero; the server refuses one without evidence',
    {
      tag: [
        '@SRS-6-44',
        '@SRS-6-57',
        '@BR-CPF-025',
        '@SC-07-0033',
        '@SC-07-0068',
        '@SC-07-0077',
        '@SC-07-0108',
        '@negative',
      ],
    },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const day = asAt(1);
      openLedger(day, '1000.00', '500.00', '10.00');
      await workAs(page, 'en', MANAGER, manager, 'I-22');
      await showMember(page, day);
      await page.getByRole('button', { name: t('en', 'action.newCorrection') }).click();
      const problems = page.getByRole('list', { name: t('en', 'i22.problems') });
      await expect(problems).toContainText(t('en', 'error.CPF-422-NO-CHANGE'));
      await expect(problems).toContainText(t('en', 'error.CPF-422-EVIDENCE'));
      await page.getByLabel(t('en', 'i22.correctedOf', [t('en', 'i22.part.VPF')])).fill('-0.01');
      await expect(problems).toContainText(t('en', 'i22.problem.negative'));
      await expect(page.getByRole('button', { name: t('en', 'common.submitForApproval') })).toBeDisabled();
      await page.getByRole('button', { name: t('en', 'common.cancel') }).click();
      // A request the screen never sends: the only part "corrected" to its current amount.
      const fields = {
        eis: EIS,
        asAtDate: day,
        employee: '1000.00',
        reasonCode: 'CLERICAL_ERROR',
        interestEffectiveDate: day,
        remarks: `${RUN} forged`,
        documentType: 'OTHER',
        orderDate: '2026-10-01',
        issuingAuthority: 'CPF Division NZ',
      };
      const pdf = { name: 'evidence.pdf', mimeType: 'application/pdf', content: '%PDF-1.7 evidence' };
      expect(await apiMultipart(page, '/v1/cpf/opening-balance-corrections', fields, pdf)).toEqual({
        status: 422,
        errorCode: 'CPF-422-NO-CHANGE',
      });
    },
  );

  test(
    'SRS-6-44: a correction returned by AGM (CPF) applies nothing and shows the reason; the maker never decides it',
    { tag: ['@SRS-6-44', '@SRS-G-1', '@SC-07-0070', '@SC-07-0071', '@negative'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const day = asAt(2);
      openLedger(day, '1000.00', '500.00', '0');
      const ref = await raise(page, day, '900.00');
      // The register pages (SRS-NF-3): read it whole.
      const register = await apiAll<{ ref: string; approvalId: number }>(
        page,
        '/v1/cpf/opening-balance-corrections',
      );
      const approvalId = register.find((c) => c.ref === ref)?.approvalId ?? 0;
      expect(
        await apiStatus(page, `/v1/approvals/${approvalId}/decision`, 'POST', { decision: 'APPROVE' }),
      ).toBe(403);

      await workAs(page, 'en', AGM, agm, 'W-01');
      await decide(page, new RegExp(ref), 'RETURN', `${RUN} evidence does not match`);
      await workAs(page, 'en', MANAGER, manager, 'I-22');
      const returned = row(page, ref);
      await expect(returned).toContainText(t('en', 'i22.status.RETURNED'));
      await expect(returned).toContainText(`${RUN} evidence does not match`);
      await showMember(page, day);
      await expect(page.getByLabel(t('en', 'i22.current'))).toContainText('1,000');
      await expect(page.getByRole('button', { name: t('en', 'action.newCorrection') })).toBeEnabled();
    },
  );

  test(
    'SRS-6-44: lowering a balance after a sanctioned withdrawal is blocked for the CPF Division, never sent to the approvers',
    { tag: ['@SRS-6-44', '@SC-07-0069', '@edge'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      // A member of its own: a sanction dated in this year would block every later run's lowering
      // of the shared member (the rule is "sanctioned on or after the as-at date").
      const other = '105461';
      const day = asAt(3);
      openLedger(day, '1000.00', '500.00', '0', other);
      // A withdrawal already paid out after the as-at date (the sanction chain's outcome, PAID so
      // that no open advance is left for the member).
      sql(
        `INSERT INTO pf_sanction (employee_id, sanction_type, requested_amount, sanctioned_amount, sanction_date, workflow_stage) VALUES (${memberId(other)}, 'ADVANCE', 200, 200, DATE '${day.slice(0, 4)}-09-01', 'PAID')`,
      );
      const ref = await raise(page, day, '900.00', other);
      const blocked = row(page, ref);
      await expect(blocked).toContainText(t('en', 'i22.status.BLOCKED_CPF_DIVISION'));
      // Blocked counts as open: no second correction of the member until the CPF Division decides.
      const register = await apiAll<{ ref: string; approvalId: number | null }>(
        page,
        '/v1/cpf/opening-balance-corrections',
      );
      expect(register.find((c) => c.ref === ref)?.approvalId ?? null).toBeNull();
      await showMember(page, day, other);
      await expect(page.getByRole('button', { name: t('en', 'action.newCorrection') })).toBeDisabled();
    },
  );

  test(
    'I-22 field table: a member without a CPF ledger for the year has no opening to correct',
    { tag: ['@SRS-6-43', '@SC-07-0074', '@SC-07-0075', '@negative'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      // A year no ledger was opened for.
      await workAs(page, 'en', MANAGER, manager, 'I-22');
      await page.getByLabel(t('en', 'i22.by.eis')).fill(EIS);
      await page.getByLabel(t('en', 'i22.asAt')).fill(asAt(9));
      await page.getByRole('button', { name: t('en', 'i22.show') }).click();
      await expect(errorAlert(page)).toContainText(t('en', 'error.CPF-422-NO-LEDGER'));
      await expect(page.getByRole('button', { name: t('en', 'action.newCorrection') })).toHaveCount(0);
    },
  );

  test(
    'I-22 field table: the reason is one of the permitted list, remarks 1 to 1000 characters, the interest date required',
    {
      tag: [
        '@SRS-6-43',
        '@SRS-6-44',
        '@SRS-6-57',
        '@BR-CPF-026',
        '@SC-07-0033',
        '@SC-07-0078',
        '@SC-07-0080',
        '@SC-07-0081',
        '@SC-07-0108',
        '@negative',
      ],
    },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const day = asAt(4);
      openLedger(day, '1000.00', '500.00', '0');
      await workAs(page, 'en', MANAGER, manager, 'I-22');
      const valid: Record<string, string> = {
        eis: EIS,
        asAtDate: day,
        employee: '1100.00',
        reasonCode: 'CLERICAL_ERROR',
        interestEffectiveDate: day,
        remarks: `${RUN} field rules`,
        documentType: 'OTHER',
        orderDate: '2026-10-01',
        issuingAuthority: 'CPF Division NZ',
      };
      const pdf = { name: 'evidence.pdf', mimeType: 'application/pdf', content: '%PDF-1.7 evidence' };
      const send = (over: Record<string, string | null>) => {
        // A field given as null is left out of the form.
        const fields = Object.fromEntries(
          Object.entries({ ...valid, ...over }).filter((e): e is [string, string] => e[1] !== null),
        );
        return apiMultipart(page, '/v1/cpf/opening-balance-corrections', fields, pdf);
      };
      expect(await send({ reasonCode: 'NOT_A_REASON' })).toEqual({
        status: 422,
        errorCode: 'CPF-422-REASON-CODE',
      });
      // SRS-6-44 / SRS-6-57: no part of a balance ever goes below zero, not even by a forged request
      // (the form refuses it first); exactly zero is a balance, below it is not.
      expect(await send({ employee: '-0.01' })).toEqual({ status: 422, errorCode: 'CPF-422-NEGATIVE' });
      expect((await send({ remarks: '   ' })).status).toBe(400);
      expect((await send({ remarks: 'x'.repeat(1001) })).status).toBe(400);
      expect((await send({ interestEffectiveDate: null })).status).toBe(400);
      // Nothing was raised: the member's register holds no correction for the year.
      const register = await apiAll<{ asAtDate: string }>(page, '/v1/cpf/opening-balance-corrections');
      expect(register.some((c) => c.asAtDate === day)).toBe(false);
    },
  );

  test(
    'SRS-22-21: the CPF Division reads I-22 in Hindi and raises nothing; a Payroll Maker reads it (SRS 14.4) and raises nothing',
    { tag: ['@SC-07-0032', '@SRS-22-21', '@SRS-6-43', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'hi', 'cpf.north@fci-demo.test', cpfDivision, 'I-22');
      await expect(page.getByRole('heading', { level: 1, name: t('hi', 'screen.I-22') })).toBeVisible();
      await expect(page.getByText(t('hi', 'i22.register'))).toBeVisible();
      await expect(page.getByRole('button', { name: t('hi', 'action.newCorrection') })).toHaveCount(0);
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'W-02',
      );
      // SRS 14.4 lists the maker on I-22 (OD-125); the register is bounded by the data scope and the
      // correction belongs to the CPF roles of the route (WF-271).
      expect(await apiStatus(page, '/v1/cpf/opening-balance-corrections')).toBe(200);
      await page.goto('/payroll/?s=I-22');
      await expect(page.getByRole('button', { name: t('en', 'action.newCorrection') })).toHaveCount(0);
    },
  );

  test(
    'SRS l.2234: the member is found by CPF code or by UAN, and the UAN shows read-only; an unknown UAN is not found',
    { tag: ['@SRS-6-43', '@SC-07-0074', '@positive'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const [cpfCode = '', uan = ''] =
        sqlRows(`SELECT cpf_code, uan_number FROM employee WHERE emp_num = '${EIS}'`)[0] ?? [];
      expect(cpfCode).not.toBe('');
      const day = asAt(0);
      await workAs(page, 'en', MANAGER, manager, 'I-22');
      await page.getByLabel(t('en', 'i22.findBy')).selectOption('cpfCode');
      await page.getByLabel(t('en', 'i22.by.cpfCode')).fill(cpfCode);
      await page.getByLabel(t('en', 'i22.asAt')).fill(day);
      await page.getByRole('button', { name: t('en', 'i22.show') }).click();
      await expect(page.getByLabel(t('en', 'i22.current'))).toContainText(EIS);
      if (uan !== '') {
        await expect(page.getByLabel(t('en', 'i22.current'))).toContainText(uan);
        await page.getByLabel(t('en', 'i22.findBy')).selectOption('uan');
        await page.getByLabel(t('en', 'i22.by.uan')).fill(uan);
        await page.getByRole('button', { name: t('en', 'i22.show') }).click();
        await expect(page.getByLabel(t('en', 'i22.current'))).toContainText(EIS);
      }
      // A UAN no member holds: not found, nothing shown.
      await page.getByLabel(t('en', 'i22.findBy')).selectOption('uan');
      await page.getByLabel(t('en', 'i22.by.uan')).fill('999999999999');
      await page.getByRole('button', { name: t('en', 'i22.show') }).click();
      await expect(errorAlert(page)).toBeVisible();
      // A malformed UAN cannot be asked.
      await page.getByLabel(t('en', 'i22.by.uan')).fill('12345');
      await expect(page.getByRole('button', { name: t('en', 'i22.show') })).toBeDisabled();
    },
  );
});
