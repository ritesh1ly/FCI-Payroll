import { describe, expect, it } from 'vitest';
import type { CaseDetail } from '@/modules/settlement/api/settlementApi';
import type { LeArrearRow, RunRequest } from '@/modules/leave/api/leaveArrearApi';
import {
  SETTLEMENT_COMPONENTS,
  STAGES,
  awaitsNoDues,
  componentAmount,
  componentSource,
  mayCompute,
  mayEnter,
  mayExclude,
  mayRelease,
  maySubmit,
  outstanding,
  stageNumber,
} from '@/modules/settlement/business/settlementRules';
import { LE_STATUSES, runReady, selectable, selectedTotal } from '@/modules/leave/business/leaveArrearRules';

/** O-05 settlement rules (plan 11; SRS-10-8, SRS-10-13, SRS-10-14, SRS-10-19). */
describe('O-05 settlement rules', () => {
  const detail = (stage: string, status: string, clearances: { type: string; status: string }[] = []) =>
    ({ summary: { stage, status }, clearances }) as unknown as CaseDetail;

  it('UC-06: the eight stages in order, numbered from one', () => {
    expect(STAGES).toHaveLength(8);
    expect(stageNumber('SEPARATION_RECEIVED')).toBe(1);
    expect(stageNumber('CLEARANCES_RECEIVED')).toBe(3);
    expect(stageNumber('COMPUTED')).toBe(4);
    expect(stageNumber('CLOSED')).toBe(8);
  });

  it('SRS-10-14: a clearance is outstanding while pending or raised', () => {
    expect(outstanding('PENDING')).toBe(true);
    expect(outstanding('RAISED')).toBe(true);
    for (const s of ['RECEIVED', 'NOT_APPLICABLE', 'EXCLUDED']) expect(outstanding(s)).toBe(false);
  });

  it('SRS-10-14 (US-GAP-218): the Maker computes at stages 2 - 4 while initiated or computed, a clearance outstanding or not', () => {
    expect(mayCompute(detail('CLEARANCES_RAISED', 'INITIATED', [{ type: 'X', status: 'RAISED' }]))).toBe(
      true,
    );
    expect(mayCompute(detail('CLEARANCES_RECEIVED', 'INITIATED'))).toBe(true);
    expect(mayCompute(detail('COMPUTED', 'COMPUTED'))).toBe(true);
    expect(mayCompute(detail('SEPARATION_RECEIVED', 'INITIATED'))).toBe(false);
    expect(mayCompute(detail('CPF_LINKED', 'COMPUTED'))).toBe(false);
    expect(mayCompute(detail('COMPUTED', 'PENDING_APPROVAL'))).toBe(false);
  });

  it('SRS-10-30 (Wave A review): never recomputed while the settlement waits for the Checker, even sent back to initiated', () => {
    const waits = (stage: string, status: string) =>
      ({ ...detail(stage, status), awaitingChecker: true }) as CaseDetail;
    expect(mayCompute(waits('COMPUTED', 'COMPUTED'))).toBe(false);
    expect(mayCompute(waits('CLEARANCES_RAISED', 'INITIATED'))).toBe(false);
    // The Maker's entries (ex-gratia, a recovery's source) stay open: a decision on one sends it back.
    expect(mayEnter(waits('COMPUTED', 'COMPUTED'))).toBe(true);
    expect(mayEnter(waits('CPF_LINKED', 'COMPUTED'))).toBe(false);
    expect(mayEnter(waits('COMPUTED', 'APPROVED'))).toBe(false);
    expect(
      mayCompute({ ...detail('CLEARANCES_RAISED', 'INITIATED'), awaitingChecker: false } as CaseDetail),
    ).toBe(true);
  });

  it('SRS-10-14 (US-GAP-218): a computed case is submitted with a clearance outstanding, once', () => {
    expect(maySubmit(detail('COMPUTED', 'COMPUTED', [{ type: 'X', status: 'RECEIVED' }]))).toBe(true);
    expect(maySubmit(detail('COMPUTED', 'COMPUTED', [{ type: 'X', status: 'RAISED' }]))).toBe(true);
    expect(maySubmit(detail('COMPUTED', 'INITIATED'))).toBe(false);
    const submitted = { ...detail('COMPUTED', 'COMPUTED'), awaitingChecker: true } as CaseDetail;
    expect(maySubmit(submitted)).toBe(false);
  });

  it('SRS-10-14 (US-GAP-219): the Checker excludes only an outstanding clearance of a computed settlement awaiting them', () => {
    const awaiting = (status: string, clearances: { type: string; status: string }[], waits = true) =>
      ({ ...detail('COMPUTED', status, clearances), awaitingChecker: waits }) as CaseDetail;
    expect(mayExclude(awaiting('COMPUTED', [{ type: 'X', status: 'RAISED' }]))).toBe(true);
    expect(mayExclude(awaiting('COMPUTED', [{ type: 'X', status: 'PENDING' }]))).toBe(true);
    expect(mayExclude(awaiting('COMPUTED', [{ type: 'X', status: 'RAISED' }], false))).toBe(false);
    expect(mayExclude(awaiting('INITIATED', [{ type: 'X', status: 'RAISED' }]))).toBe(false);
    expect(
      mayExclude(
        awaiting('COMPUTED', [
          { type: 'X', status: 'RECEIVED' },
          { type: 'Y', status: 'EXCLUDED' },
          { type: 'Z', status: 'NOT_APPLICABLE' },
        ]),
      ),
    ).toBe(false);
  });

  it('SRS-10-19: Finance releases an approved settlement; the no-dues is awaited while outstanding', () => {
    expect(mayRelease(detail('APPROVED', 'APPROVED'))).toBe(true);
    expect(mayRelease(detail('COMPUTED', 'COMPUTED'))).toBe(false);
    expect(
      awaitsNoDues(
        detail('CLEARANCES_RAISED', 'INITIATED', [{ type: 'ESTABLISHMENT_NO_DUES', status: 'RAISED' }]),
      ),
    ).toBe(true);
    expect(
      awaitsNoDues(
        detail('CLEARANCES_RAISED', 'INITIATED', [{ type: 'ESTABLISHMENT_NO_DUES', status: 'RECEIVED' }]),
      ),
    ).toBe(false);
    expect(
      awaitsNoDues(detail('CLEARANCES_RAISED', 'INITIATED', [{ type: 'LOANS', status: 'RAISED' }])),
    ).toBe(false);
  });
});

