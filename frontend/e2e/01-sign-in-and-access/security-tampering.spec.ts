import { expect, type Page, test } from '@playwright/test';
import { apiProblem, apiStatus, workAs } from '../support/app';
import { sqlRows } from '../support/db';
import { apiJson } from '../support/payroll-support';

/**
 * The user interface is no protection (SRS-G-25, SRS-22-8; OWASP ASVS V4.1.1 "access control on a
 * trusted service layer"; docs/quality/rbac-security-scenarios-2026-11-04.md RBAC-SEC-167..171):
 * a signed-in low-privilege user who rewrites the browser's own traffic - the profile the page
 * reads, the buttons it enables, the bodies and headers it sends - gains nothing, because every
 * call is decided again by the backend from the server-side session. Also checked through the
 * real NGINX: the security headers of pages and API answers, and a cross-site write refused even
 * with a valid CSRF token pair.
 *
 * Read-only for the business data: every forged call is refused.
 */
const MAKER = 'maker.delhi@fci-demo.test';

async function asMaker(page: Page) {
  await workAs(page, 'en', MAKER, { role: 'PAYROLL_MAKER', office: 'DH001' }, 'W-02');
}

/** A request the page itself sends with extra headers (an intercepting proxy would add them). */
async function forged(
  page: Page,
  path: string,
  method: string,
  headers: Record<string, string>,
  body?: unknown,
): Promise<{ status: number; errorCode: string | null; text: string }> {
  return page.evaluate(
    async ([p, m, h, b]) => {
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
          ...(h as Record<string, string>),
        },
        body: b === undefined ? undefined : JSON.stringify(b),
      });
      const text = await response.text();
      let errorCode: string | null = null;
      try {
        errorCode = (JSON.parse(text) as { errorCode?: string }).errorCode ?? null;
      } catch {
        errorCode = null;
      }
      return { status: response.status, errorCode, text };
    },
    [path, method, headers, body] as const,
  );
}

