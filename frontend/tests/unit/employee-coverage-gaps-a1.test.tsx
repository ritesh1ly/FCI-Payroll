import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { HrmsChangeApproval } from '@/modules/employee';
import { backend, paged, problem, referenceLists, t, wrap } from './support/harness';

/**
 * I-21 HRMS Change Approval, the Checker's and HQ Establishment's paths (SRS-1-28, SRS-1-21 to 1-23,
 * SRS-1-67, SRS-1-71): the row and detail Approve / Reject, the bulk order's Approve, the DOB rows sent to
 * HQ, the decision dialog's cancel, refusal and closed OTP step, and the register / bulk / DOB rows whose
 * HRMS values are absent (a dash, never an empty cell).
 */

afterEach(() => vi.unstubAllGlobals());

const task = (over: object = {}) => ({
  id: 7,
  taskNo: 'HCA-2026-9101',
  eis: '104882',
  employee: 'Rakesh Sharma',
  fieldCode: 'BASIC_PAY',
  fieldLabel: 'Basic Pay',
  fieldGroup: 'SERVICE_PAY',
  currentValue: '62400',
  newValue: '65000',
  effectiveDate: '2026-10-20',
  hrmsRequestId: 'HRMS-PF-88412',
  source: 'HRMS_PUSH',
  status: 'PENDING_CHECKER',
  targetPeriod: '2026-12',
  receivedBeforeFreeze: false,
  receivedAt: '2026-11-04T05:00:00Z',
  daysPending: 3,
  otpRequired: true,
  orderNo: 'EST/311',
  orderDate: '2026-10-20',
  issuingAuthority: 'GM (P)',
  orderHeldInHrms: false,
  makerUser: 'maker.delhi',
  checkerUser: null,
  returnReason: null,
  rejectionReason: null,
  approvalId: 91,
  orderRef: null,
  overdue: false,
  hrmsReason: null,
  ...over,
});

const ORDER = {
  id: 5,
  orderRef: 'HCO-1',
  orderNo: 'EST/HQ/TRF/88',
  orderDate: '2026-10-30',
  authority: 'ED (P)',
  subject: null,
  taskCount: 3,
  status: 'SUBMITTED',
  approvalId: 92,
  ready: 3,
  held: 0,
};

const dobRow = (over: object = {}) => ({
  id: 9,
  taskNo: 'HCA-2026-9109',
  eis: '106004',
  employee: 'Prakash Nayak',
  currentDob: '1973-04-13',
  newDob: '1959-01-03',
  newRetirement: '2019-01-31',
  retirementPast: true,
  retiresSoon: false,
  status: 'PENDING_HQ_ESTABLISHMENT',
  approvalId: 77,
  rejectionReason: null,
  ...over,
});

interface Opts {
  tasks?: object[];
  orders?: object[];
  dob?: object[];
  dobDetail?: object | null;
  register?: object[];
  orderTasks?: object[];
  decision?: () => unknown;
}

