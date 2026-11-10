import { type PageResult } from '@/shared/ui';
import { API_BASE, api } from '@/shared/api';

/** SRS-18-24: produced on O-04, produced on its own screen, or Blocked with the reason. */
export type EntryStatus = 'AVAILABLE' | 'PRODUCED_ELSEWHERE' | 'BLOCKED';

/** One catalogue entry (O-04 / M-12). */
export interface CatalogueEntry {
  key: string;
  name: string;
  family: string;
  section: string;
  domain: string;
  producingScreen: string;
  payrollTypes: string | null;
  periodicity: string | null;
  lockRequirement: string;
  versionNo: number | null;
  layoutFormat: string | null;
  blockedReason: string | null;
  status: EntryStatus;
}

/** SRS-18-28: the hierarchy levels of the Component-wise Summary. */
export type Level = 'UNIT' | 'REGION' | 'ZONE' | 'ALL';

export interface SummaryLine {
  levelCode: string;
  levelName: string;
  payrollType: string;
  payCode: number;
  component: string;
  componentType: 'EARNING' | 'DEDUCTION';
  amount: number;
  members: number;
}

export interface Summary {
  yymm: string;
  level: Level;
  provisional: boolean;
  earnings: number;
  deductions: number;
  postedGross: number;
  postedDeductions: number;
  controlTotalsMatch: boolean;
  lines: SummaryLine[];
}

/** SRS-18-12 (a): whose results the summary reads. */
export type MemberStatus = 'CURRENT' | 'SEPARATED' | 'BOTH';

/** SRS-18-12 (a) (US-GAP-37): the O-04 parameters besides the month and level; '' / null = every one. */
export interface SummaryFilters {
  officeId: number | null;
  payrollType: string;
  category: string;
  memberStatus: MemberStatus;
  /** SRS-18-12 (US-GAP-265): the member range (employee numbers), each end optional. */
  fromEis?: string;
  toEis?: string;
  /** SRS-18-12: the order of the lines on screen and in the export. */
  sort?: 'PAY_CODE' | 'AMOUNT';
}

/** What O-04 offers to choose from for a month (SRS-18-12 (a)). */
export interface SummaryOptions {
  offices: { id: number; label: string }[];
  payrollTypes: string[];
  categories: string[];
}

/** M-12: one Annexure section of the catalogue with its entries and how many are Blocked. */
export interface SectionCount {
  section: string;
  reports: number;
  blocked: number;
}

/** M-12: the tiles, the catalogue by family and the choices of the two filters (OD-123). */
export interface DefinitionSummary {
  size: number;
  active: number;
  sections: SectionCount[];
  families: string[];
  payrollTypes: string[];
}

/** M-12: the filters of the definitions list; '' = every one. */
export interface DefinitionFilter {
  family: string;
  payrollType: string;
  text: string;
}

/** One version of a definition (M-12). */
export interface ReportVersion {
  id: number;
  versionNo: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  layoutFormat: string | null;
  periodLockRequired: boolean;
  storeMode: string;
  approvalState: string;
  blocked: boolean;
  blockedReason: string | null;
  createdBy: string;
  createdAt: string;
  roundingRuleKey?: string | null;
  parameters?: ModelParameter[];
  fields?: ModelField[];
  controls?: ModelControl[];
  distribution?: ModelDistribution[];
}

/** SRS-18-9: one parameter of the set, with its own validation rule (report_parameter). */
export interface ModelParameter {
  code: string;
  label: string | null;
  dataType: string;
  mandatory: boolean;
  roleScoped: boolean;
  defaultExpression: string | null;
  validationRule: string;
}

/** SRS-18-9: one field bound to the component catalogue or the field-source model. */
export interface ModelField {
  label: string;
  binding: 'COMPONENT' | 'FIELD_SOURCE' | 'AGGREGATE' | 'LITERAL' | 'DERIVED';
  reference: string;
  formatMask: string | null;
}

