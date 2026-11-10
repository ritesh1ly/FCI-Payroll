import { expect, type Page, test } from '@playwright/test';
import { apiProblem, apiStatus, decide, t, workAs } from '../support/app';
import { dbFixtureAvailable, sqlRows } from '../support/db';

/**
 * M-04 Staff Rate Tables, M-22 Special Compensatory & Allowance Rate Masters and M-23 Other
 * Parameters (SRS-11-15: effective-dated versions; SRS-G-1 maker-checker; review 2026-10-24, A2).
 * The Rule Administrator at Headquarters proposes a dated version, a Payroll Checker at HQ decides;
 * a version starts after the one in force and on a date no other version holds; each screen
 * maintains only its own catalogue; a rate line is a code, a value and a unit (a percentage at most
 * 100). Nothing in force is changed: the approved version starts in 2200 (a date of this run), the
 * M-23 proposal is rejected, and every other attempt is refused.
 */
const RULE_ADMIN = 'su.hq@fci-demo.test';
const ruleAdmin = { role: 'RULE_ADMINISTRATOR', office: 'HQ001' };
const HQ_CHECKER = 'checker.hq@fci-demo.test';
const hqChecker = { role: 'PAYROLL_CHECKER', office: 'HQ001' };

/** A far-future date of this run (one day per minute), plus {@code offset} days. */
function runDay(offset: number): string {
  const minute = Math.floor(Date.now() / 60_000) % 50_000;
  return new Date(Date.UTC(2200, 0, 1) + (minute * 3 + offset) * 86_400_000).toISOString().slice(0, 10);
}

const line = (over: Record<string, unknown> = {}) => ({
  designationId: null,
  categoryCode: null,
  payPattern: null,
  basicSlabFrom: null,
  basicSlabTo: null,
  cityClass: null,
  rateCode: 'HCA_AMT',
  rateValue: 1500,
  unit: 'INR',
  ...over,
});

// The rate sets, parameters and their editors stay in the closed register of each drawn screen (OD-121).
const register = (page: Page) => page.locator('details[data-testid$="-register"]');
const openRegister = async (page: Page) => {
  if (!(await register(page).evaluate((d) => (d as HTMLDetailsElement).open)))
    await register(page).locator('summary').click();
};
const row = (page: Page, code: string) =>
  register(page).getByRole('row', { name: new RegExp(`\\b${code}\\b`) });

