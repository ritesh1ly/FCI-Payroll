import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { OrderDetail, OrderRow, PaymentRow } from '@/modules/penalty/api/penaltyApi';
import { PenaltyRecovery } from '@/modules/penalty';
import { backend, me, paged, problem, t, wrap } from './support/harness';

/**
 * I-06 Penalty, Recovery & Over-and-Above (plan 12 phase 12g; SRS-14-1, SRS-14-2, SRS-14-7,
 * SRS-14-10, SRS-14-15, SRS-14-16, SRS-14-19, PN-17): the orders by Penalty ID with what is
 * recovered and outstanding, an order's schedule and revokes, a returned request with its reason,
 * the order checked before it is sent (ceiling, floor), a revoke, the over-and-above payments and
 * the report; nothing without I-06 VIEW.
 */

afterEach(() => vi.unstubAllGlobals());

const ORDER: OrderRow = {
  id: 7,
  penaltyRef: 'PEN-2026-00007',
  eis: '104882',
  employee: 'Rakesh Sharma',
  nature: 'RECOVERY',
  orderReference: 'CH/REC/1',
  source: 'ENT',
  amount: 3000,
  instalments: 3,
  periodFrom: '2026-11-01',
  periodTo: '2027-01-31',
  recovered: 1000,
  outstanding: 2000,
  status: 'ACTIVE',
  backdated: false,
};

const DETAIL: OrderDetail = {
  order: ORDER,
  schedule: [
    { payrollYymm: '202611', scheduled: 1000, recovered: 1000, status: 'RECOVERED', reason: null },
    { payrollYymm: '202612', scheduled: 1000, recovered: 0, status: 'PENDING', reason: null },
  ],
  revokes: [
    {
      id: 1,
      type: 'PARTIAL',
      amount: 500,
      periodFrom: null,
      periodTo: null,
      orderReference: 'REV/1',
      reason: 'Appeal',
      status: 'APPROVED',
    },
  ],
};

const PAYMENT: PaymentRow = {
  id: 4,
  eis: '104882',
  payCode: 101,
  payName: 'Basic Pay',
  payrollYymm: '202611',
  amount: 2500,
  taxable: true,
  applicationDate: '2026-10-01',
  sanctionReference: 'SAN/1',
  sanctionDate: '2026-10-05',
  status: 'APPROVED',
  invoiceStatus: 'NOT_RAISED',
  checkerReason: null,
};

const ALL = ['I-06:VIEW', 'I-06:CREATE', 'I-06:REVOKE', 'I-06:CANCEL', 'I-06:OVER_ABOVE', 'I-06:EXPORT'];

function routes(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
  return backend({
    'GET /v1/penalties?': (url: string) =>
      paged(
        [ORDER, { ...ORDER, id: 8, penaltyRef: 'PEN-2026-00008', source: 'HRMS', backdated: true }],
        2,
        url,
      ),
    'GET /v1/penalties/7': () => DETAIL,
    'GET /v1/penalties/requests': (url: string) =>
      paged(
        [
          {
            approvalId: 31,
            penaltyRef: 'PEN-2026-00031',
            eis: '104882',
            employee: 'Rakesh Sharma',
            amount: 900,
            status: 'RETURNED',
            submittedBy: 'maker',
            submittedAt: '2026-10-26T06:00:00Z',
            returnReason: 'Amount differs from the order',
            summary: 'RECOVERY CH/REC/2',
          },
        ],
        1,
        url,
      ),
    'GET /v1/penalties/over-and-above': (url: string) => paged([PAYMENT], 1, url),
    'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
    ...over,
  });
}

async function copy(prefix: string) {
  await screen.findAllByRole('option', { name: 'Other document' });
  const type = document.getElementById(`${prefix}-doctype`) as HTMLSelectElement;
  await userEvent.selectOptions(type, 'OTHER');
  await userEvent.type(document.getElementById(`${prefix}-docref`) as HTMLElement, 'REV/IT/1');
  await userEvent.type(document.getElementById(`${prefix}-docdate`) as HTMLElement, '2026-10-01');
  await userEvent.type(document.getElementById(`${prefix}-docauth`) as HTMLElement, 'Disciplinary Authority');
  await userEvent.upload(
    document.getElementById(`${prefix}-docfile`) as HTMLInputElement,
    new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }),
  );
}

