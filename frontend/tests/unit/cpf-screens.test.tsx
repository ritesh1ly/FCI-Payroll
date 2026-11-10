import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { InterestRates } from '@/modules/cpf/components/InterestRates';
import { StatutoryRates } from '@/modules/cpf/components/StatutoryRates';
import type { InterestRate, InterestRates as Rates, PendingRateChange } from '@/modules/cpf/api/cpfApi';
import { backend, me, problem, sent, t, wrap, paged } from './support/harness';
import { rule, version } from './support/ruleFixtures';
import { date } from '@/shared/ui/primitives';

/**
 * M-19 PF Interest Rates & Parameters and M-18 Statutory Rates (SRS-6-11, SRS-6-47, BR-CPF-033):
 * what each role sees, the Rule Administrator's new rate and declaration, the screen's own checks,
 * and the two registers on the rule engine.
 */

afterEach(() => vi.unstubAllGlobals());

const RATE: InterestRate = {
  id: 11,
  finYear: '2026-27',
  fundType: 'CPF',
  rateSource: 'FCI_TRUST',
  memberPopulation: 'ACTIVE_AND_DEPUTED',
  ratePercent: 8.25,
  appliesFrom: '2026-04-01',
  appliesTo: null,
  applicabilityNote: 'up to the retirement month',
  bonusInterestPct: 2.5,
  declared: true,
  status: 'ACTIVE',
  approvalId: 4,
};

const UNDECLARED: InterestRate = {
  ...RATE,
  id: 12,
  rateSource: 'RPFC',
  memberPopulation: 'INACTIVE',
  ratePercent: 8,
  applicabilityNote: null,
  bonusInterestPct: null,
  declared: false,
};

const OLD: InterestRate = {
  ...RATE,
  id: 10,
  finYear: '2025-26',
  appliesFrom: '2025-04-01',
  appliesTo: '2025-09-30',
  status: 'SUPERSEDED',
};

const PENDING: PendingRateChange = {
  approvalId: 90,
  kind: 'RECORD',
  rateId: null,
  finYear: '2027-28',
  fundType: 'CPF',
  rateSource: 'FCI_TRUST',
  ratePercent: 8.4,
  appliesFrom: '2027-04-01',
  appliesTo: null,
  bonusInterestPct: null,
  declared: true,
  proposedBy: 'su.hq@fci-demo.test',
  proposedAt: '2026-10-22T05:00:00Z',
};

const RATES: Rates = { rates: [RATE, UNDECLARED, OLD], pending: [PENDING] };

const CPF_RULES = [
  rule({ key: 'CPF.EPS.RATE', name: 'EPS share', owningModule: 'M-18', inForce: null, versions: 0 }),
  rule({ key: 'CPF.RATE.EMPLOYEE', name: 'Employee rate', owningModule: 'M-18', inForce: null, versions: 0 }),
  rule({
    key: 'CPF.VPF.MAX',
    name: 'VPF maximum',
    owningModule: 'M-19',
    type: 'CEILING',
    inForce: null,
    versions: 0,
  }),
  rule({
    key: 'CPF.INTEREST.EXMEMBER_YEARS',
    name: 'Ex-member stop',
    owningModule: 'M-19',
    type: 'CEILING',
    orderRequired: false,
    inForce: version({ value: 3, versionNo: 1, effectiveFrom: '1900-01-01' }),
    versions: 1,
  }),
  rule({ key: 'DA.IDA.RATE', owningModule: null }),
];

const CATALOGUE = {
  asOf: '2026-10-23',
  summary: {
    rules: 5,
    byType: { RATE: 3, CEILING: 2 },
    pendingRuleChanges: 0,
    pendingOtherChanges: 0,
    notConfigured: 3,
  },
  rules: CPF_RULES,
};

const RULE_ADMIN = me(['M-19:VIEW', 'M-19:CREATE', 'M-19:EDIT', 'M-08:VIEW', 'M-08:CREATE', 'M-08:EDIT'], {
  role: 'RULE_ADMINISTRATOR',
  location: { code: 'HQ001', label: 'HQ' },
});

function routes(over: Record<string, () => unknown> = {}) {
  return backend({
    'GET /v1/cpf/interest-rates': () => RATES,
    'GET /v1/rules/pending': () => paged([]),
    'GET /v1/rules': () => CATALOGUE,
    ...over,
  });
}

