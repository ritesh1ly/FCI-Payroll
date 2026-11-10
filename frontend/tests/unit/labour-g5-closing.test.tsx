import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { IntegrationMonitor } from '@/modules/integration';
import type { Window } from '@/modules/integration/api/integrationApi';
import { windowReprocessBlocked } from '@/modules/integration/business/integrationRules';
import { AttendanceCapture, WorkSlips } from '@/modules/labour';
import { IncentiveNorms } from '@/modules/labour/components/IncentiveNorms';
import { LabourMaster } from '@/modules/labour/components/LabourMaster';
import { LabourRates } from '@/modules/labour/components/LabourRates';
import type { AttendanceGrid, Slab } from '@/modules/labour/api/labourApi';
import { addSlab, removeSlab, srsSlabs } from '@/modules/labour/business/labourRules';
import { date } from '@/shared/ui';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * Group g5 closing pass (PENDING 2i): M-05 GoI rate effective date (SRS-4-11, G5-65), I-01 Attendance
 * Source (SRS-2-36, G5-11), M-06 add / remove band and the band boundary (M-06 l.4276, SRS-3-6; G5-43,
 * G5-44), parked I-02 / M-05 sections disabled with the reason (DoD 4, G5-53), M-02 gang search as of
 * a date (SRS-2-40, G5-55) and X-03 Reprocess Failed per window (X-03 l.5172, SRS-INT-33; G5-96).
 */

afterEach(() => vi.unstubAllGlobals());

const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&');
const label = (key: string, args: unknown[] = []) => new RegExp(`^${esc(t(key, args as string[]))}`);

// ------------------------------------------------------------------ G5-65

