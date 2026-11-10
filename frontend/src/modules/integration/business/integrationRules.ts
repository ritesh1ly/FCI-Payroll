import type { InterfaceRow, QueueItem, Window } from '../api/integrationApi';

/** SRS-INT-32: the pill of a window status (OK, Partial, Failed) or of an interface without one. */
export function statusTone(status: string | null): string {
  if (status === 'OK') return 'p-ok';
  if (status === 'PARTIAL') return 'p-warn';
  if (status === 'FAILED') return 'p-bad';
  return 'p-info';
}

/** SRS-INT-32: received = accepted + rejected; a window that does not balance is shown as losing records. */
export function balanced(w: { received: number; accepted: number; rejected: number }): boolean {
  return w.received === w.accepted + w.rejected;
}

/** SRS-INT-33 l.5228 / X-03 l.5175: the stated reason of every Administrator-only action on X-03. */
export const ADMIN_ONLY = 'x03.reason.adminOnly';

/** SRS-INT-31: an exception still waiting for the Administrator. */
export function unresolved(e: QueueItem): boolean {
  return e.status === 'OPEN' || e.status === 'IN_PROGRESS';
}

/**
 * SRS-INT-31 / SRS-INT-33: why Reprocess is not offered (the label key), or null. Only the
 * Administrator reprocesses, an item still open, of an interface with an adapter and a staged record.
 */
export function reprocessBlocked(e: QueueItem, isAdmin: boolean): string | null {
  if (!isAdmin) return ADMIN_ONLY;
  if (!unresolved(e)) return 'x03.why.resolved';
  if (!e.adapter) return 'x03.why.noAdapter';
  if (e.stagingId === null) return 'x03.why.noRecord';
  return null;
}

/**
 * X-03 l.5172 / SRS-INT-33: why Reprocess Failed is not offered for a window (the label key), or null. Only the
 * Administrator, a window with rejected records or a failed run, of an interface with an adapter (the server
 * refuses a window with no open record).
 */
export function windowReprocessBlocked(w: Window, isAdmin: boolean): string | null {
  if (!isAdmin) return ADMIN_ONLY;
  if (w.rejected === 0 && w.status !== 'FAILED') return 'x03.why.nothingFailed';
  if (!w.adapter) return 'x03.why.noAdapter';
  return null;
}

/** SRS-INT-31 / SRS-INT-33: why Write off is not offered, or null. */
export function writeOffBlocked(e: QueueItem, isAdmin: boolean): string | null {
  if (!isAdmin) return ADMIN_ONLY;
  return unresolved(e) ? null : 'x03.why.resolved';
}

/** SRS-INT-16: an interface is configured when its settings are in force. */
export function configured(row: InterfaceRow): boolean {
  return row.settings !== null;
}

/** X-03: the age in whole days of the oldest HRMS change awaiting I-21 (null when none). */
export function ageDays(oldest: string | null, now: Date): number | null {
  if (!oldest) return null;
  const ms = now.getTime() - new Date(oldest).getTime();
  return ms < 0 ? 0 : Math.floor(ms / 86_400_000);
}