async function openNewRate() {
  await userEvent.click(await screen.findByRole('button', { name: t('action.newRate') }));
  return screen.findByRole('heading', { name: t('m19.newRate') });
}

describe('M-19 PF interest rates', () => {
  it('SRS-6-11: each rate shows its source, members, period, bonus, declaration and status; changes awaiting approval are listed', async () => {
    routes();
    wrap(<InterestRates />, RULE_ADMIN);
    await screen.findAllByText('2026-27');
    const row = document.querySelector('tr[data-reference="2026-27|CPF|FCI_TRUST"]') as HTMLElement;
    expect(within(row).getByText(t('m19.source.FCI_TRUST'))).toBeTruthy();
    expect(within(row).getByText(t('m19.population.ACTIVE_AND_DEPUTED'))).toBeTruthy();
    expect(within(row).getByText('2.5 %')).toBeTruthy();
    expect(within(row).getByText('up to the retirement month')).toBeTruthy();
    expect(within(row).getByText(t('status.ACTIVE'))).toBeTruthy();
    const old = document.querySelector('tr[data-reference="2025-26|CPF|FCI_TRUST"]') as HTMLElement;
    expect(within(old).getByText(t('status.SUPERSEDED'))).toBeTruthy();
    const undeclared = document.querySelector('tr[data-reference="2026-27|CPF|RPFC"]') as HTMLElement;
    expect(within(undeclared).getByText(t('m19.undeclared'))).toBeTruthy();
    expect(within(undeclared).getByText(t('m19.population.INACTIVE'))).toBeTruthy();
    const pending = screen.getByRole('list', { name: t('m19.pendingTitle') });
    expect(within(pending).getByText(t('m19.pending.RECORD'))).toBeTruthy();
    expect(pending.textContent).toContain('2027-28');
    // Only the undeclared rate offers Declare.
    expect(screen.getAllByRole('button', { name: /^Declare the rate/ })).toHaveLength(1);
  });

  it('SRS-6-11: the Rule Administrator records a new rate for the Rule Approver; the body is trimmed', async () => {
    const calls = routes({ 'POST /v1/cpf/interest-rates': () => ({ status: 202, body: PENDING }) });
    wrap(<InterestRates />, RULE_ADMIN);
    await openNewRate();
    await userEvent.clear(screen.getByLabelText(t('m19.finYear')));
    await userEvent.type(screen.getByLabelText(t('m19.finYear')), '2027-28');
    expect((screen.getByLabelText(t('mst.from')) as HTMLInputElement).value).toBe('2027-04-01');
    await userEvent.type(screen.getByLabelText(t('m19.ratePercent')), ' 8.40 ');
    await userEvent.type(screen.getByLabelText(t('m19.bonus')), '2.50');
    await userEvent.type(screen.getByLabelText(t('m19.note')), ' full year ');
    await userEvent.type(screen.getByLabelText(t('common.reason')), ' Board resolution 12 ');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await waitFor(() => expect(sent(calls, 'POST', '/v1/cpf/interest-rates')).toHaveLength(1));
    expect(sent(calls, 'POST', '/v1/cpf/interest-rates')[0]).toMatchObject({
      finYear: '2027-28',
      fundType: 'CPF',
      rateSource: 'FCI_TRUST',
      ratePercent: '8.40',
      appliesFrom: '2027-04-01',
      appliesTo: null,
      applicabilityNote: 'full year',
      bonusInterestPct: '2.50',
      declared: true,
      reason: 'Board resolution 12',
    });
    await waitFor(() => expect(screen.queryByRole('heading', { name: t('m19.newRate') })).toBeNull());
  });

  it('BR-CPF-033: the form names what is wrong and keeps Submit disabled until it is right', async () => {
    routes();
    wrap(<InterestRates />, RULE_ADMIN);
    await openNewRate();
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    const problems = () => screen.getByRole('list', { name: t('m19.problems') });
    expect(within(problems()).getByText(t('error.CPF-422-RATE'))).toBeTruthy();
    expect(within(problems()).getByText(t('m19.reasonRequired'))).toBeTruthy();
    expect((submit as HTMLButtonElement).disabled).toBe(true);
    await userEvent.type(screen.getByLabelText(t('m19.ratePercent')), '9.505');
    expect(within(problems()).getByText(t('error.CPF-422-RATE'))).toBeTruthy();
    await userEvent.clear(screen.getByLabelText(t('m19.ratePercent')));
    await userEvent.type(screen.getByLabelText(t('m19.ratePercent')), '9.5');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Board');
    expect(screen.queryByRole('list', { name: t('m19.problems') })).toBeNull();
    expect((submit as HTMLButtonElement).disabled).toBe(false);
    // A year in the wrong form, and a date outside the year.
    await userEvent.clear(screen.getByLabelText(t('m19.finYear')));
    await userEvent.type(screen.getByLabelText(t('m19.finYear')), '2026-28');
    expect(within(problems()).getByText(t('error.CPF-422-FIN-YEAR'))).toBeTruthy();
    await userEvent.clear(screen.getByLabelText(t('m19.finYear')));
    await userEvent.type(screen.getByLabelText(t('m19.finYear')), '2026-27');
    await userEvent.type(screen.getByLabelText(t('mst.to')), '2027-04-01');
    expect(within(problems()).getByText(t('error.CPF-422-PERIOD'))).toBeTruthy();
  });

  it('SRS-6-11: the EPFO rate takes no bonus interest: switching to it clears and hides the field', async () => {
    const calls = routes({ 'POST /v1/cpf/interest-rates': () => ({ status: 202, body: PENDING }) });
    wrap(<InterestRates />, RULE_ADMIN);
    await openNewRate();
    await userEvent.type(screen.getByLabelText(t('m19.bonus')), '2.5');
    await userEvent.selectOptions(screen.getByLabelText(t('m19.rateSource')), 'RPFC');
    expect(screen.queryByLabelText(t('m19.bonus'))).toBeNull();
    expect(screen.getAllByText(t('m19.population.INACTIVE')).length).toBeGreaterThan(0);
    await userEvent.selectOptions(screen.getByLabelText(t('m19.fund')), 'VPF');
    await userEvent.type(screen.getByLabelText(t('m19.ratePercent')), '8.65');
    await userEvent.click(screen.getByLabelText(t('m19.declared')));
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'EPFO');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await waitFor(() => expect(sent(calls, 'POST', '/v1/cpf/interest-rates')).toHaveLength(1));
    expect(sent(calls, 'POST', '/v1/cpf/interest-rates')[0]).toMatchObject({
      rateSource: 'RPFC',
      fundType: 'VPF',
      bonusInterestPct: null,
      applicabilityNote: null,
      declared: false,
    });
  });

  it('SRS-6-11: a recorded rate is declared at the declared rate; a refusal is shown and the form stays', async () => {
    let answer: unknown = problem(409, 'CPF-409-DECLARED');
    const calls = routes({ 'POST /v1/cpf/interest-rates/12/declare': () => answer });
    wrap(<InterestRates />, RULE_ADMIN);
    await userEvent.click(
      await screen.findByRole('button', { name: t('m19.declareOf', ['2026-27|CPF|RPFC']) }),
    );
    const rate = screen.getByLabelText(t('m19.declaredRate')) as HTMLInputElement;
    expect(rate.value).toBe('8');
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    expect((submit as HTMLButtonElement).disabled).toBe(true);
    await userEvent.clear(rate);
    await userEvent.type(rate, '8.65');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'EPFO notified');
    await userEvent.click(submit);
    expect(await screen.findByText(t('error.CPF-409-DECLARED'))).toBeTruthy();
    answer = { status: 202, body: { ...PENDING, kind: 'DECLARE', rateId: 12 } };
    await userEvent.click(submit);
    await waitFor(() =>
      expect(screen.queryByRole('heading', { name: t('m19.declareOf', ['2026-27|CPF|RPFC']) })).toBeNull(),
    );
    expect(sent(calls, 'POST', '/declare').at(-1)).toEqual({ ratePercent: '8.65', reason: 'EPFO notified' });
  });

  it('SRS-G-48: a refused rate is shown and the form stays; a cancelled OTP is silent; Cancel closes both forms', async () => {
    let answer: unknown = problem(409, 'CPF-409-DECLARED');
    const calls = routes({ 'POST /v1/cpf/interest-rates': () => answer });
    wrap(<InterestRates />, RULE_ADMIN);
    await openNewRate();
    await userEvent.clear(screen.getByLabelText(t('m19.finYear')));
    await userEvent.type(screen.getByLabelText(t('m19.finYear')), '2027-28');
    // The first day may be moved inside the year (a rate from mid-year).
    const from = screen.getByLabelText(t('mst.from')) as HTMLInputElement;
    await userEvent.clear(from);
    await userEvent.type(from, '2027-10-01');
    await userEvent.type(screen.getByLabelText(t('m19.ratePercent')), '8.40');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Board resolution 13');
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    await userEvent.click(submit);
    expect(await screen.findByText(t('error.CPF-409-DECLARED'))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/cpf/interest-rates')[0]).toMatchObject({ appliesFrom: '2027-10-01' });
    // A cancelled OTP leaves the form open without an error.
    answer = problem(428, 'AUTH-428-OTP', ['7']);
    await userEvent.click(submit);
    const otp = await screen.findByRole('dialog', { name: t('otp.title') });
    await userEvent.click(within(otp).getByRole('button', { name: t('common.cancel') }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: t('otp.title') })).toBeNull());
    expect(screen.getByRole('heading', { name: t('m19.newRate') })).toBeTruthy();
    expect(screen.queryByText(t('error.CPF-409-DECLARED'))).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('heading', { name: t('m19.newRate') })).toBeNull();
    // The declaration form closes the same way.
    await userEvent.click(screen.getByRole('button', { name: t('m19.declareOf', ['2026-27|CPF|RPFC']) }));
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('heading', { name: t('m19.declareOf', ['2026-27|CPF|RPFC']) })).toBeNull();
  });

  it('SRS-6-11: a change already pending on a series offers no second declaration', async () => {
    routes({
      'GET /v1/cpf/interest-rates': () => ({
        rates: [UNDECLARED],
        pending: [{ ...PENDING, kind: 'DECLARE', rateId: 12, finYear: '2026-27', rateSource: 'RPFC' }],
      }),
    });
    wrap(<InterestRates />, RULE_ADMIN);
    expect(await screen.findByText(t('m19.pending.DECLARE'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: /^Declare the rate/ })).toBeNull();
  });

  it('SRS-22-21: the CPF Division reads the rates only; outside HQ the Rule Administrator is told why', async () => {
    routes();
    wrap(<InterestRates />, me(['M-19:VIEW'], { role: 'CPF_DIVISION' }));
    expect(await screen.findByText(t('m19.source.RPFC'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: t('action.newRate') })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Declare the rate/ })).toBeNull();
    // Without the rule catalogue the parameters are not loaded.
    expect(screen.getByText(t('m19.parametersNoAccess'))).toBeTruthy();
  });

  it('SRS-6-11: away from Headquarters the actions stay visible but disabled with the reason', async () => {
    routes();
    wrap(<InterestRates />, { ...RULE_ADMIN, atHeadquarters: false, hqOnlyScreens: ['M-19'] });
    const create = await screen.findByRole('button', { name: t('action.newRate') });
    expect((create as HTMLButtonElement).disabled).toBe(true);
    expect(create.getAttribute('title')).toBe(t('error.AUTHZ-403-HQ-ONLY'));
    const declare = await screen.findByRole('button', { name: t('m19.declareOf', ['2026-27|CPF|RPFC']) });
    expect((declare as HTMLButtonElement).disabled).toBe(true);
    expect(declare.getAttribute('title')).toBe(t('error.AUTHZ-403-HQ-ONLY'));
  });

  it('SRS-6-11: no rate recorded yet is said so; a list that cannot load is shown, never empty', async () => {
    routes({ 'GET /v1/cpf/interest-rates': () => ({ rates: [], pending: [] }) });
    const view = wrap(<InterestRates />, RULE_ADMIN);
    expect(await screen.findByText(t('m19.noRates'))).toBeTruthy();
    view.unmount();
    vi.unstubAllGlobals();
    routes({ 'GET /v1/cpf/interest-rates': () => problem(503, 'COM-503-UNAVAILABLE') });
    wrap(<InterestRates />, RULE_ADMIN);
    expect(await screen.findByText(t('error.COM-503-UNAVAILABLE'))).toBeTruthy();
  });

  it('SRS M-19: the parameters register lists only the CPF parameters M-19 owns', async () => {
    routes();
    wrap(<InterestRates />, RULE_ADMIN);
    expect(await screen.findByText('CPF.VPF.MAX')).toBeTruthy();
    expect(screen.getByText('CPF.INTEREST.EXMEMBER_YEARS')).toBeTruthy();
    expect(screen.queryByText('CPF.EPS.RATE')).toBeNull();
    expect(screen.queryByText('DA.IDA.RATE')).toBeNull();
  });
});

