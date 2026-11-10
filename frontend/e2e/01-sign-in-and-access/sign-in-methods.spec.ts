import { expect, test } from '@playwright/test';
import {
  answerLoginOtp,
  enterCredentials,
  errorAlert,
  type Locale,
  loginOtpField,
  openLogin,
  SIGN_IN_PAGE,
  type SignInMethod,
  t,
} from '../support/app';

/**
 * Every way to sign in (SRS-G-35, SRS-G-48; OD-36, OD-37, OD-41): the official e-mail and the
 * Employee ID (EIS number), each with the password and then the code sent to the registered mobile;
 * and "Sign in with OTP on registered mobile", the number and its code, no password. For each method:
 * the happy path in English and Hindi, every refusal answered as for the other accounts (the e-mail
 * and Employee ID before any code; the mobile only after the right code, so its page never tells
 * which numbers are registered), and its own format check before any request.
 * The refusals run first: a wrong password is followed by that user's successful sign-in, so no
 * failure count is left behind. Demo users and their seeded EIS numbers and mobiles (V008_1 ...).
 */
interface Account {
  email: string;
  employee: string;
  mobile: string;
}
const USER = (email: string, employee: string, mobile: string): Account => ({ email, employee, mobile });
/** The account each method signs in with: its own user, so the OTP cap is spread. */
const HAPPY: Record<SignInMethod, Account> = {
  email: USER('agm.north@fci-demo.test', '107506', '9000010008'),
  employee: USER('emp.rsharma@fci-demo.test', '104882', '9000010000'),
  mobile: USER('agm.west@fci-demo.test', '207607', '9000010009'),
};
const LOCKED = USER('locked.user@fci-demo.test', '107011', '9000010014');
const DEACTIVATED = USER('deactivated.user@fci-demo.test', '107112', '9000010015');
const SUSPENDED = USER('rbac.suspended@fci-demo.test', '109617', '9000170015');
const UNKNOWN = USER('nobody.at.all@fci-demo.test', 'NO-SUCH-EIS', '9999999999');
const METHODS: SignInMethod[] = ['email', 'employee', 'mobile'];
const masked = (mobile: string) => `******${mobile.slice(-4)}`;

for (const method of ['email', 'employee'] as SignInMethod[]) {
  test.describe(`Sign-in by ${method}: refusals`, () => {
    const cases: [string, Account, string, string?][] = [
      ['a wrong password', HAPPY[method], 'error.AUTH-401-CREDENTIALS', 'Wrong#Pass2026'],
      ['an unknown identifier (the same answer: no enumeration)', UNKNOWN, 'error.AUTH-401-CREDENTIALS'],
      ['a locked account', LOCKED, 'error.AUTH-423-ACCOUNT-LOCKED'],
      ['a deactivated account', DEACTIVATED, 'error.AUTH-403-DEACTIVATED'],
      ['a suspended account', SUSPENDED, 'error.AUTH-403-SUSPENDED'],
    ];
    for (const [name, account, key, password] of cases) {
      test(
        `${name} is refused and no code is sent`,
        { tag: ['@SRS-G-35', '@SRS-G-49', '@SRS-EH-3', '@negative'] },
        async ({ page }) => {
          await openLogin(page, 'en');
          await enterCredentials(page, 'en', account[method], password, method);
          await expect(errorAlert(page)).toHaveText(t('en', key));
          await expect(loginOtpField(page, 'en')).toHaveCount(0);
          await expect(page).toHaveURL(SIGN_IN_PAGE);
        },
      );
    }

    test(
      'its format is checked on the card before any request',
      { tag: ['@SRS-G-35', '@negative'] },
      async ({ page }) => {
        await openLogin(page, 'en');
        let requests = 0;
        page.on('request', (r) => {
          if (r.url().endsWith('/nginx/fci-payroll/auth/login')) requests += 1;
        });
        const typed: Record<'email' | 'employee', [string, string]> = {
          email: ['name@fci', 'login.emailInvalid'],
          employee: ['', 'login.employeeIdRequired'],
        };
        const [value, key] = typed[method as 'email' | 'employee'];
        await enterCredentials(page, 'en', value, undefined, method);
        await expect(page.getByText(t('en', key))).toBeVisible();
        expect(requests).toBe(0);
      },
    );
  });
}

