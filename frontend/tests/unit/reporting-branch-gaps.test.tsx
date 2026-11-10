import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, renderHook, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState, type ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MisReports, ReportCatalogue } from '@/modules/reporting';
import { summaryQuery, type VersionProposal } from '@/modules/reporting/api/reportApi';
import { ModelEditor } from '@/modules/reporting/components/ModelEditor';
import { useCatalogueChoices } from '@/modules/reporting/orchestration/reportQueries';
import { I18nProvider } from '@/shared/i18n';
import { backend, me, paged, t, wrap } from './support/harness';

/**
 * O-04 and M-12 beyond the main paths (SRS-18-9, SRS-18-12, SRS-18-24; O-04 l.3500): a catalogue
 * longer than one read, an entry blocked or produced elsewhere without its reason or screen, the
 * amount order chosen and undone, the member range's upper end, a report's versions opened twice,
 * a format cleared, and one model row edited among several.
 */

afterEach(() => vi.unstubAllGlobals());

function entry(i: number, over: Record<string, unknown> = {}) {
  return {
    key: `REPORT_${i}`,
    name: `Report ${i}`,
    family: 'Operational',
    section: 'A.4.1',
    domain: 'REPORT',
    producingScreen: 'O-04',
    payrollTypes: 'All',
    periodicity: 'Monthly',
    lockRequirement: 'POST_LOCK',
    versionNo: 1,
    layoutFormat: 'EXCEL',
    blockedReason: null,
    status: 'AVAILABLE',
    ...over,
  };
}

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

function host() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
}

describe('O-04 report choice (branch gaps)', () => {
  it('O-04 l.3500: the choice reads the catalogue page by page and stops at an empty page', async () => {
    const first = Array.from({ length: 100 }, (_, i) => entry(i));
    let second: unknown[] = Array.from({ length: 50 }, (_, i) => entry(100 + i));
    backend({
      'GET /v1/reports/catalogue?': (url) =>
        url.includes('page=0') ? { ...paged(first, 150, url) } : { ...paged(second as never[], 150, url) },
    });
    const full = renderHook(() => useCatalogueChoices(), { wrapper: host() });
    await waitFor(() => expect(full.result.current.data?.items).toHaveLength(150));

    // The server answers a short catalogue (rows withdrawn meanwhile): the read stops, never loops.
    second = [];
    const short = renderHook(() => useCatalogueChoices(), { wrapper: host() });
    await waitFor(() => expect(short.result.current.data?.items).toHaveLength(100));
  });

  it('SRS-18-24: an entry blocked without a reason or produced on an unnamed screen still says why', async () => {
    backend({
      'GET /v1/reports/catalogue?': (url) =>
        paged(
          [
            entry(1, { key: 'COMPONENT_WISE_SUMMARY', name: 'Component-wise Summary' }),
            entry(2, { status: 'BLOCKED', blockedReason: null }),
            entry(3, { status: 'PRODUCED_ELSEWHERE', producingScreen: null }),
          ],
          3,
          url,
        ),
      'GET /v1/report-definitions?': (url) => paged([], 0, url),
      'GET /v1/reports/component-summary?': () => SUMMARY,
    });
    wrap(<MisReports />, me(['O-04:VIEW']));
    await screen.findByRole('option', { name: 'Report 2' });
    const generate = screen.getByTestId('o04-generate');
    await userEvent.selectOptions(screen.getByLabelText(t('o04.report')), 'REPORT_2');
    expect(generate).toBeDisabled();
    expect(generate).toHaveAttribute('title', t('o04.status.BLOCKED'));
    await userEvent.selectOptions(screen.getByLabelText(t('o04.report')), 'REPORT_3');
    expect(generate).toHaveAttribute('title', t('o04.status.PRODUCED_ELSEWHERE', ['']));
  });

  it('SRS-18-12: the amount order chosen and undone returns to the pay-code order', async () => {
    const calls = backend({
      'GET /v1/reports/catalogue?': (url) => paged([], 0, url),
      'GET /v1/report-definitions?': (url) => paged([], 0, url),
      'GET /v1/reports/component-summary?': () => SUMMARY,
    });
    wrap(<MisReports />, me(['O-04:VIEW']));
    const generate = await screen.findByTestId('o04-generate');
    await waitFor(() => expect(generate).toBeEnabled());
    await userEvent.click(generate);
    await waitFor(() => expect(calls.some((c) => c.url.includes('component-summary?'))).toBe(true));
    const sort = screen.getByLabelText(t('o04.sort'));
    await userEvent.selectOptions(sort, 'AMOUNT');
    await waitFor(() => expect(generate).toBeEnabled());
    await userEvent.click(generate);
    await waitFor(() => expect(calls.some((c) => c.url.includes('sort=AMOUNT'))).toBe(true));
    await userEvent.selectOptions(sort, 'PAY_CODE');
    expect(sort).toHaveValue('PAY_CODE');
  });

  it('SRS-18-12: the upper end of the member range typed in the filter is read, and cleared it is left out', async () => {
    const calls = backend({
      'GET /v1/reports/catalogue?': (url) => paged([], 0, url),
      'GET /v1/report-definitions?': (url) => paged([], 0, url),
      'GET /v1/reports/component-summary?': () => SUMMARY,
    });
    wrap(<MisReports />, me(['O-04:VIEW']));
    const generate = await screen.findByTestId('o04-generate');
    await waitFor(() => expect(generate).toBeEnabled());
    await userEvent.click(generate);
    await waitFor(() => expect(calls.some((c) => c.url.includes('component-summary?'))).toBe(true));
    const toEis = screen.getByLabelText(t('o04.toEis'));
    expect(toEis).toHaveValue('');
    await userEvent.type(toEis, '109999');
    await waitFor(() => expect(generate).toBeEnabled());
    await userEvent.click(generate);
    await waitFor(() => expect(calls.some((c) => c.url.includes('toEis=109999'))).toBe(true));
    await userEvent.clear(toEis);
    expect(toEis).toHaveValue('');
    await waitFor(() => expect(generate).toBeEnabled());
    await userEvent.click(generate);
    const last = () => calls.filter((c) => c.url.includes('component-summary?')).at(-1)?.url ?? '';
    await waitFor(() => expect(last()).not.toContain('toEis'));
  });

  it('SRS-18-12: the upper end of the member range is sent trimmed', () => {
    const q = summaryQuery('202610', 'UNIT', {
      officeId: null,
      payrollType: '',
      category: '',
      memberStatus: 'BOTH',
      toEis: ' 109999 ',
    });
    expect(q).toContain('toEis=109999');
    expect(q).not.toContain('fromEis');
  });
});

