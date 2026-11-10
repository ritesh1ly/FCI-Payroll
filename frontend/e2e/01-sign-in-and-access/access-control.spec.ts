import { expect, test } from '@playwright/test';
import {
  apiStatus,
  dataRows,
  decide,
  expectNoBrowserStorage,
  sidebarCodes,
  SIGN_IN_PAGE,
  signIn,
  switchScope,
  t,
  workAs,
  onSomePage,
  employeeList,
} from '../support/app';

/**
 * RBAC edge cases through the browser (docs/rbac/RBAC_DESIGN.md section 5a): the menu is a
 * convenience, never the protection. Edited URLs, forged API calls, another office's data, the
 * same user as maker and checker, and a closed session are all refused by the server.
 */
test.describe('Access control (RBAC)', () => {
  test(
    'an edited ?s= never opens a screen outside the role (deny by default)',
    { tag: ['@SRS-G-25', '@SRS-EH-5', '@SC-01-0025', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'W-02',
      );
      // SRS 14.4: M-08, M-16, M-18 and M-19 do not list the maker. W-01 (X-02) does (OD-125): see below.
      for (const screen of ['A-07', 'A-06', 'M-16', 'M-08', 'M-18', 'M-19', 'XX', '../A-01', 'A-01%00']) {
        await page.goto(`/payroll/?s=${encodeURIComponent(screen)}`);
        // Refused by the server-side guard: the dashboard, never the screen.
        await expect(page).toHaveURL(/\/payroll\/?$/);
        await expect(page.getByRole('heading', { level: 1, name: t('en', 'dash.title') })).toBeVisible();
      }
      // The design-pack body of a screen is served only after the same check (the page route itself,
      // under the base path, so the answer is the route's and not NGINX's).
      expect((await page.request.get('/payroll/wireframe/A-07')).status()).toBe(404);
      expect((await page.request.get('/nginx/payroll/wireframe/A-07')).status()).toBe(404);
      // X-02 opens for the maker (SRS 14.4, OD-125), with its own submissions only and nothing to
      // decide (SRS-22-22): the page never renders an approver's queue or button for this role.
      await page.goto('/payroll/?s=W-01');
      await expect(page.getByRole('heading', { level: 1, name: t('en', 'screen.W-01') })).toBeVisible();
      await expect(page.getByRole('button', { name: t('en', 'w01.approveSelected') })).toBeDisabled();
      await expect(page.getByRole('button', { name: t('en', 'w01.approveSelected') })).toHaveAttribute(
        'title',
        t('en', 'w01.why.approveSelected'),
      );
    },
  );

  test(
    'forged API calls from a signed-in maker are refused',
    { tag: ['@SRS-G-25', '@SRS-SEC-7', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'W-02',
      );
      expect(await apiStatus(page, '/v1/admin/parameters')).toBe(403);
      expect(await apiStatus(page, '/v1/masters/reference-lists')).toBe(403);
      expect(await apiStatus(page, '/v1/admin/roles')).toBe(403);
      expect(
        await apiStatus(page, '/v1/masters/reference-lists/JOB_STATUS/values/PAUSED', 'POST', {
          labelEn: 'tampered',
          reason: 'e2e',
        }),
      ).toBe(403);
      // A scope the user does not hold.
      expect(
        await apiStatus(page, '/auth/switch-scope', 'POST', {
          role: 'SYSTEM_ADMINISTRATOR',
          location: 'HQ001',
        }),
      ).toBe(403);
      // The reference lists every dropdown uses are open to any signed-in session.
      expect(await apiStatus(page, '/v1/reference/lists')).toBe(200);
    },
  );

  test(
    'tampered paths and a session id in the URL are refused by the stack, never 5xx',
    { tag: ['@SRS-G-25', '@SRS-SEC-7', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'W-02',
      );
      // Path traversal, encoded separators, a matrix parameter, a backslash, a null byte and a
      // CRLF in the path: refused by NGINX / Tomcat / Spring before any handler, never a 5xx and
      // never the admin resource they reach for.
      const tampered = [
        '/nginx/fci-payroll/v1/masters/circulars/../../admin/parameters',
        '/nginx/fci-payroll/v1/masters/circulars/..%2f..%2fadmin%2fparameters',
        '/nginx/fci-payroll/v1/masters/circulars/%2e%2e/%2e%2e/admin/parameters',
        '/nginx/fci-payroll/v1/admin/roles/1%5c..%5cparameters',
        '/nginx/fci-payroll/v1/admin/audit/export?from=..%2f..%2fetc%2fpasswd',
        '/nginx/fci-payroll/v1/masters/circulars/1/document%00.txt',
      ];
      for (const path of tampered) {
        const status = await page.evaluate(async (p) => {
          try {
            return (await fetch(p, { credentials: 'same-origin' })).status;
          } catch {
            return 0;
          }
        }, path);
        // 0 (blocked by the browser before it left) or a 4xx; never 2xx, never 5xx.
        expect(status, path).toBeLessThan(500);
        expect(status, path).not.toBe(200);
      }
      // A session id presented in the URL is ignored (cookie-only tracking).
      const cookies = await page.context().cookies();
      const session = cookies.find((c) => c.name.includes('FCISESSION'))?.value ?? '';
      expect(
        await apiStatus(page, `/auth/me;jsessionid=${encodeURIComponent(session)}`),
      ).toBeGreaterThanOrEqual(400);
    },
  );

  test(
    'switching office changes the data: nothing of the previous office stays visible',
    { tag: ['@SRS-G-29', '@SC-02-0002', '@edge'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'ddo.multi@fci-demo.test',
        { role: 'PAYROLL_CHECKER', office: 'DH001' },
        'W-01',
      );
      await expect(page.getByRole('cell', { name: /Selection grade .*106845/ })).toBeVisible();
      await switchScope(page, 'en', { role: 'PAYROLL_CHECKER', office: 'MR001' });
      await page.goto('/payroll/?s=W-01');
      await expect(page.getByRole('heading', { level: 1, name: t('en', 'screen.W-01') })).toBeVisible();
      await expect(page.getByRole('cell', { name: /Selection grade .*106845/ })).toHaveCount(0);
      await expect(page.locator('.rolebar')).toContainText('MR001');
    },
  );

  test(
    'switching zone shows only that zone: the employee master of an HQ checker',
    { tag: ['@SRS-G-29', '@edge'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'checker.hq@fci-demo.test',
        { role: 'PAYROLL_CHECKER', office: 'NA11' },
        'M-01',
      );
      await expect(employeeList(page).getByRole('cell', { name: '104882', exact: true })).toBeVisible();
      await expect(page.getByRole('cell', { name: '205101', exact: true })).toHaveCount(0);
      await switchScope(page, 'en', { role: 'PAYROLL_CHECKER', office: 'ZO-W' });
      await page.goto('/payroll/?s=M-01');
      await expect(employeeList(page).getByRole('cell', { name: '205101', exact: true })).toBeVisible();
      await expect(page.getByRole('cell', { name: '104882', exact: true })).toHaveCount(0);
    },
  );

  test(
    'the audit trail follows the selected zone; Headquarters keeps the national trail',
    { tag: ['@SRS-G-2', '@SRS-G-29', '@positive'] },
    async ({ browser }) => {
      // An auditable action at Delhi HQ (DH001, North Zone).
      const maker = await browser.newPage();
      await workAs(
        maker,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'C-13',
      );
      const units = await maker.evaluate(async () => {
        const r = await fetch('/nginx/fci-payroll/v1/jobs/types', { credentials: 'same-origin' });
        return ((await r.json()) as { locations: { id: number; code: string }[] }).locations;
      });
      const dh = units.find((u) => u.code === 'DH001')?.id;
      // 409 on a re-run: the request of an earlier run still waits for approval (there is no
      // cancel for a job request) and already left the maker's audit rows this test looks for.
      expect([202, 409]).toContain(
        await apiStatus(maker, '/v1/jobs', 'POST', {
          jobType: 'PAYROLL_DRY_RUN',
          params: { locationId: String(dh), payPeriod: '2026-03' },
          reason: `zone audit e2e ${Date.now()}`,
        }),
      );

      // The drawn Maker column prints the user as signed in (name and employee number, OD-121), not the login.
      const makerName = await maker.evaluate(async () => {
        const r = await fetch('/nginx/fci-payroll/auth/me', { credentials: 'same-origin' });
        return ((await r.json()) as { displayName: string }).displayName;
      });
      expect(makerName).toBeTruthy();
      const makerCell = new RegExp(`^${makerName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}( \\(\\d+\\))?$`);

      const auditor = await browser.newPage();
      const delhiMakerRows = async (office: string) => {
        await workAs(auditor, 'en', 'superuser.hq@fci-demo.test', { role: 'SUPER_USER', office }, 'A-05');
        // Filter by actor, as an auditor would (the first page may be full of other users' rows).
        await auditor.getByLabel(t('en', 'a05.actor')).fill('maker.delhi@fci-demo.test');
        await auditor.getByLabel(t('en', 'a05.actor')).press('Enter');
        // The result: rows in the Audit Trail table, or the "no audit rows" message (server-paged list).
        const trail = auditor.getByRole('table', { name: t('en', 'x04w.trail'), exact: true });
        await expect(
          dataRows(trail)
            .first()
            .or(auditor.getByText(t('en', 'a05.none')).first()),
        ).toBeVisible();
        await auditor.waitForLoadState('networkidle');
        // Rows whose Maker (second column) is the Delhi maker.
        return dataRows(trail)
          .filter({ has: auditor.locator('td:nth-child(2)', { hasText: makerCell }) })
          .count();
      };
      expect(await delhiMakerRows('HQ001')).toBeGreaterThan(0);
      expect(await delhiMakerRows('NA11')).toBeGreaterThan(0);
      expect(await delhiMakerRows('ZO-W')).toBe(0);
    },
  );

  test(
    'the same user as maker and checker can never decide its own item',
    { tag: ['@SRS-G-1', '@SC-01-0001', '@SC-02-0005', '@SC-02-0048', '@SC-02-0054', '@negative'] },
    async ({ browser }) => {
      const page = await browser.newPage();
      await workAs(
        page,
        'en',
        'maker.checker@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'C-13',
      );
      const reason = `SoD e2e ${Date.now()}`;
      await page.getByRole('tab', { name: t('en', 'c13.tab.submit') }).click();
      await page.locator('#job-type').selectOption('PAYROLL_DRY_RUN');
      await page.locator('#job-locationId').selectOption({ label: 'DO — Delhi HQ (DH001)' });
      await page.locator('#job-payPeriod').fill('2026-05');
      await page.locator('#job-reason').fill(reason);
      await page.getByRole('button', { name: t('en', 'c13.submit.review') }).click();
      await page
        .getByRole('dialog')
        .getByRole('button', { name: t('en', 'c13.submit.confirm') })
        .click();
      await expect(page.getByRole('status')).toBeVisible();

      // Same person, checker role: the item is in the inbox but not decidable.
      await switchScope(page, 'en', { role: 'PAYROLL_CHECKER', office: 'DH001' });
      await page.goto('/payroll/?s=W-01');
      const own = await onSomePage(page, page.getByRole('row', { name: new RegExp(reason) }));
      // As drawn (OD-121): the row has no decision of this user (no ✔); View carries all three, disabled.
      await expect(own.getByRole('button', { name: '✔' })).toHaveCount(0);
      await own.getByRole('button', { name: t('en', 'wf.view') }).click();
      const detail = page.getByRole('dialog');
      for (const decision of ['APPROVE', 'RETURN', 'REJECT']) {
        const button = detail.getByRole('button', { name: t('en', `w01.${decision}`) });
        await expect(button).toBeDisabled();
        await expect(button).toHaveAttribute('title', t('en', 'error.WF-403-SOD'));
      }
      await detail.getByRole('button', { name: t('en', 'common.close') }).click();

      // Another checker of the office rejects it (nothing runs; the suite stays re-runnable).
      const checker = await browser.newPage();
      await workAs(
        checker,
        'en',
        'ddo.delhi@fci-demo.test',
        { role: 'PAYROLL_CHECKER', office: 'DH001' },
        'W-01',
      );
      await decide(checker, new RegExp(reason), 'REJECT', 'e2e: separation of duties check');
    },
  );

  test(
    'sign-out ends the session everywhere: no page, no API, no stored data',
    { tag: ['@SRS-G-48', '@SRS-SEC-14', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'W-02',
      );
      await page.locator('.me').click();
      await page.getByRole('menuitem', { name: t('en', 'account.signOut') }).click();
      await expect(page).toHaveURL(SIGN_IN_PAGE);
      await page.goto('/payroll/?s=M-01');
      await expect(page).toHaveURL(SIGN_IN_PAGE);
      expect(await apiStatus(page, '/auth/me')).toBe(401);
      await expectNoBrowserStorage(page);
    },
  );

  test(
    'after sign-out, Back shows nothing of the closed session',
    { tag: ['@SRS-SEC-14', '@SRS-G-48', '@negative'] },
    async ({ page }) => {
      await workAs(
        page,
        'en',
        'maker.delhi@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'DH001' },
        'M-01',
      );
      await expect(page.locator('table.t tbody tr').first()).toBeVisible();
      await page.locator('.me').click();
      await page.getByRole('menuitem', { name: t('en', 'account.signOut') }).click();
      await expect(page).toHaveURL(SIGN_IN_PAGE);
      // The workspace is never cached and is guarded on the server: Back lands on sign-in.
      await page.goBack();
      await expect(page).toHaveURL(SIGN_IN_PAGE);
      await expect(page.locator('table.t tbody tr')).toHaveCount(0);
      expect(await apiStatus(page, '/v1/employees?page=0&size=5')).toBe(401);
    },
  );

  test(
    'the session cookie is __Host-, HttpOnly, Secure and SameSite=Strict, and no script can read it',
    { tag: ['@SRS-SEC-14', '@negative'] },
    async ({ page }) => {
      await signIn(page, 'en', 'finance.mumbai@fci-demo.test');
      await expect(page).toHaveURL(/\/payroll/);
      const session = (await page.context().cookies()).find((c) => c.name.endsWith('FCISESSION'));
      expect(session?.name).toBe('__Host-FCISESSION');
      expect(session).toMatchObject({ httpOnly: true, secure: true, sameSite: 'Strict', path: '/' });
      // HttpOnly: a script injected into the page could not steal it.
      expect(await page.evaluate(() => document.cookie)).not.toContain('FCISESSION');
    },
  );

  test(
    'a second sign-in of the same user ends the first session (one session per user)',
    { tag: ['@SRS-SEC-14', '@negative'] },
    async ({ browser }) => {
      const first = await browser.newPage();
      await signIn(first, 'en', 'finance.mumbai@fci-demo.test');
      await expect(first).toHaveURL(/\/payroll/);
      expect(await apiStatus(first, '/auth/me')).toBe(200);
      const second = await browser.newPage();
      await signIn(second, 'en', 'finance.mumbai@fci-demo.test');
      await expect(second).toHaveURL(/\/payroll/);
      // The older session is superseded at once, in every instance.
      expect(await apiStatus(first, '/auth/me')).toBe(401);
      expect(await apiStatus(second, '/auth/me')).toBe(200);
      await first.close();
      await second.close();
    },
  );

  test(
    'an eSeva employee sees only the own record, and the API agrees',
    { tag: ['@SRS-G-35', '@SRS-SEC-4', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', 'emp.rsharma@fci-demo.test', { role: 'EMPLOYEE_ESEVA' }, 'E-01');
      // E-01, X-06 (a member raises payroll queries on the own record, SRS-G-11) and X-08 (sign-in,
      // OTP and password, all users): SRS 14.4; the order is the pack's sidebar (OD-125).
      expect((await sidebarCodes(page)).sort()).toEqual(['E-01', 'X-06', 'X-08']);
      expect(await apiStatus(page, '/v1/employees?page=0&size=5')).toBe(403);
      expect(await apiStatus(page, '/v1/jobs')).toBe(403);
      expect(await apiStatus(page, '/v1/inbox')).toBe(403);
    },
  );

  test(
    'a locked account and an expired assignment cannot sign in',
    { tag: ['@SRS-G-49', '@negative'] },
    async ({ page }) => {
      await signIn(page, 'en', 'locked.user@fci-demo.test');
      await expect(page.locator('.fl-error[role="alert"]')).toHaveText(
        t('en', 'error.AUTH-423-ACCOUNT-LOCKED'),
      );
      await signIn(page, 'en', 'expired.group@fci-demo.test');
      await expect(page.locator('.fl-error[role="alert"]')).toHaveText(t('en', 'error.AUTH-403-NO-SCOPE'));
    },
  );
});
