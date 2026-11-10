import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PtStateMaster, SectionMaster } from '@/modules/tax';
import type { PendingSection, PendingState, PtState, SectionVersion } from '@/modules/tax/api/taxApi';
import { date } from '@/shared/ui';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * M-18 income-tax sections and PT states (SRS-7-2, SRS-7-4, SRS-7-20; BR-TAX-002, BR-TAX-006): the
 * versions and states a page at a time, a new version and a state change sent to the Rule Approver,
 * the screen's own checks, what waits for a decision, and who may propose.
 */

afterEach(() => vi.unstubAllGlobals());

const C80: SectionVersion = {
  id: 1,
  code: '80C',
  description: 'Life insurance, PF, ELSS',
  limit: 150000,
  regime: 'OLD',
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  inForce: true,
};

const OLD80D: SectionVersion = {
  ...C80,
  id: 2,
  code: '80D',
  description: 'Health insurance',
  limit: null,
  regime: 'BOTH',
  effectiveTo: '2026-12-31',
  inForce: false,
};

const PENDING_SECTION: PendingSection = {
  approvalId: 7,
  proposal: {
    code: '80CCD(1B)',
    description: 'NPS',
    limit: '50000',
    regime: 'BOTH',
    effectiveFrom: '2027-04-01',
    proposedBy: 'su.hq@fci-demo.test',
    proposedAt: '2026-10-22T05:00:00Z',
  },
};

const MH: PtState = {
  id: 1,
  code: 'MH',
  name: 'Maharashtra',
  ptApplicable: true,
  registrationNumber: 'PTR-MH-0001',
  slabRuleKey: 'PT.SLAB',
  returnPeriodicity: 'MONTHLY',
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
  inForce: true,
};

const DL: PtState = {
  ...MH,
  id: 2,
  code: 'DL',
  name: 'Delhi',
  ptApplicable: false,
  registrationNumber: null,
  slabRuleKey: null,
  returnPeriodicity: null,
};

const PENDING_STATE: PendingState = {
  approvalId: 8,
  proposal: {
    code: 'DL',
    name: 'Delhi',
    ptApplicable: true,
    registrationNumber: null,
    returnPeriodicity: null,
    effectiveFrom: '2026-04-01',
    effectiveTo: null,
    proposedBy: 'su.hq@fci-demo.test',
    proposedAt: '2026-10-22T05:00:00Z',
  },
};

const RULE_ADMIN = me(['M-18:VIEW', 'M-18:CREATE', 'M-18:EDIT'], {
  role: 'RULE_ADMINISTRATOR',
  location: { code: 'HQ001', label: 'HQ' },
});

function routes(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
  return backend({
    'GET /v1/tax/sections?': (url) => paged([C80, OLD80D], 2, url),
    'GET /v1/tax/sections/pending': () => [PENDING_SECTION],
    'GET /v1/tax/pt-states?': (url) => paged([MH, DL], 2, url),
    'GET /v1/tax/pt-states/pending': () => [PENDING_STATE],
    ...over,
  });
}

