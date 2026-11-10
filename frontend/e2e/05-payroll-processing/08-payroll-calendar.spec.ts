import { expect, type Page, test } from '@playwright/test';
import { businessToday, decide, t, workAs } from '../support/app';

/**
 * M-10 Payroll Calendar & Cycle Setup (plan 05 phase 05f; SRS-15-33, SRS M-10): the System Administrator at
 * Headquarters proposes a period of the next financial year (June, which no cycle uses), the Payroll Checker
 * at Headquarters approves it on W-01, and the period shows approved; a window opening before the lock target
 * is refused before it reaches the Checker. Re-runnable: a second run changes the same period.
 */
const ADMIN = 'sysadmin@fci-demo.test';
const CHECKER = 'checker.hq@fci-demo.test';
const admin = { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' };
const checker = { role: 'PAYROLL_CHECKER', office: 'HQ001' };

/** The next financial year ("2027-28") and its June ("2027-06", label "JUN 2027"). */
function nextYear(): { finYear: string; june: string; label: string } {
  const [year, month] = businessToday().split('-').map(Number) as [number, number];
  const start = month >= 4 ? year + 1 : year;
  return { finYear: `${start}-${String(start + 1).slice(2)}`, june: `${start}-06`, label: `JUN ${start}` };
}

/** FY Calendar holds the year select as drawn (OD-121). */
async function openYear(page: Page, finYear: string): Promise<void> {
  await page.getByRole('button', { name: t('en', 'm10w.fyCalendar') }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel(t('en', 'm10.finYear')).selectOption(finYear);
  await dialog.getByRole('button', { name: t('en', 'common.close') }).click();
}

test.describe.serial('Payroll calendar (M-10, plan 05f)', () => {
  // SC-05-0054 (UC scenario of the KB, cited here; outside the e2e_trace review scope).
  test(
    'SRS-15-33: the Administrator proposes a period and the Checker at HQ approves it',
    { tag: ['@SRS-15-33', '@SRS-15-1', '@positive'] },
    async ({ browser }) => {
      const { finYear, june, label } = nextYear();
      const page = await browser.newPage();
      await workAs(page, 'en', ADMIN, admin, 'M-10');
      await openYear(page, finYear);
      await expect(page.getByText(t('en', 'm10w.calendar', [finYear]))).toBeVisible();
      await page
        .getByRole('main')
        .getByRole('button', { name: t('en', 'm10w.maintain'), exact: true })
        .click();
      const pick = page.getByRole('dialog', { name: t('en', 'm10w.maintain') });
      await pick.getByLabel(t('en', 'm10.month')).selectOption(june);
      await pick.getByRole('button', { name: t('en', 'm10w.continue') }).click();
      const dialog = page.getByRole('dialog');
      const fill = async (key: string, day: string) =>
        dialog.getByLabel(t('en', `m10.field.${key}`)).fill(`${june}-${day}`);
      await fill('freezeTarget', '20');
      await fill('lockTarget', '28');
      // A window opening before the lock target is refused before it reaches the Checker.
      await fill('supplementaryFrom', '27');
      await fill('supplementaryTo', '30');
      await fill('mdpFrom', '28');
      await fill('mdpTo', '30');
      const reason = `E2E calendar ${finYear} ${Date.now()}`;
      await dialog.getByLabel(t('en', 'common.reason')).fill(reason);
      await dialog.getByRole('button', { name: t('en', 'm10.submit') }).click();
      await expect(
        page.getByText(t('en', 'error.CAL-422-PERIOD', [june, 'SUPPLEMENTARY_BEFORE_LOCK'])),
      ).toBeVisible();
      await fill('supplementaryFrom', '28');
      await dialog.getByRole('button', { name: t('en', 'm10.submit') }).click();
      await expect(page.getByRole('status')).toContainText(t('en', 'm10.proposed'));
      await expect(page.getByRole('note')).toContainText(/approval \d+/);

      const hq = await browser.newPage();
      await workAs(hq, 'en', CHECKER, checker, 'W-01');
      await decide(hq, new RegExp(reason), 'APPROVE');

      await page.reload();
      await openYear(page, finYear);
      // The period is defined now, so it has its row; no cycle yet at this unit.
      const row = page.getByRole('row', { name: new RegExp(label) });
      await expect(row).toContainText(t('en', 'd01.state.NOT_INITIATED'));
      await expect(page.getByRole('note')).toHaveCount(0);
    },
  );
});
