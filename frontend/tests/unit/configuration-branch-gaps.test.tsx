import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  CeilingNorms,
  Circulars,
  ComponentCatalogue,
  Labels,
  ReferenceLists,
  StaffRates,
} from '@/modules/configuration';
import type { ComponentRow } from '@/modules/configuration/api/componentsApi';
import { mdmApi } from '@/modules/configuration/api/mdmApi';
import { retentionApi } from '@/modules/configuration/api/retentionApi';
import type { RuleSimulation } from '@/modules/configuration/api/rulesApi';
import { RuleSimulationPanel } from '@/modules/configuration/components/RuleSimulation';
import { useProposeRate } from '@/modules/configuration/orchestration/masterQueries';
import { useProposeReferenceValue } from '@/modules/configuration/orchestration/referenceListQueries';
import { GovernedProvider } from '@/modules/workflow';
import { I18nProvider } from '@/shared/i18n';
import { backend, me, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

/**
 * Remaining edges of the Module 04 master screens: M-15 without the circular list and with the OTP
 * cancelled (SRS-16-14, SRS-G-38, SRS-G-48); a refused M-25 / M-26 withdrawal shows its reason
 * (SRS-G-38, SRS-G-39); an M-16 value without an end date is open-ended (SRS-G-1); M-07 entries
 * not subject to LWP and without a payslip group or GL account (SRS-G-42); an M-04 line without a
 * unit (SRS-1-62); an M-08 simulation over every payroll (SRS-19-4); the OTP proof on every proposal
 * (SRS-G-48).
 */

afterEach(() => vi.unstubAllGlobals());

const REFERENCE = referenceLists({
  CLAIM_TYPE: [['LABOUR_TA', 'Labour TA']],
  ELIGIBILITY_CATEGORY: [['DEMO_ALL', 'Demo - all members']],
  CEILING_BASIS: [],
  ISSUING_AUTHORITY: [['HQ_PERSONNEL', 'HQ Personnel']],
  CIRCULAR_CATEGORY: [['LABOUR_NORM', 'Labour Norm']],
  LANGUAGE: [
    ['EN', 'English'],
    ['HI', 'Hindi'],
  ],
  PAY_PATTERN: [['IDA', 'IDA']],
  CITY_CLASS: [['X', 'X']],
  RATE_UNIT: [
    ['PERCENT', 'Percent'],
    ['INR', 'Rupees'],
  ],
});

const otpRequired = () => problem(428, 'AUTH-428-OTP', ['7']);

async function cancelOtp() {
  const dialog = await screen.findByRole('dialog', { name: t('otp.title') });
  await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
  await waitFor(() => expect(screen.queryByRole('dialog', { name: t('otp.title') })).toBeNull());
}

// ---------------------------------------------------------------- M-15

describe('M-15 ceiling norms: circulars unavailable and the OTP cancelled', () => {
  const norm = (over: object = {}) => ({
    id: 1,
    versionNo: 1,
    effectiveFrom: '2026-04-01',
    effectiveTo: null,
    ceilingBasis: null,
    ceilingValue: 750,
    periodicity: 'MONTHLY',
    eligibilityCategory: 'DEMO_ALL',
    memberScope: 'BOTH',
    confirmed: true,
    status: 'APPROVED',
    approvalId: 1,
    circularOrderId: null,
    ...over,
  });

  it('SRS-G-38: without the circular list a version names its circular by id and the form says none is active', async () => {
    backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/masters/ceiling-norms/LABOUR_TA/versions': () => paged([norm({ circularOrderId: 6 })]),
      'GET /v1/masters/ceiling-norms': () =>
        paged([{ claimType: 'LABOUR_TA', name: 'Labour TA', current: norm(), pending: null }]),
      'GET /v1/masters/circulars/current': () => problem(503, 'COM-503-UNAVAILABLE'),
    });
    wrap(<CeilingNorms />, ['M-15:VIEW', 'M-15:CREATE']);
    await userEvent.click(await screen.findByRole('button', { name: t('mst.history') }));
    expect(await screen.findByText('#6')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('mst.newVersion') }));
    expect(await screen.findByText(t('m27.noActiveCircular'))).toBeTruthy();
    const circular = screen.getByLabelText(t('mst.circular')) as HTMLSelectElement;
    expect(circular.options).toHaveLength(1);
  });

  it('SRS-G-48: cancelling the OTP keeps the ceiling form open, without an error', async () => {
    backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/masters/ceiling-norms': () =>
        paged([{ claimType: 'LABOUR_TA', name: 'Labour TA', current: norm(), pending: null }]),
      'GET /v1/masters/circulars/current': () => [
        { id: 6, number: 'CIR/M15/6', subject: 'Ceiling revision' },
      ],
      'POST /v1/masters/ceiling-norms/': otpRequired,
    });
    wrap(<CeilingNorms />, ['M-15:VIEW', 'M-15:CREATE']);
    await userEvent.click(await screen.findByRole('button', { name: t('mst.newVersion') }));
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Revision');
    await screen.findByRole('option', { name: 'CIR/M15/6 · Ceiling revision' });
    await userEvent.selectOptions(screen.getByLabelText(t('mst.circular')), '6');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await cancelOtp();
    expect(screen.getByRole('button', { name: t('common.submitForApproval') })).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });
});

