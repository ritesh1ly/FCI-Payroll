import { expect, test } from '@playwright/test';
import { decide, otpFromLog, signIn, t, workAs, onSomePage } from '../support/app';

/**
 * Module 02 (SRS-G-48, SRS-G-21, SRS-22-8, SRS-22-13, SRS-22-19, SRS-22-23, SRS-SEC-14): the dashboard
 * after sign-in, the OTP step-up of sensitive screens and of a role switch, A-02 changes under
 * maker-checker with an OTP, a revocation that ends the open session, a session cookie replayed from
 * another browser, and a zone administrator held to its zone. Users of the RBAC test data (V026_9).
 */

const otpField = (page: import('@playwright/test').Page) =>
  page.getByRole('textbox', { name: t('en', 'otp.code') });

async function answerOtp(page: import('@playwright/test').Page, purpose: string, since: number) {
  await otpField(page).fill(await otpFromLog(purpose, since));
  await page.getByRole('button', { name: t('en', 'otp.verify') }).click();
  await expect(otpField(page)).toHaveCount(0);
}

test.describe('Dashboard and OTP step-up of sensitive screens', () => {
  test(
    'a maker lands on the dashboard; its role has no sensitive screen, so no OTP is asked',
    { tag: ['@SRS-G-48', '@positive'] },
    async ({ page }) => {
      await signIn(page, 'en', 'rbac.maker.ludhiana@fci-demo.test');
      await expect(page.getByRole('heading', { level: 1, name: t('en', 'dash.title') })).toBeVisible();
      await expect(page.getByText(t('en', 'dash.otpNotNeeded'))).toBeVisible();
      await expect(otpField(page)).toHaveCount(0);
      await page.getByRole('button', { name: /M-01/ }).first().click();
      await expect(page.getByRole('heading', { level: 1, name: t('en', 'screen.M-01') })).toBeVisible();
    },
  );

  test(
    'a checker who signed in with the OTP opens its sensitive screen at once: the sign-in code confirmed the step-up',
    { tag: ['@SC-01-0021', '@SRS-G-48', '@SRS-G-21', '@edge'] },
    async ({ page }) => {
      // ADR-0026: the sign-in OTP confirms the session's step-up (the cancelled step-up after its
      // validity is proved by StepUpIT and session-guards.test.tsx).
      await signIn(page, 'en', 'rbac.checker.ludhiana@fci-demo.test');
      await expect(page.getByRole('heading', { level: 1, name: t('en', 'dash.title') })).toBeVisible();
      await expect(otpField(page)).toHaveCount(0);
      await expect(page.getByText(t('en', 'dash.otpDue'))).toHaveCount(0);
      await page.goto('/payroll/?s=W-01');
      await expect(page.getByRole('heading', { level: 1, name: t('en', 'screen.W-01') })).toBeVisible();
      await expect(page.getByRole('heading', { name: t('en', 'stepup.title') })).toHaveCount(0);
    },
  );

  test(
    'taking on another role right after sign-in needs no second OTP: the sign-in code is recent',
    { tag: ['@SRS-G-48', '@SRS-22-2', '@positive'] },
    async ({ page }) => {
      await signIn(page, 'en', 'rbac.hr.finance@fci-demo.test');
      const role = page.getByLabel(t('en', 'top.role'), { exact: true });
      await expect(role).not.toHaveValue('');
      const target = (await role.inputValue()).startsWith('FINANCE')
        ? 'ESTABLISHMENT_HR@LD001'
        : 'FINANCE@LD001';
      await role.selectOption(target);
      await expect(page.locator('.rolebar b')).toContainText(t('en', `role.${target.split('@')[0]}`));
      await expect(otpField(page)).toHaveCount(0);
    },
  );
});

