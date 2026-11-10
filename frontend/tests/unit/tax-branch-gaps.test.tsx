import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  DeclarationReview,
  FlaggedTdsCases,
  Form16Register,
  MyTaxDeclarations,
  OutputDefinitions,
  PtStateMaster,
  SavingsDeclarations,
  SectionMaster,
  StatutoryReturns,
} from '@/modules/tax';
import { statutoryApi } from '@/modules/tax/api/statutoryApi';
import type { Certificate, StatutoryReturn } from '@/modules/tax/api/statutoryApi';
import type { StatutoryOutput } from '@/modules/tax/api/statutoryApi';
import type {
  DeclarationRound,
  FlaggedTdsCase,
  MemberSavings,
  MyDeclarations,
  SavingsEntry,
  SectionVersion,
} from '@/modules/tax/api/taxApi';
import { TaxWhatIf } from '@/modules/tax/components/TaxWhatIf';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * Module 08 tax screens beyond the main paths (SRS-7-9, SRS-7-14, SRS-7-16, SRS-7-20, SRS-7-21,
 * SRS-7-24, SRS-7-25, SRS-7-28, SRS-7-31; BR-TAX-015): a member without an employee number on the
 * record, a file choice cleared, a second line edited, a record changed while the form is open, an
 * OTP dialog cancelled, and a list that cannot be read.
 */

afterEach(() => vi.unstubAllGlobals());
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-02T06:00:00Z'));
});
afterEach(() => vi.useRealTimers());

/** A label as the browser names it (whitespace collapsed, trimmed). */
function n(text: string): string {
  return text.replace(/\s+/g, ' ').trim();
}

/** Chooses no file in a file input (the user opens the picker and cancels, or clears it). */
function clearFile(input: HTMLElement) {
  fireEvent.change(input, { target: { files: [] } });
}

const PDF = () => new File(['%PDF'], 'proof.pdf', { type: 'application/pdf' });

async function cancelOtp() {
  await screen.findByLabelText(t('otp.code'));
  await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }));
  await waitFor(() => expect(screen.queryByLabelText(t('otp.code'))).toBeNull());
}

// ---------------------------------------------------------------- C-10

const CERT: Certificate = {
  id: 11,
  employeeId: 7,
  eis: '870001',
  name: 'Asha Kulkarni',
  finYear: '2025-26',
  grossSalary: 1200000,
  taxableIncome: 1200000,
  taxDeducted: 96000,
  partAStatus: 'PENDING',
  status: 'PENDING_PART_A',
  partBGeneratedAt: '2026-05-01T05:00:00Z',
  releasedAt: null,
  partADocumentId: null,
};

const C10_MAKER = me(['C-10:VIEW', 'C-10:CREATE', 'C-10:EDIT', 'C-10:SUBMIT']);

describe('C-10 Form 16 register (branch gaps)', () => {
  it('SRS_7_9_aCertificateWithoutAnEmployeeNumberStillTakesPartAAndAReissue', async () => {
    backend({
      'GET /v1/tax/form16-certificates?': (url) =>
        paged(
          [
            { ...CERT, eis: null },
            {
              ...CERT,
              id: 12,
              eis: null,
              status: 'RELEASED',
              partAStatus: 'RECEIVED',
              partADocumentId: 5,
              releasedAt: '2026-07-01T05:00:00Z',
            },
          ],
          2,
          url,
        ),
      'GET /v1/document-types': () => [
        { code: 'CERTIFICATE', name: 'Certificate', referenceRequired: false },
      ],
    });
    wrap(<Form16Register />, C10_MAKER);
    await screen.findByRole('table', { name: t('screen.C-10') });
    expect(screen.getByLabelText(n(t('c10.reissueReasonOf', [''])))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: n(t('c10.reissueOf', [''])) })).toBeDisabled();

    await userEvent.click(screen.getByRole('button', { name: n(t('c10.partAOf', [''])) }));
    expect(screen.getByText(n(t('c10.partAFor', ['', '2025-26'])))).toBeInTheDocument();
    const upload = screen.getByRole('button', { name: t('c10.uploadPartA') });
    const file = screen.getByLabelText(t('c10.partAFile'));
    await userEvent.upload(file, PDF());
    await userEvent.type(screen.getByLabelText(t('i18.documentDate')), '2026-06-15');
    await userEvent.type(screen.getByLabelText(t('i18.documentIssuer')), 'Income Tax Department');
    expect(upload).toBeEnabled();
    // The file choice cleared: nothing to upload, the action waits again.
    clearFile(file);
    expect(upload).toBeDisabled();
    expect(upload).toHaveAttribute('title', t('c10.partAFirst'));
  });
});

