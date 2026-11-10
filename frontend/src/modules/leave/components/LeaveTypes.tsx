'use client';

import { HistoryButton } from '@/modules/audit';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { Card, dateTime, dayOf, Empty, ExportButton, ServerPagination, Src, Tile } from '@/shared/ui';
import {
  leaveKeys,
  leavePages,
  leaveTypesExportUrl,
  useLeaveTypeCounts,
} from '../orchestration/leaveQueries';

/**
 * M-13 Leave Type Reference (from HRMS, read-only) (plan 19 phase 19a; SRS M-13 field table,
 * SRS-G-45, BR-LEAVE-001 / 003). The leave types HRMS sends, with the pay effect M-20 holds for each
 * today; no role creates, edits or approves a type in Payroll. A type with no pay effect configured
 * holds its leave events on X-05 (never a zero deduction).
 */
export function LeaveTypes() {
  const { t } = useI18n();
  const counts = useLeaveTypeCounts();
  const me = useMe();
  return (
    <div className="wireframe-body-container">
      {counts.data && (
        <div className="tiles">
          <Tile icon="≡" label={t('m13.tile.received')} value={counts.data.received} />
          <Tile icon="₹" label={t('m13.tile.encashable')} value={counts.data.encashable} />
          <Tile icon="↓" label={t('m13.tile.payReducing')} value={counts.data.payReducing} tone="warn" />
          <Tile icon="⟳" label={t('m13.tile.lastSync')} value={dateTime(counts.data.lastSync) || '—'} />
        </div>
      )}
      <div className="toolbar">
        <span className="sub2">{t('m13.note')}</span>
        {can(me.data, 'M-13', 'EXPORT') && (
          <ExportButton href={leaveTypesExportUrl()} what={t('screen.M-13')} />
        )}
      </div>
      <Card title={t('screen.M-13')}>
        <ServerPagination
          queryKey={leaveKeys.types}
          fetchPage={leavePages.types}
          empty={<Empty text={t('m13.none')} />}
        >
          {(rows) => (
            <table className="t" aria-label={t('screen.M-13')}>
              <thead>
                <tr>
                  <th>
                    {t('m13.code')}
                    <Src s="HRMS" />
                  </th>
                  <th>
                    {t('m13.encashable')}
                    <Src s="HRMS" />
                  </th>
                  <th>
                    {t('m13.payEffect')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m13.lastReceived')}
                    <Src s="INTF" />
                  </th>
                  <th aria-label={t('common.actions')} />
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.code} data-reference={r.code}>
                    <td>
                      <b>{r.code}</b> {r.name}
                      {r.halfDay && <div className="sub2">{t('m13.halfDay')}</div>}
                    </td>
                    <td>{t(r.encashable ? 'common.yes' : 'common.no')}</td>
                    <td>
                      {r.payEffect ? (
                        t(`lv.effect.${r.payEffect}`)
                      ) : (
                        <span className="pill p-warn">{t('m13.noPayEffect')}</span>
                      )}
                    </td>
                    <td>{dayOf(r.lastReceived)}</td>
                    <td>
                      <HistoryButton
                        entity="leave_type_reference"
                        recordKey={r.code}
                        label={`${r.code} · ${r.name}`}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </ServerPagination>
      </Card>
    </div>
  );
}
