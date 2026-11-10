'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { Card, Empty, Problem, Src, amount } from '@/shared/ui';
import { monthLabel } from '../business/vpfRules';
import { useMyStatement } from '../orchestration/vpfQueries';

/**
 * E-01 CPF balance and statement (plan 14 phase 14e; SRS-17-16, SRS-17-17, BR-INT-007): the
 * member's CPF ledger of a year as Payroll keeps it - opening and closing balance of each part,
 * interest credited, and the postings month by month. Read only.
 */
export function MyCpfStatement() {
  const { t } = useI18n();
  const me = useMe();
  const member = can(me.data, 'E-01', 'VIEW');
  const [year, setYear] = useState('');
  const asked = /^\d{4}-\d{2}$/.test(year.trim()) ? year.trim() : undefined;
  const statement = useMyStatement(member, asked);
  if (!member) return null;
  const s = statement.data;
  return (
    <Card title={t('e01.cpfStatement', [s?.finYear ?? asked ?? ''])}>
      <div className="toolbar">
        <label>
          {t('e01.finYear')}{' '}
          <input maxLength={7} placeholder="2026-27" value={year} onChange={(e) => setYear(e.target.value)} />
        </label>
      </div>
      {/* A failed load is shown, never hidden; the year selector stays so the member can correct it. */}
      <Problem error={statement.error} />
      {!s ? (
        statement.isPending && <p className="sub2">{t('common.loading')}</p>
      ) : s.ledgers.length === 0 ? (
        <Empty text={t('e01.noCpfLedger')} />
      ) : (
        <table className="t" aria-label={t('e01.cpfBalances')}>
          <thead>
            <tr>
              <th />
              <th>
                {t('e01.cpfEmployee')}
                <Src s="CALC" />
              </th>
              <th>
                {t('e01.cpfEmployer')}
                <Src s="CALC" />
              </th>
              <th>
                {t('e01.cpfVpf')}
                <Src s="CALC" />
              </th>
            </tr>
          </thead>
          <tbody>
            {s.ledgers.map((l) => [
              <tr key={`o${l.ledgerId}`}>
                <td>
                  {t('e01.opening')}
                  <Src s="CALC" />
                </td>
                <td className="num">{amount(l.openingEmployee)}</td>
                <td className="num">{amount(l.openingEmployer)}</td>
                <td className="num">{amount(l.openingVpf)}</td>
              </tr>,
              <tr key={`c${l.ledgerId}`}>
                <td>
                  {t('e01.closing')}
                  <Src s="CALC" />
                </td>
                <td className="num">{amount(l.closingEmployee)}</td>
                <td className="num">{amount(l.closingEmployer)}</td>
                <td className="num">{amount(l.closingVpf)}</td>
              </tr>,
              <tr key={`i${l.ledgerId}`}>
                <td>{t('e01.interestCredited')}</td>
                <td className="num" colSpan={3}>
                  {amount(l.interestCredited)}
                </td>
              </tr>,
            ])}
          </tbody>
        </table>
      )}
      {s && s.postings.length > 0 && (
        <table className="t" aria-label={t('e01.cpfPostings')}>
          <thead>
            <tr>
              <th>
                {t('e01.month')}
                <Src s="SYS" />
              </th>
              <th>
                {t('e01.postingType')}
                <Src s="SYS" />
              </th>
              <th>
                {t('e01.cpfEmployee')}
                <Src s="CALC" />
              </th>
              <th>
                {t('e01.cpfEmployer')}
                <Src s="CALC" />
              </th>
              <th>
                {t('e01.cpfVpf')}
                <Src s="CALC" />
              </th>
            </tr>
          </thead>
          <tbody>
            {s.postings.map((p, i) => (
              <tr key={`${p.yymm}-${i}`}>
                <td>{monthLabel(p.yymm)}</td>
                <td>{t(`e01.paymentType.${p.paymentType}`)}</td>
                <td className="num">{amount(p.employee)}</td>
                <td className="num">{amount(p.employer)}</td>
                <td className="num">{amount(p.vpf)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Card>
  );
}