// ---------------------------------------------------------------- C-11

const RETURN: StatutoryReturn = {
  id: 21,
  type: 'FORM_24Q',
  finYear: '2025-26',
  quarter: 'Q1',
  deductees: 2,
  totalTds: 39000,
  status: 'RELEASED',
  validated: true,
  validationUtility: 'FVU',
  validationResult: [],
  correction: false,
  correctsReturnId: null,
  generatedAt: '2026-07-10T05:00:00Z',
  releasedBy: 'tax.checker.delhi@fci-demo.test',
  filings: [],
};

describe('C-11 Form 24Q (branch gaps)', () => {
  it('SRS_7_21_SRS_7_31_aClearedChallanFileOrEvidenceIsNoFile', async () => {
    backend({
      'GET /v1/tax/returns?': (url) => paged([RETURN], 1, url),
      'GET /v1/tax/challans?': () => [],
      'GET /v1/tax/returns/21/deductees': (url) => paged([], 0, url),
    });
    wrap(<StatutoryReturns />, me(['C-11:VIEW', 'C-11:CREATE', 'C-11:EDIT', 'C-11:SUBMIT']));
    const challanFile = await screen.findByLabelText(t('c11.challanFile'));
    const importButton = screen.getByRole('button', { name: t('c11.import') });
    await userEvent.upload(challanFile, new File(['cin,bsr'], 'challans.csv', { type: 'text/csv' }));
    expect(importButton).toBeEnabled();
    clearFile(challanFile);
    expect(importButton).toBeDisabled();
    expect(importButton).toHaveAttribute('title', t('c11.needFile'));

    await userEvent.click(await screen.findByRole('button', { name: t('c11.openOf', [21]) }));
    const evidence = screen.getByLabelText(t('c11.evidence'));
    await userEvent.upload(evidence, PDF());
    expect(screen.getByLabelText(t('i18.documentIssuer'))).toBeInTheDocument();
    clearFile(evidence);
    expect(screen.queryByLabelText(t('i18.documentIssuer'))).toBeNull();
  });

  it('SRS_l2625_aKeyedChallanReferenceIsSentTrimmed', async () => {
    const calls = backend({ 'POST /v1/tax/challans': () => ({ id: 3 }) });
    await statutoryApi.enterChallan('2025-26', {
      cin: 'CIN3',
      bsr: '0510308',
      date: '2025-07-07',
      amount: '1200.50',
      reference: '  UTR-0007 ',
      period: 'Q2',
    });
    expect(sent(calls, 'POST', '/v1/tax/challans')[0]).toMatchObject({ reference: 'UTR-0007' });
  });
});

// ---------------------------------------------------------------- C-01

describe('C-01 TDS cases for review (branch gaps)', () => {
  it('US-08-07: a case without a round or approval item shows a dash', async () => {
    const row: FlaggedTdsCase = {
      employeeId: 9,
      eis: '105221',
      name: 'Kiran Rao',
      office: null,
      flag: 'PROOF_DEADLINE_REVERSAL',
      reference: null,
      reason: null,
      at: null,
    };
    backend({ 'GET /v1/tax/flagged-tds-cases': () => paged([row]) });
    wrap(<FlaggedTdsCases period="202610" />, me(['C-01:VIEW']));
    const member = await screen.findByText('105221');
    const tr = member.closest('tr') as HTMLElement;
    // The reference cell (third column) shows a dash, never "Round " without a number.
    expect(tr.querySelectorAll('td')[2]).toHaveTextContent(/^—$/);
  });
});

// ---------------------------------------------------------------- E-01 / I-07

const YEAR: MyDeclarations = {
  finYear: '2026-27',
  windowOpen: true,
  proofsUntil: '2027-01-31',
  regime: null,
  regimeFrozen: false,
  sections: [
    { code: '80C', description: 'Life insurance', limit: 150000, regime: 'OLD' },
    { code: '80D', description: 'Health insurance', limit: 25000, regime: 'BOTH' },
  ],
  rounds: [],
};

