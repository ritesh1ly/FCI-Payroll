import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { OpeningBalanceCorrection } from '@/modules/cpf/components/OpeningBalanceCorrection';
import type { Correction, OpeningBalance } from '@/modules/cpf/api/cpfApi';
import { backend, me, problem, referenceLists, t, wrap, paged } from './support/harness';

/**
 * I-22 PF / CPF Opening Balance Correction (SRS-6-43, SRS-6-44, SRS I-22 field table): the member
 * lookup, the corrected parts with their difference, the evidence, what blocks a new correction,
 * and the Opening Balance Correction Register.
 */

afterEach(() => vi.unstubAllGlobals());

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
  parts: [{ part: 'EMPLOYEE', oldAmount: 900, newAmount: 1000, difference: 100 }],
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

const RETURNED: Correction = {
  ...APPLIED,
  ref: 'OB-2026-000002',
  parts: [{ part: 'VPF', oldAmount: 10, newAmount: 5, difference: -5 }],
  reasonCode: 'UNKNOWN_OLD_REASON',
  status: 'RETURNED',
  statusReason: 'Wrong evidence',
  checker: null,
  hqApprover: null,
  appliedAt: null,
  evidenceId: null,
};

const MANAGER = me(['I-22:VIEW', 'I-22:CREATE'], {
  role: 'CPF_MANAGER',
  location: { code: 'NA11', label: 'ZO — North (NA11)' },
});

const REFERENCE = referenceLists({
  OB_CORRECTION_REASON: [
    ['CLERICAL_ERROR', 'Clerical error'],
    ['SSP_RECONCILIATION', 'CPF SSP reconciliation difference'],
  ],
});

const TYPES = [{ code: 'OTHER', name: 'Other document', referenceRequired: false }];

function routes(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
  return backend({
    'GET /v1/cpf/opening-balance-corrections': () => paged([APPLIED, RETURNED]),
    'GET /v1/cpf/opening-balances': () => MEMBER,
    'GET /v1/reference/lists': () => REFERENCE,
    'GET /v1/document-types': () => TYPES,
    ...over,
  });
}

async function lookUp(eis = '104882', asAt = '2026-04-01') {
  await userEvent.type(screen.getByLabelText(t('i22.by.eis')), eis);
  if (asAt !== '') await userEvent.type(screen.getByLabelText(t('i22.asAt')), asAt);
  await userEvent.click(screen.getByRole('button', { name: t('i22.show') }));
  await screen.findByText('NA11/CPF/0001');
}

async function fillCorrection() {
  await userEvent.click(screen.getByRole('button', { name: t('action.newCorrection') }));
  await userEvent.type(screen.getByLabelText(t('i22.correctedOf', [t('i22.part.EMPLOYEE')])), '1100.50');
  await screen.findByRole('option', { name: 'Clerical error' });
  await userEvent.selectOptions(screen.getByLabelText(t('i22.reason')), 'CLERICAL_ERROR');
  await userEvent.type(screen.getByLabelText(t('i22.remarks')), 'Clerical error in the migrated balance');
  // SRS l.2240: the Effective Date for Interest starts empty and is the Maker's to enter.
  const interest = screen.getByLabelText(t('i22.interestDate')) as HTMLInputElement;
  expect(interest.value).toBe('');
  await userEvent.type(interest, '2026-04-01');
  await screen.findByRole('option', { name: 'Other document' });
  await userEvent.selectOptions(screen.getByLabelText(t('ord.type')), 'OTHER');
  await userEvent.type(screen.getByLabelText(t('ord.date')), '2026-10-01');
  await userEvent.type(screen.getByLabelText(t('ord.authority')), 'CPF Division NZ');
  await userEvent.upload(
    screen.getByLabelText(t('ord.file')),
    new File(['%PDF-1.7'], 'reconciliation.pdf', { type: 'application/pdf' }),
  );
}

