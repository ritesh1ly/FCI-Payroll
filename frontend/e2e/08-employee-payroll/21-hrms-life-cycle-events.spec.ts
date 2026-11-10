import { expect, type Page, test } from '@playwright/test';
import { dataRows, decide, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql, sqlRows } from '../support/db';

/**
 * Life-cycle events received from HRMS through I-21 at Delhi HQ (DH001), as the e2e scenarios walk
 * them (docs/requirements/kb/07-analysis/e2e-scenarios.md):
 *
 * - E2E-16 (SRS-1-63, SRS-1-64): a suspension task opens with the subsistence percentage pre-filled,
 *   the Maker records the order, the Checker approves it; I-17 lists the event from HRMS with the
 *   subsistence and the order, M-01 shows the member Suspended; the revocation is approved the same
 *   way and the member is Active again. Not built: the invoices held on the SCR until revocation
 *   (module 23) and the revocation arrear with the Suspension / Revocation Report (PENDING 16f-rest,
 *   B-20, UI-G2-I17-REPORT). The subsistence the engine pays is HrmsEventIT.
 * - E2E-17 (SRS-1-64, SRS-1-7): an absconding task approved on I-21 flags the member (Pay Status
 *   Absconding, the HRMS flag Yes); the payment held with its X-05 entry is raised by the Actual Run
 *   (HrmsEventIT, ResultWriter). The adjustment of pending recoveries waits for FCI (PENDING GAP-FCI-1).
 * - E2E-10 (SRS-1-22, SRS-1-23, SRS-1-60, SRS-22-12): a date-of-birth change whose new retirement date
 *   is already past is approved by the Payroll Checker and then by HQ Establishment on W-01, with no
 *   OTP at either level and no other approver; the new dates apply together. CPF / EPS effects through
 *   arrears wait (PENDING 16f-rest).
 *
 * Each run receives its own HRMS changes through the database fixture (as the HRMS interface, module
 * 23, will) for members no other spec uses, and puts the members back, so it is re-runnable.
 */
const MAKER = 'maker.delhi@fci-demo.test';
const CHECKER = 'ddo.delhi@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };
const checker = { role: 'PAYROLL_CHECKER', office: 'DH001' };

/**
 * A DH001 member in service with no sign-in of its own, by position from the end of the list. The
 * demo office has about a dozen such members (seeds V002 / V077): positions 8 to 10 stay clear of
 * the ones other specs name (889001, 871901, 1099xx, 106958, 105220).
 */
function member(fromEnd: number): { id: string; eis: string } {
  const [[id, eis]] = sqlRows(
    `SELECT e.employee_id, e.emp_num FROM employee e JOIN org_location l ON l.location_id = e.location_id WHERE l.location_code = 'DH001' AND e.record_status = 'ACTIVE' AND e.employment_status = 'IN_SERVICE' AND e.absconding_flag = 'N' AND e.employee_type = 'STAFF' AND NOT EXISTS (SELECT 1 FROM app_user u WHERE u.employee_id = e.employee_id) ORDER BY e.emp_num DESC OFFSET ${fromEnd} ROWS FETCH NEXT 1 ROWS ONLY`,
  ) as [[string, string]];
  return { id, eis };
}

/** A pending HRMS change of the member, as the interface would record it; returns its task number. */
function received(employeeId: string, field: string, value: string): string {
  const stamp = `${Date.now()}`.slice(-9);
  const taskNo = `HCA-E2E-LC${field.slice(0, 3)}${stamp}`;
  sql(
    `INSERT INTO hrms_change_task (task_no, employee_id, pay_effect_field_id, task_source, hrms_request_id, new_value, effective_date, received_at, paying_unit_id, target_payroll_yymm, received_before_freeze, task_status, otp_required) SELECT '${taskNo}', e.employee_id, f.pay_effect_field_id, 'HRMS_PUSH', 'E2E-LC-${stamp}-${field}', '${value}', CURRENT_DATE, CURRENT_TIMESTAMP, e.location_id, TO_CHAR(ADD_MONTHS(CURRENT_DATE, 1), 'YYYYMM'), 'N', 'PENDING_ORDER_DETAILS', f.otp_required FROM employee e JOIN hrms_pay_effect_field f ON f.field_code = '${field}' WHERE e.employee_id = ${employeeId}`,
  );
  return taskNo;
}