function routes(o: Opts = {}) {
  const tasks = o.tasks ?? [task()];
  return backend({
    'GET /v1/hrms-change-tasks/summary': () => ({
      pending: 4,
      beforeFreeze: 1,
      afterFreeze: 3,
      withChecker: 2,
      ageing: 1,
      blocking: 0,
      autoApplied: 6,
    }),
    'GET /v1/hrms-change-tasks/register': (url) => paged(o.register ?? [], (o.register ?? []).length, url),
    'GET /v1/hrms-change-tasks/field-groups': () => [],
    'GET /v1/hrms-change-tasks/dob-tasks': (url) =>
      paged(o.dob ?? [dobRow()], (o.dob ?? [dobRow()]).length, url),
    'GET /v1/hrms-change-orders/5/tasks': (url) =>
      paged(o.orderTasks ?? [], (o.orderTasks ?? []).length, url),
    'GET /v1/hrms-change-orders': (url) => paged(o.orders ?? [ORDER], (o.orders ?? [ORDER]).length, url),
    'GET /v1/hrms-change-tasks/7/documents': () => [],
    'GET /v1/hrms-change-tasks/8/documents': () => [],
    'GET /v1/hrms-change-tasks/9/documents': () => [],
    'GET /v1/hrms-change-tasks/9': () => ({
      task: task({ id: 9, taskNo: 'HCA-2026-9109', hrmsRequestId: null, orderNo: null }),
      dob: o.dobDetail === undefined ? null : o.dobDetail,
      applicable: true,
    }),
    'GET /v1/hrms-change-tasks/8': () => ({ task: tasks[1] ?? task({ id: 8 }), dob: null, applicable: true }),
    'GET /v1/hrms-change-tasks/7': () => ({ task: tasks[0] ?? task(), dob: null, applicable: true }),
    'GET /v1/hrms-change-tasks/counts': () => ({ total: tasks.length, byStatus: [], byField: [] }),
    'GET /v1/hrms-change-tasks?': (url) => paged(tasks, tasks.length, url),
    'GET /v1/hrms-update-requests/fields': () => [],
    'GET /v1/hrms-update-requests': (url) => paged([], 0, url),
    'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: true }],
    'GET /v1/reference/lists': () =>
      referenceLists({ ORDER_COPY_DOCUMENT_TYPE: [['OTHER', 'Other document']] }),
    'POST /v1/hrms-change-tasks/7/documents': () => ({ status: 201, body: { id: 3 } }),
    'POST /v1/approvals/91/decision': o.decision ?? (() => ({ status: 'APPROVED' })),
    'POST /v1/approvals/92/decision': () => ({ status: 'APPROVED' }),
    'POST /v1/approvals/77/decision': () => ({ status: 'APPROVED' }),
  });
}

const CHECKER = ['I-21:VIEW', 'I-21:APPROVE'];
const HQ = ['I-21:VIEW', 'I-21:APPROVE', 'M-01:FLAG'];

async function decideWith(reason: string, decision: 'APPROVE' | 'REJECT' | 'RETURN') {
  const dialog = await screen.findByRole('dialog', { name: /./ });
  await userEvent.type(within(dialog).getByLabelText(t('common.reason')), reason);
  await userEvent.click(within(dialog).getByRole('button', { name: t(`i21.w.decide.${decision}`) }));
  return dialog;
}

