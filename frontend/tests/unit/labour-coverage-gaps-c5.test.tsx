import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { GangStructure } from '@/modules/labour';
import type { GangLine } from '@/modules/labour/api/labourScreenApi';
import { backend as serve, paged, referenceLists, t, wrap, type Route } from './support/harness';

/**
 * M-09 Gang / Shed Structure as drawn (Wireframes v6.1, OD-121; SRS-2-1, SRS-2-46, SRS-2-47; BR-LABM-001,
 * BR-LABM-006, BR-LABM-011) coverage gaps: Membership, End-date, row Edit and Strength Report opening the register
 * on the lead gang, an empty register, the filters, a gang at a shed with nothing recorded, the strength rows and the
 * Payroll Checker's ✔.
 */

const engine = vi.hoisted(() => ({ value: null as string | null }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams(engine.value ? `engine=${engine.value}` : ''),
}));

const backend = (routes: Record<string, Route>) =>
  serve(Object.fromEntries(Object.entries(routes).map(([k, v]) => [`GET ${k}`, v])));

afterEach(() => {
  engine.value = null;
  vi.unstubAllGlobals();
});

const GANG: GangLine = {
  gangId: 5,
  gangCode: 'GNG-01',
  gangName: 'Okhla Shed A Gang 1',
  payrollType: 'DEPT_LABOUR',
  gangType: 'HANDLING',
  status: 'ACTIVE',
  depot: 'Depot Okhla',
  shed: 'Shed A',
  sardar: null,
  mandal: null,
  labourCount: 4,
  effectiveFrom: '2025-08-07',
  effectiveTo: '2027-03-31',
  endBlocked: false,
  pending: true,
};

const routes = (gangs: GangLine[] = [GANG], over: Record<string, Route> = {}) => ({
  '/v1/reference/lists': () => referenceLists({}),
  '/v1/labour/options': () => ({
    depots: [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla' }],
    designations: [],
  }),
  '/v1/labour/registers/gangs/summary': () => ({}),
  '/v1/labour/registers/gangs': () => paged(gangs),
  '/v1/labour/registers/gang-strengths': () =>
    paged([
      {
        gangId: 5,
        gangCode: 'GNG-01',
        gangName: 'Okhla Shed A Gang 1',
        gangType: 'HANDLING',
        sanctioned: null,
        present: 4,
        shortBy: 0,
        alertOpen: false,
        filledBy: [],
      },
    ]),
  '/v1/labour/gangs/5/members': () => ({}),
  '/v1/labour/gangs': () => paged([]),
  ...over,
});

const register = () => screen.getByTestId('m09-register') as HTMLDetailsElement;
const close = async () => {
  register().open = false;
  fireEvent(register(), new Event('toggle'));
  await waitFor(() => expect(register().open).toBe(false));
};
const gangCalls = (calls: { url: string }[]) =>
  calls.filter((c) => c.url.includes('/v1/labour/registers/gangs?'));

describe('M-09 the drawn buttons open the register on the lead gang', () => {
  it('SRS-2-47: Membership, End-date, the row Edit and Strength Report open the register for Establishment / HR', async () => {
    backend(routes([GANG], { '/v1/labour/gangs/5/members': () => [] }));
    wrap(<GangStructure />, ['M-09:VIEW', 'M-09:EDIT']);
    const table = await screen.findByRole('table', { name: t('m09w.tableLabel') });
    await within(table).findByText('GNG-01');
    for (const button of [
      () => screen.getByRole('button', { name: t('m09w.membership') }),
      () => screen.getByRole('button', { name: t('m09w.endDate') }),
      () => within(table).getByRole('button', { name: `${t('common.edit')} GNG-01` }),
      () => screen.getByRole('button', { name: t('m09w.strengthReport') }),
    ]) {
      fireEvent.click(button());
      await waitFor(() => expect(register().open).toBe(true));
      await close();
    }
  });

  it('BR-LABM-001: an empty register: History says there is no gang, Membership opens the register on none', async () => {
    backend(routes([], { '/v1/labour/registers/gang-strengths': () => paged([]) }));
    wrap(<GangStructure />, ['M-09:VIEW', 'M-09:EDIT']);
    const table = await screen.findByRole('table', { name: t('m09w.tableLabel') });
    expect(await within(table).findByText(t('m09.none'))).toBeInTheDocument();
    expect(screen.getByText(t('m09w.composition', ['—']))).toBeInTheDocument();
    expect(screen.getByText(t('m09w.v.breach', [0]))).toBeInTheDocument();
    expect(screen.getByText(t('m09w.v.blocked', [0]))).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: t('lw.history') }));
    expect((await screen.findAllByText(t('m09.none'))).length).toBeGreaterThanOrEqual(3);
    fireEvent.click(screen.getByRole('button', { name: t('m09w.membership') }));
    await waitFor(() => expect(register().open).toBe(true));
    await close();
    fireEvent.click(screen.getByRole('button', { name: t('m09w.endDate') }));
    await waitFor(() => expect(register().open).toBe(true));
    await close();
    fireEvent.click(screen.getByRole('button', { name: t('m09w.strengthReport') }));
    await waitFor(() => expect(register().open).toBe(true));
  });

  it('SRS-2-1: a viewer sees End-date locked with its reason; opening the register by hand starts on no gang', async () => {
    const calls = backend(routes());
    wrap(<GangStructure />, ['M-09:VIEW']);
    await screen.findByRole('table', { name: t('m09w.tableLabel') });
    const endDate = screen.getByRole('button', { name: t('m09w.endDate') });
    expect(endDate).toBeDisabled();
    expect(endDate).toHaveAttribute('data-why', t('m09w.why.endDate'));
    register().open = true;
    fireEvent(register(), new Event('toggle'));
    await waitFor(() => expect(calls.some((c) => c.url.includes('/v1/labour/gangs?'))).toBe(true));
  });
});

