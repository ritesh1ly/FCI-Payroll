import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Supplementary } from '@/modules/general';
import type { SupplementaryPayment } from '@/modules/general/api/generalApi';
import { date } from '@/shared/ui';
import { backend, paged, referenceLists, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * I-23 Supplementary Payment (SRS-G-33, SRS-G-34, BR-CYC-010): a clicked row is selected for the order card; the
 * Checker's ✔ on a request with the Checker leads to the inbox and decides nothing here; the view of a request with a
 * sanction date and no member or order; a viewer's view says the submission is the Maker's; a raise not yet valid is
 * never sent; an order of a type the type list does not hold needs no reference.
 */

afterEach(() => vi.unstubAllGlobals());

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
  duplicateCheck: 'PASSED',
  duplicateOf: null,
  status: 'DRAFT',
  invoiceStatus: 'NOT_RAISED',
  invoiceId: null,
  invoiceSentAt: null,
  appsCancelStatus: 'NOT_REQUESTED',
  cancelReason: null,
  orderAttachmentId: 5,
  orderReference: 'DH/SUP/106004',
  approvalId: null,
};

function routes(rows: SupplementaryPayment[], types: unknown[] = []) {
  return backend({
    'GET /v1/supplementary/tiles': () => ({ open: 1, pendingChecker: 0, blockedDuplicates: 0, rejected: 0 }),
    'GET /v1/reference/lists': () =>
      referenceLists({
        SUPPLEMENTARY_PAYMENT_TYPE: [['SALARY_WAGE', 'Pending salary / wage']],
        ORDER_COPY_DOCUMENT_TYPE: [['OTHER', 'Other']],
      }),
    'GET /v1/document-types': () => types,
    'GET /v1/supplementary/11/orders': () => [],
    'GET /v1/supplementary/12/orders': () => [],
    'GET /v1/supplementary': (url) => paged(rows, rows.length, url),
  });
}

async function rowOf(reference: string): Promise<HTMLElement> {
  return waitFor(() => {
    const row = document.querySelector<HTMLElement>(`tr[data-reference="${reference}"]`);
    if (!row) throw new Error(`no row ${reference}`);
    return row;
  });
}

describe('I-23 rows and the view', () => {
  it('SRS-G-34: a clicked row is selected; the Checker’s ✔ on a request with the Checker sends nothing', async () => {
    const calls = routes([
      PAYMENT,
      { ...PAYMENT, id: 12, reference: 'SUP-00000012', status: 'PENDING_CHECKER', approvalId: 4 },
    ]);
    wrap(<Supplementary />, ['I-23:VIEW', 'I-23:APPROVE']);
    const first = await rowOf('SUP-00000011');
    const second = await rowOf('SUP-00000012');
    expect(first.getAttribute('aria-selected')).toBe('true');
    await userEvent.click(within(second).getAllByRole('cell')[0] as HTMLElement);
    expect(second.getAttribute('aria-selected')).toBe('true');
    expect(second.className).toBe('fl-row-selected');
    expect(first.getAttribute('aria-selected')).toBe('false');
    // The draft has no ✔; the request with the Checker has it (decided on W-01, not here).
    expect(within(first).queryByRole('button', { name: '✔' })).toBeNull();
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign, search: '' });
    const before = calls.length;
    await userEvent.click(within(second).getByRole('button', { name: '✔' }));
    expect(assign).toHaveBeenCalledWith('/payroll/?s=W-01');
    expect(calls.slice(before).some((c) => c.method !== 'GET')).toBe(false);
  });

  it('SRS-G-33: the view of a request with a sanction date, no member and no order shows dashes; Close shuts it', async () => {
    routes([{ ...PAYMENT, eis: null, memberName: null, sanctionDate: '2026-08-20', orderReference: null }]);
    wrap(<Supplementary />, ['I-23:VIEW']);
    const row = await rowOf('SUP-00000011');
    await userEvent.click(within(row).getByRole('button', { name: `${t('common.view')} SUP-00000011` }));
    const dialog = await screen.findByRole('dialog', { name: t('i23.viewTitle', ['SUP-00000011']) });
    // The member (no EIS, no name) and the order note read a dash; the sanction date is shown.
    expect(within(dialog).getAllByText('—')).toHaveLength(2);
    expect(within(dialog).getByText(date('2026-08-20'))).toBeTruthy();
    const submit = within(dialog).getByRole('button', { name: t('common.submitForApproval') });
    expect(submit).toBeDisabled();
    expect(submit.getAttribute('data-why')).toBe(t('reason.makerOnly'));
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });
});