describe('I-21 the Checker approves from the row, the detail and the bulk order', () => {
  it('SRS-1-28: the row Approve opens the decision for a task with the Checker; the decision posts and is toasted', async () => {
    const calls = routes();
    wrap(<HrmsChangeApproval />, CHECKER);
    await screen.findAllByText('HCA-2026-9101');
    expect((await screen.findByRole('button', { name: '✔' })).title).toBe(t('wf.approveTitle'));
    await userEvent.click(screen.getByRole('button', { name: '✔' }));
    expect(await screen.findByText(t('i21.w.decisionTitle', ['HCA-2026-9101']))).toBeTruthy();
    await decideWith('Matches the order', 'RETURN');
    await waitFor(() =>
      expect(calls.some((c) => c.method === 'POST' && c.url.includes('/v1/approvals/91/decision'))).toBe(
        true,
      ),
    );
    const body = calls.find((c) => c.url.includes('/v1/approvals/91/decision'))?.body as {
      decision: string;
      reason: string;
    };
    expect(body.decision).toBe('RETURN');
    expect(body.reason).toBe('Matches the order');
    expect(await screen.findByText(t('i21.w.decided', ['HCA-2026-9101']))).toBeTruthy();
  });

  it('SRS-1-28: a task not with the Checker is not decided: the row and the detail toast why', async () => {
    const calls = routes({ tasks: [task({ status: 'PENDING_ORDER_DETAILS', approvalId: null })] });
    wrap(<HrmsChangeApproval />, CHECKER);
    await screen.findAllByText('HCA-2026-9101');
    await waitFor(() =>
      expect((screen.getByRole('button', { name: t('i21.w.reject') }) as HTMLButtonElement).disabled).toBe(
        false,
      ),
    );
    await userEvent.click(screen.getByRole('button', { name: t('i21.w.reject') }));
    expect((await screen.findAllByText(t('i21.w.why.notWithChecker', ['HCA-2026-9101']))).length).toBe(1);
    await userEvent.click(screen.getByRole('button', { name: '✔' }));
    await waitFor(() =>
      expect(screen.getAllByText(t('i21.w.why.notWithChecker', ['HCA-2026-9101'])).length).toBeGreaterThan(0),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('SRS-1-28: Reject from the detail opens the decision; Cancel closes it with nothing sent or toasted', async () => {
    const calls = routes();
    wrap(<HrmsChangeApproval />, CHECKER);
    await screen.findAllByText('EST/311');
    const reject = screen.getByRole('button', { name: t('i21.w.reject') }) as HTMLButtonElement;
    await waitFor(() => expect(reject.disabled).toBe(false));
    await userEvent.click(reject);
    const dialog = await screen.findByRole('dialog');
    const no = within(dialog).getByRole('button', { name: t('i21.w.decide.REJECT') }) as HTMLButtonElement;
    expect(no.disabled).toBe(true);
    expect(no.title).toBe(t('i21.w.why.reason'));
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
    await waitFor(() => expect(screen.queryByText(t('i21.w.decisionTitle', ['HCA-2026-9101']))).toBeNull());
    expect(screen.queryByText(t('i21.w.decided', ['HCA-2026-9101']))).toBeNull();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('R-MC: a refused decision (409) stays open with the error; a closed OTP step shows nothing', async () => {
    let answer: () => unknown = () => problem(409, 'APPROVAL-409-STATE');
    routes({ decision: () => answer() });
    wrap(<HrmsChangeApproval />, CHECKER);
    await screen.findAllByText('EST/311');
    const approve = screen.getByRole('button', { name: t('i21.w.approve') }) as HTMLButtonElement;
    await waitFor(() => expect(approve.disabled).toBe(false));
    await userEvent.click(approve);
    const dialog = await decideWith('Matches the order', 'APPROVE');
    expect(await within(dialog).findByRole('alert')).toBeTruthy();
    expect(screen.queryByText(t('i21.w.decided', ['HCA-2026-9101']))).toBeNull();
    answer = () => problem(428, 'AUTH-428-OTP', ['7']);
    await userEvent.click(within(dialog).getByRole('button', { name: t('i21.w.decide.REJECT') }));
    const otp = await screen.findByRole('dialog', { name: t('otp.title') });
    await userEvent.click(within(otp).getByRole('button', { name: t('common.cancel') }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: t('otp.title') })).toBeNull());
    // The earlier refusal was cleared and the cancelled OTP step is not an error.
    expect(within(dialog).queryByRole('alert')).toBeNull();
    expect(screen.getByText(t('i21.w.decisionTitle', ['HCA-2026-9101']))).toBeTruthy();
  });

  it('SRS-1-67: the bulk order is approved from the toolbar and from its tab; its tasks show their values or a dash', async () => {
    const calls = routes({
      orderTasks: [
        {
          id: 3,
          taskNo: 'HCA-2026-9103',
          eis: '105461',
          employee: 'Amrita Kaur',
          fromValue: null,
          toValue: null,
          status: 'PENDING_ORDER_DETAILS',
          fieldCode: 'PLACE_OF_POSTING',
        },
      ],
    });
    wrap(<HrmsChangeApproval />, CHECKER);
    const top = await screen.findByRole('button', { name: t('i21.w.approveBulk') });
    // Until the latest order arrives the button explains itself (data-guard); then it decides.
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: t('i21.w.approveBulk') }).getAttribute('data-guard'),
      ).toBeNull(),
    );
    expect(top).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('i21.w.tab.bulk') }));
    expect(await screen.findByText('HCA-2026-9103')).toBeTruthy();
    expect(screen.getByText('— → —')).toBeTruthy();
    expect(screen.getAllByText(t('i21.status.PENDING_ORDER_DETAILS')).length).toBeGreaterThan(0);
    const onTab = screen.getAllByRole('button', { name: t('i21.w.approveBulk') });
    expect(onTab).toHaveLength(2);
    await userEvent.click(onTab[1] as HTMLElement);
    expect(await screen.findByText(t('i21.w.decisionTitle', ['EST/HQ/TRF/88']))).toBeTruthy();
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    await userEvent.click(screen.getAllByRole('button', { name: t('i21.w.approveBulk') })[0] as HTMLElement);
    expect(await screen.findByText(t('i21.w.decisionTitle', ['EST/HQ/TRF/88']))).toBeTruthy();
    await decideWith('All tasks match', 'APPROVE');
    await waitFor(() => expect(calls.some((c) => c.url.includes('/v1/approvals/92/decision'))).toBe(true));
    expect(await screen.findByText(t('i21.w.decided', ['EST/HQ/TRF/88']))).toBeTruthy();
  });

  it('SRS-1-67: no order with an approval: the bulk Approve is explained and the tab says there is no order', async () => {
    routes({ orders: [] });
    wrap(<HrmsChangeApproval />, CHECKER);
    await screen.findAllByText('HCA-2026-9101');
    const top = screen.getByRole('button', { name: t('i21.w.approveBulk') });
    expect(top.getAttribute('data-guard')).toBe(t('i21.w.why.noOrder'));
    await userEvent.click(top);
    expect(await screen.findByText(t('i21.w.why.noOrder'))).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('i21.w.tab.bulk') }));
    expect((await screen.findAllByText(t('i21.w.bulk.none'))).length).toBeGreaterThan(0);
    const locked = screen.getAllByRole('button', { name: t('i21.w.approveBulk') });
    expect((locked[locked.length - 1] as HTMLButtonElement).title).toBe(t('i21.w.why.bulkAll'));
    expect(screen.getByText(t('i21.w.bulk.readyText', [0, 0]))).toBeTruthy();
  });
});

