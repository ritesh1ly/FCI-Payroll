'use client';

import { useState } from 'react';
import {
  EMPTY_ORDER,
  OrderFields,
  orderComplete,
  todayIso,
  type OrderDraft as CopyDraft,
  useDocumentTypes,
} from '@/modules/general';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { businessYymm } from '@/shared/time';
import { can, useMe } from '@/shared/session';
import {
  Card,
  Empty,
  ExportButton,
  Problem,
  ReasonDialog,
  ServerPagination,
  Src,
  useConfirmCancel,
  useToast,
  TextAreaField,
  MonthField,
  dateTime,
} from '@/shared/ui';
import type { OrderCopy, OrderPreview, OrderRow, PaymentRow } from '../api/penaltyApi';
import {
  changeable,
  isYymm,
  revocable,
  orderProblems,
  type OrderDraft,
  paymentProblems,
  type PaymentDraft,
  revokeProblems,
} from '../business/penaltyRules';
import {
  orderPage,
  paymentPage,
  penaltyKeys,
  penaltyReportUrl,
  requestPage,
  useOrderDetail,
  usePenaltyActions,
} from '../orchestration/penaltyQueries';

function rupees(n: number | null | undefined): string {
  return n === null || n === undefined
    ? '—'
    : `₹${Number(n).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function asCopy(o: CopyDraft): OrderCopy | null {
  return o.file
    ? {
        documentType: o.documentType,
        reference: o.reference,
        orderDate: o.orderDate,
        issuingAuthority: o.issuingAuthority,
        file: o.file,
      }
    : null;
}

/** The keyboard a numeric box asks for: an amount (12,2) or a whole count / code. */
const NUMERIC_KIND: Record<string, 'decimal' | 'numeric' | undefined> = {
  decimal: 'decimal',
  count: 'numeric',
};

const STATUSES = ['ACTIVE', 'PARTLY_REVOKED', 'REVOKED', 'COMPLETED', 'PAUSED_FNF', 'CANCELLED'] as const;

/**
 * I-06 Penalty, Recovery & Over-and-Above (plan 12; SRS-14-1 … 14-20). The unit's orders by Penalty
 * ID with what is recovered and outstanding, each order's schedule and revokes; the requests
 * waiting for the Checker or returned with the reason; the Payroll Maker records a recovery order
 * against its copy (checked first: the schedule, the ceiling and the net-pay floor), revokes or
 * cancels an order, and records an over-and-above payment; the Penalty Report. An employee's
 * disciplinary penalty arrives from HRMS through I-21 and is read-only here.
 */
export function PenaltyRecovery({
  initialOpen = null,
  heldCopy = null,
}: {
  /** The order the drawn register opened. */
  initialOpen?: number | null;
  /** The file held by the drawn order card, stored with the record on submit (SRS-G-31). */
  heldCopy?: CopyDraft | null;
} = {}) {
  const { t } = useI18n();
  const me = useMe();
  const may = (action: string) => can(me.data, 'I-06', action);
  const [eis, setEis] = useState('');
  const [status, setStatus] = useState('');
  const [openId, setOpenId] = useState<number | null>(initialOpen);
  // SRS l.2997-2998: a returned request goes back to the Maker to correct and resubmit.
  const [correcting, setCorrecting] = useState<{ key: number; draft: Partial<OrderDraft> } | null>(null);
  const filter = { eis: eis.trim() || undefined, status: status || undefined };
  if (!may('VIEW')) return null;
  return (
    <>
      <Card title={t('i06.orders')}>
        <div className="toolbar">
          <label>
            {t('m01.eis')} <input maxLength={20} value={eis} onChange={(e) => setEis(e.target.value)} />
          </label>
          <label>
            {t('i06.status')}{' '}
            <select value={status} onChange={(e) => setStatus(e.target.value)}>
              <option value="">{t('i06.allStatuses')}</option>
              {STATUSES.map((s) => (
                <option key={s} value={s}>
                  {t(`i06.status.${s}`)}
                </option>
              ))}
            </select>
          </label>
        </div>
        <ServerPagination
          queryKey={penaltyKeys.list(filter)}
          fetchPage={orderPage(filter)}
          empty={<Empty text={t('i06.none')} />}
        >
          {(rows) => <Orders rows={rows} onOpen={setOpenId} />}
        </ServerPagination>
      </Card>
      {openId !== null && <OrderPanel key={openId} id={openId} onClose={() => setOpenId(null)} />}
      <Card title={t('i06.requests')}>
        <ServerPagination
          queryKey={penaltyKeys.requests()}
          fetchPage={requestPage()}
          empty={<Empty text={t('i06.noRequests')} />}
        >
          {(rows) => (
            <table className="t" aria-label={t('i06.requests')}>
              <thead>
                <tr>
                  <th>
                    {t('i06.penaltyId')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('i06.member')}
                    <Src s="HRMS" />
                  </th>
                  <th>
                    {t('i06.summary')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('i06.amount')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('i06.status')}
                    <Src s="SYS" />
                  </th>
                  <th aria-label={t('common.actions')} />
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.approvalId}>
                    <td>{r.penaltyRef ?? ''}</td>
                    <td>
                      {r.employee} ({r.eis})
                    </td>
                    <td>{r.summary}</td>
                    <td>{rupees(r.amount)}</td>
                    <td>
                      {t(`i06.request.${r.status}`)}
                      {r.returnReason && <div className="pill p-warn">{r.returnReason}</div>}
                    </td>
                    <td>
                      {r.status === 'RETURNED' &&
                        (may('CREATE') ? (
                          <button
                            type="button"
                            className="ia"
                            aria-label={t('i06.correctOf', [r.penaltyRef ?? r.approvalId])}
                            onClick={() =>
                              setCorrecting({
                                key: r.approvalId,
                                draft: { eis: r.eis, amount: r.amount === null ? '' : String(r.amount) },
                              })
                            }
                          >
                            {t('i06.correct')}
                          </button>
                        ) : (
                          <button type="button" className="ia" disabled title={t('i06.correctWhy')}>
                            {t('i06.correct')}
                          </button>
                        ))}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </ServerPagination>
      </Card>
      {may('CREATE') && (
        <OrderForm
          key={correcting?.key ?? 'new'}
          correcting={correcting}
          heldCopy={heldCopy}
          onDone={() => setCorrecting(null)}
        />
      )}
      <Payments mayPay={may('OVER_ABOVE')} mayCancel={may('CANCEL')} />
      {may('EXPORT') && <Report />}
    </>
  );
}

function Orders({ rows, onOpen }: { rows: OrderRow[]; onOpen: (id: number) => void }) {
  const { t } = useI18n();
  return (
    <table className="t" aria-label={t('i06.orders')}>
      <thead>
        <tr>
          <th>
            {t('i06.penaltyId')}
            <Src s="SYS" />
          </th>
          <th>
            {t('i06.member')}
            <Src s="HRMS" />
          </th>
          <th>
            {/* SRS l.3020: the nature is the Maker's selection on I-06 (ENT) or HRMS's (per row). */}
            {t('i06.nature')}
            <Src s="ENT" />
          </th>
          <th>
            {t('i06.source')}
            <Src s="SYS" />
          </th>
          <th>
            {t('i06.amount')}
            <Src s="ENT" />
          </th>
          <th>
            {t('i06.recovered')}
            <Src s="CALC" />
          </th>
          <th>
            {t('i06.outstanding')}
            <Src s="CALC" />
          </th>
          <th>
            {t('i06.status')}
            <Src s="SYS" />
          </th>
        </tr>
      </thead>
      <tbody>
        {rows.map((o) => (
          <tr key={o.id} data-reference={o.penaltyRef}>
            <td>
              <button type="button" className="lnk" onClick={() => onOpen(o.id)}>
                {o.penaltyRef}
              </button>
            </td>
            <td>
              {o.employee} ({o.eis})
            </td>
            <td>
              {t(`i06.nature.${o.nature}`)}
              {o.source === 'HRMS' && <Src s="HRMS" />}
            </td>
            <td>{t(`i06.source.${o.source}`)}</td>
            <td>
              {rupees(o.amount)}
              {o.source === 'HRMS' && <Src s="HRMS" />}
            </td>
            <td>{rupees(o.recovered)}</td>
            <td>{rupees(o.outstanding)}</td>
            <td>
              {t(`i06.status.${o.status}`)}
              {o.backdated && <span className="pill p-warn"> {t('i06.backdated')}</span>}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** One order: its schedule, its revokes and the changes the Maker may submit. */
function OrderPanel({ id, onClose }: { id: number; onClose: () => void }) {
  const { t } = useI18n();
  const me = useMe();
  const detail = useOrderDetail(id);
  const d = detail.data;
  if (!d) return <Problem error={detail.error} />;
  const o = d.order;
  return (
    <section className="card" aria-label={o.penaltyRef}>
      <div className="hd">
        <h3>{t('i06.orderTitle', [o.penaltyRef, o.eis])}</h3>
        <button type="button" className="btn" onClick={onClose}>
          {t('common.close')}
        </button>
      </div>
      <p className="sub2">
        {o.orderReference} · {t(`i06.nature.${o.nature}`)} · {t(`i06.source.${o.source}`)}
      </p>
      {d.schedule.length > 0 && (
        <table className="t" aria-label={t('i06.schedule')}>
          <thead>
            <tr>
              <th>
                {t('i06.month')}
                <Src s="CALC" />
              </th>
              <th>
                {t('i06.scheduled')}
                <Src s="CALC" />
              </th>
              <th>
                {t('i06.recovered')}
                <Src s="CALC" />
              </th>
              <th>
                {t('i06.lineStatus')}
                <Src s="SYS" />
              </th>
            </tr>
          </thead>
          <tbody>
            {d.schedule.map((l) => (
              <tr key={l.payrollYymm}>
                <td>{l.payrollYymm}</td>
                <td>{rupees(l.scheduled)}</td>
                <td>{rupees(l.recovered)}</td>
                <td>
                  {t(`i06.line.${l.status}`)}
                  {l.reason && <span className="sub2"> {l.reason}</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {d.revokes.length > 0 && (
        <ul className="fl-list" aria-label={t('i06.revokes')}>
          {d.revokes.map((r) => (
            <li key={r.id}>
              {t(`i06.revokeType.${r.type}`)} · {r.orderReference} · {rupees(r.amount)} ·{' '}
              {t(`i06.revokeStatus.${r.status}`)}
            </li>
          ))}
        </ul>
      )}
      {o.source === 'HRMS' && <p className="fl-hint">{t('i06.hrmsReadOnly')}</p>}
      {revocable(o.status) && can(me.data, 'I-06', 'REVOKE') && <RevokeForm order={o} />}
      {changeable(o.status, o.source) && can(me.data, 'I-06', 'CANCEL') && <CancelForm order={o} />}
    </section>
  );
}

function RevokeForm({ order }: { order: OrderRow }) {
  const { t } = useI18n();
  const toast = useToast();
  const actions = usePenaltyActions();
  const types = useDocumentTypes(true);
  const [type, setType] = useState<'PARTIAL' | 'FULL'>('FULL');
  const [amount, setAmount] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [reason, setReason] = useState('');
  const [copy, setCopy] = useState<CopyDraft>(EMPTY_ORDER);
  const [error, setError] = useState<unknown>(null);
  const today = todayIso();
  const draft = {
    type,
    amount,
    fromYymm: from || null,
    toYymm: to || null,
    orderReference: copy.reference,
    orderDate: copy.orderDate,
    reason,
  };
  const problems = [
    ...revokeProblems(draft, order.amount),
    ...(orderComplete(copy, types.data ?? [], today) ? [] : ['i06.bad.copy']),
  ];
  async function submit() {
    const c = asCopy(copy);
    if (!c) return;
    setError(null);
    try {
      await actions.revoke.mutateAsync({
        id: order.id,
        body: { ...draft, amount: amount.trim() === '' ? null : Number(amount) },
        copy: c,
      });
      toast(t('i06.revokeSent', [order.penaltyRef]));
    } catch (cause) {
      setError(cause);
    }
  }
  return (
    <div className="bd" aria-label={t('i06.revoke')}>
      <h4>{t('i06.revoke')}</h4>
      <Problem error={error} />
      <div className="rf-grid">
        <div className="rf-field">
          <label htmlFor="i06-revoke-type">{t('i06.revokeTypeLabel')}</label>
          <select
            id="i06-revoke-type"
            value={type}
            onChange={(e) => setType(e.target.value as 'PARTIAL' | 'FULL')}
          >
            <option value="FULL">{t('i06.revokeType.FULL')}</option>
            <option value="PARTIAL">{t('i06.revokeType.PARTIAL')}</option>
          </select>
        </div>
        {type === 'PARTIAL' && (
          <>
            <div className="rf-field">
              <label htmlFor="i06-revoke-amount">{t('i06.waiveAmount')}</label>
              <input
                id="i06-revoke-amount"
                inputMode="decimal"
                maxLength={15}
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="i06-revoke-from">{t('i06.waiveFrom')}</label>
              <MonthField id="i06-revoke-from" value={from} onChange={setFrom} />
            </div>
            <div className="rf-field">
              <label htmlFor="i06-revoke-to">{t('i06.waiveTo')}</label>
              <MonthField id="i06-revoke-to" value={to} onChange={setTo} />
            </div>
          </>
        )}
        <div className="rf-field span2">
          <label htmlFor="i06-revoke-reason">{t('i06.reason')}</label>
          <TextAreaField
            id="i06-revoke-reason"
            maxLength={1000}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </div>
        <OrderFields idPrefix="i06r" value={copy} types={types.data ?? []} today={today} onChange={setCopy} />
      </div>
      <button
        type="button"
        className="btn pri"
        disabled={problems.length > 0 || actions.revoke.isPending}
        title={problems.length > 0 ? t(problems[0] ?? '') : undefined}
        onClick={() => void submit()}
      >
        {t('i06.sendRevoke')}
      </button>
    </div>
  );
}

function CancelForm({ order }: { order: OrderRow }) {
  const { t } = useI18n();
  const toast = useToast();
  const actions = usePenaltyActions();
  const types = useDocumentTypes(true);
  const [reason, setReason] = useState('');
  const [copy, setCopy] = useState<CopyDraft>(EMPTY_ORDER);
  const [error, setError] = useState<unknown>(null);
  const confirmCancel = useConfirmCancel();
  const today = todayIso();
  const ok = reason.trim() !== '' && orderComplete(copy, types.data ?? [], today);
  async function submit() {
    const c = asCopy(copy);
    if (!c) return;
    setError(null);
    try {
      await actions.cancel.mutateAsync({ id: order.id, reason: reason.trim(), copy: c });
      toast(t('i06.cancelSent', [order.penaltyRef]));
    } catch (cause) {
      setError(cause);
    }
  }
  return (
    <div className="bd" aria-label={t('i06.cancel')}>
      <h4>{t('i06.cancel')}</h4>
      <Problem error={error} />
      {confirmCancel.dialog}
      <div className="rf-grid">
        <div className="rf-field span2">
          <label htmlFor="i06-cancel-reason">{t('i06.cancelReason')}</label>
          <TextAreaField
            id="i06-cancel-reason"
            maxLength={1000}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </div>
        <OrderFields idPrefix="i06c" value={copy} types={types.data ?? []} today={today} onChange={setCopy} />
      </div>
      <button
        type="button"
        className="btn"
        disabled={!ok || actions.cancel.isPending}
        title={ok ? undefined : t('i06.bad.cancel')}
        onClick={() => confirmCancel.ask(order.penaltyRef, () => void submit())}
      >
        {t('i06.sendCancel')}
      </button>
    </div>
  );
}

const EMPTY_DRAFT: OrderDraft = {
  eis: '',
  orderReference: '',
  effectiveDate: '',
  amount: '',
  instalments: '',
  startYymm: '',
};

function OrderForm({
  correcting = null,
  heldCopy = null,
  onDone = () => undefined,
}: {
  /** SRS l.2997-2998: a returned request corrected and resubmitted (its particulars prefilled). */
  correcting?: { key: number; draft: Partial<OrderDraft> } | null;
  heldCopy?: CopyDraft | null;
  onDone?: () => void;
} = {}) {
  const { t } = useI18n();
  const toast = useToast();
  const actions = usePenaltyActions();
  const types = useDocumentTypes(true);
  const [d, setD] = useState<OrderDraft>(() =>
    correcting ? { ...EMPTY_DRAFT, ...correcting.draft } : EMPTY_DRAFT,
  );
  // SRS l.2995: at a below-floor breach the Maker may defer the recovery to F&F instead.
  const [deferToFnf, setDeferToFnf] = useState(false);
  const [copy, setCopy] = useState<CopyDraft>(heldCopy ?? EMPTY_ORDER);
  const [nature, setNature] = useState('RECOVERY');
  const [periodTo, setPeriodTo] = useState('');
  const [preview, setPreview] = useState<OrderPreview | null>(null);
  const [error, setError] = useState<unknown>(null);
  const today = todayIso();
  // SRS-14-8: a labour order that recovers nothing has no amount, instalments or start month.
  const recovers = nature === 'RECOVERY';
  const problems = recovers
    ? orderProblems(d)
    : orderProblems({ ...d, amount: '1', instalments: '1', startYymm: '209901' });
  const set = (patch: Partial<OrderDraft>) => {
    setD({ ...d, ...patch });
    setPreview(null);
    setDeferToFnf(false);
  };
  const input = () =>
    recovers
      ? {
          eis: d.eis.trim(),
          nature,
          orderReference: d.orderReference.trim(),
          effectiveDate: d.effectiveDate,
          amount: Number(d.amount),
          instalments: Number(d.instalments),
          startYymm: d.startYymm,
          deferToFnf: deferToFnf && preview?.belowFloor === true,
        }
      : {
          eis: d.eis.trim(),
          nature,
          orderReference: d.orderReference.trim(),
          effectiveDate: d.effectiveDate,
          periodTo: periodTo || null,
        };
  async function check() {
    setError(null);
    try {
      setPreview(await actions.preview.mutateAsync(input()));
    } catch (cause) {
      setError(cause);
    }
  }
  async function submit() {
    const c = asCopy(copy);
    if (!c) return;
    setError(null);
    try {
      await actions.submit.mutateAsync({ body: input(), copy: c });
      toast(t('i06.orderSent'));
      setD(EMPTY_DRAFT);
      setCopy(EMPTY_ORDER);
      setPreview(null);
      setDeferToFnf(false);
      onDone();
    } catch (cause) {
      setError(cause);
    }
  }
  const field = (key: keyof OrderDraft, label: string, max: number, type = 'text') => (
    <div className="rf-field">
      <label htmlFor={`i06-${key}`}>{t(label)}</label>
      {type === 'month' ? (
        <MonthField id={`i06-${key}`} value={d[key]} onChange={(v) => set({ [key]: v })} />
      ) : (
        <input
          id={`i06-${key}`}
          type={NUMERIC_KIND[type] ? 'text' : type}
          maxLength={max}
          inputMode={NUMERIC_KIND[type]}
          value={d[key]}
          onChange={(e) => set({ [key]: e.target.value })}
        />
      )}
    </div>
  );
  return (
    <Card title={t('i06.newOrder')}>
      <p className="sub2">{t('i06.newOrderHint')}</p>
      {correcting && (
        <p className="fl-hint" role="status">
          {t('i06.correcting', [correcting.key])}{' '}
          <button type="button" className="ia" onClick={onDone}>
            {t('common.cancel')}
          </button>
        </p>
      )}
      <Problem error={error} />
      <div className="rf-grid">
        {field('eis', 'i06.memberEis', 20)}
        <div className="rf-field">
          <label htmlFor="i06-nature">
            {t('i06.natureField')} <Src s="ENT" />
          </label>
          {/* Plan 12 D12-2: an employee order keyed here is a recovery; an employee penalty arrives
              from HRMS through I-21. SRS-14-1, Q-12-1: a labour penalty of any nature is keyed here;
              the server refuses any other nature for an employee (PEN-422-HRMS-ONLY). */}
          <select
            id="i06-nature"
            value={nature}
            title={t('i06.natureFixed')}
            onChange={(e) => {
              setNature(e.target.value);
              setPreview(null);
              setDeferToFnf(false);
            }}
          >
            <ReferenceOptions list="PENALTY_NATURE" />
          </select>
        </div>
        {field('orderReference', 'i06.orderReference', 60)}
        {field('effectiveDate', 'i06.effectiveDate', 10, 'date')}
        {recovers ? (
          <>
            {field('amount', 'i06.amount', 15, 'decimal')}
            {field('instalments', 'i06.instalments', 3, 'count')}
            {field('startYymm', 'i06.startYymm', 6, 'month')}
          </>
        ) : (
          <div className="rf-field">
            <label htmlFor="i06-periodTo">{t('i06.periodTo')}</label>
            <input
              id="i06-periodTo"
              type="date"
              value={periodTo}
              onChange={(e) => setPeriodTo(e.target.value)}
            />
          </div>
        )}
      </div>
      <button
        type="button"
        className="btn"
        disabled={problems.length > 0 || actions.preview.isPending}
        title={problems.length > 0 ? t(problems[0] ?? '') : undefined}
        onClick={() => void check()}
      >
        {t('i06.check')}
      </button>
      {preview && (
        <div aria-label={t('i06.preview')}>
          <p>{t('i06.previewMember', [preview.name, preview.eis, rupees(preview.currentBasic)])}</p>
          {/* SRS-14-18 (l.2979): the Basic after the order. */}
          {preview.proposedBasic !== undefined && preview.proposedBasic !== null && (
            <p className="sub2">{t('i06.proposedBasic', [rupees(preview.proposedBasic)])}</p>
          )}
          {preview.adjusted && (
            <p className="pill p-warn">
              {t('i06.adjusted', [rupees(preview.schedule[0]?.amount), preview.instalments])}
            </p>
          )}
          {preview.belowFloor && (
            <>
              <p className="pill p-warn">
                {t('i06.belowFloor', [rupees(preview.netAfter), rupees(preview.floor)])}
              </p>
              {/* SRS l.2995: instead, extend the instalments (change them and check again) or defer to F&F. */}
              <p className="sub2">{t('i06.belowFloorOptions')}</p>
              <label>
                <input
                  type="checkbox"
                  checked={deferToFnf}
                  onChange={(e) => setDeferToFnf(e.target.checked)}
                />{' '}
                {t('i06.deferToFnf')}
              </label>
            </>
          )}
          {preview.backdated && <p className="pill p-warn">{t('i06.backdatedHint')}</p>}
          <table className="t" aria-label={t('i06.schedule')}>
            <thead>
              <tr>
                <th>
                  {t('i06.month')}
                  <Src s="CALC" />
                </th>
                <th>
                  {t('i06.scheduled')}
                  <Src s="CALC" />
                </th>
              </tr>
            </thead>
            <tbody>
              {preview.schedule.map((l) => (
                <tr key={l.payrollYymm}>
                  <td>{l.payrollYymm}</td>
                  <td>{rupees(l.amount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="rf-grid">
            <OrderFields
              idPrefix="i06o"
              value={copy}
              types={types.data ?? []}
              today={today}
              onChange={setCopy}
            />
          </div>
          <button
            type="button"
            className="btn pri"
            disabled={!orderComplete(copy, types.data ?? [], today) || actions.submit.isPending}
            title={orderComplete(copy, types.data ?? [], today) ? undefined : t('i06.bad.copy')}
            onClick={() => void submit()}
          >
            {t('i06.send')}
          </button>
        </div>
      )}
    </Card>
  );
}

const EMPTY_PAYMENT: PaymentDraft = {
  eis: '',
  payCode: '',
  amount: '',
  payrollYymm: '',
  reason: '',
  applicationDate: '',
  sanctionReference: '',
  sanctionDate: '',
};

function Payments({ mayPay, mayCancel }: { mayPay: boolean; mayCancel: boolean }) {
  const { t } = useI18n();
  const toast = useToast();
  const actions = usePenaltyActions();
  const [p, setP] = useState<PaymentDraft>(EMPTY_PAYMENT);
  const [error, setError] = useState<unknown>(null);
  const problems = paymentProblems(p);
  async function pay() {
    setError(null);
    try {
      await actions.pay.mutateAsync({
        eis: p.eis.trim(),
        payCode: Number(p.payCode),
        amount: Number(p.amount),
        payrollYymm: p.payrollYymm,
        reason: p.reason.trim(),
        applicationDate: p.applicationDate || null,
        sanctionReference: p.sanctionReference.trim(),
        sanctionDate: p.sanctionDate || null,
      });
      toast(t('i06.paymentSent'));
      setP(EMPTY_PAYMENT);
    } catch (cause) {
      setError(cause);
    }
  }
  // SRS-14-14 / SRS-14-20 / R-AUD: the Maker gives the reason for the cancellation; it is audited.
  const [cancelling, setCancelling] = useState<PaymentRow | null>(null);
  async function cancel(row: PaymentRow, reason: string) {
    setCancelling(null);
    setError(null);
    try {
      await actions.cancelPayment.mutateAsync({ id: row.id, reason });
      toast(t('i06.cancelSent', [String(row.id)]));
    } catch (cause) {
      setError(cause);
    }
  }
  const input = (key: keyof PaymentDraft, label: string, max: number, type = 'text') => (
    <div className={type === 'textarea' ? 'rf-field span2' : 'rf-field'}>
      <label htmlFor={`i06-p-${key}`}>{t(label)}</label>
      {type === 'month' ? (
        <MonthField id={`i06-p-${key}`} value={p[key]} onChange={(v) => setP({ ...p, [key]: v })} />
      ) : type === 'textarea' ? (
        <TextAreaField
          id={`i06-p-${key}`}
          maxLength={max}
          value={p[key]}
          onChange={(e) => setP({ ...p, [key]: e.target.value })}
        />
      ) : (
        <input
          id={`i06-p-${key}`}
          type={NUMERIC_KIND[type] ? 'text' : type}
          maxLength={max}
          inputMode={NUMERIC_KIND[type]}
          value={p[key]}
          onChange={(e) => setP({ ...p, [key]: e.target.value })}
        />
      )}
    </div>
  );
  return (
    <Card title={t('i06.payments')}>
      <Problem error={error} />
      {cancelling && (
        <ReasonDialog
          title={t('i06.paymentCancelTitle', [String(cancelling.id)])}
          note={t('i06.paymentCancelNote')}
          confirmLabel={t('i06.paymentCancelConfirm')}
          onConfirm={(reason) => void cancel(cancelling, reason)}
          onCancel={() => setCancelling(null)}
        />
      )}
      <ServerPagination
        queryKey={penaltyKeys.payments()}
        fetchPage={paymentPage()}
        empty={<Empty text={t('i06.noPayments')} />}
      >
        {(rows) => (
          <table className="t" aria-label={t('i06.payments')}>
            <thead>
              <tr>
                <th>
                  {t('i06.member')}
                  <Src s="HRMS" />
                </th>
                <th>
                  {t('i06.payCode')}
                  <Src s="RULE" />
                </th>
                <th>
                  {t('i06.amount')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('i06.sanction')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('i06.status')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('i06.invoiceStatus')}
                  <Src s="INTF" />
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>{r.eis}</td>
                  <td>
                    {r.payCode} {r.payName ?? ''} {r.taxable ? t('i06.taxable') : ''}
                  </td>
                  <td>{rupees(r.amount)}</td>
                  <td>
                    {r.sanctionReference} {r.sanctionDate ?? ''}
                  </td>
                  <td>
                    {t(`i06.payment.${r.status}`)}
                    {r.checkerReason && (
                      <div className="pill p-warn" title={t('i06.checkerReason')}>
                        {r.checkerReason}
                      </div>
                    )}
                  </td>
                  <td>
                    {/* SRS l.3030: the invoice status by its description, with the sent date. */}
                    {t(`i03.inv.${r.invoiceStatus}`)}
                    {r.invoiceSentAt && (
                      <div className="sub2">{t('i03.invoiceSentOn', [dateTime(r.invoiceSentAt)])}</div>
                    )}
                    {mayCancel && (r.status === 'SUBMITTED' || r.status === 'APPROVED') && (
                      <button type="button" className="btn" onClick={() => setCancelling(r)}>
                        {t('i06.cancelPayment')}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </ServerPagination>
      {mayPay && (
        <div aria-label={t('i06.newPayment')}>
          <h4>{t('i06.newPayment')}</h4>
          <div className="rf-grid">
            {input('eis', 'i06.memberEis', 20)}
            {input('payCode', 'i06.payCode', 5, 'count')}
            {input('amount', 'i06.amount', 15, 'decimal')}
            {input('payrollYymm', 'i06.month', 6, 'month')}
            {input('reason', 'i06.reason', 500, 'textarea')}
            {input('applicationDate', 'i06.applicationDate', 10, 'date')}
            {input('sanctionReference', 'i06.sanction', 60)}
            {input('sanctionDate', 'i06.sanctionDate', 10, 'date')}
          </div>
          <button
            type="button"
            className="btn pri"
            disabled={problems.length > 0 || actions.pay.isPending}
            title={problems.length > 0 ? t(problems[0] ?? '') : undefined}
            onClick={() => void pay()}
          >
            {t('i06.sendPayment')}
          </button>
        </div>
      )}
    </Card>
  );
}

/** SRS-14-19: the Penalty Report for a range of months. */
function Report() {
  const { t } = useI18n();
  const [from, setFrom] = useState(() => businessYymm());
  const [to, setTo] = useState(() => businessYymm());
  const ok = isYymm(from) && isYymm(to) && from <= to;
  return (
    <Card title={t('i06.report')}>
      <div className="toolbar">
        <label>
          {t('i14.from')} <MonthField value={from} onChange={setFrom} />
        </label>
        <label>
          {t('i14.to')} <MonthField value={to} onChange={setTo} />
        </label>
        {ok ? (
          <ExportButton
            href={penaltyReportUrl(from, to)}
            what={t('i06.report')}
            label={t('i06.reportExport')}
          />
        ) : (
          <button type="button" className="btn" disabled title={t('i14.badRange')}>
            {t('i06.reportExport')}
          </button>
        )}
      </div>
    </Card>
  );
}
