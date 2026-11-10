import { type BrowserContext, expect, type Locator, type Page } from '@playwright/test';
import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { messages } from '../../src/generated/messages';
import { guardConsole } from './console-guard';
import { laneLogin, ownOtpLines, readLogTail } from './parallel-users';

/** The one password of every seeded demo user (docs/rbac/SEED_USERS.md). */
export const DEMO_PASSWORD = 'Fci@Demo2026';

export type Locale = 'en' | 'hi';
/** A scope of the top-bar selectors: an acting role and, except for Employee (eSeva), an office code. */
export interface Scope {
  role: string;
  office?: string;
}

/** A label from the same bundle the UI uses (so en and hi runs assert the same things). */
export function t(locale: Locale, key: string, args: unknown[] = []): string {
  const bundle = messages[locale] as Record<string, string>;
  const template = bundle[key];
  if (template === undefined) throw new Error(`no label ${key}`);
  return template.replace(/\{(\d+)\}/g, (_, i: string) => String(args[Number(i)]));
}

/**
 * The sign-in page's URL: the pages' base path (/nginx, next.config.ts; the root on a build with
 * FCI_BASE_PATH=/), with or without its trailing slash.
 */
export const SIGN_IN_PAGE = /(?:\/|\/nginx|\/nginx\/)$/;

/**
 * A link to a page route as the screens render it: under the pages' base path (/nginx; nothing on a
 * build at the root). Matches the whole href.
 */
export function pageHref(path: string): RegExp {
  const literal = path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`^(?:/nginx)?${literal}$`);
}

export async function openLogin(page: Page, locale: Locale) {
  guardConsole(page);
  await page.goto('/');
  await expect(
    page
      .getByRole('heading', { name: t('en', 'login.signIn') })
      .or(page.getByRole('heading', { name: t('hi', 'login.signIn') })),
  ).toBeVisible();
  const toggle = page
    .getByRole('group', { name: t('en', 'lang.toggle') })
    .or(page.getByRole('group', { name: t('hi', 'lang.toggle') }));
  await toggle.getByRole('button', { name: t(locale, `lang.${locale}`) }).click();
  await expect(page.getByRole('heading', { name: t(locale, 'login.signIn') })).toBeVisible();
}

/** The sign-in OTP field of the login card (X-08 step 2, SRS-G-48). */
export function loginOtpField(page: Page, locale: Locale) {
  return page.getByRole('textbox', { name: t(locale, 'login.otp.code'), exact: true });
}

/** How the login card names the account (OD-37, OD-41). */
export type SignInMethod = 'email' | 'employee' | 'mobile';

/**
 * Fills the login card and presses its button: the Email tab (the default) or the Employee ID tab
 * with the password, or "Sign in with OTP on registered mobile" with the number alone and Send OTP
 * (OD-41: no password there). Nothing else is chosen (no persona, no location).
 */
export async function enterCredentials(
  page: Page,
  locale: Locale,
  loginId: string,
  password = DEMO_PASSWORD,
  method: SignInMethod = 'email',
) {
  await expect(page.getByRole('group', { name: t(locale, 'login.signInAs') })).toHaveCount(0);
  await expect(page.getByLabel(t(locale, 'login.location'))).toHaveCount(0);
  if (method === 'employee')
    await page.getByRole('tab', { name: t(locale, 'login.method.employee') }).click();
  if (method === 'mobile') await page.getByRole('button', { name: t(locale, 'login.otpOption') }).click();
  const field = {
    email: page.getByPlaceholder(t(locale, 'login.emailPlaceholder')),
    employee: page.getByPlaceholder(t(locale, 'login.employeeIdPlaceholder')),
    mobile: page.getByLabel(t(locale, 'login.mobile')),
  }[method];
  await field.fill(method === 'email' ? laneLogin(loginId) : loginId);
  if (method !== 'mobile') await page.getByPlaceholder(t(locale, 'login.passwordPlaceholder')).fill(password);
  const submit = t(locale, method === 'mobile' ? 'login.sendOtp' : 'login.submit');
  await page.getByRole('button', { name: submit, exact: true }).click();
}

