import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PayrollCycle } from '@/modules/jobs';
import { resultsExportUrl } from '@/modules/jobs/orchestration/cycleQueries';
import { backend, me, paged, problem, referenceLists, t, wrap } from './support/harness';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push, replace: vi.fn() }) }));

/**
 * C-02 as Wireframes v6.1 draws it (OD-121; SRS l.3686-3692, FRS §7.4): the drawn sections, the Maker's re-run,
 * the filters from the M-16 lists, the Calculation trace, the PLI invoice card, and the cycle actions kept in
 * their closed section.
 */

afterEach(() => {
  vi.unstubAllGlobals();
  push.mockReset();
  window.location.hash = '';
});

const MAKER = ['C-02:VIEW', 'C-02:CREATE', 'C-02:EXPORT', 'C-13:CREATE'];
const CHECKER = ['C-02:VIEW', 'C-02:APPROVE', 'C-02:EXPORT'];
const ADMIN = ['C-02:VIEW'];

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

const row = (id: number, over: object = {}) => ({
  resultId: id,
  eis: String(104880 + id),
  name: `Employee ${id}`,
  gross: 127350,
  deductions: 35387,
  net: 91963,
  status: 'CURRENT',
  locked: true,
  payPattern: 'IDA',
  basic: 62400,
  da: 31200,
  cityClass: 'X',
  scale: 'E-4',
  hra: null,
  other: 33750,
  rowStatus: 'COMPUTED',
  statusGroup: 'COMPUTED',
  ...over,
});

const SUMMARY = {
  ida: { members: 6, exceptions: 2 },
  cda: { members: 4, exceptions: 1 },
  gross: 312400000,
  deductions: 70600000,
  net: 241800000,
  held: 1,
  shown: { members: 10, gross: 312400000, deductions: 70600000, net: 241800000 },
};

const LINES = [
  {
    payCode: 101,
    name: 'Basic Pay',
    head: 'BASIC',
    amount: 62400,
    rate: null,
    basis: null,
    rule: null,
    ruleVersion: null,
  },
  {
    payCode: 111,
    name: 'Dearness Allowance',
    head: 'DA',
    amount: 31200,
    rate: 50,
    basis: 62400,
    rule: 'DA.IDA.RATE',
    ruleVersion: 14,
  },
  {
    payCode: 131,
    name: 'City Compensatory Allowance',
    head: 'EARNING',
    amount: 3600,
    rate: null,
    basis: null,
    rule: null,
    ruleVersion: null,
  },
  {
    payCode: 904,
    name: 'CPF',
    head: 'CPF',
    amount: 11232,
    rate: null,
    basis: null,
    rule: null,
    ruleVersion: null,
  },
  {
    payCode: 905,
    name: 'VPF',
    head: 'VPF',
    amount: 5000,
    rate: null,
    basis: null,
    rule: null,
    ruleVersion: null,
  },
  {
    payCode: 901,
    name: 'PT',
    head: 'PT',
    amount: 200,
    rate: null,
    basis: null,
    rule: null,
    ruleVersion: null,
  },
  {
    payCode: 910,
    name: 'Festival Advance recovery',
    head: 'DEDUCTION',
    amount: 1500,
    rate: null,
    basis: null,
    rule: null,
    ruleVersion: null,
  },
];

const trace = (over: object = {}) => ({
  resultId: 2,
  eis: '104882',
  name: 'Employee 2',
  period: '2026-01',
  payPattern: 'IDA',
  cityClass: 'X',
  stagnationCase: false,
  accommodation: { type: 'SELF_LEASE', amount: 28000, hraSuppressed: true },
  gross: 127350,
  deductions: 35387,
  net: 91963,
  lines: LINES,
  ...over,
});

const pli = (id: number, over: object = {}) => ({
  id,
  entry: `PLI-2025-000${id}`,
  eis: '104882',
  member: 'Employee 2',
  type: 'PAYMENT',
  finYear: '2025-26',
  amount: 42600,
  invoiceNo: 202601000622,
  invoiceStatus: 'SENT_TO_APPS',
  sentToAppsAt: '2026-01-28T00:00:00Z',
  appsCancelStatus: 'NOT_REQUESTED',
  ...over,
});

const LISTS = {
  C02_PAY_PATTERN_FILTER: [
    ['IDA', 'IDA only'],
    ['CDA', 'CDA only'],
  ],
  C02_RESULT_FILTER: [
    ['COMPUTED', 'Computed'],
    ['EXCEPTION', 'Exception'],
    ['HELD', 'Held'],
  ],
  C02_RESULT_STATUS: [
    ['COMPUTED', 'Computed'],
    ['NEGATIVE_NET', 'Negative net'],
  ],
};

