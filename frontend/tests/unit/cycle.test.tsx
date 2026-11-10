import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PayrollCycle } from '@/modules/jobs';
import { runningRunNo } from '@/modules/jobs/business/cycleRules';
import { dateTime } from '@/shared/ui';
import { backend, me, problem, referenceLists, sent, t, wrap, paged } from './support/harness';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push, replace: vi.fn() }) }));

/**
 * C-02 payroll cycle (BR-CYC-008): forward only, one action per state, each offered only to the role
 * that may take it; the freeze and the lock request need the OTP; the run of record and its results.
 */

// The cycle actions sit in a closed section (Wireframes v6.1 does not draw them on C-02): these tests work inside it.
beforeEach(() => {
  window.location.hash = '#c02-cycle';
});

afterEach(() => {
  vi.unstubAllGlobals();
  push.mockReset();
  window.location.hash = '';
});

const ALL = [
  'C-02:VIEW',
  'C-02:CREATE',
  'C-02:SUBMIT',
  'C-02:APPROVE',
  'C-02:LOCK',
  'C-02:LEASE_RELEASE',
  'C-13:CREATE',
];
const CHECKER = ['C-02:VIEW', 'C-02:APPROVE', 'C-02:RETURN'];
const MAKER = ['C-02:VIEW', 'C-02:SUBMIT', 'C-02:UNFREEZE', 'C-13:CREATE'];

const cycle = (over: object = {}) => ({
  period: '2026-10',
  unitCode: 'DH001',
  unitLabel: 'DO Delhi',
  unitLevel: 'DO',
  inCalendar: true,
  computeBy: '2026-10-24',
  lockTarget: '2026-10-28',
  id: null,
  status: null,
  initiatedBy: null,
  initiatedAt: null,
  frozenBy: null,
  frozenAt: null,
  verifiedBy: null,
  verifiedAt: null,
  lockedBy: null,
  lockedAt: null,
  currentRunId: null,
  runs: [],
  resultsRunId: null,
  resultsRunType: null,
  totals: { members: 0, gross: 0, deductions: 0, net: 0, components: [] },
  lockApprovalId: null,
  lockApprovalStatus: null,
  postedToEbs: false,
  supplementaryOpen: false,
  lease: null,
  leaseReleaseApprovalId: null,
  openReturn: null,
  unfreezeApprovalId: null,
  ...over,
});

/** SRS-15-30: the Checker's open return. */
const opened = (over: object = {}) => ({
  id: 12,
  scope: 'MEMBERS',
  orgUnitCode: null,
  members: ['100001', '100003'],
  fromStatus: 'VERIFIED',
  reason: 'Attendance not matched',
  returnedBy: 'ddo.delhi@fci-demo.test',
  returnedAt: '2026-10-21T09:00:00Z',
  runId: 31,
  ...over,
});

/** SRS-15-28: a lease another action holds on the instance. */
const lease = (over: object = {}) => ({
  cycleId: 9,
  action: 'COMPUTE',
  heldBy: 'batch.compute@fci-demo.test',
  acquiredAt: '2026-10-20T10:00:00Z',
  heartbeatAt: '2026-10-20T10:04:00Z',
  runId: 31,
  expired: false,
  ...over,
});

const heldBy = (l: ReturnType<typeof lease>) =>
  t('error.CYC-409-LEASE', [t(`cyc.leaseAction.${l.action}`), l.heldBy, dateTime(l.acquiredAt)]);

const run = (over: object = {}) => ({
  id: 31,
  type: 'ACTUAL',
  runNo: 1,
  status: 'COMPLETED',
  startedBy: 'maker.delhi@fci-demo.test',
  startedAt: '2026-10-20T10:00:00Z',
  completedAt: '2026-10-20T10:05:00Z',
  eligible: 120,
  results: 118,
  units: 4,
  unitsCommitted: 4,
  unitsFailed: 0,
  ...over,
});

const result = (id: number) => ({
  resultId: id,
  eis: String(100000 + id),
  name: `Employee ${id}`,
  gross: 50000,
  deductions: 8000,
  net: 42000,
  status: 'COMPUTED',
  locked: id === 1,
  payPattern: id % 2 === 0 ? 'CDA' : 'IDA',
  basic: id === 3 ? null : 30000,
  da: id === 3 ? null : 15000,
  cityClass: id === 3 ? null : 'X',
  scale: 'E-4',
  hra: id === 3 ? null : 1000,
  other: 4000,
  rowStatus: 'COMPUTED',
  statusGroup: 'COMPUTED',
});

const SUMMARY = {
  ida: { members: 60, exceptions: 1 },
  cda: { members: 58, exceptions: 2 },
  gross: 5900000,
  deductions: 944000,
  net: 4956000,
  held: 3,
  shown: { members: 118, gross: 5900000, deductions: 944000, net: 4956000 },
};

