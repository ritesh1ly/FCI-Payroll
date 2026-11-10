import { expect, test } from '@playwright/test';
import { apiStatus, decide, previousFinYear, t, workAs } from '../support/app';
import { apiJson, CHECKER, checker, expectToast, MAKER, maker } from '../support/payroll-support';

/**
 * Module 14 eSeva through the UI (docs/plan/14-eseva.md; SRS-17-3, SRS-17-6, SRS-17-8, SRS-17-12,
 * SRS-17-18, SRS-SEC-9, BR-ESEVA-006): the member acknowledges the privacy notice and asks for a new
 * monthly VPF on E-01; the Payroll Maker of the office finds it in the Self-Service Submission Log
 * and verifies it on E-02; the Payroll Checker approves it in the Approvals Inbox; the member sees it
 * applied and is notified at each status. A support preview is masked, read-only and refused for a
 * member outside the user's offices.
 *
 * Demo data V008_1 / V050_9: member emp.rsharma (EIS 104882, DH001) with a CPF ledger of the year.
 * Re-runnable: a VPF change left waiting by an earlier run is verified and approved first.
 */
const MEMBER = 'emp.rsharma@fci-demo.test';
const member = { role: 'EMPLOYEE_ESEVA' };
const EIS = '104882';

interface MyVpf {
  currentAmount: number;
  cpfMember: boolean;
  requests: { id: number; status: string; awaitingChecker: boolean }[];
}

