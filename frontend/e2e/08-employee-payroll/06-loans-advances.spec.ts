import { expect, type Page, test } from '@playwright/test';
import {
  businessToday,
  decide,
  errorAlert,
  onSomePage,
  pickerMonth,
  pressDecision,
  sidebarCodes,
  t,
  workAs,
} from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql, sqlRows } from '../support/db';
import { expectToast, fillOrder, PDF } from '../support/payroll-support';

/**
 * I-04 Loans & Advances (plan 09 phase 09f; SRS-12-1, SRS-12-11, SRS-12-12, SRS-12-17,
 * SRS-12-18): the Payroll Maker of Delhi sets up a Festival Advance against its order after the
 * check (schedule and maximum new sanction), the request waits for the Checker, the Payroll Checker
 * approves it on W-01 and the loan is active with its schedule; a second Festival Advance is refused
 * (one open at a time, demo limit); Establishment/HR has no I-04. The member's pay is a fixture
 * result of March 1998 (the deduction ceiling is tested on it), the same each run.
 */
const MAKER = 'maker.delhi@fci-demo.test';
const CHECKER = 'ddo.delhi@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };
const checker = { role: 'PAYROLL_CHECKER', office: 'DH001' };

/** A DH001 member in service without any loan or loan request, with the fixture result. */
function member(): string {
  const [eis] = sqlRows(
    `SELECT e.emp_num AS eis FROM employee e JOIN org_location l ON l.location_id = e.location_id WHERE l.location_code = 'DH001' AND e.employment_status = 'IN_SERVICE' AND e.record_status = 'ACTIVE' AND NOT EXISTS (SELECT 1 FROM loan_header h WHERE h.employee_id = e.employee_id) AND NOT EXISTS (SELECT 1 FROM approval_queue q WHERE q.entity_name = 'loan_request' AND q.entity_key LIKE CONCAT(CAST(e.employee_id AS VARCHAR(20)), '|%')) ORDER BY e.emp_num DESC OFFSET 5 ROWS FETCH NEXT 1 ROWS ONLY`,
  )[0] as [string];
  const self = `(SELECT employee_id FROM employee WHERE emp_num = '${eis}')`;
  const unit = `(SELECT location_id FROM org_location WHERE location_code = 'DH001')`;
  sql(
    [
      `INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level, fin_year, cycle_status, initiated_by) SELECT '199803', ${unit}, 'DO', '1997-98', 'COMPUTED', 'e2e.fixture' FROM dual WHERE NOT EXISTS (SELECT 1 FROM payroll_cycle_instance WHERE payroll_yymm = '199803' AND org_unit_id = ${unit})`,
      `INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id, payroll_type, payroll_yymm, period_date, run_type, gross_earnings, total_deductions, net_pay, result_status, is_locked) SELECT (SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm = '199803' AND org_unit_id = ${unit}), ${unit}, ${self}, 'STAFF', '199803', DATE '1998-03-01', 'ACTUAL', 40000, 5000, 35000, 'CURRENT', 'Y' FROM dual WHERE NOT EXISTS (SELECT 1 FROM payroll_result WHERE employee_id = ${self} AND payroll_yymm = '199803')`,
    ].join(';\n'),
  );
  return eis;
}

/** The month after today, YYYYMM. */
function nextMonth(): string {
  const today = businessToday();
  const y = Number(today.slice(0, 4));
  const m = Number(today.slice(5, 7));
  return m === 12 ? `${y + 1}01` : `${y}${String(m + 1).padStart(2, '0')}`;
}

/** Fills one order block (ids `<prefix>-doc*`, SRS-G-30) and its file. */
async function fillDocument(page: Page, prefix: string) {
  await page.locator(`#${prefix}-doctype`).selectOption('OTHER');
  await page.locator(`#${prefix}-docdate`).fill(businessToday());
  await page.locator(`#${prefix}-docauth`).fill('AGM (Pay) Delhi');
  await page.locator(`#${prefix}-docfile`).setInputFiles(PDF);
}

