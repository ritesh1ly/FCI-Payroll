import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EmployeeMaster, HrmsChangeApproval, LifecycleEvents, StaffTransfers } from '@/modules/employee';
import { copyPill, copyState, dobRule } from '@/modules/employee/business/hrmsChangeRules';
import { eventDetails, eventEffect, eventSource, taskPill } from '@/modules/employee/business/eventRules';
import type { ReceivedEvent } from '@/modules/employee/api/registerApi';
import { backend, paged, t, wrap } from './support/harness';

/**
 * The Employee menu as Wireframes v6.1 draws it (OD-121; SRS-G-54): M-01, I-17, M-11 and I-21 show every drawn object
 * for each role, a role without the right sees the action disabled with its reason (R-RBAC), nothing HRMS-owned is
 * keyed (R-SRC), and the HRMS read of M-01 comes from the API (SRS-1-70).
 */
afterEach(() => vi.unstubAllGlobals());

const employee = {
  eis: '104882',
  name: 'Rakesh Sharma',
  designation: 'Manager',
  band: 'E-4',
  location: 'DO — Delhi HQ (DH001)',
  payPattern: 'IDA',
  employeeType: 'STAFF',
  status: 'ACTIVE',
  payScale: 'E-4',
  basic: 62400,
  city: 'X',
  cpfCode: 'CPF/104882',
  bank: 'SBI',
};

const detail = {
  eis: '104882',
  firstName: 'Rakesh',
  lastName: 'Sharma',
  panMasked: 'XXXXXX234K',
  currentBasic: 62400,
  status: 'ACTIVE',
  employmentStatus: 'IN_SERVICE',
  employeeType: 'STAFF',
};

function masterRoutes(extras: object, over: object = {}) {
  return backend({
    'GET /v1/employees/offices': () => [{ code: 'DH001', label: 'DO — Delhi HQ (DH001)' }],
    'GET /v1/employees/counts': () => ({
      total: 7,
      payrollSetupPending: 1,
      changePending: 1,
      panCpfMissing: 1,
      bankInvalid: 1,
      active: 3,
    }),
    'GET /v1/employees/104882/hrms-record': () => extras,
    'GET /v1/employees/104882/member-ids': (url) =>
      paged(
        [
          {
            idType: 'CPF',
            memberId: 'CPF/104882',
            category: 'CAT-I',
            zone: 'North Zone',
            effectiveFrom: '2011-04-01',
            effectiveTo: null,
            status: 'ACTIVE',
            reason: null,
          },
        ],
        1,
        url,
      ),
    'GET /v1/employees/104882/staff-numbers': (url) =>
      paged(
        [
          {
            oldNumber: null,
            newNumber: '099882',
            origin: null,
            destination: 'DO Mumbai',
            effectiveDate: '2004-01-02',
            event: 'ORIGINAL',
          },
        ],
        1,
        url,
      ),
    'GET /v1/employees/104882/documents': () => [],
    'GET /v1/employees/104882': () => ({ ...detail, ...over }),
    'GET /v1/employees?': (url) => paged([employee], 1, url),
    'GET /v1/document-types': () => [],
  });
}

