import { finYearOfDay } from '@/shared/time';
import type { CertificateLine, DeclarationLine, ReturnSummary } from '../api/screensApi';
import type { OutputVersion } from '../api/statutoryApi';

/**
 * What the drawn tax screens read from a record (OD-121): the label key of a status and the state of
 * a step. Display only: each decision stays in the service that owns it (SRS-7-1, BR-TAX-010, SRS-7-8).
 */

/** The quarter of a month of the financial year: April to June is Q1. */
function quarterOf(day: string): string {
  const month = Number(day.slice(5, 7));
  return `Q${Math.floor(((month + 8) % 12) / 3) + 1}`;
}

/** C-11 opens on the last completed quarter: the one a return can be prepared for. */
export function lastCompletedQuarter(today: string): { finYear: string; quarter: string } {
  const [y, m] = today.split('-').map(Number) as [number, number];
  const month = m > 3 ? m - 3 : m + 9;
  const day = `${m > 3 ? y : y - 1}-${String(month).padStart(2, '0')}-01`;
  return { finYear: finYearOfDay(day), quarter: quarterOf(day) };
}

/** I-07: the Status of a member's line, as a bundle key. */
export function declarationStatus(line: DeclarationLine): string {
  if (line.regime === null) return 'txw.i07.status.noRegime';
  if (line.capped) return 'txw.i07.status.capped';
  if (line.unproved) return 'txw.i07.status.proofPending';
  return line.roundId === null ? 'txw.i07.status.noRound' : 'txw.i07.status.complete';
}

/** I-07: the Proof of a member's line, as a bundle key; a dash for a member with no regime. */
export function proofLabel(line: DeclarationLine): string | null {
  if (line.regime === null) return null;
  if (line.declared80c === null && line.declared80d === null && line.declared24b === null) {
    return 'txw.i07.proof.none';
  }
  return line.unproved ? 'txw.i07.proof.notSubmitted' : 'txw.i07.proof.verified';
}

/** C-10: the Status of a line: a missing PAN stops a certificate first (SRS-7-13). */
export function certificateStatus(line: CertificateLine): string {
  if (line.status === 'RELEASED') return 'txw.c10.status.issued';
  if (line.panStatus !== 'VALID') return 'txw.c10.status.blockedPan';
  return line.status === 'MERGED' ? 'txw.c10.status.unsigned' : 'txw.c10.status.correctionPending';
}

/** M-17: the version that governs a financial year: the newest effective by its last day. */
export function versionOfYear(versions: readonly OutputVersion[], finYear: string): OutputVersion | null {
  const lastDay = `${Number(finYear.slice(0, 4)) + 1}-03-31`;
  const held = versions
    .filter((v) => v.effectiveFrom <= lastDay)
    .sort((a, b) => b.effectiveFrom.localeCompare(a.effectiveFrom));
  return held[0] ?? null;
}

/** C-11: the state of the eight drawn steps, each a bundle key of the right-hand pill. */
export function returnSteps(s: ReturnSummary | undefined): string[] {
  const held = s?.status ? s.status : null;
  const filed = held === 'FILED';
  const released = filed || held === 'RELEASED';
  const validated = released || s?.validated === true;
  return [
    held ? 'txw.c11.pill.done' : 'txw.c11.pill.pending',
    held ? 'txw.c11.pill.matched' : 'txw.c11.pill.pending',
    '',
    '',
    validated ? 'txw.c11.pill.passed' : 'txw.c11.pill.pending',
    released ? 'txw.c11.pill.done' : 'txw.c11.pill.pending',
    'txw.c11.pill.external',
    filed ? 'txw.c11.pill.done' : 'txw.c11.pill.pending',
  ];
}

/** A token shown as the pack shows it: its prefix and last four characters (R-PII). */
export function maskedToken(token: string | null): string {
  if (!token) return '—';
  const cut = token.indexOf('-');
  return `${cut > 0 ? token.slice(0, cut + 1) : ''}••${token.slice(-4)}`;
}
