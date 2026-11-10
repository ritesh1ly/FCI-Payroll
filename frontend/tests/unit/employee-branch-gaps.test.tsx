import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  ConsolidatedMis,
  EmployeeMaster,
  HrmsChangeApproval,
  HrmsUpdateRequests,
  LifecycleEvents,
  StaffTransfers,
} from '@/modules/employee';
import { AllowanceFlags } from '@/modules/employee/components/AllowanceFlags';
import { ControlFlags } from '@/modules/employee/components/ControlFlags';
import { todayIso } from '@/modules/general';
import { backend, me, paged, problem, referenceLists, t, wrap } from './support/harness';

/**
 * The employee screens at their branch edges (SRS-1-7, SRS-1-11, SRS-1-28, SRS-1-63, SRS-1-67,
 * SRS-1-70, SRS-16-18, SRS-22-3, SRS-G-31): an order shown without its date, an order copy without a
 * reference, a copy file cleared, a flag switched from Yes, a member without a joining date, the
 * document types that failed to load, a transfer without its EIS, a release without its end month,
 * a head count whose totals miss a status, a session without an office.
 */

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/payroll',
  useSearchParams: () => new URLSearchParams(),
}));

afterEach(() => vi.unstubAllGlobals());

const DOC_TYPES = [{ code: 'OTHER', name: 'Other document', referenceRequired: false }];

// ---------------------------------------------------------------- I-21

const task = (over: object = {}) => ({
  id: 7,
  taskNo: 'HCA-2026-0007',
  eis: '100041',
  employee: 'Asha Rao',
  fieldCode: 'PAN',
  fieldLabel: 'PAN',
  fieldGroup: 'IDENTITY_STATUTORY',
  currentValue: '******234F',
  newValue: '******567Z',
  effectiveDate: '2026-10-20',
  hrmsRequestId: 'HRMS-1',
  source: 'HRMS_PUSH',
  status: 'PENDING_ORDER_DETAILS',
  targetPeriod: '2026-10',
  receivedBeforeFreeze: true,
  receivedAt: '2026-10-21T05:00:00Z',
  daysPending: 5,
  otpRequired: false,
  orderNo: null,
  orderDate: null,
  issuingAuthority: null,
  orderHeldInHrms: false,
  makerUser: null,
  checkerUser: null,
  returnReason: null,
  rejectionReason: null,
  approvalId: null,
  orderRef: null,
  overdue: false,
  hrmsReason: null,
  ...over,
});

const COUNTS = { total: 2, byStatus: [{ code: 'PENDING_ORDER_DETAILS', count: 2 }], byField: [] };
const MAKER = ['I-21:VIEW', 'I-21:SUBMIT', 'I-21:SUBMIT_OTP', 'I-21:SUBMIT_HQ'];

