import { expect, test } from '@playwright/test';
import { businessToday, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql } from '../support/db';

/**
 * OD-144 / OD-149 (owner 2026-11-09: every manual download rendered on demand from the database; a bulk download is a
 * ZIP of single-month PDFs, a single month one PDF; the office-wide bulk is a background job, SRS-NF-2):
 *   - E-01: one locked month downloads as its PDF, two as one ZIP; an open month is refused (404) with nothing to say
 *     which; a staff user has no own payslip (403);
 *   - O-01: Bulk PDF queues the job of a locked month and says so; a member cannot ask for it (403);
 *   - C-10: a certificate released before issued copies were kept (OD-153) shows its PDF off with the reason and
 *     the server refuses it (409, never recomputed); Bulk PDF queues the year's job. The positive download of a
 *     freshly released, frozen certificate is proven in backend Form16IT.
 * Fixture months 26 and 25 years before today (far behind every demo month, G67); dates from businessToday().
 * Written for the deployed demo stack (the member bulk needs fci.api.pdf.member-bulk-max-periods, set by the demo
 * profile); not run in this change.
 */
const MEMBER = 'emp.rsharma@fci-demo.test';
const MAKER = 'maker.delhi@fci-demo.test';
const MM = businessToday().slice(5, 7);
const Y1 = Number(businessToday().slice(0, 4)) - 26;
const Y2 = Y1 + 1;
const LOCKED_1 = `${Y1}${MM}`;
const LOCKED_2 = `${Y2}${MM}`;
const OPEN = `${Y1}${MM === '12' ? '11' : String(Number(MM) + 1).padStart(2, '0')}`;
const API = '/nginx/fci-payroll';

function finYearOf(yymm: string): string {
  const y = Number(yymm.slice(0, 4));
  const start = Number(yymm.slice(4)) >= 4 ? y : y - 1;
  return `${start}-${String((start + 1) % 100).padStart(2, '0')}`;
}

/** A STAFF result of the member for {@code yymm}, locked or open, with three lines. */
function monthFixture(yymm: string, locked: boolean) {
  const self = `(SELECT employee_id FROM app_user WHERE login_id = '${MEMBER}')`;
  const unit = `(SELECT location_id FROM employee WHERE employee_id = ${self})`;
  const day = `DATE '${yymm.slice(0, 4)}-${yymm.slice(4)}-01'`;
  const flag = locked ? 'Y' : 'N';
  sql(
    [
      `INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level, fin_year, cycle_status, initiated_by) SELECT '${yymm}', ${unit}, 'DO', '${finYearOf(yymm)}', '${locked ? 'LOCKED' : 'COMPUTED'}', 'e2e.fixture' FROM dual WHERE NOT EXISTS (SELECT 1 FROM payroll_cycle_instance WHERE payroll_yymm = '${yymm}' AND org_unit_id = ${unit})`,
      `INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id, payroll_type, payroll_yymm, period_date, run_type, gross_earnings, total_deductions, net_pay, result_status, is_locked) SELECT (SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm = '${yymm}' AND org_unit_id = ${unit}), ${unit}, ${self}, 'STAFF', '${yymm}', ${day}, 'ACTUAL', 45000, 5400, 39600, 'CURRENT', '${flag}' FROM dual WHERE NOT EXISTS (SELECT 1 FROM payroll_result WHERE employee_id = ${self} AND payroll_yymm = '${yymm}')`,
      `INSERT INTO payroll_result_component (payroll_result_id, cycle_instance_id, org_unit_id, employee_id, component_id, amount, run_type, is_locked, payroll_yymm, period_date, legacy_pay_code) SELECT r.payroll_result_id, r.cycle_instance_id, r.org_unit_id, r.employee_id, c.component_id, CASE c.legacy_pay_code WHEN 101 THEN 30000 WHEN 111 THEN 15000 ELSE -5400 END, 'ACTUAL', '${flag}', '${yymm}', ${day}, c.legacy_pay_code FROM payroll_result r JOIN pay_component c ON c.legacy_pay_code IN (101, 111, 904) WHERE r.employee_id = ${self} AND r.payroll_yymm = '${yymm}' AND NOT EXISTS (SELECT 1 FROM payroll_result_component x WHERE x.payroll_result_id = r.payroll_result_id)`,
    ].join(';\n'),
  );
}

