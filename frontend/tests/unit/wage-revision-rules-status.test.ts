import { describe, expect, it } from 'vitest';
import type { RevisionOffice } from '@/modules/arrear/api/wageRevisionApi';
import {
  isFixed,
  isOtpApproved,
  officeState,
  revisionTone,
} from '@/modules/arrear/business/wageRevisionRules';

/** I-25 wage revision statuses and the office progress (SRS-8-25). */
describe('I-25 wage revision status rules', () => {
  const ALL = [
    'DRAFT',
    'DEFINED',
    'SIMULATED',
    'PENDING_APPROVAL',
    'APPROVED',
    'REJECTED',
    'CANCELLED',
    'PAY_FIXED',
    'ARREARS_COMPUTED',
    'PAYING',
    'COMPLETED',
  ];

  it('SRS-8-25 steps 5 to 8: pay is fixed from pay fixation to completion', () => {
    expect(ALL.filter(isFixed)).toEqual(['PAY_FIXED', 'ARREARS_COMPUTED', 'PAYING', 'COMPLETED']);
    expect(isFixed('')).toBe(false);
  });

  it('SRS-8-25 step 4: the approval OTP stands behind a revision from its approval on', () => {
    expect(ALL.filter(isOtpApproved)).toEqual([
      'APPROVED',
      'PAY_FIXED',
      'ARREARS_COMPUTED',
      'PAYING',
      'COMPLETED',
    ]);
    expect(isOtpApproved('')).toBe(false);
  });

  it('SRS-8-25: the drawn status pill of each status', () => {
    expect(Object.fromEntries(ALL.map((s) => [s, revisionTone(s)]))).toEqual({
      DRAFT: 'p-gray',
      DEFINED: 'p-gray',
      SIMULATED: 'p-warn',
      PENDING_APPROVAL: 'p-ok',
      APPROVED: 'p-ok',
      REJECTED: 'p-bad',
      CANCELLED: 'p-bad',
      PAY_FIXED: 'p-ok',
      ARREARS_COMPUTED: 'p-ok',
      PAYING: 'p-ok',
      COMPLETED: 'p-ok',
    });
    expect(revisionTone('')).toBe('p-ok');
  });

  it('SRS-8-25 step 8: an office in exception first, then paid once every member is paid, else pending', () => {
    const office = (exception: number, paid: number, members: number) =>
      ({ exception, paid, members }) as RevisionOffice;
    expect(officeState(office(1, 10, 10))).toBe('EXCEPTIONS');
    expect(officeState(office(0, 10, 10))).toBe('PAID');
    expect(officeState(office(0, 11, 10))).toBe('PAID');
    expect(officeState(office(0, 9, 10))).toBe('PENDING');
    expect(officeState(office(0, 0, 0))).toBe('PAID');
  });
});
