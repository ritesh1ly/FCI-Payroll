import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { InterestRates, StatutoryRates } from '@/modules/cpf';
import type { InterestRate } from '@/modules/cpf/api/cpfApi';
import type { RegisterRule } from '@/modules/cpf/api/cpfScreensApi';
import { backend, me, paged, t, wrap } from './support/harness';

/**
 * M-18 Statutory Rate Seed and M-19 PF Interest Rates as Wireframes v6.1 draws them (OD-121; SRS-6-47, SRS-6-11,
 * BR-CPF-017, BR-CPF-033): the value a rule row shows (a value, slabs, or not seeded: never a guess, R-RULE), the
 * actions each role sees (proposer, approver, Payroll Checker told who decides, other roles locked with the reason)
 * and the View dialog of a row.
 */
afterEach(() => vi.unstubAllGlobals());

const EMPTY_CATALOGUE = {
  asOf: '2026-10-23',
  summary: { rules: 0, byType: {}, pendingRuleChanges: 0, pendingOtherChanges: 0, notConfigured: 0 },
  rules: [],
};

const UNSEEDED: RegisterRule = {
  key: 'CPF.EPS.RATE',
  name: 'EPS share',
  description: null,
  payrollTypeScope: null,
  inForce: null,
  versions: 0,
};
const VALUED: RegisterRule = {
  key: 'CPF.RATE.EMPLOYEE',
  name: 'Employee share',
  description: 'BR-CPF-017',
  payrollTypeScope: 'IDA',
  inForce: {
    versionNo: 5,
    effectiveFrom: '2026-04-01',
    status: 'ACTIVE',
    value: 12,
    bands: [],
    circularNumber: 'FCI/CPF/1',
  },
  versions: 1,
};
const SLABS: RegisterRule = {
  key: 'PT.SLABS',
  name: 'Professional tax slabs',
  description: 'PT',
  payrollTypeScope: null,
  inForce: {
    versionNo: 2,
    effectiveFrom: '2026-04-01',
    status: 'ACTIVE',
    value: null,
    bands: [{ from: 0, to: 10000, value: 0 }] as never,
    circularNumber: null,
  },
  versions: 1,
};
const NO_BANDS: RegisterRule = {
  ...SLABS,
  key: 'PT.EMPTY',
  name: 'Empty slab rule',
  inForce: {
    ...(SLABS.inForce as NonNullable<RegisterRule['inForce']>),
    bands: [],
    circularNumber: 'FCI/PT/9',
  },
};

function registerRoutes(rules: RegisterRule[]) {
  return backend({
    'GET /v1/rules/register/m-18': () => rules,
    'GET /v1/rules/pending': () => paged([]),
    'GET /v1/rules': () => EMPTY_CATALOGUE,
    'GET /v1/tax/sections': () => paged([]),
    'GET /v1/tax/pt-states': () => paged([]),
  });
}

