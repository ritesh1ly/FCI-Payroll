import { expect, test } from '@playwright/test';
import {
  answerLoginOtp,
  enterCredentials,
  errorAlert,
  expectNoBrowserStorage,
  loginOtpField,
  openLogin,
  otpFromLog,
  t,
} from '../support/app';

/**
 * X-08 sign-in in the login wireframe (2026-10-29): the password, then the OTP sent to the
 * registered mobile (SRS-G-48, OD-36); the Employee ID (EIS number) as the user ID (OD-37); inline
 * validation; "Remember this device" (OD-38). Each test signs in a different demo user, so the
 * OTP issuance cap (20 an hour per user) is never reached by this file.
 */
const masked = (mobile: string) => `******${mobile.slice(-4)}`;

/** The reference may scroll vertically; its content must remain within the viewport width. */
async function fitsTheWidth(page: import('@playwright/test').Page) {
  return page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
}

test.describe('Sign-in with OTP on the registered mobile', () => {
  test(
    'the password sends a code to the masked mobile and the code opens the workspace',
    // SC-01-0140 (Module 01 numbering of a use case kb_review_01 maps to module 02 / 04: a comment, not an e2e_trace tag).
    { tag: ['@SRS-G-48', '@SRS-G-35', '@SRS-G-21', '@positive'] },
    async ({ page }) => {
      await openLogin(page, 'en');
      const since = Date.now();
      await enterCredentials(page, 'en', 'agm.north@fci-demo.test');
      await expect(page.getByRole('heading', { name: t('en', 'login.otp.title') })).toBeVisible();
      await expect(page.getByText(t('en', 'login.otp.sentSms', [masked('9000010008')]))).toBeVisible();
      await expect(page.getByText(/^Valid for \d+:\d\d$/)).toBeVisible();
      // The password left the page with the first step; no session yet.
      await expect(page.getByPlaceholder(t('en', 'login.passwordPlaceholder'))).toHaveCount(0);
      expect((await page.request.get('/nginx/fci-payroll/auth/me')).status()).toBe(401);
      await answerLoginOtp(page, 'en', since);
      await expect(page).toHaveURL(/\/payroll/);
      await expect(page.locator('.rolebar b')).toBeVisible();
      await expectNoBrowserStorage(page);
    },
  );

  test(
    'a wrong code is refused and cleared; a resend makes the earlier code useless; the new one signs in',
    { tag: ['@SRS-G-48', '@SRS-G-49', '@negative'] },
    async ({ page }) => {
      await openLogin(page, 'en');
      const since = Date.now();
      await enterCredentials(page, 'en', 'agm.east@fci-demo.test');
      const field = loginOtpField(page, 'en');
      const earlier = await otpFromLog('AUTH_LOGIN', since);
      const wrong = earlier === '000000' ? '111111' : '000000';
      await field.fill(wrong);
      await page.getByRole('button', { name: t('en', 'login.otp.verify'), exact: true }).click();
      await expect(errorAlert(page)).toHaveText(t('en', 'error.AUTH-400-OTP'));
      await expect(field).toHaveValue('');

      const resentAt = Date.now();
      await page.getByRole('button', { name: t('en', 'login.otp.resend') }).click();
      await expect(page.getByText(t('en', 'login.otp.resent'))).toBeVisible();
      const later = await otpFromLog('AUTH_LOGIN', resentAt);
      await field.fill(earlier);
      await page.getByRole('button', { name: t('en', 'login.otp.verify'), exact: true }).click();
      await expect(errorAlert(page)).toHaveText(t('en', 'error.AUTH-400-OTP'));
      await field.fill(later);
      await page.getByRole('button', { name: t('en', 'login.otp.verify'), exact: true }).click();
      await expect(page).toHaveURL(/\/payroll/);
    },
  );

  test(
    '"Use another account" returns to the card with the user ID kept and the password cleared',
    { tag: ['@SRS-G-48', '@edge'] },
    async ({ page }) => {
      await openLogin(page, 'en');
      await enterCredentials(page, 'en', 'agm.south@fci-demo.test');
      await expect(loginOtpField(page, 'en')).toBeVisible();
      await page.getByRole('button', { name: t('en', 'login.otp.back') }).click();
      await expect(page.getByPlaceholder(t('en', 'login.passwordPlaceholder'))).toHaveValue('');
      await expect(page.getByPlaceholder(t('en', 'login.emailPlaceholder'))).toHaveValue(
        'agm.south@fci-demo.test',
      );
    },
  );
});

