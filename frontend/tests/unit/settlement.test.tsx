import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FinalSettlement } from '@/modules/settlement';
import {
  awaitsNoDues,
  mayCompute,
  mayRelease,
  maySubmit,
  outstanding,
  stageNumber,
} from '@/modules/settlement/business/settlementRules';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * O-05 Full & Final Settlement (plan 11): the cases with their stage and awaited clearance, the case
 * with its checklist, components (gratuity apart) and recoveries; the actions offered by role and
 * stage, each disabled with its reason.
 */

afterEach(() => vi.unstubAllGlobals());

const SUMMARY = {
  separations: 0,
  settled: 0,
  settledAmount: 0,
  byType: [],
  awaitingClearance: 0,
  hqApproval: 0,
  netRecovery: 0,
};

const ROW = {
  caseId: 7,
  caseReference: 'FNF-2026-00007',
  eis: '104882',
  name: 'Rakesh Sharma',
  office: 'DH001 - Delhi Division',
  separationType: 'RETIREMENT',
  separationDate: '2026-09-30',
  stage: 'CLEARANCES_RAISED',
  awaiting: ['ESTABLISHMENT_NO_DUES'],
  status: 'INITIATED',
  netPayable: null,
  payrollType: 'STAFF',
  payrollDues: null,
  totalRecoveries: null,
  recoveredFromGratuity: null,
  awaitingChecker: false,
  hqApprovalPending: false,
};

const detail = (over: object = {}) => ({
  summary: ROW,
  settlementId: 3,
  clearances: [
    { type: 'LOANS_ADVANCES', status: 'RECEIVED', remark: 'Nil' },
    { type: 'ESTABLISHMENT_NO_DUES', status: 'RAISED', remark: null },
  ],
  pendingSalary: null,
  leaveEncashment: null,
  arrears: null,
  exGratia: null,
  gratuity: null,
  totalRecoveries: null,
  recoveredFromGratuity: null,
  netRecovery: false,
  recoveries: [],
  exGratiaEntries: [],
  cpfStatus: 'NOT_APPLIED',
  cpfAmount: null,
  benefitsHeld: true,
  invoiceStatus: 'NOT_RAISED',
  ...over,
});

