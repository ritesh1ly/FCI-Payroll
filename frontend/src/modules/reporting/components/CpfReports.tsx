'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { periodLabel, useMe } from '@/shared/session';
import { Problem, ServerPagination, useToast } from '@/shared/ui';
import { Explained, Locked, RowActs, Tbl, Tiles, ViewDialog, WfTile } from '@/shared/ui/wireframe';
import type { CatalogueEntry } from '../api/reportApi';
import { screenPages, useCpfMonths } from '../orchestration/screensQueries';

/** A month written yyyymm, as the session writes it (yyyy-mm). */
const dashed = (yymm: string) => `${yymm.slice(0, 4)}-${yymm.slice(4)}`;

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
 * O-07 CPF & e-Seva Reports as Wireframes v6.1 draws it (OD-121; BR-CPF-036, Annexure A.4.6): the family from
 * report_definition, in the drawn order, and whether the month is Provisional or Final from the cycle state. Nothing
 * generates: the current-system layouts are not in the requirement set (OQ-12, Q-07-10) and the ECR file format neither
 * (OQ-23), so Generate, Bulk generate and Send to CPF Division are drawn for the role the pack gives them and say why,
 * and each entry shows its blocked reason (PENDING 07j). The Provisional marking waits with the reports.
 */
export function CpfReports() {
  const { t, locale } = useI18n();
  const me = useMe();
  const roles = me.data?.roles ?? [];
  const maker = roles.includes('PAYROLL_MAKER');
  const checker = roles.includes('PAYROLL_CHECKER');
  const period = me.data?.period ?? '';
  const months = useCpfMonths(period.replace('-', ''));
  const m = months.data;
  const why = t('o07w.why.generate');
  const label = period ? periodLabel(period, locale) : '—';
  const previous = m ? periodLabel(dashed(m.previous), locale) : '—';
  const versionOf = (e: CatalogueEntry) =>
    e.lockRequirement === 'NONE' || e.lockRequirement === 'POST_APPROVAL' || !m
      ? '—'
      : t(`o07w.version.${m.version}`);
  return (
    <>
      <div className="toolbar">
        <span className="sp" />
        {checker ? (
          <Explained label={t('o07w.act.send')} why={why} />
        ) : (
          <Locked label={t('o07w.act.send')} why={t('o07w.why.send')} />
        )}
        {maker ? (
          <Explained label={t('o07w.act.generate')} kind="pri" why={why} />
        ) : (
          <Locked label={t('o07w.act.generate')} kind="pri" why={t('o07w.why.maker')} />
        )}
      </div>
      <Problem error={months.error} />
      <Tiles>
        <WfTile icon="🏦" label={t('o07w.tile.family')} src="RULE" big={12} sub={t('o07w.tile.familySub')} />
        <WfTile
          icon="🧪"
          label={t('o07w.tile.run', [label])}
          src="SYS"
          big={m ? t(`o07w.version.${m.version}`) : '—'}
          sub={t(`o07w.tile.run.${m?.version ?? 'PROVISIONAL'}`)}
        />
        <WfTile
          icon="✅"
          label={previous}
          src="SYS"
          big={m ? t(`o07w.version.${m.previousVersion}`) : '—'}
          sub={t(`o07w.tile.prev.${m?.previousVersion ?? 'PROVISIONAL'}`)}
        />
        <WfTile
          icon="#"
          label={t('o07w.tile.count')}
          src="CALC"
          big={t('o07w.tile.countBig')}
          sub={t('o07w.tile.countSub')}
        />
      </Tiles>
      <Tbl
        title={t('o07w.table', [label])}
        acts={
          <>
            {maker ? (
              <Explained label={t('o07w.act.bulk')} kind="sm" why={why} />
            ) : (
              <Locked label={t('o07w.act.bulk')} kind="sm" why={t('o07w.why.bulk')} />
            )}
            <Explained label={t('o07w.act.log')} kind="sm" why={why} />
          </>
        }
        cols={[
          { t: t('o07w.col.report'), s: 'SYS' },
          { t: t('o07w.col.level'), s: 'RULE' },
          { t: t('o07w.col.trigger'), s: 'RULE' },
          { t: t('o07w.col.version'), s: 'SYS' },
          { t: t('o07w.col.status'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={['cpf-reports', 'O-07']}
          fetchPage={screenPages.cpfEseva}
          empty={t('o03.none')}
        >
          {(rows) =>
            rows.map((e) => (
              <tr key={e.key}>
                <td>
                  <b>{t(`o07w.r.${e.key}`)}</b>
                </td>
                <td>{t(`o07w.level.${e.key}`)}</td>
                <td>{t(`o07w.trigger.${e.key}`)}</td>
                <td>{versionOf(e)}</td>
                <td>{statusPill(t, e)}</td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
    </>
  );
}

/**
 * O-08 CPF Reconciliation & OBCB Reports as Wireframes v6.1 draws it (OD-121; SRS O-08 table): the CPF report set with
 * purpose, frequency, layout and status. The frequency is report_definition's, from the SRS table (WF-277). Nothing
 * generates (PENDING 07j, Q-07-10); a definition is edited on M-12, and a row's View says what it waits for.
 */
export function CpfReconciliationReports() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const roles = me.data?.roles ?? [];
  const maker = roles.includes('PAYROLL_MAKER');
  const checker = roles.includes('PAYROLL_CHECKER');
  const [viewing, setViewing] = useState<CatalogueEntry | null>(null);
  const why = t('o07w.why.generate');
  return (
    <>
      <div className="toolbar">
        <span className="sp" />
        <Explained label={t('o08w.act.schedule')} why={why} />
        {maker ? (
          <Explained label={t('o08w.act.generate')} kind="pri" why={why} />
        ) : (
          <Locked label={t('o08w.act.generate')} kind="pri" why={t('o07w.why.maker')} />
        )}
      </div>
      <Tbl
        title={t('o08w.table')}
        acts={<Explained label={t('o08w.act.generateSm')} kind="sm" why={why} />}
        cols={[
          { t: t('o08w.col.report'), s: 'SYS' },
          { t: t('o08w.col.purpose'), s: 'SYS' },
          { t: t('o08w.col.frequency'), s: 'RULE' },
          { t: t('o08w.col.layout'), s: 'ENT' },
          { t: t('o08w.col.status'), s: 'SYS' },
          { t: t('o08w.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={['cpf-reports', 'O-08']}
          fetchPage={screenPages.cpfReconciliation}
          empty={t('o03.none')}
        >
          {(rows) =>
            rows.map((e) => (
              <tr key={e.key}>
                <td>
                  <b>{t(`o08w.r.${e.key}`)}</b>
                </td>
                <td>{t(`o08w.purpose.${e.key}`)}</td>
                <td>{e.periodicity ?? '—'}</td>
                <td>{t('o08w.layout')}</td>
                <td>{statusPill(t, e)}</td>
                <td>
                  <RowActs
                    onView={() => setViewing(e)}
                    editWhy={t('o08w.why.edit')}
                    onApprove={checker ? () => toast(t('o08w.why.validate')) : undefined}
                    viewName={t('o08w.viewName', [t(`o08w.r.${e.key}`)])}
                    editName={t('o08w.editName', [t(`o08w.r.${e.key}`)])}
                  />
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      {viewing && (
        <ViewDialog
          screen={t('screen.O-08')}
          onClose={() => setViewing(null)}
          rows={[
            { k: t('o08w.col.report'), v: t(`o08w.r.${viewing.key}`), s: 'SYS' },
            { k: t('o08w.col.purpose'), v: t(`o08w.purpose.${viewing.key}`), s: 'SYS' },
            { k: t('o08w.col.frequency'), v: viewing.periodicity ?? '—', s: 'RULE' },
            { k: t('o08w.col.status'), v: viewing.blockedReason ?? t('o03.status.available'), s: 'SYS' },
          ]}
        />
      )}
    </>
  );
}
