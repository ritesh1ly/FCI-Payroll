'use client';

import { useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { can, hqOnlyBlock, useMe } from '@/shared/session';
import { Card, date, Problem, ServerPagination, Src, useToast, TextAreaField } from '@/shared/ui';
import { businessToday } from '@/shared/time';
import type { FloorRow, FloorVersion, ProposedFloor } from '../api/mdmApi';
import { FLOOR_BASES } from '../business/mdmRules';
import {
  mdmKeys,
  mdmPages,
  useExclusions,
  useProposeExclusions,
  useProposeFloor,
} from '../orchestration/mdmQueries';

type Draft = Omit<ProposedFloor, 'reason'>;

function floorText(t: (k: string, a?: readonly unknown[]) => string, v: FloorVersion): string {
  if (v.floorBasis === 'NONE') return t('m14.basis.NONE');
  const value = v.floorBasis === 'PERCENT_OF_GROSS' ? `${v.floorValue ?? '—'} %` : `₹ ${v.floorValue ?? '—'}`;
  return `${t(`m14.basis.${v.floorBasis}`)}: ${value}`;
}

/**
 * M-14 Penalty & Recovery Rule Configuration (SRS-16-13): the net-pay floor of each payroll type as
 * dated versions, and the pay codes the supplementary run skips. A type without an approved floor is
 * shown as not configured: no floor is guessed (values are settings from the current system).
 */
export function NetPayFloors() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const hqBlock = hqOnlyBlock(me.data, 'M-14');
  const exclusions = useExclusions();
  const propose = useProposeFloor();
  const proposeExclusions = useProposeExclusions();
  const [historyOf, setHistoryOf] = useState<string | null>(null);
  const [editing, setEditing] = useState<FloorRow | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [excluded, setExcluded] = useState<number[] | null>(null);
  const [exclusionReason, setExclusionReason] = useState('');
  const mayCreate = can(me.data, 'M-14', 'CREATE');
  const mayEdit = can(me.data, 'M-14', 'EDIT');
  const pendingExclusions = exclusions.data?.pending ?? null;

  function startEdit(row: FloorRow) {
    const c = row.current;
    setEditing(row);
    setDraft({
      effectiveFrom: businessToday(),
      floorBasis: c?.floorBasis ?? 'ABSOLUTE_AMOUNT',
      floorValue: c?.floorValue ?? null,
      deductionCeilingPct: c?.deductionCeilingPct ?? null,
      sourceInstrument: c?.sourceInstrument ?? null,
      confirmed: false,
    });
    setReason('');
    setError(null);
  }

  const num = (v: string) => (v === '' ? null : Number(v));

  async function submit() {
    if (!editing || !draft) return;
    setError(null);
    try {
      await propose.mutateAsync({ type: editing.payrollType, body: { ...draft, reason } });
      toast(t('mst.submitted', [t(`m14.type.${editing.payrollType}`)]));
      setEditing(null);
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  async function submitExclusions(excluded: number[]) {
    setError(null);
    try {
      await proposeExclusions.mutateAsync({ payCodes: excluded, reason: exclusionReason });
      toast(t('m14.exclusionsSubmitted'));
      setExcluded(null);
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  const components = exclusions.data?.components ?? [];

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sub2">{t('mst.hint.M-14')}</span>
      </div>
      <Problem error={editing || excluded ? null : error} />
      <Card title={t('m14.floors')}>
        <div className="wrap">
          <ServerPagination queryKey={mdmKeys.floors} fetchPage={mdmPages.floors} personal={false}>
            {(rows) => (
              <table className="t">
                <thead>
                  <tr>
                    <th>
                      {t('m14.payrollType')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('m14.floor')}
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
                    <tr key={row.payrollType}>
                      <td>
                        <b>{t(`m14.type.${row.payrollType}`)}</b>
                        <div className="sub2">{row.payrollType}</div>
                      </td>
                      <td>
                        {row.current ? (
                          <>
                            {floorText(t, row.current)}
                            {!row.current.confirmed && (
                              <span className="pill p-warn">{t('mst.unconfirmed')}</span>
                            )}
                          </>
                        ) : (
                          <span className="pill p-bad">{t('mst.notConfigured')}</span>
                        )}
                      </td>
                      <td>
                        {row.current ? (
                          <>
                            {date(row.current.effectiveFrom)} –{' '}
                            {row.current.effectiveTo ? date(row.current.effectiveTo) : t('mst.open')}
                          </>
                        ) : (
                          '—'
                        )}
                      </td>
                      <td>
                        {row.pending ? (
                          <span className="pill p-warn">
                            {t('mst.pendingFrom', [date(row.pending.effectiveFrom)])}
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
                            onClick={() =>
                              setHistoryOf(historyOf === row.payrollType ? null : row.payrollType)
                            }
                          >
                            {t('mst.history')}
                          </button>
                          <HistoryButton entity="net_pay_floor_rule" recordKey={row.payrollType} />
                          {mayCreate && !row.pending && (
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
        <Card title={t('mst.historyOf', [t(`m14.type.${historyOf}`)])}>
          <div className="wrap">
            <ServerPagination
              queryKey={mdmKeys.floorHistory(historyOf)}
              fetchPage={mdmPages.floorHistory(historyOf)}
              personal={false}
            >
              {(versions) => (
                <table className="t">
                  <thead>
                    <tr>
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
                        {t('m14.floor')}
                        <Src s="RULE" />
                      </th>
                      <th>
                        {t('m14.deductionCeiling')}
                        <Src s="RULE" />
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {versions.map((v) => (
                      <tr key={v.id}>
                        <td>{date(v.effectiveFrom)}</td>
                        <td>{v.effectiveTo ? date(v.effectiveTo) : '—'}</td>
                        <td>{t(`status.${v.status}`)}</td>
                        <td>{floorText(t, v)}</td>
                        <td>{v.deductionCeilingPct === null ? '—' : `${v.deductionCeilingPct} %`}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </ServerPagination>
          </div>
        </Card>
      )}
      {editing && draft && (
        <Card title={t('mst.newVersionOf', [t(`m14.type.${editing.payrollType}`)])}>
          <Problem error={error} />
          <div className="bd rf-grid">
            <div className="rf-field">
              <label htmlFor="fl-from">{t('mst.from')}</label>
              <input
                id="fl-from"
                type="date"
                value={draft.effectiveFrom}
                onChange={(e) => setDraft({ ...draft, effectiveFrom: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="fl-basis">{t('m14.basis')}</label>
              <select
                id="fl-basis"
                value={draft.floorBasis}
                onChange={(e) => setDraft({ ...draft, floorBasis: e.target.value as Draft['floorBasis'] })}
              >
                {FLOOR_BASES.map((b) => (
                  <option key={b} value={b}>
                    {t(`m14.basis.${b}`)}
                  </option>
                ))}
              </select>
            </div>
            <div className="rf-field">
              <label htmlFor="fl-value">{t('m14.floorValue')}</label>
              <input
                id="fl-value"
                type="number"
                min={0}
                step="0.01"
                disabled={draft.floorBasis === 'NONE'}
                value={draft.floorValue ?? ''}
                onChange={(e) => setDraft({ ...draft, floorValue: num(e.target.value) })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="fl-ceiling">{t('m14.deductionCeiling')}</label>
              <input
                id="fl-ceiling"
                type="number"
                min={0}
                max={100}
                step="0.0001"
                value={draft.deductionCeilingPct ?? ''}
                onChange={(e) => setDraft({ ...draft, deductionCeilingPct: num(e.target.value) })}
              />
            </div>
            <div className="rf-field span2">
              <label htmlFor="fl-source">{t('m14.sourceInstrument')}</label>
              <TextAreaField
                id="fl-source"
                maxLength={300}
                value={draft.sourceInstrument ?? ''}
                onChange={(e) => setDraft({ ...draft, sourceInstrument: e.target.value || null })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="fl-conf">
                <input
                  id="fl-conf"
                  type="checkbox"
                  checked={draft.confirmed}
                  onChange={(e) => setDraft({ ...draft, confirmed: e.target.checked })}
                />{' '}
                {t('mst.confirmed')}
              </label>
              <span className="hint">{t('mst.confirmedHint')}</span>
            </div>
            <div className="rf-field span2">
              <label htmlFor="fl-reason">{t('common.reason')}</label>
              <TextAreaField
                id="fl-reason"
                value={reason}
                maxLength={500}
                onChange={(e) => setReason(e.target.value)}
              />
            </div>
          </div>
          <p className="fl-hint">{t('mst.checkerNote')}</p>
          <div className="rf-actbar">
            <button
              type="button"
              className="btn"
              onClick={() => {
                setEditing(null);
                setError(null);
              }}
            >
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className="btn pri"
              // SRS l.1385 (UI_BMAD_g1 #54): the floor value is mandatory unless the basis is NONE, as
              // the server checks (MST-422-FLOOR).
              disabled={
                !reason.trim() ||
                !draft.effectiveFrom ||
                (draft.floorBasis !== 'NONE' && draft.floorValue === null) ||
                propose.isPending
              }
              title={
                draft.floorBasis !== 'NONE' && draft.floorValue === null ? t('m14.valueRequired') : undefined
              }
              onClick={() => void submit()}
            >
              {t('common.submitForApproval')}
            </button>
          </div>
        </Card>
      )}
      <Card title={t('m14.exclusions')}>
        <Problem error={exclusions.error ?? (excluded ? error : null)} />
        <p className="sub2">{t('m14.exclusionsHint')}</p>
        {pendingExclusions && (
          <p>
            <span className="pill p-warn">
              {t('m14.exclusionsPending', [pendingExclusions.join(', ') || '—'])}
            </span>
          </p>
        )}
        {components.length === 0 ? (
          <p className="sub2">{t('mst.noPayCodes')}</p>
        ) : (
          <div className="wrap">
            <table className="t">
              <thead>
                <tr>
                  <th>
                    {t('mst.payCodeCol')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('mst.component')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m14.excluded')}
                    <Src s="RULE" />
                  </th>
                </tr>
              </thead>
              <tbody>
                {components.map((c) => {
                  const checked = excluded ? excluded.includes(c.payCode) : c.excluded;
                  return (
                    <tr key={c.payCode}>
                      <td>{c.payCode}</td>
                      <td>
                        {c.name}
                        <div className="sub2">{t(`mst.componentType.${c.type}`)}</div>
                      </td>
                      <td>
                        <input
                          type="checkbox"
                          aria-label={t('m14.excludeCode', [c.payCode])}
                          checked={checked}
                          disabled={!excluded}
                          onChange={(e) =>
                            setExcluded(
                              (excluded ?? [])
                                .filter((p) => p !== c.payCode)
                                .concat(e.target.checked ? [c.payCode] : []),
                            )
                          }
                        />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        {mayEdit && !pendingExclusions && !excluded && components.length > 0 && (
          <div className="rf-actbar">
            <button
              disabled={hqBlock !== null}
              title={hqBlock ? t(hqBlock) : undefined}
              type="button"
              className="btn"
              onClick={() => {
                setExcluded(components.filter((c) => c.excluded).map((c) => c.payCode));
                setExclusionReason('');
                setError(null);
              }}
            >
              {t('action.changeExclusions')}
            </button>
          </div>
        )}
        {excluded && (
          <>
            <div className="bd rf-grid">
              <div className="rf-field span2">
                <label htmlFor="ex-reason">{t('common.reason')}</label>
                <TextAreaField
                  id="ex-reason"
                  value={exclusionReason}
                  maxLength={500}
                  onChange={(e) => setExclusionReason(e.target.value)}
                />
              </div>
            </div>
            <div className="rf-actbar">
              <button
                type="button"
                className="btn"
                onClick={() => {
                  setExcluded(null);
                  setError(null);
                }}
              >
                {t('common.cancel')}
              </button>
              <button
                type="button"
                className="btn pri"
                disabled={!exclusionReason.trim() || proposeExclusions.isPending}
                onClick={() => void submitExclusions(excluded)}
              >
                {t('common.submitForApproval')}
              </button>
            </div>
          </>
        )}
      </Card>
    </div>
  );
}
