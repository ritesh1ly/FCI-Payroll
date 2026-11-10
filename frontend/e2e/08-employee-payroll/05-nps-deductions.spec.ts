import { expect, test } from '@playwright/test';
import { businessToday, exportLink, sidebarCodes, t, workAs, pickerMonth } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sqlRows } from '../support/db';

/**
 * I-14 NPS Deduction (from HRMS) (plan 18 phase 18c; SRS-9-1, SRS-9-1a, SRS-9-2, SRS-9-6; D18-7):
 * Finance at Headquarters sees the previous month's records HRMS sent (demo seed V041_3_9), the
 * mismatch listed first and marked as corrected in HRMS, the PRAN as a reference, and exports the
 * register and the NPS Deduction Report; the Payroll Maker of Delhi does not see another unit's
 * records; Establishment/HR has no I-14.
 */
const FINANCE = { role: 'FINANCE', office: 'HQ001' };
const MAKER = { role: 'PAYROLL_MAKER', office: 'DH001' };
const HR = { role: 'ESTABLISHMENT_HR', office: 'DH001' };

/** The previous month as YYYYMM (the demo records' month). */
function previousMonth(): string {
  const today = businessToday();
  const year = Number(today.slice(0, 4));
  const month = Number(today.slice(5, 7));
  return month === 1 ? `${year - 1}12` : `${year}${String(month - 1).padStart(2, '0')}`;
}

test.describe.serial('NPS deduction from HRMS (I-14, plan 18c)', () => {
  test(
    'SRS-9-1a: Finance at HQ sees the month, the mismatch first, the PRAN, and exports the register and the report',
    { tag: ['@SRS-9-1', '@SRS-9-1a', '@SRS-9-2', '@SRS-9-6', '@positive'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const month = previousMonth();
      const [pran] = sqlRows(
        `SELECT MIN(pran_reference) AS pran FROM nps_deduction WHERE payroll_yymm = '${month}' AND mismatch_status = 'CONSISTENT'`,
      ).map((r) => r[0] as string);
      await workAs(page, 'en', 'finance.hq@fci-demo.test', FINANCE, 'I-14');
      const monthBox = page.getByLabel(t('en', 'i14.month'));
      await monthBox.fill(pickerMonth(month));
      const table = page.getByRole('table', { name: t('en', 'screen.I-14') });
      await expect(table.locator('tbody tr').first()).toContainText(
        t('en', 'i14.status.FLAG_WITHOUT_AMOUNT'),
      );
      await expect(table.locator('tbody tr').first()).toContainText(t('en', 'i14.fixInHrms'));
      await expect(table).toContainText(String(pran));
      await expect(page.getByRole('list', { name: t('en', 'i14.counts') })).toContainText(
        t('en', 'i14.status.RECEIVED'),
      );

      // The closed section's own Export reads its month; the drawn one reads the session's.
      const register = await exportLink(page, 'CSV', undefined, page.getByTestId('i14-register'));
      const downloading = page.waitForEvent('download');
      await register.click();
      expect((await downloading).suggestedFilename()).toBe(`nps-deductions-${month}.csv`);

      const closed = page.locator('details[data-testid="i14-register"]');
      if (!(await closed.evaluate((d) => (d as HTMLDetailsElement).open)))
        await closed.locator('summary').first().click();
      const report = page
        .locator('section, .card')
        .filter({ hasText: t('en', 'i14.report') })
        .last();
      await report.getByLabel(t('en', 'i14.from')).fill(pickerMonth(month));
      await report.getByLabel(t('en', 'i14.to')).fill(pickerMonth(month));
      await report.getByRole('button', { name: t('en', 'i14.reportExport') }).click();
      const dialog = page.getByRole('dialog');
      await dialog.locator('input[value="XLSX"]').check();
      const reporting = page.waitForEvent('download');
      await dialog.getByRole('link', { name: t('en', 'export.download') }).click();
      expect((await reporting).suggestedFilename()).toBe(`nps-deduction-report-${month}-${month}.xlsx`);
    },
  );

  test(
    "D18-7: the Payroll Maker of Delhi does not see another unit's records",
    { tag: ['@SRS-9-1', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', 'maker.delhi@fci-demo.test', MAKER, 'I-14');
      await page.getByLabel(t('en', 'i14.month')).fill(pickerMonth(previousMonth()));
      await expect(page.getByText(t('en', 'i14.none'))).toBeVisible();
    },
  );

  test('SRS-22-1: Establishment/HR has no I-14', { tag: ['@SRS-22-1', '@negative'] }, async ({ page }) => {
    await workAs(page, 'en', 'hr.delhi@fci-demo.test', HR, 'M-01');
    expect(await sidebarCodes(page)).not.toContain('I-14');
  });
});
