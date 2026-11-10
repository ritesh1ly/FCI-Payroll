import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { ExceptionsWorklist, HrmsFallback, OffCycle, PayrollQueries, Supplementary } from '@/modules/general';
import { payrollIdLabel } from '@/modules/general/components/PayrollQueries';
import type {
  Enablement,
  ExceptionItem,
  FallbackUpload,
  OffCycleCase,
  PayrollQuery,
  SupplementaryPayment,
} from '@/modules/general/api/generalApi';
import { GovernedProvider } from '@/modules/workflow';
import { I18nProvider } from '@/shared/i18n';
import { date, ToastProvider } from '@/shared/ui';
import { exportedUrl, paged, referenceLists, t } from './support/harness';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * The Module 01 screens (layout after Wireframe v6.1; behaviour from the SRS) against a stubbed
 * backend: positive paths, refusals shown with their reasons, and what each role may see or do.
 */

/** A route answers a request: a body (200) or an explicit status with a problem. */
type Answer = unknown;
type Route = (url: string, init?: RequestInit) => Answer;

interface Call {
  url: string;
  method: string;
  body: unknown;
}

/** A fetch stub that answers by "METHOD path-prefix" (longest prefix wins); records every call. */
function backend(routes: Record<string, Route>) {
  const calls: Call[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      if (url.endsWith('/auth/csrf')) {
        document.cookie = 'XSRF-TOKEN=t; Path=/';
        return new Response(null, { status: 204 });
      }
      const raw = init?.body;
      calls.push({
        url,
        method,
        body: raw instanceof FormData ? raw : typeof raw === 'string' ? JSON.parse(raw) : undefined,
      });
      const key = Object.keys(routes)
        .sort((a, b) => b.length - a.length)
        .find((k) => {
          const [m, prefix] = k.split(' ');
          return m === method && prefix !== undefined && url.includes(prefix);
        });
      const answer = key
        ? routes[key]?.(url, init)
        : url.includes('/orders') || url.includes('/document-types')
          ? []
          : {};
      const explicit =
        answer !== null && typeof answer === 'object' && 'status' in answer && 'body' in answer
          ? (answer as { status: number; body: unknown })
          : null;
      return new Response(JSON.stringify(explicit ? explicit.body : (answer ?? {})), {
        status: explicit ? explicit.status : 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }),
  );
  return calls;
}

function problem(status: number, code: string, args: unknown[] = []) {
  return { status, body: { errorCode: code, messageKey: `error.${code}`, args } };
}

function wrap(children: ReactNode, permissions: string[]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(['me'], { permissions, userId: 1 });
  return render(
    <I18nProvider>
      <QueryClientProvider client={client}>
        <ToastProvider>
          <GovernedProvider>{children}</GovernedProvider>
        </ToastProvider>
      </QueryClientProvider>
    </I18nProvider>,
  );
}

/** The register row of a record (references appear in more than one table). */
async function rowOf(reference: string): Promise<HTMLElement> {
  return waitFor(() => {
    const row = document.querySelector<HTMLElement>(`tr[data-reference="${reference}"]`);
    if (!row) throw new Error(`no row ${reference}`);
    return row;
  });
}

/** The row of a live enablement in the withdrawals opened from History. */
async function liveRow(reference: string): Promise<HTMLElement> {
  return waitFor(() => {
    const row = document.querySelector<HTMLElement>(`tr[data-live="${reference}"]`);
    if (!row) throw new Error(`no row ${reference}`);
    return row;
  });
}

function invoiceRow(reference: string): HTMLElement {
  return document.querySelector<HTMLElement>(`tr[data-invoice="${reference}"]`) as HTMLElement;
}

const TYPES = [
  { code: 'OTHER', name: 'Other', referenceRequired: false },
  { code: 'SANCTION_ORDER', name: 'Sanction order', referenceRequired: true },
];
const PDF = new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' });
const STORED = [
  {
    id: 3,
    documentTypeCode: 'SANCTION_ORDER',
    documentTypeName: 'Sanction Order',
    referenceNumber: 'FCI/RO-DEL/2026/118',
    orderDate: '2026-07-01',
    issuingAuthority: 'RO Delhi',
    fileName: 'order_104882.pdf',
    status: 'ACTIVE',
    uploadedBy: 'maker.delhi',
    uploadedAt: '2026-07-12T05:34:00Z',
  },
];

// ---------------------------------------------------------------- I-20

const CASE: OffCycleCase = {
  id: 7,
  reference: 'OFC-00000007',
  eis: '105220',
  memberName: 'Sourav Banerjee',
  caseType: 'LATE_JOINER',
  salaryYymm: '202609',
  processingYymm: '202610',
  amount: 52000,
  approvalReference: 'HQ/PAY/17',
  duplicateCheck: 'PASS',
  status: 'DRAFT',
  invoiceStatus: 'NOT_RAISED',
  invoiceNo: null,
  invoiceSentToAppsDate: null,
  appsCancelConfirmedAt: null,
  excludedFromYymm: null,
  cancelReason: null,
  orders: 0,
  approvalId: null,
};

/** X-06 mocks: the queries of a status (from the URL) one page at a time, and the counts by status. */
function queriesRoute(queries: PayrollQuery[]) {
  return (url: string) => {
    if (url.includes('/counts')) {
      const counts: Record<string, number> = { OPEN: 0, IN_PROGRESS: 0, RESOLVED: 0, CLOSED: 0 };
      queries.forEach((q) => (counts[q.status] = (counts[q.status] ?? 0) + 1));
      return counts;
    }
    const status = /[?&]status=([A-Z_]+)/.exec(url)?.[1];
    return paged(status ? queries.filter((q) => q.status === status) : queries);
  };
}

/** X-05 mock: the worklist with its tiles and one page of rows. */
function worklist(w: { tiles: unknown; items: ExceptionItem[] }) {
  return { ...w, page: 0, size: 20, total: w.items.length };
}

/**
 * X-05 mocks: the server groups and counts the conditions (SRS l.634) - mirrored here over the given
 * items - the opened condition's items come filtered by its code, and the offices of the scope.
 * {@code members} overrides the counted members of a code (a condition larger than one page).
 */
function x05Routes(w: {
  tiles: unknown;
  items: ExceptionItem[];
  members?: Record<string, number>;
  modules?: string[];
}) {
  const unsettledItem = (e: ExceptionItem) =>
    e.status === 'OPEN' || e.status === 'IN_PROGRESS' || e.status === 'ROUTED_UPSTREAM';
  const groups = () => {
    const byKey = new Map<string, ExceptionItem[]>();
    for (const e of w.items) {
      const key = `${e.moduleCode}|${e.exceptionCode}`;
      byKey.set(key, [...(byKey.get(key) ?? []), e]);
    }
    return [...byKey.values()].map((g) => {
      const first = g[0] as ExceptionItem;
      const owners = new Set(g.map((e) => e.ownerRoleCode));
      return {
        moduleCode: first.moduleCode,
        exceptionCode: first.exceptionCode,
        items: g.length,
        members: w.members?.[first.exceptionCode] ?? new Set(g.map((e) => e.eis).filter(Boolean)).size,
        blocksG2: g.some((e) => unsettledItem(e) && e.blocksGate === 'G2' && e.severity === 'BLOCKING'),
        owner: owners.size === 1 ? first.ownerRoleCode : null,
        resolvableInPayroll: g.some((e) => e.resolvableInPayroll),
        actionable: g.some((e) => unsettledItem(e) && e.resolvableInPayroll),
        correctionScreen: first.correctionScreen,
        detail: first.detail,
        firstRaisedAt: first.raisedAt,
      };
    });
  };
  return {
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
    'GET /v1/exceptions/groups': () => {
      const items = groups();
      return {
        tiles: w.tiles,
        items,
        page: 0,
        size: 20,
        total: items.length,
        modules: w.modules ?? [...new Set(w.items.map((e) => e.moduleCode))],
      };
    },
    'GET /v1/exceptions/offices': () => [
      { id: 11, code: 'DH001', name: 'Delhi', type: 'DO' },
      { id: 12, code: 'DH010', name: 'Delhi depot', type: 'DEPOT' },
    ],
    'GET /v1/exceptions': (url: string) => {
      const code = new URL(url, 'http://x').searchParams.get('code');
      return worklist({ tiles: w.tiles, items: w.items.filter((e) => !code || e.exceptionCode === code) });
    },
  };
}

/**
 * X-07 mocks (docs/plan/X-pagination.md): the paged enablements and uploads, the live enablements of
 * the upload form, and the tiles the server counts from the same rows.
 */
function fallback(enablements: Enablement[], uploads: FallbackUpload[]) {
  return {
    'GET /v1/hrms-fallback/enablements': (url: string) =>
      url.includes('/live')
        ? enablements.filter((e) => e.status === 'OPEN' || e.status === 'UPLOADED')
        : paged(enablements),
    'GET /v1/hrms-fallback/uploads': (url: string) => (url.includes('/rejections') ? [] : paged(uploads)),
    'GET /v1/hrms-fallback/summary': () => ({
      awaitingApproval: uploads.filter((u) => u.status === 'PENDING_APPROVAL').length,
      reconciliationPending: uploads.filter((u) => u.status === 'APPROVED' && u.reconStatus === 'PENDING')
        .length,
    }),
    'GET /v1/hrms-fallback/interface-status': () => ({ down: false, since: null }),
    'GET /v1/hrms-fallback/reconciliation': () => paged([]),
    'GET /v1/document-types': () => TYPES,
    'GET /v1/reference/lists': () =>
      referenceLists({
        HRMS_FALLBACK_TYPE: [
          ['ATTENDANCE_LEAVE', '(a) Attendance & leave'],
          ['SUSPENSION_ABSCONDING', '(b) Suspension, absconding, revocation'],
        ],
        ORDER_COPY_DOCUMENT_TYPE: ORDER_TYPES,
      }),
  };
}

const CASE_TYPES: [string, string][] = [
  ['LATE_JOINER', 'Late joiner'],
  ['DEATH_IN_SERVICE', 'Death-in-service'],
  ['OTHER', 'Other'],
];
const ORDER_TYPES: [string, string][] = [
  ['SANCTION_ORDER', 'Sanction Order'],
  ['OTHER', 'Other'],
];
/** The routes every I-20 test needs: the reference lists (case types, order types) and the document types. */
const I20 = {
  'GET /v1/reference/lists': () =>
    referenceLists({ OFF_CYCLE_CASE_TYPE: CASE_TYPES, ORDER_COPY_DOCUMENT_TYPE: ORDER_TYPES }),
  'GET /v1/document-types': () => TYPES,
  'GET /v1/off-cycle/7/orders': () => [],
  'GET /v1/off-cycle/8/orders': () => [],
  'GET /v1/off-cycle/9/orders': () => [],
};

/** The drawn order card (one per screen, for the selected case). */
async function orderCard(): Promise<HTMLElement> {
  return (await screen.findByText(t('wf.ord.title'))).closest('.card') as HTMLElement;
}

/** Fills the drawn card's particulars and picks the file (which stores it). */
async function pickOrder(card: HTMLElement, office = 'GM (Pay)') {
  const user = userEvent.setup();
  const [type] = Array.from(card.querySelectorAll('select')) as HTMLSelectElement[];
  const [, date, issuing, file] = Array.from(card.querySelectorAll('input')) as HTMLInputElement[];
  await user.selectOptions(type as HTMLSelectElement, 'OTHER');
  fireEvent.change(date as HTMLInputElement, { target: { value: '2026-01-05' } });
  if (office) await user.type(issuing as HTMLInputElement, office);
  await user.upload(file as HTMLInputElement, PDF);
  return user;
}

async function raise(user: ReturnType<typeof userEvent.setup>, month = 2, type = 'LATE_JOINER') {
  await user.click(await screen.findByRole('button', { name: t('i20.raise') }));
  const dialog = screen.getByRole('dialog', { name: t('i20.raiseTitle') });
  await user.type(within(dialog).getByLabelText(t('gen.eis')), '105220');
  await user.selectOptions(within(dialog).getByLabelText(t('i20.caseType')), type);
  const select = within(dialog).getByLabelText(t('i20.salaryMonth')) as HTMLSelectElement;
  const value = select.options[month]?.value ?? '';
  await user.selectOptions(select, value);
  await user.type(within(dialog).getByLabelText(t('i20.approvalReference')), 'HQ/PAY/17');
  return { dialog, value };
}

