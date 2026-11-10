import { describe, expect, it } from 'vitest';
import type {
  Enablement,
  ExceptionItem,
  FallbackUpload,
  OffCycleCase,
  PayrollQuery,
  SupplementaryPayment,
} from '@/modules/general/api/generalApi';
import {
  approveBlock,
  canAccept,
  canRespond,
  conditionKey,
  defaultUpload,
  orderUpload,
  enablementPill,
  isAsciiText,
  isEis,
  isYymm,
  monthLabel,
  monthName,
  monthShort,
  monthsBack,
  officeCodes,
  offCycleCancellable,
  reconUnit,
  offCycleEditable,
  offCycleSubmitBlock,
  resolveRole,
  sanctionAfterApplication,
  supplementaryCancellable,
  supplementarySubmitBlock,
  tone,
  unsettled,
  uploadEditable,
  uploadSubmitBlock,
  yymmOf,
} from '@/modules/general/business/generalRules';
import { EMPTY_ORDER, orderComplete, todayIso } from '@/modules/general/components/OrderFields';

/** Module 01 screen rules (they mirror the backend so a disabled button says why). */

describe('inputs', () => {
  it('R_ASCII: text is printable ASCII within its length; line breaks only where multi-line', () => {
    expect(isAsciiText('HQ/PAY/17', 100)).toBe(true);
    expect(isAsciiText('  ', 100)).toBe(false);
    expect(isAsciiText('x'.repeat(101), 100)).toBe(false);
    expect(isAsciiText('वेतन', 100)).toBe(false);
    expect(isAsciiText('a\nb', 100)).toBe(false);
    expect(isAsciiText('a\r\n\tb', 100, true)).toBe(true);
    expect(isAsciiText('a\u0007b', 100, true)).toBe(false);
    expect(isAsciiText('del\u007f', 100)).toBe(false);
  });

  it('R_ASCII: the length limit is inclusive; every printable character from space to tilde is taken', () => {
    expect(isAsciiText('x'.repeat(100), 100)).toBe(true);
    expect(isAsciiText('Order no. 17 ~ HQ', 100)).toBe(true);
    expect(isAsciiText('a\u001fb', 100)).toBe(false);
    expect(isAsciiText('a\tb', 100)).toBe(false);
  });

  it('staff numbers and payroll months', () => {
    expect(isEis(' 105220 ')).toBe(true);
    expect(isEis('10-5220')).toBe(false);
    expect(isEis('')).toBe(false);
    expect(isYymm('202607')).toBe(true);
    expect(isYymm('202613')).toBe(false);
    expect(isYymm('2026-07')).toBe(false);
  });

  it('a payroll month is six digits of the years 2000 to 2099, nothing before or after them', () => {
    expect(isYymm('200001')).toBe(true);
    expect(isYymm('209912')).toBe(true);
    expect(isYymm('199912')).toBe(false);
    expect(isYymm('210001')).toBe(false);
    expect(isYymm('202600')).toBe(false);
    expect(isYymm('1202607')).toBe(false);
    expect(isYymm('2026071')).toBe(false);
  });

  it.each([
    ['202601', 'JAN 2026'],
    ['202602', 'FEB 2026'],
    ['202603', 'MAR 2026'],
    ['202604', 'APR 2026'],
    ['202605', 'MAY 2026'],
    ['202606', 'JUN 2026'],
    ['202607', 'JUL 2026'],
    ['202608', 'AUG 2026'],
    ['202609', 'SEP 2026'],
    ['202610', 'OCT 2026'],
    ['202611', 'NOV 2026'],
    ['202612', 'DEC 2026'],
  ])('the payroll month %s is shown as %s', (yymm, name) => {
    expect(monthName(yymm)).toBe(name);
  });

  it('a missing or malformed payroll month is shown as a dash', () => {
    expect(monthName(null)).toBe('—');
    expect(monthName(undefined)).toBe('—');
    expect(monthName('')).toBe('—');
    expect(monthName('JUN 2026')).toBe('—');
    expect(monthName('202613')).toBe('—');
  });

  it('months are listed newest first across a year end, shown as MM-YYYY', () => {
    expect(monthsBack('202602', 4)).toEqual(['202602', '202601', '202512', '202511']);
    expect(monthLabel('202607')).toBe('07-2026');
    expect(monthLabel(null)).toBe('—');
    expect(monthLabel('bad')).toBe('—');
  });

  it('the business month and day are those of India', () => {
    // 2026-10-31 20:00 UTC is already 1 Nov in India.
    const late = new Date(Date.UTC(2026, 9, 31, 20, 0));
    expect(yymmOf(late)).toBe('202611');
    expect(todayIso(late)).toBe('2026-11-01');
    // The month turns at midnight in India (18:30 UTC), also across a year end.
    expect(yymmOf(new Date('2026-10-31T18:29:59Z'))).toBe('202610');
    expect(yymmOf(new Date('2026-10-31T18:30:00Z'))).toBe('202611');
    expect(yymmOf(new Date('2026-12-31T18:30:00Z'))).toBe('202701');
  });

  it('X-07 office codes are trimmed, upper-cased and de-duplicated', () => {
    expect(officeCodes(' dh001, MR001 dh001,,')).toEqual(['DH001', 'MR001']);
    expect(officeCodes('   ')).toEqual([]);
    expect(officeCodes('')).toEqual([]);
    expect(officeCodes('dh001\tmr001\nkl001')).toEqual(['DH001', 'MR001', 'KL001']);
  });

  // Every status of the Module 01 screens (X-05, X-06, X-07, I-20, I-23): done, waiting, stopped,
  // sent back, being prepared; anything else (cancelled, withdrawn) is neutral.
  it.each([
    ['APPROVED', 'p-ok'],
    ['PASSED', 'p-ok'],
    ['PASS', 'p-ok'],
    ['ACCEPTED', 'p-ok'],
    ['CLOSED', 'p-ok'],
    ['RESOLVED', 'p-ok'],
    ['RELEASED', 'p-ok'],
    ['PENDING_CHECKER', 'p-warn'],
    ['PENDING_APPROVAL', 'p-warn'],
    ['SUBMITTED', 'p-warn'],
    ['IN_PROGRESS', 'p-warn'],
    ['OPEN', 'p-warn'],
    ['UPLOADED', 'p-warn'],
    ['BLOCKED', 'p-bad'],
    ['REJECTED', 'p-bad'],
    ['ROUTED_UPSTREAM', 'p-bad'],
    ['RETURNED', 'p-pur'],
    ['DRAFT', 'p-info'],
    ['VALIDATED', 'p-info'],
    ['CANCELLED', 'p-gray'],
    ['WITHDRAWN', 'p-gray'],
  ])('a %s item has the %s pill', (status, pill) => {
    expect(tone(status)).toBe(pill);
  });
});