test.describe.serial('E-01 / E-02 eSeva', () => {
  test(
    'SRS-SEC-9: the member acknowledges the privacy notice and asks for a new VPF',
    { tag: ['@SRS-SEC-9', '@BR-ESEVA-006', '@SRS-17-3', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MEMBER, member, 'E-01');
      const acknowledge = page.getByRole('button', { name: t('en', 'action.eseva.acknowledge') });
      // Not drawn (WF-1510): a notice still waiting stands open above the portal until acknowledged, never in a
      // closed section, so the member sees it without opening anything (SRS-SEC-9).
      const privacy = page.getByTestId('e01-privacy');
      if (await privacy.isVisible()) {
        await expect(privacy.getByText(t('en', 'eseva.privacy.text'))).toBeVisible();
        await acknowledge.click();
        await expect(acknowledge).toBeHidden();
      }
      const mine = await apiJson<MyVpf>(page, '/v1/cpf/vpf-changes/mine');
      expect(mine.cpfMember).toBe(true);
      if (!mine.requests.some((r) => r.status === 'SUBMITTED')) {
        const ask = page.getByRole('button', { name: t('en', 'action.vpf.request') });
        await page.getByLabel(t('en', 'vpf.newAmount')).fill(String(mine.currentAmount + 100));
        await ask.click();
        await expectToast(page, t('en', 'vpf.asked'));
      }
      await expect(
        page.getByRole('table', { name: t('en', 'vpf.requests') }).getByText(t('en', 'vpf.status.SUBMITTED')),
      ).toBeVisible();
      await expect(page.getByRole('button', { name: t('en', 'action.vpf.request') })).toBeDisabled();
      // SRS-17-18: the log is for staff only.
      expect(await apiStatus(page, '/v1/eseva/submissions')).toBe(403);
    },
  );

  test(
    'SRS-17-18: the Payroll Maker finds the request in the log and sends it to the Checker',
    { tag: ['@SRS-17-18', '@BR-ESEVA-006', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'E-02');
      const log = page.getByRole('table', { name: t('en', 'e02.log') });
      await expect(log.getByText(t('en', 'e02.type.VPF_SUBSCRIPTION')).first()).toBeVisible();
      const requests = page.getByRole('table', { name: t('en', 'vpf.staffTitle') });
      const row = requests.getByRole('row', { name: new RegExp(EIS) }).first();
      await row.getByRole('button', { name: t('en', 'action.e02.verifyVpf') }).click();
      await expectToast(page, t('en', 'vpf.sentToChecker'));
    },
  );

  test(
    'BR-ESEVA-006: the Payroll Checker approves it and the member sees it applied and notified',
    { tag: ['@BR-ESEVA-006', '@SRS-17-12', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', CHECKER, checker, 'W-01');
      await decide(page, new RegExp(`VPF.*${EIS}`), 'APPROVE');
      await workAs(page, 'en', MEMBER, member, 'E-01');
      await expect(
        page
          .getByRole('table', { name: t('en', 'vpf.requests') })
          .getByText(t('en', 'vpf.status.APPLIED'))
          .first(),
      ).toBeVisible();
      await expect(
        page
          .getByRole('list', { name: t('en', 'e01.notices') })
          .getByText(t('en', 'notice.ESEVA_ACTIONED'))
          .first(),
      ).toBeVisible();
    },
  );

  test(
    'SRS-17-6: a support preview is masked and read-only, every submit disabled',
    { tag: ['@SRS-17-6', '@SRS-17-8', '@SRS-17-10', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'E-02');
      await page.getByLabel(t('en', 'm01.eis')).first().fill(EIS);
      await page.getByLabel(t('en', 'common.reason')).fill('E2E helpdesk ticket');
      await page.getByRole('button', { name: t('en', 'action.e02.preview') }).click();
      const view = page.getByRole('region', { name: t('en', 'e02.previewOf', [EIS]) });
      await expect(view.getByText(/\*\*\*\*/).first()).toBeVisible();
      await expect(view.getByRole('button', { name: t('en', 'action.e02.submit') })).toBeDisabled();
    },
  );

  test(
    'SRS-17-6: on E-01 the Payroll Maker opens a recorded preview, every submit disabled, and closes it; no /mine call',
    { tag: ['@SRS-17-6', '@SRS-17-8', '@SRS-G-54', '@positive'] },
    async ({ page }) => {
      const mine: string[] = [];
      page.on('request', (r) => {
        if (/\/mine\b|\/v1\/eseva\/me\b/.test(r.url()) && !r.url().includes('/notifications/mine')) {
          mine.push(r.url());
        }
      });
      await workAs(page, 'en', MAKER, maker, 'E-01');
      const declare = page.getByRole('button', { name: t('en', 'e01w.submitDeclaration') });
      await expect(declare).toBeDisabled();
      await expect(declare).toHaveAttribute('data-why', t('en', 'e01w.why.declaration'));
      const raise = page.getByRole('button', { name: t('en', 'e01w.hrms.raise') });
      await expect(raise).toBeDisabled();
      await expect(raise).toHaveAttribute('data-why', t('en', 'e01w.why.hrmsRaise'));
      await expect(page.getByText(t('en', 'e01w.netPayNone'))).toBeVisible();
      // Not drawn: the session stands in a closed section, which workAs opened on entry (a second click closes it).
      const session = page.getByTestId('e01-section-session');
      if ((await session.getAttribute('open')) === null) await session.locator('summary').click();
      await expect(session).toHaveAttribute('open', '');
      const open = page.getByRole('button', { name: t('en', 'action.e02.preview') });
      await expect(open).toBeDisabled();
      await page.getByLabel(t('en', 'm01.eis')).fill(EIS);
      await expect(open).toBeDisabled();
      await page.getByLabel(t('en', 'common.reason')).fill('E2E helpdesk ticket');
      await open.click();
      const close = page.getByRole('button', { name: t('en', 'action.e02.closePreview') });
      await expect(close).toBeVisible();
      await expect(page.getByTestId('e01-support-session').getByRole('status')).toBeVisible();
      await expect(page.locator('.e01-band')).toContainText(EIS);
      await expect(raise).toBeDisabled();
      const tile = page.getByRole('button', { name: t('en', 'e01w.act.cpf') });
      await expect(tile).toHaveAttribute('data-why', t('en', 'e01w.why.preview'));
      await close.click();
      await expect(open).toBeVisible();
      await expect(page.getByText(t('en', 'e01w.netPayNone'))).toBeVisible();
      expect(mine).toEqual([]);
    },
  );

  test(
    'SRS-17-9: a member outside the offices reads as not found',
    { tag: ['@SRS-17-9', '@SRS-17-8', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'maker.chennai@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'CH001' },
        'E-02',
      );
      await page.getByLabel(t('en', 'm01.eis')).first().fill(EIS);
      await page.getByLabel(t('en', 'common.reason')).fill('E2E helpdesk ticket');
      await page.getByRole('button', { name: t('en', 'action.e02.preview') }).click();
      // The screen's error, not Next.js's (empty) route announcer, which is also role=alert.
      await expect(page.locator('.fl-error[role="alert"]')).toBeVisible();
      await expect(page.getByRole('region', { name: t('en', 'e02.previewOf', [EIS]) })).toHaveCount(0);
    },
  );

  test(
    'SRS-17-17: a CPF statement or VPF that fails to load shows the problem; the year selector stays and another year loads',
    { tag: ['@SRS-17-17', '@SRS-17-16', '@BR-ESEVA-006', '@negative'] },
    async ({ page }) => {
      // The service failing (a fault cannot be made on demand): the two reads answer 500 until released
      // (a 500 is final; a 503 would be retried first).
      let failing = true;
      const unavailable = { errorCode: 'COM-500-INTERNAL', messageKey: 'error.COM-500-INTERNAL', args: [] };
      await page.route('**/v1/cpf/statements/mine**', (route) =>
        failing ? route.fulfill({ status: 500, json: unavailable }) : route.continue(),
      );
      await page.route('**/v1/cpf/vpf-changes/mine', (route) =>
        failing && route.request().method() === 'GET'
          ? route.fulfill({ status: 500, json: unavailable })
          : route.continue(),
      );
      await workAs(page, 'en', MEMBER, member, 'E-01');
      const vpf = page.locator('.card').filter({ hasText: t('en', 'vpf.title') });
      await expect(vpf.locator('.fl-error[role="alert"]')).toBeVisible();
      await expect(vpf.getByRole('button', { name: t('en', 'action.vpf.request') })).toHaveCount(0);
      const year = page.getByLabel(t('en', 'e01.finYear'));
      await expect(year).toBeVisible();
      const statement = page.locator('.card').filter({ has: year });
      await expect(statement.locator('.fl-error[role="alert"]')).toBeVisible();
      // Released: the member asks for last year and the statement shows, without the problem.
      failing = false;
      const last = previousFinYear();
      await year.fill(last);
      await expect(page.getByText(t('en', 'e01.cpfStatement', [last]))).toBeVisible();
      await expect(statement.locator('.fl-error[role="alert"]')).toHaveCount(0);
    },
  );

  test(
    'SRS-SEC-9: a privacy notice that fails to load is shown as the problem with a retry, never skipped',
    { tag: ['@SRS-SEC-9', '@negative'] },
    async ({ page }) => {
      // The service failing (a 500 is final): the notice answers 500 until released, then an unacknowledged
      // notice (stubbed, so this run acknowledges nothing).
      let failing = true;
      const unavailable = { errorCode: 'COM-500-INTERNAL', messageKey: 'error.COM-500-INTERNAL', args: [] };
      await page.route('**/v1/eseva/privacy-notice/mine', (route) =>
        route.request().method() !== 'GET'
          ? route.continue()
          : failing
            ? route.fulfill({ status: 500, json: unavailable })
            : route.fulfill({ status: 200, json: { version: '1', acknowledged: false } }),
      );
      await workAs(page, 'en', MEMBER, member, 'E-01');
      const notice = page.locator('.card').filter({ hasText: t('en', 'eseva.privacy.title') });
      await expect(notice.locator('.fl-error[role="alert"]')).toBeVisible();
      await expect(notice.getByRole('button', { name: t('en', 'action.eseva.acknowledge') })).toHaveCount(0);
      failing = false;
      await notice.getByRole('button', { name: t('en', 'common.retry') }).click();
      // Loaded, the notice stands open until acknowledged (SRS-SEC-9, WF-1510): nothing to open first.
      await expect(page.getByTestId('e01-privacy')).toBeVisible();
      await expect(notice.getByText(t('en', 'eseva.privacy.text'))).toBeVisible();
      await expect(notice.getByRole('button', { name: t('en', 'action.eseva.acknowledge') })).toBeEnabled();
      await expect(notice.locator('.fl-error[role="alert"]')).toHaveCount(0);
    },
  );

  test(
    'SRS-17-12: a mark-read that fails is shown and the notice stays unread',
    { tag: ['@SRS-17-12', '@negative'] },
    async ({ page }) => {
      const unavailable = { errorCode: 'COM-500-INTERNAL', messageKey: 'error.COM-500-INTERNAL', args: [] };
      // One unread notice (stubbed, so the shared demo data is not changed) whose mark-read fails.
      await page.route(
        (url) => url.pathname.endsWith('/v1/eseva/notifications/mine'),
        (route) =>
          route.fulfill({
            status: 200,
            json: {
              items: [
                {
                  id: 999001,
                  type: 'ESEVA_ROUTED',
                  relatedEntity: 'ess_submission',
                  relatedKey: '1',
                  message: null,
                  read: false,
                  createdAt: '2026-10-28T10:00:00',
                },
              ],
              page: 0,
              size: 20,
              total: 1,
            },
          }),
      );
      await page.route('**/v1/eseva/notifications/mine/999001/reads', (route) =>
        route.fulfill({ status: 500, json: unavailable }),
      );
      await workAs(page, 'en', MEMBER, member, 'E-01');
      const notices = page.locator('.card').filter({ hasText: t('en', 'e01.notices') });
      const markRead = notices.getByRole('button', { name: t('en', 'action.e01.markRead') });
      await markRead.click();
      await expect(notices.locator('.fl-error[role="alert"]')).toBeVisible();
      await expect(markRead).toBeEnabled();
    },
  );

  // SC-14-0017 (scenario of a module after 07: cited, not tagged)
  test(
    'SRS-G-40: the member reads E-01 in Hindi - the cards and the VPF requests carry the Hindi labels',
    { tag: ['@SRS-G-40', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'hi', MEMBER, member, 'E-01');
      await expect(page.getByRole('table', { name: t('hi', 'vpf.requests') })).toBeVisible();
      await expect(page.getByRole('button', { name: t('hi', 'action.vpf.request') })).toBeVisible();
      await expect(page.locator('.card').filter({ hasText: t('hi', 'e01.notices') })).toBeVisible();
      await expect(page.getByText(t('en', 'e01.notices'), { exact: true })).toHaveCount(0);
      // Back to English for the specs that follow.
      await workAs(page, 'en', MEMBER, member, 'E-01');
    },
  );
});
