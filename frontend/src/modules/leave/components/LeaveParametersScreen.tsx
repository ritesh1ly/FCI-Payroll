'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { date, Problem } from '@/shared/ui';
import { RegisterSection, SimulateAct, useApproveRow, useMasterRights } from '@/shared/ui/masterParts';
import {
  Act,
  Explained,
  Locked,
  Panel,
  RowActs,
  Tbl,
  Tiles,
  ViewDialog,
  WfTile,
} from '@/shared/ui/wireframe';
import type { LeaveParameterVersion } from '../api/leaveApi';
import { drawnParameters, DRAWN_PARAMETERS, type ParameterRow } from '../business/leaveRules';
import { useLeaveVersions } from '../orchestration/leaveQueries';
import { LeavePayParameters } from './LeavePayParameters';

/** The value as the register shows it. */
function valueOf(v: LeaveParameterVersion): string {
  return String(v.text ?? v.number ?? '—');
}

/** The rules each parameter is read by (SRS M-20 table, l.4170), as the pack lists them. */
const USED_BY: Record<string, string> = {
  LWP_DIVISOR: 'BR-LEAVE-007',
  ENCASH_DIVISOR: 'BR-LEAVE-008, BR-FNF-009',
  ENCASH_PAY_ELEMENTS: 'BR-LEAVE-008, BR-FNF-009',
  INSERVICE_PER_OCCASION_LIMIT: 'BR-LEAVE-009',
  INSERVICE_PER_YEAR_LIMIT: 'BR-LEAVE-009',
  SEPARATION_AGGREGATE_CEILING: 'BR-LEAVE-009, BR-FNF-009',
};

/**
 * M-20 Leave Encashment Parameters as Wireframes v6.1 draws it (OD-121; SRS-LV-14, SRS-LV-15 to 17, SRS-LV-21, SRS
 * M-20 table l.4170-4180; DDL leave_pay_parameter): the seven parameters, each with its version in force or the
 * one waiting, the rules that read them and the governance. The Rule Administrator proposes at Headquarters and
 * the Payroll Checker approves on W-01; the drawn Administrator sees the entry as drawn, told why (OD-135). Every
 * version, the pay effect per leave type that M-13 reads (not drawn, WF-1408), the form and the row history stay
 * in the closed register. "Seeded values" counts the parameters with a value in force: the DDL holds no seeded
 * flag (WF-1409); a parameter with none is not configured and the engine refuses, never a default (R-RULE).
 */
