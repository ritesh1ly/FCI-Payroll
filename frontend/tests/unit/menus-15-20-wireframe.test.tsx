import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CafeteriaScreen, ClaimsScreen } from '@/modules/allowance';
import { CeilingNormsScreen } from '@/modules/configuration';
import { LeaveEventsScreen, LeaveParametersScreen, LeaveTypesScreen } from '@/modules/leave';
import { drawnParameters } from '@/modules/leave/business/leaveRules';
import { backend, me, paged, referenceLists, sent, t, wrap } from './support/harness';

/**
 * Menus 15 and 20 as Wireframes v6.1 draws them (OD-121): M-15, I-03, I-16, M-13, M-20, I-11. The drawn objects for
 * each kind of role, the source badges, disabled reasons, the row actions, the closed sections that keep what the
 * drawing leaves out, and the rules beneath (SRS-16-14, SRS-11-1, SRS-11-16, SRS-11-17, SRS-LV-14, SRS-LV-23).
 */

afterEach(() => vi.unstubAllGlobals());

const REFERENCE = referenceLists({
  CLAIM_TYPE: [
    ['MEDICAL', 'Medical reimbursement'],
    ['LABOUR_TA', 'Labour TA'],
  ],
  CEILING_BASIS: [
    ['FIXED_AMOUNT', 'Fixed Amount'],
    ['PERCENT_BASIC_DA', 'Percentage of Basic + DA'],
  ],
  ELIGIBILITY_CATEGORY: [['DEMO_ALL', 'Demo - all members']],
  ALLOWANCE_PERIODICITY: [
    ['MONTHLY', 'Monthly'],
    ['ANNUAL', 'Annual'],
  ],
  LEAVE_RECOVERY_EVENT: [['LWP', 'LWP']],
  LEAVE_RECOVERY_STATUS: [['POSTED', 'Recovered']],
  ORDER_COPY_DOCUMENT_TYPE: [['OTHER', 'Other']],
});

function columns(heading: string): string[] {
  const card = screen.getByRole('heading', { name: heading }).closest('.card') as HTMLElement;
  return Array.from(card.querySelectorAll('thead th')).map((th) => {
    const copy = th.cloneNode(true) as HTMLElement;
    const source = /s-([A-Z]+)/.exec(copy.querySelector('.src')?.className ?? '')?.[1] ?? '';
    copy.querySelector('.src')?.remove();
    return `${copy.textContent} [${source}]`;
  });
}

const version = (over: object = {}) => ({
  id: 1,
  versionNo: 1,
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  ceilingBasis: 'FIXED_AMOUNT',
  ceilingValue: 15000,
  periodicity: 'ANNUAL',
  eligibilityCategory: 'DEMO_ALL',
  memberScope: 'EMPLOYEE',
  confirmed: true,
  status: 'ACTIVE',
  approvalId: 1,
  circularOrderId: null,
  ...over,
});

function norms(rows: unknown[]) {
  return backend({
    'GET /v1/reference/lists': () => REFERENCE,
    'GET /v1/masters/ceiling-norms/counts': () => ({ claimTypes: 3, percentageRules: 1, pendingApproval: 1 }),
    'GET /v1/masters/ceiling-norms': () => paged(rows),
    'GET /v1/claims/ceiling-breaches': () => 4,
    'GET /v1/masters/circulars/current': () => [{ id: 9, number: 'CIR-9', subject: 'Norms' }],
  });
}

