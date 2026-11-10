'use client';

import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { type Me, periodLabel } from '@/shared/session';
import { usePeriods, useSwitchPeriod } from '../orchestration/authQueries';

/**
 * The top-bar 📅 payroll period selector of wireframe v6.1: the approved periods of the M-10
 * calendar, newest first, each with its cycle state at the session's paying unit. The session works
 * on the current period or a back-dated one; a future period is not offered (the server refuses it
 * too). Screens that work per period (C-02, C-13) follow the choice.
 */
export function PeriodSwitcher({
  session,
  enabled,
  onSwitched,
  onError,
}: {
  session: Me;
  enabled: boolean;
  onSwitched: (next: Me) => void;
  onError: (message: string) => void;
}) {
  const { t } = useI18n();
  const periods = usePeriods(enabled);
  const switchPeriod = useSwitchPeriod();
  const options = (periods.data ?? []).filter(
    (p) => p.period <= (periods.data?.find((x) => x.current)?.period ?? p.period),
  );

  function label(period: string, status: string | null, current: boolean): string {
    const state = status ? t(`cyc.state.${status}`) : t('period.notStarted');
    return `${periodLabel(period)} · ${state}${current ? ` · ${t('period.current')}` : ''}`;
  }

  async function choose(period: string) {
    try {
      onSwitched(await switchPeriod.mutateAsync(period));
    } catch (cause) {
      onError(cause instanceof ApiError ? t(cause.messageKey, cause.args) : t('error.unexpected'));
    }
  }

  // Until the calendar answers (or without one) the session's period is the only option.
  const known = options.some((p) => p.period === session.period);
  return (
    <select
      aria-label={t('top.period')}
      value={session.period}
      disabled={!enabled || switchPeriod.isPending || options.length === 0}
      onChange={(e) => void choose(e.target.value)}
    >
      {!known && <option value={session.period}>{periodLabel(session.period)}</option>}
      {options.map((p) => (
        <option key={p.period} value={p.period}>
          {label(p.period, p.status, p.current)}
        </option>
      ))}
    </select>
  );
}
