import { type Page, expect, test } from '@playwright/test';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql } from '../support/db';
import { apiProblem, apiStatus, businessToday, decide, t, workAs } from '../support/app';
import { openCapture } from '../support/labour';
import { CHECKER, MAKER, checker, maker } from '../support/payroll-support';

/**
 * Module 22 NWNP labour through the UI (docs/plan/22-nwnp-labour.md; SRS-5-2, SRS-5-3, SRS-5-6,
 * SRS-5-7, SRS-5-8, SRS-5-14; NWNP Reinstate Details, Nominee & Dependent Details): the Labour
 * Dealing Hand records an NWNP gang's work-slip with no attendance of the day and a Present But No
 * Work row after 17:30; a slip whose time frame lies within another is refused; on M-05 the Rule
 * Administrator proposes a depot's ASOR set with piece rates, units and MGW and the Rule Approver
 * approves; HR reinstates a separated labour with the order and records nominees whose shares add
 * to 100, the Payroll Checker approving both.
 * Negatives and edges: the subset duplicate, a filled row that cannot be removed, a set not after
 * the latest, a reinstatement on the last working day, nominee shares not adding to 100, a Payroll
 * Maker proposing rates or reinstating.
 *
 * Demo data V060_9 (Okhla NWNP Gang 1, L30701 ... L30704), V061_9 (NWNP operations, units,
 * relationships). Re-runnable: the chosen day's slips are cancelled, L30704 is separated again and
 * waiting proposals are rejected first.
 */
const LDH = 'ldh.depot@fci-demo.test';
const HR = 'hr.delhi@fci-demo.test';
const ADMIN = 'tax.ruleadmin@fci-demo.test';
const APPROVER = 'rule.approver@fci-demo.test';
const ldh = { role: 'LABOUR_DEALING_HAND', office: 'DP-DL01' };
const hr = { role: 'ESTABLISHMENT_HR', office: 'DH001' };
const admin = { role: 'RULE_ADMINISTRATOR', office: 'HQ001' };
const approver = { role: 'RULE_APPROVER', office: 'HQ001' };
const GANG = 'DP-DL01-G005 - Okhla NWNP Gang 1';

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

const today = businessToday();
const lastMonth = monthOf(today, 1);
const day = `${lastMonth}-${pad(10 + (run % 8))}`;
const hour = 6 + (run % 3);
const start = `${pad(hour)}:00`;
const end = `${pad(hour + 3)}:00`;

