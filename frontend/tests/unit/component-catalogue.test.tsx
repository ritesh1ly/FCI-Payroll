import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ComponentRow } from '@/modules/configuration/api/componentsApi';
import { ComponentCatalogue } from '@/modules/configuration';
import { componentProblems, EMPTY_COMPONENT } from '@/modules/configuration/business/componentRules';
import { backend, exportedUrl, me, paged, problem, t, wrap } from './support/harness';

/**
 * M-07 Component Catalogue as Wireframes v6.1 draws it (plan 06 phase 06d; SRS-G-42, FAP-14, SRS l.1907, l.1909): the
 * Staff and Labour tables filtered on the server, where a code is used, a new component sent for approval, a revision
 * that may only add payroll types, a retirement, a refused code; an action the role lacks drawn disabled with the
 * reason (SRS-G-25, OD-135); nothing without M-07 VIEW.
 */

afterEach(() => vi.unstubAllGlobals());

const ROW: ComponentRow = {
  payCode: 101,
  name: 'Basic Pay',
  type: 'EARNING',
  payslipGroup: 'PAY',
  basisRuleKey: 'COMP.BASIS.MASTER',
  source: 'HRMS',
  naturalAccount: 'NA-4001',
  taxable: 'Y',
  inCpfBase: true,
  payrollTypes: ['STAFF'],
  labourOnly: false,
  effectiveFrom: '2000-01-01',
  effectiveTo: null,
  retired: false,
  versionNo: 1,
  pending: false,
  subjectToLwp: true,
  eligibilityRuleKey: null,
};

const LABOUR: ComponentRow = {
  ...ROW,
  payCode: 188,
  name: 'Minimum Guaranteed Wage',
  payrollTypes: ['DPS_LABOUR', 'NWNP_LABOUR'],
  labourOnly: true,
};

const ALL = ['M-07:VIEW', 'M-07:CREATE', 'M-07:EDIT'];

const list = (code: string, values: [string, string][]) => ({
  code,
  name: code,
  module: 'CONFIGURATION',
  system: true,
  values: values.map(([c, label], i) => ({
    code: c,
    label,
    labelHi: null,
    sortOrder: i,
    numeric: null,
    parent: null,
    active: true,
  })),
});

const LISTS = {
  asOf: '2026-11-07',
  lists: {
    COMPONENT_VIEW: list('COMPONENT_VIEW', [
      ['STAFF', 'Staff'],
      ['LABOUR', 'Labour'],
      ['EARNING', 'Earnings'],
      ['DEDUCTION', 'Deductions'],
    ]),
    COMPONENT_TYPE: list('COMPONENT_TYPE', [
      ['EARNING', 'Earning'],
      ['DEDUCTION', 'Deduction'],
    ]),
    FIELD_SOURCE: list('FIELD_SOURCE', [
      ['ENT', 'Enterable'],
      ['CALC', 'Calculated'],
      ['RULE', 'Rule engine'],
      ['ESS', 'Self-service'],
      ['INTF', 'Interface'],
      ['HRMS', 'HRMS'],
      ['SYS', 'System'],
    ]),
    TAXABLE_FLAG: list('TAXABLE_FLAG', [
      ['Y', 'Yes'],
      ['N', 'No'],
      ['PARTLY', 'Partly'],
    ]),
    YES_NO: list('YES_NO', [
      ['Y', 'Yes'],
      ['N', 'No'],
    ]),
  },
};

function routes(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
  return backend({
    'GET /v1/reference/lists': () => LISTS,
    'GET /v1/pay-components?': (url: string) =>
      url.includes('class=LABOUR')
        ? paged([LABOUR], 1, url)
        : paged(
            [ROW, { ...ROW, payCode: 912, name: 'Penalty recovery', type: 'DEDUCTION', pending: true }],
            2,
            url,
          ),
    'GET /v1/pay-components/101/usages': () => [
      { consumerType: 'RULE', consumerKey: 'DA.STAFF', note: null },
      { consumerType: 'LIST', consumerKey: 'PENALTY_RECOVERY', note: 'head' },
    ],
    ...over,
  });
}

