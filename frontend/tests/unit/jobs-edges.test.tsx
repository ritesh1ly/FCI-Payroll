import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { jobQuery } from '@/modules/jobs/api/jobsApi';
import type { Halt, Job, JobDetail } from '@/modules/jobs/api/types';
import { BatchJobs } from '@/modules/jobs/components/BatchJobs';
import { JobApproval } from '@/modules/jobs/components/JobApproval';
import { JobHistory } from '@/modules/jobs/components/JobHistory';
import { SubmitJobForm } from '@/modules/jobs/components/SubmitJobForm';
import { backend, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * C-13 beyond the happy paths (SRS-15-25, SRS-15-27): a job awaiting a command, the sort order
 * reversed, an empty history, a rejection with its reason, a refused decision and a cancelled OTP,
 * an optional office in the submission review, a required office with none to choose, the tabs
 * and the detail of the batch screen, and the date filters of the history query.
 */

afterEach(() => vi.unstubAllGlobals());

const REFERENCE = referenceLists({
  JOB_STATUS: [
    ['RUNNING', 'Running'],
    ['PENDING', 'Pending'],
    ['AWAITING_APPROVAL', 'Awaiting approval'],
  ],
  JOB_TYPE: [['PAYROLL_DRY_RUN', 'Payroll dry run']],
});

const RUNNING: Halt = {
  engaged: false,
  haltId: null,
  haltedBy: null,
  haltedAt: null,
  reason: null,
  releaseApprovalId: null,
  releaseRequestedBy: null,
  releaseReason: null,
};

const job = (over: Partial<Job> = {}): Job => ({
  id: 12,
  jobType: 'PAYROLL_DRY_RUN',
  params: { locationId: '3', payPeriod: '2026-07' },
  priority: 5,
  status: 'RUNNING',
  source: 'UI',
  requestedBy: 'maker.delhi@fci-demo.test',
  locationId: 3,
  locationLabel: 'DO — Delhi HQ (DH001)',
  payPeriod: '2026-07',
  requestedAt: '2026-07-15T10:00:00Z',
  startedAt: '2026-07-15T10:00:05Z',
  finishedAt: null,
  durationSeconds: 65,
  attemptNo: 1,
  jobExecutionId: 4,
  approvalId: null,
  canDecide: false,
  readCount: 10,
  writeCount: 8,
  skipCount: 1,
  resultSummary: null,
  errorMessage: null,
  pendingCommand: null,
  allowedCommands: ['STOP', 'PAUSE'],
  ...over,
});

describe('C-13 job history without every particular', () => {
  it('a job awaiting a command says so; sorting the same column again reverses it; no jobs says so', async () => {
    let items = [job({ pendingCommand: 'STOP' })];
    const calls = backend({
      'GET /v1/jobs?': (url) => paged(items, items.length, url),
      'GET /v1/reference/lists': () => REFERENCE,
    });
    const { unmount } = wrap(<JobHistory onOpen={vi.fn()} />, ['C-13:VIEW']);
    expect(await screen.findByText(t('c13.commandPending'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: /^Status/ }));
    await waitFor(() => expect(calls.some((c) => c.url.includes('sort=status&direction=desc'))).toBe(true));
    await userEvent.click(screen.getByRole('button', { name: /^Status/ }));
    await waitFor(() => expect(calls.some((c) => c.url.includes('sort=status&direction=asc'))).toBe(true));
    const ascending = await screen.findByRole('button', { name: /^Status ▲/ });
    expect(ascending.closest('th')).toHaveAttribute('aria-sort', 'ascending');
    unmount();
    items = [];
    wrap(<JobHistory onOpen={vi.fn()} />, ['C-13:VIEW']);
    expect(await screen.findByText(t('c13.history.none'))).toBeTruthy();
  });

  it('from and to dates reach the history query', () => {
    expect(
      jobQuery({
        page: 0,
        size: 20,
        sort: 'requestedAt',
        direction: 'desc',
        from: '2026-07-01',
        to: '2026-07-31',
      }),
    ).toBe('from=2026-07-01&to=2026-07-31&page=0&size=20&sort=requestedAt&direction=desc');
  });
});

describe('C-13 job approval decisions', () => {
  it('a rejection carries its reason and is announced; a refused decision is a message; a cancelled OTP is nothing', async () => {
    let answer: unknown = { status: 'REJECTED' };
    const calls = backend({ 'POST /v1/approvals/77/decision': () => answer });
    wrap(
      <JobApproval
        job={job({ status: 'AWAITING_APPROVAL', approvalId: 77, canDecide: true, allowedCommands: [] })}
      />,
      ['C-13:VIEW', 'C-13:APPROVE'],
    );
    await userEvent.click(screen.getByRole('button', { name: t('c13.approval.reject') }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(t('c13.approval.confirmReject', [12]))).toBeTruthy();
    await userEvent.type(within(dialog).getByRole('textbox'), 'Wrong period');
    await userEvent.click(within(dialog).getByRole('button', { name: t('c13.approval.reject') }));
    expect(await screen.findByText(t('c13.approval.rejected', [12]))).toBeTruthy();
    expect(sent(calls, 'POST', '/decision').at(-1)).toEqual({ decision: 'REJECT', reason: 'Wrong period' });

    answer = problem(409, 'WF-409-DECIDED');
    await userEvent.click(screen.getByRole('button', { name: t('c13.approval.approve') }));
    await userEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: t('c13.approval.approve') }),
    );
    expect(await screen.findByText(t('error.WF-409-DECIDED'))).toBeTruthy();

    answer = problem(428, 'AUTH-428-OTP', ['7']);
    await userEvent.click(screen.getByRole('button', { name: t('c13.approval.approve') }));
    await userEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: t('c13.approval.approve') }),
    );
    const otp = await screen.findByRole('dialog', { name: t('otp.title') });
    await userEvent.click(within(otp).getByRole('button', { name: t('common.cancel') }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(screen.queryByText(t('error.AUTH-428-OTP', ['7']))).toBeNull();
  });
});