const label = (key: string, args: unknown[] = []) =>
  new RegExp(`^${t('en', key, args).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

async function chooseGang(page: Page, month: string) {
  await page
    .getByRole('combobox', { name: label('m09.gang') })
    .first()
    .selectOption({ label: GANG });
  await page.getByLabel(label('i01.month')).fill(month);
}

async function chooseOperation(page: Page, index: number, code: string) {
  const operation = page.getByLabel(label('i02.operation')).nth(index);
  const value = await operation.locator('option', { hasText: code }).first().getAttribute('value');
  await operation.selectOption(value ?? '');
}

async function member(page: Page, code: string) {
  await page.getByLabel(label('m02.search')).fill(code);
  const row = page
    .getByRole('table', { name: t('en', 'screen.M-02') })
    .getByRole('row')
    .filter({ hasText: code });
  await expect(row.first()).toBeVisible();
  return row.first();
}

test.describe.serial('Module 22 NWNP labour', () => {
  test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);

  test.beforeAll(() => {
    const gang = "(SELECT gang_id FROM labour_gang WHERE gang_code = 'DP-DL01-G005')";
    const l4 = "(SELECT employee_id FROM employee WHERE emp_num = 'L30704')";
    sql(
      `UPDATE work_slip SET slip_status = 'CANCELLED' WHERE gang_id = ${gang} AND work_slip_date = DATE '${day}' AND slip_status NOT IN ('AMENDED', 'CANCELLED');` +
        ` UPDATE approval_queue SET status = 'REJECTED' WHERE entity_name IN ('nwnp_rate_set', 'labour_reinstatement', 'labour_nomination') AND status = 'PENDING_APPROVAL';` +
        ` UPDATE labour_profile SET labour_status = 'SEPARATED', effective_to = DATE '${addDays(today, -1)}' WHERE employee_id = ${l4};` +
        ` UPDATE employee SET employment_status = 'SEPARATED' WHERE employee_id = ${l4};`,
    );
  });

  // SC-22-0005, SC-22-0006, SC-22-0012, SC-22-0013
  test(
    'SRS-5-14: an NWNP slip is saved with no attendance of the day, a Present But No Work row after 17:30; a filled row stays',
    { tag: ['@SRS-5-14', '@SRS-5-6', '@SRS-5-7', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', LDH, ldh, 'I-02');
      await openCapture(page, 'I-02');
      await chooseGang(page, lastMonth);
      await page
        .getByTestId('i02-capture')
        .getByRole('button', { name: t('en', 'action.i02.new') })
        .click();
      await page.getByLabel(label('i02.date')).fill(day);
      await chooseOperation(page, 0, 'W2P');
      await page.getByLabel(label('i02.bagType')).selectOption({ index: 1 });
      await page.getByLabel(label('i02.bags')).fill('600');
      await page.getByLabel(label('i02.start')).first().fill(start);
      await page.getByLabel(label('i02.end')).first().fill(end);
      await page
        .getByTestId('i02-capture')
        .getByRole('button', { name: t('en', 'action.i02.addRow') })
        .click();
      const remove = page
        .getByTestId('i02-capture')
        .getByRole('button', { name: t('en', 'action.i02.removeRow') });
      // SRS-5-7: only an empty row not yet saved can be removed.
      await expect(remove.first()).toBeDisabled();
      await expect(remove.first()).toHaveAttribute('title', t('en', 'i02.why.rowNotEmpty'));
      await expect(remove.nth(1)).toBeEnabled();
      await chooseOperation(page, 1, 'PBNW');
      await page.getByLabel(label('i02.pbnwReason')).selectOption('OTHER');
      await page.getByLabel(label('i02.pbnwText')).fill(`E2E no work ${run}`);
      await page.getByLabel(label('i02.start')).nth(1).fill('18:00');
      await page.getByLabel(label('i02.end')).nth(1).fill('20:00');
      await page
        .getByTestId('i02-capture')
        .getByRole('button', { name: t('en', 'action.i02.save') })
        .click();
      await expect(page.getByText(t('en', 'i02.saved'))).toBeVisible({ timeout: 15_000 });
      const register = page.getByRole('table', { name: t('en', 'i02.slips') });
      await expect(
        register
          .getByRole('row')
          .filter({ hasText: `${start}-${end}` })
          .first(),
      ).toBeVisible();
    },
  );

  // SC-22-0007
  test(
    'SRS-5-8: a slip whose time frame lies within one already entered is refused, naming it',
    { tag: ['@SRS-5-8', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', LDH, ldh, 'I-02');
      await openCapture(page, 'I-02');
      await chooseGang(page, lastMonth);
      await page
        .getByTestId('i02-capture')
        .getByRole('button', { name: t('en', 'action.i02.new') })
        .click();
      await page.getByLabel(label('i02.date')).fill(day);
      await chooseOperation(page, 0, 'T2P');
      await page.getByLabel(label('i02.bagType')).selectOption({ index: 1 });
      await page.getByLabel(label('i02.bags')).fill('100');
      await page.getByLabel(label('i02.start')).fill(`${pad(hour + 1)}:00`);
      await page.getByLabel(label('i02.end')).fill(`${pad(hour + 2)}:00`);
      await page
        .getByTestId('i02-capture')
        .getByRole('button', { name: t('en', 'action.i02.save') })
        .click();
      await expect(page.getByText(/The work repeats work-slip WS-/)).toBeVisible({ timeout: 15_000 });
    },
  );

  // SC-22-0002, SC-22-0003, SC-22-0014, SC-22-0015, SC-22-0016, SC-22-0017, SC-22-0018, SC-22-0019, SC-22-0020
  test(
    'SRS-5-2: the Rule Administrator proposes an ASOR set with piece rates, units and MGW; one not after the latest is refused; the Rule Approver approves',
    { tag: ['@SRS-5-2', '@SRS-5-3', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', ADMIN, admin, 'M-05');
      await page.getByLabel(label('m02.payrollType')).selectOption('NWNP_LABOUR');
      const latest = await page.evaluate(async () => {
        const r = await fetch('/nginx/fci-payroll/v1/labour/nwnp-rate-sets?size=100', {
          credentials: 'same-origin',
        });
        const body = (await r.json()) as {
          items: { code: string; effectiveFrom: string; version: string }[];
        };
        // SRS-5-2: "the latest" is per depot and version; the demo holds Mumbai sets as well.
        return body.items
          .filter((i) => i.version === 'ASOR' && i.code.includes('DP-DL01'))
          .map((i) => i.effectiveFrom)
          .sort()
          .at(-1);
      });
      const next = latest ? addDays(latest, 1) : addDays(today, -30);
      const mgw = `7${10 + (run % 90)}`;
      const reason = `E2E NWNP rates ${run}`;

      await page.getByRole('button', { name: t('en', 'action.m05.propose') }).click();
      const send = page.getByRole('button', { name: t('en', 'action.m05.send') });
      await page.getByLabel(label('m02.depot')).last().selectOption({ label: 'DP-DL01 - Depot Okhla' });
      await page.getByLabel(label('m05.rateVersion')).selectOption('ASOR');
      await page.getByLabel(label('m12.effective')).fill(next);
      for (const field of await page.getByLabel(/^Piece of Rate /).all()) await field.fill('52.25');
      // Every rate needs its Unit of Measure (SRS-5-3, SC-22-0019).
      await expect(send).toBeDisabled();
      for (const field of await page.getByLabel(/^MGW /).all()) await field.fill(mgw);
      await page.getByLabel(label('m02.reason')).fill(reason);
      await expect(send).toHaveAttribute('title', t('en', 'm05.why.unit'));
      for (const unit of await page.getByLabel(/^Unit of Measure /).all())
        await unit.selectOption('PER_100_BAGS');
      if (latest) {
        // SC-22-0015: not after the latest set: refused, the form kept.
        await page.getByLabel(label('m12.effective')).fill(latest);
        await send.click();
        await expect(
          page.getByText(t('en', 'error.LBR-422-RATE', ['EFFECTIVE_BEFORE_LATEST'])),
        ).toBeVisible();
        await page.getByLabel(label('m12.effective')).fill(next);
      }
      await send.click();
      await expect(page.getByText(t('en', 'm05.proposed'))).toBeVisible();

      await workAs(page, 'en', APPROVER, approver, 'W-01');
      await decide(page, new RegExp(reason), 'APPROVE');

      await workAs(page, 'en', ADMIN, admin, 'M-05');
      await page.getByLabel(label('m02.payrollType')).selectOption('NWNP_LABOUR');
      const row = page
        .getByRole('table', { name: t('en', 'screen.M-05') })
        .getByRole('row')
        .filter({ hasText: `₹ ${mgw}` })
        .first();
      await expect(row).toContainText(t('en', 'm12.state.APPROVED'));
      await expect(row).toContainText(/₹ 52\.25 \(PER_100_BAGS/);
    },
  );

  // SC-22-0040, SC-22-0041
  test(
    'NWNP Reinstate Details: HR reinstates a separated labour with the order; not on the last working day; the Payroll Checker approves',
    { tag: ['@SRS-6-9', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', HR, hr, 'M-02');
      const row = await member(page, 'L30704');
      await row.getByRole('button', { name: t('en', 'action.m02.reinstate') }).click();
      const send = page.getByRole('button', { name: t('en', 'action.m02.send') });
      const reason = `E2E reinstatement ${run}`;
      await page.getByLabel(label('m02.orderReference')).fill(`EST/RI/${run}`);
      await page.getByLabel(label('m02.orderDate')).fill(today);
      await page.getByLabel(label('m02.issuingAuthority')).fill('Area Manager, Delhi');
      await page.getByLabel(label('m09.changeReason')).fill(reason);
      await expect(send).toHaveAttribute('title', t('en', 'm02.why.file'));
      await page.getByLabel(label('m02.reinstateOrder')).setInputFiles({
        name: 'reinstatement-order.pdf',
        mimeType: 'application/pdf',
        buffer: Buffer.from('%PDF-1.7 reinstatement order'),
      });
      // On the last working day: refused by the server, the form kept.
      await page.getByLabel(label('m02.reinstateDate')).fill(addDays(today, -1));
      await send.click();
      await expect(page.getByText(t('en', 'error.LBR-422-ENTRY', ['DATE']))).toBeVisible({ timeout: 15_000 });
      await page.getByLabel(label('m02.reinstateDate')).fill(today);
      await send.click();
      await expect(page.getByText(t('en', 'm02.reinstateSent'))).toBeVisible({ timeout: 15_000 });

      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(reason), 'APPROVE');
      await workAs(page, 'en', HR, hr, 'M-02');
      const after = await member(page, 'L30704');
      await expect(after.getByRole('button', { name: t('en', 'action.m02.reinstate') })).toHaveCount(0);
    },
  );

  // SC-22-0042, SC-22-0043
  test(
    'NWNP Nominee & Dependent Details: shares not adding to 100 are held back; HR records nominees and a dependent; the Payroll Checker approves',
    { tag: ['@BR-FNF-014', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', HR, hr, 'M-02');
      const row = await member(page, 'L30701');
      await row.getByRole('button', { name: t('en', 'm02.nominees'), exact: true }).click();
      await page.getByRole('button', { name: t('en', 'action.m02.nominees') }).click();
      const reason = `E2E nominees ${run}`;
      await page.getByLabel(label('m02.nomineeName', [1])).fill('Sunita Yadav');
      await page.getByLabel(label('m02.nomineeRelationship', [1])).selectOption('SPOUSE');
      await page.getByLabel(label('m02.nomineeShare', [1])).fill('60');
      await page.getByRole('button', { name: t('en', 'action.m02.addNominee') }).click();
      await page.getByLabel(label('m02.nomineeName', [2])).fill('Ravi Yadav');
      await page.getByLabel(label('m02.nomineeRelationship', [2])).selectOption('SON');
      await page.getByLabel(label('m02.nomineeShare', [2])).fill('30');
      await page.getByLabel(label('m09.changeReason')).fill(reason);
      const send = page.getByRole('button', { name: t('en', 'action.m02.send') });
      await expect(send).toBeDisabled();
      await expect(send).toHaveAttribute('title', t('en', 'm02.why.shareTotal'));
      await page.getByLabel(label('m02.nomineeShare', [2])).fill('40');
      await page.getByRole('button', { name: t('en', 'action.m02.addDependent') }).click();
      await page.getByLabel(label('m02.dependentName', [1])).fill('Pooja Yadav');
      await page.getByLabel(label('m02.dependentRelationship', [1])).selectOption('DAUGHTER');
      await page.getByLabel(label('m02.dependentBirth', [1])).fill('2012-04-05');
      await send.click();
      await expect(page.getByText(t('en', 'm02.nominationSent'))).toBeVisible({ timeout: 15_000 });
      await expect(page.getByRole('button', { name: t('en', 'action.m02.nominees') })).toBeDisabled();

      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(reason), 'APPROVE');
      await workAs(page, 'en', HR, hr, 'M-02');
      const again = await member(page, 'L30701');
      await again.getByRole('button', { name: t('en', 'm02.nominees'), exact: true }).click();
      const table = page.getByRole('table', { name: t('en', 'm02.nominationsOf', ['L30701']) });
      await expect(table.getByRole('row').filter({ hasText: 'Sunita Yadav' })).toContainText('60%');
      await expect(table.getByRole('row').filter({ hasText: 'Ravi Yadav' })).toContainText('40%');
      await expect(table.getByRole('row').filter({ hasText: 'Pooja Yadav' })).toContainText(
        t('en', 'm02.dependent'),
      );
    },
  );

  test(
    'SRS-5-2: a Payroll Maker neither proposes NWNP rates nor records nominees',
    { tag: ['@SRS-5-2', '@SRS-G-25', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'M-05');
      await page.getByLabel(label('m02.payrollType')).selectOption('NWNP_LABOUR');
      const propose = page.getByRole('button', { name: t('en', 'action.m05.propose') });
      await expect(propose).toBeDisabled();
      await expect(propose).toHaveAttribute('title', t('en', 'reason.ruleAdminOnly'));
      expect(
        await apiStatus(page, '/v1/labour/nwnp-rate-sets', 'POST', {
          depotLocationId: 1,
          version: 'ASOR',
          effectiveFrom: '2030-01-01',
          lines: [{ operationCode: 'W2P', pieceOfRate: 1, unitOfMeasure: 'PER_100_BAGS' }],
          mgw: [{ designationId: 1, mgw: 1 }],
          reason: 'E2E',
        }),
      ).toBe(403);
      expect(
        await apiStatus(page, '/v1/labour/members/1/nominations', 'POST', {
          nominees: [],
          dependents: [],
          reason: 'E2E',
        }),
      ).toBe(403);
      expect(
        await apiProblem(page, '/v1/labour/members/1/nominations', 'POST', {
          nominees: [],
          dependents: [],
          reason: 'E2E',
        }),
      ).toMatchObject({ status: 403 });
    },
  );
});