describe('I-21 HRMS change approval: branch edges', () => {
  it('SRS_G_31_anOrderWithoutItsDateShowsItsNumberAndAuthorityAndACopyWithoutReferenceShowsNone', async () => {
    backend({
      'GET /v1/hrms-change-tasks/counts': () => COUNTS,
      'GET /v1/hrms-change-tasks?': (url) =>
        paged([task({ orderNo: 'EST/9', orderDate: null, issuingAuthority: 'GM (P)' })], 1, url),
      'GET /v1/hrms-change-tasks/7/documents': () => [
        {
          id: 3,
          documentTypeName: 'Other document',
          referenceNumber: null,
          orderDate: '2026-10-20',
          fileName: 'order.pdf',
          uploadedBy: 'maker.delhi',
          uploadedAt: '2026-10-21T05:00:00Z',
        },
      ],
      'GET /v1/document-types': () => DOC_TYPES,
      'GET /v1/hrms-change-tasks/7': () => ({ task: task(), dob: null, applicable: true }),
    });
    wrap(<HrmsChangeApproval />, me(MAKER));
    expect(await screen.findByText('EST/9 · GM (P)')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('i21.w.viewOf', ['HCA-2026-0007']) }));
    const view = await screen.findByRole('link', { name: t('i21.viewOrder') });
    const item = view.closest('li') as HTMLElement;
    expect(item.textContent).toContain('order.pdf');
    expect(item.textContent).not.toContain('(');
  });

  it('SRS_G_31_aCopyFileClearedOnTheTaskFormIsNoCopy', async () => {
    backend({
      'GET /v1/hrms-change-tasks/counts': () => COUNTS,
      'GET /v1/hrms-change-tasks?': (url) => paged([task()], 1, url),
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/document-types': () => DOC_TYPES,
      'GET /v1/hrms-change-tasks/7': () => ({ task: task(), dob: null, applicable: true }),
    });
    wrap(<HrmsChangeApproval />, me(MAKER));
    await userEvent.click(await screen.findByRole('button', { name: t('i21.w.viewOf', ['HCA-2026-0007']) }));
    const form = await screen.findByRole('form', { name: t('i21.order') });
    await userEvent.type(within(form).getByLabelText(t('i21.orderNo')), 'EST/114');
    await userEvent.type(within(form).getByLabelText(t('i21.orderDate')), '2026-10-20');
    await userEvent.type(within(form).getByLabelText(t('i21.authority')), 'GM (P)');
    await within(form).findAllByRole('option', { name: 'Other document' });
    await userEvent.selectOptions(within(form).getByLabelText(t('ord.type')), 'OTHER');
    const file = within(form).getByLabelText(t('i21.copy'));
    await userEvent.upload(file, new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }));
    const send = within(form).getByRole('button', { name: t('i21.submit') });
    expect(send).toBeEnabled();
    fireEvent.change(file, { target: { files: [] } });
    expect(send).toBeDisabled();
  });

  it('SRS_1_67_aBulkOrderCopyClearedLeavesTheOrderUnsendable', async () => {
    backend({
      'GET /v1/document-types': () => DOC_TYPES,
      'GET /v1/hrms-change-tasks/counts': () => COUNTS,
      'GET /v1/hrms-change-tasks?': (url) =>
        paged([task(), task({ id: 8, taskNo: 'HCA-2026-0008' })], 2, url),
    });
    wrap(<HrmsChangeApproval />, me([...MAKER, 'I-21:ORDER', 'I-21:ORDER_OTP']));
    await userEvent.click(await screen.findByLabelText(t('i21.bulk.pickTask', ['HCA-2026-0007'])));
    await userEvent.click(screen.getByLabelText(t('i21.bulk.pickTask', ['HCA-2026-0008'])));
    await userEvent.click(screen.getByRole('button', { name: t('i21.bulk.enter', [2]) }));
    const form = await screen.findByRole('form', { name: t('i21.bulk.order') });
    await userEvent.type(within(form).getByLabelText(t('i21.orderNo')), 'EST/TR/31');
    await userEvent.type(within(form).getByLabelText(t('i21.orderDate')), '2026-10-20');
    await userEvent.type(within(form).getByLabelText(t('i21.authority')), 'GM (P)');
    await within(form).findAllByRole('option', { name: 'Other document' });
    await userEvent.selectOptions(within(form).getByLabelText(t('ord.type')), 'OTHER');
    const file = within(form).getByLabelText(t('i21.copy'));
    await userEvent.upload(file, new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }));
    const send = within(form).getByRole('button', { name: t('i21.submit') });
    expect(send).toBeEnabled();
    fireEvent.change(file, { target: { files: [] } });
    expect(send).toBeDisabled();
  });
});

// ---------------------------------------------------------------- M-01 flags

