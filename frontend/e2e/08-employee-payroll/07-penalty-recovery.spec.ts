import { expect, test } from '@playwright/test';
import {
  apiProblem,
  apiStatus,
  businessToday,
  decide,
  errorAlert,
  sidebarCodes,
  t,
  workAs,
  pickerMonth,
} from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql, sqlRows } from '../support/db';
import { expectToast, fillOrder } from '../support/payroll-support';

/**
 * I-06 Penalty, Recovery & Over-and-Above (plan 12 phase 12g; SRS-14-1, SRS-14-7, SRS-14-12,
 * SRS-14-15, SRS-14-2): the Payroll Maker of Delhi records a recovery order with its copy after the
 * check (the schedule within the ceiling), the Payroll Checker approves it on W-01 and the order is
 * listed with its Penalty ID; an over-and-above payment is approved likewise; Establishment/HR has
 * no I-06. The member's pay is a fixture result of March 1998 (the ceiling and floor are tested on
 * it), the same each run.
 */
const MAKER = 'maker.delhi@fci-demo.test';
const CHECKER = 'ddo.delhi@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };
const checker = { role: 'PAYROLL_CHECKER', office: 'DH001' };

/**
 * Reloads I-06 and opens its closed register again: the order, payment and request desks stay there under the
 * drawn screen (OD-121), as workAs opens it on entry.
 */
async function reloadI06(page: import('@playwright/test').Page) {
  await page.reload();
  const register = page.getByTestId('i06-register');
  await register.waitFor();
  if ((await register.getAttribute('open')) === null)
    await page.locator('[data-testid="i06-register"] > summary').click();
}

/** The locked March 1998 fixture result (gross 40,000, deductions 5,000, net 35,000) of {@code self}. */
function fixturePay(self: string) {
  const unit = `(SELECT location_id FROM org_location WHERE location_code = 'DH001')`;
  sql(
    [
      `INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level, fin_year, cycle_status, initiated_by) SELECT '199803', ${unit}, 'DO', '1997-98', 'COMPUTED', 'e2e.fixture' FROM dual WHERE NOT EXISTS (SELECT 1 FROM payroll_cycle_instance WHERE payroll_yymm = '199803' AND org_unit_id = ${unit})`,
      `INSERT INTO payroll_result (cycle_instance_id, org_unit_id, employee_id, payroll_type, payroll_yymm, period_date, run_type, gross_earnings, total_deductions, net_pay, result_status, is_locked) SELECT (SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm = '199803' AND org_unit_id = ${unit}), ${unit}, ${self}, 'STAFF', '199803', DATE '1998-03-01', 'ACTUAL', 40000, 5000, 35000, 'CURRENT', 'Y' FROM dual WHERE NOT EXISTS (SELECT 1 FROM payroll_result WHERE employee_id = ${self} AND payroll_yymm = '199803')`,
    ].join(';\n'),
  );
}

/** A DH001 member in service with the fixture result of March 1998. */
function member(): string {
  const [eis] = sqlRows(
    `SELECT e.emp_num AS eis FROM employee e JOIN org_location l ON l.location_id = e.location_id WHERE l.location_code = 'DH001' AND e.employment_status = 'IN_SERVICE' AND e.record_status = 'ACTIVE' ORDER BY e.emp_num DESC OFFSET 7 ROWS FETCH NEXT 1 ROWS ONLY`,
  )[0] as [string];
  fixturePay(`(SELECT employee_id FROM employee WHERE emp_num = '${eis}')`);
  return eis;
}

/** This run's own member for the net-pay floor checks (EIS 87nnnn), made once. */
const ownEis = `87${String(Math.floor(Math.random() * 9000) + 1000)}`;

/**
 * A DH001 member of this run alone whose only pay is the March 1998 fixture (net 35,000), so the I-06
 * net-pay floor check, which reads the latest pay, sees it: a member of the demo office has a later
 * result once C-02 has computed DH001 (05-payroll-processing runs first). A copy of a DH001 staff
 * member with its own EIS, PAN (dummy, valid format), CPF code, mobile, e-mail and bank number.
 */
