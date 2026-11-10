import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  DeputationAdjustment,
  InterestRates,
  InterestRun,
  OpeningBalanceCorrection,
  ParentZoneChange,
  StatutoryRates,
  SupplementaryPayment,
} from '@/modules/cpf';
import { CpfReconciliationReports, CpfReports } from '@/modules/reporting';
import type {
  CorrectionLine,
  DeputedRow,
  RegisterRule,
  SupplementaryRow,
  ZoneChangeDetail,
  ZoneChangeRow,
} from '@/modules/cpf/api/cpfScreensApi';
import { backend, me, paged, sent, t, wrap } from './support/harness';

/**
 * The CPF / PF & VPF screens as Wireframes v6.1 draws them (OD-121): the drawn tables on their reads, the actions each
 * role sees, and that no action pretends to a function that is not built (I-12, I-13, O-07, O-08) or to a right the role
 * does not hold (SRS-6-27, SRS-6-43, SRS-6-45, SRS-6-47, SRS-6-48, SRS-6-49, BR-CPF-036).
 */
afterEach(() => vi.unstubAllGlobals());

const MAKER = me(['I-09:VIEW', 'I-22:VIEW', 'C-12:VIEW'], { roles: ['PAYROLL_MAKER'] });
const CHECKER = me(['I-09:VIEW', 'I-22:VIEW', 'C-12:VIEW', 'M-18:VIEW', 'M-19:VIEW'], {
  roles: ['PAYROLL_CHECKER'],
});
const ADMIN = me(['M-18:VIEW', 'M-19:VIEW'], { roles: ['SYSTEM_ADMINISTRATOR'] });
const FINANCE = me(['I-09:VIEW'], { roles: ['FINANCE'] });

const DEPUTED: DeputedRow[] = [
  {
    eis: '871902',
    name: 'H. Kumar',
    organisation: 'Ministry of Consumer Affairs',
    from: '2026-04-01',
    to: '2028-03-31',
    outstanding: 202800,
    instalment: 16900,
    balanceAfter: null,
    contribution: 9412,
    status: 'PENDING_CHECKER',
  },
  {
    eis: '871901',
    name: 'K. Bose',
    organisation: null,
    from: '2026-08-01',
    to: null,
    outstanding: 60000,
    instalment: null,
    balanceAfter: null,
    contribution: null,
    status: 'AWAITING_ADVICE',
  },
];

const i09 = {
  'GET /v1/cpf/deputations/board': () => ({
    members: 2,
    withAdvance: 2,
    recovered: 19200,
    organisations: 1,
    contributions: 29264,
    pending: 1,
  }),
  'GET /v1/cpf/deputations/members': () => paged(DEPUTED),
  'GET /v1/cpf/deputations/871902': () => ({
    eis: '871902',
    name: 'H. Kumar',
    deputationFrom: '2026-04-01',
    deputationTo: null,
    advances: [],
    monthsWithoutReceipt: [],
    entries: [],
  }),
  'GET /v1/document-types': () => [],
};

