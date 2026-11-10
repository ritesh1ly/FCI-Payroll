import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LoansAdvances } from '@/modules/loans';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * I-04 Loans & Advances actions (plan 09 phases 09d - 09f; SRS-12-11, SRS-12-13, SRS-12-14,
 * SRS-12-17): a foreclosure against its receipt, a reschedule against the signed request, a new
 * sanction against its order (an open request reviewed first), each sent to the Checker or its
 * refusal shown; the list filters go to the server.
 */

afterEach(() => vi.unstubAllGlobals());

const LOAN = {
  id: 7,
  loanNumber: 'LN-2026-000007',
  eis: '104882',
  employee: 'Rakesh Sharma',
  loanType: 'FESTIVAL_ADVANCE',
  sanctioned: 20000,
  recoveryStart: '202611',
  principalInstalments: 4,
  instalment: 5000,
  paidInstalments: 1,
  principalOutstanding: 15000,
  interestAmount: 0,
  interestOutstanding: 0,
  status: 'ACTIVE',
  source: 'LABOUR_ENT',
};

const PREVIEW = {
  eis: '104882',
  loanType: 'FESTIVAL_ADVANCE',
  terms: { sanctioned: 20000, principalInstalments: 1, instalment: 20000, startYymm: '202611' },
  schedule: [{ number: 1, payrollYymm: '202611', principal: 20000, interest: 0 }],
  outstanding: 0,
  maxNewSanction: 500000,
  reduced: false,
  openRequest: 31,
};

function routes(over: Record<string, (url: string) => unknown> = {}) {
  return {
    'GET /v1/loans?': (url: string) => paged([LOAN], 1, url),
    'GET /v1/loans/7': () => ({ loan: LOAN, versions: [] }),
    'GET /v1/loans/requests': (url: string) => paged([], 0, url),
    'GET /v1/loans/types': () => [
      { code: 'FESTIVAL_ADVANCE', name: 'Festival Advance', interestBearing: false, maxInstalments: 10 },
    ],
    'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
    ...over,
  };
}

async function order(prefix: string) {
  await screen.findAllByRole('option', { name: 'Other document' });
  await userEvent.selectOptions(document.getElementById(`${prefix}-doctype`) as HTMLSelectElement, 'OTHER');
  await userEvent.type(document.getElementById(`${prefix}-docref`) as HTMLElement, 'LN/IT/1');
  await userEvent.type(document.getElementById(`${prefix}-docdate`) as HTMLElement, '2026-10-01');
  await userEvent.type(document.getElementById(`${prefix}-docauth`) as HTMLElement, 'DGM (F)');
  await userEvent.upload(
    document.getElementById(`${prefix}-docfile`) as HTMLInputElement,
    new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }),
  );
}

async function openLoan() {
  const loans = await screen.findByRole('table', { name: t('i04l.loans') });
  await userEvent.click(within(loans).getByRole('button', { name: t('i04l.openLoan', ['LN-2026-000007']) }));
  return screen.findByRole('region', { name: 'LN-2026-000007' });
}