function floorMember(): string {
  const d = ownEis.slice(2);
  sql(
    `INSERT INTO employee (emp_num, first_name, last_name, birth_date, gender, comp_joining_date, employee_type, emp_lbr_flag, pan_number, designation_id, grade_id, location_id, department_id, band_id, pay_pattern, pay_pattern_source, provident_fund_type, cpf_code, cpf_code_origin, parent_zone_id, employment_status, record_status, current_basic, mobile_number, email_address, bank_account_number, bank_branch_id, record_origin) SELECT '${ownEis}', 'Floor', 'Fixture', e.birth_date, e.gender, e.comp_joining_date, e.employee_type, e.emp_lbr_flag, 'FLRPE${d}F', e.designation_id, e.grade_id, e.location_id, e.department_id, e.band_id, e.pay_pattern, e.pay_pattern_source, e.provident_fund_type, 'CPF/${ownEis}', e.cpf_code_origin, e.parent_zone_id, 'IN_SERVICE', 'ACTIVE', e.current_basic, '98700${d}0', 'floor.${ownEis}@fci-demo.test', '0000${ownEis}', e.bank_branch_id, e.record_origin FROM employee e JOIN org_location l ON l.location_id = e.location_id WHERE l.location_code = 'DH001' AND e.employee_type = 'STAFF' AND e.employment_status = 'IN_SERVICE' AND e.record_status = 'ACTIVE' AND NOT EXISTS (SELECT 1 FROM employee x WHERE x.emp_num = '${ownEis}') ORDER BY e.emp_num FETCH FIRST 1 ROWS ONLY`,
  );
  fixturePay(`(SELECT employee_id FROM employee WHERE emp_num = '${ownEis}')`);
  return ownEis;
}

/** The month after today, YYYYMM. */
function nextMonth(): string {
  const today = businessToday();
  const y = Number(today.slice(0, 4));
  const m = Number(today.slice(5, 7));
  return m === 12 ? `${y + 1}01` : `${y}${String(m + 1).padStart(2, '0')}`;
}

