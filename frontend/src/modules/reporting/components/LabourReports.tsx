'use client';

import { useEffect, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { periodLabel, useMe } from '@/shared/session';
import { ServerPagination, useToast } from '@/shared/ui';
import { Explained, KvCard, Locked, Panel, RowActs, Tbl } from '@/shared/ui/wireframe';
import type { CatalogueEntry } from '../api/reportApi';
import type { LabourEngine } from '../api/screensApi';
import { screenPages, useOptionsOfLabour } from '../orchestration/screensQueries';

const ENGINES: LabourEngine[] = ['DEPT', 'DPS', 'NWNP'];
const RULES: [string, string][] = [
  ['🏭', 'o03.why.1'],
  ['🧮', 'o03.why.2'],
  ['💰', 'o03.why.3'],
  ['📑', 'o03.why.4'],
];

/** The pill of a catalogue entry: Available, or Blocked with its reason (SRS-18-24). */
function statusPill(t: (k: string) => string, e: CatalogueEntry) {
  return e.status === 'BLOCKED' ? (
    <span className="pill p-warn" title={e.blockedReason ?? undefined}>
      {t('o03.status.blocked')}
    </span>
  ) : (
    <span className="pill p-ok">{t('o03.status.available')}</span>
  );
}

/**
 * O-03 Labour Registers & Reports as Wireframes v6.1 draws it (OD-121; FRS §6.21, Annexure A.4, A.11): the payroll type,
 * depot and gang filters, the Labour Report Catalogue, the report parameters, why the reports are separate and the
 * reports added from FCI feedback. The DPS menu entry opens the same screen with engine=DPS, as the pack's ENGINE()
 * does.
 *
 * The catalogue is report_definition. Every entry is Blocked today with its reason (V069_1: the computation rules and the
 * current-system layouts wait for FCI; SRS-18-16, SRS-18-24, OQ-12), so nothing here generates: ▶ Generate says so for the
 * Payroll Maker and is off with the pack's reason for the others. Depot and gang fill the parameters.
 */
export function LabourReports() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const me = useMe();
  const [engine, setEngine] = useState<LabourEngine>('DEPT');
  const [depot, setDepot] = useState('');
  const [gang, setGang] = useState('');
  useEffect(() => {
    const wanted = new URLSearchParams(window.location.search).get('engine');
    if (wanted === 'DPS' || wanted === 'NWNP') setEngine(wanted);
  }, []);
  const options = useOptionsOfLabour(depot);
  const maker = (me.data?.roles ?? []).includes('PAYROLL_MAKER');
  const checker = (me.data?.roles ?? []).includes('PAYROLL_CHECKER');
  const generate = maker ? (
    <Explained label={t('o03.act.generate')} kind="pri" why={t('o03.why.generate')} />
  ) : (
    <Locked label={t('o03.act.generate')} kind="pri" why={t('o03.why.maker')} />
  );
  const smallGenerate = maker ? (
    <Explained label={t('o03.act.generateSm')} kind="sm" why={t('o03.why.generate')} />
  ) : (
    <Locked label={t('o03.act.generateSm')} kind="sm" why={t('o03.why.maker')} />
  );
  const period = me.data?.period ? periodLabel(me.data.period, locale) : '—';
  return (
    <>
      <div className="toolbar">
        <span className="sp" />
        <Explained label={t('o03.act.schedule')} why={t('o03.why.schedule')} />
        {generate}
      </div>
      <div className="toolbar">
        <select
          aria-label={t('o03.filter.type')}
          value={engine}
          onChange={(e) => setEngine(e.target.value as LabourEngine)}
        >
          {ENGINES.map((e) => (
            <option key={e} value={e}>
              {t(`o03.engine.${e}`)}
            </option>
          ))}
        </select>
        <select
          aria-label={t('o03.filter.depot')}
          value={depot}
          onChange={(e) => {
            setDepot(e.target.value);
            setGang('');
          }}
        >
          <option value="">{t('o03.allDepots')}</option>
          {(options.data?.depots ?? []).map((d) => (
            <option key={d.id} value={d.id}>
              {d.label}
            </option>
          ))}
        </select>
        <select aria-label={t('o03.filter.gang')} value={gang} onChange={(e) => setGang(e.target.value)}>
          <option value="">{t('o03.allGangs')}</option>
          {(options.data?.gangs ?? []).map((g) => (
            <option key={g.id} value={g.code}>
              {g.code}
            </option>
          ))}
        </select>
        <span className="sp" />
        <Explained label={t('wf.export')} why={t('o03.why.generate')} />
      </div>
      <Tbl
        title={t('o03.catalogue')}
        acts={smallGenerate}
        cols={[
          { t: t('o03.col.report'), s: 'SYS' },
          { t: t('o03.col.applies'), s: 'RULE' },
          { t: t('o03.col.contents'), s: 'SYS' },
          { t: t('o03.col.scope'), s: 'ENT' },
          { t: t('o03.col.status'), s: 'SYS' },
          { t: t('o03.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={['labour-catalogue', engine, 'main']}
          fetchPage={screenPages.labourCatalogue(false, engine)}
          empty={t('o03.none')}
        >
          {(rows) =>
            rows.map((e) => (
              <tr key={e.key}>
                <td>
                  <b>{e.name}</b>
                </td>
                <td>{e.payrollTypes ?? '—'}</td>
                <td>{t(`o03.contents.${e.key}`)}</td>
                <td>{t(`o03.scope.${e.key}`)}</td>
                <td>{statusPill(t, e)}</td>
                <td>
                  <RowActs
                    onView={() => toast(e.blockedReason ?? t('o03.status.available'))}
                    editWhy={t('o03.why.edit')}
                    onApprove={checker ? () => toast(t('o03.why.validate')) : undefined}
                    viewName={t('o03.viewName', [e.name])}
                    editName={t('o03.editName', [e.name])}
                  />
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <KvCard
          title={t('o03.parameters')}
          rows={[
            { k: t('o03.p.period'), v: period, s: 'ENT' },
            { k: t('o03.p.region'), v: t('o03.p.regionV'), s: 'ENT' },
            {
              k: t('o03.p.depot'),
              v: `${options.data?.depots.find((d) => String(d.id) === depot)?.label ?? t('o03.allDepots')} · ${gang || t('o03.allGangs')}`,
              s: 'ENT',
            },
            { k: t('o03.p.type'), v: t(`o03.engine.${engine}`), s: 'ENT' },
            { k: t('o03.p.designation'), v: t('o03.p.designationV'), s: 'ENT' },
            { k: t('o03.p.output'), v: t('o03.p.outputV'), s: 'ENT' },
            { k: t('o03.p.source'), v: t('o03.p.sourceV'), s: 'SYS' },
          ]}
        />
        <Panel
          title={t('o03.separate')}
          items={RULES.map(([icon, key], i) => ({
            icon,
            tone: 'info' as const,
            src: i === 3 ? ('SYS' as const) : ('RULE' as const),
            title: t(`${key}.t`),
            text: t(`${key}.m`),
          }))}
        />
      </div>
      <div className="fl-mt">
        <Tbl
          title={t('o03.annexure')}
          acts={
            <>
              {smallGenerate}
              <Explained label={t('m01.w.exportShort')} kind="sm" why={t('o03.why.generate')} />
            </>
          }
          cols={[
            { t: t('o03.a.report'), s: 'RULE' },
            { t: t('o03.a.contents'), s: 'RULE' },
            { t: t('o03.col.applies'), s: 'RULE' },
            { t: t('o03.a.output'), s: 'SYS' },
            { t: t('o03.col.status'), s: 'SYS' },
          ]}
          after={
            <div className="bd fl-pad-top-0">
              <div className="oq">{t('o03.a.note')}</div>
            </div>
          }
        >
          <ServerPagination
            queryKey={['labour-catalogue', engine, 'annexure']}
            fetchPage={screenPages.labourCatalogue(true, engine)}
            empty={t('o03.none')}
          >
            {(rows) =>
              rows.map((e) => (
                <tr key={e.key}>
                  <td>
                    <b>{e.name}</b>
                  </td>
                  <td>{t(`o03.contents.${e.key}`)}</td>
                  <td>{e.payrollTypes ?? '—'}</td>
                  <td>{t('o03.a.outputV')}</td>
                  <td>{statusPill(t, e)}</td>
                </tr>
              ))
            }
          </ServerPagination>
        </Tbl>
      </div>
    </>
  );
}
