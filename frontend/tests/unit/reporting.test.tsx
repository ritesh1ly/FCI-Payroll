import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MisReports, ReportCatalogue } from '@/modules/reporting';
import { summaryQuery } from '@/modules/reporting/api/reportApi';
import { MEMBER_STATUSES, exportBlockedWhy, modelBlocked } from '@/modules/reporting/business/reportRules';
import { businessToday } from '@/shared/time';
import { backend, exportedUrl, me, paged, problem, sent, t, wrap } from './support/harness';

/** O-04 MIS & Statutory Reports and M-12 Report Catalogue (plan 15; SRS-18-8, SRS-18-10, SRS-18-23, SRS-18-24, SRS-18-28). */

afterEach(() => vi.unstubAllGlobals());

/** G59: the summary runs only on a press; the small Generate is drawn for every O-04 role. */
async function generate() {
  const button = await screen.findByTestId('o04-generate-table');
  await vi.waitFor(() => expect(button).toBeEnabled());
  await userEvent.click(button);
}

/** The closed section that keeps the Component-wise Summary. */
const summarySection = () => within(screen.getByTestId('o04-summary-section'));

/** M-12: the definitions and their versions stay behind the drawn Version history. */
async function openVersions() {
  await userEvent.click(await screen.findByRole('button', { name: t('m12w.act.versions') }));
}

const ENTRIES = [
  {
    key: 'COMPONENT_WISE_SUMMARY',
    name: 'Component-wise Summary',
    family: 'Control-Audit-MIS',
    section: 'A.4.6',
    domain: 'REPORT',
    producingScreen: 'O-04',
    payrollTypes: 'All',
    periodicity: 'Monthly',
    lockRequirement: 'POST_LOCK',
    versionNo: 1,
    layoutFormat: 'EXCEL',
    blockedReason: null,
    status: 'AVAILABLE',
  },
  {
    key: 'NPS_DEDUCTION_REPORT',
    name: 'NPS Deduction Report',
    family: 'Statutory & Tax',
    section: 'A.4.2',
    domain: 'REPORT',
    producingScreen: 'I-14',
    payrollTypes: null,
    periodicity: null,
    lockRequirement: 'POST_LOCK',
    // A definition without a version yet.
    versionNo: null,
    layoutFormat: 'EXCEL',
    blockedReason: null,
    status: 'PRODUCED_ELSEWHERE',
  },
  {
    key: 'SALARY_CONTROL_REGISTER',
    name: 'Salary Control Register',
    family: 'Operational',
    section: 'A.4.1',
    domain: 'REPORT',
    producingScreen: 'O-04',
    payrollTypes: 'All',
    periodicity: 'Monthly',
    lockRequirement: 'NONE',
    versionNo: 1,
    layoutFormat: null,
    blockedReason: 'Layout not supplied',
    status: 'BLOCKED',
  },
];

const SUMMARY = {
  yymm: '202610',
  level: 'UNIT',
  provisional: false,
  earnings: 1000,
  deductions: 100,
  postedGross: 1000,
  postedDeductions: 100,
  controlTotalsMatch: true,
  lines: [
    {
      levelCode: 'DH001',
      levelName: 'Delhi',
      payrollType: 'STAFF',
      payCode: 101,
      component: 'Basic Pay',
      componentType: 'EARNING',
      amount: 1000,
      members: 1,
    },
  ],
};

