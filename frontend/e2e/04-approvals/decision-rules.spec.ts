import { expect, type Page, test } from '@playwright/test';
import { apiProblem, closeDetail, onSomePage, pressDecision, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sqlRows } from '../support/db';

/**
 * W-01 decision rules (SRS-G-1 maker-checker, SRS-G-37 reason on every controlled change;
 * ApprovalService.submit / decide / denial), on the M-24 route as the vehicle (V033_3: Finance at
 * HQ makes, the Payroll Checker at HQ checks, no OTP):
 *
 * - one record has at most one change waiting: a second proposal is refused (WF-409-PENDING);
 * - Return and Reject need a reason, in the dialog and at the API (WF-400-REASON); Approve does not;
 * - a decided item takes no further decision (WF-409-STATE);
 * - the maker of an item can never decide it, even by a forged call (WF-403-SOD);
 * - a Checker of another office neither lists nor decides the item (SRS-G-29);
 * - a returned item shows to its maker on W-02 as Returned, with the checker's return reason beside
 *   the maker's own text (SRS l.596, SRS-G-29), marked apart from a rejected item, which shows
 *   Rejected and no return reason (SC-01-0062).
 *
 * Every item opened here is returned or rejected, on pay code 163 and a date of its own, so the
 * spec re-runs on one stack and nothing it leaves is in force.
 */
const FINANCE_HQ = 'finance.hq@fci-demo.test';
const financeHq = { role: 'FINANCE', office: 'HQ001' };
const CHECKER_HQ = 'checker.hq@fci-demo.test';
const checkerHq = { role: 'PAYROLL_CHECKER', office: 'HQ001' };
const PAY_CODE = 163;

/** A far-future date of this run (one day per minute), plus {@code offset} days. */
function runDay(offset: number): string {
  const minute = Math.floor(Date.now() / 60_000) % 50_000;
  return new Date(Date.UTC(2200, 0, 1) + (minute * 3 + offset) * 86_400_000).toISOString().slice(0, 10);
}

/** Proposes a mapping for {@code PAY_CODE}; returns the status and the approval item's id. */
async function propose(page: Page, effectiveFrom: string, reason: string) {
  return page.evaluate(
    async ([code, body]) => {
      const token =
        document.cookie
          .split('; ')
          .find((c) => c.startsWith('XSRF-TOKEN='))
          ?.split('=')[1] ?? '';
      const response = await fetch(`/nginx/fci-payroll/v1/masters/gl-mappings/${code}/versions`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': token,
          'Idempotency-Key': crypto.randomUUID(),
        },
        body: JSON.stringify(body),
      });
      const json = (await response.json().catch(() => ({}))) as { approvalId?: number; errorCode?: string };
      return {
        status: response.status,
        approvalId: json.approvalId ?? null,
        errorCode: json.errorCode ?? null,
      };
    },
    [PAY_CODE, { effectiveFrom, naturalAccount: 'E2E-W01', reason }] as const,
  );
}

const decision = (page: Page, id: number, choice: string, reason?: string) =>
  apiProblem(page, `/v1/approvals/${id}/decision`, 'POST', {
    decision: choice,
    ...(reason === undefined ? {} : { reason }),
  });

