import { date } from '@/shared/ui';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  ConsolidatedMis,
  EmployeeMaster,
  EsevaRecord,
  LifecycleEvents,
  StaffTransfers,
} from '@/modules/employee';
import { backend, me, problem, referenceLists, sent, t, wrap, paged } from './support/harness';

/**
 * The employee screens: M-01 list and dossier (scope on the server, Payroll-owned changes under
 * maker-checker), E-01 own record, R-01 head count, I-17 life-cycle events and M-11 transfers.
 */

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', '/');
});

const governed = (answer: unknown) => (_url: string, init?: RequestInit) =>
  (JSON.parse(String(init?.body)) as { otp?: unknown }).otp ? answer : problem(428, 'AUTH-428-OTP', [7]);

async function answerOtp(code = '123456') {
  await userEvent.type(await screen.findByLabelText(t('otp.code')), code);
  await userEvent.click(screen.getByRole('button', { name: t('otp.verify') }));
}

async function cancelOtp() {
  await screen.findByLabelText(t('otp.code'));
  await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }));
  await waitFor(() => expect(screen.queryByLabelText(t('otp.code'))).toBeNull());
}

const REFERENCE = referenceLists({
  PAY_PATTERN: [
    ['IDA', 'IDA'],
    ['CDA', 'CDA'],
  ],
  EMPLOYEE_RECORD_STATUS: [
    ['ACTIVE', 'Active'],
    ['BANK_INVALID', 'Bank invalid'],
  ],
  EMPLOYMENT_STATUS: [['IN_SERVICE', 'In service']],
  LIFECYCLE_EVENT_TYPE: [
    ['SUSPENSION', 'Suspension'],
    ['SALARY_RELEASE', 'Salary release'],
    ['DEPUTATION_OUT', 'Deputation out'],
    ['SEPARATION', 'Separation'],
  ],
});

const row = (over: object = {}) => ({
  eis: '104882',
  name: 'Usha Pillai',
  designation: 'Manager',
  band: 'E-4',
  location: 'DO Delhi',
  payPattern: 'IDA',
  employeeType: 'EMPLOYEE',
  status: 'ACTIVE',
  ...over,
});
const ROWS = [
  row(),
  row({
    eis: '104883',
    name: 'Prem Ghosh',
    status: 'BANK_INVALID',
    designation: null,
    band: null,
    location: null,
    payPattern: null,
  }),
  row({ eis: '104884', name: 'Alok Nair', status: 'PAYROLL_SETUP_PENDING' }),
];
const detail = (over: object = {}) => ({
  eis: '104882',
  firstName: 'Usha',
  lastName: 'Pillai',
  designation: 'Manager',
  band: 'E-4',
  location: 'DO Delhi',
  locationCode: 'DH001',
  payPattern: 'IDA',
  employeeType: 'EMPLOYEE',
  providentFundType: 'CPF',
  panMasked: '******123K',
  cpfCode: 'CPF/1',
  mobileNumber: '9876543210',
  emailAddress: 'usha.pillai@fci-demo.test',
  bankAccountMasked: '******4321',
  currentBasic: 56100,
  status: 'ACTIVE',
  employmentStatus: 'IN_SERVICE',
  changePending: false,
  nextIncrementDue: '2027-07-01',
  ...over,
});

const COUNTS = {
  total: 128,
  payrollSetupPending: 1,
  changePending: 2,
  panCpfMissing: 3,
  bankInvalid: 4,
  active: 118,
};

