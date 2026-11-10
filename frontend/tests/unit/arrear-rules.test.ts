import { describe, expect, it } from 'vitest';
import type { EventRow } from '@/modules/arrear/api/arrearApi';
import type { DefinitionRequest, RevisionDetail } from '@/modules/arrear/api/wageRevisionApi';
import {
  STATUSES,
  cyclePeriod,
  mayAskHeadquarters,
  mayRecompute,
  maySubmit,
  mayUndo,
  month,
  remarkGiven,
} from '@/modules/arrear/business/arrearRules';
import {
  FITMENTS,
  ROUNDINGS,
  STAFF_TYPES,
  definitionReady,
  mayFix,
  maySimulate,
  maySubmit as revisionMaySubmit,
} from '@/modules/arrear/business/wageRevisionRules';

/** I-08 and I-25 screen rules (plan 13; SRS-8-13, SRS-8-14, SRS-8-25, SRS-21-9). */
describe('I-08 arrear rules', () => {
  const event = (status: string, singleMember = true, stated = false) =>
    ({ status, singleMember, stated }) as unknown as EventRow;

  it('SRS-21-6: only an approved event with its posting cycle is undone; never one posted or reversed', () => {
    const at = (status: string, postedToYymm: string | null) =>
      ({ status, postedToYymm }) as unknown as EventRow;
    expect(mayUndo(at('APPROVED', '202611'))).toBe(true);
    expect(mayUndo(at('APPROVED', null))).toBe(false);
    expect(mayUndo(at('APPROVED', '20261'))).toBe(false);
    for (const s of ['RAISED', 'COMPUTED', 'POSTED', 'REVERSED', 'REJECTED'])
      expect(mayUndo(at(s, '202611'))).toBe(false);
    expect(cyclePeriod('202611')).toBe('2026-11');
    expect(cyclePeriod('202601')).toBe('2026-01');
  });

  it('SRS-8-14: only a computed event goes for Approve & Merge', () => {
    expect(STATUSES).toEqual(['RAISED', 'COMPUTED', 'APPROVED', 'POSTED', 'REVERSED', 'REJECTED']);
    expect(maySubmit(event('COMPUTED'))).toBe(true);
    for (const s of ['RAISED', 'APPROVED', 'POSTED', 'REVERSED', 'REJECTED'])
      expect(maySubmit(event(s))).toBe(false);
  });

  it('SRS-8-13: a single-member event raised or computed is computed again; a bulk one never', () => {
    expect(mayRecompute(event('RAISED'))).toBe(true);
    expect(mayRecompute(event('COMPUTED'))).toBe(true);
    expect(mayRecompute(event('APPROVED'))).toBe(false);
    expect(mayRecompute(event('RAISED', false))).toBe(false);
    expect(mayRecompute(event('COMPUTED', false))).toBe(false);
    expect(mayRecompute(event('COMPUTED', true, true))).toBe(false);
    expect(mayRecompute(event('RAISED', true, true))).toBe(false);
  });

  it('SRS-8-6: an event beyond the back-date limit is computed only after the HQ exception', () => {
    const beyond = (status: string, hqException: string | null) =>
      ({ status, singleMember: true, stated: false, beyondWindow: true, hqException }) as unknown as EventRow;
    expect(mayRecompute(beyond('RAISED', null))).toBe(false);
    expect(mayRecompute(beyond('RAISED', 'PENDING'))).toBe(false);
    expect(mayRecompute(beyond('RAISED', 'APPROVED'))).toBe(true);
    expect(mayAskHeadquarters(beyond('RAISED', null))).toBe(true);
    expect(mayAskHeadquarters(beyond('RAISED', 'PENDING'))).toBe(false);
    expect(mayAskHeadquarters(beyond('RAISED', 'APPROVED'))).toBe(false);
    expect(mayAskHeadquarters(beyond('REJECTED', null))).toBe(false);
    expect(mayAskHeadquarters({ ...beyond('RAISED', null), beyondWindow: false })).toBe(false);
  });

  it('SRS-21-9: the remark is mandatory; a month shows as MM/YYYY or a dash', () => {
    expect(remarkGiven('Order 12')).toBe(true);
    expect(remarkGiven('   ')).toBe(false);
    expect(remarkGiven('')).toBe(false);
    expect(month('202609')).toBe('09/2026');
    expect(month(null)).toBe('—');
    expect(month('20269')).toBe('—');
    expect(month('2026091')).toBe('—');
  });
});

describe('I-25 wage revision rules', () => {
  const ready: Partial<DefinitionRequest> = {
    name: 'Pay revision',
    circularId: 3,
    payrollTypes: ['STAFF_IDA'],
    effectiveFrom: '2027-01-01',
    fitment: 'FACTOR',
    value: 1.1,
    rounding: 'RUPEE',
    reason: 'Order',
  };
  const detail = (status: string) => ({ summary: { status } }) as unknown as RevisionDetail;

  it('SRS-8-25 step 2: a definition needs every field; the minimum is not above the maximum', () => {
    expect(STAFF_TYPES).toEqual(['STAFF_IDA', 'STAFF_CDA']);
    expect(FITMENTS).toEqual(['FACTOR', 'PERCENTAGE', 'FIXED_AMOUNT']);
    expect(ROUNDINGS).toEqual(['RUPEE', 'NEXT_10', 'NEXT_100']);
    expect(definitionReady(ready)).toBe(true);
    expect(definitionReady({ ...ready, name: ' ' })).toBe(false);
    expect(definitionReady({ ...ready, name: undefined })).toBe(false);
    expect(definitionReady({ ...ready, circularId: undefined })).toBe(false);
    expect(definitionReady({ ...ready, payrollTypes: [] })).toBe(false);
    expect(definitionReady({ ...ready, payrollTypes: undefined })).toBe(false);
    expect(definitionReady({ ...ready, effectiveFrom: '' })).toBe(false);
    expect(definitionReady({ ...ready, fitment: undefined })).toBe(false);
    expect(definitionReady({ ...ready, value: undefined })).toBe(false);
    expect(definitionReady({ ...ready, value: 0 })).toBe(false);
    expect(definitionReady({ ...ready, rounding: undefined })).toBe(false);
    expect(definitionReady({ ...ready, reason: '  ' })).toBe(false);
    expect(definitionReady({ ...ready, reason: undefined })).toBe(false);
    expect(definitionReady({ ...ready, minimum: 500, maximum: 500 })).toBe(true);
    expect(definitionReady({ ...ready, minimum: 501, maximum: 500 })).toBe(false);
    expect(definitionReady({ ...ready, minimum: 501 })).toBe(true);
    expect(definitionReady({ ...ready, maximum: 1 })).toBe(true);
  });

  it('SRS-8-25 steps 3 - 5: simulate when defined or simulated, submit when simulated, fix when approved', () => {
    expect(maySimulate(detail('DEFINED'))).toBe(true);
    expect(maySimulate(detail('SIMULATED'))).toBe(true);
    expect(maySimulate(detail('PENDING_APPROVAL'))).toBe(false);
    expect(revisionMaySubmit(detail('SIMULATED'))).toBe(true);
    expect(revisionMaySubmit(detail('DEFINED'))).toBe(false);
    expect(mayFix(detail('APPROVED'))).toBe(true);
    expect(mayFix(detail('SIMULATED'))).toBe(false);
  });
});
