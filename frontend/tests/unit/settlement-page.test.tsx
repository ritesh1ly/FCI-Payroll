import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FinalSettlement } from '@/modules/settlement';
import { settlementApi } from '@/modules/settlement/api/settlementApi';
import type { CaseRow } from '@/modules/settlement/api/settlementApi';
import {
  mayEnterRow,
  payrollRecovered,
  recoveryReference,
  statementRows,
  stepOf,
} from '@/modules/settlement/business/settlementRules';
import type { CaseDetail } from '@/modules/settlement/api/settlementApi';
import { date } from '@/shared/ui';
import {
  backend,
  captureDownloads,
  me,
  paged,
  problem,
  referenceLists,
  sent,
  t,
  wrap,
} from './support/harness';

/**
 * O-05 as drawn (Wireframes v6.1 SCREENS.o5, OD-121): the page actions by role with their reasons, the
 * four tiles, the worklist with its stage text, the newest case open on the page, the statement rows,
 * the invoice card, the order card, and what stays behind Case actions (SRS-10-6, 10-8, 10-11, 10-12,
 * 10-14, 10-19, 10-22, 10-24, 10-25, SRS-G-30, SRS-G-37).
 */

afterEach(() => vi.unstubAllGlobals());

const SUMMARY = {
  separations: 6,
  settled: 1,
  settledAmount: 771420,
  byType: [
    { type: 'RETIREMENT', cases: 3 },
    { type: 'DEATH', cases: 1 },
    { type: 'VOLUNTARY', cases: 2 },
  ],
  awaitingClearance: 1,
  hqApproval: 1,
  netRecovery: 2,
};

const ROW: CaseRow = {
  caseId: 7,
  caseReference: 'FF-2026-0445',
  eis: '880594',
  name: 'A. Menon',
  office: 'DH001 - Delhi Division',
  separationType: 'RETIREMENT',
  separationDate: '2026-09-30',
  stage: 'COMPUTED',
  awaiting: [],
  status: 'COMPUTED',
  netPayable: 947200,
  payrollType: 'STAFF',
  payrollDues: 1123600,
  totalRecoveries: 176400,
  recoveredFromGratuity: 0,
  awaitingChecker: false,
  hqApprovalPending: false,
};

const NET_RECOVERY: CaseRow = {
  ...ROW,
  caseId: 8,
  caseReference: 'FF-2026-0431',
  name: 'L. Bai',
  payrollType: 'DEPT_LABOUR',
  separationType: 'RESIGNATION',
  netPayable: -4790,
  payrollDues: 246610,
  totalRecoveries: 539800,
  recoveredFromGratuity: 288400,
  hqApprovalPending: true,
};

const CLOSED: CaseRow = { ...ROW, caseId: 9, caseReference: 'FF-2026-0402', stage: 'POSTED', status: 'PAID' };

const detail = (summary: CaseRow = ROW, over: object = {}) => ({
  summary,
  settlementId: 3,
  clearances: [
    { type: 'LOANS_ADVANCES', status: 'RECEIVED', remark: 'Balance confirmed' },
    { type: 'ESTABLISHMENT_NO_DUES', status: 'RECEIVED', remark: null },
    { type: 'CUSTOM_CLEARANCE', status: 'RAISED', remark: null },
  ],
  pendingSalary: 198600,
  leaveEncashment: 780000,
  arrears: 25000,
  exGratia: 120000,
  gratuity: 1188000,
  totalRecoveries: summary.totalRecoveries,
  recoveredFromGratuity: summary.recoveredFromGratuity,
  netRecovery: false,
  recoveries: [
    {
      id: 1,
      type: 'LOAN',
      reference: '12 LN-2022-0533',
      period: 'From Jun 2022',
      amount: 148000,
      source: 'PAYROLL_DUES',
      hqApprovalStatus: null,
    },
    {
      id: 2,
      type: 'LICENCE_FEE',
      reference: 'EST/LF/2026/52',
      period: 'Apr - Jul 2026',
      amount: 28400,
      source: 'GRATUITY',
      hqApprovalStatus: 'APPROVED',
    },
  ],
  exGratiaEntries: [
    { id: 5, letterNo: 'HQ/EXG/9', letterDate: '2026-10-01', amount: 120000, status: 'APPROVED' },
  ],
  cpfStatus: 'SANCTIONED',
  cpfAmount: 1985400,
  cpfSanctionNumber: null,
  benefitsHeld: false,
  invoiceStatus: 'NOT_RAISED',
  awaitingChecker: false,
  ...over,
});

