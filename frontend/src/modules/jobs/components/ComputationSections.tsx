'use client';

import { useRouter } from 'next/navigation';
import { useCallback, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions, useReference } from '@/shared/reference';
import { can, periodLabel, useMe } from '@/shared/session';
import { amount, date, ExportButton, Problem, ServerPagination, Th, useToast } from '@/shared/ui';
import { InvoiceCard, type InvoiceRow } from '@/shared/ui/invoiceCard';
import {
  Act,
  type Col,
  Explained,
  KvCard,
  type KvRow,
  Locked,
  RowActs,
  Tbl,
  Tiles,
  Toolbar,
  ViewDialog,
  WfTile,
} from '@/shared/ui/wireframe';
import type { PayPatternFilter, PliPayment, ResultRow, ResultStatusFilter } from '../api/cycleApi';
import {
  crore,
  pliCancelled,
  pliSent,
  resultEditWhy,
  resultTone,
  rerunnable,
  searchText,
  traceRows,
} from '../business/cycleRules';
import {
  cycleKeys,
  cyclePages,
  resultsExportUrl,
  useResultsPeriod,
  useResultsSummary,
  useTrace,
} from '../orchestration/cycleQueries';

/**
 * The sections of C-02 Employee / Staff Payroll Computation as Wireframes v6.1 draws them (OD-121; SRS
 * l.3686-3692, FRS §7.4, §7.4.1): 🔍 Trace a calculation and ▶ Re-run Computation (Payroll Maker, a C-13 job
 * as before); the Pattern and Status filters (M-16 lists, OD-123), list search and Export (SRS-G-13); the
 * four tiles; the Employee-wise Result with its totals line, Component view and 👁 View; the calculation
 * trace of the selected member; the PLI payments with their invoice status (BR-AUDIT-009 / 010). Every figure
 * is what the run stored. The results are of the session's period, or of the unit's latest run while the
 * period has none. A row cannot be edited (BR-AUDIT-005): ✎ Edit is off with the reason of the row's state.
 */
