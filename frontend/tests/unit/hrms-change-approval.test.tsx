import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { HrmsChangeApproval } from '@/modules/employee';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * I-21 HRMS Change Approval (plan 16 phase 16a; SRS-1-22, SRS-1-28, SRS-1-61): the tasks with the
 * value locked and masked, the DOB preview, the Maker's order (or "held in HRMS") sent to the
 * Checker, a refusal shown, and the send disabled with its reason. Plan 16 phase 16b (SRS-1-67,
 * SRS-1-68, SRS-1-30, SRS-1-71): one order for the ticked tasks, the overdue flag, the counts
 * and the register export.
 */

afterEach(() => vi.unstubAllGlobals());

const task = (over: object = {}) => ({
  id: 7,
  taskNo: 'HCA-2026-0007',
  eis: '100041',
  employee: 'Asha Rao',
  fieldCode: 'PAN',
  fieldLabel: 'PAN',
  fieldGroup: 'IDENTITY_STATUTORY',
  currentValue: '******234F',
  newValue: '******567Z',
  effectiveDate: '2026-10-20',
  hrmsRequestId: 'HRMS-1',
  source: 'HRMS_PUSH',
  status: 'PENDING_ORDER_DETAILS',
  targetPeriod: '2026-10',
  receivedBeforeFreeze: true,
  receivedAt: '2026-10-21T05:00:00Z',
  daysPending: 5,
  otpRequired: false,
  orderNo: null,
  orderDate: null,
  issuingAuthority: null,
  orderHeldInHrms: false,
  makerUser: null,
  checkerUser: null,
  returnReason: null,
  rejectionReason: null,
  approvalId: null,
  orderRef: null,
  overdue: false,
  hrmsReason: null,
  ...over,
});

const COUNTS = {
  total: 3,
  byStatus: [
    { code: 'PENDING_ORDER_DETAILS', count: 2 },
    { code: 'APPROVED', count: 1 },
  ],
  byField: [{ code: 'PAN', count: 3 }],
};

const MAKER = ['I-21:VIEW', 'I-21:SUBMIT', 'I-21:SUBMIT_OTP', 'I-21:SUBMIT_HQ'];

