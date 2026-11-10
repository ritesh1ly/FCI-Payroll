'use client';

import { useState } from 'react';
import { EMPTY_ORDER, todayIso, type OrderDraft as CopyDraft, useDocumentTypes } from '@/modules/general';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { ReasonDialog, Problem, ServerPagination, useToast, withBase } from '@/shared/ui';
import { InvoiceCard, type InvoiceRow } from '@/shared/ui/invoiceCard';
import { RegisterSection, useApproveRow } from '@/shared/ui/masterParts';
import { OrderCopyCard } from '@/shared/ui/orderCopy';
import {
  Act,
  Explained,
  KvCard,
  Locked,
  orderCapturePanel,
  Panel,
  RowActs,
  Tbl,
  Tiles,
  ViewDialog,
  WfTile,
} from '@/shared/ui/wireframe';
import {
  orderPage,
  paymentPage,
  penaltyCopyUrl,
  penaltyKeys,
  registerPage,
  useMinNetGuard,
  useFirstOrder,
  useOrderCopies,
  type OrderRow,
  type PaymentRow,
  type RegisterRow,
  usePenaltyActions,
  usePenaltyCounts,
} from '../orchestration/penaltyQueries';
import { PenaltyRecovery } from './PenaltyRecovery';

function rupees(n: number | null | undefined): string {
  return n === null || n === undefined ? '—' : Number(n).toLocaleString('en-IN');
}

function pill(status: string): string {
  if (['ACTIVE', 'APPROVED', 'PAID', 'COMPLETED'].includes(status)) return 'p-ok';
  if (['PARTLY_REVOKED', 'REVOKED', 'CANCELLED', 'REJECTED'].includes(status)) return 'p-info';
  return 'p-warn';
}

/** The drawn Status of a register row: the guard and the deferral have their own pill. */
function statusKey(r: RegisterRow): string {
  if (r.belowFloor) return 'i06w.status.guard';
  if (r.kind === 'ORDER') return r.status === 'PAUSED_FNF' ? 'i06w.status.fnf' : `i06.status.${r.status}`;
  return r.kind === 'PAYMENT' ? `i06.payment.${r.status}` : `i06.request.${r.status}`;
}

/** The drawn Imposed cell: the amount, or what a nature that recovers nothing does. */
function imposed(o: OrderRow, t: (k: string) => string): string {
  if (o.nature === 'INCREMENT_STOP') return t('i06w.imposed.stop');
  if (o.nature === 'BASIC_REDUCTION') return t('i06w.imposed.basic');
  return rupees(o.amount);
}

/**
 * I-06 Penalty, Recovery &amp; Over-and-Above as Wireframes v6.1 draws it (OD-121; SRS-14-1 ... 14-20, screen I-06). The
 * two registers, the penalty types, the tiles, the Min-Net Guard, the panels, the invoice card and the order card. The
 * entry forms, the schedule, revokes and cancellations and the Penalty Report stay whole in the closed section that
 * the drawn buttons open. The order copy travels with its record (SRS-G-31): the file picked on the drawn card is held
 * and handed to the form that stores it on submit.
 *
 * <p>The pack draws these for the Payroll Maker, who holds the rights; other roles see them off with the drawn reason.
 * An employee penalty stays HRMS's (Edit off); its Penalty Revoke is the Maker's (SRS-14-16, SRS-14-17).
 */