describe('M-15 as drawn', () => {
  const rows = [
    { claimType: 'MEDICAL', name: 'Medical', current: version(), pending: null },
    {
      claimType: 'LEASED',
      name: 'Leased Accommodation',
      current: version({ ceilingBasis: 'PERCENT_BASIC_DA', ceilingValue: 7, periodicity: 'MONTHLY' }),
      pending: version({ versionNo: 2, status: 'PENDING_APPROVAL' }),
    },
    { claimType: 'LABOUR_TA', name: 'Labour TA', current: null, pending: null },
  ];

  it('SRS_16_14_theColumnsSplitEachFieldWithTheirBadges_theTilesAreTheServersCounts', async () => {
    norms(rows);
    wrap(<CeilingNormsScreen />, ['M-15:VIEW']);
    await screen.findByRole('cell', { name: 'Medical reimbursement' }, { timeout: 8000 });
    expect(columns(t('screen.M-15'))).toEqual([
      `${t('m15w.claimType')} [ENT]`,
      `${t('m15w.basis')} [RULE]`,
      `${t('m15w.value')} [RULE]`,
      `${t('m15w.periodicity')} [RULE]`,
      `${t('m15w.eligibility')} [RULE]`,
      `${t('m15w.effFrom')} [ENT]`,
      `${t('i03.status')} [SYS]`,
      `${t('m04.col.actions')} [SYS]`,
    ]);
    expect(await screen.findByText('7%')).toBeInTheDocument();
    expect((await screen.findByText(t('m15w.tile.breaches'))).closest('.tile')).toHaveTextContent('4');
    expect(screen.getByText(t('m15w.tile.percent')).closest('.tile')).toHaveTextContent('1');
    expect(screen.getByText(t('mst.notConfigured'))).toBeInTheDocument();
    expect(screen.getByText(t('status.PENDING_APPROVAL'))).toBeInTheDocument();
  });

  it('SRS_16_14_aReadOnlyRoleSeesTheEntryDrawnDisabledWithItsReasonAndTheNote', async () => {
    norms(rows);
    wrap(<CeilingNormsScreen />, ['M-15:VIEW']);
    await screen.findByRole('cell', { name: 'Medical reimbursement' });
    const add = screen.getByRole('button', { name: t('m15w.newRule') });
    expect(add).toBeDisabled();
    expect(add).toHaveAttribute('data-why', t('m15w.why.admin'));
    expect(screen.getByText(t('m15w.formNote'))).toBeInTheDocument();
    expect(screen.queryByLabelText(/Claim Type/)).not.toBeInTheDocument();
  });

  it('OD_135_theSystemAdministratorSeesTheFormButCannotSubmit_noGrantMatchesADrawing', async () => {
    norms(rows);
    wrap(<CeilingNormsScreen />, me(['M-15:VIEW'], { roles: ['SYSTEM_ADMINISTRATOR'] }));
    await screen.findByRole('cell', { name: 'Medical reimbursement' });
    expect(screen.getByLabelText(/Claim Type/)).toBeInTheDocument();
    // OD-123: Periodicity reads its M-16 list.
    expect(
      Array.from((screen.getByLabelText(/Periodicity/) as HTMLSelectElement).options).map((o) => o.value),
    ).toEqual(['', 'MONTHLY', 'ANNUAL']);
    const save = screen.getByRole('button', { name: t('m15w.save') });
    expect(save).toBeEnabled();
    expect(save).toHaveAttribute('data-guard', t('mst.why.notMaker'));
    await userEvent.click(save);
    expect(await screen.findByText(t('mst.why.notMaker'))).toBeInTheDocument();
  });

  it('SRS_G_38_theRuleAdministratorCannotSaveWithoutTheCircularAndSendsEveryDrawnField', async () => {
    const calls = norms(rows);
    const user = userEvent.setup();
    wrap(
      <CeilingNormsScreen />,
      me(['M-15:VIEW', 'M-15:CREATE'], { locationCode: 'HQ001', locationType: 'HQ' } as never),
    );
    await screen.findByRole('cell', { name: 'Medical reimbursement' });
    await user.selectOptions(screen.getByLabelText(/Claim Type/), 'MEDICAL');
    await user.selectOptions(screen.getByLabelText(/Ceiling Basis/), 'PERCENT_BASIC_DA');
    expect(screen.getByText(t('m15w.valueHintPercent'))).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText(/Ceiling Basis/), 'FIXED_AMOUNT');
    expect(screen.getByText(t('m15w.valueHintAmount'))).toBeInTheDocument();
    await user.type(screen.getByLabelText(/Ceiling Value/), '100');
    await user.selectOptions(screen.getByLabelText(/Periodicity/), 'ANNUAL');
    await user.selectOptions(screen.getByLabelText(/Eligibility Category/), 'DEMO_ALL');
    await user.type(screen.getByLabelText(/Reason for Change/), 'Annual revision');
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('SRS_16_14_theCheckersRowTickGoesToTheInboxAndViewShowsTheRecord', async () => {
    norms(rows);
    const user = userEvent.setup();
    wrap(<CeilingNormsScreen />, ['M-15:VIEW', 'M-15:APPROVE']);
    await user.click(
      await screen.findByRole('button', { name: `${t('common.view')} Medical reimbursement` }),
    );
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Fixed Amount')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('SRS_16_14_theDrawnHistoryOpensTheClosedRegisterThatKeepsTheVersions', async () => {
    norms(rows);
    const user = userEvent.setup();
    wrap(<CeilingNormsScreen />, ['M-15:VIEW']);
    await screen.findByRole('cell', { name: 'Medical reimbursement' });
    expect(screen.getByTestId('m15-register')).not.toHaveAttribute('open');
    await user.click(screen.getByRole('button', { name: t('wf.historyAct') }));
    await waitFor(() => expect(screen.getByTestId('m15-register')).toHaveAttribute('open'));
  });
});

const claim = (over: object = {}) => ({
  id: 7,
  eis: '881401',
  name: 'Vikram Sethi',
  memberType: 'EMPLOYEE',
  claimType: 'MEDICAL',
  claimTypeLabel: 'Medical reimbursement',
  periodFrom: '2026-10-01',
  periodTo: '2026-10-28',
  claimed: 142600,
  entitlement: 15000,
  payable: 15000,
  capped: true,
  status: 'APPROVED',
  source: 'HRMS',
  hrmsRequestId: 'HRMS-MED-1',
  taxable: null,
  taxableConfirmedAt: null,
  invoiceStatus: 'NOT_RAISED',
  iogaNumber: null,
  iogaDate: null,
  iogaAmount: null,
  approvalId: null,
  decisionReason: null,
  taxTreatment: null,
  taxableAmount: null,
  invoiceSentAt: null,
  ...over,
});

const lease = (over: object = {}) => ({
  id: 1,
  eis: '880707',
  name: 'Farida Khan',
  memberType: 'EMPLOYEE',
  accommodationType: 'THIRD_PARTY',
  leaseAmount: 16000,
  sevenPercent: null,
  hraSuppressed: false,
  conflict: true,
  source: 'HRMS',
  hrmsRequestId: null,
  effectiveFrom: '2026-04-01',
  ...over,
});

function claims(rows: unknown[], leases: unknown[] = [lease()]) {
  return backend({
    'GET /v1/reference/lists': () => REFERENCE,
    'GET /v1/claims/counts': () => ({ claims: 9, payable: 50000, pendingApproval: 2, capped: 3, leases: 4 }),
    'GET /v1/claims/leases': () => paged(leases),
    'GET /v1/claims?': (url: string) =>
      paged(url.includes('status=PAID') ? [claim({ status: 'PAID' })] : rows),
    'GET /v1/claims/7': () => ({ claim: rows[0], documents: [] }),
    'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other', referenceRequired: false }],
  });
}