describe('SRS_DM_13 order particulars', () => {
  const types = [
    { code: 'SANCTION_ORDER', name: 'Sanction order', referenceRequired: true },
    { code: 'OTHER', name: 'Other', referenceRequired: false },
  ];
  const file = new File(['%PDF'], 'o.pdf', { type: 'application/pdf' });
  const base = {
    ...EMPTY_ORDER,
    documentType: 'OTHER',
    orderDate: '2026-10-01',
    issuingAuthority: 'GM',
    file,
  };

  it('needs a type, a past order date, the authority, the file and the reference where required', () => {
    expect(orderComplete(base, types, '2026-10-21')).toBe(true);
    expect(orderComplete({ ...base, file: null }, types, '2026-10-21')).toBe(false);
    expect(orderComplete({ ...base, orderDate: '2026-10-22' }, types, '2026-10-21')).toBe(false);
    expect(orderComplete({ ...base, issuingAuthority: '' }, types, '2026-10-21')).toBe(false);
    expect(orderComplete({ ...base, documentType: 'SANCTION_ORDER' }, types, '2026-10-21')).toBe(false);
    expect(
      orderComplete({ ...base, documentType: 'SANCTION_ORDER', reference: 'RO/1' }, types, '2026-10-21'),
    ).toBe(true);
    expect(orderComplete({ ...base, reference: 'आदेश' }, types, '2026-10-21')).toBe(false);
    expect(orderComplete({ ...base, documentType: 'UNKNOWN' }, types, '2026-10-21')).toBe(false);
  });
});

describe('SRS_G_11 X-06 lifecycle', () => {
  const q = (status: PayrollQuery['status'], computed = false) =>
    ({ status, concernsComputedAmount: computed }) as PayrollQuery;

  it('the Maker answers an open query; a computed amount is confirmed by the Checker', () => {
    expect(canRespond(q('OPEN'))).toBe(true);
    expect(canRespond(q('IN_PROGRESS'))).toBe(false);
    expect(resolveRole(q('OPEN'))).toBeNull();
    expect(resolveRole(q('IN_PROGRESS'))).toBe('MAKER');
    expect(resolveRole(q('IN_PROGRESS', true))).toBe('CHECKER');
    expect(resolveRole(q('CLOSED', true))).toBeNull();
  });
});

