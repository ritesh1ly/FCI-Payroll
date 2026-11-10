import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, type Page, test } from '@playwright/test';
import { sidebarCodes, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sqlRows } from '../support/db';

/**
 * Every role against every screen (SRS-22-1, SRS-22-8: roles are configuration; SRS-G-25: deny by
 * default; SRS-G-40: Hindi and English). For each role, one coverage user (seed V037_9,
 * docs/rbac/ROLE_COVERAGE.md) at an office its persona admits:
 *
 * - the sidebar shows exactly the screens the role holds VIEW on, read from app_role_permission at
 *   run time - the configuration is the oracle, so a grant changed on X-01 changes the expectation;
 * - every granted screen the UI has built opens in English and in Hindi with its own heading, no
 *   raw label key, no uncaught page error and no 5xx from the API;
 * - EVERY built screen the role does not hold, asked for by a typed URL, is refused by the Next.js
 *   server guard with a redirect to the dashboard (never to the sign-in page); five of them are
 *   also opened in the browser, which must land on the dashboard with the session intact (still
 *   signed in, same user and role) - "redirected" is told apart from "signed out";
 * - the API behind the screens is refused server side for every role x every gate in
 *   backend RbacGateMatrixIT (the UI is no protection: see security-tampering.spec.ts).
 *
 * Read-only: nothing is changed, so the spec runs on any stack with the coverage seed.
 */
const ROLES: [login: string, role: string, office: string | undefined][] = [
  ['pm.dh001', 'PAYROLL_MAKER', 'DH001'],
  ['pc.dh001', 'PAYROLL_CHECKER', 'DH001'],
  ['pc.hq001', 'PAYROLL_CHECKER', 'HQ001'],
  ['hr.dh001', 'ESTABLISHMENT_HR', 'DH001'],
  ['fin.dh001', 'FINANCE', 'DH001'],
  ['ldh.dpdl01', 'LABOUR_DEALING_HAND', 'DP-DL01'],
  ['dm.okhla', 'DEPOT_MANAGER', 'DP-DL01'],
  ['ucpf.dh001', 'UNIT_CPF_ADMIN', 'DH001'],
  ['cpfmgr.na11', 'CPF_MANAGER', 'NA11'],
  ['cpfdiv.na11', 'CPF_DIVISION', 'NA11'],
  ['agmcpf.na11', 'AGM_CPF', 'NA11'],
  ['cpfsa.na11', 'CPF_SANCTIONING_AUTHORITY', 'NA11'],
  ['hqcpf.hq001', 'HQ_CPF_APPROVER', 'HQ001'],
  ['rpt.na11', 'REPORT_ADMINISTRATOR', 'NA11'],
  ['su.hq001', 'SUPER_USER', 'HQ001'],
  ['ruleadm.hq001', 'RULE_ADMINISTRATOR', 'HQ001'],
  ['ruleapp.hq001', 'RULE_APPROVER', 'HQ001'],
  ['stat.hq001', 'STATUTORY_ADMINISTRATOR', 'HQ001'],
  ['pension.hq001', 'PENSION_DIVISION_HQ', 'HQ001'],
  ['aud.hq001', 'AUDITOR', 'HQ001'],
  ['sysadm.hq001', 'SYSTEM_ADMINISTRATOR', 'HQ001'],
  ['sysadm.dh001', 'SYSTEM_ADMINISTRATOR', 'DH001'],
  ['eseva.dh001', 'EMPLOYEE_ESEVA', undefined],
];

/** The screens the UI has built (shell/components/screenRegistry.tsx), read from the source. */
const BUILT: Set<string> = (() => {
  const here = dirname(fileURLToPath(import.meta.url));
  const registry = readFileSync(
    resolve(here, '..', '..', 'src', 'modules', 'shell', 'components', 'screenRegistry.tsx'),
    'utf8',
  );
  return new Set([...registry.matchAll(/'([A-Z]-\d{2})':/g)].map((m) => m[1] as string));
})();

/** The screens the role holds VIEW on today (the configuration is the oracle). */
function granted(role: string): string[] {
  return sqlRows(
    `SELECT DISTINCT p.screen_code FROM app_role_permission rp JOIN app_role r ON r.role_id = rp.role_id JOIN app_permission p ON p.permission_id = rp.permission_id WHERE r.role_code = '${role}' AND p.action_code = 'VIEW' AND rp.effective_from <= CURRENT_DATE AND (rp.effective_to IS NULL OR rp.effective_to >= CURRENT_DATE) ORDER BY 1`,
  ).map((r) => r[0] as string);
}

/** The menu entries of the pack (app_menu_item): the screens the sidebar can carry (OD-125). */
function drawn(): Set<string> {
  return new Set(
    sqlRows('SELECT DISTINCT screen_code FROM app_menu_item WHERE screen_code IS NOT NULL ORDER BY 1').map(
      (r) => r[0] as string,
    ),
  );
}

/** The code the sidebar prints for a built screen: the pack's, where they differ (WF-070). */
const PRINTED: Record<string, string> = { 'W-01': 'X-02', 'A-08': 'X-01', 'A-05': 'X-04' };

/** A label key left on the page instead of its text (the namespaces of the bundles). */
const RAW_KEY =
  /\b(screen|role|action|error|reason|status|common|gen|ord|mst|cyc|menu|top|page|x0\d|w0\d|a0\d|m\d{2}|i\d{2}|c\d{2}|e01s?|i04|i22|c12|x04|x05|x06|x07)\.[a-zA-Z][\w.]*\b/;

