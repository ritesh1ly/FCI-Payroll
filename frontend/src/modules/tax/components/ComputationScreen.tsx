'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { amount, Problem, ServerPagination, withBase } from '@/shared/ui';
import { RegisterSection } from '@/shared/ui/masterParts';
import { Act, Explained, Locked, Panel, Tbl, Tiles, WfTile } from '@/shared/ui/wireframe';
import type { TaxLine, TypeLine } from '../api/screensApi';
import { reasonOf } from '../business/statutoryRules';
import { screenKeys, screenPages, useComputationSummary } from '../orchestration/screensQueries';
import { TaxComputation } from './TaxComputation';

const TYPES = ['STAFF', 'DEPT_LABOUR', 'DPS_LABOUR', 'NWNP_LABOUR'] as const;

function sum(lines: TypeLine[], pick: (l: TypeLine) => number | null): number | null {
  const held = lines.map(pick).filter((v): v is number => v !== null);
  return held.length === 0 ? null : held.reduce((a, b) => a + b, 0);
}

/**
 * C-07 Statutory Computation as Wireframes v6.1 draws it (OD-121; SRS-7-1 to SRS-7-4, SRS-7-12, SRS-7-13, SRS-7-26,
 * SRS-9-1, BR-TAX-001, BR-PEN-001): the unit's latest locked period by payroll type, each member's tax from the real
 * forecast or the reason it cannot be made, and NPS exactly as HRMS sent it. A rate that is not configured stays
 * empty: the EPS and higher-pension rates are not in the rule engine (PENDING TAX-C07-EPS), so that table shows the
 * wages a run stored and no amount. A member's full forecast, its export and the what-if stay in the closed section.
 */
