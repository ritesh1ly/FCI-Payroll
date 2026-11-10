import { expect, test } from '@playwright/test';
import { apiStatus, businessToday, decide, t, workAs } from '../support/app';
import { MAKER, maker } from '../support/payroll-support';

/**
 * Module 15 MIS & reporting through the UI (docs/plan/15-mis-reporting.md; SRS-18-10, SRS-18-23,
 * SRS-18-24, SRS-18-28): O-04 lists the catalogue with where each output is produced or why it is
 * Blocked, and the Component-wise Summary of a month with its finality and reconciliation; M-12: the
 * Report Administrator proposes a version and the Rule Approver approves it.
 *
 * Demo data V051_2 (catalogue) and V051_9 (report.admin@, HQ001). Re-runnable: each proposal takes
 * a date no earlier run used.
 */
const ADMIN = 'report.admin@fci-demo.test';
const APPROVER = 'rule.approver@fci-demo.test';

test.describe.serial('O-04 / M-12 MIS & reporting', () => {
  test(
    'SRS-18-24: the catalogue shows where each output is produced or why it is blocked',
    { tag: ['@SRS-18-24', '@SRS-18-14', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'O-04');
      const catalogue = page.getByRole('table', { name: t('en', 'o04.catalogue') });
      // The catalogue search sits in its closed section (the pack draws no toolbar there).
      await page.getByTestId('o04-catalogue-filters').locator('summary').click();
      await page.getByLabel(t('en', 'o04.search'), { exact: true }).fill('Component-wise');
      await expect(catalogue.getByText(t('en', 'o04.status.AVAILABLE')).first()).toBeVisible();
      await page.getByLabel(t('en', 'o04.search'), { exact: true }).fill('Salary Control Register');
      await expect(catalogue.getByText(t('en', 'o04.status.BLOCKED')).first()).toBeVisible();
      await expect(catalogue.getByText(/SRS-18-16/).first()).toBeVisible();
    },
  );

  test(
    'SRS-18-28: the Component-wise Summary of a month says whether it is final and reconciled',
    { tag: ['@SRS-18-28', '@SRS-18-23', '@SRS-18-8', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'O-04');
      await page.getByTestId('o04-summary-section').locator('summary').click();
      await page.getByLabel(t('en', 'o04.month')).fill(businessToday().slice(0, 7));
      await page.getByLabel(t('en', 'o04.level')).selectOption('ZONE');
      // FRS §23.9: generating writes report_output, so it runs on the press, never on a filter change.
      await page.getByTestId('o04-generate-table').click();
      await expect(page.getByRole('status').first()).toBeVisible();
      // The catalogue is the Payroll Maker's to read (O-04 VIEW).
      expect(await apiStatus(page, '/v1/reports/catalogue')).toBe(200);
    },
  );

  test(
    'SRS-18-10: the Report Administrator proposes a version and the Rule Approver approves it',
    { tag: ['@SRS-18-10', '@BR-MIS-003', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', ADMIN, { role: 'REPORT_ADMINISTRATOR', office: 'HQ001' }, 'M-12');
      await page.getByRole('button', { name: t('en', 'm12w.act.versions') }).click();
      // The drawn catalogue holds every definition (146 with demo V081), paged at 20 (SRS-NF-3): the
      // version history shows 100 a page and is paged on until the report is listed.
      const versions = page.getByTestId('m12-versions');
      await versions.getByLabel(t('en', 'page.size')).first().selectOption('100');
      await expect(versions.getByText(/^1–(100 of \d+|(\d+) of \2)$/).first()).toBeVisible();
      const table = versions.getByRole('table', { name: t('en', 'screen.M-12') });
      const row = table.getByRole('row', { name: /Leave Recovery Report/ }).first();
      for (let turn = 1; turn < 3 && (await row.count()) === 0; turn += 1) {
        await versions
          .getByRole('button', { name: t('en', 'page.next') })
          .first()
          .click();
        await expect(
          versions.getByText(new RegExp(`^${turn * 100 + 1}–\\d+ of \\d+$`)).first(),
        ).toBeVisible();
      }
      await row.getByRole('button', { name: t('en', 'm12.versions') }).click();
      const day = new Date(Date.now() + (60 + Math.floor(Math.random() * 600)) * 86400000)
        .toISOString()
        .slice(0, 10);
      await page.getByLabel(t('en', 'm12.effective')).fill(day);
      await page.getByLabel(t('en', 'm12.blocked')).check();
      await page.getByLabel(t('en', 'm12.blockedReason')).fill('E2E: layout still awaited');
      await page.getByRole('button', { name: t('en', 'action.m12.propose') }).click();
      await expect(page.getByText(t('en', 'm12.proposed'))).toBeVisible();
      await workAs(page, 'en', APPROVER, { role: 'RULE_APPROVER', office: 'HQ001' }, 'W-01');
      await decide(page, /LEAVE_RECOVERY version/, 'APPROVE');
    },
  );

  test(
    'SRS-18-10: a Payroll Maker never proposes a version',
    { tag: ['@SRS-18-10', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'O-04');
      expect(
        await apiStatus(page, '/v1/report-definitions/LEAVE_RECOVERY/versions', 'POST', {
          storeMode: 'REGENERATE',
          blocked: false,
          periodLockRequired: false,
          effectiveFrom: businessToday(),
        }),
      ).toBe(403);
    },
  );

  test(
    'SRS-18-8: the Export waits with the right reason while the summary loads or after it fails, never "totals do not match"',
    { tag: ['@SRS-18-8', '@negative'] },
    async ({ page }) => {
      // The summary read held, then failing (a 500 is final), then released: a fault cannot be made on demand.
      let mode: 'hold' | 'fail' | 'pass' = 'hold';
      let release: () => void = () => undefined;
      const held = new Promise<void>((resolve) => {
        release = resolve;
      });
      const unavailable = { errorCode: 'COM-500-INTERNAL', messageKey: 'error.COM-500-INTERNAL', args: [] };
      await page.route(
        (url) => url.pathname.endsWith('/v1/reports/component-summary'),
        async (route) => {
          if (mode === 'hold') await held;
          if (mode === 'fail') return route.fulfill({ status: 500, json: unavailable });
          return route.continue();
        },
      );
      await workAs(page, 'en', MAKER, maker, 'O-04');
      const section = page.getByTestId('o04-summary-section');
      await section.locator('summary').click();
      await page.getByLabel(t('en', 'o04.month')).fill(businessToday().slice(0, 7));
      const exportButton = section.getByRole('button', { name: t('en', 'action.export'), exact: true });
      await expect(exportButton).toBeDisabled();
      await expect(exportButton).toHaveAttribute('title', t('en', 'o04.why.generate'));
      await page.getByTestId('o04-generate-table').click();
      await expect(exportButton).toHaveAttribute('title', t('en', 'o04.why.loading'));
      mode = 'fail';
      release();
      await expect(page.locator('.fl-error[role="alert"]')).toBeVisible();
      await expect(exportButton).toHaveAttribute('title', t('en', 'o04.why.error'));
      // No month (the picker cleared) names the month, whatever the summary did.
      await page.getByLabel(t('en', 'o04.month')).fill('');
      await expect(exportButton).toHaveAttribute('title', t('en', 'o04.why.month'));
      mode = 'pass';
    },
  );
});
