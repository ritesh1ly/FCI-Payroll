import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, renderHook, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AttendanceCapture, WorkSlips } from '@/modules/labour';
import { GangStructure } from '@/modules/labour/components/GangStructure';
import { IncentiveNorms } from '@/modules/labour/components/IncentiveNorms';
import { LabourMaster } from '@/modules/labour/components/LabourMaster';
import { LabourRates } from '@/modules/labour/components/LabourRates';
import { emptyEntry } from '@/modules/labour/business/labourRules';
import { useMoveMember, useSubmitMember } from '@/modules/labour/orchestration/labourQueries';
import { businessToday } from '@/shared/time';
import { date } from '@/shared/ui';
import { backend, me, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

/**
 * Labour module branch gaps (M-02 Labour Master, M-05 rate tables, M-06 Incentive Norms, M-09 Gang /
 * Shed Structure, I-01 Attendance, I-02 Work-Slips; SRS-2-1, SRS-2-2, SRS-2-4, SRS-2-6, SRS-2-7,
 * SRS-2-30, SRS-2-47, SRS-2-48, SRS-2-59, SRS-3-6, SRS-3-13, SRS-4-11): the sides of each condition
 * the screen suites leave untested - a gang without a payroll type, a list that failed to load, a
 * cleared field, a value the server leaves out.
 */

afterEach(() => vi.unstubAllGlobals());

const label = (key: string, args: unknown[] = []) =>
  new RegExp(`^${t(key, args as string[]).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

const TODAY = businessToday();
const MONTH = TODAY.slice(0, 7);

/** An ISO day moved by a number of days (UTC arithmetic: no zone drift). */
function addDays(day: string, days: number): string {
  const d = new Date(`${day}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** The month before the business month, YYYY-MM, and its last day. */
function lastMonth(): { month: string; end: string } {
  const [y, m] = MONTH.split('-').map(Number) as [number, number];
  const year = m === 1 ? y - 1 : y;
  const month = m === 1 ? 12 : m - 1;
  const end = new Date(Date.UTC(year, month, 0)).getUTCDate();
  const mm = String(month).padStart(2, '0');
  return { month: `${year}-${mm}`, end: `${year}-${mm}-${String(end).padStart(2, '0')}` };
}

const OPTIONS = {
  depots: [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla', family: null }],
  designations: [{ id: 3, code: 'HL', name: 'Handling Labour', family: 'DEPT_LABOUR' }],
};

const GANG = {
  gangId: 5,
  gangCode: 'DP-DL01-G002',
  gangName: 'Okhla Gang 1',
  depot: 'DP-DL01 - Depot Okhla',
  payrollType: 'DEPT_LABOUR',
  gangType: 'HANDLING',
  status: 'ACTIVE',
  sanctionedStrength: 6,
  members: 2,
  effectiveFrom: '2025-07-01',
};

const OTHER_GANG = { ...GANG, gangId: 6, gangCode: 'DP-DL01-G003', gangName: 'Okhla Gang 2' };

const GANG_MEMBERS = [
  { employeeId: 11, labourCode: 'DPT-000001', name: 'Harpal Sandhu', role: 'SARDAR' },
  { employeeId: 13, labourCode: 'DPT-000003', name: 'Bhola Prasad', role: 'HANDLING' },
];

const MEMBER_ROW = {
  employeeId: 11,
  labourCode: 'DPT-000001',
  name: 'Ram Kumar',
  payrollType: 'DEPT_LABOUR',
  designation: 'HL - Handling Labour',
  depot: 'DP-DL01 - Depot Okhla',
  status: 'ACTIVE',
  joiningDate: '2010-07-01',
  retirementDate: null,
  panMasked: null,
  bankAccountMasked: null,
};

function hookWrapper() {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
}

describe('labour use cases', () => {
  it('SRS-2-30: an amendment sent without the record as it opened compares the entry with itself', async () => {
    const calls = backend({
      'POST /v1/labour/members/11/amendments': () => ({ status: 202, body: { id: 1, status: 'PENDING' } }),
    });
    const { result } = renderHook(() => useSubmitMember(), { wrapper: hookWrapper() });
    await act(async () => {
      await result.current.mutateAsync({ id: 11, entry: { ...emptyEntry(), firstName: 'Ram' } });
    });
    // Nothing differs from itself: the body carries no field and no reason.
    expect(sent(calls, 'POST', '/v1/labour/members/11/amendments')).toEqual([{}]);
  });

  it('SRS-2-6: a transfer sent without its order sends the order fields empty', async () => {
    const calls = backend({
      'POST /v1/labour/members/13/transfers': () => ({ status: 202, body: { id: 2, status: 'PENDING' } }),
    });
    const { result } = renderHook(() => useMoveMember(), { wrapper: hookWrapper() });
    await act(async () => {
      await result.current.mutateAsync({
        kind: 'TRANSFER',
        employeeId: 13,
        toGangId: 6,
        from: TODAY,
        to: null,
        reason: ' Shed closed ',
      });
    });
    expect(sent(calls, 'POST', '/transfers').at(-1)).toEqual({
      toGangId: 6,
      transferDate: TODAY,
      reason: 'Shed closed',
      orderReference: '',
      orderDate: '',
      issuingAuthority: '',
    });
  });
});

describe('M-09 gang without a payroll type', () => {
  function routes(extra: Record<string, (url: string) => unknown> = {}) {
    return backend({
      'GET /v1/labour/options': () => OPTIONS,
      'GET /v1/labour/gangs?': (url) =>
        paged([{ ...GANG, payrollType: null, effectiveTo: '2030-12-31' }, OTHER_GANG], 2, url),
      'GET /v1/labour/gangs/5/members': () => GANG_MEMBERS,
      'GET /v1/labour/gangs/5/strength-report': () => problem(404, 'COM-404-NOT-FOUND'),
      'GET /v1/labour/members?': (url) => paged([], 0, url),
      'GET /v1/labour/transfer-targets': (url) => paged([], 0, url),
      ...extra,
    });
  }

  async function openFirstGang() {
    const table = await screen.findByRole('table', { name: t('screen.M-09') });
    await userEvent.click(within(table).getAllByRole('button', { name: t('m09.open') })[0] as HTMLElement);
    return screen.findByRole('table', { name: t('m09.membersOf', ['Okhla Gang 1']) });
  }

  it('SRS-2-48: the members as of a chosen date, a cleared date reading today; an end needs its date', async () => {
    const calls = routes();
    wrap(<GangStructure />, me(['M-09:VIEW', 'M-09:CREATE', 'M-09:EDIT', 'M-02:EDIT']));
    const table = await screen.findByRole('table', { name: t('screen.M-09') });
    // SRS-2-7: an end-dated gang shows its end date.
    expect(within(table).getByText(date('2030-12-31'))).toBeTruthy();
    await openFirstGang();
    const asOf = screen.getByLabelText(label('m09.asOf')) as HTMLInputElement;
    fireEvent.change(asOf, { target: { value: '2026-01-15' } });
    await vi.waitFor(() =>
      expect(calls.some((c) => c.url.includes('/gangs/5/members') && c.url.includes('2026-01-15'))).toBe(
        true,
      ),
    );
    fireEvent.change(asOf, { target: { value: '' } });
    expect(asOf.value).toBe(TODAY);
    // SRS-2-7 (UI #30): a cleared end date names what is missing.
    fireEvent.change(screen.getByLabelText(label('m09.endDateLabel')), { target: { value: '' } });
    expect(screen.getByRole('button', { name: t('action.m09.endDate') }).getAttribute('title')).toBe(
      t('m09.why.endDate'),
    );
  });

  it('SRS-2-47: a join looks the code up as Departmental labour; a transfer and an attachment open for any type', async () => {
    const calls = routes({
      'POST /v1/labour/members/13/attachments': () => ({ status: 202, body: { id: 70, status: 'PENDING' } }),
    });
    wrap(<GangStructure />, me(['M-09:VIEW', 'M-09:CREATE', 'M-09:EDIT', 'M-02:EDIT']));
    await openFirstGang();
    // A gang without a payroll type: the joining member is looked up as Departmental labour.
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.join') }));
    const joinCode = screen.getByLabelText(label('m09.joinLabour'));
    await userEvent.type(joinCode, 'dpt-000009');
    const joinForm = joinCode.closest('.form-grid') as HTMLElement;
    await userEvent.type(within(joinForm).getByLabelText(label('m09.changeReason')), 'Joins the shed');
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.sendMove') }));
    expect(await screen.findByText(t('m09.notFound', ['DPT-000009']))).toBeTruthy();
    expect(
      calls.some((c) => c.url.includes('/v1/labour/members?') && c.url.includes('payrollType=DEPT_LABOUR')),
    ).toBe(true);
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    // SRS-2-6: the transfer form opens for the member.
    await userEvent.click(screen.getByRole('button', { name: t('action.m02.transfer') }));
    expect(screen.getByRole('heading', { name: t('m09.move.TRANSFER', ['DPT-000003']) })).toBeTruthy();
    // SRS-2-47: an attachment without an end date sends none; a cleared target gang is empty again.
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.attach') }));
    const target = screen.getByLabelText(label('m09.toGang'), { selector: 'select' }) as HTMLSelectElement;
    await vi.waitFor(() => expect(within(target).queryByText(/DP-DL01-G003/)).toBeTruthy());
    await userEvent.selectOptions(target, '6');
    await userEvent.selectOptions(target, '');
    expect(target.value).toBe('');
    const send = screen.getByRole('button', { name: t('action.m09.sendMove') });
    await userEvent.selectOptions(target, '6');
    await userEvent.type(screen.getByLabelText(label('m09.moveReason')), 'Rake surge');
    expect(send).toBeEnabled();
    await userEvent.click(send);
    expect(await screen.findByText(t('m09.moveSent'))).toBeTruthy();
    expect(sent(calls, 'POST', '/attachments').at(-1)).toEqual({
      toGangId: 6,
      from: TODAY,
      to: null,
      reason: 'Rake surge',
    });
  });

  it('SRS-2-47: an attachment sent with its own from and to dates carries both; the refusal of the server is shown', async () => {
    let answer: unknown = problem(409, 'LAB-409-OVERLAP', ['DPT-000003']);
    const calls = routes({ 'POST /v1/labour/members/13/attachments': () => answer });
    wrap(<GangStructure />, me(['M-09:VIEW', 'M-09:CREATE', 'M-09:EDIT', 'M-02:EDIT']));
    await openFirstGang();
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.attach') }));
    const target = screen.getByLabelText(label('m09.toGang'), { selector: 'select' }) as HTMLSelectElement;
    await vi.waitFor(() => expect(within(target).queryByText(/DP-DL01-G003/)).toBeTruthy());
    await userEvent.selectOptions(target, '6');
    const from = screen.getByLabelText(label('m09.attachFrom')) as HTMLInputElement;
    const to = screen.getByLabelText(label('m09.attachTo')) as HTMLInputElement;
    expect(from.value).toBe(TODAY);
    expect(to.value).toBe('');
    const later = addDays(TODAY, 10);
    const until = addDays(TODAY, 40);
    fireEvent.change(from, { target: { value: later } });
    fireEvent.change(to, { target: { value: until } });
    expect(from.value).toBe(later);
    expect(to.value).toBe(until);
    await userEvent.type(screen.getByLabelText(label('m09.moveReason')), 'Rake surge');
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.sendMove') }));
    expect(await screen.findByText(t('error.LAB-409-OVERLAP', ['DPT-000003']))).toBeTruthy();
    expect(sent(calls, 'POST', '/attachments').at(-1)).toEqual({
      toGangId: 6,
      from: later,
      to: until,
      reason: 'Rake surge',
    });
    // The refused form stays open for the Maker to correct; cancelling closes it without a second call.
    answer = { status: 202, body: { id: 71, status: 'PENDING' } };
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByLabelText(label('m09.attachFrom'))).toBeNull();
    expect(sent(calls, 'POST', '/attachments')).toHaveLength(1);
  });

  it('SRS-2-1: the lists that failed to load leave the depot and the leader choices empty', async () => {
    routes({
      'GET /v1/labour/options': () => problem(503, 'SYS-503'),
      'GET /v1/labour/gangs/5/members': () => problem(503, 'SYS-503'),
    });
    wrap(<GangStructure />, me(['M-09:VIEW', 'M-09:CREATE', 'M-09:EDIT']));
    await screen.findByRole('table', { name: t('screen.M-09') });
    await userEvent.click(screen.getByRole('button', { name: t('action.m09.create') }));
    const scope = screen.getByRole('button', { name: t('action.m09.send') }).closest('.card') as HTMLElement;
    expect(within(within(scope).getByLabelText(label('m02.depot'))).queryAllByRole('option')).toHaveLength(0);
    await userEvent.click(within(scope).getByRole('button', { name: t('common.cancel') }));
    const table = await screen.findByRole('table', { name: t('screen.M-09') });
    await userEvent.click(within(table).getAllByRole('button', { name: t('m09.open') })[0] as HTMLElement);
    await userEvent.click(await screen.findByRole('button', { name: t('action.m09.leader') }));
    const leader = screen.getByLabelText(label('m09.leaderMember'));
    expect(
      within(leader)
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual(['—']);
  });
});

describe('M-02 edges', () => {
  const DETAIL = {
    employeeId: 11,
    labourCode: 'DPT-000001',
    payrollType: 'DEPT_LABOUR',
    firstName: 'Ram',
    lastName: 'Kumar',
    fatherName: null,
    gender: null,
    birthDate: '1980-02-01',
    joiningDate: '2010-07-01',
    designationId: 3,
    depotLocationId: 7,
    panMasked: null,
    ifsc: 'SBIN0DEMO01',
    bankAccountMasked: null,
    mobileMasked: null,
    pfApplicable: false,
    cpfCode: null,
    uanNumber: null,
    casualLabour: false,
  };

  it('SRS-2-1: with the options not loaded the designation and depot choices are empty', async () => {
    backend({
      'GET /v1/labour/options': () => problem(503, 'SYS-503'),
      'GET /v1/labour/members?': (url) => paged([MEMBER_ROW], 1, url),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:CREATE', 'M-02:EDIT']));
    const table = await screen.findByRole('table', { name: t('screen.M-02') });
    await userEvent.click(within(table).getByRole('button', { name: t('action.m02.lightDuty') }));
    const designation = screen.getByLabelText(label('m02.toDesignation'));
    expect(
      within(designation)
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual(['—']);
    await userEvent.click(screen.getByRole('button', { name: t('action.m02.create') }));
    const depot = screen.getAllByLabelText(label('m02.depot')).at(-1) as HTMLElement;
    expect(
      within(depot)
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual(['—']);
  });

  it('SRS-2-30: a record without masked values opens with no kept hint; the depot filter clears', async () => {
    const calls = backend({
      'GET /v1/labour/options': () => OPTIONS,
      'GET /v1/labour/members?': (url) => paged([MEMBER_ROW], 1, url),
      'GET /v1/labour/members/11': () => DETAIL,
    });
    wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:EDIT']));
    const table = await screen.findByRole('table', { name: t('screen.M-02') });
    const filter = screen.getByLabelText(label('m02.searchDepot'));
    await vi.waitFor(() => expect(within(filter).queryByText('DP-DL01 - Depot Okhla')).toBeTruthy());
    await userEvent.selectOptions(filter, '7');
    await vi.waitFor(() => expect(calls.some((c) => c.url.includes('depot=7'))).toBe(true));
    await userEvent.selectOptions(filter, '');
    await vi.waitFor(() => expect(calls.at(-1)?.url).not.toContain('depot='));
    await userEvent.click(within(table).getByRole('button', { name: t('action.m02.amend') }));
    expect(await screen.findByText(t('m02.amendOf', ['DPT-000001']))).toBeTruthy();
    // R-PII: nothing masked, so no "kept" hint for PAN, bank account or mobile.
    const hint = t('m02.keepMasked', ['@@']).split('@@')[0] as string;
    expect(screen.queryByText((text) => text.startsWith(hint))).toBeNull();
    expect((screen.getByLabelText(label('m02.pan')) as HTMLInputElement).value).toBe('');
  });

  it('SRS-2-59: a correction document chosen and cleared again is missing', async () => {
    backend({
      'GET /v1/labour/options': () => OPTIONS,
      'GET /v1/labour/members?': (url) => paged([MEMBER_ROW], 1, url),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:EDIT']));
    const table = await screen.findByRole('table', { name: t('screen.M-02') });
    await userEvent.click(within(table).getByRole('button', { name: t('action.m02.correct') }));
    await userEvent.type(screen.getByLabelText(label('m02.documentAuthority')), 'Area Manager');
    const send = screen.getAllByRole('button', { name: t('action.m02.send') }).at(-1) as HTMLElement;
    expect(send.getAttribute('title')).toBe(t('m02.why.file'));
    const file = screen.getByLabelText(label('m02.documentFile'));
    await userEvent.upload(file, new File(['%PDF'], 'order.pdf', { type: 'application/pdf' }));
    expect(send).toBeEnabled();
    fireEvent.change(file, { target: { files: [] } });
    expect(send.getAttribute('title')).toBe(t('m02.why.file'));
  });
});

describe('M-02 reinstatement and nominee edges', () => {
  const SEPARATED = { ...MEMBER_ROW, employeeId: 33, labourCode: 'L30703', status: 'SEPARATED' };

  function routes() {
    return backend({
      'GET /v1/labour/options': () => ({ depots: [], designations: [] }),
      'GET /v1/labour/members?': (url) => paged([SEPARATED], 1, url),
      'GET /v1/reference/lists': () => referenceLists({ NOMINEE_RELATIONSHIP: [['SON', 'Son']] }),
      'GET /v1/labour/members/33/nominations': () => ({
        nominees: [],
        dependents: [
          { name: 'Kiran Mahto', relationship: 'SON', birthDate: null, share: null, nominee: true },
        ],
        pending: false,
      }),
    });
  }

  async function row() {
    const table = await screen.findByRole('table', { name: t('screen.M-02') });
    return within(table).getByText('L30703').closest('tr') as HTMLElement;
  }

  it('reinstateDetails: an order file chosen and cleared again is missing', async () => {
    routes();
    wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:EDIT']));
    await userEvent.click(within(await row()).getByRole('button', { name: t('action.m02.reinstate') }));
    fireEvent.change(screen.getByLabelText(label('m02.orderDate')), { target: { value: TODAY } });
    await userEvent.type(screen.getByLabelText(label('m02.issuingAuthority')), 'Area Manager');
    await userEvent.type(screen.getByLabelText(label('m09.changeReason')), 'Appellate order');
    const file = screen.getByLabelText(label('m02.reinstateOrder'));
    await userEvent.upload(file, new File(['%PDF'], 'order.pdf', { type: 'application/pdf' }));
    const send = screen.getByRole('button', { name: t('action.m02.send') });
    expect(send).toBeEnabled();
    fireEvent.change(file, { target: { files: [] } });
    expect(send.getAttribute('title')).toBe(t('m02.why.file'));
  });

  it('nomineeDetails: a dependent who is also a nominee reads so; editing one dependent keeps the other', async () => {
    routes();
    wrap(<LabourMaster />, me(['M-02:VIEW', 'M-02:EDIT']));
    await userEvent.click(within(await row()).getByRole('button', { name: t('m02.nominees') }));
    const table = await screen.findByRole('table', { name: t('m02.nominationsOf', ['L30703']) });
    expect(within(table).getByText(t('m02.dependentNominee'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('action.m02.nominees') }));
    await userEvent.click(screen.getByRole('button', { name: t('action.m02.addDependent') }));
    await userEvent.click(screen.getByRole('button', { name: t('action.m02.addDependent') }));
    await userEvent.type(screen.getByLabelText(label('m02.dependentName', [2])), 'Asha');
    await userEvent.type(screen.getByLabelText(label('m02.dependentName', [1])), 'Kiran');
    expect((screen.getByLabelText(label('m02.dependentName', [1])) as HTMLInputElement).value).toBe('Kiran');
    expect((screen.getByLabelText(label('m02.dependentName', [2])) as HTMLInputElement).value).toBe('Asha');
  });
});

describe('M-05 depot-scoped rate sets', () => {
  const DPS_SET = {
    id: 5,
    payrollType: 'DPS_LABOUR',
    depotId: 7,
    depot: 'DP-DL01 - Depot Okhla',
    versionNo: 1,
    effectiveFrom: '2023-10-01',
    effectiveTo: null,
    interimRelief: null,
    goiRate: null,
    goiRateSource: null,
    comparisonGrain: null,
    approvalState: 'APPROVED',
    rates: [{ designationId: 31, designation: 'DSDR - Sardar (DPS)', gangRole: 'SARDAR', mgw: 799 }],
  };

  function routes() {
    return backend({
      'GET /v1/labour/rate-sets/designations': () => [],
      'GET /v1/labour/rate-sets?': (url) =>
        url.includes('DEPT_LABOUR')
          ? paged(
              [
                {
                  ...DPS_SET,
                  id: 12,
                  payrollType: 'DEPT_LABOUR',
                  effectiveTo: '2026-03-31',
                  rates: [
                    {
                      designationId: 41,
                      designation: 'SDR - Sardar',
                      gangRole: 'SARDAR',
                      basicWage: 520,
                      da: 110,
                    },
                  ],
                },
              ],
              1,
              url,
            )
          : paged([DPS_SET], 1, url),
      'GET /v1/audit/history': () => ({ items: [], page: 0, size: 20, total: 0 }),
    });
  }

  const historyKeys = (calls: { url: string }[]) =>
    calls.filter((c) => c.url.includes('/v1/audit/history')).map((c) => decodeURIComponent(c.url));

  it('SRS-4-11: a DPS set of one depot opens the history of that depot', async () => {
    const calls = routes();
    wrap(<LabourRates />, me(['M-05:VIEW']));
    const table = await screen.findByRole('table', { name: t('screen.M-05') });
    await userEvent.click(
      within(table).getByRole('button', { name: t('history.for', ['DP-DL01 - Depot Okhla']) }),
    );
    await vi.waitFor(() => expect(historyKeys(calls).some((u) => u.includes('DPS_LABOUR@7'))).toBe(true));
  });

  it('SRS-3-13: a closed Departmental set of one depot shows its end and opens that depot history', async () => {
    const calls = routes();
    wrap(<LabourRates />, me(['M-05:VIEW']));
    await userEvent.selectOptions(await screen.findByLabelText(label('m02.payrollType')), 'DEPT_LABOUR');
    const table = await screen.findByRole('table', { name: t('screen.M-05') });
    await vi.waitFor(() => expect(within(table).getByText(new RegExp(date('2026-03-31')))).toBeTruthy());
    await userEvent.click(
      within(table).getByRole('button', { name: t('history.for', ['DP-DL01 - Depot Okhla']) }),
    );
    await vi.waitFor(() => expect(historyKeys(calls).some((u) => u.includes('DEPT_LABOUR@7'))).toBe(true));
  });
});

describe('M-06 circular choice', () => {
  it('SRS-3-6: a circular without a subject reads by its number; a cleared choice is none', async () => {
    const calls = backend({
      'GET /v1/labour/norm-sets/depots': () => [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla' }],
      'GET /v1/labour/norm-sets?': (url) => paged([], 0, url),
      'GET /v1/masters/circulars/current': () => [{ id: 21, number: 'HQ/MOU/2026/9', subject: null }],
    });
    wrap(<IncentiveNorms />, me(['M-06:VIEW', 'M-06:CREATE']));
    expect(await screen.findByText(t('m06.none'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('action.m06.propose') }));
    const circular = screen.getByLabelText(label('m06.mouCircular')) as HTMLSelectElement;
    await vi.waitFor(() =>
      expect(within(circular).getByRole('option', { name: 'HQ/MOU/2026/9' })).toBeTruthy(),
    );
    await userEvent.selectOptions(circular, '21');
    expect(circular.value).toBe('21');
    await userEvent.selectOptions(circular, '');
    expect(circular.value).toBe('');
    expect(sent(calls, 'POST', '/v1/labour/norm-sets')).toHaveLength(0);
  });
});

describe('I-01 attendance edges', () => {
  it('SRS-2-2: a submitted past month blocks the submit; days before joining are blank; cleared choices', async () => {
    const { month, end } = lastMonth();
    const calls = backend({
      'GET /v1/labour/options': () => OPTIONS,
      'GET /v1/labour/gangs?': (url) => paged([GANG], 1, url),
      'GET /v1/labour/gangs/5/attendance?': () => ({
        gangId: 5,
        gangName: 'Okhla Gang 1',
        depot: 'DP-DL01 - Depot Okhla',
        payrollType: 'DEPT_LABOUR',
        yymm: month.replace('-', ''),
        lastMarkable: end,
        batchState: 'SUBMITTED',
        approvalId: 4,
        sanctionedStrength: 6,
        codes: [{ code: 'W', meaning: 'On work', weight: 1, leave: false, confirmed: true }],
        members: [
          {
            employeeId: 11,
            labourCode: 'L10701',
            name: 'Harpal Sandhu',
            role: 'SARDAR',
            from: `${month}-15`,
            to: null,
            retirementDate: null,
            marks: [],
            marked: 0,
            presentDays: 0,
            leaveDays: 0,
            absentDays: 0,
          },
        ],
      }),
    });
    wrap(<AttendanceCapture />, me(['I-01:VIEW', 'I-01:CREATE', 'I-01:SUBMIT']));
    const depot = screen.getByLabelText(label('m02.depot'));
    await vi.waitFor(() => expect(within(depot).queryByText('DP-DL01 - Depot Okhla')).toBeTruthy());
    await userEvent.selectOptions(depot, '7');
    await vi.waitFor(() =>
      expect(calls.some((c) => c.url.includes('/gangs?') && c.url.includes('depot=7'))).toBe(true),
    );
    await userEvent.selectOptions(depot, '');
    expect((depot as HTMLSelectElement).value).toBe('');
    const gang = screen.getByLabelText(label('m09.gang'), { selector: 'select' }) as HTMLSelectElement;
    await vi.waitFor(() => expect(within(gang).queryByText('DP-DL01-G002 - Okhla Gang 1')).toBeTruthy());
    await userEvent.selectOptions(gang, '5');
    await userEvent.selectOptions(gang, '');
    expect(screen.getByText(t('i01.chooseGang'))).toBeTruthy();
    await userEvent.selectOptions(gang, '5');
    await userEvent.click(screen.getByRole('button', { name: t('i01.lastMonth') }));
    const grid = await screen.findByRole('table', { name: t('i01.grid') });
    const cells = within(grid)
      .getByText('L10701')
      .closest('tr')
      ?.querySelectorAll('td') as NodeListOf<HTMLElement>;
    // Day 1 is before the member joined (blank); day 15 is markable.
    expect(cells[2]?.textContent).toBe('');
    expect(cells[16]?.textContent).toBe('·');
    expect(screen.getByRole('button', { name: t('action.i01.submit') }).getAttribute('title')).toBe(
      t('i01.why.batch'),
    );
  });
});

describe('I-02 work-slip edges', () => {
  it('SRS-2-4: a slip of the current month reads bags without a bag type; the date is capped at today; a cleared PBNW reason', async () => {
    backend({
      'GET /v1/labour/gangs?': (url) => paged([GANG], 1, url),
      'GET /v1/labour/gangs/5/members': () => GANG_MEMBERS,
      'GET /v1/labour/gangs/5/work-slips?': () => ({
        gangId: 5,
        gangName: 'Okhla Gang 1',
        depot: 'DP-DL01 - Depot Okhla',
        payrollType: 'DEPT_LABOUR',
        yymm: MONTH.replace('-', ''),
        batchState: 'OPEN',
        approvalId: null,
        operations: [
          { id: 4, code: 'PBNW', name: 'Present But No Work', bagBased: false, normType: 'NONE', pbnw: true },
        ],
        bagTypes: [],
        slips: [
          {
            id: 71,
            number: `WS-5-${MONTH.replace('-', '')}01-1`,
            date: `${MONTH}-01`,
            status: 'DRAFT',
            continueWork: false,
            holiday: false,
            labourStrength: 2,
            amendmentOf: null,
            rows: [
              {
                order: 1,
                operation: 'W2P - Wagon-to-Platform',
                bagType: null,
                bags: 100,
                stackHeight: null,
                heightBand: null,
                start: '09:00',
                end: '11:00',
                pbnwReason: null,
              },
            ],
            participants: [],
          },
        ],
      }),
    });
    wrap(<WorkSlips />, me(['I-02:VIEW', 'I-02:CREATE', 'I-02:EDIT', 'I-02:SUBMIT']));
    const gang = screen.getByLabelText(label('m09.gang'), { selector: 'select' });
    await vi.waitFor(() => expect(within(gang).queryByText('DP-DL01-G002 - Okhla Gang 1')).toBeTruthy());
    await userEvent.selectOptions(gang, '5');
    const table = await screen.findByRole('table', { name: t('i02.slips') });
    expect(within(table).getByText(new RegExp(t('i02.bagsOf', [100, '']).trim()))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('action.i02.new') }));
    const day = screen.getByLabelText(label('i02.date'), { selector: 'input' });
    expect(day.getAttribute('max')).toBe(TODAY);
    await userEvent.selectOptions(screen.getByLabelText(label('i02.operation')), '4');
    const reason = screen.getByLabelText(label('i02.pbnwReason')) as HTMLSelectElement;
    await userEvent.selectOptions(reason, 'RAKE_DELAYED');
    expect(reason.value).toBe('RAKE_DELAYED');
    await userEvent.selectOptions(reason, '');
    expect(reason.value).toBe('');
  });
});
