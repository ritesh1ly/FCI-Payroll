import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DirectPayroll } from '@/modules/jobs';
import { backend, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

/**
 * I-19 Maintain Direct Payroll as Wireframes v6.1 draws it (OD-121; plan 05 phase 05g; SRS-15-2, SRS-15-19,
 * SRS-15-22, SRS-G-37, BR-CYC-010, BR-CYC-018): the four tiles, the permitted heads, the register of the
 * window's period with Filter (status from the MDP_ENTRY_STATUS list) and Export, the row actions (View with
 * the cancellation, Edit = amend, ✔ for the Checker), the controls; a capture in the open window; amend and
 * cancel held while a decision waits, once cancelled or outside the window.
 */

afterEach(() => vi.unstubAllGlobals());

const entry = (over: object = {}) => ({
  id: 41,
  reference: 'MDP-202607-41',
  period: '2026-07',
  eis: '100041',
  member: 'Member 41',
  payCode: 901,
  head: 'Professional Tax',
  amount: 200,
  status: 'APPROVED',
  reason: 'PT short-deducted',
  enteredBy: 'maker.chennai',
  enteredAt: '2026-10-20T10:00:00Z',
  checkedBy: 'ddo.chennai',
  decidedAt: '2026-10-21T10:00:00Z',
  pendingApprovalId: null,
  ...over,
});

const HEADS = [
  { payCode: 901, name: 'Professional Tax' },
  { payCode: 348, name: 'Labour Welfare Fund' },
];

const SUMMARY = {
  period: '2026-07',
  windowOpen: true,
  lockedAt: '2026-07-31T12:00:00Z',
  approved: 26,
  pending: 8,
  heads: 2,
  value: 318000,
  approvedValue: 230,
};

const STATUSES: [string, string][] = [
  ['ENTERED', 'Pending Checker'],
  ['AMENDED', 'Pending Checker (amended)'],
  ['APPROVED', 'Approved'],
  ['CANCELLED', 'Cancelled'],
  ['POSTED', 'Posted'],
];

function routes(
  rows: unknown[],
  over: Record<string, (url: string, init?: RequestInit) => unknown> = {},
  periods: string[] = ['2026-07'],
) {
  return backend({
    'GET /v1/mdp-transactions/heads': () => HEADS,
    'GET /v1/mdp-transactions/open-periods': () => periods,
    'GET /v1/mdp-transactions/summary': () => SUMMARY,
    'GET /v1/mdp-transactions?': (url) => paged(rows, rows.length, url),
    'GET /v1/reference/lists': () => referenceLists({ MDP_ENTRY_STATUS: STATUSES }),
    ...over,
  });
}

const MAKER = ['I-19:VIEW', 'I-19:CREATE', 'I-19:EDIT', 'I-19:CANCEL', 'I-19:EXPORT'];

/** The MDP Transaction Register (the card titled as drawn). */
async function register(): Promise<HTMLElement> {
  const title = await screen.findByText(t('i19.registerTitle', ['202607']));
  return (title.closest('.card') as HTMLElement).querySelector('table') as HTMLElement;
}

const rowOf = (table: HTMLElement, reference: string) =>
  table.querySelector(`tr[data-reference="${reference}"]`) as HTMLElement;

describe('I-19 Maintain Direct Payroll as drawn', () => {
  it('SRS-15-22: the register of the window shows each entry with maker and checker; a viewer only reads it', async () => {
    routes([entry(), entry({ id: 42, reference: 'MDP-202607-42', status: 'CANCELLED', checkedBy: null })]);
    wrap(<DirectPayroll />, ['I-19:VIEW']);
    const table = await register();
    expect(await within(table).findByText('MDP-202607-41')).toBeTruthy();
    const approved = rowOf(table, 'MDP-202607-41');
    expect(approved.textContent).toContain('ddo.chennai');
    expect(approved.textContent).toContain('maker.chennai');
    expect(approved.textContent).toContain('100041 Member 41');
    expect(rowOf(table, 'MDP-202607-42').textContent).toContain(t('i19.status.CANCELLED'));
    // A viewer: New MDP Entry drawn disabled with its reason; Edit drawn disabled; no ✔.
    const capture = screen.getByRole('button', { name: t('i19.newEntry') });
    expect(capture).toBeDisabled();
    expect(capture.getAttribute('data-why')).toBe(t('reason.makerOnly'));
    const edit = within(approved).getByRole('button', { name: `${t('common.edit')} MDP-202607-41` });
    expect(edit).toBeDisabled();
    expect(edit.getAttribute('data-why')).toBe(t('i19.why.makerEdit'));
    expect(within(table).queryByRole('button', { name: '✔' })).toBeNull();
    // Export is drawn for every role; without EXPORT pressing it says why and starts nothing.
    await userEvent.click(screen.getByRole('button', { name: t('i19.exportRegister') }));
    expect(await screen.findByText(t('x05.why.export'))).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('SRS-15-2 / BR-CYC-010 (OD-121): the tiles, the permitted heads, the approved total and the controls are drawn', async () => {
    routes([entry()]);
    wrap(<DirectPayroll />, ['I-19:VIEW']);
    await register();
    const tile = (label: string) => screen.getByText(label).closest('.tile') as HTMLElement;
    expect(within(tile(t('i19.tile.window'))).getByText(t('i19.tile.open'))).toBeTruthy();
    expect(within(tile(t('i19.tile.window'))).getByText(/^Period 202607 · Locked /)).toBeTruthy();
    expect(within(tile(t('i19.tile.entries'))).getByText('34')).toBeTruthy();
    expect(within(tile(t('i19.tile.entries'))).getByText(t('i19.tile.entriesSub', [26, 8]))).toBeTruthy();
    expect(within(tile(t('i19.tile.heads'))).getByText('2')).toBeTruthy();
    expect(within(tile(t('i19.tile.value'))).getByText(t('i19.tile.valueSub'))).toBeTruthy();
    // The heads of M-16 (code + name), then every other head blocked.
    const heads = (screen.getByText(t('i19.headsTitle')).closest('.card') as HTMLElement).textContent ?? '';
    expect(heads).toContain('901 Professional Tax');
    expect(heads).toContain('348 Labour Welfare Fund');
    expect(heads).toContain(t('i19.otherHeadsText', [2]));
    expect(screen.getByText(t('i19.approvedTotal'))).toBeTruthy();
    for (const k of [
      'i19.ctl.window',
      'i19.ctl.makerChecker',
      'i19.ctl.reportable',
      'i19.ctl.audited',
      'i19.notDo.salary',
    ])
      expect(screen.getByText(t(k))).toBeTruthy();
  });

  it('SRS-15-2: a closed window, or no locked period yet, reads so on the tile', async () => {
    const view = routes([], {
      'GET /v1/mdp-transactions/summary': () => ({ ...SUMMARY, windowOpen: false, lockedAt: null }),
    });
    const first = wrap(<DirectPayroll />, ['I-19:VIEW']);
    expect(await screen.findByText(t('i19.tile.closed'))).toBeTruthy();
    expect(screen.getByText(t('i19.tile.windowSub', ['202607', '—']))).toBeTruthy();
    first.unmount();
    void view;
    routes([], {
      'GET /v1/mdp-transactions/summary': () => ({
        ...SUMMARY,
        period: null,
        windowOpen: false,
        lockedAt: null,
      }),
    });
    wrap(<DirectPayroll />, ['I-19:VIEW']);
    expect(await screen.findByText(t('i19.tile.noPeriod'))).toBeTruthy();
    expect(screen.getByText(t('i19.registerTitleAll'))).toBeTruthy();
  });

  it('SRS-15-19: the maker captures a deduction of a permitted head in the open window', async () => {
    const calls = routes(
      [],
      { 'POST /v1/mdp-transactions': () => ({ status: 202, body: { approvalId: 90 } }) },
      ['2026-06', '2026-07'],
    );
    wrap(<DirectPayroll />, MAKER);
    // Drawn locked while the window and the heads load ("Loading"), open once both have answered.
    const capture = await screen.findByRole('button', { name: t('i19.newEntry') });
    await waitFor(() => expect(screen.getByRole('button', { name: t('i19.newEntry') })).toBeEnabled());
    expect(capture).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('i19.newEntry') }));
    const dialog = screen.getByRole('dialog');
    const submit = within(dialog).getByRole('button', { name: t('common.submit') });
    // Two windows open: the maker picks the month.
    await userEvent.selectOptions(within(dialog).getByLabelText(t('i19.period')), '2026-07');
    await userEvent.type(within(dialog).getByLabelText(t('i19.eis')), '100041');
    await userEvent.selectOptions(within(dialog).getByLabelText(t('i19.head')), '348');
    // A credit or more than two decimals is not a deduction amount.
    await userEvent.type(within(dialog).getByLabelText(t('i19.amount')), '-5');
    await userEvent.type(within(dialog).getByLabelText(t('common.reason')), 'LWF missed');
    expect(submit).toBeDisabled();
    await userEvent.clear(within(dialog).getByLabelText(t('i19.amount')));
    await userEvent.type(within(dialog).getByLabelText(t('i19.amount')), '12.505');
    expect(submit).toBeDisabled();
    await userEvent.clear(within(dialog).getByLabelText(t('i19.amount')));
    await userEvent.type(within(dialog).getByLabelText(t('i19.amount')), '12.50');
    await userEvent.click(submit);
    expect(await screen.findByText(t('i19.captured'))).toBeTruthy();
    expect(sent(calls, 'POST', '/mdp-transactions')).toEqual([
      { period: '2026-07', eis: '100041', payCode: 348, amount: 12.5, reason: 'LWF missed' },
    ]);
  });

  it('SRS-15-2: without an open window, or with no head configured, New MDP Entry is drawn disabled with the reason', async () => {
    routes([], {}, []);
    const view = wrap(<DirectPayroll />, MAKER);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: t('i19.newEntry') }).getAttribute('data-why')).toBe(
        t('i19.noWindow'),
      ),
    );
    expect(screen.getByRole('button', { name: t('i19.newEntry') })).toBeDisabled();
    view.unmount();
    routes([], { 'GET /v1/mdp-transactions/heads': () => [] });
    wrap(<DirectPayroll />, MAKER);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: t('i19.newEntry') }).getAttribute('data-why')).toBe(
        t('error.MDP-422-NOT-CONFIGURED'),
      ),
    );
  });

  it('SRS-G-37: an approved entry is amended (Edit) or cancelled (View) through the checker; a waiting, cancelled or closed-window one is held', async () => {
    const calls = routes(
      [
        entry(),
        entry({ id: 43, reference: 'MDP-202607-43', pendingApprovalId: 77, status: 'ENTERED' }),
        entry({ id: 44, reference: 'MDP-202607-44', status: 'CANCELLED' }),
        entry({ id: 45, reference: 'MDP-202606-45', period: '2026-06' }),
        entry({ id: 46, reference: 'MDP-202607-46', status: 'POSTED' }),
      ],
      {
        'POST /v1/mdp-transactions/41/amendments': () => ({ status: 202, body: { approvalId: 91 } }),
        'POST /v1/mdp-transactions/41/cancellations': () => ({ status: 202, body: { approvalId: 92 } }),
      },
    );
    wrap(<DirectPayroll />, MAKER);
    const table = await register();
    await within(table).findByText('MDP-202607-43');
    const editOf = (ref: string) =>
      within(rowOf(table, ref)).getByRole('button', { name: `${t('common.edit')} ${ref}` });
    expect(editOf('MDP-202607-43')).toBeDisabled();
    expect(editOf('MDP-202607-43').getAttribute('data-why')).toBe(t('i19.pending', [77]));
    expect(editOf('MDP-202607-44').getAttribute('data-why')).toBe(t('i19.why.cancelledKept'));
    expect(editOf('MDP-202606-45').getAttribute('data-why')).toBe(t('i19.windowClosed', ['JUN 2026']));
    expect(editOf('MDP-202607-46').getAttribute('data-why')).toBe(
      t('error.MDP-409-STATE', [t('i19.status.POSTED')]),
    );

    await userEvent.click(editOf('MDP-202607-41'));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByLabelText(t('i19.eis'))).toBeDisabled();
    await userEvent.clear(within(dialog).getByLabelText(t('i19.amount')));
    await userEvent.type(within(dialog).getByLabelText(t('i19.amount')), '175');
    await userEvent.type(within(dialog).getByLabelText(t('common.reason')), 'Correct rate');
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.submit') }));
    expect(await screen.findByText(t('i19.amended'))).toBeTruthy();
    expect(sent(calls, 'POST', '/amendments')).toEqual([{ amount: 175, reason: 'Correct rate' }]);

    // View opens the entry; a cancelled one offers no cancellation, an open one does.
    await userEvent.click(
      within(rowOf(table, 'MDP-202607-44')).getByRole('button', {
        name: `${t('common.view')} MDP-202607-44`,
      }),
    );
    const held = screen.getByRole('dialog', { name: t('i19.viewTitle', ['MDP-202607-44']) });
    expect(within(held).getByRole('button', { name: t('i19.cancelEntry') })).toBeDisabled();
    await userEvent.click(within(held).getByRole('button', { name: t('common.close') }));
    await userEvent.click(
      within(rowOf(table, 'MDP-202607-41')).getByRole('button', {
        name: `${t('common.view')} MDP-202607-41`,
      }),
    );
    const open = screen.getByRole('dialog', { name: t('i19.viewTitle', ['MDP-202607-41']) });
    expect(open.textContent).toContain('901 Professional Tax');
    await userEvent.click(within(open).getByRole('button', { name: t('i19.cancelEntry') }));
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'Recovered elsewhere');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.submit') }),
    );
    expect(await screen.findByText(t('i19.cancelRequested'))).toBeTruthy();
    expect(sent(calls, 'POST', '/cancellations')).toEqual([{ reason: 'Recovered elsewhere' }]);
  });

  it('SRS-G-29: the Checker gets ✔ on every row that is not cancelled, which opens the Approvals Inbox', async () => {
    routes([entry(), entry({ id: 44, reference: 'MDP-202607-44', status: 'CANCELLED' })]);
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    wrap(<DirectPayroll />, ['I-19:VIEW', 'I-19:APPROVE']);
    const table = await register();
    await within(table).findByText('MDP-202607-41');
    expect(within(rowOf(table, 'MDP-202607-44')).queryByRole('button', { name: '✔' })).toBeNull();
    await userEvent.click(within(rowOf(table, 'MDP-202607-41')).getByRole('button', { name: '✔' }));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('s=W-01'));
  });

  it('SRS-15-22: the drawn Filter opens the filters (status from the database list, OD-123); they go to the server; a refused capture shows its reason', async () => {
    const calls = routes([], {
      'POST /v1/mdp-transactions': () => problem(422, 'MDP-422-HEAD'),
    });
    wrap(<DirectPayroll />, MAKER);
    await screen.findByText(t('i19.empty'));
    // Closed until Filter is pressed (as drawn).
    expect(screen.queryByLabelText(t('i19.statusCol'))).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: t('i19.filterAct') }));
    const status = screen.getByLabelText(t('i19.statusCol'));
    expect(
      within(status)
        .getAllByRole('option')
        .map((o) => o.getAttribute('value')),
    ).toEqual(['', 'ENTERED', 'AMENDED', 'APPROVED', 'CANCELLED', 'POSTED']);
    await userEvent.selectOptions(status, 'APPROVED');
    await userEvent.type(screen.getByLabelText(t('i19.eis'), { selector: 'input[name="eis"]' }), '100041');
    await userEvent.click(screen.getByRole('button', { name: t('i19.filter') }));
    // The English key is sent (OD-123); the window's period stays the default of the register.
    await waitFor(() =>
      expect(
        calls.some(
          (c) =>
            c.url.includes('status=APPROVED') &&
            c.url.includes('eis=100041') &&
            c.url.includes('period=2026-07'),
        ),
      ).toBe(true),
    );
    await userEvent.click(screen.getByRole('button', { name: t('i19.newEntry') }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText(t('i19.eis')), '100041');
    await userEvent.type(within(dialog).getByLabelText(t('i19.amount')), '10');
    await userEvent.type(within(dialog).getByLabelText(t('common.reason')), 'x');
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.submit') }));
    expect(await screen.findByText(t('error.MDP-422-HEAD'))).toBeTruthy();
  });

  it('SRS-15-22: with EXPORT both drawn exports name the MDP Audit Report of the shown period', async () => {
    routes([entry()]);
    wrap(<DirectPayroll />, MAKER);
    await register();
    for (const label of [t('i19.exportRegister'), t('i19.exportAct')]) {
      await userEvent.click(screen.getByRole('button', { name: label }));
      const dialog = await screen.findByRole('dialog');
      expect(dialog.textContent).toContain(t('i19.auditReport'));
      await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
    }
  });

  it('SRS-G-30 (OD-121): the order card follows the selected entry - open to the Maker while it waits, view only once decided', async () => {
    const calls = routes(
      [entry({ id: 47, reference: 'MDP-202607-47', status: 'ENTERED', checkedBy: null }), entry()],
      {
        'GET /v1/mdp-transactions/47/documents': () => [],
        'GET /v1/mdp-transactions/41/documents': () => [
          {
            id: 9,
            fileName: 'mdp_authority.pdf',
            documentTypeCode: 'SANCTION_ORDER',
            documentTypeName: 'Sanction Order',
            referenceNumber: 'FCI/EST/2026/118',
            uploadedBy: 'maker.chennai',
            uploadedAt: '2026-07-20T05:00:00Z',
          },
        ],
        'GET /v1/document-types': () => [
          { code: 'SANCTION_ORDER', name: 'Sanction Order', referenceRequired: true },
          { code: 'OTHER', name: 'Other', referenceRequired: false },
        ],
        'POST /v1/mdp-transactions/47/documents': () => ({ status: 201, body: { id: 10 } }),
        'GET /v1/reference/lists': () =>
          referenceLists({
            MDP_ENTRY_STATUS: STATUSES,
            ORDER_COPY_DOCUMENT_TYPE: [
              ['SANCTION_ORDER', 'Sanction Order'],
              ['OTHER', 'Other'],
            ],
          }),
      },
    );
    wrap(<DirectPayroll />, MAKER);
    const table = await register();
    await within(table).findByText('MDP-202607-47');
    // The first row is selected on load: the waiting entry, whose order the Maker attaches.
    expect(rowOf(table, 'MDP-202607-47')).toHaveAttribute('aria-selected', 'true');
    let card = (await screen.findByText(t('wf.ord.title'))).closest('.card') as HTMLElement;
    expect(within(card).getByText(t('i19.ord.hint'))).toBeTruthy();
    const [type] = Array.from(card.querySelectorAll('select')) as HTMLSelectElement[];
    const inputs = () => Array.from(card.querySelectorAll('input')) as HTMLInputElement[];
    await userEvent.selectOptions(type as HTMLSelectElement, 'OTHER');
    await userEvent.type(inputs()[1] as HTMLInputElement, '2026-07-01');
    await userEvent.upload(
      inputs()[3] as HTMLInputElement,
      new File(['%PDF'], 'a.pdf', { type: 'application/pdf' }),
    );
    expect(await within(card).findByText(t('wf.ord.stored', [1]))).toBeTruthy();
    expect(calls.filter((c) => c.method === 'POST' && c.url.endsWith('/47/documents'))).toHaveLength(1);
    // The approved entry: its stored order is listed with View; the card is view only.
    await userEvent.click(within(table).getByText('MDP-202607-41'));
    card = (await screen.findByText('mdp_authority.pdf')).closest('.card') as HTMLElement;
    expect(within(card).getByText(t('wf.ord.viewOnly'))).toBeTruthy();
    expect(
      within(card)
        .getByRole('link', { name: t('wf.view') })
        .getAttribute('href'),
    ).toContain('/v1/mdp-transactions/41/documents/9');
  });
});
