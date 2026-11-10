'use client';

import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { can, periodLabel, useMe } from '@/shared/session';
import { Card, date, dateTime, Problem, ReasonDialog, Src, useToast, withBase } from '@/shared/ui';
import type { PreRunCheck } from '../api/cycleApi';
import {
  correctionBlocked,
  returnable,
  type ReturnInput,
  runningRunNo,
  unfreezable,
} from '../business/cycleRules';
import {
  type CycleActionName,
  useCycle,
  useCycleAction,
  usePreRunChecks,
} from '../orchestration/cycleQueries';
import { ComputationSections } from './ComputationSections';
import { ReturnDialog } from './ReturnDialog';

const STEPS = ['INITIATED', 'FROZEN', 'COMPUTED', 'VERIFIED', 'LOCKED'] as const;
const INR = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 2, minimumFractionDigits: 2 });
/** The address of the open Cycle actions section: D-01 links to it, and a reload keeps it open. */
const BOARD_HASH = '#c02-cycle';

/**
 * C-02 Employee / Staff Payroll Computation: the payroll cycle of the session's cycle unit for the
 * current period (target DDL v4.0), forward only: initiate, freeze (OTP), compute (a C-13 job),
 * verify (checker, not whoever froze or computed), lock (maker request, checker approval with OTP).
 * Shows the runs and the results of the run of record (or of the latest dry run). SRS-15-28: while
 * another action holds the instance's lease, the holder and since when are shown and the actions are
 * disabled naming it; the Maker may ask the X-01 authority to force-release a stuck lease.
 * SRS-15-30 / SRS-15-31 (phase 05d): the Checker returns a computed or verified cycle with a scope
 * and a reason (and withdraws a return raised in error); the Maker asks for an un-freeze before
 * lock, decided by route C02_UNFREEZE with OTP.
 * C-02 l.3686-3692: the Pay Pattern selector (IDA / CDA / Both) and each member's Basic, DA and HRA
 * city class; the Payroll Period and the Organisational Scope are the session's, chosen in the top
 * bar (SRS-G-29, OD-72). C-08 (SRS l.1643) is X-05 filtered to this period's run: linked here.
 * Wireframes v6.1 (OD-121): the screen opens on ComputationSections as drawn; the pack draws the cycle
 * actions on C-01 and D-01, so until C-01 is rebuilt every action above stays here, unchanged, in the closed
 * section "Cycle actions" (WF list; PENDING X08-1), opened by a lease, an open return or D-01's link.
 */
