import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LoansAdvances } from '@/modules/loans';
import { loansApi, type LoanDocument } from '@/modules/loans/api/loansApi';
import { backend, me, paged, t, wrap } from './support/harness';

/**
 * Branch gaps of I-04 Loans & Advances: a returned request without a type or an amount is corrected
 * from an empty field (SRS-12-7), and an order or document without a reference number sends none
 * (SRS-G-30, US-GAP-202).
 */

afterEach(() => vi.unstubAllGlobals());

function doc(reference: string): LoanDocument {
  return {
    documentType: 'SANCTION_ORDER',
    reference,
    orderDate: '2026-10-01',
    issuingAuthority: 'AGM (P)',
    file: new File(['%PDF'], 'order.pdf', { type: 'application/pdf' }),
  };
}

describe('I-04 Loans & Advances branch gaps', () => {
  it('SRS-12-7: a returned request without a type or an amount is corrected from empty fields', async () => {
    backend({
      'GET /v1/loans?': (url: string) => paged([], 0, url),
      'GET /v1/loans/requests': (url: string) =>
        paged(
          [
            {
              approvalId: 32,
              eis: '104883',
              employee: 'Sunita Rao',
              loanType: null,
              amount: null,
              status: 'RETURNED',
              submittedBy: 'maker.delhi',
              submittedAt: '2026-10-26T06:00:00Z',
              returnReason: 'Type missing',
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
    });
    wrap(<LoansAdvances />, me(['I-04:VIEW', 'I-04:LOAN_SANCTION']));
    const requests = await screen.findByRole('table', { name: t('i04l.requests') });
    await userEvent.click(within(requests).getByRole('button', { name: t('i04l.correctOf', [32]) }));
    expect(await screen.findByText(t('i04l.correcting', [32]))).toBeTruthy();
    const grid = screen.getByDisplayValue('104883').closest('.rf-grid') as HTMLElement;
    expect((grid.querySelector('select') as HTMLSelectElement).value).toBe('');
    expect((grid.querySelector('input[maxlength="15"]') as HTMLInputElement).value).toBe('');
  });

  it('SRS-G-30: an order and a receipt without a reference send none; with one, it is sent', async () => {
    const calls = backend({
      'POST /v1/loans/7/foreclosures': () => ({ approvalId: 90, status: 'PENDING_APPROVAL' }),
      'POST /v1/loans/7/reschedules': () => ({ approvalId: 91, status: 'PENDING_APPROVAL' }),
    });
    await loansApi.foreclose(7, doc(''), doc(''));
    const bare = calls.find((c) => c.url.includes('/foreclosures'))?.body as FormData;
    expect(bare.has('reference')).toBe(false);
    expect(bare.has('orderReference')).toBe(false);
    expect(bare.get('orderDocumentType')).toBe('SANCTION_ORDER');

    await loansApi.reschedule(
      7,
      {
        principalInstalments: 4,
        instalment: 1000,
        interestInstalments: 0,
        interestInstalment: null,
        startYymm: '202612',
      },
      doc('O/1'),
      doc('R/2'),
    );
    const full = calls.find((c) => c.url.includes('/reschedules'))?.body as FormData;
    expect(full.get('reference')).toBe('R/2');
    expect(full.get('orderReference')).toBe('O/1');
  });
});
