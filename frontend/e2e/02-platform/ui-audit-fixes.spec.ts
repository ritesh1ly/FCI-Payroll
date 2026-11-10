import { expect, type Page, test } from '@playwright/test';
import { apiStatus, businessToday, errorAlert, finYearOf, previousFinYear, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql, sqlRows } from '../support/db';
import { apiJson } from '../support/payroll-support';

/**
 * Fixes of the static UI audit of 2026-11-02 (items S1 to S13) that change what a user sees:
 * - S1 (R-SRC, SRS-14-1): an I-06 order received from HRMS offers neither Revoke nor Cancel;
 * - S4: M-10 opens on the current financial year (IST), C-01 says "no cycle yet" instead of an
 *   error, and the demo region RO-PB has a cycle of the current month (seed V077_22); I-14 opens on
 *   the current month with the records of seed V077_13;
 * - S5: the I-18 member year is the financial year chosen;
 * - S8 (SRS l.5440): the Rule Approver reads the M-12 catalogue, without O-04;
 * - S13: C-10 "Request release" waits for a merged certificate, and a refused export is shown in
 *   the export dialog instead of being saved as a file of error text;
 * - SRS-16-7 (review g1): M-01 finds a member by a staff number held before a transfer.
 * Read only except the S1 stubs and the M-01 history row (a run of its own): re-runnable.
 */
