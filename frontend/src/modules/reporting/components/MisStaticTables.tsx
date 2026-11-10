'use client';

import { useI18n } from '@/shared/i18n';
import { Explained, Panel, Tbl } from '@/shared/ui/wireframe';
import type { CatalogueEntry } from '../api/reportApi';
import { A11_EMPLOYEE_KEYS } from '../business/reportRules';
import { EntryPill } from './catalogueParts';

const SPEC_ROWS = ['filters', 'output', 'lock', 'totals', 'columns'] as const;
const ADDED_ROWS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11'] as const;

/** O-04 Report specification standard and the reports added in FRS v0.4, text of the pack (FRS §23, en + hi). */
export function MisStandards() {
  const { t } = useI18n();
  return (
    <div className="g2 fl-mt">
      <Tbl
        title={t('o04w.spec')}
        cols={[
          { t: t('o04w.col.element'), s: 'SYS' },
          { t: t('o04w.col.standard'), s: 'RULE' },
        ]}
      >
        {SPEC_ROWS.map((r) => (
          <tr key={r}>
            <td>{t(`o04w.spec.${r}`)}</td>
            <td>{t(`o04w.spec.${r}.v`)}</td>
          </tr>
        ))}
      </Tbl>
      <Tbl
        title={t('o04w.added')}
        cols={[
          { t: t('o04w.col.report'), s: 'SYS' },
          { t: t('o04w.col.source'), s: 'SYS' },
        ]}
      >
        {ADDED_ROWS.map((r) => (
          <tr key={r}>
            <td>{t(`o04w.added.${r}`)}</td>
            <td>{t(`o04w.added.${r}.s`)}</td>
          </tr>
        ))}
      </Tbl>
    </div>
  );
}

/**
 * O-04 "Reports and certificates added from FCI feedback" (SRS-18-25, Annexure A.11): the employee rows as drawn, each
 * with the state of its catalogue entry (no entry reads Available unless the API says so). Nothing is generated or
 * exported from this list: each report waits for its layout or is produced on its own screen (15-layouts).
 */
export function MisAnnexure({ entries }: { entries: readonly CatalogueEntry[] }) {
  const { t } = useI18n();
  const byKey = new Map(entries.map((e) => [e.key, e]));
  const why = t('o04w.why.a11');
  return (
    <Tbl
      title={t('o04w.a11')}
      acts={
        <>
          <Explained label={t('action.o04.generate')} kind="sm" why={why} />
          <Explained label={t('action.export')} kind="sm" why={why} />
        </>
      }
      cols={[
        { t: t('o04w.col.a11Report'), s: 'RULE' },
        { t: t('o04w.col.contents'), s: 'RULE' },
        { t: t('o04w.col.appliesTo'), s: 'RULE' },
        { t: t('o04w.col.output'), s: 'SYS' },
        { t: t('o04w.col.status'), s: 'SYS' },
      ]}
      after={
        <div className="bd fl-pad-top-0">
          <div className="oq" data-testid="o04-note">
            {t('o04w.a11.note')}
          </div>
        </div>
      }
    >
      {A11_EMPLOYEE_KEYS.map((key) => (
        <tr key={key}>
          <td>
            <b>{t(`o04w.a11.${key}`)}</b>
          </td>
          <td>{t(`o04w.a11.${key}.c`)}</td>
          <td>{t(`o04w.a11.${key}.a`)}</td>
          <td>{t('o04w.a11.output')}</td>
          <td>
            <EntryPill entry={byKey.get(key)} />
          </td>
        </tr>
      ))}
    </Tbl>
  );
}

/** O-04 BI dashboard and report builder (SRS-18-11): drawn; the data set and KPIs are FCI's to give (CLR-101, 15-bi). */
export function MisBiPanel() {
  const { t } = useI18n();
  return (
    <div className="fl-mt">
      <Panel
        title={t('o04w.bi')}
        act={<Explained label={t('o04w.act.dashboard')} kind="sm" why={t('o04w.why.bi')} />}
        items={[
          { icon: '📊', tone: 'info', title: t('o04w.bi.total'), src: 'CALC', text: t('o04w.bi.total.m') },
          { icon: '🧩', tone: 'info', title: t('o04w.bi.builder'), src: 'ENT', text: t('o04w.bi.builder.m') },
          { icon: '📈', tone: 'gray', title: t('o04w.bi.views'), src: 'ENT', text: t('o04w.bi.views.m') },
        ]}
      />
    </div>
  );
}