describe('M-12 Report Catalogue (branch gaps)', () => {
  const ROWS = [
    {
      key: 'SALARY_CONTROL_REGISTER',
      name: 'Salary Control Register',
      versionNo: 1,
      status: 'BLOCKED',
      producingScreen: null,
    },
  ];

  it('Review 15-13: the same report opened again keeps the proposal; a format cleared is none', async () => {
    backend({
      'GET /v1/report-definitions?': (url) => paged(ROWS, 1, url),
      'GET /v1/report-definitions/SALARY_CONTROL_REGISTER/versions': () => [],
    });
    wrap(<ReportCatalogue />, me(['M-12:VIEW', 'M-12:CREATE']));
    await userEvent.click(await screen.findByRole('button', { name: t('m12w.act.versions') }));
    const table = await screen.findByRole('table', { name: t('screen.M-12') });
    const row = (await within(table).findByText('SALARY_CONTROL_REGISTER')).closest('tr') as HTMLElement;
    await userEvent.click(within(row).getByRole('button', { name: t('m12.versions') }));
    await userEvent.click(await screen.findByLabelText(t('m12.blocked')));
    await userEvent.click(within(row).getByRole('button', { name: t('m12.versions') }));
    expect(screen.getByLabelText(t('m12.blocked'))).toBeChecked();
    const format = screen.getByLabelText(t('m12.format'));
    await userEvent.selectOptions(format, 'PDF');
    expect(format).toHaveValue('PDF');
    await userEvent.selectOptions(format, '');
    expect(format).toHaveValue('');
  });
});

function ModelHost() {
  const [form, setForm] = useState<VersionProposal>({
    layoutFormat: null,
    periodLockRequired: false,
    storeMode: 'REGENERATE',
    blocked: false,
    blockedReason: null,
    effectiveFrom: '2090-04-01',
    parameters: [
      {
        code: 'YYMM',
        label: null,
        dataType: 'MONTH',
        mandatory: true,
        roleScoped: false,
        defaultExpression: null,
        validationRule: 'Locked',
      },
      {
        code: 'OFF',
        label: null,
        dataType: 'LOCATION',
        mandatory: false,
        roleScoped: true,
        defaultExpression: null,
        validationRule: 'In scope',
      },
    ],
  });
  return <ModelEditor form={form} setForm={setForm} />;
}

describe('M-12 report model editor (branch gaps)', () => {
  it('SRS-18-9: editing one parameter leaves the others as they are', async () => {
    render(
      <I18nProvider>
        <ModelHost />
      </I18nProvider>,
    );
    const codes = screen.getAllByLabelText(t('m12.parameterCode'));
    await userEvent.type(codes[1] as HTMLElement, 'ICE');
    expect(screen.getAllByLabelText(t('m12.parameterCode'))[0]).toHaveValue('YYMM');
    expect(screen.getAllByLabelText(t('m12.parameterCode'))[1]).toHaveValue('OFFICE');
  });
});
