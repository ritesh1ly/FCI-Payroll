import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LifecycleEvents, MyHrmsUpdate, StaffTransfers } from '@/modules/employee';
import { backend, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

/**
 * E-01 HRMS Update Request (SRS-17-14, SRS-17-15), M-11 Staff Transfers (SRS-G-31, SRS l.1343) and I-17 Lifecycle
 * Events (SRS-1-63): the outcomes of a request (closed, routed), a request raised with no order copy, the order copy
 * card closed, the support preview's rows without a value; the M-11 order card attaching to the picked transfer's
 * task; the I-17 card with its type list failing.
 */

afterEach(() => vi.unstubAllGlobals());

const MEMBER = ['E-01:VIEW', 'E-01:SELF', 'E-01:CREATE'];

const request = (over: object = {}) => ({
  id: 3,
  requestNo: 'HUR-2026-0003',
  fieldLabel: 'Basic Pay',
  channel: 'ESEVA',
  raisedAt: '2026-10-21T05:00:00Z',
  hrmsReference: null,
  effectiveDate: '2026-10-01',
  status: 'CLOSED',
  messageCode: null,
  taskNo: null,
  taskStatus: null,
  ...over,
});

function e01Routes(
  post: () => unknown = () => ({
    status: 201,
    body: request({ id: 9, requestNo: 'HUR-2026-0009', status: 'TASK_OPENED' }),
  }),
) {
  return backend({
    'GET /v1/hrms-update-requests/fields': () => [{ code: 'BASIC_PAY', label: 'Basic Pay' }],
    'GET /v1/hrms-update-requests/mine/': () => [],
    'GET /v1/hrms-update-requests/mine': (url) =>
      paged([request(), request({ id: 4, requestNo: 'HUR-2026-0004', status: 'ROUTED' })], 2, url),
    'POST /v1/hrms-update-requests/mine': post,
    'GET /v1/document-types': () => [],
  });
}

describe('E-01 HRMS Update Request', () => {
  it('SRS-17-15: a closed request is grey and a routed one is informational', async () => {
    e01Routes();
    wrap(<MyHrmsUpdate />, MEMBER);
    const table = await screen.findByRole('table', { name: t('i21.upd.list') });
    expect(await within(table).findByText(t('i21.upd.status.CLOSED'))).toHaveClass('p-gray');
    expect(within(table).getByText(t('i21.upd.status.ROUTED'))).toHaveClass('p-info');
  });

  it('SRS-17-14: a request raised with the file taken back opens no order card and sends no reference', async () => {
    const calls = e01Routes();
    wrap(<MyHrmsUpdate />, MEMBER);
    const form = await screen.findByRole('form', { name: t('i21.upd.raise') });
    await screen.findByRole('option', { name: 'Basic Pay' });
    await userEvent.selectOptions(within(form).getByLabelText(new RegExp(t('i21.field'))), 'BASIC_PAY');
    await userEvent.type(within(form).getByLabelText(new RegExp(t('i21.effective'))), '2026-10-01');
    const file = within(form).getByLabelText(t('e01w.hrms.orderCopy'));
    await userEvent.upload(file, new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }));
    fireEvent.change(file, { target: { files: [] } });
    await userEvent.click(within(form).getByRole('button', { name: t('e01w.hrms.raise') }));
    expect(
      await screen.findByText(t('i21.upd.raised', ['HUR-2026-0009', t('i21.upd.status.TASK_OPENED')])),
    ).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/hrms-update-requests/mine')).toEqual([
      { fieldCode: 'BASIC_PAY', effectiveDate: '2026-10-01' },
    ]);
    expect(screen.queryByRole('region', { name: t('i21.upd.order.title', ['HUR-2026-0009']) })).toBeNull();
  });

  it('SRS-G-30: the order card that opens on the new request closes with Close', async () => {
    e01Routes();
    wrap(<MyHrmsUpdate />, MEMBER);
    const form = await screen.findByRole('form', { name: t('i21.upd.raise') });
    await screen.findByRole('option', { name: 'Basic Pay' });
    await userEvent.selectOptions(within(form).getByLabelText(new RegExp(t('i21.field'))), 'BASIC_PAY');
    await userEvent.type(within(form).getByLabelText(new RegExp(t('i21.effective'))), '2026-10-01');
    await userEvent.upload(
      within(form).getByLabelText(t('e01w.hrms.orderCopy')),
      new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }),
    );
    await userEvent.click(within(form).getByRole('button', { name: t('e01w.hrms.raise') }));
    const card = await screen.findByRole('region', { name: t('i21.upd.order.title', ['HUR-2026-0009']) });
    await userEvent.click(within(card).getByRole('button', { name: t('common.close') }));
    await waitFor(() =>
      expect(screen.queryByRole('region', { name: t('i21.upd.order.title', ['HUR-2026-0009']) })).toBeNull(),
    );
  });

  it('SRS-17-17: the support preview lists a submission without a value as a dash and reads nothing of its own', async () => {
    const calls = backend({ 'GET /v1/hrms-update-requests/fields': () => [] });
    wrap(
      <MyHrmsUpdate
        preview={{
          why: 'Support preview: read only',
          rows: [{ value: null, status: 'ROUTED', submittedAt: '2026-10-01T05:00:00Z' }],
        }}
      />,
      ['E-02:VIEW'],
    );
    expect(await screen.findByText(t('e02.status.ROUTED'))).toBeTruthy();
    const row = screen.getByText(t('e02.status.ROUTED')).closest('tr') as HTMLElement;
    expect(within(row).getAllByText('—')).toHaveLength(2);
    expect(calls.some((c) => c.url.includes('/mine'))).toBe(false);
    expect(screen.getByRole('button', { name: t('e01w.hrms.raise') }).dataset.why).toBe(
      'Support preview: read only',
    );
  });
});

