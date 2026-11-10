import { describe, expect, it } from 'vitest';
import { amountValid } from '@/modules/cpf/business/vpfRules';

/**
 * Mutation-gap tests for vpfRules.ts (BR-ESEVA-006).
 * Equivalent mutants, not tested: L14 ConditionalExpression/LogicalOperator on the extra-dot check
 * (a second dot always makes Number() NaN, so L16 refuses it); L16 `true` (ten whole digits and two
 * decimals can never exceed the maximum 9999999999.99); L24 `text` for text.trim() (Number() trims).
 */
describe('vpfRules mutation gaps', () => {
  it('BR-ESEVA-006: the whole part is plain digits, no exponent form', () => {
    expect(amountValid('1e3')).toBe(false);
    expect(amountValid('1000')).toBe(true);
    expect(amountValid('9999999999.99')).toBe(true);
    expect(amountValid('12345678901')).toBe(false);
    expect(amountValid('1.2.3')).toBe(false);
  });
});
