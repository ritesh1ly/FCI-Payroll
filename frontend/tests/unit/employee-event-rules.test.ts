import { describe, expect, it } from 'vitest';
import type { ReceivedEvent } from '@/modules/employee/api/registerApi';
import { eventDetails, eventEffect, eventSource, taskPill } from '@/modules/employee/business/eventRules';
import { copyPill, copyState, dobRule } from '@/modules/employee/business/hrmsChangeRules';

/** I-17 events received from HRMS (SRS-1-64) and I-21 order copy / DOB rule (SRS-1-22, SRS-1-23, SRS-G-31). */
describe('I-17 received event rules', () => {
  it('SRS-1-64: the I-21 task pill of each task status, grey with no task', () => {
    expect(taskPill('APPROVED')).toBe('p-ok');
    expect(taskPill('AUTO_APPLIED')).toBe('p-ok');
    expect(taskPill('REJECTED_RETURNED_TO_HRMS')).toBe('p-bad');
    expect(taskPill(null)).toBe('p-gray');
    expect(taskPill('PENDING_CHECKER')).toBe('p-warn');
    expect(taskPill('')).toBe('p-warn');
  });

  it('SRS-1-64: each event type with a pay effect names it; any other reads as none', () => {
    for (const t of [
      'SUSPENSION',
      'REVOCATION',
      'REINSTATEMENT',
      'ABSCONDING',
      'SALARY_RELEASE',
      'SALARY_STOP',
      'DISMISSAL',
      'SEPARATION',
    ])
      expect(eventEffect(t)).toBe(t);
    expect(eventEffect('TRANSFER')).toBe('NONE');
    expect(eventEffect('')).toBe('NONE');
  });

  const ev = (over: Partial<ReceivedEvent> = {}): ReceivedEvent =>
    ({
      subsistencePercent: null,
      interveningPeriod: null,
      releaseFrom: null,
      releaseTo: null,
      reason: null,
      source: 'HRMS_API',
      hrmsRequestId: null,
      ...over,
    }) as ReceivedEvent;

  it('SRS-1-64: the details show the subsistence percentage first, zero included', () => {
    expect(eventDetails(ev({ subsistencePercent: 50, interveningPeriod: 'LEAVE', reason: 'R' }))).toEqual({
      key: 'evt.w.detail.subsistence',
      args: [50],
    });
    expect(eventDetails(ev({ subsistencePercent: 0 }))).toEqual({
      key: 'evt.w.detail.subsistence',
      args: [0],
    });
  });

  it('SRS-1-64: then the intervening period treatment with its reason', () => {
    expect(eventDetails(ev({ interveningPeriod: 'DUTY', reason: 'Order 5', releaseFrom: '202601' }))).toEqual(
      {
        key: 'evt.w.detail.intervening',
        args: ['Order 5', 'DUTY'],
      },
    );
    expect(eventDetails(ev({ interveningPeriod: 'LEAVE' }))).toEqual({
      key: 'evt.w.detail.intervening',
      args: ['', 'LEAVE'],
    });
  });

  it('SRS-1-64: then the released months, one month when no end is given', () => {
    expect(eventDetails(ev({ releaseFrom: '202601', releaseTo: '202603', reason: 'x' }))).toEqual({
      key: 'evt.w.detail.released',
      args: ['202601', '202603'],
    });
    expect(eventDetails(ev({ releaseFrom: '202601' }))).toEqual({
      key: 'evt.w.detail.released',
      args: ['202601', '202601'],
    });
  });

  it('SRS-1-64: else the reason, a dash when none', () => {
    expect(eventDetails(ev({ reason: 'Misconduct' }))).toEqual({
      key: 'evt.w.detail.text',
      args: ['Misconduct'],
    });
    expect(eventDetails(ev())).toEqual({ key: 'evt.w.detail.text', args: ['—'] });
  });

  it('SRS-1-64 (X-07): the fallback upload names its request, the interface names itself', () => {
    expect(eventSource(ev({ source: 'HRMS_FALLBACK_UPLOAD', hrmsRequestId: 'REQ-9' }))).toEqual({
      key: 'evt.w.source.fallback',
      args: ['REQ-9'],
    });
    expect(eventSource(ev({ source: 'HRMS_FALLBACK_UPLOAD' }))).toEqual({
      key: 'evt.w.source.fallback',
      args: ['—'],
    });
    expect(eventSource(ev({ source: 'HRMS_API', hrmsRequestId: 'REQ-1' }))).toEqual({
      key: 'evt.w.source.HRMS_API',
      args: [],
    });
  });
});

describe('I-21 order copy and DOB rule', () => {
  const task = (over: Partial<Parameters<typeof copyState>[0]> = {}) => ({
    orderHeldInHrms: false,
    orderRef: null,
    orderNo: null,
    ...over,
  });

  it('SRS-G-31: held in HRMS first, then linked to a bulk order, then attached, else missing', () => {
    expect(copyState(task({ orderHeldInHrms: true, orderRef: 'B-1', orderNo: '7' }))).toBe('held');
    expect(copyState(task({ orderRef: 'B-1', orderNo: '7' }))).toBe('linked');
    expect(copyState(task({ orderNo: '7' }))).toBe('attached');
    expect(copyState(task())).toBe('missing');
    expect(copyState(task({ orderRef: '', orderNo: '' }))).toBe('missing');
  });

  it('SRS-G-31: the order copy pill: info when held, bad when missing, ok otherwise', () => {
    expect(copyPill(task({ orderHeldInHrms: true }))).toBe('p-info');
    expect(copyPill(task())).toBe('p-bad');
    expect(copyPill(task({ orderRef: 'B-1' }))).toBe('p-ok');
    expect(copyPill(task({ orderNo: '7' }))).toBe('p-ok');
  });

  it('SRS-1-22, SRS-1-23 (UC-04): the first DOB rule that applies', () => {
    expect(dobRule({ status: 'REJECTED_RETURNED_TO_HRMS', retirementPast: true, retiresSoon: true })).toBe(
      'rejected',
    );
    expect(dobRule({ status: 'PENDING_CHECKER', retirementPast: true, retiresSoon: true })).toBe('hq');
    expect(dobRule({ status: 'PENDING_CHECKER', retirementPast: false, retiresSoon: true })).toBe('soon');
    expect(dobRule({ status: 'PENDING_CHECKER', retirementPast: false, retiresSoon: false })).toBe('checker');
  });
});
