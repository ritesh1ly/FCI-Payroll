'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import { dateTime, Problem } from '@/shared/ui';
import { RegisterSection } from '@/shared/ui/masterParts';
import { Act, Explained, Locked, Panel, Tbl, Tiles, WfTile } from '@/shared/ui/wireframe';
import { finYearOf } from '../business/interestRateRules';
import { useInterestRuns } from '../orchestration/cpfQueries';
import { InterestRun as InterestRunRegister } from './InterestRun';

const NUM = new Intl.NumberFormat('en-IN');

type Start = 'RUN' | 'FREEZE' | null;

/**
 * C-12 CPF Interest Run — Calculate / Re-Run / Freeze as Wireframes v6.1 draws it (OD-121; SRS-6-45, SRS-6-46): the five
 * tiles of the zone's financial year, the interest lifecycle and the Interest Run Audit. The year choice, the run form,
 * the not-yet-calculated report and the freeze request stay whole in the closed section, opened by the drawn buttons
 * for those who hold the right: runs are the Manager (CPF)'s, the freeze request C-12 SUBMIT, its decision the AGM (CPF)'s
 * in W-01 without OTP (not one of the 18 gated actions, WF-273). The interest amount waits for FCI's method (Q-072): a
 * run records its counts, and every member stays not yet calculated until then.
 */
export function InterestRun() {
  const { t } = useI18n();
  const me = useMe();
  const year = finYearOf(businessToday());
  const summary = useInterestRuns(year, true);
  const roles = me.data?.roles ?? [];
  const mayRun = can(me.data, 'C-12', 'CREATE');
  const mayFreeze = can(me.data, 'C-12', 'SUBMIT');
  const [open, setOpen] = useState(false);
  const [start, setStart] = useState<Start>(null);
  const s = summary.data;
  const go = (what: Start) => {
    setStart(what);
    setOpen(true);
  };
  const lifecycle = [
    { icon: '1', src: 'ENT' as const, right: s ? t('c12w.life.rate') : '—' },
    {
      icon: '2',
      src: 'CALC' as const,
      right: s && s.runs.length > 0 ? t('c12w.life.run', [s.runs.length]) : t('c12w.life.noRun'),
    },
    { icon: '3', src: 'SYS' as const, right: NUM.format(s?.notYetCalculated ?? 0) },
    {
      icon: '4',
      src: 'CALC' as const,
      right: s?.freezeStatus === 'FROZEN' ? t('c12.freeze.FROZEN') : t('c12w.life.available'),
    },
    {
      icon: '5',
      src: 'SYS' as const,
      right: s?.freezeStatus === 'FROZEN' ? t('c12.freeze.FROZEN') : t('c12w.life.notFrozen'),
    },
  ];
  return (
    <>
      <div className="toolbar">
        <span className="sp" />
        {mayRun && <Act label={t('c12w.act.run')} kind="pri" onClick={() => go('RUN')} />}
        {!mayRun && roles.includes('PAYROLL_MAKER') && (
          <Explained label={t('c12w.act.run')} kind="pri" why={t('c12w.why.notManager')} />
        )}
        {!mayRun && !roles.includes('PAYROLL_MAKER') && (
          <Locked label={t('c12w.act.run')} kind="pri" why={t('c12w.why.run')} />
        )}
        {mayFreeze && <Act label={t('c12w.act.freeze')} kind="pri" onClick={() => go('FREEZE')} />}
        {!mayFreeze && roles.includes('PAYROLL_CHECKER') && (
          <Explained label={t('c12w.act.freeze')} kind="pri" why={t('c12w.why.notAgm')} />
        )}
        {!mayFreeze && !roles.includes('PAYROLL_CHECKER') && (
          <Locked label={t('c12w.act.freeze')} why={t('c12w.why.freeze')} />
        )}
      </div>
      <Problem error={summary.error} />
      <Tiles>
        <WfTile icon="📅" label={t('c12w.tile.year')} src="ENT" big={year} sub={t('c12w.tile.yearSub')} />
        <WfTile
          icon="✅"
          label={t('c12w.tile.calculated')}
          src="CALC"
          big={NUM.format(s?.calculated ?? 0)}
          sub={t('c12w.tile.calculatedSub', [NUM.format(s?.members ?? 0)])}
        />
        <WfTile
          icon="⚠️"
          label={t('c12w.tile.notYet')}
          src="SYS"
          big={NUM.format(s?.notYetCalculated ?? 0)}
          sub={t('c12w.tile.notYetSub')}
        />
        <WfTile
          icon="🔁"
          label={t('c12w.tile.reRuns')}
          src="SYS"
          big={NUM.format(s?.reRuns ?? 0)}
          sub={t('c12w.tile.reRunsSub')}
        />
        <WfTile
          icon="🔒"
          label={t('c12w.tile.freeze')}
          src="SYS"
          big={s ? t(`c12.freeze.${s.freezeStatus}`) : '—'}
          sub={t('c12w.tile.freezeSub')}
        />
      </Tiles>
      <Panel
        title={t('c12w.lifecycle')}
        items={lifecycle.map((l, i) => ({
          icon: l.icon,
          src: l.src,
          tone: 'info' as const,
          title: t(`c12w.life.${i + 1}.t`),
          text: t(`c12w.life.${i + 1}.m`),
          right: l.right,
        }))}
      />
      <div className="fl-mt">
        <Tbl
          title={t('c12w.audit')}
          acts={<Explained label={t('c12w.act.export')} kind="sm" why={t('c12w.why.export')} />}
          cols={[
            { t: t('c12w.col.run'), s: 'SYS' },
            { t: t('c12w.col.scope'), s: 'ENT' },
            { t: t('c12w.col.by'), s: 'SYS' },
            { t: t('c12w.col.when'), s: 'SYS' },
            { t: t('c12w.col.members'), s: 'CALC', num: true },
            { t: t('c12w.col.reason'), s: 'ENT' },
          ]}
        >
          {(s?.runs ?? []).map((r) => (
            <tr key={r.run}>
              <td>
                <b>{t('c12w.runNo', [r.run])}</b>
              </td>
              <td>{r.eis ? t('c12w.individual', [r.eis]) : t(`c12.scope.${r.scope}`)}</td>
              <td>{r.initiatedBy}</td>
              <td>{dateTime(r.initiatedAt)}</td>
              <td className="num">{NUM.format(r.members)}</td>
              <td>{r.reason}</td>
            </tr>
          ))}
          {s && s.runs.length === 0 && (
            <tr>
              <td colSpan={6} className="sub2">
                {t('c12.noRuns')}
              </td>
            </tr>
          )}
        </Tbl>
      </div>
      <RegisterSection id="c12-actions" label={t('c12w.register')} open={open} onToggle={setOpen}>
        {open && <InterestRunRegister key={start ?? 'open'} start={start} />}
      </RegisterSection>
    </>
  );
}
