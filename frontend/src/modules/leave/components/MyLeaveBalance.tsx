'use client';

import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { Card, Empty, Src, date } from '@/shared/ui';
import { useMyLeaveBalance } from '../orchestration/leaveQueries';

/**
 * E-01 leave balance (plan 14 phase 14e; SRS-LV-32): the latest balance HRMS sent for each leave
 * type, for the member's information only (HRMS data, read-only).
 */
export function MyLeaveBalance() {
  const { t } = useI18n();
  const me = useMe();
  const member = can(me.data, 'E-01', 'VIEW');
  const balances = useMyLeaveBalance(member);
  if (!member || !balances.data) return null;
  return (
    <Card title={t('e01.leaveBalance')}>
      {balances.data.length === 0 ? (
        <Empty text={t('e01.noLeaveBalance')} />
      ) : (
        <table className="t" aria-label={t('e01.leaveBalance')}>
          <thead>
            <tr>
              <th>
                {t('e01.leaveType')}
                <Src s="HRMS" />
              </th>
              <th>
                {t('e01.balanceDays')}
                <Src s="HRMS" />
              </th>
              <th>
                {t('e01.asOf')}
                <Src s="HRMS" />
              </th>
            </tr>
          </thead>
          <tbody>
            {balances.data.map((b) => (
              <tr key={b.leaveType}>
                <td>
                  {b.leaveType} - {b.leaveTypeName}
                </td>
                <td className="num">{b.days}</td>
                <td>{date(b.asOf)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <p className="sub2">{t('e01.leaveBalanceNote')}</p>
    </Card>
  );
}