describe('I-20 Off-Cycle as drawn', () => {
  it('SRS_G_16: the register shows source badges and the drawn columns; a case without its order cannot be submitted', async () => {
    backend({ ...I20, 'GET /v1/off-cycle': () => paged([CASE]) });
    wrap(<OffCycle />, ['I-20:VIEW', 'I-20:CREATE', 'I-20:SUBMIT']);
    const row = await rowOf('OFC-00000007');
    expect(within(row).getByText('SEP 2026')).toBeInTheDocument();
    expect(within(row).getByText('Passed - not paid, not scheduled')).toBeInTheDocument();
    for (const col of ['i20.col.case', 'i20.col.caseType', 'i20.col.duplicateCheck', 'i20.col.actions'])
      expect(screen.getAllByText(t(col)).length).toBeGreaterThan(0);
    // Field-source badges in the header: the amount is computed (C), the member from HRMS (H).
    expect(screen.getAllByText('C', { selector: '.src.s-CALC' }).length).toBeGreaterThan(0);
    expect(screen.getAllByText('H', { selector: '.src.s-HRMS' }).length).toBeGreaterThan(0);
    expect(screen.getByText(t('i20.guard'))).toBeInTheDocument();
    expect(invoiceRow('OFC-00000007')).toHaveTextContent(t('inv.NOT_RAISED'));
    // The Maker's Edit opens the case; its submission waits for the order.
    await userEvent.click(within(row).getByRole('button', { name: `${t('common.edit')} OFC-00000007` }));
    const view = screen.getByRole('dialog', { name: t('i20.viewTitle', ['OFC-00000007']) });
    const submit = within(view).getByRole('button', { name: t('common.submitForApproval') });
    expect(submit).toBeDisabled();
    expect(submit).toHaveAttribute('title', t('i20.block.order'));
  });

  it('SRS_G_10: ＋ Raise Off-Cycle Case sends the case, never an amount, and selects it for its order', async () => {
    const calls = backend({
      ...I20,
      'GET /v1/off-cycle': () => paged([]),
      'POST /v1/off-cycle': () => ({ ...CASE, id: 9, reference: 'OFC-00000009' }),
    });
    wrap(<OffCycle />, ['I-20:VIEW', 'I-20:CREATE']);
    const user = userEvent.setup();
    const { dialog, value } = await raise(user);
    // The case types come from the database list (OD-123), English keys.
    expect(
      Array.from((within(dialog).getByLabelText(t('i20.caseType')) as HTMLSelectElement).options).map(
        (o) => o.value,
      ),
    ).toEqual(['', 'LATE_JOINER', 'DEATH_IN_SERVICE', 'OTHER']);
    await user.click(within(dialog).getByRole('button', { name: t('i20.capture') }));
    await screen.findByText(/Case OFC-00000009 recorded/);
    const post = calls.find((c) => c.method === 'POST' && c.url.endsWith('/v1/off-cycle'));
    expect(post?.body).toEqual({
      eis: '105220',
      caseType: 'LATE_JOINER',
      salaryYymm: value,
      approvalReference: 'HQ/PAY/17',
    });
    expect(post?.body).not.toHaveProperty('amount');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(within(await orderCard()).getByText(t('i20.ord.hint'))).toBeInTheDocument();
  });

  it('a locked salary month is refused with the message that names I-23; an incomplete case is not sent', async () => {
    const calls = backend({
      ...I20,
      'GET /v1/off-cycle': () => paged([]),
      'POST /v1/off-cycle': () => problem(409, 'OFC-409-LOCKED', ['202607']),
    });
    wrap(<OffCycle />, ['I-20:VIEW', 'I-20:CREATE']);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: t('i20.raise') }));
    const empty = screen.getByRole('dialog', { name: t('i20.raiseTitle') });
    expect(within(empty).getByRole('button', { name: t('i20.capture') })).toBeDisabled();
    await user.click(within(empty).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    const { dialog } = await raise(user, 1, 'OTHER');
    await user.click(within(dialog).getByRole('button', { name: t('i20.capture') }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/I-23/);
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1);
  });

  it('SRS_G_30: the first case is selected; its order is attached as multipart and the stored orders are listed with View links', async () => {
    const calls = backend({
      ...I20,
      'GET /v1/off-cycle': () => paged([CASE]),
      'GET /v1/off-cycle/7/orders': () => STORED,
      'POST /v1/off-cycle/7/order': () => ({ id: 1, fileName: 'order.pdf', status: 'ACTIVE' }),
    });
    wrap(<OffCycle />, ['I-20:VIEW', 'I-20:CREATE', 'I-20:SUBMIT']);
    expect(await rowOf('OFC-00000007')).toHaveAttribute('aria-selected', 'true');
    const card = await orderCard();
    expect(await within(card).findByText('order_104882.pdf')).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: t('wf.view') })).toHaveAttribute(
      'href',
      '/nginx/fci-payroll/v1/off-cycle/7/files/3',
    );
    await pickOrder(card);
    await waitFor(() => expect(calls.some((c) => c.url.endsWith('/v1/off-cycle/7/order'))).toBe(true));
    const form = calls.find((c) => c.url.endsWith('/v1/off-cycle/7/order'))?.body as FormData;
    expect(form.get('documentType')).toBe('OTHER');
    expect(form.get('issuingAuthority')).toBe('GM (Pay)');
    expect((form.get('file') as File).name).toBe('order.pdf');
  });

  it('SRS_G_30: without the issuing office the picked order is not sent and the card says what is missing', async () => {
    const calls = backend({ ...I20, 'GET /v1/off-cycle': () => paged([CASE]) });
    wrap(<OffCycle />, ['I-20:VIEW', 'I-20:CREATE', 'I-20:SUBMIT']);
    await rowOf('OFC-00000007');
    const card = await orderCard();
    await pickOrder(card, '');
    expect(await within(card).findByText(t('wf.ord.particularsFirst'))).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
  });

  it('a checker sees cases, their orders and ✔ only on a case waiting for the Checker; none of the maker actions', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    backend({
      ...I20,
      'GET /v1/off-cycle': () =>
        paged([
          { ...CASE, orders: 1 },
          { ...CASE, id: 8, reference: 'OFC-00000008', status: 'PENDING_CHECKER', orders: 1 },
        ]),
      'GET /v1/off-cycle/7/orders': () => STORED,
    });
    wrap(<OffCycle />, ['I-20:VIEW', 'I-20:APPROVE']);
    const row = await rowOf('OFC-00000007');
    const edit = within(row).getByRole('button', { name: `${t('common.edit')} OFC-00000007` });
    expect(edit).toBeDisabled();
    expect(edit.getAttribute('data-why')).toBe(t('i20.why.makerEdit'));
    expect(within(row).queryByRole('button', { name: '✔' })).toBeNull();
    const raise = screen.getByRole('button', { name: t('i20.raise') });
    expect(raise).toBeDisabled();
    expect(raise.getAttribute('data-why')).toBe(t('reason.makerOnly'));
    expect(
      within(invoiceRow('OFC-00000007')).getByRole('button', { name: t('wf.inv.cancel') }),
    ).toBeDisabled();
    expect(await screen.findByText('order_104882.pdf')).toBeInTheDocument();
    expect(within(await orderCard()).getByText(t('wf.ord.viewOnly'))).toBeInTheDocument();
    await userEvent.click(within(await rowOf('OFC-00000008')).getByRole('button', { name: '✔' }));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('s=W-01'));
    vi.unstubAllGlobals();
  });

  it('SRS_G_37: ⊘ Cancel in the invoice table asks for a reason; a sent invoice cannot be cancelled; a cancelled one is a new version', async () => {
    const calls = backend({
      ...I20,
      'GET /v1/off-cycle': () =>
        paged([
          CASE,
          {
            ...CASE,
            id: 8,
            reference: 'OFC-00000008',
            invoiceStatus: 'SENT_TO_APPS',
            invoiceNo: '2026070005',
            invoiceSentToAppsDate: '2026-07-04',
          },
          { ...CASE, id: 9, reference: 'OFC-00000009', status: 'CANCELLED', cancelReason: 'Twice' },
        ]),
      'POST /v1/off-cycle/7/cancel': () => ({ ...CASE, status: 'CANCELLED' }),
    });
    wrap(<OffCycle />, ['I-20:VIEW', 'I-20:SUBMIT']);
    await rowOf('OFC-00000007');
    const sent = invoiceRow('OFC-00000008');
    expect(sent).toHaveTextContent(t('inv.SENT_TO_APPS'));
    expect(sent).toHaveTextContent('2026070005');
    const off = within(sent).getByRole('button', { name: t('wf.inv.cancel') });
    expect(off).toBeDisabled();
    expect(off.getAttribute('data-why')).toBe(t('inv.cancelBlocked'));
    expect(invoiceRow('OFC-00000009')).toHaveTextContent(t('wf.inv.cancelledVersion'));
    const user = userEvent.setup();
    // A reason dismissed sends nothing; a reason given cancels.
    await user.click(within(invoiceRow('OFC-00000007')).getByRole('button', { name: t('wf.inv.cancel') }));
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    expect(calls.filter((c) => c.url.endsWith('/7/cancel'))).toHaveLength(0);
    await user.click(within(invoiceRow('OFC-00000007')).getByRole('button', { name: t('wf.inv.cancel') }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByRole('textbox'), 'Raised in error');
    await user.click(within(dialog).getAllByRole('button').at(-1) as HTMLElement);
    await waitFor(() =>
      expect(calls.find((c) => c.url.endsWith('/7/cancel'))?.body).toEqual({ reason: 'Raised in error' }),
    );
  });

  it('an approved case shows the month it is excluded from; a blocked one says why in its view; the Filter sends the case type', async () => {
    const calls = backend({
      ...I20,
      'GET /v1/off-cycle': () =>
        paged([
          { ...CASE, status: 'APPROVED', excludedFromYymm: '202609', orders: 1 },
          {
            ...CASE,
            id: 8,
            reference: 'OFC-00000008',
            status: 'BLOCKED',
            duplicateCheck: 'FAIL: paid',
            orders: 1,
          },
        ]),
    });
    wrap(<OffCycle />, ['I-20:VIEW', 'I-20:CREATE', 'I-20:SUBMIT']);
    expect(await screen.findByText('Excluded from the regular run of SEP 2026')).toBeInTheDocument();
    const blocked = await rowOf('OFC-00000008');
    expect(within(blocked).getByText('Failed - paid')).toBeInTheDocument();
    expect(
      within(blocked)
        .getByRole('button', { name: `${t('common.edit')} OFC-00000008` })
        .getAttribute('data-why'),
    ).toBe(t('i20.why.duplicate'));
    await userEvent.click(within(blocked).getByRole('button', { name: `${t('common.view')} OFC-00000008` }));
    expect(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submitForApproval') }),
    ).toHaveAttribute('title', t('i20.block.duplicate'));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.close') }),
    );
    // The drawn Filter opens the case type (database list) and sends the English key.
    await userEvent.click(screen.getByRole('button', { name: t('i20.filterAct') }));
    await userEvent.selectOptions(screen.getByLabelText(t('i20.caseType')), 'DEATH_IN_SERVICE');
    await waitFor(() => expect(calls.some((c) => c.url.includes('caseType=DEATH_IN_SERVICE'))).toBe(true));
  });

  it('a submission refused by the server shows its message', async () => {
    backend({
      ...I20,
      'GET /v1/off-cycle': () => paged([{ ...CASE, orders: 1 }]),
      'POST /v1/off-cycle/7/submit': () => problem(409, 'OFC-409-DUPLICATE', ['FAIL: paid']),
    });
    wrap(<OffCycle />, ['I-20:VIEW', 'I-20:CREATE', 'I-20:SUBMIT']);
    const user = userEvent.setup();
    await user.click(
      within(await rowOf('OFC-00000007')).getByRole('button', { name: `${t('common.view')} OFC-00000007` }),
    );
    await user.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submitForApproval') }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(/duplicate-payment check failed/);
  });
});

// ---------------------------------------------------------------- I-23

const PAYMENT: SupplementaryPayment = {
  id: 11,
  reference: 'SUP-00000011',
  eis: '106004',
  memberName: 'Prakash',
  paymentType: 'SALARY_WAGE',
  payCode: null,
  forPeriodYymm: '202607',
  amount: 41200,
  applicationDate: '2026-08-10',
  sanctionDate: null,
  sanctionOrderRef: null,
  duplicateCheck: 'BLOCKED',
  duplicateOf: 'SUP-00000010',
  status: 'BLOCKED',
  invoiceStatus: 'NOT_RAISED',
  invoiceId: null,
  invoiceSentAt: null,
  appsCancelStatus: 'NOT_REQUESTED',
  cancelReason: null,
  orderAttachmentId: 5,
  orderReference: 'DH/SUP/106004',
  approvalId: null,
};
const TILES = { open: 2, pendingChecker: 0, blockedDuplicates: 1, rejected: 1 };

