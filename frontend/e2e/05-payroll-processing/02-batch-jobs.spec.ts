import { expect, type Page, test } from '@playwright/test';
import { apiStatus, decide, sessionPeriod, shiftPeriod, t, workAs } from '../support/app';
import { TEST_CHECKER, TEST_MAKER, TEST_OFFICE, testChecker, testMaker } from '../support/cycle-office';

/**
 * C-13 Batch Jobs through the UI against the deployed stack (online + batch instance): validation on
 * the form, maker-checker on every job, the duplicate guard, the batch instance running the job, and
 * the cycle rules. Each run uses its own reason (and period where it matters), so it is re-runnable.
 *
 * The dry run goes to the cycle test office (OD-141, support/cycle-office.ts), two months before the
 * current one: DH001's months up to the previous one are locked demo months (V082_22), on which the batch
 * node refuses a run (CYCLE_LOCKED; PENDING CYC-SIM-LOCKED), and its current month is the evaluators'
 * (OD-150). The test office's current month is the one 03-payroll-cycle drives and its previous month the
 * one that spec needs locked (SRS-15-33), so neither is touched: the dry run of the earlier month opens
 * its cycle once (INITIATED) and finds it open on every later run.
 */
const MAKER = TEST_MAKER;
const CHECKER = TEST_CHECKER;
const OFFICE = `DO — Delhi Test Office (E2E only) (${TEST_OFFICE})`;

/** The dry run's month: two before the session's current month (never a fixed date). */
async function dryRunPeriod(page: Page): Promise<string> {
  return shiftPeriod(await sessionPeriod(page), -2);
}

async function openSubmit(page: Page) {
  await page.getByRole('tab', { name: t('en', 'c13.tab.submit') }).click();
  await expect(page.locator('#job-type')).toBeVisible();
}

async function fillDryRun(page: Page, period: string, reason: string) {
  await page.locator('#job-type').selectOption('PAYROLL_DRY_RUN');
  await page.locator('#job-locationId').selectOption({ label: OFFICE });
  await page.locator('#job-payPeriod').fill(period);
  await page.locator('#job-reason').fill(reason);
}

/** Polls the job (the maker's own API view) until it reaches one of {@code states}. */
async function waitForJob(page: Page, id: number, states: string[]): Promise<string> {
  let status = '';
  await expect
    .poll(
      async () => {
        status = await page.evaluate(async (jobId) => {
          const r = await fetch(`/nginx/fci-payroll/v1/jobs/${jobId}`, { credentials: 'same-origin' });
          return r.ok ? ((await r.json()) as { job: { status: string } }).job.status : `HTTP ${r.status}`;
        }, id);
        return states.includes(status);
      },
      { timeout: 90_000, intervals: [2_000] },
    )
    .toBe(true);
  return status;
}

