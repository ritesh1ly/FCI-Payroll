import { expect, type Page, test } from '@playwright/test';
import { apiProblem, apiStatus, decide, t, workAs } from '../support/app';
import { dbFixtureAvailable, sql, sqlRows } from '../support/db';

/**
 * Plan 05 phase 05j (SRS-15-25, SRS-15-27; owner decision D-5): a failed job nobody wants to continue
 * is abandoned for good under maker-checker, and the kill switch - a System Administrator at
 * Headquarters halts all batch processing at once; nothing is resumed or restarted until a Payroll
 * Checker at Headquarters approves the release.
 *
 * The failed jobs are real: a dry run for a period the payroll calendar (M-10) does not hold fails at
 * the batch node (NO_PAYROLL_CALENDAR). Re-runnable: abandoned and failed jobs are not open, so the
 * same periods can be submitted again; the halt is released at the end, and by the database fixture
 * should a step fail before that.
 */
const MAKER = 'maker.delhi@fci-demo.test';
const maker = { role: 'PAYROLL_MAKER', office: 'DH001' };
const CHECKER = 'ddo.delhi@fci-demo.test';
const checker = { role: 'PAYROLL_CHECKER', office: 'DH001' };
const HQ_ADMIN = 'sysadmin@fci-demo.test';
const hqAdmin = { role: 'SYSTEM_ADMINISTRATOR', office: 'HQ001' };
const ZONAL_ADMIN = 'sysadm.na11@fci-demo.test';
const zonalAdmin = { role: 'SYSTEM_ADMINISTRATOR', office: 'NA11' };
const HQ_CHECKER = 'checker.hq@fci-demo.test';
const hqChecker = { role: 'PAYROLL_CHECKER', office: 'HQ001' };
const DH001 = 'DO — Delhi HQ (DH001)';
const COMMANDS = ['PAUSE', 'RESUME', 'STOP', 'RESTART', 'ABANDON'] as const;

/** Submits a dry run on the C-13 form; returns the job id from the toast. */
async function submitDryRun(page: Page, period: string, reason: string): Promise<number> {
  await page.getByRole('tab', { name: t('en', 'c13.tab.submit') }).click();
  await page.locator('#job-type').selectOption('PAYROLL_DRY_RUN');
  await page.locator('#job-locationId').selectOption({ label: DH001 });
  await page.locator('#job-payPeriod').fill(period);
  await page.locator('#job-reason').fill(reason);
  await page.getByRole('button', { name: t('en', 'c13.submit.review') }).click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: t('en', 'c13.submit.confirm') })
    .click();
  const toast = page.getByRole('status');
  await expect(toast).toContainText(/Job \d+ submitted/);
  const id = Number(/Job (\d+)/.exec((await toast.textContent()) ?? '')?.[1]);
  expect(id).toBeGreaterThan(0);
  return id;
}

/** Polls the job (the session's own API view) until it reaches one of {@code states}. */
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

async function openJob(page: Page, id: number) {
  await page.getByRole('button', { name: t('en', 'c13.open', [id]) }).click();
  await expect(page.getByRole('heading', { name: new RegExp(`Job ${id}`) })).toBeVisible();
}

const actions = (page: Page, id: number) =>
  page.getByRole('group', { name: t('en', 'c13.actionsFor', [id]) });
const command = (page: Page, id: number, name: (typeof COMMANDS)[number]) =>
  actions(page, id).getByRole('button', { name: t('en', `c13.command.${name}`) });
const banner = (page: Page) => page.getByTestId('batch-halt');

