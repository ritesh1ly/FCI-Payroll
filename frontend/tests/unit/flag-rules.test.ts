import { describe, expect, it } from 'vitest';
import { endDateValid, proposable, proposalComplete } from '@/modules/employee/business/flagRules';

/** M-01 allowance flag screen rules (plan 16 phase 16d-2; SRS-1-11, BR-EMPM-005 / 006). */
describe('M-01 allowance flag rules', () => {
  const full = {
    flagCode: 'NPA',
    value: 'Y',
    effectiveFrom: '2026-10-01',
    reason: 'Posted',
    joiningDate: '2010-06-01',
  };

  it('BR-EMPM-005 / 006: a proposal names the flag, Y or N, a date from joining and a reason', () => {
    expect(proposalComplete(full)).toBe(true);
    expect(proposalComplete({ ...full, value: 'N' })).toBe(true);
    expect(proposalComplete({ ...full, joiningDate: null })).toBe(true);
    expect(proposalComplete({ ...full, effectiveFrom: '2010-06-01' })).toBe(true);
    expect(proposalComplete({ ...full, effectiveFrom: '2010-05-31' })).toBe(false);
    expect(proposalComplete({ ...full, flagCode: '' })).toBe(false);
    expect(proposalComplete({ ...full, value: 'X' })).toBe(false);
    expect(proposalComplete({ ...full, effectiveFrom: '' })).toBe(false);
    expect(proposalComplete({ ...full, effectiveFrom: '', joiningDate: null })).toBe(false);
    expect(proposalComplete({ ...full, reason: '  ' })).toBe(false);
    expect(proposalComplete({ ...full, reason: 'r'.repeat(500) })).toBe(true);
    expect(proposalComplete({ ...full, reason: ` ${'r'.repeat(500)} ` })).toBe(true);
    expect(proposalComplete({ ...full, reason: 'r'.repeat(501) })).toBe(false);
    expect(proposalComplete({ ...full, reason: 'कारण' })).toBe(false);
  });

  it('SRS-1-11: a flag with a change waiting for the Checker is not proposed again', () => {
    expect(proposable(false)).toBe(true);
    expect(proposable(true)).toBe(false);
  });
});

describe('M-01 allowance flag end date', () => {
  it('BR-EMPM-004 (US-GAP-39): the end date is optional and never before the effective date', () => {
    expect(endDateValid('2026-10-01', '')).toBe(true);
    expect(endDateValid('', '')).toBe(true);
    expect(endDateValid('2026-10-01', '2026-10-01')).toBe(true);
    expect(endDateValid('2026-10-01', '2027-03-31')).toBe(true);
    expect(endDateValid('2026-10-01', '2026-09-30')).toBe(false);
    expect(endDateValid('', '2026-09-30')).toBe(false);
  });
});
