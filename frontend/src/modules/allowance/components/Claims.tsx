'use client';

import { useEffect, useState } from 'react';
import {
  EMPTY_ORDER,
  OrderFields,
  orderComplete,
  type OrderDraft,
  todayIso,
  useDocumentTypes,
} from '@/modules/general';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import {
  amount,
  Card,
  ConfirmDialog,
  date,
  dateTime,
  Empty,
  ExportButton,
  Problem,
  ServerPagination,
  Src,
  useToast,
  TextAreaField,
} from '@/shared/ui';
import type { ClaimDocumentInput, ClaimFilter, ClaimRow } from '../api/claimsApi';
import { claimProblems, type ClaimDraft, EMPTY_CLAIM, isMedical, yymm } from '../business/claimRules';
import {
  claimDocumentUrl,
  claimKeys,
  claimPage,
  claimRegisterUrl,
  medicalRegisterUrl,
  pendingPage,
  useClaimActions,
  useClaimDetail,
  useLabourClaimTypes,
  useMedicalClaims,
} from '../orchestration/claimQueries';

const STATUSES = [
  'SUBMITTED',
  'VALIDATED',
  'CAPPED',
  'APPROVED',
  'PAID',
  'REJECTED',
  'OVERLAP_REJECTED',
  'CANCELLED',
];

/**
 * I-03 Allowances & Reimbursements (plan 10; SRS §6.12, SRS I-03 field table and use case): the Claim
 * Register paged on the server; a labour claim entered by the Labour Dealing Hand with its supporting
 * documents and validated by the Payroll Maker (the payable capped at the entitlement, never rejected
 * for it); employee claims received from HRMS (read-only, waiting on I-21 until approved); the
 * Medical Claim view with the taxable flag and the IOGA; the Claim and Medical Registers.
 */
export function Claims({ focus = null }: { focus?: number | null } = {}) {
  const { t } = useI18n();
  const me = useMe();
  const may = (action: string) => can(me.data, 'I-03', action);
  const [filter, setFilter] = useState<ClaimFilter>({});
  const [open, setOpen] = useState<number | null>(null);
  const [entering, setEntering] = useState(false);
  // The drawn row's Edit opens that claim here, with its validation, taxable flag and IOGA.
  useEffect(() => {
    if (focus !== null) setOpen(focus);
  }, [focus]);
  if (!may('VIEW')) return null;
  const set = (patch: Partial<ClaimFilter>) => setFilter({ ...filter, ...patch });
  return (
    <>
      <Card title={t('i03.register')}>
        <div className="toolbar">
          <label>
            {t('i03.memberType')}{' '}
            <select
              value={filter.memberType ?? ''}
              onChange={(e) => set({ memberType: e.target.value || undefined })}
            >
              <option value="">{t('i03.all')}</option>
              <option value="LABOUR">{t('i03.member.LABOUR')}</option>
              <option value="EMPLOYEE">{t('i03.member.EMPLOYEE')}</option>
            </select>
          </label>
          <label>
            {t('i03.status')}{' '}
            <select
              value={filter.status ?? ''}
              onChange={(e) => set({ status: e.target.value || undefined })}
            >
              <option value="">{t('i03.all')}</option>
              {STATUSES.map((s) => (
                <option key={s} value={s}>
                  {t(`i03.st.${s}`)}
                </option>
              ))}
            </select>
          </label>
          <label>
            {t('i03.eis')}{' '}
            <input
              maxLength={20}
              value={filter.eis ?? ''}
              onChange={(e) => set({ eis: e.target.value.trim() || undefined })}
            />
          </label>
          {may('CREATE') && (
            <button type="button" className="btn pri" onClick={() => setEntering(true)}>
              {t('i03.newClaim')}
            </button>
          )}
          {may('EXPORT') && <ExportButton href={claimRegisterUrl(filter)} what={t('i03.register')} />}
        </div>
        <ServerPagination
          queryKey={claimKeys.register(filter)}
          fetchPage={claimPage(filter)}
          empty={<Empty text={t('i03.none')} />}
        >
          {(rows) => <Register rows={rows} onOpen={setOpen} />}
        </ServerPagination>
      </Card>
      {entering && <EntryForm onDone={() => setEntering(false)} />}
      {open !== null && <Detail key={open} id={open} onClose={() => setOpen(null)} />}
      <PendingFromHrms />
      <MedicalView mayExport={may('EXPORT')} />
    </>
  );
}