describe('I-09 as drawn', () => {
  it('SRS_6_27_theDeputedMembersAndTheirRemittancesAreRead', async () => {
    backend(i09);
    wrap(<DeputationAdjustment />, MAKER);
    const table = await screen.findByRole('table', { name: t('i09w.table') });
    expect(await within(table).findByText('871902')).toBeInTheDocument();
    expect(within(table).getByText(t('i09w.st.PENDING_CHECKER'))).toBeInTheDocument();
    // BR-CPF-023: a member with no receipt reads as not remitted, never as zero.
    expect(within(table).getByText(t('i09w.notRemitted'))).toBeInTheDocument();
    expect(within(table).getByText(t('i09w.st.AWAITING_ADVICE'))).toBeInTheDocument();
  });

  it('SRS_G_30_theCardOpensOnTheFirstMemberPastTheAdvice', async () => {
    const calls = backend({
      ...i09,
      'GET /v1/cpf/deputations/members': () => paged([...DEPUTED].reverse()),
    });
    wrap(<DeputationAdjustment />, MAKER);
    await screen.findByRole('table', { name: t('i09w.table') });
    await waitFor(() => expect(sent(calls, 'GET', '/v1/cpf/deputations/871902')).not.toHaveLength(0));
    expect(sent(calls, 'GET', '/v1/cpf/deputations/871901')).toHaveLength(0);
  });

  it('SRS_6_27_thePayrollMakerIsToldTheManagerCpfRecords', async () => {
    const calls = backend(i09);
    const user = userEvent.setup();
    wrap(<DeputationAdjustment />, MAKER);
    await user.click(await screen.findByRole('button', { name: t('i09w.act.record') }));
    expect(await screen.findByText(t('i09w.why.notManager'))).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'POST')).toEqual([]);
  });

  it('SRS_6_27_anotherRoleSeesTheDrawnButtonOffWithItsReason', async () => {
    backend(i09);
    wrap(<DeputationAdjustment />, FINANCE);
    expect(await screen.findByRole('button', { name: t('i09w.act.record') })).toBeDisabled();
    expect(screen.getByRole('button', { name: t('i09w.act.import') })).toHaveAttribute(
      'data-why',
      t('i09w.why.importRole'),
    );
  });

  it('SRS_6_27_theManagerCpfOpensTheRegisterWithTheEntryForm', async () => {
    backend({ ...i09, 'GET /v1/cpf/deputation-entries': () => paged([]) });
    const user = userEvent.setup();
    wrap(<DeputationAdjustment />, me(['I-09:VIEW', 'I-09:CREATE'], { roles: ['CPF_MANAGER'] }));
    await user.click(await screen.findByRole('button', { name: t('i09w.act.record') }));
    expect(await screen.findByLabelText(t('i09.eis'))).toBeInTheDocument();
  });

  it('SRS_6_27_thePayrollCheckerIsToldTheAgmDecides', async () => {
    backend(i09);
    const user = userEvent.setup();
    wrap(<DeputationAdjustment />, CHECKER);
    const table = await screen.findByRole('table', { name: t('i09w.table') });
    const [approve] = await within(table).findAllByTitle(t('wf.approveTitle'));
    await user.click(approve as HTMLElement);
    expect(await screen.findByText(t('i09w.why.agm'))).toBeInTheDocument();
  });

  it('SRS_6_27_viewShowsTheRecord', async () => {
    backend(i09);
    const user = userEvent.setup();
    wrap(<DeputationAdjustment />, MAKER);
    await user.click(await screen.findByRole('button', { name: t('i09w.viewName', ['871901']) }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('871901 K. Bose')).toBeInTheDocument();
  });
});

const LINE: CorrectionLine = {
  ref: 'OB-2026-0031',
  eis: '871903',
  name: 'M. Verma',
  cpfCode: 'CPF/871903',
  part: 'EMPLOYEE',
  current: 412880,
  corrected: 418300,
  difference: 5420,
  reasonCode: 'CLERICAL_ERROR',
  evidence: 'Reconciliation record MR-118',
  interestEffect: null,
  status: 'PENDING_AGM_CPF',
  evidenceId: 7,
  fileName: 'evidence.pdf',
  documentType: 'Sanction Order',
  uploadedBy: 'cpf.manager.north@fci-demo.test',
  uploadedAt: '2026-11-01T05:00:00Z',
};

const i22 = {
  'GET /v1/cpf/opening-balance-corrections/board': () => ({
    open: 1,
    pendingAgm: 1,
    pendingHq: 0,
    interestQueued: null,
  }),
  'GET /v1/cpf/opening-balance-corrections/lines': () => paged([LINE]),
  'GET /v1/document-types': () => [],
};

