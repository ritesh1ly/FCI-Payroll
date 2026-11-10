import { expect, test } from '@playwright/test';
import { errorAlert, t, workAs } from '../support/app';

/**
 * The what-if Income Tax Forecast through the UI (docs/plan/08-income-tax.md phase 08-C09, D-14;
 * SRS-7-28): the payroll desk varies a member's regime on C-07 and sees the actual and the what-if
 * side by side under the SIMULATION marker; the employee does the same on their own year on E-01.
 * In a deployment the demo year has no approved slabs, so each side may name what is missing; the
 * run itself is recorded either way. Re-runnable: a what-if changes nothing.
 */
const MAKER = 'tax.maker.delhi@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };
const MEMBER = 'tax.old@fci-demo.test';
const member = { role: 'EMPLOYEE_ESEVA' };

test.describe('Module 08 what-if tax forecast', () => {
  test(
    'SRS-7-28: the desk runs a what-if for a member and nothing is sent until something is varied',
    { tag: ['@SRS-7-28', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'C-07');
      await page.getByLabel(t('en', 'm01.eis')).fill('870001');
      await page.getByRole('button', { name: t('en', 'c07.compute'), exact: true }).click();
      await expect(page.getByRole('note')).toContainText(t('en', 'c07w.banner'));
      const run = page.getByRole('button', { name: t('en', 'c07w.run') });
      await expect(run).toBeDisabled();
      await expect(page.getByRole('list', { name: t('en', 'c07w.problems') })).toContainText(
        t('en', 'c07w.nothingVaried'),
      );
      await page.getByLabel(t('en', 'e01t.regime')).selectOption('NEW');
      await run.click();
      const outcome = page.getByRole('table', { name: t('en', 'c07w.outcome') });
      await expect(outcome).toContainText(t('en', 'e01t.regime.NEW'));
      await expect(outcome).toContainText(t('en', 'e01t.regime.OLD'));
      await expect(page.getByText(/^Run \d+ recorded/)).toBeVisible();
    },
  );

  test(
    'SRS-7-28: a section not in force is refused and the refusal stays on screen',
    { tag: ['@SRS-7-28', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'C-07');
      await page.getByLabel(t('en', 'm01.eis')).fill('870001');
      await page.getByRole('button', { name: t('en', 'c07.compute'), exact: true }).click();
      await page.getByLabel(t('en', 'm18s.section')).last().fill('80ZZZ');
      await page.getByLabel(t('en', 'c07.claimed')).last().fill('1000');
      await page.getByRole('button', { name: t('en', 'c07w.run') }).click();
      await expect(errorAlert(page)).toContainText('80ZZZ is not a section in force');
    },
  );

  test(
    'SRS-7-28: the employee runs a what-if on their own year from E-01',
    { tag: ['@SRS-7-28', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MEMBER, member, 'E-01');
      await expect(page.getByText(t('en', 'c07w.title'))).toBeVisible();
      await page.getByLabel(t('en', 'c07w.salaryChange')).fill('-50000');
      await page.getByRole('button', { name: t('en', 'c07w.run') }).click();
      await expect(page.getByRole('table', { name: t('en', 'c07w.outcome') })).toBeVisible();
    },
  );
});
