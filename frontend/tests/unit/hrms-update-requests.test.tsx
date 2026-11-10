import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { HrmsUpdateRequests } from '@/modules/employee';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * HRMS Update Request (plan 16 phase 16c; SRS-1-70): the Maker names the employee, the field and
 * the date on I-21; the employee raises their own in eSeva; the outcome (task opened, not yet in
 * HRMS, fetch failed) is listed; a failed fetch is fetched again; a viewer has no form.
 */

afterEach(() => vi.unstubAllGlobals());

const FIELDS = [
  { code: 'PAN', label: 'PAN', group: 'IDENTITY_STATUTORY' },
  { code: 'BASIC_PAY', label: 'Basic Pay', group: 'SERVICE_PAY' },
];

const request = (over: object = {}) => ({
  id: 3,
  requestNo: 'HUR-2026-0003',
  eis: '100041',
  employee: 'Asha Rao',
  fieldCode: 'BASIC_PAY',
  fieldLabel: 'Basic Pay',
  channel: 'PAYROLL_MAKER',
  raisedBy: 'maker',
  raisedAt: '2026-10-21T05:00:00Z',
  hrmsReference: 'HRMS-77',
  effectiveDate: '2026-10-01',
  fetchResult: 'FETCH_FAILED',
  status: 'FETCH_FAILED',
  messageCode: 'HRMS_FETCH_NOT_CONFIGURED',
  taskNo: null,
  ...over,
});

