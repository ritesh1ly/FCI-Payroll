import { type Page, expect, test } from '@playwright/test';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql } from '../support/db';
import { apiProblem, apiStatus, businessToday, decide, t, workAs } from '../support/app';
import { openCapture } from '../support/labour';
import { CHECKER, MAKER, checker, maker } from '../support/payroll-support';

/**
 * Module 21 DPS labour through the UI (docs/plan/21-dps-labour.md; SRS-4-1, SRS-4-5, SRS-2-34,
 * SRS-4-7, SRS-4-13, SRS-4-11, SRS-22-1): the Labour Dealing Hand marks the DPS gang in the DPS code
 * set, the split-week Sunday is marked absent, booking-slips go one by one to the Depot Manager who
 * approves or returns them, the month waits for every approval; on M-05 the Rule Administrator
 * proposes a DPS rate set and the Rule Approver approves it, ending the set before.
 * Negatives and edges: a repeated booking-slip, a Payroll Maker or Depot Manager entering, the
 * Payroll Checker never deciding a booking-slip, a GoI rate without its source, a set not after the
 * latest, a second proposal while one waits, a Payroll Maker proposing rates.
 *
 * Demo data V058_9 (Okhla DPS Gang 1, L20701 ... L20704, dm.okhla@), V059_9 (DPS rates). Re-runnable:
 * the chosen days' booking-slips are cancelled and a waiting rate proposal is rejected first.
 */
const LDH = 'ldh.depot@fci-demo.test';
const DM = 'dm.okhla@fci-demo.test';
const ADMIN = 'tax.ruleadmin@fci-demo.test';
const APPROVER = 'rule.approver@fci-demo.test';
const ldh = { role: 'LABOUR_DEALING_HAND', office: 'DP-DL01' };
const dm = { role: 'DEPOT_MANAGER', office: 'DP-DL01' };
const admin = { role: 'RULE_ADMINISTRATOR', office: 'HQ001' };
const approver = { role: 'RULE_APPROVER', office: 'HQ001' };
const GANG = 'DP-DL01-G003 - Okhla DPS Gang 1';
const MEMBERS = ['L20701', 'L20702', 'L20703', 'L20704'];

const run = Math.floor(Math.random() * 9000) + 1000;
const pad = (n: number) => String(n).padStart(2, '0');

function monthOf(day: string, back: number): string {
  const [y, m] = day.split('-').map(Number) as [number, number];
  const d = new Date(Date.UTC(y, m - 1 - back, 1));
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}`;
}

function addDays(day: string, n: number): string {
  const d = new Date(`${day}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

const lastMonth = monthOf(businessToday(), 1);
const first = `${lastMonth}-${pad(10 + (run % 8))}`;
const second = addDays(first, 1);
const hour = 6 + (run % 10);
const start = `${pad(hour)}:00`;
const end = `${pad(hour + 2)}:00`;

/** A past month (four or more back) whose first Sunday falls before the 7th: a split week. */
function splitMonth(): { month: string; sunday: string } {
  for (let back = 4; back < 16; back++) {
    const month = monthOf(businessToday(), back);
    for (let d = 1; d <= 6; d++) {
      const day = `${month}-${pad(d)}`;
      if (new Date(`${day}T00:00:00Z`).getUTCDay() === 0) return { month, sunday: day };
    }
  }
  throw new Error('no split week in the last year');
}

