'use client';

import { useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { can, hqOnlyBlock, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import { Card, date, Empty, Problem, ServerPagination, Src, useToast, TextAreaField } from '@/shared/ui';
import type { SectionProposal, SectionVersion } from '../api/taxApi';
import { REGIMES, sectionProblems } from '../business/taxConfigRules';
import { taxKeys, taxPages, usePendingSections, useProposeSection } from '../orchestration/taxQueries';

type Draft = SectionProposal & { reason: string; after?: string };

function fresh(today: string): Draft {
  return { code: '', description: '', limit: null, regime: 'OLD', effectiveFrom: today, reason: '' };
}

function nextVersion(s: SectionVersion): Draft {
  return {
    code: s.code,
    description: s.description,
    limit: s.limit === null ? null : String(s.limit),
    regime: s.regime,
    effectiveFrom: '',
    reason: '',
    after: s.effectiveFrom,
  };
}

/** 150000.5 -> "₹1,50,000.50" (Indian grouping). */
function rupees(limit: number): string {
  return `₹${limit.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

/**
 * M-18 card: the income-tax sections and their limits (SRS-7-2, SRS-7-20; BR-TAX-002; plan 08 D-2).
 * The Rule Administrator at Headquarters proposes a version of a section from a later date; a
 * different Rule Approver approves it (route M18_SECTION); the version in force then ends the day
 * before. A section without a limit has none: no value is assumed (OQ-4).
 */
export function SectionMaster() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const pending = usePendingSections();
  const propose = useProposeSection();
  const mayCreate = can(me.data, 'M-18', 'CREATE');
  const hqBlock = hqOnlyBlock(me.data, 'M-18');
  const [draft, setDraft] = useState<Draft | null>(null);
  const [error, setError] = useState<unknown>(null);
  // An answer without a list (an older server) shows nothing pending, never a crash.
  const waiting = Array.isArray(pending.data) ? pending.data : [];
  const pendingCodes = new Set(waiting.map((p) => p.proposal.code));
  /** Why the row's action is disabled, or nothing. */
  function blockedBecause(code: string): string | undefined {
    if (hqBlock) return t(hqBlock);
    if (pendingCodes.has(code)) return t('m18s.pendingOne');
    return undefined;
  }
  const problems = draft ? sectionProblems(draft, draft.after) : [];

  function open(next: Draft) {
    setError(null);
    setDraft(next);
  }

  async function submit(form: Draft) {
    setError(null);
    try {
      await propose.mutateAsync({
        code: form.code,
        description: form.description.trim(),
        limit: form.limit === null || form.limit === '' ? null : form.limit,
        regime: form.regime,
        effectiveFrom: form.effectiveFrom,
        reason: form.reason.trim(),
      });
      toast(t('m18s.submitted', [form.code]));
      setDraft(null);
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  return (
    <Card title={t('m18s.title')}>
      <p className="sub2">{t('m18s.hint')}</p>
      {mayCreate && (
        <div className="toolbar">
          <button
            type="button"
            className="btn"
            disabled={hqBlock !== null || draft !== null}
            title={hqBlock ? t(hqBlock) : undefined}
            onClick={() => open(fresh(businessToday()))}
          >
            {t('action.newSection')}
          </button>
        </div>
      )}
      <Problem error={pending.error ?? (draft ? null : error)} />
      <ServerPagination
        queryKey={taxKeys.sections}
        fetchPage={taxPages.sections}
        personal={false}
        empty={<Empty text={t('m18s.none')} />}
      >
        {(rows) => (
          <table className="t" aria-label={t('m18s.title')}>
            <thead>
              <tr>
                <th>
                  {t('m18s.section')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('m18s.limit')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('m18s.regime')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('m18s.period')}
                  <Src s="ENT" />
                </th>
                <th aria-label={t('common.actions')} />
              </tr>
            </thead>
            <tbody>
              {rows.map((s) => (
                <tr key={s.id} data-reference={`${s.code}|${s.effectiveFrom}`}>
                  <td>
                    <b>{s.code}</b>
                    <div className="sub2">{s.description}</div>
                  </td>
                  <td>
                    {s.limit === null ? <span className="sub2">{t('m18s.noLimit')}</span> : rupees(s.limit)}
                  </td>
                  <td>{t(`m18s.regime.${s.regime}`)}</td>
                  <td>
                    {date(s.effectiveFrom)} – {s.effectiveTo ? date(s.effectiveTo) : t('mst.open')}
                    {s.inForce && (
                      <div>
                        <span className="pill p-ok">{t('m19p.inForce')}</span>
                      </div>
                    )}
                  </td>
                  <td>
                    <div className="acts">
                      <HistoryButton entity="it_section_master" recordKey={s.code} />
                      {mayCreate && s.effectiveTo === null && (
                        <button
                          type="button"
                          className="ia"
                          aria-label={t('m18s.newVersionOf', [s.code])}
                          disabled={hqBlock !== null || draft !== null || pendingCodes.has(s.code)}
                          title={blockedBecause(s.code)}
                          onClick={() => open(nextVersion(s))}
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
        )}
      </ServerPagination>
      {waiting.length > 0 && (
        <ul className="bd fl-list" aria-label={t('m18s.pendingTitle')}>
          {waiting.map((p) => (
            <li key={p.approvalId}>
              <span className="pill p-warn">{t('status.PENDING_APPROVAL')}</span>{' '}
              {t('m18s.pendingLine', [
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
          <h3>{t('m18s.formTitle', [draft.code || '…'])}</h3>
          <Problem error={error} />
          <div className="rf-grid">
            <div className="rf-field">
              <label htmlFor="m18s-code">{t('m18s.code')}</label>
              <input
                id="m18s-code"
                maxLength={20}
                value={draft.code}
                readOnly={draft.after !== undefined}
                onChange={(e) => setDraft({ ...draft, code: e.target.value.trim() })}
              />
            </div>
            <div className="rf-field span2">
              <label htmlFor="m18s-description">{t('m18s.description')}</label>
              <TextAreaField
                id="m18s-description"
                maxLength={500}
                value={draft.description}
                onChange={(e) => setDraft({ ...draft, description: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="m18s-limit">{t('m18s.limit')}</label>
              <input
                id="m18s-limit"
                inputMode="decimal"
                maxLength={13}
                placeholder={t('m18s.noLimit')}
                value={draft.limit ?? ''}
                onChange={(e) => setDraft({ ...draft, limit: e.target.value === '' ? null : e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="m18s-regime">{t('m18s.regime')}</label>
              <select
                id="m18s-regime"
                value={draft.regime}
                onChange={(e) => setDraft({ ...draft, regime: e.target.value as Draft['regime'] })}
              >
                {REGIMES.map((r) => (
                  <option key={r} value={r}>
                    {t(`m18s.regime.${r}`)}
                  </option>
                ))}
              </select>
            </div>
            <div className="rf-field">
              <label htmlFor="m18s-from">{t('mst.from')}</label>
              <input
                id="m18s-from"
                type="date"
                value={draft.effectiveFrom}
                onChange={(e) => setDraft({ ...draft, effectiveFrom: e.target.value })}
              />
            </div>
            <div className="rf-field span2">
              <label htmlFor="m18s-reason">{t('common.reason')}</label>
              <TextAreaField
                id="m18s-reason"
                maxLength={500}
                value={draft.reason}
                onChange={(e) => setDraft({ ...draft, reason: e.target.value })}
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
