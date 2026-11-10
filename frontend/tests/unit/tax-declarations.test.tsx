import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DeclarationReview, MyTaxDeclarations } from '@/modules/tax';
import type { DeclarationRound, MyDeclarations } from '@/modules/tax/api/taxApi';
import { finYearOf } from '@/modules/tax/components/DeclarationReview';
import { date } from '@/shared/ui';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * E-01 and I-07 income-tax declarations (SRS-7-1, SRS-7-15, SRS-7-16, SRS-7-24; BR-TAX-001,
 * BR-TAX-002, BR-TAX-014): the member's form with the cap shown, the window and the frozen states,
 * and the Payroll Maker's verification, return and regime reset.
 */

afterEach(() => vi.unstubAllGlobals());
// The year selector offers the business year and the four before it (shared FinancialYearSelect):
// the fixtures' years (2024-25, 2025-26) are in that window on this pinned date in India.
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-02T06:00:00Z'));
});
afterEach(() => vi.useRealTimers());

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
    {
      id: 2,
      kind: 'HRA_RENT',
      sectionCode: '10(13A)',
      declared: 120000,
      allowed: 120000,
      capped: false,
      proofStatus: 'VERIFIED',
      verified: 120000,
      frozen: true,
      proofId: null,
    },
  ],
};

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

const MEMBER = me(['E-01:VIEW', 'E-01:CREATE'], { persona: 'employee', role: 'EMPLOYEE_ESEVA' });
const MAKER = me(['I-07:VIEW', 'I-07:SUBMIT', 'I-07:EDIT']);

function memberRoutes(
  year: MyDeclarations,
  over: Record<string, (url: string, init?: RequestInit) => unknown> = {},
) {
  return backend({
    'GET /v1/tax/declarations/mine': () => year,
    'GET /v1/document-types': () => [
      { code: 'CERTIFICATE', name: 'Certificate', referenceRequired: false },
      { code: 'OTHER', name: 'Other', referenceRequired: false },
    ],
    ...over,
  });
}

