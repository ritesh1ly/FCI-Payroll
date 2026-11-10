'use client';

import { useEffect, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import { Card, Empty, Problem, ServerPagination, Src, date, useToast, TextAreaField } from '@/shared/ui';
import {
  GRAINS,
  RATE_PAYROLL_TYPES,
  type RateDraft,
  rateBlocked,
  toRateRequest,
} from '../business/labourRules';
import { rateSetsPage, useProposeRateSet, useRateDesignations } from '../orchestration/labourQueries';
import { DeptRates } from './DeptRates';
import { type NwnpPrefill, NwnpRates } from './NwnpRates';

export type { NwnpPrefill };

/**
 * M-05 Labour Rate Tables (plan 21 phase 21c; SRS-4-11, SRS-4-2, SRS-3-13; D21-4): the payroll
 * type's effective-dated sets of designation-wise Minimum Guaranteed Wage day rates with the
 * Interim Relief, the GoI rate and its source and the comparison grain. Only Headquarters sets
 * labour rates: the Rule Administrator proposes a set dated after the latest one, every
 * designation's rate mandatory; the Rule Approver approves it in the Approvals Inbox and the set
 * before ends the day before. The same screen for every labour payroll (DPS first).
 */
export function LabourRates({
  payrollType: initialType,
  startNew = false,
  prefill = null,
}: {
  /** The engine of the view (the sidebar's entry). */
  payrollType?: string;
  /** The drawn New Rate Set: the set's entry opens at once. */
  startNew?: boolean;
  /** The values of the drawn NWNP ASOR form. */
  prefill?: NwnpPrefill | null;
} = {}) {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const today = businessToday();
  const [payrollType, setPayrollType] = useState<string>(initialType ?? RATE_PAYROLL_TYPES[0]);
  const nwnp = payrollType === 'NWNP_LABOUR';
  const dept = payrollType === 'DEPT_LABOUR';
  const designations = useRateDesignations(payrollType, !nwnp && !dept);
  const propose = useProposeRateSet();
  const [draft, setDraft] = useState<RateDraft | null>(null);
  const [error, setError] = useState<unknown>(null);
  const mayPropose = can(me.data, 'M-05', 'CREATE');
  const list = designations.data ?? [];
  const why = draft ? rateBlocked(draft, list) : null;

  function start() {
    setError(null);
    setDraft({
      effectiveFrom: today,
      mgw: {},
      interimRelief: '',
      goiRate: '',
      goiRateSource: '',
      comparisonGrain: '',
      reason: '',
    });
  }

  async function send(draft: RateDraft) {
    setError(null);
    try {
      await propose.mutateAsync(toRateRequest(draft, payrollType, list));
      toast(t('m05.proposed'));
      setDraft(null);
    } catch (cause) {
      setError(cause);
    }
  }

  useEffect(() => {
    if (startNew && !nwnp && !dept) start();
    // Once, when the drawn New Rate Set mounts the register.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const money = (v: number | null) => (v === null ? t('m05.notConfigured') : `₹ ${v}`);

  const typeSelect = (
    <label>
      {t('m02.payrollType')}{' '}
      <select
        value={payrollType}
        onChange={(e) => {
          setPayrollType(e.target.value);
          setDraft(null);
        }}
      >
        {RATE_PAYROLL_TYPES.map((p) => (
          <option key={p} value={p}>
            {t(`labour.type.${p}`)}
          </option>
        ))}
      </select>
    </label>
  );
  // SRS-5-2 / SRS-5-3: the NWNP rates are depot-wise SOR / ASOR sets with piece rates (one screen, M-05).
  if (nwnp) return <NwnpRates typeSelect={typeSelect} prefill={prefill} />;
  // SRS-3-13: the Departmental Basic wage and DA sets (US-GAP-61).
  if (dept) return <DeptRates typeSelect={typeSelect} startNew={startNew} />;

  return (
    <div className="wireframe-body-container">
      <Card title={t('screen.M-05')}>
        <div className="toolbar">
          {typeSelect}
          <button
            type="button"
            className="btn primary"
            disabled={!mayPropose}
            title={mayPropose ? undefined : t('reason.ruleAdminOnly')}
            onClick={start}
          >
            {t('action.m05.propose')}
          </button>
        </div>
        <ServerPagination
          queryKey={['labour-rate-sets', payrollType]}
          fetchPage={rateSetsPage(payrollType)}
          empty={<Empty text={t('m05.none')} />}
        >
          {(rows) => (
            <table className="t" aria-label={t('screen.M-05')}>
              <thead>
                <tr>
                  <th>
                    {t('m06.period')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m05.mgw')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m05.interimRelief')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m05.goiRate')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m05.grain')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m02.status')}
                    <Src s="SYS" />
                  </th>
                  <th aria-label={t('common.actions')} />
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id}>
                    <td>
                      {date(r.effectiveFrom)} - {r.effectiveTo ? date(r.effectiveTo) : t('m12.open')}
                      <div className="sub2">
                        {t('m06.version', [r.versionNo])} · {r.depot ?? t('m05.allDepots')}
                      </div>
                    </td>
                    <td>
                      {r.rates.map((d) => (
                        <div key={d.designationId}>
                          {d.designation}: ₹ {d.mgw}
                        </div>
                      ))}
                    </td>
                    <td>{money(r.interimRelief)}</td>
                    <td>
                      {money(r.goiRate)}
                      {/* SRS-4-11 (l.4735): the GoI rate's effective date is the set's Effective From (no DDL column). */}
                      {r.goiRate !== null && (
                        <div className="sub2" data-testid="goi-effective">
                          {t('m05.goiEffective', [date(r.effectiveFrom)])}
                        </div>
                      )}
                      {r.goiRateSource && <div className="sub2">{r.goiRateSource}</div>}
                    </td>
                    <td>
                      {r.comparisonGrain ? t(`m05.grain.${r.comparisonGrain}`) : t('m05.notConfigured')}
                    </td>
                    <td>{t(`m12.state.${r.approvalState}`)}</td>
                    <td>
                      <HistoryButton
                        entity="labour_min_wage"
                        recordKey={r.depotId === null ? r.payrollType : `${r.payrollType}@${r.depotId}`}
                        label={r.depot ?? t('m05.allDepots')}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </ServerPagination>
      </Card>
      {draft && (
        <Card title={t('m05.new', [t(`labour.type.${payrollType}`)])}>
          <div className="form-grid">
            <label>
              {t('m12.effective')}
              <Src s="ENT" />
              <input
                type="date"
                value={draft.effectiveFrom}
                onChange={(e) => setDraft({ ...draft, effectiveFrom: e.target.value })}
              />
            </label>
            {list.map((x) => (
              <label key={x.id}>
                {t('m05.mgwOf', [`${x.code} - ${x.name}`])}
                <Src s="ENT" />
                <input
                  maxLength={13}
                  inputMode="decimal"
                  value={draft.mgw[x.id] ?? ''}
                  onChange={(e) => setDraft({ ...draft, mgw: { ...draft.mgw, [x.id]: e.target.value } })}
                />
              </label>
            ))}
            <label>
              {t('m05.interimRelief')}
              <Src s="ENT" />
              <input
                maxLength={13}
                inputMode="decimal"
                value={draft.interimRelief}
                onChange={(e) => setDraft({ ...draft, interimRelief: e.target.value })}
              />
            </label>
            <label>
              {t('m05.goiRate')}
              <Src s="ENT" />
              <input
                maxLength={13}
                inputMode="decimal"
                value={draft.goiRate}
                onChange={(e) => setDraft({ ...draft, goiRate: e.target.value })}
              />
            </label>
            <label>
              {t('m05.goiRateSource')}
              <Src s="ENT" />
              <TextAreaField
                maxLength={200}
                value={draft.goiRateSource}
                onChange={(e) => setDraft({ ...draft, goiRateSource: e.target.value })}
              />
            </label>
            <label>
              {t('m05.grain')}
              <Src s="ENT" />
              <select
                value={draft.comparisonGrain}
                onChange={(e) => setDraft({ ...draft, comparisonGrain: e.target.value })}
              >
                <option value="">{t('m05.notConfigured')}</option>
                {GRAINS.map((g) => (
                  <option key={g} value={g}>
                    {t(`m05.grain.${g}`)}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t('m02.reason')}
              <Src s="ENT" />
              <TextAreaField
                maxLength={500}
                value={draft.reason}
                onChange={(e) => setDraft({ ...draft, reason: e.target.value })}
              />
            </label>
          </div>
          <p className="sub2">{t('m05.settingsNote')}</p>
          <div className="toolbar">
            <button
              type="button"
              className="btn primary"
              disabled={why !== null || propose.isPending}
              title={why ? t(why) : undefined}
              onClick={() => void send(draft)}
            >
              {t('action.m05.send')}
            </button>
            <button type="button" className="btn" onClick={() => setDraft(null)}>
              {t('common.cancel')}
            </button>
          </div>
          {why && <p className="sub2">{t(why)}</p>}
        </Card>
      )}
      <Problem error={error ?? designations.error} />
    </div>
  );
}
