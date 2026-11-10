'use client';

import { useI18n } from '@/shared/i18n';
import { Problem, Src, withBase } from '@/shared/ui';
import { KvCard } from '@/shared/ui/wireframe';
import { useCycleParameters } from '../orchestration/calendarQueries';

/** A link to the screen that keeps a value (each is changed there, under its own approval). */
function KeptOn({ screen }: { screen: string }) {
  const { t } = useI18n();
  return (
    <a className="lnk" href={withBase(`/payroll/?s=${screen}`)}>
      {t('m10.params.keptOn', [screen])}
    </a>
  );
}

/**
 * M-10 Cycle Parameters as the pack draws them (OD-121; plan 05 phase 05f, D-23; SRS l.1638): the
 * period key, what a freeze locks, the lock and undo authorities (X-01), the post-lock path, the MDP
 * heads (M-16), the undo stage, the back-date window (A-07) and the net-pay floor (M-14), read-only
 * with a link to the screen that keeps each; none is set here (D-23, M10-6). The check rules the
 * SRS names for the panel and the pack does not draw (SRS-15-39) stay in a closed section.
 */
export function CycleParameters() {
  const { t } = useI18n();
  const params = useCycleParameters();
  const p = params.data;
  // A partial answer (an older server) shows "not configured" rather than failing the screen.
  const authorities = p?.authorities ?? [];
  const heads = p?.mdpHeads ?? [];
  const checks = p?.checks ?? [];
  const authority = (action: string) =>
    authorities
      .filter((a) => a.action === action)
      .map((a) => `${a.level}. ${a.role}${a.otp ? ` (${t('m10.params.otp')})` : ''}`)
      .join(' → ') || t('mst.notConfigured');
  const notConfigured = <span className="pill p-info">{t('m10w.params.setting')}</span>;
  return (
    <KvCard
      title={t('m10w.params.title')}
      rows={[
        { k: t('m10w.params.key'), v: 'PAYROLL_YYMM', s: 'SYS' },
        { k: t('m10w.params.freezeLocks'), v: t('m10w.params.freezeLocksValue'), s: 'RULE' },
        {
          k: t('m10.params.lock'),
          v: (
            <>
              {authority('C02_LOCK')} <KeptOn screen="X-01" />
            </>
          ),
          s: 'ENT',
        },
        { k: t('m10.params.correction'), v: t('m10.params.correctionPath'), s: 'RULE' },
        {
          k: t('m10w.params.mdp'),
          v: (
            <>
              {heads.map((h) => `${h.payCode} ${h.name}`).join(', ') || notConfigured}{' '}
              <KeptOn screen="M-16" />
            </>
          ),
          s: 'RULE',
        },
        { k: t('m10w.params.undoAt'), v: t('m10w.params.undoAtValue'), s: 'ENT' },
        {
          k: t('m10w.params.undoAuthority'),
          v: (
            <>
              {authority('C02_UNDO')} <KeptOn screen="X-01" />
            </>
          ),
          s: 'ENT',
        },
        {
          k: t('m10w.params.backDate'),
          v: (
            <>
              {p?.backDateWindowMonths == null
                ? notConfigured
                : t('m10.params.months', [p.backDateWindowMonths])}{' '}
              <KeptOn screen="A-07" />
            </>
          ),
          s: 'ENT',
        },
        {
          k: t('m10w.params.floor'),
          v: (
            <>
              {p?.staffNetPayFloor
                ? `${p.staffNetPayFloor.basis} ${p.staffNetPayFloor.value}`
                : notConfigured}{' '}
              <KeptOn screen="M-14" />
            </>
          ),
          s: 'RULE',
        },
      ]}
      after={
        <>
          <Problem error={params.error} />
          <details className="fl-mt" data-testid="m10-check-rules">
            <summary>{t('m10.params.checks')}</summary>
            <table className="t" aria-label={t('m10.params.checks')}>
              <thead>
                <tr>
                  <th>
                    {t('m10.params.check')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m10.params.stage')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m10.params.payrolls')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m10.params.owner')}
                    <Src s="RULE" />
                  </th>
                  <th>
                    {t('m10.params.severity')}
                    <Src s="RULE" />
                  </th>
                </tr>
              </thead>
              <tbody>
                {checks.map((c) => (
                  <tr key={`${c.stage}|${c.code}`}>
                    <td>
                      {c.name}
                      {!c.active && <span className="pill p-gray"> {t('cyc.preRun.NOT_AVAILABLE')}</span>}
                    </td>
                    <td>{t(`m10.params.stage.${c.stage}`)}</td>
                    <td>{c.appliesTo}</td>
                    <td>{c.owner ?? '—'}</td>
                    <td>{c.severity}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </details>
        </>
      }
    />
  );
}