export function LeaveParametersScreen() {
  const { t } = useI18n();
  const rights = useMasterRights('M-20');
  const approve = useApproveRow();
  const versions = useLeaveVersions();
  const [register, setRegister] = useState(false);
  const [supersede, setSupersede] = useState<{ code: string } | null>(null);
  const [viewing, setViewing] = useState<ParameterRow | null>(null);
  const rows = drawnParameters(versions.data?.items ?? []);
  const inForce = rows.filter((r) => r.current !== null).length;
  const pending = rows.filter((r) => r.pending !== null).length;

  function edit(code: string) {
    setRegister(true);
    if (rights.mayCreate) setSupersede({ code });
  }

  function status(r: ParameterRow) {
    if (r.pending) return <span className="pill p-warn">{t('m20.status.PENDING_APPROVAL')}</span>;
    if (r.current) return <span className="pill p-ok">{t('m20.status.ACTIVE')}</span>;
    return <span className="pill p-bad">{t('mst.notConfigured')}</span>;
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <SimulateAct label={t('m15w.simulate')} />
        {rights.drawn ? (
          <Act label={t('m20w.newVersion')} kind="pri" onClick={() => setRegister(true)} />
        ) : (
          <Locked label={t('m20w.newVersion')} kind="pri" why={t('m20w.why.ruleAdmin')} />
        )}
      </div>
      <Problem error={versions.error} />
      <Tiles>
        <WfTile
          icon="🗓"
          label={t('m20w.tile.parameters')}
          src="RULE"
          big={DRAWN_PARAMETERS.length}
          sub={t('m20w.tile.parametersSub')}
        />
        <WfTile
          icon="↪"
          label={t('m20w.tile.seeded')}
          src="RULE"
          big={inForce}
          sub={t('m20w.tile.seededSub')}
        />
        <WfTile
          icon="✍️"
          tone="warn"
          label={t('m15w.tile.pending')}
          src="SYS"
          big={pending}
          sub={t('m20w.tile.pendingSub')}
        />
      </Tiles>
      <Tbl
        title={t('m20w.title')}
        acts={
          <>
            <Act label={t('mst.history')} kind="sm" onClick={() => setRegister(true)} />
            {rights.drawn ? (
              <Act label={t('m20.supersede')} kind="sm" onClick={() => setRegister(true)} />
            ) : (
              <Explained label={t('m20.supersede')} kind="sm" why={t('m20w.why.ruleAdmin')} />
            )}
          </>
        }
        cols={[
          { t: t('m20.parameter'), s: 'RULE' },
          { t: t('m20.value'), s: 'RULE' },
          { t: t('m20w.col.used'), s: 'RULE' },
          { t: t('m15w.effFrom'), s: 'ENT' },
          { t: t('m20.status'), s: 'SYS' },
          { t: t('m04.col.actions'), s: 'SYS' },
        ]}
      >
        {rows.map((r) => {
          const shown = r.current ?? r.pending;
          const name = t(`m20w.param.${r.code}`);
          return (
            <tr key={r.code} data-reference={r.code}>
              <td>{name}</td>
              <td>{shown ? valueOf(shown) : <span className="pill p-bad">{t('mst.notSeeded')}</span>}</td>
              {/* i18n-ignore: requirement references, not labels */}
              <td>{USED_BY[r.code] ?? t('m20w.posting')}</td>
              <td>{shown ? date(shown.effectiveFrom) : '—'}</td>
              <td>{status(r)}</td>
              <td>
                <RowActs
                  viewName={`${t('common.view')} ${name}`}
                  editName={`${t('common.edit')} ${name}`}
                  onView={() => setViewing(r)}
                  onEdit={rights.drawn && !r.pending ? () => edit(r.code) : undefined}
                  editWhy={rights.drawn ? t('mst.why.pending') : t('m20w.why.ruleAdmin')}
                  onApprove={rights.checker ? () => approve(r.pending !== null) : undefined}
                />
              </td>
            </tr>
          );
        })}
      </Tbl>
      <div className="g2 fl-mt">
        <Panel
          title={t('m20w.panel1')}
          items={[
            { icon: '➖', tone: 'info', title: t('m20w.r1'), src: 'CALC', text: t('m20w.r1Sub') },
            { icon: '💰', tone: 'info', title: t('m20w.r2'), src: 'CALC', text: t('m20w.r2Sub') },
            { icon: '📏', tone: 'info', title: t('m20w.r3'), src: 'RULE', text: t('m20w.r3Sub') },
            { icon: '⛔', tone: 'bad', title: t('m20w.r4'), src: 'RULE', text: t('m20w.r4Sub') },
          ]}
        />
        <Panel
          title={t('m20w.panel2')}
          items={[
            { icon: '📅', tone: 'info', title: t('m20w.g1'), src: 'RULE', text: t('m20w.g1Sub') },
            {
              icon: '✍️',
              tone: 'warn',
              title: t('m20w.g2'),
              src: 'SYS',
              text: t('m20w.g2Sub'),
              right: <span className="pill p-ok">{t('m20w.on')}</span>,
            },
            { icon: '🧪', tone: 'gray', title: t('m20w.g3'), src: 'SYS', text: t('m20w.g3Sub') },
          ]}
        />
      </div>
      <RegisterSection id="m20-register" label={t('m20w.register')} open={register} onToggle={setRegister}>
        {register && <LeavePayParameters supersede={supersede} />}
      </RegisterSection>
      {viewing && (
        <ViewDialog
          screen={t('screen.M-20')}
          onClose={() => setViewing(null)}
          rows={[
            { k: t('m20.parameter'), v: t(`m20w.param.${viewing.code}`), s: 'RULE' },
            {
              k: t('m20.value'),
              v: viewing.current ? valueOf(viewing.current) : '—',
              s: 'RULE',
            },
            {
              k: t('m20w.proposed'),
              v: viewing.pending ? valueOf(viewing.pending) : '—',
              s: 'RULE',
            },
            { k: t('m20w.col.used'), v: USED_BY[viewing.code] ?? t('m20w.posting'), s: 'RULE' },
            {
              k: t('m20.circular'),
              v: viewing.current?.circularNumber ?? '—',
              s: 'ENT',
            },
            { k: t('m20.status'), v: status(viewing), s: 'SYS' },
          ]}
        />
      )}
    </div>
  );
}
