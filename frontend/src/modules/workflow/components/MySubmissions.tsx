'use client';

import { useI18n } from '@/shared/i18n';
import { useState } from 'react';
import { workflowKeys, workflowPages } from '../orchestration/workflowQueries';
import { Card, dateTime, Empty, entityLabel, ServerPagination, Src, StatusPill } from '@/shared/ui';

/** W-02 My Submissions: the maker's own items and where they stand. */
export function MySubmissions() {
  const { t } = useI18n();
  const [total, setTotal] = useState(0);
  return (
    <div className="wireframe-body-container">
      <Card title={t('w02.title', [total])}>
        <div className="wrap">
          <ServerPagination
            queryKey={workflowKeys.submissions}
            fetchPage={workflowPages.submissions}
            empty={<Empty text={t('w02.none')} />}
            onTotal={setTotal}
          >
            {(items) => (
              <table className="t">
                <thead>
                  <tr>
                    <th>
                      {t('w01.id')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('w01.module')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('w01.subject')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('w02.submittedAt')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('w02.checker')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('m01.status')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('common.reason')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('w02.checkerDecision')}
                      <Src s="SYS" />
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((item) => (
                    <tr key={item.approvalId}>
                      <td>
                        <b>{item.approvalId}</b>
                      </td>
                      <td>{item.moduleCode ?? '—'}</td>
                      <td>
                        {entityLabel(t, item.entityName)} <span className="sub2">{item.entityKey}</span>
                      </td>
                      <td>{dateTime(item.submittedAt)}</td>
                      <td>
                        {item.assignedRoleCode ? t(`role.${item.assignedRoleCode}`) : '—'}
                        {item.noApproverInScope && <span className="pill p-bad">{t('w01.noApprover')}</span>}
                        <div className="sub2">{item.locationLabel ?? ''}</div>
                      </td>
                      <td>
                        <StatusPill status={item.status} />
                      </td>
                      <td>{item.reason ?? '—'}</td>
                      <td data-testid={`w02-decision-${item.approvalId}`}>
                        {item.decidedBy ? (
                          <>
                            {item.decidedBy}
                            {item.decidedAt && <div className="sub2">{dateTime(item.decidedAt)}</div>}
                          </>
                        ) : (
                          '—'
                        )}
                        {/* SRS l.596: the Maker sees a returned item with the Checker's reason. */}
                        {item.status === 'RETURNED' && item.returnReason && (
                          <div className="sub2">
                            {t('w02.returnReason')}: {item.returnReason}
                          </div>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </ServerPagination>
        </div>
      </Card>
    </div>
  );
}
