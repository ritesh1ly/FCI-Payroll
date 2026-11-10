'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import { Card, Empty, Problem, Src, FinancialYearSelect } from '@/shared/ui';
import type { Forecast } from '../api/statutoryApi';
import { isFinYear, reasonOf } from '../business/statutoryRules';
import { forecastExportUrl, useForecast } from '../orchestration/statutoryQueries';
import { finYearOf } from './DeclarationReview';
import { rupees } from './money';
import { TaxWhatIf } from './TaxWhatIf';

/**
 * C-07 Statutory Computation (SRS-7-1 … SRS-7-4, SRS-7-12, SRS-7-13, SRS-7-19, SRS-7-26, SRS-7-27;
 * BR-TAX-001 … 006, BR-TAX-014). The Payroll Maker, Checker or Finance reads a member's year: the
 * approved inputs (regime, declarations at their verified amounts capped at the section limits,
 * external savings, other income, the transport rebate), the earnings payroll provides, the tax on
 * the year's rates, or the reasons it is not computed. Everything is read-only (CALC / SYS); the
 * Income Tax Forecast downloads as CSV or Excel.
 */
export function TaxComputation() {
  const { t } = useI18n();
  const [finYear, setFinYear] = useState(finYearOf(businessToday()));
  const [lookup, setLookup] = useState('');
  const [eis, setEis] = useState<string | null>(null);
  const yearOk = isFinYear(finYear);
  // SRS-7-28: the what-if is a simulation run (C-09 RUN).
  const mayWhatIf = can(useMe().data, 'C-09', 'RUN');
  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <label>
          {t('i07.finYear')} <FinancialYearSelect value={finYear} onChange={setFinYear} />
        </label>
        <label>
          {t('m01.eis')}{' '}
          <input maxLength={20} value={lookup} onChange={(e) => setLookup(e.target.value.trim())} />
        </label>
        <button
          type="button"
          className="btn"
          disabled={!yearOk || !/^[0-9A-Za-z]{1,20}$/.test(lookup)}
          title={yearOk ? undefined : t('i07.badYear')}
          onClick={() => setEis(lookup)}
        >
          {t('c07.compute')}
        </button>
        <span className="sub2">{t('c07.hint')}</span>
      </div>
      {eis === null ? (
        <Card title={t('screen.C-07')}>
          <Empty text={t('c07.chooseMember')} />
        </Card>
      ) : (
        <>
          <MemberForecast eis={eis} finYear={finYear} />
          {mayWhatIf && <TaxWhatIf key={`${eis}|${finYear}`} eis={eis} finYear={finYear} />}
        </>
      )}
    </div>
  );
}

function MemberForecast({ eis, finYear }: { eis: string; finYear: string }) {
  const { t } = useI18n();
  const me = useMe();
  const forecast = useForecast(eis, finYear);
  if (forecast.isPending) return <p className="fl-hint">{t('common.loading')}</p>;
  if (!forecast.data) return <Problem error={forecast.error} />;
  const f = forecast.data;
  const mayExport = can(me.data, 'C-07', 'EXPORT');
  return (
    <Card title={t('c07.memberOf', [f.eis, f.name, f.finYear])}>
      <p>
        <span className={`pill ${f.view === 'ACTUAL' ? 'p-ok' : 'p-info'}`}>{t(`c07.view.${f.view}`)}</span>{' '}
        <span className="sub2">{t(`c07.viewHint.${f.view}`)}</span>
        {f.employmentStatus && f.employmentStatus !== 'IN_SERVICE' && (
          <span className="pill p-warn"> {t(`c07.status.${f.employmentStatus}`)}</span>
        )}
      </p>
      {f.reasons.length > 0 && (
        <ul className="bd fl-list" aria-label={t('c07.notComputed')}>
          {f.reasons.map((r) => {
            const { key, args } = reasonOf(r);
            return (
              <li key={r}>
                <span className="pill p-warn">{t('c07.notComputed')}</span> {t(key, args)}
              </li>
            );
          })}
        </ul>
      )}
      <Inputs f={f} />
      <Earnings f={f} />
      {f.result && <Result f={f} />}
      {mayExport && (
        <div className="rf-actbar">
          <a className="btn" href={forecastExportUrl(f.eis, f.finYear, 'CSV')}>
            {t('c07.exportCsv')}
          </a>
          <a className="btn" href={forecastExportUrl(f.eis, f.finYear, 'XLSX')}>
            {t('c07.exportXlsx')}
          </a>
        </div>
      )}
    </Card>
  );
}

