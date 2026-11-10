'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { useReference } from '@/shared/reference';
import { recentFinYears } from '@/shared/time';
import { date, Problem, useToast } from '@/shared/ui';
import { RegisterSection, SimulateAct, useMasterRights, useRouteApprove } from '@/shared/ui/masterParts';
import {
  Act,
  Explained,
  KvCard,
  type KvRow,
  Locked,
  Panel,
  RowActs,
  Tbl,
  Tiles,
  Toolbar,
  ViewDialog,
  WfTile,
} from '@/shared/ui/wireframe';
import type { OutputLine } from '../api/screensApi';
import type { OutputVersion, StatutoryOutput } from '../api/statutoryApi';
import { versionOfYear } from '../business/screenRules';
import { useOutputSummary } from '../orchestration/screensQueries';
import { useOutputs } from '../orchestration/statutoryQueries';
import { OutputDefinitions } from './OutputDefinitions';

interface Row {
  line: OutputLine;
  output: StatutoryOutput | undefined;
  version: OutputVersion | null;
}

/** The drawn Status of a row: the catalogue's state, never a state of its own (SRS M-17). */
function statusKey(row: Row): string {
  if (row.output && row.output.pendingApprovals.length > 0) return 'txw.m17.status.pending';
  if (row.version?.inForce) return 'txw.m17.status.active';
  return row.line.carriedForward ? 'txw.m17.status.carried' : 'txw.m17.status.blocked';
}

function formatKey(row: Row): string {
  if (row.line.carriedForward) return 'txw.m17.format.carried';
  return row.version?.layoutFormat === 'PRESCRIBED_FILE' ? 'txw.m17.format.file' : 'txw.m17.format.document';
}

/**
 * M-17 Statutory Output Definitions as Wireframes v6.1 draws it (OD-121; SRS M-17, BR-TAX-013, BR-MIS-002,
 * SRS-7-30): the tiles, the family and year filters from the DB list, the outputs with their version of the year,
 * the detail of the selected output and the validations. The pack's Statutory Administrator is the SRS Rule
 * Administrator: the proposal keeps its route and stays whole in the closed register (WF list).
 */
