import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ExceptionsWorklist, HrmsFallback, OffCycle, PayrollQueries, Supplementary } from '@/modules/general';
import type {
  Enablement,
  ExceptionItem,
  FallbackUpload,
  OffCycleCase,
  PayrollQuery,
  SupplementaryPayment,
} from '@/modules/general/api/generalApi';
import { InvoicePill, StoredOrders } from '@/modules/general/components/Parts';
import { I18nProvider } from '@/shared/i18n';
import { date } from '@/shared/ui';
import { backend, paged, problem, referenceLists, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * The Module 01 screens with records that lack a particular (a member not on the master, no order,
 * no owner, no source file, no resolution yet): what each shows instead, never "null", and the
 * controls a viewer does not get.
 */

afterEach(() => vi.unstubAllGlobals());

async function rowOf(reference: string): Promise<HTMLElement> {
  return waitFor(() => {
    const row = document.querySelector<HTMLElement>(`tr[data-reference="${reference}"]`);
    if (!row) throw new Error(`no row ${reference}`);
    return row;
  });
}

function invoiceRow(reference: string): HTMLElement {
  return document.querySelector<HTMLElement>(`tr[data-invoice="${reference}"]`) as HTMLElement;
}

/** The invoice row once rendered (the invoice table may render after the register under load). */
async function invoiceRowOf(reference: string): Promise<HTMLElement> {
  return waitFor(() => {
    const row = document.querySelector<HTMLElement>(`tr[data-invoice="${reference}"]`);
    if (!row) throw new Error(`no invoice row ${reference}`);
    return row;
  });
}

const CASE: OffCycleCase = {
  id: 7,
  reference: 'OFC-00000007',
  eis: '105220',
  memberName: 'Sourav Banerjee',
  caseType: 'LATE_JOINER',
  salaryYymm: '202609',
  processingYymm: '202610',
  amount: 52000,
  approvalReference: 'HQ/PAY/17',
  duplicateCheck: 'PASS',
  status: 'DRAFT',
  invoiceStatus: 'NOT_RAISED',
  invoiceNo: null,
  invoiceSentToAppsDate: null,
  appsCancelConfirmedAt: null,
  excludedFromYymm: null,
  cancelReason: null,
  orders: 0,
  approvalId: null,
};

const PAYMENT: SupplementaryPayment = {
  id: 11,
  reference: 'SUP-00000011',
  eis: '106004',
  memberName: 'Prakash',
  paymentType: 'SALARY_WAGE',
  payCode: null,
  forPeriodYymm: '202607',
  amount: 41200,
  applicationDate: '2026-08-10',
  sanctionDate: null,
  sanctionOrderRef: null,
  duplicateCheck: 'PASSED',
  duplicateOf: null,
  status: 'DRAFT',
  invoiceStatus: 'NOT_RAISED',
  invoiceId: null,
  invoiceSentAt: null,
  appsCancelStatus: 'NOT_REQUESTED',
  cancelReason: null,
  orderAttachmentId: 5,
  orderReference: 'DH/SUP/106004',
  approvalId: null,
};
const TILES = { open: 2, pendingChecker: 0, blockedDuplicates: 0, rejected: 0 };

const QUERY: PayrollQuery = {
  id: 3,
  reference: 'QRY-00000003',
  payrollId: 88,
  eis: '105220',
  memberName: 'Sourav Banerjee',
  payrollYymm: '202607',
  channel: 'STAFF',
  queryText: 'Why is HRA lower?',
  concernsComputedAmount: false,
  status: 'IN_PROGRESS',
  raisedBy: 'maker.delhi',
  raisedAt: '2026-10-20T05:00:00Z',
  makerResponse: 'City class changed',
  respondedBy: 'maker.delhi',
  respondedAt: '2026-10-20T06:00:00Z',
  resolutionText: null,
  resolvedBy: null,
  resolvedAt: null,
};

function queriesRoute(queries: PayrollQuery[]) {
  return (url: string) => {
    if (url.includes('/counts')) {
      const counts: Record<string, number> = { OPEN: 0, IN_PROGRESS: 0, RESOLVED: 0, CLOSED: 0 };
      queries.forEach((q) => (counts[q.status] = (counts[q.status] ?? 0) + 1));
      return counts;
    }
    const status = /[?&]status=([A-Z_]+)/.exec(url)?.[1];
    return paged(status ? queries.filter((q) => q.status === status) : queries);
  };
}

const ITEM: ExceptionItem = {
  id: 21,
  cycleInstanceId: null,
  eis: '105220',
  memberName: 'Sourav Banerjee',
  moduleCode: 'C-02',
  exceptionCode: 'MISSING_BANK',
  severity: 'WARNING',
  blocksGate: 'G2',
  entityName: 'employee',
  fieldName: 'bank_account',
  fieldSource: 'ENT',
  resolvableInPayroll: true,
  correctionScreen: 'M-01',
  ownerRoleCode: null,
  detail: 'Bank account missing',
  status: 'OPEN',
  acceptedBy: null,
  acceptedReason: null,
  raisedAt: '2026-10-20T05:00:00Z',
  ageDays: 1,
  resolvedAt: null,
};

/**
 * X-05 routes: the server's conditions (grouped and counted over the given items, SRS l.634), the
 * opened condition's items by code, and the offices of the scope.
 */
function x05(items: ExceptionItem[]) {
  const open = (e: ExceptionItem) =>
    e.status === 'OPEN' || e.status === 'IN_PROGRESS' || e.status === 'ROUTED_UPSTREAM';
  const codes = [...new Set(items.map((e) => e.exceptionCode))];
  const groups = codes.map((code) => {
    const g = items.filter((e) => e.exceptionCode === code);
    const first = g[0] as ExceptionItem;
    const owners = new Set(g.map((e) => e.ownerRoleCode));
    return {
      moduleCode: first.moduleCode,
      exceptionCode: code,
      items: g.length,
      members: new Set(g.map((e) => e.eis).filter(Boolean)).size,
      blocksG2: g.some((e) => open(e) && e.blocksGate === 'G2' && e.severity === 'BLOCKING'),
      owner: owners.size === 1 ? first.ownerRoleCode : null,
      resolvableInPayroll: g.some((e) => e.resolvableInPayroll),
      actionable: g.some((e) => open(e) && e.resolvableInPayroll),
      correctionScreen: first.correctionScreen,
      detail: first.detail,
      firstRaisedAt: first.raisedAt,
    };
  });
  return {
    'GET /v1/reference/lists': () =>
      referenceLists({
        EXCEPTION_STATUS: [
          ['OPEN', 'Open'],
          ['IN_PROGRESS', 'In progress'],
          ['RESOLVED', 'Resolved'],
          ['ACCEPTED', 'Accepted with reason'],
          ['ROUTED_UPSTREAM', 'Routed upstream'],
        ],
        EXCEPTION_SEVERITY: [
          ['BLOCKING', 'Blocking'],
          ['WARNING', 'Warning'],
          ['INFORMATIONAL', 'Informational'],
          ['SECURITY', 'Security'],
        ],
      }),
    'GET /v1/exceptions/groups': () => ({
      ...worklist(items),
      items: groups,
      total: groups.length,
      modules: [],
    }),
    'GET /v1/exceptions/offices': () => [],
    'GET /v1/exceptions': (url: string) => {
      const code = new URL(url, 'http://x').searchParams.get('code');
      return worklist(items.filter((e) => !code || e.exceptionCode === code));
    },
  };
}

function worklist(items: ExceptionItem[]) {
  return {
    tiles: { open: items.length, blockingG2: 0, acceptedWithReason: 0, upstream: 0 },
    items,
    page: 0,
    size: 20,
    total: items.length,
  };
}

const ENABLEMENT: Enablement = {
  id: 4,
  reference: 'FBE-2026-001',
  type: 'SUSPENSION_ABSCONDING',
  payrollYymm: '202610',
  offices: ['DH001'],
  reason: 'HRMS down',
  enabledBy: 'sysadmin',
  enabledAt: '2026-10-01T05:00:00Z',
  status: 'OPEN',
  withdrawnReason: null,
  officeNames: ['DH001 - Delhi HQ'],
  hrmsRestoredAt: null,
};

const UPLOAD: FallbackUpload = {
  id: 9,
  batchNo: 'FBU-1',
  enablementId: 4,
  type: 'SUSPENSION_ABSCONDING',
  office: 'DH001',
  payrollYymm: '202610',
  fileName: 'export.csv',
  recordCount: 3,
  acceptedCount: 2,
  rejectedCount: 1,
  uploadedBy: 'maker.delhi',
  uploadedAt: '2026-10-02T05:00:00Z',
  status: 'VALIDATED',
  checkerUser: null,
  decisionReason: null,
  reconStatus: 'NOT_DUE',
  orderCopies: 0,
  sourceAttachmentId: 31,
  approvalId: null,
  enablementReference: 'FBE-2026-001',
};

function fallback(enablements: Enablement[], uploads: FallbackUpload[]) {
  return {
    'GET /v1/hrms-fallback/enablements': (url: string) =>
      url.includes('/live')
        ? enablements.filter((e) => e.status === 'OPEN' || e.status === 'UPLOADED')
        : paged(enablements),
    'GET /v1/hrms-fallback/uploads': (url: string) => (url.includes('/rejections') ? [] : paged(uploads)),
    'GET /v1/hrms-fallback/summary': () => ({ awaitingApproval: 0, reconciliationPending: 0 }),
    'GET /v1/hrms-fallback/interface-status': () => ({ down: false, since: null }),
    'GET /v1/hrms-fallback/reconciliation': () => paged([]),
  };
}

describe('I-20 Off-Cycle without every particular', () => {
  it('SRS_G_16_aCaseWithoutAMemberReferenceOrGuardResultShowsDashesAndItsCancellationReason', async () => {
    const bare: OffCycleCase = {
      ...CASE,
      id: 8,
      reference: 'OFC-00000008',
      eis: null,
      memberName: null,
      approvalReference: '',
      duplicateCheck: null,
      status: 'CANCELLED',
      cancelReason: 'Raised twice',
    };
    const cancelledInApps: OffCycleCase = {
      ...CASE,
      id: 9,
      reference: 'OFC-00000009',
      eis: null,
      status: 'APPROVED',
      invoiceStatus: 'CANCELLED_IN_APPS',
      invoiceNo: 'INV-9',
      appsCancelConfirmedAt: '2026-10-21T05:00:00Z',
    };
    backend({ 'GET /v1/off-cycle': () => paged([bare, cancelledInApps]) });
    wrap(<OffCycle />, ['I-20:VIEW']);
    const row = await rowOf('OFC-00000008');
    expect(within(row).getAllByText('—').length).toBeGreaterThanOrEqual(2);
    // UI_BMAD_g1 #18 (SRS l.761): a check not run yet is neither Pass nor Fail.
    expect(within(row).getByText(t('i20.dupPending'))).toBeInTheDocument();
    expect(within(row).queryByText(t('i20.dupFailed', ['']).trim())).toBeNull();
    expect(within(row).getByText('Raised twice')).toBeInTheDocument();
    expect(row.textContent).not.toContain('null');
    const invoice = await invoiceRowOf('OFC-00000009');
    expect(within(invoice).getByText('—')).toBeInTheDocument();
    // The pill and the APPS confirmation read the same; only the pill is toned.
    const cancelled = within(invoice).getAllByText(t('inv.cancelledInApps'));
    expect(cancelled).toHaveLength(2);
    expect(cancelled.some((e) => e.classList.contains('p-gray'))).toBe(true);
  });

  it('SRS_G_10_aCaseRecordedBeforeItsGuardRanIsAnnouncedWithoutAResult', async () => {
    backend({
      'GET /v1/off-cycle': () => paged([]),
      'POST /v1/off-cycle': () => ({ ...CASE, id: 9, reference: 'OFC-00000009', duplicateCheck: null }),
      'GET /v1/document-types': () => [],
      'GET /v1/off-cycle/9/orders': () => [],
      'GET /v1/reference/lists': () =>
        referenceLists({
          OFF_CYCLE_CASE_TYPE: [
            ['LATE_JOINER', 'Late joiner'],
            ['OTHER', 'Other'],
          ],
        }),
    });
    wrap(<OffCycle />, ['I-20:VIEW', 'I-20:CREATE']);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: t('i20.raise') }));
    const dialog = screen.getByRole('dialog', { name: t('i20.raiseTitle') });
    await user.type(within(dialog).getByLabelText(t('gen.eis')), '105220');
    await user.selectOptions(within(dialog).getByLabelText(t('i20.caseType')), 'LATE_JOINER');
    const month = within(dialog).getByLabelText(t('i20.salaryMonth')) as HTMLSelectElement;
    await user.selectOptions(month, month.options[2]?.value ?? '');
    await user.type(within(dialog).getByLabelText(t('i20.approvalReference')), 'HQ/PAY/17');
    await user.click(within(dialog).getByRole('button', { name: t('i20.capture') }));
    expect(await screen.findByText(t('i20.captured', ['OFC-00000009', '']))).toBeInTheDocument();
  });
});

