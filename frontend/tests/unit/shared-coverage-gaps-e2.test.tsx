import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FinalSettlement } from '@/modules/settlement';
import { OrderCopyCard } from '@/shared/ui/orderCopy';
import { backend, me, referenceLists, t, wrap } from './support/harness';

/**
 * Coverage of the order copy card's empty pick (SRS-G-30) and of O-05's chosen case kept across pages (SRS-10-8: the
 * case chosen stays open while another page of the worklist is read).
 */
afterEach(() => vi.unstubAllGlobals());

describe('the order copy card', () => {
  it('SRS_G_30_aPickerClosedWithoutAFileStoresNothingAndSaysNothing', async () => {
    backend({
      'GET /v1/reference/lists': () => referenceLists({ ORDER_COPY_DOCUMENT_TYPE: [['OTHER', 'Other']] }),
    });
    const onAttach = vi.fn(async () => undefined);
    wrap(
      <OrderCopyCard
        hint="hint"
        canAttach
        stored={[]}
        storedError={null}
        fileHref={(id) => `/files/${id}`}
        today="2026-11-01"
        attachmentRequired
        referenceRequired={() => false}
        onAttach={onAttach}
      />,
      me([]),
    );
    const card = screen.getByText(t('wf.ord.title')).closest('.card') as HTMLElement;
    const files = card.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(files, { target: { files: [] } });
    expect(onAttach).not.toHaveBeenCalled();
    expect(within(card).getByRole('status')).toHaveTextContent(t('wf.ord.noneBlocked'));
  });
});

describe('O-05 worklist pages', () => {
  it('SRS_10_8_theChosenCaseStaysOpenWhenTheNextPageDoesNotHoldIt', async () => {
    const cases = Array.from({ length: 25 }, (_, i) => ({
      caseId: 100 + i,
      caseReference: `FNF-2026-${String(100 + i).padStart(5, '0')}`,
      eis: String(104800 + i),
      name: `Member ${i}`,
      office: 'DH001 - Delhi Division',
      separationType: 'RETIREMENT',
      separationDate: '2026-09-30',
      stage: 'CLEARANCES_RAISED',
      awaiting: [],
      status: 'INITIATED',
      netPayable: null,
      payrollType: 'STAFF',
      payrollDues: null,
      totalRecoveries: null,
      recoveredFromGratuity: null,
      awaitingChecker: false,
      hqApprovalPending: false,
    }));
    const calls = backend({
      'GET /v1/settlement-cases/awaiting': () => [],
      'GET /v1/settlement-cases/summary': () => ({
        separations: 0,
        settled: 0,
        settledAmount: 0,
        byType: [],
        awaitingClearance: 0,
        hqApproval: 0,
        netRecovery: 0,
      }),
      'GET /v1/settlement-cases/invoices': () => ({ items: [], page: 0, size: 20, total: 0 }),
      'GET /v1/settlement-cases?': (url) => {
        const page = Number(/[?&]page=(\d+)/.exec(url)?.[1] ?? 0);
        const size = Number(/[?&]size=(\d+)/.exec(url)?.[1] ?? 20);
        return { items: cases.slice(page * size, page * size + size), page, size, total: cases.length };
      },
      'GET /v1/settlement-cases/100': () => ({ status: 404, body: { errorCode: 'FNF-404-CASE' } }),
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    const table = await screen.findByRole('table', { name: t('screen.O-05') });
    await within(table).findByText('FNF-2026-00100');
    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/v1/settlement-cases/100'))).toBe(true));
    const [nav] = screen.getAllByRole('navigation', { name: t('page.nav') });
    await userEvent.click(within(nav as HTMLElement).getByRole('button', { name: t('page.next') }));
    expect(await within(table).findByText('FNF-2026-00120')).toBeInTheDocument();
    // The case chosen on page 1 is still the one read: no case of page 2 is asked for.
    expect(calls.some((c) => c.url.endsWith('/v1/settlement-cases/120'))).toBe(false);
  });
});
