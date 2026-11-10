import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CpfSanctions, CpfWithdrawals } from '@/modules/cpf';
import { DeputationAdjustment } from '@/modules/cpf/components/DeputationAdjustment';
import { InterestRates } from '@/modules/cpf/components/InterestRates';
import type {
  DeputationEntry,
  DeputedMember,
  InterestRate,
  Sanction,
  WithdrawalPurpose,
} from '@/modules/cpf/api/cpfApi';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * The CPF screens at their branch edges (SRS-6-4, SRS-6-14, SRS-6-28, SRS-6-11, SRS-6-39, SRS-12-9,
 * SRS-G-31, SRS-G-37, SRS-G-48, SRS l.2279): a withdrawn request without its reason, a member with no
 * earlier withdrawal or one without purpose, date or number, a certificate file cleared, an advance
 * or instalment without a sanction number, evidence types that failed to load, a refused or OTP-
 * cancelled withdrawal, a declaration or purpose whose OTP is cancelled.
 */

afterEach(() => vi.unstubAllGlobals());

const sanction = (over: Partial<Sanction> = {}): Sanction => ({
  id: 41,
  eis: '104882',
  memberName: 'Rakesh Sharma',
  type: 'ADVANCE',
  purposeCode: 'MARRIAGE',
  purposeName: 'Marriage',
  requested: 50000,
  ceiling: null,
  sanctioned: null,
  capped: false,
  stage: 'UNIT_VERIFICATION',
  sanctionNumber: null,
  sanctionDate: null,
  propertyAddress: null,
  propertyDescription: null,
  approvalId: null,
  cancelReason: null,
  tiers: [],
  uan: '100000104882',
  aadhaarMasked: 'XXXX-XXXX-4886',
  noCasteDisputeRequired: false,
  documents: [],
  ...over,
});

const UNIT = me(['I-04:VIEW', 'I-04:EDIT'], {
  role: 'UNIT_CPF_ADMIN',
  location: { code: 'DH001', label: 'DO — Delhi HQ (DH001)' },
});

describe('I-04 CPF sanctions: branch edges', () => {
  it('SRS_G_37_aWithdrawnRequestWithoutItsReasonShowsNoneAndAMemberWithoutEarlierWithdrawalsSaysSo', async () => {
    const withdrawn = sanction({ id: 44, stage: 'CANCELLED', cancelReason: null, earlierWithdrawals: [] });
    backend({
      'GET /v1/cpf/sanctions/44': () => withdrawn,
      'GET /v1/cpf/sanctions?': (url) => paged([withdrawn], undefined, url),
    });
    wrap(<CpfSanctions />, UNIT);
    await userEvent.click(await screen.findByRole('button', { name: t('i04.openOf', [44]) }));
    expect(await screen.findByText(t('i04.noEarlier'))).toBeTruthy();
    const reasonKey = screen.getByText(t('i04.cancelReason'));
    expect(reasonKey.nextElementSibling?.textContent).toBe('—');
  });

  it('SRS_6_5_anEarlierWithdrawalWithoutPurposeDateOrNumberShowsOnlyItsKindAndStage', async () => {
    const counted = sanction({
      earlierWithdrawals: [
        {
          id: 30,
          type: 'PART_FINAL',
          purposeCode: null,
          stage: 'UNIT_VERIFICATION',
          sanctionNumber: null,
          sanctionDate: null,
        },
      ],
    });
    backend({
      'GET /v1/cpf/sanctions/41': () => counted,
      'GET /v1/cpf/sanctions?': (url) => paged([counted], undefined, url),
    });
    wrap(<CpfSanctions />, UNIT);
    await userEvent.click(await screen.findByRole('button', { name: t('i04.openOf', [41]) }));
    const earlier = await screen.findByRole('list', { name: t('i04.withdrawalCount') });
    expect(within(earlier).getByRole('listitem').textContent).toBe(
      `${t('i04.kind.PART_FINAL')} · ${t('i04.stage.UNIT_VERIFICATION')}`,
    );
  });

  it('SRS_6_14_aCertificateFileClearedDisablesTheAttachAgain', async () => {
    const waiting = sanction({ noCasteDisputeRequired: true });
    backend({
      'GET /v1/cpf/sanctions/41': () => waiting,
      'GET /v1/cpf/sanctions?': (url) => paged([waiting], undefined, url),
    });
    wrap(<CpfSanctions />, UNIT);
    await userEvent.click(await screen.findByRole('button', { name: t('i04.openOf', [41]) }));
    const form = await screen.findByRole('group', { name: t('i04.ncd.title') });
    const attach = within(form).getByRole('button', { name: t('i04.ncd.attach') });
    await userEvent.type(within(form).getByLabelText(t('i04.ncd.date')), '2026-09-01');
    await userEvent.type(within(form).getByLabelText(t('i04.ncd.authority')), 'Tehsildar');
    const file = within(form).getByLabelText(t('i04.ncd.file'));
    await userEvent.upload(file, new File(['%PDF-1.7'], 'ncd.pdf', { type: 'application/pdf' }));
    expect(attach).toBeEnabled();
    fireEvent.change(file, { target: { files: [] } });
    expect(attach).toBeDisabled();
  });
});

