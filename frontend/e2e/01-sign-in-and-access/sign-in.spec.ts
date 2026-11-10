import { expect, test } from '@playwright/test';
import { apiStatus, errorAlert, type Locale, openLogin, SIGN_IN_PAGE, signIn, t } from '../support/app';

type SignInArgs = [Locale, string, string?];

/** Refused sign-ins render the API's message key in the chosen language. */
for (const locale of ['en', 'hi'] as Locale[]) {
  test.describe(`Sign-in refusals (${locale})`, () => {
    // Name, sign-in arguments, expected message, requirement tags (SRS-G-35 sign-in, SRS-G-49 lockout,
    // SRS-G-36 deactivation, SRS-G-29 assignment scope, SRS-EH-3 message rules).
    const cases: [string, SignInArgs, string, string[]][] = [
      [
        'wrong password',
        [locale, 'hr.delhi@fci-demo.test', 'Wrong#Password1'],
        'error.AUTH-401-CREDENTIALS',
        ['@SRS-G-35', '@SRS-G-49', '@negative'],
      ],
      // The same message as a wrong password: no user enumeration.
      [
        'unknown login',
        [locale, 'nobody@fci-demo.test'],
        'error.AUTH-401-CREDENTIALS',
        ['@SRS-G-35', '@SRS-EH-3', '@negative'],
      ],
      [
        'locked account',
        [locale, 'locked.user@fci-demo.test'],
        'error.AUTH-423-ACCOUNT-LOCKED',
        ['@SRS-G-49', '@negative'],
      ],
      [
        'deactivated account',
        [locale, 'deactivated.user@fci-demo.test'],
        'error.AUTH-403-DEACTIVATED',
        ['@SRS-G-36', '@negative'],
      ],
      // Suspended (V026_9): refused with its own reason, not as a wrong password.
      [
        'suspended account',
        [locale, 'rbac.suspended@fci-demo.test'],
        'error.AUTH-403-SUSPENDED',
        ['@SRS-G-36', '@negative'],
      ],
      [
        'expired assignment',
        [locale, 'expired.group@fci-demo.test'],
        'error.AUTH-403-NO-SCOPE',
        ['@SRS-G-29', '@edge'],
      ],
    ];
    for (const [name, args, key, tag] of cases) {
      test(name, { tag }, async ({ page }) => {
        await signIn(page, ...args);
        await expect(errorAlert(page)).toHaveText(t(locale, key));
        await expect(page).toHaveURL(SIGN_IN_PAGE);
      });
    }

    test(
      'the workspace is guarded without a session',
      { tag: ['@SRS-G-48', '@SRS-SEC-14', '@negative'] },
      async ({ page }) => {
        await page.goto('/payroll/?s=M-01');
        await expect(page).toHaveURL(SIGN_IN_PAGE);
        await openLogin(page, locale);
        // The sign-in page never shows credentials, not even the demo password.
        await expect(page.getByText(/Fci@Demo2026/)).toHaveCount(0);
      },
    );
  });
}

/** Password self-service, without changing any demo user's password. */
test.describe('Password self-service', () => {
  test(
    'forgot password answers an unknown login like a real one, starts the OTP step empty and refuses a wrong code',
    { tag: ['@SRS-G-49', '@SRS-EH-3', '@negative'] },
    async ({ page }) => {
      await openLogin(page, 'en');
      await page.getByRole('button', { name: t('en', 'login.forgotPassword') }).click();
      const help = page.locator('section').filter({ has: page.locator('#help-title') });
      await expect(help.getByRole('heading', { name: t('en', 'login.resetTitle') })).toBeVisible();
      const send = help.getByRole('button', { name: new RegExp(t('en', 'reset.sendOtp')) });
      await expect(send).toBeDisabled();
      await help.getByRole('textbox').fill('nobody.at.all@fci-demo.test');
      await send.click();
      // No user enumeration: the same "OTP sent" step as for an existing account.
      await expect(help.getByText(t('en', 'reset.otpSent'))).toBeVisible();
      // The OTP field starts empty: it once kept the login ID typed in step 1 (fixed 2026-10-22).
      const code = help.getByLabel(t('en', 'otp.code'));
      await expect(code).toHaveValue('');
      await code.fill('000000');
      // Exact: "New password" is also part of "Confirm new password".
      await help.getByLabel(t('en', 'password.new'), { exact: true }).fill('Example#Pass1234');
      await help.getByLabel(t('en', 'password.confirm'), { exact: true }).fill('Example#Pass1234');
      await help.getByRole('button', { name: new RegExp(t('en', 'reset.submit')) }).click();
      await expect(help.getByRole('alert')).toBeVisible();
      await expect(page).toHaveURL(SIGN_IN_PAGE);
    },
  );

  test(
    'a forced password change holds every screen and every API call until done; leaving signs out',
    { tag: ['@SRS-G-49', '@SRS-SEC-14', '@negative'] },
    async ({ page }) => {
      await signIn(page, 'en', 'emp.mverma@fci-demo.test');
      await expect(page.getByText(t('en', 'password.forced'))).toBeVisible();
      // An edited URL does not get past it, and neither does the API.
      await page.goto('/payroll/?s=E-01');
      await expect(page.getByText(t('en', 'password.forced'))).toBeVisible();
      expect(await apiStatus(page, '/v1/eseva/me')).toBe(403);
      await page.getByRole('button', { name: t('en', 'account.signOut') }).click();
      await expect(page).toHaveURL(SIGN_IN_PAGE);
    },
  );
});
