'use client';

import { useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { can, useMe } from '@/shared/session';
import { Problem, ServerPagination, useToast, withBase } from '@/shared/ui';
import {
  Act,
  Explained,
  KvCard,
  Locked,
  Panel,
  RowActs,
  Tbl,
  Tiles,
  Toolbar,
  ViewDialog,
  WfTile,
  type KvRow,
} from '@/shared/ui/wireframe';
import type { CatalogueEntry, DefinitionFilter, SectionCount } from '../api/reportApi';
import { CPF_DETAIL_KEYS, DETAIL_KEY } from '../business/reportRules';
import {
  definitionsPage,
  reportKeys,
  useDefinitionSet,
  useDefinitionSummary,
} from '../orchestration/reportQueries';
import { EntryPill, valueLabel } from './catalogueParts';
import { DefinitionVersions } from './DefinitionVersions';

const RULES = ['committed', 'reproducible', 'parity', 'gl', 'bindings'] as const;
const RULE_ICONS: Record<(typeof RULES)[number], string> = {
  committed: '🔒',
  reproducible: '♻️',
  parity: '⚖️',
  gl: '🧾',
  bindings: '🔗',
};
const RULE_TONES = {
  committed: 'bad',
  reproducible: 'ok',
  parity: 'info',
  gl: 'info',
  bindings: 'warn',
} as const;

/**
 * M-12 Report Definition Catalogue as Wireframes v6.1 draws it (OD-121; SRS-18-9, SRS-18-10, SRS-18-24, BR-MIS-002,
 * BR-MIS-003, Annexure A.4, A.11): the counts and the families from the catalogue, the filters from the M-16 lists
 * REPORT_FAMILY and REPORT_PAYROLL_TYPE (OD-123), the entry state from the API. The definitions with their versions and
 * the proposal form stay whole behind Version history. What has no rule or engine is drawn as the pack draws it and
 * says why when pressed, sending nothing: Preview (no report engine), New definition (CLR-110, CLR-32), Bulk activate
 * (approval activates, BR-MIS-003), Import layout (15-layouts) and the exports (M-12 has no EXPORT permission); the
 * pack's disabled ones stay disabled with the reason. OD-135: the demo System Administrator gets no new right.
 */
export function ReportCatalogue() {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const mayEdit = can(me.data, 'M-12', 'CREATE');
  const roles = me.data?.roles ?? [];
  const drawnAdmin = roles.includes('SYSTEM_ADMINISTRATOR');
  const checker = roles.includes('PAYROLL_CHECKER') || can(me.data, 'M-12', 'APPROVE');
  const [filter, setFilter] = useState<DefinitionFilter>({ family: '', payrollType: '', text: '' });
  const [versionsOpen, setVersionsOpen] = useState(false);
  const [openKey, setOpenKey] = useState<string | null>(null);
  const [viewing, setViewing] = useState<KvRow[] | null>(null);
  const summary = useDefinitionSummary();
  const set = useDefinitionSet();
  const s = summary.data;
  const byKey = new Map((set.data ?? []).map((e) => [e.key, e]));
  const sections = s?.sections ?? [];
  const adminOnly = t('m12w.why.adminOnly');
  const exportWhy = t('m12w.why.export');
  const sumOf = (test: (section: string) => boolean) =>
    sections.filter((x) => test(x.section)).reduce((sum, x) => sum + x.reports, 0);

  function openVersions(key: string) {
    setOpenKey(key);
    setVersionsOpen(true);
  }

  const sectionPill = (x: SectionCount) => {
    if (x.blocked === 0) return <span className="pill p-ok">{t('m12w.status.active')}</span>;
    return (
      <span className="pill p-warn">
        {x.blocked === x.reports ? t('m12w.status.allBlocked') : t('m12w.status.someBlocked', [x.blocked])}
      </span>
    );
  };

  function viewFamily(x: SectionCount) {
    setViewing([
      { k: t('m12w.col.family'), v: valueLabel(t, 'm12w.section', x.section), s: 'RULE' },
      { k: t('m12w.col.reports'), v: x.reports, s: 'SYS' },
      { k: t('m12w.col.examples'), v: valueLabel(t, 'm12w.examples', x.section), s: 'RULE' },
      { k: t('m12w.col.committed'), v: valueLabel(t, 'm12w.committed', x.section), s: 'RULE' },
      { k: t('m12w.col.status'), v: sectionPill(x), s: 'SYS' },
    ]);
  }

  function viewCpf(key: string, e: CatalogueEntry | undefined) {
    setViewing([
      { k: t('m12w.col.report'), v: t(`m12w.cpf.${key}.name`), s: 'SYS' },
      { k: t('m12w.col.generatedAt'), v: t(`m12w.cpf.${key}.at`), s: 'RULE' },
      { k: t('m12w.col.contents'), v: t(`m12w.cpf.${key}.contents`), s: 'SYS' },
      { k: t('m12w.col.trigger'), v: t(`m12w.cpf.${key}.trigger`), s: 'RULE' },
      { k: t('m12w.col.status'), v: <EntryPill entry={e} />, s: 'SYS' },
      { k: t('m12w.dialog.reason'), v: e?.blockedReason ?? '—', s: 'SYS' },
      { k: t('m12w.dialog.change'), v: t('m12w.dialog.changeText'), s: 'RULE' },
    ]);
  }

  const detail = byKey.get(DETAIL_KEY);
  const detailRows: KvRow[] = [
    { k: t('m12w.detail.identity'), v: '', section: true },
    { k: t('m12w.detail.key'), v: detail?.key ?? '—', s: 'SYS' },
    {
      k: t('m12w.detail.family'),
      v: detail ? `${valueLabel(t, 'm12w.family', detail.family)} · ${detail.periodicity ?? '—'}` : '—',
      s: 'ENT',
    },
    { k: t('m12w.detail.types'), v: detail?.payrollTypes ?? '—', s: 'ENT' },
    { k: t('m12w.detail.parameters'), v: '', section: true },
    { k: t('m12w.detail.period'), v: t('m12w.detail.period.v'), s: 'ENT' },
    { k: t('m12w.detail.office'), v: t('m12w.detail.office.v'), s: 'ENT' },
    { k: t('m12w.detail.grain'), v: t('m12w.detail.grain.v'), s: 'ENT' },
    { k: t('m12w.detail.bindings'), v: '', section: true },
    { k: t('m12w.detail.bags'), v: t('m12w.detail.bags.v'), s: 'CALC' },
    { k: t('m12w.detail.count'), v: t('m12w.detail.count.v'), s: 'CALC' },
    { k: t('m12w.detail.time'), v: t('m12w.detail.time.v'), s: 'ENT' },
    { k: t('m12w.detail.amount'), v: t('m12w.detail.amount.v'), s: 'CALC' },
    { k: t('m12w.detail.control'), v: '', section: true },
    { k: t('m12w.detail.reconciles'), v: t('m12w.detail.reconciles.v'), s: 'RULE' },
    { k: t('m12w.detail.reads'), v: t('m12w.detail.reads.v'), s: 'RULE' },
    {
      k: t('m12w.detail.lock'),
      v: detail ? t(detail.lockRequirement === 'NONE' ? 'm12w.no' : 'm12w.yes') : '—',
      s: 'RULE',
    },
    { k: t('m12w.detail.legacy'), v: t('m12w.detail.legacy.v'), s: 'RULE' },
  ];
  // OD-135: the drawn admin keeps the object and is told why; others are not drawn it; the Report Administrator uses it.
  const editDefinition = mayEdit ? (
    <Act label={t('m12w.act.edit')} kind="sm" onClick={() => openVersions(DETAIL_KEY)} />
  ) : drawnAdmin ? (
    <Explained label={t('m12w.act.edit')} kind="sm" why={adminOnly} />
  ) : undefined;

  return (
    <>
      <div className="toolbar">
        <span className="sp" />
        <HistoryButton
          entity="report_catalogue_version"
          recordKey={DETAIL_KEY}
          label={t('m12w.detail.name')}
          text={t('wf.historyAct')}
          className="btn"
        />
        <Explained label={t('m12w.act.preview')} why={t('m12w.why.preview')} />
        <Act label={t('m12w.act.versions')} onClick={() => setVersionsOpen((v) => !v)} />
        {mayEdit ? (
          <Explained label={t('m12w.act.new')} kind="pri" why={t('m12w.why.newAwaited')} />
        ) : drawnAdmin ? (
          <Explained label={t('m12w.act.new')} kind="pri" why={t('m12w.why.newAdminOnly')} />
        ) : (
          <Locked label={t('m12w.act.new')} why={t('m12w.why.newAdminOnly')} />
        )}
      </div>
      {versionsOpen && (
        <div className="fl-mt" data-testid="m12-versions">
          <DefinitionVersions key={openKey ?? 'all'} initial={openKey} />
        </div>
      )}
      <Toolbar
        search={filter.text}
        onSearch={(text) => setFilter({ ...filter, text })}
        exportControl={<Explained label={t('wf.export')} why={exportWhy} />}
      >
        <select
          aria-label={t('m12w.filter.family')}
          value={filter.family}
          onChange={(e) => setFilter({ ...filter, family: e.target.value })}
        >
          <option value="">{t('m12w.filter.family')}</option>
          <ReferenceOptions list="REPORT_FAMILY" />
        </select>
        <select
          aria-label={t('m12w.filter.type')}
          value={filter.payrollType}
          onChange={(e) => setFilter({ ...filter, payrollType: e.target.value })}
        >
          <option value="">{t('m12w.filter.type')}</option>
          <ReferenceOptions list="REPORT_PAYROLL_TYPE" />
        </select>
      </Toolbar>
      <Problem error={summary.error} />
      <Problem error={set.error} />
      <Tiles>
        <WfTile
          icon="📚"
          label={t('m12w.tile.size')}
          src="RULE"
          big={s?.size ?? '—'}
          sub={t('m12w.tile.sizeSub', [sumOf((x) => x.startsWith('A.4')), sumOf((x) => x === 'A.11')])}
        />
        <WfTile
          icon="✅"
          tone="ok"
          label={t('m12w.tile.active')}
          src="SYS"
          big={s?.active ?? '—'}
          sub={t('m12w.tile.activeSub')}
        />
        <WfTile icon="📐" label={t('m12w.tile.layouts')} src="SYS" big="—" sub={t('m12w.tile.layoutsSub')} />
        <WfTile
          icon="↪"
          tone="ok"
          label={t('m12w.tile.carried')}
          src="SYS"
          big="—"
          sub={t('m12w.tile.carriedSub')}
        />
      </Tiles>
      <Tbl
        title={t('m12w.byFamily')}
        acts={
          <>
            {checker ? (
              <Explained label={t('m12w.act.bulk')} kind="sm" why={t('m12w.why.bulk')} />
            ) : (
              <Locked label={t('m12w.act.bulk')} kind="sm" why={t('m12w.why.bulk')} />
            )}
            <Explained label={t('m12w.act.exportCatalogue')} kind="sm" why={exportWhy} />
          </>
        }
        cols={[
          { t: t('m12w.col.family'), s: 'RULE' },
          { t: t('m12w.col.reports'), s: 'SYS', num: true },
          { t: t('m12w.col.examples'), s: 'RULE' },
          { t: t('m12w.col.committed'), s: 'RULE' },
          { t: t('m12w.col.status'), s: 'SYS' },
          { t: t('m12w.col.actions'), s: 'SYS' },
        ]}
        foot={
          <tr>
            <td>
              <b>{t('m12w.total')}</b>
            </td>
            <td className="num">
              <b>{s?.size ?? '—'}</b>
            </td>
            <td>
              <b>{t('m12w.foot')}</b>
            </td>
            <td />
            <td />
            <td />
          </tr>
        }
      >
        {sections.map((x) => {
          const label = valueLabel(t, 'm12w.section', x.section);
          return (
            <tr key={x.section}>
              <td>
                <b>{label}</b>
              </td>
              <td className="num">{x.reports}</td>
              <td>{valueLabel(t, 'm12w.examples', x.section)}</td>
              <td>{valueLabel(t, 'm12w.committed', x.section)}</td>
              <td>{sectionPill(x)}</td>
              <td>
                <RowActs
                  onView={() => viewFamily(x)}
                  onEdit={mayEdit ? () => toast(t('m12w.why.editFamily')) : undefined}
                  editWhy={t('m12w.why.edit')}
                  onApprove={checker ? () => toast(t('m12w.why.approve')) : undefined}
                  viewName={t('m12w.viewName', [label])}
                  editName={t('m12w.editName', [label])}
                />
              </td>
            </tr>
          );
        })}
      </Tbl>
      <div className="fl-mt">
        <Tbl
          title={t('m12w.cpfTable')}
          acts={
            mayEdit || drawnAdmin ? (
              <Explained
                label={t('m12w.act.import')}
                kind="sm"
                why={mayEdit ? t('m12w.why.import') : t('m12w.why.adminShort')}
              />
            ) : (
              <Locked label={t('m12w.act.import')} kind="sm" why={t('m12w.why.adminShort')} />
            )
          }
          cols={[
            { t: t('m12w.col.report'), s: 'SYS' },
            { t: t('m12w.col.generatedAt'), s: 'RULE' },
            { t: t('m12w.col.contents'), s: 'SYS' },
            { t: t('m12w.col.trigger'), s: 'RULE' },
            { t: t('m12w.col.status'), s: 'SYS' },
            { t: t('m12w.col.actions'), s: 'SYS' },
          ]}
        >
          {CPF_DETAIL_KEYS.map((key) => {
            const e = byKey.get(key);
            const name = t(`m12w.cpf.${key}.name`);
            return (
              <tr key={key}>
                <td>
                  <b>{name}</b>
                </td>
                <td>{t(`m12w.cpf.${key}.at`)}</td>
                <td>{t(`m12w.cpf.${key}.contents`)}</td>
                <td>{t(`m12w.cpf.${key}.trigger`)}</td>
                <td>
                  <EntryPill entry={e} />
                </td>
                <td>
                  <RowActs
                    onView={() => viewCpf(key, e)}
                    onEdit={mayEdit ? () => openVersions(key) : undefined}
                    editWhy={t('m12w.why.edit')}
                    onApprove={checker ? () => toast(t('m12w.why.approve')) : undefined}
                    viewName={t('m12w.viewName', [name])}
                    editName={t('m12w.editName', [name])}
                  />
                </td>
              </tr>
            );
          })}
        </Tbl>
      </div>
      <div className="g2 fl-mt">
        <KvCard
          title={t('m12w.detail.title')}
          act={editDefinition}
          rows={detailRows}
          after={
            detail?.blockedReason ? (
              <p className="sub2" data-testid="m12-detail-blocked">
                {t('m12w.detail.blocked', [detail.blockedReason])}
              </p>
            ) : undefined
          }
        />
        <Panel
          title={t('m12w.rules')}
          items={RULES.map((r) => ({
            icon: RULE_ICONS[r],
            tone: RULE_TONES[r],
            title: t(`m12w.rule.${r}`),
            src: 'RULE' as const,
            text: t(`m12w.rule.${r}.m`),
          }))}
        />
      </div>
      <div className="fl-mt">
        <Tbl
          title={t('m12w.full')}
          acts={<Explained label={t('m12w.act.exportCatalogue')} kind="sm" why={exportWhy} />}
          cols={[
            { t: t('m12w.col.familyCode'), s: 'SYS' },
            { t: t('m12w.col.report'), s: 'SYS' },
            { t: t('m12w.col.producedOn'), s: 'SYS' },
          ]}
        >
          <ServerPagination
            queryKey={reportKeys.definitions(filter)}
            fetchPage={definitionsPage(filter)}
            empty={t('o04.none')}
          >
            {(rows) =>
              rows.map((r) => (
                <tr key={r.key}>
                  <td>{r.section}</td>
                  <td>{r.name}</td>
                  <td>
                    <a href={withBase(`/payroll/?s=${r.producingScreen}`)}>{r.producingScreen}</a>
                  </td>
                </tr>
              ))
            }
          </ServerPagination>
        </Tbl>
      </div>
      {viewing && <ViewDialog screen={t('m12w.dialog')} rows={viewing} onClose={() => setViewing(null)} />}
    </>
  );
}