describe('O-04 MIS & Statutory Reports', () => {
  it('SRS-18-24 / SRS-18-28: the catalogue says where each output is made; the summary reconciles', async () => {
    const calls = backend({
      'GET /v1/reports/catalogue?': (url) => paged(ENTRIES, 3, url),
      'GET /v1/report-definitions?': (url) => paged(ENTRIES, 3, url),
      'GET /v1/reports/component-summary?': () => SUMMARY,
    });
    wrap(<MisReports />, me(['O-04:VIEW', 'O-04:EXPORT']));
    const catalogue = await screen.findByRole('table', { name: t('o04.catalogue') });
    expect(await within(catalogue).findByText(t('o04.status.AVAILABLE'))).toBeTruthy();
    expect(within(catalogue).getByText(t('o04.status.PRODUCED_ELSEWHERE', ['I-14']))).toBeTruthy();
    expect(within(catalogue).getByText('Layout not supplied')).toBeTruthy();
    await generate();
    const summary = await screen.findByRole('table', { name: t('o04.summary') });
    expect(within(summary).getByText('DH001 - Delhi')).toBeTruthy();
    expect(screen.getByRole('status').textContent).toContain(t('o04.final'));
    expect(summarySection().getByRole('button', { name: /export/i })).toBeEnabled();
    await userEvent.selectOptions(screen.getByLabelText(t('o04.level')), 'ZONE');
    await screen.findByRole('option', { name: t('m12w.family.Operational') });
    await userEvent.selectOptions(screen.getByLabelText(t('o04.family')), 'Operational');
    await generate();
    await vi.waitFor(() => expect(calls.some((c) => c.url.includes('level=ZONE'))).toBe(true));
    expect(calls.some((c) => c.url.includes('family=Operational'))).toBe(true);
    await userEvent.type(screen.getByLabelText(t('o04.search')), 'Salary');
    expect(screen.getByLabelText(t('o04.search'))).toHaveValue('Salary');
  });

  it('SRS-18-8 / SRS-18-23: a provisional month says so and a mismatch disables the export', async () => {
    backend({
      'GET /v1/reports/catalogue?': (url) => paged([], 0, url),
      'GET /v1/report-definitions?': (url) => paged([], 0, url),
      'GET /v1/reports/component-summary?': () => ({
        ...SUMMARY,
        provisional: true,
        controlTotalsMatch: false,
        postedGross: 1001,
        lines: [],
      }),
    });
    wrap(<MisReports />, me(['O-04:VIEW', 'O-04:EXPORT']));
    await generate();
    expect(await screen.findByText(t('o04.noResults'))).toBeTruthy();
    expect(screen.getByRole('status').textContent).toContain(t('o04.provisional'));
    const exportButton = summarySection().getByRole('button', { name: t('action.export') });
    expect(exportButton).toBeDisabled();
    expect(exportButton.getAttribute('title')).toBe(t('o04.why.mismatch'));
    await userEvent.clear(screen.getByLabelText(t('o04.month')));
    await userEvent.type(screen.getByLabelText(t('o04.month')), '2026');
    expect(screen.getAllByText(t('o04.why.month')).length).toBeGreaterThan(0);
  });

  it('SRS-18-8 (review 15-6): the export says it waits for the summary, or that the summary failed, never "totals do not match"', async () => {
    expect(exportBlockedWhy(false, { isPending: false, isError: true })).toBe('o04.why.month');
    expect(exportBlockedWhy(true, { isPending: true, isError: false })).toBe('o04.why.loading');
    expect(exportBlockedWhy(true, { isPending: false, isError: true })).toBe('o04.why.error');
    expect(exportBlockedWhy(true, { isPending: false, isError: false })).toBe('o04.why.mismatch');
    backend({
      'GET /v1/reports/catalogue?': (url) => paged([], 0, url),
      'GET /v1/report-definitions?': (url) => paged([], 0, url),
      'GET /v1/reports/component-summary?': () => problem(503, 'COM-503-UNAVAILABLE'),
    });
    wrap(<MisReports />, me(['O-04:VIEW', 'O-04:EXPORT']));
    await generate();
    expect(await screen.findByText(t('error.COM-503-UNAVAILABLE'))).toBeTruthy();
    const exportButton = summarySection().getByRole('button', { name: t('action.export') });
    expect(exportButton).toBeDisabled();
    expect(exportButton.getAttribute('title')).toBe(t('o04.why.error'));
  });

  it('SRS-18-8 (review 15-6): while the summary loads the export waits with that reason', async () => {
    backend({
      'GET /v1/reports/catalogue?': (url) => paged([], 0, url),
      'GET /v1/report-definitions?': (url) => paged([], 0, url),
    });
    // The read held for good: a route's answer is sent at once, so the fetch itself never settles here.
    const answered = globalThis.fetch;
    vi.stubGlobal('fetch', (url: string, init?: RequestInit) =>
      url.includes('component-summary?') ? new Promise(() => undefined) : answered(url, init),
    );
    wrap(<MisReports />, me(['O-04:VIEW', 'O-04:EXPORT']));
    await generate();
    const exportButton = summarySection().getByRole('button', { name: t('action.export') });
    expect(exportButton).toBeDisabled();
    expect(exportButton.getAttribute('title')).toBe(t('o04.why.loading'));
  });

  it('SRS-18-12: a refusal is shown', async () => {
    backend({
      'GET /v1/reports/catalogue?': (url) => paged([], 0, url),
      'GET /v1/report-definitions?': (url) => paged([], 0, url),
      'GET /v1/reports/component-summary?': () => problem(422, 'REP-422-PARAMETERS', ['month, level']),
    });
    wrap(<MisReports />, me(['O-04:VIEW']));
    await generate();
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(summarySection().queryByRole('button', { name: /export/i })).toBeNull();
  });
});

