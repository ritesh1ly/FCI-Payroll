'use client';

import { useState } from 'react';
import { FlaggedTdsCases } from '@/modules/tax';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { can, periodLabel, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import {
  amount,
  date,
  dateTime,
  Empty,
  ExportButton,
  Problem,
  ReasonDialog,
  ServerPagination,
  Src,
  useToast,
  withBase,
} from '@/shared/ui';
import { OrderCopyCard } from '@/shared/ui/orderCopy';
import {
  Act,
  Explained,
  Locked,
  Panel,
  type PanelItem,
  RowActs,
  Tbl,
  Tiles,
  ViewDialog,
  WfTile,
} from '@/shared/ui/wireframe';
import type {
  CheckView,
  CycleOverview,
  GateView,
  PeriodRow,
  PrePayrollReportRow,
  QuarantineView,
} from '../api/cycleApi';
import {
  cycleKeys,
  cycleOrderUrl,
  cyclePages,
  registerExportUrl,
  useAttachCycleOrder,
  useCycleAction,
  useCycleDocumentTypes,
  useCycleOrders,
  useCycleOverview,
  useGateAction,
  useGates,
} from '../orchestration/cycleQueries';
import { bandYear, CycleBand } from './CycleBand';

/** SRS-15-39 / l.1767: a check "links to ... the fixing screen". */
function fixingScreen(code: string | null): string | null {
  return code && /^[A-Z]-\d{2}$/.test(code) ? withBase(`/payroll/?s=${code}`) : null;
}

const SYSTEM_ADMINISTRATOR = 'SYSTEM_ADMINISTRATOR';
const LOCKED_STATE = 'LOCKED';

/** The gates as the panel draws them; the hard gates carry the drawn pill (SRS-15-15). */
const HARD = new Set(['G0', 'G1', 'G3', 'G4']);
const GATES = ['G0', 'G1', 'G2', 'G3', 'G4', 'G5', 'G6'];

/** The engine states that end a run for the Engines Computed tile (SRS-15-18 G1). */
const COMPLETE = 'COMPLETE';

type Pending =
  | { kind: 'gate'; gate: GateView }
  | { kind: 'check'; check: CheckView }
  | { kind: 'member'; member: QuarantineView; accepted: boolean };

/**
 * C-01 Payroll Cycle Control as Wireframes v6.1 draws it (OD-121; SRS-15-11, SRS-15-14 ... 18,
 * SRS-15-39, SRS-15-42, SRS l.1755-1767): the actions over the screen (each lands on the screen that
 * holds its right: C-13 runs, C-02 verifies and requests the lock, W-01 decides, so no rule moved
 * here), the period band, the four tiles, the Period Register with the independent Posted and
 * Supplementary flags, the initiation checks P1-P5, the platform controls, the seven pre-lock gates
 * with the pre-payroll check row, the Gate Override & Undo / Reversal Register, the instances by
 * organisational level, the direct payroll notes, the pre-payroll checks with their report, the
 * HRMS fallback status, the run state and the order copy (SRS-G-32). The reads are on C-01 VIEW:
 * the Administrator and Finance hold no C-02. What the Checker decides and the pack does not draw
 * (accept a gate, acknowledge a Warn check, review a quarantined member, flagged TDS cases) stays
 * here in closed sections. A period with no cycle draws every object with its gates waiting.
 */
export function CycleControl() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const me = useMe();
  const period = me.data?.period ?? '';
  const gates = useGates(period);
  const overview = useCycleOverview(period);
  const noCycle = gates.error instanceof ApiError && gates.error.status === 404;
  const action = useGateAction(period);
  const cycleAction = useCycleAction(period);
  const [pending, setPending] = useState<Pending | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [release, setRelease] = useState(false);
  const [report, setReport] = useState(false);
  const [viewing, setViewing] = useState<PeriodRow | null>(null);
  // G5 (SRS l.1524, BMAD TR-010): the members legitimately excluded, named by EIS.
  const [excluded, setExcluded] = useState('');
  const mayDecide = can(me.data, 'C-01', 'APPROVE');
  const maker = can(me.data, 'C-02', 'CREATE');
  const checker = can(me.data, 'C-02', 'APPROVE');
  const finance = can(me.data, 'O-02', 'CREATE');
  const admin = me.data?.roles?.includes(SYSTEM_ADMINISTRATOR) === true;
  const mayRelease = can(me.data, 'C-02', 'LEASE_RELEASE');
  const g = gates.data;
  const o = overview.data;
  const locked = g?.status === LOCKED_STATE;

  async function confirm(reason: string) {
    if (!pending) return;
    setError(null);
    try {
      if (pending.kind === 'gate') {
        const members =
          pending.gate.code === 'G5' ? excluded.split(/[\s,]+/).filter((m) => m !== '') : undefined;
        await action.mutateAsync({ kind: 'gate', gate: pending.gate.code, reason, members });
        toast(t('c01.accepted', [pending.gate.code]));
      } else if (pending.kind === 'check') {
        await action.mutateAsync({ kind: 'check', checkId: pending.check.checkId, reason });
        toast(t('c01.acknowledged', [pending.check.code]));
      } else {
        await action.mutateAsync({
          kind: 'member',
          id: pending.member.id,
          accepted: pending.accepted,
          reason,
        });
        toast(t('c01.reviewed', [pending.member.empNum]));
      }
    } catch (cause) {
      setError(cause);
    } finally {
      setPending(null);
      setExcluded('');
    }
  }

  async function requestRelease(reason: string) {
    setRelease(false);
    setError(null);
    try {
      await cycleAction.mutateAsync({ action: 'leaseRelease', reason });
      toast(t('c01w.releaseAsked'));
    } catch (cause) {
      setError(cause);
    }
  }

  const frozen = o?.frozenBy && o.frozenAt ? t('c01w.band.frozen', [o.frozenBy, dateTime(o.frozenAt)]) : null;
  const scope = o ? `${o.unitCode} ${o.unitLabel}` : '';
  const line = frozen
    ? t('c01w.band.line', [bandYear(period), period.replace('-', ''), frozen, scope])
    : t('d01.band.line', [bandYear(period), period.replace('-', ''), scope]);

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {maker ? (
          <Act label={t('c01w.run')} kind="pri" href={withBase('/payroll/?s=C-13')} />
        ) : (
          <Locked label={t('c01w.run')} why={t('c01w.why.maker')} />
        )}
        {checker ? (
          <Act label={t('c01w.verifyLock')} kind="pri" href={withBase('/payroll/?s=C-02#c02-cycle')} />
        ) : (
          <Locked label={t('c01w.verifyLock')} why={t('c01w.why.checker')} />
        )}
        {admin ? (
          <Act label={t('c01w.undo')} href={withBase('/payroll/?s=W-01')} />
        ) : (
          <Locked label={t('c01w.undo')} why={t('c01w.why.admin')} />
        )}
        {maker ? (
          <Act label={t('c01w.resume')} href={withBase('/payroll/?s=C-13')} />
        ) : (
          <Locked label={t('c01w.resume')} why={t('c01w.why.maker')} />
        )}
        {mayRelease ? (
          o?.lease ? (
            <button type="button" className="btn" onClick={() => setRelease(true)}>
              {t('c01w.release')}
            </button>
          ) : (
            <Explained label={t('c01w.release')} why={t('c01w.why.noStuckRun')} />
          )
        ) : (
          <Locked label={t('c01w.release')} why={t('c01w.why.release')} />
        )}
      </div>
      {/* UI audit S4: no cycle initiated yet for the period at the unit is a state, not an error. */}
      <Problem error={noCycle ? (overview.error ?? error) : (gates.error ?? overview.error ?? error)} />
      {noCycle && <Empty text={t('c01.noCycle', [periodLabel(period, locale)])} />}
      {o && (
        <>
          <CycleBand
            period={period}
            state={o.status}
            line={line}
            own
            maker={maker}
            checker={checker}
            finance={finance}
          />
          <CycleTiles overview={o} />
        </>
      )}
      <div className="g2">
        <Tbl
          title={t('c01w.periods')}
          acts={<Locked label={t('c01w.reopen')} kind="sm" why={t('c01w.why.reopen')} />}
          cols={[
            { t: t('c01w.col.period'), s: 'SYS' },
            { t: t('c01w.col.status'), s: 'SYS' },
            { t: t('c01w.col.computed'), s: 'SYS' },
            { t: t('c01w.col.verified'), s: 'SYS' },
            { t: t('c01w.col.locked'), s: 'SYS' },
            { t: t('c01w.col.posted'), s: 'SYS' },
            { t: t('c01w.col.supplementary'), s: 'SYS' },
            { t: t('c08.col.actions'), s: 'SYS' },
          ]}
        >
          <ServerPagination
            queryKey={cycleKeys.periodRegister()}
            fetchPage={cyclePages.periodRegister}
            personal={false}
            empty={<p className="fl-hint">{t('c01w.noPeriods')}</p>}
          >
            {(rows) =>
              rows.map((r) => (
                <PeriodRowView
                  key={r.period}
                  row={r}
                  maker={maker}
                  checker={checker}
                  onView={() => setViewing(r)}
                />
              ))
            }
          </ServerPagination>
        </Tbl>
        <Panel
          title={t('c01w.preRun')}
          items={(o?.preRun ?? []).map((c): PanelItem => ({
            icon: c.code.slice(0, 2),
            tone: 'bad',
            title: t(`c01w.preRunName.${c.code.slice(0, 2)}`),
            src: 'SYS',
            text: c.members.length > 0 ? c.members.join(', ') : t(`c01w.preRun.${c.code.slice(0, 2)}`),
            right: (
              <span
                className={`pill ${c.status === 'PASS' ? 'p-ok' : c.status === 'FAIL' ? 'p-bad' : 'p-gray'}`}
              >
                {t(`cyc.preRun.${c.status}`)}
              </span>
            ),
          }))}
        />
        <Panel
          title={t('c01w.platform')}
          items={[
            {
              icon: '🔐',
              tone: 'bad',
              title: t('c01w.platform.otp'),
              src: 'SYS',
              right: <span className="pill p-ok">§6.4.2.5</span>,
            },
            {
              icon: '📎',
              tone: 'bad',
              title: t('c01w.platform.attachment'),
              src: 'ENT',
              right: <span className="pill p-ok">{t('c01w.platform.enforced')}</span>,
            },
          ]}
        />
        <GatePanel gates={g?.gates} checks={g?.checks} noCycle={noCycle} />
      </div>
      <Tbl
        title={t('c01w.registerTitle')}
        acts={
          can(me.data, 'C-01', 'EXPORT') ? (
            <ExportButton href={registerExportUrl()} what={t('c01.register')} label={t('c01w.export')} />
          ) : (
            <Explained label={t('c01w.export')} kind="sm" why={t('c01w.why.export')} />
          )
        }
        cols={[
          { t: t('c01.reg.txn'), s: 'SYS' },
          { t: t('c01.reg.period'), s: 'SYS' },
          { t: t('c01.reg.object'), s: 'SYS' },
          { t: t('common.reason'), s: 'ENT' },
          { t: t('c01.reg.requestedBy'), s: 'SYS' },
          { t: t('c01.reg.authorisedBy'), s: 'SYS' },
          { t: t('c01.reg.stage'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={cycleKeys.register()}
          fetchPage={cyclePages.register()}
          empty={<p className="fl-hint">{t('c01.registerEmpty')}</p>}
        >
          {(rows) =>
            rows.map((r) => (
              <tr key={r.id}>
                <td>
                  <b>{r.id}</b> {t(`c01.regType.${r.type}`)}
                  <div className="sub2">{dateTime(r.executedAt)}</div>
                </td>
                <td>{r.period ? periodLabel(`${r.period.slice(0, 4)}-${r.period.slice(4)}`) : '—'}</td>
                <td>{r.type === 'GATE_OVERRIDE' ? t('c01.reg.gate', [r.key]) : `${r.entity} ${r.key}`}</td>
                <td>{r.reason}</td>
                <td>{r.requestedBy}</td>
                <td>
                  {r.authorisedBy}
                  {r.checkedBy && <div className="sub2">{t('c01.reg.checkedBy', [r.checkedBy])}</div>}
                </td>
                <td>{r.stage ? t(`cyc.state.${r.stage}`) : '—'}</td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <Tbl
          title={t('c01w.levels', [periodLabel(period, locale)])}
          cols={[
            { t: t('c01w.level'), s: 'SYS' },
            { t: t('c01w.instances'), s: 'SYS', num: true },
            { t: t('c01w.lockedCount'), s: 'SYS', num: true },
            { t: t('c01w.rule'), s: 'RULE' },
          ]}
        >
          {(o?.levels ?? []).map((l) => (
            <tr key={l.level}>
              <td>{t(`c01w.level.${l.level}`)}</td>
              <td className="num">{l.instances}</td>
              <td className="num">{l.locked}</td>
              <td>{t(`c01w.level.${l.level}.rule`)}</td>
            </tr>
          ))}
        </Tbl>
        <Panel
          title={t('c01w.direct')}
          items={[
            {
              icon: '🪟',
              tone: 'info',
              title: t('c01w.direct.mdp'),
              src: 'RULE',
              text: t('c01w.direct.mdp.text'),
            },
            {
              icon: '🧪',
              tone: 'warn',
              title: t('c01w.direct.slip'),
              src: 'SYS',
              text: t('c01w.direct.slip.text'),
            },
            {
              icon: '↔️',
              tone: 'info',
              title: t('c01w.direct.nature'),
              src: 'RULE',
              text: t('c01w.direct.nature.text'),
            },
          ]}
        />
      </div>
      <div className="g2 fl-mt">
        <Tbl
          title={t('c01w.checks')}
          acts={
            <button type="button" className="btn sm" onClick={() => setReport(true)}>
              {t('c01w.report')}
            </button>
          }
          cols={[
            { t: t('c01.check'), s: 'RULE' },
            { t: t('c01w.appliesTo'), s: 'RULE' },
            { t: t('c01.severity'), s: 'RULE' },
            { t: t('c01w.thisCycle'), s: 'SYS' },
          ]}
        >
          {(g?.checks ?? []).length === 0 && (
            <tr>
              <td colSpan={4} className="sub2">
                {t('c01.noChecks')}
              </td>
            </tr>
          )}
          {(g?.checks ?? []).map((c) => (
            <tr key={c.checkId}>
              <td>{c.name}</td>
              <td>{t(`c01w.applies.${c.appliesTo}`)}</td>
              <td>{t(`c01.severity.${c.severity}`)}</td>
              <td>
                <CheckCount check={c} />
              </td>
            </tr>
          ))}
        </Tbl>
        <Panel
          title={t('c01w.fallback')}
          items={
            (o?.fallback ?? []).length > 0
              ? (o?.fallback ?? []).map((f): PanelItem => ({
                  icon: f.type === 'ATTENDANCE_LEAVE' ? 'a' : 'b',
                  tone: f.status === 'APPROVED' || f.status === 'RECONCILED' ? 'ok' : 'warn',
                  title: t(`c01w.fallback.${f.type}`, [`${f.unitCode} ${f.unitLabel}`]),
                  src: 'SYS',
                  text: t(`c01w.fallback.text.${f.status}`),
                  right: (
                    <span className={`pill ${f.blocksLock ? 'p-warn' : 'p-ok'}`}>
                      {t(`c01w.fallback.status.${f.status}`)}
                    </span>
                  ),
                }))
              : [
                  {
                    icon: 'a',
                    tone: 'gray',
                    title: t('c01w.fallback.none'),
                    src: 'SYS',
                    text: t('c01w.fallback.noneText'),
                    right: <span className="pill p-gray">{t('c01w.fallback.status.NOT_ENABLED')}</span>,
                  },
                ]
          }
        />
      </div>
      <div className="fl-mt">
        <RunStatePanel overview={o} />
      </div>
      <OrderCard period={period} cycleId={o?.cycleId ?? null} maker={maker} drawn={maker || admin} />
      <details className="fl-mt" data-testid="c01-decisions">
        <summary>{t('c01w.decisions')}</summary>
        {g && (
          <GateDetail
            view={g}
            mayDecide={mayDecide}
            locked={locked}
            onAccept={(gate) => setPending({ kind: 'gate', gate })}
            onAcknowledge={(check) => setPending({ kind: 'check', check })}
          />
        )}
        <FlaggedTdsCases period={period} />
        {g && (
          <Tbl
            title={t('c01.quarantine')}
            cols={[
              { t: t('c01.member'), s: 'HRMS' },
              { t: t('c01.reason'), s: 'SYS' },
              { t: t('c01.net'), s: 'CALC' },
              { t: t('mst.state'), s: 'SYS' },
              { t: t('common.actions') },
            ]}
          >
            <ServerPagination
              queryKey={cycleKeys.quarantine(period)}
              fetchPage={cyclePages.quarantine(period)}
              empty={<p className="fl-hint">{t('c01.noQuarantine')}</p>}
            >
              {(rows) =>
                rows.map((m) => (
                  <tr key={m.id}>
                    <td>
                      <b>{m.empNum}</b>
                      <div className="sub2">{m.name}</div>
                    </td>
                    <td>{t(`c01.qreason.${m.reason}`)}</td>
                    <td>{m.net === null ? '—' : amount(m.net)}</td>
                    <td>
                      {t(`c01.review.${m.reviewStatus}`)}
                      {m.reviewReason && <div className="sub2">{m.reviewReason}</div>}
                    </td>
                    <td>
                      {mayDecide && !locked && m.reviewStatus === 'UNREVIEWED' && (
                        <div className="acts">
                          <button
                            type="button"
                            className="ia"
                            onClick={() => setPending({ kind: 'member', member: m, accepted: true })}
                          >
                            {t('c01.acceptMember')}
                          </button>
                          <button
                            type="button"
                            className="ia"
                            onClick={() => setPending({ kind: 'member', member: m, accepted: false })}
                          >
                            {t('c01.corrected')}
                          </button>
                        </div>
                      )}
                    </td>
                  </tr>
                ))
              }
            </ServerPagination>
          </Tbl>
        )}
      </details>
      {viewing && <PeriodDialog row={viewing} onClose={() => setViewing(null)} />}
      {report && <ReportDialog period={period} onClose={() => setReport(false)} />}
      {release && (
        <ReasonDialog
          title={t('c01w.release')}
          onCancel={() => setRelease(false)}
          onConfirm={(reason) => void requestRelease(reason)}
        />
      )}
      {pending && (
        <ReasonDialog
          maxLength={1000}
          title={
            pending.kind === 'gate'
              ? t('c01.acceptTitle', [pending.gate.code])
              : pending.kind === 'check'
                ? t('c01.acknowledgeTitle', [pending.check.code])
                : t('c01.reviewTitle', [pending.member.empNum])
          }
          note={
            pending.kind === 'gate' && pending.gate.code === 'G5' ? (
              <>
                {t('c01.g5MembersHint')}
                <label className="fl-field">
                  {t('c01.g5Members')}
                  <input
                    name="excluded"
                    maxLength={400}
                    value={excluded}
                    onChange={(e) => setExcluded(e.target.value)}
                  />
                </label>
              </>
            ) : undefined
          }
          onConfirm={(reason) => void confirm(reason)}
          onCancel={() => {
            setPending(null);
            setExcluded('');
          }}
        />
      )}
    </div>
  );
}

/** The four drawn tiles (SRS-15-14, SRS-15-18; DDL payroll_calendar.disbursement_date). */
function CycleTiles({ overview }: { overview: CycleOverview }) {
  const { t } = useI18n();
  const { tiles } = overview;
  const days = tiles.daysToDisbursement;
  const states = Object.entries(tiles.engines);
  const done = states.filter(([, s]) => s === COMPLETE).length;
  const open = states
    .filter(([, s]) => s !== COMPLETE)
    .map(([type, s]) => `${t(`d01.payrollType.${type}`)} ${t(`d01.engineState.${s}`)}`)
    .join(' · ');
  return (
    <Tiles>
      <WfTile
        icon="📅"
        label={t('c01w.tile.day')}
        src="CALC"
        big={days === null ? '—' : days >= 0 ? `D-${days}` : `D+${-days}`}
        sub={tiles.disbursement ? t('c01w.tile.daySub', [date(tiles.disbursement)]) : t('c01w.tile.dayNone')}
      />
      <WfTile
        icon="✅"
        label={t('c01w.tile.engines')}
        src="SYS"
        big={`${done} / ${states.length}`}
        sub={open}
        tone="ok"
      />
      <WfTile
        icon="⛔"
        label={t('c01w.tile.blocking')}
        src="SYS"
        big={tiles.blockingExceptions}
        sub={t('c01w.tile.blockingSub')}
        tone="bad"
        bad={tiles.blockingExceptions > 0}
      />
      <WfTile
        icon="✍️"
        label={t('c01w.tile.verification')}
        src="SYS"
        big={tiles.verifiedBy ? t('c01w.tile.verified') : t('c01w.tile.notStarted')}
        sub={tiles.verifiedBy ? t('c01w.tile.checker', [tiles.verifiedBy]) : ''}
        tone="warn"
      />
    </Tiles>
  );
}

/** One row of the Period Register: Posted and Supplementary are flags, not statuses (SRS-15-12). */
function PeriodRowView({
  row,
  maker,
  checker,
  onView,
}: {
  row: PeriodRow;
  maker: boolean;
  checker: boolean;
  onView: () => void;
}) {
  const { t } = useI18n();
  const closed = row.status === LOCKED_STATE;
  const onCycle = () => window.location.assign(withBase('/payroll/?s=C-02#c02-cycle'));
  return (
    <tr>
      <td>
        <b>{row.period.replace('-', '')}</b> {periodLabel(row.period)}
      </td>
      <td>
        <span className={`pill ${closed ? 'p-info' : 'p-warn'}`}>{t(`d01.state.${row.status}`)}</span>
      </td>
      <td>{row.computedAt ? date(row.computedAt) : '—'}</td>
      <td>
        {row.verifiedAt ? date(row.verifiedAt) : '—'}
        {row.verifiedBy && <div className="sub2">{row.verifiedBy}</div>}
      </td>
      <td>{row.lockedAt ? date(row.lockedAt) : '—'}</td>
      <td>{row.postedToEbs ? t('x05.yes') : '—'}</td>
      <td>
        {row.mdpOpen ? (
          <span className="pill p-warn">{t('c01w.mdpOpen')}</span>
        ) : row.supplementaryOpen ? (
          <span className="pill p-warn">{t('c01w.supplementaryOpen')}</span>
        ) : (
          '—'
        )}
      </td>
      <td>
        <RowActs
          viewName={t('c01w.viewOf', [row.period])}
          onView={onView}
          onEdit={!closed && maker ? onCycle : undefined}
          editWhy={closed ? t(row.postedToEbs ? 'c01w.why.posted' : 'c01w.why.locked') : t('c01w.why.maker')}
          onApprove={!closed && checker ? onCycle : undefined}
        />
      </td>
    </tr>
  );
}

function PeriodDialog({ row, onClose }: { row: PeriodRow; onClose: () => void }) {
  const { t } = useI18n();
  return (
    <ViewDialog
      screen={t('screen.C-01')}
      onClose={onClose}
      rows={[
        { k: t('c01w.col.period'), v: periodLabel(row.period), s: 'SYS' },
        { k: t('c01w.col.status'), v: t(`d01.state.${row.status}`), s: 'SYS' },
        { k: t('c01w.col.computed'), v: row.computedAt ? dateTime(row.computedAt) : '—', s: 'SYS' },
        {
          k: t('c01w.col.verified'),
          v: row.verifiedAt ? `${dateTime(row.verifiedAt)} ${row.verifiedBy ?? ''}` : '—',
          s: 'SYS',
        },
        { k: t('c01w.col.locked'), v: row.lockedAt ? dateTime(row.lockedAt) : '—', s: 'SYS' },
        { k: t('c01w.col.posted'), v: row.postedToEbs ? t('x05.yes') : t('x05.no'), s: 'SYS' },
        {
          k: t('c01w.col.supplementary'),
          v: row.mdpOpen || row.supplementaryOpen ? t('x05.yes') : t('x05.no'),
          s: 'SYS',
        },
      ]}
    />
  );
}

/** The state pill of a gate: met, accepted with a reason, not met, or waiting for a cycle. */
function GatePill({ gate }: { gate?: GateView }) {
  const { t } = useI18n();
  if (!gate) return <span className="pill p-gray">{t('c01w.waiting')}</span>;
  if (gate.met) return <span className="pill p-ok">{t('c01.met')}</span>;
  if (gate.acceptedBy) {
    return <span className="pill p-warn">{t('c01.acceptedBy', [gate.acceptedBy])}</span>;
  }
  return <span className="pill p-bad">{t('c01.unmet')}</span>;
}

/** Pre-lock gates G0-G6 and the pre-payroll check row as the pack draws them (SRS-15-14, l.1760). */
function GatePanel({
  gates,
  checks,
  noCycle,
}: {
  gates?: GateView[];
  checks?: CheckView[];
  noCycle: boolean;
}) {
  const { t } = useI18n();
  const pendingHrms = checks?.find((c) => c.code === 'HRMS_CHANGE_PENDING');
  const failedBlocks = (checks ?? []).filter((c) => c.severity === 'BLOCK' && c.result !== 'PASS').length;
  const items: PanelItem[] = GATES.map((code) => {
    const gate = noCycle ? undefined : gates?.find((x) => x.code === code);
    return {
      icon: code,
      tone: HARD.has(code) ? 'bad' : 'info',
      title: t(`c01w.gate.${code}`),
      badge: HARD.has(code) ? <span className="pill p-bad">{t('c01w.hardGate')}</span> : undefined,
      src: 'SYS',
      text: gate?.detail || t(`c01w.gateNote.${code}`),
      right: <GatePill gate={gate} />,
    };
  });
  items.push({
    icon: 'PC',
    tone: 'bad',
    title: t('c01w.gate.PC'),
    badge: <span className="pill p-bad">{t('c01w.block')}</span>,
    src: 'SYS',
    text: t('c01w.gateNote.PC'),
    right:
      checks === undefined ? (
        <span className="pill p-gray">{t('c01w.waiting')}</span>
      ) : failedBlocks === 0 ? (
        <span className="pill p-ok">{t('c01.met')}</span>
      ) : (
        <span className="pill p-bad">{t('c01w.pending', [pendingHrms?.failures ?? failedBlocks])}</span>
      ),
  });
  return <Panel title={t('c01w.gates')} items={items} />;
}

/** The This cycle figure of a check, linked to the fixing screen while it fails (SRS l.1767). */
function CheckCount({ check }: { check: CheckView }) {
  const { t } = useI18n();
  const href = fixingScreen(check.correctionScreen);
  if (check.result === 'PASS') return <span className="pill p-ok">0</span>;
  if (check.acknowledgedBy) {
    return <span className="pill p-warn">{t('c01w.acknowledged', [check.failures])}</span>;
  }
  const cls = `pill ${check.severity === 'WARN' ? 'p-warn' : 'p-bad'}`;
  // As drawn, the count of the I-21 check reads "5 pending" and opens I-21; the others are the plain count.
  const count = check.correctionScreen === 'I-21' ? t('c01w.pending', [check.failures]) : check.failures;
  return <span className={cls}>{href ? <a href={href}>{count}</a> : count}</span>;
}

/** SRS-15-16, SRS l.1767: what the Checker decides and the owner of each check, undrawn, kept. */
function GateDetail({
  view,
  mayDecide,
  locked,
  onAccept,
  onAcknowledge,
}: {
  view: NonNullable<ReturnType<typeof useGates>['data']>;
  mayDecide: boolean;
  locked: boolean;
  onAccept: (gate: GateView) => void;
  onAcknowledge: (check: CheckView) => void;
}) {
  const { t } = useI18n();
  return (
    <>
      <p className={view.lockable ? 'fl-ok' : 'sub2'}>
        {view.lockable ? t('c01.lockable') : t('c01.blocking', [view.blocking.join(', ')])}
      </p>
      <Tbl
        title={t('c01w.gateDetail')}
        cols={[
          { t: t('c01.gate'), s: 'SYS' },
          { t: t('c01.condition'), s: 'RULE' },
          { t: t('mst.state'), s: 'SYS' },
          { t: t('c01.detail'), s: 'SYS' },
          { t: t('common.actions') },
        ]}
      >
        {view.gates.map((gate) => (
          <tr key={gate.code}>
            <td>
              <b>{gate.code}</b>
              {!gate.overridable && <div className="sub2">{t('c01.hard')}</div>}
              {/* SRS l.1525 / SRS-15-16 (BMAD TR-007): G6 is accepted member by member. */}
              {gate.code === 'G6' && <div className="sub2">{t('c01.perMember')}</div>}
            </td>
            <td>{t(`c01.gate.${gate.code}`)}</td>
            <td>
              <GatePill gate={gate} />
            </td>
            <td>
              {gate.detail}
              {gate.acceptedReason && <div className="sub2">{gate.acceptedReason}</div>}
            </td>
            <td>
              {mayDecide && !locked && gate.acceptable && (
                <button type="button" className="ia" onClick={() => onAccept(gate)}>
                  {t('action.acceptGate')}
                </button>
              )}
            </td>
          </tr>
        ))}
      </Tbl>
      <Tbl
        title={t('c01.checks')}
        cols={[
          { t: t('c01.check'), s: 'RULE' },
          { t: t('c01.severity'), s: 'RULE' },
          { t: t('c01.result'), s: 'SYS' },
          { t: t('c01.detail'), s: 'SYS' },
          { t: t('c01.fixOn'), s: 'SYS' },
          { t: t('common.actions') },
        ]}
      >
        {view.checks.map((c) => (
          <tr key={c.checkId}>
            <td>
              {c.name}
              <div className="sub2">{c.code}</div>
            </td>
            <td>{t(`c01.severity.${c.severity}`)}</td>
            <td>
              {c.result === 'PASS' ? (
                <span className="pill p-ok">{t('c01.pass')}</span>
              ) : c.acknowledgedBy ? (
                <span className="pill p-warn">{t('c01.acknowledgedBy', [c.acknowledgedBy])}</span>
              ) : (
                <span className="pill p-bad">{t('c01.fail', [c.failures])}</span>
              )}
            </td>
            <td>{c.detail}</td>
            <td>
              {fixingScreen(c.correctionScreen) ? (
                <a href={fixingScreen(c.correctionScreen) as string}>{c.correctionScreen}</a>
              ) : (
                (c.correctionScreen ?? '—')
              )}
            </td>
            <td>
              {mayDecide && !locked && c.severity === 'WARN' && c.result === 'FAIL' && !c.acknowledgedBy && (
                <button type="button" className="ia" onClick={() => onAcknowledge(c)}>
                  {t('action.acknowledge')}
                </button>
              )}
            </td>
          </tr>
        ))}
      </Tbl>
    </>
  );
}

/** SRS-15-24 ... 15-28, l.1764: the run, its progress, its options and the lease holder. */
function RunStatePanel({ overview }: { overview?: CycleOverview }) {
  const { t } = useI18n();
  const run = overview?.run ?? null;
  const lease = overview?.lease ?? null;
  const failed = run !== null && (run.status === 'FAILED' || run.status === 'PARTIAL');
  return (
    <Panel
      title={t('c01w.runState')}
      items={[
        {
          icon: '▶',
          tone: 'info',
          title: run ? t('c01w.run.id', [run.id]) : t('c01w.run.none'),
          src: 'SYS',
          text: run
            ? t('c01w.run.progress', [
                run.unitsCommitted,
                run.units ?? '—',
                run.unitsFailed,
                dateTime(run.startedAt),
                run.startedBy,
              ])
            : '',
          right: (
            <span className="pill p-warn">
              {run ? t(`c01w.runStatus.${run.status}`) : t('d01.state.NONE')}
            </span>
          ),
        },
        {
          icon: '⏯',
          tone: 'ok',
          title: t('c01w.run.resume'),
          src: 'SYS',
          text: t('c01w.run.resumeText'),
          right: (
            <span className={`pill ${failed ? 'p-ok' : 'p-gray'}`}>
              {failed ? t('c01w.run.available') : t('c01w.run.notNeeded')}
            </span>
          ),
        },
        {
          icon: '🔁',
          tone: 'warn',
          title: t('c01w.run.restart'),
          src: 'RULE',
          text: t('c01w.run.restartText'),
          right: <span className="pill p-gray">{t('c01w.run.notNeeded')}</span>,
        },
        {
          icon: '🔓',
          tone: 'bad',
          title: t('c01w.run.stuck'),
          src: 'SYS',
          text: lease
            ? t('c01w.run.leaseHeld', [lease.heldBy, lease.action, dateTime(lease.acquiredAt)])
            : t('c01w.run.stuckText'),
          right: (
            <span className={`pill ${lease ? 'p-warn' : 'p-gray'}`}>
              {lease ? t('c01w.run.leased') : t('c01w.run.noStuck')}
            </span>
          ),
        },
        {
          icon: '🗓',
          tone: 'info',
          title: t('c01w.run.onDemand'),
          src: 'ENT',
          text: t('c01w.run.onDemandText'),
          right: <span className="pill p-info">{t('c01w.run.onDemandMode')}</span>,
        },
      ]}
    />
  );
}

/** SRS-15-39, US-05-10 AC3: the failed checks of the scope, by office, with the fixing link. */
function ReportDialog({ period, onClose }: { period: string; onClose: () => void }) {
  const { t } = useI18n();
  return (
    <div className="fl-modal-backdrop" role="presentation">
      <div className="fl-modal fl-modal-wide" role="dialog" aria-modal="true" aria-label={t('c01w.report')}>
        <h2>{t('c01w.report')}</h2>
        <ServerPagination<ReportPage>
          queryKey={cycleKeys.report(period)}
          fetchPage={cyclePages.report(period)}
          personal={false}
          empty={<p className="fl-hint">{t('c01w.reportEmpty')}</p>}
        >
          {(rows) => (
            <div className="wrap">
              <table className="t" aria-label={t('c01w.report')}>
                <thead>
                  <tr>
                    <th>
                      {t('c01w.office')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('c01.check')}
                      <Src s="RULE" />
                    </th>
                    <th>
                      {t('c01.severity')}
                      <Src s="RULE" />
                    </th>
                    <th>
                      {t('c01w.thisCycle')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('c01.fixOn')}
                      <Src s="SYS" />
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={`${r.unitCode}|${r.checkCode}`}>
                      <td>
                        <b>{r.unitCode}</b> <span className="sub2">{r.unitLabel}</span>
                      </td>
                      <td>
                        {r.checkName}
                        {r.detail && <div className="sub2">{r.detail}</div>}
                      </td>
                      <td>{t(`c01.severity.${r.severity}`)}</td>
                      <td className="num">{r.failures}</td>
                      <td>
                        {fixingScreen(r.correctionScreen) ? (
                          <a href={fixingScreen(r.correctionScreen) as string}>{r.correctionScreen}</a>
                        ) : (
                          (r.correctionScreen ?? '—')
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </ServerPagination>
        <div className="rf-actbar">
          <button type="button" className="btn" onClick={onClose}>
            {t('common.close')}
          </button>
        </div>
      </div>
    </div>
  );
}

interface ReportPage {
  items: PrePayrollReportRow[];
  page: number;
  size: number;
  total: number;
}

/**
 * SRS-G-32, SRS-G-30: the override approval note with the period's instance. The Payroll Maker
 * stores it; the pack draws the fields open for the Administrator too, who holds no right to store
 * (OD-135): the submit stays disabled for that role.
 */
function OrderCard({
  period,
  cycleId,
  maker,
  drawn,
}: {
  period: string;
  cycleId: number | null;
  maker: boolean;
  drawn: boolean;
}) {
  const { t } = useI18n();
  const orders = useCycleOrders(period, true);
  const types = useCycleDocumentTypes();
  const attach = useAttachCycleOrder(period);
  return (
    <OrderCopyCard
      key={cycleId ?? 0}
      hint={t('c01w.orderHint')}
      canAttach={maker && cycleId !== null}
      readOnly={!drawn}
      stored={orders.data}
      storedError={orders.error ?? types.error}
      fileHref={(file) => cycleOrderUrl(period, file)}
      today={businessToday()}
      attachmentRequired={false}
      referenceRequired={(code) => types.data?.find((d) => d.code === code)?.referenceRequired ?? false}
      onAttach={async (o) => {
        for (const file of o.files) {
          const form = new FormData();
          form.append('documentType', o.documentType);
          if (o.reference) form.append('reference', o.reference);
          form.append('orderDate', o.orderDate);
          if (o.issuingOffice) form.append('issuingAuthority', o.issuingOffice);
          form.append('file', file);
          await attach.mutateAsync(form);
        }
      }}
    />
  );
}
