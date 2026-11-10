import { describe, expect, it } from 'vitest';
import {
  REASON_MAX,
  SUBMISSION_STATUSES,
  SUBMISSION_TYPES,
  amountInWords,
  initials,
  isOpen,
  previewReady,
  requestIcon,
  requestTone,
  slipRows,
} from '@/modules/eseva/business/esevaRules';

/** eSeva screen rules (plan 14; SRS-17-8, SRS-17-18). */
describe('eSeva rules', () => {
  it('SRS-17-18: the log filters by every type and status; only the first two statuses are open', () => {
    expect(SUBMISSION_TYPES).toHaveLength(10);
    expect(SUBMISSION_TYPES[0]).toBe('REIMBURSEMENT_CLAIM');
    expect(SUBMISSION_TYPES.at(-1)).toBe('PAYROLL_QUERY');
    expect(SUBMISSION_STATUSES).toEqual(['SUBMITTED', 'ROUTED', 'ACTIONED', 'REJECTED']);
    expect(isOpen('SUBMITTED')).toBe(true);
    expect(isOpen('ROUTED')).toBe(true);
    expect(isOpen('ACTIONED')).toBe(false);
    expect(isOpen('REJECTED')).toBe(false);
  });

  it('SRS-17-8: a preview needs the member and a printable reason of at most 500 characters', () => {
    expect(REASON_MAX).toBe(500);
    expect(previewReady('104882', 'Ticket 42')).toBe(true);
    expect(previewReady(' ', 'Ticket 42')).toBe(false);
    expect(previewReady('104882', '  ')).toBe(false);
    expect(previewReady('104882', 'x'.repeat(500))).toBe(true);
    expect(previewReady('104882', 'x'.repeat(501))).toBe(false);
    expect(previewReady('104882', ' x'.repeat(250) + '  ')).toBe(true);
    expect(previewReady('104882', 'टिकट')).toBe(false);
  });
});

describe('eSeva log filters', () => {
  it('SRS-17-18: the submission types in DDL order', () => {
    expect(SUBMISSION_TYPES).toEqual([
      'REIMBURSEMENT_CLAIM',
      'LOAN_REQUEST',
      'CPF_ADVANCE_REQUEST',
      'CPF_WITHDRAWAL_REQUEST',
      'CPF_FINAL_PAYMENT_REQUEST',
      'TAX_DECLARATION',
      'REGIME_CHOICE',
      'VPF_SUBSCRIPTION',
      'HRMS_UPDATE_REQUEST',
      'PAYROLL_QUERY',
    ]);
  });
});

describe('E-01 drawn helpers', () => {
  it('the initials of the band: two at most, spaces ignored', () => {
    expect(initials('R. Sharma')).toBe('RS');
    expect(initials('  usha   rani pillai ')).toBe('UR');
    expect(initials('')).toBe('');
  });

  it('SRS-17-7: the two payslip sides pair row by row, the shorter side empty', () => {
    expect(slipRows(['a', 'b'], ['x'])).toEqual([
      ['a', 'x'],
      ['b', null],
    ]);
    expect(slipRows(['a'], ['x', 'y'])).toEqual([
      ['a', 'x'],
      [null, 'y'],
    ]);
    expect(slipRows([], [])).toEqual([]);
  });

  it('SRS-17-17: the pill and the glyph of a request', () => {
    expect(requestTone('ACTIONED')).toBe('p-ok');
    expect(requestTone('REJECTED')).toBe('p-bad');
    expect(requestTone('SUBMITTED')).toBe('p-warn');
    expect(requestTone('ROUTED')).toBe('p-warn');
    expect(requestIcon('HRMS_UPDATE_REQUEST')).toBe('🔄');
    expect(requestIcon('CPF_ADVANCE_REQUEST')).toBe('🏦');
    expect(requestIcon('VPF_SUBSCRIPTION')).toBe('🏦');
    expect(requestIcon('TAX_DECLARATION')).toBe('📝');
    expect(requestIcon('REGIME_CHOICE')).toBe('📝');
    expect(requestIcon('PAYROLL_QUERY')).toBe('📨');
  });

  it('the net pay in words, in Indian groups, with its edges', () => {
    expect(amountInWords(0)).toBe('Zero');
    expect(amountInWords(7)).toBe('Seven');
    expect(amountInWords(19)).toBe('Nineteen');
    expect(amountInWords(20)).toBe('Twenty');
    expect(amountInWords(91)).toBe('Ninety One');
    expect(amountInWords(100)).toBe('One Hundred');
    expect(amountInWords(101)).toBe('One Hundred and One');
    expect(amountInWords(91963)).toBe('Ninety One Thousand Nine Hundred and Sixty Three');
    expect(amountInWords(100000)).toBe('One Lakh');
    expect(amountInWords(180363)).toBe('One Lakh Eighty Thousand Three Hundred and Sixty Three');
    expect(amountInWords(10000000)).toBe('One Crore');
    expect(amountInWords(123456789)).toBe(
      'Twelve Crore Thirty Four Lakh Fifty Six Thousand Seven Hundred and Eighty Nine',
    );
    expect(amountInWords(1234.4)).toBe('One Thousand Two Hundred and Thirty Four and Forty Paise');
    expect(amountInWords(0.05)).toBe('Zero and Five Paise');
    expect(amountInWords(-100)).toBe('One Hundred');
  });
});
