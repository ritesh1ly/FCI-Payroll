import { describe, expect, it } from 'vitest';
import type { Job, JobCommand, JobDef } from '@/modules/jobs/api/types';
import {
  defaultPriority,
  PRIORITY_LEVELS,
  priorityChoices,
  ABANDON_AWAITING_APPROVAL,
  commandBlocker,
  commandEnabled,
  formatDuration,
  isLive,
  JOB_COMMANDS,
  jobAccess,
  LIVE_STATUSES,
  progress,
  validateParams,
} from '@/modules/jobs/business/jobRules';
import type { Me } from '@/shared/session';

/**
 * C-13 batch-job rules (pure; SRS-NF-2: long work runs as a background job whose progress the screen
 * shows): access from the permissions, commands, submit validation (JobCatalog.validate on the
 * server), formatting.
 */

const me = (permissions: string[]) => ({ permissions }) as unknown as Me;

describe('job guards', () => {
  it('derives view / submit / control from the C-13 permissions', () => {
    expect(jobAccess(me(['C-13:VIEW']))).toEqual({
      view: true,
      submit: false,
      control: false,
      halt: false,
    });
    expect(jobAccess(me(['C-13:VIEW', 'C-13:CREATE', 'C-13:EDIT']))).toEqual({
      view: true,
      submit: true,
      control: true,
      halt: false,
    });
    expect(jobAccess(me(['C-13:VIEW', 'C-13:HALT'])).halt).toBe(true);
    expect(jobAccess(undefined)).toEqual({ view: false, submit: false, control: false, halt: false });
  });

  it('enables a command only with permission, nothing pending, and the server allowing it', () => {
    const job = { allowedCommands: ['PAUSE', 'STOP'], pendingCommand: null } as Pick<
      Job,
      'allowedCommands' | 'pendingCommand'
    >;
    const all = { view: true, submit: true, control: true, halt: false };
    expect(commandEnabled(job, 'PAUSE', all)).toBe(true);
    expect(commandEnabled(job, 'RESUME', all)).toBe(false);
    expect(commandEnabled(job, 'PAUSE', { ...all, control: false })).toBe(false);
    expect(commandEnabled({ ...job, pendingCommand: 'PENDING' }, 'PAUSE', all)).toBe(false);
  });

  it('says why a command is not offered: permission first, then a command still pending, then the job state', () => {
    const job = { allowedCommands: ['PAUSE', 'STOP'], pendingCommand: null } as Pick<
      Job,
      'allowedCommands' | 'pendingCommand'
    >;
    const all = { view: true, submit: true, control: true, halt: false };
    const viewOnly = { ...all, control: false };
    expect(commandBlocker(job, 'PAUSE', all)).toBeNull();
    expect(commandBlocker(job, 'RESUME', all)).toBe('state');
    expect(commandBlocker({ ...job, pendingCommand: 'STOP' }, 'PAUSE', all)).toBe('pending');
    expect(commandBlocker({ ...job, pendingCommand: 'STOP' }, 'RESUME', all)).toBe('pending');
    expect(commandBlocker({ ...job, pendingCommand: 'STOP' }, 'PAUSE', viewOnly)).toBe('noPermission');
    expect(commandBlocker(job, 'PAUSE', viewOnly)).toBe('noPermission');
  });

  it('plan 05j: an abandon awaiting the checker blocks everything; a halt withholds Resume and Restart', () => {
    const all = { view: true, submit: true, control: true, halt: false };
    const failed = { allowedCommands: ['ABANDON'] as JobCommand[], pendingCommand: null };
    expect(commandBlocker(failed, 'RESTART', all, true)).toBe('halted');
    expect(commandBlocker(failed, 'RESUME', all, true)).toBe('halted');
    expect(commandBlocker(failed, 'RESTART', all, false)).toBe('state');
    expect(commandBlocker(failed, 'STOP', all, true)).toBe('state');
    expect(commandBlocker(failed, 'ABANDON', all, true)).toBeNull();
    const awaiting = { ...failed, pendingCommand: ABANDON_AWAITING_APPROVAL };
    expect(commandBlocker(awaiting, 'ABANDON', all)).toBe('abandonPending');
    expect(commandBlocker(awaiting, 'RESTART', all, true)).toBe('abandonPending');
    expect(commandEnabled(awaiting, 'ABANDON', all)).toBe(false);
    expect(commandBlocker(awaiting, 'ABANDON', { ...all, control: false })).toBe('noPermission');
  });

  it('offers the four job commands in their screen order', () => {
    expect(JOB_COMMANDS).toEqual(['PAUSE', 'RESUME', 'STOP', 'RESTART']);
  });

  it('SRS-NF-2: the screen follows a job until it ends: awaiting the checker, queued, picked or running', () => {
    expect(LIVE_STATUSES).toEqual(['AWAITING_APPROVAL', 'PENDING', 'PICKED', 'RUNNING']);
    for (const status of LIVE_STATUSES) expect(isLive(status)).toBe(true);
    // A paused job waits for a command; the others have ended.
    for (const status of ['REJECTED', 'COMPLETED', 'FAILED', 'STOPPED', 'PAUSED'] as const) {
      expect(isLive(status)).toBe(false);
    }
  });
});

