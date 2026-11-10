import { expect, test } from '@playwright/test';
import { apiStatus, businessToday, decide, t, workAs } from '../support/app';
import { openCapture } from '../support/labour';
import { CHECKER, MAKER, apiJson, checker, maker } from '../support/payroll-support';

/** A label that starts with the text of the key, its regular-expression characters escaped (e.g. "(bags)"). */
const starts = (key: string) => new RegExp(`^${t('en', key).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

/**
 * Module 20 Labour common + Departmental through the UI (docs/plan/20-labour-common-departmental.md;
 * SRS-2-30, SRS-2-1, SRS-2-35, SRS-2-4, SRS-3-12): Establishment / HR records a labour member on M-02
 * and the Payroll Checker approves it on W-01; M-09 lists the depot's gangs and members; the Labour
 * Dealing Hand marks today for Okhla Gang 1 on I-01 and sees the month on I-02; HQ reads the depot
 * norm sets on M-06. Negative: the Payroll Maker never records a labour member.
 *
 * Demo data V052_9 (Okhla Gang 1), V053_2 (codes), V054_9 (operations). Re-runnable: each member has
 * a name and PAN no earlier run used; today's marks are saved again unchanged.
 */
const HR = 'hr.delhi@fci-demo.test';
const LDH = 'ldh.depot@fci-demo.test';
const ADMIN = 'tax.ruleadmin@fci-demo.test';
const APPROVER = 'rule.approver@fci-demo.test';
const hr = { role: 'ESTABLISHMENT_HR', office: 'DH001' };
const ldh = { role: 'LABOUR_DEALING_HAND', office: 'DP-DL01' };

const run = String(Math.floor(Math.random() * 9000) + 1000);
const letters = 'ABCDEFGHJKLMNPQRSTUVWXYZ';
const tag =
  letters.charAt(Number(run) % letters.length) +
  letters.charAt(Math.floor(Number(run) / 24) % letters.length);
const name = `Eetoo${tag}`;

function nextDay(day: string): string {
  const d = new Date(`${day}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
}

