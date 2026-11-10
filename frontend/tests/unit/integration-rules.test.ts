import { describe, expect, it } from 'vitest';
import type { InterfaceRow, QueueItem, Window } from '@/modules/integration/api/integrationApi';
import {
  ADMIN_ONLY,
  ageDays,
  balanced,
  configured,
  reprocessBlocked,
  statusTone,
  unresolved,
  windowReprocessBlocked,
  writeOffBlocked,
} from '@/modules/integration/business/integrationRules';

/**
 * X-03 Integration Monitor rules (plan 23 phase 23b; SRS-INT-16, SRS-INT-31 … SRS-INT-33), each
 * with its boundaries and the order of its refusals. The rules file's own test, so mutation testing
 * (vitest.mutation.config.ts runs only *rules* tests) reaches it.
 */

function row(over: Partial<InterfaceRow> = {}): InterfaceRow {
  return {
    code: 'HRMS_BULK_RECONCILE',
    name: 'HRMS bulk reconciliation load',
    counterparty: 'HRMS',
    direction: 'INBOUND',
    transport: null,
    endpointObject: null,
    stagingObject: null,
    consumingScreen: 'X-03',
    specSignedOff: false,
    scopeStatus: 'CONFIRMED',
    active: true,
    status: 'OK',
    lastExchangeAt: '2026-10-28T01:30:00Z',
    lastGoodExchangeAt: '2026-10-28T01:30:00Z',
    received: 10,
    accepted: 10,
    rejected: 0,
    openExceptions: 0,
    breakerState: 'CLOSED',
    adapter: false,
    settings: {
      retryCount: 2,
      backoffSeconds: 30,
      backoffStrategy: 'FIXED',
      timeoutSeconds: 30,
      circuitBreakerRequired: true,
      circuitBreakerThreshold: 5,
      ackTimeoutSeconds: 300,
      counterpartySla: 'Demo values',
      effectiveFrom: '2020-04-01',
      confirmed: false,
    },
    ...over,
  };
}

function item(over: Partial<QueueItem> = {}): QueueItem {
  return {
    id: 7,
    interfaceCode: 'HRMS_EMP_OTA',
    runId: 3,
    stagingId: 11,
    txnType: 'EMP_OTA',
    sourceKey: 'DEMO-OTA-0002',
    hrmsRequestId: 'DEMO-OTA-0002',
    reason: 'Overtime hours must be more than 0 (demo rejection)',
    status: 'OPEN',
    raisedAt: '2026-10-28T02:00:00Z',
    resolvedAt: null,
    writtenOffBy: null,
    writtenOffReason: null,
    reprocessedStagingId: null,
    adapter: true,
    ...over,
  };
}

describe('X-03 window status and balance (SRS-INT-32)', () => {
  it('SRS-INT-32: each window status has its pill; no status or an unknown one is informational', () => {
    expect(statusTone('OK')).toBe('p-ok');
    expect(statusTone('PARTIAL')).toBe('p-warn');
    expect(statusTone('FAILED')).toBe('p-bad');
    expect(statusTone(null)).toBe('p-info');
    expect(statusTone('')).toBe('p-info');
    expect(statusTone('ok')).toBe('p-info');
  });

  it('SRS-INT-32: received balances only when it equals accepted plus rejected, either way off', () => {
    expect(balanced({ received: 10, accepted: 8, rejected: 2 })).toBe(true);
    expect(balanced({ received: 0, accepted: 0, rejected: 0 })).toBe(true);
    expect(balanced({ received: 10, accepted: 7, rejected: 2 })).toBe(false);
    expect(balanced({ received: 10, accepted: 9, rejected: 2 })).toBe(false);
    // accepted - rejected would balance 10 = 12 - 2: the rule adds them.
    expect(balanced({ received: 10, accepted: 12, rejected: 2 })).toBe(false);
  });
});

