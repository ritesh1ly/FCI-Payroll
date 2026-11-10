import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CycleControl } from '@/modules/jobs';
import { businessToday } from '@/shared/time';
import { dateTime } from '@/shared/ui';
import { backend, paged, referenceLists, sent, t, wrap } from './support/harness';

/**
 * C-01 Payroll Cycle Control as Wireframes v6.1 draws it (OD-121; SRS-15-12, SRS-15-14, SRS-15-24 … 15-28,
 * SRS-15-42, SRS-G-32): the Maker and the Checker sent to C-02 from an open period, a reconciled fallback, a run with
 * no unit count yet (a dash), the release dialog cancelled without a request, and the order copy the Maker stores on
 * the period's instance.
 */
afterEach(() => vi.unstubAllGlobals());

const MAKER = ['C-01:VIEW', 'C-02:CREATE', 'C-02:LEASE_RELEASE'];
const CHECKER = ['C-01:VIEW', 'C-01:APPROVE', 'C-02:APPROVE'];

const OVERVIEW = {
  period: '2026-10',
  finYear: '2026-27',
  unitCode: 'DH001',
  unitLabel: 'Delhi',
  unitLevel: 'DO',
  cycleId: 7,
  status: 'FROZEN',
  frozenBy: 'maker.delhi',
  frozenAt: '2026-10-20T10:00:00Z',
  postedToEbs: false,
  supplementaryOpen: false,
  tiles: {
    disbursement: '2026-11-04',
    daysToDisbursement: 4,
    engines: { STAFF: 'COMPLETE' },
    blockingExceptions: 0,
    verifiedBy: null,
    verifiedAt: null,
  },
  preRun: [],
  run: {
    id: 32,
    status: 'RUNNING',
    startedBy: 'maker.delhi',
    startedAt: '2026-10-20T11:00:00Z',
    units: null,
    unitsCommitted: 0,
    unitsFailed: 0,
  },
  lease: {
    cycleId: 7,
    action: 'COMPUTE',
    heldBy: 'maker.other',
    acquiredAt: '2026-10-20T10:00:00Z',
    heartbeatAt: '2026-10-20T10:00:00Z',
    runId: 32,
    stale: false,
  },
  levels: [],
  fallback: [
    {
      unitCode: 'DH001',
      unitLabel: 'Delhi',
      type: 'SUSPENSION_ABSCONDING',
      status: 'RECONCILED',
      blocksLock: false,
    },
  ],
};

const PERIODS = [
  {
    period: '2026-10',
    status: 'COMPUTED',
    computedAt: '2026-10-19T10:00:00Z',
    verifiedBy: null,
    verifiedAt: '2026-10-20T10:00:00Z',
    lockedAt: null,
    postedToEbs: false,
    supplementaryOpen: true,
    mdpOpen: false,
  },
];

const VIEW = { cycleId: 7, status: 'FROZEN', gates: [], checks: [], blocking: [], lockable: false };

function routes() {
  return backend({
    'GET /v1/cycles/2026-10/overview': () => OVERVIEW,
    'GET /v1/cycles/2026-10/orders': () => [],
    'GET /v1/cycles/period-register': (url: string) => paged(PERIODS, 1, url),
    'GET /v1/document-types': () => [{ code: 'ORDER', name: 'Order', referenceRequired: true }],
    'GET /v1/reference/lists': () => referenceLists({ ORDER_COPY_DOCUMENT_TYPE: [['OTHER', 'Other']] }),
    'GET /v1/tax/flagged-tds-cases?': (url: string) => paged([], 0, url),
    'GET /v1/cycles/2026-10/gates': () => VIEW,
    'GET /v1/cycles/2026-10/quarantine': () => paged([]),
    'GET /v1/cycle-register-entries?': (url: string) => paged([], 0, url),
    'POST /v1/cycles/2026-10/order': () => ({
      id: 3,
      fileName: 'order.pdf',
      documentTypeCode: 'OTHER',
      documentTypeName: 'Other',
      referenceNumber: null,
      uploadedBy: 'maker',
      uploadedAt: '2026-10-20T10:00:00Z',
    }),
  });
}

