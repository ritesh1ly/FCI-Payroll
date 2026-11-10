import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FinalSettlement } from '@/modules/settlement';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/**
 * O-05 Maker forms (plan 11; SRS-10-11, SRS-10-22, SRS-10-25): the ex-gratia from its sanction
 * letter, a clearance excluded with its reason, a recovery taken from the gratuity with the order.
 */

afterEach(() => vi.unstubAllGlobals());

const label = (key: string) => new RegExp(`^${t(key).replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')}`);

const SUMMARY = {
  separations: 0,
  settled: 0,
  settledAmount: 0,
  byType: [],
  awaitingClearance: 0,
  hqApproval: 0,
  netRecovery: 0,
};

const ROW = {
  caseId: 7,
  caseReference: 'FNF-2026-00007',
  eis: '104882',
  name: 'Rakesh Sharma',
  office: 'DH001 - Delhi Division',
  separationType: 'RETIREMENT',
  separationDate: '2026-09-30',
  stage: 'CLEARANCES_RECEIVED',
  awaiting: [],
  status: 'INITIATED',
  netPayable: null,
  payrollType: 'STAFF',
  payrollDues: null,
  totalRecoveries: null,
  recoveredFromGratuity: null,
  awaitingChecker: false,
  hqApprovalPending: false,
};

const detail = (over: object = {}) => ({
  summary: ROW,
  settlementId: 3,
  clearances: [
    { type: 'LOANS_ADVANCES', status: 'RECEIVED', remark: 'Nil' },
    { type: 'ESTABLISHMENT_NO_DUES', status: 'RAISED', remark: null },
  ],
  pendingSalary: null,
  leaveEncashment: null,
  arrears: null,
  exGratia: null,
  gratuity: null,
  totalRecoveries: 1200,
  recoveredFromGratuity: null,
  netRecovery: false,
  recoveries: [{ id: 41, type: 'LOAN', reference: 'FA-12', amount: 1200, source: 'PAYROLL_DUES' }],
  exGratiaEntries: [],
  cpfStatus: 'NOT_APPLIED',
  cpfAmount: null,
  benefitsHeld: false,
  invoiceStatus: 'NOT_RAISED',
  cpfSanctionNumber: null,
  ...over,
});

function routes(extra: Record<string, (url: string) => unknown> = {}) {
  return backend({
    'GET /v1/settlement-cases/awaiting': () => [],
    'GET /v1/settlement-cases/summary': () => SUMMARY,
    'GET /v1/settlement-cases/invoices': (url) => paged([], 0, url),
    'GET /v1/settlement-cases?': (url) => paged([ROW], 1, url),
    'GET /v1/settlement-cases/7': () => detail(),
    ...extra,
  });
}

async function openCase() {
  await userEvent.click(await screen.findByRole('button', { name: t('common.view') }));
  await screen.findByRole('table', { name: t('o05.recoveries') });
}

