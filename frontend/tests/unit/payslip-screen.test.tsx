import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PayslipScreen } from '@/modules/reporting';
import {
  closingYear,
  firstOpenRow,
  invoiceRange,
  noSlipKey,
  slipRows,
  statusPill,
} from '@/modules/reporting/business/outputRules';
import { periodLabel } from '@/shared/session';
import {
  backend,
  captureDownloads,
  currentPeriod,
  me,
  noContent,
  paged,
  problem,
  sent,
  t,
  wrap,
  type Route,
} from './support/harness';

/**
 * O-01 Payslip (SRS-17-1, SRS-17-7, SRS-18-12, SRS l.3381-3407; OD-121): tiles, Period from the cycles (OD-123), the
 * register, the payslip of the selected row, bulk generation for the Payroll Maker on a locked period, and every
 * drawn object that cannot act disabled with its reason.
 */

afterEach(() => vi.unstubAllGlobals());

const MONTH = currentPeriod();
const YYMM = MONTH.replace('-', '');
const before = new Date(Date.UTC(Number(YYMM.slice(0, 4)), Number(YYMM.slice(4)) - 2, 1));
const PRIOR = before.toISOString().slice(0, 7).replace('-', '');
const VIEW = ['O-01:VIEW'];
const MAKER = ['O-01:VIEW', 'O-01:GENERATE'];

const row = (over: object = {}) => ({
  eis: '104882',
  name: 'Ravi Sharma',
  payrollType: 'STAFF',
  payPattern: 'IDA',
  net: 91963,
  status: 'READY',
  published: true,
  ...over,
});
const summary = (over: object = {}) => ({
  yymm: YYMM,
  locked: true,
  toGenerate: 43245,
  published: 7,
  suppressed: 2,
  lastPublished: PRIOR,
  lastPublishedAt: '2026-06-30T06:00:00Z',
  lastPublishedCount: 43190,
  ...over,
});
const slip = (over: object = {}) => ({
  yymm: YYMM,
  eis: '104882',
  name: 'Ravi Sharma',
  designation: 'Manager',
  office: 'Regional Office Delhi',
  payPattern: 'IDA',
  panMasked: 'XXXXX1234K',
  bankAccountMasked: 'XXXXXX4412',
  pran: null,
  earnings: [
    { payCode: 101, name: 'Basic Pay', group: 'BASIC', amount: 62400 },
    { payCode: 102, name: 'Dearness Allowance', group: 'DA', amount: 31200 },
  ],
  deductions: [{ payCode: 301, name: 'CPF (Employee)', group: 'CPF', amount: 11232 }],
  gross: 93600,
  totalDeductions: 11232,
  net: 82368,
  layoutVariant: 'STAFF',
  provisional: false,
  ...over,
});

function routes(rows: unknown[], over: Record<string, Route> = {}) {
  return backend({
    'GET /v1/payslips/periods': () => [
      { yymm: YYMM, locked: true },
      { yymm: PRIOR, locked: true },
    ],
    'GET /v1/payslips/summary': () => summary(),
    'GET /v1/payslips/register': (url) => paged(rows, rows.length, url),
    'GET /v1/payslips/members': () => slip(),
    ...over,
  });
}

/** The reason of a drawn action: a disabled one carries data-why, one drawn enabled (Explained) data-guard. */
const why = (name: string) => {
  const button = screen.getByRole('button', { name });
  return button.getAttribute('data-why') ?? button.getAttribute('data-guard');
};

/** WF-1900: drawn enabled, pressing says why; the caller checks that nothing was sent. */
async function pressSaysWhy(name: string, reason: string) {
  const button = screen.getByRole('button', { name });
  expect(button).toBeEnabled();
  expect(button).toHaveAttribute('data-guard', reason);
  await userEvent.click(button);
}

