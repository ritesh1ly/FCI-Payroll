'use client';

import { Fragment, type ReactNode, useEffect, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import { Card, Empty, Problem, ServerPagination, Src, date, useToast, TextAreaField } from '@/shared/ui';
import { type DeptRateDraft, deptRateBlocked, toDeptRateRequest } from '../business/labourRules';
import { rateSetsPage, useProposeRateSet, useRateDesignations } from '../orchestration/labourQueries';

/**
 * M-05 Labour Rate Tables ▸ Departmental Labour (SRS-3-13; US-GAP-61): the designation-wise Basic
 * wage and DA as dated rate sets. Only Headquarters sets labour rates: the Rule Administrator
 * proposes a set dated after the latest one, every designation's Basic wage and DA mandatory; the
 * Rule Approver approves it in the Approvals Inbox and the set before ends the day before. No set is
 * seeded: the values come from FCI (OQ-80).
 */
export function DeptRates({ typeSelect, startNew = false }: { typeSelect: ReactNode; startNew?: boolean }) {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const today = businessToday();
  const designations = useRateDesignations('DEPT_LABOUR');
  const propose = useProposeRateSet();
  const [draft, setDraft] = useState<DeptRateDraft | null>(null);
  const [error, setError] = useState<unknown>(null);
  const mayPropose = can(me.data, 'M-05', 'CREATE');
  const list = designations.data ?? [];
  const why = draft ? deptRateBlocked(draft, list) : null;

  async function send(d: DeptRateDraft) {
    setError(null);
    try {
      await propose.mutateAsync(toDeptRateRequest(d, list));
      toast(t('m05.proposed'));
      setDraft(null);
    } catch (cause) {
      setError(cause);
    }
  }

  useEffect(() => {
    if (startNew) setDraft({ effectiveFrom: today, basic: {}, da: {}, reason: '' });
    // Once, when the drawn New Rate Set mounts the register.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const rupees = (v: number | null) => (v === null ? '—' : `₹ ${v}`);

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
            onClick={() => {
              setError(null);
              setDraft({ effectiveFrom: today, basic: {}, da: {}, reason: '' });
            }}
          >
            {t('action.m05.propose')}
          </button>
        </div>
        <ServerPagination
          queryKey={['labour-rate-sets', 'DEPT_LABOUR']}
          fetchPage={rateSetsPage('DEPT_LABOUR')}
          empty={<Empty text={t('m05.deptNone')} />}
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
                    {t('m05.basicWage')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m05.da')}
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
                          {d.designation}: {rupees(d.basicWage)}
                        </div>
                      ))}
                    </td>
                    <td>
                      {r.rates.map((d) => (
                        <div key={d.designationId}>
                          {d.designation}: {rupees(d.da)}
                        </div>
                      ))}
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
        <Card title={t('m05.new', [t('labour.type.DEPT_LABOUR')])}>
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
              <Fragment key={x.id}>
                <label>
                  {t('m05.basicWageOf', [`${x.code} - ${x.name}`])}
                  <Src s="ENT" />
                  <input
                    maxLength={13}
                    inputMode="decimal"
                    value={draft.basic[x.id] ?? ''}
                    onChange={(e) =>
                      setDraft({ ...draft, basic: { ...draft.basic, [x.id]: e.target.value } })
                    }
                  />
                </label>
                <label>
                  {t('m05.daOf', [`${x.code} - ${x.name}`])}
                  <Src s="ENT" />
                  <input
                    maxLength={13}
                    inputMode="decimal"
                    value={draft.da[x.id] ?? ''}
                    onChange={(e) => setDraft({ ...draft, da: { ...draft.da, [x.id]: e.target.value } })}
                  />
                </label>
              </Fragment>
            ))}
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
          <p className="sub2">{t('m05.deptNote')}</p>
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
