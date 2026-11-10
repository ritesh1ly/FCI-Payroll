import { describe, expect, it } from 'vitest';
import { controlProposalComplete } from '@/modules/employee/business/flagRules';

/** SRS-1-7 / FRS §7.8.1 Scenario 4: a Hold or Stop-Payment flag dated today or earlier (CLR-17). */
describe('M-01 control flags', () => {
  const full = {
    flagCode: 'HOLD',
    value: 'Y',
    effectiveFrom: '2026-10-28',
    reason: 'Disciplinary hold',
    joiningDate: '2010-06-01',
    today: '2026-10-28',
  };

  it('SRS-1-7: a complete proposal dated today or earlier may be sent', () => {
    expect(controlProposalComplete(full)).toBe(true);
    expect(controlProposalComplete({ ...full, effectiveFrom: '2026-10-27' })).toBe(true);
  });

  it('SRS-1-7: a later date waits for FCI (CLR-17)', () => {
    expect(controlProposalComplete({ ...full, effectiveFrom: '2026-10-29' })).toBe(false);
  });

  it('SRS-1-7: an incomplete proposal is refused whatever its date', () => {
    expect(controlProposalComplete({ ...full, reason: ' ' })).toBe(false);
    expect(controlProposalComplete({ ...full, effectiveFrom: '2010-05-31' })).toBe(false);
  });
});
