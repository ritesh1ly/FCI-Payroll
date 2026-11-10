'use client';

import { type RefObject, useImperativeHandle, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import {
  Card,
  Empty,
  Problem,
  ReasonDialog,
  Src,
  date,
  dateTime,
  useToast,
  TextAreaField,
} from '@/shared/ui';
import type { GangOption } from '../api/captureApi';
import type { GangRow, PayrollType, RowEntry, Slip, SlipEntry } from '../api/labourApi';
import {
  emptyRow,
  heightBand,
  monthEnd,
  nextSlip,
  slipDates,
  previousMonth,
  rowIsEmpty,
  slipBlocked,
  slipEditable,
  slipRows,
  slipStatusKey,
} from '../business/labourRules';
import {
  useCancelSlip,
  useGangMembers,
  useSaveSlip,
  useSubmitSlip,
  useSubmitSlips,
  useWorkSlips,
} from '../orchestration/labourQueries';
import { GangPicker } from './GangPicker';

const PBNW_REASONS = ['RAKE_DELAYED', 'RAKE_NOT_PLACED', 'OTHER'] as const;

/** What the drawn buttons of the screen do to the closed capture section. */
export interface SlipControl {
  /** Opens the section on a gang and month; `create` starts a new slip. */
  open: (gang: GangOption | null, month: string, create: boolean) => void;
  hasEntry: () => boolean;
  addRow: () => void;
  save: () => void;
  saveNext: () => void;
  resetRow: () => void;
}

/**
 * The capture of I-02 Work-Slip Capture, kept whole in the closed section of the screen as drawn; the
 * drawn buttons drive it through {@link SlipControl}. Departmental (plan 20 phase 20c; SRS-2-3, SRS-2-4, SRS-2-5, SRS-2-15,
 * SRS-2-29, SRS-2-42, SRS-3-9): the Labour Dealing Hand chooses the gang and month and saves a
 * day's work-slip once the day's attendance is marked: rows of work by operation (M-27), bags and
 * stack height for bag-based work (the height band is the system's), the participating members.
 * Saving freezes the day's attendance. Save and Next saves and opens the next day's entry with the
 * gang and the last operation kept (BR-LABR-002). A saved slip is amended or cancelled with a reason,
 * never deleted; the month's slips are submitted to the Payroll Checker as one batch.
 *
 * DPS (plan 21 phase 21b; SRS-2-34, SRS-4-13, D21-3): the slips of a DPS gang are its booking-slip
 * register; each goes to the Depot Manager, a returned one is corrected and sent again, and the month
 * goes to the Payroll Checker once every booking-slip is approved.
 *
 * The gang is searched on the server, the depot's Ancillary Pool included (SRS-2-46; its slips have
 * no bag fields, l.4621). The labour offered are the gang's members on the slip date, labour
 * attached for that date included (l.4632). An amendment opens with the saved slip's rows
 * (SRS-2-15; SRS-2-4 "never clears data already entered").
 */
export function WorkSlipEntry({
  control,
  engine = null,
}: {
  control?: RefObject<SlipControl | null>;
  /** The payroll type the view opened with; null lists every type. */
  engine?: PayrollType | null;
}) {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const today = businessToday();
  const [gang, setGang] = useState<GangRow | null>(null);
  const gangId = gang?.gangId ?? null;
  const pool = gang?.gangType === 'ANCILLARY_POOL';
  const [month, setMonth] = useState(today.slice(0, 7));
  const view = useWorkSlips(gangId, month);
  const [entry, setEntry] = useState<SlipEntry | null>(null);
  // A new slip takes every member of the date until the maker ticks a box.
  const [allMembers, setAllMembers] = useState(false);
  const members = useGangMembers(gangId, entry?.date ?? '');
  const memberIds = (members.data ?? []).map((m) => m.employeeId);
  const save = useSaveSlip();
  const cancel = useCancelSlip();
  const submit = useSubmitSlips();
  const sendSlip = useSubmitSlip();
  const [amending, setAmending] = useState<Slip | null>(null);
  const [asking, setAsking] = useState<{ kind: 'amend' | 'cancel'; slip: Slip | null } | null>(null);
  const [error, setError] = useState<unknown>(null);
  const data = view.data;
  const editable = data !== undefined && (data.batchState === 'OPEN' || data.batchState === 'RETURNED');
  const mayCreate = can(me.data, 'I-02', 'CREATE');
  const mayEdit = can(me.data, 'I-02', 'EDIT');
  const maySubmit = can(me.data, 'I-02', 'SUBMIT');
  const dps = data?.payrollType === 'DPS_LABOUR';
  // Only the members of the slip date can be on it (SRS-2-4); a tick from another date drops.
  const picked = entry
    ? allMembers
      ? memberIds
      : entry.participants.filter((p) => memberIds.includes(p))
    : [];
  // BR-LABR-002: the slip is checked against the gang's month (its operations); until that month has loaded
  // there is nothing to check against, so Save waits ("Loading…") instead of sending an unchecked slip.
  let why: string | null = null;
  if (entry && !data) why = 'common.loading';
  else if (entry && data) why = slipBlocked({ ...entry, participants: picked }, data.operations, today);
  const operations = (data?.operations ?? []).filter((o) => !pool || !o.bagBased);
  const submitWhy = !maySubmit
    ? 'reason.ldhOnly'
    : month >= today.slice(0, 7)
      ? 'i01.why.monthOpen'
      : !editable
        ? 'i01.why.batch'
        : null;

  function start(slip: Slip | null) {
    setError(null);
    setAmending(slip);
    setAllMembers(slip === null);
    setEntry({
      date: slip?.date ?? '',
      continueWork: slip?.continueWork ?? false,
      rows: slip ? slipRows(slip) : [emptyRow()],
      participants: slip ? slip.participants.map((p) => p.employeeId) : [],
    });
  }

  function setRow(index: number, row: Partial<RowEntry>) {
    if (!entry) return;
    setEntry({ ...entry, rows: entry.rows.map((r, i) => (i === index ? { ...r, ...row } : r)) });
  }

  useImperativeHandle(control, () => ({
    open(option, nextMonth, create) {
      if (option) {
        setGang({
          gangId: option.id,
          gangCode: option.code,
          gangName: option.name,
          gangType: option.type,
        } as GangRow);
      }
      setMonth(nextMonth);
      if (create) start(null);
    },
    hasEntry: () => entry !== null,
    addRow() {
      if (entry && entry.rows.length < 20) setEntry({ ...entry, rows: [...entry.rows, emptyRow()] });
    },
    save() {
      if (why === null && !amending) void send();
      else if (why === null) setAsking({ kind: 'amend', slip: amending });
      else toast(t(why));
    },
    saveNext() {
      if (why === null && !amending) void send(undefined, true);
      else toast(t(why ?? 'i02w.why.noNextOnAmend'));
    },
    resetRow() {
      if (entry) setEntry({ ...entry, rows: [...entry.rows.slice(0, -1), emptyRow()] });
    },
  }));

  async function send(reason?: string, next = false) {
    if (!entry || gangId === null) return;
    setError(null);
    try {
      await save.mutateAsync({
        gangId,
        slip: { ...entry, participants: picked },
        amend: amending && reason ? { id: amending.id, reason } : undefined,
      });
      toast(t('i02.saved'));
      setAllMembers(next);
      // The saved slip's date is taken too: the view refreshes after the save.
      setEntry(next ? nextSlip(entry, [], [...slipDates(data?.slips ?? []), entry.date], today) : null);
      setAmending(null);
    } catch (cause) {
      setError(cause);
    }
  }

  async function withdraw(slip: Slip, reason: string) {
    setError(null);
    try {
      await cancel.mutateAsync({ id: slip.id, reason });
      toast(t('i02.cancelled', [slip.number]));
    } catch (cause) {
      setError(cause);
    }
  }

  async function sendToManager(slip: Slip) {
    setError(null);
    try {
      await sendSlip.mutateAsync(slip.id);
      toast(t('i02.slipSent', [slip.number]));
    } catch (cause) {
      setError(cause);
    }
  }

  async function sendBatch() {
    if (gangId === null) return;
    setError(null);
    try {
      await submit.mutateAsync({ gangId, month });
      toast(t('i01.submitted'));
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="wireframe-body-container">
      <Card title={t('screen.I-02')}>
        <div className="toolbar">
          <GangPicker
            label={t('m09.gang')}
            value={gangId}
            search={{ status: 'ACTIVE', payrollType: engine ?? undefined }}
            onChange={(g) => {
              setGang(g);
              setEntry(null);
            }}
          />
          <label>
            {t('i01.month')}{' '}
            <input
              type="month"
              value={month}
              max={today.slice(0, 7)}
              onChange={(e) => {
                setMonth(e.target.value);
                setEntry(null);
              }}
            />
          </label>
          <button type="button" className="btn" onClick={() => setMonth(previousMonth(today))}>
            {t('i01.lastMonth')}
          </button>
          <button
            type="button"
            className="btn primary"
            disabled={!mayCreate || !editable}
            title={!mayCreate ? t('reason.ldhOnly') : !editable ? t('i01.why.batch') : undefined}
            onClick={() => start(null)}
          >
            {t('action.i02.new')}
          </button>
        </div>
        {gangId === null && <Empty text={t('i01.chooseGang')} />}
        {data && (
          <>
            <p className="sub2" role="status">
              {data.gangName} · {data.depot} · {t(`i01.batch.${data.batchState}`)}
            </p>
            {data.batchState === 'RETURNED' && data.returnReason && (
              <p className="sub2" role="note" aria-label={t('i01.returnReason')}>
                {t('i01.returnedWith', [data.returnReason])}
              </p>
            )}
            {data.slips.length === 0 ? (
              <Empty text={t('i02.none')} />
            ) : (
              <table className="t" aria-label={dps ? t('i02.bookingRegister') : t('i02.slips')}>
                <thead>
                  <tr>
                    <th>
                      {t('i02.number')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('i02.date')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('i02.work')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('i02.strength')}
                      <Src s="CALC" />
                    </th>
                    <th>
                      {t('m02.status')}
                      <Src s="SYS" />
                    </th>
                    {dps && (
                      <>
                        <th>
                          {t('i02.enteredBy')}
                          <Src s="SYS" />
                        </th>
                        <th>
                          {t('i02.approvedBy')}
                          <Src s="SYS" />
                        </th>
                      </>
                    )}
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {data.slips.map((s) => (
                    <tr key={s.id}>
                      <td>{s.number}</td>
                      <td>
                        {date(s.date)}
                        {s.holiday && (
                          <div className="sub2">
                            {t('i02.holidayFlag')}
                            <Src s="SYS" />
                          </div>
                        )}
                      </td>
                      <td>
                        {s.rows.map((r) => (
                          <div key={r.order}>
                            {r.operation} · {r.start}-{r.end}
                            {r.bags !== null && ` · ${t('i02.bagsOf', [r.bags, r.bagType ?? ''])}`}
                            {r.heightBand && ` · ${t(`i02.band.${r.heightBand}`)}`}
                            {r.pbnwReason && ` · ${t(`i02.pbnw.${r.pbnwReason}`)}`}
                          </div>
                        ))}
                      </td>
                      <td>{s.labourStrength ?? '—'}</td>
                      <td>
                        {t(slipStatusKey(s.status, data.payrollType))}
                        {s.returnReason && (
                          <div className="sub2">{t('i02.returnedWith', [s.returnReason])}</div>
                        )}
                      </td>
                      {dps && (
                        <>
                          <td>{s.enteredBy ?? '—'}</td>
                          <td>
                            {s.decidedBy ?? '—'}
                            {s.decidedAt && <div className="sub2">{dateTime(s.decidedAt)}</div>}
                          </td>
                        </>
                      )}
                      <td>
                        {slipEditable(s.status, data.payrollType) && (
                          <>
                            <button
                              type="button"
                              className="btn"
                              disabled={!mayEdit}
                              title={mayEdit ? undefined : t('reason.ldhOnly')}
                              onClick={() => start(s)}
                            >
                              {t('action.i02.amend')}
                            </button>
                            <button
                              type="button"
                              className="btn"
                              disabled={!mayEdit}
                              title={mayEdit ? undefined : t('reason.ldhOnly')}
                              onClick={() => setAsking({ kind: 'cancel', slip: s })}
                            >
                              {t('action.i02.cancel')}
                            </button>
                            {dps && (
                              <button
                                type="button"
                                className="btn primary"
                                disabled={!maySubmit || sendSlip.isPending}
                                title={maySubmit ? undefined : t('reason.ldhOnly')}
                                onClick={() => void sendToManager(s)}
                              >
                                {t('action.i02.sendSlip')}
                              </button>
                            )}
                          </>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {entry && (
              <Card title={amending ? t('i02.amendOf', [amending.number]) : t('i02.new')}>
                <div className="form-grid">
                  <label>
                    {t('i02.date')}
                    <Src s="ENT" />
                    <input
                      type="date"
                      value={entry.date}
                      // I-02 l.4619 "within the open period": a day of the chosen month, up to today.
                      min={`${month}-01`}
                      max={monthEnd(month) < today ? monthEnd(month) : today}
                      disabled={amending !== null}
                      onChange={(e) => setEntry({ ...entry, date: e.target.value })}
                    />
                  </label>
                  <label>
                    <input
                      type="checkbox"
                      checked={entry.continueWork}
                      onChange={(e) => setEntry({ ...entry, continueWork: e.target.checked })}
                    />{' '}
                    {t('i02.continueWork')}
                  </label>
                </div>
                {entry.rows.map((r, i) => {
                  const op = data.operations.find((o) => o.id === r.operationId);
                  return (
                    <fieldset key={i} className="form-grid">
                      <legend>{t('i02.rowOf', [i + 1])}</legend>
                      <label>
                        {t('i02.operation')}
                        <Src s="ENT" />
                        <select
                          value={r.operationId ?? ''}
                          onChange={(e) =>
                            setRow(i, {
                              ...emptyRow(),
                              start: r.start,
                              end: r.end,
                              operationId: Number(e.target.value) || null,
                            })
                          }
                        >
                          <option value="">—</option>
                          {operations.map((o) => (
                            <option key={o.id} value={o.id}>
                              {o.code} - {o.name}
                            </option>
                          ))}
                        </select>
                      </label>
                      {op?.bagBased && !pool && (
                        <>
                          <label>
                            {t('i02.bagType')}
                            <Src s="ENT" />
                            <select
                              value={r.bagTypeId ?? ''}
                              onChange={(e) => setRow(i, { bagTypeId: Number(e.target.value) || null })}
                            >
                              <option value="">—</option>
                              {data.bagTypes.map((b) => (
                                <option key={b.id} value={b.id}>
                                  {b.code} - {b.name}
                                </option>
                              ))}
                            </select>
                          </label>
                          <label>
                            {t('i02.bags')}
                            <Src s="ENT" />
                            <input
                              inputMode="numeric"
                              maxLength={5}
                              value={r.bags ?? ''}
                              onChange={(e) =>
                                setRow(i, { bags: e.target.value ? Number(e.target.value) : null })
                              }
                            />
                          </label>
                          <label>
                            {t('i02.stack')}
                            <Src s="ENT" />
                            <input
                              inputMode="numeric"
                              maxLength={4}
                              value={r.stackHeight ?? ''}
                              onChange={(e) =>
                                setRow(i, { stackHeight: e.target.value ? Number(e.target.value) : null })
                              }
                            />
                            {r.stackHeight !== null && (
                              <span className="sub2"> {t(`i02.band.${heightBand(r.stackHeight)}`)}</span>
                            )}
                          </label>
                        </>
                      )}
                      {op?.pbnw && (
                        <>
                          <label>
                            {t('i02.pbnwReason')}
                            <Src s="ENT" />
                            <select
                              value={r.pbnwReason ?? ''}
                              onChange={(e) => setRow(i, { pbnwReason: e.target.value || null })}
                            >
                              <option value="">—</option>
                              {PBNW_REASONS.map((p) => (
                                <option key={p} value={p}>
                                  {t(`i02.pbnw.${p}`)}
                                </option>
                              ))}
                            </select>
                          </label>
                          <label>
                            {t('i02.pbnwText')}
                            <Src s="ENT" />
                            <TextAreaField
                              maxLength={500}
                              value={r.pbnwText}
                              onChange={(e) => setRow(i, { pbnwText: e.target.value })}
                            />
                          </label>
                          <label>
                            {t('i02.rake')}
                            <Src s="ENT" />
                            <input
                              maxLength={60}
                              value={r.rakeReference}
                              onChange={(e) => setRow(i, { rakeReference: e.target.value })}
                            />
                          </label>
                        </>
                      )}
                      <label>
                        {t('i02.start')}
                        <Src s="ENT" />
                        <input
                          type="time"
                          value={r.start}
                          onChange={(e) => setRow(i, { start: e.target.value })}
                        />
                      </label>
                      <label>
                        {t('i02.end')}
                        <Src s="ENT" />
                        <input
                          type="time"
                          value={r.end}
                          onChange={(e) => setRow(i, { end: e.target.value })}
                        />
                      </label>
                      {entry.rows.length > 1 && (
                        <button
                          type="button"
                          className="btn"
                          disabled={!rowIsEmpty(r)}
                          title={rowIsEmpty(r) ? undefined : t('i02.why.rowNotEmpty')}
                          onClick={() => setEntry({ ...entry, rows: entry.rows.filter((_, j) => j !== i) })}
                        >
                          {t('action.i02.removeRow')}
                        </button>
                      )}
                    </fieldset>
                  );
                })}
                <div className="toolbar">
                  <button
                    type="button"
                    className="btn"
                    disabled={entry.rows.length >= 20}
                    onClick={() => setEntry({ ...entry, rows: [...entry.rows, emptyRow()] })}
                  >
                    {t('action.i02.addRow')}
                  </button>
                  {/* G5-53 (DoD 4): I-02 overtime per person (SRS-2-45, PENDING LBR-20c-1) and the Anna Darpan
                      records to confirm (SRS-2-27, PENDING API-23-4) are shown disabled with the reason. */}
                  <button type="button" className="btn" disabled title={t('i02.why.overtime')}>
                    {t('action.i02.overtime')}
                  </button>
                  <button type="button" className="btn" disabled title={t('i02.why.annaDarpan')}>
                    {t('action.i02.annaDarpan')}
                  </button>
                </div>
                <fieldset>
                  {/* SRS-5-13: the NWNP slip names its labour list "Labours" (Label Master standard name). */}
                  <legend>
                    {t(data?.payrollType === 'NWNP_LABOUR' ? 'i02.labours' : 'i02.participants')}
                  </legend>
                  {(members.data ?? []).map((m) => (
                    <label key={m.employeeId}>
                      <input
                        type="checkbox"
                        checked={picked.includes(m.employeeId)}
                        onChange={(e) => {
                          setAllMembers(false);
                          setEntry({
                            ...entry,
                            participants: e.target.checked
                              ? [...picked, m.employeeId]
                              : picked.filter((p) => p !== m.employeeId),
                          });
                        }}
                      />{' '}
                      {m.labourCode} - {m.name} ({t(`m09.role.${m.role}`)})
                    </label>
                  ))}
                </fieldset>
                <div className="toolbar">
                  <button
                    type="button"
                    className="btn primary"
                    disabled={why !== null || save.isPending}
                    title={why ? t(why) : undefined}
                    onClick={() => (amending ? setAsking({ kind: 'amend', slip: amending }) : void send())}
                  >
                    {t('action.i02.save')}
                  </button>
                  {!amending && (
                    <button
                      type="button"
                      className="btn"
                      disabled={why !== null || save.isPending}
                      title={why ? t(why) : undefined}
                      onClick={() => void send(undefined, true)}
                    >
                      {t('action.i02.saveNext')}
                    </button>
                  )}
                  <button
                    type="button"
                    className="btn"
                    onClick={() => {
                      setEntry(null);
                      setAmending(null);
                    }}
                  >
                    {t('common.cancel')}
                  </button>
                </div>
                {why && <p className="sub2">{t(why)}</p>}
              </Card>
            )}
            <div className="toolbar">
              <button
                type="button"
                className="btn primary"
                disabled={submitWhy !== null || submit.isPending}
                title={submitWhy ? t(submitWhy) : undefined}
                onClick={() => void sendBatch()}
              >
                {t('action.i02.submit')}
              </button>
            </div>
          </>
        )}
        <Problem error={error ?? view.error} />
      </Card>
      {asking && (
        <ReasonDialog
          title={
            asking.kind === 'amend' ? t('i02.amendWhy') : t('i02.cancelWhy', [asking.slip?.number ?? ''])
          }
          onCancel={() => setAsking(null)}
          onConfirm={(reason) => {
            const ask = asking;
            setAsking(null);
            if (ask.kind === 'amend') void send(reason);
            else if (ask.slip) void withdraw(ask.slip, reason);
          }}
        />
      )}
    </div>
  );
}
