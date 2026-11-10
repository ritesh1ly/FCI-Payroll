import { expect, type Page, test } from '@playwright/test';
import { DEMO_PASSWORD, otpFromLog, signIn, t } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql } from '../support/db';

/**
 * The own password change asks the user's OTP (SRS-G-21, X-08 l.894; US-GAP-128): the current
 * password and the policy are checked first, then the server answers with a challenge bound to the
 * account and the change is made only with its code. Each run signs in as an account of its own
 * (database fixture: a copy of a demo account's credential), so the demo users' passwords never
 * change and the spec re-runs.
 */
const NEW_PASSWORD = 'Example#Pass5678';

/** A fresh active account with the demo password and the demo read-only Punjab access. */
function freshAccount(): string {
  const login = `e2e.pwd.${Date.now()}@fci-demo.test`;
  const source = `(SELECT user_id FROM app_user WHERE login_id = 'rbac.reports.punjab@fci-demo.test')`;
  const self = `(SELECT user_id FROM app_user WHERE login_id = '${login}')`;
  sql(
    [
      `INSERT INTO app_user (login_id, display_name, user_category, registered_mobile, registered_email, user_status) VALUES ('${login}', 'E2E Password', 'OPERATIONS', '9000199999', '${login}', 'ACTIVE')`,
      `INSERT INTO app_user_credential (user_id, password_hash, hash_algorithm, must_change_flag) SELECT ${self}, password_hash, hash_algorithm, 'N' FROM app_user_credential WHERE user_id = ${source}`,
      `INSERT INTO app_user_preference (user_id, preferred_locale) VALUES (${self}, 'en')`,
      `INSERT INTO app_user_access_group (user_id, access_group_id, effective_from, assigned_by, approval_id) SELECT ${self}, access_group_id, effective_from, 'e2e.fixture', approval_id FROM app_user_access_group WHERE user_id = ${source} AND revoked_at IS NULL`,
    ].join(';\n'),
  );
  return login;
}

async function openChange(page: Page) {
  await page.getByRole('button', { name: t('en', 'account.menu') }).click();
  await page.getByRole('menuitem', { name: t('en', 'account.changePassword') }).click();
  await expect(page.getByRole('heading', { name: t('en', 'password.title') })).toBeVisible();
}

async function fill(page: Page, current: string, next: string) {
  await page.getByLabel(t('en', 'password.current'), { exact: true }).fill(current);
  await page.getByLabel(t('en', 'password.new'), { exact: true }).fill(next);
  await page.getByLabel(t('en', 'password.confirm'), { exact: true }).fill(next);
  await page.getByRole('button', { name: t('en', 'password.submit') }).click();
}

const otpField = (page: Page) => page.getByRole('textbox', { name: t('en', 'otp.code') });

test.describe.serial('X-08 own password change with OTP (SRS-G-21)', () => {
  test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);

  test(
    'US-GAP-128: a wrong current password is refused before any OTP; a wrong code changes nothing; the right code changes it',
    { tag: ['@SRS-G-21', '@US-GAP-128', '@edge'] },
    async ({ page }) => {
      const login = freshAccount();
      await signIn(page, 'en', login);
      await expect(page).toHaveURL(/\/payroll/);

      // The current password first: no challenge for a wrong one.
      await openChange(page);
      await fill(page, 'Wrong#Pass0000', NEW_PASSWORD);
      await expect(page.locator('.fl-password [role="alert"], .fl-password .fl-error').first()).toBeVisible();
      await expect(otpField(page)).toHaveCount(0);

      // Then the OTP: a wrong code changes nothing.
      await fill(page, DEMO_PASSWORD, NEW_PASSWORD);
      await expect(otpField(page)).toBeVisible();
      await otpField(page).fill('000000');
      await page.getByRole('button', { name: t('en', 'otp.verify') }).click();
      await expect(page.getByRole('alert').filter({ hasText: t('en', 'error.AUTH-400-OTP') })).toBeVisible();
      await expect(page.getByText(t('en', 'password.changed'))).toHaveCount(0);

      // The right code of the challenge bound to this account changes it.
      await page.reload();
      await openChange(page);
      const since = Date.now();
      await fill(page, DEMO_PASSWORD, NEW_PASSWORD);
      await expect(otpField(page)).toBeVisible();
      await otpField(page).fill(await otpFromLog('AUTH_OWN_PASSWORD_CHANGE', since));
      await page.getByRole('button', { name: t('en', 'otp.verify') }).click();
      await expect(page.getByText(t('en', 'password.changed'))).toBeVisible();

      // The new password signs in.
      await page.context().clearCookies();
      await signIn(page, 'en', login, NEW_PASSWORD);
      await expect(page).toHaveURL(/\/payroll/);
    },
  );
});