describe('M-01 Employee Payroll Master', () => {
  function routes(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
    return backend({
      'GET /v1/reference/lists': () => REFERENCE,
      // The server applies the Status filter before paging (SRS-NF-3).
      'GET /v1/employees?': (url: string) => {
        const status = /[?&]status=([A-Z_]+)/.exec(url)?.[1];
        return paged(
          ROWS.filter((r) => !status || r.status === status),
          undefined,
          url,
        );
      },
      'GET /v1/employees/counts': () => COUNTS,
      'GET /v1/employees/104882/allowance-flags/history': (url: string) => paged([], 0, url),
      'GET /v1/employees/104882/allowance-flags': () => ({ eis: '104882', joiningDate: null, flags: [] }),
      'GET /v1/employees/104883/allowance-flags/history': (url: string) => paged([], 0, url),
      'GET /v1/employees/104883/allowance-flags': () => ({ eis: '104883', joiningDate: null, flags: [] }),
      'GET /v1/employees/104882/control-flags': () => ({ eis: '104882', joiningDate: null, flags: [] }),
      'GET /v1/employees/104883/control-flags': () => ({ eis: '104883', joiningDate: null, flags: [] }),
      'GET /v1/employees/104882': () => detail(),
      'GET /v1/employees/104883': () =>
        detail({
          eis: '104883',
          firstName: 'Prem',
          lastName: null,
          changePending: true,
          panMasked: null,
          cpfCode: null,
          mobileNumber: null,
          emailAddress: null,
          bankAccountMasked: null,
          designation: null,
          band: null,
          location: null,
          payPattern: null,
        }),
      ...over,
    });
  }

  it('SRS-G-29: lists the rows in scope with tiles; filters by status and searches on the server', async () => {
    const calls = routes();
    wrap(<EmployeeMaster />, ['M-01:VIEW']);
    expect(await screen.findByText('Usha Pillai')).toBeTruthy();
    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
    // The tiles are the server's counts over the whole scope, not the rows of the page.
    expect(screen.getByText('128')).toBeTruthy();
    expect(screen.getByText('9')).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText(t('m01.status')), 'BANK_INVALID');
    await waitFor(() => expect(screen.queryByText('Usha Pillai')).toBeNull());
    expect(calls.some((c) => c.url.includes('status=BANK_INVALID'))).toBe(true);
    expect(screen.getByText('Prem Ghosh')).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText(t('m01.payPattern')), 'CDA');
    await userEvent.type(screen.getByLabelText(t('wf.searchInList')), 'Usha');
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('q=Usha') && c.url.includes('payPattern=CDA'))).toBe(true),
    );
  });

  it('an empty result says so; a refused list is shown', async () => {
    routes({ 'GET /v1/employees?': () => paged([]) });
    wrap(<EmployeeMaster />, ['M-01:VIEW']);
    expect(await screen.findByText(t('m01.none'))).toBeTruthy();
  });

  it('a refused list load is shown as the error', async () => {
    routes({ 'GET /v1/employees?': () => problem(403, 'AUTH-403-FORBIDDEN') });
    wrap(<EmployeeMaster />, ['M-01:VIEW']);
    expect(await screen.findByText(t('error.AUTH-403-FORBIDDEN'))).toBeTruthy();
    expect(screen.queryByText(t('e01.claimsInHrms'))).toBeNull();
  });

  it('SRS-1-52 / SRS-1-76: the dossier shows the field table groups; Aadhaar as its last four digits', async () => {
    routes({
      'GET /v1/employees/104882': () =>
        detail({
          payPattern: 'CDA',
          birthDate: '1980-05-02',
          joiningDate: '2005-07-01',
          retirementDate: '2040-05-31',
          gender: 'F',
          employeeCategory: 'REGULAR',
          cdaStaffCode: null,
          aadhaarMasked: 'XXXX-XXXX-1234',
          pranNumber: '110012345678',
          uanOnRecord: true,
          epsNumber: 'EPS-1',
          epsJoinDate: '2005-07-01',
          epfoMemberId: 'DL/CPM/1',
          epsPre2014Member: false,
          epsEntitlementDate: null,
          epsSeparationReason: 'SUPERANNUATION',
          epsSeparationDate: '2038-05-31',
          holdFlag: true,
          abscondingFlag: false,
          stopPaymentFlag: false,
          casualLabour: false,
          hraCityClass: 'Y',
          daRateApplied: 45,
          hraRateApplied: null,
          payStatus: 'HOLD',
        }),
    });
    wrap(<EmployeeMaster />, ['M-01:VIEW']);
    await screen.findByText('Usha Pillai');
    await userEvent.click(screen.getAllByRole('button', { name: /View record/ })[0] as HTMLElement);
    expect(await screen.findByText(t('m01.group.service'))).toBeTruthy();
    expect(screen.getByText(date('1980-05-02'))).toBeTruthy();
    expect(screen.getByText(date('2040-05-31'))).toBeTruthy();
    expect(screen.getAllByText('XXXX-XXXX-1234')[0]).toBeTruthy();
    expect(screen.getByText('110012345678')).toBeTruthy();
    expect(screen.getByText('45 %')).toBeTruthy();
    expect(screen.getByText(`SUPERANNUATION ${date('2038-05-31')}`)).toBeTruthy();
    const control = screen.getByLabelText(t('m01.group.control'));
    expect(within(control).getAllByText(t('common.yes'))).toHaveLength(1);
    expect(within(control).getAllByText(t('common.no'))).toHaveLength(3);
    // SRS l.3738 (US-GAP-275): the Pay Status, System, with its code and label.
    expect(within(control).getByText(`HOLD - ${t('m01.payStatus.HOLD')}`)).toBeTruthy();
    const statutory = screen.getByLabelText(t('m01.group.statutory'));
    expect(within(statutory).getByText(t('common.yes'))).toBeTruthy();
    expect(within(statutory).getByText(t('common.no'))).toBeTruthy();
  });

  it('US-GAP-40: a record from an older server without the groups shows dashes', async () => {
    routes();
    wrap(<EmployeeMaster />, ['M-01:VIEW']);
    await screen.findByText('Usha Pillai');
    await userEvent.click(screen.getAllByRole('button', { name: /View record/ })[0] as HTMLElement);
    const service = await screen.findByLabelText(t('m01.group.service'));
    expect(within(service).getAllByText('—').length).toBe(6);
    const control = screen.getByLabelText(t('m01.group.control'));
    expect(within(control).getAllByText('—').length).toBe(5);
  });

  it('SRS-G-45: the dossier shows masked data and offers no edit of an HRMS-owned field, even with M-01 EDIT', async () => {
    const calls = routes();
    wrap(<EmployeeMaster />, ['M-01:VIEW', 'M-01:EDIT']);
    await screen.findByText('Usha Pillai');
    await userEvent.click(screen.getAllByRole('button', { name: /View record/ })[0] as HTMLElement);
    expect(await screen.findByText(t('m01.dossier', ['Usha Pillai', '104882']))).toBeTruthy();
    expect(screen.getByText('******123K')).toBeTruthy();
    // SRS-13-1 (US-GAP-51): the next increment due date, read-only from I-05.
    expect(screen.getByText(t('m01.nextIncrementDue'))).toBeTruthy();
    expect(screen.getByText(date('2027-07-01'))).toBeTruthy();
    expect(screen.getByText('In service')).toBeTruthy();
    // CLAUDE.md section 2, R-SRC (plan 16 D16-19): contact, bank and PAN change only through I-21.
    expect(screen.queryByRole('button', { name: t('action.m01.editRecord') })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    expect(screen.queryByText(t('m01.dossier', ['Usha Pillai', '104882']))).toBeNull();
    expect(calls.some((c) => c.method === 'POST' && c.url.includes('/changes'))).toBe(false);
  });

  it('a record with a change pending from before shows the note; a deep link (?eis=) opens the dossier', async () => {
    window.history.replaceState(null, '', '/payroll?s=M-01&eis=104883');
    routes();
    wrap(<EmployeeMaster />, ['M-01:VIEW', 'M-01:EDIT']);
    expect(await screen.findByText(t('m01.dossier', ['Prem', '104883']))).toBeTruthy();
    expect(screen.getByText(t('m01.pendingNote'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: t('action.m01.editRecord') })).toBeNull();
  });

  // NEW-G1-07 (SRS-G-53): the previous unit reads the record only: the note names the transfer
  // date and no flag panel is offered; the own unit's record still shows its panels.
  it('SRS-G-53: a member transferred out is read-only for the previous unit, with the transfer date', async () => {
    window.history.replaceState(null, '', '/payroll?s=M-01&eis=104884');
    const calls = routes({
      'GET /v1/employees/104884': () => detail({ eis: '104884', transferredOut: '2026-10-15' }),
    });
    wrap(<EmployeeMaster />, ['M-01:VIEW', 'M-01:EDIT']);
    expect(await screen.findByTestId('m01-previous-unit')).toHaveTextContent(
      t('m01.previousUnit', [date('2026-10-15')]),
    );
    expect(screen.queryByText(t('m01.flags.title'))).toBeNull();
    expect(screen.queryByText(t('m01.control.title'))).toBeNull();
    expect(calls.some((c) => c.url.includes('/v1/employees/104884/allowance-flags'))).toBe(false);
    window.history.replaceState(null, '', '/payroll?s=M-01&eis=104882');
  });

  it('SRS-G-53: the own unit sees no previous-unit note and keeps the flag panels', async () => {
    window.history.replaceState(null, '', '/payroll?s=M-01&eis=104882');
    routes();
    wrap(<EmployeeMaster />, ['M-01:VIEW', 'M-01:EDIT']);
    expect(await screen.findByText(t('m01.flags.title'))).toBeTruthy();
    expect(screen.queryByTestId('m01-previous-unit')).toBeNull();
    window.history.replaceState(null, '', '/');
  });

  // SC-16-0149 / SRS-1-12: a returned record is back here in Payroll Setup Pending and resubmitted.
  it('SRS-1-11: HR submits a record in Payroll Setup Pending with the CDA Staff Code of a CDA member', async () => {
    window.history.replaceState(null, '', '/payroll?s=M-01&eis=104883');
    const calls = routes({
      'GET /v1/employees/104883': () =>
        detail({
          eis: '104883',
          firstName: 'Prem',
          lastName: 'Ghosh',
          payPattern: 'CDA',
          status: 'PAYROLL_SETUP_PENDING',
        }),
      'POST /v1/employees/104883/onboarding-submissions': () => ({
        status: 202,
        body: { approvalId: 61, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<EmployeeMaster />, ['M-01:VIEW', 'M-01:SUBMIT']);
    expect(await screen.findByText(t('m01.setupPendingNote'))).toBeTruthy();
    const send = screen.getByRole('button', { name: t('action.m01.onboard') });
    expect(send.getAttribute('title')).toBe(t('m01.why.cdaStaffCode'));
    await userEvent.type(screen.getByLabelText(new RegExp(`^${t('m01.cdaStaffCode')}`)), 'CDA/DL/77');
    expect(send.getAttribute('title')).toBe(t('m01.why.onboardReason'));
    await userEvent.type(screen.getByLabelText(new RegExp(`^${t('m01.onboardReason')}`)), 'Flags set');
    await userEvent.click(send);
    expect(await screen.findByText(t('m01.onboardSent'))).toBeTruthy();
    expect(sent(calls, 'POST', '/onboarding-submissions').at(-1)).toEqual({
      cdaStaffCode: 'CDA/DL/77',
      reason: 'Flags set',
    });
  });

  it('SRS-1-11: an IDA record needs no CDA Staff Code; a viewer cannot submit; a refusal is shown', async () => {
    window.history.replaceState(null, '', '/payroll?s=M-01&eis=104882');
    const calls = routes({
      'GET /v1/employees/104882': () => detail({ status: 'PAYROLL_SETUP_PENDING' }),
      'POST /v1/employees/104882/onboarding-submissions': () => problem(409, 'EMP-409-ONBOARD', ['ACTIVE']),
    });
    wrap(<EmployeeMaster />, ['M-01:VIEW', 'M-01:SUBMIT']);
    expect(await screen.findByText(t('m01.setupPendingNote'))).toBeTruthy();
    expect(screen.queryByLabelText(new RegExp(`^${t('m01.cdaStaffCode')}`))).toBeNull();
    await userEvent.type(screen.getByLabelText(new RegExp(`^${t('m01.onboardReason')}`)), 'Ready');
    await userEvent.click(screen.getByRole('button', { name: t('action.m01.onboard') }));
    expect(await screen.findByText(t('error.EMP-409-ONBOARD', ['ACTIVE']))).toBeTruthy();
    expect(sent(calls, 'POST', '/onboarding-submissions').at(-1)).toEqual({
      cdaStaffCode: null,
      reason: 'Ready',
    });
  });

  it('SRS-1-11: without M-01 SUBMIT the submission is disabled with its reason', async () => {
    window.history.replaceState(null, '', '/payroll?s=M-01&eis=104882');
    routes({ 'GET /v1/employees/104882': () => detail({ status: 'PAYROLL_SETUP_PENDING' }) });
    wrap(<EmployeeMaster />, ['M-01:VIEW']);
    const send = await screen.findByRole('button', { name: t('action.m01.onboard') });
    expect(send).toBeDisabled();
    expect(send.getAttribute('title')).toBe(t('reason.hrOnly'));
  });

  it('SRS-G-29: a record outside the scope is refused in the dossier; a viewer cannot edit', async () => {
    routes({ 'GET /v1/employees/104882': () => problem(403, 'AUTH-403-OUT-OF-SCOPE') });
    wrap(<EmployeeMaster />, ['M-01:VIEW']);
    await screen.findByText('Usha Pillai');
    await userEvent.click(screen.getAllByRole('button', { name: /View record/ })[0] as HTMLElement);
    expect(await screen.findByText(t('m01.dossierLoading', ['104882']))).toBeTruthy();
    expect(await screen.findAllByText(t('error.AUTH-403-OUT-OF-SCOPE'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: t('action.m01.editRecord') })).toBeNull();
  });
});

describe('E-01 eSeva and R-01 consolidated MIS', () => {
  it('SRS-G-35: the eSeva member sees the own record only', async () => {
    backend({ 'GET /v1/eseva/me': () => detail() });
    wrap(<EsevaRecord />, ['E-01:VIEW']);
    expect(await screen.findByText(t('e01.title', ['Usha Pillai']))).toBeTruthy();
    expect(screen.getByText('******4321')).toBeTruthy();
    expect(screen.getByText('CPF/1')).toBeTruthy();
  });

  it('a record with missing data shows dashes; a refusal is shown', async () => {
    backend({
      'GET /v1/eseva/me': () =>
        detail({
          lastName: null,
          location: null,
          designation: null,
          band: null,
          payPattern: null,
          panMasked: null,
          cpfCode: null,
          mobileNumber: null,
          emailAddress: null,
          bankAccountMasked: null,
          providentFundType: null,
        }),
    });
    wrap(<EsevaRecord />, ['E-01:VIEW']);
    expect(await screen.findByText(t('e01.title', ['Usha']))).toBeTruthy();
    expect(screen.getAllByText('—').length).toBeGreaterThan(5);
  });

  it('SRS-17-2: an employee member is told that employee claims are made in HRMS, and no claim is offered', async () => {
    backend({ 'GET /v1/eseva/me': () => detail() });
    wrap(<EsevaRecord />, ['E-01:VIEW']);
    expect(await screen.findByText(t('e01.claimsInHrms'))).toBeTruthy();
    expect(screen.getByRole('note')).toHaveTextContent(t('e01.claimsInHrms'));
    expect(screen.queryByRole('button', { name: /claim/i })).toBeNull();
  });

  it('an eSeva refusal is shown', async () => {
    backend({ 'GET /v1/eseva/me': () => problem(403, 'AUTH-403-FORBIDDEN') });
    wrap(<EsevaRecord />, ['E-01:VIEW']);
    expect(await screen.findByText(t('error.AUTH-403-FORBIDDEN'))).toBeTruthy();
  });

  it('SRS-22-3: R-01 counts per location with totals; the consolidated view is read-only', async () => {
    backend({
      // The totals are the server's, over every office of the scope (X-pagination).
      'GET /v1/mis/headcount': () => ({
        ...paged([
          {
            location: 'DO Delhi',
            locationCode: 'DH001',
            byStatus: { ACTIVE: 7, CHANGE_PENDING: 1 },
            total: 8,
          },
          {
            location: 'DO Kolkata',
            locationCode: 'KL001',
            byStatus: { ACTIVE: 3, PAYROLL_SETUP_PENDING: 2 },
            total: 5,
          },
        ]),
        totals: { ACTIVE: 10, CHANGE_PENDING: 1, PAYROLL_SETUP_PENDING: 2 },
        grandTotal: 13,
      }),
    });
    wrap(<ConsolidatedMis />, me(['R-01:VIEW'], { readOnlyConsolidated: true }));
    expect(await screen.findByText('DO Kolkata')).toBeTruthy();
    expect(screen.getByText(t('r01.readOnly'))).toBeTruthy();
    const total = screen.getAllByRole('row').at(-1) as HTMLElement;
    expect(within(total).getByText('10')).toBeTruthy();
    expect(within(total).getByText('13')).toBeTruthy();
  });

  it('R-01 without rows says so', async () => {
    backend({ 'GET /v1/mis/headcount': () => ({ ...paged([]), totals: {}, grandTotal: 0 }) });
    wrap(<ConsolidatedMis />, ['R-01:VIEW']);
    expect(await screen.findByText(t('r01.none'))).toBeTruthy();
    expect(screen.queryByText(t('r01.readOnly'))).toBeNull();
  });
});

describe('I-17 life-cycle events', () => {
  const evt = (over: object = {}) => ({
    id: 1,
    eis: '104882',
    employeeName: 'Usha Pillai',
    eventType: 'SUSPENSION',
    eventDate: '2026-10-01',
    endDate: '2026-12-31',
    reason: 'Inquiry',
    subsistencePercent: 50,
    releaseFromYymm: null,
    releaseToYymm: null,
    orderReference: 'ORD/1',
    source: 'MANUAL',
    createdBy: 'hr@fci-demo.test',
    status: 'PENDING_APPROVAL',
    approvalId: 3,
    hrmsChangeTaskId: null,
    hrmsRequestId: null,
    interveningPeriod: null,
    ...over,
  });
  function routes(
    post: (url: string, init?: RequestInit) => unknown = governed({ status: 202, body: evt() }),
  ) {
    return backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/lifecycle-events': () =>
        paged([
          evt(),
          evt({
            id: 2,
            eventType: 'SALARY_RELEASE',
            endDate: null,
            subsistencePercent: null,
            releaseFromYymm: '202601',
            releaseToYymm: '202603',
            orderReference: null,
            status: 'APPROVED',
          }),
          evt({ id: 3, subsistencePercent: null, orderReference: null, status: 'REJECTED' }),
        ]),
      'GET /v1/employees/104882/lifecycle-events': () => paged([evt({ status: 'RETURNED' })]),
      'GET /v1/employees/999/lifecycle-events': () => problem(403, 'AUTH-403-OUT-OF-SCOPE'),
      'POST /v1/employees/': post,
    });
  }

  it('lists recent events with their details and status; looks up one employee', async () => {
    routes();
    wrap(<LifecycleEvents />, ['I-17:VIEW']);
    expect(await screen.findByText(t('evt.subsistenceValue', [50]), { exact: false })).toBeTruthy();
    expect(screen.getByText('202601–202603')).toBeTruthy();
    expect(screen.getByText(t('status.REJECTED'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: t('common.submitForApproval') })).toBeNull();
    const show = screen.getByRole('button', { name: t('evt.show') }) as HTMLButtonElement;
    expect(show.disabled).toBe(true);
    await userEvent.type(screen.getByLabelText(t('evt.eis')), ' 104882 ');
    await userEvent.click(show);
    expect(await screen.findByText(t('status.RETURNED'))).toBeTruthy();
    await userEvent.clear(screen.getByLabelText(t('evt.eis')));
    await userEvent.type(screen.getByLabelText(t('evt.eis')), '999');
    await userEvent.click(show);
    expect(await screen.findByText(t('error.AUTH-403-OUT-OF-SCOPE'))).toBeTruthy();
  });

  it('an empty list says so; a refused list is shown', async () => {
    backend({ 'GET /v1/reference/lists': () => REFERENCE, 'GET /v1/lifecycle-events': () => paged([]) });
    wrap(<LifecycleEvents />, ['I-17:VIEW']);
    expect(await screen.findByText(t('evt.none'))).toBeTruthy();
  });

  it('SRS-1-63: I-17 is read-only for every role: no keying form, HRMS sources, the linked I-21 task', async () => {
    backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/lifecycle-events': () =>
        paged([
          evt({
            id: 7,
            source: 'HRMS',
            hrmsChangeTaskId: 41,
            hrmsRequestId: 'HRMS-REQ-41',
            eventType: 'REINSTATEMENT',
            interveningPeriod: 'DUTY',
          }),
          evt({ id: 8, source: 'PAYROLL', hrmsChangeTaskId: null }),
        ]),
    });
    // Even a role that once held I-17 CREATE gets no form (SRS l.3871 "all are read-only").
    wrap(<LifecycleEvents />, ['I-17:VIEW', 'I-17:CREATE', 'I-17:EDIT']);
    const link = await screen.findByRole('link', { name: t('evt.taskLink', [41]) });
    expect(link.getAttribute('href')).toContain('s=I-21&task=41');
    expect(screen.getByText('HRMS-REQ-41')).toBeTruthy();
    expect(screen.getByText(t('evt.intervening', ['DUTY']), { exact: false })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /record/i })).toBeNull();
    expect(screen.queryByText(t('common.submitForApproval'))).toBeNull();
    expect(document.querySelector('#evt-type, #evt-date, #evt-reason')).toBeNull();
    // An event keyed before stays listed; without a task its link cell is a dash.
    const rows = screen.getAllByRole('row');
    expect(rows.some((r) => within(r).queryAllByText('—').length > 0)).toBe(true);
    // The headers carry the HRMS source, never ENT.
    const headers = screen.getAllByRole('columnheader').map((h) => h.textContent ?? '');
    expect(headers.join(' ')).not.toContain('ENT');
    expect(screen.getByRole('link', { name: t('evt.w.tasks') }).getAttribute('href')).toContain('s=I-21');
    expect(screen.getByRole('link', { name: t('evt.w.fallback') }).getAttribute('href')).toContain('s=X-07');
  });
});

