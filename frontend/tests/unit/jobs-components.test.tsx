import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { BatchJobs } from '@/modules/jobs/components/BatchJobs';
import { JobActions } from '@/modules/jobs/components/JobActions';
import { JobApproval } from '@/modules/jobs/components/JobApproval';
import { GovernedProvider } from '@/modules/workflow';
import { JobHistory } from '@/modules/jobs/components/JobHistory';
import { SubmitJobForm } from '@/modules/jobs/components/SubmitJobForm';
import { ToastProvider } from '@/shared/ui';
import { I18nProvider } from '@/shared/i18n';
import type { Job } from '@/modules/jobs/api/types';
import { messages } from '@/generated/messages';
import { referenceKey, type ReferenceList } from '@/shared/reference';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/** The M-16 lists the C-13 screens label with (from the database in the app; English labels here). */
function referenceList(code: string, prefix: string): ReferenceList {
  const en = messages.en as Record<string, string>;
  const values = Object.keys(en)
    .filter((k) => k.startsWith(prefix))
    .map((k) => ({
      code: k.slice(prefix.length),
      label: en[k] ?? '',
      labelHi: null,
      sortOrder: null,
      numeric: null,
      parent: null,
      active: true,
    }));
  return { code, name: code, module: 'JOBS', system: true, values };
}

type Route = (url: string, init?: RequestInit) => unknown;

/** A fetch stub that answers by URL; records every call. */
function backend(routes: Record<string, Route>) {
  const calls: { url: string; init?: RequestInit }[] = [];
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url, init });
    if (url.endsWith('/auth/csrf')) {
      document.cookie = 'XSRF-TOKEN=t; Path=/';
      return new Response(null, { status: 204 });
    }
    const match = Object.keys(routes)
      .sort((a, b) => b.length - a.length)
      .find((prefix) => url.includes(prefix));
    const body = match ? routes[match]?.(url, init) : { locale: 'en' };
    // A route answers an error by returning { status, body } with a problem body.
    const error = body as { status?: number; body?: unknown } | undefined;
    const refused = typeof error?.status === 'number' && error.status >= 400;
    return new Response(JSON.stringify((refused ? error?.body : body) ?? {}), {
      status: refused ? (error?.status as number) : 200,
      headers: { 'Content-Type': 'application/json' },
    });
  });
  vi.stubGlobal('fetch', fetchMock);
  return calls;
}

function wrap(children: ReactNode, permissions: string[] = ['C-13:VIEW', 'C-13:CREATE', 'C-13:EDIT']) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(['me'], { permissions, userId: 1 });
  client.setQueryData(referenceKey, {
    asOf: '2026-07-01',
    lists: {
      JOB_STATUS: referenceList('JOB_STATUS', 'c13.status.'),
      JOB_TYPE: referenceList('JOB_TYPE', 'c13.type.'),
    },
  });
  return render(
    <I18nProvider>
      <QueryClientProvider client={client}>
        <ToastProvider>
          <GovernedProvider>{children}</GovernedProvider>
        </ToastProvider>
      </QueryClientProvider>
    </I18nProvider>,
  );
}

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

describe('C-13 route guard', () => {
  it('shows nothing of the module without C-13 VIEW', () => {
    backend({});
    wrap(<BatchJobs />, ['C-02:VIEW']);
    expect(screen.getByRole('alert')).toHaveTextContent('C-13');
    expect(screen.queryByRole('tab')).toBeNull();
  });

  it('offers Submit only with C-13 CREATE', async () => {
    backend({ '/nginx/fci-payroll/v1/jobs': () => ({ items: [], page: 0, size: 20, total: 0 }) });
    wrap(<BatchJobs />, ['C-13:VIEW']);
    expect(screen.getAllByRole('tab')).toHaveLength(1);
    expect(await screen.findByText('No jobs match the filter.')).toBeTruthy();
  });
});

