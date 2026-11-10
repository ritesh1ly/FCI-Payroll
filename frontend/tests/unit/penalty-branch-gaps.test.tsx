import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { OrderRow, PaymentRow } from '@/modules/penalty/api/penaltyApi';
import { penaltyApi } from '@/modules/penalty/api/penaltyApi';
import { PenaltyRecovery } from '@/modules/penalty';
import { dateTime } from '@/shared/ui';
import { backend, me, paged, t, wrap } from './support/harness';

/**
 * I-06 remaining edges (SRS-14-2, SRS-14-12, SRS-14-15, SRS l.2997-2998, SRS l.3030): a returned
 * request recorded before the Penalty ID was given is corrected by its approval number and opens
 * without an amount when it had none; an over-and-above payment under a code without a name, not
 * taxable, shows the date its invoice went to APPS; an order copy without a reference number sends
 * no reference.
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

const PAYMENT: PaymentRow = {
  id: 4,
  eis: '104882',
  payCode: 914,
  payName: null,
  payrollYymm: '202611',
  amount: 2500,
  taxable: false,
  applicationDate: '2026-10-01',
  sanctionReference: 'SAN/1',
  sanctionDate: null,
  status: 'APPROVED',
  invoiceStatus: 'SENT',
  invoiceSentAt: '2026-11-03T06:30:00Z',
  checkerReason: null,
};

const ALL = ['I-06:VIEW', 'I-06:CREATE', 'I-06:OVER_ABOVE'];

function routes() {
  return backend({
    'GET /v1/penalties?': (url: string) => paged([ORDER], 1, url),
    'GET /v1/penalties/requests': (url: string) =>
      paged(
        [
          {
            approvalId: 33,
            penaltyRef: null,
            eis: '104882',
            employee: 'Rakesh Sharma',
            amount: null,
            status: 'RETURNED',
            submittedBy: 'maker',
            submittedAt: '2026-10-26T06:00:00Z',
            returnReason: 'Order copy unreadable',
            summary: 'RECOVERY CH/REC/3',
          },
        ],
        1,
        url,
      ),
    'GET /v1/penalties/over-and-above': (url: string) => paged([PAYMENT], 1, url),
    'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
  });
}

describe('I-06 remaining edges', () => {
  it('SRS l.2997-2998: a returned request without a Penalty ID is corrected by its approval, without an amount', async () => {
    routes();
    wrap(<PenaltyRecovery />, me(ALL));
    const requests = await screen.findByRole('table', { name: t('i06.requests') });
    const row = within(requests).getByText('RECOVERY CH/REC/3').closest('tr') as HTMLElement;
    // No Penalty ID yet: the first cell is empty.
    expect((row.querySelector('td') as HTMLElement).textContent).toBe('');
    await userEvent.click(within(row).getByRole('button', { name: t('i06.correctOf', [33]) }));
    expect(await screen.findByText(t('i06.correcting', [33]))).toBeTruthy();
    expect(
      (screen.getByLabelText(t('i06.amount'), { selector: '#i06-amount' }) as HTMLInputElement).value,
    ).toBe('');
  });

  it('SRS l.3030: a payment under a code without a name, not taxable, shows when its invoice went to APPS', async () => {
    routes();
    wrap(<PenaltyRecovery />, me(ALL));
    const cell = await screen.findByText(/SAN\/1/);
    const row = cell.closest('tr') as HTMLElement;
    expect(row).toHaveTextContent('914');
    expect(row).not.toHaveTextContent(t('i06.taxable'));
    expect(row).toHaveTextContent(t('i03.invoiceSentOn', [dateTime('2026-11-03T06:30:00Z')]));
  });

  it('SRS-14-12: an order copy without a reference number sends no reference', async () => {
    const calls = backend({ 'POST /v1/penalties/7/cancellations': () => ({ status: 202, body: { id: 1 } }) });
    await penaltyApi.cancel(7, 'Appeal allowed', {
      documentType: 'OTHER',
      reference: '',
      orderDate: '2026-10-01',
      issuingAuthority: 'Disciplinary Authority',
      file: new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }),
    });
    const form = calls.find((c) => c.method === 'POST')?.body as FormData;
    expect(form.has('reference')).toBe(false);
    expect(form.get('documentType')).toBe('OTHER');
    expect((form.get('file') as File).name).toBe('order.pdf');
  });
});