describe('M-01 as drawn', () => {
  it('SRS-1-70: Record Detail shows what HRMS holds now, read through the API, beside the record', async () => {
    const calls = masterRoutes({
      cadre: 'Executive',
      payScaleCode: 'E-4',
      oldBasic: 60600,
      bankCode: 'SBI',
      hrmsAvailable: true,
      hrmsBasic: 65000,
      hrmsStatus: 'ACTIVE',
    });
    wrap(<EmployeeMaster />, ['M-01:VIEW']);
    expect(await screen.findByTestId('m01-hrms-read')).toBeTruthy();
    expect((await screen.findByTestId('m01-hrms-read')).textContent).toContain('ACTIVE');
    expect(calls.some((c) => c.url.includes('/v1/employees/104882/hrms-record'))).toBe(true);
  });

  it('SRS-1-70: an HRMS that cannot be read says so and the record still shows', async () => {
    masterRoutes({ hrmsAvailable: false });
    wrap(<EmployeeMaster />, ['M-01:VIEW']);
    await screen.findByText('Rakesh Sharma');
    expect(await screen.findByText(t('m01.w.d.hrmsDown'))).toBeTruthy();
  });

  it('R-RBAC: a viewer sees Edit Record, Bulk update and Unmask disabled with the reason, every object drawn', async () => {
    masterRoutes({ hrmsAvailable: false });
    wrap(<EmployeeMaster />, ['M-01:VIEW']);
    await screen.findByText('Rakesh Sharma');
    const edit = screen.getByRole('button', { name: t('m01.w.editRecord') }) as HTMLButtonElement;
    expect(edit.disabled).toBe(true);
    expect(edit.title).toBe(t('m01.w.why.edit'));
    expect((screen.getByRole('button', { name: t('m01.w.bulk') }) as HTMLButtonElement).disabled).toBe(true);
    const unmask = (await screen.findByRole('button', { name: t('m01.w.unmask') })) as HTMLButtonElement;
    expect(unmask.title).toBe(t('m01.w.why.unmask'));
    for (const title of [
      'm01.w.val.title',
      'm01.w.join.title',
      'm01.w.retain.title',
      'm01.w.anchor.title',
      'm01.w.rules.title',
    ]) {
      expect(screen.getByRole('heading', { name: t(title) })).toBeTruthy();
    }
    expect(screen.getByRole('option', { name: t('m01.w.allOffices') })).toBeTruthy();
    expect((await screen.findAllByRole('cell', { name: 'CPF/104882' })).length).toBeGreaterThan(0);
  });

  it('SRS-G-45: Establishment / HR edits from the row; a press on Cancel explains and sends nothing', async () => {
    const calls = masterRoutes({ hrmsAvailable: false }, { transferredOut: '2026-01-01' });
    wrap(<EmployeeMaster />, ['M-01:VIEW', 'M-01:FLAG']);
    await screen.findByText('Rakesh Sharma');
    const before = calls.length;
    await userEvent.click(screen.getByRole('button', { name: t('wf.cancel') }));
    expect(await screen.findByText(t('m01.w.why.cancel'))).toBeTruthy();
    expect(calls.slice(before).filter((c) => c.method !== 'GET')).toHaveLength(0);
    await userEvent.click(screen.getByRole('button', { name: t('m01.w.editOf', ['104882']) }));
    expect(await screen.findByRole('dialog')).toBeTruthy();
  });

  it('SRS-1-11: the office filter goes to the server with the tiles', async () => {
    const calls = masterRoutes({ hrmsAvailable: false });
    wrap(<EmployeeMaster />, ['M-01:VIEW']);
    await screen.findByRole('option', { name: 'DO — Delhi HQ (DH001)' });
    await userEvent.selectOptions(screen.getByLabelText(t('m01.w.allOffices')), 'DH001');
    await vi.waitFor(() => {
      expect(calls.some((c) => c.url.includes('/v1/employees?') && c.url.includes('office=DH001'))).toBe(
        true,
      );
      expect(
        calls.some((c) => c.url.includes('/v1/employees/counts') && c.url.includes('office=DH001')),
      ).toBe(true);
    });
  });
});

const event: ReceivedEvent = {
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
};

const ORDER = {
  id: 91,
  fileName: 'order-9107.pdf',
  documentTypeCode: 'SANCTION_ORDER',
  documentTypeName: 'Sanction order',
  referenceNumber: 'EST/ORD/2026-9107',
  uploadedBy: 'maker.delhi@fci-demo.test',
  uploadedAt: '2026-10-26T05:00:00Z',
};

