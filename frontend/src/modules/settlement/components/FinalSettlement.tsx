'use client';

import { useCallback, useRef, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { amount, date, Empty, Problem, ServerPagination, useToast, withBase } from '@/shared/ui';
import { Act, Locked, RowActs, Tbl, Tiles, WfTile } from '@/shared/ui/wireframe';
import type { CaseDetail, CaseRow, InvoiceRow as SettlementInvoice } from '../api/settlementApi';
import { mayCompute, mayEnter, mayEnterRow, payrollRecovered, stepOf } from '../business/settlementRules';
import {
  settlementCalls,
  settlementKeys,
  settlementPages,
  useCase,
  useSettlementAction,
  useSummary,
} from '../orchestration/settlementQueries';
import { CaseActions } from './CaseActions';
import { CaseForm, type FormState, PendingClearancesDialog, StatementDialog } from './SettlementDialogs';
import {
  ClearanceChecklist,
  ExGratiaCard,
  RecoveriesCard,
  RecoveryRules,
  SettlementInvoices,
  SettlementOrders,
  StatementCard,
} from './SettlementPanels';

/**
 * O-05 Full & Final Settlement as drawn (pack SCREENS.o5, Wireframes v6.1, OD-121; plan 11; SRS-10-8 …
 * 10-32): the page actions, four tiles, the worklist, then the open case (the newest of the offices
 * unless a row is chosen): itemised recoveries, clearance checklist, settlement statement, ex-gratia,
 * the recovery rules, the settlement payments and the order copy. What the drawing leaves out stays
 * behind the closed Case actions section: Submit, Release, Exclude, the no-dues, the CPF information
 * and the ex-gratia entries. The Payroll Checker approves in W-01 with OTP (OD-79). Every control a
 * role may not use is drawn, disabled with its reason (SRS-G-25).
 */
export function FinalSettlement() {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const summary = useSummary();
  const action = useSettlementAction();
  const [eis, setEis] = useState('');
  const [selected, setSelected] = useState<CaseRow | null>(null);
  const [form, setForm] = useState<FormState>(null);
  const [error, setError] = useState<unknown>(null);
  const [clearancesOpen, setClearancesOpen] = useState(false);
  const [statementOpen, setStatementOpen] = useState(false);
  const [actionsOpen, setActionsOpen] = useState(false);
  const actionsRef = useRef<HTMLDetailsElement>(null);
  const detail = useCase(selected?.caseId ?? null);
  const d = detail.data;

  const mayComputeRole = can(me.data, 'O-05', 'COMPUTE');
  const mayApprove = can(me.data, 'O-05', 'APPROVE');
  const mayClear = can(me.data, 'O-05', 'CLEAR');
  const mayReleaseRole = can(me.data, 'O-05', 'RELEASE');

  // SRS-10-8 (pack: the panels stand on the page): the newest case opens unless one is chosen.
  const shown = useCallback((page: CaseRow[]) => {
    setSelected((prev) => (prev ? (page.find((c) => c.caseId === prev.caseId) ?? prev) : (page[0] ?? null)));
  }, []);

  /** Runs the action and answers whether it succeeded (a refusal stays on the screen). */
  async function run(work: () => Promise<unknown>, done: string): Promise<boolean> {
    setError(null);
    try {
      await action.mutateAsync(work);
      toast(t(done));
      return true;
    } catch (cause) {
      setError(cause);
      return false;
    }
  }

  /** A form opened for one case is never carried to the next. */
  function choose(row: CaseRow) {
    setSelected(row);
    setForm(null);
    setError(null);
  }

  /** ✎ Edit: the case with its Maker entries open (recovery source, ex-gratia). */
  function edit(row: CaseRow) {
    choose(row);
    setActionsOpen(true);
    actionsRef.current?.scrollIntoView?.({ block: 'nearest' });
  }

  const inbox = withBase('/payroll/?s=W-01');
  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <Act label={t('o05.w.pendingClearances')} onClick={() => setClearancesOpen(true)} />
        <ComputeButton
          d={d}
          role={mayComputeRole}
          onCompute={(id) => void run(() => settlementCalls.compute(id), 'o05.computed')}
        />
        {mayApprove ? (
          <Act label={t('o05.w.approve')} href={inbox} />
        ) : (
          <Locked label={t('o05.w.approve')} why={t('o05.why.checkerOtp')} />
        )}
        <ExGratiaButton
          d={d}
          role={mayComputeRole}
          checker={mayApprove}
          onEnter={() =>
            setForm({
              kind: 'EX_GRATIA',
              letterNo: '',
              letterDate: '',
              amount: '',
              authority: '',
              file: null,
            })
          }
        />
      </div>
      <Problem error={detail.error ?? (form ? null : error)} />
      <Tiles>
        <WfTile
          icon="📤"
          label={t('o05.tile.separations')}
          src="HRMS"
          big={summary.data?.separations ?? '—'}
          sub={
            summary.data &&
            t('o05.tile.separationsSub', [
              summary.data.settled,
              amount(summary.data.settledAmount),
              summary.data.byType.map((x) => `${separationName(t, x.type)} ${x.cases}`).join(' · '),
            ])
          }
        />
        <WfTile
          icon="⏳"
          tone="warn"
          label={t('o05.tile.awaiting')}
          src="SYS"
          big={summary.data?.awaitingClearance ?? '—'}
          sub={t('o05.tile.awaitingSub')}
        />
        <WfTile
          icon="🏛"
          tone="pur"
          label={t('o05.tile.hq')}
          src="SYS"
          big={summary.data?.hqApproval ?? '—'}
          sub={t('o05.tile.hqSub')}
        />
        <WfTile
          icon="⛔"
          tone="bad"
          bad
          label={t('o05.tile.net')}
          src="CALC"
          big={summary.data?.netRecovery ?? '—'}
          sub={t('o05.tile.netSub')}
        />
      </Tiles>
      <Tbl
        title={t('o05.list.title')}
        label={t('screen.O-05')}
        acts={
          <Act
            kind="sm"
            label={t('o05.statementAct')}
            onClick={() => (d ? setStatementOpen(true) : toast(t('o05.none')))}
          />
        }
        cols={[
          { t: t('o05.case'), s: 'SYS' },
          { t: t('o05.member'), s: 'HRMS' },
          { t: t('o05.col.payrollType'), s: 'RULE' },
          { t: t('o05.separation'), s: 'HRMS' },
          { t: t('o05.col.date'), s: 'HRMS' },
          { t: t('o05.col.dues'), s: 'CALC', num: true },
          { t: t('o05.col.recoveries'), s: 'CALC', num: true },
          { t: t('o05.col.net'), s: 'CALC', num: true },
          { t: t('o05.col.stage'), s: 'SYS' },
          { t: t('o05.col.actions'), s: 'SYS' },
        ]}
        after={
          <details className="fl-mt">
            <summary>{t('o05.filters')}</summary>
            <div className="toolbar fl-filters">
              <label>
                {t('m01.eis')}{' '}
                <input maxLength={20} value={eis} onChange={(e) => setEis(e.target.value.trim())} />
              </label>
            </div>
          </details>
        }
      >
        <ServerPagination
          queryKey={settlementKeys.cases(eis)}
          fetchPage={settlementPages.cases(eis)}
          empty={<Empty text={t('o05.none')} />}
          onRows={shown}
        >
          {(rows) =>
            rows.map((r) => (
              <CaseLine
                key={r.caseId}
                r={r}
                chosen={selected?.caseId === r.caseId}
                maker={mayComputeRole}
                checker={mayApprove}
                onView={() => choose(r)}
                onEdit={() => edit(r)}
                inbox={inbox}
              />
            ))
          }
        </ServerPagination>
      </Tbl>
      {d && (
        <>
          <div className="g2 fl-mt">
            <RecoveriesCard
              d={d}
              editable={mayComputeRole && mayEnter(d)}
              form={form}
              onForm={(next) => setForm(next)}
            />
            <ClearanceChecklist d={d} />
          </div>
          <div className="g2 fl-mt">
            <StatementCard d={d} onOpen={() => setStatementOpen(true)} />
            <div>
              <ExGratiaCard d={d} />
              <RecoveryRules />
            </div>
          </div>
        </>
      )}
      <div className="fl-mt">
        <SettlementInvoices maker={mayComputeRole} memberOf={memberText} />
      </div>
      <SettlementOrders kase={d ?? null} mayAttach={mayComputeRole} />
      {d && (
        <details
          ref={actionsRef}
          className="fl-mt"
          open={actionsOpen}
          onToggle={(e) => setActionsOpen(e.currentTarget.open)}
          data-testid="o05-case-actions"
        >
          <summary>{t('o05.caseActions')}</summary>
          <CaseActions
            key={d.summary.caseId}
            d={d}
            mayCompute={mayComputeRole}
            mayApprove={mayApprove}
            mayClear={mayClear}
            mayRelease={mayReleaseRole}
            run={run}
            onExclude={(clearance) => setForm({ kind: 'EXCLUDE', clearance, reason: '' })}
          />
        </details>
      )}
      {clearancesOpen && <PendingClearancesDialog onClose={() => setClearancesOpen(false)} />}
      {statementOpen && d && <StatementDialog d={d} onClose={() => setStatementOpen(false)} />}
      {form && d && <CaseForm d={d} form={form} onForm={setForm} run={run} error={error} />}
    </div>
  );
}

