import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { InterestRates } from '@/modules/cpf';
import type { WithdrawalPurpose, WithdrawalPurposes } from '@/modules/cpf/api/cpfApi';
import { backend, me, problem, sent, t, wrap } from './support/harness';

/**
 * M-19 withdrawal purposes and checklists (SRS-6-5, SRS-6-14, SRS-6-39; BR-CPF-028, 039): the list
 * with its versions and checklists, a new version proposed with its documents, the screen's own
 * checks, a pending proposal, and who may propose.
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

const HOUSE: WithdrawalPurpose = {
  ...MARRIAGE,
  id: 2,
  code: 'READY_BUILT_HOUSE',
  name: 'Ready-built house',
  category: 'PART_FINAL',
  limitRuleKey: null,
  maxOccurrences: null,
  noCasteDispute: false,
  propertyDetails: true,
  effectiveTo: '2026-12-31',
  confirmed: true,
  inForce: false,
  documents: [],
};

const LIST: WithdrawalPurposes = {
  purposes: [MARRIAGE, HOUSE],
  pending: [
    {
      approvalId: 7,
      proposal: {
        ...MARRIAGE,
        code: 'PLOT_PURCHASE',
        effectiveFrom: '2027-04-01',
        confirmed: true,
        proposedBy: 'su.hq@fci-demo.test',
        proposedAt: '2026-10-22T05:00:00Z',
      },
    },
  ],
};

const RULE_ADMIN = me(['M-19:VIEW', 'M-19:CREATE', 'M-19:EDIT'], {
  role: 'RULE_ADMINISTRATOR',
  location: { code: 'HQ001', label: 'HQ' },
});

function routes(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
  return backend({
    'GET /v1/cpf/interest-rates': () => ({ rates: [], pending: [] }),
    'GET /v1/cpf/withdrawal-purposes': () => LIST,
    'GET /v1/rules/register/m-19': () => [],
    'GET /v1/document-types': () => [
      { code: 'CERTIFICATE', name: 'Certificate', referenceRequired: false },
      { code: 'OTHER', name: 'Other document', referenceRequired: false },
    ],
    ...over,
  });
}

/** The purposes sit in the closed register of M-19: 🕘 History opens it, as a user does. */
async function openRegister() {
  await userEvent.click(await screen.findByRole('button', { name: t('m19w.act.history') }));
}

/** The purpose table once the register is open and its rows have loaded. */
async function table() {
  await openRegister();
  await screen.findByText('MARRIAGE');
  return screen.getByRole('table', { name: t('m19p.title') });
}