describe('I-17 as drawn', () => {
  it('SRS-1-63: the events show source, pay effect, task and status; a role without export is told why', async () => {
    backend({
      'GET /v1/lifecycle-events/received': (url) => paged([event], 1, url),
      'GET /v1/lifecycle-events': (url) => paged([], 0, url),
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/document-types': () => [],
    });
    wrap(<LifecycleEvents />, ['I-17:VIEW']);
    expect(await screen.findByText('HCA-2026-9107')).toBeTruthy();
    expect(screen.getByText(t('evt.w.detail.subsistence', [50]))).toBeTruthy();
    expect(screen.getByText(t('evt.w.source.HRMS'))).toBeTruthy();
    expect(screen.getByText(t('evt.w.status.APPROVED'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('evt.w.report') }));
    expect(await screen.findByText(t('evt.w.why.export'))).toBeTruthy();
  });

  it('SRS-G-31: the order card opens on the first event whose task has its order, not a task still waiting for one', async () => {
    const newest = { ...event, id: 3, taskId: null, taskNo: null, taskStatus: null };
    const waiting = {
      ...event,
      id: 2,
      taskId: 6,
      taskNo: 'HCA-2026-9119',
      taskStatus: 'PENDING_ORDER_DETAILS',
    };
    const calls = backend({
      'GET /v1/lifecycle-events/received': (url) => paged([newest, waiting, event], 3, url),
      'GET /v1/lifecycle-events': (url) => paged([], 0, url),
      'GET /v1/hrms-change-tasks/7/documents': () => [ORDER],
      'GET /v1/document-types': () => [],
    });
    wrap(<LifecycleEvents />, ['I-17:VIEW']);
    expect(await screen.findByText('order-9107.pdf')).toBeTruthy();
    expect(screen.getByRole('link', { name: t('wf.view') })).toBeTruthy();
    expect(calls.some((c) => c.url.includes('/hrms-change-tasks/6/'))).toBe(false);
  });

  it('SRS-G-31: with only waiting tasks the card is on the first of them, with no task on the first row; a click still picks', async () => {
    const plain = { ...event, id: 3, taskId: null, taskNo: 'none', taskStatus: null };
    const waiting = {
      ...event,
      id: 2,
      taskId: 6,
      taskNo: 'HCA-2026-9119',
      taskStatus: 'PENDING_ORDER_DETAILS',
    };
    const calls = backend({
      'GET /v1/lifecycle-events/received': (url) => paged([plain, waiting, event], 3, url),
      'GET /v1/lifecycle-events': (url) => paged([], 0, url),
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/hrms-change-tasks/6/documents': () => [],
      'GET /v1/document-types': () => [],
    });
    const view = wrap(<LifecycleEvents />, ['I-17:VIEW']);
    await userEvent.click(await screen.findByText('HCA-2026-9119'));
    await vi.waitFor(() => expect(calls.some((c) => c.url.includes('/hrms-change-tasks/6/'))).toBe(true));
    view.unmount();
    const only = backend({
      'GET /v1/lifecycle-events/received': (url) => paged([plain, waiting], 2, url),
      'GET /v1/lifecycle-events': (url) => paged([], 0, url),
      'GET /v1/hrms-change-tasks/6/documents': () => [],
      'GET /v1/document-types': () => [],
    });
    wrap(<LifecycleEvents />, ['I-17:VIEW']);
    await vi.waitFor(() => expect(only.some((c) => c.url.includes('/hrms-change-tasks/6/'))).toBe(true));
  });

  it('SRS-1-63: the order fields are read-only for every role on I-17', async () => {
    backend({
      'GET /v1/lifecycle-events/received': (url) => paged([{ ...event, taskId: null }], 1, url),
      'GET /v1/lifecycle-events': (url) => paged([], 0, url),
      'GET /v1/document-types': () => [],
    });
    wrap(<LifecycleEvents />, ['I-17:VIEW', 'I-17:EXPORT']);
    await screen.findByText('HCA-2026-9107');
    expect((document.querySelector('input[type="date"]') as HTMLInputElement).disabled).toBe(true);
    expect(screen.getByRole('button', { name: t('evt.w.report') })).toBeTruthy();
  });

  it('the rules of the list: effect, details, source and pill for each case', () => {
    expect(eventEffect('SUSPENSION')).toBe('SUSPENSION');
    expect(eventEffect('HOLD')).toBe('NONE');
    expect(eventDetails(event).key).toBe('evt.w.detail.subsistence');
    expect(eventDetails({ ...event, subsistencePercent: null, interveningPeriod: 'Duty' }).args).toEqual([
      'Enquiry',
      'Duty',
    ]);
    expect(
      eventDetails({ ...event, subsistencePercent: null, reason: null, interveningPeriod: 'Duty' }).args,
    ).toEqual(['', 'Duty']);
    expect(eventDetails({ ...event, subsistencePercent: null, releaseFrom: '202608' }).args).toEqual([
      '202608',
      '202608',
    ]);
    expect(
      eventDetails({ ...event, subsistencePercent: null, releaseFrom: '202608', releaseTo: '202609' }).args,
    ).toEqual(['202608', '202609']);
    expect(eventDetails({ ...event, subsistencePercent: null }).args).toEqual(['Enquiry']);
    expect(eventDetails({ ...event, subsistencePercent: null, reason: null }).args).toEqual(['—']);
    expect(eventSource(event).key).toBe('evt.w.source.HRMS');
    expect(eventSource({ ...event, source: 'HRMS_FALLBACK_UPLOAD' }).args).toEqual(['HRMS-SUS-0044']);
    expect(eventSource({ ...event, source: 'HRMS_FALLBACK_UPLOAD', hrmsRequestId: null }).args).toEqual([
      '—',
    ]);
    expect(taskPill('APPROVED')).toBe('p-ok');
    expect(taskPill('AUTO_APPLIED')).toBe('p-ok');
    expect(taskPill('REJECTED_RETURNED_TO_HRMS')).toBe('p-bad');
    expect(taskPill(null)).toBe('p-gray');
    expect(taskPill('PENDING_CHECKER')).toBe('p-warn');
  });
});

