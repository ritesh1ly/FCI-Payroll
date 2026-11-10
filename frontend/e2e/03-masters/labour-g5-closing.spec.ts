import { type Page, expect, test } from '@playwright/test';
import { businessToday, decide, sidebarCodes, t, workAs } from '../support/app';
import { openCapture } from '../support/labour';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql } from '../support/db';
import { CHECKER, checker } from '../support/payroll-support';

/**
 * Group g5 closing pass (PENDING 2i, G5-30 and the screens changed by G5-11, G5-43 / 44, G5-53, G5-55,
 * G5-65, G5-96): M-09 gang create and approve (SRS-2-31, BR-LABM-002), the I-01 batch returned by the
 * Payroll Checker with the reason shown to the Labour Dealing Hand (SRS-2-29) and the depot's Attendance
 * Source (SRS-2-36), the Departmental M-05 rate set (SRS-3-13), the M-06 band rows and the band boundary
 * (M-06 l.4276, SRS-3-6), the parked I-02 / M-05 NWNP sections disabled with their reason (DoD 4), the
 * M-02 gang search as of a date (SRS-2-40) and X-03 Reprocess Failed per window (X-03 l.5172, SRS-INT-33).
 *
 * Fixture labour and gangs are created per run through the database fixture (E2E_DB_*: no producer of
 * free labour exists - labour are created on M-02 under maker-checker); every proposal the run makes is
 * rejected or returned by its checker, so the demo data stays as it was.
 */
