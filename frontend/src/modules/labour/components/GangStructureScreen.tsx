'use client';

import { useMemo, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { RegisterSection, useApproveRow } from '@/shared/ui/masterParts';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { date, ExportButton, Problem, ServerPagination, useToast } from '@/shared/ui';
import {
  Act,
  Explained,
  KvCard,
  Locked,
  Panel,
  RowActs,
  Tbl,
  Toolbar,
  ViewDialog,
} from '@/shared/ui/wireframe';
import type { GangRow } from '../api/labourApi';
import type { GangLine, ScreenFilter, StrengthLine } from '../api/labourScreenApi';
import { gangPill } from '../business/labourScreenRules';
import { useEngine } from '../orchestration/captureQueries';
import { useGangMembers, useOptions } from '../orchestration/labourQueries';
import {
  gangLinesPage,
  gangsExportUrl,
  screenKeys,
  strengthsPage,
  useFirstGangs,
  useGangSummary,
} from '../orchestration/screenQueries';
import { TypeSelect } from './CaptureParts';
import { GangStructure as GangRegister } from './GangStructure';

function asRow(g: GangLine): GangRow {
  return {
    gangId: g.gangId,
    gangCode: g.gangCode,
    gangName: g.gangName,
    depot: g.depot,
    payrollType: g.payrollType,
    gangType: g.gangType,
    status: g.status,
    sanctionedStrength: null,
    members: g.labourCount,
    effectiveFrom: g.effectiveFrom,
    effectiveTo: g.effectiveTo,
  };
}

/**
 * M-09 Gang / Shed Structure as Wireframes v6.1 draws it (OD-121; SRS-2-1, SRS-2-46, SRS-2-47, SRS-2-48;
 * BR-LABM-001, BR-LABM-006, BR-LABM-011): the gang register of the engine, the structural validations with today's
 * figures, the composition of the first gang, gang strength today and the gang rules. Establishment / HR forms and
 * ends gangs and maintains membership; the Payroll Checker approves; the Labour Dealing Hand views its gangs. New
 * Gang, Membership, End-date, the strength change, transfers and attachments stay in the closed register below, which
 * the drawn buttons open; the Gang Strength Report and the alert's closure are there too.
 */
export function GangStructure() {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const approve = useApproveRow();
  const { type, choose } = useEngine();
  const options = useOptions();
  const [depot, setDepot] = useState<number | null>(null);
  const [q, setQ] = useState('');
  const [register, setRegister] = useState(false);
  const [opening, setOpening] = useState<{ gang: GangRow | null; startNew: boolean } | null>(null);
  const [viewing, setViewing] = useState<GangLine | null>(null);
  const mayCreate = can(me.data, 'M-09', 'CREATE');
  const mayEdit = can(me.data, 'M-09', 'EDIT');
  const mayApprove = can(me.data, 'M-09', 'APPROVE');
  const mayExport = can(me.data, 'M-09', 'EXPORT');
  const filter = useMemo<ScreenFilter>(() => ({ type, depot, q }), [type, depot, q]);
  const firstPage = useFirstGangs(filter);
  const summary = useGangSummary(filter);
  const lead =
    firstPage.data?.items?.find((g) => g.gangType === 'HANDLING' && g.status === 'ACTIVE') ??
    firstPage.data?.items?.[0] ??
    null;
  const members = useGangMembers(lead?.gangId ?? null);
  const list = Array.isArray(members.data) ? members.data : [];
  const leader = (role: string) => list.find((m) => m.role === role)?.name ?? '—';
  const count = (role: string) => list.filter((m) => m.role === role).length;
  const breaches = summary.data?.leaderBreaches ?? 0;
  const blocked = summary.data?.endBlocked ?? 0;
  const typeName = t(`i01w.type.${type}`);

  function open(gang: GangRow | null, startNew = false) {
    setOpening({ gang, startNew });
    setRegister(true);
  }

  const exportControl = mayExport ? (
    <ExportButton href={gangsExportUrl(filter)} what={t('screen.M-09')} label={t('wf.export')} />
  ) : (
    <Explained label={t('wf.export')} why={t('lw.why.export')} />
  );

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {lead ? (
          <HistoryButton
            entity="labour_gang"
            recordKey={lead.gangCode}
            label={`${lead.gangCode} · ${lead.gangName}`}
            text={t('lw.history')}
            className="btn"
          />
        ) : (
          <Act label={t('lw.history')} onClick={() => toast(t('m09.none'))} />
        )}
        <Act label={t('m09w.membership')} onClick={() => open(lead ? asRow(lead) : null)} />
        {mayCreate ? (
          <Act label={t('m09w.newGang')} kind="pri" onClick={() => open(null, true)} />
        ) : (
          <Locked label={t('m09w.newGang')} kind="pri" why={t('m09w.why.newGang')} />
        )}
      </div>
      <Toolbar search={q} onSearch={setQ} exportControl={exportControl}>
        <TypeSelect value={type} onChange={choose} />
        <select
          aria-label={t('lw.depotFilter')}
          value={depot ?? ''}
          onChange={(e) => setDepot(e.target.value ? Number(e.target.value) : null)}
        >
          <option value="">{t('m09.allDepots')}</option>
          {(options.data?.depots ?? []).map((d) => (
            <option key={d.id} value={d.id}>
              {d.name}
            </option>
          ))}
        </select>
      </Toolbar>
      <Tbl
        title={t('m09w.title', [typeName])}
        label={t('m09w.tableLabel')}
        acts={
          mayEdit ? (
            <Act label={t('m09w.endDate')} kind="sm" onClick={() => open(lead ? asRow(lead) : null)} />
          ) : (
            <Locked label={t('m09w.endDate')} kind="sm" why={t('m09w.why.endDate')} />
          )
        }
        cols={[
          { t: t('m09w.col.gang'), s: 'ENT' },
          { t: t('m09w.col.shed'), s: 'HRMS' },
          { t: t('m09w.col.sardar'), s: 'ENT' },
          { t: t('m09w.col.mandal'), s: 'ENT' },
          { t: t('m09w.col.count'), s: 'CALC', num: true },
          { t: t('m09w.col.from'), s: 'ENT' },
          { t: t('m09w.col.to'), s: 'ENT' },
          { t: t('m09w.col.status'), s: 'SYS' },
          { t: t('m09w.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={screenKeys.of('gangs', filter)}
          fetchPage={gangLinesPage(filter)}
          empty={t('m09.none')}
        >
          {(rows) =>
            rows.map((g) => {
              const pill = gangPill(g);
              return (
                <tr key={g.gangId}>
                  <td>
                    <b>{g.gangCode}</b>
                    <div className="sub2">{g.gangName}</div>
                  </td>
                  <td>{g.shed ? t('m09w.shedAt', [g.shed, g.depot]) : g.depot}</td>
                  <td>{g.sardar ?? '—'}</td>
                  <td>{g.mandal ?? '—'}</td>
                  <td className="num">{g.labourCount}</td>
                  <td>{date(g.effectiveFrom)}</td>
                  <td>{g.effectiveTo ? date(g.effectiveTo) : '—'}</td>
                  <td>
                    <span className={`pill ${pill.tone}`}>{t(pill.key)}</span>
                  </td>
                  <td>
                    <RowActs
                      viewName={`${t('common.view')} ${g.gangCode}`}
                      editName={`${t('common.edit')} ${g.gangCode}`}
                      onView={() => setViewing(g)}
                      onEdit={mayEdit && !g.endBlocked ? () => open(asRow(g)) : undefined}
                      editWhy={g.endBlocked ? t('m09w.why.slips') : t('m09w.why.edit')}
                      onApprove={mayApprove ? () => approve(g.pending) : undefined}
                    />
                  </td>
                </tr>
              );
            })
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <Panel
          title={t('m09w.validations')}
          items={[
            {
              icon: '1️⃣',
              tone: 'bad',
              title: t('m09w.v.one'),
              src: 'RULE',
              text: t('m09w.v.oneM'),
              right: <b>{t('m09w.v.breach', [breaches])}</b>,
            },
            {
              icon: '🚫',
              tone: 'bad',
              title: t('m09w.v.end'),
              src: 'RULE',
              text: t('m09w.v.endM'),
              right: <b>{t('m09w.v.blocked', [blocked])}</b>,
            },
            {
              icon: '🔁',
              tone: 'info',
              title: t('m09w.v.auto'),
              src: 'SYS',
              text: t('m09w.v.autoM'),
              right: <span className="pill p-ok">{t('m09w.v.autoR')}</span>,
            },
            {
              icon: '📅',
              tone: 'info',
              title: t('m09w.v.to'),
              src: 'RULE',
              text: t('m09w.v.toM'),
              right: <span className="pill p-ok">{t('m02w.engine.on')}</span>,
            },
          ]}
        />
        <KvCard
          title={t('m09w.composition', [lead?.gangCode ?? '—'])}
          rows={[
            { k: t('m09w.c.sardar'), v: t('m09w.c.sardarV', [leader('SARDAR')]), s: 'RULE' },
            { k: t('m09w.c.mandal'), v: t('m09w.c.mandalV', [leader('MANDAL')]), s: 'RULE' },
            { k: t('m09w.c.handling'), v: count('HANDLING'), s: 'CALC' },
            { k: t('m09w.c.ancillary'), v: count('ANCILLARY'), s: 'CALC' },
            { k: t('m09w.c.nominal'), v: t('m09w.c.nominalV'), s: 'RULE' },
            { k: t('m09w.c.basis'), v: t('m09w.c.basisV'), s: 'RULE' },
            { k: t('m09w.c.absence'), v: t('m09w.c.absenceV'), s: 'RULE' },
          ]}
        />
      </div>
      <div className="g2 fl-mt">
        <Tbl
          title={t('m09w.strength')}
          acts={
            <Act label={t('m09w.strengthReport')} kind="sm" onClick={() => open(lead ? asRow(lead) : null)} />
          }
          cols={[
            { t: t('m09w.s.gang'), s: 'SYS' },
            { t: t('m09w.s.type'), s: 'ENT' },
            { t: t('m09w.s.sanctioned'), s: 'ENT', num: true },
            { t: t('m09w.s.present'), s: 'CALC', num: true },
            { t: t('m09w.s.short'), s: 'CALC', num: true },
            { t: t('m09w.s.alert'), s: 'SYS' },
            { t: t('m09w.s.filled'), s: 'SYS' },
          ]}
        >
          <ServerPagination
            queryKey={screenKeys.of('strengths', filter)}
            fetchPage={strengthsPage(filter)}
            empty={t('m09.none')}
          >
            {(rows: StrengthLine[]) =>
              rows.map((s) => {
                const pool = s.gangType !== 'HANDLING';
                return (
                  <tr key={s.gangId}>
                    <td>{s.gangName}</td>
                    <td>{pool ? <b>{t('m09w.s.pool')}</b> : t('m09w.s.handling')}</td>
                    <td className="num">{pool ? '—' : (s.sanctioned ?? '—')}</td>
                    <td className="num">{s.present}</td>
                    <td className="num">{pool ? '—' : s.shortBy}</td>
                    <td>
                      {pool ? (
                        t('m09w.s.excluded')
                      ) : s.alertOpen ? (
                        <span className="pill p-bad">{t('m09w.s.open')}</span>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td>{pool ? t('m09w.s.source') : s.filledBy.join(', ') || '—'}</td>
                  </tr>
                );
              })
            }
          </ServerPagination>
        </Tbl>
        <Panel
          title={t('m09w.rules')}
          items={[
            {
              icon: '#',
              tone: 'info',
              title: t('m09w.r.sanctioned'),
              src: 'ENT',
              text: t('m09w.r.sanctionedM'),
            },
            { icon: '🔔', tone: 'warn', title: t('m09w.r.alert'), src: 'SYS', text: t('m09w.r.alertM') },
            { icon: '🔗', tone: 'info', title: t('m09w.r.fill'), src: 'ENT', text: t('m09w.r.fillM') },
            { icon: '🧺', tone: 'gray', title: t('m09w.r.pool'), src: 'SYS', text: t('m09w.r.poolM') },
            {
              icon: '👤',
              tone: 'gray',
              title: t('m09w.r.membership'),
              src: 'SYS',
              text: t('m09w.r.membershipM'),
            },
          ]}
        />
      </div>
      {viewing && (
        <ViewDialog
          screen={t('screen.M-09')}
          onClose={() => setViewing(null)}
          rows={[
            { k: t('m09w.col.gang'), v: `${viewing.gangCode} · ${viewing.gangName}`, s: 'ENT' },
            { k: t('m09w.col.shed'), v: viewing.shed ?? viewing.depot, s: 'HRMS' },
            { k: t('m09w.col.sardar'), v: viewing.sardar ?? '—', s: 'ENT' },
            { k: t('m09w.col.mandal'), v: viewing.mandal ?? '—', s: 'ENT' },
            { k: t('m09w.col.count'), v: viewing.labourCount, s: 'CALC' },
            { k: t('m09w.col.from'), v: date(viewing.effectiveFrom), s: 'ENT' },
            { k: t('m09w.col.to'), v: viewing.effectiveTo ? date(viewing.effectiveTo) : '—', s: 'ENT' },
            { k: t('m09w.col.status'), v: t(gangPill(viewing).key), s: 'SYS' },
          ]}
        />
      )}
      <RegisterSection id="m09-register" label={t('m09w.register')} open={register} onToggle={setRegister}>
        {register && (
          <GangRegister
            key={`${opening?.gang?.gangId ?? 0}|${String(opening?.startNew ?? false)}`}
            opening={opening?.gang ?? null}
            startNew={opening?.startNew ?? false}
          />
        )}
      </RegisterSection>
      <Problem error={firstPage.error ?? summary.error} />
    </div>
  );
}
