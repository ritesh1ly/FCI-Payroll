'use client';

import { useMemo, useRef, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { RegisterSection, useMasterRights, useRouteApprove } from '@/shared/ui/masterParts';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { businessToday } from '@/shared/time';
import { date, ExportButton, Problem, ServerPagination, useToast } from '@/shared/ui';
import {
  Act,
  Explained,
  KvCard,
  Locked,
  RowActs,
  Tbl,
  Tiles,
  Toolbar,
  ViewDialog,
  WfTile,
} from '@/shared/ui/wireframe';
import type { NormLine, ScreenFilter } from '../api/labourScreenApi';
import {
  type NormEntry,
  figure,
  normEntryReady,
  rangeText,
  versionPill,
} from '../business/labourScreenRules';
import { useMouCirculars, useNormDepots } from '../orchestration/labourQueries';
import {
  normLinesPage,
  normsExportUrl,
  screenKeys,
  useFirstNorm,
  useNormSummary,
  useSlabs,
} from '../orchestration/screenQueries';
import { IncentiveNorms as NormRegister } from './IncentiveNorms';

const EMPTY: NormEntry = { depot: '', mou: '', mln: '', mlsw: '', height: '', lead: '', effective: '' };

/**
 * M-06 Incentive Norms as Wireframes v6.1 draws it (OD-121; SRS-3-6, SRS-3-12; FRS §8.4.2): the depot and norm-type
 * filters, the four tiles, the New Depot Norm Set form, the Depot Norm Register, the above-norm slab rates and the
 * formula the norms feed. Norms are set at Headquarters only (A-10 revised): the Rule Administrator proposes, the
 * Rule Approver approves. The drawn form leads into the full proposal in the closed register, which adds the slab
 * bands and the MoU circular the drawing does not show; a role that may not enter is told why.
 */
export function IncentiveNorms() {
  const { t } = useI18n();
  const toast = useToast();
  const rights = useMasterRights('M-06');
  const approve = useRouteApprove('M-06');
  const today = businessToday();
  const depots = useNormDepots();
  const summary = useNormSummary();
  const [depot, setDepot] = useState<number | null>(null);
  const [normType, setNormType] = useState('HANDLING');
  const [q, setQ] = useState('');
  const [entry, setEntry] = useState<NormEntry>(EMPTY);
  const [checked, setChecked] = useState(false);
  const [register, setRegister] = useState(false);
  const [startNew, setStartNew] = useState<NormEntry | null>(null);
  const [viewing, setViewing] = useState<NormLine | null>(null);
  const [mouOpen, setMouOpen] = useState(false);
  const form = useRef<HTMLDivElement>(null);
  const circulars = useMouCirculars(mouOpen);
  const filter = useMemo<ScreenFilter>(() => ({ depot, normType, q }), [depot, normType, q]);
  const firstNorm = useFirstNorm(filter);
  const norm = firstNorm.data?.items?.[0] ?? null;
  const slabs = useSlabs(norm?.id ?? null);
  const why = t('m06w.why.hq');
  const hqOnly = rights.drawn;

  function field(key: keyof NormEntry, label: string, type: string, placeholder?: string) {
    const bad = checked && entry[key].trim() === '';
    return (
      <div className={`rf-field${bad ? ' err' : ''}`}>
        <label>
          {label} <span className="req">*</span>
        </label>
        <input
          type={type}
          placeholder={placeholder}
          min={type === 'number' ? 0 : undefined}
          value={entry[key]}
          onChange={(e) => setEntry({ ...entry, [key]: e.target.value })}
        />
      </div>
    );
  }

  function save() {
    setChecked(true);
    if (!normEntryReady(entry)) {
      toast(t('m05w.correct'));
      return;
    }
    if (!rights.mayCreate) {
      toast(rights.entryWhy ?? t('mst.why.notMaker'));
      return;
    }
    setStartNew({ ...entry });
    setRegister(true);
    toast(t('m06w.completeInRegister'));
  }

  function toForm() {
    form.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
    form.current?.querySelector('input')?.focus();
  }

  /** Import MoU and Maintain: the set is proposed whole, so both lead to the register's proposal. */
  function maintain() {
    if (!rights.mayCreate) {
      toast(rights.entryWhy ?? t('mst.why.notMaker'));
      return;
    }
    setStartNew(EMPTY);
    setRegister(true);
  }

  const exportControl = rights.mayExport ? (
    <ExportButton href={normsExportUrl(filter)} what={t('screen.M-06')} label={t('wf.export')} />
  ) : (
    <Explained label={t('wf.export')} why={t('lw.why.export')} />
  );

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {norm ? (
          <HistoryButton
            entity="labour_norm"
            recordKey={norm.depotCode}
            label={norm.depot}
            text={t('lw.history')}
            className="btn"
          />
        ) : (
          <Act label={t('lw.history')} onClick={() => toast(t('m06.none'))} />
        )}
        <Act label={t('m06w.mouRegister')} onClick={() => setMouOpen(true)} />
        {hqOnly ? (
          <Act label={t('m06w.newSet')} kind="pri" onClick={toForm} />
        ) : (
          <Locked label={t('m06w.newSet')} kind="pri" why={why} />
        )}
      </div>
      <Toolbar search={q} onSearch={setQ} exportControl={exportControl}>
        <select
          aria-label={t('lw.depotFilter')}
          value={depot ?? ''}
          onChange={(e) => setDepot(e.target.value ? Number(e.target.value) : null)}
        >
          <option value="">{t('m09.allDepots')}</option>
          {(Array.isArray(depots.data) ? depots.data : []).map((d) => (
            <option key={d.id} value={d.id}>
              {d.name}
            </option>
          ))}
        </select>
        <select
          aria-label={t('m06w.normType')}
          value={normType}
          onChange={(e) => setNormType(e.target.value)}
        >
          <ReferenceOptions list="LABOUR_NORM_TYPE" />
        </select>
      </Toolbar>
      <Tiles>
        <WfTile
          icon="🏭"
          label={t('m06w.t.depots')}
          src="HRMS"
          big={Array.isArray(depots.data) ? depots.data.length : '—'}
          sub={t('m06w.t.depotsS')}
        />
        <WfTile
          icon="🎯"
          tone={summary.data?.depotsWithSet ? 'ok' : 'bad'}
          label={t('m06w.t.seeded')}
          src="ENT"
          big={summary.data?.depotsWithSet ?? 0}
          bad={!summary.data?.depotsWithSet}
          sub={t('m06w.t.seededS')}
        />
        <WfTile
          icon="📊"
          tone="warn"
          label={t('m06w.t.slabs')}
          src="RULE"
          big={summary.data?.slabTables ?? 0}
          sub={t('m06w.t.slabsS')}
        />
        <WfTile icon="📐" label={t('m06w.t.types')} src="RULE" big={3} sub={t('m06w.t.typesS')} />
      </Tiles>
      <div className="card fl-mb" ref={form}>
        <div className="hd">
          <h3>{t('m06w.form')}</h3>
        </div>
        <div className="bd">
          {hqOnly ? (
            <>
              <div className="rf-grid">
                {field('depot', t('m06w.f.depot'), 'text', t('m06w.ph.depot'))}
                {field('mou', t('m06w.f.mou'), 'text', t('m06w.ph.mou'))}
                {field('mln', t('m06w.f.mln'), 'number', t('m06w.ph.mln'))}
                {field('mlsw', t('m06w.f.mlsw'), 'number', t('m06w.ph.mlsw'))}
                {field('height', t('m06w.f.height'), 'text', t('m06w.ph.height'))}
                {field('lead', t('m06w.f.lead'), 'text', t('m06w.ph.lead'))}
                {field('effective', t('m06w.f.effective'), 'date')}
              </div>
              <div className="rf-actbar">
                <button type="button" className="btn pri" onClick={save}>
                  {t('m05w.save')}
                </button>
              </div>
            </>
          ) : (
            <p className="sub2">{t('m06w.entryAdmin')}</p>
          )}
        </div>
      </div>
      <Tbl
        title={t('m06w.register')}
        acts={
          hqOnly ? (
            <Act label={t('m06w.import')} kind="sm" onClick={maintain} />
          ) : (
            <Locked label={t('m06w.import')} kind="sm" why={why} />
          )
        }
        cols={[
          { t: t('m06w.col.depot'), s: 'HRMS' },
          { t: t('m06w.col.mou'), s: 'ENT' },
          { t: t('m06w.col.mln'), s: 'ENT', num: true },
          { t: t('m06w.col.mlsw'), s: 'ENT', num: true },
          { t: t('m06w.col.height'), s: 'ENT' },
          { t: t('m06w.col.lead'), s: 'ENT' },
          { t: t('m06w.col.from'), s: 'ENT' },
          { t: t('m06w.col.status'), s: 'SYS' },
          { t: t('m06w.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={screenKeys.of('norms', filter)}
          fetchPage={normLinesPage(filter)}
          empty={t('m06.none')}
        >
          {(rows) =>
            rows.map((r) => {
              const pill = versionPill(r.approvalState, r.effectiveTo, today);
              return (
                <tr key={r.id}>
                  <td>
                    <b>{r.depot}</b>
                  </td>
                  <td>{r.mouReference ?? '—'}</td>
                  <td className="num">{figure(r.minLabourNumber)}</td>
                  <td className="num">{r.minSlabWeight}</td>
                  <td>{r.heightNorm ?? '—'}</td>
                  <td>{r.leadNorm ?? '—'}</td>
                  <td>{date(r.effectiveFrom)}</td>
                  <td>
                    <span className={`pill ${pill.tone}`}>{t(pill.key)}</span>
                  </td>
                  <td>
                    <RowActs
                      viewName={`${t('common.view')} ${r.depot}`}
                      editName={`${t('common.edit')} ${r.depot}`}
                      onView={() => setViewing(r)}
                      onEdit={hqOnly ? maintain : undefined}
                      editWhy={t('m05w.why.edit')}
                      onApprove={approve ? () => approve(r.approvalState === 'PENDING_APPROVAL') : undefined}
                    />
                  </td>
                </tr>
              );
            })
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <Tbl
          title={t('m06w.slabs')}
          acts={
            hqOnly ? (
              <Act label={t('m06w.maintain')} kind="sm" onClick={maintain} />
            ) : (
              <Locked label={t('m06w.maintain')} kind="sm" why={why} />
            )
          }
          cols={[
            { t: t('m06w.col.band'), s: 'RULE' },
            { t: t('m06w.col.range'), s: 'ENT' },
            { t: t('m06w.col.rate'), s: 'ENT', num: true },
            { t: t('m06w.col.step'), s: 'RULE', num: true },
            { t: t('m06w.col.bag'), s: 'ENT' },
            { t: t('m06w.col.status'), s: 'SYS' },
          ]}
        >
          {(Array.isArray(slabs.data) ? slabs.data : []).map((s) => (
            <tr key={`${s.table}-${s.order}`}>
              <td>{t(`m06.band.${s.table}`)}</td>
              <td>{rangeText(s.from, s.to, t('m06w.andAbove'))}</td>
              <td className="num">{s.ratePercent}%</td>
              <td className="num">{s.stepSize ?? '—'}</td>
              <td>{t(`m06w.bag.${s.table}`)}</td>
              <td>
                <span className="pill p-ok">{t('m06w.defined')}</span>
              </td>
            </tr>
          ))}
        </Tbl>
        <KvCard
          title={t('m06w.formula')}
          rows={[
            { k: t('m06w.fx.bpl'), v: 'BPL = (TB × MLN) / (MLSW × TPL)', s: 'CALC' },
            { k: t('m06w.fx.bemsl'), v: 'BEMSL = BPL − MLN', s: 'CALC' },
            { k: t('m06w.fx.odw'), v: 'ODW = Basic Pay / 26', s: 'CALC' },
            { k: t('m06w.fx.pbw'), v: 'PBW = ((HRA + DA) × 12/365 + ODW) / MLN', s: 'CALC' },
            { k: t('m06w.fx.tpbw'), v: 'TPBW = (R / 100) × PBW + PBW', s: 'CALC' },
            { k: t('m06w.fx.above'), v: 'TPBW1 × step + TPBW2 × step + …', s: 'CALC' },
            { k: t('m06w.fx.height'), v: t('m06w.fx.heightV'), s: 'CALC' },
            { k: t('m06w.fx.sardar'), v: t('m06w.fx.sardarV'), s: 'RULE' },
          ]}
        />
      </div>
      {viewing && (
        <ViewDialog
          screen={t('screen.M-06')}
          onClose={() => setViewing(null)}
          rows={[
            { k: t('m06w.col.depot'), v: viewing.depot, s: 'HRMS' },
            { k: t('m06w.col.mou'), v: viewing.mouReference ?? '—', s: 'ENT' },
            { k: t('m06w.col.mln'), v: figure(viewing.minLabourNumber), s: 'ENT' },
            { k: t('m06w.col.mlsw'), v: viewing.minSlabWeight, s: 'ENT' },
            { k: t('m06w.col.height'), v: viewing.heightNorm ?? '—', s: 'ENT' },
            { k: t('m06w.col.lead'), v: viewing.leadNorm ?? '—', s: 'ENT' },
            { k: t('m06w.col.from'), v: date(viewing.effectiveFrom), s: 'ENT' },
          ]}
        />
      )}
      {mouOpen && (
        <ViewDialog
          screen={t('m06w.mouRegister')}
          onClose={() => setMouOpen(false)}
          rows={(Array.isArray(circulars.data) ? circulars.data : []).map((c) => ({
            k: c.number,
            v: c.subject ?? '—',
            s: 'ENT',
          }))}
        />
      )}
      <RegisterSection
        id="m06-register"
        label={t('m06w.registerClosed')}
        open={register}
        onToggle={setRegister}
      >
        {register && (
          <NormRegister
            key={startNew ? JSON.stringify(startNew) : 'list'}
            entry={startNew}
            depots={depots.data}
          />
        )}
      </RegisterSection>
      <Problem error={firstNorm.error ?? summary.error ?? slabs.error} />
    </div>
  );
}
