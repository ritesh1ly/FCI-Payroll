import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  CeilingNorms,
  Circulars,
  Labels,
  NetPayFloors,
  Operations,
  OtherParameters,
  SystemParameters,
} from '@/modules/configuration';
import { date } from '@/shared/ui';
import { backend, me, paged, problem, referenceLists, t, wrap } from './support/harness';

/**
 * The configuration screens without every particular (SRS-16-13, SRS-16-14, SRS-G-38, SRS-G-39,
 * SRS-G-41, SRS-20-5): values and ends that are absent, catalogues that failed to load, a status
 * outside the palette, a cleared file, a history that folds again, and every change locked with its
 * reason outside Headquarters.
 */

afterEach(() => vi.unstubAllGlobals());

const HQ_ONLY = t('error.AUTHZ-403-HQ-ONLY');

const REFERENCE = referenceLists({
  CLAIM_TYPE: [['LABOUR_TA', 'Labour TA']],
  ELIGIBILITY_CATEGORY: [['DEMO_ALL', 'Demo - all members']],
  CEILING_BASIS: [['PER_DAY', 'Per day']],
  ISSUING_AUTHORITY: [['HQ_PERSONNEL', 'HQ Personnel']],
  CIRCULAR_CATEGORY: [['LABOUR_NORM', 'Labour Norm']],
  LANGUAGE: [
    ['EN', 'English'],
    ['HI', 'Hindi'],
  ],
});

function outsideHq(screenCode: string, permissions: string[]) {
  return me(permissions, { atHeadquarters: false, hqOnlyScreens: [screenCode] });
}

async function expectLocked(atLeast: number) {
  const locked = await screen.findAllByTitle(HQ_ONLY);
  expect(locked.length).toBeGreaterThanOrEqual(atLeast);
  locked.forEach((button) => expect(button).toBeDisabled());
}

// ---------------------------------------------------------------- M-14

const floor = (over: object = {}) => ({
  id: 1,
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  floorBasis: 'ABSOLUTE_AMOUNT',
  floorValue: 1500,
  deductionCeilingPct: 50,
  sourceInstrument: 'Circular 1',
  confirmed: true,
  status: 'APPROVED',
  approvalId: 1,
  ...over,
});
const EXCLUSIONS = {
  components: [{ payCode: 101, name: 'Basic pay', type: 'EARNING', excluded: false }],
  pending: null,
  approvalId: null,
};

describe('M-14 without every particular', () => {
  it('a percentage floor without its value shows a dash; outside Headquarters nothing is proposed', async () => {
    backend({
      'GET /v1/masters/net-pay-floors': () =>
        paged([
          {
            payrollType: 'STAFF',
            current: floor({ floorBasis: 'PERCENT_OF_GROSS', floorValue: null }),
            pending: null,
          },
        ]),
      'GET /v1/masters/supplementary-exclusions': () => EXCLUSIONS,
    });
    wrap(<NetPayFloors />, outsideHq('M-14', ['M-14:VIEW', 'M-14:CREATE', 'M-14:EDIT']));
    expect(await screen.findByText(`${t('m14.basis.PERCENT_OF_GROSS')}: — %`)).toBeTruthy();
    await expectLocked(2);
  });
});

// ---------------------------------------------------------------- M-15

const norm = (over: object = {}) => ({
  id: 1,
  versionNo: 1,
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  ceilingBasis: null,
  ceilingValue: 750,
  periodicity: 'MONTHLY',
  eligibilityCategory: 'DEMO_ALL',
  memberScope: 'BOTH',
  confirmed: false,
  status: 'APPROVED',
  approvalId: 1,
  circularOrderId: null,
  ...over,
});