const PAY_TYPES: [string, string][] = [
  ['SALARY_WAGE', 'Pending salary / wage'],
  ['SALARY_ARREAR', 'Salary arrear'],
  ['RECOVERY', 'Recovery'],
];
/** The routes every I-23 test needs: tiles, reference lists and the document types. */
const I23 = {
  'GET /v1/supplementary/tiles': () => TILES,
  'GET /v1/reference/lists': () =>
    referenceLists({
      SUPPLEMENTARY_PAYMENT_TYPE: PAY_TYPES,
      ORDER_COPY_DOCUMENT_TYPE: [
        ['SANCTION_ORDER', 'Sanction Order'],
        ['OTHER', 'Other'],
      ],
    }),
  'GET /v1/document-types': () => TYPES,
  'GET /v1/supplementary/11/orders': () => [],
  'GET /v1/supplementary/12/orders': () => [],
};

/** "＋ New Supplementary Payment" filled with a valid request (period: the fourth month back). */
async function raiseSupplementary(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: t('i23.newAct') }));
  const dialog = screen.getByRole('dialog', { name: t('i23.raiseTitle') });
  await user.type(within(dialog).getByLabelText(t('gen.eis')), '106004');
  const period = within(dialog).getByLabelText(t('i23.forPeriod')) as HTMLSelectElement;
  await user.selectOptions(period, period.options[3]?.value ?? '');
  fireEvent.change(within(dialog).getByLabelText(t('i23.applicationDate')), {
    target: { value: '2026-01-10' },
  });
  return dialog;
}

describe('I-23 Supplementary as drawn', () => {
  it('SRS_G_34: a duplicate is shown with its match and its submission is held; the drawn tiles and cards are there', async () => {
    backend({
      ...I23,
      'GET /v1/supplementary': () =>
        paged([PAYMENT, { ...PAYMENT, id: 12, reference: 'SUP-00000012', status: 'REJECTED' }]),
    });
    wrap(<Supplementary />, ['I-23:VIEW', 'I-23:CREATE', 'I-23:SUBMIT']);
    const row = await rowOf('SUP-00000011');
    expect(within(row).getByText('Matches SUP-00000010')).toBeInTheDocument();
    expect(within(row).getByText('Locked')).toBeInTheDocument();
    expect(within(row).getByText('DH/SUP/106004 - attached')).toBeInTheDocument();
    const edit = within(row).getByRole('button', { name: `${t('common.edit')} SUP-00000011` });
    expect(edit).toBeDisabled();
    expect(edit.getAttribute('data-why')).toBe(t('i23.block.duplicate'));
    await userEvent.click(within(row).getByRole('button', { name: `${t('common.view')} SUP-00000011` }));
    const submit = within(screen.getByRole('dialog')).getByRole('button', {
      name: t('common.submitForApproval'),
    });
    expect(submit).toBeDisabled();
    expect(submit).toHaveAttribute('title', t('i23.block.duplicate'));
    for (const label of [
      'i23.tile.requests',
      'i23.tile.pending',
      'i23.tile.rejectedLocked',
      'i23.tile.blockedDrawn',
    ])
      expect(screen.getByText(t(label))).toBeInTheDocument();
    const rejected = screen.getByText(t('i23.tile.rejectedLocked')).closest('.tile') as HTMLElement;
    expect(rejected).toHaveTextContent('1');
    expect(screen.getByText(t('i23.accepted'))).toBeInTheDocument();
    expect(screen.getByText(t('i23.invoicesDrawn'))).toBeInTheDocument();
  });

  it('BR_CYC_010: the order held on the card is recorded with the request in one multipart call; only pending salary is offered', async () => {
    const calls = backend({
      ...I23,
      'GET /v1/supplementary/tiles': () => ({
        open: 0,
        pendingChecker: 0,
        blockedDuplicates: 0,
        rejected: 0,
      }),
      'GET /v1/supplementary': () => paged([]),
      'POST /v1/supplementary': () => ({ ...PAYMENT, status: 'DRAFT', reference: 'SUP-00000012' }),
    });
    wrap(<Supplementary />, ['I-23:VIEW', 'I-23:CREATE']);
    const user = userEvent.setup();
    // Without an order on the card, the request cannot be recorded.
    let dialog = await raiseSupplementary(user);
    expect(within(dialog).getByText(t('i23.orderFirst'))).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: t('i23.record') })).toBeDisabled();
    // The payment type comes from the database list, narrowed to what this screen records (SRS-G-33).
    const type = within(dialog).getByLabelText(t('i23.paymentType')) as HTMLSelectElement;
    expect([...type.options].map((o) => o.value)).toEqual(['SALARY_WAGE']);
    await user.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
    // The order is held on the drawn card (one file), then the request is raised with it.
    const card = (await screen.findByText(t('wf.ord.title'))).closest('.card') as HTMLElement;
    const [orderType] = Array.from(card.querySelectorAll('select')) as HTMLSelectElement[];
    const [, orderDate, office, file] = Array.from(card.querySelectorAll('input')) as HTMLInputElement[];
    await user.selectOptions(orderType as HTMLSelectElement, 'OTHER');
    fireEvent.change(orderDate as HTMLInputElement, { target: { value: '2026-01-05' } });
    await user.type(office as HTMLInputElement, 'GM (Pay)');
    await user.upload(file as HTMLInputElement, [
      PDF,
      new File(['x'], 'second.pdf', { type: 'application/pdf' }),
    ]);
    expect(await within(card).findByText(t('wf.ord.oneFile'))).toBeInTheDocument();
    await user.upload(file as HTMLInputElement, PDF);
    expect(await within(card).findByText(t('wf.ord.held', ['order.pdf']))).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
    dialog = await raiseSupplementary(user);
    expect(within(dialog).getByText(t('i23.orderHeld', ['order.pdf']))).toBeInTheDocument();
    // chk_supp_sanction_after_appl: a same-day sanction is refused before sending.
    fireEvent.change(within(dialog).getByLabelText(t('i23.sanctionDate')), {
      target: { value: '2026-01-10' },
    });
    expect(within(dialog).getByText(t('error.SUP-422-SANCTION'))).toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText(t('i23.sanctionDate')), { target: { value: '' } });
    await user.click(within(dialog).getByRole('button', { name: t('i23.record') }));
    await screen.findByText(t('i23.recorded', ['SUP-00000012']));
    const form = calls.find((c) => c.method === 'POST' && c.url.endsWith('/v1/supplementary'))
      ?.body as FormData;
    expect(form.get('eis')).toBe('106004');
    expect(form.get('paymentType')).toBe('SALARY_WAGE');
    expect(form.get('sanctionDate')).toBeNull();
    expect(form.get('amount')).toBeNull();
    expect(form.get('issuingAuthority')).toBe('GM (Pay)');
    expect((form.get('file') as File).name).toBe('order.pdf');
  });

  it('D_5: statutory deductions not configured is reported on submission', async () => {
    backend({
      ...I23,
      'GET /v1/supplementary': () =>
        paged([{ ...PAYMENT, status: 'DRAFT', duplicateCheck: 'PASSED', duplicateOf: null }]),
      'POST /v1/supplementary/11/submit': () => problem(503, 'SUP-503-STATUTORY'),
    });
    wrap(<Supplementary />, ['I-23:VIEW', 'I-23:CREATE', 'I-23:SUBMIT']);
    const user = userEvent.setup();
    await user.click(
      within(await rowOf('SUP-00000011')).getByRole('button', { name: `${t('common.edit')} SUP-00000011` }),
    );
    await user.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submitForApproval') }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(/statutory deductions/i);
  });

  it('a draft is submitted and another cancelled with a reason from the invoice table; a dismissed reason sends nothing', async () => {
    const draft = { ...PAYMENT, status: 'DRAFT', duplicateCheck: 'PASSED' as const, duplicateOf: null };
    const calls = backend({
      ...I23,
      'GET /v1/supplementary': () => paged([draft, { ...draft, id: 12, reference: 'SUP-00000012' }]),
      'POST /v1/supplementary/11/submit': () => ({ ...draft, status: 'PENDING_CHECKER' }),
      'POST /v1/supplementary/12/cancel': () => ({ ...draft, status: 'CANCELLED' }),
    });
    wrap(<Supplementary />, ['I-23:VIEW', 'I-23:CREATE', 'I-23:SUBMIT']);
    const user = userEvent.setup();
    await user.click(
      within(await rowOf('SUP-00000011')).getByRole('button', { name: `${t('common.view')} SUP-00000011` }),
    );
    await user.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submitForApproval') }),
    );
    await screen.findByText(t('i23.submitted', ['SUP-00000011']));
    await user.click(within(invoiceRow('SUP-00000012')).getByRole('button', { name: t('wf.inv.cancel') }));
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    expect(calls.filter((c) => c.url.endsWith('/12/cancel'))).toHaveLength(0);
    await user.click(within(invoiceRow('SUP-00000012')).getByRole('button', { name: t('wf.inv.cancel') }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByRole('textbox'), 'Paid by I-20');
    await user.click(within(dialog).getAllByRole('button').at(-1) as HTMLElement);
    await screen.findByText(t('i23.cancelled', ['SUP-00000012']));
    expect(calls.find((c) => c.url.endsWith('/12/cancel'))?.body).toEqual({ reason: 'Paid by I-20' });
  });

  it('the selected request shows its stored order read-only; the checker approves in the inbox; finance exports the report; the Filter sends the type', async () => {
    const calls = backend({
      ...I23,
      'GET /v1/supplementary': () =>
        paged([{ ...PAYMENT, invoiceStatus: 'SENT_TO_APPS', invoiceId: 77, appsCancelStatus: 'REQUESTED' }]),
      'GET /v1/supplementary/11/orders': () => STORED,
    });
    wrap(<Supplementary />, ['I-23:VIEW', 'I-23:APPROVE', 'I-23:EXPORT']);
    const user = userEvent.setup();
    expect(await screen.findByRole('link', { name: t('i23.approveAct') })).toHaveAttribute(
      'href',
      '/payroll/?s=W-01',
    );
    expect(await exportedUrl('CSV', t('i23.reportAct'))).toBe(
      '/nginx/fci-payroll/v1/supplementary/export?format=CSV',
    );
    expect(await rowOf('SUP-00000011')).toHaveAttribute('aria-selected', 'true');
    expect(invoiceRow('SUP-00000011')).toHaveTextContent(t('inv.SENT_TO_APPS'));
    const card = (await screen.findByText('order_104882.pdf')).closest('.card') as HTMLElement;
    expect(within(card).getByText(t('wf.ord.viewOnly'))).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: t('wf.view') })).toHaveAttribute(
      'href',
      '/nginx/fci-payroll/v1/supplementary/11/files/3',
    );
    const raise = screen.getByRole('button', { name: t('i23.newAct') });
    expect(raise).toBeDisabled();
    expect(raise.getAttribute('data-why')).toBe(t('reason.makerOnly'));
    await user.click(screen.getByRole('button', { name: t('i23.filterAct') }));
    await user.selectOptions(screen.getByLabelText(t('i23.paymentType')), 'RECOVERY');
    await waitFor(() => expect(calls.some((c) => c.url.includes('paymentType=RECOVERY'))).toBe(true));
  });

  it('SRS-G-29: without EXPORT the drawn report says why; without APPROVE the Approve (OTP) is drawn locked', async () => {
    backend({ ...I23, 'GET /v1/supplementary': () => paged([PAYMENT]) });
    wrap(<Supplementary />, ['I-23:VIEW']);
    await rowOf('SUP-00000011');
    const approve = screen.getByRole('button', { name: t('i23.approveAct') });
    expect(approve).toBeDisabled();
    expect(approve.getAttribute('data-why')).toBe(t('i23.why.approve'));
    await userEvent.click(screen.getByRole('button', { name: t('i23.reportAct') }));
    expect(await screen.findByText(t('x05.why.export'))).toBeInTheDocument();
    expect(
      within(invoiceRow('SUP-00000011')).getByRole('button', { name: t('wf.inv.cancel') }),
    ).toHaveAttribute('data-why', t('wf.inv.why.maker'));
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

describe('X-06 Payroll ID label', () => {
  it('SRS_G_11: PR-period-staff number; the result id without a period; a dash without a Payroll ID', () => {
    expect(payrollIdLabel(QUERY)).toBe('PR-202607-105220');
    expect(payrollIdLabel({ ...QUERY, payrollYymm: null })).toBe('88');
    expect(payrollIdLabel({ ...QUERY, eis: null })).toBe('88');
    // DDL payroll_query.payroll_result_ref is nullable: such a query is listed, never a failed list.
    expect(payrollIdLabel({ ...QUERY, payrollId: null, payrollYymm: null })).toBe('—');
  });
});

describe('X-06 Payroll Query', () => {
  it('SRS_G_11: the register counts each status and shows the Payroll ID as PR-period-staff number', async () => {
    backend({
      'GET /v1/queries': queriesRoute([
        QUERY,
        { ...QUERY, id: 4, reference: 'QRY-00000004', status: 'OPEN', channel: 'ESEVA', makerResponse: null },
        { ...QUERY, id: 5, reference: 'QRY-00000005', status: 'CLOSED', resolvedBy: 'ddo.delhi' },
      ]),
    });
    wrap(<PayrollQueries />, ['X-06:VIEW']);
    const row = await rowOf('QRY-00000003');
    expect(within(row).getByText('PR-202607-105220')).toBeInTheDocument();
    expect(within(row).getByText('Staff - maker.delhi')).toBeInTheDocument();
    expect(within(await rowOf('QRY-00000004')).getByText('Member - Sourav Banerjee')).toBeInTheDocument();
    expect(within(await rowOf('QRY-00000005')).getByText('ddo.delhi')).toBeInTheDocument();
    const open = screen.getByText('Awaiting Maker investigation').closest('.tile') as HTMLElement;
    expect(open).toHaveTextContent('1');
    // A viewer has no step to take: Edit is disabled with the reason.
    expect(within(row).getByRole('button', { name: 'Next step on QRY-00000003' })).toBeDisabled();
    expect(screen.getByText('Lifecycle')).toBeInTheDocument();
    expect(screen.getByText('A query never computes')).toBeInTheDocument();
  });

  it('chk_query_checker: the Maker cannot confirm a computed-amount query; the Checker can', async () => {
    backend({ 'GET /v1/queries': queriesRoute([QUERY]) });
    const { unmount } = wrap(<PayrollQueries />, ['X-06:VIEW', 'X-06:EDIT']);
    const user = userEvent.setup();
    expect(
      within(await rowOf('QRY-00000003')).getByRole('button', { name: 'Next step on QRY-00000003' }),
    ).toBeDisabled();
    await user.click(within(await rowOf('QRY-00000003')).getByRole('button', { name: 'View QRY-00000003' }));
    expect(screen.queryByRole('button', { name: 'Confirm and resolve' })).toBeNull();
    expect(screen.getByText(/confirmed by the Payroll Checker who did not respond/)).toBeInTheDocument();
    unmount();

    const calls = backend({
      'GET /v1/queries': queriesRoute([QUERY]),
      'POST /v1/queries/3/resolve': () => ({ ...QUERY, status: 'CLOSED' }),
    });
    wrap(<PayrollQueries />, ['X-06:VIEW', 'X-06:APPROVE']);
    await user.click(
      within(await rowOf('QRY-00000003')).getByRole('button', { name: 'Next step on QRY-00000003' }),
    );
    const confirm = screen.getByRole('button', { name: 'Confirm and resolve' });
    expect(confirm).toBeDisabled();
    await user.type(screen.getByLabelText('Resolution'), 'Correct as computed');
    await user.click(confirm);
    await screen.findByText('✔ QRY-00000003 is now Closed.');
    expect(calls.find((c) => c.url.endsWith('/3/resolve'))?.body).toEqual({
      resolution: 'Correct as computed',
    });
  });

  it('SRS_G_11: raising needs a Payroll ID and English text; the drawn Filter narrows the list by status (database list); no export is drawn', async () => {
    const calls = backend({
      'GET /v1/reference/lists': () =>
        referenceLists({
          PAYROLL_QUERY_STATUS: [
            ['OPEN', 'Open'],
            ['IN_PROGRESS', 'In Progress'],
            ['RESOLVED', 'Resolved'],
            ['CLOSED', 'Closed'],
          ],
        }),
      'GET /v1/queries': queriesRoute([
        QUERY,
        { ...QUERY, id: 5, reference: 'QRY-00000005', status: 'CLOSED' },
      ]),
      'POST /v1/queries': () => ({ ...QUERY, status: 'OPEN', reference: 'QRY-00000004' }),
    });
    wrap(<PayrollQueries />, ['X-06:VIEW', 'X-06:CREATE', 'X-06:EXPORT']);
    const user = userEvent.setup();
    await rowOf('QRY-00000003');
    await user.click(screen.getByRole('button', { name: t('x06.filterAct') }));
    await waitFor(() =>
      expect((screen.getByLabelText('Status') as HTMLSelectElement).options.length).toBe(5),
    );
    await user.selectOptions(screen.getByLabelText('Status'), 'CLOSED');
    await waitFor(() => expect(document.querySelector('tr[data-reference="QRY-00000003"]')).toBeNull());
    expect(calls.some((c) => c.url.includes('status=CLOSED'))).toBe(true);
    // The pack draws no export on X-06 (OD-121): none is offered, even with EXPORT.
    expect(screen.queryByRole('button', { name: t('action.export') })).toBeNull();
    await user.click(screen.getByRole('button', { name: t('x06.raiseAct') }));
    await user.type(screen.getByLabelText('Payroll ID'), '88');
    await user.type(screen.getByLabelText('Query'), 'वेतन');
    const raise = screen.getAllByRole('button', { name: 'Raise a query' }).at(-1) as HTMLElement;
    expect(raise).toBeDisabled();
    await user.clear(screen.getByLabelText('Query'));
    await user.type(screen.getByLabelText('Query'), 'Arrear missing');
    await user.click(raise);
    await screen.findByText('✔ Query QRY-00000004 raised.');
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({
      payrollId: 88,
      queryText: 'Arrear missing',
      concernsComputedAmount: false,
    });
  });

  it('the Maker records a response to an open query', async () => {
    const calls = backend({
      'GET /v1/queries': queriesRoute([
        { ...QUERY, status: 'OPEN', makerResponse: null, concernsComputedAmount: false },
      ]),
      'POST /v1/queries/3/respond': () => ({ ...QUERY, status: 'IN_PROGRESS' }),
    });
    wrap(<PayrollQueries />, ['X-06:VIEW', 'X-06:EDIT']);
    const user = userEvent.setup();
    await user.click(
      within(await rowOf('QRY-00000003')).getByRole('button', { name: 'Next step on QRY-00000003' }),
    );
    await user.click(screen.getByLabelText('Concerns a computed amount'));
    await user.type(screen.getByLabelText('Maker response'), 'City class changed in June');
    await user.click(screen.getByRole('button', { name: 'Record response' }));
    await screen.findByText('✔ QRY-00000003 is now In progress.');
    expect(calls.find((c) => c.url.endsWith('/3/respond'))?.body).toEqual({
      response: 'City class changed in June',
      concernsComputedAmount: true,
    });
  });

  it('SRS_22_5: a query raised about a computed amount keeps that mark when the Maker answers', async () => {
    backend({ 'GET /v1/queries': queriesRoute([{ ...QUERY, status: 'OPEN', makerResponse: null }]) });
    wrap(<PayrollQueries />, ['X-06:VIEW', 'X-06:EDIT']);
    await userEvent
      .setup()
      .click(within(await rowOf('QRY-00000003')).getByRole('button', { name: 'Next step on QRY-00000003' }));
    const flag = screen.getByLabelText('Concerns a computed amount');
    expect(flag).toBeChecked();
    expect(flag).toBeDisabled();
    expect(flag).toHaveAttribute('title', t('error.QRY-422-COMPUTED'));
  });

  it('a refused raise shows the server message', async () => {
    backend({
      'GET /v1/queries': queriesRoute([]),
      'POST /v1/queries': () => problem(422, 'QRY-422-PAYROLL-ID'),
    });
    wrap(<PayrollQueries />, ['X-06:VIEW', 'X-06:CREATE']);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: t('x06.raiseAct') }));
    await user.type(screen.getByLabelText('Payroll ID'), '987654321');
    await user.type(screen.getByLabelText('Query'), 'Where is it?');
    await user.click(screen.getAllByRole('button', { name: 'Raise a query' }).at(-1) as HTMLElement);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'No payroll record of a run of record has this Payroll ID.',
    );
  });
});

