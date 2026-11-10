import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LeaveEncashmentArrears } from '@/modules/leave';
import { leaveArrearApi } from '@/modules/leave/api/leaveArrearApi';
import {
  calculationSheet,
  cancellable,
  daPercent,
  invoiceCode,
  isPayment,
  payOf,
  runReady,
  selectable,
  selectedTds,
  selectedTotal,
  statusTone,
} from '@/modules/leave/business/leaveArrearRules';
import { date } from '@/shared/ui';
import { backend, me, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

/**
 * I-24 Leave Encashment Arrear (plan 13 phase 13d): the Maker's run against a revision order, the
 * records with their calculation sheet, the multi-record submission with a remark; each action
 * disabled with its reason.
 */

afterEach(() => vi.unstubAllGlobals());

const ROW = {
  id: 3,
  recordNumber: 'LEA-2026-00003',
  eis: '104882',
  name: 'Rakesh Sharma',
  separationDate: null,
  order: 'DEMO/DA-REV/LEA-01',
  revisionEffective: '2026-07-01',
  month: '202608',
  days: 10,
  oldBasic: 60000,
  oldDa: 0,
  revisedBasic: 62400,
  revisedDa: 0,
  paid: 20000,
  due: 20800,
  arrear: 800,
  tds: 0,
  status: 'CALCULATED',
  ineligibleReason: null,
  batch: null,
};

const ORDERS = [{ id: 9, number: 'DEMO/DA-REV/LEA-01', subject: 'DA revision', effectiveFrom: '2026-07-01' }];

const openSubmit = () => userEvent.click(screen.getByRole('button', { name: t('i24.act.submit') }));
const openReport = () => userEvent.click(screen.getByRole('button', { name: t('i24.act.report') }));

describe('I-24 Leave Encashment Arrear', () => {
  it('SRS-8-24: the rules decide what the screen offers', () => {
    expect(selectable(ROW as never)).toBe(true);
    expect(selectable({ ...ROW, status: 'APPROVED' } as never)).toBe(false);
    expect(runReady({ circularId: 9, revisionEffective: '2026-07-01', reason: 'DA order' })).toBe(true);
    expect(runReady({ circularId: 9, revisionEffective: '2026-07-01', reason: ' ' })).toBe(false);
    expect(runReady({ revisionEffective: '2026-07-01', reason: 'x' })).toBe(false);
    expect(runReady({ circularId: 9, revisionEffective: '07/2026', reason: 'x' })).toBe(false);
    expect(selectedTotal([ROW as never, { ...ROW, id: 4, arrear: null } as never], new Set([3, 4]))).toBe(
      800,
    );
    expect(leaveArrearApi.reportUrl({})).toMatch(/\/v1\/leave-encashment-arrears\/report$/);
    expect(leaveArrearApi.reportUrl({ status: 'APPROVED', eis: '104882' })).toMatch(
      /report\?status=APPROVED&eis=104882$/,
    );
  });

  it('SRS l.3138-3141 (I-24 #1-#4, #6): old and revised pay, TDS, invoice status and the calculation sheet', async () => {
    // BR-EMPR-013: the Arrear Payments (LE) entries are shown and kept in the one arrear-payment screen, I-24.
    // Old / revised pay are Basic + DA, shown only when both parts are known (never a partial sum).
    expect(payOf(60000, 0)).toBe(60000);
    expect(payOf(60000, 2400)).toBe(62400);
    expect(payOf(null, 2400)).toBeNull();
    expect(payOf(60000, null)).toBeNull();
    expect(payOf(null, null)).toBeNull();
    // The sheet: label, value rows (CRLF); a label with a comma is quoted; an unknown value is empty.
    const sheet = calculationSheet({ ...ROW, oldDa: null, order: 'A,B' } as never, (k) =>
      k === 'i24.revisionOrder' ? 'Order, revision' : k,
    );
    expect(sheet.endsWith('\r\n')).toBe(true);
    expect(sheet).toContain('i24.record,LEA-2026-00003\r\n');
    expect(sheet).toContain('"Order, revision","A,B"');
    expect(sheet).toContain('i24.oldPay,\r\n');
    expect(sheet).toContain('i24.revisedPay,62400');
    expect(sheet).toContain('i24.arrear,800');
    expect(sheet).toContain('i24.tds,0');

    const created: Blob[] = [];
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: (b: Blob) => (created.push(b), 'blob:x'),
      revokeObjectURL: () => {},
    });
    backend({
      'GET /v1/leave-encashment-arrears?': (url) =>
        paged([ROW, { ...ROW, id: 4, recordNumber: 'LEA-2026-00004', revisedDa: null, tds: 120 }], 2, url),
    });
    wrap(<LeaveEncashmentArrears />, me(['I-24:VIEW']));
    const table = await screen.findByRole('table', { name: t('screen.I-24') });
    const row = (await within(table).findByText('LEA-2026-00003')).closest('tr') as HTMLElement;
    expect(within(row).getByText(t('i24.invoiceStatus.CALCULATED'))).toBeTruthy();
    const other = within(table).getByText('LEA-2026-00004').closest('tr') as HTMLElement;
    // Revised DA unknown: the revised pay is a dash, the TDS is shown.
    expect(within(other).getAllByText('—').length).toBeGreaterThan(0);
    const headers = screen.getAllByRole('columnheader').map((h) => h.textContent ?? '');
    for (const key of ['i24.col.oldPay', 'i24.col.revisedPay', 'i24.col.tds', 'i24.col.invoiceStatus']) {
      expect(headers.some((h) => h.includes(t(key)))).toBe(true);
    }
    await openReport();
    const send = screen.getByRole('button', { name: t('i24.sendInvoices') });
    expect(send).toBeDisabled();
    expect(send.getAttribute('title')).toBe(t('i24.why.invoices'));
    await userEvent.click(screen.getByRole('button', { name: t('i24.sheetOf', ['LEA-2026-00003']) }));
    expect(created).toHaveLength(1);
    expect(await created[0]?.text()).toContain(t('i24.arrear') + ',800');
  });

  it('SRS-8-23: the Maker runs the arrear against an order and sees who could not be computed', async () => {
    const calls = backend({
      'GET /v1/masters/circulars/current': () => ORDERS,
      'GET /v1/leave-encashment-arrears?': (url) => paged([ROW], 1, url),
      'POST /v1/leave-encashment-arrears/runs': () => ({
        eventId: 5,
        members: 2,
        records: 1,
        skipped: [{ eis: '105220', reason: 'Encashment divisor is not configured on M-20.' }],
      }),
    });
    wrap(<LeaveEncashmentArrears />, me(['I-24:VIEW', 'I-24:COMPUTE', 'I-24:SUBMIT', 'I-24:EXPORT']));
    await screen.findByLabelText(t('i24.selectRecord', ['LEA-2026-00003']));
    await openSubmit();
    const run = await screen.findByRole('button', { name: t('action.i24.run') });
    expect(run).toBeDisabled();
    expect(run.getAttribute('title')).toBe(t('i24.why.run'));
    await userEvent.selectOptions(
      await screen.findByLabelText(t('i24.revisionOrder')),
      await screen.findByRole('option', { name: /DEMO\/DA-REV\/LEA-01/ }),
    );
    await userEvent.type(screen.getByLabelText(t('i24.effective')), '2026-07-01');
    const reasons = screen.getAllByLabelText(t('common.reason'));
    await userEvent.type(reasons[1] as HTMLElement, 'DA order');
    await userEvent.click(run);
    expect(await screen.findByText(t('i24.ran', [2, 1]))).toBeTruthy();
    expect(screen.getByText(/105220/)).toBeTruthy();
    expect(sent(calls, 'POST', '/runs').at(-1)).toEqual({
      circularId: 9,
      revisionEffective: '2026-07-01',
      reason: 'DA order',
    });
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    await openReport();
    expect(screen.getByRole('button', { name: /export/i })).toBeTruthy();
  });

  it('SRS-8-24: the Maker selects records and submits them together; a refusal is shown', async () => {
    let answer: unknown = problem(409, 'LEA-409-STATE', ['LEA-2026-00003', 'APPROVED']);
    const calls = backend({
      'GET /v1/masters/circulars/current': () => ORDERS,
      'GET /v1/leave-encashment-arrears?': (url) =>
        paged([ROW, { ...ROW, id: 4, recordNumber: 'LEA-2026-00004', status: 'APPROVED' }], 2, url),
      'POST /v1/leave-encashment-arrears/submissions': () => answer,
    });
    wrap(<LeaveEncashmentArrears />, me(['I-24:VIEW', 'I-24:SUBMIT']));
    const table = await screen.findByRole('table', { name: t('screen.I-24') });
    await within(table).findByText('LEA-2026-00004');
    expect(within(table).getAllByText('DEMO/DA-REV/LEA-01')).toHaveLength(2);
    expect(within(table).getAllByText(date('2026-07-01'))).toHaveLength(2);
    const approved = within(table).getByLabelText(t('i24.selectRecord', ['LEA-2026-00004']));
    expect(approved).toBeDisabled();
    expect(approved.getAttribute('title')).toBe(t('i24.why.select'));
    await openSubmit();
    const none = screen.getByRole('button', { name: new RegExp(t('action.i24.submit')) });
    expect(none.getAttribute('title')).toBe(t('i24.why.none'));
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    await userEvent.click(within(table).getByLabelText(t('i24.selectRecord', ['LEA-2026-00003'])));
    await openSubmit();
    const submit = screen.getByRole('button', { name: new RegExp(t('action.i24.submit')) });
    expect(submit.getAttribute('title')).toBe(t('i24.why.remark'));
    await userEvent.type(screen.getByLabelText(t('common.reason')), 'Arrear order');
    await userEvent.click(submit);
    expect(await screen.findByText(t('error.LEA-409-STATE', ['LEA-2026-00003', 'APPROVED']))).toBeTruthy();
    answer = { status: 202, body: { approvalId: 7, status: 'PENDING_APPROVAL' } };
    await userEvent.click(submit);
    expect(await screen.findByText(t('i24.submitted'))).toBeTruthy();
    expect(sent(calls, 'POST', '/submissions').at(-1)).toEqual({ ids: [3], remark: 'Arrear order' });
    expect(screen.queryByRole('button', { name: t('action.i24.run') })).toBeNull();
  });

  it('SRS-8-24: a filter change clears the selection, so no hidden record is submitted', async () => {
    backend({
      'GET /v1/masters/circulars/current': () => ORDERS,
      'GET /v1/leave-encashment-arrears?': (url) => paged([ROW], 1, url),
    });
    wrap(<LeaveEncashmentArrears />, me(['I-24:VIEW', 'I-24:SUBMIT']));
    const table = await screen.findByRole('table', { name: t('screen.I-24') });
    const box = await within(table).findByLabelText(t('i24.selectRecord', ['LEA-2026-00003']));
    await userEvent.click(box);
    await openReport();
    await userEvent.type(screen.getByLabelText(t('m01.eis')), '1');
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    await openSubmit();
    const submit = screen.getByRole('button', { name: new RegExp(t('action.i24.submit')) });
    expect(submit.getAttribute('title')).toBe(t('i24.why.none'));
  });

  it('SRS-G-29: a viewer selects nothing; filters go to the server; an empty list says so', async () => {
    const calls = backend({
      'GET /v1/reference/lists': () => referenceLists({ LE_ARREAR_STATUS: [['PAID', 'Paid']] }),
      'GET /v1/leave-encashment-arrears?': (url) =>
        paged(url.includes('status=PAID') ? [] : [{ ...ROW, separationDate: '2026-09-30' }], 1, url),
    });
    wrap(<LeaveEncashmentArrears />, me(['I-24:VIEW']));
    const table = await screen.findByRole('table', { name: t('screen.I-24') });
    expect(await within(table).findByText(date('2026-09-30'))).toBeTruthy();
    const box = await within(table).findByLabelText(t('i24.selectRecord', ['LEA-2026-00003']));
    expect(box).toBeDisabled();
    expect(box.getAttribute('title')).toBe(t('reason.makerOnly'));
    await openReport();
    expect(screen.queryByRole('button', { name: /export/i })).toBeNull();
    await userEvent.selectOptions(screen.getByLabelText(t('i24.status')), 'PAID');
    expect(await screen.findByText(t('i24.none'))).toBeTruthy();
    expect(calls.some((c) => c.url.includes('status=PAID'))).toBe(true);
  });
  it('I-24 variants: empty amounts, a reason not eligible, a deselect, a failed run, a short month key', async () => {
    backend({
      'GET /v1/masters/circulars/current': () => [{ id: 9, number: 'DEMO/DA', subject: null }],
      'GET /v1/leave-encashment-arrears?': (url) =>
        paged(
          [
            {
              ...ROW,
              month: '2026',
              days: null,
              revisedBasic: null,
              revisedDa: null,
              paid: null,
              due: null,
              arrear: null,
            },
            {
              ...ROW,
              id: 5,
              recordNumber: 'LEA-2026-00005',
              revisedDa: null,
              status: 'NOT_ELIGIBLE',
              ineligibleReason: 'Separated before',
            },
          ],
          2,
          url,
        ),
      'POST /v1/leave-encashment-arrears/runs': () =>
        problem(422, 'LV-422-NOT-CONFIGURED', ['Encashment divisor']),
    });
    wrap(<LeaveEncashmentArrears />, me(['I-24:VIEW', 'I-24:COMPUTE', 'I-24:SUBMIT']));
    const table = await screen.findByRole('table', { name: t('screen.I-24') });
    expect(await within(table).findByText('2026')).toBeTruthy();
    expect(within(table).getAllByText('—').length).toBeGreaterThanOrEqual(5);
    expect(within(table).getByText(t('i24.invoiceStatus.notEligibleWhy', ['Separated before']))).toBeTruthy();
    const box = await within(table).findByLabelText(t('i24.selectRecord', ['LEA-2026-00003']));
    await userEvent.click(box);
    await userEvent.click(box);
    expect(box).not.toBeChecked();
    await openReport();
    await userEvent.type(screen.getByLabelText(t('m01.eis')), '104882');
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    await openSubmit();
    await userEvent.selectOptions(
      screen.getByLabelText(t('i24.revisionOrder')),
      await screen.findByRole('option', { name: 'DEMO/DA' }),
    );
    await userEvent.selectOptions(screen.getByLabelText(t('i24.revisionOrder')), '');
    await userEvent.selectOptions(screen.getByLabelText(t('i24.revisionOrder')), 'DEMO/DA');
    await userEvent.type(screen.getByLabelText(t('i24.effective')), '2026-07-01');
    await userEvent.type(screen.getByLabelText(t('i24.runEis')), '104882');
    await userEvent.type(screen.getAllByLabelText(t('common.reason'))[1] as HTMLElement, 'DA order');
    await userEvent.click(screen.getByRole('button', { name: t('action.i24.run') }));
    expect(await screen.findByText(t('error.LV-422-NOT-CONFIGURED', ['Encashment divisor']))).toBeTruthy();
  });
});

