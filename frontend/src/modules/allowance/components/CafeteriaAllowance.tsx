'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { recentFinYears } from '@/shared/time';
import { amount, Card, date, Empty, FinancialYearSelect, ServerPagination, Src } from '@/shared/ui';
import { cafeteriaPage, claimKeys } from '../orchestration/claimQueries';

/**
 * I-16 Cafeteria Allowance (from HRMS) (plan 10 phase 10f; SRS-11-16, SRS I-16 field table): the
 * selections received from HRMS for the financial year, read-only (no entry, election or upload in
 * Payroll), each with its components, start date, HRMS reference and this month's pay effect (pro
 * rata for a part month).
 */
export function CafeteriaAllowance() {
  const { t } = useI18n();
  const me = useMe();
  const [eis, setEis] = useState('');
  const [shown, setShown] = useState('');
  // SRS l.2836-2838: the selections "for the financial year", the current year (India) first.
  const [finYear, setFinYear] = useState(() => recentFinYears(1)[0] ?? '');
  const eisOk = eis.trim() === '' || /^[A-Za-z0-9]{1,20}$/.test(eis.trim());
  if (!can(me.data, 'I-16', 'VIEW')) return null;
  return (
    <Card title={t('i16.title')}>
      <p className="sub2">{t('i16.hint')}</p>
      <div className="toolbar">
        <label>
          {t('i03.eis')} <input maxLength={20} value={eis} onChange={(e) => setEis(e.target.value)} />
        </label>
        <label>
          {t('i16.finYear')} <FinancialYearSelect value={finYear} onChange={setFinYear} />
        </label>
        <button
          type="button"
          className="btn"
          disabled={!eisOk}
          title={eisOk ? undefined : t('i16.badEis')}
          onClick={() => setShown(eis.trim())}
        >
          {t('i03.show')}
        </button>
      </div>
      <ServerPagination
        queryKey={claimKeys.cafeteria(shown, finYear)}
        fetchPage={cafeteriaPage(shown, finYear)}
        empty={
          // SRS l.2850: a member searched with no selection reads "No selection in HRMS".
          shown !== '' ? (
            <p className="fl-hint" role="status">
              {t('i16.noSelection', [shown])}
            </p>
          ) : (
            <Empty text={t('i16.none')} />
          )
        }
      >
        {(rows) => (
          <table className="t" aria-label={t('i16.title')}>
            <thead>
              <tr>
                <th>
                  {t('i16.eis')}
                  <Src s="HRMS" />
                </th>
                <th>
                  {t('i16.category')}
                  <Src s="HRMS" />
                </th>
                <th>
                  {t('i16.components')}
                  <Src s="HRMS" />
                </th>
                <th>
                  {t('i16.start')}
                  <Src s="HRMS" />
                </th>
                <th>
                  {t('i16.reference')}
                  <Src s="HRMS" />
                </th>
                <th>
                  {t('i16.payEffect')}
                  <Src s="CALC" />
                </th>
                <th>
                  {t('i16.status')}
                  <Src s="SYS" />
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((s) => (
                <tr key={s.id}>
                  <td>
                    <b>{s.eis}</b> <span className="sub2">{s.name}</span>
                    <div className="sub2">{t('i16.version', [s.finYear, s.version])}</div>
                  </td>
                  <td>{s.category ?? '—'}</td>
                  <td>{s.components.map((c) => `${c.name} ${amount(c.monthlyAmount)}`).join(' · ')}</td>
                  <td>
                    {date(s.startDate)}
                    {s.endDate && ` – ${date(s.endDate)}`}
                  </td>
                  <td>{s.hrmsReference ?? '—'}</td>
                  <td>{amount(s.payEffect)}</td>
                  <td>{t(`i16.st.${s.status}`)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </ServerPagination>
    </Card>
  );
}
