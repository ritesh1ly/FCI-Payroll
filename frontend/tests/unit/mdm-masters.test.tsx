import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CeilingNorms, Circulars, Labels, NetPayFloors, Operations } from '@/modules/configuration';
import { date } from '@/shared/ui';
import { backend, problem, referenceLists, sent, t, wrap, paged } from './support/harness';

/**
 * The Module 04 master screens (M-14, M-15, M-25, M-26, M-27): what is in force, not configured or
 * pending, the history, and each change as a maker-checker proposal (SRS-16-13, SRS-16-14,
 * SRS-G-38, SRS-G-39, SRS-G-41).
 */

afterEach(() => vi.unstubAllGlobals());

const REFERENCE = referenceLists({
  CLAIM_TYPE: [
    ['LABOUR_TA', 'Labour TA'],
    ['TRANSFER_TA', 'Transfer TA'],
  ],
  ELIGIBILITY_CATEGORY: [['DEMO_ALL', 'Demo - all members']],
  ALLOWANCE_PERIODICITY: [
    ['MONTHLY', 'Monthly'],
    ['BLOCK_YEAR_4', 'Block-Year (4 years)'],
  ],
  CEILING_BASIS: [],
  ISSUING_AUTHORITY: [['HQ_PERSONNEL', 'HQ Personnel']],
  CIRCULAR_CATEGORY: [['LABOUR_NORM', 'Labour Norm']],
  LANGUAGE: [
    ['EN', 'English'],
    ['HI', 'Hindi'],
  ],
});

const accepted = (body: unknown) => () => ({ status: 202, body });
const otpRequired = () => problem(428, 'AUTH-428-OTP', ['7']);

/** Cancels the OTP dialog the governed action opened (SRS-G-48): the action fails silently. */
async function cancelOtp() {
  const dialog = await screen.findByRole('dialog', { name: t('otp.title') });
  await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
  await waitFor(() => expect(screen.queryByRole('dialog', { name: t('otp.title') })).toBeNull());
}

// ---------------------------------------------------------------- M-14