describe('E-01 income-tax declarations (branch gaps)', () => {
  it('SRS_7_16_aSecondLineIsEditedAloneAndAProofChoiceCancelledAddsNoProof', async () => {
    backend({
      'GET /v1/tax/declarations/mine': () => YEAR,
      'GET /v1/document-types': () => [
        { code: 'CERTIFICATE', name: 'Certificate', referenceRequired: false },
      ],
    });
    wrap(
      <MyTaxDeclarations />,
      me(['E-01:VIEW', 'E-01:CREATE'], { persona: 'employee', role: 'EMPLOYEE_ESEVA' }),
    );
    await userEvent.click(await screen.findByRole('button', { name: t('action.declare') }));
    await userEvent.click(screen.getByLabelText(t('e01t.regime.OLD')));
    await userEvent.click(screen.getByRole('button', { name: t('e01t.addLine') }));
    await userEvent.click(screen.getByRole('button', { name: t('e01t.addLine') }));
    await userEvent.selectOptions(screen.getByLabelText(t('e01t.sectionOf', [1])), '80C');
    await userEvent.selectOptions(screen.getByLabelText(t('e01t.sectionOf', [2])), '80D');
    await userEvent.type(screen.getByLabelText(t('e01t.declaredOf', [2])), '20000');
    expect(screen.getByLabelText(t('e01t.declaredOf', [1]))).toHaveValue('');
    expect(screen.getByLabelText(t('e01t.declaredOf', [2]))).toHaveValue('20000');
    await userEvent.upload(screen.getByLabelText(t('e01t.proofOf', [1])), PDF());
    await userEvent.type(screen.getByLabelText(t('e01t.proofIssuerOf', [1])), 'LIC Delhi');
    expect(screen.getByLabelText(t('e01t.proofIssuerOf', [1]))).toHaveValue('LIC Delhi');
    // The second line's proof picker cancelled: still no proof on it.
    clearFile(screen.getByLabelText(t('e01t.proofOf', [2])));
    expect(screen.getByLabelText(t('e01t.proofOf', [2]))).toBeInTheDocument();
    expect(screen.queryByLabelText(t('e01t.proofIssuerOf', [2]))).toBeNull();
  });
});

const ROUND: DeclarationRound = {
  id: 41,
  employeeId: 9,
  eis: '104882',
  name: 'R Sharma',
  finYear: '2026-27',
  number: 1,
  status: 'SUBMITTED',
  submittedAt: '2026-10-20T05:00:00Z',
  verifiedBy: null,
  returnReason: null,
  proofDeadlineReversed: false,
  regime: 'OLD',
  regimeFrozen: false,
  lines: [
    {
      id: 1,
      kind: 'INVESTMENT',
      sectionCode: '80C',
      declared: 160000,
      allowed: 150000,
      capped: true,
      proofStatus: 'PROOF_SUBMITTED',
      verified: null,
      frozen: false,
      proofId: 7,
    },
  ],
};

describe('I-07 declaration review (branch gaps)', () => {
  it('SRS_7_24_aLineThatArrivesWhileTheMakerTypesHasNoAmountAndBlocksTheVerification', async () => {
    backend({
      'GET /v1/tax/declarations?': (url) => paged([ROUND], 1, url),
      'GET /v1/tax/declarations/41': () => ROUND,
    });
    const { client } = wrap(<DeclarationReview />, me(['I-07:VIEW', 'I-07:SUBMIT', 'I-07:EDIT']));
    await userEvent.click(await screen.findByRole('button', { name: t('i07.openOf', ['104882', 1]) }));
    const amount = await screen.findByLabelText(t('i07.verifiedOf', ['80C']));
    await userEvent.clear(amount);
    await userEvent.type(amount, '140000');
    const second = {
      ...(ROUND.lines[0] as DeclarationRound['lines'][number]),
      id: 3,
      sectionCode: '80D',
      allowed: 25000,
    };
    client.setQueryData(['tax', 'declarations', 'one', 41], { ...ROUND, lines: [...ROUND.lines, second] });
    expect(await screen.findByLabelText(t('i07.verifiedOf', ['80D']))).toHaveValue('');
    expect(screen.getByLabelText(t('i07.verifiedOf', ['80C']))).toHaveValue('140000');
    expect(screen.getByRole('button', { name: t('action.verifyDeclaration') })).toBeDisabled();
  });
});

