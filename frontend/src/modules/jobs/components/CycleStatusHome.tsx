'use client';

import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { amount, Problem, ServerPagination, withBase } from '@/shared/ui';
import { Act, Locked, Panel, Tbl, Tiles, WfTile } from '@/shared/ui/wireframe';
import type { CycleStatus, Figure } from '../api/cycleStatusApi';
import { cycleStatusKeys, cycleUnitPage, useCycleStatus } from '../orchestration/cycleStatusQueries';
import { bandYear, CYCLE_STATES, CycleBand } from './CycleBand';

/** The blocking conditions as the wireframe draws them, by the API's blocker code (D-01 panel). */
const BLOCKERS: Record<
  string,
  { icon: string; src: 'CALC' | 'HRMS' | 'ENT' | 'RULE' | 'SYS'; tone: 'bad' | 'warn' }
> = {
  NET_PAY_FLOOR: { icon: '⛔', src: 'CALC', tone: 'bad' },
  BANK_DETAILS: { icon: '🏦', src: 'HRMS', tone: 'bad' },
  DPS_BOOKING_SLIP: { icon: '📄', src: 'ENT', tone: 'warn' },
  HRMS_CHANGE_PENDING: { icon: '🔗', src: 'HRMS', tone: 'warn' },
  INCENTIVE_NORMS: { icon: '⚙️', src: 'RULE', tone: 'warn' },
  PRE_PAYROLL_CHECK: { icon: '⚙️', src: 'RULE', tone: 'warn' },
};

/**
 * D-01 Home — Cycle Status (SRS D-01, D-19, D-20; Wireframes v6.1 screen d1, a frozen requirement
 * per OD-121): the period band with the gated cycle actions and the step track, the eight KPI tiles,
 * the computation status by payroll type, what is blocking the cycle and the control items needing
 * action, each with the screen to act on it. Every figure is calculated — nothing is enterable; a
 * figure whose module is not built says which module brings it, never a zero. Headquarters sees the
 * roll-up of the units below (the band says how many).
 */
export function CycleStatusHome() {
  const { t } = useI18n();
  const me = useMe();
  const status = useCycleStatus();
  const data = status.data;
  const maker = can(me.data, 'C-02', 'CREATE');
  const checker = can(me.data, 'C-02', 'APPROVE');
  const finance = can(me.data, 'O-02', 'CREATE');

  function figure(f: Figure) {
    return f.count ?? t(`d01.unavailable.${f.unavailable}`);
  }

  return (
    <section className="wireframe-body-container" aria-label={t('screen.D-01')}>
      <div className="toolbar">
        <span className="sp" />
        <Act label={t('d01.checklist')} href={withBase('/payroll/?s=C-01')} />
        {maker ? (
          <Act label={t('d01.resumeCompute')} kind="pri" href={withBase('/payroll/?s=C-13')} />
        ) : (
          <Locked label={t('d01.resumeCompute')} why={t('d01.why.resumeCompute')} />
        )}
      </div>
      <Problem error={status.error} />
      {data && (
        <>
          <Band data={data} maker={maker} checker={checker} finance={finance} />
          <Tiles>
            <WfTile icon="👥" label={t('d01.kpi.payable')} src="CALC" big={data.kpis.payable} />
            <WfTile icon="₹" label={t('d01.kpi.gross')} src="CALC" big={amount(data.kpis.gross)} tone="ok" />
            <WfTile
              icon="➖"
              label={t('d01.kpi.deductions')}
              src="CALC"
              big={amount(data.kpis.deductions)}
              tone="pur"
            />
            <WfTile
              icon="🏦"
              label={t('d01.kpi.net')}
              src="CALC"
              big={amount(data.kpis.net)}
              sub={t('d01.sub.net')}
            />
          </Tiles>
          <Tiles>
            <WfTile
              icon="⛔"
              label={t('d01.kpi.exceptions')}
              src="SYS"
              big={figure(data.kpis.exceptions)}
              tone="bad"
              bad={(data.kpis.exceptions.count ?? 0) > 0}
            />
            <WfTile
              icon="✍️"
              label={t('d01.kpi.pending')}
              src="SYS"
              big={figure(data.kpis.pendingApproval)}
              tone="warn"
            />
            <WfTile icon="↩️" label={t('d01.kpi.retro')} src="SYS" big={figure(data.kpis.retroEvents)} />
            <WfTile
              icon="🔌"
              label={t('d01.kpi.interface')}
              src="INTF"
              big={figure(data.kpis.interfaceHealth)}
              tone="ok"
            />
          </Tiles>
          <div className="g2">
            <Tbl
              title={t('d01.types')}
              acts={<Act label={t('d01.runLog')} kind="sm" href={withBase('/payroll/?s=C-13')} />}
              cols={[
                { t: t('d01.type'), s: 'RULE' },
                { t: t('d01.engine'), s: 'SYS' },
                { t: t('d01.population'), s: 'HRMS', num: true },
                { t: t('d01.computed'), s: 'CALC', num: true },
                { t: t('d01.exceptions'), s: 'SYS', num: true },
                { t: t('d01.progress') },
                { t: t('d01.status'), s: 'SYS' },
              ]}
            >
              {data.types.map((row, n) => (
                <tr key={row.payrollType}>
                  <td>
                    <b>
                      {n + 1} · {t(`d01.payrollType.${row.payrollType}`)}
                    </b>
                  </td>
                  <td>{t(`d01.engineOf.${row.payrollType}`)}</td>
                  {row.unavailable ? (
                    <td colSpan={5} className="sub2">
                      {t(`d01.unavailable.${row.unavailable}`)}
                    </td>
                  ) : (
                    <>
                      <td className="num">{row.population}</td>
                      <td className="num">{row.computed}</td>
                      <td className="num">{row.exceptions}</td>
                      <td>
                        <progress className="bar" value={progress(row.computed, row.population)} max={100} />
                      </td>
                      <td>
                        <span
                          className={`pill ${row.status === 'COMPLETE' ? 'p-ok' : row.status === 'RUNNING' ? 'p-warn' : 'p-gray'}`}
                        >
                          {t(`d01.engineState.${row.status}`)}
                        </span>
                      </td>
                    </>
                  )}
                </tr>
              ))}
            </Tbl>
            <Panel
              title={t('d01.blocking')}
              act={<Act label={t('d01.openWorklist')} kind="sm" href={withBase('/payroll/?s=X-05')} />}
              items={data.blockers.map((b) => ({
                icon: BLOCKERS[b.code]?.icon ?? '⚠️',
                tone: BLOCKERS[b.code]?.tone ?? 'warn',
                title: t(`d01.blocker.${b.code}`),
                src: BLOCKERS[b.code]?.src ?? 'SYS',
                text: t(`d01.blocker.${b.code}.basis`),
                right: <b>{figure(b.figure)}</b>,
              }))}
            />
          </div>
          <div className="g2 fl-mt">
            <Panel
              title={t('d01.control')}
              items={[
                {
                  icon: '🔁',
                  tone: 'bad',
                  title: t('d01.control.hrms'),
                  src: 'SYS',
                  text: t('d01.control.hrms.text', [
                    figure(data.blockers.find((b) => b.code === 'HRMS_CHANGE_PENDING')?.figure ?? NONE),
                  ]),
                  right: (
                    <Act label={t('d01.open', ['I-21'])} kind="sm" href={withBase('/payroll/?s=I-21')} />
                  ),
                },
                {
                  icon: '📥',
                  tone: 'warn',
                  title: t('d01.control.fallback'),
                  src: 'SYS',
                  text: t('d01.control.fallback.text', [figure(data.fallbackUploads ?? NONE)]),
                  right: (
                    <Act label={t('d01.open', ['X-07'])} kind="sm" href={withBase('/payroll/?s=X-07')} />
                  ),
                },
                {
                  icon: '👷',
                  tone: 'warn',
                  title: t('d01.control.gangs'),
                  src: 'SYS',
                  text: t('d01.control.gangs.text', [figure(data.gangShortfalls ?? NONE)]),
                  right: (
                    <Act label={t('d01.open', ['M-09'])} kind="sm" href={withBase('/payroll/?s=M-09')} />
                  ),
                },
              ]}
            />
          </div>
          {data.rollUp && <UnitRollUp />}
        </>
      )}
    </section>
  );
}