describe('I-22 as drawn', () => {
  it('SRS_6_43_oneRowPerCorrectedPartWithItsEvidence', async () => {
    backend(i22);
    wrap(<OpeningBalanceCorrection />, MAKER);
    const table = await screen.findByRole('table', { name: t('i22w.table') });
    expect(await within(table).findByText('OB-2026-0031')).toBeInTheDocument();
    expect(within(table).getByText('Reconciliation record MR-118')).toBeInTheDocument();
    expect(await screen.findByText('evidence.pdf')).toBeInTheDocument();
  });

  it('BR_CPF_026_noInterestFigureBeforeTheMethodIsGiven', async () => {
    backend(i22);
    const user = userEvent.setup();
    wrap(<OpeningBalanceCorrection />, MAKER);
    await user.click(
      await screen.findByRole('button', {
        name: t('i22w.viewName', ['OB-2026-0031', t('i22.part.EMPLOYEE')]),
      }),
    );
    expect(await screen.findByText(t('i22.interestEffectNotConfigured'))).toBeInTheDocument();
  });

  it('SRS_6_43_thePayrollMakerRaisesNothing', async () => {
    const calls = backend(i22);
    const user = userEvent.setup();
    wrap(<OpeningBalanceCorrection />, MAKER);
    await user.click(await screen.findByRole('button', { name: t('i22w.act.raise') }));
    expect(await screen.findByText(t('i22w.why.notManager'))).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'POST')).toEqual([]);
  });

  it('SRS_6_43_theCheckerIsToldWhoDecidesAndFinanceHasTheButtonOff', async () => {
    backend(i22);
    const user = userEvent.setup();
    const view = wrap(<OpeningBalanceCorrection />, CHECKER);
    const table = await screen.findByRole('table', { name: t('i22w.table') });
    await user.click((await within(table).findAllByTitle(t('wf.approveTitle')))[0] as HTMLElement);
    expect(await screen.findByText(t('i22w.why.agm'))).toBeInTheDocument();
    view.unmount();
    wrap(<OpeningBalanceCorrection />, FINANCE);
    expect(await screen.findByRole('button', { name: t('i22w.act.raise') })).toBeDisabled();
  });

  it('SRS_6_43_theRegisterButtonOpensTheOldScreen', async () => {
    backend({ ...i22, 'GET /v1/cpf/opening-balance-corrections?': () => paged([]) });
    const user = userEvent.setup();
    wrap(<OpeningBalanceCorrection />, MAKER);
    await user.click(await screen.findByRole('button', { name: t('i22w.act.register') }));
    expect(await screen.findByLabelText(t('i22.findBy'))).toBeInTheDocument();
  });
});

const RULE: RegisterRule = {
  key: 'CPF.EPS.RATE',
  name: 'EPS share',
  description: 'BR-CPF-017',
  payrollTypeScope: null,
  inForce: null,
  versions: 0,
};
const SET: RegisterRule = {
  ...RULE,
  key: 'CPF.RATE.EMPLOYEE',
  inForce: {
    versionNo: 5,
    effectiveFrom: '2026-04-01',
    status: 'ACTIVE',
    value: 12,
    bands: [],
    circularNumber: 'FCI/CPF/1',
  },
  versions: 1,
};

