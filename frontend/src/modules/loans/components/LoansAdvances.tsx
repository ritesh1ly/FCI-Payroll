'use client';

import { useEffect, useRef, useState } from 'react';
import {
  EMPTY_ORDER,
  OrderFields,
  orderComplete,
  todayIso,
  type OrderDraft,
  useDocumentTypes,
} from '@/modules/general';
import { useI18n } from '@/shared/i18n';
import { businessYymm } from '@/shared/time';
import { can, useMe } from '@/shared/session';
import { Card, Empty, ExportButton, Problem, ServerPagination, Src, useToast, MonthField } from '@/shared/ui';
import type { LoanDocument, LoanRow, SanctionInput, SanctionPreview } from '../api/loansApi';
import {
  changeable,
  hasInterestOutstanding,
  isYymm,
  rescheduleReady,
  sanctionReady,
  tracker,
} from '../business/loanRules';
import {
  loanKeys,
  loanPage,
  loanReportUrl,
  requestPage,
  useLoanActions,
  useLoanDetail,
  useLoanTypes,
} from '../orchestration/loanQueries';

function rupees(n: number | null | undefined): string {
  return n === null || n === undefined
    ? '—'
    : `₹${Number(n).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function asDocument(o: OrderDraft): LoanDocument | null {
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

const EMPTY_FORM = {
  eis: '',
  loanType: '',
  sanctioned: '',
  principalInstalments: '',
  instalment: '',
  firstInstalment: '',
  interestAmount: '',
  interestInstalments: '',
  interestInstalment: '',
  startYymm: '',
};

/**
 * I-04 Loans & Advances view (plan 09 phase 09f; SRS-12-1, SRS-12-7, SRS-12-8, SRS-12-11,
 * SRS-12-17, SRS-12-21 … 12-23). The unit's loans with principal and interest apart and the
 * recovery tracker; the requests waiting for the Checker or returned with the reason; the Payroll
 * Maker sets up a sanction against its order for the types not held in HRMS (checked first: the
 * schedule and the maximum new sanction), forecloses with the payment receipt (the Checker approves
 * with OTP in the Approvals Inbox) or reschedules with the signed request. An employee loan from
 * HRMS is read-only here.
 */
export function LoansAdvances({
  opening = null,
  startNew = false,
}: {
  opening?: number | null;
  startNew?: boolean;
}) {
  const { t } = useI18n();
  const me = useMe();
  const may = (action: string) => can(me.data, 'I-04', action);
  const [eis, setEis] = useState('');
  const [status, setStatus] = useState('');
  const [openId, setOpenId] = useState<number | null>(opening);
  // The drawn "New Loan Sanction" lands on the form at the end of the section.
  const form = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (startNew) form.current?.scrollIntoView?.({ block: 'start' });
  }, [startNew]);
  // SRS-12-7 (l.2659): a returned request is corrected and resubmitted from the Maker's form.
  const [correcting, setCorrecting] = useState<{
    key: number;
    eis: string;
    loanType: string;
    amount: string;
  } | null>(null);
  const filter = { eis: eis.trim() || undefined, status: status || undefined };
  if (!may('VIEW')) return null;

  return (
    <>
      <Card title={t('i04l.loans')}>
        <div className="toolbar">
          <label>
            {t('m01.eis')} <input maxLength={20} value={eis} onChange={(e) => setEis(e.target.value)} />
          </label>
          <label>
            {t('i04l.status')}{' '}
            <select value={status} onChange={(e) => setStatus(e.target.value)}>
              <option value="">{t('i04l.allStatuses')}</option>
              {['ACTIVE', 'RESCHEDULED', 'STOPPED', 'CLOSED', 'FORECLOSED', 'SETTLED_IN_FNF'].map((s) => (
                <option key={s} value={s}>
                  {t(`i04l.status.${s}`)}
                </option>
              ))}
            </select>
          </label>
        </div>
        <ServerPagination
          queryKey={loanKeys.list(filter)}
          fetchPage={loanPage(filter)}
          empty={<Empty text={t('i04l.none')} />}
        >
          {(rows) => (
            <table className="t" aria-label={t('i04l.loans')}>
              <thead>
                <tr>
                  <th>
                    {t('i04l.number')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('i04l.member')}
                    <Src s="HRMS" />
                  </th>
                  <th>
                    {t('i04l.type')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('i04l.sanctioned')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('i04l.instalment')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('i04l.principalOutstanding')}
                    <Src s="CALC" />
                  </th>
                  <th>
                    {t('i04l.interestOutstanding')}
                    <Src s="CALC" />
                  </th>
                  <th>
                    {t('i04l.status')}
                    <Src s="SYS" />
                  </th>
                  <th aria-label={t('common.actions')} />
                </tr>
              </thead>
              <tbody>
                {rows.map((l) => (
                  <tr key={l.id} data-reference={l.loanNumber}>
                    <td>{l.loanNumber}</td>
                    <td>
                      {l.eis} {l.employee}
                    </td>
                    {/* SRS l.2713-2714: an employee loan's type and amount are HRMS's, read-only. */}
                    <td>
                      {t(`i04l.type.${l.loanType}`)}
                      {l.source === 'HRMS' && <Src s="HRMS" />}
                    </td>
                    <td>
                      {rupees(l.sanctioned)}
                      {l.source === 'HRMS' && <Src s="HRMS" />}
                    </td>
                    <td>
                      {rupees(l.instalment)}
                      <div className="sub2">
                        {t('i04l.paidOf', [l.paidInstalments, l.principalInstalments])}
                      </div>
                    </td>
                    <td>{rupees(l.principalOutstanding)}</td>
                    <td>{rupees(l.interestOutstanding)}</td>
                    <td>
                      {t(`i04l.status.${l.status}`)}
                      {l.source === 'HRMS' && <div className="sub2">{t('i04l.fromHrms')}</div>}
                    </td>
                    <td>
                      <button
                        type="button"
                        className="ia"
                        aria-label={t('i04l.openLoan', [l.loanNumber])}
                        onClick={() => setOpenId(l.id)}
                      >
                        {t('common.view')}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </ServerPagination>
      </Card>
      {openId !== null && <LoanPanel key={openId} id={openId} onClose={() => setOpenId(null)} />}
      <Card title={t('i04l.requests')}>
        <ServerPagination
          queryKey={loanKeys.requests()}
          fetchPage={requestPage()}
          empty={<Empty text={t('i04l.noRequests')} />}
        >
          {(rows) => (
            <table className="t" aria-label={t('i04l.requests')}>
              <thead>
                <tr>
                  <th>
                    {t('i04l.member')}
                    <Src s="HRMS" />
                  </th>
                  <th>
                    {t('i04l.type')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('i04l.sanctioned')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('i04l.status')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('i04l.returnReason')}
                    <Src s="ENT" />
                  </th>
                  <th aria-label={t('common.actions')} />
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.approvalId}>
                    <td>
                      {r.eis} {r.employee}
                    </td>
                    <td>{r.loanType ? t(`i04l.type.${r.loanType}`) : '—'}</td>
                    <td>{rupees(r.amount)}</td>
                    <td>{t(`i04l.request.${r.status}`)}</td>
                    <td>{r.returnReason ?? '—'}</td>
                    <td>
                      {r.status === 'RETURNED' &&
                        (may('LOAN_SANCTION') ? (
                          <button
                            type="button"
                            className="ia"
                            aria-label={t('i04l.correctOf', [r.approvalId])}
                            onClick={() =>
                              setCorrecting({
                                key: r.approvalId,
                                eis: r.eis,
                                loanType: r.loanType ?? '',
                                amount: r.amount === null ? '' : String(r.amount),
                              })
                            }
                          >
                            {t('i04l.correct')}
                          </button>
                        ) : (
                          <button type="button" className="ia" disabled title={t('i04l.correctWhy')}>
                            {t('i04l.correct')}
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
      {may('EXPORT') && <Report />}
      {may('LOAN_SANCTION') && (
        <div ref={form}>
          <SanctionForm
            key={correcting?.key ?? 'new'}
            correcting={correcting}
            onDone={() => setCorrecting(null)}
          />
        </div>
      )}
    </>
  );
}

/** SRS-12-26: the Loan Recovery & Outstanding Report for a range of months. */
function Report() {
  const { t } = useI18n();
  const [from, setFrom] = useState(() => businessYymm());
  const [to, setTo] = useState(() => businessYymm());
  const ok = isYymm(from) && isYymm(to) && from <= to;
  return (
    <Card title={t('i04l.report')}>
      <div className="toolbar">
        <label>
          {t('i14.from')} <MonthField value={from} onChange={setFrom} />
        </label>
        <label>
          {t('i14.to')} <MonthField value={to} onChange={setTo} />
        </label>
        {ok ? (
          <ExportButton
            href={loanReportUrl(from, to)}
            what={t('i04l.report')}
            label={t('i04l.reportExport')}
          />
        ) : (
          <button type="button" className="btn" disabled title={t('i14.badRange')}>
            {t('i04l.reportExport')}
          </button>
        )}
      </div>
    </Card>
  );
}

/** One loan: the recovery tracker, the schedule versions and the changes the Maker may submit. */
function LoanPanel({ id, onClose }: { id: number; onClose: () => void }) {
  const { t } = useI18n();
  const me = useMe();
  const detail = useLoanDetail(id);
  const d = detail.data;
  if (!d) return <Problem error={detail.error} />;
  const loan: LoanRow = d.loan;
  const track = tracker(loan);
  return (
    <section className="card" aria-label={loan.loanNumber}>
      <div className="hd">
        <h3>{t('i04l.loanTitle', [loan.loanNumber, loan.eis])}</h3>
        <button type="button" className="btn" onClick={onClose}>
          {t('common.close')}
        </button>
      </div>
      <dl className="kv" aria-label={t('i04l.tracker')}>
        <dt>{t('i04l.sanctioned')}</dt>
        <dd>{rupees(track.total)}</dd>
        <dt>{t('i04l.recovered')}</dt>
        <dd>{rupees(track.recovered)}</dd>
        <dt>{t('i04l.outstanding')}</dt>
        <dd>
          {rupees(track.outstanding)} ({track.percent}%)
        </dd>
        <dt>{t('i04l.nextInstalment')}</dt>
        <dd>
          {d.nextInstalment
            ? t('i04l.nextInstalmentLine', [
                rupees(d.nextInstalment.principal + d.nextInstalment.interest),
                d.nextInstalment.payrollYymm,
              ])
            : t('i04l.noNextInstalment')}
        </dd>
      </dl>
      {d.versions.map((v) => (
        <table
          key={v.versionNo}
          className="t"
          aria-label={t('i04l.version', [v.versionNo, t(`i04l.reason.${v.reason}`)])}
        >
          <caption>
            {t('i04l.version', [v.versionNo, t(`i04l.reason.${v.reason}`)])}
            {v.supersededAt && <span className="sub2"> {t('i04l.superseded')}</span>}
          </caption>
          <thead>
            <tr>
              <th>
                #
                <Src s="SYS" />
              </th>
              <th>
                {t('i04l.month')}
                <Src s="CALC" />
              </th>
              <th>
                {t('i04l.principal')}
                <Src s="CALC" />
              </th>
              <th>
                {t('i04l.interest')}
                <Src s="CALC" />
              </th>
            </tr>
          </thead>
          <tbody>
            {v.lines.map((l) => (
              <tr key={l.number}>
                <td>{l.number}</td>
                <td>{l.payrollYymm}</td>
                <td>{rupees(l.principal)}</td>
                <td>{rupees(l.interest)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ))}
      {changeable(loan) && can(me.data, 'I-04', 'LOAN_FORECLOSE') && <Foreclose loan={loan} />}
      {changeable(loan) && can(me.data, 'I-04', 'LOAN_RESCHEDULE') && <Reschedule loan={loan} />}
      {loan.source === 'HRMS' && <p className="fl-hint">{t('i04l.hrmsReadOnly')}</p>}
    </section>
  );
}

function Foreclose({ loan }: { loan: LoanRow }) {
  const { t } = useI18n();
  const toast = useToast();
  const types = useDocumentTypes(true);
  const actions = useLoanActions();
  const [order, setOrder] = useState<OrderDraft>(EMPTY_ORDER);
  const [receipt, setReceipt] = useState<OrderDraft>(EMPTY_ORDER);
  const [error, setError] = useState<unknown>(null);
  const orderOk = orderComplete(order, types.data ?? [], todayIso());
  const ready = orderOk && orderComplete(receipt, types.data ?? [], todayIso());
  return (
    <fieldset>
      <legend>{t('i04l.foreclose')}</legend>
      <p className="sub2">
        {t('i04l.forecloseHint', [rupees(loan.principalOutstanding + loan.interestOutstanding)])}
      </p>
      <p className="sub2">{t('i04l.changeOrder')}</p>
      <OrderFields
        idPrefix="fco"
        value={order}
        types={types.data ?? []}
        today={todayIso()}
        onChange={setOrder}
      />
      <p className="sub2">{t('i04l.paymentReceipt')}</p>
      <OrderFields
        idPrefix="fc"
        value={receipt}
        types={types.data ?? []}
        today={todayIso()}
        onChange={setReceipt}
      />
      <Problem error={error} />
      <button
        type="button"
        className="btn pri"
        disabled={!ready || actions.foreclose.isPending}
        title={ready ? undefined : t(orderOk ? 'i04l.receiptMissing' : 'i04l.changeOrderMissing')}
        onClick={async () => {
          const doc = asDocument(receipt);
          const orderDoc = asDocument(order);
          if (!doc || !orderDoc) return;
          setError(null);
          try {
            await actions.foreclose.mutateAsync({ id: loan.id, order: orderDoc, receipt: doc });
            toast(t('i04l.sentToChecker'));
          } catch (cause) {
            setError(cause);
          }
        }}
      >
        {t('i04l.sendForeclosure')}
      </button>
    </fieldset>
  );
}

function Reschedule({ loan }: { loan: LoanRow }) {
  const { t } = useI18n();
  const toast = useToast();
  const types = useDocumentTypes(true);
  const actions = useLoanActions();
  const [n, setN] = useState('');
  const [instalment, setInstalment] = useState('');
  const [start, setStart] = useState('');
  const [interestN, setInterestN] = useState('');
  const [interestInstalment, setInterestInstalment] = useState('');
  const [order, setOrder] = useState<OrderDraft>(EMPTY_ORDER);
  const [signed, setSigned] = useState<OrderDraft>(EMPTY_ORDER);
  const [error, setError] = useState<unknown>(null);
  const orderOk = orderComplete(order, types.data ?? [], todayIso());
  const interest = hasInterestOutstanding(loan);
  const termsOk = rescheduleReady(loan, {
    instalments: n,
    instalment,
    interestInstalments: interestN,
    interestInstalment,
    start,
  });
  const ready = termsOk && orderOk && orderComplete(signed, types.data ?? [], todayIso());
  return (
    <fieldset>
      <legend>{t('i04l.reschedule')}</legend>
      <p className="sub2">{t('i04l.rescheduleHint', [rupees(loan.principalOutstanding)])}</p>
      <label>
        {t('i04l.instalments')}{' '}
        <input maxLength={4} inputMode="numeric" value={n} onChange={(e) => setN(e.target.value)} />
      </label>
      <label>
        {t('i04l.instalment')}{' '}
        <input
          maxLength={13}
          inputMode="decimal"
          value={instalment}
          onChange={(e) => setInstalment(e.target.value)}
        />
      </label>
      <label>
        {t('i04l.start')} <MonthField value={start} onChange={setStart} />
      </label>
      {interest && (
        <>
          <p className="sub2">{t('i04l.rescheduleInterestHint', [rupees(loan.interestOutstanding)])}</p>
          <label>
            {t('i04l.interestInstalments')}{' '}
            <input
              maxLength={4}
              inputMode="numeric"
              value={interestN}
              onChange={(e) => setInterestN(e.target.value)}
            />
          </label>
          <label>
            {t('i04l.interestInstalment')}{' '}
            <input
              maxLength={13}
              inputMode="decimal"
              value={interestInstalment}
              onChange={(e) => setInterestInstalment(e.target.value)}
            />
          </label>
        </>
      )}
      <p className="sub2">{t('i04l.changeOrder')}</p>
      <OrderFields
        idPrefix="rso"
        value={order}
        types={types.data ?? []}
        today={todayIso()}
        onChange={setOrder}
      />
      <p className="sub2">{t('i04l.signedRequest')}</p>
      <OrderFields
        idPrefix="rs"
        value={signed}
        types={types.data ?? []}
        today={todayIso()}
        onChange={setSigned}
      />
      <Problem error={error} />
      <button
        type="button"
        className="btn pri"
        disabled={!ready || actions.reschedule.isPending}
        title={
          ready
            ? undefined
            : t(
                !orderOk
                  ? 'i04l.changeOrderMissing'
                  : termsOk
                    ? 'i04l.signedRequestMissing'
                    : 'i04l.rescheduleTermsMissing',
              )
        }
        onClick={async () => {
          const doc = asDocument(signed);
          const orderDoc = asDocument(order);
          if (!doc || !orderDoc) return;
          setError(null);
          try {
            await actions.reschedule.mutateAsync({
              id: loan.id,
              body: {
                principalInstalments: Number(n),
                instalment: Number(instalment),
                interestInstalments: interest ? Number(interestN) : 0,
                interestInstalment: interest ? Number(interestInstalment) : null,
                startYymm: start,
              },
              order: orderDoc,
              signed: doc,
            });
            toast(t('i04l.sentToChecker'));
          } catch (cause) {
            setError(cause);
          }
        }}
      >
        {t('i04l.sendReschedule')}
      </button>
    </fieldset>
  );
}

/** SRS-12-17 / SRS-12-18: a sanction of a type not held in HRMS, against its order. */
function SanctionForm({
  correcting = null,
  onDone = () => undefined,
}: {
  correcting?: { key: number; eis: string; loanType: string; amount: string } | null;
  onDone?: () => void;
} = {}) {
  const { t } = useI18n();
  const toast = useToast();
  const loanTypes = useLoanTypes();
  const docTypes = useDocumentTypes(true);
  const actions = useLoanActions();
  const [f, setF] = useState(() =>
    correcting
      ? { ...EMPTY_FORM, eis: correcting.eis, loanType: correcting.loanType, sanctioned: correcting.amount }
      : EMPTY_FORM,
  );
  const [order, setOrder] = useState<OrderDraft>(EMPTY_ORDER);
  const [preview, setPreview] = useState<SanctionPreview | null>(null);
  const [reviewed, setReviewed] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const set = (k: keyof typeof EMPTY_FORM) => (e: { target: { value: string } }) => {
    setF({ ...f, [k]: e.target.value });
    setPreview(null);
  };
  const num = (v: string) => (v.trim() === '' ? null : Number(v));
  const input = (): SanctionInput => ({
    eis: f.eis.trim(),
    loanType: f.loanType,
    sanctioned: Number(f.sanctioned),
    principalInstalments: Number(f.principalInstalments),
    instalment: Number(f.instalment),
    firstInstalment: num(f.firstInstalment),
    interestAmount: num(f.interestAmount),
    interestInstalments: Number(f.interestInstalments || 0),
    interestInstalment: num(f.interestInstalment),
    startYymm: f.startYymm,
    reviewedOpenRequest: reviewed,
  });
  const ready = sanctionReady(f);
  const orderOk = orderComplete(order, docTypes.data ?? [], todayIso());

  async function check() {
    setError(null);
    try {
      setPreview(await actions.preview.mutateAsync(input()));
    } catch (cause) {
      setPreview(null);
      setError(cause);
    }
  }

  async function send() {
    const doc = asDocument(order);
    if (!doc) return;
    setError(null);
    try {
      await actions.submit.mutateAsync({ body: input(), order: doc });
      toast(t('i04l.sentToChecker'));
      setF(EMPTY_FORM);
      setOrder(EMPTY_ORDER);
      setPreview(null);
      setReviewed(false);
      onDone();
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <Card title={t('i04l.newSanction')}>
      <p className="sub2">{t('i04l.newSanctionHint')}</p>
      {correcting && (
        <p className="fl-hint" role="status">
          {t('i04l.correcting', [correcting.key])}{' '}
          <button type="button" className="ia" onClick={onDone}>
            {t('common.cancel')}
          </button>
        </p>
      )}
      <div className="rf-grid">
        <label>
          {t('m01.eis')} <input maxLength={20} value={f.eis} onChange={set('eis')} />
        </label>
        <label>
          {t('i04l.type')}{' '}
          <select value={f.loanType} onChange={set('loanType')}>
            <option value="">—</option>
            {(loanTypes.data ?? []).map((x) => (
              <option key={x.code} value={x.code}>
                {x.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          {t('i04l.sanctioned')}{' '}
          <input maxLength={15} inputMode="decimal" value={f.sanctioned} onChange={set('sanctioned')} />
        </label>
        <label>
          {t('i04l.instalments')}{' '}
          <input
            maxLength={4}
            inputMode="numeric"
            value={f.principalInstalments}
            onChange={set('principalInstalments')}
          />
        </label>
        <label>
          {t('i04l.instalment')}{' '}
          <input maxLength={13} inputMode="decimal" value={f.instalment} onChange={set('instalment')} />
        </label>
        <label>
          {t('i04l.firstInstalment')}{' '}
          <input
            maxLength={13}
            inputMode="decimal"
            value={f.firstInstalment}
            onChange={set('firstInstalment')}
          />
        </label>
        <label>
          {t('i04l.interestAmount')}{' '}
          <input
            maxLength={15}
            inputMode="decimal"
            value={f.interestAmount}
            onChange={set('interestAmount')}
          />
        </label>
        <label>
          {t('i04l.interestInstalments')}{' '}
          <input
            maxLength={4}
            inputMode="numeric"
            value={f.interestInstalments}
            onChange={set('interestInstalments')}
          />
        </label>
        <label>
          {t('i04l.interestInstalment')}{' '}
          <input
            maxLength={13}
            inputMode="decimal"
            value={f.interestInstalment}
            onChange={set('interestInstalment')}
          />
        </label>
        <label>
          {t('i04l.start')}{' '}
          <MonthField value={f.startYymm} onChange={(v) => set('startYymm')({ target: { value: v } })} />
        </label>
      </div>
      <button
        type="button"
        className="btn"
        disabled={!ready || actions.preview.isPending}
        onClick={() => void check()}
      >
        {t('i04l.check')}
      </button>
      <Problem error={error} />
      {preview && (
        <div aria-label={t('i04l.preview')}>
          <p>{t('i04l.maxNew', [rupees(preview.maxNewSanction), rupees(preview.outstanding)])}</p>
          {preview.reduced && (
            <p className="warn">
              {t('i04l.reduced', [rupees(preview.terms.instalment), preview.terms.principalInstalments])}
            </p>
          )}
          {preview.openRequest !== null && (
            <label className="warn">
              <input type="checkbox" checked={reviewed} onChange={(e) => setReviewed(e.target.checked)} />{' '}
              {t('i04l.openRequest', [preview.openRequest])}
            </label>
          )}
          <table className="t" aria-label={t('i04l.schedule')}>
            <thead>
              <tr>
                <th>
                  #
                  <Src s="SYS" />
                </th>
                <th>
                  {t('i04l.month')}
                  <Src s="CALC" />
                </th>
                <th>
                  {t('i04l.principal')}
                  <Src s="CALC" />
                </th>
                <th>
                  {t('i04l.interest')}
                  <Src s="CALC" />
                </th>
              </tr>
            </thead>
            <tbody>
              {preview.schedule.map((l) => (
                <tr key={l.number}>
                  <td>{l.number}</td>
                  <td>{l.payrollYymm}</td>
                  <td>{rupees(l.principal)}</td>
                  <td>{rupees(l.interest)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <OrderFields
            idPrefix="ls"
            value={order}
            types={docTypes.data ?? []}
            today={todayIso()}
            onChange={setOrder}
          />
          <button
            type="button"
            className="btn pri"
            disabled={!orderOk || (preview.openRequest !== null && !reviewed) || actions.submit.isPending}
            title={orderOk ? undefined : t('i04l.orderMissing')}
            onClick={() => void send()}
          >
            {t('i04l.sendSanction')}
          </button>
        </div>
      )}
    </Card>
  );
}
