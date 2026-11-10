import { expect, test } from '@playwright/test';
import { decide, t, workAs } from '../support/app';

/**
 * O-02 emergency stop of payment release (plan 05 phase 05h; SRS-15-35, FRS BR-SEC-007 (SRS label BR-SEC-006, CLR-43)): Finance at Headquarters
 * suspends the bank-file release of Delhi HQ (DH001) with a reason, asks for its resumption, and the Payroll
 * Checker at Headquarters approves it on W-01; the suspension then shows resumed. As Wireframes v6.1 draws O-02
 * the stop opens from ⛔ Emergency stop and the suspensions sit in a closed section. Re-runnable: each run adds
 * one suspension and resumes it.
 */
const FINANCE = 'finance.hq@fci-demo.test';
const CHECKER = 'checker.hq@fci-demo.test';
const finance = { role: 'FINANCE', office: 'HQ001' };
const checker = { role: 'PAYROLL_CHECKER', office: 'HQ001' };

test.describe.serial('Release suspension (O-02, plan 05h)', () => {
  test(
    'SRS-15-35: Finance at HQ suspends a release and the HQ Payroll Checker approves its resumption',
    { tag: ['@SRS-15-35', '@positive'] },
    async ({ browser }) => {
      const page = await browser.newPage();
      await workAs(page, 'en', FINANCE, finance, 'O-02');
      await page.getByRole('button', { name: t('en', 'o02w.act.stop') }).click();
      const dialog = page.getByRole('dialog');
      await dialog.getByLabel(t('en', 'o02.scope')).selectOption('UNIT');
      await dialog.getByLabel(t('en', 'o02.unitCode')).fill('DH001');
      await dialog.getByLabel(t('en', 'o02.channel')).selectOption('BANK_FILE');
      const reason = `E2E bank file stop ${Date.now()}`;
      await dialog.getByLabel(t('en', 'common.reason')).fill(reason);
      await dialog.getByRole('button', { name: t('en', 'o02.suspend') }).click();
      await expect(page.getByRole('status')).toContainText(t('en', 'o02.suspendedDone'));
      const row = page.getByRole('row', { name: new RegExp(reason) });
      await expect(row).toContainText(t('en', 'o02.active'));

      await row.getByRole('button', { name: /Request resumption/ }).click();
      const why = `E2E reconciled ${Date.now()}`;
      await page.getByRole('dialog').getByRole('textbox').fill(why);
      await page
        .getByRole('dialog')
        .getByRole('button', { name: t('en', 'common.submit') })
        .click();
      await expect(page.getByRole('status')).toContainText(t('en', 'o02.resumeRequested'));

      const hq = await browser.newPage();
      await workAs(hq, 'en', CHECKER, checker, 'W-01');
      await decide(hq, new RegExp(why), 'APPROVE');

      await page.reload();
      // The suspensions stay in their closed section after a reload: open it by its summary (the list inside repeats the name).
      const suspensions = page.locator('[data-testid="o02-suspensions"] > summary');
      await expect(suspensions).toHaveText(t('en', 'o02w.section.suspensions'));
      await suspensions.click();
      const resumed = page.getByRole('row', { name: new RegExp(reason) });
      await expect(resumed).toContainText(CHECKER);
      await expect(resumed).not.toContainText(t('en', 'o02.active'));
    },
  );
});