describe('M-14 Net-pay floors and supplementary exclusions', () => {
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
  const ROWS = [
    { payrollType: 'STAFF', current: floor(), pending: null },
    {
      payrollType: 'DEPT_LABOUR',
      current: floor({ floorBasis: 'PERCENT_OF_GROSS', floorValue: 25, confirmed: false }),
      pending: null,
    },
    {
      payrollType: 'DPS_LABOUR',
      current: null,
      pending: floor({ status: 'PENDING_APPROVAL', effectiveFrom: '2027-01-01' }),
    },
    { payrollType: 'NWNP_LABOUR', current: floor({ floorBasis: 'NONE', floorValue: null }), pending: null },
  ];
  const EXCLUSIONS = {
    components: [
      { payCode: 101, name: 'Basic pay', type: 'EARNING', excluded: false },
      { payCode: 348, name: 'Benevolent fund', type: 'DEDUCTION', excluded: true },
    ],
    pending: null,
    approvalId: null,
  };
  function routes(exclusions: unknown = EXCLUSIONS) {
    return backend({
      'GET /v1/masters/net-pay-floors/STAFF/versions': () =>
        paged([floor({ status: 'APPROVED', deductionCeilingPct: null })]),
      'GET /v1/masters/net-pay-floors': () => paged(ROWS),
      'POST /v1/masters/net-pay-floors/': accepted(floor({ status: 'PENDING_APPROVAL' })),
      'GET /v1/masters/supplementary-exclusions': () => exclusions,
      'POST /v1/masters/supplementary-exclusions': accepted(EXCLUSIONS),
    });
  }

  it('SRS-16-13: shows each type in force, unconfirmed, not configured and pending, and its history', async () => {
    routes();
    wrap(<NetPayFloors />, ['M-14:VIEW', 'M-14:CREATE']);
    expect(await screen.findByText(`${t('m14.basis.ABSOLUTE_AMOUNT')}: ₹ 1500`)).toBeTruthy();
    expect(screen.getByText(`${t('m14.basis.PERCENT_OF_GROSS')}: 25 %`)).toBeTruthy();
    expect(screen.getByText(t('m14.basis.NONE'))).toBeTruthy();
    expect(screen.getByText(t('mst.unconfirmed'))).toBeTruthy();
    expect(screen.getByText(t('mst.notConfigured'))).toBeTruthy();
    // A type with a pending version takes no second proposal.
    expect(screen.getAllByRole('button', { name: t('mst.newVersion') })).toHaveLength(3);
    const history = screen.getAllByRole('button', { name: t('mst.history') })[0] as HTMLElement;
    await userEvent.click(history);
    expect(await screen.findByText(t('mst.historyOf', [t('m14.type.STAFF')]))).toBeTruthy();
    await userEvent.click(history);
    expect(screen.queryByText(t('mst.historyOf', [t('m14.type.STAFF')]))).toBeNull();
  });

  it('SRS-16-13: a new floor version is proposed with its basis, value and reason; None takes no value', async () => {
    const calls = routes();
    wrap(<NetPayFloors />, ['M-14:VIEW', 'M-14:CREATE']);
    await screen.findByText(t('mst.notConfigured'));
    await userEvent.click(screen.getAllByRole('button', { name: t('mst.newVersion') })[0] as HTMLElement);
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    expect(submit.hasAttribute('disabled')).toBe(true);
    await userEvent.selectOptions(screen.getByLabelText(t('m14.basis')), 'NONE');
    expect((screen.getByLabelText(t('m14.floorValue')) as HTMLInputElement).disabled).toBe(true);
    await userEvent.selectOptions(screen.getByLabelText(t('m14.basis')), 'ABSOLUTE_AMOUNT');
    await userEvent.clear(screen.getByLabelText(t('m14.floorValue')));
    await userEvent.type(screen.getByLabelText(t('m14.floorValue')), '1600');
    await userEvent.clear(screen.getByLabelText(t('m14.deductionCeiling')));
    await userEvent.clear(screen.getByLabelText(t('m14.sourceInstrument')));
    await userEvent.type(screen.getByLabelText(t('m14.sourceInstrument')), 'Order 7');
    await userEvent.click(screen.getByLabelText(t('mst.confirmed')));
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Revised by order 7');
    const from = screen.getByLabelText(t('mst.from')) as HTMLInputElement;
    await userEvent.clear(from);
    await userEvent.type(from, '2027-04-01');
    await userEvent.click(submit);
    await screen.findByText(t('mst.submitted', [t('m14.type.STAFF')]));
    expect(sent(calls, 'POST', '/net-pay-floors/STAFF/versions')[0]).toMatchObject({
      floorBasis: 'ABSOLUTE_AMOUNT',
      floorValue: 1600,
      deductionCeilingPct: null,
      sourceInstrument: 'Order 7',
      confirmed: true,
      reason: 'Revised by order 7',
      effectiveFrom: '2027-04-01',
    });
  });

  // UI_BMAD_g1 #54 (SRS l.1385 "Floor Amount - mandatory - zero or more"): an amount or percentage
  // basis needs its value (zero is a value); basis None needs none.
  it('SRS-16-13: a floor version needs its value unless the basis is None; zero is accepted', async () => {
    routes();
    wrap(<NetPayFloors />, ['M-14:VIEW', 'M-14:CREATE']);
    await screen.findByText(t('mst.notConfigured'));
    await userEvent.click(screen.getAllByRole('button', { name: t('mst.newVersion') })[0] as HTMLElement);
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Revised');
    await userEvent.clear(screen.getByLabelText(t('m14.floorValue')));
    expect(submit.hasAttribute('disabled')).toBe(true);
    expect(submit.getAttribute('title')).toBe(t('m14.valueRequired'));
    await userEvent.type(screen.getByLabelText(t('m14.floorValue')), '0');
    expect(submit.hasAttribute('disabled')).toBe(false);
    await userEvent.clear(screen.getByLabelText(t('m14.floorValue')));
    await userEvent.selectOptions(screen.getByLabelText(t('m14.basis')), 'NONE');
    expect(submit.hasAttribute('disabled')).toBe(false);
    expect(submit.getAttribute('title')).toBeNull();
  });

  it('SRS-16-13: a refused floor shows the reason; cancel closes the form', async () => {
    backend({
      'GET /v1/masters/net-pay-floors': () => paged(ROWS),
      'POST /v1/masters/net-pay-floors/': () => problem(422, 'MST-422-DATE', ['2026-04-01']),
      'GET /v1/masters/supplementary-exclusions': () => EXCLUSIONS,
    });
    wrap(<NetPayFloors />, ['M-14:VIEW', 'M-14:CREATE']);
    await screen.findByText(t('mst.notConfigured'));
    await userEvent.click(screen.getAllByRole('button', { name: t('mst.newVersion') })[0] as HTMLElement);
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'x');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByText(t('error.MST-422-DATE', ['2026-04-01']))).toBeTruthy();
    await userEvent.click(screen.getAllByRole('button', { name: t('common.cancel') })[0] as HTMLElement);
    expect(screen.queryByText(t('error.MST-422-DATE', ['2026-04-01']))).toBeNull();
  });

  it('SRS-16-13: exclusion codes are changed as one list under maker-checker', async () => {
    const calls = routes();
    wrap(<NetPayFloors />, ['M-14:VIEW', 'M-14:EDIT']);
    const basic = await screen.findByLabelText(t('m14.excludeCode', [101]));
    expect((basic as HTMLInputElement).disabled).toBe(true);
    await userEvent.click(screen.getByRole('button', { name: t('action.changeExclusions') }));
    await userEvent.click(screen.getByLabelText(t('m14.excludeCode', [101])));
    await userEvent.click(screen.getByLabelText(t('m14.excludeCode', [348])));
    const reason = screen.getAllByLabelText(t('common.reason')).at(-1) as HTMLElement;
    await userEvent.type(reason, 'Skip basic, keep fund');
    await userEvent.click(
      screen.getAllByRole('button', { name: t('common.submitForApproval') }).at(-1) as HTMLElement,
    );
    await screen.findByText(t('m14.exclusionsSubmitted'));
    expect(sent(calls, 'POST', '/supplementary-exclusions')[0]).toMatchObject({
      payCodes: [101],
      reason: 'Skip basic, keep fund',
    });
  });

  it('SRS-16-13: a pending exclusion change is shown and no second one is offered; no catalogue, no list', async () => {
    routes({ ...EXCLUSIONS, pending: [101, 348], approvalId: 9 });
    wrap(<NetPayFloors />, ['M-14:VIEW', 'M-14:EDIT']);
    expect(await screen.findByText(t('m14.exclusionsPending', ['101, 348']))).toBeTruthy();
    expect(screen.queryByRole('button', { name: t('action.changeExclusions') })).toBeNull();
    vi.unstubAllGlobals();
    routes({ components: [], pending: null, approvalId: null });
    wrap(<NetPayFloors />, ['M-14:VIEW', 'M-14:EDIT']);
    expect(await screen.findByText(t('mst.noPayCodes'))).toBeTruthy();
  });
  it('SRS-16-13: a refused exclusion change is shown; cancel closes the editor and clears the error', async () => {
    const calls = backend({
      'GET /v1/masters/net-pay-floors': () => paged(ROWS),
      'GET /v1/masters/supplementary-exclusions': () => EXCLUSIONS,
      'POST /v1/masters/supplementary-exclusions': () => problem(409, 'MST-409-PENDING'),
    });
    wrap(<NetPayFloors />, ['M-14:VIEW', 'M-14:EDIT']);
    await userEvent.click(await screen.findByRole('button', { name: t('action.changeExclusions') }));
    await userEvent.click(screen.getByLabelText(t('m14.excludeCode', [101])));
    await userEvent.type(screen.getAllByLabelText(t('common.reason')).at(-1) as HTMLElement, 'Skip basic');
    await userEvent.click(
      screen.getAllByRole('button', { name: t('common.submitForApproval') }).at(-1) as HTMLElement,
    );
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(sent(calls, 'POST', '/supplementary-exclusions')[0]).toMatchObject({ payCodes: [348, 101] });
    await userEvent.click(screen.getAllByRole('button', { name: t('common.cancel') }).at(-1) as HTMLElement);
    expect(screen.queryByRole('alert')).toBeNull();
    expect((screen.getByLabelText(t('m14.excludeCode', [101])) as HTMLInputElement).disabled).toBe(true);
  });

  it('SRS-G-48: cancelling the OTP keeps the floor form and the exclusion editor open, without an error', async () => {
    backend({
      'GET /v1/masters/net-pay-floors': () => paged(ROWS),
      'POST /v1/masters/net-pay-floors/': otpRequired,
      'GET /v1/masters/supplementary-exclusions': () => EXCLUSIONS,
      'POST /v1/masters/supplementary-exclusions': otpRequired,
    });
    wrap(<NetPayFloors />, ['M-14:VIEW', 'M-14:CREATE', 'M-14:EDIT']);
    await screen.findByText(t('mst.notConfigured'));
    await userEvent.click(screen.getAllByRole('button', { name: t('mst.newVersion') })[0] as HTMLElement);
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'x');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await cancelOtp();
    expect(screen.getByLabelText(t('m14.floorValue'))).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
    await userEvent.click(screen.getAllByRole('button', { name: t('common.cancel') })[0] as HTMLElement);

    await userEvent.click(screen.getByRole('button', { name: t('action.changeExclusions') }));
    await userEvent.type(screen.getAllByLabelText(t('common.reason')).at(-1) as HTMLElement, 'y');
    await userEvent.click(
      screen.getAllByRole('button', { name: t('common.submitForApproval') }).at(-1) as HTMLElement,
    );
    await cancelOtp();
    expect((screen.getByLabelText(t('m14.excludeCode', [101])) as HTMLInputElement).disabled).toBe(false);
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('SRS-16-13: a closed floor shows its last day; a type never configured starts from defaults; an empty pending list shows a dash', async () => {
    backend({
      'GET /v1/masters/net-pay-floors/STAFF/versions': () =>
        paged([
          floor({ effectiveTo: '2026-12-31', deductionCeilingPct: 40 }),
          floor({
            id: 0,
            floorValue: null,
            effectiveFrom: '2025-04-01',
            effectiveTo: null,
            deductionCeilingPct: null,
          }),
        ]),
      'GET /v1/masters/net-pay-floors': () =>
        paged([
          { payrollType: 'STAFF', current: floor({ effectiveTo: '2026-12-31' }), pending: null },
          { payrollType: 'DPS_LABOUR', current: null, pending: null },
        ]),
      'GET /v1/masters/supplementary-exclusions': () => ({ ...EXCLUSIONS, pending: [], approvalId: 9 }),
    });
    wrap(<NetPayFloors />, ['M-14:VIEW', 'M-14:CREATE']);
    expect(await screen.findByText(t('m14.exclusionsPending', ['—']))).toBeTruthy();
    expect(screen.getAllByText(new RegExp(date('2026-12-31'))).length).toBeGreaterThan(0);
    await userEvent.click(screen.getAllByRole('button', { name: t('mst.history') })[0] as HTMLElement);
    await screen.findByText(t('mst.historyOf', [t('m14.type.STAFF')]));
    expect(await screen.findByText('40 %')).toBeTruthy();
    expect(screen.getByText(`${t('m14.basis.ABSOLUTE_AMOUNT')}: ₹ —`)).toBeTruthy();
    // DPS Labour has nothing yet: the form starts from the defaults.
    await userEvent.click(screen.getAllByRole('button', { name: t('mst.newVersion') })[1] as HTMLElement);
    expect((screen.getByLabelText(t('m14.basis')) as HTMLSelectElement).value).toBe('ABSOLUTE_AMOUNT');
    expect((screen.getByLabelText(t('m14.floorValue')) as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText(t('m14.sourceInstrument')) as HTMLInputElement).value).toBe('');
  });
});