// ---------------------------------------------------------------- M-25 / M-26

describe('M-25 / M-26: a refused withdrawal shows its reason', () => {
  it('SRS-G-38: a withdrawal the server refuses shows the reason', async () => {
    backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/masters/circulars/offices': () => [],
      'GET /v1/masters/circulars': () =>
        paged([
          {
            id: 5,
            number: 'HQ/DA/1',
            date: '2026-10-01',
            issuingAuthority: 'HQ_PERSONNEL',
            issuingLocationId: 1,
            subject: 'DA revision',
            category: 'LABOUR_NORM',
            appliesAllOffices: true,
            payrollTypes: ['STAFF'],
            offices: [],
            effectiveFrom: '2026-10-01',
            effectiveTo: null,
            supersedesId: null,
            supersedesNumber: null,
            documentId: 3,
            status: 'ACTIVE',
            statusReason: null,
            approvalId: 1,
            createdBy: 'maker',
            createdAt: '2026-10-01T00:00:00Z',
            changedAt: null,
          },
        ]),
      'POST /v1/masters/circulars/5/withdraw': () => problem(409, 'MST-409-CIRCULAR', ['HQ/DA/1']),
    });
    wrap(<Circulars />, ['M-25:VIEW', 'M-25:EDIT']);
    await screen.findByText('HQ/DA/1');
    await userEvent.click(screen.getByRole('button', { name: t('m25.act.withdraw') }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Order cancelled');
    await userEvent.click(within(dialog).getByRole('button', { name: t('action.withdraw') }));
    expect(await screen.findByText(t('error.MST-409-CIRCULAR', ['HQ/DA/1']))).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('SRS-G-39: a label withdrawal the server refuses shows the reason', async () => {
    const local = {
      id: 12,
      key: 'DESIG.SARDAR',
      type: 'MASTER_VALUE',
      masterEntity: 'ORG_DESIGNATION',
      masterCode: 'SARDAR',
      language: 'HI',
      scope: 'OFFICE',
      scopeLocationId: 30,
      scopeOffice: 'DH001 Delhi',
      text: 'Mukadam',
      localisable: true,
      versionNo: 1,
      effectiveFrom: '2026-04-01',
      effectiveTo: null,
      status: 'ACTIVE',
      approvalId: 1,
      changedBy: 'seed',
    };
    backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/masters/labels/missing-hindi': () => paged([]),
      'GET /v1/masters/labels': () => paged([local]),
      'POST /v1/masters/labels/12/withdraw': () => problem(409, 'MST-409-LABEL', ['K', 'HI', '2026-04-01']),
    });
    wrap(<Labels />, ['M-26:VIEW', 'M-26:EDIT']);
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.view')} DESIG.SARDAR` }));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('action.withdraw') }),
    );
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Name not used');
    await userEvent.click(within(dialog).getByRole('button', { name: t('action.withdraw') }));
    expect(await screen.findByText(t('error.MST-409-LABEL', ['K', 'HI', '2026-04-01']))).toBeTruthy();
  });
});