describe('M-15 without every particular', () => {
  const rows = [
    {
      claimType: 'LABOUR_TA',
      name: 'Labour TA',
      current: norm({ effectiveTo: '2026-12-31' }),
      pending: null,
    },
    { claimType: 'FOOD_TA', name: 'Food TA', current: null, pending: null },
  ];
  function routes() {
    return backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/masters/ceiling-norms/LABOUR_TA/versions': () => paged([norm()]),
      'GET /v1/masters/ceiling-norms': () => paged(rows),
      'GET /v1/masters/circulars/current': () => [],
    });
  }

  it('SRS-16-14: a claim type outside the catalogue shows its code, a closed ceiling its last day; history folds; the value and basis clear', async () => {
    routes();
    wrap(<CeilingNorms />, ['M-15:VIEW', 'M-15:CREATE']);
    // A claim type the reference list does not carry is shown by its code.
    expect((await screen.findAllByText('FOOD_TA')).length).toBeGreaterThan(0);
    expect(screen.getAllByText(new RegExp(date('2026-12-31'))).length).toBeGreaterThan(0);
    const history = screen.getAllByRole('button', { name: t('mst.history') })[0] as HTMLElement;
    await userEvent.click(history);
    await screen.findByText(t('mst.historyOf', ['LABOUR_TA']));
    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
    await userEvent.click(history);
    expect(screen.queryByText(t('mst.historyOf', ['LABOUR_TA']))).toBeNull();

    await userEvent.click(screen.getAllByRole('button', { name: t('mst.newVersion') })[0] as HTMLElement);
    const value = screen.getByLabelText(t('m15.ceilingValue')) as HTMLInputElement;
    await userEvent.type(value, '900');
    await userEvent.clear(value);
    expect(value.value).toBe('');
    await waitFor(() => expect(screen.getByRole('option', { name: 'Per day' })).toBeTruthy());
    const basis = screen.getByLabelText(t('m15.basis')) as HTMLSelectElement;
    await userEvent.selectOptions(basis, 'PER_DAY');
    expect(basis.value).toBe('PER_DAY');
    await userEvent.selectOptions(basis, '');
    expect(basis.value).toBe('');
  });

  it('SRS-16-14: outside Headquarters a new version is locked with the reason', async () => {
    routes();
    wrap(<CeilingNorms />, outsideHq('M-15', ['M-15:VIEW', 'M-15:CREATE']));
    await screen.findAllByText('FOOD_TA');
    await expectLocked(2);
  });
});

// ---------------------------------------------------------------- M-27

const op = (over: object = {}) => ({
  id: 21,
  versionNo: 1,
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  name: 'Wagon to Platform',
  departmental: true,
  dps: true,
  nwnp: false,
  bagBased: true,
  normType: 'HANDLING',
  rateNormLink: 'NORM_SLAB',
  dusting: false,
  allowancePayCode: null,
  presentButNoWork: false,
  circularId: 5,
  status: 'APPROVED',
  approvalId: 1,
  ...over,
});

describe('M-27 without every particular', () => {
  it('SRS-G-41: a circular catalogue that failed leaves no circular to link; outside Headquarters every change is locked', async () => {
    backend({
      'GET /v1/masters/circulars/current': () => problem(503, 'COM-503-UNAVAILABLE'),
      'GET /v1/masters/operations': () => paged([{ code: 'WTP', current: op(), pending: null }]),
    });
    const { unmount } = wrap(<Operations />, ['M-27:VIEW', 'M-27:CREATE', 'M-27:EDIT']);
    await userEvent.click(await screen.findByRole('button', { name: t('m27.act.new') }));
    expect(await screen.findByText(t('m27.noActiveCircular'))).toBeTruthy();
    unmount();
    vi.unstubAllGlobals();

    backend({
      'GET /v1/masters/circulars/current': () => [],
      'GET /v1/masters/operations': () => paged([{ code: 'WTP', current: op(), pending: null }]),
    });
    wrap(<Operations />, outsideHq('M-27', ['M-27:VIEW', 'M-27:CREATE', 'M-27:EDIT']));
    await screen.findByText('Wagon to Platform');
    // Outside Headquarters: the drawn reason on New Operation, the rule's reason on the rest.
    expect(screen.getByRole('button', { name: t('m27.act.new') })).toBeDisabled();
    expect(screen.getByRole('button', { name: `${t('common.edit')} WTP` })).toBeDisabled();
    expect(screen.getByRole('button', { name: `${t('common.edit')} WTP` }).getAttribute('data-why')).toBe(
      HQ_ONLY,
    );
    await userEvent.click(screen.getByRole('button', { name: `${t('common.view')} WTP` }));
    await expectLocked(1);
  });
});

// ---------------------------------------------------------------- M-25 / M-26