test.describe('The sign-in card', () => {
  test(
    'inline validation stops an empty, invalid or non-ASCII user ID and an empty password before any request',
    { tag: ['@SRS-G-35', '@negative'] },
    async ({ page }) => {
      await openLogin(page, 'en');
      let requests = 0;
      page.on('request', (r) => {
        if (r.url().endsWith('/nginx/fci-payroll/auth/login')) requests += 1;
      });
      const submit = page.getByRole('button', { name: t('en', 'login.submit'), exact: true });
      await submit.click();
      await expect(page.getByText(t('en', 'login.emailRequired'))).toBeVisible();
      await expect(page.getByText(t('en', 'login.passwordRequired'))).toBeVisible();
      const user = page.getByPlaceholder(t('en', 'login.emailPlaceholder'));
      await user.fill('name@fci');
      await user.blur();
      await expect(page.getByText(t('en', 'login.emailInvalid'))).toBeVisible();
      await user.fill('नाम@fci.gov.in');
      await expect(page.getByText(t('en', 'login.asciiOnly'))).toBeVisible();
      await page.getByRole('tab', { name: t('en', 'login.method.employee') }).click();
      await submit.click();
      await expect(page.getByText(t('en', 'login.employeeIdRequired'))).toBeVisible();
      expect(requests).toBe(0);
    },
  );

  test(
    'show / hide password, help, privacy note and the wireframe layout on a phone',
    { tag: ['@SRS-G-35', '@edge'] },
    async ({ page }) => {
      await page.setViewportSize({ width: 390, height: 844 });
      await openLogin(page, 'en');
      const password = page.getByPlaceholder(t('en', 'login.passwordPlaceholder'));
      await password.fill('Secret#1');
      await page.getByRole('button', { name: t('en', 'login.showPassword') }).click();
      await expect(password).toHaveAttribute('type', 'text');
      await page.getByRole('button', { name: t('en', 'login.hidePassword') }).click();
      await expect(password).toHaveAttribute('type', 'password');
      // Phone layout as the wireframe (OD-44): the brand tile, the name and the headline above the
      // card, no illustration; the page may scroll down, never sideways.
      await expect(page.locator('.lx-scene')).toBeHidden();
      await expect(page.locator('.lx-mark')).toBeVisible();
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      const [brand, card] = await Promise.all([
        page.locator('.lx-brand').boundingBox(),
        page.locator('.lx-card').boundingBox(),
      ]);
      expect(brand && card && brand.y < card.y).toBe(true);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
      await page.getByRole('button', { name: t('en', 'login.helpLine') }).click();
      await expect(page.getByRole('dialog')).toContainText(t('en', 'login.helpdeskExtension'));
      await page.keyboard.press('Escape');
      await page.getByRole('button', { name: t('en', 'login.privacy') }).click();
      await expect(page.getByRole('dialog')).toContainText(t('en', 'login.privacyTitle'));
      await page.getByRole('button', { name: t('en', 'login.closeHelp') }).click();
      await expect(page.getByRole('dialog')).toHaveCount(0);
    },
  );

  test(
    'the wireframe layout stays within the viewport and the form remains reachable',
    { tag: ['@SRS-G-35', '@edge'] },
    async ({ page, isMobile }) => {
      // Exercise the reference's side-by-side and stacked desktop layouts. Phone emulation has
      // its own coverage below and in phone-layout.spec.ts.
      test.skip(isMobile, 'desktop and tablet sizes');
      for (const [width, height] of [
        [1366, 768],
        [1280, 720],
        [768, 1024],
      ] as const) {
        await page.setViewportSize({ width, height });
        await openLogin(page, 'en');
        const signIn = page.getByRole('button', { name: t('en', 'login.submit'), exact: true });
        await signIn.scrollIntoViewIfNeeded();
        await expect(signIn).toBeInViewport();
        await expect(page.locator('.lx-scene')).toBeVisible();
        expect(await fitsTheWidth(page), `${width} x ${height}`).toBe(true);
      }
    },
  );

  test(
    'remember this device keeps the user ID through a sign-out, never the password, and only on request',
    { tag: ['@OD-38', '@SRS-SEC-14', '@edge'] },
    async ({ page, browserName }) => {
      await openLogin(page, 'en');
      await page.getByLabel(t('en', 'login.remember')).check();
      const since = Date.now();
      await enterCredentials(page, 'en', 'agm.west@fci-demo.test');
      await answerLoginOtp(page, 'en', since);
      await expect(page).toHaveURL(/\/payroll/);
      const remembered = (await page.context().cookies()).find((c) => c.name === 'FCI_REMEMBER');
      expect(remembered?.value).toBe('email%7Cagm.west%40fci-demo.test');
      // Playwright's WebKit builds for Windows and Linux report every cookie as SameSite=None (no
      // SameSite support there); the attribute itself is unit-tested (login-rules.test.ts).
      if (browserName !== 'webkit') expect(remembered?.sameSite).toBe('Strict');
      await expectNoBrowserStorage(page);

      await page.locator('.me').click();
      await page.getByRole('menuitem', { name: t('en', 'account.signOut') }).click();
      await expect(page.getByRole('heading', { name: t('en', 'login.signIn') })).toBeVisible();
      await expect(page.getByPlaceholder(t('en', 'login.emailPlaceholder'))).toHaveValue(
        'agm.west@fci-demo.test',
      );
      await expect(page.getByLabel(t('en', 'login.remember'))).toBeChecked();
      await expect(page.getByPlaceholder(t('en', 'login.passwordPlaceholder'))).toHaveValue('');
    },
  );
});
