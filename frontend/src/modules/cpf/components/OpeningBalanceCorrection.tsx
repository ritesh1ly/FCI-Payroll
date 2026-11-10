'use client';

import { useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import {
  EMPTY_ORDER,
  OrderFields,
  orderComplete,
  todayIso,
  useDocumentTypes,
  type OrderDraft,
} from '@/modules/general';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions, useReference } from '@/shared/reference';
import { can, useMe } from '@/shared/session';
import {
  amount,
  Card,
  date,
  dateTime,
  Empty,
  Problem,
  ServerPagination,
  Src,
  useToast,
  TextAreaField,
} from '@/shared/ui';
import type { Correction, MemberLookup, NewCorrection, OpeningBalance } from '../api/cpfApi';
import {
  CORRECTION_STATUS_PILL,
  FIELD,
  PARTS,
  changes,
  correctionProblems,
  paise,
  signed,
} from '../business/openingBalanceRules';
import {
  evidenceUrl,
  cpfKeys,
  cpfPages,
  useOpeningBalance,
  useRaiseCorrection,
} from '../orchestration/cpfQueries';

/** SRS I-22 "Member (CPF Code / UAN)" (l.2234): the form of each lookup value. */
const LOOKUP: Record<MemberLookup, RegExp> = {
  eis: /^[A-Za-z0-9]{1,20}$/,
  cpfCode: /^[A-Za-z0-9/-]{1,20}$/,
  uan: /^\d{12}$/,
};

function emptyCorrection(current: OpeningBalance, asAt: string): NewCorrection {
  return {
    eis: current.eis,
    asAtDate: asAt,
    employee: null,
    employer: null,
    vpf: null,
    reasonCode: '',
    // SRS l.2240: the Effective Date for Interest is the Maker's, mandatory, never defaulted.
    interestEffectiveDate: '',
    remarks: '',
  };
}

/** Why "New correction" is disabled for the member shown, or null when it may be raised. */
function blockOf(current: OpeningBalance): string | null {
  if (current.settled) return 'error.CPF-409-SETTLED';
  if (current.pending) return 'error.CPF-409-CORRECTION-PENDING';
  return null;
}

/**
 * I-22 PF / CPF Opening Balance Correction (SRS-6-43, SRS-6-44, BR-CPF-025 … 027, SRS I-22 field
 * table): the only way to change an established opening balance. Manager (CPF) at a Zonal Office or
 * Headquarters selects the member and the as-at date, enters the corrected amount of at least one
 * part with a permitted reason, the effective date for interest, remarks and the evidence; AGM (CPF)
 * and then the Headquarters CPF approver decide in the Approvals Inbox, each with OTP. Nothing is
 * overwritten: approval writes one adjustment row per corrected part. Below, the Opening Balance
 * Correction Register of the user's scope.
 */
