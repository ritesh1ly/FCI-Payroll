import { expect, test } from '@playwright/test';
import { apiStatus, decide, m16OtherListRows, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql, sqlRows } from '../support/db';
import { apiAll, apiJson } from '../support/payroll-support';

/**
 * SRS-G-48 (Module 02 D-2): a sensitive role's OTP confirmation lasts STEP_UP_VALIDITY. When it lapses during a
 * session, the Payroll Checker is asked the OTP again before deciding, and the decision then goes through. Found in
 * the full run of 2026-11-08: a session signed in hours earlier reached W-01 with its confirmation lapsed. Time
 * cannot pass in a test, so a database fixture moves the session's confirmation a day back; the item is a real M-16
 * relabel proposal, rejected at the end, so nothing changes and nothing is left waiting.
 */
const ADMIN = 'sysadmin@fci-demo.test';
const HQ_CHECKER = 'checker.hq@fci-demo.test';
const admin = { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' };
const hqChecker = { role: 'PAYROLL_CHECKER', office: 'HQ001' };
const stamp = Date.now();

test(
  'SRS-G-48: a checker whose OTP confirmation lapsed is asked it again, then the decision goes through',
  { tag: ['@SRS-G-48', '@edge'] },
  async ({ page }) => {
    test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
    const reason = `E2E step-up lapse ${stamp}`;
    // Re-runnable: an item an interrupted run left waiting holds the value (one pending change per value).
    await workAs(page, 'en', HQ_CHECKER, hqChecker, 'W-01');
    for (const left of await apiAll<{ approvalId: number; reason: string | null }>(page, '/v1/inbox')) {
      if (left.reason?.startsWith('E2E step-up lapse')) {
        expect(
          await apiStatus(page, `/v1/approvals/${left.approvalId}/decision`, 'POST', {
            decision: 'REJECT',
            reason: 'E2E: left by an interrupted run',
          }),
        ).toBe(200);
      }
    }
    await workAs(page, 'en', ADMIN, admin, 'M-16');
    await page.locator('#m16-list').selectOption('CIRCULAR_CATEGORY');
    const code =
      (await m16OtherListRows(page).first().locator('td code').first().textContent())?.trim() ?? '';
    const { approvalId } = await apiJson<{ approvalId: number }>(
      page,
      `/v1/masters/reference-lists/CIRCULAR_CATEGORY/values/${code}`,
      'POST',
      { labelEn: `Lapse ${stamp}`, reason },
    );
    expect(approvalId, 'the relabel proposal').toEqual(expect.any(Number));

    await workAs(page, 'en', HQ_CHECKER, hqChecker, 'W-01');
    const stepUp = async () =>
      ((await (await page.request.get('/nginx/fci-payroll/auth/me')).json()) as { stepUpRequired?: boolean })
        .stepUpRequired;
    expect(await stepUp(), 'confirmed at sign-in').toBeFalsy();
    // The confirmation lapses: the session's step-up moved a day back (it lasts STEP_UP_VALIDITY, 15 minutes by
    // default). Sessions are cached (region ACCESS, reloaded within a minute of a version change): the version is
    // moved as the application's own changes do, and the server is asked until it has reloaded.
    const user = sqlRows(`SELECT user_id FROM app_user WHERE login_id = '${HQ_CHECKER}'`)[0]?.[0];
    sql(
      [
        `UPDATE app_session SET step_up_at = step_up_at - INTERVAL '1' DAY WHERE user_id = ${user} AND step_up_at IS NOT NULL AND ended_at IS NULL`,
        `UPDATE cache_region SET version_no = version_no + 1, changed_at = CURRENT_TIMESTAMP, changed_by = 'E2E step-up lapse' WHERE region_code = 'ACCESS'`,
      ].join(';\n'),
    );
    await expect.poll(stepUp, { message: 'the server now asks the OTP again', timeout: 90_000 }).toBe(true);

    // decide() renews the confirmation with the real code, then rejects: the item leaves the inbox.
    await decide(page, new RegExp(reason), 'REJECT', 'E2E: step-up lapse test item, not needed');
    await expect(page.getByRole('row', { name: new RegExp(reason) })).toHaveCount(0);
    expect(await stepUp(), 'confirmed again').toBeFalsy();
    expect(
      sqlRows(`SELECT status FROM approval_queue WHERE approval_id = ${approvalId}`)[0]?.[0],
      'rejected, nothing changed',
    ).toBe('REJECTED');
  },
);
