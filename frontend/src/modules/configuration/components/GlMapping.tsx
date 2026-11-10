'use client';

import { useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { can, hqOnlyBlock, useMe } from '@/shared/session';
import { Card, date, Problem, ServerPagination, Src, useToast, TextAreaField } from '@/shared/ui';
import { businessToday } from '@/shared/time';
import type { ComponentRow } from '../api/mastersApi';
import { useProposeMapping, masterKeys, masterPages } from '../orchestration/masterQueries';

/**
 * M-24 Accounting & Bank Mapping: the natural account of each pay code, live data of
 * gl_account_mapping over pay_component (target DDL v4.0 S23, S07). An unmapped pay code is flagged:
 * its posting is held on the exceptions worklist, never sent to a suspense account.
 */
export function GlRegister() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const hqBlock = hqOnlyBlock(me.data, 'M-24');
  const proposal = useProposeMapping();
  const [historyOf, setHistoryOf] = useState<number | null>(null);
  const [editing, setEditing] = useState<ComponentRow | null>(null);
  const [from, setFrom] = useState(businessToday());
  const [account, setAccount] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>(null);
  const mayCreate = can(me.data, 'M-24', 'CREATE');

  async function submit(editing: ComponentRow) {
    setError(null);
    try {
      await proposal.mutateAsync({
        payCode: editing.payCode,
        effectiveFrom: from,
        naturalAccount: account,
        reason,
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
        <span className="sub2">{t('mst.hint.M-24')}</span>
      </div>
      <Problem error={editing ? null : error} />
      <Card title={t('mst.payCodeAccounts')}>
        <div className="wrap">
          <ServerPagination
            queryKey={masterKeys.mappings}
            fetchPage={masterPages.mappings}
            empty={<p className="fl-hint">{t('mst.noPayCodes')}</p>}
            personal={false}
          >
            {(rows) => (
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
                      {t('mst.naturalAccount')}
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
                  {rows.map((c) => (
                    <tr key={c.payCode}>
                      <td>
                        <b>{c.payCode}</b>
                      </td>
                      <td>
                        {c.name}
                        <div className="sub2">
                          {t(`mst.componentType.${c.type}`)}
                          {c.payslipGroup ? ` · ${c.payslipGroup}` : ''}
                          {c.retired ? ` · ${t('mst.retired')}` : ''}
                        </div>
                      </td>
                      <td>
                        {c.current ? (
                          c.current.naturalAccount
                        ) : (
                          <span className="pill p-bad">{t('mst.notMapped')}</span>
                        )}
                      </td>
                      <td>
                        {c.current ? (
                          <>
                            v{c.current.versionNo}
                            <div className="sub2">{date(c.current.effectiveFrom)}</div>
                          </>
                        ) : (
                          '—'
                        )}
                      </td>
                      <td>
                        {c.pending ? (
                          <span className="pill p-warn">
                            {c.pending.naturalAccount} ·{' '}
                            {t('mst.pendingVersion', [c.pending.versionNo, date(c.pending.effectiveFrom)])}
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
                            onClick={() => setHistoryOf(historyOf === c.payCode ? null : c.payCode)}
                          >
                            {t('mst.history')}
                          </button>
                          <HistoryButton entity="gl_account_mapping" recordKey={String(c.payCode)} />
                          {mayCreate && !c.pending && !c.retired && (
                            <button
                              disabled={hqBlock !== null}
                              title={hqBlock ? t(hqBlock) : undefined}
                              type="button"
                              className="ia"
                              onClick={() => {
                                setEditing(c);
                                setFrom(businessToday());
                                setAccount(c.current?.naturalAccount ?? '');
                                setReason('');
                                setError(null);
                              }}
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
      {historyOf !== null && (
        <Card title={t('mst.historyOf', [historyOf])}>
          <div className="wrap">
            <ServerPagination
              queryKey={masterKeys.mappingHistory(historyOf)}
              fetchPage={masterPages.mappingHistory(historyOf)}
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
                        {t('mst.naturalAccount')}
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
                        <td>{v.naturalAccount}</td>
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
        <Card title={t('mst.newVersionOf', [`${editing.payCode} ${editing.name}`])}>
          <Problem error={error} />
          <div className="bd rf-grid">
            <div className="rf-field">
              <label htmlFor="gl-from">{t('mst.from')}</label>
              <input id="gl-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
            </div>
            <div className="rf-field">
              <label htmlFor="gl-acct">{t('mst.naturalAccount')}</label>
              <input
                id="gl-acct"
                maxLength={20}
                value={account}
                onChange={(e) => setAccount(e.target.value.toUpperCase())}
              />
            </div>
            <div className="rf-field span2">
              <label htmlFor="gl-reason">{t('common.reason')}</label>
              <TextAreaField
                id="gl-reason"
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
              disabled={
                !reason.trim() || !from || !/^[A-Z0-9][A-Z0-9-]{1,19}$/.test(account) || proposal.isPending
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