// ---------------------------------------------------------------- M-15

describe('M-15 Ceiling norms', () => {
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
  function routes(rows: unknown[], post: () => unknown = accepted(norm({ status: 'PENDING_APPROVAL' }))) {
    return backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/masters/ceiling-norms/LABOUR_TA/versions': () =>
        paged([
          norm({ effectiveTo: '2026-12-31', circularOrderId: 6 }),
          norm({ id: 2, versionNo: 2, circularOrderId: 7 }),
        ]),
      'GET /v1/masters/ceiling-norms': () => paged(rows),
      'POST /v1/masters/ceiling-norms/': post,
      'GET /v1/masters/circulars/current': () => [
        { id: 6, number: 'CIR/M15/6', subject: 'Ceiling revision' },
      ],
    });
  }

  it('SRS-16-14: shows the claim types with their ceiling, pending version and history', async () => {
    routes([
      { claimType: 'LABOUR_TA', name: 'Labour TA', current: norm(), pending: null },
      {
        claimType: 'TRANSFER_TA',
        name: 'Transfer TA',
        current: null,
        pending: norm({ versionNo: 2, status: 'PENDING_APPROVAL' }),
      },
    ]);
    wrap(<CeilingNorms />, ['M-15:VIEW', 'M-15:CREATE']);
    expect(await screen.findByText(/₹ 750 · Monthly/)).toBeTruthy();
    expect(screen.getByText(t('mst.unconfirmed'))).toBeTruthy();
    expect(screen.getByText(t('mst.notConfigured'))).toBeTruthy();
    expect(screen.getAllByRole('button', { name: t('mst.newVersion') })).toHaveLength(1);
    await userEvent.click(screen.getAllByRole('button', { name: t('mst.history') })[0] as HTMLElement);
    expect(await screen.findByText(t('mst.historyOf', ['LABOUR_TA']))).toBeTruthy();
    // SRS-G-38 (US-GAP-211): each version shows its circular; one no longer in force by its id.
    expect(await screen.findByText('CIR/M15/6')).toBeTruthy();
    expect(screen.getByText('#7')).toBeTruthy();
  });

  it('SRS-16-14: a ceiling is proposed with its value, periodicity and eligibility', async () => {
    const calls = routes([{ claimType: 'LABOUR_TA', name: 'Labour TA', current: null, pending: null }]);
    wrap(<CeilingNorms />, ['M-15:VIEW', 'M-15:CREATE']);
    await userEvent.click(await screen.findByRole('button', { name: t('mst.newVersion') }));
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    await userEvent.type(screen.getByLabelText(t('m15.ceilingValue')), '900.5');
    await userEvent.selectOptions(screen.getByLabelText(t('m15.periodicity')), 'BLOCK_YEAR_4');
    await userEvent.selectOptions(screen.getByLabelText(t('m15.memberScope')), 'LABOUR');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'From the current system');
    expect(submit.hasAttribute('disabled')).toBe(true);
    await waitFor(() =>
      expect(screen.getAllByRole('option', { name: 'Demo - all members' }).length).toBeGreaterThan(0),
    );
    await userEvent.selectOptions(screen.getByLabelText(t('m15.eligibility')), 'DEMO_ALL');
    await userEvent.click(screen.getByLabelText(t('mst.confirmed')));
    const from = screen.getByLabelText(t('mst.from')) as HTMLInputElement;
    await userEvent.clear(from);
    await userEvent.type(from, '2027-04-01');
    // SRS-G-38 (US-GAP-211): not without its Active circular.
    expect(submit.hasAttribute('disabled')).toBe(true);
    expect(submit.getAttribute('title')).toBe(t('mst.why.circular'));
    await screen.findByRole('option', { name: 'CIR/M15/6 · Ceiling revision' });
    await userEvent.selectOptions(screen.getByLabelText(t('mst.circular')), '6');
    await userEvent.selectOptions(screen.getByLabelText(t('mst.circular')), '');
    expect(submit.hasAttribute('disabled')).toBe(true);
    await userEvent.selectOptions(screen.getByLabelText(t('mst.circular')), '6');
    expect(submit.getAttribute('title')).toBeNull();
    await userEvent.click(submit);
    await screen.findByText(t('mst.submitted', ['Labour TA']));
    expect(sent(calls, 'POST', '/ceiling-norms/LABOUR_TA/versions')[0]).toMatchObject({
      effectiveFrom: '2027-04-01',
      ceilingValue: 900.5,
      periodicity: 'BLOCK_YEAR_4',
      eligibilityCategory: 'DEMO_ALL',
      memberScope: 'LABOUR',
      ceilingBasis: null,
      confirmed: true,
      circularOrderId: 6,
    });
  });

  it('SRS-16-14: a refused ceiling shows the reason; no claim types tells where to add them', async () => {
    routes([{ claimType: 'LABOUR_TA', name: 'Labour TA', current: norm(), pending: null }], () =>
      problem(422, 'MST-422-CEILING'),
    );
    wrap(<CeilingNorms />, ['M-15:VIEW', 'M-15:CREATE']);
    await userEvent.click(await screen.findByRole('button', { name: t('mst.newVersion') }));
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'x');
    await screen.findByRole('option', { name: 'CIR/M15/6 · Ceiling revision' });
    await userEvent.selectOptions(screen.getByLabelText(t('mst.circular')), '6');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByText(t('error.MST-422-CEILING'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    vi.unstubAllGlobals();
    routes([]);
    wrap(<CeilingNorms />, ['M-15:VIEW']);
    expect(await screen.findByText(t('m15.noClaimTypes'))).toBeTruthy();
  });
});

// ---------------------------------------------------------------- M-25

