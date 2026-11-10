import { expect, type Page, test } from '@playwright/test';
import { t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sqlRows } from '../support/db';

/**
 * E2E-03 step 2 (SRS-NF-26, SRS-15-29; e2e-scenarios l.65): a save made against a version another
 * tab has since changed is refused with COM-412-CHANGED naming who changed it, when, and the version
 * now held; nothing of the stale save is written; after a reload the change applies. The record is an
 * A-01 user's contact details (the save that carries If-Match, SRS-15-29), of a demo user no other
 * suite signs in with, and the spec puts the name back, so it is re-runnable.
 * Step 1 (the lease holder and since when, the action disabled) is 06-cycle-lease; step 3 (a pending
 * Checker action named with who and when, WF-409-PENDING-BY) is decision-rules and batch-kill-switch.
 */
const ADMIN = { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' };
const SYSADMIN = 'sysadmin@fci-demo.test';
// Aakash Chopra, Finance at Zone South (seed V037_9): a seeded user no spec signs in with.
const TARGET = 'fin.zos@fci-demo.test';

interface UserRow {
  id: number;
  loginId: string;
  displayName: string;
  rowVersion: number;
}

async function read(page: Page): Promise<UserRow> {
  return page.evaluate(async (login) => {
    const r = await fetch(`/nginx/fci-payroll/v1/admin/users?q=${encodeURIComponent(login)}&page=0&size=10`, {
      credentials: 'same-origin',
    });
    const body = (await r.json()) as { items: UserRow[] };
    const row = body.items.find((u) => u.loginId === login);
    if (!row) throw new Error(`no A-01 row for ${login}`);
    return row;
  }, TARGET);
}

/** PUT the contact with If-Match, as a screen holding version {@code version} would. */
async function save(
  page: Page,
  id: number,
  version: number,
  displayName: string,
  mobile: string | null,
): Promise<{ status: number; errorCode: string | null; detail: string; rowVersion: number | null }> {
  return page.evaluate(
    async ([userId, v, name, phone]) => {
      const token =
        document.cookie
          .split('; ')
          .find((c) => c.startsWith('XSRF-TOKEN='))
          ?.split('=')[1] ?? '';
      const r = await fetch(`/nginx/fci-payroll/v1/admin/users/${userId}/contact`, {
        method: 'PUT',
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': token,
          'Idempotency-Key': crypto.randomUUID(),
          'If-Match': `"${v}"`,
        },
        body: JSON.stringify({ displayName: name, mobile: phone }),
      });
      const body = (await r.json().catch(() => ({}))) as {
        errorCode?: string;
        detail?: string;
        rowVersion?: number;
      };
      return {
        status: r.status,
        errorCode: r.ok ? null : (body.errorCode ?? null),
        detail: body.detail ?? '',
        rowVersion: r.ok ? (body.rowVersion ?? null) : null,
      };
    },
    [id, version, displayName, mobile] as const,
  );
}

test.describe('E2E-03: two users act on the same record', () => {
  test(
    'SRS-NF-26: a stale save from a second tab is refused naming who, when and the current version; after a reload it applies',
    { tag: ['@E2E-03', '@SRS-NF-26', '@SRS-15-29', '@SC-05-0029', '@negative'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      await workAs(page, 'en', SYSADMIN, ADMIN, 'A-01');
      // Two tabs of the session open the same record at the same version.
      const tabB = await page.context().newPage();
      await tabB.goto('/payroll/?s=A-01');
      const a = await read(page);
      const b = await read(tabB);
      expect(b.rowVersion).toBe(a.rowVersion);
      const [[mobile]] = sqlRows(
        `SELECT COALESCE(registered_mobile, '') FROM app_user WHERE login_id = '${TARGET}'`,
      ) as [[string]];
      const phone = mobile === '' ? null : mobile;
      const original = a.displayName;
      const changed = original.endsWith(' (E2E)') ? original.replace(/ \(E2E\)$/, '') : `${original} (E2E)`;

      // Tab A saves first.
      const first = await save(page, a.id, a.rowVersion, changed, phone);
      expect(first.status).toBe(200);
      expect(first.rowVersion).toBe(a.rowVersion + 1);

      // Tab B saves against the version it read: refused, naming the change it missed.
      const stale = await save(tabB, b.id, b.rowVersion, `${original} (stale)`, phone);
      expect(stale.status).toBe(412);
      expect(stale.errorCode).toBe('COM-412-CHANGED');
      expect(stale.detail).toContain(SYSADMIN);
      expect(stale.detail).toContain(`version ${a.rowVersion + 1}`);
      expect(sqlRows(`SELECT display_name FROM app_user WHERE login_id = '${TARGET}'`)[0]?.[0]).toBe(changed);
      // The user-facing text of the refusal carries the same three facts.
      expect(t('en', 'error.COM-412-CHANGED', [SYSADMIN, 'T', a.rowVersion + 1])).toContain(
        `version ${a.rowVersion + 1}`,
      );

      // Tab B reloads and applies its change again: the name is put back.
      const reloaded = await read(tabB);
      expect(reloaded.rowVersion).toBe(a.rowVersion + 1);
      const again = await save(tabB, reloaded.id, reloaded.rowVersion, original, phone);
      expect(again.status).toBe(200);
      expect(sqlRows(`SELECT display_name FROM app_user WHERE login_id = '${TARGET}'`)[0]?.[0]).toBe(
        original,
      );
      const [[phoneAfter]] = sqlRows(
        `SELECT COALESCE(registered_mobile, '') FROM app_user WHERE login_id = '${TARGET}'`,
      ) as [[string]];
      expect(phoneAfter).toBe(mobile);
    },
  );
});