/** The claim's cell in the register (the invoice card names a claim too). */
const registerRow = async () =>
  within(await screen.findByRole('table', { name: t('i03.register') })).findByText('CLM-7');

describe('I-03 as drawn', () => {
  it('WF_1709_theCheckerTickOnALabourLeaseSaysNothingIsWaiting_noneOnAStaffLease', async () => {
    const calls = claims(
      [claim()],
      [lease({ id: 1, memberType: 'LABOUR', eis: 'L10702' }), lease({ id: 2 })],
    );
    wrap(<ClaimsScreen />, ['I-03:VIEW', 'I-03:APPROVE']);
    const table = await screen.findByRole('table', { name: t('i03w.leaseTitle') });
    const labour = (await within(table).findByText(/L10702/)).closest('tr') as HTMLElement;
    await userEvent.click(within(labour).getByRole('button', { name: '✔' }));
    expect(await screen.findByText(t('mst.nothingWaiting'))).toBeTruthy();
    expect(within(table).getAllByRole('button', { name: '✔' })).toHaveLength(1);
    expect(calls.filter((c) => c.method === 'POST')).toEqual([]);
  });

  it('SRS_G_30_theOrderCardOpensOnTheFirstClaimUntilOneIsPicked_viewingOnly', async () => {
    const calls = claims([claim()]);
    wrap(<ClaimsScreen />, ['I-03:VIEW']);
    await registerRow();
    await waitFor(() => expect(calls.some((c) => c.url.includes('/v1/claims/7'))).toBe(true));
    expect(calls.filter((c) => c.method === 'POST')).toEqual([]);
  });

  it('SRS_11_1_anEmployeeClaimIsReadOnlyWithTheReason_theColumnsAndBadgesAreAsDrawn', async () => {
    claims([claim()]);
    wrap(<ClaimsScreen />, ['I-03:VIEW', 'I-03:VALIDATE', 'I-03:EDIT', 'I-03:EXPORT']);
    const register = await screen.findByRole('table', { name: t('i03.register') });
    await within(register).findByText('CLM-7');
    const cols = Array.from(register.querySelectorAll('thead th')).map((th) => {
      const copy = th.cloneNode(true) as HTMLElement;
      const source = /s-([A-Z]+)/.exec(copy.querySelector('.src')?.className ?? '')?.[1] ?? '';
      copy.querySelector('.src')?.remove();
      return `${copy.textContent} [${source}]`;
    });
    expect(cols[0]).toBe(`${t('i03w.col.claim')} [SYS]`);
    expect(cols[2]).toBe(`${t('i03w.col.type')} [ESS]`);
    expect(cols[5]).toBe(`${t('i03w.col.entitlement')} [RULE]`);
    expect(cols[7]).toBe(`${t('i03w.col.source')} [INTF]`);
    const row = within(register).getByText('CLM-7').closest('tr') as HTMLElement;
    const edit = within(row).getByRole('button', { name: t('wf.edit') });
    expect(edit).toBeDisabled();
    expect(edit).toHaveAttribute('data-why', t('i03w.why.hrms'));
  });

  it('SRS_11_13_theMakerEditsASubmittedLabourClaimInTheRegister', async () => {
    claims([claim({ memberType: 'LABOUR', status: 'SUBMITTED', source: 'LDH', hrmsRequestId: null })]);
    const user = userEvent.setup();
    wrap(<ClaimsScreen />, ['I-03:VIEW', 'I-03:VALIDATE', 'I-03:EDIT']);
    const row = (await registerRow()).closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: t('wf.edit') }));
    await waitFor(() => expect(screen.getByTestId('i03-register')).toHaveAttribute('open'));
  });

  it('SRS_11_13_aLabourClaimPastValidationSaysWhy_aNonMakerToo', async () => {
    claims([claim({ memberType: 'LABOUR', status: 'VALIDATED', source: 'LDH' })]);
    wrap(<ClaimsScreen />, ['I-03:VIEW', 'I-03:VALIDATE']);
    const row = (await registerRow()).closest('tr') as HTMLElement;
    expect(within(row).getByRole('button', { name: t('wf.edit') })).toHaveAttribute(
      'data-why',
      t('i03w.why.validated'),
    );
  });

  it('SRS_G_13_anExportNotHeldIsDrawnAndExplained_approveSelectedIsLockedForNonCheckers', async () => {
    claims([claim()]);
    const user = userEvent.setup();
    wrap(<ClaimsScreen />, ['I-03:VIEW']);
    await registerRow();
    await user.click(screen.getByRole('button', { name: t('wf.export') }));
    expect(await screen.findByText(t('i03w.why.export'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('w01.approveSelected') })).toBeDisabled();
  });

  it('SRS_11_3_the7PercentFigureIsNotConfigured_aLeaseWithFullHraIsAConflict', async () => {
    claims(
      [claim()],
      [
        lease(),
        lease({
          id: 2,
          eis: 'L10702',
          memberType: 'LABOUR',
          conflict: false,
          hraSuppressed: true,
          sevenPercent: 900,
        }),
      ],
    );
    wrap(<ClaimsScreen />, ['I-03:VIEW']);
    expect(await screen.findByText(t('i03w.lease.conflict'))).toBeInTheDocument();
    expect(screen.getByText(t('i03w.notConfigured'))).toBeInTheDocument();
    const staff = screen.getByText(/880707/).closest('tr') as HTMLElement;
    expect(within(staff).getByRole('button', { name: t('wf.edit') })).toHaveAttribute(
      'data-why',
      t('i03w.why.leaseStaff'),
    );
    const labour = screen.getByText(/L10702/).closest('tr') as HTMLElement;
    expect(within(labour).getByRole('button', { name: t('wf.edit') })).toHaveAttribute(
      'data-why',
      t('i03w.why.leaseLabour'),
    );
  });

  it('SRS_11_17_viewOfALeaseAndOfAClaimShowWhatTheColumnsLeaveOut', async () => {
    claims([claim()]);
    const user = userEvent.setup();
    wrap(<ClaimsScreen />, ['I-03:VIEW']);
    const row = (await registerRow()).closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: `${t('common.view')} CLM-7` }));
    expect(await within(screen.getByRole('dialog')).findByText('HRMS-MED-1')).toBeInTheDocument();
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: t('common.close') }));
    await user.click(screen.getByRole('button', { name: `${t('common.view')} 880707` }));
    expect(within(screen.getByRole('dialog')).getByText('Third-party lease')).toBeInTheDocument();
  });

  it('SRS_G_37_thePaidClaimsInvoiceIsOnTheCard_itsCancelIsOffWithTheReason', async () => {
    claims([claim()]);
    wrap(<ClaimsScreen />, ['I-03:VIEW']);
    await registerRow();
    const card = await screen.findByRole('table', { name: t('i03w.invoiceTitle') });
    await within(card).findByText('881401 Vikram Sethi');
    expect(within(card).getByRole('button', { name: t('wf.inv.cancel') })).toHaveAttribute(
      'data-why',
      t('i03w.why.invoice'),
    );
  });

  it('A_41_theOrderCardIsReadOnlyForAnEmployeeClaim_theLabourDealingHandStartsInTheRegister', async () => {
    claims([claim()]);
    wrap(<ClaimsScreen />, ['I-03:VIEW', 'I-03:CREATE', 'I-03:VALIDATE']);
    await registerRow();
    expect(screen.getByTestId('i03-register')).toHaveAttribute('open');
  });

  it('A_41_theMakerAttachesTheBillOfASelectedLabourClaim', async () => {
    const calls = claims([claim({ memberType: 'LABOUR', status: 'SUBMITTED', source: 'LDH' })]);
    const user = userEvent.setup();
    wrap(<ClaimsScreen />, ['I-03:VIEW', 'I-03:VALIDATE', 'I-03:EDIT']);
    await user.click(await registerRow());
    const card = document.querySelector('[data-order-card]') as HTMLElement;
    await waitFor(() => expect(card.querySelector('input[type=file]')).not.toBeDisabled());
    const fields = card.querySelectorAll('input');
    await user.type(fields[0] as HTMLInputElement, 'BILL/1');
    await user.type(fields[1] as HTMLInputElement, '2026-10-01');
    await user.type(fields[2] as HTMLInputElement, 'Depot office');
    await user.upload(
      card.querySelector('input[type=file]') as HTMLInputElement,
      new File(['x'], 'bill.pdf', { type: 'application/pdf' }),
    );
    await waitFor(() => expect(sent(calls, 'POST', '/v1/claims/7/documents')).toHaveLength(1));
  });
});