describe('I-23 raising a request', () => {
  it('BR-CYC-010: a raise not yet valid is never sent, even by Enter; the type stays the only one offered', async () => {
    const calls = routes([]);
    wrap(<Supplementary />, ['I-23:VIEW', 'I-23:CREATE']);
    await userEvent.click(await screen.findByRole('button', { name: t('i23.newAct') }));
    const dialog = screen.getByRole('dialog', { name: t('i23.raiseTitle') });
    const type = within(dialog).getByLabelText(t('i23.paymentType')) as HTMLSelectElement;
    await waitFor(() => expect([...type.options].map((o) => o.value)).toEqual(['SALARY_WAGE']));
    fireEvent.change(type, { target: { value: 'SALARY_WAGE' } });
    expect(type.value).toBe('SALARY_WAGE');
    fireEvent.submit(dialog);
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
    expect(within(dialog).getByRole('button', { name: t('i23.record') })).toBeDisabled();
  });

  it('SRS-G-30: an order of a type the type list does not hold is held without a reference', async () => {
    routes([]);
    wrap(<Supplementary />, ['I-23:VIEW', 'I-23:CREATE']);
    const card = (await screen.findByText(t('wf.ord.title'))).closest('.card') as HTMLElement;
    await within(card).findByRole('option', { name: 'Other' });
    const inputs = Array.from(card.querySelectorAll('input')) as HTMLInputElement[];
    const date = card.querySelector('input[type=date]') as HTMLInputElement;
    const file = card.querySelector('input[type=file]') as HTMLInputElement;
    fireEvent.change(date, { target: { value: '2026-01-05' } });
    fireEvent.change(inputs[2] as HTMLInputElement, { target: { value: 'GM (Pay)' } });
    await userEvent.upload(file, new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }));
    expect(await within(card).findByText(t('wf.ord.held', ['order.pdf']))).toBeTruthy();
  });

  it('SRS-G-33: a filter that no longer lists the selected request keeps it selected for its order card', async () => {
    const calls = backend({
      'GET /v1/supplementary/tiles': () => ({
        open: 2,
        pendingChecker: 0,
        blockedDuplicates: 0,
        rejected: 0,
      }),
      'GET /v1/reference/lists': () =>
        referenceLists({
          SUPPLEMENTARY_PAYMENT_TYPE: [
            ['SALARY_WAGE', 'Pending salary / wage'],
            ['RECOVERY', 'Recovery'],
          ],
          ORDER_COPY_DOCUMENT_TYPE: [['OTHER', 'Other']],
        }),
      'GET /v1/document-types': () => [],
      'GET /v1/supplementary/11/orders': () => [],
      'GET /v1/supplementary/12/orders': () => [],
      'GET /v1/supplementary': (url) =>
        url.includes('paymentType=RECOVERY')
          ? paged([PAYMENT], 1, url)
          : paged([PAYMENT, { ...PAYMENT, id: 12, reference: 'SUP-00000012' }], 2, url),
    });
    wrap(<Supplementary />, ['I-23:VIEW']);
    const second = await rowOf('SUP-00000012');
    await userEvent.click(within(second).getAllByRole('cell')[0] as HTMLElement);
    await userEvent.click(screen.getByRole('button', { name: t('i23.filterAct') }));
    await userEvent.selectOptions(screen.getByLabelText(t('i23.paymentType')), 'RECOVERY');
    await waitFor(() => expect(calls.some((c) => c.url.includes('paymentType=RECOVERY'))).toBe(true));
    await waitFor(() => expect(document.querySelector('tr[data-reference="SUP-00000012"]')).toBeNull());
    expect((await rowOf('SUP-00000011')).getAttribute('aria-selected')).toBe('false');
  });
});
