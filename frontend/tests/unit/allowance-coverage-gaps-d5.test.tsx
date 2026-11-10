import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ClaimsScreen } from '@/modules/allowance';
import { amount, date } from '@/shared/ui';
import { backend, me, paged, referenceLists, sent, t, wrap } from './support/harness';

/**
 * I-03 Allowances & Reimbursements as Wireframes v6.1 draws it (OD-121; SRS-11-1, SRS-11-3, SRS-11-13, SRS-11-17,
 * SRS-G-37): the tone of each claim status, a claim with no entitlement or payable yet (a dash, never a guessed
 * figure), the type and member filters sent to the server, the Maker told why a labour claim is not theirs to edit,
 * the stored documents of a claim, the lease view and a role without I-03 VIEW seeing nothing.
 */
afterEach(() => vi.unstubAllGlobals());

const REFERENCE = referenceLists({
  CLAIM_TYPE: [
    ['MEDICAL', 'Medical reimbursement'],
    ['LABOUR_TA', 'Labour TA'],
  ],
  ORDER_COPY_DOCUMENT_TYPE: [['OTHER', 'Other']],
});

const claim = (over: object = {}) => ({
  id: 7,
  eis: '881401',
  name: 'Vikram Sethi',
  memberType: 'EMPLOYEE',
  claimType: 'MEDICAL',
  claimTypeLabel: null,
  periodFrom: '2026-10-01',
  periodTo: '2026-10-28',
  claimed: 142600,
  entitlement: null,
  payable: null,
  capped: false,
  status: 'REJECTED',
  source: 'HRMS',
  hrmsRequestId: null,
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

const DOC = {
  id: 41,
  documentTypeCode: null,
  documentTypeName: 'Bill',
  referenceNumber: 'BILL/9',
  fileName: 'bill-9.pdf',
  uploadedBy: 'maker',
  uploadedAt: '2026-10-02T05:00:00Z',
};

function claims(rows: unknown[], leases: unknown[] = [], paid: unknown[] = []) {
  return backend({
    'GET /v1/reference/lists': () => REFERENCE,
    'GET /v1/claims/counts': () => ({ claims: 1, payable: 0, pendingApproval: 0, capped: 0, leases: 0 }),
    'GET /v1/claims/leases': () => paged(leases),
    'GET /v1/claims?': (url: string) => paged(url.includes('status=PAID') ? paid : rows),
    'GET /v1/claims/7': () => ({ claim: rows[0], documents: [DOC] }),
    'GET /v1/claims/8': () => ({ claim: rows[1], documents: [] }),
    'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other', referenceRequired: false }],
  });
}

const register = () => screen.findByRole('table', { name: t('i03.register') });

