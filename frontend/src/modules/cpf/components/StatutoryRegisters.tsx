'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { amount, date, Problem } from '@/shared/ui';
import { RegisterSection, SimulateAct, useMasterRights } from '@/shared/ui/masterParts';
import {
  Act,
  Explained,
  Locked,
  Panel,
  RowActs,
  Tbl,
  Tiles,
  ViewDialog,
  WfTile,
} from '@/shared/ui/wireframe';
import type { InterestRate } from '../api/cpfApi';
import type { RegisterRule } from '../api/cpfScreensApi';
import { useInterestRates } from '../orchestration/cpfQueries';
import { useCpfApprove, useRegister } from '../orchestration/cpfScreenQueries';
import { InterestRates as RateRegister } from './InterestRates';
import { StatutoryRates as StatutoryRegister } from './StatutoryRates';

const DASH = '—';

/** A rule's value as a register row shows it: never a guess when no version is in force (R-RULE). */
function ruleValue(t: (k: string) => string, r: RegisterRule): string {
  if (!r.inForce) return t('m18w.notSeeded');
  if (r.inForce.value !== null) return String(r.inForce.value);
  return r.inForce.bands.length > 0 ? t('m18w.slabs') : t('m18w.notSeeded');
}

/**
 * M-18 Statutory Rate Seed — EPS / Higher-Pension / PT as Wireframes v6.1 draws it (OD-121; SRS-6-47, BR-CPF-017): the
 * tiles and the statutory rate register of the rule engine, read on M-18 VIEW. A rate with no approved version reads as
 * not seeded (OQ-24, R-RULE): no value is assumed. Proposing, superseding, the income-tax sections and the PT states stay
 * whole in the closed register on the M-08 rights: the Rule Administrator proposes, the Rule Approver activates.
 */
export function StatutoryRates() {
  const { t } = useI18n();
  const me = useMe();
  const rules = useRegister('m-18');
  const rights = useMasterRights('M-18');
  const mayPropose = can(me.data, 'M-08', 'EDIT') || can(me.data, 'M-08', 'CREATE');
  const mayApprove = can(me.data, 'M-08', 'APPROVE');
  const admin = (me.data?.roles ?? []).includes('SYSTEM_ADMINISTRATOR');
  const approve = useCpfApprove('M-08', 'mst.why.ruleApprover');
  const [open, setOpen] = useState(false);
  const [viewing, setViewing] = useState<RegisterRule | null>(null);
  const rows = rules.data ?? [];
  const active = rows.filter((r) => r.inForce !== null).length;
  const circulars = new Set(rows.map((r) => r.inForce?.circularNumber).filter(Boolean)).size;
  const openRegister = () => setOpen(true);
  return (
    <>
      <div className="toolbar">
        <span className="sp" />
        <SimulateAct label={t('m18w.act.simulate')} />
        <Act label={t('m18w.act.history')} onClick={openRegister} />
        {mayPropose && <Act label={t('m18w.act.new')} kind="pri" onClick={openRegister} />}
        {!mayPropose && admin && (
          <Explained label={t('m18w.act.new')} kind="pri" why={rights.entryWhy ?? t('mst.why.notMaker')} />
        )}
        {!mayPropose && !admin && <Locked label={t('m18w.act.new')} kind="pri" why={t('m18w.why.new')} />}
      </div>
      <Problem error={rules.error} />
      <Tiles>
        <WfTile
          icon="🔑"
          label={t('m18w.tile.keys')}
          src="RULE"
          big={rows.length}
          sub={t('m18w.tile.keysSub')}
        />
        <WfTile
          icon="✅"
          label={t('m18w.tile.active')}
          src="SYS"
          big={active}
          sub={t('m18w.tile.activeSub')}
        />
        <WfTile
          icon="📜"
          label={t('m18w.tile.circulars')}
          src="SYS"
          big={circulars}
          sub={t('m18w.tile.circularsSub')}
        />
        <WfTile
          icon="⚖️"
          label={t('m18w.tile.rounding')}
          src="RULE"
          big={t('m18w.tile.roundingBig')}
          sub={t('m18w.tile.roundingSub')}
        />
      </Tiles>
      <Tbl
        title={t('m18w.table')}
        acts={
          <>
            <SimulateAct label={t('m18w.act.simulateSm')} />
            {mayApprove && <Act label={t('m18w.act.supersede')} kind="sm" onClick={openRegister} />}
            {!mayApprove && (me.data?.roles ?? []).includes('PAYROLL_CHECKER') && (
              <Explained label={t('m18w.act.supersede')} kind="sm" why={t('mst.why.ruleApprover')} />
            )}
            {!mayApprove && !(me.data?.roles ?? []).includes('PAYROLL_CHECKER') && (
              <Locked label={t('m18w.act.supersede')} kind="sm" why={t('m18w.why.supersede')} />
            )}
          </>
        }
        cols={[
          { t: t('m18w.col.key'), s: 'ENT' },
          { t: t('m18w.col.applies'), s: 'RULE' },
          { t: t('m18w.col.value'), s: 'RULE' },
          { t: t('m18w.col.from'), s: 'ENT' },
          { t: t('m18w.col.version'), s: 'SYS' },
          { t: t('m18w.col.status'), s: 'SYS' },
          { t: t('m18w.col.order'), s: 'ENT' },
          { t: t('m18w.col.actions'), s: 'SYS' },
        ]}
      >
        {rows.map((r) => (
          <tr key={r.key}>
            <td>
              <b>{r.key}</b>
            </td>
            <td>{r.payrollTypeScope ?? r.name}</td>
            <td>{ruleValue(t, r)}</td>
            <td>{r.inForce ? date(r.inForce.effectiveFrom) : DASH}</td>
            <td>{r.inForce ? `v${r.inForce.versionNo}` : DASH}</td>
            <td>
              <span className={`pill ${r.inForce ? 'p-ok' : 'p-warn'}`}>
                {r.inForce ? t('status.ACTIVE') : t('m18w.notConfigured')}
              </span>
            </td>
            <td>{r.inForce?.circularNumber ?? DASH}</td>
            <td>
              <RowActs
                onView={() => setViewing(r)}
                onEdit={mayPropose ? openRegister : undefined}
                editWhy={t('m18w.why.edit')}
                onApprove={approve ? () => approve(false) : undefined}
                viewName={t('m18w.viewName', [r.key])}
                editName={t('m18w.editName', [r.key])}
              />
            </td>
          </tr>
        ))}
      </Tbl>
      <RegisterSection id="m18-register" label={t('m18w.register')} open={open} onToggle={setOpen}>
        {open && <StatutoryRegister />}
      </RegisterSection>
      {viewing && (
        <ViewDialog
          screen={t('screen.M-18')}
          onClose={() => setViewing(null)}
          rows={[
            { k: t('m18w.col.key'), v: viewing.key, s: 'ENT' },
            { k: t('m18w.view.name'), v: viewing.name, s: 'RULE' },
            { k: t('m18w.col.value'), v: ruleValue(t, viewing), s: 'RULE' },
            {
              k: t('m18w.col.from'),
              v: viewing.inForce ? date(viewing.inForce.effectiveFrom) : DASH,
              s: 'ENT',
            },
            { k: t('m18w.col.order'), v: viewing.inForce?.circularNumber ?? DASH, s: 'ENT' },
            { k: t('m18w.view.basis'), v: viewing.description ?? DASH, s: 'RULE' },
          ]}
        />
      )}
    </>
  );
}