describe('HRMS Update Requests', () => {
  it('SRS-1-70: the maker raises a request for an employee; the task it opened is shown', async () => {
    const calls = backend({
      'GET /v1/hrms-update-requests/fields': () => FIELDS,
      'GET /v1/hrms-update-requests?': (url) => paged([request()], 1, url),
      'POST /v1/hrms-update-requests': () => ({
        status: 201,
        body: request({ id: 4, requestNo: 'HUR-2026-0004', status: 'TASK_OPENED', taskNo: 'HCA-2026-0040' }),
      }),
    });
    wrap(<HrmsUpdateRequests mine={false} />, me(['I-21:VIEW', 'I-21:REQUEST']));
    expect(await screen.findByText(t('i21.upd.msg.HRMS_FETCH_NOT_CONFIGURED'))).toBeTruthy();
    expect(screen.getByText(new RegExp(`· ${t('i21.upd.channel.PAYROLL_MAKER')}$`))).toBeTruthy();
    const form = screen.getByRole('form', { name: t('i21.upd.raise') });
    const submit = within(form).getByRole('button', { name: t('i21.upd.submit') });
    expect(submit).toBeDisabled();
    await userEvent.type(within(form).getByLabelText(t('i21.upd.eis')), '100041');
    await screen.findByRole('option', { name: 'PAN' });
    await userEvent.selectOptions(within(form).getByLabelText(t('i21.field')), 'PAN');
    await userEvent.type(within(form).getByLabelText(t('i21.effective')), '2026-10-01');
    await userEvent.click(submit);
    expect(
      await screen.findByText(t('i21.upd.raised', ['HUR-2026-0004', t('i21.upd.status.TASK_OPENED')])),
    ).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/hrms-update-requests')).toEqual([
      { eis: '100041', fieldCode: 'PAN', effectiveDate: '2026-10-01' },
    ]);
  });

  it('SRS-1-70: a failed fetch is fetched again; a refusal is shown; a viewer has neither form nor fetch', async () => {
    const calls = backend({
      'GET /v1/hrms-update-requests/fields': () => FIELDS,
      'GET /v1/hrms-update-requests?': (url) =>
        paged(
          [
            request(),
            request({
              id: 5,
              requestNo: 'HUR-2026-0005',
              status: 'TASK_OPENED',
              taskNo: 'HCA-2026-0041',
              messageCode: null,
              hrmsReference: null,
            }),
          ],
          2,
          url,
        ),
      'POST /v1/hrms-update-requests/3/fetches': () =>
        problem(409, 'HRC-409-STATE', ['HUR-2026-0003 TASK_OPENED']),
    });
    const view = wrap(<HrmsUpdateRequests mine={false} />, me(['I-21:VIEW', 'I-21:REQUEST']));
    expect(await screen.findByText(t('i21.upd.task', ['HCA-2026-0041']))).toBeTruthy();
    const again = screen.getAllByRole('button', { name: t('i21.upd.refetch') });
    expect(again).toHaveLength(1);
    await userEvent.click(again[0] as HTMLElement);
    expect(calls.some((c) => c.method === 'POST' && c.url.includes('/3/fetches'))).toBe(true);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      t('error.HRC-409-STATE', ['HUR-2026-0003 TASK_OPENED']),
    );
    view.unmount();
    backend({ 'GET /v1/hrms-update-requests?': (url) => paged([request()], 1, url) });
    wrap(<HrmsUpdateRequests mine={false} />, me(['I-21:VIEW']));
    expect(await screen.findByText('HUR-2026-0003')).toBeTruthy();
    expect(screen.queryByRole('form', { name: t('i21.upd.raise') })).toBeNull();
    expect(screen.queryByRole('button', { name: t('i21.upd.refetch') })).toBeNull();
  });

  it('SRS-1-70: a fetch that succeeds reloads the register', async () => {
    let fetched = false;
    const calls = backend({
      'GET /v1/hrms-update-requests/fields': () => FIELDS,
      'GET /v1/hrms-update-requests?': (url) =>
        paged(
          [
            fetched
              ? request({ status: 'TASK_OPENED', taskNo: 'HCA-2026-0042', messageCode: null })
              : request(),
          ],
          1,
          url,
        ),
      'POST /v1/hrms-update-requests/3/fetches': () => {
        fetched = true;
        return request({ status: 'TASK_OPENED', taskNo: 'HCA-2026-0042', messageCode: null });
      },
    });
    wrap(<HrmsUpdateRequests mine={false} />, me(['I-21:VIEW', 'I-21:REQUEST']));
    await userEvent.click(await screen.findByRole('button', { name: t('i21.upd.refetch') }));
    expect(await screen.findByText(t('i21.upd.task', ['HCA-2026-0042']))).toBeTruthy();
    expect(
      calls.filter((c) => c.method === 'GET' && c.url.includes('/v1/hrms-update-requests?')).length,
    ).toBeGreaterThan(1);
  });

  it('SRS-1-70: the employee raises their own request in eSeva, without naming an employee', async () => {
    const calls = backend({
      'GET /v1/hrms-update-requests/fields': () => FIELDS,
      'GET /v1/hrms-update-requests/mine': (url) => paged([], 0, url),
      'POST /v1/hrms-update-requests/mine': () => ({
        status: 201,
        body: request({ channel: 'ESEVA', status: 'NOT_YET_IN_HRMS', messageCode: 'HRMS_NOT_YET_UPDATED' }),
      }),
    });
    wrap(<HrmsUpdateRequests mine />, me(['E-01:VIEW', 'E-01:CREATE']));
    expect(await screen.findByText(t('i21.upd.none'))).toBeTruthy();
    const form = screen.getByRole('form', { name: t('i21.upd.raise') });
    expect(within(form).queryByLabelText(t('i21.upd.eis'))).toBeNull();
    await screen.findByRole('option', { name: 'Basic Pay' });
    await userEvent.selectOptions(within(form).getByLabelText(t('i21.field')), 'BASIC_PAY');
    await userEvent.type(within(form).getByLabelText(t('i21.upd.reference')), 'HRMS-9');
    await userEvent.type(within(form).getByLabelText(t('i21.effective')), '2026-10-01');
    await userEvent.click(within(form).getByRole('button', { name: t('i21.upd.submit') }));
    expect(
      await screen.findByText(t('i21.upd.raised', ['HUR-2026-0003', t('i21.upd.status.NOT_YET_IN_HRMS')])),
    ).toBeTruthy();
    expect(sent(calls, 'POST', '/mine')).toEqual([
      { fieldCode: 'BASIC_PAY', effectiveDate: '2026-10-01', hrmsReference: 'HRMS-9' },
    ]);
  });

  it('SRS-1-70: without E-01 the eSeva card is not shown', () => {
    backend({});
    const view = wrap(<HrmsUpdateRequests mine />, me(['I-21:VIEW']));
    expect(view.container.textContent).toBe('');
  });

  it('SRS-1-70: the employee attaches the order copy of an own request and downloads it', async () => {
    let attached = false;
    const calls = backend({
      'GET /v1/hrms-update-requests/mine?': (url) => paged([request({ channel: 'ESEVA' })], 1, url),
      'GET /v1/hrms-update-requests/fields': () => FIELDS,
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/hrms-update-requests/mine/3/documents': () =>
        attached
          ? [
              {
                id: 9,
                documentTypeName: 'Other document',
                referenceNumber: 'ORD/1',
                orderDate: '2026-10-01',
                fileName: 'order.pdf',
                uploadedBy: 'asha',
                uploadedAt: '2026-10-21T06:00:00Z',
              },
            ]
          : [],
      'POST /v1/hrms-update-requests/mine/3/documents': () => {
        attached = true;
        return { status: 201, body: { id: 9 } };
      },
    });
    wrap(<HrmsUpdateRequests mine />, me(['E-01:VIEW', 'E-01:CREATE']));
    await userEvent.click(
      await screen.findByRole('button', { name: t('i21.upd.order.of', ['HUR-2026-0003']) }),
    );
    const panel = await screen.findByRole('region', { name: t('i21.upd.order.title', ['HUR-2026-0003']) });
    expect(await within(panel).findByText(t('i21.upd.order.none'))).toBeTruthy();
    const attach = within(panel).getByRole('button', { name: t('i21.upd.order.attach') });
    expect(attach).toBeDisabled();
    await within(panel).findAllByRole('option', { name: 'Other document' });
    await userEvent.selectOptions(document.getElementById('upd-doctype') as HTMLSelectElement, 'OTHER');
    await userEvent.type(document.getElementById('upd-docdate') as HTMLElement, '2026-10-01');
    await userEvent.type(document.getElementById('upd-docauth') as HTMLElement, 'Zonal Office');
    await userEvent.upload(
      document.getElementById('upd-docfile') as HTMLInputElement,
      new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }),
    );
    expect(attach).toBeEnabled();
    await userEvent.click(attach);
    expect(await screen.findByText(t('i21.upd.order.attached'))).toBeTruthy();
    const link = await within(panel).findByRole('link', { name: 'order.pdf' });
    expect(link).toHaveAttribute('href', '/nginx/fci-payroll/v1/hrms-update-requests/mine/3/documents/9');
    const form = sent(calls, 'POST', '/mine/3/documents')[0] as FormData;
    expect(form.get('issuingAuthority')).toBe('Zonal Office');
    await userEvent.click(within(panel).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('region', { name: t('i21.upd.order.title', ['HUR-2026-0003']) })).toBeNull();
  });

  it('SRS-1-70: a viewer of the desk reads the order copies without the attach form', async () => {
    backend({
      'GET /v1/hrms-update-requests?': (url) => paged([request()], 1, url),
      'GET /v1/hrms-update-requests/3/documents': () => problem(404, 'COM-404-NOT-FOUND'),
    });
    wrap(<HrmsUpdateRequests mine={false} />, me(['I-21:VIEW']));
    await userEvent.click(
      await screen.findByRole('button', { name: t('i21.upd.order.of', ['HUR-2026-0003']) }),
    );
    const panel = await screen.findByRole('region', { name: t('i21.upd.order.title', ['HUR-2026-0003']) });
    expect(await within(panel).findByRole('alert')).toBeTruthy();
    expect(within(panel).queryByRole('button', { name: t('i21.upd.order.attach') })).toBeNull();
  });

  it('SRS-1-70: an order copy the server refuses is shown and the entry is kept', async () => {
    backend({
      'GET /v1/hrms-update-requests/mine?': (url) => paged([request({ channel: 'ESEVA' })], 1, url),
      'GET /v1/hrms-update-requests/fields': () => FIELDS,
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/hrms-update-requests/mine/3/documents': () => [],
      'POST /v1/hrms-update-requests/mine/3/documents': () => problem(422, 'DOC-422-INFECTED'),
    });
    wrap(<HrmsUpdateRequests mine />, me(['E-01:VIEW', 'E-01:CREATE']));
    await userEvent.click(
      await screen.findByRole('button', { name: t('i21.upd.order.of', ['HUR-2026-0003']) }),
    );
    const panel = await screen.findByRole('region', { name: t('i21.upd.order.title', ['HUR-2026-0003']) });
    await within(panel).findAllByRole('option', { name: 'Other document' });
    await userEvent.selectOptions(document.getElementById('upd-doctype') as HTMLSelectElement, 'OTHER');
    await userEvent.type(document.getElementById('upd-docdate') as HTMLElement, '2026-10-01');
    await userEvent.type(document.getElementById('upd-docauth') as HTMLElement, 'Zonal Office');
    await userEvent.upload(
      document.getElementById('upd-docfile') as HTMLInputElement,
      new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }),
    );
    await userEvent.click(within(panel).getByRole('button', { name: t('i21.upd.order.attach') }));
    expect(await within(panel).findByRole('alert')).toBeTruthy();
    expect(screen.queryByText(t('i21.upd.order.attached'))).toBeNull();
    expect(document.getElementById('upd-docauth')).toHaveValue('Zonal Office');
  });
});

describe('HRMS Update Request outcome (g4)', () => {
  it('E-01 l.3308 (US-GAP-258): a request whose task is approved reads "Approved and applied"; one still open reads Task opened', async () => {
    backend({
      'GET /v1/hrms-update-requests/mine?': (url) =>
        paged(
          [
            request({
              id: 3,
              channel: 'ESEVA',
              status: 'TASK_OPENED',
              messageCode: null,
              taskNo: 'HCT-9',
              taskStatus: 'APPROVED',
            }),
            request({
              id: 4,
              requestNo: 'HUR-2026-0004',
              channel: 'ESEVA',
              status: 'TASK_OPENED',
              messageCode: null,
              taskNo: 'HCT-10',
              taskStatus: 'PENDING_CHECKER',
            }),
          ],
          2,
          url,
        ),
      'GET /v1/hrms-update-requests/fields': () => FIELDS,
      'GET /v1/document-types': () => [],
    });
    wrap(<HrmsUpdateRequests mine />, me(['E-01:VIEW', 'E-01:CREATE']));
    expect(await screen.findByText(t('i21.upd.status.APPROVED_APPLIED'))).toBeTruthy();
    expect(screen.getByText(t('i21.upd.status.TASK_OPENED'))).toBeTruthy();
  });
});
