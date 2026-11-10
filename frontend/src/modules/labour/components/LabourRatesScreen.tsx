'use client';

import { type ReactNode, useRef, useState } from 'react';
import { RegisterSection, SimulateAct, useMasterRights, useRouteApprove } from '@/shared/ui/masterParts';
import { useI18n } from '@/shared/i18n';
import { useReference } from '@/shared/reference';
import { businessToday } from '@/shared/time';
import { date, ExportButton, Problem, ServerPagination, useToast } from '@/shared/ui';
import { Act, Explained, Locked, RowActs, Tbl, Toolbar, ViewDialog, type KvRow } from '@/shared/ui/wireframe';
import type { RateSet } from '../api/labourApi';
import type { AsorLine, MinWageLine, SorLine } from '../api/labourScreenApi';
import {
  type AsorEntry,
  asorEntryReady,
  byRole,
  figure,
  rupees,
  setInForce,
  versionPill,
} from '../business/labourScreenRules';
import { useEngine } from '../orchestration/captureQueries';
import {
  asorPage,
  ratesExportUrl,
  screenKeys,
  sorPage,
  useMinWages,
  useRateSets,
} from '../orchestration/screenQueries';
import { TypeSelect } from './CaptureParts';
import { LabourRates as RateRegister, type NwnpPrefill } from './LabourRates';

interface ParameterRow {
  key: string;
  name: ReactNode;
  value: ReactNode;
  third: ReactNode;
  from: string | null;
  pill: { key: string; tone: string };
  /** An engine rule, not a rate: the drawn Edit stays off with this reason. */
  rule?: string;
}

const EMPTY: AsorEntry = { effective: '', piece: '', sardar: '', mandal: '', handling: '', ancillary: '' };

/**
 * M-05 Labour Rate Tables as Wireframes v6.1 draws it, one view per engine (OD-121; SRS-3-13, SRS-4-11, SRS-5-1,
 * SRS-5-2, SRS-5-3): the Departmental wage parameters, the DPS wage basis, and for NWNP the new ASOR form with the
 * ASOR, minimum wage and SOR tables. Only Headquarters sets labour rates: the Rule Administrator proposes, the Rule
 * Approver approves (A-10 revised); a set's every value is entered in the closed register, where the drawn New Rate
 * Set and the drawn ASOR form lead (the depot, the operations and their units are not on the drawing).
 */