describe('M-18 and M-19 as drawn', () => {
  it('SRS_6_47_aRateWithNoVersionIsNotConfiguredNeverAssumed', async () => {
    backend({ 'GET /v1/rules/register/m-18': () => [RULE, SET] });
    wrap(<StatutoryRates />, CHECKER);
    const table = await screen.findByRole('table', { name: t('m18w.table') });
    const row = (await within(table).findByText('CPF.EPS.RATE')).closest('tr') as HTMLElement;
    expect(within(row).getByText(t('m18w.notConfigured'))).toBeInTheDocument();
    const set = within(table).getByText('CPF.RATE.EMPLOYEE').closest('tr') as HTMLElement;
    expect(within(set).getByText('12')).toBeInTheDocument();
  });

  it('SRS_6_47_onlyARuleAdministratorProposesTheAdministratorIsToldWhy', async () => {
    backend({ 'GET /v1/rules/register/m-18': () => [RULE] });
    const view = wrap(<StatutoryRates />, CHECKER);
    expect(await screen.findByRole('button', { name: t('m18w.act.new') })).toBeDisabled();
    view.unmount();
    wrap(<StatutoryRates />, ADMIN);
    expect(await screen.findByRole('button', { name: t('m18w.act.new') })).toBeEnabled();
    expect(screen.getByRole('button', { name: t('m18w.act.supersede') })).toBeDisabled();
  });

  it('SRS_6_11_theRatesAndParametersAreRead', async () => {
    backend({
      'GET /v1/cpf/interest-rates': () => ({
        rates: [
          {
            id: 1,
            finYear: '2025-26',
            fundType: 'CPF',
            rateSource: 'FCI_TRUST',
            memberPopulation: 'ACTIVE_AND_DEPUTED',
            ratePercent: 8.25,
            appliesFrom: '2025-04-01',
            appliesTo: null,
            applicabilityNote: 'Up to the retirement month',
            bonusInterestPct: null,
            declared: true,
            status: 'ACTIVE',
            approvalId: 1,
          },
          {
            id: 2,
            finYear: '2026-27',
            fundType: 'CPF',
            rateSource: 'RPFC',
            memberPopulation: 'INACTIVE',
            ratePercent: 8.1,
            appliesFrom: '2026-04-01',
            appliesTo: null,
            applicabilityNote: null,
            bonusInterestPct: null,
            declared: false,
            status: 'ACTIVE',
            approvalId: 2,
          },
        ],
        pending: [],
      }),
      'GET /v1/rules/register/m-19': () => [RULE],
    });
    wrap(<InterestRates />, ADMIN);
    expect(await screen.findByText('8.25')).toBeInTheDocument();
    // SRS M-19: a rate recorded undeclared is never shown as the year's rate.
    expect(screen.getByText(t('m19w.undeclared'))).toBeInTheDocument();
    expect(await screen.findByText('EPS share')).toBeInTheDocument();
  });

  it('SRS_6_11_theRuleAdministratorStartsTheFormFromTheDrawnButton', async () => {
    backend({
      'GET /v1/cpf/interest-rates': () => ({ rates: [], pending: [] }),
      'GET /v1/rules/register/m-19': () => [],
      'GET /v1/cpf/withdrawal-purposes': () => ({ purposes: [], pending: [] }),
    });
    const user = userEvent.setup();
    wrap(
      <InterestRates />,
      me(['M-19:VIEW', 'M-19:CREATE'], {
        roles: ['RULE_ADMINISTRATOR'],
        location: { code: 'HQ001', label: 'HQ' },
      }),
    );
    await user.click(await screen.findByRole('button', { name: t('m19w.act.new') }));
    expect(await screen.findByLabelText(t('m19.ratePercent'))).toBeInTheDocument();
  });
});

const SUMMARY = {
  finYear: '2026-27',
  zone: 'NA11',
  members: 2,
  calculated: 0,
  notYetCalculated: 2,
  reRuns: 0,
  freezeStatus: 'OPEN',
  frozenBy: null,
  frozenAt: null,
  runs: [
    {
      run: 1,
      scope: 'INDIVIDUAL',
      eis: '104882',
      initiatedBy: 'm@x',
      initiatedAt: '2026-11-01T05:00:00Z',
      members: 1,
      calculated: 0,
      notCalculated: 1,
      stopped: 0,
      reason: 'Member query',
    },
  ],
};

