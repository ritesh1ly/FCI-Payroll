/**
 * I-08 screen rules (plan 13 phase 13a; SRS-8-14, SRS-21-9). The server checks each again; these
 * only decide what the screen offers.
 */
import type { EventRow } from '../api/arrearApi';

/** The statuses the list filters by, in their lifecycle order. */
export const STATUSES = ['RAISED', 'COMPUTED', 'APPROVED', 'POSTED', 'REVERSED', 'REJECTED'] as const;

/** SRS-8-14: only a computed event goes for Approve &amp; Merge. */
export function maySubmit(e: EventRow): boolean {
  return e.status === 'COMPUTED';
}

/**
 * SRS-8-13: a single-member event not yet submitted is computed again by the Maker; one whose
 * amounts the source module stated (plan 13 phase 13g, D13-14) never is.
 */
export function mayRecompute(e: EventRow): boolean {
  return (
    e.singleMember &&
    !e.stated &&
    (e.status === 'RAISED' || e.status === 'COMPUTED') &&
    (!e.beyondWindow || e.hqException === 'APPROVED')
  );
}

/**
 * SRS-8-6: an event before the Headquarters back-date limit is held until Headquarters approves an
 * exception; the Maker asks once, while it is RAISED and no request waits.
 */
export function mayAskHeadquarters(e: EventRow): boolean {
  return e.beyondWindow && e.status === 'RAISED' && e.hqException === null;
}

/**
 * SRS-21-6 (I-08 action "Undo event"): a controlled undo is asked for an approved event not yet
 * posted - it then belongs to the open cycle it posts into; after posting a correction is a new
 * arrear or recovery.
 */
export function mayUndo(e: EventRow): boolean {
  return e.status === 'APPROVED' && e.postedToYymm !== null && e.postedToYymm.length === 6;
}

/** The cycle period (YYYY-MM) of a posting month "YYYYMM". */
export function cyclePeriod(yymm: string): string {
  return `${yymm.slice(0, 4)}-${yymm.slice(4, 6)}`;
}

/** SRS-21-9: the remark is mandatory. */
export function remarkGiven(remark: string): boolean {
  return remark.trim().length > 0;
}

/** "YYYYMM" as "MM/YYYY", or a dash. */
export function month(yymm: string | null): string {
  return yymm && yymm.length === 6 ? `${yymm.slice(4)}/${yymm.slice(0, 4)}` : '—';
}