describe('I-21 DOB tab: HQ Establishment and the Checker', () => {
  it('SRS-1-22: HQ Establishment approves a past-retirement DOB from its row; dates absent show a dash', async () => {
    const calls = routes({
      dobDetail: {
        currentRetirement: null,
        newRetirement: '2019-01-31',
        epsCutoff: null,
        retirementPast: false,
        retiresSoon: true,
      },
    });
    wrap(<HrmsChangeApproval />, HQ);
    await userEvent.click(await screen.findByRole('button', { name: t('i21.w.tab.dob') }));
    expect(await screen.findByText(t('i21.w.dob.alertYes'))).toBeTruthy();
    expect(screen.getByText(t('i21.w.dob.hqNo'))).toBeTruthy();
    expect(screen.getByText(t('i21.w.dob.orderPending'))).toBeTruthy();
    await userEvent.click(await screen.findByRole('button', { name: t('i21.w.dob.approveHq') }));
    expect(await screen.findByText(t('i21.w.decisionTitle', ['HCA-2026-9109']))).toBeTruthy();
    await decideWith('Retirement confirmed', 'APPROVE');
    await waitFor(() => expect(calls.some((c) => c.url.includes('/v1/approvals/77/decision'))).toBe(true));
  });

  it('SRS-1-21: the Checker approves a DOB row with the Checker; other rows show their status pill', async () => {
    routes({
      dob: [
        dobRow({
          status: 'PENDING_CHECKER',
          approvalId: 78,
          retirementPast: false,
          newDob: null,
          currentDob: null,
        }),
        dobRow({
          id: 10,
          taskNo: 'HCA-2026-9110',
          status: 'APPROVED',
          approvalId: null,
          newRetirement: null,
        }),
      ],
    });
    wrap(<HrmsChangeApproval />, CHECKER);
    await userEvent.click(await screen.findByRole('button', { name: t('i21.w.tab.dob') }));
    expect(await screen.findByText('HCA-2026-9110')).toBeTruthy();
    expect(screen.getAllByText(t('i21.status.APPROVED')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('— → —').length).toBeGreaterThan(0);
    const rowApprove = screen
      .getAllByRole('button', { name: t('i21.w.approve') })
      .find((b) => b.className.includes('sm')) as HTMLElement;
    await userEvent.click(rowApprove);
    expect(await screen.findByText(t('i21.w.decisionTitle', ['HCA-2026-9109']))).toBeTruthy();
  });

  it('SRS-1-22: a viewer sees a pending HQ row as a pill only, with no Approve', async () => {
    routes();
    wrap(<HrmsChangeApproval />, ['I-21:VIEW']);
    await userEvent.click(await screen.findByRole('button', { name: t('i21.w.tab.dob') }));
    expect((await screen.findAllByText(t('i21.status.PENDING_HQ_ESTABLISHMENT'))).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: t('i21.w.dob.approveHq') })).toBeNull();
  });
});

