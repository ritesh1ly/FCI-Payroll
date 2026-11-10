'use client';

import { useRouter } from 'next/navigation';
import { useI18n } from '@/shared/i18n';
import { useReference } from '@/shared/reference';
import { periodLabel, useMe } from '@/shared/session';
import { amount, date, Problem, ServerPagination } from '@/shared/ui';
import { Act, Explained, Locked, Tbl, Tiles, WfTile } from '@/shared/ui/wireframe';
import { computationPages, useIncentiveSummary } from '../orchestration/computationQueries';
import { gangTone } from './LabourComputation';

const NUM = new Intl.NumberFormat('en-IN');
const DASH = '—';

/**
 * C-06 Labour Incentive Computation as Wireframes v6.1 draws it (OD-121; SRS-3-3 ... 3-8, SRS-2-50, SRS-2-51; FRS
 * §8.4.2, §8.11.7): the tiles, Incentive gang-wise, the re-computations and Incentive Basic vs Salary Basic.
 *
 * The run is not built (PENDING LBR-20d-2: how BPL is applied is not stated), so ▶ Compute Incentive and ✅ Approve
 * Incentive say why, Bags above norm, Slab and the amount stay a dash until labour_incentive_worksheet holds them, and
 * Old DA / Revised DA stay a dash (DR-8). Bags, present labour and the norm in force are real.
 */