// ---------------------------------------------------------------- X-05

const ITEM: ExceptionItem = {
  id: 21,
  cycleInstanceId: null,
  eis: '105220',
  memberName: 'Sourav Banerjee',
  moduleCode: 'C-02',
  exceptionCode: 'MISSING_BANK',
  severity: 'WARNING',
  blocksGate: 'G2',
  entityName: 'employee',
  fieldName: 'bank_account',
  fieldSource: 'ENT',
  resolvableInPayroll: true,
  correctionScreen: 'M-01',
  ownerRoleCode: null,
  detail: 'Bank account missing',
  status: 'OPEN',
  acceptedBy: null,
  acceptedReason: null,
  raisedAt: '2026-10-20T05:00:00Z',
  ageDays: 1,
  resolvedAt: null,
};

/** The filters of X-05 open from its Filter button (as drawn). */
async function openX05Filters() {
  // The last rendered worklist (a test may mount a second one before unmounting the first).
  const buttons = screen.getAllByRole('button', { name: t('x05.filter') });
  await userEvent.click(buttons[buttons.length - 1] as HTMLElement);
}

describe('X-05 Exceptions', () => {
  it('SRS_G_9: the worklist counts members per condition; a blocking item cannot be accepted', async () => {
    const calls = backend({
      ...x05Routes({
        tiles: { open: 3, blockingG2: 1, acceptedWithReason: 0, upstream: 0 },
        items: [
          ITEM,
          { ...ITEM, id: 23, eis: '104882' },
          { ...ITEM, id: 22, exceptionCode: 'NO_PAN', severity: 'BLOCKING' },
        ],
      }),
      'POST /v1/exceptions/21/accept': () => ({ ...ITEM, status: 'ACCEPTED' }),
      'POST /v1/exceptions/21/assign': () => ({ ...ITEM, status: 'IN_PROGRESS' }),
    });
    wrap(<ExceptionsWorklist />, ['X-05:VIEW', 'X-05:EDIT', 'X-05:APPROVE', 'X-05:EXPORT']);
    await openX05Filters();
    const bank = (await screen.findByText('MISSING_BANK')).closest('tr') as HTMLElement;
    expect(within(bank).getByText('2')).toBeInTheDocument();
    expect(within(bank).getByText('No')).toBeInTheDocument();
    const pan = screen.getByText('NO_PAN').closest('tr') as HTMLElement;
    expect(within(pan).getByText('Yes')).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(within(pan).getByRole('button', { name: 'Act on NO_PAN' }));
    expect(screen.getByRole('button', { name: 'Accept…' })).toBeDisabled();
    await user.click(within(bank).getByRole('button', { name: 'Act on MISSING_BANK' }));
    const item = document.querySelector('tr[data-item="21"]') as HTMLElement;
    await user.selectOptions(within(item).getByLabelText('Owner of MISSING_BANK'), 'ESTABLISHMENT_HR');
    await waitFor(() =>
      expect(calls.find((c) => c.url.endsWith('/21/assign'))?.body).toEqual({ owner: 'ESTABLISHMENT_HR' }),
    );
    await user.click(within(item).getByRole('button', { name: 'Accept…' }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByRole('textbox'), 'Paid by cheque this month');
    await user.click(within(dialog).getAllByRole('button').at(-1) as HTMLElement);
    await waitFor(() =>
      expect(calls.find((c) => c.url.endsWith('/21/accept'))?.body).toEqual({
        reason: 'Paid by cheque this month',
      }),
    );
    expect(await exportedUrl()).toBe('/nginx/fci-payroll/v1/exceptions/export?format=CSV');
  });

  it('SRS_15_14: each condition shows where it is fixed; its items show severity, field and field source (C-08 fields)', async () => {
    backend({
      ...x05Routes({
        tiles: { open: 1, blockingG2: 0, acceptedWithReason: 0, upstream: 0 },
        items: [ITEM],
      }),
    });
    wrap(<ExceptionsWorklist />, ['X-05:VIEW', 'X-05:EDIT']);
    await openX05Filters();
    const bank = (await screen.findByText('MISSING_BANK')).closest('tr') as HTMLElement;
    // Fix where: the correction screen of the condition (C-08 "Fix Where").
    expect(within(bank).getByText(/M-01/)).toBeInTheDocument();
    await userEvent.setup().click(within(bank).getByRole('button', { name: 'Act on MISSING_BANK' }));
    const item = document.querySelector('tr[data-item="21"]') as HTMLElement;
    expect(within(item).getByText(t('x05.severity.WARNING'))).toBeInTheDocument();
    expect(within(item).getByText(/bank_account/)).toBeInTheDocument();
    expect(within(item).getByText(t('x05.source', ['ENT']))).toBeInTheDocument();
  });

  it('SRS_EH_4: an HRMS value is routed upstream; its items are viewed, never acted on', async () => {
    backend({
      ...x05Routes({
        tiles: { open: 0, blockingG2: 0, acceptedWithReason: 0, upstream: 1 },
        items: [
          {
            ...ITEM,
            status: 'ROUTED_UPSTREAM',
            resolvableInPayroll: false,
            ownerRoleCode: 'HRMS',
            eis: null,
          },
        ],
      }),
    });
    wrap(<ExceptionsWorklist />, ['X-05:VIEW', 'X-05:EDIT', 'X-05:APPROVE']);
    await openX05Filters();
    const row = (await screen.findByText('MISSING_BANK')).closest('tr') as HTMLElement;
    expect(within(row).getByText('Corrected in HRMS, not in Payroll')).toBeInTheDocument();
    expect(within(row).getByRole('button', { name: 'Act on MISSING_BANK' })).toBeDisabled();
    await userEvent.setup().click(within(row).getByRole('button', { name: 'View MISSING_BANK' }));
    expect(screen.getByText('Whole cycle')).toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: /Owner of/ })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Export CSV' })).toBeNull();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Close' }));
    expect(screen.queryByText('Whole cycle')).toBeNull();
  });

  it('SRS_G_9: a blocking item routed upstream still holds gate G2 until the corrected value arrives', async () => {
    backend({
      ...x05Routes({
        tiles: { open: 0, blockingG2: 1, acceptedWithReason: 0, upstream: 1 },
        items: [
          {
            ...ITEM,
            severity: 'BLOCKING',
            status: 'ROUTED_UPSTREAM',
            resolvableInPayroll: false,
            ownerRoleCode: 'HRMS',
          },
        ],
      }),
    });
    wrap(<ExceptionsWorklist />, ['X-05:VIEW']);
    await openX05Filters();
    const row = (await screen.findByText('MISSING_BANK')).closest('tr') as HTMLElement;
    expect(within(row).getByText(t('x05.yes'))).toBeInTheDocument();
    expect(within(row).queryByText(t('x05.no'))).toBeNull();
  });

  it('SRS l.1643 (C-08): opened from C-02 with the run, the list holds that run only until the filter is removed', async () => {
    window.history.pushState({}, '', '/payroll/?s=X-05&period=2026-10&cycle=5');
    const calls = backend({
      ...x05Routes({ tiles: { open: 1, blockingG2: 0, acceptedWithReason: 0, upstream: 0 }, items: [ITEM] }),
    });
    wrap(<ExceptionsWorklist />, ['X-05:VIEW']);
    await openX05Filters();
    expect(await screen.findByText(t('x05.c08', ['2026-10']))).toBeInTheDocument();
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('/v1/exceptions/groups') && c.url.includes('cycle=5'))).toBe(
        true,
      ),
    );
    expect(screen.getByLabelText(t('x05.period'))).toHaveValue('2026-10');
    await userEvent.click(screen.getByRole('button', { name: t('x05.c08.clear') }));
    expect(screen.queryByText(t('x05.c08', ['2026-10']))).toBeNull();
    // A malformed run or period in the address is ignored, never sent.
    window.history.pushState({}, '', '/payroll/?s=X-05&period=10-2026&cycle=abc');
    const later = backend({
      ...x05Routes({ tiles: { open: 0, blockingG2: 0, acceptedWithReason: 0, upstream: 0 }, items: [] }),
    });
    wrap(<ExceptionsWorklist />, ['X-05:VIEW']);
    await openX05Filters();
    expect(await screen.findAllByText('No exception matches.')).not.toHaveLength(0);
    expect(later.some((c) => c.url.includes('cycle='))).toBe(false);
    window.history.pushState({}, '', '/');
  });

  it('filters reach the worklist and the export link', async () => {
    const calls = backend({
      ...x05Routes({
        tiles: { open: 0, blockingG2: 0, acceptedWithReason: 0, upstream: 0 },
        items: [],
      }),
    });
    wrap(<ExceptionsWorklist />, ['X-05:VIEW', 'X-05:EXPORT']);
    await openX05Filters();
    const user = userEvent.setup();
    expect(await screen.findByText('No exception matches.')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Status'), 'OPEN');
    await user.selectOptions(screen.getByLabelText('Severity'), 'BLOCKING');
    await waitFor(() =>
      expect(
        calls.some((c) =>
          c.url.includes('/v1/exceptions/groups?status=OPEN&severity=BLOCKING&page=0&size=20'),
        ),
      ).toBe(true),
    );
    expect(await exportedUrl()).toBe(
      '/nginx/fci-payroll/v1/exceptions/export?status=OPEN&severity=BLOCKING&format=CSV',
    );
  });
  // UI_BMAD_g1 #5 (SRS l.627-630 "All modules, all engines"): the status, severity and class
  // filters narrow the rows, never the tiles; the period and office narrow both.
  it('SRS_G_9: the tiles ignore the status, severity and class filters', async () => {
    const calls = backend({
      ...x05Routes({
        tiles: { open: 7, blockingG2: 2, acceptedWithReason: 1, upstream: 1 },
        items: [ITEM],
        modules: ['C-02', 'I-01'],
      }),
    });
    wrap(<ExceptionsWorklist />, ['X-05:VIEW']);
    await openX05Filters();
    const user = userEvent.setup();
    await screen.findByText('MISSING_BANK');
    await user.selectOptions(screen.getByLabelText('Status'), 'ACCEPTED');
    await user.selectOptions(screen.getByLabelText(t('x05.class')), 'I-01');
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('/v1/exceptions/groups?status=ACCEPTED&module=I-01'))).toBe(
        true,
      ),
    );
    const tiles = calls.filter((c) => c.url.includes('/v1/exceptions/groups?page=0&size=10'));
    expect(tiles.length).toBeGreaterThan(0);
    expect(calls.some((c) => c.url.includes('size=10') && c.url.includes('status='))).toBe(false);
    fireEvent.change(screen.getByLabelText(t('x05.period')), { target: { value: '2026-10' } });
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('/v1/exceptions/groups?period=2026-10&page=0&size=10'))).toBe(
        true,
      ),
    );
  });

  // UI_BMAD_g1 #4 (SRS l.636): an upstream item is owned by "HRMS (corrected at source)".
  it('SRS_EH_4: an upstream condition shows HRMS as its owner; a Payroll one without owner a dash', async () => {
    backend({
      ...x05Routes({
        tiles: { open: 2, blockingG2: 0, acceptedWithReason: 0, upstream: 1 },
        items: [
          { ...ITEM, id: 41, exceptionCode: 'HRMS_DOB', resolvableInPayroll: false, fieldSource: 'HRMS' },
          { ...ITEM, id: 42 },
        ],
      }),
    });
    wrap(<ExceptionsWorklist />, ['X-05:VIEW']);
    await openX05Filters();
    const upstream = (await screen.findByText('HRMS_DOB')).closest('tr') as HTMLElement;
    expect(within(upstream).getByText(t('x05.owner.hrms'))).toBeInTheDocument();
    const bank = screen.getByText('MISSING_BANK').closest('tr') as HTMLElement;
    expect(within(bank).queryByText(t('x05.owner.hrms'))).toBeNull();
  });

  it("SRS_G_9: the count is the server's over the whole scope, not the rows of one page", async () => {
    // 25 members affected; only the first page of items (20) would be in the browser.
    const items = Array.from({ length: 20 }, (_, i) => ({ ...ITEM, id: 100 + i, eis: String(200000 + i) }));
    backend({
      ...x05Routes({
        tiles: { open: 25, blockingG2: 0, acceptedWithReason: 0, upstream: 0 },
        items,
        members: { MISSING_BANK: 25 },
      }),
    });
    wrap(<ExceptionsWorklist />, ['X-05:VIEW']);
    await openX05Filters();
    expect(await screen.findByTestId('x05-count-MISSING_BANK')).toHaveTextContent('25');
    // One row for the condition, however many items it has.
    expect(screen.getAllByText('MISSING_BANK')).toHaveLength(1);
  });

  it('SRS_G_9: a cycle-level condition counts its items; a member count shows its item count beside it', async () => {
    backend({
      ...x05Routes({
        tiles: { open: 3, blockingG2: 0, acceptedWithReason: 0, upstream: 0 },
        items: [
          { ...ITEM, id: 31, exceptionCode: 'CUTOFF', eis: null },
          { ...ITEM, id: 32, exceptionCode: 'CUTOFF', eis: null },
          { ...ITEM, id: 33 },
          { ...ITEM, id: 34, fieldName: 'ifsc' },
        ],
      }),
    });
    wrap(<ExceptionsWorklist />, ['X-05:VIEW']);
    await openX05Filters();
    expect(await screen.findByTestId('x05-count-CUTOFF')).toHaveTextContent('2');
    const bank = screen.getByTestId('x05-count-MISSING_BANK');
    expect(bank).toHaveTextContent('1');
    expect(bank).toHaveTextContent(t('x05.itemCount', [2]));
  });

  it('SRS_G_9: class, period and office filters reach the worklist and the export; a new filter closes the opened condition', async () => {
    const calls = backend({
      ...x05Routes({
        tiles: { open: 1, blockingG2: 0, acceptedWithReason: 0, upstream: 0 },
        items: [ITEM],
        modules: ['C-02', 'I-01'],
      }),
    });
    wrap(<ExceptionsWorklist />, ['X-05:VIEW', 'X-05:EXPORT']);
    await openX05Filters();
    const user = userEvent.setup();
    const bank = (await screen.findByText('MISSING_BANK')).closest('tr') as HTMLElement;
    await user.click(within(bank).getByRole('button', { name: 'View MISSING_BANK' }));
    // The opened condition's items are read by its code (a paged list of their own).
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('/v1/exceptions?module=C-02&code=MISSING_BANK&page=0'))).toBe(
        true,
      ),
    );
    expect(await screen.findByText(t('x05.itemsOf', ['MISSING_BANK']))).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText(t('x05.class')), 'I-01');
    expect(screen.queryByText(t('x05.itemsOf', ['MISSING_BANK']))).toBeNull();
    fireEvent.change(screen.getByLabelText(t('x05.period')), { target: { value: '2026-10' } });
    await user.selectOptions(await screen.findByLabelText(t('x05.office')), '12');
    await waitFor(() =>
      expect(
        calls.some((c) =>
          c.url.includes('/v1/exceptions/groups?module=I-01&period=2026-10&office=12&page=0&size=20'),
        ),
      ).toBe(true),
    );
    expect(await exportedUrl()).toBe(
      '/nginx/fci-payroll/v1/exceptions/export?module=I-01&period=2026-10&office=12&format=CSV',
    );
    // Clearing a filter sends no empty parameter.
    fireEvent.change(screen.getByLabelText(t('x05.period')), { target: { value: '' } });
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('/v1/exceptions/groups?module=I-01&office=12&page=0'))).toBe(
        true,
      ),
    );
  });

  it('SRS_G_9: a refused office list is shown, the worklist still opens', async () => {
    backend({
      ...x05Routes({ tiles: { open: 1, blockingG2: 0, acceptedWithReason: 0, upstream: 0 }, items: [ITEM] }),
      'GET /v1/exceptions/offices': () => problem(403, 'COM-403'),
    });
    wrap(<ExceptionsWorklist />, ['X-05:VIEW']);
    await openX05Filters();
    expect(await screen.findByText('MISSING_BANK')).toBeInTheDocument();
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });
});

