import { expect, test } from '@playwright/test';
import {
  answerLoginOtp,
  DEMO_PASSWORD,
  enterCredentials,
  errorAlert,
  loginOtpField,
  openLogin,
  t,
} from '../support/app';

/**
 * The login page against the industry checklist for authentication: OWASP ASVS 4.0 chapters 2
 * (authentication) and 3 (session) and the OWASP Web Security Testing Guide WSTG-ATHN / WSTG-SESS.
 * What other specs already prove is not repeated here: the methods and their refusals
 * (sign-in-methods.spec.ts), the OTP step, resend, remember-device and the layout (login-otp.spec.ts),
 * lockout, single session and forced change (sign-in.spec.ts, access-control.spec.ts), forgot password
 * without enumeration (sign-in.spec.ts). The user is not one the other specs sign in, so this file's
 * code requests stay well inside the OTP cap and the per-user limit of 5 attempts a minute.
 */
/** Refused attempts and the case check (its last attempt succeeds, so no failure count stays). */
const USER = 'finance.mumbai@fci-demo.test';
/** The sign-ins (cookie and sign-out); a second user keeps each under 5 attempts a minute. */
const SIGNED = 'ldh.depot@fci-demo.test';

test.describe('Login: industry practice (OWASP ASVS V2 / V3, WSTG-ATHN)', () => {
  test(
    'credentials travel only in a POST body, the fields carry password-manager hints, the password is masked',
    { tag: ['@SRS-G-35', '@SRS-SEC-14', '@edge'] },
    async ({ page }) => {
      await openLogin(page, 'en');
      const user = page.getByPlaceholder(t('en', 'login.emailPlaceholder'));
      const password = page.getByPlaceholder(t('en', 'login.passwordPlaceholder'));
      // ASVS 2.1.x: autocomplete hints so password managers work; masked by default.
      await expect(user).toHaveAttribute('autocomplete', 'username');
      await expect(password).toHaveAttribute('autocomplete', 'current-password');
      await expect(password).toHaveAttribute('type', 'password');
      await expect(page.locator('#lx-form')).toHaveAttribute('method', 'post');
      // WSTG-ATHN-01: the credentials never reach a URL.
      const posted = page.waitForRequest((r) => r.url().endsWith('/nginx/fci-payroll/auth/login'));
      await enterCredentials(page, 'en', USER, 'Wrong#Pass2026');
      const request = await posted;
      expect(request.method()).toBe('POST');
      expect(request.url()).not.toContain('Wrong');
      expect(request.postDataJSON()).toMatchObject({ loginId: USER, password: 'Wrong#Pass2026' });
      expect(page.url()).not.toContain('Wrong');
      await expect(errorAlert(page)).toHaveText(t('en', 'error.AUTH-401-CREDENTIALS'));
      // The refused password does not stay readable in the field's type.
      await expect(password).toHaveAttribute('type', 'password');
    },
  );

  test(
    'paste works in every field and Enter submits (ASVS 2.1.11: no paste blocking)',
    { tag: ['@SRS-G-35', '@edge'] },
    async ({ page, context }) => {
      await context.grantPermissions(['clipboard-read', 'clipboard-write']).catch(() => undefined);
      await openLogin(page, 'en');
      const user = page.getByPlaceholder(t('en', 'login.emailPlaceholder'));
      const password = page.getByPlaceholder(t('en', 'login.passwordPlaceholder'));
      // A paste is an input event with the text; nothing on the page prevents it.
      for (const [field, text] of [
        [user, USER],
        [password, 'Pasted#Value1'],
      ] as const) {
        await field.focus();
        await page.evaluate((value) => {
          const target = document.activeElement as HTMLInputElement;
          const data = new DataTransfer();
          data.setData('text/plain', value);
          const allowed = target.dispatchEvent(
            new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }),
          );
          if (allowed) document.execCommand('insertText', false, value);
        }, text);
        await expect(field).toHaveValue(text);
      }
      const posted = page.waitForRequest((r) => r.url().endsWith('/nginx/fci-payroll/auth/login'));
      await password.press('Enter');
      expect((await posted).postDataJSON()).toMatchObject({ loginId: USER });
      await expect(errorAlert(page)).toHaveText(t('en', 'error.AUTH-401-CREDENTIALS'));
    },
  );

  test(
    'the e-mail is matched without regard to case or spaces; the password exactly, case included',
    { tag: ['@SRS-G-35', '@edge'] },
    async ({ page }) => {
      await openLogin(page, 'en');
      await enterCredentials(page, 'en', USER, DEMO_PASSWORD.toUpperCase());
      await expect(errorAlert(page)).toHaveText(t('en', 'error.AUTH-401-CREDENTIALS'));
      await expect(loginOtpField(page, 'en')).toHaveCount(0);
      await openLogin(page, 'en');
      const since = Date.now();
      await enterCredentials(page, 'en', `  ${USER.toUpperCase()}  `);
      await expect(loginOtpField(page, 'en')).toBeVisible();
      await answerLoginOtp(page, 'en', since);
      await expect(page).toHaveURL(/\/payroll/);
    },
  );

  test(
    'the session cookie is Secure, HttpOnly and SameSite; signing in issues a new session id',
    { tag: ['@SRS-SEC-13', '@SRS-SEC-14', '@positive'] },
    async ({ page }) => {
      await openLogin(page, 'en');
      const before = (await page.context().cookies()).find((c) => c.name.includes('FCISESSION'))?.value;
      const since = Date.now();
      await enterCredentials(page, 'en', SIGNED);
      const pending = (await page.context().cookies()).find((c) => c.name.includes('FCISESSION'))?.value;
      await answerLoginOtp(page, 'en', since);
      await expect(page).toHaveURL(/\/payroll/);
      const session = (await page.context().cookies()).find((c) => c.name.includes('FCISESSION'));
      expect(session, 'a session cookie').toBeTruthy();
      // ASVS 3.4.1-3.4.3, WSTG-SESS-02: not readable by scripts, HTTPS only, not sent cross-site.
      expect(session?.httpOnly).toBe(true);
      expect(session?.secure).toBe(true);
      expect(['Strict', 'Lax']).toContain(session?.sameSite);
      // ASVS 3.2.1, WSTG-SESS-03 (fixation): a new id once signed in, neither the anonymous nor the
      // pending one.
      expect(session?.value).not.toBe(before);
      expect(session?.value).not.toBe(pending);
      // Nothing of the session lands in scripts' reach.
      expect(await page.evaluate(() => document.cookie)).not.toContain('FCISESSION');
    },
  );

  test(
    'hostile input (SQL, script, oversized) is refused cleanly: no server error, nothing reflected',
    { tag: ['@SRS-G-35', '@SRS-SEC-14', '@negative'] },
    async ({ page }) => {
      await openLogin(page, 'en');
      const statuses: number[] = [];
      page.on('response', (r) => {
        if (r.url().endsWith('/nginx/fci-payroll/auth/login')) statuses.push(r.status());
      });
      await page.getByRole('tab', { name: t('en', 'login.method.employee') }).click();
      const id = page.getByPlaceholder(t('en', 'login.employeeIdPlaceholder'));
      const password = page.getByPlaceholder(t('en', 'login.passwordPlaceholder'));
      const submit = page.getByRole('button', { name: t('en', 'login.submit'), exact: true });
      for (const hostile of ["' OR '1'='1' --", '<script>alert(1)</script>', 'x'.repeat(150)]) {
        await id.fill(hostile);
        await password.fill("' OR '1'='1");
        await submit.click();
        await expect(errorAlert(page)).toBeVisible();
        // Never echoed as markup, never a stack trace.
        await expect(page.locator('script:not([src])').filter({ hasText: 'alert(1)' })).toHaveCount(0);
        await expect(errorAlert(page)).not.toContainText(/exception|sql|ORA-|stack/i);
      }
      expect(statuses.length).toBeGreaterThan(0);
      expect(
        statuses.every((s) => s < 500),
        statuses.join(','),
      ).toBe(true);
      await expect(loginOtpField(page, 'en')).toHaveCount(0);
    },
  );

  test(
    'the login page cannot be framed or cached and sends the hardening headers',
    { tag: ['@SRS-SEC-14', '@positive'] },
    async ({ page }) => {
      const response = await page.goto('/');
      const headers = response?.headers() ?? {};
      // WSTG-CLNT-09 clickjacking; ASVS 14.4: no caching of a page that handles credentials.
      expect(headers['x-frame-options'] ?? headers['content-security-policy']).toMatch(
        /DENY|frame-ancestors 'none'/i,
      );
      expect(headers['cache-control']).toContain('no-store');
      expect(headers['content-security-policy']).toContain("default-src 'none'");
      expect(headers['x-content-type-options']).toBe('nosniff');
      expect(headers['referrer-policy']).toBe('no-referrer');
      expect(headers['strict-transport-security']).toContain('max-age=');
    },
  );

  test(
    'signing out ends the session: the cookie is gone and Back does not reopen the workspace',
    { tag: ['@SRS-SEC-14', '@SRS-G-48', '@positive'] },
    async ({ page }) => {
      await openLogin(page, 'en');
      const since = Date.now();
      await enterCredentials(page, 'en', SIGNED);
      await answerLoginOtp(page, 'en', since);
      await expect(page).toHaveURL(/\/payroll/);
      await page.locator('.me').click();
      await page.getByRole('menuitem', { name: t('en', 'account.signOut') }).click();
      await expect(page.getByRole('heading', { name: t('en', 'login.signIn') })).toBeVisible();
      expect((await page.request.get('/nginx/fci-payroll/auth/me')).status()).toBe(401);
      await page.goBack();
      await expect(page.getByRole('heading', { name: t('en', 'login.signIn') })).toBeVisible();
      await expect(page.locator('.rolebar b')).toHaveCount(0);
    },
  );
});