describe('M-01 flags: branch edges', () => {
  it('SRS_1_11_aMemberWithoutAJoiningDateMayDateAFlagFreelyButNotWithoutDocumentTypes', async () => {
    backend({
      'GET /v1/document-types': () => problem(503, 'COM-503-UNAVAILABLE'),
      'GET /v1/employees/100041/allowance-flags/history': (url) => paged([], 0, url),
      'GET /v1/employees/100041/allowance-flags': () => ({
        eis: '100041',
        joiningDate: null,
        flags: [{ code: 'NPA', label: 'Non-Practising Allowance', value: 'N', since: null, pending: false }],
      }),
      'GET /v1/employees/100041': () => ({ eis: '100041', employmentStatus: 'IN_SERVICE' }),
    });
    wrap(<AllowanceFlags eis="100041" />, me(['M-01:VIEW', 'M-01:FLAG']));
    const change = await screen.findByRole('button', {
      name: t('m01.flags.change', ['Non-Practising Allowance']),
    });
    expect(change).toBeEnabled();
    expect(change).not.toHaveAttribute('title');
    await userEvent.click(change);
    const form = screen.getByRole('form', { name: t('m01.flags.change', ['Non-Practising Allowance']) });
    const from = within(form).getByLabelText(t('m01.flags.from'));
    expect(from).not.toHaveAttribute('min');
    await userEvent.type(from, '2026-10-01');
    await userEvent.type(within(form).getByLabelText(t('common.reason')), 'Medical officer');
    expect(within(form).getByRole('button', { name: t('m01.flags.submit') })).toBeDisabled();
  });

  it('SRS_1_77_aSeparatedMembersFlagsCannotBeChanged', async () => {
    backend({
      'GET /v1/document-types': () => DOC_TYPES,
      'GET /v1/employees/100041/allowance-flags/history': (url) => paged([], 0, url),
      'GET /v1/employees/100041/allowance-flags': () => ({
        eis: '100041',
        joiningDate: '2010-06-01',
        flags: [{ code: 'NPA', label: 'Non-Practising Allowance', value: 'N', since: null, pending: false }],
      }),
      'GET /v1/employees/100041': () => ({ eis: '100041', employmentStatus: 'SEPARATED' }),
    });
    wrap(<AllowanceFlags eis="100041" />, me(['M-01:VIEW', 'M-01:FLAG']));
    const change = await screen.findByRole('button', {
      name: t('m01.flags.change', ['Non-Practising Allowance']),
    });
    await vi.waitFor(() => expect(change).toBeDisabled());
    expect(change).toHaveAttribute('title', t('m01.why.separated'));
  });

  it('SRS_1_7_aStopPaymentInForceIsProposedOffWithoutAJoiningDateLimitOrDocumentTypes', async () => {
    backend({
      'GET /v1/document-types': () => problem(503, 'COM-503-UNAVAILABLE'),
      'GET /v1/employees/100041/control-flags': () => ({
        eis: '100041',
        joiningDate: null,
        flags: [{ code: 'STOP_PAYMENT', value: 'Y', pending: false }],
      }),
    });
    wrap(<ControlFlags eis="100041" />, me(['M-01:VIEW', 'M-01:FLAG']));
    await userEvent.click(
      await screen.findByRole('button', {
        name: `${t('m01.control.change')} ${t('m01.control.STOP_PAYMENT')}`,
      }),
    );
    const form = screen.getByRole('form');
    expect(form.textContent).toContain(t('m01.flags.N'));
    const from = within(form).getByLabelText(t('m01.flags.from'));
    expect(from).not.toHaveAttribute('min');
    // Date and reason given: the order is still incomplete without the document types.
    await userEvent.type(from, todayIso());
    await userEvent.type(within(form).getByLabelText(t('common.reason')), 'Order 4');
    expect(
      within(form)
        .getAllByRole('button')
        .find((b) => b.getAttribute('type') === 'submit'),
    ).toBeDisabled();
  });
});

// ---------------------------------------------------------------- M-11, I-17, R-01, M-01