// ---------------------------------------------------------------- I-09

const ENTRY: DeputationEntry = {
  id: 3,
  eis: '871901',
  organisation: 'Demo Public Sector Undertaking One',
  type: 'RECOVERY_INSTALMENT',
  sanctionId: 5,
  payrollYymm: '202608',
  instalment: 5000,
  employee: 0,
  employer: 0,
  vpf: 0,
  outstandingBefore: 60000,
  balanceAfter: 55000,
  status: 'APPROVED',
  approvalId: 81,
  evidenceId: 12,
  evidence: [
    {
      id: 12,
      fileName: 'remittance.pdf',
      uploadedBy: 'cpf.manager.north@fci-demo.test',
      uploadedAt: '2026-10-01T05:00:00Z',
    },
  ],
};

const MEMBER: DeputedMember = {
  eis: '871901',
  name: 'Kiran Bose',
  deputationFrom: '2026-07-01',
  deputationTo: null,
  advances: [{ sanctionId: 6, sanctionNumber: null, sanctioned: 30000, outstanding: 30000 }],
  monthsWithoutReceipt: ['202607'],
  entries: [ENTRY],
};

const MANAGER = me(['I-09:VIEW', 'I-09:CREATE'], { role: 'CPF_MANAGER' });

function deputation(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
  return backend({
    'GET /v1/cpf/deputation-entries': (url) => paged([ENTRY], 1, url),
    'GET /v1/cpf/deputations/871901': () => MEMBER,
    'GET /v1/cpf/borrowing-organisations': () => [
      { id: 1, code: 'DEMO-PSU-1', name: 'Demo PSU One', contact: null, active: true },
    ],
    'GET /v1/document-types': () => problem(503, 'COM-503-UNAVAILABLE'),
    ...over,
  });
}

async function openMember() {
  await userEvent.type(screen.getByLabelText(t('i09.eis')), '871901');
  await userEvent.click(screen.getByRole('button', { name: t('i09.find') }));
  await screen.findByText('Kiran Bose (871901)');
}

