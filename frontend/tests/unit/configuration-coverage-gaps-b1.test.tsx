import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { RuleCatalogue } from '@/modules/configuration';
import type { Me } from '@/shared/session';
import { backend, me, paged, sent, t, wrap } from './support/harness';
import { TODAY, rule, version } from './support/ruleFixtures';

/**
 * M-08 Rule Catalogue gaps (SRS-19-4, SRS-19-6, SRS-19-12, SRS-19-21; M08-08; SRS M-19, SRS-6-47): the register
 * mode embedded on M-18 / M-19 (its own rules only, row actions, disabled reasons), the drawn Simulate buttons, the
 * drawn Administrator outside Headquarters, the checker's tick into the inbox and a read-through row's View.
 */

afterEach(() => vi.unstubAllGlobals());

const ADMIN = ['M-08:VIEW', 'M-08:CREATE', 'M-08:EDIT', 'M-25:VIEW'];
const PENDING_V = version({
  id: null,
  versionNo: 4,
  status: 'PENDING_APPROVAL',
  effectiveFrom: '2027-01-01',
});

const REGISTER_RULES = [
  rule({
    key: 'CPF.INT.RATE',
    name: 'CPF interest rate',
    owningModule: 'M-19',
    inForce: version({ blocked: true }),
    pending: PENDING_V,
  }),
  rule({
    key: 'CPF.ADV.CAP',
    type: 'CEILING',
    name: 'CPF advance cap',
    owningModule: 'M-19',
    inForce: null,
    versions: 0,
    orderRequired: false,
  }),
  rule({ key: 'DA.IDA.RATE', owningModule: null }),
];

function catalogue(rules = REGISTER_RULES) {
  return {
    asOf: TODAY,
    summary: {
      rules: rules.length,
      byType: { RATE: 1 },
      pendingRuleChanges: 1,
      pendingOtherChanges: 0,
      notConfigured: 0,
    },
    rules,
  };
}

function routes(over: Record<string, () => unknown> = {}) {
  return backend({
    'GET /v1/rules/pending': () => paged([]),
    'GET /v1/rules/vocabulary': () => ({ operators: [], functions: {}, variables: [] }),
    'GET /v1/rules/read-through': () => [],
    'GET /v1/rules/CPF.INT.RATE/versions': () => paged([]),
    'GET /v1/rules/CPF.INT.RATE/simulations': () => paged([]),
    'GET /v1/rules/DA.IDA.RATE/simulations': () => paged([]),
    'GET /v1/rules/simulation-offices': () => [],
    'GET /v1/rules': () => catalogue(),
    'GET /v1/masters/circulars/current': () => [],
    ...over,
  });
}

const registerRow = (key: string) =>
  within(screen.getByText(t('m08.catalogue')).closest('.card') as HTMLElement)
    .getByText(key)
    .closest('tr') as HTMLElement;