describe('M-11, I-17, R-01 and M-01: branch edges', () => {
  it('SRS_16_18_withoutDocumentTypesATransferNotHeldInHrmsCannotBeSentAndARowWithoutEisIsLabelled', async () => {
    backend({
      'GET /v1/transfers/destinations': () => [{ code: 'KL001', label: 'DO Kolkata', type: 'DO' }],
      'GET /v1/transfers': () =>
        paged([
          {
            id: 5,
            eis: null,
            employeeName: null,
            fromLocation: 'DH001',
            toLocation: 'KL001',
            transferDate: '2026-10-01',
            relievingDate: null,
            joiningDate: null,
            joiningTime: null,
            orderReference: 'TRF/5',
            lpcReference: null,
            splitWage: false,
            fromUnitDays: null,
            toUnitDays: null,
            reason: 'Transfer',
            status: 'PENDING_APPROVAL',
            approvalId: 3,
          },
        ]),
      'GET /v1/document-types': () => problem(503, 'COM-503-UNAVAILABLE'),
    });
    wrap(<StaffTransfers />, ['M-11:VIEW', 'M-11:CREATE']);
    expect(await screen.findByRole('button', { name: /DH001 → KL001$/ })).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('trf.record') }));
    await screen.findByRole('option', { name: 'DO Kolkata' });
    await userEvent.type(screen.getByLabelText(t('evt.eis')), '104882');
    await userEvent.selectOptions(screen.getByLabelText(t('trf.to')), 'KL001');
    await userEvent.type(screen.getByLabelText(t('trf.date')), '2026-10-01');
    await userEvent.type(screen.getByLabelText(t('evt.order'), { selector: '#trf-order' }), 'TRF/9');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Rotation');
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    expect(submit).toBeDisabled();
    await userEvent.click(screen.getByLabelText(t('m11.heldInHrms')));
    expect(submit).toBeEnabled();
  });

  it('SRS_1_63_aSalaryReleaseWithoutItsEndMonthShowsItsStartOnly', async () => {
    backend({
      'GET /v1/reference/lists': () =>
        referenceLists({ LIFECYCLE_EVENT_TYPE: [['SALARY_RELEASE', 'Salary release']] }),
      'GET /v1/lifecycle-events': () =>
        paged([
          {
            id: 2,
            eis: '104882',
            employeeName: 'Usha Pillai',
            eventType: 'SALARY_RELEASE',
            eventDate: '2026-10-01',
            endDate: null,
            reason: 'Released',
            subsistencePercent: null,
            releaseFromYymm: '202601',
            releaseToYymm: null,
            orderReference: null,
            source: 'HRMS',
            createdBy: 'system.hrms',
            status: 'APPROVED',
            approvalId: 3,
            hrmsChangeTaskId: null,
            hrmsRequestId: null,
            interveningPeriod: null,
          },
        ]),
    });
    wrap(<LifecycleEvents />, ['I-17:VIEW']);
    expect(await screen.findByText('202601–')).toBeTruthy();
  });

  it('SRS_22_3_aStatusMissingFromTheTotalsCountsZero', async () => {
    backend({
      'GET /v1/mis/headcount': () => ({
        ...paged([{ location: 'DO Delhi', locationCode: 'DH001', byStatus: { ACTIVE: 7 }, total: 7 }]),
        totals: { ACTIVE: 7 },
        grandTotal: 7,
      }),
    });
    wrap(<ConsolidatedMis />, ['R-01:VIEW']);
    expect(await screen.findByText('DO Delhi')).toBeTruthy();
    const total = screen.getAllByRole('row').at(-1) as HTMLElement;
    const cells = within(total)
      .getAllByRole('cell')
      .map((c) => c.textContent);
    expect(cells).toContain('0');
    expect(cells.at(-1)).toBe('7');
  });

  it('SRS_G_29_aSessionWithoutAnOfficeShowsTheTotalWithoutAnOfficeName', async () => {
    backend({
      'GET /v1/reference/lists': () => referenceLists({ EMPLOYEE_RECORD_STATUS: [['ACTIVE', 'Active']] }),
      'GET /v1/employees?': (url) => paged([], 0, url),
      'GET /v1/employees/counts': () => ({
        total: 0,
        payrollSetupPending: 0,
        changePending: 0,
        panCpfMissing: 0,
        bankInvalid: 0,
        active: 0,
      }),
    });
    wrap(<EmployeeMaster />, me(['M-01:VIEW'], { location: null }));
    expect(await screen.findByText(t('m01.w.totalSub'))).toBeTruthy();
  });

  it('SRS_1_70_anOrderCopyWithoutAReferenceShowsNone', async () => {
    backend({
      'GET /v1/hrms-update-requests/mine?': (url) =>
        paged(
          [
            {
              id: 3,
              requestNo: 'HUR-2026-0003',
              eis: '100041',
              employee: 'Asha Rao',
              fieldCode: 'BASIC_PAY',
              fieldLabel: 'Basic Pay',
              channel: 'ESEVA',
              raisedBy: 'asha',
              raisedAt: '2026-10-21T05:00:00Z',
              hrmsReference: 'HRMS-77',
              effectiveDate: '2026-10-01',
              fetchResult: 'FETCH_FAILED',
              status: 'FETCH_FAILED',
              messageCode: 'HRMS_FETCH_NOT_CONFIGURED',
              taskNo: null,
            },
          ],
          1,
          url,
        ),
      'GET /v1/hrms-update-requests/fields': () => [],
      'GET /v1/document-types': () => DOC_TYPES,
      'GET /v1/hrms-update-requests/mine/3/documents': () => [
        {
          id: 9,
          documentTypeName: 'Other document',
          referenceNumber: null,
          orderDate: '2026-10-01',
          fileName: 'order.pdf',
          uploadedBy: 'asha',
          uploadedAt: '2026-10-21T06:00:00Z',
        },
      ],
    });
    wrap(<HrmsUpdateRequests mine />, me(['E-01:VIEW', 'E-01:CREATE']));
    await userEvent.click(
      await screen.findByRole('button', { name: t('i21.upd.order.of', ['HUR-2026-0003']) }),
    );
    const link = await screen.findByRole('link', { name: 'order.pdf' });
    const item = link.closest('li') as HTMLElement;
    expect(item.textContent?.startsWith('order.pdf · Other document · asha · ')).toBe(true);
  });
});