/** The cycle of state {@code status} with everything that state carries. */
function computed(over: object = {}) {
  return cycle({
    id: 9,
    status: 'COMPUTED',
    initiatedBy: 'maker.delhi@fci-demo.test',
    initiatedAt: '2026-10-19T09:00:00Z',
    frozenBy: 'maker.delhi@fci-demo.test',
    frozenAt: '2026-10-19T10:00:00Z',
    currentRunId: 31,
    runs: [
      run(),
      run({
        id: 30,
        type: 'SIMULATION',
        runNo: 1,
        eligible: null,
        units: null,
        unitsCommitted: 0,
        unitsFailed: 2,
        status: 'FAILED',
      }),
    ],
    resultsRunId: 31,
    resultsRunType: 'ACTUAL',
    totals: {
      members: 118,
      gross: 5900000,
      deductions: 944000,
      net: 4956000,
      components: [{ payCode: 101, name: 'Basic Pay', amount: 3000000 }],
    },
    ...over,
  });
}

const CLEAR = [
  {
    code: 'P1_RETIREMENT',
    name: 'P1 - Retirement',
    status: 'PASS',
    failures: 0,
    members: [],
    correctionScreen: 'I-21',
  },
  {
    code: 'P4_APPS_PERIOD_CLOSE',
    name: 'P4 - APPS period close',
    status: 'NOT_AVAILABLE',
    failures: 0,
    members: [],
    correctionScreen: 'X-03',
  },
];

/** The trace of whichever row is selected (each page selects its first row). */
const TRACE = {
  resultId: 1,
  eis: '100001',
  name: 'Employee 1',
  period: '2026-10',
  payPattern: 'IDA',
  cityClass: 'X',
  stagnationCase: false,
  accommodation: null,
  gross: 50000,
  deductions: 8000,
  net: 42000,
  lines: [],
};

function routes(board: unknown, over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
  return backend({
    'GET /v1/reference/lists': () =>
      referenceLists({
        C02_PAY_PATTERN_FILTER: [
          ['IDA', 'IDA only'],
          ['CDA', 'CDA only'],
        ],
      }),
    'GET /v1/cycles/2026-10': (url) => (url.endsWith('/trace') ? TRACE : board),
    'GET /v1/cycles/2026-10/pre-run-checks': () => CLEAR,
    'GET /v1/cycles/2026-10/results-period': () => ({ period: '2026-10', runType: 'ACTUAL' }),
    'GET /v1/cycles/2026-10/results/summary': () => SUMMARY,
    'GET /v1/cycles/2026-10/pli-payments': (url) => paged([], 0, url),
    // 101 results, served one page at a time as the URL asks (X-pagination).
    'GET /v1/cycles/2026-10/results?': (url) => {
      const page = Number(/page=(\d+)/.exec(url)?.[1] ?? 0);
      const size = Number(/size=(\d+)/.exec(url)?.[1] ?? 20);
      const pattern = /payPattern=(IDA|CDA)/.exec(url)?.[1];
      const all = Array.from({ length: 101 }, (_, i) => result(i + 1)).filter(
        (r) => !pattern || r.payPattern === pattern,
      );
      return paged(all.slice(page * size, page * size + size), all.length, url);
    },
    'GET /v1/cycles/2026-10/results/1': (url) =>
      url.endsWith('/trace')
        ? TRACE
        : paged([
            { payCode: 101, name: 'Basic Pay', type: 'EARNING', amount: 30000, rate: null, basis: null },
            {
              payCode: 103,
              name: 'Dearness Allowance',
              type: 'EARNING',
              amount: 15000,
              rate: 50,
              basis: 30000,
            },
          ]),
    ...over,
  });
}

const button = (key: string) => screen.queryByRole('button', { name: t(`cyc.action.${key}`) });