// ---------------------------------------------------------------- X-07

const ENABLEMENT: Enablement = {
  id: 4,
  reference: 'FBE-2026-001',
  type: 'SUSPENSION_ABSCONDING',
  payrollYymm: '202610',
  offices: ['DH001'],
  reason: 'HRMS down',
  enabledBy: 'sysadmin',
  enabledAt: '2026-10-01T05:00:00Z',
  status: 'OPEN',
  withdrawnReason: null,
  officeNames: ['DH001 - Delhi HQ'],
  hrmsRestoredAt: null,
};

const UPLOAD: FallbackUpload = {
  id: 9,
  batchNo: 'FBU-1',
  enablementId: 4,
  type: 'SUSPENSION_ABSCONDING',
  office: 'DH001',
  payrollYymm: '202610',
  fileName: 'export.csv',
  recordCount: 3,
  acceptedCount: 2,
  rejectedCount: 1,
  uploadedBy: 'maker.delhi',
  uploadedAt: '2026-10-02T05:00:00Z',
  status: 'VALIDATED',
  checkerUser: null,
  decisionReason: null,
  reconStatus: 'NOT_DUE',
  orderCopies: 0,
  sourceAttachmentId: 31,
  approvalId: null,
  enablementReference: 'FBE-2026-001',
};