describe('I-06 penalty, recovery and over-and-above', () => {
  it('SRS_14_15_theOrdersByPenaltyIdTheScheduleAndAReturnedRequest', async () => {
    routes();
    wrap(<PenaltyRecovery />, me(ALL));
    const orders = await screen.findByRole('table', { name: t('i06.orders') });
    expect(orders).toHaveTextContent('PEN-2026-00007');
    expect(orders).toHaveTextContent(t('i06.source.HRMS'));
    expect(orders).toHaveTextContent(t('i06.backdated'));
    await userEvent.click(within(orders).getByRole('button', { name: 'PEN-2026-00007' }));
    const panel = await screen.findByRole('region', { name: 'PEN-2026-00007' });
    expect(within(panel).getByRole('table', { name: t('i06.schedule') })).toHaveTextContent(
      t('i06.line.RECOVERED'),
    );
    expect(within(panel).getByRole('list', { name: t('i06.revokes') })).toHaveTextContent('REV/1');
    expect(await screen.findByText('Amount differs from the order')).toBeInTheDocument();
    await userEvent.click(within(panel).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('region', { name: 'PEN-2026-00007' })).toBeNull();
  });

  it('SRS_14_7_theOrderIsCheckedBeforeItIsSentAndTheCeilingAndFloorAreSaid', async () => {
    const calls = routes({
      'POST /v1/penalties/previews': () => ({
        eis: '104882',
        name: 'Rakesh Sharma',
        currentBasic: 50000,
        nature: 'RECOVERY',
        amount: 6000,
        instalments: 6,
        schedule: [{ payrollYymm: '202611', amount: 1000 }],
        adjusted: true,
        latestNet: 20000,
        netAfter: 19000,
        floor: 19500,
        belowFloor: true,
        backdated: true,
        proposedBasic: 50000,
        deferredToFnf: false,
      }),
      'POST /v1/penalties': () => ({ status: 202, body: { id: 99, status: 'PENDING_APPROVAL' } }),
    });
    wrap(<PenaltyRecovery />, me(ALL));
    const check = await screen.findByRole('button', { name: t('i06.check') });
    expect(check).toBeDisabled();
    // Plan 12 D12-2: the nature of an order keyed here is shown, read only: a recovery order.
    const nature = document.getElementById('i06-nature') as HTMLInputElement;
    // SRS-14-1: the Maker selects the nature of a labour order; the server refuses another nature for an
    // employee (PEN-422-HRMS-ONLY), and a recovery stays the starting choice.
    expect(nature.tagName).toBe('SELECT');
    await userEvent.type(screen.getByLabelText(t('i06.memberEis'), { selector: '#i06-eis' }), '104882');
    await userEvent.type(screen.getByLabelText(t('i06.orderReference')), 'CH/REC/1');
    await userEvent.type(screen.getByLabelText(t('i06.effectiveDate')), '2026-10-01');
    await userEvent.type(screen.getByLabelText(t('i06.amount'), { selector: '#i06-amount' }), '6000');
    await userEvent.type(screen.getByLabelText(t('i06.instalments')), '2');
    fireEvent.change(screen.getByLabelText(t('i06.startYymm')), { target: { value: '2026-11' } });
    await userEvent.click(check);
    const preview = await screen.findByLabelText(t('i06.preview'));
    expect(preview).toHaveTextContent(t('i06.adjusted', ['₹1,000.00', 6]));
    expect(preview).toHaveTextContent(t('i06.belowFloor', ['₹19,000.00', '₹19,500.00']));
    expect(preview).toHaveTextContent(t('i06.backdatedHint'));
    // SRS-14-18: the Basic after the order; SRS l.2995: extend or defer to F&F instead.
    expect(preview).toHaveTextContent(t('i06.proposedBasic', ['₹50,000.00']));
    expect(preview).toHaveTextContent(t('i06.belowFloorOptions'));
    await userEvent.click(within(preview).getByLabelText(t('i06.deferToFnf')));
    const send = within(preview).getByRole('button', { name: t('i06.send') });
    expect(send).toBeDisabled();
    await copy('i06o');
    await userEvent.click(send);
    expect(await screen.findByText(t('i06.orderSent'))).toBeInTheDocument();
    const posted = calls.find((c) => c.method === 'POST' && c.url.endsWith('/v1/penalties'))
      ?.body as FormData;
    expect((posted.get('file') as File).name).toBe('order.pdf');
    const order = JSON.parse(await (posted.get('order') as Blob).text());
    expect(order).toMatchObject({
      eis: '104882',
      nature: 'RECOVERY',
      amount: 6000,
      instalments: 2,
      deferToFnf: true,
    });
  });

  it('SRS l.2997-2998: a returned request is corrected and resubmitted from the order form, prefilled', async () => {
    routes();
    wrap(<PenaltyRecovery />, me(ALL));
    const requests = await screen.findByRole('table', { name: t('i06.requests') });
    await userEvent.click(
      within(requests).getByRole('button', { name: t('i06.correctOf', ['PEN-2026-00031']) }),
    );
    expect(await screen.findByText(t('i06.correcting', [31]))).toBeTruthy();
    expect(
      (screen.getByLabelText(t('i06.memberEis'), { selector: '#i06-eis' }) as HTMLInputElement).value,
    ).toBe('104882');
    expect(
      (screen.getByLabelText(t('i06.amount'), { selector: '#i06-amount' }) as HTMLInputElement).value,
    ).toBe('900');
  });

  it('SRS_G_30_whileTheDocumentTypesCannotBeReadNoOrderCopyIsCompleteAndNothingIsSent', async () => {
    routes({
      'GET /v1/document-types': () => problem(503, 'COM-503-UNAVAILABLE'),
      'POST /v1/penalties/previews': () => ({
        eis: '104882',
        name: 'Rakesh Sharma',
        currentBasic: 50000,
        nature: 'RECOVERY',
        amount: 2000,
        instalments: 2,
        schedule: [{ payrollYymm: '202611', amount: 1000 }],
        adjusted: false,
        latestNet: 20000,
        netAfter: 19000,
        netFloor: null,
        backdated: false,
      }),
    });
    wrap(<PenaltyRecovery />, me(ALL));
    // The entry: the order copy has no type to choose, so the order is never complete.
    await userEvent.type(screen.getByLabelText(t('i06.memberEis'), { selector: '#i06-eis' }), '104882');
    await userEvent.type(screen.getByLabelText(t('i06.orderReference')), 'CH/REC/2');
    await userEvent.type(screen.getByLabelText(t('i06.effectiveDate')), '2026-10-01');
    await userEvent.type(screen.getByLabelText(t('i06.amount'), { selector: '#i06-amount' }), '2000');
    await userEvent.type(screen.getByLabelText(t('i06.instalments')), '2');
    fireEvent.change(screen.getByLabelText(t('i06.startYymm')), { target: { value: '2026-11' } });
    await userEvent.click(screen.getByRole('button', { name: t('i06.check') }));
    const preview = await screen.findByLabelText(t('i06.preview'));
    const send = within(preview).getByRole('button', { name: t('i06.send') });
    expect(send).toBeDisabled();
    expect(send.getAttribute('title')).toBe(t('i06.bad.copy'));
    // SRS-1-49 / BR-EMPR-019: a cancel action is never applied on one click - it asks for its
    // confirmation with a reason first.
    // The revoke and the cancellation of an order: neither can be sent.
    const orders = await screen.findByRole('table', { name: t('i06.orders') });
    await userEvent.click(within(orders).getByRole('button', { name: 'PEN-2026-00007' }));
    const revoke = await screen.findByLabelText(t('i06.revoke'));
    expect(within(revoke).getByRole('button', { name: t('i06.sendRevoke') })).toBeDisabled();
    const cancel = await screen.findByLabelText(t('i06.cancel'));
    await userEvent.type(within(cancel).getByLabelText(t('i06.cancelReason')), 'Order withdrawn');
    expect(within(cancel).getByRole('button', { name: t('i06.sendCancel') })).toBeDisabled();
  });

  it('SRS_14_16_aRevokeIsSentWithItsOrderAndARefusalStays', async () => {
    const calls = routes({
      'POST /v1/penalties/7/revokes': () =>
        problem(422, 'PEN-422-ORDER', ['the order is REVOKED and cannot change']),
    });
    wrap(<PenaltyRecovery />, me(ALL));
    const orders = await screen.findByRole('table', { name: t('i06.orders') });
    await userEvent.click(within(orders).getByRole('button', { name: 'PEN-2026-00007' }));
    const revoke = await screen.findByLabelText(t('i06.revoke'));
    const send = within(revoke).getByRole('button', { name: t('i06.sendRevoke') });
    await userEvent.selectOptions(within(revoke).getByLabelText(t('i06.revokeTypeLabel')), 'PARTIAL');
    await userEvent.type(within(revoke).getByLabelText(t('i06.waiveAmount')), '500');
    await userEvent.type(within(revoke).getByLabelText(t('i06.reason')), 'On appeal');
    expect(send).toBeDisabled();
    await copy('i06r');
    expect(send).toBeEnabled();
    await userEvent.click(send);
    expect(await screen.findByText(/cannot change/)).toBeInTheDocument();
    const posted = calls.find((c) => c.url.includes('/revokes'))?.body as FormData;
    expect(JSON.parse(await (posted.get('revoke') as Blob).text())).toMatchObject({
      type: 'PARTIAL',
      amount: 500,
      orderReference: 'REV/IT/1',
    });
  });

  it('SRS_14_15_aRecordedRequestShowsThePenaltyIdItWasGiven (US-GAP-229)', async () => {
    routes();
    wrap(<PenaltyRecovery />, me(ALL));
    const requests = await screen.findByRole('table', { name: t('i06.requests') });
    expect(requests).toHaveTextContent(t('i06.penaltyId'));
    expect(requests).toHaveTextContent('PEN-2026-00031');
  });

  it('SRS_14_2_aReturnedOrRejectedPaymentShowsTheCheckersReasonToTheMaker (US-GAP-230)', async () => {
    routes({
      'GET /v1/penalties/over-and-above': (url: string) =>
        paged(
          [
            { ...PAYMENT, id: 5, status: 'REJECTED', checkerReason: 'No sanction on file' },
            { ...PAYMENT, id: 6, status: 'RECORDED', checkerReason: 'Amount differs' },
            { ...PAYMENT, id: 7, status: 'APPROVED', checkerReason: null },
          ],
          3,
          url,
        ),
    });
    wrap(<PenaltyRecovery />, me(ALL));
    const payments = await screen.findByRole('table', { name: t('i06.payments') });
    expect(payments).toHaveTextContent('No sanction on file');
    expect(payments).toHaveTextContent('Amount differs');
    expect(within(payments).getAllByTitle(t('i06.checkerReason'))).toHaveLength(2);
  });

  it('SRS_14_2_anOverAndAbovePaymentIsSentAndCancelledAndTheReportLinks', async () => {
    const calls = routes({
      'POST /v1/penalties/over-and-above': () => ({
        status: 202,
        body: { id: 5, status: 'PENDING_APPROVAL' },
      }),
      'POST /v1/penalties/over-and-above/4/cancellations': () => ({ status: 202, body: { id: 6 } }),
    });
    wrap(<PenaltyRecovery />, me(ALL));
    const payments = await screen.findByRole('table', { name: t('i06.payments') });
    expect(payments).toHaveTextContent('SAN/1');
    await userEvent.click(within(payments).getByRole('button', { name: t('i06.cancelPayment') }));
    // SRS-14-14 / SRS-14-20: the Maker gives the reason; without one the cancellation cannot be sent.
    const dialog = await screen.findByRole('dialog', { name: t('i06.paymentCancelTitle', ['4']) });
    const confirm = within(dialog).getByRole('button', { name: t('i06.paymentCancelConfirm') });
    expect(confirm).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText(t('common.reason')), '   ');
    expect(confirm).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText(t('common.reason')), 'Sanction withdrawn by the DGM');
    await userEvent.click(confirm);
    expect(await screen.findByText(t('i06.cancelSent', ['4']))).toBeInTheDocument();
    expect(calls.find((c) => c.url.endsWith('/over-and-above/4/cancellations'))?.body).toEqual({
      reason: 'Sanction withdrawn by the DGM',
    });
    const form = screen.getByLabelText(t('i06.newPayment'));
    const send = within(form).getByRole('button', { name: t('i06.sendPayment') });
    expect(send).toBeDisabled();
    await userEvent.type(within(form).getByLabelText(t('i06.memberEis')), '104882');
    await userEvent.type(within(form).getByLabelText(t('i06.payCode')), '101');
    await userEvent.type(within(form).getByLabelText(t('i06.amount')), '2500');
    fireEvent.change(within(form).getByLabelText(t('i06.month')), { target: { value: '2026-11' } });
    await userEvent.type(within(form).getByLabelText(t('i06.reason')), 'Special duty');
    await userEvent.type(within(form).getByLabelText(t('i06.sanction')), 'SAN/2');
    await userEvent.click(send);
    expect(await screen.findByText(t('i06.paymentSent'))).toBeInTheDocument();
    expect(calls.find((c) => c.url.endsWith('/over-and-above') && c.method === 'POST')?.body).toMatchObject({
      payCode: 101,
      amount: 2500,
      sanctionReference: 'SAN/2',
      applicationDate: null,
    });
    // The report opens on this business month (IST), so it can be exported at once.
    expect(screen.getByRole('button', { name: t('i06.reportExport') })).toBeEnabled();
  });

  it('SRS_14_19_theReportTakesMonthsInOrderAndBlankFieldsShowADash', async () => {
    routes({
      'GET /v1/penalties/over-and-above': (url: string) =>
        paged([{ ...PAYMENT, taxable: false, sanctionDate: null, amount: null }], 1, url),
      'GET /v1/penalties/7': () => ({
        ...DETAIL,
        schedule: [
          { payrollYymm: '202611', scheduled: 1000, recovered: null, status: 'DEFERRED', reason: 'F&F' },
        ],
        revokes: [{ ...DETAIL.revokes[0], amount: null, status: 'PENDING' }],
      }),
    });
    wrap(<PenaltyRecovery />, me(ALL));
    const payments = await screen.findByRole('table', { name: t('i06.payments') });
    expect(payments).not.toHaveTextContent(t('i06.taxable'));
    expect(within(payments).getByText('—')).toBeInTheDocument();
    const report = () => screen.getByRole('button', { name: t('i06.reportExport') });
    const [from, to] = screen.getByText(t('i06.report')).closest('.card')?.querySelectorAll('input') ?? [];
    // Month pickers, opening on this business month (IST): the range is ready at once.
    expect(from).toHaveAttribute('type', 'month');
    expect(report()).toBeEnabled();
    fireEvent.change(from as HTMLElement, { target: { value: '2026-12' } });
    fireEvent.change(to as HTMLElement, { target: { value: '2026-11' } });
    expect(report()).toBeDisabled();
    fireEvent.change(to as HTMLElement, { target: { value: '' } });
    expect(report()).toBeDisabled();
    fireEvent.change(to as HTMLElement, { target: { value: '2027-01' } });
    expect(report()).toBeEnabled();

    const orders = screen.getByRole('table', { name: t('i06.orders') });
    await userEvent.click(within(orders).getByRole('button', { name: 'PEN-2026-00007' }));
    const panel = await screen.findByRole('region', { name: 'PEN-2026-00007' });
    expect(within(panel).getByRole('table', { name: t('i06.schedule') })).toHaveTextContent('F&F');
    expect(within(panel).getByRole('list', { name: t('i06.revokes') })).toHaveTextContent('—');
  });

  it('SRS_14_17_aCancellationIsSentWithItsReasonAndOrderAndARefusalIsShownFirst', async () => {
    let answer: unknown = problem(409, 'PEN-409-STATE', ['CANCELLED', 'ACTIVE']);
    const calls = routes({ 'POST /v1/penalties/7/cancellations': () => answer });
    wrap(<PenaltyRecovery />, me(ALL));
    const orders = await screen.findByRole('table', { name: t('i06.orders') });
    await userEvent.click(within(orders).getByRole('button', { name: 'PEN-2026-00007' }));
    const cancel = await screen.findByLabelText(t('i06.cancel'));
    const send = within(cancel).getByRole('button', { name: t('i06.sendCancel') });
    expect(send).toBeDisabled();
    expect(send.getAttribute('title')).toBe(t('i06.bad.cancel'));
    await userEvent.type(within(cancel).getByLabelText(t('i06.cancelReason')), 'Order withdrawn');
    await copy('i06c');
    await userEvent.click(send);
    await userEvent.click(await screen.findByRole('button', { name: t('confirm.cancel.yes') }));
    expect(await within(cancel).findByRole('alert')).toBeTruthy();
    answer = { status: 202, body: { id: 9, status: 'PENDING_APPROVAL' } };
    await userEvent.click(send);
    await userEvent.click(await screen.findByRole('button', { name: t('confirm.cancel.yes') }));
    expect(await screen.findByText(t('i06.cancelSent', ['PEN-2026-00007']))).toBeInTheDocument();
    expect(calls.filter((c) => c.url.includes('/cancellations') && c.method === 'POST')).toHaveLength(2);
  });

  it('SRS_14_16_aFullRevokeIsSentAndTheHrmsOrderIsReadOnly', async () => {
    routes({
      'POST /v1/penalties/7/revokes': () => ({ status: 202, body: { id: 3, status: 'PENDING_APPROVAL' } }),
      'GET /v1/penalties/8': () => ({
        ...DETAIL,
        order: { ...ORDER, id: 8, penaltyRef: 'PEN-2026-00008', source: 'HRMS', backdated: true },
      }),
    });
    wrap(<PenaltyRecovery />, me(ALL));
    const orders = await screen.findByRole('table', { name: t('i06.orders') });
    await userEvent.click(within(orders).getByRole('button', { name: 'PEN-2026-00007' }));
    const revoke = await screen.findByLabelText(t('i06.revoke'));
    await userEvent.selectOptions(within(revoke).getByLabelText(t('i06.revokeTypeLabel')), 'FULL');
    await userEvent.type(within(revoke).getByLabelText(t('i06.reason')), 'Set aside');
    await copy('i06r');
    await userEvent.click(within(revoke).getByRole('button', { name: t('i06.sendRevoke') }));
    expect(await screen.findByText(t('i06.revokeSent', ['PEN-2026-00007']))).toBeInTheDocument();
    await userEvent.click(within(orders).getByRole('button', { name: 'PEN-2026-00008' }));
    expect(await screen.findByText(t('i06.hrmsReadOnly'))).toBeInTheDocument();
  });

  it('SRS_14_15_theMemberAndStatusFiltersGoToTheServer', async () => {
    const calls = routes();
    wrap(<PenaltyRecovery />, me(['I-06:VIEW']));
    await screen.findByRole('table', { name: t('i06.orders') });
    await userEvent.type(screen.getAllByLabelText(t('m01.eis'))[0] as HTMLElement, '104882');
    await userEvent.selectOptions(screen.getAllByLabelText(t('i06.status'))[0] as HTMLElement, 'REVOKED');
    expect(calls.some((c) => c.url.includes('eis=104882'))).toBe(true);
    expect(calls.some((c) => c.url.includes('status=REVOKED'))).toBe(true);
  });

  it('SRS_14_16_aPartialRevokeByPeriodAndEveryRefusedSendIsShown', async () => {
    const calls = routes({
      'POST /v1/penalties/previews': () => problem(422, 'PEN-422-CEILING'),
      'POST /v1/penalties/over-and-above': () => problem(409, 'PEN-409-STATE', ['PAID', 'RECORDED']),
      'POST /v1/penalties/over-and-above/4/cancellations': () =>
        problem(409, 'PEN-409-STATE', ['PAID', 'RECORDED']),
      'POST /v1/penalties/7/revokes': () => ({ status: 202, body: { id: 3, status: 'PENDING_APPROVAL' } }),
    });
    wrap(<PenaltyRecovery />, me(ALL));
    const orders = await screen.findByRole('table', { name: t('i06.orders') });
    await userEvent.click(within(orders).getByRole('button', { name: 'PEN-2026-00007' }));
    const revoke = await screen.findByLabelText(t('i06.revoke'));
    await userEvent.selectOptions(within(revoke).getByLabelText(t('i06.revokeTypeLabel')), 'PARTIAL');
    fireEvent.change(document.getElementById('i06-revoke-from') as HTMLElement, {
      target: { value: '2026-11' },
    });
    fireEvent.change(document.getElementById('i06-revoke-to') as HTMLElement, {
      target: { value: '2026-12' },
    });
    await userEvent.type(within(revoke).getByLabelText(t('i06.reason')), 'Months waived on appeal');
    await copy('i06r');
    await userEvent.click(within(revoke).getByRole('button', { name: t('i06.sendRevoke') }));
    expect(await screen.findByText(t('i06.revokeSent', ['PEN-2026-00007']))).toBeInTheDocument();
    const posted = calls.find((c) => c.url.includes('/revokes'))?.body as FormData;
    expect(JSON.parse(await (posted.get('revoke') as Blob).text())).toMatchObject({
      type: 'PARTIAL',
      amount: null,
      fromYymm: '202611',
      toYymm: '202612',
    });

    await userEvent.type(screen.getByLabelText(t('i06.memberEis'), { selector: '#i06-eis' }), '104882');
    await userEvent.type(screen.getByLabelText(t('i06.orderReference')), 'CH/REC/1');
    await userEvent.type(screen.getByLabelText(t('i06.effectiveDate')), '2026-10-01');
    await userEvent.type(screen.getByLabelText(t('i06.amount'), { selector: '#i06-amount' }), '6000');
    await userEvent.type(screen.getByLabelText(t('i06.instalments')), '2');
    fireEvent.change(screen.getByLabelText(t('i06.startYymm')), { target: { value: '2026-11' } });
    await userEvent.click(screen.getByRole('button', { name: t('i06.check') }));
    expect(await screen.findByText(t('error.PEN-422-CEILING'))).toBeInTheDocument();

    const payments = screen.getByRole('table', { name: t('i06.payments') });
    await userEvent.click(within(payments).getByRole('button', { name: t('i06.cancelPayment') }));
    // Closing the reason dialog sends nothing; asking again and confirming shows the refusal.
    await userEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(calls.filter((c) => c.url.endsWith('/4/cancellations'))).toHaveLength(0);
    await userEvent.click(within(payments).getByRole('button', { name: t('i06.cancelPayment') }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText(t('common.reason')), 'Duplicate entry');
    await userEvent.click(within(dialog).getByRole('button', { name: t('i06.paymentCancelConfirm') }));
    const form = screen.getByLabelText(t('i06.newPayment'));
    await userEvent.type(within(form).getByLabelText(t('i06.memberEis')), '104882');
    await userEvent.type(within(form).getByLabelText(t('i06.payCode')), '101');
    await userEvent.type(within(form).getByLabelText(t('i06.amount')), '2500');
    fireEvent.change(within(form).getByLabelText(t('i06.month')), { target: { value: '2026-11' } });
    await userEvent.type(within(form).getByLabelText(t('i06.reason')), 'Special duty');
    await userEvent.type(within(form).getByLabelText(t('i06.sanction')), 'SAN/2');
    await userEvent.click(within(form).getByRole('button', { name: t('i06.sendPayment') }));
    expect((await screen.findAllByRole('alert')).length).toBeGreaterThan(0);
  });

  it('SRS_22_21_nothingWithoutTheRightAndNoActionsForAViewer', async () => {
    routes();
    const view = wrap(<PenaltyRecovery />, me(['M-01:VIEW']));
    expect(view.container).toBeEmptyDOMElement();
    view.unmount();
    routes();
    wrap(<PenaltyRecovery />, me(['I-06:VIEW']));
    await screen.findByRole('table', { name: t('i06.orders') });
    expect(screen.queryByText(t('i06.newOrder'))).toBeNull();
    expect(screen.queryByLabelText(t('i06.newPayment'))).toBeNull();
    expect(screen.queryByText(t('i06.report'))).toBeNull();
  });

  it('SRS_14_7_aRefusedOrderIsShownAndTheEntryIsKept', async () => {
    routes({
      'POST /v1/penalties/previews': () => ({
        eis: '104882',
        name: 'Rakesh Sharma',
        currentBasic: 50000,
        nature: 'RECOVERY',
        amount: 2000,
        instalments: 2,
        schedule: [{ payrollYymm: '202611', amount: 1000 }],
        adjusted: false,
        latestNet: 40000,
        netAfter: 39000,
        floor: 19500,
        belowFloor: false,
        backdated: false,
      }),
      'POST /v1/penalties': () => problem(409, 'PEN-409-DUPLICATE'),
    });
    wrap(<PenaltyRecovery />, me(ALL));
    const check = await screen.findByRole('button', { name: t('i06.check') });
    await userEvent.type(screen.getByLabelText(t('i06.memberEis'), { selector: '#i06-eis' }), '104882');
    await userEvent.type(screen.getByLabelText(t('i06.orderReference')), 'CH/REC/9');
    await userEvent.type(screen.getByLabelText(t('i06.effectiveDate')), '2026-10-01');
    await userEvent.type(screen.getByLabelText(t('i06.amount'), { selector: '#i06-amount' }), '2000');
    await userEvent.type(screen.getByLabelText(t('i06.instalments')), '2');
    fireEvent.change(screen.getByLabelText(t('i06.startYymm')), { target: { value: '2026-11' } });
    await userEvent.click(check);
    const preview = await screen.findByLabelText(t('i06.preview'));
    await copy('i06o');
    await userEvent.click(within(preview).getByRole('button', { name: t('i06.send') }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.queryByText(t('i06.orderSent'))).toBeNull();
    expect(screen.getByLabelText(t('i06.orderReference'))).toHaveValue('CH/REC/9');
  });
});