export function PenaltyScreen() {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const approve = useApproveRow();
  const actions = usePenaltyActions();
  const may = (action: string) => can(me.data, 'I-06', action);
  const counts = usePenaltyCounts();
  const guard = useMinNetGuard();
  const types = useDocumentTypes(true);
  const [register, setRegister] = useState(false);
  const [openId, setOpenId] = useState<number | null>(null);
  const [viewing, setViewing] = useState<RegisterRow | null>(null);
  const [held, setHeld] = useState<CopyDraft | null>(null);
  const [cancelling, setCancelling] = useState<PaymentRow | null>(null);
  const [error, setError] = useState<unknown>(null);
  const first = useFirstOrder();
  const selected = openId ?? first.data?.items[0]?.id ?? null;
  const copies = useOrderCopies(selected);
  const c = counts.data;
  const g = guard.data ?? null;

  function open(id: number | null) {
    setOpenId(id);
    setRegister(true);
  }

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

  const invoiceRows = (rows: PaymentRow[]): InvoiceRow[] =>
    rows.map((r) => {
      const sent = r.invoiceSentAt !== null && r.invoiceSentAt !== undefined;
      const open = r.status === 'SUBMITTED' || r.status === 'APPROVED';
      return {
        key: String(r.id),
        entry: r.sanctionReference,
        member: `${r.eis} ${r.employee ?? ''}`.trim(),
        paymentType: `${r.payCode ?? ''} ${r.payName ?? ''}`.trim(),
        amount: rupees(r.amount),
        invoiceNo: r.invoiceNo === null || r.invoiceNo === undefined ? null : String(r.invoiceNo),
        invoiceStatus: r.invoiceStatus,
        sentToAppsDate: r.invoiceSentAt?.slice(0, 10) ?? null,
        appsCancelled: r.appsCancelStatus === 'CONFIRMED',
        cancelled: r.status === 'CANCELLED',
        cancelWhy: !may('CANCEL')
          ? t('i06w.why.maker')
          : !open
            ? t('i06w.why.notOpen')
            : sent
              ? t('i06w.why.sent')
              : null,
        onCancel: () => setCancelling(r),
      };
    });

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {may('CREATE') ? (
          <Act label={t('i06w.labour')} kind="pri" onClick={() => open(null)} />
        ) : (
          <Locked label={t('i06w.labour')} why={t('i06w.why.labour')} />
        )}
        {may('REVOKE') ? (
          <Act label={t('i06w.revoke')} onClick={() => open(selected)} />
        ) : (
          <Locked label={t('i06w.revoke')} why={t('i06w.why.revoke')} />
        )}
        {may('OVER_ABOVE') ? (
          <Act label={t('i06w.overAbove')} onClick={() => open(null)} />
        ) : (
          <Locked label={t('i06w.overAbove')} why={t('i06w.why.maker')} />
        )}
        {may('APPROVE') ? (
          <Act label={t('i06w.batch')} kind="pri" href={withBase('/payroll/?s=W-01')} />
        ) : (
          <Locked label={t('i06w.batch')} why={t('i06w.why.checker')} />
        )}
      </div>
      <Problem error={error ?? counts.error} />
      <div className="g2 fl-mb">
        <Tbl
          title={t('i06w.penalties')}
          acts={
            may('EXPORT') ? (
              <Act label={t('i06w.report')} kind="sm" onClick={() => setRegister(true)} />
            ) : (
              <Explained label={t('i06w.report')} kind="sm" why={t('i06w.why.export')} />
            )
          }
          cols={[
            { t: t('i06w.col.id'), s: 'SYS' },
            { t: t('i06w.col.member'), s: 'HRMS' },
            { t: t('i06w.col.source'), s: 'SYS' },
            { t: t('i06w.col.order'), s: 'ENT' },
            { t: t('i06w.col.imposed'), s: 'ENT', num: true },
            { t: t('i06w.col.recovered'), s: 'CALC', num: true },
            { t: t('i06w.col.waived'), s: 'CALC', num: true },
            { t: t('i06w.col.outstanding'), s: 'CALC', num: true },
            { t: t('i06w.col.status'), s: 'SYS' },
          ]}
        >
          <ServerPagination
            queryKey={penaltyKeys.list({})}
            fetchPage={orderPage({})}
            empty={<p className="fl-hint">{t('i06.none')}</p>}
          >
            {(rows) =>
              rows.map((o) => (
                <tr key={o.id} data-reference={o.penaltyRef}>
                  <td>
                    <b>{o.penaltyRef}</b>
                  </td>
                  <td>
                    {o.eis} {o.employee}
                  </td>
                  <td>{t(`i06.source.${o.source}`)}</td>
                  <td>{o.orderReference}</td>
                  <td className="num">{imposed(o, t)}</td>
                  <td className="num">{rupees(o.recovered)}</td>
                  <td className="num">{rupees(o.waived)}</td>
                  <td className="num">{rupees(o.outstanding)}</td>
                  <td>
                    <span className={`pill ${pill(o.status)}`}>{t(`i06.status.${o.status}`)}</span>
                  </td>
                </tr>
              ))
            }
          </ServerPagination>
        </Tbl>
        <Panel
          title={t('i06w.revokePanel')}
          act={
            may('REVOKE') ? (
              <Act label={t('i06w.revokeAct')} kind="sm" onClick={() => open(selected)} />
            ) : (
              <Locked label={t('i06w.revokeAct')} kind="sm" why={t('i06w.why.revoke')} />
            )
          }
          items={[
            { icon: '🆔', tone: 'info', title: t('i06w.rp.id'), src: 'SYS', text: t('i06w.rp.id.m') },
            { icon: '↩', tone: 'warn', title: t('i06w.rp.waiver'), src: 'ENT', text: t('i06w.rp.waiver.m') },
            { icon: '📈', tone: 'ok', title: t('i06w.rp.stop'), src: 'CALC', text: t('i06w.rp.stop.m') },
            {
              icon: '👁',
              tone: 'gray',
              title: t('i06w.rp.preview'),
              src: 'CALC',
              text: t('i06w.rp.preview.m'),
            },
            { icon: '🏛', tone: 'gray', title: t('i06w.rp.source'), src: 'HRMS', text: t('i06w.rp.source.m') },
          ]}
        />
      </div>
      <Tbl
        title={t('i06w.types')}
        cols={[
          { t: t('i06w.types.type'), s: 'RULE' },
          { t: t('i06w.types.does'), s: 'RULE' },
          { t: t('i06w.types.rule'), s: 'RULE' },
        ]}
      >
        {(['recovery', 'stop', 'basic'] as const).map((k) => (
          <tr key={k}>
            <td>
              <b>{t(`i06w.types.${k}`)}</b>
            </td>
            <td>{t(`i06w.types.${k}.does`)}</td>
            <td>{t(`i06w.types.${k}.rule`)}</td>
          </tr>
        ))}
      </Tbl>
      <Tiles>
        <WfTile
          icon="⚠️"
          label={t('i06w.tile.orders')}
          src="ENT"
          big={c?.orders ?? '—'}
          sub={c ? t('i06w.tile.orders.sub', [rupees(c.outstanding)]) : ''}
        />
        <WfTile
          icon="➕"
          tone="ok"
          label={t('i06w.tile.oa')}
          src="ENT"
          big={c?.payments ?? '—'}
          sub={c ? t('i06w.tile.oa.sub', [rupees(c.paymentsAwaitingAmount)]) : ''}
        />
        <WfTile
          icon="⛔"
          tone="bad"
          bad
          label={t('i06w.tile.guard')}
          src="CALC"
          big={c?.minNetGuard ?? '—'}
          sub={t('i06w.tile.guard.sub')}
        />
        <WfTile
          icon="🎯"
          tone="pur"
          label={t('i06w.tile.137')}
          src="CALC"
          big={c?.incentiveRecovery ?? '—'}
          sub={t('i06w.tile.137.sub')}
        />
      </Tiles>
      <Tbl
        title={t('i06w.register')}
        acts={<Act label={t('i06w.schedule')} kind="sm" onClick={() => open(selected)} />}
        cols={[
          { t: t('i06w.rc.no'), s: 'SYS' },
          { t: t('i06w.col.member'), s: 'HRMS' },
          { t: t('i06w.rc.nature'), s: 'ENT' },
          { t: t('i06w.rc.reason'), s: 'ENT' },
          { t: t('i06w.rc.amount'), s: 'ENT', num: true },
          { t: t('i06w.rc.instalments'), s: 'ENT', num: true },
          { t: t('i06w.rc.per'), s: 'CALC', num: true },
          { t: t('i06w.rc.approved'), s: 'SYS' },
          { t: t('i06w.col.status'), s: 'SYS' },
          { t: t('m04.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={penaltyKeys.register}
          fetchPage={registerPage()}
          empty={<p className="fl-hint">{t('i06.none')}</p>}
        >
          {(rows) =>
            rows.map((r) => (
              <tr key={`${r.kind}-${r.id}`}>
                <td>
                  <b>{r.reference ?? '—'}</b>
                </td>
                <td>
                  {r.eis} {r.employee}
                </td>
                <td>{r.nature ? t(`i06w.nature.${r.nature}`) : t('i06w.nature.REQUEST')}</td>
                <td>{r.reason ?? '—'}</td>
                <td className="num">{rupees(r.amount)}</td>
                <td className="num">{r.instalments ?? '—'}</td>
                <td className="num">{rupees(r.perPeriod)}</td>
                <td>{r.approvedBy ?? '—'}</td>
                <td>
                  <span className={`pill ${r.belowFloor ? 'p-bad' : pill(r.status)}`}>{t(statusKey(r))}</span>
                </td>
                <td>
                  <RowActs
                    viewName={`${t('common.view')} ${r.reference ?? r.id}`}
                    editName={`${t('common.edit')} ${r.reference ?? r.id}`}
                    onView={() => {
                      if (r.kind === 'ORDER') setOpenId(r.id);
                      setViewing(r);
                    }}
                    onEdit={
                      may('CREATE') && r.source !== 'HRMS'
                        ? () => open(r.kind === 'ORDER' ? r.id : null)
                        : undefined
                    }
                    editWhy={t(r.source === 'HRMS' ? 'i06w.why.hrms' : 'i06w.why.edit')}
                    onApprove={
                      may('APPROVE')
                        ? () => approve(r.kind === 'REQUEST' || r.status === 'SUBMITTED')
                        : undefined
                    }
                  />
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <KvCard
          title={t('i06w.guard', [g?.reference ?? '—'])}
          rows={[
            { k: t('i06w.g.member'), v: g ? `${g.eis} ${g.employee}` : '—', s: 'HRMS' },
            { k: t('i06w.g.gross'), v: g ? `₹ ${rupees(g.gross)}` : '—', s: 'CALC' },
            { k: t('i06w.g.deductions'), v: g ? `₹ ${rupees(g.existingDeductions)}` : '—', s: 'CALC' },
            { k: t('i06w.g.instalment'), v: g ? `₹ ${rupees(g.proposedInstalment)}` : '—', s: 'CALC' },
            {
              k: t('i06w.g.net'),
              v: g ? <b className="fl-bad">₹ {rupees(g.resultingNet)}</b> : '—',
              s: 'CALC',
            },
            {
              k: t('i06w.g.minimum'),
              v:
                g?.floor === null || g === null ? (
                  <span className="pill p-info">{t('i06w.g.setting')}</span>
                ) : (
                  `₹ ${rupees(g.floor)}`
                ),
              s: 'RULE',
            },
            {
              k: t('i06w.g.action'),
              v: g ? <span className="pill p-bad">{t('i06w.g.blocked')}</span> : '—',
              s: 'SYS',
            },
          ]}
          after={
            <div className="chips fl-mt">
              <span className="chip">{t('i06w.g.chipA')}</span>
              <span className="chip">{t('i06w.g.chipB')}</span>
              <span className="chip">{t('i06w.g.chipC')}</span>
            </div>
          }
        />
        <Panel title={t('pen.cap')} items={orderCapturePanel(t)} />
      </div>
      <div className="g2 fl-mt">
        <Panel
          title={t('pen.ctrl')}
          items={[
            {
              icon: '📐',
              tone: 'warn',
              title: t('pen.ctrl.ceiling'),
              src: 'RULE',
              text: t('pen.ctrl.ceiling.m'),
            },
            {
              icon: '🗓',
              tone: 'info',
              title: t('pen.ctrl.periods'),
              src: 'RULE',
              text: t('pen.ctrl.periods.m'),
              right: <span className="pill p-ok">{t('common.on')}</span>,
            },
            {
              icon: '🧾',
              tone: 'info',
              title: t('pen.ctrl.tax'),
              src: 'CALC',
              text: t('pen.ctrl.tax.m'),
              right: <span className="pill p-ok">{t('common.on')}</span>,
            },
            {
              icon: '↩️',
              tone: 'warn',
              title: t('pen.ctrl.retro'),
              src: 'RULE',
              text: t('pen.ctrl.retro.m'),
              right: <span className="pill p-ok">{'BR-PEN2-006'}</span>,
            },
            {
              icon: '✅',
              tone: 'ok',
              title: t('pen.ctrl.post'),
              src: 'SYS',
              text: t('pen.ctrl.post.m'),
              right: <span className="pill p-ok">{t('common.on')}</span>,
            },
            {
              icon: '⛔',
              tone: 'bad',
              title: t('pen.ctrl.floor'),
              src: 'RULE',
              text: t('i06w.ctrl.floor.m'),
              right: <b>{c?.minNetGuard ?? '—'}</b>,
            },
            {
              icon: '✍️',
              tone: 'warn',
              title: t('i06w.ctrl.mc'),
              src: 'SYS',
              text: t('i06w.ctrl.mc.m'),
              right: <b>{c?.paymentsAwaiting ?? '—'}</b>,
            },
            {
              icon: '📅',
              tone: 'info',
              title: t('i06w.ctrl.sanction'),
              src: 'RULE',
              text: t('i06w.ctrl.sanction.m'),
              right: <span className="pill p-ok">{t('common.on')}</span>,
            },
            {
              icon: '🚫',
              tone: 'gray',
              title: t('pen.ctrl.supp'),
              src: 'RULE',
              text: t('pen.ctrl.supp.m'),
              right: <span className="pill p-ok">{t('common.on')}</span>,
            },
            {
              icon: '📤',
              tone: 'gray',
              title: t('i06w.ctrl.fnf'),
              src: 'CALC',
              text: t('i06w.ctrl.fnf.m'),
              right: <b>{c?.carriedToFnf ?? '—'}</b>,
            },
          ]}
        />
      </div>
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
        empty={<InvoiceCard title={t('i06w.invoices')} rows={[]} />}
      >
        {(rows) => <InvoiceCard title={t('i06w.invoices')} rows={invoiceRows(rows)} />}
      </ServerPagination>
      <OrderCopyCard
        hint={t('i06w.order.hint')}
        canAttach={may('CREATE')}
        readOnly={!may('CREATE')}
        holds
        stored={copies.data}
        storedError={copies.error ?? types.error}
        fileHref={(fileId) => penaltyCopyUrl(selected ?? 0, fileId)}
        today={todayIso()}
        attachmentRequired
        referenceRequired={(code) => types.data?.find((d) => d.code === code)?.referenceRequired ?? false}
        officeRequired
        onAttach={async (o) => {
          const file = o.files[0];
          setHeld({
            ...EMPTY_ORDER,
            documentType: o.documentType,
            reference: o.reference,
            orderDate: o.orderDate,
            issuingAuthority: o.issuingOffice,
            file: file ?? null,
          });
          setRegister(true);
        }}
      />
      <RegisterSection label={t('i06w.closed')} open={register} onToggle={setRegister} id="i06-register">
        {register && (
          <PenaltyRecovery key={`${openId}-${held?.file?.name}`} initialOpen={openId} heldCopy={held} />
        )}
      </RegisterSection>
      {viewing && (
        <ViewDialog
          screen={t('screen.I-06')}
          rows={[
            { k: t('i06w.rc.no'), v: viewing.reference ?? '—', s: 'SYS' },
            { k: t('i06w.col.member'), v: `${viewing.eis} ${viewing.employee}`, s: 'HRMS' },
            { k: t('i06w.col.source'), v: t(`i06.source.${viewing.source}`), s: 'SYS' },
            {
              k: t('i06w.rc.nature'),
              v: viewing.nature ? t(`i06w.nature.${viewing.nature}`) : t('i06w.nature.REQUEST'),
              s: 'ENT',
            },
            { k: t('i06w.rc.reason'), v: viewing.reason ?? '—', s: 'ENT' },
            { k: t('i06w.rc.amount'), v: rupees(viewing.amount), s: 'ENT' },
            { k: t('i06w.rc.approved'), v: viewing.approvedBy ?? '—', s: 'SYS' },
            { k: t('i06w.col.status'), v: t(statusKey(viewing)), s: 'SYS' },
          ]}
          actions={
            viewing.kind === 'ORDER' ? (
              <Act
                label={t('i06w.schedule')}
                onClick={() => {
                  setViewing(null);
                  open(viewing.id);
                }}
              />
            ) : undefined
          }
          onClose={() => setViewing(null)}
        />
      )}
    </div>
  );
}
