'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, periodLabel, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import { ExportButton, Problem, ServerPagination, date, useToast, withBase } from '@/shared/ui';
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
} from '@/shared/ui/wireframe';
import type { CaptureFilter, DayRow, GangSummaryRow } from '../api/captureApi';
import { dayMarkable, daySource, fourthCode, gangTone, marksByGang } from '../business/captureRules';
import { previousMonth } from '../business/labourRules';
import {
  captureKeys,
  daysPage,
  gangSummariesExportUrl,
  gangSummariesPage,
  leavePage,
  sourcesPage,
  useAttendanceOverview,
  useEngine,
  useRefreshCapture,
} from '../orchestration/captureQueries';
import { useSaveDay } from '../orchestration/labourQueries';
import { AttendanceMonthGrid } from './AttendanceCapture';
import { AnnaStrip, GangFilter, TypeSelect } from './CaptureParts';

/**
 * I-01 Labour Attendance Capture as Wireframes v6.1 draws it, one view per payroll type (OD-121;
 * SRS-2-2, SRS-2-27, SRS-2-29, SRS-2-35 to SRS-2-40, SRS-2-48, SRS-2-57; FRS V0.5 §8.11.1, §8.11.9,
 * Annexure A.7). The day's blank entry over the gangs of the type, the code set of the type, the
 * month's tiles and Gang Attendance Summary, the controls, labour leave and the HQ Attendance Source
 * master (read-only). The month grid (submit the batch, the day of a gang, the Gang Strength Check)
 * stays in the closed Day-wise grid section. The month shown is the last completed one, the month
 * a batch can be submitted for. Labour are not in HRMS, so no column comes from it, whatever the
 * drawn badge says (WIREFRAME_GAPS). Punches and Anna Darpan rows read what Payroll holds (PENDING
 * API-23-4, API-23-5); Reprocess, Office Upload, Correct Attendance and the leave actions say why
 * they wait (PENDING LBR-20b-1, LBR-20b-3, API-20-3).
 */