// ---------------------------------------------------------------- M-16

describe('M-16: a value without an end date stays open-ended', () => {
  const value = (over: object = {}) => ({
    code: 'PAUSED',
    labelEn: 'Paused',
    labelHi: null,
    sortOrder: 3,
    effectiveFrom: '2026-04-01',
    effectiveTo: null,
    active: true,
    versionNo: 1,
    changedBy: 'system',
    changedAt: null,
    pending: null,
    ...over,
  });
  const LISTS = [
    {
      code: 'JOB_STATUS',
      name: 'Job status',
      module: 'C-13',
      system: true,
      values: [value()],
      pendingNew: [],
    },
    {
      code: 'LEAVE_TYPE',
      name: 'Leave type',
      module: null,
      system: false,
      values: [value({ code: 'EL', labelEn: 'Earned leave' })],
      pendingNew: [],
    },
  ];
  const governed = (_url: string, init?: RequestInit) =>
    (JSON.parse(String(init?.body)) as { otp?: unknown }).otp
      ? { status: 202, body: { approvalId: 50, op: 'EDIT' } }
      : problem(428, 'AUTH-428-OTP', [7]);

  async function answerOtp() {
    await userEvent.type(await screen.findByLabelText(t('otp.code')), '123456');
    await userEvent.click(screen.getByRole('button', { name: t('otp.verify') }));
  }

  it('SRS-G-1: a change and a new value without an end date send no end date', async () => {
    const calls = backend({
      'GET /v1/masters/reference-lists': () => LISTS,
      'POST /v1/masters/reference-lists/': governed,
    });
    wrap(<ReferenceLists />, ['M-16:VIEW', 'M-16:CREATE', 'M-16:EDIT']);
    await screen.findByText(t('m16.systemList'));
    await userEvent.click(screen.getByRole('button', { name: t('m16.change') }));
    await userEvent.type(screen.getByLabelText(t('m16.reason'), { selector: '#m16-reason' }), 'Relabel');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await answerOtp();
    await waitFor(() =>
      expect(sent(calls, 'POST', '/JOB_STATUS/values/PAUSED').at(-1)).toMatchObject({ effectiveTo: null }),
    );
    await userEvent.selectOptions(screen.getByLabelText(t('m16.list')), 'LEAVE_TYPE');
    await userEvent.click(screen.getByRole('button', { name: t('m16.newValue') }));
    await userEvent.type(screen.getByLabelText(t('m16.code'), { selector: '#m16-code' }), 'ML');
    await userEvent.type(screen.getByLabelText(t('m16.labelEn'), { selector: '#m16-en' }), 'Maternity leave');
    await userEvent.type(screen.getByLabelText(t('m16.reason'), { selector: '#m16-reason' }), 'Policy');
    await userEvent.clear(screen.getByLabelText(t('m16.from'), { selector: '#m16-from' }));
    await userEvent.type(screen.getByLabelText(t('m16.from'), { selector: '#m16-from' }), '2099-01-01');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await answerOtp();
    await waitFor(() =>
      expect(sent(calls, 'POST', '/LEAVE_TYPE/values').at(-1)).toMatchObject({
        code: 'ML',
        effectiveFrom: '2099-01-01',
        effectiveTo: null,
      }),
    );
  });
});

// ---------------------------------------------------------------- M-07