describe('M-08 register mode (M-18 / M-19)', () => {
  it('SRS-6-47: the register lists only its own rules with their tiles, the blocked pill and the maintainer hint', async () => {
    const calls = routes();
    wrap(<RuleCatalogue register="M-19" />, ADMIN);
    expect(await screen.findByText('CPF.INT.RATE')).toBeTruthy();
    expect(screen.queryByText('DA.IDA.RATE')).toBeNull();
    expect(screen.getByText(t('m08.hint'))).toBeTruthy();
    const rulesTile = screen.getByText(t('m08.tile.rules')).closest('.tile') as HTMLElement;
    expect(within(rulesTile).getByText('2')).toBeTruthy();
    expect(screen.getByText(t('m08.tile.rulesSub', [2]))).toBeTruthy();
    const pendingTile = screen.getByText(t('m08.tile.pending')).closest('.tile') as HTMLElement;
    expect(within(pendingTile).getByText('1')).toBeTruthy();
    // The other screens' pending changes and the reports belong to M-08 only.
    expect(screen.queryByText(t('m08.tile.pendingOther'))).toBeNull();
    expect(screen.queryByText(t('m08w.pendingList'))).toBeNull();
    const notConfigured = screen.getByText(t('m08.tile.notConfigured')).closest('.tile') as HTMLElement;
    expect(within(notConfigured).getByText('1')).toBeTruthy();
    const row = registerRow('CPF.INT.RATE');
    expect(within(row).getByText(t('m08.blocked'))).toBeTruthy();
    expect(within(row).getByText('v3')).toBeTruthy();
    expect(within(row).getByText('HQ/DA/2026/3')).toBeTruthy();
    const empty = registerRow('CPF.ADV.CAP');
    expect(within(empty).getAllByText('—').length).toBeGreaterThanOrEqual(3);
    // The register does not read the domains entered on M-14 / M-15 / M-20.
    expect(calls.some((c) => c.url.includes('/read-through'))).toBe(false);
  });

  it('SRS-19-3: Versions toggles its panel; Simulate is off with its reason until a version is proposed', async () => {
    const calls = routes();
    wrap(<RuleCatalogue register="M-19" />, ADMIN);
    await screen.findByText('CPF.INT.RATE');
    const versions = within(registerRow('CPF.INT.RATE')).getByRole('button', {
      name: t('m08.versionsOf', ['CPF.INT.RATE']),
    });
    expect(versions).toHaveAttribute('aria-pressed', 'false');
    await userEvent.click(versions);
    expect(versions).toHaveAttribute('aria-pressed', 'true');
    await waitFor(() => expect(calls.some((c) => c.url.includes('/CPF.INT.RATE/versions'))).toBe(true));
    await userEvent.click(versions);
    expect(versions).toHaveAttribute('aria-pressed', 'false');

    const offSim = within(registerRow('CPF.ADV.CAP')).getByRole('button', {
      name: t('m08.sim.of', ['CPF.ADV.CAP']),
    });
    expect(offSim).toBeDisabled();
    expect(offSim).toHaveAttribute('title', t('m08.sim.nothingProposed'));
    const sim = within(registerRow('CPF.INT.RATE')).getByRole('button', {
      name: t('m08.sim.of', ['CPF.INT.RATE']),
    });
    expect(sim).toBeEnabled();
    expect(sim).not.toHaveAttribute('title');
    await userEvent.click(sim);
    expect(sim).toHaveAttribute('aria-pressed', 'true');
    expect(await screen.findByText(t('m08.sim.none'))).toBeTruthy();
    await userEvent.click(sim);
    expect(sim).toHaveAttribute('aria-pressed', 'false');
  });

  it('SRS-19-12 / SRS-19-6: a first value and an end are started from the register row; nothing is sent yet', async () => {
    const calls = routes();
    wrap(<RuleCatalogue register="M-19" />, ADMIN);
    await screen.findByText('CPF.ADV.CAP');
    const first = within(registerRow('CPF.ADV.CAP')).getByRole('button', {
      name: t('m08.newVersionOf', ['CPF.ADV.CAP']),
    });
    expect(first).toHaveTextContent(t('action.newRuleVersion'));
    expect(first).not.toHaveAttribute('title');
    await userEvent.click(first);
    expect(await screen.findByLabelText(t('mst.from'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));

    const da = rule({ key: 'CPF.MIN.RATE', owningModule: 'M-19' });
    routes({ 'GET /v1/rules': () => catalogue([da]) });
    const view = wrap(<RuleCatalogue register="M-19" />, ADMIN);
    const rows = await view.findAllByText('CPF.MIN.RATE');
    const row = rows[0]?.closest('tr') as HTMLElement;
    expect(
      within(row).getByRole('button', { name: t('m08.supersedeOf', ['CPF.MIN.RATE']) }),
    ).toHaveTextContent(t('action.supersede'));
    await userEvent.click(within(row).getByRole('button', { name: t('m08.endOf', ['CPF.MIN.RATE']) }));
    expect(await screen.findByLabelText(t('m08.endOn'))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/rules')).toHaveLength(0);
  });

  it('SRS-19-4: outside Headquarters the register row actions are disabled with the HQ reason', async () => {
    routes({ 'GET /v1/rules': () => catalogue([rule({ key: 'CPF.MIN.RATE', owningModule: 'M-19' })]) });
    wrap(<RuleCatalogue register="M-19" />, me(ADMIN, { atHeadquarters: false, hqOnlyScreens: ['M-08'] }));
    await screen.findByText('CPF.MIN.RATE');
    const row = registerRow('CPF.MIN.RATE');
    const supersede = within(row).getByRole('button', { name: t('m08.supersedeOf', ['CPF.MIN.RATE']) });
    expect(supersede).toBeDisabled();
    expect(supersede).toHaveAttribute('title', t('error.AUTHZ-403-HQ-ONLY'));
    const end = within(row).getByRole('button', { name: t('m08.endOf', ['CPF.MIN.RATE']) });
    expect(end).toBeDisabled();
    expect(end).toHaveAttribute('title', t('error.AUTHZ-403-HQ-ONLY'));
  });

  it('SRS-19-14: a reader of a register with no rule of its own sees the read-only note and the empty list', async () => {
    routes({ 'GET /v1/rules': () => catalogue([rule({ owningModule: 'M-19' })]) });
    wrap(<RuleCatalogue register="M-18" />, ['M-08:VIEW']);
    expect(await screen.findByText(t('m08.noRules'))).toBeTruthy();
    expect(screen.getByText(t('m08.readOnly'))).toBeTruthy();
    const notConfigured = screen.getByText(t('m08.tile.notConfigured')).closest('.tile') as HTMLElement;
    expect(within(notConfigured).getByText('0')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /CPF/ })).toBeNull();
  });
});