const label = (key: string, args: unknown[] = []) =>
  new RegExp(`^${t('en', key, args).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

async function chooseGang(page: Page, month: string) {
  await page
    .getByRole('combobox', { name: label('m09.gang') })
    .first()
    .selectOption({ label: GANG });
  await page.getByLabel(label('i01.month')).fill(month);
}

/** I-01: marks one day of the DPS gang, every member W unless named. */
async function markDay(page: Page, date: string, codes: Record<string, string> = {}) {
  await chooseGang(page, date.slice(0, 7));
  await page.getByRole('button', { name: t('en', 'i01.markDay', [date]) }).click();
  for (const m of MEMBERS) {
    await page
      .getByLabel(t('en', 'i01.codeOf', [m]))
      .first()
      .selectOption(codes[m] ?? 'W');
  }
  await page
    .getByTestId('i01-grid')
    .getByRole('button', { name: t('en', 'action.i01.save') })
    .click();
  await expect(page.getByText(t('en', 'i01.saved', [date])).first()).toBeVisible();
}

/** I-02: a booking-slip of one operation for every member; answers its number. */
async function bookingSlip(page: Page, date: string, op: 'W2P' | 'T2P'): Promise<string> {
  await chooseGang(page, date.slice(0, 7));
  await page
    .getByTestId('i02-capture')
    .getByRole('button', { name: t('en', 'action.i02.new') })
    .click();
  await page.getByLabel(label('i02.date')).fill(date);
  const operation = page.getByLabel(label('i02.operation'));
  const value = await operation.locator('option', { hasText: op }).first().getAttribute('value');
  await operation.selectOption(value ?? '');
  await page.getByLabel(label('i02.bagType')).selectOption({ index: 1 });
  await page.getByLabel(label('i02.bags')).fill('400');
  await page.getByLabel(label('i02.start')).fill(start);
  await page.getByLabel(label('i02.end')).fill(end);
  await page
    .getByTestId('i02-capture')
    .getByRole('button', { name: t('en', 'action.i02.save') })
    .click();
  await expect(page.getByText(t('en', 'i02.saved'))).toBeVisible();
  return slipNumber(page, op);
}

function slipRow(page: Page, op: string) {
  return page
    .getByRole('table', { name: t('en', 'i02.bookingRegister') })
    .getByRole('row')
    .filter({ hasText: new RegExp(`${op} - .*${start}-${end}`) })
    .filter({ hasNotText: t('en', 'i02.status.AMENDED') })
    .filter({ hasNotText: t('en', 'i02.status.CANCELLED') });
}

async function slipNumber(page: Page, op: string): Promise<string> {
  const text = (await slipRow(page, op).first().getByRole('cell').first().textContent()) ?? '';
  return text.trim();
}

test.describe.serial('Module 21 DPS labour', () => {
  test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);

  test.beforeAll(() => {
    const gang = "(SELECT gang_id FROM labour_gang WHERE gang_code = 'DP-DL01-G003')";
    sql(
      `UPDATE work_slip SET slip_status = 'CANCELLED' WHERE gang_id = ${gang} AND work_slip_date IN (DATE '${first}', DATE '${second}') AND slip_status NOT IN ('AMENDED', 'CANCELLED');` +
        ` UPDATE labour_attendance SET work_slip_filed_flag = 'N' WHERE gang_id = ${gang} AND attendance_date IN (DATE '${first}', DATE '${second}');` +
        ` UPDATE approval_queue SET status = 'REJECTED' WHERE entity_name = 'labour_min_wage' AND status = 'PENDING_APPROVAL';`,
    );
  });

  // SC-21-0001
  test(
    'SRS-4-1: the DPS gang is marked in the DPS code set; a Departmental-only code is not offered',
    { tag: ['@SRS-4-1', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', LDH, ldh, 'I-01');
      await openCapture(page, 'I-01');
      await chooseGang(page, lastMonth);
      await page.getByRole('button', { name: t('en', 'i01.markDay', [first]) }).click();
      const code = page.getByLabel(t('en', 'i01.codeOf', ['L20703'])).first();
      await expect(code.locator('option[value="P"]')).toHaveCount(1);
      await expect(code.locator('option[value="M"]')).toHaveCount(1);
      await expect(code.locator('option[value="V"]')).toHaveCount(0);
      await markDay(page, first, { L20704: 'P' });
      await markDay(page, second, { L20702: 'M' });
    },
  );

  // SC-21-0005, SC-21-0018
  test(
    'SRS-4-5: the first Sunday of a split week is marked absent for a labour absent all the week before',
    { tag: ['@SRS-4-5', '@edge'] },
    async ({ page }) => {
      const { sunday } = splitMonth();
      await workAs(page, 'en', LDH, ldh, 'I-01');
      await openCapture(page, 'I-01');
      for (let d = addDays(sunday, -6); d < `${sunday.slice(0, 7)}-01`; d = addDays(d, 1)) {
        await markDay(page, d, { L20703: 'A' });
      }
      await chooseGang(page, sunday.slice(0, 7));
      await page.getByRole('button', { name: t('en', 'i01.markDay', [sunday]) }).click();
      for (const m of MEMBERS)
        await page
          .getByLabel(t('en', 'i01.codeOf', [m]))
          .first()
          .selectOption('W');
      await page
        .getByTestId('i01-grid')
        .getByRole('button', { name: t('en', 'action.i01.save') })
        .click();
      await expect(page.getByText(t('en', 'i01.markedAbsent', ['L20703']))).toBeVisible();
    },
  );

  // SC-21-0007, SC-21-0014
  test(
    'SRS-2-34: booking-slips go to the Depot Manager; a repeat is refused; the month waits for every approval',
    { tag: ['@SRS-2-34', '@SRS-4-7', '@SRS-4-13', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', LDH, ldh, 'I-02');
      await openCapture(page, 'I-02');
      const one = await bookingSlip(page, first, 'W2P');
      await expect(slipRow(page, 'W2P').first()).toContainText(t('en', 'i02.dpsStatus.DRAFT'));
      // BS-2: the same gang, date and operation again is refused, the existing slip named.
      await page
        .getByTestId('i02-capture')
        .getByRole('button', { name: t('en', 'action.i02.new') })
        .click();
      await page.getByLabel(label('i02.date')).fill(first);
      const operation = page.getByLabel(label('i02.operation'));
      const w2p = await operation.locator('option', { hasText: 'W2P' }).first().getAttribute('value');
      await operation.selectOption(w2p ?? '');
      await page.getByLabel(label('i02.bagType')).selectOption({ index: 1 });
      await page.getByLabel(label('i02.bags')).fill('100');
      await page.getByLabel(label('i02.start')).fill('22:00');
      await page.getByLabel(label('i02.end')).fill('23:00');
      await page
        .getByTestId('i02-capture')
        .getByRole('button', { name: t('en', 'action.i02.save') })
        .click();
      await expect(page.getByText(t('en', 'error.LBR-409-DUPLICATE-SLIP', [one]))).toBeVisible();
      await page
        .getByRole('button', { name: t('en', 'common.cancel') })
        .last()
        .click();

      const two = await bookingSlip(page, second, 'T2P');
      // One at a time: a send in progress disables the others.
      for (const [op, no] of [
        ['W2P', one],
        ['T2P', two],
      ] as const) {
        await slipRow(page, op)
          .first()
          .getByRole('button', { name: t('en', 'action.i02.sendSlip') })
          .click();
        await expect(page.getByText(t('en', 'i02.slipSent', [no]))).toBeVisible({ timeout: 15_000 });
      }
      await expect(slipRow(page, 'W2P').first()).toContainText(t('en', 'i02.dpsStatus.SUBMITTED'));
      await expect(
        slipRow(page, 'W2P')
          .first()
          .getByRole('button', { name: t('en', 'action.i02.sendSlip') }),
      ).toHaveCount(0);
      await page
        .getByTestId('i02-capture')
        .getByRole('button', { name: t('en', 'action.i02.submit') })
        .click();
      await expect(
        // The count includes any slip an earlier run left with the Depot Manager.
        page.getByText(/\d+ booking-slip\(s\) wait for the Depot Manager/).first(),
      ).toBeVisible();

      // The Payroll Checker is not the route's checker: no booking-slip in its inbox.
      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await expect(page.getByRole('row', { name: new RegExp(one) })).toHaveCount(0);

      await workAs(page, 'en', DM, dm, 'W-01');
      await decide(page, new RegExp(one), 'APPROVE');
      await decide(page, new RegExp(two), 'RETURN', 'Bag count differs from Anna Darpan');

      // SRS-2-34: a returned booking-slip is corrected by an amendment and sent again.
      await workAs(page, 'en', LDH, ldh, 'I-02');
      await openCapture(page, 'I-02');
      await chooseGang(page, lastMonth);
      await expect(slipRow(page, 'W2P').first()).toContainText(t('en', 'i02.status.APPROVED'));
      const returned = slipRow(page, 'T2P').first();
      await expect(returned).toContainText(t('en', 'i02.dpsStatus.REJECTED'));
      await returned.getByRole('button', { name: t('en', 'action.i02.amend') }).click();
      // The amendment re-enters the work (D20-9: a replacing slip).
      const op = page.getByLabel(label('i02.operation'));
      const t2p = await op.locator('option', { hasText: 'T2P' }).first().getAttribute('value');
      await op.selectOption(t2p ?? '');
      await page.getByLabel(label('i02.bagType')).selectOption({ index: 1 });
      await page.getByLabel(label('i02.bags')).fill('380');
      await page.getByLabel(label('i02.start')).fill(start);
      await page.getByLabel(label('i02.end')).fill(end);
      await page
        .getByTestId('i02-capture')
        .getByRole('button', { name: t('en', 'action.i02.save') })
        .click();
      const dialog = page.getByRole('dialog');
      await dialog.getByRole('textbox').fill('Bags per Anna Darpan');
      await dialog.getByRole('button', { name: t('en', 'common.submit') }).click();
      await expect(page.getByText(t('en', 'i02.saved'))).toBeVisible();
      const three = await slipNumber(page, 'T2P');
      expect(three).not.toBe(two);
      await slipRow(page, 'T2P')
        .first()
        .getByRole('button', { name: t('en', 'action.i02.sendSlip') })
        .click();
      await expect(page.getByText(t('en', 'i02.slipSent', [three]))).toBeVisible({ timeout: 15_000 });
    },
  );

  test(
    'SRS-2-34: a Payroll Maker and the Depot Manager enter no booking-slip',
    { tag: ['@SRS-2-34', '@SRS-22-1', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'I-02');
      await openCapture(page, 'I-02');
      await chooseGang(page, lastMonth);
      await expect(
        page.getByTestId('i02-capture').getByRole('button', { name: t('en', 'action.i02.new') }),
      ).toBeDisabled();
      expect(await apiStatus(page, '/v1/labour/work-slips/1/submissions', 'POST', {})).toBe(403);

      await workAs(page, 'en', DM, dm, 'I-02');

      await openCapture(page, 'I-02');
      await chooseGang(page, lastMonth);
      const create = page.getByTestId('i02-capture').getByRole('button', { name: t('en', 'action.i02.new') });
      await expect(create).toBeDisabled();
      await expect(create).toHaveAttribute('title', t('en', 'reason.ldhOnly'));
      expect(await apiStatus(page, '/v1/labour/work-slips/1/submissions', 'POST', {})).toBe(403);
    },
  );

  // SC-21-0011, SC-21-0024, SC-21-0025, SC-21-0026
  test(
    'SRS-4-11: the DPS rates read with IR and GoI not configured; a set is proposed and approved, ending the one before',
    {
      tag: ['@SRS-4-11', '@SRS-4-2', '@positive'],
    },
    async ({ page }) => {
      await workAs(page, 'en', ADMIN, admin, 'M-05');
      const table = page.getByRole('table', { name: t('en', 'screen.M-05') });
      await expect(table.getByText(/DSDR - Sardar \(DPS\): ₹ 799/).first()).toBeVisible();
      const latest = await page.evaluate(async () => {
        const r = await fetch('/nginx/fci-payroll/v1/labour/rate-sets?payrollType=DPS_LABOUR&size=100', {
          credentials: 'same-origin',
        });
        const body = (await r.json()) as { items: { effectiveFrom: string; approvalState: string }[] };
        return body.items
          .filter((i) => i.approvalState === 'APPROVED')
          .map((i) => i.effectiveFrom)
          .sort()
          .at(-1);
      });
      expect(latest).toBeTruthy();
      const next = addDays(latest ?? '', 1);
      const reason = `E2E DPS rates ${run}`;

      await page.getByRole('button', { name: t('en', 'action.m05.propose') }).click();
      const send = page.getByRole('button', { name: t('en', 'action.m05.send') });
      for (const field of await page.getByLabel(/^MGW /).all()) await field.fill('825');
      await page.getByLabel(label('m05.interimRelief')).fill('25');
      await page.getByLabel(label('m05.goiRate')).fill('870');
      // A GoI rate needs its source and date.
      await expect(send).toHaveAttribute('title', t('en', 'm05.why.goiSource'));
      await page.getByLabel(label('m05.goiRateSource')).fill('GoI CLC notification');
      await page.getByLabel(label('m05.grain'), { exact: false }).last().selectOption('PER_DAY');
      await page.getByLabel(label('m02.reason')).fill(reason);
      // Not after the latest set: refused, the form kept.
      await page.getByLabel(label('m12.effective')).fill(latest ?? '');
      await send.click();
      await expect(page.getByText(t('en', 'error.LBR-422-RATE', ['EFFECTIVE_BEFORE_LATEST']))).toBeVisible();
      await page.getByLabel(label('m12.effective')).fill(next);
      await send.click();
      await expect(page.getByText(t('en', 'm05.proposed'))).toBeVisible();
      // One proposal waits at a time.
      const ids = await page.evaluate(async () => {
        const r = await fetch('/nginx/fci-payroll/v1/labour/rate-sets/designations?payrollType=DPS_LABOUR', {
          credentials: 'same-origin',
        });
        return ((await r.json()) as { id: number }[]).map((d) => d.id);
      });
      expect(
        await apiProblem(page, '/v1/labour/rate-sets', 'POST', {
          payrollType: 'DPS_LABOUR',
          effectiveFrom: addDays(next, 1),
          rates: ids.map((designationId) => ({ designationId, mgw: 830 })),
          reason: 'second',
        }),
      ).toMatchObject({ status: 422, errorCode: 'LBR-422-RATE' });

      await workAs(page, 'en', APPROVER, approver, 'W-01');
      await decide(page, new RegExp(reason), 'APPROVE');

      await workAs(page, 'en', ADMIN, admin, 'M-05');
      const rows = page.getByRole('table', { name: t('en', 'screen.M-05') }).getByRole('row');
      await expect(rows.filter({ hasText: /₹ 825/ }).first()).toContainText(t('en', 'm12.state.APPROVED'));
      await expect(rows.filter({ hasText: /₹ 825/ }).first()).toContainText(t('en', 'm05.grain.PER_DAY'));
    },
  );

  test(
    'SRS-4-11: only the Rule Administrator proposes labour rates; a Payroll Maker views',
    { tag: ['@SRS-4-11', '@SRS-G-25', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'M-05');
      const propose = page.getByRole('button', { name: t('en', 'action.m05.propose') });
      await expect(propose).toBeDisabled();
      await expect(propose).toHaveAttribute('title', t('en', 'reason.ruleAdminOnly'));
      expect(
        await apiStatus(page, '/v1/labour/rate-sets', 'POST', {
          payrollType: 'DPS_LABOUR',
          effectiveFrom: '2030-01-01',
          rates: [{ designationId: 1, mgw: 1 }],
          reason: 'E2E',
        }),
      ).toBe(403);
    },
  );
});
