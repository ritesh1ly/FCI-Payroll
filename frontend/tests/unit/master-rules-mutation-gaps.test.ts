import { describe, expect, it } from 'vitest';
import { linesProblem, type RateDraftLine } from '@/modules/configuration/business/masterRules';

/**
 * Mutation-gap tests for masterRules.ts (MST-422-LINES).
 * Equivalent mutant, not tested: L89 `l.rateValue !== null` -> true (Number.isFinite(null) is false,
 * so a null value is refused by the next check anyway).
 */
describe('masterRules mutation gaps', () => {
  const line = (rateValue: number | null): RateDraftLine =>
    ({
      payPattern: 'IDA',
      cityClass: null,
      categoryCode: null,
      basicSlabFrom: null,
      basicSlabTo: null,
      rateCode: 'HRA_X',
      rateValue,
      unit: 'PERCENT',
    }) as unknown as RateDraftLine;

  it('MST-422-LINES: a blank, NaN or infinite rate value is "value required", not "invalid"', () => {
    expect(linesProblem([line(null)])).toBe('mst.why.valueRequired');
    expect(linesProblem([line(Number.NaN)])).toBe('mst.why.valueRequired');
    expect(linesProblem([line(Number.POSITIVE_INFINITY)])).toBe('mst.why.valueRequired');
    expect(linesProblem([line(150)])).toBe('mst.why.linesInvalid');
    expect(linesProblem([line(20)])).toBeNull();
  });
});
