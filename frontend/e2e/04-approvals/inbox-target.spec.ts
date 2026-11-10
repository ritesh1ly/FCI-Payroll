import { expect, test } from '@playwright/test';
import { apiStatus, decide, m16OtherListRows, onSomePage, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sql } from '../support/db';
import { apiAll, apiJson } from '../support/payroll-support';

/**
 * W-01 target turnaround (SRS-22-4, BR-APPR-005): an item is due by its action's target and stays
 * flagged once past it until it is actioned. Time cannot pass in a test, so a database fixture moves
 * the item's submission three days back, as InboxTargetIT does; the item is a real M-16 relabel
 * proposal, rejected at the end, so nothing changes and nothing is left waiting.
 */
const ADMIN = 'sysadmin@fci-demo.test';
const HQ_CHECKER = 'checker.hq@fci-demo.test';
const admin = { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' };
const hqChecker = { role: 'PAYROLL_CHECKER', office: 'HQ001' };
const stamp = Date.now();

test(
  'SRS-22-4: an item past its target turnaround is flagged with its age until the checker acts on it',
  { tag: ['@SRS-22-4', '@SC-02-0053', '@edge'] },
  async ({ page }) => {
    test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
    // Re-runnable: an item an interrupted run left waiting would hold the value (one pending
    // change per value), so the checker ends it first.
    await workAs(page, 'en', HQ_CHECKER, hqChecker, 'W-01');
    for (const left of await apiAll<{ approvalId: number; reason: string | null }>(page, '/v1/inbox')) {
      if (left.reason?.startsWith('E2E past target')) {
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
    const reason = `E2E past target ${stamp}`;
    const { approvalId } = await apiJson<{ approvalId: number }>(
      page,
      `/v1/masters/reference-lists/CIRCULAR_CATEGORY/values/${code}`,
      'POST',
      { labelEn: `Late ${stamp}`, reason },
    );
    expect(approvalId, 'the relabel proposal').toEqual(expect.any(Number));
    // Explicit UTC timestamps, as the backend reads them (InboxTargetIT uses Instants): the
    // database's CURRENT_TIMESTAMP is in the session's zone and would shift the age by hours.
    const utc = (ms: number) => `TIMESTAMP '${new Date(ms).toISOString().slice(0, 19).replace('T', ' ')}'`;
    const submitted = Date.now() - 3 * 86_400_000 - 60_000;
    sql(
      [
        `UPDATE approval_queue SET submitted_at = ${utc(submitted)} WHERE approval_id = ${approvalId}`,
        `UPDATE workflow_inbox_item SET sla_due_at = ${utc(submitted + 48 * 3_600_000)} WHERE approval_id = ${approvalId}`,
      ].join(';\n'),
    );

    await workAs(page, 'en', HQ_CHECKER, hqChecker, 'W-01');
    const item = await onSomePage(page, page.getByRole('row', { name: new RegExp(reason) }).first());
    await expect(item).toContainText(t('en', 'w01.ageDays', [3]));
    await expect(item).toContainText(t('en', 'w01.breached'));
    // Acting on it ends the flag: the item leaves the inbox, and the label stays as it was.
    await decide(page, new RegExp(reason), 'REJECT', 'E2E: overdue test item, not needed');
    await expect(page.getByRole('row', { name: new RegExp(reason) })).toHaveCount(0);
  },
);
