'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import { Card, date, Empty, ExportButton, ServerPagination, Src, MonthField } from '@/shared/ui';
import type { NpsFilter, NpsStatus } from '../api/npsApi';
import { isMismatch, isYymm, NPS_STATUSES, reportRangeOk, yymmOf } from '../business/npsRules';
import { npsExportUrl, npsKeys, npsPage, npsReportUrl, useNpsCounts } from '../orchestration/npsQueries';

function rupees(n: number | null): string {
  return n === null
    ? '—'
    : `₹${Number(n).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

/**
 * I-14 NPS Deduction (from HRMS) (plan 18 phase 18c; SRS-9-1, SRS-9-1a, SRS-9-2, SRS-9-6, SRS-9-21).
 * View only: the NPS flag, PRAN and the employee and employer NPS amounts HRMS sent for the month.
 * The employee amount is deducted as received; the employer amount is shown under earnings and
 * deductions, so net pay is unchanged. A flag / amount mismatch is listed first and holds the lock
 * until HRMS corrects it: nothing here edits a value (R-SRC). Export and the NPS Deduction Report
 * need I-14 EXPORT.
 */
export function NpsDeductions() {
  const { t } = useI18n();
  const me = useMe();
  const [yymm, setYymm] = useState(() => yymmOf(businessToday()));
  const [status, setStatus] = useState<NpsStatus | ''>('');
  const [eis, setEis] = useState('');
  const [from, setFrom] = useState(() => yymmOf(businessToday()));
  const [to, setTo] = useState(() => yymmOf(businessToday()));
  const monthOk = isYymm(yymm);
  const filter: NpsFilter = { yymm, status: status || undefined, eis: eis.trim() || undefined };
  const counts = useNpsCounts(yymm, monthOk);
  const mayExport = can(me.data, 'I-14', 'EXPORT');
  const rangeOk = reportRangeOk(from, to);

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <label>
          {t('i14.month')} <MonthField value={yymm} onChange={setYymm} />
        </label>
        <label>
          {t('i14.status')}{' '}
          <select value={status} onChange={(e) => setStatus(e.target.value as NpsStatus | '')}>
            <option value="">{t('i14.allStatuses')}</option>
            {NPS_STATUSES.map((s) => (
              <option key={s} value={s}>
                {t(`i14.status.${s}`)}
              </option>
            ))}
          </select>
        </label>
        <label>
          {t('m01.eis')} <input maxLength={20} value={eis} onChange={(e) => setEis(e.target.value)} />
        </label>
        {mayExport &&
          (monthOk ? (
            <ExportButton href={npsExportUrl(filter)} what={t('screen.I-14')} />
          ) : (
            <button type="button" className="btn" disabled title={t('i14.badMonth')}>
              {t('action.export')}
            </button>
          ))}
      </div>
      {monthOk && counts.data && counts.data.length > 0 && (
        <ul className="fl-list" aria-label={t('i14.counts')}>
          {counts.data.map((c) => (
            <li key={c.status} className={isMismatch(c.status) ? 'warn' : undefined}>
              {t(`i14.status.${c.status}`)}: {c.count}
            </li>
          ))}
        </ul>
      )}
      <p className="sub2">{t('i14.note')}</p>
      <Card title={t('screen.I-14')}>
        {monthOk ? (
          <ServerPagination
            queryKey={npsKeys.list(filter)}
            fetchPage={npsPage(filter)}
            empty={<Empty text={t('i14.none')} />}
          >
            {(rows) => (
              <table className="t" aria-label={t('screen.I-14')}>
                <thead>
                  <tr>
                    <th>
                      {t('i14.member')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('i14.flag')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('i14.pran')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('i14.employee')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('i14.employer')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('i14.deducted')}
                      <Src s="CALC" />
                    </th>
                    <th>
                      {t('i14.effectiveFrom')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('i14.status')}
                      <Src s="SYS" />
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.id} data-reference={`${r.eis}|${yymm}`}>
                      <td>
                        {r.eis} {r.employee}
                        {r.office && <div className="sub2">{r.office}</div>}
                      </td>
                      <td>{t(r.npsFlag ? 'common.yes' : 'common.no')}</td>
                      <td>{r.pran ?? '—'}</td>
                      <td>{rupees(r.employeeAmount)}</td>
                      <td>{rupees(r.employerAmount)}</td>
                      <td>{rupees(r.deducted)}</td>
                      <td>{r.effectiveFrom ? date(r.effectiveFrom) : '—'}</td>
                      <td>
                        {t(`i14.status.${r.status}`)}
                        {isMismatch(r.status) && <div className="sub2">{t('i14.fixInHrms')}</div>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </ServerPagination>
        ) : (
          <p className="fl-hint">{t('i14.badMonth')}</p>
        )}
      </Card>
      {mayExport && (
        <Card title={t('i14.report')}>
          <div className="toolbar">
            <label>
              {t('i14.from')} <MonthField value={from} onChange={setFrom} />
            </label>
            <label>
              {t('i14.to')} <MonthField value={to} onChange={setTo} />
            </label>
            {rangeOk ? (
              <ExportButton
                href={npsReportUrl(from, to)}
                what={t('i14.report')}
                label={t('i14.reportExport')}
              />
            ) : (
              <button type="button" className="btn" disabled title={t('i14.badRange')}>
                {t('i14.reportExport')}
              </button>
            )}
          </div>
          <p className="sub2">{t('i14.reportNote')}</p>
        </Card>
      )}
    </div>
  );
}
