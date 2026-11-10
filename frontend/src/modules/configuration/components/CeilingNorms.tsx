'use client';

import { useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions, useReference } from '@/shared/reference';
import { can, hqOnlyBlock, useMe } from '@/shared/session';
import { Card, date, Problem, ServerPagination, Src, useToast, TextAreaField } from '@/shared/ui';
import { businessToday } from '@/shared/time';
import type { NormRow, NormVersion, ProposedNorm } from '../api/mdmApi';
import { MEMBER_SCOPES } from '../business/mdmRules';
import { mdmKeys, mdmPages, useCurrentCirculars, useProposeNorm } from '../orchestration/mdmQueries';

type Draft = Omit<ProposedNorm, 'reason'>;

/**
 * M-15 Allowance & Reimbursement Ceiling Norms (SRS-16-14): one dated ceiling per claim type. Claim
 * types and eligibility categories are M-16 lists FCI fills; a claim type without an approved ceiling is
 * not configured, and the claim engine must not pay against an unconfirmed one. Each revision links its
 * Active M-25 circular (SRS-G-38, US-GAP-211).
 */
export function CeilingNorms() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const hqBlock = hqOnlyBlock(me.data, 'M-15');
  const reference = useReference();
  const propose = useProposeNorm();
  const circulars = useCurrentCirculars();
  const [historyOf, setHistoryOf] = useState<string | null>(null);
  const [editing, setEditing] = useState<NormRow | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>(null);
  const mayCreate = can(me.data, 'M-15', 'CREATE');

  function normText(v: NormVersion): string {
    return `₹ ${v.ceilingValue} · ${t(`m15.periodicity.${v.periodicity}`)} · ${reference.label('ELIGIBILITY_CATEGORY', v.eligibilityCategory)}`;
  }

  function startEdit(row: NormRow) {
    const c = row.current;
    setEditing(row);
    setDraft({
      effectiveFrom: businessToday(),
      ceilingBasis: c?.ceilingBasis ?? null,
      ceilingValue: c?.ceilingValue ?? null,
      periodicity: c?.periodicity ?? 'MONTHLY',
      eligibilityCategory: c?.eligibilityCategory ?? '',
      memberScope: c?.memberScope ?? 'BOTH',
      confirmed: false,
      circularOrderId: null,
    });
    setReason('');
    setError(null);
  }

  async function submit() {
    if (!editing || !draft) return;
    setError(null);
    try {
      await propose.mutateAsync({ claimType: editing.claimType, body: { ...draft, reason } });
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
        <span className="sub2">{t('mst.hint.M-15')}</span>
      </div>
      <Problem error={editing ? null : error} />
      <Card title={t('screen.M-15')}>
        <div className="wrap">
          <ServerPagination
            queryKey={mdmKeys.norms}
            fetchPage={mdmPages.norms}
            personal={false}
            empty={<p className="sub2">{t('m15.noClaimTypes')}</p>}
          >
            {(rows) => (
              <table className="t">
                <thead>
                  <tr>
                    <th>
                      {t('m15.claimType')}
                      <Src s="RULE" />
                    </th>
                    <th>
                      {t('m15.ceiling')}
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
                    <tr key={row.claimType}>
                      <td>
                        <b>{reference.label('CLAIM_TYPE', row.claimType) || row.name}</b>
                        <div className="sub2">{row.claimType}</div>
                      </td>
                      <td>
                        {row.current ? (
                          <>
                            {normText(row.current)}
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
                            onClick={() => setHistoryOf(historyOf === row.claimType ? null : row.claimType)}
                          >
                            {t('mst.history')}
                          </button>
                          <HistoryButton entity="allowance_ceiling_norm" recordKey={row.claimType} />
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
        <Card title={t('mst.historyOf', [historyOf])}>
          <div className="wrap">
            <ServerPagination
              queryKey={mdmKeys.normHistory(historyOf)}
              fetchPage={mdmPages.normHistory(historyOf)}
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
                        {t('m15.ceiling')}
                        <Src s="RULE" />
                      </th>
                      <th>
                        {t('mst.circular')}
                        <Src s="ENT" />
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
                        <td>{normText(v)}</td>
                        <td>
                          {v.circularOrderId === null
                            ? '—'
                            : ((circulars.data ?? []).find((c) => c.id === v.circularOrderId)?.number ??
                              `#${v.circularOrderId}`)}
                        </td>
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
        <Card title={t('mst.newVersionOf', [editing.name])}>
          <Problem error={error} />
          <div className="bd rf-grid">
            <div className="rf-field">
              <label htmlFor="cn-from">{t('mst.from')}</label>
              <input
                id="cn-from"
                type="date"
                value={draft.effectiveFrom}
                onChange={(e) => setDraft({ ...draft, effectiveFrom: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="cn-value">{t('m15.ceilingValue')}</label>
              <input
                id="cn-value"
                type="number"
                min={0.01}
                step="0.01"
                value={draft.ceilingValue ?? ''}
                onChange={(e) =>
                  setDraft({ ...draft, ceilingValue: e.target.value === '' ? null : Number(e.target.value) })
                }
              />
            </div>
            <div className="rf-field">
              <label htmlFor="cn-period">{t('m15.periodicity')}</label>
              <select
                id="cn-period"
                value={draft.periodicity}
                onChange={(e) => setDraft({ ...draft, periodicity: e.target.value })}
              >
                <ReferenceOptions list="ALLOWANCE_PERIODICITY" />
              </select>
            </div>
            <div className="rf-field">
              <label htmlFor="cn-elig">{t('m15.eligibility')}</label>
              <select
                id="cn-elig"
                value={draft.eligibilityCategory}
                onChange={(e) => setDraft({ ...draft, eligibilityCategory: e.target.value })}
              >
                <option value="">{t('common.choose')}</option>
                <ReferenceOptions list="ELIGIBILITY_CATEGORY" />
              </select>
            </div>
            <div className="rf-field">
              <label htmlFor="cn-basis">{t('m15.basis')}</label>
              <select
                id="cn-basis"
                value={draft.ceilingBasis ?? ''}
                onChange={(e) => setDraft({ ...draft, ceilingBasis: e.target.value || null })}
              >
                <option value="">{t('m15.basisFromRules')}</option>
                <ReferenceOptions list="CEILING_BASIS" />
              </select>
            </div>
            <div className="rf-field">
              <label htmlFor="cn-scope">{t('m15.memberScope')}</label>
              <select
                id="cn-scope"
                value={draft.memberScope}
                onChange={(e) => setDraft({ ...draft, memberScope: e.target.value })}
              >
                {MEMBER_SCOPES.map((s) => (
                  <option key={s} value={s}>
                    {t(`m15.memberScope.${s}`)}
                  </option>
                ))}
              </select>
            </div>
            <div className="rf-field">
              <label htmlFor="cn-conf">
                <input
                  id="cn-conf"
                  type="checkbox"
                  checked={draft.confirmed}
                  onChange={(e) => setDraft({ ...draft, confirmed: e.target.checked })}
                />{' '}
                {t('mst.confirmed')}
              </label>
              <span className="hint">{t('mst.confirmedHint')}</span>
            </div>
            <div className="rf-field span2">
              <label htmlFor="cn-circular">{t('mst.circular')}</label>
              <select
                id="cn-circular"
                value={draft.circularOrderId ?? ''}
                onChange={(e) =>
                  setDraft({
                    ...draft,
                    circularOrderId: e.target.value === '' ? null : Number(e.target.value),
                  })
                }
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
            <div className="rf-field span2">
              <label htmlFor="cn-reason">{t('common.reason')}</label>
              <TextAreaField
                id="cn-reason"
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
              disabled={
                !reason.trim() ||
                !draft.effectiveFrom ||
                !draft.eligibilityCategory ||
                draft.ceilingValue === null ||
                draft.circularOrderId === null ||
                propose.isPending
              }
              title={draft.circularOrderId === null ? t('mst.why.circular') : undefined}
              onClick={() => void submit()}
            >
              {t('common.submitForApproval')}
            </button>
          </div>
        </Card>
      )}
    </div>
  );
}