describe('M-11 as drawn', () => {
  const row = {
    id: 1,
    panMasked: 'XXXXXX234K',
    eis: '105461',
    employee: 'Amrita Kaur',
    oldStaffNo: '105461',
    newStaffNo: null,
    origin: 'DO — Delhi HQ (DH001)',
    destination: 'DO — Mumbai Regional (MR001)',
    cpfMemberId: 'CPF/105461',
    effectiveDate: '2026-11-17',
    taskId: 7,
    taskNo: 'HCA-2026-9103',
    taskStatus: 'PENDING_CHECKER',
    approvalStatus: null,
  };

  it('SRS l.1343: the register with PAN as the anchor, the CPF ID unchanged and the tiles from the API', async () => {
    backend({
      'GET /v1/transfers/summary': () => ({ period: '2026-11', received: 19, reissued: 15, withChecker: 4 }),
      'GET /v1/transfers/register': (url) => paged([row], 1, url),
      'GET /v1/transfers': (url) => paged([], 0, url),
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/document-types': () => [],
    });
    wrap(<StaffTransfers />, ['M-11:VIEW']);
    expect(await screen.findByText('HCA-2026-9103')).toBeTruthy();
    expect(screen.getByText(t('trf.w.unchangedId', ['CPF/105461']))).toBeTruthy();
    expect(await screen.findByText('19')).toBeTruthy();
    expect(screen.queryByText(t('trf.record'))).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('m01.w.exportShort') }));
    expect(await screen.findByText(t('trf.w.why.export'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('trf.w.serviceRegister') }));
    expect(await screen.findByText(t('trf.w.why.service'))).toBeTruthy();
  });
});

describe('M-11 order card', () => {
  const row = {
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
    taskStatus: 'PENDING_CHECKER',
    approvalStatus: null,
  };

  it.each([
    ['REJECTED', 'status.REJECTED'],
    ['RETURNED', 'status.RETURNED'],
    ['PENDING_APPROVAL', 'status.PENDING_APPROVAL'],
  ])(
    'SRS-G-1: a transfer raised on M-11 has no I-21 task, so its own decision %s is its status',
    async (approvalStatus, label) => {
      backend({
        'GET /v1/transfers/summary': () => ({ period: '2026-11', received: 1, reissued: 0, withChecker: 0 }),
        'GET /v1/transfers/register': (url) =>
          paged([{ ...row, id: 3, taskId: null, taskNo: null, taskStatus: null, approvalStatus }], 1, url),
        'GET /v1/transfers': (url) => paged([], 0, url),
        'GET /v1/document-types': () => [],
      });
      wrap(<StaffTransfers />, ['M-11:VIEW']);
      expect(await screen.findByText(t(label))).toBeTruthy();
      expect(screen.queryByText(t('evt.w.status.NONE'))).toBeNull();
    },
  );

  it('SRS-1-69: a transfer with neither a task nor a decision says it has none', async () => {
    backend({
      'GET /v1/transfers/summary': () => ({ period: '2026-11', received: 1, reissued: 0, withChecker: 0 }),
      'GET /v1/transfers/register': (url) =>
        paged([{ ...row, id: 3, taskId: null, taskNo: null, taskStatus: null }], 1, url),
      'GET /v1/transfers': (url) => paged([], 0, url),
      'GET /v1/document-types': () => [],
    });
    wrap(<StaffTransfers />, ['M-11:VIEW']);
    expect(await screen.findByText(t('evt.w.status.NONE'))).toBeTruthy();
  });

  it('SRS-G-31: opens on the first transfer with a task that has its order', async () => {
    backend({
      'GET /v1/transfers/summary': () => ({ period: '2026-11', received: 2, reissued: 0, withChecker: 1 }),
      'GET /v1/transfers/register': (url) =>
        paged([{ ...row, id: 3, taskId: null, taskNo: null, taskStatus: null }, row], 2, url),
      'GET /v1/transfers': (url) => paged([], 0, url),
      'GET /v1/hrms-change-tasks/7/documents': () => [ORDER],
      'GET /v1/document-types': () => [],
    });
    wrap(<StaffTransfers />, ['M-11:VIEW']);
    expect(await screen.findByText('order-9107.pdf')).toBeTruthy();
  });
});

