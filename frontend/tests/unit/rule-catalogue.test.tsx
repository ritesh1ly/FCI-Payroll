import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { RuleCatalogue } from '@/modules/configuration';
import { rulesApi } from '@/modules/configuration/api/rulesApi';
import { MAX_BANDS, canAddBand } from '@/modules/configuration/business/ruleRules';
import { date } from '@/shared/ui';
import { backend, me, problem, sent, t, wrap, paged } from './support/harness';
import { RULES, TODAY, rule, version } from './support/ruleFixtures';

/**
 * M-08 Rule / Parameter Umbrella Catalogue (SRS-19-1, SRS-19-4 … 6, SRS-19-11, SRS-19-12,
 * SRS-19-14, SRS-19-17, SRS-19-21): what is in force or not configured, the read-only roles, each
 * proposal shaped by its rule type, and the pending view across M-08 / M-14 / M-15.
 */

afterEach(() => vi.unstubAllGlobals());

const CATALOGUE = {
  asOf: TODAY,
  summary: {
    rules: 7,
    byType: { RATE: 2, CEILING: 1, SLAB: 1 },
    pendingRuleChanges: 2,
    pendingOtherChanges: 1,
    notConfigured: 1,
  },
  rules: RULES,
};

const PENDING = [
  {
    screen: 'M-08',
    entity: 'rule_version',
    key: 'TA.FORMULA',
    approvalId: 94,
    submittedBy: 'su.hq@fci-demo.test',
    submittedAt: '2026-10-21T05:00:00Z',
  },
  {
    screen: 'M-14',
    entity: 'net_pay_floor_rule',
    key: 'STAFF',
    approvalId: 96,
    submittedBy: 'su.hq@fci-demo.test',
    submittedAt: '2026-10-22T05:00:00Z',
  },
];

const VOCABULARY = {
  operators: ['*', '+', '>='],
  functions: { IF: 3, MIN: null, ROUND: 2 },
  variables: ['BASIC'],
};
const CIRCULARS = [{ id: 7, number: 'HQ/DA/2026/3', subject: 'DA revision', status: 'ACTIVE' }];

const accepted = (body: unknown) => () => ({ status: 202, body });

function routes(over: Record<string, () => unknown> = {}) {
  return backend({
    'GET /v1/rules/pending': () => paged(PENDING),
    'GET /v1/rules/vocabulary': () => VOCABULARY,
    'GET /v1/rules/INCENTIVE.SLAB/versions': () =>
      paged([
        version({
          ...RULES[2]?.inForce,
          approvedBy: 'rule.approver@fci-demo.test',
          supersedesVersionNo: 2,
        }),
        version({
          id: 5,
          versionNo: 2,
          effectiveFrom: '2026-04-01',
          effectiveTo: '2026-06-30',
          status: 'SUPERSEDED',
          value: 4,
          roundingRuleKey: 'COMPONENT.ROUNDING',
          circularNumber: null,
          approvalId: null,
          createdBy: 'seed.golive',
        }),
      ]),
    'GET /v1/rules': () => CATALOGUE,
    'GET /v1/masters/circulars/current': () => CIRCULARS,
    'POST /v1/rules/': accepted(version({ id: null, status: 'PENDING_APPROVAL' })),
    ...over,
  });
}

const ADMIN = ['M-08:VIEW', 'M-08:CREATE', 'M-08:EDIT', 'M-25:VIEW'];
const READER = ['M-08:VIEW'];

/** Chooses the Active circular once the list has loaded (SRS-19-17). */
async function chooseCircular() {
  await screen.findByRole('option', { name: 'HQ/DA/2026/3 · DA revision' });
  await userEvent.selectOptions(screen.getByLabelText(t('m08.circular')), '7');
}

/** Sets a date field (typing into a date input is not supported by the DOM under test). */
function setDate(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

/** A rule's row in the catalogue card (its key may also appear in the pending view). */
/** Cancels the OTP dialog a governed action opened: the action fails silently (SRS-G-48). */
async function cancelOtp() {
  const dialog = await screen.findByRole('dialog', { name: t('otp.title') });
  await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
  await waitFor(() => expect(screen.queryByRole('dialog', { name: t('otp.title') })).toBeNull());
}

/** The row's drawn View: versions, simulation and end date open from it (M08-06). */
async function viewRule(key: string): Promise<HTMLElement> {
  await userEvent.click(screen.getByRole('button', { name: t('m08w.viewOf', [key]) }));
  return screen.findByRole('dialog', { name: t('wf.viewHead', [t('screen.M-08')]) });
}

async function closeView(dialog: HTMLElement) {
  await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
}

/** The End action of the rule, from its View. */
async function endRule(key: string) {
  const dialog = await viewRule(key);
  await userEvent.click(within(dialog).getByRole('button', { name: t('m08.endOf', [key]) }));
}

/** Versions of the rule, from its View: pressing it again closes the card, as on the row before. */
async function versionsOf(key: string) {
  const dialog = await viewRule(key);
  await userEvent.click(within(dialog).getByRole('button', { name: t('m08.versions') }));
}

function rowOf(key: string): HTMLElement {
  const catalogue = screen.getByText(t('m08w.catalogue')).closest('.card') as HTMLElement;
  return within(catalogue).getByText(key).closest('tr') as HTMLElement;
}

// ---------------------------------------------------------------- the API

describe('M-08 API', () => {
  it('SRS-19-4: a proposal carries the OTP proof only when one was given, and the key is a single encoded segment', async () => {
    document.cookie = 'XSRF-TOKEN=abc; Path=/';
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({}), { status: 202 }));
    vi.stubGlobal('fetch', fetchMock);
    const body = { endOn: '2026-12-31', reason: 'Withdrawn' };
    await rulesApi.end('DA.IDA.RATE', body, { challengeId: 7, code: '123456' });
    await rulesApi.end('A/../B', body);
    const [firstUrl, first] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    const [secondUrl, second] = fetchMock.mock.calls[1] as unknown as [string, RequestInit];
    expect(firstUrl).toBe('/nginx/fci-payroll/v1/rules/DA.IDA.RATE/end');
    expect(JSON.parse(first.body as string)).toEqual({ ...body, otp: { challengeId: 7, code: '123456' } });
    // Path tampering: the key cannot step out of its segment.
    expect(secondUrl).toBe('/nginx/fci-payroll/v1/rules/A%2F..%2FB/end');
    expect(JSON.parse(second.body as string)).toEqual(body);
  });
});

// ---------------------------------------------------------------- the screen

