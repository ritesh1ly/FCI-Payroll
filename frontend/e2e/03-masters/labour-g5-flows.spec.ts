import { expect, test } from '@playwright/test';
import { businessToday, decide, errorAlert, t, workAs } from '../support/app';
import { openCapture } from '../support/labour';
import { CHECKER, checker } from '../support/payroll-support';

/**
 * Group g5 labour flows (modules 20-22; S\GROUP_WORK_g5.md): the M-02 transfer started with its
 * order (SRS l.4429, l.4937; OD-88, OD-93), an NWNP transfer no longer refused for attendance (the
 * 2026-11-03 defect; OD-87), a separation held until its order is entered, the M-09 register's
 * Effective to and Status columns, the Gang Strength Report with the shortfall alert the Depot
 * Manager closes with a reason (SRS-2-48; OD-90) and the Labour Dealing Hand only sees, the I-01
 * depot and payroll-type filters with the frozen days and the parked correction shown with its
 * reason (SRS-2-3, SRS-2-39), and the gang's slips on I-02.
 *
 * Demo data V060_9 (Okhla NWNP Gangs 1 and 2, L30701 ... L30704) and V077_32 (Okhla Gang 7,
 * L50801 ... L50804, the previous month's marks and slips, the shortfall of the 10th).
 * Re-runnable: the transfer is rejected by the Checker; the shortfall alert closed by an earlier
 * run is found closed.
 */
const HR = 'hr.delhi@fci-demo.test';
const hr = { role: 'ESTABLISHMENT_HR', office: 'DH001' };
const LDH = 'ldh.depot@fci-demo.test';
const ldh = { role: 'LABOUR_DEALING_HAND', office: 'DP-DL01' };
const DM = 'dm.okhla@fci-demo.test';
const dm = { role: 'DEPOT_MANAGER', office: 'DP-DL01' };
const run = String(Math.floor(Math.random() * 9000) + 1000);

function previousMonth(): string {
  const [y, m] = businessToday().split('-').map(Number) as [number, number];
  return m === 1 ? `${y - 1}-12` : `${y}-${String(m - 1).padStart(2, '0')}`;
}

/** The first day of the month after today (IST): a transfer date no receiving cycle has reached yet. */
function firstOfNextMonth(): string {
  const [y, m] = businessToday().split('-').map(Number) as [number, number];
  return m === 12 ? `${y + 1}-01-01` : `${y}-${String(m + 1).padStart(2, '0')}-01`;
}