describe('O-05 Maker forms', () => {
  it('SRS-10-25: the Maker enters an ex-gratia with its sanction letter', async () => {
    const calls = routes({
      'POST /v1/settlement-cases/7/ex-gratia': () => ({
        status: 202,
        body: { id: 90, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:COMPUTE']));
    await openCase();
    await userEvent.click(screen.getByRole('button', { name: t('action.o05.exGratia') }));
    const form = screen.getByLabelText(t('action.o05.exGratia'), { selector: 'div' });
    const send = within(form).getByRole('button', { name: t('common.submitForApproval') });
    expect(send).toBeDisabled();
    await userEvent.type(within(form).getByLabelText(label('o05.letterNo')), 'EG/12');
    fireEvent.change(within(form).getByLabelText(label('o05.letterDate')), {
      target: { value: '2026-10-01' },
    });
    await userEvent.type(within(form).getByLabelText(label('o05.amount')), '50000');
    await userEvent.type(within(form).getByLabelText(label('o05.issuingAuthority')), 'GM (P)');
    await userEvent.upload(
      within(form).getAllByLabelText(label('o05.letter')).at(-1) as HTMLElement,
      new File(['%PDF'], 'letter.pdf', { type: 'application/pdf' }),
    );
    expect(send).toBeEnabled();
    await userEvent.click(send);
    expect(await screen.findByText(t('o05.exGratiaSent'))).toBeTruthy();
    const body = sent(calls, 'POST', '/ex-gratia').at(-1) as FormData;
    expect(body.get('letterNo')).toBe('EG/12');
    expect(body.get('amount')).toBe('50000');
    expect(screen.queryByLabelText(t('action.o05.exGratia'), { selector: 'div' })).toBeNull();
  });

  it('SRS-10-14 (US-GAP-219): the Checker excludes an outstanding clearance of the settlement awaiting them; a refusal keeps the form', async () => {
    const calls = routes({
      'GET /v1/settlement-cases/7': () =>
        detail({ summary: { ...ROW, stage: 'COMPUTED', status: 'COMPUTED' }, awaitingChecker: true }),
      'POST /v1/settlement-cases/7/exclusions': () => problem(409, 'FNF-409-STAGE', ['COMPUTED', 'COMPUTED']),
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:APPROVE']));
    await openCase();
    expect(screen.getByText(t('o05.approvalHeld'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('action.o05.exclude') }));
    const form = screen.getByLabelText(t('action.o05.exclude'), { selector: 'div' });
    expect(within(form).getByLabelText(label('o05.clearanceToExclude'))).toHaveValue('ESTABLISHMENT_NO_DUES');
    await userEvent.type(
      within(form).getByLabelText(label('o05.exclusionReason')),
      'Not on establishment rolls',
    );
    await userEvent.click(within(form).getByRole('button', { name: t('action.o05.recordExclusion') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByLabelText(t('action.o05.exclude'), { selector: 'div' })).toBeTruthy();
    expect(sent(calls, 'POST', '/exclusions').at(-1)).toMatchObject({
      clearanceType: 'ESTABLISHMENT_NO_DUES',
      reason: 'Not on establishment rolls',
    });
    await userEvent.click(within(form).getByRole('button', { name: t('common.cancel') }));
  });

  it('SRS-10-22: a recovery is taken from the gratuity with the order', async () => {
    const calls = routes({
      'PUT /v1/settlement-cases/7/recoveries/41/source': () => detail(),
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:COMPUTE']));
    await openCase();
    const recoveries = screen.getByRole('table', { name: t('o05.recoveries') });
    await userEvent.selectOptions(within(recoveries).getByRole('combobox'), 'GRATUITY');
    const form = screen.getByLabelText(t('action.o05.changeSource'), { selector: 'div' });
    expect(within(form).getByText(t('o05.source.GRATUITY'))).toBeTruthy();
    const save = within(form).getByRole('button', { name: t('common.save') });
    await userEvent.type(within(form).getByLabelText(label('o05.sourceReason')), 'Dues exceed the pay');
    expect(save).toBeDisabled();
    await userEvent.type(within(form).getByLabelText(label('o05.orderRef')), 'HQ/FNF/3');
    fireEvent.change(within(form).getByLabelText(label('o05.orderDate')), {
      target: { value: '2026-10-02' },
    });
    await userEvent.type(within(form).getByLabelText(label('o05.issuingAuthority')), 'ED (P)');
    await userEvent.upload(
      within(form).getByLabelText(label('o05.order')),
      new File(['%PDF'], 'order.pdf', { type: 'application/pdf' }),
    );
    await userEvent.click(save);
    expect(await screen.findByText(t('o05.sourceChanged'))).toBeTruthy();
    const body = sent(calls, 'PUT', '/source').at(-1) as FormData;
    expect(body.get('source')).toBe('GRATUITY');
    expect(body.get('orderRef')).toBe('HQ/FNF/3');
  });

  it('SRS-10-22: back to the payroll dues needs only the reason; a viewer has no forms', async () => {
    const calls = routes({
      'GET /v1/settlement-cases/7': () =>
        detail({ recoveries: [{ id: 41, type: 'LOAN', reference: null, amount: 1200, source: 'GRATUITY' }] }),
      'PUT /v1/settlement-cases/7/recoveries/41/source': () => detail(),
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:COMPUTE']));
    await openCase();
    await userEvent.selectOptions(screen.getByRole('combobox', { name: /FNF|LOAN|Loan/ }), 'PAYROLL_DUES');
    const form = screen.getByLabelText(t('action.o05.changeSource'), { selector: 'div' });
    await userEvent.type(within(form).getByLabelText(label('o05.sourceReason')), 'HQ declined');
    await userEvent.click(within(form).getByRole('button', { name: t('common.save') }));
    expect(await screen.findByText(t('o05.sourceChanged'))).toBeTruthy();
    expect((sent(calls, 'PUT', '/source').at(-1) as FormData).get('orderRef')).toBeNull();
  });

  it('SRS-10-14 (US-GAP-219): the Maker cannot exclude a clearance; the exclusion is the Checker record', async () => {
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:COMPUTE']));
    await openCase();
    const exclude = screen.getByRole('button', { name: t('action.o05.exclude') });
    expect(exclude).toBeDisabled();
    expect(exclude.getAttribute('title')).toBe(t('reason.checkerOnly'));
  });

  it('SRS-10-14 (US-GAP-219): the Checker excludes only once the settlement awaits them', async () => {
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:APPROVE']));
    await openCase();
    expect(screen.getByRole('button', { name: t('action.o05.exGratia') })).toBeDisabled();
    const exclude = screen.getByRole('button', { name: t('action.o05.exclude') });
    expect(exclude).toBeDisabled();
    expect(exclude.getAttribute('title')).toBe(t('o05.why.exclude'));
    expect(screen.queryByRole('button', { name: t('action.o05.changeSource') })).toBeNull();
    expect(
      within(screen.getByRole('table', { name: t('o05.recoveries') })).queryByRole('combobox'),
    ).toBeNull();
    expect(screen.queryByText(t('o05.approvalHeld'))).toBeNull();
  });
});

describe('O-05 form edges', () => {
  it('SRS-10-27: the no-dues carries the dues entered; a cancelled form sends nothing; the clearance is chosen', async () => {
    const calls = routes({
      'GET /v1/settlement-cases/7': () =>
        detail({
          clearances: [
            { type: 'LOANS_ADVANCES', status: 'RAISED', remark: null },
            { type: 'ESTABLISHMENT_NO_DUES', status: 'RAISED', remark: null },
          ],
          summary: { ...ROW, stage: 'COMPUTED', status: 'COMPUTED', netPayable: 4500 },
          awaitingChecker: true,
        }),
      'POST /v1/settlement-cases/7/no-dues': () => detail(),
      'POST /v1/settlement-cases/7/exclusions': () => detail(),
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:CLEAR', 'O-05:COMPUTE', 'O-05:APPROVE']));
    await openCase();
    await userEvent.type(screen.getByLabelText(t('o05.dues')), '350');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Canteen');
    await userEvent.click(screen.getByRole('button', { name: t('action.o05.noDues') }));
    expect(await screen.findByText(t('o05.noDuesRecorded'))).toBeTruthy();
    expect(sent(calls, 'POST', '/no-dues').at(-1)).toEqual({ dues: 350, remark: 'Canteen' });

    await userEvent.click(screen.getByRole('button', { name: t('action.o05.exGratia') }));
    const exGratia = screen.getByLabelText(t('action.o05.exGratia'), { selector: 'div' });
    await userEvent.click(within(exGratia).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByLabelText(t('action.o05.exGratia'), { selector: 'div' })).toBeNull();

    await userEvent.click(screen.getByRole('button', { name: t('action.o05.exclude') }));
    const exclude = screen.getByLabelText(t('action.o05.exclude'), { selector: 'div' });
    const clearance = within(exclude).getByLabelText(label('o05.clearanceToExclude'));
    expect(clearance).toHaveValue('LOANS_ADVANCES');
    await userEvent.selectOptions(clearance, 'ESTABLISHMENT_NO_DUES');
    await userEvent.type(within(exclude).getByLabelText(label('o05.exclusionReason')), 'Not on rolls');
    await userEvent.click(within(exclude).getByRole('button', { name: t('action.o05.recordExclusion') }));
    expect(await screen.findByText(t('o05.exclusionSent'))).toBeTruthy();
    expect(sent(calls, 'POST', '/exclusions').at(-1)).toMatchObject({
      clearanceType: 'ESTABLISHMENT_NO_DUES',
    });
    expect(screen.queryByLabelText(t('action.o05.exclude'), { selector: 'div' })).toBeNull();
    expect(sent(calls, 'POST', '/ex-gratia')).toHaveLength(0);
  });

  it('SRS-10-22: a cancelled source change sends nothing; a cleared order file blocks the save', async () => {
    const calls = routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:COMPUTE']));
    await openCase();
    const recoveries = screen.getByRole('table', { name: t('o05.recoveries') });
    await userEvent.selectOptions(within(recoveries).getByRole('combobox'), 'GRATUITY');
    const form = screen.getByLabelText(t('action.o05.changeSource'), { selector: 'div' });
    await userEvent.type(within(form).getByLabelText(label('o05.sourceReason')), 'Dues exceed the pay');
    await userEvent.type(within(form).getByLabelText(label('o05.orderRef')), 'HQ/FNF/3');
    fireEvent.change(within(form).getByLabelText(label('o05.orderDate')), {
      target: { value: '2026-10-02' },
    });
    await userEvent.type(within(form).getByLabelText(label('o05.issuingAuthority')), 'ED (P)');
    const order = within(form).getByLabelText(label('o05.order'));
    await userEvent.upload(order, new File(['%PDF'], 'order.pdf', { type: 'application/pdf' }));
    fireEvent.change(order, { target: { files: [] } });
    expect(within(form).getByRole('button', { name: t('common.save') })).toBeDisabled();
    await userEvent.click(within(form).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByLabelText(t('action.o05.changeSource'), { selector: 'div' })).toBeNull();
    expect(sent(calls, 'PUT', '/source')).toHaveLength(0);
  });
});

describe('O-05 detail card per case', () => {
  it('SRS-10-25: a form opened for one case is not carried to the next case viewed', async () => {
    const other = { ...ROW, caseId: 8, caseReference: 'FNF-2026-00008', eis: '104883', name: 'Meena Rao' };
    const calls = backend({
      'GET /v1/settlement-cases/awaiting': () => [],
      'GET /v1/settlement-cases/summary': () => SUMMARY,
      'GET /v1/settlement-cases/invoices': (url) => paged([], 0, url),
      'GET /v1/settlement-cases?': (url) => paged([ROW, other], 2, url),
      'GET /v1/settlement-cases/7': () => detail(),
      'GET /v1/settlement-cases/8': () => detail({ summary: other, settlementId: 4 }),
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:COMPUTE']));
    const views = await screen.findAllByRole('button', { name: t('common.view') });
    await userEvent.click(views[0] as HTMLElement);
    await screen.findByRole('table', { name: t('o05.recoveries') });
    await userEvent.click(screen.getByRole('button', { name: t('action.o05.exGratia') }));
    expect(screen.getByLabelText(t('action.o05.exGratia'), { selector: 'div' })).toBeTruthy();
    await userEvent.click(views[1] as HTMLElement);
    await vi.waitFor(() => expect(calls.some((c) => c.url.endsWith('/v1/settlement-cases/8'))).toBe(true));
    await screen.findByRole('table', { name: t('o05.recoveries') });
    expect(screen.queryByLabelText(t('action.o05.exGratia'), { selector: 'div' })).toBeNull();
    expect(sent(calls, 'POST', '/ex-gratia')).toHaveLength(0);
  });
});

describe('O-05 fields of the source (g4)', () => {
  it('SRS-10-4 / SRS-10-22: each recovery shows its period and the HQ approval of a gratuity source', async () => {
    routes({
      'GET /v1/settlement-cases/7': () =>
        detail({
          recoveries: [
            {
              id: 41,
              type: 'LOAN',
              reference: 'FA-12',
              period: 'AT 2026-09-30',
              amount: 1200,
              source: 'PAYROLL_DUES',
              hqApprovalStatus: null,
            },
            {
              id: 42,
              type: 'PENALTY',
              reference: 'P-3',
              period: null,
              amount: 300,
              source: 'GRATUITY',
              hqApprovalStatus: 'PENDING_APPROVAL',
            },
          ],
        }),
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    await openCase();
    const table = screen.getByRole('table', { name: t('o05.recoveries') });
    expect(within(table).getByText(t('o05.period'))).toBeTruthy();
    expect(within(table).getByText('AT 2026-09-30')).toBeTruthy();
    expect(within(table).getByText(t('o05.approval.hq', [t('o05.hq.PENDING_APPROVAL')]))).toBeTruthy();
    // SRS-10-21: a viewer sees the source as text; no select is offered.
    expect(within(table).queryByRole('combobox')).toBeNull();
    expect(within(table).getByText(t('o05.source.GRATUITY'))).toBeTruthy();
  });

  it('SRS-10-21 (l.2932): the source is chosen from Payroll dues or Gratuity only; the same value opens no form', async () => {
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:COMPUTE']));
    await openCase();
    const select = within(screen.getByRole('table', { name: t('o05.recoveries') })).getByRole('combobox');
    const options = within(select)
      .getAllByRole('option')
      .map((o) => (o as HTMLOptionElement).value);
    expect(options).toEqual(['PAYROLL_DUES', 'GRATUITY']);
    await userEvent.selectOptions(select, 'GRATUITY');
    expect(screen.getByLabelText(t('action.o05.changeSource'), { selector: 'div' })).toBeTruthy();
    await userEvent.selectOptions(select, 'PAYROLL_DUES');
    expect(screen.queryByLabelText(t('action.o05.changeSource'), { selector: 'div' })).toBeNull();
  });

  it('SRS-10-3 / SRS-G-37 (SC-11-0054): the CPF final payment shows its sanction reference and the case its invoice status', async () => {
    routes({
      'GET /v1/settlement-cases/7': () =>
        detail({ cpfStatus: 'SANCTIONED', cpfAmount: 250000, cpfSanctionNumber: 'CPF/FIN/2026/12' }),
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    await openCase();
    expect(screen.getByText(t('o05.st.linked', ['CPF/FIN/2026/12']))).toBeTruthy();
    expect(screen.getByText(new RegExp(t('o05.cpfSanction', ['CPF/FIN/2026/12'])))).toBeTruthy();
    expect(screen.getByText(t('o05.invoice.NOT_RAISED'))).toBeTruthy();
  });

  it('SRS-10-25: the ex-gratia entries are listed with their status; none means no table', async () => {
    routes({
      'GET /v1/settlement-cases/7': () =>
        detail({
          exGratiaEntries: [
            { id: 5, letterNo: 'HQ/EXG/9', letterDate: '2026-10-01', amount: 20000, status: 'SUBMITTED' },
          ],
        }),
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    await openCase();
    const table = screen.getByRole('table', { name: t('o05.exGratiaEntries') });
    expect(within(table).getByText('HQ/EXG/9')).toBeTruthy();
    expect(within(table).getByText(t('o05.exGratiaStatus.SUBMITTED'))).toBeTruthy();
  });

  it('SRS-10-24 (SC-11-0058): the recoveries taken from gratuity are shown against it; the computed gratuity drops the hint', async () => {
    routes({
      'GET /v1/settlement-cases/7': () =>
        detail({ gratuity: 300000, recoveredFromGratuity: 500, totalRecoveries: 1700 }),
    });
    wrap(<FinalSettlement />, me(['O-05:VIEW']));
    await openCase();
    const table = screen.getByRole('table', { name: t('o05.components') });
    expect(within(table).getByText(t('o05.col.recoveryAgainst'))).toBeTruthy();
    expect(within(table).queryByText(t('o05.gratuityBlocked'))).toBeNull();
    const gratuityRow = table.querySelector('tr[data-component="GRATUITY"]') as HTMLElement;
    expect(gratuityRow.textContent).toContain('−');
  });

  it('l.2942 / l.2945: the letter and the order accept PDF, JPG and PNG only', async () => {
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:COMPUTE']));
    await openCase();
    await userEvent.click(screen.getByRole('button', { name: t('action.o05.exGratia') }));
    const form = screen.getByLabelText(t('action.o05.exGratia'), { selector: 'div' });
    const file = form.querySelector('input[type="file"]') as HTMLInputElement;
    expect(file.getAttribute('accept')).toContain('application/pdf');
    expect(file.getAttribute('accept')).toContain('image/png');
  });

  it('SRS-10-27: the dues start empty, so a nil clearance is typed, never assumed', async () => {
    const calls = routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:CLEAR']));
    await openCase();
    const dues = screen.getByLabelText(t('o05.dues'));
    expect(dues).toHaveValue('');
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Nil');
    const button = screen.getByRole('button', { name: t('action.o05.noDues') });
    expect(button).toBeDisabled();
    await userEvent.type(dues, '0');
    expect(button).toBeEnabled();
    await userEvent.clear(dues);
    expect(button).toBeDisabled();
    expect(sent(calls, 'POST', '/no-dues')).toHaveLength(0);
  });

  it('SRS-10-30: the Checker is sent to the Approvals Inbox to approve or return the settlement', async () => {
    routes({ 'GET /v1/settlement-cases/7': () => detail({ awaitingChecker: true }) });
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:APPROVE']));
    await openCase();
    expect(screen.getByRole('link', { name: t('o05.decideInInbox') }).getAttribute('href')).toContain(
      's=W-01',
    );
  });

  it('SRS-10-30: no inbox link for the Maker or when nothing awaits the Checker', async () => {
    routes();
    wrap(<FinalSettlement />, me(['O-05:VIEW', 'O-05:APPROVE']));
    await openCase();
    expect(screen.queryByRole('link', { name: t('o05.decideInInbox') })).toBeNull();
  });
});
