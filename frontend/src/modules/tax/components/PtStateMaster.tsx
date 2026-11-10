'use client';

import { useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { can, hqOnlyBlock, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import { Card, date, Empty, Problem, ServerPagination, Src, useToast, TextAreaField } from '@/shared/ui';
import type { PtState, StateProposal } from '../api/taxApi';
import { stateProblems } from '../business/taxConfigRules';
import { taxKeys, taxPages, usePendingStates, useProposeState } from '../orchestration/taxQueries';

type Draft = StateProposal & { reason: string; edit: boolean };

function fresh(today: string): Draft {
  return {
    code: '',
    name: '',
    ptApplicable: true,
    registrationNumber: null,
    returnPeriodicity: null,
    effectiveFrom: today,
    effectiveTo: null,
    reason: '',
    edit: false,
  };
}

function change(s: PtState): Draft {
  return {
    code: s.code,
    name: s.name,
    ptApplicable: s.ptApplicable,
    registrationNumber: s.registrationNumber,
    returnPeriodicity: s.returnPeriodicity,
    effectiveFrom: s.effectiveFrom,
    effectiveTo: s.effectiveTo,
    reason: '',
    edit: true,
  };
}

const orNull = (v: string | null) => (v === null || v.trim() === '' ? null : v.trim());

/**
 * M-18 card: the states for Professional Tax (SRS-7-4, BR-TAX-006; plan 08 D-2, D-7). The Rule
 * Administrator at Headquarters adds a state or changes one; a different Rule Approver approves it
 * (route M18_PT_STATE). A state where PT applies is computed on the PT.SLAB rule's band set of its
 * code (set by the system, read-only here); the slabs themselves are the rule versions above, not
 * configured until approved.
 */
export function PtStateMaster() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const pending = usePendingStates();
  const propose = useProposeState();
  const mayCreate = can(me.data, 'M-18', 'CREATE');
  const mayEdit = can(me.data, 'M-18', 'EDIT');
  const hqBlock = hqOnlyBlock(me.data, 'M-18');
  const [draft, setDraft] = useState<Draft | null>(null);
  const [error, setError] = useState<unknown>(null);
  // An answer without a list (an older server) shows nothing pending, never a crash.
  const waiting = Array.isArray(pending.data) ? pending.data : [];
  const pendingCodes = new Set(waiting.map((p) => p.proposal.code));
  /** Why the row's action is disabled, or nothing. */
  function blockedBecause(code: string): string | undefined {
    if (hqBlock) return t(hqBlock);
    if (pendingCodes.has(code)) return t('m18p.pendingOne');
    return undefined;
  }
  const problems = draft ? stateProblems(draft) : [];

  function open(next: Draft) {
    setError(null);
    setDraft(next);
  }

  async function submit(form: Draft) {
    setError(null);
    try {
      await propose.mutateAsync({
        ...form,
        name: form.name.trim(),
        registrationNumber: orNull(form.registrationNumber),
        returnPeriodicity: orNull(form.returnPeriodicity),
        effectiveTo: orNull(form.effectiveTo),
        reason: form.reason.trim(),
      });
      toast(t('m18p.submitted', [form.code]));
      setDraft(null);
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  return (
    <Card title={t('m18p.title')}>
      <p className="sub2">{t('m18p.hint')}</p>
      {mayCreate && (
        <div className="toolbar">
          <button
            type="button"
            className="btn"
            disabled={hqBlock !== null || draft !== null}
            title={hqBlock ? t(hqBlock) : undefined}
            onClick={() => open(fresh(businessToday()))}
          >
            {t('action.newState')}
          </button>
        </div>
      )}
      <Problem error={pending.error ?? (draft ? null : error)} />
      <ServerPagination
        queryKey={taxKeys.states}
        fetchPage={taxPages.states}
        personal={false}
        empty={<Empty text={t('m18p.none')} />}
      >
        {(rows) => (
          <table className="t" aria-label={t('m18p.title')}>
            <thead>
              <tr>
                <th>
                  {t('m18p.state')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('m18p.applicable')}
                  <Src s="ENT" />
                </th>
                <th>
                  {t('m18p.slab')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('m18p.registration')}
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
                <tr key={s.id} data-reference={s.code}>
                  <td>
                    <b>{s.name}</b>
                    <div className="sub2">{s.code}</div>
                  </td>
                  <td>
                    {s.ptApplicable ? (
                      <span className="pill p-ok">{t('m18p.yes')}</span>
                    ) : (
                      <span className="pill">{t('m18p.no')}</span>
                    )}
                  </td>
                  <td>{s.slabRuleKey ?? <span className="sub2">—</span>}</td>
                  <td>
                    {s.registrationNumber ?? <span className="sub2">—</span>}
                    {s.returnPeriodicity && <div className="sub2">{s.returnPeriodicity}</div>}
                  </td>
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
                      <HistoryButton entity="pt_state_master" recordKey={s.code} />
                      {mayEdit && (
                        <button
                          type="button"
                          className="ia"
                          aria-label={t('m18p.changeOf', [s.code])}
                          disabled={hqBlock !== null || draft !== null || pendingCodes.has(s.code)}
                          title={blockedBecause(s.code)}
                          onClick={() => open(change(s))}
                        >
                          {t('action.editParameter')}
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
        <ul className="bd fl-list" aria-label={t('m18p.pendingTitle')}>
          {waiting.map((p) => (
            <li key={p.approvalId}>
              <span className="pill p-warn">{t('status.PENDING_APPROVAL')}</span>{' '}
              {t('m18p.pendingLine', [p.proposal.code, p.proposal.name, p.proposal.proposedBy])}
            </li>
          ))}
        </ul>
      )}
      {draft && (
        <div className="bd">
          <h3>{t('m18p.formTitle', [draft.code || '…'])}</h3>
          <Problem error={error} />
          <div className="rf-grid">
            <div className="rf-field">
              <label htmlFor="m18p-code">{t('m18p.code')}</label>
              <input
                id="m18p-code"
                maxLength={10}
                value={draft.code}
                readOnly={draft.edit}
                onChange={(e) => setDraft({ ...draft, code: e.target.value.trim().toUpperCase() })}
              />
            </div>
            <div className="rf-field span2">
              <label htmlFor="m18p-name">{t('m18p.name')}</label>
              <input
                id="m18p-name"
                maxLength={100}
                value={draft.name}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="m18p-applicable">
                <input
                  id="m18p-applicable"
                  type="checkbox"
                  checked={draft.ptApplicable}
                  onChange={(e) => setDraft({ ...draft, ptApplicable: e.target.checked })}
                />{' '}
                {t('m18p.applicable')}
              </label>
            </div>
            <div className="rf-field">
              <label htmlFor="m18p-registration">{t('m18p.registration')}</label>
              <input
                id="m18p-registration"
                maxLength={50}
                value={draft.registrationNumber ?? ''}
                onChange={(e) => setDraft({ ...draft, registrationNumber: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="m18p-periodicity">{t('m18p.periodicity')}</label>
              <input
                id="m18p-periodicity"
                maxLength={20}
                value={draft.returnPeriodicity ?? ''}
                onChange={(e) => setDraft({ ...draft, returnPeriodicity: e.target.value.toUpperCase() })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="m18p-from">{t('mst.from')}</label>
              <input
                id="m18p-from"
                type="date"
                value={draft.effectiveFrom}
                onChange={(e) => setDraft({ ...draft, effectiveFrom: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="m18p-to">{t('mst.to')}</label>
              <input
                id="m18p-to"
                type="date"
                value={draft.effectiveTo ?? ''}
                onChange={(e) =>
                  setDraft({ ...draft, effectiveTo: e.target.value === '' ? null : e.target.value })
                }
              />
            </div>
            <div className="rf-field span2">
              <label htmlFor="m18p-reason">{t('common.reason')}</label>
              <TextAreaField
                id="m18p-reason"
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