export function OutputDefinitionsScreen() {
  const { t } = useI18n();
  const toast = useToast();
  const { options } = useReference();
  const outputs = useOutputs();
  const summary = useOutputSummary();
  const rights = useMasterRights('M-17');
  const approve = useRouteApprove('M-17');
  const years = recentFinYears(2);
  const [family, setFamily] = useState('');
  const [finYear, setFinYear] = useState(years[0] ?? '');
  const [query, setQuery] = useState('');
  const [chosen, setChosen] = useState<string | null>(null);
  const [viewing, setViewing] = useState<Row | null>(null);
  const [open, setOpen] = useState(false);
  const rows: Row[] = (summary.data?.outputs ?? []).map((line) => {
    const output = outputs.data?.find((o) => o.key === line.key);
    return { line, output, version: output ? versionOfYear(output.versions, finYear) : null };
  });
  const needle = query.trim().toLowerCase();
  const shown = rows.filter(
    (r) =>
      (family === '' || r.line.family === family) &&
      (needle === '' || r.line.name.toLowerCase().includes(needle)),
  );
  const selected = shown.find((r) => r.line.key === chosen) ?? shown[0] ?? null;
  const pending = (outputs.data ?? []).filter((o) => o.pendingApprovals.length > 0);
  const active = rows.filter((r) => r.version?.inForce).length;
  const edit = () => {
    if (rights.entryWhy) toast(rights.entryWhy);
    else setOpen(true);
  };
  const statAdminWhy = t('txw.m17.why.statAdmin');
  const ruleAdminWhy = t('txw.m17.why.ruleAdmin');
  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <SimulateAct label={t('txw.m17.simulate')} />
        <Act label={t('txw.m17.history')} onClick={() => setOpen(true)} />
        {rights.drawn ? (
          <Act label={t('txw.m17.new')} kind="pri" onClick={edit} />
        ) : (
          <Locked label={t('txw.m17.new')} why={statAdminWhy} />
        )}
      </div>
      <Toolbar
        search={query}
        onSearch={setQuery}
        exportControl={<Explained label={t('wf.export')} why={t('txw.why.noExport')} />}
      >
        <select
          aria-label={t('txw.m17.allFamilies')}
          value={family}
          onChange={(e) => setFamily(e.target.value)}
        >
          <option value="">{t('txw.m17.allFamilies')}</option>
          {options('STATUTORY_OUTPUT_FAMILY').map((o) => (
            <option key={o.value} value={o.value}>
              {t(`txw.m17.family.${o.value}`)}
            </option>
          ))}
        </select>
        <select
          aria-label={t('txw.m17.effectiveFy', [finYear])}
          value={finYear}
          onChange={(e) => setFinYear(e.target.value)}
        >
          {years.map((y) => (
            <option key={y} value={y}>
              {t('txw.m17.effectiveFy', [y])}
            </option>
          ))}
        </select>
      </Toolbar>
      <Problem error={outputs.error ?? summary.error} />
      <Tiles>
        <WfTile
          icon="📜"
          label={t('txw.m17.tile.active')}
          src="RULE"
          big={active}
          sub={t('txw.m17.tile.activeSub', [summary.data?.families ?? 0])}
          tone="ok"
        />
        <WfTile
          icon="✍️"
          label={t('txw.m17.tile.pending')}
          src="SYS"
          big={pending.length}
          sub={pending.map((o) => o.name).join(' · ')}
          tone="warn"
        />
        <WfTile
          icon="📐"
          label={t('txw.m17.tile.carried')}
          src="SYS"
          big={summary.data?.carriedForward ?? 0}
          sub={t('txw.m17.tile.carriedSub')}
        />
        <WfTile
          icon="🗄"
          label={t('txw.m17.tile.superseded')}
          src="SYS"
          big={summary.data?.superseded ?? 0}
          sub={t('txw.m17.tile.supersededSub')}
          tone="pur"
        />
      </Tiles>
      <Tbl
        title={t('screen.M-17')}
        label={t('txw.m17.table')}
        acts={
          <>
            <Act label={t('txw.m17.compare')} kind="sm" onClick={() => setOpen(true)} />
            {rights.drawn ? (
              <Explained label={t('txw.m17.import')} kind="sm" why={t('txw.why.noImport')} />
            ) : (
              <Locked label={t('txw.m17.import')} kind="sm" why={ruleAdminWhy} />
            )}
          </>
        }
        cols={[
          { t: t('txw.m17.col.output'), s: 'RULE' },
          { t: t('txw.m17.col.reference'), s: 'RULE' },
          { t: t('txw.m17.col.periodicity'), s: 'RULE' },
          { t: t('txw.m17.col.format'), s: 'ENT' },
          { t: t('txw.m17.col.lock'), s: 'RULE' },
          { t: t('txw.m17.col.version'), s: 'SYS' },
          { t: t('txw.m17.col.from'), s: 'ENT' },
          { t: t('txw.m17.col.status'), s: 'SYS' },
          { t: t('txw.m17.col.actions'), s: 'SYS' },
        ]}
      >
        {shown.map((r) => (
          <tr
            key={r.line.key}
            data-reference={r.line.key}
            className={selected?.line.key === r.line.key ? 'fl-row-selected' : undefined}
            onClick={() => setChosen(r.line.key)}
          >
            <td>{r.line.name}</td>
            <td>{r.line.statutoryReference ?? '—'}</td>
            <td>{r.line.periodicity ?? '—'}</td>
            <td>{t(formatKey(r))}</td>
            <td>{r.line.lockRequirement === 'NONE' ? '—' : t('txw.m17.lockRequired')}</td>
            <td>{r.version ? `v${r.version.number}` : '—'}</td>
            <td>{date(r.version?.effectiveFrom)}</td>
            <td>
              <span className="pill">{t(statusKey(r))}</span>
            </td>
            <td onClick={(e) => e.stopPropagation()}>
              <RowActs
                viewName={`${t('common.view')} ${r.line.key}`}
                editName={`${t('common.edit')} ${r.line.key}`}
                onView={() => setViewing(r)}
                onEdit={rights.drawn ? edit : undefined}
                editWhy={t('txw.m17.why.edit')}
                onApprove={approve ? () => approve((r.output?.pendingApprovals.length ?? 0) > 0) : undefined}
              />
            </td>
          </tr>
        ))}
      </Tbl>
      {selected && (
        <Detail
          row={selected}
          act={
            rights.drawn ? <Act label={t('txw.m17.editDefinition')} kind="sm" onClick={edit} /> : undefined
          }
        />
      )}
      <Panel
        title={t('txw.m17.validations')}
        items={[
          { icon: '🔗', tone: 'bad', title: t('txw.m17.val.1'), src: 'RULE', text: t('txw.m17.val.1s') },
          { icon: '📅', tone: 'warn', title: t('txw.m17.val.2'), src: 'RULE', text: t('txw.m17.val.2s') },
          { icon: '🧮', tone: 'info', title: t('txw.m17.val.3'), src: 'RULE', text: t('txw.m17.val.3s') },
          { icon: '✍️', tone: 'pur', title: t('txw.m17.val.4'), src: 'RULE', text: t('txw.m17.val.4s') },
          { icon: '🗄', tone: 'gray', title: t('txw.m17.val.5'), src: 'SYS', text: t('txw.m17.val.5s') },
        ]}
      />
      <RegisterSection label={t('txw.m17.register')} open={open} onToggle={setOpen} id="m17-register">
        {open && <OutputDefinitions />}
      </RegisterSection>
      {viewing && (
        <ViewDialog
          screen={t('screen.M-17')}
          rows={detailRows(viewing, t)}
          onClose={() => setViewing(null)}
        />
      )}
    </div>
  );
}