test.describe.serial('W-01 decision rules', () => {
  const stamp = Date.now();
  const returnedReason = `E2E W-01 return ${stamp}`;
  const returnNote = `E2E: wrong account ${stamp}`;
  let returned = 0;

  test(
    'one change waits per record: a second proposal is refused until the first is decided',
    { tag: ['@SRS-G-1', '@SRS-G-37', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', FINANCE_HQ, financeHq, 'M-24');
      const first = await propose(page, runDay(0), returnedReason);
      expect(first.status).toBe(202);
      returned = first.approvalId ?? 0;
      expect(returned).toBeGreaterThan(0);
      expect(await propose(page, runDay(1), `E2E W-01 second ${stamp}`)).toEqual({
        status: 409,
        approvalId: null,
        errorCode: 'WF-409-PENDING-BY',
      });
    },
  );

  test(
    'a Checker of another office never sees the item in the inbox and cannot decide it',
    { tag: ['@SRS-G-29', '@SRS-G-1', '@negative'] },
    async ({ page }) => {
      test.skip(returned === 0, 'Needs the proposal of the first test.');
      // E2E-42 / SRS-G-29 (l.543): the item is routed to Headquarters; the Delhi Checker holds the
      // same role at a unit, so it is out of scope: not listed, and a forged decision is refused.
      await workAs(
        page,
        'en',
        'ddo.delhi@fci-demo.test',
        { role: 'PAYROLL_CHECKER', office: 'DH001' },
        'W-01',
      );
      await expect(page.getByRole('row', { name: new RegExp(returnedReason) })).toHaveCount(0);
      for (const choice of ['APPROVE', 'RETURN', 'REJECT']) {
        const refused = await decision(page, returned, choice, 'E2E: not my office');
        expect([403, 404], choice).toContain(refused.status);
        expect(refused.errorCode, choice).not.toBeNull();
      }
    },
  );

  test(
    'Return and Reject will not go without a reason; with one, the item is returned',
    { tag: ['@SRS-G-1', '@SRS-G-37', '@SC-01-0003', '@negative'] },
    async ({ page }) => {
      test.skip(returned === 0, 'Needs the proposal of the first test.');
      await workAs(page, 'en', CHECKER_HQ, checkerHq, 'W-01');
      const row = await onSomePage(page, page.getByRole('row', { name: new RegExp(returnedReason) }).first());
      for (const choice of ['REJECT', 'RETURN'] as const) {
        const dialog = await pressDecision(page, row, choice);
        const confirm = dialog.getByRole('button', { name: t('en', `w01.${choice}`) });
        await expect(confirm).toBeDisabled();
        await dialog.getByRole('textbox').fill('   ');
        await expect(confirm).toBeDisabled();
        await dialog.getByRole('button', { name: t('en', 'common.cancel') }).click();
        await closeDetail(page);
      }
      // A forged decision without a reason is refused the same way; the item stays pending.
      expect(await decision(page, returned, 'RETURN', '')).toEqual({
        status: 400,
        errorCode: 'WF-400-REASON',
      });
      expect(await decision(page, returned, 'REJECT')).toEqual({ status: 400, errorCode: 'WF-400-REASON' });

      const reason = await pressDecision(page, row, 'RETURN');
      await reason.getByRole('textbox').fill(returnNote);
      await reason.getByRole('button', { name: t('en', 'w01.RETURN') }).click();
      await expect(page.getByRole('status')).toContainText(t('en', 'status.RETURNED'));
    },
  );

  test(
    'a decided item takes no further decision, whatever is forged',
    { tag: ['@SRS-G-1', '@negative'] },
    async ({ page }) => {
      test.skip(returned === 0, 'Needs the proposal of the first test.');
      await workAs(page, 'en', CHECKER_HQ, checkerHq, 'W-01');
      for (const choice of ['APPROVE', 'REJECT', 'RETURN']) {
        expect(await decision(page, returned, choice, 'E2E: once more'), choice).toEqual({
          status: 409,
          errorCode: 'WF-409-STATE',
        });
      }
      await expect(page.getByRole('row', { name: new RegExp(returnedReason) })).toHaveCount(0);
    },
  );

  test(
    'the maker sees the item returned on W-02 with the reason, and nothing was mapped',
    // SC-01-0052, SC-01-0062 (Module 01 numbering of a use case kb_review_01 maps to module 02 / 04: a comment, not an e2e_trace tag).
    { tag: ['@SRS-G-1', '@SRS-G-37', '@positive'] },
    async ({ page }) => {
      test.skip(returned === 0, 'Needs the proposal of the first test.');
      await workAs(page, 'en', FINANCE_HQ, financeHq, 'W-02');
      const mine = await onSomePage(
        page,
        page.getByRole('row').filter({ has: page.locator('td b', { hasText: new RegExp(`^${returned}$`) }) }),
      );
      await expect(mine).toContainText(t('en', 'status.RETURNED'));
      await expect(mine).toContainText(returnedReason);
      // SRS l.596 / SRS-G-29: beside the maker's own text, the Checker's return reason and who
      // returned it.
      const decided = page.getByTestId(`w02-decision-${returned}`);
      await expect(decided).toContainText(`${t('en', 'w02.returnReason')}: ${returnNote}`);
      await expect(decided).toContainText(CHECKER_HQ);
      await expect(page.getByRole('columnheader', { name: t('en', 'w02.checkerDecision') })).toBeVisible();
      await workAs(page, 'en', FINANCE_HQ, financeHq, 'M-24');
      const row = page
        .getByRole('row')
        .filter({ has: page.locator('td b', { hasText: new RegExp(`^${PAY_CODE}$`) }) });
      await expect(row).toContainText(t('en', 'mst.notMapped'));
      // Decided, the record takes a new proposal; it is rejected at once, by the checker.
      const again = await propose(page, runDay(2), `E2E W-01 after return ${stamp}`);
      expect(again.status).toBe(202);
      await workAs(page, 'en', CHECKER_HQ, checkerHq, 'W-01');
      expect(await decision(page, again.approvalId ?? 0, 'REJECT', 'E2E: clean-up')).toEqual({
        status: 200,
        errorCode: null,
      });

      // SC-01-0062 (SRS l.596): the returned item is marked differently from a rejected one: the
      // rejected item shows Rejected and carries no return reason; the returned one keeps both.
      await workAs(page, 'en', FINANCE_HQ, financeHq, 'W-02');
      const rejected = await onSomePage(
        page,
        page
          .getByRole('row')
          .filter({ has: page.locator('td b', { hasText: new RegExp(`^${again.approvalId}$`) }) }),
      );
      await expect(rejected).toContainText(t('en', 'status.REJECTED'));
      await expect(rejected).not.toContainText(t('en', 'status.RETURNED'));
      await expect(page.getByTestId(`w02-decision-${again.approvalId}`)).not.toContainText(
        t('en', 'w02.returnReason'),
      );
      await expect(page.getByTestId(`w02-decision-${again.approvalId}`)).toContainText(CHECKER_HQ);
    },
  );

  test(
    'the maker of an item cannot decide it, even by a forged call',
    { tag: ['@SRS-G-1', '@SC-02-0019', '@negative'] },
    async ({ page }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      // The seeded HQ-only DA version (V008_1), made by su.hq, who also holds Rule Approver.
      const own = sqlRows(
        "SELECT approval_id FROM approval_queue WHERE entity_name = 'rule_version' AND entity_key = 'DA-2026-07' AND status = 'PENDING_APPROVAL'",
      )[0]?.[0];
      test.skip(own === undefined, 'The seeded DA-2026-07 item is no longer pending.');
      await workAs(page, 'en', 'su.hq@fci-demo.test', { role: 'RULE_APPROVER', office: 'HQ001' }, 'W-01');
      for (const choice of ['APPROVE', 'REJECT']) {
        expect(await decision(page, Number(own), choice, 'E2E: my own'), choice).toEqual({
          status: 403,
          errorCode: 'WF-403-SOD',
        });
      }
      expect(sqlRows(`SELECT status FROM approval_queue WHERE approval_id = ${own}`)[0]?.[0]).toBe(
        'PENDING_APPROVAL',
      );
    },
  );
});
