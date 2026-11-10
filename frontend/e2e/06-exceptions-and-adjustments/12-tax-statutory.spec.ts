import { expect, test } from '@playwright/test';
import {
  apiStatus,
  businessToday,
  decide,
  finYearOf,
  pageHref,
  previousFinYear,
  t,
  workAs,
} from '../support/app';
import { apiJson, expectToast } from '../support/payroll-support';

/**
 * C-07, M-17, C-10, C-11 and the member's E-01 certificates through the UI (docs/plan/08-income-tax.md
 * phases 08e-08h). In a deployment the engines' earnings, the posting interface and the prescribed
 * utility are not integrated yet (PENDING 08-ports), so the screens must say what is missing and
 * never compute on an assumption; M-17 runs end to end: the tax Rule Administrator proposes a
 * version, a different Rule Approver approves it in the Approvals Inbox, the output is Active.
 * Users of the hierarchy seed V039_11_9. Re-runnable: each run proposes a version after the latest
 * one and imports a challan of its own.
 */
const MAKER = 'tax.maker.delhi@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };
const RULE_ADMIN = 'tax.ruleadmin@fci-demo.test';
const ruleAdmin = { role: 'RULE_ADMINISTRATOR', office: 'HQ001' };
const RULE_APPROVER = 'tax.ruleapprover@fci-demo.test';
const OUTPUT = 'FORM_12BB';
const RUN = `E2E ${Date.now()}`;

interface Output {
  key: string;
  versions: { effectiveFrom: string }[];
}

/** A translated message as a pattern: an argument given as '#' matches any number. */
function message(key: string, args: string[] = []): RegExp {
  const escaped = t('en', key, args).replace(/[.*+?^${}()|[\]\\]/g, (c) => `\\${c}`);
  return new RegExp(escaped.replaceAll('#', String.raw`\d+`));
}

