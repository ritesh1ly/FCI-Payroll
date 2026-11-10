import { expect, type Page, test } from '@playwright/test';
import { apiProblem, apiStatus, decide, errorAlert, m16OtherListRows, t, workAs } from '../support/app';

/**
 * M-16 Reference & Combo List Administration through the UI: who may change a dropdown, the system
 * list rule, maker-checker, and the approved label reaching another user's screen through the
 * master-data cache (docs/architecture/CACHING.md). The label is restored at the end (re-runnable).
 */
const ADMIN = 'sysadmin@fci-demo.test';
const HQ_CHECKER = 'checker.hq@fci-demo.test';
const admin = { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' };
const hqChecker = { role: 'PAYROLL_CHECKER', office: 'HQ001' };

async function relabelPaused(page: Page, label: string, reason: string) {
  await page.locator('#m16-list').selectOption('JOB_STATUS');
  await page
    .getByRole('row', { name: /PAUSED/ })
    .getByRole('button', { name: t('en', 'm16.change'), exact: true })
    .click();
  await page.locator('#m16-en').fill(label);
  await page.locator('#m16-reason').fill(reason);
  await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
  await expect(page.getByRole('status')).toContainText(t('en', 'm16.submitted', ['JOB_STATUS']));
}

async function statusFilterLabels(page: Page): Promise<string[]> {
  await page.goto('/payroll/?s=C-13');
  // "All" first, then the JOB_STATUS values once the reference lists have arrived.
  await expect.poll(() => page.locator('#jf-status option').count()).toBeGreaterThan(1);
  return page.locator('#jf-status option').allTextContents();
}

test.describe.serial('Reference and combo lists (M-16)', () => {
  const stamp = Date.now();
  const label = `On hold ${stamp}`;

  test(
    'only the HQ System Administrator opens M-16; a system list takes no new value',
    { tag: ['@SRS-16-15', '@SRS-G-6', '@SC-04-0014', '@SC-04-0066', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'W-02',
      );
      await page.goto('/payroll/?s=M-16');
      await expect(page).toHaveURL(/\/payroll\/?$/);
      expect(await apiStatus(page, '/v1/masters/reference-lists')).toBe(403);

      await workAs(page, 'en', ADMIN, admin, 'M-16');
      await page.locator('#m16-list').selectOption('JOB_STATUS');
      await expect(page.getByText(t('en', 'm16.systemList')).first()).toBeVisible();
      await expect(page.getByRole('button', { name: t('en', 'm16.newValue') }).first()).toBeDisabled();
      await page.locator('#m16-list').selectOption('CIRCULAR_CATEGORY');
      await expect(page.getByRole('button', { name: t('en', 'm16.newValue') })).toBeVisible();
    },
  );

  test(
    'a relabel waits for the HQ checker; a second change of the value is held by the pending Checker action',
    { tag: ['@SRS-G-1', '@SRS-G-4', '@SRS-G-25', '@SC-01-0001', '@SC-01-0004', '@edge'] },
    async ({ page }) => {
      await workAs(page, 'en', ADMIN, admin, 'M-16');
      await relabelPaused(page, label, `E2E relabel ${stamp}`);
      const row = page.getByRole('row', { name: /PAUSED/ });
      await expect(row).toContainText(t('en', 'm16.pending', ['']).replace(/\(.*$/, '').trim());
      // SRS-G-4 (BMAD TR-004): the same HQ Administrator, who holds the M-16 right, cannot change the
      // value again: the control stays visible, disabled with the reason naming the pending Checker action
      // (SRS-G-25), and the API refuses the second proposal with the hold (409), not with an RBAC 403.
      const change = row.getByRole('button', { name: t('en', 'm16.change'), exact: true });
      await expect(change).toBeDisabled();
      await expect(change).toHaveAttribute(
        'title',
        new RegExp((t('en', 'm16.heldByPending', ['0']).split('(')[0] ?? '').trim()),
      );
      const again = await apiProblem(page, '/v1/masters/reference-lists/JOB_STATUS/values/PAUSED', 'POST', {
        labelEn: `Second ${stamp}`,
        reason: 'e2e second change',
      });
      expect(again).toEqual({ status: 409, errorCode: 'MST-409-PENDING' });
      // Before the approval every screen still shows the old label.
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'C-13',
      );
      expect(await statusFilterLabels(page)).not.toContain(label);
    },
  );

  test(
    'a user without the M-16 right cannot change a reference value (RBAC, not a hold)',
    { tag: ['@SRS-16-15', '@SRS-G-6', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'C-13',
      );
      expect(
        await apiStatus(page, '/v1/masters/reference-lists/JOB_STATUS/values/PAUSED', 'POST', {
          labelEn: 'x',
          reason: 'e2e',
        }),
      ).toBe(403);
    },
  );

  test(
    'the administrator cannot approve; the HQ checker does, and the dropdown changes at once',
    // SC-01-0066 (Module 01 numbering of a use case kb_review_01 maps to module 02 / 04: a comment, not an e2e_trace tag).
    { tag: ['@SRS-G-1', '@SRS-G-6', '@SC-04-0063', '@SC-04-0065', '@positive'] },
    async ({ browser }) => {
      const sysadmin = await browser.newPage();
      await workAs(sysadmin, 'en', ADMIN, admin, 'M-16');
      // The Administrator has an inbox since module 05 (V039_15: it authorises a controlled undo, C02_UNDO), but
      // the M-16 change is not routed to it: it never lists the item to decide.
      const inbox = await sysadmin.evaluate(() =>
        fetch('/nginx/fci-payroll/v1/inbox?size=100', { credentials: 'same-origin' }).then((r) => r.text()),
      );
      expect(inbox).not.toContain(`E2E relabel ${stamp}`);

      const checker = await browser.newPage();
      await workAs(checker, 'en', HQ_CHECKER, hqChecker, 'W-01');
      await decide(checker, new RegExp(`E2E relabel ${stamp}`), 'APPROVE');

      const maker = await browser.newPage();
      await workAs(
        maker,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'C-13',
      );
      expect(await statusFilterLabels(maker)).toContain(label);
    },
  );

  test(
    'M-16: a new value needs its effective-from date, and a code the list already holds is refused when proposed',
    { tag: ['@SRS-16-15', '@SRS-G-37', '@SC-04-0067', '@SC-04-0068', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', ADMIN, admin, 'M-16');
      await page.locator('#m16-list').selectOption('CIRCULAR_CATEGORY');
      const taken =
        (await m16OtherListRows(page).first().locator('td code').first().textContent())?.trim() ?? '';
      expect(taken).not.toBe('');
      await page.getByRole('button', { name: t('en', 'm16.newValue') }).click();
      await page.locator('#m16-code').fill(taken);
      await page.locator('#m16-en').fill(`Duplicate ${stamp}`);
      await page.locator('#m16-reason').fill(`E2E duplicate ${stamp}`);
      const submit = page.getByRole('button', { name: t('en', 'common.submitForApproval') });
      // Effective-from is mandatory for a new value: without it the proposal cannot go.
      const from = await page.locator('#m16-from').inputValue();
      await page.locator('#m16-from').fill('');
      await expect(submit).toBeDisabled();
      await page.locator('#m16-from').fill(from);
      await submit.click();
      await expect(errorAlert(page)).toContainText(
        t('en', 'error.MST-409-CODE', ['CIRCULAR_CATEGORY', taken]),
      );
    },
  );

  test(
    'restore the label (leaves the environment as it was)',
    { tag: ['@SRS-G-6', '@positive'] },
    async ({ browser }) => {
      const sysadmin = await browser.newPage();
      await workAs(sysadmin, 'en', ADMIN, admin, 'M-16');
      await relabelPaused(sysadmin, 'Paused', `E2E restore ${stamp}`);
      const checker = await browser.newPage();
      await workAs(checker, 'en', HQ_CHECKER, hqChecker, 'W-01');
      await decide(checker, new RegExp(`E2E restore ${stamp}`), 'APPROVE');
      const maker = await browser.newPage();
      await workAs(
        maker,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'C-13',
      );
      expect(await statusFilterLabels(maker)).toContain('Paused');
    },
  );
});
