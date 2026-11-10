import { renderHook, screen, waitFor, within } from '@testing-library/react';
import { fireEvent, render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import { useState, type ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  EMPTY_ORDER,
  ExceptionsWorklist,
  OrderFields,
  PayrollQueries,
  Supplementary,
} from '@/modules/general';
import type { OrderDraft } from '@/modules/general';
import { generalApi } from '@/modules/general/api/generalApi';
import type { ExceptionCondition, ExceptionItem, PayrollQuery } from '@/modules/general/api/generalApi';
import { useStoredOrders } from '@/modules/general/orchestration/generalQueries';
import { I18nProvider } from '@/shared/i18n';
import { backend, me, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * Module 01 general screens beyond the main paths (SRS-G-9, SRS-G-11, SRS-G-16, SRS-G-30, SRS-G-34;
 * SRS l.604, l.1643): a run opened with only one of its address parameters, a condition without a
 * module, an upstream item without an owner, filters cleared back to all, a query no longer on the
 * page and confirmed by the Checker, order particulars, and the stored orders of a closed record.
 */

afterEach(() => vi.unstubAllGlobals());

/** A label as the browser names it (whitespace collapsed, trimmed). */
function n(text: string): string {
  return text.replace(/\s+/g, ' ').trim();
}

// ---------------------------------------------------------------- X-05

const ITEM: ExceptionItem = {
  id: 21,
  cycleInstanceId: null,
  eis: '105220',
  memberName: 'Sourav Banerjee',
  moduleCode: 'C-02',
  exceptionCode: 'HRMS_DOB',
  severity: 'WARNING',
  blocksGate: null,
  entityName: 'employee',
  fieldName: 'date_of_birth',
  fieldSource: 'HRMS',
  resolvableInPayroll: false,
  correctionScreen: null,
  ownerRoleCode: null,
  detail: 'Date of birth missing in HRMS',
  status: 'ROUTED_UPSTREAM',
  acceptedBy: null,
  acceptedReason: null,
  raisedAt: '2026-10-20T05:00:00Z',
  ageDays: 1,
  resolvedAt: null,
};

const CONDITION: ExceptionCondition = {
  moduleCode: null,
  exceptionCode: 'HRMS_DOB',
  items: 1,
  members: 1,
  blocksG2: false,
  owner: null,
  resolvableInPayroll: false,
  actionable: false,
  correctionScreen: null,
  detail: 'Date of birth missing in HRMS',
  firstRaisedAt: '2026-10-20T05:00:00Z',
};

const TILES = { open: 1, blockingG2: 0, acceptedWithReason: 0, upstream: 1 };

function x05() {
  return backend({
    'GET /v1/reference/lists': () =>
      referenceLists({
        EXCEPTION_STATUS: [
          ['OPEN', 'Open'],
          ['IN_PROGRESS', 'In progress'],
          ['RESOLVED', 'Resolved'],
          ['ACCEPTED', 'Accepted with reason'],
          ['ROUTED_UPSTREAM', 'Routed upstream'],
        ],
        EXCEPTION_SEVERITY: [
          ['BLOCKING', 'Blocking'],
          ['WARNING', 'Warning'],
          ['INFORMATIONAL', 'Informational'],
          ['SECURITY', 'Security'],
        ],
      }),
    'GET /v1/exceptions/groups': () => ({
      tiles: TILES,
      items: [CONDITION],
      page: 0,
      size: 20,
      total: 1,
      modules: ['C-02'],
    }),
    'GET /v1/exceptions/offices': () => [{ id: 11, code: 'DH001', name: 'Delhi', type: 'DO' }],
    'GET /v1/exceptions?': (url) => ({ tiles: TILES, ...paged([ITEM], 1, url) }),
  });
}

/** The filters of X-05 open from its Filter button (as drawn). */
async function openX05Filters() {
  // The last rendered worklist (a test may mount a second one before unmounting the first).
  const buttons = screen.getAllByRole('button', { name: t('x05.filter') });
  await userEvent.click(buttons[buttons.length - 1] as HTMLElement);
}

describe('X-05 Exceptions (branch gaps)', () => {
  it('SRS l.1643 (C-08): a run opened with its cycle only, or with a malformed cycle, keeps what is valid', async () => {
    window.history.pushState({}, '', '/payroll/?s=X-05&cycle=5');
    const calls = x05();
    const first = wrap(<ExceptionsWorklist />, ['X-05:VIEW']);
    await openX05Filters();
    expect(await screen.findByText(n(t('x05.c08', [''])))).toBeInTheDocument();
    await waitFor(() => expect(calls.some((c) => c.url.includes('cycle=5'))).toBe(true));
    expect(calls.some((c) => c.url.includes('period='))).toBe(false);
    first.unmount();

    window.history.pushState({}, '', '/payroll/?s=X-05&period=2026-10&cycle=abc');
    const later = x05();
    wrap(<ExceptionsWorklist />, ['X-05:VIEW']);
    await openX05Filters();
    await waitFor(() => expect(screen.getByLabelText(t('x05.period'))).toHaveValue('2026-10'));
    expect(screen.queryByRole('button', { name: t('x05.c08.clear') })).toBeNull();
    expect(later.some((c) => c.url.includes('cycle='))).toBe(false);
    window.history.pushState({}, '', '/');
  });

  it('SRS_EH_4: a condition without a module lists its items by code; an upstream item without owner is HRMS-owned', async () => {
    const calls = x05();
    wrap(<ExceptionsWorklist />, ['X-05:VIEW']);
    await openX05Filters();
    const row = (await screen.findByText('HRMS_DOB')).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: t('x05.viewOf', ['HRMS_DOB']) }));
    const item = await waitFor(() => {
      const found = document.querySelector('tr[data-item="21"]');
      expect(found).not.toBeNull();
      return found as HTMLElement;
    });
    expect(within(item).getByText(t('x05.owner.hrms'))).toBeInTheDocument();
    const read = calls.filter((c) => c.url.startsWith('/') || c.url.includes('/v1/exceptions?'));
    const items = read.find((c) => c.url.includes('/v1/exceptions?') && c.url.includes('code=HRMS_DOB'));
    expect(items?.url).not.toContain('module=');
  });

  it('SRS l.604: a class or office chosen and then cleared reads every class and office again', async () => {
    const calls = x05();
    wrap(<ExceptionsWorklist />, ['X-05:VIEW']);
    await openX05Filters();
    await screen.findByText('HRMS_DOB');
    await screen.findByRole('option', { name: 'C-02' });
    await userEvent.selectOptions(screen.getByLabelText(t('x05.class')), 'C-02');
    await waitFor(() => expect(calls.some((c) => c.url.includes('module=C-02'))).toBe(true));
    await userEvent.selectOptions(screen.getByLabelText(t('x05.class')), '');
    await screen.findByRole('option', { name: 'DH001 - Delhi' });
    await userEvent.selectOptions(screen.getByLabelText(t('x05.office')), '11');
    await waitFor(() => expect(calls.some((c) => c.url.includes('office=11'))).toBe(true));
    await userEvent.selectOptions(screen.getByLabelText(t('x05.office')), '');
    expect(screen.getByLabelText(t('x05.class'))).toHaveValue('');
    expect(screen.getByLabelText(t('x05.office'))).toHaveValue('');
    const last = calls.filter((c) => c.url.includes('/v1/exceptions/groups?')).at(-1);
    expect(last?.url).not.toContain('module=');
    expect(last?.url).not.toContain('office=');
  });
});