describe('X-03 exception queue actions (SRS-INT-31, SRS-INT-33)', () => {
  it('SRS-INT-31: only an open or in-progress item still waits for the Administrator', () => {
    expect(unresolved(item())).toBe(true);
    expect(unresolved(item({ status: 'IN_PROGRESS' }))).toBe(true);
    expect(unresolved(item({ status: 'REPROCESSED' }))).toBe(false);
    expect(unresolved(item({ status: 'WRITTEN_OFF' }))).toBe(false);
    expect(unresolved(item({ status: '' }))).toBe(false);
  });

  it('SRS-INT-33: Reprocess is offered to the Administrator for an open item with an adapter and a staged record', () => {
    expect(ADMIN_ONLY).toBe('x03.reason.adminOnly');
    expect(reprocessBlocked(item(), true)).toBeNull();
    expect(reprocessBlocked(item({ status: 'IN_PROGRESS' }), true)).toBeNull();
    expect(reprocessBlocked(item({ stagingId: 0 }), true)).toBeNull();
  });

  it('SRS-INT-33: Reprocess names the first reason it is refused, the role before the state', () => {
    // Not the Administrator: the role, whatever else is wrong.
    expect(reprocessBlocked(item(), false)).toBe('x03.reason.adminOnly');
    expect(reprocessBlocked(item({ status: 'WRITTEN_OFF', adapter: false, stagingId: null }), false)).toBe(
      'x03.reason.adminOnly',
    );
    // Resolved before no adapter, no adapter before no staged record.
    expect(reprocessBlocked(item({ status: 'REPROCESSED', adapter: false, stagingId: null }), true)).toBe(
      'x03.why.resolved',
    );
    expect(reprocessBlocked(item({ adapter: false, stagingId: null }), true)).toBe('x03.why.noAdapter');
    expect(reprocessBlocked(item({ stagingId: null }), true)).toBe('x03.why.noRecord');
  });

  it("SRS-INT-33: Write off is the Administrator's for an unresolved item; a resolved one says so", () => {
    expect(writeOffBlocked(item(), true)).toBeNull();
    expect(
      writeOffBlocked(item({ status: 'IN_PROGRESS', adapter: false, stagingId: null }), true),
    ).toBeNull();
    expect(writeOffBlocked(item(), false)).toBe('x03.reason.adminOnly');
    expect(writeOffBlocked(item({ status: 'WRITTEN_OFF' }), false)).toBe('x03.reason.adminOnly');
    expect(writeOffBlocked(item({ status: 'WRITTEN_OFF' }), true)).toBe('x03.why.resolved');
    expect(writeOffBlocked(item({ status: 'REPROCESSED' }), true)).toBe('x03.why.resolved');
  });
});

describe('X-03 settings and the I-21 backlog (SRS-INT-16)', () => {
  it('SRS-INT-16: an interface is configured exactly when settings are in force', () => {
    expect(configured(row())).toBe(true);
    expect(configured(row({ settings: null }))).toBe(false);
  });

  it('X-03: the oldest I-21 change is aged in whole days; none is null; a future time is day 0', () => {
    const now = new Date('2026-10-28T12:00:00Z');
    expect(ageDays(null, now)).toBeNull();
    expect(ageDays('', now)).toBeNull();
    expect(ageDays('2026-10-28T12:00:00Z', now)).toBe(0);
    expect(ageDays('2026-10-27T12:00:00.001Z', now)).toBe(0);
    expect(ageDays('2026-10-27T12:00:00Z', now)).toBe(1);
    expect(ageDays('2026-10-25T11:00:00Z', now)).toBe(3);
    expect(ageDays('2026-10-28T12:00:00.001Z', now)).toBe(0);
    expect(ageDays('2026-10-29T00:00:00Z', now)).toBe(0);
  });
});

describe('X-03 Reprocess Failed per window (l.5172, SRS-INT-33)', () => {
  const window = (over: Partial<Window> = {}): Window => ({
    runId: 1,
    interfaceCode: 'HRMS',
    direction: 'IN',
    batchReference: null,
    windowStart: null,
    windowEnd: null,
    received: 5,
    accepted: 5,
    rejected: 0,
    status: 'COMPLETED',
    retryAttempt: 0,
    startedAt: '2026-11-04T00:00:00Z',
    completedAt: null,
    failureDetail: null,
    adapter: true,
    ...over,
  });

  it('SRS-INT-33: only the Administrator reprocesses, before any other reason', () => {
    expect(windowReprocessBlocked(window({ rejected: 2 }), false)).toBe(ADMIN_ONLY);
    expect(windowReprocessBlocked(window(), false)).toBe(ADMIN_ONLY);
  });

  it('SRS-INT-33: a window with rejected records or a failed run is offered; a clean one is not', () => {
    expect(windowReprocessBlocked(window(), true)).toBe('x03.why.nothingFailed');
    expect(windowReprocessBlocked(window({ rejected: 1 }), true)).toBeNull();
    expect(windowReprocessBlocked(window({ status: 'FAILED' }), true)).toBeNull();
    expect(windowReprocessBlocked(window({ rejected: 3, status: 'FAILED' }), true)).toBeNull();
  });

  it('SRS-INT-33: a window of an interface without an adapter is not offered', () => {
    expect(windowReprocessBlocked(window({ rejected: 1, adapter: false }), true)).toBe('x03.why.noAdapter');
    expect(windowReprocessBlocked(window({ status: 'FAILED', adapter: undefined }), true)).toBe(
      'x03.why.noAdapter',
    );
    // Nothing failed outranks the missing adapter.
    expect(windowReprocessBlocked(window({ adapter: false }), true)).toBe('x03.why.nothingFailed');
  });
});
