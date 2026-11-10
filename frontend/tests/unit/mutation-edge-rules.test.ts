import { describe, expect, it } from 'vitest';
import type { RateLine } from '@/modules/configuration/api/mastersApi';
import type { SupplementaryPayment } from '@/modules/general/api/generalApi';
import type { CaseDetail } from '@/modules/settlement/api/settlementApi';
import { linesProblem, type RateDraftLine } from '@/modules/configuration/business/masterRules';
import { supplementaryCancellable } from '@/modules/general/business/generalRules';
import { defaultFinYear } from '@/modules/jobs/business/calendarRules';
import { ABANDON_AWAITING_APPROVAL, finYears } from '@/modules/jobs/business/jobRules';
import { mayExclude } from '@/modules/settlement/business/settlementRules';

/**
 * Edge cases of single rules whose screen tests did not pin them (SRS-G-37, SRS-10-14, C-13 /
 * M-24 financial years, M-04 rate lines), each at its boundary.
 */

describe('edge cases', () => {
  it('SRS-G-37: an invoice cancelled in APPS still waits for the confirmation of the cancellation', () => {
    const p = (over: Partial<SupplementaryPayment>) =>
      ({ status: 'APPROVED', invoiceStatus: 'CANCELLED_IN_APPS', ...over }) as SupplementaryPayment;
    expect(supplementaryCancellable(p({ appsCancelStatus: 'REQUESTED' }))).toBe(false);
    expect(
      supplementaryCancellable(p({ invoiceStatus: 'CANCEL_REQUESTED', appsCancelStatus: 'REQUESTED' })),
    ).toBe(false);
  });

  it('a financial year of the 2000s keeps the leading zero of its second part', () => {
    expect(defaultFinYear(['2009-10', '2008-09'], '2008-05-01')).toBe('2008-09');
    expect(defaultFinYear(['2009-10', '2008-09'], '2009-03-31')).toBe('2008-09');
    expect(finYears('2010-05', 3)).toEqual(['2010-11', '2009-10', '2008-09']);
  });

  it('SRS-10-14: one outstanding clearance among cleared ones is enough to exclude', () => {
    const d = {
      awaitingChecker: true,
      summary: { status: 'COMPUTED' },
      clearances: [
        { type: 'X', status: 'RECEIVED' },
        { type: 'Y', status: 'PENDING' },
      ],
    } as unknown as CaseDetail;
    expect(mayExclude(d)).toBe(true);
  });

  it('SRS l.3653: one line without a value among valued ones needs its value', () => {
    const valued = { rateCode: 'HRA_X', rateValue: 10, unit: 'PERCENT' } as unknown as RateLine;
    const blank: RateDraftLine = { ...valued, rateValue: null };
    expect(linesProblem([valued, blank])).toBe('mst.why.valueRequired');
  });

  it('C-13: the pending command of an abandon awaiting the checker is the server code (JobRequestService)', () => {
    expect(ABANDON_AWAITING_APPROVAL).toBe('ABANDON_AWAITING_APPROVAL');
  });
});
