import { expect, test, type Page } from '@playwright/test';
import { apiProblem, businessToday, signIn, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql, sqlRows } from '../support/db';

/**
 * Module 02 user administration on A-01 (SRS-G-49, SRS-G-50, SRS-G-13): an administrator ends a
 * signed-in user's sessions with a reason, the Active User Report and the Maker / Checker readiness
 * of every payroll unit, and every list export asks for CSV or Excel and downloads it.
 */

const ADMIN = { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' };

async function openTab(page: Page, key: string) {
  await page.getByRole('tab', { name: t('en', `a01.tab.${key}`) }).click();
}

test.describe('A-01 session administration', () => {
  test(
    "ending a user's sessions with a reason sends the open page back to sign-in; the account stays active",
    { tag: ['@SRS-G-49', '@SC-02-0043', '@SC-02-0046', '@positive'] },
    async ({ browser, page }) => {
      await signIn(page, 'en', 'rbac.ldh.khanna@fci-demo.test');
      await expect(page).toHaveURL(/\/payroll/);

      const admin = await browser.newPage();
      await workAs(admin, 'en', 'sysadmin@fci-demo.test', ADMIN, 'A-01');
      await admin.getByLabel(t('en', 'a01.search')).fill('rbac.ldh.khanna');
      const row = admin.getByRole('row', { name: /rbac\.ldh\.khanna/ });
      await row.getByRole('button', { name: t('en', 'a01.terminate') }).click();
      const dialog = admin.getByRole('dialog');
      await expect(dialog.getByRole('button', { name: t('en', 'a01.terminate') })).toBeDisabled();
      await dialog.getByRole('textbox').fill('E2E: shared terminal');
      await dialog.getByRole('button', { name: t('en', 'a01.terminate') }).click();
      await expect(admin.locator('.fl-toast')).toContainText('session');
      await expect(row.getByText(t('en', 'status.ACTIVE'))).toBeVisible();

      await page.reload();
      await expect(page).not.toHaveURL(/\/payroll/);

      // The login record shows the ended sign-in and its reason.
      await row.getByRole('button', { name: t('en', 'a01.logins') }).click();
      await expect(admin.getByText(t('en', 'session.end.ADMIN_REVOKE')).first()).toBeVisible();
      await admin.close();
    },
  );
});

test.describe('A-01 reports', () => {
  test(
    'the Active User Report lists role, unit and last sign-in, says when inactivity is not configured, and downloads as CSV or Excel',
    { tag: ['@SRS-G-50', '@SRS-G-13', '@SC-02-0013', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', 'sysadmin@fci-demo.test', ADMIN, 'A-01');
      await openTab(page, 'report');
      await expect(page.getByText(t('en', 'a01.report.notConfigured'))).toBeVisible();
      await expect(page.getByRole('checkbox', { name: t('en', 'a01.report.inactiveOnly') })).toBeDisabled();
      await page.getByLabel(t('en', 'a01.search')).fill('rbac.maker.ludhiana');
      const row = page.getByRole('row', { name: /rbac\.maker\.ludhiana/ });
      // A plain string is a substring match; the label has parentheses, so no RegExp.
      await expect(row.getByText(t('en', 'role.PAYROLL_MAKER'))).toBeVisible();

      for (const [format, extension] of [
        ['CSV', 'csv'],
        ['XLSX', 'xlsx'],
      ] as const) {
        await page.getByRole('button', { name: t('en', 'action.export') }).click();
        const dialog = page.getByRole('dialog');
        await expect(dialog.getByRole('heading')).toHaveText(
          t('en', 'export.title', [t('en', 'a01.tab.report')]),
        );
        await dialog.locator(`input[value="${format}"]`).check();
        const downloading = page.waitForEvent('download');
        await dialog.getByRole('link', { name: t('en', 'export.download') }).click();
        const file = await downloading;
        expect(file.suggestedFilename()).toBe(`active-users.${extension}`);
        await expect(page.getByRole('dialog')).toHaveCount(0);
      }
    },
  );

  test(
    'every payroll unit shows whether it has a Maker and a separate Checker',
    { tag: ['@SRS-G-50', '@SRS-22-11', '@SC-02-0011', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', 'sysadmin@fci-demo.test', ADMIN, 'A-01');
      await openTab(page, 'readiness');
      await expect(page.getByText(t('en', 'a01.readiness.minimumNotConfigured'))).toBeVisible();
      const states = [
        t('en', 'a01.readiness.READY'),
        t('en', 'a01.readiness.COVERED_FROM_ABOVE'),
        t('en', 'a01.readiness.NOT_READY'),
      ];
      const delhi = page.getByRole('row', { name: /RO-DL/ });
      await expect(delhi).toBeVisible();
      expect(states).toContain(((await delhi.locator('.pill').first().textContent()) ?? '').trim());
    },
  );

  test(
    'a zone administrator reports on its own zone only',
    { tag: ['@SRS-22-13', '@SC-02-0045', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'rbac.admin.north@fci-demo.test',
        { role: 'SYSTEM_ADMINISTRATOR', office: 'NA11' },
        'A-01',
      );
      await openTab(page, 'readiness');
      await expect(page.getByRole('row', { name: /NA11/ }).first()).toBeVisible();
      await expect(page.getByRole('row', { name: /ZO-W/ })).toHaveCount(0);
    },
  );
});

test.describe('A-04 access assignments: the scope before the account status', () => {
  test(
    'US-GAP-125 / Wave A review: a zone administrator is told nothing of an account outside its zone, not even that it is suspended',
    { tag: ['@SRS-22-23', '@SRS-22-2', '@US-GAP-125', '@negative'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      const id = (login: string) =>
        sqlRows(`SELECT user_id FROM app_user WHERE login_id = '${login}'`)[0]?.[0] as string;
      const suspendedNorth = Number(id('rbac.suspended@fci-demo.test'));
      const west = 'rbac.cpf.west@fci-demo.test';
      const proposal = (userId: number) => ({
        userId,
        groupId: 1,
        effectiveFrom: businessToday(),
        reason: 'E2E: scope before status',
      });
      await workAs(
        page,
        'en',
        'rbac.admin.north@fci-demo.test',
        { role: 'SYSTEM_ADMINISTRATOR', office: 'NA11' },
        'A-04',
      );
      // In its own zone a suspended account is refused for its status (X-01 User, l.1121).
      expect(await apiProblem(page, '/v1/admin/assignments', 'POST', proposal(suspendedNorth))).toEqual({
        status: 409,
        errorCode: 'ADM-409-STATUS',
      });
      // Outside its zone the same refusal would tell the account's state: it reads as unknown.
      sql(`UPDATE app_user SET user_status = 'SUSPENDED' WHERE login_id = '${west}'`);
      try {
        expect(await apiProblem(page, '/v1/admin/assignments', 'POST', proposal(Number(id(west))))).toEqual({
          status: 404,
          errorCode: 'COM-404-NOT-FOUND',
        });
      } finally {
        sql(`UPDATE app_user SET user_status = 'ACTIVE' WHERE login_id = '${west}'`);
      }
    },
  );
});