/** The drawn name of a separation type (SRS l.2933), or the code as HRMS sent it. */
export function separationName(t: (key: string) => string, type: string): string {
  return ['RETIREMENT', 'RESIGNATION', 'DEATH', 'TERMINATION'].includes(type)
    ? t(`o05.separationType.${type}`)
    : type;
}

function memberText(r: SettlementInvoice): string {
  return `${r.eis} ${r.name}`.trim();
}

function ComputeButton({
  d,
  role,
  onCompute,
}: {
  d: CaseDetail | undefined;
  role: boolean;
  onCompute: (id: number) => void;
}) {
  const { t } = useI18n();
  const label = t('action.o05.compute');
  if (!role) return <Locked kind="pri" label={label} why={t('o05.why.makerOnly')} />;
  if (!d || !mayCompute(d)) return <Locked kind="pri" label={label} why={t('o05.why.compute')} />;
  return <Act kind="pri" label={label} onClick={() => onCompute(d.summary.caseId)} />;
}

function ExGratiaButton({
  d,
  role,
  checker,
  onEnter,
}: {
  d: CaseDetail | undefined;
  role: boolean;
  checker: boolean;
  onEnter: () => void;
}) {
  const { t } = useI18n();
  const label = t('action.o05.exGratia');
  if (!role) {
    return <Locked label={label} why={t(checker ? 'o05.why.exGratiaChecker' : 'o05.why.exGratiaMaker')} />;
  }
  if (!d || !mayEnter(d)) return <Locked label={label} why={t('o05.why.compute')} />;
  return <Act label={label} onClick={onEnter} />;
}