const LINES = [
  {
    lineNo: 1,
    employeeNumber: '105220',
    validationStatus: 'ACCEPTED',
    rejectionReason: null,
    reconStatus: 'NOT_DUE',
  },
  {
    lineNo: 2,
    employeeNumber: '999999',
    validationStatus: 'REJECTED',
    rejectionReason: 'EMPLOYEE_NOT_FOUND: not on the Payroll master',
    reconStatus: 'NOT_DUE',
  },
];

/** The upload register and the withdrawals open from the drawn History button (WF-198). */
async function openHistory(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: t('x07.w.history') }));
}

const RECON_ROWS = [
  {
    reference: 'FBE-2026-005',
    payrollYymm: '202606',
    employeeNumber: '103884',
    employeeName: 'N. Patil',
    others: 0,
    item: 'LWP_DAYS',
    uploaded: '2',
    hrms: '3',
    effect: 'RECOVERY',
    difference: '1',
    nextCycleYymm: '202607',
    status: 'DIFFERENCE',
  },
  {
    reference: 'FBE-2026-005',
    payrollYymm: '202606',
    employeeNumber: '105512',
    employeeName: 'J. Sen',
    others: 0,
    item: 'HALF_PAY_DAYS',
    uploaded: '4',
    hrms: '2',
    effect: 'ARREAR',
    difference: '2',
    nextCycleYymm: '202607',
    status: 'DIFFERENCE',
  },
  {
    reference: 'FBE-2026-005',
    payrollYymm: '202606',
    employeeNumber: null,
    employeeName: null,
    others: 4086,
    item: 'ALL',
    uploaded: null,
    hrms: null,
    effect: 'NONE',
    difference: null,
    nextCycleYymm: null,
    status: 'CLOSED',
  },
  {
    reference: 'FBE-2026-006',
    payrollYymm: '202607',
    employeeNumber: '101774',
    employeeName: 'J. Paul',
    others: 0,
    item: 'REVOCATION',
    uploaded: '2026-07-01',
    hrms: '2026-07-01',
    effect: 'NONE',
    difference: null,
    nextCycleYymm: null,
    status: 'MATCHED',
  },
  {
    reference: 'FBE-2026-006',
    payrollYymm: '202607',
    employeeNumber: '101775',
    employeeName: null,
    others: 0,
    item: 'SUSPENSION',
    uploaded: null,
    hrms: null,
    effect: 'ARREAR',
    difference: null,
    nextCycleYymm: '202608',
    status: 'NOT_IN_HRMS',
  },
];