/**
 * Signs in through the live login page: the user ID and the password, then (SRS-G-48, OD-36) the
 * OTP sent to the registered mobile, read from the API log as the user reads it from the SMS. The
 * API allows 10 sign-ins per IP and 5 per user per minute (ADR-0008); if the limiter answers, the
 * helper waits out the window and tries again, so a long suite never fails on its own pace.
 */
export async function signIn(page: Page, locale: Locale, loginId: string, password = DEMO_PASSWORD) {
  for (let attempt = 0; attempt < 3; attempt++) {
    await openLogin(page, locale);
    const since = Date.now();
    await enterCredentials(page, locale, loginId, password);
    const limited = errorAlert(page).filter({ hasText: t(locale, 'error.COM-429-RATE-LIMIT') });
    const outcome = await Promise.race([
      page.waitForURL(/\/payroll/, { timeout: 15_000 }).then(() => 'in' as const),
      loginOtpField(page, locale)
        .waitFor({ timeout: 15_000 })
        .then(() => 'otp' as const),
      limited.waitFor({ timeout: 15_000 }).then(() => 'limited' as const),
      errorAlert(page)
        .waitFor({ timeout: 15_000 })
        .then(() => 'refused' as const),
    ]).catch(() => 'refused' as const);
    if (outcome === 'otp') {
      await answerLoginOtp(page, locale, since);
      // A refusal after the code (an account locked or deactivated meanwhile) stays on the card.
      await Promise.race([
        page.waitForURL(/\/payroll/, { timeout: 15_000 }),
        errorAlert(page).waitFor({ timeout: 15_000 }),
      ]);
    }
    if (outcome === 'in' || outcome === 'otp') await requireOtpVerification(page);
    if (outcome !== 'limited' || (await limited.count()) === 0) return;
    await page.waitForTimeout(61_000);
  }
}

/** Answers the OTP step with the newest sign-in code of the log written after {@code since}. */
export async function answerLoginOtp(page: Page, locale: Locale, since: number) {
  await loginOtpField(page, locale).fill(await otpFromLog('AUTH_LOGIN', since));
  await page.getByRole('button', { name: t(locale, 'login.otp.verify'), exact: true }).click();
}

/**
 * The suite proves the OTP of the critical activities (SRS-G-21), so it runs only against a stack
 * that asks for it: fci.api.otp.required = Y. A local stack defaults to N (project owner,
 * 2026-10-23); deploy it with `deploy-local.ps1 -OtpRequired Y` for an E2E run.
 */
async function requireOtpVerification(page: Page) {
  if (!/\/payroll/.test(page.url())) return;
  const required = await page.evaluate(async () => {
    const response = await fetch('/nginx/fci-payroll/auth/me', { credentials: 'same-origin' });
    return response.ok ? ((await response.json()) as { otpRequired?: boolean }).otpRequired : undefined;
  });
  if (required === false) {
    throw new Error(
      'This stack runs with fci.api.otp.required=N: redeploy it with deploy-local.ps1 -OtpRequired Y to run the E2E suite.',
    );
  }
}

/**
 * Session cookies of each signed-in demo user, reused across tests (one sign-in per user per run).
 * Keyed by user AND browser User-Agent: the backend binds a session to the browser that signed in
 * (hijack detection, SessionPolicyFilter), so a cookie from a page opened with another User-Agent
 * (`browser.newPage()` does not take the project's device) would end the session when reused.
 */
const sessions = new Map<string, Awaited<ReturnType<BrowserContext['cookies']>>>();

/** The workspace for {@code loginId}: the cached session if it is still open, else a fresh sign-in. */
/**
 * Opens the workspace as {@code loginId} on its landing screen (the session reused while open, so
 * a long suite signs each demo user in once: the OTP cap is 20 an hour per user); no scope switch.
 */