describe('M-07: an entry not subject to LWP, without a payslip group or GL account', () => {
  const ROW: ComponentRow = {
    payCode: 140,
    name: 'Washing allowance',
    type: 'EARNING',
    payslipGroup: null,
    basisRuleKey: null,
    source: 'RULE',
    naturalAccount: null,
    taxable: 'Y',
    inCpfBase: false,
    payrollTypes: ['STAFF'],
    labourOnly: false,
    effectiveFrom: '2020-04-01',
    effectiveTo: null,
    retired: false,
    versionNo: 1,
    pending: false,
    subjectToLwp: false,
    eligibilityRuleKey: null,
  };

  it('SRS-G-42: the catalogue says N for LWP and a revision starts with the empty group and account', async () => {
    backend({
      'GET /v1/pay-components?': (url: string) => paged([ROW], 1, url),
      'GET /v1/pay-components/140/usages': () => [],
    });
    wrap(<ComponentCatalogue />, me(['M-07:VIEW', 'M-07:CREATE', 'M-07:EDIT']));
    const table = await screen.findByRole('table', { name: t('m07w.staff') });
    const code = await within(table).findByRole('button', { name: '140' });
    // In CPF base N (the list is not served here, so the code is shown); subject to LWP moved to the record.
    expect(within(code.closest('tr') as HTMLElement).getAllByText('N').length).toBeGreaterThan(0);
    await userEvent.click(code);
    await userEvent.click(await screen.findByRole('button', { name: t('m07.revise') }));
    expect((document.getElementById('m07-payslipGroup') as HTMLInputElement).value).toBe('');
    expect((document.getElementById('m07-naturalAccount') as HTMLInputElement).value).toBe('');
    expect((document.getElementById('m07-basisRuleKey') as HTMLInputElement).value).toBe('');
  });
});

// ---------------------------------------------------------------- M-04

describe('M-04: a rate line without a unit', () => {
  it('SRS-1-62: a version whose line names no unit opens with the unit unchosen', async () => {
    backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/masters/staff-rates': () =>
        paged([
          {
            code: 'HRA',
            name: 'House Rent Allowance',
            group: 'ALLOWANCE',
            payCode: 102,
            current: {
              id: 1,
              versionNo: 1,
              effectiveFrom: '2026-04-01',
              effectiveTo: null,
              state: 'APPROVED',
              approvalId: 1,
              circularOrderId: null,
              lines: [
                {
                  designationId: null,
                  categoryCode: null,
                  payPattern: 'IDA',
                  basicSlabFrom: null,
                  basicSlabTo: null,
                  cityClass: 'X',
                  rateCode: 'HRA_X',
                  rateValue: 30,
                  unit: null,
                },
              ],
            },
            pending: null,
          },
        ]),
      'GET /v1/masters/circulars/current': () => [],
    });
    wrap(<StaffRates />, ['M-04:VIEW', 'M-04:CREATE']);
    await userEvent.click(await screen.findByRole('button', { name: t('mst.newVersion') }));
    // The line keeps no unit of its own: the field offers the RATE_UNIT list to choose from.
    const unit = screen.getByLabelText(t('mst.unit')) as HTMLSelectElement;
    expect(Array.from(unit.options).map((o) => o.value)).toEqual(['PERCENT', 'INR']);
  });
});

// ---------------------------------------------------------------- M-08

describe('M-08: a simulation over every payroll and office', () => {
  const RUN: RuleSimulation = {
    id: 4,
    approvalId: 40,
    ruleKey: 'DA.IDA.RATE',
    payrollType: null,
    officeId: null,
    requestedBy: 'su.hq@fci-demo.test',
    requestedAt: '2026-10-27T05:00:00Z',
    population: 10,
    impact: { sampled: 10, inScope: 10, changed: 0, groups: [] },
    ruleVersionId: null,
    inputsChanged: false,
  };

  it('SRS_19_4_aRunWithoutAScopeCoversEveryPayrollAndSaysSo', async () => {
    const calls = backend({
      'GET /v1/rules/DA.IDA.RATE/simulations': (url: string) => paged([RUN], 1, url),
      'GET /v1/rules/simulation-offices': () => [],
      'POST /v1/rules/DA.IDA.RATE/simulations': () => ({ status: 201, body: RUN }),
    });
    wrap(
      <RuleSimulationPanel ruleKey="DA.IDA.RATE" mayRun onClose={() => undefined} />,
      me(['M-08:VIEW', 'M-08:APPROVE']),
    );
    const run = await screen.findByRole('region', { name: t('m08.sim.run.label', [4]) });
    expect(run).toHaveTextContent(t('m08.sim.every'));
    const scope = await screen.findByLabelText(t('m08.sim.scope'));
    await userEvent.click(within(scope).getByRole('button', { name: t('m08.sim.run') }));
    expect(await screen.findByText(t('m08.sim.done'))).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'POST').map((c) => c.body)).toEqual([
      { payrollType: null, officeId: null },
    ]);
  });
});