describe('X-07 HRMS Fallback', () => {
  it('OD_121: every object is drawn for the role, each locked action with the reason of the pack', async () => {
    backend(fallback([ENABLEMENT], [UPLOAD]));
    wrap(<HrmsFallback />, ['X-07:VIEW']);
    const locked = (name: string) => screen.getByRole('button', { name });
    await screen.findByText(t('x07.w.enablements'));
    expect(locked(t('x07.w.act.enable'))).toBeDisabled();
    expect(locked(t('x07.w.act.enable'))).toHaveAttribute('data-why', t('x07.w.why.enable'));
    expect(locked(t('x07.w.act.upload'))).toHaveAttribute('data-why', t('x07.w.why.upload'));
    screen
      .getAllByRole('button', { name: t('x07.w.act.approve') })
      .forEach((b) => expect(b).toHaveAttribute('data-why', t('x07.w.why.approve')));
    expect(screen.getAllByRole('button', { name: t('x07.w.act.approve') })).toHaveLength(2);
    expect(screen.getByRole('button', { name: t('x07.w.act.recon') })).toBeEnabled();
    for (const heading of [t('x07.w.what'), t('x07.recon'), t('wf.ord.title')]) {
      expect(screen.getByText(heading)).toBeInTheDocument();
    }
    for (const text of [t('x07.what.a'), t('x07.what.b'), t('x07.what.none'), t('x07.w.whatLabourSub')]) {
      expect(screen.getByText(text)).toBeInTheDocument();
    }
    expect(
      await screen.findByText(
        t('x07.w.detail', ['FBE-2026-001', 'DH001', t('x07.w.type.SUSPENSION_ABSCONDING')]),
      ),
    ).toBeInTheDocument();
    // Nothing of the register is on the screen until History is pressed.
    expect(document.querySelector('tr[data-reference="FBU-1"]')).toBeNull();
    const row = await rowOf('FBE-2026-001');
    expect(row).toHaveTextContent('DH001 - Delhi HQ');
    expect(within(row).getByText(t('x07.w.enablement.INTERIM'))).toBeInTheDocument();
  });

  it('BR_INT_011: fallback (b) needs its order before submission; the detail shows each record and the source file', async () => {
    backend({
      ...fallback([ENABLEMENT], [UPLOAD]),
      'GET /v1/hrms-fallback/uploads/9/lines': () => paged(LINES),
      'GET /v1/hrms-fallback/uploads/9/rejections': () => [
        { reason: 'employee not on Payroll master', count: 9 },
        { reason: 'leave dates overlap', count: 5 },
      ],
    });
    wrap(<HrmsFallback />, ['X-07:VIEW', 'X-07:SUBMIT']);
    const user = userEvent.setup();
    await openHistory(user);
    const row = await rowOf('FBU-1');
    const submit = within(row).getByRole('button', { name: 'Submit for approval' });
    expect(submit).toBeDisabled();
    expect(submit).toHaveAttribute('title', 'Attach the order copy first.');
    await user.click(within(row).getByRole('button', { name: 'Records' }));
    expect(await screen.findByText('EMPLOYEE_NOT_FOUND: not on the Payroll master')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: t('x07.w.sourceKept') })).toHaveAttribute(
      'href',
      '/nginx/fci-payroll/v1/hrms-fallback/uploads/9/files/31',
    );
    expect(
      await screen.findByText(
        '2 passed · 1 rejected (employee not on Payroll master 9; leave dates overlap 5)',
      ),
    ).toBeInTheDocument();
    // UI_BMAD_g1 #15: on the register row (SRS l.882 Reconciliation Status).
    expect(within(row).getByText('Reconciliation not due')).toBeInTheDocument();
    expect(screen.getByText(t('x07.w.orderHint'))).toBeInTheDocument();
  });

  it('SRS_EH_11: without an open enablement the upload form says HRMS data comes through the interface', async () => {
    backend({
      ...fallback([{ ...ENABLEMENT, status: 'WITHDRAWN' }], []),
    });
    wrap(<HrmsFallback />, ['X-07:VIEW', 'X-07:SUBMIT']);
    await userEvent.setup().click(await screen.findByRole('button', { name: t('x07.w.act.upload') }));
    expect(await screen.findByText(/No fallback upload is enabled for your office/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Upload and validate' })).toBeDisabled();
  });

  it('the upload is sent as multipart with its enablement and its detail opens', async () => {
    const calls = backend({
      ...fallback([ENABLEMENT], [UPLOAD]),
      'GET /v1/hrms-fallback/uploads/9/lines': () => paged(LINES),
      'POST /v1/hrms-fallback/uploads': () => UPLOAD,
    });
    wrap(<HrmsFallback />, ['X-07:VIEW', 'X-07:SUBMIT']);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: t('x07.w.act.upload') }));
    await user.selectOptions(await screen.findByLabelText('Enablement'), '4');
    await user.upload(
      screen.getByLabelText('HRMS export file'),
      new File(['EMP_NO\n1'], 'export.csv', { type: 'text/csv' }),
    );
    await user.click(screen.getByRole('button', { name: 'Upload and validate' }));
    await screen.findByText('✔ FBU-1: 2 accepted, 1 rejected.');
    const post = calls.find((c) => c.method === 'POST');
    expect(post?.url).toContain('/v1/hrms-fallback/uploads?enablementId=4');
    expect(((post?.body as FormData).get('file') as File).name).toBe('export.csv');
    expect(
      await screen.findByText(
        t('x07.w.detail', ['FBE-2026-001', 'DH001', t('x07.w.type.SUSPENSION_ABSCONDING')]),
      ),
    ).toBeInTheDocument();
  });

  it('SRS_G_47: Headquarters proposes an enablement for named offices; it goes to approval', async () => {
    const calls = backend({
      ...fallback([], []),
      'POST /v1/hrms-fallback/enablements': () => ({ approvalId: 77 }),
    });
    wrap(<HrmsFallback />, ['X-07:VIEW', 'X-07:CREATE']);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: t('x07.w.act.enable') }));
    // OD-123: the upload types come from the reference list.
    await screen.findByRole('option', { name: '(a) Attendance & leave' });
    await user.selectOptions(screen.getByLabelText('Fallback'), 'ATTENDANCE_LEAVE');
    const month = screen.getByLabelText('Payroll month') as HTMLSelectElement;
    await user.selectOptions(month, month.options[1]?.value ?? '');
    await user.type(screen.getByLabelText('Offices'), 'dh001, mr001');
    await user.type(screen.getByLabelText('Reason'), 'HRMS down at cut-off');
    await user.click(screen.getByRole('button', { name: 'Submit for approval' }));
    await screen.findByText('✔ Sent for approval (item 77).');
    expect(calls.find((c) => c.method === 'POST')?.body).toMatchObject({
      type: 'ATTENDANCE_LEAVE',
      offices: ['DH001', 'MR001'],
      reason: 'HRMS down at cut-off',
    });
    expect(screen.getByRole('button', { name: t('x07.w.act.upload') })).toBeDisabled();
  });

  it('the order copy is attached, then the upload goes to the Checker', async () => {
    const calls = backend({
      ...fallback([ENABLEMENT], [{ ...UPLOAD, orderCopies: 1 }]),
      'GET /v1/hrms-fallback/uploads/9/lines': () => paged(LINES),
      'GET /v1/hrms-fallback/uploads/9/orders': () => STORED,
      'POST /v1/hrms-fallback/uploads/9/order': () => ({ id: 3, fileName: 'order.pdf', status: 'ACTIVE' }),
      'POST /v1/hrms-fallback/uploads/9/submit': () => ({ ...UPLOAD, status: 'PENDING_APPROVAL' }),
    });
    wrap(<HrmsFallback />, ['X-07:VIEW', 'X-07:SUBMIT']);
    const user = userEvent.setup();
    await screen.findByText(t('x07.w.fileValue', ['export.csv']));
    const card = await orderCard();
    expect(await within(card).findByText('order_104882.pdf')).toBeInTheDocument();
    await waitFor(() =>
      expect((card.querySelector('select') as HTMLSelectElement).options.length).toBeGreaterThan(0),
    );
    await pickOrder(card);
    await screen.findByText('✔ Order attached.');
    const post = calls.find((c) => c.url.endsWith('/uploads/9/order'));
    expect((post?.body as FormData).get('issuingAuthority')).toBe('GM (Pay)');
    await user.click(screen.getByRole('button', { name: 'Submit for approval' }));
    await screen.findByText('✔ Upload FBU-1 sent to the Payroll Checker.');
  });

  it('an upload is cancelled and an enablement withdrawn, each with a reason', async () => {
    const calls = backend({
      ...fallback([ENABLEMENT], [UPLOAD]),
      'POST /v1/hrms-fallback/uploads/9/cancel': () => ({ ...UPLOAD, status: 'CANCELLED' }),
      'POST /v1/hrms-fallback/enablements/4/withdraw': () => ({ ...ENABLEMENT, status: 'WITHDRAWN' }),
    });
    wrap(<HrmsFallback />, ['X-07:VIEW', 'X-07:CREATE', 'X-07:SUBMIT']);
    const user = userEvent.setup();
    await openHistory(user);
    await user.click(within(await rowOf('FBU-1')).getByRole('button', { name: 'Cancel…' }));
    let dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByRole('textbox'), 'Wrong office file');
    await user.click(within(dialog).getAllByRole('button').at(-1) as HTMLElement);
    await screen.findByText('✔ Upload FBU-1 cancelled.');
    await user.click(within(await liveRow('FBE-2026-001')).getByRole('button', { name: 'Withdraw…' }));
    dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByRole('textbox'), 'HRMS is back');
    await user.click(within(dialog).getAllByRole('button').at(-1) as HTMLElement);
    await screen.findByText('✔ Enablement FBE-2026-001 withdrawn.');
    expect(calls.find((c) => c.url.endsWith('/4/withdraw'))?.body).toEqual({ reason: 'HRMS is back' });
  });

  it('the Maker cancels the validated upload from the Upload card, with a reason', async () => {
    const calls = backend({
      ...fallback([ENABLEMENT], [UPLOAD]),
      'POST /v1/hrms-fallback/uploads/9/cancel': () => ({ ...UPLOAD, status: 'CANCELLED' }),
    });
    wrap(<HrmsFallback />, ['X-07:VIEW', 'X-07:SUBMIT']);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Cancel…' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
    await user.click(screen.getByRole('button', { name: 'Cancel…' }));
    const again = await screen.findByRole('dialog');
    await user.type(within(again).getByRole('textbox'), 'Wrong office file');
    await user.click(within(again).getAllByRole('button').at(-1) as HTMLElement);
    await screen.findByText('✔ Upload FBU-1 cancelled.');
  });

  it('a viewer sees read-only uploads; the tiles count enablements, pending and to-reconcile uploads', async () => {
    backend({
      ...fallback(
        [
          { ...ENABLEMENT, status: 'UPLOADED' },
          { ...ENABLEMENT, id: 5, reference: 'FBE-2026-002', type: 'ATTENDANCE_LEAVE' },
        ],
        [
          { ...UPLOAD, status: 'APPROVED', reconStatus: 'PENDING' },
          { ...UPLOAD, id: 10, batchNo: 'FBU-2', status: 'PENDING_APPROVAL' },
        ],
      ),
      'POST /v1/hrms-fallback/enablements/4/withdraw': () => problem(409, 'HFB-409-STATUS', ['APPROVED']),
    });
    wrap(<HrmsFallback />, ['X-07:VIEW', 'X-07:CREATE', 'X-07:APPROVE', 'X-07:EXPORT']);
    const user = userEvent.setup();
    await openHistory(user);
    const row = await rowOf('FBU-1');
    expect(
      within(row)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Records']);
    expect(screen.getByText('Uploads enabled').closest('.tile') as HTMLElement).toHaveTextContent('2');
    expect(screen.getByText('Uploads enabled').closest('.tile') as HTMLElement).toHaveTextContent(
      'OCT cycle · 1 attendance & leave · 1 suspension',
    );
    const awaiting = screen.getByText('Awaiting Checker (OTP)').closest('.tile') as HTMLElement;
    await waitFor(() => expect(awaiting).toHaveTextContent('1'));
    expect(awaiting).toHaveTextContent(`DH001 — ${t('x07.w.type.SUSPENSION_ABSCONDING')}`);
    expect(screen.getByText('To reconcile with HRMS').closest('.tile') as HTMLElement).toHaveTextContent('1');
    expect(screen.getByRole('button', { name: t('x07.w.act.recon') })).toBeEnabled();
    const card = document.querySelector('.card .frs')?.closest('.card') as HTMLElement;
    await user.click(within(card).getByRole('button', { name: t('action.export') }));
    const exporting = screen.getByRole('dialog');
    await user.click(exporting.querySelector('input[value="CSV"]') as HTMLElement);
    expect(within(exporting).getByRole('link', { name: t('export.download') })).toHaveAttribute(
      'href',
      '/nginx/fci-payroll/v1/hrms-fallback/uploads/export?format=CSV',
    );
    await user.click(within(exporting).getByRole('button', { name: t('common.cancel') }));
    await user.click(within(await liveRow('FBE-2026-001')).getByRole('button', { name: 'Withdraw…' }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByRole('textbox'), 'late');
    await user.click(within(dialog).getAllByRole('button').at(-1) as HTMLElement);
    expect(await screen.findByRole('alert')).toHaveTextContent(/APPROVED/);
  });

  it('a row of the enablements picks its upload; an enablement without one says so', async () => {
    const other = {
      ...UPLOAD,
      id: 10,
      batchNo: 'FBU-2',
      enablementId: 5,
      enablementReference: 'FBE-2026-002',
    };
    const calls = backend({
      ...fallback(
        [
          ENABLEMENT,
          { ...ENABLEMENT, id: 5, reference: 'FBE-2026-002' },
          { ...ENABLEMENT, id: 6, reference: 'FBE-2026-003', officeNames: ['MR001 - Mumbai'] },
        ],
        [UPLOAD, other],
      ),
      'GET /v1/hrms-fallback/uploads': (url: string) =>
        url.includes('/rejections')
          ? []
          : url.includes('enablementId=5')
            ? paged([other])
            : url.includes('enablementId=6')
              ? paged([])
              : paged([UPLOAD, other]),
    });
    wrap(<HrmsFallback />, ['X-07:VIEW']);
    const user = userEvent.setup();
    const type = t('x07.w.type.SUSPENSION_ABSCONDING');
    expect(await screen.findByText(t('x07.w.detail', ['FBE-2026-001', 'DH001', type]))).toBeInTheDocument();
    await user.click(await rowOf('FBE-2026-002'));
    expect(await screen.findByText(t('x07.w.detail', ['FBE-2026-002', 'DH001', type]))).toBeInTheDocument();
    expect(await rowOf('FBE-2026-002')).toHaveAttribute('aria-selected', 'true');
    expect(calls.some((c) => c.url.includes('enablementId=5'))).toBe(true);
    await user.click(await rowOf('FBE-2026-003'));
    expect(
      await screen.findByText(t('x07.w.detail', ['FBE-2026-003', 'MR001 - Mumbai', type])),
    ).toBeInTheDocument();
    expect(screen.getAllByText(t('x07.w.notUploaded')).length).toBeGreaterThanOrEqual(4);
    expect(screen.getByText(t('x07.w.checkerPending'))).toBeInTheDocument();
  });

  it('with nothing enabled and nothing uploaded the card and the tiles say so, never blank', async () => {
    backend(fallback([], []));
    wrap(<HrmsFallback />, ['X-07:VIEW']);
    expect(await screen.findByText(t('x07.w.detailNone'))).toBeInTheDocument();
    expect(await screen.findByText(t('x07.w.enabledNone'))).toBeInTheDocument();
    expect(await screen.findByText(t('x07.w.awaitingNone'))).toBeInTheDocument();
    expect(await screen.findByText(t('x07.w.reconNone'))).toBeInTheDocument();
    expect(await screen.findByText(t('x07.noEnablements'))).toBeInTheDocument();
  });

  it('SRS_G_47: the HRMS interface tile shows an outage with its start, and Up without one', async () => {
    backend({
      ...fallback([], []),
      'GET /v1/hrms-fallback/interface-status': () => ({ down: true, since: '2026-07-27T16:40:00Z' }),
    });
    const { unmount } = wrap(<HrmsFallback />, ['X-07:VIEW']);
    const tile = (await screen.findByText(t('x07.tile.hrms'))).closest('.tile') as HTMLElement;
    await within(tile).findByText(t('x07.w.hrmsDown'));
    expect(tile.querySelector('.big')).toHaveClass('fl-bad');
    expect(tile).toHaveTextContent(/Since .*2026/);
    unmount();
    backend(fallback([], []));
    wrap(<HrmsFallback />, ['X-07:VIEW']);
    const up = (await screen.findByText(t('x07.tile.hrms'))).closest('.tile') as HTMLElement;
    await within(up).findByText(t('x07.w.hrmsUp'));
    expect(up).toHaveTextContent(t('x07.w.hrmsUpSub'));
  });

  it('SRS_G_47: the reconciliation table lays out differences, the folded matched lines and an event line', async () => {
    backend({
      ...fallback([ENABLEMENT], [UPLOAD]),
      'GET /v1/hrms-fallback/reconciliation': () => paged(RECON_ROWS),
    });
    wrap(<HrmsFallback />, ['X-07:VIEW']);
    const first = (await screen.findByText('103884 N. Patil')).closest('tr') as HTMLElement;
    expect(first).toHaveTextContent('FBE-2026-005 (JUN)');
    expect(first).toHaveTextContent('LWP days');
    expect(first).toHaveTextContent('Recovery 1 day pay');
    expect(within(first).getByText('JUL — recovery')).toHaveClass('p-warn');
    const second = screen.getByText('105512 J. Sen').closest('tr') as HTMLElement;
    expect(second).toHaveTextContent('Arrear 2 half-days');
    expect(within(second).getByText('JUL — arrear')).toBeInTheDocument();
    const folded = screen.getByText('4,086 others').closest('tr') as HTMLElement;
    expect(folded).toHaveTextContent(`All items${t('x07.w.match')}${t('x07.w.match')}NoneClosed`);
    const event = screen.getByText('101774 J. Paul').closest('tr') as HTMLElement;
    expect(event).toHaveTextContent('Revocation from');
    expect(event).toHaveTextContent(date('2026-07-01'));
    expect(within(event).getByText('Matched')).toHaveClass('p-ok');
    const missing = screen.getByText('101775').closest('tr') as HTMLElement;
    expect(within(missing).getAllByText(t('x07.w.notInHrms'))).toHaveLength(2);
    expect(missing).toHaveTextContent('Arrear');
  });

  it('SRS_G_13: the reconciliation exports for a role with the right, and says why for one without', async () => {
    backend(fallback([ENABLEMENT], []));
    const { unmount } = wrap(<HrmsFallback />, ['X-07:VIEW', 'X-07:EXPORT']);
    await screen.findByText(t('x07.w.enablements'));
    expect(await exportedUrl('CSV', t('x07.w.export'))).toBe(
      '/nginx/fci-payroll/v1/hrms-fallback/reconciliation/export?format=CSV',
    );
    unmount();
    wrap(<HrmsFallback />, ['X-07:VIEW']);
    await userEvent.click(await screen.findByRole('button', { name: t('x07.w.export') }));
    expect(await screen.findByText(t('x07.w.why.export'))).toBeInTheDocument();
  });

  it('OD_121: Reconciliation Report brings the table into view', async () => {
    backend(fallback([], []));
    wrap(<HrmsFallback />, ['X-07:VIEW']);
    const table = (await screen.findByText(t('x07.recon'))).closest('#x07-recon') as HTMLElement;
    const scroll = vi.fn();
    table.scrollIntoView = scroll;
    await userEvent.click(screen.getByRole('button', { name: t('x07.w.act.recon') }));
    expect(scroll).toHaveBeenCalled();
  });
});