describe('C-12 as drawn', () => {
  it('SRS_6_45_theTilesAndTheAuditAreRead', async () => {
    backend({ 'GET /v1/cpf/interest-runs': () => SUMMARY });
    wrap(<InterestRun />, MAKER);
    expect(await screen.findByText('Member query')).toBeInTheDocument();
    expect(screen.getByText(t('c12w.individual', ['104882']))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('c12w.act.freeze') })).toBeDisabled();
  });

  it('SRS_6_45_thePayrollMakerRunsNothingTheManagerCpfOpensTheForm', async () => {
    const calls = backend({ 'GET /v1/cpf/interest-runs': () => SUMMARY });
    const user = userEvent.setup();
    const view = wrap(<InterestRun />, MAKER);
    await user.click(await screen.findByRole('button', { name: t('c12w.act.run') }));
    expect(await screen.findByText(t('c12w.why.notManager'))).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'POST')).toEqual([]);
    view.unmount();
    wrap(<InterestRun />, me(['C-12:VIEW', 'C-12:CREATE'], { roles: ['CPF_MANAGER'] }));
    await user.click(await screen.findByRole('button', { name: t('c12w.act.run') }));
    expect(await screen.findByLabelText(t('c12.reason'))).toBeInTheDocument();
  });

  it('SRS_6_46_theFreezeHasNoOtpAndOpensForItsHolder', async () => {
    backend({ 'GET /v1/cpf/interest-runs': () => SUMMARY });
    const user = userEvent.setup();
    const view = wrap(<InterestRun />, CHECKER);
    await user.click(await screen.findByRole('button', { name: t('c12w.act.freeze') }));
    expect(await screen.findByText(t('c12w.why.notAgm'))).toBeInTheDocument();
    view.unmount();
    wrap(<InterestRun />, me(['C-12:VIEW', 'C-12:SUBMIT'], { roles: ['CPF_MANAGER'] }));
    await user.click(await screen.findByRole('button', { name: t('c12w.act.freeze') }));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });
});

const PAYMENT: SupplementaryRow = {
  entry: 'CSP-2026-0012',
  eis: '871907',
  name: 'P. Barre',
  designation: 'Assistant Manager',
  category: null,
  employeeType: 'STAFF',
  cpfCode: 'CPF/871907',
  posting: 'Delhi HQ',
  employmentStatus: 'SEPARATED',
  sanctionNumber: 'HQ-F-2015-256',
  employee: 149,
  employer: 162,
  vpf: 0,
  sanctioned: 311,
  orderNumber: '17(49)',
  orderDate: '2015-06-30',
  paymentStatus: 'RELEASED',
  invoiceNumber: '201506303540',
  invoiceStatus: 'SENT',
  sentToApps: '2015-06-30T05:30:00Z',
  appsCancelStatus: 'NOT_REQUESTED',
};

describe('I-12 as drawn', () => {
  it('SRS_6_48_theEntryAndItsInvoiceStatusAreRead', async () => {
    backend({ 'GET /v1/cpf/supplementary-payments': () => paged([PAYMENT]) });
    wrap(<SupplementaryPayment />, MAKER);
    expect(await screen.findByText('CSP-2026-0012')).toBeInTheDocument();
    expect(await screen.findByText(t('i12w.member', ['871907', 'P. Barre']))).toBeInTheDocument();
    expect(screen.getByText(t('i12w.inv.SENT'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('i12w.cancelName', ['CSP-2026-0012']) })).toBeDisabled();
  });

  it('SRS_6_48_noPaymentActionSendsAnything', async () => {
    const calls = backend({ 'GET /v1/cpf/supplementary-payments': () => paged([PAYMENT]) });
    const user = userEvent.setup();
    wrap(<SupplementaryPayment />, MAKER);
    await user.click(await screen.findByRole('button', { name: t('i12w.act.record') }));
    await waitFor(() => expect(screen.getAllByText(t('i12w.why.notBuilt')).length).toBeGreaterThan(1));
    expect(calls.filter((c) => c.method === 'POST')).toEqual([]);
    expect(screen.getByRole('button', { name: t('i12w.act.release') })).toBeDisabled();
  });

  it('SRS_6_48_theCheckerHoldsTheReleaseOnlyAndAnEntryWithoutInvoiceSaysSo', async () => {
    backend({
      'GET /v1/cpf/supplementary-payments': () =>
        paged([
          { ...PAYMENT, invoiceNumber: null, invoiceStatus: null, sentToApps: null, appsCancelStatus: null },
        ]),
    });
    wrap(<SupplementaryPayment />, CHECKER);
    expect(await screen.findByText(t('i12w.inv.NONE'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('i12w.act.release') })).toBeEnabled();
    expect(screen.getByRole('button', { name: t('i12w.act.letter') })).toBeDisabled();
  });
});