describe('I-21 register, raise and the empty list', () => {
  it('SRS-1-71: a received change shows old and new, its request, order and people; missing values are a dash', async () => {
    routes({
      register: [
        {
          kind: 'TASK',
          reference: 'HCA-2026-9101',
          received: '2026-11-01T05:00:00Z',
          eis: '104882',
          employee: 'Rakesh Sharma',
          field: 'Basic Pay',
          oldValue: null,
          newValue: '65000',
          hrmsRequestId: null,
          effective: '2026-10-20',
          order: 'EST/311',
          maker: 'maker.delhi',
          checker: 'checker.delhi',
          status: 'APPROVED',
          daysPending: 2,
        },
        {
          kind: 'TASK',
          reference: 'HCA-2026-9102',
          received: '2026-11-01T05:00:00Z',
          eis: '104884',
          employee: 'Meera Iyer',
          field: 'Bank account number',
          oldValue: '******1234',
          newValue: null,
          hrmsRequestId: 'HRMS-10',
          effective: null,
          order: null,
          maker: null,
          checker: null,
          status: 'PENDING_ORDER_DETAILS',
          daysPending: 1,
        },
        {
          kind: 'REQUEST',
          reference: 'HUR-1',
          received: '2026-11-01T05:00:00Z',
          eis: '104883',
          employee: 'Asha Rao',
          field: 'Basic Pay',
          oldValue: null,
          newValue: null,
          hrmsRequestId: 'HRMS-9',
          effective: null,
          order: null,
          maker: null,
          checker: null,
          status: 'TASK_OPENED',
          daysPending: null,
        },
      ],
    });
    wrap(<HrmsChangeApproval />, ['I-21:VIEW', 'I-21:EXPORT']);
    await userEvent.click(await screen.findByRole('button', { name: t('i21.w.tab.register') }));
    expect(await screen.findByText('— → 65000')).toBeTruthy();
    expect(screen.getByText('******1234 → —')).toBeTruthy();
    expect(screen.getByText('checker.delhi')).toBeTruthy();
    expect(screen.getByText('HRMS-9')).toBeTruthy();
    expect(screen.getByText(t('i21.upd.status.TASK_OPENED'))).toBeTruthy();
    expect(screen.getAllByText(t('i21.status.APPROVED')).length).toBeGreaterThan(0);
    // With EXPORT the register's own export is a button, not the explained one.
    expect(
      screen.getAllByRole('button', { name: t('m01.w.exportShort') })[0]?.getAttribute('title'),
    ).not.toBe(t('i21.w.why.export'));
  });

  it('SRS-17-15: Raise HRMS Update Request opens the closed section; an empty list picks no task', async () => {
    routes({ tasks: [] });
    wrap(<HrmsChangeApproval />, ['I-21:VIEW', 'I-21:REQUEST']);
    expect((await screen.findAllByText(t('i21.none'))).length).toBeGreaterThan(0);
    expect(screen.getByText(t('i21.w.d.none'))).toBeTruthy();
    const details = document.querySelector('details') as HTMLDetailsElement;
    expect(details.open).toBe(false);
    await userEvent.click(screen.getByRole('button', { name: t('i21.w.raise') }));
    await waitFor(() => expect(details.open).toBe(true));
  });

  it('SRS-1-61: the task number in the closed list opens the task; Close shuts it', async () => {
    routes();
    wrap(<HrmsChangeApproval />, ['I-21:VIEW']);
    await screen.findAllByText('HCA-2026-9101');
    await userEvent.click(screen.getByText(t('i21.w.closed')));
    const link = await waitFor(() => {
      const found = Array.from(document.querySelectorAll('details button.ia')).find(
        (b) => b.textContent === 'HCA-2026-9101',
      );
      expect(found).toBeTruthy();
      return found as HTMLElement;
    });
    await userEvent.click(link);
    expect(await screen.findByRole('dialog', { name: t('i21.task') })).toBeTruthy();
  });
});