/** The loan forms and lists stay in the closed register of the drawn I-04 (OD-121). */
async function openRegister(page: Page) {
  const section = page.locator('details[data-testid="i04-register"]');
  if (!(await section.evaluate((d) => (d as HTMLDetailsElement).open)))
    await section.locator('summary').first().click();
}

test.describe.serial('Loans and advances (I-04, plan 09)', () => {
  let eis = '';

  // SC-09-0026 (scenario of a module after 07: cited, not tagged)
  test(
    'SRS-12-17: the Maker sets up a Festival Advance against its order and the Checker approves it',
    { tag: ['@SRS-12-17', '@SRS-12-18', '@SRS-12-11', '@SRS-12-1', '@positive'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      eis = member();
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'I-04');
      const form = page.locator('.card').filter({ hasText: t('en', 'i04l.newSanction') });
      await form.getByLabel(t('en', 'm01.eis')).fill(eis);
      await form.getByLabel(t('en', 'i04l.type')).selectOption('FESTIVAL_ADVANCE');
      await form.getByLabel(t('en', 'i04l.sanctioned')).fill('20000');
      await form.getByLabel(t('en', 'i04l.instalments')).fill('4');
      await form.getByLabel(t('en', 'i04l.instalment'), { exact: true }).fill('5000');
      await form.getByLabel(t('en', 'i04l.start')).fill(pickerMonth(nextMonth()));
      await form.getByRole('button', { name: t('en', 'i04l.check') }).click();
      await expect(
        form.getByRole('table', { name: t('en', 'i04l.schedule') }).locator('tbody tr'),
      ).toHaveCount(4);
      const send = form.getByRole('button', { name: t('en', 'i04l.sendSanction') });
      await expect(send).toBeDisabled();
      await fillOrder(page, form);
      await send.click();
      await expectToast(page, t('en', 'i04l.sentToChecker'));
      await expect(page.getByRole('table', { name: t('en', 'i04l.requests') })).toContainText(eis);

      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, new RegExp(`FESTIVAL_ADVANCE of 20000 for ${eis}`), 'APPROVE');

      await page.reload();
      await openRegister(page);
      await page.getByLabel(t('en', 'm01.eis')).first().fill(eis);
      const loans = page.getByRole('table', { name: t('en', 'i04l.loans') });
      await expect(loans).toContainText(/LN-\d{4}-\d{6}/);
      await expect(loans).toContainText(t('en', 'i04l.status.ACTIVE'));
    },
  );

  test(
    'SRS-12-12: a second Festival Advance is refused while one is open',
    { tag: ['@SRS-12-12', '@negative'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable() || eis === '', NO_DB_FIXTURE);
      await workAs(page, 'en', MAKER, maker, 'I-04');
      const form = page.locator('.card').filter({ hasText: t('en', 'i04l.newSanction') });
      await form.getByLabel(t('en', 'm01.eis')).fill(eis);
      await form.getByLabel(t('en', 'i04l.type')).selectOption('FESTIVAL_ADVANCE');
      await form.getByLabel(t('en', 'i04l.sanctioned')).fill('4000');
      await form.getByLabel(t('en', 'i04l.instalments')).fill('1');
      await form.getByLabel(t('en', 'i04l.instalment'), { exact: true }).fill('4000');
      await form.getByLabel(t('en', 'i04l.start')).fill(pickerMonth(nextMonth()));
      await form.getByRole('button', { name: t('en', 'i04l.check') }).click();
      await expect(errorAlert(page)).toBeVisible();
      await expect(form.getByRole('table', { name: t('en', 'i04l.schedule') })).toHaveCount(0);
    },
  );

  test(
    'US-GAP-202: a foreclosure and a reschedule need their order beside the receipt or signed request',
    { tag: ['@US-GAP-202', '@SRS-12-18', '@SRS-12-16', '@negative'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable() || eis === '', NO_DB_FIXTURE);
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'I-04');
      await page.getByLabel(t('en', 'm01.eis')).first().fill(eis);
      const loans = page.getByRole('table', { name: t('en', 'i04l.loans') });
      const loanNumber = /LN-\d{4}-\d{6}/.exec((await loans.textContent()) ?? '')?.[0] ?? '';
      await loans.getByRole('button', { name: t('en', 'i04l.openLoan', [loanNumber]) }).click();

      const foreclose = page.getByRole('group', { name: t('en', 'i04l.foreclose') });
      const sendForeclosure = foreclose.getByRole('button', { name: t('en', 'i04l.sendForeclosure') });
      await expect(sendForeclosure).toBeDisabled();
      await expect(sendForeclosure).toHaveAttribute('title', t('en', 'i04l.changeOrderMissing'));
      // The receipt alone is not enough: the order is still missing.
      await fillDocument(page, 'fc');
      await expect(sendForeclosure).toBeDisabled();
      await expect(sendForeclosure).toHaveAttribute('title', t('en', 'i04l.changeOrderMissing'));
      await fillDocument(page, 'fco');
      await expect(sendForeclosure).toBeEnabled();

      const reschedule = page.getByRole('group', { name: t('en', 'i04l.reschedule') });
      const sendReschedule = reschedule.getByRole('button', { name: t('en', 'i04l.sendReschedule') });
      await reschedule.getByLabel(t('en', 'i04l.instalments')).fill('2');
      await reschedule.getByLabel(t('en', 'i04l.instalment'), { exact: true }).fill('7500');
      await reschedule.getByLabel(t('en', 'i04l.start')).fill(pickerMonth(nextMonth()));
      await expect(sendReschedule).toHaveAttribute('title', t('en', 'i04l.changeOrderMissing'));
      await fillDocument(page, 'rso');
      await expect(sendReschedule).toBeDisabled();
      await expect(sendReschedule).toHaveAttribute('title', t('en', 'i04l.signedRequestMissing'));

      // With both documents the foreclosure goes to the Checker; rejected there, the loan runs on.
      await sendForeclosure.click();
      await expectToast(page, t('en', 'i04l.sentToChecker'));
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, new RegExp(`Foreclosure of ${loanNumber}`), 'REJECT', 'Not yet paid in (E2E)');
    },
  );

  test(
    'SRS-12-4: a loan that still owes interest is rescheduled only with its interest instalments',
    { tag: ['@SRS-12-4', '@SRS-12-1', '@SRS-12-22', '@negative'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable() || eis === '', NO_DB_FIXTURE);
      // Fixture: the member's advance still owes 600 of interest (an interest-bearing balance).
      sql(
        `UPDATE loan_header SET interest_amount = 900, interest_outstanding = 600 WHERE employee_id = (SELECT employee_id FROM employee WHERE emp_num = '${eis}') AND loan_status = 'ACTIVE'`,
      );
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'I-04');
      await page.getByLabel(t('en', 'm01.eis')).first().fill(eis);
      const loans = page.getByRole('table', { name: t('en', 'i04l.loans') });
      const loanNumber = /LN-\d{4}-\d{6}/.exec((await loans.textContent()) ?? '')?.[0] ?? '';
      await loans.getByRole('button', { name: t('en', 'i04l.openLoan', [loanNumber]) }).click();

      const reschedule = page.getByRole('group', { name: t('en', 'i04l.reschedule') });
      const send = reschedule.getByRole('button', { name: t('en', 'i04l.sendReschedule') });
      await reschedule.getByLabel(t('en', 'i04l.instalments'), { exact: true }).fill('4');
      await reschedule.getByLabel(t('en', 'i04l.instalment'), { exact: true }).fill('5000');
      await reschedule.getByLabel(t('en', 'i04l.start')).fill(pickerMonth(nextMonth()));
      await fillDocument(page, 'rso');
      await fillDocument(page, 'rs');
      // Both documents and the principal terms, but the interest is not yet spread.
      await expect(send).toBeDisabled();
      await expect(send).toHaveAttribute('title', t('en', 'i04l.rescheduleTermsMissing'));
      const count = reschedule.getByLabel(t('en', 'i04l.interestInstalments'), { exact: true });
      const each = reschedule.getByLabel(t('en', 'i04l.interestInstalment'), { exact: true });
      await count.fill('2');
      await each.fill('0');
      await expect(send).toBeDisabled();
      await each.fill('300');
      await expect(send).toBeEnabled();
      await send.click();
      await expectToast(page, t('en', 'i04l.sentToChecker'));

      // The Checker sees it; rejected there, the loan runs on its old schedule.
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, new RegExp(`Reschedule of ${loanNumber}`), 'REJECT', 'Not agreed (E2E)');
    },
  );

  test(
    'US-GAP-198: a sanction request the Checker returns is told to the unit Payroll Makers with the reason',
    { tag: ['@US-GAP-198', '@SRS-12-7', '@positive'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const other = member();
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'I-04');
      const form = page.locator('.card').filter({ hasText: t('en', 'i04l.newSanction') });
      await form.getByLabel(t('en', 'm01.eis')).fill(other);
      await form.getByLabel(t('en', 'i04l.type')).selectOption('FESTIVAL_ADVANCE');
      await form.getByLabel(t('en', 'i04l.sanctioned')).fill('8000');
      await form.getByLabel(t('en', 'i04l.instalments')).fill('2');
      await form.getByLabel(t('en', 'i04l.instalment'), { exact: true }).fill('4000');
      await form.getByLabel(t('en', 'i04l.start')).fill(pickerMonth(nextMonth()));
      await form.getByRole('button', { name: t('en', 'i04l.check') }).click();
      await fillOrder(page, form);
      await form.getByRole('button', { name: t('en', 'i04l.sendSanction') }).click();
      await expectToast(page, t('en', 'i04l.sentToChecker'));

      const reason = `Order date mismatch ${Date.now() % 100000}`;
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, new RegExp(`FESTIVAL_ADVANCE of 8000 for ${other}`), 'RETURN', reason);

      await page.goto('/payroll/');
      const notices = page.getByRole('list', { name: t('en', 'dash.notices') });
      const notice = notices.getByRole('listitem').filter({ hasText: reason });
      await expect(notice).toContainText(t('en', 'notice.LOAN_REQUEST_RETURNED'));
      await expect(notice).toContainText(`FESTIVAL_ADVANCE for ${other}`);
      // The Checker, who returned it, is not told.
      await ddo.goto('/payroll/');
      await expect(ddo.getByRole('list', { name: t('en', 'dash.notices') }).getByText(reason)).toHaveCount(0);
    },
  );

  test(
    'SRS-12-21: the Payroll Checker approves a foreclosure only with the OTP; without it the request stays',
    { tag: ['@SRS-12-21', '@BR-LOAN-011', '@SRS-G-21', '@positive'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable() || eis === '', NO_DB_FIXTURE);
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'I-04');
      await page.getByLabel(t('en', 'm01.eis')).first().fill(eis);
      const loans = page.getByRole('table', { name: t('en', 'i04l.loans') });
      const loanNumber = /LN-\d{4}-\d{6}/.exec((await loans.textContent()) ?? '')?.[0] ?? '';
      await loans.getByRole('button', { name: t('en', 'i04l.openLoan', [loanNumber]) }).click();
      await fillDocument(page, 'fc');
      await fillDocument(page, 'fco');
      await page
        .getByRole('group', { name: t('en', 'i04l.foreclose') })
        .getByRole('button', { name: t('en', 'i04l.sendForeclosure') })
        .click();
      await expectToast(page, t('en', 'i04l.sentToChecker'));

      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      // Without the code: the OTP is asked and cancelling it leaves the request waiting.
      const item = await onSomePage(
        ddo,
        ddo.getByRole('row', { name: new RegExp(`Foreclosure of ${loanNumber}`) }).first(),
      );
      await (
        await pressDecision(ddo, item, 'APPROVE')
      )
        .getByRole('button', { name: t('en', 'w01.APPROVE') })
        .click();
      await expect(ddo.getByRole('textbox', { name: t('en', 'otp.code') })).toBeVisible();
      await ddo
        .getByRole('button', { name: t('en', 'common.cancel') })
        .last()
        .click();
      await ddo.reload();
      await expect(
        await onSomePage(ddo, ddo.getByRole('row', { name: new RegExp(`Foreclosure of ${loanNumber}`) })),
      ).toBeVisible();
      // With the code: approved and the loan is foreclosed.
      await decide(ddo, new RegExp(`Foreclosure of ${loanNumber}`), 'APPROVE', '', 'LOAN_FORECLOSURE');
      await page.reload();
      await openRegister(page);
      await page.getByLabel(t('en', 'm01.eis')).first().fill(eis);
      await expect(page.getByRole('table', { name: t('en', 'i04l.loans') })).toContainText(
        t('en', 'i04l.status.FORECLOSED'),
      );
    },
  );
  test(
    'E2E-20 / SRS-12-8: a second request of the same type is flagged for Maker review, not refused',
    { tag: ['@E2E-20', '@SRS-12-8', '@BR-LOAN-007', '@positive'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const other = member();
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'I-04');
      const newSanction = async (amount: string, instalment: string) => {
        const form = page.locator('.card').filter({ hasText: t('en', 'i04l.newSanction') });
        await form.getByLabel(t('en', 'm01.eis')).fill(other);
        await form.getByLabel(t('en', 'i04l.type')).selectOption('FESTIVAL_ADVANCE');
        await form.getByLabel(t('en', 'i04l.sanctioned')).fill(amount);
        await form.getByLabel(t('en', 'i04l.instalments')).fill('2');
        await form.getByLabel(t('en', 'i04l.instalment'), { exact: true }).fill(instalment);
        await form.getByLabel(t('en', 'i04l.start')).fill(pickerMonth(nextMonth()));
        await form.getByRole('button', { name: t('en', 'i04l.check') }).click();
        await expect(
          form.getByRole('table', { name: t('en', 'i04l.schedule') }).locator('tbody tr'),
        ).toHaveCount(2);
        await fillOrder(page, form);
        return form;
      };
      // The first request: nothing open, nothing flagged.
      const first = await newSanction('8000', '4000');
      // The flag's label names the open request's approval: any number in its place.
      const escape = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const flag = new RegExp(escape(t('en', 'i04l.openRequest', ['NUMBER'])).replace('NUMBER', '\\d+'));
      await expect(first.getByRole('checkbox', { name: flag })).toHaveCount(0);
      await first.getByRole('button', { name: t('en', 'i04l.sendSanction') }).click();
      await expectToast(page, t('en', 'i04l.sentToChecker'));

      // The second of the same type: no refusal; it is flagged and waits for the Maker's review.
      await page.reload();
      await openRegister(page);
      const second = await newSanction('6000', '3000');
      await expect(errorAlert(page)).toHaveCount(0);
      const reviewed = second.getByRole('checkbox', { name: flag });
      await expect(reviewed).toBeVisible();
      await expect(reviewed).not.toBeChecked();
      const send = second.getByRole('button', { name: t('en', 'i04l.sendSanction') });
      await expect(send).toBeDisabled();
      await reviewed.check();
      await expect(send).toBeEnabled();
      await send.click();
      await expectToast(page, t('en', 'i04l.sentToChecker'));
      const requests = page.getByRole('table', { name: t('en', 'i04l.requests') });
      await expect(requests.getByRole('row').filter({ hasText: other })).toHaveCount(2);

      // Tidy: the Checker rejects both, so the member holds nothing open.
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, new RegExp(`FESTIVAL_ADVANCE of 6000 for ${other}`), 'REJECT', 'Duplicate (E2E-20)');
      await decide(ddo, new RegExp(`FESTIVAL_ADVANCE of 8000 for ${other}`), 'REJECT', 'Not needed (E2E-20)');
    },
  );
  test('SRS-22-1: Establishment/HR has no I-04', { tag: ['@SRS-22-1', '@negative'] }, async ({ page }) => {
    await workAs(page, 'en', 'hr.delhi@fci-demo.test', { role: 'ESTABLISHMENT_HR', office: 'DH001' }, 'M-01');
    expect(await sidebarCodes(page)).not.toContain('I-04');
  });
});