describe('I-07 declaration review (year and status filters)', () => {
  it('SRS_7_24_aChangedYearReadsThatYearAndClosesTheOpenRoundAndAStatusDoesTheSame', async () => {
    const calls = backend({
      'GET /v1/tax/declarations?': (url) => paged(url.includes('finYear=2026-27') ? [ROUND] : [], 0, url),
      'GET /v1/tax/declarations/41': () => ROUND,
    });
    wrap(<DeclarationReview />, me(['I-07:VIEW', 'I-07:SUBMIT', 'I-07:EDIT']));
    await userEvent.click(await screen.findByRole('button', { name: t('i07.openOf', ['104882', 1]) }));
    await screen.findByLabelText(t('i07.verifiedOf', ['80C']));
    // Another year: the open round is put away and the list is read for that year.
    const year = screen.getByLabelText(t('i07.finYear'));
    const earlier = Array.from((year as HTMLSelectElement).options).map((o) => o.value)[1] as string;
    await userEvent.selectOptions(year, earlier);
    await waitFor(() => expect(calls.some((c) => c.url.includes(`finYear=${earlier}`))).toBe(true));
    await waitFor(() => expect(screen.queryByLabelText(t('i07.verifiedOf', ['80C']))).toBeNull());
    expect(await screen.findByText(t('i07.none'))).toBeInTheDocument();
    // A status chosen filters the same list.
    await userEvent.selectOptions(screen.getByLabelText(t('i07.status')), 'SUBMITTED');
    await waitFor(() => expect(calls.some((c) => c.url.includes('status=SUBMITTED'))).toBe(true));
  });
});

// ---------------------------------------------------------------- I-18

const SAVED: SavingsEntry = {
  id: 5,
  employeeId: 9,
  eis: '870003',
  name: 'Lata Pillai',
  finYear: '2026-27',
  type: 'OTHER_INCOME',
  sectionCode: null,
  incomeHead: 'Honorarium',
  description: 'Guest lecture',
  amount: 12000,
  tdsOtherSource: 1200,
  status: 'MAKER_SAVED',
  documentId: 31,
  enteredBy: 'tax.maker.delhi@fci-demo.test',
  enteredAt: '2026-10-24T05:00:00Z',
};

const MEMBER_YEAR: MemberSavings = {
  employeeId: 9,
  eis: '870003',
  name: 'Lata Pillai',
  finYear: '2026-27',
  entries: [SAVED],
  savings: [
    {
      id: 2,
      type: 'TRANSPORT_REBATE',
      sectionCode: null,
      annualAmount: null,
      payrollYymm: null,
      monthlyAmount: 1600,
      componentId: null,
    },
  ],
  pendingRebates: [],
  sections: [{ code: '80C', description: 'Life insurance', limit: 150000, regime: 'OLD' }],
};

describe('I-18 savings declarations (branch gaps)', () => {
  it('SRS_7_25_anEntryWithoutAnEmployeeNumberOpensNothingAndACancelledDocumentStartsNoEntry', async () => {
    backend({
      'GET /v1/tax/savings?': (url) => paged([{ ...SAVED, id: 7, eis: null }, SAVED], 2, url),
      'GET /v1/tax/savings/members/870003': () => MEMBER_YEAR,
      'GET /v1/document-types': () => [
        { code: 'CERTIFICATE', name: 'Certificate', referenceRequired: false },
      ],
    });
    wrap(<SavingsDeclarations />, me(['I-18:VIEW', 'I-18:CREATE', 'I-18:EDIT', 'I-18:SUBMIT']));
    await userEvent.click(await screen.findByRole('button', { name: n(t('i18.openOf', [''])) }));
    expect(screen.queryByRole('table', { name: t('i18.entries') })).toBeNull();

    await userEvent.click(screen.getByRole('button', { name: t('i18.openOf', ['870003']) }));
    await screen.findByRole('table', { name: t('i18.entries') });
    // A rebate month the record holds without its payroll month blocks none of the months offered.
    expect(screen.getByLabelText('202605')).toBeEnabled();
    const document = screen.getByLabelText(t('i18.document'));
    clearFile(document);
    expect(screen.getByLabelText(t('i18.document'))).toBeInTheDocument();
    expect(screen.queryByLabelText(t('i18.type'))).toBeNull();
  });
});

// ---------------------------------------------------------------- M-18

const NO_LIMIT: SectionVersion = {
  id: 3,
  code: '80TTA',
  description: 'Savings interest',
  limit: null,
  regime: 'OLD',
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  inForce: true,
};

const RULE_ADMIN = me(['M-18:VIEW', 'M-18:CREATE', 'M-18:EDIT'], {
  role: 'RULE_ADMINISTRATOR',
  location: { code: 'HQ001', label: 'HQ' },
});

