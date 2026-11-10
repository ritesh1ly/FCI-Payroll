import { defineConfig, devices } from '@playwright/test';
import { createRequire } from 'node:module';
import { parallelWorkers } from './e2e/lanes';

/**
 * End-to-end tests against the real stack with the demo seed (profiles uat,demo): normally the
 * NGINX-served build of scripts/local/deploy-local.ps1 (E2E_BASE_URL=https://localhost:8443, or 8643
 * with -PortOffset 200). Several stacks in parallel: scripts/e2e-parallel.mjs (npm run e2e:parallel).
 * Without E2E_BASE_URL this config starts `next dev`, which proxies /api to the backend (online profile) on :8180.
 * Each persona runs in English and in Hindi (e2e/01-sign-in-and-access/personas-and-menus.spec.ts).
 * Browsers: the whole suite on Microsoft Edge (project `edge`, the default browser at FCI); a smoke
 * set on Chrome, Firefox, Safari (WebKit) and on iPhone / Android emulation. `npx playwright test`
 * runs all of them; `--project edge` the whole suite alone. Firefox and WebKit once need
 * `npx playwright install firefox webkit`.
 */
const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:3000';

/**
 * Cross-browser smoke set (owner 2026-10-29: Chrome, Safari, Firefox ... besides Edge): the specs
 * that exercise the browser itself rather than the business rules, which are browser-independent.
 */
const SMOKE = [
  '**/01-sign-in-and-access/login-otp.spec.ts',
  '**/01-sign-in-and-access/personas-and-menus.spec.ts',
  '**/02-platform/phone-layout.spec.ts',
  '**/02-platform/tablet-layout.spec.ts',
];
/**
 * The persona menus are driven through the desktop sidebar; on a phone the same menu sits in the
 * drawer, which phone-layout.spec.ts drives instead.
 */
const PHONE_SMOKE = SMOKE.filter((spec) => !spec.includes('personas-and-menus'));
/** Desktop Chrome, Firefox and Safari (WebKit), and Safari / Chrome on phones. */
const CROSS_BROWSER: [string, string, string[]][] = [
  ['chrome', 'Desktop Chrome', SMOKE],
  ['firefox', 'Desktop Firefox', SMOKE],
  ['webkit', 'Desktop Safari', SMOKE],
  ['mobile-safari', 'iPhone 13', PHONE_SMOKE],
  ['mobile-chrome', 'Pixel 7', PHONE_SMOKE],
];

const EDGE = { ...devices['Desktop Edge'], channel: 'msedge', userAgent: undefined };

/** The suites of e2e/suites.json: read only for a suite run, so `--project edge` never depends on it. */
function suites(): { name: string; specs: string[]; lane: string; identity: string }[] {
  return createRequire(import.meta.url)('./e2e/suites.json').suites;
}

/**
 * The suites (e2e/suites.json, e2e/README.md "Suites"; scripts/e2e-run.mjs sets E2E_LANES and picks the
 * projects): one project per suite, serial in itself, as that lane's users (e2e/support/parallel-users.ts).
 */
const LANES = process.env.E2E_LANES
  ? [
      // Each step sets the employee links of the clones its lane needs (e2e/support/lanes.setup.ts), so a
      // re-run of a parallel suite after the serial lane (npm run e2e:failed) finds them as deployed.
      {
        name: 'edge-serial-setup',
        testMatch: '**/support/lanes.setup.ts',
        grep: /serial lane/,
        outputDir: 'test-results/lanes/serial-setup',
        use: EDGE,
      },
      {
        name: 'edge-parallel-setup',
        testMatch: '**/support/lanes.setup.ts',
        grep: /parallel lane/,
        outputDir: 'test-results/lanes/parallel-setup',
        use: EDGE,
      },
      ...suites().map((suite) => ({
        name: `suite-${suite.name}`,
        testMatch: suite.specs.map((spec) => `**/${spec}`),
        workers: 1,
        dependencies:
          suite.lane === 'serial'
            ? ['edge-serial-setup']
            : suite.identity === 'clones'
              ? ['edge-parallel-setup']
              : [],
        outputDir: `test-results/lanes/${suite.name}`,
        use: EDGE,
      })),
    ]
  : [];

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: process.env.E2E_LANES ? parallelWorkers() : 1,
  retries: process.env.CI ? 1 : 0,
  forbidOnly: Boolean(process.env.CI),
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  // Room for one wait-out of the sign-in rate limit (61 s, e2e/support/app.ts) inside a test.
  timeout: 180_000,
  use: {
    baseURL,
    locale: 'en-IN',
    timezoneId: 'Asia/Kolkata',
    // Only for a self-signed test certificate (scripts/local/generate-dev-keys.sh); never in CI
    // against UAT or production.
    ignoreHTTPSErrors: process.env.E2E_IGNORE_TLS_ERRORS === '1',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    // Microsoft Edge, FCI's default browser: the whole suite (the installed Edge, no download).
    // The browser's own User-Agent, not the device descriptor's fixed one: Chromium sends a few
    // requests (one in flight across a navigation, a service-worker fetch) with its real UA, and
    // the backend ends a session whose User-Agent changes (hijack detection, SessionPolicyFilter).
    // A mixed UA signed the role-screen-matrix user out mid-test (2026-11-04: CPF_MANAGER at
    // /payroll/?s=I-18 landed on the sign-in page; SESSION_HIJACK_SUSPECTED in the audit log).
    { name: 'edge', use: EDGE },
    // Every other browser FCI users may open the application in: the cross-browser smoke set
    // (sign-in with OTP, the persona menus, the phone and tablet layouts). The whole suite on each
    // would exceed the shared demo users' OTP cap (500 an hour on the demo profile); run one with --project <name>.
    ...CROSS_BROWSER.map(([name, device, specs]) => ({
      name,
      testMatch: specs,
      // Desktop Chrome: its real User-Agent, as for Edge above. Phones keep the device's (the
      // emulation needs it) and run only the short smoke set.
      use: { ...devices[device], ...(name === 'chrome' ? { userAgent: undefined } : {}) },
    })),
    ...LANES,
  ],
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : { command: 'npm run dev', url: baseURL, reuseExistingServer: true, timeout: 120_000 },
});