const circular = (over: object = {}) => ({
  id: 5,
  number: 'HQ/DA/1',
  date: '2026-10-01',
  issuingAuthority: 'HQ_PERSONNEL',
  issuingLocationId: 1,
  subject: 'DA revision',
  category: 'LABOUR_NORM',
  appliesAllOffices: true,
  payrollTypes: ['STAFF'],
  offices: [],
  effectiveFrom: '2026-10-01',
  effectiveTo: null,
  supersedesId: null,
  supersedesNumber: null,
  documentId: 3,
  status: 'ACTIVE',
  statusReason: null,
  approvalId: 1,
  createdBy: 'maker',
  createdAt: '2026-10-01T00:00:00Z',
  changedAt: null,
  ...over,
});

describe('M-25 and M-26 without every particular', () => {
  it('SRS-G-38: a status outside the palette has no tone; clearing the chosen PDF disables the submission', async () => {
    backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/masters/circulars/offices': () => [],
      'GET /v1/masters/circulars': () => paged([circular({ status: 'ARCHIVED' })]),
    });
    wrap(<Circulars />, ['M-25:VIEW', 'M-25:CREATE']);
    const pill = await screen.findByText(t('m25.status.ARCHIVED'));
    expect(pill.className.trim()).toBe('pill');
    await userEvent.click(screen.getByRole('button', { name: t('m25.act.new') }));
    const file = screen.getByLabelText(t('m25.document'));
    await userEvent.upload(file, new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }));
    fireEvent.change(file, { target: { files: [] } });
    expect(screen.getByRole('button', { name: t('common.submitForApproval') })).toBeDisabled();
  });

  it('SRS-G-39: clearing the chosen CSV disables the label upload', async () => {
    backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/masters/labels/missing-hindi': () => paged([]),
      'GET /v1/masters/labels': () => paged([]),
    });
    wrap(<Labels />, ['M-26:VIEW', 'M-26:CREATE', 'M-26:EDIT']);
    await userEvent.click(await screen.findByRole('button', { name: t('m26.act.upload') }));
    const file = await screen.findByLabelText(t('m26.file'));
    await userEvent.upload(file, new File(['key,text'], 'labels.csv', { type: 'text/csv' }));
    fireEvent.change(file, { target: { files: [] } });
    expect(screen.getByRole('button', { name: t('m26.upload') })).toBeDisabled();
  });
});

// ---------------------------------------------------------------- M-23

const version = (over: object = {}) => ({
  id: 1,
  versionNo: 1,
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  numericValue: null,
  ceilingValue: 7000,
  percentValue: 10,
  windowFromTime: '22:00',
  windowToTime: '06:00',
  instalmentCount: 3,
  payrollTypeScope: null,
  confirmed: true,
  status: 'APPROVED',
  approvalId: 1,
  ...over,
});

describe('M-23 without every particular', () => {
  const rows = [
    { code: 'NIGHT_SHIFT', name: 'Night shift allowance', payCode: 220, current: version(), pending: null },
  ];
  function routes() {
    return backend({
      'GET /v1/masters/other-parameters': () => paged(rows),
      'GET /v1/masters/other-parameters/NIGHT_SHIFT/versions': () =>
        paged([
          version({
            status: 'SUPERSEDED',
            effectiveTo: null,
            ceilingValue: null,
            percentValue: null,
            windowFromTime: null,
            windowToTime: null,
            instalmentCount: null,
          }),
        ]),
    });
  }

  it('a history version without values or an end shows dashes; the scope clears to none', async () => {
    routes();
    wrap(<OtherParameters />, ['M-23:VIEW', 'M-23:CREATE']);
    const register = within(await screen.findByTestId('m23-register'));
    await userEvent.click(
      (await register.findAllByRole('button', { name: t('mst.history') }))[0] as HTMLElement,
    );
    await waitFor(() => expect(register.getAllByText('—').length).toBeGreaterThanOrEqual(2));
    await userEvent.click(screen.getAllByRole('button', { name: t('mst.newVersion') })[0] as HTMLElement);
    const scope = screen.getByLabelText(t('mst.appliesTo')) as HTMLInputElement;
    expect(scope.value).toBe('');
    await userEvent.type(scope, 'STAFF');
    await userEvent.clear(scope);
    expect(scope.value).toBe('');
  });

  it('outside Headquarters a new version is locked with the reason', async () => {
    routes();
    wrap(<OtherParameters />, outsideHq('M-23', ['M-23:VIEW', 'M-23:CREATE']));
    await screen.findByText('Night shift allowance');
    await expectLocked(1);
  });
});

// ---------------------------------------------------------------- A-07