describe('M-11 the order card of the picked transfer', () => {
  const row = (over: object = {}) => ({
    id: 2,
    panMasked: 'XXXXXX234K',
    eis: '105461',
    employee: 'Amrita Kaur',
    oldStaffNo: '105461',
    newStaffNo: null,
    origin: 'DO Delhi',
    destination: 'DO Mumbai',
    cpfMemberId: 'CPF/105461',
    effectiveDate: '2026-11-17',
    taskId: 7,
    taskNo: 'HCA-2026-9103',
    taskStatus: 'PENDING_ORDER_DETAILS',
    approvalStatus: null,
    ...over,
  });

  it('SRS-G-31: the Maker picks a transfer and attaches its order to the task, with its particulars', async () => {
    const calls = backend({
      'GET /v1/transfers/summary': () => ({ period: '2026-11', received: 2, reissued: 0, withChecker: 0 }),
      'GET /v1/transfers/register': (url) =>
        paged(
          [row(), row({ id: 3, eis: '105462', employee: 'Vikram Rao', taskId: 8, taskNo: 'HCA-2026-9104' })],
          2,
          url,
        ),
      'GET /v1/transfers': (url) => paged([], 0, url),
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/hrms-change-tasks/8/documents': () => [],
      'POST /v1/hrms-change-tasks/8/documents': () => ({ status: 201, body: { id: 4 } }),
      'GET /v1/document-types': () => [
        { code: 'TRANSFER_ORDER', name: 'Transfer order', referenceRequired: true },
      ],
      'GET /v1/reference/lists': () =>
        referenceLists({ ORDER_COPY_DOCUMENT_TYPE: [['TRANSFER_ORDER', 'Transfer order']] }),
    });
    wrap(<StaffTransfers />, ['M-11:VIEW', 'I-21:SUBMIT']);
    await userEvent.click(await screen.findByText('HCA-2026-9104'));
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('/hrms-change-tasks/8/documents'))).toBe(true),
    );
    const card = document.querySelector('[data-order-card]') as HTMLElement;
    await within(card).findByRole('option', { name: 'Transfer order' });
    const file = card.querySelector('input[type=file]') as HTMLInputElement;
    await waitFor(() => expect(file.disabled).toBe(false));
    fireEvent.change(card.querySelector('input[type=date]') as HTMLInputElement, {
      target: { value: '2026-11-01' },
    });
    fireEvent.change(within(card).getByPlaceholderText(t('wf.ord.officePlaceholder')), {
      target: { value: 'GM (P), Delhi' },
    });
    fireEvent.change(file, {
      target: { files: [new File(['%PDF'], 'order.pdf', { type: 'application/pdf' })] },
    });
    // The transfer order needs its reference.
    expect(await within(card).findByText(t('wf.ord.particularsFirst'))).toBeTruthy();
    fireEvent.change(within(card).getByPlaceholderText(t('wf.ord.referencePlaceholder')), {
      target: { value: 'EST/TR/31' },
    });
    fireEvent.change(file, {
      target: { files: [new File(['%PDF'], 'order.pdf', { type: 'application/pdf' })] },
    });
    expect(await within(card).findByText(t('wf.ord.stored', [1]))).toBeTruthy();
    const post = calls.find((c) => c.method === 'POST')?.body as FormData;
    expect(calls.find((c) => c.method === 'POST')?.url).toMatch(/\/v1\/hrms-change-tasks\/8\/documents$/);
    expect(post.get('documentType')).toBe('TRANSFER_ORDER');
  });

  it('SRS-G-31: a type the type list does not hold needs no reference: the order is stored without one', async () => {
    const calls = backend({
      'GET /v1/transfers/summary': () => ({ period: '2026-11', received: 1, reissued: 0, withChecker: 0 }),
      'GET /v1/transfers/register': (url) => paged([row()], 1, url),
      'GET /v1/transfers': (url) => paged([], 0, url),
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'POST /v1/hrms-change-tasks/7/documents': () => ({ status: 201, body: { id: 4 } }),
      'GET /v1/document-types': () => [],
      'GET /v1/reference/lists': () =>
        referenceLists({ ORDER_COPY_DOCUMENT_TYPE: [['TRANSFER_ORDER', 'Transfer order']] }),
    });
    wrap(<StaffTransfers />, ['M-11:VIEW', 'I-21:SUBMIT']);
    await screen.findByText('HCA-2026-9103');
    const card = document.querySelector('[data-order-card]') as HTMLElement;
    await within(card).findByRole('option', { name: 'Transfer order' });
    const file = card.querySelector('input[type=file]') as HTMLInputElement;
    await waitFor(() => expect(file.disabled).toBe(false));
    fireEvent.change(card.querySelector('input[type=date]') as HTMLInputElement, {
      target: { value: '2026-11-01' },
    });
    fireEvent.change(within(card).getByPlaceholderText(t('wf.ord.officePlaceholder')), {
      target: { value: 'GM (P), Delhi' },
    });
    fireEvent.change(file, {
      target: { files: [new File(['%PDF'], 'order.pdf', { type: 'application/pdf' })] },
    });
    expect(await within(card).findByText(t('wf.ord.stored', [1]))).toBeTruthy();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1);
  });

  it('R-INT: a failing type list (500) is shown on the card; a viewer cannot attach', async () => {
    backend({
      'GET /v1/transfers/summary': () => ({ period: '2026-11', received: 1, reissued: 0, withChecker: 0 }),
      'GET /v1/transfers/register': (url) => paged([row()], 1, url),
      'GET /v1/transfers': (url) => paged([], 0, url),
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/document-types': () => problem(500, 'SYS-500'),
    });
    wrap(<StaffTransfers />, ['M-11:VIEW']);
    await screen.findByText('HCA-2026-9103');
    const card = document.querySelector('[data-order-card]') as HTMLElement;
    expect(await within(card).findByRole('alert')).toBeTruthy();
    expect((card.querySelector('input[type=file]') as HTMLInputElement).disabled).toBe(true);
  });
});

