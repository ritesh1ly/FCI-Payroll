import userEvent from '@testing-library/user-event';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MisReports, ReportCatalogue } from '@/modules/reporting';
import { reportChoices } from '@/modules/reporting/business/reportRules';
import { backend, me, paged, problem, referenceLists, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * O-04 MIS & Statutory Reports and M-12 Report Definition Catalogue as Wireframes v6.1 draws them (OD-121, OD-123,
 * OD-135; SRS-18-8, SRS-18-9, SRS-18-10, SRS-18-11, SRS-18-24; BR-MIS-003; FRS §23.9): drawn labels, the reason of every
 * disabled object per role, filters from the catalogue, the summary on Generate only, empty and error states.
 */
afterEach(() => vi.unstubAllGlobals());

const entry = (key: string, over: object = {}) => ({
  key,
  name: key,
  family: 'Operational',
  section: 'A.4.1',
  domain: 'REPORT',
  producingScreen: 'O-04',
  payrollTypes: 'All',
  periodicity: 'Monthly',
  lockRequirement: 'NONE',
  versionNo: 1,
  layoutFormat: 'EXCEL',
  blockedReason: null,
  status: 'AVAILABLE',
  ...over,
});

const BLOCKED = { status: 'BLOCKED', blockedReason: 'Layout not supplied', layoutFormat: null };

const ENTRIES = [
  entry('COMPONENT_WISE_SUMMARY', { name: 'Component-wise Summary', family: 'Control-Audit-MIS' }),
  entry('SALARY_CONTROL_REGISTER', { name: 'Salary Control Register', ...BLOCKED }),
  entry('BANK_ADVICE', { name: 'Bank Advice', status: 'PRODUCED_ELSEWHERE', producingScreen: 'O-02' }),
  entry('PENALTY_REPORT', { status: 'PRODUCED_ELSEWHERE', producingScreen: 'I-06' }),
];

const SUMMARY = {
  yymm: '202610',
  level: 'UNIT',
  provisional: true,
  earnings: 1000,
  deductions: 100,
  postedGross: 1000,
  postedDeductions: 90,
  controlTotalsMatch: false,
  lines: [],
};

const DEFINITIONS = {
  size: 146,
  active: 36,
  sections: [
    { section: 'A.4.1', reports: 20, blocked: 0 },
    { section: 'A.4.2', reports: 15, blocked: 15 },
    { section: 'A.11', reports: 38, blocked: 10 },
    { section: 'ADDED', reports: 3, blocked: 3 },
  ],
  families: ['Control-Audit-MIS', 'Operational'],
  payrollTypes: ['All', 'Employee', 'Employee, Departmental'],
};

const ROWS = [
  entry('LABOUR_INCENTIVE_DETAIL', {
    name: 'Labour Incentive Detail Report',
    family: 'Labour Registers',
    lockRequirement: 'POST_LOCK',
    producingScreen: 'O-03',
    ...BLOCKED,
  }),
  entry('CPF_OBCB', { producingScreen: 'O-08', ...BLOCKED }),
];

function o04(summary: () => unknown = () => SUMMARY) {
  return backend({
    'GET /v1/reports/catalogue?': (url) => paged(ENTRIES, ENTRIES.length, url),
    'GET /v1/reports/component-summary/latest-locked': () => ({ yymm: '202610' }),
    'GET /v1/reports/component-summary/options?': () => ({
      offices: [{ id: 3, label: 'DH001 - Delhi' }],
      payrollTypes: ['STAFF'],
      categories: [],
    }),
    'GET /v1/reports/component-summary?': summary,
  });
}

const M12_LISTS = referenceLists({
  REPORT_FAMILY: [
    ['Operational', 'Operational & standard'],
    ['Control-Audit-MIS', 'Control & MIS'],
    ['CPF Control & Audit', 'CPF Control & Audit'],
  ],
  REPORT_PAYROLL_TYPE: [
    ['Employee', 'Employee / Staff'],
    ['Departmental', 'Departmental'],
    ['DPS', 'DPS'],
    ['NWNP', 'NWNP'],
  ],
});

function m12(over: Record<string, () => unknown> = {}) {
  return backend({
    'GET /v1/reference/lists': () => M12_LISTS,
    'GET /v1/report-definitions/summary': () => DEFINITIONS,
    'GET /v1/report-definitions?': (url) => paged(ROWS, ROWS.length, url),
    'GET /v1/report-definitions/LABOUR_INCENTIVE_DETAIL/versions': () => [],
    'GET /v1/report-definitions/CPF_OBCB/versions': () => [],
    ...over,
  });
}

const summaryCalls = (calls: { url: string }[]) =>
  calls.filter((c) => c.url.includes('component-summary?')).length;

async function pressGenerate(testId = 'o04-generate-table') {
  const button = await screen.findByTestId(testId);
  await waitFor(() => expect(button).toBeEnabled());
  fireEvent.click(button);
}

describe('O-04 MIS & Statutory Reports as drawn', () => {
  it('OD-121: the drawn tables, panels and columns are there with their exact text', async () => {
    o04();
    wrap(<MisReports />, me(['O-04:VIEW']));
    const catalogue = await screen.findByRole('table', { name: 'Report Catalogue' });
    expect(
      within(catalogue)
        .getAllByRole('columnheader')
        .map((h) => h.firstChild?.textContent),
    ).toEqual(['Report', 'Category', 'Frequency', 'Format', 'Needs lock?', 'Status', 'Actions']);
    for (const heading of [
      'Report parameters — SCR',
      'Control-Total Reconciliation',
      'Report specification standard',
      'Reports added in FRS v0.4 (A.4.6 / A.4.7)',
      'Reports and certificates added from FCI feedback (Annexure A.11)',
      'BI dashboard and report builder (§6.10.7)',
    ]) {
      expect(screen.getByRole('heading', { name: heading })).toBeInTheDocument();
    }
    expect(screen.getByRole('table', { name: t('o04w.added') }).querySelectorAll('tbody tr')).toHaveLength(
      11,
    );
    expect(screen.getByTestId('o04-note').textContent).toContain('as per OQ-12');
  });

  it('SRS-18-11, WF-1900: Schedule and Open dashboard are drawn enabled and only say why; PDF needs O-04 EXPORT (OD-144)', async () => {
    const calls = o04();
    wrap(<MisReports />, me(['O-04:VIEW']));
    await screen.findByRole('table', { name: t('o04.catalogue') });
    for (const name of [t('o04w.act.schedule'), t('o04w.act.scheduleSm')]) {
      const button = screen.getByRole('button', { name });
      expect(button).toBeEnabled();
      expect(button).toHaveAttribute('data-guard', t('o04w.why.schedule'));
      await userEvent.click(button);
    }
    const dashboard = screen.getByRole('button', { name: t('o04w.act.dashboard') });
    expect(dashboard).toBeEnabled();
    expect(dashboard).toHaveAttribute('data-guard', t('o04w.why.bi'));
    await userEvent.click(dashboard);
    expect(calls.filter((c) => c.method !== 'GET')).toEqual([]);
    expect(screen.getByText(t('o04w.act.pdf'))).toHaveAttribute('data-why', t('o04w.why.pdfRight'));
  });

  it("SRS l.3472: the top Generate is the Maker's; the Checker has it off with the reason and keeps the one over the table", async () => {
    const calls = o04();
    const view = wrap(<MisReports />, me(['O-04:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    await pressGenerate('o04-generate-top');
    await waitFor(() => expect(summaryCalls(calls)).toBe(1));
    view.unmount();
    wrap(<MisReports />, me(['O-04:VIEW'], { roles: ['PAYROLL_CHECKER'] }));
    const top = await screen.findByRole('button', { name: t('o04w.act.generate') });
    expect(top).toBeDisabled();
    expect(top).toHaveAttribute('data-why', t('o04w.why.maker'));
    await pressGenerate();
    await waitFor(() => expect(summaryCalls(calls)).toBe(2));
  });

  it('G59: opening the screen and changing a filter generate nothing; Generate runs once, Recheck once more', async () => {
    const calls = o04();
    wrap(<MisReports />, me(['O-04:VIEW']));
    await screen.findByRole('option', { name: 'DH001 - Delhi' });
    fireEvent.change(screen.getByLabelText(t('o04.level')), { target: { value: 'ZONE' } });
    fireEvent.change(screen.getByLabelText(t('o04.officeFilter')), { target: { value: '3' } });
    fireEvent.change(screen.getByLabelText(t('o04.fromEis')), { target: { value: '104000' } });
    await screen.findByRole('table', { name: t('o04.catalogue') });
    expect(summaryCalls(calls)).toBe(0);
    await pressGenerate();
    await waitFor(() => expect(summaryCalls(calls)).toBe(1));
    await screen.findByRole('status');
    await pressGenerate('o04-recheck');
    await waitFor(() => expect(summaryCalls(calls)).toBe(2));
    // A changed parameter drops the result instead of showing or exporting it under other parameters.
    fireEvent.change(screen.getByLabelText(t('o04.level')), { target: { value: 'REGION' } });
    await waitFor(() => expect(screen.queryByRole('status')).toBeNull());
    expect(summaryCalls(calls)).toBe(2);
  });

  it('G59: before Generate the export says to generate, and the panel says nothing was generated', async () => {
    o04();
    wrap(<MisReports />, me(['O-04:VIEW', 'O-04:EXPORT']));
    await screen.findByRole('table', { name: t('o04.catalogue') });
    const section = within(screen.getByTestId('o04-summary-section'));
    await waitFor(() =>
      expect(section.getByText(t('action.export'))).toHaveAttribute('title', t('o04.why.generate')),
    );
    expect(screen.getAllByText(t('o04w.ct.notRun'))).toHaveLength(2);
  });

  it('SRS-18-8: the panel compares each total from the summary; the three unsourced lines read n/a with the reason', async () => {
    o04();
    wrap(<MisReports />, me(['O-04:VIEW']));
    await pressGenerate();
    const gross = (await screen.findByText(t('o04w.ct.gross'))).closest('.lst') as HTMLElement;
    await within(gross).findByText(t('o04w.ct.match'));
    const deductions = screen.getByText(t('o04w.ct.deductions')).closest('.lst') as HTMLElement;
    expect(within(deductions).getByText(t('o04w.ct.mismatch'))).toBeInTheDocument();
    expect(screen.getAllByText(t('o04w.ct.na'))).toHaveLength(3);
    expect(screen.getByText(t('o04w.ct.incentive.why'))).toBeInTheDocument();
    expect(screen.getByText(t('o04w.ct.bank.why'))).toBeInTheDocument();
    expect(screen.getByText(t('o04w.ct.cpf.why'))).toBeInTheDocument();
    expect(screen.getByText(t('o04w.kv.check.v'))).toBeInTheDocument();
  });

  it('SRS-18-8: a final month with totals that match reads Final and Match', async () => {
    o04(() => ({ ...SUMMARY, provisional: false, postedDeductions: 100, controlTotalsMatch: true }));
    wrap(<MisReports />, me(['O-04:VIEW']));
    await pressGenerate();
    expect(await screen.findByText(t('o04w.kv.check.final'))).toBeInTheDocument();
    expect(screen.getAllByText(t('o04w.ct.match'))).toHaveLength(2);
  });

  it('SRS-18-24: a row reads the API state; a Blocked one shows its reason and none is called Available by itself', async () => {
    o04();
    wrap(<MisReports />, me(['O-04:VIEW']));
    const table = await screen.findByRole('table', { name: t('o04.catalogue') });
    const blocked = (await within(table).findByText('Salary Control Register')).closest('tr') as HTMLElement;
    expect(within(blocked).getByText(t('o04.status.BLOCKED'))).toBeInTheDocument();
    expect(within(blocked).getByText('Layout not supplied')).toBeInTheDocument();
    expect(within(blocked).queryByText(t('o04.status.AVAILABLE'))).toBeNull();
    const elsewhere = within(table).getByText('Bank Advice').closest('tr') as HTMLElement;
    expect(within(elsewhere).getByText(t('o04.status.PRODUCED_ELSEWHERE', ['O-02']))).toBeInTheDocument();
  });

  it('SRS-18-25: an annexure row reads its catalogue entry, and one with no entry reads a dash, never Available', async () => {
    o04();
    wrap(<MisReports />, me(['O-04:VIEW']));
    const table = await screen.findByRole('table', { name: t('o04w.a11') });
    const penalty = (await within(table).findByText(t('o04w.a11.PENALTY_REPORT'))).closest(
      'tr',
    ) as HTMLElement;
    await within(penalty).findByText(t('o04.status.PRODUCED_ELSEWHERE', ['I-06']));
    const none = within(table).getByText(t('o04w.a11.INVOICE_SUMMARY')).closest('tr') as HTMLElement;
    expect(within(none).getByText('—')).toBeInTheDocument();
    expect(within(table).queryByText(t('o04.status.AVAILABLE'))).toBeNull();
  });

  it("WF-277: the Maker's Edit explains that a definition is edited on M-12; others have it off; the Checker is told there is nothing to approve", async () => {
    o04();
    const view = wrap(<MisReports />, me(['O-04:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    fireEvent.click(await screen.findByRole('button', { name: t('o04w.editName', ['Bank Advice']) }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(t('o04w.why.edit'))).toBeInTheDocument();
    expect(within(dialog).getByText(t('o04w.act.openM12')).getAttribute('href')).toContain('s=M-12');
    fireEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    fireEvent.click(screen.getByRole('button', { name: t('o04w.viewName', ['Salary Control Register']) }));
    const viewed = await screen.findByRole('dialog');
    expect(within(viewed).getByText('Layout not supplied')).toBeInTheDocument();
    expect(within(viewed).queryByText(t('o04w.why.edit'))).toBeNull();
    view.unmount();
    wrap(<MisReports />, me(['O-04:VIEW'], { roles: ['PAYROLL_CHECKER'] }));
    const edit = await screen.findByRole('button', { name: t('o04w.editName', ['Bank Advice']) });
    expect(edit).toBeDisabled();
    expect(edit).toHaveAttribute('data-why', t('o04w.why.editRole'));
    const table = screen.getByRole('table', { name: t('o04.catalogue') });
    fireEvent.click(within(table).getAllByRole('button', { name: '✔' })[0] as HTMLElement);
    expect(await screen.findByText(t('o04w.why.approve'))).toBeInTheDocument();
  });

  it('FCI-6: pension and PRMS entries are never offered, even if the API returned them; the families come from the entries', async () => {
    expect(
      reportChoices([{ key: 'PENSION_SLIP' }, { key: 'PRMS_MEDICAL' }, { key: 'COMPONENT_WISE_SUMMARY' }]),
    ).toEqual([{ key: 'COMPONENT_WISE_SUMMARY' }]);
    backend({
      'GET /v1/reports/catalogue?': (url) =>
        paged([...ENTRIES, entry('PENSION_SLIP', { name: 'Pension Slip', family: 'Member-Facing' })], 5, url),
    });
    wrap(<MisReports />, me(['O-04:VIEW']));
    const family = screen.getByLabelText(t('o04.family'));
    await within(family).findByRole('option', { name: t('m12w.family.Operational'), hidden: true });
    const report = screen.getByLabelText(t('o04.report'));
    await within(report).findByRole('option', { name: 'Bank Advice', hidden: true });
    expect(
      within(report)
        .getAllByRole('option', { hidden: true })
        .map((o) => (o as HTMLOptionElement).value),
    ).not.toContain('PENSION_SLIP');
  });

  it('SRS-18-25: the annexure Generate and Export say why and send nothing', async () => {
    const calls = o04();
    wrap(<MisReports />, me(['O-04:VIEW']));
    const table = await screen.findByRole('table', { name: t('o04w.a11') });
    const card = table.closest('.card') as HTMLElement;
    fireEvent.click(within(card).getByRole('button', { name: t('action.export') }));
    expect((await screen.findAllByText(t('o04w.why.a11'))).length).toBeGreaterThan(0);
    fireEvent.click(within(card).getByRole('button', { name: t('action.o04.generate') }));
    expect(summaryCalls(calls)).toBe(0);
    expect(calls.filter((c) => c.method !== 'GET')).toHaveLength(0);
  });

  it('OD-121: an empty catalogue says so; a month not valid names the reason on every Generate', async () => {
    backend({
      'GET /v1/reports/catalogue?': (url) => paged([], 0, url),
      'GET /v1/reports/component-summary/latest-locked': () => ({ yymm: null }),
    });
    wrap(<MisReports />, me(['O-04:VIEW']));
    expect(await screen.findByText(t('o04.none'))).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(t('o04.month')), { target: { value: '' } });
    await waitFor(() =>
      expect(screen.getByTestId('o04-generate-table')).toHaveAttribute('title', t('o04.why.month')),
    );
    expect(screen.getByTestId('o04-recheck')).toBeDisabled();
  });
});

describe('M-12 Report Definition Catalogue as drawn', () => {
  it('SRS-18-24: the tiles count the catalogue; the two without a definition read a dash and say why', async () => {
    m12();
    wrap(<ReportCatalogue />, me(['M-12:VIEW']));
    const big = async (label: string) =>
      (await screen.findByText(label)).closest('.tile')?.querySelector('.big')?.textContent;
    await screen.findByText(t('m12w.tile.sizeSub', [35, 38]));
    expect(await big(t('m12w.tile.size'))).toBe('146');
    expect(await big(t('m12w.tile.active'))).toBe('36');
    expect(await big(t('m12w.tile.layouts'))).toBe('—');
    expect(await big(t('m12w.tile.carried'))).toBe('—');
    expect(screen.getByText(t('m12w.tile.layoutsSub'))).toBeInTheDocument();
    expect(screen.getByText(t('m12w.tile.carriedSub'))).toBeInTheDocument();
  });

  it('OD-121: the catalogue by family reads the sections with their state, the static words and the foot', async () => {
    m12();
    wrap(<ReportCatalogue />, me(['M-12:VIEW']));
    const table = await screen.findByRole('table', { name: 'Report Catalogue by Family' });
    const row = (label: string) => within(table).getByText(label).closest('tr') as HTMLElement;
    await within(table).findByText(t('m12w.section.A.4.1'));
    expect(within(row(t('m12w.section.A.4.1'))).getByText(t('m12w.status.active'))).toBeInTheDocument();
    expect(within(row(t('m12w.section.A.4.2'))).getByText(t('m12w.status.allBlocked'))).toBeInTheDocument();
    expect(
      within(row(t('m12w.section.A.11'))).getByText(t('m12w.status.someBlocked', [10])),
    ).toBeInTheDocument();
    expect(within(row(t('m12w.section.A.11'))).getByText(t('m12w.committed.A.11'))).toBeInTheDocument();
    expect(within(table).getByText(t('m12w.foot'))).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: t('m12w.viewName', [t('m12w.section.A.4.1')]) }));
    expect(await screen.findByRole('dialog')).toHaveTextContent(t('m12w.examples.A.4.1'));
  });

  it('OD-123: the filters come from the M-16 lists, read in the drawn words, and ask the server', async () => {
    const calls = m12();
    wrap(<ReportCatalogue />, me(['M-12:VIEW']));
    const family = screen.getByRole('combobox', { name: t('m12w.filter.family') });
    await within(family).findByRole('option', { name: 'Control & MIS' });
    expect(within(family).getByRole('option', { name: 'CPF Control & Audit' })).toBeInTheDocument();
    fireEvent.change(family, { target: { value: 'Operational' } });
    const type = screen.getByRole('combobox', { name: t('m12w.filter.type') });
    // One drawn type, in words: never a combination the catalogue holds.
    expect(Array.from((type as HTMLSelectElement).options).map((o) => o.text)).toEqual([
      t('m12w.filter.type'),
      'Employee / Staff',
      'Departmental',
      'DPS',
      'NWNP',
    ]);
    fireEvent.change(type, { target: { value: 'Employee' } });
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'Advice' } });
    await waitFor(() =>
      expect(
        calls.some((c) =>
          /report-definitions\?family=Operational&payrollType=Employee&text=Advice/.test(c.url),
        ),
      ).toBe(true),
    );
  });

  it('SRS-18-10: the full catalogue pages on the server and Produced on links to the screen', async () => {
    const calls = m12();
    wrap(<ReportCatalogue />, me(['M-12:VIEW']));
    const table = await screen.findByRole('table', { name: t('m12w.full') });
    expect((await within(table).findByRole('link', { name: 'O-08' })).getAttribute('href')).toContain(
      's=O-08',
    );
    expect(calls.some((c) => /report-definitions\?page=0&size=20/.test(c.url))).toBe(true);
  });

  it('G31, G34, G38: Preview, Bulk activate, Export and Export catalogue are drawn, say why and send nothing', async () => {
    const calls = m12();
    wrap(<ReportCatalogue />, me(['M-12:VIEW', 'M-12:CREATE', 'M-12:APPROVE']));
    const preview = await screen.findByRole('button', { name: t('m12w.act.preview') });
    expect(preview).toBeEnabled();
    expect(preview).toHaveAttribute('data-guard', t('m12w.why.preview'));
    fireEvent.click(preview);
    expect(await screen.findByText(t('m12w.why.preview'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: t('m12w.act.bulk') })).toHaveAttribute(
      'data-guard',
      t('m12w.why.bulk'),
    );
    expect(screen.getByRole('button', { name: t('wf.export') })).toHaveAttribute(
      'data-guard',
      t('m12w.why.export'),
    );
    const exports = screen.getAllByRole('button', { name: t('m12w.act.exportCatalogue') });
    expect(exports).toHaveLength(2);
    for (const button of exports) expect(button).toHaveAttribute('data-guard', t('m12w.why.export'));
    expect(calls.some((c) => c.method !== 'GET')).toBe(false);
  });

  it('G34: Bulk activate stays disabled with the reason for a role that is no checker', async () => {
    m12();
    wrap(<ReportCatalogue />, me(['M-12:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    const bulk = await screen.findByRole('button', { name: t('m12w.act.bulk') });
    expect(bulk).toBeDisabled();
    expect(bulk).toHaveAttribute('data-why', t('m12w.why.bulk'));
  });

  it('CLR-110: New definition and Import layout say Report Administrator only without the right, and what they wait for with it', async () => {
    m12();
    const view = wrap(<ReportCatalogue />, me(['M-12:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    const plain = await screen.findByRole('button', { name: t('m12w.act.new') });
    expect(plain).toBeDisabled();
    expect(plain).toHaveAttribute('data-why', t('m12w.why.newAdminOnly'));
    expect(screen.getByRole('button', { name: t('m12w.act.import') })).toHaveAttribute(
      'data-why',
      t('m12w.why.adminShort'),
    );
    view.unmount();
    const drawn = wrap(<ReportCatalogue />, me(['M-12:VIEW'], { roles: ['SYSTEM_ADMINISTRATOR'] }));
    expect(await screen.findByRole('button', { name: t('m12w.act.new') })).toHaveAttribute(
      'data-guard',
      t('m12w.why.newAdminOnly'),
    );
    expect(screen.getByRole('button', { name: t('m12w.act.import') })).toHaveAttribute(
      'data-guard',
      t('m12w.why.adminShort'),
    );
    drawn.unmount();
    wrap(<ReportCatalogue />, me(['M-12:VIEW', 'M-12:CREATE'], { roles: ['REPORT_ADMINISTRATOR'] }));
    const admin = await screen.findByRole('button', { name: t('m12w.act.new') });
    expect(admin).toBeEnabled();
    expect(admin).toHaveAttribute('data-guard', t('m12w.why.newAwaited'));
    expect(screen.getByRole('button', { name: t('m12w.act.import') })).toHaveAttribute(
      'data-guard',
      t('m12w.why.import'),
    );
  });

  it('OD-135: the drawn System Administrator gets no new right: Edit definition and row Edit stay off with the reason; a Maker is not drawn it', async () => {
    m12();
    const view = wrap(<ReportCatalogue />, me(['M-12:VIEW'], { roles: ['SYSTEM_ADMINISTRATOR'] }));
    const edit = await screen.findByRole('button', { name: t('m12w.act.edit') });
    expect(edit).toHaveAttribute('data-guard', t('m12w.why.adminOnly'));
    fireEvent.click(edit);
    expect(await screen.findByText(t('m12w.why.adminOnly'))).toBeInTheDocument();
    expect(screen.queryByTestId('m12-versions')).toBeNull();
    const row = await screen.findByRole('button', {
      name: t('m12w.editName', [t('m12w.cpf.CPF_OBCB.name')]),
    });
    expect(row).toBeDisabled();
    expect(row).toHaveAttribute('data-why', t('m12w.why.edit'));
    view.unmount();
    wrap(<ReportCatalogue />, me(['M-12:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    await screen.findByRole('button', { name: t('m12w.act.new') });
    expect(screen.queryByRole('button', { name: t('m12w.act.edit') })).toBeNull();
  });

  it('SRS-18-9: the Report Administrator edits a definition: the button and a row open its proposal; a family row says where', async () => {
    m12();
    const view = wrap(
      <ReportCatalogue />,
      me(['M-12:VIEW', 'M-12:CREATE'], { roles: ['REPORT_ADMINISTRATOR'] }),
    );
    fireEvent.click(await screen.findByRole('button', { name: t('m12w.act.edit') }));
    expect(
      await screen.findByRole('table', { name: t('m12.versionsOf', ['LABOUR_INCENTIVE_DETAIL']) }),
    ).toBeTruthy();
    view.unmount();
    wrap(<ReportCatalogue />, me(['M-12:VIEW', 'M-12:CREATE'], { roles: ['REPORT_ADMINISTRATOR'] }));
    fireEvent.click(
      await screen.findByRole('button', { name: t('m12w.editName', [t('m12w.cpf.CPF_OBCB.name')]) }),
    );
    expect(await screen.findByRole('table', { name: t('m12.versionsOf', ['CPF_OBCB']) })).toBeTruthy();
    fireEvent.click(
      await screen.findByRole('button', { name: t('m12w.editName', [t('m12w.section.A.4.1')]) }),
    );
    expect(await screen.findByText(t('m12w.why.editFamily'))).toBeInTheDocument();
  });

  it('BR-MIS-003: the Rule Approver and the Payroll Checker see the mark, which points to the Approvals Inbox; a Maker has none', async () => {
    m12();
    const view = wrap(<ReportCatalogue />, me(['M-12:VIEW', 'M-12:APPROVE'], { roles: ['RULE_APPROVER'] }));
    const table = await screen.findByRole('table', { name: t('m12w.cpfTable') });
    fireEvent.click((await within(table).findAllByRole('button', { name: '✔' }))[0] as HTMLElement);
    expect(await screen.findByText(t('m12w.why.approve'))).toBeInTheDocument();
    view.unmount();
    wrap(<ReportCatalogue />, me(['M-12:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    const plain = await screen.findByRole('table', { name: t('m12w.cpfTable') });
    expect(within(plain).queryByRole('button', { name: '✔' })).toBeNull();
  });

  it('OD-121: the CPF rows and the definition card read the API state; a row View gives the reason', async () => {
    m12();
    wrap(<ReportCatalogue />, me(['M-12:VIEW']));
    const table = await screen.findByRole('table', { name: 'CPF Control & Audit — report detail' });
    expect(table.querySelectorAll('tbody tr')).toHaveLength(12);
    const row = within(table).getByText(t('m12w.cpf.CPF_OBCB.name')).closest('tr') as HTMLElement;
    await within(row).findByText(t('o04.status.BLOCKED'));
    expect(screen.getByRole('heading', { name: t('m12w.detail.title') })).toBeInTheDocument();
    expect(await screen.findByText('LABOUR_INCENTIVE_DETAIL')).toBeInTheDocument();
    expect(screen.getByTestId('m12-detail-blocked')).toHaveTextContent('Layout not supplied');
    fireEvent.click(screen.getByRole('button', { name: t('m12w.viewName', [t('m12w.cpf.CPF_OBCB.name')]) }));
    expect(await screen.findByRole('dialog')).toHaveTextContent('Layout not supplied');
    expect(screen.getByRole('heading', { name: t('m12w.rules') })).toBeInTheDocument();
  });

  it('OD-121: a failed summary is shown and a report with no entry reads a dash, never a state', async () => {
    m12({
      'GET /v1/report-definitions/summary': () => problem(503, 'COM-503-UNAVAILABLE'),
      'GET /v1/report-definitions?': () => paged([], 0),
    });
    wrap(<ReportCatalogue />, me(['M-12:VIEW']));
    expect((await screen.findAllByText(t('error.COM-503-UNAVAILABLE'))).length).toBeGreaterThan(0);
    const table = screen.getByRole('table', { name: t('m12w.cpfTable') });
    expect(within(table).queryByText(t('o04.status.AVAILABLE'))).toBeNull();
    expect(await screen.findByText(t('o04.none'))).toBeInTheDocument();
  });

  it('SRS-20-29: History opens the change history of the definition on screen', async () => {
    m12();
    wrap(<ReportCatalogue />, me(['M-12:VIEW']));
    expect(
      await screen.findByRole('button', { name: t('history.for', [t('m12w.detail.name')]) }),
    ).toHaveTextContent(t('wf.historyAct'));
  });
});