describe('M-25 Circulars', () => {
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
    whereUsed: 1,
    ...over,
  });
  function routes(post: () => unknown = accepted(circular({ status: 'PENDING_APPROVAL' }))) {
    return backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/masters/circulars/offices': () => [{ id: 9, code: 'DH001', name: 'Delhi', type: 'DO' }],
      'GET /v1/masters/circulars/5/where-used': () =>
        paged([
          {
            kind: 'MASTER_ENTRY',
            entity: 'operation_master',
            key: 'WTP',
            screen: 'M-27',
            status: 'ACTIVE',
            linkedAt: '2026-10-02T00:00:00Z',
          },
        ]),
      'GET /v1/masters/circulars/6/where-used': () => paged([]),
      'GET /v1/masters/circulars': () =>
        paged([
          circular(),
          circular({
            id: 6,
            number: 'HQ/DA/0',
            status: 'SUPERSEDED',
            supersedesNumber: 'HQ/DA/-1',
            appliesAllOffices: false,
            offices: [{ scopeType: 'LOCATION', payrollType: null, locationId: 9, office: 'DH001 Delhi' }],
            documentId: null,
            effectiveTo: '2026-09-30',
          }),
        ]),
      'POST /v1/masters/circulars/5/withdraw': accepted(circular()),
      'POST /v1/masters/circulars': post,
    });
  }

  it('SRS-G-38: lists circulars with status, copy and where used; filters are sent', async () => {
    const calls = routes();
    wrap(<Circulars />, ['M-25:VIEW']);
    expect(await screen.findByText('HQ/DA/1')).toBeTruthy();
    expect(screen.getByText('HQ/DA/-1')).toBeTruthy();
    // The copy of the first row is listed; the second has none.
    expect(screen.getAllByText(t('m25.pdf'))).toHaveLength(1);
    // Drawn and disabled with the reason for a role that may not use them, never hidden.
    const locked = (name: string) => screen.getByRole('button', { name });
    expect(locked(t('m25.act.new'))).toBeDisabled();
    expect(locked(t('m25.act.new')).getAttribute('data-why')).toBe(t('m25.why.new'));
    expect(locked(t('m25.act.supersede')).getAttribute('data-why')).toBe(t('m25.why.act'));
    expect(locked(t('m25.act.withdraw'))).toBeDisabled();
    expect(locked(t('m25.act.withdraw')).getAttribute('data-why')).toBe(t('m25.why.act'));
    // The first row is selected: its Where used is on the screen; clicking a row changes it.
    expect(await screen.findByText('WTP')).toBeTruthy();
    expect(screen.getByText(t('m25.whereUsedOf', ['HQ/DA/1']))).toBeTruthy();
    expect(screen.getByText(t('m25.kind.MASTER_ENTRY'))).toBeTruthy();
    await userEvent.click(screen.getByText('HQ/DA/0'));
    expect(await screen.findByText(t('m25.notUsed'))).toBeTruthy();
    expect(screen.getByText(t('m25.whereUsedOf', ['HQ/DA/0']))).toBeTruthy();
    expect(screen.getByText(/DH001 Delhi/)).toBeTruthy();
    await userEvent.type(screen.getByLabelText(t('m25.search')), 'DA');
    await userEvent.selectOptions(screen.getByLabelText(t('mst.state')), 'ACTIVE');
    await userEvent.selectOptions(
      screen.getAllByLabelText(t('m25.category'))[0] as HTMLElement,
      'LABOUR_NORM',
    );
    await waitFor(() =>
      expect(
        calls.some(
          (c) =>
            c.url.includes('text=DA') &&
            c.url.includes('status=ACTIVE') &&
            c.url.includes('category=LABOUR_NORM'),
        ),
      ).toBe(true),
    );
  });

  // UI_BMAD_g1 #38 / #44 (SRS-G-38 "search circulars by number, date, subject and category"): the
  // date range goes to the server; a reversed range is named and not sent.
  it('SRS-G-38: circulars are searched by date; a reversed range is refused on the screen', async () => {
    const calls = routes();
    wrap(<Circulars />, ['M-25:VIEW']);
    await screen.findByText('HQ/DA/1');
    fireEvent.change(screen.getByLabelText(t('m25.dateFrom')), { target: { value: '2026-10-01' } });
    fireEvent.change(screen.getByLabelText(t('m25.dateTo')), { target: { value: '2026-10-31' } });
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('from=2026-10-01&to=2026-10-31'))).toBe(true),
    );
    fireEvent.change(screen.getByLabelText(t('m25.dateTo')), { target: { value: '2026-09-30' } });
    expect(await screen.findByText(t('m25.rangeReversed'))).toBeTruthy();
    expect(calls.some((c) => c.url.includes('to=2026-09-30'))).toBe(false);
    // One day (From = To) is a range.
    fireEvent.change(screen.getByLabelText(t('m25.dateTo')), { target: { value: '2026-10-01' } });
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('from=2026-10-01&to=2026-10-01'))).toBe(true),
    );
    expect(screen.queryByText(t('m25.rangeReversed'))).toBeNull();
  });

  // UI_BMAD_g1 #42: Effective To before Effective From blocks the submission with its reason.
  it('SRS-G-38: a new circular ending before it starts is not submitted', async () => {
    routes();
    wrap(<Circulars />, ['M-25:VIEW', 'M-25:CREATE']);
    await userEvent.click(await screen.findByRole('button', { name: t('m25.act.new') }));
    const fromField = screen.getByLabelText(t('mst.from')) as HTMLInputElement;
    fireEvent.change(fromField, { target: { value: '2026-10-01' } });
    fireEvent.change(screen.getByLabelText(t('mst.to')), { target: { value: '2026-09-30' } });
    expect(await screen.findByText(t('m25.toBeforeFrom'))).toBeTruthy();
    expect(screen.getByRole('button', { name: t('common.submitForApproval') }).hasAttribute('disabled')).toBe(
      true,
    );
    fireEvent.change(screen.getByLabelText(t('mst.to')), { target: { value: '2026-10-01' } });
    expect(screen.queryByText(t('m25.toBeforeFrom'))).toBeNull();
  });

  // SC-01-0161 (Issuing Authority / Division), SC-01-0164 (Applicable Payroll Types), SC-01-0165
  // (Applicable Region / Office), SC-01-0053 (a missing field or attachment blocks submission with
  // its reason).
  it('SRS-G-38: a new circular needs every field, a payroll type, offices or all, and the PDF', async () => {
    const calls = routes();
    wrap(<Circulars />, ['M-25:VIEW', 'M-25:CREATE']);
    await userEvent.click(await screen.findByRole('button', { name: t('m25.act.new') }));
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    await userEvent.type(screen.getByLabelText(t('m25.number')), 'HQ/DA/2');
    await userEvent.type(screen.getByLabelText(t('m25.subject')), 'DA revision 2');
    await waitFor(() =>
      expect(screen.getAllByRole('option', { name: 'HQ Personnel' }).length).toBeGreaterThan(0),
    );
    await userEvent.selectOptions(screen.getByLabelText(t('m25.issuingAuthority')), 'HQ_PERSONNEL');
    await userEvent.selectOptions(
      screen.getAllByLabelText(t('m25.category')).at(-1) as HTMLElement,
      'LABOUR_NORM',
    );
    const staff = screen.getByRole('checkbox', { name: t('m14.type.STAFF') });
    await userEvent.click(staff);
    await userEvent.click(staff);
    await userEvent.click(screen.getByRole('checkbox', { name: t('m14.type.DPS_LABOUR') }));
    await userEvent.click(screen.getByLabelText(t('m25.allOffices')));
    await waitFor(() => expect(screen.getByRole('option', { name: /DH001/ })).toBeTruthy());
    await userEvent.selectOptions(screen.getByLabelText(t('m25.offices')), '9');
    const dateField = screen.getByLabelText(t('m25.date')) as HTMLInputElement;
    await userEvent.clear(dateField);
    await userEvent.type(dateField, '2026-09-30');
    const fromField = screen.getByLabelText(t('mst.from')) as HTMLInputElement;
    await userEvent.clear(fromField);
    await userEvent.type(fromField, '2026-10-01');
    await userEvent.type(screen.getByLabelText(t('mst.to')), '2027-03-31');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'New order');
    expect(submit.hasAttribute('disabled')).toBe(true);
    await userEvent.upload(
      screen.getByLabelText(t('m25.document')),
      new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' }),
    );
    await userEvent.click(submit);
    await screen.findByText(t('m25.submitted', ['HQ/DA/2']));
    const form = sent(calls, 'POST', '/v1/masters/circulars')[0] as FormData;
    const body = JSON.parse(await (form.get('circular') as Blob).text()) as Record<string, unknown>;
    expect(body).toMatchObject({
      number: 'HQ/DA/2',
      payrollTypes: ['DPS_LABOUR'],
      appliesAllOffices: false,
      officeIds: [9],
      date: '2026-09-30',
      effectiveFrom: '2026-10-01',
      effectiveTo: '2027-03-31',
    });
    expect((form.get('file') as File).name).toBe('order.pdf');
  });

  it('SRS-G-38: supersede starts from the Active circular; withdraw asks for a reason; a refusal shows', async () => {
    const calls = routes(() => problem(409, 'MST-409-CIRCULAR', ['HQ/DA/1']));
    wrap(<Circulars />, ['M-25:VIEW', 'M-25:CREATE', 'M-25:EDIT']);
    await screen.findByText('HQ/DA/1');
    await userEvent.click(screen.getByRole('button', { name: t('m25.act.supersede') }));
    expect(screen.getByText(t('m25.supersedeTitle'))).toBeTruthy();
    await userEvent.type(screen.getByLabelText(t('m25.number')), 'HQ/DA/1');
    await userEvent.type(screen.getByLabelText(t('m25.subject')), 'Again');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Supersede');
    await userEvent.upload(
      screen.getByLabelText(t('m25.document')),
      new File(['%PDF-'], 'o.pdf', { type: 'application/pdf' }),
    );
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByText(t('error.MST-409-CIRCULAR', ['HQ/DA/1']))).toBeTruthy();
    const form = sent(calls, 'POST', '/v1/masters/circulars')[0] as FormData;
    expect(JSON.parse(await (form.get('circular') as Blob).text())).toMatchObject({
      supersedesId: 5,
      payrollTypes: ['STAFF'],
    });
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));

    // A withdrawal cancelled at its reason sends nothing.
    await userEvent.click(screen.getByRole('button', { name: t('m25.act.withdraw') }));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(sent(calls, 'POST', '/circulars/5/withdraw')).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: t('m25.act.withdraw') }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Order cancelled');
    await userEvent.click(within(dialog).getByRole('button', { name: t('action.withdraw') }));
    await screen.findByText(t('m25.withdrawSubmitted', ['HQ/DA/1']));
    expect(sent(calls, 'POST', '/circulars/5/withdraw')[0]).toMatchObject({ reason: 'Order cancelled' });
  });
  it('SRS-G-48: cancelling the OTP keeps the circular form; a cancelled withdrawal shows no error; where-used follows the selected row', async () => {
    backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/masters/circulars/offices': () => [],
      'GET /v1/masters/circulars/7/where-used': () =>
        paged([
          {
            kind: 'MASTER_ENTRY',
            entity: 'rate_set',
            key: 'RS-1',
            screen: null,
            status: 'ACTIVE',
            linkedAt: null,
          },
        ]),
      'GET /v1/masters/circulars/5/where-used': () => paged([]),
      'GET /v1/masters/circulars': () =>
        paged([
          circular({
            id: 7,
            number: 'HQ/X/7',
            status: 'WITHDRAWN',
            appliesAllOffices: false,
            offices: [{ scopeType: 'LOCATION', payrollType: null, locationId: 9, office: null }],
          }),
          circular(),
        ]),
      'POST /v1/masters/circulars/5/withdraw': otpRequired,
      'POST /v1/masters/circulars': otpRequired,
    });
    wrap(<Circulars />, ['M-25:VIEW', 'M-25:CREATE', 'M-25:EDIT']);
    expect(await screen.findByText('HQ/X/7')).toBeTruthy();
    expect(await screen.findByText('RS-1')).toBeTruthy();
    // An office without a label shows its id; no screen and no link time show a dash.
    expect(screen.getByText('9')).toBeTruthy();
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(2);
    await userEvent.click(screen.getByText('HQ/DA/1'));
    await waitFor(() => expect(screen.queryByText('RS-1')).toBeNull());

    await userEvent.click(screen.getByRole('button', { name: t('m25.act.withdraw') }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Cancelled');
    await userEvent.click(within(dialog).getByRole('button', { name: t('action.withdraw') }));
    await cancelOtp();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(screen.queryByRole('alert')).toBeNull();

    await userEvent.click(screen.getByRole('button', { name: t('m25.act.new') }));
    await userEvent.type(screen.getByLabelText(t('m25.number')), 'HQ/X/8');
    await userEvent.type(screen.getByLabelText(t('m25.subject')), 'S');
    await waitFor(() =>
      expect(screen.getAllByRole('option', { name: 'HQ Personnel' }).length).toBeGreaterThan(0),
    );
    await userEvent.selectOptions(screen.getByLabelText(t('m25.issuingAuthority')), 'HQ_PERSONNEL');
    await userEvent.selectOptions(
      screen.getAllByLabelText(t('m25.category')).at(-1) as HTMLElement,
      'LABOUR_NORM',
    );
    await userEvent.click(screen.getByRole('checkbox', { name: t('m14.type.STAFF') }));
    await userEvent.type(screen.getByLabelText(t('mst.to')), '2027-03-31');
    await userEvent.clear(screen.getByLabelText(t('mst.to')));
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'r');
    await userEvent.upload(
      screen.getByLabelText(t('m25.document')),
      new File(['%PDF-1.7'], 'o.pdf', { type: 'application/pdf' }),
    );
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await cancelOtp();
    expect((screen.getByLabelText(t('m25.number')) as HTMLInputElement).value).toBe('HQ/X/8');
    expect(screen.queryByRole('alert')).toBeNull();
  });
});

