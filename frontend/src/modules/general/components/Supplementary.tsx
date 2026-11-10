'use client';

import { useCallback, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { can, useMe } from '@/shared/session';
import {
  amount,
  date,
  ExportButton,
  Problem,
  ReasonDialog,
  ServerPagination,
  useDialogFocus,
  useToast,
  withBase,
} from '@/shared/ui';
import { InvoiceCard } from '@/shared/ui/invoiceCard';
import { OrderCopyCard, type OrderCopyInput } from '@/shared/ui/orderCopy';
import { Act, Explained, KvCard, Locked, Panel, RowActs, Tbl, Tiles, WfTile } from '@/shared/ui/wireframe';
import type { SupplementaryPayment } from '../api/generalApi';
import {
  isAsciiText,
  isEis,
  monthName,
  monthsBack,
  sanctionAfterApplication,
  SCREEN_PAYMENT_TYPES,
  supplementaryCancellable,
  supplementarySubmitBlock,
  tone,
  yymmOf,
} from '../business/generalRules';
import {
  fileUrl,
  generalKeys,
  generalPages,
  supplementaryExportUrl,
  useCancelSupplementary,
  useCaptureSupplementary,
  useDocumentTypes,
  useStoredOrders,
  useSubmitSupplementary,
  useSupplementaryTiles,
} from '../orchestration/generalQueries';
import { todayIso } from './OrderFields';

/** The payment types of BR-CYC-010 as the "Payment types accepted" card groups them (labels, not values). */
const ACCEPTED: [string, string, 'RULE' | 'ENT'][] = [
  ['i23.acc.salary', 'i23.acc.salarySub', 'RULE'],
  ['i23.acc.pli', 'i23.acc.pliSub', 'RULE'],
  ['i23.acc.exGratia', 'i23.acc.exGratiaSub', 'ENT'],
  ['i23.acc.leave', 'i23.acc.leaveSub', 'RULE'],
  ['i23.acc.overAbove', 'i23.acc.overAboveSub', 'RULE'],
  ['i23.acc.cpf', 'i23.acc.cpfSub', 'RULE'],
  ['i23.acc.recovery', 'i23.acc.recoverySub', 'RULE'],
  ['i23.acc.cafeteria', 'i23.acc.cafeteriaSub', 'RULE'],
];

/**
 * I-23 Supplementary Payment (Post-Lock) as Wireframes v6.1 draws it (a frozen requirement per OD-121; SRS-G-33,
 * SRS-G-34, BR-CYC-010): "＋ New Supplementary Payment" for the Payroll Maker, "✅ Approve (OTP)" for the Payroll
 * Checker (the decision with OTP in W-01) and the Unit-wise Supplementary Payment Report; the four tiles; the
 * Supplementary Payment Requests with Filter (payment type from SUPPLEMENTARY_PAYMENT_TYPE, OD-123) and the row
 * actions; the controls and the payment types accepted; the invoice status (BR-AUDIT-009 / 010); the order card -
 * the order is captured with the request (one order per request, supplementary_payment.order_attachment_id), so the
 * card holds the picked order until "New Supplementary Payment" raises it. An open period is refused at entry
 * (SRS-G-33), so no "rejected - period not locked" request is ever stored (the tile counts the Checker's rejections).
 */
export function Supplementary() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const mayCreate = can(me.data, 'I-23', 'CREATE');
  const maySubmit = can(me.data, 'I-23', 'SUBMIT');
  const mayExport = can(me.data, 'I-23', 'EXPORT');
  const mayApprove = can(me.data, 'I-23', 'APPROVE');
  // The requests of the shown page: the invoice table reads them (X-pagination).
  const [list, setList] = useState<SupplementaryPayment[]>([]);
  const [paymentType, setPaymentType] = useState('');
  const [filtersOpen, setFiltersOpen] = useState(false);
  const tiles = useSupplementaryTiles();
  const submit = useSubmitSupplementary();
  const cancel = useCancelSupplementary();
  const [raising, setRaising] = useState(false);
  const [held, setHeld] = useState<OrderCopyInput | null>(null);
  const [rowError, setRowError] = useState<unknown>(null);
  const [cancelFor, setCancelFor] = useState<SupplementaryPayment | null>(null);
  const [viewing, setViewing] = useState<SupplementaryPayment | null>(null);
  const [selected, setSelected] = useState<SupplementaryPayment | null>(null);
  const shown = useCallback((page: SupplementaryPayment[]) => {
    setList(page);
    setSelected((prev) => (prev ? (page.find((p) => p.id === prev.id) ?? prev) : (page[0] ?? null)));
  }, []);

  async function act(run: () => Promise<unknown>, done: string) {
    setRowError(null);
    try {
      await run();
      toast(done);
    } catch (cause) {
      setRowError(cause);
    }
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {mayCreate ? (
          <Act label={t('i23.newAct')} kind="pri" onClick={() => setRaising(true)} />
        ) : (
          <Locked label={t('i23.newAct')} kind="pri" why={t('reason.makerOnly')} />
        )}
        {mayApprove ? (
          <Act label={t('i23.approveAct')} href={withBase('/payroll/?s=W-01')} />
        ) : (
          <Locked label={t('i23.approveAct')} why={t('i23.why.approve')} />
        )}
        {mayExport ? (
          <ExportButton
            href={supplementaryExportUrl()}
            what={t('i23.unitReport')}
            label={t('i23.reportAct')}
          />
        ) : (
          <Explained label={t('i23.reportAct')} why={t('x05.why.export')} />
        )}
      </div>
      <Problem error={tiles.error ?? rowError} />
      <Tiles>
        <WfTile
          icon="🧾"
          label={t('i23.tile.requests')}
          src="SYS"
          big={tiles.data?.open ?? '—'}
          sub={t('i23.tile.openSub')}
        />
        <WfTile
          icon="✍️"
          label={t('i23.tile.pending')}
          src="SYS"
          tone="warn"
          big={tiles.data?.pendingChecker ?? '—'}
          sub={t('i23.tile.pendingSub')}
        />
        <WfTile
          icon="⛔"
          label={t('i23.tile.rejectedLocked')}
          src="RULE"
          tone="bad"
          bad
          big={tiles.data?.rejected ?? '—'}
          sub={t('i23.tile.rejectedSubDrawn')}
        />
        <WfTile
          icon="♻️"
          label={t('i23.tile.blockedDrawn')}
          src="SYS"
          tone="bad"
          bad
          big={tiles.data?.blockedDuplicates ?? '—'}
          sub={t('i23.tile.blockedSub')}
        />
      </Tiles>
      <Tbl
        title={t('i23.requestsDrawn')}
        acts={<Act label={t('i23.filterAct')} kind="sm" onClick={() => setFiltersOpen(!filtersOpen)} />}
        cols={[
          { t: t('i23.col.request'), s: 'SYS' },
          { t: t('i23.col.member'), s: 'HRMS' },
          { t: t('i23.col.paymentType'), s: 'ENT' },
          { t: t('i23.col.forPeriod'), s: 'ENT' },
          { t: t('i23.col.periodState'), s: 'SYS' },
          { t: t('i23.col.amount'), s: 'CALC', num: true },
          { t: t('i23.col.duplicateCheck'), s: 'SYS' },
          { t: t('i23.col.orderNote'), s: 'ENT' },
          { t: t('i23.col.status'), s: 'SYS' },
          { t: t('i23.col.actions'), s: 'SYS' },
        ]}
        after={
          filtersOpen ? (
            <div className="toolbar fl-filters">
              <label>
                {t('i23.paymentType')}{' '}
                <select value={paymentType} onChange={(e) => setPaymentType(e.target.value)}>
                  <option value="">{t('common.all')}</option>
                  <ReferenceOptions list="SUPPLEMENTARY_PAYMENT_TYPE" />
                </select>
              </label>
            </div>
          ) : undefined
        }
      >
        <ServerPagination
          queryKey={[...generalKeys.supplementary, paymentType]}
          fetchPage={generalPages.supplementaryOf(paymentType || undefined)}
          empty={<p className="fl-hint">{t('i23.none')}</p>}
          onRows={shown}
        >
          {(page) =>
            page.map((p) => {
              const editable = mayCreate && (p.status === 'DRAFT' || p.status === 'RETURNED');
              return (
                <tr
                  key={p.id}
                  data-reference={p.reference}
                  aria-selected={selected?.id === p.id}
                  className={selected?.id === p.id ? 'fl-row-selected' : undefined}
                  onClick={() => setSelected(p)}
                >
                  <td>
                    <b>{p.reference}</b>
                    <div className="sub2">{t('i23.appliedOn', [date(p.applicationDate)])}</div>
                  </td>
                  <td>
                    {p.eis ?? '—'} {p.memberName}
                  </td>
                  <td>
                    {t(`i23.type.${p.paymentType}`)}
                    {p.payCode !== null && <div className="sub2">{t('i23.payCode', [p.payCode])}</div>}
                  </td>
                  <td>{monthName(p.forPeriodYymm)}</td>
                  <td>
                    {/* Only a month-end-locked period is ever recorded (trg_supp_period_locked). */}
                    <span className="pill p-ok">{t('i23.locked')}</span>
                  </td>
                  <td className="num">{amount(p.amount)}</td>
                  <td>
                    <span className={`pill ${tone(p.duplicateCheck)}`}>
                      {t(`i23.dup.${p.duplicateCheck}`)}
                    </span>
                    {p.duplicateOf && <div className="sub2">{t('i23.duplicateOf', [p.duplicateOf])}</div>}
                  </td>
                  <td>
                    {p.orderReference ? t('i23.orderAttached', [p.orderReference]) : t('i23.orderOnly')}
                  </td>
                  <td>
                    <span className={`pill ${tone(p.status)}`}>{t(`i23.status.${p.status}`)}</span>
                    {p.cancelReason && <div className="sub2">{p.cancelReason}</div>}
                  </td>
                  <td onClick={(e) => e.stopPropagation()}>
                    <RowActs
                      viewName={`${t('common.view')} ${p.reference}`}
                      editName={`${t('common.edit')} ${p.reference}`}
                      onView={() => setViewing(p)}
                      onEdit={editable ? () => setViewing(p) : undefined}
                      editWhy={
                        !mayCreate
                          ? t('i20.why.makerEdit')
                          : p.status === 'BLOCKED'
                            ? t('i23.block.duplicate')
                            : t('i20.why.notEditable')
                      }
                      onApprove={
                        mayApprove && p.status === 'PENDING_CHECKER'
                          ? () => window.location.assign(withBase('/payroll/?s=W-01'))
                          : undefined
                      }
                    />
                  </td>
                </tr>
              );
            })
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <Panel
          title={t('i23.controls')}
          items={[
            {
              icon: '🔒',
              tone: 'bad',
              title: t('i23.ctl.locked'),
              src: 'RULE',
              text: t('i23.ctl.lockedSub'),
            },
            {
              icon: '♻️',
              tone: 'bad',
              title: t('i23.ctl.duplicate'),
              src: 'SYS',
              text: t('i23.ctl.duplicateSub'),
            },
            { icon: '✍️', tone: 'warn', title: t('i23.ctl.otpDrawn'), src: 'SYS', text: t('i23.ctl.otpSub') },
            {
              icon: '📎',
              tone: 'info',
              title: t('i23.ctl.order'),
              src: 'ENT',
              text: t('i23.ctl.orderSubDrawn'),
            },
            {
              icon: '📊',
              tone: 'info',
              title: t('i23.ctl.statutory'),
              src: 'CALC',
              text: t('i23.ctl.statutorySub'),
            },
          ]}
        />
        <KvCard title={t('i23.accepted')} rows={ACCEPTED.map(([k, v, s]) => ({ k: t(k), v: t(v), s }))} />
      </div>
      <div className="fl-mt">
        <InvoiceCard
          title={t('i23.invoicesDrawn')}
          rows={list.map((p) => ({
            key: String(p.id),
            entry: p.reference,
            member: `${p.eis ?? '—'} ${p.memberName ?? ''}`.trim(),
            paymentType: t(`i23.type.${p.paymentType}`),
            amount: amount(p.amount),
            invoiceNo: p.invoiceId === null ? null : String(p.invoiceId),
            invoiceStatus: p.invoiceStatus,
            sentToAppsDate: p.invoiceSentAt,
            appsCancelled: p.appsCancelStatus === 'CONFIRMED',
            cancelled: p.status === 'CANCELLED',
            cancelWhy: !maySubmit
              ? t('wf.inv.why.maker')
              : supplementaryCancellable(p)
                ? null
                : t('inv.cancelBlocked'),
            onCancel: () => setCancelFor(p),
          }))}
        />
      </div>
      <PaymentOrders payment={selected} mayHold={mayCreate} onHeld={setHeld} />
      {raising && (
        <RaiseDialog
          held={held}
          onClose={() => setRaising(false)}
          onRaised={() => {
            setHeld(null);
            setRaising(false);
          }}
        />
      )}
      {viewing && (
        <PaymentView
          payment={list.find((p) => p.id === viewing.id) ?? viewing}
          maySubmit={maySubmit}
          busy={submit.isPending}
          onClose={() => setViewing(null)}
          onSubmit={(p) => {
            setViewing(null);
            void act(() => submit.mutateAsync(p.id), t('i23.submitted', [p.reference]));
          }}
        />
      )}
      {cancelFor && (
        <ReasonDialog
          title={t('i23.cancelTitle', [cancelFor.reference])}
          onCancel={() => setCancelFor(null)}
          onConfirm={(reason) => {
            const target = cancelFor;
            setCancelFor(null);
            void act(
              () => cancel.mutateAsync({ id: target.id, reason }),
              t('i23.cancelled', [target.reference]),
            );
          }}
        />
      )}
    </div>
  );
}

/**
 * "＋ New Supplementary Payment" (SRS-G-33): the member, the payment type this screen records (SRS-G-33: the pending
 * salary; the others come with the transaction that computes them), the locked period, the dates and the sanction;
 * the order is the one held on the order card (one per request, SRS-G-16).
 */
function RaiseDialog({
  held,
  onClose,
  onRaised,
}: {
  held: OrderCopyInput | null;
  onClose: () => void;
  onRaised: () => void;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const dialog = useDialogFocus<HTMLFormElement>();
  const capture = useCaptureSupplementary();
  const today = todayIso();
  const months = monthsBack(yymmOf(new Date()), 36);
  const [form, setForm] = useState({
    eis: '',
    paymentType: 'SALARY_WAGE',
    forPeriodYymm: '',
    applicationDate: '',
    sanctionDate: '',
    sanctionOrderRef: '',
  });
  const [error, setError] = useState<unknown>(null);
  const sanctionOk = sanctionAfterApplication(form.applicationDate, form.sanctionDate);
  const file = held?.files[0] ?? null;
  const valid =
    isEis(form.eis) &&
    form.forPeriodYymm !== '' &&
    form.applicationDate !== '' &&
    form.applicationDate <= today &&
    sanctionOk &&
    (form.sanctionOrderRef === '' || isAsciiText(form.sanctionOrderRef, 100)) &&
    file !== null;

  async function record() {
    if (!held || !file) return;
    setError(null);
    try {
      const created = await capture.mutateAsync({
        ...form,
        order: {
          documentType: held.documentType,
          reference: held.reference,
          orderDate: held.orderDate,
          issuingAuthority: held.issuingOffice,
          file,
        },
      });
      toast(t('i23.recorded', [created.reference]));
      onRaised();
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="fl-modal-backdrop" role="presentation">
      <form
        ref={dialog}
        className="fl-modal"
        role="dialog"
        aria-modal="true"
        aria-label={t('i23.raiseTitle')}
        onSubmit={(e) => {
          e.preventDefault();
          if (valid) void record();
        }}
      >
        <h2>{t('i23.raiseTitle')}</h2>
        <Problem error={error} />
        <div className="rf-grid">
          <div className="rf-field">
            <label htmlFor="i23-eis">{t('gen.eis')}</label>
            <input
              id="i23-eis"
              maxLength={20}
              value={form.eis}
              onChange={(e) => setForm({ ...form, eis: e.target.value })}
            />
          </div>
          <div className="rf-field">
            <label htmlFor="i23-type">{t('i23.paymentType')}</label>
            <select
              id="i23-type"
              value={form.paymentType}
              onChange={(e) => setForm({ ...form, paymentType: e.target.value })}
            >
              <ReferenceOptions list="SUPPLEMENTARY_PAYMENT_TYPE" only={SCREEN_PAYMENT_TYPES} />
            </select>
            <span className="hint">{t('i23.otherTypesHint')}</span>
          </div>
          <div className="rf-field">
            <label htmlFor="i23-period">{t('i23.forPeriod')}</label>
            <select
              id="i23-period"
              value={form.forPeriodYymm}
              onChange={(e) => setForm({ ...form, forPeriodYymm: e.target.value })}
            >
              <option value="">—</option>
              {months.map((m) => (
                <option key={m} value={m}>
                  {monthName(m)}
                </option>
              ))}
            </select>
            <span className="hint">{t('i23.periodHint')}</span>
          </div>
          <div className="rf-field">
            <label htmlFor="i23-applied">{t('i23.applicationDate')}</label>
            <input
              id="i23-applied"
              type="date"
              max={today}
              value={form.applicationDate}
              onChange={(e) => setForm({ ...form, applicationDate: e.target.value })}
            />
          </div>
          <div className="rf-field">
            <label htmlFor="i23-sanctioned">{t('i23.sanctionDate')}</label>
            <input
              id="i23-sanctioned"
              type="date"
              value={form.sanctionDate}
              onChange={(e) => setForm({ ...form, sanctionDate: e.target.value })}
            />
            {!sanctionOk && <span className="fl-error">{t('error.SUP-422-SANCTION')}</span>}
          </div>
          <div className="rf-field">
            <label htmlFor="i23-sanction-ref">{t('i23.sanctionRef')}</label>
            <input
              id="i23-sanction-ref"
              maxLength={100}
              value={form.sanctionOrderRef}
              onChange={(e) => setForm({ ...form, sanctionOrderRef: e.target.value })}
            />
          </div>
        </div>
        <p className="fl-hint">{file ? t('i23.orderHeld', [file.name]) : t('i23.orderFirst')}</p>
        <p className="fl-hint">{t('i23.amountNote')}</p>
        <div className="fl-actions">
          <button type="button" className="btn" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button type="submit" className="btn pri" disabled={!valid || capture.isPending}>
            {t('i23.record')}
          </button>
        </div>
      </form>
    </div>
  );
}

/** 👁 View / ✎ Edit: the request read-only, and its submission for the Maker while it is a Draft or Returned. */
function PaymentView({
  payment,
  maySubmit,
  busy,
  onClose,
  onSubmit,
}: {
  payment: SupplementaryPayment;
  maySubmit: boolean;
  busy: boolean;
  onClose: () => void;
  onSubmit: (p: SupplementaryPayment) => void;
}) {
  const { t } = useI18n();
  const dialog = useDialogFocus<HTMLDivElement>();
  const block = supplementarySubmitBlock(payment);
  const why = !maySubmit ? t('reason.makerOnly') : block ? t(block) : null;
  return (
    <div className="fl-modal-backdrop" role="presentation">
      <div
        ref={dialog}
        className="fl-modal"
        role="dialog"
        aria-modal="true"
        aria-label={t('i23.viewTitle', [payment.reference])}
      >
        <h2>{t('i23.viewTitle', [payment.reference])}</h2>
        <div className="kv">
          <span className="k">{t('i23.col.member')}</span>
          <span>{`${payment.eis ?? '—'} ${payment.memberName ?? ''}`}</span>
          <span className="k">{t('i23.col.paymentType')}</span>
          <span>{t(`i23.type.${payment.paymentType}`)}</span>
          <span className="k">{t('i23.col.forPeriod')}</span>
          <span>{monthName(payment.forPeriodYymm)}</span>
          <span className="k">{t('i23.col.amount')}</span>
          <span>{amount(payment.amount)}</span>
          <span className="k">{t('i23.applicationDate')}</span>
          <span>{date(payment.applicationDate)}</span>
          <span className="k">{t('i23.sanctionDate')}</span>
          <span>{payment.sanctionDate ? date(payment.sanctionDate) : '—'}</span>
          <span className="k">{t('i23.col.orderNote')}</span>
          <span>{payment.orderReference ?? '—'}</span>
          <span className="k">{t('i23.col.status')}</span>
          <span>{t(`i23.status.${payment.status}`)}</span>
        </div>
        <div className="fl-actions">
          <button type="button" className="btn" onClick={onClose}>
            {t('common.close')}
          </button>
          <button
            type="button"
            className="btn pri"
            disabled={why !== null || busy}
            title={why ?? undefined}
            data-why={why ?? undefined}
            onClick={() => onSubmit(payment)}
          >
            {t('common.submitForApproval')}
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * The drawn order card: the selected request's stored order, and - for the Maker - the order of the next request,
 * held until "＋ New Supplementary Payment" raises it (one order per request).
 */
function PaymentOrders({
  payment,
  mayHold,
  onHeld,
}: {
  payment: SupplementaryPayment | null;
  mayHold: boolean;
  onHeld: (order: OrderCopyInput) => void;
}) {
  const { t } = useI18n();
  const stored = useStoredOrders('supplementary', payment?.id ?? null);
  const types = useDocumentTypes(true);
  return (
    <OrderCopyCard
      hint={t('i23.ord.hint')}
      canAttach={mayHold}
      stored={stored.data}
      storedError={stored.error ?? types.error}
      fileHref={(fileId) => fileUrl('supplementary', payment?.id ?? 0, fileId)}
      today={todayIso()}
      attachmentRequired
      holds
      officeRequired
      referenceRequired={(code) => types.data?.find((d) => d.code === code)?.referenceRequired ?? false}
      onAttach={async (o) => onHeld(o)}
    />
  );
}
