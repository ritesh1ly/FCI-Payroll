import type { DayRate, GangRole, RateSet } from '../api/labourApi';
import type { GangLine, MemberLine } from '../api/labourScreenApi';

/** The pills of the drawn tables: what the record's state reads and how it is coloured. */
export type Tone = 'p-ok' | 'p-warn' | 'p-bad' | 'p-gray';

export interface Pill {
  key: string;
  tone: Tone;
}

/**
 * The Status pill of a labour member (M-02 as drawn): a transfer waiting for the Checker first, then what the
 * record lacks (the identifiers the depot's work depends on), then the labour_status as held.
 */
export function memberPill(m: MemberLine): Pill {
  if (m.transferPending) return { key: 'm02w.state.TRANSFER', tone: 'p-warn' };
  if (m.status !== 'ACTIVE') return { key: `labour.status.${m.status}`, tone: 'p-gray' };
  if (m.cpfCode === null && m.aadhaarMasked === null) return { key: 'm02w.state.AADHAAR_CPF', tone: 'p-bad' };
  if (m.cpfCode === null) return { key: 'm02w.state.CPF', tone: 'p-bad' };
  return { key: 'labour.status.ACTIVE', tone: 'p-ok' };
}

/** BR-LABM-001 / BR-LABM-006: the Status pill of a gang as drawn on M-09. */
export function gangPill(g: GangLine): Pill {
  if (g.endBlocked) return { key: 'm09w.state.END_BLOCKED', tone: 'p-bad' };
  if (g.status === 'END_DATED') return { key: 'm09.status.END_DATED', tone: 'p-gray' };
  if (g.gangType === 'HANDLING' && g.sardar === null) return { key: 'm09w.state.NO_SARDAR', tone: 'p-bad' };
  if (g.gangType === 'HANDLING' && g.mandal === null) return { key: 'm09w.state.NO_MANDAL', tone: 'p-bad' };
  return { key: 'm09.status.ACTIVE', tone: 'p-ok' };
}

/** SC-19: the one version-status vocabulary of the rate and norm screens. */
export function versionPill(approvalState: string, effectiveTo: string | null, today: string): Pill {
  if (approvalState === 'PENDING_APPROVAL') return { key: 'lw.state.PENDING', tone: 'p-warn' };
  if (approvalState === 'REJECTED') return { key: 'lw.state.REJECTED', tone: 'p-bad' };
  if (effectiveTo !== null && effectiveTo < today) return { key: 'lw.state.SUPERSEDED', tone: 'p-gray' };
  return { key: 'lw.state.ACTIVE', tone: 'p-ok' };
}

/** The approved set in force today: the newest one begun and not ended. */
export function setInForce(sets: RateSet[] | undefined, today: string): RateSet | null {
  return (
    (sets ?? []).find(
      (s) =>
        s.approvalState === 'APPROVED' &&
        s.effectiveFrom <= today &&
        (s.effectiveTo === null || s.effectiveTo >= today),
    ) ?? null
  );
}

const ROLE_ORDER: GangRole[] = ['SARDAR', 'MANDAL', 'HANDLING', 'ANCILLARY'];

/** A set's designations in the drawn order: Sardar, Mandal, Handling, Ancillary. */
export function byRole(rates: DayRate[]): DayRate[] {
  const rank = (r: DayRate) => {
    const at = ROLE_ORDER.indexOf(r.gangRole as GangRole);
    return at < 0 ? ROLE_ORDER.length : at;
  };
  return [...rates].sort((a, b) => rank(a) - rank(b));
}

/** A rupee figure with Indian digit grouping, or the screen's "not configured" text. */
export function rupees(value: number | null | undefined, none: string): string {
  return value === null || value === undefined ? none : `₹ ${Number(value).toLocaleString('en-IN')}`;
}

/** A value as drawn in a table: Indian digit grouping, a dash when the record has none. */
export function figure(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : Number(value).toLocaleString('en-IN');
}

/** "1 – 30", "91 and above" (M-06 Above-norm range). */
export function rangeText(from: number, to: number | null, above: string): string {
  return to === null ? `${from} ${above}` : `${from} – ${to}`;
}

export interface NormEntry {
  depot: string;
  mou: string;
  mln: string;
  mlsw: string;
  height: string;
  lead: string;
  effective: string;
}

/** Every drawn field of the New Depot Norm Set form is mandatory. */
export function normEntryReady(e: NormEntry): boolean {
  return Object.values(e).every((v) => v.trim() !== '');
}

export interface AsorEntry {
  effective: string;
  piece: string;
  sardar: string;
  mandal: string;
  handling: string;
  ancillary: string;
}

/** All fields of the New NWNP ASOR Rate Set form are mandatory and numeric, none negative. */
export function asorEntryReady(e: AsorEntry): boolean {
  const { effective, ...amounts } = e;
  return (
    effective !== '' &&
    Object.values(amounts).every((v) => v.trim() !== '' && !Number.isNaN(Number(v)) && Number(v) >= 0)
  );
}