describe('SRS_G_9 X-05 acceptance', () => {
  const e = (over: Partial<ExceptionItem>) =>
    ({
      status: 'OPEN',
      blocksGate: 'G2',
      severity: 'WARNING',
      resolvableInPayroll: true,
      ...over,
    }) as ExceptionItem;

  it('only an unsettled, non-blocking, gate-G2 item resolvable in Payroll is accepted', () => {
    expect(canAccept(e({}))).toBe(true);
    expect(canAccept(e({ severity: 'BLOCKING' }))).toBe(false);
    expect(canAccept(e({ blocksGate: 'G3' }))).toBe(false);
    expect(canAccept(e({ resolvableInPayroll: false }))).toBe(false);
    expect(canAccept(e({ status: 'ACCEPTED' }))).toBe(false);
    expect(unsettled(e({ status: 'IN_PROGRESS' }))).toBe(true);
    expect(unsettled(e({ status: 'RESOLVED' }))).toBe(false);
    expect(unsettled(e({ status: 'ACCEPTED' }))).toBe(false);
  });

  it('an item routed upstream stays open until the corrected value arrives, and is never accepted', () => {
    // SRS X-05 alternate flow; the server and gate G2 count it as open (ExceptionEntry.UNSETTLED).
    const upstream = e({ status: 'ROUTED_UPSTREAM', resolvableInPayroll: false });
    expect(unsettled(upstream)).toBe(true);
    expect(canAccept(upstream)).toBe(false);
  });
});

describe('SRS_G_9 X-05 worklist by condition', () => {
  it('a condition is keyed by module and code; the same code in another module is another one', () => {
    expect(conditionKey({ moduleCode: 'PAYROLL', exceptionCode: 'NO_BANK' })).toBe('PAYROLL|NO_BANK');
    expect(conditionKey({ moduleCode: 'HRMS', exceptionCode: 'NO_BANK' })).toBe('HRMS|NO_BANK');
    // A condition raised without a module still has a key of its own.
    expect(conditionKey({ moduleCode: null, exceptionCode: 'NO_BANK' })).toBe('|NO_BANK');
  });
});