describe('C-02 payroll cycle', () => {
  it('BR-CYC-008: a cycle not yet initiated offers Initiate (outside the calendar disabled) and a dry-run hint', async () => {
    const calls = routes(cycle(), {
      'POST /v1/cycles/2026-10/initiate': () => cycle({ status: 'INITIATED' }),
    });
    wrap(<PayrollCycle />, me(ALL, { atHeadquarters: false }));
    expect(await screen.findByText(t('cyc.notInitiated'))).toBeTruthy();
    expect(screen.getByText(t('cyc.dryRunHint'))).toBeTruthy();
    // SRS-15-23: the pre-run checks are listed; a check without its source is "not available".
    const panel = await screen.findByRole('table', { name: t('cyc.preRun.title') });
    expect(panel).toHaveTextContent(t('cyc.preRun.NOT_AVAILABLE'));
    await waitFor(() => expect((button('initiate') as HTMLButtonElement).disabled).toBe(false));
    await userEvent.click(button('initiate') as HTMLElement);
    expect(await screen.findByText(t('cyc.done.initiate'))).toBeTruthy();
    expect(sent(calls, 'POST', '/initiate')).toHaveLength(1);
  });

  it('SRS_15_23_aFailingPreRunCheckKeepsInitiateDisabledNamingItsRecords', async () => {
    routes(cycle(), {
      'GET /v1/cycles/2026-10/pre-run-checks': () => [
        {
          code: 'P2_DUPLICATE_FPS',
          name: 'P2 - Duplicate FPS',
          status: 'FAIL',
          failures: 2,
          members: ['104882', '104883'],
          correctionScreen: 'I-21',
        },
      ],
    });
    wrap(<PayrollCycle />, me(ALL, { atHeadquarters: false }));
    const panel = await screen.findByRole('table', { name: t('cyc.preRun.title') });
    expect(panel).toHaveTextContent('104882, 104883');
    expect(panel).toHaveTextContent(t('cyc.preRun.fixOn', ['I-21']));
    const initiate = button('initiate') as HTMLButtonElement;
    expect(initiate.disabled).toBe(true);
    expect(initiate.title).toBe(t('error.CYC-409-PRE-RUN', ['P2_DUPLICATE_FPS']));
  });

  it('SRS_15_23_aFailingCheckWithoutNamedRecordsOrScreenShowsItsCountOnly', async () => {
    routes(cycle(), {
      'GET /v1/cycles/2026-10/pre-run-checks': () => [
        {
          code: 'P5_NOT_BUILT',
          name: 'P5 - Not built',
          status: 'FAIL',
          failures: 1,
          members: [],
          correctionScreen: null,
        },
      ],
    });
    wrap(<PayrollCycle />, me(ALL, { atHeadquarters: false }));
    const panel = await screen.findByRole('table', { name: t('cyc.preRun.title') });
    const row = within(panel).getByText(t('cyc.preRun.FAIL')).closest('tr') as HTMLElement;
    expect(row.lastElementChild?.textContent).toBe('1');
  });

  it('SRS-15-12: a cycle posted to EBS says so', async () => {
    routes(
      computed({ status: 'LOCKED', lockedBy: 'c', lockedAt: '2026-10-22T09:00:00Z', postedToEbs: true }),
    );
    wrap(<PayrollCycle />, me(ALL, { atHeadquarters: false }));
    const posted = await screen.findByText(t('cyc.postedToEbs'));
    expect(posted.nextElementSibling?.textContent).toBe(t('common.yes'));
  });

  it('outside the payroll calendar the cycle cannot be initiated', async () => {
    routes(cycle({ inCalendar: false }));
    wrap(<PayrollCycle />, me(ALL, { atHeadquarters: false }));
    expect(await screen.findByText(t('cyc.notInCalendar'))).toBeTruthy();
    expect((button('initiate') as HTMLButtonElement).disabled).toBe(true);
  });

  it('SRS-G-21: the freeze needs the OTP; a refusal is shown, a cancelled OTP is not', async () => {
    let refuse = false;
    const calls = routes(
      cycle({ id: 9, status: 'INITIATED', initiatedBy: 'm', initiatedAt: '2026-10-19T09:00:00Z' }),
      {
        'POST /v1/cycles/2026-10/freeze': (_url, init) =>
          (JSON.parse(String(init?.body)) as { otp?: unknown }).otp
            ? refuse
              ? problem(409, 'CYC-409-STATE')
              : cycle({ status: 'FROZEN' })
            : problem(428, 'AUTH-428-OTP', [5]),
      },
    );
    wrap(<PayrollCycle />, me(ALL, { atHeadquarters: false }));
    await userEvent.click(
      (await screen.findByRole('button', { name: t('cyc.action.freeze') })) as HTMLElement,
    );
    await userEvent.type(await screen.findByLabelText(t('otp.code')), '111222');
    await userEvent.click(screen.getByRole('button', { name: t('otp.verify') }));
    expect(await screen.findByText(t('cyc.done.freeze'))).toBeTruthy();
    expect(sent(calls, 'POST', '/freeze').at(-1)).toEqual({ otp: { challengeId: 5, code: '111222' } });
    refuse = true;
    await userEvent.click(button('freeze') as HTMLElement);
    await userEvent.type(await screen.findByLabelText(t('otp.code')), '111222');
    await userEvent.click(screen.getByRole('button', { name: t('otp.verify') }));
    expect(await screen.findByText(t('error.CYC-409-STATE'))).toBeTruthy();
    await userEvent.click(button('freeze') as HTMLElement);
    await screen.findByLabelText(t('otp.code'));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('a frozen cycle sends the maker to C-13 to compute', async () => {
    routes(cycle({ id: 9, status: 'FROZEN' }));
    wrap(<PayrollCycle />, me(ALL, { atHeadquarters: false }));
    expect(await screen.findByText(t('cyc.computeHint'))).toBeTruthy();
    await userEvent.click(button('compute') as HTMLElement);
    expect(push).toHaveBeenCalledWith('/payroll?s=C-13');
  });

  it('a computed cycle shows the steps reached, its runs, totals and results; the checker verifies', async () => {
    const calls = routes(computed(), {
      'POST /v1/cycles/2026-10/verify': () => computed({ status: 'VERIFIED' }),
    });
    wrap(<PayrollCycle />, me(ALL, { atHeadquarters: false }));
    expect(await screen.findByText(t('cyc.resultsActual', [31]))).toBeTruthy();
    const steps = screen.getAllByRole('listitem');
    expect(steps.filter((s) => s.className.includes('p-ok'))).toHaveLength(3);
    expect(steps.find((s) => s.getAttribute('aria-current') === 'step')?.textContent).toBe(
      t('cyc.state.COMPUTED'),
    );
    expect(screen.getByText(t('cyc.ofRecord'))).toBeTruthy();
    expect(screen.getByText(t('cyc.unitsFailed', [2]), { exact: false })).toBeTruthy();
    expect(screen.getByText('118 / 120')).toBeTruthy();
    expect(screen.getByText('101 Basic Pay')).toBeTruthy();
    expect(await screen.findByText('Employee 1')).toBeTruthy();
    await userEvent.click(button('verify') as HTMLElement);
    expect(await screen.findByText(t('cyc.done.verify'))).toBeTruthy();
    expect(sent(calls, 'POST', '/verify')).toHaveLength(1);
  });

  it('PAGING: results page with the shared pager; Component view opens the pay lines and closes them', async () => {
    routes(computed());
    wrap(<PayrollCycle />, ['C-02:VIEW']);
    expect(await screen.findByText('Employee 1')).toBeTruthy();
    const list = screen.getByRole('table', { name: t('c02.results', ['OCT 2026']) });
    const pager = (key: string) => within(list).getByRole('button', { name: t(key) });
    expect(pager('page.prev')).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: t('c02.act.components') }));
    expect(await screen.findByText('Dearness Allowance')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('c02.act.components') }));
    expect(screen.queryByText('Dearness Allowance')).toBeNull();
    await userEvent.click(pager('page.next'));
    expect(await screen.findByText('Employee 21')).toBeTruthy();
    await userEvent.click(pager('page.last'));
    expect(await screen.findByText('Employee 101')).toBeTruthy();
    expect(pager('page.next')).toBeDisabled();
    await userEvent.click(pager('page.first'));
    expect(await screen.findByText('Employee 1')).toBeTruthy();
    // A viewer has no action buttons.
    expect(button('verify')).toBeNull();
  });

  it('C-02 l.3686-3692: the Pattern filter asks the server; Pattern / Scale, Basic and DA per member; C-08 link', async () => {
    const calls = routes(computed());
    wrap(<PayrollCycle />, ['C-02:VIEW']);
    const first = (await screen.findByText('Employee 1')).closest('tr') as HTMLElement;
    expect(within(first).getByText('IDA · E-4')).toBeTruthy();
    expect(within(first).getByText('30,000')).toBeTruthy();
    expect(within(first).getByText('15,000')).toBeTruthy();
    // A member without Basic, DA or HRA lines shows dashes, never zero.
    const third = screen.getByText('Employee 3').closest('tr') as HTMLElement;
    expect(within(third).getAllByText('—')).toHaveLength(3);
    const pattern = screen.getByLabelText(t('c02.payPattern'));
    expect(pattern).toHaveValue('');
    expect(await within(pattern).findByText('CDA only')).toBeTruthy();
    fireEvent.change(pattern, { target: { value: 'CDA' } });
    await waitFor(() => expect(calls.some((c) => c.url.includes('payPattern=CDA'))).toBe(true));
    const link = screen.getByRole('link', { name: t('c02.exceptions') });
    expect(link.getAttribute('href')).toMatch(/s=X-05&period=2026-10&cycle=\d+/);
  });

  it('SRS-G-21: the lock is a request with a reason and the OTP; a pending request disables it', async () => {
    const verified = computed({
      status: 'VERIFIED',
      verifiedBy: 'checker.delhi@fci-demo.test',
      verifiedAt: '2026-10-21T09:00:00Z',
      resultsRunType: 'SIMULATION',
    });
    const calls = routes(verified, {
      'POST /v1/cycles/2026-10/lock': (_url, init) =>
        (JSON.parse(String(init?.body)) as { otp?: unknown }).otp
          ? verified
          : problem(428, 'AUTH-428-OTP', [6]),
    });
    wrap(<PayrollCycle />, me(ALL, { atHeadquarters: false }));
    expect(await screen.findByText(t('cyc.resultsSimulation', [31]))).toBeTruthy();
    await userEvent.click(button('lock') as HTMLElement);
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    await userEvent.click(button('lock') as HTMLElement);
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'October payroll');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submit') }),
    );
    await userEvent.type(await screen.findByLabelText(t('otp.code')), '333444');
    await userEvent.click(screen.getByRole('button', { name: t('otp.verify') }));
    expect(await screen.findByText(t('cyc.done.lock'))).toBeTruthy();
    expect(sent(calls, 'POST', '/lock').at(-1)).toEqual({
      reason: 'October payroll',
      otp: { challengeId: 6, code: '333444' },
    });
  });

  it('SRS-15-11: at Headquarters the lock is disabled with the reason, whatever the cycle state', async () => {
    routes(
      computed({
        status: 'VERIFIED',
        verifiedBy: 'checker.delhi@fci-demo.test',
        verifiedAt: '2026-10-21T09:00:00Z',
        resultsRunType: 'SIMULATION',
      }),
    );
    wrap(
      <PayrollCycle />,
      me(ALL, { atHeadquarters: true, location: { code: 'HQ001', label: 'HQ — Headquarters (HQ001)' } }),
    );
    const lock = await screen.findByRole('button', { name: t('cyc.action.lock') });
    expect(lock).toBeDisabled();
    expect(lock).toHaveAttribute('title', t('error.AUTHZ-403-HQ-LOCK'));
  });

  it('a pending lock request is shown and the lock cannot be asked again; a locked cycle shows who locked', async () => {
    routes(
      computed({
        status: 'LOCKED',
        verifiedBy: 'c',
        verifiedAt: '2026-10-21T09:00:00Z',
        lockedBy: 'checker.hq@fci-demo.test',
        lockedAt: '2026-10-22T09:00:00Z',
        lockApprovalStatus: 'APPROVED',
        lockApprovalId: 77,
      }),
    );
    wrap(<PayrollCycle />, me(ALL, { atHeadquarters: false }));
    expect(await screen.findByText(t('status.APPROVED'))).toBeTruthy();
    expect(screen.getByText(/checker.hq@fci-demo.test/)).toBeTruthy();
    expect(button('lock')).toBeNull();
  });

  it('SRS-15-12: Posted to EBS and the supplementary window are flags beside the status, not steps', async () => {
    routes(
      computed({
        status: 'LOCKED',
        lockedBy: 'c',
        lockedAt: '2026-10-22T09:00:00Z',
        supplementaryOpen: true,
      }),
    );
    wrap(<PayrollCycle />, me(ALL, { atHeadquarters: false }));
    const posted = await screen.findByText(t('cyc.postedToEbs'));
    expect(posted.nextElementSibling?.textContent).toBe(t('common.no'));
    expect(screen.getByText(t('cyc.supplementaryOpen')).nextElementSibling?.textContent).toBe(
      t('common.yes'),
    );
  });

  it('a cycle not yet initiated shows no flags', async () => {
    routes(cycle());
    wrap(<PayrollCycle />, me(ALL, { atHeadquarters: false }));
    await screen.findByText(t('cyc.status'));
    expect(screen.queryByText(t('cyc.postedToEbs'))).toBeNull();
  });

  it('a lock request awaiting approval disables the lock', async () => {
    routes(computed({ status: 'VERIFIED', lockApprovalStatus: 'PENDING_APPROVAL', lockApprovalId: 77 }));
    wrap(<PayrollCycle />, me(ALL, { period: '2026-10' }));
    expect(await screen.findByText(t('status.PENDING_APPROVAL'))).toBeTruthy();
    expect((button('lock') as HTMLButtonElement).disabled).toBe(true);
  });

  it('SRS-15-28: a held lease names its holder and since when, disables the actions with the reason, and the maker asks for its release', async () => {
    const held = lease();
    const initiated = cycle({
      id: 9,
      status: 'INITIATED',
      initiatedBy: 'm',
      initiatedAt: '2026-10-19T09:00:00Z',
    });
    const calls = routes(cycle({ ...initiated, lease: held }), {
      'POST /v1/cycles/2026-10/lease-releases': () => ({
        status: 202,
        body: cycle({ ...initiated, lease: held, leaseReleaseApprovalId: 88 }),
      }),
    });
    wrap(<PayrollCycle />, me(ALL, { atHeadquarters: false }));
    const banner = await screen.findByTestId('cycle-lease');
    expect(banner.textContent).toContain('batch.compute@fci-demo.test');
    expect(banner.textContent).toContain(t('cyc.leaseAction.COMPUTE'));
    const freeze = button('freeze') as HTMLButtonElement;
    expect(freeze).toBeDisabled();
    expect(freeze).toHaveAttribute('title', heldBy(held));

    // The reason is required; cancelling sends nothing.
    await userEvent.click(button('leaseRelease') as HTMLElement);
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(t('cyc.leaseReleaseNote'))).toBeTruthy();
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
    expect(sent(calls, 'POST', '/lease-releases')).toHaveLength(0);
    await userEvent.click(button('leaseRelease') as HTMLElement);
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'Run stuck since noon');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submit') }),
    );
    expect(await screen.findByText(t('cyc.done.leaseRelease'))).toBeTruthy();
    expect(sent(calls, 'POST', '/lease-releases')).toEqual([{ reason: 'Run stuck since noon' }]);
  });

  it('SRS-15-28: a pending release request disables a second one; a viewer cannot ask; the lock waits too', async () => {
    const held = lease({ action: 'FREEZE', heldBy: 'maker.other@fci-demo.test', runId: null });
    routes(
      computed({
        status: 'VERIFIED',
        verifiedBy: 'c',
        verifiedAt: '2026-10-21T09:00:00Z',
        lease: held,
        leaseReleaseApprovalId: 88,
      }),
    );
    wrap(<PayrollCycle />, me(ALL, { atHeadquarters: false }));
    expect(await screen.findByText(t('cyc.leaseRelease'))).toBeTruthy();
    const release = button('leaseRelease') as HTMLButtonElement;
    expect(release).toBeDisabled();
    expect(release).toHaveAttribute('title', t('cyc.leaseReleasePending'));
    const lock = button('lock') as HTMLButtonElement;
    expect(lock).toBeDisabled();
    expect(lock).toHaveAttribute('title', heldBy(held));
  });

  it('SRS-15-28: a held lease disables Verify with its holder; nothing is sent', async () => {
    const held = lease();
    const calls = routes(computed({ lease: held }));
    wrap(<PayrollCycle />, me(CHECKER, { atHeadquarters: false }));
    await screen.findByTestId('cycle-lease');
    const verify = button('verify') as HTMLButtonElement;
    expect(verify).toBeDisabled();
    expect(verify).toHaveAttribute('title', heldBy(held));
    expect(sent(calls, 'POST', '/verify')).toHaveLength(0);
  });

  it('SRS l.1734: a re-computation still RUNNING (lease lapsed) disables Verify naming the run; a FAILED or a dry run does not', async () => {
    routes(
      computed({
        lease: lease({ expired: true }),
        runs: [run(), run({ id: 32, runNo: 2, status: 'RUNNING', completedAt: null })],
      }),
    );
    wrap(<PayrollCycle />, me(CHECKER, { atHeadquarters: false }));
    const verify = (await screen.findByRole('button', { name: t('cyc.action.verify') })) as HTMLButtonElement;
    expect(verify).toBeDisabled();
    expect(verify).toHaveAttribute('title', t('error.CYC-409-RUNNING', [2]));
  });

  it('SRS l.1734: only an ACTUAL run that is RUNNING holds Verify', () => {
    expect(runningRunNo([])).toBeNull();
    expect(
      runningRunNo([run({ status: 'FAILED' }), run({ type: 'SIMULATION', status: 'RUNNING' })]),
    ).toBeNull();
    expect(runningRunNo([run(), run({ runNo: 3, status: 'RUNNING' })])).toBe(3);
  });

  it('SRS-15-28: Verify is open once the lease has expired and no run is going on', async () => {
    routes(computed({ lease: lease({ expired: true }) }));
    wrap(<PayrollCycle />, me(CHECKER, { atHeadquarters: false }));
    const verify = (await screen.findByRole('button', { name: t('cyc.action.verify') })) as HTMLButtonElement;
    expect(verify).toBeEnabled();
    expect(verify).not.toHaveAttribute('title');
  });

  it('SRS-15-28: without the LEASE_RELEASE permission the release is not offered', async () => {
    routes(cycle({ id: 9, status: 'FROZEN', lease: lease() }));
    wrap(<PayrollCycle />, me(['C-02:VIEW', 'C-13:CREATE'], { atHeadquarters: false }));
    await screen.findByTestId('cycle-lease');
    expect(button('leaseRelease')).toBeNull();
    expect(button('compute')).toBeDisabled();
  });

  it('BR-CYC-015: an expired lease holds nothing: it is shown as stopped and the actions are open', async () => {
    routes(cycle({ id: 9, status: 'INITIATED', lease: lease({ expired: true }) }));
    wrap(<PayrollCycle />, me(ALL, { atHeadquarters: false }));
    const banner = await screen.findByTestId('cycle-lease');
    expect(banner.textContent).toBe(
      t('cyc.leaseExpired', [
        t('cyc.leaseAction.COMPUTE'),
        'batch.compute@fci-demo.test',
        dateTime('2026-10-20T10:00:00Z'),
      ]),
    );
    expect(button('freeze')).not.toBeDisabled();
    expect(button('leaseRelease')).toBeNull();
  });

  it('SRS-15-30: the checker returns a computed cycle with a scope and a reason; the scope asks for what it needs', async () => {
    const calls = routes(computed(), {
      'POST /v1/cycles/2026-10/returns': () => cycle({ id: 9, status: 'FROZEN', openReturn: opened() }),
    });
    wrap(<PayrollCycle />, me(CHECKER, { atHeadquarters: false }));
    await userEvent.click(
      (await screen.findByRole('button', { name: t('cyc.action.return') })) as HTMLElement,
    );
    const dialog = screen.getByRole('dialog');
    const submit = within(dialog).getByRole('button', { name: t('cyc.action.return') });
    // A reason is needed; then each scope its detail.
    expect(submit).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText(t('common.reason')), 'Attendance not matched');
    expect(submit).not.toBeDisabled();
    await userEvent.click(within(dialog).getByLabelText(t('cyc.returnScope.ORG_UNIT')));
    expect(submit).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText(t('cyc.returnOffice')), 'DP-WB01');
    expect(submit).not.toBeDisabled();
    await userEvent.click(within(dialog).getByLabelText(t('cyc.returnScope.MEMBERS')));
    expect(submit).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText(t('cyc.returnMembers')), '100001, 100003\n100001');
    expect(within(dialog).getByText(t('cyc.returnMembersCount', [2]))).toBeTruthy();
    await userEvent.click(submit);
    expect(await screen.findByText(t('cyc.done.return'))).toBeTruthy();
    expect(sent(calls, 'POST', '/returns')).toEqual([
      { scope: 'MEMBERS', reason: 'Attendance not matched', members: ['100001', '100003'] },
    ]);
  });

  it('SRS-15-30: the return dialog sends the office for an office scope, nothing else for the whole cycle, and cancels', async () => {
    const calls = routes(computed(), {
      'POST /v1/cycles/2026-10/returns': () => computed(),
    });
    wrap(<PayrollCycle />, me(CHECKER, { atHeadquarters: false }));
    const open = async () => {
      await userEvent.click(
        (await screen.findByRole('button', { name: t('cyc.action.return') })) as HTMLElement,
      );
      return screen.getByRole('dialog');
    };
    let dialog = await open();
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    dialog = await open();
    await userEvent.click(within(dialog).getByLabelText(t('cyc.returnScope.ORG_UNIT')));
    await userEvent.type(within(dialog).getByLabelText(t('cyc.returnOffice')), ' DP-WB01 ');
    await userEvent.type(within(dialog).getByLabelText(t('common.reason')), 'Depot wages');
    await userEvent.click(within(dialog).getByRole('button', { name: t('cyc.action.return') }));
    await screen.findByText(t('cyc.done.return'));
    dialog = await open();
    await userEvent.type(within(dialog).getByLabelText(t('common.reason')), 'Whole cycle');
    // Non-ASCII text is refused before it is sent (R-ASCII).
    await userEvent.type(within(dialog).getByLabelText(t('common.reason')), ' é');
    expect(within(dialog).getByRole('alert').textContent).toBe(t('common.plainTextOnly'));
    expect(within(dialog).getByRole('button', { name: t('cyc.action.return') })).toBeDisabled();
    await userEvent.clear(within(dialog).getByLabelText(t('common.reason')));
    await userEvent.type(within(dialog).getByLabelText(t('common.reason')), 'Whole cycle');
    await userEvent.click(within(dialog).getByRole('button', { name: t('cyc.action.return') }));
    await waitFor(() => expect(sent(calls, 'POST', '/returns')).toHaveLength(2));
    expect(sent(calls, 'POST', '/returns')).toEqual([
      { scope: 'ORG_UNIT', reason: 'Depot wages', orgUnitCode: 'DP-WB01' },
      { scope: 'INSTANCE', reason: 'Whole cycle' },
    ]);
  });

  it('SRS-15-30: an open return is shown to the maker with its scope; the checker withdraws it with a reason', async () => {
    const frozen = cycle({ id: 9, status: 'FROZEN', openReturn: opened() });
    const calls = routes(frozen, {
      'POST /v1/cycles/2026-10/returns/12/withdraw': () => computed({ status: 'VERIFIED' }),
    });
    wrap(<PayrollCycle />, me(CHECKER, { atHeadquarters: false }));
    const banner = await screen.findByTestId('cycle-return');
    expect(banner.textContent).toContain('Attendance not matched');
    expect(banner.textContent).toContain(t('cyc.returnedMembers', ['100001, 100003']));
    expect(banner.textContent).toContain(t('cyc.recomputeHint'));
    expect(button('return')).toBeNull();
    // Cancelled first: nothing is sent and the return stays open.
    await userEvent.click(button('withdraw') as HTMLElement);
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(sent(calls, 'POST', '/withdraw')).toEqual([]);
    await userEvent.click(button('withdraw') as HTMLElement);
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'Wrong month');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submit') }),
    );
    expect(await screen.findByText(t('cyc.done.withdraw'))).toBeTruthy();
    expect(sent(calls, 'POST', '/withdraw')).toEqual([{ reason: 'Wrong month' }]);
  });

  it('SRS-15-30: the return names an office or the whole cycle', async () => {
    routes(
      cycle({
        id: 9,
        status: 'FROZEN',
        openReturn: opened({ scope: 'ORG_UNIT', orgUnitCode: 'DP-WB01', members: [] }),
      }),
    );
    const { unmount } = wrap(<PayrollCycle />, me(MAKER, { atHeadquarters: false }));
    expect((await screen.findByTestId('cycle-return')).textContent).toContain(
      t('cyc.returnedOffice', ['DP-WB01']),
    );
    // The maker sees it but cannot withdraw it.
    expect(button('withdraw')).toBeNull();
    unmount();
    routes(cycle({ id: 9, status: 'FROZEN', openReturn: opened({ scope: 'INSTANCE', members: [] }) }));
    wrap(<PayrollCycle />, me(MAKER, { atHeadquarters: false }));
    expect((await screen.findByTestId('cycle-return')).textContent).toContain(t('cyc.returnScope.INSTANCE'));
  });

  it('SRS-15-31: the maker asks for an un-freeze with a reason; a waiting request disables the next one and the return', async () => {
    const calls = routes(computed(), {
      'POST /v1/cycles/2026-10/unfreeze-requests': () => ({
        status: 202,
        body: computed({ unfreezeApprovalId: 77 }),
      }),
    });
    wrap(<PayrollCycle />, me(MAKER, { atHeadquarters: false }));
    await userEvent.click(
      (await screen.findByRole('button', { name: t('cyc.action.unfreeze') })) as HTMLElement,
    );
    expect(within(screen.getByRole('dialog')).getByText(t('cyc.unfreezeNote'))).toBeTruthy();
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    await userEvent.click(button('unfreeze') as HTMLElement);
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'Late increment order');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submit') }),
    );
    expect(await screen.findByText(t('cyc.done.unfreeze'))).toBeTruthy();
    expect(sent(calls, 'POST', '/unfreeze-requests')).toEqual([{ reason: 'Late increment order' }]);
  });

  it('SRS-15-31: a waiting un-freeze, a waiting lock or a held lease disables the corrections with the reason; none after lock', async () => {
    routes(computed({ status: 'VERIFIED', unfreezeApprovalId: 77 }));
    const first = wrap(<PayrollCycle />, me([...CHECKER, 'C-02:UNFREEZE'], { atHeadquarters: false }));
    expect(await screen.findByText(t('cyc.unfreezeRequest'))).toBeTruthy();
    expect(button('return')).toBeDisabled();
    expect(button('return')).toHaveAttribute('title', t('cyc.blocked.unfreezePending'));
    expect(button('unfreeze')).toHaveAttribute('title', t('cyc.blocked.unfreezePending'));
    first.unmount();

    routes(computed({ status: 'VERIFIED', lockApprovalStatus: 'PENDING_APPROVAL', lockApprovalId: 70 }));
    const second = wrap(<PayrollCycle />, me(CHECKER, { atHeadquarters: false }));
    expect(
      (await screen.findByRole('button', { name: t('cyc.action.return') })) as HTMLElement,
    ).toHaveAttribute('title', t('cyc.blocked.lockPending'));
    second.unmount();

    const held = lease();
    routes(computed({ lease: held }));
    const third = wrap(<PayrollCycle />, me(MAKER, { atHeadquarters: false }));
    expect(
      (await screen.findByRole('button', { name: t('cyc.action.unfreeze') })) as HTMLElement,
    ).toHaveAttribute('title', heldBy(held));
    third.unmount();

    routes(computed({ status: 'LOCKED', lockedBy: 'c', lockedAt: '2026-10-22T09:00:00Z' }));
    wrap(<PayrollCycle />, me([...CHECKER, ...MAKER], { atHeadquarters: false }));
    await screen.findByText(t('cyc.postedToEbs'));
    expect(button('return')).toBeNull();
    expect(button('unfreeze')).toBeNull();
  });

  it('a refused board is shown', async () => {
    routes(problem(403, 'AUTH-403-FORBIDDEN'));
    wrap(<PayrollCycle />, me(ALL, { atHeadquarters: false }));
    expect(await screen.findByText(t('error.AUTH-403-FORBIDDEN'))).toBeTruthy();
  });
});