test.describe('A-02 changes need a second person and an OTP', () => {
  test(
    'a permission grant is a proposal; the HQ checker decides it, the matrix stays until approved',
    { tag: ['@SRS-22-8', '@SRS-22-23', '@SC-02-0001', '@SC-02-0008', '@SC-02-0020', '@positive'] },
    async ({ browser }) => {
      const admin = await browser.newPage();
      await workAs(
        admin,
        'en',
        'sysadmin@fci-demo.test',
        { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' },
        'A-02',
      );
      // The matrix pages by permission row (20 to start, SRS-NF-3): find the permission by its code.
      const find = async () => admin.getByLabel(t('en', 'a02.filter')).fill('M-24:VIEW');
      await find();
      const box = admin.getByLabel(t('en', 'a02.toggle', ['PENSION_DIVISION_HQ', 'M-24:VIEW']));
      const before = await box.isChecked();
      await box.click();
      const reason = `e2e grant ${Date.now()}`;
      await admin.getByRole('dialog').getByRole('textbox').fill(reason);
      const since = Date.now();
      await admin
        .getByRole('dialog')
        .getByRole('button', { name: t('en', 'common.submitForApproval') })
        .click();
      await answerOtp(admin, 'ROLE_ACCESS_GROUP_CHANGE', since);
      await expect(admin.getByText(t('en', 'a02.proposed', ['PENSION_DIVISION_HQ']))).toBeVisible();
      await admin.reload();
      await find();
      await expect(admin.getByLabel(t('en', 'a02.toggle', ['PENSION_DIVISION_HQ', 'M-24:VIEW']))).toBeChecked(
        {
          checked: before,
        },
      );

      const checker = await browser.newPage();
      await workAs(
        checker,
        'en',
        'checker.hq@fci-demo.test',
        { role: 'PAYROLL_CHECKER', office: 'HQ001' },
        'W-01',
      );
      // Rejected (no OTP: only an approval takes effect), so the matrix stays as seeded for re-runs.
      await decide(checker, new RegExp(reason), 'REJECT', 'Not for pensions');
    },
  );
});

test.describe('Sessions end when access ends or the cookie travels', () => {
  test(
    'a session cookie replayed in another browser is refused and ends the session for its owner too',
    { tag: ['@SRS-SEC-14', '@SRS-G-48', '@negative'] },
    async ({ browser, page }) => {
      await signIn(page, 'en', 'rbac.ldh.khanna@fci-demo.test');
      await expect(page.getByRole('heading', { level: 1, name: t('en', 'dash.title') })).toBeVisible();
      const stolen = await page.context().cookies();
      const other = await browser.newContext({
        ignoreHTTPSErrors: true,
        userAgent: 'Mozilla/5.0 (X11; Linux x86_64) Attacker/1.0',
      });
      await other.addCookies(stolen);
      const intruder = await other.newPage();
      await intruder.goto('/payroll/');
      await expect(intruder).not.toHaveURL(/\/payroll/);
      await page.reload();
      await expect(page).not.toHaveURL(/\/payroll/);
      await other.close();
    },
  );

  test(
    "revoking a signed-in user's access sends the open session back to sign-in",
    { tag: ['@SRS-SEC-14', '@SRS-G-36', '@SC-02-0027', '@positive'] },
    async ({ browser, page }) => {
      await signIn(page, 'en', 'rbac.ldh.borivali@fci-demo.test');
      test.skip(
        !/\/payroll/.test(page.url()),
        'access already revoked on this stack (runs once per deployment)',
      );
      await expect(page.getByRole('heading', { level: 1, name: t('en', 'dash.title') })).toBeVisible();

      const admin = await browser.newPage();
      await workAs(
        admin,
        'en',
        'sysadmin@fci-demo.test',
        { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' },
        'A-04',
      );
      const row = await onSomePage(admin, admin.getByRole('row', { name: /rbac\.ldh\.borivali/ }).first());
      await row.getByRole('button', { name: t('en', 'action.revoke') }).click();
      await admin.getByRole('dialog').getByRole('textbox').fill('E2E: transferred');
      await admin
        .getByRole('dialog')
        .getByRole('button', { name: t('en', 'common.submit') })
        .click();
      await expect(
        admin.getByText(t('en', 'a04.revoked', ['rbac.ldh.borivali@fci-demo.test'])),
      ).toBeVisible();

      await page.reload();
      await expect(page).not.toHaveURL(/\/payroll/);
    },
  );
});

test.describe('A zone administrator stays in its zone', () => {
  test(
    "sees only its zone's users and cannot define access groups",
    { tag: ['@SRS-22-13', '@SRS-22-19', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'rbac.admin.north@fci-demo.test',
        { role: 'SYSTEM_ADMINISTRATOR', office: 'NA11' },
        'A-01',
      );
      // The register pages at 20 (SRS-NF-3): users are found by search.
      await page.getByLabel(t('en', 'a01.search')).fill('rbac.maker.ludhiana');
      await expect(page.getByText('rbac.maker.ludhiana@fci-demo.test')).toBeVisible();
      await page.getByLabel(t('en', 'a01.search')).fill('finance.mumbai');
      await expect(page.getByText('finance.mumbai@fci-demo.test')).toHaveCount(0);
      await page.goto('/payroll/?s=A-03');
      // The route is HQ-only, so the action is disabled with its reason before any form is filled
      // (the server's ADM-403-HQ-ONLY refusal stays and is proven in the integration tests).
      const create = page.getByRole('button', { name: t('en', 'action.newAccessGroup') });
      await expect(create).toBeDisabled();
      await expect(create).toHaveAttribute('title', t('en', 'error.AUTHZ-403-HQ-ONLY'));
    },
  );
});