describe('C-01 period rows, run state and fallback', () => {
  it('SRS_15_12_theMakerOnAnOpenPeriodIsSentToTheCycleOfC02', async () => {
    routes();
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    const user = userEvent.setup();
    wrap(<CycleControl />, MAKER);
    const row = (await screen.findByText('202610')).closest('tr') as HTMLElement;
    expect(within(row).getByText(t('c01w.supplementaryOpen'))).toBeInTheDocument();
    await user.click(within(row).getByRole('button', { name: t('wf.edit') }));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('s=C-02#c02-cycle'));
  });

  it('SRS_15_12_theCheckerTicksAnOpenPeriodAndIsSentToC02', async () => {
    routes();
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    const user = userEvent.setup();
    wrap(<CycleControl />, CHECKER);
    const row = (await screen.findByText('202610')).closest('tr') as HTMLElement;
    // not the Maker: the row Edit is off with the Maker's reason
    expect(within(row).getByRole('button', { name: t('wf.edit') })).toHaveAttribute(
      'data-why',
      t('c01w.why.maker'),
    );
    await user.click(within(row).getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('s=C-02'));
  });

  it('SRS_15_14_aVerifiedPeriodWithoutItsCheckerNameShowsOnlyTheTime', async () => {
    routes();
    const user = userEvent.setup();
    wrap(<CycleControl />, MAKER);
    const row = (await screen.findByText('202610')).closest('tr') as HTMLElement;
    expect(row.querySelector('.sub2')).toBeNull();
    await user.click(within(row).getByRole('button', { name: t('c01w.viewOf', ['2026-10']) }));
    const dialog = await screen.findByRole('dialog');
    expect(
      within(dialog).getByText(`${dateTime('2026-10-20T10:00:00Z')} `, { normalizer: (s) => s }),
    ).toBeInTheDocument();
    // supplementary open: Yes
    expect(within(dialog).getAllByText(t('x05.yes'))).toHaveLength(1);
  });

  it('SRS_15_24_aRunWithoutItsUnitCountReadsADashAndAReconciledFallbackDoesNotBlock', async () => {
    routes();
    wrap(<CycleControl />, MAKER);
    expect(
      await screen.findByText(
        t('c01w.run.progress', [0, '—', 0, dateTime('2026-10-20T11:00:00Z'), 'maker.delhi']),
      ),
    ).toBeInTheDocument();
    const reconciled = screen.getByText(t('c01w.fallback.status.RECONCILED'));
    expect(reconciled).toHaveClass('p-ok');
    expect(screen.getByText(t('c01w.fallback.SUSPENSION_ABSCONDING', ['DH001 Delhi']))).toBeInTheDocument();
    expect(screen.getByText(t('c01w.fallback.text.RECONCILED'))).toBeInTheDocument();
  });

  it('SRS_15_28_cancellingTheReleaseDialogAsksNothing', async () => {
    const calls = routes();
    const user = userEvent.setup();
    wrap(<CycleControl />, MAKER);
    await screen.findByText(t('c01w.run.leased'));
    await user.click(screen.getByRole('button', { name: t('c01w.release') }));
    const dialog = screen.getByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(sent(calls, 'POST', '/lease-releases')).toHaveLength(0);
  });
});

describe('C-01 order copy', () => {
  it('SRS_G_32_theMakerStoresAnOrderCopyOnThePeriodWithoutAReference', async () => {
    const calls = routes();
    const user = userEvent.setup();
    wrap(<CycleControl />, MAKER);
    // the card is keyed by the cycle: it is drawn again once the overview names it
    await screen.findByText(t('c01w.run.leased'));
    const card = (await screen.findByText(t('wf.ord.title'))).closest('[data-order-card]') as HTMLElement;
    const select = card.querySelector('select') as HTMLSelectElement;
    await waitFor(() => expect(select.options.length).toBe(1));
    const [, orderDate, , file] = Array.from(card.querySelectorAll('input')) as [
      HTMLInputElement,
      HTMLInputElement,
      HTMLInputElement,
      HTMLInputElement,
    ];
    fireEvent.change(orderDate, { target: { value: businessToday() } });
    await user.upload(file, new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }));
    await waitFor(() => expect(sent(calls, 'POST', '/v1/cycles/2026-10/order')).toHaveLength(1));
    const form = sent(calls, 'POST', '/v1/cycles/2026-10/order')[0] as FormData;
    expect(form.get('documentType')).toBe('OTHER');
    // no reference and no issuing office typed: neither is sent
    expect(form.get('reference')).toBeNull();
    expect(form.get('issuingAuthority')).toBeNull();
    expect(form.get('orderDate')).toBe(businessToday());
  });

  it('SRS_G_32_theReferenceAndOfficeTypedAreSentWithTheFile', async () => {
    const calls = routes();
    const user = userEvent.setup();
    wrap(<CycleControl />, MAKER);
    // the card is keyed by the cycle: it is drawn again once the overview names it
    await screen.findByText(t('c01w.run.leased'));
    const card = (await screen.findByText(t('wf.ord.title'))).closest('[data-order-card]') as HTMLElement;
    await waitFor(() => expect((card.querySelector('select') as HTMLSelectElement).options.length).toBe(1));
    const [reference, orderDate, office, file] = Array.from(card.querySelectorAll('input')) as [
      HTMLInputElement,
      HTMLInputElement,
      HTMLInputElement,
      HTMLInputElement,
    ];
    await user.type(reference, 'FCI/C01/1');
    fireEvent.change(orderDate, { target: { value: businessToday() } });
    await user.type(office, 'DO Delhi');
    await user.upload(file, new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }));
    await waitFor(() => expect(sent(calls, 'POST', '/v1/cycles/2026-10/order')).toHaveLength(1));
    const form = sent(calls, 'POST', '/v1/cycles/2026-10/order')[0] as FormData;
    expect(form.get('reference')).toBe('FCI/C01/1');
    expect(form.get('issuingAuthority')).toBe('DO Delhi');
  });
});