describe('I-23 Supplementary without every particular', () => {
  it('SRS_G_34_aRequestWithoutAMemberOrOrderShowsWhatItLacksAndASentInvoiceCannotBeCancelled', async () => {
    const bare: SupplementaryPayment = {
      ...PAYMENT,
      id: 13,
      reference: 'SUP-00000013',
      eis: null,
      memberName: null,
      orderReference: null,
      orderAttachmentId: null,
      status: 'CANCELLED',
      cancelReason: 'Entered twice',
    };
    const sentToApps: SupplementaryPayment = {
      ...PAYMENT,
      id: 14,
      reference: 'SUP-00000014',
      eis: null,
      payCode: 1037,
      status: 'RELEASED',
      invoiceStatus: 'SENT_TO_APPS',
      invoiceId: 77,
      invoiceSentAt: '2026-10-21T05:00:00Z',
    };
    backend({
      'GET /v1/supplementary/tiles': () => TILES,
      'GET /v1/supplementary': () => paged([bare, sentToApps]),
      // The document types cannot be read: no order copy is complete, so nothing can be recorded.
      'GET /v1/document-types': () => problem(503, 'COM-503-UNAVAILABLE'),
    });
    wrap(<Supplementary />, ['I-23:VIEW', 'I-23:SUBMIT']);
    const row = await rowOf('SUP-00000013');
    expect(within(row).getByText(t('i23.orderOnly'))).toBeInTheDocument();
    expect(within(row).getByText('Entered twice')).toBeInTheDocument();
    expect(within(row).getByText('—')).toBeInTheDocument();
    expect(within(await rowOf('SUP-00000014')).getByText(t('i23.payCode', [1037]))).toBeInTheDocument();
    const invoice = invoiceRow('SUP-00000014');
    expect(within(invoice).getByText('— Prakash')).toBeInTheDocument();
    expect(within(invoice).getByText(date('2026-10-21T05:00:00Z'))).toBeInTheDocument();
    const cancel = within(invoice).getByRole('button', { name: t('wf.inv.cancel') });
    expect(cancel).toBeDisabled();
    expect(cancel).toHaveAttribute('title', t('inv.cancelBlocked'));
    const open = invoiceRow('SUP-00000011');
    expect(open).toBeNull();
  });
});

