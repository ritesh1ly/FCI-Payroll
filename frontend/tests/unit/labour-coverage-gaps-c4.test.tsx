import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { IncentiveNorms } from '@/modules/labour';
import { backend as serve, me, paged, referenceLists, t, wrap, type Route } from './support/harness';

/**
 * M-06 Incentive Norms as drawn (Wireframes v6.1, OD-121; SRS-3-6, SRS-3-12; FRS §8.4.2) coverage gaps: New Depot
 * Norm Set, Import MoU / Maintain and the row Edit per role, the filters, a norm with nothing recorded, the tiles
 * with no set and no depot list, the View and MoU dialogs and the ✔ of the Rule Approver.
 */

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams(''),
}));

const backend = (routes: Record<string, Route>) =>
  serve(Object.fromEntries(Object.entries(routes).map(([k, v]) => [`GET ${k}`, v])));

afterEach(() => vi.unstubAllGlobals());

const lists = referenceLists({
  LABOUR_NORM_TYPE: [
    ['HANDLING', 'Handling Norm'],
    ['HEIGHT', 'Height Norm'],
  ],
});

const NORM = {
  id: 3,
  depotCode: 'DP-DL01',
  depot: 'Depot Okhla',
  mouReference: null,
  minLabourNumber: 3600,
  minSlabWeight: 50,
  heightNorm: null,
  leadNorm: null,
  effectiveFrom: '2024-11-07',
  effectiveTo: null,
  approvalState: 'PENDING_APPROVAL',
};

