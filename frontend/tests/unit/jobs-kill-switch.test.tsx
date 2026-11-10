import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Halt, Job, JobDetail } from '@/modules/jobs/api/types';
import { BatchJobs } from '@/modules/jobs/components/BatchJobs';
import { JobActions } from '@/modules/jobs/components/JobActions';
import { JobDetailView } from '@/modules/jobs/components/JobDetailView';
import { backend, me, paged, referenceLists, sent, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * Plan 05 phase 05j (SRS-15-25, SRS-15-27): Abandon is a proposal with its consequence stated; while
 * it awaits the checker nothing else is offered; the kill switch shows its banner to every viewer,
 * is engaged at once by the System Administrator at Headquarters and released only by approval.
 */

afterEach(() => vi.unstubAllGlobals());

const ACCESS = { view: true, submit: true, control: true, halt: false };

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

const HALTED: Halt = {
  engaged: true,
  haltId: 5,
  haltedBy: 'sysadmin@fci-demo.test',
  haltedAt: '2026-10-24T06:00:00Z',
  reason: 'Wrong DA rate activated',
  releaseApprovalId: null,
  releaseRequestedBy: null,
  releaseReason: null,
};

const RELEASE_ASKED: Halt = {
  ...HALTED,
  releaseApprovalId: 66,
  releaseRequestedBy: 'sysadmin@fci-demo.test',
  releaseReason: 'Rate corrected',
};

const REFERENCE = referenceLists({
  JOB_STATUS: [
    ['FAILED', 'Failed'],
    ['ABANDONED', 'Abandoned'],
  ],
  JOB_TYPE: [['PAYROLL_DRY_RUN', 'Payroll dry run']],
});

const failed = (over: Partial<Job> = {}): Job => ({
  id: 12,
  jobType: 'PAYROLL_DRY_RUN',
  params: { locationId: '3', payPeriod: '2026-07' },
  priority: 5,
  status: 'FAILED',
  source: 'UI',
  requestedBy: 'maker.delhi@fci-demo.test',
  locationId: 3,
  locationLabel: 'DO — Delhi HQ (DH001)',
  payPeriod: '2026-07',
  requestedAt: '2026-07-15T10:00:00Z',
  startedAt: '2026-07-15T10:00:05Z',
  finishedAt: '2026-07-15T10:01:10Z',
  durationSeconds: 65,
  attemptNo: 1,
  jobExecutionId: 4,
  approvalId: null,
  canDecide: false,
  readCount: 10,
  writeCount: 8,
  skipCount: 0,
  resultSummary: null,
  errorMessage: 'simulated outage',
  pendingCommand: null,
  allowedCommands: ['RESTART', 'ABANDON'],
  ...over,
});

const jobs = (url: string) => paged([], 0, url);

describe('JobActions: Abandon (plan 05j)', () => {
  it('SRS-15-27: offers Abandon for a failed job, states what it does and sends the reason', async () => {
    const calls = backend({
      'GET /v1/jobs/halt': () => RUNNING,
      'GET /v1/reference/lists': () => REFERENCE,
      'POST /v1/jobs/12/abandon': () =>
        failed({ pendingCommand: 'ABANDON_AWAITING_APPROVAL', allowedCommands: [] }),
    });
    wrap(<JobActions job={failed()} access={ACCESS} />, ['C-13:VIEW', 'C-13:EDIT']);
    const abandon = screen.getByRole('button', { name: t('c13.command.ABANDON') });
    expect(abandon).toBeEnabled();
    expect(screen.getByRole('button', { name: t('c13.command.RESTART') })).toBeEnabled();

    await userEvent.click(abandon);
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(t('c13.abandon.note'))).toBeTruthy();
    expect(within(dialog).getByRole('button', { name: t('c13.command.ABANDON') })).toBeDisabled();
    await userEvent.type(within(dialog).getByRole('textbox'), 'Wrong period');
    await userEvent.click(within(dialog).getByRole('button', { name: t('c13.command.ABANDON') }));

    await waitFor(() =>
      expect(sent(calls, 'POST', '/v1/jobs/12/abandon')).toEqual([{ reason: 'Wrong period' }]),
    );
    expect(await screen.findByText(t('c13.abandon.requested', [12]))).toBeTruthy();
  });

  it('while an abandon awaits the checker, every button is disabled and says so', () => {
    backend({ 'GET /v1/jobs/halt': () => RUNNING, 'GET /v1/reference/lists': () => REFERENCE });
    wrap(
      <JobActions
        job={failed({ pendingCommand: 'ABANDON_AWAITING_APPROVAL', allowedCommands: [] })}
        access={ACCESS}
      />,
      ['C-13:VIEW', 'C-13:EDIT'],
    );
    expect(screen.getAllByRole('button').every((b) => (b as HTMLButtonElement).disabled)).toBe(true);
    expect(screen.getByRole('button', { name: t('c13.command.RESTART') })).toHaveAttribute(
      'title',
      t('c13.why.abandonPending'),
    );
    expect(screen.getByRole('button', { name: t('c13.command.ABANDON') })).toHaveAttribute(
      'title',
      t('c13.why.abandonPending'),
    );
  });

  it('SRS-15-25: while batch processing is halted, Restart says why; Abandon and Stop keep their own reasons', async () => {
    backend({ 'GET /v1/jobs/halt': () => HALTED, 'GET /v1/reference/lists': () => REFERENCE });
    wrap(<JobActions job={failed({ allowedCommands: ['ABANDON'] })} access={ACCESS} />, [
      'C-13:VIEW',
      'C-13:EDIT',
    ]);
    const restart = screen.getByRole('button', { name: t('c13.command.RESTART') });
    expect(restart).toBeDisabled();
    await waitFor(() => expect(restart).toHaveAttribute('title', t('c13.why.halted')));
    expect(screen.getByRole('button', { name: t('c13.command.RESUME') })).toHaveAttribute(
      'title',
      t('c13.why.halted'),
    );
    expect(screen.getByRole('button', { name: t('c13.command.ABANDON') })).toBeEnabled();
    // Stop is not a start: its reason is the job's state, as before.
    await waitFor(() =>
      expect(screen.getByRole('button', { name: t('c13.command.STOP') })).toHaveAttribute(
        'title',
        t('c13.why.state', ['Failed']),
      ),
    );
  });
});