function Inputs({ f }: { f: Forecast }) {
  const { t } = useI18n();
  const i = f.inputs;
  // SRS-7-27, SRS-7-29: the employer's NPS contribution is income with its matching exemption.
  const nps = (f.earnings?.months ?? []).reduce((sum, m) => sum + m.employerNps, 0);
  return (
    <div className="bd">
      <h3>{t('c07.inputs')}</h3>
      <dl className="kv">
        <dt>
          {t('e01t.regime')}
          <Src s="ESS" />
        </dt>
        <dd>{i.regime ? t(`e01t.regime.${i.regime}`) : t('c07.noRegime')}</dd>
        <dt>
          {t('c07.pan')}
          <Src s="HRMS" />
        </dt>
        <dd>{t(`c07.pan.${i.pan}`)}</dd>
        <dt>
          {t('c07.transportRebate')}
          <Src s="ENT" />
        </dt>
        <dd>{rupees(i.transportRebate)}</dd>
        {nps > 0 && (
          <>
            <dt>
              {t('c07.employerNps')}
              <Src s="CALC" />
            </dt>
            <dd>{rupees(nps)}</dd>
            <dt>
              {t('c07.npsExemption')}
              <Src s="CALC" />
            </dt>
            <dd>{rupees(nps)}</dd>
          </>
        )}
      </dl>
      <table className="t" aria-label={t('c07.deductions')}>
        <thead>
          <tr>
            <th>
              {t('m18s.section')}
              <Src s="ENT" />
            </th>
            <th>
              {t('c07.claimed')}
              <Src s="ENT" />
            </th>
            <th>
              {t('m18s.limit')}
              <Src s="RULE" />
            </th>
            <th>
              {t('c07.allowed')}
              <Src s="CALC" />
            </th>
          </tr>
        </thead>
        <tbody>
          {i.deductions.map((d) => (
            <tr key={d.sectionCode}>
              <td>{d.sectionCode}</td>
              <td>{rupees(d.claimed)}</td>
              <td>{rupees(d.limit)}</td>
              <td>{rupees(d.allowed)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {i.deductions.length === 0 && <p className="sub2">{t('c07.noDeductions')}</p>}
      {i.otherIncome.length > 0 && (
        <table className="t" aria-label={t('c07.otherIncome')}>
          <thead>
            <tr>
              <th>
                {t('i18.incomeHead')}
                <Src s="ENT" />
              </th>
              <th>
                {t('i18.amount')}
                <Src s="ENT" />
              </th>
              <th>
                {t('i18.tds')}
                <Src s="ENT" />
              </th>
            </tr>
          </thead>
          <tbody>
            {i.otherIncome.map((o) => (
              <tr key={`${o.head}|${o.amount}`}>
                <td>{o.head}</td>
                <td>{rupees(o.amount)}</td>
                <td>{rupees(o.tdsOtherSource)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function Earnings({ f }: { f: Forecast }) {
  const { t } = useI18n();
  if (!f.earnings) return <p className="fl-hint">{t('error.TAX-422-EARNINGS-NOT-AVAILABLE')}</p>;
  return (
    <table className="t" aria-label={t('c07.earnings')}>
      <thead>
        <tr>
          <th>
            {t('i18.month')}
            <Src s="SYS" />
          </th>
          <th>
            {t('c07.taxableGross')}
            <Src s="CALC" />
          </th>
          <th>
            {t('c07.tdsDeducted')}
            <Src s="CALC" />
          </th>
          <th>
            {t('c07.basis')}
            <Src s="SYS" />
          </th>
        </tr>
      </thead>
      <tbody>
        {f.earnings.months.map((m) => (
          <tr key={m.yymm}>
            <td>{m.yymm}</td>
            <td>{rupees(m.taxableGross)}</td>
            <td>{rupees(m.tdsDeducted)}</td>
            <td>{t(m.actual ? 'c07.actual' : 'c07.projected')}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function Result({ f }: { f: Forecast }) {
  const { t } = useI18n();
  const r = f.result;
  if (!r) return null;
  const rows: [string, number][] = [
    ['c07.taxableIncome', r.taxableIncome],
    ['c07.slabTax', r.slabTax],
    ['c07.surcharge', r.surcharge],
    ['c07.cess', r.cess],
    ['c07.annualTax', r.annualTax],
    ['c07.monthlyTds', r.monthlyTds],
  ];
  return (
    <div className="bd">
      <h3>{t('c07.result')}</h3>
      <dl className="kv" aria-label={t('c07.result')}>
        {rows.map(([key, value]) => (
          <div key={key}>
            <dt>
              {t(key)}
              <Src s="CALC" />
            </dt>
            <dd>{rupees(value)}</dd>
          </div>
        ))}
        {f.yearEndDifference !== null && (
          <div>
            <dt>
              {t('c07.yearEndDifference')}
              <Src s="CALC" />
            </dt>
            <dd>{rupees(f.yearEndDifference)}</dd>
          </div>
        )}
      </dl>
      {r.higherRateApplied && <p className="pill p-warn">{t('c07.higherRate')}</p>}
    </div>
  );
}