/** The Staff table once its rows have come. */
async function staffTable() {
  const table = await screen.findByRole('table', { name: t('m07w.staff') });
  await within(table).findAllByRole('button');
  return table;
}

async function fill(code: string) {
  await userEvent.type(screen.getByLabelText(new RegExp(`^${t('m07w.code')}`)), code);
  await userEvent.type(screen.getByLabelText(new RegExp(`^${t('m07w.name')}`)), 'Demo washing allowance');
  await userEvent.type(screen.getByLabelText(new RegExp(`^${t('m07w.basis')}`)), 'COMP.BASIS.FLAT');
  await userEvent.selectOptions(
    await screen.findByLabelText(new RegExp(`^${t('m07w.source')}`), { selector: '#m07-source' }),
    'RULE',
  );
  await userEvent.selectOptions(
    screen.getByLabelText(new RegExp(`^${t('m07w.taxable')}`), { selector: '#m07-taxable' }),
    'Y',
  );
  await userEvent.selectOptions(
    screen.getByLabelText(new RegExp(`^${t('m07w.cpf')}`), { selector: '#m07-cpf' }),
    'Y',
  );
  await userEvent.selectOptions(screen.getByLabelText(t('m07.type'), { selector: '#m07-type' }), 'EARNING');
  await userEvent.type(screen.getByLabelText(t('m07.account')), 'NA-4101');
  await userEvent.click(screen.getByRole('checkbox', { name: t('m07.payrollType.STAFF') }));
  await userEvent.type(screen.getByLabelText(t('m07.from')), '2026-11-01');
  await userEvent.type(screen.getByLabelText(t('m07.reason'), { selector: '#m07-reason' }), 'By circular');
}