const APPLIED: ZoneChangeRow = {
  reference: 'HRMS-PZC-0014',
  eis: '871905',
  name: 'S. Gupta',
  reason: 'PROMOTION_CAT_II_TO_I',
  fromZone: 'West',
  toZone: 'North',
  order: 'CPF/HQ/2026/31',
  oldId: 'CPF/MH/2004/1188',
  newId: 'CPF/NA11/2026/0412',
  effective: '2026-07-01',
  decision: 'APPROVED',
  status: 'APPLIED',
  taskNumber: 'HCA-2026-9180',
};
const REJECTED: ZoneChangeRow = {
  ...APPLIED,
  reference: 'HRMS-PZC-0011',
  name: 'K. Das',
  reason: 'ZONE_SURRENDER',
  newId: null,
  effective: null,
  order: null,
  decision: 'REJECTED',
  status: 'REJECTED_IN_HRMS',
  taskNumber: null,
};
const DETAIL: ZoneChangeDetail = {
  change: APPLIED,
  carriedEmployee: null,
  carriedEmployer: null,
  carriedVpf: null,
  interestTo: null,
  reconStatus: 'PENDING',
  sspSyncedAt: null,
  ecrFrom: null,
  iocpfStatus: null,
  iocpfAcknowledged: null,
  ids: [
    {
      id: 'CPF/MH/2004/1188',
      zone: 'West',
      from: '2004-01-02',
      to: '2026-06-30',
      status: 'SUPERSEDED',
      reason: null,
    },
    { id: 'CPF/NA11/2026/0412', zone: 'North', from: '2026-07-01', to: null, status: 'ACTIVE', reason: null },
  ],
};

describe('I-13 as drawn', () => {
  const routes = {
    'GET /v1/cpf/parent-zone-changes/HRMS-PZC-0014': () => DETAIL,
    'GET /v1/cpf/parent-zone-changes/HRMS-PZC-0011': () => ({ ...DETAIL, change: REJECTED, ids: [] }),
    'GET /v1/cpf/parent-zone-changes': () => paged([REJECTED, APPLIED]),
    'GET /v1/cpf/iocpf-pending': () =>
      paged([
        {
          number: 'IOCPF-1',
          eis: '871905',
          name: 'S. Gupta',
          reason: 'PARENT_ZONE_CHANGE',
          total: 1000,
          appsStatus: 'SENT',
          sentAt: '2026-07-02T05:00:00Z',
          retries: 2,
        },
      ]),
  };

  it('SRS_6_49_theCardsFollowTheAppliedChangeAndHoldNoComputedBalance', async () => {
    backend(routes);
    wrap(<ParentZoneChange />, MAKER);
    expect(await screen.findByText(t('i13w.history', ['S. Gupta']))).toBeInTheDocument();
    expect(await screen.findByText(t('i13w.carried.none'))).toBeInTheDocument();
    expect(await screen.findByText(t('i13w.id.SUPERSEDED'))).toBeInTheDocument();
  });

  it('BR_CPF_020_aRejectedChangeReadsAsUnchangedAndNothingIsEditable', async () => {
    const calls = backend(routes);
    const user = userEvent.setup();
    wrap(<ParentZoneChange />, CHECKER);
    expect(await screen.findByText(t('i13w.st.REJECTED_IN_HRMS'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('i13w.editName', ['HRMS-PZC-0011']) })).toBeDisabled();
    await user.click(screen.getByText('HRMS-PZC-0011'));
    expect(await screen.findByText(t('i13w.h.none'))).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t('i13w.viewName', ['HRMS-PZC-0011']) }));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(calls.filter((c) => c.method !== 'GET')).toEqual([]);
  });

  it('SRS_6_52_theIocpfPendingListOpensFromTheDrawnButton', async () => {
    backend(routes);
    const user = userEvent.setup();
    wrap(<ParentZoneChange />, MAKER);
    await user.click(await screen.findByRole('button', { name: t('i13w.act.iocpf') }));
    expect(await screen.findByText('IOCPF-1')).toBeInTheDocument();
  });

  it('SRS_6_49_aRoleWithoutI21IsToldWhoHoldsTheTasksTheHolderIsSent', async () => {
    backend(routes);
    const user = userEvent.setup();
    wrap(<ParentZoneChange />, MAKER);
    await user.click(await screen.findByRole('button', { name: t('i13w.act.tasks') }));
    expect(await screen.findByText(t('i13w.why.tasks'))).toBeInTheDocument();
  });
});