describe('JobActions', () => {
  it('enables exactly the commands the server allows, and confirms with a reason', async () => {
    const calls = backend({
      '/nginx/fci-payroll/v1/jobs/12/pause': () => ({ id: 1, command: 'PAUSE', status: 'PENDING' }),
    });
    wrap(<JobActions job={job()} access={{ view: true, submit: true, control: true, halt: false }} />);
    expect(screen.getByRole('button', { name: 'Pause' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Stop' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Resume' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Restart' })).toBeDisabled();

    await userEvent.click(screen.getByRole('button', { name: 'Pause' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'month-end freeze');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Pause' }));

    await waitFor(() =>
      expect(calls.some((c) => c.url.endsWith('/nginx/fci-payroll/v1/jobs/12/pause'))).toBe(true),
    );
    const sent = calls.find((c) => c.url.endsWith('/nginx/fci-payroll/v1/jobs/12/pause'));
    expect(JSON.parse(sent?.init?.body as string)).toEqual({ reason: 'month-end freeze' });
  });

  it('disables everything without the control permission or while a command is pending', () => {
    backend({});
    const { unmount } = wrap(
      <JobActions job={job()} access={{ view: true, submit: false, control: false, halt: false }} />,
    );
    expect(screen.getAllByRole('button').every((b) => (b as HTMLButtonElement).disabled)).toBe(true);
    unmount();
    wrap(
      <JobActions
        job={job({ pendingCommand: 'PENDING' })}
        access={{ view: true, submit: true, control: true, halt: false }}
      />,
    );
    expect(screen.getAllByRole('button').every((b) => (b as HTMLButtonElement).disabled)).toBe(true);
  });
});

describe('SubmitJobForm', () => {
  const types = {
    types: [
      {
        type: 'PAYROLL_DRY_RUN',
        priority: 2,
        params: [
          { name: 'locationId', kind: 'LOCATION', required: true },
          { name: 'payPeriod', kind: 'PERIOD', required: true },
        ],
      },
    ],
    currentPeriod: '2026-07',
    locations: [{ id: 3, code: 'DH001', label: 'DO — Delhi HQ (DH001)' }],
  };

  it('validates, asks for confirmation, then queues the job', async () => {
    const calls = backend({
      '/nginx/fci-payroll/v1/jobs/types': () => types,
      '/nginx/fci-payroll/v1/jobs': () => job({ id: 99, status: 'PENDING' }),
    });
    const onSubmitted = vi.fn();
    wrap(<SubmitJobForm onSubmitted={onSubmitted} />);

    const period = await screen.findByLabelText(/Pay period/);
    await userEvent.clear(period);
    await userEvent.click(screen.getByRole('button', { name: 'Review & submit' }));
    expect(await screen.findAllByText('Required.')).toHaveLength(2); // period and the maker's reason
    expect(screen.queryByRole('dialog')).toBeNull();

    await userEvent.type(period, '2026-06');
    await userEvent.click(screen.getByRole('button', { name: 'Review & submit' }));
    expect(await screen.findByText('Required.')).toBeTruthy(); // the maker's reason
    await userEvent.type(screen.getByLabelText(/Reason/), 'June dry run before verification');
    await userEvent.click(screen.getByRole('button', { name: 'Review & submit' }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('DO — Delhi HQ (DH001)');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Submit job' }));

    await waitFor(() => expect(onSubmitted).toHaveBeenCalledWith(99));
    const post = calls.find((c) => c.init?.method === 'POST' && c.url.endsWith('/nginx/fci-payroll/v1/jobs'));
    expect(JSON.parse(post?.init?.body as string)).toEqual({
      jobType: 'PAYROLL_DRY_RUN',
      params: { locationId: '3', payPeriod: '2026-06' },
      priority: 2,
      reason: 'June dry run before verification',
    });
  });

  it('a new job type starts from its own defaults; unit and priority are sent; cancel and a refusal keep the form', async () => {
    const two = {
      ...types,
      types: [
        ...types.types,
        {
          type: 'DA_ARREAR',
          params: [
            { name: 'locationId', kind: 'LOCATION', required: true },
            { name: 'fromPeriod', kind: 'PERIOD', required: true },
            { name: 'toPeriod', kind: 'PERIOD', required: true },
          ],
        },
      ],
      locations: [...types.locations, { id: 4, code: 'MR001', label: 'RO — Mumbai (MR001)' }],
    };
    const calls = backend({
      '/nginx/fci-payroll/v1/jobs/types': () => two,
      '/nginx/fci-payroll/v1/jobs': () => ({
        status: 409,
        body: { status: 409, errorCode: 'JOB-409-QUEUED', messageKey: 'error.JOB-409-QUEUED', args: [] },
      }),
    });
    const onSubmitted = vi.fn();
    wrap(<SubmitJobForm onSubmitted={onSubmitted} />);
    await screen.findByLabelText(/Pay period/);
    // Another type: its own parameters, with the current period and the first unit to start.
    await userEvent.selectOptions(screen.getByLabelText('Job'), 'DA_ARREAR');
    expect(screen.queryByLabelText(/Pay period/)).toBeNull();
    expect((screen.getByLabelText(/From period/) as HTMLInputElement).value).toBe('2026-07');
    expect(screen.getByText(/arrear event with the per-month DA differentials/)).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText(/Unit/), '4');
    await userEvent.selectOptions(screen.getByLabelText('Priority'), '9');
    await userEvent.type(screen.getByLabelText(/Reason/), 'Arrear for the revision');
    await userEvent.click(screen.getByRole('button', { name: 'Review & submit' }));
    // Cancel closes the confirmation and keeps the form.
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Review & submit' }));
    await userEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Submit job' }),
    );
    // The server refuses a duplicate: the reason is shown, nothing is opened.
    expect(await screen.findByText(messages.en['error.JOB-409-QUEUED'] as string)).toBeTruthy();
    expect(onSubmitted).not.toHaveBeenCalled();
    const post = calls.find((c) => c.init?.method === 'POST' && c.url.endsWith('/nginx/fci-payroll/v1/jobs'));
    expect(JSON.parse(post?.init?.body as string)).toMatchObject({
      jobType: 'DA_ARREAR',
      params: { locationId: '4', fromPeriod: '2026-07', toPeriod: '2026-07' },
      priority: 9,
    });
  });
});

describe('JobApproval (maker-checker)', () => {
  it('lets the checker approve a job awaiting approval through the workflow endpoint', async () => {
    const calls = backend({ '/nginx/fci-payroll/v1/approvals/77/decision': () => ({ status: 'APPROVED' }) });
    wrap(
      <JobApproval
        job={job({ status: 'AWAITING_APPROVAL', approvalId: 77, canDecide: true, allowedCommands: [] })}
      />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Approve' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Approve' }));

    await waitFor(() =>
      expect(calls.some((c) => c.url.endsWith('/nginx/fci-payroll/v1/approvals/77/decision'))).toBe(true),
    );
    const sent = calls.find((c) => c.url.endsWith('/nginx/fci-payroll/v1/approvals/77/decision'));
    expect(JSON.parse(sent?.init?.body as string)).toEqual({ decision: 'APPROVE' });
  });

  it('shows the maker (and anyone who may not decide) who the job is waiting for, without buttons', () => {
    backend({});
    wrap(<JobApproval job={job({ status: 'AWAITING_APPROVAL', approvalId: 77, canDecide: false })} />);
    expect(screen.getByText(/Waiting for a Payroll Checker/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull();
  });

  it('renders nothing once the job is past approval', () => {
    backend({});
    const { container } = wrap(<JobApproval job={job({ status: 'PENDING', approvalId: 77 })} />);
    expect(container.textContent).toBe('');
  });
});

describe('JobHistory', () => {
  it('lists jobs, sorts on the server when a header is clicked, and opens a job', async () => {
    const calls = backend({
      '/nginx/fci-payroll/v1/jobs?': () => ({ items: [job()], page: 0, size: 20, total: 1 }),
    });
    const onOpen = vi.fn();
    wrap(<JobHistory onOpen={onOpen} />);

    expect(await screen.findByText('maker.delhi@fci-demo.test')).toBeTruthy();
    const table = screen.getByRole('table');
    expect(within(table).getByText('Running')).toBeTruthy();
    expect(within(table).getByText('1m 05s')).toBeTruthy();

    await userEvent.click(screen.getByRole('button', { name: /^Status/ }));
    await waitFor(() => expect(calls.some((c) => c.url.includes('sort=status&direction=desc'))).toBe(true));

    await userEvent.click(screen.getByRole('button', { name: 'Open job 12' }));
    expect(onOpen).toHaveBeenCalledWith(12);
  });
});
