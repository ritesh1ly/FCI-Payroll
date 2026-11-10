'use client';

import { useI18n } from '@/shared/i18n';
import { Card, dateTime, Empty, Problem, Src } from '@/shared/ui';
import { partAUrl, useMyCertificates } from '../orchestration/statutoryQueries';
import { rupees } from './money';

/**
 * E-01 card: the member's released Form 16 certificates, Part B figures and the merged Part A
 * (SRS-7-9, SRS-17-2): locked periods only, released by the Payroll Checker. No PAN is shown.
 */
export function MyForm16() {
  const { t } = useI18n();
  const mine = useMyCertificates();
  if (mine.isPending) return <p className="fl-hint">{t('common.loading')}</p>;
  if (!mine.data) return <Problem error={mine.error} />;
  return (
    <Card title={t('e01f.title')}>
      {mine.data.length === 0 ? (
        <Empty text={t('e01f.none')} />
      ) : (
        <table className="t" aria-label={t('e01f.title')}>
          <thead>
            <tr>
              <th>
                {t('i07.finYear')}
                <Src s="SYS" />
              </th>
              <th>
                {t('c10.gross')}
                <Src s="CALC" />
              </th>
              <th>
                {t('c07.taxableIncome')}
                <Src s="CALC" />
              </th>
              <th>
                {t('c10.tds')}
                <Src s="CALC" />
              </th>
              <th>
                {t('e01f.released')}
                <Src s="SYS" />
              </th>
              <th>
                {t('c10.partA')}
                <Src s="ENT" />
              </th>
            </tr>
          </thead>
          <tbody>
            {mine.data.map((c) => (
              <tr key={c.id}>
                <td>{c.finYear}</td>
                <td>{rupees(c.grossSalary)}</td>
                <td>{rupees(c.taxableIncome)}</td>
                <td>{rupees(c.taxDeducted)}</td>
                <td>{dateTime(c.releasedAt)}</td>
                <td>
                  {c.partADocumentId ? (
                    <a href={partAUrl(c.id)} aria-label={t('e01f.downloadOf', [c.finYear])}>
                      {t('e01f.download')}
                    </a>
                  ) : (
                    '—'
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Card>
  );
}
