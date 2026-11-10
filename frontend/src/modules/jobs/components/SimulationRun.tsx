'use client';

import { type FormEvent, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import {
  amount,
  Card,
  dateTime,
  ExportButton,
  Problem,
  ServerPagination,
  Src,
  useDialogFocus,
  useToast,
  TextAreaField,
} from '@/shared/ui';
import { Act, Explained, Locked, Panel, RowActs, Tbl, Tiles, Toolbar, WfTile } from '@/shared/ui/wireframe';
import type {
  SimPopulation,
  SimulationDetail,
  SimulationInput,
  SimulationSide,
  SimWhatIf,
} from '../api/simulationApi';
import {
  simulationExportUrl,
  simulationKeys,
  simulationPages,
  useRunSimulation,
  useSimulationDetail,
  useSimulationTiles,
} from '../orchestration/simulationQueries';

const EMPLOYEE_NUMBER = /^[\x21-\x7E]{1,20}$/;

const DIGITS = /^\d+$/;

/** A DA % as the server takes it: up to ten integer digits and four decimals (Numeric 14,4). */
export function rateOk(value: string): boolean {
  const [whole = '', fraction, extra] = value.split('.');
  if (extra !== undefined || whole.length < 1 || whole.length > 10 || !DIGITS.test(whole)) return false;
  return fraction === undefined || (fraction.length >= 1 && fraction.length <= 4 && DIGITS.test(fraction));
}

/** The employee numbers typed, one per line or separated by commas. */
export function membersOf(text: string): string[] {
  return text
    .split(/[\n,]/)
    .map((m) => m.trim())
    .filter((m) => m !== '');
}

function notComputed(side: SimulationSide): number {
  return Object.values(side.notComputed).reduce((a, b) => a + b, 0);
}

/**
 * C-09 General (Simulation) Run as Wireframes v6.1 draws it (OD-121; plan 05 phase 05i; SRS-15-7,
 * SRS C-09, D-17 to D-21): the Payroll Maker or Checker previews the whole office, one member or a
 * list, as it stands or with a what-if DA rate. Styled apart from C-01 to C-08: an amber banner, a
 * SIMULATION watermark on every output and the marker on every export (SRS l.1789), and no post,
 * report or pay action. Request Simulation Run opens the form, the drawn toolbar filters are its
 * first two fields; up to the interactive limit the result comes back at once with its member rows
 * (shown once, never kept); above it the run goes to the background and its progress is shown here
 * and on C-13. Only the run record is kept; opening one says when the data changed since it ran.
 * The drawn Approve Simulation Request has no rule behind it: SRS-15-7 runs a simulation without an
 * approval (WF-021), so it explains itself and sends nothing.
 */
export function SimulationRun() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const tiles = useSimulationTiles();
  const run = useRunSimulation();
  const [result, setResult] = useState<SimulationDetail | null>(null);
  const [openId, setOpenId] = useState<number | null>(null);
  const [asking, setAsking] = useState(false);
  const [search, setSearch] = useState('');
  const [population, setPopulation] = useState<SimPopulation>('OFFICE');
  const [whatIf, setWhatIf] = useState<SimWhatIf>('AS_IS');
  const opened = useSimulationDetail(openId);
  const mayRun = can(me.data, 'C-09', 'RUN');
  const mayExport = can(me.data, 'C-09', 'EXPORT');
  const checker = can(me.data, 'C-02', 'APPROVE');
  const limits = tiles.data;
  const notConfigured =
    limits && (limits.maxSelection === null || limits.interactiveLimit === null)
      ? t('c09.notConfigured')
      : null;

  async function start(input: SimulationInput) {
    try {
      const done = await run.mutateAsync(input);
      setAsking(false);
      setResult(done);
      setOpenId(done.run.status === 'COMPLETE' ? null : done.run.id);
      toast(t(done.run.status === 'COMPLETE' ? 'c09.done' : 'c09.queued', [done.run.reference]));
    } catch {
      // shown by <Problem error={run.error} />
    }
  }

  const shown = openId !== null && opened.data ? opened.data : result;

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {mayRun ? (
          <Act label={t('c09w.request')} kind="pri" onClick={() => setAsking(true)} />
        ) : (
          <Locked label={t('c09w.request')} kind="pri" why={t('c09w.why.request')} />
        )}
        {checker ? (
          <Explained label={t('c09w.approve')} why={t('c09w.why.noApproval')} />
        ) : (
          <Locked label={t('c09w.approve')} why={t('c09w.why.approve')} />
        )}
      </div>
      {/* The pack's amber note, on every view (SRS l.1789). */}
      <div className="anno on fl-anno-warn" role="note">
        <b>{t('c09.banner')}</b> {t('c09.bannerText')}
      </div>
      <Toolbar
        search={search}
        onSearch={setSearch}
        exportControl={
          mayExport ? (
            <ExportButton href={simulationExportUrl(search)} what={t('screen.C-09')} label={t('wf.export')} />
          ) : (
            <Explained label={t('wf.export')} why={t('c09w.why.export')} />
          )
        }
      >
        <select
          aria-label={t('c09.population')}
          value={population}
          onChange={(e) => setPopulation(e.target.value as SimPopulation)}
        >
          <option value="OFFICE">{t('c09w.filter.population', [limits?.unitLabel ?? ''])}</option>
          <option value="MEMBER">{t('c09w.filter.member')}</option>
          <option value="MEMBER_LIST">{t('c09w.filter.list')}</option>
        </select>
        <select
          aria-label={t('c09.whatIf')}
          value={whatIf}
          onChange={(e) => setWhatIf(e.target.value as SimWhatIf)}
        >
          <option value="DA_RATE">{t('c09w.filter.daRate')}</option>
          {/* OD-29: the what-if needs a scale source; drawn, not offered yet (WF-1107). */}
          <option value="SCALE" disabled title={t('c09w.why.scale')}>
            {t('c09w.filter.scale')}
          </option>
          <option value="AS_IS">{t('c09w.filter.asIs')}</option>
        </select>
      </Toolbar>
      {limits && (
        <Tiles>
          <WfTile
            icon="🧪"
            label={t('c09w.tile.runs')}
            src="SYS"
            big={limits.runsThisCycle}
            sub={t('c09.tile.runsSub', [limits.mineThisCycle, limits.unitCode, limits.period])}
          />
          <WfTile
            icon="👥"
            label={t('c09w.tile.largest')}
            src="ENT"
            big={limits.largestThisCycle}
            sub={t('c09w.tile.largestSub')}
            tone="pur"
          />
          <WfTile
            icon="⏱"
            label={t('c09w.tile.mode')}
            src="SYS"
            big={limits.interactiveLimit === null ? t('c09.tile.notSet') : t('c09w.tile.background')}
            sub={
              limits.interactiveLimit === null
                ? t('c09.tile.modeSub')
                : t('c09w.tile.modeSub', [limits.interactiveLimit])
            }
            tone={limits.interactiveLimit === null ? 'warn' : 'info'}
          />
          <WfTile
            icon="🗄"
            label={t('c09w.tile.retained')}
            src="SYS"
            big={t('c09w.tile.requestOnly')}
            sub={
              <span title={t('c09w.tile.retainedCount', [limits.retained])}>
                {t('c09w.tile.retainedSub')}
              </span>
            }
            tone="bad"
          />
        </Tiles>
      )}
      <Problem error={tiles.error} />
      {shown && <Outcome detail={shown} />}
      <Tbl
        title={t('c09w.recent')}
        cols={[
          { t: t('c09.runId'), s: 'SYS' },
          { t: t('c09.requestedBy'), s: 'SYS' },
          { t: t('c09.population'), s: 'ENT' },
          { t: t('c09w.whatIfChange'), s: 'ENT' },
          { t: t('c09.result'), s: 'CALC' },
          { t: t('c09.retainedCol'), s: 'SYS' },
          { t: t('c08.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={[...simulationKeys.list(), search]}
          fetchPage={simulationPages(search)}
          personal={false}
          empty={<p className="fl-hint">{t('c09.none')}</p>}
        >
          {(rows) =>
            rows.map((r) => (
              <tr key={r.id}>
                <td>
                  <b>{r.reference}</b>
                  <div className="sub2">{t(`c09.mode.${r.mode}`)}</div>
                </td>
                <td>
                  {r.requestedBy}
                  <div className="sub2">{dateTime(r.requestedAt)}</div>
                </td>
                <td>
                  {t(`c09.population.${r.population}`)} · {r.populationCount}
                </td>
                <td>{whatIfText(t, r.whatIf, r.daIda, r.daCda)}</td>
                <td>{t(`c09.status.${r.status}`)}</td>
                <td>{t('c09.retainedValue')}</td>
                <td>
                  <RowActs
                    viewName={t('c09w.viewOf', [r.reference])}
                    onView={() => {
                      setResult(null);
                      setOpenId(r.id);
                    }}
                    editWhy={t('c09w.why.edit')}
                  />
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <Panel
          title={t('c09w.not')}
          items={[
            { icon: '🚫', tone: 'bad', title: t('c09w.not.ebs'), src: 'RULE', text: t('c09w.not.ebs.text') },
            {
              icon: '🚫',
              tone: 'bad',
              title: t('c09w.not.report'),
              src: 'RULE',
              text: t('c09w.not.report.text'),
            },
            {
              icon: '🚫',
              tone: 'bad',
              title: t('c09w.not.member'),
              src: 'RULE',
              text: t('c09w.not.member.text'),
            },
            {
              icon: '🔁',
              tone: 'info',
              title: t('c09w.not.stale'),
              src: 'RULE',
              text: t('c09w.not.stale.text'),
            },
          ]}
        />
        <Panel
          title={t('c09w.settings')}
          items={[
            {
              icon: '🚫',
              tone: 'info',
              title: t('c09w.set.members'),
              src: 'RULE',
              text: t('c09w.set.members.text'),
            },
            {
              icon: '⏱',
              tone: 'info',
              title: t('c09w.set.background'),
              src: 'SYS',
              text: t('c09w.set.background.text'),
            },
            { icon: '🗄', tone: 'gray', title: t('c09w.set.kept'), src: 'SYS', text: t('c09w.set.kept.text') },
            { icon: '👥', tone: 'gray', title: t('c09w.set.who'), src: 'RULE', text: t('c09w.set.who.text') },
          ]}
        />
      </div>
      {asking && (
        <RunForm
          key={`${population}|${whatIf}`}
          initialPopulation={population}
          initialWhatIf={whatIf}
          disabledReason={notConfigured}
          busy={run.isPending}
          error={run.error}
          onRun={(input) => void start(input)}
          onCancel={() => setAsking(false)}
          maxSelection={limits?.maxSelection ?? null}
        />
      )}
    </div>
  );
}

function whatIfText(
  t: (key: string, args?: readonly unknown[]) => string,
  whatIf: SimWhatIf,
  ida: number | null,
  cda: number | null,
): string {
  return whatIf === 'AS_IS' ? t('c09.whatIf.AS_IS') : t('c09.whatIf.daRates', [ida ?? '—', cda ?? '—']);
}

/** SRS C-09 filters: Population and What-if, validated as the server does. */
function RunForm({
  initialPopulation,
  initialWhatIf,
  disabledReason,
  busy,
  error,
  onRun,
  onCancel,
  maxSelection,
}: {
  initialPopulation: SimPopulation;
  initialWhatIf: SimWhatIf;
  disabledReason: string | null;
  busy: boolean;
  error: unknown;
  onRun: (input: SimulationInput) => void;
  onCancel: () => void;
  /** C-09 maximum selection (SIM_MAX_SELECTION); the member list is capped at that many numbers. */
  maxSelection: number | null;
}) {
  const { t } = useI18n();
  const dialog = useDialogFocus<HTMLFormElement>();
  const [population, setPopulation] = useState<SimPopulation>(initialPopulation);
  const [members, setMembers] = useState('');
  const [whatIf, setWhatIf] = useState<SimWhatIf>(initialWhatIf);
  const [ida, setIda] = useState('');
  const [cda, setCda] = useState('');
  const list = membersOf(members);
  const selectionOk =
    population === 'OFFICE' ||
    (list.length > 0 &&
      list.every((m) => EMPLOYEE_NUMBER.test(m)) &&
      (population === 'MEMBER_LIST' || list.length === 1));
  const ratesOk =
    whatIf === 'AS_IS' ||
    ((ida !== '' || cda !== '') && (ida === '' || rateOk(ida)) && (cda === '' || rateOk(cda)));
  const reason = disabledReason ?? (busy ? t('c09.running') : null);
  const ready = reason === null && selectionOk && ratesOk;

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!ready) return;
    onRun({
      population,
      members: population === 'OFFICE' ? [] : list,
      whatIf,
      ...(whatIf === 'DA_RATE' && ida !== '' ? { daIda: ida } : {}),
      ...(whatIf === 'DA_RATE' && cda !== '' ? { daCda: cda } : {}),
    });
  }

  return (
    <div className="fl-modal-backdrop" role="presentation">
      <form
        ref={dialog}
        className="fl-modal"
        role="dialog"
        aria-modal="true"
        aria-label={t('c09.new')}
        onSubmit={submit}
      >
        <h2>{t('c09w.request')}</h2>
        <label className="fl-field">
          {t('c09.population')}
          <select value={population} onChange={(e) => setPopulation(e.target.value as SimPopulation)}>
            <option value="OFFICE">{t('c09.population.OFFICE')}</option>
            <option value="MEMBER">{t('c09.population.MEMBER')}</option>
            <option value="MEMBER_LIST">{t('c09.population.MEMBER_LIST')}</option>
          </select>
        </label>
        {population !== 'OFFICE' && (
          <label className="fl-field">
            {population === 'MEMBER' ? t('c09.member') : t('c09.members')}
            {population === 'MEMBER' ? (
              <input
                maxLength={20}
                value={members}
                onChange={(e) => setMembers(e.target.value)}
                placeholder={t('c09.membersHint')}
              />
            ) : (
              <TextAreaField
                rows={4}
                // an EIS (up to 20 characters) and its separator per member of the maximum selection
                maxLength={Math.max(maxSelection ?? 0, 1) * 21}
                value={members}
                onChange={(e) => setMembers(e.target.value)}
                placeholder={t('c09.membersHint')}
              />
            )}
          </label>
        )}
        <label className="fl-field">
          {t('c09.whatIf')}
          <select value={whatIf} onChange={(e) => setWhatIf(e.target.value as SimWhatIf)}>
            <option value="AS_IS">{t('c09.whatIf.AS_IS')}</option>
            <option value="DA_RATE">{t('c09.whatIf.DA_RATE')}</option>
          </select>
        </label>
        {whatIf === 'DA_RATE' && (
          <>
            <label className="fl-field">
              {t('c09.daIda')}
              <input
                inputMode="decimal"
                maxLength={15}
                value={ida}
                onChange={(e) => setIda(e.target.value.trim())}
              />
            </label>
            <label className="fl-field">
              {t('c09.daCda')}
              <input
                inputMode="decimal"
                maxLength={15}
                value={cda}
                onChange={(e) => setCda(e.target.value.trim())}
              />
            </label>
          </>
        )}
        <p className="fl-hint">{t('c09w.why.scale')}</p>
        <Problem error={error} />
        <div className="fl-actions">
          <button type="button" className="btn" onClick={onCancel}>
            {t('common.cancel')}
          </button>
          <button type="submit" className="btn pri" disabled={!ready} title={reason ?? undefined}>
            {t('c09.run')}
          </button>
        </div>
      </form>
    </div>
  );
}

/** One run's outcome, always under the SIMULATION watermark. */
function Outcome({ detail }: { detail: SimulationDetail }) {
  const { t } = useI18n();
  const { run, asIs, whatIf } = detail;
  const sides: [string, SimulationSide | null][] = [
    [t('c09.asIs'), asIs],
    ...(whatIf ? ([[t('c09.withWhatIf'), whatIf]] as [string, SimulationSide][]) : []),
  ];
  return (
    <div className="sim-output" data-watermark={t('c09.watermark')}>
      <Card title={t('c09.outcome', [run.reference])}>
        <p className="sub2">
          {t(`c09.population.${run.population}`)} · {run.populationCount} ·{' '}
          {whatIfText(t, run.whatIf, run.daIda, run.daCda)} · {t(`c09.mode.${run.mode}`)}
        </p>
        {(run.status === 'QUEUED' || run.status === 'RUNNING') && (
          <p role="status">
            {t('c09.progress', [
              t(`c09.status.${run.status}`),
              run.done,
              run.populationCount,
              run.jobRequestId,
            ])}
          </p>
        )}
        {run.status === 'FAILED' && (
          <p className="fl-error" role="alert">
            {t('c09.failed', [run.failure ?? '—'])}
          </p>
        )}
        {detail.current === false && (
          <p className="fl-error" role="alert">
            {t('c09.outOfDate')}
          </p>
        )}
        {asIs && (
          <div className="wrap">
            <table className="t">
              <thead>
                <tr>
                  <th />
                  <th>
                    {t('c09.computed')}
                    <Src s="CALC" />
                  </th>
                  <th>
                    {t('c09.stopped')}
                    <Src s="CALC" />
                  </th>
                  <th>
                    {t('c09.notComputed')}
                    <Src s="CALC" />
                  </th>
                  <th>
                    {t('c09.gross')}
                    <Src s="CALC" />
                  </th>
                  <th>
                    {t('c09.deductions')}
                    <Src s="CALC" />
                  </th>
                  <th>
                    {t('c09.net')}
                    <Src s="CALC" />
                  </th>
                </tr>
              </thead>
              <tbody>
                {sides.map(([label, side]) =>
                  side ? (
                    <tr key={label}>
                      <td>
                        <b>{label}</b>
                      </td>
                      <td>{side.computed}</td>
                      <td>{side.stopped}</td>
                      <td
                        title={Object.entries(side.notComputed)
                          .map(([k, v]) => `${k}: ${v}`)
                          .join('\n')}
                      >
                        {notComputed(side)}
                      </td>
                      <td>{amount(side.gross)}</td>
                      <td>{amount(side.deductions)}</td>
                      <td>{amount(side.net)}</td>
                    </tr>
                  ) : null,
                )}
              </tbody>
            </table>
          </div>
        )}
        {detail.components.length > 0 && (
          <div className="wrap">
            <table className="t" aria-label={t('c09.components')}>
              <thead>
                <tr>
                  <th>
                    {t('c09.component')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('c09.asIs')}
                    <Src s="CALC" />
                  </th>
                  {whatIf && (
                    <th>
                      {t('c09.withWhatIf')}
                      <Src s="CALC" />
                    </th>
                  )}
                </tr>
              </thead>
              <tbody>
                {detail.components.map((c) => (
                  <tr key={c.payCode}>
                    <td>
                      {c.payCode} {c.name}
                    </td>
                    <td>{amount(c.asIs)}</td>
                    {whatIf && <td>{amount(c.whatIf)}</td>}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {detail.members.length > 0 && (
          <div className="wrap">
            <table className="t" aria-label={t('c09.memberRows')}>
              <thead>
                <tr>
                  <th>
                    {t('c09.member')}
                    <Src s="HRMS" />
                  </th>
                  <th>
                    {t('c09.result')}
                    <Src s="CALC" />
                  </th>
                  <th>
                    {t('c09.gross')}
                    <Src s="CALC" />
                  </th>
                  <th>
                    {t('c09.net')}
                    <Src s="CALC" />
                  </th>
                  {whatIf && (
                    <th>
                      {t('c09.whatIfNet')}
                      <Src s="CALC" />
                    </th>
                  )}
                </tr>
              </thead>
              <tbody>
                {detail.members.map((m) => (
                  <tr key={m.eis}>
                    <td>
                      <b>{m.eis}</b> <span className="sub2">{m.name}</span>
                    </td>
                    <td>
                      {t(`c09.memberStatus.${m.status}`)}
                      {m.reason && <div className="sub2">{m.reason}</div>}
                    </td>
                    <td>{amount(m.gross)}</td>
                    <td>{amount(m.net)}</td>
                    {whatIf && <td>{amount(m.whatIfNet)}</td>}
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="fl-hint">{t('c09.rowsNotKept')}</p>
          </div>
        )}
      </Card>
    </div>
  );
}