const selection = (over: object = {}) => ({
  id: 1,
  eis: '104882',
  name: 'R. Sharma',
  category: 'IDA',
  finYear: '2026-27',
  version: 1,
  startDate: '2026-04-01',
  endDate: null,
  hrmsReference: 'HRMS-CAF-1',
  status: 'APPLIED',
  components: [
    { payCode: 917, name: 'Newspaper', monthlyAmount: 500 },
    { payCode: 918, name: 'Mobile', monthlyAmount: 750 },
  ],
  payEffect: 1250,
  arrearFrom: null,
  arrearTo: null,
  arrearIn: null,
  ...over,
});

describe('I-16 as drawn', () => {
  it('SRS_11_16_appliedArrearAndNoSelectionReadAsDrawn_theSrsStartDateStaysInTheHistory', async () => {
    backend({
      'GET /v1/cafeteria-selections': () =>
        paged([
          selection(),
          selection({
            id: 2,
            eis: '105461',
            status: 'ARREAR_RAISED',
            arrearFrom: '202604',
            arrearTo: '202606',
            arrearIn: '202607',
          }),
          selection({
            id: null,
            eis: '106552',
            status: 'NO_SELECTION',
            components: [],
            hrmsReference: null,
            startDate: null,
            finYear: null,
          }),
        ]),
    });
    wrap(<CafeteriaScreen />, ['I-16:VIEW', 'I-16:EXPORT']);
    expect(await screen.findByText(t('i16.st.NO_SELECTION'))).toBeInTheDocument();
    expect(screen.getByText(t('i16w.st.arrearIn', ['JUL']))).toBeInTheDocument();
    expect(screen.getByText(t('i16w.effect.arrear', ['APR', 'JUN']))).toBeInTheDocument();
    expect(screen.getAllByText('Newspaper · Mobile')).toHaveLength(2);
    expect(screen.queryByText(t('i16.start'))).not.toBeInTheDocument();
  });

  it('SRS_11_16_noRoleWithoutTheRightSeesIt_exportIsExplainedForAViewOnlyRole', async () => {
    backend({ 'GET /v1/cafeteria-selections': () => paged([selection()]) });
    const user = userEvent.setup();
    wrap(<CafeteriaScreen />, ['I-16:VIEW']);
    await screen.findByText('HRMS-CAF-1');
    await user.click(screen.getByRole('button', { name: t('i16w.export') }));
    expect(await screen.findByText(t('i16w.why.export'))).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t('wf.historyAct') }));
    await waitFor(() => expect(screen.getByTestId('i16-register')).toHaveAttribute('open'));
  });

  it('SRS_11_16_aRoleWithoutViewSeesNothing', () => {
    backend({});
    wrap(<CafeteriaScreen />, []);
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });
});

