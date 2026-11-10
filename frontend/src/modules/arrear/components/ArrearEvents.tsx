'use client';

import { useCallback, useEffect, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, periodLabel, useMe } from '@/shared/session';
import {
  amount,
  Card,
  date,
  Empty,
  ExportButton,
  Problem,
  ReasonDialog,
  ServerPagination,
  Src,
  useToast,
  TextAreaField,
  withBase,
} from '@/shared/ui';
import { OrderCopyCard } from '@/shared/ui/orderCopy';
import { Act, Explained, KvCard, Locked, RowActs, Tbl, Tiles, WfTile } from '@/shared/ui/wireframe';
import { businessToday } from '@/shared/time';
import type { EventFilter, EventRow, NewRetroEvent } from '../api/arrearApi';
import {
  cyclePeriod,
  mayAskHeadquarters,
  mayRecompute,
  mayUndo,
  month,
  maySubmit,
  remarkGiven,
} from '../business/arrearRules';
import {
  arrearKeys,
  arrearPages,
  arrearRegisterUrl,
  eventOrderUrl,
  useArrearEventTypes,
  useAttachEventOrder,
  useEventOrders,
  useOrderDocumentTypes,
  useArrearSummary,
  useCreateRetroEvent,
  useEventPeriods,
  useRecomputeEvent,
  useRequestException,
  useRequestUndo,
  useRetroControls,
  useSubmitEvent,
} from '../orchestration/arrearQueries';
import { useRevisionOrders } from '../orchestration/wageRevisionQueries';

const PAYROLL_TYPES = ['STAFF', 'DEPT_LABOUR', 'DPS_LABOUR', 'NWNP_LABOUR'] as const;

/**
 * I-08 Arrears &amp; Retrospective Events as Wireframes v6.1 draws it (a frozen requirement per
 * OD-121; SRS-8-6, SRS-8-8, SRS-8-13, SRS-8-14, SRS-8-21, FRS §6.9.3, §6.19): "New Retro Event",
 * "Recompute" and "Approve &amp; Merge" over the screen; the four tiles over the scope; the Arrear /
 * Retro Event Register with Registers and Undo event, one row per event (View opens the event's
 * differentials and its actions; Edit is drawn disabled, an event is computed and merged, never
 * edited; ✔ for the Checker); the period-wise differential of the selected event (click its row);
 * the retro controls (Headquarters settings read from A-07, never a guess) and the worked
 * scenarios of FRS §6.19.5. The Payroll Maker raises a retro event for the office and span, its
 * engine computes it, the Maker submits it with a remark and the Payroll Checker approves it in
 * W-01, after which it posts into the next open cycle. An event beyond the Headquarters back-date
 * limit is held until Headquarters approves an exception with its order (SRS-8-6).
 */
