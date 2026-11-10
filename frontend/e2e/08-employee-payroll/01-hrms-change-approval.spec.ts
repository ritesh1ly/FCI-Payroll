import { expect, test } from '@playwright/test';
import { businessToday, closeDetail, decide, onSomePage, pressDecision, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql, sqlRows } from '../support/db';

/**
 * I-21 HRMS Change Approval (plan 16 phase 16a; SRS-1-28, SRS-1-61) at Delhi HQ (DH001). Each run receives its own
 * HRMS changes through the database fixture, as the HRMS interface (module 23) will: the Payroll Maker records the
 * order of a PAN change and sends it, the Payroll Checker approves it on W-01; a Basic Pay change is approved with the
 * OTP; Finance views the tasks without the order form. Plan 16 phase 16b (SRS-1-67, SRS-1-71): the Maker sends two
 * tasks under one order and the Checker approves both in one action; the Maker downloads the HRMS Change Register.
 * Re-runnable.
 */
const MAKER = 'maker.delhi@fci-demo.test';
const CHECKER = 'ddo.delhi@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };
const checker = { role: 'PAYROLL_CHECKER', office: 'DH001' };

/** A pending HRMS change of a Delhi member, as the interface would record it; returns its task number. */
function received(
  field: string,
  value: string,
  offset: number,
  on: { effective: string; target: string } = {
    effective: 'CURRENT_DATE',
    target: "TO_CHAR(ADD_MONTHS(CURRENT_DATE, 1), 'YYYYMM')",
  },
): string {
  const stamp = `${offset}${`${Date.now()}`.slice(-9)}`;
  const taskNo = `HCA-E2E-${field.slice(0, 3)}${stamp}`;
  sql(
    `INSERT INTO hrms_change_task (task_no, employee_id, pay_effect_field_id, task_source, hrms_request_id, new_value, effective_date, received_at, paying_unit_id, target_payroll_yymm, received_before_freeze, task_status, otp_required) SELECT '${taskNo}', e.employee_id, f.pay_effect_field_id, 'HRMS_PUSH', 'E2E-${stamp}-${field}', '${value}', ${on.effective}, CURRENT_TIMESTAMP, l.location_id, ${on.target}, 'N', 'PENDING_ORDER_DETAILS', f.otp_required FROM org_location l JOIN hrms_pay_effect_field f ON f.field_code = '${field}' JOIN (SELECT employee_id, location_id, ROW_NUMBER() OVER (PARTITION BY location_id ORDER BY emp_num) - 1 AS n FROM employee WHERE record_status = 'ACTIVE') e ON e.location_id = l.location_id AND e.n = ${offset} WHERE l.location_code = 'DH001'`,
  );
  return taskNo;
}