// ---------------------------------------------------------------- M-26

describe('M-26 Labels', () => {
  const label = (over: object = {}) => ({
    id: 11,
    key: 'DESIG.SARDAR',
    type: 'MASTER_VALUE',
    masterEntity: 'ORG_DESIGNATION',
    masterCode: 'SARDAR',
    language: 'EN',
    scope: 'GLOBAL',
    scopeLocationId: null,
    scopeOffice: null,
    text: 'Sardar',
    localisable: true,
    versionNo: 1,
    effectiveFrom: '2026-04-01',
    effectiveTo: null,
    status: 'ACTIVE',
    approvalId: 1,
    changedBy: 'seed',
    ...over,
  });
  const LOCAL = label({
    id: 12,
    scope: 'OFFICE',
    scopeOffice: 'DH001 Delhi',
    text: 'Mukadam',
    language: 'HI',
    effectiveTo: '2026-12-31',
  });
  function routes(post: () => unknown = accepted(label({ status: 'PENDING_APPROVAL' }))) {
    return backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/masters/labels/missing-hindi': () => paged([label()]),
      'GET /v1/masters/labels': () =>
        paged([
          label(),
          LOCAL,
          label({
            id: 13,
            key: 'LBL.X',
            type: 'UI_LABEL',
            masterEntity: null,
            masterCode: null,
            localisable: false,
            status: 'SUPERSEDED',
          }),
        ]),
      'POST /v1/masters/labels/12/withdraw': accepted(LOCAL),
      'POST /v1/masters/labels/upload': accepted([label(), LOCAL]),
      'POST /v1/masters/labels': post,
    });
  }

  const editOf = (key: string) =>
    screen.getAllByRole('button', { name: `${t('common.edit')} ${key}` })[0] as HTMLElement;
  const viewOf = (key: string, at: number) =>
    screen.getAllByRole('button', { name: `${t('common.view')} ${key}` })[at] as HTMLElement;

  it('SRS-G-39: lists labels with scope and status, the missing-Hindi report, and the export', async () => {
    const calls = routes();
    wrap(<Labels />, ['M-26:VIEW', 'M-26:EXPORT']);
    expect(await screen.findByText('Mukadam')).toBeTruthy();
    expect(screen.getByText(`${t('m26.scope.OFFICE')} — DH001 Delhi`)).toBeTruthy();
    expect(screen.getByText(t('m26.until', [date('2026-12-31')]))).toBeTruthy();
    expect(screen.getByRole('button', { name: t('action.export') })).toBeTruthy();
    expect(screen.getByRole('button', { name: t('m26.act.download') })).toBeTruthy();
    expect(screen.queryByRole('button', { name: t('action.newLabel') })).toBeNull();
    const missing = screen.getByRole('button', { name: t('m26.act.missing') });
    await userEvent.click(missing);
    await waitFor(() => expect(screen.queryByText('Mukadam')).toBeNull());
    expect(calls.some((c) => c.url.includes('/labels/missing-hindi'))).toBe(true);
    // Each row of the report is the Hindi row that is missing.
    expect(await screen.findByText(t('m26.status.HINDI_PENDING'))).toBeTruthy();
    expect(screen.getByText('HI')).toBeTruthy();
    expect(screen.getByText(t('m26.missingNote'))).toBeTruthy();
    await userEvent.click(missing);
    await userEvent.selectOptions(screen.getByLabelText(t('m26.scope')), 'OFFICE');
    await waitFor(() => expect(calls.some((c) => c.url.includes('scope=OFFICE'))).toBe(true));
  });

  it('SRS-G-39: a label is proposed; screen text stays Global; a revision starts from the label', async () => {
    const calls = routes();
    wrap(<Labels />, ['M-26:VIEW', 'M-26:CREATE', 'M-26:EDIT']);
    await userEvent.click(await screen.findByRole('button', { name: t('m26.act.upload') }));
    await userEvent.click(screen.getByRole('button', { name: t('action.newLabel') }));
    expect((screen.getAllByLabelText(t('m26.scope')).at(-1) as HTMLSelectElement).disabled).toBe(true);
    expect(screen.getByText(t('m26.scopeHint'))).toBeTruthy();
    await userEvent.type(screen.getByLabelText(t('m26.key')), 'LBL.NEW');
    await userEvent.type(screen.getByLabelText(t('m26.text')), 'नया');
    await userEvent.type(screen.getAllByLabelText(t('common.reason'))[0] as HTMLElement, 'Hindi caption');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await screen.findByText(t('m26.submitted', ['LBL.NEW']));
    expect(sent(calls, 'POST', '/v1/masters/labels')[0]).toMatchObject({
      key: 'LBL.NEW',
      type: 'UI_LABEL',
      scope: 'GLOBAL',
      language: 'HI',
      text: 'नया',
    });
    // Revise a localisable master value as a local name.
    await userEvent.click(editOf('DESIG.SARDAR'));
    expect((screen.getByLabelText(t('m26.masterCode')) as HTMLInputElement).value).toBe('SARDAR');
    expect(screen.getByLabelText(t('m26.localisable'))).toBeTruthy();
    await userEvent.selectOptions(screen.getAllByLabelText(t('m26.scope')).at(-1) as HTMLElement, 'OFFICE');
    expect(screen.getByText(t('m26.localNote'))).toBeTruthy();
    expect(screen.queryByLabelText(t('m26.localisable'))).toBeNull();
    await userEvent.selectOptions(screen.getByLabelText(t('m26.typeLabel')), 'HELP_TEXT');
    expect(screen.queryByLabelText(t('m26.masterCode'))).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
  });

  // UI_BMAD_g1 #48: SRS M-26 "Bulk upload (Administrator)": an office maker sees the upload
  // disabled with the reason and sends nothing.
  it('SRS M-26: bulk upload is for the administrator; a maker sees it disabled with the reason', async () => {
    const calls = routes();
    wrap(<Labels />, ['M-26:VIEW', 'M-26:CREATE']);
    const upload = await screen.findByRole('button', { name: t('m26.act.upload') });
    expect(upload.hasAttribute('disabled')).toBe(true);
    expect(upload.getAttribute('title')).toBe(t('m26.why.upload'));
    expect(upload.getAttribute('data-why')).toBe(t('m26.why.upload'));
    await userEvent.click(upload);
    expect(screen.queryByLabelText(t('m26.file'))).toBeNull();
    expect(calls.some((c) => c.url.includes('/labels/upload'))).toBe(false);
  });

  it('SRS-G-39: a refused label shows the reason; HQ withdraws a local name; a file is uploaded', async () => {
    const calls = routes(() => problem(409, 'MST-409-LABEL', ['K', 'HI', '2026-04-01']));
    wrap(<Labels />, ['M-26:VIEW', 'M-26:CREATE', 'M-26:EDIT']);
    await userEvent.click(await screen.findByRole('button', { name: t('m26.act.upload') }));
    await userEvent.click(screen.getByRole('button', { name: t('action.newLabel') }));
    await userEvent.type(screen.getByLabelText(t('m26.key')), 'K');
    await userEvent.type(screen.getByLabelText(t('m26.text')), 'x');
    await userEvent.type(screen.getAllByLabelText(t('common.reason'))[0] as HTMLElement, 'x');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByText(t('error.MST-409-LABEL', ['K', 'HI', '2026-04-01']))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));

    // Only the local name (not a Global label) can be withdrawn, from the record's View.
    await userEvent.click(viewOf('DESIG.SARDAR', 0));
    const global = screen.getByRole('dialog');
    expect(within(global).queryByRole('button', { name: t('action.withdraw') })).toBeNull();
    await userEvent.click(within(global).getByRole('button', { name: t('common.close') }));
    // A withdrawal cancelled at its reason sends nothing.
    await userEvent.click(viewOf('DESIG.SARDAR', 1));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('action.withdraw') }),
    );
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
    await userEvent.click(viewOf('DESIG.SARDAR', 1));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('action.withdraw') }),
    );
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Name not used');
    await userEvent.click(within(dialog).getByRole('button', { name: t('action.withdraw') }));
    await screen.findByText(t('m26.withdrawSubmitted', ['DESIG.SARDAR']));

    const upload = screen.getByRole('button', { name: t('m26.upload') });
    expect(upload.hasAttribute('disabled')).toBe(true);
    await userEvent.upload(
      screen.getByLabelText(t('m26.file')),
      new File(['label_key'], 'l.csv', { type: 'text/csv' }),
    );
    await userEvent.type(screen.getAllByLabelText(t('common.reason')).at(-1) as HTMLElement, 'Bulk');
    await userEvent.click(upload);
    await screen.findByText(t('m26.uploaded', [2]));
    expect(calls.some((c) => c.method === 'POST' && c.url.includes('/labels/upload?reason=Bulk'))).toBe(true);
  });
  it('SRS-G-39: the filters go to the server; language, start date and localisable reach the proposal; a refused upload shows its reason', async () => {
    const calls = backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/masters/labels/missing-hindi': () => paged([]),
      'GET /v1/masters/labels': () => paged([label()]),
      'POST /v1/masters/labels': accepted(label({ status: 'PENDING_APPROVAL' })),
      'POST /v1/masters/labels/upload': () => problem(422, 'MST-422-LABEL'),
      'POST /v1/masters/labels/11/withdraw': accepted(label()),
    });
    wrap(<Labels />, ['M-26:VIEW', 'M-26:CREATE', 'M-26:EDIT']);
    await screen.findByText('Sardar');
    // SRS-NF-3: the filters are applied by the server, before paging.
    await userEvent.type(screen.getByLabelText(t('m26.search')), 'Sar');
    await userEvent.selectOptions(screen.getAllByLabelText(t('m26.language'))[0] as HTMLElement, 'EN');
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('text=Sar') && c.url.includes('language=EN'))).toBe(true),
    );
    // Revising a localisable master value: language, start date and the localisable flag are sent.
    await userEvent.click(editOf('DESIG.SARDAR'));
    await userEvent.selectOptions(screen.getAllByLabelText(t('m26.language')).at(-1) as HTMLElement, 'HI');
    const from = screen.getByLabelText(t('mst.from')) as HTMLInputElement;
    await userEvent.clear(from);
    await userEvent.type(from, '2027-04-01');
    await userEvent.click(screen.getByLabelText(t('m26.localisable')));
    await userEvent.type(screen.getByLabelText(t('m26.text')), 'सरदार');
    await userEvent.type(screen.getAllByLabelText(t('common.reason'))[0] as HTMLElement, 'Hindi caption');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await waitFor(() => expect(sent(calls, 'POST', '/v1/masters/labels')).toHaveLength(1));
    expect(sent(calls, 'POST', '/v1/masters/labels')[0]).toMatchObject({
      language: 'HI',
      effectiveFrom: '2027-04-01',
      localisable: false,
    });
    // A refused upload shows the server's reason.
    await userEvent.click(screen.getByRole('button', { name: t('m26.act.upload') }));
    await userEvent.upload(
      screen.getByLabelText(t('m26.file')),
      new File(['x'], 'l.csv', { type: 'text/csv' }),
    );
    await userEvent.type(screen.getAllByLabelText(t('common.reason')).at(-1) as HTMLElement, 'Bulk');
    await userEvent.click(screen.getByRole('button', { name: t('m26.upload') }));
    expect(await screen.findByText(t('error.MST-422-LABEL'))).toBeTruthy();
  });

  it('SRS-G-48: cancelling the OTP keeps the label form; a cancelled withdrawal shows no error; a cleared master field is null', async () => {
    backend({
      'GET /v1/reference/lists': () => REFERENCE,
      'GET /v1/masters/labels/missing-hindi': () => paged([]),
      'GET /v1/masters/labels': () =>
        paged([LOCAL, label({ id: 14, key: 'DESIG.X', masterEntity: null, masterCode: 'X' })]),
      'POST /v1/masters/labels/12/withdraw': otpRequired,
      'POST /v1/masters/labels': otpRequired,
    });
    wrap(<Labels />, ['M-26:VIEW', 'M-26:CREATE', 'M-26:EDIT']);
    expect(await screen.findByText('DESIG.X')).toBeTruthy();
    // A master value whose master is unknown shows the code alone.
    await userEvent.click(viewOf('DESIG.X', 0));
    const record = within(screen.getByRole('dialog'));
    expect(record.getByText('X')).toBeTruthy();
    expect(record.getAllByText('—').length).toBeGreaterThanOrEqual(1);
    await userEvent.click(record.getByRole('button', { name: t('common.close') }));
    await userEvent.click(editOf('DESIG.X'));
    const entity = screen.getByLabelText(t('m26.masterEntity')) as HTMLInputElement;
    const code = screen.getByLabelText(t('m26.masterCode')) as HTMLInputElement;
    await userEvent.clear(code);
    expect(code.value).toBe('');
    await userEvent.type(code, 'Y');
    await userEvent.type(entity, 'ORG_DESIGNATION');
    await userEvent.clear(entity);
    await userEvent.type(entity, 'ORG_DESIGNATION');
    await userEvent.type(screen.getByLabelText(t('m26.text')), 'x');
    await userEvent.type(screen.getAllByLabelText(t('common.reason'))[0] as HTMLElement, 'r');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await cancelOtp();
    expect((screen.getByLabelText(t('m26.key')) as HTMLInputElement).value).toBe('DESIG.X');
    expect(screen.queryByRole('alert')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));

    await userEvent.click(viewOf('DESIG.SARDAR', 0));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('action.withdraw') }),
    );
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox'), 'Cancelled');
    await userEvent.click(within(dialog).getByRole('button', { name: t('action.withdraw') }));
    await cancelOtp();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(screen.queryByRole('alert')).toBeNull();
  });
});

