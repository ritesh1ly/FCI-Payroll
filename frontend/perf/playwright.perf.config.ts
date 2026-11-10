import { defineConfig, devices } from '@playwright/test';

/**
 * Browser-side profiling of a module's screens against a deployed stack (owner 2026-10-25: profile
 * the frontend and the backend end to end before a module is closed). Not part of `npm run e2e`:
 *   E2E_BASE_URL=https://localhost:8743 E2E_IGNORE_TLS_ERRORS=1 PERF_MODULE=08 \
 *     npx playwright test -c perf/playwright.perf.config.ts
 * Results: docs/perf/module-<NN>-browser.json (one run per call; compare with the last one kept).
 */
export default defineConfig({
  testDir: '.',
  testMatch: /.*\.profile\.spec\.ts/,
  workers: 1,
  retries: 0,
  timeout: 600_000,
  reporter: 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'https://localhost:8743',
    locale: 'en-IN',
    timezoneId: 'Asia/Kolkata',
    ignoreHTTPSErrors: process.env.E2E_IGNORE_TLS_ERRORS === '1',
    trace: 'off',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