async function send(page: import('@playwright/test').Page, taskNo: string) {
  await page.getByRole('button', { name: t('en', 'i21.w.viewOf', [taskNo]) }).click();
  const form = page.getByRole('form', { name: t('en', 'i21.order') });
  await form.getByLabel(t('en', 'i21.orderNo')).fill('EST/2026/E2E');
  await form.getByLabel(t('en', 'i21.orderDate')).fill('2026-10-01');
  await form.getByLabel(t('en', 'i21.authority')).fill('General Manager (P), Delhi');
  // SRS-G-31 (US-GAP-26): the order copy goes with the details unless HRMS holds the order letter.
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

test.describe.serial('HRMS change approval (I-21, plan 16a)', () => {
  // SC-16-0156 (UC scenario of the KB, cited here; outside the e2e_trace review scope).
  test(
    'SRS-1-28: the maker records the order of a PAN change and the checker approves it on W-01',
    { tag: ['@SRS-1-28', '@SRS-G-31', '@positive'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const taskNo = received('PAN', 'PQRPX4321L', 20);
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'I-21');
      await expect(page.getByRole('row', { name: new RegExp(taskNo) })).toContainText('******321L');
      await send(page, taskNo);

      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, new RegExp(taskNo), 'APPROVE');

      await page.reload();
      await expect(page.getByRole('row', { name: new RegExp(taskNo) })).toContainText(
        t('en', 'i21.status.APPROVED'),
      );
      const [pan] = sqlRows(
        `SELECT e.pan_number FROM hrms_change_task x JOIN employee e ON e.employee_id = x.employee_id WHERE x.task_no = '${taskNo}'`,
      )[0] as [string];
      expect(pan).toBe('PQRPX4321L');
    },
  );

  test(
    'SRS-1-61: a Basic Pay change is approved with the OTP',
    { tag: ['@SRS-1-61', '@SRS-G-21', '@positive'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const taskNo = received('BASIC_PAY', '61900', 21);
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'I-21');
      await send(page, taskNo);
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, new RegExp(taskNo), 'APPROVE', '', 'INCREMENT_DATE_BASIC_CORRECTION');
      await page.reload();
      await expect(page.getByRole('row', { name: new RegExp(taskNo) })).toContainText(
        t('en', 'i21.status.APPROVED'),
      );
    },
  );

  test(
    'SRS-1-28: Finance views the tasks with the value locked and has no order form',
    { tag: ['@SRS-1-28', '@negative'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const taskNo = received('UAN', '100777666555', 22);
      await workAs(page, 'en', 'finance.hq@fci-demo.test', { role: 'FINANCE', office: 'HQ001' }, 'I-21');
      await page.getByRole('button', { name: t('en', 'i21.w.viewOf', [taskNo]) }).click();
      await expect(page.getByText(t('en', 'i21.locked'))).toBeVisible();
      await expect(page.getByRole('form', { name: t('en', 'i21.order') })).toHaveCount(0);
    },
  );

  test(
    'SRS-1-67: two PAN changes go under one order and the checker approves both in one action',
    { tag: ['@SRS-1-67', '@SRS-G-31', '@positive'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const first = received('PAN', 'KLMPO1357P', 23);
      const second = received('PAN', 'KLMPO2468Q', 24);
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'I-21');
      await page.getByText(t('en', 'i21.w.closed')).click();
      const bulk = (n: number) => page.getByRole('button', { name: t('en', 'i21.bulk.enter', [n]) });
      await page.getByLabel(t('en', 'i21.bulk.pickTask', [first])).check();
      await expect(bulk(1)).toBeDisabled();
      await page.getByLabel(t('en', 'i21.bulk.pickTask', [second])).check();
      await bulk(2).click();
      const form = page.getByRole('form', { name: t('en', 'i21.bulk.order') });
      await form.getByLabel(t('en', 'i21.orderNo')).fill('EST/2026/TR-E2E');
      await form.getByLabel(t('en', 'i21.orderDate')).fill('2026-10-01');
      await form.getByLabel(t('en', 'i21.authority')).fill('General Manager (P), Delhi');
      // SRS-G-31 (US-GAP-26): one order copy for every task of the order.
      await form.getByLabel(t('en', 'ord.type')).selectOption({ index: 1 });
      await form.getByLabel(t('en', 'i21.copy')).setInputFiles({
        name: 'order.pdf',
        mimeType: 'application/pdf',
        buffer: Buffer.from('%PDF-1.7\n1 0 obj\n<< >>\nendobj\n', 'ascii'),
      });
      await form.getByRole('button', { name: t('en', 'i21.submit') }).click();
      await expect(page.getByRole('status').filter({ hasText: 'HCO-' })).toBeVisible();
      await expect(
        page.getByRole('row', { name: new RegExp(first) }).filter({ hasText: 'HCO-' }),
      ).not.toHaveCount(0);

      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      await decide(ddo, new RegExp(second), 'APPROVE');

      await page.reload();
      for (const taskNo of [first, second]) {
        await expect(page.getByRole('row', { name: new RegExp(taskNo) })).toContainText(
          t('en', 'i21.status.APPROVED'),
        );
      }
    },
  );

  test(
    'SRS-1-71: the maker downloads the HRMS Change Register; Finance has no export',
    { tag: ['@SRS-1-71', '@SRS-1-30', '@positive'] },
    async ({ browser }) => {
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'I-21');
      await page.getByText(t('en', 'i21.w.closed')).click();
      await expect(page.getByRole('heading', { name: /Register:/ })).toBeVisible();
      await page
        .locator('details[open]')
        .getByRole('button', { name: t('en', 'action.export') })
        .click();
      const dialog = page.getByRole('dialog');
      await expect(dialog.getByRole('heading')).toHaveText(
        t('en', 'export.title', [t('en', 'i21.register.hrms')]),
      );
      const downloading = page.waitForEvent('download');
      await dialog.getByRole('link', { name: t('en', 'export.download') }).click();
      expect((await downloading).suggestedFilename()).toBe('hrms-change-register.xlsx');

      const finance = await browser.newPage();
      await workAs(finance, 'en', 'finance.hq@fci-demo.test', { role: 'FINANCE', office: 'HQ001' }, 'I-21');
      await finance.getByText(t('en', 'i21.w.closed')).click();
      await expect(finance.getByRole('heading', { name: /Register:/ })).toBeVisible();
      await expect(
        finance.locator('details[open]').getByRole('button', { name: t('en', 'action.export') }),
      ).toHaveCount(0);
    },
  );
  test(
    'US-GAP-276: a task shows its pay impact by month - the arrear months of a back-dated change, none for a current one',
    { tag: ['@US-GAP-276', '@SRS-1-28', '@SRS-1-58', '@edge'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const month = (back: number) => {
        const [y, m] = businessToday().split('-').map(Number) as [number, number];
        const i = y * 12 + (m - 1) + back;
        return `${Math.floor(i / 12)}-${String((i % 12) + 1).padStart(2, '0')}`;
      };
      const paid = month(1);
      const target = `'${paid.replace('-', '')}'`;
      // Effective two months back, paid next month: the arrear runs from the effective month to this month.
      const backDated = received('BASIC_PAY', '61200', 25, { effective: `DATE '${month(-2)}-01'`, target });
      // Effective in the month it is paid: nothing already paid is affected.
      const current = received('BASIC_PAY', '61300', 26, { effective: `DATE '${paid}-01'`, target });
      await workAs(page, 'en', MAKER, maker, 'I-21');
      await page.getByRole('button', { name: t('en', 'i21.w.viewOf', [backDated]) }).click();
      const impact = page.getByRole('status', { name: t('en', 'i21.impact') });
      await expect(impact).toContainText(t('en', 'i21.impact.applies', [month(-2), paid]));
      await expect(impact).toContainText(t('en', 'i21.impact.arrear', [month(-2), month(0)]));
      await page
        .getByRole('dialog')
        .getByRole('button', { name: t('en', 'common.close') })
        .first()
        .click();
      await page.getByRole('button', { name: t('en', 'i21.w.viewOf', [current]) }).click();
      await expect(impact).toContainText(t('en', 'i21.impact.applies', [paid, paid]));
      await expect(impact).toContainText(t('en', 'i21.impact.noArrear'));
    },
  );

  test(
    'Wave C review: a task HRMS superseded with a newer record is refused at approval (HRC-409-SUPERSEDED) and stays superseded when rejected',
    { tag: ['@SRS-9-23', '@SRS-1-28', '@negative'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const taskNo = received('PAN', 'KLMPS1122S', 27);
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'I-21');
      await send(page, taskNo);
      // The intake (module 23) closes the waiting task when a newer record arrives.
      sql(`UPDATE hrms_change_task SET task_status = 'SUPERSEDED' WHERE task_no = '${taskNo}'`);
      const ddo = await browser.newPage();
      await workAs(ddo, 'en', CHECKER, checker, 'W-01');
      const item = await onSomePage(ddo, ddo.getByRole('row', { name: new RegExp(taskNo) }).first());
      await (
        await pressDecision(ddo, item, 'APPROVE')
      )
        .getByRole('button', { name: t('en', 'w01.APPROVE') })
        .click();
      await expect(ddo.getByRole('status').filter({ hasText: taskNo }).last()).toContainText(
        t('en', 'error.HRC-409-SUPERSEDED', [taskNo]),
      );
      await closeDetail(ddo);
      await decide(ddo, new RegExp(taskNo), 'REJECT', 'Superseded by a newer HRMS record (E2E)');
      await page.reload();
      await expect(page.getByRole('row', { name: new RegExp(taskNo) })).toContainText(
        t('en', 'i21.status.SUPERSEDED'),
      );
    },
  );

  test(
    'SRS l.3850, l.3845 (group g2): the task list shows the HRMS reason, the request ID and how the task was opened',
    { tag: ['@SRS-1-28', '@SRS-1-71', '@positive'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const taskNo = received('UAN', '100987654321', 28);
      sql(
        `UPDATE hrms_change_task SET hrms_change_reason = 'UAN corrected by EPFO (E2E)' WHERE task_no = '${taskNo}'`,
      );
      const bare = received('UAN', '100987654322', 29);
      await workAs(page, 'en', MAKER, maker, 'I-21');
      // The drawn list has no reason column (OD-121); the task table of the closed section keeps it.
      await page.getByText(t('en', 'i21.w.closed')).click();
      const closed = page.locator('details[open]');
      const row = closed.getByRole('row', { name: new RegExp(taskNo) });
      await expect(row).toContainText('UAN corrected by EPFO (E2E)');
      await expect(row).toContainText(t('en', 'i21.source.HRMS_PUSH'));
      await expect(row).toContainText(/E2E-\d+-UAN/);
      // A change HRMS sent without a reason shows a dash, never an empty cell or "null".
      await expect(closed.getByRole('row', { name: new RegExp(bare) })).not.toContainText('null');
      await expect(closed.locator('thead').first()).toContainText(t('en', 'i21.reason'));
      await page.getByRole('button', { name: t('en', 'i21.w.viewOf', [taskNo]) }).click();
      await expect(page.locator('.fl-vocabulary')).toContainText('UAN corrected by EPFO (E2E)');
    },
  );
});
