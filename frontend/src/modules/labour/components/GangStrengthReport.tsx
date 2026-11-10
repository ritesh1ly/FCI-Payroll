'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import { Empty, MonthField, Problem, ReasonDialog, Src, date, useToast } from '@/shared/ui';
import { useCloseShortfall, useStrengthReport } from '../orchestration/labourQueries';

/**
 * The Gang Strength Report of a handling gang's month (SRS-2-48 l.4403; OD-90): sanctioned and
 * present strength by day, each shortfall with its absent members, the labour attached that day (how
 * the gap was filled) and whether the day's alert to the Depot Manager is still open. The Depot
 * Manager of the depot (M-09 APPROVE) closes an open alert with a reason; every other role sees the
 * action disabled with the reason. The Ancillary Pool has no strength and no report.
 */
export function GangStrengthReport({ gangId }: { gangId: number }) {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const [month, setMonth] = useState(businessToday().slice(0, 7));
  const report = useStrengthReport(gangId, month);
  const close = useCloseShortfall();
  const [closing, setClosing] = useState<string | null>(null);
  const mayClose = can(me.data, 'M-09', 'APPROVE');
  const days = report.data?.days ?? [];

  return (
    <section aria-label={t('m09.strengthReport')}>
      <h4>{t('m09.strengthReport')}</h4>
      <div className="toolbar">
        <label>
          {t('i01.month')} <MonthField format="YYYY-MM" value={month} onChange={setMonth} />
        </label>
      </div>
      {report.data && days.length === 0 && <Empty text={t('m09.strengthNone')} />}
      {days.length > 0 && (
        <table className="t" aria-label={t('m09.strengthReport')}>
          <thead>
            <tr>
              <th>
                {t('i02.date')}
                <Src s="SYS" />
              </th>
              <th>
                {t('m09.strength')}
                <Src s="ENT" />
              </th>
              <th>
                {t('m09.presentStrength')}
                <Src s="CALC" />
              </th>
              <th>
                {t('m09.shortBy')}
                <Src s="CALC" />
              </th>
              <th>
                {t('m09.absentMembers')}
                <Src s="CALC" />
              </th>
              <th>
                {t('m09.attachedMembers')}
                <Src s="SYS" />
              </th>
              <th>
                {t('m09.alert')}
                <Src s="SYS" />
              </th>
            </tr>
          </thead>
          <tbody>
            {days.map((d) => (
              <tr key={d.date}>
                <td>{date(d.date)}</td>
                <td>{d.sanctioned}</td>
                <td>{d.present}</td>
                <td>{d.shortBy}</td>
                <td>{d.absent.join(', ') || '—'}</td>
                <td>{d.attached.join(', ') || '—'}</td>
                <td>
                  {d.alertOpen ? t('m09.alertOpen') : d.shortBy > 0 ? t('m09.alertClosed') : '—'}
                  {d.alertOpen && (
                    <button
                      type="button"
                      className="btn"
                      disabled={!mayClose}
                      title={mayClose ? undefined : t('m09.why.closeShortfall')}
                      aria-label={t('m09.closeShortfallOf', [d.date])}
                      onClick={() => setClosing(d.date)}
                    >
                      {t('action.m09.closeShortfall')}
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {closing !== null && (
        <ReasonDialog
          title={t('m09.closeShortfallOf', [closing])}
          error={close.error}
          busy={close.isPending}
          onCancel={() => setClosing(null)}
          onConfirm={(reason) =>
            close.mutate(
              { gangId, date: closing, reason },
              {
                onSuccess: () => {
                  toast(t('m09.shortfallClosed'));
                  setClosing(null);
                },
              },
            )
          }
        />
      )}
      <Problem error={report.error} />
    </section>
  );
}
