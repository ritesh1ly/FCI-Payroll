import { describe, expect, it } from 'vitest';
import { describeError, sanitize } from '@/shared/log/mask';

/**
 * Log masking edge cases (R-PII, BR-SEC-001): an error nested in a logged object, a long list
 * capped, a value JSON cannot render or that throws while read, and an error asked with its stack
 * when it has none.
 */
describe('log masking edges', () => {
  it('R_PII_anErrorInsideALoggedObjectIsDescribedNotDumped', () => {
    const out = sanitize({ cause: new TypeError('bad 9876543210') }) as Record<
      string,
      Record<string, unknown>
    >;
    expect(out.cause?.kind).toBe('TypeError');
    expect(String(out.cause?.message)).not.toContain('9876543210');
  });

  it('R_PII_aListLongerThanTenIsCappedWithItsLength', () => {
    const out = sanitize(Array.from({ length: 12 }, (_, i) => i)) as unknown[];
    expect(out).toHaveLength(11);
    expect(out[10]).toBe('…(12 items)');
  });

  it('R_PII_aValueJsonCannotRenderIsNamedByItsType', () => {
    expect(describeError(undefined)).toEqual({ kind: 'undefined', message: 'undefined' });
    const unreadable = {
      get secretish(): string {
        throw new Error('getter failed');
      },
    };
    expect(describeError(unreadable)).toEqual({ kind: 'object', message: 'object' });
  });

  it('R_PII_anErrorWithoutAStackHasNoneEvenWhenAsked', () => {
    const e = new Error('no stack');
    e.stack = undefined;
    expect(describeError(e, true)).not.toHaveProperty('stack');
    const withStack = new Error('has stack');
    expect(describeError(withStack, true)).toHaveProperty('stack');
  });
});