// ---------------------------------------------------------------- X-06

const QUERY: PayrollQuery = {
  id: 3,
  reference: 'QRY-00000003',
  payrollId: 88,
  eis: '105220',
  memberName: 'Sourav Banerjee',
  payrollYymm: '202607',
  channel: 'STAFF',
  queryText: 'Why is HRA lower?',
  concernsComputedAmount: true,
  status: 'IN_PROGRESS',
  raisedBy: 'maker.delhi',
  raisedAt: '2026-10-20T05:00:00Z',
  makerResponse: 'City class changed',
  respondedBy: 'maker.delhi',
  respondedAt: '2026-10-20T06:00:00Z',
  resolutionText: null,
  resolvedBy: null,
  resolvedAt: null,
};

function queries(rows: PayrollQuery[]) {
  return backend({
    'GET /v1/reference/lists': () =>
      referenceLists({
        PAYROLL_QUERY_STATUS: [
          ['OPEN', 'Open'],
          ['IN_PROGRESS', 'In Progress'],
          ['RESOLVED', 'Resolved'],
          ['CLOSED', 'Closed'],
        ],
      }),
    'GET /v1/queries/counts': () => ({ OPEN: 0, IN_PROGRESS: 1, RESOLVED: 0, CLOSED: 0 }),
    'GET /v1/queries?': (url) => {
      const status = /[?&]status=([A-Z_]+)/.exec(url)?.[1];
      return paged(status ? rows.filter((q) => q.status === status) : rows, undefined, url);
    },
    'POST /v1/queries/3/resolve': () => ({ ...QUERY, status: 'RESOLVED' }),
  });
}

