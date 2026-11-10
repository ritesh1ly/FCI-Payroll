import { expect, type Page, type Route, test } from '@playwright/test';
import { apiStatus, decide, onSomePage, pressDecision, signIn, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sqlRows } from '../support/db';
import { receivedTask, sendTaskOrder } from '../support/hrms-tasks';
import { TEST_MAKER, testEis, testMaker } from '../support/cycle-office';
import { currentCycle, monthsBefore, PDF } from '../support/payroll-support';

/**
 * What the user sees when something outside the form fails, menu by menu (SRS-EH-1, SRS-EH-3: what
 * failed and what to do next, never a technical exception). The browser's own call is intercepted, so
 * nothing on the server is broken and the retry reaches the real server: nothing half saved, the
 * entry kept, and the same action works again. Double click, stale version and the OTP paths have
 * their own specs (09-double-submit, 15-two-users-same-record, otp-gated-actions).
 */
const MAKER = 'maker.delhi@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };
const CHECKER = 'ddo.delhi@fci-demo.test';
const checker = { role: 'PAYROLL_CHECKER', office: 'DH001' };
/**
 * OD-141: an I-20 case needs a salary month that is not locked; DH001's past months are locked demo months
 * (V082_21), so the I-20 checks raise their cases at the cycle test office DT001, on its copy of 107304.
 */
const I20_MAKER = TEST_MAKER;
const i20Maker = testMaker;
const I20_MEMBER = testEis('107304');

/** A problem document the server would send for a fault it could not handle (never a stack trace). */
const fault = (status: number, code: string) => ({
  status,
  contentType: 'application/problem+json',
  body: JSON.stringify({
    status,
    errorCode: code,
    args: ['I-20', 'CREATE'],
    title: 'Fault',
    correlationId: 'e2e-failure-path',
  }),
});

async function failing(page: Page, path: RegExp, how: (route: Route) => Promise<void>, method: string) {
  await page.route(path, (route) => (route.request().method() === method ? how(route) : route.fallback()));
}

/**
 * SRS-EH-3: never a technical exception, stack trace or constraint name. Reads the shown errors and the
 * screen, not the sidebar, whose labels hold the word (Exceptions Worklist).
 */
