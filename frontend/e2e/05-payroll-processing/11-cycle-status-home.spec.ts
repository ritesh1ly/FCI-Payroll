import { expect, test } from '@playwright/test';
import { t, workAs } from '../support/app';

/**
 * D-01 Home - Cycle Status (plan 05 phase 05i; SRS D-01, D-19, D-20): the Payroll Maker of Delhi HQ (DH001) lands
 * on the home and sees the cycle status of the unit above the cards: KPIs, payroll types and the blocking panel,
 * a figure without a source naming its module; the Payroll Checker at Headquarters sees the roll-up across units.
 */
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };
const hq = { role: 'PAYROLL_CHECKER', office: 'HQ001' };

test.describe('Home - Cycle Status (D-01, plan 05i)', () => {
  // SC-05-0043, SC-05-0047 (UC scenario of the KB, cited here; outside the e2e_trace review scope).
  test(
    'SRS D-01: the unit maker sees KPIs, payroll types and what is blocking the cycle on the home',
    { tag: ['@SRS-15-14', '@SRS-15-39', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', 'maker.delhi@fci-demo.test', maker, 'D-01');
      // The scope is on the drawn period band (Organisational Scope, SRS D-01 field table).
      await expect(page.locator('main').getByText(/Delhi/).first()).toBeVisible();
      await expect(page.getByText(t('en', 'd01.kpi.net'))).toBeVisible();
      await expect(
        page.getByRole('row', { name: new RegExp(t('en', 'd01.payrollType.DPS_LABOUR')) }),
      ).toContainText(t('en', 'd01.unavailable.AWAITING_FCI'));
      // OD-35: counted from the data (no "available when module N is built").
      await expect(page.getByText(t('en', 'd01.blocker.INCENTIVE_NORMS.basis'))).toBeVisible();
      await expect(page.getByText(t('en', 'd01.blocker.INCENTIVE_NORMS')).first()).toBeVisible();
      await expect(page.getByRole('link', { name: t('en', 'd01.resumeCompute') })).toBeVisible();
      await page.goto('/payroll');
      await expect(page.getByText(t('en', 'd01.blocking'))).toBeVisible();
    },
  );

  test(
    'SRS D-01: Headquarters sees the read-only roll-up across the cycle units, without the maker actions',
    { tag: ['@SRS-15-14', '@SRS-15-39', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', 'checker.hq@fci-demo.test', hq, 'D-01');
      await page.getByTestId('d01-units').locator('summary').click();
      await expect(page.getByRole('row', { name: /DH001/ })).toBeVisible();
      await expect(page.getByRole('link', { name: t('en', 'd01.resumeCompute') })).toHaveCount(0);
    },
  );
});