export async function openWorkspaceAs(page: Page, locale: Locale, loginId: string) {
  await openWorkspace(page, locale, loginId);
}

async function openWorkspace(page: Page, locale: Locale, loginId: string) {
  // A page may already hold another user's session: start from no cookies at all.
  await page.context().clearCookies();
  const key = await sessionKey(page, loginId);
  const cached = sessions.get(key);
  if (cached) {
    await page.context().addCookies(cached);
    // Asked of the server, not read from the URL: /payroll/ renders before the workspace's own
    // /auth/me answers, so a closed session used to pass a URL check and the test then ended on the
    // sign-in page when the workspace's 401 redirect came (role-screen-matrix flake, 2026-11-02).
    const alive = await page.request.get('/nginx/fci-payroll/auth/me');
    if (alive.ok()) {
      const since = Date.now();
      await page.goto('/payroll/');
      await expect(page).toHaveURL(/\/payroll/);
      // The confirmation lasts STEP_UP_VALIDITY: a long run is asked again (the id rotates).
      await answerStepUp(page, since);
      await remember(page, key);
      return;
    }
    sessions.delete(key);
    await page.context().clearCookies();
  }
  const since = Date.now();
  await signIn(page, locale, loginId);
  await expect(page).toHaveURL(/\/payroll/);
  await answerStepUp(page, since);
  await remember(page, key);
}

async function sessionKey(page: Page, loginId: string) {
  return `${loginId}|${await page.evaluate(() => navigator.userAgent)}`;
}

/**
 * Keeps the session's CURRENT cookies. The session id rotates on a step-up and on every scope switch
 * (MeController: changeSessionId), so the cookies must be taken again after each: a cookie kept from
 * before a switch names a session that no longer exists.
 */
async function remember(page: Page, key: string) {
  sessions.set(
    key,
    (await page.context().cookies()).filter((c) => c.name !== 'FCI_LOCALE'),
  );
}

/** The OTP dialog of the workspace (step-up, role switch), in either language. */
function otpCodeField(page: Page) {
  return page.getByRole('textbox', { name: new RegExp(`^(${t('en', 'otp.code')}|${t('hi', 'otp.code')})$`) });
}

/**
 * A sensitive role asks the user's OTP when the workspace opens (SRS-G-48, Module 02 D-2): when
 * /auth/me says a step-up is due, wait for the dialog and answer it with the newest AUTH_LOGIN code
 * of the log, as the user would with the code from their e-mail. Nothing to do otherwise.
 */
export async function answerStepUp(page: Page, since: number) {
  const me = await page.request.get('/nginx/fci-payroll/auth/me');
  if (!me.ok() || !((await me.json()) as { stepUpRequired?: boolean }).stepUpRequired) return;
  const field = otpCodeField(page);
  // The demo OTP cap is 20 an hour per user (fci.api.otp.max-per-hour, @Max 20): re-running the whole
  // suite within the hour can exhaust it for the shared users. Say so instead of timing out.
  const capped = page.getByText(t('en', 'error.COM-429-RATE-LIMIT')).first();
  await expect(field.or(capped)).toBeVisible({ timeout: 15_000 });
  if (await capped.isVisible()) {
    throw new Error('OTP issuance cap reached for this user: wait an hour or redeploy the stack');
  }
  await field.fill(await otpFromLog('AUTH_LOGIN', since));
  await page
    .getByRole('button', { name: new RegExp(`^(${t('en', 'otp.verify')}|${t('hi', 'otp.verify')})$`) })
    .click();
  await expect(field).toHaveCount(0);
}