describe('X-06 Payroll Query without every particular', () => {
  it('SRS_G_11_aMemberQueryWithoutANameNamesItsRaiserAndAClosedQueryShowsItsResolution', async () => {
    const anonymous: PayrollQuery = {
      ...QUERY,
      id: 6,
      reference: 'QRY-00000006',
      channel: 'ESEVA',
      memberName: null,
      status: 'OPEN',
      makerResponse: null,
      respondedBy: null,
      respondedAt: null,
    };
    const closed: PayrollQuery = {
      ...QUERY,
      id: 7,
      reference: 'QRY-00000007',
      status: 'CLOSED',
      resolutionText: 'Correct as computed',
      resolvedBy: 'checker.delhi',
      resolvedAt: '2026-10-21T05:00:00Z',
    };
    backend({ 'GET /v1/queries': queriesRoute([anonymous, closed, QUERY]) });
    wrap(<PayrollQueries />, ['X-06:VIEW', 'X-06:APPROVE']);
    expect(
      within(await rowOf('QRY-00000006')).getByText(t('x06.byMember', ['maker.delhi'])),
    ).toBeInTheDocument();
    // A Checker may close an answered query that concerns no computed amount; nobody takes a
    // step on an open or a closed one.
    expect(
      within(await rowOf('QRY-00000003')).getByRole('button', { name: t('x06.editOf', ['QRY-00000003']) }),
    ).toBeEnabled();
    expect(
      within(await rowOf('QRY-00000006')).getByRole('button', { name: t('x06.editOf', ['QRY-00000006']) }),
    ).toBeDisabled();
    expect(
      within(await rowOf('QRY-00000007')).getByRole('button', { name: t('x06.editOf', ['QRY-00000007']) }),
    ).toBeDisabled();
    await userEvent
      .setup()
      .click(
        within(await rowOf('QRY-00000007')).getByRole('button', { name: t('x06.viewOf', ['QRY-00000007']) }),
      );
    expect(await screen.findByText('Correct as computed')).toBeInTheDocument();
  });
});