describe('submit validation', () => {
  const arrear: JobDef = {
    type: 'DA_ARREAR',
    params: [
      { name: 'locationId', kind: 'LOCATION', required: true },
      { name: 'fromPeriod', kind: 'PERIOD', required: true },
      { name: 'toPeriod', kind: 'PERIOD', required: true },
    ],
  };

  it('requires every parameter', () => {
    expect(validateParams(arrear, {}, '2026-07')).toEqual({
      locationId: 'c13.error.required',
      fromPeriod: 'c13.error.required',
      toPeriod: 'c13.error.required',
    });
  });

  it('checks the period format, the current period and the range', () => {
    expect(
      validateParams(arrear, { locationId: '3', fromPeriod: '2026-13', toPeriod: '2026-08' }, '2026-07'),
    ).toEqual({
      fromPeriod: 'c13.error.period',
      toPeriod: 'c13.error.future',
    });
    expect(
      validateParams(arrear, { locationId: '3', fromPeriod: '2026-06', toPeriod: '2026-02' }, '2026-07'),
    ).toEqual({
      fromPeriod: 'c13.error.range',
    });
    expect(
      validateParams(arrear, { locationId: '3', fromPeriod: '2026-01', toPeriod: '2026-07' }, '2026-07'),
    ).toEqual({});
  });

  it('a period is YYYY-MM and nothing else (a typed date is refused, not taken as a later period)', () => {
    expect(
      validateParams(arrear, { locationId: '3', fromPeriod: '2026-05-01', toPeriod: 'x2026-06' }, '2026-07'),
    ).toEqual({ fromPeriod: 'c13.error.period', toPeriod: 'c13.error.period' });
  });

  it('a value is checked without the spaces around it; spaces alone are no value (the server strips)', () => {
    expect(
      validateParams(arrear, { locationId: '  ', fromPeriod: ' 2026-06', toPeriod: '2026-02' }, '2026-07'),
    ).toEqual({ locationId: 'c13.error.required', fromPeriod: 'c13.error.range' });
  });

  it('an optional parameter may be left empty', () => {
    const optional: JobDef = {
      type: 'DA_ARREAR',
      params: [
        { name: 'locationId', kind: 'LOCATION', required: false },
        { name: 'payPeriod', kind: 'PERIOD', required: false },
      ],
    };
    expect(validateParams(optional, {}, '2026-07')).toEqual({});
    expect(validateParams(optional, { payPeriod: '2026-08' }, '2026-07')).toEqual({
      payPeriod: 'c13.error.future',
    });
  });

  it('a one-month range is not reversed; a range is judged only between two valid periods', () => {
    expect(
      validateParams(arrear, { locationId: '3', fromPeriod: '2026-05', toPeriod: '2026-05' }, '2026-07'),
    ).toEqual({});
    // Only the invalid end is reported: no range error is added beside it.
    expect(
      validateParams(arrear, { locationId: '3', fromPeriod: '2026-13', toPeriod: '2026-02' }, '2026-07'),
    ).toEqual({ fromPeriod: 'c13.error.period' });
    expect(
      validateParams(arrear, { locationId: '3', fromPeriod: '2026-06', toPeriod: '2026-00' }, '2026-07'),
    ).toEqual({ toPeriod: 'c13.error.period' });
  });
});

describe('formatting', () => {
  it('formats durations and progress', () => {
    expect(formatDuration(null)).toBe('—');
    expect(formatDuration(0)).toBe('0s');
    // A negative duration (clocks of two instances apart) is shown as unknown.
    expect(formatDuration(-5)).toBe('—');
    expect(formatDuration(42)).toBe('42s');
    expect(formatDuration(125)).toBe('2m 05s');
    expect(formatDuration(3725)).toBe('1h 02m 05s');
    expect(progress({ status: 'STARTED', readCount: 50, plannedItems: 200 })).toBe(25);
    expect(progress({ status: 'COMPLETED', readCount: 3, plannedItems: null })).toBe(100);
    expect(progress({ status: 'STARTED', readCount: 3, plannedItems: null })).toBeNull();
  });
});

describe('SRS-NF-11 priority (DR-17)', () => {
  const def = (priority?: number): JobDef => ({ type: 'X', params: [], priority });

  it('SRS_NF_11_theTypeSetsTheDefaultAndOnlyLowerLevelsAreOffered', () => {
    expect(defaultPriority(def(2))).toBe(2);
    expect(priorityChoices(def(2))).toEqual([2, 1]);
    expect(priorityChoices(def(5))).toEqual([5, 3, 2, 1]);
    expect(priorityChoices(def(9))).toEqual(PRIORITY_LEVELS);
  });

  it('SRS_NF_11_aTypeLevelWithoutItsOwnStepIsStillFirst', () => {
    expect(priorityChoices(def(4))).toEqual([4, 3, 2, 1]);
    expect(priorityChoices(def(1))).toEqual([1]);
  });

  it('SRS_NF_11_noTypeOrNoPriorityFallsBackToTheTopLevel', () => {
    expect(defaultPriority(undefined)).toBe(9);
    expect(defaultPriority(def())).toBe(9);
    expect(priorityChoices(undefined)).toEqual(PRIORITY_LEVELS);
  });
});
