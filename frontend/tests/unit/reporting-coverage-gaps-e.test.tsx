import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  InvoiceOutputs,
  LabourReports,
  MisReports,
  PayslipScreen,
  Remittance,
  ReportCatalogue,
} from '@/modules/reporting';
import { backend, currentPeriod, me, paged, problem, referenceLists, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * Coverage of the reporting screens' remaining paths (O-03, O-04, O-06, O-11, M-12; OD-121, SRS-18-8, SRS-18-24,
 * FRS Annexure A.4): a session without roles is no Maker or Checker, the Checker's tick on each register, the record
 * dialogs closed, empty values read as a dash and a value the bundle does not name read as itself.
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

describe('M-12 remaining paths', () => {
  const ROWS = [
    entry('LABOUR_INCENTIVE_DETAIL', { periodicity: null, lockRequirement: 'NONE' }),
    entry('CPF_SANCTION_PAYMENT', { status: 'BLOCKED', blockedReason: null, layoutFormat: null }),
  ];
  function m12() {
    return backend({
      'GET /v1/reference/lists': () => referenceLists({ REPORT_FAMILY: [], REPORT_PAYROLL_TYPE: [] }),
      'GET /v1/report-definitions/summary': () => ({
        size: 4,
        active: 1,
        sections: [{ section: 'Z.9', reports: 4, blocked: 0 }],
        families: [],
        payrollTypes: [],
      }),
      'GET /v1/report-definitions?': (url) => paged(ROWS, ROWS.length, url),
      'GET /v1/report-definitions/LABOUR_INCENTIVE_DETAIL/versions': () => [],
    });
  }

  it('SRS_18_24_aSessionWithoutRolesApprovesByItsRightAndAnUnnamedSectionReadsItself', async () => {
    m12();
    wrap(<ReportCatalogue />, me(['M-12:VIEW', 'M-12:APPROVE'], { roles: undefined }));
    const table = await screen.findByRole('table', { name: 'Report Catalogue by Family' });
    // The section, its examples and its committed words have no label in the bundle: each reads the value itself.
    const [name] = await within(table).findAllByText('Z.9');
    expect(within(table).getAllByText('Z.9')).toHaveLength(3);
    const row = (name as HTMLElement).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: '✔' }));
    expect(await screen.findByText(t('m12w.why.approve'))).toBeInTheDocument();
    // The detail of the drawn definition: no periodicity reads a dash, no lock reads No.
    expect(await screen.findByText(`${t('m12w.family.Operational')} · —`)).toBeInTheDocument();
    expect(screen.getAllByText(t('m12w.no')).length).toBeGreaterThan(0);
  });

  it('SRS_18_24_aBlockedCpfReportWithoutReasonReadsADashAndTheDialogCloses', async () => {
    m12();
    wrap(<ReportCatalogue />, me(['M-12:VIEW']));
    const name = t('m12w.cpf.CPF_SANCTION_PAYMENT.name');
    await screen.findByText(`${t('m12w.family.Operational')} · —`);
    await userEvent.click(screen.getByRole('button', { name: t('m12w.viewName', [name]) }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent(t('o04.status.BLOCKED'));
    const reason = within(dialog).getByText(t('m12w.dialog.reason')).parentElement as HTMLElement;
    expect(reason).toHaveTextContent('—');
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});

describe('O-04 remaining paths', () => {
  const SUMMARY = {
    yymm: '202610',
    level: 'UNIT',
    provisional: false,
    earnings: 1000,
    deductions: 100,
    postedGross: 1000,
    postedDeductions: 100,
    controlTotalsMatch: true,
    lines: [],
  };
  function o04() {
    return backend({
      'GET /v1/reports/catalogue?': (url) =>
        paged(
          [entry('COMPONENT_WISE_SUMMARY', { name: 'Component-wise Summary', periodicity: null })],
          1,
          url,
        ),
      'GET /v1/reports/component-summary/latest-locked': () => ({ yymm: '202610' }),
      'GET /v1/reports/component-summary/options?': () => ({
        offices: [],
        payrollTypes: ['STAFF'],
        categories: [],
      }),
      'GET /v1/reports/component-summary?': () => SUMMARY,
    });
  }

  it('SRS_18_8_aMatchedSummaryDownloadsItsPdfAndTheSectionToggles', async () => {
    const calls = o04();
    vi.stubGlobal('URL', { ...URL, createObjectURL: () => 'blob:x', revokeObjectURL: () => undefined });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    try {
      wrap(<MisReports />, me(['O-04:VIEW', 'O-04:EXPORT'], { roles: undefined }));
      const button = await screen.findByTestId('o04-generate-table');
      await waitFor(() => expect(button).toBeEnabled());
      fireEvent.click(button);
      await waitFor(() => expect(screen.getByRole('button', { name: t('o04w.act.pdf') })).toBeEnabled());
      await userEvent.click(screen.getByRole('button', { name: t('o04w.act.pdf') }));
      await waitFor(() =>
        expect(calls.some((c) => c.url.includes('component-summary') && c.url.includes('format=PDF'))).toBe(
          true,
        ),
      );
      // In Hindi the PDF is locked with its reason (no embedded font, PENDING LIB-1-HI).
      await userEvent.selectOptions(screen.getByLabelText(t('o04.language')), 'hi');
      expect(screen.getByRole('button', { name: t('o04w.act.pdf') })).toHaveAttribute(
        'data-why',
        t('o04w.why.pdfHindi'),
      );
      const section = screen.getByTestId('o04-summary-section');
      const open = section.hasAttribute('open');
      fireEvent.click(section.querySelector('summary') as HTMLElement);
      expect(section.hasAttribute('open')).toBe(!open);
    } finally {
      click.mockRestore();
    }
  });

  it('SRS_18_10_aRowWithoutPeriodicityReadsADashInItsRecord', async () => {
    o04();
    wrap(<MisReports />, me(['O-04:VIEW']));
    const table = await screen.findByRole('table', { name: t('o04.catalogue') });
    await within(table).findByText('Component-wise Summary');
    await userEvent.click(
      within(table).getByRole('button', { name: t('o04w.viewName', ['Component-wise Summary']) }),
    );
    const dialog = await screen.findByRole('dialog');
    const frequency = within(dialog).getByText(t('o04w.col.frequency')).parentElement as HTMLElement;
    expect(frequency).toHaveTextContent('—');
  });
});

describe('O-06 remaining paths', () => {
  const OVERVIEW = {
    period: '202601',
    periods: ['202601'],
    units: [{ id: 5, label: 'DH001 - Delhi HQ' }],
    count: 1,
    value: 1,
    salary: 1,
    incentive: 0,
    glGap: 0,
    released: 0,
    held: 0,
    locked: false,
    types: [],
  };
  const ROW = {
    key: 'SALARY_INVOICE_DEPOT',
    name: 'Salary Invoice - by depot',
    section: 'A.4.3',
    count: 1,
    value: 642000,
    held: 0,
    released: 0,
    blockedReason: null,
  };
  function invoices() {
    return backend({
      'GET /v1/reports/invoices/overview': () => OVERVIEW,
      'GET /v1/reports/invoices/register': () =>
        paged([
          ROW,
          { ...ROW, key: 'INCENTIVE_INVOICE', name: 'Incentive', blockedReason: 'Waits for APPS' },
        ]),
      'GET /v1/reports/invoices/lines': () =>
        paged([
          {
            id: 1,
            number: '202601900005',
            unit: 'DH001 - Delhi HQ',
            paymentType: null,
            amount: 1,
            postingStatus: 'HELD',
            sentToApps: null,
            appsCancelStatus: 'NOT_REQUESTED',
          },
        ]),
      'GET /v1/reference/lists': () => referenceLists({ INVOICE_OUTPUT_TYPE: [] }),
    });
  }

  it('FRS_A_4_aRowSaysWhyItWaitsAndTheCheckerIsToldTheInterfaceIsNotBuilt', async () => {
    invoices();
    wrap(<InvoiceOutputs />, me(['O-06:VIEW'], { roles: ['PAYROLL_CHECKER'] }));
    const salary = (await screen.findByText(ROW.name, { selector: 'b' })).closest('tr') as HTMLElement;
    await userEvent.click(within(salary).getByRole('button', { name: t('o03.viewName', [ROW.name]) }));
    expect((await screen.findAllByText(t('o06.status.none'))).length).toBeGreaterThan(1);
    const incentive = screen.getByText('Incentive', { selector: 'b' }).closest('tr') as HTMLElement;
    await userEvent.click(within(incentive).getByRole('button', { name: t('o03.viewName', ['Incentive']) }));
    expect((await screen.findAllByText('Waits for APPS')).length).toBeGreaterThan(0);
    await userEvent.click(within(incentive).getByRole('button', { name: '✔' }));
    expect((await screen.findAllByText(t('o06.why.apps'))).length).toBeGreaterThan(0);
    // A line without a payment type reads a dash.
    const [cell] = await screen.findAllByText('202601900005');
    expect((cell?.closest('tr') as HTMLElement).querySelectorAll('td')[2]).toHaveTextContent('—');
  });

  it('SRS_G_54_aSessionWithoutRolesOrPeriodIsNeitherMakerNorCheckerAndTheUnitFilterClears', async () => {
    const calls = invoices();
    wrap(<InvoiceOutputs />, me(['O-06:VIEW'], { roles: undefined, period: undefined as never }));
    const generate = await screen.findByRole('button', { name: t('o06.act.generate') });
    expect(generate).toBeDisabled();
    expect(generate).toHaveAttribute('data-why', t('o06.why.maker'));
    await screen.findByRole('option', { name: 'DH001 - Delhi HQ' });
    const period = screen.getByRole('combobox', { name: t('o06.filter.period') }) as HTMLSelectElement;
    expect(period.options).toHaveLength(1);
    const unit = screen.getByRole('combobox', { name: t('o06.filter.unit') });
    fireEvent.change(unit, { target: { value: '5' } });
    await waitFor(() => expect(calls.some((c) => /register\?.*unit=5/.test(c.url))).toBe(true));
    fireEvent.change(unit, { target: { value: '' } });
    expect((unit as HTMLSelectElement).value).toBe('');
  });
});

describe('O-11 and O-03 remaining paths', () => {
  it('SRS_G_52_aSessionWithoutRolesHasEveryActionLocked', async () => {
    const yymm = currentPeriod().replace('-', '');
    backend({
      'GET /v1/remittances/overview': () => ({
        yymm,
        locked: true,
        payments: 0,
        lateArrears: 0,
        supplementary: 0,
        unmappedPayCodes: 0,
        yearEndFinYear: null,
        yearEndStatus: null,
        yearEndClosedAt: null,
      }),
      'GET /v1/remittances/runs': (url) => paged([], 0, url),
    });
    wrap(<Remittance />, me(['O-11:VIEW'], { roles: undefined }));
    await waitFor(() =>
      expect(screen.getAllByRole('button').filter((b) => b.hasAttribute('data-why')).length).toBeGreaterThan(
        0,
      ),
    );
  });

  it('SRS_18_24_unreadableLabourOptionsLeaveTheDepotAndGangListsEmpty', async () => {
    const calls = backend({
      'GET /v1/reports/labour-catalogue': () => paged([]),
      'GET /v1/labour/options': () => problem(500, 'COM-500-INTERNAL'),
      'GET /v1/labour/gangs': () => problem(500, 'COM-500-INTERNAL'),
    });
    wrap(<LabourReports />, me(['O-03:VIEW']));
    await waitFor(() => expect(calls.some((c) => c.url.includes('/v1/labour/gangs?size=100'))).toBe(true));
    for (const select of screen.getAllByRole('combobox')) {
      expect(within(select).queryByRole('option', { name: /DH0/ })).toBeNull();
    }
  });
});

describe('M-12 definition set and O-01 remaining paths', () => {
  it('SRS_18_24_theDefinitionSetReadsEveryPageAndStopsAtAnEmptyOne', async () => {
    const first = Array.from({ length: 100 }, (_, i) => entry(`R${i}`));
    let emptySecond = false;
    const calls = backend({
      'GET /v1/reference/lists': () => referenceLists({ REPORT_FAMILY: [], REPORT_PAYROLL_TYPE: [] }),
      'GET /v1/report-definitions/summary': () => ({
        size: 0,
        active: 0,
        sections: [],
        families: [],
        payrollTypes: [],
      }),
      'GET /v1/report-definitions?': (url) => {
        const page = Number(/[?&]page=(\d+)/.exec(url)?.[1] ?? 0);
        const size = Number(/[?&]size=(\d+)/.exec(url)?.[1] ?? 20);
        if (size !== 100) return paged([], 0, url);
        if (page === 0) return { items: first, page, size, total: 250 };
        if (page === 1 && !emptySecond)
          return {
            items: [entry('LABOUR_INCENTIVE_DETAIL', { periodicity: 'Annual' })],
            page,
            size,
            total: 250,
          };
        return { items: [], page, size, total: 250 };
      },
    });
    const view = wrap(<ReportCatalogue />, me(['M-12:VIEW']));
    // Page 1 holds the drawn definition (read after the first hundred); page 2 is empty and ends the read.
    expect(await screen.findByText(`${t('m12w.family.Operational')} · Annual`)).toBeInTheDocument();
    const pages = (n: number) => calls.filter((c) => c.url.includes(`page=${n}&size=100`)).length;
    expect(pages(1)).toBe(1);
    expect(pages(2)).toBe(1);
    expect(pages(3)).toBe(0);
    view.unmount();
    emptySecond = true;
    wrap(<ReportCatalogue />, me(['M-12:VIEW']));
    await waitFor(() => expect(pages(1)).toBe(2));
    expect(pages(2)).toBe(1);
  });

  it('SRS_17_1_moreDeductionsThanEarningsLeaveTheEarningSideEmptyAndAnotherKeyKeepsThePreview', async () => {
    const yymm = currentPeriod().replace('-', '');
    backend({
      'GET /v1/payslips/periods': () => [{ yymm, locked: true }],
      'GET /v1/payslips/summary': () => ({
        yymm,
        locked: true,
        toGenerate: 1,
        published: 1,
        suppressed: 0,
        lastPublished: null,
        lastPublishedAt: null,
        lastPublishedCount: 0,
      }),
      'GET /v1/payslips/register': (url) =>
        paged(
          [
            {
              eis: '104882',
              name: 'Ravi Sharma',
              payrollType: 'STAFF',
              payPattern: 'IDA',
              net: 1,
              status: 'READY',
              published: true,
            },
          ],
          1,
          url,
        ),
      'GET /v1/payslips/members': () => ({
        yymm,
        eis: '104882',
        name: 'Ravi Sharma',
        designation: 'Manager',
        office: 'Regional Office Delhi',
        payPattern: 'IDA',
        panMasked: 'XXXXX1234K',
        bankAccountMasked: 'XXXXXX4412',
        pran: null,
        earnings: [{ payCode: 101, name: 'Basic Pay', group: 'BASIC', amount: 62400 }],
        deductions: [
          { payCode: 301, name: 'CPF (Employee)', group: 'CPF', amount: 11232 },
          { payCode: 302, name: 'Income Tax', group: 'TDS', amount: 5000 },
        ],
        gross: 62400,
        totalDeductions: 16232,
        net: 46168,
        layoutVariant: 'STAFF',
        provisional: false,
      }),
    });
    wrap(<PayslipScreen />, me(['O-01:VIEW']));
    const tax = (await screen.findByText('Income Tax')).closest('tr') as HTMLElement;
    const cells = tax.querySelectorAll('td');
    expect(cells[0]?.textContent).toBe('');
    expect(cells[1]?.textContent).toBe('');
    await userEvent.click(screen.getByRole('button', { name: t('o01.act.preview') }));
    const dialog = await screen.findByRole('dialog');
    const printed = within(dialog).getByText('Income Tax').closest('tr') as HTMLElement;
    expect(printed.querySelectorAll('td')[0]?.textContent).toBe('');
    expect(printed.querySelectorAll('td')[1]?.textContent).toBe('');
    fireEvent.keyDown(dialog, { key: 'Enter' });
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});