/** One row of the worklist (pack rows l.5828-5832; RA_MAKER). */
function CaseLine({
  r,
  chosen,
  maker,
  checker,
  onView,
  onEdit,
  inbox,
}: {
  r: CaseRow;
  chosen: boolean;
  maker: boolean;
  checker: boolean;
  onView: () => void;
  onEdit: () => void;
  inbox: string;
}) {
  const { t } = useI18n();
  const step = stepOf(r);
  const fromGratuity = r.recoveredFromGratuity ?? 0;
  const recovered = payrollRecovered(r.totalRecoveries, r.recoveredFromGratuity);
  const negative = r.netPayable !== null && r.netPayable < 0;
  return (
    <tr
      data-reference={r.caseReference}
      aria-selected={chosen}
      className={chosen ? 'fl-row-selected' : undefined}
    >
      <td>
        <b>{r.caseReference}</b>
        {r.awaiting.length > 0 && (
          <div className="sub2">
            {t('o05.awaits', [r.awaiting.map((a) => t(`o05.clearance.${a}`)).join(', ')])}
          </div>
        )}
      </td>
      <td>
        {r.eis} {r.name}
        {r.office && <div className="sub2">{r.office}</div>}
      </td>
      <td>{t(`o05.payrollType.${r.payrollType}`)}</td>
      <td>{separationName(t, r.separationType)}</td>
      <td>{date(r.separationDate)}</td>
      <td className="num">{r.payrollDues ? amount(r.payrollDues) : '—'}</td>
      <td className="num">
        {recovered > 0 ? amount(recovered) : '—'}
        {fromGratuity > 0 && (
          <div className="sub2">
            {t(r.hqApprovalPending ? 'o05.againstGratuityPending' : 'o05.againstGratuity', [
              amount(fromGratuity),
            ])}
          </div>
        )}
      </td>
      <td className={`num${negative ? ' fl-bad' : ''}`}>
        {r.netPayable === null ? '—' : amount(r.netPayable)}
      </td>
      <td>
        <span className={`pill ${step.tone}`}>
          {t('o05.step', [step.number, t(`o05.stepText.${step.text}`)])}
        </span>
      </td>
      <td>
        <RowActs
          viewName={t('common.view')}
          editName={t('common.edit')}
          onView={onView}
          onEdit={maker && mayEnterRow(r) ? onEdit : undefined}
          editWhy={maker ? t('o05.why.compute') : t('o05.why.edit')}
          onApprove={checker && r.awaitingChecker ? () => window.location.assign(inbox) : undefined}
          approveWhy={checker ? t('o05.why.noDecision') : undefined}
        />
      </td>
    </tr>
  );
}
