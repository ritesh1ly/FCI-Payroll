import type { ParameterVersion, RateLine } from '../api/mastersApi';

/** Pure display and form rules of the master screens (the server enforces the same). */

const INR = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 2 });

/** "50%", "₹ 1,800" or "3": a rate value in its unit. */
export function formatRate(value: number, unit: string | null): string {
  if (unit === 'PERCENT') return `${INR.format(value)}%`;
  if (unit === 'INR') return `₹ ${INR.format(value)}`;
  return INR.format(value);
}

/** The rows M-23 draws as one: a rate or percentage with its ceiling (the two stay separate parameters). */
const DRAWN_PAIRS: Record<string, string> = { OTA_RATE: 'OTA_CEILING', NPA_PCT: 'NPA_CEILING' };

/** The rows of a page as M-23 draws them: a pair's ceiling joins the row of its rate. */
export function drawnParameterRows<R extends { code: string }>(rows: R[]): { row: R; ceiling: R | null }[] {
  const joined = new Set(Object.values(DRAWN_PAIRS));
  return rows
    .filter((r) => !joined.has(r.code) || !rows.some((o) => DRAWN_PAIRS[o.code] === r.code))
    .map((row) => ({
      row,
      ceiling: rows.find((o) => o.code === DRAWN_PAIRS[row.code]) ?? null,
    }));
}

/** The code a master carries in the labels: its catalogue code without the pay pattern. */
export function masterBase(code: string): string {
  return code.replace(/_(IDA|CDA)$/, '');
}

/** "AE-131 · ₹ 5,36,000": the arrear event a DA revision raised, or a dash. */
export function arrearImpact(reference: string | null, amount: number | null): string {
  if (!reference) return '—';
  return amount === null ? reference : `${reference} · ₹ ${INR.format(amount)}`;
}

/** The pill of a version's state on M-04. */
export function versionTone(status: string): string {
  if (status === 'ACTIVE') return 'p-ok';
  if (status === 'AWAITING_APPROVAL' || status === 'SCHEDULED') return 'p-warn';
  return status === 'REJECTED' ? 'p-bad' : 'p-gray';
}

/**
 * Why the M-04 DA form may not be saved (an i18n key), or null: the drawn validation of Pattern, Rate
 * (0 to 100), Computed On (the characters the server stores with the proposal) and Effective From.
 */
export function daFormProblem(d: {
  pattern: string;
  rate: string;
  basis: string;
  from: string;
}): string | null {
  if (!d.pattern) return 'm04.err.pattern';
  const rate = Number(d.rate);
  if (d.rate === '' || !Number.isFinite(rate) || rate < 0 || rate > 100) return 'm04.err.rate';
  if (!/^[A-Za-z0-9 +/().,%-]{1,200}$/.test(d.basis.trim())) return 'm04.err.basis';
  return d.from ? null : 'm04.err.from';
}

/** A line being edited: its rate value is null while the field is blank (never read as zero). */
export type RateDraftLine = Omit<RateLine, 'rateValue'> & { rateValue: number | null };

/** What a line applies to: pay pattern, city class, category, Basic slab. */
export function lineKey(line: RateDraftLine): string {
  const parts = [
    line.payPattern,
    line.cityClass ? `${line.cityClass}` : null,
    line.categoryCode,
    line.basicSlabFrom !== null || line.basicSlabTo !== null
      ? `${line.basicSlabFrom ?? 0}–${line.basicSlabTo ?? '∞'}`
      : null,
  ].filter((p): p is string => p !== null && p !== '');
  return parts.join(' · ');
}

/** A one-line summary of a version's lines, e.g. "X 30% · Y 20% · Z 10%". */
export function summarise(lines: RateLine[]): string {
  return lines
    .map((l) => {
      const key = lineKey(l);
      return `${key ? `${key} ` : ''}${formatRate(l.rateValue, l.unit)}`;
    })
    .join(' · ');
}

/** The values of an other-parameter version, in the order the screen shows them. */
export function parameterValues(p: ParameterVersion): string[] {
  const values: string[] = [];
  if (p.percentValue !== null) values.push(`${INR.format(p.percentValue)}%`);
  if (p.numericValue !== null) values.push(INR.format(p.numericValue));
  if (p.ceilingValue !== null) values.push(`≤ ₹ ${INR.format(p.ceilingValue)}`);
  if (p.windowFromTime) values.push(`${p.windowFromTime}–${p.windowToTime ?? ''}`);
  if (p.instalmentCount !== null) values.push(`× ${p.instalmentCount}`);
  return values;
}

/**
 * An empty line for the editor (a percentage on the staff screen, an amount elsewhere). The rate value
 * starts blank: the Maker enters it (M-04 Rate Value is mandatory, SRS l.3651).
 */
export function emptyLine(percent: boolean): RateDraftLine {
  return {
    designationId: null,
    categoryCode: null,
    payPattern: null,
    basicSlabFrom: null,
    basicSlabTo: null,
    cityClass: null,
    rateCode: '',
    rateValue: null,
    unit: percent ? 'PERCENT' : 'INR',
  };
}

/**
 * Why the lines may not be submitted (an i18n key), or null when they may: at least one line; a rate
 * value entered (a blank is never read as zero), of zero or more and at most 100 for a percentage; on
 * M-04 a pay pattern on every line (SRS l.3653, the server refuses the same, MST-422-LINES).
 */
export function linesProblem(lines: RateDraftLine[], requirePattern = false): string | null {
  if (lines.length === 0) return 'mst.why.noLines';
  if (lines.some((l) => l.rateValue === null || !Number.isFinite(l.rateValue)))
    return 'mst.why.valueRequired';
  if (requirePattern && lines.some((l) => !l.payPattern)) return 'mst.why.patternRequired';
  return linesValid(lines, requirePattern) ? null : 'mst.why.linesInvalid';
}

/** Lines the form may submit: a rate code, a value of zero or more, at most 100 for a percentage. */
export function linesValid(lines: RateDraftLine[], requirePattern = false): lines is RateLine[] {
  return (
    lines.length > 0 &&
    lines.every(
      (l) =>
        /^[A-Z][A-Z0-9_]{1,39}$/.test(l.rateCode) &&
        l.rateValue !== null &&
        Number.isFinite(l.rateValue) &&
        l.rateValue >= 0 &&
        (l.unit !== 'PERCENT' || l.rateValue <= 100) &&
        (!requirePattern || !!l.payPattern) &&
        (l.basicSlabFrom === null || l.basicSlabTo === null || l.basicSlabTo > l.basicSlabFrom),
    )
  );
}
