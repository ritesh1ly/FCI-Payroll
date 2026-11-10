import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ComputationExceptions, OffCycle } from '@/modules/general';
import type { OffCycleCase } from '@/modules/general/api/generalApi';
import { backend, me, paged, referenceLists, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * C-08 Computation Exceptions Review (SRS-G-9, SRS l.1641-1678) and I-20 Off-Cycle Payment (SRS-G-10, SRS-G-16):
 * the items of a condition with no member, no field and an accepted reason, an acceptable item with no blocked
 * reason, the items closed; a fixable condition without a correction screen; a session with no period or roles; the
 * I-20 row picked for its order card, a viewer's view of a case with no member and no approval reference, a raise
 * not yet valid never sent, and an order type the type list does not hold.
 */

afterEach(() => vi.unstubAllGlobals());

const condition = (over: object = {}) => ({
  moduleCode: 'I-01',
  exceptionCode: 'WORK_SLIP_MISSING',
  payrollType: 'DPS_LABOUR',
  count: 2,
  severity: 'WARNING',
  fieldName: 'Booking-slip',
  fieldSource: 'ENT',
  correctionScreen: null,
  blocksLock: false,
  owner: 'LABOUR_DEALING_HAND',
  resolvable: true,
  detail: 'Work-slip missing',
  ...over,
});

const ITEM = {
  id: 5,
  cycleInstanceId: 7,
  eis: null,
  memberName: null,
  moduleCode: 'I-01',
  exceptionCode: 'WORK_SLIP_MISSING',
  severity: 'WARNING',
  blocksGate: 'G2',
  fieldName: null,
  fieldSource: 'ENT',
  resolvableInPayroll: true,
  correctionScreen: 'I-02',
  ownerRoleCode: 'LABOUR_DEALING_HAND',
  detail: 'Work-slip missing',
  status: 'ACCEPTED',
  acceptedBy: 'checker.delhi',
  acceptedReason: 'Slip follows in the next run',
  raisedAt: '2026-10-20T10:00:00Z',
  ageDays: 2,
  resolvedAt: null,
};

function c08(items: object[] = [ITEM], rows: object[] = [condition()]) {
  return backend({
    'GET /v1/computation-exceptions?': (url) => ({
      ...paged(rows, rows.length, url),
      period: '2026-10',
      tiles: { total: 2, blocking: 0, fixable: 2, hrms: 0, systemOrMember: 0 },
    }),
    'GET /v1/computation-exceptions/items': (url) => paged(items, items.length, url),
  });
}

describe('C-08 the items of a condition', () => {
  it('SRS-G-9: an item with no member or field shows dashes and its accepted reason; Close shuts the items', async () => {
    c08([
      ITEM,
      { ...ITEM, id: 6, status: 'OPEN', acceptedBy: null, acceptedReason: null, eis: 'E002' },
      {
        ...ITEM,
        id: 7,
        status: 'OPEN',
        severity: 'BLOCKING',
        acceptedBy: null,
        acceptedReason: null,
        eis: 'E003',
      },
    ]);
    wrap(<ComputationExceptions />, me(['C-08:VIEW', 'X-05:APPROVE'], { roles: ['PAYROLL_CHECKER'] }));
    const r = (await screen.findByText('WORK_SLIP_MISSING')).closest('tr') as HTMLElement;
    // A fixable condition without its screen names no screen.
    expect(within(r).queryByText(t('c08.fix.upstream'))).toBeNull();
    await userEvent.click(within(r).getByRole('button', { name: t('x05.viewOf', ['WORK_SLIP_MISSING']) }));
    expect(await screen.findByText('Slip follows in the next run')).toBeTruthy();
    const accepts = screen.getAllByRole('button', { name: t('x05.accept') });
    // The accepted item has no Accept; the open warning can be accepted; the blocking one says why not.
    expect(accepts).toHaveLength(2);
    expect(accepts[0]?.getAttribute('title')).toBeNull();
    expect(accepts[0]).toBeEnabled();
    expect(accepts[1]?.getAttribute('title')).toBe(t('x05.acceptBlocked'));
    expect(accepts[1]).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    await waitFor(() => expect(screen.queryByText(t('x05.itemsOf', ['WORK_SLIP_MISSING']))).toBeNull());
  });

  it('SRS l.1643: a session with no period and no roles still reads the review and acts on nothing', async () => {
    const calls = c08();
    wrap(
      <ComputationExceptions />,
      me(['C-08:VIEW'], { period: undefined as unknown as string, roles: undefined as unknown as string[] }),
    );
    const r = (await screen.findByText('WORK_SLIP_MISSING')).closest('tr') as HTMLElement;
    expect(within(r).getByRole('button', { name: t('x05.editOf', ['WORK_SLIP_MISSING']) })).toHaveAttribute(
      'data-why',
    );
    expect(calls.some((c) => c.method !== 'GET')).toBe(false);
  });
});

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

function i20(rows: OffCycleCase[]) {
  return backend({
    'GET /v1/reference/lists': () =>
      referenceLists({
        OFF_CYCLE_CASE_TYPE: [['LATE_JOINER', 'Late joiner']],
        ORDER_COPY_DOCUMENT_TYPE: [['OTHER', 'Other']],
      }),
    'GET /v1/document-types': () => [],
    'GET /v1/off-cycle/7/orders': () => [],
    'GET /v1/off-cycle/8/orders': () => [],
    'POST /v1/off-cycle/8/order': () => ({ status: 201, body: { id: 3 } }),
    'GET /v1/off-cycle': (url) => paged(rows, rows.length, url),
  });
}

const rowOf = (reference: string) =>
  waitFor(() => {
    const row = document.querySelector<HTMLElement>(`tr[data-reference="${reference}"]`);
    if (!row) throw new Error(`no row ${reference}`);
    return row;
  });

describe('I-20 rows, the view and the raise', () => {
  it('SRS-G-16: a clicked row is selected; a viewer’s view of a case with no member or reference shows dashes', async () => {
    i20([
      CASE,
      { ...CASE, id: 8, reference: 'OFC-00000008', eis: null, memberName: null, approvalReference: '' },
    ]);
    wrap(<OffCycle />, ['I-20:VIEW']);
    const first = await rowOf('OFC-00000007');
    const second = await rowOf('OFC-00000008');
    expect(first.getAttribute('aria-selected')).toBe('true');
    await userEvent.click(within(second).getAllByRole('cell')[0] as HTMLElement);
    expect(second.getAttribute('aria-selected')).toBe('true');
    await userEvent.click(within(second).getByRole('button', { name: `${t('common.view')} OFC-00000008` }));
    const view = await screen.findByRole('dialog', { name: t('i20.viewTitle', ['OFC-00000008']) });
    expect(within(view).getAllByText('—')).toHaveLength(2);
    const submit = within(view).getByRole('button', { name: t('common.submitForApproval') });
    expect(submit).toBeDisabled();
    expect(submit).toHaveAttribute('title', t('reason.makerOnly'));
  });

  it('SRS-G-10: a raise not yet complete is never sent, even by Enter', async () => {
    const calls = i20([]);
    wrap(<OffCycle />, ['I-20:VIEW', 'I-20:CREATE']);
    await userEvent.click(await screen.findByRole('button', { name: t('i20.raise') }));
    const dialog = screen.getByRole('dialog', { name: t('i20.raiseTitle') });
    fireEvent.submit(dialog);
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('SRS-G-30: an order of a type the type list does not hold is stored on the selected case without a reference', async () => {
    const calls = i20([{ ...CASE, id: 8, reference: 'OFC-00000008' }]);
    wrap(<OffCycle />, ['I-20:VIEW', 'I-20:CREATE']);
    await rowOf('OFC-00000008');
    const card = (await screen.findByText(t('wf.ord.title'))).closest('.card') as HTMLElement;
    await within(card).findByRole('option', { name: 'Other' });
    const file = card.querySelector('input[type=file]') as HTMLInputElement;
    await waitFor(() => expect(file.disabled).toBe(false));
    fireEvent.change(card.querySelector('input[type=date]') as HTMLInputElement, {
      target: { value: '2026-01-05' },
    });
    fireEvent.change(within(card).getByPlaceholderText(t('wf.ord.officePlaceholder')), {
      target: { value: 'GM (Pay)' },
    });
    fireEvent.change(file, {
      target: { files: [new File(['%PDF'], 'order.pdf', { type: 'application/pdf' })] },
    });
    expect(await within(card).findByText(t('wf.ord.stored', [1]))).toBeTruthy();
    expect(calls.filter((c) => c.method === 'POST' && c.url.endsWith('/v1/off-cycle/8/order'))).toHaveLength(
      1,
    );
  });

  it('SRS-G-16: a filter that no longer lists the selected case keeps it selected for its order card', async () => {
    const calls = backend({
      'GET /v1/reference/lists': () =>
        referenceLists({
          OFF_CYCLE_CASE_TYPE: [
            ['LATE_JOINER', 'Late joiner'],
            ['OTHER', 'Other'],
          ],
          ORDER_COPY_DOCUMENT_TYPE: [['OTHER', 'Other']],
        }),
      'GET /v1/document-types': () => [],
      'GET /v1/off-cycle/7/orders': () => [],
      'GET /v1/off-cycle/8/orders': () => [],
      'GET /v1/off-cycle': (url) =>
        url.includes('caseType=OTHER')
          ? paged([CASE], 1, url)
          : paged([CASE, { ...CASE, id: 8, reference: 'OFC-00000008' }], 2, url),
    });
    wrap(<OffCycle />, ['I-20:VIEW']);
    const second = await rowOf('OFC-00000008');
    await userEvent.click(within(second).getAllByRole('cell')[0] as HTMLElement);
    await userEvent.click(screen.getByRole('button', { name: t('i20.filterAct') }));
    await userEvent.selectOptions(screen.getByLabelText(t('i20.caseType')), 'OTHER');
    await waitFor(() => expect(calls.some((c) => c.url.includes('caseType=OTHER'))).toBe(true));
    await waitFor(() => expect(document.querySelector('tr[data-reference="OFC-00000008"]')).toBeNull());
    // The order card still reads the selected case, not the first row of the new list.
    expect(calls.filter((c) => c.url.includes('/v1/off-cycle/8/orders')).length).toBeGreaterThan(0);
    expect((await rowOf('OFC-00000007')).getAttribute('aria-selected')).toBe('false');
  });
});