/** The rate of a row: an undeclared Trust rate reads as drawn, the setting not yet declared. */
function rateText(t: (k: string) => string, r: InterestRate): string {
  return r.declared ? String(r.ratePercent) : t('m19w.undeclared');
}

/** Interest rule lines drawn with titles only: the pack's developer edition strips their text. */
const RULE_PANEL: [string, number][] = [
  ['📌', 1],
  ['📌', 2],
  ['⏹', 3],
  ['🗄', 4],
];

/**
 * M-19 PF Interest Rates & Parameters as Wireframes v6.1 draws it (OD-121; SRS-6-11, BR-CPF-033, SRS M-19): the rates of
 * each financial year and source, the interest rules and the CPF / VPF parameters (rule versions read on M-19 VIEW). The
 * record and declare forms, the fund and bonus columns, the withdrawal purposes and the full parameter catalogue stay
 * whole in the closed register.
 */
export function InterestRates() {
  const { t } = useI18n();
  const me = useMe();
  const rates = useInterestRates();
  const parameters = useRegister('m-19');
  const rights = useMasterRights('M-19');
  const admin = (me.data?.roles ?? []).includes('SYSTEM_ADMINISTRATOR');
  const approve = useCpfApprove('M-19', 'mst.why.ruleApprover');
  const [open, setOpen] = useState(false);
  const [startNew, setStartNew] = useState(false);
  const [viewing, setViewing] = useState<InterestRate | null>(null);
  const pending = new Set((rates.data?.pending ?? []).map((p) => p.rateId));
  const openRegister = () => setOpen(true);
  return (
    <>
      <div className="toolbar">
        <span className="sp" />
        <Act label={t('m19w.act.history')} onClick={openRegister} />
        {rights.mayCreate && rights.entryWhy === null && (
          <Act
            label={t('m19w.act.new')}
            kind="pri"
            onClick={() => {
              setStartNew(true);
              setOpen(true);
            }}
          />
        )}
        {(!rights.mayCreate || rights.entryWhy !== null) && admin && (
          <Explained label={t('m19w.act.new')} kind="pri" why={rights.entryWhy ?? t('mst.why.notMaker')} />
        )}
        {(!rights.mayCreate || rights.entryWhy !== null) && !admin && (
          <Locked label={t('m19w.act.new')} kind="pri" why={t('m19w.why.new')} />
        )}
      </div>
      <Problem error={rates.error ?? parameters.error} />
      <Tbl
        title={t('m19w.rates')}
        acts={<Act label={t('m19w.act.historySm')} kind="sm" onClick={openRegister} />}
        cols={[
          { t: t('m19w.col.year'), s: 'ENT' },
          { t: t('m19w.col.source'), s: 'RULE' },
          { t: t('m19w.col.rate'), s: 'ENT' },
          { t: t('m19w.col.period'), s: 'RULE' },
          { t: t('m19w.col.status'), s: 'SYS' },
          { t: t('m19w.col.actions'), s: 'SYS' },
        ]}
      >
        {(rates.data?.rates ?? []).map((r) => (
          <tr key={r.id}>
            <td>
              <b>{r.finYear}</b>
            </td>
            <td>{t(`m19.source.${r.rateSource}`)}</td>
            <td>{rateText(t, r)}</td>
            <td>{r.applicabilityNote ?? t(`m19.population.${r.memberPopulation}`)}</td>
            <td>
              <span className={`pill ${r.status === 'ACTIVE' ? 'p-ok' : 'p-gray'}`}>
                {t(`status.${r.status}`)}
              </span>
            </td>
            <td>
              <RowActs
                onView={() => setViewing(r)}
                onEdit={rights.mayCreate && rights.entryWhy === null ? openRegister : undefined}
                editWhy={rights.entryWhy ?? t('m19w.why.new')}
                onApprove={approve ? () => approve(pending.has(r.id)) : undefined}
                viewName={t('m19w.viewName', [r.finYear, t(`m19.source.${r.rateSource}`)])}
                editName={t('m19w.editName', [r.finYear, t(`m19.source.${r.rateSource}`)])}
              />
            </td>
          </tr>
        ))}
        {rates.isSuccess && rates.data.rates.length === 0 && (
          <tr>
            <td colSpan={6} className="sub2">
              {t('m19.noRates')}
            </td>
          </tr>
        )}
      </Tbl>
      <div className="fl-mt">
        <Panel
          title={t('m19w.rules')}
          items={RULE_PANEL.map(([icon, n]) => ({
            icon,
            src: 'RULE' as const,
            tone: 'info' as const,
            title: t(`m19w.rule.${n}`),
          }))}
        />
      </div>
      <div className="fl-mt">
        <Tbl
          title={t('m19w.parameters')}
          acts={<Act label={t('m19w.act.historySm')} kind="sm" onClick={openRegister} />}
          cols={[
            { t: t('m19w.p.parameter'), s: 'ENT' },
            { t: t('m19w.p.value'), s: 'ENT' },
            { t: t('m19w.p.basis'), s: 'RULE' },
            { t: t('m19w.p.level'), s: 'SYS' },
            { t: t('m19w.p.actions'), s: 'SYS' },
          ]}
        >
          {(parameters.data ?? []).map((r) => (
            <tr key={r.key}>
              <td>
                <b>{r.name}</b>
              </td>
              <td>{ruleValue(t, r)}</td>
              <td>{r.description ?? DASH}</td>
              <td>{t('m19w.p.hq')}</td>
              <td>
                <RowActs
                  onView={openRegister}
                  onEdit={rights.mayCreate && rights.entryWhy === null ? openRegister : undefined}
                  editWhy={rights.entryWhy ?? t('m19w.why.new')}
                  onApprove={approve ? () => approve(false) : undefined}
                  viewName={t('m18w.viewName', [r.key])}
                  editName={t('m18w.editName', [r.key])}
                />
              </td>
            </tr>
          ))}
        </Tbl>
      </div>
      <RegisterSection id="m19-register" label={t('m19w.register')} open={open} onToggle={setOpen}>
        {open && <RateRegister key={String(startNew)} startNew={startNew} />}
      </RegisterSection>
      {viewing && (
        <ViewDialog
          screen={t('screen.M-19')}
          onClose={() => setViewing(null)}
          rows={[
            { k: t('m19w.col.year'), v: viewing.finYear, s: 'ENT' },
            { k: t('m19.fund'), v: t(`m19.fundType.${viewing.fundType}`), s: 'ENT' },
            { k: t('m19w.col.source'), v: t(`m19.source.${viewing.rateSource}`), s: 'RULE' },
            { k: t('m19w.col.rate'), v: rateText(t, viewing), s: 'ENT' },
            {
              k: t('m19w.col.period'),
              v: `${date(viewing.appliesFrom)} – ${viewing.appliesTo ? date(viewing.appliesTo) : t('mst.open')}`,
              s: 'RULE',
            },
            {
              k: t('m19.bonus'),
              v: viewing.bonusInterestPct === null ? DASH : amount(viewing.bonusInterestPct),
              s: 'ENT',
            },
            { k: t('m19w.col.status'), v: t(`status.${viewing.status}`), s: 'SYS' },
          ]}
        />
      )}
    </>
  );
}
