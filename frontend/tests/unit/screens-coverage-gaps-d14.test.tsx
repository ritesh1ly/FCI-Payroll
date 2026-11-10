import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CafeteriaScreen, ClaimsScreen } from '@/modules/allowance';
import { WageRevisions } from '@/modules/arrear';
import { InterestRun, OpeningBalanceCorrection, ParentZoneChange, SupplementaryPayment } from '@/modules/cpf';
import { amount } from '@/shared/ui';
import { backend, me, paged, referenceLists, sent, t, wrap } from './support/harness';

/**
 * Edge rows of I-25, I-13, I-22, I-12, C-12, I-03 and I-16 as Wireframes v6.1 draws them (OD-121; SRS-8-25, SRS-6-52,
 * SRS-6-43, SRS-6-48, SRS-6-45, SRS-11-14, SRS-11-16): a fitment without its value, the row chosen on a page kept when
 * another page is shown, a carried balance without interest date, an individual run whose member is held and a run
 * without reason, a claim document of a type the document types do not list, and a cafeteria selection without
 * category or start date.
 */
afterEach(() => vi.unstubAllGlobals());

const twoPages =
  <T,>(first: T[], second: T[]) =>
  (url: string) =>
    url.includes('page=1') ? paged(second, 21, url) : paged(first, 21, url);

const nextPage = () => userEvent.click(screen.getAllByRole('button', { name: /next/i })[0] as HTMLElement);

describe('I-25 revision list', () => {
  const revision = (id: number, over: object = {}) => ({
    id,
    code: `WR-2026-000${id}`,
    name: 'Pay revision',
    order: 'HQ/WR/2026',
    payrollTypes: 'STAFF_IDA',
    status: 'DEFINED',
    step: 2,
    createdAt: '2026-10-20T10:00:00',
    scope: 'ALL_INDIA',
    effectiveFrom: '2026-08-01',
    rule: 'PERCENTAGE',
    ruleValue: null,
    rounding: null,
    ...over,
  });

  it('SRS_8_25_aFitmentWithoutValueOrRoundingIsShownAloneAndTheChoiceFollowsThePage', async () => {
    backend({
      'GET /v1/wage-revisions?': twoPages([revision(4)], [revision(5)]),
      'GET /v1/wage-revisions/': () => ({ status: 404, body: { errorCode: 'WR-404' } }),
    });
    wrap(<WageRevisions />, me(['I-25:VIEW']));
    const table = await screen.findByRole('table', { name: t('screen.I-25') });
    const row = (await within(table).findByText('WR-2026-0004')).closest('tr') as HTMLElement;
    expect(
      within(row).getByText(
        (_, el) => el?.tagName === 'TD' && el.textContent === `${t('i25.fitment.PERCENTAGE')} `,
      ),
    ).toBeInTheDocument();
    expect(row).toHaveAttribute('aria-selected', 'true');
    await nextPage();
    const next = (await within(table).findByText('WR-2026-0005')).closest('tr') as HTMLElement;
    // the chosen revision is not on this page: the first row is chosen
    await waitFor(() => expect(next).toHaveAttribute('aria-selected', 'true'));
  });
});

