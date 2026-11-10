import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { InvoiceOutputs, LabourReports } from '@/modules/reporting';
import { invoiceStatus } from '@/modules/reporting/components/InvoiceOutputs';
import { backend, me, paged, referenceLists, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * O-03 and O-06 as Wireframes v6.1 draws them (OD-121; SRS-18-24, FRS Annexure A.4, A.11, BR-AUDIT-010): the catalogue
 * with each entry's Blocked reason, the invoice register from the staged rows, and no action that generates or releases.
 */
const serveAll = (routes: Record<string, unknown>) =>
  backend(Object.fromEntries(Object.entries(routes).map(([k, v]) => [k, () => v])));

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', '/');
});

const entry = (key: string, over: object = {}) => ({
  key,
  name: key,
  family: 'Labour Registers',
  section: 'A.4.4',
  domain: 'REPORT',
  producingScreen: 'O-03',
  payrollTypes: 'Labour',
  periodicity: 'Monthly',
  lockRequirement: 'POST_LOCK',
  versionNo: 1,
  layoutFormat: null,
  blockedReason: 'Layout not supplied',
  status: 'BLOCKED',
  ...over,
});

function reports(url = '/v1/reports/labour-catalogue') {
  return serveAll({
    [`GET ${url}`]: paged([entry('LABOUR_WAGE_DETAIL')]),
    'GET /v1/labour/options': { depots: [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla' }], designations: [] },
    'GET /v1/labour/gangs': paged([{ gangId: 3, gangCode: 'DP-DL01-G002' }]),
  });
}

describe('O-03 Labour Registers & Reports', () => {
  it('OD-121: both drawn exports are there, the toolbar one and the annexure one, each saying why', async () => {
    reports();
    wrap(<LabourReports />, me(['O-03:VIEW']));
    fireEvent.click(await screen.findByRole('button', { name: t('wf.export') }));
    expect((await screen.findAllByText(t('o03.why.generate'))).length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: t('m01.w.exportShort') })).toBeInTheDocument();
  });

  it('SRS-18-24: lists each report with its Blocked state and says why on View', async () => {
    reports();
    wrap(<LabourReports />, me(['O-03:VIEW']));
    const tables = await screen.findAllByRole('table');
    expect(tables.length).toBe(2);
    expect((await screen.findAllByText(t('o03.contents.LABOUR_WAGE_DETAIL'))).length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole('button', { name: t('o03.viewName', ['LABOUR_WAGE_DETAIL']) }));
    expect(await screen.findByText('Layout not supplied')).toBeInTheDocument();
  });

  it("SRS-G-54: Generate is the Maker's and says why; others have it off", async () => {
    reports();
    const view = wrap(<LabourReports />, me(['O-03:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    fireEvent.click(await screen.findByRole('button', { name: t('o03.act.generate') }));
    expect((await screen.findAllByText(t('o03.why.generate'))).length).toBeGreaterThan(0);
    view.unmount();
    wrap(<LabourReports />, me(['O-03:VIEW'], { roles: ['LABOUR_DEALING_HAND'] }));
    expect(await screen.findByRole('button', { name: t('o03.act.generate') })).toBeDisabled();
  });

  it('SRS-G-54: the DPS menu entry opens the DPS context, and the type filter asks the server', async () => {
    window.history.replaceState(null, '', '/payroll?s=O-03&engine=DPS');
    const calls = reports();
    wrap(<LabourReports />, me(['O-03:VIEW']));
    const type = await screen.findByRole('combobox', { name: t('o03.filter.type') });
    await waitFor(() => expect(type).toHaveValue('DPS'));
    await waitFor(() => expect(calls.some((c) => /engine=DPS/.test(c.url))).toBe(true));
    fireEvent.change(type, { target: { value: 'NWNP' } });
    await waitFor(() => expect(calls.some((c) => /engine=NWNP/.test(c.url))).toBe(true));
  });

  it('SRS-G-54: depot and gang fill the report parameters; a depot asks for its gangs', async () => {
    const calls = reports();
    wrap(<LabourReports />, me(['O-03:VIEW']));
    const depot = await screen.findByRole('combobox', { name: t('o03.filter.depot') });
    await screen.findByRole('option', { name: 'DP-DL01 - Depot Okhla' });
    fireEvent.change(depot, { target: { value: '7' } });
    expect(await screen.findByText(/DP-DL01 - Depot Okhla · /)).toBeInTheDocument();
    await waitFor(() => expect(calls.some((c) => /gangs\?depot=7/.test(c.url))).toBe(true));
  });

  it('SRS-G-54: the Checker sees the approve mark, which says there is nothing to validate', async () => {
    reports();
    wrap(<LabourReports />, me(['O-03:VIEW'], { roles: ['PAYROLL_CHECKER'] }));
    const table = await screen.findByRole('table', { name: t('o03.catalogue') });
    fireEvent.click(await within(table).findByRole('button', { name: '✔' }));
    expect(await screen.findByText(t('o03.why.validate'))).toBeInTheDocument();
  });

  it('SRS-G-54: an entry that can be generated reads Available', async () => {
    serveAll({
      'GET /v1/reports/labour-catalogue': paged([
        entry('WORK_SLIP_REGISTER', { status: 'PRODUCED_ELSEWHERE', blockedReason: null }),
      ]),
      'GET /v1/labour/options': { depots: [], designations: [] },
      'GET /v1/labour/gangs': paged([]),
    });
    wrap(<LabourReports />, me(['O-03:VIEW']));
    expect((await screen.findAllByText(t('o03.status.available'))).length).toBeGreaterThan(0);
  });
});

const OVERVIEW = {
  period: '202601',
  periods: ['202601', '202512'],
  units: [{ id: 5, label: 'DH001 - Delhi HQ' }],
  count: 8,
  value: 1584000,
  salary: 1296000,
  incentive: 58400,
  glGap: 64200,
  released: 6,
  held: 1,
  locked: true,
  types: [{ key: 'SALARY_INVOICE_DEPOT', name: 'Salary Invoice - by depot' }],
};

const ROW = {
  key: 'SALARY_INVOICE_DEPOT',
  name: 'Salary Invoice - by depot',
  section: 'A.4.3',
  count: 1,
  value: 642000,
  held: 1,
  released: 0,
  blockedReason: 'Waits for module 23',
};

const LINE = {
  id: 1,
  number: '202601900005',
  unit: 'DH001 - Delhi HQ',
  paymentType: 'Salary',
  amount: 642000,
  postingStatus: 'HELD',
  sentToApps: null,
  appsCancelStatus: 'NOT_REQUESTED',
};

function invoices(over: object = {}) {
  return serveAll({
    'GET /v1/reports/invoices/overview': { ...OVERVIEW, ...over },
    'GET /v1/reports/invoices/register': paged([
      ROW,
      { ...ROW, key: 'INCENTIVE_INVOICE', name: 'Incentive Invoice', held: 0, released: 1 },
      { ...ROW, key: 'SUPPLEMENTARY_INVOICE', name: 'Supplementary', held: 0, released: 0, count: 0 },
    ]),
    'GET /v1/reports/invoices/lines': paged([LINE]),
    'GET /v1/reference/lists': referenceLists({
      INVOICE_OUTPUT_TYPE: [
        ['SALARY_INVOICE_EMPLOYEE', 'Salary - employee'],
        ['SALARY_INVOICE_DEPOT', 'Salary - depot'],
      ],
    }),
  });
}

describe('O-06 Invoice Outputs', () => {
  it('FRS A.4: the register reads what is staged, with its GL tie and status', async () => {
    invoices();
    wrap(<InvoiceOutputs />, me(['O-06:VIEW']));
    const row = (await screen.findByText('Salary Invoice - by depot', { selector: 'b' })).closest(
      'tr',
    ) as HTMLElement;
    expect(within(row).getAllByText(t('o06.status.held'))).toHaveLength(2);
    expect(within(row).getByText(t('o06.locked'))).toBeInTheDocument();
    expect(screen.getByText(t('o06.gl.matched'))).toBeInTheDocument();
    expect(screen.getByText(t('o06.status.none'))).toBeInTheDocument();
    expect(await screen.findAllByText('202601900005')).not.toHaveLength(0);
  });

  it('SRS-G-54: the Maker generates, the Checker releases, each is told why; nothing is sent', async () => {
    const calls = invoices();
    const view = wrap(<InvoiceOutputs />, me(['O-06:VIEW'], { roles: ['PAYROLL_MAKER'] }));
    fireEvent.click(await screen.findByRole('button', { name: t('o06.act.generate') }));
    expect((await screen.findAllByText(t('o06.why.apps'))).length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: t('o06.act.release') })).toBeDisabled();
    view.unmount();
    wrap(<InvoiceOutputs />, me(['O-06:VIEW'], { roles: ['PAYROLL_CHECKER'] }));
    expect(await screen.findByRole('button', { name: t('o06.act.release') })).toBeEnabled();
    expect(screen.getByRole('button', { name: t('o06.act.generate') })).toBeDisabled();
    expect(calls.filter((c) => c.method !== 'GET')).toHaveLength(0);
  });

  it('BR-AUDIT-009: a line cannot be cancelled here', async () => {
    invoices();
    wrap(<InvoiceOutputs />, me(['O-06:VIEW']));
    const [cell] = await screen.findAllByText('202601900005');
    const row = cell?.closest('tr') as HTMLElement;
    expect(within(row).getByRole('button', { name: t('wf.inv.cancel') })).toBeDisabled();
  });

  it('SRS-G-54: the filters ask the server', async () => {
    const calls = invoices();
    wrap(<InvoiceOutputs />, me(['O-06:VIEW']));
    await screen.findByRole('option', { name: 'DH001 - Delhi HQ' });
    await screen.findByRole('option', { name: t('o06.type.SALARY_INVOICE_DEPOT') });
    fireEvent.change(screen.getByRole('combobox', { name: t('o06.filter.unit') }), {
      target: { value: '5' },
    });
    await waitFor(() => expect(calls.some((c) => /overview\?.*unit=5/.test(c.url))).toBe(true));
    fireEvent.change(screen.getByRole('combobox', { name: t('o06.filter.type') }), {
      target: { value: 'SALARY_INVOICE_DEPOT' },
    });
    await waitFor(() =>
      expect(calls.some((c) => /register\?.*type=SALARY_INVOICE_DEPOT/.test(c.url))).toBe(true),
    );
    fireEvent.change(screen.getByRole('combobox', { name: t('o06.filter.period') }), {
      target: { value: '202512' },
    });
    await waitFor(() => expect(calls.some((c) => /overview\?yymm=202512/.test(c.url))).toBe(true));
  });

  it('OD-123: the type filter is the reference list in the drawn words, without the catalogue entry that is no type', async () => {
    invoices();
    wrap(<InvoiceOutputs />, me(['O-06:VIEW']));
    await screen.findByRole('option', { name: t('o06.type.SALARY_INVOICE_EMPLOYEE') });
    const types = screen.getByRole('combobox', { name: t('o06.filter.type') });
    expect(
      within(types)
        .getAllByRole('option')
        .map((o) => o.textContent),
    ).toEqual([t('o06.allTypes'), t('o06.type.SALARY_INVOICE_EMPLOYEE'), t('o06.type.SALARY_INVOICE_DEPOT')]);
  });

  it('OD-121: the month of the session is offered after the lock until it holds invoices, then as itself', async () => {
    const calls = invoices();
    const view = wrap(<InvoiceOutputs />, me(['O-06:VIEW'], { period: '2026-11' }));
    const after = await screen.findByRole('option', { name: t('o06.afterLock', ['NOV 2026']) });
    expect((after as HTMLOptionElement).value).toBe('202611');
    fireEvent.change(screen.getByRole('combobox', { name: t('o06.filter.period') }), {
      target: { value: '202611' },
    });
    await waitFor(() => expect(calls.some((c) => /overview\?yymm=202611/.test(c.url))).toBe(true));
    view.unmount();
    invoices({ periods: ['202611', '202601'] });
    wrap(<InvoiceOutputs />, me(['O-06:VIEW'], { period: '2026-11' }));
    await screen.findByRole('option', { name: 'NOV 2026' });
    expect(screen.queryByRole('option', { name: t('o06.afterLock', ['NOV 2026']) })).toBeNull();
  });

  it('OD-121: the drawn Export says why and sends nothing', async () => {
    const calls = invoices();
    wrap(<InvoiceOutputs />, me(['O-06:VIEW']));
    fireEvent.click(await screen.findByRole('button', { name: t('wf.export') }));
    expect((await screen.findAllByText(t('o06.why.apps'))).length).toBeGreaterThan(0);
    expect(calls.filter((c) => c.method !== 'GET')).toHaveLength(0);
  });

  it('FRS A.4: without any invoice the screen says so and sends no register read', async () => {
    const calls = invoices({ period: null, periods: [], units: [], count: 0, value: 0 });
    wrap(<InvoiceOutputs />, me(['O-06:VIEW']));
    expect(await screen.findByText(t('o06.none'))).toBeInTheDocument();
    expect(screen.getByText(t('wf.inv.none'))).toBeInTheDocument();
    expect(calls.some((c) => /register/.test(c.url))).toBe(false);
  });

  it('BR-AUDIT-010: maps the posting status to the card status', () => {
    expect(invoiceStatus('SENT')).toBe('SENT_TO_APPS');
    expect(invoiceStatus('POSTED')).toBe('SENT_TO_APPS');
    expect(invoiceStatus('CANCELLED')).toBe('CANCELLED_IN_APPS');
    expect(invoiceStatus('HELD')).toBe('RAISED');
  });
});