describe('I-21 the Maker on the task list and the order card', () => {
  const MAKER = ['I-21:VIEW', 'I-21:SUBMIT'];

  it('SRS-G-31: the Maker attaches the order copy of the picked task with its particulars, each file posted', async () => {
    const calls = routes({ tasks: [task({ status: 'PENDING_ORDER_DETAILS', approvalId: null })] });
    wrap(<HrmsChangeApproval />, MAKER);
    await screen.findAllByText('HCA-2026-9101');
    const card = document.querySelector('[data-order-card]') as HTMLElement;
    const file = card.querySelector('input[type=file]') as HTMLInputElement;
    await waitFor(() => expect(file.disabled).toBe(false));
    await within(card).findByRole('option', { name: 'Other document' });
    fireEvent.change(card.querySelector('input[type=date]') as HTMLInputElement, {
      target: { value: '2026-10-20' },
    });
    // The type in force needs its reference: without it nothing is sent.
    fireEvent.change(within(card).getByPlaceholderText(t('wf.ord.officePlaceholder')), {
      target: { value: 'GM (P), Delhi' },
    });
    fireEvent.change(file, {
      target: { files: [new File(['%PDF'], 'order.pdf', { type: 'application/pdf' })] },
    });
    expect(await within(card).findByText(t('wf.ord.particularsFirst'))).toBeTruthy();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
    fireEvent.change(within(card).getByPlaceholderText(t('wf.ord.referencePlaceholder')), {
      target: { value: 'EST/311' },
    });
    fireEvent.change(file, {
      target: {
        files: [
          new File(['%PDF'], 'order.pdf', { type: 'application/pdf' }),
          new File(['%PDF'], 'annex.pdf', { type: 'application/pdf' }),
        ],
      },
    });
    expect(await within(card).findByText(t('wf.ord.stored', [2]))).toBeTruthy();
    const posts = calls.filter(
      (c) => c.method === 'POST' && c.url.includes('/v1/hrms-change-tasks/7/documents'),
    );
    expect(posts).toHaveLength(2);
  });

  it('SRS-1-61: the Maker edits a task still awaiting the order from its row; the task opens', async () => {
    routes({ tasks: [task({ status: 'PENDING_ORDER_DETAILS', approvalId: null })] });
    wrap(<HrmsChangeApproval />, MAKER);
    const edit = await screen.findByRole('button', { name: t('i21.w.editOf', ['HCA-2026-9101']) });
    expect((edit as HTMLButtonElement).disabled).toBe(false);
    await userEvent.click(edit);
    expect(await screen.findByRole('dialog', { name: t('i21.task') })).toBeTruthy();
  });

  it('SRS-1-28: the decision form is never submitted by Enter; the Checker still chooses a decision', async () => {
    const calls = routes();
    wrap(<HrmsChangeApproval />, CHECKER);
    await userEvent.click(await screen.findByRole('button', { name: '✔' }));
    await screen.findByText(t('i21.w.decisionTitle', ['HCA-2026-9101']));
    const form = document.querySelector('form[aria-labelledby="i21-decision"]') as HTMLFormElement;
    fireEvent.submit(form);
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
    expect(screen.getByText(t('i21.w.decisionTitle', ['HCA-2026-9101']))).toBeTruthy();
  });
});