describe('M-18 rule values and actions', () => {
  it('SRS_6_47_aSlabRuleReadsSlabsAndARuleWithNeitherValueNorSlabsReadsNotSeeded', async () => {
    registerRoutes([VALUED, SLABS, NO_BANDS]);
    wrap(<StatutoryRates />, me(['M-18:VIEW'], { roles: ['FINANCE'] }));
    const table = await screen.findByRole('table', { name: t('m18w.table') });
    const slab = (await within(table).findByText('PT.SLABS')).closest('tr') as HTMLElement;
    expect(within(slab).getByText(t('m18w.slabs'))).toBeInTheDocument();
    // no circular: a dash, never an invented order number
    expect(within(slab).getAllByText('—').length).toBeGreaterThan(0);
    const empty = within(table).getByText('PT.EMPTY').closest('tr') as HTMLElement;
    expect(within(empty).getByText(t('m18w.notSeeded'))).toBeInTheDocument();
    const valued = within(table).getByText('CPF.RATE.EMPLOYEE').closest('tr') as HTMLElement;
    // the payroll-type scope stands in the "applies to" column when the rule has one
    expect(within(valued).getByText('IDA')).toBeInTheDocument();
    expect(within(valued).getByText('v5')).toBeInTheDocument();
    // the tiles: three keys, three in force, two distinct circulars
    expect(screen.getAllByText('3').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('2')).toBeInTheDocument();
  });

  it('SRS_6_47_aRoleWithoutRulesRightsSeesNewAndSupersedeLockedWithTheReason', async () => {
    registerRoutes([VALUED]);
    wrap(<StatutoryRates />, me(['M-18:VIEW'], { roles: undefined as unknown as string[] }));
    const created = await screen.findByRole('button', { name: t('m18w.act.new') });
    expect(created).toBeDisabled();
    expect(created).toHaveAttribute('data-why', t('m18w.why.new'));
    const supersede = screen.getByRole('button', { name: t('m18w.act.supersede') });
    expect(supersede).toBeDisabled();
    expect(supersede).toHaveAttribute('data-why', t('m18w.why.supersede'));
    const edit = await screen.findByRole('button', { name: t('m18w.editName', ['CPF.RATE.EMPLOYEE']) });
    // no approve right and not a checker: no ✔ drawn on the row
    expect(screen.queryByTitle(t('wf.approveTitle'))).toBeNull();
    expect(edit).toBeDisabled();
    expect(edit).toHaveAttribute('data-why', t('m18w.why.edit'));
  });

  it('SRS_6_47_anAdministratorHoldingM18CreateIsToldWhyTheEntryIsNotHisWithoutTheFallback', async () => {
    registerRoutes([VALUED]);
    const user = userEvent.setup();
    wrap(<StatutoryRates />, me(['M-18:VIEW', 'M-18:CREATE'], { roles: ['SYSTEM_ADMINISTRATOR'] }));
    const created = await screen.findByRole('button', { name: t('m18w.act.new') });
    // M-18 create held, so no M-18 entry block: the administrator is told he is not the M-08 maker
    expect(created).toHaveAttribute('data-guard', t('mst.why.notMaker'));
    await user.click(created);
    expect(await screen.findByText(t('mst.why.notMaker'), { selector: ':not(button)' })).toBeInTheDocument();
  });

  it('SRS_6_47_theRuleAdministratorProposesAndTheRuleApproverSupersedesFromTheRegister', async () => {
    registerRoutes([VALUED]);
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    const user = userEvent.setup();
    wrap(
      <StatutoryRates />,
      me(['M-18:VIEW', 'M-08:VIEW', 'M-08:EDIT', 'M-08:APPROVE', 'M-18:APPROVE'], {
        roles: ['RULE_ADMINISTRATOR'],
      }),
    );
    const register = await screen.findByTestId('m18-register');
    expect(register).not.toHaveAttribute('open');
    await user.click(screen.getByRole('button', { name: t('m18w.act.supersede') }));
    await waitFor(() => expect(register).toHaveAttribute('open'));
    // the row Edit is live for the proposer
    expect(screen.getByRole('button', { name: t('m18w.editName', ['CPF.RATE.EMPLOYEE']) })).toBeEnabled();
    // ✔ on a rule row: nothing waits on it (pending false), so the approver is told, never sent to W-01
    await user.click(screen.getByTitle(t('wf.approveTitle')));
    expect(await screen.findByText(t('mst.nothingWaiting'))).toBeInTheDocument();
    expect(assign).not.toHaveBeenCalled();
  });

  it('SRS_6_47_theNewButtonOfTheProposerOpensTheRegister', async () => {
    registerRoutes([VALUED]);
    const user = userEvent.setup();
    wrap(<StatutoryRates />, me(['M-18:VIEW', 'M-08:CREATE'], { roles: ['RULE_ADMINISTRATOR'] }));
    await user.click(await screen.findByRole('button', { name: t('m18w.act.new') }));
    await waitFor(() => expect(screen.getByTestId('m18-register')).toHaveAttribute('open'));
  });

  it('SRS_6_47_thePayrollCheckerIsToldTheRuleApproverDecides', async () => {
    registerRoutes([VALUED]);
    const user = userEvent.setup();
    wrap(<StatutoryRates />, me(['M-18:VIEW'], { roles: ['PAYROLL_CHECKER'] }));
    const supersede = await screen.findByRole('button', { name: t('m18w.act.supersede') });
    expect(supersede).toHaveAttribute('data-guard', t('mst.why.ruleApprover'));
    await user.click(await screen.findByTitle(t('wf.approveTitle')));
    expect(await screen.findAllByText(t('mst.why.ruleApprover'))).not.toHaveLength(0);
  });

  it('SRS_6_47_theViewDialogShowsTheVersionInForceOrDashesForAnUnseededRule', async () => {
    registerRoutes([VALUED, UNSEEDED]);
    const user = userEvent.setup();
    wrap(<StatutoryRates />, me(['M-18:VIEW'], { roles: ['FINANCE'] }));
    await user.click(await screen.findByRole('button', { name: t('m18w.viewName', ['CPF.RATE.EMPLOYEE']) }));
    let dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Employee share')).toBeInTheDocument();
    expect(within(dialog).getByText('FCI/CPF/1')).toBeInTheDocument();
    expect(within(dialog).getByText('BR-CPF-017')).toBeInTheDocument();
    expect(within(dialog).getByText('12')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    await user.click(screen.getByRole('button', { name: t('m18w.viewName', ['CPF.EPS.RATE']) }));
    dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(t('m18w.notSeeded'))).toBeInTheDocument();
    // from, circular and basis: three dashes, no assumed value
    expect(within(dialog).getAllByText('—')).toHaveLength(3);
  });
});