export function LabourRates() {
  const { t } = useI18n();
  const toast = useToast();
  const rights = useMasterRights('M-05');
  const approve = useRouteApprove('M-05');
  const reference = useReference();
  const today = businessToday();
  const { type, chosen, choose } = useEngine();
  const nwnp = type === 'NWNP_LABOUR';
  const dps = type === 'DPS_LABOUR';
  const sets = useRateSets(type, !nwnp);
  const minWages = useMinWages(!(type === 'DEPT_LABOUR'));
  const [register, setRegister] = useState(false);
  const [startNew, setStartNew] = useState(false);
  const [prefill, setPrefill] = useState<NwnpPrefill | null>(null);
  const [entry, setEntry] = useState<AsorEntry>(EMPTY);
  const [checked, setChecked] = useState(false);
  const [viewing, setViewing] = useState<KvRow[] | null>(null);
  const form = useRef<HTMLDivElement>(null);
  const inForce = setInForce(sets.data?.items, today);
  const notConfigured = t('m05.notConfigured');

  /** New Rate Set: the ASOR form for NWNP (scrollToForm), the set's full entry otherwise. */
  function newSet() {
    if (nwnp) {
      form.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
      form.current?.querySelector('input')?.focus();
      return;
    }
    if (!rights.mayCreate) {
      toast(rights.entryWhy ?? t('mst.why.notMaker'));
      return;
    }
    setStartNew(true);
    setRegister(true);
  }

  /** The drawn Save carries the entered values to the full proposal, which adds depot and operations. */
  function saveAsor() {
    setChecked(true);
    if (!asorEntryReady(entry)) {
      toast(t('m05w.correct'));
      return;
    }
    if (!rights.mayCreate) {
      toast(rights.entryWhy ?? t('mst.why.notMaker'));
      return;
    }
    setPrefill({ ...entry });
    setRegister(true);
    toast(t('m05w.completeInRegister'));
  }

  /** A rate never changes in place: a row's Edit opens a new set, for the role that may propose one. */
  function rowEdit() {
    if (rights.mayCreate) newSet();
    else toast(rights.entryWhy ?? t('mst.why.notMaker'));
  }

  const parameterActs = (row: ParameterRow, pending: boolean) => (
    <RowActs
      viewName={`${t('common.view')} ${row.key}`}
      editName={`${t('common.edit')} ${row.key}`}
      onView={() =>
        setViewing([
          { k: t('m05w.col.parameter'), v: row.name, s: 'ENT' },
          { k: t('m05w.col.value'), v: row.value, s: 'ENT' },
          { k: dps ? t('m05w.col.source') : t('m05w.col.applies'), v: row.third, s: 'RULE' },
          { k: t('m05w.col.from'), v: row.from ? date(row.from) : '—', s: 'ENT' },
        ])
      }
      onEdit={!row.rule && rights.drawn ? rowEdit : undefined}
      editWhy={row.rule ?? t('m05w.why.edit')}
      onApprove={approve ? () => approve(pending) : undefined}
    />
  );

  const parameterTable = (title: string, third: string, rows: ParameterRow[], pending: boolean) => (
    <Tbl
      title={title}
      acts={<Act label={t('m05w.history')} kind="sm" onClick={() => setRegister(true)} />}
      cols={[
        { t: t('m05w.col.parameter'), s: 'ENT' },
        { t: t('m05w.col.value'), s: 'ENT' },
        { t: third, s: 'RULE' },
        { t: t('m05w.col.from'), s: 'ENT' },
        { t: t('m05w.col.status'), s: 'SYS' },
        { t: t('m05w.col.actions'), s: 'SYS' },
      ]}
    >
      {rows.map((r) => (
        <tr key={r.key}>
          <td>
            <b>{r.name}</b>
          </td>
          <td>{r.value}</td>
          <td>{r.third}</td>
          <td>{r.from ? date(r.from) : '—'}</td>
          <td>
            <span className={`pill ${r.pill.tone}`}>{t(r.pill.key)}</span>
          </td>
          <td>{parameterActs(r, pending)}</td>
        </tr>
      ))}
    </Tbl>
  );

  const entryField = (key: keyof AsorEntry, label: string, placeholder?: string) => {
    const bad = checked && (entry[key].trim() === '' || Number(entry[key]) < 0);
    return (
      <div className={`rf-field${bad ? ' err' : ''}`}>
        <label>
          {label} <span className="req">*</span>
        </label>
        <input
          type={key === 'effective' ? 'date' : 'number'}
          min={key === 'effective' ? undefined : 0}
          step={key === 'piece' ? '0.01' : key === 'effective' ? undefined : '1'}
          placeholder={placeholder}
          value={entry[key]}
          onChange={(e) => setEntry({ ...entry, [key]: e.target.value })}
        />
      </div>
    );
  };

  const hasPending = (sets.data?.items ?? []).some((s: RateSet) => s.approvalState === 'PENDING_APPROVAL');
  const locTypes = reference.options('NWNP_LOC_TYPE');
  const byLoc = new Map<string, MinWageLine>(
    (Array.isArray(minWages.data) ? minWages.data : []).map((w) => [w.locationType, w]),
  );

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <SimulateAct label={t('m05w.simulate')} />
        {rights.drawn ? (
          <Act label={t('m05w.newSet')} kind="pri" onClick={newSet} />
        ) : (
          <Locked label={t('m05w.newSet')} kind="pri" why={t('m05w.why.newSet')} />
        )}
      </div>
      <Toolbar
        exportControl={
          rights.mayExport ? (
            <ExportButton href={ratesExportUrl(type)} what={t('screen.M-05')} label={t('wf.export')} />
          ) : (
            <Explained label={t('wf.export')} why={t('lw.why.export')} />
          )
        }
      >
        <TypeSelect value={type} onChange={choose} />
      </Toolbar>
      {type === 'DEPT_LABOUR' &&
        parameterTable(t('m05w.dept'), t('m05w.col.applies'), deptRows(t, inForce, today), hasPending)}
      {dps &&
        parameterTable(
          t('m05w.dps'),
          t('m05w.col.source'),
          dpsRows(t, inForce, today, byLoc.get('A') ?? null, notConfigured),
          hasPending,
        )}
      {nwnp && (
        <>
          <div className="card fl-mb" ref={form}>
            <div className="hd">
              <h3>{t('m05w.asorForm')}</h3>
            </div>
            <div className="bd">
              {rights.drawn ? (
                <>
                  <div className="rf-grid">
                    {entryField('effective', t('m05w.f.effective'))}
                    {entryField('piece', t('m05w.f.piece'), t('m05w.ph.piece'))}
                    {entryField('sardar', t('m05w.f.sardar'), t('m05w.ph.sardar'))}
                    {entryField('mandal', t('m05w.f.mandal'), t('m05w.ph.mandal'))}
                    {entryField('handling', t('m05w.f.handling'), t('m05w.ph.handling'))}
                    {entryField('ancillary', t('m05w.f.ancillary'), t('m05w.ph.ancillary'))}
                  </div>
                  <div className="rf-actbar">
                    <button type="button" className="btn pri" onClick={saveAsor}>
                      {t('m05w.save')}
                    </button>
                  </div>
                </>
              ) : (
                <p className="sub2">{t('m05w.entryAdmin')}</p>
              )}
            </div>
          </div>
          <Tbl
            title={t('m05w.asor')}
            acts={<Act label={t('m05w.history')} kind="sm" onClick={() => setRegister(true)} />}
            cols={[
              { t: t('m05w.col.effective'), s: 'ENT' },
              { t: t('m05w.col.piece'), s: 'ENT', num: true },
              { t: t('m05w.col.sardar'), s: 'ENT', num: true },
              { t: t('m05w.col.mandal'), s: 'ENT', num: true },
              { t: t('m05w.col.handling'), s: 'ENT', num: true },
              { t: t('m05w.col.ancillary'), s: 'ENT', num: true },
              { t: t('m05w.col.status'), s: 'SYS' },
              { t: t('m05w.col.actions'), s: 'SYS' },
            ]}
          >
            <ServerPagination queryKey={screenKeys.of('asor')} fetchPage={asorPage} empty={t('m05.none')}>
              {(rows: AsorLine[]) =>
                rows.map((r) => {
                  const pill = versionPill(r.approvalState, r.effectiveTo, today);
                  const superseded = pill.key === 'lw.state.SUPERSEDED';
                  return (
                    <tr key={r.id}>
                      <td>
                        {date(r.effectiveFrom)}
                        <div className="sub2">{r.depot}</div>
                      </td>
                      <td className="num">{r.pieceOfRate ?? '—'}</td>
                      <td className="num">{figure(r.mgw.SARDAR)}</td>
                      <td className="num">{figure(r.mgw.MANDAL)}</td>
                      <td className="num">{figure(r.mgw.HANDLING)}</td>
                      <td className="num">{figure(r.mgw.ANCILLARY)}</td>
                      <td>
                        <span className={`pill ${pill.tone}`}>{t(pill.key)}</span>
                      </td>
                      <td>
                        <RowActs
                          viewName={`${t('common.view')} ${r.code} ${r.effectiveFrom}`}
                          editName={`${t('common.edit')} ${r.code} ${r.effectiveFrom}`}
                          onView={() =>
                            setViewing([
                              { k: t('m05w.col.effective'), v: date(r.effectiveFrom), s: 'ENT' },
                              { k: t('m02.depot'), v: r.depot, s: 'ENT' },
                              { k: t('m05w.col.piece'), v: r.pieceOfRate ?? '—', s: 'ENT' },
                              { k: t('m05w.col.status'), v: t(pill.key), s: 'SYS' },
                            ])
                          }
                          onEdit={!superseded && rights.drawn ? rowEdit : undefined}
                          editWhy={superseded ? t('m05w.why.superseded') : t('m05w.why.edit')}
                          onApprove={
                            approve ? () => approve(r.approvalState === 'PENDING_APPROVAL') : undefined
                          }
                        />
                      </td>
                    </tr>
                  );
                })
              }
            </ServerPagination>
          </Tbl>
          <div className="fl-mt">
            <Tbl
              title={t('m05w.minWage')}
              cols={[
                { t: t('m05w.col.locType'), s: 'ENT' },
                { t: t('m05w.col.basic'), s: 'ENT', num: true },
                { t: t('m05w.col.vda'), s: 'ENT', num: true },
                { t: t('m05w.col.mgwCalc'), s: 'CALC', num: true },
                { t: t('m05w.col.from'), s: 'ENT' },
                { t: t('m05w.col.status'), s: 'SYS' },
                { t: t('m05w.col.actions'), s: 'SYS' },
              ]}
            >
              {locTypes.map((o) => {
                const w = byLoc.get(o.value);
                const pill = w
                  ? versionPill(w.approvalState, w.effectiveTo, today)
                  : { key: 'm05.notConfigured', tone: 'p-bad' };
                return (
                  <tr key={o.value}>
                    <td>
                      <b>{o.label}</b>
                    </td>
                    <td className="num">{figure(w?.basicWage)}</td>
                    <td className="num">{figure(w?.vda)}</td>
                    <td className="num">{figure(w?.mgw)}</td>
                    <td>{w ? date(w.effectiveFrom) : '—'}</td>
                    <td>
                      <span className={`pill ${pill.tone}`}>{t(pill.key)}</span>
                    </td>
                    <td>
                      <RowActs
                        viewName={`${t('common.view')} ${o.value}`}
                        editName={`${t('common.edit')} ${o.value}`}
                        onView={() =>
                          setViewing([
                            { k: t('m05w.col.locType'), v: o.label, s: 'ENT' },
                            { k: t('m05w.col.basic'), v: figure(w?.basicWage), s: 'ENT' },
                            { k: t('m05w.col.vda'), v: figure(w?.vda), s: 'ENT' },
                            { k: t('m05w.col.mgwCalc'), v: figure(w?.mgw), s: 'CALC' },
                          ])
                        }
                        onEdit={rights.drawn ? () => toast(t('m05.why.nwnpMinWage')) : undefined}
                        editWhy={t('m05w.why.edit')}
                        onApprove={approve ? () => approve(false) : undefined}
                      />
                    </td>
                  </tr>
                );
              })}
            </Tbl>
          </div>
          <div className="fl-mt">
            <Tbl
              title={t('m05w.sor')}
              cols={[
                { t: t('m05w.col.operation'), s: 'ENT' },
                { t: t('m05w.col.bag'), s: 'ENT' },
                { t: t('m05w.col.unit'), s: 'ENT' },
                { t: t('m05w.col.rate'), s: 'ENT', num: true },
                { t: t('m05w.col.from'), s: 'ENT' },
                { t: t('m05w.col.status'), s: 'SYS' },
                { t: t('m05w.col.actions'), s: 'SYS' },
              ]}
            >
              <ServerPagination queryKey={screenKeys.of('sor')} fetchPage={sorPage} empty={t('m05.none')}>
                {(rows: SorLine[]) =>
                  rows.map((r) => {
                    const pill = versionPill(r.approvalState, r.effectiveTo, today);
                    return (
                      <tr key={r.id}>
                        <td>
                          {r.operationCode} - {r.operation}
                        </td>
                        <td>{t('m05w.bagNotHeld')}</td>
                        <td>{r.unit ?? '—'}</td>
                        <td className="num">{figure(r.rate)}</td>
                        <td>{date(r.effectiveFrom)}</td>
                        <td>
                          <span className={`pill ${pill.tone}`}>{t(pill.key)}</span>
                        </td>
                        <td>
                          <RowActs
                            viewName={`${t('common.view')} ${r.operationCode}`}
                            editName={`${t('common.edit')} ${r.operationCode}`}
                            onView={() =>
                              setViewing([
                                { k: t('m05w.col.operation'), v: r.operation, s: 'ENT' },
                                { k: t('m05w.col.unit'), v: r.unit ?? '—', s: 'ENT' },
                                { k: t('m05w.col.rate'), v: figure(r.rate), s: 'ENT' },
                                { k: t('m05w.col.from'), v: date(r.effectiveFrom), s: 'ENT' },
                              ])
                            }
                            onEdit={rights.drawn ? rowEdit : undefined}
                            editWhy={t('m05w.why.edit')}
                            onApprove={
                              approve ? () => approve(r.approvalState === 'PENDING_APPROVAL') : undefined
                            }
                          />
                        </td>
                      </tr>
                    );
                  })
                }
              </ServerPagination>
            </Tbl>
          </div>
        </>
      )}
      {viewing && <ViewDialog screen={t('screen.M-05')} rows={viewing} onClose={() => setViewing(null)} />}
      <RegisterSection id="m05-register" label={t('m05w.register')} open={register} onToggle={setRegister}>
        {register && (
          <RateRegister
            key={`${chosen ?? ''}|${String(startNew)}|${prefill?.effective ?? ''}`}
            payrollType={chosen ?? undefined}
            startNew={startNew}
            prefill={prefill}
          />
        )}
      </RegisterSection>
      <Problem error={sets.error ?? minWages.error} />
    </div>
  );
}