describe('I-17 the order card', () => {
  it('R-INT: with the type list failing (500) the card shows the error and stays read-only', async () => {
    backend({
      'GET /v1/lifecycle-events/received': (url) =>
        paged(
          [
            {
              id: 1,
              eventType: 'SUSPENSION',
              eis: '106118',
              employee: 'Tara Rao',
              effective: '2026-10-14',
              endDate: null,
              subsistencePercent: 50,
              releaseFrom: null,
              releaseTo: null,
              interveningPeriod: null,
              reason: 'Enquiry',
              source: 'HRMS',
              hrmsRequestId: 'HRMS-SUS-0044',
              orderReference: 'VIG/9',
              taskId: 7,
              taskNo: 'HCA-2026-9107',
              taskStatus: 'APPROVED',
            },
          ],
          1,
          url,
        ),
      'GET /v1/lifecycle-events': (url) => paged([], 0, url),
      'GET /v1/hrms-change-tasks/7/documents': () => [
        {
          id: 91,
          fileName: 'order-9107.pdf',
          documentTypeCode: 'SANCTION_ORDER',
          documentTypeName: 'Sanction order',
          referenceNumber: 'EST/ORD/2026-9107',
          uploadedBy: 'maker.delhi@fci-demo.test',
          uploadedAt: '2026-10-26T05:00:00Z',
        },
      ],
      'GET /v1/document-types': () => problem(500, 'SYS-500'),
    });
    wrap(<LifecycleEvents />, ['I-17:VIEW']);
    expect(await screen.findByText('order-9107.pdf')).toBeTruthy();
    const card = document.querySelector('[data-order-card]') as HTMLElement;
    expect(await within(card).findByRole('alert')).toBeTruthy();
    expect(
      within(card)
        .getByRole('link', { name: t('wf.ord.viewOrder') })
        .getAttribute('href'),
    ).toMatch(/\/v1\/hrms-change-tasks\/7\/documents\/91$/);
  });
});
