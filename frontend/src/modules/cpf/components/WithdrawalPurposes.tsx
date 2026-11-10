'use client';

import { useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { useDocumentTypes } from '@/modules/general';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { can, hqOnlyBlock, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import { Card, date, Empty, Problem, Src, useLocalPage, useToast, TextAreaField } from '@/shared/ui';
import type { PurposeProposal, WithdrawalPurpose } from '../api/cpfApi';
import { CATEGORIES, LIMIT_KEYS, TIERS, purposeProblems } from '../business/purposeRules';
import { useProposePurpose, useWithdrawalPurposes } from '../orchestration/cpfQueries';

type Draft = PurposeProposal & { reason: string };

function fresh(today: string): Draft {
  return {
    code: '',
    name: '',
    category: 'ADVANCE',
    limitRuleKey: null,
    maxOccurrences: null,
    noCasteDispute: false,
    propertyDetails: false,
    effectiveFrom: today,
    confirmed: false,
    documents: [],
    reason: '',
  };
}

function nextVersion(p: WithdrawalPurpose): Draft {
  return {
    code: p.code,
    name: p.name,
    category: p.category,
    limitRuleKey: p.limitRuleKey,
    maxOccurrences: p.maxOccurrences,
    noCasteDispute: p.noCasteDispute,
    propertyDetails: p.propertyDetails,
    effectiveFrom: '',
    confirmed: p.confirmed,
    documents: p.documents.map((d) => ({ ...d })),
    reason: '',
  };
}

/**
 * M-19 card: CPF withdrawal purposes and their document checklists (SRS-6-5, SRS-6-14, SRS-6-39;
 * BR-CPF-028, 039; plan 07 D-5). The Rule Administrator at Headquarters proposes a new version of a
 * purpose from a later date; a different Rule Approver approves it; the version in force then ends the
 * day before. Purposes FCI has not confirmed are marked (Q-074).
 */
export function WithdrawalPurposes() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const list = useWithdrawalPurposes();
  const propose = useProposePurpose();
  const mayCreate = can(me.data, 'M-19', 'CREATE');
  const hqBlock = hqOnlyBlock(me.data, 'M-19');
  const types = useDocumentTypes(mayCreate);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [error, setError] = useState<unknown>(null);
  const purposes = list.data?.purposes ?? [];
  // The purposes arrive with their pending proposals; the table shows a page of them.
  const shown = useLocalPage(purposes, '');
  const pendingCodes = new Set((list.data?.pending ?? []).map((p) => p.proposal.code));
  const problems = draft ? purposeProblems(draft, purposes) : [];

  function open(next: Draft) {
    setError(null);
    setDraft(next);
  }

  async function submit(form: Draft) {
    setError(null);
    try {
      await propose.mutateAsync({ ...form, name: form.name.trim(), reason: form.reason.trim() });
      toast(t('m19p.submitted', [form.code]));
      setDraft(null);
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  return (
    <Card title={t('m19p.title')}>
      <p className="sub2">{t('m19p.hint')}</p>
      {mayCreate && (
        <div className="toolbar">
          <button
            type="button"
            className="btn"
            disabled={hqBlock !== null || draft !== null}
            title={hqBlock ? t(hqBlock) : undefined}
            onClick={() => open(fresh(businessToday()))}
          >
            {t('action.newPurpose')}
          </button>
        </div>
      )}
      <Problem error={list.error ?? (draft ? null : error)} />
      {list.isSuccess && purposes.length === 0 ? (
        <Empty text={t('m19p.none')} />
      ) : (
        <div className="wrap">
          <table className="t" aria-label={t('m19p.title')}>
            <thead>
              <tr>
                <th>
                  {t('m19p.purpose')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('m19p.category')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('m19p.limit')}
                  <Src s="RULE" />
                </th>
                <th>
                  {t('m19p.checklist')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('m19p.period')}
                  <Src s="ENT" />
                </th>
                <th aria-label={t('common.actions')} />
              </tr>
            </thead>
            <tbody>
              {shown.rows.map((p) => (
                <tr key={p.id} data-reference={`${p.code}|${p.effectiveFrom}`}>
                  <td>
                    <b>{p.name}</b>
                    <div className="sub2">{p.code}</div>
                    {!p.confirmed && <span className="pill p-warn">{t('m19p.unconfirmed')}</span>}
                  </td>
                  <td>
                    {t(`m19p.category.${p.category}`)}
                    {p.propertyDetails && <div className="sub2">{t('m19p.propertyDetails')}</div>}
                    {p.noCasteDispute && <div className="sub2">{t('m19p.noCasteDispute')}</div>}
                    {p.maxOccurrences !== null && (
                      <div className="sub2">{t('m19p.quotaOf', [p.maxOccurrences])}</div>
                    )}
                  </td>
                  <td>
                    {p.limitRuleKey ? (
                      `${p.limitRuleKey} - ${t(`m19p.limitKey.${p.limitRuleKey}`)}`
                    ) : (
                      <span className="sub2">{t('mst.notConfigured')}</span>
                    )}
                  </td>
                  <td>
                    {p.documents.length === 0 ? (
                      <span className="sub2">{t('m19p.noChecklist')}</span>
                    ) : (
                      p.documents.map((d) => (
                        <div key={d.documentType}>
                          {d.documentType}
                          {d.mandatory ? ' *' : ''} · {t(`m19p.tier.${d.tier}`)}
                        </div>
                      ))
                    )}
                  </td>
                  <td>
                    {date(p.effectiveFrom)} – {p.effectiveTo ? date(p.effectiveTo) : t('mst.open')}
                    {p.inForce && (
                      <div>
                        <span className="pill p-ok">{t('m19p.inForce')}</span>
                      </div>
                    )}
                  </td>
                  <td>
                    <div className="acts">
                      <HistoryButton entity="cpf_withdrawal_purpose" recordKey={p.code} />
                      {mayCreate && p.effectiveTo === null && (
                        <button
                          type="button"
                          className="ia"
                          aria-label={t('m19p.newVersionOf', [p.code])}
                          disabled={hqBlock !== null || draft !== null || pendingCodes.has(p.code)}
                          title={
                            hqBlock ? t(hqBlock) : pendingCodes.has(p.code) ? t('m19p.pendingOne') : undefined
                          }
                          onClick={() => open(nextVersion(p))}
                        >
                          {t('action.newVersion')}
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {shown.pager}
        </div>
      )}
      {(list.data?.pending ?? []).length > 0 && (
        <ul className="bd fl-list" aria-label={t('m19p.pendingTitle')}>
          {(list.data?.pending ?? []).map((p) => (
            <li key={p.approvalId}>
              <span className="pill p-warn">{t('status.PENDING_APPROVAL')}</span>{' '}
              {t('m19p.pendingLine', [
                p.proposal.code,
                date(p.proposal.effectiveFrom),
                p.proposal.proposedBy,
              ])}
            </li>
          ))}
        </ul>
      )}
      {draft && (
        <div className="bd">
          <h3>{t('m19p.formTitle', [draft.code || '…'])}</h3>
          <Problem error={error} />
          <div className="rf-grid">
            <div className="rf-field">
              <label htmlFor="m19p-code">{t('m19p.code')}</label>
              <input
                id="m19p-code"
                maxLength={30}
                value={draft.code}
                onChange={(e) => setDraft({ ...draft, code: e.target.value.toUpperCase() })}
              />
            </div>
            <div className="rf-field span2">
              <label htmlFor="m19p-name">{t('m19p.name')}</label>
              <input
                id="m19p-name"
                maxLength={200}
                value={draft.name}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="m19p-category">{t('m19p.category')}</label>
              <select
                id="m19p-category"
                value={draft.category}
                onChange={(e) => {
                  const category = e.target.value as Draft['category'];
                  // A final payment carries no limit rule.
                  setDraft({
                    ...draft,
                    category,
                    limitRuleKey: category === 'FINAL' ? null : draft.limitRuleKey,
                  });
                }}
              >
                {CATEGORIES.map((c) => (
                  <option key={c} value={c}>
                    {t(`m19p.category.${c}`)}
                  </option>
                ))}
              </select>
            </div>
            <div className="rf-field">
              <label htmlFor="m19p-limit">{t('m19p.limit')}</label>
              <select
                id="m19p-limit"
                value={draft.limitRuleKey ?? ''}
                disabled={draft.category === 'FINAL'}
                onChange={(e) => setDraft({ ...draft, limitRuleKey: e.target.value || null })}
              >
                <option value="">{t('m19p.noLimit')}</option>
                {LIMIT_KEYS.map((k) => (
                  <option key={k} value={k}>
                    {`${k} - ${t(`m19p.limitKey.${k}`)}`}
                  </option>
                ))}
              </select>
            </div>
            <div className="rf-field">
              <label htmlFor="m19p-quota">{t('m19p.quota')}</label>
              <input
                id="m19p-quota"
                inputMode="numeric"
                maxLength={3}
                value={draft.maxOccurrences ?? ''}
                onChange={(e) =>
                  setDraft({
                    ...draft,
                    maxOccurrences: e.target.value === '' ? null : Number(e.target.value),
                  })
                }
              />
            </div>
            <div className="rf-field">
              <label htmlFor="m19p-from">{t('mst.from')}</label>
              <input
                id="m19p-from"
                type="date"
                value={draft.effectiveFrom}
                onChange={(e) => setDraft({ ...draft, effectiveFrom: e.target.value })}
              />
            </div>
            {(
              [
                ['propertyDetails', 'm19p.propertyDetails'],
                ['noCasteDispute', 'm19p.noCasteDispute'],
                ['confirmed', 'm19p.confirmed'],
              ] as const
            ).map(([field, label]) => (
              <div className="rf-field" key={field}>
                <label htmlFor={`m19p-${field}`}>
                  <input
                    id={`m19p-${field}`}
                    type="checkbox"
                    checked={draft[field]}
                    onChange={(e) => setDraft({ ...draft, [field]: e.target.checked })}
                  />{' '}
                  {t(label)}
                </label>
              </div>
            ))}
          </div>
          <table className="t" aria-label={t('m19p.checklist')}>
            <thead>
              <tr>
                <th>
                  {t('ord.type')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('m19p.mandatory')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('m19p.tier')}
                  <Src s="ENT" />
                </th>
                <th aria-label={t('common.actions')} />
              </tr>
            </thead>
            <tbody>
              {draft.documents.map((d, i) => (
                <tr key={i}>
                  <td>
                    <select
                      aria-label={t('m19p.documentOf', [i + 1])}
                      value={d.documentType}
                      onChange={(e) => {
                        const documents = draft.documents.map((x, j) =>
                          j === i ? { ...x, documentType: e.target.value } : x,
                        );
                        setDraft({ ...draft, documents });
                      }}
                    >
                      <option value="">—</option>
                      {(types.data ?? []).map((x) => (
                        <option key={x.code} value={x.code}>
                          {x.name}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td>
                    <input
                      type="checkbox"
                      aria-label={t('m19p.mandatoryOf', [i + 1])}
                      checked={d.mandatory}
                      onChange={(e) => {
                        const documents = draft.documents.map((x, j) =>
                          j === i ? { ...x, mandatory: e.target.checked } : x,
                        );
                        setDraft({ ...draft, documents });
                      }}
                    />
                  </td>
                  <td>
                    <select
                      aria-label={t('m19p.tierOf', [i + 1])}
                      value={d.tier}
                      onChange={(e) => {
                        const documents = draft.documents.map((x, j) =>
                          j === i ? { ...x, tier: e.target.value as typeof d.tier } : x,
                        );
                        setDraft({ ...draft, documents });
                      }}
                    >
                      {TIERS.map((x) => (
                        <option key={x} value={x}>
                          {t(`m19p.tier.${x}`)}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td>
                    <button
                      type="button"
                      className="ia"
                      aria-label={t('m19p.removeOf', [i + 1])}
                      onClick={() =>
                        setDraft({ ...draft, documents: draft.documents.filter((_, j) => j !== i) })
                      }
                    >
                      {t('common.remove')}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <button
            type="button"
            className="btn sm"
            onClick={() =>
              setDraft({
                ...draft,
                documents: [
                  ...draft.documents,
                  { documentType: '', mandatory: true, tier: 'UNIT_CPF_ADMIN' },
                ],
              })
            }
          >
            {t('m19p.addDocument')}
          </button>
          <div className="rf-field span2">
            <label htmlFor="m19p-reason">{t('common.reason')}</label>
            <TextAreaField
              id="m19p-reason"
              maxLength={500}
              value={draft.reason}
              onChange={(e) => setDraft({ ...draft, reason: e.target.value })}
            />
          </div>
          {problems.length > 0 && (
            <ul className="bd fl-list" aria-label={t('m19p.problems')}>
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
              disabled={problems.length > 0 || propose.isPending}
              onClick={() => void submit(draft)}
            >
              {t('common.submitForApproval')}
            </button>
          </div>
        </div>
      )}
    </Card>
  );
}