describe('E-01 income-tax declarations', () => {
  it('SRS_7_16_aLineAboveItsLimitIsCappedAndShownAndTheRoundIsSent', async () => {
    const calls = memberRoutes(YEAR, {
      'POST /v1/tax/declarations/mine': () => ({ status: 201, body: ROUND }),
    });
    wrap(<MyTaxDeclarations />, MEMBER);
    expect(await screen.findByText(t('e01t.windowOpen'))).toBeInTheDocument();
    expect(screen.getByText(t('e01t.proofsUntil', [date('2027-01-31')]))).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: t('action.declare') }));
    const problems = () => screen.getByRole('list', { name: t('m18s.problems') });
    expect(within(problems()).getByText(t('error.TAX-422-REGIME-REQUIRED'))).toBeInTheDocument();
    await userEvent.click(screen.getByLabelText(t('e01t.regime.OLD')));
    await userEvent.click(screen.getByRole('button', { name: t('e01t.addLine') }));
    await userEvent.selectOptions(screen.getByLabelText(t('e01t.sectionOf', [1])), '80C');
    await userEvent.type(screen.getByLabelText(t('e01t.declaredOf', [1])), '160000');
    expect(screen.getByText(t('e01t.cappedAt', ['₹1,50,000.00']))).toBeInTheDocument();
    await userEvent.upload(
      screen.getByLabelText(t('e01t.proofOf', [1])),
      new File(['%PDF'], 'lic.pdf', { type: 'application/pdf' }),
    );
    expect(within(problems()).getByText(t('e01t.problem.proof'))).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText(t('e01t.proofDateOf', [1])), '2026-10-01');
    await userEvent.type(screen.getByLabelText(t('e01t.proofIssuerOf', [1])), 'LIC Delhi');
    await userEvent.click(screen.getByRole('button', { name: t('common.submit') }));
    expect(await screen.findByText(t('e01t.submitted', [1]))).toBeInTheDocument();
    const form = sent(calls, 'POST', '/v1/tax/declarations/mine')[0] as FormData;
    const request = JSON.parse(await (form.get('request') as Blob).text());
    expect(request).toMatchObject({
      regime: 'OLD',
      lines: [
        {
          kind: 'INVESTMENT',
          sectionCode: '80C',
          declared: '160000',
          proof: { documentType: 'CERTIFICATE', issuingAuthority: 'LIC Delhi' },
        },
      ],
    });
    expect(form.getAll('file')).toHaveLength(1);
  });

  it('BR_TAX_001_aFrozenRegimeAndRentStayAndTheRoundsShowCapsAndReturns', async () => {
    const frozen: MyDeclarations = {
      ...YEAR,
      regime: 'OLD',
      regimeFrozen: true,
      rounds: [
        {
          ...ROUND,
          id: 42,
          number: 2,
          status: 'RETURNED',
          returnReason: 'Proof unreadable',
          regimeFrozen: true,
        },
        { ...ROUND, status: 'APPROVED', regimeFrozen: true, proofDeadlineReversed: true },
      ],
    };
    const calls = memberRoutes(frozen, {
      'POST /v1/tax/declarations/mine': () => problem(409, 'TAX-409-RENT-FROZEN', ['2026-27']),
    });
    wrap(<MyTaxDeclarations />, MEMBER);
    const rounds = await screen.findByRole('list', { name: t('e01t.rounds') });
    expect(rounds).toHaveTextContent(t('e01t.returnedBecause', ['Proof unreadable']));
    expect(rounds).toHaveTextContent(t('e01t.reversed'));
    expect(rounds).toHaveTextContent(t('e01t.cappedAt', ['₹1,50,000.00']));
    expect(within(rounds).getAllByRole('link', { name: t('e01t.proof') })[0]).toHaveAttribute(
      'href',
      expect.stringContaining('/v1/tax/declarations/42/proofs/7'),
    );
    await userEvent.click(screen.getByRole('button', { name: t('action.declare') }));
    expect(screen.getByText(t('e01t.rentFrozen'))).toBeInTheDocument();
    expect(screen.getByLabelText(t('e01t.regime.OLD'))).toBeDisabled();
    expect(screen.getByLabelText(t('e01t.regime.OLD'))).toBeChecked();
    // The rent kind is not offered while the rent is frozen.
    await userEvent.click(screen.getByRole('button', { name: t('e01t.addLine') }));
    expect(
      within(screen.getByLabelText(t('e01t.kindOf', [1]))).queryByRole('option', {
        name: t('e01t.kind.HRA_RENT'),
      }),
    ).toBeNull();
    await userEvent.selectOptions(screen.getByLabelText(t('e01t.sectionOf', [1])), '80D');
    await userEvent.type(screen.getByLabelText(t('e01t.declaredOf', [1])), '100');
    await userEvent.click(screen.getByRole('button', { name: t('common.submit') }));
    expect(await screen.findByText(t('error.TAX-409-RENT-FROZEN', ['2026-27']))).toBeInTheDocument();
    // A frozen regime is not sent again: the server keeps the approved one.
    const form = sent(calls, 'POST', '/v1/tax/declarations/mine')[0] as FormData;
    expect(JSON.parse(await (form.get('request') as Blob).text()).regime).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('m19p.removeOf', [1]) }));
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('button', { name: t('common.submit') })).toBeNull();
  });

  it('SRS_7_24_aClosedWindowOrARoundAtTheCheckerDisablesTheDeclarationWithItsReason', async () => {
    memberRoutes({ ...YEAR, windowOpen: false, proofsUntil: null });
    wrap(<MyTaxDeclarations />, MEMBER);
    const closed = await screen.findByRole('button', { name: t('action.declare') });
    expect(closed).toBeDisabled();
    expect(closed).toHaveAttribute('title', t('e01t.windowClosed'));
    expect(screen.getByText(t('e01t.none'))).toBeInTheDocument();
    vi.unstubAllGlobals();
  });

  it('SRS_7_24_aRoundAtTheCheckerBlocksTheNextAndAReaderSeesNoAction', async () => {
    memberRoutes({ ...YEAR, rounds: [{ ...ROUND, status: 'VERIFIED' }] });
    wrap(<MyTaxDeclarations />, MEMBER);
    const pending = await screen.findByRole('button', { name: t('action.declare') });
    expect(pending).toHaveAttribute('title', t('error.TAX-409-DECLARATION-PENDING'));
  });

  it('SRS_17_2_aMemberWithoutCreateOnlyReads', async () => {
    memberRoutes(YEAR);
    wrap(<MyTaxDeclarations />, me(['E-01:VIEW'], { persona: 'employee' }));
    expect(await screen.findByText(t('e01t.windowOpen'))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('action.declare') })).toBeNull();
  });

  it('SRS_7_20_aSectionWithoutALimitAllowsWhatIsDeclaredAndEveryProofFieldIsKept', async () => {
    const calls = memberRoutes(
      {
        ...YEAR,
        sections: [
          ...YEAR.sections,
          { code: '80CCD(2)', description: 'Employer NPS', limit: null, regime: 'BOTH' },
        ],
      },
      { 'POST /v1/tax/declarations/mine': () => ({ status: 201, body: ROUND }) },
    );
    wrap(<MyTaxDeclarations />, MEMBER);
    await userEvent.click(await screen.findByRole('button', { name: t('action.declare') }));
    await userEvent.click(screen.getByLabelText(t('e01t.regime.NEW')));
    await userEvent.click(screen.getByRole('button', { name: t('e01t.addLine') }));
    await userEvent.selectOptions(screen.getByLabelText(t('e01t.kindOf', [1])), 'HRA_RENT');
    await userEvent.selectOptions(screen.getByLabelText(t('e01t.sectionOf', [1])), '80CCD(2)');
    expect(screen.getByText(t('m18s.noLimit'))).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText(t('e01t.declaredOf', [1])), '999999');
    expect(screen.getByText('₹9,99,999.00')).toBeInTheDocument();
    expect(screen.queryByText(/Capped at/)).toBeNull();
    // A cancelled file choice leaves the line without a proof.
    await userEvent.upload(screen.getByLabelText(t('e01t.proofOf', [1])), []);
    expect(screen.getByLabelText(t('e01t.proofOf', [1]))).toBeInTheDocument();
    await userEvent.upload(
      screen.getByLabelText(t('e01t.proofOf', [1])),
      new File(['%PDF'], 'rent.pdf', { type: 'application/pdf' }),
    );
    await screen.findByRole('option', { name: 'Other' });
    await userEvent.selectOptions(screen.getByLabelText(t('e01t.proofTypeOf', [1])), 'OTHER');
    await userEvent.type(screen.getByLabelText(t('e01t.proofDateOf', [1])), '2026-09-30');
    await userEvent.type(screen.getByLabelText(t('e01t.proofIssuerOf', [1])), 'Landlord');
    await userEvent.type(screen.getByLabelText(t('e01t.proofReferenceOf', [1])), 'RENT/2026/09');
    await userEvent.click(screen.getByRole('button', { name: t('common.submit') }));
    await screen.findByText(t('e01t.submitted', [1]));
    const form = sent(calls, 'POST', '/v1/tax/declarations/mine')[0] as FormData;
    expect(JSON.parse(await (form.get('request') as Blob).text())).toMatchObject({
      regime: 'NEW',
      lines: [
        {
          kind: 'HRA_RENT',
          sectionCode: '80CCD(2)',
          declared: '999999',
          proof: {
            documentType: 'OTHER',
            reference: 'RENT/2026/09',
            documentDate: '2026-09-30',
            issuingAuthority: 'Landlord',
          },
        },
      ],
    });
  });

  it('SRS_7_20_whileTheDocumentTypesCannotBeReadAProofHasNoTypeToChoose', async () => {
    memberRoutes(YEAR, { 'GET /v1/document-types': () => problem(503, 'COM-503-UNAVAILABLE') });
    wrap(<MyTaxDeclarations />, MEMBER);
    await userEvent.click(await screen.findByRole('button', { name: t('action.declare') }));
    await userEvent.click(screen.getByLabelText(t('e01t.regime.NEW')));
    await userEvent.click(screen.getByRole('button', { name: t('e01t.addLine') }));
    await userEvent.upload(
      screen.getByLabelText(t('e01t.proofOf', [1])),
      new File(['%PDF'], 'rent.pdf', { type: 'application/pdf' }),
    );
    expect(within(screen.getByLabelText(t('e01t.proofTypeOf', [1]))).queryAllByRole('option')).toHaveLength(
      0,
    );
  });

  it('SRS_17_2_aYearThatCannotBeReadShowsTheReason', async () => {
    backend({ 'GET /v1/tax/declarations/mine': () => problem(404, 'COM-404-NOT-FOUND') });
    wrap(<MyTaxDeclarations />, MEMBER);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('action.declare') })).toBeNull();
  });
});

