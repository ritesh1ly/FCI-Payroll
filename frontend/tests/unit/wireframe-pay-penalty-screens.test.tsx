import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PenaltyRulesScreen } from '@/modules/configuration';
import { IncrementScreen, PayScalesScreen } from '@/modules/increment';
import { PenaltyScreen } from '@/modules/penalty';
import { revocable, changeable } from '@/modules/penalty/business/penaltyRules';
import { backend, me, paged, referenceLists, t, wrap } from './support/harness';

/**
 * M-03, I-05, M-14 and I-06 as Wireframes v6.1 draws them (OD-121): the drawn objects, the action a role may not use
 * disabled with its reason (never hidden, SRS-G-25), no right widened to match a drawing (OD-135), and the closed
 * section that keeps every function of the old screen.
 */

afterEach(() => vi.unstubAllGlobals());

function why(name: string): string | null {
  return screen.getByRole('button', { name }).getAttribute('data-why');
}

const SCALE = {
  bandId: 1,
  code: 'E-4',
  name: 'Executive 4',
  scaleType: 'IDA',
  min: 60000,
  max: 90000,
  stagnationAmount: 2100,
  effectiveFrom: '2026-04-01',
  stages: 12,
  pending: false,
  active: true,
  incrementRuleKey: 'INCR_E4',
};

function m03Routes() {
  return backend({
    'GET /v1/pay-scales/mapping': () =>
      paged([
        {
          gradeCode: 'E-4',
          gradeName: 'Manager',
          designationCode: 'MGR',
          designationName: 'Manager',
          scaleCode: 'E-4',
          headcount: 4,
        },
        {
          gradeCode: 'E-7',
          gradeName: 'ED',
          designationCode: 'ED',
          designationName: 'Executive Director',
          scaleCode: null,
          headcount: 1,
        },
      ]),
    'GET /v1/pay-scales': () => paged([SCALE]),
    'GET /v1/masters/circulars/current': () => [],
    'GET /v1/pay-scales/wage-revisions': () => [],
    'GET /v1/document-types': () => [],
  });
}

const STORED = {
  id: 3,
  fileName: 'scale-order-E-4.pdf',
  documentTypeCode: 'SANCTION_ORDER',
  documentTypeName: 'Sanction Order',
  referenceNumber: 'FCI/EST/SCALE/E-4',
  uploadedBy: 'su.hq@fci-demo.test',
  uploadedAt: '2026-10-01T06:00:00Z',
};
const ORDER_FILE = () => new File(['%PDF-1.7'], 'order.pdf', { type: 'application/pdf' });

/** Fills the drawn order card, whose fields carry no accessible name of their own. */
async function fillOrderCard(card: HTMLElement) {
  const inputs = card.querySelectorAll('input');
  await userEvent.type(inputs[0] as HTMLElement, 'FCI/EST/2026/1');
  await userEvent.type(inputs[1] as HTMLElement, '2026-01-02');
  await userEvent.type(inputs[2] as HTMLElement, 'Headquarters');
  return inputs[3] as HTMLInputElement;
}

