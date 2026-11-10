'use client';

import { useCallback, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { can, useMe } from '@/shared/session';
import {
  amount,
  Card,
  date,
  Empty,
  ExportButton,
  Problem,
  ServerPagination,
  Src,
  useDialogFocus,
  useToast,
  TextAreaField,
  withBase,
} from '@/shared/ui';
import { Act, Explained, Locked, Panel, Tbl, Tiles, WfTile } from '@/shared/ui/wireframe';
import type { DefinitionRequest, RevisionSummary } from '../api/wageRevisionApi';
import {
  definitionReady,
  isFixed,
  isOtpApproved,
  mayFix,
  maySimulate,
  maySubmit,
  officeState,
  revisionOrders,
  revisionTone,
  STAFF_TYPES,
} from '../business/wageRevisionRules';
import {
  useRevision,
  useRevisionAction,
  useRevisionBoard,
  useRevisionOrders,
  wageRevisionCalls,
  wageRevisionKeys,
  wageRevisionMembersUrl,
  wageRevisionStatusUrl,
  wageRevisionPages,
} from '../orchestration/wageRevisionQueries';

/** The nine steps of §6.19.8 as drawn: the glyph's tone per step. */
const STEP_TONES = ['info', 'info', 'info', 'warn', 'info', 'info', 'info', 'gray', 'gray'] as const;
const STEP_SOURCES = ['ENT', 'ENT', 'CALC', 'SYS', 'CALC', 'CALC', 'SYS', 'SYS', 'CALC'] as const;

const SYSTEM_ADMINISTRATOR = 'SYSTEM_ADMINISTRATOR';

/**
 * I-25 Wage Revision (Staff &amp; Labour) as Wireframes v6.1 draws it (a frozen requirement per OD-121; plan 13 phase
 * 13e; SRS-8-25, FRS §6.19.8, §6.4.2.5): the Revision Status Report, "＋ Define Revision Event" and
 * "✅ Approve Revision (OTP)" over the screen; the four tiles; the Revision events; the nine steps; the Revision Status
 * Report DO-wise of the chosen revision. Headquarters defines a revision once on its Active order (M-25), simulates it
 * (no pay changes) and sends it to the Payroll Checker at Headquarters, who approves in W-01 with the OTP
 * WAGE_REVISION_HQ; pay is then fixed in bulk. Not drawn and kept, closed until pressed (PENDING WF-190): a row of the
 * list opens the revision with its nine-step status, simulation, submission, bulk fixation, members and files; the
 * definition form opens from its button. Unit desks read the revision and their members.
 */
export function WageRevisions() {
  const { t } = useI18n();
  const me = useMe();
  const [chosen, setChosen] = useState<RevisionSummary | null>(null);
  const [opened, setOpened] = useState<number | null>(null);
  const [defining, setDefining] = useState(false);
  const board = useRevisionBoard();
  // SRS-8-25: only Headquarters defines a revision (the HQ Payroll Maker or Rule Administrator, V077_29).
  const atHq = me.data?.atHeadquarters === true;
  const mayDefine = can(me.data, 'I-25', 'CREATE') && atHq;
  const approver = can(me.data, 'I-25', 'APPROVE');
  const mayExport = can(me.data, 'I-25', 'EXPORT');
  const sysAdmin = me.data?.roles?.includes(SYSTEM_ADMINISTRATOR) === true;
  const shown = useCallback((page: RevisionSummary[]) => {
    setChosen((prev) => {
      const same = prev ? page.find((r) => r.id === prev.id) : undefined;
      return same ?? page.find((r) => isFixed(r.status)) ?? page[0] ?? null;
    });
  }, []);
  const b = board.data;
  const statusUrl = chosen ? wageRevisionStatusUrl(chosen.id) : '';
  const reportWhy = !mayExport
    ? t('i25.why.noExport')
    : !chosen || !isFixed(chosen.status)
      ? t('i25.why.report')
      : null;

  function open(r: RevisionSummary) {
    setChosen(r);
    setOpened(r.id);
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {reportWhy === null ? (
          <ExportButton href={statusUrl} what={t('i25.statusReport')} label={t('i25.act.report')} />
        ) : (
          <Explained label={t('i25.act.report')} why={reportWhy} />
        )}
        {mayDefine ? (
          <Act label={t('i25.act.define')} kind="pri" onClick={() => setDefining(true)} />
        ) : sysAdmin ? (
          // Drawn for the Administrator; the right is the Rule Administrator's and the HQ Maker's (WF-191).
          <Explained label={t('i25.act.define')} kind="pri" why={t('i25.why.defineAdmin')} />
        ) : (
          <Locked label={t('i25.act.define')} why={t('i25.guard.define')} />
        )}
        {approver && atHq ? (
          <Act label={t('i25.act.approve')} href={withBase('/payroll/?s=W-01')} />
        ) : approver ? (
          // Drawn for the Payroll Checker; SRS-8-25: the approver is at Headquarters.
          <Explained label={t('i25.act.approve')} why={t('i25.why.approveHq')} />
        ) : (
          <Locked label={t('i25.act.approve')} why={t('i25.guard.approve')} />
        )}
      </div>
      <Tiles>
        <WfTile
          icon="📜"
          label={t('i25.tile.events')}
          src="SYS"
          big={b?.events ?? 0}
          sub={t('i25.tile.eventsSub', [b?.approved ?? 0, b?.inSimulation ?? 0])}
        />
        <WfTile
          icon="✅"
          tone="ok"
          label={t('i25.tile.fixed')}
          src="CALC"
          big={amount(b?.membersFixed ?? 0)}
          sub={b?.fixedCode ? t('i25.tile.fixedSub', [b.fixedName, b.fixedCode]) : ''}
        />
        <WfTile
          icon="⚠"
          tone="warn"
          label={t('i25.tile.exceptions')}
          src="SYS"
          big={b?.exceptions ?? 0}
          sub={t('i25.tile.exceptionsSub')}
        />
        <WfTile
          icon="₹"
          tone="pur"
          label={t('i25.tile.arrears')}
          src="CALC"
          big={t('i25.tile.arrearsValue', [amount(b?.arrearsPaid ?? 0)])}
          sub={t('i25.tile.arrearsSub', [b?.officesDone ?? 0, b?.offices ?? 0])}
        />
      </Tiles>
      <Tbl
        title={t('i25.table')}
        label={t('screen.I-25')}
        cols={[
          { t: t('i25.col.event'), s: 'SYS' },
          { t: t('i25.col.order'), s: 'ENT' },
          { t: t('i25.col.types'), s: 'ENT' },
          { t: t('i25.col.scope'), s: 'ENT' },
          { t: t('i25.col.effective'), s: 'ENT' },
          { t: t('i25.col.fitment'), s: 'RULE' },
          { t: t('i25.col.step'), s: 'SYS' },
          { t: t('i25.col.status'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={wageRevisionKeys.list}
          fetchPage={wageRevisionPages.list}
          empty={<p className="fl-hint">{t('i25.none')}</p>}
          onRows={shown}
        >
          {(rows) =>
            rows.map((r) => (
              <tr
                key={r.id}
                data-code={r.code}
                tabIndex={0}
                aria-selected={chosen?.id === r.id}
                className={chosen?.id === r.id ? 'fl-row-selected' : undefined}
                onClick={() => open(r)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') open(r);
                }}
              >
                <td>
                  <b>{r.code}</b>
                  <div className="sub2">{r.name}</div>
                </td>
                <td>{r.order ?? '—'}</td>
                <td>
                  {r.payrollTypes
                    .split(',')
                    .map((p) => t(`i25.typeCol.${p}`))
                    .join(', ')}
                </td>
                <td>{r.scope ? t(`i25.scope.${r.scope}`) : '—'}</td>
                <td>{r.effectiveFrom ? date(r.effectiveFrom) : '—'}</td>
                <td>
                  {r.rule
                    ? `${t(`i25.fitment.${r.rule}`)} ${r.ruleValue ?? ''}${
                        r.rounding ? ` · ${t(`i25.rounding.${r.rounding}`)}` : ''
                      }`
                    : '—'}
                </td>
                <td>{t('i25.stepLabel', [r.step, t(`i25.stepName.${r.step}`)])}</td>
                <td>
                  <span className={`pill ${revisionTone(r.status)}`}>
                    {r.status === 'SIMULATED' ? t('i25.pill.SIMULATED') : t(`i25.status.${r.status}`)}
                    {isOtpApproved(r.status) && (
                      <>
                        {' '}
                        <span className="pill p-pur" title={t('i25.otpTitle')}>
                          {t('i25.otp')}
                        </span>
                      </>
                    )}
                  </span>
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <Panel
          title={t('i25.panel.title')}
          items={STEP_TONES.map((tone, i) => ({
            icon: String(i + 1),
            tone,
            title: t(`i25.panel.${i + 1}`),
            src: STEP_SOURCES[i],
            text: t(`i25.panel.${i + 1}.m`),
          }))}
        />
        <Tbl
          title={chosen ? t('i25.report.title', [chosen.code]) : t('i25.report.titleNone')}
          acts={
            reportWhy === null ? (
              <ExportButton
                href={statusUrl}
                what={t('i25.statusReport')}
                label={t('i25.report.export')}
                className="btn sm"
              />
            ) : (
              <Explained label={t('i25.report.export')} kind="sm" why={reportWhy} />
            )
          }
          cols={[
            { t: t('i25.report.office'), s: 'SYS' },
            { t: t('i25.report.members'), s: 'SYS', num: true },
            { t: t('i25.report.fixed'), s: 'CALC', num: true },
            { t: t('i25.report.exception'), s: 'SYS', num: true },
            { t: t('i25.report.paid'), s: 'CALC', num: true },
            { t: t('i25.report.status'), s: 'SYS' },
          ]}
        >
          {chosen ? (
            <ServerPagination
              key={chosen.id}
              queryKey={wageRevisionKeys.offices(chosen.id)}
              fetchPage={wageRevisionPages.offices(chosen.id)}
              empty={<p className="fl-hint">{t('i25.report.none')}</p>}
            >
              {(rows) =>
                rows.map((o) => {
                  const state = officeState(o);
                  return (
                    <tr key={o.office}>
                      <td>{o.office}</td>
                      <td className="num">{o.members}</td>
                      <td className="num">{o.fixed}</td>
                      <td className="num">{o.exception}</td>
                      <td className="num">{o.paid}</td>
                      <td>
                        <span className={`pill ${state === 'PAID' ? 'p-ok' : 'p-warn'}`}>
                          {t(`i25.report.state.${state}`)}
                        </span>
                      </td>
                    </tr>
                  );
                })
              }
            </ServerPagination>
          ) : (
            <tr>
              <td colSpan={6} className="sub2">
                {t('i25.none')}
              </td>
            </tr>
          )}
        </Tbl>
      </div>
      {opened !== null && <RevisionCard key={opened} id={opened} onClose={() => setOpened(null)} />}
      {defining && (
        <DefineDialog
          onClose={() => setDefining(false)}
          onDefined={(id) => {
            setDefining(false);
            setOpened(id);
          }}
        />
      )}
    </div>
  );
}

/** Steps 1 and 2: the definition on an Active order. */
/** The definition form when it opens and after each revision defined. */
const EMPTY_DEFINITION: Partial<DefinitionRequest> = { payrollTypes: [], rounding: 'RUPEE' };

function DefineDialog({ onClose, onDefined }: { onClose: () => void; onDefined: (id: number) => void }) {
  const { t } = useI18n();
  const dialog = useDialogFocus<HTMLDivElement>();
  const orders = useRevisionOrders(true);
  const action = useRevisionAction();
  const toast = useToast();
  const [draft, setDraft] = useState<Partial<DefinitionRequest>>(EMPTY_DEFINITION);
  const [error, setError] = useState<unknown>(null);
  const set = (patch: Partial<DefinitionRequest>) => setDraft((d) => ({ ...d, ...patch }));
  const num = (v: string) => (v.trim() === '' ? undefined : Number(v));

  async function define() {
    setError(null);
    try {
      const d = (await action.mutateAsync(() =>
        wageRevisionCalls.define({
          ...(draft as DefinitionRequest),
          name: (draft.name ?? '').trim(),
          reason: (draft.reason ?? '').trim(),
        }),
      )) as { summary: { id: number } };
      toast(t('i25.defined'));
      // Review 13-20: the form closes, so a second click defines no duplicate.
      onDefined(d.summary.id);
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
        aria-label={t('action.i25.define')}
      >
        <h2>{t('action.i25.define')}</h2>
        <Problem error={error} />
        <div className="form-grid">
          <label>
            {t('i25.name')}{' '}
            <input maxLength={250} value={draft.name ?? ''} onChange={(e) => set({ name: e.target.value })} />
          </label>
          <label>
            {t('i25.order')}{' '}
            <select
              value={draft.circularId ?? ''}
              onChange={(e) =>
                set({ circularId: e.target.value === '' ? undefined : Number(e.target.value) })
              }
            >
              <option value="">{t('common.choose')}</option>
              {revisionOrders(orders.data ?? []).map((o) => (
                <option key={o.id} value={o.id}>
                  {o.number}
                  {o.subject ? ` - ${o.subject}` : ''}
                </option>
              ))}
            </select>
            {orders.isSuccess && revisionOrders(orders.data).length === 0 && (
              <span className="hint" data-testid="i25-no-order">
                {t('i25.noRevisionOrder')}
              </span>
            )}
          </label>
          <fieldset>
            <legend>{t('i25.types')}</legend>
            {STAFF_TYPES.map((type) => (
              <label key={type}>
                <input
                  type="checkbox"
                  checked={draft.payrollTypes?.includes(type) ?? false}
                  onChange={(e) =>
                    set({
                      payrollTypes: e.target.checked
                        ? [...(draft.payrollTypes ?? []), type]
                        : (draft.payrollTypes ?? []).filter((x) => x !== type),
                    })
                  }
                />{' '}
                {t(`i25.type.${type}`)}
              </label>
            ))}
          </fieldset>
          <label>
            {t('i25.effective')}{' '}
            <input
              type="date"
              value={draft.effectiveFrom ?? ''}
              onChange={(e) => set({ effectiveFrom: e.target.value })}
            />
          </label>
          <label>
            {t('i25.fitment')}{' '}
            <select
              value={draft.fitment ?? ''}
              onChange={(e) =>
                set({
                  fitment:
                    e.target.value === '' ? undefined : (e.target.value as DefinitionRequest['fitment']),
                })
              }
            >
              <option value="">{t('common.choose')}</option>
              <ReferenceOptions list="WAGE_REVISION_FITMENT" />
            </select>
          </label>
          <label>
            {t('i25.value')}{' '}
            <input inputMode="decimal" maxLength={14} onChange={(e) => set({ value: num(e.target.value) })} />
          </label>
          <label>
            {t('i25.minimum')}{' '}
            <input
              inputMode="decimal"
              maxLength={14}
              onChange={(e) => set({ minimum: num(e.target.value) })}
            />
          </label>
          <label>
            {t('i25.maximum')}{' '}
            <input
              inputMode="decimal"
              maxLength={14}
              onChange={(e) => set({ maximum: num(e.target.value) })}
            />
          </label>
          <label>
            {t('i25.rounding')}{' '}
            <select
              value={draft.rounding ?? 'RUPEE'}
              onChange={(e) => set({ rounding: e.target.value as DefinitionRequest['rounding'] })}
            >
              <ReferenceOptions list="WAGE_REVISION_ROUNDING" />
            </select>
          </label>
          <label>
            {t('common.reason')}{' '}
            <TextAreaField
              maxLength={500}
              value={draft.reason ?? ''}
              onChange={(e) => set({ reason: e.target.value })}
            />
          </label>
        </div>
        <div className="fl-actions">
          <button type="button" className="btn" onClick={onClose}>
            {t('common.close')}
          </button>
          <button
            type="button"
            className="btn pri"
            disabled={!definitionReady(draft) || action.isPending}
            title={!definitionReady(draft) ? t('i25.why.define') : undefined}
            onClick={() => void define()}
          >
            {t('action.i25.define')}
          </button>
        </div>
      </div>
    </div>
  );
}

/** One revision: its definition, the nine steps, the simulation and its members. */
function RevisionCard({ id, onClose }: { id: number; onClose: () => void }) {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const detail = useRevision(id);
  const action = useRevisionAction();
  const [remark, setRemark] = useState('');
  const [error, setError] = useState<unknown>(null);
  const d = detail.data;
  const mayAct = can(me.data, 'I-25', 'CREATE') && me.data?.atHeadquarters === true;

  async function run(work: () => Promise<unknown>, done: string) {
    setError(null);
    try {
      await action.mutateAsync(work);
      toast(t(done));
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <Card
      title={d ? `${d.summary.code} - ${d.summary.name}` : t('i25.loading')}
      actions={
        <button type="button" className="btn sm" onClick={onClose}>
          {t('common.close')}
        </button>
      }
    >
      <Problem error={detail.error ?? error} />
      {d && (
        <>
          <div className="kv">
            <span className="k">{t('i25.order')}</span>
            <span>{d.summary.order ?? '—'}</span>
            <span className="k">{t('i25.effective')}</span>
            <span>{date(d.effectiveFrom)}</span>
            <span className="k">{t('i25.fitment')}</span>
            <span>
              {t(`i25.fitment.${d.rule}`)} {d.value}
              {d.minimum !== null && ` · ${t('i25.minimum')} ${amount(d.minimum)}`}
              {d.maximum !== null && ` · ${t('i25.maximum')} ${amount(d.maximum)}`} ·{' '}
              {t(`i25.rounding.${d.rounding}`)}
            </span>
            <span className="k">{t('i25.members')}</span>
            <span>{d.members}</span>
            <span className="k">{t('i25.estimate')}</span>
            <span className="num">{amount(d.estimatedArrear)}</span>
          </div>
          <h4>{t('i25.steps')}</h4>
          <ol className="fl-list" aria-label={t('i25.steps')}>
            {d.steps.map((s) => (
              <li key={s.no} className={s.status === 'DONE' ? undefined : 'warn'}>
                {t(`i25.step.${s.code}`)}: {t(`i25.stepStatus.${s.status}`)}
                {s.by && <span className="sub2"> — {s.by}</span>}
              </li>
            ))}
          </ol>
          <div className="toolbar">
            <button
              type="button"
              className="btn"
              disabled={!mayAct || !maySimulate(d) || action.isPending}
              title={
                !mayAct ? t('reason.i25.hqDefinerOnly') : !maySimulate(d) ? t('i25.why.simulate') : undefined
              }
              onClick={() => void run(() => wageRevisionCalls.simulate(id), 'i25.simulated')}
            >
              {t('action.i25.simulate')}
            </button>
            <label>
              {t('common.reason')}{' '}
              <TextAreaField
                maxLength={500}
                value={remark}
                disabled={!mayAct}
                onChange={(e) => setRemark(e.target.value)}
              />
            </label>
            <button
              type="button"
              className="btn pri"
              disabled={!mayAct || !maySubmit(d) || remark.trim() === '' || action.isPending}
              title={
                !mayAct
                  ? t('reason.i25.hqDefinerOnly')
                  : !maySubmit(d)
                    ? t('i25.why.submit')
                    : remark.trim() === ''
                      ? t('i25.why.remark')
                      : undefined
              }
              onClick={() => void run(() => wageRevisionCalls.submit(id, remark.trim()), 'i25.submitted')}
            >
              {t('common.submitForApproval')}
            </button>
            {mayAct && (
              <button
                type="button"
                className="btn"
                disabled={!mayFix(d) || action.isPending}
                title={!mayFix(d) ? t('i25.why.fix') : undefined}
                onClick={() =>
                  void (async () => {
                    setError(null);
                    try {
                      const f = (await action.mutateAsync(() => wageRevisionCalls.fix(id))) as {
                        fixed: number;
                        exceptions: number;
                        arrears: number;
                      };
                      toast(t('i25.fixed', [f.fixed, f.exceptions, f.arrears]));
                    } catch (cause) {
                      setError(cause);
                    }
                  })()
                }
              >
                {t('action.i25.fix')}
              </button>
            )}
            {can(me.data, 'I-25', 'EXPORT') && (
              <ExportButton href={wageRevisionMembersUrl(id)} what={t('i25.members')} />
            )}
            {can(me.data, 'I-25', 'EXPORT') && d.steps.some((s) => s.no === 5 && s.status === 'DONE') && (
              <ExportButton
                href={wageRevisionStatusUrl(id)}
                what={t('i25.statusReport')}
                label={t('i25.statusReport')}
              />
            )}
          </div>
          <ServerPagination
            queryKey={wageRevisionKeys.members(id)}
            fetchPage={wageRevisionPages.members(id)}
            empty={<Empty text={t('i25.noMembers')} />}
          >
            {(rows) => (
              <table className="t" aria-label={t('i25.members')}>
                <thead>
                  <tr>
                    <th>
                      {t('i25.member')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('i25.office')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('i25.oldPay')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('i25.newPay')}
                      <Src s="CALC" />
                    </th>
                    <th>
                      {t('i25.estimate')}
                      <Src s="CALC" />
                    </th>
                    <th>
                      {t('i25.status')}
                      <Src s="SYS" />
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((m) => (
                    <tr key={m.eis}>
                      <td>
                        {m.eis} {m.name}
                      </td>
                      <td>{m.office}</td>
                      <td className="num">{m.oldPay === null ? '—' : amount(m.oldPay)}</td>
                      <td className="num">{m.newPay === null ? '—' : amount(m.newPay)}</td>
                      <td className="num">{m.estimate === null ? '—' : amount(m.estimate)}</td>
                      <td>{t(`i25.fixation.${m.status}`)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </ServerPagination>
        </>
      )}
    </Card>
  );
}