describe('X-07 approval on the screen (WF-066)', () => {
  const PENDING: FallbackUpload = {
    ...UPLOAD,
    status: 'PENDING_APPROVAL',
    approvalId: 77,
    type: 'ATTENDANCE_LEAVE',
  };
  const otpGated = (answer: unknown) => (_url: string, init?: RequestInit) =>
    (JSON.parse(String(init?.body)) as { otp?: unknown }).otp ? answer : problem(428, 'AUTH-428-OTP', [7]);

  async function openDecision(user: ReturnType<typeof userEvent.setup>) {
    await screen.findByText(t('x07.w.fileValue', ['export.csv']));
    await waitFor(() =>
      expect(screen.getAllByRole('button', { name: t('x07.w.act.approve') })[0]).not.toHaveAttribute(
        'data-guard',
      ),
    );
    await user.click(screen.getAllByRole('button', { name: t('x07.w.act.approve') })[0] as HTMLElement);
    return screen.findByRole('dialog');
  }

  it('SRS_G_21: the Payroll Checker approves with OTP on the screen', async () => {
    const calls = backend({
      ...fallback([ENABLEMENT], [PENDING]),
      'POST /v1/approvals/77/decision': otpGated({ status: 'APPROVED' }),
    });
    wrap(<HrmsFallback />, ['X-07:VIEW', 'X-07:APPROVE']);
    const user = userEvent.setup();
    const dialog = await openDecision(user);
    await user.click(within(dialog).getByRole('button', { name: t('x07.w.act.approve') }));
    await user.type(await screen.findByLabelText(t('otp.code')), '123456');
    await user.click(screen.getByRole('button', { name: t('otp.verify') }));
    await screen.findByText(t('x07.w.decided.APPROVE', ['FBU-1']));
    const decisions = calls.filter((c) => c.url.endsWith('/v1/approvals/77/decision'));
    expect(decisions.at(-1)?.body).toMatchObject({
      decision: 'APPROVE',
      otp: { challengeId: 7, code: '123456' },
    });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('SRS_G_21: a return needs its reason and takes no OTP', async () => {
    const calls = backend({
      ...fallback([ENABLEMENT], [PENDING]),
      'POST /v1/approvals/77/decision': () => ({ status: 'RETURNED' }),
    });
    wrap(<HrmsFallback />, ['X-07:VIEW', 'X-07:APPROVE']);
    const user = userEvent.setup();
    const dialog = await openDecision(user);
    const back = within(dialog).getByRole('button', { name: t('x07.w.return') });
    expect(back).toBeDisabled();
    expect(back).toHaveAttribute('title', t('x07.w.why.returnReason'));
    await user.type(within(dialog).getByRole('textbox'), 'Wrong month in the file');
    expect(back).toBeEnabled();
    expect(back).not.toHaveAttribute('title');
    await user.click(back);
    await screen.findByText(t('x07.w.decided.RETURN', ['FBU-1']));
    expect(calls.find((c) => c.method === 'POST')?.body).toMatchObject({
      decision: 'RETURN',
      reason: 'Wrong month in the file',
    });
  });

  it('a refused decision stays in the dialog; a cancelled OTP is no error; Cancel closes it', async () => {
    let refuse = true;
    const calls = backend({
      ...fallback([ENABLEMENT], [PENDING]),
      'POST /v1/approvals/77/decision': (url: string, init?: RequestInit) =>
        refuse ? problem(403, 'WF-403-SOD') : otpGated({ status: 'APPROVED' })(url, init),
    });
    wrap(<HrmsFallback />, ['X-07:VIEW', 'X-07:APPROVE']);
    const user = userEvent.setup();
    const dialog = await openDecision(user);
    await user.click(within(dialog).getByRole('button', { name: t('x07.w.act.approve') }));
    expect(await within(dialog).findByRole('alert')).toBeInTheDocument();
    refuse = false;
    await user.click(within(dialog).getByRole('button', { name: t('x07.w.act.approve') }));
    await screen.findByLabelText(t('otp.code'));
    const otp = screen.getByLabelText(t('otp.code')).closest('form') as HTMLElement;
    await user.click(within(otp).getByRole('button', { name: t('common.cancel') }));
    await waitFor(() => expect(screen.queryByLabelText(t('otp.code'))).toBeNull());
    expect(within(dialog).queryByRole('alert')).toBeNull();
    await user.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(calls.filter((c) => c.method === 'POST').length).toBeGreaterThan(0);
  });

  it('SRS_G_21: the Maker of the upload may not approve it, and nothing pending says so', async () => {
    backend(fallback([ENABLEMENT], [{ ...PENDING, uploadedBy: 'checker.one' }]));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    client.setQueryData(['me'], {
      permissions: ['X-07:VIEW', 'X-07:APPROVE'],
      userId: 1,
      loginId: 'checker.one',
    });
    const { unmount } = render(
      <I18nProvider>
        <QueryClientProvider client={client}>
          <ToastProvider>
            <GovernedProvider>
              <HrmsFallback />
            </GovernedProvider>
          </ToastProvider>
        </QueryClientProvider>
      </I18nProvider>,
    );
    await screen.findByText(t('x07.w.fileValue', ['export.csv']));
    await waitFor(() =>
      expect(screen.getAllByRole('button', { name: t('x07.w.act.approve') })[0]).toHaveAttribute(
        'data-guard',
        t('x07.w.why.ownUpload'),
      ),
    );
    await userEvent.click(screen.getAllByRole('button', { name: t('x07.w.act.approve') })[0] as HTMLElement);
    expect(await screen.findByText(t('x07.w.why.ownUpload'))).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).toBeNull();
    unmount();
    backend(fallback([ENABLEMENT], [UPLOAD]));
    wrap(<HrmsFallback />, ['X-07:VIEW', 'X-07:APPROVE']);
    await screen.findByText(t('x07.w.fileValue', ['export.csv']));
    expect(screen.getAllByRole('button', { name: t('x07.w.act.approve') })[0]).toHaveAttribute(
      'data-guard',
      t('x07.w.why.nonePending'),
    );
  });
});

describe('X-07 refusals', () => {
  it('SRS_EH_11: a refused proposal, upload, order and submission are shown; a dismissed reason sends nothing', async () => {
    const calls = backend({
      ...fallback([ENABLEMENT], [{ ...UPLOAD, orderCopies: 1 }]),
      'GET /v1/hrms-fallback/uploads/9/lines': () => paged(LINES),
      'POST /v1/hrms-fallback/enablements': () => problem(409, 'HFB-409-STATUS', ['OPEN']),
      'POST /v1/hrms-fallback/uploads?': () => problem(422, 'HFB-422-FORMAT'),
      'POST /v1/hrms-fallback/uploads/9/order': () => problem(422, 'DOC-422-TYPE'),
      'POST /v1/hrms-fallback/uploads/9/submit': () => problem(409, 'HFB-409-STATUS', ['VALIDATED']),
    });
    wrap(<HrmsFallback />, ['X-07:VIEW', 'X-07:CREATE', 'X-07:SUBMIT']);
    const user = userEvent.setup();
    await openHistory(user);
    const row = await rowOf('FBU-1');

    await user.click(within(row).getByRole('button', { name: 'Cancel…' }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Cancel' }));
    await user.click(within(await liveRow('FBE-2026-001')).getByRole('button', { name: 'Withdraw…' }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);

    await user.click(within(row).getByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findAllByRole('alert')).not.toHaveLength(0);

    await user.click(within(row).getByRole('button', { name: 'Records' }));
    const card = await orderCard();
    await waitFor(() =>
      expect((card.querySelector('select') as HTMLSelectElement).options.length).toBeGreaterThan(0),
    );
    await pickOrder(card);
    await waitFor(() => expect(within(card).queryByRole('alert')).not.toBeNull());

    await user.click(screen.getByRole('button', { name: t('x07.w.act.upload') }));
    await user.selectOptions(await screen.findByLabelText('Enablement'), '4');
    await user.upload(
      screen.getByLabelText('HRMS export file'),
      new File(['x'], 'export.csv', { type: 'text/csv' }),
    );
    await user.click(screen.getByRole('button', { name: 'Upload and validate' }));
    await waitFor(() =>
      expect(calls.some((c) => c.method === 'POST' && c.url.includes('/uploads?enablementId=4'))).toBe(true),
    );
    expect(screen.getByRole('button', { name: 'Upload and validate' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: t('x07.w.act.enable') }));
    await screen.findByRole('option', { name: '(a) Attendance & leave' });
    await user.selectOptions(screen.getByLabelText('Fallback'), 'ATTENDANCE_LEAVE');
    const month = screen.getByLabelText('Payroll month') as HTMLSelectElement;
    await user.selectOptions(month, month.options[1]?.value ?? '');
    await user.type(screen.getByLabelText('Offices'), 'DH001');
    await user.type(screen.getByLabelText('Reason'), 'HRMS down');
    const enable = screen.getByLabelText('Fallback').closest('.card') as HTMLElement;
    const propose = within(enable).getByRole('button', { name: 'Submit for approval' });
    await user.click(propose);
    await waitFor(() =>
      expect(calls.some((c) => c.method === 'POST' && c.url.endsWith('/v1/hrms-fallback/enablements'))).toBe(
        true,
      ),
    );
    expect(screen.getByLabelText('Fallback')).toBeInTheDocument();
  });

  it('SRS_G_21: a refused submission from the Upload card is shown there', async () => {
    backend({
      ...fallback([ENABLEMENT], [{ ...UPLOAD, orderCopies: 1 }]),
      'POST /v1/hrms-fallback/uploads/9/submit': () => problem(409, 'HFB-409-STATUS', ['VALIDATED']),
    });
    wrap(<HrmsFallback />, ['X-07:VIEW', 'X-07:SUBMIT']);
    await userEvent.click(await screen.findByRole('button', { name: 'Submit for approval' }));
    expect(await screen.findAllByRole('alert')).not.toHaveLength(0);
  });
});

describe('I-23 refusals', () => {
  it('BR_CYC_010: a refused record keeps the dialog and the request; Cancel closes it with nothing more sent', async () => {
    const calls = backend({
      ...I23,
      'GET /v1/supplementary': () => paged([]),
      'POST /v1/supplementary': () => problem(409, 'SUP-409-DUPLICATE', ['SUP-00000010']),
    });
    wrap(<Supplementary />, ['I-23:VIEW', 'I-23:CREATE', 'I-23:SUBMIT']);
    const user = userEvent.setup();
    const card = (await screen.findByText(t('wf.ord.title'))).closest('.card') as HTMLElement;
    const [orderType] = Array.from(card.querySelectorAll('select')) as HTMLSelectElement[];
    const [, orderDate, office, file] = Array.from(card.querySelectorAll('input')) as HTMLInputElement[];
    // The order types arrive with the reference lists.
    await waitFor(() => expect((orderType as HTMLSelectElement).options.length).toBeGreaterThan(0));
    await user.selectOptions(orderType as HTMLSelectElement, 'OTHER');
    fireEvent.change(orderDate as HTMLInputElement, { target: { value: '2026-01-05' } });
    await user.type(office as HTMLInputElement, 'GM (Pay)');
    await user.upload(file as HTMLInputElement, PDF);
    const dialog = await raiseSupplementary(user);
    await user.type(within(dialog).getByLabelText(t('i23.sanctionRef')), 'DH/SUP/9');
    await user.click(within(dialog).getByRole('button', { name: t('i23.record') }));
    expect(await within(dialog).findAllByRole('alert')).not.toHaveLength(0);
    const form = calls.find((c) => c.method === 'POST' && c.url.endsWith('/v1/supplementary'))
      ?.body as FormData;
    expect(form.get('sanctionOrderRef')).toBe('DH/SUP/9');
    expect(within(dialog).getByRole('button', { name: t('i23.record') })).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1);
  });
});

describe('X-06, X-05 and I-20 dismissals and refusals', () => {
  it('SRS_G_11: a raise form is cancelled, a query card closed and a refused resolution shown', async () => {
    const calls = backend({
      'GET /v1/queries': queriesRoute([QUERY]),
      'POST /v1/queries/3/resolve': () => problem(409, 'QRY-409-STATE', ['CLOSED']),
    });
    wrap(<PayrollQueries />, ['X-06:VIEW', 'X-06:CREATE', 'X-06:APPROVE']);
    const user = userEvent.setup();
    await rowOf('QRY-00000003');
    await user.click(screen.getByRole('button', { name: t('x06.raiseAct') }));
    const computed = screen.getByRole('checkbox') as HTMLInputElement;
    await user.click(computed);
    expect(computed.checked).toBe(true);
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByLabelText('Payroll ID')).toBeNull();

    await user.click(within(await rowOf('QRY-00000003')).getByRole('button', { name: 'View QRY-00000003' }));
    await user.click(screen.getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('button', { name: 'Close' })).toBeNull();

    await user.click(
      within(await rowOf('QRY-00000003')).getByRole('button', { name: 'Next step on QRY-00000003' }),
    );
    await user.type(screen.getByLabelText('Resolution'), 'Correct as computed');
    await user.click(screen.getByRole('button', { name: 'Confirm and resolve' }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(calls.filter((c) => c.url.endsWith('/3/resolve'))).toHaveLength(1);
  });

  it('SRS_G_9: a refused assignment is shown on the row; a dismissed acceptance sends nothing', async () => {
    const calls = backend({
      ...x05Routes({ tiles: { open: 1, blockingG2: 0, acceptedWithReason: 0, upstream: 0 }, items: [ITEM] }),
      'POST /v1/exceptions/21/assign': () => problem(409, 'EXC-409-STATE', ['RESOLVED']),
    });
    wrap(<ExceptionsWorklist />, ['X-05:VIEW', 'X-05:EDIT', 'X-05:APPROVE']);
    await openX05Filters();
    const user = userEvent.setup();
    const bank = (await screen.findByText('MISSING_BANK')).closest('tr') as HTMLElement;
    await user.click(within(bank).getByRole('button', { name: 'Act on MISSING_BANK' }));
    const item = document.querySelector('tr[data-item="21"]') as HTMLElement;
    await user.selectOptions(within(item).getByLabelText('Owner of MISSING_BANK'), 'ESTABLISHMENT_HR');
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    await user.click(within(item).getByRole('button', { name: 'Accept…' }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(calls.filter((c) => c.url.endsWith('/21/accept'))).toHaveLength(0);
  });

  it('SRS_G_37: a refused order is shown on the drawn card and nothing more is stored', async () => {
    backend({
      ...I20,
      'GET /v1/off-cycle': () => paged([CASE]),
      'POST /v1/off-cycle/7/order': () => problem(422, 'DOC-422-TYPE'),
    });
    wrap(<OffCycle />, ['I-20:VIEW', 'I-20:CREATE', 'I-20:SUBMIT']);
    await rowOf('OFC-00000007');
    const card = await orderCard();
    await pickOrder(card);
    await waitFor(() => expect(within(card).queryByRole('alert')).not.toBeNull());
  });
});
