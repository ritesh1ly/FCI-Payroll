import { describe, expect, it } from 'vitest';
import type { DefinitionRequest } from '@/modules/arrear/api/wageRevisionApi';
import {
  REVISION_ORDER_CATEGORY,
  definitionReady,
  revisionOrders,
} from '@/modules/arrear/business/wageRevisionRules';

/** I-25 wage revision, steps 1 and 2 (plan 13; SRS-8-25, SRS l.3164): boundaries of the bounds and the order. */
describe('I-25 wage revision', () => {
  const ready: Partial<DefinitionRequest> = {
    name: 'WR 2027',
    circularId: 4,
    payrollTypes: ['STAFF_IDA'],
    effectiveFrom: '2027-01-01',
    fitment: 'FACTOR',
    value: 1.1,
    rounding: 'RUPEE',
    reason: 'Order',
  };

  it('SRS-8-25 review X3: a bound that is not a number is never sent as null', () => {
    expect(definitionReady({ ...ready, minimum: Number.NaN })).toBe(false);
    expect(definitionReady({ ...ready, maximum: Number.NaN })).toBe(false);
    expect(definitionReady({ ...ready, maximum: Number.POSITIVE_INFINITY })).toBe(false);
    expect(definitionReady({ ...ready, minimum: 0, maximum: 1 })).toBe(true);
  });

  it('SRS l.3164: only an order of category Wage Revision may be named', () => {
    expect(REVISION_ORDER_CATEGORY).toBe('WAGE_REVISION');
    const orders = [
      { id: 1, category: 'WAGE_REVISION' },
      { id: 2, category: 'DA' },
      { id: 3, category: null },
      { id: 4, category: 'WAGE_REVISION' },
    ];
    expect(revisionOrders(orders).map((o) => o.id)).toEqual([1, 4]);
    expect(revisionOrders([])).toEqual([]);
  });
});
