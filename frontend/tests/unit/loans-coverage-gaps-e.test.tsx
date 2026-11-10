import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LoansScreen } from '@/modules/loans';
import { businessYymm } from '@/shared/time';
import { backend, me, paged, referenceLists, sent, t, wrap } from './support/harness';

/**
 * Coverage of I-04's remaining paths (OD-121; SRS-12-1, SRS-12-17, SRS-G-30): the Checker's way to W-01, the record
 * dialog closed, a ledger page that no longer holds the chosen loan, a session with no period and an order type the
 * document master does not hold.
 */
afterEach(() => vi.unstubAllGlobals());

const HRMS_LOAN = {
  id: 1,
  loanNumber: 'LN-2024-0912',
  eis: '880601',
  employee: 'Rohit Sinha',
  loanType: 'PLI_ADVANCE',
  sanctioned: 1209600,
  recoveryStart: '202501',
  principalInstalments: 144,
  instalment: 8400,
  paidInstalments: 89,
  principalOutstanding: 462000,
  interestAmount: 0,
  interestOutstanding: 0,
  status: 'ACTIVE',
  source: 'HRMS',
  separated: false,
  scheduleReconciled: true,
};
const LABOUR_LOAN = {
  ...HRMS_LOAN,
  id: 2,
  loanNumber: 'LN-2026-0402',
  eis: '880603',
  employee: 'Harpal Rana',
  loanType: 'FESTIVAL_ADVANCE',
  source: 'LABOUR_ENT',
};

function routes(ledger: (url: string) => unknown) {
  return {
    'GET /v1/loans/summary': () => ({
      yymm: '202610',
      activeLoans: 0,
      outstanding: 0,
      recoveryPrincipal: 0,
      recoveryInterest: 0,
      importFailures: 0,
      failingInterface: null,
    }),
    'GET /v1/loans/disbursements': (url: string) => paged([], 0, url),
    'GET /v1/loans/1/orders': () => [],
    'GET /v1/loans/2/orders': () => [],
    'GET /v1/loans?': ledger,
    'GET /v1/document-types': () => [
      { code: 'SANCTION_ORDER', name: 'Sanction Order', referenceRequired: true },
    ],
    'GET /v1/reference/lists': () =>
      referenceLists({
        ORDER_COPY_DOCUMENT_TYPE: [
          ['SANCTION_ORDER', 'Sanction Order'],
          ['OTHER', 'Other'],
        ],
      }),
  };
}

function screenOf() {
  return <LoansScreen cpfTile={null} cpf={() => null} cpfRegister={() => null} />;
}

