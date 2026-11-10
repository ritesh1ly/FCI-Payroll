'use client';

import { useState } from 'react';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { can, useMe } from '@/shared/session';
import { date, Problem, ServerPagination, useToast } from '@/shared/ui';
import { RegisterSection, SimulateAct, useApproveRow, useMasterRights } from '@/shared/ui/masterParts';
import {
  Act,
  Explained,
  KvCard,
  Locked,
  orderCapturePanel,
  Panel,
  RowActs,
  Tbl,
  Tiles,
  ViewDialog,
  WfTile,
} from '@/shared/ui/wireframe';
import type { FloorRow } from '../api/mdmApi';
import {
  mdmKeys,
  mdmPages,
  useExclusions,
  useFloorGuardCount,
  useProposeExclusions,
  useProposeFloor,
} from '../orchestration/mdmQueries';
import { NetPayFloors } from './NetPayFloors';

/** The drawn "All Payroll Types": a list value with no SRS row, since a floor is held per payroll type (WF-1806). */
const ALL_TYPES = 'ALL';

/**
 * M-14 Penalty &amp; Recovery Rule Configuration as Wireframes v6.1 draws it (OD-121; SRS-14-9, SRS-16-13, FRS §6.18).
 * The tiles, both drawn entry cards and rule tables, the panels; the dated versions, the basis, the deduction ceiling
 * and the tick list stay in the closed register. A floor proposed here is an amount (the drawn Floor Amount); a percent
 * basis stays with the register (M14-4). No range is enforced: the drawn 50,000 has no source (WF-1309). Payroll Type
 * Scope and Excluded From read their M-16 lists (OD-123). The exclusion of a code is one flag on M-07 with no date or
 * Excluded From (CLR-48): for a role that may propose, the drawn date is off with the reason and waits for the owner
 * (ALT-4), and Excluded From is rule-resolved and read-only for every role (SRS l.1389, R-SRC). The System Administrator
 * sees the entry as drawn and is told why (OD-135, WF-208).
 */
