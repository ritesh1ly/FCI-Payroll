'use client';

import { useState } from 'react';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { can, hqOnlyBlock, useMe } from '@/shared/session';
import { Card, Problem, ServerPagination, Src, useToast, TextAreaField } from '@/shared/ui';
import type { RetentionClass } from '../api/retentionApi';
import { MAX_RETENTION_MONTHS, validRetention } from '../business/retention';
import { retentionKeys, retentionPages, useProposeRetention } from '../orchestration/retentionQueries';

/**
 * A-07 audit retention periods (SRS-20-5): how long the native audit trail and the Legacy Audit
 * table are kept, set by FCI policy. Until FCI sets one the period is "not decided"; nothing is
 * ever purged by the application. A change takes effect once a Headquarters checker approves it.
 */
export function RetentionPeriods() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const hqBlock = hqOnlyBlock(me.data, 'A-07');
  const proposal = useProposeRetention();
  const [editing, setEditing] = useState<RetentionClass | null>(null);
  const [months, setMonths] = useState('');
  const [indefinite, setIndefinite] = useState(false);
  const [basis, setBasis] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>(null);
  const mayEdit = can(me.data, 'A-07', 'EDIT');

  function period(c: RetentionClass): string {
    if (!c.confirmed) return t('a07.retention.notDecided');
    return c.indefinite ? t('a07.retention.indefinite') : t('a07.retention.months', [c.months ?? '']);
  }

  async function propose(editing: RetentionClass) {
    setError(null);
    try {
      await proposal.mutateAsync({
        code: editing.code,
        months: indefinite ? null : Number(months),
        indefinite,
        basis: basis.trim(),
        reason: reason.trim(),
      });
      setEditing(null);
      toast(t('a07.retention.proposed'));
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  return (
    <Card title={t('a07.retention.title')}>
      <p className="fl-hint">{t('a07.retention.note')}</p>
      <Problem error={error} />
      <div className="wrap">
        <ServerPagination queryKey={retentionKeys.all} fetchPage={retentionPages.list} personal={false}>
          {(classes) => (
            <table className="t">
              <thead>
                <tr>
                  <th>
                    {t('a07.retention.class')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('a07.retention.period')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('a07.retention.basis')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('a07.pending')}
                    <Src s="SYS" />
                  </th>
                  <th aria-label={t('common.actions')} />
                </tr>
              </thead>
              <tbody>
                {classes.map((c) => (
                  <tr key={c.code}>
                    <td>
                      <b>{t(`a07.retention.class.${c.code}`)}</b>
                      <div className="sub2">{c.code}</div>
                    </td>
                    <td>
                      <span className={c.confirmed ? 'pill p-ok' : 'pill p-bad'}>{period(c)}</span>
                    </td>
                    <td>{c.basis ?? '—'}</td>
                    <td>
                      {c.pendingApprovalId ? (
                        <span className="pill p-warn">
                          {t('a07.retention.pending', [c.pendingApprovalId])}
                        </span>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td>
                      {mayEdit && !c.pendingApprovalId && (
                        <button
                          disabled={hqBlock !== null}
                          title={hqBlock ? t(hqBlock) : undefined}
                          type="button"
                          className="ia"
                          onClick={() => {
                            setEditing(c);
                            setMonths(c.months === null ? '' : String(c.months));
                            setIndefinite(c.indefinite);
                            setBasis('');
                            setReason('');
                            setError(null);
                          }}
                        >
                          {t('a07.retention.propose')}
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </ServerPagination>
      </div>
      {editing && (
        <div className="bd rf-grid">
          <h4 className="span2">
            {t('a07.retention.editTitle', [t(`a07.retention.class.${editing.code}`)])}
          </h4>
          <div className="rf-field">
            <label htmlFor="ret-months">{t('a07.retention.monthsLabel')}</label>
            <input
              id="ret-months"
              type="number"
              min={1}
              max={MAX_RETENTION_MONTHS}
              disabled={indefinite}
              value={indefinite ? '' : months}
              onChange={(e) => setMonths(e.target.value)}
            />
          </div>
          <label className="rf-field fl-choice">
            <input
              type="checkbox"
              checked={indefinite}
              onChange={(e) => {
                setIndefinite(e.target.checked);
                if (e.target.checked) setMonths('');
              }}
            />{' '}
            {t('a07.retention.indefiniteLabel')}
          </label>
          <div className="rf-field span2">
            <label htmlFor="ret-basis">{t('a07.retention.basis')}</label>
            <input id="ret-basis" value={basis} maxLength={200} onChange={(e) => setBasis(e.target.value)} />
          </div>
          <div className="rf-field span2">
            <label htmlFor="ret-reason">{t('common.reason')}</label>
            <TextAreaField
              id="ret-reason"
              value={reason}
              maxLength={500}
              onChange={(e) => setReason(e.target.value)}
            />
          </div>
          <div className="rf-actbar span2">
            <button type="button" className="btn" onClick={() => setEditing(null)}>
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className="btn pri"
              disabled={proposal.isPending || !validRetention({ months, indefinite, basis, reason })}
              onClick={() => void propose(editing)}
            >
              {t('common.submitForApproval')}
            </button>
          </div>
        </div>
      )}
    </Card>
  );
}
