import { type Page, expect, test } from '@playwright/test';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql } from '../support/db';
import { apiStatus, businessToday, decide, t, workAs } from '../support/app';
import { openCapture } from '../support/labour';
import { CHECKER, apiJson, checker } from '../support/payroll-support';

/**
 * Module 22 NWNP labour life cycle through the UI (test-scope batch E, 2026-11-04; SRS-2-30,
 * SRS-5-14, SRS-10-19; UC "NWNP Employee Joining", "NWNP Separation & F&F", "NWNP Master Search",
 * SRS l.4956-4989): Establishment / HR records an NWNP labour on M-02 and the Payroll Checker
 * approves it (NWN code); the NWNP master search finds it and opens its record on M-02; it joins
 * an NWNP gang and is on the gang's list for the work-slip. HR then separates it on M-02 with its
 * order; once approved the record shows Separated, it is off the gang's list and a work-slip naming
 * it is refused.
 * Negatives: another payroll type's search does not return it; the separation is not sent without
 * its order; a later work-slip naming the separated labour is refused.
 *
 * A fixture NWNP gang per run (E2E_DB_URL fixture, never a demo gang); a fresh name and PAN per run.
 */
const HR = 'hr.delhi@fci-demo.test';
const LDH = 'ldh.depot@fci-demo.test';
const hr = { role: 'ESTABLISHMENT_HR', office: 'DH001' };
const ldh = { role: 'LABOUR_DEALING_HAND', office: 'DP-DL01' };

const run = Math.floor(Math.random() * 9000) + 1000;
const letters = 'ABCDEFGHJKLMNPQRSTUVWXYZ';
const tag = letters.charAt(run % letters.length) + letters.charAt(Math.floor(run / 24) % letters.length);
const name = `Nwjoin${tag}`;
const gangCode = `E2E-NWJ-${run}`;
const today = businessToday();
const tomorrow = businessToday(1);

