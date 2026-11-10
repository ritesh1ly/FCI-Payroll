'use client';

import { useI18n } from '@/shared/i18n';
import { useMe } from '@/shared/session';
import { Card, Empty, ServerPagination, Src } from '@/shared/ui';
import { HEADCOUNT_STATUSES as STATUSES } from '../business/employeeRules';
import { employeeKeys, headCountPage } from '../orchestration/employeeQueries';

/** R-01 Consolidated MIS: head count per location in scope. View and export only (SRS-22-3). */
export function ConsolidatedMis() {
  const { t } = useI18n();
  const me = useMe();
  return (
    <div className="wireframe-body-container">
      {me.data?.readOnlyConsolidated && <p className="fl-banner">{t('r01.readOnly')}</p>}
      <Card title={t('r01.title')}>
        <div className="wrap">
          <ServerPagination
            queryKey={employeeKeys.headCount}
            fetchPage={headCountPage}
            empty={<Empty text={t('r01.none')} />}
          >
            {(rows, page) => (
              <table className="t">
                <thead>
                  <tr>
                    <th>
                      {t('m01.location')}
                      <Src s="SYS" />
                    </th>
                    {STATUSES.map((s) => (
                      <th key={s}>
                        {t(`status.${s}`)}
                        <Src s="SYS" />
                      </th>
                    ))}
                    <th>
                      {t('r01.total')}
                      <Src s="SYS" />
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.locationCode}>
                      <td>{row.location}</td>
                      {STATUSES.map((s) => (
                        <td key={s}>{row.byStatus[s] ?? 0}</td>
                      ))}
                      <td>
                        <b>{row.total}</b>
                      </td>
                    </tr>
                  ))}
                </tbody>
                {rows.length > 0 && (
                  <tfoot>
                    <tr>
                      <td>{t('r01.total')}</td>
                      {/* Over every office of the scope, not this page: the server's totals. */}
                      {STATUSES.map((s) => (
                        <td key={s}>{page.totals[s] ?? 0}</td>
                      ))}
                      <td>{page.grandTotal}</td>
                    </tr>
                  </tfoot>
                )}
              </table>
            )}
          </ServerPagination>
        </div>
      </Card>
    </div>
  );
}
