import type { DayRow, GangStatus, RegisterRow } from '../api/captureApi';
import type { PayrollType } from '../api/labourApi';

/** The view of a sidebar entry (&engine=DPS) as the payroll type it opens (SRS: one screen per engine). */
export function engineType(view: string | null | undefined): PayrollType | null {
  switch (view) {
    case 'DEPT':
      return 'DEPT_LABOUR';
    case 'DPS':
      return 'DPS_LABOUR';
    case 'NWNP':
      return 'NWNP_LABOUR';
    default:
      return null;
  }
}

/** The pill of a gang's month. */
export function gangTone(status: GangStatus): string {
  if (status === 'READY') return 'p-ok';
  return status === 'EXCEPTION' ? 'p-bad' : 'p-warn';
}

/** The fourth code column of the summary: P exists only in the DPS set (Annexure A.7), else S. */
export function fourthCode(type: PayrollType): 'P' | 'S' {
  return type === 'DPS_LABOUR' ? 'P' : 'S';
}

/** Can the row's day be marked on the drawn blank entry: nothing held, nothing blocking, by hand. */
export function dayMarkable(row: DayRow): boolean {
  return row.code === null && row.otherCode === null && !row.pastRetirement && row.sourceMode !== 'BIOMETRIC';
}

/** A row's Source column: [message key, args]. */
export function daySource(row: DayRow): [string, string[]] {
  if (row.captureMode === 'ANNA_DARPAN') return ['i01w.src.anna', []];
  if (row.otherCode !== null || row.pastRetirement) return ['i01w.src.none', []];
  if (row.sourceMode === 'MANUAL' || (row.punchIn === null && row.punchOut === null && !row.exception))
    return ['i01w.src.manual', []];
  if (row.punchIn !== null && row.punchOut !== null) return ['i01w.src.punches', [row.punchIn, row.punchOut]];
  return [row.punchOut === null ? 'i01w.src.missingOut' : 'i01w.src.missingIn', []];
}

/** Marks to send per gang: the day is saved whole (SRS-2-35), so a gang needs each listed labour marked. */
export function marksByGang(
  rows: DayRow[],
  chosen: Record<number, string>,
): Map<number, Record<number, string>> {
  const gangs = new Set(rows.filter((r) => chosen[r.employeeId]).map((r) => r.gangId));
  const out = new Map<number, Record<number, string>>();
  for (const gang of gangs) {
    const marks: Record<number, string> = {};
    for (const r of rows.filter((x) => x.gangId === gang && !x.pastRetirement && x.otherCode === null)) {
      const code = chosen[r.employeeId] ?? r.code;
      if (code) marks[r.employeeId] = code;
    }
    out.set(gang, marks);
  }
  return out;
}

/** The status pill of a register row: tone, key and args. */
export function registerStatus(row: RegisterRow, dps: boolean): [string, string, string[]] {
  switch (row.status) {
    case 'APPROVED':
    case 'PROCESSED':
      return ['p-ok', 'i02w.status.accepted', []];
    case 'DUPLICATE_HELD':
      return row.duplicateOf
        ? ['p-bad', 'i02w.status.duplicateOf', [row.duplicateOf]]
        : ['p-bad', 'i02w.status.duplicate', []];
    case 'REJECTED':
      return row.source === 'INTF'
        ? ['p-bad', 'i02w.status.rejectedImport', []]
        : ['p-bad', dps ? 'i02.dpsStatus.REJECTED' : 'i02.status.REJECTED', []];
    case 'SUBMITTED':
      return ['p-warn', dps ? 'i02.dpsStatus.SUBMITTED' : 'i02.status.SUBMITTED', []];
    case 'DRAFT':
      return ['p-info', dps ? 'i02.dpsStatus.DRAFT' : 'i02.status.DRAFT', []];
    default:
      return ['', `i02.status.${row.status}`, []];
  }
}
