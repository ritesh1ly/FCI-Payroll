'use client';

import { type ReactNode, useMemo, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { can, hqOnlyBlock, useMe } from '@/shared/session';
import {
  amount,
  Card,
  date,
  dateTime,
  Empty,
  entityLabel,
  ExportButton,
  Problem,
  ServerPagination,
  Src,
  Tile,
  useLocalPage,
  useToast,
  TextAreaField,
  withBase,
} from '@/shared/ui';
import { businessToday } from '@/shared/time';
import {
  Act,
  KvCard,
  Explained,
  Locked,
  Panel,
  type PanelItem,
  RowActs,
  Tbl,
  Tiles,
  ViewDialog,
  WfTile,
} from '@/shared/ui/wireframe';
import type { ReadThroughRow, RuleRow, RuleVersionView, ValueShape } from '../api/rulesApi';
import {
  NO_FILTER,
  appendBand,
  canAddBand,
  RULE_STATUS_PILL,
  draftProblems,
  emptyDraft,
  hasPendingChange,
  inRegister,
  isRetrospective,
  matches,
  mayEnd,
  mayPropose,
  notConfigured,
  registerSummary,
  toProposal,
  type BandDraft,
  type CatalogueFilter,
  type StatusFilter,
  type VersionDraft,
} from '../business/ruleRules';
import { useCurrentCirculars } from '../orchestration/mdmQueries';
import { RuleSimulationPanel } from './RuleSimulation';
import {
  catalogueSummaryUrl,
  pendingReportUrl,
  useProposeRuleEnd,
  useProposeRuleVersion,
  useRuleCatalogue,
  useRuleReadThrough,
  useRuleSimulations,
  rulePages,
  ruleKeys,
  useRuleVocabulary,
} from '../orchestration/ruleQueries';

type T = (key: string, args?: readonly unknown[]) => string;

const STATUS_FILTERS: StatusFilter[] = ['ACTIVE', 'NOT_CONFIGURED', 'PENDING', 'READ_ONLY'];

/** The drawn Engine Guard-Rails (FRS §6.12.7), each with its badge and state. */
const GUARD_RAILS: {
  key: string;
  icon: string;
  src: 'RULE' | 'SYS';
  tone: PanelItem['tone'];
  pill?: string;
}[] = [
  { key: 'closed', icon: '♻️', src: 'RULE', tone: 'ok' },
  { key: 'from', icon: '📅', src: 'RULE', tone: 'info', pill: 'on' },
  { key: 'maker', icon: '✍️', src: 'SYS', tone: 'warn', pill: 'on' },
  { key: 'slabs', icon: '📊', src: 'RULE', tone: 'info', pill: 'on' },
  { key: 'successor', icon: '🔗', src: 'RULE', tone: 'warn', pill: 'on' },
  { key: 'type', icon: '🏷', src: 'RULE', tone: 'ok' },
  { key: 'audited', icon: '🔐', src: 'SYS', tone: 'pur', pill: 'auto' },
];

/** A domain with its own master screen, as a catalogue row: read only, its Edit names that screen (M08-04). */
function readThroughRow(r: ReadThroughRow): RuleRow {
  return {
    key: r.key,
    type: r.type,
    domain: 'READ_THROUGH',
    name: r.key,
    description: null,
    payrollTypeScope: null,
    officeScopeId: null,
    officeScopeName: null,
    owningModule: r.screen,
    orderRequired: false,
    active: true,
    maintained: false,
    valueShape: null,
    inForce: null,
    pending: null,
    pendingEnd: null,
    versions: 0,
    enteredOn: r.screen,
    records: r.records,
    heldFrom: r.from,
    readThroughPending: r.pending,
  };
}

function StatusPill({ status }: { status: RuleVersionView['status'] }) {
  const { t } = useI18n();
  return <span className={`pill ${RULE_STATUS_PILL[status]}`}>{t(`m08.status.${status}`)}</span>;
}

/** The value a version holds, as the catalogue shows it. */
function valueText(t: T, shape: ValueShape | null, v: RuleVersionView | null): string {
  if (!v) return '—';
  if (shape === 'SLAB' || v.bands.length > 0) return t('m08.bandCount', [v.bands.length]);
  if (v.formula) return v.formula;
  return v.value === null ? '—' : String(v.value);
}

/** Where a rule applies: its engines and office, or everywhere (KB RE-10). */
function scopeText(t: T, row: RuleRow): string {
  const parts: string[] = [];
  parts.push(
    row.payrollTypeScope
      ? row.payrollTypeScope
          .split(',')
          .map((p) => p.trim())
          .join(', ')
      : t('m08.allEngines'),
  );
  parts.push(
    row.officeScopeName ?? (row.officeScopeId === null ? t('m08.allOffices') : String(row.officeScopeId)),
  );
  return parts.join(' · ');
}

/**
 * M-08 Rule / Parameter Umbrella Catalogue (SRS-19-1 … 21; KB RE-1 … RE-14): every rule with the
 * version in force today, the changes pending on M-08, M-14 and M-15, and the Rule Administrator's
 * proposals (a new version, or an end date without successor). A proposal takes effect only when a
 * Rule Approver, never its proposer, approves it in the inbox. A rule with no version in force is
 * shown as not configured: no value is ever assumed (R-RULE).
 *
 * M-08 itself is drawn as Wireframes v6.1 has it (OD-121): Simulate Impact and New Rule Version over the screen, four
 * tiles with their badges, the Rule Catalogue with View, Edit and the Checker's tick, the domains entered on M-14,
 * M-15 and M-20 as read-only rows (SRS-19-21), the simulation of the pending version with its amounts (BR-RULE-006),
 * Return and Approve and Activate, and the Engine Guard-Rails. The decision itself stays in the Approvals Inbox: the
 * two drawn buttons open it, there is no second decision path (M08-08). The filters, the two reports and the
 * pending list are in a closed section; versions, history, simulation and end date open from a row's View.
 *
 * With a {@code register} (M-18 statutory rates, M-19 CPF parameters, SRS-6-47 / SRS M-19) it shows only the
 * rules that screen owns, proposed and approved through the same M-08 routes; the changes pending on other
 * screens and the engine notes stay on M-08.
 */
export function RuleCatalogue({ register }: { register?: string } = {}) {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const catalogue = useRuleCatalogue();
  const readThrough = useRuleReadThrough(!register);
  const propose = useProposeRuleVersion();
  const end = useProposeRuleEnd();
  const [filter, setFilter] = useState<CatalogueFilter>(NO_FILTER);
  const [versionsOf, setVersionsOf] = useState<string | null>(null);
  const [simulateOf, setSimulateOf] = useState<string | null>(null);
  const [editing, setEditing] = useState<RuleRow | null>(null);
  const [draft, setDraft] = useState<VersionDraft | null>(null);
  const [ending, setEnding] = useState<RuleRow | null>(null);
  const [endOn, setEndOn] = useState('');
  const [endReason, setEndReason] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [viewed, setViewed] = useState<RuleRow | null>(null);
  const [picking, setPicking] = useState(false);
  const [pickType, setPickType] = useState('');
  const [pickKey, setPickKey] = useState('');
  const allowed = (action: string) => can(me.data, 'M-08', action);
  // The rule catalogue is maintained at Headquarters only (SRS: Rule Engine closed to Region level).
  const hqBlock = hqOnlyBlock(me.data, 'M-08');
  const maintainer = allowed('CREATE') || allowed('EDIT');
  const today = catalogue.data?.asOf ?? businessToday();
  const all = useMemo(
    () => (catalogue.data?.rules ?? []).filter((r) => inRegister(r, register)),
    [catalogue.data, register],
  );
  const listed = useMemo(
    () => [...all, ...(Array.isArray(readThrough.data) ? readThrough.data : []).map(readThroughRow)],
    [all, readThrough.data],
  );
  const rows = (register ? all : listed).filter((r) => matches(r, filter));
  const shown = useLocalPage(rows, JSON.stringify([filter, register]));
  const types = [...new Set(all.map((r) => r.type))].sort();
  const domains = [...new Set(all.map((r) => r.domain))].sort();
  const roundingRules = all.filter((r) => r.type === 'ROUNDING' && r.active);
  const summary = catalogue.data?.summary;
  const own = registerSummary(all);
  const counts = register
    ? { rules: own.rules, pending: own.pending, notConfigured: own.notConfigured }
    : summary && {
        rules: summary.rules,
        pending: summary.pendingRuleChanges,
        notConfigured: summary.notConfigured,
      };
  const shape = editing?.valueShape ?? null;
  // The Simulation card and the two buttons act on the selected row, else on the first version waiting.
  const target = all.find((r) => r.key === selected && r.pending) ?? all.find((r) => r.pending) ?? null;
  const mayRun = (allowed('CREATE') || allowed('APPROVE')) && hqBlock === null;
  const inbox = withBase('/payroll?s=W-01');
  // The pack gives the checker the approval buttons and the Administrator the new version; the rights stay with the
  // Rule Approver and Rule Administrator: a drawn role without the right is told why, nothing is sent (OD-135).
  const roles = me.data?.roles ?? [];
  const drawnAdmin = roles.includes('SYSTEM_ADMINISTRATOR');
  const drawnChecker = roles.includes('PAYROLL_CHECKER') || allowed('APPROVE');
  const simulateWhy = t(mayRun ? 'm08.sim.nothingProposed' : 'm08w.why.simulate');
  const proposable = all.filter((r) => mayPropose(r, allowed));

  /** Why a row's Edit is off; the drawn button stays, disabled with this reason. */
  function editWhy(row: RuleRow): string {
    if (row.enteredOn) return t('m08w.why.enteredOn', [row.enteredOn]);
    if (hqBlock) return t(hqBlock);
    if (hasPendingChange(row)) return t('m08w.why.pending');
    return t('m08w.why.ruleAdmin');
  }

  function startVersion(row: RuleRow) {
    setEnding(null);
    setEditing(row);
    setDraft(emptyDraft(today));
    setError(null);
  }

  function startEnd(row: RuleRow) {
    setEditing(null);
    setEnding(row);
    setEndOn('');
    setEndReason('');
    setError(null);
  }

  async function submitVersion(row: RuleRow, form: VersionDraft, valueShape: ValueShape) {
    setError(null);
    try {
      await propose.mutateAsync({ key: row.key, body: toProposal(valueShape, form) });
      toast(t('m08.submitted', [row.key]));
      setEditing(null);
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  async function submitEnd(row: RuleRow) {
    setError(null);
    try {
      await end.mutateAsync({ key: row.key, body: { endOn, reason: endReason.trim() } });
      toast(t('m08.endSubmitted', [row.key]));
      setEnding(null);
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  const problems = shape && draft ? draftProblems(shape, draft) : [];
  const filters = (
    <div className="bd rf-grid">
      <div className="rf-field">
        <label htmlFor="m08-text">{t('m08.search')}</label>
        <input
          id="m08-text"
          maxLength={100}
          value={filter.text}
          onChange={(e) => setFilter({ ...filter, text: e.target.value })}
        />
      </div>
      <div className="rf-field">
        <label htmlFor="m08-type">{t('m08.type')}</label>
        <select
          id="m08-type"
          value={filter.type}
          onChange={(e) => setFilter({ ...filter, type: e.target.value })}
        >
          <option value="">{t('m08.allTypes')}</option>
          {types.map((ty) => (
            <option key={ty} value={ty}>
              {t(`m08.ruleType.${ty}`)}
            </option>
          ))}
        </select>
      </div>
      <div className="rf-field">
        <label htmlFor="m08-domain">{t('m08.domain')}</label>
        <select
          id="m08-domain"
          value={filter.domain}
          onChange={(e) => setFilter({ ...filter, domain: e.target.value })}
        >
          <option value="">{t('m08.allDomains')}</option>
          {domains.map((d) => (
            <option key={d} value={d}>
              {t(`m08.ruleDomain.${d}`)}
            </option>
          ))}
        </select>
      </div>
      <div className="rf-field">
        <label htmlFor="m08-status">{t('m08.state')}</label>
        <select
          id="m08-status"
          value={filter.status}
          onChange={(e) => setFilter({ ...filter, status: e.target.value as StatusFilter })}
        >
          <option value="">{t('m08.allStates')}</option>
          {STATUS_FILTERS.map((s) => (
            <option key={s} value={s}>
              {t(`m08.filter.${s}`)}
            </option>
          ))}
        </select>
      </div>
    </div>
  );

  function statusCell(row: RuleRow) {
    return (
      <>
        {row.inForce ? (
          <StatusPill status={row.inForce.status} />
        ) : row.enteredOn ? (
          <span className={`pill ${row.readThroughPending ? 'p-warn' : 'p-info'}`}>
            {t(row.readThroughPending ? 'm08.pendingTitle' : 'm08w.readOnly')}
          </span>
        ) : notConfigured(row) ? (
          <span className="pill p-bad">{t('mst.notConfigured')}</span>
        ) : (
          <span className="pill p-gray">{t('m08.inactive')}</span>
        )}
        {row.pending && (
          <div>
            <span className="pill p-warn">
              {t('m08.pendingVersion', [row.pending.versionNo, date(row.pending.effectiveFrom)])}
            </span>
          </div>
        )}
        {row.pendingEnd && (
          <div>
            <span className="pill p-warn">{t('m08.pendingEnd', [date(row.pendingEnd.endOn)])}</span>
          </div>
        )}
        {!row.maintained && !row.enteredOn && <div className="sub2">{t('m08.notMaintained')}</div>}
      </>
    );
  }

  return (
    <div className="wireframe-body-container">
      {register ? (
        <>
          <div className="toolbar">
            <span className="sub2">{maintainer ? t('m08.hint') : t('m08.readOnly')}</span>
            {!register && allowed('EXPORT') && (
              <>
                <ExportButton
                  href={catalogueSummaryUrl()}
                  what={t('m08.report.catalogue')}
                  label={t('m08.report.catalogue')}
                />
                <ExportButton
                  href={pendingReportUrl()}
                  what={t('m08.report.pending')}
                  label={t('m08.report.pending')}
                />
              </>
            )}
          </div>
          <div className="tiles">
            <Tile
              icon="⚙️"
              label={t('m08.tile.rules')}
              value={counts?.rules ?? '—'}
              sub={t('m08.tile.rulesSub', [
                register ? types.length : Object.keys(summary?.byType ?? {}).length,
              ])}
            />
            <Tile
              icon="✍️"
              label={t('m08.tile.pending')}
              value={counts?.pending ?? '—'}
              sub={t('m08.tile.pendingSub')}
              tone="warn"
            />
            {!register && (
              <Tile
                icon="📥"
                label={t('m08.tile.pendingOther')}
                value={summary?.pendingOtherChanges ?? '—'}
                sub={t('m08.tile.pendingOtherSub')}
                tone="info"
              />
            )}
            <Tile
              icon="⛔"
              label={t('m08.tile.notConfigured')}
              value={counts?.notConfigured ?? '—'}
              sub={t('m08.tile.notConfiguredSub')}
              tone={counts && counts.notConfigured > 0 ? 'bad' : 'ok'}
            />
          </div>
        </>
      ) : (
        <>
          <div className="toolbar">
            <span className="sp" />
            {mayRun && target ? (
              <Act label={t('m08w.simulateImpact')} onClick={() => setSimulateOf(target.key)} />
            ) : (
              <Explained label={t('m08w.simulateImpact')} why={simulateWhy} />
            )}
            {allowed('CREATE') && hqBlock === null ? (
              <Act label={t('m08w.newVersion')} kind="pri" onClick={() => setPicking(!picking)} />
            ) : drawnAdmin ? (
              <Explained
                label={t('m08w.newVersion')}
                kind="pri"
                why={hqBlock ? t(hqBlock) : t('m08w.why.ruleAdmin')}
              />
            ) : (
              <Locked
                label={t('m08w.newVersion')}
                kind="pri"
                why={hqBlock ? t(hqBlock) : t('m08w.why.ruleAdmin')}
              />
            )}
          </div>
          <Tiles>
            <WfTile
              icon="⚙️"
              label={t('m08w.tile.rules')}
              src="SYS"
              big={summary?.rules ?? '—'}
              sub={t('m08.tile.rulesSub', [Object.keys(summary?.byType ?? {}).length])}
            />
            <WfTile
              icon="✍️"
              label={t('m08w.tile.pending')}
              src="SYS"
              big={summary?.pendingRuleChanges ?? '—'}
              sub={t('m08.tile.pendingSub')}
              tone="warn"
            />
            <WfTile
              icon="📥"
              label={t('m08w.tile.pendingOther')}
              src="SYS"
              big={summary?.pendingOtherChanges ?? '—'}
              sub={t('m08w.tile.pendingOtherSub')}
              tone="warn"
            />
            <WfTile
              icon="⛔"
              label={t('m08w.tile.missing')}
              src="SYS"
              big={summary?.notConfigured ?? '—'}
              sub={t('m08w.tile.missingSub')}
              tone={summary && summary.notConfigured > 0 ? 'bad' : 'ok'}
              bad={(summary?.notConfigured ?? 0) > 0}
            />
          </Tiles>
          {picking && (
            <Card title={t('m08w.pick.title')}>
              <div className="bd rf-grid">
                <div className="rf-field">
                  <label htmlFor="m08-pick-type">{t('m08.type')}</label>
                  <select
                    id="m08-pick-type"
                    value={pickType}
                    onChange={(e) => {
                      setPickType(e.target.value);
                      setPickKey('');
                    }}
                  >
                    <option value="">{t('common.choose')}</option>
                    {[...new Set(proposable.map((r) => r.type))].sort().map((ty) => (
                      <option key={ty} value={ty}>
                        {t(`m08.ruleType.${ty}`)}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="rf-field">
                  <label htmlFor="m08-pick-key">{t('m08.ruleKey')}</label>
                  <select id="m08-pick-key" value={pickKey} onChange={(e) => setPickKey(e.target.value)}>
                    <option value="">{t('common.choose')}</option>
                    {proposable
                      .filter((r) => r.type === pickType)
                      .map((r) => (
                        <option key={r.key} value={r.key}>
                          {r.key} · {r.name}
                        </option>
                      ))}
                  </select>
                </div>
              </div>
              <div className="rf-actbar">
                <button
                  type="button"
                  className="btn pri"
                  disabled={pickKey === ''}
                  onClick={() => {
                    const row = proposable.find((r) => r.key === pickKey);
                    if (row) startVersion(row);
                    setPicking(false);
                  }}
                >
                  {t('m08w.pick.go')}
                </button>
              </div>
            </Card>
          )}
        </>
      )}
      <Problem error={catalogue.error ?? (editing || ending ? null : error)} />
      {register ? (
        <Card title={t('m08.catalogue')}>
          {filters}
          {catalogue.isSuccess && rows.length === 0 ? (
            <Empty text={t('m08.noRules')} />
          ) : (
            <div className="wrap">
              <table className="t">
                <thead>
                  <tr>
                    <th>
                      {t('m08.ruleKey')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('m08.type')}
                      <Src s="RULE" />
                    </th>
                    <th>
                      {t('m08.applicability')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('m08.value')}
                      <Src s="RULE" />
                    </th>
                    <th>
                      {t('mst.from')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('m08.version')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {/* SRS l.2322: the governing instrument (M-25) of the version in force. */}
                      {t('m08.governingOrder')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('m08.state')}
                      <Src s="SYS" />
                    </th>
                    <th aria-label={t('common.actions')} />
                  </tr>
                </thead>
                <tbody>
                  {shown.rows.map((row) => (
                    <tr key={row.key}>
                      <td>
                        <b>{row.key}</b>
                        <div className="sub2">{row.name}</div>
                      </td>
                      <td>
                        {t(`m08.ruleType.${row.type}`)}
                        {row.orderRequired && <div className="sub2">{t('m08.orderRequired')}</div>}
                      </td>
                      <td>{scopeText(t, row)}</td>
                      <td className="fl-wrap-any">
                        {valueText(t, row.valueShape, row.inForce)}
                        {row.inForce?.blocked && <span className="pill p-bad">{t('m08.blocked')}</span>}
                      </td>
                      <td>{row.inForce ? date(row.inForce.effectiveFrom) : '—'}</td>
                      <td>{row.inForce ? `v${row.inForce.versionNo}` : '—'}</td>
                      <td>{row.inForce?.circularNumber ?? '—'}</td>
                      <td>{statusCell(row)}</td>
                      <td>
                        <div className="acts">
                          <button
                            type="button"
                            className="ia"
                            aria-label={t('m08.versionsOf', [row.key])}
                            aria-pressed={versionsOf === row.key}
                            onClick={() => setVersionsOf(versionsOf === row.key ? null : row.key)}
                          >
                            {t('m08.versions')}
                          </button>
                          <HistoryButton entity="rule_version" recordKey={row.key} />
                          {/* SRS l.2323: Simulate Impact on every row, disabled with its reason while no version is proposed. */}
                          <button
                            type="button"
                            className="ia"
                            aria-label={t('m08.sim.of', [row.key])}
                            aria-pressed={simulateOf === row.key}
                            disabled={!row.pending}
                            title={row.pending ? undefined : t('m08.sim.nothingProposed')}
                            onClick={() => setSimulateOf(simulateOf === row.key ? null : row.key)}
                          >
                            {t('m08.sim.action')}
                          </button>
                          {mayPropose(row, allowed) && (
                            <button
                              type="button"
                              className="ia"
                              aria-label={t(row.versions === 0 ? 'm08.newVersionOf' : 'm08.supersedeOf', [
                                row.key,
                              ])}
                              disabled={hqBlock !== null}
                              title={hqBlock ? t(hqBlock) : undefined}
                              onClick={() => startVersion(row)}
                            >
                              {t(row.versions === 0 ? 'action.newRuleVersion' : 'action.supersede')}
                            </button>
                          )}
                          {mayEnd(row, allowed) && (
                            <button
                              type="button"
                              className="ia"
                              aria-label={t('m08.endOf', [row.key])}
                              disabled={hqBlock !== null}
                              title={hqBlock ? t(hqBlock) : undefined}
                              onClick={() => startEnd(row)}
                            >
                              {t('m08.end')}
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {rows.length > 0 && shown.pager}
        </Card>
      ) : (
        <>
          <Tbl
            title={t('m08w.catalogue')}
            acts={
              <>
                {mayRun && target ? (
                  <Act label={t('m08w.simulate')} kind="sm" onClick={() => setSimulateOf(target.key)} />
                ) : (
                  <Explained label={t('m08w.simulate')} kind="sm" why={simulateWhy} />
                )}
                {/* M08-07: supersession happens on approval: drawn for the checker, it says so; off for the rest. */}
                {drawnChecker ? (
                  <Explained label={t('action.supersede')} kind="sm" why={t('m14w.why.supersede')} />
                ) : (
                  <Locked label={t('action.supersede')} kind="sm" why={t('m08w.why.supersede')} />
                )}
              </>
            }
            cols={[
              { t: t('m08.ruleKey'), s: 'ENT' },
              { t: t('m08w.col.type'), s: 'RULE' },
              { t: t('m08.applicability'), s: 'ENT' },
              { t: t('m08.value'), s: 'ENT' },
              { t: t('m08w.col.from'), s: 'ENT' },
              { t: t('m08.version'), s: 'SYS' },
              { t: t('m08w.col.status'), s: 'SYS' },
              { t: t('common.actions'), s: 'SYS' },
            ]}
            after={rows.length > 0 ? shown.pager : undefined}
          >
            {catalogue.isSuccess && rows.length === 0 && (
              <tr>
                <td colSpan={8}>
                  <Empty text={t('m08.noRules')} />
                </td>
              </tr>
            )}
            {shown.rows.map((row) => (
              <tr
                key={row.key}
                data-rule={row.key}
                aria-selected={selected === row.key}
                className={selected === row.key ? 'fl-row-selected' : undefined}
                onClick={() => setSelected(row.key)}
              >
                <td>
                  <b>{row.key}</b>
                  <div className="sub2">{row.name}</div>
                </td>
                <td>
                  {t(`m08.ruleType.${row.type}`)}
                  {row.orderRequired && <div className="sub2">{t('m08.orderRequired')}</div>}
                </td>
                <td>{row.enteredOn ? t('m08w.readThroughScope') : scopeText(t, row)}</td>
                <td className="fl-wrap-any">
                  {row.enteredOn
                    ? t('m08w.enteredOn', [row.enteredOn, row.records ?? 0])
                    : valueText(t, row.valueShape, row.inForce)}
                  {row.inForce?.blocked && <span className="pill p-bad">{t('m08.blocked')}</span>}
                </td>
                <td>
                  {row.inForce ? date(row.inForce.effectiveFrom) : row.heldFrom ? date(row.heldFrom) : '—'}
                </td>
                <td>{row.inForce ? `v${row.inForce.versionNo}` : '—'}</td>
                <td>{statusCell(row)}</td>
                <td>
                  <RowActs
                    viewName={t('m08w.viewOf', [row.key])}
                    editName={t(row.versions === 0 ? 'm08.newVersionOf' : 'm08.supersedeOf', [row.key])}
                    onView={() => setViewed(row)}
                    onEdit={
                      mayPropose(row, allowed) && hqBlock === null ? () => startVersion(row) : undefined
                    }
                    editWhy={editWhy(row)}
                    onApprove={
                      allowed('APPROVE') && row.pending ? () => window.location.assign(inbox) : undefined
                    }
                    approveWhy={
                      row.enteredOn
                        ? undefined
                        : allowed('APPROVE')
                          ? t('m08w.why.nothingToApprove')
                          : me.data?.role === 'PAYROLL_CHECKER'
                            ? t('m08w.why.approver')
                            : undefined
                    }
                  />
                </td>
              </tr>
            ))}
          </Tbl>
          <div className="g2 fl-mt">
            <SimulationCard
              row={target}
              actions={
                allowed('APPROVE') ? (
                  <>
                    <Act label={t('m08w.return')} href={inbox} />
                    <span className="sp" />
                    <Act label={t('m08w.approve')} kind="pri" href={inbox} />
                  </>
                ) : drawnChecker ? (
                  <>
                    <Explained label={t('m08w.return')} why={t('m08w.why.approver')} />
                    <span className="sp" />
                    <Explained label={t('m08w.approve')} kind="pri" why={t('m08w.why.approver')} />
                  </>
                ) : (
                  <>
                    <Locked label={t('m08w.return')} why={t('m08w.why.approver')} />
                    <span className="sp" />
                    <Locked label={t('m08w.approve')} kind="pri" why={t('m08w.why.approver')} />
                  </>
                )
              }
            />
            <Panel
              title={t('m08w.guardRails')}
              items={GUARD_RAILS.map((g) => ({
                icon: g.icon,
                title: t(`m08w.guard.${g.key}`),
                src: g.src,
                text: t(`m08w.guard.${g.key}.text`),
                tone: g.tone,
                right: g.pill ? (
                  <span className="pill p-ok">{t(`m08w.guard.pill.${g.pill}`)}</span>
                ) : undefined,
              }))}
            />
          </div>
          <details className="card fl-mt" data-testid="m08-register">
            <summary>{t('wf.searchDisclosure')}</summary>
            <p className="sub2">{maintainer ? t('m08.hint') : t('m08.readOnly')}</p>
            {filters}
            {allowed('EXPORT') && (
              <div className="toolbar">
                <ExportButton
                  href={catalogueSummaryUrl()}
                  what={t('m08.report.catalogue')}
                  label={t('m08.report.catalogue')}
                />
                <ExportButton
                  href={pendingReportUrl()}
                  what={t('m08.report.pending')}
                  label={t('m08.report.pending')}
                />
              </div>
            )}
            <ul className="bd fl-list">
              {['dates', 'slabs', 'grammar', 'approval', 'circular', 'noDefault'].map((g) => (
                <li key={g}>{t(`m08.guard.${g}`)}</li>
              ))}
            </ul>
          </details>
        </>
      )}
      {simulateOf && (
        <RuleSimulationPanel
          key={simulateOf}
          ruleKey={simulateOf}
          mayRun={(allowed('CREATE') || allowed('APPROVE')) && hqBlock === null}
          onClose={() => setSimulateOf(null)}
        />
      )}
      {versionsOf && (
        <Card title={t('m08.versionsTitle', [versionsOf])}>
          <div className="wrap">
            <ServerPagination
              queryKey={ruleKeys.versions(versionsOf)}
              fetchPage={rulePages.versions(versionsOf)}
              personal={false}
              empty={<Empty text={t('m08.noVersions')} />}
            >
              {(versionRows) => (
                <table className="t">
                  <thead>
                    <tr>
                      <th>
                        {t('m08.version')}
                        <Src s="SYS" />
                      </th>
                      <th>
                        {t('mst.from')}
                        <Src s="ENT" />
                      </th>
                      <th>
                        {t('mst.to')}
                        <Src s="ENT" />
                      </th>
                      <th>
                        {t('m08.value')}
                        <Src s="RULE" />
                      </th>
                      <th>
                        {t('m08.circular')}
                        <Src s="ENT" />
                      </th>
                      <th>
                        {t('m08.state')}
                        <Src s="SYS" />
                      </th>
                      <th>
                        {t('m08.proposedBy')}
                        <Src s="SYS" />
                      </th>
                      <th>
                        {t('m08.approvedBy')}
                        <Src s="SYS" />
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {versionRows.map((v) => (
                      <tr key={`${v.versionNo}-${v.approvalId ?? v.id}`}>
                        <td>
                          v{v.versionNo}
                          {v.supersedesVersionNo !== null && (
                            <div className="sub2">{t('m08.supersedesVersion', [v.supersedesVersionNo])}</div>
                          )}
                        </td>
                        <td>{date(v.effectiveFrom)}</td>
                        <td>{v.effectiveTo ? date(v.effectiveTo) : t('mst.open')}</td>
                        <td className="fl-wrap-any">
                          {v.bands.length > 0 ? (
                            <ul className="fl-list">
                              {v.bands.map((b) => (
                                <li key={`${b.qualifier ?? ''}-${b.order}`}>
                                  {b.qualifier ? `${b.qualifier} · ` : ''}
                                  {b.from} – {b.to ?? t('m08.andAbove')}: {b.rateOrAmount}
                                  {b.step !== null ? ` (${t('m08.step')} ${b.step})` : ''}
                                </li>
                              ))}
                            </ul>
                          ) : (
                            valueText(t, null, v)
                          )}
                          {v.roundingRuleKey && (
                            <div className="sub2">{t('m08.roundedBy', [v.roundingRuleKey])}</div>
                          )}
                        </td>
                        <td>{v.circularNumber ?? '—'}</td>
                        <td>
                          <StatusPill status={v.status} />
                        </td>
                        <td>
                          {v.createdBy}
                          <div className="sub2">{dateTime(v.createdAt)}</div>
                        </td>
                        <td>{v.approvedBy ?? '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </ServerPagination>
          </div>
        </Card>
      )}
      {editing && draft && shape && (
        <Card title={t(editing.versions === 0 ? 'm08.newVersionOf' : 'm08.supersedeOf', [editing.key])}>
          <Problem error={error} />
          <div className="bd rf-grid">
            <div className="rf-field">
              <label htmlFor="rv-from">{t('mst.from')}</label>
              <input
                id="rv-from"
                type="date"
                value={draft.effectiveFrom}
                onChange={(e) => setDraft({ ...draft, effectiveFrom: e.target.value })}
              />
              {isRetrospective(draft.effectiveFrom, today) && (
                <span className="hint">{t('m08.retrospective')}</span>
              )}
            </div>
            <div className="rf-field">
              <label htmlFor="rv-to">{t('m08.effectiveTo')}</label>
              <input
                id="rv-to"
                type="date"
                value={draft.effectiveTo}
                onChange={(e) => setDraft({ ...draft, effectiveTo: e.target.value })}
              />
              <span className="hint">{t('m08.effectiveToHint')}</span>
            </div>
            {shape === 'SCALAR' && (
              <div className="rf-field">
                <label htmlFor="rv-value">{t('m08.value')}</label>
                <input
                  id="rv-value"
                  inputMode="decimal"
                  maxLength={14}
                  value={draft.value}
                  onChange={(e) => setDraft({ ...draft, value: e.target.value })}
                />
              </div>
            )}
            {(shape === 'NUMBER_FORMULA' || shape === 'CONDITION_FORMULA') && (
              <FormulaField
                condition={shape === 'CONDITION_FORMULA'}
                value={draft.formula}
                onChange={(formula) => setDraft({ ...draft, formula })}
              />
            )}
            <div className="rf-field">
              <label htmlFor="rv-rounding">{t('m08.rounding')}</label>
              <select
                id="rv-rounding"
                value={draft.roundingRuleKey}
                onChange={(e) => setDraft({ ...draft, roundingRuleKey: e.target.value })}
              >
                <option value="">{t('m08.noRounding')}</option>
                {roundingRules.map((r) => (
                  <option key={r.key} value={r.key}>
                    {r.key}
                  </option>
                ))}
              </select>
            </div>
            <CircularField
              value={draft.circularId}
              onChange={(circularId) => setDraft({ ...draft, circularId })}
            />
            <div className="rf-field span2">
              <label htmlFor="rv-reason">{t('common.reason')}</label>
              <TextAreaField
                id="rv-reason"
                maxLength={500}
                value={draft.reason}
                onChange={(e) => setDraft({ ...draft, reason: e.target.value })}
              />
            </div>
          </div>
          {shape === 'SLAB' && (
            <BandEditor bands={draft.bands} onChange={(bands) => setDraft({ ...draft, bands })} />
          )}
          {problems.length > 0 && (
            <ul className="fl-hint" aria-live="polite">
              {problems.map((p) => (
                <li key={p}>{t(p)}</li>
              ))}
            </ul>
          )}
          <p className="fl-hint">{t('m08.approverNote')}</p>
          <div className="rf-actbar">
            <button
              type="button"
              className="btn"
              onClick={() => {
                setEditing(null);
                setError(null);
              }}
            >
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className="btn pri"
              disabled={problems.length > 0 || propose.isPending}
              onClick={() => void submitVersion(editing, draft, shape)}
            >
              {t('common.submitForApproval')}
            </button>
          </div>
        </Card>
      )}
      {ending && (
        <Card title={t('m08.endOf', [ending.key])}>
          <Problem error={error} />
          <p className="sub2">{t('m08.endHint')}</p>
          <div className="bd rf-grid">
            <div className="rf-field">
              <label htmlFor="re-on">{t('m08.endOn')}</label>
              <input id="re-on" type="date" value={endOn} onChange={(e) => setEndOn(e.target.value)} />
            </div>
            <div className="rf-field span2">
              <label htmlFor="re-reason">{t('common.reason')}</label>
              <TextAreaField
                id="re-reason"
                maxLength={500}
                value={endReason}
                onChange={(e) => setEndReason(e.target.value)}
              />
            </div>
          </div>
          <p className="fl-hint">{t('m08.approverNote')}</p>
          <div className="rf-actbar">
            <button
              type="button"
              className="btn"
              onClick={() => {
                setEnding(null);
                setError(null);
              }}
            >
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className="btn pri"
              disabled={!endOn || !endReason.trim() || end.isPending}
              onClick={() => void submitEnd(ending)}
            >
              {t('common.submitForApproval')}
            </button>
          </div>
        </Card>
      )}
      {!register && (
        <details className="card fl-mt">
          <summary>{t('m08w.pendingList')}</summary>
          <Card title={t('m08.pendingTitle')}>
            <p className="sub2">{t('m08.pendingHint')}</p>
            <div className="wrap">
              <ServerPagination
                queryKey={ruleKeys.pending}
                fetchPage={rulePages.pending}
                personal={false}
                empty={<Empty text={t('m08.noPending')} />}
              >
                {(items) => (
                  <table className="t">
                    <thead>
                      <tr>
                        <th>
                          {t('m08.screen')}
                          <Src s="SYS" />
                        </th>
                        <th>
                          {t('m08.change')}
                          <Src s="SYS" />
                        </th>
                        <th>
                          {t('m08.record')}
                          <Src s="SYS" />
                        </th>
                        <th>
                          {t('m08.submittedBy')}
                          <Src s="SYS" />
                        </th>
                        <th>
                          {t('m08.submittedAt')}
                          <Src s="SYS" />
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {items.map((p) => (
                        <tr key={p.approvalId}>
                          <td>{p.screen}</td>
                          <td>{entityLabel(t, p.entity)}</td>
                          <td>{p.key}</td>
                          <td>{p.submittedBy}</td>
                          <td>{dateTime(p.submittedAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </ServerPagination>
            </div>
          </Card>
        </details>
      )}
      {viewed && (
        <ViewDialog
          screen={t('screen.M-08')}
          onClose={() => setViewed(null)}
          rows={[
            { k: t('m08.ruleKey'), v: viewed.key, s: 'ENT' },
            { k: t('m07.name'), v: viewed.name, s: 'ENT' },
            { k: t('m08w.col.type'), v: t(`m08.ruleType.${viewed.type}`), s: 'RULE' },
            { k: t('m08.applicability'), v: scopeText(t, viewed), s: 'ENT' },
            { k: t('m08.value'), v: valueText(t, viewed.valueShape, viewed.inForce), s: 'ENT' },
            { k: t('m08w.col.from'), v: viewed.inForce ? date(viewed.inForce.effectiveFrom) : '—', s: 'ENT' },
            { k: t('m08.version'), v: viewed.inForce ? `v${viewed.inForce.versionNo}` : '—', s: 'SYS' },
            { k: t('m08.governingOrder'), v: viewed.inForce?.circularNumber ?? '—', s: 'ENT' },
            { k: t('m08w.col.status'), v: statusCell(viewed), s: 'SYS' },
          ]}
          actions={
            viewed.enteredOn ? undefined : (
              <>
                <button
                  type="button"
                  className="btn"
                  aria-pressed={versionsOf === viewed.key}
                  onClick={() => {
                    setVersionsOf(versionsOf === viewed.key ? null : viewed.key);
                    setViewed(null);
                  }}
                >
                  {t('m08.versions')}
                </button>
                <HistoryButton entity="rule_version" recordKey={viewed.key} />
                {viewed.pending ? (
                  <button
                    type="button"
                    className="btn"
                    aria-label={t('m08.sim.of', [viewed.key])}
                    onClick={() => {
                      setSimulateOf(simulateOf === viewed.key ? null : viewed.key);
                      setViewed(null);
                    }}
                  >
                    {t('m08.sim.action')}
                  </button>
                ) : (
                  <Locked label={t('m08.sim.action')} why={t('m08.sim.nothingProposed')} />
                )}
                {mayEnd(viewed, allowed) && hqBlock === null ? (
                  <button
                    type="button"
                    className="btn"
                    aria-label={t('m08.endOf', [viewed.key])}
                    onClick={() => {
                      startEnd(viewed);
                      setViewed(null);
                    }}
                  >
                    {t('m08.end')}
                  </button>
                ) : (
                  <Locked label={t('m08.end')} why={editWhy(viewed)} />
                )}
              </>
            )
          }
        />
      )}
    </div>
  );
}

/**
 * The drawn Simulation card (BR-RULE-006, FRS V0.5 §24.4 Step 2): the latest run of the version waiting, with the
 * amounts where the engines price the rule. A DA rate is priced with the C-09 engine as a what-if, and the card says
 * that pay reads the M-04 rate today (M08-DA-SOURCE, for FCI): an approval here does not change pay.
 */
function SimulationCard({ row, actions }: { row: RuleRow | null; actions: ReactNode }) {
  const { t } = useI18n();
  const runs = useRuleSimulations(row?.key ?? null);
  const run = runs.data?.items?.[0];
  const priced = run?.impact.priced ?? null;
  const dash = '—';
  const change = (before: number, after: number) =>
    `${amount(before)} → ${amount(after)} (${amount(after - before)})`;
  const delta = (code: string) => {
    const value = priced?.statutoryDelta[code];
    return value === undefined ? dash : amount(value);
  };
  const pending = row?.pending ?? null;
  return (
    <KvCard
      title={
        row && pending
          ? t('m08w.sim.title', [
              row.key,
              pending.versionNo,
              valueText(t, row.valueShape, row.inForce),
              valueText(t, row.valueShape, pending),
            ])
          : t('m08w.sim.none')
      }
      rows={[
        {
          k: t('m08w.sim.population'),
          v: run ? t('m08w.sim.sample', [run.population, run.impact.sampled]) : dash,
          s: 'CALC',
        },
        { k: t('m08w.sim.from'), v: pending ? date(pending.effectiveFrom) : dash, s: 'ENT' },
        {
          k: t('m08w.sim.monthly'),
          v: priced ? change(priced.currentComponent, priced.proposedComponent) : dash,
          s: 'CALC',
        },
        { k: t('m08w.sim.cpf'), v: priced ? delta('CPF') : dash, s: 'CALC' },
        { k: t('m08w.sim.tds'), v: priced ? delta('TDS') : dash, s: 'CALC' },
        {
          k: t('m08w.sim.retro'),
          v: priced ? t('m08w.sim.periods', [priced.retroPeriods]) : dash,
          s: 'CALC',
        },
        { k: t('m08w.sim.negative'), v: priced ? priced.negativeNet : dash, s: 'CALC' },
        {
          k: t('m08w.sim.decision'),
          v: pending ? <span className="pill p-warn">{t('m08w.sim.awaiting')}</span> : dash,
          s: 'SYS',
        },
      ]}
      after={
        <>
          {run && !priced && <p className="fl-hint">{t('m08w.sim.notPriced')}</p>}
          {priced && <p className="fl-hint">{t('m08w.sim.daSource')}</p>}
          <div className="pnav">{actions}</div>
        </>
      }
    />
  );
}

/** The Active M-25 circular a version rests on (SRS-19-17); read only while the form is open. */
function CircularField({ value, onChange }: { value: number | null; onChange: (id: number | null) => void }) {
  const { t } = useI18n();
  const circulars = useCurrentCirculars();
  return (
    <div className="rf-field span2">
      <label htmlFor="rv-circular">{t('m08.circular')}</label>
      <select
        id="rv-circular"
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))}
      >
        <option value="">{t('common.choose')}</option>
        {(circulars.data ?? []).map((c) => (
          <option key={c.id} value={c.id}>
            {c.number} · {c.subject}
          </option>
        ))}
      </select>
      {circulars.isSuccess && circulars.data.length === 0 && (
        <span className="hint">{t('m08.noActiveCircular')}</span>
      )}
      {/* The list is M-25's: a role without M-25 access is told so, never shown an empty list. */}
      <Problem error={circulars.error} />
    </div>
  );
}

/** The formula, with the grammar in force beside it (SRS-19-15: no free program code). */
function FormulaField({
  condition,
  value,
  onChange,
}: {
  condition: boolean;
  value: string;
  onChange: (formula: string) => void;
}) {
  const { t } = useI18n();
  const vocabulary = useRuleVocabulary(true);
  const v = vocabulary.data;
  return (
    <div className="rf-field span2">
      <label htmlFor="rv-formula">{t(condition ? 'm08.condition' : 'm08.formula')}</label>
      <textarea
        id="rv-formula"
        rows={3}
        maxLength={4000}
        spellCheck={false}
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
      <span className="hint">{t(condition ? 'm08.conditionHint' : 'm08.formulaHint')}</span>
      {/* The formula can still be written; the server checks it against the grammar in force. */}
      <Problem error={vocabulary.error} />
      {v && (
        <dl className="fl-vocabulary">
          <dt>{t('m08.operators')}</dt>
          <dd>{v.operators.join('  ')}</dd>
          <dt>{t('m08.functions')}</dt>
          <dd>
            {Object.entries(v.functions)
              .map(([name, arity]) => (arity === null ? `${name}(…)` : `${name}(${arity})`))
              .join('  ')}
          </dd>
          <dt>{t('m08.variables')}</dt>
          <dd>{v.variables.length === 0 ? t('m08.noVariables') : v.variables.join('  ')}</dd>
        </dl>
      )}
    </div>
  );
}

/** The slab bands, in order (SRS-19-5: contiguous, not overlapping; the server checks the set). */
function BandEditor({ bands, onChange }: { bands: BandDraft[]; onChange: (bands: BandDraft[]) => void }) {
  const { t } = useI18n();
  const set = (i: number, patch: Partial<BandDraft>) =>
    onChange(bands.map((b, j) => (j === i ? { ...b, ...patch } : b)));
  return (
    <div className="bd">
      <div className="wrap">
        <table className="t">
          <caption className="sub2">{t('m08.bandsHint')}</caption>
          <thead>
            <tr>
              <th>
                {t('m08.band')}
                <Src s="SYS" />
              </th>
              <th>
                {t('m08.bandFrom')}
                <Src s="ENT" />
              </th>
              <th>
                {t('m08.bandTo')}
                <Src s="ENT" />
              </th>
              <th>
                {t('m08.rateOrAmount')}
                <Src s="ENT" />
              </th>
              <th>
                {t('m08.step')}
                <Src s="ENT" />
              </th>
              <th>
                {t('m08.qualifier')}
                <Src s="ENT" />
              </th>
              <th aria-label={t('common.actions')} />
            </tr>
          </thead>
          <tbody>
            {bands.map((b, i) => (
              // Bands are positional rows of an unsaved draft: the index is their identity.
              <tr key={i}>
                <td>{i + 1}</td>
                <td>
                  <input
                    aria-label={t('m08.bandFromOf', [i + 1])}
                    inputMode="decimal"
                    maxLength={14}
                    value={b.from}
                    onChange={(e) => set(i, { from: e.target.value })}
                  />
                </td>
                <td>
                  <input
                    aria-label={t('m08.bandToOf', [i + 1])}
                    inputMode="decimal"
                    maxLength={14}
                    value={b.to}
                    onChange={(e) => set(i, { to: e.target.value })}
                  />
                </td>
                <td>
                  <input
                    aria-label={t('m08.rateOrAmountOf', [i + 1])}
                    inputMode="decimal"
                    maxLength={14}
                    value={b.rateOrAmount}
                    onChange={(e) => set(i, { rateOrAmount: e.target.value })}
                  />
                </td>
                <td>
                  <input
                    aria-label={t('m08.stepOf', [i + 1])}
                    inputMode="decimal"
                    maxLength={14}
                    value={b.step}
                    onChange={(e) => set(i, { step: e.target.value })}
                  />
                </td>
                <td>
                  <input
                    aria-label={t('m08.qualifierOf', [i + 1])}
                    maxLength={40}
                    value={b.qualifier}
                    onChange={(e) => set(i, { qualifier: e.target.value })}
                  />
                </td>
                <td>
                  <button
                    type="button"
                    className="ia"
                    aria-label={t('m08.removeBand', [i + 1])}
                    disabled={bands.length === 1}
                    onClick={() => onChange(bands.filter((_, j) => j !== i))}
                  >
                    ✕
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <button
        type="button"
        className="btn"
        disabled={!canAddBand(bands)}
        onClick={() => onChange(appendBand(bands))}
      >
        {t('m08.addBand')}
      </button>
    </div>
  );
}
