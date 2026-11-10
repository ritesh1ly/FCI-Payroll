import { expect, test } from '@playwright/test';
import { apiStatus, onSomePage, pageHref, sidebarCodes, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql } from '../support/db';
import { MAKER, maker } from '../support/payroll-support';

/**
 * X-03 Integration Monitor (plan docs/plan/23-integrations.md phases 23a / 23b; SRS-INT-6,
 * SRS-INT-16, SRS-INT-17, SRS-INT-31 … SRS-INT-33) through the UI: a viewer reads every interface
 * with its status, counts and read-only resilience parameters (US-GAP-345), the exchange windows
 * and the Integration Failure Report, the Administrator's actions disabled with their reason; the
 * Administrator opens an interface's Contract, tests a connection (no adapter yet: the outcome
 * is NO_ADAPTER, audited), finds Reprocess waiting for an adapter and writes an item off with a
 * reason; a role without X-03 does not see it.
 *
 * Demo data V062_2 (registry), V062_9 (HRMS pull settings, a PARTIAL window of HRMS_EMP_OTA).
 * Re-runnable: each run queues its own exception through the DB fixture.
 */
const ADMIN = 'sysadmin@fci-demo.test';
const admin = { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' };
const HR = 'hr.delhi@fci-demo.test';
const hr = { role: 'ESTABLISHMENT_HR', office: 'DH001' };
const run = Math.floor(Math.random() * 9000) + 1000;
const REASON = `E2E rejection ${run}`;

test.describe.serial('Module 23 X-03 Integration Monitor', () => {
  // G5-98 (UI #99): the read and refusal checks run on the demo data alone (V062_9); only the
  // checks of this run's own queued exception need the H2 database fixture.
  test.beforeAll(() => {
    if (!dbFixtureAvailable()) return;
    sql(
      `INSERT INTO interface_exception (run_id, staging_id, interface_id, rejection_reason, remediation_status, raised_at)` +
        ` SELECT s.run_id, s.staging_id, s.interface_id, '${REASON}', 'OPEN', CURRENT_TIMESTAMP` +
        ` FROM intf_staging_txn s WHERE s.request_id = 'DEMO-OTA-0002' AND s.txn_type = 'EMP_OTA';`,
    );
  });

  // SC-23-0022, SC-23-0024, SC-23-0025, SC-23-0026, SC-23-0054
  test(
    'SRS-INT-32: a viewer reads the interfaces, windows and the Integration Failure Report; the actions are the Administrator only',
    { tag: ['@SRS-INT-32', '@SRS-INT-33', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'X-03');
      // The drawn lists have a status filter and a search only (OD-121); the row is found page by page.
      const bulk = await onSomePage(page, page.locator('tr[data-code="HRMS_BULK_RECONCILE"]'));
      await expect(bulk).toContainText(t('en', 'x03.configured'));
      await expect(bulk).toContainText(t('en', 'x03.specNotHeld'));
      // SRS-INT-16 (US-GAP-345): the resilience parameters, read-only, for a viewer too (drawn View).
      await bulk.getByRole('button', { name: t('en', 'x03.resilienceOf', ['HRMS_BULK_RECONCILE']) }).click();
      const params = page.locator('.card').filter({
        has: page.getByRole('heading', { name: t('en', 'x03.resilienceOf', ['HRMS_BULK_RECONCILE']) }),
      });
      await expect(params).toContainText(t('en', 'x03.seconds', [30]));
      await expect(params).toContainText(t('en', 'x03.unconfirmed'));
      await expect(
        bulk.getByRole('button', { name: t('en', 'x03.contractOf', ['HRMS_BULK_RECONCILE']) }),
      ).toBeDisabled();
      expect(await apiStatus(page, '/v1/integration/interfaces/HRMS_BULK_RECONCILE', 'GET')).toBe(403);
      const search = page.getByPlaceholder(t('en', 'wf.searchInList'));
      await search.fill('HRMS_EMP_OTA');
      const ota = page.locator('tr[data-code="HRMS_EMP_OTA"]');
      await expect(ota).toContainText('INTF_PAY_EMP_OTA');
      // The demo seed V077_44 gives every HRMS interface its settings since this spec was written.
      await expect(ota).toContainText(t('en', 'x03.configured'));
      // Test connection is the Administrator's: drawn over the list, disabled with the reason (OD-121).
      const testAct = page.getByRole('button', { name: t('en', 'x03.testAct') });
      await expect(testAct).toBeDisabled();
      await expect(testAct).toHaveAttribute('title', t('en', 'x03.why.admin'));
      await search.fill('EPFO_ECR');
      await expect(page.locator('tr[data-code="EPFO_ECR"]')).toContainText(t('en', 'x03.noExchange'));
      await search.fill('');
      // The failure report and the exchange windows open from the drawn Exception Queue (WF-079).
      await page.getByRole('button', { name: t('en', 'x03.queueAct') }).click();
      const windows = page.getByRole('table', { name: t('en', 'x03.windows') });
      await expect(page.getByRole('heading', { name: t('en', 'x03.failureReport') })).toBeVisible();
      // UI #94: every viewer narrows the windows and the queue to one interface.
      // The queue and the windows each have the filter; the windows are drawn second.
      const windowFilter = page.getByLabel(t('en', 'x03.filterInterface')).last();
      await windowFilter.fill('HRMS_EMP_OTA');
      // Live pulls of the mock add windows, so the demo window is read after the filter.
      await expect(
        await onSomePage(page, windows.getByRole('row').filter({ hasText: 'DEMO-HRMS-EMP_OTA-1' })),
      ).toContainText(t('en', 'x03.status.PARTIAL'));
      await windowFilter.fill('EPFO_ECR');
      await expect(windows.getByRole('row').filter({ hasText: 'DEMO-HRMS-EMP_OTA-1' })).toHaveCount(0);
      await windowFilter.fill('');
      const queue = page.getByRole('table', { name: t('en', 'x03.queue') });
      await page.getByLabel(t('en', 'x03.filterInterface')).first().fill('HRMS_EMP_OTA');
      // The demo rejection (V062_9) is listed with its reason; Write off is the Administrator's.
      const demo = queue.getByRole('row').filter({ hasText: 'demo rejection' }).first();
      await expect(demo.getByRole('button', { name: /^Write off exception/ })).toBeDisabled();
      await expect(demo.getByRole('button', { name: /^Write off exception/ })).toHaveAttribute(
        'title',
        t('en', 'x03.reason.adminOnly'),
      );
      await expect(page.getByRole('link', { name: t('en', 'x03.fallbackAct') })).toHaveAttribute(
        'href',
        pageHref('/payroll/?s=X-07'),
      );
      expect(await apiStatus(page, '/v1/integration/exceptions/1/write-off', 'POST', { reason: 'E2E' })).toBe(
        403,
      );
    },
  );

  // SC-23-0020, SC-23-0023, SC-23-0026, SC-23-0027, SC-23-0028, SC-23-0047, SC-23-0054
  test(
    'SRS-INT-31: the Administrator reads a Contract, tests a connection and writes an exception off with a reason',
    { tag: ['@SRS-INT-31', '@SRS-INT-33', '@positive'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      await workAs(page, 'en', ADMIN, admin, 'X-03');
      const bulk = await onSomePage(page, page.locator('tr[data-code="HRMS_BULK_RECONCILE"]'));
      await bulk.getByRole('button', { name: t('en', 'x03.contractOf', ['HRMS_BULK_RECONCILE']) }).click();
      await expect(page.getByText(t('en', 'x03.contractTitle', ['HRMS_BULK_RECONCILE']))).toBeVisible();
      await expect(page.getByText(t('en', 'x03.resilienceInList'))).toBeVisible();
      // The per-interface test opens from the drawn Test connection (WF-079).
      await page.getByRole('button', { name: t('en', 'x03.testAct') }).click();
      const tests = page.getByRole('table', { name: t('en', 'x03.testAct') });
      const testOne = await onSomePage(
        page,
        tests.getByRole('button', { name: t('en', 'x03.testOf', ['HRMS_BULK_RECONCILE']) }),
      );
      await testOne.click();
      await expect(page.getByText(t('en', 'x03.tested', ['HRMS_BULK_RECONCILE', 'NO_ADAPTER']))).toBeVisible({
        timeout: 15_000,
      });

      await page.getByRole('button', { name: t('en', 'x03.queueAct') }).click();
      const item = page
        .getByRole('table', { name: t('en', 'x03.queue') })
        .getByRole('row')
        .filter({ hasText: REASON });
      const reprocess = item.getByRole('button', { name: /^Reprocess exception/ });
      await expect(reprocess).toBeDisabled();
      await expect(reprocess).toHaveAttribute('title', t('en', 'x03.why.noAdapter'));
      await item.getByRole('button', { name: /^Write off exception/ }).click();
      const dialog = page.getByRole('dialog');
      await expect(dialog.getByRole('button', { name: t('en', 'common.submit') })).toBeDisabled();
      await dialog.getByRole('textbox').fill(`Corrected at source ${run}`);
      await dialog.getByRole('button', { name: t('en', 'common.submit') }).click();
      await expect(page.getByText(/written off\.$/).first()).toBeVisible({ timeout: 15_000 });
      await expect(
        page
          .getByRole('table', { name: t('en', 'x03.queue') })
          .getByRole('row')
          .filter({ hasText: REASON }),
      ).toHaveCount(0);
      await page.getByRole('checkbox', { name: t('en', 'x03.openOnly') }).uncheck();
      await expect(
        page
          .getByRole('table', { name: t('en', 'x03.queue') })
          .getByRole('row')
          .filter({ hasText: REASON }),
      ).toContainText(t('en', 'x03.exStatus.WRITTEN_OFF'));
    },
  );

  test(
    'SRS-INT-6: the HRMS APIs of the Postman collection open from Contract (WF-079), read-only, and narrow',
    { tag: ['@SRS-INT-6', '@SRS-INT-16', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', ADMIN, admin, 'X-03');
      await page.getByRole('button', { name: t('en', 'x03.contractAct'), exact: true }).click();
      const apis = page.getByTestId('x03-hrms-apis');
      const table = apis.getByRole('table', { name: t('en', 'x03.hrms.title') });
      // Every API of the collection has a row, configured or not; the get_loan_status call is class A.
      const loanStatus = table.getByRole('row').filter({ hasText: 'get_loan_status' });
      await expect(loanStatus).toContainText(t('en', 'x03.hrms.class.REQUIRED'));
      // A stack that calls the HRMS mock sets every API for the outbound direction.
      const live = process.env.E2E_HRMS_LIVE === '1';
      await expect(loanStatus).toContainText(t('en', `x03.hrms.direction.${live ? 'OUTBOUND' : 'INBOUND'}`));
      // Read-only: the list has no button and the URL settings are not editable here.
      await expect(table.getByRole('button')).toHaveCount(0);
      // The filters narrow server-side: class B only, then a class with no match shows the empty state.
      await apis.getByLabel(t('en', 'x03.hrms.class')).selectOption('RULE_PENDING');
      await expect(table.getByRole('row').filter({ hasText: 'get_loan_status' })).toHaveCount(0);
      await expect(
        table
          .getByRole('row')
          .filter({ hasText: t('en', 'x03.hrms.class.RULE_PENDING') })
          .first(),
      ).toBeVisible();
      await apis.getByLabel(t('en', 'x03.hrms.class')).selectOption('REQUIRED');
      await apis.getByLabel(t('en', 'x03.hrms.direction')).selectOption(live ? 'INBOUND' : 'OUTBOUND');
      await expect(apis.getByText(t('en', 'x03.hrms.noApis'))).toBeVisible();
      // A tampered filter value is refused by the API, not accepted (400).
      expect(await apiStatus(page, '/v1/integration/hrms-apis?class=ZZZ', 'GET')).toBe(400);
      expect(await apiStatus(page, '/v1/integration/hrms-apis?days=91', 'GET')).toBe(400);
    },
  );

  test(
    'SRS-INT-33: a role without X-03 neither sees it nor reads its API',
    { tag: ['@SRS-INT-33', '@negative'] },
    async ({ page }) => {
      // M-01, not M-02: M-02 is a step-up screen and asks the OTP once the last step-up has lapsed.
      await workAs(page, 'en', HR, hr, 'M-01');
      expect(await sidebarCodes(page)).not.toContain('X-03');
      expect(await apiStatus(page, '/v1/integration/interfaces', 'GET')).toBe(403);
      expect(await apiStatus(page, '/v1/integration/hrms-apis', 'GET')).toBe(403);
    },
  );

  // OD-148 (WF-2002): the member e-mail channel is an enterprise interface of X-03 (V082_61); the demo seed
  // V082_62 gives it settings and a breaker. Its queue, quota and bounce counts are API-only.
  test(
    'SRS-INT-16: the member e-mail interface reads configured, in English and Hindi, and its channel counts answer X-03',
    { tag: ['@SRS-INT-16', '@SRS-INT-6', '@SRS-17-12', '@positive'] },
    async ({ page }) => {
      for (const lang of ['en', 'hi'] as const) {
        await workAs(page, lang, MAKER, maker, 'X-03');
        await page.getByPlaceholder(t(lang, 'wf.searchInList')).fill('MEMBER_EMAIL');
        const row = page.locator('tr[data-code="MEMBER_EMAIL"]');
        await expect(row).toContainText(`SMS_EMAIL - ${t(lang, 'x03.cp.SMS_EMAIL')}`);
        await expect(row).toContainText('Member e-mail (payslip, Form 16)');
        await expect(row).toContainText(t(lang, 'x03.dir.OUTBOUND'));
        await expect(row).toContainText(t(lang, 'x03.configured'));
        await expect(row).not.toContainText(t(lang, 'x03.breaker.OPEN'));
      }
      // The channel status (capacity, pause, queue by status, bounces) is X-03 VIEW; the maker holds it.
      expect(await apiStatus(page, '/v1/document-mails/channel', 'GET')).toBe(200);
    },
  );

  test(
    'SRS-INT-16: a role without X-03 reads no member e-mail channel counts',
    { tag: ['@SRS-INT-16', '@SRS-17-12', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', HR, hr, 'M-01');
      expect(await apiStatus(page, '/v1/document-mails/channel', 'GET')).toBe(403);
    },
  );
});