function leaveTypes() {
  return backend({
    'GET /v1/leave-types/counts': () => ({
      received: 10,
      encashable: 2,
      payReducing: 3,
      lastSync: '2026-09-24T06:00:00',
    }),
    'GET /v1/leave-types': () =>
      paged([
        {
          code: 'EL',
          name: 'Earned Leave',
          encashable: true,
          halfDay: false,
          payEffect: 'NO_EFFECT',
          lastReceived: '2026-09-24T06:00:00',
        },
        {
          code: 'CL',
          name: 'Casual Leave',
          encashable: false,
          halfDay: true,
          payEffect: null,
          lastReceived: null,
        },
      ]),
  });
}

describe('M-13 as drawn', () => {
  it('SRS_G_45_codeAndTypeAreTwoColumns_lastSyncSaysWhenItArrivedAndCallsNothing', async () => {
    const calls = leaveTypes();
    const user = userEvent.setup();
    wrap(<LeaveTypesScreen />, ['M-13:VIEW', 'M-13:EXPORT']);
    await screen.findByText('Earned Leave');
    expect(columns(t('m13w.title'))).toEqual([
      `${t('m13w.col.code')} [HRMS]`,
      `${t('m13w.col.type')} [HRMS]`,
      `${t('m13w.col.encashable')} [HRMS]`,
      `${t('m13w.col.effect')} [RULE]`,
      `${t('m13w.col.used')} [RULE]`,
      `${t('m13w.col.received')} [INTF]`,
    ]);
    expect(screen.getByText(t('m13.noPayEffect'))).toBeInTheDocument();
    // The captions tell the data: the encashable codes, and a type with no pay effect on M-20 as unmatched.
    expect(await screen.findByText(t('m13w.tile.unmatched', [1]))).toBeInTheDocument();
    expect(screen.getByText(t('m13w.tile.encashableOf', ['EL']))).toBeInTheDocument();
    const before = calls.length;
    await user.click(screen.getByRole('button', { name: t('m13w.lastSync') }));
    expect(await screen.findByRole('status')).toHaveTextContent('2026');
    expect(calls.length).toBe(before);
  });

  it('SRS_G_45_withNothingReceivedSyncSaysSo_aViewOnlyRoleHasExportExplained', async () => {
    backend({
      'GET /v1/leave-types/counts': () => ({ received: 0, encashable: 0, payReducing: 0, lastSync: null }),
      'GET /v1/leave-types': () => paged([]),
    });
    const user = userEvent.setup();
    wrap(<LeaveTypesScreen />, ['M-13:VIEW']);
    await screen.findByRole('heading', { name: t('m13w.title') });
    expect(await screen.findByText(t('m13w.tile.noUnmatched'))).toBeInTheDocument();
    expect(screen.getByText(t('m13w.tile.encashableSub'))).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: t('m13w.lastSync') }));
    expect(await screen.findByRole('status')).toHaveTextContent(t('m13w.syncNone'));
    expect(screen.getAllByRole('button', { name: t('i16w.export') })[0]).toHaveAttribute(
      'data-guard',
      t('m13w.why.export'),
    );
  });
});