describe('M-08 Rule / Parameter Umbrella Catalogue', () => {
  it('SRS-19-4: a Rule Administrator outside Headquarters sees proposals and ends disabled with the reason', async () => {
    routes();
    wrap(<RuleCatalogue />, me(ADMIN, { atHeadquarters: false, hqOnlyScreens: ['M-08'] }));
    expect(await screen.findByText('DA.IDA.RATE')).toBeTruthy();
    const da = rowOf('DA.IDA.RATE');
    const edit = within(da).getByRole('button', { name: t('m08.supersedeOf', ['DA.IDA.RATE']) });
    expect(edit).toBeDisabled();
    expect(edit).toHaveAttribute('data-why', t('error.AUTHZ-403-HQ-ONLY'));
    const dialog = await viewRule('DA.IDA.RATE');
    const end = within(dialog).getByRole('button', { name: t('m08.end') });
    expect(end).toBeDisabled();
    expect(end).toHaveAttribute('data-why', t('error.AUTHZ-403-HQ-ONLY'));
  });

  it('SRS-19-21: shows the tiles, each rule with its value in force, not configured, pending and read-only states', async () => {
    routes();
    wrap(<RuleCatalogue />, ADMIN);
    expect(await screen.findByText('DA.IDA.RATE')).toBeTruthy();
    expect(screen.getByText(t('m08w.tile.rules'))).toBeTruthy();
    expect(screen.getByText(t('m08.tile.rulesSub', [3]))).toBeTruthy();
    expect(screen.getByText(t('m08w.tile.missing'))).toBeTruthy();
    const da = rowOf('DA.IDA.RATE');
    expect(within(da).getByText('53')).toBeTruthy();
    expect(within(da).getByText(date('2026-07-01'))).toBeTruthy();
    expect(within(da).getByText('v3')).toBeTruthy();
    expect(within(da).getByText(t('m08.status.ACTIVE'))).toBeTruthy();
    expect(within(da).getByText(t('m08.orderRequired'))).toBeTruthy();
    expect(within(da).getByText(`STAFF · ${t('m08.allOffices')}`)).toBeTruthy();
    expect(within(rowOf('CPF.SANCTION.LIMIT')).getByText(t('mst.notConfigured'))).toBeTruthy();
    expect(within(rowOf('INCENTIVE.SLAB')).getByText(t('m08.bandCount', [2]))).toBeTruthy();
    expect(
      within(rowOf('INCENTIVE.SLAB')).getByText(`DEPT_LABOUR, DPS_LABOUR · ${t('m08.allOffices')}`),
    ).toBeTruthy();
    const ta = rowOf('TA.FORMULA');
    expect(within(ta).getByText('ROUND(BASIC * 0.06, 0)')).toBeTruthy();
    expect(within(ta).getByText(`STAFF · Zonal Office North`)).toBeTruthy();
    expect(within(ta).getByText(t('m08.pendingVersion', [4, date('2027-01-01')]))).toBeTruthy();
    expect(
      within(rowOf('LWF.348.APPLICABILITY')).getByText(t('m08.pendingEnd', [date('2026-12-31')])),
    ).toBeTruthy();
    const form16 = rowOf('FORM16.DEFINITION');
    expect(within(form16).getByText(t('m08.notMaintained'))).toBeTruthy();
    expect(within(form16).getByText(t('m08.blocked'))).toBeTruthy();
    // A definition holds no value of its own; it applies to every engine and office.
    expect(within(form16).getByText('—')).toBeTruthy();
    expect(within(form16).getByText(`${t('m08.allEngines')} · ${t('m08.allOffices')}`)).toBeTruthy();
    // An office scope whose name is not known shows its id.
    expect(within(rowOf('LWF.348.APPLICABILITY')).getByText('STAFF · 9')).toBeTruthy();
    expect(within(rowOf('OLD.RATE')).getByText(t('m08.inactive'))).toBeTruthy();
    // Actions: a first version, a supersede, an end; none for pending or read-only rules.
    expect(
      within(rowOf('CPF.SANCTION.LIMIT')).getByRole('button', {
        name: t('m08.newVersionOf', ['CPF.SANCTION.LIMIT']),
      }),
    ).toBeTruthy();
    const noVersionYet = await viewRule('CPF.SANCTION.LIMIT');
    expect(
      within(noVersionYet).queryByRole('button', { name: t('m08.endOf', ['CPF.SANCTION.LIMIT']) }),
    ).toBeNull();
    await closeView(noVersionYet);
    expect(within(da).getByRole('button', { name: t('m08.supersedeOf', ['DA.IDA.RATE']) })).toBeEnabled();
    const held = await viewRule('DA.IDA.RATE');
    expect(within(held).getByRole('button', { name: t('m08.endOf', ['DA.IDA.RATE']) })).toBeTruthy();
    await closeView(held);
    // SRS-G-25: not maintained here, so the drawn Edit stays, disabled.
    expect(within(ta).getByRole('button', { name: t('m08.supersedeOf', ['TA.FORMULA']) })).toBeDisabled();
    expect(
      within(form16).getByRole('button', { name: t('m08.supersedeOf', ['FORM16.DEFINITION']) }),
    ).toBeDisabled();
    // The guard-rails and the pending view across M-08 / M-14 / M-15.
    expect(screen.getByText(t('m08.guard.noDefault'))).toBeTruthy();
    const pending = (await screen.findByText(t('entity.net_pay_floor_rule'))).closest('table') as HTMLElement;
    expect(within(pending).getByText('M-14')).toBeTruthy();
    expect(within(pending).getByText('TA.FORMULA')).toBeTruthy();
    expect(screen.getByText(t('m08.hint'))).toBeTruthy();
  });

  it('SRS-19-14: the Payroll Checker and Finance read only: no proposal, the read-only note', async () => {
    routes({ 'GET /v1/rules/pending': () => paged([]) });
    wrap(<RuleCatalogue />, READER);
    await screen.findByText('DA.IDA.RATE');
    expect(screen.getByText(t('m08.readOnly'))).toBeTruthy();
    const edit = screen.getByRole('button', { name: t('m08.supersedeOf', ['DA.IDA.RATE']) });
    expect(edit).toBeDisabled();
    expect(edit).toHaveAttribute('data-why', t('m08w.why.ruleAdmin'));
    const dialog = await viewRule('DA.IDA.RATE');
    expect(within(dialog).queryByRole('button', { name: t('m08.endOf', ['DA.IDA.RATE']) })).toBeNull();
    await closeView(dialog);
    expect(await screen.findByText(t('m08.noPending'))).toBeTruthy();
  });

  it('SRS-19-21: the Rule Catalogue Summary and Pending Approval - Cross-Domain reports are offered on demand with M-08 EXPORT only', async () => {
    routes();
    const view = wrap(<RuleCatalogue />, [...READER, 'M-08:EXPORT']);
    await screen.findByText('DA.IDA.RATE');
    expect(screen.getByRole('button', { name: t('m08.report.catalogue') })).toBeTruthy();
    expect(screen.getByRole('button', { name: t('m08.report.pending') })).toBeTruthy();
    expect(rulesApi.catalogueSummaryUrl()).toBe('/nginx/fci-payroll/v1/rules/export');
    expect(rulesApi.pendingReportUrl()).toBe('/nginx/fci-payroll/v1/rules/pending/export');
    view.unmount();
    routes();
    wrap(<RuleCatalogue />, READER);
    await screen.findByText('DA.IDA.RATE');
    expect(screen.queryByRole('button', { name: t('m08.report.catalogue') })).toBeNull();
    expect(screen.queryByRole('button', { name: t('m08.report.pending') })).toBeNull();
  });

  it('SRS-19-21: the filters narrow the catalogue and an empty result says so', async () => {
    routes();
    wrap(<RuleCatalogue />, READER);
    await screen.findByText('DA.IDA.RATE');
    await userEvent.selectOptions(screen.getByLabelText(t('m08.state')), 'NOT_CONFIGURED');
    expect(screen.queryByText('DA.IDA.RATE')).toBeNull();
    expect(screen.getByText('CPF.SANCTION.LIMIT')).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText(t('m08.state')), '');
    await userEvent.selectOptions(screen.getByLabelText(t('m08.type')), 'SLAB');
    expect(screen.getByText('INCENTIVE.SLAB')).toBeTruthy();
    expect(screen.queryByText('CPF.SANCTION.LIMIT')).toBeNull();
    await userEvent.selectOptions(screen.getByLabelText(t('m08.domain')), 'STATUTORY_OUTPUT');
    expect(screen.getByText(t('m08.noRules'))).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText(t('m08.type')), '');
    expect(screen.getByText('FORM16.DEFINITION')).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText(t('m08.domain')), '');
    await userEvent.type(screen.getByLabelText(t('m08.search')), 'welfare');
    expect(screen.getByText('LWF.348.APPLICABILITY')).toBeTruthy();
    expect(screen.queryByText('DA.IDA.RATE')).toBeNull();
  });

  it('SRS-19-3: the versions of a rule show its bands, circular and proposer; the toggle closes them', async () => {
    routes();
    wrap(<RuleCatalogue />, READER);
    await screen.findByText('INCENTIVE.SLAB');
    await versionsOf('INCENTIVE.SLAB');
    const card = (await screen.findByText(t('m08.versionsTitle', ['INCENTIVE.SLAB']))).closest(
      '.card',
    ) as HTMLElement;
    expect(await within(card).findByText(`0 – 1000: 0`)).toBeTruthy();
    expect(
      within(card).getByText(`CITY-X · 1000 – ${t('m08.andAbove')}: 5 (${t('m08.step')} 10)`),
    ).toBeTruthy();
    expect(within(card).getByText('HQ/DA/2026/3')).toBeTruthy();
    expect(within(card).getByText('su.hq@fci-demo.test')).toBeTruthy();
    expect(within(card).getByText(t('mst.open'))).toBeTruthy();
    // A superseded go-live version: its end date, its value, its rounding, no circular recorded.
    expect(within(card).getByText(date('2026-06-30'))).toBeTruthy();
    expect(within(card).getByText('4')).toBeTruthy();
    expect(within(card).getByText(t('m08.roundedBy', ['COMPONENT.ROUNDING']))).toBeTruthy();
    expect(within(card).getByText(t('m08.status.SUPERSEDED'))).toBeTruthy();
    expect(within(card).getByText('seed.golive')).toBeTruthy();
    // FRS §24.9: the approver and the version superseded are shown with the version.
    expect(within(card).getByText('rule.approver@fci-demo.test')).toBeTruthy();
    expect(within(card).getByText(t('m08.supersedesVersion', [2]))).toBeTruthy();
    await versionsOf('INCENTIVE.SLAB');
    expect(screen.queryByText(t('m08.versionsTitle', ['INCENTIVE.SLAB']))).toBeNull();
  });

  it('SRS-19-12: a first value is proposed with its date, circular and reason; a back-dated start warns of arrears', async () => {
    const calls = routes();
    wrap(<RuleCatalogue />, ADMIN);
    await screen.findByText('CPF.SANCTION.LIMIT');
    await userEvent.click(
      screen.getByRole('button', { name: t('m08.newVersionOf', ['CPF.SANCTION.LIMIT']) }),
    );
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    expect(submit.hasAttribute('disabled')).toBe(true);
    expect(screen.getByText(t('m08.need.value'))).toBeTruthy();
    setDate(t('mst.from'), '2026-04-01');
    expect(screen.getByText(t('m08.retrospective'))).toBeTruthy();
    // SRS-19-6: an effective-to date before the start is named, and cleared again.
    setDate(t('m08.effectiveTo'), '2026-03-31');
    expect(screen.getByText(t('m08.need.toAfterFrom'))).toBeTruthy();
    setDate(t('m08.effectiveTo'), '');
    expect(screen.queryByText(t('m08.need.toAfterFrom'))).toBeNull();
    await userEvent.type(screen.getByLabelText(t('m08.value')), '500000');
    await chooseCircular();
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Order 3');
    expect(submit.hasAttribute('disabled')).toBe(false);
    await userEvent.click(submit);
    await screen.findByText(t('m08.submitted', ['CPF.SANCTION.LIMIT']));
    expect(sent(calls, 'POST', '/v1/rules/CPF.SANCTION.LIMIT/versions')[0]).toEqual({
      effectiveFrom: '2026-04-01',
      effectiveTo: null,
      value: 500000,
      formula: null,
      bands: null,
      roundingRuleKey: null,
      circularId: 7,
      reason: 'Order 3',
    });
    expect(screen.queryByRole('button', { name: t('common.submitForApproval') })).toBeNull();
  });

  it('SRS-19-12: a conflicting date is refused naming the version; cancel closes the form', async () => {
    routes({ 'POST /v1/rules/': () => problem(409, 'RULE-409-OVERLAP', [3, '2026-07-01']) });
    wrap(<RuleCatalogue />, ADMIN);
    await screen.findByText('DA.IDA.RATE');
    await userEvent.click(screen.getByRole('button', { name: t('m08.supersedeOf', ['DA.IDA.RATE']) }));
    expect(screen.getByText(t('m08.supersedeOf', ['DA.IDA.RATE']))).toBeTruthy();
    await userEvent.type(screen.getByLabelText(t('m08.value')), '55');
    await chooseCircular();
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Order 4');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByRole('alert').textContent).toContain(t('error.RULE-409-OVERLAP', [3, '2026-07-01']));
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('button', { name: t('common.submitForApproval') })).toBeNull();
  });

  it('SRS-19-5: a slab version is entered band by band; bands are added, removed and sent in order', async () => {
    const calls = routes();
    wrap(<RuleCatalogue />, ADMIN);
    await screen.findByText('INCENTIVE.SLAB');
    await userEvent.click(screen.getByRole('button', { name: t('m08.supersedeOf', ['INCENTIVE.SLAB']) }));
    expect(screen.queryByLabelText(t('m08.value'))).toBeNull();
    // The only band cannot be removed.
    expect(screen.getByRole('button', { name: t('m08.removeBand', [1]) }).hasAttribute('disabled')).toBe(
      true,
    );
    await userEvent.type(screen.getByLabelText(t('m08.bandFromOf', [1])), '0');
    await userEvent.type(screen.getByLabelText(t('m08.bandToOf', [1])), '1000');
    await userEvent.type(screen.getByLabelText(t('m08.rateOrAmountOf', [1])), '0');
    await userEvent.click(screen.getByRole('button', { name: t('m08.addBand') }));
    // A new band starts where the last one ended.
    expect((screen.getByLabelText(t('m08.bandFromOf', [2])) as HTMLInputElement).value).toBe('1000');
    await userEvent.type(screen.getByLabelText(t('m08.rateOrAmountOf', [2])), '5');
    await userEvent.type(screen.getByLabelText(t('m08.stepOf', [2])), '10');
    await userEvent.type(screen.getByLabelText(t('m08.qualifierOf', [2])), 'city-x');
    await userEvent.click(screen.getByRole('button', { name: t('m08.addBand') }));
    await userEvent.click(screen.getByRole('button', { name: t('m08.removeBand', [3]) }));
    expect(screen.queryByLabelText(t('m08.bandFromOf', [3]))).toBeNull();
    await chooseCircular();
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'MoU 2026');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await screen.findByText(t('m08.submitted', ['INCENTIVE.SLAB']));
    expect(sent(calls, 'POST', '/v1/rules/INCENTIVE.SLAB/versions')[0]).toMatchObject({
      value: null,
      formula: null,
      bands: [
        { order: 1, from: 0, to: 1000, rateOrAmount: 0, step: null, qualifier: null },
        { order: 2, from: 1000, to: null, rateOrAmount: 5, step: 10, qualifier: 'CITY-X' },
      ],
    });
  });

  it('SRS-19-15: a formula is written beside the grammar in force, with a rounding setting', async () => {
    const calls = routes({
      'GET /v1/rules': () => ({
        ...CATALOGUE,
        rules: [
          ...RULES.filter((r) => r.key !== 'TA.FORMULA'),
          rule({
            key: 'TA.FORMULA',
            type: 'FORMULA',
            valueShape: 'NUMBER_FORMULA',
            inForce: null,
            versions: 0,
          }),
          rule({
            key: 'COMPONENT.ROUNDING',
            type: 'ROUNDING',
            valueShape: null,
            maintained: false,
            inForce: null,
            versions: 0,
          }),
        ],
      }),
      'GET /v1/rules/vocabulary': () => ({ ...VOCABULARY, variables: [] }),
    });
    wrap(<RuleCatalogue />, ADMIN);
    await screen.findByText('DA.IDA.RATE');
    await userEvent.click(screen.getByRole('button', { name: t('m08.newVersionOf', ['TA.FORMULA']) }));
    expect(await screen.findByText('IF(3) MIN(…) ROUND(2)')).toBeTruthy();
    expect(screen.getByText(t('m08.noVariables'))).toBeTruthy();
    expect(screen.getByText(t('m08.formulaHint'))).toBeTruthy();
    await userEvent.type(screen.getByLabelText(t('m08.formula')), 'BASIC * 0.06');
    await userEvent.selectOptions(screen.getByLabelText(t('m08.rounding')), 'COMPONENT.ROUNDING');
    await chooseCircular();
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'TA order');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await screen.findByText(t('m08.submitted', ['TA.FORMULA']));
    expect(sent(calls, 'POST', '/v1/rules/TA.FORMULA/versions')[0]).toMatchObject({
      value: null,
      formula: 'BASIC * 0.06',
      bands: null,
      roundingRuleKey: 'COMPONENT.ROUNDING',
    });
  });

  it('SRS-19-1: an eligibility or applicability rule takes a condition', async () => {
    routes({
      'GET /v1/rules': () => ({
        ...CATALOGUE,
        rules: [
          rule({
            key: 'CEA.ELIGIBLE',
            type: 'ELIGIBILITY',
            valueShape: 'CONDITION_FORMULA',
            inForce: null,
            versions: 0,
          }),
        ],
      }),
    });
    wrap(<RuleCatalogue />, ADMIN);
    await screen.findByText('CEA.ELIGIBLE');
    await userEvent.click(screen.getByRole('button', { name: t('m08.newVersionOf', ['CEA.ELIGIBLE']) }));
    expect(screen.getByLabelText(t('m08.condition'))).toBeTruthy();
    expect(screen.getByText(t('m08.conditionHint'))).toBeTruthy();
    expect(await screen.findByText('BASIC')).toBeTruthy();
  });

  it('SRS-19-6: an end date without successor is proposed with its reason', async () => {
    const calls = routes({
      'POST /v1/rules/DA.IDA.RATE/end': accepted({
        approvalId: 97,
        versionNo: 3,
        endOn: '2026-12-31',
        requestedBy: 'x',
        requestedAt: 'y',
      }),
    });
    wrap(<RuleCatalogue />, ADMIN);
    await screen.findByText('DA.IDA.RATE');
    await endRule('DA.IDA.RATE');
    expect(screen.getByText(t('m08.endHint'))).toBeTruthy();
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    expect(submit.hasAttribute('disabled')).toBe(true);
    setDate(t('m08.endOn'), '2026-12-31');
    expect(submit.hasAttribute('disabled')).toBe(true);
    await userEvent.type(screen.getByLabelText(t('common.reason')), '  Scheme withdrawn  ');
    await userEvent.click(submit);
    await screen.findByText(t('m08.endSubmitted', ['DA.IDA.RATE']));
    expect(sent(calls, 'POST', '/v1/rules/DA.IDA.RATE/end')[0]).toEqual({
      endOn: '2026-12-31',
      reason: 'Scheme withdrawn',
    });
  });

  it('SRS-19-6: a refused end shows why; switching to a new version closes the end form', async () => {
    routes({ 'POST /v1/rules/DA.IDA.RATE/end': () => problem(422, 'RULE-422-END', ['2026-07-01']) });
    wrap(<RuleCatalogue />, ADMIN);
    await screen.findByText('DA.IDA.RATE');
    await endRule('DA.IDA.RATE');
    setDate(t('m08.endOn'), '2026-06-30');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Wrong');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect((await screen.findByRole('alert')).textContent).toContain(t('error.RULE-422-END', ['2026-07-01']));
    await userEvent.click(screen.getByRole('button', { name: t('m08.supersedeOf', ['DA.IDA.RATE']) }));
    expect(screen.queryByLabelText(t('m08.endOn'))).toBeNull();
    expect(screen.getByLabelText(t('m08.value'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    await waitFor(() => expect(screen.queryByLabelText(t('m08.value'))).toBeNull());
  });

  it('SRS-19-6: the end form is cancelled without a proposal', async () => {
    const calls = routes();
    wrap(<RuleCatalogue />, ADMIN);
    await screen.findByText('DA.IDA.RATE');
    await endRule('DA.IDA.RATE');
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByLabelText(t('m08.endOn'))).toBeNull();
    expect(sent(calls, 'POST', '/end')).toHaveLength(0);
  });

  it('SRS-19-17: clearing the circular makes the form incomplete again', async () => {
    routes();
    wrap(<RuleCatalogue />, ADMIN);
    await screen.findByText('DA.IDA.RATE');
    await userEvent.click(screen.getByRole('button', { name: t('m08.supersedeOf', ['DA.IDA.RATE']) }));
    await chooseCircular();
    expect(screen.queryByText(t('m08.need.circular'))).toBeNull();
    await userEvent.selectOptions(screen.getByLabelText(t('m08.circular')), '');
    expect(screen.getByText(t('m08.need.circular'))).toBeTruthy();
  });

  it('SRS-G-48: a cancelled OTP leaves both proposals open and silent', async () => {
    routes({
      'POST /v1/rules/': () => problem(428, 'AUTH-428-OTP', ['7']),
      'POST /v1/rules/DA.IDA.RATE/end': () => problem(428, 'AUTH-428-OTP', ['8']),
    });
    wrap(<RuleCatalogue />, ADMIN);
    await screen.findByText('DA.IDA.RATE');
    await userEvent.click(screen.getByRole('button', { name: t('m08.supersedeOf', ['DA.IDA.RATE']) }));
    await userEvent.type(screen.getByLabelText(t('m08.value')), '55');
    await chooseCircular();
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Order 4');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await cancelOtp();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByLabelText(t('m08.value'))).toBeTruthy();
    await endRule('DA.IDA.RATE');
    setDate(t('m08.endOn'), '2026-12-31');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Withdrawn');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await cancelOtp();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByLabelText(t('m08.endOn'))).toBeTruthy();
  });

  it('SRS-19-17: with no Active circular the form says where to record the order', async () => {
    routes({ 'GET /v1/masters/circulars/current': () => [] });
    wrap(<RuleCatalogue />, ADMIN);
    await screen.findByText('CPF.SANCTION.LIMIT');
    await userEvent.click(
      screen.getByRole('button', { name: t('m08.newVersionOf', ['CPF.SANCTION.LIMIT']) }),
    );
    expect(await screen.findByText(t('m08.noActiveCircular'))).toBeTruthy();
    expect(screen.getByText(t('m08.need.circular'))).toBeTruthy();
  });

  it('SRS-19-14: a catalogue that cannot be read shows the problem', async () => {
    routes({ 'GET /v1/rules': () => problem(403, 'COM-403') });
    wrap(<RuleCatalogue />, READER);
    expect(await screen.findByRole('alert')).toBeTruthy();
  });
});

