'use client';

import { type FormEvent, useEffect, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { useI18n } from '@/shared/i18n';
import { can, periodLabel, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import {
  date,
  Problem,
  ReasonDialog,
  Src,
  TextAreaField,
  useDialogFocus,
  useToast,
  withBase,
} from '@/shared/ui';
import { Act, Locked, RowActs, Tbl, ViewDialog } from '@/shared/ui/wireframe';
import type { CalendarPeriod, PeriodView, RunStep } from '../api/calendarApi';
import { blankPeriod, defaultFinYear, monthsOf, move } from '../business/calendarRules';
import { CycleParameters } from './CycleParameters';
import { useCycleAction, usePreRunChecks } from '../orchestration/cycleQueries';
import {
  useCalendarYear,
  useCalendarYears,
  useProposeOrder,
  useProposePeriod,
  useRunOrder,
} from '../orchestration/calendarQueries';

const PLAIN_TEXT = /^[\x20-\x7E\t\r\n]*$/;
const DATES = [
  ['start', true],
  ['end', true],
  ['freezeTarget', true],
  ['computeBy', false],
  ['verifyBy', false],
  ['lockTarget', true],
  ['disbursement', false],
  ['supplementaryFrom', true],
  ['supplementaryTo', true],
  ['mdpFrom', true],
  ['mdpTo', true],
] as const;

/** "2026-07" as the pack's period cell: the key and the month ("202607 JUL 2026"). */
function periodKey(month: string): string {
  return month.replace('-', '');
}

/**
 * M-10 Payroll Calendar & Cycle Setup as Wireframes v6.1 draws it (OD-121; plan 05 phase 05f;
 * SRS-15-33, SRS M-10, SRS-15-1, SRS l.1633-1638): History, FY Calendar and Initiate Period over
 * the screen, the calendar of the year with one row per defined period, the Engine Run Order with
 * the state of each engine at the session's unit, and the Cycle Parameters. The Administrator
 * maintains a period or the order (Maintain, row Edit, Reorder), the Payroll Checker approves it on
 * W-01; a period a cycle was initiated for is fixed (plan 05 D-12). The Payroll Maker initiates its
 * own cycle instance here after the confirmation and the checks P1-P5 (SRS-15-23, SRS-15-33).
 * Controls a role may not use stay drawn, disabled with the reason.
 */
export function PayrollCalendar() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const me = useMe();
  const years = useCalendarYears();
  const [finYear, setFinYear] = useState('');
  useEffect(() => {
    // UI audit S4: open on the current financial year (IST), not on next year's empty one.
    if (finYear === '' && years.data && years.data.length > 0)
      setFinYear(defaultFinYear(years.data, businessToday()) as string);
  }, [finYear, years.data]);
  const year = useCalendarYear(finYear);
  const order = useRunOrder();
  const proposePeriod = useProposePeriod(finYear);
  const proposeOrder = useProposeOrder();
  const [editing, setEditing] = useState<CalendarPeriod | null>(null);
  const [viewing, setViewing] = useState<PeriodView | null>(null);
  const [picking, setPicking] = useState(false);
  const [yearDialog, setYearDialog] = useState(false);
  const [initiating, setInitiating] = useState(false);
  const [reordering, setReordering] = useState(false);
  const [draft, setDraft] = useState<RunStep[] | null>(null);
  const [askOrder, setAskOrder] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const mayEdit = can(me.data, 'M-10', 'EDIT');
  const mayReorder = can(me.data, 'M-10', 'REORDER');
  const mayApprove = can(me.data, 'M-10', 'APPROVE');
  const mayInitiate = can(me.data, 'C-02', 'CREATE');
  const period = me.data?.period ?? '';
  const yearPending = year.data?.pendingApprovalId != null;
  const orderPending = order.data?.pendingApprovalId != null;
  const rows = year.data?.periods ?? [];
  const byMonth = new Map(rows.map((p) => [p.period.month, p]));
  const steps = draft ?? order.data?.steps ?? [];
  const open = monthsOf(finYear).filter((m) => byMonth.get(m)?.initiated !== true);

  async function submitPeriod(next: CalendarPeriod, reason: string) {
    setError(null);
    try {
      await proposePeriod.mutateAsync({ period: next, reason });
      setEditing(null);
      toast(t('m10.proposed'));
    } catch (cause) {
      setError(cause);
    }
  }

  async function submitOrder(reason: string) {
    setError(null);
    try {
      await proposeOrder.mutateAsync({ order: steps.map((s) => s.code), reason });
      setDraft(null);
      setReordering(false);
      toast(t('m10.orderProposed'));
    } catch (cause) {
      setError(cause);
    }
  }

  /** Why a row's Edit is disabled: the drawn reasons, then the rule that fixes a period. */
  function editWhy(row: PeriodView): string {
    if (row.cycleStatus === 'LOCKED') return t('m10w.why.locked');
    if (row.initiated) return t('error.CAL-409-IN-USE', [row.period.month]);
    if (yearPending) return t('m10.yearPending', [year.data?.pendingApprovalId]);
    return t('m10w.why.admin');
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <HistoryButton
          entity="payroll_calendar"
          recordKey={periodKey(period)}
          label={period ? periodLabel(period, locale) : ''}
          text={t('wf.historyAct')}
          className="btn"
        />
        <Act label={t('m10w.fyCalendar')} onClick={() => setYearDialog(true)} />
        {mayInitiate ? (
          <Act label={t('m10w.initiate')} kind="pri" onClick={() => setInitiating(true)} />
        ) : (
          <Locked label={t('m10w.initiate')} kind="pri" why={t('m10w.why.initiate')} />
        )}
      </div>
      <Problem error={years.error ?? year.error ?? order.error ?? error} />
      {yearPending && (
        <p className="fl-banner" role="note">
          {t('m10.yearPending', [year.data?.pendingApprovalId])}
        </p>
      )}
      <Tbl
        title={t('m10w.calendar', [finYear])}
        acts={
          mayEdit && !yearPending && open.length > 0 ? (
            <Act label={t('m10w.maintain')} kind="sm" onClick={() => setPicking(true)} />
          ) : (
            <Locked
              label={t('m10w.maintain')}
              kind="sm"
              why={mayEdit ? t('m10.yearPending', [year.data?.pendingApprovalId]) : t('m10w.why.maintain')}
            />
          )
        }
        cols={[
          { t: t('m10w.col.period'), s: 'SYS' },
          { t: t('m10w.col.cutoff'), s: 'ENT' },
          { t: t('m10w.col.compute'), s: 'ENT' },
          { t: t('m10w.col.verify'), s: 'ENT' },
          { t: t('m10w.col.lock'), s: 'ENT' },
          { t: t('m10w.col.disbursement'), s: 'ENT' },
          { t: t('m10w.col.supplementary'), s: 'ENT' },
          { t: t('m10w.col.status'), s: 'SYS' },
          { t: t('c08.col.actions'), s: 'SYS' },
        ]}
      >
        {rows.length === 0 && (
          <tr>
            <td colSpan={9} className="sub2">
              {t('m10w.noPeriods', [finYear])}
            </td>
          </tr>
        )}
        {rows.map((row) => {
          const p = row.period;
          const editable = mayEdit && !yearPending && !row.initiated && row.cycleStatus !== 'LOCKED';
          return (
            <tr key={p.month}>
              <td>
                <b>{periodKey(p.month)}</b> {periodLabel(p.month, locale)}
              </td>
              <td>{date(p.freezeTarget)}</td>
              <td>{p.computeBy ? date(p.computeBy) : '—'}</td>
              <td>{p.verifyBy ? date(p.verifyBy) : '—'}</td>
              <td>{date(p.lockTarget)}</td>
              <td>{p.disbursement ? date(p.disbursement) : '—'}</td>
              <td>
                {p.supplementaryFrom ? `${date(p.supplementaryFrom)} – ${date(p.supplementaryTo)}` : '—'}
              </td>
              <td>
                <StatusPill row={row} />
              </td>
              <td>
                <RowActs
                  viewName={t('m10w.viewOf', [periodLabel(p.month, locale)])}
                  editName={t('m10.edit', [periodLabel(p.month, locale)])}
                  onView={() => setViewing(row)}
                  onEdit={editable ? () => setEditing(p) : undefined}
                  editWhy={editWhy(row)}
                  onApprove={
                    mayApprove && yearPending
                      ? () => window.location.assign(withBase('/payroll/?s=W-01'))
                      : undefined
                  }
                  approveWhy={mayApprove && !yearPending ? t('m10w.why.noProposal') : undefined}
                />
              </td>
            </tr>
          );
        })}
      </Tbl>
      <div className="g2 fl-mt">
        <Tbl
          title={t('m10.runOrder')}
          acts={
            mayReorder && !orderPending ? (
              <Act label={t('m10w.reorder')} kind="sm" onClick={() => setReordering(true)} />
            ) : (
              <Locked
                label={t('m10w.reorder')}
                kind="sm"
                why={
                  mayReorder ? t('m10.orderPending', [order.data?.pendingApprovalId]) : t('m10w.why.reorder')
                }
              />
            )
          }
          cols={[
            { t: t('m10.seq'), s: 'ENT' },
            { t: t('m10.step'), s: 'RULE' },
            { t: t('m10.dependsOn'), s: 'RULE' },
            { t: t('m10.owner'), s: 'ENT' },
            { t: t('m10w.col.status'), s: 'SYS' },
          ]}
          after={
            orderPending ? (
              <p className="fl-banner" role="note">
                {t('m10.orderPending', [order.data?.pendingApprovalId])}
              </p>
            ) : undefined
          }
        >
          {(order.data?.steps ?? []).map((s, i) => (
            <tr key={s.code}>
              <td>{i + 1}</td>
              <td>{s.name}</td>
              <td>{s.dependsOn ?? '—'}</td>
              <td>{s.ownerRole ? t(`role.${s.ownerRole}`) : '—'}</td>
              <td>
                <span
                  className={`pill ${s.status === 'COMPLETE' ? 'p-ok' : s.status === 'RUNNING' ? 'p-warn' : 'p-gray'}`}
                >
                  {s.status ? t(`m10w.engine.${s.status}`) : t('m10w.engine.QUEUED')}
                </span>
              </td>
            </tr>
          ))}
        </Tbl>
        <CycleParameters />
      </div>
      {yearDialog && (
        <YearDialog
          years={years.data ?? []}
          finYear={finYear}
          onChange={setFinYear}
          onClose={() => setYearDialog(false)}
        />
      )}
      {picking && (
        <MonthDialog
          months={open}
          defined={(m) => byMonth.has(m)}
          onCancel={() => setPicking(false)}
          onPick={(month) => {
            setPicking(false);
            setEditing(byMonth.get(month)?.period ?? blankPeriod(month));
          }}
        />
      )}
      {viewing && (
        <ViewDialog
          screen={t('screen.M-10')}
          onClose={() => setViewing(null)}
          actions={
            <HistoryButton
              entity="payroll_calendar"
              recordKey={periodKey(viewing.period.month)}
              label={periodLabel(viewing.period.month, locale)}
            />
          }
          rows={[
            { k: t('m10w.col.period'), v: periodKey(viewing.period.month), s: 'SYS' },
            { k: t('m10.field.start'), v: date(viewing.period.start), s: 'ENT' },
            { k: t('m10.field.end'), v: date(viewing.period.end), s: 'ENT' },
            { k: t('m10w.col.cutoff'), v: date(viewing.period.freezeTarget), s: 'ENT' },
            {
              k: t('m10w.col.compute'),
              v: viewing.period.computeBy ? date(viewing.period.computeBy) : '—',
              s: 'ENT',
            },
            {
              k: t('m10w.col.verify'),
              v: viewing.period.verifyBy ? date(viewing.period.verifyBy) : '—',
              s: 'ENT',
            },
            { k: t('m10w.col.lock'), v: date(viewing.period.lockTarget), s: 'ENT' },
            {
              k: t('m10w.col.disbursement'),
              v: viewing.period.disbursement ? date(viewing.period.disbursement) : '—',
              s: 'ENT',
            },
            {
              k: t('m10w.col.supplementary'),
              v: viewing.period.supplementaryFrom
                ? `${date(viewing.period.supplementaryFrom)} – ${date(viewing.period.supplementaryTo)}`
                : '—',
              s: 'ENT',
            },
            {
              k: t('m10.field.mdpFrom'),
              v: viewing.period.mdpFrom ? date(viewing.period.mdpFrom) : '—',
              s: 'ENT',
            },
            {
              k: t('m10.field.mdpTo'),
              v: viewing.period.mdpTo ? date(viewing.period.mdpTo) : '—',
              s: 'ENT',
            },
            { k: t('m10w.col.status'), v: <StatusPill row={viewing} />, s: 'SYS' },
          ]}
        />
      )}
      {editing && (
        <PeriodDialog
          period={editing}
          onCancel={() => setEditing(null)}
          onConfirm={(next, reason) => void submitPeriod(next, reason)}
        />
      )}
      {initiating && <InitiateDialog period={period} onClose={() => setInitiating(false)} />}
      {reordering && (
        <ReorderDialog
          steps={steps}
          changed={draft !== null}
          onMove={(i, by) => setDraft(move(steps, i, by))}
          onReset={() => setDraft(null)}
          onPropose={() => setAskOrder(true)}
          onClose={() => {
            setDraft(null);
            setReordering(false);
          }}
        />
      )}
      {askOrder && (
        <ReasonDialog
          title={t('m10.proposeOrder')}
          onCancel={() => setAskOrder(false)}
          onConfirm={(reason) => {
            setAskOrder(false);
            void submitOrder(reason);
          }}
        />
      )}
    </div>
  );
}

/** The drawn Status: the cycle at the session's unit, with the MDP flag beside it (SRS-15-12). */
function StatusPill({ row }: { row: PeriodView }) {
  const { t } = useI18n();
  if (!row.cycleStatus) return <span className="pill p-gray">{t('d01.state.NOT_INITIATED')}</span>;
  if (row.cycleStatus === 'LOCKED') {
    return (
      <span className="pill p-info">{row.windowOpen ? t('m10w.lockedMdp') : t('d01.state.LOCKED')}</span>
    );
  }
  return <span className="pill p-warn">{t(`d01.state.${row.cycleStatus}`)}</span>;
}

/** 📅 FY Calendar: the financial year the screen shows (SRS l.1633, from the list). */
function YearDialog({
  years,
  finYear,
  onChange,
  onClose,
}: {
  years: string[];
  finYear: string;
  onChange: (year: string) => void;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const dialog = useDialogFocus<HTMLDivElement>();
  return (
    <div className="fl-modal-backdrop" role="presentation">
      <div
        ref={dialog}
        className="fl-modal"
        role="dialog"
        aria-modal="true"
        aria-label={t('m10w.fyCalendar')}
      >
        <h2>{t('m10w.fyCalendar')}</h2>
        <p className="sub2">{t('m10.hint')}</p>
        <label className="fl-field">
          {t('m10.finYear')}
          <select name="finYear" value={finYear} onChange={(e) => onChange(e.target.value)}>
            {years.map((y) => (
              <option key={y} value={y}>
                {y}
              </option>
            ))}
          </select>
        </label>
        <div className="fl-actions">
          <button type="button" className="btn" onClick={onClose}>
            {t('common.close')}
          </button>
        </div>
      </div>
    </div>
  );
}

/** Maintain: the month to define or change; a period a cycle was initiated for is not offered. */
function MonthDialog({
  months,
  defined,
  onPick,
  onCancel,
}: {
  months: string[];
  defined: (month: string) => boolean;
  onPick: (month: string) => void;
  onCancel: () => void;
}) {
  const { t, locale } = useI18n();
  const dialog = useDialogFocus<HTMLFormElement>();
  const [month, setMonth] = useState(months[0] ?? '');
  return (
    <div className="fl-modal-backdrop" role="presentation">
      <form
        ref={dialog}
        className="fl-modal"
        role="dialog"
        aria-modal="true"
        aria-label={t('m10w.maintain')}
        onSubmit={(e) => {
          e.preventDefault();
          onPick(month);
        }}
      >
        <h2>{t('m10w.maintain')}</h2>
        <label className="fl-field">
          {t('m10.month')}
          <select name="month" value={month} onChange={(e) => setMonth(e.target.value)}>
            {months.map((m) => (
              <option key={m} value={m}>
                {`${periodLabel(m, locale)} - ${defined(m) ? t('m10w.defined') : t('m10.notDefined')}`}
              </option>
            ))}
          </select>
        </label>
        <div className="fl-actions">
          <button type="button" className="btn" onClick={onCancel}>
            {t('common.cancel')}
          </button>
          <button type="submit" className="btn pri">
            {t('m10w.continue')}
          </button>
        </div>
      </form>
    </div>
  );
}

/**
 * ＋ Initiate Period (SRS-15-23, SRS-15-33): the confirmation and the checks P1-P5 of the Maker's
 * own instance; Initialise stays disabled until they clear, the failing records named.
 */
function InitiateDialog({ period, onClose }: { period: string; onClose: () => void }) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const dialog = useDialogFocus<HTMLDivElement>();
  const checks = usePreRunChecks(period, true);
  const action = useCycleAction(period);
  const [error, setError] = useState<unknown>(null);
  const failing = (checks.data ?? []).filter((c) => c.status === 'FAIL');
  const ready = checks.data !== undefined && failing.length === 0;

  async function initiate() {
    setError(null);
    try {
      await action.mutateAsync({ action: 'initiate' });
      toast(t('m10w.initiated', [periodLabel(period, locale)]));
      onClose();
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="fl-modal-backdrop" role="presentation">
      <div
        ref={dialog}
        className="fl-modal fl-modal-wide"
        role="dialog"
        aria-modal="true"
        aria-label={t('m10w.initiate')}
      >
        <h2>{t('m10w.initiate')}</h2>
        <p className="sub2">{t('m10w.initiateNote', [periodLabel(period, locale)])}</p>
        <Problem error={checks.error ?? error} />
        <table className="t" aria-label={t('cyc.preRun.title')}>
          <thead>
            <tr>
              <th>
                {t('cyc.preRun.check')} <Src s="RULE" />
              </th>
              <th>
                {t('cyc.preRun.status')} <Src s="CALC" />
              </th>
              <th>
                {t('cyc.preRun.failing')} <Src s="CALC" />
              </th>
            </tr>
          </thead>
          <tbody>
            {(checks.data ?? []).map((c) => (
              <tr key={c.code}>
                <td>
                  <b>{c.code}</b> {c.name}
                </td>
                <td>{t(`cyc.preRun.${c.status}`)}</td>
                <td>{c.members.join(', ') || '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="fl-actions">
          <button type="button" className="btn" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className="btn pri"
            disabled={!ready || action.isPending}
            title={ready ? undefined : t('cyc.preRun.pending')}
            onClick={() => void initiate()}
          >
            {t('m10w.initialise')}
          </button>
        </div>
      </div>
    </div>
  );
}

/** Reorder: the steps with ↑ ↓, the proposal and undo (SRS-15-1, SRS-15-33; route M10_REORDER). */
function ReorderDialog({
  steps,
  changed,
  onMove,
  onReset,
  onPropose,
  onClose,
}: {
  steps: RunStep[];
  changed: boolean;
  onMove: (index: number, by: 1 | -1) => void;
  onReset: () => void;
  onPropose: () => void;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const dialog = useDialogFocus<HTMLDivElement>();
  return (
    <div className="fl-modal-backdrop" role="presentation">
      <div
        ref={dialog}
        className="fl-modal fl-modal-wide"
        role="dialog"
        aria-modal="true"
        aria-label={t('m10w.reorder')}
      >
        <h2>{t('m10w.reorder')}</h2>
        <table className="t" aria-label={t('m10.runOrder')}>
          <thead>
            <tr>
              <th>
                {t('m10.seq')} <Src s="ENT" />
              </th>
              <th>
                {t('m10.step')} <Src s="SYS" />
              </th>
              <th aria-label={t('common.actions')} />
            </tr>
          </thead>
          <tbody>
            {steps.map((s, i) => (
              <tr key={s.code}>
                <td>{i + 1}</td>
                <td>
                  {s.name}
                  <div className="sub2">{s.code}</div>
                  <HistoryButton entity="cycle_run_order" recordKey={s.code} label={s.name} />
                </td>
                <td>
                  <button
                    type="button"
                    className="ia"
                    disabled={i === 0}
                    aria-label={t('m10.up', [s.name])}
                    onClick={() => onMove(i, -1)}
                  >
                    ↑
                  </button>
                  <button
                    type="button"
                    className="ia"
                    disabled={i === steps.length - 1}
                    aria-label={t('m10.down', [s.name])}
                    onClick={() => onMove(i, 1)}
                  >
                    ↓
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="fl-actions">
          <button type="button" className="btn" onClick={onClose}>
            {t('common.close')}
          </button>
          <button type="button" className="btn" disabled={!changed} onClick={onReset}>
            {t('m10.resetOrder')}
          </button>
          <button type="button" className="btn pri" disabled={!changed} onClick={onPropose}>
            {t('m10.proposeOrder')}
          </button>
        </div>
      </div>
    </div>
  );
}

/** SRS M-10 field table: one period's dates and windows, and the reason of the proposal. */
function PeriodDialog({
  period,
  onConfirm,
  onCancel,
}: {
  period: CalendarPeriod;
  onConfirm: (period: CalendarPeriod, reason: string) => void;
  onCancel: () => void;
}) {
  const { t } = useI18n();
  const dialog = useDialogFocus<HTMLFormElement>();
  // Every date as typed ('' = empty); the optional ones go to the server as null.
  const [values, setValues] = useState<Record<string, string>>(
    Object.fromEntries(DATES.map(([key]) => [key, period[key] ?? ''])),
  );
  const [reason, setReason] = useState('');
  const complete = DATES.every(([key, required]) => !required || (values[key] ?? '') !== '');
  const ready = complete && reason.trim() !== '' && PLAIN_TEXT.test(reason);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!ready) return;
    const value = (key: (typeof DATES)[number][0]) => values[key] ?? '';
    const optional = (key: (typeof DATES)[number][0]) => (value(key) === '' ? null : value(key));
    onConfirm(
      {
        month: period.month,
        start: value('start'),
        end: value('end'),
        freezeTarget: value('freezeTarget'),
        computeBy: optional('computeBy'),
        verifyBy: optional('verifyBy'),
        lockTarget: value('lockTarget'),
        disbursement: optional('disbursement'),
        supplementaryFrom: value('supplementaryFrom'),
        supplementaryTo: value('supplementaryTo'),
        mdpFrom: value('mdpFrom'),
        mdpTo: value('mdpTo'),
      },
      reason.trim(),
    );
  }

  return (
    <div className="fl-modal-backdrop" role="presentation">
      <form
        ref={dialog}
        className="fl-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="period-title"
        onSubmit={submit}
      >
        <h2 id="period-title">{t('m10.periodTitle', [periodLabel(period.month)])}</h2>
        <p className="sub2">{t('m10.periodNote')}</p>
        {DATES.map(([key, required]) => (
          <label key={key} className="fl-field">
            {t(`m10.field.${key}`)}
            <input
              type="date"
              name={key}
              required={required}
              value={values[key] ?? ''}
              onChange={(e) => setValues({ ...values, [key]: e.target.value })}
            />
          </label>
        ))}
        <label className="fl-field">
          {t('common.reason')}
          <TextAreaField
            name="reason"
            maxLength={500}
            rows={2}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
        <div className="fl-actions">
          <button type="button" className="btn" onClick={onCancel}>
            {t('common.cancel')}
          </button>
          <button type="submit" className="btn pri" disabled={!ready}>
            {t('m10.submit')}
          </button>
        </div>
      </form>
    </div>
  );
}
