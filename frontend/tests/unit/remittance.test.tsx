import userEvent from '@testing-library/user-event';
import { screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Remittance } from '@/modules/reporting';
import { periodLabel } from '@/shared/session';
import { backend, currentPeriod, me, paged, problem, t, wrap, type Route } from './support/harness';

/**
 * O-11 Monthly & Year-End Remittance (SRS-G-52, SRS-18-19, SRS-18-24, BR-MIS-006; OD-121): read-only tiles and runs;
 * every drawn action disabled with the reason per role, nothing sent (API-23-2).
 */

afterEach(() => vi.unstubAllGlobals());

const MONTH = currentPeriod();
const YYMM = MONTH.replace('-', '');
const VIEW = ['O-11:VIEW'];

const overview = (over: object = {}) => ({
  yymm: YYMM,
  locked: true,
  payments: 1284,
  lateArrears: 41,
  supplementary: 6,
  unmappedPayCodes: 0,
  yearEndFinYear: '2025-26',
  yearEndStatus: 'SENT',
  yearEndClosedAt: '2026-04-15T05:00:00Z',
  ...over,
});
const run = (over: object = {}) => ({
  id: 1,
  type: 'MONTHLY',
  period: YYMM,
  finYear: '2026-27',
  invoiceFrom: '202606000413',
  invoiceTo: '202606000488',
  generated: '2026-07-01T05:50:00Z',
  transmittedBy: '088120',
  transmittedAt: '2026-07-01T06:12:00Z',
  reference: 'TRN-0619',
  status: 'SENT',
  ...over,
});

