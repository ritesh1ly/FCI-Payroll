import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ClaimRow } from '@/modules/allowance/api/claimsApi';
import { CafeteriaAllowance, Claims } from '@/modules/allowance';
import { backend, me, paged, problem, t, wrap } from './support/harness';

/**
 * I-03 Allowances & Reimbursements and I-16 Cafeteria (plan 10; SRS-11-1, 11-6, 11-9, 11-11, 11-13,
 * 11-14, 11-16, 11-19, 11-21, 11-23): the register with its cap and source; a labour claim entered
 * with its document; the Maker's validation; the taxable flag and the IOGA of a medical claim; the
 * HRMS claims waiting on I-21; the Medical Claim view; I-16 read-only; nothing without VIEW.
 */

afterEach(() => vi.unstubAllGlobals());

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
  capped: true,
  status: 'SUBMITTED',
  source: 'LDH',
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
};

const MEDICAL: ClaimRow = {
  ...ROW,
  id: 8,
  eis: '104882',
  name: 'Asha Rao',
  memberType: 'EMPLOYEE',
  claimType: 'MEDICAL',
  claimTypeLabel: 'Medical reimbursement',
  capped: false,
  claimed: 2500,
  payable: 2500,
  status: 'PAID',
  source: 'HRMS',
  hrmsRequestId: 'HR-77',
  taxable: true,
  taxableConfirmedAt: '2026-10-20T05:00:00Z',
  iogaNumber: 'IOGA/1',
  iogaDate: '2026-10-02',
  iogaAmount: 2500,
  taxTreatment: 'Y',
  taxableAmount: 2500,
};

const ALL = ['I-03:VIEW', 'I-03:CREATE', 'I-03:VALIDATE', 'I-03:EDIT', 'I-03:EXPORT'];

function routes(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
  return backend({
    'GET /v1/claims?': (url: string) =>
      paged([ROW, { ...ROW, id: 9, status: 'REJECTED', capped: false, decisionReason: 'No order' }], 2, url),
    'GET /v1/claims/7': () => ({
      claim: ROW,
      documents: [
        {
          id: 3,
          documentTypeName: 'Other document',
          referenceNumber: null,
          fileName: 'bill.pdf',
          uploadedBy: 'ldh',
          uploadedAt: '2026-10-06T05:00:00Z',
        },
      ],
    }),
    'GET /v1/claims/8': () => ({ claim: MEDICAL, documents: [] }),
    'GET /v1/claims/pending-hrms': (url: string) =>
      paged(
        [
          {
            taskId: 4,
            taskNo: 'HCA-2026-0004',
            eis: '104882',
            name: 'Asha Rao',
            value: '{"claimType":"MEDICAL"}',
            status: 'PENDING_CHECKER',
          },
        ],
        1,
        url,
      ),
    'GET /v1/claims/types': () => [{ code: 'LABOUR_TA', label: 'Labour TA', payCode: 915 }],
    'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
    ...over,
  });
}