function Register({ rows, onOpen }: { rows: ClaimRow[]; onOpen: (id: number) => void }) {
  const { t } = useI18n();
  return (
    <table className="t" aria-label={t('i03.register')}>
      <thead>
        <tr>
          <th>
            {t('i03.claim')}
            <Src s="SYS" />
          </th>
          <th>
            {t('i03.member')}
            <Src s="HRMS" />
          </th>
          <th>
            {t('i03.type')}
            <Src s="ENT" />
          </th>
          <th>
            {t('i03.period')}
            <Src s="ENT" />
          </th>
          <th>
            {t('i03.claimed')}
            <Src s="ENT" />
          </th>
          <th>
            {t('i03.entitlement')}
            <Src s="CALC" />
          </th>
          <th>
            {t('i03.payable')}
            <Src s="CALC" />
          </th>
          <th>
            {t('i03.status')}
            <Src s="SYS" />
          </th>
          <th>
            {t('i03.source')}
            <Src s="SYS" />
          </th>
          {/* SRS l.2804-2806: the register's invoice and HRMS columns. */}
          <th>
            {t('i03.invoiceStatus')}
            <Src s="INTF" />
          </th>
          <th>
            {t('i03.invoiceSentAt')}
            <Src s="INTF" />
          </th>
          <th>
            {t('i03.hrmsRequestId')}
            <Src s="HRMS" />
          </th>
        </tr>
      </thead>
      <tbody>
        {rows.map((c) => (
          <tr key={c.id}>
            <td>
              <button type="button" className="lnk" onClick={() => onOpen(c.id)}>
                {t('i03.claimNo', [c.id])}
              </button>
            </td>
            <td>
              <b>{c.eis}</b> <span className="sub2">{c.name}</span>
            </td>
            {/* SRS l.2795-2798: an employee claim's type, period and amount are HRMS's. */}
            <td>
              {c.claimTypeLabel ?? c.claimType}
              {c.memberType === 'EMPLOYEE' && <Src s="HRMS" />}
            </td>
            <td>
              {date(c.periodFrom)} – {date(c.periodTo)}
              {c.memberType === 'EMPLOYEE' && <Src s="HRMS" />}
            </td>
            <td>
              {amount(c.claimed)}
              {c.memberType === 'EMPLOYEE' && <Src s="HRMS" />}
            </td>
            <td>{c.entitlement === null ? '—' : amount(c.entitlement)}</td>
            <td>
              {c.payable === null ? '—' : amount(c.payable)}
              {c.capped && <span className="pill p-warn"> {t('i03.capped')}</span>}
            </td>
            <td>
              {t(`i03.st.${c.status}`)}
              {c.decisionReason && <div className="sub2">{c.decisionReason}</div>}
            </td>
            <td>{t(`i03.src.${c.source}`)}</td>
            <td>{t(`i03.inv.${c.invoiceStatus}`)}</td>
            <td>{c.invoiceSentAt ? dateTime(c.invoiceSentAt) : '—'}</td>
            <td>{c.hrmsRequestId ?? '—'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function Detail({ id, onClose }: { id: number; onClose: () => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const may = (action: string) => can(me.data, 'I-03', action);
  const detail = useClaimDetail(id);
  const actions = useClaimActions();
  const [reason, setReason] = useState('');
  const [askTaxable, setAskTaxable] = useState(false);
  // SRS-11-19 / I-03 Taxable Flag (l.2756, l.2803): the user chooses; nothing is preselected.
  const [taxable, setTaxable] = useState<boolean | null>(null);
  const [error, setError] = useState<unknown>(null);
  const c = detail.data?.claim;
  async function run(work: () => Promise<unknown>, done: string) {
    setError(null);
    try {
      await work();
      toast(t(done));
    } catch (cause) {
      setError(cause);
    }
  }
  const settled = c !== undefined && (c.status === 'APPROVED' || c.status === 'PAID');
  // The flag is confirmed before the invoice goes to APPS (UI review #94), never after.
  const medical =
    c !== undefined &&
    c.memberType === 'EMPLOYEE' &&
    isMedical(c.claimType) &&
    settled &&
    c.invoiceStatus === 'NOT_RAISED';
  return (
    <section className="card" aria-label={t('i03.claimNo', [id])}>
      <div className="hd">
        <h3>{t('i03.claimNo', [id])}</h3>
        <button type="button" className="btn" onClick={onClose}>
          {t('common.close')}
        </button>
      </div>
      <Problem error={detail.error ?? error} />
      {c && (
        <>
          <p className="sub2">
            {c.eis} · {c.name} · {c.claimTypeLabel ?? c.claimType} · {t(`i03.src.${c.source}`)}
            {c.hrmsRequestId && ` · ${t('i03.hrmsRequest', [c.hrmsRequestId])}`}
          </p>
          <p>
            {t('i03.sum', [
              amount(c.claimed),
              c.entitlement === null ? '—' : amount(c.entitlement),
              c.payable === null ? '—' : amount(c.payable),
            ])}
            {c.capped && <span className="pill p-warn"> {t('i03.cappedHint')}</span>}
          </p>
          <p className="sub2" data-testid="i03-tax-impact">
            {c.taxTreatment === null
              ? t('i03.taxImpactNone')
              : t('i03.taxImpact', [
                  c.taxableAmount === null ? '—' : amount(c.taxableAmount),
                  t(`i03.taxTreatment.${c.taxTreatment}`),
                ])}
          </p>
          <p className="sub2">
            {t('i03.invoice', [t(`i03.inv.${c.invoiceStatus}`)])}
            {c.invoiceSentAt && ` · ${t('i03.invoiceSentOn', [dateTime(c.invoiceSentAt)])}`}
            {c.taxable !== null && ` · ${t(c.taxable ? 'i03.taxableYes' : 'i03.taxableNo')}`}
            {c.taxableConfirmedAt && ` (${dateTime(c.taxableConfirmedAt)})`}
            {c.iogaNumber &&
              ` · ${t('i03.iogaShown', [c.iogaNumber, date(c.iogaDate), amount(c.iogaAmount)])}`}
          </p>
          {(detail.data?.documents ?? []).length > 0 && (
            <ul className="fl-list" aria-label={t('i03.documents')}>
              {(detail.data?.documents ?? []).map((d) => (
                <li key={d.id}>
                  <a href={claimDocumentUrl(id, d.id)}>{d.fileName}</a> · {d.documentTypeName} ·{' '}
                  {d.uploadedBy}
                </li>
              ))}
            </ul>
          )}
          {may('VALIDATE') && c.memberType === 'LABOUR' && c.status === 'SUBMITTED' && (
            <div className="toolbar" aria-label={t('i03.validate')}>
              <label>
                {t('i03.reason')}{' '}
                <TextAreaField maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
              </label>
              <button
                type="button"
                className="btn pri"
                disabled={actions.validate.isPending}
                onClick={() =>
                  void run(() => actions.validate.mutateAsync({ id, reason: reason.trim() }), 'i03.sent')
                }
              >
                {t('i03.validate')}
              </button>
            </div>
          )}
          {may('EDIT') && medical && (
            <>
              <button
                type="button"
                className="btn"
                onClick={() => {
                  setTaxable(null);
                  setAskTaxable(true);
                }}
              >
                {t('i03.confirmTaxable')}
              </button>
              <IogaForm
                onSend={(body) => run(() => actions.ioga.mutateAsync({ id, body }), 'i03.sent')}
                busy={actions.ioga.isPending}
              />
            </>
          )}
          {askTaxable && (
            <ConfirmDialog
              title={t('i03.taxableTitle', [id])}
              confirmLabel={t('i03.taxableSave')}
              busy={taxable === null}
              onCancel={() => setAskTaxable(false)}
              onConfirm={() => {
                if (taxable === null) return;
                setAskTaxable(false);
                void run(() => actions.confirmTaxable.mutateAsync({ id, taxable }), 'i03.taxableSaved');
              }}
            >
              <fieldset>
                <legend>{t('i03.taxableQuestion')}</legend>
                <label>
                  <input
                    type="radio"
                    name="i03-taxable"
                    checked={taxable === true}
                    onChange={() => setTaxable(true)}
                  />{' '}
                  {t('i03.taxableYes')}
                </label>{' '}
                <label>
                  <input
                    type="radio"
                    name="i03-taxable"
                    checked={taxable === false}
                    onChange={() => setTaxable(false)}
                  />{' '}
                  {t('i03.taxableNo')}
                </label>
              </fieldset>
            </ConfirmDialog>
          )}
        </>
      )}
    </section>
  );
}

function IogaForm({
  onSend,
  busy,
}: {
  onSend: (body: {
    number: string;
    date: string;
    amount: string;
    originatingOffice: string;
    reason: string;
  }) => Promise<void>;
  busy: boolean;
}) {
  const { t } = useI18n();
  const [v, setV] = useState({ number: '', date: '', amount: '', originatingOffice: '', reason: '' });
  const ok =
    v.number.trim() !== '' &&
    /^\d{4}-\d{2}-\d{2}$/.test(v.date) &&
    Number(v.amount) > 0 &&
    v.originatingOffice.trim() !== '';
  return (
    <fieldset className="toolbar" aria-label={t('i03.ioga')}>
      <legend>{t('i03.ioga')}</legend>
      <label>
        {t('i03.iogaNumber')}{' '}
        <input maxLength={50} value={v.number} onChange={(e) => setV({ ...v, number: e.target.value })} />
      </label>
      <label>
        {t('i03.iogaDate')}{' '}
        <input type="date" value={v.date} onChange={(e) => setV({ ...v, date: e.target.value })} />
      </label>
      <label>
        {t('i03.iogaAmount')}{' '}
        <input
          maxLength={13}
          inputMode="decimal"
          value={v.amount}
          onChange={(e) => setV({ ...v, amount: e.target.value })}
        />
      </label>
      <label>
        {t('i03.iogaOffice')}{' '}
        <input
          maxLength={100}
          value={v.originatingOffice}
          onChange={(e) => setV({ ...v, originatingOffice: e.target.value })}
        />
      </label>
      <button
        type="button"
        className="btn"
        disabled={!ok || busy}
        title={ok ? undefined : t('i03.iogaIncomplete')}
        onClick={() =>
          void onSend({ ...v, number: v.number.trim(), originatingOffice: v.originatingOffice.trim() })
        }
      >
        {t('i03.iogaSend')}
      </button>
    </fieldset>
  );
}

function EntryForm({ onDone }: { onDone: () => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const types = useLabourClaimTypes(true);
  const docTypes = useDocumentTypes(true);
  const actions = useClaimActions();
  const [d, setD] = useState<ClaimDraft>(EMPTY_CLAIM);
  const [order, setOrder] = useState<OrderDraft>(EMPTY_ORDER);
  const [docs, setDocs] = useState<ClaimDocumentInput[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [result, setResult] = useState<ClaimRow | null>(null);
  const today = todayIso();
  const problems = claimProblems(d);
  const orderReady = orderComplete(order, docTypes.data ?? [], today) && order.file !== null;
  const set = (patch: Partial<ClaimDraft>) => setD({ ...d, ...patch });
  function addDocument() {
    if (!order.file) return;
    setDocs([...docs, { ...order, file: order.file }]);
    setOrder(EMPTY_ORDER);
  }
  async function send() {
    setError(null);
    try {
      const saved = await actions.enter.mutateAsync({
        body: { ...d, memberNumber: d.memberNumber.trim() },
        documents: docs,
      });
      setResult(saved);
      // SRS-11-6 (UI review #96): an entered claim clears the form, so a second click does not
      // enter it again; an overlap keeps it, so the period can be corrected.
      if (saved.status !== 'OVERLAP_REJECTED') {
        setD(EMPTY_CLAIM);
        setDocs([]);
        setOrder(EMPTY_ORDER);
      }
      toast(t(saved.status === 'OVERLAP_REJECTED' ? 'i03.overlapRecorded' : 'i03.entered'));
    } catch (cause) {
      setError(cause);
    }
  }
  return (
    <Card title={t('i03.newClaim')}>
      <Problem error={error ?? types.error} />
      {result && (
        <p role="status" className={result.status === 'OVERLAP_REJECTED' ? 'fl-banner' : 'sub2'}>
          {t('i03.result', [
            result.id,
            t(`i03.st.${result.status}`),
            result.payable === null ? '—' : amount(result.payable),
          ])}
          {result.capped && ` ${t('i03.cappedHint')}`}
        </p>
      )}
      <div className="rf-grid">
        <div className="rf-field">
          <label htmlFor="i03-member">{t('i03.labourNumber')}</label>
          <input
            id="i03-member"
            maxLength={20}
            value={d.memberNumber}
            onChange={(e) => set({ memberNumber: e.target.value })}
          />
        </div>
        <div className="rf-field">
          <label htmlFor="i03-type">{t('i03.type')}</label>
          <select id="i03-type" value={d.claimType} onChange={(e) => set({ claimType: e.target.value })}>
            <option value="">—</option>
            {(types.data ?? []).map((ct) => (
              <option key={ct.code} value={ct.code}>
                {ct.label}
              </option>
            ))}
          </select>
        </div>
        <div className="rf-field">
          <label htmlFor="i03-from">{t('i03.from')}</label>
          <input
            id="i03-from"
            type="date"
            value={d.periodFrom}
            onChange={(e) => set({ periodFrom: e.target.value })}
          />
        </div>
        <div className="rf-field">
          <label htmlFor="i03-to">{t('i03.to')}</label>
          <input
            id="i03-to"
            type="date"
            value={d.periodTo}
            onChange={(e) => set({ periodTo: e.target.value })}
          />
        </div>
        <div className="rf-field">
          <label htmlFor="i03-amount">{t('i03.claimed')}</label>
          <input
            maxLength={13}
            id="i03-amount"
            inputMode="decimal"
            value={d.claimedAmount}
            onChange={(e) => set({ claimedAmount: e.target.value })}
          />
        </div>
      </div>
      <h4>{t('i03.documents')}</h4>
      {docs.length > 0 && (
        <ul className="fl-list" aria-label={t('i03.documentsAdded')}>
          {docs.map((doc, i) => (
            <li key={`${doc.file.name}-${i}`}>
              {doc.file.name} · {doc.documentType}{' '}
              <button type="button" className="lnk" onClick={() => setDocs(docs.filter((_, j) => j !== i))}>
                {t('i03.remove')}
              </button>
            </li>
          ))}
        </ul>
      )}
      <OrderFields
        idPrefix="i03d"
        value={order}
        types={docTypes.data ?? []}
        today={today}
        onChange={setOrder}
      />
      <button type="button" className="btn" disabled={!orderReady} onClick={addDocument}>
        {t('i03.addDocument')}
      </button>
      {problems.length > 0 && (
        <ul className="fl-list" aria-label={t('i03.problems')}>
          {problems.map((p) => (
            <li key={p} className="pill p-warn">
              {t(p)}
            </li>
          ))}
        </ul>
      )}
      <div className="rf-actbar">
        <button type="button" className="btn" onClick={onDone}>
          {t('common.close')}
        </button>
        <button
          type="button"
          className="btn pri"
          disabled={problems.length > 0 || actions.enter.isPending}
          title={problems.length > 0 ? t(problems[0] ?? '') : undefined}
          onClick={() => void send()}
        >
          {t('i03.enter')}
        </button>
      </div>
    </Card>
  );
}

function PendingFromHrms() {
  const { t } = useI18n();
  return (
    <Card title={t('i03.pendingHrms')}>
      <p className="sub2">{t('i03.pendingHint')}</p>
      <ServerPagination
        queryKey={claimKeys.pending()}
        fetchPage={pendingPage()}
        empty={<Empty text={t('i03.noPending')} />}
      >
        {(rows) => (
          <table className="t" aria-label={t('i03.pendingHrms')}>
            <thead>
              <tr>
                <th>
                  {t('i03.task')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('i03.member')}
                  <Src s="HRMS" />
                </th>
                <th>
                  {t('i03.hrmsValue')}
                  <Src s="HRMS" />
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((p) => (
                <tr key={p.taskId}>
                  <td>
                    {p.taskNo}
                    <div className="sub2">{p.status}</div>
                  </td>
                  <td>
                    <b>{p.eis}</b> <span className="sub2">{p.name}</span>
                  </td>
                  <td className="fl-wrap-any">{p.value}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </ServerPagination>
    </Card>
  );
}

function MedicalView({ mayExport }: { mayExport: boolean }) {
  const { t } = useI18n();
  const [eis, setEis] = useState('');
  const [shown, setShown] = useState('');
  const [month, setMonth] = useState('');
  const medical = useMedicalClaims(shown);
  const monthCode = yymm(month);
  return (
    <Card title={t('i03.medical')}>
      <div className="toolbar">
        <label>
          {t('i03.eis')} <input maxLength={20} value={eis} onChange={(e) => setEis(e.target.value)} />
        </label>
        <button
          type="button"
          className="btn"
          disabled={!/^[A-Za-z0-9]{1,20}$/.test(eis.trim())}
          onClick={() => setShown(eis.trim())}
        >
          {t('i03.show')}
        </button>
        {mayExport && (
          <>
            <label>
              {t('i03.month')} <input type="month" value={month} onChange={(e) => setMonth(e.target.value)} />
            </label>
            {monthCode ? (
              <ExportButton
                href={medicalRegisterUrl(monthCode)}
                what={t('i03.medicalRegister')}
                label={t('i03.medicalRegister')}
              />
            ) : (
              <button type="button" className="btn" disabled title={t('i03.pickMonth')}>
                {t('i03.medicalRegister')}
              </button>
            )}
          </>
        )}
      </div>
      <Problem error={medical.error} />
      {medical.data && medical.data.length === 0 && <Empty text={t('i03.noMedical')} />}
      {medical.data && medical.data.length > 0 && (
        <table className="t" aria-label={t('i03.medical')}>
          <thead>
            <tr>
              <th>
                {t('i03.month')}
                <Src s="HRMS" />
              </th>
              <th>
                {t('i03.claim')}
                <Src s="SYS" />
              </th>
              <th>
                {t('i03.payable')}
                <Src s="CALC" />
              </th>
              <th>
                {t('i03.status')}
                <Src s="SYS" />
              </th>
              <th>
                {t('i03.ioga')}
                <Src s="ENT" />
              </th>
            </tr>
          </thead>
          <tbody>
            {medical.data.map((c) => (
              <tr key={c.id}>
                <td>{c.periodFrom.slice(0, 7)}</td>
                <td>{t('i03.claimNo', [c.id])}</td>
                <td>{c.payable === null ? '—' : amount(c.payable)}</td>
                <td>{t(`i03.st.${c.status}`)}</td>
                <td>{c.iogaNumber ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Card>
  );
}