describe('I-07 declaration review', () => {
  function makerRoutes(
    round: DeclarationRound,
    over: Record<string, (url: string, init?: RequestInit) => unknown> = {},
  ) {
    return backend({
      'GET /v1/tax/declarations?': (url) => paged([round], 1, url),
      'GET /v1/tax/declarations/41': () => round,
      ...over,
    });
  }

  it('SRS_7_24_theMakerVerifiesEachOpenLineUpToItsAllowedAmountAndSendsTheRound', async () => {
    const calls = makerRoutes(ROUND, {
      'POST /v1/tax/declarations/41/verify': () => ({ status: 202, body: { ...ROUND, status: 'VERIFIED' } }),
    });
    wrap(<DeclarationReview />, MAKER);
    await userEvent.click(await screen.findByRole('button', { name: t('i07.openOf', ['104882', 1]) }));
    const amount = await screen.findByLabelText(t('i07.verifiedOf', ['80C']));
    expect(amount).toHaveValue('150000');
    expect(screen.getByText(t('i07.capped'))).toBeInTheDocument();
    // The frozen rent line is shown, not verified again.
    expect(screen.queryByLabelText(t('i07.verifiedOf', ['10(13A)']))).toBeNull();
    await userEvent.clear(amount);
    await userEvent.type(amount, '150000.01');
    expect(screen.getByRole('button', { name: t('action.verifyDeclaration') })).toBeDisabled();
    await userEvent.clear(amount);
    await userEvent.type(amount, '140000');
    // Returning needs a reason first.
    expect(screen.getByRole('button', { name: t('action.returnToMember') })).toHaveAttribute(
      'title',
      t('i07.reasonFirst'),
    );
    await userEvent.click(screen.getByRole('button', { name: t('action.verifyDeclaration') }));
    expect(await screen.findByText(t('i07.verifiedSent', ['104882']))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/41/verify')[0]).toEqual({ lines: [{ lineId: 1, verified: '140000' }] });
  });

  it('SRS_7_24_aRoundIsReturnedWithTheReason', async () => {
    const calls = makerRoutes(ROUND, {
      'POST /v1/tax/declarations/41/return': () => ({ ...ROUND, status: 'RETURNED' }),
    });
    wrap(<DeclarationReview />, MAKER);
    await userEvent.click(await screen.findByRole('button', { name: t('i07.openOf', ['104882', 1]) }));
    await userEvent.type(await screen.findByLabelText(t('i07.returnReason')), ' Proof unreadable ');
    await userEvent.click(screen.getByRole('button', { name: t('action.returnToMember') }));
    expect(await screen.findByText(t('i07.returned', ['104882']))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/41/return')[0]).toEqual({ reason: 'Proof unreadable' });
  });

  it('FRS_6_14_6_1_aFrozenRegimeIsResetThroughTheCheckerAndARefusalStaysOnTheCard', async () => {
    const approved = { ...ROUND, status: 'APPROVED' as const, regimeFrozen: true };
    const calls = makerRoutes(approved, {
      'POST /v1/tax/regimes/reset': () => problem(403, 'TAX-403-OWN-DECLARATION'),
    });
    wrap(<DeclarationReview />, MAKER);
    await userEvent.selectOptions(await screen.findByLabelText(t('i07.status')), 'APPROVED');
    await userEvent.click(await screen.findByRole('button', { name: t('i07.openOf', ['104882', 1]) }));
    const reset = await screen.findByRole('button', { name: t('action.resetRegime') });
    expect(reset).toBeDisabled();
    // Not at the Maker: verify and return are disabled with the reason.
    expect(screen.getByRole('button', { name: t('action.verifyDeclaration') })).toHaveAttribute(
      'title',
      t('i07.notSubmitted'),
    );
    await userEvent.type(screen.getByLabelText(t('i07.resetReason')), 'Wrong regime');
    await userEvent.click(reset);
    expect(await screen.findByText(t('error.TAX-403-OWN-DECLARATION'))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/v1/tax/regimes/reset')[0]).toEqual({
      employeeId: 9,
      finYear: '2026-27',
      reason: 'Wrong regime',
    });
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    await waitFor(() => expect(screen.queryByRole('button', { name: t('action.resetRegime') })).toBeNull());
  });

  it('SRS_7_1_theResetReasonIsItsOwnFieldAReturnReasonNeverResetsTheRegime', async () => {
    const frozen = { ...ROUND, regimeFrozen: true };
    const calls = makerRoutes(frozen, {
      'POST /v1/tax/declarations/41/return': () => ({ ...frozen, status: 'RETURNED' }),
    });
    wrap(<DeclarationReview />, MAKER);
    await userEvent.click(await screen.findByRole('button', { name: t('i07.openOf', ['104882', 1]) }));
    await userEvent.type(await screen.findByLabelText(t('i07.returnReason')), 'Proof unreadable');
    // The return reason does not enable the audited reset; the reset box is empty.
    const reset = screen.getByRole('button', { name: t('action.resetRegime') });
    expect(reset).toBeDisabled();
    expect(screen.getByLabelText(t('i07.resetReason'))).toHaveValue('');
    await userEvent.click(screen.getByRole('button', { name: t('action.returnToMember') }));
    expect(await screen.findByText(t('i07.returned', ['104882']))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/v1/tax/regimes/reset')).toHaveLength(0);
  });

  it('SRS_NF_3_aBadYearIsNamedAndAReaderHasNoActions', async () => {
    makerRoutes(ROUND);
    wrap(<DeclarationReview />, me(['I-07:VIEW']));
    await userEvent.click(await screen.findByRole('button', { name: t('i07.openOf', ['104882', 1]) }));
    expect(await screen.findByText('₹1,60,000.00')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('action.verifyDeclaration') })).toBeNull();
    expect(screen.queryByRole('button', { name: t('action.resetRegime') })).toBeNull();
    // The year is chosen from valid financial years: "2026" cannot be entered (UI audit 2026-11-02).
    const year = screen.getByLabelText(t('i07.finYear'));
    expect(year.tagName).toBe('SELECT');
    expect(within(year).queryByRole('option', { name: '2026' })).toBeNull();
  });

  it('SRS_7_24_aRoundWithoutRegimeOrIdentityAndAReturnedOneReadAsTheyAre', async () => {
    const bare = {
      ...ROUND,
      regime: null,
      eis: null,
      name: null,
      status: 'RETURNED' as const,
      returnReason: 'Proof unreadable',
    };
    backend({
      'GET /v1/tax/declarations?': (url) => paged([bare], 1, url),
      'GET /v1/tax/declarations/41': () => bare,
    });
    wrap(<DeclarationReview />, MAKER);
    const open = await screen.findByRole('button', { name: t('i07.openOf', ['', 1]) });
    expect(open.closest('tr')).toHaveTextContent('—');
    await userEvent.click(open);
    expect(await screen.findByText(t('e01t.returnedBecause', ['Proof unreadable']))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('action.verifyDeclaration') })).toBeDisabled();
  });

  it('SRS_7_24_aRoundThatCannotBeReadShowsTheReason', async () => {
    backend({
      'GET /v1/tax/declarations?': (url) => paged([ROUND], 1, url),
      'GET /v1/tax/declarations/41': () => problem(404, 'COM-404-NOT-FOUND'),
    });
    wrap(<DeclarationReview />, MAKER);
    await userEvent.click(await screen.findByRole('button', { name: t('i07.openOf', ['104882', 1]) }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('the financial year runs from April to March', () => {
    expect(finYearOf('2026-04-01')).toBe('2026-27');
    expect(finYearOf('2027-03-31')).toBe('2026-27');
    expect(finYearOf('2099-12-31')).toBe('2099-00');
  });
});