/** The filters of X-05 open from its Filter button (as drawn). */
async function openX05Filters() {
  // The last rendered worklist (a test may mount a second one before unmounting the first).
  const buttons = screen.getAllByRole('button', { name: t('x05.filter') });
  await userEvent.click(buttons[buttons.length - 1] as HTMLElement);
}

describe('X-05 Exceptions without every particular', () => {
  it('SRS_G_9_anItemWithoutAScreenOrFieldShowsDashesItsOwnerAndReasonAndAViewerCannotAct', async () => {
    const accepted: ExceptionItem = {
      ...ITEM,
      id: 24,
      exceptionCode: 'HRMS_GAP',
      correctionScreen: null,
      fieldName: null,
      fieldSource: null,
      ownerRoleCode: 'ESTABLISHMENT_HR',
      status: 'ACCEPTED',
      acceptedBy: 'ddo.delhi',
      acceptedReason: 'Paid by cheque',
    };
    const calls = backend(x05([ITEM, accepted]));
    wrap(<ExceptionsWorklist />, ['X-05:VIEW']);
    await openX05Filters();
    const user = userEvent.setup();
    const open = (await screen.findByText('MISSING_BANK')).closest('tr') as HTMLElement;
    const act = within(open).getByRole('button', { name: t('x05.editOf', ['MISSING_BANK']) });
    expect(act).toBeDisabled();
    // Drawn disabled with its reason (OD-121), never without one.
    expect(act).toHaveAttribute('title', t('wf.editOff', [t('x05.why.ownerOnly')]));
    const settled = screen.getByText('HRMS_GAP').closest('tr') as HTMLElement;
    expect(within(settled).getByText(t('role.ESTABLISHMENT_HR'))).toBeInTheDocument();
    expect(within(settled).getByRole('button', { name: t('x05.editOf', ['HRMS_GAP']) })).toHaveAttribute(
      'title',
      t('wf.editOff', [t('x05.why.settled')]),
    );
    await user.click(within(settled).getByRole('button', { name: t('x05.viewOf', ['HRMS_GAP']) }));
    const item = document.querySelector('tr[data-item="24"]') as HTMLElement;
    expect(within(item).getByText('Paid by cheque')).toBeInTheDocument();
    expect(within(item).getByText(t('role.ESTABLISHMENT_HR'))).toBeInTheDocument();
    expect(within(item).getAllByText('—').length).toBeGreaterThanOrEqual(1);
    // Narrowing and widening the filters again both reach the server.
    await user.selectOptions(screen.getByLabelText(t('gen.status')), 'OPEN');
    await user.selectOptions(screen.getByLabelText(t('x05.severity')), 'BLOCKING');
    await waitFor(() => expect(calls.some((c) => c.url.includes('severity=BLOCKING'))).toBe(true));
    await user.selectOptions(screen.getByLabelText(t('gen.status')), '');
    await user.selectOptions(screen.getByLabelText(t('x05.severity')), '');
    await waitFor(() => expect(calls.at(-1)?.url).not.toMatch(/status=|severity=/));
  });
});

