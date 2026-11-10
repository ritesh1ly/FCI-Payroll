import { expect, type Page, test } from '@playwright/test';
import { apiStatus, decide, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql, sqlRows } from '../support/db';
import { apiJson, expectToast, row } from '../support/payroll-support';

/**
 * C-12 CPF Interest Run through the UI (docs/plan/07-cpf.md phase 07e; SRS-6-45, SRS-6-46): the
 * Manager (CPF) of the North Zone runs and re-runs a year's interest, each run in the audit list
 * with its reason; the report of members not yet calculated (the method waits for Q-072); a run
 * refused for an undeclared rate; the freeze requested by the Manager (CPF) and approved by the AGM
 * (CPF) without OTP, after which nothing runs; who may do what.
 *
 * The demo stack has no CPF ledger or rates for a year yet, so each test opens them through the
 * database fixture in a financial year far in the future, picked per run (a freeze is final).
 */
const MANAGER = 'cpf.manager.north@fci-demo.test';
const AGM = 'agm.cpf.north@fci-demo.test';
const manager = { role: 'CPF_MANAGER', office: 'NA11' };
const agm = { role: 'AGM_CPF', office: 'NA11' };
const EIS = '104882';

const BASE = 2300 + 3 * (Math.floor((Date.now() - Date.UTC(2026, 0, 1)) / 60_000) % 2_500);
const RUN = `E2E ${Date.now()}`;

function finYear(offset: number): string {
  const first = BASE + offset;
  return `${first}-${String((first + 1) % 100).padStart(2, '0')}`;
}

/** A North Zone ledger of the member for {@code year} and its FCI rate, declared or not. */
function openYear(year: string, declared: boolean) {
  const member = `(SELECT e.employee_id FROM employee e JOIN org_zone z ON z.zone_id = e.parent_zone_id WHERE e.emp_num = '${EIS}' AND z.zone_code = 'NA11')`;
  sql(
    [
      `INSERT INTO employee_pf_header (employee_id, fin_year, zone_id, opening_balance_employee, closing_balance_employee, opening_balance_source) SELECT ${member}, '${year}', (SELECT zone_id FROM org_zone WHERE zone_code = 'NA11'), 1000, 1000, 'MIGRATION' FROM dual WHERE NOT EXISTS (SELECT 1 FROM employee_pf_header WHERE employee_id = ${member} AND fin_year = '${year}')`,
      `INSERT INTO pf_interest_rate (fin_year, fund_type, rate_source, member_population, rate_percent, applies_from, is_declared) SELECT '${year}', 'CPF', 'FCI_TRUST', 'ACTIVE_AND_DEPUTED', 8.25, DATE '${year.slice(0, 4)}-04-01', '${declared ? 'Y' : 'N'}' FROM dual WHERE NOT EXISTS (SELECT 1 FROM pf_interest_rate WHERE fin_year = '${year}' AND fund_type = 'CPF' AND rate_source = 'FCI_TRUST')`,
    ].join(';\n'),
  );
}

async function showYear(page: Page, year: string) {
  const field = page.getByLabel(t('en', 'c12.finYear'));
  await field.fill(year);
  await expect(page.getByText(t('en', 'c12.zone', ['NA11']))).toBeVisible();
}

/** SRS l.2358: "Calculate Interest", or "Re-Run Interest" once the year has a run. */
function runButton(locale: 'en' | 'hi'): RegExp {
  return new RegExp(`^(${t(locale, 'action.runInterest')}|${t(locale, 'action.reRunInterest')})$`);
}

async function runInterest(page: Page, reason: string, eis?: string) {
  await page.getByRole('button', { name: runButton('en') }).click();
  if (eis) {
    await page.getByLabel(t('en', 'c12.scope.INDIVIDUAL')).check();
    await page.getByLabel(t('en', 'c12.eis')).fill(eis);
  }
  await page.getByLabel(t('en', 'c12.reason'), { exact: true }).fill(reason);
  await page.getByRole('button', { name: t('en', 'c12.runNow'), exact: true }).click();
}