describe('M-19 withdrawal purposes', () => {
  it('SRS_6_39_eachVersionShowsItsCategoryLimitChecklistAndPeriod', async () => {
    routes();
    wrap(<InterestRates />, RULE_ADMIN);
    const list = await table();
    const marriage = within(list).getByText('Marriage').closest('tr') as HTMLElement;
    expect(within(marriage).getByText(t('m19p.unconfirmed'))).toBeInTheDocument();
    expect(within(marriage).getByText(t('m19p.quotaOf', [2]))).toBeInTheDocument();
    expect(within(marriage).getByText(t('m19p.noCasteDispute'))).toBeInTheDocument();
    expect(within(marriage).getByText(/CERTIFICATE \*/)).toBeInTheDocument();
    expect(within(marriage).getByText(t('m19p.inForce'))).toBeInTheDocument();
    const house = within(list).getByText('Ready-built house').closest('tr') as HTMLElement;
    expect(within(house).getByText(t('m19p.propertyDetails'))).toBeInTheDocument();
    expect(within(house).getByText(t('mst.notConfigured'))).toBeInTheDocument();
    expect(within(house).getByText(t('m19p.noChecklist'))).toBeInTheDocument();
    // An ended version takes no new version.
    expect(
      within(house).queryByRole('button', { name: t('m19p.newVersionOf', ['READY_BUILT_HOUSE']) }),
    ).toBeNull();
    expect(screen.getByRole('list', { name: t('m19p.pendingTitle') })).toHaveTextContent('PLOT_PURCHASE');
  });

  it('SRS_6_14_aNewVersionCarriesItsChecklistToTheRuleApprover', async () => {
    const calls = routes({
      'POST /v1/cpf/withdrawal-purposes': () => ({
        status: 202,
        body: { approvalId: 9, proposal: LIST.pending[0]?.proposal },
      }),
    });
    wrap(<InterestRates />, RULE_ADMIN);
    await userEvent.click(
      within(await table()).getByRole('button', { name: t('m19p.newVersionOf', ['MARRIAGE']) }),
    );
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    const problems = screen.getByRole('list', { name: t('m19p.problems') });
    expect(within(problems).getByText(t('error.CPF-422-PURPOSE-DATE'))).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText(t('mst.from')), '2027-04-01');
    await userEvent.click(screen.getByRole('button', { name: t('m19p.addDocument') }));
    expect(
      within(screen.getByRole('list', { name: t('m19p.problems') })).getByText(
        t('error.CPF-422-PURPOSE-DOCUMENT'),
      ),
    ).toBeInTheDocument();
    await screen.findAllByRole('option', { name: 'Other document' });
    await userEvent.selectOptions(screen.getByLabelText(t('m19p.documentOf', [2])), 'OTHER');
    await userEvent.click(screen.getByLabelText(t('m19p.mandatoryOf', [2])));
    await userEvent.selectOptions(screen.getByLabelText(t('m19p.tierOf', [2])), 'ZONAL_HQ_CPF_DIVISION');
    await userEvent.click(screen.getByLabelText(t('m19p.confirmed')));
    await userEvent.click(screen.getByLabelText(t('m19p.propertyDetails')));
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Checklist supplied by FCI');
    expect(submit).toBeEnabled();
    await userEvent.click(submit);
    expect(await screen.findByText(t('m19p.submitted', ['MARRIAGE']))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/v1/cpf/withdrawal-purposes')).toEqual([
      {
        code: 'MARRIAGE',
        name: 'Marriage',
        category: 'ADVANCE',
        limitRuleKey: 'CPF.LIMIT.MGW.MARRIAGE',
        maxOccurrences: 2,
        noCasteDispute: true,
        propertyDetails: true,
        effectiveFrom: '2027-04-01',
        confirmed: true,
        documents: [
          { documentType: 'CERTIFICATE', mandatory: true, tier: 'UNIT_CPF_ADMIN' },
          { documentType: 'OTHER', mandatory: false, tier: 'ZONAL_HQ_CPF_DIVISION' },
        ],
        reason: 'Checklist supplied by FCI',
      },
    ]);
  });

  it('SRS_6_5_aNewPurposeIsCheckedAndAFinalPaymentTakesNoLimit', async () => {
    routes({ 'POST /v1/cpf/withdrawal-purposes': () => problem(409, 'WF-409-PENDING') });
    wrap(<InterestRates />, RULE_ADMIN);
    await table();
    await userEvent.click(screen.getByRole('button', { name: t('action.newPurpose') }));
    await userEvent.type(screen.getByLabelText(t('m19p.code')), 'covid_19');
    expect(screen.getByText(t('error.CPF-422-PURPOSE-WITHDRAWN'))).toBeInTheDocument();
    await userEvent.clear(screen.getByLabelText(t('m19p.code')));
    await userEvent.type(screen.getByLabelText(t('m19p.code')), 'ILLNESS_LONG');
    await userEvent.type(screen.getByLabelText(t('m19p.name')), 'Long illness');
    await userEvent.selectOptions(screen.getByLabelText(t('m19p.limit')), 'CPF.LIMIT.MGW.MARRIAGE');
    await userEvent.selectOptions(screen.getByLabelText(t('m19p.category')), 'FINAL');
    expect(screen.getByLabelText(t('m19p.limit'))).toBeDisabled();
    expect(screen.getByLabelText(t('m19p.limit'))).toHaveValue('');
    await userEvent.type(screen.getByLabelText(t('m19p.quota')), '0');
    expect(screen.getByText(t('error.CPF-422-PURPOSE-QUOTA'))).toBeInTheDocument();
    await userEvent.clear(screen.getByLabelText(t('m19p.quota')));
    await userEvent.click(screen.getByRole('button', { name: t('m19p.addDocument') }));
    await userEvent.click(screen.getByRole('button', { name: t('m19p.removeOf', [1]) }));
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'New purpose');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByText(t('error.WF-409-PENDING'))).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByLabelText(t('m19p.code'))).toBeNull();
  });

  it('SRS_6_5_aPurposeWithAPendingVersionTakesNoOther', async () => {
    routes({
      'GET /v1/cpf/withdrawal-purposes': () => ({
        purposes: [MARRIAGE],
        pending: [{ approvalId: 8, proposal: { ...LIST.pending[0]?.proposal, code: 'MARRIAGE' } }],
      }),
    });
    wrap(<InterestRates />, RULE_ADMIN);
    const button = within(await table()).getByRole('button', { name: t('m19p.newVersionOf', ['MARRIAGE']) });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', t('m19p.pendingOne'));
  });

  it('SRS_22_21_theCpfDivisionReadsOnly', async () => {
    routes({ 'GET /v1/cpf/withdrawal-purposes': () => ({ purposes: [], pending: [] }) });
    wrap(<InterestRates />, me(['M-19:VIEW'], { role: 'CPF_DIVISION' }));
    await openRegister();
    expect(await screen.findByText(t('m19p.none'))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('action.newPurpose') })).toBeNull();
  });
});