test.describe.serial('Penalty, recovery and over-and-above (I-06, plan 12)', () => {
  test(
    'SRS-14-1: the Maker records a recovery order against its copy and the Checker approves it',
    { tag: ['@SRS-14-1', '@SRS-14-7', '@SRS-14-12', '@SRS-14-15', '@positive'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const eis = member();
      const reference = `E2E/REC/${Date.now() % 100000}`;
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'I-06');
      const form = page.locator('.card').filter({ hasText: t('en', 'i06.newOrder') });
      // Plan 12 D12-2: an employee order keyed here is a recovery order. The nature opens on Recovery; it is a
      // choice since a labour penalty of any nature is keyed here (SRS-14-1, Q-12-1), and the server refuses
      // any other nature for an employee: that is received from HRMS through I-21 (PEN-422-HRMS-ONLY).
      await expect(form.locator('#i06-nature')).toHaveValue('RECOVERY');
      expect(
        await apiProblem(page, '/v1/penalties/previews', 'POST', {
          eis,
          nature: 'INCREMENT_STOP',
          orderReference: reference,
          effectiveDate: businessToday(),
        }),
      ).toEqual({ status: 422, errorCode: 'PEN-422-HRMS-ONLY' });
      await form.getByLabel(t('en', 'i06.memberEis')).fill(eis);
      await form.getByLabel(t('en', 'i06.orderReference')).fill(reference);
      await form.getByLabel(t('en', 'i06.effectiveDate')).fill(businessToday());
      await form.getByLabel(t('en', 'i06.amount')).fill('3000');
      await form.getByLabel(t('en', 'i06.instalments')).fill('3');
      await form.getByLabel(t('en', 'i06.startYymm')).fill(pickerMonth(nextMonth()));
      await form.getByRole('button', { name: t('en', 'i06.check') }).click();
      await expect(
        form.getByRole('table', { name: t('en', 'i06.schedule') }).locator('tbody tr'),
      ).toHaveCount(3);
      const send = form.getByRole('button', { name: t('en', 'i06.send') });
      await expect(send).toBeDisabled();
      await fillOrder(page, form);
      await send.click();
      await expectToast(page, t('en', 'i06.orderSent'));
      // US-GAP-229 (SRS-14-15): the Penalty ID is given when the Maker records the order, before the Checker.
      const request = page
        .getByRole('table', { name: t('en', 'i06.requests') })
        .getByRole('row')
        .filter({ hasText: reference });
      await expect(request).toContainText(/PEN-\d{4}-\d{5}/);
      const penaltyId = /PEN-\d{4}-\d{5}/.exec((await request.textContent()) ?? '')?.[0] ?? '';

      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, new RegExp(`RECOVERY ${reference.replace(/\//g, '\\/')}`), 'APPROVE');

      await reloadI06(page);
      await page.getByLabel(t('en', 'm01.eis')).first().fill(eis);
      const orders = page.getByRole('table', { name: t('en', 'i06.orders') });
      // The approved order keeps the Penalty ID it was given at submission.
      await expect(orders).toContainText(penaltyId);
      await expect(orders).toContainText(t('en', 'i06.status.ACTIVE'));

      // US-GAP-234 (SRS-14-16): a partial revoke by a period sharing no month with the order's is refused.
      await orders.getByRole('row').filter({ hasText: penaltyId }).getByRole('button').first().click();
      const revoke = page.getByLabel(t('en', 'i06.revoke'), { exact: true });
      await revoke.getByLabel(t('en', 'i06.revokeTypeLabel')).selectOption('PARTIAL');
      await revoke.getByLabel(t('en', 'i06.waiveFrom')).fill('1990-01');
      await revoke.getByLabel(t('en', 'i06.waiveTo')).fill('1990-02');
      await revoke.getByLabel(t('en', 'i06.reason')).fill('Waived by the competent authority (E2E)');
      await fillOrder(page, revoke);
      await revoke.getByLabel(t('en', 'ord.reference')).fill(`E2E/REV/${Date.now() % 100000}`);
      await revoke.getByRole('button', { name: t('en', 'i06.sendRevoke') }).click();
      await expect(errorAlert(page).first()).toContainText("the period is outside the order's period");
    },
  );

  test(
    'SRS-14-2: an over-and-above payment is approved before posting',
    { tag: ['@SRS-14-2', '@positive'] },
    async ({ browser }) => {
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'I-06');
      const [eis, code] = sqlRows(
        `SELECT (SELECT emp_num FROM employee e JOIN org_location l ON l.location_id = e.location_id WHERE l.location_code = 'DH001' AND e.employment_status = 'IN_SERVICE' ORDER BY e.emp_num FETCH FIRST 1 ROWS ONLY) AS eis, (SELECT MIN(legacy_pay_code) FROM pay_component WHERE component_type = 'EARNING') AS code FROM dual`,
      )[0] as [string, string];
      const form = page.getByLabel(t('en', 'i06.newPayment'));
      await form.getByLabel(t('en', 'i06.memberEis')).fill(eis);
      await form.getByLabel(t('en', 'i06.payCode')).fill(String(code));
      await form.getByLabel(t('en', 'i06.amount')).fill('1500');
      await form.getByLabel(t('en', 'i06.month')).fill(pickerMonth(nextMonth()));
      await form.getByLabel(t('en', 'i06.reason')).fill('E2E special duty');
      const sanction = `E2E/SAN/${Date.now() % 100000}`;
      await form.getByLabel(t('en', 'i06.sanction')).fill(sanction);
      await form.getByRole('button', { name: t('en', 'i06.sendPayment') }).click();
      await expectToast(page, t('en', 'i06.paymentSent'));
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, new RegExp(sanction.replace(/\//g, '\\/')), 'APPROVE');
    },
  );

  test(
    "US-GAP-230: a payment the Checker rejects shows the Checker's reason to the Maker",
    { tag: ['@US-GAP-230', '@SRS-14-2', '@negative'] },
    async ({ browser }) => {
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'I-06');
      const [eis, code] = sqlRows(
        `SELECT (SELECT emp_num FROM employee e JOIN org_location l ON l.location_id = e.location_id WHERE l.location_code = 'DH001' AND e.employment_status = 'IN_SERVICE' ORDER BY e.emp_num FETCH FIRST 1 ROWS ONLY) AS eis, (SELECT MIN(legacy_pay_code) FROM pay_component WHERE component_type = 'EARNING') AS code FROM dual`,
      )[0] as [string, string];
      const form = page.getByLabel(t('en', 'i06.newPayment'));
      await form.getByLabel(t('en', 'i06.memberEis')).fill(eis);
      await form.getByLabel(t('en', 'i06.payCode')).fill(String(code));
      await form.getByLabel(t('en', 'i06.amount')).fill('900');
      await form.getByLabel(t('en', 'i06.month')).fill(pickerMonth(nextMonth()));
      await form.getByLabel(t('en', 'i06.reason')).fill('E2E honorarium');
      const sanction = `E2E/REJ/${Date.now() % 100000}`;
      await form.getByLabel(t('en', 'i06.sanction')).fill(sanction);
      await form.getByRole('button', { name: t('en', 'i06.sendPayment') }).click();
      await expectToast(page, t('en', 'i06.paymentSent'));
      const reason = 'Sanction not on file (E2E)';
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, new RegExp(sanction.replace(/\//g, '\\/')), 'REJECT', reason);

      await reloadI06(page);
      const payment = page
        .getByRole('table', { name: t('en', 'i06.payments'), exact: true })
        .getByRole('row')
        .filter({ hasText: sanction });
      await expect(payment).toContainText(t('en', 'i06.payment.REJECTED'));
      await expect(payment.getByTitle(t('en', 'i06.checkerReason'))).toHaveText(reason);
    },
  );

  test(
    'US-GAP-115 (SRS-G-54, SRS-14-14): cancelling a payment asks first, with the Maker reason; declined, nothing is sent',
    { tag: ['@US-GAP-115', '@SRS-G-54', '@SRS-EH-12', '@SRS-14-14', '@SRS-14-20', '@edge'] },
    async ({ browser }) => {
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'I-06');
      const [eis, code] = sqlRows(
        `SELECT (SELECT emp_num FROM employee e JOIN org_location l ON l.location_id = e.location_id WHERE l.location_code = 'DH001' AND e.employment_status = 'IN_SERVICE' ORDER BY e.emp_num FETCH FIRST 1 ROWS ONLY) AS eis, (SELECT MIN(legacy_pay_code) FROM pay_component WHERE component_type = 'EARNING') AS code FROM dual`,
      )[0] as [string, string];
      const form = page.getByLabel(t('en', 'i06.newPayment'));
      await form.getByLabel(t('en', 'i06.memberEis')).fill(eis);
      await form.getByLabel(t('en', 'i06.payCode')).fill(String(code));
      await form.getByLabel(t('en', 'i06.amount')).fill('700');
      await form.getByLabel(t('en', 'i06.month')).fill(pickerMonth(nextMonth()));
      await form.getByLabel(t('en', 'i06.reason')).fill('E2E cancel check');
      const sanction = `E2E/CAN/${Date.now() % 100000}`;
      await form.getByLabel(t('en', 'i06.sanction')).fill(sanction);
      await form.getByRole('button', { name: t('en', 'i06.sendPayment') }).click();
      await expectToast(page, t('en', 'i06.paymentSent'));
      const payment = page
        .getByRole('table', { name: t('en', 'i06.payments'), exact: true })
        .getByRole('row')
        .filter({ hasText: sanction });
      await payment.getByRole('button', { name: t('en', 'i06.cancelPayment') }).click();
      const dialog = page.getByRole('dialog');
      await expect(dialog).toContainText(t('en', 'i06.paymentCancelNote'));
      // Declined: the dialog closes and the payment stays as it was.
      await dialog.getByRole('button', { name: t('en', 'common.cancel'), exact: true }).click();
      await expect(dialog).toHaveCount(0);
      await expect(page.getByRole('status').filter({ hasText: /Cancellation of/ })).toHaveCount(0);
      await expect(payment).toContainText(t('en', 'i06.payment.SUBMITTED'));
      // Asked again: no reason (or only spaces), nothing can be sent.
      await payment.getByRole('button', { name: t('en', 'i06.cancelPayment') }).click();
      const confirm = dialog.getByRole('button', { name: t('en', 'i06.paymentCancelConfirm') });
      await expect(confirm).toBeDisabled();
      await dialog.getByLabel(t('en', 'common.reason')).fill('   ');
      await expect(confirm).toBeDisabled();
      // With the Maker's own reason the cancellation goes to the Checker, who sees that reason.
      const why = `Sanction withdrawn E2E ${Date.now() % 100000}`;
      await dialog.getByLabel(t('en', 'common.reason')).fill(why);
      await confirm.click();
      await expectToast(page, /Cancellation of \d+ submitted to the Checker/);
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, new RegExp(why), 'REJECT', 'Payment stands (E2E)');
    },
  );

  // SRS 14.4 l.5460: "maker, checker, hr, admin - enter: maker; approve: checker" (OD-125).
  test(
    'SRS-22-1: Establishment/HR reads I-06 and enters nothing',
    { tag: ['@SRS-22-1', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'hr.delhi@fci-demo.test',
        { role: 'ESTABLISHMENT_HR', office: 'DH001' },
        'M-01',
      );
      expect(await sidebarCodes(page)).toContain('I-06');
      expect(await apiStatus(page, '/v1/penalties/previews', 'POST', {})).toBe(403);
    },
  );

  /** An M-14 floor of Rs 34,000 in force in one month far ahead only (no other spec meets it); its YYYYMM. */
  function farFloor(offset: number): string {
    const today = businessToday();
    const index = Number(today.slice(0, 4)) * 12 + Number(today.slice(5, 7)) - 1 + offset;
    const y = Math.floor(index / 12);
    const m = String((index % 12) + 1).padStart(2, '0');
    const last = new Date(Date.UTC(y, (index % 12) + 1, 0)).getUTCDate();
    sql(
      `INSERT INTO net_pay_floor_rule (payroll_type, floor_basis, floor_value, is_confirmed, effective_from, effective_to, approval_id) SELECT 'STAFF', 'ABSOLUTE_AMOUNT', 34000, 'Y', DATE '${y}-${m}-01', DATE '${y}-${m}-${last}', (SELECT MIN(approval_id) FROM approval_queue WHERE status = 'APPROVED') FROM dual WHERE NOT EXISTS (SELECT 1 FROM net_pay_floor_rule WHERE payroll_type = 'STAFF' AND effective_from = DATE '${y}-${m}-01')`,
    );
    return `${y}${m}`;
  }

  async function belowFloorOrder(
    page: import('@playwright/test').Page,
    eis: string,
    reference: string,
    start: string,
  ) {
    const form = page.locator('.card').filter({ hasText: t('en', 'i06.newOrder') });
    await form.getByLabel(t('en', 'i06.memberEis')).fill(eis);
    await form.getByLabel(t('en', 'i06.orderReference')).fill(reference);
    await form.getByLabel(t('en', 'i06.effectiveDate')).fill(businessToday());
    await form.getByLabel(t('en', 'i06.amount')).fill('2000');
    await form.getByLabel(t('en', 'i06.instalments')).fill('1');
    await form.getByLabel(t('en', 'i06.startYymm')).fill(pickerMonth(start));
    await form.getByRole('button', { name: t('en', 'i06.check') }).click();
    await expect(form.getByLabel(t('en', 'i06.preview'))).toContainText(t('en', 'i06.belowFloorOptions'));
    return form;
  }

  test(
    'SRS-14-10: a recovery below the net-pay floor takes the Checker and then the Regional Office Checker',
    { tag: ['@SRS-14-10', '@positive'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const eis = floorMember();
      const start = farFloor(150);
      const reference = `E2E/FLR/${Date.now() % 100000}`;
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'I-06');
      const form = await belowFloorOrder(page, eis, reference, start);
      await fillOrder(page, form);
      await form.getByRole('button', { name: t('en', 'i06.send') }).click();
      await expectToast(page, t('en', 'i06.orderSent'));
      const row = new RegExp(`RECOVERY ${reference.replace(/\//g, '\\/')}`);
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, row, 'APPROVE', '', undefined, 'PENDING_APPROVAL');
      // Not applied on one approval: the order is not listed yet.
      await reloadI06(page);
      await page.getByLabel(t('en', 'm01.eis')).first().fill(eis);
      await expect(page.getByRole('table', { name: t('en', 'i06.orders') }).getByText(reference)).toHaveCount(
        0,
      );
      const region = await browser.newPage();
      await workAs(
        region,
        'en',
        'tax.checker.ro.delhi@fci-demo.test',
        { role: 'PAYROLL_CHECKER', office: 'RO-DL' },
        'W-01',
      );
      await decide(region, row, 'APPROVE');
      // Written on the second approval: the request leaves the waiting list.
      await reloadI06(page);
      await expect(
        page
          .getByRole('table', { name: t('en', 'i06.requests') })
          .getByRole('row')
          .filter({ hasText: reference }),
      ).toHaveCount(0);
    },
  );

  test(
    'SRS l.2995: at a below-floor breach the Maker defers the recovery to F&F instead; one Checker approves it',
    { tag: ['@SRS-14-10', '@SRS-14-11', '@edge'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const eis = floorMember();
      const start = farFloor(151);
      const reference = `E2E/FNF/${Date.now() % 100000}`;
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'I-06');
      const form = await belowFloorOrder(page, eis, reference, start);
      await form.getByLabel(t('en', 'i06.deferToFnf')).check();
      await fillOrder(page, form);
      await form.getByRole('button', { name: t('en', 'i06.send') }).click();
      await expectToast(page, t('en', 'i06.orderSent'));
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, new RegExp(`RECOVERY ${reference.replace(/\//g, '\\/')}`), 'APPROVE');
      await reloadI06(page);
      await page.getByLabel(t('en', 'm01.eis')).first().fill(eis);
      await expect(page.getByRole('table', { name: t('en', 'i06.orders') })).toContainText(
        t('en', 'i06.status.PAUSED_FNF'),
      );
    },
  );
});