describe('I-21 as drawn', () => {
  const task = {
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
  };

  function routes() {
    return backend({
      'GET /v1/hrms-change-tasks/summary': () => ({
        pending: 46,
        beforeFreeze: 5,
        afterFreeze: 41,
        withChecker: 12,
        ageing: 5,
        blocking: 5,
        autoApplied: 64,
      }),
      'GET /v1/hrms-change-tasks/register': (url) =>
        paged(
          [
            {
              kind: 'REQUEST',
              reference: 'HUR-2026-9091',
              received: '2026-11-01T05:00:00Z',
              eis: '106004',
              employee: 'Prakash Nayak',
              field: 'Bank account number',
              oldValue: null,
              newValue: null,
              hrmsRequestId: null,
              effective: null,
              order: null,
              maker: 'emp',
              checker: null,
              status: 'TASK_OPENED',
              daysPending: null,
            },
          ],
          1,
          url,
        ),
      'GET /v1/hrms-change-tasks/field-groups': () => [
        { group: 'SERVICE_PAY', fields: ['Basic Pay'], payEffects: ['Every Basic Pay change'] },
      ],
      'GET /v1/hrms-change-tasks/dob-tasks': (url) =>
        paged(
          [
            {
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
            },
          ],
          1,
          url,
        ),
      'GET /v1/hrms-change-orders/5/tasks': (url) =>
        paged(
          [
            {
              id: 3,
              taskNo: 'HCA-2026-9103',
              eis: '105461',
              employee: 'Amrita Kaur',
              fromValue: 'DO Delhi',
              toValue: 'DO Mumbai',
              status: 'PENDING_CHECKER',
              fieldCode: 'PLACE_OF_POSTING',
            },
          ],
          1,
          url,
        ),
      'GET /v1/hrms-change-orders': (url) =>
        paged(
          [
            {
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
            },
          ],
          1,
          url,
        ),
      'GET /v1/hrms-change-tasks/7/documents': () => [],
      'GET /v1/hrms-change-tasks/9/documents': () => [],
      'GET /v1/hrms-change-tasks/9': () => ({
        task: { ...task, id: 9, taskNo: 'HCA-2026-9109' },
        dob: {
          currentRetirement: '2033-04-30',
          newRetirement: '2019-01-31',
          epsCutoff: '2017-01-03',
          retirementPast: true,
          retiresSoon: false,
        },
        applicable: true,
      }),
      'GET /v1/hrms-change-tasks/7': () => ({ task, dob: null, applicable: true, impact: null }),
      'GET /v1/hrms-change-tasks/counts': () => ({ total: 1, byStatus: [], byField: [] }),
      'GET /v1/hrms-change-tasks?': (url) => paged([task], 1, url),
      'GET /v1/hrms-update-requests': (url) => paged([], 0, url),
      'GET /v1/document-types': () => [],
      'POST /v1/approvals/91/decision': () => ({ status: 'APPROVED' }),
    });
  }

  it('R-RBAC: the tiles from the API; a viewer has no Approve, each action disabled with its reason', async () => {
    routes();
    wrap(<HrmsChangeApproval />, ['I-21:VIEW']);
    expect((await screen.findAllByText('HCA-2026-9101')).length).toBeGreaterThan(0);
    expect(await screen.findByText('46')).toBeTruthy();
    const approve = screen.getAllByRole('button', { name: t('i21.w.approveBulk') })[0] as HTMLButtonElement;
    expect(approve.disabled).toBe(true);
    expect(approve.title).toBe(t('i21.w.why.checker'));
    expect((screen.getByRole('button', { name: t('i21.w.raise') }) as HTMLButtonElement).title).toBe(
      t('i21.w.why.raise'),
    );
    expect(
      ((await screen.findByRole('button', { name: t('i21.w.approve') })) as HTMLButtonElement).disabled,
    ).toBe(true);
    expect((screen.getByRole('button', { name: t('i21.w.reject') }) as HTMLButtonElement).title).toBe(
      t('i21.w.why.checker'),
    );
  });

  it('SRS-1-28: the Checker decides from the detail with a reason; without one the decision stays closed', async () => {
    const calls = routes();
    wrap(<HrmsChangeApproval />, ['I-21:VIEW', 'I-21:APPROVE']);
    await screen.findAllByText('EST/311');
    await vi.waitFor(() =>
      expect((screen.getByRole('button', { name: t('i21.w.approve') }) as HTMLButtonElement).disabled).toBe(
        false,
      ),
    );
    await userEvent.click(screen.getByRole('button', { name: t('i21.w.approve') }));
    const dialog = await screen.findByRole('dialog');
    const yes = within(dialog).getByRole('button', { name: t('i21.w.decide.APPROVE') }) as HTMLButtonElement;
    expect(yes.disabled).toBe(true);
    await userEvent.type(within(dialog).getByLabelText(t('common.reason')), 'Matches the order');
    expect(yes.disabled).toBe(false);
    await userEvent.click(yes);
    await vi.waitFor(() =>
      expect(calls.some((c) => c.method === 'POST' && c.url.includes('/v1/approvals/91'))).toBe(true),
    );
  });

  it('SRS-1-71: every tab shows its table from the API', async () => {
    routes();
    wrap(<HrmsChangeApproval />, ['I-21:VIEW']);
    await userEvent.click(await screen.findByRole('button', { name: t('i21.w.tab.register') }));
    expect(
      await screen.findByText(t('i21.w.reg.request', ['HUR-2026-9091', 'Bank account number'])),
    ).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('i21.w.tab.fields') }));
    expect(await screen.findByText('Every Basic Pay change')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('i21.w.tab.bulk') }));
    expect(await screen.findByText('HCA-2026-9103')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('i21.w.tab.dob') }));
    expect(await screen.findByText('HCA-2026-9109')).toBeTruthy();
    expect(await screen.findByText(t('i21.w.dob.rule.hq'))).toBeTruthy();
  });

  it('the drawn search goes to the server', async () => {
    const calls = routes();
    wrap(<HrmsChangeApproval />, ['I-21:VIEW']);
    await userEvent.type(await screen.findByLabelText(t('i21.w.search')), 'Sharma');
    await vi.waitFor(() => expect(calls.some((c) => c.url.includes('q=Sharma'))).toBe(true));
  });

  it('the rules of the task list: order copy state and the DOB rule line', () => {
    const base = { orderHeldInHrms: false, orderRef: null, orderNo: null };
    expect(copyState(base)).toBe('missing');
    expect(copyPill(base)).toBe('p-bad');
    expect(copyState({ ...base, orderNo: 'E/1' })).toBe('attached');
    expect(copyPill({ ...base, orderNo: 'E/1' })).toBe('p-ok');
    expect(copyState({ ...base, orderRef: 'HCO-1' })).toBe('linked');
    expect(copyState({ ...base, orderHeldInHrms: true })).toBe('held');
    expect(copyPill({ ...base, orderHeldInHrms: true })).toBe('p-info');
    const dob = { retirementPast: false, retiresSoon: false, status: 'PENDING_CHECKER' };
    expect(dobRule(dob)).toBe('checker');
    expect(dobRule({ ...dob, retiresSoon: true })).toBe('soon');
    expect(dobRule({ ...dob, retirementPast: true })).toBe('hq');
    expect(dobRule({ ...dob, status: 'REJECTED_RETURNED_TO_HRMS', retirementPast: true })).toBe('rejected');
  });
});
