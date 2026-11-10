import { describe, expect, it } from 'vitest';
import type { MemberEntry, NewGang, Slab } from '@/modules/labour/api/labourApi';
import {
  addSlab,
  emptyEntry,
  entryBlocked,
  gangBlocked,
  monthEnd,
  nextSlip,
  orderBlocked,
  slipDates,
  transferBlocked,
  reasonOk,
  removeSlab,
  strengthBlocked,
  toRequest,
} from '@/modules/labour/business/labourRules';

/** M-02 / M-09 screen rules (plan 20 phase 20a; SRS-2-1, SRS-2-30, SRS-2-48; BR-LABM-001). */

const TODAY = '2026-10-28';

function entry(over: Partial<MemberEntry> = {}): MemberEntry {
  return {
    ...emptyEntry(),
    firstName: 'Ram',
    lastName: 'Kumar',
    birthDate: '1990-05-10',
    joiningDate: '2010-07-01',
    designationId: 3,
    depotLocationId: 7,
    ifsc: 'SBIN0001234',
    bankAccount: '12345678',
    reason: 'Appointment order',
    ...over,
  };
}

function gang(over: Partial<NewGang> = {}): NewGang {
  return {
    depotLocationId: 7,
    payrollType: 'DEPT_LABOUR',
    gangName: 'Okhla Gang 2',
    sanctionedStrength: 6,
    effectiveFrom: TODAY,
    members: [
      { employeeId: 1, role: 'SARDAR' },
      { employeeId: 2, role: 'MANDAL' },
    ],
    reason: 'New gang',
    ...over,
  };
}

describe('M-02 entry', () => {
  it('SRS-2-30: a complete entry may be sent', () => {
    expect(entryBlocked(entry(), TODAY)).toBeNull();
    expect(
      entryBlocked(entry({ lastName: '', panNumber: 'abcpk1234d', mobile: '9876543210' }), TODAY),
    ).toBeNull();
    expect(entryBlocked(entry({ pfApplicable: true, cpfCode: 'dl/123' }), TODAY)).toBeNull();
  });

  it('SRS-2-30: each missing or malformed field is named', () => {
    expect(entryBlocked(entry({ firstName: '' }), TODAY)).toBe('m02.why.name');
    expect(entryBlocked(entry({ lastName: 'K#' }), TODAY)).toBe('m02.why.name');
    expect(entryBlocked(entry({ joiningDate: '' }), TODAY)).toBe('m02.why.joining');
    expect(entryBlocked(entry({ joiningDate: '2026-10-29' }), TODAY)).toBe('m02.why.joining');
    expect(entryBlocked(entry({ birthDate: '' }), TODAY)).toBe('m02.why.birth');
    expect(entryBlocked(entry({ birthDate: '2010-07-02' }), TODAY)).toBe('m02.why.birth');
    expect(entryBlocked(entry({ designationId: null }), TODAY)).toBe('m02.why.designation');
    expect(entryBlocked(entry({ depotLocationId: null }), TODAY)).toBe('m02.why.depot');
    expect(entryBlocked(entry({ panNumber: 'ABC123' }), TODAY)).toBe('m02.why.pan');
    expect(entryBlocked(entry({ ifsc: 'SBIN1001234' }), TODAY)).toBe('m02.why.ifsc');
    expect(entryBlocked(entry({ bankAccount: '12345' }), TODAY)).toBe('m02.why.account');
    expect(entryBlocked(entry({ mobile: '5876543210' }), TODAY)).toBe('m02.why.mobile');
    expect(entryBlocked(entry({ pfApplicable: true, cpfCode: '' }), TODAY)).toBe('m02.why.cpf');
    expect(entryBlocked(entry({ reason: ' ' }), TODAY)).toBe('m02.why.reason');
  });

  it('SRS-2-30: the request upper-cases identifiers and sends blanks as null', () => {
    const body = toRequest(
      entry({ panNumber: ' abcpk1234d ', ifsc: 'sbin0001234', lastName: ' ', cpfCode: 'x' }),
    );
    expect(body).toMatchObject({
      panNumber: 'ABCPK1234D',
      ifsc: 'SBIN0001234',
      lastName: null,
      cpfCode: null,
      gender: null,
    });
    expect(toRequest(entry({ pfApplicable: true, cpfCode: ' dl/1 ' })).cpfCode).toBe('DL/1');
  });

  it('a reason is printable and at most 500 characters', () => {
    expect(reasonOk('Order 12')).toBe(true);
    expect(reasonOk('')).toBe(false);
    expect(reasonOk('x'.repeat(501))).toBe(false);
    expect(reasonOk('अ')).toBe(false);
  });
});

