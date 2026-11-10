'use client';

import { useState, type ReactNode } from 'react';
import { API_BASE } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { can, periodLabel, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import {
  Card,
  Empty,
  ExportButton,
  MonthField,
  Problem,
  ServerPagination,
  Src,
  amount,
  useToast,
  withBase,
  usePdfDownload,
} from '@/shared/ui';
import {
  Act,
  Explained,
  KvCard,
  Locked,
  Panel,
  RowActs,
  Tbl,
  ViewDialog,
  type KvRow,
} from '@/shared/ui/wireframe';
import type { CatalogueEntry, Level, MemberStatus, SummaryFilters } from '../api/reportApi';
import {
  LEVELS,
  MEMBER_STATUSES,
  exportBlockedWhy,
  familiesOf,
  isYymm,
  reportChoices,
} from '../business/reportRules';
import {
  cataloguePage,
  reportKeys,
  summaryExportUrl,
  useSummary,
  useCatalogueChoices,
  useLatestLocked,
  useSummaryOptions,
} from '../orchestration/reportQueries';
import { EntryPill, valueLabel } from './catalogueParts';
import { MisAnnexure, MisBiPanel, MisStandards } from './MisStaticTables';

/**
 * O-04 MIS & Statutory Reports as Wireframes v6.1 draws it (OD-121; plan 15 phase 15b; SRS-18-5, SRS-18-8, SRS-18-23,
 * SRS-18-24, SRS-18-28; D15-1 - D15-4): the report catalogue with where each output is produced, or why it is Blocked;
 * and, in a closed section under the parameters card, the Component-wise Summary of a month at a hierarchy level from
 * committed results, marked Provisional before the lock, reconciled to the posted payroll (a mismatch halts the export).
 * SRS-18-12 (a), (c), (d), SRS-G-40 (US-GAP-37): the summary is narrowed to an office of the user's
 * scope, a payroll type, a category and current / separated members; the file comes in English or
 * Hindi with the report header and the sub-totals at each level.
 * FRS §23.9 (G59): generating writes report_output, so the summary runs only on Generate or Recheck, never on a filter
 * change. The drawn top Generate is the Payroll Maker's; the small one over the catalogue stays for the other actors of
 * SRS l.3472, and the API stays VIEW.
 */
export function MisReports() {
  const { t, locale } = useI18n();
  const me = useMe();
  const toast = useToast();
  const [family, setFamily] = useState('');
  const [text, setText] = useState('');
  const [yymm, setYymmState] = useState(() => businessToday().slice(0, 7).replace('-', ''));
  // SRS l.3471 / l.3501: the screen opens on the latest locked month (the open business month
  // when none is locked yet), until the user picks another.
  const latest = useLatestLocked();
  const [picked, setPicked] = useState(false);
  const shown = !picked && latest.data?.yymm ? latest.data.yymm : yymm;
  function setYymm(next: string) {
    setPicked(true);
    setYymmState(next);
    // l.3502: an office of another month's options is not kept.
    setFilters((f) => ({ ...f, officeId: null }));
  }
  const [level, setLevel] = useState<Level>('UNIT');
  const [filters, setFilters] = useState<SummaryFilters>({
    officeId: null,
    payrollType: '',
    category: '',
    memberStatus: 'BOTH',
  });
  const [lang, setLang] = useState<'en' | 'hi'>('en');
  // O-04 l.3500: the report chosen from the catalogue; only one produced here can be generated.
  const choices = useCatalogueChoices();
  const [report, setReport] = useState('COMPONENT_WISE_SUMMARY');
  const entries = choices.data?.items ?? [];
  const offered = reportChoices(entries);
  const chosen = offered.find((e) => e.key === report);
  const monthOk = isYymm(shown);
  const summary = useSummary(shown, level, filters);
  const options = useSummaryOptions(shown, monthOk);
  const o = options.data;
  const mayExport = can(me.data, 'O-04', 'EXPORT');
  const pdf = usePdfDownload();
  const s = summary.data;
  const roles = me.data?.roles ?? [];
  const maker = roles.includes('PAYROLL_MAKER');
  const checker = roles.includes('PAYROLL_CHECKER');
  const [sectionOpen, setSectionOpen] = useState(false);
  const [row, setRow] = useState<{ entry: CatalogueEntry; edit: boolean } | null>(null);
  const scheduleWhy = t('o04w.why.schedule');

  /** Why the summary cannot run now, or null. */
  function runWhy(): string | null {
    if (!monthOk) return t('o04.why.month');
    if (chosen && chosen.status !== 'AVAILABLE') return generateWhy(chosen, t);
    if (latest.isPending) return t('o04w.why.latest');
    if (summary.isFetching) return t('o04.why.loading');
    return null;
  }

  function run() {
    setSectionOpen(true);
    void summary.refetch();
  }

  const runButton = (label: string, testId: string, kind?: 'pri' | 'sm') => {
    const why = runWhy();
    return (
      <button
        type="button"
        className={`btn${kind ? ` ${kind}` : ''}`}
        data-testid={testId}
        disabled={why !== null}
        title={why ?? undefined}
        data-why={why ?? undefined}
        onClick={run}
      >
        {label}
      </button>
    );
  };

  const lockLabel = (e: CatalogueEntry) => t(`o04w.needsLock.${e.lockRequirement}`);
  const formatLabel = (e: CatalogueEntry) =>
    e.layoutFormat ? valueLabel(t, 'o04w.format', e.layoutFormat) : '—';
  const dialogRows = (e: CatalogueEntry, edit: boolean): KvRow[] => [
    { k: t('o04w.col.report'), v: e.name, s: 'SYS' },
    { k: t('o04w.col.category'), v: valueLabel(t, 'm12w.family', e.family), s: 'SYS' },
    { k: t('o04w.col.frequency'), v: e.periodicity ?? '—', s: 'RULE' },
    { k: t('o04w.col.format'), v: formatLabel(e), s: 'ENT' },
    { k: t('o04w.col.needsLock'), v: lockLabel(e), s: 'RULE' },
    { k: t('o04w.col.status'), v: <EntryPill entry={e} />, s: 'SYS' },
    { k: t('o04w.dialog.reason'), v: e.blockedReason ?? '—', s: 'SYS' },
    ...(edit ? [{ k: t('o04w.dialog.change'), v: t('o04w.why.edit'), s: 'RULE' as const }] : []),
  ];

  // The month as drawn, "JUL 2026 (202607)".
  const periodText = monthOk
    ? `${periodLabel(`${shown.slice(0, 4)}-${shown.slice(4)}`, locale)} (${shown})`
    : '—';
  const office = (o?.offices ?? []).find((x) => x.id === filters.officeId);
  const finalMonth = s !== undefined && !s.provisional;
  const parameterRows: KvRow[] = [
    { k: t('o04w.kv.period'), v: periodText, s: 'ENT' },
    {
      k: t('o04w.kv.office'),
      v: `${office?.label ?? t('o04.sum.allOffices')} · ${t(`o04.level.${level}`)}`,
      s: 'ENT',
    },
    { k: t('o04w.kv.type'), v: filters.payrollType || t('o04.payrollType.all'), s: 'ENT' },
    {
      k: t('o04w.kv.range'),
      v:
        filters.fromEis?.trim() || filters.toEis?.trim()
          ? `${filters.fromEis?.trim() || '—'} - ${filters.toEis?.trim() || '—'}`
          : t('o04w.kv.range.v'),
      s: 'ENT',
    },
    { k: t('o04w.kv.grouping'), v: t('o04w.kv.grouping.v'), s: 'ENT' },
    {
      k: t('o04w.kv.output'),
      v: (
        <>
          {t('o04w.kv.output.v')}
          <div className="sub2">{t('o04w.kv.output.why')}</div>
        </>
      ),
      s: 'ENT',
    },
    { k: t('o04w.kv.source'), v: t('o04w.kv.source.v'), s: 'SYS' },
    {
      k: t('o04w.kv.check'),
      v: (
        <span className={`pill ${finalMonth ? 'p-ok' : 'p-warn'}`}>
          {t(finalMonth ? 'o04w.kv.check.final' : 'o04w.kv.check.v')}
        </span>
      ),
      s: 'CALC',
    },
  ];

  const pill = (tone: string, text: string): ReactNode => <span className={`pill ${tone}`}>{text}</span>;
  const matchPill = (same: boolean) =>
    s === undefined
      ? pill('p-gray', t('o04w.ct.notRun'))
      : same
        ? pill('p-ok', t('o04w.ct.match'))
        : pill('p-bad', t('o04w.ct.mismatch'));
  const na = pill('p-warn', t('o04w.ct.na'));

  return (
    <>
      <div className="toolbar">
        <span className="sp" />
        <Explained label={t('o04w.act.schedule')} why={scheduleWhy} />
        {maker ? (
          runButton(t('o04w.act.generate'), 'o04-generate-top', 'pri')
        ) : (
          <Locked label={t('o04w.act.generate')} kind="pri" why={t('o04w.why.maker')} />
        )}
      </div>
      <details data-testid="o04-catalogue-filters">
        <summary className="sub2">{t('wf.searchDisclosure')}</summary>
        <div className="toolbar">
          <label>
            {t('o04.family')}{' '}
            <select value={family} onChange={(e) => setFamily(e.target.value)}>
              <option value="">{t('o04.allFamilies')}</option>
              {familiesOf(entries).map((f) => (
                <option key={f} value={f}>
                  {valueLabel(t, 'm12w.family', f)}
                </option>
              ))}
            </select>
          </label>
          <label>
            {t('o04.search')} <input maxLength={60} value={text} onChange={(e) => setText(e.target.value)} />
          </label>
        </div>
      </details>
      <Tbl
        title={t('o04.catalogue')}
        acts={
          <>
            {runButton(t('action.o04.generate'), 'o04-generate-table', 'sm')}
            <Explained label={t('o04w.act.scheduleSm')} kind="sm" why={scheduleWhy} />
          </>
        }
        cols={[
          { t: t('o04w.col.report'), s: 'SYS' },
          { t: t('o04w.col.category'), s: 'SYS' },
          { t: t('o04w.col.frequency'), s: 'RULE' },
          { t: t('o04w.col.format'), s: 'ENT' },
          { t: t('o04w.col.needsLock'), s: 'RULE' },
          { t: t('o04w.col.status'), s: 'SYS' },
          { t: t('o04w.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={reportKeys.catalogue(family, text)}
          fetchPage={cataloguePage(family, text)}
          empty={t('o04.none')}
        >
          {(rows) =>
            rows.map((r) => (
              <tr key={r.key} data-reference={r.key}>
                <td>
                  <b>{r.name}</b>
                  <div className="sub2">
                    {r.section} · {r.payrollTypes ?? '—'}
                  </div>
                </td>
                <td>{valueLabel(t, 'm12w.family', r.family)}</td>
                <td>{r.periodicity ?? '—'}</td>
                <td>{formatLabel(r)}</td>
                <td>{lockLabel(r)}</td>
                <td>
                  <EntryPill entry={r} />
                  {r.status === 'BLOCKED' && r.blockedReason && <div className="sub2">{r.blockedReason}</div>}
                </td>
                <td>
                  <RowActs
                    onView={() => setRow({ entry: r, edit: false })}
                    onEdit={maker ? () => setRow({ entry: r, edit: true }) : undefined}
                    editWhy={t('o04w.why.editRole')}
                    onApprove={checker ? () => toast(t('o04w.why.approve')) : undefined}
                    viewName={t('o04w.viewName', [r.name])}
                    editName={t('o04w.editName', [r.name])}
                  />
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <KvCard title={t('o04w.params')} rows={parameterRows} />
        <Panel
          title={t('o04w.ct')}
          act={runButton(t('o04w.act.recheck'), 'o04-recheck', 'sm')}
          items={[
            {
              icon: '₹',
              tone: 'ok',
              title: t('o04w.ct.gross'),
              src: 'CALC',
              text: s ? t('o04w.ct.amounts', [amount(s.earnings), amount(s.postedGross)]) : t('o04w.ct.scr'),
              right: matchPill(s ? s.controlTotalsMatch || s.earnings === s.postedGross : false),
            },
            {
              icon: '➖',
              tone: 'ok',
              title: t('o04w.ct.deductions'),
              src: 'CALC',
              text: s
                ? t('o04w.ct.amounts', [amount(s.deductions), amount(s.postedDeductions)])
                : t('o04w.ct.scr'),
              right: matchPill(s ? s.controlTotalsMatch || s.deductions === s.postedDeductions : false),
            },
            {
              icon: '🎯',
              tone: 'warn',
              title: t('o04w.ct.incentive'),
              src: 'CALC',
              text: t('o04w.ct.incentive.why'),
              right: na,
            },
            {
              icon: '🏦',
              tone: 'warn',
              title: t('o04w.ct.bank'),
              src: 'CALC',
              text: t('o04w.ct.bank.why'),
              right: na,
            },
            {
              icon: '🏦',
              tone: 'warn',
              title: t('o04w.ct.cpf'),
              src: 'CALC',
              text: t('o04w.ct.cpf.why'),
              right: na,
            },
          ]}
        />
      </div>
      <details className="fl-mt" data-testid="o04-summary-section" open={sectionOpen}>
        <summary
          className="sub2"
          onClick={(e) => {
            e.preventDefault();
            setSectionOpen((open) => !open);
          }}
        >
          {t('o04w.section.summary')}
        </summary>
        <Card title={t('o04.summary')}>
          <div className="toolbar">
            <label>
              {t('o04.report')}{' '}
              <select value={report} onChange={(e) => setReport(e.target.value)}>
                {(offered.length > 0
                  ? offered
                  : [{ key: 'COMPONENT_WISE_SUMMARY', name: t('o04.summary') }]
                ).map((e) => (
                  <option key={e.key} value={e.key}>
                    {e.name}
                  </option>
                ))}
              </select>
            </label>
            {runButton(t('action.o04.generate'), 'o04-generate')}
          </div>
          <div className="toolbar">
            <label>
              {t('o04.month')} <MonthField value={shown} onChange={setYymm} />
            </label>
            <label>
              {t('o04.level')}{' '}
              <select value={level} onChange={(e) => setLevel(e.target.value as Level)}>
                {LEVELS.map((l) => (
                  <option key={l} value={l}>
                    {t(`o04.level.${l}`)}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('o04.officeFilter')}{' '}
              <select
                value={filters.officeId ?? ''}
                onChange={(e) =>
                  setFilters({ ...filters, officeId: e.target.value === '' ? null : Number(e.target.value) })
                }
              >
                <option value="">{t('o04.office.all')}</option>
                {(o?.offices ?? []).map((x) => (
                  <option key={x.id} value={x.id}>
                    {x.label}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('o04.payrollType')}{' '}
              <select
                value={filters.payrollType}
                onChange={(e) => setFilters({ ...filters, payrollType: e.target.value })}
              >
                <option value="">{t('o04.payrollType.all')}</option>
                {(o?.payrollTypes ?? []).map((x) => (
                  <option key={x} value={x}>
                    {x}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('o04.category')}{' '}
              <select
                value={filters.category}
                onChange={(e) => setFilters({ ...filters, category: e.target.value })}
              >
                <option value="">{t('o04.category.all')}</option>
                {(o?.categories ?? []).map((x) => (
                  <option key={x} value={x}>
                    {x}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('o04.memberStatus')}{' '}
              <select
                value={filters.memberStatus}
                onChange={(e) => setFilters({ ...filters, memberStatus: e.target.value as MemberStatus })}
              >
                {MEMBER_STATUSES.map((m) => (
                  <option key={m} value={m}>
                    {t(`o04.sum.members.${m}`)}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('o04.fromEis')}{' '}
              <input
                maxLength={20}
                value={filters.fromEis ?? ''}
                onChange={(e) => setFilters({ ...filters, fromEis: e.target.value })}
              />
            </label>
            <label>
              {t('o04.toEis')}{' '}
              <input
                maxLength={20}
                value={filters.toEis ?? ''}
                onChange={(e) => setFilters({ ...filters, toEis: e.target.value })}
              />
            </label>
            <label>
              {t('o04.sort')}{' '}
              <select
                value={filters.sort ?? 'PAY_CODE'}
                onChange={(e) =>
                  setFilters({ ...filters, sort: e.target.value === 'AMOUNT' ? 'AMOUNT' : 'PAY_CODE' })
                }
              >
                <option value="PAY_CODE">{t('o04.sort.PAY_CODE')}</option>
                <option value="AMOUNT">{t('o04.sort.AMOUNT')}</option>
              </select>
            </label>
            <label>
              {t('o04.language')}{' '}
              <select value={lang} onChange={(e) => setLang(e.target.value === 'hi' ? 'hi' : 'en')}>
                <option value="en">{t('o04.language.en')}</option>
                <option value="hi">{t('o04.language.hi')}</option>
              </select>
            </label>
            {mayExport &&
              (s?.controlTotalsMatch ? (
                <ExportButton href={summaryExportUrl(shown, level, filters, lang)} what={t('o04.summary')} />
              ) : (
                <button type="button" className="btn" disabled title={t(exportBlockedWhy(monthOk, summary))}>
                  {t('action.export')}
                </button>
              ))}
            {/* OD-144: the same summary as PDF (English only: Hindi needs an embedded font, PENDING LIB-1-HI). */}
            {mayExport && s?.controlTotalsMatch && lang === 'en' ? (
              <Act
                label={t('o04w.act.pdf')}
                onClick={() =>
                  void pdf.download(
                    `${summaryExportUrl(shown, level, filters, lang).slice(API_BASE.length)}&format=PDF`,
                    'summary.pdf',
                  )
                }
              />
            ) : (
              <Locked
                label={t('o04w.act.pdf')}
                why={
                  !mayExport
                    ? t('o04w.why.pdfRight')
                    : !s?.controlTotalsMatch
                      ? t(exportBlockedWhy(monthOk, summary))
                      : t('o04w.why.pdfHindi')
                }
              />
            )}
          </div>
          {!monthOk && <p className="fl-hint">{t('o04.why.month')}</p>}
          <Problem error={summary.error ?? pdf.error} />
          {s && (
            <>
              <p className={s.provisional ? 'warn' : 'sub2'} role="status">
                {t(s.provisional ? 'o04.provisional' : 'o04.final')}{' '}
                {t(s.controlTotalsMatch ? 'o04.reconciled' : 'o04.mismatch', [
                  amount(s.earnings),
                  amount(s.postedGross),
                ])}
              </p>
              {/* SRS-18-8 (l.3505): the deductions reconcile against the posted deductions too. */}
              <p className="sub2" data-testid="o04-deductions">
                {t('o04.deductionsReconciled', [amount(s.deductions), amount(s.postedDeductions)])}
              </p>
              {s.lines.length === 0 ? (
                <Empty text={t('o04.noResults')} />
              ) : (
                <table className="t" aria-label={t('o04.summary')}>
                  <thead>
                    <tr>
                      <th>
                        {t('o04.office')}
                        <Src s="SYS" />
                      </th>
                      <th>
                        {t('o04.payrollType')}
                        <Src s="SYS" />
                      </th>
                      <th>
                        {t('o04.component')}
                        <Src s="RULE" />
                      </th>
                      <th>
                        {t('o04.amount')}
                        <Src s="CALC" />
                      </th>
                      <th>
                        {t('o04.members')}
                        <Src s="CALC" />
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {s.lines.map((l) => (
                      <tr key={`${l.levelCode}-${l.payrollType}-${l.payCode}-${l.componentType}`}>
                        <td>
                          {l.levelCode} - {l.levelName}
                        </td>
                        <td>{l.payrollType}</td>
                        <td>
                          {l.payCode} - {l.component} ({t(`o04.type.${l.componentType}`)})
                        </td>
                        <td className="num">{amount(l.amount)}</td>
                        <td className="num">{l.members}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </>
          )}
        </Card>
      </details>
      <MisStandards />
      <div className="fl-mt">
        <MisAnnexure entries={entries} />
      </div>
      <MisBiPanel />
      {row && (
        <ViewDialog
          screen={t('screen.O-04')}
          rows={dialogRows(row.entry, row.edit)}
          actions={
            row.edit ? <Act label={t('o04w.act.openM12')} href={withBase('/payroll/?s=M-12')} /> : undefined
          }
          onClose={() => setRow(null)}
        />
      )}
    </>
  );
}

/**
 * O-04 l.3500: why a catalogue entry is not generated here - Blocked with its reason, or produced on
 * its own screen (SRS-18-24).
 */
function generateWhy(
  e: { status: string; blockedReason?: string | null; producingScreen?: string | null },
  t: (key: string, args?: unknown[]) => string,
): string {
  if (e.status === 'BLOCKED') return e.blockedReason ?? t('o04.status.BLOCKED');
  return t(`o04.status.${e.status}`, [e.producingScreen ?? '']);
}
