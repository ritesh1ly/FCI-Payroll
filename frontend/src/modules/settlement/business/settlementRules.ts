/**
 * O-05 screen rules (plan 11; SRS-10-8, SRS-10-14, SRS-10-13). The server checks each again; these
 * only decide what the screen offers.
 */
import type { CaseDetail, CaseRow, CaseStage } from '../api/settlementApi';

/** The UC-06 stages in order (DDL case_stage 1 - 8). */
export const STAGES: readonly CaseStage[] = [
  'SEPARATION_RECEIVED',
  'CLEARANCES_RAISED',
  'CLEARANCES_RECEIVED',
  'COMPUTED',
  'CPF_LINKED',
  'APPROVED',
  'POSTED',
  'CLOSED',
];

/** The 1-based number of a stage. */
export function stageNumber(stage: CaseStage): number {
  return STAGES.indexOf(stage) + 1;
}

/** SRS-10-14: a clearance still outstanding (not received, not applicable, not excluded). */
export function outstanding(status: string): boolean {
  return status === 'PENDING' || status === 'RAISED';
}

/**
 * What the Maker may do now: compute once the clearances are raised, submit once computed and not
 * already with the Checker. SRS-10-14 (US-GAP-218): an outstanding clearance holds neither; it
 * blocks only the Checker's approval. SRS-10-30 (Wave A review): never recomputed while the
 * settlement waits for the Checker - the item submitted stands for it until returned.
 */
export function mayCompute(d: CaseDetail): boolean {
  return mayEnter(d) && !d.awaitingChecker;
}

/**
 * The Maker's entries on a case (ex-gratia, the source of a recovery): at stages 2 - 4 while
 * initiated or computed. A decision on one sends a submitted settlement back for recomputation.
 */
export function mayEnter(d: CaseDetail): boolean {
  return mayEnterRow(d.summary);
}

/** {@link mayEnter} on a worklist row. */
export function mayEnterRow(r: Pick<CaseRow, 'stage' | 'status'>): boolean {
  const n = stageNumber(r.stage);
  return n >= 2 && n <= 4 && (r.status === 'INITIATED' || r.status === 'COMPUTED');
}

export function maySubmit(d: CaseDetail): boolean {
  return d.summary.status === 'COMPUTED' && !d.awaitingChecker;
}

/**
 * SRS-10-14 (US-GAP-219): the Payroll Checker records a named, reasoned exclusion of an outstanding
 * clearance while the computed settlement waits for their approval.
 */
export function mayExclude(d: CaseDetail): boolean {
  return (
    d.awaitingChecker && d.summary.status === 'COMPUTED' && d.clearances.some((c) => outstanding(c.status))
  );
}

/** SRS-10-19: Finance releases an approved settlement. */
export function mayRelease(d: CaseDetail): boolean {
  return d.summary.status === 'APPROVED';
}

/** The establishment no-dues is still awaited. */
export function awaitsNoDues(d: CaseDetail): boolean {
  return d.clearances.some((c) => c.type === 'ESTABLISHMENT_NO_DUES' && outstanding(c.status));
}

/**
 * SRS-10-12 (US-GAP-31): the settlement components in the order of the FRS V0.5 §6.17 Settlement
 * Components table, each shown with the module that computes it, its basis and its payment channel
 * (labels `o05.comp.<code>.by / .basis / .via`). The CPF final payment is listed for information
 * only, never part of the net payable.
 */
export const SETTLEMENT_COMPONENTS = [
  'PENDING_SALARY',
  'ARREARS',
  'LEAVE_ENCASHMENT',
  'GRATUITY',
  'CPF_FINAL',
  'EX_GRATIA',
  'RECOVERIES',
] as const;

export type SettlementComponent = (typeof SETTLEMENT_COMPONENTS)[number];

/** The amount of a component in a case; null when not computed. */
export function componentAmount(d: CaseDetail, c: SettlementComponent): number | null {
  switch (c) {
    case 'PENDING_SALARY':
      return d.pendingSalary;
    case 'ARREARS':
      return d.arrears;
    case 'LEAVE_ENCASHMENT':
      return d.leaveEncashment;
    case 'GRATUITY':
      return d.gratuity;
    case 'CPF_FINAL':
      return d.cpfAmount;
    case 'EX_GRATIA':
      return d.exGratia;
    case 'RECOVERIES':
      return d.totalRecoveries;
  }
}