test.describe('Tampering with the browser traffic gains nothing (SRS-G-25)', () => {
  test(
    'a rewritten profile shows more, but every forged call is refused by the server',
    { tag: ['@SRS-G-25', '@SRS-22-8', '@negative'] },
    async ({ page }) => {
      await asMaker(page);
      // RBAC-SEC-170: the page is told the maker holds the administration screens and every
      // governed button is allowed.
      await page.route('**/nginx/fci-payroll/auth/me', async (route) => {
        const response = await route.fetch();
        const me = (await response.json()) as { permissions: string[] };
        me.permissions = [...me.permissions, 'A-01:VIEW', 'A-02:VIEW', 'A-02:EDIT', 'W-01:APPROVE'];
        await route.fulfill({ response, json: me });
      });
      await page.route('**/nginx/fci-payroll/v1/me/actions**', async (route) => {
        const response = await route.fetch();
        const text = (await response.text()).replace(/"allowed"\s*:\s*false/g, '"allowed":true');
        await route.fulfill({ response, body: text });
      });
      await page.goto('/payroll/?s=W-02');
      // Whatever the page now shows, the server answers from the session.
      for (const [path, method, body] of [
        ['/v1/admin/users', 'GET', undefined],
        ['/v1/admin/roles', 'GET', undefined],
        ['/v1/inbox/export', 'GET', undefined],
        ['/v1/approvals/1/decision', 'POST', { decision: 'APPROVE', reason: 'forged' }],
        ['/v1/actions', 'POST', { screen: 'A-02', action: 'EDIT', reason: 'forged' }],
        ['/v1/actions', 'POST', { screen: 'W-01', action: 'APPROVE', reason: 'forged' }],
      ] as const) {
        const answer = await apiProblem(page, path, method, body);
        expect(answer.status, `${method} ${path}`).toBe(403);
        expect(answer.errorCode, `${method} ${path}`).toBe('AUTHZ-403-PERMISSION');
      }
      // SRS 14.4 lists the maker on X-02 (W-01 VIEW, OD-125; only approvers act, SRS-22-22): the read
      // answers, but SRS-G-29 shows an item only to the role and office of its level, so the maker
      // gets none of the items waiting for the Checker at the same office.
      const queue = await apiJson<{ items: unknown[]; total: number }>(page, '/v1/inbox');
      expect(queue).toMatchObject({ items: [], total: 0 });
      const waiting = sqlRows(
        "SELECT i.approval_id FROM workflow_inbox_item i JOIN approval_queue q ON q.approval_id = i.approval_id JOIN org_location o ON o.location_id = i.org_scope_id WHERE i.item_status = 'PENDING' AND i.assigned_role_code = 'PAYROLL_CHECKER' AND o.location_code = 'DH001' AND q.submitted_by <> 'maker.delhi@fci-demo.test' ORDER BY 1",
      );
      expect(waiting.length, 'demo items of the Checker at DH001').toBeGreaterThan(0);
      // Nor is the item reached by its id: as one that does not exist (W-1, security review 2026-11-04).
      expect(await apiStatus(page, `/v1/approvals/${waiting[0]?.[0]}/documents`)).toBe(404);
      // The server-side page guard does not read the rewritten profile either.
      expect(
        (await page.request.get('/nginx/payroll?s=A-02', { maxRedirects: 0 })).headers().location,
      ).toMatch(/\/payroll\/?$/);
    },
  );

  test(
    'a rewritten request body, a forged role header and a replayed id are refused',
    { tag: ['@SRS-22-2', '@SRS-G-29', '@negative'] },
    async ({ page }) => {
      await asMaker(page);
      // RBAC-SEC-171: a body rewritten in flight - the scope switch the page sends is turned into
      // a switch to a role the user does not hold.
      await page.route('**/nginx/fci-payroll/auth/switch-scope', async (route) => {
        await route.continue({
          postData: JSON.stringify({ role: 'SYSTEM_ADMINISTRATOR', location: 'HQ001' }),
        });
      });
      const switched = await apiProblem(page, '/auth/switch-scope', 'POST', {
        role: 'PAYROLL_MAKER',
        location: 'DH001',
      });
      expect(switched.status).toBe(403);
      expect(switched.errorCode).toBe('AUTHZ-403-SWITCH');
      await page.unroute('**/nginx/fci-payroll/auth/switch-scope');

      // RBAC-SEC-085 / 167: identity and scope headers a proxy adds are ignored.
      const headers = {
        'X-Role': 'SYSTEM_ADMINISTRATOR',
        'X-Active-Role': 'SYSTEM_ADMINISTRATOR',
        'X-Office': 'HQ001',
        'X-Location-Id': '1',
        'X-User-Id': '1',
        'X-Forwarded-User': 'sysadmin@fci-demo.test',
        'X-Original-URL': '/api/v1/admin/roles',
        'X-Rewrite-URL': '/api/v1/admin/roles',
        'X-HTTP-Method-Override': 'GET',
      };
      const admin = await forged(page, '/v1/admin/roles', 'GET', headers);
      expect(admin.status).toBe(403);
      expect(admin.errorCode).toBe('AUTHZ-403-PERMISSION');
      const me = await forged(page, '/auth/me', 'GET', headers);
      expect(me.status).toBe(200);
      expect(JSON.parse(me.text)).toMatchObject({ role: 'PAYROLL_MAKER', loginId: MAKER });

      // IDOR: approval ids guessed in sequence are refused, never decided, and never answered
      // with another office's data.
      for (const id of [1, 2, 3, 999_999_999]) {
        const guess = await forged(page, `/v1/approvals/${id}/documents`, 'GET', {});
        expect(guess.status, `approval ${id}`).toBeGreaterThanOrEqual(400);
        expect(guess.text).not.toMatch(/"fileName"/);
      }
    },
  );

  test(
    'a write from another site is refused even with a valid token pair, and the headers hold',
    { tag: ['@SRS-G-48', '@SRS-SEC-4', '@negative'] },
    async ({ page }) => {
      await asMaker(page);
      // RBAC-SEC-104 / 105: a planted token pair does not help a cross-site request.
      const cookies = await page.context().cookies();
      const token = cookies.find((c) => c.name === 'XSRF-TOKEN')?.value ?? '';
      const write = (extra: Record<string, string>) =>
        page.request.put('/nginx/fci-payroll/auth/locale', {
          data: { locale: 'en' },
          headers: { 'X-XSRF-TOKEN': token, ...extra },
        });
      expect((await write({})).status()).toBe(204);
      const crossSite: Record<string, string>[] = [
        { 'Sec-Fetch-Site': 'cross-site' },
        { 'Sec-Fetch-Site': 'same-site' },
        { Origin: 'https://evil.example' },
        { Origin: 'null' },
      ];
      for (const extra of crossSite) {
        const refused = await write(extra);
        expect(refused.status(), JSON.stringify(extra)).toBe(403);
        expect(((await refused.json()) as { errorCode: string }).errorCode).toBe('COM-403-DENIED');
      }
      // RBAC-SEC-131: the headers of a page and of an API answer, through NGINX.
      const pageAnswer = await page.request.get('/nginx/payroll');
      const pageHeaders = pageAnswer.headers();
      expect(pageHeaders['content-security-policy']).toMatch(/frame-ancestors 'none'/);
      expect(pageHeaders['content-security-policy']).toMatch(/'nonce-/);
      expect(pageHeaders['content-security-policy']).not.toMatch(/'unsafe-inline'|'unsafe-eval'/);
      expect(pageHeaders['strict-transport-security']).toMatch(/max-age=\d{7,}/);
      expect(pageHeaders['x-content-type-options']).toBe('nosniff');
      expect(pageHeaders['cache-control']).toMatch(/no-store/);
      const apiAnswer = await page.request.get('/nginx/fci-payroll/auth/me');
      const apiHeaders = apiAnswer.headers();
      expect(apiHeaders['x-content-type-options']).toBe('nosniff');
      expect(apiHeaders['cache-control']).toMatch(/no-store/);
      expect(apiHeaders['strict-transport-security']).toMatch(/max-age=\d{7,}/);
      expect(apiHeaders['x-frame-options']).toBe('DENY');
      // The session cookie is never readable by script.
      expect(await page.evaluate(() => document.cookie)).not.toMatch(/FCISESSION/);
      // RBAC-SEC-150 area: no open redirect through the sign-in page.
      const host = new URL(page.url()).host;
      for (const target of [
        '/nginx/?next=https://evil.example/',
        '/nginx/?returnTo=//evil.example/',
        '/nginx/payroll?s=W-02&redirect=https://evil.example/',
      ]) {
        await page.goto(target);
        expect(new URL(page.url()).host, target).toBe(host);
      }
    },
  );

  test(
    'an eSeva member cannot reach staff data by editing ids or paths',
    { tag: ['@SRS-G-29', '@SRS-G-35', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', 'eseva.dh001@fci-demo.test', { role: 'EMPLOYEE_ESEVA' }, 'E-01');
      for (const path of [
        '/v1/employees',
        '/v1/employees/106620',
        '/v1/payslips/mine?employeeId=1',
        '/v1/inbox',
        '/v1//employees',
        '/v1/employees/%2e%2e/admin/users',
        '/v1/./admin/users',
      ]) {
        const answer = await forged(page, path, 'GET', {});
        if (path.startsWith('/v1/payslips/mine')) {
          // The own payslips only: an added employee id is ignored.
          expect(answer.status, path).toBeLessThan(500);
          expect(answer.text, path).not.toMatch(/"eis"\s*:\s*"106620"/);
          continue;
        }
        expect(answer.status, path).toBeGreaterThanOrEqual(400);
        expect(answer.status, path).toBeLessThan(500);
        expect(answer.text, path).not.toMatch(/Exception|at in\.gov|SQL/);
      }
    },
  );
});