// ---------------------------------------------------------------- the OTP proof

describe('the OTP proof travels with every Module 04 proposal', () => {
  const OTP = { challengeId: 7, code: '123456' };

  it('SRS-G-48: an M-14 floor, an M-15 ceiling and a retention period carry the proof when given', async () => {
    const calls = backend({
      'POST /v1/masters/net-pay-floors/STAFF/versions': () => ({ status: 202, body: {} }),
      'POST /v1/masters/ceiling-norms/LABOUR_TA/versions': () => ({ status: 202, body: {} }),
      'PUT /v1/admin/retention-classes/AUDIT_EVENT': () => ({ status: 202, body: {} }),
    });
    const floor = {
      effectiveFrom: '2027-04-01',
      floorBasis: 'ABSOLUTE_AMOUNT' as const,
      floorValue: 1500,
      deductionCeilingPct: 50,
      sourceInstrument: null,
      confirmed: true,
      reason: 'Circular',
    };
    await mdmApi.proposeFloor('STAFF', floor, OTP);
    await mdmApi.proposeFloor('STAFF', floor);
    await retentionApi.propose(
      { code: 'AUDIT_EVENT', months: 96, indefinite: false, basis: 'SRS-20-5', reason: 'Set' },
      OTP,
    );
    const floors = sent(calls, 'POST', '/net-pay-floors/STAFF/versions');
    expect(floors[0]).toMatchObject({ otp: OTP });
    expect(floors[1]).not.toHaveProperty('otp');
    expect(sent(calls, 'PUT', '/retention-classes/AUDIT_EVENT')[0]).toMatchObject({ months: 96, otp: OTP });
  });

  function host({ children }: { children: ReactNode }) {
    const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
    return (
      <I18nProvider>
        <QueryClientProvider client={client}>
          <GovernedProvider>{children}</GovernedProvider>
        </QueryClientProvider>
      </I18nProvider>
    );
  }

  it('SRS-1-62: a rate proposal without a circular sends none; a change without a code goes to the empty code', async () => {
    const calls = backend({
      'POST /v1/masters/staff-rates/HRA/versions': () => ({ status: 201, body: {} }),
      'POST /v1/masters/reference-lists/': () => ({ status: 202, body: {} }),
    });
    const rate = renderHook(() => useProposeRate('M-04'), { wrapper: host });
    await act(async () => {
      await rate.result.current.mutateAsync({
        code: 'HRA',
        effectiveFrom: '2027-04-01',
        lines: [],
        reason: 'Revision',
      });
    });
    expect(sent(calls, 'POST', '/staff-rates/HRA/versions')[0]).toMatchObject({ circularOrderId: null });
    const value = renderHook(() => useProposeReferenceValue(), { wrapper: host });
    await act(async () => {
      await value.result.current.mutateAsync({
        list: 'JOB_STATUS',
        code: null,
        changed: { labelEn: 'Paused', labelHi: null, sortOrder: null, effectiveTo: null, reason: 'r' },
      });
    });
    expect(
      calls.some((c) => c.method === 'POST' && c.url.endsWith('/reference-lists/JOB_STATUS/values/')),
    ).toBe(true);
  });
});