describe('I-04 Loans & Advances actions', () => {
  it('SRS-12-13 / SRS-12-18: a foreclosure is sent with its order and receipt; a refusal is shown first', async () => {
    let answer: unknown = problem(409, 'LN-409-STATE', ['CLOSED', 'ACTIVE']);
    const calls = backend(routes({ 'POST /v1/loans/7/foreclosures': () => answer }));
    wrap(<LoansAdvances />, me(['I-04:VIEW', 'I-04:LOAN_FORECLOSE']));
    const panel = await openLoan();
    const send = within(panel).getByRole('button', { name: t('i04l.sendForeclosure') });
    expect(send.getAttribute('title')).toBe(t('i04l.changeOrderMissing'));
    await order('fco');
    // US-GAP-202: with the order but no receipt it still cannot be sent.
    expect(send.getAttribute('title')).toBe(t('i04l.receiptMissing'));
    expect(send).toBeDisabled();
    await order('fc');
    await userEvent.click(send);
    expect(await within(panel).findByRole('alert')).toBeTruthy();
    answer = { status: 202, body: { approvalId: 5, status: 'PENDING_APPROVAL' } };
    await userEvent.click(send);
    expect(await screen.findByText(t('i04l.sentToChecker'))).toBeTruthy();
    expect(sent(calls, 'POST', '/foreclosures')).toHaveLength(2);
    const form = sent(calls, 'POST', '/foreclosures')[0] as FormData;
    expect(form.get('orderReference')).toBe('LN/IT/1');
    expect(form.get('orderIssuedOn')).toBe('2026-10-01');
    expect(form.get('orderFile')).toBeInstanceOf(File);
    expect(form.get('file')).toBeInstanceOf(File);
  });

  it('SRS-12-14 / SRS-12-18: a reschedule is sent with its order and the signed request; a refusal is shown first', async () => {
    let answer: unknown = problem(422, 'LN-422-TERMS', ['instalment']);
    const calls = backend(routes({ 'POST /v1/loans/7/reschedules': () => answer }));
    wrap(<LoansAdvances />, me(['I-04:VIEW', 'I-04:LOAN_RESCHEDULE']));
    const panel = await openLoan();
    const send = within(panel).getByRole('button', { name: t('i04l.sendReschedule') });
    expect(send.getAttribute('title')).toBe(t('i04l.changeOrderMissing'));
    await userEvent.type(within(panel).getByLabelText(t('i04l.instalments')), '3');
    await userEvent.type(within(panel).getByLabelText(t('i04l.instalment')), '5000');
    fireEvent.change(within(panel).getByLabelText(t('i04l.start')), { target: { value: '2026-12' } });
    await order('rso');
    expect(send.getAttribute('title')).toBe(t('i04l.signedRequestMissing'));
    await order('rs');
    await userEvent.click(send);
    expect(await within(panel).findByRole('alert')).toBeTruthy();
    answer = { status: 202, body: { approvalId: 6, status: 'PENDING_APPROVAL' } };
    await userEvent.click(send);
    expect(await screen.findByText(t('i04l.sentToChecker'))).toBeTruthy();
    expect(sent(calls, 'POST', '/reschedules')).toHaveLength(2);
  });

  it('SRS-12-4: a reschedule of a loan without interest outstanding asks no interest terms and sends none', async () => {
    const calls = backend(
      routes({
        'POST /v1/loans/7/reschedules': () => ({
          status: 202,
          body: { approvalId: 6, status: 'PENDING_APPROVAL' },
        }),
      }),
    );
    wrap(<LoansAdvances />, me(['I-04:VIEW', 'I-04:LOAN_RESCHEDULE']));
    const panel = await openLoan();
    expect(within(panel).queryByLabelText(t('i04l.interestInstalments'))).toBeNull();
    await userEvent.type(within(panel).getByLabelText(t('i04l.instalments')), '3');
    await userEvent.type(within(panel).getByLabelText(t('i04l.instalment')), '5000');
    fireEvent.change(within(panel).getByLabelText(t('i04l.start')), { target: { value: '2026-12' } });
    await order('rso');
    await order('rs');
    await userEvent.click(within(panel).getByRole('button', { name: t('i04l.sendReschedule') }));
    expect(await screen.findByText(t('i04l.sentToChecker'))).toBeTruthy();
    const part = (sent(calls, 'POST', '/reschedules')[0] as FormData).get('schedule') as Blob;
    expect(JSON.parse(await part.text())).toMatchObject({
      principalInstalments: 3,
      instalment: 5000,
      interestInstalments: 0,
      interestInstalment: null,
      startYymm: '202612',
    });
  });

  it('SRS-12-4 / SRS-12-1: a loan with interest outstanding is rescheduled only with its interest instalments, which are sent', async () => {
    const interestLoan = { ...LOAN, interestAmount: 1800, interestOutstanding: 1200 };
    const calls = backend(
      routes({
        'GET /v1/loans?': (url: string) => paged([interestLoan], 1, url),
        'GET /v1/loans/7': () => ({ loan: interestLoan, versions: [] }),
        'POST /v1/loans/7/reschedules': () => ({
          status: 202,
          body: { approvalId: 8, status: 'PENDING_APPROVAL' },
        }),
      }),
    );
    wrap(<LoansAdvances />, me(['I-04:VIEW', 'I-04:LOAN_RESCHEDULE']));
    const panel = await openLoan();
    const send = within(panel).getByRole('button', { name: t('i04l.sendReschedule') });
    await userEvent.type(within(panel).getByLabelText(t('i04l.instalments')), '3');
    await userEvent.type(within(panel).getByLabelText(t('i04l.instalment')), '5000');
    fireEvent.change(within(panel).getByLabelText(t('i04l.start')), { target: { value: '2026-12' } });
    await order('rso');
    await order('rs');
    // The principal terms and both documents are not enough: the interest still has to be spread.
    expect(send).toBeDisabled();
    expect(send.getAttribute('title')).toBe(t('i04l.rescheduleTermsMissing'));
    const count = within(panel).getByLabelText(t('i04l.interestInstalments'));
    const each = within(panel).getByLabelText(t('i04l.interestInstalment'));
    // Edge: a part instalment count or a zero amount is refused.
    await userEvent.type(count, '2.5');
    await userEvent.type(each, '600');
    expect(send).toBeDisabled();
    await userEvent.clear(count);
    await userEvent.type(count, '2');
    await userEvent.clear(each);
    await userEvent.type(each, '0');
    expect(send).toBeDisabled();
    await userEvent.clear(each);
    await userEvent.type(each, '600');
    expect(send).toBeEnabled();
    await userEvent.click(send);
    expect(await screen.findByText(t('i04l.sentToChecker'))).toBeTruthy();
    const part = (sent(calls, 'POST', '/reschedules')[0] as FormData).get('schedule') as Blob;
    expect(JSON.parse(await part.text())).toMatchObject({ interestInstalments: 2, interestInstalment: 600 });
  });

  it('SRS-G-30: while the document types cannot be read neither a reschedule nor a sanction can be sent', async () => {
    backend(
      routes({
        'GET /v1/document-types': () => problem(503, 'COM-503-UNAVAILABLE'),
        'POST /v1/loans/sanctions/previews': () => ({ ...PREVIEW, openRequest: null }),
      }),
    );
    wrap(<LoansAdvances />, me(['I-04:VIEW', 'I-04:LOAN_RESCHEDULE', 'I-04:LOAN_SANCTION']));
    await userEvent.type(screen.getAllByLabelText(t('m01.eis'))[1] as HTMLElement, '104882');
    await userEvent.selectOptions(screen.getByLabelText(t('i04l.type')), 'FESTIVAL_ADVANCE');
    await userEvent.type(screen.getByLabelText(t('i04l.sanctioned')), '20000');
    await userEvent.type(screen.getAllByLabelText(t('i04l.instalments'))[0] as HTMLElement, '1');
    await userEvent.type(screen.getAllByLabelText(t('i04l.instalment'))[0] as HTMLElement, '20000');
    fireEvent.change(screen.getAllByLabelText(t('i04l.start'))[0] as HTMLElement, {
      target: { value: '2026-11' },
    });
    await userEvent.click(screen.getByRole('button', { name: t('i04l.check') }));
    expect(await screen.findByRole('button', { name: t('i04l.sendSanction') })).toBeDisabled();
    const panel = await openLoan();
    await userEvent.type(within(panel).getByLabelText(t('i04l.instalments')), '3');
    await userEvent.type(within(panel).getByLabelText(t('i04l.instalment')), '5000');
    fireEvent.change(within(panel).getByLabelText(t('i04l.start')), { target: { value: '2026-12' } });
    expect(within(panel).getByRole('button', { name: t('i04l.sendReschedule') })).toBeDisabled();
  });

  it('SRS-12-17: a sanction with an open request is reviewed, then sent with its order; a failed check is shown', async () => {
    let preview: unknown = problem(422, 'LN-422-TERMS', ['amount']);
    let submit: unknown = problem(409, 'LN-409-OPEN', ['31']);
    const calls = backend(
      routes({
        'POST /v1/loans/sanctions/previews': () => preview,
        'POST /v1/loans/sanctions': () => submit,
      }),
    );
    wrap(<LoansAdvances />, me(['I-04:VIEW', 'I-04:LOAN_SANCTION']));
    await screen.findByText(t('i04l.newSanction'));
    await userEvent.type(screen.getAllByLabelText(t('m01.eis'))[1] as HTMLElement, '104882');
    await userEvent.selectOptions(screen.getByLabelText(t('i04l.type')), 'FESTIVAL_ADVANCE');
    await userEvent.type(screen.getByLabelText(t('i04l.sanctioned')), '20000');
    await userEvent.type(screen.getAllByLabelText(t('i04l.instalments'))[0] as HTMLElement, '1');
    await userEvent.type(screen.getAllByLabelText(t('i04l.instalment'))[0] as HTMLElement, '20000');
    await userEvent.type(screen.getByLabelText(t('i04l.firstInstalment')), '20000');
    await userEvent.type(screen.getByLabelText(t('i04l.interestAmount')), ' ');
    fireEvent.change(screen.getAllByLabelText(t('i04l.start'))[0] as HTMLElement, {
      target: { value: '2026-11' },
    });
    await userEvent.click(screen.getByRole('button', { name: t('i04l.check') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    preview = PREVIEW;
    await userEvent.click(screen.getByRole('button', { name: t('i04l.check') }));
    const send = await screen.findByRole('button', { name: t('i04l.sendSanction') });
    expect(send.getAttribute('title')).toBe(t('i04l.orderMissing'));
    await order('ls');
    expect(send).toBeDisabled();
    await userEvent.click(screen.getByLabelText(new RegExp(t('i04l.openRequest', [31]).slice(0, 12))));
    await userEvent.click(send);
    expect(await screen.findByRole('alert')).toBeTruthy();
    submit = { status: 202, body: { approvalId: 7, status: 'PENDING_APPROVAL' } };
    await userEvent.click(send);
    expect(await screen.findByText(t('i04l.sentToChecker'))).toBeTruthy();
    expect(calls.filter((c) => c.method === 'POST' && c.url.endsWith('/v1/loans/sanctions'))).toHaveLength(2);
  });

  it('SRS-12-23: the member and status filters go to the server', async () => {
    const calls = backend(routes());
    wrap(<LoansAdvances />, me(['I-04:VIEW']));
    await screen.findByRole('table', { name: t('i04l.loans') });
    await userEvent.type(screen.getByLabelText(t('m01.eis')), '104882');
    await userEvent.selectOptions(screen.getAllByLabelText(t('i04l.status'))[0] as HTMLElement, 'CLOSED');
    expect(calls.some((c) => c.url.includes('eis=104882'))).toBe(true);
    expect(calls.some((c) => c.url.includes('status=CLOSED'))).toBe(true);
  });
});