describe('I-13 carried balance', () => {
  it('SRS_6_52_aCarriedBalanceWithoutInterestDateReadsADashForTheDate', async () => {
    const change = {
      reference: 'HRMS-PZC-0014',
      eis: '871905',
      name: 'S. Gupta',
      reason: 'PROMOTION_CAT_II_TO_I',
      fromZone: 'West',
      toZone: 'North',
      order: null,
      oldId: 'CPF/MH/2004/1188',
      newId: null,
      effective: null,
      decision: 'APPROVED',
      status: 'APPLIED',
      taskNumber: null,
    };
    backend({
      'GET /v1/cpf/parent-zone-changes/HRMS-PZC-0014': () => ({
        change,
        carriedEmployee: 1000,
        carriedEmployer: 500,
        carriedVpf: 50,
        interestTo: null,
        reconStatus: 'PENDING',
        sspSyncedAt: null,
        ecrFrom: null,
        iocpfStatus: null,
        iocpfAcknowledged: null,
        ids: [],
      }),
      'GET /v1/cpf/parent-zone-changes': () => paged([change]),
    });
    wrap(<ParentZoneChange />, me(['I-13:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    expect(
      await screen.findByText(t('i13w.carried.value', [amount(1000), amount(500), amount(50), '—'])),
    ).toBeInTheDocument();
  });
});

describe('I-22 and I-12 chosen rows across pages', () => {
  const line = (ref: string) => ({
    ref,
    eis: '871903',
    name: 'M. Verma',
    cpfCode: 'CPF/871903',
    part: 'EMPLOYEE',
    current: 1,
    corrected: 2,
    difference: 1,
    reasonCode: 'CLERICAL_ERROR',
    evidence: null,
    interestEffect: null,
    status: 'APPLIED',
    evidenceId: null,
    fileName: null,
    documentType: null,
    uploadedBy: null,
    uploadedAt: null,
  });

  it('SRS_6_43_theChosenCorrectionIsKeptWhenItsPageReturns', async () => {
    backend({
      'GET /v1/cpf/opening-balance-corrections/board': () => ({}),
      'GET /v1/cpf/opening-balance-corrections/lines': twoPages([line('OB-1'), line('OB-2')], [line('OB-3')]),
      'GET /v1/document-types': () => [],
    });
    const user = userEvent.setup();
    wrap(<OpeningBalanceCorrection />, me(['I-22:VIEW'], { roles: ['FINANCE'] }));
    const second = (await screen.findByText('OB-2')).closest('tr') as HTMLElement;
    await user.click(second);
    await nextPage();
    const third = (await screen.findByText('OB-3')).closest('tr') as HTMLElement;
    await waitFor(() => expect(third).toHaveAttribute('aria-selected', 'true'));
    await user.click(screen.getAllByRole('button', { name: /previous/i })[0] as HTMLElement);
    const back = (await screen.findByText('OB-1')).closest('tr') as HTMLElement;
    expect(back).toHaveAttribute('aria-selected', 'true');
  });

  it('SRS_6_48_theChosenPaymentIsKeptWhenItsPageReturns', async () => {
    const payment = (entry: string) => ({
      entry,
      eis: '871907',
      name: `Member ${entry}`,
      designation: null,
      category: null,
      employeeType: 'STAFF',
      cpfCode: null,
      posting: null,
      employmentStatus: 'SEPARATED',
      sanctionNumber: null,
      employee: 1,
      employer: 1,
      vpf: 0,
      sanctioned: 2,
      orderNumber: null,
      orderDate: null,
      paymentStatus: 'RELEASED',
      invoiceNumber: null,
      invoiceStatus: null,
      sentToApps: null,
      appsCancelStatus: null,
    });
    backend({
      'GET /v1/cpf/supplementary-payments': twoPages(
        [payment('CSP-1'), payment('CSP-2')],
        [payment('CSP-3')],
      ),
    });
    const user = userEvent.setup();
    wrap(<SupplementaryPayment />, me(['I-12:VIEW'], { roles: ['FINANCE'] }));
    await user.click((await screen.findByText('CSP-2')).closest('tr') as HTMLElement);
    await nextPage();
    expect(await screen.findByText(t('i12w.member', ['871907', 'Member CSP-3']))).toBeInTheDocument();
    await user.click(screen.getAllByRole('button', { name: /previous/i })[0] as HTMLElement);
    await screen.findByText('CSP-1');
    expect(screen.getByText(t('i12w.member', ['871907', 'Member CSP-1']))).toBeInTheDocument();
  });
});

describe('C-12 register runs', () => {
  it('SRS_6_45_anIndividualRunWithoutItsMemberSaysTheMemberIsHeldAndNoReasonIsADash', async () => {
    backend({
      'GET /v1/cpf/interest-runs': () => ({
        finYear: '2026-27',
        zone: 'NA11',
        members: 1,
        calculated: 0,
        notYetCalculated: 1,
        reRuns: 0,
        freezeStatus: 'OPEN',
        frozenBy: null,
        frozenAt: null,
        runs: [
          {
            run: 3,
            scope: 'INDIVIDUAL',
            eis: null,
            initiatedBy: 'm@x',
            initiatedAt: '2026-11-01T05:00:00Z',
            members: 1,
            calculated: 0,
            notCalculated: 1,
            stopped: 0,
            reason: null,
          },
        ],
      }),
    });
    const user = userEvent.setup();
    wrap(<InterestRun />, me(['C-12:VIEW'], { roles: ['FINANCE'] }));
    const section = await screen.findByTestId('c12-actions');
    await user.click(within(section).getByText(t('c12w.register')));
    const held = await within(section).findByText(t('c12.memberHeld'));
    const row = held.closest('tr') as HTMLElement;
    expect(within(row).getByText('—')).toBeInTheDocument();
  });
});

describe('I-03 claim document of an unlisted type', () => {
  it('SRS_11_14_aTypeTheDocumentTypesDoNotListNeedsNoReference', async () => {
    const claim = {
      id: 7,
      eis: 'L10702',
      name: 'Labour',
      memberType: 'LABOUR',
      claimType: 'LABOUR_TA',
      claimTypeLabel: 'Labour TA',
      periodFrom: '2026-10-01',
      periodTo: '2026-10-28',
      claimed: 100,
      entitlement: null,
      payable: null,
      capped: false,
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
      invoiceSentAt: null,
    };
    const calls = backend({
      'GET /v1/reference/lists': () => referenceLists({ ORDER_COPY_DOCUMENT_TYPE: [['BILL', 'Bill']] }),
      'GET /v1/claims/counts': () => ({}),
      'GET /v1/claims/leases': () => paged([]),
      'GET /v1/claims?': (url: string) => paged(url.includes('status=PAID') ? [] : [claim]),
      'GET /v1/claims/7': () => ({ claim, documents: [] }),
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other', referenceRequired: true }],
      'POST /v1/claims/7/documents': () => ({ status: 201, body: { id: 1 } }),
    });
    const user = userEvent.setup();
    wrap(<ClaimsScreen />, ['I-03:VIEW', 'I-03:VALIDATE', 'I-03:EDIT']);
    await user.click(await screen.findByText('CLM-7', { selector: 'b' }));
    const card = document.querySelector('[data-order-card]') as HTMLElement;
    await waitFor(() => expect(card.querySelector('input[type=file]')).not.toBeDisabled());
    const [, orderDate, office, file] = Array.from(card.querySelectorAll('input')) as [
      HTMLInputElement,
      HTMLInputElement,
      HTMLInputElement,
      HTMLInputElement,
    ];
    fireEvent.change(orderDate, { target: { value: '2026-10-01' } });
    await user.type(office, 'Depot office');
    await user.upload(file, new File(['x'], 'bill.pdf', { type: 'application/pdf' }));
    await waitFor(() => expect(sent(calls, 'POST', '/v1/claims/7/documents')).toHaveLength(1));
    const form = sent(calls, 'POST', '/v1/claims/7/documents')[0] as FormData;
    expect(form.get('documentType')).toBe('BILL');
    expect(form.get('reference')).toBeNull();
  });
});

describe('I-16 selections', () => {
  it('SRS_11_16_anAppliedSelectionWithoutStartAndAnotherStatusReadTheAmountAndNoCategoryADash', async () => {
    const selection = (over: object) => ({
      id: 1,
      eis: '104882',
      name: 'R. Sharma',
      category: null,
      finYear: '2026-27',
      version: 1,
      startDate: null,
      endDate: null,
      hrmsReference: null,
      status: 'APPLIED',
      components: [],
      payEffect: 1250,
      arrearFrom: null,
      arrearTo: null,
      arrearIn: null,
      ...over,
    });
    backend({
      'GET /v1/cafeteria-selections': () =>
        paged([selection({}), selection({ id: 2, eis: '104883', status: 'SUPERSEDED', payEffect: 800 })]),
    });
    wrap(<CafeteriaScreen />, ['I-16:VIEW']);
    const applied = (await screen.findByText('104882')).closest('tr') as HTMLElement;
    expect(within(applied).getByText(amount(1250))).toBeInTheDocument();
    // category, components and HRMS reference: dashes
    expect(within(applied).getAllByText('—')).toHaveLength(3);
    const other = screen.getByText('104883').closest('tr') as HTMLElement;
    expect(within(other).getByText(amount(800))).toBeInTheDocument();
  });
});