describe('X-06 Payroll Query (branch gaps)', () => {
  it('SRS_22_5: the Checker confirms a computed-amount query; the card stays when the query leaves the page', async () => {
    const calls = queries([QUERY]);
    wrap(<PayrollQueries />, ['X-06:VIEW', 'X-06:APPROVE']);
    await userEvent.click(await screen.findByRole('button', { name: t('x06.editOf', ['QRY-00000003']) }));
    const confirm = screen.getByRole('button', { name: t('x06.confirm') });
    expect(confirm).toBeDisabled();
    // Another status filter (the drawn Filter): the query is no longer on the page; its card still shows it.
    await userEvent.click(screen.getByRole('button', { name: t('x06.filterAct') }));
    await waitFor(() =>
      expect((screen.getByLabelText(t('gen.status')) as HTMLSelectElement).options.length).toBeGreaterThan(1),
    );
    await userEvent.selectOptions(screen.getByLabelText(t('gen.status')), 'CLOSED');
    await screen.findByText(t('x06.none'));
    expect(screen.getByText(t('x06.detail', ['QRY-00000003']))).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText(t('x06.resolution')), 'Arrear paid in the next run');
    await userEvent.click(screen.getByRole('button', { name: t('x06.confirm') }));
    await waitFor(() =>
      expect(sent(calls, 'POST', '/v1/queries/3/resolve')[0]).toMatchObject({
        resolution: 'Arrear paid in the next run',
      }),
    );
  });

  it('SRS_G_11: a query not about a computed amount is resolved by the Maker', async () => {
    queries([{ ...QUERY, concernsComputedAmount: false }]);
    wrap(<PayrollQueries />, ['X-06:VIEW', 'X-06:EDIT']);
    await userEvent.click(await screen.findByRole('button', { name: t('x06.editOf', ['QRY-00000003']) }));
    expect(screen.getByRole('button', { name: t('x06.resolve') })).toBeDisabled();
    expect(screen.queryByRole('button', { name: t('x06.confirm') })).toBeNull();
  });
});

// ---------------------------------------------------------------- I-23

describe('I-23 Supplementary (branch gaps)', () => {
  it('SRS_G_30: while the document types cannot be read the request waits for its order and nothing is recorded', async () => {
    backend({
      'GET /v1/supplementary/tiles': () => ({
        open: 0,
        pendingChecker: 0,
        blockedDuplicates: 0,
        rejected: 0,
      }),
      'GET /v1/supplementary': (url) => paged([], 0, url),
      'GET /v1/document-types': () => problem(503, 'COM-503-UNAVAILABLE'),
    });
    wrap(<Supplementary />, me(['I-23:VIEW', 'I-23:CREATE']));
    await userEvent.click(await screen.findByRole('button', { name: t('i23.newAct') }));
    const dialog = screen.getByRole('dialog', { name: t('i23.raiseTitle') });
    // Every particular of the request given: only the order (held on the card) is missing.
    await userEvent.type(within(dialog).getByLabelText(t('gen.eis')), '105220');
    const period = within(dialog).getByLabelText(t('i23.forPeriod')) as HTMLSelectElement;
    await userEvent.selectOptions(period, period.options[1]?.value ?? '');
    await userEvent.type(within(dialog).getByLabelText(t('i23.applicationDate')), '2026-10-01');
    expect(within(dialog).getByText(t('i23.orderFirst'))).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: t('i23.record') })).toBeDisabled();
  });
});