const ACTIVE: InterestRate = {
  id: 11,
  finYear: '2025-26',
  fundType: 'CPF',
  rateSource: 'FCI_TRUST',
  memberPopulation: 'ACTIVE_AND_DEPUTED',
  ratePercent: 8.25,
  appliesFrom: '2025-04-01',
  appliesTo: '2026-03-31',
  applicabilityNote: null,
  bonusInterestPct: 0.5,
  declared: true,
  status: 'ACTIVE',
  approvalId: 1,
} as InterestRate;
const PENDING: InterestRate = {
  ...ACTIVE,
  id: 12,
  finYear: '2026-27',
  rateSource: 'RPFC',
  appliesTo: null,
  bonusInterestPct: null,
  declared: false,
  status: 'SUPERSEDED',
} as InterestRate;

function rateRoutes(rates: InterestRate[], pending: number[] = [], parameters: RegisterRule[] = [VALUED]) {
  return backend({
    'GET /v1/cpf/interest-rates': () => ({ rates, pending: pending.map((rateId) => ({ rateId })) }),
    'GET /v1/rules/register/m-19': () => parameters,
    'GET /v1/cpf/withdrawal-purposes': () => ({ purposes: [], pending: [] }),
    'GET /v1/rules/pending': () => paged([]),
    'GET /v1/rules': () => EMPTY_CATALOGUE,
  });
}

