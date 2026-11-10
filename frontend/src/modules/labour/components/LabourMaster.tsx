'use client';

import { useEffect, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import { Card, Empty, Problem, ServerPagination, Src, date, useToast, TextAreaField } from '@/shared/ui';
import type { CorrectionEntry, LabourRow, MemberEntry, PayrollType } from '../api/labourApi';
import {
  PAYROLL_TYPES,
  correctionBlocked,
  detailEntry,
  emptyEntry,
  entryBlocked,
  orderBlocked,
  reasonOk,
  toAmendRequest,
} from '../business/labourRules';
import {
  fetchMember,
  labourKeys,
  membersPage,
  useCorrection,
  useLifecycle,
  useOptions,
  useSubmitMember,
} from '../orchestration/labourQueries';
import { NominationsCard, ReinstatementCard } from './LabourRecords';
import { TransferForm } from './TransferForm';

/**
 * M-02 Labour Master (plan 20 phase 20a; SRS-2-27, SRS-2-30; D20-1): the labour of the session's
 * offices, kept in Payroll because labour are not in HRMS. Establishment / HR records a new member
 * or an amendment with the reason; the Payroll Checker approves it in the Approvals Inbox and the
 * old values stay in force until then. The labour code and the retirement date are the system's.
 * Establishment / HR also separates a member (Retirement, Resignation, Death or Termination: the Full
 * & Final case opens on approval) and orders Permanent Light Duty (Ancillary designation and the
 * depot's Ancillary Pool, with the pay option; SRS-2-49). The name and the date of birth change only
 * through the correction with the service-book page or order (SRS-2-59; a past new retirement date
 * goes to Headquarters Establishment too), never through an amendment; the Casual Labour indicator
 * is kept on the record (SRS-G-55). The Payroll Maker and the Labour Dealing Hand view only.
 * An amendment opens with the record as it stands (PAN, bank account and mobile masked and left
 * empty to keep them) and sends only the fields the maker changed (SRS-2-30 "keep the old value in
 * force").
 */
export function LabourMaster({
  opening,
  payrollType,
}: {
  /** The member a drawn Edit opened: the amendment form opens on it. */
  opening?: { employeeId: number; labourCode: string } | null;
  /** The engine of the view (the sidebar's entry); all engines when none. */
  payrollType?: string;
} = {}) {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const options = useOptions();
  const submit = useSubmitMember();
  const [type, setType] = useState(payrollType ?? '');
  const [text, setText] = useState('');
  const [gang, setGang] = useState('');
  const [depot, setDepot] = useState<number | null>(null);
  // SRS-2-40 (l.4396): the gang membership as of a date (blank: today); only with a gang.
  const [asOf, setAsOf] = useState('');
  const [editing, setEditing] = useState<{ id: number | null; code: string | null } | null>(null);
  const [entry, setEntry] = useState<MemberEntry>(emptyEntry());
  const [original, setOriginal] = useState<MemberEntry | null>(null);
  const [masked, setMasked] = useState<{ pan: string; bank: string; mobile: string }>({
    pan: '',
    bank: '',
    mobile: '',
  });
  const [loadError, setLoadError] = useState<unknown>(null);
  const [error, setError] = useState<unknown>(null);
  const mayCreate = can(me.data, 'M-02', 'CREATE');
  const mayAmend = can(me.data, 'M-02', 'EDIT');
  const today = businessToday();
  const amending = editing !== null && editing.id !== null;
  const blocked =
    entryBlocked(entry, today, amending) ??
    (amending && original && Object.keys(toAmendRequest(entry, original)).length === 0
      ? 'm02.why.noChange'
      : null);
  const lifecycle = useLifecycle();
  const correction = useCorrection();
  const [corr, setCorr] = useState<{ row: LabourRow; entry: CorrectionEntry } | null>(null);
  const [record, setRecord] = useState<{ kind: 'REINSTATE' | 'NOMINEES'; row: LabourRow } | null>(null);
  const corrWhy = corr ? correctionBlocked(corr.entry, today) : null;

  function startCorrection(row: LabourRow) {
    setError(null);
    setEditing(null);
    setLife(null);
    const [first, ...rest] = row.name.split(' ');
    setCorr({
      row,
      entry: {
        type: 'NAME',
        firstName: first ?? '',
        lastName: rest.join(' '),
        birthDate: '',
        reasonCode: 'AS_PER_SERVICE_BOOK',
        reasonText: '',
        orderReference: '',
        orderDate: today,
        issuingAuthority: '',
        effectiveDate: today,
        document: null,
      },
    });
  }

  function setC<K extends keyof CorrectionEntry>(key: K, value: CorrectionEntry[K]) {
    if (corr) setCorr({ ...corr, entry: { ...corr.entry, [key]: value } });
  }

  async function sendCorrection(corr: { row: LabourRow; entry: CorrectionEntry }) {
    setError(null);
    try {
      await correction.mutateAsync({ employeeId: corr.row.employeeId, entry: corr.entry });
      toast(t('m02.correctionSent'));
      setCorr(null);
    } catch (cause) {
      setError(cause);
    }
  }
  // M-02 l.4429: the transfer starts on M-02 with the order attached.
  const [transferring, setTransferring] = useState<LabourRow | null>(null);
  const [life, setLife] = useState<{
    kind: 'SEPARATE' | 'LIGHT_DUTY';
    row: LabourRow;
    type: string;
    date: string;
    toDesignationId: number | null;
    payOption: string;
    protectedBasic: string;
    orderReference: string;
    orderDate: string;
    issuingAuthority: string;
    reason: string;
  } | null>(null);
  // SRS l.4429 / l.4971: a separation, like a Light Duty order, is sent with its order.
  const lifeWhy = !life
    ? null
    : !life.date ||
        !reasonOk(life.reason) ||
        orderBlocked(
          {
            orderReference: life.orderReference,
            orderDate: life.orderDate,
            issuingAuthority: life.issuingAuthority,
          },
          today,
        ) !== null
      ? 'm02.why.lifecycle'
      : life.kind === 'SEPARATE'
        ? null
        : life.toDesignationId === null ||
            (life.payOption === 'PROTECTED_PAY' && !(Number(life.protectedBasic) > 0))
          ? 'm02.why.lifecycle'
          : null;

  function startLife(kind: 'SEPARATE' | 'LIGHT_DUTY', row: LabourRow) {
    setError(null);
    setEditing(null);
    setCorr(null);
    setLife({
      kind,
      row,
      type: 'RESIGNATION',
      date: today,
      toDesignationId: null,
      payOption: 'ANCILLARY_PAY',
      protectedBasic: '',
      orderReference: '',
      orderDate: today,
      issuingAuthority: '',
      reason: '',
    });
  }

  async function sendLife(life: {
    kind: 'SEPARATE' | 'LIGHT_DUTY';
    row: LabourRow;
    type: string;
    date: string;
    toDesignationId: number | null;
    payOption: string;
    protectedBasic: string;
    orderReference: string;
    orderDate: string;
    issuingAuthority: string;
    reason: string;
  }) {
    setError(null);
    const body: Record<string, unknown> =
      life.kind === 'SEPARATE'
        ? {
            type: life.type,
            date: life.date,
            reason: life.reason.trim(),
            orderReference: life.orderReference.trim(),
            orderDate: life.orderDate,
            issuingAuthority: life.issuingAuthority.trim(),
          }
        : {
            toDesignationId: life.toDesignationId,
            effectiveDate: life.date,
            payOption: life.payOption,
            protectedBasic: life.payOption === 'PROTECTED_PAY' ? Number(life.protectedBasic) : null,
            orderReference: life.orderReference.trim(),
            orderDate: life.orderDate,
            issuingAuthority: life.issuingAuthority.trim(),
            reason: life.reason.trim(),
          };
    try {
      await lifecycle.mutateAsync({ kind: life.kind, employeeId: life.row.employeeId, body });
      toast(t('m02.lifecycleSent'));
      setLife(null);
    } catch (cause) {
      setError(cause);
    }
  }

  /** A new entry, or the amendment form with the record as it stands (SRS-2-30). */
  async function open(row: Pick<LabourRow, 'employeeId' | 'labourCode'> | null) {
    setError(null);
    setLoadError(null);
    let fresh = emptyEntry();
    if (row) {
      try {
        const d = await fetchMember(row.employeeId);
        fresh = detailEntry(d);
        setMasked({ pan: d.panMasked ?? '', bank: d.bankAccountMasked ?? '', mobile: d.mobileMasked ?? '' });
      } catch (cause) {
        setLoadError(cause);
        return;
      }
    }
    setCorr(null);
    setLife(null);
    setEntry(fresh);
    setOriginal(row ? fresh : null);
    setEditing({ id: row?.employeeId ?? null, code: row?.labourCode ?? null });
  }

  function set<K extends keyof MemberEntry>(key: K, value: MemberEntry[K]) {
    setEntry({ ...entry, [key]: value });
  }

  async function send(editing: { id: number | null; code: string | null }) {
    setError(null);
    try {
      await submit.mutateAsync({ id: editing.id, entry, original: original ?? undefined });
      toast(t('m02.sent'));
      setEditing(null);
    } catch (cause) {
      setError(cause);
    }
  }

  useEffect(() => {
    if (opening) void open(opening);
    // Once, when the drawn Edit mounts the register on the member.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const designations = (options.data?.designations ?? []).filter((d) => d.family === entry.payrollType);

  return (
    <div className="wireframe-body-container">
      <Card title={t('screen.M-02')}>
        <div className="toolbar">
          <label>
            {t('m02.payrollType')}{' '}
            <select value={type} onChange={(e) => setType(e.target.value)}>
              <option value="">{t('m02.allTypes')}</option>
              {PAYROLL_TYPES.map((p) => (
                <option key={p} value={p}>
                  {t(`labour.type.${p}`)}
                </option>
              ))}
            </select>
          </label>
          <label>
            {t('m02.search')} <input maxLength={60} value={text} onChange={(e) => setText(e.target.value)} />
          </label>
          <label>
            {t('m02.searchGang')}{' '}
            <input maxLength={150} value={gang} onChange={(e) => setGang(e.target.value)} />
          </label>
          <label>
            {t('m02.searchGangOn')}{' '}
            <input
              type="date"
              value={asOf}
              disabled={gang.trim() === ''}
              title={gang.trim() === '' ? t('m02.why.gangOn') : undefined}
              onChange={(e) => setAsOf(e.target.value)}
            />
          </label>
          <label>
            {t('m02.searchDepot')}{' '}
            <select
              value={depot ?? ''}
              onChange={(e) => setDepot(e.target.value === '' ? null : Number(e.target.value))}
            >
              <option value="">{t('m02.allDepots')}</option>
              {(options.data?.depots ?? []).map((d) => (
                <option key={d.id} value={d.id}>
                  {d.code} - {d.name}
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            className="btn primary"
            disabled={!mayCreate}
            title={mayCreate ? undefined : t('reason.hrOnly')}
            onClick={() => void open(null)}
          >
            {t('action.m02.create')}
          </button>
        </div>
        <ServerPagination
          queryKey={labourKeys.members(type, text, gang.trim(), depot, gang.trim() ? asOf : '')}
          fetchPage={membersPage(type, text, gang, depot, gang.trim() ? asOf : '')}
          empty={<Empty text={t('m02.none')} />}
        >
          {(rows) => (
            <table className="t" aria-label={t('screen.M-02')}>
              <thead>
                <tr>
                  <th>
                    {t('m02.code')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('m02.name')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('m02.designation')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('m02.depot')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('m02.retirement')}
                    <Src s="CALC" />
                  </th>
                  <th>
                    {t('m02.bank')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('m02.status')}
                    <Src s="SYS" />
                  </th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.employeeId}>
                    <td>
                      {r.labourCode}
                      <div className="sub2">{t(`labour.type.${r.payrollType}`)}</div>
                      {r.casualLabour && <div className="sub2">{t('m02.casual')}</div>}
                    </td>
                    <td>
                      {r.name}
                      {r.panMasked && <div className="sub2">{r.panMasked}</div>}
                    </td>
                    <td>{r.designation ?? '—'}</td>
                    <td>{r.depot ?? '—'}</td>
                    <td>{r.retirementDate ? date(r.retirementDate) : t('m02.retirementNotSet')}</td>
                    <td>{r.bankAccountMasked ?? '—'}</td>
                    <td>{t(`labour.status.${r.status}`)}</td>
                    <td>
                      <button
                        type="button"
                        className="btn"
                        disabled={!mayAmend}
                        title={mayAmend ? undefined : t('reason.hrOnly')}
                        onClick={() => void open(r)}
                      >
                        {t('action.m02.amend')}
                      </button>
                      <HistoryButton
                        entity="labour_profile"
                        recordKey={r.labourCode}
                        label={`${r.labourCode} · ${r.name}`}
                      />
                      {r.status === 'ACTIVE' && (
                        <>
                          <button
                            type="button"
                            className="btn"
                            disabled={!mayAmend}
                            title={mayAmend ? undefined : t('reason.hrOnly')}
                            onClick={() => startLife('SEPARATE', r)}
                          >
                            {t('action.m02.separate')}
                          </button>
                          <button
                            type="button"
                            className="btn"
                            disabled={!mayAmend}
                            title={mayAmend ? undefined : t('reason.hrOnly')}
                            onClick={() => {
                              setError(null);
                              setEditing(null);
                              setLife(null);
                              setTransferring(r);
                            }}
                          >
                            {t('action.m02.transfer')}
                          </button>
                          <button
                            type="button"
                            className="btn"
                            disabled={!mayAmend}
                            title={mayAmend ? undefined : t('reason.hrOnly')}
                            onClick={() => startLife('LIGHT_DUTY', r)}
                          >
                            {t('action.m02.lightDuty')}
                          </button>
                          <button
                            type="button"
                            className="btn"
                            disabled={!mayAmend}
                            title={mayAmend ? undefined : t('reason.hrOnly')}
                            onClick={() => startCorrection(r)}
                          >
                            {t('action.m02.correct')}
                          </button>
                        </>
                      )}
                      {r.status === 'SEPARATED' && (
                        <button
                          type="button"
                          className="btn"
                          disabled={!mayAmend}
                          title={mayAmend ? undefined : t('reason.hrOnly')}
                          onClick={() => setRecord({ kind: 'REINSTATE', row: r })}
                        >
                          {t('action.m02.reinstate')}
                        </button>
                      )}
                      <button
                        type="button"
                        className="btn"
                        onClick={() => setRecord({ kind: 'NOMINEES', row: r })}
                      >
                        {t('m02.nominees')}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </ServerPagination>
      </Card>
      {record?.kind === 'REINSTATE' && (
        <ReinstatementCard key={record.row.employeeId} row={record.row} onClose={() => setRecord(null)} />
      )}
      {record?.kind === 'NOMINEES' && (
        <NominationsCard
          key={record.row.employeeId}
          row={record.row}
          mayEdit={mayAmend}
          onClose={() => setRecord(null)}
        />
      )}
      {life && (
        <Card
          title={
            life.kind === 'SEPARATE'
              ? t('m02.separation', [life.row.labourCode])
              : t('m02.lightDutyOf', [life.row.labourCode])
          }
        >
          <div className="form-grid">
            {life.kind === 'SEPARATE' ? (
              <label>
                {t('m02.separationType')}
                <Src s="ENT" />
                <select value={life.type} onChange={(e) => setLife({ ...life, type: e.target.value })}>
                  {['RETIREMENT', 'RESIGNATION', 'DEATH', 'TERMINATION'].map((v) => (
                    <option key={v} value={v}>
                      {t(`m02.sepType.${v}`)}
                    </option>
                  ))}
                </select>
              </label>
            ) : (
              <>
                <label>
                  {t('m02.toDesignation')}
                  <Src s="ENT" />
                  <select
                    value={life.toDesignationId ?? ''}
                    onChange={(e) =>
                      setLife({ ...life, toDesignationId: e.target.value ? Number(e.target.value) : null })
                    }
                  >
                    <option value="">—</option>
                    {(options.data?.designations ?? [])
                      .filter((d) => d.family === life.row.payrollType)
                      .map((d) => (
                        <option key={d.id} value={d.id}>
                          {d.code} - {d.name}
                        </option>
                      ))}
                  </select>
                </label>
                <label>
                  {t('m02.payOption')}
                  <Src s="ENT" />
                  <select
                    value={life.payOption}
                    onChange={(e) => setLife({ ...life, payOption: e.target.value })}
                  >
                    {['ANCILLARY_PAY', 'PROTECTED_PAY'].map((v) => (
                      <option key={v} value={v}>
                        {t(`m02.payOption.${v}`)}
                      </option>
                    ))}
                  </select>
                </label>
                {life.payOption === 'PROTECTED_PAY' && (
                  <label>
                    {t('m02.protectedBasic')}
                    <Src s="ENT" />
                    <input
                      inputMode="decimal"
                      maxLength={13}
                      value={life.protectedBasic}
                      onChange={(e) => setLife({ ...life, protectedBasic: e.target.value })}
                    />
                  </label>
                )}
              </>
            )}
            <label>
              {t('m02.orderReference')}
              <Src s="ENT" />
              <input
                maxLength={100}
                value={life.orderReference}
                onChange={(e) => setLife({ ...life, orderReference: e.target.value })}
              />
            </label>
            <label>
              {t('m02.orderDate')}
              <Src s="ENT" />
              <input
                type="date"
                max={today}
                value={life.orderDate}
                onChange={(e) => setLife({ ...life, orderDate: e.target.value })}
              />
            </label>
            <label>
              {t('m02.issuingAuthority')}
              <Src s="ENT" />
              <input
                maxLength={150}
                value={life.issuingAuthority}
                onChange={(e) => setLife({ ...life, issuingAuthority: e.target.value })}
              />
            </label>
            <label>
              {life.kind === 'SEPARATE' ? t('m02.separationDate') : t('m02.effectiveDate')}
              <Src s="ENT" />
              <input
                type="date"
                value={life.date}
                onChange={(e) => setLife({ ...life, date: e.target.value })}
              />
            </label>
            <label>
              {t('m09.changeReason')}
              <Src s="ENT" />
              <TextAreaField
                maxLength={500}
                value={life.reason}
                onChange={(e) => setLife({ ...life, reason: e.target.value })}
              />
            </label>
          </div>
          <div className="toolbar">
            <button
              type="button"
              className="btn primary"
              disabled={lifeWhy !== null || lifecycle.isPending}
              title={lifeWhy ? t(lifeWhy) : undefined}
              onClick={() => void sendLife(life)}
            >
              {t('action.m02.send')}
            </button>
            <button type="button" className="btn" onClick={() => setLife(null)}>
              {t('common.cancel')}
            </button>
          </div>
          <Problem error={error} />
        </Card>
      )}
      {transferring && (
        <Card title={t('m09.move.TRANSFER', [transferring.labourCode])}>
          <TransferForm
            key={transferring.employeeId}
            member={{
              employeeId: transferring.employeeId,
              labourCode: transferring.labourCode,
              payrollType: transferring.payrollType,
            }}
            onDone={() => setTransferring(null)}
          />
        </Card>
      )}
      {corr && (
        <Card title={t('m02.correctionOf', [corr.row.labourCode])}>
          <div className="form-grid">
            <label>
              {t('m02.correctionType')}
              <Src s="ENT" />
              <select
                value={corr.entry.type}
                onChange={(e) => setC('type', e.target.value as CorrectionEntry['type'])}
              >
                {(['NAME', 'DOB'] as const).map((v) => (
                  <option key={v} value={v}>
                    {v} - {t(`m02.corrType.${v}`)}
                  </option>
                ))}
              </select>
            </label>
            {corr.entry.type === 'NAME' ? (
              <>
                <label>
                  {t('m02.newFirstName')}
                  <Src s="ENT" />
                  <input
                    maxLength={150}
                    value={corr.entry.firstName}
                    onChange={(e) => setC('firstName', e.target.value)}
                  />
                </label>
                <label>
                  {t('m02.newLastName')}
                  <Src s="ENT" />
                  <input
                    maxLength={150}
                    value={corr.entry.lastName}
                    onChange={(e) => setC('lastName', e.target.value)}
                  />
                </label>
              </>
            ) : (
              <label>
                {t('m02.newBirthDate')}
                <Src s="ENT" />
                <input
                  type="date"
                  value={corr.entry.birthDate}
                  onChange={(e) => setC('birthDate', e.target.value)}
                />
              </label>
            )}
            <label>
              {t('m02.correctionReason')}
              <Src s="ENT" />
              <select
                value={corr.entry.reasonCode}
                onChange={(e) => setC('reasonCode', e.target.value as CorrectionEntry['reasonCode'])}
              >
                {(['AS_PER_SERVICE_BOOK', 'AS_PER_ORDER'] as const).map((v) => (
                  <option key={v} value={v}>
                    {v} - {t(`m02.corrReason.${v}`)}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('m02.correctionRemarks')}
              <Src s="ENT" />
              <TextAreaField
                maxLength={1000}
                value={corr.entry.reasonText}
                onChange={(e) => setC('reasonText', e.target.value)}
              />
            </label>
            <label>
              {t('m02.documentReference')}
              <Src s="ENT" />
              <input
                maxLength={60}
                value={corr.entry.orderReference}
                onChange={(e) => setC('orderReference', e.target.value)}
              />
            </label>
            <label>
              {t('m02.documentDate')}
              <Src s="ENT" />
              <input
                type="date"
                value={corr.entry.orderDate}
                onChange={(e) => setC('orderDate', e.target.value)}
              />
            </label>
            <label>
              {t('m02.documentAuthority')}
              <Src s="ENT" />
              <input
                maxLength={150}
                value={corr.entry.issuingAuthority}
                onChange={(e) => setC('issuingAuthority', e.target.value)}
              />
            </label>
            <label>
              {t('m02.correctionEffective')}
              <Src s="ENT" />
              <input
                type="date"
                value={corr.entry.effectiveDate}
                onChange={(e) => setC('effectiveDate', e.target.value)}
              />
            </label>
            <label>
              {t('m02.documentFile')}
              <Src s="ENT" />
              <input
                type="file"
                accept="application/pdf,image/png,image/jpeg"
                onChange={(e) => setC('document', e.target.files?.[0] ?? null)}
              />
            </label>
          </div>
          {corr.entry.type === 'DOB' && <p className="sub2">{t('m02.dobNote')}</p>}
          <div className="toolbar">
            <button
              type="button"
              className="btn primary"
              disabled={corrWhy !== null || correction.isPending}
              title={corrWhy ? t(corrWhy) : undefined}
              onClick={() => void sendCorrection(corr)}
            >
              {t('action.m02.send')}
            </button>
            <button type="button" className="btn" onClick={() => setCorr(null)}>
              {t('common.cancel')}
            </button>
          </div>
          {corrWhy && <p className="sub2">{t(corrWhy)}</p>}
          <Problem error={error} />
        </Card>
      )}
      <Problem error={loadError} />
      {editing && (
        <Card title={editing.code ? t('m02.amendOf', [editing.code]) : t('m02.new')}>
          {amending && <p className="sub2">{t('m02.keptOnAmend')}</p>}
          <div className="form-grid">
            <label>
              {t('m02.payrollType')}
              <Src s="ENT" />
              <select
                value={entry.payrollType}
                disabled={editing.id !== null}
                onChange={(e) =>
                  setEntry({ ...entry, payrollType: e.target.value as PayrollType, designationId: null })
                }
              >
                {PAYROLL_TYPES.map((p) => (
                  <option key={p} value={p}>
                    {t(`labour.type.${p}`)}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('m02.firstName')}
              <Src s="ENT" />
              <input
                maxLength={100}
                value={entry.firstName}
                disabled={amending}
                onChange={(e) => set('firstName', e.target.value)}
              />
            </label>
            <label>
              {t('m02.lastName')}
              <Src s="ENT" />
              <input
                maxLength={100}
                value={entry.lastName}
                disabled={amending}
                onChange={(e) => set('lastName', e.target.value)}
              />
            </label>
            <label>
              {t('m02.fatherName')}
              <Src s="ENT" />
              <input
                maxLength={150}
                value={entry.fatherName}
                onChange={(e) => set('fatherName', e.target.value)}
              />
            </label>
            <label>
              {t('m02.gender')}
              <Src s="ENT" />
              <select
                value={entry.gender}
                onChange={(e) => set('gender', e.target.value as MemberEntry['gender'])}
              >
                <option value="">—</option>
                <option value="M">{t('m02.gender.M')}</option>
                <option value="F">{t('m02.gender.F')}</option>
                <option value="O">{t('m02.gender.O')}</option>
              </select>
            </label>
            <label>
              {t('m02.birthDate')}
              <Src s="ENT" />
              <input
                type="date"
                value={entry.birthDate}
                disabled={amending}
                onChange={(e) => set('birthDate', e.target.value)}
              />
            </label>
            <label>
              {t('m02.joiningDate')}
              <Src s="ENT" />
              <input
                type="date"
                value={entry.joiningDate}
                onChange={(e) => set('joiningDate', e.target.value)}
              />
            </label>
            <label>
              {t('m02.designation')}
              <Src s="ENT" />
              <select
                value={entry.designationId ?? ''}
                disabled={amending}
                onChange={(e) => set('designationId', e.target.value ? Number(e.target.value) : null)}
              >
                <option value="">—</option>
                {designations.map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.code} - {d.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('m02.depot')}
              <Src s="ENT" />
              <select
                value={entry.depotLocationId ?? ''}
                disabled={amending}
                onChange={(e) => set('depotLocationId', e.target.value ? Number(e.target.value) : null)}
              >
                <option value="">—</option>
                {(options.data?.depots ?? []).map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.code} - {d.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('m02.pan')}
              <Src s="ENT" />
              <input
                maxLength={10}
                value={entry.panNumber}
                placeholder={amending ? masked.pan : undefined}
                onChange={(e) => set('panNumber', e.target.value)}
              />
              {amending && masked.pan && <span className="sub2">{t('m02.keepMasked', [masked.pan])}</span>}
            </label>
            <label>
              {t('m02.ifsc')}
              <Src s="ENT" />
              <input maxLength={11} value={entry.ifsc} onChange={(e) => set('ifsc', e.target.value)} />
            </label>
            <label>
              {t('m02.bank')}
              <Src s="ENT" />
              <input
                inputMode="numeric"
                maxLength={20}
                value={entry.bankAccount}
                placeholder={amending ? masked.bank : undefined}
                onChange={(e) => set('bankAccount', e.target.value)}
              />
              {amending && masked.bank && <span className="sub2">{t('m02.keepMasked', [masked.bank])}</span>}
            </label>
            <label>
              {t('m02.mobile')}
              <Src s="ENT" />
              <input
                type="tel"
                inputMode="numeric"
                maxLength={10}
                value={entry.mobile}
                placeholder={amending ? masked.mobile : undefined}
                onChange={(e) => set('mobile', e.target.value)}
              />
              {amending && masked.mobile && (
                <span className="sub2">{t('m02.keepMasked', [masked.mobile])}</span>
              )}
            </label>
            <label>
              <input
                type="checkbox"
                checked={entry.pfApplicable}
                onChange={(e) => set('pfApplicable', e.target.checked)}
              />{' '}
              {t('m02.pfApplicable')}
            </label>
            <label>
              <input
                type="checkbox"
                checked={entry.casualLabour}
                onChange={(e) => set('casualLabour', e.target.checked)}
              />{' '}
              {t('m02.casualLabour')}
            </label>
            {entry.pfApplicable && (
              <label>
                {t('m02.cpfCode')}
                <Src s="ENT" />
                <input
                  maxLength={20}
                  value={entry.cpfCode}
                  onChange={(e) => set('cpfCode', e.target.value)}
                />
              </label>
            )}
            <label>
              {t('m02.uan')}
              <Src s="ENT" />
              <input
                inputMode="numeric"
                maxLength={20}
                value={entry.uanNumber}
                onChange={(e) => set('uanNumber', e.target.value)}
              />
            </label>
            <label>
              {t('m02.reason')}
              <Src s="ENT" />
              <TextAreaField
                maxLength={500}
                value={entry.reason}
                onChange={(e) => set('reason', e.target.value)}
              />
            </label>
          </div>
          <div className="toolbar">
            <button
              type="button"
              className="btn primary"
              disabled={blocked !== null || submit.isPending}
              title={blocked ? t(blocked) : undefined}
              onClick={() => void send(editing)}
            >
              {t('action.m02.send')}
            </button>
            <button type="button" className="btn" onClick={() => setEditing(null)}>
              {t('common.cancel')}
            </button>
          </div>
          {blocked && <p className="sub2">{t(blocked)}</p>}
          <Problem error={error} />
        </Card>
      )}
    </div>
  );
}
