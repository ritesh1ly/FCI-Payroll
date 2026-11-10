import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { IncentiveNorms, WorkSlips } from '@/modules/labour';
import type { RegisterRow } from '@/modules/labour/api/captureApi';
import { previousMonth } from '@/modules/labour/business/labourRules';
import { businessToday } from '@/shared/time';
import { backend, me, paged, referenceLists, sent, t, wrap, type Route } from './support/harness';

/**
 * Labour loading races (owner target 100 %): what the drawn screens do while one of their reads is still in flight.
 * I-02 Work-Slip Capture (SRS-2-4, SRS-2-15, BR-LABR-002) acting before the month overview or the gang's month has
 * loaded, and M-06 Incentive Norms (SRS-3-12) carrying the drawn form to the register before the depot list has loaded.
 * Each read is held by a deferred fetch and released inside the test.
 */

const search = vi.hoisted(() => ({ current: '' }));
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search.current),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));

afterEach(() => {
  search.current = '';
  vi.unstubAllGlobals();
});

/** Holds every GET whose URL contains {@code fragment} until the returned release() is called. */
function hold(fragment: string) {
  let release: () => void = () => undefined;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  const inner = globalThis.fetch;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      if ((init?.method ?? 'GET') === 'GET' && url.includes(fragment)) await gate;
      return inner(url, init);
    }),
  );
  return () => release();
}

const MONTH = previousMonth(businessToday());
const GANGS = [{ id: 8, code: 'GNG-01', name: 'Okhla Shed A Gang 1', type: 'HANDLING' }];

const ROW: RegisterRow = {
  slipId: 7,
  rowOrder: 1,
  number: 'WS-88402',
  gangId: 8,
  gangCode: 'GNG-01',
  date: `${MONTH}-02`,
  bagType: null,
  operationCode: 'W2P',
  operation: null,
  bags: null,
  start: null,
  end: null,
  normType: null,
  continueWork: false,
  labours: 4,
  status: 'APPROVED',
  source: 'ENT',
  duplicateOf: null,
  reason: null,
  enteredBy: null,
  decidedBy: null,
  decidedAt: null,
};

function slipRoutes(over: Record<string, Route> = {}) {
  return backend({
    'GET /v1/labour/work-slips/overview': () => ({
      month: MONTH,
      saved: 0,
      rejected: 0,
      pendingBooking: 0,
      anna: { received: 0, confirmed: 0, returned: 0 },
      gangs: GANGS,
      dates: [],
    }),
    'GET /v1/labour/work-slips': () => paged([ROW]),
    'GET /v1/labour/overtime': () => paged([]),
    'GET /v1/labour/gangs?': () => paged([]),
    'GET /v1/labour/gangs/8/members': () => [],
    'GET /v1/labour/gangs/8/work-slips': () => ({
      gangId: 8,
      gangName: 'Okhla Shed A Gang 1',
      depot: 'DP-DL01 - Depot Okhla',
      payrollType: 'DEPT_LABOUR',
      yymm: MONTH.replace('-', ''),
      batchState: 'OPEN',
      approvalId: null,
      operations: [],
      bagTypes: [],
      slips: [],
    }),
    'POST /v1/labour/gangs/8/work-slips': () => ({ status: 201, body: { id: 71 } }),
    ...over,
  });
}

const LDH = ['I-02:VIEW', 'I-02:CREATE', 'I-02:EDIT'];
const drawn = (key: string) =>
  screen.getAllByRole('button', { name: t(key) }).find((b) => !b.hasAttribute('data-guard')) as HTMLElement;

