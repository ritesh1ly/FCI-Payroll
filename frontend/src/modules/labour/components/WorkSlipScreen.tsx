'use client';

import { useRef, useState } from 'react';
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
  Toolbar,
  ViewDialog,
} from '@/shared/ui/wireframe';
import type { CaptureFilter, GangOption, OvertimeRow, RegisterRow } from '../api/captureApi';
import { registerStatus } from '../business/captureRules';
import { previousMonth } from '../business/labourRules';
import {
  captureKeys,
  overtimePage,
  registerExportUrl,
  registerPage,
  useEngine,
  useSlipOverview,
} from '../orchestration/captureQueries';
import { AnnaStrip, GangFilter, TypeSelect } from './CaptureParts';
import { type SlipControl, WorkSlipEntry } from './WorkSlips';

/**
 * I-02 Work-Slip Capture as Wireframes v6.1 draws it, one view per payroll type (OD-121; SRS-2-4,
 * SRS-2-15, SRS-2-17, SRS-2-27, SRS-2-34, SRS-2-42, SRS-2-45; FRS V0.5 §8.4.3, §8.11.2, §8.11.3,
 * §9.8.3, §10.8.3). Overtime per person, the DPS booking-slip register (the DPS view; the Depot
 * Manager approves in W-01, SRS-2-34), the Work-Slip Register of the month with the duplicate test
 * of the type, the controls of the type, the import queue and the entry actions. The capture itself
 * (gang, month, slips, amendment, cancellation, sending to the Depot Manager, the batch) stays in
 * the closed section the drawn buttons open. Overtime entry waits for its rule settings (PENDING
 * LBR-20c-1); Reprocess and Office Upload for an interface (PENDING API-23-4, API-20-3).
 */