const PARAMETER = {
  code: 'SESSION_IDLE_TIMEOUT',
  group: 'SESSION',
  valueType: 'INTEGER',
  value: 15,
  minValue: 5,
  maxValue: 60,
  unit: 'MINUTES',
  enforced: false,
  version: 1,
  effectiveFrom: '2026-04-01',
  changedBy: 'system',
  pending: {
    value: 20,
    requestedBy: 'sysadmin@fci-demo.test',
    requestedAt: '2026-10-20T10:00:00Z',
    effectiveFrom: '2026-10-21',
    reason: null,
  },
};
const SINGLE_SESSION_OFF = {
  code: 'SINGLE_SESSION',
  group: 'SESSION',
  valueType: 'BOOLEAN',
  value: 0,
  minValue: 0,
  maxValue: 1,
  unit: null,
  enforced: true,
  version: 1,
  effectiveFrom: '2026-04-01',
  changedBy: 'system',
  pending: null,
};
const RETENTION = [
  {
    code: 'LEGACY_AUDIT',
    name: 'Legacy Audit table',
    months: null,
    indefinite: false,
    confirmed: true,
    basis: null,
    archivalAction: 'RETAIN',
    pendingApprovalId: null,
  },
  {
    code: 'NATIVE_AUDIT',
    name: 'Native audit trail',
    months: 120,
    indefinite: false,
    confirmed: true,
    basis: 'Order B',
    archivalAction: 'RETAIN',
    pendingApprovalId: null,
  },
];

const normalised = (text: string) => text.replace(/\s+/g, ' ').trim();

describe('A-07 without every particular', () => {
  it('a boolean off reads as no; a pending change without a reason carries no hint; outside Headquarters locked', async () => {
    backend({
      'GET /v1/admin/parameters': () => paged([PARAMETER, SINGLE_SESSION_OFF]),
      'GET /v1/admin/retention-classes': () => paged([]),
    });
    wrap(<SystemParameters />, outsideHq('A-07', ['A-07:VIEW', 'A-07:EDIT']));
    expect(await screen.findByText(t('common.no'))).toBeTruthy();
    const pending = document.querySelector('.pill.p-warn') as HTMLElement;
    expect(pending).not.toHaveAttribute('title');
    // The parameter with a pending change offers no second change: one button is locked.
    await expectLocked(1);
  });

  it('SRS-20-5: outside Headquarters a retention period is shown but its change is locked', async () => {
    backend({
      'GET /v1/admin/parameters': () => paged([]),
      'GET /v1/admin/retention-classes': () => paged(RETENTION),
    });
    wrap(<SystemParameters />, outsideHq('A-07', ['A-07:VIEW', 'A-07:EDIT']));
    await expectLocked(1);
  });

  it('SRS-20-5: a decided period without its months or basis shows what it has; editing starts from the months; a cancelled OTP keeps the form', async () => {
    backend({
      'GET /v1/admin/parameters': () => paged([]),
      'GET /v1/admin/retention-classes': () => paged(RETENTION),
      'PUT /v1/admin/retention-classes/': () => problem(428, 'AUTH-428-OTP', ['7']),
    });
    const user = userEvent.setup();
    wrap(<SystemParameters />, ['A-07:VIEW', 'A-07:EDIT']);
    const monthsText = normalised(t('a07.retention.months', ['']));
    expect(await screen.findByText((content) => normalised(content) === monthsText)).toBeTruthy();
    const legacy = screen.getByText(t('a07.retention.class.LEGACY_AUDIT')).closest('tr') as HTMLElement;
    expect(within(legacy).getAllByText('—').length).toBeGreaterThanOrEqual(2);
    const native = screen.getByText(t('a07.retention.class.NATIVE_AUDIT')).closest('tr') as HTMLElement;
    await user.click(within(native).getByRole('button', { name: t('a07.retention.propose') }));
    expect((screen.getByLabelText(t('a07.retention.monthsLabel')) as HTMLInputElement).value).toBe('120');
    await user.type(screen.getByLabelText(t('a07.retention.basis')), 'Order B');
    await user.type(screen.getByLabelText(t('common.reason')), 'Records policy');
    await user.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    const otp = await screen.findByRole('dialog', { name: t('otp.title') });
    await user.click(within(otp).getByRole('button', { name: t('common.cancel') }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(screen.getByLabelText(t('a07.retention.monthsLabel'))).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