// ---------------------------------------------------------------- edges, feature by feature

describe('M-08 edges', () => {
  /** The catalogue card (a key may also appear in the pending view). */
  const catalogueCard = () => screen.getByText(t('m08w.catalogue')).closest('.card') as HTMLElement;
  const tileOf = (label: string) => screen.getByText(label).closest('.tile') as HTMLElement;

  // ---- catalogue display

  it('SRS-19-21: the tiles say nothing until the catalogue has answered', () => {
    routes();
    wrap(<RuleCatalogue />, READER);
    expect(within(tileOf(t('m08w.tile.rules'))).getByText('—')).toBeTruthy();
    expect(within(tileOf(t('m08w.tile.missing'))).getByText('—')).toBeTruthy();
  });

  it('SRS-19-21: an empty catalogue counts nothing and the seed tile reads as fine', async () => {
    routes({
      'GET /v1/rules': () => ({
        asOf: TODAY,
        summary: { rules: 0, byType: {}, pendingRuleChanges: 0, pendingOtherChanges: 0, notConfigured: 0 },
        rules: [],
      }),
    });
    wrap(<RuleCatalogue />, READER);
    expect(await screen.findByText(t('m08.noRules'))).toBeTruthy();
    expect(within(tileOf(t('m08w.tile.rules'))).getByText('0')).toBeTruthy();
    expect(tileOf(t('m08w.tile.missing')).querySelector('.fl-tico-ok')).not.toBeNull();
    expect(screen.getByText(t('m08.tile.rulesSub', [0]))).toBeTruthy();
  });

  it('SRS-19-4: a rule showing both a pending version and a pending end offers no action at all', async () => {
    routes({
      'GET /v1/rules': () => ({
        ...CATALOGUE,
        rules: [
          rule({
            pending: version({ id: null, status: 'PENDING_APPROVAL', versionNo: 4 }),
            pendingEnd: RULES[4]?.pendingEnd ?? null,
          }),
        ],
      }),
    });
    wrap(<RuleCatalogue />, ADMIN);
    await screen.findByText('DA.IDA.RATE');
    const row = within(catalogueCard()).getByText('DA.IDA.RATE').closest('tr') as HTMLElement;
    expect(within(row).getAllByText(/awaiting approval/)).toHaveLength(2);
    const edit = within(row).getByRole('button', { name: t('m08.supersedeOf', ['DA.IDA.RATE']) });
    expect(edit).toBeDisabled();
    expect(edit).toHaveAttribute('data-why', t('m08w.why.pending'));
    const dialog = await viewRule('DA.IDA.RATE');
    expect(within(dialog).queryByRole('button', { name: t('m08.endOf', ['DA.IDA.RATE']) })).toBeNull();
  });

  it('SRS-19-4: a pending version is simulated from its row; only the Rule Administrator or Approver runs one', async () => {
    const pendingRule = {
      ...CATALOGUE,
      rules: [rule({ pending: version({ id: null, status: 'PENDING_APPROVAL', versionNo: 4 }) })],
    };
    for (const [profile, mayRun] of [
      [ADMIN, true],
      [['M-08:VIEW', 'M-08:APPROVE'], true],
      [['M-08:VIEW'], false],
    ] as const) {
      routes({
        'GET /v1/rules': () => pendingRule,
        'GET /v1/rules/DA.IDA.RATE/simulations': () => paged([]),
        'GET /v1/rules/simulation-offices': () => [],
      });
      const view = wrap(<RuleCatalogue />, [...profile]);
      await screen.findByText('DA.IDA.RATE');
      const simulate = async () =>
        userEvent.click(
          within(await viewRule('DA.IDA.RATE')).getByRole('button', {
            name: t('m08.sim.of', ['DA.IDA.RATE']),
          }),
        );
      await simulate();
      expect(await screen.findByText(t('m08.sim.none'))).toBeTruthy();
      expect(screen.queryByRole('button', { name: t('m08.sim.run') }) !== null).toBe(mayRun);
      if (mayRun) {
        await simulate();
      } else {
        await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
      }
      expect(screen.queryByText(t('m08.sim.none'))).toBeNull();
      view.unmount();
      vi.unstubAllGlobals();
    }
  });

  it('SRS-19-21: the pending view fails on its own; the catalogue still shows', async () => {
    routes({ 'GET /v1/rules/pending': () => problem(503, 'COM-503') });
    wrap(<RuleCatalogue />, READER);
    await screen.findByText('DA.IDA.RATE');
    const pendingCard = screen
      .getByRole('heading', { name: t('m08.pendingTitle') })
      .closest('.card') as HTMLElement;
    expect(await within(pendingCard).findByRole('alert')).toBeTruthy();
    expect(within(catalogueCard()).queryByRole('alert')).toBeNull();
  });

  // ---- versions panel

  it('SRS-19-3: choosing another rule moves the versions panel to it; an empty history says so', async () => {
    routes({ 'GET /v1/rules/DA.IDA.RATE/versions': () => paged([]) });
    wrap(<RuleCatalogue />, READER);
    await screen.findByText('INCENTIVE.SLAB');
    await versionsOf('INCENTIVE.SLAB');
    expect(await screen.findByText(t('m08.versionsTitle', ['INCENTIVE.SLAB']))).toBeTruthy();
    await versionsOf('DA.IDA.RATE');
    expect(await screen.findByText(t('m08.versionsTitle', ['DA.IDA.RATE']))).toBeTruthy();
    expect(screen.queryByText(t('m08.versionsTitle', ['INCENTIVE.SLAB']))).toBeNull();
    const slab = await viewRule('INCENTIVE.SLAB');
    expect(within(slab).getByRole('button', { name: t('m08.versions') })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
    await closeView(slab);
    const da = await viewRule('DA.IDA.RATE');
    expect(within(da).getByRole('button', { name: t('m08.versions') })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    await closeView(da);
    expect(await screen.findByText(t('m08.noVersions'))).toBeTruthy();
  });

  it('SRS-19-3: versions that cannot be read show the problem in their panel', async () => {
    routes({ 'GET /v1/rules/DA.IDA.RATE/versions': () => problem(404, 'COM-404') });
    wrap(<RuleCatalogue />, READER);
    await screen.findByText('DA.IDA.RATE');
    await versionsOf('DA.IDA.RATE');
    const panel = (await screen.findByText(t('m08.versionsTitle', ['DA.IDA.RATE']))).closest(
      '.card',
    ) as HTMLElement;
    expect(await within(panel).findByRole('alert')).toBeTruthy();
    expect(within(panel).queryByText(t('m08.noVersions'))).toBeNull();
  });

  // ---- the proposal form

  it('SRS-19-12: the form starts on the catalogue day and a back-dated start warns of arrears only while back-dated', async () => {
    routes();
    wrap(<RuleCatalogue />, ADMIN);
    await screen.findByText('DA.IDA.RATE');
    await userEvent.click(screen.getByRole('button', { name: t('m08.supersedeOf', ['DA.IDA.RATE']) }));
    expect((screen.getByLabelText(t('mst.from')) as HTMLInputElement).value).toBe(TODAY);
    expect(screen.queryByText(t('m08.retrospective'))).toBeNull();
    setDate(t('mst.from'), '2026-10-22');
    expect(screen.getByText(t('m08.retrospective'))).toBeTruthy();
    setDate(t('mst.from'), '2026-10-24');
    expect(screen.queryByText(t('m08.retrospective'))).toBeNull();
    setDate(t('mst.from'), '');
    expect(screen.getByText(t('m08.need.from'))).toBeTruthy();
  });

  it('SRS-19-12: opening another rule discards the draft of the first', async () => {
    routes();
    wrap(<RuleCatalogue />, ADMIN);
    await screen.findByText('DA.IDA.RATE');
    await userEvent.click(screen.getByRole('button', { name: t('m08.supersedeOf', ['DA.IDA.RATE']) }));
    await userEvent.type(screen.getByLabelText(t('m08.value')), '55');
    await userEvent.click(
      screen.getByRole('button', { name: t('m08.newVersionOf', ['CPF.SANCTION.LIMIT']) }),
    );
    expect(screen.getByText(t('m08.newVersionOf', ['CPF.SANCTION.LIMIT']), { selector: 'h3' })).toBeTruthy();
    expect((screen.getByLabelText(t('m08.value')) as HTMLInputElement).value).toBe('');
  });

  it('SRS-19-1: a negative or padded value is sent as its number', async () => {
    const calls = routes();
    wrap(<RuleCatalogue />, ADMIN);
    await screen.findByText('DA.IDA.RATE');
    await userEvent.click(screen.getByRole('button', { name: t('m08.supersedeOf', ['DA.IDA.RATE']) }));
    await userEvent.type(screen.getByLabelText(t('m08.value')), ' -5.5 ');
    expect(screen.queryByText(t('m08.need.amount'))).toBeNull();
    await chooseCircular();
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Recovery rate');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await screen.findByText(t('m08.submitted', ['DA.IDA.RATE']));
    expect(sent(calls, 'POST', '/v1/rules/DA.IDA.RATE/versions')[0]).toMatchObject({ value: -5.5 });
  });

  it('SRS-19-5: removing a middle band moves the later bands up with their values', async () => {
    routes();
    wrap(<RuleCatalogue />, ADMIN);
    await screen.findByText('INCENTIVE.SLAB');
    await userEvent.click(screen.getByRole('button', { name: t('m08.supersedeOf', ['INCENTIVE.SLAB']) }));
    await userEvent.type(screen.getByLabelText(t('m08.bandToOf', [1])), '100');
    await userEvent.click(screen.getByRole('button', { name: t('m08.addBand') }));
    await userEvent.type(screen.getByLabelText(t('m08.bandToOf', [2])), '200');
    await userEvent.click(screen.getByRole('button', { name: t('m08.addBand') }));
    await userEvent.type(screen.getByLabelText(t('m08.rateOrAmountOf', [3])), '9');
    await userEvent.click(screen.getByRole('button', { name: t('m08.removeBand', [2]) }));
    expect(screen.queryByLabelText(t('m08.bandFromOf', [3]))).toBeNull();
    // The former band 3 is now band 2: it starts at 200 and keeps its rate.
    expect((screen.getByLabelText(t('m08.bandFromOf', [2])) as HTMLInputElement).value).toBe('200');
    expect((screen.getByLabelText(t('m08.rateOrAmountOf', [2])) as HTMLInputElement).value).toBe('9');
  });

  it('SRS-19-1: only an active rounding rule is offered as a rounding setting', async () => {
    routes({
      'GET /v1/rules': () => ({
        ...CATALOGUE,
        rules: [
          ...RULES,
          rule({
            key: 'ACTIVE.ROUNDING',
            type: 'ROUNDING',
            valueShape: null,
            maintained: false,
            inForce: null,
            versions: 0,
          }),
          rule({
            key: 'OLD.ROUNDING',
            type: 'ROUNDING',
            valueShape: null,
            maintained: false,
            active: false,
            inForce: null,
            versions: 0,
          }),
        ],
      }),
    });
    wrap(<RuleCatalogue />, ADMIN);
    await screen.findByText('DA.IDA.RATE');
    await userEvent.click(screen.getByRole('button', { name: t('m08.supersedeOf', ['DA.IDA.RATE']) }));
    const rounding = screen.getByLabelText(t('m08.rounding'));
    expect(within(rounding).getByRole('option', { name: 'ACTIVE.ROUNDING' })).toBeTruthy();
    expect(within(rounding).queryByRole('option', { name: 'OLD.ROUNDING' })).toBeNull();
  });

  it('SRS-19-12: every free-text field is held to its column width', async () => {
    routes();
    wrap(<RuleCatalogue />, ADMIN);
    await screen.findByText('INCENTIVE.SLAB');
    await userEvent.click(screen.getByRole('button', { name: t('m08.supersedeOf', ['INCENTIVE.SLAB']) }));
    expect(screen.getByLabelText(t('common.reason')).getAttribute('maxlength')).toBe('500');
    expect(screen.getByLabelText(t('m08.qualifierOf', [1])).getAttribute('maxlength')).toBe('40');
    expect(screen.getByLabelText(t('m08.bandFromOf', [1])).getAttribute('maxlength')).toBe('14');
    await userEvent.click(
      screen.getByRole('button', { name: t('m08.newVersionOf', ['CPF.SANCTION.LIMIT']) }),
    );
    expect(screen.getByLabelText(t('m08.value')).getAttribute('maxlength')).toBe('14');
  });

  it('SRS-19-4: the proposal cannot be sent twice while it is on its way, and the catalogue is read again after it', async () => {
    let release: (r: Response) => void = () => undefined;
    const calls: string[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        const method = init?.method ?? 'GET';
        calls.push(`${method} ${url}`);
        if (url.endsWith('/auth/csrf')) {
          document.cookie = 'XSRF-TOKEN=t; Path=/';
          return new Response(null, { status: 204 });
        }
        const json = (body: unknown, status = 200) =>
          new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
        if (method === 'POST') return new Promise<Response>((resolve) => (release = resolve));
        if (url.includes('/v1/masters/circulars/current')) return json(CIRCULARS);
        if (url.includes('/v1/rules/pending')) return json(paged([]));
        return json(CATALOGUE);
      }),
    );
    wrap(<RuleCatalogue />, ADMIN);
    await screen.findByText('DA.IDA.RATE');
    await userEvent.click(screen.getByRole('button', { name: t('m08.supersedeOf', ['DA.IDA.RATE']) }));
    await userEvent.type(screen.getByLabelText(t('m08.value')), '55');
    await chooseCircular();
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Order 4');
    const submit = screen.getByRole('button', { name: t('common.submitForApproval') });
    await userEvent.click(submit);
    await waitFor(() => expect(submit.hasAttribute('disabled')).toBe(true));
    await userEvent.click(submit);
    expect(calls.filter((c) => c.startsWith('POST'))).toHaveLength(1);
    const before = calls.filter((c) => c === 'GET /nginx/fci-payroll/v1/rules').length;
    release(
      new Response(JSON.stringify(version({ id: null, status: 'PENDING_APPROVAL' })), {
        status: 202,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    await screen.findByText(t('m08.submitted', ['DA.IDA.RATE']));
    await waitFor(() =>
      expect(calls.filter((c) => c === 'GET /nginx/fci-payroll/v1/rules').length).toBeGreaterThan(before),
    );
  });

  it('SRS-19-12: a refused proposal can be corrected and sent again; the problem then goes away', async () => {
    let first = true;
    routes({
      'POST /v1/rules/': () => {
        if (first) {
          first = false;
          return problem(422, 'RULE-422-VALUE', ['the value exceeds 10 digits and 2 decimals']);
        }
        return { status: 202, body: version({ id: null, status: 'PENDING_APPROVAL' }) };
      },
    });
    wrap(<RuleCatalogue />, ADMIN);
    await screen.findByText('DA.IDA.RATE');
    await userEvent.click(screen.getByRole('button', { name: t('m08.supersedeOf', ['DA.IDA.RATE']) }));
    await userEvent.type(screen.getByLabelText(t('m08.value')), '55');
    await chooseCircular();
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Order 4');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await screen.findByText(t('m08.submitted', ['DA.IDA.RATE']));
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('SRS-19-17: a role without M-25 access is told why no circular can be chosen', async () => {
    routes({ 'GET /v1/masters/circulars/current': () => problem(403, 'AUTHZ-403') });
    wrap(<RuleCatalogue />, ['M-08:VIEW', 'M-08:EDIT']);
    await screen.findByText('DA.IDA.RATE');
    await userEvent.click(screen.getByRole('button', { name: t('m08.supersedeOf', ['DA.IDA.RATE']) }));
    const field = screen.getByLabelText(t('m08.circular')).closest('.rf-field') as HTMLElement;
    expect(await within(field).findByRole('alert')).toBeTruthy();
    expect(within(field).queryByText(t('m08.noActiveCircular'))).toBeNull();
  });

  it('SRS-19-15: without the vocabulary the formula can still be written and sent; the server checks it', async () => {
    const calls = routes({
      'GET /v1/rules/vocabulary': () => problem(503, 'COM-503'),
      'GET /v1/rules': () => ({
        ...CATALOGUE,
        rules: [
          rule({
            key: 'TA.FORMULA',
            type: 'FORMULA',
            valueShape: 'NUMBER_FORMULA',
            inForce: null,
            versions: 0,
          }),
        ],
      }),
    });
    wrap(<RuleCatalogue />, ADMIN);
    await waitFor(() => within(catalogueCard()).getByText('TA.FORMULA'));
    await userEvent.click(screen.getByRole('button', { name: t('m08.newVersionOf', ['TA.FORMULA']) }));
    const field = screen.getByLabelText(t('m08.formula')).closest('.rf-field') as HTMLElement;
    expect(await within(field).findByRole('alert')).toBeTruthy();
    await userEvent.type(screen.getByLabelText(t('m08.formula')), 'BASIC * 2');
    await chooseCircular();
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'TA order');
    await userEvent.click(screen.getByRole('button', { name: t('common.submitForApproval') }));
    await screen.findByText(t('m08.submitted', ['TA.FORMULA']));
    expect(sent(calls, 'POST', '/v1/rules/TA.FORMULA/versions')[0]).toMatchObject({ formula: 'BASIC * 2' });
  });

  // ---- permissions

  it('SRS-19-4: CREATE alone proposes first values only; EDIT alone supersedes and ends only', async () => {
    routes();
    const { unmount } = wrap(<RuleCatalogue />, ['M-08:VIEW', 'M-08:CREATE', 'M-25:VIEW']);
    await screen.findByText('DA.IDA.RATE');
    expect(screen.getByRole('button', { name: t('m08.newVersionOf', ['CPF.SANCTION.LIMIT']) })).toBeTruthy();
    expect(screen.getByRole('button', { name: t('m08.supersedeOf', ['DA.IDA.RATE']) })).toBeDisabled();
    const firstOnly = await viewRule('DA.IDA.RATE');
    expect(within(firstOnly).queryByRole('button', { name: t('m08.endOf', ['DA.IDA.RATE']) })).toBeNull();
    await closeView(firstOnly);
    expect(screen.getByText(t('m08.hint'))).toBeTruthy();
    unmount();
    routes();
    wrap(<RuleCatalogue />, ['M-08:VIEW', 'M-08:EDIT', 'M-25:VIEW']);
    await screen.findByText('DA.IDA.RATE');
    expect(
      screen.getByRole('button', { name: t('m08.newVersionOf', ['CPF.SANCTION.LIMIT']) }),
    ).toBeDisabled();
    expect(screen.getByRole('button', { name: t('m08.supersedeOf', ['DA.IDA.RATE']) })).toBeEnabled();
    const dialog = await viewRule('DA.IDA.RATE');
    expect(within(dialog).getByRole('button', { name: t('m08.endOf', ['DA.IDA.RATE']) })).toBeTruthy();
  });

  it('SRS-19-5: a version holds at most the bands the API accepts', () => {
    const band = { from: '0', to: '', rateOrAmount: '1', step: '', qualifier: '' };
    expect(canAddBand(Array.from({ length: MAX_BANDS - 1 }, () => band))).toBe(true);
    expect(canAddBand(Array.from({ length: MAX_BANDS }, () => band))).toBe(false);
  });

  // ---- as Wireframes v6.1 draws it

  const ADMIN_PENDING = {
    ...CATALOGUE,
    rules: [rule({ pending: version({ id: null, status: 'PENDING_APPROVAL', versionNo: 15, value: 53 }) })],
  };
  const priced = {
    id: 3,
    approvalId: 40,
    ruleKey: 'DA.IDA.RATE',
    payrollType: null,
    officeId: null,
    requestedBy: 'rule.admin@fci-demo.test',
    requestedAt: '2026-10-21T08:00:00Z',
    population: 18402,
    impact: {
      sampled: 500,
      inScope: 500,
      changed: 500,
      groups: [],
      priced: {
        priced: 498,
        notComputed: 2,
        currentComponent: 1000,
        proposedComponent: 1060,
        currentNet: 9000,
        proposedNet: 9050,
        statutoryDelta: { TDS: 12.5 },
        negativeNet: 1,
        retroPeriods: 2,
      },
    },
    ruleVersionId: null,
    inputsChanged: false,
  };

  it('BR-RULE-006: the card shows the amounts of the latest run and says that pay reads DA from M-04', async () => {
    routes({
      'GET /v1/rules': () => ADMIN_PENDING,
      'GET /v1/rules/DA.IDA.RATE/simulations': () => paged([priced]),
    });
    wrap(<RuleCatalogue />, READER);
    expect(await screen.findByText(t('m08w.sim.daSource'))).toBeTruthy();
    expect(screen.getByText(/^Simulation — DA\.IDA\.RATE v15/)).toBeTruthy();
    expect(screen.getByText(t('m08w.sim.periods', [2]))).toBeTruthy();
    expect(screen.getByText(t('m08w.sim.sample', [18402, 500]))).toBeTruthy();
    // Nothing is decided here: without APPROVE both drawn buttons are disabled with the reason.
    const approve = screen.getByRole('button', { name: t('m08w.approve') });
    expect(approve).toBeDisabled();
    expect(approve).toHaveAttribute('data-why', t('m08w.why.approver'));
  });

  it('BR-RULE-006: a run the engines do not price says so, with no invented amount', async () => {
    routes({
      'GET /v1/rules': () => ADMIN_PENDING,
      'GET /v1/rules/DA.IDA.RATE/simulations': () =>
        paged([{ ...priced, impact: { ...priced.impact, priced: null } }]),
    });
    wrap(<RuleCatalogue />, READER);
    expect(await screen.findByText(t('m08w.sim.notPriced'))).toBeTruthy();
    expect(screen.queryByText(t('m08w.sim.daSource'))).toBeNull();
  });

  it('M08-08: a Rule Approver is sent to the inbox: there is no second decision path', async () => {
    routes({
      'GET /v1/rules': () => ADMIN_PENDING,
      'GET /v1/rules/DA.IDA.RATE/simulations': () => paged([]),
    });
    wrap(<RuleCatalogue />, ['M-08:VIEW', 'M-08:APPROVE']);
    const approve = await screen.findByRole('link', { name: t('m08w.approve') });
    expect(approve.getAttribute('href')).toContain('W-01');
    expect(screen.getByRole('link', { name: t('m08w.return') }).getAttribute('href')).toContain('W-01');
  });

  it('SRS-19-21: a domain entered elsewhere is a read-only row whose Edit names that screen', async () => {
    routes({
      'GET /v1/rules/read-through': () => [
        {
          key: 'PENALTY.MINNET.FLOOR',
          type: 'FLOOR',
          screen: 'M-14',
          records: 2,
          from: '2026-04-01',
          pending: false,
        },
        {
          key: 'SUPP.EXCL.CODES',
          type: 'ELIGIBILITY',
          screen: 'M-14',
          records: 0,
          from: null,
          pending: true,
        },
      ],
    });
    wrap(<RuleCatalogue />, ADMIN);
    const row = (await screen.findAllByText('PENALTY.MINNET.FLOOR'))[0]?.closest('tr') as HTMLElement;
    expect(row).toHaveTextContent(t('m08w.enteredOn', ['M-14', 2]));
    const edit = within(row).getByRole('button', { name: t('m08.newVersionOf', ['PENALTY.MINNET.FLOOR']) });
    expect(edit).toHaveAttribute('data-why', t('m08w.why.enteredOn', ['M-14']));
    expect(screen.getAllByText('SUPP.EXCL.CODES')[0]?.closest('tr')).toHaveTextContent(t('m08.pendingTitle'));
  });

  it('SRS l.1939: New Rule Version starts from the rule type; a viewer has it disabled with the reason', async () => {
    routes();
    const { unmount } = wrap(<RuleCatalogue />, ADMIN);
    await screen.findByText('DA.IDA.RATE');
    await userEvent.click(screen.getByRole('button', { name: t('m08w.newVersion') }));
    const go = screen.getByRole('button', { name: t('m08w.pick.go') });
    expect(go).toBeDisabled();
    await userEvent.selectOptions(
      screen.getByLabelText(t('m08.type'), { selector: '#m08-pick-type' }),
      'CEILING',
    );
    await userEvent.selectOptions(screen.getByLabelText(t('m08.ruleKey')), 'CPF.SANCTION.LIMIT');
    await userEvent.click(go);
    expect(await screen.findByLabelText(t('mst.from'))).toBeTruthy();
    unmount();
    routes();
    wrap(<RuleCatalogue />, READER);
    await screen.findByText('DA.IDA.RATE');
    expect(screen.getByRole('button', { name: t('m08w.newVersion') })).toHaveAttribute(
      'data-why',
      t('m08w.why.ruleAdmin'),
    );
    // Drawn enabled for every viewer: it says why and runs nothing.
    expect(screen.getByRole('button', { name: t('m08w.simulateImpact') })).toHaveAttribute(
      'data-guard',
      t('m08w.why.simulate'),
    );
    expect(screen.getByRole('button', { name: t('m08w.simulate') })).toHaveAttribute(
      'data-guard',
      t('m08w.why.simulate'),
    );
  });

  it('OD-135: the drawn checker and Administrator are told why; the rights do not move', async () => {
    const calls = routes({
      'GET /v1/rules': () => ADMIN_PENDING,
      'GET /v1/rules/DA.IDA.RATE/simulations': () => paged([]),
    });
    const checker = wrap(<RuleCatalogue />, me(['M-08:VIEW'], { roles: ['PAYROLL_CHECKER'] }));
    const approve = await screen.findByRole('button', { name: t('m08w.approve') });
    expect(approve).toHaveAttribute('data-guard', t('m08w.why.approver'));
    expect(screen.getByRole('button', { name: t('m08w.return') })).toHaveAttribute(
      'data-guard',
      t('m08w.why.approver'),
    );
    expect(screen.getByRole('button', { name: t('action.supersede') })).toHaveAttribute(
      'data-guard',
      t('m14w.why.supersede'),
    );
    expect(screen.getByRole('button', { name: t('m08w.newVersion') })).toBeDisabled();
    await userEvent.click(approve);
    expect(await screen.findByText(t('m08w.why.approver'))).toBeTruthy();
    checker.unmount();
    wrap(<RuleCatalogue />, me(['M-08:VIEW'], { roles: ['SYSTEM_ADMINISTRATOR'] }));
    const drawn = await screen.findByRole('button', { name: t('m08w.newVersion') });
    expect(drawn).toHaveAttribute('data-guard', t('m08w.why.ruleAdmin'));
    expect(screen.getByRole('button', { name: t('action.supersede') })).toBeDisabled();
    expect(screen.getByRole('button', { name: t('m08w.approve') })).toBeDisabled();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });
});