describe('M-09 gang', () => {
  it('SRS-2-1 / BR-LABM-001: a gang has exactly one Sardar and one Mandal', () => {
    expect(gangBlocked(gang())).toBeNull();
    // BR-LABM-011 / M-09 l.4498: a handling gang needs its Sanctioned Strength.
    expect(gangBlocked(gang({ sanctionedStrength: null }))).toBe('m09.why.strength');
    expect(gangBlocked(gang({ sanctionedStrength: 0 }))).toBe('m09.why.strength');
    expect(gangBlocked(gang({ sanctionedStrength: 1 }))).toBeNull();
    expect(gangBlocked(gang({ sanctionedStrength: 9999 }))).toBeNull();
    expect(gangBlocked(gang({ sanctionedStrength: 10000 }))).toBe('m09.why.strength');
    expect(gangBlocked(gang({ members: [{ employeeId: 1, role: 'SARDAR' }] }))).toBe('m09.why.roles');
    expect(
      gangBlocked(
        gang({
          members: [
            { employeeId: 1, role: 'SARDAR' },
            { employeeId: 2, role: 'SARDAR' },
            { employeeId: 3, role: 'MANDAL' },
          ],
        }),
      ),
    ).toBe('m09.why.roles');
    expect(
      gangBlocked(
        gang({
          members: [
            { employeeId: 1, role: 'SARDAR' },
            { employeeId: 2, role: 'MANDAL' },
            { employeeId: 2, role: 'HANDLING' },
          ],
        }),
      ),
    ).toBe('m09.why.twice');
  });

  it('SRS-2-1: the name, strength and reason are checked', () => {
    expect(gangBlocked(gang({ gangName: ' ' }))).toBe('m09.why.name');
    expect(gangBlocked(gang({ gangName: 'g'.repeat(151) }))).toBe('m09.why.name');
    expect(gangBlocked(gang({ sanctionedStrength: 0 }))).toBe('m09.why.strength');
    expect(gangBlocked(gang({ sanctionedStrength: 10000 }))).toBe('m09.why.strength');
    expect(gangBlocked(gang({ reason: '' }))).toBe('m09.why.reason');
  });

  it('SRS-2-48: a new Sanctioned Strength from today or later', () => {
    expect(strengthBlocked(8, TODAY, 'Second shift', TODAY)).toBeNull();
    expect(strengthBlocked(0, TODAY, 'x', TODAY)).toBe('m09.why.strength');
    expect(strengthBlocked(1.5, TODAY, 'x', TODAY)).toBe('m09.why.strength');
    expect(strengthBlocked(8, '2026-10-27', 'x', TODAY)).toBe('m09.why.effective');
    expect(strengthBlocked(8, '', 'x', TODAY)).toBe('m09.why.effective');
    expect(strengthBlocked(8, TODAY, '', TODAY)).toBe('m09.why.reason');
  });
});

describe('group g5 rules', () => {
  const order = { orderReference: 'TO/7', orderDate: '2026-10-28', issuingAuthority: 'Area Manager' };

  // SRS l.4429 / l.4937 / l.4971: the order a transfer or separation is started with.
  it('SRS-2-6: a transfer or separation needs its order, dated not after today', () => {
    expect(orderBlocked(order, TODAY)).toBeNull();
    expect(orderBlocked({ ...order, orderReference: ' ' }, TODAY)).toBe('m02.why.order');
    expect(orderBlocked({ ...order, orderReference: 'R'.repeat(101) }, TODAY)).toBe('m02.why.order');
    expect(orderBlocked({ ...order, orderReference: 'R'.repeat(100) }, TODAY)).toBeNull();
    expect(orderBlocked({ ...order, issuingAuthority: '' }, TODAY)).toBe('m02.why.order');
    expect(orderBlocked({ ...order, issuingAuthority: 'A'.repeat(151) }, TODAY)).toBe('m02.why.order');
    expect(orderBlocked({ ...order, issuingAuthority: 'A'.repeat(150) }, TODAY)).toBeNull();
    expect(orderBlocked({ ...order, orderDate: '' }, TODAY)).toBe('m02.why.order');
    expect(orderBlocked({ ...order, orderDate: '2026-10-29' }, TODAY)).toBe('m02.why.orderDate');
    const move = { ...order, toGangId: 6, transferDate: '2026-11-02', reason: 'Order 7' };
    expect(transferBlocked(move, TODAY)).toBeNull();
    expect(transferBlocked({ ...move, toGangId: null }, TODAY)).toBe('m09.why.move');
    expect(transferBlocked({ ...move, transferDate: '' }, TODAY)).toBe('m09.why.move');
    expect(transferBlocked({ ...move, reason: ' ' }, TODAY)).toBe('m09.why.move');
    expect(transferBlocked({ ...move, orderReference: '' }, TODAY)).toBe('m02.why.order');
  });

  // SRS-2-15 / SRS-2-17: Save and Next moves to the next open date of the month.
  it('SRS-2-17: Save and Next skips a day that holds a live slip and stops at today or the month end', () => {
    const saved = { date: '2026-09-02', continueWork: false, rows: [], participants: [] };
    expect(nextSlip(saved, [], ['2026-09-03', '2026-09-04']).date).toBe('2026-09-05');
    expect(nextSlip(saved, [], [], '2026-09-02').date).toBe('');
    expect(nextSlip(saved, [], [], '2026-09-03').date).toBe('2026-09-03');
    expect(nextSlip({ ...saved, date: '2026-09-29' }, [], ['2026-09-30']).date).toBe('');
    expect(
      slipDates([
        { date: '2026-09-01', status: 'DRAFT' },
        { date: '2026-09-02', status: 'AMENDED' },
        { date: '2026-09-03', status: 'CANCELLED' },
        { date: '2026-09-04', status: 'APPROVED' },
      ]),
    ).toEqual(['2026-09-01', '2026-09-04']);
    expect(monthEnd('2026-09')).toBe('2026-09-30');
    expect(monthEnd('2028-02')).toBe('2028-02-29');
    expect(monthEnd('2026-12')).toBe('2026-12-31');
  });
});