describe('M-09 filters and rows', () => {
  it('SRS-2-47: the depot and search filters ask the register again', async () => {
    const calls = backend(routes());
    wrap(<GangStructure />, ['M-09:VIEW']);
    const depot = screen.getByRole('combobox', { name: t('lw.depotFilter') });
    await within(depot).findByRole('option', { name: 'Depot Okhla' });
    fireEvent.change(depot, { target: { value: '7' } });
    await waitFor(() => expect(gangCalls(calls).some((c) => c.url.includes('depot=7'))).toBe(true));
    fireEvent.change(depot, { target: { value: '' } });
    await waitFor(() => expect((depot as HTMLSelectElement).value).toBe(''));
    fireEvent.change(screen.getByPlaceholderText(t('wf.searchInList')), { target: { value: 'GNG' } });
    await waitFor(() => expect(gangCalls(calls).some((c) => c.url.includes('q=GNG'))).toBe(true));
  });

  it('SRS-2-1: a gang at a shed with no leaders reads the shed and — ; its View shows the end date; Close dismisses it', async () => {
    backend(routes());
    wrap(<GangStructure />, ['M-09:VIEW']);
    const table = await screen.findByRole('table', { name: t('m09w.tableLabel') });
    const tr = (await within(table).findByText('GNG-01')).closest('tr') as HTMLElement;
    expect(within(tr).getByText(t('m09w.shedAt', ['Shed A', 'Depot Okhla']))).toBeInTheDocument();
    // Sardar and Mandal.
    expect(within(tr).getAllByText('—')).toHaveLength(2);
    expect(within(tr).getByText('31 Mar 2027')).toBeInTheDocument();
    // No member list: no leader named, no labour counted.
    expect(await screen.findByText(t('m09w.c.sardarV', ['—']))).toBeInTheDocument();
    fireEvent.click(within(tr).getByRole('button', { name: `${t('common.view')} GNG-01` }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Shed A')).toBeInTheDocument();
    expect(within(dialog).getByText('31 Mar 2027')).toBeInTheDocument();
    expect(within(dialog).getAllByText('—')).toHaveLength(2);
    fireEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('SRS-2-1: a gang without shed or end date views the depot and —', async () => {
    backend(
      routes([{ ...GANG, shed: null, effectiveTo: null, sardar: 'Hemant Kumar', mandal: 'Jagdish Yadav' }]),
    );
    wrap(<GangStructure />, ['M-09:VIEW']);
    const table = await screen.findByRole('table', { name: t('m09w.tableLabel') });
    const tr = (await within(table).findByText('GNG-01')).closest('tr') as HTMLElement;
    fireEvent.click(within(tr).getByRole('button', { name: `${t('common.view')} GNG-01` }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Depot Okhla')).toBeInTheDocument();
    expect(within(dialog).getByText('Jagdish Yadav')).toBeInTheDocument();
    expect(within(dialog).getAllByText('—')).toHaveLength(1);
  });

  it('BR-LABM-011: a handling gang without a sanctioned strength, alert or filler reads —', async () => {
    backend(routes());
    wrap(<GangStructure />, ['M-09:VIEW']);
    const strength = await screen.findByRole('table', { name: t('m09w.strength') });
    const tr = (await within(strength).findByText('Okhla Shed A Gang 1')).closest('tr') as HTMLElement;
    expect(within(tr).getByText(t('m09w.s.handling'))).toBeInTheDocument();
    // Sanctioned, alert and filled by.
    expect(within(tr).getAllByText('—')).toHaveLength(3);
    expect(within(tr).queryByText(t('m09w.s.open'))).not.toBeInTheDocument();
  });

  it('SRS-2-59: the Payroll Checker’s ✔ goes to W-01 for a pending gang', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    backend(routes());
    wrap(<GangStructure />, ['M-09:VIEW', 'M-09:APPROVE']);
    const table = await screen.findByRole('table', { name: t('m09w.tableLabel') });
    await within(table).findByText('GNG-01');
    fireEvent.click(within(table).getByRole('button', { name: '✔' }));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('?s=W-01'));
  });
});