const parameter = (over: object = {}) => ({
  id: 1,
  code: 'LWP_DIVISOR',
  leaveType: null,
  number: null,
  text: 'CALENDAR_DAYS',
  versionNo: 1,
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  status: 'ACTIVE',
  approvalId: 1,
  circularNumber: null,
  ...over,
});

describe('M-20 as drawn', () => {
  const versions = [
    parameter(),
    parameter({ id: 2, code: 'INSERVICE_PER_YEAR_LIMIT', text: null, number: 20 }),
    parameter({
      id: 3,
      code: 'INSERVICE_PER_YEAR_LIMIT',
      text: null,
      number: 30,
      status: 'PENDING_APPROVAL',
      versionNo: 2,
    }),
    parameter({ id: 4, code: 'PAY_EFFECT', leaveType: 'EL', text: 'NO_EFFECT' }),
  ];

  it('SRS_LV_14_theSevenParametersAreDrawn_aPerTypeVersionStaysInTheRegister', () => {
    const rows = drawnParameters(versions);
    expect(rows).toHaveLength(7);
    expect(rows.find((r) => r.code === 'INSERVICE_PER_YEAR_LIMIT')?.pending?.number).toBe(30);
    expect(rows.find((r) => r.code === 'ENCASH_DIVISOR')?.current).toBeNull();
    expect(rows.some((r) => r.code === 'PAY_EFFECT')).toBe(false);
  });

  it('SRS_LV_14_aParameterWithNoVersionIsNotSeeded_theTilesCountWhatIsInForce', async () => {
    backend({ 'GET /v1/leave-pay-parameters': () => paged(versions) });
    wrap(<LeaveParametersScreen />, ['M-20:VIEW']);
    expect(await screen.findByText('CALENDAR_DAYS')).toBeInTheDocument();
    expect(screen.getByText(t('m20w.tile.parameters')).closest('.tile')).toHaveTextContent('7');
    expect(screen.getByText(t('m20w.tile.seeded')).closest('.tile')).toHaveTextContent('2');
    expect(screen.getByText(t('m15w.tile.pending')).closest('.tile')).toHaveTextContent('1');
    expect(screen.getAllByText(t('mst.notSeeded')).length).toBe(5);
    const add = screen.getByRole('button', { name: t('m20w.newVersion') });
    expect(add).toHaveAttribute('data-why', t('m20w.why.ruleAdmin'));
  });

  it('SRS_LV_14_aProposalWaitingBlocksEdit_aRowEditOpensTheRegister', async () => {
    backend({
      'GET /v1/leave-pay-parameters': () => paged(versions),
      'GET /v1/masters/circulars/current': () => [],
    });
    const user = userEvent.setup();
    wrap(
      <LeaveParametersScreen />,
      me(['M-20:VIEW', 'M-20:CREATE'], { locationCode: 'HQ001', locationType: 'HQ' } as never),
    );
    await screen.findByText('CALENDAR_DAYS');
    const pending = document.querySelector('[data-reference="INSERVICE_PER_YEAR_LIMIT"]') as HTMLElement;
    expect(within(pending).getByRole('button', { name: new RegExp(t('common.edit')) })).toBeDisabled();
    const open = document.querySelector('[data-reference="LWP_DIVISOR"]') as HTMLElement;
    await user.click(within(open).getByRole('button', { name: new RegExp(t('common.edit')) }));
    await waitFor(() => expect(screen.getByTestId('m20-register')).toHaveAttribute('open'));
  });

  it('SRS_LV_14_viewShowsTheVersionInForceAndTheProposal', async () => {
    backend({ 'GET /v1/leave-pay-parameters': () => paged(versions) });
    const user = userEvent.setup();
    wrap(<LeaveParametersScreen />, ['M-20:VIEW']);
    await screen.findByText('CALENDAR_DAYS');
    const row = document.querySelector('[data-reference="INSERVICE_PER_YEAR_LIMIT"]') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: new RegExp(t('common.view')) }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('20')).toBeInTheDocument();
    expect(within(dialog).getByText('30')).toBeInTheDocument();
  });
});