describe('M-18 income-tax sections', () => {
  it('SRS_7_2_eachVersionShowsItsLimitRegimeAndPeriodAndWhatWaits', async () => {
    routes();
    wrap(<SectionMaster />, RULE_ADMIN);
    await screen.findByText('80C');
    const list = screen.getByRole('table', { name: t('m18s.title') });
    const c80 = within(list).getByText('80C').closest('tr') as HTMLElement;
    expect(c80).toHaveTextContent('₹1,50,000.00');
    expect(within(c80).getByText(t('m18s.regime.OLD'))).toBeInTheDocument();
    expect(within(c80).getByText(t('m19p.inForce'))).toBeInTheDocument();
    const d80 = within(list).getByText('80D').closest('tr') as HTMLElement;
    expect(within(d80).getByText(t('m18s.noLimit'))).toBeInTheDocument();
    // An ended version takes no new version.
    expect(within(d80).queryByRole('button', { name: t('m18s.newVersionOf', ['80D']) })).toBeNull();
    expect(screen.getByRole('list', { name: t('m18s.pendingTitle') })).toHaveTextContent('80CCD(1B)');
  });

  it('SRS_7_20_aNewVersionStartsAfterTheOneItReplacesAndGoesToTheRuleApprover', async () => {
    const calls = routes({
      'POST /v1/tax/sections': () => ({ status: 202, body: PENDING_SECTION }),
    });
    wrap(<SectionMaster />, RULE_ADMIN);
    await userEvent.click(await screen.findByRole('button', { name: t('m18s.newVersionOf', ['80C']) }));
    expect(screen.getByLabelText(t('m18s.code'))).toHaveAttribute('readonly');
    const problems = () => screen.getByRole('list', { name: t('m18s.problems') });
    expect(within(problems()).getByText(t('m18s.problem.order'))).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText(t('mst.from')), '2026-04-01');
    expect(within(problems()).getByText(t('m18s.problem.order'))).toBeInTheDocument();
    await userEvent.clear(screen.getByLabelText(t('mst.from')));
    await userEvent.type(screen.getByLabelText(t('mst.from')), '2027-04-01');
    const limit = screen.getByLabelText(t('m18s.limit'));
    await userEvent.clear(limit);
    await userEvent.type(limit, '1.005');
    expect(within(problems()).getByText(t('error.TAX-422-SECTION-LIMIT'))).toBeInTheDocument();
    await userEvent.clear(limit);
    await userEvent.type(limit, '200000.50');
    await userEvent.selectOptions(screen.getByLabelText(t('m18s.regime')), 'NEW');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Finance Act 2027');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByText(t('m18s.submitted', ['80C']))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/v1/tax/sections')[0]).toEqual({
      code: '80C',
      description: 'Life insurance, PF, ELSS',
      limit: '200000.50',
      regime: 'NEW',
      effectiveFrom: '2027-04-01',
      reason: 'Finance Act 2027',
    });
  });

  it('SRS_7_20_aNewSectionWithoutALimitAndTheServerRefusalShown', async () => {
    const calls = routes({
      'POST /v1/tax/sections': () => problem(409, 'TAX-409-SECTION-ORDER', ['80TTA', '2027-04-01']),
    });
    wrap(<SectionMaster />, RULE_ADMIN);
    await userEvent.click(await screen.findByRole('button', { name: t('action.newSection') }));
    expect(screen.getByRole('button', { name: t('action.newSection') })).toBeDisabled();
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    expect(submit).toBeDisabled();
    await userEvent.type(screen.getByLabelText(t('m18s.code')), '80TTA ');
    await userEvent.type(screen.getByLabelText(t('m18s.description')), 'Savings interest');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Notified');
    await userEvent.click(submit);
    expect(await screen.findByText(/80TTA starts after the version it replaces/)).toBeInTheDocument();
    expect(sent(calls, 'POST', '/v1/tax/sections')[0]).toMatchObject({ code: '80TTA', limit: null });
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('button', { name: t('common.submitForApproval') })).toBeNull();
  });

  it('SRS_7_26_aSectionWithAProposalAwaitingTheRuleApproverTakesNoSecond', async () => {
    routes({ 'GET /v1/tax/sections?': (url) => paged([{ ...C80, id: 3, code: '80CCD(1B)' }], 1, url) });
    wrap(<SectionMaster />, RULE_ADMIN);
    const button = await screen.findByRole('button', { name: t('m18s.newVersionOf', ['80CCD(1B)']) });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', t('m18s.pendingOne'));
  });

  it('SRS_7_26_outsideHeadquartersTheProposalIsDisabledWithItsReason', async () => {
    routes();
    wrap(
      <SectionMaster />,
      me(['M-18:VIEW', 'M-18:CREATE'], { atHeadquarters: false, hqOnlyScreens: ['M-18'] }),
    );
    const button = await screen.findByRole('button', { name: t('action.newSection') });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', t('error.AUTHZ-403-HQ-ONLY'));
    expect(await screen.findByRole('button', { name: t('m18s.newVersionOf', ['80C']) })).toHaveAttribute(
      'title',
      t('error.AUTHZ-403-HQ-ONLY'),
    );
  });

  it('SRS_7_26_aReaderSeesTheSectionsWithoutActions', async () => {
    routes({ 'GET /v1/tax/sections/pending': () => ({}) });
    wrap(<SectionMaster />, me(['M-18:VIEW']));
    expect(await screen.findByText('80C')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('action.newSection') })).toBeNull();
    expect(screen.queryByRole('button', { name: t('m18s.newVersionOf', ['80C']) })).toBeNull();
    expect(screen.queryByRole('list', { name: t('m18s.pendingTitle') })).toBeNull();
    // SRS-20-29 (US-03-07 AC1): a reader opens each section's change history.
    expect(screen.getAllByRole('button', { name: t('history.for', ['80C']) }).length).toBeGreaterThan(0);
  });
});

