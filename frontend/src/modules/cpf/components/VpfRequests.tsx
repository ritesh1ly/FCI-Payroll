'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { Card, Empty, Problem, ReasonDialog, ServerPagination, Src, amount, useToast } from '@/shared/ui';
import type { VpfRequest, VpfStatus } from '../api/vpfApi';
import { monthLabel, withMaker } from '../business/vpfRules';
import { useReturnVpf, useVerifyVpf, vpfKeys, vpfPage } from '../orchestration/vpfQueries';

const STATUSES: readonly VpfStatus[] = ['SUBMITTED', 'APPLIED', 'REJECTED', 'RETURNED'];

/**
 * E-02 VPF change requests (plan 14 phase 14c; BR-ESEVA-006; D14-4): the members' requests of the
 * session's offices; the Payroll Maker verifies one and sends it to the Payroll Checker (W-01), or
 * returns it to the member with the reason. Each action is disabled with its reason otherwise.
 */
export function VpfRequests() {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const [status, setStatus] = useState<VpfStatus | ''>('SUBMITTED');
  const [returning, setReturning] = useState<VpfRequest | null>(null);
  const [error, setError] = useState<unknown>(null);
  const verify = useVerifyVpf();
  const back = useReturnVpf();
  const mayAct = can(me.data, 'E-02', 'SUBMIT');

  async function send(r: VpfRequest) {
    setError(null);
    try {
      await verify.mutateAsync(r.id);
      toast(t('vpf.sentToChecker'));
    } catch (cause) {
      setError(cause);
    }
  }

  async function returned(reason: string) {
    const r = returning;
    setReturning(null);
    if (!r) return;
    setError(null);
    try {
      await back.mutateAsync({ id: r.id, reason });
      toast(t('vpf.returned'));
    } catch (cause) {
      setError(cause);
    }
  }

  function why(r: VpfRequest): string | undefined {
    if (!mayAct) return t('reason.makerOnly');
    return withMaker(r) ? undefined : t('vpf.why.notWithMaker');
  }

  return (
    <Card title={t('vpf.staffTitle')}>
      <div className="toolbar">
        <label>
          {t('vpf.status')}{' '}
          <select value={status} onChange={(e) => setStatus(e.target.value as VpfStatus | '')}>
            <option value="">{t('e02.allStatuses')}</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {t(`vpf.status.${s}`)}
              </option>
            ))}
          </select>
        </label>
      </div>
      <Problem error={error} />
      <ServerPagination
        queryKey={vpfKeys.list(status || undefined)}
        fetchPage={vpfPage(status || undefined)}
        empty={<Empty text={t('vpf.none')} />}
      >
        {(rows) => (
          <table className="t" aria-label={t('vpf.staffTitle')}>
            <thead>
              <tr>
                <th>
                  {t('e02.member')}
                  <Src s="HRMS" />
                </th>
                <th>
                  {t('vpf.currentShort')}
                  <Src s="ENT" />
                </th>
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
                <th />
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>
                    {r.eis} {r.member}
                    {r.office && <div className="sub2">{r.office}</div>}
                  </td>
                  <td className="num">{amount(r.currentAmount)}</td>
                  <td className="num">{amount(r.requestedAmount)}</td>
                  <td>{monthLabel(r.effectiveFromYymm)}</td>
                  <td>{t(r.awaitingChecker ? 'vpf.status.WITH_CHECKER' : `vpf.status.${r.status}`)}</td>
                  <td>
                    <button
                      type="button"
                      className="btn primary"
                      disabled={why(r) !== undefined || verify.isPending}
                      title={why(r)}
                      onClick={() => void send(r)}
                    >
                      {t('action.e02.verifyVpf')}
                    </button>{' '}
                    <button
                      type="button"
                      className="btn"
                      disabled={why(r) !== undefined || back.isPending}
                      title={why(r)}
                      onClick={() => setReturning(r)}
                    >
                      {t('action.vpf.return')}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </ServerPagination>
      {returning && (
        <ReasonDialog
          title={t('vpf.returnTitle', [returning.eis])}
          confirmLabel={t('action.vpf.return')}
          onConfirm={(reason) => void returned(reason)}
          onCancel={() => setReturning(null)}
        />
      )}
    </Card>
  );
}
