'use client';

import { useCallback, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import {
  Card,
  date,
  ExportButton,
  Problem,
  ServerPagination,
  Src,
  useToast,
  TextAreaField,
  withBase,
} from '@/shared/ui';
import { OrderCopyCard } from '@/shared/ui/orderCopy';
import { Act, Explained, KvCard, Panel, Tbl, Tiles, WfTile } from '@/shared/ui/wireframe';
import type { TransferRow } from '../api/registerApi';
import { statusPill, taskPill } from '../business/eventRules';
import { taskDocumentUrl, useAttachTaskOrder, useTaskDocuments } from '../orchestration/hrmsChangeQueries';
import {
  registerKeys,
  registerPages,
  registerUrls,
  useTransferSummary,
} from '../orchestration/registerQueries';
import {
  EMPTY_ORDER,
  OrderFields,
  orderComplete,
  todayIso,
  useDocumentTypes,
  type OrderDraft,
} from '@/modules/general';
import { eventKeys, eventPages, useDestinations, useRecordTransfer } from '../orchestration/eventQueries';

/**
 * The row the order card opens on: the first whose I-21 task has passed its order entry, else the first with a task,
 * else the first. A task still PENDING_ORDER_DETAILS has no order to show (SRS-G-31).
 */
function firstWithOrder<T extends { taskId: number | null; taskStatus: string | null }>(rows: T[]): T | null {
  return (
    rows.find((r) => r.taskId && r.taskStatus !== 'PENDING_ORDER_DETAILS') ??
    rows.find((r) => r.taskId) ??
    rows[0] ??
    null
  );
}

/**
 * M-11 Staff Transfer, Inter-Zone / Inter-Region as Wireframes v6.1 draws it (OD-121; SRS l.1339-1362; FRS 7.9.8): the
 * link to the transfer tasks on I-21, the four tiles, the Employee Transfer Register with PAN as the anchor, the rules,
 * the detail of the picked transfer and its order card. A transfer comes from HRMS and opens an I-21 task: the Payroll
 * Maker records the order there, the Payroll Checker approves. The CPF Member ID and parent zone never change. No
 * EMP_TRANSFER API is in the HRMS collection (HRMS-PULL-1), so the register reads employee_transfer.
 */
export function StaffTransfers() {
  const { t } = useI18n();
  const me = useMe();
  const summary = useTransferSummary();
  const [picked, setPicked] = useState<TransferRow | null>(null);
  const mayExport = can(me.data, 'M-11', 'EXPORT');
  const onRows = useCallback(
    (rows: TransferRow[]) => setPicked((now) => rows.find((r) => r.id === now?.id) ?? firstWithOrder(rows)),
    [],
  );
  const s = summary.data;
  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="spacer" />
        <Act label={t('trf.w.tasks')} href={withBase('/payroll/?s=I-21')} />
      </div>
      <Tiles>
        <WfTile
          icon="🔁"
          label={t('trf.w.t.received')}
          src="HRMS"
          big={s?.received ?? '—'}
          sub={t('trf.w.t.receivedSub', [s?.period ?? ''])}
        />
        <WfTile
          icon="🆔"
          label={t('trf.w.t.reissued')}
          src="SYS"
          big={s?.reissued ?? '—'}
          sub={t('trf.w.t.reissuedSub')}
          tone="pur"
        />
        <WfTile
          icon="🏦"
          label={t('trf.w.t.cpf')}
          src="RULE"
          big={t('trf.w.t.unchanged')}
          sub={t('trf.w.t.cpfSub')}
          tone="ok"
        />
        <WfTile
          icon="✍️"
          label={t('trf.w.t.checker')}
          src="SYS"
          big={s?.withChecker ?? '—'}
          sub={t('trf.w.t.checkerSub')}
          tone="warn"
        />
      </Tiles>
      <Problem error={summary.error} />
      <Tbl
        title={t('trf.w.register')}
        acts={
          <>
            <Explained label={t('trf.w.serviceRegister')} why={t('trf.w.why.service')} kind="sm" />
            {mayExport ? (
              <ExportButton
                href={registerUrls.transfersExport()}
                what={t('trf.w.register')}
                label={t('m01.w.exportShort')}
                className="btn sm"
              />
            ) : (
              <Explained label={t('m01.w.exportShort')} why={t('trf.w.why.export')} kind="sm" />
            )}
          </>
        }
        cols={[
          { t: t('trf.w.col.pan'), s: 'HRMS' },
          { t: t('trf.w.col.old'), s: 'SYS' },
          { t: t('trf.w.col.new'), s: 'SYS' },
          { t: t('trf.w.col.origin'), s: 'HRMS' },
          { t: t('trf.w.col.destination'), s: 'HRMS' },
          { t: t('trf.w.col.cpf'), s: 'SYS' },
          { t: t('trf.w.col.effective'), s: 'HRMS' },
          { t: t('trf.w.col.task'), s: 'SYS' },
          { t: t('trf.w.col.status'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={registerKeys.transfers}
          fetchPage={registerPages.transfers}
          empty={<p className="fl-hint">{t('trf.none')}</p>}
          onRows={onRows}
        >
          {(rows) =>
            rows.map((x) => (
              <tr key={x.id} className="fl-pick" onClick={() => setPicked(x)}>
                <td>{x.panMasked ?? '—'}</td>
                <td>{x.oldStaffNo ?? '—'}</td>
                <td>{x.newStaffNo ?? '—'}</td>
                <td>{x.origin ?? '—'}</td>
                <td>{x.destination ?? '—'}</td>
                <td>{x.cpfMemberId ? t('trf.w.unchangedId', [x.cpfMemberId]) : '—'}</td>
                <td>{date(x.effectiveDate)}</td>
                <td>{x.taskNo ?? '—'}</td>
                <td>
                  {/* A transfer raised here has no I-21 task: its own decision is its status (SRS-G-1). */}
                  {x.taskStatus === null && x.approvalStatus !== null ? (
                    <span className={`pill ${statusPill(x.approvalStatus)}`}>
                      {t(`status.${x.approvalStatus}`)}
                    </span>
                  ) : (
                    <span className={`pill ${taskPill(x.taskStatus)}`}>
                      {t(`evt.w.status.${x.taskStatus ?? 'NONE'}`)}
                    </span>
                  )}
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <Panel
          title={t('trf.w.rules')}
          items={[
            { icon: '🪪', title: t('trf.w.rule.1'), src: 'HRMS', text: t('trf.w.rule.1m'), tone: 'info' },
            { icon: '🔚', title: t('trf.w.rule.2'), src: 'SYS', text: t('trf.w.rule.2m'), tone: 'gray' },
            { icon: '🏦', title: t('trf.w.rule.3'), src: 'RULE', text: t('trf.w.rule.3m'), tone: 'ok' },
            { icon: '↪', title: t('trf.w.rule.4'), src: 'SYS', text: t('trf.w.rule.4m'), tone: 'info' },
          ]}
        />
        <KvCard
          title={t('trf.w.detail', [picked?.panMasked ?? '—', picked?.employee ?? ''])}
          rows={[
            {
              k: t('trf.w.d.numbers'),
              v: `${picked?.oldStaffNo ?? '—'} → ${picked?.newStaffNo ?? '—'}`,
              s: 'SYS',
            },
            {
              k: t('trf.w.d.route'),
              v: `${picked?.origin ?? '—'} → ${picked?.destination ?? '—'}`,
              s: 'HRMS',
            },
            {
              k: t('trf.w.d.effective'),
              v: picked ? t('trf.w.d.fromHrms', [date(picked.effectiveDate)]) : '—',
              s: 'HRMS',
            },
            {
              k: t('trf.w.col.cpf'),
              v: picked?.cpfMemberId ? t('trf.w.d.cpf', [picked.cpfMemberId]) : '—',
              s: 'RULE',
            },
            { k: t('trf.w.d.ledger'), v: t('trf.w.d.ledgerText'), s: 'CALC' },
            { k: t('trf.w.d.ff'), v: t('trf.w.d.ffText'), s: 'RULE' },
          ]}
        />
      </div>
      <TransferOrders row={picked} />
      <details className="fl-mt">
        <summary>{t('trf.w.closed')}</summary>
        <RecordedTransfers />
      </details>
    </div>
  );
}

/** The order of the picked transfer, on its I-21 task: the Payroll Maker attaches it there (SRS l.1361). */
function TransferOrders({ row }: { row: TransferRow | null }) {
  const { t } = useI18n();
  const me = useMe();
  const taskId = row?.taskId ?? null;
  const stored = useTaskDocuments(taskId ?? 0, taskId !== null);
  const types = useDocumentTypes(true);
  const attach = useAttachTaskOrder();
  const mayAttach = can(me.data, 'I-21', 'SUBMIT');
  return (
    <OrderCopyCard
      key={taskId ?? 0}
      hint={t('trf.w.ordHint')}
      canAttach={mayAttach && taskId !== null}
      readOnly={!mayAttach}
      stored={taskId === null ? [] : stored.data}
      storedError={stored.error ?? types.error}
      fileHref={(fileId) => taskDocumentUrl(taskId ?? 0, fileId)}
      today={todayIso()}
      attachmentRequired
      referenceRequired={(code) => types.data?.find((d) => d.code === code)?.referenceRequired ?? false}
      officeRequired
      onAttach={async (o) => {
        if (taskId === null) return;
        for (const file of o.files) {
          await attach.mutateAsync({
            id: taskId,
            copy: {
              documentType: o.documentType,
              reference: o.reference,
              orderDate: o.orderDate,
              issuingAuthority: o.issuingOffice,
              file,
            },
          });
        }
      }}
    />
  );
}

/**
 * The transfers recorded in Payroll and their History (live data of employee_transfer). Recorded by the Establishment /
 * HR maker of the releasing unit and approved by its Payroll Checker; the approval moves the employee, reissues the staff
 * number if one is given and withdraws every access right held at the old unit. Closed until pressed: the pack draws no
 * Raise Transfer (WF-230).
 */
function RecordedTransfers() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const record = useRecordTransfer();
  const mayCreate = can(me.data, 'M-11', 'CREATE');
  const [open, setOpen] = useState(false);
  const destinations = useDestinations(open && mayCreate);
  const empty = {
    eis: '',
    toLocation: '',
    transferDate: '',
    relievingDate: '',
    joiningDate: '',
    joiningTime: '',
    orderReference: '',
    newStaffNumber: '',
    splitWage: false,
    fromUnitDays: '',
    toUnitDays: '',
    reason: '',
  };
  const [form, setForm] = useState(empty);
  const [error, setError] = useState<unknown>(null);
  // SRS-16-18 / SRS-G-31 (US-GAP-26): the order copy, unless HRMS holds the order letter.
  const [heldInHrms, setHeldInHrms] = useState(false);
  const [order, setOrder] = useState<OrderDraft>(EMPTY_ORDER);
  const types = useDocumentTypes();
  const today = todayIso();

  async function submit() {
    setError(null);
    try {
      await record.mutateAsync({
        eis: form.eis,
        body: {
          toLocation: form.toLocation,
          transferDate: form.transferDate,
          relievingDate: form.relievingDate || null,
          joiningDate: form.joiningDate || null,
          joiningTime: form.joiningTime || null,
          orderReference: form.orderReference || null,
          newStaffNumber: form.newStaffNumber || null,
          splitWage: form.splitWage,
          fromUnitDays: form.splitWage && form.fromUnitDays !== '' ? Number(form.fromUnitDays) : null,
          toUnitDays: form.splitWage && form.toUnitDays !== '' ? Number(form.toUnitDays) : null,
          reason: form.reason,
          orderHeldInHrms: heldInHrms,
        },
        copy:
          !heldInHrms && order.file
            ? {
                documentType: order.documentType,
                reference: order.reference.trim() || form.orderReference.trim(),
                orderDate: order.orderDate,
                issuingAuthority: order.issuingAuthority.trim(),
                file: order.file,
              }
            : undefined,
      });
      toast(t('trf.submitted', [form.eis]));
      setOpen(false);
      setForm(empty);
      setOrder(EMPTY_ORDER);
      setHeldInHrms(false);
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  const valid =
    form.eis.trim() !== '' &&
    form.toLocation !== '' &&
    form.transferDate !== '' &&
    form.reason.trim() !== '' &&
    form.orderReference.trim() !== '' &&
    (heldInHrms || orderComplete(order, types.data ?? [], today)) &&
    (!form.splitWage || (form.fromUnitDays !== '' && form.toUnitDays !== ''));

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sub2">{t('trf.hint')}</span>
        <div className="spacer" />
        {mayCreate && (
          <button
            type="button"
            className="btn pri"
            onClick={() => {
              setOpen(true);
              setError(null);
            }}
          >
            {t('trf.record')}
          </button>
        )}
      </div>
      <Problem error={open ? null : error} />
      {open && (
        <Card title={t('trf.record')}>
          <Problem error={error ?? destinations.error} />
          <div className="bd rf-grid">
            <div className="rf-field">
              <label htmlFor="trf-eis">{t('evt.eis')}</label>
              <input
                id="trf-eis"
                maxLength={20}
                value={form.eis}
                onChange={(e) => setForm({ ...form, eis: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="trf-to">{t('trf.to')}</label>
              <select
                id="trf-to"
                value={form.toLocation}
                onChange={(e) => setForm({ ...form, toLocation: e.target.value })}
              >
                <option value="">—</option>
                {(destinations.data ?? []).map((d) => (
                  <option key={d.code} value={d.code}>
                    {d.label}
                  </option>
                ))}
              </select>
            </div>
            <div className="rf-field">
              <label htmlFor="trf-date">{t('trf.date')}</label>
              <input
                id="trf-date"
                type="date"
                value={form.transferDate}
                onChange={(e) => setForm({ ...form, transferDate: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="trf-rel">{t('trf.relieving')}</label>
              <input
                id="trf-rel"
                type="date"
                value={form.relievingDate}
                onChange={(e) => setForm({ ...form, relievingDate: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="trf-join">{t('trf.joining')}</label>
              <input
                id="trf-join"
                type="date"
                value={form.joiningDate}
                onChange={(e) => setForm({ ...form, joiningDate: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="trf-time">{t('trf.joiningTime')}</label>
              <input
                id="trf-time"
                type="time"
                value={form.joiningTime}
                onChange={(e) => setForm({ ...form, joiningTime: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="trf-order">{t('evt.order')}</label>
              <input
                id="trf-order"
                // SRS l.1353: Order Number, Alphanumeric (60).
                maxLength={60}
                value={form.orderReference}
                onChange={(e) => setForm({ ...form, orderReference: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label className="fl-check">
                <input
                  type="checkbox"
                  checked={heldInHrms}
                  onChange={(e) => setHeldInHrms(e.target.checked)}
                />
                {t('m11.heldInHrms')}
              </label>
            </div>
            {!heldInHrms && (
              <fieldset className="rf-grid">
                <legend>{t('m11.orderCopy')}</legend>
                <OrderFields
                  idPrefix="trf-copy"
                  value={order}
                  types={types.data ?? []}
                  today={today}
                  onChange={setOrder}
                />
              </fieldset>
            )}
            <p className="fl-hint">{t('m11.orderRequired')}</p>
            <div className="rf-field">
              <label htmlFor="trf-num">{t('trf.newNumber')}</label>
              <input
                id="trf-num"
                maxLength={20}
                value={form.newStaffNumber}
                onChange={(e) => setForm({ ...form, newStaffNumber: e.target.value })}
              />
              <span className="hint">{t('trf.newNumberHint')}</span>
            </div>
            <div className="rf-field">
              <label htmlFor="trf-split">
                <input
                  id="trf-split"
                  type="checkbox"
                  checked={form.splitWage}
                  onChange={(e) => setForm({ ...form, splitWage: e.target.checked })}
                />{' '}
                {t('trf.splitWage')}
              </label>
            </div>
            {form.splitWage && (
              <>
                <div className="rf-field">
                  <label htmlFor="trf-fd">{t('trf.fromDays')}</label>
                  <input
                    id="trf-fd"
                    type="number"
                    min={0}
                    max={31}
                    value={form.fromUnitDays}
                    onChange={(e) => setForm({ ...form, fromUnitDays: e.target.value })}
                  />
                </div>
                <div className="rf-field">
                  <label htmlFor="trf-td">{t('trf.toDays')}</label>
                  <input
                    id="trf-td"
                    type="number"
                    min={0}
                    max={31}
                    value={form.toUnitDays}
                    onChange={(e) => setForm({ ...form, toUnitDays: e.target.value })}
                  />
                </div>
              </>
            )}
            <div className="rf-field span2">
              <label htmlFor="trf-reason">{t('common.reason')}</label>
              <TextAreaField
                id="trf-reason"
                maxLength={500}
                value={form.reason}
                onChange={(e) => setForm({ ...form, reason: e.target.value })}
              />
            </div>
          </div>
          <p className="fl-hint">{t('trf.rightsNote')}</p>
          <p className="fl-hint">{t('evt.approvalNote')}</p>
          <div className="rf-actbar">
            <button type="button" className="btn" onClick={() => setOpen(false)}>
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className="btn pri"
              disabled={!valid || record.isPending}
              onClick={() => void submit()}
            >
              {t('common.submitForApproval')}
            </button>
          </div>
        </Card>
      )}
      <Card title={t('trf.recent')}>
        <div className="wrap">
          <ServerPagination
            queryKey={eventKeys.recentTransfers}
            fetchPage={eventPages.recentTransfers}
            empty={<p className="fl-hint">{t('trf.none')}</p>}
          >
            {(rows) => (
              <table className="t">
                <thead>
                  <tr>
                    <th>
                      {t('evt.employee')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('trf.fromTo')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('trf.date')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('trf.joining')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('evt.order')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('evt.status')}
                      <Src s="SYS" />
                    </th>
                    <th aria-label={t('common.actions')} />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((x) => (
                    <tr key={x.id}>
                      <td>
                        <b>{x.eis}</b>
                        <div className="sub2">{x.employeeName}</div>
                      </td>
                      <td>
                        {x.fromLocation ?? '—'} → {x.toLocation ?? '—'}
                        {x.splitWage && (
                          <div className="sub2">
                            {t('trf.splitDays', [x.fromUnitDays ?? 0, x.toUnitDays ?? 0])}
                          </div>
                        )}
                      </td>
                      <td>{date(x.transferDate)}</td>
                      <td>
                        {x.joiningDate ? date(x.joiningDate) : '—'}
                        {x.joiningTime ? ` ${x.joiningTime}` : ''}
                      </td>
                      <td>
                        {x.orderReference ?? '—'}
                        {x.lpcReference ? (
                          <div className="sub2">{t('trf.lpcShort', [x.lpcReference])}</div>
                        ) : null}
                      </td>
                      <td>
                        <span className={`pill ${statusPill(x.status)}`}>{t(`status.${x.status}`)}</span>
                      </td>
                      <td>
                        <HistoryButton
                          entity="employee_transfer"
                          recordKey={String(x.id)}
                          label={`${x.eis ?? ''} ${x.fromLocation ?? '—'} → ${x.toLocation ?? '—'}`}
                        />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </ServerPagination>
        </div>
      </Card>
    </div>
  );
}