describe('M-07 component catalogue', () => {
  it('SRS_G_42_theCatalogueIsFilteredOnTheServerAndACodeSaysWhereItIsUsed', async () => {
    const calls = routes();
    wrap(<ComponentCatalogue />, me(ALL));
    const table = await staffTable();
    expect(table).toHaveTextContent('Basic Pay');
    expect(table).toHaveTextContent(t('m07.pending'));
    const labour = await screen.findByRole('table', { name: t('m07w.labour') });
    expect(await within(labour).findByText('Minimum Guaranteed Wage')).toBeInTheDocument();
    expect(labour).toHaveTextContent(
      `${t('m07.payrollType.DPS_LABOUR')}, ${t('m07.payrollType.NWNP_LABOUR')}`,
    );
    await userEvent.selectOptions(
      await screen.findByLabelText(t('m07.cpfBase'), { selector: 'select[aria-label]' }),
      'Y',
    );
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('class=LABOUR') && c.url.includes('cpf=Y'))).toBe(true),
    );
    await userEvent.click(within(table).getByRole('button', { name: '101' }));
    const panel = await screen.findByRole('region', { name: t('m07.detail', [101]) });
    expect(await within(panel).findByRole('list', { name: t('m07.whereUsed') })).toHaveTextContent(
      'DA.STAFF',
    );
    await userEvent.click(within(panel).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('region', { name: t('m07.detail', [101]) })).toBeNull();
  });

  it('SRS_G_42_aNewComponentIsSentForApprovalAndATakenCodeIsRefused', async () => {
    let refuse = true;
    const calls = routes({
      'POST /v1/pay-components': () =>
        refuse
          ? problem(409, 'MST-409-COMPONENT-CODE', [101])
          : { status: 202, body: { id: 5, status: 'PENDING_APPROVAL' } },
    });
    wrap(<ComponentCatalogue />, me(ALL));
    await staffTable();
    const send = screen.getByRole('button', { name: t('m07w.save') });
    expect(send).toBeDisabled();
    await fill('101');
    await userEvent.type(screen.getByLabelText(t('m07w.elig')), 'HILL_ALLOW_FLAG');
    // SRS-LV-15 / BR-LEAVE-007 (US-GAP-298): subject to LWP, marked on M-07.
    await userEvent.click(screen.getByRole('checkbox', { name: t('m07.subjectToLwp') }));
    expect(send).toBeEnabled();
    await userEvent.click(send);
    expect(await screen.findByText(t('error.MST-409-COMPONENT-CODE', [101]))).toBeInTheDocument();
    refuse = false;
    await userEvent.click(send);
    expect(await screen.findByText(t('m07.sent'))).toBeInTheDocument();
    const body = calls.filter((c) => c.method === 'POST').at(-1)?.body;
    expect(body).toMatchObject({
      payCode: 101,
      type: 'EARNING',
      payrollTypes: ['STAFF'],
      payslipGroup: null,
      basisRuleKey: 'COMP.BASIS.FLAT',
      eligibilityRuleKey: 'HILL_ALLOW_FLAG',
      inCpfBase: true,
      subjectToLwp: true,
    });
  });

  it('SRS_l1907_DriverBasisIsMandatoryAndTheEligibilityFlagIsAFlagCode', () => {
    expect(componentProblems(EMPTY_COMPONENT)).toContain('m07.bad.basis');
    expect(componentProblems(EMPTY_COMPONENT)).toContain('m07.bad.cpf');
    const named = { ...EMPTY_COMPONENT, basisRuleKey: 'COMP.BASIS.FLAT', inCpfBase: 'N' as const };
    expect(componentProblems(named)).not.toContain('m07.bad.basis');
    expect(componentProblems(named)).not.toContain('m07.bad.cpf');
    // SC-06-0028: optional, and a drawn wildcard flag is a flag.
    expect(componentProblems(named)).not.toContain('m07.bad.elig');
    expect(componentProblems({ ...named, eligibilityRuleKey: 'SPL_PAY_*' })).not.toContain('m07.bad.elig');
    expect(componentProblems({ ...named, eligibilityRuleKey: 'free text' })).toContain('m07.bad.elig');
  });

  it('FAP_14_aRevisionMayAddButNotRemoveAPayrollType', async () => {
    const calls = routes({
      'POST /v1/pay-components/101/revisions': () => ({
        status: 202,
        body: { id: 6, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<ComponentCatalogue />, me(ALL));
    const table = await staffTable();
    await userEvent.click(within(table).getByRole('button', { name: '101' }));
    await userEvent.click(await screen.findByRole('button', { name: t('m07.revise') }));
    expect(screen.getByLabelText(new RegExp(`^${t('m07w.code')}`))).toBeDisabled();
    await userEvent.type(screen.getByLabelText(t('m07.from')), '2026-11-01');
    await userEvent.type(screen.getByLabelText(t('m07.reason'), { selector: '#m07-reason' }), 'Extend');
    await userEvent.click(screen.getByRole('checkbox', { name: t('m07.payrollType.STAFF') }));
    const send = screen.getByRole('button', { name: t('m07w.save') });
    expect(send).toBeDisabled();
    expect(screen.getByRole('list', { name: t('m07.problems') })).toHaveTextContent(t('m07.bad.removeType'));
    await userEvent.click(screen.getByRole('checkbox', { name: t('m07.payrollType.STAFF') }));
    await userEvent.click(screen.getByRole('checkbox', { name: t('m07.payrollType.DPS_LABOUR') }));
    await userEvent.click(send);
    expect(await screen.findByText(t('m07.sent'))).toBeInTheDocument();
    expect(calls.find((c) => c.url.includes('/revisions'))?.body).toMatchObject({
      payCode: 101,
      payrollTypes: ['STAFF', 'DPS_LABOUR'],
      // US-GAP-298: the revision carries the code's LWP attribute as it stands.
      subjectToLwp: true,
    });
  });

  it('SRS_G_42_aCodeIsRetiredWithItsLastDayAndARefusalStays', async () => {
    let refuse = true;
    const calls = routes({
      'POST /v1/pay-components/101/retirements': () =>
        refuse
          ? problem(409, 'MST-409-COMPONENT-PENDING', [101])
          : { status: 202, body: { id: 7, status: 'PENDING_APPROVAL' } },
    });
    wrap(<ComponentCatalogue />, me(ALL));
    const table = await staffTable();
    await userEvent.click(within(table).getByRole('button', { name: '101' }));
    const panel = await screen.findByRole('region', { name: t('m07.detail', [101]) });
    const retire = within(panel).getByRole('button', { name: t('action.retire') });
    expect(retire).toBeDisabled();
    await userEvent.type(within(panel).getByLabelText(t('m07.lastDay')), '2026-10-01');
    await userEvent.type(within(panel).getByLabelText(t('m07.reason')), 'Merged into DA');
    await userEvent.click(retire);
    expect(await screen.findByText(t('error.MST-409-COMPONENT-PENDING', [101]))).toBeInTheDocument();
    refuse = false;
    await userEvent.click(retire);
    expect(await screen.findByText(t('m07.sent'))).toBeInTheDocument();
    expect(calls.find((c) => c.url.includes('/retirements'))?.body).toEqual({
      lastDay: '2026-10-01',
      reason: 'Merged into DA',
    });
  });

  it('SRS_G_42_aPendingCodeCannotBeChangedAndHistoryOpensOnTheSelectedRow', async () => {
    routes({ 'GET /v1/pay-components/912/usages': () => [] });
    wrap(<ComponentCatalogue />, me(ALL));
    const table = await staffTable();
    // Nothing selected: the drawn buttons say so, nothing is read.
    await userEvent.click(screen.getByRole('button', { name: t('m07w.whereUsed') }));
    expect(await screen.findByText(t('m07w.pick'))).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: t('m07w.history') }));
    await userEvent.click(within(table).getByText('Penalty recovery'));
    // SRS-20-29 (US-03-07 AC1): the drawn History opens the selected code's change history.
    expect(screen.getByRole('button', { name: t('history.for', ['912 · Penalty recovery']) })).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('m07w.whereUsed') }));
    const panel = await screen.findByRole('region', { name: t('m07.detail', [912]) });
    expect(await within(panel).findByText(t('m07.unused'))).toBeInTheDocument();
    for (const name of [t('m07.revise'), t('action.retire')]) {
      const off = within(panel).getByRole('button', { name });
      expect(off).toBeDisabled();
      expect(off).toHaveAttribute('data-why', t('m07.pending'));
    }
  });

  it('SRS_G_42_everyFilterGoesToTheServerAndARetiredCodeShowsItsLastDay', async () => {
    const calls = routes({
      'GET /v1/pay-components?': (url: string) =>
        paged(
          [
            {
              ...ROW,
              payCode: 140,
              name: 'Night shift',
              inCpfBase: false,
              retired: true,
              effectiveTo: '2026-03-31',
              naturalAccount: null,
              payslipGroup: null,
            },
          ],
          1,
          url,
        ),
      'GET /v1/pay-components/140/usages': () => [],
    });
    wrap(<ComponentCatalogue />, me(ALL));
    const table = await staffTable();
    expect(table).toHaveTextContent(t('m07.retired'));
    const view = await screen.findByRole('combobox', { name: t('m07w.allComponents') });
    await userEvent.selectOptions(view, 'DEDUCTION');
    await userEvent.selectOptions(
      screen.getByLabelText(t('m07.source'), { selector: 'select[aria-label]' }),
      'HRMS',
    );
    await userEvent.selectOptions(
      screen.getByLabelText(t('m07.taxable'), { selector: 'select[aria-label]' }),
      'N',
    );
    await userEvent.selectOptions(view, 'STAFF');
    await userEvent.selectOptions(view, '');
    await userEvent.selectOptions(
      screen.getByLabelText(t('m07.source'), { selector: 'select[aria-label]' }),
      '',
    );
    await userEvent.selectOptions(
      screen.getByLabelText(t('m07.taxable'), { selector: 'select[aria-label]' }),
      '',
    );
    await userEvent.selectOptions(
      screen.getByLabelText(t('m07.cpfBase'), { selector: 'select[aria-label]' }),
      '',
    );
    expect(calls.some((c) => c.url.includes('type=DEDUCTION'))).toBe(true);
    expect(calls.some((c) => c.url.includes('source=HRMS'))).toBe(true);
    expect(calls.some((c) => c.url.includes('taxable=N'))).toBe(true);
    await userEvent.click(within(await staffTable()).getByRole('button', { name: '140' }));
    const panel = await screen.findByRole('region', { name: t('m07.detail', [140]) });
    // M-07 field table l.1915: day-month-year (UI review M-07 #6; the app's date()), never the raw ISO date.
    expect(panel).toHaveTextContent('31 Mar 2026');
    expect(panel).not.toHaveTextContent('2026-03-31');
    expect(panel).toHaveTextContent('—');
  });

  it('wireframe_theDrawnViewEmptiesTheOtherTableAndTheListSearchGoesToTheServer', async () => {
    const calls = routes();
    wrap(<ComponentCatalogue />, me(['M-07:VIEW', 'M-07:EXPORT']));
    await staffTable();
    const view = await screen.findByRole('combobox', { name: t('m07w.allComponents') });
    await userEvent.selectOptions(view, 'LABOUR');
    expect(await screen.findByRole('table', { name: t('m07w.staff') })).toHaveTextContent(t('m07.none'));
    await userEvent.selectOptions(view, 'STAFF');
    expect(await screen.findByRole('table', { name: t('m07w.labour') })).toHaveTextContent(t('m07.none'));
    await userEvent.type(screen.getByRole('searchbox', { name: t('wf.searchInList') }), 'wash');
    await waitFor(() => expect(calls.some((c) => c.url.includes('q=wash'))).toBe(true));
    expect(await exportedUrl()).toContain('/v1/pay-components/export?');
  });

  it('SRS_G_25_aViewerSeesEveryActionDisabledWithItsReasonAndNothingShowsWithoutView', async () => {
    routes();
    const { unmount } = wrap(<ComponentCatalogue />, me(['M-07:VIEW']));
    const table = await staffTable();
    const create = screen.getByRole('button', { name: t('m07w.new') });
    expect(create).toBeDisabled();
    expect(create).toHaveAttribute('data-why', t('m07w.why.create'));
    // Drawn enabled for every role: pressing it says why and sends nothing (SRS-G-25, OD-135).
    const exportAct = screen.getByRole('button', { name: t('wf.export') });
    expect(exportAct).toBeEnabled();
    expect(exportAct).toHaveAttribute('data-guard', t('m07w.why.export'));
    await userEvent.click(exportAct);
    expect(await screen.findByText(t('m07w.why.export'))).toBeInTheDocument();
    // The drawn card shows its heading only for a viewer.
    expect(screen.getByRole('heading', { name: t('m07w.newStaff') })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('m07w.save') })).toBeNull();
    await userEvent.click(within(table).getByRole('button', { name: '101' }));
    const panel = await screen.findByRole('region', { name: t('m07.detail', [101]) });
    expect(within(panel).getByRole('button', { name: t('m07.revise') })).toHaveAttribute(
      'data-why',
      t('m07w.why.create'),
    );
    unmount();
    wrap(<ComponentCatalogue />, me([]));
    expect(screen.queryByRole('table', { name: t('m07w.staff') })).toBeNull();
  });

  it('OD_135_theSystemAdministratorSeesTheFormAsDrawn_SaveSaysWhyAndNothingIsSent', async () => {
    const calls = routes();
    wrap(<ComponentCatalogue />, me(['M-07:VIEW'], { role: 'SYSTEM_ADMINISTRATOR' }));
    await staffTable();
    const code = screen.getByLabelText(new RegExp(`^${t('m07w.code')}`));
    expect(code).toBeEnabled();
    await userEvent.type(code, '777');
    expect(screen.getByRole('button', { name: t('m07w.new') })).toHaveAttribute(
      'data-guard',
      t('m07w.why.create'),
    );
    const save = screen.getByRole('button', { name: t('m07w.save') });
    expect(save).toBeEnabled();
    expect(save).toHaveAttribute('data-guard', t('m07w.why.create'));
    await userEvent.click(save);
    expect(await screen.findByText(t('m07w.why.create'))).toBeInTheDocument();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });
});
