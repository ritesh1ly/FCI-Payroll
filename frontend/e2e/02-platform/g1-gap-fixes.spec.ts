import { expect, type Page, test } from '@playwright/test';
import { apiStatus, businessToday, dataRows, employeeList, errorAlert, t, workAs } from '../support/app';
import { apiJson } from '../support/payroll-support';

/**
 * Group g1 gap fixes (modules 01-04, 2026-11-02) through the UI, on the demo data of V077_14:
 * the previous unit's read-only access to a transferred member (SRS-G-53) found by the old staff
 * number (SRS-16-7); the M-26 local name of an operation shown with the standard one and searched
 * (SRS-G-39); circulars searched by date and listed on the home page (SRS-G-38); the bulk label
 * upload for the Administrator only; the route's Attachment flag on A-08; the A-04 date range; the
 * X-04 empty result naming its dates. Read-only or refused actions only: re-runnable.
 */
const HR_DELHI = 'hr.delhi@fci-demo.test';
const hrDelhi = { role: 'ESTABLISHMENT_HR', office: 'DH001' };
const MAKER_DELHI = 'maker.delhi@fci-demo.test';
const makerDelhi = { role: 'PAYROLL_MAKER', office: 'DH001' };
const SYSADMIN = 'sysadmin@fci-demo.test';
const sysadmin = { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' };
const RULE_ADMIN = 'su.hq@fci-demo.test';
const ruleAdmin = { role: 'RULE_ADMINISTRATOR', office: 'HQ001' };

function table(page: Page) {
  return page.locator('table.t').last();
}

test.describe('Group g1 gap fixes (modules 01-04)', () => {
  test(
    'SRS-G-53: the previous unit finds a transferred member by the old staff number and reads the record only',
    { tag: ['@SRS-G-53', '@SRS-16-7', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', HR_DELHI, hrDelhi, 'M-01');
      await page.getByLabel(t('en', 'wf.searchInList')).fill('107790');
      await expect(dataRows(employeeList(page))).toHaveCount(1);
      await expect(dataRows(employeeList(page)).first()).toContainText('779001');
      await employeeList(page)
        .getByRole('button', { name: /View record/ })
        .click();
      await expect(page.getByTestId('m01-previous-unit')).toBeVisible();
      // Transferred 30 days ago (V077_14: the database's CURRENT_DATE at deploy, which is the UTC day
      // between 00:00 and 05:30 IST - so the date is read back, not recomputed), shown in the
      // screens' date format (en-IN "03 Oct 2026").
      const record = (await apiJson<{ transferredOut?: string | null }>(page, '/v1/employees/779001')) ?? {};
      expect(record.transferredOut).toBeTruthy();
      const [y, m, d] = String(record.transferredOut).slice(0, 10).split('-').map(Number) as [
        number,
        number,
        number,
      ];
      const day = new Date(Date.UTC(y, m - 1, d)).toLocaleDateString('en-IN', {
        day: '2-digit',
        month: 'short',
        year: 'numeric',
        timeZone: 'UTC',
      });
      await expect(page.getByTestId('m01-previous-unit')).toHaveText(t('en', 'm01.previousUnit', [day]));
      // Read-only: no Payroll-owned flag panel for the previous unit, and nothing is recorded (no
      // event is keyed in Payroll at all since SRS-1-63; EmployeeEventIT proves the flag refusal).
      await expect(page.getByText(t('en', 'm01.flags.title'))).toHaveCount(0);
      expect(
        await apiStatus(page, '/v1/employees/779001/lifecycle-events', 'POST', {
          eventType: 'HOLD',
          eventDate: businessToday(),
          reason: 'E2E previous unit may not record',
        }),
      ).toBeGreaterThanOrEqual(400);
    },
  );

  test(
    'SRS-G-53: an office the member never served does not read the record',
    { tag: ['@SRS-G-53', '@SRS-G-29', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'maker.chennai@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'CH001' },
        'M-01',
      );
      expect([403, 404]).toContain(await apiStatus(page, '/v1/employees/779001'));
      await page.getByLabel(t('en', 'wf.searchInList')).fill('107790');
      await expect(page.getByText('779001')).toHaveCount(0);
    },
  );

  test(
    'SRS-G-39: a Delhi user sees the local and the standard operation name and finds it by the local name',
    { tag: ['@SRS-G-39', '@SRS-G-40', '@SC-01-0037', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER_DELHI, makerDelhi, 'M-27');
      await expect(page.getByText(t('en', 'm27.localName', ['Thappi Lagai', 'Stacking']))).toBeVisible();
      await page.getByText(t('en', 'wf.searchDisclosure')).click();
      await page.getByLabel(t('en', 'm27.search')).fill('thappi');
      await expect(table(page).locator('tbody tr:not(.fl-state-row)')).toHaveCount(1);
      await expect(table(page).locator('tbody tr').first()).toContainText('STACK');
      await page.getByLabel(t('en', 'm27.search')).fill('no-such-operation');
      await expect(page.getByText(t('en', 'm27.none'))).toBeVisible();
    },
  );

  test(
    'SRS-G-39: Headquarters sees the standard name only, and still finds the operation by the local name',
    { tag: ['@SRS-G-39', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-27');
      await page.getByText(t('en', 'wf.searchDisclosure')).click();
      await page.getByLabel(t('en', 'm27.search')).fill('Thappi');
      await expect(table(page).locator('tbody tr:not(.fl-state-row)')).toHaveCount(1);
      await expect(table(page).locator('tbody tr').first()).toContainText('Stacking');
      await expect(page.getByText('Thappi Lagai')).toHaveCount(0);
    },
  );

  test(
    'SRS-G-38: circulars are searched by date; a reversed range is named; the home page lists the current ones',
    { tag: ['@SRS-G-38', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER_DELHI, makerDelhi, 'M-25');
      await page.getByText(t('en', 'wf.searchDisclosure')).click();
      await page.getByLabel(t('en', 'm25.dateFrom')).fill('2026-04-01');
      await page.getByLabel(t('en', 'm25.dateTo')).fill('2026-03-31');
      await expect(errorAlert(page)).toHaveText(t('en', 'm25.rangeReversed'));
      await page.getByLabel(t('en', 'm25.dateTo')).fill(businessToday());
      await expect(errorAlert(page)).toHaveCount(0);
      await page.goto('/payroll/');
      await expect(page.getByRole('heading', { name: t('en', 'dash.circulars') })).toBeVisible();
    },
  );

  test(
    'SRS M-26: an office maker sees the bulk label upload disabled with its reason',
    { tag: ['@SRS-G-39', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER_DELHI, makerDelhi, 'M-26');
      const upload = page.getByRole('button', { name: t('en', 'm26.act.upload'), exact: true });
      await expect(upload).toBeDisabled();
      await expect(upload).toHaveAttribute('title', t('en', 'm26.why.upload'));
    },
  );

  test(
    'SRS-22-12: the route list shows the Attachment flag; X-04 names the dates of an empty search',
    { tag: ['@SRS-22-12', '@SRS-20-2', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', SYSADMIN, sysadmin, 'A-08');
      await expect(
        page.getByRole('columnheader', { name: new RegExp(t('en', 'x01.x.attachment')) }),
      ).toBeVisible();
      await page.goto('/payroll/?s=A-05');
      // X-04 keeps the dates and the free-text filters behind the drawn Filter button of the Audit Trail (OD-121).
      await page.getByRole('button', { name: t('en', 'x04w.filter'), exact: true }).click();
      await page.getByLabel(t('en', 'a05.field'), { exact: true }).fill('NO_SUCH_FIELD_E2E');
      await page.getByRole('button', { name: t('en', 'common.search'), exact: true }).click();
      await expect(page.getByText(t('en', 'a05.none')).first()).toBeVisible();
      await expect(page.getByTestId('a05-none-range')).toBeVisible();
    },
  );

  test(
    'SRS l.1131: an assignment ending before it starts is refused on A-04 and nothing is sent',
    { tag: ['@SRS-22-19', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', SYSADMIN, sysadmin, 'A-04');
      await page.getByRole('button', { name: t('en', 'action.newAssignment') }).click();
      await page.getByLabel(t('en', 'a04.from'), { exact: true }).fill('2099-12-31');
      await page.getByLabel(t('en', 'a04.to'), { exact: true }).fill('2099-12-30');
      await page.getByLabel(t('en', 'common.reason')).last().fill('E2E reversed range');
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expect(errorAlert(page)).toHaveText(t('en', 'a04.toBeforeFrom'));
    },
  );
});