test.describe.serial('Batch jobs (C-13)', () => {
  const reason = `E2E dry run ${Date.now()}`;
  let jobId = 0;

  test(
    'the form refuses a missing reason and a period after the current one, before any call',
    { tag: ['@SRS-NF-2', '@SRS-NF-3', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, testMaker, 'C-13');
      await openSubmit(page);
      await page.locator('#job-type').selectOption('PAYROLL_DRY_RUN');
      // The form pre-fills the maker's only unit and the current period; the reason is still missing.
      await expect(page.locator('#job-locationId')).toHaveValue(/\d+/);
      await page.getByRole('button', { name: t('en', 'c13.submit.review') }).click();
      await expect(page.getByRole('alert').filter({ hasText: t('en', 'c13.error.required') })).toBeVisible();
      await expect(page.getByRole('dialog')).toHaveCount(0);
      await fillDryRun(page, shiftPeriod(await sessionPeriod(page), 1), 'e2e: future period');
      await page.getByRole('button', { name: t('en', 'c13.submit.review') }).click();
      await expect(page.locator('#job-payPeriod-err')).toHaveText(t('en', 'c13.error.future'));
      await expect(page.getByRole('dialog')).toHaveCount(0);
    },
  );

  test(
    'the maker submits a dry run; it waits for a checker and the maker cannot approve it',
    { tag: ['@SRS-NF-2', '@SRS-G-1', '@SC-05-0007', '@SC-05-0111', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, testMaker, 'C-13');
      await openSubmit(page);
      await fillDryRun(page, await dryRunPeriod(page), reason);
      await page.getByRole('button', { name: t('en', 'c13.submit.review') }).click();
      await page
        .getByRole('dialog')
        .getByRole('button', { name: t('en', 'c13.submit.confirm') })
        .click();
      const toast = page.getByRole('status');
      await expect(toast).toContainText(/Job \d+ submitted/);
      jobId = Number(/Job (\d+)/.exec((await toast.textContent()) ?? '')?.[1]);
      expect(jobId).toBeGreaterThan(0);
      await expect(page.getByRole('heading', { name: new RegExp(`Job ${jobId}`) })).toBeVisible();
      // SRS 14.4 lists the maker on X-02 to read (OD-125): the own item with nothing to decide, and
      // the decision endpoint refuses (same identity can never decide).
      await page.goto('/payroll/?s=W-01');
      await expect(page.getByRole('button', { name: t('en', 'w01.approveSelected') })).toBeDisabled();
      const item = await page.evaluate(async (id) => {
        const r = await fetch(`/nginx/fci-payroll/v1/jobs/${id}`, { credentials: 'same-origin' });
        return ((await r.json()) as { job: { approvalId: number; status: string } }).job;
      }, jobId);
      expect(item.status).toBe('AWAITING_APPROVAL');
      expect(
        await apiStatus(page, `/v1/approvals/${item.approvalId}/decision`, 'POST', { decision: 'APPROVE' }),
      ).toBe(403);
    },
  );

  test(
    'the same job twice is refused while the first is open',
    { tag: ['@SRS-EH-7', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, testMaker, 'C-13');
      await openSubmit(page);
      await fillDryRun(page, await dryRunPeriod(page), `${reason} again`);
      await page.getByRole('button', { name: t('en', 'c13.submit.review') }).click();
      await page
        .getByRole('dialog')
        .getByRole('button', { name: t('en', 'c13.submit.confirm') })
        .click();
      await expect(page.getByText(t('en', 'error.JOB-409-QUEUED'))).toBeVisible();
    },
  );

  test(
    'the checker approves; the batch instance runs it to completion',
    { tag: ['@SRS-NF-2', '@SRS-G-1', '@SC-05-0115', '@positive'] },
    async ({ browser }) => {
      const checker = await browser.newPage();
      await workAs(checker, 'en', CHECKER, testChecker, 'W-01');
      await decide(checker, new RegExp(reason), 'APPROVE');

      const maker = await browser.newPage();
      await workAs(maker, 'en', MAKER, testMaker, 'C-13');
      expect(await waitForJob(maker, jobId, ['COMPLETED', 'FAILED'])).toBe('COMPLETED');
      await maker.reload();
      await maker.getByRole('button', { name: t('en', 'c13.open', [jobId]) }).click();
      await expect(maker.getByText(/SIMULATION run \d+: members read \d+/)).toBeVisible();
    },
  );

  test(
    'a checker cannot submit jobs of another office it does not hold',
    { tag: ['@SRS-G-29', '@negative'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, testMaker, 'C-13');
      const mumbai = await page.evaluate(async () => {
        const r = await fetch('/nginx/fci-payroll/v1/jobs/types', { credentials: 'same-origin' });
        const d = (await r.json()) as { locations: { id: number; code: string }[] };
        return d.locations.map((l) => l.code);
      });
      expect(mumbai).not.toContain('MR001');
      // Nor the office of the demo Delhi users (DH001): a test office's Maker holds its own office only.
      expect(mumbai).not.toContain('DH001');
      // Forging another office's id: refused by the server's scope check.
      expect(
        await apiStatus(page, '/v1/jobs', 'POST', {
          jobType: 'PAYROLL_DRY_RUN',
          params: { locationId: '999999', payPeriod: await dryRunPeriod(page) },
          reason: 'e2e forged unit',
        }),
      ).toBeGreaterThanOrEqual(400);
    },
  );
});