const event = (over: object = {}) => ({
  applicationId: 1,
  sanctionRef: 'HR/LV/2026/88410',
  eis: '881402',
  name: 'Anita Banerjee',
  office: 'DH001 - Delhi',
  leaveType: 'LWP',
  leaveTypeName: 'Leave Without Pay',
  from: '2026-10-05',
  to: '2026-10-08',
  days: 4,
  eventType: 'ADDITION',
  received: '2026-10-09T10:00:00',
  payEffect: 'FULL_DEDUCTION',
  status: 'APPLIED',
  appliedIn: '2026-10',
  rule: 'BR-LEAVE-007',
  effectAmount: null,
  ...over,
});

function events(rows: unknown[]) {
  return backend({
    'GET /v1/reference/lists': () => REFERENCE,
    'GET /v1/leave-events/counts': () => ({ received: 5, applied: 2, backDated: 2, held: 1 }),
    'GET /v1/leave-events': () => paged(rows),
  });
}

describe('I-11 as drawn', () => {
  it('US_GAP_59_aBackDatedEventShowsItsAmountAndRule_anOpenOneItsEffect_aPendingOneIsNotApplied', async () => {
    events([
      event(),
      event({ applicationId: 2, status: 'BACK_DATED', rule: 'BR-LEAVE-010', effectAmount: -13905 }),
      event({ applicationId: 3, status: 'BACK_DATED', rule: 'BR-LEAVE-010', effectAmount: 250 }),
      event({ applicationId: 4, status: 'PENDING', payEffect: null, rule: null, appliedIn: null }),
    ]);
    wrap(<LeaveEventsScreen />, ['I-11:VIEW']);
    await screen.findByText(t('i11w.notApplied'));
    expect(screen.getByText(new RegExp(`${t('i11w.recovery', ['']).trim()}.*13,905`))).toBeInTheDocument();
    expect(screen.getByText(new RegExp(`${t('i11w.arrear', ['']).trim()}.*250`))).toBeInTheDocument();
    expect(screen.getByText(t('i11w.notApplied'))).toBeInTheDocument();
    expect(screen.getAllByText(t('i11w.backDated', ['BR-LEAVE-010']))).toHaveLength(2);
    expect(screen.getByText(t('i11w.tile.held')).closest('.tile')).toHaveTextContent('1');
  });

  it('SRS_LV_23_theReportBuildsItsAddressFromTheFilters_aRoleWithoutExportIsTold', async () => {
    events([event()]);
    const user = userEvent.setup();
    wrap(<LeaveEventsScreen />, ['I-11:VIEW', 'I-11:EXPORT']);
    await screen.findByText('HR/LV/2026/88410');
    await user.click(screen.getByRole('button', { name: t('i11w.report') }));
    await waitFor(() => expect(screen.getByTestId('i11-report')).toHaveAttribute('open'));
    await user.type(screen.getByLabelText(t('m01.eis')), '881403');
    await user.selectOptions(screen.getByLabelText(t('i11w.eventType')), 'LWP');
    expect(screen.getByRole('button', { name: t('i11w.reportName') })).toBeEnabled();
  });

  it('SRS_LV_23_aRoleWithoutExportHasTheReportExplained', async () => {
    events([event()]);
    const user = userEvent.setup();
    wrap(<LeaveEventsScreen />, ['I-11:VIEW']);
    await screen.findByText('HR/LV/2026/88410');
    await user.click(screen.getByRole('button', { name: t('i11w.report') }));
    const button = await screen.findByRole('button', { name: t('i11w.reportName') });
    expect(button).toHaveAttribute('data-guard', t('i11w.why.export'));
  });

  it('SRS_LV_33_nothingIsKeyedHere_theFiltersStayInTheClosedRegister', async () => {
    events([event()]);
    const user = userEvent.setup();
    wrap(<LeaveEventsScreen />, ['I-11:VIEW']);
    await screen.findByText('HR/LV/2026/88410');
    expect(screen.queryByLabelText(t('i11.status'))).not.toBeInTheDocument();
    await user.click(within(screen.getByTestId('i11-register')).getByText(t('i11w.register')));
    expect(await screen.findByLabelText(t('i11.status'))).toBeInTheDocument();
  });
});
