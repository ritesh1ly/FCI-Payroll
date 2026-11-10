'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { can, useMe } from '@/shared/session';
import { businessMonth, monthToYymm } from '@/shared/time';
import { amount, date, dayOf, Empty, ExportButton, MonthField, ServerPagination } from '@/shared/ui';
import { RegisterSection } from '@/shared/ui/masterParts';
import { Act, Explained, Panel, Tbl, Tiles, WfTile } from '@/shared/ui/wireframe';
import type { LeaveEventRow, LeaveRecoveryFilter } from '../api/leaveApi';
import { appliedInLabel } from '../business/leaveRules';
import {
  leaveEventsExportUrl,
  leaveKeys,
  leavePages,
  leaveRecoveryReportUrl,
  useLeaveEventCounts,
} from '../orchestration/leaveQueries';
import { LeaveEvents } from './LeaveEvents';

/** The rule rows the pack draws under the list (SRS-LV-10, 15, 16, 19, 20, 21, 22). */
const RULES = ['006', '007', '008', '010', '011', '013', 'val'];

function tone(status: string): string {
  if (status === 'APPLIED') return 'p-ok';
  if (status === 'BACK_DATED' || status === 'PENDING') return 'p-warn';
  return 'p-bad';
}

/**
 * I-11 Leave Data View (from HRMS, read-only) as Wireframes v6.1 draws it (OD-121; SRS I-11 table, SRS-LV-13,
 * SRS-LV-23, SRS-LV-26, SRS-LV-33, US-GAP-59): the tiles of the month, the events with their pay effect, the
 * recovery and payment rules and the HRA stop panel (SRS-LV-29: held until OQ-4 is answered, nothing computed).
 * The status, EIS and leave-type filters stay in the closed register; the Leave Recovery Report (SRS-LV-23) opens
 * behind its drawn button. An event held for no sanction reference stays on X-05 and is counted here (WF-1411).
 * The Administrator has no access (SRS-LV-14). No row is keyed in Payroll: there is no HRMS API for leave events
 * on the gateway, they arrive through the intake port (PENDING API-19-1).
 */