const HR = 'hr.delhi@fci-demo.test';
const hr = { role: 'ESTABLISHMENT_HR', office: 'DH001' };
const LDH = 'ldh.depot@fci-demo.test';
const ldh = { role: 'LABOUR_DEALING_HAND', office: 'DP-DL01' };
const ADMIN = 'tax.ruleadmin@fci-demo.test';
const admin = { role: 'RULE_ADMINISTRATOR', office: 'HQ001' };
const APPROVER = 'rule.approver@fci-demo.test';
const approver = { role: 'RULE_APPROVER', office: 'HQ001' };
const SYSADMIN = 'sysadmin@fci-demo.test';
const sysadmin = { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' };
const run = String(Math.floor(Math.random() * 9000) + 1000);
const label = (key: string) => new RegExp(`^${t('en', key).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

function previousMonth(): string {
  const [y, m] = businessToday().split('-').map(Number) as [number, number];
  return m === 1 ? `${y - 1}-12` : `${y}-${String(m - 1).padStart(2, '0')}`;
}

function plusDays(day: string, n: number): string {
  const d = new Date(`${day}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

/** A fixture labour of Depot Okhla (Departmental) with the designation; not in any gang. */
function freeLabour(code: string, pan: string, designation: string) {
  sql(
    `INSERT INTO employee (emp_num, first_name, last_name, birth_date, gender, comp_joining_date, employee_type,
       emp_lbr_flag, pan_number, designation_id, location_id, depot_location_id, provident_fund_type, parent_zone_id,
       employment_status, record_status, record_origin)
     SELECT '${code}', 'Fixture', '${code}', DATE '1990-01-15', 'M', DATE '2015-07-01', 'DEPT_LABOUR', 'Y', '${pan}',
       d.designation_id, l.location_id, l.location_id, 'NONE', l.zone_id, 'IN_SERVICE', 'ACTIVE', 'ENTERED'
       FROM org_designation d JOIN org_location l ON l.location_code = 'DP-DL01' WHERE d.designation_code = '${designation}';
     INSERT INTO labour_profile (employee_id, labour_number, payroll_type, depot_location_id, pf_applicable_flag,
       labour_status, effective_from)
     SELECT employee_id, emp_num, 'DEPT_LABOUR', depot_location_id, 'N', 'ACTIVE', DATE '2015-07-01'
       FROM employee WHERE emp_num = '${code}'`,
  );
}

async function i01(page: Page, gang: string, month: string) {
  await workAs(page, 'en', LDH, ldh, 'I-01');
  await openCapture(page, 'I-01');
  await page.getByLabel(t('en', 'm09.gangSearch')).fill(gang);
  await page
    .getByRole('combobox', { name: label('m09.gang') })
    .selectOption({ label: `${gang} - Fixture ${gang}` });
  await page.getByLabel(label('i01.month')).fill(month);
  await expect(page.getByRole('table', { name: t('en', 'i01.grid') })).toBeVisible();
}

test.describe.serial('Group g5 closing pass', () => {
  // SC-20-0140 (US-20-03): HR creates a gang with its Sardar and Mandal; the Payroll Checker decides it.
  test(
    'SRS-2-31: HR creates a gang on M-09 with its Sardar, Mandal and strength; the Payroll Checker rejects it',
    { tag: ['@SRS-2-31', '@BR-LABM-002', '@positive'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      freeLabour(`LG5S${run}`, `QZXPS${run}A`, 'SDR');
      freeLabour(`LG5M${run}`, `QZXPM${run}B`, 'MDL');
      const reason = `E2E new gang ${run}`;
      await workAs(page, 'en', HR, hr, 'M-09');
      await page.getByRole('button', { name: t('en', 'action.m09.create'), exact: true }).click();
      const send = page.getByRole('button', { name: t('en', 'action.m09.send') });
      await page.getByLabel(label('m09.gang')).last().fill(`E2E Gang ${run}`);
      await page.getByLabel(label('m09.strength')).last().fill('6');
      await page.getByLabel(label('m02.reason')).last().fill(reason);
      // Negative: a gang needs one Sardar and one Mandal.
      await expect(send).toBeDisabled();
      await expect(send).toHaveAttribute('title', t('en', 'm09.why.roles'));
      const members = [
        [`LG5S${run}`, 'SARDAR'],
        [`LG5M${run}`, 'MANDAL'],
      ] as const;
      for (const [i, [code, role]] of members.entries()) {
        await page.getByLabel(label('m02.code')).last().fill(code);
        await page.getByLabel(label('m09.role')).last().selectOption(role);
        await page.getByRole('button', { name: t('en', 'action.m09.addMember') }).click();
        // addMember resolves a labour code asynchronously and only then clears the code field;
        // wait for this member to land before typing the next, or the async clear races the next fill.
        await expect(
          page.getByRole('list', { name: t('en', 'm09.members') }).getByRole('listitem'),
        ).toHaveCount(i + 1);
      }
      await expect(send).toBeEnabled();
      await send.click();
      await expect(page.getByText(t('en', 'm09.sent'))).toBeVisible();

      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(reason), 'REJECT', `E2E not this gang ${run}`);
    },
  );

  // SC-20-0058 (US-20-07 AC2): the returned batch shows the Payroll Checker's reason.
  test(
    'SRS-2-29: a fully marked month is submitted; the Payroll Checker returns it and I-01 shows the reason',
    { tag: ['@SRS-2-29', '@SRS-2-36', '@positive'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const gang = `DP-DL01-E${run}`;
      const month = previousMonth();
      freeLabour(`LG5A${run}`, `QZXPA${run}C`, 'SDR');
      freeLabour(`LG5B${run}`, `QZXPB${run}D`, 'MDL');
      sql(
        `INSERT INTO labour_gang (gang_code, gang_name, depot_location_id, payroll_type, gang_type, gang_status, effective_from)
         SELECT '${gang}', 'Fixture ${gang}', location_id, 'DEPT_LABOUR', 'HANDLING', 'ACTIVE', DATE '2024-04-01'
           FROM org_location WHERE location_code = 'DP-DL01';
         INSERT INTO labour_gang_membership (gang_id, employee_id, gang_role, effective_from)
         SELECT g.gang_id, e.employee_id, CASE WHEN e.emp_num LIKE 'LG5A%' THEN 'SARDAR' ELSE 'MANDAL' END, DATE '2024-04-01'
           FROM labour_gang g JOIN employee e ON e.emp_num IN ('LG5A${run}', 'LG5B${run}') WHERE g.gang_code = '${gang}';
         INSERT INTO labour_attendance (employee_id, gang_id, depot_location_id, attendance_date, attendance_code_id,
           attendance_code, engine_code, source_code, capture_mode)
         SELECT m.employee_id, g.gang_id, g.depot_location_id, DATEADD('DAY', r.X - 1, DATE '${month}-01'), c.attendance_code_id,
           c.attendance_code, 'DEPT_LABOUR', 'ENT', 'MANUAL'
           FROM labour_gang g JOIN labour_gang_membership m ON m.gang_id = g.gang_id
           JOIN attendance_code_master c ON c.engine_code = 'DEPT_LABOUR' AND c.attendance_code = 'W'
           JOIN SYSTEM_RANGE(1, 31) r ON DATEADD('DAY', r.X - 1, DATE '${month}-01') < DATEADD('MONTH', 1, DATE '${month}-01')
          WHERE g.gang_code = '${gang}'`,
      );
      await i01(page, gang, month);
      // SRS-2-36 / I-01 l.4543: the depot's Attendance Source, read-only from the HQ master.
      await expect(page.getByLabel(t('en', 'i01.source'), { exact: true }).first()).toBeVisible();
      await page
        .getByTestId('i01-grid')
        .getByRole('button', { name: t('en', 'action.i01.submit') })
        .click();
      await expect(page.getByText(t('en', 'i01.batch.SUBMITTED')).first()).toBeVisible();

      const why = `E2E recount the 3rd ${run}`;
      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(`Attendance of Fixture ${gang}`), 'RETURN', why);

      await i01(page, gang, month);
      const note = page.getByRole('note', { name: t('en', 'i01.returnReason') });
      await expect(note).toContainText(why);
    },
  );

  // SC-20-0150 (US-20-23 AC1): a Departmental Basic wage and DA set; values from FCI (OQ-80), so the run rejects it.
  test(
    'SRS-3-13: the Rule Administrator proposes a Departmental M-05 set; the Rule Approver rejects it; HR has no M-05',
    { tag: ['@SRS-3-13', '@positive'] },
    async ({ page }) => {
      // SRS l.5433: M-05 is for the Maker, Checker, dealing hand, Finance and Administrator; Establishment/HR
      // has no M-05 at all (no menu entry).
      await workAs(page, 'en', HR, hr, 'M-02');
      expect(await sidebarCodes(page)).not.toContain('M-05');

      await workAs(page, 'en', ADMIN, admin, 'M-05');
      await page.getByLabel(label('m02.payrollType')).first().selectOption('DEPT_LABOUR');
      await page.getByRole('button', { name: t('en', 'action.m05.propose') }).click();
      const send = page.getByRole('button', { name: t('en', 'action.m05.send') });
      await expect(send).toBeDisabled();
      await page.getByLabel(label('m12.effective')).fill(plusDays(businessToday(), 400));
      for (const field of await page.getByLabel(/^Basic wage /).all()) await field.fill('612.50');
      for (const field of await page.getByLabel(/^DA /).all()) await field.fill('105');
      const reason = `E2E Departmental set ${run}`;
      await page.getByLabel(label('m02.reason')).last().fill(reason);
      await expect(send).toBeEnabled();
      await send.click();
      await expect(page.getByText(t('en', 'm05.proposed'))).toBeVisible();

      await workAs(page, 'en', APPROVER, approver, 'W-01');
      await decide(page, new RegExp(reason), 'REJECT', `E2E values come from FCI ${run}`);
    },
  );

  // SRS-4-11 (G5-65) and DoD 4 (G5-53): the GoI rate's date; the parked NWNP Minimum Wage section.
  test(
    "SRS-4-11: a DPS GoI rate shows the set's effective date; the NWNP Minimum Wage by location type is parked",
    { tag: ['@SRS-4-11', '@SRS-5-11', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', ADMIN, admin, 'M-05');
      const table = page.getByRole('table', { name: t('en', 'screen.M-05') });
      await expect(table).toBeVisible();
      const withGoi = table.getByTestId('goi-effective');
      for (const cell of await withGoi.all()) await expect(cell).toContainText(/In force from /);
      await page.getByLabel(label('m02.payrollType')).first().selectOption('NWNP_LABOUR');
      const parked = page.getByRole('button', { name: t('en', 'action.m05.nwnpMinWage') });
      await expect(parked).toBeDisabled();
      await expect(parked).toHaveAttribute('title', t('en', 'm05.why.nwnpMinWage'));
    },
  );

  // M-06 l.4276 (G5-43) and SRS-3-6 (G5-44).
  test(
    'M-06: a band is added and removed in the proposal; a table keeps one band; the 66 kg boundary is read-only',
    { tag: ['@SRS-3-6', '@edge'] },
    async ({ page }) => {
      await workAs(page, 'en', ADMIN, admin, 'M-06');
      await page.getByRole('button', { name: t('en', 'action.m06.propose'), exact: true }).click();
      await expect(page.getByLabel(t('en', 'm06.boundary'))).toContainText('66 kg');
      const band1 = t('en', 'm06.band.BAND_1');
      await page.getByRole('button', { name: t('en', 'action.m06.addBand', [band1]) }).click();
      const rate5 = page.getByLabel(t('en', 'm06.rateOf', [band1, '5']));
      await expect(rate5).toHaveValue('');
      await expect(page.getByLabel(t('en', 'm06.fromOf', [band1, '5']))).toHaveValue('92');
      const remove = page.getByRole('button', { name: t('en', 'action.m06.removeBand', [band1]) });
      for (let i = 0; i < 4; i += 1) await remove.click();
      await expect(remove).toBeDisabled();
      await expect(remove).toHaveAttribute('title', t('en', 'm06.why.oneBand'));
      await page
        .getByRole('button', { name: t('en', 'common.cancel') })
        .last()
        .click();
    },
  );

  // DoD 4 (G5-53): I-02 overtime and Anna Darpan wait for FCI / the interface.
  test(
    'SRS-2-45: I-02 shows overtime per person and the Anna Darpan records disabled with their reason',
    { tag: ['@SRS-2-45', '@SRS-2-27', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', LDH, ldh, 'I-02');
      await openCapture(page, 'I-02');
      await page
        .getByRole('combobox', { name: label('m09.gang') })
        .selectOption({ label: 'DP-DL01-G007 - Okhla Gang 7' });
      await page.getByLabel(label('i01.month')).fill(businessToday().slice(0, 7));
      await page
        .getByTestId('i02-capture')
        .getByRole('button', { name: t('en', 'action.i02.new') })
        .click();
      const overtime = page
        .getByTestId('i02-capture')
        .getByRole('button', { name: t('en', 'action.i02.overtime') });
      await expect(overtime).toBeDisabled();
      await expect(overtime).toHaveAttribute('title', t('en', 'i02.why.overtime'));
      const anna = page
        .getByTestId('i02-capture')
        .getByRole('button', { name: t('en', 'action.i02.annaDarpan') });
      await expect(anna).toBeDisabled();
      await expect(anna).toHaveAttribute('title', t('en', 'i02.why.annaDarpan'));
    },
  );

  // SC-20-0089 (G5-55): members found by the gang they were in on the date.
  test(
    'SRS-2-40: M-02 finds the members of a gang on a chosen date; without a gang the date is disabled',
    { tag: ['@SRS-2-40', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', HR, hr, 'M-02');
      const on = page.getByLabel(label('m02.searchGangOn'));
      await expect(on).toBeDisabled();
      await expect(on).toHaveAttribute('title', t('en', 'm02.why.gangOn'));
      await page.getByLabel(label('m02.searchGang')).fill('DP-DL01-G007');
      await expect(on).toBeEnabled();
      await on.fill(plusDays(businessToday(), -30));
      const table = page.getByRole('table', { name: t('en', 'screen.M-02') });
      await expect(table.getByRole('row').filter({ hasText: 'L50801' })).toHaveCount(1);
      // Before the gang existed (15 months back in the demo data) nobody was in it.
      await on.fill('2020-01-01');
      await expect(page.getByText(t('en', 'm02.none'))).toBeVisible();
    },
  );

  // SC-23-0023 / SC-23-0054 (G5-96): Reprocess Failed per window, the Administrator only.
  test(
    'SRS-INT-33: X-03 Reprocess failed per window is disabled with its reason for a viewer and without an adapter',
    { tag: ['@SRS-INT-33', '@negative'] },
    async ({ page }) => {
      // SRS l.5502: X-03 is the Maker's, Checker's, Finance's and Administrator's; the Checker only views.
      await workAs(page, 'en', CHECKER, checker, 'X-03');
      const windows = page.getByRole('table', { name: t('en', 'x03.windows') });
      const any = windows.getByRole('button', { name: /^Reprocess the failed records of window / }).first();
      if ((await any.count()) > 0) {
        await expect(any).toBeDisabled();
        await expect(any).toHaveAttribute('title', t('en', 'x03.reason.adminOnly'));
      }
      await workAs(page, 'en', SYSADMIN, sysadmin, 'X-03');
      const failed = page
        .getByRole('table', { name: t('en', 'x03.windows') })
        .getByRole('row')
        .filter({ hasText: 'HRMS_EMP_OTA' })
        .first();
      if ((await failed.count()) > 0) {
        const button = failed.getByRole('button', { name: /^Reprocess the failed records of window / });
        await expect(button).toBeDisabled();
        // No adapter is built for the demo interfaces (module 23 waits for the API schemas).
        await expect(button).toHaveAttribute('title', t('en', 'x03.why.noAdapter'));
      }
    },
  );
});