export function PenaltyRulesScreen() {
  const { t } = useI18n();
  const me = useMe();
  const toast = useToast();
  const rights = useMasterRights('M-14');
  const approve = useApproveRow();
  const exclusions = useExclusions();
  const guard = useFloorGuardCount();
  const propose = useProposeFloor();
  const proposeExclusions = useProposeExclusions();
  const mayExclude = can(me.data, 'M-14', 'EDIT');
  const [register, setRegister] = useState(false);
  const [viewing, setViewing] = useState<FloorRow | null>(null);
  const [floors, setFloors] = useState<FloorRow[]>([]);
  const [floor, setFloor] = useState({ type: '', amount: '', from: '', reason: '' });
  const [exclusion, setExclusion] = useState({ code: '', other: '', reason: '' });
  // Typed only by a role that may not propose: a proposal carries the code, the name stays M-07's (SRS l.1388).
  const [typedName, setTypedName] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const components = exclusions.data?.components ?? [];
  const excluded = components.filter((c) => c.excluded);
  const pendingList = exclusions.data?.pending ?? null;
  const pendingFloors = floors.filter((r) => r.pending !== null).length;
  const pendingCount = pendingFloors + (pendingList ? 1 : 0);
  const code = exclusion.code === 'OTHER' ? exclusion.other.trim() : exclusion.code;
  const component = components.find((c) => String(c.payCode) === code);

  async function saveFloor() {
    if (!rights.mayCreate) {
      toast(rights.entryWhy ?? t('m14w.why.admin'));
      return;
    }
    setError(null);
    try {
      await propose.mutateAsync({
        type: floor.type,
        body: {
          effectiveFrom: floor.from,
          floorBasis: 'ABSOLUTE_AMOUNT',
          floorValue: Number(floor.amount),
          deductionCeilingPct: null,
          sourceInstrument: null,
          confirmed: false,
          reason: floor.reason,
        },
      });
      toast(t('mst.submitted', [t(`m14.type.${floor.type}`)]));
      setFloor({ type: '', amount: '', from: '', reason: '' });
    } catch (cause) {
      if (!(cause instanceof ApiError && cause.messageKey === 'otp.cancelled')) setError(cause);
    }
  }

  async function saveExclusion() {
    if (!mayExclude) {
      toast(rights.entryWhy ?? t('m14w.why.admin'));
      return;
    }
    setError(null);
    try {
      await proposeExclusions.mutateAsync({
        payCodes: [...excluded.map((c) => c.payCode as number), Number(code)],
        reason: exclusion.reason,
      });
      toast(t('m14.exclusionsSubmitted'));
      setExclusion({ code: '', other: '', reason: '' });
    } catch (cause) {
      if (!(cause instanceof ApiError && cause.messageKey === 'otp.cancelled')) setError(cause);
    }
  }

  const supersede = rights.checker ? (
    <Explained label={t('m14w.supersede')} kind="sm" why={t('m14w.why.supersede')} />
  ) : (
    <Locked label={t('m14w.supersede')} kind="sm" why={t('m14w.why.checker')} />
  );

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <Act label={t('wf.historyAct')} onClick={() => setRegister(true)} />
        <SimulateAct label={t('m14w.simulate')} />
        {rights.drawn ? (
          <>
            <Act
              label={t('m14w.newFloor')}
              kind="pri"
              onClick={() => document.getElementById('penaltyFloorForm')?.scrollIntoView()}
            />
            <Act
              label={t('m14w.newExclusion')}
              kind="pri"
              onClick={() => document.getElementById('penaltyExclusionForm')?.scrollIntoView()}
            />
          </>
        ) : (
          <>
            <Locked label={t('m14w.newFloor')} why={t('m14w.why.admin')} />
            <Locked label={t('m14w.newExclusion')} why={t('m14w.why.admin')} />
          </>
        )}
      </div>
      <Problem error={error ?? exclusions.error ?? guard.error} />
      <Tiles>
        <WfTile
          icon="⛔"
          label={t('m14w.tile.types')}
          src="RULE"
          big={floors.filter((r) => r.current !== null).length}
          sub={t('m14w.tile.types.sub')}
        />
        <WfTile
          icon="🚫"
          tone="pur"
          label={t('m14w.tile.codes')}
          src="RULE"
          big={excluded.length}
          sub={t('m14w.tile.codes.sub')}
        />
        <WfTile
          icon="✍️"
          tone="warn"
          label={t('m14w.tile.pending')}
          src="SYS"
          big={pendingCount}
          sub={t('m14w.tile.pending.sub')}
        />
        <WfTile
          icon="⚠️"
          tone="bad"
          bad
          label={t('m14w.tile.guard')}
          src="CALC"
          big={guard.data ?? '—'}
          sub={t('m14w.tile.guard.sub')}
        />
      </Tiles>
      <div className="card fl-mb" id="penaltyFloorForm">
        <div className="hd">
          <h3>{t('m14w.floorForm')}</h3>
        </div>
        <div className="bd">
          {!rights.drawn ? (
            <p className="sub2">{t('m14w.floorNote')}</p>
          ) : (
            <>
              <p className="sub2">{t('m14w.floorHint')}</p>
              <div className="rf-grid">
                <div className="rf-field">
                  <label htmlFor="pf_scope">
                    {t('m14w.f.scope')} <span className="req">*</span>
                  </label>
                  {/* The M-16 list as drawn (OD-123). "All Payroll Types" cannot be proposed: WF-1806, Q-04-2. */}
                  <select
                    id="pf_scope"
                    value={floor.type}
                    onChange={(e) => setFloor({ ...floor, type: e.target.value })}
                  >
                    <option value="">{t('m15w.select')}</option>
                    <ReferenceOptions list="PENALTY_PAYROLL_SCOPE" />
                  </select>
                </div>
                <div className="rf-field" title={t('m14w.f.amount.hint')}>
                  <label htmlFor="pf_amt">
                    {t('m14w.f.amount')} <span className="req">*</span>
                  </label>
                  <input
                    id="pf_amt"
                    type="number"
                    min={0}
                    step="0.01"
                    placeholder={t('m14w.f.amount.placeholder')}
                    value={floor.amount}
                    onChange={(e) => setFloor({ ...floor, amount: e.target.value })}
                  />
                </div>
                <div className="rf-field">
                  <label htmlFor="pf_eff">
                    {t('m14w.f.from')} <span className="req">*</span>
                  </label>
                  <input
                    id="pf_eff"
                    type="date"
                    value={floor.from}
                    onChange={(e) => setFloor({ ...floor, from: e.target.value })}
                  />
                </div>
                <div className="rf-field span2">
                  <label htmlFor="pf_reason">
                    {t('m14w.f.reason')} <span className="req">*</span>
                  </label>
                  <input
                    id="pf_reason"
                    maxLength={500}
                    placeholder={t('m14w.f.reason.floor')}
                    value={floor.reason}
                    onChange={(e) => setFloor({ ...floor, reason: e.target.value })}
                  />
                </div>
              </div>
              <div className="rf-actbar">
                <button
                  type="button"
                  className="btn pri"
                  disabled={
                    rights.mayCreate &&
                    (floor.type === '' ||
                      floor.type === ALL_TYPES ||
                      floor.amount === '' ||
                      floor.from === '' ||
                      floor.reason.trim() === '' ||
                      propose.isPending)
                  }
                  title={floor.type === ALL_TYPES ? t('m14w.why.allTypes') : undefined}
                  data-why={floor.type === ALL_TYPES ? t('m14w.why.allTypes') : undefined}
                  onClick={() => void saveFloor()}
                >
                  {t('m14w.save')}
                </button>
              </div>
            </>
          )}
        </div>
      </div>
      <Tbl
        title={t('m14w.floors')}
        acts={supersede}
        cols={[
          { t: t('m14w.col.scope'), s: 'ENT' },
          { t: t('m14w.col.amount'), s: 'RULE', num: true },
          { t: t('m14w.col.from'), s: 'ENT' },
          { t: t('m14w.col.reason'), s: 'ENT' },
          { t: t('m14w.col.status'), s: 'SYS' },
          { t: t('m04.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination queryKey={mdmKeys.floors} fetchPage={mdmPages.floors} personal={false}>
          {(rows) => {
            if (rows !== floors && JSON.stringify(rows) !== JSON.stringify(floors)) {
              queueMicrotask(() => setFloors(rows));
            }
            return rows.map((r) => {
              const v = r.pending ?? r.current;
              return (
                <tr key={r.payrollType}>
                  <td>{t(`m14.type.${r.payrollType}`)}</td>
                  <td className="num">{v?.floorValue ?? '—'}</td>
                  <td>{v ? date(v.effectiveFrom) : '—'}</td>
                  <td>{v?.sourceInstrument ?? '—'}</td>
                  <td>
                    <span className={`pill ${r.pending ? 'p-warn' : r.current ? 'p-ok' : 'p-bad'}`}>
                      {t(
                        r.pending
                          ? 'm14w.status.pending'
                          : r.current
                            ? 'm14w.status.active'
                            : 'mst.notConfigured',
                      )}
                    </span>
                  </td>
                  <td>
                    <RowActs
                      viewName={`${t('common.view')} ${r.payrollType}`}
                      editName={`${t('common.edit')} ${r.payrollType}`}
                      onView={() => setViewing(r)}
                      onEdit={
                        rights.drawn
                          ? () => {
                              setFloor({ ...floor, type: r.payrollType });
                              document.getElementById('penaltyFloorForm')?.scrollIntoView();
                            }
                          : undefined
                      }
                      editWhy={t('m14w.why.edit')}
                      onApprove={rights.checker ? () => approve(r.pending !== null) : undefined}
                    />
                  </td>
                </tr>
              );
            });
          }}
        </ServerPagination>
      </Tbl>
      <div className="card fl-mt fl-mb" id="penaltyExclusionForm">
        <div className="hd">
          <h3>{t('m14w.excForm')}</h3>
        </div>
        <div className="bd">
          {!rights.drawn ? (
            <p className="sub2">{t('m14w.excNote')}</p>
          ) : (
            <>
              <p className="sub2">{t('m14w.excHint')}</p>
              <div className="rf-grid">
                <div className="rf-field">
                  <label htmlFor="pe_code">
                    {t('m14w.f.code')} <span className="req">*</span>
                  </label>
                  <select
                    id="pe_code"
                    value={exclusion.code}
                    onChange={(e) => setExclusion({ ...exclusion, code: e.target.value })}
                  >
                    <option value="">{t('m15w.select')}</option>
                    {excluded.map((c) => (
                      <option key={String(c.payCode)} value={String(c.payCode)}>
                        {c.payCode}
                      </option>
                    ))}
                    <option value="OTHER">{t('m14w.f.other')}</option>
                  </select>
                  <input
                    id="pe_code_other"
                    aria-label={t('m14w.f.otherCode')}
                    disabled={exclusion.code !== 'OTHER'}
                    inputMode="numeric"
                    value={exclusion.other}
                    onChange={(e) => setExclusion({ ...exclusion, other: e.target.value })}
                  />
                </div>
                <div className="rf-field span2">
                  <label htmlFor="pe_desc">
                    {t('m14w.f.desc')} <span className="req">*</span>
                  </label>
                  {/* SRS l.1388: the code resolves to M-07, so its description is read from there (WF-1311). */}
                  <input
                    id="pe_desc"
                    readOnly={mayExclude}
                    placeholder={t('m14w.f.desc.placeholder')}
                    value={mayExclude ? (component?.name ?? '') : (typedName ?? component?.name ?? '')}
                    onChange={(e) => setTypedName(e.target.value)}
                  />
                </div>
                <div className="rf-field">
                  <label htmlFor="pe_excl">
                    {t('m14w.f.excluded')} <span className="req">*</span>
                  </label>
                  <select
                    id="pe_excl"
                    disabled
                    title={t('m14w.why.noColumn')}
                    data-why={t('m14w.why.noColumn')}
                  >
                    <option value="">{t('m15w.select')}</option>
                    <ReferenceOptions list="PENALTY_EXCLUDED_FROM" />
                  </select>
                </div>
                <div className="rf-field">
                  <label htmlFor="pe_eff">
                    {t('m14w.f.from')} <span className="req">*</span>
                  </label>
                  <input
                    id="pe_eff"
                    type="date"
                    disabled={mayExclude}
                    title={mayExclude ? t('m14w.why.noColumn') : undefined}
                    data-why={mayExclude ? t('m14w.why.noColumn') : undefined}
                  />
                </div>
                <div className="rf-field">
                  <label htmlFor="pe_reason">
                    {t('m14w.f.reason')} <span className="req">*</span>
                  </label>
                  <input
                    id="pe_reason"
                    maxLength={500}
                    placeholder={t('m14w.f.reason.exclusion')}
                    value={exclusion.reason}
                    onChange={(e) => setExclusion({ ...exclusion, reason: e.target.value })}
                  />
                </div>
              </div>
              <div className="rf-actbar">
                <button
                  type="button"
                  className="btn pri"
                  disabled={mayExclude && (component === undefined || exclusion.reason.trim() === '')}
                  onClick={() => void saveExclusion()}
                >
                  {t('m14w.save')}
                </button>
              </div>
            </>
          )}
        </div>
      </div>
      <Tbl
        title={t('m14w.exclusions')}
        acts={supersede}
        cols={[
          { t: t('m14w.col.code'), s: 'ENT' },
          { t: t('m14w.col.desc'), s: 'ENT' },
          { t: t('m14w.col.excluded'), s: 'RULE' },
          { t: t('m14w.col.from'), s: 'ENT' },
          { t: t('m14w.col.status'), s: 'SYS' },
          { t: t('m04.col.actions'), s: 'SYS' },
        ]}
      >
        {excluded.map((c) => (
          <tr key={String(c.payCode)}>
            <td>
              <b>{c.payCode}</b>
            </td>
            <td>{c.name}</td>
            <td>{t('m14w.excludedFrom')}</td>
            <td>—</td>
            <td>
              <span className={`pill ${pendingList ? 'p-warn' : 'p-ok'}`}>
                {t(pendingList ? 'm14w.status.pending' : 'm14w.status.active')}
              </span>
            </td>
            <td>
              <RowActs
                viewName={`${t('common.view')} ${c.payCode}`}
                editName={`${t('common.edit')} ${c.payCode}`}
                onView={() => setRegister(true)}
                onEdit={
                  rights.drawn
                    ? () => document.getElementById('penaltyExclusionForm')?.scrollIntoView()
                    : undefined
                }
                editWhy={t('m14w.why.edit')}
                onApprove={rights.checker ? () => approve(pendingList !== null) : undefined}
              />
            </td>
          </tr>
        ))}
      </Tbl>
      <div className="g2 fl-mt">
        <Panel title={t('pen.cap')} items={orderCapturePanel(t)} />
      </div>
      <div className="g2 fl-mt">
        <Panel
          title={t('pen.ctrl')}
          items={[
            {
              icon: '📐',
              tone: 'warn',
              title: t('pen.ctrl.ceiling'),
              src: 'RULE',
              text: t('pen.ctrl.ceiling.m'),
            },
            {
              icon: '🗓',
              tone: 'info',
              title: t('pen.ctrl.periods'),
              src: 'RULE',
              text: t('pen.ctrl.periods.m'),
              right: <span className="pill p-ok">{t('common.on')}</span>,
            },
            {
              icon: '🧾',
              tone: 'info',
              title: t('pen.ctrl.tax'),
              src: 'CALC',
              text: t('pen.ctrl.tax.m'),
              right: <span className="pill p-ok">{t('common.on')}</span>,
            },
            {
              icon: '↩️',
              tone: 'warn',
              title: t('pen.ctrl.retro'),
              src: 'RULE',
              text: t('pen.ctrl.retro.m'),
              right: <span className="pill p-ok">{'BR-PEN2-006'}</span>,
            },
            {
              icon: '✅',
              tone: 'ok',
              title: t('pen.ctrl.post'),
              src: 'SYS',
              text: t('pen.ctrl.post.m'),
              right: <span className="pill p-ok">{t('common.on')}</span>,
            },
            {
              icon: '⛔',
              tone: 'bad',
              title: t('pen.ctrl.floor'),
              src: 'RULE',
              text: t('m14w.ctrl.floor.m'),
              right: <b>{guard.data ?? '—'}</b>,
            },
            {
              icon: '🚫',
              tone: 'gray',
              title: t('pen.ctrl.supp'),
              src: 'RULE',
              text: t('pen.ctrl.supp.m'),
              right: <span className="pill p-ok">{t('common.on')}</span>,
            },
            {
              icon: '✍️',
              tone: 'warn',
              title: t('m14w.ctrl.mc'),
              src: 'SYS',
              text: t('m14w.ctrl.mc.m'),
              right: <span className="pill p-ok">{t('common.on')}</span>,
            },
          ]}
        />
        <KvCard
          title={t('m14w.consumed')}
          rows={[
            { k: t('m14w.consumed.floor'), v: t('m14w.consumed.floor.v'), s: 'CALC' },
            { k: t('m14w.consumed.scope'), v: t('m14w.consumed.scope.v'), s: 'RULE' },
            { k: t('m14w.consumed.code'), v: t('m14w.consumed.code.v'), s: 'RULE' },
            { k: t('m14w.consumed.excl'), v: t('m14w.consumed.excl.v'), s: 'RULE' },
          ]}
        />
      </div>
      <RegisterSection label={t('m14w.register')} open={register} onToggle={setRegister} id="m14-register">
        {register && <NetPayFloors />}
      </RegisterSection>
      {viewing && (
        <ViewDialog
          screen={t('screen.M-14')}
          rows={[
            { k: t('m14w.col.scope'), v: t(`m14.type.${viewing.payrollType}`), s: 'ENT' },
            { k: t('m14w.col.amount'), v: String(viewing.current?.floorValue ?? '—'), s: 'RULE' },
            {
              k: t('m14w.col.from'),
              v: viewing.current ? date(viewing.current.effectiveFrom) : '—',
              s: 'ENT',
            },
            { k: t('m14w.col.reason'), v: viewing.current?.sourceInstrument ?? '—', s: 'ENT' },
          ]}
          onClose={() => setViewing(null)}
        />
      )}
    </div>
  );
}