test.describe.serial('Module 20 labour', () => {
  test(
    'SRS-2-30: HR records a labour member and the Payroll Checker approves it',
    { tag: ['@SRS-2-30', '@SRS-2-27', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', HR, hr, 'M-02');
      await page.getByRole('button', { name: t('en', 'action.m02.create') }).click();
      await page.getByLabel(starts('m02.firstName')).fill(name);
      await page.getByLabel(starts('m02.lastName')).fill('Demo');
      await page.getByLabel(starts('m02.birthDate')).fill('1991-04-12');
      await page.getByLabel(starts('m02.joiningDate')).fill('2012-07-01');
      await page.getByLabel(starts('m02.designation')).selectOption({ label: 'HL - Handling Labour' });
      await page.getByLabel(starts('m02.depot')).last().selectOption({ label: 'DP-DL01 - Depot Okhla' });
      await page.getByLabel(starts('m02.pan')).fill(`DMOPE${run}K`);
      await page.getByLabel(starts('m02.ifsc')).fill('SBIN0DEMO01');
      await page.getByLabel(starts('m02.bank')).fill(`00009${run}`);
      await page.getByLabel(starts('m02.reason')).fill(`E2E appointment ${run}`);
      await page.getByRole('button', { name: t('en', 'action.m02.send') }).click();
      await expect(page.getByText(t('en', 'm02.sent'))).toBeVisible();

      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(`E2E appointment ${run}`), 'APPROVE');

      await workAs(page, 'en', HR, hr, 'M-02');
      await page.getByLabel(starts('m02.search')).fill(name);
      const table = page.getByRole('table', { name: t('en', 'screen.M-02') });
      await expect(table.getByText(/DPT-\d{6}/).first()).toBeVisible();
    },
  );

  // SC-20-0201: the name of a labour member is corrected with the service-book page, under
  // maker-checker; the correction never goes through an amendment.
  test(
    'SRS-2-59: HR corrects the name with the page attached and the Payroll Checker approves it',
    { tag: ['@SRS-2-59', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', HR, hr, 'M-02');
      await page.getByLabel(starts('m02.search')).fill(name);
      const table = page.getByRole('table', { name: t('en', 'screen.M-02') });
      await table
        .getByRole('button', { name: t('en', 'action.m02.correct') })
        .first()
        .click();
      const send = page.getByRole('button', { name: t('en', 'action.m02.send') });
      await page.getByLabel(starts('m02.newLastName')).fill('Demokumar');
      await page.getByLabel(starts('m02.correctionRemarks')).fill(`E2E correction ${run}`);
      await page.getByLabel(starts('m02.documentAuthority')).fill('Area Manager, Delhi');
      // Without the page the correction is not sent (SRS-2-59: the evidence is mandatory).
      await expect(send).toBeDisabled();
      await page.getByLabel(starts('m02.documentFile')).setInputFiles({
        name: 'service-book.pdf',
        mimeType: 'application/pdf',
        buffer: Buffer.from('%PDF-1.7 service book page'),
      });
      await expect(send).toBeEnabled();
      await send.click();
      await expect(page.getByText(t('en', 'm02.correctionSent'))).toBeVisible();

      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(`E2E correction ${run}`), 'APPROVE');
      await workAs(page, 'en', HR, hr, 'M-02');
      await page.getByLabel(starts('m02.search')).fill(name);
      await expect(
        page.getByRole('table', { name: t('en', 'screen.M-02') }).getByText(new RegExp(`${name} Demokumar`)),
      ).toBeVisible();
    },
  );

  test(
    'SRS-2-1: M-09 lists the depot gangs with the Sardar and Mandal',
    { tag: ['@SRS-2-1', '@SRS-2-46', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', HR, hr, 'M-09');
      const gangs = page.getByRole('table', { name: t('en', 'screen.M-09') });
      const row = gangs.getByRole('row', { name: /Okhla Gang 1/ }).first();
      await row.getByRole('button', { name: t('en', 'm09.open') }).click();
      const members = page.getByRole('table', { name: t('en', 'm09.membersOf', ['Okhla Gang 1']) });
      await expect(members.getByText(t('en', 'm09.role.SARDAR'))).toBeVisible();
      await expect(members.getByText(t('en', 'm09.role.MANDAL'))).toBeVisible();
      await expect(gangs.getByText(t('en', 'm09.type.ANCILLARY_POOL')).first()).toBeVisible();
    },
  );

  test(
    'SRS-2-35: the Labour Dealing Hand marks the whole gang for today',
    { tag: ['@SRS-2-35', '@SRS-2-2', '@SRS-3-1', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', LDH, ldh, 'I-01');
      await openCapture(page, 'I-01');
      await page
        .getByRole('combobox', { name: starts('m09.gang') })
        .selectOption({ label: 'DP-DL01-G002 - Okhla Gang 1' });
      const today = businessToday();
      await page.getByRole('button', { name: t('en', 'i01.markDay', [today]) }).click();
      const day = page.getByRole('table', { name: t('en', 'i01.dayOf', [today]) });
      const codes = day.getByRole('combobox');
      const count = await codes.count();
      for (let i = 0; i < count; i++) await codes.nth(i).selectOption('W');
      const save = page.getByTestId('i01-grid').getByRole('button', { name: t('en', 'action.i01.save') });
      // No spec files a work-slip for today on this gang, so today is never frozen: the save
      // succeeds (an earlier run's identical marks are saved again unchanged) and no error shows.
      await save.click();
      await expect(page.getByText(t('en', 'i01.saved', [today]))).toBeVisible();
      await expect(page.locator('.fl-error[role="alert"]')).toHaveCount(0);
    },
  );

  test(
    'SRS-2-4: I-02 shows the month of the gang with its operations',
    { tag: ['@SRS-2-4', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', LDH, ldh, 'I-02');
      await openCapture(page, 'I-02');
      await page
        .getByRole('combobox', { name: starts('m09.gang') })
        .selectOption({ label: 'DP-DL01-G002 - Okhla Gang 1' });
      await expect(page.getByRole('status').first()).toContainText('Okhla Gang 1');
      await page
        .getByTestId('i02-capture')
        .getByRole('button', { name: t('en', 'action.i02.new') })
        .click();
      await expect(
        page.getByLabel(starts('i02.operation')).locator('option', { hasText: 'W2P' }),
      ).toHaveCount(1);
    },
  );

  // SC-20-0195: Save and Next keeps the gang and the operation and moves to the next day.
  test(
    'BR-LABR-002: Save and Next saves the slip and opens the next day with the same operation',
    { tag: ['@BR-LABR-002', '@positive'] },
    async ({ page }) => {
      // A day of last month an earlier run did not use: the run number picks it.
      const [y, m] = businessToday().split('-').map(Number) as [number, number];
      const month = m === 1 ? `${y - 1}-12` : `${y}-${String(m - 1).padStart(2, '0')}`;
      const date = `${month}-${String((Number(run) % 20) + 2).padStart(2, '0')}`;
      // SRS-2-3: the gang's attendance of the day is marked before its work-slip.
      await workAs(page, 'en', LDH, ldh, 'I-01');
      await openCapture(page, 'I-01');
      await page
        .getByRole('combobox', { name: starts('m09.gang') })
        .selectOption({ label: 'DP-DL01-G002 - Okhla Gang 1' });
      await page.getByLabel(starts('i01.month')).fill(month);
      await page.getByRole('button', { name: t('en', 'i01.markDay', [date]) }).click();
      const grid = page.getByRole('table', { name: t('en', 'i01.dayOf', [date]) });
      const codes = grid.getByRole('combobox');
      const count = await codes.count();
      for (let i = 0; i < count; i++) await codes.nth(i).selectOption('W');
      await page
        .getByTestId('i01-grid')
        .getByRole('button', { name: t('en', 'action.i01.save') })
        .click();
      // The run picks one of 20 days: a day an earlier run already filed a work-slip for is frozen
      // (SRS-2-3) and answers with that refusal only; any other error fails the test.
      const frozen = page.locator('.fl-error[role="alert"]').filter({ hasText: /cannot change/ });
      await expect(page.getByText(t('en', 'i01.saved', [date])).or(frozen)).toBeVisible();
      await expect(
        page.locator('.fl-error[role="alert"]').filter({ hasNotText: /cannot change/ }),
      ).toHaveCount(0);

      await workAs(page, 'en', LDH, ldh, 'I-02');

      await openCapture(page, 'I-02');
      await page
        .getByRole('combobox', { name: starts('m09.gang') })
        .selectOption({ label: 'DP-DL01-G002 - Okhla Gang 1' });
      await page.getByLabel(starts('i01.month')).fill(month);
      await page
        .getByTestId('i02-capture')
        .getByRole('button', { name: t('en', 'action.i02.new') })
        .click();
      const day = page.getByLabel(starts('i02.date'));
      await day.fill(date);
      const operation = page.getByLabel(starts('i02.operation'));
      const w2p = await operation.locator('option', { hasText: 'W2P' }).first().getAttribute('value');
      await operation.selectOption(w2p ?? '');
      await page.getByLabel(starts('i02.bagType')).selectOption({ index: 1 });
      await page.getByLabel(starts('i02.bags')).fill('300');
      const hour = 6 + (Number(run) % 12);
      await page.getByLabel(starts('i02.start')).fill(`${String(hour).padStart(2, '0')}:00`);
      await page.getByLabel(starts('i02.end')).fill(`${String(hour + 1).padStart(2, '0')}:00`);
      await page
        .getByTestId('i02-capture')
        .getByRole('button', { name: t('en', 'action.i02.saveNext') })
        .click();
      await expect(page.getByText(t('en', 'i02.saved'))).toBeVisible();
      await expect(day).not.toHaveValue(date);
      await expect(operation).toHaveValue(w2p ?? '');
      await expect(page.getByLabel(starts('i02.bags'))).toHaveValue('');
    },
  );

  // SC-20-0018 (Maintain Depot Incentive Norms, main flow); G5-30 (M-06 propose / approve).
  test(
    'SRS-3-12: Headquarters proposes the depot norm set, the Rule Approver approves it and M-06 shows it in force',
    { tag: ['@SRS-3-12', '@positive'] },
    async ({ page }) => {
      const admin = { role: 'RULE_ADMINISTRATOR', office: 'HQ001' };
      await workAs(page, 'en', ADMIN, admin, 'M-06');
      const depots = await apiJson<{ id: number; code: string }[]>(page, '/v1/labour/norm-sets/depots');
      const okhla = depots.find((d) => d.code === 'DP-DL01');
      expect(okhla).toBeTruthy();
      // The set starts after the depot's latest (an earlier run's) and at least today.
      const sets = await apiJson<{ items: { effectiveFrom: string }[] }>(
        page,
        `/v1/labour/norm-sets?depot=${okhla?.id}&size=100`,
      );
      const latest = sets.items
        .map((x) => x.effectiveFrom)
        .sort()
        .pop();
      const from = latest && latest >= businessToday() ? nextDay(latest) : businessToday();
      const handling = 400 + (Number(run) % 100);
      await page.getByRole('button', { name: t('en', 'action.m06.propose'), exact: true }).click();
      const form = page.locator('.form-grid').last();
      await form.getByLabel(starts('m02.depot')).selectOption(String(okhla?.id));
      await form.getByLabel(starts('m12.effective')).fill(from);
      await form.getByLabel(starts('m06.handling')).fill(String(handling));
      await form.getByLabel(starts('m06.height')).fill('20');
      await form.getByLabel(starts('m06.lead')).fill('30');
      await form.getByLabel(starts('m06.mln')).fill('10');
      await form.getByLabel(starts('m06.mlsw')).fill('8');
      await form.getByLabel(starts('m06.mouCircular')).selectOption({ index: 1 });
      const reason = `E2E norm set ${run}`;
      await form.getByLabel(starts('m02.reason')).fill(reason);
      await page.getByRole('button', { name: t('en', 'action.m06.send') }).click();
      await expect(page.getByText(t('en', 'm06.proposed'))).toBeVisible();

      await workAs(page, 'en', APPROVER, { role: 'RULE_APPROVER', office: 'HQ001' }, 'W-01');
      await decide(page, new RegExp(reason), 'APPROVE');

      await workAs(page, 'en', ADMIN, admin, 'M-06');
      await page
        .getByRole('combobox', { name: starts('m02.depot') })
        .first()
        .selectOption(String(okhla?.id));
      const row = page
        .getByRole('table', { name: t('en', 'screen.M-06') })
        .getByRole('row')
        .filter({ hasText: t('en', 'm06.normValues', [handling, 20, 30]) });
      await expect(row.first()).toBeVisible();
      // The list shows the date as the screens do (dd Mon yyyy).
      const shown = new Date(`${from}T00:00:00Z`).toLocaleDateString('en-GB', {
        day: '2-digit',
        month: 'short',
        year: 'numeric',
        timeZone: 'UTC',
      });
      await expect(row.first()).toContainText(shown);
    },
  );

  test(
    'SRS-2-30: the Payroll Maker never records a labour member',
    { tag: ['@SRS-2-30', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'M-02');
      await expect(page.getByRole('button', { name: t('en', 'action.m02.create') })).toBeDisabled();
      expect(
        await apiStatus(page, '/v1/labour/members', 'POST', {
          payrollType: 'DEPT_LABOUR',
          firstName: 'Nobody',
          birthDate: '1990-01-01',
          joiningDate: '2010-01-01',
          designationId: 1,
          depotLocationId: 1,
          ifsc: 'SBIN0DEMO01',
          bankAccount: '12345678',
          pfApplicable: false,
          reason: 'E2E',
        }),
      ).toBe(403);
    },
  );
});