export function WorkSlips() {
  const { t, locale } = useI18n();
  const me = useMe();
  const toast = useToast();
  const today = businessToday();
  const { type, chosen, choose } = useEngine();
  const control = useRef<SlipControl | null>(null);
  const [gang, setGang] = useState<number | null>(null);
  const [day, setDay] = useState('');
  const [text, setText] = useState('');
  const [open, setOpen] = useState(false);
  const [viewing, setViewing] = useState<RegisterRow | null>(null);
  const month = previousMonth(today);
  const filter: CaptureFilter = { payrollType: type, month, gang };
  const list: CaptureFilter = { ...filter, date: day || undefined, text };
  const overview = useSlipOverview(filter);
  const data = overview.data;
  const dps = type === 'DPS_LABOUR';
  const nwnp = type === 'NWNP_LABOUR';
  const ldh = can(me.data, 'I-02', 'CREATE');
  const checker = can(me.data, 'I-02', 'APPROVE');
  const a12 = t('i01w.why.a12');
  const a12Short = t('i02w.why.a12');
  const typeName = t(`i01w.type.${type}`);

  /** The gang of a row or of the filter, as the closed section takes it. */
  function gangOf(id: number | null): GangOption | null {
    return (data?.gangs ?? []).find((g) => g.id === id) ?? null;
  }

  function startSlip() {
    setOpen(true);
    control.current?.open(gangOf(gang), month, true);
  }

  /** A drawn entry button: it acts on the open entry; with none open it says what to do first. */
  function onEntry(act: (c: SlipControl) => void) {
    const c = control.current;
    if (c?.hasEntry()) act(c);
    else toast(t('i02w.why.noEntry'));
  }

  const addRow = ldh ? (
    <Act
      label={t('i02w.addRow')}
      kind="sm"
      onClick={() => {
        if (control.current?.hasEntry()) control.current.addRow();
        else startSlip();
      }}
    />
  ) : (
    <Locked label={t('i02w.addRow')} why={a12Short} kind="sm" />
  );
  const reprocess = ldh ? (
    <Explained label={t('i01w.reprocess')} why={t('i01w.why.reprocess')} kind="sm" />
  ) : (
    <Locked label={t('i01w.reprocess')} why={a12Short} kind="sm" />
  );

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
          <Act label={t('i02w.new')} kind="pri" onClick={startSlip} />
        ) : (
          <Locked label={t('i02w.new')} why={a12} />
        )}
      </div>
      <AnnaStrip anna={data?.anna} />
      <Problem error={overview.error} />
      {dps && (
        <Tbl
          title={t('i02w.booking')}
          acts={
            ldh ? (
              <Act label={t('i02w.newBooking')} kind="sm" onClick={startSlip} />
            ) : (
              <Locked label={t('i02w.newBooking')} why={t('i01w.why.capture')} kind="sm" />
            )
          }
          cols={[
            { t: t('i02w.col.booking'), s: 'SYS' },
            { t: t('i02w.col.gang'), s: 'ENT' },
            { t: t('i02w.col.date'), s: 'ENT' },
            { t: t('i02w.col.operationM27'), s: 'ENT' },
            { t: t('i02w.col.bags'), s: 'ENT', num: true },
            { t: t('i02w.col.enteredBy'), s: 'SYS' },
            { t: t('i02w.col.approvedBy'), s: 'SYS' },
            { t: t('i01w.col.status'), s: 'SYS' },
          ]}
          after={
            <div className="bd fl-pad-top-0">
              <div className="oq">{t('i02w.bookingNote', [data?.pendingBooking ?? 0])}</div>
            </div>
          }
        >
          <ServerPagination
            queryKey={captureKeys.of('booking', list)}
            fetchPage={registerPage(list)}
            empty={t('i02w.noneMonth')}
          >
            {(page: RegisterRow[]) =>
              page.map((r) => {
                const [tone, key, args] = registerStatus(r, true);
                return (
                  <tr key={`${r.slipId}-${r.rowOrder}`}>
                    <td>{r.number}</td>
                    <td>{r.gangCode}</td>
                    <td>{date(r.date)}</td>
                    <td>{r.operation ? `${r.operationCode} — ${r.operation}` : '—'}</td>
                    <td className="num">{r.bags ?? '—'}</td>
                    <td>{r.enteredBy ?? '—'}</td>
                    <td>{r.decidedBy ?? '—'}</td>
                    <td>
                      <span className={`pill ${tone}`}>{t(key, args)}</span>
                    </td>
                  </tr>
                );
              })
            }
          </ServerPagination>
        </Tbl>
      )}
      <div className="g2">
        <Tbl
          title={t('i02w.ot')}
          acts={
            ldh ? (
              <Explained label={t('i02w.addRow')} why={t('i02.why.overtime')} kind="sm" />
            ) : (
              <Locked label={t('i02w.addRow')} why={a12Short} kind="sm" />
            )
          }
          cols={[
            { t: t('i01w.col.labour'), s: 'SYS' },
            { t: t('i01w.col.gang'), s: 'SYS' },
            { t: t('i02w.col.operationM27'), s: 'ENT' },
            { t: t('i02w.col.workingAs'), s: 'ENT' },
            { t: t('i02w.col.otStart'), s: 'ENT' },
            { t: t('i02w.col.otEnd'), s: 'ENT' },
            { t: t('i02w.col.otHours'), s: 'CALC', num: true },
            { t: t('i02w.col.otBags'), s: 'ENT', num: true },
            { t: t('i01w.col.check'), s: 'SYS' },
          ]}
        >
          <ServerPagination
            queryKey={captureKeys.of('overtime', filter)}
            fetchPage={overtimePage(filter)}
            empty={t('i02w.noOvertime')}
          >
            {(page: OvertimeRow[]) =>
              page.map((o) => (
                <tr key={o.key}>
                  <td>
                    {o.labourCode} {o.name}
                  </td>
                  <td>{o.pool ? t('i02w.pool', [o.gangCode]) : o.gangCode}</td>
                  <td>
                    {o.operationCode} — {o.operation}
                  </td>
                  <td>{o.workingAs ? t(`m09.role.${o.workingAs}`) : '—'}</td>
                  <td>{o.start ?? '—'}</td>
                  <td>{o.end ?? '—'}</td>
                  <td className="num">{o.hours ?? '—'}</td>
                  <td className="num">{o.ancillary ? t('i02w.noBagField') : (o.bags ?? '—')}</td>
                  <td>
                    <OvertimeCheck row={o} />
                  </td>
                </tr>
              ))
            }
          </ServerPagination>
        </Tbl>
        <Panel
          title={t('i02w.rules')}
          items={[
            { icon: '🧰', tone: 'info', title: t('i02w.r.ops'), src: 'RULE', text: t('i02w.r.opsSub') },
            { icon: '⏱', tone: 'info', title: t('i02w.r.ot'), src: 'ENT', text: t('i02w.r.otSub') },
            { icon: '🧺', tone: 'gray', title: t('i02w.r.pool'), src: 'RULE', text: t('i02w.r.poolSub') },
            { icon: '🔗', tone: 'gray', title: t('i02w.r.attach'), src: 'RULE', text: t('i02w.r.attachSub') },
            { icon: '🔄', tone: 'warn', title: t('i02w.r.mandal'), src: 'RULE', text: t('i02w.r.mandalSub') },
            { icon: '✍', tone: 'warn', title: t('i02w.r.dps'), src: 'SYS', text: t('i02w.r.dpsSub') },
          ]}
        />
      </div>
      <Toolbar
        search={text}
        onSearch={setText}
        exportControl={
          can(me.data, 'I-02', 'EXPORT') ? (
            <ExportButton href={registerExportUrl(list)} what={t('screen.I-02')} label={t('wf.export')} />
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
            setDay('');
          }}
        />
        <GangFilter gangs={data?.gangs} value={gang} onChange={setGang} />
        <select aria-label={t('i02w.allDates')} value={day} onChange={(e) => setDay(e.target.value)}>
          <option value="">{t('i02w.allDates')}</option>
          {(data?.dates ?? []).map((d) => (
            <option key={d} value={d}>
              {date(d)}
            </option>
          ))}
        </select>
      </Toolbar>
      <Tbl
        title={t('i02w.register', [typeName])}
        label={t('i02w.registerName')}
        acts={
          <>
            {addRow}
            {reprocess}
          </>
        }
        cols={[
          { t: t('i02w.col.slip'), s: 'SYS' },
          { t: t('i02w.col.gang'), s: 'ENT' },
          { t: t('i02w.col.date'), s: 'ENT' },
          { t: t('i02w.col.bagType'), s: 'ENT' },
          { t: t('i02w.col.operation'), s: 'ENT' },
          { t: t('i02w.col.noBags'), s: 'ENT', num: true },
          { t: t('i02w.col.start'), s: 'ENT' },
          { t: t('i02w.col.end'), s: 'ENT' },
          { t: nwnp ? t('i02w.col.continue') : t('i02w.col.norm'), s: 'ENT' },
          { t: t('i02w.col.labours'), s: 'ENT', num: true },
          { t: t('i01w.col.status'), s: 'SYS' },
          { t: t('i20.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={captureKeys.of('register', list)}
          fetchPage={registerPage(list)}
          empty={t('i02w.noneMonth')}
        >
          {(page: RegisterRow[]) =>
            page.map((r) => {
              const [tone, key, args] = registerStatus(r, dps);
              return (
                <tr key={`${r.slipId}-${r.rowOrder}`}>
                  <td>
                    <b>{r.number}</b>
                  </td>
                  <td>{r.gangCode}</td>
                  <td>{date(r.date)}</td>
                  <td>{r.bagType ?? '—'}</td>
                  <td>{r.operation ?? '—'}</td>
                  <td className="num">{r.bags ?? '—'}</td>
                  <td>{r.start ?? '—'}</td>
                  <td>{r.end ?? '—'}</td>
                  <td>
                    {nwnp
                      ? t(r.continueWork ? 'i02w.continueYes' : 'i02w.continueNo')
                      : r.normType
                        ? t(`i02w.norm.${r.normType}`)
                        : '—'}
                  </td>
                  <td className="num">{r.labours}</td>
                  <td>
                    <span className={`pill ${tone}`}>{t(key, args)}</span>
                  </td>
                  <td>
                    <RowActs
                      viewName={`${t('common.view')} ${r.number}`}
                      editName={`${t('common.edit')} ${r.number}`}
                      onView={() => setViewing(r)}
                      onEdit={
                        ldh
                          ? () => {
                              setOpen(true);
                              control.current?.open(gangOf(r.gangId), r.date.slice(0, 7), false);
                            }
                          : undefined
                      }
                      editWhy={t('i01w.why.record')}
                      onApprove={
                        checker ? () => window.location.assign(withBase('/payroll/?s=W-01')) : undefined
                      }
                    />
                  </td>
                </tr>
              );
            })
          }
        </ServerPagination>
      </Tbl>
      <details
        className="fl-mt"
        data-testid="i02-capture"
        open={open}
        onToggle={(e) => setOpen((e.currentTarget as HTMLDetailsElement).open)}
      >
        <summary>{t('i02w.capture')}</summary>
        <WorkSlipEntry control={control} engine={chosen} />
      </details>
      <div className="g2 fl-mt">
        {nwnp ? (
          <KvCard
            title={t('i02w.nwnp')}
            rows={[
              { k: t('i02w.n.bag'), v: t('i02w.n.bagV'), s: 'ENT' },
              { k: t('i02w.n.continue'), v: t('i02w.n.continueV'), s: 'ENT' },
              { k: t('i02w.n.pbnw'), v: t('i02w.n.pbnwV'), s: 'ENT' },
              { k: t('i02w.n.window'), v: t('i02w.n.windowV'), s: 'RULE' },
              { k: t('i02w.n.rate'), v: t('i02w.n.rateV'), s: 'CALC' },
              { k: t('i02w.n.eligible'), v: t('i02w.n.eligibleV'), s: 'RULE' },
              { k: t('i02w.n.addRow'), v: t('i02w.n.addRowV'), s: 'ENT' },
              { k: t('i02w.n.delete'), v: t('i02w.n.deleteV'), s: 'RULE' },
            ]}
          />
        ) : (
          <KvCard
            title={t('i02w.controls', [typeName])}
            rows={[
              { k: t('i02w.c.op'), v: t('i02w.c.opV'), s: 'ENT' },
              { k: t('i02w.c.time'), v: t('i02w.c.timeV'), s: 'SYS' },
              { k: t('i02w.c.month'), v: t('i02w.c.monthV'), s: 'SYS' },
              {
                k: t('i02w.c.continuous'),
                v: (
                  <>
                    <span className="pill p-ok">{t('i02w.c.continuousPill')}</span> {t('i02w.c.continuousV')}
                  </>
                ),
                s: 'RULE',
              },
              { k: t('i02w.c.stack'), v: t('i02w.c.stackV'), s: 'RULE' },
              { k: t('i02w.c.sardar'), v: t('i02w.c.sardarV'), s: 'RULE' },
              { k: t('i02w.c.dup'), v: t(dps ? 'i02w.c.dupDps' : 'i02w.c.dupDept'), s: 'RULE' },
              {
                k: t('i02w.c.transfer'),
                v: t(dps ? 'i02w.c.transferDps' : 'i02w.c.transferDept'),
                s: 'RULE',
              },
            ]}
          />
        )}
        <Panel
          title={t('i02w.queue')}
          act={reprocess}
          items={[
            {
              icon: '📄',
              tone: 'ok',
              title: t('i02w.q.saved'),
              src: 'ENT',
              text: t('i02w.q.savedSub'),
              right: <b>{(data?.saved ?? 0).toLocaleString('en-IN')}</b>,
            },
            {
              icon: '⛔',
              tone: 'bad',
              title: t('i02w.q.rejected'),
              src: 'INTF',
              text: t('i02w.q.rejectedSub'),
              right: <b>{(data?.rejected ?? 0).toLocaleString('en-IN')}</b>,
            },
            {
              icon: '🔁',
              tone: 'gray',
              title: t('i02w.q.never'),
              src: 'INTF',
              text: t('i02w.q.neverSub'),
              right: <span className="pill p-ok">{t('i02w.q.rule')}</span>,
            },
          ]}
        />
      </div>
      <div className="g2 fl-mt">
        <Panel
          title={t('i02w.capControls')}
          items={[
            { icon: '📌', tone: 'ok', title: t('i02w.k.gang'), src: 'SYS' },
            { icon: '⏭', tone: 'ok', title: t('i02w.k.next'), src: 'SYS', text: t('i02w.k.nextSub') },
            { icon: '📋', tone: 'info', title: t('i02w.k.ops'), src: 'RULE' },
            { icon: '🔒', tone: 'info', title: t('i02w.k.locks'), src: 'RULE' },
            { icon: '🔀', tone: 'warn', title: t('i02w.k.transfer'), src: 'RULE' },
          ]}
        />
        <div className="card">
          <div className="hd">
            <h3>{t('i02w.entry')}</h3>
          </div>
          <div className="bd">
            {ldh ? (
              <>
                <Act label={t('i02w.save')} kind="pri" onClick={() => onEntry((c) => c.save())} />{' '}
                <Act label={t('i02w.saveNext')} onClick={() => onEntry((c) => c.saveNext())} />{' '}
                <Act label={t('i02w.reset')} onClick={() => onEntry((c) => c.resetRow())} />
              </>
            ) : (
              <>
                <Locked label={t('i02w.save')} why={a12Short} kind="pri" />{' '}
                <Locked label={t('i02w.saveNext')} why={a12Short} />{' '}
                <Locked label={t('i02w.reset')} why={a12Short} />
              </>
            )}
          </div>
        </div>
      </div>
      {viewing && (
        <ViewDialog
          screen={t('screen.I-02')}
          onClose={() => setViewing(null)}
          rows={[
            { k: t('i02w.col.slip'), v: viewing.number, s: 'SYS' },
            { k: t('i02w.col.gang'), v: viewing.gangCode, s: 'ENT' },
            { k: t('i02w.col.date'), v: date(viewing.date), s: 'ENT' },
            { k: t('i02w.col.operation'), v: viewing.operation ?? '—', s: 'ENT' },
            { k: t('i02w.col.noBags'), v: viewing.bags ?? '—', s: 'ENT' },
            { k: t('i02w.col.labours'), v: viewing.labours, s: 'ENT' },
            { k: t('i02w.col.enteredBy'), v: viewing.enteredBy ?? '—', s: 'SYS' },
            { k: t('i02w.col.approvedBy'), v: viewing.decidedBy ?? '—', s: 'SYS' },
            {
              k: t('i01w.col.status'),
              v: (() => {
                const [, key, args] = registerStatus(viewing, dps);
                return t(key, args);
              })(),
              s: 'SYS',
            },
            { k: t('i02w.reason'), v: viewing.reason ?? '—', s: 'SYS' },
            { k: t('i02w.month'), v: periodLabel(viewing.date.slice(0, 7), locale), s: 'SYS' },
          ]}
        />
      )}
    </div>
  );
}

/** The Check column of the overtime table. */
function OvertimeCheck({ row }: { row: OvertimeRow }) {
  const { t } = useI18n();
  if (row.pbnwReason) return <>{t('i02w.chk.pbnw', [t(`i02.pbnw.${row.pbnwReason}`)])}</>;
  if (row.ancillary) return <span className="pill p-ok">{t('i02w.chk.time')}</span>;
  if (row.minimumMet === 'N') return <span className="pill p-bad">{t('i02w.chk.below')}</span>;
  return row.minimumMet === 'Y' ? <span className="pill p-ok">{t('i02w.chk.eligible')}</span> : <>—</>;
}