function detailRows(row: Row, t: (key: string, args?: readonly unknown[]) => string): KvRow[] {
  const v = row.version;
  return [
    { k: t('txw.m17.d.identity'), v: '', section: true },
    { k: t('txw.m17.d.key'), v: row.line.key, s: 'SYS' },
    { k: t('txw.m17.d.reference'), v: row.line.statutoryReference ?? '—', s: 'ENT' },
    { k: t('txw.m17.d.periodicity'), v: row.line.periodicity ?? '—', s: 'ENT' },
    { k: t('txw.m17.d.binding'), v: '', section: true },
    ...(v?.fields ?? []).map((f) => ({ k: f.label, v: f.reference, s: 'RULE' as const })),
    { k: t('txw.m17.d.control'), v: '', section: true },
    {
      k: t('txw.m17.d.reconcile'),
      v: (v?.controls ?? []).map((c) => c.label).join(' + ') || '—',
      s: 'RULE',
    },
    {
      k: t('txw.m17.d.lock'),
      v: t(row.line.lockRequirement === 'NONE' ? 'txw.m17.d.lockNo' : 'txw.m17.d.lockYes'),
      s: 'RULE',
    },
    { k: t('txw.m17.d.path'), v: t('txw.m17.d.pathValue'), s: 'RULE' },
    { k: t('txw.m17.d.from'), v: date(v?.effectiveFrom), s: 'ENT' },
  ];
}

function Detail({ row, act }: { row: Row; act?: React.ReactNode }) {
  const { t } = useI18n();
  const name = row.line.name.split(' - ')[0];
  return (
    <KvCard
      title={t('txw.m17.detail', [name, row.version ? `v${row.version.number}` : '—'])}
      act={act}
      rows={detailRows(row, t)}
    />
  );
}
