'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { dateTime, dayOf, Empty, ExportButton, ServerPagination } from '@/shared/ui';
import { RegisterSection } from '@/shared/ui/masterParts';
import { Act, Explained, Panel, Tbl, Tiles, WfTile } from '@/shared/ui/wireframe';
import {
  leaveKeys,
  leavePages,
  leaveTypesExportUrl,
  useLeaveTypeCounts,
  useLeaveTypeList,
} from '../orchestration/leaveQueries';
import { LeaveTypes } from './LeaveTypes';

/** The types whose rule reference the pack states (CLR-29: the list itself is FCI's, PENDING M13-2). */
const USED_BY = new Set(['EL', 'HPL', 'EOL', 'LWP', 'DL', 'SL']);

/**
 * M-13 Leave Type Reference (from HRMS, read-only) as Wireframes v6.1 draws it (OD-121; SRS-G-45, BR-LEAVE-001,
 * BR-LEAVE-003, SRS M-13 table): the tiles, each type with its code and name in their own columns, what Payroll
 * does with leave and where the pieces now live. Payroll has no API that fetches leave types from HRMS: the
 * drawn Last HRMS Sync says when they last arrived, it calls nothing (PENDING API-19-1, WF-025). A row's change
 * history stays in the closed register. The Encashable and Last Sync captions read the types received, so they stay
 * true of the data: a type HRMS sends with no pay effect on M-20 counts as unmatched (WF-1804).
 */
export function LeaveTypesScreen() {
  const { t } = useI18n();
  const me = useMe();
  const counts = useLeaveTypeCounts();
  const [register, setRegister] = useState(false);
  const [note, setNote] = useState(false);
  const mayExport = can(me.data, 'M-13', 'EXPORT');
  const lastSync = counts.data?.lastSync ?? null;
  const types = useLeaveTypeList().data?.items;
  const encashable = (types ?? []).filter((r) => r.encashable).map((r) => r.code);
  const unmatched = (types ?? []).filter((r) => !r.payEffect).length;

  const exportAct = (className: string) =>
    mayExport ? (
      <ExportButton href={leaveTypesExportUrl()} what={t('screen.M-13')} className={className} />
    ) : (
      <Explained label={t('i16w.export')} why={t('m13w.why.export')} />
    );

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <Act label={t('m13w.lastSync')} onClick={() => setNote(true)} />
        {exportAct('btn')}
      </div>
      {note && (
        <p className="sub2" role="status">
          {lastSync ? t('m13w.syncAt', [dateTime(lastSync)]) : t('m13w.syncNone')}
        </p>
      )}
      <Tiles>
        <WfTile
          icon="🌴"
          label={t('m13w.tile.received')}
          src="HRMS"
          big={counts.data?.received ?? '—'}
          sub={t('m13w.tile.receivedSub')}
        />
        <WfTile
          icon="💰"
          tone="ok"
          label={t('m13w.tile.encashable')}
          src="HRMS"
          big={counts.data?.encashable ?? '—'}
          sub={
            encashable.length > 0
              ? t('m13w.tile.encashableOf', [encashable.join(', ')])
              : t('m13w.tile.encashableSub')
          }
        />
        <WfTile
          icon="➖"
          tone="warn"
          label={t('m13w.tile.reducing')}
          src="RULE"
          big={counts.data?.payReducing ?? '—'}
          sub={t('m13w.tile.reducingSub')}
        />
        <WfTile
          icon="🔄"
          label={t('m13w.tile.sync')}
          src="INTF"
          big={lastSync ? dateTime(lastSync) : '—'}
          sub={
            types === undefined
              ? t('m13w.tile.syncSub')
              : unmatched === 0
                ? t('m13w.tile.noUnmatched')
                : t('m13w.tile.unmatched', [unmatched])
          }
        />
      </Tiles>
      <Tbl
        title={t('m13w.title')}
        acts={exportAct('btn sm')}
        cols={[
          { t: t('m13w.col.code'), s: 'HRMS' },
          { t: t('m13w.col.type'), s: 'HRMS' },
          { t: t('m13w.col.encashable'), s: 'HRMS' },
          { t: t('m13w.col.effect'), s: 'RULE' },
          { t: t('m13w.col.used'), s: 'RULE' },
          { t: t('m13w.col.received'), s: 'INTF' },
        ]}
      >
        <ServerPagination
          queryKey={[...leaveKeys.types, '#drawn']}
          fetchPage={leavePages.types}
          empty={<Empty text={t('m13.none')} />}
        >
          {(rows) =>
            rows.map((r) => (
              <tr key={r.code} data-reference={r.code}>
                <td>
                  <b>{r.code}</b>
                </td>
                <td>{r.name}</td>
                <td>{t(r.encashable ? 'common.yes' : 'common.no')}</td>
                <td>
                  {r.payEffect ? (
                    t(`lv.effect.${r.payEffect}`)
                  ) : (
                    <span className="pill p-warn">{t('m13.noPayEffect')}</span>
                  )}
                </td>
                <td>{USED_BY.has(r.code) ? t(`m13w.used.${r.code}`) : '—'}</td>
                <td>{dayOf(r.lastReceived)}</td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <Panel
          title={t('m13w.panel1')}
          items={[
            { icon: '🏢', tone: 'info', title: t('m13w.p1'), src: 'HRMS', text: t('m13w.p1Sub') },
            { icon: '🔒', tone: 'gray', title: t('m13w.p2'), src: 'SYS', text: t('m13w.p2Sub') },
            { icon: '✅', tone: 'ok', title: t('m13w.p3'), src: 'RULE', text: t('m13w.p3Sub') },
            { icon: '🚫', tone: 'bad', title: t('m13w.p4'), src: 'RULE', text: t('m13w.p4Sub') },
            { icon: '↩️', tone: 'warn', title: t('m13w.p5'), src: 'RULE', text: t('m13w.p5Sub') },
          ]}
        />
        <Panel
          title={t('m13w.panel2')}
          items={[
            { icon: '🗓', tone: 'info', title: t('m13w.q1'), src: 'RULE', text: t('m13w.q1Sub') },
            { icon: '👁', tone: 'info', title: t('m13w.q2'), src: 'HRMS', text: t('m13w.q2Sub') },
            { icon: '📊', tone: 'info', title: t('m13w.q3'), src: 'SYS', text: t('m13w.q3Sub') },
            { icon: '🔌', tone: 'info', title: t('m13w.q4'), src: 'INTF', text: t('m13w.q4Sub') },
          ]}
        />
      </div>
      <RegisterSection id="m13-register" label={t('m13w.register')} open={register} onToggle={setRegister}>
        {register && <LeaveTypes />}
      </RegisterSection>
    </div>
  );
}