describe('M-11 staff transfers', () => {
  const trf = (over: object = {}) => ({
    id: 1,
    eis: '104882',
    employeeName: 'Usha Pillai',
    fromLocation: 'DH001',
    toLocation: 'KL001',
    transferDate: '2026-10-01',
    relievingDate: null,
    joiningDate: '2026-10-05',
    joiningTime: 'AN',
    orderReference: 'TRF/1',
    lpcReference: 'LPC/1',
    splitWage: true,
    fromUnitDays: 4,
    toUnitDays: 27,
    reason: 'Transfer',
    status: 'APPROVED',
    approvalId: 3,
    ...over,
  });
  function routes(
    post: (url: string, init?: RequestInit) => unknown = governed({ status: 202, body: trf() }),
  ) {
    return backend({
      'GET /v1/transfers/destinations': () => [
        { code: 'KL001', label: 'DO Kolkata', type: 'DO' },
        { code: 'CH001', label: 'DO Chennai', type: 'DO' },
      ],
      'GET /v1/transfers': () =>
        paged([
          trf(),
          trf({
            id: 2,
            fromLocation: null,
            toLocation: null,
            joiningDate: null,
            joiningTime: null,
            orderReference: null,
            lpcReference: null,
            splitWage: true,
            fromUnitDays: null,
            toUnitDays: null,
            status: 'PENDING_APPROVAL',
          }),
        ]),
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'POST /v1/employees/104882/transfer-orders': () => ({
        status: 201,
        body: { attachmentId: 41, provisionalKey: 'PROVISIONAL:7c9e6679-7425-40de-944b-e07fc1f90ae7' },
      }),
      'POST /v1/employees/': post,
    });
  }

  it('lists transfers with split wages and LPC; a viewer records nothing', async () => {
    routes();
    wrap(<StaffTransfers />, ['M-11:VIEW']);
    expect(await screen.findByText(t('trf.splitDays', [4, 27]))).toBeTruthy();
    expect(screen.getByText(t('trf.splitDays', [0, 0]))).toBeTruthy();
    expect(screen.getByText(t('trf.lpcShort', ['LPC/1']))).toBeTruthy();
    expect(screen.getAllByText('— → —')[0]).toBeTruthy();
    expect(screen.queryByRole('button', { name: t('trf.record') })).toBeNull();
    // SRS-20-29 (US-03-07 AC1): a viewer opens each transfer's change history.
    expect(screen.getByRole('button', { name: t('history.for', ['104882 DH001 → KL001']) })).toBeTruthy();
    expect(screen.getByRole('button', { name: t('history.for', ['104882 — → —']) })).toBeTruthy();
  });

  it('an empty list says so', async () => {
    backend({ 'GET /v1/transfers': () => paged([]) });
    wrap(<StaffTransfers />, ['M-11:VIEW']);
    expect(await screen.findByText(t('trf.none'))).toBeTruthy();
  });

  it('SRS-G-1: a transfer with split wages is recorded with the OTP; the form then resets', async () => {
    const calls = routes();
    wrap(<StaffTransfers />, ['M-11:VIEW', 'M-11:CREATE']);
    await userEvent.click(await screen.findByRole('button', { name: t('trf.record') }));
    await screen.findByRole('option', { name: 'DO Kolkata' });
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') }) as HTMLButtonElement;
    await userEvent.type(screen.getByLabelText(t('evt.eis')), '104882');
    await userEvent.selectOptions(screen.getByLabelText(t('trf.to')), 'KL001');
    await userEvent.type(screen.getByLabelText(t('trf.date')), '2026-10-01');
    await userEvent.type(screen.getByLabelText(t('trf.relieving')), '2026-10-04');
    await userEvent.type(screen.getByLabelText(t('trf.joining')), '2026-10-05');
    // A time picker (HH:MM, as the server checks): the field is type time (UI audit 2026-11-02).
    fireEvent.change(screen.getByLabelText(t('trf.joiningTime')), { target: { value: '14:30' } });
    await userEvent.type(screen.getByLabelText(t('evt.order'), { selector: '#trf-order' }), 'TRF/9');
    await userEvent.click(screen.getByLabelText(t('m11.heldInHrms')));
    // SRS l.1352: the LPC Reference is System, read-only - never typed on the form.
    expect(screen.queryByLabelText(t('trf.lpc'))).toBeNull();
    await userEvent.type(screen.getByLabelText(t('trf.newNumber')), 'K-1');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Rotation');
    await userEvent.click(screen.getByLabelText(t('trf.splitWage')));
    expect(submit.disabled).toBe(true);
    await userEvent.type(screen.getByLabelText(t('trf.fromDays')), '4');
    await userEvent.type(screen.getByLabelText(t('trf.toDays')), '27');
    await userEvent.click(submit);
    await answerOtp();
    expect(await screen.findByText(t('trf.submitted', ['104882']))).toBeTruthy();
    expect(sent(calls, 'POST', '/104882/transfers').at(-1)).toEqual({
      toLocation: 'KL001',
      transferDate: '2026-10-01',
      relievingDate: '2026-10-04',
      joiningDate: '2026-10-05',
      joiningTime: '14:30',
      orderReference: 'TRF/9',
      newStaffNumber: 'K-1',
      splitWage: true,
      fromUnitDays: 4,
      toUnitDays: 27,
      reason: 'Rotation',
      orderHeldInHrms: true,
      otp: { challengeId: 7, code: '123456' },
    });
    await userEvent.click(screen.getByRole('button', { name: t('trf.record') }));
    expect((screen.getByLabelText(t('evt.eis')) as HTMLInputElement).value).toBe('');
  });

  it('SRS-16-18 / SRS-G-31: without HRMS holding it, the order copy is stored first and the transfer names it', async () => {
    const calls = routes();
    wrap(<StaffTransfers />, ['M-11:VIEW', 'M-11:CREATE']);
    await userEvent.click(await screen.findByRole('button', { name: t('trf.record') }));
    await screen.findByRole('option', { name: 'DO Kolkata' });
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') }) as HTMLButtonElement;
    await userEvent.type(screen.getByLabelText(t('evt.eis')), '104882');
    await userEvent.selectOptions(screen.getByLabelText(t('trf.to')), 'KL001');
    await userEvent.type(screen.getByLabelText(t('trf.date')), '2026-10-01');
    await userEvent.type(screen.getByLabelText(t('evt.order'), { selector: '#trf-order' }), 'TRF/12');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Rotation');
    // No copy yet: not sendable.
    expect(submit.disabled).toBe(true);
    const copy = screen.getByRole('group', { name: t('m11.orderCopy') });
    await userEvent.selectOptions(within(copy).getByLabelText(t('ord.type')), 'OTHER');
    await userEvent.type(within(copy).getByLabelText(t('ord.date')), '2026-09-30');
    await userEvent.type(within(copy).getByLabelText(t('ord.authority')), 'GM (P)');
    await userEvent.upload(
      within(copy).getByLabelText(t('ord.file')),
      new File(['%PDF-1.7'], 'transfer.pdf', { type: 'application/pdf' }),
    );
    await userEvent.click(submit);
    await answerOtp();
    expect(await screen.findByText(t('trf.submitted', ['104882']))).toBeTruthy();
    const stored = calls.find((c) => c.method === 'POST' && c.url.includes('/transfer-orders'))
      ?.body as FormData;
    expect(stored.get('reference')).toBe('TRF/12');
    expect(sent(calls, 'POST', '/104882/transfers').at(-1)).toMatchObject({
      orderReference: 'TRF/12',
      orderHeldInHrms: false,
      orderAttachmentId: 41,
      orderProvisionalKey: 'PROVISIONAL:7c9e6679-7425-40de-944b-e07fc1f90ae7',
    });
    // The copy was stored once, though the record was sent again with the OTP.
    expect(calls.filter((c) => c.method === 'POST' && c.url.includes('/transfer-orders'))).toHaveLength(1);
  });

  it('a transfer without split wages sends no days; refusal, cancelled OTP, cancel', async () => {
    let refuse = true;
    const calls = routes((url, init) =>
      refuse ? governed(problem(409, 'TRF-409-PENDING'))(url, init) : problem(428, 'AUTH-428-OTP', [7]),
    );
    wrap(<StaffTransfers />, ['M-11:VIEW', 'M-11:CREATE']);
    await userEvent.click(await screen.findByRole('button', { name: t('trf.record') }));
    await screen.findByRole('option', { name: 'DO Chennai' });
    await userEvent.type(screen.getByLabelText(t('evt.eis')), '104882');
    await userEvent.selectOptions(screen.getByLabelText(t('trf.to')), 'CH001');
    await userEvent.type(screen.getByLabelText(t('trf.date')), '2026-10-01');
    await userEvent.type(screen.getByLabelText(t('evt.order'), { selector: '#trf-order' }), 'TRF/1');
    await userEvent.click(screen.getByLabelText(t('m11.heldInHrms')));
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'x');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await answerOtp();
    expect(await screen.findByText(t('error.TRF-409-PENDING'))).toBeTruthy();
    expect(sent(calls, 'POST', '/transfers').at(-1)).toMatchObject({
      splitWage: false,
      fromUnitDays: null,
      relievingDate: null,
      newStaffNumber: null,
    });
    refuse = false;
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await cancelOtp();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByLabelText(t('trf.to'))).toBeNull();
  });
});
