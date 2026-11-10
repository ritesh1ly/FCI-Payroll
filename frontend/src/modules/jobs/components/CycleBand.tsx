'use client';

import { useI18n } from '@/shared/i18n';
import { periodLabel } from '@/shared/session';
import { withBase } from '@/shared/ui';
import { Act, Locked, PeriodBand, type StepState } from '@/shared/ui/wireframe';

export const CYCLE_STATES = [
  'NOT_INITIATED',
  'INITIATED',
  'FROZEN',
  'COMPUTED',
  'VERIFIED',
  'LOCKED',
] as const;

/** SRS l.1604, SRS-15-31: an un-freeze is asked for a frozen cycle before its lock. */
const UNFREEZABLE = new Set<string>(['FROZEN', 'COMPUTED', 'VERIFIED']);

/** The pack's step track of the period band (Wireframes v6.1 periodBand): one step per cycle stage. */
const STEPS = ['initiate', 'freeze', 'compute', 'verify', 'lock', 'post', 'supplementary'] as const;

/** The financial year of a period "2026-07" as the band prints it ("2026–27"). */
export function bandYear(period: string): string {
  const [year = '', month = ''] = period.split('-');
  return Number(month) >= 4
    ? `${year}–${String(Number(year) + 1).slice(2)}`
    : `${Number(year) - 1}–${year.slice(2)}`;
}

/**
 * The period band of D-01 and C-01 (Wireframes v6.1 periodBand): the period and its state pill, the
 * line, the three gated cycle actions, each disabled with its reason until its gate opens (each
 * acts on C-02 or O-02, under that screen's right), and the step track. Posted and Supplementary
 * are steps of the drawing only: the cycle status stops at Locked (SRS-15-12).
 */
export function CycleBand({
  period,
  state,
  line,
  own,
  maker,
  checker,
  finance,
}: {
  period: string;
  /** The cycle state, null when none was initiated. */
  state: string | null;
  line: string;
  /** The session's own unit (a roll-up shows the state and acts on nothing). */
  own: boolean;
  maker: boolean;
  checker: boolean;
  finance: boolean;
}) {
  const { t, locale } = useI18n();
  const at = state ? CYCLE_STATES.indexOf(state as (typeof CYCLE_STATES)[number]) : -1;
  const stateText = state ? t(`d01.state.${state}`) : t('d01.state.NONE');
  const steps: StepState[] = STEPS.map((step, i) => ({
    label: t(`d01.step.${step}`),
    state: i < at ? 'done' : i === at ? 'now' : '',
  }));
  const unfreezable = own && state !== null && UNFREEZABLE.has(state);
  return (
    <PeriodBand
      period={periodLabel(period, locale)}
      statePill={<span className="pill p-warn fl-pill-band">{stateText}</span>}
      line={line}
      steps={steps}
      actions={
        <>
          {maker && unfreezable ? (
            <Act label={t('d01.unfreeze')} href={withBase('/payroll/?s=C-02#c02-cycle')} />
          ) : (
            <Locked label={t('d01.unfreeze')} why={t('d01.why.unfreeze')} />
          )}
          {checker && own && state === 'VERIFIED' ? (
            <Act label={t('d01.lock')} href={withBase('/payroll/?s=C-02#c02-cycle')} />
          ) : (
            <Locked label={t('d01.lock')} why={t('d01.why.lock', [stateText])} />
          )}
          {finance && own && state === 'LOCKED' ? (
            <Act label={t('d01.post')} href={withBase('/payroll/?s=O-02')} />
          ) : (
            <Locked label={t('d01.post')} why={t('d01.why.post', [stateText])} />
          )}
        </>
      }
    />
  );
}