test.describe('Sign-in by mobile (no password): refusals', () => {
  test(
    'an unregistered number gets the same code step, and no code opens it',
    { tag: ['@SRS-G-48', '@SRS-EH-3', '@negative'] },
    async ({ page }) => {
      await openLogin(page, 'en');
      await enterCredentials(page, 'en', UNKNOWN.mobile, undefined, 'mobile');
      await expect(page.getByText(t('en', 'login.otp.sentSms', [masked(UNKNOWN.mobile)]))).toBeVisible();
      await loginOtpField(page, 'en').fill('123456');
      await page.getByRole('button', { name: t('en', 'login.otp.verify'), exact: true }).click();
      await expect(errorAlert(page)).toHaveText(t('en', 'error.AUTH-400-OTP'));
    },
  );

  test(
    'a wrong code is refused and says nothing about the account',
    { tag: ['@SRS-G-48', '@SRS-G-49', '@negative'] },
    async ({ page }) => {
      await openLogin(page, 'en');
      await enterCredentials(page, 'en', LOCKED.mobile, undefined, 'mobile');
      await loginOtpField(page, 'en').fill('000000');
      await page.getByRole('button', { name: t('en', 'login.otp.verify'), exact: true }).click();
      await expect(errorAlert(page)).toHaveText(t('en', 'error.AUTH-400-OTP'));
    },
  );

  const states: [string, Account, string][] = [
    ['a locked account', LOCKED, 'error.AUTH-423-ACCOUNT-LOCKED'],
    ['a deactivated account', DEACTIVATED, 'error.AUTH-403-DEACTIVATED'],
    ['a suspended account', SUSPENDED, 'error.AUTH-403-SUSPENDED'],
  ];
  for (const [name, account, key] of states) {
    test(
      `${name} is told why only after its right code`,
      { tag: ['@SRS-G-48', '@SRS-G-36', '@SRS-G-49', '@negative'] },
      async ({ page }) => {
        await openLogin(page, 'en');
        const since = Date.now();
        await enterCredentials(page, 'en', account.mobile, undefined, 'mobile');
        await answerLoginOtp(page, 'en', since);
        await expect(errorAlert(page)).toHaveText(t('en', key));
        await expect(page).toHaveURL(SIGN_IN_PAGE);
      },
    );
  }

  test(
    'its format is checked on the card, and it has no password and no Forgot password',
    { tag: ['@SRS-G-35', '@negative'] },
    async ({ page }) => {
      await openLogin(page, 'en');
      let requests = 0;
      page.on('request', (r) => {
        if (r.url().includes('/nginx/fci-payroll/auth/login')) requests += 1;
      });
      await enterCredentials(page, 'en', '12345', undefined, 'mobile');
      await expect(page.getByText(t('en', 'login.mobileInvalid'))).toBeVisible();
      await expect(page.getByPlaceholder(t('en', 'login.passwordPlaceholder'))).toHaveCount(0);
      await expect(page.getByRole('button', { name: t('en', 'login.forgotPassword') })).toHaveCount(0);
      expect(requests).toBe(0);
    },
  );
});

for (const locale of ['en', 'hi'] as Locale[]) {
  for (const method of METHODS) {
    test(
      `sign-in by ${method} (${locale}): the password, the code on the masked mobile, the workspace`,
      { tag: ['@SRS-G-35', '@SRS-G-48', '@positive'] },
      async ({ page }) => {
        const account = HAPPY[method];
        await openLogin(page, locale);
        const since = Date.now();
        await enterCredentials(
          page,
          locale,
          method === 'mobile' ? `+91 ${account.mobile}` : account[method],
          undefined,
          method,
        );
        await expect(page.getByText(t(locale, 'login.otp.sentSms', [masked(account.mobile)]))).toBeVisible();
        await answerLoginOtp(page, locale, since);
        await expect(page).toHaveURL(/\/payroll/);
        await expect(page.locator('.rolebar b')).toBeVisible();
      },
    );
  }
}