type T = (key: string, args?: (string | number)[]) => string;

/** M-05 Departmental as drawn: the set in force, then the NPH parameters and the CPF rule (SRS-3-13). */
function deptRows(t: T, set: RateSet | null, today: string): ParameterRow[] {
  const applies = t('m05w.allDepots');
  const from = set?.effectiveFrom ?? null;
  const pill = set
    ? versionPill(set.approvalState, set.effectiveTo, today)
    : { key: 'm05.notConfigured', tone: 'p-bad' };
  const rows: ParameterRow[] = set
    ? byRole(set.rates).map((r) => ({
        key: `BASIC-${r.designationId}`,
        name: t('m05w.basicOf', [r.designation]),
        value: figure(r.basicWage),
        third: applies,
        from,
        pill,
      }))
    : [
        {
          key: 'BASIC',
          name: t('m05w.basicNone'),
          value: t('m05.notConfigured'),
          third: applies,
          from: null,
          pill,
        },
      ];
  return [
    ...rows,
    {
      key: 'NPH-BASIC',
      name: t('m05w.nphBasic'),
      value: t('m05w.nphPolicy'),
      third: t('m05w.deptOnly'),
      from,
      pill,
    },
    {
      key: 'NPH-DA',
      name: t('m05w.nphDa'),
      value: t('m05w.nphPolicy'),
      third: t('m05w.deptOnly'),
      from,
      pill,
    },
    {
      key: 'CPF-NPH',
      name: t('m05w.cpfNph'),
      value: <b>{t('m05w.notDeducted')}</b>,
      third: t('m05w.deptRule'),
      from,
      pill,
      rule: t('m05w.why.rule'),
    },
  ];
}