const ADMIN = 'sysadmin@fci-demo.test';
const admin = { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' };
const MAKER = 'maker.delhi@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };
const PUNJAB = 'pm.ropb@fci-demo.test';
const punjab = { role: 'PAYROLL_MAKER', office: 'RO-PB' };
const TAX_MAKER = 'tax.maker.delhi@fci-demo.test';
const APPROVER = 'rule.approver@fci-demo.test';
const approver = { role: 'RULE_APPROVER', office: 'HQ001' };
const FINANCE = 'finance.hq@fci-demo.test';
const finance = { role: 'FINANCE', office: 'HQ001' };

/** The financial year of a period YYYY-MM, as M-10 names it (2026-27). */
function currentFinYear(): string {
  return finYearOf(businessToday());
}

async function problemJson(page: Page, pattern: string, status: number, errorCode: string) {
  await page.route(pattern, (route) =>
    route.fulfill({
      status,
      contentType: 'application/problem+json',
      body: JSON.stringify({ status, errorCode, title: 'refused' }),
    }),
  );
}

test.describe('UI audit fixes (S1-S13)', () => {
  test(
    'S4: M-10 opens on the current financial year, not on next year still to be set up',
    { tag: ['@SRS-15-33', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', ADMIN, admin, 'M-10');
      // The calendar heading names the year shown; the year select sits in the drawn FY Calendar dialog (OD-121).
      await expect(page.getByText(t('en', 'm10w.calendar', [currentFinYear()]))).toBeVisible();
      await page.getByRole('button', { name: t('en', 'm10w.fyCalendar') }).click();
      const dialog = page.getByRole('dialog', { name: t('en', 'm10w.fyCalendar') });
      await expect(dialog.getByLabel(t('en', 'm10.finYear'))).toHaveValue(currentFinYear());
      await dialog.getByRole('button', { name: t('en', 'common.close') }).click();
      await expect(page.getByText(t('en', 'm10w.calendar', [currentFinYear()]))).toBeVisible();
    },
  );

  test(
    'S4: C-01 shows the gates of the seeded RO-PB cycle, and a period without a cycle as an empty state',
    { tag: ['@SRS-15-17', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', PUNJAB, punjab, 'C-01');
      const noCycle = page.getByText(new RegExp(t('en', 'c01.noCycle', ['§']).split('§')[0] as string));
      await expect(noCycle).toHaveCount(0);
      await expect(
        page
          .locator('.card')
          .filter({ hasText: t('en', 'c01.gate') })
          .first(),
      ).toBeVisible();
      // No cycle for the period (forced): a plain empty state, no error and no gate card.
      await problemJson(page, '**/v1/cycles/*/gates', 404, 'COM-404-NOT-FOUND');
      await page.reload();
      await expect(noCycle).toBeVisible();
      await expect(errorAlert(page)).toHaveCount(0);
    },
  );

  test(
    'S4: I-14 opens on the current month with the records HRMS sent for it',
    { tag: ['@SRS-9-1', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', FINANCE, finance, 'I-14');
      const month = businessToday().slice(0, 7).replace('-', '');
      // A month picker (UI audit 2026-11-02): it shows YYYY-MM and the API gets YYYYMM.
      await expect(page.getByLabel(t('en', 'i14.month'))).toHaveValue(businessToday().slice(0, 7));
      await expect(page.locator(`tr[data-reference$="|${month}"]`).first()).toBeVisible();
    },
  );

  test(
    'S5: the I-18 member year is the financial year chosen; a partial year cannot be chosen',
    { tag: ['@SRS-7-25', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', TAX_MAKER, maker, 'I-18');
      const year = page.getByLabel(t('en', 'i07.finYear'));
      await year.selectOption(previousFinYear());
      await page.getByLabel(t('en', 'm01.eis')).fill('870003');
      const opened = page.waitForRequest((r) => r.url().includes('/v1/tax/savings/members/870003?'));
      await page.getByRole('button', { name: t('en', 'i18.openMember') }).click();
      expect((await opened).url()).toContain(`finYear=${previousFinYear()}`);
      await expect(page.getByRole('heading', { name: new RegExp(previousFinYear()) }).first()).toBeVisible();
      // The year is a select of valid financial years (UI audit 2026-11-02): "2025" is never offered.
      await expect(year.locator('option', { hasText: /^2025$/ })).toHaveCount(0);
    },
  );

  test(
    'S8: the Rule Approver reads the M-12 catalogue without O-04; the O-04 catalogue stays refused',
    { tag: ['@SRS-18-9', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', APPROVER, approver, 'M-12');
      await expect(errorAlert(page)).toHaveCount(0);
      await page.getByRole('button', { name: t('en', 'm12w.act.versions') }).click();
      await expect(page.getByRole('table', { name: t('en', 'screen.M-12') })).toBeVisible();
      expect(await apiStatus(page, '/v1/report-definitions?page=0&size=20')).toBe(200);
      expect(await apiStatus(page, '/v1/reports/catalogue?page=0&size=20')).toBe(403);
    },
  );

  test(
    'S13: C-10 "Request release" waits for a merged certificate and says why',
    { tag: ['@BR-TAX-012', '@negative'] },
    async ({ page }) => {
      // The Payroll Maker requests the release; the Checker releases it in W-01 (SRS-7-21).
      await workAs(page, 'en', TAX_MAKER, maker, 'C-10');
      // C-10 opens on the year just closed (Form 16 follows the year end), which the demo data now
      // has merged certificates in; the year in progress has none. Each year per its own count.
      const yearSelect = page.getByLabel(t('en', 'i07.finYear'));
      const release = page.getByRole('button', { name: t('en', 'c10.release') });
      const mergedIn = async (year: string) =>
        (
          await apiJson<{ total: number }>(
            page,
            `/v1/tax/form16-certificates?finYear=${year}&status=MERGED&page=0&size=10`,
          )
        ).total;
      expect(await mergedIn(currentFinYear()), 'no certificate of the year in progress').toBe(0);
      if ((await mergedIn(await yearSelect.inputValue())) > 0) await expect(release).toBeEnabled();
      await yearSelect.selectOption(currentFinYear());
      await expect(release).toBeDisabled();
      await expect(release).toHaveAttribute('title', t('en', 'c10.releaseNoMerged'));
    },
  );

  test(
    'S13: a refused export is shown in the dialog and saves nothing; the next one downloads',
    { tag: ['@SRS-G-13', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'I-21');
      await problemJson(page, '**/export?*', 413, 'COM-413-EXPORT-SIZE');
      let downloads = 0;
      page.on('download', () => {
        downloads += 1;
      });
      await page.getByRole('button', { name: t('en', 'action.export'), exact: true }).click();
      const dialog = page.getByRole('dialog');
      await dialog.locator('input[value="CSV"]').check();
      await dialog.getByRole('link', { name: t('en', 'export.download') }).click();
      await expect(dialog.locator('.fl-error[role="alert"]')).toBeVisible();
      expect(downloads).toBe(0);
      await page.unroute('**/export?*');
      const download = page.waitForEvent('download');
      await dialog.getByRole('link', { name: t('en', 'export.download') }).click();
      expect((await download).suggestedFilename()).toMatch(/\.csv$/);
      await expect(page.getByRole('dialog')).toHaveCount(0);
    },
  );

  test(
    'S1 / SRS-14-17: an I-06 order received from HRMS is read-only and offers no Cancel; Penalty Revoke stays',
    { tag: ['@SRS-14-1', '@SRS-14-16', '@SRS-14-17', '@negative'] },
    async ({ page }) => {
      const row = {
        id: 990001,
        penaltyRef: 'PEN-E2E-HRMS',
        eis: '100001',
        employee: 'HRMS Member',
        nature: 'RECOVERY',
        orderReference: 'HRMS/ORD/1',
        source: 'HRMS',
        amount: 1200,
        instalments: 3,
        periodFrom: null,
        periodTo: null,
        recovered: 0,
        outstanding: 1200,
        status: 'ACTIVE',
        backdated: false,
      };
      await page.route('**/v1/penalties?*', (route) =>
        route.fulfill({ json: { items: [row], page: 0, size: 20, total: 1 } }),
      );
      await page.route('**/v1/penalties/990001', (route) =>
        route.fulfill({ json: { order: row, schedule: [], revokes: [] } }),
      );
      await workAs(page, 'en', MAKER, maker, 'I-06');
      await page.getByRole('button', { name: 'PEN-E2E-HRMS' }).click();
      await expect(page.getByText(t('en', 'i06.hrmsReadOnly'))).toBeVisible();
      // SRS-14-16 / SRS-14-17: a penalty is waived through Penalty Revoke for all payrolls, and an employee's
      // stopped increment (recorded in HRMS, SRS-14-1) is restored that way, so the HRMS order keeps Revoke
      // (PENDING I06-HRMS-REVOKE, superseding UI audit S1). Its terms are HRMS's: no Cancel here.
      await expect(page.locator(`[aria-label="${t('en', 'i06.revoke')}"]`)).toHaveCount(1);
      await expect(page.locator(`[aria-label="${t('en', 'i06.cancel')}"]`)).toHaveCount(0);
    },
  );

  test(
    'SRS-16-7: M-01 finds a member by the staff number held before a transfer',
    { tag: ['@SRS-16-7', '@positive'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const [first] = sqlRows(
        "SELECT e.employee_id || '|' || e.emp_num FROM employee e JOIN org_location l ON l.location_id = e.location_id" +
          " WHERE l.location_code = 'DH001' AND e.record_status = 'ACTIVE' ORDER BY e.emp_num FETCH FIRST 1 ROWS ONLY",
      );
      const [id, eis] = (first?.[0] ?? '').split('|');
      test.skip(!id, 'no active DH001 member');
      const old = `OLD${Date.now() % 10_000_000}`;
      sql(
        'INSERT INTO employee_number_history (employee_id, old_emp_num, new_emp_num, change_date, change_event, reason)' +
          ` VALUES (${id}, '${old}', '${eis}', DATE '2020-01-01', 'TRANSFER', 'E2E old staff number')`,
      );
      await workAs(page, 'en', MAKER, maker, 'M-01');
      await page.getByLabel(t('en', 'wf.searchInList')).fill(old.toLowerCase());
      await expect(
        page
          .locator('tbody tr')
          .filter({ hasText: eis ?? '' })
          .first(),
      ).toBeVisible();
    },
  );
});