describe('O-04 summary parameters and output standard (SRS-18-12, US-GAP-37)', () => {
  it('SRS-18-12: the query carries the month, level and every parameter chosen; none left out is sent', () => {
    expect(MEMBER_STATUSES).toEqual(['BOTH', 'CURRENT', 'SEPARATED']);
    expect(
      summaryQuery('202610', 'UNIT', { officeId: null, payrollType: '', category: '', memberStatus: 'BOTH' }),
    ).toBe('yymm=202610&level=UNIT&memberStatus=BOTH');
    expect(
      summaryQuery('202610', 'ZONE', {
        officeId: 7,
        payrollType: 'STAFF',
        category: 'III',
        memberStatus: 'CURRENT',
      }),
    ).toBe('yymm=202610&level=ZONE&officeId=7&payrollType=STAFF&category=III&memberStatus=CURRENT');
  });

  it('SRS-18-12 (a) / SRS-G-40: the user narrows the summary and takes the file in Hindi', async () => {
    const calls = backend({
      'GET /v1/reports/catalogue?': (url) => paged([], 0, url),
      'GET /v1/report-definitions?': (url) => paged([], 0, url),
      'GET /v1/reports/component-summary/options?': () => ({
        offices: [{ id: 3, label: 'DH001 - Delhi' }],
        payrollTypes: ['STAFF', 'DPS_LABOUR'],
        categories: ['III'],
      }),
      'GET /v1/reports/component-summary?': () => SUMMARY,
    });
    wrap(<MisReports />, me(['O-04:VIEW', 'O-04:EXPORT']));
    await generate();
    await screen.findByRole('table', { name: t('o04.summary') });
    await screen.findByRole('option', { name: 'DH001 - Delhi' });
    await userEvent.selectOptions(screen.getByLabelText(t('o04.officeFilter')), '3');
    await userEvent.selectOptions(screen.getByLabelText(t('o04.payrollType')), 'STAFF');
    await userEvent.selectOptions(screen.getByLabelText(t('o04.category')), 'III');
    await userEvent.selectOptions(screen.getByLabelText(t('o04.memberStatus')), 'SEPARATED');
    await generate();
    await vi.waitFor(() =>
      expect(
        calls.some((c) => c.url.includes('officeId=3&payrollType=STAFF&category=III&memberStatus=SEPARATED')),
      ).toBe(true),
    );
    expect(screen.getByRole('option', { name: t('o04.sum.members.CURRENT') })).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText(t('o04.language')), 'hi');
    const url = await exportedUrl('CSV', undefined, summarySection());
    expect(url).toContain('officeId=3');
    expect(url).toContain('lang=hi');
    await userEvent.selectOptions(screen.getByLabelText(t('o04.officeFilter')), '');
    await userEvent.selectOptions(screen.getByLabelText(t('o04.language')), 'en');
    await generate();
    const english = await exportedUrl('CSV', undefined, summarySection());
    expect(english).not.toContain('officeId');
    expect(english).toContain('lang=en');
  });
});

