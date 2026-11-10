/** X-04 / History button presentation rules (pure functions). */
import type { Outcome, Person } from '../api/auditApi';

/** The pill colour of a FAP-13 outcome: done, waiting, sent back, refused or failed. */
export function outcomeClass(outcome: Outcome): string {
  switch (outcome) {
    case 'APPROVED':
    case 'POSTED':
      return 'pill p-ok';
    case 'SUBMITTED':
      return 'pill p-info';
    case 'RETURNED':
      return 'pill p-warn';
    case 'REJECTED':
    case 'FAILED':
      return 'pill p-bad';
    default:
      return 'pill p-gray';
  }
}

/** "Name (Employee No.)", the login id when the user has no name, or a dash for nobody. */
export function personLabel(person: Person | null | undefined): string {
  if (!person) return '—';
  const name = person.name ?? person.loginId;
  return person.employeeNumber ? `${name} (${person.employeeNumber})` : name;
}

/**
 * A legacy row image (JSON as held in legacy) as "FIELD: value" pairs for reading; text that is not
 * a JSON object is shown as held. The row is never split into before / after (SC-03-0039).
 */
export function snapshotPairs(rowImage: string | null): string[] {
  if (!rowImage) return [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(rowImage);
  } catch {
    // not JSON: shown as held (only the parse is guarded, so a fault below is never hidden)
  }
  if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
    return Object.entries(parsed as Record<string, unknown>).map(
      ([k, v]) => `${k}: ${v === null ? '—' : String(v)}`,
    );
  }
  return [rowImage];
}

/** The fields of a version or reconstruction in a stable order (as the server names them). */
export function fieldRows(values: Record<string, string | null>): [string, string][] {
  return Object.entries(values).map(([k, v]) => [k, v ?? '—']);
}

/** A record key the enquiry accepts: 1 to 100 characters after trimming (X-04 Record Key). */
export function validRecordKey(key: string): boolean {
  const trimmed = key.trim();
  return trimmed.length > 0 && trimmed.length <= 100;
}
