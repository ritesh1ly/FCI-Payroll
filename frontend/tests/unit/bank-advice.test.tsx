import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { BankAdvice } from '@/modules/jobs';
import { adviceTone, releaseStatus, sumOf } from '@/modules/jobs/business/bankAdviceRules';
import { backend, currentPeriod, me, paged, problem, sent, t, wrap, type Route } from './support/harness';

/**
 * O-02 Bank Advice & EBS Posting (SRS-15-34, SRS-15-35, SRS-15-41, l.1707-1713, BR-SEC-006; OD-121): the drawn tiles
 * and tables read from the month, Generate and Post disabled with the reason, the Emergency stop for the holder of
 * SUSPEND at HQ, and the undrawn fields and the suspensions in closed sections.
 */

afterEach(() => vi.unstubAllGlobals());

const YYMM = currentPeriod().replace('-', '');
const VIEW = ['O-02:VIEW'];
const FINANCE = ['O-02:VIEW', 'O-02:SUSPEND', 'O-02:RESUME', 'O-01:VIEW'];

const overview = (over: object = {}) => ({
  yymm: YYMM,
  locked: false,
  net: 3235400000,
  banks: 2,
  blocked: 188,
  ebsStatus: 'IDLE',
  suspended: false,
  bankRows: [
    {
      bank: 'State Bank of India',
      members: 19330,
      net: 1682200000,
      blocked: 92,
      fileFormat: 'Carried-forward layout',
      status: 'NOT_GENERATED',
    },
    { bank: '-', members: 12, net: 100000, blocked: 12, fileFormat: null, status: 'NOT_GENERATED' },
  ],
  members: 19342,
  gl: [
    { head: 'Salaries & wages (staff)', account: '51100', debit: 3124100000, credit: 0 },
    { head: 'Net salary payable (AP)', account: '21000', debit: null, credit: 3124100000 },
  ],
  invoices: [{ status: 'SENT', count: 5, sentToApps: '2026-07-05T06:00:00Z' }],
  ...over,
});