/** Shows the workspace in {@code locale} (the EN | हिं toggle of the top bar, as a user does). */
async function chooseLocale(page: Page, locale: Locale) {
  // The signed-in workspace adopts the user's stored language (/auth/me) just after it renders, so a
  // click on the EN | हिं toggle can race it, and on a touch layout the toggle may be covered. Store
  // the preference through the API the toggle itself calls (PUT /auth/locale, CSRF double-submit)
  // and reload: deterministic whatever an earlier test stored and whatever the viewport. The toggle
  // as such is covered by the login-page tests (openLogin) and 01-sign-in-and-access/personas-and-menus.spec.ts.
  // Always store it: before the stored language is adopted the page shows the default, so the
  // page itself cannot tell whether a change is needed.
  const csrf = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')?.value ?? '';
  const stored = await page.request.put('/nginx/fci-payroll/auth/locale', {
    data: { locale },
    headers: { 'X-XSRF-TOKEN': csrf },
  });
  expect(stored.status()).toBe(204);
  await page.reload();
  await expect(page.locator('.rolebar b')).toBeAttached();
  await expect(page.locator('html')).toHaveAttribute('lang', locale);
}

/** The message of a refused sign-in or action (not Next.js's route announcer, also role=alert). */
export function errorAlert(page: Page) {
  return page.locator('.fl-error[role="alert"]');
}

/**
 * Switches the workspace to an office and acting role in the top bar (no sign-out), as the user
 * does; waits until the sidebar's role bar shows the new role.
 */
export async function switchScope(page: Page, locale: Locale, scope: Scope) {
  const office = page.getByLabel(t(locale, 'top.office'), { exact: true });
  const role = page.getByLabel(t(locale, 'top.role'), { exact: true });
  await expect(role).toBeVisible();
  // The selectors are filled once /auth/scopes has answered; before that they read ''.
  await expect(role).not.toHaveValue('');
  if (scope.office && (await office.inputValue()) !== scope.office) {
    await office.selectOption(scope.office);
    await expect(office).toHaveValue(scope.office);
  }
  const key = scope.office ? `${scope.role}@${scope.office}` : scope.role;
  if ((await role.inputValue()) !== key) {
    const since = Date.now();
    await role.selectOption(key);
    // Another role asks the OTP unless one was confirmed recently (SRS-G-48, Module 02 D-2).
    const field = otpCodeField(page);
    const asked = await field
      .waitFor({ state: 'visible', timeout: 3000 })
      .then(() => true)
      .catch(() => false);
    if (asked) {
      await field.fill(await otpFromLog('AUTH_LOGIN', since));
      await page
        .getByRole('button', { name: new RegExp(`^(${t('en', 'otp.verify')}|${t('hi', 'otp.verify')})$`) })
        .click();
    }
  }
  await expect(role).toHaveValue(key);
  await expect(page.locator('.rolebar b')).toContainText(t(locale, `role.${scope.role}`));
}

/**
 * Opens the workspace as {@code loginId} (its session reused when still open), in {@code locale},
 * switches to the scope and opens a screen of it.
 */
