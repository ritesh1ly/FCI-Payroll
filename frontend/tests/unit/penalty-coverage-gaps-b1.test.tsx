import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PenaltyScreen } from '@/modules/penalty';
import { backend, me, noContent, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

/**
 * I-06 Penalty, Recovery and Over-and-Above as drawn (SRS-14-1, SRS-14-10, SRS-14-16, SRS-14-19, SRS-G-30,
 * SRS-G-37; OD-121): the drawn buttons for the Maker who holds every right, the register rows of every kind, their
 * View / Edit / tick, the invoice cancellation with its reason, the Min-Net Guard card and the order held on the card.
 */

afterEach(() => vi.unstubAllGlobals());

const ALL = [
  'I-06:VIEW',
  'I-06:CREATE',
  'I-06:REVOKE',
  'I-06:OVER_ABOVE',
  'I-06:APPROVE',
  'I-06:EXPORT',
  'I-06:CANCEL',
];
const MAKER = (permissions = ALL) => me(permissions, { roles: ['PAYROLL_MAKER'] });

const COUNTS = {
  orders: 3,
  outstanding: 16000,
  payments: 2,
  paymentsAwaiting: 1,
  paymentsAwaitingAmount: 40000,
  minNetGuard: 4,
  incentiveRecovery: 0,
  carriedToFnf: 5,
};

const reg = (over: Record<string, unknown>) => ({
  kind: 'ORDER',
  id: 7,
  reference: 'PEN-1',
  eis: '881305',
  employee: 'Tapan Bose',
  source: 'ENT',
  nature: 'RECOVERY',
  reason: 'Order',
  amount: 24000,
  instalments: 6,
  perPeriod: 4000,
  approvedBy: 'ddo',
  status: 'ACTIVE',
  belowFloor: false,
  ...over,
});

const REGISTER = [
  reg({}),
  reg({ id: 8, reference: 'PEN-3', status: 'PAUSED_FNF', nature: 'BASIC_REDUCTION' }),
  reg({
    kind: 'PAYMENT',
    id: 4,
    reference: null,
    nature: 'OVER_ABOVE',
    reason: null,
    amount: null,
    instalments: null,
    perPeriod: null,
    approvedBy: null,
    status: 'SUBMITTED',
  }),
  reg({ kind: 'REQUEST', id: 9, reference: 'PEN-2', nature: null, status: 'SUBMITTED' }),
];

const order = (over: Record<string, unknown>) => ({
  id: 7,
  penaltyRef: 'PEN-1',
  eis: '881305',
  employee: 'Tapan Bose',
  nature: 'RECOVERY',
  orderReference: 'VIG/44',
  source: 'ENT',
  amount: 24000,
  instalments: 6,
  periodFrom: null,
  periodTo: null,
  recovered: 4000,
  waived: null,
  outstanding: 20000,
  status: 'APPROVED',
  backdated: false,
  ...over,
});

const payment = (over: Record<string, unknown>) => ({
  id: 4,
  eis: '881302',
  employee: 'Vani Menon',
  payCode: 101,
  payName: 'Honorarium',
  payrollYymm: '202610',
  amount: 12000,
  taxable: true,
  applicationDate: null,
  sanctionReference: 'SAN/1',
  sanctionDate: null,
  status: 'SUBMITTED',
  invoiceStatus: 'PENDING',
  invoiceSentAt: null,
  invoiceNo: null,
  appsCancelStatus: 'NOT_REQUESTED',
  checkerReason: null,
  ...over,
});

const STORED = {
  id: 3,
  fileName: 'penalty-order-7.pdf',
  documentTypeCode: 'SANCTION_ORDER',
  documentTypeName: 'Sanction Order',
  referenceNumber: 'VIG/44',
  uploadedBy: 'su.hq@fci-demo.test',
  uploadedAt: '2026-10-01T06:00:00Z',
};

function routes(over: Record<string, () => unknown> = {}) {
  return backend({
    'GET /v1/penalties/counts': () => COUNTS,
    'GET /v1/penalties/register': () => paged(REGISTER),
    'GET /v1/penalties/min-net': () => ({
      approvalId: 9,
      reference: 'PEN-2',
      eis: '881305',
      employee: 'Tapan Bose',
      gross: 64936,
      existingDeductions: 52936,
      proposedInstalment: 19767,
      resultingNet: -7767,
      floor: 5000,
    }),
    'GET /v1/penalties/over-and-above': () => paged([payment({})]),
    'GET /v1/penalties/7/orders': () => [STORED],
    'GET /v1/penalties/7': () => ({ order: order({ status: 'ACTIVE' }), schedule: [], revokes: [] }),
    'GET /v1/penalties/8': () => ({
      order: order({ id: 8, penaltyRef: 'PEN-3' }),
      schedule: [],
      revokes: [],
    }),
    'GET /v1/penalties?': () =>
      paged([
        order({}),
        order({ id: 8, penaltyRef: 'PEN-3', nature: 'BASIC_REDUCTION', status: 'REVOKED' }),
        order({ id: 10, penaltyRef: 'PEN-4', status: 'PENDING_APPROVAL', amount: null }),
      ]),
    'GET /v1/document-types': () => [
      { code: 'SANCTION_ORDER', name: 'Sanction Order', referenceRequired: true },
    ],
    'GET /v1/reference/lists': () =>
      referenceLists({ ORDER_COPY_DOCUMENT_TYPE: [['SANCTION_ORDER', 'Sanction Order']] }),
    ...over,
  });
}

const sectionOpen = () => screen.getByTestId('i06-register').hasAttribute('open');

describe('I-06 drawn actions for the Maker who holds every right', () => {
  it('SRS_14_1_eachDrawnButtonOpensTheClosedSectionAndTheBatchGoesToTheInbox', async () => {
    routes();
    wrap(<PenaltyScreen />, MAKER());
    const batch = await screen.findByRole('link', { name: t('i06w.batch') });
    expect(batch.getAttribute('href')).toContain('W-01');
    for (const name of [
      t('i06w.labour'),
      t('i06w.overAbove'),
      t('i06w.report'),
      t('i06w.revokeAct'),
      t('i06w.schedule'),
    ]) {
      const unmounted = !sectionOpen();
      expect(unmounted).toBe(true);
      await userEvent.click(screen.getAllByRole('button', { name })[0] as HTMLElement);
      await waitFor(() => expect(sectionOpen()).toBe(true));
      // Close it again through its summary, ready for the next drawn button.
      await userEvent.click(within(screen.getByTestId('i06-register')).getByText(t('i06w.closed')));
      await waitFor(() => expect(sectionOpen()).toBe(false));
    }
  });

  it('SRS_14_19_thePenaltiesListShowsEachNatureAndStatusWithItsPill', async () => {
    routes();
    wrap(<PenaltyScreen />, MAKER());
    const basic = (await screen.findByText(t('i06w.imposed.basic'))).closest('tr') as HTMLElement;
    expect(within(basic).getByText(t('i06.status.REVOKED'))).toHaveClass('p-info');
    const approved = screen.getAllByText('PEN-1')[0]?.closest('tr') as HTMLElement;
    expect(approved).toHaveTextContent('24,000');
    expect(within(approved).getByText(t('i06.status.APPROVED'))).toHaveClass('p-ok');
    const waiting = screen.getByText('PEN-4').closest('tr') as HTMLElement;
    expect(within(waiting).getByText(t('i06.status.PENDING_APPROVAL'))).toHaveClass('p-warn');
    expect(within(waiting).getAllByText('—').length).toBeGreaterThanOrEqual(2);
  });

  it('SRS_14_10_theRegisterReadsEveryKindWithItsStatusAndDash', async () => {
    routes();
    wrap(<PenaltyScreen />, MAKER());
    expect(await screen.findByText(t('i06w.status.fnf'))).toBeTruthy();
    expect(screen.getByText(t('i06.payment.SUBMITTED'))).toHaveClass('p-warn');
    expect(screen.getByText(t('i06.request.SUBMITTED'))).toBeTruthy();
    const request = screen
      .getByRole('button', { name: `${t('common.view')} PEN-2` })
      .closest('tr') as HTMLElement;
    expect(within(request).getByText(t('i06w.nature.REQUEST'))).toBeTruthy();
    // A payment without a reference is named by its id.
    expect(screen.getByRole('button', { name: `${t('common.view')} 4` })).toBeTruthy();
    expect(screen.getByRole('button', { name: `${t('common.edit')} 4` })).toBeEnabled();
  });
});

describe('I-06 register row actions', () => {
  it('SRS_14_16_viewingAnOrderOffersItsScheduleWhichOpensTheClosedSection', async () => {
    routes();
    wrap(<PenaltyScreen />, MAKER(['I-06:VIEW']));
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.view')} PEN-3` }));
    const dialog = await screen.findByRole('dialog', { name: t('wf.viewHead', [t('screen.I-06')]) });
    expect(within(dialog).getByText(t('i06w.nature.BASIC_REDUCTION'))).toBeTruthy();
    expect(within(dialog).getByText(t('i06w.status.fnf'))).toBeTruthy();
    await userEvent.click(within(dialog).getByRole('button', { name: t('i06w.schedule') }));
    await waitFor(() => expect(sectionOpen()).toBe(true));
    expect(screen.queryByRole('dialog', { name: t('wf.viewHead', [t('screen.I-06')]) })).toBeNull();
  });

  it('SRS_14_10_viewingAPaymentShowsDashesAndNoSchedule_closeShutsIt', async () => {
    routes();
    wrap(<PenaltyScreen />, MAKER(['I-06:VIEW']));
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.view')} 4` }));
    const dialog = await screen.findByRole('dialog', { name: t('wf.viewHead', [t('screen.I-06')]) });
    expect(within(dialog).getAllByText('—').length).toBeGreaterThanOrEqual(3);
    expect(within(dialog).queryByRole('button', { name: t('i06w.schedule') })).toBeNull();
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('SRS_14_10_viewingARequestNamesItAsARequest', async () => {
    routes();
    wrap(<PenaltyScreen />, MAKER(['I-06:VIEW']));
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.view')} PEN-2` }));
    const dialog = await screen.findByRole('dialog', { name: t('wf.viewHead', [t('screen.I-06')]) });
    expect(within(dialog).getByText(t('i06w.nature.REQUEST'))).toBeTruthy();
    expect(within(dialog).getByText(t('i06.request.SUBMITTED'))).toBeTruthy();
  });

  it('SRS_14_16_editingAnEnteredOrderOrAPaymentOpensTheClosedSection', async () => {
    routes();
    wrap(<PenaltyScreen />, MAKER(['I-06:VIEW', 'I-06:CREATE']));
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.edit')} PEN-1` }));
    await waitFor(() => expect(sectionOpen()).toBe(true));
    await userEvent.click(within(screen.getByTestId('i06-register')).getByText(t('i06w.closed')));
    await waitFor(() => expect(sectionOpen()).toBe(false));
    await userEvent.click(screen.getByRole('button', { name: `${t('common.edit')} 4` }));
    await waitFor(() => expect(sectionOpen()).toBe(true));
  });

  it('SRS_14_10_withoutCreateTheEditIsOffWithTheEditReason', async () => {
    routes();
    wrap(<PenaltyScreen />, MAKER(['I-06:VIEW']));
    const edit = await screen.findByRole('button', { name: `${t('common.edit')} PEN-1` });
    expect(edit).toBeDisabled();
    expect(edit).toHaveAttribute('data-why', t('i06w.why.edit'));
  });

  it('M08_08_theApproverTickGoesToTheInboxForAWaitingRowAndSaysNothingWaitsOtherwise', async () => {
    const assign = vi.fn();
    routes();
    vi.stubGlobal('location', { ...window.location, assign });
    wrap(<PenaltyScreen />, MAKER(['I-06:VIEW', 'I-06:APPROVE']));
    const rowOf = (ref: string) =>
      screen.getByRole('button', { name: `${t('common.view')} ${ref}` }).closest('tr') as HTMLElement;
    await screen.findByRole('button', { name: `${t('common.view')} PEN-1` });
    const activeRow = rowOf('PEN-1');
    await userEvent.click(within(activeRow).getByTitle(t('wf.approveTitle')));
    expect(await screen.findByText(t('mst.nothingWaiting'))).toBeTruthy();
    expect(assign).not.toHaveBeenCalled();
    const request = rowOf('PEN-2');
    await userEvent.click(within(request).getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('W-01'));
    assign.mockClear();
    const submittedPayment = rowOf('4');
    await userEvent.click(within(submittedPayment).getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledTimes(1);
  });
});

describe('I-06 invoices (SRS-G-37)', () => {
  const invoiceRow = async () => {
    const card = (await screen.findByText(t('i06w.invoices'))).closest('.card') as HTMLElement;
    return (await within(card).findByText('SAN/1')).closest('tr') as HTMLElement;
  };

  it('SRS_G_37_anOpenUnsentPaymentIsCancelledWithItsReason', async () => {
    const calls = routes({
      'POST /v1/penalties/over-and-above/4/cancellations': () => ({ status: 202, body: { approvalId: 51 } }),
    });
    wrap(<PenaltyScreen />, MAKER());
    const row = await invoiceRow();
    expect(row).toHaveTextContent('881302 Vani Menon');
    expect(row).toHaveTextContent('101 Honorarium');
    await userEvent.click(within(row).getByRole('button', { name: t('wf.inv.cancel') }));
    const dialog = await screen.findByRole('dialog', { name: t('i06.paymentCancelTitle', ['4']) });
    await userEvent.type(within(dialog).getByRole('textbox'), 'Duplicate sanction');
    await userEvent.click(within(dialog).getByRole('button', { name: t('i06.paymentCancelConfirm') }));
    await waitFor(() =>
      expect(sent(calls, 'POST', '/over-and-above/4/cancellations')).toEqual([
        { reason: 'Duplicate sanction' },
      ]),
    );
    expect(await screen.findByText(t('i06.cancelSent', ['4']))).toBeTruthy();
    expect(screen.queryByRole('dialog', { name: t('i06.paymentCancelTitle', ['4']) })).toBeNull();
  });

  it('SRS_G_37_aRefusedCancellationShowsTheProblem', async () => {
    routes({
      'POST /v1/penalties/over-and-above/4/cancellations': () => problem(409, 'PEN-409-SENT'),
    });
    wrap(<PenaltyScreen />, MAKER());
    const row = await invoiceRow();
    await userEvent.click(within(row).getByRole('button', { name: t('wf.inv.cancel') }));
    const dialog = await screen.findByRole('dialog', { name: t('i06.paymentCancelTitle', ['4']) });
    await userEvent.type(within(dialog).getByRole('textbox'), 'Wrong member');
    await userEvent.click(within(dialog).getByRole('button', { name: t('i06.paymentCancelConfirm') }));
    expect(await screen.findByText(t('error.PEN-409-SENT'))).toBeTruthy();
    expect(screen.queryByText(t('i06.cancelSent', ['4']))).toBeNull();
  });

  it('SRS_G_37_closingTheReasonDialogSendsNothing', async () => {
    const calls = routes();
    wrap(<PenaltyScreen />, MAKER());
    const row = await invoiceRow();
    await userEvent.click(within(row).getByRole('button', { name: t('wf.inv.cancel') }));
    const dialog = await screen.findByRole('dialog', { name: t('i06.paymentCancelTitle', ['4']) });
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('dialog', { name: t('i06.paymentCancelTitle', ['4']) })).toBeNull();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('SRS_G_37_cancelIsOffWithItsReasonForAClosedPaymentOrARoleWithoutTheRight', async () => {
    routes({
      'GET /v1/penalties/over-and-above': () =>
        paged([
          payment({
            status: 'PAID',
            employee: null,
            payCode: null,
            payName: null,
            amount: null,
            invoiceNo: 12,
            appsCancelStatus: 'CONFIRMED',
          }),
        ]),
    });
    const view = wrap(<PenaltyScreen />, MAKER());
    let row = await invoiceRow();
    expect(row).toHaveTextContent('881302');
    expect(row).toHaveTextContent(t('inv.cancelledInApps'));
    expect(within(row).getByRole('button', { name: t('wf.inv.cancel') })).toHaveAttribute(
      'data-why',
      t('i06w.why.notOpen'),
    );
    view.unmount();
    vi.unstubAllGlobals();
    routes();
    wrap(<PenaltyScreen />, MAKER(['I-06:VIEW']));
    row = await invoiceRow();
    expect(within(row).getByRole('button', { name: t('wf.inv.cancel') })).toHaveAttribute(
      'data-why',
      t('i06w.why.maker'),
    );
  });

  it('SRS_G_37_aCancelledPaymentShowsItsCancelledVersion_andNoPaymentsShowAnEmptyCard', async () => {
    routes({
      'GET /v1/penalties/over-and-above': () => paged([payment({ status: 'CANCELLED' })]),
    });
    const view = wrap(<PenaltyScreen />, MAKER());
    const row = await invoiceRow();
    expect(within(row).getByText(t('wf.inv.cancelledVersion'))).toBeTruthy();
    view.unmount();
    vi.unstubAllGlobals();
    routes({ 'GET /v1/penalties/over-and-above': () => paged([]) });
    wrap(<PenaltyScreen />, MAKER());
    const card = (await screen.findByText(t('i06w.invoices'))).closest('.card') as HTMLElement;
    expect(within(card).queryByText('SAN/1')).toBeNull();
  });
});

describe('I-06 tiles and Min-Net Guard', () => {
  it('SRS_14_10_theTilesAndTheControlsReadTheCounts_aSetFloorIsShownInRupees', async () => {
    routes();
    wrap(<PenaltyScreen />, MAKER(['I-06:VIEW']));
    expect(await screen.findByText(t('i06w.tile.orders.sub', ['16,000']))).toBeTruthy();
    expect(screen.getByText(t('i06w.tile.oa.sub', ['40,000']))).toBeTruthy();
    expect(await screen.findByText('₹ 5,000')).toBeTruthy();
    expect(screen.queryByText(t('i06w.g.setting'))).toBeNull();
    expect(screen.getByText('5')).toBeTruthy();
  });

  it('SRS_14_10_withoutCountsOrGuardTheTilesAndCardSayNothing_andTheProblemIsShown', async () => {
    routes({
      'GET /v1/penalties/counts': () => problem(500, 'SYS-500'),
      'GET /v1/penalties/min-net': () => noContent(),
    });
    wrap(<PenaltyScreen />, MAKER(['I-06:VIEW']));
    expect(await screen.findByText(t('error.SYS-500'))).toBeTruthy();
    expect(screen.getByText(t('i06w.guard', ['—']))).toBeTruthy();
    expect(screen.getByText(t('i06w.g.setting'))).toBeTruthy();
    expect(screen.queryByText(t('i06w.g.blocked'))).toBeNull();
    expect(screen.queryByText(t('i06w.tile.orders.sub', ['16,000']))).toBeNull();
  });
});

describe('I-06 order card (SRS-G-30)', () => {
  it('SRS_G_30_theStoredOrderOfTheFirstPenaltyLinksToItsFile', async () => {
    routes();
    wrap(<PenaltyScreen />, MAKER(['I-06:VIEW']));
    const row = (await screen.findByText('penalty-order-7.pdf', {}, { timeout: 8000 })).closest(
      'tr',
    ) as HTMLElement;
    expect(
      within(row)
        .getByRole('link', { name: t('wf.view') })
        .getAttribute('href'),
    ).toContain('/v1/penalties/7/files/3');
  });

  it('SRS_G_30_theMakerHoldsTheOrderOnTheCardAndItIsCarriedIntoTheClosedSection_nothingIsStored', async () => {
    const calls = routes({ 'GET /v1/penalties/7/orders': () => [] });
    wrap(<PenaltyScreen />, MAKER(['I-06:VIEW', 'I-06:CREATE']));
    const card = (await screen.findByText(t('wf.ord.title'))).closest('[data-order-card]') as HTMLElement;
    await screen.findByRole('option', { name: 'Sanction Order' }, { timeout: 8000 });
    const inputs = card.querySelectorAll('input');
    await userEvent.type(inputs[0] as HTMLElement, 'VIG/2026/9');
    await userEvent.type(inputs[1] as HTMLElement, '2026-01-02');
    await userEvent.type(inputs[2] as HTMLElement, 'Headquarters');
    await userEvent.upload(
      inputs[3] as HTMLInputElement,
      new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }),
    );
    await waitFor(() => expect(sectionOpen()).toBe(true));
    expect(calls.filter((c) => c.method === 'POST')).toEqual([]);
  });

  it('SRS_G_30_aDocumentTypeTheTypeListDoesNotNameNeedsNoReference', async () => {
    routes({ 'GET /v1/penalties/7/orders': () => [], 'GET /v1/document-types': () => [] });
    wrap(<PenaltyScreen />, MAKER(['I-06:VIEW', 'I-06:CREATE']));
    const card = (await screen.findByText(t('wf.ord.title'))).closest('[data-order-card]') as HTMLElement;
    await screen.findByRole('option', { name: 'Sanction Order' }, { timeout: 8000 });
    const inputs = card.querySelectorAll('input');
    await userEvent.type(inputs[1] as HTMLElement, '2026-01-02');
    await userEvent.type(inputs[2] as HTMLElement, 'Headquarters');
    await userEvent.upload(
      inputs[3] as HTMLInputElement,
      new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }),
    );
    await waitFor(() => expect(sectionOpen()).toBe(true));
  });

  it('SRS_G_30_aRoleWithoutCreateSeesTheCardReadOnly', async () => {
    routes({ 'GET /v1/penalties/7/orders': () => [] });
    wrap(<PenaltyScreen />, MAKER(['I-06:VIEW']));
    const card = (await screen.findByText(t('wf.ord.title'))).closest('[data-order-card]') as HTMLElement;
    expect(card.querySelector('input')).toBeDisabled();
  });
});