describe('I-03 register lines', () => {
  it('SRS_11_13_eachStatusHasItsToneAndAMissingEntitlementOrPayableIsADash', async () => {
    claims([
      claim(),
      claim({ id: 8, status: 'SUBMITTED', claimTypeLabel: 'Medical reimbursement' }),
      claim({ id: 9, status: 'PAID', entitlement: 1000, payable: 900 }),
    ]);
    wrap(<ClaimsScreen />, ['I-03:VIEW']);
    const table = await register();
    const rejected = (await within(table).findByText('CLM-7')).closest('tr') as HTMLElement;
    expect(rejected.querySelector('.pill')).toHaveClass('p-bad');
    // no label: the type code itself
    expect(within(rejected).getByText('MEDICAL')).toBeInTheDocument();
    expect(within(rejected).getAllByText('—')).toHaveLength(2);
    const submitted = within(table).getByText('CLM-8').closest('tr') as HTMLElement;
    expect(submitted.querySelector('.pill')).toHaveClass('p-warn');
    const paid = within(table).getByText('CLM-9').closest('tr') as HTMLElement;
    expect(paid.querySelector('.pill')).toHaveClass('p-ok');
    expect(within(paid).getByText(amount(900))).toBeInTheDocument();
  });

  it('SRS_11_1_theTypeAndAnEisSearchAreSentAndATextThatIsNoEisIsNot', async () => {
    const calls = claims([claim()]);
    const user = userEvent.setup();
    wrap(<ClaimsScreen />, ['I-03:VIEW']);
    await register();
    await screen.findByRole('option', { name: 'Labour TA' });
    await user.selectOptions(screen.getByRole('combobox', { name: t('i03w.allTypes') }), 'LABOUR_TA');
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('claimType=LABOUR_TA') && !c.url.includes('status=PAID'))).toBe(
        true,
      ),
    );
    const search = screen.getByRole('searchbox', { name: t('wf.searchInList') });
    await user.type(search, '#no-eis');
    expect(calls.some((c) => c.url.includes('eis='))).toBe(false);
    await user.clear(search);
    await user.type(search, 'L10702');
    await waitFor(() => expect(calls.some((c) => c.url.includes('eis=L10702'))).toBe(true));
  });

  it('SRS_11_13_aLabourClaimIsNotTheMakersToEditWithoutValidateTheCheckerTicks', async () => {
    claims([
      claim({ memberType: 'LABOUR', status: 'VALIDATED', source: 'LDH' }),
      claim({ id: 8, status: 'PAID' }),
    ]);
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    const user = userEvent.setup();
    wrap(<ClaimsScreen />, ['I-03:VIEW', 'I-03:APPROVE']);
    const table = await register();
    const labour = (await within(table).findByText('CLM-7')).closest('tr') as HTMLElement;
    expect(within(labour).getByRole('button', { name: /Edit|✎/ })).toHaveAttribute(
      'data-why',
      t('i03w.why.maker'),
    );
    // VALIDATED waits on the Checker: W-01
    await user.click(within(labour).getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('s=W-01'));
    const paid = within(table).getByText('CLM-8').closest('tr') as HTMLElement;
    await user.click(within(paid).getByTitle(t('wf.approveTitle')));
    expect(await screen.findByText(t('mst.nothingWaiting'))).toBeInTheDocument();
    expect(assign).toHaveBeenCalledTimes(1);
  });

  it('SRS_G_37_theViewListsTheStoredDocumentsAndTheInvoiceDateOrDashes', async () => {
    claims([
      claim(),
      claim({ id: 8, claimTypeLabel: 'Medical reimbursement', invoiceSentAt: '2026-10-05T05:00:00Z' }),
    ]);
    const user = userEvent.setup();
    wrap(<ClaimsScreen />, ['I-03:VIEW']);
    await user.click(await screen.findByRole('button', { name: `${t('common.view')} CLM-7` }));
    let dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText('bill-9.pdf')).toBeInTheDocument();
    // entitlement, payable, HRMS request id, invoice date: dashes
    expect(within(dialog).getAllByText('—').length).toBeGreaterThanOrEqual(4);
    await user.click(within(dialog).getByRole('button', { name: t('common.close') }));
    await user.click(screen.getByRole('button', { name: `${t('common.view')} CLM-8` }));
    dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Medical reimbursement')).toBeInTheDocument();
    // no documents on this claim
    await waitFor(() => expect(within(dialog).queryByText('bill-9.pdf')).toBeNull());
  });

  it('A_41_theOrderCardShowsTheStoredDocumentsOfTheFirstClaimWithTheirLink', async () => {
    claims([claim()]);
    wrap(<ClaimsScreen />, ['I-03:VIEW']);
    const card = await screen.findByText('bill-9.pdf', { selector: '[data-order-card] td' });
    const row = card.closest('tr') as HTMLElement;
    expect(within(row).getByRole('link', { name: t('wf.view') })).toHaveAttribute(
      'href',
      expect.stringContaining('/v1/claims/7/documents/41'),
    );
  });

  it('SRS_11_17_anInvoiceRowWithoutLabelReadsTheTypeAndItsSentDate', async () => {
    claims(
      [],
      [],
      [claim({ status: 'PAID', payable: 900, invoiceStatus: 'SENT_TO_APPS', invoiceSentAt: '2026-10-05' })],
    );
    wrap(<ClaimsScreen />, ['I-03:VIEW']);
    const card = await screen.findByRole('table', { name: t('i03w.invoiceTitle') });
    const row = (await within(card).findByText('CLM-7')).closest('tr') as HTMLElement;
    expect(within(row).getByText('MEDICAL')).toBeInTheDocument();
    expect(within(row).getByText(date('2026-10-05'))).toBeInTheDocument();
    // the cancellation path is not built: off with its reason
    expect(within(row).getByRole('button', { name: t('wf.inv.cancel') })).toHaveAttribute(
      'data-why',
      t('i03w.why.invoice'),
    );
  });

  it('SRS_11_3_aLeaseWithoutAmountReadsADashAndItsViewSaysHraSuppressed', async () => {
    claims(
      [],
      [
        {
          id: 3,
          eis: '880708',
          name: 'Kiran Rao',
          memberType: 'EMPLOYEE',
          accommodationType: 'THIRD_PARTY',
          leaseAmount: null,
          sevenPercent: 1120,
          hraSuppressed: true,
          conflict: false,
          source: 'HRMS',
          hrmsRequestId: 'HRMS-L-3',
          effectiveFrom: '2026-04-01',
        },
      ],
    );
    const user = userEvent.setup();
    wrap(<ClaimsScreen />, ['I-03:VIEW']);
    const table = await screen.findByRole('table', { name: t('i03w.leaseTitle') });
    const row = (await within(table).findByText(/880708/)).closest('tr') as HTMLElement;
    expect(within(row).getByText('—')).toBeInTheDocument();
    expect(within(row).getByText(amount(1120))).toBeInTheDocument();
    expect(within(row).getByText(t('i03w.hra.suppressed'))).toBeInTheDocument();
    await user.click(within(row).getByRole('button', { name: `${t('common.view')} 880708` }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('HRMS-L-3')).toBeInTheDocument();
    expect(within(dialog).getByText(t('i03w.hra.suppressed'))).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('SRS_11_1_aRoleWithoutI03ViewSeesNothingAndReadsNoClaim', async () => {
    const calls = claims([claim()]);
    const { container } = wrap(<ClaimsScreen />, ['I-04:VIEW']);
    await waitFor(() => expect(container.querySelector('.wireframe-body-container')).toBeNull());
    expect(sent(calls, 'GET', '/v1/claims?')).toHaveLength(0);
  });

  it('SRS_11_1_withoutASessionPeriodTheRegisterReadsTheCurrentMonth', async () => {
    const calls = claims([]);
    wrap(<ClaimsScreen />, me(['I-03:VIEW'], { period: undefined as never }));
    expect(await screen.findByText(t('i03.none'))).toBeInTheDocument();
    expect(calls.some((c) => /yymm=\d{6}/.test(c.url))).toBe(true);
  });
});