export function ArrearEvents() {
  const { t, locale } = useI18n();
  const me = useMe();
  const toast = useToast();
  const maker = can(me.data, 'I-08', 'SUBMIT');
  const checker = can(me.data, 'I-08', 'APPROVE');
  const [selected, setSelected] = useState<EventRow | null>(null);
  const [opened, setOpened] = useState<EventRow | null>(null);
  const [creating, setCreating] = useState(false);
  // As drawn, the register has no filter of its own: every event of the offices in scope, newest first.
  const filter: EventFilter = {};
  // The period-wise differential shows the selected event; the first row is selected when the page loads.
  const selectFirst = useCallback((rows: EventRow[]) => setSelected((prev) => prev ?? rows[0] ?? null), []);
  const summary = useArrearSummary();
  const controls = useRetroControls();
  const periods = useEventPeriods(selected?.id ?? null);
  const recompute = useRecomputeEvent();
  const tiles = summary.data;

  async function recomputeSelected() {
    if (!selected) {
      toast(t('i08.selectEvent'));
      return;
    }
    if (!selected.singleMember) {
      toast(t('i08.why.bulkRecompute'));
      return;
    }
    if (!mayRecompute(selected)) {
      toast(t('i08.why.recompute'));
      return;
    }
    try {
      const outcome = await recompute.mutateAsync(selected.id);
      toast(outcome.computed ? t('i08.computedAgain') : t('i08.notComputable', [outcome.reason ?? '']));
    } catch (cause) {
      toast(t('error.unexpected'));
      void cause;
    }
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {maker ? (
          <Act label={t('i08.newEvent')} kind="pri" onClick={() => setCreating(true)} />
        ) : (
          <Locked label={t('i08.newEvent')} why={t('i08.why.newEvent')} />
        )}
        {maker ? (
          <Act label={t('i08.recomputeAct')} onClick={() => void recomputeSelected()} />
        ) : (
          <Locked label={t('i08.recomputeAct')} why={t('reason.makerOnly')} />
        )}
        {checker ? (
          <Act label={t('i08.approveMerge')} kind="pri" href={withBase('/payroll/?s=W-01')} />
        ) : (
          <Locked label={t('i08.approveMerge')} why={t('i08.why.approveMerge')} />
        )}
      </div>
      <Problem error={summary.error ?? controls.error} />
      <Tiles>
        <WfTile
          icon="↩️"
          label={t('i08.tile.open')}
          src="SYS"
          big={tiles?.openEvents ?? '—'}
          sub={t('i08.tile.openSub', [tiles?.awaitingApproval ?? '—'])}
        />
        <WfTile
          icon="₹"
          label={t('i08.tile.arrears')}
          src="CALC"
          big={tiles ? amount(tiles.arrears) : '—'}
          sub={t('i08.tile.members', [tiles?.arrearMembers ?? '—'])}
          tone="ok"
        />
        <WfTile
          icon="➖"
          label={t('i08.tile.recoveries')}
          src="CALC"
          big={tiles ? amount(tiles.recoveries) : '—'}
          sub={t('i08.tile.members', [tiles?.recoveryMembers ?? '—'])}
          tone="bad"
          bad={(tiles?.recoveries ?? 0) > 0}
        />
        <WfTile
          icon="🧾"
          label={t('i08.tile.statutory')}
          src="CALC"
          big={
            tiles?.statutory === null || tiles?.statutory === undefined
              ? t('i08.unavailable.NEXT_RUN')
              : amount(tiles.statutory)
          }
          sub={t('i08.tile.statutorySub')}
          tone="pur"
        />
      </Tiles>
      <Tbl
        title={t('i08.registerTitle')}
        acts={
          <>
            {can(me.data, 'I-08', 'EXPORT') ? (
              <ExportButton
                href={arrearRegisterUrl(filter)}
                what={t('i08.register')}
                label={t('i08.registers')}
                className="btn sm"
              />
            ) : (
              <Explained label={t('i08.registers')} kind="sm" why={t('x05.why.export')} />
            )}
            <UndoSelected event={selected} maker={maker} />
          </>
        }
        cols={[
          { t: t('i08.col.event'), s: 'SYS' },
          { t: t('i08.col.description'), s: 'ENT' },
          { t: t('i08.col.payrollType'), s: 'ENT' },
          { t: t('i08.col.revises'), s: 'ENT' },
          { t: t('i08.col.effFrom'), s: 'ENT' },
          { t: t('i08.col.effTo'), s: 'ENT' },
          { t: t('i08.col.members'), s: 'CALC', num: true },
          { t: t('i08.col.arrear'), s: 'CALC', num: true },
          { t: t('i08.col.recovery'), s: 'CALC', num: true },
          { t: t('i08.col.status'), s: 'SYS' },
          { t: t('i08.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={arrearKeys.events(filter)}
          fetchPage={arrearPages.events(filter)}
          empty={<Empty text={t('i08.none')} />}
          onRows={selectFirst}
        >
          {(rows) => (
            <>
              <SyncSelected rows={rows} selected={opened} onChange={setOpened} />
              {rows.map((r) => (
                <tr
                  key={r.id}
                  data-reference={r.reference}
                  aria-selected={selected?.id === r.id}
                  className={selected?.id === r.id ? 'fl-row-selected' : undefined}
                  onClick={() => setSelected(r)}
                >
                  <td>
                    <b>{r.reference}</b>
                    {r.office && <div className="sub2">{r.office}</div>}
                  </td>
                  <td>
                    {r.eventName}
                    {r.singleMember && r.source && <div className="sub2">{t('i08.source', [r.source])}</div>}
                  </td>
                  <td>{t('i08.payrollType.STAFF')}</td>
                  <td>{r.eventCode}</td>
                  <td>{date(r.effectiveFrom)}</td>
                  <td>{r.effectiveTo ? date(r.effectiveTo) : '—'}</td>
                  <td className="num">{r.members}</td>
                  <td className="num">{amount(r.arrears)}</td>
                  <td className="num">{amount(r.recoveries)}</td>
                  <td>
                    <span
                      className={`pill ${r.status === 'APPROVED' || r.status === 'POSTED' ? 'p-ok' : r.status === 'REJECTED' || r.status === 'REVERSED' ? 'p-gray' : 'p-warn'}`}
                    >
                      {t(`i08.status.${r.status}`)}
                    </span>
                    {r.postedToYymm && (
                      <div className="sub2">{t('i08.postsInto', [month(r.postedToYymm)])}</div>
                    )}
                    {r.beyondWindow && (
                      <div className="sub2">
                        {t(r.hqException ? `i08.hqException.${r.hqException}` : 'i08.beyondWindow')}
                      </div>
                    )}
                  </td>
                  <td onClick={(e) => e.stopPropagation()}>
                    <RowActs
                      viewName={t('common.view') + ' ' + r.reference}
                      onView={() => setOpened(r)}
                      editWhy={t('i08.why.edit')}
                      onApprove={
                        // As drawn (RA_MAKER: ✔ for the Checker on every row): the decision is taken in W-01.
                        checker ? () => window.location.assign(withBase('/payroll/?s=W-01')) : undefined
                      }
                    />
                  </td>
                </tr>
              ))}
            </>
          )}
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <Tbl
          title={t('i08.periodsTitle', [selected?.reference ?? '—'])}
          acts={
            selected ? (
              <Act label={t('i08.memberView')} kind="sm" onClick={() => setOpened(selected)} />
            ) : (
              <Locked label={t('i08.memberView')} kind="sm" why={t('i08.selectEvent')} />
            )
          }
          cols={[
            { t: t('i08.col.period'), s: 'SYS' },
            { t: t('i08.col.original'), s: 'SYS', num: true },
            { t: t('i08.col.revised'), s: 'CALC', num: true },
            { t: t('i08.col.basicDiff'), s: 'CALC', num: true },
            { t: t('i08.col.daDiff'), s: 'CALC', num: true },
            { t: t('i08.col.hraDiff'), s: 'CALC', num: true },
            { t: t('i08.col.netArrear'), s: 'CALC', num: true },
          ]}
          foot={
            periods.data && periods.data.length > 0 ? (
              <tr>
                <td>
                  <b>{t('i08.periodsTotal', [periods.data.length])}</b>
                </td>
                <td />
                <td />
                <td className="num">
                  <b>{amount(periods.data.reduce((s, p) => s + p.basicDiff, 0))}</b>
                </td>
                <td className="num">
                  <b>{amount(periods.data.reduce((s, p) => s + p.daDiff, 0))}</b>
                </td>
                <td className="num">
                  <b>{amount(periods.data.reduce((s, p) => s + p.hraDiff, 0))}</b>
                </td>
                <td className="num">
                  <b>{amount(periods.data.reduce((s, p) => s + p.netArrear, 0))}</b>
                </td>
              </tr>
            ) : undefined
          }
        >
          {!selected && (
            <tr>
              <td colSpan={7} className="sub2">
                {t('i08.periodsNone')}
              </td>
            </tr>
          )}
          {selected && periods.data && periods.data.length === 0 && (
            <tr>
              <td colSpan={7} className="sub2">
                {t('i08.noDetails')}
              </td>
            </tr>
          )}
          {(periods.data ?? []).map((p) => (
            <tr key={p.yymm}>
              <td>{p.yymm}</td>
              <td className="num">{amount(p.original)}</td>
              <td className="num">{amount(p.revised)}</td>
              <td className="num">{amount(p.basicDiff)}</td>
              <td className="num">{amount(p.daDiff)}</td>
              <td className="num">{amount(p.hraDiff)}</td>
              <td className="num">{amount(p.netArrear)}</td>
            </tr>
          ))}
        </Tbl>
        <KvCard
          title={t('i08.controls')}
          rows={[
            {
              k: t('i08.ctl.window'),
              s: 'ENT',
              v:
                controls.data?.maxRetroMonths === null || controls.data?.maxRetroMonths === undefined ? (
                  <span className="pill p-info">{t('i08.ctl.notConfigured')}</span>
                ) : (
                  t('i08.ctl.windowValue', [controls.data.maxRetroMonths])
                ),
            },
            { k: t('i08.ctl.crossFy'), s: 'RULE', v: t('i08.ctl.crossFyValue') },
            {
              k: t('i08.ctl.postRetirement'),
              s: 'RULE',
              v:
                controls.data?.postRetirementMonths === null ||
                controls.data?.postRetirementMonths === undefined ? (
                  <span className="pill p-info">{t('i08.ctl.notConfigured')}</span>
                ) : (
                  t('i08.ctl.windowValue', [controls.data.postRetirementMonths])
                ),
            },
            { k: t('i08.ctl.relief89'), s: 'RULE', v: t('i08.ctl.relief89Value') },
            { k: t('i08.ctl.cpf'), s: 'CALC', v: t('i08.ctl.cpfValue') },
            { k: t('i08.ctl.incentive'), s: 'CALC', v: t('i08.ctl.incentiveValue') },
            { k: t('i08.ctl.closed'), s: 'RULE', v: t('i08.ctl.closedValue') },
            { k: t('i08.ctl.reversal'), s: 'SYS', v: t('i08.ctl.reversalValue') },
          ]}
          after={<div className="oq">{t('i08.ctl.note')}</div>}
        />
      </div>
      <div className="fl-mt">
        <Tbl
          title={t('i08.scenarios')}
          cols={[
            { t: t('i08.col.scenario'), s: 'SYS' },
            { t: t('i08.col.event'), s: 'RULE' },
            { t: t('i08.col.monthly'), s: 'CALC', num: true },
            { t: t('i08.col.threeMonths'), s: 'CALC', num: true },
            { t: t('i08.col.statutoryEffect'), s: 'CALC' },
          ]}
        >
          {(
            [
              [1, '1,600', '4,800'],
              [2, '4,000', '8,000'],
              [3, '2,040', '6,120'],
              [4, t('i08.scn.negative'), t('i08.scn.recovery')],
            ] as const
          ).map(([n, monthly, three]) => (
            <tr key={n}>
              <td>{t(`i08.scn.${n}`)}</td>
              <td>{t(`i08.scn.${n}.event`)}</td>
              <td className="num">{monthly}</td>
              <td className="num">{three}</td>
              <td>{t(`i08.scn.${n}.statutory`)}</td>
            </tr>
          ))}
        </Tbl>
        <p className="sub2">{t('i08.worked.note')}</p>
      </div>
      <EventOrders event={selected} maker={maker} />
      {opened && <EventCard key={opened.id} event={opened} onClose={() => setOpened(null)} />}
      {creating && <NewEventDialog onClose={() => setCreating(false)} />}
      {/* The period labels read the language bundle through periodLabel (OD-123). */}
      <span hidden>{periodLabel(me.data?.period ?? '', locale)}</span>
    </div>
  );
}

/** "Undo event" over the selected event (SRS-21-6): the Maker asks the controlled undo with a reason. */
function UndoSelected({ event, maker }: { event: EventRow | null; maker: boolean }) {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const undo = useRequestUndo();
  const [asking, setAsking] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const right = maker && can(me.data, 'C-02', 'UNDO');
  function ask() {
    // Drawn enabled for every role (the pack's guard does not reach the "(OTP)" label); the right is the
    // Maker's with C-02 UNDO, the Administrator authorises with OTP (§6.6.4): anyone else is told why.
    if (!right) {
      toast(t('i08.why.undoRight'));
      return;
    }
    if (!event) {
      toast(t('i08.selectEvent'));
      return;
    }
    if (!mayUndo(event)) {
      toast(t('i08.why.undo'));
      return;
    }
    setError(null);
    setAsking(true);
  }
  async function send(reason: string) {
    if (!event) return;
    setError(null);
    try {
      const item = await undo.mutateAsync({
        id: event.id,
        period: cyclePeriod(event.postedToYymm ?? ''),
        reason: reason.trim(),
      });
      setAsking(false);
      toast(t('i08.undoRequested', [item.approvalId]));
    } catch (cause) {
      setError(cause);
    }
  }
  return (
    <>
      <Act label={t('i08.undoEventAct')} kind="sm" onClick={ask} />
      {asking && event && (
        <ReasonDialog
          title={t('i08.undo.title', [event.reference])}
          note={t('i08.undo.note')}
          confirmLabel={t('action.i08.undo')}
          error={error}
          busy={undo.isPending}
          onCancel={() => setAsking(false)}
          onConfirm={(reason) => void send(reason)}
        />
      )}
    </>
  );
}

/**
 * The drawn "Order copy — attach & store" card (FRS §6.4.3, A-41) for the selected event: the Maker attaches
 * the DA / HRA / rate circular or arrear order while the event is RAISED or COMPUTED; everyone in scope views.
 */
function EventOrders({ event, maker }: { event: EventRow | null; maker: boolean }) {
  const { t } = useI18n();
  const orders = useEventOrders(event?.id ?? null);
  const types = useOrderDocumentTypes();
  const attach = useAttachEventOrder();
  const open = event !== null && (event.status === 'RAISED' || event.status === 'COMPUTED');
  return (
    <OrderCopyCard
      key={event?.id ?? 0}
      hint={t('i08.ord.hint')}
      canAttach={maker && open}
      readOnly={!maker}
      stored={orders.data}
      storedError={orders.error ?? types.error}
      fileHref={(fileId) => eventOrderUrl(event?.id ?? 0, fileId)}
      today={businessToday()}
      // The I08_SUBMIT route (approval_workflow_action, V048_1) does not require an order: the card says
      // "no file attached yet", never "submit blocked" (wireframe vs route: PENDING WF-I08-ORD).
      attachmentRequired={false}
      referenceRequired={(code) => types.data?.find((d) => d.code === code)?.referenceRequired ?? false}
      onAttach={async (o) => {
        if (!event) return;
        await attach.mutateAsync({
          id: event.id,
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

/** "New Retro Event" (SRS-8-8): the event type from the database list, the span, the order. */
function NewEventDialog({ onClose }: { onClose: () => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const types = useArrearEventTypes(true);
  const orders = useRevisionOrders(true);
  const create = useCreateRetroEvent();
  const [form, setForm] = useState<NewRetroEvent>({
    eventCode: '',
    reference: '',
    payrollType: 'STAFF',
    effectiveFrom: '',
    effectiveTo: null,
    circularId: null,
  });
  const [error, setError] = useState<unknown>(null);
  const ready = form.eventCode !== '' && form.reference.trim() !== '' && form.effectiveFrom !== '';
  async function raise() {
    setError(null);
    try {
      const out = await create.mutateAsync({ ...form, reference: form.reference.trim() });
      toast(t('i08.new.raised', [out.id]));
      onClose();
    } catch (cause) {
      setError(cause);
    }
  }
  return (
    <div className="fl-modal-backdrop" role="presentation">
      <form
        className="fl-modal"
        role="dialog"
        aria-modal="true"
        aria-label={t('i08.new.title')}
        onSubmit={(e) => {
          e.preventDefault();
          if (ready) void raise();
        }}
      >
        <h2>{t('i08.new.title')}</h2>
        <Problem error={error ?? types.error} />
        {/* SRS-8-11, BR-LEAVE-003: only the types M-16 lists may be raised by hand (none until FCI decides, I08-3). */}
        {types.data?.length === 0 && <p className="fl-hint">{t('i08.new.notConfigured')}</p>}
        <label>
          {t('i08.new.type')}
          <Src s="ENT" />{' '}
          <select
            value={form.eventCode}
            onChange={(e) => setForm({ ...form, eventCode: e.target.value })}
            required
          >
            <option value="">{t('common.choose')}</option>
            {(types.data ?? []).map((x) => (
              <option key={x.code} value={x.code}>
                {x.code} - {x.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          {t('i08.new.reference')}
          <Src s="ENT" />{' '}
          <input
            value={form.reference}
            maxLength={100}
            onChange={(e) => setForm({ ...form, reference: e.target.value })}
            required
          />
        </label>
        <label>
          {t('i08.new.payrollType')}
          <Src s="ENT" />{' '}
          <select
            value={form.payrollType}
            onChange={(e) => setForm({ ...form, payrollType: e.target.value })}
          >
            {PAYROLL_TYPES.map((p) => (
              <option key={p} value={p}>
                {p} - {t(`i08.payrollType.${p}`)}
              </option>
            ))}
          </select>
        </label>
        <label>
          {t('i08.new.from')}
          <Src s="ENT" />{' '}
          <input
            type="date"
            value={form.effectiveFrom}
            onChange={(e) => setForm({ ...form, effectiveFrom: e.target.value })}
            required
          />
        </label>
        <label>
          {t('i08.new.to')}
          <Src s="ENT" />{' '}
          <input
            type="date"
            value={form.effectiveTo ?? ''}
            onChange={(e) => setForm({ ...form, effectiveTo: e.target.value || null })}
          />
        </label>
        <label>
          {t('i08.new.order')}
          <Src s="ENT" />{' '}
          <select
            value={form.circularId ?? ''}
            onChange={(e) => setForm({ ...form, circularId: e.target.value ? Number(e.target.value) : null })}
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
        <div className="toolbar">
          <button type="button" className="btn" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <span className="sp" />
          <button type="submit" className="btn pri" disabled={!ready || create.isPending}>
            {t('i08.new.raise')}
          </button>
        </div>
      </form>
    </div>
  );
}

function EventCard({ event, onClose }: { event: EventRow; onClose: () => void }) {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const submit = useSubmitEvent();
  const recompute = useRecomputeEvent();
  const [remark, setRemark] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [refused, setRefused] = useState<string | null>(null);
  // The status after a recompute here (the list row is refreshed behind the card).
  const [status, setStatus] = useState(event.status);
  // Review 13-9: the status follows the refreshed list row (a decision made meanwhile elsewhere).
  useEffect(() => setStatus(event.status), [event.status]);
  const current = { ...event, status };
  const mayAct = can(me.data, 'I-08', 'SUBMIT');
  const mayApprove = can(me.data, 'I-08', 'APPROVE');
  const open = maySubmit(current);

  async function computeAgain() {
    setError(null);
    setRefused(null);
    try {
      const outcome = await recompute.mutateAsync(event.id);
      if (outcome.computed) {
        setStatus('COMPUTED');
        toast(t('i08.computedAgain'));
      } else {
        setRefused(outcome.reason ?? '');
      }
    } catch (cause) {
      setError(cause);
    }
  }

  async function send() {
    setError(null);
    try {
      await submit.mutateAsync({ id: event.id, remark: remark.trim() });
      toast(t('i08.submitted'));
      onClose();
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <Card
      title={t('i08.eventTitle', [event.reference])}
      actions={
        <button type="button" className="btn sm" onClick={onClose}>
          {t('common.close')}
        </button>
      }
    >
      <Problem error={error} />
      {/* SRS-21-9 (l.3213-3221): the event's type, sanction reference, span, source and posting cycle. */}
      <div className="kv" aria-label={t('i08.eventHeader')}>
        <span className="k">{t('i08.eventType')}</span>
        <span>{event.eventName}</span>
        <span className="k">{t('i08.sanctionReference')}</span>
        <span>{event.reference}</span>
        <span className="k">{t('i08.span')}</span>
        <span>
          {date(event.effectiveFrom)} – {event.effectiveTo ? date(event.effectiveTo) : '—'}
        </span>
        <span className="k">{t('i08.sourceModule')}</span>
        <span>{event.source ?? '—'}</span>
        <span className="k">{t('i08.postedTo')}</span>
        <span>{event.postedToYymm ? month(event.postedToYymm) : '—'}</span>
      </div>
      {refused !== null && <p className="fl-hint">{t('i08.notComputable', [refused])}</p>}
      <ServerPagination
        queryKey={arrearKeys.details(event.id)}
        fetchPage={arrearPages.details(event.id)}
        empty={<Empty text={t('i08.noDetails')} />}
      >
        {(rows) => (
          <table className="t" aria-label={t('i08.details')}>
            <thead>
              <tr>
                <th>
                  {t('i08.member')}
                  <Src s="HRMS" />
                </th>
                <th>
                  {t('i08.month')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('i08.component')}
                  <Src s="RULE" />
                </th>
                <th>
                  {t('i08.original')}
                  <Src s="CALC" />
                </th>
                <th>
                  {t('i08.recomputed')}
                  <Src s="CALC" />
                </th>
                <th>
                  {t('i08.arrear')}
                  <Src s="CALC" />
                </th>
                <th>
                  {t('i08.status')}
                  <Src s="SYS" />
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((d, i) => (
                <tr key={`${d.eis}-${d.yymm}-${d.payCode}-${i}`}>
                  <td>
                    {d.eis} {d.name}
                  </td>
                  <td>{month(d.yymm)}</td>
                  <td>
                    {d.payCode} - {d.component}
                  </td>
                  <td className="num">{d.original === null ? '—' : amount(d.original)}</td>
                  <td className="num">{d.recomputed === null ? '—' : amount(d.recomputed)}</td>
                  <td className="num">{amount(d.arrear)}</td>
                  <td>{t(`i08.detailStatus.${d.status}`)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </ServerPagination>
      <div className="toolbar">
        <button
          type="button"
          className="btn"
          disabled={!mayAct || !event.singleMember || !mayRecompute(current) || recompute.isPending}
          title={
            !mayAct
              ? t('reason.makerOnly')
              : !event.singleMember
                ? t('i08.why.bulkRecompute')
                : current.stated
                  ? t('i08.why.stated')
                  : current.beyondWindow && current.hqException !== 'APPROVED'
                    ? t('i08.why.beyondWindow')
                    : !mayRecompute(current)
                      ? t('i08.why.recompute')
                      : undefined
          }
          onClick={() => void computeAgain()}
        >
          {t('action.i08.recompute')}
        </button>
        {mayApprove && current.status === 'COMPUTED' && (
          <a className="btn" href={withBase('/payroll/?s=W-01')}>
            {t('i08.approveInInbox')}
          </a>
        )}
        {event.beyondWindow && <HeadquartersException event={current} mayAct={mayAct} />}
        <label>
          {t('common.reason')}{' '}
          <TextAreaField
            maxLength={500}
            value={remark}
            disabled={!mayAct || !open}
            onChange={(e) => setRemark(e.target.value)}
          />
        </label>
        <button
          type="button"
          className="btn pri"
          disabled={!mayAct || !open || !remarkGiven(remark) || submit.isPending}
          title={
            !mayAct
              ? t('reason.makerOnly')
              : !open
                ? t('i08.why.submit')
                : !remarkGiven(remark)
                  ? t('i08.why.remark')
                  : undefined
          }
          onClick={() => void send()}
        >
          {t('action.i08.submit')}
        </button>
      </div>
    </Card>
  );
}

/**
 * SRS-8-6: the Maker asks Headquarters to process an event older than the back-date limit, with a
 * reason and its order (an Active M-25 circular); a Payroll Checker at Headquarters decides in W-01.
 */
function HeadquartersException({ event, mayAct }: { event: EventRow; mayAct: boolean }) {
  const { t } = useI18n();
  const toast = useToast();
  const ask = useRequestException();
  const askable = mayAct && mayAskHeadquarters(event);
  const orders = useRevisionOrders(askable);
  const [reason, setReason] = useState('');
  const [circularId, setCircularId] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [asked, setAsked] = useState(false);
  const ready = askable && !asked && remarkGiven(reason) && circularId !== '';

  async function send() {
    setError(null);
    try {
      await ask.mutateAsync({ id: event.id, reason: reason.trim(), circularId: Number(circularId) });
      setAsked(true);
      toast(t('i08.hqException.asked'));
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <fieldset>
      <legend>{t('i08.hqException.title')}</legend>
      <p className="fl-hint">{t('i08.beyondWindowHint')}</p>
      <Problem error={error} />
      <label>
        {t('i08.hqException.order')}{' '}
        <select
          value={circularId}
          disabled={!askable || asked}
          onChange={(e) => setCircularId(e.target.value)}
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
      <Problem error={orders.error} />
      <label>
        {t('i08.hqException.reason')}{' '}
        <TextAreaField
          maxLength={500}
          value={reason}
          disabled={!askable || asked}
          onChange={(e) => setReason(e.target.value)}
        />
      </label>
      <button
        type="button"
        className="btn"
        disabled={!ready || ask.isPending}
        title={
          !mayAct
            ? t('reason.makerOnly')
            : !mayAskHeadquarters(event) || asked
              ? t('i08.why.hqException')
              : !ready
                ? t('i08.why.hqExceptionInput')
                : undefined
        }
        onClick={() => void send()}
      >
        {t('action.i08.askHeadquarters')}
      </button>
    </fieldset>
  );
}

/**
 * Review 13-9 (SRS-8-14): the open event follows its refreshed list row, so a status changed in
 * another session (or by the server after a recompute) is the one the card's actions act on.
 */
function SyncSelected({
  rows,
  selected,
  onChange,
}: {
  rows: EventRow[];
  selected: EventRow | null;
  onChange: (row: EventRow) => void;
}) {
  useEffect(() => {
    const fresh = selected && rows.find((r) => r.id === selected.id);
    if (fresh && fresh.status !== selected.status) onChange(fresh);
  }, [rows, selected, onChange]);
  return null;
}
