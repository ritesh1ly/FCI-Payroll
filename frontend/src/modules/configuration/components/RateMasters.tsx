'use client';

import { useEffect, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { can, hqOnlyBlock, useMe } from '@/shared/session';
import { Card, date, Problem, ServerPagination, Src, useToast, TextAreaField } from '@/shared/ui';
import { businessToday } from '@/shared/time';
import type { RateMaster, RateScreen } from '../api/mastersApi';
import {
  emptyLine,
  lineKey,
  linesProblem,
  linesValid,
  type RateDraftLine,
  summarise,
} from '../business/masterRules';
import { masterKeys, masterPages, useProposeRate } from '../orchestration/masterQueries';
import { useCurrentCirculars } from '../orchestration/mdmQueries';

/**
 * M-04 Staff Rate Tables (DA / HRA / CCA) and M-22 Special Compensatory & Allowance Rate Masters:
 * live data of rate_set / rate_set_line (target DDL v4.0). Each master shows its version in force
 * (or "not seeded"), a pending proposal and its history; a change is a new dated version submitted
 * for approval, never an edit of the version in force. A staff rate revision (SRS-1-62) and an M-22
 * rate change (SRS l.2819, SRS-G-38; US-GAP-211) link their Active M-25 circular before submission.
 */
export function RateMasters({
  screen,
  edit = null,
  onEditing,
}: {
  screen: RateScreen;
  /** A drawn row's ✎ Edit: opens the version editor of this master. */
  edit?: RateMaster | null;
  onEditing?: () => void;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const hqBlock = hqOnlyBlock(me.data, screen);
  const proposal = useProposeRate(screen);
  const [historyOf, setHistoryOf] = useState<string | null>(null);
  const [editing, setEditing] = useState<RateMaster | null>(null);
  const [from, setFrom] = useState(businessToday());
  const [lines, setLines] = useState<RateDraftLine[]>([]);
  const [reason, setReason] = useState('');
  const [circularId, setCircularId] = useState<number | null>(null);
  const [error, setError] = useState<unknown>(null);
  const mayCreate = can(me.data, screen, 'CREATE');
  const circulars = useCurrentCirculars();

  function startEdit(master: RateMaster) {
    setEditing(master);
    setFrom(businessToday());
    setLines(master.current?.lines.map((l) => ({ ...l })) ?? [emptyLine(screen === 'M-04')]);
    setReason('');
    setCircularId(master.current?.circularOrderId ?? null);
    setError(null);
  }

  useEffect(() => {
    if (!edit) return;
    startEdit(edit);
    onEditing?.();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- runs once per drawn Edit
  }, [edit]);

  function setLine(index: number, patch: Partial<RateDraftLine>) {
    setLines((current) => current.map((l, i) => (i === index ? { ...l, ...patch } : l)));
  }

  // M-04 field table (SRS l.3651, l.3653): Rate Value and Pay Pattern are mandatory; M-22 asks the value.
  const linesWhy = linesProblem(lines, screen === 'M-04');

  async function submit(editing: RateMaster) {
    setError(null);
    // Checked again here: only lines with every value entered are sent (a blank is never zero).
    if (!linesValid(lines, screen === 'M-04')) return;
    try {
      await proposal.mutateAsync({
        code: editing.code,
        effectiveFrom: from,
        lines,
        reason,
        circularOrderId: circularId,
      });
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
        <span className="sub2">{t(`mst.hint.${screen}`)}</span>
      </div>
      <Problem error={editing ? null : error} />
      <ServerPagination
        queryKey={masterKeys.rates(screen)}
        fetchPage={masterPages.rates(screen)}
        personal={false}
      >
        {(rows) => (
          <>
            {Array.from(new Set(rows.map((m) => m.group ?? ''))).map((group) => (
              <Card key={group} title={t(`mst.group.${group}`)}>
                <div className="wrap">
                  <table className="t">
                    <thead>
                      <tr>
                        <th>
                          {t('mst.master')}
                          <Src s="ENT" />
                        </th>
                        <th>
                          {t('mst.inForce')}
                          <Src s="SYS" />
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
                      {rows
                        .filter((m) => (m.group ?? '') === group)
                        .map((m) => (
                          <tr key={m.code}>
                            <td>
                              <b>{m.name}</b>
                              <div className="sub2">
                                {m.code}
                                {m.payCode !== null
                                  ? ` · ${t('mst.payCode', [m.payCode])}`
                                  : ` · ${t('mst.notInFrs')}`}
                              </div>
                            </td>
                            <td>
                              {m.current ? (
                                summarise(m.current.lines)
                              ) : (
                                <span className="pill p-bad">{t('mst.notSeeded')}</span>
                              )}
                            </td>
                            <td>
                              {m.current ? (
                                <>
                                  v{m.current.versionNo}
                                  <div className="sub2">
                                    {date(m.current.effectiveFrom)} –{' '}
                                    {m.current.effectiveTo ? date(m.current.effectiveTo) : t('mst.open')}
                                  </div>
                                </>
                              ) : (
                                '—'
                              )}
                            </td>
                            <td>
                              {m.pending ? (
                                <span className="pill p-warn">
                                  {t('mst.pendingVersion', [
                                    m.pending.versionNo,
                                    date(m.pending.effectiveFrom),
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
                                  onClick={() => setHistoryOf(historyOf === m.code ? null : m.code)}
                                >
                                  {t('mst.history')}
                                </button>
                                <HistoryButton entity="rate_set" recordKey={m.code} />
                                {mayCreate && !m.pending && (
                                  <button
                                    type="button"
                                    disabled={hqBlock !== null}
                                    title={hqBlock ? t(hqBlock) : undefined}
                                    className="ia"
                                    onClick={() => startEdit(m)}
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
                </div>
              </Card>
            ))}
          </>
        )}
      </ServerPagination>
      {historyOf && (
        <Card title={t('mst.historyOf', [historyOf])}>
          <div className="wrap">
            <ServerPagination
              queryKey={masterKeys.rateHistory(screen, historyOf)}
              fetchPage={masterPages.rateHistory(screen, historyOf)}
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
                        {t('mst.rates')}
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
                        <td>
                          <span
                            className={`pill ${v.state === 'APPROVED' ? 'p-ok' : v.state === 'REJECTED' ? 'p-bad' : 'p-warn'}`}
                          >
                            {t(`mst.state.${v.state}`)}
                          </span>
                        </td>
                        <td>{summarise(v.lines)}</td>
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
              <label htmlFor="mst-from">{t('mst.from')}</label>
              <input id="mst-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
              <span className="hint">{t('mst.fromHint')}</span>
            </div>
          </div>
          <div className="wrap">
            <table className="t">
              <thead>
                <tr>
                  <th>
                    {t('mst.pattern')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('mst.cityClass')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('mst.category')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('mst.slabFrom')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('mst.slabTo')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('mst.rateCode')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('mst.value')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('mst.unit')}
                    <Src s="RULE" />
                  </th>
                  <th aria-label={t('common.actions')} />
                </tr>
              </thead>
              <tbody>
                {lines.map((l, i) => (
                  <tr key={i} title={lineKey(l)}>
                    <td>
                      <select
                        aria-label={t('mst.pattern')}
                        required={screen === 'M-04'}
                        aria-invalid={screen === 'M-04' && !l.payPattern}
                        value={l.payPattern ?? ''}
                        onChange={(e) => setLine(i, { payPattern: e.target.value || null })}
                      >
                        <option value="">—</option>
                        <ReferenceOptions list="PAY_PATTERN" />
                      </select>
                    </td>
                    <td>
                      <select
                        aria-label={t('mst.cityClass')}
                        value={l.cityClass ?? ''}
                        onChange={(e) => setLine(i, { cityClass: e.target.value || null })}
                      >
                        <option value="">—</option>
                        <ReferenceOptions list="CITY_CLASS" />
                      </select>
                    </td>
                    <td>
                      <input
                        aria-label={t('mst.category')}
                        maxLength={10}
                        value={l.categoryCode ?? ''}
                        onChange={(e) => setLine(i, { categoryCode: e.target.value || null })}
                      />
                    </td>
                    <td>
                      <input
                        aria-label={t('mst.slabFrom')}
                        type="number"
                        min={0}
                        value={l.basicSlabFrom ?? ''}
                        onChange={(e) =>
                          setLine(i, { basicSlabFrom: e.target.value === '' ? null : Number(e.target.value) })
                        }
                      />
                    </td>
                    <td>
                      <input
                        aria-label={t('mst.slabTo')}
                        type="number"
                        min={0}
                        value={l.basicSlabTo ?? ''}
                        onChange={(e) =>
                          setLine(i, { basicSlabTo: e.target.value === '' ? null : Number(e.target.value) })
                        }
                      />
                    </td>
                    <td>
                      <input
                        aria-label={t('mst.rateCode')}
                        maxLength={40}
                        value={l.rateCode}
                        onChange={(e) => setLine(i, { rateCode: e.target.value.toUpperCase() })}
                      />
                    </td>
                    <td>
                      <input
                        aria-label={t('mst.value')}
                        type="number"
                        min={0}
                        step="0.01"
                        required
                        aria-invalid={l.rateValue === null}
                        value={l.rateValue ?? ''}
                        onChange={(e) =>
                          setLine(i, { rateValue: e.target.value === '' ? null : Number(e.target.value) })
                        }
                      />
                    </td>
                    <td>
                      <select
                        aria-label={t('mst.unit')}
                        value={l.unit ?? ''}
                        onChange={(e) => setLine(i, { unit: e.target.value || null })}
                      >
                        <ReferenceOptions list="RATE_UNIT" />
                      </select>
                    </td>
                    <td>
                      <button
                        type="button"
                        className="ia d"
                        onClick={() => setLines((c) => c.filter((_, j) => j !== i))}
                      >
                        {t('mst.removeLine')}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="rf-actbar">
            <button
              type="button"
              className="btn"
              onClick={() => setLines((c) => [...c, emptyLine(screen === 'M-04')])}
            >
              {t('mst.addLine')}
            </button>
          </div>
          <div className="bd rf-grid">
            {
              <div className="rf-field span2">
                <label htmlFor="mst-circular">{t('mst.circular')}</label>
                <select
                  id="mst-circular"
                  value={circularId ?? ''}
                  onChange={(e) => setCircularId(e.target.value === '' ? null : Number(e.target.value))}
                >
                  <option value="">{t('common.choose')}</option>
                  {(circulars.data ?? []).map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.number} · {c.subject}
                    </option>
                  ))}
                </select>
                {(circulars.data ?? []).length === 0 && (
                  <span className="hint">{t('m27.noActiveCircular')}</span>
                )}
              </div>
            }
            <div className="rf-field span2">
              <label htmlFor="mst-reason">{t('common.reason')}</label>
              <TextAreaField
                id="mst-reason"
                value={reason}
                maxLength={500}
                onChange={(e) => setReason(e.target.value)}
              />
            </div>
          </div>
          {linesWhy !== null && lines.length > 0 && (
            <p className="fl-hint" data-testid="mst-lines-why">
              {t(linesWhy)}
            </p>
          )}
          <p className="fl-hint">{t('mst.approvalNote')}</p>
          <div className="rf-actbar">
            <button type="button" className="btn" onClick={() => setEditing(null)}>
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className="btn pri"
              disabled={
                !reason.trim() || !from || linesWhy !== null || circularId === null || proposal.isPending
              }
              title={
                circularId === null ? t('mst.why.circular') : linesWhy !== null ? t(linesWhy) : undefined
              }
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
