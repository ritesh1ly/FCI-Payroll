import { expect, type Page, test } from '@playwright/test';
import { apiStatus, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql, sqlRows } from '../support/db';

/**
 * I-08 action "Undo event" (SRS-21-6; E2E-05 step 2, E2E-24): the Payroll Maker asks for the
 * controlled undo of an approved arrear event before EBS posting, with a reason; the request waits
 * on route C02_UNDO for the Payroll Checker and the Administrator's OTP. Any other event offers the
 * action disabled with its reason; a posted event is corrected by a new arrear, never undone.
 *
 * Data: no demo event is APPROVED into an open cycle, so the H2 database fixture writes two events
 * of the Punjab Region RO-PB (a unit no suite computes; its current month is INITIATED by seed
 * V077_22): one APPROVED into this month, one POSTED. Each run writes its own, by reference.
 */
const MAKER = 'pm.ropb@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'RO-PB' };
const FINANCE = 'fin.ropb@fci-demo.test';
const finance = { role: 'FINANCE', office: 'RO-PB' };
const RUN = Date.now();
const APPROVED = `E2E-UNDO-${RUN}`;
const POSTED = `E2E-POSTED-${RUN}`;

function fixture() {
  const unit = `(SELECT location_id FROM org_location WHERE location_code = 'RO-PB')`;
  const month = `(SELECT payroll_yymm FROM payroll_cycle_instance WHERE org_unit_id = ${unit} AND cycle_status = 'INITIATED' AND initiated_by = 'seed.demo')`;
  const event = (reference: string, status: string) =>
    `INSERT INTO arrear_event (event_type_id, event_reference, effective_from, scope_payroll_type, scope_location_id,` +
    ` scope_from_yymm, source_module, event_status, posted_to_yymm, raised_by)` +
    ` SELECT t.event_type_id, '${reference}', DATE '2026-05-01', 'STAFF', ${unit}, '202605', 'RATE_CHANGE', '${status}',` +
    ` ${month}, 'e2e.fixture' FROM arrear_event_type t WHERE t.event_code = 'DA_REVISION'`;
  const detail = (reference: string, status: string) =>
    `INSERT INTO arrear_detail (arrear_event_id, employee_id, component_id, differential_yymm, original_amount,` +
    ` recomputed_amount, arrear_amount, arrear_status, period_date)` +
    ` SELECT e.arrear_event_id, m.employee_id, c.component_id, '202605', 10000, 10500, 500, '${status}', DATE '2026-05-01'` +
    ` FROM arrear_event e, employee m, pay_component c WHERE e.event_reference = '${reference}' AND m.emp_num = '119901'` +
    ` AND c.legacy_pay_code = 111`;
  const member = (reference: string) =>
    `INSERT INTO arrear_event_member (arrear_event_id, employee_id) SELECT e.arrear_event_id, m.employee_id` +
    ` FROM arrear_event e, employee m WHERE e.event_reference = '${reference}' AND m.emp_num = '119901'`;
  sql(
    [
      event(APPROVED, 'APPROVED'),
      member(APPROVED),
      detail(APPROVED, 'APPROVED'),
      event(POSTED, 'POSTED'),
      member(POSTED),
      detail(POSTED, 'POSTED'),
    ].join(';\n'),
  );
}

/** As drawn, Undo event sits over the register and acts on the selected row (OD-121). */
async function selectEvent(page: Page, reference: string) {
  await page.getByLabel(t('en', 'page.size')).first().selectOption('100');
  const row = page
    .getByRole('table', { name: t('en', 'i08.registerTitle') })
    .locator(`tr[data-reference="${reference}"]`);
  await expect(row).toBeVisible();
  await row.locator('td').first().click();
  await expect(row).toHaveAttribute('aria-selected', 'true');
}