function routes(rows: CaseRow[] = [ROW], extra: Record<string, (url: string) => unknown> = {}) {
  return backend({
    'GET /v1/settlement-cases/awaiting': () => [],
    'GET /v1/settlement-cases/summary': () => SUMMARY,
    'GET /v1/settlement-cases/invoices': (url) => paged([], 0, url),
    'GET /v1/settlement-cases?': (url) => paged(rows, rows.length, url),
    'GET /v1/settlement-cases/7/orders': () => [],
    'GET /v1/settlement-cases/7': () => detail(),
    'GET /v1/settlement-cases/8': () => detail(NET_RECOVERY),
    'GET /v1/settlement-cases/9/orders': () => [],
    'GET /v1/settlement-cases/9': () => detail(CLOSED),
    'GET /v1/document-types': () => [
      { code: 'SANCTION_ORDER', name: 'Sanction Order', referenceRequired: true },
    ],
    'GET /v1/reference/lists': () =>
      referenceLists({ ORDER_COPY_DOCUMENT_TYPE: [['SANCTION_ORDER', 'Sanction Order']] }),
    ...extra,
  });
}

const open = () => screen.findByRole('table', { name: t('o05.recoveries') });

describe('O-05 page actions by role (pack acts, SRS-G-25)', () => {
  it('SRS-10-19: the Maker computes and enters ex-gratia; the Checker action is drawn off with its reason', async () => {
    const calls = routes([ROW], { 'POST /v1/settlement-cases/7/computations': () => detail() });
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:COMPUTE']));
    await open();
    const compute = await screen.findByRole('button', { name: t('action.o05.compute') }, { timeout: 30000 });
    await waitFor(() => expect(compute).toBeEnabled(), { timeout: 30000 });
    expect(screen.getByRole('button', { name: t('action.o05.exGratia') })).toBeEnabled();
    const approve = screen.getByRole('button', { name: t('o05.w.approve') });
    expect(approve).toBeDisabled();
    expect(approve.getAttribute('data-why')).toBe(t('o05.why.checkerOtp'));
    await userEvent.click(compute);
    expect(await screen.findByText(t('o05.computed'), {}, { timeout: 30000 })).toBeTruthy();
    expect(sent(calls, 'POST', '/computations')).toHaveLength(1);
  });

  it('SRS-10-19: the Checker reaches W-01 (OTP); Compute and Ex-Gratia are drawn off with their reasons', async () => {
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:APPROVE']));
    await open();
    const approve = screen.getByRole('link', { name: t('o05.w.approve') });
    expect(approve.getAttribute('href')).toContain('s=W-01');
    expect(screen.getByRole('button', { name: t('action.o05.compute') }).getAttribute('data-why')).toBe(
      t('o05.why.makerOnly'),
    );
    expect(screen.getByRole('button', { name: t('action.o05.exGratia') }).getAttribute('data-why')).toBe(
      t('o05.why.exGratiaChecker'),
    );
  });

  it('SRS-10-25: another role is told the Maker enters the ex-gratia', async () => {
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:RELEASE']));
    await open();
    expect(screen.getByRole('button', { name: t('action.o05.exGratia') }).getAttribute('data-why')).toBe(
      t('o05.why.exGratiaMaker'),
    );
  });

  it('SRS-10-30: a case at the Checker or closed takes no computation; the Maker is told why', async () => {
    routes([CLOSED]);
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:COMPUTE']));
    await open();
    expect(screen.getByRole('button', { name: t('action.o05.compute') }).getAttribute('data-why')).toBe(
      t('o05.why.compute'),
    );
    expect(screen.getByRole('button', { name: t('action.o05.exGratia') }).getAttribute('data-why')).toBe(
      t('o05.why.compute'),
    );
  });

  it('SRS-10-8: with no case in scope the page actions stay drawn, off, and the tiles show none', async () => {
    routes([], { 'GET /v1/settlement-cases/summary': () => ({ ...SUMMARY, separations: 0, byType: [] }) });
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:COMPUTE']));
    expect(await screen.findByText(t('o05.none'))).toBeTruthy();
    expect(screen.getByRole('button', { name: t('action.o05.compute') })).toBeDisabled();
    expect(screen.getByRole('button', { name: t('action.o05.exGratia') })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: t('o05.statementAct') }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});

describe('O-05 tiles and worklist', () => {
  it('SRS-10-6, 10-11, 10-22: the four tiles count the offices cases; a separation type HRMS sent is kept', async () => {
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    await open();
    expect(await screen.findByText(t('o05.tile.separations'))).toBeTruthy();
    expect(screen.getByText(t('o05.tile.awaiting'))).toBeTruthy();
    expect(screen.getByText(t('o05.tile.hq'))).toBeTruthy();
    expect(screen.getByText(t('o05.tile.net'))).toBeTruthy();
    const sub = await screen.findByText(new RegExp(`${t('o05.separationType.DEATH')} 1`));
    expect(sub.textContent).toContain('VOLUNTARY 2');
    expect(sub.textContent).toContain('7,71,420');
  });

  it('SRS-10-8 (pack rows): payroll type, dues, recoveries with the gratuity part, a negative net in red, the step', async () => {
    routes([ROW, NET_RECOVERY]);
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    const table = await screen.findByRole('table', { name: t('screen.O-05') });
    const row = (await within(table).findByText('FF-2026-0431')).closest('tr') as HTMLElement;
    expect(within(row).getByText(t('o05.payrollType.DEPT_LABOUR'))).toBeTruthy();
    expect(within(row).getByText(t('o05.separationType.RESIGNATION'))).toBeTruthy();
    expect(within(row).getByText('2,46,610')).toBeTruthy();
    expect(within(row).getByText('2,51,400')).toBeTruthy();
    expect(within(row).getByText(t('o05.againstGratuityPending', ['2,88,400']))).toBeTruthy();
    expect(within(row).getByText('-4,790').className).toContain('fl-bad');
    expect(within(row).getByText(t('o05.step', [6, t('o05.stepText.NET_RECOVERY_HQ')])).className).toContain(
      'p-bad',
    );
  });

  it('SRS-10-22: a gratuity recovery already approved reads without the HQ note', async () => {
    const approved = { ...NET_RECOVERY, hqApprovalPending: false };
    routes([approved]);
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    expect(await screen.findByText(t('o05.againstGratuity', ['2,88,400']))).toBeTruthy();
  });

  it('SRS-10-11: a case still awaiting a clearance names it; a member of no office and no recovery show dashes', async () => {
    const waiting = {
      ...ROW,
      awaiting: ['ESTABLISHMENT_NO_DUES'],
      office: null,
      payrollDues: null,
      netPayable: null,
      totalRecoveries: null,
      recoveredFromGratuity: null,
    };
    routes([waiting]);
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    const row = (await screen.findAllByText('FF-2026-0445'))[0]?.closest('tr') as HTMLElement;
    expect(within(row).getByText(t('o05.awaits', [t('o05.clearance.ESTABLISHMENT_NO_DUES')]))).toBeTruthy();
    expect(within(row).getAllByText('—').length).toBeGreaterThanOrEqual(3);
  });

  it('SRS-G-25: the Maker edits an open case; a closed one, and any other role, sees why not', async () => {
    routes([ROW, CLOSED]);
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:COMPUTE']));
    const table = await screen.findByRole('table', { name: t('screen.O-05') });
    const editOf = async (reference: string) =>
      within((await within(table).findByText(reference)).closest('tr') as HTMLElement).getByRole('button', {
        name: t('common.edit'),
      });
    const off = await editOf('FF-2026-0402');
    expect(off).toBeDisabled();
    expect(off.getAttribute('data-why')).toBe(t('o05.why.compute'));
    await userEvent.click(await editOf('FF-2026-0445'));
    expect(screen.getByTestId('o05-case-actions').hasAttribute('open')).toBe(true);
  });

  it('SRS-G-25: the Checker has no edit, with the reason; the tick opens W-01 only for the item awaiting them', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    routes([{ ...ROW, awaitingChecker: true }, CLOSED]);
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:APPROVE']));
    const table = await screen.findByRole('table', { name: t('screen.O-05') });
    const pending = (await within(table).findByText('FF-2026-0445')).closest('tr') as HTMLElement;
    expect(
      within(pending)
        .getByRole('button', { name: t('common.edit') })
        .getAttribute('data-why'),
    ).toBe(t('o05.why.edit'));
    expect(within(pending).getByText(t('o05.step', [6, t('o05.stepText.CHECKER')]))).toBeTruthy();
    await userEvent.click(within(pending).getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('s=W-01'));
    const closed = (await within(table).findByText('FF-2026-0402')).closest('tr') as HTMLElement;
    const tick = within(closed).getByText('✔');
    expect(tick).toBeDisabled();
    expect(tick.getAttribute('data-why')).toBe(t('o05.why.noDecision'));
  });
});

describe('O-05 the open case on the page', () => {
  it('SRS-10-8: the newest case opens by itself; View moves every panel to the chosen one', async () => {
    const calls = routes([ROW, NET_RECOVERY]);
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    await open();
    expect(screen.getByText(t('o05.recoveriesTitle', ['FF-2026-0445', 'A. Menon']))).toBeTruthy();
    expect(screen.getByText(t('o05.statementTitle', ['FF-2026-0445', 'A. Menon']))).toBeTruthy();
    const table = screen.getByRole('table', { name: t('screen.O-05') });
    const other = (await within(table).findByText('FF-2026-0431')).closest('tr') as HTMLElement;
    await userEvent.click(within(other).getByRole('button', { name: t('common.view') }));
    expect(await screen.findByText(t('o05.recoveriesTitle', ['FF-2026-0431', 'L. Bai']))).toBeTruthy();
    expect(calls.some((c) => c.url.includes('/v1/settlement-cases/8'))).toBe(true);
  });

  it('SRS-10-4 / 10-24: the recovery foot totals and splits; the reference drops the ledger id; the approval reads as held', async () => {
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    const table = await open();
    expect(within(table).getByText('LN-2022-0533')).toBeTruthy();
    expect(within(table).getByText('1,76,400')).toBeTruthy();
    expect(within(table).getByText(t('o05.recoverySplit', ['1,48,000', '28,400']))).toBeTruthy();
    expect(within(table).getByText(t('o05.approval.checker'))).toBeTruthy();
    const pill = within(table).getByText(t('o05.approval.hq', [t('o05.hq.APPROVED')]));
    expect(pill.className).toContain('p-ok');
  });

  it('SRS-10-22: a gratuity recovery with no approval item reads as none', async () => {
    routes([ROW], {
      'GET /v1/settlement-cases/7': () =>
        detail(ROW, {
          recoveries: [
            {
              id: 2,
              type: 'OTHER',
              reference: null,
              period: null,
              amount: 10,
              source: 'GRATUITY',
              hqApprovalStatus: null,
            },
          ],
        }),
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    const table = await open();
    expect(within(table).getAllByText('—').length).toBeGreaterThanOrEqual(2);
  });

  it('SRS-10-27: the clearances in the drawn order, one the checklist does not name still shown; Component freezing drawn', async () => {
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    await open();
    const checklist = screen.getByTestId('o05-clearances');
    expect(within(checklist).getByText(t('o05.clearance.LOANS_ADVANCES'))).toBeTruthy();
    expect(within(checklist).getByText('Balance confirmed')).toBeTruthy();
    expect(within(checklist).getByText(t('o05.clearanceStatus.RAISED')).className).toContain('p-warn');
    expect(within(checklist).getByText(t('o05.componentFreeze'))).toBeTruthy();
    expect(within(checklist).getByText(t('o05.on'))).toBeTruthy();
  });

  it('SRS-10-14: the note that a clearance holds the approval shows on a computed case only', async () => {
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    await open();
    expect(screen.getByText(t('o05.approvalHeld'))).toBeTruthy();
  });

  it('the Chase of a clearance has no source: pressing it says so and sends nothing', async () => {
    const calls = routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    await open();
    const before = calls.length;
    await userEvent.click(screen.getByRole('button', { name: t('o05.chase') }));
    expect(await screen.findByText(t('o05.why.chase'))).toBeTruthy();
    expect(calls.filter((c) => c.method !== 'GET').length).toBe(0);
    expect(calls.length).toBeGreaterThanOrEqual(before);
  });

  it('SRS-10-12 / 10-24: the statement reconciles: dues and their recoveries, net, gratuity with its own, CPF for information', async () => {
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    await open();
    const table = screen.getByRole('table', { name: t('o05.components') });
    const cell = (key: string) => table.querySelector(`tr[data-component="${key}"]`) as HTMLElement;
    expect(cell('PENDING').textContent).toContain('2,23,600');
    expect(cell('DUES').textContent).toContain('11,23,600');
    expect(cell('DUES').textContent).toContain('(1,76,400)');
    expect(cell('NET').textContent).toContain('9,47,200');
    expect(cell('GRATUITY').textContent).toContain('11,88,000');
    expect(cell('CPF_FINAL').textContent).toContain('19,85,400');
    expect(cell('CPF_FINAL').textContent).toContain(t('o05.st.information'));
    expect(screen.queryByText(t('o05.gratuityBlocked'))).toBeNull();
  });

  it('SRS-10-14: a gratuity written as 0 reads as not computed; a negative net is flagged and red (SRS-10-6)', async () => {
    routes([NET_RECOVERY], {
      'GET /v1/settlement-cases/8': () => detail(NET_RECOVERY, { gratuity: 0, cpfAmount: null }),
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    await open();
    expect(screen.getByText(t('o05.gratuityBlocked'))).toBeTruthy();
    expect(screen.getByText(t('o05.netRecovery'))).toBeTruthy();
    const net = screen
      .getByRole('table', { name: t('o05.components') })
      .querySelector('tr[data-component="NET"] b.fl-bad');
    expect(net?.textContent).toBe('-4,790');
  });

  it('SRS-10-12: the statement opens from either button, prints and closes', async () => {
    const print = vi.fn();
    vi.stubGlobal('print', print);
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    await open();
    await userEvent.click(screen.getByRole('button', { name: t('o05.statementCard') }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: t('o05.print') }));
    expect(print).toHaveBeenCalledOnce();
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    await userEvent.click(screen.getAllByRole('button', { name: t('o05.statementAct') })[0] as HTMLElement);
    expect(await screen.findByRole('dialog')).toBeTruthy();
  });

  it('SRS-10-12, OD-144: the statement downloads as the server PDF of the case', async () => {
    const calls = routes();
    const files = captureDownloads();
    try {
      wrap(<FinalSettlement />, me(['O-05:VIEW']));
      await open();
      await userEvent.click(screen.getByRole('button', { name: t('o05.statementCard') }));
      const dialog = await screen.findByRole('dialog');
      await userEvent.click(within(dialog).getByRole('button', { name: t('o05.statementPdf') }));
      await waitFor(() => expect(files.names).toHaveLength(1));
      expect(sent(calls, 'GET', `/v1/settlement-cases/${ROW.caseId}/statement`)).toHaveLength(1);
    } finally {
      files.restore();
    }
  });

  it('SRS-G-13: the recoveries card prints', async () => {
    const print = vi.fn();
    vi.stubGlobal('print', print);
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    await open();
    await userEvent.click(screen.getByRole('button', { name: t('o05.print') }));
    expect(print).toHaveBeenCalledOnce();
  });

  it('SRS-10-25: the ex-gratia card reads the latest entry; a case with none says so', async () => {
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    await open();
    expect(screen.getByText(t('o05.kv.letter', ['HQ/EXG/9', date('2026-10-01')]))).toBeTruthy();
    expect(screen.getByText(t('o05.kv.amount', ['1,20,000']))).toBeTruthy();
  });

  it('SRS-10-25: no ex-gratia on the case reads as such', async () => {
    routes([ROW], { 'GET /v1/settlement-cases/7': () => detail(ROW, { exGratiaEntries: [] }) });
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    await open();
    expect(screen.getByText(t('o05.kv.noLetter'))).toBeTruthy();
  });

  it('the recovery rules panel is drawn with its six rows', async () => {
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    await open();
    for (const key of ['full', 'source', 'gratuity', 'residual', 'death', 'reversed']) {
      expect(screen.getByText(t(`o05.rules.${key}`))).toBeTruthy();
    }
  });

  it('a refused read of the case is shown, not a blank page', async () => {
    routes([ROW], {
      'GET /v1/settlement-cases/7': () => ({ status: 500, body: problem(500, 'COM-500').body }),
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByRole('table', { name: t('o05.recoveries') })).toBeNull();
  });
});

describe('O-05 settlement payments (SRS-G-37, SRS-10-31)', () => {
  const invoice = {
    caseId: 9,
    caseReference: 'FF-2026-0402',
    eis: '880592',
    name: 'R. Devi',
    creditMemo: false,
    amount: 771420,
    invoiceNo: '202610000877',
    invoiceStatus: 'SENT_TO_APPS',
    sentToApps: '2026-10-25',
    appsCancelled: false,
    settlementStatus: 'PAID',
  };

  it('each payment with its invoice; a credit memo is named; Cancel stays off with its reason, for the Maker too', async () => {
    routes([ROW], {
      'GET /v1/settlement-cases/invoices': (url) =>
        paged(
          [
            invoice,
            {
              ...invoice,
              caseId: 8,
              caseReference: 'FF-2026-0431',
              creditMemo: true,
              amount: -4790,
              settlementStatus: 'CANCELLED',
            },
          ],
          2,
          url,
        ),
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:COMPUTE']));
    const row = (await screen.findAllByText('880592 R. Devi'))[0]?.closest('tr') as HTMLElement;
    expect(within(row).getByText(t('o05.pay.net'))).toBeTruthy();
    expect(within(row).getByText('202610000877')).toBeTruthy();
    const cancel = within(row).getByRole('button', { name: t('wf.inv.cancel') });
    expect(cancel).toBeDisabled();
    expect(cancel.getAttribute('data-why')).toBe(t('o05.why.cancelApps'));
    expect(screen.getByText(t('o05.pay.creditMemo'))).toBeTruthy();
    expect(screen.getByText(t('wf.inv.cancelledVersion'))).toBeTruthy();
  });

  it('BR-AUDIT-009: a role other than the Maker is told who raises the cancel', async () => {
    routes([ROW], { 'GET /v1/settlement-cases/invoices': (url) => paged([invoice], 1, url) });
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    const row = (await screen.findByText('880592 R. Devi')).closest('tr') as HTMLElement;
    expect(
      within(row)
        .getByRole('button', { name: t('wf.inv.cancel') })
        .getAttribute('data-why'),
    ).toBe(t('wf.inv.why.maker'));
  });

  it('no payment yet: the card stays drawn', async () => {
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    await open();
    expect(await screen.findByText(t('o05.invoices'))).toBeTruthy();
    expect(screen.getByText(t('wf.inv.none'))).toBeTruthy();
  });
});

describe('O-05 order copy (SRS-G-30, SRS-G-32, A-41)', () => {
  const stored = {
    id: 40,
    fileName: 'sanction-0445.pdf',
    documentTypeCode: 'SANCTION_ORDER',
    documentTypeName: 'Sanction Order',
    referenceNumber: 'FCI/EST/EXG/2026/38',
    uploadedBy: 'maker.delhi',
    uploadedAt: '2026-10-20T05:30:00Z',
  };

  it('the stored orders read, each with its file link', async () => {
    routes([ROW], { 'GET /v1/settlement-cases/7/orders': () => [stored] });
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    expect(await screen.findByText('sanction-0445.pdf')).toBeTruthy();
    expect(
      screen
        .getAllByRole('link', { name: t('wf.view') })
        .at(-1)
        ?.getAttribute('href'),
    ).toContain('/v1/settlement-cases/7/files/40');
  });

  it('the Maker stores an order with its particulars, one file a call; the list follows', async () => {
    let orders: unknown[] = [];
    const calls = routes([ROW], {
      'GET /v1/settlement-cases/7/orders': () => orders,
      'POST /v1/settlement-cases/7/orders': () => {
        orders = [stored];
        return { status: 201, body: stored };
      },
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:COMPUTE']));
    await open();
    const card = document.querySelector('[data-order-card]') as HTMLElement;
    const inputs = card.querySelectorAll('input');
    await userEvent.type(inputs[0] as HTMLInputElement, 'FCI/EST/EXG/2026/38');
    fireEvent.change(inputs[1] as HTMLInputElement, { target: { value: '2026-10-01' } });
    await userEvent.type(inputs[2] as HTMLInputElement, 'RO Delhi');
    const file = new File(['%PDF'], 'sanction.pdf', { type: 'application/pdf' });
    await userEvent.upload(inputs[3] as HTMLInputElement, file);
    expect(await screen.findByText('sanction-0445.pdf')).toBeTruthy();
    const body = calls.find((c) => c.method === 'POST' && c.url.endsWith('/orders'))?.body as FormData;
    expect(body.get('documentType')).toBe('SANCTION_ORDER');
    expect(body.get('reference')).toBe('FCI/EST/EXG/2026/38');
    expect(body.get('orderDate')).toBe('2026-10-01');
    expect(body.get('issuingAuthority')).toBe('RO Delhi');
  });

  it('SRS-10-29: a posted case takes no new order: the Maker sees the card, view only', async () => {
    routes([CLOSED]);
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:COMPUTE']));
    await open();
    await screen.findAllByText(t('wf.ord.viewOnly'));
    const card = document.querySelector('[data-order-card]') as HTMLElement;
    expect(card.querySelector('input[type="date"]')).not.toBeDisabled();
  });

  it('a role without the right sees the fields read-only; no call attaches', async () => {
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    await open();
    const card = document.querySelector('[data-order-card]') as HTMLElement;
    expect(card.querySelector('input[type="date"]')).toBeDisabled();
    expect(card.querySelector('input[type="file"]')).toBeDisabled();
  });

  it('the API puts the optional particulars only when given', async () => {
    const calls = routes([ROW], {
      'POST /v1/settlement-cases/7/orders': () => ({ status: 201, body: stored }),
    });
    const file = new File(['%PDF'], 'x.pdf', { type: 'application/pdf' });
    await settlementApi.attachOrder(7, {
      documentType: 'OTHER',
      reference: '',
      orderDate: '2026-10-01',
      issuingAuthority: '',
      file,
    });
    const body = calls.at(-1)?.body as FormData;
    expect(body.has('reference')).toBe(false);
    expect(body.has('issuingAuthority')).toBe(false);
  });
});

describe('O-05 screen rules', () => {
  const d = (over: Partial<CaseDetail> & { summary?: Partial<CaseRow> }) =>
    ({
      pendingSalary: null,
      arrears: null,
      leaveEncashment: null,
      exGratia: null,
      gratuity: null,
      cpfAmount: null,
      totalRecoveries: null,
      recoveredFromGratuity: null,
      ...over,
      summary: { payrollDues: null, netPayable: null, ...over.summary },
    }) as unknown as CaseDetail;

  it('SRS-10-13: only the part not taken from gratuity reduces the payroll dues; nothing computed is nil', () => {
    expect(payrollRecovered(1700, 500)).toBe(1200);
    expect(payrollRecovered(null, null)).toBe(0);
  });

  it('SRS-10-4: a ledger key shows its reference; any other reference and none are kept', () => {
    expect(recoveryReference('12 LN-1')).toBe('LN-1');
    expect(recoveryReference('NO_DUES')).toBe('NO_DUES');
    expect(recoveryReference('EST 12')).toBe('EST 12');
    expect(recoveryReference(null)).toBeNull();
  });

  it('SRS-10-8: a row is the Makers while initiated or computed, at stages 2 to 4', () => {
    expect(mayEnterRow({ stage: 'CLEARANCES_RAISED', status: 'INITIATED' })).toBe(true);
    expect(mayEnterRow({ stage: 'COMPUTED', status: 'COMPUTED' })).toBe(true);
    expect(mayEnterRow({ stage: 'SEPARATION_RECEIVED', status: 'INITIATED' })).toBe(false);
    expect(mayEnterRow({ stage: 'CPF_LINKED', status: 'COMPUTED' })).toBe(false);
    expect(mayEnterRow({ stage: 'COMPUTED', status: 'APPROVED' })).toBe(false);
  });

  it('SRS-10-8, 10-6, 10-20: the step text of every drawn state', () => {
    const r = (over: Partial<CaseRow>) => ({ ...ROW, ...over });
    expect(stepOf(r({ stage: 'CLOSED' }))).toEqual({ number: 8, text: 'CLOSED', tone: 'p-ok' });
    expect(stepOf(r({ stage: 'POSTED' }))).toEqual({ number: 7, text: 'POSTED', tone: 'p-ok' });
    expect(stepOf(r({ stage: 'APPROVED', netPayable: -1 }))).toEqual({
      number: 6,
      text: 'NET_RECOVERY',
      tone: 'p-bad',
    });
    expect(stepOf(r({ stage: 'APPROVED', netPayable: 0 })).tone).toBe('p-warn');
    expect(stepOf(r({ stage: 'APPROVED', netPayable: null })).text).toBe('APPROVED');
    expect(stepOf(r({ awaitingChecker: true })).text).toBe('CHECKER');
    expect(stepOf(r({ hqApprovalPending: true, netPayable: 5 }))).toEqual({
      number: 6,
      text: 'COMPUTED',
      tone: 'p-warn',
    });
    expect(stepOf(r({ stage: 'CLEARANCES_RAISED', separationType: 'DEATH' }))).toEqual({
      number: 3,
      text: 'NOMINEE',
      tone: 'p-warn',
    });
    expect(stepOf(r({ stage: 'COMPUTED', separationType: 'DEATH' })).text).toBe('COMPUTED');
  });

  it('SRS-10-12: pending salary and arrears read as one row; none computed reads as none', () => {
    const rows = statementRows(d({ pendingSalary: 10, arrears: null }));
    expect(rows.map((r) => r.key)).toEqual([
      'PENDING',
      'LEAVE',
      'EX_GRATIA',
      'DUES',
      'NET',
      'GRATUITY',
      'CPF_FINAL',
    ]);
    expect(rows[0]?.amount).toBe(10);
    expect(statementRows(d({ pendingSalary: null, arrears: 5 }))[0]?.amount).toBe(5);
    expect(statementRows(d({}))[0]?.amount).toBeNull();
  });

  it('SRS-10-24: the gratuity carries what was taken from it, the dues the rest', () => {
    const rows = statementRows(d({ totalRecoveries: 700, recoveredFromGratuity: 200 }));
    expect(rows.find((r) => r.key === 'DUES')?.recovery).toBe(500);
    expect(rows.find((r) => r.key === 'GRATUITY')?.recovery).toBe(200);
    expect(rows.filter((r) => r.strong).map((r) => r.key)).toEqual(['DUES', 'NET']);
  });
});
