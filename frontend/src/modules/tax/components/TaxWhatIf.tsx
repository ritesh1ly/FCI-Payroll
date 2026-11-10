'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { Card, Problem, Src } from '@/shared/ui';
import type { Forecast, WhatIfResult } from '../api/statutoryApi';
import { reasonOf, type WhatIfDraft, whatIfProblems, whatIfRequest } from '../business/statutoryRules';
import { useWhatIf } from '../orchestration/statutoryQueries';
import { rupees } from './money';

const EMPTY: WhatIfDraft = {
  regime: '',
  claims: [{ sectionCode: '', amount: '' }],
  salaryChange: '',
  otherIncome: '',
};

/**
 * The what-if Income Tax Forecast (SRS-7-28): the regime, a declaration or the income varied and the
 * year computed again beside the actual forecast. A General (Simulation) Run: an amber banner and
 * the SIMULATION watermark as on C-09; the run is recorded, the result is shown once and changes
 * no TDS, payslip or posted figure. {@code eis} null: the employee's own year (E-01).
 */
export function TaxWhatIf({ eis, finYear }: { eis: string | null; finYear: string }) {
  const { t } = useI18n();
  const [draft, setDraft] = useState<WhatIfDraft>(EMPTY);
  const run = useWhatIf(eis, finYear);
  const problems = whatIfProblems(draft);
  const set = (patch: Partial<WhatIfDraft>) => setDraft({ ...draft, ...patch });
  const claim = (i: number, patch: Partial<WhatIfDraft['claims'][number]>) =>
    set({ claims: draft.claims.map((c, k) => (k === i ? { ...c, ...patch } : c)) });
  return (
    <Card title={t('c07w.title')}>
      <p className="sim-banner" role="note">
        {t('c07w.banner')}
      </p>
      <form
        className="fl-grid"
        onSubmit={(e) => {
          e.preventDefault();
          if (problems.length === 0) run.mutate(whatIfRequest(draft));
        }}
      >
        <label>
          {t('e01t.regime')}
          <select
            value={draft.regime}
            onChange={(e) => set({ regime: e.target.value as WhatIfDraft['regime'] })}
          >
            <option value="">{t('c07w.asApproved')}</option>
            <option value="OLD">{t('e01t.regime.OLD')}</option>
            <option value="NEW">{t('e01t.regime.NEW')}</option>
          </select>
        </label>
        {draft.claims.map((c, i) => (
          // The rows are positional: a row is only ever added at the end.
          <div key={i} className="toolbar">
            <label>
              {t('m18s.section')}{' '}
              <input
                maxLength={20}
                value={c.sectionCode}
                onChange={(e) => claim(i, { sectionCode: e.target.value })}
              />
            </label>
            <label>
              {t('c07.claimed')}{' '}
              <input maxLength={13} value={c.amount} onChange={(e) => claim(i, { amount: e.target.value })} />
            </label>
          </div>
        ))}
        <button
          type="button"
          className="btn"
          onClick={() => set({ claims: [...draft.claims, { sectionCode: '', amount: '' }] })}
        >
          {t('c07w.addClaim')}
        </button>
        <label>
          {t('c07w.salaryChange')}{' '}
          <input
            maxLength={14}
            value={draft.salaryChange}
            onChange={(e) => set({ salaryChange: e.target.value })}
          />
        </label>
        <label>
          {t('c07w.otherIncome')}{' '}
          <input
            maxLength={13}
            value={draft.otherIncome}
            onChange={(e) => set({ otherIncome: e.target.value })}
          />
        </label>
        {problems.length > 0 && (
          <ul className="fl-list" aria-label={t('c07w.problems')}>
            {problems.map((p) => (
              <li key={p} className="pill p-warn">
                {t(p)}
              </li>
            ))}
          </ul>
        )}
        <div className="rf-actbar">
          <button
            type="submit"
            className="btn primary"
            disabled={problems.length > 0 || run.isPending}
            title={problems.length > 0 ? t(problems[0] ?? '') : undefined}
          >
            {t('c07w.run')}
          </button>
        </div>
      </form>
      {run.error && <Problem error={run.error} />}
      {run.data && <Outcome result={run.data} />}
    </Card>
  );
}

/** The actual and the what-if forecast side by side, under the SIMULATION watermark. */
function Outcome({ result }: { result: WhatIfResult }) {
  const { t } = useI18n();
  const rows: [string, (f: Forecast) => number | null | undefined][] = [
    ['c07.taxableIncome', (f) => f.result?.taxableIncome],
    ['c07.annualTax', (f) => f.result?.annualTax],
    ['c07.monthlyTds', (f) => f.result?.monthlyTds],
  ];
  return (
    <div className="sim-output" data-watermark={t('c09.watermark')}>
      <p className="sub2">{t('c07w.recorded', [result.runId])}</p>
      <table className="t" aria-label={t('c07w.outcome')}>
        <thead>
          <tr>
            <th>
              {t('c07w.item')}
              <Src s="SYS" />
            </th>
            <th>
              {t('c07w.actual')}
              <Src s="CALC" />
            </th>
            <th>
              {t('c07w.whatIf')}
              <Src s="CALC" />
            </th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>{t('e01t.regime')}</td>
            <td>{regimeText(t, result.actual)}</td>
            <td>{regimeText(t, result.whatIf)}</td>
          </tr>
          {rows.map(([key, value]) => (
            <tr key={key}>
              <td>{t(key)}</td>
              <td>{rupees(value(result.actual))}</td>
              <td>{rupees(value(result.whatIf))}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {result.whatIf.reasons.length > 0 && (
        <ul className="fl-list" aria-label={t('c07.notComputed')}>
          {result.whatIf.reasons.map((r) => {
            const { key, args } = reasonOf(r);
            return (
              <li key={r}>
                <span className="pill p-warn">{t('c07.notComputed')}</span> {t(key, args)}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

function regimeText(t: (key: string) => string, f: Forecast): string {
  return f.inputs.regime ? t(`e01t.regime.${f.inputs.regime}`) : t('c07.noRegime');
}
