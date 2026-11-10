'use client';

import { useRouter } from 'next/navigation';
import { useCallback, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { useReference } from '@/shared/reference';
import { periodLabel, useMe } from '@/shared/session';
import { amount, Problem, ServerPagination, type Source } from '@/shared/ui';
import {
  Act,
  type Col,
  Explained,
  KvCard,
  type KvRow,
  Locked,
  Panel,
  type PanelItem,
  RowActs,
  Tbl,
  Tiles,
  ViewDialog,
  WfTile,
} from '@/shared/ui/wireframe';
import type { ComputationSummary, EngineRate, GangResult } from '../api/computationApi';
import type { GangRole, PayrollType } from '../api/labourApi';
import { computationPages, useComputationSummary } from '../orchestration/computationQueries';

const NUM = new Intl.NumberFormat('en-IN');
const DASH = '—';

/** The tone of a Status pill as the pack draws it. */
export function gangTone(status: string): string {
  if (status === 'NOT_COMPUTED') return 'p-gray';
  return status === 'COMPUTED' ? 'p-ok' : 'p-bad';
}

/** A rate of a role, or the words of a value the M-05 tables do not hold. */
function rateOf(rates: EngineRate[], role: GangRole, pick: (r: EngineRate) => number | null): number | null {
  const found = rates.find((r) => r.role === role);
  return found ? pick(found) : null;
}

/** The row columns of each engine as Wireframes v6.1 draws them. */
function columns(t: (k: string) => string, engine: PayrollType): Col[] {
  const head: Col[] = [
    { t: t('lbc.col.gang'), s: 'HRMS' },
    { t: t('lbc.col.depot'), s: 'HRMS' },
    { t: t('lbc.col.labour'), s: 'HRMS', num: true },
  ];
  const tail: Col[] = [
    { t: t('lbc.col.status'), s: 'SYS' },
    { t: t('lbc.col.actions'), s: 'SYS' },
  ];
  const cols: Record<PayrollType, [string, Source, boolean?][]> = {
    DEPT_LABOUR: [
      ['lbc.col.present', 'CALC', true],
      ['c03.col.nph', 'CALC', true],
      ['c03.col.wage', 'CALC', true],
      ['c03.col.nphAmount', 'CALC', true],
      ['c03.col.cpfNph', 'RULE'],
      ['c03.col.incentive', 'CALC', true],
    ],
    DPS_LABOUR: [
      ['lbc.col.present', 'CALC', true],
      ['c04.col.mgw', 'RULE', true],
      ['c04.col.goi', 'RULE', true],
      ['c04.col.floor', 'CALC', true],
      ['c04.col.actual', 'CALC', true],
      ['c04.col.paid', 'CALC'],
    ],
    NWNP_LABOUR: [
      ['c05.col.leaders', 'ENT'],
      ['c05.col.handling', 'ENT', true],
      ['c05.col.ancillary', 'ENT', true],
      ['c05.col.output', 'INTF', true],
      ['c05.col.piece', 'RULE', true],
      ['c05.col.noWork', 'ENT', true],
    ],
  };
  return [...head, ...cols[engine].map(([k, s, num]) => ({ t: t(k), s, num })), ...tail];
}

/** The cells between Labour and Status: what a run has not stored stays a dash, never a guess. */
function middle(
  t: (k: string) => string,
  engine: PayrollType,
  g: GangResult,
  rates: EngineRate[],
): (string | number)[] {
  const days = g.presentDays === null ? DASH : NUM.format(g.presentDays);
  if (engine === 'DEPT_LABOUR') {
    const cpf = g.status === 'NOT_COMPUTED' ? t('c03.cpfNotDeducted') : DASH;
    return [days, DASH, DASH, DASH, cpf, DASH];
  }
  if (engine === 'DPS_LABOUR') {
    const set = rates.length > 0;
    return [
      days,
      set && rates.some((r) => r.interimRelief !== null) ? t('c04.configured') : t('lbc.notConfigured'),
      set && rates.some((r) => r.goiRate !== null) ? t('c04.configured') : t('lbc.notConfigured'),
      DASH,
      DASH,
      DASH,
    ];
  }
  return [
    `${g.sardar} / ${g.mandal}`,
    g.handling,
    g.ancillary,
    g.bags === null ? DASH : NUM.format(g.bags),
    DASH,
    g.noWorkHours === null ? DASH : NUM.format(g.noWorkHours),
  ];
}

/** The calculation trace of a gang as drawn; the rule lines are the FRS text of the engine. */
function trace(
  t: (k: string, a?: unknown[]) => string,
  engine: PayrollType,
  s: ComputationSummary | undefined,
): KvRow[] {
  const rates = s?.rates ?? [];
  const value = (v: number | null) => (v === null ? t('lbc.notConfigured') : amount(v));
  const rule = (key: string, s_: Source = 'RULE'): KvRow => ({ k: t(`${key}.k`), v: t(`${key}.v`), s: s_ });
  if (engine === 'DEPT_LABOUR') {
    return [
      rule('c03.tr.codes'),
      rule('c03.tr.present'),
      rule('c03.tr.medical'),
      {
        k: t('c03.tr.basic.k'),
        v: t('c03.tr.basic.v', [
          value(rateOf(rates, 'SARDAR', (r) => r.basicWage)),
          value(rateOf(rates, 'MANDAL', (r) => r.basicWage)),
          value(rateOf(rates, 'HANDLING', (r) => r.basicWage)),
        ]),
        s: 'RULE',
      },
      rule('c03.tr.nph', 'CALC'),
      rule('c03.tr.cpf'),
      rule('c03.tr.lease', 'ENT'),
      rule('c03.tr.ota'),
      rule('c03.tr.incentive', 'CALC'),
    ];
  }
  if (engine === 'DPS_LABOUR') {
    const ir = rates.find((r) => r.interimRelief !== null)?.interimRelief ?? null;
    const goi = rates.find((r) => r.goiRate !== null)?.goiRate ?? null;
    return [
      rule('c04.tr.codes'),
      rule('c04.tr.present'),
      rule('c04.tr.medical'),
      rule('c04.tr.floor'),
      { k: t('c04.tr.ir'), v: value(ir), s: 'RULE' },
      { k: t('c04.tr.goi'), v: value(goi), s: 'RULE' },
      rule('c04.tr.grain'),
      rule('c04.tr.ota'),
      rule('c04.tr.sunday'),
      rule('c04.tr.revision'),
    ];
  }
  return [
    rule('c05.tr.basis'),
    {
      k: t('c05.tr.mgwLeaders'),
      v: `${value(rateOf(rates, 'SARDAR', (r) => r.mgw))} / ${value(rateOf(rates, 'MANDAL', (r) => r.mgw))}`,
      s: 'RULE',
    },
    {
      k: t('c05.tr.mgwLabour'),
      v: `${value(rateOf(rates, 'HANDLING', (r) => r.mgw))} / ${value(rateOf(rates, 'ANCILLARY', (r) => r.mgw))}`,
      s: 'RULE',
    },
    rule('c05.tr.piece'),
    rule('c05.tr.alternative'),
    rule('c05.tr.continue', 'CALC'),
    rule('c05.tr.noWork', 'CALC'),
    rule('c05.tr.eligibility'),
    rule('c05.tr.sequence', 'SYS'),
  ];
}

/** The Engine-specific rules panel as drawn. */
function rules(t: (k: string) => string, engine: PayrollType): PanelItem[] {
  const prefix = { DEPT_LABOUR: 'c03', DPS_LABOUR: 'c04', NWNP_LABOUR: 'c05' }[engine];
  const items: Record<PayrollType, [string, PanelItem['tone'], Source][]> = {
    DEPT_LABOUR: [
      ['🚫', 'bad', 'RULE'],
      ['📄', 'info', 'RULE'],
      ['🆔', 'info', 'RULE'],
      ['🏠', 'info', 'ENT'],
      ['⏱', 'gray', 'RULE'],
    ],
    DPS_LABOUR: [
      ['⚖️', 'warn', 'RULE'],
      ['⏱', 'info', 'RULE'],
      ['💳', 'info', 'RULE'],
      ['📅', 'info', 'RULE'],
      ['📄', 'info', 'RULE'],
      ['🏥', 'gray', 'INTF'],
    ],
    NWNP_LABOUR: [
      ['⚖️', 'bad', 'RULE'],
      ['🌙', 'info', 'RULE'],
      ['⏸', 'info', 'RULE'],
      ['📄', 'info', 'RULE'],
      ['🗂', 'gray', 'SYS'],
    ],
  };
  return items[engine].map(([icon, tone, src], i) => ({
    icon,
    tone,
    src,
    title: t(`${prefix}.rule.${i + 1}.t`),
    text: t(`${prefix}.rule.${i + 1}.m`),
  }));
}

/**
 * C-03 Departmental (FRS §8), C-04 DPS (FRS §9) and C-05 NWNP (FRS §10) Labour Computation as Wireframes v6.1 draws
 * them (OD-121): the four tiles, the Gang-wise Result, the calculation trace and the engine's rules.
 *
 * The wage runs are not built: their rules wait for FCI (PENDING LBR-20d-3, LBR-21d-1, NWNP-22d-1). So ▶ Compute,
 * Submit and Approve are drawn for the role the pack gives them and say why when pressed, nothing is sent, and a
 * figure only a run could produce is a dash. The rows show what is real: the gangs, their labour, the present days
 * and bags, and the first thing standing in the way of a run (Status, M-16 LABOUR_GANG_STATUS).
 */
export function LabourComputation({ engine }: { engine: PayrollType }) {
  const { t, locale } = useI18n();
  const router = useRouter();
  const me = useMe();
  const reference = useReference();
  const period = me.data?.period ?? '';
  const [selected, setSelected] = useState<GangResult | null>(null);
  const [viewing, setViewing] = useState<GangResult | null>(null);
  const summary = useComputationSummary(engine, period);
  const s = summary.data;
  const shown = s?.period ?? period;
  const label = periodLabel(shown, locale);
  const onRows = useCallback((page: GangResult[]) => {
    setSelected((prev) => page.find((g) => g.gangId === prev?.gangId) ?? page[0] ?? null);
  }, []);
  const roles = me.data?.roles ?? [];
  const notBuilt = t(`lbc.why.run.${engine}`);
  const statusOf = (g: GangResult) => reference.label('LABOUR_GANG_STATUS', g.status);
  const fixOf = (g: GangResult) => t(`lbc.fix.${g.status}`);
  const cols = columns(t, engine);
  const screen = t(`nav.${{ DEPT_LABOUR: 'c3', DPS_LABOUR: 'c4', NWNP_LABOUR: 'c5' }[engine]}`);
  const rateCount = s ? s.rates.filter((r) => r.mgw !== null).length : 0;
  return (
    <>
      <div className="toolbar">
        <span className="sp" />
        <Act label={t('lbc.act.slips')} onClick={() => router.push('/payroll?s=I-02')} />
        {roles.includes('PAYROLL_MAKER') ? (
          <Explained label={t('lbc.act.compute')} why={notBuilt} kind="pri" />
        ) : (
          <Locked label={t('lbc.act.compute')} why={t('lbc.why.compute')} kind="pri" />
        )}
        {roles.includes('LABOUR_DEALING_HAND') ? (
          <Explained label={t('lbc.act.submit')} why={notBuilt} kind="pri" />
        ) : (
          <Locked label={t('lbc.act.submit')} why={t('lbc.why.submit')} kind="pri" />
        )}
        {roles.includes('PAYROLL_CHECKER') ? (
          <Explained label={t('lbc.act.approve')} why={notBuilt} />
        ) : (
          <Locked label={t('lbc.act.approve')} why={t('lbc.why.approve')} />
        )}
      </div>
      <Problem error={summary.error} />
      {s && s.period !== period && (
        <p className="sub2" data-testid="lbc-other-period">
          {t('lbc.shownPeriod', [periodLabel(period, locale), label])}
        </p>
      )}
      <p className="sub2" data-testid="lbc-not-built">
        {notBuilt}
      </p>
      <Tiles>
        {engine === 'DEPT_LABOUR' && (
          <>
            <WfTile
              icon="🏭"
              label={t('lbc.tile.population')}
              src="HRMS"
              big={NUM.format(s?.population ?? 0)}
              sub={t('lbc.tile.depots', [s?.depots ?? 0])}
            />
            <WfTile
              icon="✅"
              label={t('lbc.tile.computed')}
              src="CALC"
              tone="ok"
              big={NUM.format(s?.computed ?? 0)}
              sub={t('lbc.tile.exceptions', [s?.notReady ?? 0])}
            />
            <WfTile
              icon="₹"
              label={t('c03.tile.wage')}
              src="CALC"
              big={s?.wage == null ? DASH : amount(s.wage)}
              sub={t('c03.tile.wageSub')}
            />
            <WfTile
              icon="🎯"
              label={t('c03.tile.incentive')}
              src="CALC"
              tone="info"
              big={t('c03.tile.incentiveBig')}
              sub={t('c03.tile.incentiveSub')}
            />
          </>
        )}
        {engine === 'DPS_LABOUR' && (
          <>
            <WfTile
              icon="🚚"
              label={t('lbc.tile.population')}
              src="HRMS"
              big={NUM.format(s?.population ?? 0)}
              sub={t('lbc.tile.depots', [s?.depots ?? 0])}
            />
            <WfTile
              icon="⏳"
              label={t('lbc.tile.computed')}
              src="CALC"
              tone="warn"
              big={NUM.format(s?.computed ?? 0)}
              sub={t('c04.tile.computedSub', [s?.notReady ?? 0])}
            />
            <WfTile
              icon="⚖️"
              label={t('c04.tile.floor')}
              src="CALC"
              tone="pur"
              big={DASH}
              sub={t('c04.tile.floorSub')}
            />
            <WfTile
              icon="₹"
              label={t('c04.tile.actual')}
              src="CALC"
              tone="ok"
              big={DASH}
              sub={t('c04.tile.actualSub')}
            />
          </>
        )}
        {engine === 'NWNP_LABOUR' && (
          <>
            <WfTile
              icon="📉"
              label={t('lbc.tile.population')}
              src="HRMS"
              big={NUM.format(s?.population ?? 0)}
              sub={t('lbc.tile.depots', [s?.depots ?? 0])}
            />
            <WfTile
              icon="⏳"
              label={t('lbc.tile.computed')}
              src="CALC"
              big={NUM.format(s?.computed ?? 0)}
              sub={t('c05.tile.computedSub')}
            />
            <WfTile
              icon="⚖️"
              label={t('c05.tile.asor')}
              src="RULE"
              tone="warn"
              big={rateCount > 0 ? rateCount : t('lbc.notConfigured')}
              sub={t('c05.tile.asorSub')}
            />
            <WfTile
              icon="❓"
              label={t('c05.tile.basis')}
              src="RULE"
              tone="bad"
              bad
              big={t('c05.tile.basisBig')}
              sub={t('c05.tile.basisSub')}
            />
          </>
        )}
      </Tiles>
      <Tbl
        title={t('lbc.result', [screen, label])}
        acts={
          <Act
            label={t('lbc.act.register')}
            kind="sm"
            onClick={() =>
              router.push(engine === 'DPS_LABOUR' ? '/payroll?s=O-03&engine=DPS' : '/payroll?s=O-03')
            }
          />
        }
        cols={cols}
      >
        {period !== '' && (
          <ServerPagination
            queryKey={['labour-computation', engine, period, 'gangs']}
            fetchPage={computationPages.gangs(engine, period)}
            empty={t('lbc.none')}
            onRows={onRows}
          >
            {(rows) =>
              rows.map((g) => (
                <tr
                  key={g.gangId}
                  aria-selected={selected?.gangId === g.gangId}
                  className={selected?.gangId === g.gangId ? 'fl-row-selected' : undefined}
                  onClick={() => setSelected(g)}
                >
                  <td>
                    <b>{g.gang}</b>
                  </td>
                  <td>{g.depot}</td>
                  <td className="num">{g.labour}</td>
                  {middle(t, engine, g, s?.rates ?? []).map((cell, i) => (
                    <td key={cols[3 + i]?.t} className={cols[3 + i]?.num ? 'num' : undefined}>
                      {cell}
                    </td>
                  ))}
                  <td>
                    <span className={`pill ${gangTone(g.status)}`}>{statusOf(g)}</span>
                  </td>
                  <td>
                    <RowActs
                      onView={() => setViewing(g)}
                      editWhy={fixOf(g)}
                      viewName={t('lbc.viewName', [g.gang])}
                      editName={t('lbc.editName', [g.gang])}
                    />
                  </td>
                </tr>
              ))
            }
          </ServerPagination>
        )}
      </Tbl>
      <div className="g2 fl-mt">
        <KvCard title={t('lbc.trace', [selected?.gang ?? DASH])} rows={trace(t, engine, s)} />
        <Panel title={t('lbc.rules')} items={rules(t, engine)} />
      </div>
      {viewing && (
        <ViewDialog
          screen={screen}
          onClose={() => setViewing(null)}
          rows={[
            { k: t('lbc.col.gang'), v: `${viewing.gang} ${viewing.gangName}`, s: 'HRMS' },
            { k: t('lbc.col.depot'), v: viewing.depot, s: 'HRMS' },
            { k: t('lbc.col.labour'), v: viewing.labour, s: 'HRMS' },
            { k: t('lbc.col.present'), v: viewing.presentDays ?? DASH, s: 'CALC' },
            { k: t('lbc.view.slips'), v: viewing.slips, s: 'ENT' },
            { k: t('lbc.view.bags'), v: viewing.bags ?? DASH, s: 'ENT' },
            { k: t('lbc.col.status'), v: statusOf(viewing), s: 'SYS' },
            { k: t('lbc.view.fix'), v: fixOf(viewing), s: 'SYS' },
          ]}
        />
      )}
    </>
  );
}

/** C-03 Departmental Labour Computation. */
export function DepartmentalComputation() {
  return <LabourComputation engine="DEPT_LABOUR" />;
}

/** C-04 DPS Labour Computation. */
export function DpsComputation() {
  return <LabourComputation engine="DPS_LABOUR" />;
}

/** C-05 NWNP Labour Computation. */
export function NwnpComputation() {
  return <LabourComputation engine="NWNP_LABOUR" />;
}