describe('M-06 slab bands (l.4276, US-20-22 AC3)', () => {
  const band = (table: Slab['table'], order: number, from: number, to: number | null, rate = 5): Slab => ({
    table,
    order,
    from,
    to,
    ratePercent: rate,
  });

  it('M-06: an empty table gets its first open band from bag 1 without a rate', () => {
    const other = band('BAND_2', 1, 1, null);
    expect(addSlab([other], 'BAND_1')).toEqual([
      other,
      { table: 'BAND_1', order: 1, from: 1, to: null, ratePercent: -1 },
    ]);
    expect(addSlab([], 'BAND_2')).toEqual([
      { table: 'BAND_2', order: 1, from: 1, to: null, ratePercent: -1 },
    ]);
  });

  it('M-06: a band added after an open last band closes it at its own start and follows it', () => {
    const first = band('BAND_1', 1, 1, 10);
    const open = band('BAND_1', 2, 11, null, 7);
    const other = band('BAND_2', 1, 1, null, 9);
    // Listed out of order: the last band is the one of the highest order, not the last listed.
    const next = addSlab([open, other, first], 'BAND_1');
    expect(next).toEqual([
      { ...open, to: 11 },
      other,
      first,
      { table: 'BAND_1', order: 3, from: 12, to: null, ratePercent: -1 },
    ]);
    // The other table's open band is untouched.
    expect(next[1]).toBe(other);
    expect(next[2]).toBe(first);
  });

  it('M-06: a band added after a closed last band starts the bag after it and changes nothing else', () => {
    const first = band('BAND_1', 1, 1, 10);
    const second = band('BAND_1', 2, 11, 20);
    const next = addSlab([first, second], 'BAND_1');
    expect(next.slice(0, 2)).toEqual([first, second]);
    expect(next[0]).toBe(first);
    expect(next[1]).toBe(second);
    expect(next[2]).toEqual({ table: 'BAND_1', order: 3, from: 21, to: null, ratePercent: -1 });
    expect(next).toHaveLength(3);
  });

  it('M-06: with three bands the band added and the band removed are the third, never the second', () => {
    const one = band('BAND_1', 1, 1, 10);
    const two = band('BAND_1', 2, 11, 20);
    const three = band('BAND_1', 3, 21, null);
    expect(addSlab([three, one, two], 'BAND_1')).toEqual([
      { ...three, to: 21 },
      one,
      two,
      { table: 'BAND_1', order: 4, from: 22, to: null, ratePercent: -1 },
    ]);
    expect(removeSlab([three, one, two], 'BAND_1')).toEqual([one, two]);
  });

  it('M-06: the last band is removed; a table keeps at least one band', () => {
    const first = band('BAND_1', 1, 1, 10);
    const second = band('BAND_1', 2, 11, null);
    const other = band('BAND_2', 1, 1, null);
    expect(removeSlab([second, first, other], 'BAND_1')).toEqual([first, other]);
    expect(removeSlab([first, other], 'BAND_1')).toBeNull();
    expect(removeSlab([other], 'BAND_1')).toBeNull();
    expect(removeSlab([first, second, other, band('BAND_2', 2, 2, null)], 'BAND_2')).toEqual([
      first,
      second,
      other,
    ]);
  });
});