const label = (key: string, args: unknown[] = []) =>
  new RegExp(`^${t('en', key, args).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

async function search(page: Page, type: string) {
  await page.getByLabel(label('m02.payrollType')).first().selectOption(type);
  await page.getByLabel(label('m02.search')).fill(name);
  return page.getByRole('table', { name: t('en', 'screen.M-02') });
}

async function gangId(page: Page): Promise<number> {
  const gangs = await apiJson<{ items: { gangId: number }[] }>(page, `/v1/labour/gangs?text=${gangCode}`);
  return gangs.items[0]?.gangId ?? 0;
}

async function employeeId(page: Page): Promise<number> {
  const found = await apiJson<{ items: { employeeId: number }[] }>(
    page,
    `/v1/labour/members?payrollType=NWNP_LABOUR&text=${name}&size=10`,
  );
  return found.items[0]?.employeeId ?? 0;
}

async function onGangList(page: Page, date = today): Promise<boolean> {
  const members = await apiJson<{ employeeId: number }[]>(
    page,
    `/v1/labour/gangs/${await gangId(page)}/members?date=${date}`,
  );
  const id = await employeeId(page);
  return members.some((m) => m.employeeId === id);
}

test.describe.serial('Module 22 NWNP labour life cycle', () => {
  test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);

  test.beforeAll(() => {
    sql(
      `INSERT INTO labour_gang (gang_code, gang_name, depot_location_id, payroll_type, gang_type, gang_status, effective_from)` +
        ` SELECT '${gangCode}', 'E2E NWNP joining ${run}', location_id, 'NWNP_LABOUR', 'HANDLING', 'ACTIVE', DATE '2020-04-01'` +
        ` FROM org_location WHERE location_code = 'DP-DL01'`,
    );
  });

  // SC-22-0034 (main flow), SC-22-0035 (verify), SC-22-0038 (search main flow), SC-22-0039 (verify)
  test(
    'SRS-2-30: HR records an NWNP labour, the Checker approves, the NWNP search finds and opens it, it joins the gang',
    {
      tag: ['@SRS-2-30', '@SRS-5-14', '@positive'],
    },
    async ({ page }) => {
      await workAs(page, 'en', HR, hr, 'M-02');
      await page.getByRole('button', { name: t('en', 'action.m02.create') }).click();
      await page.getByLabel(label('m02.payrollType')).last().selectOption('NWNP_LABOUR');
      await page.getByLabel(label('m02.firstName')).fill(name);
      await page.getByLabel(label('m02.lastName')).fill('Demo');
      await page.getByLabel(label('m02.birthDate')).fill('1990-02-14');
      await page.getByLabel(label('m02.joiningDate')).fill('2015-07-01');
      await page.getByLabel(label('m02.designation')).selectOption({ label: 'NHL - Handling Labour (NWNP)' });
      await page.getByLabel(label('m02.depot')).last().selectOption({ label: 'DP-DL01 - Depot Okhla' });
      await page.getByLabel(label('m02.pan')).fill(`DMOPN${run}Q`);
      await page.getByLabel(label('m02.ifsc')).fill('SBIN0DEMO01');
      await page.getByLabel(label('m02.bank')).fill(`00008${run}`);
      await page.getByLabel(label('m02.reason')).fill(`E2E NWNP joining ${run}`);
      await page.getByRole('button', { name: t('en', 'action.m02.send') }).click();
      await expect(page.getByText(t('en', 'm02.sent'))).toBeVisible();

      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(`E2E NWNP joining ${run}`), 'APPROVE');

      // NWNP Master Search: the NWNP labour by name; another payroll type does not return it.
      await workAs(page, 'en', HR, hr, 'M-02');
      let table = await search(page, 'DEPT_LABOUR');
      await expect(table.getByText(name)).toHaveCount(0);
      table = await search(page, 'NWNP_LABOUR');
      const row = table.getByRole('row').filter({ hasText: name }).first();
      await expect(row).toContainText(/NWN-\d{6}/);
      await expect(row).toContainText(t('en', 'labour.status.ACTIVE'));
      // From the search result to the NWNP labour's own record on M-02.
      const code = (/NWN-\d{6}/.exec((await row.textContent()) ?? '') ?? [''])[0];
      await row.getByRole('button', { name: t('en', 'action.m02.amend') }).click();
      await expect(page.getByText(t('en', 'm02.amendOf', [code]))).toBeVisible();

      // Joining the NWNP gang (from today), approved; the labour is then on the gang's list.
      const added = await apiJson<{ id: number }>(
        page,
        `/v1/labour/gangs/${await gangId(page)}/members`,
        'POST',
        {
          employeeId: await employeeId(page),
          from: today,
          reason: `E2E NWNP gang joining ${run}`,
        },
      );
      expect(added.id).toBeTruthy();
      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(`E2E NWNP gang joining ${run}`), 'APPROVE');
      await workAs(page, 'en', LDH, ldh, 'I-02');
      await openCapture(page, 'I-02');
      expect(await onGangList(page)).toBe(true);
    },
  );

  // SC-22-0036 (main flow), SC-22-0037 (verify)
  test(
    'SRS-10-19: HR separates the NWNP labour with its order; once approved it is Separated and off later work-slips',
    { tag: ['@SRS-10-19', '@SRS-2-30', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', HR, hr, 'M-02');
      const table = await search(page, 'NWNP_LABOUR');
      await table
        .getByRole('row')
        .filter({ hasText: name })
        .first()
        .getByRole('button', { name: t('en', 'action.m02.separate') })
        .click();
      await page.getByLabel(label('m02.separationType')).selectOption('RESIGNATION');
      // The labour joined the gang today: a separation from today would end that membership before
      // it began, so it is refused naming the membership (OD-100, CLR-154); from tomorrow it goes.
      await page.getByLabel(label('m02.separationDate')).fill(today);
      const reason = `E2E NWNP separation ${run}`;
      await page.getByLabel(label('m09.changeReason')).fill(reason);
      const send = page.getByRole('button', { name: t('en', 'action.m02.send') });
      // SRS l.4971: the separation starts with its order.
      await expect(send).toBeDisabled();
      await page.getByLabel(label('m02.orderReference')).fill(`SO/NWNP/${run}`);
      await page.getByLabel(label('m02.orderDate')).fill(today);
      await page.getByLabel(label('m02.issuingAuthority')).fill('Area Manager, Delhi');
      await expect(send).toBeEnabled();
      await send.click();
      await expect(page.locator('.fl-error[role="alert"]')).toContainText('MEMBERSHIP_FROM');
      await expect(page.getByText(t('en', 'm02.lifecycleSent'))).toHaveCount(0);
      await page.getByLabel(label('m02.separationDate')).fill(tomorrow);
      await send.click();
      await expect(page.getByText(t('en', 'm02.lifecycleSent'))).toBeVisible();

      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(reason), 'APPROVE');

      await workAs(page, 'en', HR, hr, 'M-02');
      const after = await search(page, 'NWNP_LABOUR');
      await expect(after.getByRole('row').filter({ hasText: name }).first()).toContainText(
        t('en', 'labour.status.SEPARATED'),
      );

      // Excluded from later cycles: still on the gang's list on its last day (today), off it from the
      // separation date, and a work-slip naming the labour on that date is refused.
      await workAs(page, 'en', LDH, ldh, 'I-02');
      await openCapture(page, 'I-02');
      expect(await onGangList(page, today)).toBe(true);
      expect(await onGangList(page, tomorrow)).toBe(false);
      const view = await apiJson<{ operations: { id: number; code: string }[]; bagTypes: { id: number }[] }>(
        page,
        `/v1/labour/gangs/${await gangId(page)}/work-slips?month=${today.slice(0, 7)}`,
      );
      expect(
        await apiStatus(page, `/v1/labour/gangs/${await gangId(page)}/work-slips`, 'POST', {
          date: tomorrow,
          continueWork: false,
          rows: [
            {
              operationId: view.operations.find((o) => o.code === 'W2P')?.id,
              bagTypeId: view.bagTypes[0]?.id,
              bags: 100,
              start: '09:00',
              end: '10:00',
            },
          ],
          participants: [await employeeId(page)],
        }),
      ).toBe(422);
    },
  );
});