describe('M-19 rates, parameters and actions', () => {
  it('SRS_6_11_noRateYetIsSaidAndALockedNewCarriesTheReason', async () => {
    rateRoutes([]);
    wrap(<InterestRates />, me(['M-19:VIEW'], { roles: ['FINANCE'] }));
    expect(await screen.findByText(t('m19.noRates'))).toBeInTheDocument();
    const created = screen.getByRole('button', { name: t('m19w.act.new') });
    expect(created).toBeDisabled();
    expect(created).toHaveAttribute('data-why', t('m19w.why.new'));
    // the parameter row: its Edit disabled with the entry reason, its basis read from the rule
    expect(await screen.findByText('Employee share')).toBeInTheDocument();
    const edit = await screen.findByRole('button', { name: t('m18w.editName', ['CPF.RATE.EMPLOYEE']) });
    expect(edit).toHaveAttribute('data-why', t('mst.why.notMaker'));
  });

  it('SRS_6_11_theAdministratorIsToldWhyAndAParameterWithoutBasisShowsADash', async () => {
    rateRoutes([ACTIVE], [], [UNSEEDED]);
    wrap(<InterestRates />, me(['M-19:VIEW'], { roles: ['SYSTEM_ADMINISTRATOR'] }));
    const created = await screen.findByRole('button', { name: t('m19w.act.new') });
    expect(created).toHaveAttribute('data-guard', t('mst.why.notMaker'));
    const row = (await screen.findByText('EPS share')).closest('tr') as HTMLElement;
    expect(within(row).getByText('—')).toBeInTheDocument();
    expect(within(row).getByText(t('m18w.notSeeded'))).toBeInTheDocument();
  });

  it('SRS_6_11_theApproverOfAPendingRateGoesToW01AndANonPendingOneIsToldNothingWaits', async () => {
    rateRoutes([ACTIVE, PENDING], [12]);
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    const user = userEvent.setup();
    wrap(<InterestRates />, me(['M-19:VIEW', 'M-19:APPROVE'], { roles: ['RULE_APPROVER'] }));
    const table = await screen.findByRole('table', { name: t('m19w.rates') });
    const pendingRow = (await within(table).findByText('2026-27')).closest('tr') as HTMLElement;
    // a rate not ACTIVE carries the grey pill and its own status
    expect(within(pendingRow).getByText(t('status.SUPERSEDED'))).toHaveClass('p-gray');
    await user.click(within(pendingRow).getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('s=W-01'));
    const activeRow = within(table).getByText('2025-26').closest('tr') as HTMLElement;
    await user.click(within(activeRow).getByTitle(t('wf.approveTitle')));
    expect(await screen.findByText(t('mst.nothingWaiting'))).toBeInTheDocument();
    expect(assign).toHaveBeenCalledTimes(1);
    // the parameter row's ✔ asks nothing of W-01 either
    const params = screen.getByRole('table', { name: t('m19w.parameters') });
    await user.click(within(params).getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledTimes(1);
  });

  it('SRS_6_11_theViewDialogShowsThePeriodAndTheBonusOrAnOpenEndAndADash', async () => {
    rateRoutes([ACTIVE, PENDING]);
    const user = userEvent.setup();
    wrap(<InterestRates />, me(['M-19:VIEW'], { roles: ['FINANCE'] }));
    const view = (year: string, source: string) =>
      screen.getByRole('button', { name: t('m19w.viewName', [year, t(`m19.source.${source}`)]) });
    await screen.findByText('2025-26');
    await user.click(view('2025-26', 'FCI_TRUST'));
    let dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('8.25')).toBeInTheDocument();
    expect(within(dialog).getByText(/^0\.5/)).toBeInTheDocument();
    expect(within(dialog).queryByText(new RegExp(t('mst.open')))).toBeNull();
    await user.click(within(dialog).getByRole('button', { name: t('common.close') }));
    await user.click(view('2026-27', 'RPFC'));
    dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(t('m19w.undeclared'))).toBeInTheDocument();
    expect(within(dialog).getByText(new RegExp(t('mst.open')))).toBeInTheDocument();
    expect(within(dialog).getByText('—')).toBeInTheDocument();
  });

  it('SRS_6_11_theMakerOpensTheRegisterFromARowAndAParameter', async () => {
    rateRoutes([ACTIVE]);
    const user = userEvent.setup();
    wrap(
      <InterestRates />,
      me(['M-19:VIEW', 'M-19:CREATE'], {
        roles: ['RULE_ADMINISTRATOR'],
        location: { code: 'HQ001', label: 'HQ' },
      }),
    );
    const register = await screen.findByTestId('m19-register');
    await user.click(
      await screen.findByRole('button', { name: t('m19w.editName', ['2025-26', t('m19.source.FCI_TRUST')]) }),
    );
    await waitFor(() => expect(register).toHaveAttribute('open'));
  });

  it('SRS_6_11_aParameterRowViewAndEditOpenTheRegister', async () => {
    rateRoutes([]);
    const user = userEvent.setup();
    wrap(
      <InterestRates />,
      me(['M-19:VIEW', 'M-19:CREATE'], {
        roles: ['RULE_ADMINISTRATOR'],
        location: { code: 'HQ001', label: 'HQ' },
      }),
    );
    const register = await screen.findByTestId('m19-register');
    await user.click(await screen.findByRole('button', { name: t('m18w.editName', ['CPF.RATE.EMPLOYEE']) }));
    await waitFor(() => expect(register).toHaveAttribute('open'));
  });

  it('SRS_6_11_aParameterRowViewOpensTheRegister', async () => {
    rateRoutes([]);
    const user = userEvent.setup();
    wrap(<InterestRates />, me(['M-19:VIEW'], { roles: ['FINANCE'] }));
    const register = await screen.findByTestId('m19-register');
    await user.click(await screen.findByRole('button', { name: t('m18w.viewName', ['CPF.RATE.EMPLOYEE']) }));
    await waitFor(() => expect(register).toHaveAttribute('open'));
  });
});
