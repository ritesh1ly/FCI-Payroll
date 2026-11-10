import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LabourRates } from '@/modules/labour/components/LabourRates';
import type { NwnpRateOptions } from '@/modules/labour/api/labourApi';
import { type NwnpDraft, nwnpBlocked, toNwnpRequest } from '@/modules/labour/business/labourRules';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * M-05 Labour Rate Tables ▸ NWNP (plan 22 phase 22b; SRS-5-2, SRS-5-3; D22-5, D22-6): a depot's
 * ASOR / SOR set, every operation's Piece of Rate with its Unit of Measure and every designation's
 * MGW mandatory; the Rule Administrator proposes, the Rule Approver approves.
 */

afterEach(() => vi.unstubAllGlobals());

const label = (key: string, args: unknown[] = []) =>
  new RegExp(`^${t(key, args as string[]).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

const OPTIONS: NwnpRateOptions = {
  operations: [
    { code: 'W2P', name: 'Wagon-to-Platform', m27: 'W2P' },
    { code: 'PBNW', name: 'Present But No Work', m27: 'PBNW' },
  ],
  designations: [
    { id: 41, code: 'NSDR', name: 'Sardar (NWNP)', gangRole: 'SARDAR' },
    { id: 42, code: 'NHL', name: 'Handling Labour (NWNP)', gangRole: 'HANDLING' },
  ],
  unitsOfMeasure: [
    { code: 'PER_100_BAGS', label: 'Per 100 bags' },
    { code: 'PER_HOUR', label: 'Per hour' },
  ],
  depots: [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla', gangRole: null }],
};

const SET = {
  id: 3,
  code: 'NWNP-DP-DL01-ASOR',
  depot: 'DP-DL01 - Depot Okhla',
  version: 'ASOR',
  versionNo: 1,
  effectiveFrom: '2026-05-01',
  effectiveTo: null,
  approvalState: 'APPROVED',
  lines: [
    {
      operationCode: 'W2P',
      operation: 'Wagon-to-Platform',
      pieceOfRate: 12.5,
      unitOfMeasure: 'PER_100_BAGS',
      unitLabel: 'Per 100 bags',
    },
    {
      operationCode: 'PBNW',
      operation: 'Present But No Work',
      pieceOfRate: 40,
      unitOfMeasure: 'PER_HOUR',
      unitLabel: null,
    },
  ],
  mgw: [{ designationId: 41, designation: 'NSDR - Sardar (NWNP)', gangRole: 'SARDAR', mgw: 700 }],
};

function draft(over: Partial<NwnpDraft> = {}): NwnpDraft {
  return {
    depotLocationId: 7,
    version: 'ASOR',
    effectiveFrom: '2026-11-01',
    rates: { W2P: '12.50', PBNW: '40' },
    units: { W2P: 'PER_100_BAGS', PBNW: 'PER_HOUR' },
    mgw: { 41: '700', 42: '690' },
    reason: 'Union settlement 2026',
    ...over,
  };
}

describe('M-05 NWNP rules', () => {
  // SC-22-0003, SC-22-0018, SC-22-0019, SC-22-0020
  it('SRS-5-3: every operation needs its Piece of Rate and Unit of Measure; every designation its MGW', () => {
    expect(nwnpBlocked(draft(), OPTIONS)).toBeNull();
    expect(nwnpBlocked(draft({ depotLocationId: null }), OPTIONS)).toBe('m05.why.depot');
    expect(nwnpBlocked(draft({ effectiveFrom: '' }), OPTIONS)).toBe('m05.why.effective');
    expect(nwnpBlocked(draft(), { ...OPTIONS, operations: [] })).toBe('m05.why.designations');
    expect(nwnpBlocked(draft(), { ...OPTIONS, designations: [] })).toBe('m05.why.designations');
    expect(nwnpBlocked(draft({ rates: { W2P: '12.50' } }), OPTIONS)).toBe('m05.why.pieceOfRate');
    expect(nwnpBlocked(draft({ rates: { W2P: '0', PBNW: '1' } }), OPTIONS)).toBe('m05.why.pieceOfRate');
    expect(nwnpBlocked(draft({ units: { W2P: 'PER_100_BAGS' } }), OPTIONS)).toBe('m05.why.unit');
    expect(nwnpBlocked(draft({ units: { W2P: 'PER_TONNE', PBNW: 'PER_HOUR' } }), OPTIONS)).toBe(
      'm05.why.unit',
    );
    expect(nwnpBlocked(draft({ mgw: { 41: '700' } }), OPTIONS)).toBe('m05.why.mgw');
    expect(nwnpBlocked(draft({ mgw: { 41: '700', 42: '0' } }), OPTIONS)).toBe('m05.why.mgw');
    expect(nwnpBlocked(draft({ reason: '' }), OPTIONS)).toBe('m05.why.reason');
    expect(toNwnpRequest(draft({ version: 'SOR' }), OPTIONS)).toEqual({
      depotLocationId: 7,
      version: 'SOR',
      effectiveFrom: '2026-11-01',
      lines: [
        { operationCode: 'W2P', pieceOfRate: 12.5, unitOfMeasure: 'PER_100_BAGS' },
        { operationCode: 'PBNW', pieceOfRate: 40, unitOfMeasure: 'PER_HOUR' },
      ],
      mgw: [
        { designationId: 41, mgw: 700 },
        { designationId: 42, mgw: 690 },
      ],
      reason: 'Union settlement 2026',
    });
    expect(
      toNwnpRequest(draft({ depotLocationId: null, rates: {}, units: {}, mgw: {} }), OPTIONS),
    ).toMatchObject({
      depotLocationId: 0,
      lines: [{ operationCode: 'W2P', unitOfMeasure: '' }, { operationCode: 'PBNW' }],
    });
  });
});

function routes(extra: Record<string, (url: string) => unknown> = {}) {
  return backend({
    'GET /v1/labour/rate-sets/designations': () => [],
    'GET /v1/labour/rate-sets?': (url) => paged([], 0, url),
    'GET /v1/labour/nwnp-rate-sets/options': () => OPTIONS,
    'GET /v1/labour/nwnp-rate-sets?': (url) =>
      paged(
        // The set in force and the earlier one it ended.
        [
          SET,
          {
            ...SET,
            id: 2,
            versionNo: 0,
            effectiveFrom: '2025-05-01',
            effectiveTo: '2026-04-30',
            lines: [],
            mgw: [],
          },
        ],
        2,
        url,
      ),
    ...extra,
  });
}

async function openNwnp() {
  await userEvent.selectOptions(await screen.findByLabelText(label('m02.payrollType')), 'NWNP_LABOUR');
  return screen.findByRole('table', { name: t('screen.M-05') });
}

describe('M-05 Labour Rate Tables ▸ NWNP', () => {
  // SC-22-0014, SC-22-0016
  it('SRS-5-2: the depot sets read with each rate and unit; the Rule Administrator proposes a set', async () => {
    const calls = routes({
      'POST /v1/labour/nwnp-rate-sets': () => ({ status: 202, body: { id: 80, status: 'PENDING_APPROVAL' } }),
    });
    wrap(<LabourRates />, me(['M-05:VIEW', 'M-05:CREATE']));
    const table = await openNwnp();
    expect(
      within(table).getByText(/W2P - Wagon-to-Platform: ₹ 12.5 \(PER_100_BAGS - Per 100 bags\)/),
    ).toBeTruthy();
    expect(within(table).getByText(/PBNW - Present But No Work: ₹ 40 \(PER_HOUR\)/)).toBeTruthy();
    expect(within(table).getByText('NSDR - Sardar (NWNP): ₹ 700')).toBeTruthy();
    expect(within(table).getAllByText(new RegExp(t('m05.version.ASOR')))).toHaveLength(2);
    expect(within(table).getAllByText(new RegExp(t('m12.open')))).toHaveLength(1);
    // SRS-20-29 (US-03-07 AC1): each set opens the change history of its set code.
    expect(
      within(table).getAllByRole('button', { name: t('history.for', ['NWNP-DP-DL01-ASOR']) }),
    ).toHaveLength(2);
    await userEvent.click(screen.getByRole('button', { name: t('action.m05.propose') }));
    const send = screen.getByRole('button', { name: t('action.m05.send') });
    expect(send.getAttribute('title')).toBe(t('m05.why.depot'));
    await userEvent.selectOptions(screen.getAllByLabelText(label('m02.depot')).at(-1) as HTMLElement, '7');
    await userEvent.selectOptions(screen.getByLabelText(label('m05.rateVersion')), 'SOR');
    fireEvent.change(screen.getByLabelText(label('m12.effective')), { target: { value: '2026-12-01' } });
    await userEvent.type(
      screen.getByLabelText(label('m05.pieceOfRateOf', ['W2P - Wagon-to-Platform'])),
      '13',
    );
    await userEvent.selectOptions(screen.getByLabelText(label('m05.unitOf', ['W2P'])), 'PER_100_BAGS');
    await userEvent.type(
      screen.getByLabelText(label('m05.pieceOfRateOf', ['PBNW - Present But No Work'])),
      '41',
    );
    expect(send.getAttribute('title')).toBe(t('m05.why.unit'));
    await userEvent.selectOptions(screen.getByLabelText(label('m05.unitOf', ['PBNW'])), 'PER_HOUR');
    await userEvent.type(screen.getByLabelText(label('m05.mgwOf', ['NSDR - Sardar (NWNP)'])), '710');
    await userEvent.type(screen.getByLabelText(label('m05.mgwOf', ['NHL - Handling Labour (NWNP)'])), '700');
    await userEvent.type(screen.getByLabelText(label('m02.reason')), 'Revision 2026');
    expect(send).toBeEnabled();
    await userEvent.click(send);
    expect(await screen.findByText(t('m05.proposed'))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/labour/nwnp-rate-sets').at(-1)).toMatchObject({
      depotLocationId: 7,
      version: 'SOR',
      effectiveFrom: '2026-12-01',
      lines: [
        { operationCode: 'W2P', pieceOfRate: 13, unitOfMeasure: 'PER_100_BAGS' },
        { operationCode: 'PBNW', pieceOfRate: 41, unitOfMeasure: 'PER_HOUR' },
      ],
      mgw: [
        { designationId: 41, mgw: 710 },
        { designationId: 42, mgw: 700 },
      ],
    });
    expect(calls.some((c) => c.url.includes('/rate-sets/designations?payrollType=NWNP'))).toBe(false);
  });

  // SC-22-0015
  it('SRS-5-2: a refused set is shown and kept; the depot can be cleared; cancel closes it; a viewer cannot propose', async () => {
    routes({
      'POST /v1/labour/nwnp-rate-sets': () => problem(422, 'LBR-422-RATE', ['EFFECTIVE_BEFORE_LATEST']),
    });
    const view = wrap(<LabourRates />, me(['M-05:VIEW', 'M-05:CREATE']));
    await openNwnp();
    await userEvent.click(screen.getByRole('button', { name: t('action.m05.propose') }));
    const depot = screen.getAllByLabelText(label('m02.depot')).at(-1) as HTMLSelectElement;
    await userEvent.selectOptions(depot, '7');
    await userEvent.selectOptions(depot, '');
    expect(depot.value).toBe('');
    await userEvent.selectOptions(depot, '7');
    for (const op of ['W2P - Wagon-to-Platform', 'PBNW - Present But No Work'])
      await userEvent.type(screen.getByLabelText(label('m05.pieceOfRateOf', [op])), '10');
    await userEvent.selectOptions(screen.getByLabelText(label('m05.unitOf', ['W2P'])), 'PER_100_BAGS');
    await userEvent.selectOptions(screen.getByLabelText(label('m05.unitOf', ['PBNW'])), 'PER_HOUR');
    await userEvent.type(screen.getByLabelText(label('m05.mgwOf', ['NSDR - Sardar (NWNP)'])), '700');
    await userEvent.type(screen.getByLabelText(label('m05.mgwOf', ['NHL - Handling Labour (NWNP)'])), '690');
    await userEvent.type(screen.getByLabelText(label('m02.reason')), 'Revision');
    await userEvent.click(screen.getByRole('button', { name: t('action.m05.send') }));
    expect(await screen.findByText(t('error.LBR-422-RATE', ['EFFECTIVE_BEFORE_LATEST']))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('button', { name: t('action.m05.send') })).toBeNull();
    view.unmount();
    routes();
    wrap(<LabourRates />, me(['M-05:VIEW']));
    await openNwnp();
    const propose = screen.getByRole('button', { name: t('action.m05.propose') });
    expect(propose).toBeDisabled();
    expect(propose.getAttribute('title')).toBe(t('reason.ruleAdminOnly'));
  });
});