export function LeaveEventsScreen() {
  const { t } = useI18n();
  const me = useMe();
  const counts = useLeaveEventCounts();
  const [register, setRegister] = useState(false);
  const [report, setReport] = useState(false);
  const month = monthToYymm(businessMonth());
  const mayExport = can(me.data, 'I-11', 'EXPORT');
  const [filter, setFilter] = useState<LeaveRecoveryFilter>({ fromMonth: month, toMonth: month });

  const named = t(`month.short.${Number(month.slice(4))}`);
  const shortMonth = `${named.charAt(0)}${named.slice(1).toLowerCase()}`;

  const exportAct = (className: string) =>
    mayExport ? (
      <ExportButton href={leaveEventsExportUrl({})} what={t('screen.I-11')} className={className} />
    ) : (
      <Explained label={t('i16w.export')} why={t('i11w.why.export')} />
    );

  function payEffect(r: LeaveEventRow): string {
    if (!r.payEffect) return t('i11w.notApplied');
    const label = t(`lv.effect.${r.payEffect}`);
    const shown =
      r.effectAmount === null || r.effectAmount === undefined
        ? label
        : t(r.effectAmount < 0 ? 'i11w.recovery' : 'i11w.arrear', [amount(Math.abs(r.effectAmount))]);
    return r.rule ? `${shown} (${r.rule})` : shown;
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <Act label={t('i11w.report')} onClick={() => setReport(true)} />
        {exportAct('btn')}
      </div>
      <Tiles>
        <WfTile
          icon="📥"
          label={t('i11w.tile.received', [month.slice(0, 4), shortMonth])}
          src="HRMS"
          big={counts.data?.received ?? '—'}
          sub={t('i11w.tile.receivedSub')}
        />
        <WfTile
          icon="✅"
          tone="ok"
          label={t('i11w.tile.applied')}
          src="SYS"
          big={counts.data?.applied ?? '—'}
          sub={t('i11w.tile.appliedSub')}
        />
        <WfTile
          icon="↩️"
          tone="warn"
          label={t('i11w.tile.backDated')}
          src="CALC"
          big={counts.data?.backDated ?? '—'}
          sub={t('i11w.tile.backDatedSub')}
        />
        <WfTile
          icon="⛔"
          tone="bad"
          bad
          label={t('i11w.tile.held')}
          src="SYS"
          big={counts.data?.held ?? '—'}
          sub={t('i11w.tile.heldSub')}
        />
      </Tiles>
      <Tbl
        title={t('i11w.title')}
        acts={exportAct('btn sm')}
        cols={[
          { t: t('i11.sanctionRef'), s: 'HRMS' },
          { t: t('i11.member'), s: 'HRMS' },
          { t: t('i11.leaveType'), s: 'HRMS' },
          { t: t('i11.period'), s: 'HRMS' },
          { t: t('i11.days'), s: 'HRMS', num: true },
          { t: t('i11.received'), s: 'INTF' },
          { t: t('i11.payEffect'), s: 'CALC' },
          { t: t('i11.appliedIn'), s: 'SYS' },
          { t: t('i11.status'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={[...leaveKeys.events({}), '#drawn']}
          fetchPage={leavePages.events({})}
          empty={<Empty text={t('i11.none')} />}
        >
          {(rows) =>
            rows.map((r) => (
              <tr key={r.applicationId} data-reference={r.sanctionRef}>
                <td>{r.sanctionRef}</td>
                <td>
                  {r.eis} {r.name}
                </td>
                <td>{r.leaveType}</td>
                <td>
                  {date(r.from)} – {date(r.to)}
                </td>
                <td className="num">{r.days}</td>
                <td>{dayOf(r.received)}</td>
                <td>{payEffect(r)}</td>
                <td>{appliedInLabel(r.appliedIn)}</td>
                <td>
                  <span className={`pill ${tone(r.status)}`}>
                    {r.status === 'BACK_DATED' && r.rule
                      ? t('i11w.backDated', [r.rule])
                      : t(`i11.status.${r.status}`)}
                  </span>
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <Tbl
          title={t('i11w.rules')}
          cols={[
            { t: t('i11w.col.rule'), s: 'RULE' },
            { t: t('i11w.col.statement'), s: 'RULE' },
          ]}
        >
          {RULES.map((k) => (
            <tr key={k}>
              <td>{t(`i11w.rule.${k}`)}</td>
              <td>{t(`i11w.rule.${k}.text`)}</td>
            </tr>
          ))}
        </Tbl>
        <Panel
          title={t('i11w.hra')}
          items={[
            { icon: '⚑', tone: 'warn', title: t('i11w.h1'), src: 'SYS', text: t('i11w.h1Sub') },
            { icon: '🎓', tone: 'info', title: t('i11w.h2'), src: 'RULE', text: t('i11w.h2Sub') },
            { icon: '❓', tone: 'bad', title: t('i11w.h3'), src: 'SYS', text: t('i11w.h3Sub') },
          ]}
        />
      </div>
      <RegisterSection id="i11-report" label={t('i11w.reportSection')} open={report} onToggle={setReport}>
        {report && (
          <div className="card">
            <div className="bd">
              <p className="sub2">{t('i11w.reportNote')}</p>
              <div className="rf-grid">
                <div className="rf-field">
                  <label htmlFor="i11-from">{t('i11w.from')}</label>
                  <MonthField
                    id="i11-from"
                    value={filter.fromMonth}
                    onChange={(fromMonth) => setFilter({ ...filter, fromMonth })}
                  />
                </div>
                <div className="rf-field">
                  <label htmlFor="i11-to">{t('i11w.to')}</label>
                  <MonthField
                    id="i11-to"
                    value={filter.toMonth}
                    onChange={(toMonth) => setFilter({ ...filter, toMonth })}
                  />
                </div>
                <div className="rf-field">
                  <label htmlFor="i11-eis">{t('m01.eis')}</label>
                  <input
                    id="i11-eis"
                    maxLength={20}
                    value={filter.eis ?? ''}
                    onChange={(e) => setFilter({ ...filter, eis: e.target.value.trim() || undefined })}
                  />
                </div>
                <div className="rf-field">
                  <label htmlFor="i11-type">{t('i11.leaveType')}</label>
                  <input
                    id="i11-type"
                    maxLength={15}
                    value={filter.leaveType ?? ''}
                    onChange={(e) =>
                      setFilter({ ...filter, leaveType: e.target.value.trim().toUpperCase() || undefined })
                    }
                  />
                </div>
                <div className="rf-field">
                  <label htmlFor="i11-event">{t('i11w.eventType')}</label>
                  <select
                    id="i11-event"
                    value={filter.eventType ?? ''}
                    onChange={(e) => setFilter({ ...filter, eventType: e.target.value || undefined })}
                  >
                    <option value="">{t('i11.allStatuses')}</option>
                    <ReferenceOptions list="LEAVE_RECOVERY_EVENT" />
                  </select>
                </div>
                <div className="rf-field">
                  <label htmlFor="i11-status">{t('i11.status')}</label>
                  <select
                    id="i11-status"
                    value={filter.status ?? ''}
                    onChange={(e) => setFilter({ ...filter, status: e.target.value || undefined })}
                  >
                    <option value="">{t('i11.allStatuses')}</option>
                    <ReferenceOptions list="LEAVE_RECOVERY_STATUS" />
                  </select>
                </div>
              </div>
              <div className="rf-actbar">
                {mayExport && filter.fromMonth !== '' && filter.toMonth !== '' ? (
                  <ExportButton
                    href={leaveRecoveryReportUrl(filter)}
                    what={t('i11w.reportName')}
                    label={t('i11w.reportName')}
                  />
                ) : (
                  <Explained label={t('i11w.reportName')} why={t('i11w.why.export')} />
                )}
              </div>
            </div>
          </div>
        )}
      </RegisterSection>
      <RegisterSection id="i11-register" label={t('i11w.register')} open={register} onToggle={setRegister}>
        {register && <LeaveEvents />}
      </RegisterSection>
    </div>
  );
}