export function ComputationSections({
  period,
  cycleStatus,
}: {
  period: string;
  /** The cycle of the session's period, which the re-run acts on. */
  cycleStatus: string | null;
}) {
  const { t, locale } = useI18n();
  const toast = useToast();
  const router = useRouter();
  const me = useMe();
  const reference = useReference();
  const [payPattern, setPayPattern] = useState<PayPatternFilter>('');
  const [status, setStatus] = useState<ResultStatusFilter>('');
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState<ResultRow | null>(null);
  const [viewing, setViewing] = useState<ResultRow | null>(null);
  const [fullTrace, setFullTrace] = useState(false);
  const [showLines, setShowLines] = useState(false);
  const filter = { payPattern, status, search };
  const shown = useResultsPeriod(period);
  // Before the answer, and for a unit without a run, the session's own period.
  const resultsPeriod = shown.data?.period ?? period;
  const label = periodLabel(resultsPeriod, locale);
  const summary = useResultsSummary(resultsPeriod, filter);
  const trace = useTrace(resultsPeriod, selected?.resultId ?? null);
  const s = summary.data;
  const onRows = useCallback((page: ResultRow[]) => {
    setSelected((prev) => page.find((r) => r.resultId === prev?.resultId) ?? page[0] ?? null);
  }, []);
  const maker = can(me.data, 'C-13', 'CREATE');
  const rerunWhy = rerunnable(cycleStatus) ? null : t('c02.why.rerunState');
  const kv = trace.data ? traceKv(t, trace.data) : [];
  const statusOf = (r: ResultRow) => reference.label('C02_RESULT_STATUS', r.rowStatus);
  const maySeeExport = can(me.data, 'C-02', 'EXPORT');
  const lockedExport = <Explained label={t('wf.export')} why={t('c02.why.export')} />;
  const openTrace = () => {
    if (selected) setFullTrace(true);
    else toast(t('c02.trace.pickRow'));
  };

  const cols: Col[] = [
    { t: t('c02.col.eis'), s: 'HRMS' },
    { t: t('c02.col.name'), s: 'HRMS' },
    { t: t('c02.col.pattern'), s: 'ENT' },
    { t: t('c02.col.basic'), s: 'ENT', num: true },
    { t: t('c02.col.da'), s: 'CALC', num: true },
    { t: t('c02.col.hra'), s: 'CALC', num: true },
    { t: t('c02.col.other'), s: 'CALC', num: true },
    { t: t('c02.col.gross'), s: 'CALC', num: true },
    { t: t('c02.col.deduct'), s: 'CALC', num: true },
    { t: t('c02.col.net'), s: 'CALC', num: true },
    { t: t('c02.col.status'), s: 'SYS' },
    { t: t('c02.col.actions'), s: 'SYS' },
  ];

  return (
    <>
      <div className="toolbar">
        <span className="sp" />
        <Act label={t('c02.act.trace')} onClick={openTrace} />
        {!maker ? (
          <Locked label={t('c02.act.rerun')} why={t('c02.why.makerOnly')} kind="pri" />
        ) : rerunWhy ? (
          <Explained label={t('c02.act.rerun')} why={rerunWhy} kind="pri" />
        ) : (
          <Act label={t('c02.act.rerun')} kind="pri" onClick={() => router.push('/payroll?s=C-13')} />
        )}
      </div>
      <Toolbar
        search={search}
        onSearch={(v) => setSearch(searchText(v))}
        exportControl={
          maySeeExport ? (
            <ExportButton
              href={resultsExportUrl(resultsPeriod, filter)}
              what={t('screen.C-02')}
              label={t('wf.export')}
            />
          ) : (
            lockedExport
          )
        }
      >
        <select
          aria-label={t('c02.payPattern')}
          value={payPattern}
          onChange={(e) => setPayPattern(e.target.value as PayPatternFilter)}
        >
          <option value="">{t('c02.allPatterns')}</option>
          <ReferenceOptions list="C02_PAY_PATTERN_FILTER" />
        </select>
        <select
          aria-label={t('c02.col.status')}
          value={status}
          onChange={(e) => setStatus(e.target.value as ResultStatusFilter)}
        >
          <option value="">{t('c02.allStatuses')}</option>
          <ReferenceOptions list="C02_RESULT_FILTER" />
        </select>
      </Toolbar>
      <Problem error={shown.error ?? summary.error ?? trace.error} />
      {shown.data && shown.data.period !== period && (
        <p className="sub2" data-testid="c02-other-period">
          {t('c02.shownPeriod', [periodLabel(period, locale), label])}
        </p>
      )}
      <Tiles>
        <WfTile
          icon="👥"
          label={t('c02.tile.ida')}
          src="CALC"
          big={(s?.ida.members ?? 0).toLocaleString('en-IN')}
          sub={t('c02.tile.computed', [s?.ida.exceptions ?? 0])}
        />
        <WfTile
          icon="👤"
          label={t('c02.tile.cda')}
          src="CALC"
          tone="pur"
          big={(s?.cda.members ?? 0).toLocaleString('en-IN')}
          sub={t('c02.tile.computed', [s?.cda.exceptions ?? 0])}
        />
        <WfTile
          icon="₹"
          label={t('c02.tile.money')}
          src="CALC"
          tone="ok"
          big={t('c02.tile.moneyBig', [crore(s?.gross ?? 0), crore(s?.net ?? 0)])}
          sub={t('c02.tile.moneySub', [crore(s?.deductions ?? 0)])}
        />
        <WfTile
          icon="🚫"
          label={t('c02.tile.held')}
          src="ENT"
          tone="bad"
          bad
          big={(s?.held ?? 0).toLocaleString('en-IN')}
          sub={t('c02.tile.heldSub')}
        />
      </Tiles>
      <Tbl
        title={t('c02.results', [label])}
        acts={
          <>
            <Act label={t('c02.act.components')} kind="sm" onClick={() => setShowLines(!showLines)} />
            <Explained label={t('c02.act.payslip')} kind="sm" why={t('c02.why.payslip')} />
          </>
        }
        cols={cols}
        foot={
          s ? (
            <tr>
              <td />
              <td />
              <td>
                <b>{t('c02.totals', [s.shown.members.toLocaleString('en-IN')])}</b>
              </td>
              <td />
              <td />
              <td />
              <td />
              <td className="num">{t('c02.crore', [crore(s.shown.gross)])}</td>
              <td className="num">{t('c02.crore', [crore(s.shown.deductions)])}</td>
              <td className="num">{t('c02.crore', [crore(s.shown.net)])}</td>
              <td />
              <td />
            </tr>
          ) : undefined
        }
        after={
          showLines && selected ? (
            <div className="wrap">
              <ServerPagination
                queryKey={cycleKeys.lines(resultsPeriod, selected.resultId)}
                fetchPage={cyclePages.lines(resultsPeriod, selected.resultId)}
              >
                {(lines) => (
                  <table className="t" aria-label={t('c02.act.components')}>
                    <thead>
                      <tr>
                        <Th label={t('mst.payCodeCol')} s="RULE" />
                        <Th label={t('mst.component')} s="RULE" />
                        <Th label={t('cyc.basis')} s="CALC" num />
                        <Th label={t('cyc.rate')} s="RULE" num />
                        <Th label={t('cyc.amount')} s="CALC" num />
                      </tr>
                    </thead>
                    <tbody>
                      {lines.map((l) => (
                        <tr key={l.payCode}>
                          <td>{l.payCode}</td>
                          <td>{l.name}</td>
                          <td className="num">{amount(l.basis)}</td>
                          <td className="num">{amount(l.rate)}</td>
                          <td className="num">
                            <b>{amount(l.amount)}</b>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </ServerPagination>
            </div>
          ) : undefined
        }
      >
        <ServerPagination
          queryKey={cycleKeys.results(resultsPeriod, payPattern, status, search)}
          fetchPage={cyclePages.results(resultsPeriod, filter)}
          empty={<p className="fl-hint">{t('c02.noResults')}</p>}
          onRows={onRows}
        >
          {(rows) =>
            rows.map((r) => (
              <tr key={r.resultId} className={selected?.resultId === r.resultId ? 'sel' : undefined}>
                <td>
                  <b>{r.eis}</b>
                </td>
                <td>{r.name}</td>
                <td>{[r.payPattern, r.scale].filter(Boolean).join(' · ') || '—'}</td>
                <td className="num">{amount(r.basic)}</td>
                <td className="num">{amount(r.da)}</td>
                <td className="num">{amount(r.hra)}</td>
                <td className="num">{amount(r.other)}</td>
                <td className="num">{amount(r.gross)}</td>
                <td className="num">{amount(r.deductions)}</td>
                <td className={`num${r.net < 0 ? ' fl-bad' : ''}`}>
                  <b>{amount(r.net)}</b>
                </td>
                <td>
                  <span className={`pill ${resultTone(r.rowStatus)}`}>{statusOf(r)}</span>
                </td>
                <td>
                  <RowActs
                    onView={() => {
                      setSelected(r);
                      setViewing(r);
                    }}
                    viewName={t('c02.viewName', [r.eis])}
                    editName={t('c02.editName', [r.eis])}
                    editWhy={t(resultEditWhy(r))}
                  />
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <KvCard
        title={selected ? t('c02.trace.title', [selected.eis, label]) : t('c02.trace.none')}
        act={<Act label={t('c02.trace.full')} kind="sm" onClick={openTrace} />}
        rows={kv}
      />
      <PliCard period={resultsPeriod} />
      {viewing && (
        <ViewDialog
          screen={t('screen.C-02')}
          onClose={() => setViewing(null)}
          rows={[
            { k: t('c02.col.eis'), v: viewing.eis, s: 'HRMS' },
            { k: t('c02.col.name'), v: viewing.name, s: 'HRMS' },
            {
              k: t('c02.col.pattern'),
              v: `${viewing.payPattern ?? '—'} · ${viewing.scale ?? '—'}`,
              s: 'ENT',
            },
            { k: t('c02.cityClass'), v: viewing.cityClass ?? '—', s: 'CALC' },
            { k: t('c02.col.basic'), v: amount(viewing.basic), s: 'ENT' },
            { k: t('c02.col.da'), v: amount(viewing.da), s: 'CALC' },
            { k: t('c02.col.hra'), v: amount(viewing.hra), s: 'CALC' },
            { k: t('c02.col.other'), v: amount(viewing.other), s: 'CALC' },
            { k: t('c02.col.gross'), v: amount(viewing.gross), s: 'CALC' },
            { k: t('c02.col.deduct'), v: amount(viewing.deductions), s: 'CALC' },
            { k: t('c02.col.net'), v: amount(viewing.net), s: 'CALC' },
            { k: t('c02.col.status'), v: statusOf(viewing), s: 'SYS' },
          ]}
        />
      )}
      {fullTrace && trace.data && (
        <ViewDialog
          screen={t('c02.trace.title', [trace.data.eis, label])}
          onClose={() => setFullTrace(false)}
          rows={trace.data.lines.map((l) => ({
            k: `${l.payCode} ${l.name}`,
            v: fullLine(t, l),
            s: l.rule ? 'RULE' : 'CALC',
          }))}
        />
      )}
    </>
  );
}

type T = (key: string, args?: readonly unknown[]) => string;

function fullLine(
  t: T,
  l: {
    amount: number;
    rate: number | null;
    basis: number | null;
    rule: string | null;
    ruleVersion: number | null;
  },
): string {
  const value =
    l.rate !== null && l.basis !== null
      ? t('c02.trace.formula', [amount(l.amount), amount(l.basis), amount(l.rate)])
      : t('c02.rupees', [amount(l.amount)]);
  return l.rule ? `${value} · ${t('c02.trace.rule', [l.rule, l.ruleVersion ?? ''])}` : value;
}

/**
 * The Accommodation row says what the result did (SRS-1-3, SRS-11-17): "HRA suppressed" only where
 * the result has no HRA line. A locked result that paid HRA under a record suppressing it stays as
 * it is and is shown as that conflict. Quarters have no lease amount.
 */
function accommodationLine(
  t: T,
  lease: { type: string; amount: number | null; hraSuppressed: boolean },
  hraPaid: boolean,
): string {
  const type = t(`c02.accom.${lease.type}`);
  const held = lease.amount === null ? type : t('c02.trace.accomPaid', [type, amount(lease.amount)]);
  if (!lease.hraSuppressed) {
    return held;
  }
  return t(hraPaid ? 'c02.trace.accomConflict' : 'c02.trace.accomSuppressed', [held]);
}

/** The drawn rows of the Calculation trace card, each with its source badge (FRS §7.4). */
function traceKv(t: T, trace: NonNullable<ReturnType<typeof useTrace>['data']>): KvRow[] {
  const r = traceRows(trace);
  const money = (n: number | null) => (n === null ? '—' : t('c02.rupees', [amount(n)]));
  const rated = (rate: number | null, rule: string | null, version: number | null) =>
    rate === null
      ? '—'
      : rule
        ? t('c02.trace.rateRule', [amount(rate), rule, version ?? ''])
        : t('c02.trace.rate', [amount(rate)]);
  const lease = trace.accommodation;
  return [
    {
      k: t('c02.trace.basic'),
      v: r.basic ? t('c02.trace.basicFrom', [amount(r.basic.amount)]) : '—',
      s: 'HRMS',
    },
    {
      k: t('c02.trace.stagnation'),
      v: t(trace.stagnationCase ? 'c02.trace.stagYes' : 'c02.trace.stagNo'),
      s: 'CALC',
    },
    {
      k: t('c02.trace.daRate'),
      v: rated(r.da?.rate ?? null, r.da?.rule ?? null, r.da?.ruleVersion ?? null),
      s: 'RULE',
    },
    { k: t('c02.trace.da'), v: <b>{r.da ? fullLine(t, { ...r.da, rule: null }) : '—'}</b>, s: 'CALC' },
    {
      k: t('c02.trace.city'),
      v: `${trace.cityClass ?? '—'} / ${rated(r.hra?.rate ?? null, r.hra?.rule ?? null, r.hra?.ruleVersion ?? null)}`,
      s: 'RULE',
    },
    {
      k: t('c02.trace.accom'),
      v: lease ? accommodationLine(t, lease, Boolean(r.hra)) : t('c02.trace.accomNone'),
      s: 'HRMS',
    },
    {
      k: t('c02.trace.hra'),
      v: (
        <b>
          {r.hra
            ? t('c02.rupees', [amount(r.hra.amount)])
            : lease?.hraSuppressed
              ? t('c02.trace.hraSuppressed')
              : '—'}
        </b>
      ),
      s: 'CALC',
    },
    {
      k: t('c02.trace.lease'),
      v:
        lease?.type === 'SELF_LEASE' && lease.amount !== null
          ? t('c02.trace.leaseRecord', [amount(lease.amount)])
          : '—',
      s: 'CALC',
    },
    {
      k: t('c02.trace.transport'),
      v: r.earnings.map((l) => `${l.name} ${money(l.amount)}`).join(' · ') || '—',
      s: 'CALC',
    },
    { k: t('c02.trace.gross'), v: <b>{money(trace.gross)}</b>, s: 'CALC' },
    { k: t('c02.trace.cpf'), v: money(r.cpf?.amount ?? null), s: 'CALC' },
    {
      k: t('c02.trace.vpf'),
      v: r.vpf ? t('c02.trace.vpfDeclared', [amount(r.vpf.amount)]) : '—',
      s: 'ESS',
    },
    {
      k: t('c02.trace.tdsPt'),
      v: `${money(r.tds?.amount ?? null)} / ${money(r.pt?.amount ?? null)}`,
      s: 'CALC',
    },
    {
      k: t('c02.trace.loan'),
      v: r.recoveries.map((l) => `${money(l.amount)} ${l.name}`).join(' · ') || '—',
      s: 'CALC',
    },
    { k: t('c02.trace.net'), v: <b>{t('c02.trace.netRounded', [amount(trace.net)])}</b>, s: 'CALC' },
  ];
}

/**
 * "PLI payments — invoice status" as drawn (invCard, BR-AUDIT-009 / 010, SRS-G-37). The rows are read from
 * pli_transaction; ⊘ Cancel is off with its reason: the PLI cancellation arrives with the HRMS PLI interface
 * (PENDING 10-pli), as does the OTP-gated adjustment (SRS-G-21), never a stand-in here.
 */
function PliCard({ period }: { period: string }) {
  const { t } = useI18n();
  const me = useMe();
  const maker = can(me.data, 'C-02', 'CREATE');
  const row = (p: PliPayment): InvoiceRow => ({
    key: String(p.id),
    entry: p.entry,
    member: `${p.eis} ${p.member}`,
    paymentType: (
      <>
        {t(`c02.pli.${p.type}`, [p.finYear])}
        {p.type === 'ADJUSTMENT' && (
          <>
            {' '}
            <span className="pill p-pur" title={t('c02.pli.otpTitle')}>
              {t('c02.pli.otp')}
            </span>
          </>
        )}
      </>
    ),
    amount: amount(p.amount),
    invoiceNo: p.invoiceNo === null ? null : String(p.invoiceNo),
    invoiceStatus: p.invoiceStatus,
    sentToAppsDate: p.sentToAppsAt,
    appsCancelled: p.appsCancelStatus === 'CONFIRMED',
    cancelled: pliCancelled(p),
    cancelWhy: !maker
      ? t('wf.inv.why.maker')
      : pliSent(p) && p.appsCancelStatus !== 'CONFIRMED'
        ? t('c02.pli.sentWhy', [date(p.sentToAppsAt)])
        : t('c02.pli.cancelLater'),
    onCancel: () => undefined,
  });
  return (
    <ServerPagination
      queryKey={cycleKeys.pli(period)}
      fetchPage={cyclePages.pli(period)}
      empty={<InvoiceCard title={t('c02.pli')} rows={[]} />}
    >
      {(rows) => <InvoiceCard title={t('c02.pli')} rows={rows.map(row)} />}
    </ServerPagination>
  );
}