async function noTechnicalText(page: Page) {
  const shown = [
    ...(await page.locator('.fl-error[role="alert"]').allInnerTexts()),
    await page.locator('main').innerText(),
  ].join('\n');
  expect(shown).not.toMatch(
    /\b\w+Exception\b|\bat [\w.$]+\(|stack trace|ORA-\d+|SQLState|\bundefined\b|\bnull\b|NullPointer/,
  );
}

const READS: [string, string, string, { role: string; office: string }, string, RegExp][] = [
  ['Home', 'D-01', MAKER, maker, 'SRS-EH-3', /\/v1\/cycle-status(\?|$)/],
  ['General', 'X-05', MAKER, maker, 'SRS-G-9', /\/v1\/exceptions/],
  ['General', 'I-20', MAKER, maker, 'SRS-G-10', /\/v1\/off-cycle(\?|$)/],
  ['Employee', 'M-01', MAKER, maker, 'SRS-1-1', /\/v1\/employees/],
  [
    'Departmental Labour',
    'M-02',
    'ldh.depot@fci-demo.test',
    { role: 'LABOUR_DEALING_HAND', office: 'DP-DL01' },
    'SRS-G-54',
    /\/v1\/labour\//,
  ],
  [
    'DPS Labour',
    'M-05',
    'su.hq@fci-demo.test',
    { role: 'RULE_ADMINISTRATOR', office: 'HQ001' },
    'SRS-G-54',
    /\/v1\/labour\//,
  ],
  [
    'CPF',
    'I-22',
    'cpf.manager.north@fci-demo.test',
    { role: 'CPF_MANAGER', office: 'NA11' },
    'SRS-6-43',
    /\/v1\/cpf\/opening-balance-corrections/,
  ],
  ['Tax', 'I-07', MAKER, maker, 'SRS-7-5', /\/v1\/tax\/declarations/],
  [
    'NPS',
    'I-14',
    'finance.hq@fci-demo.test',
    { role: 'FINANCE', office: 'HQ001' },
    'SRS-G-54',
    /\/v1\/nps-deductions/,
  ],
  ['Loans', 'I-04', MAKER, maker, 'SRS-12-1', /\/v1\/loans/],
];

test.describe('Failure paths of menus 1-10 (SRS-EH-3)', () => {
  for (const [menu, screen, login, scope, id, path] of READS) {
    test(
      `SRS-EH-3 ${menu} ${screen}: a server error on the list says so with a reference, keeps the screen and a reload recovers`,
      { tag: ['@SRS-EH-3', `@${id.replace(' ', '-')}`, '@negative'] },
      async ({ page }) => {
        await failing(page, path, (route) => route.fulfill(fault(500, 'COM-500-INTERNAL')), 'GET');
        await workAs(page, 'en', login, scope, screen);
        await expect(page.locator('.fl-error[role="alert"]').first()).toBeVisible({ timeout: 15_000 });
        await noTechnicalText(page);
        await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
        await page.unroute(path);
        await page.reload();
        await expect(page.locator('.rolebar b')).toBeVisible();
        await expect(page.locator('.fl-error[role="alert"]')).toHaveCount(0);
      },
    );
  }

  test(
    'SRS-EH-3 General I-20: a dropped connection on Capture keeps the dialog and the entry; nothing is saved; the retry records one case',
    { tag: ['@SRS-EH-3', '@SRS-G-10', '@SRS-EH-8', '@negative'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const reference = `HQ/PAY/E2E/FAIL/${Date.now()}`;
      const saved = () =>
        sqlRows(`SELECT COUNT(*) FROM off_cycle_payment WHERE approval_reference = '${reference}'`)[0]?.[0];
      await workAs(page, 'en', I20_MAKER, i20Maker, 'I-20');
      const month = monthsBefore((await currentCycle(page)).yymm, 3);
      await page.getByRole('button', { name: t('en', 'i20.raise') }).click();
      await page.getByLabel(t('en', 'gen.eis')).fill(I20_MEMBER);
      await page.getByLabel(t('en', 'i20.caseType')).selectOption('OTHER');
      await page.getByLabel(t('en', 'i20.salaryMonth')).selectOption(month);
      await page.getByLabel(t('en', 'i20.approvalReference')).fill(reference);
      const path = /\/v1\/off-cycle(\?|$)/;
      await failing(page, path, (route) => route.abort('connectionfailed'), 'POST');
      await page.getByRole('button', { name: t('en', 'i20.capture') }).click();
      const dialog = page.getByRole('dialog');
      await expect(dialog.locator('.fl-error[role="alert"]')).toBeVisible();
      await noTechnicalText(page);
      expect(saved()).toBe('0');
      await expect(dialog.getByLabel(t('en', 'i20.approvalReference'))).toHaveValue(reference);
      await page.unroute(path);
      await page.getByRole('button', { name: t('en', 'i20.capture') }).click();
      await expect(dialog).toBeHidden();
      expect(saved()).toBe('1');
      const id = sqlRows(
        `SELECT off_cycle_id FROM off_cycle_payment WHERE approval_reference = '${reference}'`,
      )[0]?.[0];
      expect(
        await apiStatus(page, `/v1/off-cycle/${id}/cancel`, 'POST', {
          reason: 'E2E clean-up of a failure path',
        }),
      ).toBe(200);
    },
  );

  test(
    'SRS-EH-3 General W-01: a server error during an approval leaves the item pending with a clear reason; the retry approves it',
    { tag: ['@SRS-EH-3', '@SRS-G-1', '@negative'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const taskNo = receivedTask('DATE_OF_BIRTH', '1981-06-21', 17);
      await workAs(page, 'en', MAKER, maker, 'I-21');
      await sendTaskOrder(page, taskNo);
      const state = () =>
        sqlRows(`SELECT task_status FROM hrms_change_task WHERE task_no = '${taskNo}'`)[0]?.[0];
      await workAs(page, 'en', CHECKER, checker, 'W-01');
      const path = /\/v1\/approvals\/\d+\/decision$/;
      await failing(page, path, (route) => route.fulfill(fault(500, 'COM-500-INTERNAL')), 'POST');
      const item = await onSomePage(page, page.getByRole('row', { name: new RegExp(taskNo) }).first());
      const dialog = await pressDecision(page, item, 'APPROVE');
      await dialog.getByRole('button', { name: t('en', 'w01.APPROVE') }).click();
      // W-01 says it in its toast and keeps the item open for the next try.
      await expect(
        page.getByRole('status').filter({ hasText: t('en', 'error.COM-500-INTERNAL') }),
      ).toBeVisible();
      await noTechnicalText(page);
      expect(state()).toBe('PENDING_CHECKER');
      await page.unroute(path);
      await page.reload();
      await decide(page, new RegExp(taskNo), 'APPROVE');
      expect(state()).toBe('APPROVED');
    },
  );

  test(
    'SRS-EH-3: a session ended while a form is open: nothing is saved and the user is sent to sign in again',
    { tag: ['@SRS-EH-3', '@SRS-G-48', '@negative'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const LOGIN = 'pm.ropb@fci-demo.test';
      const page = await browser.newPage();
      const reference = `HQ/PAY/E2E/SESS/${Date.now()}`;
      await workAs(page, 'en', LOGIN, { role: 'PAYROLL_MAKER', office: 'RO-PB' }, 'I-20');
      const month = monthsBefore((await currentCycle(page)).yymm, 3);
      await page.getByRole('button', { name: t('en', 'i20.raise') }).click();
      await page.getByLabel(t('en', 'gen.eis')).fill('119901');
      await page.getByLabel(t('en', 'i20.caseType')).selectOption('OTHER');
      await page.getByLabel(t('en', 'i20.salaryMonth')).selectOption(month);
      await page.getByLabel(t('en', 'i20.approvalReference')).fill(reference);
      // SRS-G-48: one active session per user: a new sign-in ends this one on the server.
      const other = await browser.newContext({ ignoreHTTPSErrors: true });
      const second = await other.newPage();
      await signIn(second, 'en', LOGIN);
      await expect(second).toHaveURL(/\/payroll/);
      await other.close();
      await page.getByRole('button', { name: t('en', 'i20.capture') }).click();
      await expect(page.getByRole('heading', { name: t('en', 'login.signIn') })).toBeVisible({
        timeout: 15_000,
      });
      expect(
        sqlRows(`SELECT COUNT(*) FROM off_cycle_payment WHERE approval_reference = '${reference}'`)[0]?.[0],
      ).toBe('0');
      await page.close();
    },
  );

  test(
    'SRS-EH-3: a right lost while a form is open is refused with the reason; nothing is saved and the dialog stays',
    { tag: ['@SRS-EH-3', '@SRS-G-25', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', I20_MAKER, i20Maker, 'I-20');
      const month = monthsBefore((await currentCycle(page)).yymm, 3);
      await page.getByRole('button', { name: t('en', 'i20.raise') }).click();
      await page.getByLabel(t('en', 'gen.eis')).fill(I20_MEMBER);
      await page.getByLabel(t('en', 'i20.caseType')).selectOption('OTHER');
      await page.getByLabel(t('en', 'i20.salaryMonth')).selectOption(month);
      await page.getByLabel(t('en', 'i20.approvalReference')).fill('HQ/PAY/E2E/RIGHT');
      await failing(
        page,
        /\/v1\/off-cycle(\?|$)/,
        (route) => route.fulfill(fault(403, 'AUTHZ-403-PERMISSION')),
        'POST',
      );
      await page.getByRole('button', { name: t('en', 'i20.capture') }).click();
      const dialog = page.getByRole('dialog');
      await expect(dialog.locator('.fl-error[role="alert"]')).toBeVisible();
      await noTechnicalText(page);
      await expect(dialog.getByLabel(t('en', 'i20.approvalReference'))).toHaveValue('HQ/PAY/E2E/RIGHT');
    },
  );

  test(
    'SRS-G-16, SRS-G-30: an order file of a type the server refuses leaves the case without an order and it can be picked again',
    { tag: ['@SRS-G-30', '@SRS-EH-3', '@negative'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      await workAs(page, 'en', I20_MAKER, i20Maker, 'I-20');
      const reference = `HQ/PAY/E2E/ORD/${Date.now()}`;
      const month = monthsBefore((await currentCycle(page)).yymm, 4);
      await page.getByRole('button', { name: t('en', 'i20.raise') }).click();
      await page.getByLabel(t('en', 'gen.eis')).fill(I20_MEMBER);
      await page.getByLabel(t('en', 'i20.caseType')).selectOption('OTHER');
      await page.getByLabel(t('en', 'i20.salaryMonth')).selectOption(month);
      await page.getByLabel(t('en', 'i20.approvalReference')).fill(reference);
      await page.getByRole('button', { name: t('en', 'i20.capture') }).click();
      await expect(page.getByRole('dialog')).toBeHidden();
      const row = page.getByRole('row', { name: new RegExp(reference) });
      await row.locator('td').first().click();
      const card = page.locator('[data-order-card]');
      await card.locator('select').first().selectOption('OTHER');
      await card.locator('input[type="date"]').fill(new Date().toISOString().slice(0, 10));
      await card.getByPlaceholder(t('en', 'wf.ord.officePlaceholder')).fill('AGM (Pay) Delhi');
      // A file whose bytes are not a PDF, whatever its name says.
      await card.locator('input[type="file"]').setInputFiles({
        name: 'order.pdf',
        mimeType: 'application/pdf',
        buffer: Buffer.from('MZ not a pdf'),
      });
      await expect(card.locator('.fl-error[role="alert"]')).toBeVisible();
      await noTechnicalText(page);
      await expect(card).toContainText(t('en', 'ord.none'));
      await card.locator('input[type="file"]').setInputFiles(PDF);
      await expect(card).toContainText(t('en', 'wf.ord.stored', ['1']));
      const id = sqlRows(
        `SELECT off_cycle_id FROM off_cycle_payment WHERE approval_reference = '${reference}'`,
      )[0]?.[0];
      expect(await apiStatus(page, `/v1/off-cycle/${id}/cancel`, 'POST', { reason: 'E2E clean-up' })).toBe(
        200,
      );
    },
  );
});
