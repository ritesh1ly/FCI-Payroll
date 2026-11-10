'use client';

import { useState } from 'react';
import { type OrderDraft, EMPTY_ORDER, OrderFields, useDocumentTypes } from '@/modules/general';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import {
  amount,
  Card,
  Empty,
  Problem,
  ReasonDialog,
  Src,
  useLocalPage,
  useToast,
  TextAreaField,
} from '@/shared/ui';
import type { NewSanction, Sanction, SanctionFile } from '../api/cpfApi';
import {
  requestProblems,
  resubmittable,
  STAGE_PILL,
  validAmount,
  withdrawable,
} from '../business/sanctionRules';
import {
  useMySanctions,
  useRaiseSanction,
  useResubmitSanction,
  useSanctionPurposes,
  useWithdrawSanction,
} from '../orchestration/cpfQueries';

const EMPTY: Omit<NewSanction, 'documents'> = {
  purposeCode: '',
  amount: '',
  propertyAddress: '',
  propertyDescription: '',
};

/**
 * E-01 eSeva - CPF advance and withdrawal requests (SRS-6-4 step 1, SRS-6-14, SRS-6-26,
 * BR-CPF-039; plan 07 D-14): the member raises a request with the checklist documents, sees each
 * request's stage, withdraws one before the sanction and resubmits one returned for correction.
 */