export function PayrollCycle() {
  const { t } = useI18n();
  const toast = useToast();
  const router = useRouter();
  const me = useMe();
  const period = me.data?.period ?? '';
  const cycle = useCycle(period);
  const action = useCycleAction(period);
  const [boardOpen, setBoardOpen] = useState(false);
  const [askLock, setAskLock] = useState(false);
  const [askRelease, setAskRelease] = useState(false);
  const [askReturn, setAskReturn] = useState(false);
  const [askWithdraw, setAskWithdraw] = useState(false);
  const [askUnfreeze, setAskUnfreeze] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const c = cycle.data;
  const status = c?.status ?? null;
  // SRS-15-23: before the instance exists, the pre-run checks P1-P5 keep Initialise disabled until clear.
  const preRun = usePreRunChecks(period, cycle.isSuccess && !status);
  const preRunFailing = (preRun.data ?? []).filter((p) => p.status === 'FAIL').map((p) => p.code);
  const preRunBlock =
    !status && preRun.data === undefined
      ? t('cyc.preRun.pending')
      : preRunFailing.length > 0
        ? t('error.CYC-409-PRE-RUN', [preRunFailing.join(', ')])
        : null;

  async function run(
    name: CycleActionName,
    reason?: string,
    extra: { returnInput?: ReturnInput; returnId?: number } = {},
  ) {
    setError(null);
    try {
      await action.mutateAsync({ action: name, reason, ...extra });
      toast(t(`cyc.done.${name}`));
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  const lockPending = c?.lockApprovalStatus === 'PENDING_APPROVAL';
  // SRS §6.7 / BR-CYC-004: Headquarters never locks a unit's payroll (the server refuses it too).
  const hqLock = me.data?.atHeadquarters === true ? t('error.AUTHZ-403-HQ-LOCK') : null;
  // SRS-15-28 / BR-CYC-015: another action holds the instance (an expired lease holds nothing: the
  // next action takes it over). The server refuses the same way (CYC-409-LEASE).
  const lease = c?.lease ?? null;
  const leaseArgs = lease
    ? [t(`cyc.leaseAction.${lease.action}`), lease.heldBy, dateTime(lease.acquiredAt)]
    : [];
  const held = lease !== null && !lease.expired ? t('error.CYC-409-LEASE', leaseArgs) : null;
  const releasePending = c?.leaseReleaseApprovalId != null;
  // SRS-15-30 / SRS-15-31: a return or un-freeze waits for a run, a lock request or another
  // un-freeze request to end (the server refuses the same way).
  const unfreezePending = c?.unfreezeApprovalId != null;
  const openReturn = c?.openReturn ?? null;
  // Someone needs these actions now: a lease is held, a return is open, or D-01 sent the user here.
  const needsBoard = c?.lease != null || c?.openReturn != null;
  useEffect(() => {
    if (needsBoard || window.location.hash === BOARD_HASH) setBoardOpen(true);
  }, [needsBoard]);
  const blockedKey = correctionBlocked({ held: held !== null, lockPending, unfreezePending });
  const blocked = blockedKey === 'held' ? held : blockedKey ? t(blockedKey) : null;
  // SRS-15-28 / l.1734: Verify waits for the lease and for a re-computation still running (it would
  // supersede the results just verified); the server refuses the same way (CYC-409-RUNNING).
  const runningNo = runningRunNo(c?.runs ?? []);
  const verifyBlock = held ?? (runningNo !== null ? t('error.CYC-409-RUNNING', [runningNo]) : null);
  const buttons = [
    {
      key: 'initiate',
      show: !status && can(me.data, 'C-02', 'CREATE'),
      disabled: !c?.inCalendar || preRunBlock !== null,
      why: preRunBlock,
    },
    {
      key: 'freeze',
      show: status === 'INITIATED' && can(me.data, 'C-02', 'SUBMIT'),
      disabled: held !== null,
      why: held,
    },
    {
      key: 'compute',
      show: (status === 'FROZEN' || status === 'COMPUTED') && can(me.data, 'C-13', 'CREATE'),
      disabled: held !== null,
      why: held,
    },
    {
      key: 'verify',
      show: status === 'COMPUTED' && can(me.data, 'C-02', 'APPROVE'),
      disabled: verifyBlock !== null,
      why: verifyBlock,
    },
    {
      key: 'lock',
      show: status === 'VERIFIED' && can(me.data, 'C-02', 'LOCK'),
      disabled: lockPending || hqLock !== null || held !== null,
      // Disabled with its reason (R-UI): a lock request already waiting says so (SRS-G-1, SRS-15-32).
      why: hqLock ?? held ?? (lockPending ? t('cyc.blocked.lockPending') : null),
    },
    {
      key: 'leaseRelease',
      show: held !== null && can(me.data, 'C-02', 'LEASE_RELEASE'),
      disabled: releasePending,
      why: releasePending ? t('cyc.leaseReleasePending') : null,
    },
    {
      key: 'return',
      show: returnable(status) && can(me.data, 'C-02', 'RETURN'),
      disabled: blocked !== null,
      why: blocked,
    },
    {
      key: 'withdraw',
      show: status === 'FROZEN' && openReturn !== null && can(me.data, 'C-02', 'RETURN'),
      disabled: held !== null,
      why: held,
    },
    {
      key: 'unfreeze',
      show: unfreezable(status) && can(me.data, 'C-02', 'UNFREEZE'),
      disabled: blocked !== null,
      why: blocked,
    },
  ] as const;

  return (
    <div className="wireframe-body-container">
      <ComputationSections period={period} cycleStatus={status} />
      <Problem error={cycle.error ?? error} />
      <details
        id="c02-cycle"
        className="card"
        open={boardOpen}
        onToggle={(e) => {
          const open = e.currentTarget.open;
          setBoardOpen(open);
          const { pathname, search } = window.location;
          window.history.replaceState(
            window.history.state,
            '',
            `${pathname}${search}${open ? BOARD_HASH : ''}`,
          );
        }}
      >
        <summary className="sub2">{t('c02.board')}</summary>
        <div className="toolbar">
          <span className="sub2">{t('cyc.hint')}</span>
        </div>
        {c && (
          <Card title={t('cyc.title', [periodLabel(c.period), c.unitLabel])}>
            <div className="bd">
              {lease && (
                <p className="fl-banner" role="note" data-testid="cycle-lease">
                  {t(lease.expired ? 'cyc.leaseExpired' : 'cyc.leaseHeld', leaseArgs)}
                  {releasePending && <span className="pill p-info">{t('cyc.leaseReleasePending')}</span>}
                </p>
              )}
              {openReturn && (
                <p className="fl-banner" role="note" data-testid="cycle-return">
                  {t('cyc.returned', [
                    t(`cyc.state.${openReturn.fromStatus}`),
                    openReturn.returnedBy,
                    dateTime(openReturn.returnedAt),
                    openReturn.reason,
                  ])}{' '}
                  {openReturn.scope === 'INSTANCE'
                    ? t('cyc.returnScope.INSTANCE')
                    : openReturn.scope === 'ORG_UNIT'
                      ? t('cyc.returnedOffice', [openReturn.orgUnitCode ?? ''])
                      : t('cyc.returnedMembers', [openReturn.members.join(', ')])}
                  {' · '}
                  {t('cyc.recomputeHint')}
                </p>
              )}
              {!status && preRun.data && <PreRunPanel checks={preRun.data} />}
              <Problem error={preRun.error} />
              <div className="steps" role="list" aria-label={t('cyc.states')}>
                {STEPS.map((s) => {
                  const reached =
                    status !== null && STEPS.indexOf(s) <= STEPS.indexOf(status as (typeof STEPS)[number]);
                  return (
                    <span
                      key={s}
                      role="listitem"
                      className={`pill ${reached ? 'p-ok' : 'p-info'}`}
                      aria-current={s === status ? 'step' : undefined}
                    >
                      {t(`cyc.state.${s}`)}
                    </span>
                  );
                })}
              </div>
              <div className="kv">
                <span className="k">{t('cyc.status')}</span>
                <span>{status ? t(`cyc.state.${status}`) : t('cyc.notInitiated')}</span>
                <span className="k">{t('cyc.window')}</span>
                <span>
                  {c.inCalendar
                    ? `${t('cyc.computeBy')} ${date(c.computeBy)} · ${t('cyc.lockBy')} ${date(c.lockTarget)}`
                    : t('cyc.notInCalendar')}
                </span>
                {c.initiatedBy && (
                  <>
                    <span className="k">{t('cyc.state.INITIATED')}</span>
                    <span>{`${c.initiatedBy} · ${dateTime(c.initiatedAt)}`}</span>
                  </>
                )}
                {c.frozenBy && (
                  <>
                    <span className="k">{t('cyc.state.FROZEN')}</span>
                    <span>{`${c.frozenBy} · ${dateTime(c.frozenAt)}`}</span>
                  </>
                )}
                {c.verifiedBy && (
                  <>
                    <span className="k">{t('cyc.state.VERIFIED')}</span>
                    <span>{`${c.verifiedBy} · ${dateTime(c.verifiedAt)}`}</span>
                  </>
                )}
                {c.lockedBy && (
                  <>
                    <span className="k">{t('cyc.state.LOCKED')}</span>
                    <span>{`${c.lockedBy} · ${dateTime(c.lockedAt)}`}</span>
                  </>
                )}
                {c.lockApprovalStatus && (
                  <>
                    <span className="k">{t('cyc.lockRequest')}</span>
                    <span>{t(`status.${c.lockApprovalStatus}`)}</span>
                  </>
                )}
                {releasePending && (
                  <>
                    <span className="k">{t('cyc.leaseRelease')}</span>
                    <span>{t('status.PENDING_APPROVAL')}</span>
                  </>
                )}
                {unfreezePending && (
                  <>
                    <span className="k">{t('cyc.unfreezeRequest')}</span>
                    <span>{t('status.PENDING_APPROVAL')}</span>
                  </>
                )}
                {c.id !== null && (
                  <>
                    <span className="k">{t('cyc.postedToEbs')}</span>
                    <span>{t(c.postedToEbs ? 'common.yes' : 'common.no')}</span>
                    <span className="k">{t('cyc.supplementaryOpen')}</span>
                    <span>{t(c.supplementaryOpen ? 'common.yes' : 'common.no')}</span>
                  </>
                )}
              </div>
              <div className="rf-actbar">
                {buttons
                  .filter((b) => b.show)
                  .map((b) => (
                    <button
                      key={b.key}
                      type="button"
                      className="btn pri"
                      disabled={b.disabled || action.isPending}
                      title={b.why ?? undefined}
                      onClick={() => {
                        if (b.key === 'compute') router.push('/payroll?s=C-13');
                        else if (b.key === 'lock') setAskLock(true);
                        else if (b.key === 'leaseRelease') setAskRelease(true);
                        else if (b.key === 'return') setAskReturn(true);
                        else if (b.key === 'withdraw') setAskWithdraw(true);
                        else if (b.key === 'unfreeze') setAskUnfreeze(true);
                        else void run(b.key);
                      }}
                    >
                      {t(`cyc.action.${b.key}`)}
                    </button>
                  ))}
              </div>
              {status === 'FROZEN' && <p className="fl-hint">{t('cyc.computeHint')}</p>}
              {!status && <p className="fl-hint">{t('cyc.dryRunHint')}</p>}
            </div>
          </Card>
        )}
        {c && c.runs.length > 0 && (
          <Card title={t('cyc.runs')}>
            <div className="wrap">
              <table className="t">
                <thead>
                  <tr>
                    <th>
                      {t('cyc.run')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('cyc.runStatus')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('cyc.startedBy')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('cyc.results')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('cyc.units')}
                      <Src s="SYS" />
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {c.runs.map((r) => (
                    <tr key={r.id}>
                      <td>
                        <b>
                          {t(`cyc.runType.${r.type}`)} #{r.runNo}
                        </b>
                        {r.id === c.currentRunId && <span className="pill p-ok">{t('cyc.ofRecord')}</span>}
                        <div className="sub2">{t('cyc.runId', [r.id])}</div>
                      </td>
                      <td>{t(`cyc.runState.${r.status}`)}</td>
                      <td>
                        {r.startedBy}
                        <div className="sub2">{dateTime(r.startedAt)}</div>
                      </td>
                      <td>
                        {r.results}
                        {r.eligible !== null ? ` / ${r.eligible}` : ''}
                      </td>
                      <td>
                        {r.unitsCommitted}/{r.units ?? 0}
                        {r.unitsFailed > 0 ? ` · ${t('cyc.unitsFailed', [r.unitsFailed])}` : ''}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>
        )}
        {c?.resultsRunId && (
          <Card
            title={t(c.resultsRunType === 'ACTUAL' ? 'cyc.resultsActual' : 'cyc.resultsSimulation', [
              c.resultsRunId,
            ])}
          >
            <div className="bd kv">
              <span className="k">{t('cyc.members')}</span>
              <span>{c.totals.members}</span>
              <span className="k">{t('cyc.gross')}</span>
              <span>₹ {INR.format(c.totals.gross)}</span>
              <span className="k">{t('cyc.deductions')}</span>
              <span>₹ {INR.format(c.totals.deductions)}</span>
              <span className="k">{t('cyc.net')}</span>
              <span>
                <b>₹ {INR.format(c.totals.net)}</b>
              </span>
              {c.totals.components.map((x) => (
                <span key={x.payCode} className="kv-pair">
                  <span className="k">{`${x.payCode} ${x.name}`}</span> <span>₹ {INR.format(x.amount)}</span>
                </span>
              ))}
            </div>
            {c.id !== null && (
              <div className="toolbar">
                <span className="sp" />
                <a className="btn" href={withBase(`/payroll/?s=X-05&period=${period}&cycle=${c.id}`)}>
                  {t('c02.exceptions')}
                </a>
              </div>
            )}
          </Card>
        )}
      </details>
      {askRelease && lease && (
        <ReasonDialog
          title={t('cyc.leaseReleaseTitle', leaseArgs)}
          note={t('cyc.leaseReleaseNote')}
          onCancel={() => setAskRelease(false)}
          onConfirm={(reason) => {
            setAskRelease(false);
            void run('leaseRelease', reason);
          }}
        />
      )}
      {askReturn && c && (
        <ReturnDialog
          title={t('cyc.returnTitle', [periodLabel(c.period)])}
          onCancel={() => setAskReturn(false)}
          onConfirm={(input) => {
            setAskReturn(false);
            void run('return', input.reason, { returnInput: input });
          }}
        />
      )}
      {askWithdraw && openReturn && (
        <ReasonDialog
          title={t('cyc.withdrawTitle')}
          note={t('cyc.withdrawNote')}
          onCancel={() => setAskWithdraw(false)}
          onConfirm={(reason) => {
            setAskWithdraw(false);
            void run('withdraw', reason, { returnId: openReturn.id });
          }}
        />
      )}
      {askUnfreeze && c && (
        <ReasonDialog
          title={t('cyc.unfreezeTitle', [periodLabel(c.period)])}
          note={t('cyc.unfreezeNote')}
          onCancel={() => setAskUnfreeze(false)}
          onConfirm={(reason) => {
            setAskUnfreeze(false);
            void run('unfreeze', reason);
          }}
        />
      )}
      {askLock && (
        <ReasonDialog
          title={t('cyc.lockTitle', [c ? periodLabel(c.period) : ''])}
          onCancel={() => setAskLock(false)}
          onConfirm={(reason) => {
            setAskLock(false);
            void run('lock', reason);
          }}
        />
      )}
    </div>
  );
}

/** SRS-15-23 / SRS C-01 field table: the pre-run checks P1-P5, each with its failing count and records. */
function PreRunPanel({ checks }: { checks: PreRunCheck[] }) {
  const { t } = useI18n();
  return (
    <table className="t" aria-label={t('cyc.preRun.title')}>
      <thead>
        <tr>
          <th>
            {t('cyc.preRun.check')}
            <Src s="SYS" />
          </th>
          <th>
            {t('cyc.preRun.status')}
            <Src s="CALC" />
          </th>
          <th>
            {t('cyc.preRun.failing')}
            <Src s="HRMS" />
          </th>
        </tr>
      </thead>
      <tbody>
        {checks.map((p) => (
          <tr key={p.code}>
            <td>{p.name}</td>
            <td>
              <span
                className={`pill ${p.status === 'PASS' ? 'p-ok' : p.status === 'FAIL' ? 'p-bad' : 'p-gray'}`}
              >
                {t(`cyc.preRun.${p.status}`)}
              </span>
            </td>
            <td className="fl-wrap-any">
              {p.status === 'FAIL'
                ? `${p.failures}${p.members.length > 0 ? ` · ${p.members.join(', ')}` : ''}${
                    p.correctionScreen ? ` · ${t('cyc.preRun.fixOn', [p.correctionScreen])}` : ''
                  }`
                : '—'}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
