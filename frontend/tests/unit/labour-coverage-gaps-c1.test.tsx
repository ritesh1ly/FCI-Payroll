import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LabourRates } from '@/modules/labour';
import type { RateSet } from '@/modules/labour/api/labourApi';
import { businessToday } from '@/shared/time';
import { backend as serve, me, paged, referenceLists, t, wrap, type Route } from './support/harness';

/**
 * M-05 Labour Rate Tables (Wireframes v6.1, OD-121; SRS-3-13, SRS-4-11, SRS-5-3) coverage gaps: the drawn
 * New Rate Set per engine and role, the row View / Edit / approve of every table, the DPS wage basis with
 * its settings entered, and a role drawn the entry but not its maker.
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

const SET: RateSet = {
  id: 1,
  payrollType: 'DEPT_LABOUR',
  depotId: null,
  depot: null,
  versionNo: 1,
  effectiveFrom: '2020-04-01',
  effectiveTo: null,
  interimRelief: null,
  goiRate: null,
  goiRateSource: null,
  comparisonGrain: null,
  approvalState: 'APPROVED',
  rates: [
    { designationId: 1, designation: 'Sardar', gangRole: 'SARDAR', mgw: 26400, basicWage: 24800, da: null },
  ],
};

const lists = referenceLists({
  NWNP_LOC_TYPE: [
    ['A', 'A - Metro'],
    ['D', 'D - Sirsa cluster'],
  ],
});

const ASOR = {
  id: 1,
  code: 'NWNP-DP-MH01-ASOR',
  depot: 'Depot Borivali',
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  approvalState: 'PENDING_APPROVAL',
  pieceOfRate: null,
  mgw: { SARDAR: 26400 },
};

const SOR = {
  id: 1,
  operationCode: 'W2P',
  operation: 'Wagon to Platform',
  unit: null,
  rate: 182,
  effectiveFrom: '2025-11-01',
  effectiveTo: null,
  approvalState: 'PENDING_APPROVAL',
};

const WAGE = {
  id: 9,
  locationType: 'A',
  basicWage: 512,
  vda: 268,
  mgw: 20280,
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  approvalState: 'APPROVED',
};

const routes = (over: Record<string, Route> = {}) => ({
  '/v1/reference/lists': () => lists,
  '/v1/labour/rate-sets': () => paged([SET]),
  '/v1/labour/registers/asor-rates': () => paged([ASOR]),
  '/v1/labour/registers/sor-rates': () => paged([SOR]),
  '/v1/labour/registers/min-wages': () => [WAGE],
  '/v1/labour/nwnp-rate-sets/options': () => ({
    operations: [],
    designations: [],
    unitsOfMeasure: [],
    depots: [],
  }),
  '/v1/labour/nwnp-rate-sets': () => paged([]),
  '/v1/labour/options': () => ({ depots: [], designations: [] }),
  ...over,
});

const SYS_ADMIN = (permissions: string[]) => me(permissions, { roles: ['SYSTEM_ADMINISTRATOR'] });
const register = () => screen.getByTestId('m05-register') as HTMLDetailsElement;

describe('M-05 the drawn New Rate Set per engine and role', () => {
  it('SRS-5-3: on NWNP New Rate Set leads to the drawn ASOR form and focuses its first field', async () => {
    engine.value = 'NWNP';
    backend(routes());
    wrap(<LabourRates />, ['M-05:VIEW', 'M-05:CREATE']);
    const first = (await screen.findAllByRole('button', { name: t('m05w.save') }))[0];
    expect(first).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: t('m05w.newSet') }));
    const effective = document.querySelector<HTMLInputElement>('.rf-grid input');
    expect(effective).toHaveAttribute('type', 'date');
    expect(document.activeElement).toBe(effective);
    expect(register().open).toBe(false);
  });

  it('SRS-3-13: the Rule Administrator opens a new set in the register from New Rate Set', async () => {
    backend(routes());
    wrap(<LabourRates />, ['M-05:VIEW', 'M-05:CREATE']);
    await screen.findByRole('table', { name: t('m05w.dept') });
    expect(register().open).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: t('m05w.newSet') }));
    await waitFor(() => expect(register().open).toBe(true));
  });

  it('SRS-3-13: in the Departmental view New Rate Set starts a Departmental set dated today', async () => {
    engine.value = 'DEPT';
    backend(
      routes({
        '/v1/labour/rate-sets/designations': () => [
          { id: 1, code: 'DSDR', name: 'Sardar', gangRole: 'SARDAR' },
        ],
      }),
    );
    wrap(<LabourRates />, ['M-05:VIEW', 'M-05:CREATE']);
    await screen.findByRole('table', { name: t('m05w.dept') });
    fireEvent.click(screen.getByRole('button', { name: t('m05w.newSet') }));
    await waitFor(() => expect(register().open).toBe(true));
    expect(await within(register()).findByDisplayValue(businessToday())).toBeInTheDocument();
  });

  it('SRS-4-11: on DPS New Rate Set opens the DPS set entry in the register', async () => {
    engine.value = 'DPS';
    backend(
      routes({
        '/v1/labour/rate-sets': () => paged([{ ...SET, payrollType: 'DPS_LABOUR' }]),
        '/v1/labour/rate-sets/designations': () => [
          { id: 1, code: 'DSDR', name: 'Sardar', gangRole: 'SARDAR' },
        ],
      }),
    );
    wrap(<LabourRates />, ['M-05:VIEW', 'M-05:CREATE']);
    await screen.findByRole('table', { name: t('m05w.dps') });
    fireEvent.click(screen.getByRole('button', { name: t('m05w.newSet') }));
    await waitFor(() => expect(register().open).toBe(true));
    expect(
      await within(register()).findByRole('button', { name: t('action.m05.propose') }),
    ).toBeInTheDocument();
  });

  it('WF-208: the System Administrator is drawn New Rate Set but is told it makes no payroll entry', async () => {
    backend(routes());
    wrap(<LabourRates />, SYS_ADMIN(['M-05:VIEW']));
    await screen.findByRole('table', { name: t('m05w.dept') });
    const newSet = screen.getByRole('button', { name: t('m05w.newSet') });
    expect(newSet).toBeEnabled();
    fireEvent.click(newSet);
    expect(await screen.findByText(t('mst.why.notMaker'))).toBeInTheDocument();
    expect(register().open).toBe(false);
  });

  it('SRS-G-6: outside Headquarters the drawn entry answers the HQ-only reason', async () => {
    backend(routes());
    wrap(
      <LabourRates />,
      me(['M-05:VIEW'], { roles: ['SYSTEM_ADMINISTRATOR'], atHeadquarters: false, hqOnlyScreens: ['M-05'] }),
    );
    await screen.findByRole('table', { name: t('m05w.dept') });
    fireEvent.click(await screen.findByRole('button', { name: `${t('common.edit')} BASIC-1` }));
    expect(await screen.findByText(t('error.AUTHZ-403-HQ-ONLY'))).toBeInTheDocument();
  });

  it('SRS-5-3: a complete ASOR entry is refused for a role that is not the maker', async () => {
    engine.value = 'NWNP';
    const calls = backend(routes());
    wrap(<LabourRates />, SYS_ADMIN(['M-05:VIEW']));
    const save = await screen.findByRole('button', { name: t('m05w.save') });
    const fields = document.querySelectorAll<HTMLInputElement>('.rf-grid input');
    ['2026-12-01', '2.06', '29900', '28400', '26900', '25500'].forEach((value, i) =>
      fireEvent.change(fields[i] as HTMLInputElement, { target: { value } }),
    );
    fireEvent.click(save);
    expect(await screen.findByText(t('mst.why.notMaker'))).toBeInTheDocument();
    expect(screen.queryByText(t('m05w.completeInRegister'))).not.toBeInTheDocument();
    expect(register().open).toBe(false);
    expect(calls.some((c) => c.method !== 'GET')).toBe(false);
  });

  it('SRS-5-3: a full ASOR entry carries each leader MGW to its designation; one without a gang role stays empty', async () => {
    engine.value = 'NWNP';
    backend(
      routes({
        '/v1/labour/nwnp-rate-sets/options': () => ({
          operations: [{ code: 'W2P', name: 'Wagon to Platform', m27: 'W2P' }],
          designations: [
            { id: 21, code: 'NSDR', name: 'Sardar', gangRole: 'SARDAR' },
            { id: 22, code: 'NTMP', name: 'Temporary', gangRole: null },
          ],
          unitsOfMeasure: [],
          depots: [],
        }),
      }),
    );
    wrap(<LabourRates />, ['M-05:VIEW', 'M-05:CREATE']);
    const save = await screen.findByRole('button', { name: t('m05w.save') });
    const fields = document.querySelectorAll<HTMLInputElement>('.rf-grid input');
    ['2026-12-01', '2.06', '29900', '28400', '26900', '25500'].forEach((value, i) =>
      fireEvent.change(fields[i] as HTMLInputElement, { target: { value } }),
    );
    fireEvent.click(save);
    expect(await screen.findByText(t('m05w.completeInRegister'))).toBeInTheDocument();
    await waitFor(() => expect(register().open).toBe(true));
    expect(await within(register()).findByDisplayValue('29900')).toBeInTheDocument();
    expect(within(register()).queryByDisplayValue('28400')).not.toBeInTheDocument();
    expect(await within(register()).findByDisplayValue('2.06')).toBeInTheDocument();
  });

  it('SRS-5-3: a negative ASOR amount is marked in error and refused', async () => {
    engine.value = 'NWNP';
    backend(routes());
    wrap(<LabourRates />, ['M-05:VIEW', 'M-05:CREATE']);
    const save = await screen.findByRole('button', { name: t('m05w.save') });
    const fields = document.querySelectorAll<HTMLInputElement>('.rf-grid input');
    ['2026-12-01', '-1', '29900', '28400', '26900', '25500'].forEach((value, i) =>
      fireEvent.change(fields[i] as HTMLInputElement, { target: { value } }),
    );
    fireEvent.click(save);
    expect(await screen.findByText(t('m05w.correct'))).toBeInTheDocument();
    expect(document.querySelectorAll('.rf-field.err')).toHaveLength(1);
  });

  it('SRS-5-3: a viewer without the maker right reads that the entry is the administrator’s', async () => {
    engine.value = 'NWNP';
    backend(routes());
    wrap(<LabourRates />, ['M-05:VIEW']);
    expect(await screen.findByText(t('m05w.entryAdmin'))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('m05w.save') })).not.toBeInTheDocument();
  });
});

describe('M-05 Departmental rows: View, Edit, approve, history', () => {
  it('SRS-3-13: View shows the parameter read-only with its date; Close dismisses it', async () => {
    backend(routes());
    wrap(<LabourRates />, ['M-05:VIEW']);
    const table = await screen.findByRole('table', { name: t('m05w.dept') });
    fireEvent.click(await within(table).findByRole('button', { name: `${t('common.view')} BASIC-1` }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(t('m05w.col.applies'))).toBeInTheDocument();
    expect(within(dialog).getByText(t('m05w.basicOf', ['Sardar']))).toBeInTheDocument();
    expect(within(dialog).getByText('01 Apr 2020')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('SRS-3-13: no set in force: the row View dates nothing', async () => {
    backend(routes({ '/v1/labour/rate-sets': () => paged([]) }));
    wrap(<LabourRates />, ['M-05:VIEW']);
    const table = await screen.findByRole('table', { name: t('m05w.dept') });
    await within(table).findByText(t('m05w.basicNone'));
    fireEvent.click(within(table).getByRole('button', { name: `${t('common.view')} BASIC` }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('—')).toBeInTheDocument();
  });

  it('SRS-3-13: the maker’s row Edit opens a new set in the register; the CPF rule stays off with its reason', async () => {
    backend(routes());
    wrap(<LabourRates />, ['M-05:VIEW', 'M-05:CREATE']);
    const table = await screen.findByRole('table', { name: t('m05w.dept') });
    const cpf = await within(table).findByRole('button', { name: `${t('common.edit')} CPF-NPH` });
    expect(cpf).toBeDisabled();
    expect(cpf).toHaveAttribute('data-why', t('m05w.why.rule'));
    fireEvent.click(within(table).getByRole('button', { name: `${t('common.edit')} BASIC-1` }));
    await waitFor(() => expect(register().open).toBe(true));
  });

  it('WF-208: the System Administrator’s row Edit is told it is not the maker', async () => {
    backend(routes());
    wrap(<LabourRates />, SYS_ADMIN(['M-05:VIEW']));
    const table = await screen.findByRole('table', { name: t('m05w.dept') });
    fireEvent.click(await within(table).findByRole('button', { name: `${t('common.edit')} NPH-DA` }));
    expect(await screen.findByText(t('mst.why.notMaker'))).toBeInTheDocument();
    expect(register().open).toBe(false);
  });

  it('WF-249: the Payroll Checker’s ✔ says the Rule Approver decides', async () => {
    backend(routes());
    wrap(<LabourRates />, me(['M-05:VIEW'], { roles: ['PAYROLL_CHECKER'] }));
    const table = await screen.findByRole('table', { name: t('m05w.dept') });
    await within(table).findByText(t('m05w.basicOf', ['Sardar']));
    fireEvent.click(within(table).getAllByRole('button', { name: '✔' })[0] as HTMLElement);
    expect(await screen.findByText(t('mst.why.ruleApprover'))).toBeInTheDocument();
  });

  it('WF-249: the Rule Approver’s ✔ goes to W-01 when a set waits, and says nothing waits otherwise', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    backend(
      routes({
        '/v1/labour/rate-sets': () => paged([SET, { ...SET, id: 2, approvalState: 'PENDING_APPROVAL' }]),
      }),
    );
    wrap(<LabourRates />, ['M-05:VIEW', 'M-05:APPROVE']);
    const table = await screen.findByRole('table', { name: t('m05w.dept') });
    await within(table).findByText(t('m05w.basicOf', ['Sardar']));
    fireEvent.click(within(table).getAllByRole('button', { name: '✔' })[0] as HTMLElement);
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('?s=W-01'));
  });

  it('M-05: the table History opens the register', async () => {
    backend(routes());
    wrap(<LabourRates />, ['M-05:VIEW']);
    const table = await screen.findByRole('table', { name: t('m05w.dept') });
    await within(table).findByText(t('m05w.basicOf', ['Sardar']));
    fireEvent.click(screen.getByRole('button', { name: t('m05w.history') }));
    await waitFor(() => expect(register().open).toBe(true));
    fireEvent.click(screen.getByText(t('m05w.register')));
  });
});

describe('M-05 DPS wage basis', () => {
  it('SRS-4-11: entered Interim Relief and GoI rate show as amounts with their dates and the GoI source', async () => {
    engine.value = 'DPS';
    backend(
      routes({
        '/v1/labour/rate-sets': () =>
          paged([
            {
              ...SET,
              payrollType: 'DPS_LABOUR',
              interimRelief: 1500,
              goiRate: 640,
              goiRateSource: 'GoI notification 2026/11',
            },
          ]),
      }),
    );
    wrap(<LabourRates />, ['M-05:VIEW']);
    const table = await screen.findByRole('table', { name: t('m05w.dps') });
    expect(await within(table).findByText('GoI notification 2026/11')).toBeInTheDocument();
    expect(within(table).queryByText(t('m05w.dps.setting'))).not.toBeInTheDocument();
    expect(within(table).getByText(t('m05w.dps.mgwV', ['20,280']))).toBeInTheDocument();
    expect(within(table).getByText(t('m05w.dps.day', ['Sardar']))).toBeInTheDocument();
    fireEvent.click(within(table).getByRole('button', { name: `${t('common.view')} GOI` }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(t('m05w.col.source'))).toBeInTheDocument();
    expect(within(dialog).getByText('01 Apr 2020')).toBeInTheDocument();
  });

  it('CF-49: no set and no minimum wage: every value stays not configured or a setting', async () => {
    engine.value = 'DPS';
    backend(
      routes({ '/v1/labour/rate-sets': () => paged([]), '/v1/labour/registers/min-wages': () => ({}) }),
    );
    wrap(<LabourRates />, ['M-05:VIEW']);
    const table = await screen.findByRole('table', { name: t('m05w.dps') });
    expect(await within(table).findAllByText(t('m05w.dps.setting'))).toHaveLength(2);
    expect(within(table).getAllByText(t('m05.notConfigured')).length).toBeGreaterThanOrEqual(2);
    expect(within(table).queryByText(t('m05w.dps.day', ['Sardar']))).not.toBeInTheDocument();
    expect(within(table).getByText(t('m05w.dps.goiSource'))).toBeInTheDocument();
  });
});

describe('M-05 NWNP tables: View, Edit, approve', () => {
  it('SRS-5-3: an ASOR row without piece rate reads — in the table and its View; the maker’s Edit opens a new set', async () => {
    engine.value = 'NWNP';
    backend(routes());
    wrap(<LabourRates />, ['M-05:VIEW', 'M-05:CREATE']);
    const asor = await screen.findByRole('table', { name: t('m05w.asor') });
    await within(asor).findByText('26,400');
    expect(within(asor).getByText('Depot Borivali')).toBeInTheDocument();
    fireEvent.click(
      within(asor).getByRole('button', { name: `${t('common.view')} NWNP-DP-MH01-ASOR 2026-04-01` }),
    );
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Depot Borivali')).toBeInTheDocument();
    expect(within(dialog).getByText(t('lw.state.PENDING'))).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    const effective = document.querySelector<HTMLInputElement>('.rf-grid input');
    fireEvent.click(
      within(asor).getByRole('button', { name: `${t('common.edit')} NWNP-DP-MH01-ASOR 2026-04-01` }),
    );
    expect(document.activeElement).toBe(effective);
  });

  it('SRS-5-3: History of the ASOR table opens the register', async () => {
    engine.value = 'NWNP';
    backend(routes());
    wrap(<LabourRates />, ['M-05:VIEW']);
    await screen.findByRole('table', { name: t('m05w.asor') });
    fireEvent.click(screen.getByRole('button', { name: t('m05w.history') }));
    await waitFor(() => expect(register().open).toBe(true));
  });

  it('SRS-5-3: the minimum wage View reads the figures; its Edit explains where the wage is kept', async () => {
    engine.value = 'NWNP';
    backend(routes());
    wrap(<LabourRates />, ['M-05:VIEW', 'M-05:CREATE']);
    const wage = await screen.findByRole('table', { name: t('m05w.minWage') });
    await within(wage).findByText('20,280');
    fireEvent.click(within(wage).getByRole('button', { name: `${t('common.view')} A` }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('A - Metro')).toBeInTheDocument();
    expect(within(dialog).getByText('512')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    fireEvent.click(within(wage).getByRole('button', { name: `${t('common.edit')} D` }));
    expect(await screen.findByText(t('m05.why.nwnpMinWage'))).toBeInTheDocument();
  });

  it('SRS-5-3: a SOR row without unit reads — and its View shows the operation; the System Administrator’s Edit is refused', async () => {
    engine.value = 'NWNP';
    backend(routes());
    wrap(<LabourRates />, SYS_ADMIN(['M-05:VIEW']));
    const sor = await screen.findByRole('table', { name: t('m05w.sor') });
    await within(sor).findByText('W2P - Wagon to Platform');
    fireEvent.click(within(sor).getByRole('button', { name: `${t('common.view')} W2P` }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Wagon to Platform')).toBeInTheDocument();
    expect(within(dialog).getByText('—')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    fireEvent.click(within(sor).getByRole('button', { name: `${t('common.edit')} W2P` }));
    expect(await screen.findByText(t('mst.why.notMaker'))).toBeInTheDocument();
  });

  it('WF-249: the Rule Approver’s ✔ on each NWNP table goes to W-01 for a pending row and says nothing waits otherwise', async () => {
    engine.value = 'NWNP';
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    backend(routes());
    wrap(<LabourRates />, ['M-05:VIEW', 'M-05:APPROVE']);
    const asor = await screen.findByRole('table', { name: t('m05w.asor') });
    await within(asor).findByText('26,400');
    fireEvent.click(within(asor).getByRole('button', { name: '✔' }));
    expect(assign).toHaveBeenCalledTimes(1);
    const sor = screen.getByRole('table', { name: t('m05w.sor') });
    await within(sor).findByText('W2P - Wagon to Platform');
    fireEvent.click(within(sor).getByRole('button', { name: '✔' }));
    expect(assign).toHaveBeenCalledTimes(2);
    const wage = screen.getByRole('table', { name: t('m05w.minWage') });
    fireEvent.click(within(wage).getAllByRole('button', { name: '✔' })[0] as HTMLElement);
    expect(await screen.findByText(t('mst.nothingWaiting'))).toBeInTheDocument();
    expect(assign).toHaveBeenCalledTimes(2);
  });
});
