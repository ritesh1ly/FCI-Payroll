import { can, type Me } from '@/shared/session';
import type { Job, JobCommand, JobDef, JobStatus, Step } from '../api/types';

/** The direct commands (recorded at once); ABANDON is a proposal with its own button. */
export const JOB_COMMANDS: JobCommand[] = ['PAUSE', 'RESUME', 'STOP', 'RESTART'];

/** JobView.pendingCommand while an abandon awaits the checker (JobRequestService). */
export const ABANDON_AWAITING_APPROVAL = 'ABANDON_AWAITING_APPROVAL';

/** Resume and Restart start an attempt: withheld while batch processing is halted (plan 05j). */
export const STARTS_ATTEMPT: JobCommand[] = ['RESUME', 'RESTART'];

/** The priority levels the form offers (9 highest), each with a label c13.priority.<n>. */
export const PRIORITY_LEVELS = [9, 7, 5, 3, 2, 1];

/** The highest priority level (the Actual Run, the month-end close). */
const TOP_PRIORITY = 9;

/**
 * SRS-NF-11 (DR-17): the priorities a requester may choose for a job of this type: the type's own
 * (the default, first) and the lower levels; never a higher one (the server caps it too).
 */
export function priorityChoices(def: JobDef | undefined): number[] {
  const max = def?.priority ?? TOP_PRIORITY;
  return [max, ...PRIORITY_LEVELS.filter((p) => p < max)];
}

/** SRS-NF-11 (DR-17): the form's default priority for a job of this type: the type's own. */
export function defaultPriority(def: JobDef | undefined): number {
  return def?.priority ?? TOP_PRIORITY;
}

/**
 * States in which a job still changes: the screen polls while one is visible (including a job
 * awaiting the checker, so the maker sees it move to the queue).
 */
export const LIVE_STATUSES: JobStatus[] = ['AWAITING_APPROVAL', 'PENDING', 'PICKED', 'RUNNING'];

/** What this session may do on C-13 (UX only; the server checks every call). */
export interface JobAccess {
  view: boolean;
  submit: boolean;
  control: boolean;
  /** C-13 HALT: the kill switch (System Administrator; the server also asks for Headquarters). */
  halt: boolean;
}

export function jobAccess(me: Me | undefined): JobAccess {
  return {
    view: can(me, 'C-13', 'VIEW'),
    submit: can(me, 'C-13', 'CREATE'),
    control: can(me, 'C-13', 'EDIT'),
    halt: can(me, 'C-13', 'HALT'),
  };
}

/** A command is offered only if the session may control jobs and the server allows it now. */
export function commandEnabled(
  job: Pick<Job, 'allowedCommands' | 'pendingCommand'>,
  command: JobCommand,
  access: JobAccess,
): boolean {
  return access.control && job.pendingCommand === null && job.allowedCommands.includes(command);
}

export function isLive(status: JobStatus): boolean {
  return LIVE_STATUSES.includes(status);
}

const PERIOD = /^\d{4}-(0[1-9]|1[0-2])$/;
const FIN_YEAR = /^(\d{4})-(\d{2})$/;
const QUARTER = /^Q[1-4]$/;

/** The financial year (April to March) of a pay period YYYY-MM: 2026-04 and 2027-03 are 2026-27. */
export function finYearOf(period: string): string {
  const year = Number(period.slice(0, 4));
  const start = Number(period.slice(5, 7)) >= 4 ? year : year - 1;
  return `${start}-${String((start + 1) % 100).padStart(2, '0')}`;
}

/** The financial years a job may name: the current one and the ones before it, count in all. */
export function finYears(currentPeriod: string, count = 5): string[] {
  const start = Number(finYearOf(currentPeriod).slice(0, 4));
  return Array.from({ length: count }, (_, i) => {
    const s = start - i;
    return `${s}-${String((s + 1) % 100).padStart(2, '0')}`;
  });
}

/** A financial year 2026-27: the second part is the year after the first (the server's rule). */
function isFinYear(value: string): boolean {
  const m = FIN_YEAR.exec(value);
  return m !== null && Number(m[2]) === (Number(m[1]) + 1) % 100;
}

/**
 * Client-side validation of the submit form, mirroring the server's rules (JobCatalog.validate) so
 * that the user sees the problem before submitting: required parameters, YYYY-MM periods not after
 * the current period, and an arrear range that is not reversed. Every value is checked without the
 * spaces around it, as the server strips it. Returns the message keys per parameter.
 */
export function validateParams(
  def: JobDef,
  values: Record<string, string>,
  currentPeriod: string,
): Record<string, string> {
  const errors: Record<string, string> = {};
  const valueOf = (name: string) => (values[name] ?? '').trim();
  for (const param of def.params) {
    const value = valueOf(param.name);
    if (!value) {
      if (param.required) errors[param.name] = 'c13.error.required';
      continue;
    }
    if (param.kind === 'PERIOD') {
      if (!PERIOD.test(value)) errors[param.name] = 'c13.error.period';
      else if (value > currentPeriod) errors[param.name] = 'c13.error.future';
    }
    if (param.kind === 'FIN_YEAR') {
      if (!isFinYear(value)) errors[param.name] = 'c13.error.finYear';
      else if (value > finYearOf(currentPeriod)) errors[param.name] = 'c13.error.futureYear';
    }
    if (param.kind === 'QUARTER' && !QUARTER.test(value)) errors[param.name] = 'c13.error.quarter';
  }
  const from = valueOf('fromPeriod');
  const to = valueOf('toPeriod');
  if (!errors.fromPeriod && !errors.toPeriod && from && to && from > to)
    errors.fromPeriod = 'c13.error.range';
  return errors;
}

/** "1h 02m 05s" style duration of a run. */
export function formatDuration(seconds: number | null): string {
  if (seconds === null || seconds < 0) return '—';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  if (h > 0) return `${h}h ${String(m).padStart(2, '0')}m ${String(s).padStart(2, '0')}s`;
  if (m > 0) return `${m}m ${String(s).padStart(2, '0')}s`;
  return `${s}s`;
}

/** Progress of a step from its planned items (partitions), or null when unknown. */
export function progress(step: Pick<Step, 'readCount' | 'plannedItems' | 'status'>): number | null {
  if (step.status === 'COMPLETED') return 100;
  if (!step.plannedItems) return null;
  return Math.min(100, Math.round((step.readCount / step.plannedItems) * 100));
}

/**
 * Why a command is not offered now: no permission, an abandon awaiting the checker, another command
 * pending, batch processing halted (Resume / Restart), or the job's state.
 */
export type CommandBlocker = 'noPermission' | 'abandonPending' | 'pending' | 'halted' | 'state';

export function commandBlocker(
  job: Pick<Job, 'allowedCommands' | 'pendingCommand'>,
  command: JobCommand,
  access: JobAccess,
  halted = false,
): CommandBlocker | null {
  if (!access.control) return 'noPermission';
  if (job.pendingCommand === ABANDON_AWAITING_APPROVAL) return 'abandonPending';
  if (job.pendingCommand) return 'pending';
  if (!job.allowedCommands.includes(command)) {
    return halted && STARTS_ATTEMPT.includes(command) ? 'halted' : 'state';
  }
  return null;
}