function routes(over: Record<string, (url: string) => unknown> = {}) {
  const lists = referenceLists(LISTS as never);
  return backend({
    'GET /v1/reference/lists': () => lists,
    'GET /v1/cycles/2026-10': () => cycle(),
    'GET /v1/cycles/2026-10/pre-run-checks': () => [],
    'GET /v1/cycles/2026-10/results-period': () => ({ period: '2026-01', runType: 'ACTUAL' }),
    'GET /v1/cycles/2026-01/results/summary': () => SUMMARY,
    'GET /v1/cycles/2026-01/results?': (url) =>
      paged([row(2), row(4, { rowStatus: 'NEGATIVE_NET', statusGroup: 'EXCEPTION', net: -2710 })], 2, url),
    'GET /v1/cycles/2026-01/results/2/trace': () => trace(),
    'GET /v1/cycles/2026-01/results/2': (url) => paged(LINES, LINES.length, url),
    'GET /v1/cycles/2026-01/pli-payments': (url) =>
      paged(
        [
          pli(1),
          pli(2, { type: 'ADJUSTMENT', invoiceNo: null, invoiceStatus: 'NOT_RAISED', sentToAppsAt: null }),
          pli(3, { invoiceStatus: 'CANCELLED_IN_APPS', appsCancelStatus: 'CONFIRMED' }),
        ],
        3,
        url,
      ),
    ...over,
  });
}

const listTable = () => screen.findByRole('table', { name: t('c02.results', ['JAN 2026']) });

