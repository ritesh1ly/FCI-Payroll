'use client';

import { useI18n } from '@/shared/i18n';
import { Card, dateTime, ServerPagination, Src } from '@/shared/ui';
import { flaggedTdsPages } from '../orchestration/taxQueries';

/**
 * C-01 ▸ TDS cases for review (FRS V0.5 §14.4 Step 4; US-08-07, US-GAP-29): the members of the
 * session's offices whose TDS for the cycle's financial year changed by a proof-deadline reversal
 * (SRS-7-14) or an approved mid-year regime reset (BR-TAX-001), for the Payroll Checker to review
 * before deciding the cycle's lock on W-01 (approve: the TDS posts with the cycle; reject: returned
 * for correction). Read-only.
 */
export function FlaggedTdsCases({ period }: { period: string }) {
  const { t } = useI18n();
  return (
    <Card title={t('c01.tdsReview')}>
      <p className="fl-hint">{t('c01.tdsReviewHint')}</p>
      <div className="wrap">
        <ServerPagination
          queryKey={['tax', 'flagged-tds', period]}
          fetchPage={flaggedTdsPages(period)}
          empty={<p className="fl-hint">{t('c01.noTdsReview')}</p>}
        >
          {(rows) => (
            <table className="t" aria-label={t('c01.tdsReview')}>
              <thead>
                <tr>
                  <th>
                    {t('c01.member')}
                    <Src s="HRMS" />
                  </th>
                  <th>
                    {t('c01.tdsFlag')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('c01.tdsReference')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('c01.tdsWhen')}
                    <Src s="SYS" />
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={`${r.employeeId}-${r.flag}-${r.reference ?? ''}`} data-reference={r.eis}>
                    <td>
                      <b>{r.eis}</b>
                      <div className="sub2">{r.name}</div>
                      {r.office && <div className="sub2">{r.office}</div>}
                    </td>
                    <td>
                      {t(`c01.tdsFlag.${r.flag}`)}
                      {r.reason && <div className="sub2">{r.reason}</div>}
                    </td>
                    <td>
                      {r.reference === null
                        ? '—'
                        : t(r.flag === 'REGIME_RESET' ? 'c01.tdsApproval' : 'c01.tdsRound', [r.reference])}
                    </td>
                    <td>{dateTime(r.at)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </ServerPagination>
      </div>
    </Card>
  );
}