describe('M-08 drawn actions', () => {
  const ONE_PENDING = catalogue([rule({ pending: PENDING_V })]);

  it('BR-RULE-006: Simulate Impact over the screen and on the catalogue open the pending version simulation', async () => {
    routes({ 'GET /v1/rules': () => ONE_PENDING });
    wrap(<RuleCatalogue />, ADMIN);
    await screen.findAllByText('DA.IDA.RATE');
    await userEvent.click(screen.getByRole('button', { name: t('m08w.simulateImpact') }));
    expect(await screen.findByText(t('m08.sim.none'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    await waitFor(() => expect(screen.queryByText(t('m08.sim.none'))).toBeNull());
    await userEvent.click(screen.getByRole('button', { name: t('m08w.simulate') }));
    expect(await screen.findByText(t('m08.sim.none'))).toBeTruthy();
  });

  it('OD-135: the drawn Administrator outside Headquarters is told the HQ reason on New Rule Version', async () => {
    routes();
    wrap(
      <RuleCatalogue />,
      me(['M-08:VIEW'], { roles: ['SYSTEM_ADMINISTRATOR'], atHeadquarters: false, hqOnlyScreens: ['M-08'] }),
    );
    const drawn = await screen.findByRole('button', { name: t('m08w.newVersion') });
    expect(drawn).toHaveAttribute('data-guard', t('error.AUTHZ-403-HQ-ONLY'));
  });

  it('OD-135: a viewer without the drawn role outside Headquarters has New Rule Version locked with the HQ reason', async () => {
    routes();
    wrap(<RuleCatalogue />, me(['M-08:VIEW'], { atHeadquarters: false, hqOnlyScreens: ['M-08'] }));
    const locked = await screen.findByRole('button', { name: t('m08w.newVersion') });
    expect(locked).toBeDisabled();
    expect(locked).toHaveAttribute('data-why', t('error.AUTHZ-403-HQ-ONLY'));
  });

  it('M08-08: the approver tick on a pending row opens the Approvals Inbox; no decision is posted here', async () => {
    const assign = vi.fn();
    const calls = routes({ 'GET /v1/rules': () => ONE_PENDING });
    vi.stubGlobal('location', { ...window.location, assign });
    wrap(<RuleCatalogue />, ['M-08:VIEW', 'M-08:APPROVE']);
    const tick = await screen.findByTitle(t('wf.approveTitle'));
    await userEvent.click(tick);
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('W-01'));
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('SRS-19-21: a read-through row without a record count reads 0 and its View offers no action', async () => {
    routes({
      'GET /v1/rules': () => catalogue([]),
      'GET /v1/rules/read-through': () => [
        {
          key: 'PENALTY.MINNET.FLOOR',
          type: 'FLOOR',
          screen: 'M-14',
          records: null,
          from: null,
          pending: false,
        },
      ],
    });
    wrap(<RuleCatalogue />, ADMIN);
    const row = (await screen.findAllByText('PENALTY.MINNET.FLOOR'))[0]?.closest('tr') as HTMLElement;
    expect(row).toHaveTextContent(t('m08w.enteredOn', ['M-14', 0]));
    await userEvent.click(
      within(row).getByRole('button', { name: t('m08w.viewOf', ['PENALTY.MINNET.FLOOR']) }),
    );
    const dialog = await screen.findByRole('dialog', { name: t('wf.viewHead', [t('screen.M-08')]) });
    expect(within(dialog).queryByRole('button', { name: t('m08.versions') })).toBeNull();
    expect(
      within(dialog).queryByRole('button', { name: t('m08.endOf', ['PENALTY.MINNET.FLOOR']) }),
    ).toBeNull();
  });

  it('OD-135: a profile without roles is neither the drawn Administrator nor the drawn checker', async () => {
    routes();
    const profile = { ...me(['M-08:VIEW']), roles: undefined } as unknown as Me;
    wrap(<RuleCatalogue />, profile);
    const locked = await screen.findByRole('button', { name: t('m08w.newVersion') });
    expect(locked).toBeDisabled();
    expect(screen.getByRole('button', { name: t('action.supersede') })).toBeDisabled();
  });

  it('SRS-19-12: a rule picked for a new version that is no longer proposable when the catalogue is read again opens nothing', async () => {
    const calls = routes();
    const { client } = wrap(<RuleCatalogue />, ADMIN);
    await screen.findAllByText('CPF.ADV.CAP');
    await userEvent.click(screen.getByRole('button', { name: t('m08w.newVersion') }));
    await userEvent.selectOptions(
      screen.getByLabelText(t('m08.type'), { selector: '#m08-pick-type' }),
      'CEILING',
    );
    await userEvent.selectOptions(screen.getByLabelText(t('m08.ruleKey')), 'CPF.ADV.CAP');
    // Meanwhile another Administrator proposed its first value: the rule now waits for approval.
    act(() => {
      client.setQueryData(
        ['rules', 'catalogue'],
        catalogue(REGISTER_RULES.map((r) => (r.key === 'CPF.ADV.CAP' ? { ...r, pending: PENDING_V } : r))),
      );
    });
    await userEvent.click(screen.getByRole('button', { name: t('m08w.pick.go') }));
    expect(screen.queryByLabelText(t('mst.from'))).toBeNull();
    expect(screen.queryByRole('button', { name: t('m08w.pick.go') })).toBeNull();
    expect(sent(calls, 'POST', '/v1/rules')).toHaveLength(0);
  });

  it('M08-08: a Payroll Checker without the approve right sees the tick disabled with the approver reason', async () => {
    const calls = routes({ 'GET /v1/rules': () => ONE_PENDING });
    wrap(<RuleCatalogue />, me(['M-08:VIEW'], { role: 'PAYROLL_CHECKER', roles: ['PAYROLL_CHECKER'] }));
    const row = (await screen.findAllByText('DA.IDA.RATE'))[0]?.closest('tr') as HTMLElement;
    const tick = within(row).getByText('✔');
    expect(tick).toBeDisabled();
    expect(tick).toHaveAttribute('data-why', t('m08w.why.approver'));
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });
});