function routes(body: object = overview(), over: Record<string, Route> = {}) {
  return backend({
    'GET /v1/reports/bank-advice': () => body,
    'GET /v1/release-suspensions?': (url) => paged([], 0, url),
    'GET /v1/payslips/periods': () => [
      { yymm: YYMM, locked: false },
      { yymm: '202001', locked: true },
    ],
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

describe('O-02 Bank Advice & EBS Posting', () => {
  it('SRS-15-41: draws the tiles, the bank summary in crore with its total and the GL preview', async () => {
    routes();
    wrap(<BankAdvice />, me(VIEW));
    expect(await screen.findByText(t('o02w.crore', ['323.54']))).toBeTruthy();
    expect(screen.getByText(t('o02w.tile.netProvisional'))).toBeTruthy();
    expect(screen.getByText(t('o02w.ebs.IDLE'))).toBeTruthy();
    expect(screen.getByText(t('o02w.ebs.subIdle'))).toBeTruthy();
    const sbi = screen.getByRole('row', { name: /State Bank of India/ });
    expect(within(sbi).getByText('168.22')).toBeTruthy();
    expect(within(sbi).getByText('19,330')).toBeTruthy();
    expect(within(sbi).getByText('Carried-forward layout')).toBeTruthy();
    expect(within(sbi).getByText(t('o02w.advice.NOT_GENERATED'))).toBeTruthy();
    expect(screen.getByText(t('o02w.noBank'))).toBeTruthy();
    const [advice, posting] = screen.getAllByRole('row', {
      name: new RegExp(`^${t('o02w.total')}`),
    }) as [HTMLElement, HTMLElement];
    expect(within(advice).getByText('19,342')).toBeTruthy();
    expect(within(advice).getByText('323.54')).toBeTruthy();
    expect(within(posting).getAllByText('312.41')).toHaveLength(2);
    expect(screen.getByRole('row', { name: /Net salary payable/ }).textContent).toContain('—');
  });

  it('BR-SEC-006: the banners keep the drawn order and the status follows the live state', async () => {
    routes(overview({ suspended: true }));
    const { container } = wrap(<BankAdvice />, me(VIEW));
    expect(await screen.findByText(t('o02w.status.active'))).toBeTruthy();
    const banners = [...container.querySelectorAll('.keep .anno')].map((b) => b.textContent);
    expect(banners[0]).toContain(t('o02w.stopLead'));
    expect(banners[1]).toContain(t('o02w.otpLead'));
  });

  it('SRS-15-34: before the lock Generate and Post give the drawn reasons and nothing is sent', async () => {
    const calls = routes();
    wrap(<BankAdvice />, me(FINANCE));
    await screen.findByText(t('o02w.tile.netProvisional'));
    expect(screen.getByRole('button', { name: t('o02w.act.generate') })).toBeDisabled();
    expect(why(t('o02w.act.generate'))).toBe(t('o02w.why.generate'));
    expect(why(t('o02w.act.post'))).toBe(t('o02w.why.post'));
    await pressSaysWhy(t('o02w.act.fileLayout'), t('o02w.why.contract'));
    expect(calls.filter((c) => c.method === 'POST')).toEqual([]);
  });

  it('API-23-2: after the lock Generate and Post stay disabled: the contract is not supplied', async () => {
    routes(overview({ locked: true }));
    wrap(<BankAdvice />, me(VIEW));
    expect(await screen.findByText(t('o02w.tile.netLocked'))).toBeTruthy();
    expect(screen.getByText(t('o02w.ebs.subLocked'))).toBeTruthy();
    expect(why(t('o02w.act.generate'))).toBe(t('o02w.why.contract'));
    expect(why(t('o02w.act.post'))).toBe(t('o02w.why.contract'));
  });

  it('SRS-15-35: Finance at HQ opens the suspend dialog from Emergency stop and the suspensions open', async () => {
    const calls = routes(overview(), {
      'POST /v1/release-suspensions': () => ({ status: 201, body: { id: 6 } }),
    });
    wrap(<BankAdvice />, me(FINANCE, { atHeadquarters: true }));
    await screen.findByText(t('o02w.tile.netProvisional'));
    await userEvent.click(screen.getByRole('button', { name: t('o02w.act.stop') }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText(t('common.reason')), 'Bank file mismatch');
    await userEvent.click(within(dialog).getByRole('button', { name: t('o02.suspend') }));
    expect(await screen.findByText(t('o02.suspendedDone'))).toBeTruthy();
    expect(sent(calls, 'POST', '/release-suspensions')).toEqual([
      { scope: 'ALL_UNITS', channel: 'ALL', reason: 'Bank file mismatch' },
    ]);
    expect(screen.getByTestId('o02-suspensions')).toHaveAttribute('open');
  });

  it('SRS-15-35: a refused stop is shown, and cancelling the dialog sends nothing', async () => {
    const calls = routes(overview(), {
      'POST /v1/release-suspensions': () => problem(403, 'AUTHZ-403-HQ-ONLY'),
    });
    wrap(<BankAdvice />, me(FINANCE, { atHeadquarters: true }));
    await screen.findByText(t('o02w.tile.netProvisional'));
    await userEvent.click(screen.getByRole('button', { name: t('o02w.act.stop') }));
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(sent(calls, 'POST', '/release-suspensions')).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: t('o02w.act.stop') }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText(t('common.reason')), 'Stop');
    await userEvent.click(within(dialog).getByRole('button', { name: t('o02.suspend') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
  });

  it('BR-SEC-006: Finance outside HQ and the sysadmin without SUSPEND get the drawn reason (OD-135)', async () => {
    routes();
    const { unmount } = wrap(<BankAdvice />, me(FINANCE, { atHeadquarters: false }));
    await screen.findByText(t('o02w.tile.netProvisional'));
    expect(screen.getByRole('button', { name: t('o02w.act.stop') })).toBeDisabled();
    expect(why(t('o02w.act.stop'))).toBe('HQ authorised role only — BR-SEC-006');
    unmount();
    routes();
    wrap(
      <BankAdvice />,
      me(['O-02:VIEW', 'O-01:VIEW'], {
        role: 'SYSTEM_ADMINISTRATOR',
        roles: ['SYSTEM_ADMINISTRATOR'],
        atHeadquarters: true,
      }),
    );
    await screen.findByText(t('o02w.tile.netProvisional'));
    await pressSaysWhy(t('o02w.act.stop'), 'HQ authorised role only — BR-SEC-006');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.queryByRole('button', { name: t('o02.suspend') })).toBeNull();
  });

  it('SRS l.1707: a role with O-01 VIEW picks the period; the choice re-reads the month', async () => {
    const calls = routes();
    wrap(<BankAdvice />, me(FINANCE));
    await screen.findByText(t('o02w.tile.netProvisional'));
    const period = await screen.findByLabelText(t('o02w.field.period'));
    await within(period).findByRole('option', { hidden: true, name: /2020/ });
    await userEvent.selectOptions(period, '202001');
    await waitFor(() => expect(calls.some((c) => c.url.includes('bank-advice?yymm=202001'))).toBe(true));
  });

  it('R-RBAC: a role without O-01 VIEW never calls the periods read: the month is read-only', async () => {
    const calls = routes();
    wrap(<BankAdvice />, me(VIEW));
    await screen.findByText(t('o02w.tile.netProvisional'));
    expect(screen.queryByLabelText(t('o02w.field.period'))).toBeNull();
    expect(calls.some((c) => c.url.includes('/v1/payslips/periods'))).toBe(false);
  });

  it('SRS l.1709-1713: the closed section holds release status, the blocked note and the invoices', async () => {
    routes(overview({ bankRows: [{ ...overview().bankRows[0], status: 'GENERATED' }] }));
    wrap(<BankAdvice />, me(VIEW));
    const details = await screen.findByTestId('o02-release-details');
    await waitFor(() =>
      expect(within(details).getByTestId('o02-release-status').textContent).toBe(t('o02w.release.GENERATED')),
    );
    expect(details.textContent).toContain(t('o02w.field.excludedNote', [188]));
    expect(within(details).getByText(t('o02w.invoice.SENT'))).toBeTruthy();
    expect(details).not.toHaveAttribute('open');
  });

  it('M-24: Mapping rules links for a role that may open it, else is disabled with the reason', async () => {
    routes();
    const { unmount } = wrap(<BankAdvice />, me(['O-02:VIEW', 'M-24:VIEW']));
    expect(await screen.findByRole('link', { name: t('o02w.act.mapping') })).toHaveAttribute(
      'href',
      expect.stringContaining('s=M-24'),
    );
    unmount();
    routes();
    wrap(<BankAdvice />, me(VIEW));
    await screen.findByText(t('o02w.tile.netProvisional'));
    await pressSaysWhy(t('o02w.act.mapping'), t('reason.noPermission'));
  });

  it('SRS-15-41: an empty month draws the empty tables and says the GL statement is not generated', async () => {
    routes(overview({ net: 0, banks: 0, blocked: 0, bankRows: [], members: 0, gl: [], invoices: [] }));
    wrap(<BankAdvice />, me(VIEW));
    // Nothing is said about the statement until the month has been read.
    expect(screen.queryByText(t('o02w.noGl'))).toBeNull();
    expect(await screen.findByText(t('o02w.noGl'))).toBeTruthy();
    expect(screen.getByText(t('o02w.noBanks'))).toBeTruthy();
    expect(screen.getByText(t('o02w.invoice.none'))).toBeTruthy();
    // The line sits in the table, the state row of a list: the pack draws no note under it.
    expect(within(screen.getByRole('table', { name: t('o02w.gl') })).getByText(t('o02w.noGl'))).toBeTruthy();
  });

  it('API errors and a posted batch: a failed read is shown; a batch state replaces Idle', async () => {
    routes(overview(), { 'GET /v1/reports/bank-advice': () => problem(500, 'INTERNAL') });
    const { unmount } = wrap(<BankAdvice />, me(VIEW));
    expect(await screen.findByRole('alert')).toBeTruthy();
    unmount();
    routes(overview({ ebsStatus: 'ACCEPTED', locked: true }));
    wrap(<BankAdvice />, me(VIEW));
    expect(await screen.findByText(t('o02w.ebs.ACCEPTED'))).toBeTruthy();
    expect(screen.queryByText(t('o02w.ebs.subIdle'))).toBeNull();
  });
});

describe('O-02 rules', () => {
  const rows = (...s: string[]) => s.map((status) => ({ status })) as never;

  it('BR-SEC-006: Suspended wins, then Released, then Generated (SRS l.1709)', () => {
    expect(releaseStatus({ suspended: true, bankRows: rows('RELEASED') })).toBe('SUSPENDED');
    expect(releaseStatus({ suspended: false, bankRows: rows('GENERATED', 'SENT') })).toBe('RELEASED');
    expect(releaseStatus({ suspended: false, bankRows: rows('ACKNOWLEDGED') })).toBe('RELEASED');
    expect(releaseStatus({ suspended: false, bankRows: rows('DRAFT', 'GENERATED') })).toBe('GENERATED');
    expect(releaseStatus({ suspended: false, bankRows: rows('NOT_GENERATED') })).toBe('NOT_GENERATED');
    expect(releaseStatus({ suspended: false, bankRows: [] })).toBe('NOT_GENERATED');
  });

  it('DDL bank_advice: each status has its pill', () => {
    expect(adviceTone('RELEASED')).toBe('p-ok');
    expect(adviceTone('GENERATED')).toBe('p-info');
    expect(adviceTone('CANCELLED')).toBe('p-bad');
    expect(adviceTone('NOT_GENERATED')).toBe('p-gray');
  });

  it('SRS-15-41: a blank side adds nothing to the total', () => {
    const gl = [
      { head: 'a', account: '1', debit: 5, credit: null },
      { head: 'b', account: '2', debit: null, credit: 5 },
    ];
    expect(sumOf(gl, 'debit')).toBe(5);
    expect(sumOf(gl, 'credit')).toBe(5);
  });
});