test.describe.serial('Batch kill switch and abandon (C-13, plan 05j)', () => {
  const stamp = Date.now();
  const abandonReason = `E2E abandon ${stamp}`;
  const holdReason = `E2E hold ${stamp}`;
  let toAbandon = 0;
  let toHold = 0;

  test.afterAll(() => {
    // Never leave the stack halted for the specs that follow.
    if (dbFixtureAvailable()) sql('DELETE FROM batch_halt WHERE released_at IS NULL');
  });

  test(
    'two dry runs for a period the payroll calendar does not hold fail at the batch node',
    { tag: ['@SRS-15-25', '@SRS-G-1', '@positive'] },
    async ({ browser }) => {
      const page = await browser.newPage();
      await workAs(page, 'en', MAKER, maker, 'C-13');
      toAbandon = await submitDryRun(page, '2025-01', abandonReason);
      await workAs(page, 'en', MAKER, maker, 'C-13');
      toHold = await submitDryRun(page, '2025-02', holdReason);

      const c = await browser.newPage();
      await workAs(c, 'en', CHECKER, checker, 'W-01');
      await decide(c, new RegExp(abandonReason), 'APPROVE');
      await decide(c, new RegExp(holdReason), 'APPROVE');

      expect(await waitForJob(page, toAbandon, ['COMPLETED', 'FAILED'])).toBe('FAILED');
      expect(await waitForJob(page, toHold, ['COMPLETED', 'FAILED'])).toBe('FAILED');
    },
  );

  test(
    'SRS-15-27: the maker proposes to abandon the failed job; meanwhile nothing else is done with it',
    { tag: ['@SRS-15-27', '@SRS-G-37', '@positive'] },
    async ({ page }) => {
      await workAs(page, 'en', MAKER, maker, 'C-13');
      await openJob(page, toAbandon);
      await expect(command(page, toAbandon, 'RESTART')).toBeEnabled();
      await expect(command(page, toAbandon, 'ABANDON')).toBeEnabled();

      await command(page, toAbandon, 'ABANDON').click();
      const dialog = page.getByRole('dialog');
      await expect(dialog.getByText(t('en', 'c13.abandon.note'))).toBeVisible();
      await expect(dialog.getByRole('button', { name: t('en', 'c13.command.ABANDON') })).toBeDisabled();
      await dialog.getByRole('textbox').fill(`${abandonReason} for good`);
      await dialog.getByRole('button', { name: t('en', 'c13.command.ABANDON') }).click();
      await expect(page.getByRole('status')).toContainText(t('en', 'c13.abandon.requested', [toAbandon]));

      await expect(page.getByText(t('en', 'c13.abandonPending'))).toBeVisible();
      await expect(command(page, toAbandon, 'RESTART')).toBeDisabled();
      await expect(command(page, toAbandon, 'RESTART')).toHaveAttribute(
        'title',
        t('en', 'c13.why.abandonPending'),
      );
      expect(await apiProblem(page, `/v1/jobs/${toAbandon}/restart`, 'POST', { reason: 'forged' })).toEqual({
        status: 409,
        errorCode: 'JOB-409-ABANDON-PENDING',
      });
      expect(await apiProblem(page, `/v1/jobs/${toAbandon}/abandon`, 'POST', { reason: 'again' })).toEqual({
        status: 409,
        errorCode: 'WF-409-PENDING-BY',
      });
    },
  );

  test(
    'SRS-G-1: the checker approves; the batch node ends the job for good and nothing continues it',
    { tag: ['@SRS-15-27', '@SRS-G-1', '@positive'] },
    async ({ browser }) => {
      const c = await browser.newPage();
      await workAs(c, 'en', CHECKER, checker, 'W-01');
      await decide(c, new RegExp(`${abandonReason} for good`), 'APPROVE');

      const m = await browser.newPage();
      await workAs(m, 'en', MAKER, maker, 'C-13');
      expect(await waitForJob(m, toAbandon, ['ABANDONED'])).toBe('ABANDONED');
      await m.reload();
      await openJob(m, toAbandon);
      for (const name of COMMANDS) await expect(command(m, toAbandon, name)).toBeDisabled();
      await expect(m.getByText(t('en', 'c13.status.ABANDONED')).first()).toBeVisible();
      expect(await apiProblem(m, `/v1/jobs/${toAbandon}/restart`, 'POST', { reason: 'forged' })).toEqual({
        status: 409,
        errorCode: 'JOB-409-STATE',
      });
      // Spring Batch agrees: its execution is abandoned, so it would refuse a restart itself.
      if (dbFixtureAvailable()) {
        expect(
          sqlRows(
            `SELECT e.status FROM BATCH_JOB_EXECUTION e JOIN batch_job_request r ON r.job_execution_id = e.job_execution_id WHERE r.job_request_id = ${toAbandon}`,
          )[0]?.[0],
        ).toBe('ABANDONED');
      }
    },
  );

  test(
    'SRS-15-25: only the System Administrator at Headquarters holds the kill switch',
    { tag: ['@SRS-15-25', '@SRS-G-25', '@negative'] },
    async ({ browser }) => {
      const m = await browser.newPage();
      await workAs(m, 'en', MAKER, maker, 'C-13');
      await expect(m.getByRole('button', { name: t('en', 'c13.halt.button') })).toHaveCount(0);
      expect(await apiStatus(m, '/v1/jobs/halt', 'POST', { reason: 'forged' })).toBe(403);

      const z = await browser.newPage();
      await workAs(z, 'en', ZONAL_ADMIN, zonalAdmin, 'C-13');
      const halt = z.getByRole('button', { name: t('en', 'c13.halt.button') });
      await expect(halt).toBeDisabled();
      await expect(halt).toHaveAttribute('title', t('en', 'error.AUTHZ-403-HQ-ONLY'));
      expect(await apiProblem(z, '/v1/jobs/halt', 'POST', { reason: 'Zonal panic' })).toEqual({
        status: 403,
        errorCode: 'AUTHZ-403-HQ-ONLY',
      });
    },
  );

  test(
    'SRS-15-25: the halt takes effect at once; nothing is resumed or restarted; the release is approved at Headquarters',
    { tag: ['@SRS-15-25', '@SRS-15-27', '@SRS-G-1', '@edge'] },
    async ({ browser }) => {
      const a = await browser.newPage();
      await workAs(a, 'en', HQ_ADMIN, hqAdmin, 'C-13');
      await a.getByRole('button', { name: t('en', 'c13.halt.button') }).click();
      const dialog = a.getByRole('dialog');
      await expect(dialog.getByText(t('en', 'c13.halt.note'))).toBeVisible();
      await dialog.getByRole('textbox').fill(`${holdReason} halt`);
      await dialog.getByRole('button', { name: t('en', 'c13.halt.button') }).click();
      await expect(a.getByRole('status')).toContainText(t('en', 'c13.halt.done'));
      await expect(banner(a)).toContainText(t('en', 'c13.halt.title'));
      await expect(banner(a)).toContainText(HQ_ADMIN);
      expect(await apiProblem(a, '/v1/jobs/halt', 'POST', { reason: 'again' })).toEqual({
        status: 409,
        errorCode: 'JOB-409-ALREADY-HALTED',
      });

      // The maker sees the banner; the failed job offers no Restart, says why, and still Abandon.
      const m = await browser.newPage();
      await workAs(m, 'en', MAKER, maker, 'C-13');
      await expect(banner(m)).toBeVisible();
      await openJob(m, toHold);
      await expect(command(m, toHold, 'RESTART')).toBeDisabled();
      await expect(command(m, toHold, 'RESTART')).toHaveAttribute('title', t('en', 'c13.why.halted'));
      await expect(command(m, toHold, 'ABANDON')).toBeEnabled();
      expect(await apiProblem(m, `/v1/jobs/${toHold}/restart`, 'POST', { reason: 'forged' })).toEqual({
        status: 409,
        errorCode: 'JOB-409-HALTED',
      });

      // The release is a proposal: the halt stays until a Payroll Checker at Headquarters approves.
      await a.getByRole('button', { name: t('en', 'c13.halt.release') }).click();
      const release = a.getByRole('dialog');
      await expect(release.getByText(t('en', 'c13.halt.releaseNote'))).toBeVisible();
      await release.getByRole('textbox').fill(`${holdReason} release`);
      await release.getByRole('button', { name: t('en', 'c13.halt.release') }).click();
      await expect(a.getByRole('status')).toContainText(t('en', 'c13.halt.releaseRequested'));
      const pending = t('en', 'c13.halt.releasePending', [HQ_ADMIN, '§']).split('§')[0] ?? '';
      await expect(banner(a)).toContainText(pending);
      await expect(a.getByRole('button', { name: t('en', 'c13.halt.release') })).toHaveCount(0);
      expect(await apiProblem(m, `/v1/jobs/${toHold}/restart`, 'POST', { reason: 'forged' })).toEqual({
        status: 409,
        errorCode: 'JOB-409-HALTED',
      });
      const delhi = await browser.newPage();
      await workAs(delhi, 'en', CHECKER, checker, 'W-01');
      await expect(delhi.getByRole('row', { name: new RegExp(`${holdReason} release`) })).toHaveCount(0);

      const c = await browser.newPage();
      await workAs(c, 'en', HQ_CHECKER, hqChecker, 'W-01');
      await decide(c, new RegExp(`${holdReason} release`), 'APPROVE');

      await m.reload();
      await expect(banner(m)).toHaveCount(0);
      await openJob(m, toHold);
      await expect(command(m, toHold, 'RESTART')).toBeEnabled();
      await a.reload();
      await expect(a.getByRole('button', { name: t('en', 'c13.halt.button') })).toBeEnabled();
    },
  );
});
