import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Claims } from '@/modules/allowance';
import type { ClaimRow } from '@/modules/allowance/api/claimsApi';
import { cafeteriaPage } from '@/modules/allowance/orchestration/claimQueries';
import { dateTime } from '@/shared/ui';
import { backend, me, paged, sent, t, wrap } from './support/harness';

/**
 * Allowance module branch gaps (I-03 Allowances & Reimbursements, I-16 Cafeteria; SRS-11-11,
 * SRS-11-13, SRS-11-16): an invoice sent to APPS shows its time in the register and the detail, a
 * detail answered without its documents lists none, a validation without a reason sends none, and
 * the I-16 page asked without a member or year sends neither.
 */

afterEach(() => vi.unstubAllGlobals());

const SENT_AT = '2026-10-21T06:30:00Z';

const ROW: ClaimRow = {
  id: 7,
  eis: 'L10001',
  name: 'Ram Lal',
  memberType: 'LABOUR',
  claimType: 'LABOUR_TA',
  claimTypeLabel: 'Labour TA',
  periodFrom: '2026-10-01',
  periodTo: '2026-10-05',
  claimed: 6000,
  entitlement: 5000,
  payable: 5000,
  capped: false,
  status: 'SUBMITTED',
  source: 'LDH',
  hrmsRequestId: null,
  taxable: null,
  taxableConfirmedAt: null,
  invoiceStatus: 'SENT',
  invoiceSentAt: SENT_AT,
  iogaNumber: null,
  iogaDate: null,
  iogaAmount: null,
  approvalId: null,
  decisionReason: null,
  taxTreatment: null,
  taxableAmount: null,
};

describe('I-03 branch gaps', () => {
  it('SRS_11_13_anInvoiceSentTimeReadsInRegisterAndDetailAndAValidationWithoutReasonSendsNone', async () => {
    const calls = backend({
      'GET /v1/claims?': (url) => paged([ROW], 1, url),
      // A detail answered without its documents: none are listed.
      'GET /v1/claims/7': () => ({ claim: ROW }),
      'GET /v1/claims/pending-hrms': (url) => paged([], 0, url),
      'GET /v1/claims/types': () => [],
      'GET /v1/document-types': () => [],
      'POST /v1/claims/7/validations': () => ({ status: 202, body: { id: 40, status: 'PENDING_APPROVAL' } }),
    });
    wrap(<Claims />, me(['I-03:VIEW', 'I-03:VALIDATE']));
    const table = await screen.findByRole('table', { name: t('i03.register') });
    const row = within(table)
      .getAllByRole('button', { name: t('i03.claimNo', [7]) })[0]
      ?.closest('tr');
    expect(row).toHaveTextContent(dateTime(SENT_AT));
    await userEvent.click(
      within(table).getAllByRole('button', { name: t('i03.claimNo', [7]) })[0] as HTMLElement,
    );
    const panel = await screen.findByRole('region', { name: t('i03.claimNo', [7]) });
    expect(
      await within(panel).findByText(new RegExp(t('i03.invoiceSentOn', [dateTime(SENT_AT)]))),
    ).toBeTruthy();
    expect(within(panel).queryByRole('list', { name: t('i03.documents') })).toBeNull();
    const validate = within(panel).getByLabelText(t('i03.validate'));
    await userEvent.click(within(validate).getByRole('button', { name: t('i03.validate') }));
    expect(await screen.findByText(t('i03.sent'))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/validations')).toEqual([{ reason: null }]);
  });
});

describe('I-16 branch gaps', () => {
  it('SRS-11-16: a page asked without a member or a financial year sends neither', async () => {
    const calls = backend({ 'GET /v1/cafeteria-selections': (url) => paged([], 0, url) });
    await cafeteriaPage('', '')(0, 20);
    await cafeteriaPage('104882')(1, 10);
    const urls = calls.map((c) => c.url);
    expect(urls[0]).not.toMatch(/eis=|finYear=/);
    expect(urls[1]).toContain('eis=104882');
    expect(urls[1]).not.toContain('finYear=');
  });

  it('SRS_11_14_aDocumentRowAddedToANewClaimCanBeRemovedAndTheOthersStay', async () => {
    backend({
      'GET /v1/claims?': (url) => paged([], 0, url),
      'GET /v1/claims/pending-hrms': (url) => paged([], 0, url),
      'GET /v1/claims/types': () => [{ code: 'LABOUR_TA', label: 'Labour TA', payCode: 915 }],
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
    });
    wrap(<Claims />, me(['I-03:VIEW', 'I-03:CREATE']));
    await userEvent.click(await screen.findByRole('button', { name: t('i03.newClaim') }));
    await screen.findAllByRole('option', { name: 'Other document' });
    const addDocument = screen.getByRole('button', { name: t('i03.addDocument') });
    // Disabled until the order fields are complete.
    expect(addDocument).toBeDisabled();
    for (const name of ['bill-a.pdf', 'bill-b.pdf']) {
      await userEvent.selectOptions(document.getElementById('i03d-doctype') as HTMLSelectElement, 'OTHER');
      await userEvent.type(document.getElementById('i03d-docdate') as HTMLElement, '2026-10-06');
      await userEvent.type(document.getElementById('i03d-docauth') as HTMLElement, 'Depot Okhla');
      await userEvent.upload(
        document.getElementById('i03d-docfile') as HTMLInputElement,
        new File(['%PDF-1.7'], name, { type: 'application/pdf' }),
      );
      await userEvent.click(addDocument);
    }
    const added = screen.getByRole('list', { name: t('i03.documentsAdded') });
    expect(within(added).getAllByRole('listitem')).toHaveLength(2);
    await userEvent.click(within(added).getAllByRole('button', { name: t('i03.remove') })[0] as HTMLElement);
    const left = screen.getByRole('list', { name: t('i03.documentsAdded') });
    expect(within(left).getAllByRole('listitem')).toHaveLength(1);
    expect(left).toHaveTextContent('bill-b.pdf');
    expect(left).not.toHaveTextContent('bill-a.pdf');
    // The last row removed: the list itself goes.
    await userEvent.click(within(left).getByRole('button', { name: t('i03.remove') }));
    expect(screen.queryByRole('list', { name: t('i03.documentsAdded') })).toBeNull();
  });
});
