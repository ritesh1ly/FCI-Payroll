import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LoansAdvances } from '@/modules/loans';
import { backend, me, paged, t, wrap } from './support/harness';

/**
 * I-04 Loans & Advances view (plan 09 phase 09f; SRS-12-1, SRS-12-7, SRS-12-11, SRS-12-17,
 * SRS-12-23): the unit's loans with principal and interest apart, a loan's tracker and schedule
 * versions, an HRMS loan read-only, the returned request with its reason, and the sanction checked
 * before it is sent; nothing without I-04 VIEW.
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

const DETAIL = {
  loan: LOAN,
  versions: [
    {
      versionNo: 1,
      reason: 'ORIGINAL',
      effectiveFrom: '2026-11-01',
      supersededAt: null,
      lines: [
        { number: 1, payrollYymm: '202611', principal: 5000, interest: 0 },
        { number: 2, payrollYymm: '202612', principal: 5000, interest: 0 },
      ],
    },
  ],
  nextInstalment: { number: 2, payrollYymm: '202612', principal: 5000, interest: 250 },
};

function routes(over: Record<string, (url: string) => unknown> = {}) {
  return {
    'GET /v1/loans?': (url: string) =>
      paged([LOAN, { ...LOAN, id: 8, loanNumber: 'LN-2026-000008', source: 'HRMS' }], 2, url),
    'GET /v1/loans/7': () => DETAIL,
    'GET /v1/loans/8': () => ({
      ...DETAIL,
      nextInstalment: null,
      loan: { ...LOAN, id: 8, loanNumber: 'LN-2026-000008', source: 'HRMS' },
    }),
    'GET /v1/loans/requests': (url: string) =>
      paged(
        [
          {
            approvalId: 31,
            eis: '104882',
            employee: 'Rakesh Sharma',
            loanType: 'PLI_ADVANCE',
            amount: 10000,
            status: 'RETURNED',
            submittedBy: 'maker.delhi',
            submittedAt: '2026-10-26T06:00:00Z',
            returnReason: 'Amount differs from the order',
          },
        ],
        1,
        url,
      ),
    'GET /v1/loans/types': () => [
      { code: 'FESTIVAL_ADVANCE', name: 'Festival Advance', interestBearing: false, maxInstalments: 10 },
    ],
    'GET /v1/document-types': () => [
      { code: 'SANCTION_ORDER', name: 'Sanction order', referenceRequired: false },
    ],
    ...over,
  };
}

describe('I-04 Loans & Advances', () => {
  it('SRS-12-23 / SRS-12-7: the loans, a tracker with schedule versions, and a returned request with its reason', async () => {
    backend(routes());
    wrap(<LoansAdvances />, me(['I-04:VIEW', 'I-04:LOAN_FORECLOSE', 'I-04:LOAN_RESCHEDULE']));
    const loans = await screen.findByRole('table', { name: t('i04l.loans') });
    expect(within(loans).getAllByText('₹15,000.00').length).toBeGreaterThan(0);
    expect(within(loans).getByText(t('i04l.fromHrms'))).toBeTruthy();
    const requests = await screen.findByRole('table', { name: t('i04l.requests') });
    expect(within(requests).getByText('Amount differs from the order')).toBeTruthy();

    await userEvent.click(
      within(loans).getByRole('button', { name: t('i04l.openLoan', ['LN-2026-000007']) }),
    );
    const panel = await screen.findByRole('region', { name: 'LN-2026-000007' });
    expect(within(panel).getByText('₹15,000.00 (25%)')).toBeTruthy();
    expect(
      within(panel).getByText(t('i04l.nextInstalmentLine', ['₹5,250.00', '202612'])),
      'SRS-12-23: the tracker names the next instalment, principal and interest',
    ).toBeTruthy();
    expect(
      within(panel).getByRole('table', { name: t('i04l.version', [1, t('i04l.reason.ORIGINAL')]) }),
    ).toBeTruthy();
    expect(within(panel).getByText(t('i04l.foreclose'))).toBeTruthy();
    expect(
      within(panel)
        .getByRole('button', { name: t('i04l.sendForeclosure') })
        .hasAttribute('disabled'),
    ).toBe(true);
    await userEvent.click(within(panel).getByRole('button', { name: t('common.close') }));

    await userEvent.click(
      within(loans).getByRole('button', { name: t('i04l.openLoan', ['LN-2026-000008']) }),
    );
    const hrms = await screen.findByRole('region', { name: 'LN-2026-000008' });
    expect(within(hrms).getByText(t('i04l.hrmsReadOnly'))).toBeTruthy();
    expect(within(hrms).getByText(t('i04l.noNextInstalment'))).toBeTruthy();
    expect(within(hrms).queryByText(t('i04l.foreclose'))).toBeNull();
    expect(screen.queryByText(t('i04l.newSanction'))).toBeNull();
  });

  it('SRS-12-11: the sanction is checked, shows the schedule and the room, and the order is mandatory', async () => {
    const calls = backend(
      routes({
        'POST /v1/loans/sanctions/previews': () => ({
          eis: '104882',
          loanType: 'FESTIVAL_ADVANCE',
          terms: { sanctioned: 20000, principalInstalments: 2, instalment: 15000, startYymm: '202611' },
          schedule: [
            { number: 1, payrollYymm: '202611', principal: 15000, interest: 0 },
            { number: 2, payrollYymm: '202612', principal: 5000, interest: 0 },
          ],
          outstanding: 0,
          maxNewSanction: 500000,
          reduced: true,
          openRequest: 31,
        }),
      }),
    );
    wrap(<LoansAdvances />, me(['I-04:VIEW', 'I-04:LOAN_SANCTION']));
    await screen.findByText(t('i04l.newSanction'));
    const check = screen.getByRole('button', { name: t('i04l.check') });
    expect(check.hasAttribute('disabled')).toBe(true);
    await userEvent.type(screen.getAllByLabelText(t('m01.eis'))[1] as HTMLElement, '104882');
    await userEvent.selectOptions(screen.getByLabelText(t('i04l.type')), 'FESTIVAL_ADVANCE');
    await userEvent.type(screen.getByLabelText(t('i04l.sanctioned')), '20000');
    await userEvent.type(screen.getAllByLabelText(t('i04l.instalments'))[0] as HTMLElement, '1');
    await userEvent.type(screen.getAllByLabelText(t('i04l.instalment'))[0] as HTMLElement, '20000');
    fireEvent.change(screen.getAllByLabelText(t('i04l.start'))[0] as HTMLElement, {
      target: { value: '2026-11' },
    });
    await userEvent.click(screen.getByRole('button', { name: t('i04l.check') }));
    expect(await screen.findByText(t('i04l.reduced', ['₹15,000.00', 2]))).toBeTruthy();
    expect(screen.getByRole('table', { name: t('i04l.schedule') })).toBeTruthy();
    expect(screen.getByRole('button', { name: t('i04l.sendSanction') }).hasAttribute('disabled')).toBe(true);
    expect(calls.some((c) => c.url.includes('/sanctions/previews') && c.method === 'POST')).toBe(true);
  });

  it('SRS-12-26: the report exports only a range of months in order; a request without type or amount shows a dash', async () => {
    backend(
      routes({
        'GET /v1/loans/requests': (url: string) =>
          paged(
            [
              {
                approvalId: 32,
                eis: '104883',
                employee: 'Asha Rao',
                loanType: null,
                amount: null,
                status: 'PENDING_APPROVAL',
                submittedBy: 'maker.delhi',
                submittedAt: '2026-10-26T06:00:00Z',
                returnReason: null,
              },
            ],
            1,
            url,
          ),
        'GET /v1/loans/7': () => ({
          ...DETAIL,
          versions: [
            { ...DETAIL.versions[0], versionNo: 2, reason: 'RESCHEDULE', supersededAt: null },
            { ...DETAIL.versions[0], supersededAt: '2026-10-20T06:00:00Z' },
          ],
        }),
      }),
    );
    wrap(<LoansAdvances />, me(['I-04:VIEW', 'I-04:EXPORT']));
    const requests = await screen.findByRole('table', { name: t('i04l.requests') });
    expect(within(requests).getAllByText('—').length).toBe(3);
    const exported = () => screen.getByRole('button', { name: t('i04l.reportExport') });
    // Month pickers opening on this business month (IST): the report is ready at once.
    expect(exported().hasAttribute('disabled')).toBe(false);
    const card = screen.getByText(t('i04l.report')).closest('.card') as HTMLElement;
    const [from, to] = Array.from(card.querySelectorAll('input[type="month"]')) as [HTMLElement, HTMLElement];
    fireEvent.change(from, { target: { value: '2026-05' } });
    fireEvent.change(to, { target: { value: '2026-04' } });
    expect(exported().hasAttribute('disabled')).toBe(true);
    fireEvent.change(to, { target: { value: '' } });
    expect(exported().hasAttribute('disabled')).toBe(true);
    fireEvent.change(to, { target: { value: '2026-06' } });
    expect(exported().hasAttribute('disabled')).toBe(false);

    const loans = screen.getByRole('table', { name: t('i04l.loans') });
    await userEvent.click(
      within(loans).getByRole('button', { name: t('i04l.openLoan', ['LN-2026-000007']) }),
    );
    const panel = await screen.findByRole('region', { name: 'LN-2026-000007' });
    expect(within(panel).getAllByText(t('i04l.superseded')).length).toBe(1);
  });

  it('SRS-12-7 (l.2659): a returned request is corrected from the sanction form, prefilled; SRS l.2721: every status is offered', async () => {
    const calls = backend(routes());
    wrap(<LoansAdvances />, me(['I-04:VIEW', 'I-04:LOAN_SANCTION']));
    const requests = await screen.findByRole('table', { name: t('i04l.requests') });
    await userEvent.click(within(requests).getByRole('button', { name: t('i04l.correctOf', [31]) }));
    expect(await screen.findByText(t('i04l.correcting', [31]))).toBeTruthy();
    expect(screen.getByDisplayValue('104882')).toBeTruthy();
    expect(screen.getByDisplayValue('10000')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByText(t('i04l.correcting', [31]))).toBeNull();
    await userEvent.selectOptions(screen.getByLabelText(t('i04l.status')), 'SETTLED_IN_FNF');
    await waitFor(() => expect(calls.some((c) => c.url.includes('status=SETTLED_IN_FNF'))).toBe(true));
  });

  it('SRS-12-7: a reader without the sanction right sees why it cannot correct a returned request', async () => {
    backend(routes());
    wrap(<LoansAdvances />, me(['I-04:VIEW']));
    const requests = await screen.findByRole('table', { name: t('i04l.requests') });
    const correct = within(requests).getByRole('button', { name: t('i04l.correct') });
    expect(correct).toBeDisabled();
    expect(correct).toHaveAttribute('title', t('i04l.correctWhy'));
  });

  it('SRS-22-1: without I-04 VIEW nothing is shown', () => {
    backend(routes());
    const view = wrap(<LoansAdvances />, me(['M-01:VIEW']));
    expect(view.container.textContent).toBe('');
  });
});
