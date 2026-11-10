import { describe, expect, it } from 'vitest';
import type { NamedPerson } from '@/modules/labour/api/labourApi';
import { nominationBlocked } from '@/modules/labour/business/labourRules';

/** BR-FNF-014 / D22-8: the nominee shares of a labour member, at most two decimals, totalling 100. */

const TODAY = '2026-11-08';

function nominee(share: number | null): NamedPerson {
  return { name: 'Sita Devi', relationship: 'SPOUSE', birthDate: null, share, nominee: true };
}

describe('BR-FNF-014 nominee share precision', () => {
  it('BR_FNF_014: a share with more than two decimals is refused however small, as the server refuses it', () => {
    // LabourRecordRules.nominationDefect: BigDecimal scale above 2 is SHARE. A floating-point tolerance used to pass
    // 0.00000001 beside 100 here (the total then read 10000), and the server refused the save.
    expect(nominationBlocked([nominee(1e-8), nominee(100)], [], 'Form 2', TODAY)).toBe('m02.why.share');
    expect(nominationBlocked([nominee(0.001), nominee(99.999)], [], 'Form 2', TODAY)).toBe('m02.why.share');
    expect(nominationBlocked([nominee(0.01), nominee(99.99)], [], 'Form 2', TODAY)).toBeNull();
  });

  it('BR_FNF_014: a third decimal is refused, two decimals are accepted', () => {
    expect(nominationBlocked([nominee(33.333), nominee(66.667)], [], 'Form 2', TODAY)).toBe('m02.why.share');
    expect(nominationBlocked([nominee(33.33), nominee(66.67)], [], 'Form 2', TODAY)).toBeNull();
    expect(nominationBlocked([nominee(50), nominee(50.01)], [], 'Form 2', TODAY)).toBe('m02.why.shareTotal');
  });
});
