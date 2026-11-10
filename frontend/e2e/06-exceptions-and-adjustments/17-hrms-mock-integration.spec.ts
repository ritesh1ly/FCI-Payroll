import { expect, test } from '@playwright/test';
import { sidebarCodes, t, workAs } from '../support/app';
import { MAKER, maker } from '../support/payroll-support';

/**
 * Module 23 X-03 with Payroll CALLING the HRMS APIs (owner 2026-11-06; SRS-INT-1, SRS-INT-4,
 * SRS-INT-6, SRS-INT-33): the stack is started with the `hrms-mock` profile (every API OUTBOUND at
 * FCI_HRMS_BASE_URL, the api-key and login from the environment), so the HRMS APIs list shows each API
 * as called by Payroll with its path, and Test connection on the payment-status interface reaches the
 * HRMS (the FCI HRMS mock server) with the api-key, takes a bearer token with the configured login and
 * reports it. Runs only when E2E_HRMS_MOCK=1 names such a stack; the mock must hold Payroll's demo
 * employees (docs/integration/HRMS_MOCK_ALIGNMENT.md).
 */
const ADMIN = 'sysadmin@fci-demo.test';
const admin = { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' };

test.describe.serial('Module 23 HRMS mock integration', () => {
  test.skip(process.env.E2E_HRMS_MOCK !== '1', 'needs a stack started with -ExtraProfiles hrms-mock');

  // SC-23-0027
  test(
    'SRS-INT-33: the Administrator tests the HRMS connection: the mock answers and a token is obtained',
    { tag: ['@SRS-INT-33', '@SRS-INT-4', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', ADMIN, admin, 'X-03');
      // The per-interface test opens from the drawn Test connection (WF-079).
      await page.getByRole('button', { name: t('en', 'x03.testAct') }).click();
      const tests = page.getByRole('table', { name: t('en', 'x03.testAct') });
      // The interfaces fill more than one page: the list opened last has the last pager.
      await page.getByLabel(t('en', 'page.size')).last().selectOption('100');
      await tests.getByRole('button', { name: t('en', 'x03.testOf', ['HRMS_PAYMENT_STATUS']) }).click();
      await expect(page.getByText(/HRMS answered HTTP 200 after 1 attempt\(s\); token obtained/)).toBeVisible(
        {
          timeout: 20_000,
        },
      );
    },
  );

  test(
    'SRS-INT-6: the HRMS APIs list shows each API as called by Payroll, with its path',
    { tag: ['@SRS-INT-6', '@SRS-INT-1', '@positive'] },
    async ({ page }) => {
      // The catalogue opens from the drawn Contract, the Administrator's (WF-079).
      await workAs(page, 'en', ADMIN, admin, 'X-03');
      await page.getByRole('button', { name: t('en', 'x03.contractAct'), exact: true }).click();
      const apis = page.getByTestId('x03-hrms-apis');
      await apis.getByLabel(t('en', 'x03.hrms.direction')).selectOption('OUTBOUND');
      const table = apis.getByRole('table', { name: t('en', 'x03.hrms.title') });
      const status = table.getByRole('row').filter({ hasText: 'get_loan_status' });
      await expect(status).toContainText(t('en', 'x03.hrms.direction.OUTBOUND'));
      await expect(status).toContainText(t('en', 'x03.hrms.configured'));
      await expect(status).toContainText('/get-loan-status');
      // Nothing is published inbound while every API is set for the outbound direction.
      await apis.getByLabel(t('en', 'x03.hrms.direction')).selectOption('INBOUND');
      await expect(apis.getByText(t('en', 'x03.hrms.noApis'))).toBeVisible();
    },
  );

  test(
    'SRS-INT-33: a viewer cannot test the connection',
    { tag: ['@SRS-INT-33', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'X-03');
      expect(await sidebarCodes(page)).toContain('X-03');
      // Drawn over the list, disabled with the reason (OD-121); the per-interface test sits behind it.
      const testAct = page.getByRole('button', { name: t('en', 'x03.testAct') });
      await expect(testAct).toBeDisabled();
      await expect(testAct).toHaveAttribute('title', t('en', 'x03.why.admin'));
    },
  );
});
