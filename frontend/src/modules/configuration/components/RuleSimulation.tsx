'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { Card, dateTime, Empty, Problem, ServerPagination, Src, useToast } from '@/shared/ui';
import type { RuleSimulation as Simulation } from '../api/rulesApi';
import { PAYROLL_TYPES } from '../business/componentRules';
import { ruleKeys, rulePages, useSimulateRule, useSimulationOffices } from '../orchestration/ruleQueries';

/** A value text of the server, or its reason when there is none. */
function valueText(t: (k: string) => string, v: string) {
  return v === 'NOT_CONFIGURED' || v === 'NOT_APPLICABLE' ? t(`m08.sim.${v}`) : v;
}

/**
 * M-08 impact simulation (plan 06 phase 06e; SRS-19-4): the version of a rule awaiting approval is
 * tried on a sample of the members a payroll run would read, by payroll type and office, before the
 * Rule Approver approves it. Each run is kept with the input hash; a run whose inputs have changed
 * since says so.
 */
export function RuleSimulationPanel({
  ruleKey,
  mayRun,
  onClose,
}: {
  ruleKey: string;
  mayRun: boolean;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const simulate = useSimulateRule();
  const offices = useSimulationOffices(mayRun);
  const [payrollType, setPayrollType] = useState('');
  const [officeId, setOfficeId] = useState('');
  const [error, setError] = useState<unknown>(null);
  async function run() {
    setError(null);
    try {
      await simulate.mutateAsync({
        key: ruleKey,
        scope: { payrollType: payrollType || null, officeId: officeId ? Number(officeId) : null },
      });
      toast(t('m08.sim.done'));
    } catch (cause) {
      setError(cause);
    }
  }
  return (
    <Card title={t('m08.sim.title', [ruleKey])}>
      <p className="sub2">{t('m08.sim.hint')}</p>
      {mayRun && (
        <div className="toolbar" aria-label={t('m08.sim.scope')}>
          <label>
            {t('m08.sim.payrollType')}{' '}
            <select value={payrollType} onChange={(e) => setPayrollType(e.target.value)}>
              <option value="">{t('m08.sim.every')}</option>
              {PAYROLL_TYPES.map((p) => (
                <option key={p} value={p}>
                  {t(`m07.payrollType.${p}`)}
                </option>
              ))}
            </select>
          </label>
          <label>
            {t('m08.sim.office')}{' '}
            <select value={officeId} onChange={(e) => setOfficeId(e.target.value)}>
              <option value="">{t('m08.sim.every')}</option>
              {(offices.data ?? []).map((o) => (
                <option key={o.id} value={o.id}>
                  {o.code} · {o.name}
                </option>
              ))}
            </select>
          </label>
          <button type="button" className="btn pri" disabled={simulate.isPending} onClick={() => void run()}>
            {t('m08.sim.run')}
          </button>
        </div>
      )}
      <Problem error={error ?? offices.error} />
      <ServerPagination
        queryKey={ruleKeys.simulations(ruleKey)}
        fetchPage={rulePages.simulations(ruleKey)}
        personal={false}
        empty={<Empty text={t('m08.sim.none')} />}
      >
        {(rows) => (
          <div>
            {rows.map((s) => (
              <Run key={s.id} s={s} />
            ))}
          </div>
        )}
      </ServerPagination>
      <div className="rf-actbar">
        <button type="button" className="btn" onClick={onClose}>
          {t('common.close')}
        </button>
      </div>
    </Card>
  );
}

function Run({ s }: { s: Simulation }) {
  const { t } = useI18n();
  return (
    <section aria-label={t('m08.sim.run.label', [s.id])} className="card">
      <p>
        <b>{t('m08.sim.run.label', [s.id])}</b> · {s.requestedBy} · {dateTime(s.requestedAt)} ·{' '}
        {s.payrollType ? t(`m07.payrollType.${s.payrollType}`) : t('m08.sim.every')}
        {s.inputsChanged && <span className="pill p-warn"> {t('m08.sim.stale')}</span>}
      </p>
      <p className="sub2">
        {t('m08.sim.summary', [s.population, s.impact.sampled, s.impact.inScope, s.impact.changed])}
      </p>
      {s.impact.groups.length > 0 && (
        <table className="t" aria-label={t('m08.sim.groups', [s.id])}>
          <thead>
            <tr>
              <th>
                {t('m08.sim.payrollType')}
                <Src s="SYS" />
              </th>
              <th>
                {t('m08.sim.office')}
                <Src s="SYS" />
              </th>
              <th>
                {t('m08.sim.members')}
                <Src s="CALC" />
              </th>
              <th>
                {t('m08.sim.current')}
                <Src s="RULE" />
              </th>
              <th>
                {t('m08.sim.proposed')}
                <Src s="RULE" />
              </th>
            </tr>
          </thead>
          <tbody>
            {s.impact.groups.map((g) => (
              <tr key={`${g.payrollType}|${g.officeId}`}>
                <td>{t(`m07.payrollType.${g.payrollType}`)}</td>
                <td>{g.officeName ?? g.officeId}</td>
                <td>{g.members}</td>
                <td className="fl-wrap-any">{valueText(t, g.current)}</td>
                <td className="fl-wrap-any">
                  {valueText(t, g.proposed)}
                  {g.changes && <span className="pill p-warn"> {t('m08.sim.changes')}</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