describe('C-13 kill switch (plan 05j)', () => {
  it('SRS-15-25: every viewer sees the halt banner; a maker has no Halt button', async () => {
    backend({
      'GET /v1/jobs/halt': () => HALTED,
      'GET /v1/jobs?': jobs,
      'GET /v1/reference/lists': () => REFERENCE,
    });
    wrap(<BatchJobs />, ['C-13:VIEW', 'C-13:CREATE', 'C-13:EDIT']);
    const banner = await screen.findByTestId('batch-halt');
    expect(banner).toHaveTextContent(t('c13.halt.title'));
    expect(banner).toHaveTextContent('sysadmin@fci-demo.test');
    expect(banner).toHaveTextContent('Wrong DA rate activated');
    expect(screen.queryByRole('button', { name: t('c13.halt.button') })).toBeNull();
    expect(screen.queryByRole('button', { name: t('c13.halt.release') })).toBeNull();
  });

  it('SRS-15-25: the System Administrator at Headquarters halts at once, with a reason and the consequence stated', async () => {
    const calls = backend({
      'GET /v1/jobs/halt': () => RUNNING,
      'GET /v1/jobs?': jobs,
      'GET /v1/reference/lists': () => REFERENCE,
      'POST /v1/jobs/halt': () => HALTED,
    });
    wrap(
      <BatchJobs />,
      me(['C-13:VIEW', 'C-13:HALT'], { role: 'SYSTEM_ADMINISTRATOR', atHeadquarters: true }),
    );
    const halt = await screen.findByRole('button', { name: t('c13.halt.button') });
    expect(halt).toBeEnabled();
    expect(screen.queryByTestId('batch-halt')).toBeNull();

    await userEvent.click(halt);
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(t('c13.halt.note'))).toBeTruthy();
    expect(within(dialog).getByRole('button', { name: t('c13.halt.button') })).toBeDisabled();
    await userEvent.type(within(dialog).getByRole('textbox'), 'Wrong DA rate activated');
    await userEvent.click(within(dialog).getByRole('button', { name: t('c13.halt.button') }));

    await waitFor(() =>
      expect(sent(calls, 'POST', '/v1/jobs/halt')).toEqual([{ reason: 'Wrong DA rate activated' }]),
    );
    expect(await screen.findByText(t('c13.halt.done'))).toBeTruthy();
  });

  it('SRS-15-25: outside Headquarters the Halt button is disabled with the reason', async () => {
    backend({
      'GET /v1/jobs/halt': () => RUNNING,
      'GET /v1/jobs?': jobs,
      'GET /v1/reference/lists': () => REFERENCE,
    });
    wrap(
      <BatchJobs />,
      me(['C-13:VIEW', 'C-13:HALT'], {
        role: 'SYSTEM_ADMINISTRATOR',
        atHeadquarters: false,
        location: { code: 'NA11', label: 'ZO — North (NA11)' },
      }),
    );
    const halt = await screen.findByRole('button', { name: t('c13.halt.button') });
    expect(halt).toBeDisabled();
    expect(halt).toHaveAttribute('title', t('error.AUTHZ-403-HQ-ONLY'));
  });

  it('SRS-G-1: halted, the administrator asks for the release; a release awaiting approval is shown and not asked twice', async () => {
    const calls = backend({
      'GET /v1/jobs/halt': () => HALTED,
      'GET /v1/jobs?': jobs,
      'GET /v1/reference/lists': () => REFERENCE,
      'POST /v1/jobs/halt/release': () => RELEASE_ASKED,
    });
    const first = wrap(<BatchJobs />, me(['C-13:VIEW', 'C-13:HALT'], { atHeadquarters: true }));
    expect(await screen.findByTestId('batch-halt')).toBeTruthy();
    expect(screen.queryByRole('button', { name: t('c13.halt.button') })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('c13.halt.release') }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(t('c13.halt.releaseNote'))).toBeTruthy();
    await userEvent.type(within(dialog).getByRole('textbox'), 'Rate corrected');
    await userEvent.click(within(dialog).getByRole('button', { name: t('c13.halt.release') }));
    await waitFor(() =>
      expect(sent(calls, 'POST', '/v1/jobs/halt/release')).toEqual([{ reason: 'Rate corrected' }]),
    );
    expect(await screen.findByText(t('c13.halt.releaseRequested'))).toBeTruthy();
    first.unmount();

    backend({
      'GET /v1/jobs/halt': () => RELEASE_ASKED,
      'GET /v1/jobs?': jobs,
      'GET /v1/reference/lists': () => REFERENCE,
    });
    wrap(<BatchJobs />, me(['C-13:VIEW', 'C-13:HALT'], { atHeadquarters: true }));
    expect(
      await screen.findByText(t('c13.halt.releasePending', ['sysadmin@fci-demo.test', 66])),
    ).toBeTruthy();
    expect(screen.queryByRole('button', { name: t('c13.halt.release') })).toBeNull();
    expect(screen.queryByRole('button', { name: t('c13.halt.button') })).toBeNull();
  });
});

describe('C-13 Job Detail: an abandon awaiting approval', () => {
  it('SRS-15-27: says the abandon awaits the checker instead of "command pending"', async () => {
    const job = failed({ pendingCommand: 'ABANDON_AWAITING_APPROVAL', allowedCommands: [] });
    const detail: JobDetail = { job, attempts: [], controls: [], audit: [], skippedTotal: 0 };
    backend({
      'GET /v1/jobs/12': () => detail,
      'GET /v1/jobs/halt': () => RUNNING,
      'GET /v1/reference/lists': () => REFERENCE,
    });
    wrap(<JobDetailView id={12} access={ACCESS} onBack={vi.fn()} />, ['C-13:VIEW']);
    expect(await screen.findByText(t('c13.abandonPending'))).toBeTruthy();
    expect(screen.queryByText(t('c13.commandPending'))).toBeNull();
  });
});