describe('SRS_G_46 X-07 submission', () => {
  const u = (over: Partial<FallbackUpload>) =>
    ({
      status: 'VALIDATED',
      acceptedCount: 2,
      type: 'ATTENDANCE_LEAVE',
      orderCopies: 0,
      ...over,
    }) as FallbackUpload;

  it('a validated or returned upload with accepted records goes to the Checker; fallback (b) needs its order', () => {
    expect(uploadEditable(u({ status: 'RETURNED' }))).toBe(true);
    expect(uploadSubmitBlock(u({}))).toBeNull();
    expect(uploadSubmitBlock(u({ status: 'PENDING_APPROVAL' }))).toBe('x07.block.status');
    expect(uploadSubmitBlock(u({ acceptedCount: 0 }))).toBe('x07.block.nothingAccepted');
    expect(uploadSubmitBlock(u({ type: 'SUSPENSION_ABSCONDING' }))).toBe('x07.block.order');
    expect(uploadSubmitBlock(u({ type: 'SUSPENSION_ABSCONDING', orderCopies: 1 }))).toBeNull();
  });

  it('OD_121: the card opens on the upload awaiting the Checker, else the newest in force, else the newest', () => {
    const cancelled = u({ id: 1, status: 'CANCELLED' });
    const superseded = u({ id: 2, status: 'SUPERSEDED' });
    const approved = u({ id: 3, status: 'APPROVED' });
    const pending = u({ id: 4, status: 'PENDING_APPROVAL' });
    expect(defaultUpload([])).toBeNull();
    expect(defaultUpload([cancelled, superseded])).toBe(cancelled);
    expect(defaultUpload([cancelled, superseded, approved])).toBe(approved);
    expect(defaultUpload([cancelled, approved, pending])).toBe(pending);
  });

  it('SRS_G_30: the order card reads the upload opened, else a live suspension upload of the page', () => {
    const attendance = u({ id: 1, type: 'ATTENDANCE_LEAVE', status: 'VALIDATED' });
    const cancelled = u({ id: 2, type: 'SUSPENSION_ABSCONDING', status: 'CANCELLED' });
    const suspension = u({ id: 3, type: 'SUSPENSION_ABSCONDING', status: 'VALIDATED' });
    const rows = [attendance, cancelled, suspension];
    expect(orderUpload(attendance, false, rows)).toBe(suspension);
    expect(orderUpload(attendance, true, rows)).toBe(attendance);
    expect(orderUpload(cancelled, false, rows)).toBe(cancelled);
    expect(orderUpload(attendance, false, [attendance, cancelled])).toBe(attendance);
    expect(orderUpload(null, false, [])).toBeNull();
  });

  it('SRS_G_21: only a pending upload with its approval item can be decided, never by its Maker', () => {
    const pending = u({ status: 'PENDING_APPROVAL', approvalId: 7, uploadedBy: 'maker' });
    expect(approveBlock(null, 'checker')).toBe('x07.w.why.nonePending');
    expect(approveBlock(u({ approvalId: 7, uploadedBy: 'maker' }), 'checker')).toBe('x07.w.why.nonePending');
    expect(approveBlock({ ...pending, approvalId: null }, 'checker')).toBe('x07.w.why.nonePending');
    expect(approveBlock(pending, 'maker')).toBe('x07.w.why.ownUpload');
    expect(approveBlock(pending, 'checker')).toBeNull();
    expect(approveBlock(pending, undefined)).toBeNull();
  });

  it('OD_121: an enablement reads as drawn, whatever its status', () => {
    const e = (over: Partial<Enablement>) =>
      ({ status: 'OPEN', type: 'ATTENDANCE_LEAVE', hrmsRestoredAt: null, ...over }) as Enablement;
    expect(enablementPill(e({}))).toEqual({ key: 'x07.w.enablement.OPEN', pill: 'p-warn', restored: false });
    expect(enablementPill(e({ status: 'UPLOADED' })).key).toBe('x07.w.enablement.OPEN');
    expect(enablementPill(e({ type: 'SUSPENSION_ABSCONDING' }))).toEqual({
      key: 'x07.w.enablement.INTERIM',
      pill: 'p-info',
      restored: false,
    });
    expect(enablementPill(e({ status: 'UPLOADED', type: 'SUSPENSION_ABSCONDING' })).pill).toBe('p-info');
    expect(enablementPill(e({ status: 'APPROVED' }))).toEqual({
      key: 'x07.w.enablement.APPROVED',
      pill: 'p-ok',
      restored: false,
    });
    expect(enablementPill(e({ status: 'RECONCILED' })).restored).toBe(false);
    expect(enablementPill(e({ status: 'RECONCILED', hrmsRestoredAt: '2026-07-02T05:00:00Z' }))).toEqual({
      key: 'x07.w.enablement.RECONCILED',
      pill: 'p-ok',
      restored: true,
    });
    expect(enablementPill(e({ status: 'WITHDRAWN' }))).toEqual({
      key: 'x07.enablement.WITHDRAWN',
      pill: 'p-gray',
      restored: false,
    });
    expect(enablementPill(e({ status: 'CLOSED' })).key).toBe('x07.enablement.CLOSED');
  });

  it('OD_121: a month alone is the month of its name; no month is a dash', () => {
    expect(monthShort('202607')).toBe('JUL');
    expect(monthShort(null)).toBe('—');
  });

  it('SRS_G_47: a difference is told in days, half-days or as the event', () => {
    expect(reconUnit('HALF_PAY_DAYS')).toBe('half');
    expect(reconUnit('LWP_DAYS')).toBe('day');
    expect(reconUnit('PAY_DAYS')).toBe('day');
    expect(reconUnit('OTHER_LEAVE_DAYS')).toBe('day');
    expect(reconUnit('REVOCATION')).toBe('event');
    expect(reconUnit('ALL')).toBe('event');
  });
});