/** The day after the latest version of the output, and never before today. */
function nextStart(latest: string | undefined): string {
  const today = businessToday();
  if (!latest || latest < today) return today;
  const d = new Date(`${latest}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
}

/** Today's quarter of the financial year (April to June is Q1). */
function quarterToday(): string {
  const month = Number(businessToday().slice(5, 7));
  return `Q${Math.floor(((month + 8) % 12) / 3) + 1}`;
}

test.describe.serial('Module 08 statutory computation and outputs', () => {
  test(
    'SRS-7-26: C-07 names what is missing for the year and never computes on an assumption',
    { tag: ['@SRS-7-26', '@SRS-7-27', '@SRS-7-12', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'C-07');
      // The frontend logs the call (the E2E build logs at debug) with the member's EIS as :id.
      const logged: string[] = [];
      page.on('console', (m) => logged.push(m.text()));
      await page.getByLabel(t('en', 'm01.eis')).fill('870001');
      await page.getByRole('button', { name: t('en', 'c07.compute'), exact: true }).click();
      const reasons = page.getByRole('list', { name: t('en', 'c07.notComputed') });
      // What is missing depends on whether payroll computed the member this year (module 16 phase 16e-1: the
      // staff results give the earnings): no earnings yet, or then the demo year's unapproved slab (M-18).
      await expect(reasons).toBeVisible();
      const missing = (await reasons.textContent()) ?? '';
      expect(
        [
          t('en', 'error.TAX-422-EARNINGS-NOT-AVAILABLE'),
          t('en', 'error.TAX-422-RATE-NOT-CONFIGURED', ['TAX.SLAB.OLD']),
        ].some((reason) => missing.includes(reason)),
        missing,
      ).toBe(true);
      // The approved inputs of the demo year are assembled all the same.
      await expect(page.getByRole('table', { name: t('en', 'c07.deductions') })).toContainText('80C');
      await expect(page.getByLabel(t('en', 'c07.result'))).toHaveCount(0);
      const forecastLog = logged.filter((l) => l.includes('/v1/tax/forecasts/'));
      if (forecastLog.length > 0) {
        expect(forecastLog.join('\n')).toContain('/v1/tax/forecasts/:id');
        expect(forecastLog.join('\n')).not.toContain('870001');
      }
      // A member of another office reads as missing; the eSeva member has no C-07.
      expect(await apiStatus(page, '/v1/tax/forecasts/870004')).toBe(404);
      await workAs(page, 'en', 'tax.old@fci-demo.test', { role: 'EMPLOYEE_ESEVA' }, 'E-01');
      expect(await apiStatus(page, '/v1/tax/forecasts/870001')).toBe(403);
      // E-01: no certificate is released while payroll provides no earnings.
      await expect(page.getByText(t('en', 'e01f.none'))).toBeVisible();
    },
  );

  // SC-08-0031 (scenario of a module after 07: cited, not tagged)
  test(
    'BR-MIS-002: a version is proposed at HQ, approved by a different Rule Approver and the output is Active',
    { tag: ['@BR-MIS-002', '@BR-TAX-013', '@SRS-G-1', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-17');
      const catalogue = await apiJson<Output[]>(page, '/v1/tax/output-definitions');
      const output = catalogue.find((o) => o.key === OUTPUT) as Output;
      const from = nextStart(output.versions[0]?.effectiveFrom);
      await page.getByRole('button', { name: t('en', 'm17.openOf', [OUTPUT]) }).click();
      await page.getByLabel(t('en', 'm17.effectiveFrom')).fill(from);
      await page.getByLabel(t('en', 'm17.layoutFormat'), { exact: true }).selectOption('PDF');
      await page.getByLabel(t('en', 'm17.labelOf', [1])).fill('Form');
      await page.getByLabel(t('en', 'm17.bindingOf', [1])).selectOption('LITERAL');
      await page.getByLabel(t('en', 'm17.refOf', [1])).fill('Form 12BB');
      await page.getByLabel(t('en', 'common.reason')).fill(RUN);
      await page.getByRole('button', { name: t('en', 'common.submitForApproval') }).click();
      await expectToast(page, t('en', 'm17.proposed'));
      await expect(page.getByText(message('m17.waiting', ['#']))).toBeVisible();

      await workAs(page, 'en', RULE_APPROVER, { role: 'RULE_APPROVER', office: 'HQ001' }, 'W-01');
      await decide(page, new RegExp(RUN), 'APPROVE');

      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-17');
      // The list and its editor stay in the closed register (OD-121); the drawn table above repeats the rows.
      const table = page.getByTestId('m17-register').getByRole('table', { name: t('en', 'screen.M-17') });
      // The first run's version starts today; a later run's ends the one before at the day before,
      // which still governs today.
      await expect(table.locator(`tr[data-reference="${OUTPUT}"]`)).toContainText(
        t('en', 'm17.state.ACTIVE'),
      );
      // An output nobody versioned stays blocked.
      await expect(table.locator('tr[data-reference="PT_SCHEDULE"]')).toContainText(
        t('en', 'm17.state.BLOCKED'),
      );
      await page.getByRole('button', { name: t('en', 'm17.openOf', [OUTPUT]) }).click();
      await expect(page.getByRole('table', { name: message('m17.fieldsOf', ['#']) }).first()).toContainText(
        'Form 12BB',
      );
    },
  );

  test(
    'SRS M-17: a control total added by mistake is removed and no longer blocks the proposal',
    { tag: ['@BR-TAX-013', '@BR-MIS-002', '@edge'] },
    async ({ page }) => {
      await workAs(page, 'en', RULE_ADMIN, ruleAdmin, 'M-17');
      const catalogue = await apiJson<Output[]>(page, '/v1/tax/output-definitions');
      const output = catalogue.find((o) => o.key === OUTPUT) as Output;
      await page.getByRole('button', { name: t('en', 'm17.openOf', [OUTPUT]) }).click();
      await page.getByLabel(t('en', 'm17.effectiveFrom')).fill(nextStart(output.versions[0]?.effectiveFrom));
      await page.getByLabel(t('en', 'm17.layoutFormat'), { exact: true }).selectOption('PDF');
      await page.getByLabel(t('en', 'm17.labelOf', [1])).fill('Form');
      await page.getByLabel(t('en', 'm17.bindingOf', [1])).selectOption('LITERAL');
      await page.getByLabel(t('en', 'm17.refOf', [1])).fill('Form 12BB');
      await page.getByLabel(t('en', 'common.reason')).fill(`${RUN} controls`);
      const submit = page.getByRole('button', { name: t('en', 'common.submitForApproval') });
      await expect(submit).toBeEnabled();
      const addControl = page.getByRole('button', { name: t('en', 'm17.addControl') });
      await addControl.click();
      await addControl.click();
      await page.getByLabel(t('en', 'm17.controlLabel'), { exact: true }).first().fill('TDS');
      // The second, empty row blocks the proposal.
      await expect(submit).toBeDisabled();
      await expect(page.getByRole('list', { name: t('en', 'common.problems') })).toContainText(
        t('en', 'm17.badLabel'),
      );
      await page.getByRole('button', { name: t('en', 'm17.removeControl', ['2']) }).click();
      await expect(page.getByLabel(t('en', 'm17.controlLabel'), { exact: true })).toHaveCount(1);
      await expect(page.getByLabel(t('en', 'm17.controlLabel'), { exact: true })).toHaveValue('TDS');
      await expect(submit).toBeEnabled();
      // The last row can be removed too: a version need not reconcile a control total.
      await page.getByRole('button', { name: t('en', 'm17.removeControl', ['1']) }).click();
      await expect(page.getByLabel(t('en', 'm17.controlLabel'), { exact: true })).toHaveCount(0);
      await expect(submit).toBeEnabled();
    },
  );

  test(
    'SRS-7-9: Form 16 is never generated for a year that is not locked; the Checker only reads',
    { tag: ['@SRS-7-9', '@SRS-7-21', '@SRS-7-8', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'C-10');
      await page.getByLabel(t('en', 'i07.finYear')).selectOption(previousFinYear());
      // The member population: an entry that is not an EIS stops the generation.
      await page.getByLabel(t('en', 'c10.members')).fill('870001, 87-0003');
      await expect(page.getByRole('button', { name: t('en', 'c10.generate'), exact: true })).toBeDisabled();
      await page.getByLabel(t('en', 'c10.members')).fill('870001');
      await page.getByRole('button', { name: t('en', 'c10.generate'), exact: true }).click();
      await expect(page.getByText(message('error.TAX-409-PERIOD-NOT-LOCKED', ['#']))).toBeVisible();
      await workAs(
        page,
        'en',
        'tax.checker.delhi@fci-demo.test',
        { role: 'PAYROLL_CHECKER', office: 'DH001' },
        'C-10',
      );
      await expect(page.getByRole('button', { name: t('en', 'c10.generate'), exact: true })).toHaveCount(0);
      await expect(page.getByRole('button', { name: t('en', 'c10.release'), exact: true })).toHaveCount(0);
    },
  );

  test(
    'SRS-7-21: the challan file is imported once and a 24Q is refused for an unlocked quarter',
    { tag: ['@SRS-7-21', '@SRS-7-7', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'C-11');
      const year = finYearOf(businessToday());
      await page.getByLabel(t('en', 'i07.finYear')).selectOption(year);
      const cin = `CIN${Date.now() % 1_000_000_000}`;
      const quarter = quarterToday();
      const file = {
        name: 'challans.csv',
        mimeType: 'text/csv',
        buffer: Buffer.from(`${cin},0510308,${businessToday()},1000.00,UTRE2E,${quarter}`),
      };
      await page.getByLabel(t('en', 'c11.challanFile')).setInputFiles(file);
      await page.getByRole('button', { name: t('en', 'c11.import') }).click();
      await expectToast(page, t('en', 'c11.imported', [1]));
      await expect(page.getByRole('table', { name: t('en', 'c11.challans') })).toContainText(cin);
      // The same file again: the challan is held, passed over and listed.
      await page.getByLabel(t('en', 'c11.challanFile')).setInputFiles(file);
      await page.getByRole('button', { name: t('en', 'c11.import') }).click();
      await expect(page.getByLabel(t('en', 'c11.duplicates'))).toContainText(cin);
      // Today's quarter is not locked: no return on an unlocked period.
      await page.getByLabel(t('en', 'c11.quarter')).last().selectOption(quarter);
      await page.getByRole('button', { name: t('en', 'c11.generate'), exact: true }).click();
      await expect(page.getByText(message('error.TAX-409-PERIOD-NOT-LOCKED', ['#']))).toBeVisible();
      // Another paying unit sees none of these challans.
      await workAs(
        page,
        'en',
        'tax.maker.kolkata@fci-demo.test',
        { role: 'PAYROLL_MAKER', office: 'KL001' },
        'C-11',
      );
      const challans = await apiJson<{ cin: string }[]>(page, `/v1/tax/challans?finYear=${year}`);
      expect(challans.map((c) => c.cin)).not.toContain(cin);
    },
  );

  test(
    'SRS-NF-7: the 24Q of the whole unit runs in the background through C-13; an unlocked quarter opens nothing',
    { tag: ['@SRS-NF-7', '@SRS-NF-2', '@SRS-7-7', '@negative'] },
    async ({ page, browser }) => {
      await workAs(page, 'en', MAKER, maker, 'C-11');
      const year = finYearOf(businessToday());
      const quarter = quarterToday();
      await page.getByLabel(t('en', 'i07.finYear')).selectOption(year);
      await expect(page.getByText(t('en', 'c11.backgroundHint'))).toBeVisible();
      await page.getByLabel(t('en', 'c11.quarter')).last().selectOption(quarter);
      await page.getByRole('link', { name: t('en', 'tax.runInBackground') }).click();
      // C-13 opens on its submit form with the job, the year and the quarter; the unit is the Maker's.
      await expect(page).toHaveURL(/s=C-13&job=TAX_24Q_RETURN/);
      await expect(page.locator('#job-type')).toHaveValue('TAX_24Q_RETURN');
      await expect(page.locator('#job-finYear')).toHaveValue(year);
      await expect(page.locator('#job-quarter')).toHaveValue(quarter);
      await expect(page.locator('#job-locationId')).toHaveValue(/\d+/);
      const reason = `${RUN} 24Q in background`;
      await page.locator('#job-reason').fill(reason);
      await page.getByRole('button', { name: t('en', 'c13.submit.review') }).click();
      await page
        .getByRole('dialog')
        .getByRole('button', { name: t('en', 'c13.submit.confirm') })
        .click();
      const toast = page.getByRole('status');
      await expect(toast).toContainText(/Job \d+ submitted/);
      const jobId = Number(/Job (\d+)/.exec((await toast.textContent()) ?? '')?.[1]);

      const checker = await browser.newPage();
      await workAs(
        checker,
        'en',
        'tax.checker.delhi@fci-demo.test',
        { role: 'PAYROLL_CHECKER', office: 'DH001' },
        'W-01',
      );
      await decide(checker, new RegExp(reason), 'APPROVE');
      await checker.close();

      // The batch instance refuses the run at its opening: the quarter is not locked.
      await expect
        .poll(
          async () => (await apiJson<{ job: { status: string } }>(page, `/v1/jobs/${jobId}`)).job.status,
          { timeout: 90_000, intervals: [2_000] },
        )
        .toBe('FAILED');
      const returns = await apiJson<{ items: { quarter: string; correction: boolean }[] }>(
        page,
        `/v1/tax/returns?finYear=${year}&size=100`,
      );
      expect(returns.items.filter((r) => r.quarter === quarter && !r.correction)).toHaveLength(0);
    },
  );

  test(
    'SRS-NF-7: C-10 offers the whole paying unit in the background, a chosen population stays on screen',
    { tag: ['@SRS-NF-7', '@SRS-7-9', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'C-10');
      const year = previousFinYear();
      await page.getByLabel(t('en', 'i07.finYear')).selectOption(year);
      await expect(page.getByRole('link', { name: t('en', 'tax.runInBackground') })).toHaveAttribute(
        'href',
        pageHref(`/payroll?s=C-13&job=TAX_FORM16_PART_B&finYear=${year}`),
      );
      await page.getByLabel(t('en', 'c10.members')).fill('870001');
      await expect(page.getByRole('button', { name: t('en', 'tax.runInBackground') })).toBeDisabled();
    },
  );
});
