import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { InterestRates } from '@/modules/cpf/components/InterestRates';
import { OpeningBalanceCorrection } from '@/modules/cpf/components/OpeningBalanceCorrection';
import type { Correction, OpeningBalance, WithdrawalPurpose } from '@/modules/cpf/api/cpfApi';
import { backend, me, paged, problem, referenceLists, t, wrap } from './support/harness';

/**
 * The CPF configuration and correction screens without every particular (SRS-6-5, SRS-6-11,
 * SRS-6-14, SRS-6-43): an optional checklist document, a purpose with a version already pending, a
 * final payment that takes no limit, catalogues that failed to load, optional fields cleared to
 * none, a bonus that survives a switch of source, and a register row whose difference cannot be read.
 */

afterEach(() => vi.unstubAllGlobals());

const MARRIAGE: WithdrawalPurpose = {
  id: 1,
  code: 'MARRIAGE',
  name: 'Marriage',
  category: 'ADVANCE',
  limitRuleKey: 'CPF.LIMIT.MGW.MARRIAGE',
  maxOccurrences: 2,
  noCasteDispute: true,
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

const NO_RULES = {
  asOf: '2026-10-23',
  summary: { rules: 0, byType: {}, pendingRuleChanges: 0, pendingOtherChanges: 0, notConfigured: 0 },
  rules: [],
};

describe('M-19 withdrawal purposes without every particular', () => {
  it('SRS_6_14_anOptionalDocumentIsUnmarkedAPendingVersionIsSaidAndAFinalPaymentTakesNoLimit', async () => {
    const education: WithdrawalPurpose = {
      ...MARRIAGE,
      id: 3,
      code: 'EDUCATION',
      name: 'Education',
      documents: [{ documentType: 'OTHER', mandatory: false, tier: 'UNIT_CPF_ADMIN' }],
    };
    backend({
      'GET /v1/cpf/interest-rates': () => ({ rates: [], pending: [] }),
      'GET /v1/rules/pending': () => paged([]),
      'GET /v1/rules': () => NO_RULES,
      'GET /v1/cpf/withdrawal-purposes': () => ({
        purposes: [MARRIAGE, education],
        pending: [
          {
            approvalId: 7,
            proposal: { ...MARRIAGE, proposedBy: 'su.hq@fci-demo.test', proposedAt: '2026-10-22T05:00:00Z' },
          },
        ],
      }),
      'GET /v1/document-types': () => problem(503, 'COM-503-UNAVAILABLE'),
    });
    wrap(<InterestRates />, RULE_ADMIN);
    await screen.findByText('EDUCATION');
    const list = screen.getByRole('table', { name: t('m19p.title') });
    const educationRow = within(list).getByText('Education').closest('tr') as HTMLElement;
    expect(within(educationRow).getByText(/^OTHER · /)).toBeInTheDocument();
    const marriageRow = within(list).getByText('Marriage').closest('tr') as HTMLElement;
    const pending = within(marriageRow).getByRole('button', { name: t('m19p.newVersionOf', ['MARRIAGE']) });
    expect(pending).toBeDisabled();
    expect(pending).toHaveAttribute('title', t('m19p.pendingOne'));

    await userEvent.click(
      within(educationRow).getByRole('button', { name: t('m19p.newVersionOf', ['EDUCATION']) }),
    );
    await userEvent.selectOptions(screen.getByLabelText(t('m19p.category')), 'FINAL');
    const limit = screen.getByLabelText(t('m19p.limit')) as HTMLSelectElement;
    expect(limit).toBeDisabled();
    expect(limit.value).toBe('');
    await userEvent.selectOptions(screen.getByLabelText(t('m19p.category')), 'ADVANCE');
    await userEvent.selectOptions(limit, 'CPF.LIMIT.MGW.MARRIAGE');
    await userEvent.selectOptions(limit, '');
    expect(limit.value).toBe('');
    // Without the document-type catalogue a new checklist line offers only the empty choice.
    await userEvent.click(screen.getByRole('button', { name: t('m19p.addDocument') }));
    expect(within(screen.getByLabelText(t('m19p.documentOf', [2]))).getAllByRole('option')).toHaveLength(1);
  });

  it('SRS_22_21_outsideHeadquartersANewVersionIsLockedWithTheReasonAndNoPendingListIsNoList', async () => {
    backend({
      'GET /v1/cpf/interest-rates': () => ({ rates: [], pending: [] }),
      'GET /v1/rules/pending': () => paged([]),
      'GET /v1/rules': () => NO_RULES,
      'GET /v1/cpf/withdrawal-purposes': () => ({ purposes: [MARRIAGE] }),
      'GET /v1/document-types': () => [],
    });
    wrap(
      <InterestRates />,
      me(['M-19:VIEW', 'M-19:CREATE'], {
        role: 'RULE_ADMINISTRATOR',
        atHeadquarters: false,
        hqOnlyScreens: ['M-19'],
        location: { code: 'NA11', label: 'ZO — North (NA11)' },
      }),
    );
    await screen.findByText('MARRIAGE');
    const locked = screen.getByRole('button', { name: t('m19p.newVersionOf', ['MARRIAGE']) });
    expect(locked).toBeDisabled();
    expect(locked).toHaveAttribute('title', t('error.AUTHZ-403-HQ-ONLY'));
  });
});

describe('M-19 interest rates without every particular', () => {
  it('SRS_6_11_theOptionalEndBonusAndNoteClearToNoneAndTheBonusIsDroppedWhenTheSourceLeavesTheTrust', async () => {
    backend({
      'GET /v1/cpf/interest-rates': () => ({ rates: [], pending: [] }),
      'GET /v1/rules/pending': () => paged([]),
      'GET /v1/rules': () => NO_RULES,
      'GET /v1/cpf/withdrawal-purposes': () => ({ purposes: [], pending: [] }),
      'GET /v1/document-types': () => [],
    });
    wrap(<InterestRates />, RULE_ADMIN);
    await userEvent.click(await screen.findByRole('button', { name: t('action.newRate') }));
    await screen.findByRole('heading', { name: t('m19.newRate') });
    const to = screen.getByLabelText(t('mst.to')) as HTMLInputElement;
    await userEvent.type(to, '2027-03-31');
    await userEvent.clear(to);
    expect(to.value).toBe('');
    await userEvent.type(screen.getByLabelText(t('m19.bonus')), '2.5');
    await userEvent.selectOptions(screen.getByLabelText(t('m19.rateSource')), 'RPFC');
    expect(screen.queryByLabelText(t('m19.bonus'))).toBeNull();
    await userEvent.selectOptions(screen.getByLabelText(t('m19.rateSource')), 'FCI_TRUST');
    const bonus = screen.getByLabelText(t('m19.bonus')) as HTMLInputElement;
    expect(bonus.value).toBe('');
    await userEvent.type(bonus, '2');
    await userEvent.clear(bonus);
    expect(bonus.value).toBe('');
    const note = screen.getByLabelText(t('m19.note')) as HTMLInputElement;
    await userEvent.type(note, 'full year');
    await userEvent.clear(note);
    expect(note.value).toBe('');
  });
});

const MEMBER: OpeningBalance = {
  eis: '104882',
  name: 'Member One',
  cpfCode: 'NA11/CPF/0001',
  finYear: '2026-27',
  opening: { employee: 1000, employer: 500, vpf: 0 },
  settled: false,
  pending: false,
};

const APPLIED: Correction = {
  ref: 'OB-2026-000001',
  eis: '104882',
  finYear: '2026-27',
  asAtDate: '2026-04-01',
  parts: [{ part: 'EMPLOYEE', oldAmount: 900, newAmount: 1000, difference: 'n/a' as unknown as number }],
  reasonCode: 'CLERICAL_ERROR',
  remarks: 'Typing error in the migrated balance',
  interestEffectiveDate: '2026-04-01',
  interestEffect: 0,
  status: 'APPLIED',
  statusReason: null,
  raisedBy: 'cpf.manager.north@fci-demo.test',
  raisedAt: '2026-10-20T05:00:00Z',
  checker: 'agm.cpf.north@fci-demo.test',
  hqApprover: 'cpf.approver.hq@fci-demo.test',
  appliedAt: '2026-10-21T05:00:00Z',
  approvalId: 7,
  evidenceId: 31,
};

describe('I-22 without every particular', () => {
  it('SRS_6_43_aPartTypedThenClearedIsNoCorrectionADifferenceTheRegisterCannotReadCountsAsNothingAndNoTypesLeaveTheTypeEmpty', async () => {
    backend({
      'GET /v1/cpf/opening-balance-corrections': () => paged([APPLIED]),
      'GET /v1/cpf/opening-balances': () => MEMBER,
      'GET /v1/reference/lists': () =>
        referenceLists({ OB_CORRECTION_REASON: [['CLERICAL_ERROR', 'Clerical error']] }),
      'GET /v1/document-types': () => problem(503, 'COM-503-UNAVAILABLE'),
    });
    wrap(
      <OpeningBalanceCorrection />,
      me(['I-22:VIEW', 'I-22:CREATE'], {
        role: 'CPF_MANAGER',
        location: { code: 'NA11', label: 'ZO — North (NA11)' },
      }),
    );
    const applied = (await screen.findByText('OB-2026-000001')).closest('tr') as HTMLElement;
    expect(within(applied).getByText(/Employee: 900 → 1,000/)).toHaveTextContent(/\(.?0\.00\)/);

    await userEvent.type(screen.getByLabelText(t('i22.by.eis')), '104882');
    await userEvent.type(screen.getByLabelText(t('i22.asAt')), '2026-04-01');
    await userEvent.click(screen.getByRole('button', { name: t('i22.show') }));
    await screen.findByText('NA11/CPF/0001');
    await userEvent.click(screen.getByRole('button', { name: t('action.newCorrection') }));
    const parts = screen.getByRole('table', { name: t('i22.parts') });
    const field = within(parts).getByLabelText(
      t('i22.correctedOf', [t('i22.part.EMPLOYEE')]),
    ) as HTMLInputElement;
    await userEvent.type(field, '1100');
    expect(within(parts).getByText('+100.00')).toBeInTheDocument();
    await userEvent.clear(field);
    expect(field.value).toBe('');
    expect(within(parts).getAllByText('—')).toHaveLength(3);
    expect(within(screen.getByLabelText(t('ord.type'))).getAllByRole('option')).toHaveLength(1);
  });
});