/** Opens one screen and checks it rendered as itself, in {@code locale}. */
async function opens(page: Page, code: string, locale: 'en' | 'hi', problems: string[]) {
  await page.goto(`/payroll/?s=${code}`);
  const title = t(locale, `screen.${code}`).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  await expect(
    page.getByRole('heading', { level: 1, name: new RegExp(title) }),
    `${code} ${locale}`,
  ).toBeVisible();
  const text = (await page.locator('main').innerText()).replace(/\S+@\S+/g, '');
  const raw = RAW_KEY.exec(text);
  if (raw) problems.push(`${code} ${locale}: raw label key ${raw[0]}`);
}

test.describe('Every role against every screen', () => {
  test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);

  const TAGS = [
    '@SRS-22-1',
    '@SRS-22-8',
    '@SRS-G-25',
    '@SRS-G-40',
    '@SC-02-0001',
    '@SC-02-0008',
    '@SC-01-0025',
    '@positive',
  ];

  /**
   * Each role's checks are three tests, each with its own sign-in (the menu with every screen in English; every screen in
   * Hindi; deny by default with the session intact): the same assertions as one long test, each well inside its time limit
   * on a loaded or parallel run, and no session has to live through all of them.
   */
  async function signedIn(
    page: Page,
    locale: 'en' | 'hi',
    login: string,
    role: string,
    office: string | undefined,
  ): Promise<{ problems: string[]; expected: string[]; first: string }> {
    const problems: string[] = [];
    page.on('pageerror', (e) => problems.push(`page error: ${e.message.slice(0, 160)}`));
    page.on('response', (r) => {
      if (r.url().includes('/nginx/fci-payroll/') && r.status() >= 500)
        problems.push(`${r.status()} ${r.url()}`);
    });
    const expected = granted(role);
    const first = expected.find((c) => BUILT.has(c));
    expect(first, `${role} holds a built screen`).toBeTruthy();
    await workAs(
      page,
      locale,
      `${login}@fci-demo.test`,
      office ? { role, office } : { role },
      first as string,
    );
    return { problems, expected, first: first as string };
  }

  // The roles run side by side (each has its own coverage login); a role's three tests stay together in one worker,
  // one after the other, since they share that login's session.
  test.describe.configure({ mode: 'parallel' });

  for (const [login, role, office] of ROLES) {
    const who = `${role}${office ? ` at ${office}` : ''}`;
    test.describe(who, () => {
      test.describe.configure({ mode: 'serial' });

      test(
        `${who}: the menu is its grants; each granted screen opens in English`,
        { tag: TAGS },
        async ({ page }) => {
          test.setTimeout(300_000);
          const { problems, expected } = await signedIn(page, 'en', login, role, office);
          // The menu is the configuration: nothing more, nothing less, of what the pack draws. A held
          // screen with no drawn entry (WF-204: W-02, A-01 ...) stays out of the sidebar and opens by link.
          const entries = drawn();
          expect(new Set(await sidebarCodes(page))).toEqual(
            new Set(expected.filter((c) => entries.has(c)).map((c) => PRINTED[c] ?? c)),
          );
          for (const code of expected.filter((c) => BUILT.has(c))) await opens(page, code, 'en', problems);
          expect(problems, 'problems on the screens').toEqual([]);
        },
      );

      test(`${who}: each granted screen opens in Hindi`, { tag: TAGS }, async ({ page }) => {
        test.setTimeout(300_000);
        const { problems, expected } = await signedIn(page, 'hi', login, role, office);
        for (const code of expected.filter((c) => BUILT.has(c))) await opens(page, code, 'hi', problems);
        expect(problems, 'problems on the screens').toEqual([]);
      });

      test(`${who}: the rest is refused and the session stays the same`, { tag: TAGS }, async ({ page }) => {
        test.setTimeout(300_000);
        const { problems, expected } = await signedIn(page, 'en', login, role, office);
        // Deny by default: a screen the role does not hold, typed into the URL, is not opened.
        const others = [...BUILT].filter((c) => !expected.includes(c)).sort();
        const me = async () => {
          const r = await page.request.get('/nginx/fci-payroll/auth/me');
          expect(r.status(), `${role} still signed in`).toBe(200);
          return (await r.json()) as { loginId: string; role: string | null };
        };
        const before = await me();
        // Every one through the server guard: a redirect to the dashboard, not to the sign-in page.
        for (const code of others) {
          // The pages' own path (base path /nginx), so the first answer is the guard's, not NGINX's.
          const r = await page.request.get(`/nginx/payroll?s=${code}`, { maxRedirects: 0 });
          expect(r.status(), `${role} ${code}`).toBeGreaterThanOrEqual(300);
          expect(r.status(), `${role} ${code}`).toBeLessThan(400);
          expect(r.headers().location, `${role} must not open ${code}`).toMatch(/\/payroll\/?$/);
        }
        // Five of them in the browser: the dashboard renders, and the session is the same one.
        const sample = others
          .filter((_, i) => i % Math.max(1, Math.floor(others.length / 5)) === 0)
          .slice(0, 5);
        for (const code of sample) {
          await page.goto(`/payroll/?s=${code}`);
          await expect(page, `${role} must not open ${code}`).toHaveURL(/\/payroll\/?$/);
          await expect(page.locator('.rolebar b'), `${role} still in the workspace`).toBeVisible();
          await expect(page.getByRole('heading', { level: 1, name: t('en', 'dash.title') })).toBeVisible();
        }
        const after = await me();
        expect(after.loginId).toBe(before.loginId);
        expect(after.role).toBe(before.role);
        expect(problems, 'problems on the screens').toEqual([]);
      });
    });
  }
});