test.describe.serial('PDF and ZIP downloads (OD-144, OD-149)', () => {
  test.beforeAll(() => {
    if (!dbFixtureAvailable()) return;
    monthFixture(LOCKED_1, true);
    monthFixture(LOCKED_2, true);
    monthFixture(OPEN, false);
  });

  test(
    'OD-149: the member downloads one locked month as a PDF and two as one ZIP',
    { tag: ['@OD-149', '@SRS-17-1', '@positive'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      await workAs(page, 'en', MEMBER, { role: 'EMPLOYEE_ESEVA' }, 'E-01');
      const download = page.getByRole('button', { name: t('en', 'e01p.bulk.download') });
      await expect(download).toBeDisabled();
      await expect(download).toHaveAttribute('data-why', t('en', 'e01p.bulk.why.none'));
      const months = page.getByRole('table', { name: t('en', 'e01p.months') });
      const box = (year: number) =>
        months.getByRole('row', { name: new RegExp(String(year)) }).getByRole('checkbox');
      await box(Y1).check();
      const one = page.waitForEvent('download');
      await download.click();
      const pdf = await one;
      expect(pdf.suggestedFilename()).toBe(`payslip-${LOCKED_1}.pdf`);
      await box(Y2).check();
      const two = page.waitForEvent('download');
      await download.click();
      const zip = await two;
      expect(zip.suggestedFilename()).toBe(`payslips-${LOCKED_1}-${LOCKED_2}.zip`);
    },
  );

  test(
    'OD-149, SRS-17-1: an open month, alone or with a locked one, is not found; another role has no own payslip',
    { tag: ['@OD-149', '@SRS-17-1', '@negative'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      await workAs(page, 'en', MEMBER, { role: 'EMPLOYEE_ESEVA' }, 'E-01');
      expect((await page.request.get(`${API}/v1/payslips/mine/${OPEN}/pdf`)).status()).toBe(404);
      expect(
        (await page.request.get(`${API}/v1/payslips/mine/zip?months=${LOCKED_1},${OPEN}`)).status(),
      ).toBe(404);
      const zip = await page.request.get(`${API}/v1/payslips/mine/zip?months=${LOCKED_1},${LOCKED_2}`);
      expect(zip.status()).toBe(200);
      expect(zip.headers()['content-type']).toBe('application/zip');
      expect(zip.headers()['x-content-type-options']).toBe('nosniff');
      const single = await page.request.get(`${API}/v1/payslips/mine/zip?months=${LOCKED_1}`);
      expect(single.headers()['content-type']).toBe('application/pdf');
      expect((await page.request.get(`${API}/v1/payslip-bulk-files?yymm=${LOCKED_1}`)).status()).toBe(403);
    },
  );

  test(
    'SRS-NF-2, OD-149: the Payroll Maker queues the O-01 Bulk PDF of a locked month as a background job',
    { tag: ['@SRS-NF-2', '@OD-149', '@positive'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      await workAs(page, 'en', MAKER, { role: 'PAYROLL_MAKER', office: 'DH001' }, 'O-01');
      // The O-01 filter bar's Period (Wireframes v6.1), not the top bar's "Payroll period" (M-10).
      await page
        .getByRole('main')
        .getByLabel(t('en', 'o01.filter.period'), { exact: true })
        .selectOption({ value: LOCKED_1 });
      const bulk = page.getByRole('button', { name: t('en', 'o01.act.bulkPdf') });
      await expect(bulk).toBeEnabled();
      await bulk.click();
      await expect(page.getByRole('status').filter({ hasText: /Bulk file/ })).toBeVisible();
      expect((await page.request.get(`${API}/v1/payslips/mine/zip?months=${LOCKED_1}`)).status()).toBe(403);
    },
  );

  test(
    'OD-153, SRS-7-9: a certificate released before issued copies were kept shows its PDF off with the reason and is refused; the year queues as a ZIP',
    { tag: ['@OD-153', '@OD-144', '@SRS-7-9', '@negative'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      await workAs(page, 'en', MAKER, { role: 'PAYROLL_MAKER', office: 'DH001' }, 'C-10');
      // The register opens on the last closed financial year: the demo's released certificates (seed V080_31).
      const year = finYearOf(`${Number(businessToday().slice(0, 4)) - 1}${MM}`);
      const lines = (await (
        await page.request.get(`${API}/v1/tax/form16-certificates/lines?finYear=${year}&page=0&size=100`)
      ).json()) as { items: { id: number; eis: string; status: string; issuedCopy: boolean }[] };
      const seeded = lines.items.find((l) => l.status === 'RELEASED' && !l.issuedCopy);
      if (!seeded) {
        test.skip(true, `no released certificate without an issued copy in ${year} on this stack`);
        return;
      }
      const table = page.getByRole('table', { name: t('en', 'txw.c10.registerLabel') });
      const row = table.getByRole('row').filter({ hasText: seeded.eis });
      const off = row.getByRole('button', { name: t('en', 'txw.c10.pdf') });
      await expect(off).toBeDisabled();
      await expect(off).toHaveAttribute('data-why', t('en', 'txw.c10.why.noIssuedCopy'));
      // The server refuses it with its code; nothing is recomputed (a forged call, the button sends none).
      const refused = await page.request.get(`${API}/v1/tax/form16-certificates/${seeded.id}/part-b`);
      expect(refused.status()).toBe(409);
      expect(((await refused.json()) as { errorCode: string }).errorCode).toBe('TAX-409-NO-ISSUED-COPY');
      const [bulk] = await page.getByRole('button', { name: t('en', 'txw.c10.bulkPdf') }).all();
      await bulk?.click();
      await expect(
        page
          .getByRole('status')
          .filter({ hasText: /Bulk file/ })
          .first(),
      ).toBeVisible();
    },
  );
});