describe('I-09 deputation adjustment: branch edges', () => {
  it('SRS_6_28_anAdvanceWithoutASanctionNumberIsOfferedByItsIdForAnInstalment', async () => {
    deputation();
    wrap(<DeputationAdjustment />, MANAGER);
    await openMember();
    await userEvent.selectOptions(screen.getByLabelText(t('i09.type')), 'RECOVERY_INSTALMENT');
    const advance = screen.getByLabelText(t('i09.advance'));
    expect(within(advance).getByRole('option', { name: /^6 · / })).toBeTruthy();
  });

  it('SRS_6_28_anInstalmentShowsItsAdvanceByNumberOrIdOrNoneInTheRegister', async () => {
    deputation({
      'GET /v1/cpf/deputation-entries': (url) =>
        paged(
          [
            { ...ENTRY, id: 3, sanctionId: 5, sanctionNumber: null },
            { ...ENTRY, id: 4, sanctionId: null, sanctionNumber: null, evidence: [] },
          ],
          2,
          url,
        ),
    });
    wrap(<DeputationAdjustment />, MANAGER);
    const register = await screen.findByRole('table', { name: t('i09.entries') });
    const byId = register.querySelector('tr[data-reference="dep-3"]') as HTMLElement;
    const none = register.querySelector('tr[data-reference="dep-4"]') as HTMLElement;
    expect(within(byId).getAllByRole('cell')[4]?.textContent).toBe('5');
    expect(within(none).getAllByRole('cell')[4]?.textContent).toBe('—');
  });

  it('SRS_G_31_theEvidenceOpensAndClosesFromItsButtonAndWithoutTypesNothingCanBeAdded', async () => {
    deputation();
    wrap(<DeputationAdjustment />, MANAGER);
    await openMember();
    const registers = await screen.findAllByRole('table', { name: t('i09.entries') });
    const toggle = within(registers[0] as HTMLElement).getByRole('button', {
      name: t('i09.evidenceCount', [1]),
    });
    await userEvent.click(toggle);
    expect(screen.getByRole('table', { name: t('i09.evidence') })).toBeTruthy();
    // The document types did not load: the order type offers nothing, so nothing is added.
    const type = document.getElementById('i09-ev-doctype') as HTMLSelectElement;
    expect(Array.from(type.options).filter((o) => o.value !== '')).toHaveLength(0);
    await userEvent.click(toggle);
    expect(screen.queryByRole('table', { name: t('i09.evidence') })).toBeNull();
  });
});

describe('I-09 deputation evidence: refused calls', () => {
  it('SRS_G_31_aRefusedAdditionAndARefusedCancellationAreShownAndKeepTheTypedParticulars', async () => {
    const two = [
      ...ENTRY.evidence,
      {
        id: 13,
        fileName: 'wrong.pdf',
        uploadedBy: 'cpf.manager.north@fci-demo.test',
        uploadedAt: '2026-10-02T05:00:00Z',
      },
    ];
    const calls = deputation({
      'GET /v1/cpf/deputations/871901': () => ({ ...MEMBER, entries: [{ ...ENTRY, evidence: two }] }),
      'GET /v1/document-types': () => [{ code: 'OTHER', name: 'Other document', referenceRequired: false }],
      'POST /v1/cpf/deputation-entries/3/evidence/13/cancel': () => problem(409, 'CPF-409-STAGE', ['PAID']),
      'POST /v1/cpf/deputation-entries/3/evidence': () =>
        problem(403, 'AUTHZ-403-PERMISSION', ['I-09', 'CREATE']),
    });
    wrap(<DeputationAdjustment />, MANAGER);
    await openMember();
    const registers = await screen.findAllByRole('table', { name: t('i09.entries') });
    await userEvent.click(
      within(registers[0] as HTMLElement).getByRole('button', { name: t('i09.evidenceCount', [2]) }),
    );
    // A cancellation the server refuses (409): its message shows; the reason stays typed.
    await userEvent.type(screen.getByLabelText(t('i09.evidenceReason')), 'Wrong advice');
    await userEvent.click(screen.getByRole('button', { name: t('i09.evidenceCancel', ['wrong.pdf']) }));
    await userEvent.click(await screen.findByRole('button', { name: t('confirm.cancel.yes') }));
    expect(await screen.findByText(t('error.CPF-409-STAGE', ['PAID']))).toBeInTheDocument();
    expect(screen.getByLabelText(t('i09.evidenceReason'))).toHaveValue('Wrong advice');
    expect(screen.queryByText(t('i09.evidenceCancelled'))).toBeNull();
    // An addition the server refuses (403): its message shows; the particulars stay.
    const panel = screen.getByRole('table', { name: t('i09.evidence') }).closest('.card') as HTMLElement;
    await within(panel).findByRole('option', { name: 'Other document' });
    await userEvent.selectOptions(within(panel).getByLabelText(t('ord.type')), 'OTHER');
    await userEvent.type(within(panel).getByLabelText(t('ord.date')), '2026-10-01');
    await userEvent.type(within(panel).getByLabelText(t('ord.authority')), 'Demo PSU accounts');
    await userEvent.upload(
      within(panel).getByLabelText(t('ord.file')),
      new File(['%PDF-1.7'], 'right.pdf', { type: 'application/pdf' }),
    );
    await userEvent.click(within(panel).getByRole('button', { name: t('i09.evidenceAdd') }));
    expect(await screen.findByText(t('error.AUTHZ-403-PERMISSION', ['I-09', 'CREATE']))).toBeInTheDocument();
    expect(within(panel).getByLabelText(t('ord.authority'))).toHaveValue('Demo PSU accounts');
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(2);
  });
});

