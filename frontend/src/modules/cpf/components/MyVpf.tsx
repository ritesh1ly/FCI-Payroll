'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { Card, Problem, Src, amount, dateTime, useToast } from '@/shared/ui';
import { monthLabel, requestBlocked } from '../business/vpfRules';
import { useMyVpf, useRequestVpf } from '../orchestration/vpfQueries';

/**
 * E-01 VPF subscription (plan 14 phase 14c; BR-ESEVA-006, SRS-6-1; D14-4): the member's monthly VPF
 * of the year and a request for a new amount from the next open payroll month, verified by the
 * Payroll Maker and approved by the Payroll Checker before it applies; one request at a time.
 */
export function MyVpf() {
  const { t } = useI18n();
  const me = useMe();
  const member = can(me.data, 'E-01', 'VIEW');
  const mayAsk = can(me.data, 'E-01', 'CREATE');
  const mine = useMyVpf(member);
  const ask = useRequestVpf();
  const toast = useToast();
  const [value, setValue] = useState('');
  const [error, setError] = useState<unknown>(null);
  if (!member) return null;
  if (!mine.data) {
    // A failed load is shown with its problem, never a silently missing card.
    return (
      <Card title={t('vpf.title')}>
        <Problem error={mine.error} />
        {mine.isPending && <p className="sub2">{t('common.loading')}</p>}
      </Card>
    );
  }
  const data = mine.data;
  const blocked = mayAsk ? requestBlocked(data, value) : 'vpf.why.memberOnly';

  async function send() {
    setError(null);
    try {
      await ask.mutateAsync(Number(value.trim()));
      setValue('');
      toast(t('vpf.asked'));
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <Card title={t('vpf.title')}>
      <p>
        {t('vpf.current', [data.finYear])}: <strong>{amount(data.currentAmount)}</strong>
        <Src s="ENT" />
      </p>
      <div className="toolbar">
        <label>
          {t('vpf.newAmount')}{' '}
          <input
            inputMode="decimal"
            maxLength={13}
            value={value}
            onChange={(e) => setValue(e.target.value)}
          />
        </label>
        <button
          type="button"
          className="btn primary"
          disabled={blocked !== null || ask.isPending}
          title={blocked ? t(blocked) : undefined}
          onClick={() => void send()}
        >
          {t('action.vpf.request')}
        </button>
      </div>
      <p className="sub2">{t('vpf.note')}</p>
      <Problem error={error} />
      {data.requests.length > 0 && (
        <table className="t" aria-label={t('vpf.requests')}>
          <thead>
            <tr>
              <th>
                {t('vpf.asked.amount')}
                <Src s="ESS" />
              </th>
              <th>
                {t('vpf.from')}
                <Src s="SYS" />
              </th>
              <th>
                {t('vpf.status')}
                <Src s="SYS" />
              </th>
              <th>
                {t('vpf.submittedAt')}
                <Src s="SYS" />
              </th>
            </tr>
          </thead>
          <tbody>
            {data.requests.map((r) => (
              <tr key={r.id}>
                <td className="num">{amount(r.requestedAmount)}</td>
                <td>{monthLabel(r.effectiveFromYymm)}</td>
                <td>
                  {t(r.awaitingChecker ? 'vpf.status.WITH_CHECKER' : `vpf.status.${r.status}`)}
                  {r.decisionReason && <div className="sub2">{r.decisionReason}</div>}
                </td>
                <td>{dateTime(r.submittedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Card>
  );
}
