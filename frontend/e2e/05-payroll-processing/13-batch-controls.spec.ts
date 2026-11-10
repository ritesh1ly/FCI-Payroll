import { expect, type Page, test } from '@playwright/test';
import { apiProblem, decide, sessionPeriod, shiftPeriod, t, workAs } from '../support/app';
import { TEST_CHECKER, TEST_MAKER, TEST_OFFICE, testChecker, testMaker } from '../support/cycle-office';

/**
 * C-13 job controls through the UI (batch campaign, owner 2026-10-30): the priority a job type
 * allows (SRS-NF-11, DR-17), the commands a finished job refuses with their reason, and the
 * restart of a failed job as a new attempt. Each run uses its own reasons, so it is re-runnable.
 * Node loss, pause / resume of long runs and Chaos Monkey faults need the volume stack:
 * scripts/resilience/batch-campaign.mjs.
 *
 * The dry runs go to the cycle test office (OD-141, support/cycle-office.ts), as 02-batch-jobs: DH001's
 * earlier months are locked demo months (the batch node refuses a run on them: CYCLE_LOCKED) and its current
 * month is the evaluators' (OD-150); the test office's current and previous months belong to
 * 03-payroll-cycle, so the completed dry run takes the month two before the current one.
 */
const MAKER = TEST_MAKER;
const maker = testMaker;
const CHECKER = TEST_CHECKER;
const checker = testChecker;
const OFFICE = `DO — Delhi Test Office (E2E only) (${TEST_OFFICE})`;
const COMMANDS = ['PAUSE', 'RESUME', 'STOP', 'RESTART'] as const;

async function openSubmit(page: Page) {
  await page.getByRole('tab', { name: t('en', 'c13.tab.submit') }).click();
  await expect(page.locator('#job-type')).toBeVisible();
}

/** Submits a dry run on the form; returns the job id from the toast. */
async function submitDryRun(page: Page, period: string, reason: string): Promise<number> {
  await openSubmit(page);
  await page.locator('#job-type').selectOption('PAYROLL_DRY_RUN');
  await page.locator('#job-locationId').selectOption({ label: OFFICE });
  await page.locator('#job-payPeriod').fill(period);
  await page.locator('#job-reason').fill(reason);
  await page.getByRole('button', { name: t('en', 'c13.submit.review') }).click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: t('en', 'c13.submit.confirm') })
    .click();
  const toast = page.getByRole('status');
  await expect(toast).toContainText(/Job \d+ submitted/);
  return Number(/Job (\d+)/.exec((await toast.textContent()) ?? '')?.[1]);
}

/** The job as the session's own API shows it, once it reaches one of {@code states}. */
async function waitForJob(page: Page, id: number, states: string[]) {
  let job = { status: '', attemptNo: 0, priority: 0 };
  await expect
    .poll(
      async () => {
        job = await page.evaluate(async (jobId) => {
          const r = await fetch(`/nginx/fci-payroll/v1/jobs/${jobId}`, { credentials: 'same-origin' });
          return ((await r.json()) as { job: { status: string; attemptNo: number; priority: number } }).job;
        }, id);
        return states.includes(job.status);
      },
      { timeout: 90_000, intervals: [2_000] },
    )
    .toBe(true);
  return job;
}

async function openJob(page: Page, id: number) {
  await page.getByRole('button', { name: t('en', 'c13.open', [id]) }).click();
  await expect(page.getByRole('heading', { name: new RegExp(`Job ${id}`) })).toBeVisible();
}

const command = (page: Page, id: number, name: (typeof COMMANDS)[number]) =>
  page
    .getByRole('group', { name: t('en', 'c13.actionsFor', [id]) })
    .getByRole('button', { name: t('en', `c13.command.${name}`) });

