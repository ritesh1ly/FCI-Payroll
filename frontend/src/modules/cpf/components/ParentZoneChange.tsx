'use client';

import { useCallback, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { amount, date, dateTime, Problem, ServerPagination } from '@/shared/ui';
import { RegisterSection } from '@/shared/ui/masterParts';
import { Act, Explained, KvCard, RowActs, Tbl, ViewDialog } from '@/shared/ui/wireframe';
import type { ZoneChangeDetail, ZoneChangeRow } from '../api/cpfScreensApi';
import { cpfScreenKeys, cpfScreenPages, goTo, useZoneChange } from '../orchestration/cpfScreenQueries';

const DASH = '—';

/** The drawn card follows the applied change when the page has one; else the first row. */
function firstChoice(rows: ZoneChangeRow[]): ZoneChangeRow | null {
  return rows.find((r) => r.status === 'APPLIED') ?? rows[0] ?? null;
}

function carried(t: (k: string, a?: unknown[]) => string, d: ZoneChangeDetail | undefined): string {
  if (!d || d.carriedEmployee === null) return t('i13w.carried.none');
  return t('i13w.carried.value', [
    amount(d.carriedEmployee),
    amount(d.carriedEmployer ?? 0),
    amount(d.carriedVpf ?? 0),
    d.interestTo ? date(d.interestTo) : DASH,
  ]);
}

/**
 * I-13 CPF Parent-Zone Change / Zone Surrender (from HRMS) as Wireframes v6.1 draws it (OD-121; SRS-6-49, SRS-6-52,
 * BR-CPF-020, BR-CPF-038, BR-CPF-040, FAP-22): a view of what HRMS raised and approved. Nothing is keyed here: the
 * change is applied on the Payroll Checker's approval of its I-21 task, without OTP, and a change rejected in HRMS
 * changes nothing. No HRMS API carries these changes yet (PENDING API-07-3); applying the balance waits for the interest
 * to the date (07k-blocked, Q-072), so the carried balance reads as not computed.
 */
export function ParentZoneChange() {
  const { t } = useI18n();
  const me = useMe();
  const [selected, setSelected] = useState<ZoneChangeRow | null>(null);
  const [viewing, setViewing] = useState<ZoneChangeRow | null>(null);
  const [iocpf, setIocpf] = useState(false);
  const detail = useZoneChange(selected?.reference ?? '');
  const d = detail.data;
  const onRows = useCallback((rows: ZoneChangeRow[]) => {
    setSelected((prev) => rows.find((r) => r.reference === prev?.reference) ?? firstChoice(rows));
  }, []);
  const decided = (r: ZoneChangeRow) => t(`i13w.st.${r.status}`);
  return (
    <>
      <div className="toolbar">
        <span className="sp" />
        {can(me.data, 'I-21', 'VIEW') ? (
          <Act label={t('i13w.act.tasks')} onClick={() => goTo('I-21')} />
        ) : (
          <Explained label={t('i13w.act.tasks')} why={t('i13w.why.tasks')} />
        )}
        <Explained label={t('i13w.act.surrender')} why={t('i13w.why.surrender')} />
        <Act label={t('i13w.act.iocpf')} onClick={() => setIocpf(true)} />
      </div>
      <Problem error={detail.error} />
      <Tbl
        title={t('i13w.table')}
        cols={[
          { t: t('i13w.col.reference'), s: 'HRMS' },
          { t: t('i13w.col.member'), s: 'HRMS' },
          { t: t('i13w.col.reason'), s: 'HRMS' },
          { t: t('i13w.col.zones'), s: 'HRMS' },
          { t: t('i13w.col.order'), s: 'HRMS' },
          { t: t('i13w.col.oldId'), s: 'SYS' },
          { t: t('i13w.col.newId'), s: 'HRMS' },
          { t: t('i13w.col.effective'), s: 'HRMS' },
          { t: t('i13w.col.status'), s: 'SYS' },
          { t: t('i13w.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={cpfScreenKeys.zoneChanges}
          fetchPage={cpfScreenPages.zoneChanges}
          empty={t('i13w.none')}
          onRows={onRows}
        >
          {(rows) =>
            rows.map((r) => (
              <tr
                key={r.reference}
                aria-selected={selected?.reference === r.reference}
                className={selected?.reference === r.reference ? 'fl-row-selected' : undefined}
                onClick={() => setSelected(r)}
              >
                <td>
                  <b>{r.reference}</b>
                </td>
                <td>
                  {r.eis} {r.name}
                </td>
                <td>{t(`i13w.reason.${r.reason}`)}</td>
                <td>
                  {r.fromZone} → {r.toZone}
                </td>
                <td>{r.order ?? DASH}</td>
                <td>{r.oldId}</td>
                <td>{r.newId ?? DASH}</td>
                <td>{r.effective ? date(r.effective) : DASH}</td>
                <td>{decided(r)}</td>
                <td>
                  <RowActs
                    onView={() => setViewing(r)}
                    editWhy={t('i13w.why.edit')}
                    viewName={t('i13w.viewName', [r.reference])}
                    editName={t('i13w.editName', [r.reference])}
                  />
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <KvCard
          title={t('i13w.onApproval', [selected?.reference ?? DASH, selected?.name ?? DASH])}
          rows={[
            {
              k: t('i13w.k.ids'),
              v: selected
                ? `${selected.oldId} → ${selected.newId ?? DASH} · ${selected.effective ? date(selected.effective) : DASH} · ${selected.toZone}`
                : DASH,
              s: 'HRMS',
            },
            {
              k: t('i13w.k.old'),
              v:
                selected?.status === 'APPLIED' && selected.effective
                  ? t('i13w.k.oldClosed', [date(selected.effective)])
                  : t('i13w.k.oldOpen'),
              s: 'SYS',
            },
            { k: t('i13w.k.balance'), v: carried(t, d), s: 'CALC' },
            { k: t('i13w.k.surrender'), v: t(`i13w.recon.${d?.reconStatus ?? 'PENDING'}`), s: 'SYS' },
            {
              k: t('i13w.k.iocpf'),
              v: d?.iocpfStatus
                ? t('i13w.k.iocpfState', [
                    t(`i13w.apps.${d.iocpfStatus}`),
                    d.iocpfAcknowledged ? dateTime(d.iocpfAcknowledged) : DASH,
                  ])
                : t('i13w.k.iocpfNone'),
              s: 'INTF',
            },
            {
              k: t('i13w.k.ssp'),
              v: d?.sspSyncedAt
                ? t('i13w.k.sspDone', [dateTime(d.sspSyncedAt), d.ecrFrom ?? DASH])
                : t('i13w.k.sspNone'),
              s: 'INTF',
            },
          ]}
        />
        <Tbl
          title={t('i13w.history', [selected?.name ?? DASH])}
          cols={[
            { t: t('i13w.h.id'), s: 'HRMS' },
            { t: t('i13w.h.zone'), s: 'HRMS' },
            { t: t('i13w.h.from'), s: 'SYS' },
            { t: t('i13w.h.to'), s: 'SYS' },
            { t: t('i13w.h.reason'), s: 'SYS' },
          ]}
          after={
            <div className="bd">
              <div className="oq">{t('i13w.note')}</div>
            </div>
          }
        >
          {(d?.ids ?? []).map((m) => (
            <tr key={`${m.id}-${m.from}`}>
              <td>{m.id}</td>
              <td>{m.zone ?? DASH}</td>
              <td>{date(m.from)}</td>
              <td>{m.to ? date(m.to) : DASH}</td>
              <td>{t(`i13w.id.${m.status}`)}</td>
            </tr>
          ))}
          {d && d.ids.length === 0 && (
            <tr>
              <td colSpan={5} className="sub2">
                {t('i13w.h.none')}
              </td>
            </tr>
          )}
        </Tbl>
      </div>
      <RegisterSection id="i13-iocpf" label={t('i13w.iocpf')} open={iocpf} onToggle={setIocpf}>
        {iocpf && (
          <Tbl
            title={t('i13w.iocpf')}
            label={t('i13w.iocpf')}
            cols={[
              { t: t('i13w.i.number'), s: 'SYS' },
              { t: t('i13w.i.member'), s: 'HRMS' },
              { t: t('i13w.i.reason'), s: 'SYS' },
              { t: t('i13w.i.total'), s: 'CALC', num: true },
              { t: t('i13w.i.status'), s: 'INTF' },
              { t: t('i13w.i.sent'), s: 'SYS' },
              { t: t('i13w.i.retries'), s: 'SYS', num: true },
            ]}
          >
            <ServerPagination
              queryKey={cpfScreenKeys.iocpfPending}
              fetchPage={cpfScreenPages.iocpfPending}
              empty={t('i13w.i.none')}
            >
              {(rows) =>
                rows.map((r) => (
                  <tr key={r.number}>
                    <td>
                      <b>{r.number}</b>
                    </td>
                    <td>
                      {r.eis} {r.name}
                    </td>
                    <td>{t(`i13w.ir.${r.reason}`)}</td>
                    <td className="num">{amount(r.total)}</td>
                    <td>{t(`i13w.apps.${r.appsStatus}`)}</td>
                    <td>{r.sentAt ? dateTime(r.sentAt) : DASH}</td>
                    <td className="num">{r.retries}</td>
                  </tr>
                ))
              }
            </ServerPagination>
          </Tbl>
        )}
      </RegisterSection>
      {viewing && (
        <ViewDialog
          screen={t('screen.I-13')}
          onClose={() => setViewing(null)}
          actions={
            viewing.taskNumber && can(me.data, 'I-21', 'VIEW') ? (
              <Act label={t('i13w.act.tasks')} onClick={() => goTo('I-21')} />
            ) : undefined
          }
          rows={[
            { k: t('i13w.col.reference'), v: viewing.reference, s: 'HRMS' },
            { k: t('i13w.col.member'), v: `${viewing.eis} ${viewing.name}`, s: 'HRMS' },
            { k: t('i13w.col.reason'), v: t(`i13w.reason.${viewing.reason}`), s: 'HRMS' },
            { k: t('i13w.col.zones'), v: `${viewing.fromZone} → ${viewing.toZone}`, s: 'HRMS' },
            { k: t('i13w.col.order'), v: viewing.order ?? DASH, s: 'HRMS' },
            { k: t('i13w.col.oldId'), v: viewing.oldId, s: 'SYS' },
            { k: t('i13w.col.newId'), v: viewing.newId ?? DASH, s: 'HRMS' },
            { k: t('i13w.col.effective'), v: viewing.effective ? date(viewing.effective) : DASH, s: 'HRMS' },
            { k: t('i13w.col.status'), v: decided(viewing), s: 'SYS' },
            { k: t('i13w.k.task'), v: viewing.taskNumber ?? DASH, s: 'SYS' },
          ]}
        />
      )}
    </>
  );
}