describe('M-18 sections and PT states (branch gaps)', () => {
  it('SRS_7_20_aNewVersionOfASectionWithoutALimitOpensWithoutOneAndACancelledOtpKeepsTheForm', async () => {
    const calls = backend({
      'GET /v1/tax/sections?': (url) => paged([NO_LIMIT], 1, url),
      'GET /v1/tax/sections/pending': () => [],
      'POST /v1/tax/sections': () => problem(428, 'AUTH-428-OTP', [7]),
    });
    wrap(<SectionMaster />, RULE_ADMIN);
    await userEvent.click(await screen.findByRole('button', { name: t('m18s.newVersionOf', ['80TTA']) }));
    expect(screen.getByLabelText(t('m18s.limit'))).toHaveValue('');
    await userEvent.type(screen.getByLabelText(t('mst.from')), '2027-04-01');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Finance Act 2027');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await cancelOtp();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByRole('button', { name: t('common.submitForApproval') })).toBeInTheDocument();
    expect(sent(calls, 'POST', '/v1/tax/sections')[0]).toMatchObject({ code: '80TTA', limit: null });
  });

  it('SRS_7_4_aCancelledOtpOnAStateKeepsTheFormWithoutAnError', async () => {
    backend({
      'GET /v1/tax/pt-states?': (url) => paged([], 0, url),
      'GET /v1/tax/pt-states/pending': () => [],
      'POST /v1/tax/pt-states': () => problem(428, 'AUTH-428-OTP', [8]),
    });
    wrap(<PtStateMaster />, RULE_ADMIN);
    await userEvent.click(await screen.findByRole('button', { name: t('action.newState') }));
    await userEvent.type(screen.getByLabelText(t('m18p.code')), 'KA');
    await userEvent.type(screen.getByLabelText(t('m18p.name')), 'Karnataka');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Notified');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await cancelOtp();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByLabelText(t('m18p.code'))).toHaveValue('KA');
  });
});

// ---------------------------------------------------------------- C-09 / M-17

describe('what-if forecast and M-17 (branch gaps)', () => {
  it('SRS_7_28_aFormSubmittedWithNothingVariedSendsNothing', async () => {
    const calls = backend({});
    wrap(<TaxWhatIf eis="870001" finYear="2025-26" />, me(['C-09:RUN']));
    const run = screen.getByRole('button', { name: t('c07w.run') });
    expect(run).toBeDisabled();
    fireEvent.submit(run.closest('form') as HTMLFormElement);
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
  });

  it('SRS_M17_resetDiscardsADraftVersionAndTheProposalWaitsAgain', async () => {
    const output: StatutoryOutput = {
      key: 'FORM16_PART_B',
      name: 'Form 16 Part B',
      producingScreen: 'C-10',
      statutoryReference: 'Rule 31',
      state: 'ACTIVE',
      pendingApprovals: [],
      versions: [],
    };
    const calls = backend({ 'GET /v1/tax/output-definitions': () => [output] });
    wrap(<OutputDefinitions />, me(['M-17:VIEW', 'M-17:CREATE']));
    await userEvent.click(await screen.findByRole('button', { name: t('m17.openOf', ['FORM16_PART_B']) }));
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    // Nothing keyed: the problems are listed and the proposal is disabled with its reason.
    expect(submit).toBeDisabled();
    expect(submit).toHaveAttribute('title', t('common.fixProblems'));
    const from = screen.getByLabelText(t('m17.effectiveFrom'));
    await userEvent.type(from, '2027-04-01');
    await userEvent.type(screen.getByLabelText(t('m17.labelOf', [1])), 'Basic');
    await userEvent.type(screen.getByLabelText(t('m17.rounding')), 'round.rupee');
    expect(from).toHaveValue('2027-04-01');
    await userEvent.click(screen.getByRole('button', { name: t('common.reset') }));
    expect(screen.getByLabelText(t('m17.effectiveFrom'))).toHaveValue('');
    expect(screen.getByLabelText(t('m17.labelOf', [1]))).toHaveValue('');
    expect(screen.getByLabelText(t('m17.rounding'))).toHaveValue('');
    expect(screen.getByRole('list', { name: t('common.problems') })).toHaveTextContent(t('m17.needFrom'));
    expect(submit).toBeDisabled();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0);
  });

  it('BR_MIS_002_aDefinitionListThatCannotBeReadIsShownAsTheReason', async () => {
    backend({ 'GET /v1/tax/output-definitions': () => problem(503, 'COM-503-UNAVAILABLE') });
    wrap(<OutputDefinitions />, me(['M-17:VIEW']));
    expect(await screen.findByText(t('error.COM-503-UNAVAILABLE'))).toBeInTheDocument();
    expect(screen.queryByRole('table', { name: t('screen.M-17') })).toBeNull();
  });
});