describe('I-24 as Wireframes v6.1 draws it (OD-121)', () => {
  const READY = {
    ...ROW,
    id: 11,
    recordNumber: 'LEA-2026-0211',
    separationDate: '2026-07-31',
    days: 300,
    oldBasic: 62400,
    oldDa: 31200,
    revisedBasic: 62400,
    revisedDa: 33072,
    paid: 936000,
    due: 954720,
    arrear: 18720,
    tds: 1872,
  };
  const SENT = { ...READY, id: 12, recordNumber: 'LEA-2026-0190', status: 'INVOICED' };
  const WAITING = { ...READY, id: 13, recordNumber: 'LEA-2026-0214', status: 'SUBMITTED' };
  const INELIGIBLE = {
    ...READY,
    id: 14,
    recordNumber: 'LEA-2026-0213',
    status: 'NOT_ELIGIBLE',
    ineligibleReason: 'Separated before the revision took effect',
    oldBasic: null,
    oldDa: null,
    revisedBasic: null,
    revisedDa: null,
    paid: null,
    due: null,
    arrear: null,
  };

  it('BR-ARR-LE-004: the rules of the drawn objects', () => {
    expect(cancellable(READY as never)).toBe(true);
    expect(cancellable({ ...READY, status: 'APPROVED' } as never)).toBe(true);
    for (const status of ['SUBMITTED', 'INVOICED', 'PAID', 'CANCELLED', 'NOT_ELIGIBLE']) {
      expect(cancellable({ ...READY, status } as never)).toBe(false);
    }
    expect(isPayment(READY as never)).toBe(true);
    expect(isPayment(INELIGIBLE as never)).toBe(false);
    expect(invoiceCode(READY as never)).toBe('NOT_RAISED');
    expect(invoiceCode(SENT as never)).toBe('SENT_TO_APPS');
    expect(invoiceCode({ ...READY, status: 'PAID' } as never)).toBe('SENT_TO_APPS');
    expect(statusTone('INVOICED')).toBe('p-info');
    expect(statusTone('PAID')).toBe('p-ok');
    expect(statusTone('APPROVED')).toBe('p-ok');
    expect(statusTone('NOT_ELIGIBLE')).toBe('p-gray');
    expect(statusTone('CANCELLED')).toBe('p-gray');
    expect(statusTone('CALCULATED')).toBe('p-warn');
    expect(daPercent(62400, 31200)).toBe(50);
    expect(daPercent(62400, 33072)).toBe(53);
    expect(daPercent(null, 1)).toBeNull();
    expect(daPercent(1, null)).toBeNull();
    expect(daPercent(0, 1)).toBeNull();
    expect(selectedTds([READY, WAITING] as never)).toBe(3744);
    expect(selectedTds([])).toBe(0);
  });

  it('SRS-8-24: the tiles follow the order and the selection; Select all takes the Ready records of the page', async () => {
    backend({
      'GET /v1/leave-encashment-arrears/summary': () => ({ order: 'EP-01/2026/DA-07', records: 38 }),
      'GET /v1/leave-encashment-arrears?': (url) => paged([READY, WAITING, INELIGIBLE], 3, url),
    });
    wrap(<LeaveEncashmentArrears />, me(['I-24:VIEW', 'I-24:SUBMIT']));
    await screen.findByLabelText(t('i24.selectRecord', ['LEA-2026-0211']));
    expect(await screen.findByText('38')).toBeTruthy();
    expect(screen.getByText(t('i24.tile.recordsSub', ['EP-01/2026/DA-07']))).toBeTruthy();
    expect(screen.getByText(t('i24.tile.selectedSub'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('i24.act.selectAll') }));
    expect(screen.getByLabelText(t('i24.selectRecord', ['LEA-2026-0211']))).toBeChecked();
    expect(screen.getByLabelText(t('i24.selectRecord', ['LEA-2026-0214']))).not.toBeChecked();
    expect(screen.getByText('₹ 18,720')).toBeTruthy();
    expect(screen.getByText(t('i24.tile.arrearSub', ['1,872']))).toBeTruthy();
  });

  it('SRS-G-25: each role sees the three actions, locked with the drawn reason where it may not act', async () => {
    backend({ 'GET /v1/leave-encashment-arrears?': (url) => paged([READY], 1, url) });
    const view = wrap(<LeaveEncashmentArrears />, me(['I-24:VIEW']));
    await screen.findByLabelText(t('i24.selectRecord', ['LEA-2026-0211']));
    const submit = screen.getByRole('button', { name: t('i24.act.submit') });
    const approve = screen.getByRole('button', { name: t('i24.act.approve') });
    expect(submit).toBeDisabled();
    expect(submit.getAttribute('data-why')).toBe(t('i24.guard.submit'));
    expect(approve).toBeDisabled();
    expect(approve.getAttribute('data-why')).toBe(t('i24.guard.approve'));
    const all = screen.getByRole('button', { name: t('i24.act.selectAll') });
    expect(all.getAttribute('data-guard')).toBe(t('reason.makerOnly'));
    await userEvent.click(all);
    expect(screen.getByLabelText(t('i24.selectRecord', ['LEA-2026-0211']))).not.toBeChecked();
    view.unmount();
    wrap(<LeaveEncashmentArrears />, me(['I-24:VIEW', 'I-24:APPROVE']));
    expect((await screen.findByRole('link', { name: t('i24.act.approve') })).getAttribute('href')).toMatch(
      /s=W-01$/,
    );
  });

  it('BR-ARR-LE-002: a row shows its calculation sheet; in service, not eligible and an unknown pay read plainly', async () => {
    backend({
      'GET /v1/leave-encashment-arrears?': (url) =>
        paged(
          [READY, INELIGIBLE, { ...READY, id: 15, recordNumber: 'LEA-2026-0300', separationDate: null }],
          3,
          url,
        ),
    });
    wrap(<LeaveEncashmentArrears />, me(['I-24:VIEW']));
    expect(await screen.findByRole('heading', { name: new RegExp('LEA-2026-0211') })).toBeTruthy();
    expect(screen.getByText(t('i24.kv.oldPay', [50]))).toBeTruthy();
    expect(screen.getByText(t('i24.kv.revisedPay', [53]))).toBeTruthy();
    expect(screen.getByText(t('i24.kv.payValue', ['62,400', '31,200', '93,600']))).toBeTruthy();
    expect(screen.getByText(t('i24.kv.separationEligible', [date('2026-07-31')]))).toBeTruthy();
    expect(screen.getByText(t('i24.kv.formulaValue'))).toBeTruthy();
    const ineligible = screen.getAllByText('LEA-2026-0213')[0]?.closest('tr') as HTMLElement;
    await userEvent.click(ineligible);
    expect(await screen.findByText(t('i24.kv.oldPayPlain'))).toBeTruthy();
    expect(screen.getByText(t('i24.kv.revisedPayPlain'))).toBeTruthy();
    const serving = screen.getAllByText('LEA-2026-0300')[0]?.closest('tr') as HTMLElement;
    serving.focus();
    await userEvent.keyboard('{Enter}');
    expect(await screen.findByText(t('i24.kv.separationNone'))).toBeTruthy();
    await userEvent.keyboard('x');
    expect(screen.getByText(t('i24.kv.separationNone'))).toBeTruthy();
  });

  it('an empty list has no sheet and no selection to submit', async () => {
    backend({ 'GET /v1/leave-encashment-arrears?': (url) => paged([], 0, url) });
    wrap(<LeaveEncashmentArrears />, me(['I-24:VIEW', 'I-24:COMPUTE', 'I-24:SUBMIT']));
    expect(await screen.findByText(t('i24.none'))).toBeTruthy();
    expect(screen.getByText(t('i24.kv.noRow'))).toBeTruthy();
    expect(screen.getByText(t('wf.inv.none'))).toBeTruthy();
    await openReport();
    expect(screen.queryByText(t('i24.sheet'))).toBeNull();
  });

  it('SRS-G-37: the Maker raises a cancellation with a reason; a refusal is shown; sent and waiting records are off', async () => {
    let answer: unknown = problem(409, 'LEA-409-STATE', ['LEA-2026-0211', 'CALCULATED']);
    const calls = backend({
      'GET /v1/leave-encashment-arrears?': (url) => paged([READY, SENT, WAITING, INELIGIBLE], 4, url),
      'POST /v1/leave-encashment-arrears/11/cancellations': () => answer,
    });
    wrap(<LeaveEncashmentArrears />, me(['I-24:VIEW', 'I-24:SUBMIT']));
    const card = (await screen.findByRole('table', { name: t('i24.invoices') })) as HTMLElement;
    const rowOf = (entry: string) => card.querySelector(`[data-invoice="${entry}"]`) as HTMLElement;
    await within(card).findByText('LEA-2026-0211');
    expect(card.querySelector('[data-invoice="LEA-2026-0213"]')).toBeNull();
    const sent = within(rowOf('LEA-2026-0190')).getByRole('button', { name: t('wf.inv.cancel') });
    expect(sent).toBeDisabled();
    expect(sent.getAttribute('data-why')).toBe(t('inv.cancelBlocked'));
    expect(
      within(rowOf('LEA-2026-0214'))
        .getByRole('button', { name: t('wf.inv.cancel') })
        .getAttribute('data-why'),
    ).toBe(t('i24.why.cancelChecker'));
    const reason = async () => {
      await userEvent.click(within(rowOf('LEA-2026-0211')).getByRole('button', { name: t('wf.inv.cancel') }));
      const dialog = await screen.findByRole('dialog', { name: t('i24.cancelTitle', ['LEA-2026-0211']) });
      await userEvent.type(within(dialog).getByRole('textbox'), 'Wrong order');
      const buttons = within(dialog).getAllByRole('button');
      await userEvent.click(buttons[buttons.length - 1] as HTMLElement);
    };
    await reason();
    expect(await screen.findByText(t('error.LEA-409-STATE', ['LEA-2026-0211', 'CALCULATED']))).toBeTruthy();
    answer = { status: 202, body: { approvalId: 9, status: 'PENDING_APPROVAL' } };
    await reason();
    expect(await screen.findByText(t('i24.cancelRaised', ['LEA-2026-0211']))).toBeTruthy();
    expect(sent_(calls)).toEqual({ reason: 'Wrong order' });
  });

  it('BR-AUDIT-009: only the Maker cancels; a cancelled record reads as a new version', async () => {
    backend({
      'GET /v1/leave-encashment-arrears?': (url) =>
        paged([READY, { ...READY, id: 16, recordNumber: 'LEA-2026-0216', status: 'CANCELLED' }], 2, url),
    });
    wrap(<LeaveEncashmentArrears />, me(['I-24:VIEW']));
    const card = await screen.findByRole('table', { name: t('i24.invoices') });
    expect(await within(card).findByText(t('wf.inv.cancelledVersion'))).toBeTruthy();
    expect(
      within(card)
        .getByRole('button', { name: t('wf.inv.cancel') })
        .getAttribute('data-why'),
    ).toBe(t('wf.inv.why.maker'));
  });

  it('FRS 6.4.3: the Maker attaches the order of a Ready record; other roles and records read it only', async () => {
    const calls = backend({
      'GET /v1/leave-encashment-arrears?': (url) => paged([READY, SENT], 2, url),
      'GET /v1/document-types': () => [{ code: 'CIRCULAR', name: 'Circular', referenceRequired: false }],
      'GET /v1/leave-encashment-arrears/11/documents': () => [
        {
          id: 5,
          fileName: 'order.pdf',
          documentTypeCode: 'CIRCULAR',
          documentTypeName: 'Circular',
          referenceNumber: 'EP-01/2026/DA-07',
          uploadedBy: 'maker',
          uploadedAt: '2026-10-01T10:00:00',
        },
      ],
      'GET /v1/leave-encashment-arrears/12/documents': () => [],
    });
    wrap(<LeaveEncashmentArrears />, me(['I-24:VIEW', 'I-24:SUBMIT']));
    expect(await screen.findByText('order.pdf')).toBeTruthy();
    expect(leaveArrearApi.documentUrl(11, 5)).toMatch(/leave-encashment-arrears\/11\/documents\/5$/);
    await userEvent.click(screen.getAllByText('LEA-2026-0190')[0]?.closest('tr') as HTMLElement);
    expect(calls.some((c) => c.url.includes('/12/documents'))).toBe(true);
    const form = new File(['x'], 'o.pdf', { type: 'application/pdf' });
    const attached = await leaveArrearApi.attachDocument(11, {
      documentType: 'CIRCULAR',
      reference: ' R ',
      orderDate: '2026-10-01',
      issuingOffice: ' RO ',
      file: form,
    });
    expect(attached).toBeTruthy();
    await leaveArrearApi.attachDocument(11, {
      documentType: 'CIRCULAR',
      reference: ' ',
      orderDate: '2026-10-01',
      issuingOffice: '',
      file: form,
    });
    const bodies = calls.filter((c) => c.method === 'POST').map((c) => c.body as FormData);
    expect(bodies[0]?.get('reference')).toBe('R');
    expect(bodies[0]?.get('issuingOffice')).toBe('RO');
    expect(bodies[1]?.has('reference')).toBe(false);
    expect(bodies[1]?.has('issuingOffice')).toBe(false);
  });
});

function sent_(calls: ReturnType<typeof backend>): unknown {
  return sent(calls, 'POST', '/cancellations').at(-1);
}