export async function workAs(page: Page, locale: Locale, loginId: string, scope: Scope, screen: string) {
  await openWorkspace(page, locale, loginId);
  // The workspace applies the stored language once /auth/me has answered: choose after that.
  await expect(page.locator('.rolebar b')).toBeVisible();
  await chooseLocale(page, locale);
  await expect(page.locator('html')).toHaveAttribute('lang', locale);
  await switchScope(page, locale, scope);
  // The switch rotated the session id: keep the new one for the next workAs of this user.
  await remember(page, await sessionKey(page, loginId));
  await page.goto(`/payroll/?s=${screen}`);
  await expect(
    page.getByRole('heading', {
      level: 1,
      name: new RegExp(t(locale, `screen.${screen}`).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')),
    }),
  ).toBeVisible({ timeout: 15_000 });
  // M-02, M-05, M-06, M-09 keep every entry function in a closed register under the drawn screen (OD-121):
  // these specs drive those functions, so they open it (<details> with its summary).
  const register = LABOUR_REGISTERS[screen];
  if (register) {
    // I-03 stands open for the Labour Dealing Hand: a second click would close it.
    const section = page.getByTestId(register);
    await section.waitFor();
    if ((await section.getAttribute('open')) === null) {
      await page.locator(`[data-testid="${register}"] > summary`).click();
    }
  }
  // X-04 keeps dates and the free-text filters behind the drawn Filter button.
  if (screen === 'A-05') {
    await page.getByRole('button', { name: t(locale, 'x04w.filter'), exact: true }).click();
  }
  // E-01 keeps every undrawn function in a closed section under the drawn screen (OD-121): these specs
  // drive those functions, so they open them. Each stays in the page, so its position stays stable.
  if (screen === 'E-01') {
    const sections = page.locator('[data-testid^="e01-section-"] > summary');
    await expect(sections.first()).toBeVisible();
    const count = await sections.count();
    for (let i = 0; i < count; i += 1) await sections.nth(i).click();
  }
}

const LABOUR_REGISTERS: Record<string, string> = {
  'M-02': 'm02-register',
  'M-08': 'm08-register',
  'M-16': 'm16-other-lists',
  'M-05': 'm05-register',
  'M-06': 'm06-register',
  'M-09': 'm09-register',
  'I-04': 'i04-register',
  'I-05': 'i05-register',
  'I-06': 'i06-register',
  'I-14': 'i14-register',
  'I-09': 'i09-register',
  'I-22': 'i22-register',
  'M-13': 'm13-register',
  'M-15': 'm15-register',
  'M-20': 'm20-register',
  'I-03': 'i03-register',
  'I-11': 'i11-register',
  'I-16': 'i16-register',
  'M-18': 'm18-register',
  'M-19': 'm19-register',
  'C-12': 'c12-actions',
  'I-07': 'i07-register',
  'I-18': 'i18-register',
  'M-24': 'm24-register',
  'M-17': 'm17-register',
  'C-10': 'c10-register',
  'C-11': 'c11-register',
  'C-07': 'c07-member',
};

/**
 * The M-01 list by its name: the drawn screen has several tables (Member ID and Staff Number
 * histories, pay status, orders), so "the first" or "the last" table is not the list (OD-121).
 */
export function employeeList(page: Page, locale: Locale = 'en'): Locator {
  return page.getByRole('table', { name: t(locale, 'screen.M-01'), exact: true });
}

/**
 * The value rows of the list chosen in M-16's closed "other lists" section (#m16-list): the drawn list cards above it
 * hold tables of their own, so "the first table row" of the page is not this list (OD-121). The code is in td code.
 */
export function m16OtherListRows(page: Page): Locator {
  return page.getByTestId('m16-other-lists').locator('tbody tr');
}

/** The data rows of a kit table: its pager and its empty text are rows of the table too. */
export function dataRows(table: Locator): Locator {
  return table.locator('tbody tr:not(.fl-state-row)');
}

export async function sidebarCodes(page: Page): Promise<string[]> {
  const nav = page.locator('aside');
  return (await nav.locator('.nav .tag').allTextContents()).map((s) => s.trim());
}

/**
 * Opens the screen's Export dialog (SRS-G-13), picks {@code format} and returns its download link;
 * the dialog stays open for the caller to assert or click.
 */
export async function exportLink(
  page: Page,
  format: 'CSV' | 'XLSX',
  label?: string,
  scope: Page | Locator = page,
) {
  // Whole name: other screens carry longer names with the word; the drawn button has its glyph.
  await scope
    .getByRole('button', { name: label ?? new RegExp(`^(⇩ )?${t('en', 'action.export')}$`) })
    .first()
    .click();
  const dialog = page.getByRole('dialog');
  await dialog.locator(`input[value="${format}"]`).check();
  return dialog.getByRole('link', { name: t('en', 'export.download') });
}

/** Tokens and data never land in browser storage (ADR-0010 §6). */
export async function expectNoBrowserStorage(page: Page) {
  const sizes = await page.evaluate(() => [window.localStorage.length, window.sessionStorage.length]);
  expect(sizes).toEqual([0, 0]);
}

/**
 * OTPs of the demo profile are written to the fci-api log (fci.api.notification.log-codes, refused
 * in prod). Reads the newest code of a gated action (otp_challenge.gated_action) written after {@code since}.
 */
const usedCodes = new Set<string>();

export async function otpFromLog(purpose: string, since: number): Promise<string> {
  const log = latestApiLog();
  for (let attempt = 0; attempt < 20; attempt++) {
    const lines = ownOtpLines(
      readLogTail(log)
        .split('\n')
        .filter((l) => l.includes(`purpose=${purpose}`) && l.includes('devCode=')),
    );
    for (const line of lines.reverse()) {
      const at = /"@timestamp":"([^"]+)"/.exec(line)?.[1];
      const code = /devCode=(\d{6})/.exec(line)?.[1];
      // Never a code already used in this run: two users of one gated action can follow each other
      // within a second (the maker's lock request, then the checker's approval).
      if (code && !usedCodes.has(code) && (!at || Date.parse(at) >= since - 200)) {
        usedCodes.add(code);
        return code;
      }
    }
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error(`no ${purpose} OTP in ${log}`);
}