test.describe.serial('Batch job controls (C-13)', () => {
  const stamp = Date.now();
  let done = 0;
  let failed = 0;

  test(
    'SRS-NF-11: the form offers the job type’s priority and only lower ones',
    { tag: ['@SRS-NF-11', '@edge'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'C-13');
      await openSubmit(page);
      await page.locator('#job-type').selectOption('PAYROLL_DRY_RUN');
      const priority = page.locator('#job-priority');
      await expect(priority).toHaveValue('2');
      await expect(priority.locator('option')).toHaveText([
        t('en', 'c13.priority.2'),
        t('en', 'c13.priority.1'),
      ]);
      await expect(page.locator('#job-priority-hint')).toHaveText(t('en', 'c13.priority.hint'));
      await page.locator('#job-type').selectOption('PAYROLL_COMPUTATION');
      await expect(priority).toHaveValue('9');
      await expect(priority.locator('option')).toHaveCount(6);
      // A priority outside 1..9 is refused before anything is queued.
      const outOfRange = await apiProblem(page, '/v1/jobs', 'POST', {
        jobType: 'PAYROLL_DRY_RUN',
        params: { locationId: '999999', payPeriod: '2026-01' },
        priority: 10,
        reason: 'e2e priority out of range',
      });
      expect([400, 422]).toContain(outOfRange.status);
    },
  );

  test(
    'SRS-NF-2: a completed dry run, queued at its type’s priority, refuses every command and says why',
    { tag: ['@SRS-NF-2', '@SRS-NF-11', '@negative'] },
    async ({ browser }) => {
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'C-13');
      const reason = `E2E controls done ${stamp}`;
      done = await submitDryRun(page, shiftPeriod(await sessionPeriod(page), -2), reason);
      const c = await browser.newPage();
      await workAs(c, 'en', CHECKER, checker, 'W-01');
      await decide(c, new RegExp(reason), 'APPROVE');
      const job = await waitForJob(page, done, ['COMPLETED', 'FAILED']);
      expect(job.status).toBe('COMPLETED');
      expect(job.priority).toBe(2);
      await page.reload();
      await openJob(page, done);
      for (const name of COMMANDS) {
        await expect(command(page, done, name)).toBeDisabled();
        await expect(command(page, done, name)).toHaveAttribute('title', /Completed|COMPLETED/);
      }
      for (const path of ['pause', 'resume', 'stop', 'restart']) {
        expect(await apiProblem(page, `/v1/jobs/${done}/${path}`, 'POST', { reason: 'forged' })).toEqual({
          status: 409,
          errorCode: 'JOB-409-STATE',
        });
      }
    },
  );

  test(
    'SRS-15-26: a failed dry run is restarted from the screen as a new attempt',
    { tag: ['@SRS-15-26', '@SRS-15-25', '@positive'] },
    async ({ browser }) => {
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'C-13');
      const reason = `E2E controls failed ${stamp}`;
      // A period the payroll calendar does not hold fails at the batch node (as 05-batch-kill-switch).
      failed = await submitDryRun(page, '2025-03', reason);
      const c = await browser.newPage();
      await workAs(c, 'en', CHECKER, checker, 'W-01');
      await decide(c, new RegExp(reason), 'APPROVE');
      const first = await waitForJob(page, failed, ['COMPLETED', 'FAILED']);
      expect(first.status).toBe('FAILED');
      await page.reload();
      await openJob(page, failed);
      await expect(command(page, failed, 'PAUSE')).toBeDisabled();
      await expect(command(page, failed, 'RESTART')).toBeEnabled();
      await command(page, failed, 'RESTART').click();
      const dialog = page.getByRole('dialog');
      await dialog.getByRole('textbox').fill(`${reason} restart`);
      await dialog.getByRole('button', { name: t('en', 'c13.command.RESTART') }).click();
      await expect(page.getByRole('status')).toContainText(
        t('en', 'c13.commandSent', [t('en', 'c13.command.RESTART'), failed]),
      );
      // The restart is a second attempt of the same job, which ends again (the period is still missing).
      await expect
        .poll(
          async () => {
            const j = await waitForJob(page, failed, ['FAILED', 'COMPLETED']);
            return j.attemptNo > first.attemptNo;
          },
          { timeout: 120_000, intervals: [3_000] },
        )
        .toBe(true);
    },
  );
});