describe('M-18 statutory rates', () => {
  it('SRS-6-47: the register lists only the M-18 rates, counts its own tiles and leaves M-08 its pending view', async () => {
    routes();
    wrap(<StatutoryRates />, RULE_ADMIN);
    expect(await screen.findByText('CPF.EPS.RATE')).toBeTruthy();
    expect(screen.getByText('CPF.RATE.EMPLOYEE')).toBeTruthy();
    expect(screen.queryByText('CPF.VPF.MAX')).toBeNull();
    expect(screen.queryByText('DA.IDA.RATE')).toBeNull();
    expect(screen.getAllByText(t('mst.notConfigured'), { selector: 'span' })).toHaveLength(2);
    expect(screen.queryByText(t('m08.tile.pendingOther'))).toBeNull();
    expect(screen.queryByRole('heading', { name: t('m08.pendingTitle') })).toBeNull();
    expect(screen.getByText(t('m18.hint'))).toBeTruthy();
    // SRS l.2323: Simulate Impact is on every row, disabled with its reason when nothing is proposed.
    const simulate = screen.getByRole('button', { name: t('m08.sim.of', ['CPF.EPS.RATE']) });
    expect(simulate).toBeDisabled();
    expect(simulate.getAttribute('title')).toBe(t('m08.sim.nothingProposed'));
    // SRS l.2322: the governing instrument column (none in force: a dash).
    expect(
      screen
        .getAllByRole('columnheader')
        .some((h) => (h.textContent ?? '').includes(t('m08.governingOrder'))),
    ).toBe(true);
  });

  it('SRS-6-47 (US-GAP-172): a version awaiting approval is simulated from its M-18 row; the in-force version is kept beside it', async () => {
    const pendingEps = rule({
      key: 'CPF.EPS.RATE',
      name: 'EPS share',
      owningModule: 'M-18',
      inForce: version({ value: 8.33, versionNo: 1, effectiveFrom: '2026-04-01' }),
      pending: version({
        id: null,
        status: 'PENDING_APPROVAL',
        versionNo: 2,
        value: 8.5,
        effectiveFrom: '2027-04-01',
      }),
      versions: 1,
    });
    routes({
      'GET /v1/rules': () => ({ ...CATALOGUE, rules: [pendingEps] }),
      'GET /v1/rules/CPF.EPS.RATE/simulations': () => paged([]),
      'GET /v1/rules/simulation-offices': () => [],
    });
    wrap(<StatutoryRates />, RULE_ADMIN);
    await screen.findByText('CPF.EPS.RATE');
    await userEvent.click(screen.getByRole('button', { name: t('m08.sim.of', ['CPF.EPS.RATE']) }));
    expect(await screen.findByText(t('m08.sim.none'))).toBeTruthy();
    expect(screen.getByRole('button', { name: t('m08.sim.run') })).toBeTruthy();
    // SRS l.2320 / l.2323: while the proposal is simulated, the row keeps the version in force
    // (v1 from its own date, 8.33) and shows the pending v2 beside it, never in its place.
    const row = screen.getByText('CPF.EPS.RATE').closest('tr') as HTMLElement;
    expect(within(row).getByText('v1')).toBeTruthy();
    expect(within(row).queryByText('v2')).toBeNull();
    expect(within(row).getByText(date('2026-04-01'))).toBeTruthy();
    expect(within(row).getByText(/8\.33/)).toBeTruthy();
    expect(within(row).queryByText(/8\.5(?!\d)/)).toBeNull();
    expect(within(row).getByText(t('m08.pendingVersion', [2, date('2027-04-01')]))).toBeTruthy();
  });
});