function routes(rows: unknown[] = [run()], over: Record<string, Route> = {}) {
  return backend({
    'GET /v1/remittances/overview': () => overview(),
    'GET /v1/remittances/runs': (url) => paged(rows, rows.length, url),
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

describe('O-11 Monthly & Year-End Remittance', () => {
  it('SRS-G-52: draws the window tiles, the closed year-end and the two rule panels', async () => {
    routes();
    wrap(<Remittance />, me(VIEW));
    expect(await screen.findByText('1,284')).toBeTruthy();
    expect(screen.getByText(periodLabel(MONTH))).toBeTruthy();
    expect(screen.getByText(t('o11.tile.paymentsSub', [41, 6]))).toBeTruthy();
    expect(screen.getByText(t('o11.tile.unmappedSub'))).toBeTruthy();
    expect(screen.getByText(t('o11.tile.yearEnd', ['2025-26']))).toBeTruthy();
    expect(screen.getByText(t('o11.ye.run'), { selector: '.big' })).toBeTruthy();
    expect(screen.getByText(t('o11.ye.title'))).toBeTruthy();
    expect(screen.getByText(t('o11.tx.title'))).toBeTruthy();
    expect(screen.getByText(t('o11.tx.namesSub'))).toBeTruthy();
  });

  it('SRS-G-52: a monthly run shows its name, prerequisite, range, stamp and the OTP pill', async () => {
    routes();
    wrap(<Remittance />, me(VIEW));
    const label = periodLabel(MONTH);
    const r = await screen.findByRole('row', { name: new RegExp(t('o11.run.MONTHLY', [label])) });
    expect(within(r).getByText(t('o11.prereq.MONTHLY', [label]))).toBeTruthy();
    expect(within(r).getByText('202606000413 – 000488')).toBeTruthy();
    expect(r.textContent).toContain('TRN-0619');
    expect(r.textContent).toContain('088120');
    expect(within(r).getByText(t('o11.status.SENT'))).toBeTruthy();
    expect(within(r).getByText(t('o11.otp'))).toBeTruthy();
  });

  it('SRS-G-52: a year-end run closes on the March after its financial year; a draft is not enabled', async () => {
    routes([
      run({
        id: 2,
        type: 'YEAR_END',
        period: null,
        finYear: '2026-27',
        invoiceFrom: null,
        invoiceTo: null,
        generated: null,
        transmittedBy: null,
        transmittedAt: null,
        reference: null,
        status: 'DRAFT',
      }),
    ]);
    wrap(<Remittance />, me(VIEW));
    const r = await screen.findByRole('row', { name: new RegExp(t('o11.run.YEAR_END', ['2026-27'])) });
    expect(within(r).getByText(t('o11.prereq.YEAR_END', [periodLabel('2027-03')]))).toBeTruthy();
    expect(within(r).getByText(t('o11.status.YEAR_END.DRAFT'))).toBeTruthy();
    expect(within(r).queryByText(t('o11.otp'))).toBeNull();
  });

  it('SRS-G-52: a sent year-end run names the closed year; incomplete rows print dashes', async () => {
    routes([
      run({ id: 3, type: 'YEAR_END', finYear: '2025-26', reference: null, status: 'SENT' }),
      run({ id: 4, type: 'YEAR_END', finYear: null, status: 'GENERATED', transmittedBy: null }),
      run({ id: 5, type: 'SUPPLEMENTARY', period: null, invoiceFrom: null, status: 'APPROVED' }),
    ]);
    wrap(<Remittance />, me(VIEW));
    expect(await screen.findByText(t('o11.status.YEAR_END.SENT', ['2025-26']))).toBeTruthy();
    expect(screen.getByText(t('o11.run.SUPPLEMENTARY', ['—']))).toBeTruthy();
    expect(screen.getByText(t('o11.prereq.SUPPLEMENTARY'))).toBeTruthy();
    expect(screen.getByText(t('o11.run.YEAR_END', ['—']))).toBeTruthy();
  });

  it('OD-121: a draft monthly run before its invoices says how they are numbered', async () => {
    routes([run({ invoiceFrom: null, invoiceTo: null, status: 'DRAFT', transmittedBy: null })]);
    wrap(<Remittance />, me(VIEW));
    expect(await screen.findByText(t('o11.invoiceLater'))).toBeTruthy();
    expect(screen.getByText(t('o11.status.DRAFT'))).toBeTruthy();
  });

  it('SRS-18-19: an unmapped pay code turns the tile into the blocked line', async () => {
    routes([], { 'GET /v1/remittances/overview': () => overview({ unmappedPayCodes: 3 }) });
    wrap(<Remittance />, me(VIEW));
    expect(await screen.findByText(t('o11.tile.unmappedBlocked'))).toBeTruthy();
  });

  it('SRS-G-52: no year-end run and an open one read as such', async () => {
    routes([], {
      'GET /v1/remittances/overview': () =>
        overview({ yearEndFinYear: null, yearEndStatus: null, yearEndClosedAt: null }),
    });
    const { unmount } = wrap(<Remittance />, me(VIEW));
    expect(await screen.findByText(t('o11.tile.yearEndNone'))).toBeTruthy();
    expect(await screen.findByText(t('o11.none'))).toBeTruthy();
    unmount();
    routes([], {
      'GET /v1/remittances/overview': () =>
        overview({ yearEndFinYear: '2026-27', yearEndStatus: 'DRAFT', yearEndClosedAt: null }),
    });
    wrap(<Remittance />, me(VIEW));
    expect(await screen.findByText(t('o11.status.YEAR_END.DRAFT'))).toBeTruthy();
    expect(screen.getByText(t('o11.tile.yearEndOpen'))).toBeTruthy();
  });

  it('SRS-G-54: a Payroll Maker gets the drawn reason on every action; nothing can be sent', async () => {
    const calls = routes();
    wrap(<Remittance />, me(VIEW, { roles: ['PAYROLL_MAKER'] }));
    await screen.findByText('1,284');
    expect(why(t('o11.act.report'))).toBe(t('o11.why.report'));
    expect(why(t('o11.act.transmit'))).toBe(t('o11.why.transmit'));
    expect(why(t('o11.act.yearEnd'))).toBe(t('o11.why.yearEnd'));
    expect(why(t('o11.act.approve'))).toBe(t('o11.why.approve'));
    expect(why(t('o11.act.export'))).toBe(t('reason.noExport'));
    expect(calls.every((c) => c.method === 'GET')).toBe(true);
  });

  it('API-23-2, WF-1900: Finance presses Transmit and Post Year-End as drawn and reads the contract is missing; nothing is sent, no OTP starts', async () => {
    const calls = routes();
    wrap(<Remittance />, me(VIEW, { roles: ['FINANCE'] }));
    await screen.findByText('1,284');
    await pressSaysWhy(t('o11.act.transmit'), t('o11.why.apps'));
    expect(await screen.findByText(t('o11.why.apps'))).toBeTruthy();
    await pressSaysWhy(t('o11.act.yearEnd'), t('o11.why.apps'));
    await pressSaysWhy(t('o11.act.report'), t('o11.why.report'));
    await pressSaysWhy(t('o11.act.export'), t('reason.noExport'));
    expect(screen.getByRole('button', { name: t('o11.act.approve') })).toBeDisabled();
    expect(why(t('o11.act.approve'))).toBe(t('o11.why.approve'));
    expect(calls.every((c) => c.method === 'GET')).toBe(true);
    expect(calls.some((c) => c.url.includes('otp'))).toBe(false);
  });

  it('API-23-2: the Checker reads the same on Approve; Transmit keeps the Finance reason', async () => {
    const calls = routes();
    wrap(<Remittance />, me(VIEW, { roles: ['PAYROLL_CHECKER'] }));
    await screen.findByText('1,284');
    await pressSaysWhy(t('o11.act.approve'), t('o11.why.apps'));
    expect(screen.getByRole('button', { name: t('o11.act.transmit') })).toBeDisabled();
    expect(why(t('o11.act.transmit'))).toBe(t('o11.why.transmit'));
    expect(calls.every((c) => c.method === 'GET')).toBe(true);
    expect(calls.some((c) => c.url.includes('otp'))).toBe(false);
  });

  it('API errors: a failed read is shown', async () => {
    routes([], { 'GET /v1/remittances/overview': () => problem(500, 'INTERNAL') });
    wrap(<Remittance />, me(VIEW));
    expect(await screen.findByRole('alert')).toBeTruthy();
  });
});