describe('O-01 Payslip', () => {
  it('SRS-18-12: draws the tiles, the register row and the payslip of the first row', async () => {
    routes([
      row(),
      row({ eis: 'DL-0412', name: 'Gang 12', payrollType: 'DEPT_LABOUR', payPattern: null, net: 842180 }),
    ]);
    wrap(<PayslipScreen />, me(VIEW));
    expect(await screen.findByText('43,245')).toBeTruthy();
    expect(screen.getByText('91,963', { exact: false })).toBeTruthy();
    const label = periodLabel(MONTH);
    expect(
      await screen.findByRole('heading', { name: t('o01.slip', ['104882', 'Ravi Sharma', label]) }),
    ).toBeTruthy();
    expect(await screen.findByText('Basic Pay')).toBeTruthy();
    expect(screen.getByRole('option', { name: t('o01.period', [label]) })).toBeTruthy();
    expect(screen.getByText(t('o01.type.STAFF', ['IDA']))).toBeTruthy();
    expect(screen.getByText(t('o01.type.DEPT_LABOUR'))).toBeTruthy();
    expect(
      screen.getByText(t('o01.tile.lastPublishedSub', ['x', '43,190']).slice(4), { exact: false }),
    ).toBeTruthy();
    expect(screen.getByText(t('o01.labour'))).toBeTruthy();
    expect(screen.getByText(t('o01.labour.rateV'))).toBeTruthy();
    expect(screen.getByText(t('o01.note'))).toBeTruthy();
  });

  it('OD-144, SRS-17-1: the payslip PDF and the row PDF download the server PDF; export says why, e-mail stays disabled', async () => {
    const calls = routes([row()], { 'GET /v1/payslip-bulk-files': () => noContent() });
    const files = captureDownloads();
    try {
      wrap(<PayslipScreen />, me(MAKER));
      await screen.findByText('Basic Pay');
      await userEvent.click(screen.getByRole('button', { name: t('o01.act.pdf') }));
      await userEvent.click(screen.getByRole('button', { name: t('o01.act.rowPdf') }));
      await waitFor(() =>
        expect(calls.filter((c) => c.url.includes(`/v1/payslips/members/104882/${YYMM}/pdf`))).toHaveLength(
          2,
        ),
      );
      await pressSaysWhy(t('wf.export'), t('reason.noExport'));
      expect(screen.getByRole('button', { name: t('o01.act.email') })).toBeDisabled();
      expect(why(t('o01.act.email'))).toBe(t('o01.why.email'));
      expect(screen.getByRole('button', { name: t('o01.act.emailBatch') })).toBeDisabled();
      expect(why(t('o01.act.emailBatch'))).toBe(t('o01.why.email'));
      expect(calls.filter((c) => c.method !== 'GET')).toEqual([]);
    } finally {
      files.restore();
    }
  });

  it('OD-149, SRS-NF-2: Bulk PDF queues the background job of the month and level once, then says it is queued', async () => {
    const calls = routes([row()], {
      'GET /v1/payslip-bulk-files': () => noContent(),
      'POST /v1/payslip-bulk-files': () => ({ jobRequestId: 91, status: 'PENDING', written: 0, leftOut: 0 }),
    });
    wrap(<PayslipScreen />, me(MAKER));
    await screen.findByText('Basic Pay');
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes(`/v1/payslip-bulk-files?yymm=${YYMM}&level=STAFF`))).toBe(true),
    );
    const bulk = screen.getByRole('button', { name: t('o01.act.bulkPdf') });
    await userEvent.dblClick(bulk);
    expect(await screen.findByText(t('bulk.state.PENDING', [0, 0]))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/payslip-bulk-files')).toEqual([{ yymm: YYMM, level: 'STAFF' }]);
    expect(screen.getByRole('button', { name: t('o01.act.bulkPdf') })).toBeDisabled();
  });

  it('OD-149: a completed bulk file downloads its ZIP; a refusal is shown and saves no file', async () => {
    routes([row()], {
      'GET /v1/payslip-bulk-files': () => ({ jobRequestId: 91, status: 'COMPLETED', written: 3, leftOut: 1 }),
      'GET /v1/payslip-bulk-files/91/file': () => problem(409, 'PDF-409-NOT-READY', ['RUNNING']),
    });
    const files = captureDownloads();
    try {
      wrap(<PayslipScreen />, me(VIEW));
      expect(await screen.findByText(t('bulk.state.COMPLETED', [3, 1]), { exact: false })).toBeTruthy();
      expect(screen.getByText(t('bulk.leftOut', [1]), { exact: false })).toBeTruthy();
      await userEvent.click(screen.getByRole('button', { name: t('o01.act.bulkPdf') }));
      expect(await screen.findByRole('alert')).toBeTruthy();
      expect(files.names).toEqual([]);
    } finally {
      files.restore();
    }
  });

  it('SRS l.3389, OD-149: the Bulk PDF of a month not locked is disabled with the reason and asks nothing', async () => {
    const calls = routes([row()], { 'GET /v1/payslips/summary': () => summary({ locked: false }) });
    wrap(<PayslipScreen />, me(MAKER));
    await screen.findByText('Basic Pay');
    await waitFor(() => expect(screen.getByRole('button', { name: t('o01.act.bulkPdf') })).toBeDisabled());
    expect(why(t('o01.act.bulkPdf'))).toBe(t('o01.why.notLocked'));
    expect(calls.some((c) => c.url.includes('/v1/payslip-bulk-files'))).toBe(false);
  });

  it('SRS l.3405: the screen opens on the newest month with results; a month picked stays picked', async () => {
    const calls = routes([row()], {
      'GET /v1/payslips/periods': () => [
        { yymm: '209912', locked: false, results: false },
        { yymm: YYMM, locked: true, results: true },
        { yymm: PRIOR, locked: true, results: true },
      ],
    });
    wrap(<PayslipScreen />, me(VIEW));
    await screen.findByText('Basic Pay');
    expect(calls.some((c) => c.url.includes('summary') && c.url.includes(`yymm=${YYMM}`))).toBe(true);
    expect(calls.some((c) => c.url.includes('yymm=209912'))).toBe(false);
    await userEvent.selectOptions(screen.getByLabelText(t('o01.filter.period')), '209912');
    await waitFor(() => expect(calls.some((c) => c.url.includes('yymm=209912'))).toBe(true));
  });

  it('SRS l.3405: with results in no month the newest month is shown', async () => {
    const calls = routes([], {
      'GET /v1/payslips/periods': () => [
        { yymm: YYMM, locked: false, results: false },
        { yymm: PRIOR, locked: true, results: false },
      ],
    });
    wrap(<PayslipScreen />, me(VIEW));
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('summary') && c.url.includes(`yymm=${YYMM}`))).toBe(true),
    );
  });

  it('SRS-17-1, OD-144: a provisional month says so in the title and in the preview, whose PDF downloads', async () => {
    const calls = routes([row({ status: 'PROVISIONAL' })], {
      'GET /v1/payslips/members': () => slip({ provisional: true }),
    });
    const files = captureDownloads();
    wrap(<PayslipScreen />, me(VIEW));
    const label = periodLabel(MONTH);
    const heading = t('o01.slipProvisional', ['104882', 'Ravi Sharma', label]);
    expect(await screen.findByRole('heading', { name: heading })).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('o01.act.preview') }));
    const dialog = screen.getByRole('dialog', { name: t('o01.doc.title', [label, 'Ravi Sharma']) });
    expect(within(dialog).getByText(t('o01.doc.provisional'))).toBeTruthy();
    expect(within(dialog).getByText('XXXXX1234K')).toBeTruthy();
    expect(within(dialog).getByText(t('o01.doc.notLocked', [label]))).toBeTruthy();
    await userEvent.click(within(dialog).getByRole('button', { name: t('o01.doc.pdf') }));
    await waitFor(() => expect(files.names).toEqual([`payslip-104882-${YYMM}.pdf`]));
    expect(calls.some((c) => c.url.endsWith(`/v1/payslips/members/104882/${YYMM}/pdf`))).toBe(true);
    files.restore();
    const print = vi.spyOn(window, 'print').mockImplementation(() => undefined);
    await userEvent.click(within(dialog).getByRole('button', { name: t('o01.doc.print') }));
    expect(print).toHaveBeenCalled();
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('OD-121: Escape closes the preview, as Close does', async () => {
    routes([row()]);
    wrap(<PayslipScreen />, me(VIEW));
    await screen.findByText('Basic Pay');
    await userEvent.click(screen.getByRole('button', { name: t('o01.act.preview') }));
    expect(await screen.findByRole('dialog')).toBeTruthy();
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('SRS-17-1: a final month carries no provisional mark in the preview; absent details print a dash', async () => {
    routes([row()], {
      'GET /v1/payslips/members': () =>
        slip({ designation: null, office: null, payPattern: null, panMasked: null, bankAccountMasked: null }),
    });
    wrap(<PayslipScreen />, me(VIEW));
    await screen.findByText('Basic Pay');
    await userEvent.click(screen.getByRole('button', { name: t('o01.act.preview') }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).queryByText(t('o01.doc.provisional'))).toBeNull();
    expect(within(dialog).getAllByText('—').length).toBeGreaterThanOrEqual(4);
  });

  it('SRS-17-1: a HELD row shows no net and no payslip; the row PDF and the preview give the drawn reason', async () => {
    const calls = routes([row({ eis: '106004', name: 'P. Nayak', net: -1200, status: 'HELD' })]);
    wrap(<PayslipScreen />, me(VIEW));
    const nayak = await screen.findByRole('row', { name: /106004/ });
    expect(within(nayak).getByText(t('o01.status.HELD'))).toBeTruthy();
    expect(within(nayak).getByText('—')).toBeTruthy();
    expect(within(nayak).queryByText('-1,200', { exact: false })).toBeNull();
    await waitFor(() => expect(why(t('o01.act.rowPdf'))).toBe(t('o01.why.held')));
    expect(why(t('o01.act.preview'))).toBe(t('o01.why.held'));
    expect(why(t('o01.act.pdf'))).toBe(t('o01.why.held'));
    expect(screen.getByText(t('o01.why.held'))).toBeTruthy();
    expect(calls.some((c) => c.url.includes('/v1/payslips/members'))).toBe(false);
  });

  it('SRS-17-1: a suppressed row is skipped when a row is opened first and has no payslip when picked', async () => {
    const calls = routes([
      row({ eis: '100001', name: 'S. Rao', status: 'SUPPRESSED' }),
      row({ eis: '100002', name: 'M. Das' }),
    ]);
    wrap(<PayslipScreen />, me(VIEW));
    await screen.findByText('Basic Pay');
    expect(calls.find((c) => c.url.includes('/v1/payslips/members'))?.url).toContain('/100002/');
    await userEvent.click(screen.getByRole('row', { name: /100001/ }));
    expect(why(t('o01.act.preview'))).toBe(t('o01.why.suppressed'));
    await userEvent.click(screen.getByRole('row', { name: /100002/ }));
    expect(await screen.findByRole('button', { name: t('o01.act.preview') })).toBeEnabled();
  });

  it('SRS l.3381: the Payroll Maker confirms the sheet and generates; the screen says what was written', async () => {
    const calls = routes([row()], {
      'POST /v1/payslip-runs': () => ({
        status: 201,
        body: { yymm: YYMM, written: 12, alreadyGenerated: 3 },
      }),
    });
    wrap(<PayslipScreen />, me(MAKER));
    await screen.findByText('43,245');
    await userEvent.click(await screen.findByRole('button', { name: t('o01.act.generate') }));
    const sheet = screen.getByRole('dialog');
    expect(within(sheet).getByText(t('o01.sheet.writesV'))).toBeTruthy();
    await userEvent.click(within(sheet).getByRole('button', { name: t('o01.sheet.confirm') }));
    expect(await screen.findByText(t('o01.generated', [12, 3]))).toBeTruthy();
    expect(sent(calls, 'POST', '/payslip-runs')).toEqual([{ yymm: YYMM, level: 'STAFF' }]);
  });

  it('SRS l.3381: cancelling the sheet generates nothing', async () => {
    const calls = routes([row()]);
    wrap(<PayslipScreen />, me(MAKER));
    await screen.findByText('43,245');
    await userEvent.click(await screen.findByRole('button', { name: t('o01.act.generate') }));
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(sent(calls, 'POST', '/payslip-runs')).toEqual([]);
  });

  it('SRS l.3389: a refusal of the server (PSL-409-NOT-LOCKED) is shown through the shared display', async () => {
    routes([row()], {
      'POST /v1/payslip-runs': () => problem(409, 'PSL-409-NOT-LOCKED', [YYMM]),
    });
    wrap(<PayslipScreen />, me(MAKER));
    await screen.findByText('43,245');
    await userEvent.click(await screen.findByRole('button', { name: t('o01.act.generate') }));
    await userEvent.click(screen.getByRole('button', { name: t('o01.sheet.confirm') }));
    expect((await screen.findByRole('alert')).textContent).toContain(t('error.PSL-409-NOT-LOCKED', [YYMM]));
  });

  it('SRS l.3389: a period not locked keeps Generate disabled for the Maker, with the reason', async () => {
    routes([row()], { 'GET /v1/payslips/summary': () => summary({ locked: false }) });
    wrap(<PayslipScreen />, me(MAKER));
    await screen.findByText('43,245');
    expect(screen.getByRole('button', { name: t('o01.act.generate') })).toBeDisabled();
    expect(why(t('o01.act.generate'))).toBe(t('o01.why.notLocked'));
  });

  it('R-RBAC: a role without GENERATE sees Generate disabled, never sends it, even on a locked period', async () => {
    const calls = routes([row()]);
    wrap(<PayslipScreen />, me(VIEW));
    await screen.findByText('43,245');
    expect(screen.getByRole('button', { name: t('o01.act.generate') })).toBeDisabled();
    expect(why(t('o01.act.generate'))).toBe('Payroll Maker only');
    expect(sent(calls, 'POST', '/payslip-runs')).toEqual([]);
  });

  it('SRS l.3405: level, period and search drive the server reads, and the selection follows', async () => {
    const calls = routes([row()]);
    wrap(<PayslipScreen />, me(VIEW));
    await screen.findByText('Basic Pay');
    await userEvent.selectOptions(screen.getByLabelText(t('o01.filter.level')), 'LABOUR_GANG');
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('register') && c.url.includes('level=LABOUR_GANG'))).toBe(true),
    );
    await userEvent.selectOptions(screen.getByLabelText(t('o01.filter.period')), PRIOR);
    await waitFor(() =>
      expect(calls.some((c) => c.url.includes('summary') && c.url.includes(`yymm=${PRIOR}`))).toBe(true),
    );
    await userEvent.type(screen.getByRole('searchbox'), '10');
    await waitFor(() => expect(calls.some((c) => c.url.includes('text=10'))).toBe(true));
  });

  it('API errors: a refused member read is shown and the card stays without figures', async () => {
    routes([row()], { 'GET /v1/payslips/members': () => problem(404, 'NOT-FOUND') });
    wrap(<PayslipScreen />, me(VIEW));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByText('Basic Pay')).toBeNull();
    expect(screen.queryByText(t('common.loading'))).toBeNull();
  });

  it('OD-123: no cycle yet: no read is sent, the screen says so and every tile stands at nothing', async () => {
    const calls = routes([], {
      'GET /v1/payslips/periods': () => [],
    });
    wrap(<PayslipScreen />, me(MAKER));
    expect(await screen.findByText(t('o01.noPeriods'))).toBeTruthy();
    expect(calls.some((c) => c.url.includes('/summary') || c.url.includes('/register'))).toBe(false);
    expect(why(t('o01.act.preview'))).toBe(t('o01.why.selectRow'));
    expect(why(t('o01.act.generate'))).toBe(t('o01.why.notLocked'));
    expect(screen.getByRole('heading', { name: t('o01.slipNone') })).toBeTruthy();
  });

  it('SRS-18-12: an empty register and nothing published read as such', async () => {
    routes([], {
      'GET /v1/payslips/summary': () =>
        summary({ lastPublished: null, lastPublishedAt: null, lastPublishedCount: 0 }),
    });
    wrap(<PayslipScreen />, me(VIEW));
    expect(await screen.findByText(t('o01.none'))).toBeTruthy();
    expect(await screen.findByText(t('o01.tile.noneYet'))).toBeTruthy();
  });
});