function latestApiLog(): string {
  if (process.env.E2E_API_LOG) return process.env.E2E_API_LOG;
  // ES module: no __dirname.
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
  // scripts/local/deploy-local.ps1: the online Tomcat instance writes runtime/logs/online/api.json;
  // from source (./gradlew :backend:bootRunOnline) it is backend/build/local/logs/online/api.json.
  const deployed = resolve(root, 'scripts', 'local', 'runtime', 'logs', 'online', 'api.json');
  const fromSource = resolve(root, 'backend', 'build', 'local', 'logs', 'online', 'api.json');
  return existsSync(deployed) ? deployed : fromSource;
}

/** The status of a request the signed-in page makes itself (forged calls, as an attacker would). */
export async function apiStatus(page: Page, path: string, method = 'GET', body?: unknown): Promise<number> {
  return page.evaluate(
    async ([p, m, b]) => {
      const token =
        document.cookie
          .split('; ')
          .find((c) => c.startsWith('XSRF-TOKEN='))
          ?.split('=')[1] ?? '';
      const response = await fetch(`/nginx/fci-payroll${p}`, {
        method: m as string,
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': token,
          'Idempotency-Key': crypto.randomUUID(),
        },
        body: b === undefined ? undefined : JSON.stringify(b),
      });
      return response.status;
    },
    [path, method, body] as const,
  );
}

/**
 * As {@link apiStatus}, with the problem's error code (null on success): a forged call that must be
 * refused for a named reason, e.g. `{ status: 409, errorCode: 'QRY-409-STATUS' }`.
 */
export async function apiProblem(
  page: Page,
  path: string,
  method = 'GET',
  body?: unknown,
): Promise<{ status: number; errorCode: string | null }> {
  return page.evaluate(
    async ([p, m, b]) => {
      const token =
        document.cookie
          .split('; ')
          .find((c) => c.startsWith('XSRF-TOKEN='))
          ?.split('=')[1] ?? '';
      const response = await fetch(`/nginx/fci-payroll${p}`, {
        method: m as string,
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': token,
          'Idempotency-Key': crypto.randomUUID(),
        },
        body: b === undefined ? undefined : JSON.stringify(b),
      });
      const problem = (await response.json().catch(() => ({}))) as { errorCode?: string };
      return { status: response.status, errorCode: response.ok ? null : (problem.errorCode ?? null) };
    },
    [path, method, body] as const,
  );
}

/**
 * Lists open page by page (SRS-NF-3): shows the page holding {@code target}, moving with Next from
 * the first page (100 rows a page, so few moves), and returns it. The first visible pager is used.
 */