describe('I-04 remaining paths', () => {
  it('SRS_12_22_theCheckerGoesToW01FromALoanSetUpHere', async () => {
    backend(routes((url) => paged([HRMS_LOAN, LABOUR_LOAN], 2, url)));
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    wrap(screenOf(), me(['I-04:VIEW'], { roles: ['PAYROLL_CHECKER'] }));
    const ledger = await screen.findByRole('table', { name: t('i04w.ledger') });
    const here = (await within(ledger).findByText('LN-2026-0402')).closest('tr') as HTMLElement;
    await userEvent.click(within(here).getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('?s=W-01'));
  });

  it('SRS_12_1_aSessionWithoutRolesOrPeriodReadsTheBusinessMonthAndTheDialogCloses', async () => {
    const calls = backend(routes((url) => paged([LABOUR_LOAN], 1, url)));
    wrap(screenOf(), me(['I-04:VIEW'], { roles: undefined, period: '' }));
    const ledger = await screen.findByRole('table', { name: t('i04w.ledger') });
    const row = (await within(ledger).findByText('LN-2026-0402')).closest('tr') as HTMLElement;
    // No role list: not the Checker, no check mark.
    expect(within(row).queryByTitle(t('wf.approveTitle'))).toBeNull();
    // No session period: the summary reads this business month (India), as YYYYMM.
    expect(calls.some((c) => c.url.includes(`/v1/loans/summary?yymm=${businessYymm()}`))).toBe(true);
    await userEvent.click(within(row).getByRole('button', { name: t('i04w.viewOf', ['LN-2026-0402']) }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByTestId('i04-register')).not.toHaveAttribute('open');
  });

  it('SRS_12_17_aChosenLoanStaysChosenWhenTheNextPageDoesNotHoldIt', async () => {
    const many = Array.from({ length: 25 }, (_, i) => ({
      ...LABOUR_LOAN,
      id: 100 + i,
      loanNumber: `LN-2026-${String(500 + i)}`,
    }));
    backend(
      routes((url) => {
        const page = Number(/[?&]page=(\d+)/.exec(url)?.[1] ?? 0);
        const size = Number(/[?&]size=(\d+)/.exec(url)?.[1] ?? 20);
        return { items: many.slice(page * size, page * size + size), page, size, total: many.length };
      }),
    );
    wrap(screenOf(), ['I-04:VIEW']);
    const ledger = await screen.findByRole('table', { name: t('i04w.ledger') });
    await within(ledger).findByText('LN-2026-500');
    const [nav] = screen.getAllByRole('navigation', { name: t('page.nav') });
    await userEvent.click(within(nav as HTMLElement).getByRole('button', { name: t('page.next') }));
    expect(await within(ledger).findByText('LN-2026-520')).toBeInTheDocument();
    expect(within(ledger).queryByText('LN-2026-500')).toBeNull();
    // The order card still reads the first loan chosen (its orders were asked for).
    await waitFor(() => expect(screen.getByText(t('wf.ord.title'))).toBeInTheDocument());
  });

  it('SRS_G_30_aTypeTheDocumentMasterDoesNotHoldIsStoredWithoutAReference', async () => {
    const calls = backend({
      ...routes((url) => paged([LABOUR_LOAN], 1, url)),
      'POST /v1/loans/2/orders': () => ({ status: 201, body: { id: 9 } }),
    });
    wrap(screenOf(), ['I-04:VIEW', 'I-04:LOAN_SANCTION']);
    await screen.findByText('LN-2026-0402');
    const card = (await screen.findByText(t('wf.ord.title'))).closest('.card') as HTMLElement;
    await within(card).findByRole('option', { name: 'Other' });
    const [type] = Array.from(card.querySelectorAll('select')) as HTMLSelectElement[];
    const [, orderDate, , files] = Array.from(card.querySelectorAll('input')) as HTMLInputElement[];
    await userEvent.selectOptions(type as HTMLSelectElement, 'OTHER');
    fireEvent.change(orderDate as HTMLInputElement, { target: { value: '2026-07-01' } });
    await waitFor(() => expect(files).toBeEnabled());
    await userEvent.upload(
      files as HTMLInputElement,
      new File(['%PDF'], 'o.pdf', { type: 'application/pdf' }),
    );
    await waitFor(() => expect(sent(calls, 'POST', '/v1/loans/2/orders')).toHaveLength(1));
    const [body] = sent(calls, 'POST', '/v1/loans/2/orders') as FormData[];
    expect(body?.get('documentType')).toBe('OTHER');
  });
});

describe('I-04 empty ledger and the CPF card', () => {
  it('SRS_12_1_anEmptyLedgerChoosesNoLoanAndTheCpfCardOpensItsRequest', async () => {
    backend(routes((url) => paged([], 0, url)));
    const opened: (number | null)[] = [];
    wrap(
      <LoansScreen
        cpfTile={null}
        cpf={(onOpen) => (
          <button type="button" onClick={() => onOpen(13)}>
            open CPF 13
          </button>
        )}
        cpfRegister={(opening) => {
          opened.push(opening);
          return null;
        }}
      />,
      ['I-04:VIEW'],
    );
    expect(await screen.findByText(t('i04l.none'))).toBeInTheDocument();
    // No loan chosen: the order card cannot attach.
    const card = (await screen.findByText(t('wf.ord.title'))).closest('.card') as HTMLElement;
    expect(within(card).getByRole('status')).toHaveTextContent(t('wf.ord.viewOnly'));
    await userEvent.click(screen.getByRole('button', { name: 'open CPF 13' }));
    expect(screen.getByTestId('i04-register')).toHaveAttribute('open');
    expect(opened.at(-1)).toBe(13);
  });
});
