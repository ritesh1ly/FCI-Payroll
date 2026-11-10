'use client';

import { useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { RuleCatalogue } from '@/modules/configuration';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { can, hqOnlyBlock, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import {
  Card,
  date,
  dateTime,
  Empty,
  Problem,
  Src,
  useLocalPage,
  useToast,
  TextAreaField,
} from '@/shared/ui';
import type { InterestRate, NewRate } from '../api/cpfApi';
import {
  FUNDS,
  RATE_SOURCES,
  RATE_STATUS_PILL,
  finYearOf,
  finYearRange,
  mayDeclare,
  rateProblems,
  seriesKey,
  validPercent,
} from '../business/interestRateRules';
import { useDeclareRate, useInterestRates, useRecordRate } from '../orchestration/cpfQueries';
import { WithdrawalPurposes } from './WithdrawalPurposes';

/** A new rate as the form starts it: this financial year, from its first day, the CPF fund. */
function emptyRate(today: string): NewRate {
  const finYear = finYearOf(today);
  return {
    finYear,
    fundType: 'CPF',
    rateSource: 'FCI_TRUST',
    ratePercent: '',
    appliesFrom: finYearRange(finYear)?.start ?? today,
    appliesTo: null,
    applicabilityNote: null,
    bonusInterestPct: null,
    declared: true,
    reason: '',
  };
}

/**
 * M-19 PF Interest Rates & Parameters (SRS-6-11, BR-CPF-033, SRS M-19 field table): the
 * FCI-declared rate for active and deputed members and the EPFO-declared rate for inactive members,
 * per financial year and fund, recorded at Headquarters by the Rule Administrator and approved by the
 * Rule Approver; a rate recorded undeclared is declared later and is never used by an interest run
 * until then. Below, the CPF parameters this screen owns, held as rule versions (M-08 routes).
 */
export function InterestRates({ startNew = false }: { startNew?: boolean } = {}) {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const rates = useInterestRates();
  const record = useRecordRate();
  const declare = useDeclareRate();
  const hqBlock = hqOnlyBlock(me.data, 'M-19');
  const mayCreate = can(me.data, 'M-19', 'CREATE');
  const mayEdit = can(me.data, 'M-19', 'EDIT');
  // ＋ New Rate Set of the drawn screen opens this section at the form.
  const [draft, setDraft] = useState<NewRate | null>(() => (startNew ? emptyRate(businessToday()) : null));
  const [declaring, setDeclaring] = useState<InterestRate | null>(null);
  const [declared, setDeclared] = useState({ ratePercent: '', reason: '' });
  const [error, setError] = useState<unknown>(null);
  const pending = rates.data?.pending ?? [];
  // The rates arrive with their pending proposals and parameters; the table shows a page of them.
  const shownRates = useLocalPage(rates.data?.rates ?? [], '');
  const pendingSeries = new Set(pending.map(seriesKey));
  const problems = draft ? rateProblems(draft) : [];

  async function submitRate(form: NewRate) {
    setError(null);
    try {
      await record.mutateAsync({
        ...form,
        ratePercent: form.ratePercent.trim(),
        bonusInterestPct: form.bonusInterestPct?.trim() ?? null,
        applicabilityNote: form.applicabilityNote?.trim() || null,
        reason: form.reason.trim(),
      });
      toast(t('m19.submitted', [form.finYear]));
      setDraft(null);
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  async function submitDeclaration(rate: InterestRate) {
    setError(null);
    try {
      await declare.mutateAsync({
        id: rate.id,
        body: { ratePercent: declared.ratePercent.trim(), reason: declared.reason.trim() },
      });
      toast(t('m19.declareSubmitted', [rate.finYear]));
      setDeclaring(null);
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sub2">{t('m19.hint')}</span>
        {mayCreate && (
          <button
            type="button"
            className="btn pri"
            disabled={hqBlock !== null || draft !== null}
            title={hqBlock ? t(hqBlock) : undefined}
            onClick={() => {
              setDeclaring(null);
              setError(null);
              setDraft(emptyRate(businessToday()));
            }}
          >
            {t('action.newRate')}
          </button>
        )}
      </div>
      <Problem error={rates.error ?? (draft || declaring ? null : error)} />
      <Card title={t('m19.rates')}>
        {rates.isSuccess && rates.data.rates.length === 0 ? (
          <Empty text={t('m19.noRates')} />
        ) : (
          <div className="wrap">
            <table className="t">
              <thead>
                <tr>
                  <th>
                    {t('m19.finYear')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('m19.fund')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('m19.rateSource')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m19.ratePercent')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('m19.appliesTo')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m19.bonus')}
                    <Src s="ENT" />
                  </th>
                  <th>
                    {t('m19.status')}
                    <Src s="SYS" />
                  </th>
                  <th aria-label={t('common.actions')} />
                </tr>
              </thead>
              <tbody>
                {shownRates.rows.map((r) => (
                  <tr key={r.id} data-reference={seriesKey(r)}>
                    <td>
                      <b>{r.finYear}</b>
                    </td>
                    <td>{t(`m19.fundType.${r.fundType}`)}</td>
                    <td>
                      {t(`m19.source.${r.rateSource}`)}
                      <div className="sub2">{t(`m19.population.${r.memberPopulation}`)}</div>
                    </td>
                    <td>
                      {r.ratePercent} %
                      {!r.declared && (
                        <div>
                          <span className="pill p-warn">{t('m19.undeclared')}</span>
                        </div>
                      )}
                    </td>
                    <td>
                      {date(r.appliesFrom)} – {r.appliesTo ? date(r.appliesTo) : t('mst.open')}
                      {r.applicabilityNote && <div className="sub2">{r.applicabilityNote}</div>}
                    </td>
                    <td>{r.bonusInterestPct === null ? '—' : `${r.bonusInterestPct} %`}</td>
                    <td>
                      <span className={`pill ${RATE_STATUS_PILL[r.status]}`}>{t(`status.${r.status}`)}</span>
                    </td>
                    <td>
                      <div className="acts">
                        <HistoryButton entity="pf_interest_rate" recordKey={seriesKey(r)} />
                        {mayEdit && mayDeclare(r, pendingSeries) && (
                          <button
                            type="button"
                            className="ia"
                            aria-label={t('m19.declareOf', [seriesKey(r)])}
                            disabled={hqBlock !== null}
                            title={hqBlock ? t(hqBlock) : undefined}
                            onClick={() => {
                              setDraft(null);
                              setError(null);
                              setDeclared({ ratePercent: String(r.ratePercent), reason: '' });
                              setDeclaring(r);
                            }}
                          >
                            {t('action.declareRate')}
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {shownRates.pager}
          </div>
        )}
        {pending.length > 0 && (
          <ul className="bd fl-list" aria-label={t('m19.pendingTitle')}>
            {pending.map((p) => (
              <li key={p.approvalId}>
                <span className="pill p-warn">{t(`m19.pending.${p.kind}`)}</span>{' '}
                {t('m19.pendingLine', [
                  p.finYear,
                  p.fundType,
                  t(`m19.source.${p.rateSource}`),
                  p.ratePercent,
                  date(p.appliesFrom),
                  p.proposedBy,
                  dateTime(p.proposedAt),
                ])}
              </li>
            ))}
          </ul>
        )}
      </Card>
      {draft && (
        <Card title={t('m19.newRate')}>
          <Problem error={error} />
          <div className="bd rf-grid">
            <div className="rf-field">
              <label htmlFor="m19-year">{t('m19.finYear')}</label>
              <input
                id="m19-year"
                maxLength={7}
                placeholder="2026-27"
                value={draft.finYear}
                onChange={(e) => {
                  const range = finYearRange(e.target.value);
                  setDraft({
                    ...draft,
                    finYear: e.target.value,
                    appliesFrom: range?.start ?? draft.appliesFrom,
                  });
                }}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="m19-fund">{t('m19.fund')}</label>
              <select
                id="m19-fund"
                value={draft.fundType}
                onChange={(e) => setDraft({ ...draft, fundType: e.target.value as NewRate['fundType'] })}
              >
                {FUNDS.map((f) => (
                  <option key={f} value={f}>
                    {t(`m19.fundType.${f}`)}
                  </option>
                ))}
              </select>
            </div>
            <div className="rf-field">
              <label htmlFor="m19-source">{t('m19.rateSource')}</label>
              <select
                id="m19-source"
                value={draft.rateSource}
                onChange={(e) => {
                  const rateSource = e.target.value as NewRate['rateSource'];
                  // Bonus interest is declared over the Trust rate only.
                  setDraft({
                    ...draft,
                    rateSource,
                    bonusInterestPct: rateSource === 'FCI_TRUST' ? draft.bonusInterestPct : null,
                  });
                }}
              >
                {RATE_SOURCES.map((s) => (
                  <option key={s} value={s}>
                    {t(`m19.source.${s}`)}
                  </option>
                ))}
              </select>
              <span className="hint">
                {t(`m19.population.${draft.rateSource === 'FCI_TRUST' ? 'ACTIVE_AND_DEPUTED' : 'INACTIVE'}`)}
              </span>
            </div>
            <div className="rf-field">
              <label htmlFor="m19-rate">{t('m19.ratePercent')}</label>
              <input
                id="m19-rate"
                inputMode="decimal"
                maxLength={6}
                value={draft.ratePercent}
                onChange={(e) => setDraft({ ...draft, ratePercent: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="m19-from">{t('mst.from')}</label>
              <input
                id="m19-from"
                type="date"
                value={draft.appliesFrom}
                onChange={(e) => setDraft({ ...draft, appliesFrom: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="m19-to">{t('mst.to')}</label>
              <input
                id="m19-to"
                type="date"
                value={draft.appliesTo ?? ''}
                onChange={(e) => setDraft({ ...draft, appliesTo: e.target.value || null })}
              />
            </div>
            {draft.rateSource === 'FCI_TRUST' && (
              <div className="rf-field">
                <label htmlFor="m19-bonus">{t('m19.bonus')}</label>
                <input
                  id="m19-bonus"
                  inputMode="decimal"
                  maxLength={6}
                  value={draft.bonusInterestPct ?? ''}
                  onChange={(e) => setDraft({ ...draft, bonusInterestPct: e.target.value || null })}
                />
              </div>
            )}
            <div className="rf-field">
              <label htmlFor="m19-declared">
                <input
                  id="m19-declared"
                  type="checkbox"
                  checked={draft.declared}
                  onChange={(e) => setDraft({ ...draft, declared: e.target.checked })}
                />{' '}
                {t('m19.declared')}
              </label>
              <span className="hint">{t('m19.declaredHint')}</span>
            </div>
            <div className="rf-field span2">
              <label htmlFor="m19-note">{t('m19.note')}</label>
              <TextAreaField
                id="m19-note"
                maxLength={500}
                value={draft.applicabilityNote ?? ''}
                onChange={(e) => setDraft({ ...draft, applicabilityNote: e.target.value || null })}
              />
            </div>
            <div className="rf-field span2">
              <label htmlFor="m19-reason">{t('common.reason')}</label>
              <TextAreaField
                id="m19-reason"
                maxLength={500}
                value={draft.reason}
                onChange={(e) => setDraft({ ...draft, reason: e.target.value })}
              />
            </div>
          </div>
          {problems.length > 0 && (
            <ul className="bd fl-list" aria-label={t('m19.problems')}>
              {problems.map((p) => (
                <li key={p}>{t(p)}</li>
              ))}
            </ul>
          )}
          <p className="fl-hint">{t('m19.checkerNote')}</p>
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
              disabled={problems.length > 0 || record.isPending}
              onClick={() => void submitRate(draft)}
            >
              {t('common.submitForApproval')}
            </button>
          </div>
        </Card>
      )}
      {declaring && (
        <Card title={t('m19.declareOf', [seriesKey(declaring)])}>
          <Problem error={error} />
          <div className="bd rf-grid">
            <div className="rf-field">
              <label htmlFor="m19-declared-rate">{t('m19.declaredRate')}</label>
              <input
                id="m19-declared-rate"
                inputMode="decimal"
                maxLength={6}
                value={declared.ratePercent}
                onChange={(e) => setDeclared({ ...declared, ratePercent: e.target.value })}
              />
            </div>
            <div className="rf-field span2">
              <label htmlFor="m19-declare-reason">{t('common.reason')}</label>
              <TextAreaField
                id="m19-declare-reason"
                maxLength={500}
                value={declared.reason}
                onChange={(e) => setDeclared({ ...declared, reason: e.target.value })}
              />
            </div>
          </div>
          <div className="rf-actbar">
            <button
              type="button"
              className="btn"
              onClick={() => {
                setDeclaring(null);
                setError(null);
              }}
            >
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className="btn pri"
              disabled={!validPercent(declared.ratePercent) || !declared.reason.trim() || declare.isPending}
              onClick={() => void submitDeclaration(declaring)}
            >
              {t('common.submitForApproval')}
            </button>
          </div>
        </Card>
      )}
      <WithdrawalPurposes />
      <Card title={t('m19.parameters')}>
        <p className="sub2">{t('m19.parametersHint')}</p>
        {/* The parameters are rule versions: read through the M-08 catalogue (Rule Administrator, Rule Approver, and the CPF Division for reading, V077_20). */}
        {can(me.data, 'M-08', 'VIEW') ? (
          <RuleCatalogue register="M-19" />
        ) : (
          <p className="sub2">{t('m19.parametersNoAccess')}</p>
        )}
      </Card>
    </div>
  );
}