describe('X-07 HRMS Fallback without every particular', () => {
  it('SRS_EH_11_anUploadDecidedWithAReasonAndWithoutItsSourceFileShowsBothAndItsLinesFoldAgain', async () => {
    const decided: FallbackUpload = {
      ...UPLOAD,
      id: 10,
      batchNo: 'FBU-2',
      status: 'RETURNED',
      decisionReason: 'Wrong month',
      sourceAttachmentId: null,
    };
    const withdrawn: Enablement = {
      ...ENABLEMENT,
      id: 5,
      reference: 'FBE-2026-002',
      status: 'WITHDRAWN',
      withdrawnReason: 'HRMS back',
    };
    backend({
      ...fallback([ENABLEMENT, withdrawn], [decided]),
      'GET /v1/hrms-fallback/uploads/10/lines': () =>
        paged([
          {
            lineNo: 1,
            employeeNumber: null,
            recordType: null,
            periodFrom: '2026-10-01',
            periodTo: '2026-10-15',
            payDays: null,
            lwpDays: null,
            halfPayDays: null,
            eventEffectiveDate: null,
            orderNo: 'ORD/7',
            validationStatus: 'REJECTED',
            rejectionReason: 'EMPLOYEE_NOT_FOUND',
            reconStatus: 'NOT_DUE',
          },
          {
            lineNo: 2,
            employeeNumber: '105220',
            recordType: null,
            periodFrom: null,
            periodTo: null,
            payDays: null,
            lwpDays: null,
            halfPayDays: null,
            eventEffectiveDate: null,
            orderNo: null,
            validationStatus: 'ACCEPTED',
            rejectionReason: null,
            reconStatus: 'NOT_DUE',
          },
        ]),
    });
    wrap(<HrmsFallback />, ['X-07:VIEW']);
    expect(within(await rowOf('FBE-2026-002')).getByText('HRMS back')).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: t('x07.w.history') }));
    const row = await rowOf('FBU-2');
    expect(within(row).getByText('Wrong month')).toBeInTheDocument();
    await user.click(within(row).getByRole('button', { name: t('x07.lines') }));
    expect(await screen.findByText('ORD/7')).toBeInTheDocument();
    expect(screen.getByText('2026-10-01 – 2026-10-15')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: t('x07.w.sourceKept') })).toBeNull();
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(2);
    // The Validation line of the card closes the records again.
    await user.click(screen.getByRole('link', { name: t('x07.w.validation', [2, 1]) }));
    await waitFor(() => expect(screen.queryByText('ORD/7')).toBeNull());
  });

  it('SRS_EH_11_clearingTheChosenExportFileDisablesTheUploadAgain', async () => {
    backend(fallback([ENABLEMENT], []));
    wrap(<HrmsFallback />, ['X-07:VIEW', 'X-07:SUBMIT']);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: t('x07.w.act.upload') }));
    await user.selectOptions(screen.getByLabelText(t('x07.enablement')), '4');
    const input = screen.getByLabelText(t('x07.file'));
    await user.upload(input, new File(['EMP_NO\n1'], 'export.csv', { type: 'text/csv' }));
    const send = screen.getByRole('button', { name: t('x07.uploadValidate') });
    expect(send).toBeEnabled();
    fireEvent.change(input, { target: { files: [] } });
    expect(send).toBeDisabled();
  });
});