describe('SRS_G_10 I-20 case', () => {
  const c = (over: Partial<OffCycleCase>) =>
    ({ status: 'DRAFT', orders: 1, invoiceStatus: 'NOT_RAISED', ...over }) as OffCycleCase;

  it('submission needs the order and a passing duplicate check; no role overrides a block', () => {
    expect(offCycleSubmitBlock(c({}))).toBeNull();
    expect(offCycleSubmitBlock(c({ orders: 0 }))).toBe('i20.block.order');
    expect(offCycleSubmitBlock(c({ status: 'BLOCKED' }))).toBe('i20.block.duplicate');
    expect(offCycleSubmitBlock(c({ status: 'APPROVED' }))).toBe('i20.block.status');
    expect(offCycleEditable(c({ status: 'RETURNED' }))).toBe(true);
  });

  it('SRS_G_37: cancel is free until invoiced, never while the Checker decides', () => {
    expect(offCycleCancellable(c({}))).toBe(true);
    expect(offCycleCancellable(c({ status: 'PENDING_CHECKER' }))).toBe(false);
    expect(offCycleCancellable(c({ status: 'CANCELLED' }))).toBe(false);
    expect(offCycleCancellable(c({ invoiceStatus: 'SENT_TO_APPS' }))).toBe(false);
  });

  it('SRS_G_37: once APPS confirms the cancellation (CANCELLED_IN_APPS) the case may be cancelled', () => {
    expect(offCycleCancellable(c({ status: 'APPROVED', invoiceStatus: 'CANCELLED_IN_APPS' }))).toBe(true);
    expect(offCycleCancellable(c({ status: 'APPROVED', invoiceStatus: 'CANCEL_REQUESTED' }))).toBe(false);
    expect(offCycleCancellable(c({ status: 'APPROVED', invoiceStatus: 'RAISED' }))).toBe(true);
    expect(offCycleCancellable(c({ status: 'CANCELLED', invoiceStatus: 'CANCELLED_IN_APPS' }))).toBe(false);
    expect(offCycleCancellable(c({ status: 'PENDING_CHECKER', invoiceStatus: 'CANCELLED_IN_APPS' }))).toBe(
      false,
    );
  });
});

describe('SRS_G_33 I-23 request', () => {
  const p = (over: Partial<SupplementaryPayment>) =>
    ({ status: 'DRAFT', invoiceStatus: 'NOT_RAISED', ...over }) as SupplementaryPayment;

  it('a blocked or decided request is not submitted', () => {
    expect(supplementarySubmitBlock(p({}))).toBeNull();
    expect(supplementarySubmitBlock(p({ status: 'RETURNED' }))).toBeNull();
    expect(supplementarySubmitBlock(p({ status: 'BLOCKED' }))).toBe('i23.block.duplicate');
    expect(supplementarySubmitBlock(p({ status: 'APPROVED' }))).toBe('i23.block.status');
  });

  it('SRS_G_37: cancel until sent to APPS, never while pending or once released', () => {
    expect(supplementaryCancellable(p({}))).toBe(true);
    expect(supplementaryCancellable(p({ status: 'APPROVED', invoiceStatus: 'RAISED' }))).toBe(true);
    expect(supplementaryCancellable(p({ status: 'PENDING_CHECKER' }))).toBe(false);
    expect(supplementaryCancellable(p({ status: 'RELEASED' }))).toBe(false);
    expect(supplementaryCancellable(p({ status: 'CANCELLED' }))).toBe(false);
    expect(supplementaryCancellable(p({ invoiceStatus: 'SENT_TO_APPS' }))).toBe(false);
  });

  it('SRS_G_37: a sent invoice is cancellable only once APPS confirms the cancellation', () => {
    expect(
      supplementaryCancellable(
        p({ status: 'APPROVED', invoiceStatus: 'SENT_TO_APPS', appsCancelStatus: 'CONFIRMED' }),
      ),
    ).toBe(true);
    expect(
      supplementaryCancellable(
        p({ status: 'APPROVED', invoiceStatus: 'CANCELLED_IN_APPS', appsCancelStatus: 'CONFIRMED' }),
      ),
    ).toBe(true);
    expect(
      supplementaryCancellable(
        p({ status: 'APPROVED', invoiceStatus: 'CANCEL_REQUESTED', appsCancelStatus: 'REQUESTED' }),
      ),
    ).toBe(false);
    expect(
      supplementaryCancellable(
        p({ status: 'RELEASED', invoiceStatus: 'SENT_TO_APPS', appsCancelStatus: 'CONFIRMED' }),
      ),
    ).toBe(false);
  });

  it('chk_supp_sanction_after_appl: a sanction, where given, follows the application', () => {
    expect(sanctionAfterApplication('2026-08-10', '')).toBe(true);
    expect(sanctionAfterApplication('2026-08-10', '2026-08-11')).toBe(true);
    expect(sanctionAfterApplication('2026-08-10', '2026-08-10')).toBe(false);
    expect(sanctionAfterApplication('', '2026-08-10')).toBe(false);
  });
});