function plusDays(day: string, n: number): string {
  const d = new Date(`${day}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

test.describe.serial('Group g5 labour flows', () => {
  // SC-22-0031 (defect 2026-11-03): an NWNP labour is transferred without attendance; the order goes with it.
  test(
    'SRS-2-6: HR transfers an NWNP labour from M-02 with its order; the Payroll Checker rejects it',
    { tag: ['@SRS-2-6', '@SRS-5-14', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', HR, hr, 'M-02');
      await page.getByLabel(new RegExp(`^${t('en', 'm02.search')}`)).fill('L30703');
      const table = page.getByRole('table', { name: t('en', 'screen.M-02') });
      const row = table.getByRole('row').filter({ hasText: 'L30703' });
      await row.getByRole('button', { name: t('en', 'action.m02.transfer') }).click();
      await expect(
        page.getByRole('heading', { name: t('en', 'm09.move.TRANSFER', ['L30703']) }).first(),
      ).toBeVisible();
      const form = page;
      const send = form.getByRole('button', { name: t('en', 'action.m09.sendMove') });
      await expect(send).toBeDisabled();
      await form.getByLabel(t('en', 'm09.gangSearch')).fill('G006');
      const target = form.getByLabel(new RegExp(`^${t('en', 'm09.toGang')}`));
      await expect(target.locator('option', { hasText: 'DP-DL01-G006' })).toHaveCount(1);
      await target.selectOption({ index: 1 });
      await form.getByLabel(new RegExp(`^${t('en', 'm09.transferDate')}`)).fill(plusDays(businessToday(), 1));
      await form.getByLabel(new RegExp(`^${t('en', 'm09.moveReason')}`)).fill(`E2E NWNP transfer ${run}`);
      // SRS l.4429: refused until the order is entered.
      await expect(send).toHaveAttribute('title', t('en', 'm02.why.order'));
      await form.getByLabel(new RegExp(`^${t('en', 'm02.orderReference')}`)).fill(`TO/E2E/${run}`);
      await form.getByLabel(new RegExp(`^${t('en', 'm02.issuingAuthority')}`)).fill('Area Manager Delhi');
      await expect(send).toBeEnabled();
      // OD-86 / SRS-2-6: DO Delhi HQ's current month is a computed run (demo seed V082_22_1, OD-150), so the
      // receiving site's payroll of this month is past open and a transfer dated in it is refused.
      await form.getByLabel(new RegExp(`^${t('en', 'm09.transferDate')}`)).fill(businessToday());
      await send.click();
      await expect(errorAlert(page)).toContainText(t('en', 'error.LBR-422-TRANSFER', ['RECEIVING_CLOSED']));
      // A future date is allowed (TransferRules): the first day of next month, a month no cycle has started.
      await form.getByLabel(new RegExp(`^${t('en', 'm09.transferDate')}`)).fill(firstOfNextMonth());
      await send.click();
      await expect(page.getByText(t('en', 'm09.moveSent'))).toBeVisible();

      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(`E2E NWNP transfer ${run}`), 'REJECT', `E2E not this time ${run}`);
    },
  );

  test(
    'SRS l.4429: a separation is not sent without its order',
    { tag: ['@SRS-2-30', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', HR, hr, 'M-02');
      await page.getByLabel(new RegExp(`^${t('en', 'm02.search')}`)).fill('L50804');
      const row = page
        .getByRole('table', { name: t('en', 'screen.M-02') })
        .getByRole('row')
        .filter({ hasText: 'L50804' });
      await row.getByRole('button', { name: t('en', 'action.m02.separate') }).click();
      const send = page.getByRole('button', { name: t('en', 'action.m02.send') });
      await page.getByLabel(new RegExp(`^${t('en', 'm09.changeReason')}`)).fill('E2E resignation letter');
      await expect(send).toBeDisabled();
      await expect(send).toHaveAttribute('title', t('en', 'm02.why.lifecycle'));
      await page.getByLabel(new RegExp(`^${t('en', 'm02.orderReference')}`)).fill('SO/E2E');
      await page.getByLabel(new RegExp(`^${t('en', 'm02.issuingAuthority')}`)).fill('Area Manager Delhi');
      await expect(send).toBeEnabled();
      // Not sent: the demo labour stays in the gang for the other checks.
    },
  );

  // SC-20-0134: the shortfall alert to the Depot Manager; the Ancillary Pool has no report.
  test(
    'SRS-2-48: the Gang Strength Report shows the shortfall; only the Depot Manager closes the alert with a reason',
    { tag: ['@SRS-2-48', '@positive'] },
    async ({ page }) => {
      const month = previousMonth();
      await workAs(page, 'en', LDH, ldh, 'M-09');
      const register = page.getByRole('table', { name: t('en', 'screen.M-09') });
      await expect(register.getByRole('columnheader', { name: new RegExp(t('en', 'm09.to')) })).toBeVisible();
      await expect(
        register.getByRole('columnheader', { name: new RegExp(t('en', 'm09.statusCol')) }),
      ).toBeVisible();
      const gang7 = register.getByRole('row').filter({ hasText: 'Okhla Gang 7' });
      await gang7.getByRole('button', { name: t('en', 'm09.open') }).click();
      const report = page.getByRole('region', { name: t('en', 'm09.strengthReport') });
      await report.locator('input[type="month"]').fill(month);
      const day10 = report.getByRole('row').filter({ hasText: 'L50804' }).first();
      await expect(day10).toContainText('3');
      const closeButton = day10.getByRole('button', { name: /^Close the shortfall alert of/ });
      if ((await closeButton.count()) > 0) {
        await expect(closeButton).toBeDisabled();
        await expect(closeButton).toHaveAttribute('title', t('en', 'm09.why.closeShortfall'));
      }

      await workAs(page, 'en', DM, dm, 'M-09');
      await page
        .getByRole('table', { name: t('en', 'screen.M-09') })
        .getByRole('row')
        .filter({ hasText: 'Okhla Gang 7' })
        .getByRole('button', { name: t('en', 'm09.open') })
        .click();
      const own = page.getByRole('region', { name: t('en', 'm09.strengthReport') });
      await own.locator('input[type="month"]').fill(month);
      const row = own.getByRole('row').filter({ hasText: 'L50804' }).first();
      await expect(row).toBeVisible();
      const button = row.getByRole('button', { name: /^Close the shortfall alert of/ });
      if ((await button.count()) > 0) {
        await button.click();
        const dialog = page.getByRole('dialog');
        await dialog.getByRole('textbox').fill(`E2E rake late; pool labour sent ${run}`);
        await dialog.getByRole('button', { name: t('en', 'common.submit') }).click();
        await expect(page.getByText(t('en', 'm09.shortfallClosed'))).toBeVisible();
      }
      await expect(row).toContainText(t('en', 'm09.alertClosed'));
    },
  );

  test(
    'SRS-2-3: I-01 narrows the gangs by depot and payroll type, marks the frozen days and shows the correction parked',
    { tag: ['@SRS-2-3', '@SRS-2-39', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', LDH, ldh, 'I-01');
      await openCapture(page, 'I-01');
      await page
        .getByLabel(new RegExp(`^${t('en', 'm02.depot')}`))
        .selectOption({ label: 'DP-DL01 - Depot Okhla' });
      await page.getByLabel(new RegExp(`^${t('en', 'm02.payrollType')}`)).selectOption('DEPT_LABOUR');
      await page
        .getByRole('combobox', { name: new RegExp(`^${t('en', 'm09.gang')}`) })
        .selectOption({ label: 'DP-DL01-G007 - Okhla Gang 7' });
      await page.getByLabel(new RegExp(`^${t('en', 'i01.month')}`)).fill(previousMonth());
      const grid = page.getByRole('table', { name: t('en', 'i01.grid') });
      await expect(grid.getByTitle(t('en', 'i01.frozen')).first()).toBeVisible();
      await expect(page.getByText(t('en', 'i01.frozenNote'))).toBeVisible();
      const correct = page
        .getByTestId('i01-grid')
        .getByRole('button', { name: t('en', 'action.i01.correct') });
      await expect(correct).toBeDisabled();
      await expect(correct).toHaveAttribute('title', t('en', 'i01.why.correction'));
    },
  );

  test(
    "SRS-2-4: I-02 lists the gang's work-slips of the month",
    { tag: ['@SRS-2-4', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', LDH, ldh, 'I-02');
      await openCapture(page, 'I-02');
      await page
        .getByRole('combobox', { name: new RegExp(`^${t('en', 'm09.gang')}`) })
        .selectOption({ label: 'DP-DL01-G007 - Okhla Gang 7' });
      await page.getByLabel(new RegExp(`^${t('en', 'i01.month')}`)).fill(previousMonth());
      const slips = page.getByRole('table', { name: t('en', 'i02.slips') });
      await expect(slips.getByRole('row').filter({ hasText: /WS-\d+-\d{8}-1/ })).toHaveCount(2);
    },
  );
});