describe('C-13 submission without every particular', () => {
  it('an optional office is reviewed as given; a required office with none to choose is flagged on the field', async () => {
    let locationRequired = false;
    backend({
      'GET /v1/jobs/types': () => ({
        types: [
          {
            type: 'PAYROLL_DRY_RUN',
            params: [
              { name: 'locationId', kind: 'LOCATION', required: locationRequired },
              { name: 'payPeriod', kind: 'PERIOD', required: true },
            ],
          },
        ],
        currentPeriod: '2026-07',
        locations: [],
      }),
      'GET /v1/reference/lists': () => REFERENCE,
    });
    const { unmount } = wrap(<SubmitJobForm onSubmitted={vi.fn()} />, ['C-13:VIEW', 'C-13:CREATE']);
    const office = await screen.findByLabelText(t('c13.param.locationId'));
    expect(office).not.toHaveAttribute('aria-invalid', 'true');
    await userEvent.type(screen.getByLabelText(/Reason/), 'Monthly dry run');
    await userEvent.click(screen.getByRole('button', { name: t('c13.submit.review') }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(t('c13.param.locationId'))).toBeTruthy();
    unmount();

    locationRequired = true;
    wrap(<SubmitJobForm onSubmitted={vi.fn()} />, ['C-13:VIEW', 'C-13:CREATE']);
    const required = await screen.findByLabelText(`${t('c13.param.locationId')} *`);
    await userEvent.type(screen.getByLabelText(/Reason/), 'Monthly dry run');
    await userEvent.click(screen.getByRole('button', { name: t('c13.submit.review') }));
    await waitFor(() => expect(required).toHaveAttribute('aria-invalid', 'true'));
    expect(required).toHaveAttribute('aria-describedby', 'job-locationId-err');
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});

describe('C-13 batch screen views', () => {
  it('the tabs switch between the monitor and the submission; a job opens its detail and comes back', async () => {
    const detail: JobDetail = { job: job(), attempts: [], controls: [], audit: [], skippedTotal: 0 };
    backend({
      'GET /v1/jobs/halt': () => RUNNING,
      'GET /v1/jobs/12': () => detail,
      'GET /v1/jobs?': (url) => paged([job()], 1, url),
      'GET /v1/jobs/types': () => ({ types: [], currentPeriod: '2026-07', locations: [] }),
      'GET /v1/reference/lists': () => REFERENCE,
    });
    wrap(<BatchJobs />, ['C-13:VIEW', 'C-13:CREATE']);
    const monitor = await screen.findByRole('tab', { name: t('c13.tab.monitor') });
    const submit = screen.getByRole('tab', { name: t('c13.tab.submit') });
    expect(monitor).toHaveClass('pri');
    await userEvent.click(submit);
    expect(submit).toHaveClass('pri');
    expect(monitor).not.toHaveClass('pri');
    await userEvent.click(monitor);
    await userEvent.click(await screen.findByRole('button', { name: t('c13.open', [12]) }));
    expect(await screen.findByText(t('c13.detail.title', [12, 'Payroll dry run']))).toBeTruthy();
  });
});