describe('shared parts of Module 01', () => {
  it('BR_AUDIT_010_theInvoicePillTonesEachStatusAndAStoredOrderWithoutATypeNameOrReferenceShowsDashes', () => {
    render(
      <I18nProvider>
        <InvoicePill status="RAISED" />
        <InvoicePill status="CANCELLED_IN_APPS" />
        <InvoicePill status="CANCEL_REQUESTED" />
        <StoredOrders
          orders={[
            {
              id: 3,
              documentTypeCode: 'OTHER',
              documentTypeName: null,
              referenceNumber: null,
              orderDate: null,
              issuingAuthority: null,
              fileName: 'note.pdf',
              status: 'ACTIVE',
              uploadedBy: 'maker.delhi',
              uploadedAt: '2026-07-12T05:34:00Z',
            },
            {
              id: 4,
              documentTypeCode: null,
              documentTypeName: null,
              referenceNumber: 'REF/4',
              orderDate: null,
              issuingAuthority: null,
              fileName: 'untyped.pdf',
              status: 'ACTIVE',
              uploadedBy: 'maker.delhi',
              uploadedAt: '2026-07-12T05:34:00Z',
            },
          ]}
          error={null}
          href={(id) => `/files/${id}`}
        />
      </I18nProvider>,
    );
    expect(screen.getByText(t('inv.RAISED'))).toHaveClass('p-warn');
    expect(screen.getByText(t('inv.CANCELLED_IN_APPS'))).toHaveClass('p-gray');
    expect(screen.getByText(t('inv.CANCEL_REQUESTED'))).toHaveClass('p-bad');
    const noted = screen.getByText('note.pdf').closest('tr') as HTMLElement;
    expect(within(noted).getByText('OTHER')).toBeInTheDocument();
    expect(within(noted).getByText('—')).toBeInTheDocument();
    const untyped = screen.getByText('untyped.pdf').closest('tr') as HTMLElement;
    expect(within(untyped).getByText('—')).toBeInTheDocument();
    expect(within(untyped).getByText('REF/4')).toBeInTheDocument();
  });
});