export async function onSomePage(page: Page, target: Locator): Promise<Locator> {
  // Visible within a short wait (the page may still be loading); never fails by itself.
  const seen = () =>
    target
      .waitFor({ state: 'visible', timeout: 3_000 })
      .then(() => true)
      .catch(() => false);
  if (await seen()) return target;
  const pager = page.getByRole('navigation', { name: t('en', 'page.nav') }).first();
  if ((await pager.count()) === 0) return target;
  const size = pager.getByLabel(t('en', 'page.size'));
  if ((await size.inputValue()) !== '100') {
    await size.selectOption('100');
    if (await seen()) return target;
  }
  for (let i = 0; i < 50; i++) {
    const next = pager.getByRole('button', { name: t('en', 'page.next'), exact: true });
    if (await next.isDisabled()) break;
    await next.click();
    if (await seen()) return target;
  }
  return target;
}

/**
 * Decides an item in the W-01 Approval Inbox: the row whose text matches {@code row}, the decision
 * button, the reason dialog (reason required except to approve) and, if the action is OTP-gated,
 * the one-time password from the log.
 */
/**
 * SRS-G-48: a sensitive role's OTP confirmation lasts STEP_UP_VALIDITY. A long run reuses a session signed in earlier,
 * so the confirmation can lapse between opening a screen and deciding on it; the app then asks the OTP again on the
 * next refused call and that prompt, not the item's detail, is the first dialog (full run 2026-11-08: no Approve or
 * Reject in it). Before a decision, ask the server; when it is due, reload and answer the workspace's own prompt with
 * the real code, as at sign-in. Returns whether the page was reloaded.
 */
export async function renewStepUpIfDue(page: Page): Promise<boolean> {
  const me = await page.request.get('/nginx/fci-payroll/auth/me');
  if (!me.ok() || !((await me.json()) as { stepUpRequired?: boolean }).stepUpRequired) return false;
  const since = Date.now();
  await page.reload();
  await answerStepUp(page, since);
  return true;
}

export async function decide(
  page: Page,
  row: RegExp,
  decision: 'APPROVE' | 'REJECT' | 'RETURN',
  reason = '',
  otpPurpose?: string,
  /** The item's status after the decision: an approval that only advances a multi-level route leaves it PENDING_APPROVAL. */
  outcome?: string,
) {
  await renewStepUpIfDue(page);
  const item = await onSomePage(page, page.getByRole('row', { name: row }).first());
  // As drawn (OD-121): View opens the item's detail, which carries Approve / Return / Reject; the
  // reason dialog then opens over it.
  await item.getByRole('button', { name: t('en', 'wf.view') }).click();
  const detail = page.getByRole('dialog').first();
  await detail.getByRole('button', { name: t('en', `w01.${decision}`) }).click();
  const dialog = page.getByRole('dialog').last();
  if (reason) await dialog.getByRole('textbox').fill(reason);
  const since = Date.now();
  await dialog.getByRole('button', { name: t('en', `w01.${decision}`) }).click();
  if (otpPurpose) {
    await page.getByRole('textbox', { name: t('en', 'otp.code') }).fill(await otpFromLog(otpPurpose, since));
    await page.getByRole('button', { name: t('en', 'otp.verify') }).click();
  }
  const status =
    outcome ?? (decision === 'APPROVE' ? 'APPROVED' : decision === 'REJECT' ? 'REJECTED' : 'RETURNED');
  await expect(page.getByRole('status')).toContainText(t('en', `status.${status}`));
}

/**
 * A W-01 decision as drawn (OD-121): View on the row opens the item's detail, which carries the
 * three decisions; the reason dialog opens over it and is returned. Cancelling leaves the detail open.
 */
export async function pressDecision(
  page: Page,
  row: Locator,
  decision: 'APPROVE' | 'REJECT' | 'RETURN',
): Promise<Locator> {
  // A reload goes back to the inbox's first page: find the row again where it now is.
  const target = (await renewStepUpIfDue(page)) ? await onSomePage(page, row) : row;
  await target.getByRole('button', { name: t('en', 'wf.view') }).click();
  await page
    .getByRole('dialog')
    .first()
    .getByRole('button', { name: t('en', `w01.${decision}`) })
    .click();
  return page.getByRole('dialog').last();
}