export function OpeningBalanceCorrection() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const reference = useReference();
  const mayCreate = can(me.data, 'I-22', 'CREATE');
  const [lookup, setLookup] = useState<{ by: MemberLookup; eis: string; asAt: string }>({
    by: 'eis',
    eis: '',
    asAt: '',
  });
  const [query, setQuery] = useState<{ by: MemberLookup; eis: string; asAt: string }>({
    by: 'eis',
    eis: '',
    asAt: '',
  });
  const current = useOpeningBalance(query.eis, query.asAt, query.by);
  const raise = useRaiseCorrection();
  const types = useDocumentTypes(mayCreate);
  const [draft, setDraft] = useState<NewCorrection | null>(null);
  const [order, setOrder] = useState<OrderDraft>(EMPTY_ORDER);
  const [error, setError] = useState<unknown>(null);
  const today = todayIso();
  const member = current.data;
  const block = member ? blockOf(member) : null;
  const hasEvidence = orderComplete(order, types.data ?? [], today);
  const problems = draft && member ? correctionProblems(member.opening, draft, hasEvidence) : [];
  const changed = draft && member ? changes(member.opening, draft) : [];

  function show() {
    setDraft(null);
    setError(null);
    setQuery({ by: lookup.by, eis: lookup.eis.trim(), asAt: lookup.asAt });
  }

  async function submit(form: NewCorrection) {
    if (order.file === null) return;
    setError(null);
    try {
      const raised = await raise.mutateAsync({
        body: { ...form, remarks: form.remarks.trim() },
        evidence: {
          documentType: order.documentType,
          reference: order.reference.trim(),
          orderDate: order.orderDate,
          issuingAuthority: order.issuingAuthority.trim(),
          file: order.file,
        },
      });
      toast(t(raised.status === 'BLOCKED_CPF_DIVISION' ? 'i22.blocked' : 'i22.submitted', [raised.ref]));
      setDraft(null);
      setOrder(EMPTY_ORDER);
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sub2">{t('i22.hint')}</span>
      </div>
      <Card title={t('i22.member')}>
        <div className="bd rf-grid">
          <div className="rf-field">
            <label htmlFor="i22-by">{t('i22.findBy')}</label>
            <select
              id="i22-by"
              value={lookup.by}
              onChange={(e) => setLookup({ ...lookup, by: e.target.value as MemberLookup })}
            >
              {(['eis', 'cpfCode', 'uan'] as const).map((b) => (
                <option key={b} value={b}>
                  {t(`i22.by.${b}`)}
                </option>
              ))}
            </select>
          </div>
          <div className="rf-field">
            <label htmlFor="i22-eis">{t(`i22.by.${lookup.by}`)}</label>
            <input
              id="i22-eis"
              maxLength={20}
              value={lookup.eis}
              onChange={(e) => setLookup({ ...lookup, eis: e.target.value })}
            />
          </div>
          <div className="rf-field">
            <label htmlFor="i22-asat">{t('i22.asAt')}</label>
            <input
              id="i22-asat"
              type="date"
              value={lookup.asAt}
              onChange={(e) => setLookup({ ...lookup, asAt: e.target.value })}
            />
          </div>
          <div className="rf-field">
            <button
              type="button"
              className="btn"
              disabled={!LOOKUP[lookup.by].test(lookup.eis.trim()) || lookup.asAt === ''}
              onClick={show}
            >
              {t('i22.show')}
            </button>
          </div>
        </div>
        <Problem error={current.error} />
        {member && (
          <>
            <dl className="bd kv" aria-label={t('i22.current')}>
              <div>
                <dt>
                  {t('i22.name')}
                  <Src s="HRMS" />
                </dt>
                <dd>
                  {member.name} ({member.eis})
                </dd>
              </div>
              <div>
                <dt>
                  {t('i22.cpfCode')}
                  <Src s="HRMS" />
                </dt>
                <dd>{member.cpfCode}</dd>
              </div>
              <div>
                <dt>
                  {t('i22.uan')}
                  <Src s="HRMS" />
                </dt>
                <dd>{member.uan ?? '—'}</dd>
              </div>
              <div>
                <dt>
                  {t('i22.finYear')}
                  <Src s="SYS" />
                </dt>
                <dd>{member.finYear}</dd>
              </div>
              {PARTS.map((p) => (
                <div key={p}>
                  <dt>
                    {t('i22.currentOf', [t(`i22.part.${p}`)])}
                    <Src s="SYS" />
                  </dt>
                  <dd>{amount(member.opening[FIELD[p]])}</dd>
                </div>
              ))}
            </dl>
            {block && <p className="fl-hint">{t(block)}</p>}
            {mayCreate && draft === null && (
              <div className="rf-actbar">
                <button
                  type="button"
                  className="btn pri"
                  disabled={block !== null}
                  title={block ? t(block) : undefined}
                  onClick={() => {
                    setError(null);
                    setOrder(EMPTY_ORDER);
                    setDraft(emptyCorrection(member, query.asAt));
                  }}
                >
                  {t('action.newCorrection')}
                </button>
              </div>
            )}
          </>
        )}
      </Card>
      {draft && member && (
        <Card title={t('i22.newTitle', [member.eis, member.finYear])}>
          <Problem error={error} />
          <div className="wrap">
            <table className="t" aria-label={t('i22.parts')}>
              <thead>
                <tr>
                  <th>
                    {t('i22.partHead')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('i22.currentHead')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('i22.correctedHead')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('i22.differenceHead')}
                    <Src s="CALC" />
                  </th>
                </tr>
              </thead>
              <tbody>
                {PARTS.map((p) => {
                  const change = changed.find((c) => c.part === p);
                  return (
                    <tr key={p}>
                      <td>{t(`i22.part.${p}`)}</td>
                      <td>{amount(member.opening[FIELD[p]])}</td>
                      <td>
                        <input
                          aria-label={t('i22.correctedOf', [t(`i22.part.${p}`)])}
                          inputMode="decimal"
                          maxLength={16}
                          value={draft[FIELD[p]] ?? ''}
                          onChange={(e) => setDraft({ ...draft, [FIELD[p]]: e.target.value || null })}
                        />
                      </td>
                      <td>{change ? signed(change.difference) : '—'}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <div className="bd rf-grid">
            <div className="rf-field">
              <label htmlFor="i22-reason">{t('i22.reason')}</label>
              <select
                id="i22-reason"
                value={draft.reasonCode}
                onChange={(e) => setDraft({ ...draft, reasonCode: e.target.value })}
              >
                <option value="">—</option>
                <ReferenceOptions list="OB_CORRECTION_REASON" />
              </select>
            </div>
            <div className="rf-field">
              <label htmlFor="i22-interest">{t('i22.interestDate')}</label>
              <input
                id="i22-interest"
                type="date"
                value={draft.interestEffectiveDate}
                onChange={(e) => setDraft({ ...draft, interestEffectiveDate: e.target.value })}
              />
              <span className="hint">{t('i22.interestHint')}</span>
            </div>
            <div className="rf-field span2">
              <label htmlFor="i22-remarks">{t('i22.remarks')}</label>
              <TextAreaField
                id="i22-remarks"
                maxLength={1000}
                value={draft.remarks}
                onChange={(e) => setDraft({ ...draft, remarks: e.target.value })}
              />
            </div>
            <OrderFields
              idPrefix="i22"
              value={order}
              types={types.data ?? []}
              today={today}
              onChange={setOrder}
            />
            <div className="rf-field span2">
              <span className="lbl">
                {t('i22.route')}
                <Src s="SYS" />
              </span>
              <span>{t('i22.routeText')}</span>
            </div>
          </div>
          {problems.length > 0 && (
            <ul className="bd fl-list" aria-label={t('i22.problems')}>
              {problems.map((p) => (
                <li key={p}>{t(p)}</li>
              ))}
            </ul>
          )}
          <div className="rf-actbar">
            <button
              type="button"
              className="btn"
              onClick={() => {
                setDraft(null);
                setError(null);
              }}
            >
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className="btn pri"
              disabled={problems.length > 0 || raise.isPending}
              onClick={() => void submit(draft)}
            >
              {t('common.submitForApproval')}
            </button>
          </div>
        </Card>
      )}
      <Card title={t('i22.register')}>
        <div className="wrap">
          <ServerPagination
            queryKey={cpfKeys.corrections}
            fetchPage={cpfPages.corrections}
            empty={<Empty text={t('i22.noCorrections')} />}
          >
            {(corrections) => (
              <table className="t">
                <thead>
                  <tr>
                    <th>
                      {t('i22.ref')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('i22.memberHead')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('i22.partsHead')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('i22.reason')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('i22.statusHead')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('i22.raised')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('i22.deciders')}
                      <Src s="SYS" />
                    </th>
                    <th aria-label={t('common.actions')} />
                  </tr>
                </thead>
                <tbody>
                  {corrections.map((c) => (
                    <CorrectionRow
                      key={c.ref}
                      c={c}
                      reasonLabel={reference.label('OB_CORRECTION_REASON', c.reasonCode)}
                    />
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

function CorrectionRow({ c, reasonLabel }: { c: Correction; reasonLabel: string }) {
  const { t } = useI18n();
  return (
    <tr data-reference={c.ref}>
      <td>
        <b>{c.ref}</b>
        <div className="sub2">
          {c.finYear} · {date(c.asAtDate)}
        </div>
      </td>
      <td>{c.eis}</td>
      <td>
        {c.parts.map((p) => (
          <div key={p.part}>
            {t(`i22.part.${p.part}`)}: {amount(p.oldAmount)} → {amount(p.newAmount)} (
            {signed(paise(p.difference) ?? 0n)})
          </div>
        ))}
        <div className="sub2">{t('i22.interestFrom', [date(c.interestEffectiveDate)])}</div>
        {/* SRS l.2208: the interest effect (CALC); not configured until FCI gives the method (Q-072). */}
        <div className="sub2">
          {c.interestEffect === null
            ? t('i22.interestEffectNotConfigured')
            : t('i22.interestEffect', [amount(c.interestEffect)])}
        </div>
      </td>
      <td>
        {reasonLabel}
        <div className="sub2">{c.remarks}</div>
      </td>
      <td>
        <span className={`pill ${CORRECTION_STATUS_PILL[c.status]}`}>{t(`i22.status.${c.status}`)}</span>
        {c.statusReason && <div className="sub2">{c.statusReason}</div>}
      </td>
      <td>
        {c.raisedBy}
        <div className="sub2">{dateTime(c.raisedAt)}</div>
      </td>
      <td>
        {c.checker ?? '—'}
        <div className="sub2">{c.hqApprover ?? '—'}</div>
        {c.appliedAt && <div className="sub2">{t('i22.appliedAt', [dateTime(c.appliedAt)])}</div>}
      </td>
      <td>
        <div className="acts">
          <HistoryButton entity="opening_balance_correction" recordKey={c.ref} />
          {c.evidenceId !== null && (
            <a className="ia" href={evidenceUrl(c.ref, c.evidenceId)} download>
              {t('i22.evidence')}
            </a>
          )}
        </div>
      </td>
    </tr>
  );
}
