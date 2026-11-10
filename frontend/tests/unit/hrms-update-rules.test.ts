import { describe, expect, it } from 'vitest';
import { refetchable, requestComplete } from '@/modules/employee/business/hrmsUpdateRules';

/** HRMS Update Request screen rules (plan 16 phase 16c; SRS-1-70). */
describe('HRMS Update Request rules', () => {
  it('SRS-1-70: only a request whose HRMS fetch failed is fetched again', () => {
    expect(refetchable('FETCH_FAILED')).toBe(true);
    for (const s of ['SUBMITTED', 'TASK_OPENED', 'LINKED_EXISTING', 'NOT_YET_IN_HRMS', 'CLOSED']) {
      expect(refetchable(s)).toBe(false);
    }
  });

  it('SRS-1-70: a request names the field and the date; the Maker also the employee; the reference is optional', () => {
    const full = {
      eis: '100041',
      needsEmployee: true,
      fieldCode: 'PAN',
      effectiveDate: '2026-10-01',
      hrmsReference: '',
    };
    expect(requestComplete(full)).toBe(true);
    expect(requestComplete({ ...full, eis: ' ' })).toBe(false);
    expect(requestComplete({ ...full, eis: undefined })).toBe(false);
    expect(requestComplete({ ...full, eis: '', needsEmployee: false })).toBe(true);
    expect(requestComplete({ ...full, fieldCode: '' })).toBe(false);
    expect(requestComplete({ ...full, effectiveDate: '' })).toBe(false);
    expect(requestComplete({ ...full, hrmsReference: 'R'.repeat(60) })).toBe(true);
    expect(requestComplete({ ...full, hrmsReference: ` ${'R'.repeat(60)} ` })).toBe(true);
    expect(requestComplete({ ...full, hrmsReference: 'R'.repeat(61) })).toBe(false);
    expect(requestComplete({ ...full, hrmsReference: 'संदर्भ' })).toBe(false);
  });
});