// ---------------------------------------------------------------- incomplete forms submitted

/** An incomplete form submitted by the Enter key (implicit submission) sends nothing (R-MC). */
describe('incomplete forms submitted implicitly send nothing', () => {
  it('SRS_1_70_anIncompleteUpdateRequestSubmittedSendsNothing', async () => {
    const calls = backend({
      'GET /v1/hrms-update-requests/fields': () => [
        { code: 'PAN', label: 'PAN', group: 'IDENTITY_STATUTORY' },
      ],
      'GET /v1/hrms-update-requests?': (url) => paged([], 0, url),
    });
    wrap(<HrmsUpdateRequests mine={false} />, me(['I-21:VIEW', 'I-21:REQUEST']));
    const form = await screen.findByRole('form', { name: t('i21.upd.raise') });
    fireEvent.submit(form);
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('SRS_1_28_anIncompleteTaskOrderOrBulkOrderSubmittedSendsNothing', async () => {
    const calls = backend({
      'GET /v1/hrms-change-tasks/counts': () => COUNTS,
      'GET /v1/hrms-change-tasks?': (url) =>
        paged([task(), task({ id: 8, taskNo: 'HCA-2026-0008' })], 2, url),
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/document-types': () => DOC_TYPES,
      'GET /v1/hrms-change-tasks/7': () => ({ task: task(), dob: null, applicable: true }),
    });
    wrap(<HrmsChangeApproval />, me([...MAKER, 'I-21:ORDER', 'I-21:ORDER_OTP']));
    await userEvent.click(await screen.findByRole('button', { name: t('i21.w.viewOf', ['HCA-2026-0007']) }));
    fireEvent.submit(await screen.findByRole('form', { name: t('i21.order') }));
    await userEvent.click(screen.getByLabelText(t('i21.bulk.pickTask', ['HCA-2026-0007'])));
    await userEvent.click(screen.getByLabelText(t('i21.bulk.pickTask', ['HCA-2026-0008'])));
    await userEvent.click(screen.getByRole('button', { name: t('i21.bulk.enter', [2]) }));
    fireEvent.submit(await screen.findByRole('form', { name: t('i21.bulk.order') }));
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('SRS_1_11_anIncompleteFlagProposalSubmittedSendsNothing', async () => {
    const calls = backend({
      'GET /v1/document-types': () => DOC_TYPES,
      'GET /v1/employees/100041/allowance-flags/history': (url) => paged([], 0, url),
      'GET /v1/employees/100041/allowance-flags': () => ({
        eis: '100041',
        joiningDate: '2010-06-01',
        flags: [{ code: 'NPA', label: 'Non-Practising Allowance', value: 'N', since: null, pending: false }],
      }),
      'GET /v1/employees/100041/control-flags': () => ({
        eis: '100041',
        joiningDate: '2010-06-01',
        flags: [{ code: 'HOLD', value: 'N', pending: false }],
      }),
    });
    const flags = wrap(<AllowanceFlags eis="100041" />, me(['M-01:VIEW', 'M-01:FLAG']));
    await userEvent.click(
      await screen.findByRole('button', { name: t('m01.flags.change', ['Non-Practising Allowance']) }),
    );
    fireEvent.submit(screen.getByRole('form', { name: t('m01.flags.change', ['Non-Practising Allowance']) }));
    flags.unmount();
    wrap(<ControlFlags eis="100041" />, me(['M-01:VIEW', 'M-01:FLAG']));
    await userEvent.click(
      await screen.findByRole('button', { name: `${t('m01.control.change')} ${t('m01.control.HOLD')}` }),
    );
    fireEvent.submit(screen.getByRole('form'));
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });
});
