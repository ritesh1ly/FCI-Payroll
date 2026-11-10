import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { IncentiveNorms } from '@/modules/labour/components/IncentiveNorms';
import type { NormProposal } from '@/modules/labour/api/labourApi';
import { normBlocked, srsSlabs } from '@/modules/labour/business/labourRules';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/** M-06 Incentive Norms (plan 20 phase 20d; SRS-3-6, SRS-3-12). */

afterEach(() => vi.unstubAllGlobals());

const label = (key: string) => new RegExp(`^${t(key).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

const SET = {
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
  mouCircular: 'HQ/MOU/2026/1',
};

const CIRCULARS = [{ id: 12, number: 'HQ/MOU/2026/2', subject: 'Okhla union MoU' }];

function proposal(over: Partial<NormProposal> = {}): NormProposal {
  return {
    depotLocationId: 7,
    effectiveFrom: '2026-10-29',
    handling: 250,
    height: 20,
    lead: 0,
    minLabourNumber: 10,
    minSlabWeight: 8.5,
    mouReference: '',
    slabs: srsSlabs(),
    reason: 'Union MoU',
    mouCircularId: 12,
    ...over,
  };
}

describe('M-06 rules', () => {
  it('SRS-3-12 / SRS-3-6: the norms are bags and the bands are contiguous', () => {
    const today = '2026-10-28';
    expect(normBlocked(proposal(), today)).toBeNull();
    expect(normBlocked(proposal({ depotLocationId: null }), today)).toBe('m06.why.depot');
    expect(normBlocked(proposal({ effectiveFrom: '2026-10-27' }), today)).toBe('m06.why.effective');
    expect(normBlocked(proposal({ handling: null }), today)).toBe('m06.why.norm');
    expect(normBlocked(proposal({ lead: 1.5 }), today)).toBe('m06.why.norm');
    expect(normBlocked(proposal({ minLabourNumber: 0 }), today)).toBe('m06.why.mln');
    expect(normBlocked(proposal({ minSlabWeight: 0 }), today)).toBe('m06.why.mlsw');
    const gap = srsSlabs().map((s, i) => (i === 1 ? { ...s, from: 32 } : s));
    expect(normBlocked(proposal({ slabs: gap }), today)).toBe('m06.why.slabs');
    const open = srsSlabs().map((s, i) => (i === 0 ? { ...s, to: null } : s));
    expect(normBlocked(proposal({ slabs: open }), today)).toBe('m06.why.slabs');
    const inverted = srsSlabs().map((s, i) => (i === 0 ? { ...s, to: 0 } : s));
    expect(normBlocked(proposal({ slabs: inverted }), today)).toBe('m06.why.slabs');
    const rate = srsSlabs().map((s, i) => (i === 0 ? { ...s, ratePercent: 101 } : s));
    expect(normBlocked(proposal({ slabs: rate }), today)).toBe('m06.why.rate');
    expect(normBlocked(proposal({ slabs: [] }), today)).toBe('m06.why.slabs');
    expect(normBlocked(proposal({ reason: '' }), today)).toBe('m06.why.reason');
    // SRS M-06 use case: the MoU is linked from M-25.
    expect(normBlocked(proposal({ mouCircularId: null }), today)).toBe('m06.why.mou');
  });
});

describe('M-06 Incentive Norms', () => {
  it('SRS-3-12: the Rule Administrator proposes a depot set from the SRS bag steps', async () => {
    const calls = backend({
      'GET /v1/labour/norm-sets/depots': () => [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla' }],
      'GET /v1/labour/norm-sets?': (url) => paged([SET], 1, url),
      'GET /v1/masters/circulars/current': () => CIRCULARS,
      'POST /v1/labour/norm-sets': () => ({ status: 202, body: { id: 60, status: 'PENDING_APPROVAL' } }),
    });
    wrap(<IncentiveNorms />, me(['M-06:VIEW', 'M-06:CREATE']));
    const table = await screen.findByRole('table', { name: t('screen.M-06') });
    expect(within(table).getByText(t('m06.normValues', [250, 20, 0]))).toBeTruthy();
    expect(within(table).getByText(t('m06.mouOf', ['HQ/MOU/2026/1']))).toBeTruthy();
    // SRS-20-29 (US-03-07 AC1): each set opens the change history of its depot's norms.
    expect(
      within(table).getByRole('button', { name: t('history.for', ['DP-DL01 - Depot Okhla']) }),
    ).toBeTruthy();
    await userEvent.click(within(table).getByRole('button', { name: t('m06.slabs') }));
    const slabs = await screen.findByRole('table', { name: t('m06.slabsOf', ['DP-DL01 - Depot Okhla']) });
    expect(within(slabs).getAllByText('8%')).toHaveLength(2);
    await userEvent.click(screen.getByRole('button', { name: t('action.m06.propose') }));
    const send = screen.getByRole('button', { name: t('action.m06.send') });
    expect(send.getAttribute('title')).toBe(t('m06.why.depot'));
    await userEvent.selectOptions(screen.getAllByLabelText(label('m02.depot'))[1] as HTMLElement, '7');
    await userEvent.type(screen.getByLabelText(label('m06.handling')), '250');
    await userEvent.type(screen.getByLabelText(label('m06.height')), '20');
    await userEvent.type(screen.getByLabelText(label('m06.lead')), '0');
    await userEvent.type(screen.getByLabelText(label('m06.mln')), '10');
    await userEvent.type(screen.getByLabelText(label('m06.mlsw')), '8.5');
    await userEvent.type(screen.getByLabelText(label('m06.mou')), ' MoU 2026 ');
    await userEvent.type(screen.getByLabelText(label('m02.reason')), 'Union MoU');
    await userEvent.selectOptions(
      screen.getByLabelText(label('m06.mouCircular')),
      await screen.findByRole('option', { name: 'HQ/MOU/2026/2 - Okhla union MoU' }),
    );
    const rate = screen.getByLabelText(t('m06.rateOf', [t('m06.band.BAND_1'), 1]));
    fireEvent.change(rate, { target: { value: '9' } });
    const to = screen.getByLabelText(t('m06.toOf', [t('m06.band.BAND_1'), 4]));
    fireEvent.change(to, { target: { value: '' } });
    const from = screen.getByLabelText(t('m06.fromOf', [t('m06.band.BAND_2'), 1]));
    fireEvent.change(from, { target: { value: '1' } });
    expect(send).toBeEnabled();
    await userEvent.click(send);
    expect(await screen.findByText(t('m06.proposed'))).toBeTruthy();
    const body = sent(calls, 'POST', '/v1/labour/norm-sets').at(-1) as Record<string, unknown>;
    expect(body).toMatchObject({
      depotLocationId: 7,
      handling: 250,
      minSlabWeight: 8.5,
      mouReference: 'MoU 2026',
      mouCircularId: 12,
    });
    expect((body.slabs as { ratePercent: number }[])[0]?.ratePercent).toBe(9);
  });

  it('SRS-3-12: while the depots cannot be read the set has no depot to choose', async () => {
    backend({
      'GET /v1/labour/norm-sets/depots': () => problem(503, 'COM-503-UNAVAILABLE'),
      'GET /v1/masters/circulars/current': () => CIRCULARS,
      'GET /v1/labour/norm-sets?': (url) => paged([SET], 1, url),
    });
    wrap(<IncentiveNorms />, me(['M-06:VIEW', 'M-06:CREATE']));
    await screen.findByRole('table', { name: t('screen.M-06') });
    await userEvent.click(screen.getByRole('button', { name: t('action.m06.propose') }));
    const depot = screen.getAllByRole('combobox')[0] as HTMLSelectElement;
    expect(within(depot).getAllByRole('option')).toHaveLength(1);
    expect(screen.getByRole('button', { name: t('action.m06.send') })).toBeDisabled();
  });

  it('SRS-3-12: depots view only; a refusal is shown', async () => {
    backend({
      'GET /v1/labour/norm-sets/depots': () => [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla' }],
      'GET /v1/masters/circulars/current': () => CIRCULARS,
      'GET /v1/labour/norm-sets?': (url) => paged([], 0, url),
    });
    wrap(<IncentiveNorms />, me(['M-06:VIEW']));
    expect(await screen.findByText(t('m06.none'))).toBeTruthy();
    const propose = screen.getByRole('button', { name: t('action.m06.propose') });
    expect(propose).toBeDisabled();
    expect(propose.getAttribute('title')).toBe(t('reason.ruleAdminOnly'));
  });

  it('SRS-3-12: the server refusal is shown', async () => {
    backend({
      'GET /v1/labour/norm-sets/depots': () => [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla' }],
      'GET /v1/labour/norm-sets?': (url) => paged([], 0, url),
      'POST /v1/labour/norm-sets': () => problem(422, 'LBR-422-NORM', ['EFFECTIVE_BEFORE_LATEST']),
      'GET /v1/masters/circulars/current': () => CIRCULARS,
    });
    wrap(<IncentiveNorms />, me(['M-06:VIEW', 'M-06:CREATE']));
    await screen.findByText(t('m06.none'));
    await userEvent.selectOptions(screen.getByLabelText(label('m02.depot')), '7');
    await userEvent.click(screen.getByRole('button', { name: t('action.m06.propose') }));
    for (const [key, value] of [
      ['m06.handling', '250'],
      ['m06.height', '20'],
      ['m06.lead', '0'],
      ['m06.mln', '10'],
      ['m06.mlsw', '8'],
      ['m02.reason', 'MoU'],
    ] as const) {
      await userEvent.type(
        screen.getByLabelText(label(key), { selector: key === 'm02.reason' ? 'textarea' : 'input' }),
        value,
      );
    }
    const send = screen.getByRole('button', { name: t('action.m06.send') });
    expect(send.getAttribute('title')).toBe(t('m06.why.mou'));
    await userEvent.selectOptions(
      screen.getByLabelText(label('m06.mouCircular')),
      await screen.findByRole('option', { name: 'HQ/MOU/2026/2 - Okhla union MoU' }),
    );
    await userEvent.click(send);
    expect(await screen.findByRole('alert')).toBeTruthy();
  });
});

describe('M-06 edges', () => {
  it('SRS-3-6: a closed set shows its end; a cleared or bad slab value is empty, zero or refused; cancel sends nothing', async () => {
    const calls = backend({
      'GET /v1/labour/norm-sets/depots': () => [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla' }],
      'GET /v1/masters/circulars/current': () => CIRCULARS,
      'GET /v1/labour/norm-sets?': (url) => paged([{ ...SET, effectiveTo: '2026-12-31' }], 1, url),
    });
    wrap(<IncentiveNorms />, me(['M-06:VIEW', 'M-06:CREATE']));
    const table = await screen.findByRole('table', { name: t('screen.M-06') });
    expect(within(table).queryByText(new RegExp(t('m12.open')))).toBeNull();
    await userEvent.selectOptions(screen.getByLabelText(label('m02.depot')), '7');
    await vi.waitFor(() => expect(calls.some((c) => c.url.includes('depot=7'))).toBe(true));
    await userEvent.selectOptions(screen.getByLabelText(label('m02.depot')), '');
    await userEvent.click(screen.getByRole('button', { name: t('action.m06.propose') }));
    const depot = screen.getAllByLabelText(label('m02.depot'))[1] as HTMLSelectElement;
    await userEvent.selectOptions(depot, '7');
    await userEvent.selectOptions(depot, '');
    expect(depot.value).toBe('');
    const effective = screen.getByLabelText(label('m12.effective')) as HTMLInputElement;
    fireEvent.change(effective, { target: { value: '2026-12-01' } });
    expect(effective.value).toBe('2026-12-01');
    const from = screen.getByLabelText(t('m06.fromOf', [t('m06.band.BAND_2'), 1])) as HTMLInputElement;
    fireEvent.change(from, { target: { value: 'x' } });
    expect(from.value).toBe('0');
    const to = screen.getByLabelText(t('m06.toOf', [t('m06.band.BAND_1'), 1])) as HTMLInputElement;
    fireEvent.change(to, { target: { value: '12' } });
    expect(to.value).toBe('12');
    const rate = screen.getByLabelText(t('m06.rateOf', [t('m06.band.BAND_1'), 1])) as HTMLInputElement;
    fireEvent.change(rate, { target: { value: 'abc' } });
    expect(rate.value).toBe('abc');
    expect(screen.getByRole('button', { name: t('action.m06.send') })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('button', { name: t('action.m06.send') })).toBeNull();
    expect(sent(calls, 'POST', '/v1/labour/norm-sets')).toHaveLength(0);
  });
});