/** M-12: a control total; the tolerance as typed until it is sent. */
export interface ModelControl {
  label: string;
  reconcilesAgainst: string;
  tolerance: string | number;
  releaseGate: boolean;
}

/** M-12: one consumer of the output. */
export interface ModelDistribution {
  consumerName: string;
  consumerType: string;
  channel: string;
  schedule: string | null;
}

export interface VersionProposal {
  layoutFormat: string | null;
  periodLockRequired: boolean;
  storeMode: 'STORE' | 'REGENERATE';
  blocked: boolean;
  blockedReason: string | null;
  effectiveFrom: string;
  roundingRuleKey?: string | null;
  parameters?: ModelParameter[];
  fields?: ModelField[];
  controls?: ModelControl[];
  distribution?: ModelDistribution[];
}

/** The query of the summary and its export: the month, the level and every parameter chosen. */
export function summaryQuery(yymm: string, level: Level, f: SummaryFilters): string {
  const p = new URLSearchParams();
  p.set('yymm', yymm);
  p.set('level', level);
  if (f.officeId !== null) p.set('officeId', String(f.officeId));
  if (f.payrollType) p.set('payrollType', f.payrollType);
  if (f.category) p.set('category', f.category);
  p.set('memberStatus', f.memberStatus);
  if (f.fromEis?.trim()) p.set('fromEis', f.fromEis.trim());
  if (f.toEis?.trim()) p.set('toEis', f.toEis.trim());
  if (f.sort && f.sort !== 'PAY_CODE') p.set('sort', f.sort);
  return p.toString();
}

/** The only place the reporting module talks to /api/v1/reports and /v1/report-definitions. */
export const reportApi = {
  catalogue: (family: string | undefined, text: string | undefined, page: number, size: number) => {
    const p = new URLSearchParams();
    if (family) p.set('family', family);
    if (text) p.set('text', text);
    p.set('page', String(page));
    p.set('size', String(size));
    return api<PageResult<CatalogueEntry>>(`/v1/reports/catalogue?${p.toString()}`);
  },
  /** M-12: the same catalogue under the M-12 gate (UI audit S8: the Rule Approver has no O-04). */
  definitions: (filter: DefinitionFilter, page: number, size: number) => {
    const p = new URLSearchParams();
    if (filter.family) p.set('family', filter.family);
    if (filter.payrollType) p.set('payrollType', filter.payrollType);
    if (filter.text) p.set('text', filter.text);
    p.set('page', String(page));
    p.set('size', String(size));
    return api<PageResult<CatalogueEntry>>(`/v1/report-definitions?${p.toString()}`);
  },
  /** M-12 (OD-123): the tiles, the family table and the filter choices, from the catalogue itself. */
  definitionSummary: () => api<DefinitionSummary>('/v1/report-definitions/summary'),
  summary: (yymm: string, level: Level, filters: SummaryFilters) =>
    api<Summary>(`/v1/reports/component-summary?${summaryQuery(yymm, level, filters)}`),
  /** SRS l.3501: the latest locked month of the session's offices (YYYYMM), or null. */
  latestLocked: () => api<{ yymm: string | null }>('/v1/reports/component-summary/latest-locked'),
  summaryOptions: (yymm: string) =>
    api<SummaryOptions>(`/v1/reports/component-summary/options?yymm=${encodeURIComponent(yymm)}`),
  summaryExportUrl: (yymm: string, level: Level, filters: SummaryFilters, lang: 'en' | 'hi') =>
    `${API_BASE}/v1/reports/component-summary/export?${summaryQuery(yymm, level, filters)}&lang=${lang}`,
  versions: (key: string) =>
    api<ReportVersion[]>(`/v1/report-definitions/${encodeURIComponent(key)}/versions`),
  propose: (key: string, body: VersionProposal) =>
    api<{ id: number; status: string }>(`/v1/report-definitions/${encodeURIComponent(key)}/versions`, {
      method: 'POST',
      body,
    }),
};