/**
 * SRS D-01 alternate flow: a consolidated Headquarters scope also shows a read-only roll-up across
 * the units. The pack draws no such table, so it stays in a closed section (WF-306).
 */
function UnitRollUp() {
  const { t } = useI18n();
  return (
    <details className="fl-mt" data-testid="d01-units">
      <summary>{t('d01.units')}</summary>
      <Tbl
        title={t('d01.units')}
        cols={[
          { t: t('d01.unit'), s: 'SYS' },
          { t: t('d01.status'), s: 'SYS' },
          { t: t('d01.computed'), s: 'CALC', num: true },
          { t: t('d01.kpi.net'), s: 'CALC', num: true },
        ]}
      >
        <ServerPagination queryKey={cycleStatusKeys.units()} fetchPage={cycleUnitPage} personal={false}>
          {(rows) =>
            rows.map((u) => (
              <tr key={u.unitCode}>
                <td>
                  <b>{u.unitCode}</b> <span className="sub2">{u.unitLabel}</span>
                </td>
                <td>{t(`d01.state.${u.status}`)}</td>
                <td className="num">{u.members ?? '—'}</td>
                <td className="num">{amount(u.net)}</td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
    </details>
  );
}

/** A figure the API does not carry yet: its module brings it (never a zero). */
const NONE: Figure = { count: null, unavailable: 'NOT_YET' };

function progress(computed: number | null, population: number | null): number {
  if (!computed || !population) return 0;
  return Math.min(100, Math.round((computed / population) * 100));
}

/** The period band: the state of the own unit (a roll-up is as far as its slowest unit). */
function Band({
  data,
  maker,
  checker,
  finance,
}: {
  data: CycleStatus;
  maker: boolean;
  checker: boolean;
  finance: boolean;
}) {
  const { t } = useI18n();
  const state = CYCLE_STATES.find((s) => (data.byStatus[s] ?? 0) > 0) ?? null;
  const scope = data.rollUp ? t('d01.band.rollUp', [data.scopeLabel, data.units]) : data.scopeLabel;
  return (
    <CycleBand
      period={data.period}
      state={state}
      line={t('d01.band.line', [bandYear(data.period), data.period.replace('-', ''), scope])}
      own={!data.rollUp}
      maker={maker}
      checker={checker}
      finance={finance}
    />
  );
}
