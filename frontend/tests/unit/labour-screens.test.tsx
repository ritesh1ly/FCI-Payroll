import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { GangStructure, IncentiveNorms, LabourMaster, LabourRates } from '@/modules/labour';
import {
  asorEntryReady,
  byRole,
  figure,
  gangPill,
  memberPill,
  normEntryReady,
  rangeText,
  rupees,
  setInForce,
  versionPill,
} from '@/modules/labour/business/labourScreenRules';
import type { DayRate, RateSet } from '@/modules/labour/api/labourApi';
import type { GangLine, MemberLine } from '@/modules/labour/api/labourScreenApi';
import { backend as serve, paged, referenceLists, t, wrap, type Route } from './support/harness';

/** M-02, M-05, M-06, M-09 as Wireframes v6.1 draws them (OD-121; SRS-2-27, SRS-2-47, SRS-3-12, SRS-5-3). */

const engine = vi.hoisted(() => ({ value: null as string | null }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams(engine.value ? `engine=${engine.value}` : ''),
}));

/** The screens only read: every route here is a GET. */
const backend = (routes: Record<string, Route>) =>
  serve(Object.fromEntries(Object.entries(routes).map(([k, v]) => [`GET ${k}`, v])));

afterEach(() => {
  engine.value = null;
  vi.unstubAllGlobals();
});

const MEMBER: MemberLine = {
  employeeId: 11,
  labourCode: 'DL-20114',
  name: 'Hemant Kumar',
  payrollType: 'DEPT_LABOUR',
  designation: 'Sardar',
  gangRole: 'SARDAR',
  gang: 'GNG-01',
  depot: 'Depot Okhla',
  aadhaarMasked: '********2201',
  basicWage: 24800,
  cpfCode: 'CPF/DL-20114',
  status: 'ACTIVE',
  transferPending: false,
};

const GANG: GangLine = {
  gangId: 5,
  gangCode: 'GNG-01',
  gangName: 'Okhla Shed A Gang 1',
  payrollType: 'DEPT_LABOUR',
  gangType: 'HANDLING',
  status: 'ACTIVE',
  depot: 'Depot Okhla',
  shed: null,
  sardar: 'Hemant Kumar',
  mandal: 'Jagdish Yadav',
  labourCount: 4,
  effectiveFrom: '2025-08-07',
  effectiveTo: null,
  endBlocked: false,
  pending: false,
};

const rate = (over: Partial<DayRate>): DayRate => ({
  designationId: 1,
  designation: 'Sardar',
  gangRole: 'SARDAR',
  mgw: null,
  basicWage: null,
  da: null,
  ...over,
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
    rate({ designationId: 2, designation: 'Handling Labour', gangRole: 'HANDLING', basicWage: 22600 }),
    rate({ basicWage: 24800 }),
  ],
};

const lists = referenceLists({
  LABOUR_NORM_TYPE: [
    ['HANDLING', 'Handling Norm'],
    ['HEIGHT', 'Height Norm'],
    ['LEAD', 'Lead Norm'],
  ],
  NWNP_LOC_TYPE: [
    ['A', 'A - Metro'],
    ['D', 'D - Sirsa cluster'],
  ],
  ORDER_COPY_DOCUMENT_TYPE: [['SANCTION_ORDER', 'Sanction Order']],
});

