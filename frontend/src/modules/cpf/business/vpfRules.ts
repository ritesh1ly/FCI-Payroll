/**
 * VPF subscription change rules (plan 14 phase 14c; BR-ESEVA-006; D14-4). The server checks each
 * again; these only decide what the screen offers.
 */
import type { MyVpf, VpfRequest } from '../api/vpfApi';

/** DDL NUMBER(12,2). */
const MAX = 9999999999.99;

/** A non-negative amount of at most two decimals within NUMBER(12,2). */
export function amountValid(text: string): boolean {
  const s = text.trim();
  const [whole, decimals, extra] = s.split('.');
  if (extra !== undefined || !whole || !/^\d{1,10}$/.test(whole)) return false;
  if (decimals !== undefined && !/^\d{1,2}$/.test(decimals)) return false;
  return Number(s) <= MAX;
}

/** What blocks a new request, as a label key, or null: no CPF ledger, one waiting, the same amount. */
export function requestBlocked(mine: MyVpf, text: string): string | null {
  if (!mine.cpfMember) return 'vpf.why.noLedger';
  if (mine.requests.some((r) => r.status === 'SUBMITTED')) return 'vpf.why.pending';
  if (!amountValid(text)) return 'vpf.why.amount';
  if (Number(text.trim()) === mine.currentAmount) return 'vpf.why.unchanged';
  return null;
}

/** The Maker verifies or returns only a request still with the Maker. */
export function withMaker(r: VpfRequest): boolean {
  return r.status === 'SUBMITTED' && !r.awaitingChecker;
}

/** "YYYYMM" as "MM/YYYY". */
export function monthLabel(yymm: string): string {
  return yymm.length === 6 ? `${yymm.slice(4)}/${yymm.slice(0, 4)}` : yymm;
}
