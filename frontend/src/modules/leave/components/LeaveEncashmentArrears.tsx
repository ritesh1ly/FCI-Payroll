'use client';

import { useCallback, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { can, useMe } from '@/shared/session';
import {
  amount,
  Card,
  date,
  ExportButton,
  Problem,
  ReasonDialog,
  ServerPagination,
  useDialogFocus,
  useToast,
  TextAreaField,
  withBase,
} from '@/shared/ui';
import { InvoiceCard } from '@/shared/ui/invoiceCard';
import { OrderCopyCard } from '@/shared/ui/orderCopy';
import { Act, Explained, KvCard, Locked, Tbl, Tiles, WfTile } from '@/shared/ui/wireframe';
import { businessToday } from '@/shared/time';
import type { LeArrearFilter, LeArrearRow, RunRequest, RunResult } from '../api/leaveArrearApi';
import {
  calculationSheet,
  cancellable,
  daPercent,
  invoiceCode,
  isPayment,
  payOf,
  runReady,
  selectable,
  selectedTds,
  selectedTotal,
  statusTone,
} from '../business/leaveArrearRules';
import {
  leaveArrearDocumentUrl,
  leaveArrearKeys,
  leaveArrearPages,
  leaveArrearReportUrl,
  useArrearSummary,
  useAttachRecordOrder,
  useCancelArrear,
  useOrderDocumentTypes,
  useRecordOrders,
  useRevisionOrders,
  useRunArrear,
  useSubmitArrears,
} from '../orchestration/leaveArrearQueries';

/** Saves one record's calculation sheet (CSV) in the browser: the values are already on screen. */
function saveSheet(r: LeArrearRow, label: (key: string) => string) {
  const blob = new Blob([calculationSheet(r, label)], { type: 'text/csv' });
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = `${r.recordNumber}-calculation-sheet.csv`;
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(link.href);
}

/** "YYYYMM" as "MM/YYYY". */
function month(yymm: string): string {
  return yymm.length === 6 ? `${yymm.slice(4)}/${yymm.slice(0, 4)}` : yymm;
}

/** An amount or a dash. */
const pay = (basic: number | null, da: number | null) => {
  const sum = payOf(basic, da);
  return sum === null ? '—' : amount(sum);
};

/**
 * I-24 Leave Encashment Arrear as Wireframes v6.1 draws it (a frozen requirement per OD-121; plan 13 phase 13d;
 * SRS-8-23, SRS-8-24, SRS-1-43, SRS-G-37, BR-ARR-LE-001 to 004): the three actions over the screen; the three
 * tiles; the Month-wise LE arrear records with Select all; the calculation sheet of the chosen record; the invoice
 * status with ⊘ Cancel; the order copy of the chosen record. The Maker selects one or many records and submits them
 * together; the Payroll Checker approves in W-01. Not drawn and kept, closed until pressed (PENDING WF-187): the
 * Maker's Run Arrear opens with "📤 Submit LE arrears"; the status and member filters, the report download, the
 * calculation sheet download and Send Invoices to APPS open with "📄 LE Arrear Invoice Report". The invoices to
 * APPS follow with the APPS interface (PENDING 13-le-apps), so the invoice number and date stay empty until then.
 */
export function LeaveEncashmentArrears() {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const [status, setStatus] = useState('');
  const [eis, setEis] = useState('');
  // The selection keeps the rows, so the tiles and a submission span the pages.
  const [selected, setSelected] = useState<Map<number, LeArrearRow>>(new Map());
  const [rows, setRows] = useState<LeArrearRow[]>([]);
  const [current, setCurrent] = useState<LeArrearRow | null>(null);
  const [reportOpen, setReportOpen] = useState(false);
  const [submitOpen, setSubmitOpen] = useState(false);
  const [cancelFor, setCancelFor] = useState<LeArrearRow | null>(null);
  const [error, setError] = useState<unknown>(null);
  const cancel = useCancelArrear();
  const summary = useArrearSummary();
  const mayRun = can(me.data, 'I-24', 'COMPUTE');
  const maySubmit = can(me.data, 'I-24', 'SUBMIT');
  const mayApprove = can(me.data, 'I-24', 'APPROVE');
  const filter: LeArrearFilter = { status: status || undefined, eis: eis || undefined };
  const picked = [...selected.values()];
  const total = selectedTotal(picked, new Set(selected.keys()));
  const tds = selectedTds(picked);
  const shown = useCallback((page: LeArrearRow[]) => {
    setRows(page);
    setCurrent((prev) =>
      prev ? (page.find((r) => r.id === prev.id) ?? page[0] ?? null) : (page[0] ?? null),
    );
  }, []);

  function toggle(r: LeArrearRow) {
    setSelected((s) => {
      const next = new Map(s);
      if (next.has(r.id)) next.delete(r.id);
      else next.set(r.id, r);
      return next;
    });
  }

  function selectAll() {
    setSelected((s) => {
      const next = new Map(s);
      for (const r of rows) if (selectable(r)) next.set(r.id, r);
      return next;
    });
  }

  /** SRS-8-24: a filter change clears the selection, so no hidden record is submitted. */
  function narrow(change: () => void) {
    change();
    setSelected(new Map());
  }

  async function raiseCancel(r: LeArrearRow, reason: string) {
    setError(null);
    try {
      await cancel.mutateAsync({ id: r.id, reason });
      toast(t('i24.cancelRaised', [r.recordNumber]));
    } catch (cause) {
      setError(cause);
    }
  }

  const invoiceCancelWhy = (r: LeArrearRow): string | null => {
    if (!maySubmit) return t('wf.inv.why.maker');
    if (cancellable(r)) return null;
    return r.status === 'SUBMITTED' ? t('i24.why.cancelChecker') : t('inv.cancelBlocked');
  };

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <Act label={t('i24.act.report')} onClick={() => setReportOpen(true)} />
        {maySubmit ? (
          <Act label={t('i24.act.submit')} kind="pri" onClick={() => setSubmitOpen(true)} />
        ) : (
          <Locked label={t('i24.act.submit')} why={t('i24.guard.submit')} />
        )}
        {mayApprove ? (
          <Act label={t('i24.act.approve')} href={withBase('/payroll/?s=W-01')} />
        ) : (
          <Locked label={t('i24.act.approve')} why={t('i24.guard.approve')} />
        )}
      </div>
      <Problem error={error} />
      <Tiles>
        <WfTile
          icon="🗓"
          label={t('i24.tile.records')}
          src="SYS"
          big={summary.data?.records ?? 0}
          sub={summary.data?.order ? t('i24.tile.recordsSub', [summary.data.order]) : ''}
        />
        <WfTile
          icon="☑"
          label={t('i24.tile.selected')}
          src="SYS"
          big={selected.size}
          sub={t('i24.tile.selectedSub')}
        />
        <WfTile
          icon="₹"
          tone="ok"
          label={t('i24.tile.arrear')}
          src="CALC"
          big={`₹ ${amount(total)}`}
          sub={t('i24.tile.arrearSub', [amount(tds)])}
        />
      </Tiles>
      <Tbl
        title={t('i24.table')}
        label={t('screen.I-24')}
        acts={
          maySubmit ? (
            <Act label={t('i24.act.selectAll')} kind="sm" onClick={selectAll} />
          ) : (
            <Explained label={t('i24.act.selectAll')} kind="sm" why={t('reason.makerOnly')} />
          )
        }
        cols={[
          { t: '', s: 'SYS' },
          { t: t('i24.col.record'), s: 'SYS' },
          { t: t('i24.col.member'), s: 'HRMS' },
          { t: t('i24.col.separation'), s: 'HRMS' },
          { t: t('i24.col.order'), s: 'SYS' },
          { t: t('i24.col.days'), s: 'HRMS', num: true },
          { t: t('i24.col.oldPay'), s: 'CALC', num: true },
          { t: t('i24.col.revisedPay'), s: 'CALC', num: true },
          { t: t('i24.col.paid'), s: 'SYS', num: true },
          { t: t('i24.col.due'), s: 'CALC', num: true },
          { t: t('i24.col.arrear'), s: 'CALC', num: true },
          { t: t('i24.col.tds'), s: 'CALC', num: true },
          { t: t('i24.col.invoiceStatus'), s: 'SYS' },
          { t: t('i24.col.invoiceSent'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={leaveArrearKeys.records(filter)}
          fetchPage={leaveArrearPages.records(filter)}
          empty={<p className="fl-hint">{t('i24.none')}</p>}
          onRows={shown}
        >
          {(page) =>
            page.map((r) => (
              <tr
                key={r.id}
                data-record={r.recordNumber}
                tabIndex={0}
                aria-selected={current?.id === r.id}
                className={current?.id === r.id ? 'fl-row-selected' : undefined}
                onClick={() => setCurrent(r)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') setCurrent(r);
                }}
              >
                <td onClick={(e) => e.stopPropagation()}>
                  <input
                    type="checkbox"
                    aria-label={t('i24.selectRecord', [r.recordNumber])}
                    checked={selected.has(r.id)}
                    disabled={!maySubmit || !selectable(r)}
                    title={
                      !maySubmit ? t('reason.makerOnly') : !selectable(r) ? t('i24.why.select') : undefined
                    }
                    onChange={() => toggle(r)}
                  />
                </td>
                <td>
                  <b>{r.recordNumber}</b>
                  <div className="sub2">{month(r.month)}</div>
                </td>
                <td>
                  {r.eis} {r.name}
                </td>
                <td>{r.separationDate ? date(r.separationDate) : '—'}</td>
                <td>
                  {r.order ?? '—'}
                  <div className="sub2">{date(r.revisionEffective)}</div>
                </td>
                <td className="num">{r.days ?? '—'}</td>
                <td className="num">{pay(r.oldBasic, r.oldDa)}</td>
                <td className="num">{pay(r.revisedBasic, r.revisedDa)}</td>
                <td className="num">{r.paid === null ? '—' : amount(r.paid)}</td>
                <td className="num">{r.due === null ? '—' : amount(r.due)}</td>
                <td className="num">{r.arrear === null ? '—' : amount(r.arrear)}</td>
                <td className="num">{r.arrear === null ? '—' : amount(r.tds)}</td>
                <td>
                  <span className={`pill ${statusTone(r.status)}`}>
                    {r.status === 'NOT_ELIGIBLE' && r.ineligibleReason
                      ? t('i24.invoiceStatus.notEligibleWhy', [r.ineligibleReason])
                      : t(`i24.invoiceStatus.${r.status}`)}
                  </span>
                </td>
                {/* BR-AUDIT-010: the date comes with the APPS interface (PENDING 13-le-apps). */}
                <td>—</td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <Sheet record={current} />
        <InvoiceCard
          title={t('i24.invoices')}
          rows={rows.filter(isPayment).map((r) => ({
            key: String(r.id),
            entry: r.recordNumber,
            member: `${r.eis} ${r.name}`,
            paymentType: t('i24.paymentType'),
            amount: r.arrear === null ? '—' : amount(r.arrear),
            invoiceNo: null,
            invoiceStatus: invoiceCode(r),
            sentToAppsDate: null,
            appsCancelled: false,
            cancelled: r.status === 'CANCELLED',
            cancelWhy: invoiceCancelWhy(r),
            onCancel: () => setCancelFor(r),
          }))}
        />
      </div>
      <RecordOrders record={current} maker={maySubmit} />
      {reportOpen && (
        <ReportDialog
          status={status}
          eis={eis}
          filter={filter}
          record={current}
          mayExport={can(me.data, 'I-24', 'EXPORT')}
          onStatus={(v) => narrow(() => setStatus(v))}
          onEis={(v) => narrow(() => setEis(v))}
          onClose={() => setReportOpen(false)}
        />
      )}
      {submitOpen && (
        <SubmitDialog
          records={picked}
          mayRun={mayRun}
          onSubmitted={() => setSelected(new Map())}
          onClose={() => setSubmitOpen(false)}
        />
      )}
      {cancelFor && (
        <ReasonDialog
          title={t('i24.cancelTitle', [cancelFor.recordNumber])}
          onCancel={() => setCancelFor(null)}
          onConfirm={(reason) => {
            const target = cancelFor;
            setCancelFor(null);
            void raiseCancel(target, reason);
          }}
        />
      )}
    </div>
  );
}

/** The calculation sheet of the chosen record (BR-ARR-LE-002), as drawn. */
function Sheet({ record: r }: { record: LeArrearRow | null }) {
  const { t } = useI18n();
  if (!r) {
    return <KvCard title={t('i24.sheetTitleNone')} rows={[{ k: t('i24.kv.noRow'), v: '' }]} />;
  }
  const label = (key: string, percent: number | null) =>
    percent === null ? t(`${key}Plain`) : t(key, [percent]);
  const line = (basic: number | null, da: number | null) =>
    payOf(basic, da) === null
      ? '—'
      : t('i24.kv.payValue', [amount(basic), amount(da), amount(payOf(basic, da))]);
  return (
    <KvCard
      title={t('i24.sheetTitle', [r.recordNumber])}
      rows={[
        {
          k: t('i24.kv.order'),
          s: 'SYS',
          v: t('i24.kv.orderValue', [r.order ?? '—', date(r.revisionEffective)]),
        },
        {
          k: t('i24.kv.separation'),
          s: 'HRMS',
          v: !r.separationDate
            ? t('i24.kv.separationNone')
            : r.status === 'NOT_ELIGIBLE'
              ? date(r.separationDate)
              : t('i24.kv.separationEligible', [date(r.separationDate)]),
        },
        {
          k: t('i24.kv.days'),
          s: 'HRMS',
          v: r.days === null ? '—' : t('i24.kv.daysValue', [r.days]),
        },
        {
          k: label('i24.kv.oldPay', daPercent(r.oldBasic, r.oldDa)),
          s: 'CALC',
          v: line(r.oldBasic, r.oldDa),
        },
        {
          k: label('i24.kv.revisedPay', daPercent(r.revisedBasic, r.revisedDa)),
          s: 'CALC',
          v: line(r.revisedBasic, r.revisedDa),
        },
        { k: t('i24.kv.formula'), s: 'RULE', v: t('i24.kv.formulaValue') },
        {
          k: t('i24.kv.paidDue'),
          s: 'CALC',
          v: t('i24.kv.pairValue', [amount(r.paid), amount(r.due)]),
        },
        {
          k: t('i24.kv.arrearTds'),
          s: 'CALC',
          v: <b>{t('i24.kv.pairValue', [amount(r.arrear), amount(r.tds)])}</b>,
        },
      ]}
    />
  );
}

/** "📎 Order copy": the orders of the chosen record (FRS §6.4.3, A-41); the Maker attaches while it is Ready. */
function RecordOrders({ record, maker }: { record: LeArrearRow | null; maker: boolean }) {
  const { t } = useI18n();
  const orders = useRecordOrders(record?.id ?? null);
  const types = useOrderDocumentTypes();
  const attach = useAttachRecordOrder();
  return (
    <OrderCopyCard
      key={record?.id ?? 0}
      hint={t('i24.ord.hint')}
      canAttach={maker && record?.status === 'CALCULATED'}
      readOnly={!maker}
      stored={orders.data}
      storedError={orders.error ?? types.error}
      fileHref={(fileId) => leaveArrearDocumentUrl(record?.id ?? 0, fileId)}
      today={businessToday()}
      // The I24_SUBMIT route (approval_workflow_action, V048_6) does not require an order: the card never says
      // "submit blocked" (PENDING WF-189).
      attachmentRequired={false}
      referenceRequired={(code) => types.data?.find((d) => d.code === code)?.referenceRequired ?? false}
      onAttach={async (o) => {
        if (!record) return;
        await attach.mutateAsync({
          id: record.id,
          order: {
            documentType: o.documentType,
            reference: o.reference,
            orderDate: o.orderDate,
            issuingOffice: o.issuingOffice,
          },
          files: o.files,
        });
      }}
    />
  );
}

/**
 * "📄 LE Arrear Invoice Report" (SRS-8-24, Annexure A.11): the status and member filters of the list and the report,
 * the report download, the chosen record's calculation sheet (SRS-1-43) and Send Invoices to APPS, disabled until the
 * APPS interface (SRS-8-24 / l.3121, module 23, PENDING 13-le-apps).
 */
function ReportDialog({
  status,
  eis,
  filter,
  record,
  mayExport,
  onStatus,
  onEis,
  onClose,
}: {
  status: string;
  eis: string;
  filter: LeArrearFilter;
  record: LeArrearRow | null;
  mayExport: boolean;
  onStatus: (value: string) => void;
  onEis: (value: string) => void;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const dialog = useDialogFocus<HTMLDivElement>();
  return (
    <div className="fl-modal-backdrop" role="presentation">
      <div
        ref={dialog}
        className="fl-modal"
        role="dialog"
        aria-modal="true"
        aria-label={t('i24.reportTitle')}
      >
        <h2>{t('i24.reportTitle')}</h2>
        <p className="fl-hint">{t('i24.reportNote')}</p>
        <div className="rf-grid">
          <div className="rf-field">
            <label htmlFor="i24-status">{t('i24.status')}</label>
            <select id="i24-status" value={status} onChange={(e) => onStatus(e.target.value)}>
              <option value="">{t('common.all')}</option>
              <ReferenceOptions list="LE_ARREAR_STATUS" />
            </select>
          </div>
          <div className="rf-field">
            <label htmlFor="i24-eis">{t('m01.eis')}</label>
            <input id="i24-eis" maxLength={20} value={eis} onChange={(e) => onEis(e.target.value.trim())} />
          </div>
        </div>
        <div className="fl-actions">
          {mayExport && <ExportButton href={leaveArrearReportUrl(filter)} what={t('i24.report')} />}
          {record && (
            <button
              type="button"
              className="btn"
              aria-label={t('i24.sheetOf', [record.recordNumber])}
              onClick={() => saveSheet(record, t)}
            >
              {t('i24.sheet')}
            </button>
          )}
          <button type="button" className="btn" disabled title={t('i24.why.invoices')}>
            {t('i24.sendInvoices')}
          </button>
          <button type="button" className="btn" onClick={onClose}>
            {t('common.close')}
          </button>
        </div>
      </div>
    </div>
  );
}

/** "📤 Submit LE arrears": the selection with its remark (SRS-8-24), and the Maker's run (SRS-8-23). */
function SubmitDialog({
  records,
  mayRun,
  onSubmitted,
  onClose,
}: {
  records: LeArrearRow[];
  mayRun: boolean;
  onSubmitted: () => void;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const dialog = useDialogFocus<HTMLDivElement>();
  const submit = useSubmitArrears();
  const [remark, setRemark] = useState('');
  const [error, setError] = useState<unknown>(null);
  const none = records.length === 0;
  const noRemark = remark.trim() === '';

  async function send() {
    setError(null);
    try {
      await submit.mutateAsync({ ids: records.map((r) => r.id), remark: remark.trim() });
      toast(t('i24.submitted'));
      onSubmitted();
      setRemark('');
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="fl-modal-backdrop" role="presentation">
      <div
        ref={dialog}
        className="fl-modal fl-modal-wide"
        role="dialog"
        aria-modal="true"
        aria-label={t('i24.submitTitle')}
      >
        <h2>{t('i24.submitTitle')}</h2>
        <Problem error={error} />
        <p className="fl-hint">
          {t('i24.submitNote', [
            records.length,
            amount(selectedTotal(records, new Set(records.map((r) => r.id)))),
            amount(selectedTds(records)),
          ])}
        </p>
        <div className="toolbar">
          <label>
            {t('common.reason')}{' '}
            <TextAreaField maxLength={500} value={remark} onChange={(e) => setRemark(e.target.value)} />
          </label>
          <button
            type="button"
            className="btn pri"
            disabled={none || noRemark || submit.isPending}
            title={none ? t('i24.why.none') : noRemark ? t('i24.why.remark') : undefined}
            onClick={() => void send()}
          >
            {t('action.i24.submit')} ({records.length})
          </button>
        </div>
        {mayRun && <RunCard />}
        <div className="fl-actions">
          <button type="button" className="btn" onClick={onClose}>
            {t('common.close')}
          </button>
        </div>
      </div>
    </div>
  );
}

/** SRS-8-23: the Maker's run against a revision order. */
function RunCard() {
  const { t } = useI18n();
  const orders = useRevisionOrders(true);
  const run = useRunArrear();
  const [draft, setDraft] = useState<Partial<RunRequest>>({});
  const [result, setResult] = useState<RunResult | null>(null);
  const [error, setError] = useState<unknown>(null);

  async function start() {
    setError(null);
    setResult(null);
    try {
      setResult(
        await run.mutateAsync({
          eis: draft.eis?.trim() || undefined,
          circularId: draft.circularId as number,
          revisionEffective: draft.revisionEffective as string,
          reason: (draft.reason ?? '').trim(),
        }),
      );
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <Card title={t('action.i24.run')}>
      <Problem error={error} />
      <div className="toolbar">
        <label>
          {t('i24.revisionOrder')}{' '}
          <select
            value={draft.circularId ?? ''}
            onChange={(e) =>
              setDraft({ ...draft, circularId: e.target.value === '' ? undefined : Number(e.target.value) })
            }
          >
            <option value="">{t('common.choose')}</option>
            {(orders.data ?? []).map((o) => (
              <option key={o.id} value={o.id}>
                {o.number}
                {o.subject ? ` - ${o.subject}` : ''}
              </option>
            ))}
          </select>
        </label>
        <label>
          {t('i24.effective')}{' '}
          <input
            type="date"
            value={draft.revisionEffective ?? ''}
            onChange={(e) => setDraft({ ...draft, revisionEffective: e.target.value })}
          />
        </label>
        <label>
          {t('i24.runEis')}{' '}
          <input
            maxLength={20}
            value={draft.eis ?? ''}
            onChange={(e) => setDraft({ ...draft, eis: e.target.value })}
          />
        </label>
        <label>
          {t('common.reason')}{' '}
          <TextAreaField
            maxLength={300}
            value={draft.reason ?? ''}
            onChange={(e) => setDraft({ ...draft, reason: e.target.value })}
          />
        </label>
        <button
          type="button"
          className="btn"
          disabled={!runReady(draft) || run.isPending}
          title={!runReady(draft) ? t('i24.why.run') : undefined}
          onClick={() => void start()}
        >
          {t('action.i24.run')}
        </button>
      </div>
      {result && (
        <div role="status">
          <p>{t('i24.ran', [result.members, result.records])}</p>
          {result.skipped.length > 0 && (
            <ul className="fl-list" aria-label={t('i24.skipped')}>
              {result.skipped.map((s) => (
                <li key={s.eis} className="warn">
                  {s.eis}: {s.reason}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </Card>
  );
}
