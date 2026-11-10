import { expect, type Locator, type Page, test } from '@playwright/test';
import { businessToday, finYearOf, t, workAs } from '../support/app';
import { MAKER, maker } from '../support/payroll-support';

/**
 * Control fixes of the UI audit of 2026-11-02 (R-ASCII; SRS field lengths; target DDL v4.0), walked
 * across a representative screen of each module:
 * - every long free text (reason, remark, description, subject) is the shared text area: a
 *   textarea capped at its column length, with the characters used and the plain-text hint;
 * - every payroll month is a month picker that opens on India's business month where it has a
 *   default, and every financial year is a select of valid years;
 * - the lengths that were below the DDL take the column's (A-02 role name 150, A-03 group code 40
 *   and name 200, A-05 field filter 100).
 * Read only (forms are opened, nothing is sent): re-runnable.
 */
const ADMIN = 'sysadmin@fci-demo.test';
const admin = { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' };
const TAX_MAKER = 'tax.maker.delhi@fci-demo.test';
const taxMaker = { role: 'PAYROLL_MAKER', office: 'DH001' };
const FINANCE = 'finance.hq@fci-demo.test';
const finance = { role: 'FINANCE', office: 'HQ001' };

/** The shared text area: a textarea of three rows (unless said), capped at {@code max}, with its count. */
async function expectTextArea(field: Locator, max: number, rows = '3') {
  await expect(field).toBeVisible();
  expect(await field.evaluate((e) => e.tagName)).toBe('TEXTAREA');
  await expect(field).toHaveAttribute('maxlength', String(max));
  await expect(field).toHaveAttribute('rows', rows);
  const meta = await field.evaluate((e) => {
    const id = (e.getAttribute('aria-describedby') ?? '').split(' ').pop() ?? '';
    const el = document.getElementById(id);
    return { hint: el?.getAttribute('data-hint'), count: el?.getAttribute('data-count') };
  });
  expect(meta.hint).toBe(t('en', 'common.plainTextHint'));
  expect(meta.count).toBe(t('en', 'common.charCount', [0, max]));
}

/** A month picker: `<input type="month">`, holding YYYY-MM (the API gets YYYYMM or YYYY-MM). */
async function expectMonthPicker(field: Locator, value?: string) {
  await expect(field).toHaveAttribute('type', 'month');
  if (value !== undefined) await expect(field).toHaveValue(value);
}

/** The financial-year select: this year in India first, then the four before it; nothing typed. */
async function expectYearSelect(page: Page) {
  const year = page.getByLabel(t('en', 'i07.finYear'));
  expect(await year.evaluate((e) => e.tagName)).toBe('SELECT');
  const current = finYearOf(businessToday());
  const first = Number(current.slice(0, 4));
  const expected = [0, 1, 2, 3, 4].map(
    (i) => `${first - i}-${String((first - i + 1) % 100).padStart(2, '0')}`,
  );
  const offered = await year.locator('option').allTextContents();
  for (const y of expected) expect(offered).toContain(y);
  expect(offered.every((y) => /^\d{4}-\d{2}$/.test(y))).toBe(true);
  return year;
}

test.describe('Form controls: text areas, month pickers and year selects (UI audit 2026-11-02)', () => {
  test(
    'IAM (A-02, A-03, A-05): descriptions and reasons are text areas; names and codes take the DDL lengths',
    { tag: ['@SRS-22-8', '@SRS-22-23', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', ADMIN, admin, 'A-02');
      await page.getByRole('button', { name: t('en', 'action.newRole') }).click();
      await expect(page.locator('#r-name')).toHaveAttribute('maxlength', '150');
      await expectTextArea(page.locator('#r-desc'), 500);
      await expectTextArea(page.locator('#r-reason'), 500);
      // A line break is plain text (R-ASCII): the count follows what is typed.
      await page.locator('#r-reason').fill('New module\nper HQ order');
      await expect(page.locator('#r-reason')).not.toHaveAttribute('aria-invalid', 'true');

      await workAs(page, 'en', ADMIN, admin, 'A-03');
      await page.getByRole('button', { name: t('en', 'action.newAccessGroup') }).click();
      await expect(page.locator('#g-code')).toHaveAttribute('maxlength', '40');
      await expect(page.locator('#g-name')).toHaveAttribute('maxlength', '200');
      await expectTextArea(page.locator('#g-reason'), 500);

      await workAs(page, 'en', ADMIN, admin, 'A-05');
      await expect(page.getByLabel(t('en', 'a05.field'), { exact: true })).toHaveAttribute(
        'maxlength',
        '100',
      );
    },
  );

  test(
    'Masters (M-25) and X-07: the subject and the reasons are text areas; Hindi in a reason marks it invalid',
    { tag: ['@SRS-G-38', '@SRS-INT-11', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'M-25');
      await page.getByRole('button', { name: t('en', 'm25.act.new'), exact: true }).click();
      await expectTextArea(page.locator('#nc-subject'), 500);
      await expectTextArea(page.locator('#nc-reason'), 500);
      // Not printable ASCII (the US7ASCII database): the field says so before anything is sent.
      await page.locator('#nc-reason').fill('कारण');
      await expect(page.locator('#nc-reason')).toHaveAttribute('aria-invalid', 'true');

      await workAs(page, 'en', ADMIN, admin, 'X-07');
      await page.getByRole('button', { name: t('en', 'x07.w.act.enable') }).click();
      await expectTextArea(page.locator('#x07-reason'), 500);
      await expect(page.locator('#x07-offices')).toHaveAttribute('maxlength', '4200');
    },
  );

  test(
    'Jobs (C-13): the submit reason is a text area of 500; the period stays a month picker',
    { tag: ['@SRS-15-1', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'C-13');
      await page.getByRole('tab', { name: t('en', 'c13.tab.submit') }).click();
      await page.locator('#job-type').selectOption('PAYROLL_DRY_RUN');
      await expectTextArea(page.locator('#job-reason'), 500);
      await expectMonthPicker(page.locator('#job-payPeriod'));
    },
  );

  test(
    'Employee payroll (I-14, I-04, I-06): months are pickers opening on the business month in India',
    { tag: ['@SRS-9-1', '@SRS-12-26', '@SRS-14-19', '@positive'] },
    async ({ page }) => {
      const month = businessToday().slice(0, 7);
      await workAs(page, 'en', FINANCE, finance, 'I-14');
      await expectMonthPicker(page.getByLabel(t('en', 'i14.month')), month);
      // Cleared, no month is asked for: the export is held with its reason.
      await page.getByLabel(t('en', 'i14.month')).fill('');
      await expect(page.getByText(t('en', 'i14.badMonth'))).toBeVisible();

      await workAs(page, 'en', MAKER, maker, 'I-04');
      const loans = page
        .locator('.card')
        .filter({ hasText: t('en', 'i04l.report') })
        .last();
      await expectMonthPicker(loans.getByLabel(t('en', 'i14.from')), month);
      await expectMonthPicker(loans.getByLabel(t('en', 'i14.to')), month);
      // A range that runs backwards is not exported.
      await loans.getByLabel(t('en', 'i14.from')).fill('2026-12');
      await loans.getByLabel(t('en', 'i14.to')).fill('2026-01');
      await expect(loans.getByRole('button', { name: t('en', 'i04l.reportExport') })).toBeDisabled();

      await workAs(page, 'en', MAKER, maker, 'I-06');
      const penalties = page
        .locator('.card')
        .filter({ hasText: t('en', 'i06.report') })
        .last();
      await expectMonthPicker(penalties.getByLabel(t('en', 'i14.from')), month);
      await expectMonthPicker(penalties.getByLabel(t('en', 'i14.to')), month);
    },
  );

  test(
    'Tax (I-07, I-18, C-07, C-10, C-11): the financial year is a select of valid years opening on this year',
    { tag: ['@SRS-7-24', '@SRS-7-25', '@SRS-7-9', '@negative'] },
    async ({ page }) => {
      for (const screen of ['I-07', 'I-18', 'C-07', 'C-11']) {
        await workAs(page, 'en', TAX_MAKER, taxMaker, screen);
        const year = await expectYearSelect(page);
        await expect(year).toHaveValue(finYearOf(businessToday()));
        // A malformed year such as "2026" or "2026-99" can no longer be entered.
        await expect(year.locator('option', { hasText: /^2026$/ })).toHaveCount(0);
      }
      // C-10 opens on the year just closed (Form 16 follows the year end).
      await workAs(page, 'en', TAX_MAKER, taxMaker, 'C-10');
      const year = await expectYearSelect(page);
      const first = Number(finYearOf(businessToday()).slice(0, 4)) - 1;
      await expect(year).toHaveValue(`${first}-${String((first + 1) % 100).padStart(2, '0')}`);
      await expectTextArea(page.getByLabel(t('en', 'c10.members')), 4000);
    },
  );

  test(
    'Reporting (O-04): the summary month is a month picker on the latest locked month, else the business month',
    { tag: ['@SRS-18-28', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'O-04');
      await page.getByTestId('o04-summary-section').locator('summary').click();
      // SRS l.3501 (OD-85): the screen opens on the latest month locked in the office.
      const latest = await page.evaluate(async () => {
        const r = await fetch('/nginx/fci-payroll/v1/reports/component-summary/latest-locked', {
          credentials: 'same-origin',
        });
        return ((await r.json()) as { yymm: string | null }).yymm;
      });
      const expected = latest ? `${latest.slice(0, 4)}-${latest.slice(4)}` : businessToday().slice(0, 7);
      await expectMonthPicker(page.getByLabel(t('en', 'o04.month')), expected);
    },
  );
});