// ---------------------------------------------------------------- M-27

describe('M-27 Operations', () => {
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
    circularNumber: 'HQ/LN/1',
    status: 'APPROVED',
    approvalId: 1,
    ...over,
  });
  function routes(
    post: () => unknown = accepted(op({ status: 'PENDING_APPROVAL' })),
    end: () => unknown = accepted({ code: 'WTP', current: op(), pending: null }),
  ) {
    return backend({
      'GET /v1/masters/circulars/current': () => [
        { id: 5, number: 'HQ/LN/1', subject: 'Norms', status: 'ACTIVE' },
      ],
      'GET /v1/masters/operations/WTP/versions': () =>
        paged([op({ effectiveTo: '2026-12-31', rateNormLink: 'TIME_BASED' })]),
      'GET /v1/masters/operations': () =>
        paged([
          { code: 'WTP', current: op(), pending: null },
          {
            code: 'DUST',
            current: null,
            pending: op({ name: 'Dusting', versionNo: 1, status: 'PENDING_APPROVAL' }),
          },
        ]),
      'POST /v1/masters/operations/WTP/end-date': end,
      'POST /v1/masters/operations/': post,
    });
  }

  it('SRS-G-41: lists operations with payrolls, link and version; history', async () => {
    routes();
    wrap(<Operations />, ['M-27:VIEW']);
    expect(await screen.findByText('Wagon to Platform')).toBeTruthy();
    expect(
      screen.getAllByText(`${t('m27.short.dept')} · ${t('m27.short.dps')}`).length,
    ).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText(t('m27.bandFromMaster')).length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('HQ/LN/1').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText(t('m27.notInForce'))).toBeTruthy();
    const locked = screen.getByRole('button', { name: t('m27.act.new') });
    expect(locked).toBeDisabled();
    expect(locked.getAttribute('data-why')).toBe(t('m27.why.new'));
    await userEvent.click(screen.getByRole('button', { name: t('wf.historyAct') }));
    expect(await screen.findByText(t('m27.linkShown.TIME_BASED'))).toBeTruthy();
  });

  // NEW-G1-05 / NEW-G1-06 (SRS-G-39): the local and the standard name together; the search goes to
  // the server with the text and the user's language; a row without a local name shows the standard.
  it('SRS-G-39: an operation shows "local (standard)" and is searched by any label on the server', async () => {
    const calls = backend({
      'GET /v1/masters/circulars/current': () => [],
      'GET /v1/masters/operations': () =>
        paged([
          { code: 'WTP', current: op(), pending: null, shownName: 'Rail Utarai' },
          { code: 'STK', current: op({ name: 'Stacking', bagBased: false }), pending: null, shownName: null },
        ]),
    });
    wrap(<Operations />, ['M-27:VIEW']);
    expect(await screen.findByText(t('m27.localName', ['Rail Utarai', 'Wagon to Platform']))).toBeTruthy();
    expect(screen.getByText('Stacking')).toBeTruthy();
    await userEvent.type(screen.getByLabelText(t('m27.search')), 'utarai');
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('/v1/masters/operations?q=utarai&lang=en'))).toBe(true),
    );
  });

  // SC-01-0189: Applicable Payroll Types of the operation.
  it('SRS-G-41: a new operation needs a payroll and a circular; bag-based forces the norm slab; Dusting names its pay code', async () => {
    const calls = routes();
    wrap(<Operations />, ['M-27:VIEW', 'M-27:CREATE']);
    await userEvent.click(await screen.findByRole('button', { name: t('m27.act.new') }));
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    await userEvent.type(screen.getByLabelText(t('m27.code')), 'fum');
    await userEvent.type(screen.getByLabelText(t('m27.name')), 'Fumigation');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Current system');
    expect(submit.hasAttribute('disabled')).toBe(true);
    await userEvent.click(screen.getByRole('checkbox', { name: t('m14.type.NWNP_LABOUR') }));
    await userEvent.click(screen.getByRole('checkbox', { name: t('m14.type.DPS_LABOUR') }));
    // Departmental ticked and cleared again: only the DPS and NWNP payrolls remain.
    const dept = screen.getByRole('checkbox', { name: t('m14.type.DEPT_LABOUR') });
    await userEvent.click(dept);
    await userEvent.click(dept);
    const fromField = screen.getByLabelText(t('mst.from')) as HTMLInputElement;
    await userEvent.clear(fromField);
    await userEvent.type(fromField, '2026-11-01');
    await userEvent.click(screen.getByLabelText(t('m27.bagBased')));
    expect((screen.getByLabelText(t('m27.link')) as HTMLSelectElement).value).toBe('NORM_SLAB');
    expect((screen.getByLabelText(t('m27.link')) as HTMLSelectElement).disabled).toBe(true);
    await userEvent.click(screen.getByLabelText(t('m27.bagBased')));
    await userEvent.selectOptions(screen.getByLabelText(t('m27.link')), 'ALLOWANCE');
    await userEvent.selectOptions(screen.getByLabelText(t('m27.normType')), 'PIECE_RATE');
    await userEvent.click(screen.getByLabelText(t('m27.presentButNoWork')));
    await userEvent.click(screen.getByLabelText(t('m27.dusting')));
    expect((screen.getByLabelText(t('m27.presentButNoWork')) as HTMLInputElement).disabled).toBe(true);
    await userEvent.type(screen.getByLabelText(t('m27.allowancePayCode')), '140');
    await waitFor(() => expect(screen.getByRole('option', { name: /HQ\/LN\/1/ })).toBeTruthy());
    await userEvent.selectOptions(screen.getByLabelText(t('m27.circular')), '5');
    await userEvent.click(submit);
    await screen.findByText(t('mst.submitted', ['FUM']));
    expect(sent(calls, 'POST', '/operations/FUM/versions')[0]).toMatchObject({
      name: 'Fumigation',
      departmental: false,
      dps: true,
      nwnp: true,
      bagBased: false,
      rateNormLink: 'ALLOWANCE',
      normType: 'PIECE_RATE',
      dusting: true,
      presentButNoWork: false,
      allowancePayCode: 140,
      circularId: 5,
      effectiveFrom: '2026-11-01',
    });
  });

  // SC-01-0184: an operation no longer used is end-dated, never deleted.
  it('SRS-G-41: a new version keeps the code and standard name; end-dating asks for the last day; a refusal shows', async () => {
    const calls = routes(() => problem(409, 'MST-409-OPERATION-NAME', ['WTP', 'Wagon to Platform']));
    wrap(<Operations />, ['M-27:VIEW', 'M-27:CREATE', 'M-27:EDIT']);
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.edit')} WTP` }));
    expect((screen.getByLabelText(t('m27.code')) as HTMLInputElement).disabled).toBe(true);
    expect((screen.getByLabelText(t('m27.name')) as HTMLInputElement).disabled).toBe(true);
    expect(screen.getByText(t('m27.nameFixed'))).toBeTruthy();
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Revised slab');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(
      await screen.findByText(t('error.MST-409-OPERATION-NAME', ['WTP', 'Wagon to Platform'])),
    ).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));

    await userEvent.click(screen.getByRole('button', { name: `${t('common.view')} WTP` }));
    await userEvent.click(screen.getByRole('button', { name: t('action.endDate') }));
    expect(screen.getByText(t('m27.endTitle', ['WTP']))).toBeTruthy();
    await userEvent.clear(screen.getByLabelText(t('m27.lastDay')));
    await userEvent.type(screen.getByLabelText(t('m27.lastDay')), '2027-03-31');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'No longer used');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await screen.findByText(t('m27.endSubmitted', ['WTP']));
    expect(sent(calls, 'POST', '/operations/WTP/end-date')[0]).toMatchObject({
      lastDay: '2027-03-31',
      reason: 'No longer used',
    });
  });
  it('SRS-G-41: a closed operation, one not yet in force, no active circular, and cleared fields', async () => {
    backend({
      'GET /v1/masters/circulars/current': () => [],
      'GET /v1/masters/operations/WTP/versions': () => paged([op({ effectiveTo: null, nwnp: true })]),
      'GET /v1/masters/operations': () =>
        paged([
          {
            code: 'WTP',
            current: op({ effectiveTo: '2026-12-31', nwnp: true, departmental: false }),
            pending: null,
          },
          { code: 'NEW', current: null, pending: null },
        ]),
    });
    wrap(<Operations />, ['M-27:VIEW', 'M-27:CREATE', 'M-27:EDIT']);
    expect(await screen.findByText(`${t('m27.short.dps')} · ${t('m27.short.nwnp')}`)).toBeTruthy();
    expect(screen.getAllByText(new RegExp(date('2026-12-31'))).length).toBeGreaterThan(0);
    expect(screen.getByText(t('m27.status.END_DATED'))).toBeTruthy();
    // A closed operation is not end-dated again; one without a version has no name yet.
    await userEvent.click(screen.getByRole('button', { name: `${t('common.view')} WTP` }));
    expect(screen.queryByRole('button', { name: t('action.endDate') })).toBeNull();
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.close') }),
    );
    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
    await userEvent.click(screen.getByRole('button', { name: t('wf.historyAct') }));
    await screen.findByText(t('mst.historyOf', ['WTP']));
    expect((await screen.findAllByText(t('m27.linkShown.NORM_SLAB'))).length).toBeGreaterThanOrEqual(2);
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    expect(screen.queryByText(t('mst.historyOf', ['WTP']))).toBeNull();

    await userEvent.click(screen.getByRole('button', { name: `${t('common.edit')} WTP` }));
    expect(screen.getByText(t('m27.noActiveCircular'))).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText(t('m27.normType')), '');
    await userEvent.selectOptions(screen.getByLabelText(t('m27.circular')), '');
    await userEvent.click(screen.getByLabelText(t('m27.dusting')));
    await userEvent.type(screen.getByLabelText(t('m27.allowancePayCode')), '5');
    await userEvent.clear(screen.getByLabelText(t('m27.allowancePayCode')));
    expect((screen.getByLabelText(t('m27.allowancePayCode')) as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText(t('m27.normType')) as HTMLSelectElement).value).toBe('');
    expect(screen.getByRole('button', { name: t('common.submitForApproval') }).hasAttribute('disabled')).toBe(
      true,
    );
  });

  it('SRS-G-41: a refused end date is shown; cancel closes the end-date form and clears it', async () => {
    const calls = routes(undefined, () => problem(422, 'MST-422-DATE', ['2026-11-01']));
    wrap(<Operations />, ['M-27:VIEW', 'M-27:EDIT']);
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.view')} WTP` }));
    await userEvent.click(screen.getByRole('button', { name: t('action.endDate') }));
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'No longer used');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByText(t('error.MST-422-DATE', ['2026-11-01']))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByText(t('m27.endTitle', ['WTP']))).toBeNull();
    expect(screen.queryByText(t('error.MST-422-DATE', ['2026-11-01']))).toBeNull();
    expect(sent(calls, 'POST', '/operations/WTP/end-date')).toHaveLength(1);
  });

  it('SRS-G-48: cancelling the OTP keeps the operation form and the end-date form open, without an error', async () => {
    backend({
      'GET /v1/masters/circulars/current': () => [
        { id: 5, number: 'HQ/LN/1', subject: 'Norms', status: 'ACTIVE' },
      ],
      'GET /v1/masters/operations': () => paged([{ code: 'WTP', current: op(), pending: null }]),
      'POST /v1/masters/operations/WTP/end-date': otpRequired,
      'POST /v1/masters/operations/': otpRequired,
    });
    wrap(<Operations />, ['M-27:VIEW', 'M-27:CREATE', 'M-27:EDIT']);
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.edit')} WTP` }));
    await waitFor(() => expect(screen.getByRole('option', { name: /HQ\/LN\/1/ })).toBeTruthy());
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'r');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await cancelOtp();
    expect(screen.getByLabelText(t('m27.code'))).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));

    await userEvent.click(screen.getByRole('button', { name: `${t('common.view')} WTP` }));
    await userEvent.click(screen.getByRole('button', { name: t('action.endDate') }));
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'r');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await cancelOtp();
    expect(screen.getByText(t('m27.endTitle', ['WTP']))).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