const RATE_SET = {
  id: 5,
  payrollType: 'DPS_LABOUR',
  depotId: null,
  depot: null,
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

describe('M-05 GoI rate effective date (SRS-4-11, G5-65)', () => {
  it("SRS-4-11: the GoI rate shows the set's effective date; a set without a GoI rate shows none", async () => {
    backend({
      'GET /v1/labour/rate-sets/designations': () => [],
      'GET /v1/labour/rate-sets?': (url) =>
        paged(
          [
            {
              ...RATE_SET,
              id: 9,
              versionNo: 2,
              effectiveFrom: '2026-11-01',
              goiRate: 900,
              goiRateSource: 'GoI CLC',
            },
            RATE_SET,
          ],
          2,
          url,
        ),
    });
    wrap(<LabourRates />, me(['M-05:VIEW']));
    const table = await screen.findByRole('table', { name: t('screen.M-05') });
    const shown = within(table).getAllByTestId('goi-effective');
    expect(shown).toHaveLength(1);
    expect(shown[0]?.textContent).toBe(t('m05.goiEffective', [date('2026-11-01')]));
    expect(within(table).getByText('GoI CLC')).toBeTruthy();
  });
});

// ------------------------------------------------------------------ G5-11

const GANG = {
  gangId: 5,
  gangCode: 'DP-DL01-G002',
  gangName: 'Okhla Gang 1',
  depot: 'DP-DL01 - Depot Okhla',
  payrollType: 'DEPT_LABOUR',
  gangType: 'HANDLING',
  status: 'ACTIVE',
  sanctionedStrength: 6,
  members: 0,
  effectiveFrom: '2025-07-01',
};

function grid(over: Partial<AttendanceGrid> = {}): AttendanceGrid {
  return {
    gangId: 5,
    gangName: 'Okhla Gang 1',
    depot: 'DP-DL01 - Depot Okhla',
    payrollType: 'DEPT_LABOUR',
    yymm: '202609',
    lastMarkable: '2026-09-30',
    batchState: 'OPEN',
    approvalId: null,
    sanctionedStrength: null,
    codes: [{ code: 'W', meaning: 'On work', weight: 1, leave: false, confirmed: true }],
    members: [],
    ...over,
  };
}

async function openGrid(answer: AttendanceGrid) {
  backend({
    'GET /v1/labour/gangs?': (url) => paged([GANG], 1, url),
    'GET /v1/labour/gangs/5/attendance?': () => answer,
  });
  wrap(<AttendanceCapture />, me(['I-01:VIEW']));
  const gang = screen.getByLabelText(new RegExp(`^${t('m09.gang')}`), { selector: 'select' });
  await vi.waitFor(() => expect(within(gang).queryByText('DP-DL01-G002 - Okhla Gang 1')).toBeTruthy());
  await userEvent.selectOptions(gang, '5');
  fireEvent.change(screen.getByLabelText(new RegExp(`^${t('i01.month')}`), { selector: 'input' }), {
    target: { value: '2026-09' },
  });
  return screen.findByLabelText(t('i01.source'));
}

describe('I-01 Attendance Source (SRS-2-36, G5-11)', () => {
  it("SRS-2-36: the depot's Attendance Source is shown read-only with every flag", async () => {
    const source = await openGrid(
      grid({
        attendanceSource: {
          mode: 'BOTH',
          annaDarpan: true,
          status: 'PILOT',
          fallbackToManual: true,
          effectiveFrom: '2026-04-01',
        },
      }),
    );
    expect(source.textContent).toContain(t('i01.mode.BOTH'));
    expect(source.textContent).toContain(t('i01.sourceStatus.PILOT'));
    expect(source.textContent).toContain(t('i01.sourceFrom', [date('2026-04-01')]));
    expect(source.textContent).toContain(t('i01.annaDarpan'));
    expect(source.textContent).toContain(t('i01.fallbackManual'));
    expect(source.querySelector('.s-SYS')).not.toBeNull();
    expect(within(source).queryByRole('textbox')).toBeNull();
  });

  it('SRS-2-36: a manual live source shows no Anna Darpan or fallback', async () => {
    const source = await openGrid(
      grid({
        attendanceSource: {
          mode: 'MANUAL',
          annaDarpan: false,
          status: 'LIVE',
          fallbackToManual: false,
          effectiveFrom: '2026-04-01',
        },
      }),
    );
    expect(source.textContent).toContain(t('i01.mode.MANUAL'));
    expect(source.textContent).not.toContain(t('i01.annaDarpan'));
    expect(source.textContent).not.toContain(t('i01.fallbackManual'));
  });

  it('SRS-2-36: no source row (or an older server without the field) reads not configured', async () => {
    const source = await openGrid(grid());
    expect(source.textContent).toContain(t('i01.sourceNotConfigured'));
  });
});

// ------------------------------------------------------------------ G5-43 / G5-44

const band = (slabs: Slab[], table: Slab['table']) => slabs.filter((s) => s.table === table);

describe('M-06 slab bands as rows (l.4276, G5-43) and the band boundary (SRS-3-6, G5-44)', () => {
  it('M-06 l.4276: a band added at a table end starts the bag after the last band and has no rate yet', () => {
    const added = addSlab(srsSlabs(), 'BAND_1');
    const b1 = band(added, 'BAND_1');
    expect(b1).toHaveLength(5);
    // the open last band (91 and above) is closed at its own start: 91 - 91, then 92 and above
    expect(b1[3]).toMatchObject({ order: 4, from: 91, to: 91, ratePercent: 50 });
    expect(b1[4]).toEqual({ table: 'BAND_1', order: 5, from: 92, to: null, ratePercent: -1 });
    expect(band(added, 'BAND_2')).toEqual(band(srsSlabs(), 'BAND_2'));
    // a closed last band keeps its end
    const closed = srsSlabs().map((s) => (s.table === 'BAND_2' && s.order === 4 ? { ...s, to: 80 } : s));
    expect(band(addSlab(closed, 'BAND_2'), 'BAND_2')[4]).toMatchObject({ order: 5, from: 81, to: null });
    // a table with no band starts at bag 1
    expect(addSlab(band(srsSlabs(), 'BAND_1'), 'BAND_2')).toContainEqual({
      table: 'BAND_2',
      order: 1,
      from: 1,
      to: null,
      ratePercent: -1,
    });
  });

  it('M-06 l.4276: the last band is removed; a table keeps at least one band', () => {
    const fewer = removeSlab(srsSlabs(), 'BAND_2');
    expect(fewer).not.toBeNull();
    expect(band(fewer ?? [], 'BAND_2').map((s) => s.order)).toEqual([1, 2, 3]);
    expect(band(fewer ?? [], 'BAND_1')).toHaveLength(4);
    const one = srsSlabs().filter((s) => s.table === 'BAND_1' || s.order === 1);
    expect(removeSlab(one, 'BAND_2')).toBeNull();
    expect(removeSlab([], 'BAND_1')).toBeNull();
  });

  it('M-06: the form adds and removes a band, a new band needs its rate, and the boundary is read-only', async () => {
    backend({
      'GET /v1/labour/norm-sets/depots': () => [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla' }],
      'GET /v1/labour/norm-sets?': (url) => paged([], 0, url),
      'GET /v1/masters/circulars/current': () => [],
    });
    wrap(<IncentiveNorms />, me(['M-06:VIEW', 'M-06:CREATE']));
    await userEvent.click(await screen.findByRole('button', { name: t('action.m06.propose') }));
    const boundary = screen.getByLabelText(t('m06.boundary'));
    expect(boundary.textContent).toContain(t('m06.boundaryText'));
    expect(boundary.querySelector('.s-RULE')).not.toBeNull();
    const add1 = screen.getByRole('button', { name: t('action.m06.addBand', [t('m06.band.BAND_1')]) });
    const remove1 = screen.getByRole('button', {
      name: t('action.m06.removeBand', [t('m06.band.BAND_1')]),
    });
    await userEvent.click(add1);
    const rate5 = screen.getByLabelText(t('m06.rateOf', [t('m06.band.BAND_1'), 5])) as HTMLInputElement;
    expect(rate5.value).toBe('');
    expect(
      (screen.getByLabelText(t('m06.fromOf', [t('m06.band.BAND_1'), 5])) as HTMLInputElement).value,
    ).toBe('92');
    expect((screen.getByLabelText(t('m06.toOf', [t('m06.band.BAND_1'), 4])) as HTMLInputElement).value).toBe(
      '91',
    );
    const send = screen.getByRole('button', { name: t('action.m06.send') });
    expect(send).toBeDisabled();
    // the typed rate of another band survives adding and removing
    fireEvent.change(screen.getByLabelText(t('m06.rateOf', [t('m06.band.BAND_1'), 1])), {
      target: { value: '9.5' },
    });
    for (let i = 0; i < 4; i += 1) await userEvent.click(remove1);
    expect(screen.queryByLabelText(t('m06.rateOf', [t('m06.band.BAND_1'), 2]))).toBeNull();
    expect(
      (screen.getByLabelText(t('m06.rateOf', [t('m06.band.BAND_1'), 1])) as HTMLInputElement).value,
    ).toBe('9.5');
    expect(remove1).toBeDisabled();
    expect(remove1.getAttribute('title')).toBe(t('m06.why.oneBand'));
    const remove2 = screen.getByRole('button', {
      name: t('action.m06.removeBand', [t('m06.band.BAND_2')]),
    });
    expect(remove2).toBeEnabled();
    expect(remove2.getAttribute('title')).toBeNull();
  });

  it('SRS-3-6: the slab view of a set shows the boundary too', async () => {
    backend({
      'GET /v1/labour/norm-sets/depots': () => [],
      'GET /v1/labour/norm-sets?': (url) =>
        paged(
          [
            {
              id: 3,
              depotCode: 'DP-DL01',
              depot: 'DP-DL01 - Depot Okhla',
              versionNo: 1,
              effectiveFrom: '2026-10-29',
              effectiveTo: null,
              handling: 250,
              height: 20,
              lead: 0,
              minLabourNumber: 10,
              minSlabWeight: 8.5,
              mouReference: 'MoU',
              approvalState: 'APPROVED',
              slabs: srsSlabs(),
              mouCircular: null,
            },
          ],
          1,
          url,
        ),
    });
    wrap(<IncentiveNorms />, me(['M-06:VIEW']));
    const table = await screen.findByRole('table', { name: t('screen.M-06') });
    await userEvent.click(within(table).getByRole('button', { name: t('m06.slabs') }));
    expect(screen.getByLabelText(t('m06.boundary')).textContent).toContain(t('m06.boundaryText'));
  });
});

// ------------------------------------------------------------------ G5-53

describe('parked sections shown disabled with the reason (DoD 4, G5-53)', () => {
  it('M-05 NWNP: the Minimum Wage by location type is disabled with PENDING NWNP-22b-1', async () => {
    backend({
      'GET /v1/labour/rate-sets/designations': () => [],
      'GET /v1/labour/rate-sets?': (url) => paged([], 0, url),
      'GET /v1/labour/nwnp-rate-sets': (url) => paged([], 0, url),
    });
    wrap(<LabourRates />, me(['M-05:VIEW', 'M-05:CREATE']));
    await userEvent.selectOptions(
      await screen.findByLabelText(label('m02.payrollType'), { selector: 'select' }),
      'NWNP_LABOUR',
    );
    const parked = await screen.findByRole('button', { name: t('action.m05.nwnpMinWage') });
    expect(parked).toBeDisabled();
    expect(parked.getAttribute('title')).toBe(t('m05.why.nwnpMinWage'));
    expect(t('m05.why.nwnpMinWage')).toContain('NWNP-22b-1');
  });

  it('I-02: overtime per person and the Anna Darpan records are disabled with their PENDING reason', async () => {
    backend({
      'GET /v1/labour/gangs?': (url) => paged([GANG], 1, url),
      'GET /v1/labour/gangs/5/work-slips?': () => ({
        gangId: 5,
        gangName: 'Okhla Gang 1',
        depot: 'DP-DL01 - Depot Okhla',
        payrollType: 'DEPT_LABOUR',
        yymm: '202609',
        batchState: 'OPEN',
        approvalId: null,
        operations: [],
        bagTypes: [],
        slips: [],
      }),
      'GET /v1/labour/gangs/5/members': () => [],
    });
    wrap(<WorkSlips />, me(['I-02:VIEW', 'I-02:CREATE']));
    const gang = await screen.findByLabelText(new RegExp(`^${t('m09.gang')}`), { selector: 'select' });
    await vi.waitFor(() => expect(within(gang).queryByText('DP-DL01-G002 - Okhla Gang 1')).toBeTruthy());
    await userEvent.selectOptions(gang, '5');
    fireEvent.change(screen.getByLabelText(new RegExp(`^${t('i01.month')}`), { selector: 'input' }), {
      target: { value: '2026-09' },
    });
    await screen.findByRole('table', { name: t('i02.slips') }).catch(() => null);
    await userEvent.click(await screen.findByRole('button', { name: t('action.i02.new') }));
    const overtime = await screen.findByRole('button', { name: t('action.i02.overtime') });
    expect(overtime).toBeDisabled();
    expect(overtime.getAttribute('title')).toBe(t('i02.why.overtime'));
    const anna = screen.getByRole('button', { name: t('action.i02.annaDarpan') });
    expect(anna).toBeDisabled();
    expect(anna.getAttribute('title')).toBe(t('i02.why.annaDarpan'));
  });
});

// ------------------------------------------------------------------ G5-55

describe('M-02 gang search as of a date (SRS-2-40, G5-55)', () => {
  it('SRS-2-40: the date is offered with a gang and sent with it; without a gang it is disabled with the reason', async () => {
    const calls = backend({
      'GET /v1/labour/options': () => ({ depots: [], designations: [] }),
      'GET /v1/labour/members?': (url) => paged([], 0, url),
    });
    wrap(<LabourMaster />, me(['M-02:VIEW']));
    const on = (await screen.findByLabelText(label('m02.searchGangOn'))) as HTMLInputElement;
    expect(on).toBeDisabled();
    expect(on.getAttribute('title')).toBe(t('m02.why.gangOn'));
    await userEvent.type(screen.getByLabelText(label('m02.searchGang')), 'G001');
    expect(on).toBeEnabled();
    expect(on.getAttribute('title')).toBeNull();
    fireEvent.change(on, { target: { value: '2026-08-15' } });
    await vi.waitFor(() =>
      expect(calls.some((c) => c.url.includes('gang=G001') && c.url.includes('asOf=2026-08-15'))).toBe(true),
    );
    // the gang cleared: the date is not sent
    await userEvent.clear(screen.getByLabelText(label('m02.searchGang')));
    await vi.waitFor(() => expect(calls.at(-1)?.url.includes('asOf')).toBe(false));
    expect(calls.filter((c) => c.url.includes('/v1/labour/members?')).at(-1)?.url).not.toContain('asOf');
  });
});

// ------------------------------------------------------------------ G5-96

function win(over: Partial<Window> = {}): Window {
  return {
    runId: 3,
    interfaceCode: 'HRMS_EMP_OTA',
    direction: 'INBOUND',
    batchReference: 'DEMO-HRMS-EMP_OTA-1',
    windowStart: null,
    windowEnd: null,
    received: 3,
    accepted: 1,
    rejected: 1,
    status: 'FAILED',
    retryAttempt: 0,
    startedAt: '2026-10-28T01:30:00Z',
    completedAt: '2026-10-28T01:31:00Z',
    failureDetail: null,
    adapter: true,
    ...over,
  };
}

describe('X-03 Reprocess Failed per window (X-03 l.5172, SRS-INT-33, G5-96)', () => {
  it('SRS-INT-33: only the Administrator, a window with rejected or failed records, an interface with an adapter', () => {
    expect(windowReprocessBlocked(win(), true)).toBeNull();
    expect(windowReprocessBlocked(win(), false)).toBe('x03.reason.adminOnly');
    expect(windowReprocessBlocked(win({ rejected: 0, status: 'OK' }), true)).toBe('x03.why.nothingFailed');
    expect(windowReprocessBlocked(win({ rejected: 0, status: 'PARTIAL' }), true)).toBe(
      'x03.why.nothingFailed',
    );
    expect(windowReprocessBlocked(win({ rejected: 0, status: 'FAILED' }), true)).toBeNull();
    expect(windowReprocessBlocked(win({ rejected: 1, status: 'OK' }), true)).toBeNull();
    expect(windowReprocessBlocked(win({ adapter: false }), true)).toBe('x03.why.noAdapter');
    expect(windowReprocessBlocked(win({ adapter: undefined }), true)).toBe('x03.why.noAdapter');
  });

  function routes(extra: Record<string, (url: string) => unknown> = {}) {
    return backend({
      'GET /v1/integration/summary': () => ({
        interfaces: 1,
        notConfigured: 0,
        openExceptions: 2,
        failedWindows: 1,
        awaitingI21: 0,
        oldestAwaitingI21: null,
      }),
      'GET /v1/integration/interfaces?': (url) => paged([], 0, url),
      'GET /v1/integration/exceptions?': (url) => paged([], 0, url),
      'GET /v1/integration/hrms-fallback-uploads': (url) => paged([], 0, url),
      'GET /v1/integration/windows?': (url) =>
        paged([win(), win({ runId: 4, rejected: 0, status: 'OK' })], 2, url),
      ...extra,
    });
  }

  it('SRS-INT-33: a viewer sees Reprocess failed disabled with "Administrator only"', async () => {
    routes();
    wrap(<IntegrationMonitor />, me(['X-03:VIEW']));
    await userEvent.click(await screen.findByRole('button', { name: t('x03.queueAct') }));
    const table = await screen.findByRole('table', { name: t('x03.windows') });
    const button = within(table).getByRole('button', { name: t('x03.reprocessWindowOf', [3]) });
    expect(button).toBeDisabled();
    expect(button.getAttribute('title')).toBe(t('x03.reason.adminOnly'));
  });

  it('SRS-INT-33: the Administrator reprocesses a window with one reason; a cancel sends nothing; a refusal is shown', async () => {
    let refuse = false;
    const calls = routes({
      'POST /v1/integration/windows/3/reprocess': () =>
        refuse
          ? problem(409, 'INT-409-WINDOW', [3])
          : { runId: 3, interfaceCode: 'HRMS_EMP_OTA', reprocessed: [7, 8] },
    });
    wrap(<IntegrationMonitor />, me(['X-03:VIEW', 'X-03:EDIT']));
    await userEvent.click(await screen.findByRole('button', { name: t('x03.reprocessAct') }));
    const table = await screen.findByRole('table', { name: t('x03.windows') });
    const ok = within(table).getByRole('button', { name: t('x03.reprocessWindowOf', [4]) });
    expect(ok).toBeDisabled();
    expect(ok.getAttribute('title')).toBe(t('x03.why.nothingFailed'));
    const button = within(table).getByRole('button', { name: t('x03.reprocessWindowOf', [3]) });
    expect(button).toBeEnabled();
    await userEvent.click(button);
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    expect(sent(calls, 'POST', '/windows/3/reprocess')).toHaveLength(0);
    await userEvent.click(button);
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(t('x03.reprocessWindowTitle', [3, 'HRMS_EMP_OTA']))).toBeTruthy();
    await userEvent.type(within(dialog).getByRole('textbox'), 'Feed corrected');
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.submit') }));
    expect(await screen.findByText(t('x03.reprocessedWindow', [2, 3]))).toBeTruthy();
    expect(sent(calls, 'POST', '/windows/3/reprocess').at(-1)).toEqual({ reason: 'Feed corrected' });
    refuse = true;
    await userEvent.click(button);
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'Again');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submit') }),
    );
    expect(await screen.findByText(t('error.INT-409-WINDOW', [3]))).toBeTruthy();
  });
});