/** Closes the W-01 detail a refused or cancelled decision leaves open. */
export async function closeDetail(page: Page) {
  await page
    .getByRole('dialog')
    .first()
    .getByRole('button', { name: t('en', 'common.close') })
    .click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
}

/** The payroll period (YYYY-MM) the signed-in session works on, from /auth/me. */
export async function sessionPeriod(page: Page): Promise<string> {
  return page.evaluate(async () => {
    const r = await fetch('/nginx/fci-payroll/auth/me', { credentials: 'same-origin' });
    return ((await r.json()) as { period: string }).period;
  });
}

/** `period` (YYYY-MM) moved by `months` (negative = back-dated). */
export function shiftPeriod(period: string, months: number): string {
  const [year, month] = period.split('-').map(Number) as [number, number];
  const index = year * 12 + (month - 1) + months;
  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, '0')}`;
}

/** The top-bar label of a period: "OCT 2026". */
export function periodText(period: string): string {
  const names = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];
  const [year, month] = period.split('-');
  return `${names[Number(month) - 1]} ${year}`;
}

/**
 * A forged multipart request from the signed-in page (as the screens upload files): the fields,
 * one file, CSRF and idempotency headers. Returns the HTTP status and the problem's errorCode.
 */
export async function apiMultipart(
  page: Page,
  path: string,
  fields: Record<string, string>,
  file: { name: string; mimeType: string; content: string },
): Promise<{ status: number; errorCode: string | null }> {
  return page.evaluate(
    async ([p, f, file]) => {
      const token =
        document.cookie
          .split('; ')
          .find((c) => c.startsWith('XSRF-TOKEN='))
          ?.split('=')[1] ?? '';
      const form = new FormData();
      for (const [k, v] of Object.entries(f)) form.append(k, v);
      form.append('file', new Blob([file.content], { type: file.mimeType }), file.name);
      const response = await fetch(`/nginx/fci-payroll${p}`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'X-XSRF-TOKEN': token, 'Idempotency-Key': crypto.randomUUID() },
        body: form,
      });
      const body = (await response.json().catch(() => ({}))) as { errorCode?: string };
      return { status: response.status, errorCode: body.errorCode ?? null };
    },
    [path, fields, file] as const,
  );
}

/**
 * Today in the business time zone (IST, UTC+5:30), as the server dates effective dates and orders.
 * `new Date().toISOString()` is the UTC date: between 00:00 and 05:30 IST it is still yesterday, so
 * a date "from today" computed that way is refused as back-dated (seen 2026-10-25 at 00:57 IST).
 */
export function businessToday(offsetDays = 0): string {
  return new Date(Date.now() + 5.5 * 3_600_000 + offsetDays * 86_400_000).toISOString().slice(0, 10);
}

/** The financial year of a business day (YYYY-MM-DD), April to March, as the server writes it: 2026-27. */
export function finYearOf(day: string): string {
  const [y, m] = day.split('-').map(Number) as [number, number];
  const start = m >= 4 ? y : y - 1;
  return `${start}-${String((start + 1) % 100).padStart(2, '0')}`;
}

/** The financial year before the current one (the demo history year, V039_9_1). */
export function previousFinYear(): string {
  const start = Number(finYearOf(businessToday()).slice(0, 4)) - 1;
  return `${start}-${String((start + 1) % 100).padStart(2, '0')}`;
}

/**
 * A payroll month YYYYMM as a month picker takes it (`<input type="month">`, YYYY-MM): every
 * payroll-month field is a month picker since the UI audit of 2026-11-02, and Playwright's fill
 * refuses any other value for one.
 */
export function pickerMonth(yymm: string): string {
  return `${yymm.slice(0, 4)}-${yymm.slice(4, 6)}`;
}
