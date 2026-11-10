import { describe, expect, it } from 'vitest';
import type { MyVpf, VpfRequest } from '@/modules/cpf/api/vpfApi';
import { amountValid, monthLabel, requestBlocked, withMaker } from '@/modules/cpf/business/vpfRules';

/** VPF subscription change rules (plan 14 phase 14c; BR-ESEVA-006; D14-4). */
describe('VPF change rules', () => {
  const request = (status: VpfRequest['status'], awaitingChecker = false) =>
    ({ status, awaitingChecker }) as VpfRequest;
  const mine = (over: Partial<MyVpf> = {}): MyVpf => ({
    finYear: '2026-27',
    currentAmount: 2500,
    cpfMember: true,
    requests: [],
    ...over,
  });

  it('BR-ESEVA-006: the amount is non-negative rupees of at most two decimals within NUMBER(12,2)', () => {
    expect(amountValid('0')).toBe(true);
    expect(amountValid(' 2500.5 ')).toBe(true);
    expect(amountValid('2500.55')).toBe(true);
    expect(amountValid('9999999999.99')).toBe(true);
    expect(amountValid('10000000000')).toBe(false);
    expect(amountValid('2500.555')).toBe(false);
    expect(amountValid('2500.')).toBe(false);
    expect(amountValid('.5')).toBe(false);
    expect(amountValid('-1')).toBe(false);
    expect(amountValid('1.2.3')).toBe(false);
    expect(amountValid('abc')).toBe(false);
    expect(amountValid('')).toBe(false);
  });

  it('BR-ESEVA-006: a request needs a CPF ledger, none waiting and a new valid amount', () => {
    expect(requestBlocked(mine({ cpfMember: false }), '3000')).toBe('vpf.why.noLedger');
    expect(requestBlocked(mine({ requests: [request('SUBMITTED')] }), '3000')).toBe('vpf.why.pending');
    expect(requestBlocked(mine(), 'x')).toBe('vpf.why.amount');
    expect(requestBlocked(mine(), '2500.00')).toBe('vpf.why.unchanged');
    expect(requestBlocked(mine({ requests: [request('APPLIED')] }), '3000')).toBeNull();
  });

  it('BR-ESEVA-006: the Maker acts only on a request still with the Maker', () => {
    expect(withMaker(request('SUBMITTED'))).toBe(true);
    expect(withMaker(request('SUBMITTED', true))).toBe(false);
    expect(withMaker(request('APPLIED'))).toBe(false);
    expect(monthLabel('202611')).toBe('11/2026');
    expect(monthLabel('2026')).toBe('2026');
  });
});
