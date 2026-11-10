/** C-13 Batch Jobs: the typed models of /api/v1/jobs (the backend's JobController views). */

export type JobStatus =
  | 'AWAITING_APPROVAL'
  | 'REJECTED'
  | 'PENDING'
  | 'PICKED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'STOPPED'
  | 'PAUSED'
  | 'ABANDONED';
/** The direct commands, and ABANDON: a proposal the checker decides (plan 05j). */
export type JobCommand = 'STOP' | 'PAUSE' | 'RESUME' | 'RESTART' | 'ABANDON';
/** A unit, a pay period YYYY-MM, a financial year 2026-27 or a quarter Q1-Q4 (Module 08). */
export type ParamKind = 'LOCATION' | 'PERIOD' | 'FIN_YEAR' | 'QUARTER';

/** Checker decisions (the standard workflow endpoint, as in W-01). */
export type Decision = 'APPROVE' | 'REJECT' | 'RETURN';

export interface ParamDef {
  name: string;
  kind: ParamKind;
  required: boolean;
}

export interface JobDef {
  type: string;
  params: ParamDef[];
  /** SRS-NF-11 (DR-17): the type's queue priority, the highest a requester may ask for. */
  priority?: number;
}

export interface LocationOption {
  id: number;
  code: string;
  label: string;
}

export interface JobTypes {
  types: JobDef[];
  currentPeriod: string;
  locations: LocationOption[];
}

export interface Job {
  id: number;
  jobType: string;
  params: Record<string, string>;
  priority: number;
  status: JobStatus;
  source: 'UI' | 'SCHEDULE';
  requestedBy: string;
  locationId: number | null;
  locationLabel: string | null;
  payPeriod: string | null;
  requestedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  durationSeconds: number | null;
  attemptNo: number;
  jobExecutionId: number | null;
  /** The approval_queue item of the maker-checker step (UI submissions). */
  approvalId: number | null;
  /** This session may approve or reject it (a checker of the unit who is not the maker). */
  canDecide: boolean;
  readCount: number;
  writeCount: number;
  skipCount: number;
  resultSummary: string | null;
  errorMessage: string | null;
  pendingCommand: string | null;
  allowedCommands: JobCommand[];
}

export interface Step {
  name: string;
  partition: boolean;
  status: string;
  readCount: number;
  writeCount: number;
  filterCount: number;
  readSkipCount: number;
  processSkipCount: number;
  writeSkipCount: number;
  commitCount: number;
  rollbackCount: number;
  plannedItems: number | null;
  startTime: string | null;
  endTime: string | null;
  exitCode: string;
  exitDescription: string;
}

export interface Attempt {
  jobExecutionId: number;
  status: string;
  exitCode: string;
  exitDescription: string;
  startTime: string | null;
  endTime: string | null;
  steps: Step[];
}

export interface Control {
  id: number;
  command: JobCommand;
  status: 'PENDING' | 'APPLIED' | 'REJECTED';
  requestedBy: string;
  requestedAt: string;
  reason: string;
  appliedBy: string | null;
  appliedAt: string | null;
  outcome: string | null;
}

export interface AuditEntry {
  id: number;
  event: string;
  actor: string;
  at: string;
  fromStatus: string | null;
  toStatus: string | null;
  jobExecutionId: number | null;
  detail: string | null;
}

/** The kill switch (GET /v1/jobs/halt): engaged false and the rest null while batch processing runs. */
export interface Halt {
  engaged: boolean;
  haltId: number | null;
  haltedBy: string | null;
  haltedAt: string | null;
  reason: string | null;
  releaseApprovalId: number | null;
  releaseRequestedBy: string | null;
  releaseReason: string | null;
}

export interface JobDetail {
  job: Job;
  attempts: Attempt[];
  controls: Control[];
  audit: AuditEntry[];
  skippedTotal: number;
}

export interface SkippedItem {
  id: number;
  jobExecutionId: number;
  step: string;
  phase: 'READ' | 'PROCESS' | 'WRITE';
  itemKey: string | null;
  errorClass: string;
  errorMessage: string | null;
  at: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export type SortField = 'id' | 'requestedAt' | 'startedAt' | 'finishedAt' | 'status' | 'jobType' | 'priority';

export interface JobFilter {
  status?: JobStatus | '';
  jobType?: string;
  from?: string;
  to?: string;
  requestedBy?: string;
  page: number;
  size: number;
  sort: SortField;
  direction: 'asc' | 'desc';
}