describe('O-04 default month and reconciliation (g4)', () => {
  it('SRS l.3501: the screen opens on the latest locked month; a month picked by the user stands and clears the office', async () => {
    const calls = backend({
      'GET /v1/reports/catalogue?': (url) => paged([], 0, url),
      'GET /v1/report-definitions?': (url) => paged([], 0, url),
      'GET /v1/reports/component-summary/latest-locked': () => ({ yymm: '202608' }),
      'GET /v1/reports/component-summary/options?': () => ({
        offices: [{ id: 3, label: 'DH001 - Delhi' }],
        payrollTypes: ['STAFF'],
        categories: [],
      }),
      'GET /v1/reports/component-summary?': () => SUMMARY,
    });
    wrap(<MisReports />, me(['O-04:VIEW']));
    await generate();
    await vi.waitFor(() =>
      expect(calls.some((c) => c.url.includes('component-summary?yymm=202608'))).toBe(true),
    );
    await screen.findByRole('option', { name: 'DH001 - Delhi' });
    await userEvent.selectOptions(screen.getByLabelText(t('o04.officeFilter')), '3');
    fireEvent.change(screen.getByLabelText(t('o04.month')), { target: { value: '2026-07' } });
    await generate();
    await vi.waitFor(() =>
      expect(calls.some((c) => c.url.includes('component-summary?yymm=202607'))).toBe(true),
    );
    expect(screen.getByLabelText(t('o04.officeFilter'))).toHaveValue('');
  });

  it('SRS l.3501: with no month locked yet the business month is shown', async () => {
    const calls = backend({
      'GET /v1/reports/catalogue?': (url) => paged([], 0, url),
      'GET /v1/report-definitions?': (url) => paged([], 0, url),
      'GET /v1/reports/component-summary/latest-locked': () => ({ yymm: null }),
      'GET /v1/reports/component-summary?': () => SUMMARY,
    });
    wrap(<MisReports />, me(['O-04:VIEW']));
    await generate();
    const month = new Date(Date.now() + 5.5 * 3_600_000).toISOString().slice(0, 7).replace('-', '');
    await vi.waitFor(() => expect(calls.some((c) => c.url.includes(`yymm=${month}`))).toBe(true));
  });

  it('SRS-18-8 (l.3505): the deductions reconcile on screen beside the earnings', async () => {
    backend({
      'GET /v1/reports/catalogue?': (url) => paged([], 0, url),
      'GET /v1/report-definitions?': (url) => paged([], 0, url),
      'GET /v1/reports/component-summary?': () => SUMMARY,
    });
    wrap(<MisReports />, me(['O-04:VIEW']));
    await generate();
    expect(await screen.findByTestId('o04-deductions')).toBeTruthy();
    expect(screen.getByTestId('o04-deductions').textContent).toContain('100');
  });
});

