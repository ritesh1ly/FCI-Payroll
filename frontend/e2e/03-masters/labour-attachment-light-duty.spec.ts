import { type Page, expect, test } from '@playwright/test';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql } from '../support/db';
import { businessToday, decide, t, workAs } from '../support/app';
import { CHECKER, checker } from '../support/payroll-support';

/**
 * E2E-27 and E2E-28 of the KB end-to-end scenarios (docs/requirements/kb/07-analysis/e2e-scenarios.md
 * l.569-570, l.591-592; test-scope batch E, 2026-11-04):
 * - E2E-27 (SRS-2-47): HR attaches a handling labour to another gang for a date range on M-09, the
 *   Payroll Checker approves; an attachment overlapping it is refused (one gang a day).
 * - E2E-28 (SRS-2-49): HR moves a handling labour to Permanent Light Duty on M-02 with its order:
 *   Protected pay needs its Basic; the Payroll Checker approves and M-02 shows the new designation.
 *
 * Fixture gangs and labour per run (E2E_DB_URL fixture: fresh codes and PANs, never a demo member).
 */
const HR = 'hr.delhi@fci-demo.test';
const hr = { role: 'ESTABLISHMENT_HR', office: 'DH001' };

const run = Math.floor(Math.random() * 9000) + 1000;
const gangA = `E2E-ATA-${run}`;
const gangB = `E2E-ATB-${run}`;
const attached = `LA${run}`;
const lightDuty = `LD${run}`;
const today = businessToday();