/** I-24 leave encashment arrear rules (plan 13 phase 13d; SRS-8-23, SRS-8-24). */
describe('SRS-10-12: settlement components with module, basis and channel (US-GAP-31)', () => {
  const d = {
    pendingSalary: 1,
    arrears: 2,
    leaveEncashment: 3,
    gratuity: 4,
    cpfAmount: 5,
    exGratia: 6,
    totalRecoveries: null,
  } as unknown as CaseDetail;

  it('SRS-10-12: the seven components of the FRS V0.5 §6.17 table, each with its amount', () => {
    expect(SETTLEMENT_COMPONENTS).toEqual([
      'PENDING_SALARY',
      'ARREARS',
      'LEAVE_ENCASHMENT',
      'GRATUITY',
      'CPF_FINAL',
      'EX_GRATIA',
      'RECOVERIES',
    ]);
    expect(SETTLEMENT_COMPONENTS.map((c) => componentAmount(d, c))).toEqual([1, 2, 3, 4, 5, 6, null]);
  });

  it('SRS-10-25: only the ex-gratia is entered; every other component is computed', () => {
    expect(componentSource('EX_GRATIA')).toBe('ENT');
    for (const c of SETTLEMENT_COMPONENTS.filter((x) => x !== 'EX_GRATIA'))
      expect(componentSource(c)).toBe('CALC');
  });
});

describe('I-24 leave encashment arrear rules', () => {
  const row = (id: number, status: string, arrear: number | null) =>
    ({ id, status, arrear }) as unknown as LeArrearRow;

  it('SRS-8-24: only a calculated record is selected; the total sums the selected arrears', () => {
    expect(LE_STATUSES).toHaveLength(7);
    expect(selectable(row(1, 'CALCULATED', 10))).toBe(true);
    expect(selectable(row(1, 'SUBMITTED', 10))).toBe(false);
    const rows = [row(1, 'CALCULATED', 100), row(2, 'CALCULATED', null), row(3, 'CALCULATED', 50)];
    expect(selectedTotal(rows, new Set([1, 2]))).toBe(100);
    expect(selectedTotal(rows, new Set([1, 3]))).toBe(150);
    expect(selectedTotal(rows, new Set())).toBe(0);
  });

  it('SRS-8-23: a run needs the order, an ISO effective date and a reason', () => {
    const ready: Partial<RunRequest> = { circularId: 1, revisionEffective: '2026-07-01', reason: 'DA order' };
    expect(runReady(ready)).toBe(true);
    expect(runReady({ ...ready, circularId: undefined })).toBe(false);
    expect(runReady({ ...ready, revisionEffective: '' })).toBe(false);
    expect(runReady({ ...ready, revisionEffective: '2026-7-01' })).toBe(false);
    expect(runReady({ ...ready, revisionEffective: 'x2026-07-01' })).toBe(false);
    expect(runReady({ ...ready, revisionEffective: '2026-07-01x' })).toBe(false);
    expect(runReady({ ...ready, reason: '  ' })).toBe(false);
    expect(runReady({ ...ready, reason: undefined })).toBe(false);
  });
});

describe('O-05 settlement rule edges', () => {
  const detail = (status: string, clearances: { type: string; status: string }[]) =>
    ({ summary: { stage: 'COMPUTED', status }, clearances }) as unknown as CaseDetail;

  it('UC-06: the stages in their exact order', () => {
    expect(STAGES).toEqual([
      'SEPARATION_RECEIVED',
      'CLEARANCES_RAISED',
      'CLEARANCES_RECEIVED',
      'COMPUTED',
      'CPF_LINKED',
      'APPROVED',
      'POSTED',
      'CLOSED',
    ]);
  });

  it('SRS-10-14: one outstanding clearance among received ones holds the no-dues, not the submission', () => {
    const mixed = [
      { type: 'LOANS', status: 'RECEIVED' },
      { type: 'ESTABLISHMENT_NO_DUES', status: 'RAISED' },
    ];
    expect(maySubmit(detail('COMPUTED', mixed))).toBe(true);
    expect(awaitsNoDues(detail('COMPUTED', mixed))).toBe(true);
    expect(
      awaitsNoDues(
        detail('COMPUTED', [
          { type: 'ESTABLISHMENT_NO_DUES', status: 'RAISED' },
          { type: 'LOANS', status: 'RAISED' },
        ]),
      ),
    ).toBe(true);
  });
});