describe('M-03 order card and form as drawn', () => {
  const orderRoutes = (attached: { body?: unknown }[]) =>
    backend({
      'GET /v1/pay-scales/mapping': () => paged([]),
      'GET /v1/pay-scales/E-4/orders': () => [STORED],
      'POST /v1/pay-scales/E-4/orders': (_url: string, init?: RequestInit) => {
        attached.push({ body: init?.body });
        return { status: 201, body: STORED };
      },
      'GET /v1/pay-scales': () => paged([SCALE]),
      'GET /v1/reference/lists': () =>
        referenceLists({
          PAY_SCALE_TYPE: [
            ['IDA', 'IDA'],
            ['CDA', 'CDA'],
          ],
          ORDER_COPY_DOCUMENT_TYPE: [['SANCTION_ORDER', 'Sanction Order']],
        }),
      'GET /v1/document-types': () => [
        { code: 'SANCTION_ORDER', name: 'Sanction Order', referenceRequired: true },
      ],
    });

  it('SRS_G_30_theCardOpensTheStoredOrderOfTheFirstScaleForAnyViewer', async () => {
    orderRoutes([]);
    wrap(<PayScalesScreen />, me(['M-03:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    const row = (await screen.findByText('scale-order-E-4.pdf')).closest('tr') as HTMLElement;
    expect(
      within(row)
        .getByRole('link', { name: t('wf.view') })
        .getAttribute('href'),
    ).toContain('/v1/pay-scales/E-4/files/3');
    // Rule entry is not this role's: the fields stay disabled, as drawn.
    const card = row.closest('[data-order-card]') as HTMLElement;
    expect(card.querySelector('input')).toBeDisabled();
  });

  it('OD_135_theAdministratorTypesInTheCardAndNothingIsSent', async () => {
    const attached: { body?: unknown }[] = [];
    orderRoutes(attached);
    wrap(<PayScalesScreen />, me(['M-03:VIEW'], { roles: ['SYSTEM_ADMINISTRATOR'] }));
    const row = (await screen.findByText('scale-order-E-4.pdf')).closest('tr') as HTMLElement;
    const card = row.closest('[data-order-card]') as HTMLElement;
    const file = await fillOrderCard(card);
    expect(file).toBeEnabled();
    await userEvent.upload(file, ORDER_FILE());
    expect(within(card).getByRole('status')).toHaveTextContent(t('wf.ord.viewOnly'));
    expect(attached).toEqual([]);
  });

  it('SRS_G_30_theHolderOfM03CreateStoresTheCopyOnTheScale', async () => {
    const attached: { body?: unknown }[] = [];
    orderRoutes(attached);
    wrap(
      <PayScalesScreen />,
      me(['M-03:VIEW', 'M-03:CREATE'], { roles: ['RULE_ADMINISTRATOR'], atHeadquarters: true }),
    );
    const row = (await screen.findByText('scale-order-E-4.pdf')).closest('tr') as HTMLElement;
    const card = row.closest('[data-order-card]') as HTMLElement;
    await screen.findByRole('option', { name: 'Sanction Order' });
    const file = await fillOrderCard(card);
    await userEvent.upload(file, ORDER_FILE());
    await waitFor(() => expect(attached).toHaveLength(1));
    const form = attached[0]?.body as FormData;
    expect(form.get('documentType')).toBe('SANCTION_ORDER');
    expect(form.get('issuingAuthority')).toBe('Headquarters');
  });

  it('SRS_L3946_theDrawnPatternIsIdaOrCda_minimumAndMaximumAreNumbers', async () => {
    orderRoutes([]);
    wrap(<PayScalesScreen />, me(['M-03:VIEW'], { roles: ['SYSTEM_ADMINISTRATOR'] }));
    await screen.findByRole('option', { name: 'CDA' });
    const pattern = document.getElementById('ps_pattern') as HTMLSelectElement;
    expect([...pattern.options].map((o) => o.textContent)).toEqual([t('common.select'), 'IDA', 'CDA']);
    expect(t('common.select')).not.toBe('common.select');
    expect(document.getElementById('ps_min')).toHaveAttribute('type', 'number');
    expect(document.getElementById('ps_max')).toHaveAttribute('type', 'number');
  });
});

describe('M-03 as drawn', () => {
  it('SRS_13_23_aRoleWithoutTheEntrySeesTheNoteAndTheDisabledButtonWithItsReason', async () => {
    m03Routes();
    wrap(<PayScalesScreen />, me(['M-03:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    expect(await screen.findByText(t('m03w.adminOnly'))).toBeTruthy();
    expect(why(t('m03w.newVersion'))).toBe(t('m03w.why.admin'));
    expect(await screen.findByText('E-4')).toBeTruthy();
  });

  it('OD_135_theSystemAdministratorSeesTheDrawnFormAndIsToldWhyOnSave', async () => {
    m03Routes();
    wrap(<PayScalesScreen />, me(['M-03:VIEW'], { roles: ['SYSTEM_ADMINISTRATOR'] }));
    await userEvent.click(await screen.findByRole('button', { name: t('m03w.save') }));
    expect(await screen.findByText(t('mst.why.notMaker'))).toBeTruthy();
    expect(screen.queryByTestId('m03-register')?.hasAttribute('open')).toBe(false);
  });

  it('SRS_13_23_theFormCarriesWhatItDrawsIntoTheFullFormOfTheRegister', async () => {
    m03Routes();
    wrap(<PayScalesScreen />, me(['M-03:VIEW', 'M-03:CREATE'], { roles: ['RULE_ADMINISTRATOR'] }));
    await userEvent.type(await screen.findByLabelText(/Scale Code/), 'E-9');
    await userEvent.click(screen.getByRole('button', { name: t('m03w.save') }));
    await waitFor(() => expect(screen.getByTestId('m03-register').hasAttribute('open')).toBe(true));
    expect(screen.getAllByDisplayValue('E-9').length).toBe(2);
  });

  it('SRS_20_29_aMappedAndAnUnmappedDesignationAreTold', async () => {
    m03Routes();
    wrap(<PayScalesScreen />, me(['M-03:VIEW'], { roles: ['PAYROLL_CHECKER'] }));
    expect(await screen.findByText(t('m03w.map.unmapped'))).toBeTruthy();
    expect(screen.getByText(t('m03w.map.mapped'))).toBeTruthy();
  });

  it('SRS_13_23_theRowViewShowsTheRecord', async () => {
    m03Routes();
    wrap(<PayScalesScreen />, me(['M-03:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.view')} E-4` }));
    expect(await screen.findByRole('dialog')).toBeTruthy();
    expect(within(screen.getByRole('dialog')).getByText('INCR_E4')).toBeTruthy();
  });
});

const ROW = {
  kind: 'CASE',
  key: 'K1',
  employeeId: 2,
  eis: '881304',
  employee: 'Deepak Joshi',
  scale: 'E-4',
  type: 'INDIVIDUAL_REVISION',
  presentBasic: 60000,
  incrementPct: null,
  revisedBasic: 62400,
  effective: '2026-04-01',
  atMaximum: false,
  arrearEvent: 129,
  arrearAmount: 164250,
  status: 'CHECKER',
  flags: [],
  barredBy: [],
  orderReference: 'DL/EST/121',
  approvalId: 4,
};

function i05Routes() {
  return backend({
    'GET /v1/reference/lists': () =>
      referenceLists({ ORDER_COPY_DOCUMENT_TYPE: [['SANCTION_ORDER', 'Sanction Order']] }),
    'GET /v1/increments/counts': () => ({
      due: 10,
      annual: 7,
      stagnation: 3,
      fixations: 2,
      retrospective: 1,
      latestArrearEvent: 129,
      atMaximum: 5,
      duplicates: 0,
    }),
    'GET /v1/increments/worklist': () => paged([ROW]),
    'GET /v1/pay-fixation-cases/4/orders': () => [],
    'GET /v1/document-types': () => [],
  });
}

describe('I-05 as drawn', () => {
  it('SRS_13_20_theCheckerSeesComputeOffWithItsReasonAndNoEntry', async () => {
    i05Routes();
    wrap(<IncrementScreen />, me(['I-05:VIEW', 'I-05:APPROVE'], { roles: ['PAYROLL_CHECKER'] }));
    expect(await screen.findByText(t('i05w.tile.due'))).toBeTruthy();
    expect(why(t('i05w.compute'))).toBe(t('i05w.why.compute'));
    expect(why(t('i05w.correct'))).toBe(t('i05w.why.correct'));
  });

  it('SRS_13_20_theMakerGoesToI21NeverToACaseOfTheirOwn', async () => {
    i05Routes();
    wrap(<IncrementScreen />, me(['I-05:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    const compute = await screen.findByRole('link', { name: t('i05w.compute') });
    expect(compute.getAttribute('href')).toContain('s=I-21');
    expect(screen.getByRole('link', { name: t('i05w.correct') }).getAttribute('href')).toContain('s=I-21');
  });

  it('SRS_13_24_hrOpensTheCaseSectionWithCorrectIncrementDate', async () => {
    i05Routes();
    wrap(<IncrementScreen />, me(['I-05:VIEW', 'I-05:SUBMIT'], { roles: ['ESTABLISHMENT_HR'] }));
    await userEvent.click(await screen.findByRole('button', { name: t('i05w.correct') }));
    await waitFor(() => expect(screen.getByTestId('i05-register').hasAttribute('open')).toBe(true));
  });

  it('SRS_13_4_theWorklistAndTheDetailCarryTheArrearAndTheStatus', async () => {
    i05Routes();
    wrap(<IncrementScreen />, me(['I-05:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    expect(await screen.findByText(t('i05w.type.INDIVIDUAL_REVISION'))).toBeTruthy();
    expect(screen.getAllByText('AE-129').length).toBeGreaterThan(0);
    expect(screen.getAllByText(t('i05w.status.CHECKER')).length).toBeGreaterThan(0);
    await waitFor(() => expect(screen.getByText(t('i05w.detail', ['881304 Deepak Joshi']))).toBeTruthy());
  });
});

const M14_LISTS = referenceLists({
  PENALTY_PAYROLL_SCOPE: [
    ['STAFF', 'Employee / Staff'],
    ['ALL', 'All Payroll Types'],
  ],
  PENALTY_EXCLUDED_FROM: [
    ['SUPPLEMENTARY', 'Supplementary Payment Run'],
    ['BOTH', 'Both Regular and Supplementary Run'],
  ],
});

function m14Routes() {
  return backend({
    'GET /v1/reference/lists': () => M14_LISTS,
    'GET /v1/masters/net-pay-floors/guard-count': () => 14,
    'GET /v1/masters/net-pay-floors': () =>
      paged([
        { payrollType: 'STAFF', current: null, pending: null },
        {
          payrollType: 'DEPT_LABOUR',
          current: {
            id: 1,
            effectiveFrom: '2026-04-01',
            effectiveTo: null,
            floorBasis: 'ABSOLUTE_AMOUNT',
            floorValue: 5000,
            deductionCeilingPct: null,
            sourceInstrument: 'FCI order',
            confirmed: true,
            status: 'APPROVED',
          },
          pending: null,
        },
      ]),
    'GET /v1/masters/supplementary-exclusions': () => ({
      components: [
        { payCode: 366, name: 'Court Recovery', type: 'DEDUCTION', excluded: true },
        { payCode: 401, name: 'Recovery', type: 'DEDUCTION', excluded: false },
      ],
      pending: null,
    }),
  });
}

describe('I-05 order card as drawn', () => {
  it('SRS_13_18_hrHoldsTheOrderOnTheCardAndItIsCarriedIntoTheCaseForm_nothingIsStoredYet', async () => {
    const calls = i05Routes();
    wrap(<IncrementScreen />, me(['I-05:VIEW', 'I-05:SUBMIT'], { roles: ['ESTABLISHMENT_HR'] }));
    const card = (await screen.findByText(t('wf.ord.title'))).closest('[data-order-card]') as HTMLElement;
    const file = await fillOrderCard(card);
    expect(file).toBeEnabled();
    await userEvent.upload(file, ORDER_FILE());
    await waitFor(() =>
      expect(screen.getByDisplayValue('FCI/EST/2026/1').closest('[data-order-card]')).toBeNull(),
    );
    expect(screen.getByDisplayValue('2026-01-02')).toBeTruthy();
    expect(calls.filter((c) => c.method === 'POST')).toEqual([]);
  });

  it('SRS_13_20_aMakerTypesInTheCardAndNothingIsSent_theCheckerStaysDisabled', async () => {
    const calls = i05Routes();
    const { unmount } = wrap(<IncrementScreen />, me(['I-05:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    const card = (await screen.findByText(t('wf.ord.title'))).closest('[data-order-card]') as HTMLElement;
    const file = await fillOrderCard(card);
    await userEvent.upload(file, ORDER_FILE());
    expect(within(card).getByRole('status')).toHaveTextContent(t('wf.ord.viewOnly'));
    expect(calls.filter((c) => c.method === 'POST')).toEqual([]);
    unmount();
    wrap(<IncrementScreen />, me(['I-05:VIEW', 'I-05:APPROVE'], { roles: ['PAYROLL_CHECKER'] }));
    const off = (await screen.findByText(t('wf.ord.title'))).closest('[data-order-card]') as HTMLElement;
    expect(off.querySelector('input')).toBeDisabled();
  });
});

describe('M-14 as drawn', () => {
  it('SRS_16_13_aMakerViewsTheTilesAndTheTablesAndEntryIsOffWithItsReason', async () => {
    m14Routes();
    wrap(<PenaltyRulesScreen />, me(['M-14:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    expect((await screen.findAllByText('14')).length).toBeGreaterThan(0);
    expect(why(t('m14w.newFloor'))).toBe(t('m14w.why.admin'));
    expect(why(t('m14w.newExclusion'))).toBe(t('m14w.why.admin'));
    expect(screen.getByText(t('m14w.floorNote'))).toBeTruthy();
    expect(await screen.findByText('Court Recovery')).toBeTruthy();
    // R-RULE: a type without a floor says so; nothing is shown as zero.
    expect(screen.getAllByText(t('mst.notConfigured')).length).toBe(1);
  });

  it('M14_1_theFieldsWithNoColumnAreOffWithTheReason', async () => {
    m14Routes();
    wrap(
      <PenaltyRulesScreen />,
      me(['M-14:VIEW', 'M-14:CREATE', 'M-14:EDIT'], { roles: ['RULE_ADMINISTRATOR'] }),
    );
    const excluded = (await screen.findByLabelText(/Excluded From/)) as HTMLSelectElement;
    expect(excluded.disabled).toBe(true);
    expect(excluded.getAttribute('data-why')).toBe(t('m14w.why.noColumn'));
  });

  it('OD_123_theDrawnFormReadsItsListsAndAFloorForAllTypesCannotBeProposed', async () => {
    m14Routes();
    wrap(
      <PenaltyRulesScreen />,
      me(['M-14:VIEW', 'M-14:CREATE', 'M-14:EDIT'], { roles: ['RULE_ADMINISTRATOR'] }),
    );
    const scope = (await screen.findByLabelText(/Payroll Type Scope/)) as HTMLSelectElement;
    await within(scope).findByRole('option', { name: 'All Payroll Types' });
    expect(Array.from(scope.options).map((o) => o.value)).toEqual(['', 'STAFF', 'ALL']);
    const excluded = screen.getByLabelText(/Excluded From/) as HTMLSelectElement;
    expect(Array.from(excluded.options).map((o) => o.value)).toEqual(['', 'SUPPLEMENTARY', 'BOTH']);
    expect(screen.getByLabelText(/Floor Amount/)).toHaveAttribute('type', 'number');
    await userEvent.selectOptions(scope, 'ALL');
    const save = screen.getAllByRole('button', { name: t('m14w.save') })[0] as HTMLButtonElement;
    expect(save).toBeDisabled();
    expect(save).toHaveAttribute('data-why', t('m14w.why.allTypes'));
    // The name of a code stays M-07's: a role that may propose cannot type it, and the date waits for the owner.
    expect(screen.getByLabelText(/Component Description/)).toHaveAttribute('readonly');
    const dates = screen.getAllByLabelText(/Effective From/);
    expect(dates[1]).toBeDisabled();
    expect(dates[1]).toHaveAttribute('data-why', t('m14w.why.noColumn'));
  });

  it('OD_135_theDrawnAdministratorTypesInTheFormAndNothingIsSent', async () => {
    const calls = m14Routes();
    wrap(<PenaltyRulesScreen />, me(['M-14:VIEW'], { roles: ['SYSTEM_ADMINISTRATOR'] }));
    const name = await screen.findByLabelText(/Component Description/);
    expect(name).not.toHaveAttribute('readonly');
    await userEvent.type(name, 'Court');
    expect(name).toHaveValue('Court');
    expect(screen.getAllByLabelText(/Effective From/)[1]).toBeEnabled();
    expect(screen.getByLabelText(/Excluded From/)).toBeDisabled();
    await userEvent.click(screen.getAllByRole('button', { name: t('m14w.save') })[1] as HTMLElement);
    expect(await screen.findByText(t('mst.why.notMaker'))).toBeTruthy();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('OD_135_theSystemAdministratorIsToldWhy', async () => {
    m14Routes();
    wrap(<PenaltyRulesScreen />, me(['M-14:VIEW'], { roles: ['SYSTEM_ADMINISTRATOR'] }));
    const save = await screen.findAllByRole('button', { name: t('m14w.save') });
    await userEvent.click(save[0] as HTMLElement);
    expect(await screen.findByText(t('mst.why.notMaker'))).toBeTruthy();
  });

  it('SRS_14_9_theAdministratorProposesAnAmountWithItsDateAndReason', async () => {
    m14Routes();
    wrap(<PenaltyRulesScreen />, me(['M-14:VIEW', 'M-14:CREATE'], { roles: ['RULE_ADMINISTRATOR'] }));
    const save = (await screen.findAllByRole('button', { name: t('m14w.save') }))[0] as HTMLButtonElement;
    expect(save.disabled).toBe(true);
  });
});

const REGISTER = [
  {
    kind: 'ORDER',
    id: 7,
    reference: 'PEN-1',
    eis: '881305',
    employee: 'Tapan Bose',
    source: 'HRMS',
    nature: 'RECOVERY',
    reason: 'Order',
    amount: 24000,
    instalments: 6,
    perPeriod: 4000,
    approvedBy: 'ddo',
    status: 'ACTIVE',
    belowFloor: false,
  },
  {
    kind: 'REQUEST',
    id: 9,
    reference: 'PEN-2',
    eis: '881305',
    employee: 'Tapan Bose',
    source: 'ENT',
    nature: null,
    reason: 'Below floor',
    amount: 118600,
    instalments: null,
    perPeriod: null,
    approvedBy: null,
    status: 'PENDING_APPROVAL',
    belowFloor: true,
  },
];

function i06Routes() {
  return backend({
    'GET /v1/penalties/counts': () => ({
      orders: 3,
      outstanding: 16000,
      payments: 2,
      paymentsAwaiting: 1,
      paymentsAwaitingAmount: 40000,
      minNetGuard: 1,
      incentiveRecovery: 0,
      carriedToFnf: 1,
    }),
    'GET /v1/penalties/register': () => paged(REGISTER),
    'GET /v1/penalties/min-net': () => ({
      approvalId: 9,
      reference: 'PEN-2',
      eis: '881305',
      employee: 'Tapan Bose',
      gross: 64936,
      existingDeductions: 52936,
      proposedInstalment: 19767,
      resultingNet: -7767,
      floor: null,
    }),
    'GET /v1/penalties/over-and-above': () =>
      paged([
        {
          id: 4,
          eis: '881302',
          employee: 'Vani Menon',
          payCode: 101,
          payName: 'Honorarium',
          payrollYymm: '202610',
          amount: 12000,
          taxable: true,
          applicationDate: null,
          sanctionReference: 'SAN/1',
          sanctionDate: null,
          status: 'APPROVED',
          invoiceStatus: 'SENT_TO_APPS',
          invoiceSentAt: '2026-10-30T05:00:00Z',
          invoiceNo: 640,
          appsCancelStatus: 'NOT_REQUESTED',
          checkerReason: null,
        },
      ]),
    'GET /v1/penalties/7/orders': () => [],
    'GET /v1/penalties/7': () => ({
      order: {
        id: 7,
        penaltyRef: 'PEN-1',
        eis: '881305',
        employee: 'Tapan Bose',
        nature: 'INCREMENT_STOP',
        orderReference: 'VIG/44',
        source: 'HRMS',
        amount: null,
        instalments: null,
        periodFrom: null,
        periodTo: null,
        recovered: 0,
        outstanding: 0,
        status: 'ACTIVE',
        backdated: false,
      },
      schedule: [],
      revokes: [],
    }),
    'GET /v1/penalties?': () =>
      paged([
        {
          id: 7,
          penaltyRef: 'PEN-1',
          eis: '881305',
          employee: 'Tapan Bose',
          nature: 'INCREMENT_STOP',
          orderReference: 'VIG/44',
          source: 'HRMS',
          amount: null,
          instalments: null,
          periodFrom: null,
          periodTo: null,
          recovered: 0,
          waived: 0,
          outstanding: 0,
          status: 'REVOKED',
          backdated: false,
        },
      ]),
    'GET /v1/document-types': () => [],
  });
}

describe('I-06 as drawn', () => {
  it('SRS_14_1_theMakerHoldsTheFourActionsOffForOthersWithTheDrawnReason', async () => {
    i06Routes();
    wrap(<PenaltyScreen />, me(['I-06:VIEW'], { roles: ['ESTABLISHMENT_HR'] }));
    expect(await screen.findByText(t('i06w.penalties'))).toBeTruthy();
    expect(why(t('i06w.labour'))).toBe(t('i06w.why.labour'));
    expect(why(t('i06w.revoke'))).toBe(t('i06w.why.revoke'));
    expect(why(t('i06w.overAbove'))).toBe(t('i06w.why.maker'));
    expect(why(t('i06w.batch'))).toBe(t('i06w.why.checker'));
  });

  it('SRS_14_10_theRegisterTellsTheGuardAndTheCardItsFigures', async () => {
    i06Routes();
    wrap(<PenaltyScreen />, me(['I-06:VIEW', 'I-06:CREATE'], { roles: ['PAYROLL_MAKER'] }));
    expect(await screen.findByText(t('i06w.status.guard'))).toBeTruthy();
    expect(await screen.findByText(t('i06w.guard', ['PEN-2']))).toBeTruthy();
    expect(screen.getByText(t('i06w.g.setting'))).toBeTruthy();
  });

  it('R_SRC_anEmployeePenaltyIsReadOnlyHereWithTheDrawnReason', async () => {
    i06Routes();
    wrap(<PenaltyScreen />, me(['I-06:VIEW', 'I-06:CREATE'], { roles: ['PAYROLL_MAKER'] }));
    const edit = await screen.findByRole('button', { name: `${t('common.edit')} PEN-1` });
    expect(edit.getAttribute('data-why')).toBe(t('i06w.why.hrms'));
  });

  it('SRS_14_19_aNatureThatRecoversNothingShowsWhatItDoes', async () => {
    i06Routes();
    wrap(<PenaltyScreen />, me(['I-06:VIEW'], { roles: ['PAYROLL_CHECKER'] }));
    expect(await screen.findByText(t('i06w.imposed.stop'))).toBeTruthy();
  });

  it('SRS_G_37_aSentInvoiceHoldsTheCancelWithTheReason', async () => {
    i06Routes();
    wrap(<PenaltyScreen />, me(['I-06:VIEW', 'I-06:CANCEL'], { roles: ['PAYROLL_MAKER'] }));
    expect(await screen.findByText(t('i06w.invoices'))).toBeTruthy();
    expect(await screen.findByText('640')).toBeTruthy();
  });

  it('SRS_14_16_theMakerOpensTheOrderInTheClosedSection', async () => {
    i06Routes();
    wrap(<PenaltyScreen />, me(['I-06:VIEW', 'I-06:REVOKE'], { roles: ['PAYROLL_MAKER'] }));
    await userEvent.click(await screen.findByRole('button', { name: t('i06w.revoke') }));
    await waitFor(() => expect(screen.getByTestId('i06-register').hasAttribute('open')).toBe(true));
    // SRS-14-17: the order from HRMS opens with its Penalty Revoke form.
    expect(await screen.findByLabelText(t('i06.revokeTypeLabel'))).toBeTruthy();
  });

  it('SRS_14_17_anHrmsOrderIsWaivedHereAndStillNotCancelled', () => {
    expect(revocable('ACTIVE')).toBe(true);
    expect(revocable('PARTLY_REVOKED')).toBe(true);
    expect(revocable('PAUSED_FNF')).toBe(true);
    expect(revocable('COMPLETED')).toBe(false);
    expect(changeable('ACTIVE', 'HRMS')).toBe(false);
    expect(changeable('ACTIVE', 'ENT')).toBe(true);
  });
});
