import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EsevaPortal } from '@/modules/eseva';
import { I18nProvider } from '@/shared/i18n';
import { periodLabel } from '@/shared/session';
import { businessYymm, yymmToMonth } from '@/shared/time';
import { ToastProvider } from '@/shared/ui';
import { backend, paged, problem, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/payroll',
  useSearchParams: () => new URLSearchParams(),
}));

/**
 * E-01 eSeva member portal (SRS-17-1, SRS-17-7, SRS-17-10, BR-ESEVA-004): a payslip download the server refuses is
 * told, never silent; a slip with more deductions than earnings and without designation, pattern, PAN or bank, for a
 * member with no surname or CPF code and no TDS figure, shows dashes and blank cells; the document closes on Escape
 * only; an action tile ignores keys other than Enter and Space; nothing is drawn before the session is known.
 */

afterEach(() => vi.unstubAllGlobals());

const MEMBER = ['E-01:VIEW', 'E-01:SELF', 'E-01:CREATE'];
const YYMM = businessYymm();
const MONTH = periodLabel(yymmToMonth(YYMM));

const overview = (over: object = {}) => ({
  latest: { yymm: YYMM, gross: 1000, deductions: 1500, net: 0 },
  cpf: null,
  loan: null,
  tds: null,
  requests: { open: 0, hrms: 0, cpf: 0, other: 0 },
  myRequests: [],
  documents: [{ kind: 'PAY_SLIP', period: YYMM, issued: '2026-10-30T05:00:00Z', state: 'FINAL', id: null }],
  ...over,
});

const slip = {
  yymm: YYMM,
  eis: '104882',
  name: 'R.',
  designation: null,
  office: null,
  payPattern: null,
  panMasked: null,
  bankAccountMasked: null,
  pran: null,
  earnings: [{ payCode: 101, name: 'Basic Pay', group: 'PAY', amount: 1000 }],
  deductions: [
    { payCode: 904, name: 'Income Tax (TDS)', group: 'TAX', amount: 500 },
    { payCode: 905, name: 'Professional Tax', group: 'TAX', amount: 1000 },
  ],
  gross: 1000,
  totalDeductions: 1500,
  net: 0,
  layoutVariant: 'STAFF',
  provisional: false,
};

const OWN = {
  eis: '104882',
  firstName: 'R.',
  lastName: null,
  designation: 'Manager',
  payPattern: 'IDA',
  band: null,
  location: 'Regional Office Delhi',
  cpfCode: null,
};

function routes(over: Record<string, (url: string) => unknown> = {}) {
  return backend({
    'GET /v1/eseva/overview/mine': () => overview(),
    'GET /v1/eseva/me': () => OWN,
    'GET /v1/payslips/mine/': (url) => (url.endsWith('/pdf') ? problem(404, 'PAY-404-SLIP') : slip),
    'GET /v1/eseva/privacy-notice/mine': () => ({ version: '1', acknowledged: true }),
    'GET /v1/hrms-update-requests/fields': () => [],
    'GET /v1/document-types': () => [],
    'GET /v1/hrms-update-requests/mine': (url) => paged([], 0, url),
    'GET /v1/eseva/notifications/mine': (url) => paged([], 0, url),
    ...over,
  });
}

function Portal() {
  return (
    <EsevaPortal
      record={<p>record part</p>}
      withdrawals={<p>withdrawals part</p>}
      vpf={<p>vpf part</p>}
      statement={<p>statement part</p>}
      tax={<p>tax part</p>}
      form16={<p>form16 part</p>}
      leave={<p>leave part</p>}
      payslips={(onOpen) => (
        <button type="button" onClick={() => onOpen(YYMM)}>
          month part
        </button>
      )}
      hrms={() => <p>hrms part</p>}
    />
  );
}

describe('E-01 member portal edges', () => {
  it('BR-ESEVA-004: a payslip PDF the server refuses is told to the member', async () => {
    routes();
    wrap(<Portal />, MEMBER);
    await screen.findByText('R.');
    await userEvent.click(screen.getByRole('button', { name: t('e01w.myPayslip') }));
    expect(await screen.findByText(t('error.PAY-404-SLIP'))).toBeTruthy();
  });

  it('SRS-17-7: a slip with more deductions than earnings leaves the extra earning cells blank', async () => {
    routes();
    wrap(<Portal />, MEMBER);
    const tax = await screen.findAllByText('Professional Tax');
    const row = tax[0]?.closest('tr') as HTMLElement;
    const cells = within(row).getAllByRole('cell');
    expect(cells[0]?.textContent).toBe('');
    expect(cells[1]?.textContent).toBe('');
  });

  it('SRS-17-10: the payslip document shows dashes for what the slip lacks and closes on Escape only', async () => {
    routes();
    wrap(<Portal />, MEMBER);
    await screen.findByText('R.');
    await userEvent.click(screen.getByRole('button', { name: t('e01w.previousMonths') }));
    await userEvent.click(await screen.findByRole('button', { name: 'month part' }));
    const paper = await screen.findByRole('dialog', { name: t('e01w.doc.title', [MONTH, 'R.']) });
    // Designation, pattern, PAN, CPF code and bank are not on the slip.
    expect(within(paper).getAllByText('—').length).toBeGreaterThanOrEqual(5);
    expect(within(paper).getByText(t('e01w.doc.taxable', ['—']))).toBeTruthy();
    const row = within(paper).getByText('Professional Tax').closest('tr') as HTMLElement;
    expect(within(row).getAllByRole('cell')[0]?.textContent).toBe('');
    await userEvent.keyboard('a');
    expect(screen.getByRole('dialog')).toBeTruthy();
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('SRS-17-1: an action tile ignores keys other than Enter and Space', async () => {
    routes();
    wrap(<Portal />, MEMBER);
    await screen.findByText('R.');
    const tax = screen.getByRole('button', { name: t('e01w.act.tax') });
    tax.focus();
    await userEvent.keyboard('x');
    expect(screen.queryByText('tax part')).toBeNull();
    await userEvent.keyboard('{Enter}');
    expect(await screen.findByText('tax part')).toBeTruthy();
  });

  it('SRS-17-1: nothing is drawn until the session is known', () => {
    routes({ 'GET /auth/me': () => new Promise(() => undefined) });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { container } = render(
      <I18nProvider>
        <QueryClientProvider client={client}>
          <ToastProvider>
            <Portal />
          </ToastProvider>
        </QueryClientProvider>
      </I18nProvider>,
    );
    expect(container.textContent).toBe('');
  });
});