describe('I-21 HRMS Change Approval', () => {
  it('SRS-G-31: an attached copy is listed with View order and the task is sent without another', async () => {
    const calls = backend({
      'GET /v1/hrms-change-tasks/7/documents': () => [
        {
          id: 3,
          documentTypeName: 'Other document',
          referenceNumber: 'EST/114',
          orderDate: '2026-10-20',
          fileName: 'order.pdf',
          uploadedBy: 'maker.delhi',
          uploadedAt: '2026-10-21T05:00:00Z',
        },
      ],
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/hrms-change-tasks/counts': () => COUNTS,
      'GET /v1/hrms-change-tasks?': (url) => paged([task()], 1, url),
      'GET /v1/hrms-change-tasks/7': () => ({ task: task(), dob: null, applicable: true }),
      'POST /v1/hrms-change-tasks/7/submissions': () => ({ status: 202, body: { approvalId: 91 } }),
    });
    wrap(<HrmsChangeApproval />, me(MAKER));
    await userEvent.click(await screen.findByRole('button', { name: t('i21.w.viewOf', ['HCA-2026-0007']) }));
    const view = await screen.findByRole('link', { name: t('i21.viewOrder') });
    expect(view.getAttribute('href')).toMatch(/\/v1\/hrms-change-tasks\/7\/documents\/3$/);
    const form = screen.getByRole('form', { name: t('i21.order') });
    expect(within(form).getByText(t('i21.copyAttached'))).toBeTruthy();
    await userEvent.type(within(form).getByLabelText(t('i21.orderNo')), 'EST/114');
    await userEvent.type(within(form).getByLabelText(t('i21.orderDate')), '2026-10-20');
    await userEvent.type(within(form).getByLabelText(t('i21.authority')), 'GM (P)');
    await userEvent.click(within(form).getByRole('button', { name: t('i21.submit') }));
    expect(await screen.findByText(t('i21.submitted', ['HCA-2026-0007']))).toBeTruthy();
    expect(calls.some((c) => c.method === 'POST' && c.url.includes('/documents'))).toBe(false);
  });

  it('SRS-LV-17: the Checker sees the capped encashment days and amount before approving', async () => {
    backend({
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/hrms-change-tasks/counts': () => COUNTS,
      'GET /v1/hrms-change-tasks?': (url) => paged([task()], 1, url),
      'GET /v1/hrms-change-tasks/7': () => ({
        task: task(),
        dob: null,
        applicable: true,
        effect: '5 days (capped), amount 10000.00: 60000 / 30 x 5 days (capped from 8)',
      }),
    });
    wrap(<HrmsChangeApproval />, me(MAKER));
    await userEvent.click(await screen.findByRole('button', { name: t('i21.w.viewOf', ['HCA-2026-0007']) }));
    expect(await screen.findByText(t('i21.effect'))).toBeTruthy();
    expect(screen.getByText(/5 days \(capped\), amount 10000\.00/)).toBeTruthy();
  });
  it('US-GAP-276: every task shows its pay impact by month, with or without an arrear', async () => {
    let impact: unknown = {
      appliesFrom: '2026-08',
      paidFrom: '2026-11',
      arrearFrom: '2026-08',
      arrearTo: '2026-10',
    };
    backend({
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/hrms-change-tasks/counts': () => COUNTS,
      'GET /v1/hrms-change-tasks?': (url) => paged([task()], 1, url),
      'GET /v1/hrms-change-tasks/7': () => ({
        task: task(),
        dob: null,
        applicable: true,
        effect: null,
        impact,
      }),
    });
    const view = wrap(<HrmsChangeApproval />, me(MAKER));
    await userEvent.click(await screen.findByRole('button', { name: t('i21.w.viewOf', ['HCA-2026-0007']) }));
    const box = await screen.findByLabelText(t('i21.impact'));
    expect(within(box).getByText(t('i21.impact.applies', ['2026-08', '2026-11']))).toBeTruthy();
    expect(within(box).getByText(t('i21.impact.arrear', ['2026-08', '2026-10']))).toBeTruthy();
    view.unmount();
    impact = { appliesFrom: '2026-11', paidFrom: '2026-11', arrearFrom: null, arrearTo: null };
    wrap(<HrmsChangeApproval />, me(MAKER));
    await userEvent.click(await screen.findByRole('button', { name: t('i21.w.viewOf', ['HCA-2026-0007']) }));
    const none = await screen.findByLabelText(t('i21.impact'));
    expect(within(none).getByText(t('i21.impact.noArrear'))).toBeTruthy();
  });

  it('SRS l.3850, l.3845: the list and the task show the HRMS reason, the request ID, how the task was opened and the order', async () => {
    backend({
      'GET /v1/hrms-change-tasks/counts': () => COUNTS,
      'GET /v1/hrms-change-tasks?': (url) =>
        paged(
          [
            task({ hrmsReason: 'Pay fixed on promotion', source: 'UPDATE_REQUEST_FETCH' }),
            task({
              id: 8,
              taskNo: 'HCA-2026-0008',
              hrmsRequestId: null,
              orderNo: 'EST/9',
              orderDate: '2026-10-02',
              issuingAuthority: 'GM (P)',
            }),
            task({ id: 9, taskNo: 'HCA-2026-0009', orderHeldInHrms: true }),
          ],
          3,
          url,
        ),
      'GET /v1/hrms-change-tasks/7': () => ({
        task: task({ hrmsReason: 'Pay fixed on promotion' }),
        dob: null,
        applicable: true,
      }),
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/document-types': () => [],
    });
    wrap(<HrmsChangeApproval />, me(['I-21:VIEW']));
    expect(await screen.findByText('Pay fixed on promotion')).toBeTruthy();
    expect(screen.getAllByText(t('i21.source.UPDATE_REQUEST_FETCH'))[0]).toBeTruthy();
    expect(screen.getAllByText(t('i21.source.HRMS_PUSH')).length).toBe(4);
    expect(screen.getByText(/EST\/9 · 02-Oct-2026 · GM \(P\)|EST\/9 · .* · GM \(P\)/)).toBeTruthy();
    expect(screen.getByText(t('i21.orderInHrms'))).toBeTruthy();
    const headers = screen.getAllByRole('columnheader').map((h) => h.textContent ?? '');
    expect(headers.some((h) => h.includes(t('i21.reason')))).toBe(true);
    expect(headers.some((h) => h.includes(t('i21.hrmsRequest')))).toBe(true);
    // A task without a reason or a request shows a dash, never an empty cell.
    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
    await userEvent.click(screen.getByRole('button', { name: t('i21.w.viewOf', ['HCA-2026-0007']) }));
    const reasons = await screen.findAllByText('Pay fixed on promotion');
    expect(reasons.length).toBe(2);
  });

  it('SRS-1-28: the task shows the locked, masked value; the maker sends it with the order', async () => {
    const calls = backend({
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/hrms-change-tasks/counts': () => COUNTS,
      'GET /v1/hrms-change-tasks?': (url) => paged([task()], 1, url),
      'GET /v1/hrms-change-tasks/7': () => ({ task: task(), dob: null, applicable: true }),
      'POST /v1/hrms-change-tasks/7/documents': () => ({ status: 201, body: { id: 3 } }),
      'POST /v1/hrms-change-tasks/7/submissions': () => ({ status: 202, body: { approvalId: 90 } }),
    });
    wrap(<HrmsChangeApproval />, me(MAKER));
    expect(await screen.findByText(/\*{6}567Z/)).toBeTruthy();
    expect(screen.getByText(t('i21.daysPending', [5]))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('i21.w.viewOf', ['HCA-2026-0007']) }));
    const form = await screen.findByRole('form', { name: t('i21.order') });
    const send = within(form).getByRole('button', { name: t('i21.submit') });
    expect(send).toBeDisabled();
    await userEvent.type(within(form).getByLabelText(t('i21.orderNo')), 'EST/114');
    await userEvent.type(within(form).getByLabelText(t('i21.orderDate')), '2026-10-20');
    await userEvent.type(within(form).getByLabelText(t('i21.authority')), 'GM (P)');
    // SRS-G-31 (US-GAP-5): an individual order goes with its copy.
    expect(send).toBeDisabled();
    await userEvent.selectOptions(within(form).getByLabelText(t('ord.type')), 'OTHER');
    await userEvent.upload(
      within(form).getByLabelText(t('i21.copy')),
      new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }),
    );
    await userEvent.click(send);
    expect(await screen.findByText(t('i21.submitted', ['HCA-2026-0007']))).toBeTruthy();
    expect(sent(calls, 'POST', '/submissions')).toEqual([
      { orderNo: 'EST/114', orderDate: '2026-10-20', issuingAuthority: 'GM (P)', heldInHrms: false },
    ]);
    const copy = calls.find((c) => c.method === 'POST' && c.url.includes('/documents'))?.body as FormData;
    expect(copy.get('reference')).toBe('EST/114');
    expect((copy.get('file') as File).name).toBe('order.pdf');
  });

  it('SRS-1-22: a DOB task shows the recalculated dates; held in HRMS is sent without an order; a refusal is shown', async () => {
    const dob = task({ fieldCode: 'DATE_OF_BIRTH', fieldLabel: 'Date of birth' });
    const calls = backend({
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/hrms-change-tasks/counts': () => COUNTS,
      'GET /v1/hrms-change-tasks?': (url) => paged([dob], 1, url),
      'GET /v1/hrms-change-tasks/7': () => ({
        task: dob,
        applicable: true,
        dob: {
          currentBirthDate: '1966-01-10',
          newBirthDate: '1962-05-20',
          currentRetirement: '2026-01-31',
          newRetirement: '2022-05-31',
          epsCutoff: '2020-05-20',
          retirementPast: true,
          retiresSoon: false,
          retirementAgeConfigured: true,
        },
      }),
      'POST /v1/hrms-change-tasks/7/submissions': () =>
        problem(422, 'HRC-422-NOT-CONFIGURED', ['RETIREMENT_AGE']),
    });
    wrap(<HrmsChangeApproval />, me(MAKER));
    await userEvent.click(await screen.findByRole('button', { name: t('i21.w.viewOf', ['HCA-2026-0007']) }));
    expect(await screen.findByText(t('i21.dob.past'))).toBeTruthy();
    const form = screen.getByRole('form', { name: t('i21.order') });
    await userEvent.click(within(form).getByLabelText(t('i21.heldInHrms')));
    await userEvent.click(within(form).getByRole('button', { name: t('i21.submit') }));
    expect(await within(form).findByRole('alert')).toHaveTextContent(
      t('error.HRC-422-NOT-CONFIGURED', ['RETIREMENT_AGE']),
    );
    expect(sent(calls, 'POST', '/submissions')).toEqual([{ heldInHrms: true }]);
  });

  it('SRS-1-22: without the retirement age the DOB preview says it is not configured; a soon retirement alerts', async () => {
    const dob = task({
      fieldCode: 'DATE_OF_BIRTH',
      fieldLabel: 'Date of birth',
      returnReason: 'Attach the order',
    });
    backend({
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/hrms-change-tasks/counts': () => COUNTS,
      'GET /v1/hrms-change-tasks?': (url) => paged([dob], 1, url),
      'GET /v1/hrms-change-tasks/7': () => ({
        task: dob,
        applicable: true,
        dob: {
          currentBirthDate: '1966-01-10',
          newBirthDate: '1966-11-20',
          currentRetirement: null,
          newRetirement: null,
          epsCutoff: '2024-11-20',
          retirementPast: false,
          retiresSoon: false,
          retirementAgeConfigured: false,
        },
      }),
    });
    const view = wrap(<HrmsChangeApproval />, me(MAKER));
    await userEvent.click(await screen.findByRole('button', { name: t('i21.w.viewOf', ['HCA-2026-0007']) }));
    expect(await screen.findByText(t('i21.dob.notConfigured'))).toBeTruthy();
    expect(screen.getByText('Attach the order')).toBeTruthy();
    view.unmount();
    backend({
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/hrms-change-tasks/counts': () => COUNTS,
      'GET /v1/hrms-change-tasks?': (url) => paged([dob], 1, url),
      'GET /v1/hrms-change-tasks/7': () => ({
        task: { ...dob, otpRequired: true, rejectionReason: 'Mismatch', receivedBeforeFreeze: false },
        applicable: true,
        dob: {
          currentBirthDate: '1966-01-10',
          newBirthDate: '1966-11-20',
          currentRetirement: null,
          newRetirement: '2026-11-30',
          epsCutoff: '2024-11-20',
          retirementPast: false,
          retiresSoon: true,
          retirementAgeConfigured: true,
        },
      }),
    });
    wrap(<HrmsChangeApproval />, me(MAKER));
    await userEvent.click(await screen.findByRole('button', { name: t('i21.w.viewOf', ['HCA-2026-0007']) }));
    expect(await screen.findByText(t('i21.dob.soon'))).toBeTruthy();
    expect(screen.getByText(t('i21.otpAtApproval'))).toBeTruthy();
    expect(screen.getByText('Mismatch')).toBeTruthy();
    expect(screen.getAllByText(t('i21.afterFreeze', ['2026-10']))[0]).toBeTruthy();
  });

  it('SRS-1-28: a task with the Checker or a field without a column cannot be sent, and says why; a viewer has no form', async () => {
    backend({
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/hrms-change-tasks/counts': () => COUNTS,
      'GET /v1/hrms-change-tasks?': (url) => paged([task({ status: 'PENDING_CHECKER' })], 1, url),
      'GET /v1/hrms-change-tasks/7': () => ({
        task: task({ status: 'PENDING_CHECKER' }),
        dob: null,
        applicable: true,
      }),
    });
    const view = wrap(<HrmsChangeApproval />, me(MAKER));
    await userEvent.click(await screen.findByRole('button', { name: t('i21.w.viewOf', ['HCA-2026-0007']) }));
    expect(await screen.findByRole('button', { name: t('i21.submit') })).toHaveAttribute(
      'title',
      t('i21.why.state', [t('i21.status.PENDING_CHECKER')]),
    );
    view.unmount();
    backend({
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/hrms-change-tasks/counts': () => COUNTS,
      'GET /v1/hrms-change-tasks?': (url) => paged([task({ fieldCode: 'CATEGORY' })], 1, url),
      'GET /v1/hrms-change-tasks/7': () => ({
        task: task({ fieldCode: 'CATEGORY' }),
        dob: null,
        applicable: false,
      }),
    });
    const second = wrap(<HrmsChangeApproval />, me(MAKER));
    await userEvent.click(await screen.findByRole('button', { name: t('i21.w.viewOf', ['HCA-2026-0007']) }));
    expect(await screen.findByRole('button', { name: t('i21.submit') })).toHaveAttribute(
      'title',
      t('i21.why.notApplicable'),
    );
    second.unmount();
    const calls = backend({
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/hrms-change-tasks/counts': () => COUNTS,
      'GET /v1/hrms-change-tasks?': (url) => paged([task()], 1, url),
      'GET /v1/hrms-change-tasks/7': () => ({ task: task(), dob: null, applicable: true }),
    });
    wrap(<HrmsChangeApproval />, me(['I-21:VIEW']));
    await userEvent.selectOptions(await screen.findByLabelText(t('i21.status')), 'APPROVED');
    expect(calls.some((c) => c.url.includes('status=APPROVED'))).toBe(true);
    await userEvent.click(await screen.findByRole('button', { name: t('i21.w.viewOf', ['HCA-2026-0007']) }));
    expect(await screen.findByText(t('i21.locked'))).toBeTruthy();
    expect(screen.queryByRole('form', { name: t('i21.order') })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    expect(screen.queryByText(t('i21.locked'))).toBeNull();
  });

  it('SRS-1-67: the maker ticks two tasks and sends them under one order; one task is not enough', async () => {
    const calls = backend({
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/hrms-change-tasks/counts': () => COUNTS,
      'GET /v1/hrms-change-tasks?': (url) =>
        paged(
          [
            task(),
            task({ id: 8, taskNo: 'HCA-2026-0008', overdue: true }),
            task({ id: 9, taskNo: 'HCA-2026-0009', status: 'PENDING_CHECKER', orderRef: 'HCO-2026-0001' }),
          ],
          3,
          url,
        ),
      'POST /v1/hrms-change-orders/copies': () => ({
        status: 201,
        body: { attachmentId: 12, provisionalKey: 'PROVISIONAL:7c9e6679-7425-40de-944b-e07fc1f90ae7' },
      }),
      'POST /v1/hrms-change-orders': () => ({
        status: 202,
        body: { orderId: 4, orderRef: 'HCO-2026-0004', approvalId: 91, tasks: 2 },
      }),
    });
    wrap(<HrmsChangeApproval />, me([...MAKER, 'I-21:ORDER', 'I-21:ORDER_OTP']));
    expect(await screen.findByText(t('i21.overdue'))).toBeTruthy();
    expect(screen.getByText(t('i21.bulk.under', ['HCO-2026-0001']))).toBeTruthy();
    expect(screen.getByLabelText(t('i21.bulk.pickTask', ['HCA-2026-0009']))).toBeDisabled();
    const bulk = (count: number) => screen.getByRole('button', { name: t('i21.bulk.enter', [count]) });
    await userEvent.click(screen.getByLabelText(t('i21.bulk.pickTask', ['HCA-2026-0007'])));
    expect(bulk(1)).toBeDisabled();
    expect(bulk(1)).toHaveAttribute('title', t('i21.bulk.why'));
    await userEvent.click(screen.getByLabelText(t('i21.bulk.pickTask', ['HCA-2026-0008'])));
    await userEvent.click(screen.getByLabelText(t('i21.bulk.pickTask', ['HCA-2026-0007'])));
    await userEvent.click(screen.getByLabelText(t('i21.bulk.pickTask', ['HCA-2026-0007'])));
    await userEvent.click(bulk(2));
    const form = await screen.findByRole('form', { name: t('i21.bulk.order') });
    const send = within(form).getByRole('button', { name: t('i21.submit') });
    expect(send).toBeDisabled();
    await userEvent.type(within(form).getByLabelText(t('i21.orderNo')), 'EST/TR/31');
    await userEvent.type(within(form).getByLabelText(t('i21.orderDate')), '2026-10-20');
    await userEvent.type(within(form).getByLabelText(t('i21.authority')), 'GM (P)');
    // SRS-1-67 / SRS-G-31: the copy, once for the whole order.
    expect(send).toBeDisabled();
    await userEvent.selectOptions(within(form).getByLabelText(t('ord.type')), 'OTHER');
    await userEvent.upload(
      within(form).getByLabelText(t('i21.copy')),
      new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }),
    );
    await userEvent.click(send);
    expect(await screen.findByText(t('i21.bulk.submitted', ['HCO-2026-0004', 2]))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/hrms-change-orders').at(-1)).toEqual({
      orderNo: 'EST/TR/31',
      orderDate: '2026-10-20',
      issuingAuthority: 'GM (P)',
      taskIds: [8, 7],
      copyAttachmentId: 12,
      copyProvisionalKey: 'PROVISIONAL:7c9e6679-7425-40de-944b-e07fc1f90ae7',
    });
    expect(screen.queryByRole('form', { name: t('i21.bulk.order') })).toBeNull();
    expect(bulk(0)).toBeDisabled();
  });

  it('SRS-1-67 (UI audit #13): a filter change clears the ticks, so a task no longer listed is never sent', async () => {
    backend({
      'GET /v1/hrms-change-tasks/counts': () => COUNTS,
      'GET /v1/hrms-change-tasks?': (url) =>
        paged([task(), task({ id: 8, taskNo: 'HCA-2026-0008' })], 2, url),
    });
    wrap(<HrmsChangeApproval />, me([...MAKER, 'I-21:ORDER']));
    const bulk = (count: number) => screen.getByRole('button', { name: t('i21.bulk.enter', [count]) });
    await userEvent.click(await screen.findByLabelText(t('i21.bulk.pickTask', ['HCA-2026-0007'])));
    await userEvent.click(screen.getByLabelText(t('i21.bulk.pickTask', ['HCA-2026-0008'])));
    expect(bulk(2)).toBeEnabled();
    await userEvent.click(screen.getByLabelText(t('i21.nameDobOnly')));
    expect(bulk(0)).toBeDisabled();
    await userEvent.click(await screen.findByLabelText(t('i21.bulk.pickTask', ['HCA-2026-0007'])));
    await userEvent.selectOptions(screen.getByRole('combobox', { name: t('i21.status') }), 'PENDING_CHECKER');
    expect(bulk(0)).toBeDisabled();
  });

  it('SRS-1-67: a refused order is shown in the dialog; cancel closes it and keeps the ticks', async () => {
    backend({
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/hrms-change-tasks/counts': () => COUNTS,
      'GET /v1/hrms-change-tasks?': (url) =>
        paged([task(), task({ id: 8, taskNo: 'HCA-2026-0008' })], 2, url),
      'POST /v1/hrms-change-orders': () => problem(422, 'HRC-422-ORDER', ['MIXED_UNITS']),
    });
    wrap(<HrmsChangeApproval />, me([...MAKER, 'I-21:ORDER']));
    await userEvent.click(await screen.findByLabelText(t('i21.bulk.pickTask', ['HCA-2026-0007'])));
    await userEvent.click(screen.getByLabelText(t('i21.bulk.pickTask', ['HCA-2026-0008'])));
    await userEvent.click(screen.getByRole('button', { name: t('i21.bulk.enter', [2]) }));
    const form = await screen.findByRole('form', { name: t('i21.bulk.order') });
    await userEvent.type(within(form).getByLabelText(t('i21.orderNo')), 'EST/TR/31');
    await userEvent.type(within(form).getByLabelText(t('i21.orderDate')), '2026-10-20');
    await userEvent.type(within(form).getByLabelText(t('i21.authority')), 'GM (P)');
    await userEvent.type(within(form).getByLabelText(t('i21.bulk.subject')), 'Transfers');
    await userEvent.selectOptions(within(form).getByLabelText(t('ord.type')), 'OTHER');
    await userEvent.upload(
      within(form).getByLabelText(t('i21.copy')),
      new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }),
    );
    await userEvent.click(within(form).getByRole('button', { name: t('i21.submit') }));
    expect(await within(form).findByRole('alert')).toHaveTextContent(
      t('error.HRC-422-ORDER', ['MIXED_UNITS']),
    );
    await userEvent.click(within(form).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('form', { name: t('i21.bulk.order') })).toBeNull();
    expect(screen.getByRole('button', { name: t('i21.bulk.enter', [2]) })).toBeEnabled();
  });

  it('SRS-1-30 / SRS-1-71: the counts by status and field; the export names the register of the filter', async () => {
    const calls = backend({
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/hrms-change-tasks/counts': () => COUNTS,
      'GET /v1/hrms-change-tasks?': (url) => paged([task()], 1, url),
    });
    wrap(<HrmsChangeApproval />, me([...MAKER, 'I-21:EXPORT']));
    expect(await screen.findByText(t('i21.counts', [3]))).toBeTruthy();
    expect(screen.getByText(`${t('i21.status.APPROVED')}:`, { exact: false })).toBeTruthy();
    await userEvent.click(screen.getAllByRole('button', { name: t('action.export') }).at(-1) as HTMLElement);
    expect(screen.getByRole('heading', { name: t('export.title', [t('i21.register.hrms')]) })).toBeTruthy();
    expect(screen.getByRole('link', { name: t('export.download') }).getAttribute('href')).toContain(
      '/v1/hrms-change-tasks/export?register=HRMS&format=XLSX',
    );
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    await userEvent.click(screen.getByLabelText(t('i21.nameDobOnly')));
    expect(calls.some((c) => c.url.includes('counts?nameDob=true'))).toBe(true);
    await userEvent.click(screen.getAllByRole('button', { name: t('action.export') }).at(-1) as HTMLElement);
    expect(screen.getByRole('link', { name: t('export.download') }).getAttribute('href')).toContain(
      'register=NAME_DOB',
    );
    await userEvent.click(screen.getByLabelText(t('i21.nameDobOnly')));
  });
  it('SRS-1-28: a task without a current value, a new value or an HRMS request shows dashes; the status filter goes to the server', async () => {
    const bare = task({ currentValue: null, newValue: null, hrmsRequestId: null });
    const calls = backend({
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'GET /v1/hrms-change-tasks/counts': () => COUNTS,
      'GET /v1/hrms-change-tasks?': (url) => paged([bare], 1, url),
      'GET /v1/hrms-change-tasks/7': () => ({ task: bare, dob: null, applicable: true }),
    });
    wrap(<HrmsChangeApproval />, me(MAKER));
    await userEvent.click(await screen.findByRole('button', { name: t('i21.w.viewOf', ['HCA-2026-0007']) }));
    await screen.findByRole('form', { name: t('i21.order') });
    expect(screen.getAllByText('—', { exact: false }).length).toBeGreaterThanOrEqual(3);
    const status = screen.getAllByLabelText(t('i21.status'))[0] as HTMLSelectElement;
    await userEvent.selectOptions(status, 'APPROVED');
    expect(calls.some((c) => c.url.includes('status=APPROVED'))).toBe(true);
    await userEvent.selectOptions(status, '');
    expect(status.value).toBe('');
  });
});