test.describe.serial('I-08 controlled undo of an arrear event (SRS-21-6)', () => {
  test.beforeAll(() => {
    if (dbFixtureAvailable()) fixture();
  });

  test(
    'SRS-21-6: the Maker asks the undo of an approved event with a reason; it waits for the Checker and the Administrator',
    { tag: ['@SRS-21-6', '@E2E-05', '@E2E-24', '@positive'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      await workAs(page, 'en', MAKER, maker, 'I-08');
      await selectEvent(page, APPROVED);
      const undo = page.getByRole('button', { name: t('en', 'action.i08.undo') });
      await expect(undo).toBeEnabled();
      await undo.click();
      const dialog = page.getByRole('dialog', { name: t('en', 'i08.undo.title', [APPROVED]) });
      await expect(dialog.getByText(t('en', 'i08.undo.note'))).toBeVisible();
      const confirm = dialog.getByRole('button', { name: t('en', 'action.i08.undo') });
      await expect(confirm).toBeDisabled();
      await dialog.getByLabel(t('en', 'common.reason')).fill(`E2E ${RUN}: DA order withdrawn`);
      await confirm.click();
      await expect(page.getByRole('status').filter({ hasText: /Undo requested/ })).toContainText(
        /Undo requested \(approval \d+\)/,
      );
      await expect(dialog).toBeHidden();
      // The request waits on route C02_UNDO; the event is unchanged until it is authorised.
      const id = sqlRows(
        `SELECT arrear_event_id FROM arrear_event WHERE event_reference = '${APPROVED}'`,
      )[0]?.[0];
      const waiting = sqlRows(
        `SELECT status FROM approval_queue WHERE entity_key = 'arrear_event:${id}' ORDER BY approval_id DESC FETCH FIRST 1 ROWS ONLY`,
      );
      expect(waiting[0]?.[0]).toBe('PENDING_APPROVAL');
      const status = sqlRows(`SELECT event_status FROM arrear_event WHERE event_reference = '${APPROVED}'`);
      expect(status[0]?.[0]).toBe('APPROVED');
    },
  );

  test(
    'SRS-21-6: a posted event is never undone - the action is disabled with its reason and the API refuses it',
    { tag: ['@SRS-21-6', '@E2E-05', '@negative'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      await workAs(page, 'en', MAKER, maker, 'I-08');
      await selectEvent(page, POSTED);
      // Drawn enabled for every role (WF-073): pressing it says why and sends nothing.
      await page.getByRole('button', { name: t('en', 'action.i08.undo') }).click();
      await expect(page.getByRole('status').filter({ hasText: t('en', 'i08.why.undo') })).toBeVisible();
      await expect(page.getByRole('dialog')).toHaveCount(0);
      const id = sqlRows(
        `SELECT arrear_event_id FROM arrear_event WHERE event_reference = '${POSTED}'`,
      )[0]?.[0];
      const period =
        sqlRows(`SELECT posted_to_yymm FROM arrear_event WHERE event_reference = '${POSTED}'`)[0]?.[0] ?? '';
      // The API refuses it too: a posted event is no undoable transaction of the open cycle.
      expect(
        await apiStatus(page, `/v1/cycles/${period.slice(0, 4)}-${period.slice(4)}/undo-requests`, 'POST', {
          transactionEntity: 'arrear_event',
          transactionKey: String(id),
          reason: 'E2E posted',
        }),
      ).toBe(404);
    },
  );

  test(
    "SRS-21-6, SRS-G-25: Finance reads the event; the undo is the Payroll Maker's only",
    { tag: ['@SRS-21-6', '@SRS-G-25', '@negative'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      await workAs(page, 'en', FINANCE, finance, 'I-08');
      await selectEvent(page, APPROVED);
      // Drawn enabled for every role (WF-073): pressing it says why and sends nothing.
      await page.getByRole('button', { name: t('en', 'action.i08.undo') }).click();
      await expect(page.getByRole('status').filter({ hasText: t('en', 'i08.why.undoRight') })).toBeVisible();
      await expect(page.getByRole('dialog')).toHaveCount(0);
    },
  );
});