// ---------------------------------------------------------------- order particulars

function OrderHost({ types }: { types: { code: string; name: string; referenceRequired: boolean }[] }) {
  const [order, setOrder] = useState<OrderDraft>(EMPTY_ORDER);
  return (
    <>
      <OrderFields idPrefix="t" value={order} types={types} today="2026-11-04" onChange={setOrder} />
      <output data-testid="file">{order.file ? order.file.name : 'none'}</output>
    </>
  );
}

describe('order particulars (branch gaps)', () => {
  it('SRS_G_30_aTypeNeedingAReferenceMarksItAndAClearedFileIsNone', async () => {
    render(
      <I18nProvider>
        <OrderHost
          types={[
            { code: 'SANCTION_ORDER', name: 'Sanction order', referenceRequired: true },
            { code: 'NOTE', name: 'Note', referenceRequired: false },
          ]}
        />
      </I18nProvider>,
    );
    const reference = () => screen.getByText(t('ord.reference'), { exact: false });
    expect(reference().textContent).not.toContain('*');
    await userEvent.selectOptions(screen.getByLabelText(t('ord.type')), 'SANCTION_ORDER');
    expect(reference().textContent).toContain(' *');
    await userEvent.selectOptions(screen.getByLabelText(t('ord.type')), 'NOTE');
    expect(reference().textContent).not.toContain('*');
    const file = screen.getByLabelText(t('ord.file'));
    await userEvent.upload(file, new File(['%PDF'], 'order.pdf', { type: 'application/pdf' }));
    expect(screen.getByTestId('file')).toHaveTextContent('order.pdf');
    fireEvent.change(file, { target: { files: [] } });
    expect(screen.getByTestId('file')).toHaveTextContent('none');
  });

  it('SRS_G_30_anOrderWithoutAReferenceSendsNone', async () => {
    const calls = backend({
      'POST /v1/off-cycle/9/order': () => ({ id: 1 }),
      'POST /v1/hrms-fallback/uploads/4/order': () => ({ id: 2 }),
    });
    const order = {
      documentType: 'NOTE',
      orderDate: '2026-10-01',
      issuingAuthority: 'AGM (P)',
      file: new File(['%PDF'], 'o.pdf', { type: 'application/pdf' }),
    };
    await generalApi.offCycleOrder(9, { ...order, reference: '' });
    await generalApi.uploadOrder(4, { ...order, reference: 'HQ/17' });
    const [none, given] = [
      sent(calls, 'POST', '/off-cycle/9/order')[0] as FormData,
      sent(calls, 'POST', '/uploads/4/order')[0] as FormData,
    ];
    expect(none.has('reference')).toBe(false);
    expect(given.get('reference')).toBe('HQ/17');
  });
});

// ---------------------------------------------------------------- stored orders

describe('stored orders (branch gaps)', () => {
  it('SRS_G_16_aRecordNotChosenAsksForNothingUntilItIsFetchedAndThenForRecordZero', async () => {
    const calls = backend({
      'GET /v1/off-cycle/0/orders': () => [],
      'GET /v1/supplementary/0/orders': () => [],
      'GET /v1/hrms-fallback/uploads/0/orders': () => [],
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const host = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
    for (const kind of ['off-cycle', 'supplementary', 'upload'] as const) {
      const { result } = renderHook(() => useStoredOrders(kind, null), { wrapper: host });
      expect(result.current.fetchStatus).toBe('idle');
      await result.current.refetch();
    }
    expect(calls.map((c) => c.url)).toEqual([
      expect.stringContaining('/v1/off-cycle/0/orders'),
      expect.stringContaining('/v1/supplementary/0/orders'),
      expect.stringContaining('/v1/hrms-fallback/uploads/0/orders'),
    ]);
  });
});