export function AttendanceCapture() {
  const { t, locale } = useI18n();
  const me = useMe();
  const toast = useToast();
  const refresh = useRefreshCapture();
  const today = businessToday();
  const { type, chosen, choose } = useEngine();
  const [gang, setGang] = useState<number | null>(null);
  const [text, setText] = useState('');
  const [marks, setMarks] = useState<Record<number, string>>({});
  const [rows, setRows] = useState<DayRow[]>([]);
  const [viewing, setViewing] = useState<GangSummaryRow | null>(null);
  const [gridOpen, setGridOpen] = useState(false);
  const [gridGang, setGridGang] = useState<{ id: number | null; nonce: number }>({ id: null, nonce: 0 });
  const [error, setError] = useState<unknown>(null);
  const saveDay = useSaveDay();
  const month = previousMonth(today);
  const filter: CaptureFilter = { payrollType: type, month, gang };
  const list: CaptureFilter = { ...filter, text };
  const dayFilter: CaptureFilter = { payrollType: type, date: today, gang };
  const overview = useAttendanceOverview(filter);
  const data = overview.data;
  const ldh = can(me.data, 'I-01', 'CREATE');
  const checker = can(me.data, 'I-01', 'APPROVE');
  const captureWhy = t('i01w.why.capture');
  const a12 = t('i01w.why.a12');
  const typeName = t(`i01w.type.${type}`);
  const pendingCount = (data?.records ?? 0) - (data?.withSlip ?? 0);
  const col4 = fourthCode(type);

  function openGrid(id: number | null) {
    setGridGang((g) => ({ id, nonce: g.nonce + 1 }));
    setGridOpen(true);
  }

  async function saveMarks() {
    setError(null);
    try {
      for (const [gangId, gangMarks] of marksByGang(rows, marks)) {
        await saveDay.mutateAsync({ gangId, date: today, marks: gangMarks });
      }
      setMarks({});
      toast(t('i01.saved', [today]));
      await refresh();
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {ldh ? (
          <Explained label={t('i01w.upload')} why={t('i01w.why.upload')} />
        ) : (
          <Locked label={t('i01w.upload')} why={a12} />
        )}
        {ldh ? (
          <Explained label={t('i01w.correct')} why={t('i01.why.correction')} kind="pri" />
        ) : (
          <Locked label={t('i01w.correct')} why={captureWhy} />
        )}
      </div>
      <AnnaStrip anna={data?.anna} />
      <Problem error={error ?? overview.error} />
      <div className="g2">
        <Tbl
          title={t('i01w.daily')}
          label={t('i01w.daily')}
          acts={
            ldh ? (
              <Act
                label={t('i01w.save')}
                kind="sm"
                onClick={() => {
                  if (Object.keys(marks).length === 0) toast(t('i01w.why.nothing'));
                  else void saveMarks();
                }}
              />
            ) : (
              <Locked label={t('i01w.save')} why={captureWhy} kind="sm" />
            )
          }
          cols={[
            { t: t('i01w.col.labour'), s: 'SYS' },
            { t: t('i01w.col.gang'), s: 'SYS' },
            { t: t('i01w.col.statusFor', [dayLabel(today, locale)]), s: 'ENT' },
            { t: t('i01w.col.source'), s: 'SYS' },
            { t: t('i01w.col.check'), s: 'SYS' },
          ]}
        >
          <ServerPagination
            queryKey={captureKeys.of('days', dayFilter)}
            fetchPage={daysPage(dayFilter)}
            empty={t('i01w.noneToday')}
            onRows={setRows}
          >
            {(page: DayRow[]) =>
              page.map((r) => {
                const [source, args] = daySource(r);
                return (
                  <tr key={`${r.gangId}-${r.employeeId}`}>
                    <td>
                      {r.labourCode} {r.name}
                    </td>
                    <td>{r.gangCode}</td>
                    <td>
                      {dayMarkable(r) && ldh ? (
                        <select
                          aria-label={t('i01.codeOf', [r.labourCode])}
                          value={marks[r.employeeId] ?? ''}
                          onChange={(e) => setMarks({ ...marks, [r.employeeId]: e.target.value })}
                        >
                          <option value="">{t('i01w.select')}</option>
                          {(data?.codes ?? []).map((c) => (
                            <option key={c.code} value={c.code}>
                              {c.code}
                            </option>
                          ))}
                        </select>
                      ) : r.otherCode !== null || r.pastRetirement ? (
                        <span className="pill p-bad">{t('i01w.blocked')}</span>
                      ) : (
                        (r.code ?? '—')
                      )}
                    </td>
                    <td>{t(source, args)}</td>
                    <td>
                      <DayCheck row={r} />
                    </td>
                  </tr>
                );
              })
            }
          </ServerPagination>
        </Tbl>
        <Panel
          title={t('i01w.rules')}
          items={[
            { icon: '⬜', tone: 'info', title: t('i01w.r.blank'), src: 'RULE', text: t('i01w.r.blankSub') },
            { icon: '🖐', tone: 'info', title: t('i01w.r.bio'), src: 'INTF', text: t('i01w.r.bioSub') },
            { icon: '1', tone: 'warn', title: t('i01w.r.one'), src: 'RULE', text: t('i01w.r.oneSub') },
            { icon: '↩', tone: 'info', title: t('i01w.r.back'), src: 'ENT', text: t('i01w.r.backSub') },
            { icon: '🏷', tone: 'gray', title: t('i01w.r.hrms'), src: 'RULE', text: t('i01w.r.hrmsSub') },
            {
              icon: '🔔',
              tone: 'gray',
              title: t('i01w.r.strength'),
              src: 'SYS',
              text: t('i01w.r.strengthSub'),
            },
          ]}
        />
      </div>
      <Toolbar
        search={text}
        onSearch={setText}
        exportControl={
          can(me.data, 'I-01', 'EXPORT') ? (
            <ExportButton
              href={gangSummariesExportUrl(list)}
              what={t('screen.I-01')}
              label={t('wf.export')}
            />
          ) : (
            <Explained label={t('wf.export')} why={t('i01w.why.export')} />
          )
        }
      >
        <TypeSelect
          value={type}
          onChange={(next) => {
            choose(next);
            setGang(null);
            setMarks({});
          }}
        />
        <GangFilter gangs={data?.gangs} value={gang} onChange={setGang} />
      </Toolbar>
      <Tiles>
        <WfTile
          icon="🕒"
          label={t('i01w.tile.records')}
          src="ENT"
          big={(data?.records ?? 0).toLocaleString('en-IN')}
          sub={t('i01w.tile.recordsSub', [periodLabel(month, locale)])}
        />
        <WfTile
          icon="📄"
          tone="warn"
          label={t('i01w.tile.slips')}
          src="ENT"
          big={`${(data?.withSlip ?? 0).toLocaleString('en-IN')} / ${(data?.records ?? 0).toLocaleString('en-IN')}`}
          sub={pendingCount === 0 ? t('i01w.tile.complete') : t('i01w.tile.blocked', [pendingCount])}
        />
        <WfTile
          icon="📉"
          tone="pur"
          label={t('i01w.tile.lwp')}
          src="CALC"
          big={(data?.lwpDays ?? 0).toLocaleString('en-IN')}
          sub={t('i01w.tile.lwpSub')}
        />
        <WfTile
          icon="⛔"
          tone="bad"
          bad
          label={t('i01w.tile.exceptions')}
          src="SYS"
          big={(data?.exceptions ?? 0).toLocaleString('en-IN')}
          sub={t('i01w.tile.exceptionsSub')}
        />
      </Tiles>
      <Tbl
        title={t('i01w.summary', [typeName, periodLabel(month, locale)])}
        label={t('i01w.summaryName')}
        acts={
          <>
            <Act label={t('i01w.dayGrid')} kind="sm" onClick={() => setGridOpen(!gridOpen)} />
            {ldh ? (
              <Explained label={t('i01w.reprocess')} why={t('i01w.why.reprocess')} kind="sm" />
            ) : (
              <Locked label={t('i01w.reprocess')} why={a12} kind="sm" />
            )}
          </>
        }
        cols={[
          { t: t('i01w.col.gangHead'), s: 'HRMS' },
          { t: t('i01w.col.labourHead'), s: 'HRMS', num: true },
          { t: t('i01w.col.calendar'), s: 'RULE', num: true },
          { t: 'W', s: 'INTF', num: true },
          { t: 'A', s: 'INTF', num: true },
          { t: 'H', s: 'INTF', num: true },
          { t: col4, s: 'INTF', num: true },
          { t: 'M', s: 'INTF', num: true },
          { t: t('i01w.col.nph'), s: 'INTF', num: true },
          { t: t('i01w.col.present'), s: 'CALC', num: true },
          { t: t('i01w.col.status'), s: 'SYS' },
          { t: t('i20.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={captureKeys.of('gang-summaries', list)}
          fetchPage={gangSummariesPage(list)}
          empty={t('i01w.noGangs')}
        >
          {(page: GangSummaryRow[]) =>
            page.map((g) => (
              <tr key={g.gangId}>
                <td>
                  <b>{g.gangCode}</b>
                </td>
                <td className="num">{g.labour}</td>
                <td className="num">{g.calendarDays}</td>
                {['W', 'A', 'H', col4, 'M', 'B'].map((c) => (
                  <td key={c} className="num">
                    {g.counts?.[c] ?? 0}
                  </td>
                ))}
                <td className="num">
                  {g.overCalendar ? (
                    <span className="pill p-bad">{t('i01w.overCalendar')}</span>
                  ) : (
                    g.presentDays
                  )}
                </td>
                <td>
                  <span className={`pill ${gangTone(g.status)}`}>{t(`i01w.status.${g.status}`)}</span>
                </td>
                <td>
                  <RowActs
                    viewName={`${t('common.view')} ${g.gangCode}`}
                    editName={`${t('common.edit')} ${g.gangCode}`}
                    onView={() => setViewing(g)}
                    onEdit={ldh ? () => openGrid(g.gangId) : undefined}
                    editWhy={t('i01w.why.record')}
                    onApprove={
                      checker ? () => window.location.assign(withBase('/payroll/?s=W-01')) : undefined
                    }
                  />
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <details
        className="fl-mt"
        data-testid="i01-grid"
        open={gridOpen}
        onToggle={(e) => setGridOpen((e.currentTarget as HTMLDetailsElement).open)}
      >
        <summary>{t('i01w.dayGrid')}</summary>
        <AttendanceMonthGrid key={gridGang.nonce} engine={chosen} initialGang={gridGang.id} />
      </details>
      <div className="g2 fl-mt">
        <Panel
          title={t('i01w.controls')}
          items={[
            {
              icon: '🔒',
              tone: 'bad',
              title: t('i01w.c.slip'),
              src: 'RULE',
              text: t('i01w.c.slipSub'),
              right: <span className="pill p-ok">{t('i01w.on')}</span>,
            },
            {
              icon: '🚫',
              tone: 'bad',
              title: t('i01w.c.retire'),
              src: 'RULE',
              text: t('i01w.c.retireSub'),
              right: <b>{data?.pastRetirement ?? 0}</b>,
            },
            {
              icon: '📅',
              tone: 'warn',
              title: t('i01w.c.days'),
              src: 'CALC',
              text: t('i01w.c.daysSub'),
              right: <b>{data?.overCalendar ?? 0}</b>,
            },
            {
              icon: '📝',
              tone: 'info',
              title: t('i01w.c.leave'),
              src: 'RULE',
              text: undefined,
              right: <span className="pill p-ok">{t('i01w.on')}</span>,
            },
            {
              icon: '🔁',
              tone: 'gray',
              title: t('i01w.c.reprocess'),
              src: 'INTF',
              text: t('i01w.c.reprocessSub'),
              right: <span className="pill p-ok">{t('i01w.on')}</span>,
            },
          ]}
        />
        <KvCard
          title={t('i01w.where')}
          rows={[
            { k: t('i01w.w.code'), v: t('i01w.w.codeV'), s: 'ENT' },
            { k: t('i01w.w.leave'), v: t('i01w.w.leaveV'), s: 'ENT' },
            { k: t('i01w.w.calendar'), v: t('i01w.w.calendarV'), s: 'RULE' },
            { k: t('i01w.w.present'), v: t('i01w.w.presentV', [ENGINE_NAME[type]]), s: 'CALC' },
            { k: t('i01w.w.lwp'), v: t('i01w.w.lwpV'), s: 'CALC' },
            { k: t('i01w.w.correction'), v: t('i01w.w.correctionV'), s: 'INTF' },
            {
              k: t('i01w.w.wage'),
              v: <span className="pill p-info">{t('i01w.w.wageV')}</span>,
              s: 'RULE',
            },
          ]}
        />
      </div>
      <div className="g2 fl-mt">
        <Tbl
          title={t('i01w.leave')}
          acts={
            ldh ? (
              <>
                <Explained label={t('i01w.l.enter')} why={t('i01.why.leave')} kind="sm" />
                <Explained label={t('i01w.l.regularise')} why={t('i01.why.leave')} kind="sm" />
                <Explained label={t('i01w.l.convert')} why={t('i01.why.leave')} kind="sm" />
              </>
            ) : (
              <>
                <Locked label={t('i01w.l.enter')} why={captureWhy} kind="sm" />
                <Locked label={t('i01w.l.regularise')} why={captureWhy} kind="sm" />
                <Locked label={t('i01w.l.convert')} why={captureWhy} kind="sm" />
              </>
            )
          }
          cols={[
            { t: t('i01w.col.labour'), s: 'SYS' },
            { t: t('i01w.l.action'), s: 'ENT' },
            { t: t('i01w.l.dates'), s: 'ENT' },
            { t: t('i01w.l.codes'), s: 'ENT' },
            { t: t('i01w.l.effect'), s: 'CALC' },
            { t: t('i01w.col.status'), s: 'SYS' },
          ]}
          after={
            <div className="bd fl-pad-top-0">
              <div className="oq">{t('i01w.l.note')}</div>
            </div>
          }
        >
          <ServerPagination
            queryKey={captureKeys.of('leave', { payrollType: type, text })}
            fetchPage={leavePage({ payrollType: type, text })}
            empty={t('i01w.l.none')}
          >
            {(page) =>
              page.map((l) => (
                <tr key={l.id}>
                  <td>
                    {l.labourCode} {l.name}
                  </td>
                  <td>{t(`i01w.l.origin.${l.origin}`)}</td>
                  <td>
                    {date(l.from)} – {date(l.to)}
                  </td>
                  <td>
                    {l.origin === 'REGULARISATION' ? 'A' : (l.fromCode ?? '—')} → {l.toCode}
                  </td>
                  <td>
                    {l.backDated
                      ? t('i01w.l.effectBack', [periodLabel(l.raisedOn.slice(0, 7), locale)])
                      : t(`i01w.l.effect.${l.origin}`)}
                  </td>
                  <td>
                    <span className={`pill ${l.status === 'APPROVED' ? 'p-ok' : 'p-warn'}`}>
                      {t(`i01w.l.status.${l.status}`)}
                    </span>
                  </td>
                </tr>
              ))
            }
          </ServerPagination>
        </Tbl>
        <Tbl
          title={t('i01w.src')}
          cols={[
            { t: t('i01w.s.depot'), s: 'SYS' },
            { t: t('i01w.s.source'), s: 'RULE' },
            { t: t('i01w.col.status'), s: 'RULE' },
            { t: t('i01w.s.from'), s: 'RULE' },
            { t: t('i01w.s.enrolled'), s: 'SYS', num: true },
          ]}
          after={
            <div className="bd fl-pad-top-0">
              <div className="oq">{t('i01w.s.note')}</div>
            </div>
          }
        >
          <ServerPagination
            queryKey={captureKeys.of('sources')}
            fetchPage={sourcesPage}
            empty={t('i01.sourceNotConfigured')}
          >
            {(page) =>
              page.map((s) => (
                <tr key={s.depot}>
                  <td>{s.depot}</td>
                  <td>
                    {t(`i01.mode.${s.mode}`)}
                    {s.annaDarpan && ` · ${t('i01.annaDarpan')}`}
                  </td>
                  <td>
                    <span className={`pill ${s.status === 'LIVE' ? 'p-ok' : 'p-warn'}`}>
                      {t(`i01.sourceStatus.${s.status}`)}
                    </span>
                  </td>
                  <td>{date(s.effectiveFrom)}</td>
                  <td className="num">{s.enrolled}</td>
                </tr>
              ))
            }
          </ServerPagination>
        </Tbl>
      </div>
      {viewing && (
        <ViewDialog
          screen={t('screen.I-01')}
          onClose={() => setViewing(null)}
          rows={[
            { k: t('i01w.col.gangHead'), v: `${viewing.gangCode} - ${viewing.gangName}`, s: 'SYS' },
            { k: t('m02.depot'), v: viewing.depot, s: 'SYS' },
            { k: t('i01w.col.labourHead'), v: viewing.labour, s: 'SYS' },
            { k: t('i01w.col.calendar'), v: viewing.calendarDays, s: 'RULE' },
            { k: t('i01w.col.present'), v: viewing.presentDays, s: 'CALC' },
            { k: t('i01w.col.status'), v: t(`i01w.status.${viewing.status}`), s: 'SYS' },
          ]}
        />
      )}
    </div>
  );
}

const ENGINE_NAME = { DEPT_LABOUR: 'DEPT', DPS_LABOUR: 'DPS', NWNP_LABOUR: 'NWNP' } as const;

/** "30-Jul": the day of the entry's column head. */
function dayLabel(day: string, locale: 'en' | 'hi'): string {
  const short = periodLabel(day.slice(0, 7), locale).split(' ')[0] ?? '';
  const name = short.length > 1 ? short.charAt(0) + short.slice(1).toLowerCase() : short;
  return `${day.slice(8)}-${name}`;
}

/** The Check column of the day's row. */
function DayCheck({ row }: { row: DayRow }) {
  const { t } = useI18n();
  if (row.pastRetirement) return <>{t('i01w.chk.retired')}</>;
  if (row.otherCode !== null)
    return <>{t('i01w.chk.other', [row.otherCode, row.otherGang ?? '', row.otherDepot ?? ''])}</>;
  if (row.exception) return <span className="pill p-warn">{t('i01w.chk.exception')}</span>;
  if (row.captureMode === 'ANNA_DARPAN')
    return (
      <span className={`pill ${row.depotConfirmed ? 'p-ok' : 'p-warn'}`}>
        {t(row.depotConfirmed ? 'i01w.chk.confirmed' : 'i01w.chk.received')}
      </span>
    );
  if (row.captureMode === 'BIOMETRIC') return <span className="pill p-ok">{t('i01w.chk.punches')}</span>;
  return <>—</>;
}