describe('O-01 and O-11 rules', () => {
  it('SRS-17-1: a row with no payslip has a reason; none selected asks for a row', () => {
    expect(noSlipKey(null)).toBe('o01.why.selectRow');
    expect(noSlipKey({ status: 'READY' })).toBeNull();
    expect(noSlipKey({ status: 'AWAITING_GENERATION' })).toBeNull();
    expect(firstOpenRow([])).toBeNull();
    const held = row({ status: 'HELD' }) as Parameters<typeof firstOpenRow>[0][number];
    expect(firstOpenRow([held])).toBe(held);
  });

  it('OD-121: the two sides pair up to the longer one', () => {
    expect(slipRows([1], [2, 3])).toEqual([
      [1, 2],
      [undefined, 3],
    ]);
    expect(slipRows([], [])).toEqual([]);
  });

  it('OD-121: the drawn pills and invoice ranges', () => {
    expect(statusPill('READY')).toBe('p-ok');
    expect(statusPill('AWAITING_GENERATION')).toBe('p-gray');
    expect(invoiceRange({ invoiceFrom: null, invoiceTo: null })).toBeNull();
    expect(invoiceRange({ invoiceFrom: '202606000413', invoiceTo: null })).toBe('202606000413');
    expect(invoiceRange({ invoiceFrom: '202606000413', invoiceTo: '202606000413' })).toBe('202606000413');
    expect(invoiceRange({ invoiceFrom: '202606000413', invoiceTo: '202606000488' })).toBe(
      '202606000413 – 000488',
    );
    expect(invoiceRange({ invoiceFrom: '202606000413', invoiceTo: '202607000488' })).toBe(
      '202606000413 – 202607000488',
    );
    expect(closingYear('2025-26')).toBe(2026);
  });
});