test.describe.serial('C-12 CPF interest run', () => {
  test(
    'SRS-6-45: the Manager (CPF) runs and re-runs the year, each run audited; the members not yet calculated are listed',
    { tag: ['@SRS-6-45', '@SRS-6-11', '@SC-07-0034', '@SC-07-0010', '@positive'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const year = finYear(0);
      openYear(year, true);
      await workAs(page, 'en', MANAGER, manager, 'C-12');
      await showYear(page, year);
      // Stated in the drawn run audit and in the register under it.
      await expect(page.getByText(t('en', 'c12.noRuns')).first()).toBeVisible();
      await runInterest(page, `${RUN} first calculation`);
      await expectToast(page, t('en', 'c12.ran', [1, 1]));
      await runInterest(page, `${RUN} re-run for one member`, EIS);
      await expectToast(page, t('en', 'c12.ran', [2, 1]));
      await expect(row(page, `${year}|1`)).toContainText(`${RUN} first calculation`);
      await expect(row(page, `${year}|2`)).toContainText(EIS);
      await expect(row(page, `${year}|2`)).toContainText(MANAGER);
      await page.getByRole('button', { name: t('en', 'c12.showReport') }).click();
      const report = page.getByRole('table', { name: t('en', 'c12.report') });
      await expect(report).toContainText(EIS);
      await expect(report).toContainText(t('en', 'c12.reason.INTEREST_METHOD_NOT_CONFIGURED'));
    },
  );

  test(
    'SRS-6-45: a run against an undeclared rate is refused and nothing is recorded',
    { tag: ['@SRS-6-45', '@SRS-6-11', '@SC-07-0034', '@negative'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const year = finYear(1);
      openYear(year, false);
      await workAs(page, 'en', MANAGER, manager, 'C-12');
      await showYear(page, year);
      await runInterest(page, `${RUN} undeclared`);
      await expect(
        page.getByText(t('en', 'error.CPF-409-RATE-UNDECLARED', [year, 'FCI_TRUST'])),
      ).toBeVisible();
      await page.getByRole('button', { name: t('en', 'common.cancel') }).click();
      // Stated in the drawn run audit and in the register under it.
      await expect(page.getByText(t('en', 'c12.noRuns')).first()).toBeVisible();
    },
  );

  // E2E-22 step 1 (SRS-6-11, BR-CPF-033): the rate is chosen per member - the FCI-declared rate for
  // an active member, the EPFO-declared (RPFC) rate for an ex-member. The interest amount itself
  // waits for the method (Q-072); the choice is what the run checks before it starts. A second
  // North Zone ledger of the year (fixture), of an ex-member, needs the EPFO rate too.
  test(
    'E2E-22: an active member runs on the FCI rate alone; an ex-member in scope needs the EPFO rate',
    { tag: ['@E2E-22', '@SRS-6-11', '@SRS-6-45', '@negative'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const year = finYear(3);
      openYear(year, true);
      const exMember = `(SELECT e.employee_id FROM employee e JOIN org_zone z ON z.zone_id = e.parent_zone_id WHERE e.emp_num = '870001' AND z.zone_code = 'NA11')`;
      sql(
        `INSERT INTO employee_pf_header (employee_id, fin_year, zone_id, opening_balance_employee, closing_balance_employee, opening_balance_source, member_status) SELECT ${exMember}, '${year}', (SELECT zone_id FROM org_zone WHERE zone_code = 'NA11'), 500, 500, 'MIGRATION', 'EX_MEMBER' FROM dual WHERE NOT EXISTS (SELECT 1 FROM employee_pf_header WHERE employee_id = ${exMember} AND fin_year = '${year}')`,
      );
      await workAs(page, 'en', MANAGER, manager, 'C-12');
      await showYear(page, year);
      // The whole zone holds an ex-member: no EPFO rate for the year, so the run does not start.
      await runInterest(page, `${RUN} zone without the EPFO rate`);
      await expect(page.getByText(t('en', 'error.CPF-422-RATE-MISSING', [year, 'RPFC']))).toBeVisible();
      await page.getByRole('button', { name: t('en', 'common.cancel') }).click();
      // Stated in the drawn run audit and in the register under it.
      await expect(page.getByText(t('en', 'c12.noRuns')).first()).toBeVisible();
      // The active member alone needs only the FCI rate.
      await runInterest(page, `${RUN} active member on the FCI rate`, EIS);
      await expectToast(page, t('en', 'c12.ran', [1, 1]));
      // Once the EPFO rate is declared, the whole zone runs: both members, each on its own rate.
      sql(
        `INSERT INTO pf_interest_rate (fin_year, fund_type, rate_source, member_population, rate_percent, applies_from, is_declared) SELECT '${year}', 'CPF', 'RPFC', 'INACTIVE', 8.25, DATE '${year.slice(0, 4)}-04-01', 'Y' FROM dual WHERE NOT EXISTS (SELECT 1 FROM pf_interest_rate WHERE fin_year = '${year}' AND fund_type = 'CPF' AND rate_source = 'RPFC')`,
      );
      await runInterest(page, `${RUN} zone with both rates`);
      await expectToast(page, t('en', 'c12.ran', [2, 2]));
    },
  );

  test(
    'SRS-6-46: the AGM (CPF) approves the freeze without OTP; after it nothing runs for the year',
    { tag: ['@SRS-6-46', '@SC-07-0035', '@positive'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const year = finYear(2);
      openYear(year, true);
      await workAs(page, 'en', MANAGER, manager, 'C-12');
      await showYear(page, year);
      await runInterest(page, `${RUN} final calculation`);
      await expectToast(page, t('en', 'c12.ran', [1, 1]));
      await page.getByRole('button', { name: t('en', 'action.freezeInterest') }).click();
      const dialog = page.getByRole('dialog');
      await dialog.getByRole('textbox').fill(`${RUN} year closed`);
      await dialog.getByRole('button', { name: t('en', 'action.freezeInterest') }).click();
      await expectToast(page, t('en', 'c12.freezeRequested', [year]));
      await expect(page.getByText(t('en', 'c12.freeze.PENDING'))).toBeVisible();

      await workAs(page, 'en', AGM, agm, 'W-01');
      await decide(page, new RegExp(`${year}\\|NA11`), 'APPROVE');

      await workAs(page, 'en', MANAGER, manager, 'C-12');
      await showYear(page, year);
      await expect(page.getByText(t('en', 'c12.freeze.FROZEN'), { exact: true })).toBeVisible();
      await expect(page.getByRole('button', { name: runButton('en') })).toBeDisabled();
      await expect(page.getByRole('button', { name: t('en', 'action.freezeInterest') })).toBeDisabled();
      expect(
        await apiStatus(page, '/v1/cpf/interest-runs', 'POST', {
          finYear: year,
          scope: 'ALL_MEMBERS',
          reason: `${RUN} too late`,
        }),
      ).toBe(409);
    },
  );

  test(
    'SRS-22-21: the CPF Division reads C-12 in Hindi and runs nothing; a Payroll Maker reads its own office only and runs nothing',
    { tag: ['@SRS-22-21', '@SRS-6-45', '@SRS-6-46', '@SRS-G-29', '@SC-07-0035', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'hi', 'cpf.north@fci-demo.test', { role: 'CPF_DIVISION', office: 'NA11' }, 'C-12');
      await expect(page.getByRole('heading', { level: 1, name: t('hi', 'screen.C-12') })).toBeVisible();
      await expect(page.getByText(t('hi', 'c12.audit'))).toBeVisible();
      await expect(page.getByRole('button', { name: runButton('hi') })).toHaveCount(0);
      await expect(page.getByRole('button', { name: t('hi', 'action.freezeInterest') })).toHaveCount(0);
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'W-02',
      );
      // SRS 14.4 lists the maker on C-12 (OD-125); SRS-6-45 gives the run to the CPF Division, and
      // SRS-G-29 bounds what is read: the report of a zone is never another office's members.
      expect(await apiStatus(page, '/v1/cpf/interest-runs?finYear=2026-27')).toBe(200);
      const report = await apiJson<{ items: { eis: string }[] }>(
        page,
        '/v1/cpf/interest-runs/not-calculated?finYear=2026-27&page=0&size=100',
      );
      const elsewhere = new Set(
        sqlRows(
          "SELECT e.emp_num FROM employee e WHERE e.location_id NOT IN (SELECT o.location_id FROM org_location o WHERE o.location_code = 'DH001' OR o.parent_location_id = (SELECT p.location_id FROM org_location p WHERE p.location_code = 'DH001'))",
        ).map((r) => r[0]),
      );
      expect(report.items.filter((m) => elsewhere.has(m.eis))).toEqual([]);
      expect(
        await apiStatus(page, '/v1/cpf/interest-runs', 'POST', {
          finYear: '2026-27',
          scope: 'ALL_MEMBERS',
          reason: 'forged',
        }),
      ).toBe(403);
      expect(
        await apiStatus(page, '/v1/cpf/interest-runs/freeze', 'POST', {
          finYear: '2026-27',
          reason: 'forged',
        }),
      ).toBe(403);
    },
  );
});