describe('O-05 Full & Final Settlement', () => {
  it('SRS-10-11: the cases with their stage and the clearance each awaits; the tiles count them', async () => {
    backend({
      'GET /v1/settlement-cases/awaiting': () => [{ clearance: 'ESTABLISHMENT_NO_DUES', cases: 1 }],
      'GET /v1/settlement-cases/summary': () => SUMMARY,
      'GET /v1/settlement-cases/invoices': (url) => paged([], 0, url),
      'GET /v1/settlement-cases/7': () => detail(),
      'GET /v1/settlement-cases?': (url) =>
        paged(
          [
            ROW,
            {
              ...ROW,
              caseId: 8,
              caseReference: 'FNF-2026-00008',
              stage: 'APPROVED',
              status: 'APPROVED',
              awaiting: [],
              netPayable: 74500,
            },
          ],
          2,
          url,
        ),
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    const table = await screen.findByRole('table', { name: t('screen.O-05') });
    expect(await within(table).findByText('FNF-2026-00007')).toBeTruthy();
    // A computed case shows its net payable; one not yet computed a dash.
    const computed = within(table).getByText('FNF-2026-00008').closest('tr') as HTMLElement;
    expect(within(computed).getByText('74,500')).toBeTruthy();
    expect(within(table).getByText(t('o05.step', [2, t('o05.stepText.CLEARANCES_RAISED')]))).toBeTruthy();
    expect(within(table).getByText(t('o05.awaits', [t('o05.clearance.ESTABLISHMENT_NO_DUES')]))).toBeTruthy();
    expect(within(table).getAllByText('DH001 - Delhi Division')).toHaveLength(2);
    // SRS-10-11: the awaited counts stand behind the drawn Pending Clearances.
    await userEvent.click(screen.getByRole('button', { name: t('o05.w.pendingClearances') }));
    const dialog = await screen.findByRole('dialog', { name: t('o05.w.pendingClearances') });
    expect(await within(dialog).findByText(t('o05.clearance.ESTABLISHMENT_NO_DUES'))).toBeTruthy();
    expect(within(dialog).getByText('1')).toBeTruthy();
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('SRS-10-27 / 10-14: Establishment / HR records the no-dues; the Maker cannot compute while it is outstanding', async () => {
    const calls = backend({
      'GET /v1/settlement-cases/awaiting': () => [],
      'GET /v1/settlement-cases/summary': () => SUMMARY,
      'GET /v1/settlement-cases/invoices': (url) => paged([], 0, url),
      'GET /v1/settlement-cases?': (url) => paged([ROW], 1, url),
      'GET /v1/settlement-cases/7': () => detail(),
      'POST /v1/settlement-cases/7/no-dues': () => detail(),
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:CLEAR']));
    await userEvent.click(await screen.findByRole('button', { name: t('common.view') }));
    expect(await screen.findByText(t('o05.benefitsHeld'))).toBeTruthy();
    const compute = screen.getByRole('button', { name: t('action.o05.compute') });
    expect(compute).toBeDisabled();
    expect(compute.getAttribute('title')).toBe(t('o05.why.makerOnly'));
    const record = screen.getByRole('button', { name: t('action.o05.noDues') });
    expect(record).toBeDisabled();
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Nil');
    expect(record).toBeDisabled();
    await userEvent.type(screen.getByLabelText(t('o05.dues')), '0');
    await userEvent.click(record);
    expect(await screen.findByText(t('o05.noDuesRecorded'))).toBeTruthy();
    expect(sent(calls, 'POST', '/no-dues').at(-1)).toEqual({ dues: 0, remark: 'Nil' });
  });

  it('SRS-10-13 / 10-19: the Maker computes, sees gratuity apart and submits; a refusal is shown', async () => {
    const computed = detail({
      summary: { ...ROW, stage: 'COMPUTED', status: 'COMPUTED', netPayable: 74500, awaiting: [] },
      clearances: [{ type: 'ESTABLISHMENT_NO_DUES', status: 'RECEIVED', remark: 'Licence fee' }],
      pendingSalary: 0,
      leaveEncashment: 75000,
      totalRecoveries: 500,
      recoveries: [
        {
          id: 1,
          type: 'LICENCE_FEE',
          reference: 'NO_DUES',
          period: 'AT 2026-09-30',
          amount: 500,
          source: 'PAYROLL_DUES',
          gratuityReason: null,
          gratuityOrderRef: null,
          hqApprovalId: null,
        },
      ],
    });
    const calls = backend({
      'GET /v1/settlement-cases/awaiting': () => [],
      'GET /v1/settlement-cases/summary': () => SUMMARY,
      'GET /v1/settlement-cases/invoices': (url) => paged([], 0, url),
      'GET /v1/settlement-cases?': (url) => paged([ROW], 1, url),
      'GET /v1/settlement-cases/7': () => computed,
      'POST /v1/settlement-cases/7/computations': () => computed,
      'POST /v1/settlement-cases/7/submissions': () =>
        problem(409, 'FNF-409-APPROVAL', ['decision on the ex-gratia entry']),
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:COMPUTE']));
    await userEvent.click(await screen.findByRole('button', { name: t('common.view') }));
    expect(await screen.findByText(t('o05.gratuityBlocked'))).toBeTruthy();
    // SRS-10-12 (US-GAP-31): each component with its computing module, basis and payment channel.
    const components = screen.getByRole('table', { name: t('o05.components') });
    const encashment = components.querySelector('tr[data-component="LEAVE"]') as HTMLElement;
    expect(encashment.textContent).toContain(t('o05.st.LEAVE.by'));
    expect(encashment.textContent).toContain(t('o05.st.LEAVE.basis'));
    expect(encashment.textContent).toContain(t('o05.st.LEAVE.via'));
    expect(encashment.textContent).toContain('75,000');
    const gratuity = components.querySelector('tr[data-component="GRATUITY"]') as HTMLElement;
    expect(gratuity.textContent).toContain(t('o05.st.GRATUITY.via'));
    expect(components.querySelector('tr[data-component="CPF_FINAL"]')?.textContent).toContain(
      t('o05.st.CPF_FINAL.via'),
    );
    expect(components.querySelector('tr[data-component="NET"]')?.textContent).toContain(t('o05.st.NET'));
    expect(screen.getByRole('table', { name: t('o05.recoveries') })).toBeTruthy();
    expect(screen.getByText(t('o05.recovery.LICENCE_FEE'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('action.o05.compute') }));
    expect(await screen.findByText(t('o05.computed'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(
      await screen.findByText(t('error.FNF-409-APPROVAL', ['decision on the ex-gratia entry'])),
    ).toBeTruthy();
    expect(sent(calls, 'POST', '/submissions')).toHaveLength(1);
    const release = screen.getByRole('button', { name: t('action.o05.release') });
    expect(release).toBeDisabled();
    expect(release.getAttribute('title')).toBe(t('reason.financeOnly'));
  });

  it('SRS-10-6: Finance releases an approved settlement; a net recovery is flagged', async () => {
    const approved = detail({
      summary: { ...ROW, stage: 'APPROVED', status: 'APPROVED', netPayable: -25000, awaiting: [] },
      clearances: [],
      benefitsHeld: false,
      cpfStatus: 'SANCTIONED',
      cpfAmount: 120000,
    });
    const calls = backend({
      'GET /v1/settlement-cases/awaiting': () => [],
      'GET /v1/settlement-cases/summary': () => SUMMARY,
      'GET /v1/settlement-cases/invoices': (url) => paged([], 0, url),
      'GET /v1/settlement-cases?': (url) => paged([ROW], 1, url),
      'GET /v1/settlement-cases/7': () => approved,
      'POST /v1/settlement-cases/7/releases': () => ({
        status: 202,
        body: { approvalId: 9, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:RELEASE']));
    await userEvent.click(await screen.findByRole('button', { name: t('common.view') }));
    expect(await screen.findByText(t('o05.netRecovery'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('action.o05.release') }));
    expect(await screen.findByText(t('o05.releaseSent'))).toBeTruthy();
    expect(sent(calls, 'POST', '/releases')).toHaveLength(1);
  });

  it('SRS-10-12 variants: a missing CPF status, a recovery without reference, gratuity, the member filter and the reasons', async () => {
    const calls = backend({
      'GET /v1/settlement-cases/awaiting': () => [],
      'GET /v1/settlement-cases/summary': () => SUMMARY,
      'GET /v1/settlement-cases/invoices': (url) => paged([], 0, url),
      'GET /v1/settlement-cases?': (url) => paged([ROW], 1, url),
      'GET /v1/settlement-cases/7': () =>
        detail({
          summary: { ...ROW, stage: 'APPROVED', status: 'APPROVED', netPayable: 1000, awaiting: [] },
          clearances: [],
          benefitsHeld: false,
          cpfStatus: null,
          gratuity: 50000,
          recoveries: [
            {
              id: 2,
              type: 'LICENCE_FEE',
              reference: null,
              period: null,
              amount: 100,
              source: 'PAYROLL_DUES',
              gratuityReason: null,
              gratuityOrderRef: null,
              hqApprovalId: null,
            },
          ],
        }),
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:COMPUTE']));
    await userEvent.type(screen.getByLabelText(t('m01.eis')), '104882');
    expect(calls.some((c) => c.url.includes('eis=104882'))).toBe(true);
    await userEvent.click(await screen.findByRole('button', { name: t('common.view') }));
    const recoveries = await screen.findByRole('table', { name: t('o05.recoveries') });
    expect(within(recoveries).getAllByText('—').length).toBeGreaterThan(0);
    expect(screen.getAllByText(/50,000/).length).toBeGreaterThan(0);
    const compute = screen.getByRole('button', { name: t('action.o05.compute') });
    expect(compute.getAttribute('title')).toBe(t('o05.why.compute'));
    expect(screen.getByRole('button', { name: t('common.submitForApproval') }).getAttribute('title')).toBe(
      t('o05.why.submit'),
    );
  });

  it('an empty list says so', async () => {
    backend({
      'GET /v1/settlement-cases/awaiting': () => [],
      'GET /v1/settlement-cases/summary': () => SUMMARY,
      'GET /v1/settlement-cases/invoices': (url) => paged([], 0, url),
      'GET /v1/settlement-cases?': (url) => paged([], 0, url),
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    expect(await screen.findByText(t('o05.none'))).toBeTruthy();
  });
});

describe('O-05 rules', () => {
  it('SRS-10-8 / 10-14: stages in order and what each role may do when', () => {
    expect(stageNumber('SEPARATION_RECEIVED')).toBe(1);
    expect(stageNumber('CLOSED')).toBe(8);
    expect(outstanding('RAISED')).toBe(true);
    expect(outstanding('PENDING')).toBe(true);
    expect(outstanding('EXCLUDED')).toBe(false);
    const d = detail() as unknown as Parameters<typeof mayCompute>[0];
    // SRS-10-14 (US-GAP-218): a clearance outstanding does not hold the computation.
    expect(mayCompute(d)).toBe(true);
    expect(awaitsNoDues(d)).toBe(true);
    expect(maySubmit(d)).toBe(false);
    expect(mayRelease(d)).toBe(false);
    const received = detail({
      summary: { ...ROW, stage: 'CLEARANCES_RECEIVED' },
      clearances: [{ type: 'ESTABLISHMENT_NO_DUES', status: 'RECEIVED', remark: null }],
    }) as unknown as Parameters<typeof mayCompute>[0];
    expect(mayCompute(received)).toBe(true);
    expect(awaitsNoDues(received)).toBe(false);
    const computed = detail({
      summary: { ...ROW, stage: 'COMPUTED', status: 'COMPUTED' },
      clearances: [],
    }) as unknown as Parameters<typeof mayCompute>[0];
    expect(maySubmit(computed)).toBe(true);
    expect(mayCompute(computed)).toBe(true);
    const approved = detail({
      summary: { ...ROW, stage: 'APPROVED', status: 'APPROVED' },
    }) as unknown as Parameters<typeof mayCompute>[0];
    expect(mayRelease(approved)).toBe(true);
    expect(mayCompute(approved)).toBe(false);
  });
});