// ---------------------------------------------------------------- E-01

const MEMBER_SESSION = me(['E-01:VIEW', 'E-01:CREATE'], { role: 'EMPLOYEE_ESEVA' });

function withdrawals(answer: () => unknown, mine: () => unknown = () => [sanction()]) {
  return backend({
    'GET /v1/cpf/sanctions/purposes': () => [],
    'GET /v1/cpf/sanctions/mine': mine,
    'GET /v1/document-types': () => [],
    'POST /v1/cpf/sanctions/41/withdraw': answer,
  });
}

async function withdraw(reason: string) {
  const row = (await screen.findByRole('table', { name: t('e01s.mine') })).querySelector(
    'tr[data-reference="41"]',
  ) as HTMLElement;
  await userEvent.click(within(row).getByRole('button', { name: t('e01s.withdraw') }));
  const dialog = screen.getByRole('dialog');
  await userEvent.type(within(dialog).getByRole('textbox'), reason);
  await userEvent.click(within(dialog).getByRole('button', { name: t('e01s.withdraw') }));
}

describe('E-01 CPF withdrawals: branch edges', () => {
  it('SRS_12_9_aRefusedWithdrawalIsShown', async () => {
    withdrawals(() => problem(409, 'CPF-409-STAGE', ['SANCTIONED']));
    wrap(<CpfWithdrawals />, MEMBER_SESSION);
    await withdraw('No longer needed');
    expect(await screen.findByText(t('error.CPF-409-STAGE', ['SANCTIONED']))).toBeTruthy();
  });

  it('SRS_12_9_aReturnedPropertyRequestCorrectsItsDescriptionAndAZeroAmountBlocksTheResubmission', async () => {
    const returned = sanction({
      id: 41,
      stage: 'RETURNED',
      requested: 80000,
      propertyAddress: 'Plot 4A, Sector 9',
      propertyDescription: 'Old description',
    });
    const calls = withdrawals(
      () => ({}),
      () => [returned],
    );
    wrap(<CpfWithdrawals />, MEMBER_SESSION);
    const row = (await screen.findByRole('table', { name: t('e01s.mine') })).querySelector(
      'tr[data-reference="41"]',
    ) as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: t('e01s.resubmit') }));
    const description = screen.getByLabelText(t('e01s.description'));
    expect(description).toHaveValue('Old description');
    await userEvent.clear(description);
    await userEvent.type(description, 'Two-storey house');
    expect(description).toHaveValue('Two-storey house');
    // An amount that is not a positive number is named and blocks the resubmission.
    const amount = screen.getByLabelText(t('i04.requested'));
    await userEvent.clear(amount);
    await userEvent.type(amount, '0');
    expect(screen.getByText(t('e01s.problem.amount'))).toBeTruthy();
    expect(screen.getAllByRole('button', { name: t('e01s.resubmit') }).at(-1)).toBeDisabled();
    await userEvent.clear(amount);
    await userEvent.type(amount, '85000');
    expect(screen.queryByText(t('e01s.problem.amount'))).toBeNull();
    await userEvent.click(screen.getAllByRole('button', { name: t('e01s.resubmit') }).at(-1) as HTMLElement);
    expect(await screen.findByText(t('e01s.resubmitted', [41]))).toBeTruthy();
    expect(sent(calls, 'POST', '/resubmit')[0]).toMatchObject({
      amount: '85000',
      propertyAddress: 'Plot 4A, Sector 9',
      propertyDescription: 'Two-storey house',
    });
  });

  it('SRS_6_14_noRequestsReadBackAsNoneToShow', async () => {
    withdrawals(
      () => ({}),
      () => ({ status: 200, body: null }),
    );
    wrap(<CpfWithdrawals />, MEMBER_SESSION);
    expect(await screen.findByText(t('e01s.none'))).toBeTruthy();
    expect(screen.queryByRole('table', { name: t('e01s.mine') })).toBeNull();
  });
});