test.describe.serial('Rate masters M-04 / M-22 / M-23', () => {
  const stamp = Date.now();
  const approvedReason = `E2E hill allowance ${stamp}`;
  const rejectedReason = `E2E NPA ${stamp}`;
  const day = runDay(0);

  test(
    'M-22: an allowance without a version in force gets a dated version under maker-checker; the date is then taken',
    { tag: ['@SRS-11-15', '@SRS-G-1', '@SRS-G-37', '@SRS-G-38', '@positive'] },
    async ({ browser }) => {
      const admin = await browser.newPage();
      await workAs(admin, 'en', RULE_ADMIN, ruleAdmin, 'M-22');
      await openRegister(admin);
      const hill = row(admin, 'HILL_IDA');
      // No version in force today (earlier runs only add versions from 2200 on).
      await expect(hill).toContainText(t('en', 'mst.notSeeded'));
      await hill.getByRole('button', { name: t('en', 'mst.newVersion'), exact: true }).click();
      const submit = admin.getByRole('button', { name: t('en', 'common.submitForApproval') });
      await admin.locator('#mst-from').fill(day);
      await register(admin).getByLabel(t('en', 'mst.rateCode')).fill('HCA_AMT');
      await register(admin).getByLabel(t('en', 'mst.value')).fill('1500');
      await register(admin).getByLabel(t('en', 'mst.unit')).selectOption('INR');
      await expect(submit).toBeDisabled();
      await admin.locator('#mst-reason').fill(approvedReason);
      // SRS l.2819 / SRS-G-38 (US-GAP-211): an M-22 rate change links its Active circular.
      await expect(submit).toBeDisabled();
      await admin.locator('#mst-circular').selectOption({ index: 1 });
      await expect(submit).toBeEnabled();
      await submit.click();
      await expect(admin.locator('.fl-toast')).toContainText(t('en', 'mst.submitted', ['']).slice(0, 2));
      // Data-independent: an earlier run of this spec on the same stack left approved 2200
      // versions, so the new one is v1 on a fresh stack and vN after it.
      await expect(hill).toContainText(new RegExp(t('en', 'mst.pendingVersion', ['\\d+', '.+'])));

      const checker = await browser.newPage();
      await workAs(checker, 'en', HQ_CHECKER, hqChecker, 'W-01');
      await decide(checker, new RegExp(approvedReason), 'APPROVE');

      await admin.reload();
      await openRegister(admin);
      // Approved for 2200: not in force today, so the list still says so; the history has it.
      await expect(hill).toContainText(t('en', 'mst.notSeeded'));
      await hill.getByRole('button', { name: t('en', 'mst.history'), exact: true }).click();
      const history = admin.locator('.card').filter({
        has: admin.getByRole('heading', { name: t('en', 'mst.historyOf', ['HILL_IDA']) }),
      });
      await expect(history.getByRole('row').filter({ hasText: t('en', 'status.APPROVED') })).not.toHaveCount(
        0,
      );
      // Approved, it is the latest version: another one must start after it (not merely on
      // another day), so the same day is refused as "not after", the earlier rule of the two.
      expect(
        await apiProblem(admin, '/v1/masters/special-rates/HILL_IDA/versions', 'POST', {
          effectiveFrom: day,
          lines: [line()],
          reason: 'E2E same day',
        }),
      ).toEqual({ status: 422, errorCode: 'MST-422-DATE' });
      // SRS l.2819 / SRS-G-38 (US-GAP-211): a later M-22 version without its Active circular is refused.
      expect(
        await apiProblem(admin, '/v1/masters/special-rates/HILL_IDA/versions', 'POST', {
          effectiveFrom: runDay(2),
          lines: [line()],
          reason: 'E2E no circular',
        }),
      ).toEqual({ status: 422, errorCode: 'MST-422-CIRCULAR-REQUIRED' });
    },
  );

  test(
    'M-04: a version starts after the one in force; lines are codes, values and units; each screen keeps its own catalogue',
    { tag: ['@SRS-11-15', '@SRS-G-37', '@SRS-1-62', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-04');
      await openRegister(page);
      const path = '/v1/masters/staff-rates/HRA/versions';
      // M-04 field table (SRS l.3653): a staff rate line names its pay pattern.
      const hra = { rateCode: 'HRA_PCT', rateValue: 30, unit: 'PERCENT', cityClass: 'X', payPattern: 'IDA' };
      for (const effectiveFrom of ['2026-04-01', '2025-01-01']) {
        expect(
          await apiProblem(page, path, 'POST', { effectiveFrom, lines: [line(hra)], reason: 'E2E early' }),
          effectiveFrom,
        ).toEqual({ status: 422, errorCode: 'MST-422-DATE' });
      }
      expect(
        await apiProblem(page, path, 'POST', {
          effectiveFrom: runDay(1),
          lines: [line({ ...hra, rateValue: 101 })],
          reason: 'E2E over 100 percent',
        }),
      ).toEqual({ status: 422, errorCode: 'MST-422-LINES' });
      // US-GAP-107 (SRS-G-17): a rate the column NUMBER(14,4) cannot hold exactly is refused, never rounded.
      expect(
        await apiProblem(page, path, 'POST', {
          effectiveFrom: runDay(1),
          lines: [line({ ...hra, rateValue: 12.34567 })],
          reason: 'E2E five decimals',
        }),
      ).toEqual({ status: 422, errorCode: 'MST-422-LINES' });
      expect(
        await apiProblem(page, path, 'POST', { effectiveFrom: runDay(1), lines: [], reason: 'E2E no lines' }),
      ).toEqual({ status: 400, errorCode: 'COM-400-VALIDATION' });
      expect(
        await apiProblem(page, '/v1/masters/staff-rates/NO_SUCH_SET/versions', 'POST', {
          effectiveFrom: runDay(1),
          lines: [line(hra)],
          reason: 'E2E unknown',
        }),
      ).toEqual({ status: 404, errorCode: 'MST-404-CATALOGUE' });
      // The M-22 screen never maintains an M-04 rate set, nor the other way round.
      expect(
        await apiProblem(page, '/v1/masters/special-rates/HRA/versions', 'POST', {
          effectiveFrom: runDay(1),
          lines: [line(hra)],
          reason: 'E2E wrong screen',
        }),
      ).toEqual({ status: 404, errorCode: 'MST-404-CATALOGUE' });
      // SRS-1-62: a staff rate revision links its Active circular.
      expect(
        await apiProblem(page, path, 'POST', {
          effectiveFrom: runDay(1),
          lines: [line(hra)],
          reason: 'E2E no circular',
        }),
      ).toEqual({ status: 422, errorCode: 'MST-422-CIRCULAR-REQUIRED' });

      // The form will not send a percentage above 100, nor a revision without its circular.
      await row(page, 'HRA')
        .getByRole('button', { name: t('en', 'mst.newVersion'), exact: true })
        .click();
      const submit = page
        .getByTestId('m04-register')
        .getByRole('button', { name: t('en', 'common.submitForApproval') });
      await page.locator('#mst-from').fill(runDay(1));
      await page.locator('#mst-reason').fill('E2E guard');
      await expect(submit).toBeDisabled();
      await page.locator('#mst-circular').selectOption({ index: 1 });
      // M-04: every line names its pay pattern before the revision is sent.
      const patterns = register(page).getByLabel(t('en', 'mst.pattern'));
      for (let i = 0; i < (await patterns.count()); i++) await patterns.nth(i).selectOption('IDA');
      await expect(submit).toBeEnabled();
      await register(page).getByLabel(t('en', 'mst.value')).first().fill('101');
      await expect(submit).toBeDisabled();
      await page.getByRole('button', { name: t('en', 'common.cancel') }).click();
    },
  );

  test(
    'M-04 field table: a cleared rate value is never sent as zero and every line needs its pay pattern; the server refuses the same',
    { tag: ['@SRS-11-15', '@SRS-1-62', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-04');
      await openRegister(page);
      const path = '/v1/masters/staff-rates/HRA/versions';
      const hra = { rateCode: 'HRA_PCT', rateValue: 30, unit: 'PERCENT', cityClass: 'X' };
      // A line without a pay pattern (missing or blank) is refused before anything is stored.
      expect(
        await apiProblem(page, path, 'POST', {
          effectiveFrom: runDay(2),
          lines: [line({ ...hra, payPattern: null })],
          reason: 'E2E no pay pattern',
          circularOrderId: 1,
        }),
      ).toEqual({ status: 422, errorCode: 'MST-422-LINES' });
      // A blank pattern is refused by the request's own validation first.
      expect(
        await apiProblem(page, path, 'POST', {
          effectiveFrom: runDay(2),
          lines: [line({ ...hra, payPattern: ' ' })],
          reason: 'E2E blank pay pattern',
          circularOrderId: 1,
        }),
      ).toEqual({ status: 400, errorCode: 'COM-400-VALIDATION' });
      // An empty pattern is no pattern: the rule refuses it.
      expect(
        await apiProblem(page, path, 'POST', {
          effectiveFrom: runDay(2),
          lines: [line({ ...hra, payPattern: '' })],
          reason: 'E2E empty pay pattern',
          circularOrderId: 1,
        }),
      ).toEqual({ status: 422, errorCode: 'MST-422-LINES' });
      // A blank value (null) is refused, never read as zero.
      expect(
        await apiProblem(page, path, 'POST', {
          effectiveFrom: runDay(2),
          lines: [line({ ...hra, payPattern: 'IDA', rateValue: null })],
          reason: 'E2E blank value',
          circularOrderId: 1,
        }),
      ).toEqual({ status: 400, errorCode: 'COM-400-VALIDATION' });

      await row(page, 'HRA')
        .getByRole('button', { name: t('en', 'mst.newVersion'), exact: true })
        .click();
      const submit = page
        .getByTestId('m04-register')
        .getByRole('button', { name: t('en', 'common.submitForApproval') });
      await page.locator('#mst-from').fill(runDay(2));
      await page.locator('#mst-reason').fill('E2E blank value');
      await page.locator('#mst-circular').selectOption({ index: 1 });
      const patterns = register(page).getByLabel(t('en', 'mst.pattern'));
      for (let i = 0; i < (await patterns.count()); i++) await patterns.nth(i).selectOption('IDA');
      await expect(submit).toBeEnabled();
      // Clearing the value blocks the proposal with the reason (it was sent as 0 before).
      const value = register(page).getByLabel(t('en', 'mst.value')).first();
      await value.fill('');
      await expect(value).toHaveValue('');
      await expect(submit).toBeDisabled();
      await expect(submit).toHaveAttribute('title', t('en', 'mst.why.valueRequired'));
      await expect(page.getByTestId('mst-lines-why')).toHaveText(t('en', 'mst.why.valueRequired'));
      // Zero typed is a value; the pattern cleared on one line is the next reason.
      await value.fill('0');
      await patterns.first().selectOption('');
      await expect(submit).toHaveAttribute('title', t('en', 'mst.why.patternRequired'));
      await patterns.first().selectOption('CDA');
      await expect(submit).toBeEnabled();
      await expect(page.getByTestId('mst-lines-why')).toHaveCount(0);
      // An added line starts blank, never at zero.
      await page.getByRole('button', { name: t('en', 'mst.addLine') }).click();
      await expect(register(page).getByLabel(t('en', 'mst.value')).last()).toHaveValue('');
      await expect(submit).toBeDisabled();
      await page.getByRole('button', { name: t('en', 'common.cancel') }).click();
    },
  );

  test(
    'M-04: the drawn DA form validates, asks the circular and reason, and waits for the Checker',
    { tag: ['@SRS-1-62', '@SRS-G-1', '@SRS-G-54', '@positive'] },
    async ({ browser }) => {
      const reason = `E2E DA form ${stamp}`;
      // A run stopped before its Checker step leaves its proposal waiting, which refuses the next one.
      const left = dbFixtureAvailable()
        ? sqlRows(
            `SELECT approval_id FROM approval_queue WHERE entity_key = 'DA_CDA' AND status = 'PENDING_APPROVAL'`,
          )
        : [];
      if (left.length > 0) {
        const clean = await browser.newPage();
        await workAs(clean, 'en', HQ_CHECKER, hqChecker, 'W-01');
        for (const [id] of left) {
          const status = await apiStatus(clean, `/v1/approvals/${id}/decision`, 'POST', {
            decision: 'REJECT',
            reason: 'E2E: an earlier run stopped here',
          });
          expect(status).toBe(200);
        }
        await clean.close();
      }
      const admin = await browser.newPage();
      await workAs(admin, 'en', RULE_ADMIN, ruleAdmin, 'M-04');
      const save = admin.getByRole('button', { name: t('en', 'm04.form.save') });
      await save.click();
      await expect(admin.getByText(t('en', 'm04.err.pattern'))).toBeVisible();
      await admin.locator('#da_pattern').selectOption('CDA');
      await admin.locator('#da_rate').fill('101');
      await save.click();
      await expect(admin.getByText(t('en', 'm04.err.rate'))).toBeVisible();
      await admin.locator('#da_rate').fill('48');
      await admin.locator('#da_basis').fill('Basic + NPA');
      await admin.locator('#da_eff').fill(runDay(3));
      await save.click();
      const dialog = admin.getByRole('dialog');
      const submit = dialog.getByRole('button', { name: t('en', 'common.submitForApproval') });
      await expect(submit).toBeDisabled();
      await dialog.getByLabel(t('en', 'mst.circular')).selectOption({ index: 1 });
      await dialog.getByLabel(t('en', 'common.reason')).fill(reason);
      await submit.click();
      await expect(admin.locator('.fl-toast')).toContainText(t('en', 'mst.submitted', ['']).slice(0, 2));
      await expect(
        admin.getByRole('cell', { name: t('en', 'm04.status.AWAITING_APPROVAL') }),
      ).not.toHaveCount(0);

      const checker = await browser.newPage();
      await workAs(checker, 'en', HQ_CHECKER, hqChecker, 'W-01');
      await decide(checker, new RegExp(reason), 'REJECT', 'E2E: no revision');
    },
  );

  test(
    'M-23: a parameter version waits for the checker; rejected, the value in force stays; a date not after it is refused',
    { tag: ['@SRS-11-15', '@SRS-G-1', '@SRS-G-37', '@edge'] },
    async ({ browser }) => {
      const admin = await browser.newPage();
      await workAs(admin, 'en', RULE_ADMIN, ruleAdmin, 'M-23');
      await openRegister(admin);
      const npa = row(admin, 'NPA_PCT');
      await expect(npa).toContainText('25');
      await npa.getByRole('button', { name: t('en', 'mst.newVersion'), exact: true }).click();
      const submit = admin.getByRole('button', { name: t('en', 'common.submitForApproval') });
      await admin.locator('#op-from').fill(runDay(2));
      await admin.locator('#op-pct').fill('26');
      await admin.locator('#op-conf').check();
      await expect(submit).toBeDisabled();
      await admin.locator('#op-reason').fill(rejectedReason);
      await expect(submit).toBeEnabled();
      await submit.click();
      await expect(admin.locator('.fl-toast')).toContainText(t('en', 'mst.submitted', ['']).slice(0, 2));

      const checker = await browser.newPage();
      await workAs(checker, 'en', HQ_CHECKER, hqChecker, 'W-01');
      await decide(checker, new RegExp(rejectedReason), 'REJECT', 'E2E: no circular for 26 percent');

      await admin.reload();
      await openRegister(admin);
      await expect(npa).toContainText('25');
      await npa.getByRole('button', { name: t('en', 'mst.history'), exact: true }).click();
      const history = admin.locator('.card').filter({
        has: admin.getByRole('heading', { name: t('en', 'mst.historyOf', ['NPA_PCT']) }),
      });
      await expect(history.getByRole('row').filter({ hasText: t('en', 'status.REJECTED') })).not.toHaveCount(
        0,
      );
      expect(
        await apiProblem(admin, '/v1/masters/other-parameters/NPA_PCT/versions', 'POST', {
          effectiveFrom: '2026-04-01',
          percentValue: 26,
          confirmed: true,
          reason: 'E2E not after',
        }),
      ).toEqual({ status: 422, errorCode: 'MST-422-DATE' });
      expect(
        await apiProblem(admin, '/v1/masters/other-parameters/NPA_PCT/versions', 'POST', {
          percentValue: 26,
          confirmed: true,
          reason: 'E2E no date',
        }),
      ).toEqual({ status: 400, errorCode: 'COM-400-VALIDATION' });
    },
  );
});
