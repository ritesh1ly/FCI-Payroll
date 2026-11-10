/**
 * I-05 and M-03 screen rules (plan 17; SRS-13-5, SRS-13-18, SRS-13-23, SRS-13-24). The server
 * checks each again; these only decide what the screen offers.
 */
import type { CaseRequest, DueRow, VersionRequest } from '../api/incrementApi';

/** The flags that stop an increment (STAGES_NOT_CONFIGURED only warns, D17-3). */
export const BLOCKING = ['BARRED', 'ABOVE_MAXIMUM', 'NOT_A_STAGE', 'NOT_CONFIGURED', 'NO_SCALE'];

export const CASE_TYPES = [
  'PROMOTION_FIXATION',
  'INDIVIDUAL_REVISION',
  'ADDITIONAL_INCREMENT',
  'STAGNATION_INCREMENT',
  'BASIC_CORRECTION',
  'DATE_CORRECTION',
  'REVERSE_ENTRY',
  'OBSERVATION_REFIXATION',
] as const;

/** SRS-13-27: the observations a past Basic is refixed on. */
export const OBSERVATION_SOURCES = ['AUDIT', 'ACCOUNTS'] as const;

/** SRS-13-21: a due member is submitted when nothing blocks it and it is not already waiting. */
export function selectable(r: DueRow): boolean {
  return !r.pending && !r.flags.some((f) => BLOCKING.includes(f));
}

/** The case types for which the Basic is entered (the others are proposed by the system). */
export function entersBasic(type: string | undefined): boolean {
  return (
    type === 'INDIVIDUAL_REVISION' ||
    type === 'ADDITIONAL_INCREMENT' ||
    type === 'BASIC_CORRECTION' ||
    type === 'OBSERVATION_REFIXATION'
  );
}

/** SRS-13-18 / 13-24: everything a case needs before it is previewed or submitted. */
export function caseReady(c: Partial<CaseRequest>): boolean {
  const filled = (v?: string) => !!v && v.trim().length > 0;
  return (
    filled(c.eis) &&
    !!c.caseType &&
    filled(c.effectiveDate) &&
    filled(c.orderNumber) &&
    filled(c.orderDate) &&
    filled(c.issuingAuthority) &&
    filled(c.reason) &&
    (!entersBasic(c.caseType) || (typeof c.basic === 'number' && c.basic > 0)) &&
    (c.caseType !== 'DATE_CORRECTION' || filled(c.correctedDueDate)) &&
    (c.caseType !== 'OBSERVATION_REFIXATION' ||
      (!!c.observationSource &&
        filled(c.observationReference) &&
        (c.observationReference ?? '').trim().length <= 100))
  );
}

/** "60000, 62400 ..." as numbers; empty when any part is not a positive number. */
export function parseStages(text: string): number[] {
  const parts = text
    .split(/[\s,]+/)
    .map((p) => p.trim())
    .filter((p) => p.length > 0);
  const values = parts.map(Number);
  return values.every((v) => Number.isFinite(v) && v > 0) ? values : [];
}

/**
 * M-03 Increment Stages "Grid" (SRS l.3949): the stages entered one per row; empty rows are
 * ignored; none when any row is not a positive number.
 */
export function stagesOfRows(rows: string[]): number[] {
  const filled = rows.map((r) => r.trim()).filter((r) => r.length > 0);
  const values = filled.map(Number);
  return values.every((v) => Number.isFinite(v) && v > 0) ? values : [];
}

/** The number (from 1) of the first grid row that is not a positive number, or null. */
export function badStageRow(rows: string[]): number | null {
  const i = rows.findIndex(
    (r) => r.trim() !== '' && !(Number.isFinite(Number(r.trim())) && Number(r.trim()) > 0),
  );
  return i < 0 ? null : i + 1;
}

/** M-03: a version with bounds in order and every stage between them. */
export function versionReady(v: Partial<VersionRequest>): boolean {
  return (
    typeof v.min === 'number' &&
    typeof v.max === 'number' &&
    v.min < v.max &&
    !!v.effectiveFrom &&
    typeof v.circularId === 'number' &&
    !!v.wageRevision?.trim() &&
    (v.stages?.length ?? 0) > 0 &&
    (v.stages ?? []).every((s) => s >= (v.min as number) && s <= (v.max as number)) &&
    !!v.reason?.trim()
  );
}

/** SC-17-0030: the scale types M-03 offers (SRS M-03 field table, "Dropdown (IDA / CDA)"). */
export const SCALE_TYPES = ['IDA', 'CDA'] as const;

/** The particulars of a drawn order card as one multipart form per file (the document module stores each). */
export function orderForm(order: {
  documentType: string;
  reference: string;
  orderDate: string;
  issuingOffice: string;
  files: File[];
}): FormData[] {
  return order.files.map((file) => {
    const form = new FormData();
    form.append('documentType', order.documentType);
    if (order.reference.trim() !== '') form.append('reference', order.reference.trim());
    form.append('orderDate', order.orderDate);
    form.append('issuingAuthority', order.issuingOffice.trim());
    form.append('file', file);
    return form;
  });
}

/** SC-17-0030: a new scale has a code (letters, digits or a hyphen, at most 10), a name and a type. */
export function newScaleReady(code: string, name: string, scaleType: string): boolean {
  return (
    /^[A-Za-z0-9-]{1,10}$/.test(code.trim()) &&
    name.trim().length > 0 &&
    name.trim().length <= 100 &&
    /^[ -~]+$/.test(name.trim()) &&
    (SCALE_TYPES as readonly string[]).includes(scaleType)
  );
}