// ---------------------------------------------------------------- M-19

const UNDECLARED: InterestRate = {
  id: 12,
  finYear: '2026-27',
  fundType: 'CPF',
  rateSource: 'RPFC',
  memberPopulation: 'INACTIVE',
  ratePercent: 8,
  appliesFrom: '2026-04-01',
  appliesTo: null,
  applicabilityNote: null,
  bonusInterestPct: null,
  declared: false,
  status: 'ACTIVE',
  approvalId: 4,
};

const MARRIAGE: WithdrawalPurpose = {
  id: 1,
  code: 'MARRIAGE',
  name: 'Marriage',
  category: 'ADVANCE',
  limitRuleKey: 'CPF.LIMIT.MGW.MARRIAGE',
  maxOccurrences: 2,
  noCasteDispute: false,
  propertyDetails: false,
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  confirmed: false,
  inForce: true,
  documents: [{ documentType: 'CERTIFICATE', mandatory: true, tier: 'UNIT_CPF_ADMIN' }],
};

const RULE_ADMIN = me(['M-19:VIEW', 'M-19:CREATE', 'M-19:EDIT'], {
  role: 'RULE_ADMINISTRATOR',
  location: { code: 'HQ001', label: 'HQ' },
});

function rates(over: Record<string, () => unknown> = {}) {
  return backend({
    'GET /v1/cpf/interest-rates': () => ({ rates: [UNDECLARED], pending: [] }),
    'GET /v1/cpf/withdrawal-purposes': () => ({ purposes: [MARRIAGE], pending: [] }),
    'GET /v1/rules/pending': () => paged([]),
    'GET /v1/document-types': () => [{ code: 'CERTIFICATE', name: 'Certificate', referenceRequired: false }],
    ...over,
  });
}

async function cancelOtp() {
  const otp = await screen.findByRole('dialog', { name: t('otp.title') });
  await userEvent.click(within(otp).getByRole('button', { name: t('common.cancel') }));
  await waitFor(() => expect(screen.queryByRole('dialog', { name: t('otp.title') })).toBeNull());
}

describe('M-19 rates and purposes: branch edges', () => {
  it('SRS_G_48_aDeclarationWhoseOtpIsCancelledKeepsTheFormWithoutAnError', async () => {
    rates({ 'POST /v1/cpf/interest-rates/12/declare': () => problem(428, 'AUTH-428-OTP', ['7']) });
    wrap(<InterestRates />, RULE_ADMIN);
    await userEvent.click(
      await screen.findByRole('button', { name: t('m19.declareOf', ['2026-27|CPF|RPFC']) }),
    );
    const rate = screen.getByLabelText(t('m19.declaredRate'));
    await userEvent.clear(rate);
    await userEvent.type(rate, '8.65');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'EPFO notified');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await cancelOtp();
    expect(screen.getByRole('heading', { name: t('m19.declareOf', ['2026-27|CPF|RPFC']) })).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('SRS_G_48_aPurposeVersionWhoseOtpIsCancelledKeepsTheFormWithoutAnError', async () => {
    rates({ 'POST /v1/cpf/withdrawal-purposes': () => problem(428, 'AUTH-428-OTP', ['7']) });
    wrap(<InterestRates />, RULE_ADMIN);
    await screen.findByText('MARRIAGE');
    await userEvent.click(screen.getByRole('button', { name: t('m19p.newVersionOf', ['MARRIAGE']) }));
    await userEvent.type(screen.getByLabelText(t('mst.from')), '2027-04-01');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Checklist supplied by FCI');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await cancelOtp();
    expect(screen.getByLabelText(t('mst.from'))).toHaveValue('2027-04-01');
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