/** The Maker records the order of the task with its copy and sends it to the Checker. */
async function send(page: Page, taskNo: string, orderNo: string) {
  await page.goto('/payroll/?s=I-21');
  await page.getByRole('button', { name: t('en', 'i21.w.viewOf', [taskNo]) }).click();
  const form = page.getByRole('form', { name: t('en', 'i21.order') });
  await form.getByLabel(t('en', 'i21.orderNo')).fill(orderNo);
  await form.getByLabel(t('en', 'i21.orderDate')).fill('2026-10-01');
  await form.getByLabel(t('en', 'i21.authority')).fill('General Manager (P), Delhi');
  await form.getByLabel(t('en', 'ord.type')).selectOption({ index: 1 });
  await form.getByLabel(t('en', 'i21.copy')).setInputFiles({
    name: 'order.pdf',
    mimeType: 'application/pdf',
    buffer: Buffer.from('%PDF-1.7\n1 0 obj\n<< >>\nendobj\n', 'ascii'),
  });
  await form.getByRole('button', { name: t('en', 'i21.submit') }).click();
  await expect(page.getByRole('status').filter({ hasText: taskNo })).toContainText(
    t('en', 'i21.submitted', [taskNo]),
  );
}

/** The I-17 history of one member, newest first. */
async function history(page: Page, eis: string) {
  await page.goto('/payroll/?s=I-17');
  await page.getByText(t('en', 'evt.employeeHistory')).first().click();
  await page.getByLabel(t('en', 'evt.eis')).fill(eis);
  await page.getByRole('button', { name: t('en', 'evt.show') }).click();
  return page.locator('table.t').last();
}

/** The M-01 control group of one member. */
async function control(page: Page, eis: string) {
  await page.goto('/payroll/?s=M-01');
  await page.getByLabel(t('en', 'wf.searchInList')).fill(eis);
  const list = page.getByRole('table', { name: t('en', 'screen.M-01') });
  await expect(dataRows(list)).toHaveCount(1);
  await list.getByRole('button', { name: /View record/ }).click();
  return page.getByLabel(t('en', 'm01.group.control'), { exact: true });
}

const payStatus = (code: string) => `${code} - ${t('en', `m01.payStatus.${code}`)}`;

