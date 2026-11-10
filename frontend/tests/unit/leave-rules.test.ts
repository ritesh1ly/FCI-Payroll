import { describe, expect, it } from 'vitest';
import {
  appliedInLabel,
  draftProblem,
  numeric,
  type ParameterDraft,
  payCodesOk,
  perType,
} from '@/modules/leave/business/leaveRules';

/** Plan 19 phase 19b: the M-20 form rules (the server checks each again). */

const draft = (over: Partial<ParameterDraft>): ParameterDraft => ({
  code: 'ENCASH_DIVISOR',
  leaveType: '',
  value: '30',
  effectiveFrom: '2026-11-01',
  reason: 'As in the current system',
  ...over,
});

describe('M-20 draft', () => {
  it('SRS-LV-14: each parameter takes its kind of value, with a date and a reason', () => {
    expect(draftProblem(draft({}))).toBeNull();
    expect(draftProblem(draft({ value: '0' }))).toBe('m20.problem.value');
    expect(draftProblem(draft({ code: 'NOPE' }))).toBe('m20.problem.parameter');
    expect(draftProblem(draft({ effectiveFrom: '01-11-2026' }))).toBe('m20.problem.date');
    expect(draftProblem(draft({ reason: '  ' }))).toBe('m20.problem.reason');
    expect(draftProblem(draft({ code: 'LWP_DIVISOR', value: 'CALENDAR_DAYS' }))).toBeNull();
    expect(draftProblem(draft({ code: 'LWP_DIVISOR', value: '30' }))).toBeNull();
    expect(draftProblem(draft({ code: 'LWP_DIVISOR', value: 'WORKING' }))).toBe('m20.problem.value');
    expect(draftProblem(draft({ code: 'INSERVICE_PER_YEAR_LIMIT', value: '0' }))).toBeNull();
    expect(draftProblem(draft({ code: 'INSERVICE_PER_YEAR_LIMIT', value: '' }))).toBe('m20.problem.value');
    expect(draftProblem(draft({ code: 'INSERVICE_PER_YEAR_LIMIT', value: '-1' }))).toBe('m20.problem.value');
    expect(draftProblem(draft({ code: 'ENCASH_PAY_CODES', value: '184,185' }))).toBeNull();
    expect(draftProblem(draft({ code: 'ENCASH_PAY_CODES', value: 'BASIC' }))).toBe('m20.problem.value');
    // US-GAP-298 (SRS-LV-15, BR-LEAVE-007): the components subject to LWP are marked on M-07.
    expect(draftProblem(draft({ code: 'LWP_COMPONENTS', value: '101+111' }))).toBe('m20.problem.parameter');
  });

  it('SRS-LV-27: a pay effect needs its leave type and one of the DDL effects', () => {
    expect(draftProblem(draft({ code: 'PAY_EFFECT', value: 'HALF_PAY' }))).toBe('m20.problem.leaveType');
    expect(draftProblem(draft({ code: 'PAY_EFFECT', leaveType: 'HPL', value: 'HALF_PAY' }))).toBeNull();
    expect(draftProblem(draft({ code: 'PAY_EFFECT', leaveType: 'HPL', value: 'HELD_UNDEFINED' }))).toBe(
      'm20.problem.value',
    );
  });

  it('pay codes: one to twenty of up to four digits', () => {
    expect(payCodesOk('101+111')).toBe(true);
    expect(payCodesOk(Array.from({ length: 20 }, () => '1').join(','))).toBe(true);
    expect(payCodesOk(Array.from({ length: 21 }, () => '1').join(','))).toBe(false);
    expect(payCodesOk('12345')).toBe(false);
    expect(payCodesOk('101,')).toBe(false);
  });

  it('the kinds that take a leave type and those that take a number', () => {
    expect(perType('DAYS')).toBe(true);
    expect(perType('PAY_EFFECT')).toBe(true);
    expect(perType('POSITIVE')).toBe(false);
    expect(numeric('POSITIVE', '30')).toBe(true);
    expect(numeric('DIVISOR', 'CALENDAR_DAYS')).toBe(false);
    expect(numeric('DIVISOR', '30')).toBe(true);
    expect(numeric('PAY_CODES', '101')).toBe(false);
    expect(numeric('DAYS', '5')).toBe(true);
  });
});

describe('SRS I-11 Applied in (US-GAP-59)', () => {
  it('SRS-LV-33: a cycle is shown as MM-YYYY; a dash until applied or for a malformed month', () => {
    expect(appliedInLabel('2026-10')).toBe('10-2026');
    expect(appliedInLabel('2027-01')).toBe('01-2027');
    expect(appliedInLabel(null)).toBe('—');
    expect(appliedInLabel('')).toBe('—');
    expect(appliedInLabel('202610')).toBe('—');
    expect(appliedInLabel('2026-1')).toBe('—');
    expect(appliedInLabel('x2026-10')).toBe('—');
    expect(appliedInLabel('2026-10x')).toBe('—');
  });
});