/** A component the Maker enters (ex-gratia, from its sanction letter); every other is computed. */
export function componentSource(c: SettlementComponent): 'ENT' | 'CALC' {
  return c === 'EX_GRATIA' ? 'ENT' : 'CALC';
}

/** The recoveries adjusted against the payroll dues (SRS-10-13): the rest went to gratuity. */
export function payrollRecovered(total: number | null, fromGratuity: number | null): number {
  return (total ?? 0) - (fromGratuity ?? 0);
}

/** The account a recovery names: the stored key is "<id> <reference>" for a ledger account. */
export function recoveryReference(reference: string | null): string | null {
  return reference?.replace(/^\d+ /, '') ?? null;
}

/** What the drawn stage pill says (pack l.5828-5832; label `o05.stepText.<text>`). */
export interface Step {
  number: number;
  text: string;
  tone: 'p-ok' | 'p-warn' | 'p-bad';
}

/**
 * The UC-06 step of a case, as the worklist draws it (SRS-10-8): the DDL stage, read as step 6 while
 * the Checker or Headquarters decides. A net recovery is held at step 6 (SRS-10-6); a death in
 * service at the clearances names the nominee payee (SRS-10-20).
 */
export function stepOf(r: CaseRow): Step {
  const stage = stageNumber(r.stage);
  // UC-06 step 6 holds the Checker's approval and the Headquarters approval of a gratuity recovery.
  const number = r.awaitingChecker || r.hqApprovalPending ? 6 : stage;
  if (stage >= 7) return { number: stage, text: r.stage, tone: 'p-ok' };
  if (number === 6 && r.netPayable !== null && r.netPayable < 0) {
    return { number, text: r.hqApprovalPending ? 'NET_RECOVERY_HQ' : 'NET_RECOVERY', tone: 'p-bad' };
  }
  if (r.awaitingChecker) return { number, text: 'CHECKER', tone: 'p-warn' };
  if (r.separationType === 'DEATH' && stage <= 3) {
    return { number: 3, text: 'NOMINEE', tone: 'p-warn' };
  }
  return { number, text: r.stage, tone: 'p-warn' };
}

/** One row of the settlement statement as drawn (pack l.5855-5864); label `o05.st.<key>`. */
export interface StatementRow {
  key: 'PENDING' | 'LEAVE' | 'EX_GRATIA' | 'DUES' | 'NET' | 'GRATUITY' | 'CPF_FINAL';
  amount: number | null;
  recovery: number;
  strong: boolean;
}

/**
 * SRS-10-12, SRS-10-24: the statement rows. Gratuity stands apart from the payroll net payable and
 * carries the recoveries taken from it; the payroll dues carry the rest; the CPF final payment is for
 * information only. The statement reconciles to the payroll net payable plus gratuity.
 */
export function statementRows(d: CaseDetail): StatementRow[] {
  const pending = componentAmount(d, 'PENDING_SALARY');
  const arrears = componentAmount(d, 'ARREARS');
  const fromPayroll = payrollRecovered(d.totalRecoveries, d.recoveredFromGratuity);
  const plain = (key: StatementRow['key'], amount: number | null, recovery = 0): StatementRow => ({
    key,
    amount,
    recovery,
    strong: false,
  });
  return [
    plain('PENDING', pending === null && arrears === null ? null : (pending ?? 0) + (arrears ?? 0)),
    plain('LEAVE', componentAmount(d, 'LEAVE_ENCASHMENT')),
    plain('EX_GRATIA', componentAmount(d, 'EX_GRATIA')),
    { key: 'DUES', amount: d.summary.payrollDues, recovery: fromPayroll, strong: true },
    { key: 'NET', amount: d.summary.netPayable, recovery: 0, strong: true },
    plain('GRATUITY', componentAmount(d, 'GRATUITY'), d.recoveredFromGratuity ?? 0),
    plain('CPF_FINAL', componentAmount(d, 'CPF_FINAL')),
  ];
}