export function CpfWithdrawals() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const today = businessToday();
  const purposes = useSanctionPurposes();
  const mine = useMySanctions();
  const types = useDocumentTypes();
  const raise = useRaiseSanction();
  const withdraw = useWithdrawSanction();
  const resubmit = useResubmitSanction();
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState(EMPTY);
  const [files, setFiles] = useState<Record<string, OrderDraft>>({});
  const [withdrawing, setWithdrawing] = useState<Sanction | null>(null);
  const [correcting, setCorrecting] = useState<Sanction | null>(null);
  const [correction, setCorrection] = useState(EMPTY);
  const [error, setError] = useState<unknown>(null);
  const shown = useLocalPage(mine.data ?? [], '');
  const mayCreate = can(me.data, 'E-01', 'CREATE');

  const purpose = (purposes.data ?? []).find((p) => p.code === draft.purposeCode);
  const documents: SanctionFile[] = Object.values(files)
    .filter((f) => f.file !== null)
    .map((f) => ({
      documentType: f.documentType,
      reference: f.reference,
      documentDate: f.orderDate,
      issuingAuthority: f.issuingAuthority,
      file: f.file as File,
    }));
  const problems = requestProblems({ ...draft, documents }, purpose, today);

  async function submit() {
    setError(null);
    try {
      const made = await raise.mutateAsync({ ...draft, documents });
      toast(t('e01s.submitted', [made.id]));
      setCreating(false);
      setDraft(EMPTY);
      setFiles({});
    } catch (cause) {
      setError(cause);
    }
  }

  async function confirmWithdraw(s: Sanction, reason: string) {
    setError(null);
    try {
      await withdraw.mutateAsync({ id: s.id, reason });
      toast(t('e01s.withdrawn', [s.id]));
    } catch (cause) {
      if (!(cause instanceof ApiError && cause.messageKey === 'otp.cancelled')) setError(cause);
    } finally {
      setWithdrawing(null);
    }
  }

  async function sendCorrection(s: Sanction) {
    setError(null);
    try {
      await resubmit.mutateAsync({ id: s.id, ...correction });
      toast(t('e01s.resubmitted', [s.id]));
      setCorrecting(null);
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <Card
      title={t('e01s.title')}
      actions={
        mayCreate && !creating ? (
          <button type="button" className="btn pri" onClick={() => setCreating(true)}>
            {t('e01s.new')}
          </button>
        ) : undefined
      }
    >
      <Problem error={mine.error ?? error} />
      {creating && (
        <div className="bd">
          <div className="rf-grid">
            <div className="rf-field">
              <label htmlFor="e01s-purpose">{t('i04.purpose')}</label>
              <select
                id="e01s-purpose"
                value={draft.purposeCode}
                onChange={(e) => {
                  setDraft({ ...draft, purposeCode: e.target.value });
                  setFiles({});
                }}
              >
                <option value="">—</option>
                {(purposes.data ?? []).map((p) => (
                  <option key={p.code} value={p.code}>
                    {p.name} ({t(`i04.kind.${p.category}`)})
                  </option>
                ))}
              </select>
            </div>
            <div className="rf-field">
              <label htmlFor="e01s-amount">{t('i04.requested')}</label>
              <input
                maxLength={15}
                id="e01s-amount"
                inputMode="decimal"
                value={draft.amount}
                onChange={(e) => setDraft({ ...draft, amount: e.target.value })}
              />
              <span className="hint">{t('e01s.capHint')}</span>
            </div>
            {purpose?.propertyDetails && (
              <>
                <div className="rf-field span2">
                  <label htmlFor="e01s-address">{t('e01s.address')}</label>
                  <TextAreaField
                    id="e01s-address"
                    maxLength={500}
                    value={draft.propertyAddress}
                    onChange={(e) => setDraft({ ...draft, propertyAddress: e.target.value })}
                  />
                </div>
                <div className="rf-field span2">
                  <label htmlFor="e01s-description">{t('e01s.description')}</label>
                  <TextAreaField
                    id="e01s-description"
                    maxLength={1000}
                    value={draft.propertyDescription}
                    onChange={(e) => setDraft({ ...draft, propertyDescription: e.target.value })}
                  />
                </div>
              </>
            )}
          </div>
          {purpose?.documents.map((d) => (
            <fieldset key={d.documentType} className="rf-grid">
              <legend>
                {(types.data ?? []).find((x) => x.code === d.documentType)?.name ?? d.documentType}
                {d.mandatory ? ` · ${t('e01s.mandatory')}` : ''}
              </legend>
              <OrderFields
                idPrefix={`e01s-${d.documentType}`}
                value={files[d.documentType] ?? { ...EMPTY_ORDER, documentType: d.documentType }}
                types={(types.data ?? []).filter((x) => x.code === d.documentType)}
                today={today}
                onChange={(next) =>
                  setFiles({ ...files, [d.documentType]: { ...next, documentType: d.documentType } })
                }
              />
            </fieldset>
          ))}
          {problems.length > 0 && (
            <ul className="fl-hint" aria-label={t('e01s.problems')}>
              {problems.map((p) => (
                <li key={p.key}>{t(p.key, p.args ?? [])}</li>
              ))}
            </ul>
          )}
          <div className="rf-actbar">
            <button
              type="button"
              className="btn"
              onClick={() => {
                setCreating(false);
                setDraft(EMPTY);
                setFiles({});
              }}
            >
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className="btn pri"
              disabled={problems.length > 0 || raise.isPending}
              onClick={() => void submit()}
            >
              {t('e01s.submit')}
            </button>
          </div>
        </div>
      )}
      {mine.isSuccess && (mine.data ?? []).length === 0 && !creating && <Empty text={t('e01s.none')} />}
      {(mine.data ?? []).length > 0 && (
        <>
          <table className="t" aria-label={t('e01s.mine')}>
            <thead>
              <tr>
                <th>
                  {t('i04.no')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('i04.purpose')}
                  <Src s="ESS" />
                </th>
                <th>
                  {t('i04.requested')}
                  <Src s="ESS" />
                </th>
                <th>
                  {t('i04.sanctioned')}
                  <Src s="CALC" />
                </th>
                <th>
                  {t('i04.stage')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('i04.number')}
                  <Src s="SYS" />
                </th>
                <th aria-label={t('common.actions')} />
              </tr>
            </thead>
            <tbody>
              {shown.rows.map((s) => (
                <tr key={s.id} data-reference={String(s.id)}>
                  <td>{s.id}</td>
                  <td>{s.purposeName ?? '—'}</td>
                  <td>{amount(s.requested)}</td>
                  <td>
                    {s.sanctioned === null ? '—' : amount(s.sanctioned)}
                    {s.capped && <div className="sub2">{t('e01s.capped')}</div>}
                  </td>
                  <td>
                    <span className={`pill ${STAGE_PILL[s.stage]}`}>{t(`i04.stage.${s.stage}`)}</span>
                    {(s.stage === 'RETURNED' || s.stage === 'REJECTED') && s.tiers.length > 0 && (
                      <div className="sub2">{s.tiers[s.tiers.length - 1]?.remark}</div>
                    )}
                  </td>
                  <td>{s.sanctionNumber ?? '—'}</td>
                  <td>
                    <div className="acts">
                      {mayCreate && resubmittable(s.stage) && (
                        <button
                          type="button"
                          className="btn sm"
                          onClick={() => {
                            setCorrecting(s);
                            setCorrection({
                              ...EMPTY,
                              amount: String(s.requested ?? ''),
                              propertyAddress: s.propertyAddress ?? '',
                              propertyDescription: s.propertyDescription ?? '',
                            });
                          }}
                        >
                          {t('e01s.resubmit')}
                        </button>
                      )}
                      {mayCreate && withdrawable(s.stage) && (
                        <button type="button" className="btn sm" onClick={() => setWithdrawing(s)}>
                          {t('e01s.withdraw')}
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {shown.pager}
        </>
      )}
      {correcting && (
        <div className="bd">
          <h4>{t('e01s.correctionOf', [correcting.id])}</h4>
          <div className="rf-grid">
            <div className="rf-field">
              <label htmlFor="e01s-c-amount">{t('i04.requested')}</label>
              <input
                inputMode="decimal"
                maxLength={15}
                id="e01s-c-amount"
                value={correction.amount}
                onChange={(e) => setCorrection({ ...correction, amount: e.target.value })}
              />
            </div>
            {correcting.propertyAddress !== null && (
              <div className="rf-field span2">
                <label htmlFor="e01s-c-address">{t('e01s.address')}</label>
                <TextAreaField
                  maxLength={500}
                  id="e01s-c-address"
                  value={correction.propertyAddress}
                  onChange={(e) => setCorrection({ ...correction, propertyAddress: e.target.value })}
                />
              </div>
            )}
            {correcting.propertyAddress !== null && (
              // SRS-12-9: the whole request is corrected before resubmission, the description too.
              <div className="rf-field span2">
                <label htmlFor="e01s-c-description">{t('e01s.description')}</label>
                <TextAreaField
                  maxLength={1000}
                  id="e01s-c-description"
                  value={correction.propertyDescription}
                  onChange={(e) => setCorrection({ ...correction, propertyDescription: e.target.value })}
                />
              </div>
            )}
          </div>
          {!validAmount(correction.amount) && <p className="fl-hint">{t('e01s.problem.amount')}</p>}
          <div className="rf-actbar">
            <button type="button" className="btn" onClick={() => setCorrecting(null)}>
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className="btn pri"
              disabled={!validAmount(correction.amount) || resubmit.isPending}
              onClick={() => void sendCorrection(correcting)}
            >
              {t('e01s.resubmit')}
            </button>
          </div>
        </div>
      )}
      {withdrawing && (
        <ReasonDialog
          title={t('e01s.withdrawTitle', [withdrawing.id])}
          confirmLabel={t('e01s.withdraw')}
          onConfirm={(why) => void confirmWithdraw(withdrawing, why)}
          onCancel={() => setWithdrawing(null)}
        />
      )}
    </Card>
  );
}