describe('I-02 before its reads have loaded', () => {
  it('SRS-2-15: a register row Edit before the month overview has loaded opens the capture asking for the gang', async () => {
    const calls = slipRoutes();
    const release = hold('/v1/labour/work-slips/overview');
    wrap(<WorkSlips />, me(LDH));
    fireEvent.click(
      await screen.findByRole('button', { name: `${t('common.edit')} WS-88402` }, { timeout: 5000 }),
    );
    const capture = screen.getByTestId('i02-capture');
    expect(capture.hasAttribute('open')).toBe(true);
    // The gang list of the month is not there yet: no gang is taken for the row.
    expect(within(capture).getByText(t('i01.chooseGang'))).toBeInTheDocument();
    expect(calls.some((c) => c.url.includes('/v1/labour/gangs/8/work-slips'))).toBe(false);
    release();
    await waitFor(() => expect(calls.some((c) => c.url.includes('/work-slips/overview'))).toBe(true));
  });

  it('BR-LABR-002: Save and Next while the gang month is loading sends nothing and says it is loading', async () => {
    const calls = slipRoutes();
    wrap(<WorkSlips />, me(LDH));
    const gang = screen.getByRole('combobox', { name: t('i01w.allGangs') });
    await within(gang).findByRole('option', { name: 'GNG-01' });
    fireEvent.change(gang, { target: { value: '8' } });
    const release = hold('/v1/labour/gangs/8/work-slips');
    fireEvent.click(screen.getByRole('button', { name: t('i02w.new') }));
    // Without the month the screen cannot check the slip (slipBlocked needs the operations): Save waits.
    fireEvent.click(drawn('i02w.saveNext'));
    fireEvent.click(drawn('i02w.save'));
    expect((await screen.findAllByText(t('common.loading'))).length).toBeGreaterThan(0);
    expect(sent(calls, 'POST', '/v1/labour/gangs/8/work-slips')).toHaveLength(0);
    release();
    // Once the month is there, the capture's own Save is drawn and no longer held for loading.
    const own = await screen.findByRole('button', { name: t('action.i02.save') });
    expect(own).not.toHaveAttribute('title', t('common.loading'));
    expect(sent(calls, 'POST', '/v1/labour/gangs/8/work-slips')).toHaveLength(0);
  });
});

describe('M-06 before the depot list has loaded', () => {
  it('SRS-3-12: the drawn form carried to the register before the depots load leaves the depot to choose', async () => {
    const calls = backend({
      'GET /v1/reference/lists': () => referenceLists({ LABOUR_NORM_TYPE: [['HANDLING', 'Handling Norm']] }),
      'GET /v1/labour/norm-sets/depots': () => [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla' }],
      'GET /v1/labour/norm-sets': () => paged([]),
      'GET /v1/labour/registers/norms/summary': () => ({ depotsWithSet: 0, slabTables: 0 }),
      'GET /v1/labour/registers/norms': () => paged([]),
      'GET /v1/masters/circulars/current': () => [],
    });
    const release = hold('/v1/labour/norm-sets/depots');
    wrap(<IncentiveNorms />, ['M-06:VIEW', 'M-06:CREATE']);
    const save = await screen.findByRole('button', { name: t('m05w.save') });
    const fields = document.querySelectorAll<HTMLInputElement>('.rf-grid input');
    ['Okhla', 'MoU/OKH/2026-01', '3200', '66', '12', '30', '2026-12-01'].forEach((value, i) =>
      fireEvent.change(fields[i] as HTMLInputElement, { target: { value } }),
    );
    fireEvent.click(save);
    expect(await screen.findByText(t('m06w.completeInRegister'))).toBeInTheDocument();
    const register = screen.getByTestId('m06-register');
    expect((await within(register).findAllByDisplayValue('3200')).length).toBeGreaterThan(0);
    release();
    await waitFor(() => expect(calls.some((c) => c.url.includes('/norm-sets/depots'))).toBe(true));
    // The typed depot was not matched (no list yet); the register keeps it unchosen rather than guess.
    const options = await within(register).findAllByRole('option', { name: 'DP-DL01 - Depot Okhla' });
    // The proposal's own depot select (the register's filter carries an accessible name).
    const depot = options
      .map((o) => o.closest('select') as HTMLSelectElement)
      .find((sel) => sel.closest('label')?.textContent?.startsWith(t('m02.depot'))) as HTMLSelectElement;
    expect(depot).toBeDefined();
    expect(depot.value).toBe('');
  });
});