describe('C-02 as drawn', () => {
  it('SRS-G-54: the drawn sections, and the latest run while the session period has none', async () => {
    routes();
    wrap(<PayrollCycle />, me(MAKER));
    const list = await listTable();
    expect(await within(list).findByText('Employee 2')).toBeTruthy();
    expect(screen.getByTestId('c02-other-period')).toHaveTextContent('JAN 2026');
    for (const key of ['c02.tile.ida', 'c02.tile.cda', 'c02.tile.money', 'c02.tile.held']) {
      expect(screen.getByText(t(key))).toBeTruthy();
    }
    expect(await screen.findByText(t('c02.tile.moneyBig', ['31.24', '24.18']))).toBeTruthy();
    expect(screen.getByText(t('c02.tile.moneySub', ['7.06']))).toBeTruthy();
    expect(screen.getByText(t('c02.tile.computed', [2]))).toBeTruthy();
    expect(screen.getByText(t('c02.totals', ['10']))).toBeTruthy();
    expect(
      await screen.findByRole('heading', { name: t('c02.trace.title', ['104882', 'JAN 2026']) }),
    ).toBeTruthy();
    expect(screen.getByRole('heading', { name: t('c02.pli') })).toBeTruthy();
  });

  it('SRS-G-29: a period with its own run shows no note and reads that period', async () => {
    const calls = routes({
      'GET /v1/cycles/2026-10/results-period': () => ({ period: '2026-10', runType: 'SIMULATION' }),
      'GET /v1/cycles/2026-10/results/summary': () => SUMMARY,
      'GET /v1/cycles/2026-10/results?': (url) => paged([], 0, url),
      'GET /v1/cycles/2026-10/pli-payments': (url) => paged([], 0, url),
    });
    wrap(<PayrollCycle />, me(MAKER));
    expect(await screen.findByRole('table', { name: t('c02.results', ['OCT 2026']) })).toBeTruthy();
    expect(await screen.findByText(t('c02.noResults'))).toBeTruthy();
    await waitFor(() => expect(calls.some((c) => c.url.includes('2026-10/results/summary'))).toBe(true));
    expect(screen.queryByTestId('c02-other-period')).toBeNull();
    expect(await screen.findByText(t('wf.inv.none'))).toBeTruthy();
  });

  it('a unit without any run: empty sections, the trace says nothing is selected', async () => {
    routes({
      'GET /v1/cycles/2026-10/results-period': () => ({ status: 204, body: null }),
      'GET /v1/cycles/2026-10/results/summary': () => ({
        ...SUMMARY,
        ida: { members: 0, exceptions: 0 },
        cda: { members: 0, exceptions: 0 },
      }),
      'GET /v1/cycles/2026-10/results?': (url) => paged([], 0, url),
      'GET /v1/cycles/2026-10/pli-payments': (url) => paged([], 0, url),
    });
    wrap(<PayrollCycle />, me(ADMIN));
    expect(await screen.findByRole('heading', { name: t('c02.trace.none') })).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('c02.act.trace') }));
    expect(await screen.findByText(t('c02.trace.pickRow'))).toBeTruthy();
  });

  it('BR-CYC-008: the Maker re-runs only a frozen or computed cycle; the C-13 job is the route', async () => {
    routes({ 'GET /v1/cycles/2026-10': () => cycle({ id: 9, status: 'FROZEN' }) });
    wrap(<PayrollCycle />, me(MAKER));
    await listTable();
    await waitFor(() =>
      expect(screen.getByRole('button', { name: t('c02.act.rerun') })).not.toHaveAttribute('data-guard'),
    );
    await userEvent.click(screen.getByRole('button', { name: t('c02.act.rerun') }));
    expect(push).toHaveBeenCalledWith('/payroll?s=C-13');
  });

  it('BR-CYC-008: the Maker is told why a re-run is not open yet', async () => {
    routes({ 'GET /v1/cycles/2026-10': () => cycle({ id: 9, status: 'LOCKED' }) });
    wrap(<PayrollCycle />, me(MAKER));
    await listTable();
    await waitFor(() =>
      expect(screen.getByRole('button', { name: t('c02.act.rerun') })).toHaveAttribute('data-guard'),
    );
    await userEvent.click(screen.getByRole('button', { name: t('c02.act.rerun') }));
    expect(await screen.findByText(t('c02.why.rerunState'))).toBeTruthy();
    expect(push).not.toHaveBeenCalled();
  });

  it('SRS-G-1: a role that may not run it sees the re-run disabled with the reason; export as drawn', async () => {
    routes();
    wrap(<PayrollCycle />, me(ADMIN));
    const rerun = await screen.findByRole('button', { name: t('c02.act.rerun') });
    expect(rerun).toBeDisabled();
    expect(rerun).toHaveAttribute('data-why', t('c02.why.makerOnly'));
    await userEvent.click(screen.getByRole('button', { name: t('wf.export') }));
    expect(await screen.findByText(t('c02.why.export'))).toBeTruthy();
  });

  it('OD-123: the filters come from the M-16 lists and go to the server; export carries them', async () => {
    const calls = routes();
    wrap(<PayrollCycle />, me(CHECKER));
    await listTable();
    const pattern = screen.getByLabelText(t('c02.payPattern'));
    expect(within(pattern).getByText(t('c02.allPatterns'))).toBeTruthy();
    expect(await within(pattern).findByText('IDA only')).toBeTruthy();
    fireEvent.change(pattern, { target: { value: 'CDA' } });
    fireEvent.change(screen.getByLabelText(t('c02.col.status')), { target: { value: 'HELD' } });
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'sha%rma' } });
    await waitFor(() =>
      expect(
        calls.some(
          (c) => c.url.includes('/results?') && c.url.includes('payPattern=CDA&status=HELD&search=sharma'),
        ),
      ).toBe(true),
    );
    expect(calls.some((c) => c.url.includes('/results/summary?payPattern=CDA'))).toBe(true);
    expect(screen.getByRole('button', { name: t('wf.export') })).toBeTruthy();
    expect(resultsExportUrl('2026-01', { payPattern: 'CDA', status: '', search: '' })).toContain(
      '/v1/cycles/2026-01/results/export?payPattern=CDA',
    );
    expect(resultsExportUrl('2026-01', { payPattern: '', status: '', search: '' })).toMatch(/\/export$/);
  });

  it('BR-AUDIT-005: a row is viewed, never edited: Edit is off with the reason of its state', async () => {
    routes();
    wrap(<PayrollCycle />, me(MAKER));
    const list = await listTable();
    const computed = (await within(list).findByText('Employee 2')).closest('tr') as HTMLElement;
    expect(within(computed).getByRole('button', { name: t('c02.editName', ['104882']) })).toHaveAttribute(
      'data-why',
      t('c02.why.locked'),
    );
    const negative = within(list).getByText('Employee 4').closest('tr') as HTMLElement;
    expect(within(negative).getByRole('button', { name: t('c02.editName', ['104884']) })).toHaveAttribute(
      'data-why',
      t('c02.why.exceptions'),
    );
    expect(within(negative).getByText('Negative net')).toBeTruthy();
    await userEvent.click(within(computed).getByRole('button', { name: t('c02.viewName', ['104882']) }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('IDA · E-4')).toBeTruthy();
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('FRS 7.4: the trace reads the run: rate and rule, the lease, and each recovery; Full trace lists every line', async () => {
    routes();
    wrap(<PayrollCycle />, me(MAKER));
    expect(await screen.findByText(t('c02.trace.basicFrom', ['62,400']))).toBeTruthy();
    expect(screen.getByText(t('c02.trace.rateRule', ['50', 'DA.IDA.RATE', 14]))).toBeTruthy();
    expect(
      screen.getByText(
        t('c02.trace.accomSuppressed', [t('c02.trace.accomPaid', [t('c02.accom.SELF_LEASE'), '28,000'])]),
      ),
    ).toBeTruthy();
    expect(screen.getByText(t('c02.trace.hraSuppressed'))).toBeTruthy();
    expect(screen.getByText(t('c02.trace.leaseRecord', ['28,000']))).toBeTruthy();
    expect(screen.getByText(t('c02.trace.vpfDeclared', ['5,000']))).toBeTruthy();
    expect(screen.getByText('₹ 1,500 Festival Advance recovery')).toBeTruthy();
    expect(screen.getByText('City Compensatory Allowance ₹ 3,600')).toBeTruthy();
    expect(screen.getByText(t('c02.trace.stagNo'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('c02.trace.full') }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('111 Dearness Allowance')).toBeTruthy();
    expect(within(dialog).getByText(/DA\.IDA\.RATE/)).toBeTruthy();
    expect(within(dialog).getByText(t('c02.rupees', ['62,400']))).toBeTruthy();
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
  });

  it('FRS 7.4: a member without lease lines: the trace says so; a paid lease keeps the HRA', async () => {
    routes({
      'GET /v1/cycles/2026-01/results/2/trace': () =>
        trace({
          stagnationCase: true,
          cityClass: null,
          accommodation: null,
          lines: [
            { ...LINES[0], head: 'HRA', amount: 12474, rate: 30, rule: null },
            { ...LINES[0], head: 'TDS', amount: 9055 },
          ],
        }),
    });
    wrap(<PayrollCycle />, me(MAKER));
    expect(await screen.findByText(t('c02.trace.accomNone'))).toBeTruthy();
    expect(screen.getByText(t('c02.trace.stagYes'))).toBeTruthy();
    expect(screen.getByText(`— / ${t('c02.trace.rate', ['30'])}`)).toBeTruthy();
    expect(screen.getByText('₹ 12,474')).toBeTruthy();
    expect(screen.getByText('₹ 9,055 / —')).toBeTruthy();
  });

  it('a lease the HRA is not suppressed by; a trace refused is shown', async () => {
    routes({
      'GET /v1/cycles/2026-01/results/2/trace': () =>
        trace({ accommodation: { type: 'THIRD_PARTY', amount: 18500, hraSuppressed: false } }),
    });
    wrap(<PayrollCycle />, me(MAKER));
    expect(
      await screen.findByText(t('c02.trace.accomPaid', [t('c02.accom.THIRD_PARTY'), '18,500'])),
    ).toBeTruthy();
  });

  // SRS-1-3, SRS-11-17: the row says what the result did, never "suppressed" beside a paid amount.
  it('a locked result that paid HRA under a suppressing record shows the conflict', async () => {
    routes({
      'GET /v1/cycles/2026-01/results/2/trace': () =>
        trace({
          accommodation: { type: 'THIRD_PARTY', amount: 18500, hraSuppressed: true },
          lines: [{ ...LINES[0], head: 'HRA', amount: 2868, rate: 30, rule: null }],
        }),
    });
    wrap(<PayrollCycle />, me(MAKER));
    expect(
      await screen.findByText(
        t('c02.trace.accomConflict', [t('c02.trace.accomPaid', [t('c02.accom.THIRD_PARTY'), '18,500'])]),
      ),
    ).toBeTruthy();
    expect(screen.getByText('₹ 2,868')).toBeTruthy();
    expect(screen.queryByText(t('c02.trace.hraSuppressed'))).toBeNull();
  });

  it('quarters have no lease amount: the type alone, HRA suppressed', async () => {
    routes({
      'GET /v1/cycles/2026-01/results/2/trace': () =>
        trace({
          accommodation: { type: 'DEPARTMENTAL_QUARTERS', amount: null, hraSuppressed: true },
        }),
    });
    wrap(<PayrollCycle />, me(MAKER));
    expect(
      await screen.findByText(t('c02.trace.accomSuppressed', [t('c02.accom.DEPARTMENTAL_QUARTERS')])),
    ).toBeTruthy();
    expect(screen.getByText(t('c02.trace.hraSuppressed'))).toBeTruthy();
  });

  it('a refused results read is shown', async () => {
    routes({
      'GET /v1/cycles/2026-10/results-period': () => problem(403, 'AUTHZ-403-SCOPE'),
      'GET /v1/cycles/2026-10/results/summary': () => SUMMARY,
      'GET /v1/cycles/2026-10/results?': (url) => paged([], 0, url),
      'GET /v1/cycles/2026-10/pli-payments': (url) => paged([], 0, url),
    });
    wrap(<PayrollCycle />, me(MAKER));
    expect(await screen.findByText(t('error.AUTHZ-403-SCOPE'))).toBeTruthy();
  });

  it('BR-AUDIT-009 / 010: the PLI card: the sent invoice, the OTP adjustment, a cancelled version', async () => {
    routes();
    wrap(<PayrollCycle />, me(MAKER));
    const sent = (await screen.findByText('PLI-2025-0001')).closest('tr') as HTMLElement;
    expect(within(sent).getByText(t('c02.pli.PAYMENT', ['2025-26']))).toBeTruthy();
    expect(within(sent).getByText('202601000622')).toBeTruthy();
    expect(
      within(sent)
        .getByRole('button', { name: t('wf.inv.cancel') })
        .getAttribute('data-why'),
    ).toContain('APPS');
    const adjustment = screen.getByText('PLI-2025-0002').closest('tr') as HTMLElement;
    expect(within(adjustment).getByText(t('c02.pli.otp'))).toBeTruthy();
    expect(within(adjustment).getByRole('button', { name: t('wf.inv.cancel') })).toHaveAttribute(
      'data-why',
      t('c02.pli.cancelLater'),
    );
    const cancelled = screen.getByText('PLI-2025-0003').closest('tr') as HTMLElement;
    expect(within(cancelled).getByText(t('wf.inv.cancelledVersion'))).toBeTruthy();
  });

  it('BR-AUDIT-009: for any other role Cancel is the Maker’s', async () => {
    routes();
    wrap(<PayrollCycle />, me(CHECKER));
    const sent = (await screen.findByText('PLI-2025-0001')).closest('tr') as HTMLElement;
    expect(within(sent).getByRole('button', { name: t('wf.inv.cancel') })).toHaveAttribute(
      'data-why',
      t('wf.inv.why.maker'),
    );
  });

  it('OD-121: the cycle actions stay, closed; the address keeps them open across a reload', async () => {
    routes();
    const first = wrap(<PayrollCycle />, me(MAKER));
    await listTable();
    const section = document.getElementById('c02-cycle') as HTMLDetailsElement;
    expect(section.open).toBe(false);
    section.open = true;
    fireEvent(section, new Event('toggle'));
    await waitFor(() => expect(window.location.hash).toBe('#c02-cycle'));
    first.unmount();
    wrap(<PayrollCycle />, me(MAKER));
    const again = await waitFor(() => {
      const open = document.getElementById('c02-cycle') as HTMLDetailsElement;
      expect(open.open).toBe(true);
      return open;
    });
    again.open = false;
    fireEvent(again, new Event('toggle'));
    await waitFor(() => expect(window.location.hash).toBe(''));
  });

  it('OD-121: D-01 opens the section by its address', async () => {
    routes();
    window.location.hash = '#c02-cycle';
    wrap(<PayrollCycle />, me(MAKER));
    await waitFor(() => expect((document.getElementById('c02-cycle') as HTMLDetailsElement).open).toBe(true));
  });

  it('SRS-15-28: a held lease opens the section', async () => {
    routes({
      'GET /v1/cycles/2026-10': () =>
        cycle({
          id: 9,
          status: 'FROZEN',
          lease: {
            cycleId: 9,
            action: 'COMPUTE',
            heldBy: 'batch',
            acquiredAt: '2026-10-20T10:00:00Z',
            heartbeatAt: '2026-10-20T10:04:00Z',
            runId: 31,
            expired: false,
          },
        }),
    });
    wrap(<PayrollCycle />, me(MAKER));
    await waitFor(() => expect((document.getElementById('c02-cycle') as HTMLDetailsElement).open).toBe(true));
  });
});
