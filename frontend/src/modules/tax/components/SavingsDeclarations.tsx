'use client';

import { useState } from 'react';
import { useDocumentTypes } from '@/modules/general';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import {
  Card,
  dateTime,
  Empty,
  Problem,
  ServerPagination,
  Src,
  useConfirmCancel,
  useToast,
  TextAreaField,
  FinancialYearSelect,
} from '@/shared/ui';
import type { EntryStatus, EntryType, MemberSavings, NewSavingsEntry, SavingsEntry } from '../api/taxApi';
import { entryProblems, INCOME_HEADS, monthsOf, rebateProblems } from '../business/savingsRules';
import {
  entryDocumentUrl,
  savingsPages,
  taxKeys,
  useMemberSavings,
  useSavingsActions,
} from '../orchestration/taxQueries';
import { finYearOf } from './DeclarationReview';

const STATUSES: readonly EntryStatus[] = [
  'MAKER_SAVED',
  'PENDING_CHECKER',
  'APPROVED',
  'REJECTED',
  'CANCELLED',
];
const TYPES: readonly EntryType[] = ['EXTERNAL_SAVINGS', 'OTHER_INCOME'];

function rupees(n: number | null): string {
  return n === null
    ? '—'
    : `₹${n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function blankEntry(file: File): NewSavingsEntry {
  return {
    type: 'OTHER_INCOME',
    sectionCode: '',
    incomeHead: '',
    description: '',
    amount: '',
    tdsOtherSource: '',
    document: { documentType: 'CERTIFICATE', reference: '', documentDate: '', issuingAuthority: '' },
    file,
  };
}

/**
 * I-18 Savings & Tax Rebate Declarations (SRS-7-11, SRS-7-20, SRS-7-25, SRS I-18 field table;
 * BR-TAX-007, BR-TAX-015). The Payroll Maker records a member's external savings and other income
 * with a supporting document and sends it to the Payroll Checker, who decides in the Approvals Inbox;
 * only approved entries count for TDS. The year's transport rebate months go the same way. Internal
 * savings are shown as configured; the member's own declarations are on E-01 / I-07.
 */
export function SavingsDeclarations() {
  const { t } = useI18n();
  const [finYear, setFinYear] = useState(finYearOf(businessToday()));
  const [status, setStatus] = useState('');
  const [lookup, setLookup] = useState('');
  const [eis, setEis] = useState<string | null>(null);
  const yearOk = /^\d{4}-\d{2}$/.test(finYear);
  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <label>
          {t('i07.finYear')} <FinancialYearSelect value={finYear} onChange={setFinYear} />
        </label>
        <label>
          {t('i07.status')}{' '}
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">{t('i07.allStatuses')}</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {t(`i18.status.${s}`)}
              </option>
            ))}
          </select>
        </label>
        <label>
          {t('m01.eis')}{' '}
          <input maxLength={20} value={lookup} onChange={(e) => setLookup(e.target.value.trim())} />
        </label>
        <button
          type="button"
          className="btn"
          disabled={!/^[0-9A-Za-z]{1,20}$/.test(lookup)}
          onClick={() => setEis(lookup)}
        >
          {t('i18.openMember')}
        </button>
        <span className="sub2">{t('i18.hint')}</span>
      </div>
      <Card title={t('screen.I-18')}>
        {yearOk ? (
          <ServerPagination
            queryKey={taxKeys.savings(finYear, status)}
            fetchPage={savingsPages.list(finYear, status)}
            empty={<Empty text={t('i18.none')} />}
          >
            {(rows) => (
              <table className="t" aria-label={t('screen.I-18')}>
                <thead>
                  <tr>
                    <th>
                      {t('m01.eis')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('i07.member')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('i18.type')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('i18.what')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('i18.amount')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('i07.status')}
                      <Src s="SYS" />
                    </th>
                    <th aria-label={t('common.actions')} />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((e) => (
                    <tr key={e.id} data-reference={`${e.eis}|${e.id}`}>
                      <td>{e.eis}</td>
                      <td>{e.name}</td>
                      <td>{t(`i18.type.${e.type}`)}</td>
                      <td>{e.sectionCode ?? e.incomeHead}</td>
                      <td>{rupees(e.amount)}</td>
                      <td>{t(`i18.status.${e.status}`)}</td>
                      <td>
                        <button
                          type="button"
                          className="ia"
                          aria-label={t('i18.openOf', [e.eis ?? ''])}
                          onClick={() => setEis(e.eis)}
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
        ) : (
          <p className="fl-hint">{t('i07.badYear')}</p>
        )}
      </Card>
      {eis !== null && yearOk && (
        <MemberYear key={`${eis}|${finYear}`} eis={eis} finYear={finYear} onClose={() => setEis(null)} />
      )}
    </div>
  );
}

/** One member's year: the entries with their actions, the savings rows, the new entry and rebate forms. */
function MemberYear({ eis, finYear, onClose }: { eis: string; finYear: string; onClose: () => void }) {
  const { t } = useI18n();
  const me = useMe();
  const year = useMemberSavings(eis, finYear);
  const mayCreate = can(me.data, 'I-18', 'CREATE');
  const maySubmit = can(me.data, 'I-18', 'SUBMIT');
  const mayCancel = can(me.data, 'I-18', 'EDIT');
  if (year.isPending) return <p className="fl-hint">{t('common.loading')}</p>;
  if (!year.data) return <Problem error={year.error} />;
  const m = year.data;
  return (
    <Card title={t('i18.memberOf', [m.eis, m.name, m.finYear])}>
      <Entries year={m} maySubmit={maySubmit} mayCancel={mayCancel} />
      <Savings year={m} />
      {mayCreate && <NewEntry year={m} />}
      {maySubmit && <Rebate year={m} />}
      <div className="rf-actbar">
        <button type="button" className="btn" onClick={onClose}>
          {t('common.close')}
        </button>
      </div>
    </Card>
  );
}

function Entries({
  year,
  maySubmit,
  mayCancel,
}: {
  year: MemberSavings;
  maySubmit: boolean;
  mayCancel: boolean;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const actions = useSavingsActions();
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>(null);

  const confirmCancel = useConfirmCancel();
  async function run(action: () => Promise<SavingsEntry>, done: string) {
    setError(null);
    try {
      await action();
      toast(t(done));
    } catch (cause) {
      setError(cause);
    }
  }

  if (year.entries.length === 0) return <Empty text={t('i18.noEntries')} />;
  return (
    <>
      <Problem error={error} />
      {confirmCancel.dialog}
      <table className="t" aria-label={t('i18.entries')}>
        <thead>
          <tr>
            <th>
              {t('i18.type')}
              <Src s="ENT" />
            </th>
            <th>
              {t('i18.what')}
              <Src s="ENT" />
            </th>
            <th>
              {t('i18.amount')}
              <Src s="ENT" />
            </th>
            <th>
              {t('i18.tds')}
              <Src s="ENT" />
            </th>
            <th>
              {t('i07.status')}
              <Src s="SYS" />
            </th>
            <th>
              {t('i18.document')}
              <Src s="ENT" />
            </th>
            <th aria-label={t('common.actions')} />
          </tr>
        </thead>
        <tbody>
          {year.entries.map((e) => (
            <tr key={e.id} data-reference={`entry-${e.id}`}>
              <td>{t(`i18.type.${e.type}`)}</td>
              <td>
                {e.sectionCode ?? e.incomeHead}
                {e.description && <div className="sub2">{e.description}</div>}
              </td>
              <td>{rupees(e.amount)}</td>
              <td>{rupees(e.tdsOtherSource)}</td>
              <td>
                {t(`i18.status.${e.status}`)}
                <div className="sub2">
                  {e.enteredBy} · {dateTime(e.enteredAt)}
                </div>
              </td>
              <td>
                <a href={entryDocumentUrl(e.id, e.documentId)}>{t('i18.document')}</a>
              </td>
              <td>
                {e.status === 'MAKER_SAVED' && (
                  <div className="acts">
                    {maySubmit && (
                      <button
                        type="button"
                        className="ia"
                        aria-label={t('i18.submitOf', [e.id])}
                        // DoD (UI review #65): no second submission while the first is on its way.
                        disabled={actions.submit.isPending}
                        onClick={() => void run(() => actions.submit.mutateAsync(e.id), 'i18.submitted')}
                      >
                        {t('common.submitForApproval')}
                      </button>
                    )}
                    {mayCancel && (
                      <button
                        type="button"
                        className="ia"
                        aria-label={t('i18.cancelOf', [e.id])}
                        disabled={reason.trim() === ''}
                        title={reason.trim() === '' ? t('i07.reasonFirst') : undefined}
                        onClick={() =>
                          confirmCancel.ask(
                            String(e.id),
                            () =>
                              void run(
                                () => actions.cancel.mutateAsync({ id: e.id, reason: reason.trim() }),
                                'i18.cancelled',
                              ),
                          )
                        }
                      >
                        {t('action.cancelEntry')}
                      </button>
                    )}
                  </div>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {mayCancel && year.entries.some((e) => e.status === 'MAKER_SAVED') && (
        <div className="rf-field span2">
          <label htmlFor="i18-cancel-reason">{t('i18.cancelReason')}</label>
          <TextAreaField
            id="i18-cancel-reason"
            maxLength={1000}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </div>
      )}
    </>
  );
}

/** The savings and rebate rows of the year, and the rebate months waiting for the Checker. */
function Savings({ year }: { year: MemberSavings }) {
  const { t } = useI18n();
  return (
    <>
      <table className="t" aria-label={t('i18.savings')}>
        <thead>
          <tr>
            <th>
              {t('i18.savingsType')}
              <Src s="SYS" />
            </th>
            <th>
              {t('m18s.section')}
              <Src s="SYS" />
            </th>
            <th>
              {t('i18.annual')}
              <Src s="SYS" />
            </th>
            <th>
              {t('i18.month')}
              <Src s="SYS" />
            </th>
          </tr>
        </thead>
        <tbody>
          {year.savings.map((s) => (
            <tr key={s.id}>
              <td>{t(`i18.savingsType.${s.type}`)}</td>
              <td>{s.sectionCode ?? '—'}</td>
              <td>{rupees(s.annualAmount)}</td>
              <td>{s.payrollYymm ? `${s.payrollYymm} · ${rupees(s.monthlyAmount)}` : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {year.savings.length === 0 && <p className="sub2">{t('i18.noSavings')}</p>}
      {year.pendingRebates.length > 0 && (
        <ul className="bd fl-list" aria-label={t('i18.pendingRebates')}>
          {year.pendingRebates.map((p) => (
            <li key={p.proposedAt}>
              <span className="pill p-warn">{t('status.PENDING_APPROVAL')}</span>{' '}
              {t('i18.pendingRebateLine', [Object.keys(p.months).join(', '), p.proposedBy])}
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

/** A new entry with its supporting document, saved for the Maker to submit. */
function NewEntry({ year }: { year: MemberSavings }) {
  const { t } = useI18n();
  const toast = useToast();
  const actions = useSavingsActions();
  const types = useDocumentTypes(true);
  const [draft, setDraft] = useState<NewSavingsEntry | null>(null);
  const [error, setError] = useState<unknown>(null);
  const today = businessToday();
  const problems = draft ? entryProblems(draft, year.sections, today) : [];
  const doc = (change: Partial<NewSavingsEntry['document']>) =>
    setDraft((d) => (d ? { ...d, document: { ...d.document, ...change } } : d));

  async function save(entry: NewSavingsEntry) {
    setError(null);
    try {
      await actions.save.mutateAsync({ eis: year.eis, body: entry });
      toast(t('i18.saved'));
      setDraft(null);
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="bd">
      <h3>{t('action.newEntry')}</h3>
      {draft === null ? (
        <label>
          {t('i18.documentFirst')}{' '}
          <input
            type="file"
            aria-label={t('i18.document')}
            accept="application/pdf,image/jpeg,image/png"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) setDraft(blankEntry(file));
            }}
          />
        </label>
      ) : (
        <>
          <Problem error={error} />
          <p className="sub2">{draft.file.name}</p>
          <div className="rf-grid">
            <div className="rf-field">
              <label htmlFor="i18-type">{t('i18.type')}</label>
              <select
                id="i18-type"
                value={draft.type}
                onChange={(e) => setDraft({ ...draft, type: e.target.value as EntryType })}
              >
                {TYPES.map((x) => (
                  <option key={x} value={x}>
                    {t(`i18.type.${x}`)}
                  </option>
                ))}
              </select>
            </div>
            {draft.type === 'EXTERNAL_SAVINGS' ? (
              <div className="rf-field">
                <label htmlFor="i18-section">{t('m18s.section')}</label>
                <select
                  id="i18-section"
                  value={draft.sectionCode}
                  onChange={(e) => setDraft({ ...draft, sectionCode: e.target.value })}
                >
                  <option value="">—</option>
                  {year.sections.map((s) => (
                    <option key={s.code} value={s.code}>
                      {s.code} · {s.description}
                    </option>
                  ))}
                </select>
              </div>
            ) : (
              <div className="rf-field">
                <label htmlFor="i18-head">{t('i18.incomeHead')}</label>
                <input
                  id="i18-head"
                  list="i18-heads"
                  maxLength={60}
                  value={draft.incomeHead}
                  onChange={(e) => setDraft({ ...draft, incomeHead: e.target.value })}
                />
                <datalist id="i18-heads">
                  {INCOME_HEADS.map((h) => (
                    <option key={h} value={h} />
                  ))}
                </datalist>
              </div>
            )}
            <div className="rf-field">
              <label htmlFor="i18-amount">{t('i18.amount')}</label>
              <input
                id="i18-amount"
                inputMode="decimal"
                maxLength={15}
                value={draft.amount}
                onChange={(e) => setDraft({ ...draft, amount: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="i18-tds">{t('i18.tds')}</label>
              <input
                id="i18-tds"
                inputMode="decimal"
                maxLength={15}
                value={draft.tdsOtherSource}
                onChange={(e) => setDraft({ ...draft, tdsOtherSource: e.target.value })}
              />
            </div>
            <div className="rf-field span2">
              <label htmlFor="i18-description">{t('m18s.description')}</label>
              <TextAreaField
                id="i18-description"
                maxLength={500}
                value={draft.description}
                onChange={(e) => setDraft({ ...draft, description: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="i18-doc-type">{t('i18.documentType')}</label>
              <select
                id="i18-doc-type"
                value={draft.document.documentType}
                onChange={(e) => doc({ documentType: e.target.value })}
              >
                {(types.data ?? []).map((x) => (
                  <option key={x.code} value={x.code}>
                    {x.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="rf-field">
              <label htmlFor="i18-doc-date">{t('i18.documentDate')}</label>
              <input
                id="i18-doc-date"
                type="date"
                max={today}
                value={draft.document.documentDate}
                onChange={(e) => doc({ documentDate: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="i18-doc-issuer">{t('i18.documentIssuer')}</label>
              <input
                id="i18-doc-issuer"
                maxLength={200}
                value={draft.document.issuingAuthority}
                onChange={(e) => doc({ issuingAuthority: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="i18-doc-ref">{t('i18.documentReference')}</label>
              <input
                id="i18-doc-ref"
                maxLength={60}
                value={draft.document.reference}
                onChange={(e) => doc({ reference: e.target.value })}
              />
            </div>
          </div>
          {problems.length > 0 && (
            <ul className="bd fl-list" aria-label={t('m18s.problems')}>
              {problems.map((p) => (
                <li key={p}>{t(p)}</li>
              ))}
            </ul>
          )}
          <div className="rf-actbar">
            <button type="button" className="btn" onClick={() => setDraft(null)}>
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className="btn pri"
              disabled={problems.length > 0 || actions.save.isPending}
              onClick={() => void save(draft)}
            >
              {t('common.save')}
            </button>
          </div>
        </>
      )}
    </div>
  );
}

/** The transport rebate months of the year, to the Checker. */
function Rebate({ year }: { year: MemberSavings }) {
  const { t } = useI18n();
  const toast = useToast();
  const actions = useSavingsActions();
  const [months, setMonths] = useState<Record<string, string>>({});
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>(null);
  const approved = new Set(
    year.savings.filter((s) => s.type === 'TRANSPORT_REBATE').map((s) => s.payrollYymm ?? ''),
  );
  const pending = year.pendingRebates.length > 0;
  const problems = rebateProblems(months, approved, reason);

  async function send() {
    setError(null);
    const given = Object.fromEntries(Object.entries(months).filter(([, v]) => v.trim() !== ''));
    try {
      await actions.rebate.mutateAsync({ eis: year.eis, months: given, reason: reason.trim() });
      toast(t('i18.rebateSent'));
      setMonths({});
      setReason('');
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="bd">
      <h3>{t('i18.rebateTitle', [year.finYear])}</h3>
      <Problem error={error} />
      <div className="rf-grid">
        {monthsOf(year.finYear).map((m) => (
          <div className="rf-field" key={m}>
            <label htmlFor={`i18-rebate-${m}`}>{m}</label>
            <input
              id={`i18-rebate-${m}`}
              inputMode="decimal"
              maxLength={13}
              disabled={approved.has(m) || pending}
              value={months[m] ?? ''}
              onChange={(e) => setMonths({ ...months, [m]: e.target.value })}
            />
          </div>
        ))}
        <div className="rf-field span2">
          <label htmlFor="i18-rebate-reason">{t('common.reason')}</label>
          <TextAreaField
            id="i18-rebate-reason"
            maxLength={1000}
            disabled={pending}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </div>
      </div>
      {!pending && problems.length > 0 && (
        <ul className="bd fl-list" aria-label={t('i18.rebateProblems')}>
          {problems.map((p) => (
            <li key={p}>{t(p)}</li>
          ))}
        </ul>
      )}
      <div className="rf-actbar">
        <button
          type="button"
          className="btn pri"
          disabled={pending || problems.length > 0 || actions.rebate.isPending}
          title={pending ? t('i18.rebatePending') : undefined}
          onClick={() => void send()}
        >
          {t('i18.sendRebate')}
        </button>
      </div>
    </div>
  );
}
