'use client';

import { type FormEvent, Fragment, type RefObject, useEffect, useRef, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { RegisterSection } from '@/shared/ui/masterParts';
import { can, useMe } from '@/shared/session';
import {
  date,
  dateTime,
  ExportButton,
  formText,
  type PageResult,
  Problem,
  ServerPagination,
} from '@/shared/ui';
import {
  Act,
  Explained,
  KvCard,
  type KvRow,
  Panel,
  Tbl,
  Tiles,
  Toolbar,
  ViewDialog,
  WfTile,
} from '@/shared/ui/wireframe';
import type { AuditEvent, RecordRef } from '../api/auditApi';
import { defaultAuditFilter, isoDay, rangeValid } from '../business/auditFilter';
import { fieldRows, personLabel, validRecordKey } from '../business/recordHistory';
import {
  auditExportUrl,
  auditKeys,
  fetchAuditPage,
  legacyExportUrl,
  orderDocumentUrl,
  useAsOf,
  useAuditSummary,
  useRecordTypes,
} from '../orchestration/auditQueries';
import { LegacyPanel, NativeHistory } from './RecordHistory';

/** The twelve rows of the drawn Per-Module Audit Scenario Matrix (FRS V0.5 §6.5.2). */
const MATRIX = [
  'cpf',
  'loans',
  'nps',
  'penalty',
  'tax',
  'mis',
  'intf',
  'migration',
  'ff',
  'labour',
  'leave',
  'core',
];
/** The four fixed lines of the drawn Coverage & retention panel. */
const COVERAGE: { key: string; icon: string }[] = [
  { key: 'master', icon: '🗂' },
  { key: 'rules', icon: '⚙️' },
  { key: 'slips', icon: '📄' },
  { key: 'locked', icon: '🔒' },
];

/** Waits this long after the last key before the list search reads the server. */
const SEARCH_DELAY_MS = 300;

/**
 * X-04 Audit Trail &amp; Change History (built on A-05) as Wireframes v6.1 draws it (OD-121): Point-in-Time View
 * and Export Trail over the screen; the mandatory-fields table (BR-AUDIT-003, SRS-G-2); the object and user
 * filters from the M-16 lists AUDIT_OBJECT_GROUP and AUDIT_USER_GROUP (OD-123); the four tiles; the Audit Trail
 * with Maker, Checker, Key, Effective and View; the Per-Module Scenario Matrix; Point-in-Time Reconstruction
 * (SRS-20-3, SRS-20-6) and Coverage &amp; retention (SRS-20-5); the Session Action Log; the Legacy Audit table,
 * beside the native rows and never merged (SRS-20-22, SRS-20-23). Read only for every role (SRS-20-19), and every
 * figure and row stays within the reader's data scope (SRS-20-8). Dates, the free-text filters and the record's
 * own history open from Filter and from closed sections, so nothing the drawing omits is lost: the undrawn enquiry
 * that picks a record (SRS l.1243) sits in its own closed section, and Point-in-Time View opens it. Export is drawn
 * enabled for every role: a role without A-05 EXPORT is told why when it presses it, and nothing is sent (OD-135,
 * X04-07, WF-1900).
 */
export function AuditTrail() {
  const { t } = useI18n();
  const me = useMe();
  const mayExport = can(me.data, 'A-05', 'EXPORT');
  const [filter, setFilter] = useState(defaultAuditFilter);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [rangeError, setRangeError] = useState(false);
  const [search, setSearch] = useState('');
  const [total, setTotal] = useState(0);
  const [viewed, setViewed] = useState<AuditEvent | null>(null);
  const [record, setRecord] = useState<RecordRef | null>(null);
  const [day, setDay] = useState('');
  const [enquiryOpen, setEnquiryOpen] = useState(false);
  const [jump, setJump] = useState(0);
  const enquiry = useRef<HTMLDivElement>(null);
  const summary = useAuditSummary();

  // Point-in-Time View opens the enquiry first: a control inside a closed section takes no focus.
  useEffect(() => {
    if (jump === 0) return;
    enquiry.current?.scrollIntoView?.();
    enquiry.current?.querySelector('select')?.focus();
  }, [jump]);

  // The list search reads the server once typing stops, not on every key.
  useEffect(() => {
    const timer = setTimeout(
      () => setFilter((f) => (f.q === search ? f : { ...f, q: search })),
      SEARCH_DELAY_MS,
    );
    return () => clearTimeout(timer);
  }, [search]);

  function applyDates(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const next = {
      ...filter,
      from: formText(form, 'from', filter.from),
      to: formText(form, 'to', filter.to),
      entity: formText(form, 'entity'),
      actor: formText(form, 'actor'),
      field: formText(form, 'field'),
    };
    // From and To are India days, both included: one day is a range, To before From is not.
    setRangeError(!rangeValid(next));
    if (rangeValid(next)) setFilter(next);
  }

  const exportRefused = (label: string, kind?: 'pri') => (
    <Explained label={label} why={t('x04w.why.export')} kind={kind} />
  );
  const exportTrail = mayExport ? (
    <ExportButton
      className="btn pri"
      href={auditExportUrl(filter)}
      what={t('screen.A-05')}
      label={t('x04w.exportTrail')}
    />
  ) : (
    exportRefused(t('x04w.exportTrail'), 'pri')
  );
  const exportToolbar = mayExport ? (
    <ExportButton href={auditExportUrl(filter)} what={t('screen.A-05')} label={t('wf.export')} />
  ) : (
    exportRefused(t('wf.export'))
  );
  const data = summary.data;
  const retentionPill = data?.retentionSet ? (
    <span className="pill p-ok">{t('x04w.months', [data.retentionMonths ?? ''])}</span>
  ) : (
    <span className="pill p-info">{t('x04w.coverage.retention.pill')}</span>
  );
  const filtered = !!filter.objectGroup || !!filter.userGroup;

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <Act
          label={t('x04w.pointInTime')}
          onClick={() => {
            setEnquiryOpen(true);
            setJump((n) => n + 1);
          }}
        />
        {exportTrail}
      </div>

      <MandatoryFields filter={filter} />

      <Toolbar search={search} onSearch={setSearch} exportControl={exportToolbar}>
        <select
          aria-label={t('x04w.allObjects')}
          value={filter.objectGroup ?? ''}
          onChange={(e) => setFilter({ ...filter, objectGroup: e.target.value })}
        >
          <option value="">{t('x04w.allObjects')}</option>
          <ReferenceOptions list="AUDIT_OBJECT_GROUP" />
        </select>
        <select
          aria-label={t('x04w.allUsers')}
          value={filter.userGroup ?? ''}
          onChange={(e) => setFilter({ ...filter, userGroup: e.target.value })}
        >
          <option value="">{t('x04w.allUsers')}</option>
          <ReferenceOptions list="AUDIT_USER_GROUP" />
        </select>
      </Toolbar>

      <Tiles>
        <WfTile
          icon="🔐"
          label={t('x04w.tile.rows')}
          src="SYS"
          big={data?.rowsThisCycle?.toLocaleString('en-IN') ?? '—'}
          sub={t('x04w.tile.rowsSub')}
        />
        <WfTile
          icon="🗂"
          label={t('x04w.tile.objects')}
          src="SYS"
          big={t('x04w.tile.objectsBig')}
          sub={t('x04w.tile.objectsSub')}
          tone="pur"
        />
        <WfTile
          icon="📦"
          label={t('x04w.tile.retention')}
          src="RULE"
          big={
            data?.retentionSet ? t('x04w.months', [data.retentionMonths ?? '']) : t('x04w.tile.retentionBig')
          }
          sub={t('x04w.tile.retentionSub')}
        />
        <WfTile
          icon="🗄"
          label={t('x04w.tile.legacy')}
          src="SYS"
          big={data?.legacyRows?.toLocaleString('en-IN') ?? t('x04w.tile.legacyBig')}
          sub={t('x04w.tile.legacySub')}
        />
      </Tiles>
      <Problem error={summary.error} />

      {filtered && (
        <div className="oq">
          <b>{t('x04w.filtered')}</b>{' '}
          {[
            filter.objectGroup && t('x04w.filteredObject', [filter.objectGroup]),
            filter.userGroup && t('x04w.filteredUser', [filter.userGroup]),
          ]
            .filter(Boolean)
            .join(' · ')}{' '}
          <button
            type="button"
            className="btn sm"
            onClick={() => setFilter({ ...filter, objectGroup: '', userGroup: '' })}
          >
            {t('x04w.clearFilters')}
          </button>
        </div>
      )}

      {rangeError && (
        <p className="fl-error" role="alert">
          {t('a05.rangeReversed')}
        </p>
      )}
      <Tbl
        title={t('x04w.trail')}
        acts={<Act label={t('x04w.filter')} kind="sm" onClick={() => setFiltersOpen(!filtersOpen)} />}
        cols={[
          { t: t('x04w.col.when'), s: 'SYS' },
          { t: t('x04w.col.maker'), s: 'SYS' },
          { t: t('x04w.col.checker'), s: 'SYS' },
          { t: t('x04w.col.object'), s: 'SYS' },
          { t: t('x04w.col.key'), s: 'SYS' },
          { t: t('x04w.col.field'), s: 'SYS' },
          { t: t('x04w.col.before'), s: 'SYS' },
          { t: t('x04w.col.after'), s: 'SYS' },
          { t: t('x04w.col.effective'), s: 'ENT' },
          { t: t('x04w.col.reason'), s: 'ENT' },
          { t: t('x04w.col.actions'), s: 'SYS' },
        ]}
        after={
          filtersOpen && (
            <form className="toolbar fl-filters" onSubmit={applyDates} aria-label={t('x04w.filter')}>
              <p className="fl-hint">
                {t('a05.title', [total])} · {t('x04.readOnly')}
              </p>
              <label className="fl-filter">
                {t('a05.from')} <input name="from" type="date" defaultValue={filter.from} />
              </label>
              <label className="fl-filter">
                {t('a05.to')} <input name="to" type="date" defaultValue={filter.to} />
              </label>
              {/* SRS l.1226: record type, user and field match without regard to case and as part of the
                  stored name; the known record types are offered as suggestions. */}
              <EntityInput value={filter.entity} />
              <input
                name="actor"
                aria-label={t('a05.actor')}
                placeholder={t('a05.actor')}
                maxLength={150}
                defaultValue={filter.actor}
              />
              <input
                name="field"
                aria-label={t('a05.field')}
                placeholder={t('a05.field')}
                maxLength={100}
                defaultValue={filter.field}
              />
              <button type="submit" className="btn">
                {t('common.search')}
              </button>
            </form>
          )
        }
      >
        <ServerPagination<PageResult<AuditEvent>>
          queryKey={[...auditKeys.search, filter]}
          fetchPage={fetchAuditPage(filter)}
          onTotal={setTotal}
          empty={
            <>
              <p className="fl-hint">{t('a05.none')}</p>
              {/* UI_BMAD_g1 #99: say which dates were searched, so an older change is looked for wider. */}
              <p className="sub2" data-testid="a05-none-range">
                {t('a05.noneRange', [date(filter.from), date(filter.to)])}
              </p>
            </>
          }
        >
          {(rows) => (
            <>
              {rows.map((e) => (
                <tr key={e.id}>
                  <td>{dateTime(e.changedAt)}</td>
                  <td>{personLabel(e.maker)}</td>
                  <td>{personLabel(e.checker)}</td>
                  <td>{e.entityName}</td>
                  <td>{e.entityKey}</td>
                  <td>{e.fieldName}</td>
                  <td>{e.oldValue ?? '—'}</td>
                  <td>{e.newValue ?? '—'}</td>
                  <td>{date(e.effectiveDate)}</td>
                  <td>{e.reason}</td>
                  <td>
                    <div className="acts">
                      <button
                        type="button"
                        className="ia"
                        title={t('wf.viewTitle')}
                        aria-label={t('x04w.viewRow', [e.id])}
                        onClick={() => setViewed(e)}
                      >
                        {t('wf.view')}
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </>
          )}
        </ServerPagination>
      </Tbl>

      <div className="g2 fl-mt">
        <Tbl
          title={t('x04w.matrix')}
          cols={[
            { t: t('x04w.matrix.module'), s: 'SYS' },
            { t: t('x04w.matrix.events'), s: 'SYS' },
            { t: t('x04w.matrix.capture'), s: 'SYS' },
          ]}
        >
          {MATRIX.map((row) => (
            <tr key={row}>
              <td>{t(`x04w.matrix.${row}`)}</td>
              <td>{t(`x04w.matrix.${row}.events`)}</td>
              <td>
                <span className="pill p-ok">{t('x04w.matrix.audited')}</span>
              </td>
            </tr>
          ))}
        </Tbl>
        <div>
          <PointInTime record={record} day={day} />
          <RegisterSection
            id="x04-enquiry"
            label={t('x04w.enquiry')}
            open={enquiryOpen}
            onToggle={setEnquiryOpen}
          >
            <RecordEnquiry
              anchor={enquiry}
              onShow={(next, asOf) => {
                setRecord(next);
                setDay(asOf);
              }}
            />
          </RegisterSection>
          <div className="fl-mt">
            <Panel
              title={t('x04w.coverage')}
              items={[
                ...COVERAGE.map((c) => ({
                  icon: c.icon,
                  title: t(`x04w.coverage.${c.key}`),
                  src: 'SYS' as const,
                  text: t(`x04w.coverage.${c.key}.text`),
                  right: <span className="pill p-ok">{t('x04w.coverage.full')}</span>,
                  tone: 'info' as const,
                })),
                {
                  icon: '📦',
                  title: t('x04w.coverage.retention'),
                  src: 'ENT' as const,
                  text: t('x04w.coverage.retention.text'),
                  right: retentionPill,
                  tone: 'bad' as const,
                },
              ]}
            />
          </div>
        </div>
      </div>

      <div className="fl-mt">
        <SessionLog />
      </div>

      {record && (
        <details className="fl-mt" data-testid="x04-record-history">
          <summary className="sub2">{t('x04w.recordHistory', [record.key])}</summary>
          <NativeHistory
            key={`${record.entity}|${record.key}|${day}`}
            record={record}
            day={day}
            withAsOf={false}
          />
        </details>
      )}

      <div className="fl-mt">
        <LegacyPanel
          record={record}
          acts={
            record && mayExport ? (
              <ExportButton
                className="btn sm"
                href={legacyExportUrl(record)}
                what={t('x04.legacyExport')}
                label={t('x04w.act.export')}
              />
            ) : (
              <Explained
                kind="sm"
                label={t('x04w.act.export')}
                why={mayExport ? t('x04w.why.legacyNoRecord') : t('x04w.why.export')}
              />
            )
          }
        />
      </div>

      {viewed && <RowDialog row={viewed} onClose={() => setViewed(null)} />}
    </div>
  );
}

/** The record-type text box with the known types as suggestions. */
function EntityInput({ value }: { value: string }) {
  const { t } = useI18n();
  const types = useRecordTypes();
  return (
    <>
      <input
        name="entity"
        aria-label={t('a05.entity')}
        placeholder={t('a05.entity')}
        maxLength={100}
        defaultValue={value}
        list="a05-entity-types"
      />
      <datalist id="a05-entity-types">
        {(Array.isArray(types.data) ? types.data : []).map((e) => (
          <option key={e} value={e} label={t(`x04.entity.${e}`)} />
        ))}
      </datalist>
    </>
  );
}

/** "Old → new", or a dash for a side that was not recorded. */
function change(row: AuditEvent): string {
  return `${row.oldValue ?? '—'} → ${row.newValue ?? '—'}`;
}

/** SRS l.1248: the order number, linked to its document. */
function OrderLink({ row }: { row: AuditEvent }) {
  if (!row.order) return <>—</>;
  return <a href={orderDocumentUrl(row.id, row.order.documentId)}>{row.order.number}</a>;
}

/** The authorising approval and who decided it. */
function authorisation(row: AuditEvent): string {
  if (!row.approvalId) return '—';
  return row.checker ? `APR-${row.approvalId} · ${personLabel(row.checker)}` : `APR-${row.approvalId}`;
}

/** BR-AUDIT-003 "a row missing any of ... is invalid": the drawn table of the fields every row carries. */
function MandatoryFields({ filter }: { filter: ReturnType<typeof defaultAuditFilter> }) {
  const { t } = useI18n();
  return (
    <Tbl
      title={t('x04w.mandatory')}
      cols={[
        { t: t('x04w.mf.when'), s: 'SYS' },
        { t: t('x04w.mf.user'), s: 'SYS' },
        { t: t('x04w.mf.ip'), s: 'SYS' },
        { t: t('x04w.mf.object'), s: 'SYS' },
        { t: t('x04w.mf.change'), s: 'SYS' },
        { t: t('x04w.mf.effective'), s: 'SYS' },
        { t: t('x04w.mf.reason'), s: 'SYS' },
        { t: t('x04w.mf.order'), s: 'SYS' },
        { t: t('x04w.mf.authorisation'), s: 'SYS' },
      ]}
    >
      <ServerPagination<PageResult<AuditEvent>>
        queryKey={[...auditKeys.search, filter]}
        fetchPage={fetchAuditPage(filter)}
        empty={<p className="fl-hint">{t('a05.none')}</p>}
      >
        {(rows) => (
          <>
            {rows.map((e) => (
              <tr key={e.id}>
                <td>{dateTime(e.changedAt)}</td>
                <td>{personLabel(e.maker)}</td>
                <td>{e.sourceIp ?? '—'}</td>
                <td>
                  {e.entityName} · {e.entityKey} · {e.fieldName}
                </td>
                <td>{change(e)}</td>
                <td>{date(e.effectiveDate)}</td>
                <td>{e.reason}</td>
                <td>
                  <OrderLink row={e} />
                </td>
                <td>{authorisation(e)}</td>
              </tr>
            ))}
          </>
        )}
      </ServerPagination>
    </Tbl>
  );
}

/**
 * The drawn Session Action Log: the reader's own rows of today, as the server wrote them (the pack's note: "in the
 * built system this is the audit trail above, written server-side and immutable").
 */
function SessionLog() {
  const { t } = useI18n();
  const me = useMe();
  const [today] = useState(() => isoDay(0));
  const login = me.data?.loginId;
  const filter = { ...defaultAuditFilter(), from: today, to: today, actor: login ?? '' };
  return (
    <Tbl
      title={t('x04w.session')}
      cols={[
        { t: t('x04w.col.when'), s: 'SYS' },
        { t: t('x04w.session.role'), s: 'SYS' },
        { t: t('x04w.session.screen'), s: 'SYS' },
        { t: t('x04w.session.action'), s: 'SYS' },
        { t: t('x04w.session.scope'), s: 'ENT' },
      ]}
    >
      {login ? (
        <ServerPagination<PageResult<AuditEvent>>
          queryKey={[...auditKeys.search, 'session', filter]}
          fetchPage={fetchAuditPage(filter)}
          empty={<p className="fl-hint">{t('x04w.session.none')}</p>}
        >
          {(rows) => (
            <>
              {rows.map((e) => (
                <tr key={e.id}>
                  <td>{dateTime(e.changedAt)}</td>
                  <td>{me.data?.roleName ?? '—'}</td>
                  <td>{e.moduleCode ?? '—'}</td>
                  <td>
                    {e.entityName} · {e.fieldName}
                  </td>
                  <td>{me.data?.location?.label ?? '—'}</td>
                </tr>
              ))}
            </>
          )}
        </ServerPagination>
      ) : (
        <tr>
          <td colSpan={5} className="sub2">
            {t('common.loading')}
          </td>
        </tr>
      )}
    </Tbl>
  );
}

/**
 * SRS-20-3 / SRS-20-6: the drawn Point-in-Time Reconstruction card. It prints the record and the date chosen in the
 * enquiry below it, and the record's fields as of that date with "Exactly 1 per key" (the server refuses two versions
 * on one day, AUD-409).
 */
function PointInTime({ record, day }: { record: RecordRef | null; day: string }) {
  const { t } = useI18n();
  const rows: KvRow[] = [
    {
      k: t('x04w.pit.object'),
      s: 'ENT',
      v: record ? `${t(`x04.entity.${record.entity}`)} · ${record.key}` : t('x04w.pit.noRecord'),
    },
    { k: t('x04w.pit.asOf'), s: 'ENT', v: day ? <b>{date(day)}</b> : '—' },
  ];
  return (
    <KvCard
      title={t('x04w.pit')}
      rows={rows}
      after={
        <>
          {record && day && <AsOfRows record={record} day={day} />}
          <div className="kv fl-mt">
            <div className="k">{t('x04w.pit.usedBy')}</div>
            <div>{t('x04w.pit.usedByText')}</div>
          </div>
        </>
      }
    />
  );
}

/**
 * The enquiry the drawing leaves out, in a closed section: record type, Record Key and As-of date. Record Key must
 * resolve to a master or rule record (X-04, SC-03-0016); every Legacy Audit enquiry is logged (SRS-20-24).
 */
function RecordEnquiry({
  anchor,
  onShow,
}: {
  anchor: RefObject<HTMLDivElement | null>;
  onShow: (record: RecordRef, day: string) => void;
}) {
  const { t } = useI18n();
  const types = useRecordTypes();
  const [invalid, setInvalid] = useState(false);

  function show(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const entity = formText(form, 'entity');
    const key = formText(form, 'key');
    if (!entity || !validRecordKey(key)) {
      setInvalid(true);
      return;
    }
    setInvalid(false);
    onShow({ entity, key: key.trim() }, formText(form, 'asOf'));
  }

  return (
    <div ref={anchor} className="card">
      <form className="bd" onSubmit={show} noValidate>
        <div className="toolbar">
          <select name="entity" defaultValue="" aria-label={t('x04.type')} required>
            <option value="" disabled>
              {t('x04.pickType')}
            </option>
            {(Array.isArray(types.data) ? types.data : []).map((e) => (
              <option key={e} value={e}>
                {t(`x04.entity.${e}`)}
              </option>
            ))}
          </select>
          <input
            name="key"
            aria-label={t('x04.key')}
            placeholder={t('x04.key')}
            maxLength={100}
            required
            aria-describedby="x04-key-hint"
          />
          <input name="asOf" type="date" aria-label={t('x04.asOf')} />
          <button type="submit" className="btn pri">
            {t('x04.show')}
          </button>
        </div>
        <p id="x04-key-hint" className="fl-hint">
          {t('x04.keyHint')}
        </p>
        <p className="fl-hint">{t('x04.legacy.logged')}</p>
        <Problem error={types.error} />
        {invalid && (
          <p className="fl-banner" role="alert">
            {t('x04.keyRequired')}
          </p>
        )}
      </form>
    </div>
  );
}

/** The record's fields on the date, then the drawn "Active versions returned". */
function AsOfRows({ record, day }: { record: RecordRef; day: string }) {
  const { t } = useI18n();
  const asOf = useAsOf(record, day);
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
    <div className="kv fl-mt" role="status">
      <div className="k">{t('x04w.pit.asOf')}</div>
      <div>
        {view.versioned
          ? t('x04.asOf.found', [date(view.date), view.versionNo ?? ''])
          : t('x04.asOf.rebuilt', [date(view.date)])}
      </div>
      {fieldRows(view.values).map(([k, value]) => (
        <Fragment key={k}>
          <div className="k">{k}</div>
          <div>{value}</div>
        </Fragment>
      ))}
      <div className="k">{t('x04w.pit.versions')}</div>
      <div>
        <span className="pill p-ok">{t('x04w.pit.exactlyOne')}</span>
      </div>
    </div>
  );
}

/** The row's View dialog: every SRS-G-2 field of the row, read only (SRS-20-19). */
function RowDialog({ row, onClose }: { row: AuditEvent; onClose: () => void }) {
  const { t } = useI18n();
  return (
    <ViewDialog
      screen={t('screen.A-05')}
      onClose={onClose}
      rows={[
        { k: t('x04.txn'), v: row.id, s: 'SYS' },
        { k: t('x04.when'), v: dateTime(row.changedAt), s: 'SYS' },
        { k: t('x04.maker'), v: personLabel(row.maker), s: 'SYS' },
        { k: t('x04.checker'), v: personLabel(row.checker), s: 'SYS' },
        { k: t('x04w.col.object'), v: row.entityName, s: 'SYS' },
        { k: t('x04w.col.key'), v: row.entityKey, s: 'SYS' },
        { k: t('x04w.col.field'), v: row.fieldName, s: 'SYS' },
        { k: t('x04.old'), v: row.oldValue ?? '—', s: 'SYS' },
        { k: t('x04.new'), v: row.newValue ?? '—', s: 'SYS' },
        { k: t('x04.outcome'), v: t(`x04.outcome.${row.outcome}`), s: 'SYS' },
        { k: t('x04.effective'), v: date(row.effectiveDate), s: 'SYS' },
        { k: t('common.reason'), v: row.reason, s: 'SYS' },
        { k: t('x04w.mf.order'), v: <OrderLink row={row} />, s: 'SYS' },
        { k: t('x04.authorisation'), v: authorisation(row), s: 'SYS' },
        { k: t('x04.ip'), v: row.sourceIp ?? '—', s: 'SYS' },
        { k: t('w01.module'), v: row.moduleCode ?? '—', s: 'SYS' },
        { k: t('x04.origin'), v: t('x04.origin.NATIVE'), s: 'SYS' },
      ]}
    />
  );
}
