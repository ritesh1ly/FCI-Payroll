import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LabourRates } from '@/modules/labour/components/LabourRates';
import type { RateDesignation } from '@/modules/labour/api/labourApi';
import {
  type DeptRateDraft,
  deptRateBlocked,
  type RateDraft,
  RATE_PAYROLL_TYPES,
  rateBlocked,
  toDeptRateRequest,
  toRateRequest,
} from '@/modules/labour/business/labourRules';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * M-05 Labour Rate Tables (plan 21 phase 21c; SRS-4-11, SRS-4-2, D21-4): the DPS designation-wise
 * MGW day rate sets with the Interim Relief, the GoI rate and its source and the comparison grain;
 * the Rule Administrator proposes a set with every designation's rate, the Rule Approver approves.
 */

afterEach(() => vi.unstubAllGlobals());

const label = (key: string, args: unknown[] = []) =>
  new RegExp(`^${t(key, args as string[]).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

const DESIGNATIONS: RateDesignation[] = [
  { id: 31, code: 'DSDR', name: 'Sardar (DPS)', gangRole: 'SARDAR' },
  { id: 32, code: 'DHL', name: 'Handling Labour (DPS)', gangRole: 'HANDLING' },
];

const SET = {
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
  rates: [
    { designationId: 31, designation: 'DSDR - Sardar (DPS)', gangRole: 'SARDAR', mgw: 799 },
    { designationId: 32, designation: 'DHL - Handling Labour (DPS)', gangRole: 'HANDLING', mgw: 781 },
  ],
};

function draft(over: Partial<RateDraft> = {}): RateDraft {
  return {
    effectiveFrom: '2026-11-01',
    mgw: { 31: '810', 32: '790.50' },
    interimRelief: '',
    goiRate: '',
    goiRateSource: '',
    comparisonGrain: '',
    reason: 'Circular 12/2026',
    ...over,
  };
}

describe('M-05 rules', () => {
  // SC-21-0011: every designation's day rate is mandatory; the settings are optional.
  it('SRS-4-11: every designation needs its day rate; a GoI rate needs its source', () => {
    expect(rateBlocked(draft(), DESIGNATIONS)).toBeNull();
    expect(rateBlocked(draft(), [])).toBe('m05.why.designations');
    expect(rateBlocked(draft({ effectiveFrom: '' }), DESIGNATIONS)).toBe('m05.why.effective');
    expect(rateBlocked(draft({ mgw: { 31: '810' } }), DESIGNATIONS)).toBe('m05.why.mgw');
    expect(rateBlocked(draft({ mgw: { 31: '0', 32: '1' } }), DESIGNATIONS)).toBe('m05.why.mgw');
    expect(rateBlocked(draft({ mgw: { 31: '8.123', 32: '1' } }), DESIGNATIONS)).toBe('m05.why.mgw');
    expect(rateBlocked(draft({ interimRelief: '-1' }), DESIGNATIONS)).toBe('m05.why.ir');
    expect(rateBlocked(draft({ interimRelief: '1.2.3' }), DESIGNATIONS)).toBe('m05.why.ir');
    expect(rateBlocked(draft({ interimRelief: '12.' }), DESIGNATIONS)).toBe('m05.why.ir');
    expect(rateBlocked(draft({ interimRelief: '0' }), DESIGNATIONS)).toBeNull();
    expect(rateBlocked(draft({ goiRate: '0' }), DESIGNATIONS)).toBe('m05.why.goi');
    expect(rateBlocked(draft({ goiRate: 'x' }), DESIGNATIONS)).toBe('m05.why.goi');
    expect(rateBlocked(draft({ goiRate: '900' }), DESIGNATIONS)).toBe('m05.why.goiSource');
    expect(rateBlocked(draft({ goiRate: '900', goiRateSource: 'GoI CLC 2026' }), DESIGNATIONS)).toBeNull();
    expect(rateBlocked(draft({ goiRateSource: 'x'.repeat(201) }), DESIGNATIONS)).toBe('m05.why.goiSource');
    expect(rateBlocked(draft({ goiRateSource: 'दर' }), DESIGNATIONS)).toBe('m05.why.goiSource');
    expect(rateBlocked(draft({ reason: ' ' }), DESIGNATIONS)).toBe('m05.why.reason');
    expect(
      toRateRequest(
        draft({ comparisonGrain: 'PER_DAY', goiRate: '900', goiRateSource: ' GoI ' }),
        'DPS_LABOUR',
        DESIGNATIONS,
      ),
    ).toEqual({
      payrollType: 'DPS_LABOUR',
      depotLocationId: null,
      effectiveFrom: '2026-11-01',
      rates: [
        { designationId: 31, mgw: 810 },
        { designationId: 32, mgw: 790.5 },
      ],
      interimRelief: null,
      goiRate: 900,
      goiRateSource: 'GoI',
      comparisonGrain: 'PER_DAY',
      reason: 'Circular 12/2026',
    });
    expect(
      toRateRequest(draft({ interimRelief: '25', mgw: {} }), 'DPS_LABOUR', DESIGNATIONS).interimRelief,
    ).toBe(25);
  });
});

describe('M-05 Labour Rate Tables', () => {
  it('SRS-4-11: the DPS set reads with settings not configured; the Rule Administrator proposes a new set', async () => {
    const calls = backend({
      'GET /v1/labour/rate-sets/designations': () => DESIGNATIONS,
      'GET /v1/labour/rate-sets?': (url) =>
        paged(
          [
            {
              ...SET,
              id: 9,
              versionNo: 2,
              effectiveFrom: '2026-11-01',
              approvalState: 'PENDING_APPROVAL',
              interimRelief: 25,
              goiRate: 900,
              goiRateSource: 'GoI CLC',
              comparisonGrain: 'PER_DAY',
              depot: 'DP-DL01 - Depot Okhla',
            },
            { ...SET, effectiveTo: '2026-10-31' },
          ],
          2,
          url,
        ),
      'POST /v1/labour/rate-sets': () => ({ status: 202, body: { id: 77, status: 'PENDING_APPROVAL' } }),
    });
    wrap(<LabourRates />, me(['M-05:VIEW', 'M-05:CREATE']));
    const table = await screen.findByRole('table', { name: t('screen.M-05') });
    expect(within(table).getAllByText('DSDR - Sardar (DPS): ₹ 799')).toHaveLength(2);
    expect(within(table).getAllByText(t('m05.notConfigured')).length).toBeGreaterThanOrEqual(3);
    expect(within(table).getByText('GoI CLC')).toBeTruthy();
    expect(within(table).getByText(t('m05.grain.PER_DAY'))).toBeTruthy();
    expect(within(table).getByText(/DP-DL01 - Depot Okhla/)).toBeTruthy();
    expect(within(table).getByText(/All depots|सभी डिपो/)).toBeTruthy();
    // SRS-20-29 (US-03-07 AC1): each set opens the change history of its series.
    expect(
      within(table).getByRole('button', { name: t('history.for', ['DP-DL01 - Depot Okhla']) }),
    ).toBeTruthy();
    expect(within(table).getByRole('button', { name: t('history.for', [t('m05.allDepots')]) })).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('action.m05.propose') }));
    const send = screen.getByRole('button', { name: t('action.m05.send') });
    expect(send.getAttribute('title')).toBe(t('m05.why.mgw'));
    fireEvent.change(screen.getByLabelText(label('m12.effective')), { target: { value: '2026-12-01' } });
    await userEvent.type(screen.getByLabelText(label('m05.mgwOf', ['DSDR - Sardar (DPS)'])), '815');
    await userEvent.type(screen.getByLabelText(label('m05.mgwOf', ['DHL - Handling Labour (DPS)'])), '795');
    await userEvent.type(screen.getByLabelText(label('m05.interimRelief')), '30');
    await userEvent.type(screen.getByLabelText(label('m05.goiRate')), '905');
    expect(send.getAttribute('title')).toBe(t('m05.why.goiSource'));
    await userEvent.type(screen.getByLabelText(label('m05.goiRateSource')), 'GoI CLC 2026');
    await userEvent.selectOptions(
      screen.getByLabelText(label('m05.grain'), { selector: 'select' }),
      'PER_PERIOD',
    );
    await userEvent.type(screen.getByLabelText(label('m02.reason')), 'Circular 12/2026');
    expect(send).toBeEnabled();
    await userEvent.click(send);
    expect(await screen.findByText(t('m05.proposed'))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/labour/rate-sets').at(-1)).toMatchObject({
      payrollType: 'DPS_LABOUR',
      effectiveFrom: '2026-12-01',
      rates: [
        { designationId: 31, mgw: 815 },
        { designationId: 32, mgw: 795 },
      ],
      interimRelief: 30,
      goiRate: 905,
      goiRateSource: 'GoI CLC 2026',
      comparisonGrain: 'PER_PERIOD',
    });
    expect(screen.queryByRole('button', { name: t('action.m05.send') })).toBeNull();
  });

  it('SRS-4-11: a refused set is shown and kept; cancel closes it; a viewer cannot propose', async () => {
    backend({
      'GET /v1/labour/rate-sets/designations': () => DESIGNATIONS,
      'GET /v1/labour/rate-sets?': (url) => paged([], 0, url),
      'POST /v1/labour/rate-sets': () => problem(422, 'LBR-422-RATE', ['EFFECTIVE_BEFORE_LATEST']),
    });
    const view = wrap(<LabourRates />, me(['M-05:VIEW', 'M-05:CREATE']));
    expect(await screen.findByText(t('m05.none'))).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText(label('m02.payrollType')), 'DPS_LABOUR');
    await userEvent.click(screen.getByRole('button', { name: t('action.m05.propose') }));
    await userEvent.type(screen.getByLabelText(label('m05.mgwOf', ['DSDR - Sardar (DPS)'])), '815');
    await userEvent.type(screen.getByLabelText(label('m05.mgwOf', ['DHL - Handling Labour (DPS)'])), '795');
    await userEvent.type(screen.getByLabelText(label('m02.reason')), 'Revision');
    await userEvent.click(screen.getByRole('button', { name: t('action.m05.send') }));
    expect(await screen.findByText(t('error.LBR-422-RATE', ['EFFECTIVE_BEFORE_LATEST']))).toBeTruthy();
    expect(screen.getByRole('button', { name: t('action.m05.send') })).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('button', { name: t('action.m05.send') })).toBeNull();
    view.unmount();
    backend({
      'GET /v1/labour/rate-sets/designations': () => DESIGNATIONS,
      'GET /v1/labour/rate-sets?': (url) => paged([], 0, url),
    });
    wrap(<LabourRates />, me(['M-05:VIEW']));
    const propose = await screen.findByRole('button', { name: t('action.m05.propose') });
    expect(propose).toBeDisabled();
    expect(propose.getAttribute('title')).toBe(t('reason.ruleAdminOnly'));
  });
});

const DEPT_DESIGNATIONS: RateDesignation[] = [
  { id: 41, code: 'SDR', name: 'Sardar', gangRole: 'SARDAR' },
  { id: 42, code: 'ANC', name: 'Ancillary Labour', gangRole: 'ANCILLARY' },
];

function deptDraft(over: Partial<DeptRateDraft> = {}): DeptRateDraft {
  return {
    effectiveFrom: '2026-11-01',
    basic: { 41: '520', 42: '500.50' },
    da: { 41: '110', 42: '0' },
    reason: 'Wage revision order',
    ...over,
  };
}

describe('M-05 Departmental Basic wage and DA (SRS-3-13, US-GAP-61)', () => {
  it('SRS-3-13: M-05 holds the Departmental sets beside DPS and NWNP', () => {
    expect(RATE_PAYROLL_TYPES).toEqual(['DPS_LABOUR', 'DEPT_LABOUR', 'NWNP_LABOUR']);
  });

  it('SRS-3-13: every designation needs a positive Basic wage and a DA of 0 or more', () => {
    expect(deptRateBlocked(deptDraft(), DEPT_DESIGNATIONS)).toBeNull();
    expect(deptRateBlocked(deptDraft(), [])).toBe('m05.why.designations');
    expect(deptRateBlocked(deptDraft({ effectiveFrom: '' }), DEPT_DESIGNATIONS)).toBe('m05.why.effective');
    expect(deptRateBlocked(deptDraft({ basic: { 41: '520' } }), DEPT_DESIGNATIONS)).toBe('m05.why.basic');
    expect(deptRateBlocked(deptDraft({ basic: { 41: '0', 42: '1' } }), DEPT_DESIGNATIONS)).toBe(
      'm05.why.basic',
    );
    expect(deptRateBlocked(deptDraft({ basic: { 41: '1.123', 42: '1' } }), DEPT_DESIGNATIONS)).toBe(
      'm05.why.basic',
    );
    expect(deptRateBlocked(deptDraft({ da: { 41: '1' } }), DEPT_DESIGNATIONS)).toBe('m05.why.da');
    expect(deptRateBlocked(deptDraft({ da: { 41: '-1', 42: '1' } }), DEPT_DESIGNATIONS)).toBe('m05.why.da');
    expect(deptRateBlocked(deptDraft({ da: { 41: '0', 42: '0.01' } }), DEPT_DESIGNATIONS)).toBeNull();
    expect(deptRateBlocked(deptDraft({ reason: ' ' }), DEPT_DESIGNATIONS)).toBe('m05.why.reason');
    expect(toDeptRateRequest(deptDraft({ reason: ' Order 7 ' }), DEPT_DESIGNATIONS)).toEqual({
      payrollType: 'DEPT_LABOUR',
      depotLocationId: null,
      effectiveFrom: '2026-11-01',
      rates: [
        { designationId: 41, basicWage: 520, da: 110 },
        { designationId: 42, basicWage: 500.5, da: 0 },
      ],
      interimRelief: null,
      goiRate: null,
      goiRateSource: null,
      comparisonGrain: null,
      reason: 'Order 7',
    });
  });

  it('SRS-3-13: the Departmental sets read with their Basic wage and DA; the Rule Administrator proposes one', async () => {
    const calls = backend({
      'GET /v1/labour/rate-sets/designations': () => DEPT_DESIGNATIONS,
      'GET /v1/labour/rate-sets?': (url) =>
        url.includes('DEPT_LABOUR')
          ? paged(
              [
                {
                  ...SET,
                  id: 12,
                  payrollType: 'DEPT_LABOUR',
                  rates: [
                    {
                      designationId: 41,
                      designation: 'SDR - Sardar',
                      gangRole: 'SARDAR',
                      mgw: null,
                      basicWage: 520,
                      da: 110,
                    },
                    {
                      designationId: 42,
                      designation: 'ANC - Ancillary Labour',
                      gangRole: 'ANCILLARY',
                      mgw: null,
                      basicWage: 500,
                      da: null,
                    },
                  ],
                },
              ],
              1,
              url,
            )
          : paged([], 0, url),
      'POST /v1/labour/rate-sets': () => ({ status: 202, body: { id: 78, status: 'PENDING_APPROVAL' } }),
    });
    wrap(<LabourRates />, me(['M-05:VIEW', 'M-05:CREATE']));
    expect(await screen.findByText(t('m05.none'))).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText(label('m02.payrollType')), 'DEPT_LABOUR');
    const table = await screen.findByRole('table', { name: t('screen.M-05') });
    expect(within(table).getByText('SDR - Sardar: ₹ 520')).toBeTruthy();
    expect(within(table).getByText('SDR - Sardar: ₹ 110')).toBeTruthy();
    expect(within(table).getByText('ANC - Ancillary Labour: —')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('action.m05.propose') }));
    const send = screen.getByRole('button', { name: t('action.m05.send') });
    expect(send.getAttribute('title')).toBe(t('m05.why.basic'));
    fireEvent.change(screen.getByLabelText(label('m12.effective')), { target: { value: '2026-12-01' } });
    await userEvent.type(screen.getByLabelText(label('m05.basicWageOf', ['SDR - Sardar'])), '530');
    await userEvent.type(screen.getByLabelText(label('m05.daOf', ['SDR - Sardar'])), '115');
    await userEvent.type(screen.getByLabelText(label('m05.basicWageOf', ['ANC - Ancillary Labour'])), '505');
    expect(send.getAttribute('title')).toBe(t('m05.why.da'));
    await userEvent.type(screen.getByLabelText(label('m05.daOf', ['ANC - Ancillary Labour'])), '0');
    await userEvent.type(screen.getByLabelText(label('m02.reason')), 'Wage revision');
    expect(send).toBeEnabled();
    await userEvent.click(send);
    expect(await screen.findByText(t('m05.proposed'))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/labour/rate-sets').at(-1)).toMatchObject({
      payrollType: 'DEPT_LABOUR',
      effectiveFrom: '2026-12-01',
      rates: [
        { designationId: 41, basicWage: 530, da: 115 },
        { designationId: 42, basicWage: 505, da: 0 },
      ],
      interimRelief: null,
    });
  });

  it('SRS-3-13: no Departmental set is seeded; a refusal is shown; a viewer cannot propose', async () => {
    backend({
      'GET /v1/labour/rate-sets/designations': () => DEPT_DESIGNATIONS,
      'GET /v1/labour/rate-sets?': (url) => paged([], 0, url),
      'POST /v1/labour/rate-sets': () => problem(422, 'LBR-422-RATE', ['EFFECTIVE_BEFORE_LATEST']),
    });
    const view = wrap(<LabourRates />, me(['M-05:VIEW', 'M-05:CREATE']));
    await userEvent.selectOptions(await screen.findByLabelText(label('m02.payrollType')), 'DEPT_LABOUR');
    expect(await screen.findByText(t('m05.deptNone'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('action.m05.propose') }));
    for (const x of DEPT_DESIGNATIONS) {
      await userEvent.type(screen.getByLabelText(label('m05.basicWageOf', [`${x.code} - ${x.name}`])), '500');
      await userEvent.type(screen.getByLabelText(label('m05.daOf', [`${x.code} - ${x.name}`])), '100');
    }
    await userEvent.type(screen.getByLabelText(label('m02.reason')), 'Revision');
    await userEvent.click(screen.getByRole('button', { name: t('action.m05.send') }));
    expect(await screen.findByText(t('error.LBR-422-RATE', ['EFFECTIVE_BEFORE_LATEST']))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('button', { name: t('action.m05.send') })).toBeNull();
    view.unmount();
    backend({
      'GET /v1/labour/rate-sets/designations': () => DEPT_DESIGNATIONS,
      'GET /v1/labour/rate-sets?': (url) => paged([], 0, url),
    });
    wrap(<LabourRates />, me(['M-05:VIEW']));
    await userEvent.selectOptions(await screen.findByLabelText(label('m02.payrollType')), 'DEPT_LABOUR');
    const propose = await screen.findByRole('button', { name: t('action.m05.propose') });
    expect(propose).toBeDisabled();
    expect(propose.getAttribute('title')).toBe(t('reason.ruleAdminOnly'));
  });
});