const routes = (over: Record<string, Route> = {}) => ({
  '/v1/reference/lists': () => lists,
  '/v1/labour/norm-sets/depots': () => [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla' }],
  '/v1/labour/norm-sets': () => paged([]),
  '/v1/labour/options': () => ({ depots: [], designations: [] }),
  '/v1/labour/registers/norms/summary': () => ({ depotsWithSet: 0, slabTables: 0 }),
  '/v1/labour/registers/norms/3/slabs': () => [
    { table: 'BAND_1', order: 1, from: 1, to: 30, ratePercent: 8, stepSize: null },
  ],
  '/v1/labour/registers/norms': () => paged([NORM]),
  '/v1/masters/circulars/current': () => [{ id: 1, number: 'HQ/MOU/2026/3', subject: null }],
  ...over,
});

const SYS_ADMIN = (permissions: string[]) => me(permissions, { roles: ['SYSTEM_ADMINISTRATOR'] });
const register = () => screen.getByTestId('m06-register') as HTMLDetailsElement;
const normCalls = (calls: { url: string }[]) =>
  calls.filter((c) => c.url.includes('/v1/labour/registers/norms?'));

describe('M-06 the drawn entry per role', () => {
  it('SRS-3-12: New Depot Norm Set leads to the drawn form and focuses its first field', async () => {
    backend(routes());
    wrap(<IncentiveNorms />, ['M-06:VIEW', 'M-06:CREATE']);
    await screen.findByRole('button', { name: t('m05w.save') });
    fireEvent.click(screen.getByRole('button', { name: t('m06w.newSet') }));
    expect(document.activeElement).toBe(document.querySelector('.rf-grid input'));
    expect(register().open).toBe(false);
  });

  it('SRS-3-12: Import MoU, Maintain and a row Edit open the proposal in the register for the Rule Administrator', async () => {
    backend(routes());
    wrap(<IncentiveNorms />, ['M-06:VIEW', 'M-06:CREATE']);
    const table = await screen.findByRole('table', { name: t('m06w.register') });
    await within(table).findByText('Depot Okhla');
    for (const open of [
      () => screen.getByRole('button', { name: t('m06w.import') }),
      () => screen.getByRole('button', { name: t('m06w.maintain') }),
      () => within(table).getByRole('button', { name: `${t('common.edit')} Depot Okhla` }),
    ]) {
      fireEvent.click(open());
      await waitFor(() => expect(register().open).toBe(true));
      register().open = false;
      fireEvent(register(), new Event('toggle'));
      await waitFor(() => expect(register().open).toBe(false));
    }
  });

  it('WF-208: the System Administrator is drawn the entry but is told it is not the maker, and nothing opens', async () => {
    const calls = backend(routes());
    wrap(<IncentiveNorms />, SYS_ADMIN(['M-06:VIEW']));
    const table = await screen.findByRole('table', { name: t('m06w.register') });
    await within(table).findByText('Depot Okhla');
    fireEvent.click(screen.getByRole('button', { name: t('m06w.import') }));
    expect(await screen.findByText(t('mst.why.notMaker'))).toBeInTheDocument();
    expect(register().open).toBe(false);
    const fields = document.querySelectorAll<HTMLInputElement>('.rf-grid input');
    ['Okhla', 'MoU/OKH/2026-01', '3200', '66', '12', '30', '2026-12-01'].forEach((value, i) =>
      fireEvent.change(fields[i] as HTMLInputElement, { target: { value } }),
    );
    fireEvent.click(screen.getByRole('button', { name: t('m05w.save') }));
    await waitFor(() => expect(screen.getAllByText(t('mst.why.notMaker')).length).toBeGreaterThan(0));
    expect(screen.queryByText(t('m06w.completeInRegister'))).not.toBeInTheDocument();
    expect(register().open).toBe(false);
    expect(calls.some((c) => c.method !== 'GET')).toBe(false);
  });

  it('SRS-3-12: opening the closed register by hand lists the sets with no proposal started', async () => {
    const calls = backend(routes());
    wrap(<IncentiveNorms />, ['M-06:VIEW']);
    await screen.findByRole('table', { name: t('m06w.register') });
    register().open = true;
    fireEvent(register(), new Event('toggle'));
    await waitFor(() => expect(calls.some((c) => c.url.includes('/v1/labour/norm-sets?'))).toBe(true));
  });
});

describe('M-06 filters, tiles and rows', () => {
  it('SRS-3-12: the depot, norm type and search filters ask the register again', async () => {
    const calls = backend(routes());
    wrap(<IncentiveNorms />, ['M-06:VIEW']);
    const depot = screen.getByRole('combobox', { name: t('lw.depotFilter') });
    await within(depot).findByRole('option', { name: 'Depot Okhla' });
    fireEvent.change(depot, { target: { value: '7' } });
    await waitFor(() => expect(normCalls(calls).some((c) => c.url.includes('depot=7'))).toBe(true));
    fireEvent.change(depot, { target: { value: '' } });
    const type = screen.getByRole('combobox', { name: t('m06w.normType') });
    await within(type).findByRole('option', { name: 'Height Norm' });
    fireEvent.change(type, { target: { value: 'HEIGHT' } });
    await waitFor(() => expect(normCalls(calls).some((c) => c.url.includes('normType=HEIGHT'))).toBe(true));
    fireEvent.change(screen.getByPlaceholderText(t('wf.searchInList')), { target: { value: 'Okhla' } });
    await waitFor(() => expect(normCalls(calls).some((c) => c.url.includes('q=Okhla'))).toBe(true));
  });

  it('CF-49: no depot list, no set seeded and no norm: the tiles say so and History says there is none', async () => {
    backend(
      routes({
        '/v1/labour/norm-sets/depots': () => ({}),
        '/v1/labour/registers/norms/summary': () => ({}),
        '/v1/labour/registers/norms': () => paged([]),
      }),
    );
    wrap(<IncentiveNorms />, ['M-06:VIEW']);
    const table = await screen.findByRole('table', { name: t('m06w.register') });
    expect(await within(table).findByText(t('m06.none'))).toBeInTheDocument();
    const big = (key: string) => screen.getByText(t(key)).closest('.tile')?.querySelector('.big');
    expect(big('m06w.t.seeded')).toHaveTextContent('0');
    expect(big('m06w.t.seeded')).toHaveClass('fl-bad');
    expect(big('m06w.t.slabs')).toHaveTextContent('0');
    expect(big('m06w.t.depots')).toHaveTextContent('—');
    fireEvent.click(screen.getByRole('button', { name: t('lw.history') }));
    expect(await screen.findAllByText(t('m06.none'))).toHaveLength(2);
  });

  it('SRS-3-12: a norm with nothing recorded reads — in the row, the slab step and its View; Close dismisses it', async () => {
    backend(routes());
    wrap(<IncentiveNorms />, ['M-06:VIEW']);
    const table = await screen.findByRole('table', { name: t('m06w.register') });
    const tr = (await within(table).findByText('Depot Okhla')).closest('tr') as HTMLElement;
    // MoU, height norm and lead norm.
    expect(within(tr).getAllByText('—')).toHaveLength(3);
    expect(within(tr).getByText(t('lw.state.PENDING'))).toBeInTheDocument();
    const slabs = await screen.findByRole('table', { name: t('m06w.slabs') });
    const band = (await within(slabs).findByText('8%')).closest('tr') as HTMLElement;
    expect(within(band).getByText('—')).toBeInTheDocument();
    fireEvent.click(within(tr).getByRole('button', { name: `${t('common.view')} Depot Okhla` }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getAllByText('—')).toHaveLength(3);
    expect(within(dialog).getByText('3,600')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('M-06: a MoU circular without subject reads —; Close dismisses the MoU register', async () => {
    backend(routes());
    wrap(<IncentiveNorms />, ['M-06:VIEW']);
    fireEvent.click(await screen.findByRole('button', { name: t('m06w.mouRegister') }));
    const dialog = await screen.findByRole('dialog');
    await within(dialog).findByText('HQ/MOU/2026/3');
    expect(within(dialog).getByText('—')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('WF-249: the Rule Approver’s ✔ goes to W-01 for a pending set; the Payroll Checker is told who decides', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    backend(routes());
    const approver = wrap(<IncentiveNorms />, ['M-06:VIEW', 'M-06:APPROVE']);
    let table = await screen.findByRole('table', { name: t('m06w.register') });
    await within(table).findByText('Depot Okhla');
    fireEvent.click(within(table).getByRole('button', { name: '✔' }));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('?s=W-01'));
    approver.unmount();
    wrap(<IncentiveNorms />, me(['M-06:VIEW'], { roles: ['PAYROLL_CHECKER'] }));
    table = await screen.findByRole('table', { name: t('m06w.register') });
    await within(table).findByText('Depot Okhla');
    fireEvent.click(within(table).getByRole('button', { name: '✔' }));
    expect(await screen.findByText(t('mst.why.ruleApprover'))).toBeInTheDocument();
    expect(assign).toHaveBeenCalledTimes(1);
  });
});
