'use client';

import { useEffect, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { can, hqOnlyBlock, useMe } from '@/shared/session';
import { Card, date, Problem, ServerPagination, Src, useToast, TextAreaField } from '@/shared/ui';
import { businessToday } from '@/shared/time';
import type { ParameterRow, ProposedParameter } from '../api/mastersApi';
import { parameterValues } from '../business/masterRules';
import { masterKeys, masterPages, useProposeOtherParameter } from '../orchestration/masterQueries';

type Draft = Omit<ProposedParameter, 'reason'>;

const EMPTY: Draft = {
  effectiveFrom: '',
  numericValue: null,
  ceilingValue: null,
  percentValue: null,
  windowFromTime: null,
  windowToTime: null,
  instalmentCount: null,
  payrollTypeScope: null,
  confirmed: false,
};

/**
 * M-23 Other Parameters, Ceilings, Deputation & Shift: live data of other_parameter (target DDL v4.0
 * S19). A parameter without an approved value is shown as not seeded (v4.0: an unseeded ceiling
 * silently over-pays), an unconfirmed one as such; a change is a new dated version for approval.
 */
export function ParameterRegister({
  edit = null,
  onEditing,
}: {
  /** A drawn row's ✎ Edit: opens the version editor of this parameter. */
  edit?: ParameterRow | null;
  onEditing?: () => void;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const hqBlock = hqOnlyBlock(me.data, 'M-23');
  const proposal = useProposeOtherParameter();
  const [historyOf, setHistoryOf] = useState<string | null>(null);
  const [editing, setEditing] = useState<ParameterRow | null>(null);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>(null);
  const mayCreate = can(me.data, 'M-23', 'CREATE');

  function startEdit(row: ParameterRow) {
    const c = row.current;
    setEditing(row);
    setDraft({
      ...EMPTY,
      ...(c
        ? {
            numericValue: c.numericValue,
            ceilingValue: c.ceilingValue,
            percentValue: c.percentValue,
            windowFromTime: c.windowFromTime,
            windowToTime: c.windowToTime,
            instalmentCount: c.instalmentCount,
            payrollTypeScope: c.payrollTypeScope,
          }
        : {}),
      effectiveFrom: businessToday(),
    });
    setReason('');
    setError(null);
  }

  useEffect(() => {
    if (!edit) return;
    startEdit(edit);
    onEditing?.();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- runs once per drawn Edit
  }, [edit]);

  const num = (v: string) => (v === '' ? null : Number(v));

  async function submit(editing: ParameterRow) {
    setError(null);
    try {
      await proposal.mutateAsync({ code: editing.code, body: { ...draft, reason } });
      toast(t('mst.submitted', [editing.name]));
      setEditing(null);
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sub2">{t('mst.hint.M-23')}</span>
      </div>
      <Problem error={editing ? null : error} />
      <Card title={t('screen.M-23')}>
        <div className="wrap">
          <ServerPagination
            queryKey={masterKeys.parameters}
            fetchPage={masterPages.parameters}
            personal={false}
          >
            {(rows) => (
              <table className="t">
                <thead>
                  <tr>
                    <th>
                      {t('mst.parameter')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('mst.value')}
                      <Src s="RULE" />
                    </th>
                    <th>
                      {t('mst.version')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('mst.pending')}
                      <Src s="SYS" />
                    </th>
                    <th aria-label={t('common.actions')} />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.code}>
                      <td>
                        <b>{row.name}</b>
                        <div className="sub2">
                          {row.code}
                          {row.payCode !== null ? ` · ${t('mst.payCode', [row.payCode])}` : ''}
                        </div>
                      </td>
                      <td>
                        {row.current ? (
                          <>
                            {parameterValues(row.current).join(' · ') || '—'}
                            {!row.current.confirmed && (
                              <span className="pill p-warn">{t('mst.unconfirmed')}</span>
                            )}
                          </>
                        ) : (
                          <span className="pill p-bad">{t('mst.notSeeded')}</span>
                        )}
                      </td>
                      <td>
                        {row.current ? (
                          <>
                            v{row.current.versionNo}
                            <div className="sub2">
                              {date(row.current.effectiveFrom)} –{' '}
                              {row.current.effectiveTo ? date(row.current.effectiveTo) : t('mst.open')}
                            </div>
                          </>
                        ) : (
                          '—'
                        )}
                      </td>
                      <td>
                        {row.pending ? (
                          <span className="pill p-warn">
                            {t('mst.pendingVersion', [
                              row.pending.versionNo,
                              date(row.pending.effectiveFrom),
                            ])}
                          </span>
                        ) : (
                          '—'
                        )}
                      </td>
                      <td>
                        <div className="acts">
                          <button
                            type="button"
                            className="ia"
                            onClick={() => setHistoryOf(historyOf === row.code ? null : row.code)}
                          >
                            {t('mst.history')}
                          </button>
                          <HistoryButton entity="other_parameter" recordKey={row.code} />
                          {mayCreate && !row.pending && !row.hrmsOwned && (
                            <button
                              type="button"
                              disabled={hqBlock !== null}
                              title={hqBlock ? t(hqBlock) : undefined}
                              className="ia"
                              onClick={() => startEdit(row)}
                            >
                              {t('mst.newVersion')}
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </ServerPagination>
        </div>
      </Card>
      {historyOf && (
        <Card title={t('mst.historyOf', [historyOf])}>
          <div className="wrap">
            <ServerPagination
              queryKey={masterKeys.parameterHistory(historyOf)}
              fetchPage={masterPages.parameterHistory(historyOf)}
              personal={false}
            >
              {(versions) => (
                <table className="t">
                  <thead>
                    <tr>
                      <th>
                        {t('mst.version')}
                        <Src s="SYS" />
                      </th>
                      <th>
                        {t('mst.from')}
                        <Src s="ENT" />
                      </th>
                      <th>
                        {t('mst.to')}
                        <Src s="SYS" />
                      </th>
                      <th>
                        {t('mst.state')}
                        <Src s="SYS" />
                      </th>
                      <th>
                        {t('mst.value')}
                        <Src s="RULE" />
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {versions.map((v) => (
                      <tr key={v.id}>
                        <td>v{v.versionNo}</td>
                        <td>{date(v.effectiveFrom)}</td>
                        <td>{v.effectiveTo ? date(v.effectiveTo) : '—'}</td>
                        <td>{t(`status.${v.status}`)}</td>
                        <td>{parameterValues(v).join(' · ') || '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </ServerPagination>
          </div>
        </Card>
      )}
      {editing && (
        <Card title={t('mst.newVersionOf', [editing.name])}>
          <Problem error={error} />
          <div className="bd rf-grid">
            <div className="rf-field">
              <label htmlFor="op-from">{t('mst.from')}</label>
              <input
                id="op-from"
                type="date"
                value={draft.effectiveFrom}
                onChange={(e) => setDraft({ ...draft, effectiveFrom: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="op-pct">{t('mst.percent')}</label>
              <input
                id="op-pct"
                type="number"
                min={0}
                max={100}
                step="0.01"
                value={draft.percentValue ?? ''}
                onChange={(e) => setDraft({ ...draft, percentValue: num(e.target.value) })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="op-num">{t('mst.numeric')}</label>
              <input
                id="op-num"
                type="number"
                min={0}
                step="0.01"
                value={draft.numericValue ?? ''}
                onChange={(e) => setDraft({ ...draft, numericValue: num(e.target.value) })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="op-ceil">{t('mst.ceiling')}</label>
              <input
                id="op-ceil"
                type="number"
                min={0}
                step="0.01"
                value={draft.ceilingValue ?? ''}
                onChange={(e) => setDraft({ ...draft, ceilingValue: num(e.target.value) })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="op-wf">{t('mst.windowFrom')}</label>
              <input
                id="op-wf"
                type="time"
                value={draft.windowFromTime ?? ''}
                onChange={(e) => setDraft({ ...draft, windowFromTime: e.target.value || null })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="op-wt">{t('mst.windowTo')}</label>
              <input
                id="op-wt"
                type="time"
                value={draft.windowToTime ?? ''}
                onChange={(e) => setDraft({ ...draft, windowToTime: e.target.value || null })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="op-inst">{t('mst.instalments')}</label>
              <input
                id="op-inst"
                type="number"
                min={1}
                value={draft.instalmentCount ?? ''}
                onChange={(e) => setDraft({ ...draft, instalmentCount: num(e.target.value) })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="op-scope">{t('mst.appliesTo')}</label>
              <input
                id="op-scope"
                maxLength={100}
                value={draft.payrollTypeScope ?? ''}
                onChange={(e) => setDraft({ ...draft, payrollTypeScope: e.target.value || null })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="op-conf">
                <input
                  id="op-conf"
                  type="checkbox"
                  checked={draft.confirmed}
                  onChange={(e) => setDraft({ ...draft, confirmed: e.target.checked })}
                />{' '}
                {t('mst.confirmed')}
              </label>
              <span className="hint">{t('mst.confirmedHint')}</span>
            </div>
            <div className="rf-field span2">
              <label htmlFor="op-reason">{t('common.reason')}</label>
              <TextAreaField
                id="op-reason"
                value={reason}
                maxLength={500}
                onChange={(e) => setReason(e.target.value)}
              />
            </div>
          </div>
          <p className="fl-hint">{t('mst.approvalNote')}</p>
          <div className="rf-actbar">
            <button type="button" className="btn" onClick={() => setEditing(null)}>
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className="btn pri"
              disabled={!reason.trim() || !draft.effectiveFrom || proposal.isPending}
              onClick={() => void submit(editing)}
            >
              {t('common.submitForApproval')}
            </button>
          </div>
        </Card>
      )}
    </div>
  );
}