describe('I-03 allowances and reimbursements', () => {
  it('SRS_11_11_theRegisterShowsTheCapTheSourceAndTheReasonOfARejection', async () => {
    routes();
    wrap(<Claims />, me(ALL));
    const table = await screen.findByRole('table', { name: t('i03.register') });
    expect(table).toHaveTextContent(t('i03.capped'));
    expect(table).toHaveTextContent(t('i03.src.LDH'));
    expect(table).toHaveTextContent('No order');
    expect(await screen.findByRole('table', { name: t('i03.pendingHrms') })).toHaveTextContent(
      'HCA-2026-0004',
    );
    expect(screen.getByRole('button', { name: t('i03.medicalRegister') })).toBeDisabled();
  });

  it('SRS_11_13_theMakerValidatesASubmittedLabourClaimAndSeesItsDocuments', async () => {
    const calls = routes({
      'POST /v1/claims/7/validations': () => ({ status: 202, body: { id: 40, status: 'PENDING_APPROVAL' } }),
    });
    wrap(<Claims />, me(ALL));
    const table = await screen.findByRole('table', { name: t('i03.register') });
    await userEvent.click(
      within(table).getAllByRole('button', { name: t('i03.claimNo', [7]) })[0] as HTMLElement,
    );
    const panel = await screen.findByRole('region', { name: t('i03.claimNo', [7]) });
    expect(await within(panel).findByRole('link', { name: 'bill.pdf' })).toHaveAttribute(
      'href',
      '/nginx/fci-payroll/v1/claims/7/documents/3',
    );
    expect(panel).toHaveTextContent(t('i03.cappedHint'));
    // US-GAP-209: a claim type whose pay code has no M-07 Taxable shows the impact not configured.
    expect(within(panel).getByTestId('i03-tax-impact')).toHaveTextContent(t('i03.taxImpactNone'));
    const validate = within(panel).getByLabelText(t('i03.validate'));
    await userEvent.type(within(validate).getByLabelText(t('i03.reason')), 'Checked bill');
    await userEvent.click(within(validate).getByRole('button', { name: t('i03.validate') }));
    expect(await screen.findByText(t('i03.sent'))).toBeInTheDocument();
    expect(calls.find((c) => c.url.includes('/validations'))?.body).toEqual({ reason: 'Checked bill' });
    await userEvent.click(within(panel).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('region', { name: t('i03.claimNo', [7]) })).toBeNull();
  });

  it('SRS_11_19_theTaxableFlagIsConfirmedAndTheIogaSentForAMedicalClaim', async () => {
    const calls = routes({
      'GET /v1/claims?': (url: string) => paged([MEDICAL], 1, url),
      'POST /v1/claims/8/taxable-confirmations': () => MEDICAL,
      'POST /v1/claims/8/iogas': () => ({ status: 202, body: { id: 41, status: 'PENDING_APPROVAL' } }),
    });
    wrap(<Claims />, me(ALL));
    const table = await screen.findByRole('table', { name: t('i03.register') });
    await userEvent.click(within(table).getByRole('button', { name: t('i03.claimNo', [8]) }));
    const panel = await screen.findByRole('region', { name: t('i03.claimNo', [8]) });
    expect(panel).toHaveTextContent('HR-77');
    expect(panel).toHaveTextContent('IOGA/1');
    // US-GAP-209: the tax impact - the payable added to the taxable earnings, and its treatment.
    expect(within(panel).getByTestId('i03-tax-impact')).toHaveTextContent(
      t('i03.taxImpact', ['2,500', t('i03.taxTreatment.Y')]),
    );
    await userEvent.click(within(panel).getByRole('button', { name: t('i03.confirmTaxable') }));
    const dialog = await screen.findByRole('dialog');
    // SRS-11-19 (UI review #94): nothing is preselected; the user must choose before saving.
    expect(within(dialog).getByRole('radio', { name: t('i03.taxableYes') })).not.toBeChecked();
    expect(within(dialog).getByRole('radio', { name: t('i03.taxableNo') })).not.toBeChecked();
    expect(within(dialog).getByRole('button', { name: t('i03.taxableSave') })).toBeDisabled();
    await userEvent.click(within(dialog).getByRole('radio', { name: t('i03.taxableNo') }));
    await userEvent.click(within(dialog).getByRole('button', { name: t('i03.taxableSave') }));
    expect(await screen.findByText(t('i03.taxableSaved'))).toBeInTheDocument();
    expect(calls.find((c) => c.url.includes('/taxable-confirmations'))?.body).toEqual({ taxable: false });
    const ioga = within(panel).getByRole('group', { name: t('i03.ioga') });
    const send = within(ioga).getByRole('button', { name: t('i03.iogaSend') });
    expect(send).toBeDisabled();
    await userEvent.type(within(ioga).getByLabelText(t('i03.iogaNumber')), 'IOGA/2');
    await userEvent.type(within(ioga).getByLabelText(t('i03.iogaDate')), '2026-10-03');
    await userEvent.type(within(ioga).getByLabelText(t('i03.iogaAmount')), '1500');
    await userEvent.type(within(ioga).getByLabelText(t('i03.iogaOffice')), 'DO Mumbai');
    await userEvent.click(send);
    expect(await screen.findByText(t('i03.sent'))).toBeInTheDocument();
    expect(calls.find((c) => c.url.includes('/iogas'))?.body).toMatchObject({
      number: 'IOGA/2',
      originatingOffice: 'DO Mumbai',
      reason: null,
    });
  });

  it('SRS_11_14_aLabourClaimIsEnteredWithItsDocumentAndARefusalIsShown', async () => {
    let refuse = true;
    let overlap = true;
    const calls = routes({
      'POST /v1/claims': () =>
        refuse
          ? problem(422, 'ALW-422-DOCUMENTS', ['OTHER'])
          : overlap
            ? { status: 201, body: { ...ROW, id: 11, status: 'OVERLAP_REJECTED', payable: null } }
            : { status: 201, body: { ...ROW, id: 12, status: 'ENTERED', payable: 6000 } },
    });
    wrap(<Claims />, me(ALL));
    await userEvent.click(await screen.findByRole('button', { name: t('i03.newClaim') }));
    const enter = screen.getByRole('button', { name: t('i03.enter') });
    expect(enter).toBeDisabled();
    await userEvent.type(screen.getByLabelText(t('i03.labourNumber')), 'L10001');
    await screen.findByRole('option', { name: 'Labour TA' });
    await userEvent.selectOptions(screen.getByLabelText(t('i03.type')), 'LABOUR_TA');
    await userEvent.type(screen.getByLabelText(t('i03.from')), '2026-10-01');
    await userEvent.type(screen.getByLabelText(t('i03.to')), '2026-10-05');
    await userEvent.type(screen.getByLabelText(t('i03.claimed'), { selector: '#i03-amount' }), '6000');
    expect(enter).toBeEnabled();
    await userEvent.click(enter);
    expect(await screen.findByText(t('error.ALW-422-DOCUMENTS', ['OTHER']))).toBeInTheDocument();
    await screen.findAllByRole('option', { name: 'Other document' });
    await userEvent.selectOptions(document.getElementById('i03d-doctype') as HTMLSelectElement, 'OTHER');
    await userEvent.type(document.getElementById('i03d-docdate') as HTMLElement, '2026-10-06');
    await userEvent.type(document.getElementById('i03d-docauth') as HTMLElement, 'Depot Okhla');
    await userEvent.upload(
      document.getElementById('i03d-docfile') as HTMLInputElement,
      new File(['%PDF-1.7'], 'bill.pdf', { type: 'application/pdf' }),
    );
    await userEvent.click(screen.getByRole('button', { name: t('i03.addDocument') }));
    const added = screen.getByRole('list', { name: t('i03.documentsAdded') });
    expect(added).toHaveTextContent('bill.pdf');
    refuse = false;
    await userEvent.click(enter);
    expect(await screen.findByText(t('i03.overlapRecorded'))).toBeInTheDocument();
    const form = calls.filter((c) => c.method === 'POST').at(-1)?.body as FormData;
    // Entered again without the overlap: recorded with its payable amount.
    overlap = false;
    await userEvent.click(enter);
    expect(await screen.findByText(t('i03.entered'))).toBeInTheDocument();
    expect((form.getAll('files')[0] as File).name).toBe('bill.pdf');
    const claim = JSON.parse(await (form.get('claim') as Blob).text());
    expect(claim).toMatchObject({
      memberNumber: 'L10001',
      claimType: 'LABOUR_TA',
      documents: [{ documentType: 'OTHER' }],
    });
    // UI review #96: the entered claim clears the form (no second entry by a second click).
    expect(screen.getByLabelText(t('i03.labourNumber'))).toHaveValue('');
    expect(screen.queryByRole('list', { name: t('i03.documentsAdded') })).toBeNull();
    expect(screen.getByRole('button', { name: t('i03.enter') })).toBeDisabled();
    await userEvent.click(screen.getAllByRole('button', { name: t('common.close') })[0] as HTMLElement);
    expect(screen.queryByRole('button', { name: t('i03.enter') })).toBeNull();
  });

  it('SRS_11_19_theMedicalViewListsAMembersClaimsByMonthAndTheRegisterExports', async () => {
    routes({ 'GET /v1/claims/medical': () => [MEDICAL] });
    wrap(<Claims />, me(ALL));
    const card = (await screen.findByText(t('i03.medical'))).closest('.card') as HTMLElement;
    const show = within(card).getByRole('button', { name: t('i03.show') });
    expect(show).toBeDisabled();
    await userEvent.type(within(card).getByLabelText(t('i03.eis')), '104882');
    await userEvent.click(show);
    expect(await within(card).findByRole('table', { name: t('i03.medical') })).toHaveTextContent('2026-10');
    await userEvent.type(within(card).getByLabelText(t('i03.month')), '2026-10');
    expect(within(card).getByRole('button', { name: t('i03.medicalRegister') })).toBeEnabled();
  });

  it('SRS_11_19_anUnadjudicatedMedicalClaimShowsDashesAndTheTaxableDialogCanBeLeftOrRefused', async () => {
    const BARE: ClaimRow = {
      ...MEDICAL,
      claimTypeLabel: null,
      entitlement: null,
      payable: null,
      taxable: false,
      taxableConfirmedAt: null,
      iogaNumber: null,
      hrmsRequestId: null,
      taxTreatment: 'N',
      taxableAmount: null,
    };
    let refuse = true;
    routes({
      'GET /v1/claims?': (url: string) => paged([BARE], 1, url),
      'GET /v1/claims/8': () => ({ claim: BARE, documents: [] }),
      'GET /v1/claims/medical': () => [BARE],
      'POST /v1/claims/8/taxable-confirmations': () =>
        refuse ? problem(409, 'ALW-409-STATE', ['PAID']) : BARE,
    });
    wrap(<Claims />, me(ALL));
    const table = await screen.findByRole('table', { name: t('i03.register') });
    await userEvent.click(within(table).getByRole('button', { name: t('i03.claimNo', [8]) }));
    const panel = await screen.findByRole('region', { name: t('i03.claimNo', [8]) });
    expect(panel).toHaveTextContent('MEDICAL');
    expect(panel).toHaveTextContent('—');
    expect(panel).toHaveTextContent(t('i03.taxableNo'));
    // US-GAP-209: no payable yet - the treatment is known, the amount not.
    expect(within(panel).getByTestId('i03-tax-impact')).toHaveTextContent(
      t('i03.taxImpact', ['—', t('i03.taxTreatment.N')]),
    );
    expect(within(panel).queryByRole('list', { name: t('i03.documents') })).toBeNull();
    await userEvent.click(within(panel).getByRole('button', { name: t('i03.confirmTaxable') }));
    await userEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
    await userEvent.click(within(panel).getByRole('button', { name: t('i03.confirmTaxable') }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('radio', { name: t('i03.taxableNo') }));
    await userEvent.click(within(dialog).getByRole('radio', { name: t('i03.taxableYes') }));
    await userEvent.click(within(dialog).getByRole('button', { name: t('i03.taxableSave') }));
    expect(await within(panel).findByRole('alert')).toBeInTheDocument();
    refuse = false;
    const card = (await screen.findByText(t('i03.medical'))).closest('.card') as HTMLElement;
    await userEvent.type(within(card).getByLabelText(t('i03.eis')), '104882');
    await userEvent.click(within(card).getByRole('button', { name: t('i03.show') }));
    const medical = await within(card).findByRole('table', { name: t('i03.medical') });
    expect(within(medical).getAllByText('—')).toHaveLength(2);
  });

  it('SRS_11_19_aMemberWithoutMedicalClaimsSaysSo', async () => {
    routes({ 'GET /v1/claims/medical': () => [] });
    wrap(<Claims />, me(['I-03:VIEW']));
    const card = (await screen.findByText(t('i03.medical'))).closest('.card') as HTMLElement;
    await userEvent.type(within(card).getByLabelText(t('i03.eis')), '104882');
    await userEvent.click(within(card).getByRole('button', { name: t('i03.show') }));
    expect(await within(card).findByText(t('i03.noMedical'))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('i03.newClaim') })).toBeNull();
    expect(within(card).queryByRole('button', { name: t('i03.medicalRegister') })).toBeNull();
  });

  it('SRS_11_13_aReaderSeesNoActionsAndNothingShowsWithoutView', async () => {
    routes();
    const { unmount } = wrap(<Claims />, me(['I-03:VIEW']));
    const table = await screen.findByRole('table', { name: t('i03.register') });
    await userEvent.click(
      within(table).getAllByRole('button', { name: t('i03.claimNo', [7]) })[0] as HTMLElement,
    );
    const panel = await screen.findByRole('region', { name: t('i03.claimNo', [7]) });
    await within(panel).findByRole('link', { name: 'bill.pdf' });
    expect(within(panel).queryByLabelText(t('i03.validate'))).toBeNull();
    unmount();
    const view = wrap(<Claims />, me([]));
    expect(view.container.textContent).toBe('');
  });

  it('SRS_11_13_theFiltersNarrowTheRegisterOnTheServer', async () => {
    const calls = routes();
    wrap(<Claims />, me(ALL));
    await screen.findByRole('table', { name: t('i03.register') });
    await userEvent.selectOptions(screen.getByLabelText(t('i03.memberType')), 'LABOUR');
    await userEvent.selectOptions(screen.getByLabelText(t('i03.status')), 'CAPPED');
    await userEvent.type(screen.getAllByLabelText(t('i03.eis'))[0] as HTMLElement, 'L1');
    await screen.findByRole('table', { name: t('i03.register') });
    expect(calls.some((c) => c.url.includes('memberType=LABOUR') && c.url.includes('status=CAPPED'))).toBe(
      true,
    );
  });
  it('SRS_11_13_clearedFiltersAskForEveryClaimAndAnUnadjudicatedClaimShowsDashes', async () => {
    const calls = routes({
      'GET /v1/claims?': (url: string) =>
        paged([{ ...ROW, claimTypeLabel: null, entitlement: null, payable: null, capped: false }], 1, url),
    });
    wrap(<Claims />, me(ALL));
    const register = await screen.findByRole('table', { name: t('i03.register') });
    expect(register).toHaveTextContent('LABOUR_TA');
    // Entitlement and payable not adjudicated; no invoice sent yet; a labour claim has no HRMS id.
    expect(within(register).getAllByText('—')).toHaveLength(4);
    await userEvent.selectOptions(screen.getByLabelText(t('i03.memberType')), 'EMPLOYEE');
    await userEvent.selectOptions(screen.getByLabelText(t('i03.memberType')), '');
    await userEvent.selectOptions(screen.getByLabelText(t('i03.status')), 'PAID');
    await userEvent.selectOptions(screen.getByLabelText(t('i03.status')), '');
    const eis = screen.getAllByLabelText(t('i03.eis'))[0] as HTMLElement;
    await userEvent.type(eis, ' ');
    await userEvent.clear(eis);
    await screen.findByRole('table', { name: t('i03.register') });
    const last = calls.filter((c) => c.url.includes('/v1/claims?')).at(-1)?.url ?? '';
    expect(last).not.toContain('memberType=');
    expect(last).not.toContain('status=');
    expect(last).not.toContain('eis=');
  });
});

