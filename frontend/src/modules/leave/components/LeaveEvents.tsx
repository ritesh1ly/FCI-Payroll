'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { Card, date, dayOf, Empty, ExportButton, ServerPagination, Src, Tile } from '@/shared/ui';
import type { LeaveEventFilter, LeaveEventStatus } from '../api/leaveApi';
import { appliedInLabel, LEAVE_EVENT_STATUSES } from '../business/leaveRules';
import {
  leaveEventsExportUrl,
  leaveKeys,
  leavePages,
  useLeaveEventCounts,
} from '../orchestration/leaveQueries';

/**
 * I-11 Leave Data View (from HRMS, read-only) (plan 19 phases 19a / 19c; SRS I-11 field table,
 * SRS-LV-13, SRS-LV-26, SRS-LV-10). The leave events HRMS sanctioned for the offices of the session,
 * each with its status: Pending (its I-21 task waits for the Payroll Maker and Checker), Applied in the
 * cycle, Back-dated (settled in the next open cycle) or Rejected. An event HRMS sent without a sanction
 * reference, or that cannot be matched, is held on X-05 and counted here; nothing on this screen edits
 * leave (SRS-LV-24). The Administrator and the member have no access.
 */
export function LeaveEvents() {
  const { t } = useI18n();
  const [status, setStatus] = useState<LeaveEventStatus | ''>('');
  const [eis, setEis] = useState('');
  const [leaveType, setLeaveType] = useState('');
  const counts = useLeaveEventCounts();
  const me = useMe();
  const filter: LeaveEventFilter = {
    status: status || undefined,
    eis: eis.trim() || undefined,
    leaveType: leaveType.trim().toUpperCase() || undefined,
  };
  return (
    <div className="wireframe-body-container">
      {counts.data && (
        <div className="tiles">
          <Tile icon="↧" label={t('i11.tile.received')} value={counts.data.received} />
          <Tile icon="✓" label={t('i11.tile.applied')} value={counts.data.applied} tone="ok" />
          <Tile icon="↺" label={t('i11.tile.backDated')} value={counts.data.backDated} tone="warn" />
          <Tile
            icon="!"
            label={t('i11.tile.held')}
            value={counts.data.held}
            tone={counts.data.held > 0 ? 'bad' : 'info'}
          />
        </div>
      )}
      <div className="toolbar">
        <label>
          {t('i11.status')}{' '}
          <select value={status} onChange={(e) => setStatus(e.target.value as LeaveEventStatus | '')}>
            <option value="">{t('i11.allStatuses')}</option>
            {LEAVE_EVENT_STATUSES.map((s) => (
              <option key={s} value={s}>
                {t(`i11.status.${s}`)}
              </option>
            ))}
          </select>
        </label>
        <label>
          {t('m01.eis')} <input maxLength={20} value={eis} onChange={(e) => setEis(e.target.value)} />
        </label>
        <label>
          {t('i11.leaveType')}{' '}
          <input maxLength={15} value={leaveType} onChange={(e) => setLeaveType(e.target.value)} />
        </label>
        {can(me.data, 'I-11', 'EXPORT') && (
          <ExportButton href={leaveEventsExportUrl(filter)} what={t('screen.I-11')} />
        )}
      </div>
      <p className="sub2">{t('i11.note')}</p>
      <Card title={t('screen.I-11')}>
        <ServerPagination
          queryKey={leaveKeys.events(filter)}
          fetchPage={leavePages.events(filter)}
          empty={<Empty text={t('i11.none')} />}
        >
          {(rows) => (
            <table className="t" aria-label={t('screen.I-11')}>
              <thead>
                <tr>
                  <th>
                    {t('i11.sanctionRef')}
                    <Src s="HRMS" />
                  </th>
                  <th>
                    {t('i11.member')}
                    <Src s="HRMS" />
                  </th>
                  <th>
                    {t('i11.leaveType')}
                    <Src s="HRMS" />
                  </th>
                  <th>
                    {t('i11.period')}
                    <Src s="HRMS" />
                  </th>
                  <th>
                    {t('i11.days')}
                    <Src s="HRMS" />
                  </th>
                  <th>
                    {t('i11.received')}
                    <Src s="INTF" />
                  </th>
                  <th>
                    {t('i11.payEffect')}
                    <Src s="CALC" />
                  </th>
                  <th>
                    {t('i11.appliedIn')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('i11.status')}
                    <Src s="SYS" />
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.applicationId} data-reference={r.sanctionRef}>
                    <td>
                      {r.sanctionRef}
                      {r.eventType === 'CANCELLATION' && <div className="sub2">{t('i11.cancellation')}</div>}
                    </td>
                    <td>
                      {r.eis} {r.name}
                      {r.office && <div className="sub2">{r.office}</div>}
                    </td>
                    <td>
                      {r.leaveType}
                      {r.leaveTypeName && ` - ${r.leaveTypeName}`}
                    </td>
                    <td>
                      {date(r.from)} – {date(r.to)}
                    </td>
                    <td className="num">{r.days}</td>
                    <td>{dayOf(r.received)}</td>
                    <td>
                      {r.payEffect ? t(`lv.effect.${r.payEffect}`) : '—'}
                      {/* i18n-ignore: requirement references, not labels */}
                      {r.rule && <div className="sub2">{r.rule}</div>}
                    </td>
                    <td>{appliedInLabel(r.appliedIn)}</td>
                    <td>{t(`i11.status.${r.status}`)}</td>
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
