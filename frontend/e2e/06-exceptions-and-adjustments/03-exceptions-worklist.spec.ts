import { expect, type Page, test } from '@playwright/test';
import { CHECKER, checker, MAKER, maker } from '../support/payroll-support';
import { apiStatus, exportLink, sidebarCodes, t, workAs } from '../support/app';

/**
 * X-05 Exceptions & Holds Worklist (SRS-G-9, SRS-G-13). The demo stack raises no exception yet (the
 * producers - pre-payroll checks, the computation quarantine, interfaces - arrive with module 05;
 * decision D-7), so this proves the screen, its tiles, filters and CSV export and who may open it.
 * Assigning and accepting are integration- and component-tested on raised items.
 */
/** The filters of X-05 open from its Filter button (as drawn, OD-121). */
async function openFilters(page: Page) {
  const name = new RegExp(`^(${t('en', 'x05.filter')}|${t('hi', 'x05.filter')})$`);
  const button = page.getByRole('button', { name });
  const open = page.getByLabel(t('en', 'gen.status')).or(page.getByLabel(t('hi', 'gen.status')));
  if ((await open.count()) === 0) await button.click();
}

test.describe('Exceptions and holds worklist (X-05)', () => {
  test(
    'SRS-G-9: the Maker sees the four tiles and filters the worklist',
    { tag: ['@SRS-G-9', '@SC-01-0009', '@SC-05-0063', '@SC-05-0070', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'X-05');
      await openFilters(page);
      for (const tile of ['x05.tile.open', 'x05.tile.blockingG2', 'x05.tile.accepted', 'x05.tile.upstream']) {
        await expect(page.locator('.tile').filter({ hasText: t('en', tile) })).toBeVisible();
      }
      const listed = page.waitForResponse(
        (r) => r.url().includes('/nginx/fci-payroll/v1/exceptions/groups?') && r.status() === 200,
      );
      await page.getByLabel(t('en', 'gen.status')).selectOption('OPEN');
      await page.getByLabel(t('en', 'x05.severity'), { exact: true }).selectOption('BLOCKING');
      expect((await listed).url()).toContain('status=OPEN');
    },
  );

  test(
    'SRS-G-9: the worklist filters by exception class, period and office; the export carries them; a malformed or out-of-scope filter is refused',
    { tag: ['@SRS-G-9', '@SRS-G-13', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'X-05');
      await openFilters(page);
      // The worklist's own office filter (the top bar has an office selector too).
      const office = page.locator('#x05-office');
      // The Maker's own office is offered (its data scope).
      await expect(office.locator('option', { hasText: /^\s*DH001 - / })).toHaveCount(1);
      const value = await office.locator('option', { hasText: /^\s*DH001 - / }).getAttribute('value');
      const listed = page.waitForResponse(
        (r) =>
          r.url().includes('/nginx/fci-payroll/v1/exceptions/groups?') &&
          r.url().includes('period=2026-04') &&
          r.url().includes(`office=${value}`) &&
          r.status() === 200,
      );
      await page.getByLabel(t('en', 'x05.period'), { exact: true }).fill('2026-04');
      await office.selectOption(value ?? '');
      await listed;
      await expect(await exportLink(page, 'CSV')).toHaveAttribute(
        'href',
        `/nginx/fci-payroll/v1/exceptions/export?period=2026-04&office=${value}&format=CSV`,
      );
      // The class filter offers the classes present in scope, and "All".
      await expect(
        page.getByLabel(t('en', 'x05.class'), { exact: true }).locator('option').first(),
      ).toHaveText(t('en', 'gen.all'));
      // Negative: a month that does not exist, an office outside the scope, a tampered code.
      expect(await apiStatus(page, '/v1/exceptions/groups?period=2026-13')).toBe(400);
      expect(await apiStatus(page, '/v1/exceptions/groups?period=202604')).toBe(400);
      expect(await apiStatus(page, '/v1/exceptions?code=A%27--')).toBe(400);
      const kolkata = await apiStatus(page, '/v1/exceptions/groups?office=999999999');
      expect([403, 404]).toContain(kolkata);
    },
  );

  test(
    'SRS-G-13: the export is a CSV download carrying the same filter',
    { tag: ['@SRS-G-13', '@SC-01-0013', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', CHECKER, checker, 'X-05');
      await openFilters(page);
      await page.getByLabel(t('en', 'gen.status')).selectOption('ACCEPTED');
      await expect(await exportLink(page, 'CSV')).toHaveAttribute(
        'href',
        '/nginx/fci-payroll/v1/exceptions/export?status=ACCEPTED&format=CSV',
      );
      const response = await page.request.get('/nginx/fci-payroll/v1/exceptions/export?status=ACCEPTED');
      expect(response.status()).toBe(200);
      expect(response.headers()['content-type']).toContain('text/csv');
      expect(response.headers()['content-disposition']).toContain('attachment');
    },
  );

  test(
    'SRS-G-13: every Module 01 list downloads as CSV for the Payroll Maker',
    { tag: ['@SRS-G-13', '@SC-01-0013', '@positive'] },
    async ({ page }) => {
      // I-23's export is the Unit-wise Supplementary Payment Report of the wireframe.
      const exports: [string, string, boolean, string?][] = [
        // WF-077: the wireframe draws no export on X-06 and I-20; the lists still download from the API.
        ['X-06', '/nginx/fci-payroll/v1/queries/export', false],
        // X-07's drawn Export is the reconciliation's; the upload list exports from History (WF-198).
        ['X-07', '/nginx/fci-payroll/v1/hrms-fallback/reconciliation/export', true],
        ['I-20', '/nginx/fci-payroll/v1/off-cycle/export', false],
        ['I-23', '/nginx/fci-payroll/v1/supplementary/export', true, t('en', 'i23.reportAct')],
      ];
      for (const [screen, path, drawn, label] of exports) {
        await workAs(page, 'en', MAKER, maker, screen);
        if (drawn) {
          await expect(await exportLink(page, 'CSV', label)).toHaveAttribute('href', `${path}?format=CSV`);
        }
        const response = await page.request.get(`${path}?format=CSV`);
        expect(response.status(), path).toBe(200);
        expect(response.headers()['content-type']).toContain('text/csv');
      }
    },
  );

  test(
    'every Module 01 screen opens in Hindi with its Hindi labels',
    { tag: ['@SRS-G-40', '@SC-01-0038', '@positive'] },
    async ({ page }) => {
      for (const screen of ['X-05', 'X-06', 'X-07', 'I-20', 'I-23']) {
        // workAs asserts the Hindi screen title as the page heading.
        await workAs(page, 'hi', MAKER, maker, screen);
        await expect(page.locator('main, .wf').first()).not.toContainText(
          /\b(x0[567]|i2[03]|gen|ord)\.[a-zA-Z]/,
        );
      }
      await workAs(page, 'en', MAKER, maker, 'X-05');
      await openFilters(page);
    },
  );

  test(
    'the administrator reads the worklist and acts on nothing (SRS 14.4); an eSeva member has none',
    { tag: ['@SRS-G-9', '@SRS-G-25', '@SC-01-0025', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'sysadmin@fci-demo.test',
        { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' },
        'A-01',
      );
      // SRS 14.4 X-05: "maker, checker, ldh, fin, admin - resolve: maker, ldh; approve: checker" (OD-125).
      expect(await sidebarCodes(page)).toContain('X-05');
      expect(await apiStatus(page, '/v1/exceptions')).toBe(200);
      await page.goto('/payroll/?s=X-05');
      await expect(page.getByRole('button', { name: t('en', 'x05.acceptWithReason') })).toBeDisabled();
      await workAs(page, 'en', 'emp.rsharma@fci-demo.test', { role: 'EMPLOYEE_ESEVA' }, 'E-01');
      expect(await sidebarCodes(page)).not.toContain('X-05');
      expect(await apiStatus(page, '/v1/exceptions')).toBe(403);
      expect(await apiStatus(page, '/v1/exceptions/groups')).toBe(403);
    },
  );
});
