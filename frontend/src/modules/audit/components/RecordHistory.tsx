'use client';

import { type ReactNode, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import {
  Card,
  date,
  dateTime,
  ExportButton,
  Problem,
  ServerPagination,
  Src,
  type PageResult,
  useDialogFocus,
} from '@/shared/ui';
import { Tbl } from '@/shared/ui/wireframe';
import type { HistoryRow, LegacyRow, RecordRef, RecordVersion } from '../api/auditApi';
import { fieldRows, outcomeClass, personLabel, snapshotPairs } from '../business/recordHistory';
import {
  asOfExportUrl,
  auditKeys,
  fetchHistoryPage,
  fetchLegacyPage,
  historyExportUrl,
  useAsOf,
  useRecordHead,
} from '../orchestration/auditQueries';

/** BR-AUDIT-002: the approved versions of a versioned record, newest first. */
export function VersionsTable({ versions }: { versions: RecordVersion[] }) {
  const { t } = useI18n();
  return (
    <div className="wrap">
      <table className="t">
        <caption className="sub2">{t('x04.versions')}</caption>
        <thead>
          <tr>
            <th>
              {t('x04.version')}
              <Src s="SYS" />
            </th>
            <th>
              {t('x04.from')}
              <Src s="SYS" />
            </th>
            <th>
              {t('x04.to')}
              <Src s="SYS" />
            </th>
            <th>
              {t('x04.values')}
              <Src s="SYS" />
            </th>
            <th>
              {t('x04.authorisation')}
              <Src s="SYS" />
            </th>
          </tr>
        </thead>
        <tbody>
          {versions.map((v) => (
            <tr key={v.versionNo}>
              <td>{v.versionNo}</td>
              <td>{date(v.effectiveFrom)}</td>
              <td>{v.effectiveTo ? date(v.effectiveTo) : t('x04.open')}</td>
              <td>
                {fieldRows(v.values).map(([k, value]) => (
                  <div key={k} className="sub2">
                    {k}: <b>{value}</b>
                  </div>
                ))}
              </td>
              <td>{v.approvalId ? `APR-${v.approvalId}` : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** SRS-20-3 / 20-6: the record as it stood on {@code day} - exactly one version, or not in force. */
export function AsOfPanel({ record, day }: { record: RecordRef; day: string }) {
  const { t } = useI18n();
  const asOf = useAsOf(record, day);
  if (!day) return null;
  if (asOf.isError) return <Problem error={asOf.error} />;
  if (!asOf.data) return <p className="fl-hint">{t('common.loading')}</p>;
  const view = asOf.data;
  if (view.status === 'NOT_IN_FORCE') {
    return (
      <p className="fl-banner" role="status">
        {t('x04.asOf.notInForce', [date(view.date)])}
      </p>
    );
  }
  return (
    <div className="fl-banner" role="status">
      <b>
        {view.versioned
          ? t('x04.asOf.found', [date(view.date), view.versionNo ?? ''])
          : t('x04.asOf.rebuilt', [date(view.date)])}
      </b>
      {fieldRows(view.values).map(([k, value]) => (
        <div key={k} className="sub2">
          {k}: <b>{value}</b>
        </div>
      ))}
    </div>
  );
}

/** SRS-G-2 / SRS-20-29: the native rows of a record with their audit fields, oldest first. */
export function HistoryTable({ record }: { record: RecordRef }) {
  const { t } = useI18n();
  return (
    <ServerPagination<PageResult<HistoryRow>>
      queryKey={[...auditKeys.history, record.entity, record.key]}
      fetchPage={fetchHistoryPage(record)}
      empty={<p className="fl-hint">{t('x04.none')}</p>}
    >
      {(rows) => (
        <div className="wrap">
          <table className="t">
            <thead>
              <tr>
                <th>
                  {t('x04.when')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('x04.txn')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('x04.field')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('x04.old')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('x04.new')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('x04.outcome')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('x04.effective')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('common.reason')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('x04.maker')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('x04.checker')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('x04.authorisation')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('x04.ip')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('x04.origin')}
                  <Src s="SYS" />
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.transactionId}>
                  <td>{dateTime(r.changedAt)}</td>
                  <td>{r.transactionId}</td>
                  <td>
                    {r.field}
                    <div className="sub2">{r.objectKey}</div>
                  </td>
                  <td>{r.oldValue ?? '—'}</td>
                  <td>{r.newValue ?? '—'}</td>
                  <td>
                    <span className={outcomeClass(r.outcome)}>{t(`x04.outcome.${r.outcome}`)}</span>
                  </td>
                  <td>{date(r.effectiveDate)}</td>
                  <td>{r.reason}</td>
                  <td>{personLabel(r.maker)}</td>
                  <td>{personLabel(r.checker)}</td>
                  <td>
                    {r.approvalId ? `APR-${r.approvalId}` : '—'}
                    {r.approvalStatus && <div className="sub2">{t(`status.${r.approvalStatus}`)}</div>}
                  </td>
                  <td>{r.sourceIp ?? '—'}</td>
                  <td>{t('x04.origin.NATIVE')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </ServerPagination>
  );
}

/**
 * SRS-20-23: the drawn Legacy Audit table: the rows of the record chosen on Point-in-Time Reconstruction, beside the
 * native trail and never merged with it; every enquiry is logged by the server (SRS-20-24). With no record chosen the
 * table is empty and says so: nothing is read, so no unscoped list of legacy rows exists.
 */
export function LegacyPanel({ record, acts }: { record: RecordRef | null; acts?: ReactNode }) {
  const { t } = useI18n();
  return (
    <Tbl
      title={t('x04.legacy.title')}
      acts={acts}
      cols={[
        { t: t('x04.origin'), s: 'SYS' },
        { t: t('x04.legacy.table'), s: 'SYS' },
        { t: t('x04.legacy.key'), s: 'SYS' },
        { t: t('x04.legacy.object'), s: 'SYS' },
        { t: t('x04.legacy.change'), s: 'SYS' },
        { t: t('x04.legacy.user'), s: 'SYS' },
        { t: t('x04.legacy.access'), s: 'SYS' },
      ]}
    >
      {record ? (
        <ServerPagination<PageResult<LegacyRow>>
          queryKey={[...auditKeys.legacy, record.entity, record.key]}
          fetchPage={fetchLegacyPage(record)}
          empty={<p className="fl-hint">{t('x04.legacy.none')}</p>}
        >
          {(rows) => (
            <>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>
                    <span className="pill p-gray">{t('x04.origin.LEGACY')}</span>
                  </td>
                  <td>{r.sourceObject}</td>
                  <td>{r.sourceKey}</td>
                  <td>{r.fieldName ?? r.changedFields ?? '—'}</td>
                  <td>
                    {r.grain === 'ROW_IMAGE' ? (
                      <>
                        {snapshotPairs(r.rowImage).map((pair) => (
                          <div key={pair} className="sub2">
                            {pair}
                          </div>
                        ))}
                        {r.changedFields && (
                          <div className="sub2">
                            {t('x04.legacy.changed')}: <b>{r.changedFields}</b>
                          </div>
                        )}
                      </>
                    ) : (
                      <>
                        {r.oldValue ?? '—'} → <b>{r.newValue ?? '—'}</b>
                      </>
                    )}
                    {r.reason && <div className="sub2">{r.reason}</div>}
                  </td>
                  <td>
                    {r.changedBy ?? '—'}
                    <div className="sub2">{dateTime(r.changedAt)}</div>
                  </td>
                  <td>
                    <span className="pill p-pur">{t('x04.legacy.readOnly')}</span>
                  </td>
                </tr>
              ))}
            </>
          )}
        </ServerPagination>
      ) : (
        <tr>
          <td colSpan={7} className="sub2">
            {t('x04.legacy.pick')}
          </td>
        </tr>
      )}
    </Tbl>
  );
}

/**
 * The native side of one record: its versions, its state as of a date and every change, with the
 * Change History Report and the Point-in-Time Extract (SRS-20-29), offered to a reader holding
 * EXPORT on A-05 or on the record's own screen.
 */
export function NativeHistory({
  record,
  day,
  withAsOf = true,
}: {
  record: RecordRef;
  day: string;
  /** False where the screen draws the record as of the date itself (X-04 Point-in-Time Reconstruction). */
  withAsOf?: boolean;
}) {
  const { t } = useI18n();
  const me = useMe();
  const head = useRecordHead(record);
  if (head.isError) return <Problem error={head.error} />;
  if (!head.data) return <p className="fl-hint">{t('common.loading')}</p>;
  const mayExport = can(me.data, 'A-05', 'EXPORT') || can(me.data, head.data.screen, 'EXPORT');
  return (
    <>
      {mayExport && (
        <div className="toolbar">
          <ExportButton className="btn sm" href={historyExportUrl(record)} what={t('x04.report')} />
          {day && (
            <ExportButton className="btn sm" href={asOfExportUrl(record, day)} what={t('x04.extract')} />
          )}
        </div>
      )}
      {withAsOf && <AsOfPanel record={record} day={day} />}
      {head.data.versioned && <VersionsTable versions={head.data.versions} />}
      <HistoryTable record={record} />
    </>
  );
}

/**
 * SRS-20-29: the History button of a master screen. It opens the change history of the record on
 * screen - previous value, new value, effective date, changed by, approved by, reason - with the
 * record as of a chosen date. Read only (SRS-20-19).
 */
export function HistoryButton({
  entity,
  recordKey,
  label,
  text,
  className = 'btn sm',
}: {
  entity: string;
  recordKey: string;
  label?: string;
  /** The button's text where a screen draws its own ("🕘 History" over a list). */
  text?: string;
  className?: string;
}) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [day, setDay] = useState('');
  const dialog = useDialogFocus<HTMLDivElement>(open);
  const record = { entity, key: recordKey };
  return (
    <>
      <button
        type="button"
        className={className}
        aria-haspopup="dialog"
        aria-label={t('history.for', [label ?? recordKey])}
        onClick={() => setOpen(true)}
      >
        {text ?? t('history.button')}
      </button>
      {open && (
        <div
          className="fl-modal-backdrop"
          role="presentation"
          onKeyDown={(e) => {
            if (e.key === 'Escape') setOpen(false);
          }}
        >
          <div
            ref={dialog}
            className="fl-modal fl-modal-wide"
            role="dialog"
            aria-modal="true"
            aria-labelledby="history-title"
          >
            <h2 id="history-title">{t('history.title', [label ?? recordKey])}</h2>
            <p>{t('x04.readOnly')}</p>
            <div className="toolbar">
              <label className="fl-filter">
                {t('x04.asOf')} <input type="date" value={day} onChange={(e) => setDay(e.target.value)} />
              </label>
              <span className="sp" />
              <button type="button" className="btn" data-autofocus onClick={() => setOpen(false)}>
                {t('history.close')}
              </button>
            </div>
            <Card title={t('x04.native')}>
              <NativeHistory record={record} day={day} />
            </Card>
          </div>
        </div>
      )}
    </>
  );
}
