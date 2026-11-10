import { API_BASE, api } from '@/shared/api';
import type { PageResult } from '@/shared/ui';
import type { GangRow, PayrollType } from './labourApi';

/**
 * The lists of I-01 and I-02 as Wireframes v6.1 draws them (OD-121; API-20-2): one view per labour
 * payroll over the gangs of the session's depots. Punches and Anna Darpan records are what Payroll
 * holds; neither source has an API yet (PENDING API-23-4, API-23-5).
 */
export interface GangOption {
  id: number;
  code: string;
  name: string;
  type: GangRow['gangType'];
}

export interface AnnaCounts {
  received: number;
  confirmed: number;
  returned: number;
}

export interface AttendanceOverview {
  month: string;
  records: number;
  withSlip: number;
  lwpDays: number;
  exceptions: number;
  pastRetirement: number;
  overCalendar: number;
  anna: AnnaCounts;
  codes: { code: string; meaning: string }[];
  gangs: GangOption[];
}

export type GangStatus = 'READY' | 'WORK_SLIP_MISSING' | 'EXCEPTION' | 'NOT_MARKED';

export interface GangSummaryRow {
  gangId: number;
  gangCode: string;
  gangName: string;
  depot: string;
  labour: number;
  calendarDays: number;
  counts: Record<string, number>;
  presentDays: number;
  overCalendar: boolean;
  status: GangStatus;
}

export interface DayRow {
  employeeId: number;
  labourCode: string;
  name: string;
  gangId: number;
  gangCode: string;
  code: string | null;
  captureMode: string | null;
  depotConfirmed: boolean;
  workSlipFiled: boolean;
  sourceMode: 'MANUAL' | 'BIOMETRIC' | 'BOTH';
  punchIn: string | null;
  punchOut: string | null;
  exception: string | null;
  otherCode: string | null;
  otherGang: string | null;
  otherDepot: string | null;
  pastRetirement: boolean;
}

export interface LeaveRow {
  id: number;
  labourCode: string;
  name: string;
  origin: 'APPLICATION' | 'REGULARISATION' | 'CONVERSION';
  from: string;
  to: string;
  fromCode: string | null;
  toCode: string;
  backDated: boolean;
  raisedOn: string;
  status: string;
}

export interface SourceRow {
  depot: string;
  mode: 'MANUAL' | 'BIOMETRIC' | 'BOTH';
  annaDarpan: boolean;
  status: 'PILOT' | 'LIVE' | 'SUSPENDED';
  effectiveFrom: string;
  enrolled: number;
}

export interface SlipOverview {
  month: string;
  saved: number;
  rejected: number;
  /** DPS booking-slips of the month not yet approved (SRS-4-13). */
  pendingBooking: number;
  anna: AnnaCounts;
  gangs: GangOption[];
  dates: string[];
}

export interface RegisterRow {
  slipId: number;
  rowOrder: number;
  number: string;
  gangId: number;
  gangCode: string;
  date: string;
  bagType: string | null;
  operationCode: string | null;
  operation: string | null;
  bags: number | null;
  start: string | null;
  end: string | null;
  normType: string | null;
  continueWork: boolean;
  labours: number;
  status: string;
  source: string;
  duplicateOf: string | null;
  reason: string | null;
  enteredBy: string | null;
  decidedBy: string | null;
  decidedAt: string | null;
}

export interface OvertimeRow {
  key: string;
  labourCode: string;
  name: string;
  gangCode: string;
  pool: boolean;
  operationCode: string;
  operation: string;
  workingAs: string | null;
  start: string | null;
  end: string | null;
  hours: number | null;
  bags: number | null;
  minimumMet: 'Y' | 'N' | null;
  ancillary: boolean;
  pbnwReason: string | null;
}

/** The criteria of a list; an empty one is left out of the address. */
export interface CaptureFilter {
  payrollType: PayrollType;
  month?: string;
  gang?: number | null;
  date?: string;
  text?: string;
}

function query(f: CaptureFilter, page?: number, size?: number): string {
  const p = new URLSearchParams({ payrollType: f.payrollType });
  if (f.month) p.set('month', f.month);
  if (f.gang) p.set('gang', String(f.gang));
  if (f.date) p.set('date', f.date);
  if (f.text?.trim()) p.set('text', f.text.trim());
  if (page !== undefined) p.set('page', String(page));
  if (size !== undefined) p.set('size', String(size));
  return `?${p.toString()}`;
}

export const captureApi = {
  attendanceOverview: (f: CaptureFilter) =>
    api<AttendanceOverview>(`/v1/labour/attendance/overview${query({ ...f, text: undefined })}`),
  gangSummaries: (f: CaptureFilter, page: number, size: number) =>
    api<PageResult<GangSummaryRow>>(`/v1/labour/attendance/gang-summaries${query(f, page, size)}`),
  gangSummariesExportUrl: (f: CaptureFilter) =>
    `${API_BASE}/v1/labour/attendance/gang-summaries/export${query(f)}`,
  days: (f: CaptureFilter, page: number, size: number) =>
    api<PageResult<DayRow>>(`/v1/labour/attendance/days${query(f, page, size)}`),
  leave: (f: CaptureFilter, page: number, size: number) =>
    api<PageResult<LeaveRow>>(`/v1/labour/attendance/leave${query(f, page, size)}`),
  sources: (page: number, size: number) =>
    api<PageResult<SourceRow>>(`/v1/labour/attendance/sources?page=${page}&size=${size}`),
  slipOverview: (f: CaptureFilter) =>
    api<SlipOverview>(`/v1/labour/work-slips/overview${query({ ...f, text: undefined })}`),
  register: (f: CaptureFilter, page: number, size: number) =>
    api<PageResult<RegisterRow>>(`/v1/labour/work-slips${query(f, page, size)}`),
  registerExportUrl: (f: CaptureFilter) => `${API_BASE}/v1/labour/work-slips/export${query(f)}`,
  overtime: (f: CaptureFilter, page: number, size: number) =>
    api<PageResult<OvertimeRow>>(`/v1/labour/overtime${query({ ...f, date: undefined }, page, size)}`),
};