describe('I-22 opening-balance correction', () => {
  it('SRS_l2234_aMemberIsFoundByUanAndTheUanIsShownReadOnly', async () => {
    const calls = routes({
      'GET /v1/cpf/opening-balances': () => ({ ...MEMBER, uan: '100000104882' }),
    });
    wrap(<OpeningBalanceCorrection />, MANAGER);
    await userEvent.selectOptions(screen.getByLabelText(t('i22.findBy')), 'uan');
    const show = screen.getByRole('button', { name: t('i22.show') });
    await userEvent.type(screen.getByLabelText(t('i22.by.uan')), '12345');
    await userEvent.type(screen.getByLabelText(t('i22.asAt')), '2026-04-01');
    expect(show).toBeDisabled();
    await userEvent.clear(screen.getByLabelText(t('i22.by.uan')));
    await userEvent.type(screen.getByLabelText(t('i22.by.uan')), '100000104882');
    await userEvent.click(show);
    expect(await screen.findByText('100000104882')).toBeInTheDocument();
    expect(calls.some((c) => c.url.includes('uan=100000104882'))).toBe(true);
  });

  it('SRS_l2208_theInterestEffectIsShownOrNotConfigured', async () => {
    routes({
      'GET /v1/cpf/opening-balance-corrections': () => paged([{ ...APPLIED, interestEffect: null }]),
    });
    wrap(<OpeningBalanceCorrection />, MANAGER);
    expect(await screen.findByText(t('i22.interestEffectNotConfigured'))).toBeInTheDocument();
  });

  it('SRS_6_43_theManagerSeesTheCurrentBalancePerPartAndTheDifferenceAsTyped', async () => {
    const calls = routes();
    wrap(<OpeningBalanceCorrection />, MANAGER);
    const show = screen.getByRole('button', { name: t('i22.show') });
    expect(show).toBeDisabled();
    await userEvent.type(screen.getByLabelText(t('i22.by.eis')), '10-48');
    await userEvent.type(screen.getByLabelText(t('i22.asAt')), '2026-04-01');
    expect(show).toBeDisabled();
    await userEvent.clear(screen.getByLabelText(t('i22.by.eis')));
    await lookUp(' 104882 ', '');
    expect(calls.some((c) => c.url.includes('/v1/cpf/opening-balances?eis=104882&asAt=2026-04-01'))).toBe(
      true,
    );
    const current = screen.getByLabelText(t('i22.current'));
    expect(within(current).getByText('Member One (104882)')).toBeInTheDocument();
    expect(within(current).getByText('2026-27')).toBeInTheDocument();
    expect(within(current).getByText('1,000')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: t('action.newCorrection') }));
    const parts = screen.getByRole('table', { name: t('i22.parts') });
    await userEvent.type(
      within(parts).getByLabelText(t('i22.correctedOf', [t('i22.part.EMPLOYER')])),
      '400.25',
    );
    expect(within(parts).getByText('−99.75')).toBeInTheDocument();
    const problems = screen.getByRole('list', { name: t('i22.problems') });
    expect(within(problems).getByText(t('i22.problem.reason'))).toBeInTheDocument();
    expect(within(problems).getByText(t('error.CPF-422-EVIDENCE'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('common.submitForApproval') })).toBeDisabled();
    expect(screen.getByText(t('i22.routeText'))).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('table', { name: t('i22.parts') })).not.toBeInTheDocument();
  });

  it('SRS_6_43_theSubmissionCarriesOnlyTheCorrectedPartsAndTheEvidence', async () => {
    const calls = routes({
      'POST /v1/cpf/opening-balance-corrections': () => ({
        status: 201,
        body: { ...APPLIED, ref: 'OB-2026-000003', status: 'PENDING_AGM_CPF' },
      }),
    });
    wrap(<OpeningBalanceCorrection />, MANAGER);
    await lookUp();
    await fillCorrection();
    // The interest effect may start later than the as-at date (SRS-6-44).
    const interest = screen.getByLabelText(t('i22.interestDate')) as HTMLInputElement;
    await userEvent.clear(interest);
    await userEvent.type(interest, '2026-07-01');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByText(t('i22.submitted', ['OB-2026-000003']))).toBeInTheDocument();
    const form = calls.find((c) => c.method === 'POST')?.body as FormData;
    expect(form.get('eis')).toBe('104882');
    expect(form.get('asAtDate')).toBe('2026-04-01');
    expect(form.get('employee')).toBe('1100.50');
    expect(form.has('employer')).toBe(false);
    expect(form.has('vpf')).toBe(false);
    expect(form.get('reasonCode')).toBe('CLERICAL_ERROR');
    expect(form.get('interestEffectiveDate')).toBe('2026-07-01');
    expect(form.get('remarks')).toBe('Clerical error in the migrated balance');
    expect(form.get('documentType')).toBe('OTHER');
    expect(form.has('reference')).toBe(false);
    expect(form.get('issuingAuthority')).toBe('CPF Division NZ');
    expect((form.get('file') as File).name).toBe('reconciliation.pdf');
    await waitFor(() =>
      expect(screen.queryByRole('table', { name: t('i22.parts') })).not.toBeInTheDocument(),
    );
  });

  it('SRS_6_44_aLoweringAfterASanctionIsReportedAsRoutedToTheCpfDivision', async () => {
    routes({
      'POST /v1/cpf/opening-balance-corrections': () => ({
        status: 201,
        body: { ...APPLIED, ref: 'OB-2026-000004', status: 'BLOCKED_CPF_DIVISION' },
      }),
    });
    wrap(<OpeningBalanceCorrection />, MANAGER);
    await lookUp();
    await fillCorrection();
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByText(t('i22.blocked', ['OB-2026-000004']))).toBeInTheDocument();
  });

  it('SRS_6_44_aServerRefusalIsShownAndTheDraftKept', async () => {
    routes({
      'POST /v1/cpf/opening-balance-corrections': () => problem(409, 'CPF-409-CORRECTION-PENDING'),
    });
    wrap(<OpeningBalanceCorrection />, MANAGER);
    await lookUp();
    await fillCorrection();
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByText(t('error.CPF-409-CORRECTION-PENDING'))).toBeInTheDocument();
    expect(screen.getByRole('table', { name: t('i22.parts') })).toBeInTheDocument();
  });

  it('SRS_6_44_aSettledOrPendingMemberCannotBeCorrectedAndSaysWhy', async () => {
    routes({ 'GET /v1/cpf/opening-balances': () => ({ ...MEMBER, settled: true, pending: true }) });
    wrap(<OpeningBalanceCorrection />, MANAGER);
    await lookUp();
    const create = screen.getByRole('button', { name: t('action.newCorrection') });
    expect(create).toBeDisabled();
    expect(create).toHaveAttribute('title', t('error.CPF-409-SETTLED'));
  });

  it('SRS_6_44_aPendingCorrectionBlocksANewOne', async () => {
    routes({ 'GET /v1/cpf/opening-balances': () => ({ ...MEMBER, pending: true }) });
    wrap(<OpeningBalanceCorrection />, MANAGER);
    await lookUp();
    expect(screen.getByRole('button', { name: t('action.newCorrection') })).toHaveAttribute(
      'title',
      t('error.CPF-409-CORRECTION-PENDING'),
    );
  });

  it('SRS_22_21_aReaderWithoutCreateSeesTheBalanceButRaisesNothing', async () => {
    routes();
    wrap(<OpeningBalanceCorrection />, me(['I-22:VIEW'], { role: 'CPF_DIVISION' }));
    await lookUp();
    expect(screen.queryByRole('button', { name: t('action.newCorrection') })).not.toBeInTheDocument();
  });

  it('SRS_6_44_aMemberOutsideTheScopeAnswersAsUnknown', async () => {
    routes({ 'GET /v1/cpf/opening-balances': () => problem(404, 'COM-404-NOT-FOUND') });
    wrap(<OpeningBalanceCorrection />, MANAGER);
    await userEvent.type(screen.getByLabelText(t('i22.by.eis')), '999999');
    await userEvent.type(screen.getByLabelText(t('i22.asAt')), '2026-04-01');
    await userEvent.click(screen.getByRole('button', { name: t('i22.show') }));
    expect(await screen.findByText(t('error.COM-404-NOT-FOUND'))).toBeInTheDocument();
  });

  it('SRS_6_44_theRegisterListsEachCorrectionWithItsPartsDecidersAndEvidence', async () => {
    routes();
    wrap(<OpeningBalanceCorrection />, MANAGER);
    const applied = (await screen.findByText('OB-2026-000001')).closest('tr') as HTMLElement;
    expect(within(applied).getByText(t('i22.status.APPLIED'))).toHaveClass('p-ok');
    expect(within(applied).getByText(/Employee: 900 → 1,000/)).toHaveTextContent('(+100.00)');
    expect(await within(applied).findByText('Clerical error')).toBeInTheDocument();
    expect(within(applied).getByText('agm.cpf.north@fci-demo.test')).toBeInTheDocument();
    expect(within(applied).getByText('cpf.approver.hq@fci-demo.test')).toBeInTheDocument();
    expect(within(applied).getByRole('link', { name: t('i22.evidence') })).toHaveAttribute(
      'href',
      '/nginx/fci-payroll/v1/cpf/opening-balance-corrections/OB-2026-000001/evidence/31',
    );
    const returned = screen.getByText('OB-2026-000002').closest('tr') as HTMLElement;
    expect(within(returned).getByText('Wrong evidence')).toBeInTheDocument();
    expect(within(returned).getByText(/VPF: 10 → 5/)).toHaveTextContent('(−5.00)');
    // A reason no longer in force still shows its code.
    expect(within(returned).getByText('UNKNOWN_OLD_REASON')).toBeInTheDocument();
    expect(within(returned).queryByRole('link', { name: t('i22.evidence') })).not.toBeInTheDocument();
    expect(within(returned).getAllByText('—')).toHaveLength(2);
  });

  it('SRS_6_44_anEmptyRegisterSaysSo', async () => {
    routes({ 'GET /v1/cpf/opening-balance-corrections': () => paged([]) });
    wrap(<OpeningBalanceCorrection />, MANAGER);
    expect(await screen.findByText(t('i22.noCorrections'))).toBeInTheDocument();
  });
});