/** M-05 DPS as drawn: the wage basis from the set in force; an unset value stays a setting (R-RULE, CF-49). */
function dpsRows(
  t: T,
  set: RateSet | null,
  today: string,
  location: MinWageLine | null,
  none: string,
): ParameterRow[] {
  const from = set?.effectiveFrom ?? null;
  const pill = set
    ? versionPill(set.approvalState, set.effectiveTo, today)
    : { key: 'm05.notConfigured', tone: 'p-bad' };
  const setting = (value: number | null | undefined, text: string) =>
    value === null || value === undefined ? <span className="pill p-info">{text}</span> : rupees(value, none);
  const rule = { key: 'lw.state.ACTIVE', tone: 'p-ok' };
  return [
    {
      key: 'MGW',
      name: t('m05w.dps.mgw'),
      value: location?.mgw ? t('m05w.dps.mgwV', [Number(location.mgw).toLocaleString('en-IN')]) : none,
      third: t('m05w.dps.mgwSource'),
      from: location?.effectiveFrom ?? null,
      pill: location ? rule : { key: 'm05.notConfigured', tone: 'p-bad' },
    },
    ...(set ? byRole(set.rates) : []).map((r) => ({
      key: `DAY-${r.designationId}`,
      name: t('m05w.dps.day', [r.designation]),
      value: t('m05w.dps.dayV', [figure(r.mgw)]),
      third: t('m05w.dps.daySource'),
      from,
      pill,
    })),
    {
      key: 'IR',
      name: t('m05w.dps.ir'),
      value: setting(set?.interimRelief, t('m05w.dps.setting')),
      third: t('m05w.dps.irSource'),
      from: set?.interimRelief ? from : null,
      pill,
    },
    {
      key: 'GOI',
      name: t('m05w.dps.goi'),
      value: setting(set?.goiRate, t('m05w.dps.setting')),
      third: set?.goiRateSource ?? t('m05w.dps.goiSource'),
      from: set?.goiRate ? from : null,
      pill,
    },
    {
      key: 'COMPARE',
      name: t('m05w.dps.compare'),
      value: t('m05w.dps.compareV'),
      third: '§9.4.2',
      from,
      pill: rule,
    },
    {
      key: 'OTA',
      name: t('m05w.dps.ota'),
      value: t('m05w.dps.otaV'),
      third: '§9.4.2',
      from,
      pill: rule,
    },
    {
      key: 'ADVANCE',
      name: t('m05w.dps.advance'),
      value: t('m05w.dps.advanceV'),
      third: '§9.4.2',
      from,
      pill: rule,
    },
  ];
}