test.describe.serial('HRMS life-cycle events through I-21 (E2E-16, E2E-17, E2E-10)', () => {
  // SC-16-0185 (UC scenario of the KB, cited here; outside the e2e_trace review scope).
  test(
    'E2E-16: a suspension is approved on I-21 with its subsistence and order, the member is Suspended; the revocation makes the member Active again',
    { tag: ['@E2E-16', '@SRS-1-63', '@SRS-1-64', '@SRS-G-45', '@positive'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const who = member(8);
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'I-21');
      const suspension = received(who.id, 'SUSPENSION', '50');
      // SRS-G-45: the value is pre-filled from HRMS, never keyed.
      await page.reload();
      await expect(page.getByRole('row', { name: new RegExp(suspension) })).toContainText('50');
      const order = `DISC/E2E/${Date.now()}`;
      await send(page, suspension, order);

      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, new RegExp(suspension), 'APPROVE');

      const events = await history(page, who.eis);
      const row = events.getByRole('row').filter({ hasText: 'Suspension' }).first();
      await expect(row).toContainText(/subsistence 50(?:\.00)?%/);
      await expect(row).toContainText(order);
      await expect(row).toContainText(t('en', 'evt.source.HRMS'));
      await expect(row.getByRole('link', { name: /I-21 task \d+/ })).toBeVisible();
      await expect(await control(page, who.eis)).toContainText(payStatus('SUSPENDED'));

      // Revocation, approved the same way: the member is Active again.
      const revocation = received(who.id, 'REVOCATION', 'Y');
      await send(page, revocation, `${order}/R`);
      await ddo.goto('/payroll/?s=W-01');
      await decide(ddo, new RegExp(revocation), 'APPROVE');
      const after = await history(page, who.eis);
      await expect(
        after.getByRole('row').filter({ hasText: 'Revocation of suspension' }).first(),
      ).toContainText(`${order}/R`);
      await expect(await control(page, who.eis)).toContainText(payStatus('ACTIVE'));
      expect(sqlRows(`SELECT employment_status FROM employee WHERE employee_id = ${who.id}`)[0]?.[0]).toBe(
        'IN_SERVICE',
      );
    },
  );

  test(
    'E2E-17: an absconding approved on I-21 flags the member Absconding with the HRMS flag set',
    { tag: ['@E2E-17', '@SRS-1-64', '@SRS-1-7', '@positive'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const who = member(9);
      const audited = () =>
        Number(
          sqlRows(
            `SELECT COUNT(*) FROM audit_event WHERE entity_name = 'employee' AND entity_key = '${who.eis}' AND field_name = 'absconding_flag' AND new_value = 'Y' AND approval_id IS NOT NULL`,
          )[0]?.[0] ?? '0',
        );
      // Re-runnable: an earlier run of the spec on the same stack audited its own flag change.
      const auditedBefore = audited();
      try {
        const page = await browser.newPage();
        await workAs(page, 'en', MAKER, maker, 'I-21');
        const absconding = received(who.id, 'ABSCONDING', 'Y');
        await send(page, absconding, `DISC/E2E/ABS/${Date.now()}`);
        const ddo = await browser.newPage();
        await workAs(ddo, 'en', CHECKER, checker, 'W-01');
        await decide(ddo, new RegExp(absconding), 'APPROVE');

        const flags = await control(page, who.eis);
        await expect(flags).toContainText(payStatus('ABSCONDING'));
        await expect(
          flags.getByText(t('en', 'm01.abscondingFlag')).locator('xpath=following-sibling::*[1]'),
        ).toContainText(t('en', 'common.yes'));
        const events = await history(page, who.eis);
        await expect(events.getByRole('row').filter({ hasText: 'Absconding' }).first()).toContainText(
          t('en', 'evt.source.HRMS'),
        );
        // R-AUD: the flag change is audited with the approval (one row for this approval).
        expect(audited()).toBe(auditedBefore + 1);
      } finally {
        // Re-runnable: the member returns to service (the demo data stays usable for other specs).
        sql(
          `UPDATE employee SET absconding_flag = 'N', employment_status = 'IN_SERVICE', row_version = row_version + 1 WHERE employee_id = ${who.id}`,
        );
      }
    },
  );

  // SC-16-0164 (UC scenario of the KB, cited here; outside the e2e_trace review scope).
  test(
    'E2E-10: a DOB change with a past retirement date is approved by the Payroll Checker then HQ Establishment, without OTP',
    { tag: ['@E2E-10', '@SRS-1-22', '@SRS-1-23', '@SRS-1-60', '@SRS-22-12', '@positive'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const who = member(10);
      const [[birth, retirement]] = sqlRows(
        `SELECT TO_CHAR(birth_date, 'YYYY-MM-DD'), TO_CHAR(retirement_date, 'YYYY-MM-DD') FROM employee WHERE employee_id = ${who.id}`,
      ) as [[string, string]];
      // sqlRows reads an SQL NULL as the text 'null' (a member with no retirement date on record).
      try {
        const page = await browser.newPage();
        await workAs(page, 'en', MAKER, maker, 'I-21');
        const dob = received(who.id, 'DATE_OF_BIRTH', '1962-05-20');
        await page.reload();
        await page.getByRole('button', { name: t('en', 'i21.w.viewOf', [dob]) }).click();
        // SRS-1-23: the recalculated dates are shown before anyone decides.
        await expect(page.getByText(t('en', 'i21.dob.past'))).toBeVisible();
        await expect(
          // The screen shows the dates as dd Mon yyyy.
          page.getByText(t('en', 'i21.dob.dates', ['20 May 1962', '31 May 2022', '§']).split('§')[0] ?? ''),
        ).toBeVisible();
        await send(page, dob, `EST/E2E/DOB/${Date.now()}`);

        // Level 1: the Payroll Checker, no OTP asked; the task goes on to HQ Establishment.
        const ddo = await browser.newPage();
        await workAs(ddo, 'en', CHECKER, checker, 'W-01');
        await decide(ddo, new RegExp(dob), 'APPROVE', '', undefined, 'PENDING_APPROVAL');
        await expect(ddo.getByRole('textbox', { name: t('en', 'otp.code') })).toHaveCount(0);
        await page.goto('/payroll/?s=I-21');
        await expect(page.getByRole('row', { name: new RegExp(dob) })).toContainText(
          t('en', 'i21.status.PENDING_HQ_ESTABLISHMENT'),
        );
        expect(sqlRows(`SELECT birth_date FROM employee WHERE employee_id = ${who.id}`)[0]?.[0]).toContain(
          birth,
        );

        // Level 2: HQ Establishment on W-01, no OTP, and no further approver.
        const hq = await browser.newPage();
        await workAs(hq, 'en', 'hr.hq@fci-demo.test', { role: 'ESTABLISHMENT_HR', office: 'HQ001' }, 'W-01');
        await decide(hq, new RegExp(dob), 'APPROVE');
        await expect(hq.getByRole('textbox', { name: t('en', 'otp.code') })).toHaveCount(0);
        await page.reload();
        await expect(page.getByRole('row', { name: new RegExp(dob) })).toContainText(
          t('en', 'i21.status.APPROVED'),
        );
        const [[newBirth, newRetirement, hqUser]] = sqlRows(
          `SELECT TO_CHAR(e.birth_date, 'YYYY-MM-DD'), TO_CHAR(e.retirement_date, 'YYYY-MM-DD'), x.hq_estab_user FROM employee e JOIN hrms_change_task x ON x.employee_id = e.employee_id WHERE x.task_no = '${dob}'`,
        ) as [[string, string, string]];
        expect([newBirth, newRetirement, hqUser]).toEqual([
          '1962-05-20',
          '2022-05-31',
          'hr.hq@fci-demo.test',
        ]);
      } finally {
        sql(
          `UPDATE employee SET birth_date = DATE '${birth}', retirement_date = ${retirement && retirement !== 'null' ? `DATE '${retirement}'` : 'NULL'}, row_version = row_version + 1 WHERE employee_id = ${who.id}`,
        );
      }
    },
  );
});