describe('M-18 Professional Tax states', () => {
  it('BR_TAX_006_eachStateShowsWhetherPtAppliesAndItsSystemSetSlabRule', async () => {
    routes();
    wrap(<PtStateMaster />, RULE_ADMIN);
    await screen.findByText('Maharashtra');
    const list = screen.getByRole('table', { name: t('m18p.title') });
    const mh = within(list).getByText('Maharashtra').closest('tr') as HTMLElement;
    expect(within(mh).getByText('PT.SLAB')).toBeInTheDocument();
    expect(within(mh).getByText(t('m18p.yes'))).toBeInTheDocument();
    expect(within(mh).getByText('MONTHLY')).toBeInTheDocument();
    const dl = within(list).getByText('Delhi').closest('tr') as HTMLElement;
    expect(within(dl).getByText(t('m18p.no'))).toBeInTheDocument();
    expect(within(dl).getByRole('button', { name: t('m18p.changeOf', ['DL']) })).toBeDisabled();
    // SRS-20-29 (US-03-07 AC1): each state opens its change history.
    expect(within(dl).getByRole('button', { name: t('history.for', ['DL']) })).toBeTruthy();
    expect(screen.getByRole('list', { name: t('m18p.pendingTitle') })).toHaveTextContent('Delhi');
  });

  it('SRS_7_4_aNewStateIsCreatedAndAChangeIsSentOnTheStatePath', async () => {
    const calls = routes({
      'POST /v1/tax/pt-states': () => ({ status: 202, body: PENDING_STATE }),
      'PUT /v1/tax/pt-states/MH': () => ({ status: 202, body: PENDING_STATE }),
    });
    wrap(<PtStateMaster />, RULE_ADMIN);
    await userEvent.click(await screen.findByRole('button', { name: t('action.newState') }));
    const submit = () => screen.getByRole('button', { name: t('common.submitForApproval') });
    await userEvent.type(screen.getByLabelText(t('m18p.code')), 'wb');
    await userEvent.type(screen.getByLabelText(t('m18p.name')), 'West Bengal');
    await userEvent.type(screen.getByLabelText(t('m18p.registration')), ' PTR-WB-1 ');
    await userEvent.type(screen.getByLabelText(t('m18p.periodicity')), 'monthly');
    await userEvent.type(screen.getByLabelText(t('mst.to')), '2026-03-31');
    expect(
      within(screen.getByRole('list', { name: t('m18s.problems') })).getByText(t('error.TAX-422-PT-STATE')),
    ).toBeInTheDocument();
    await userEvent.clear(screen.getByLabelText(t('mst.to')));
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Notified');
    expect(submit()).toBeEnabled();
    await userEvent.click(submit());
    expect(await screen.findByText(t('m18p.submitted', ['WB']))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/v1/tax/pt-states')[0]).toEqual({
      code: 'WB',
      name: 'West Bengal',
      ptApplicable: true,
      registrationNumber: 'PTR-WB-1',
      returnPeriodicity: 'MONTHLY',
      effectiveFrom: expect.any(String),
      effectiveTo: null,
      reason: 'Notified',
    });

    await userEvent.click(screen.getByRole('button', { name: t('m18p.changeOf', ['MH']) }));
    expect(screen.getByLabelText(t('m18p.code'))).toHaveAttribute('readonly');
    await userEvent.click(screen.getByLabelText(t('m18p.applicable')));
    await userEvent.clear(screen.getByLabelText(t('m18p.registration')));
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'PT withdrawn');
    await userEvent.click(submit());
    expect(await screen.findByText(t('m18p.submitted', ['MH']))).toBeInTheDocument();
    expect(sent(calls, 'PUT', '/v1/tax/pt-states/MH')[0]).toMatchObject({
      code: 'MH',
      ptApplicable: false,
      registrationNumber: null,
      returnPeriodicity: 'MONTHLY',
    });
  });

  it('SRS_7_4_aRefusalStaysOnTheForm', async () => {
    routes({ 'POST /v1/tax/pt-states': () => problem(409, 'MST-409-CODE', ['state_code', 'MH']) });
    wrap(<PtStateMaster />, RULE_ADMIN);
    await userEvent.click(await screen.findByRole('button', { name: t('action.newState') }));
    await userEvent.type(screen.getByLabelText(t('m18p.code')), 'MH');
    await userEvent.type(screen.getByLabelText(t('m18p.name')), 'Maharashtra');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Again');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByText(/state_code already has the value MH/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
  });

  it('SRS_7_4_aReaderSeesTheStatesWithoutActions', async () => {
    routes({ 'GET /v1/tax/pt-states/pending': () => ({}) });
    wrap(<PtStateMaster />, me(['M-18:VIEW']));
    expect(await screen.findAllByText('Maharashtra')).not.toHaveLength(0);
    expect(screen.queryByRole('button', { name: t('m18p.changeOf', ['MH']) })).toBeNull();
  });
  it('SRS_7_26_outsideHeadquartersAStateIsNotProposedAndAnEndedOneShowsItsEnd', async () => {
    routes({
      'GET /v1/tax/pt-states?': (url) => paged([{ ...MH, effectiveTo: '2027-03-31' }], 1, url),
    });
    wrap(
      <PtStateMaster />,
      me(['M-18:VIEW', 'M-18:CREATE', 'M-18:EDIT'], { atHeadquarters: false, hqOnlyScreens: ['M-18'] }),
    );
    const add = await screen.findByRole('button', { name: t('action.newState') });
    expect(add).toBeDisabled();
    expect(add).toHaveAttribute('title', t('error.AUTHZ-403-HQ-ONLY'));
    expect(await screen.findByRole('button', { name: t('m18p.changeOf', ['MH']) })).toHaveAttribute(
      'title',
      t('error.AUTHZ-403-HQ-ONLY'),
    );
    expect(screen.getByText(date('2027-03-31'), { exact: false })).toBeInTheDocument();
  });

  it('SRS_7_4_aStateFromAnotherDateAndARefusedSubmissionStaysOnTheForm', async () => {
    const calls = routes({ 'POST /v1/tax/pt-states': () => problem(422, 'TAX-422-PT-STATE') });
    wrap(<PtStateMaster />, RULE_ADMIN);
    await userEvent.click(await screen.findByRole('button', { name: t('action.newState') }));
    await userEvent.type(screen.getByLabelText(t('m18p.code')), 'GA');
    await userEvent.type(screen.getByLabelText(t('m18p.name')), 'Goa');
    const from = screen.getByLabelText(t('mst.from'));
    await userEvent.clear(from);
    await userEvent.type(from, '2026-04-01');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Notified');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByText(t('error.TAX-422-PT-STATE'), { exact: false })).toBeInTheDocument();
    expect(sent(calls, 'POST', '/v1/tax/pt-states')[0]).toMatchObject({
      code: 'GA',
      effectiveFrom: '2026-04-01',
    });
    expect(screen.getByRole('button', { name: t('common.submitForApproval') })).toBeInTheDocument();
  });
});
