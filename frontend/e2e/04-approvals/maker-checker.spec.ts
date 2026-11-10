import { expect, test } from '@playwright/test';
import { decide, otpFromLog, pressDecision, t, workAs } from '../support/app';
import { dbFixtureAvailable, NO_DB_FIXTURE, sqlRows } from '../support/db';
import { fillOrder } from '../support/payroll-support';

const WASHING = 'Washing Allowance';

/**
 * Maker-checker through the UI: Establishment / HR proposes an allowance flag, it is pending and
 * not in force; the Delhi checker approves it; the maker can never approve; an assignment needs
 * the administrator's OTP and the checker's OTP.
 */
test.describe.serial('Maker-checker approvals (W-01)', () => {
  test(
    'an allowance flag reaches the employee only after the checker approves it',
    { tag: ['@SRS-G-1', '@SRS-1-11', '@SC-01-0001', '@SC-02-0052', '@positive'] },
    async ({ browser }) => {
      test.skip(!dbFixtureAvailable(), NO_DB_FIXTURE);
      // A Payroll-owned field: contact and bank details are HRMS-owned and change only through I-21
      // (plan 16 D16-19). Re-runnable: the opposite of the member's latest Washing version, from the
      // day after it, ten years ahead at the earliest so no cycle of the suites computes it (Washing
      // has no computation rule yet: a member flagged Y would not be computed, PENDING 16-allowances).
      const [latest, after, today] = (
        sqlRows(
          `SELECT (SELECT f.flag_value FROM employee_eligibility_flag f JOIN employee e ON e.employee_id = f.employee_id WHERE e.emp_num = '106958' AND f.flag_code = 'WASHING' ORDER BY f.effective_from DESC FETCH FIRST 1 ROWS ONLY) AS latest, (SELECT TO_CHAR(MAX(f.effective_from) + 1, 'YYYY-MM-DD') FROM employee_eligibility_flag f JOIN employee e ON e.employee_id = f.employee_id WHERE e.emp_num = '106958' AND f.flag_code = 'WASHING') AS after_day, TO_CHAR(CURRENT_DATE + 3650, 'YYYY-MM-DD') AS earliest_day FROM dual`,
        )[0] ?? []
      ).map((c) => (c === 'null' ? null : c));
      const value = latest === 'Y' ? 'N' : 'Y';
      const from = after !== null && String(after) > String(today) ? String(after) : String(today);
      const reason = `E2E maker-checker WASHING ${value} from ${from}`;
      const hr = await browser.newPage();
      await workAs(hr, 'en', 'hr.delhi@fci-demo.test', { role: 'ESTABLISHMENT_HR', office: 'DH001' }, 'M-01');
      await hr.getByLabel(t('en', 'wf.searchInList')).fill('106958');
      await hr
        .getByRole('row', { name: /106958/ })
        .getByRole('button', { name: /View record/ })
        .click();
      const flags = hr.getByRole('table', { name: t('en', 'm01.flags.title') });
      await flags.getByRole('button', { name: t('en', 'm01.flags.change', [WASHING]) }).click();
      const form = hr.getByRole('form', { name: t('en', 'm01.flags.change', [WASHING]) });
      await form.getByLabel(t('en', 'm01.flags.requested')).selectOption(value);
      await form.getByLabel(t('en', 'm01.flags.from')).fill(from);
      await form.getByLabel(t('en', 'common.reason')).fill(reason);
      await fillOrder(hr, form);
      await form.getByRole('button', { name: t('en', 'm01.flags.submit') }).click();
      await expect(hr.getByRole('status').filter({ hasText: WASHING })).toBeVisible();
      await expect(flags.getByRole('row', { name: new RegExp(WASHING) })).toContainText(
        t('en', 'm01.flags.pending'),
      );

      const checker = await browser.newPage();
      await workAs(
        checker,
        'en',
        'ddo.delhi@fci-demo.test',
        { role: 'PAYROLL_CHECKER', office: 'DH001' },
        'W-01',
      );
      await decide(checker, new RegExp(`WASHING [YN] -> ${value} from ${from}`), 'APPROVE');

      await hr.reload();
      await hr.getByLabel(t('en', 'wf.searchInList')).fill('106958');
      await hr
        .getByRole('row', { name: /106958/ })
        .getByRole('button', { name: /View record/ })
        .click();
      await expect(hr.getByRole('table', { name: t('en', 'm01.flags.history') })).toContainText(reason);
    },
  );

  test(
    'the maker cannot decide: no inbox, and the HQ-only item shows its reason',
    { tag: ['@SC-02-0019', '@SRS-G-1', '@SRS-G-29', '@SC-01-0027', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', 'su.hq@fci-demo.test', { role: 'RULE_APPROVER', office: 'HQ001' }, 'W-01');
      const own = page.getByRole('row', { name: /DA-2026-07/ });
      // As drawn (OD-121): no ✔ on a row the session may not decide; the detail shows the decisions
      // disabled with the reason.
      await expect(own.getByRole('button', { name: '✔' })).toHaveCount(0);
      await own.getByRole('button', { name: t('en', 'wf.view') }).click();
      const detail = page.getByRole('dialog').first();
      await expect(detail.getByRole('button', { name: t('en', 'w01.APPROVE') })).toBeDisabled();
      await expect(detail.getByRole('button', { name: t('en', 'w01.APPROVE') })).toHaveAttribute(
        'title',
        t('en', 'error.WF-403-SOD'),
      );
    },
  );

  test(
    'an access assignment needs the administrator OTP and the checker OTP, and is revoked with a reason',
    { tag: ['@SRS-G-21', '@SRS-G-3', '@SRS-G-37', '@SC-01-0003', '@SC-01-0021', '@SC-02-0024', '@positive'] },
    async ({ browser }) => {
      // A from-date of its own per run: (user, group, from) is unique, so the spec re-runs on one stack.
      const from = new Date(Date.now() + (2 + (Math.floor(Date.now() / 60_000) % 3000)) * 86_400_000)
        .toISOString()
        .slice(0, 10);
      // A-04 shows dates as the screens do (UI_BMAD_g1 #81: en-IN "08 May 2032").
      const shown = new Date(`${from}T00:00:00Z`).toLocaleDateString('en-IN', {
        day: '2-digit',
        month: 'short',
        year: 'numeric',
        timeZone: 'UTC',
      });
      const ours = new RegExp(`su.pension@fci-demo.test.*AG-FIN-MR001.*${shown}`);
      const admin = await browser.newPage();
      await workAs(
        admin,
        'en',
        'sysadmin@fci-demo.test',
        { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' },
        'A-04',
      );
      await admin.getByRole('button', { name: t('en', 'action.newAssignment') }).click();
      // The user is found on the server by login or name (SRS-NF-3: never the whole register).
      await admin.getByLabel(t('en', 'a04.findUser')).fill('su.pension');
      const user = admin.getByLabel(t('en', 'a04.user'), { exact: true });
      await expect(user.getByRole('option')).toHaveText(['su.pension@fci-demo.test — Kavita Joshi']);
      await user.selectOption({ label: 'su.pension@fci-demo.test — Kavita Joshi' });
      await admin
        .getByLabel(t('en', 'a04.group'))
        .selectOption({ label: 'AG-FIN-MR001 — Finance - Mumbai Regional' });
      await admin.getByLabel(t('en', 'a04.from'), { exact: true }).fill(from);
      await admin.getByLabel(t('en', 'common.reason')).fill('Finance cover, e2e');
      const since = Date.now();
      await admin.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await admin
        .getByRole('textbox', { name: t('en', 'otp.code') })
        .fill(await otpFromLog('ROLE_ACCESS_GROUP_CHANGE', since));
      await admin.getByRole('button', { name: t('en', 'otp.verify') }).click();
      await expect(admin.getByRole('status')).toContainText(t('en', 'a04.submitted'));
      await expect(admin.getByRole('row', { name: ours })).toContainText(t('en', 'a04.inert'));

      // SRS-22-23, FRS V0.5 §28 (US-GAP-14): an assignment is checked at the Zonal Office above the
      // group's office (MR001 is under ZO-W), never at the group's own RO / DO.
      const checker = await browser.newPage();
      await workAs(
        checker,
        'en',
        'tax.checker.west@fci-demo.test',
        { role: 'PAYROLL_CHECKER', office: 'ZO-W' },
        'W-01',
      );
      const reasonDialog = await pressDecision(
        checker,
        checker.getByRole('row', { name: /su.pension@fci-demo.test\|AG-FIN-MR001/ }).first(),
        'APPROVE',
      );
      const decided = Date.now();
      await reasonDialog.getByRole('button', { name: t('en', 'w01.APPROVE') }).click();
      await checker
        .getByRole('textbox', { name: t('en', 'otp.code') })
        .fill(await otpFromLog('ROLE_ACCESS_GROUP_CHANGE', decided));
      await checker.getByRole('button', { name: t('en', 'otp.verify') }).click();
      await expect(checker.getByRole('status')).toContainText(t('en', 'status.APPROVED'));

      // Approved, but not in force before its from-date.
      await admin.reload();
      const row = admin.getByRole('row', { name: ours });
      await expect(row).toContainText(t('en', 'status.APPROVED'));
      await expect(row).toContainText(t('en', 'a04.inert'));

      // Revoked at once with a reason; the row stays on record.
      await row.getByRole('button', { name: t('en', 'action.revoke') }).click();
      const dialog = admin.getByRole('dialog');
      await dialog.getByRole('textbox').fill('e2e clean-up');
      await dialog.getByRole('button').last().click();
      await expect(admin.getByRole('status')).toContainText(
        t('en', 'a04.revoked', ['su.pension@fci-demo.test']),
      );
      await expect(admin.getByRole('row', { name: ours })).toContainText(/Revoked/);
      await expect(
        admin.getByRole('row', { name: ours }).getByRole('button', { name: t('en', 'action.revoke') }),
      ).toHaveCount(0);
    },
  );
});