describe('I-16 cafeteria allowance', () => {
  it('SRS_11_16_theSelectionsFromHrmsAreShownReadOnlyWithThisMonthsPayEffect', async () => {
    const calls = backend({
      'GET /v1/cafeteria-selections': (url: string) =>
        paged(
          [
            {
              id: 2,
              eis: '104882',
              name: 'Asha Rao',
              category: 'IDA',
              finYear: '2026-27',
              version: 2,
              startDate: '2026-10-16',
              endDate: null,
              hrmsReference: 'HR-CAF-1',
              status: 'APPLIED',
              components: [{ payCode: 917, name: 'Newspaper', monthlyAmount: 600 }],
              payEffect: 310,
            },
            {
              // An earlier version: ended, without a category or an HRMS reference.
              id: 1,
              eis: '104882',
              name: 'Asha Rao',
              category: null,
              finYear: '2026-27',
              version: 1,
              startDate: '2026-04-01',
              endDate: '2026-10-15',
              hrmsReference: null,
              status: 'SUPERSEDED',
              components: [{ payCode: 917, name: 'Newspaper', monthlyAmount: 500 }],
              payEffect: 250,
            },
          ],
          2,
          url,
        ),
    });
    wrap(<CafeteriaAllowance />, me(['I-16:VIEW']));
    const table = await screen.findByRole('table', { name: t('i16.title') });
    expect(table).toHaveTextContent('HR-CAF-1');
    expect(table).toHaveTextContent(t('i16.st.APPLIED'));
    expect(table).toHaveTextContent(t('i16.version', ['2026-27', 2]));
    expect(within(table).getAllByText('—')).toHaveLength(2);
    expect(table).toHaveTextContent(' – ');
    expect(screen.queryByRole('button', { name: t('action.newEntry') })).toBeNull();
    await userEvent.type(screen.getByLabelText(t('i03.eis')), '104882');
    await userEvent.click(screen.getByRole('button', { name: t('i03.show') }));
    await screen.findByRole('table', { name: t('i16.title') });
    expect(calls.some((c) => c.url.includes('eis=104882'))).toBe(true);
  });

  it('SRS l.2836-2850: the selections are asked for the chosen year; a searched member without one reads No selection in HRMS; a bad EIS cannot be asked', async () => {
    const calls = backend({ 'GET /v1/cafeteria-selections': (url: string) => paged([], 0, url) });
    wrap(<CafeteriaAllowance />, me(['I-16:VIEW']));
    await screen.findByText(t('i16.none'));
    expect(calls.some((c) => /finYear=\d{4}-\d{2}/.test(c.url))).toBe(true);
    await userEvent.type(screen.getByLabelText(t('i03.eis')), '1048-82');
    expect(screen.getByRole('button', { name: t('i03.show') })).toBeDisabled();
    await userEvent.clear(screen.getByLabelText(t('i03.eis')));
    await userEvent.type(screen.getByLabelText(t('i03.eis')), '104882');
    await userEvent.click(screen.getByRole('button', { name: t('i03.show') }));
    expect(await screen.findByText(t('i16.noSelection', ['104882']))).toBeInTheDocument();
  });

  it('SRS_11_16_noSelectionSaysSoAndNothingShowsWithoutView', async () => {
    backend({ 'GET /v1/cafeteria-selections': (url: string) => paged([], 0, url) });
    const { unmount } = wrap(<CafeteriaAllowance />, me(['I-16:VIEW']));
    expect(await screen.findByText(t('i16.none'))).toBeInTheDocument();
    unmount();
    const view = wrap(<CafeteriaAllowance />, me([]));
    expect(view.container.textContent).toBe('');
  });
});
