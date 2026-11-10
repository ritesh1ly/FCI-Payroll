import { expect, test } from '@playwright/test';
import { signIn } from '../support/app';

/** The pages run under the strict CSP with no violation, no inline style and nothing hot-linked. */
test(
  'no CSP violation, no third-party request, on the login page and the workspace',
  { tag: ['@SRS-NF-21', '@SRS-SEC-1', '@negative'] },
  async ({ page, baseURL }) => {
    const violations: string[] = [];
    const foreign: string[] = [];
    // The app's own origin (not page.url(), which is about:blank during the first navigation).
    const origin = new URL(baseURL ?? 'http://localhost:3000').origin;
    page.on('console', (message) => {
      if (/Content Security Policy|Refused to/i.test(message.text())) violations.push(message.text());
    });
    page.on('request', (request) => {
      const url = new URL(request.url());
      if (url.origin !== origin && !url.protocol.startsWith('data')) foreign.push(request.url());
    });
    await page.goto('/');
    const response = await page.request.get('/');
    expect(response.headers()['content-security-policy'] ?? '').not.toMatch(/unsafe-inline|unsafe-eval/);
    await signIn(page, 'en', 'maker.delhi@fci-demo.test');
    await expect(page).toHaveURL(/\/payroll/);
    await page.goto('/payroll/?s=C-02');
    await expect(page.locator('.wireframe-body-container')).toBeVisible();
    // No inline style anywhere except Next.js's route announcer (its screen-reader live region, styled
    // through the CSSOM, which the CSP allows).
    const styled = await page
      .locator('[style]:not(next-route-announcer):not(#__next-route-announcer__)')
      .evaluateAll((els) => els.map((e) => e.outerHTML.slice(0, 200)));
    expect(styled).toEqual([]);
    expect(violations).toEqual([]);
    expect(foreign).toEqual([]);
  },
);