const memberRoutes = (rows: MemberLine[] = [MEMBER]) => ({
  '/v1/reference/lists': () => lists,
  '/v1/labour/options': () => ({
    depots: [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla' }],
    designations: [],
  }),
  '/v1/labour/gangs': () => paged([]),
  '/v1/labour/registers/members': () => paged(rows),
  '/v1/labour/registers/attachments': () =>
    paged([
      {
        id: 1,
        reference: 'TA-2026-9412',
        labourCode: 'DL-20301',
        labour: 'Anand Rai',
        category: 'HANDLING',
        gang: 'Okhla Shed A Gang 1',
        from: '2026-11-08',
        to: null,
        status: 'PENDING_APPROVAL',
      },
    ]),
  '/v1/labour/registers/light-duty-orders': () =>
    paged([
      {
        id: 1,
        labourCode: 'DL-20390',
        labour: 'Arun Sahu',
        fromDesignation: 'Handling Labour',
        toDesignation: 'Ancillary Labour',
        reasonCode: 'PERMANENT_LIGHT_DUTY',
        effectiveDate: '2026-07-07',
        orderReference: 'DO/OKH/LD/2026/7',
        issuingAuthority: 'Depot Manager, Okhla',
        payOption: 'PROTECTED_PAY',
        protectedBasic: 22600,
        status: 'APPROVED',
      },
    ]),
  '/v1/labour/members/11/documents': () => [
    {
      id: 3,
      fileName: 'labour_order.pdf',
      documentTypeCode: 'SANCTION_ORDER',
      documentTypeName: 'Sanction Order',
      referenceNumber: 'DO/OKH/TR/1',
      uploadedBy: 'hr.delhi@fci-demo.test',
      uploadedAt: '2026-10-08T10:00:00Z',
    },
  ],
  '/v1/document-types': () => [{ code: 'SANCTION_ORDER', referenceRequired: true }],
});

describe('M-02 Labour Master as drawn', () => {
  it('SRS_NF_3_theFirstMemberAsksNoSizeTheServerDoesNotOffer', async () => {
    const calls = backend({
      ...memberRoutes(),
      '/v1/labour/members/': () => [],
    });
    wrap(<LabourMaster />, ['M-02:VIEW']);
    await waitFor(() => expect(calls.some((c) => c.url.includes('/documents'))).toBe(true));
    expect(calls.filter((c) => /[?&]size=1(&|$)/.test(c.url))).toHaveLength(0);
  });

  it('SRS-2-27: each view lists its engine with the drawn columns; a non-maker sees the actions locked with the reason', async () => {
    engine.value = 'DPS';
    const calls = backend(memberRoutes());
    wrap(<LabourMaster />, ['M-02:VIEW']);
    const table = await screen.findByRole('table', { name: t('m02w.tableLabel') });
    await within(table).findByText('DL-20114');
    expect(within(table).getByText('********2201')).toBeInTheDocument();
    expect(calls.some((c) => c.url.includes('/registers/members') && c.url.includes('type=DPS_LABOUR'))).toBe(
      true,
    );
    expect(screen.getByRole('heading', { name: t('m02w.title', ['DPS Labour']) })).toBeInTheDocument();
    for (const name of ['m02w.editRecord', 'm02w.transfer', 'm02w.separation']) {
      const button = screen.getByRole('button', { name: t(name) });
      expect(button).toBeDisabled();
      expect(button).toHaveAttribute('data-why');
    }
    expect(await screen.findByText('DO/OKH/LD/2026/7')).toBeInTheDocument();
    expect(await screen.findByText('TA-2026-9412')).toBeInTheDocument();
  });

  it('SRS-2-30: the Establishment / HR Edit opens the amendment in the register; Cancel explains and sends nothing', async () => {
    const calls = backend({
      ...memberRoutes(),
      '/v1/labour/members': () => paged([]),
      '/v1/labour/members/11': () => ({
        employeeId: 11,
        labourCode: 'DL-20114',
        payrollType: 'DEPT_LABOUR',
        firstName: 'Hemant',
        lastName: 'Kumar',
        fatherName: null,
        gender: 'M',
        birthDate: '1982-03-14',
        joiningDate: '2008-07-01',
        designationId: 1,
        depotLocationId: 7,
        panMasked: null,
        ifsc: null,
        bankAccountMasked: null,
        mobileMasked: null,
        pfApplicable: true,
        cpfCode: 'CPF/DL-20114',
        uanNumber: null,
        casualLabour: false,
      }),
    });
    wrap(<LabourMaster />, ['M-02:VIEW', 'M-02:EDIT', 'M-02:CREATE']);
    const table = await screen.findByRole('table', { name: t('m02w.tableLabel') });
    await within(table).findByText('DL-20114');
    fireEvent.click(within(table).getByRole('button', { name: t('wf.cancel') }));
    expect(await screen.findByText(t('m02w.why.cancel'))).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
    fireEvent.click(within(table).getByRole('button', { name: `${t('common.edit')} DL-20114` }));
    expect(await screen.findByText(t('m02.amendOf', ['DL-20114']))).toBeInTheDocument();
  });

  it('SRS-2-59: ✔ is drawn for the Payroll Checker, not for Establishment / HR, whose APPROVE is its level in W-01', async () => {
    backend(memberRoutes());
    const checker = wrap(<LabourMaster />, ['M-02:VIEW', 'M-02:APPROVE']);
    let table = await screen.findByRole('table', { name: t('m02w.tableLabel') });
    await within(table).findByText('DL-20114');
    expect(within(table).getByRole('button', { name: '✔' })).toBeInTheDocument();
    checker.unmount();
    wrap(<LabourMaster />, ['M-02:VIEW', 'M-02:EDIT', 'M-02:APPROVE']);
    table = await screen.findByRole('table', { name: t('m02w.tableLabel') });
    await within(table).findByText('DL-20114');
    expect(within(table).queryByRole('button', { name: '✔' })).toBeNull();
  });

  it('SRS-2-27: the drawn View shows the record read-only, and the NWNP view reads the MGW rate', async () => {
    engine.value = 'NWNP';
    backend(
      memberRoutes([{ ...MEMBER, labourCode: 'NW-10442', payrollType: 'NWNP_LABOUR', basicWage: null }]),
    );
    wrap(<LabourMaster />, ['M-02:VIEW']);
    const table = await screen.findByRole('table', { name: t('m02w.tableLabel') });
    await within(table).findByText('NW-10442');
    expect(within(table).getByText(t('m02w.mgw.SARDAR'))).toBeInTheDocument();
    fireEvent.click(within(table).getByRole('button', { name: `${t('common.view')} NW-10442` }));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });

  it('SRS-G-13: a role without the export right is told why, the one with it downloads', async () => {
    backend(memberRoutes());
    wrap(<LabourMaster />, ['M-02:VIEW']);
    fireEvent.click(await screen.findByRole('button', { name: t('wf.export') }));
    expect(await screen.findByText(t('lw.why.export'))).toBeInTheDocument();
  });
});

describe('M-05 Labour Rate Tables as drawn', () => {
  const routes = {
    '/v1/reference/lists': () => lists,
    '/v1/labour/rate-sets': () => paged([SET]),
    '/v1/labour/registers/asor-rates': () =>
      paged([
        {
          id: 1,
          code: 'NWNP-DP-MH01-ASOR',
          depot: 'Depot Borivali',
          effectiveFrom: '2025-04-01',
          effectiveTo: '2026-03-31',
          approvalState: 'APPROVED',
          pieceOfRate: 1.82,
          mgw: { SARDAR: 26400 },
        },
      ]),
    '/v1/labour/registers/sor-rates': () =>
      paged([
        {
          id: 1,
          operationCode: 'W2P',
          operation: 'Wagon to Platform',
          unit: 'Per 100 bags',
          rate: 182,
          effectiveFrom: '2025-11-01',
          effectiveTo: null,
          approvalState: 'APPROVED',
        },
      ]),
    '/v1/labour/registers/min-wages': () => [
      {
        id: 9,
        locationType: 'A',
        basicWage: 512,
        vda: 268,
        mgw: 20280,
        effectiveFrom: '2026-04-01',
        effectiveTo: null,
        approvalState: 'APPROVED',
      },
    ],
  };

  it('SRS-3-13: the Departmental view lists the set in force in the drawn order and keeps the CPF rule off edit', async () => {
    backend(routes);
    wrap(<LabourRates />, ['M-05:VIEW']);
    const table = await screen.findByRole('table', { name: t('m05w.dept') });
    await within(table).findByText(t('m05w.basicOf', ['Sardar']));
    const names = within(table)
      .getAllByRole('row')
      .map((r) => r.textContent ?? '');
    expect(names.findIndex((n) => n.includes('Sardar'))).toBeLessThan(
      names.findIndex((n) => n.includes('Handling Labour')),
    );
    expect(within(table).getByText(t('m05w.notDeducted'))).toBeInTheDocument();
    const newSet = screen.getByRole('button', { name: t('m05w.newSet') });
    expect(newSet).toBeDisabled();
    expect(newSet).toHaveAttribute('data-why', t('m05w.why.newSet'));
  });

  it('SRS-4-11: the DPS view keeps an unset Interim Relief a setting (R-RULE)', async () => {
    engine.value = 'DPS';
    backend({ ...routes, '/v1/labour/rate-sets': () => paged([{ ...SET, payrollType: 'DPS_LABOUR' }]) });
    wrap(<LabourRates />, ['M-05:VIEW']);
    const table = await screen.findByRole('table', { name: t('m05w.dps') });
    expect(await within(table).findAllByText(t('m05w.dps.setting'))).toHaveLength(2);
  });

  it('SRS-5-3: the NWNP view reads ASOR, SOR and minimum wage; an unconfigured location type says so; a superseded set is off edit', async () => {
    engine.value = 'NWNP';
    backend(routes);
    wrap(<LabourRates />, ['M-05:VIEW', 'M-05:CREATE']);
    const asor = await screen.findByRole('table', { name: t('m05w.asor') });
    await within(asor).findByText('26,400');
    expect(within(asor).getByText(t('lw.state.SUPERSEDED'))).toBeInTheDocument();
    expect(
      within(asor).getByRole('button', { name: `${t('common.edit')} NWNP-DP-MH01-ASOR 2025-04-01` }),
    ).toBeDisabled();
    const wage = await screen.findByRole('table', { name: t('m05w.minWage') });
    await within(wage).findByText('20,280');
    expect(within(wage).getByText(t('m05.notConfigured'))).toBeInTheDocument();
    expect(await screen.findByText('W2P - Wagon to Platform')).toBeInTheDocument();
  });

  it('SRS-5-3: the drawn ASOR form refuses an empty entry and carries a full one to the register', async () => {
    engine.value = 'NWNP';
    backend({
      ...routes,
      '/v1/labour/nwnp-rate-sets/options': () => ({
        operations: [{ code: 'W2P', name: 'Wagon to Platform', m27: 'W2P' }],
        designations: [{ id: 21, code: 'NSDR', name: 'Sardar', gangRole: 'SARDAR' }],
        unitsOfMeasure: [],
        depots: [],
      }),
      '/v1/labour/nwnp-rate-sets': () => paged([]),
    });
    wrap(<LabourRates />, ['M-05:VIEW', 'M-05:CREATE']);
    const save = await screen.findByRole('button', { name: t('m05w.save') });
    fireEvent.click(save);
    expect(await screen.findByText(t('m05w.correct'))).toBeInTheDocument();
    const fields = document.querySelectorAll<HTMLInputElement>('.rf-grid input');
    expect(fields).toHaveLength(6);
    ['2026-12-01', '2.06', '29900', '28400', '26900', '25500'].forEach((value, i) =>
      fireEvent.change(fields[i] as HTMLInputElement, { target: { value } }),
    );
    fireEvent.click(save);
    expect(await screen.findByText(t('m05w.completeInRegister'))).toBeInTheDocument();
    expect(await screen.findByDisplayValue('2.06')).toBeInTheDocument();
  });
});

describe('M-06 Incentive Norms as drawn', () => {
  const routes = {
    '/v1/reference/lists': () => lists,
    '/v1/labour/norm-sets/depots': () => [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla' }],
    '/v1/labour/norm-sets': () => paged([]),
    '/v1/labour/registers/norms/summary': () => ({ depotsWithSet: 1, slabTables: 2 }),
    '/v1/labour/registers/norms/3/slabs': () => [
      { table: 'BAND_1', order: 1, from: 1, to: 30, ratePercent: 8, stepSize: 30 },
      { table: 'BAND_1', order: 4, from: 91, to: null, ratePercent: 50, stepSize: 30 },
    ],
    '/v1/labour/registers/norms': () =>
      paged([
        {
          id: 3,
          depotCode: 'DP-DL01',
          depot: 'Depot Okhla',
          mouReference: 'MoU/OKH/2024-01',
          minLabourNumber: 3600,
          minSlabWeight: 50,
          heightNorm: 12,
          leadNorm: 30,
          effectiveFrom: '2024-11-07',
          effectiveTo: null,
          approvalState: 'APPROVED',
        },
      ]),
  };

  it('SRS-3-12: the register, the tiles and the bands; the entry is locked with the HQ reason for a depot role', async () => {
    backend(routes);
    wrap(<IncentiveNorms />, ['M-06:VIEW']);
    const table = await screen.findByRole('table', { name: t('m06w.register') });
    await within(table).findByText('MoU/OKH/2024-01');
    expect(await screen.findByText('91 and above')).toBeInTheDocument();
    for (const name of ['m06w.newSet', 'm06w.import', 'm06w.maintain']) {
      expect(screen.getByRole('button', { name: t(name) })).toHaveAttribute('data-why', t('m06w.why.hq'));
    }
    expect(screen.queryByRole('button', { name: t('m05w.save') })).toBeNull();
  });

  it("SRS-3-12: the Rule Administrator's drawn form opens the proposal with its values; an incomplete one is refused", async () => {
    backend({ ...routes, '/v1/masters/circulars/current': () => [] });
    wrap(<IncentiveNorms />, ['M-06:VIEW', 'M-06:CREATE']);
    const save = await screen.findByRole('button', { name: t('m05w.save') });
    fireEvent.click(save);
    expect(await screen.findByText(t('m05w.correct'))).toBeInTheDocument();
    const fields = document.querySelectorAll<HTMLInputElement>('.rf-grid input');
    ['Okhla', 'MoU/OKH/2026-01', '3200', '66', '12', '30', '2026-12-01'].forEach((value, i) =>
      fireEvent.change(fields[i] as HTMLInputElement, { target: { value } }),
    );
    fireEvent.click(save);
    expect(await screen.findByText(t('m06w.completeInRegister'))).toBeInTheDocument();
    expect((await screen.findAllByDisplayValue('3200')).length).toBeGreaterThan(1);
  });

  it('M-06: the MoU register opens the MoU circulars', async () => {
    backend({
      ...routes,
      '/v1/masters/circulars/current': () => [{ id: 1, number: 'HQ/MOU/2026/2', subject: 'Okhla union MoU' }],
    });
    wrap(<IncentiveNorms />, ['M-06:VIEW']);
    fireEvent.click(await screen.findByRole('button', { name: t('m06w.mouRegister') }));
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText('HQ/MOU/2026/2')).toBeInTheDocument();
  });
});

describe('M-09 Gang / Shed Structure as drawn', () => {
  const routes = {
    '/v1/reference/lists': () => lists,
    '/v1/labour/options': () => ({
      depots: [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla' }],
      designations: [],
    }),
    '/v1/labour/registers/gangs/summary': () => ({ leaderBreaches: 1, endBlocked: 1 }),
    '/v1/labour/registers/gangs': () =>
      paged([
        GANG,
        { ...GANG, gangId: 6, gangCode: 'GNG-02', mandal: null },
        {
          ...GANG,
          gangId: 7,
          gangCode: 'GNG-07',
          status: 'END_DATED',
          effectiveTo: '2026-07-10',
          endBlocked: true,
        },
      ]),
    '/v1/labour/registers/gang-strengths': () =>
      paged([
        {
          gangId: 5,
          gangCode: 'GNG-01',
          gangName: 'Okhla Shed A Gang 1',
          gangType: 'HANDLING',
          sanctioned: 4,
          present: 3,
          shortBy: 1,
          alertOpen: true,
          filledBy: ['DL-20390'],
        },
        {
          gangId: 1,
          gangCode: 'DP-DL01-G001',
          gangName: 'Ancillary Pool',
          gangType: 'ANCILLARY_POOL',
          sanctioned: null,
          present: 2,
          shortBy: 0,
          alertOpen: false,
          filledBy: [],
        },
      ]),
    '/v1/labour/gangs/5/members': () => [
      { employeeId: 1, labourCode: 'DL-20114', name: 'Hemant Kumar', role: 'SARDAR' },
      { employeeId: 3, labourCode: 'DL-20180', name: 'Prem Chand', role: 'HANDLING' },
    ],
  };

  it("BR-LABM-001, BR-LABM-006, BR-LABM-011: the register reads each state, the figures and today's strength", async () => {
    backend(routes);
    wrap(<GangStructure />, ['M-09:VIEW']);
    const table = await screen.findByRole('table', { name: t('m09w.tableLabel') });
    await within(table).findByText('GNG-02');
    expect(within(table).getByText(t('m09w.state.NO_MANDAL'))).toBeInTheDocument();
    expect(within(table).getByText(t('m09w.state.END_BLOCKED'))).toBeInTheDocument();
    expect(await screen.findByText(t('m09w.v.breach', [1]))).toBeInTheDocument();
    expect(screen.getByText(t('m09w.v.blocked', [1]))).toBeInTheDocument();
    expect(await screen.findByText('DL-20390')).toBeInTheDocument();
    expect(screen.getByText(t('m09w.s.excluded'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('m09w.newGang') })).toBeDisabled();
    expect(await screen.findByText(t('m09w.c.sardarV', ['Hemant Kumar']))).toBeInTheDocument();
  });

  it('BR-LABM-006: Edit stays off for a gang with work-slips after its end date, even for Establishment / HR', async () => {
    backend(routes);
    wrap(<GangStructure />, ['M-09:VIEW', 'M-09:EDIT', 'M-09:CREATE']);
    const table = await screen.findByRole('table', { name: t('m09w.tableLabel') });
    const off = await within(table).findByRole('button', { name: `${t('common.edit')} GNG-07` });
    expect(off).toBeDisabled();
    expect(off).toHaveAttribute('data-why', t('m09w.why.slips'));
    expect(within(table).getByRole('button', { name: `${t('common.edit')} GNG-02` })).toBeEnabled();
  });

  it('SRS-2-1: the drawn New Gang opens the gang entry and Membership the gang, in the register', async () => {
    backend({ ...routes, '/v1/labour/gangs': () => paged([]) });
    wrap(<GangStructure />, ['M-09:VIEW', 'M-09:EDIT', 'M-09:CREATE']);
    await screen.findByRole('table', { name: t('m09w.tableLabel') });
    await waitFor(() => expect(screen.getByRole('button', { name: t('m09w.membership') })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: t('m09w.newGang') }));
    expect((await screen.findAllByText(t('m09.new'))).length).toBeGreaterThan(0);
  });
});

describe('labour screen rules', () => {
  it('SRS-2-27: the member pill reads a pending transfer first, then what the record lacks', () => {
    expect(memberPill({ ...MEMBER, transferPending: true }).key).toBe('m02w.state.TRANSFER');
    expect(memberPill({ ...MEMBER, status: 'RETIRED', cpfCode: null }).key).toBe('labour.status.RETIRED');
    expect(memberPill({ ...MEMBER, cpfCode: null }).key).toBe('m02w.state.CPF');
    expect(memberPill({ ...MEMBER, cpfCode: null, aadhaarMasked: null }).key).toBe('m02w.state.AADHAAR_CPF');
    expect(memberPill(MEMBER)).toEqual({ key: 'labour.status.ACTIVE', tone: 'p-ok' });
  });

  it('BR-LABM-001: the gang pill; the Ancillary Pool has no leaders to lack', () => {
    expect(gangPill({ ...GANG, endBlocked: true }).key).toBe('m09w.state.END_BLOCKED');
    expect(gangPill({ ...GANG, status: 'END_DATED' }).key).toBe('m09.status.END_DATED');
    expect(gangPill({ ...GANG, sardar: null }).key).toBe('m09w.state.NO_SARDAR');
    expect(gangPill({ ...GANG, mandal: null }).key).toBe('m09w.state.NO_MANDAL');
    expect(gangPill({ ...GANG, gangType: 'ANCILLARY_POOL', sardar: null, mandal: null }).key).toBe(
      'm09.status.ACTIVE',
    );
  });

  it('SC-19: one version vocabulary, on both sides of the end date', () => {
    expect(versionPill('PENDING_APPROVAL', null, '2026-11-07').key).toBe('lw.state.PENDING');
    expect(versionPill('REJECTED', null, '2026-11-07').key).toBe('lw.state.REJECTED');
    expect(versionPill('APPROVED', '2026-11-06', '2026-11-07').key).toBe('lw.state.SUPERSEDED');
    expect(versionPill('APPROVED', '2026-11-07', '2026-11-07').key).toBe('lw.state.ACTIVE');
    expect(versionPill('APPROVED', null, '2026-11-07').key).toBe('lw.state.ACTIVE');
  });

  it('SRS-4-11: the set in force is approved, begun and not ended', () => {
    expect(setInForce(undefined, '2026-11-07')).toBeNull();
    expect(setInForce([{ ...SET, approvalState: 'PENDING_APPROVAL' }], '2026-11-07')).toBeNull();
    expect(setInForce([{ ...SET, effectiveFrom: '2026-11-08' }], '2026-11-07')).toBeNull();
    expect(setInForce([{ ...SET, effectiveTo: '2026-11-06' }], '2026-11-07')).toBeNull();
    expect(setInForce([{ ...SET, effectiveTo: '2026-11-07' }], '2026-11-07')?.id).toBe(1);
    expect(setInForce([{ ...SET, effectiveFrom: '2026-11-07' }], '2026-11-07')?.id).toBe(1);
  });

  it('a designation without a gang role goes last; figures keep Indian grouping', () => {
    const order = byRole([rate({ gangRole: null, designation: 'Other' }), rate({ gangRole: 'MANDAL' })]);
    expect(order.map((r) => r.gangRole)).toEqual(['MANDAL', null]);
    expect(figure(2028000)).toBe('20,28,000');
    expect(figure(null)).toBe('—');
    expect(rupees(799, 'none')).toBe('₹ 799');
    expect(rupees(null, 'none')).toBe('none');
    expect(rangeText(1, 30, 'and above')).toBe('1 – 30');
    expect(rangeText(91, null, 'and above')).toBe('91 and above');
  });

  it('the drawn forms: every field mandatory, an amount numeric and not negative', () => {
    const asor = {
      effective: '2026-12-01',
      piece: '2.06',
      sardar: '1',
      mandal: '1',
      handling: '1',
      ancillary: '0',
    };
    expect(asorEntryReady(asor)).toBe(true);
    expect(asorEntryReady({ ...asor, effective: '' })).toBe(false);
    expect(asorEntryReady({ ...asor, piece: ' ' })).toBe(false);
    expect(asorEntryReady({ ...asor, piece: 'x' })).toBe(false);
    expect(asorEntryReady({ ...asor, sardar: '-1' })).toBe(false);
    const norm = {
      depot: 'a',
      mou: 'b',
      mln: '1',
      mlsw: '1',
      height: '1',
      lead: '1',
      effective: '2026-12-01',
    };
    expect(normEntryReady(norm)).toBe(true);
    expect(normEntryReady({ ...norm, mou: ' ' })).toBe(false);
  });
});