function plusDays(day: string, n: number): string {
  const d = new Date(`${day}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

const label = (key: string, args: unknown[] = []) =>
  new RegExp(`^${t('en', key, args).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

/** SQL of one fixture handling labour of Okhla, member of {@code gang} since 30 days ago. */
function labourSql(code: string, pan: string, gang: string): string {
  const okhla = "(SELECT location_id FROM org_location WHERE location_code = 'DP-DL01')";
  return [
    `INSERT INTO employee (emp_num, first_name, last_name, birth_date, gender, comp_joining_date, employee_type, emp_lbr_flag, pan_number, designation_id, location_id, depot_location_id, provident_fund_type, parent_zone_id, employment_status, record_status, record_origin) SELECT '${code}', 'Fixture', '${code}', DATE '1990-01-15', 'M', DATE '2015-07-01', 'DEPT_LABOUR', 'Y', '${pan}', d.designation_id, l.location_id, l.location_id, 'NONE', l.zone_id, 'IN_SERVICE', 'ACTIVE', 'ENTERED' FROM org_designation d, org_location l WHERE d.designation_code = 'HL' AND l.location_code = 'DP-DL01'`,
    `INSERT INTO labour_profile (employee_id, labour_number, payroll_type, depot_location_id, pf_applicable_flag, labour_status, effective_from) SELECT employee_id, '${code}', 'DEPT_LABOUR', ${okhla}, 'N', 'ACTIVE', DATE '2015-07-01' FROM employee WHERE emp_num = '${code}'`,
    `INSERT INTO labour_gang_membership (gang_id, employee_id, gang_role, effective_from) SELECT g.gang_id, e.employee_id, 'HANDLING', DATE '${plusDays(today, -30)}' FROM labour_gang g, employee e WHERE g.gang_code = '${gang}' AND e.emp_num = '${code}'`,
  ].join('; ');
}

function gangSql(code: string): string {
  return `INSERT INTO labour_gang (gang_code, gang_name, depot_location_id, payroll_type, gang_type, gang_status, effective_from) SELECT '${code}', 'E2E ${code}', location_id, 'DEPT_LABOUR', 'HANDLING', 'ACTIVE', DATE '2020-04-01' FROM org_location WHERE location_code = 'DP-DL01'`;
}

async function openGangMember(page: Page) {
  await workAs(page, 'en', HR, hr, 'M-09');
  const gangs = page.getByRole('table', { name: t('en', 'screen.M-09') });
  await gangs
    .getByRole('row', { name: new RegExp(`E2E ${gangA}`) })
    .first()
    .getByRole('button', { name: t('en', 'm09.open') })
    .click();
  return page
    .getByRole('table', { name: t('en', 'm09.membersOf', [`E2E ${gangA}`]) })
    .getByRole('row')
    .filter({ hasText: attached })
    .first();
}

async function attach(page: Page, from: string, to: string, reason: string) {
  const member = await openGangMember(page);
  await member.getByRole('button', { name: t('en', 'action.m09.attach') }).click();
  await expect(
    page.getByRole('heading', { name: t('en', 'm09.move.ATTACH', [attached]) }).first(),
  ).toBeVisible();
  await page.getByLabel(t('en', 'm09.gangSearch')).fill(gangB);
  const target = page.getByLabel(label('m09.toGang'));
  await expect(target.locator('option', { hasText: gangB })).toHaveCount(1);
  await target.selectOption({ index: 1 });
  await page.getByLabel(label('m09.attachFrom')).fill(from);
  await page.getByLabel(label('m09.attachTo')).fill(to);
  await page.getByLabel(label('m09.moveReason')).fill(reason);
  await page.getByRole('button', { name: t('en', 'action.m09.sendMove') }).click();
}

test.describe.serial('E2E-27 attachment and E2E-28 Permanent Light Duty', () => {
  test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);

  test.beforeAll(() => {
    sql(
      [
        gangSql(gangA),
        gangSql(gangB),
        labourSql(attached, `DMOPA${run}Z`, gangA),
        labourSql(lightDuty, `DMOPL${run}Z`, gangA),
      ].join('; '),
    );
  });

  // E2E-27 (SRS-2-47, SC-20-0112): an attachment for a date range, then an overlapping one refused.
  test(
    'E2E-27: HR attaches a labour to another gang for three days; an overlapping attachment is refused',
    { tag: ['@E2E-27', '@SRS-2-47', '@positive'] },
    async ({ page }) => {
      const from = plusDays(today, 3);
      const to = plusDays(today, 5);
      const reason = `E2E attachment ${run}`;
      await attach(page, from, to, reason);
      await expect(page.getByText(t('en', 'm09.moveSent'))).toBeVisible();

      // One gang a day: a second attachment overlapping the one awaiting the Checker is refused,
      // naming the overlap (the pending one has not moved the membership yet).
      await attach(page, plusDays(today, 4), '', `E2E overlapping attachment ${run}`);
      await expect(page.locator('.fl-error[role="alert"]')).toContainText('OVERLAP');
      await expect(page.getByText(t('en', 'm09.moveSent'))).toHaveCount(0);

      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(reason), 'APPROVE');

      // Once approved the labour is in the other gang for those days: attaching it there again
      // from inside the range is refused for the gang (it already is that day's gang).
      await attach(page, plusDays(today, 4), '', `E2E attachment inside the range ${run}`);
      await expect(page.locator('.fl-error[role="alert"]')).toContainText('TO_GANG');
      await expect(page.getByText(t('en', 'm09.moveSent'))).toHaveCount(0);
    },
  );

  // E2E-28 (SRS-2-49, SC-20-0111): Permanent Light Duty with its order and the protected pay option.
  test(
    'E2E-28: HR moves a labour to Permanent Light Duty with its order; Protected pay needs its Basic; the Checker approves',
    { tag: ['@E2E-28', '@SRS-2-49', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', HR, hr, 'M-02');
      await page.getByLabel(label('m02.search')).fill(lightDuty);
      const row = page
        .getByRole('table', { name: t('en', 'screen.M-02') })
        .getByRole('row')
        .filter({ hasText: lightDuty })
        .first();
      await row.getByRole('button', { name: t('en', 'action.m02.lightDuty') }).click();
      await expect(page.getByText(t('en', 'm02.lightDutyOf', [lightDuty])).first()).toBeVisible();
      await page.getByLabel(label('m02.toDesignation')).selectOption({ label: 'ANC - Ancillary Labour' });
      await page.getByLabel(label('m02.payOption')).selectOption('PROTECTED_PAY');
      await page.getByLabel(label('m02.orderReference')).fill(`MB/LD/${run}`);
      await page.getByLabel(label('m02.orderDate')).fill(today);
      await page.getByLabel(label('m02.issuingAuthority')).fill('Medical Board, Delhi');
      await page.getByLabel(label('m02.effectiveDate')).fill(plusDays(today, 1));
      const reason = `E2E light duty ${run}`;
      await page.getByLabel(label('m09.changeReason')).fill(reason);
      const send = page.getByRole('button', { name: t('en', 'action.m02.send') });
      // Protected pay without its Basic is not sent.
      await expect(send).toBeDisabled();
      await page.getByLabel(label('m02.protectedBasic')).fill('21500');
      await expect(send).toBeEnabled();
      await send.click();
      await expect(page.getByText(t('en', 'm02.lifecycleSent'))).toBeVisible();

      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(reason), 'APPROVE');

      await workAs(page, 'en', HR, hr, 'M-02');
      await page.getByLabel(label('m02.search')).fill(lightDuty);
      // The M-02 list shows the designation by its name (ANC, "Ancillary Labour" in the demo master).
      await expect(
        page
          .getByRole('table', { name: t('en', 'screen.M-02') })
          .getByRole('row')
          .filter({ hasText: lightDuty })
          .first(),
      ).toContainText('Ancillary Labour');
    },
  );
});