const ENTRY = {
  key: 'CPF_OBCB',
  name: 'x',
  family: 'f',
  section: 'A.4.6',
  domain: 'REPORT',
  producingScreen: 'O-04',
  payrollTypes: 'All',
  periodicity: 'Monthly',
  lockRequirement: 'POST_LOCK',
  versionNo: 1,
  layoutFormat: null,
  blockedReason: 'Layout not supplied',
  status: 'BLOCKED',
};

describe('O-07 and O-08 as drawn', () => {
  const routes = {
    'GET /v1/reports/cpf-eseva/months': () => ({
      period: '202611',
      version: 'PROVISIONAL',
      previous: '202610',
      previousVersion: 'FINAL',
    }),
    'GET /v1/reports/cpf-eseva': () =>
      paged([
        ENTRY,
        {
          ...ENTRY,
          key: 'IOCPF_PENDING',
          lockRequirement: 'NONE',
          blockedReason: null,
          status: 'PRODUCED_ELSEWHERE',
        },
      ]),
    'GET /v1/reports/cpf-reconciliation': () => paged([ENTRY]),
  };

  it('BR_CPF_036_theMonthIsProvisionalBeforeItsLockFinalAfter', async () => {
    backend(routes);
    wrap(<CpfReports />, MAKER);
    expect(await screen.findByText(t('o07w.r.CPF_OBCB'))).toBeInTheDocument();
    expect(await screen.findByText(t('o07w.tile.run.PROVISIONAL'))).toBeInTheDocument();
    expect(screen.getByText(t('o07w.tile.prev.FINAL'))).toBeInTheDocument();
  });

  it('SRS_18_24_aBlockedReportSaysWhyAndNothingGenerates', async () => {
    const calls = backend(routes);
    const user = userEvent.setup();
    wrap(<CpfReports />, MAKER);
    await screen.findByText(t('o07w.r.CPF_OBCB'));
    expect(screen.getByText(t('o03.status.blocked'))).toHaveAttribute('title', 'Layout not supplied');
    expect(screen.getByRole('button', { name: t('o07w.act.send') })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: t('o07w.act.generate') }));
    expect(await screen.findByText(t('o07w.why.generate'))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/v1/reports')).toEqual([]);
  });

  it('BR_CPF_036_theCheckerSendsAndGeneratesNot', async () => {
    backend(routes);
    wrap(<CpfReports />, CHECKER);
    expect(await screen.findByRole('button', { name: t('o07w.act.send') })).toBeEnabled();
    expect(screen.getByRole('button', { name: t('o07w.act.generate') })).toBeDisabled();
    expect(screen.getByRole('button', { name: t('o07w.act.bulk') })).toBeDisabled();
  });

  it('SRS_18_24_o08ShowsTheSetAndWhatARowWaitsFor', async () => {
    backend(routes);
    const user = userEvent.setup();
    wrap(<CpfReconciliationReports />, CHECKER);
    expect(await screen.findByText(t('o08w.r.CPF_OBCB'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('o08w.act.generate') })).toBeDisabled();
    await user.click(screen.getByTitle(t('wf.approveTitle')));
    expect(await screen.findByText(t('o08w.why.validate'))).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t('o08w.viewName', [t('o08w.r.CPF_OBCB')]) }));
    expect(await screen.findByText('Layout not supplied')).toBeInTheDocument();
  });

  it('SRS_18_24_theMakerSeesO08GenerateEnabledWithTheReason', async () => {
    backend(routes);
    wrap(<CpfReconciliationReports />, MAKER);
    await screen.findByText(t('o08w.r.CPF_OBCB'));
    expect(screen.getByRole('button', { name: t('o08w.act.generate') })).toHaveAttribute(
      'data-guard',
      t('o07w.why.generate'),
    );
  });
});