export function ComputationScreen() {
  const { t } = useI18n();
  const me = useMe();
  const summary = useComputationSummary();
  const s = summary.data;
  const [open, setOpen] = useState(false);
  const mayCompute = can(me.data, 'I-07', 'SUBMIT');
  const notConfigured = t('txw.c07.notConfigured');
  const lines = s?.types ?? [];
  const cpf = sum(lines, (l) => l.employee);
  const vpf = sum(lines, (l) => l.vpf);
  const tds = sum(lines, (l) => l.tds);
  const exceptions = (s?.noCpfCode ?? 0) + (s?.npsMismatch ?? 0) + (s?.regimeMissing ?? 0);
  const show = () => setOpen(true);
  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <Act label={t('txw.c07.statements')} onClick={show} />
        {mayCompute ? (
          <Act label={t('txw.c07.compute')} kind="pri" onClick={show} />
        ) : (
          <Locked label={t('txw.c07.compute')} why={t('txw.why.makerOnly')} />
        )}
      </div>
      <Problem error={summary.error} />
      <Tiles>
        <WfTile
          icon="🏦"
          label={t('txw.c07.tile.cpf')}
          src="CALC"
          big={cpf === null ? '—' : `₹ ${amount(cpf)}`}
          sub={t('txw.c07.tile.cpfSub', [amount(cpf), amount(vpf)])}
        />
        <WfTile icon="🕊" label={t('txw.c07.tile.eps')} src="CALC" big="—" sub={notConfigured} tone="pur" />
        <WfTile
          icon="🧾"
          label={t('txw.c07.tile.tds')}
          src="CALC"
          big={tds === null ? '—' : `₹ ${amount(tds)}`}
          sub={tds === null ? notConfigured : (s?.period ?? '')}
          tone="ok"
        />
        <WfTile
          icon="⛔"
          label={t('txw.c07.tile.exceptions')}
          src="SYS"
          big={exceptions}
          sub={t('txw.c07.tile.exceptionsSub', [
            s?.noCpfCode ?? 0,
            s?.npsMismatch ?? 0,
            s?.regimeMissing ?? 0,
          ])}
          tone="bad"
          bad={exceptions > 0}
        />
      </Tiles>
      <Tbl
        title={t('txw.c07.cpf')}
        acts={<Explained label={t('txw.c07.reconcile')} kind="sm" why={t('txw.c07.why.ssp')} />}
        cols={[
          { t: t('txw.c07.cpf.type'), s: 'RULE' },
          { t: t('txw.c07.cpf.members'), s: 'CALC', num: true },
          { t: t('txw.c07.cpf.rate'), s: 'RULE' },
          { t: t('txw.c07.cpf.employee'), s: 'CALC', num: true },
          { t: t('txw.c07.cpf.employer'), s: 'CALC', num: true },
          { t: t('txw.c07.cpf.vpf'), s: 'ESS', num: true },
          { t: t('txw.c07.cpf.rule'), s: 'RULE' },
          { t: t('txw.c07.cpf.status'), s: 'SYS' },
        ]}
      >
        {TYPES.map((type) => {
          const l = lines.find((x) => x.payrollType === type);
          return (
            <tr key={type} data-reference={type}>
              <td>{t(`txw.c07.type.${type}`)}</td>
              <td className="num">{l?.members ?? '—'}</td>
              <td>{s?.cpfRate == null ? notConfigured : t(`txw.c07.rate.${type}`, [String(s.cpfRate)])}</td>
              <td className="num">{amount(l?.employee)}</td>
              <td className="num">—</td>
              <td className="num">{amount(l?.vpf)}</td>
              <td>{t(`txw.c07.rule.${type}`)}</td>
              <td>
                <span className="pill">{t(l ? 'txw.c07.computed' : 'txw.c07.notComputed')}</span>
              </td>
            </tr>
          );
        })}
      </Tbl>
      <Tbl
        title={t('txw.c07.eps')}
        acts={<Explained label={t('txw.c07.zonal')} kind="sm" why={t('txw.c07.why.zonal')} />}
        cols={[
          { t: t('txw.c07.eps.member'), s: 'HRMS' },
          { t: t('txw.c07.eps.wages'), s: 'CALC', num: true },
          { t: 'EPS_CONTRIB_RATE', s: 'RULE' },
          { t: t('txw.c07.eps.amount'), s: 'CALC', num: true },
          { t: 'HIGHER_PENSION_RATE', s: 'RULE' },
          { t: t('txw.c07.eps.higher'), s: 'CALC', num: true },
          { t: t('txw.c07.eps.rounding'), s: 'CALC' },
          { t: t('txw.c07.eps.status'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={screenKeys.eps}
          fetchPage={screenPages.eps}
          empty={t('txw.c07.noneLocked')}
        >
          {(page) =>
            page.map((l) => (
              <tr key={l.employeeId} data-reference={l.eis}>
                <td>
                  {l.eis} {l.name}
                </td>
                <td className="num">{amount(l.wages)}</td>
                <td>{notConfigured}</td>
                <td className="num">—</td>
                <td>{notConfigured}</td>
                <td className="num">—</td>
                <td>—</td>
                <td>
                  <span className="pill">{notConfigured}</span>
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <Panel
        title={t('txw.c07.rounding')}
        items={[
          { icon: '🧮', tone: 'info', title: t('txw.c07.r.1'), src: 'CALC', text: t('txw.c07.r.1s') },
          { icon: '⚖️', tone: 'warn', title: t('txw.c07.r.2'), src: 'CALC', text: t('txw.c07.r.2s') },
          { icon: 'Σ', tone: 'ok', title: t('txw.c07.r.3'), src: 'CALC', text: t('txw.c07.r.3s') },
          { icon: '⚖️', tone: 'gray', title: t('txw.c07.r.4'), src: 'SYS' },
        ]}
      />
      <Tbl
        title={t('txw.c07.tax')}
        acts={<Act label={t('txw.c07.form16')} kind="sm" href={withBase('/payroll/?s=C-10')} />}
        cols={[
          { t: t('txw.c07.tax.member'), s: 'HRMS' },
          { t: t('txw.c07.tax.regime'), s: 'ESS' },
          { t: t('txw.c07.tax.taxable'), s: 'CALC', num: true },
          { t: t('txw.c07.tax.annual'), s: 'CALC', num: true },
          { t: t('txw.c07.tax.monthly'), s: 'CALC', num: true },
          { t: t('txw.c07.tax.pt'), s: 'RULE', num: true },
          { t: t('txw.c07.tax.status'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={screenKeys.taxLines}
          fetchPage={screenPages.taxLines}
          empty={t('txw.c07.noMembers')}
        >
          {(page) => page.map((l) => <TaxRow key={l.employeeId} line={l} />)}
        </ServerPagination>
      </Tbl>
      <Tbl
        title={t('txw.c07.nps')}
        acts={<Act label={t('txw.c07.npsAct')} kind="sm" href={withBase('/payroll/?s=I-14')} />}
        cols={[
          { t: t('txw.c07.nps.member'), s: 'HRMS' },
          { t: t('txw.c07.nps.pattern'), s: 'RULE' },
          { t: 'PF_TYPE', s: 'RULE' },
          { t: t('txw.c07.nps.flag'), s: 'HRMS' },
          { t: t('txw.c07.nps.pran'), s: 'HRMS' },
          { t: t('txw.c07.nps.employee'), s: 'HRMS', num: true },
          { t: t('txw.c07.nps.employer'), s: 'HRMS', num: true },
          { t: t('txw.c07.nps.status'), s: 'SYS' },
        ]}
      >
        <ServerPagination queryKey={screenKeys.nps} fetchPage={screenPages.nps} empty={t('txw.c07.noNps')}>
          {(page) =>
            page.map((l) => (
              <tr key={l.id} data-reference={l.eis}>
                <td>
                  {l.eis} {l.name}
                </td>
                <td>{l.pattern ?? '—'}</td>
                <td>{l.pfType ?? '—'}</td>
                <td>{t(l.npsMember ? 'txw.c07.nps.isNps' : 'txw.c07.nps.notNps')}</td>
                <td>{l.pran ?? '—'}</td>
                <td className="num">{amount(l.employeeAmount)}</td>
                <td className="num">{amount(l.employerAmount)}</td>
                <td>
                  <span className="pill">
                    {t(l.mismatch === 'CONSISTENT' ? 'txw.c07.nps.received' : `txw.c07.nps.${l.mismatch}`)}
                  </span>
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <RegisterSection label={t('txw.c07.section')} open={open} onToggle={setOpen} id="c07-member">
        {open && <TaxComputation />}
      </RegisterSection>
    </div>
  );
}

function TaxRow({ line: l }: { line: TaxLine }) {
  const { t } = useI18n();
  let status = t('txw.c07.computed');
  if (l.reason) {
    const { key, args } = reasonOf(l.reason);
    status = t(key, args);
  } else if (l.higherRate) {
    status = t('txw.c07.higherRate');
  }
  return (
    <tr data-reference={l.eis}>
      <td>
        {l.eis} {l.name}
      </td>
      <td>{l.regime ? t(`e01t.regime.${l.regime}`) : '—'}</td>
      <td className="num">{amount(l.taxable)}</td>
      <td className="num">{amount(l.annualTax)}</td>
      <td className="num">{amount(l.monthlyTds)}</td>
      <td className="num">{amount(l.professionalTax)}</td>
      <td>
        <span className="pill">{status}</span>
      </td>
    </tr>
  );
}