export function IncentiveComputation() {
  const { t, locale } = useI18n();
  const router = useRouter();
  const me = useMe();
  const reference = useReference();
  const period = me.data?.period ?? '';
  const summary = useIncentiveSummary(period);
  const s = summary.data;
  const label = periodLabel(s?.period ?? period, locale);
  const roles = me.data?.roles ?? [];
  const notBuilt = t('c06.why.run');
  return (
    <>
      <div className="toolbar">
        <span className="sp" />
        <Act label={t('c06.act.summary')} onClick={() => router.push('/payroll?s=I-02')} />
        {roles.includes('PAYROLL_MAKER') ? (
          <Explained label={t('c06.act.compute')} why={notBuilt} kind="pri" />
        ) : (
          <Locked label={t('c06.act.compute')} why={t('c06.why.maker')} kind="pri" />
        )}
        {roles.includes('PAYROLL_CHECKER') ? (
          <Explained label={t('c06.act.approve')} why={notBuilt} />
        ) : (
          <Locked label={t('c06.act.approve')} why={t('c06.why.checker')} />
        )}
      </div>
      <Problem error={summary.error} />
      {s && s.period !== period && (
        <p className="sub2" data-testid="c06-other-period">
          {t('lbc.shownPeriod', [periodLabel(period, locale), label])}
        </p>
      )}
      <p className="sub2" data-testid="c06-not-built">
        {notBuilt}
      </p>
      <Tiles>
        <WfTile
          icon="🎯"
          label={t('c06.tile.norms')}
          src="RULE"
          tone="ok"
          big={`${s?.depotsWithNorms ?? 0} / ${s?.depots ?? 0}`}
          sub={t('c06.tile.normsSub')}
        />
        <WfTile icon="👷" label={t('c06.tile.gangs')} src="CALC" big={s?.gangsComputed ?? 0} sub={label} />
        <WfTile
          icon="🔁"
          label={t('c06.tile.recomputed')}
          src="CALC"
          tone="warn"
          big={s?.recomputations ?? 0}
          sub={t('c06.tile.recomputedSub')}
        />
        <WfTile
          icon="₹"
          label={t('c06.tile.value')}
          src="CALC"
          tone="pur"
          big={s?.value == null ? DASH : amount(s.value)}
          sub={t('c06.tile.valueSub')}
        />
      </Tiles>
      <Tbl
        title={t('c06.gangs', [label])}
        acts={<Explained label={t('c06.act.trace')} kind="sm" why={notBuilt} />}
        cols={[
          { t: t('lbc.col.gang'), s: 'SYS' },
          { t: t('lbc.col.depot'), s: 'SYS' },
          { t: t('c06.col.bags'), s: 'ENT', num: true },
          { t: t('c06.col.present'), s: 'CALC', num: true },
          { t: t('c06.col.norm'), s: 'RULE', num: true },
          { t: t('c06.col.above'), s: 'CALC', num: true },
          { t: t('c06.col.slab'), s: 'RULE' },
          { t: t('c06.col.incentive'), s: 'CALC', num: true },
          { t: t('lbc.col.status'), s: 'SYS' },
        ]}
      >
        {period !== '' && (
          <ServerPagination
            queryKey={['labour-incentive', period, 'gangs']}
            fetchPage={computationPages.incentiveGangs(period)}
            empty={t('lbc.none')}
          >
            {(rows) =>
              rows.map((g) => (
                <tr key={g.gangId}>
                  <td>{g.gang}</td>
                  <td>{g.depot}</td>
                  <td className="num">{g.bags === null ? DASH : NUM.format(g.bags)}</td>
                  <td className="num">{g.presentLabour}</td>
                  <td className="num">{g.mln === null ? DASH : NUM.format(g.mln)}</td>
                  <td className="num">{DASH}</td>
                  <td>{DASH}</td>
                  <td className="num">{g.incentive === null ? DASH : amount(g.incentive)}</td>
                  <td>
                    <span className={`pill ${gangTone(g.status)}`}>
                      {reference.label('LABOUR_GANG_STATUS', g.status)}
                    </span>
                  </td>
                </tr>
              ))
            }
          </ServerPagination>
        )}
      </Tbl>
      <div className="g2 fl-mt">
        <Tbl
          title={t('c06.recomputed')}
          cols={[
            { t: t('c06.col.gangPeriod'), s: 'SYS' },
            { t: t('c06.col.reason'), s: 'SYS' },
            { t: t('c06.col.original'), s: 'SYS', num: true },
            { t: t('c06.col.revised'), s: 'CALC', num: true },
            { t: t('c06.col.effect'), s: 'CALC' },
          ]}
        >
          <ServerPagination
            queryKey={['labour-incentive', 'recomputations']}
            fetchPage={computationPages.recomputations}
            empty={t('c06.noneRecomputed')}
          >
            {(rows) =>
              rows.map((r) => (
                <tr key={r.id}>
                  <td>{`${r.gang} · ${r.period}`}</td>
                  <td>{t(`c06.reason.${r.reason}`)}</td>
                  <td className="num">{r.original === null ? DASH : amount(r.original)}</td>
                  <td className="num">{r.revised === null ? DASH : amount(r.revised)}</td>
                  <td>{DASH}</td>
                </tr>
              ))
            }
          </ServerPagination>
        </Tbl>
        <Tbl
          title={t('c06.basics')}
          acts={<Explained label={t('m01.w.exportShort')} kind="sm" why={t('c06.why.export')} />}
          cols={[
            { t: t('c06.col.labour'), s: 'SYS' },
            { t: t('c06.col.salaryBasic'), s: 'RULE', num: true },
            { t: t('c06.col.incentiveBasic'), s: 'RULE', num: true },
            { t: t('c06.col.oldDa'), s: 'RULE' },
            { t: t('c06.col.revisedDa'), s: 'RULE' },
            { t: t('c06.col.effective'), s: 'RULE' },
          ]}
        >
          <ServerPagination
            queryKey={['labour-incentive', 'basics']}
            fetchPage={computationPages.basics}
            empty={t('c06.noneBasic')}
          >
            {(rows) =>
              rows.map((b) => (
                <tr key={b.id}>
                  <td>{`${b.labourCode} ${b.name}`}</td>
                  <td className="num">{b.salaryBasic === null ? DASH : amount(b.salaryBasic)}</td>
                  <td className="num">{amount(b.incentiveBasic)}</td>
                  <td>{DASH}</td>
                  <td>{DASH}</td>
                  <td>{date(b.effectiveFrom)}</td>
                </tr>
              ))
            }
          </ServerPagination>
        </Tbl>
      </div>
    </>
  );
}