describe('M-12 Report Catalogue', () => {
  it('SRS-18-10: the Report Administrator proposes a version of a definition', async () => {
    const calls = backend({
      'GET /v1/reports/catalogue?': (url) => paged(ENTRIES, 3, url),
      'GET /v1/report-definitions?': (url) => paged(ENTRIES, 3, url),
      'GET /v1/report-definitions/SALARY_CONTROL_REGISTER/versions': () => [
        {
          id: 1,
          versionNo: 1,
          effectiveFrom: '2026-04-01',
          effectiveTo: null,
          layoutFormat: null,
          periodLockRequired: false,
          storeMode: 'REGENERATE',
          approvalState: 'APPROVED',
          blocked: true,
          blockedReason: 'Layout not supplied',
          createdBy: 'system.migration',
          createdAt: '2026-04-01T00:00:00',
        },
        {
          id: 2,
          versionNo: 0,
          effectiveFrom: '2025-04-01',
          effectiveTo: '2026-03-31',
          layoutFormat: 'EXCEL',
          periodLockRequired: false,
          storeMode: 'REGENERATE',
          approvalState: 'APPROVED',
          blocked: false,
          blockedReason: null,
          createdBy: 'system.migration',
          createdAt: '2025-04-01T00:00:00',
        },
      ],
      'POST /v1/report-definitions/SALARY_CONTROL_REGISTER/versions': () => ({
        status: 202,
        body: { id: 9, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<ReportCatalogue />, me(['M-12:VIEW', 'M-12:CREATE']));
    await openVersions();
    const table = await screen.findByRole('table', { name: t('screen.M-12') });
    const row = (await within(table).findByText('SALARY_CONTROL_REGISTER')).closest('tr') as HTMLElement;
    // SRS-20-29 (US-03-07 AC1): each report opens its change history.
    expect(
      within(row).getByRole('button', { name: t('history.for', ['Salary Control Register']) }),
    ).toBeTruthy();
    await userEvent.click(within(row).getByRole('button', { name: t('m12.versions') }));
    const versions = await screen.findByRole('table', {
      name: t('m12.versionsOf', ['SALARY_CONTROL_REGISTER']),
    });
    expect(within(versions).getAllByText(t('m12.state.APPROVED'))).toHaveLength(2);
    // Only the version in force is open-ended; the earlier one shows its last day.
    expect(within(versions).getAllByText(new RegExp(t('m12.open')))).toHaveLength(1);
    const propose = screen.getByRole('button', { name: t('action.m12.propose') });
    await userEvent.click(screen.getByLabelText(t('m12.blocked')));
    expect(propose).toBeDisabled();
    expect(propose.getAttribute('title')).toBe(t('m12.why.reason'));
    await userEvent.type(screen.getByLabelText(t('m12.blockedReason')), 'Layout awaited');
    await userEvent.selectOptions(screen.getByLabelText(t('m12.format')), 'EXCEL');
    await userEvent.click(screen.getByLabelText(t('m12.periodLock')));
    fireEvent.change(screen.getByLabelText(t('m12.effective')), { target: { value: businessToday() } });
    await userEvent.click(propose);
    expect(await screen.findByText(t('m12.proposed'))).toBeTruthy();
    const body = sent(calls, 'POST', '/versions').at(-1) as Record<string, unknown>;
    expect(body).toMatchObject({
      blocked: true,
      blockedReason: 'Layout awaited',
      layoutFormat: 'EXCEL',
      periodLockRequired: true,
      effectiveFrom: businessToday(),
    });
  });

  it('SRS-18-10: only the Report Administrator proposes', async () => {
    backend({
      'GET /v1/reports/catalogue?': (url) => paged(ENTRIES, 3, url),
      'GET /v1/report-definitions?': (url) => paged(ENTRIES, 3, url),
      'GET /v1/report-definitions/COMPONENT_WISE_SUMMARY/versions': () => [],
      'POST /v1/report-definitions/COMPONENT_WISE_SUMMARY/versions': () =>
        problem(422, 'REP-422-VERSION', ['PENDING']),
    });
    wrap(<ReportCatalogue />, me(['M-12:VIEW']));
    await openVersions();
    const table = await screen.findByRole('table', { name: t('screen.M-12') });
    await userEvent.click(
      (await within(table).findAllByRole('button', { name: t('m12.versions') }))[0] as HTMLElement,
    );
    const propose = await screen.findByRole('button', { name: t('action.m12.propose') });
    expect(propose).toBeDisabled();
    expect(propose.getAttribute('title')).toBe(t('reason.m12.reportAdminOnly'));
  });

  it('SRS-18-10: a refused proposal is shown', async () => {
    backend({
      'GET /v1/reports/catalogue?': (url) => paged(ENTRIES, 3, url),
      'GET /v1/report-definitions?': (url) => paged(ENTRIES, 3, url),
      'GET /v1/report-definitions/COMPONENT_WISE_SUMMARY/versions': () => [],
      'POST /v1/report-definitions/COMPONENT_WISE_SUMMARY/versions': () =>
        problem(422, 'REP-422-VERSION', ['PENDING']),
    });
    wrap(<ReportCatalogue />, me(['M-12:VIEW', 'M-12:CREATE']));
    await openVersions();
    const table2 = await screen.findByRole('table', { name: t('screen.M-12') });
    await userEvent.click(
      (await within(table2).findAllByRole('button', { name: t('m12.versions') }))[0] as HTMLElement,
    );
    await userEvent.click(await screen.findByRole('button', { name: t('action.m12.propose') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
  });

  it('SRS-18-9: the Report Administrator defines the model of a version: parameters, bindings, control totals, routing', async () => {
    const calls = backend({
      'GET /v1/reports/catalogue?': (url) => paged(ENTRIES, 3, url),
      'GET /v1/report-definitions?': (url) => paged(ENTRIES, 3, url),
      'GET /v1/report-definitions/SALARY_CONTROL_REGISTER/versions': () => [
        {
          id: 3,
          versionNo: 2,
          effectiveFrom: '2026-10-01',
          effectiveTo: null,
          layoutFormat: 'EXCEL',
          periodLockRequired: true,
          storeMode: 'STORE',
          approvalState: 'APPROVED',
          blocked: false,
          blockedReason: null,
          createdBy: 'report.admin',
          createdAt: '2026-10-01T00:00:00',
          roundingRuleKey: 'ROUND.RUPEE',
          parameters: [],
          fields: [{ label: 'Basic', binding: 'COMPONENT', reference: '101', formatMask: null }],
          controls: [],
          distribution: [],
        },
      ],
      'POST /v1/report-definitions/SALARY_CONTROL_REGISTER/versions': () =>
        problem(422, 'REP-422-VERSION', ['FIELD_UNRESOLVED']),
    });
    wrap(<ReportCatalogue />, me(['M-12:VIEW', 'M-12:CREATE']));
    await openVersions();
    const table = await screen.findByRole('table', { name: t('screen.M-12') });
    const row = (await within(table).findByText('SALARY_CONTROL_REGISTER')).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: t('m12.versions') }));
    const versions = await screen.findByRole('table', {
      name: t('m12.versionsOf', ['SALARY_CONTROL_REGISTER']),
    });
    expect(within(versions).getByText(t('m12.modelCounts', [0, 1, 0, 0]))).toBeTruthy();
    expect(within(versions).getByText('ROUND.RUPEE')).toBeTruthy();
    fireEvent.change(screen.getByLabelText(t('m12.effective')), { target: { value: '2026-12-01' } });
    await userEvent.type(screen.getByLabelText(new RegExp(t('m12.rounding'))), 'ROUND.RUPEE');
    await userEvent.click(screen.getByRole('button', { name: t('m12.addParameter') }));
    await userEvent.type(screen.getByLabelText(t('m12.parameterCode')), 'YYMM');
    await userEvent.type(screen.getByLabelText(t('m12.dataType')), 'MONTH');
    const propose = screen.getByRole('button', { name: t('action.m12.propose') });
    expect(propose.getAttribute('title')).toBe(t('m12.why.parameter'));
    await userEvent.type(screen.getByLabelText(t('m12.validationRule')), 'A locked month');
    await userEvent.click(screen.getByRole('button', { name: t('m12.addControl') }));
    await userEvent.type(screen.getByLabelText(t('m12.controlLabel')), 'Gross');
    expect(propose.getAttribute('title')).toBe(t('m12.why.computable'));
    await userEvent.click(screen.getByRole('button', { name: t('m12.addField') }));
    await userEvent.type(screen.getByLabelText(t('m12.fieldLabel')), 'Basic');
    expect(propose.getAttribute('title')).toBe(t('m12.why.field'));
    await userEvent.type(screen.getByLabelText(t('m12.reference')), '999');
    await userEvent.selectOptions(screen.getByLabelText(t('m12.binding')), 'AGGREGATE');
    await userEvent.selectOptions(screen.getByLabelText(t('m12.reconcilesAgainst')), 'BANK_ADVICE');
    await userEvent.clear(screen.getByLabelText(t('m12.tolerance')));
    await userEvent.type(screen.getByLabelText(t('m12.tolerance')), '1.5');
    await userEvent.click(screen.getByLabelText(t('m12.releaseGate')));
    await userEvent.click(screen.getByLabelText(t('m12.mandatory')));
    await userEvent.click(screen.getByRole('button', { name: t('m12.addConsumer') }));
    expect(propose.getAttribute('title')).toBe(t('m12.why.consumer'));
    await userEvent.type(screen.getByLabelText(t('m12.consumer')), 'Finance');
    await userEvent.selectOptions(screen.getByLabelText(t('m12.consumerType')), 'ORG_UNIT');
    await userEvent.selectOptions(screen.getByLabelText(t('m12.channel')), 'EMAIL');
    expect(propose).toBeEnabled();
    await userEvent.click(propose);
    expect(await screen.findByText(t('error.REP-422-VERSION', ['FIELD_UNRESOLVED']))).toBeTruthy();
    expect(sent(calls, 'POST', '/versions').at(-1)).toMatchObject({
      roundingRuleKey: 'ROUND.RUPEE',
      parameters: [{ code: 'YYMM', dataType: 'MONTH', validationRule: 'A locked month', mandatory: false }],
      fields: [{ label: 'Basic', binding: 'AGGREGATE', reference: '999' }],
      controls: [{ label: 'Gross', reconcilesAgainst: 'BANK_ADVICE', tolerance: 1.5, releaseGate: false }],
      distribution: [{ consumerName: 'Finance', consumerType: 'ORG_UNIT', channel: 'EMAIL' }],
    });
    for (const remove of screen.getAllByRole('button', { name: t('m12.remove') }))
      await userEvent.click(remove);
    expect(screen.queryByLabelText(t('m12.parameterCode'))).toBeNull();
    expect(screen.queryByLabelText(t('m12.fieldLabel'))).toBeNull();
    await userEvent.clear(screen.getByLabelText(new RegExp(t('m12.rounding'))));
    expect(propose).toBeEnabled();
  });
});

describe('M-12 proposal edges (g4)', () => {
  const ROWS = [
    {
      key: 'SALARY_CONTROL_REGISTER',
      name: 'Salary Control Register',
      versionNo: 1,
      status: 'BLOCKED',
      producingScreen: null,
    },
    { key: 'BANK_ADVICE', name: 'Bank Advice', versionNo: 1, status: 'BLOCKED', producingScreen: null },
  ];

  it('SRS-18-9: a lowercase rounding key or data type, which the server refuses, blocks Propose', () => {
    const base = {
      layoutFormat: null,
      periodLockRequired: false,
      storeMode: 'REGENERATE',
      blocked: false,
      blockedReason: null,
      effectiveFrom: '2026-11-03',
    } as never;
    expect(modelBlocked({ ...(base as object), roundingRuleKey: 'round.half' } as never)).toBe(
      'm12.why.rounding',
    );
    expect(modelBlocked({ ...(base as object), roundingRuleKey: 'ROUND.HALF_UP' } as never)).toBeNull();
    expect(
      modelBlocked({
        ...(base as object),
        parameters: [{ code: 'MONTH', dataType: 'date', validationRule: 'YYYYMM' }],
      } as never),
    ).toBe('m12.why.parameter');
  });

  it('Review 15-13 / 15-14: another report opens with an empty proposal; a versions failure is shown', async () => {
    backend({
      'GET /v1/report-definitions?': (url) => paged(ROWS, 2, url),
      'GET /v1/report-definitions/SALARY_CONTROL_REGISTER/versions': () => [],
      'GET /v1/report-definitions/BANK_ADVICE/versions': () => problem(503, 'COM-503-UNAVAILABLE'),
    });
    wrap(<ReportCatalogue />, me(['M-12:VIEW', 'M-12:CREATE']));
    await openVersions();
    const table = await screen.findByRole('table', { name: t('screen.M-12') });
    const first = (await within(table).findByText('SALARY_CONTROL_REGISTER')).closest('tr') as HTMLElement;
    await userEvent.click(within(first).getByRole('button', { name: t('m12.versions') }));
    await userEvent.click(await screen.findByLabelText(t('m12.blocked')));
    expect(screen.getByLabelText(t('m12.blocked'))).toBeChecked();
    const second = within(table).getByText('BANK_ADVICE').closest('tr') as HTMLElement;
    await userEvent.click(within(second).getByRole('button', { name: t('m12.versions') }));
    await vi.waitFor(() => expect(screen.getByLabelText(t('m12.blocked'))).not.toBeChecked());
    expect(await screen.findByText(t('error.COM-503-UNAVAILABLE'))).toBeTruthy();
  });
});

describe('O-04 report choice, member range and sort (g4 UI-G4)', () => {
  it('SRS-18-12: the member range and the amount order reach the summary and the export; blank ends are none', () => {
    const q = summaryQuery('202610', 'UNIT', {
      officeId: null,
      payrollType: '',
      category: '',
      memberStatus: 'BOTH',
      fromEis: ' 104000 ',
      toEis: '',
      sort: 'AMOUNT',
    });
    expect(q).toContain('fromEis=104000');
    expect(q).not.toContain('toEis');
    expect(q).toContain('sort=AMOUNT');
    expect(
      summaryQuery('202610', 'UNIT', {
        officeId: null,
        payrollType: '',
        category: '',
        memberStatus: 'BOTH',
        sort: 'PAY_CODE',
      }),
    ).not.toContain('sort');
  });

  it('O-04 l.3500: the report is chosen from the catalogue; one not produced here cannot be generated and says why', async () => {
    const calls = backend({
      'GET /v1/reports/catalogue?': (url) => paged(ENTRIES, ENTRIES.length, url),
      'GET /v1/report-definitions?': (url) => paged([], 0, url),
      'GET /v1/reports/component-summary?': () => SUMMARY,
    });
    wrap(<MisReports />, me(['O-04:VIEW']));
    const button = await screen.findByTestId('o04-generate');
    await screen.findByRole('option', { name: 'Salary Control Register' });
    await vi.waitFor(() => expect(button).toBeEnabled());
    const before = calls.filter((c) => c.url.includes('component-summary?')).length;
    await userEvent.click(button);
    await vi.waitFor(() =>
      expect(calls.filter((c) => c.url.includes('component-summary?')).length).toBeGreaterThan(before),
    );
    await screen.findByRole('table', { name: t('o04.summary') });
    const other = ENTRIES.find((e) => e.status !== 'AVAILABLE') as { key: string };
    await userEvent.selectOptions(screen.getByLabelText(t('o04.report')), other.key);
    expect(button).toBeDisabled();
    expect(button.getAttribute('title')).toBeTruthy();
    await userEvent.selectOptions(screen.getByLabelText(t('o04.report')), 'COMPONENT_WISE_SUMMARY');
    await userEvent.type(screen.getByLabelText(t('o04.fromEis')), '104000');
    await generate();
    await vi.waitFor(() => expect(calls.some((c) => c.url.includes('fromEis=104000'))).toBe(true));
  });
});