describe('I-21 the picked task across a search, and slow or missing data', () => {
  it('SRS-1-61: a search that still lists the picked task keeps it in the detail; one that drops it picks the first', async () => {
    const other = task({ id: 8, taskNo: 'HCA-2026-9108', employee: 'Asha Rao', approvalId: null });
    const calls = backend({
      'GET /v1/hrms-change-tasks/summary': () => ({}),
      'GET /v1/hrms-change-orders': (url) => paged([], 0, url),
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/hrms-change-tasks/8/documents': () => [],
      'GET /v1/hrms-change-tasks/8': () => ({ task: other, dob: null, applicable: true }),
      'GET /v1/hrms-change-tasks/7': () => ({ task: task(), dob: null, applicable: true }),
      'GET /v1/hrms-change-tasks/counts': () => ({ total: 2, byStatus: [], byField: [] }),
      'GET /v1/hrms-change-tasks?': (url) =>
        url.includes('q=Asha') ? paged([other], 1, url) : paged([other, task()].reverse(), 2, url),
      'GET /v1/hrms-update-requests/fields': () => [],
      'GET /v1/hrms-update-requests': (url) => paged([], 0, url),
      'GET /v1/document-types': () => [],
    });
    wrap(<HrmsChangeApproval />, ['I-21:VIEW']);
    // The tiles without a summary show a dash.
    expect((await screen.findAllByText('—')).length).toBeGreaterThan(0);
    expect(
      await screen.findByText(t('i21.w.d.title', ['HCA-2026-9101', 'Rakesh Sharma', 'Basic Pay'])),
    ).toBeTruthy();
    await userEvent.type(screen.getByLabelText(t('i21.w.search')), 'Rakesh');
    await waitFor(() => expect(calls.some((c) => c.url.includes('q=Rakesh'))).toBe(true));
    expect(
      screen.getByText(t('i21.w.d.title', ['HCA-2026-9101', 'Rakesh Sharma', 'Basic Pay'])),
    ).toBeTruthy();
    await userEvent.clear(screen.getByLabelText(t('i21.w.search')));
    await userEvent.type(screen.getByLabelText(t('i21.w.search')), 'Asha');
    expect(
      await screen.findByText(t('i21.w.d.title', ['HCA-2026-9108', 'Asha Rao', 'Basic Pay'])),
    ).toBeTruthy();
  });

  it('SRS-1-68: a decided task shows no age and its status pill', async () => {
    routes({ tasks: [task({ status: 'APPROVED', approvalId: null })] });
    wrap(<HrmsChangeApproval />, ['I-21:VIEW']);
    const view = await screen.findByRole('button', { name: t('i21.w.viewOf', ['HCA-2026-9101']) });
    const cells = (view.closest('tr') as HTMLElement).querySelectorAll('td');
    expect(cells[10]?.textContent).toBe(t('i21.w.noAge'));
    expect(cells[11]?.textContent).toBe(t('i21.status.APPROVED'));
    expect(screen.queryByText(t('i21.w.age', [3]))).toBeNull();
  });

  it('SRS-G-31: the order card with a document type unknown to the type list needs no reference', async () => {
    const calls = backend({
      'GET /v1/hrms-change-tasks/summary': () => ({}),
      'GET /v1/hrms-change-orders': (url) => paged([], 0, url),
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/hrms-change-tasks/7': () => ({ task: task(), dob: null, applicable: true }),
      'GET /v1/hrms-change-tasks/counts': () => ({ total: 1, byStatus: [], byField: [] }),
      'GET /v1/hrms-change-tasks?': (url) => paged([task({ status: 'PENDING_ORDER_DETAILS' })], 1, url),
      'GET /v1/hrms-update-requests/fields': () => [],
      'GET /v1/hrms-update-requests': (url) => paged([], 0, url),
      'GET /v1/document-types': () => [],
      'GET /v1/reference/lists': () =>
        referenceLists({ ORDER_COPY_DOCUMENT_TYPE: [['OTHER', 'Other document']] }),
      'POST /v1/hrms-change-tasks/7/documents': () => ({ status: 201, body: { id: 4 } }),
    });
    wrap(<HrmsChangeApproval />, ['I-21:VIEW', 'I-21:SUBMIT']);
    await screen.findAllByText('HCA-2026-9101');
    const card = document.querySelector('[data-order-card]') as HTMLElement;
    await within(card).findByRole('option', { name: 'Other document' });
    const file = card.querySelector('input[type=file]') as HTMLInputElement;
    await waitFor(() => expect(file.disabled).toBe(false));
    fireEvent.change(card.querySelector('input[type=date]') as HTMLInputElement, {
      target: { value: '2026-10-20' },
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

  it('R-INT: the task opened while its detail, document types and copies fail (500) shows the error and no form', async () => {
    backend({
      'GET /v1/hrms-change-tasks/summary': () => ({}),
      'GET /v1/hrms-change-orders': (url) => paged([], 0, url),
      'GET /v1/hrms-change-tasks/7/documents': () => problem(500, 'SYS-500'),
      'GET /v1/hrms-change-tasks/7': () => problem(500, 'SYS-500'),
      'GET /v1/hrms-change-tasks/counts': () => ({ total: 1, byStatus: [], byField: [] }),
      'GET /v1/hrms-change-tasks?': (url) => paged([task({ status: 'PENDING_ORDER_DETAILS' })], 1, url),
      'GET /v1/hrms-update-requests/fields': () => [],
      'GET /v1/hrms-update-requests': (url) => paged([], 0, url),
      'GET /v1/document-types': () => problem(500, 'SYS-500'),
    });
    wrap(<HrmsChangeApproval />, ['I-21:VIEW', 'I-21:SUBMIT']);
    await userEvent.click(await screen.findByRole('button', { name: t('i21.w.viewOf', ['HCA-2026-9101']) }));
    const dialog = await screen.findByRole('dialog', { name: t('i21.task') });
    expect(await within(dialog).findByRole('alert')).toBeTruthy();
    expect(within(dialog).queryByRole('form', { name: t('i21.order') })).toBeNull();
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: t('i21.task') })).toBeNull());
  });

  it('R-INT: the order forms with the type list and the copies unavailable offer no type; Enter sends nothing', async () => {
    const calls = backend({
      'GET /v1/hrms-change-tasks/summary': () => ({}),
      'GET /v1/hrms-change-orders': (url) => paged([], 0, url),
      'GET /v1/hrms-change-tasks/7/documents': () => problem(500, 'SYS-500'),
      'GET /v1/hrms-change-tasks/7': () => ({
        task: task({ status: 'PENDING_ORDER_DETAILS' }),
        dob: null,
        applicable: true,
      }),
      'GET /v1/hrms-change-tasks/counts': () => ({ total: 1, byStatus: [], byField: [] }),
      'GET /v1/hrms-change-tasks?': (url) =>
        paged(
          [
            task({ status: 'PENDING_ORDER_DETAILS' }),
            task({ id: 8, taskNo: 'HCA-2026-9108', status: 'PENDING_ORDER_DETAILS' }),
          ],
          2,
          url,
        ),
      'GET /v1/hrms-change-tasks/8/documents': () => [],
      'GET /v1/hrms-update-requests/fields': () => [],
      'GET /v1/hrms-update-requests': (url) => paged([], 0, url),
      'GET /v1/document-types': () => problem(500, 'SYS-500'),
    });
    wrap(<HrmsChangeApproval />, ['I-21:VIEW', 'I-21:SUBMIT', 'I-21:ORDER']);
    await userEvent.click(await screen.findByRole('button', { name: t('i21.w.viewOf', ['HCA-2026-9101']) }));
    const form = await screen.findByRole('form', { name: t('i21.order') });
    const types = within(form).getByLabelText(t('ord.type')) as HTMLSelectElement;
    expect(Array.from(types.options).map((o) => o.value)).toEqual(['']);
    expect((within(form).getByRole('button', { name: t('i21.submit') }) as HTMLButtonElement).disabled).toBe(
      true,
    );
    fireEvent.submit(form);
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
    const task7 = screen.getByRole('dialog', { name: t('i21.task') });
    await userEvent.click(within(task7).getByRole('button', { name: t('common.close') }));
    // The bulk order dialog: with no type list it offers no type, and an incomplete order is never sent.
    await userEvent.click(screen.getByText(t('i21.w.closed')));
    await userEvent.click(await screen.findByLabelText(t('i21.bulk.pickTask', ['HCA-2026-9101'])));
    await userEvent.click(screen.getByLabelText(t('i21.bulk.pickTask', ['HCA-2026-9108'])));
    await userEvent.click(screen.getByRole('button', { name: t('i21.bulk.enter', [2]) }));
    const bulk = await screen.findByRole('form', { name: t('i21.bulk.order') });
    const bulkTypes = within(bulk).getByLabelText(t('ord.type')) as HTMLSelectElement;
    expect(Array.from(bulkTypes.options).map((o) => o.value)).toEqual(['']);
    fireEvent.submit(bulk);
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });
});
