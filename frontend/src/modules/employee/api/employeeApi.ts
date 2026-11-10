import { type PageResult } from '@/shared/ui';
import { api } from '@/shared/api';

/** M-01 list row. */
export interface EmployeeRow {
  eis: string;
  name: string;
  designation: string | null;
  band: string | null;
  location: string | null;
  payPattern: string | null;
  employeeType: string;
  status: string;
  /** Wireframes v6.1 M-01 columns (OD-121). The drawn PAN column has no data: the register carries no PAN. */
  payScale: string | null;
  basic: number | null;
  city: string | null;
  cpfCode: string | null;
  bank: string | null;
}

/** M-01 dossier and E-01 own record (personal data masked by the server). */
export interface Detail {
  eis: string;
  firstName: string;
  lastName: string | null;
  designation: string | null;
  band: string | null;
  location: string | null;
  locationCode: string | null;
  payPattern: string | null;
  employeeType: string;
  providentFundType: string | null;
  panMasked: string | null;
  cpfCode: string | null;
  mobileNumber: string | null;
  emailAddress: string | null;
  bankAccountMasked: string | null;
  currentBasic: number | null;
  status: string;
  /** IN_SERVICE, SUSPENDED, SEPARATED … (set by approved I-17 events; EMPLOYMENT_STATUS list). */
  employmentStatus: string;
  changePending: boolean;
  /** SRS-13-1 (US-GAP-51): Calculated by I-05; null when none is on record. */
  nextIncrementDue: string | null;
  /** SRS-1-52 (US-GAP-40): HRMS-sourced, read-only. */
  birthDate?: string | null;
  joiningDate?: string | null;
  retirementDate?: string | null;
  gender?: string | null;
  employeeCategory?: string | null;
  cdaStaffCode?: string | null;
  /** SRS-1-54: the last four digits only. */
  aadhaarMasked?: string | null;
  pranNumber?: string | null;
  /** SRS-6-51: whether a UAN is on record (the number is not shown). */
  uanOnRecord?: boolean;
  /** SRS-1-76: EPS number, date of joining, EPFO Member ID and the EPS details. */
  epsNumber?: string | null;
  epsJoinDate?: string | null;
  epfoMemberId?: string | null;
  epsPre2014Member?: boolean | null;
  epsEntitlementDate?: string | null;
  epsSeparationReason?: string | null;
  epsSeparationDate?: string | null;
  /** M-01 Control: Hold and Stop-Payment (Enterable), Absconding and Casual Labour (HRMS). */
  holdFlag?: boolean;
  abscondingFlag?: boolean;
  stopPaymentFlag?: boolean;
  casualLabour?: boolean;
  /** Derived from the place of posting and the rule engine's rates in force today. */
  hraCityClass?: string | null;
  daRateApplied?: number | null;
  hraRateApplied?: number | null;
  /** M-01 Pay Status (SRS l.3738, US-GAP-275): System, derived from the employment status and Hold. */
  payStatus?: string | null;
  /**
   * SRS-G-53 (NEW-G1-07): set when the session reads the record as the previous unit: the date of
   * the transfer out of it. The record is then read-only for this session.
   */
  transferredOut?: string | null;
}

/** R-01 head count of one location, by record status. */
export interface LocationCount {
  location: string;
  locationCode: string;
  byStatus: Record<string, number>;
  total: number;
}

/** R-01 one page of the offices, with the totals over all of them (X-pagination). */
export interface HeadCountPage extends PageResult<LocationCount> {
  totals: Record<string, number>;
  grandTotal: number;
}

/** The M-01 filters, applied by the server (SRS-1-11); an empty value filters nothing. */
export interface EmployeeFilter {
  q: string;
  payPattern: string;
  status: string;
  /** org_location code: one office of the scope and those below it. */
  office?: string;
}

/** The M-01 tiles over the whole filtered scope (GET /v1/employees/counts). */
export interface StatusCounts {
  total: number;
  payrollSetupPending: number;
  changePending: number;
  panCpfMissing: number;
  bankInvalid: number;
  active: number;
}

function filterQuery(filter: EmployeeFilter): string {
  return (['q', 'payPattern', 'status', 'office'] as const)
    .filter((k) => filter[k])
    .map((k) => `${k}=${encodeURIComponent(filter[k] ?? '')}&`)
    .join('');
}

/** The only place the employee module talks to /api/v1/employees, /eseva and /mis. */
export const employeeApi = {
  list: (filter: EmployeeFilter, page: number, size: number) =>
    api<PageResult<EmployeeRow>>(`/v1/employees?${filterQuery(filter)}page=${page}&size=${size}`),
  counts: (filter: EmployeeFilter) => api<StatusCounts>(`/v1/employees/counts?${filterQuery(filter)}`),
  detail: (eis: string) => api<Detail>(`/v1/employees/${encodeURIComponent(eis)}`),
  ownRecord: () => api<Detail>('/v1/eseva/me'),
  /** SRS-1-11: a record in Payroll Setup Pending submitted to the Payroll Checker. */
  submitOnboarding: (eis: string, body: { cdaStaffCode: string | null; reason: string }) =>
    api<{ approvalId: number; status: string }>(
      `/v1/employees/${encodeURIComponent(eis)}/onboarding-submissions`,
      { method: 'POST', body },
    ),
  headCount: (page: number, size: number) =>
    api<HeadCountPage>(`/v1/mis/headcount?page=${page}&size=${size}`),
};
