'use client';

import { useCallback, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { can, hqOnlyBlock, useMe } from '@/shared/session';
import {
  Card,
  date,
  ExportButton,
  Problem,
  ServerPagination,
  Src,
  TextAreaField,
  useToast,
  withBase,
} from '@/shared/ui';
import { Act, Explained, Locked, RowActs, Tbl, ViewDialog } from '@/shared/ui/wireframe';
import { businessToday } from '@/shared/time';
import type { OperationRow, OperationVersion, ProposedOperation } from '../api/mdmApi';
import { NORM_TYPES, RATE_NORM_LINKS } from '../business/mdmRules';
import {
  mdmKeys,
  mdmLinks,
  mdmPages,
  useCurrentCirculars,
  useEndDateOperation,
  useProposeOperation,
} from '../orchestration/mdmQueries';

type Draft = Omit<ProposedOperation, 'reason'>;

const EMPTY: Draft = {
  effectiveFrom: '',
  name: '',
  departmental: false,
  dps: false,
  nwnp: false,
  bagBased: false,
  normType: null,
  rateNormLink: 'TIME_BASED',
  dusting: false,
  allowancePayCode: null,
  presentButNoWork: false,
  circularId: null,
};

/**
 * M-27 Operation Master as Wireframes v6.1 draws it (a frozen requirement per OD-121; SRS-G-41, SRS
 * l.1005): "🕘 History" and "＋ New Operation" (Administrator at Headquarters) over the screen, and
 * the Operations card with Export, one row per code with its payroll types, bag-based flag, bag
 * weight band (filled from the master, never typed: operation_master keeps no band, Q-04-3), rate or
 * norm link, governing circular, effective date, status and the row actions. A row is selected by
 * clicking it: History reads the selected operation (the first of the page until one is chosen).
 * The search by code, standard name or any M-26 label (SRS-G-39) is closed under its disclosure: the
 * pack draws none (WF-082). Every version is a maker-checker proposal; end-dated, never deleted: End
 * date opens from the record's View (WF-083). An operation that shows a local name reads
 * "local (standard)" (SRS-G-39).
 */
export function Operations() {
  const { t, locale } = useI18n();
  const [text, setText] = useState('');
  const listQuery = { text: text.trim(), lang: locale };
  const toast = useToast();
  const me = useMe();
  const hqBlock = hqOnlyBlock(me.data, 'M-27');
  const propose = useProposeOperation();
  const endDate = useEndDateOperation();
  const [selected, setSelected] = useState<OperationRow | null>(null);
  const [viewing, setViewing] = useState<OperationRow | null>(null);
  const [historyOf, setHistoryOf] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [draft, setDraft] = useState<Draft | null>(null);
  const [isNew, setIsNew] = useState(false);
  const [reason, setReason] = useState('');
  const [ending, setEnding] = useState<OperationRow | null>(null);
  const [lastDay, setLastDay] = useState('');
  const [error, setError] = useState<unknown>(null);
  const mayCreate = can(me.data, 'M-27', 'CREATE');
  const mayEnd = can(me.data, 'M-27', 'EDIT');
  const mayApprove = can(me.data, 'M-27', 'APPROVE');
  const mayExport = can(me.data, 'M-27', 'EXPORT');
  const circulars = useCurrentCirculars();
  const shown = useCallback((page: OperationRow[]) => {
    setSelected((prev) => page.find((r) => r.code === prev?.code) ?? page[0] ?? null);
  }, []);

  /** The payroll types as the column draws them ("Dept · DPS · NWNP"). */
  function payrolls(v: OperationVersion): string {
    return [v.departmental && t('m27.short.dept'), v.dps && t('m27.short.dps'), v.nwnp && t('m27.short.nwnp')]
      .filter(Boolean)
      .join(' · ');
  }

  /** The rate or norm link as drawn: Dusting and Present But No Work name what pays them. */
  function linkOf(v: OperationVersion): string {
    if (v.presentButNoWork) return t('m27.linkShown.PBNW');
    if (v.dusting) return t('m27.linkShown.DUSTING');
    return t(`m27.linkShown.${v.rateNormLink}`);
  }

  const shownName = (row: OperationRow, v: OperationVersion) =>
    // SRS-G-39: the local and the standard name together, "local (standard)".
    row.shownName && row.current ? t('m27.localName', [row.shownName, row.current.name]) : v.name;

  function start(row: OperationRow | null) {
    const c = row?.current;
    setIsNew(row === null);
    setCode(row?.code ?? '');
    setDraft({
      ...EMPTY,
      ...(c
        ? {
            name: c.name,
            departmental: c.departmental,
            dps: c.dps,
            nwnp: c.nwnp,
            bagBased: c.bagBased,
            normType: c.normType,
            rateNormLink: c.rateNormLink,
            dusting: c.dusting,
            allowancePayCode: c.allowancePayCode,
            presentButNoWork: c.presentButNoWork,
            circularId: c.circularId,
          }
        : {}),
      effectiveFrom: businessToday(),
    });
    setReason('');
    setError(null);
  }

  async function submit(draft: Draft) {
    setError(null);
    try {
      await propose.mutateAsync({ code: code.trim().toUpperCase(), body: { ...draft, reason } });
      toast(t('mst.submitted', [code.trim().toUpperCase()]));
      setDraft(null);
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  async function submitEnd(ending: OperationRow) {
    setError(null);
    try {
      await endDate.mutateAsync({ code: ending.code, lastDay, reason });
      toast(t('m27.endSubmitted', [ending.code]));
      setEnding(null);
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  function startEnd(row: OperationRow) {
    setViewing(null);
    setEnding(row);
    setLastDay(businessToday());
    setReason('');
    setError(null);
  }

  /** Why the row's Edit is off (drawn disabled with the reason, never hidden). */
  function editWhy(row: OperationRow): string {
    if (!mayCreate) return t('wf.why.adminMaintained');
    if (hqBlock) return t(hqBlock);
    return row.pending ? t('m27.why.pending') : t('m27.why.formOpen');
  }

  const set = (patch: Partial<Draft>) => draft && setDraft({ ...draft, ...patch });
  const ready =
    draft !== null &&
    code.trim() !== '' &&
    draft.name.trim() !== '' &&
    (draft.departmental || draft.dps || draft.nwnp) &&
    draft.circularId !== null &&
    reason.trim() !== '' &&
    draft.effectiveFrom !== '';

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        <Act
          label={t('wf.historyAct')}
          onClick={() => (selected ? setHistoryOf(selected.code) : toast(t('wf.selectRow')))}
        />
        {mayCreate && hqBlock === null ? (
          <Act label={t('m27.act.new')} kind="pri" onClick={() => start(null)} />
        ) : (
          <Locked label={t('m27.act.new')} why={t('m27.why.new')} />
        )}
      </div>
      <Problem error={draft || ending ? null : error} />
      <details>
        <summary className="sub2">{t('wf.searchDisclosure')}</summary>
        <div className="toolbar fl-filters">
          <div className="lsearch">
            🔎
            <input
              aria-label={t('m27.search')}
              placeholder={t('m27.search')}
              maxLength={100}
              value={text}
              onChange={(e) => setText(e.target.value)}
            />
          </div>
        </div>
      </details>
      <Tbl
        title={t('m27.table')}
        acts={
          mayExport ? (
            <ExportButton
              href={mdmLinks.operationsExport(listQuery)}
              what={t('screen.M-27')}
              className="btn sm"
            />
          ) : (
            <Explained label={t('action.export')} kind="sm" why={t('m27.why.export')} />
          )
        }
        cols={[
          { t: t('m27.col.code'), s: 'ENT' },
          { t: t('m27.col.name'), s: 'ENT' },
          { t: t('m27.col.payrolls'), s: 'ENT' },
          { t: t('m27.col.bagBased'), s: 'ENT' },
          { t: t('m27.col.band'), s: 'RULE' },
          { t: t('m27.col.link'), s: 'RULE' },
          { t: t('m27.col.circular'), s: 'ENT' },
          { t: t('m27.col.effective'), s: 'ENT' },
          { t: t('wf.col.status'), s: 'SYS' },
          { t: t('wf.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={mdmKeys.operationList(listQuery)}
          fetchPage={mdmPages.operations(listQuery)}
          personal={false}
          empty={<p className="fl-hint">{t('m27.none')}</p>}
          onRows={shown}
        >
          {(rows) =>
            rows.map((row) => {
              // The version in force; a new code shows its pending version until it is approved.
              const v = row.current ?? row.pending;
              const editable = mayCreate && hqBlock === null && !row.pending && !draft;
              return (
                <tr
                  key={row.code}
                  data-code={row.code}
                  aria-selected={selected?.code === row.code}
                  className={selected?.code === row.code ? 'fl-row-selected' : undefined}
                  onClick={() => setSelected(row)}
                >
                  <td>
                    <b>{row.code}</b>
                  </td>
                  <td>{v ? shownName(row, v) : '—'}</td>
                  <td>{v ? payrolls(v) : '—'}</td>
                  <td>{v ? (v.bagBased ? 'Y' : 'N') : '—'}</td>
                  <td>{v?.bagBased ? t('m27.bandFromMaster') : '—'}</td>
                  <td>{v ? linkOf(v) : '—'}</td>
                  <td>{v?.circularNumber ?? '—'}</td>
                  <td>
                    {v ? date(v.effectiveFrom) : '—'}
                    {v?.effectiveTo && <div className="sub2">{t('m26.until', [date(v.effectiveTo)])}</div>}
                  </td>
                  <td>
                    {row.current ? (
                      <span className={`pill ${row.current.effectiveTo ? 'p-gray' : 'p-ok'}`}>
                        {t(row.current.effectiveTo ? 'm27.status.END_DATED' : 'm27.status.ACTIVE')}
                      </span>
                    ) : (
                      <span className="pill p-bad">{t('m27.notInForce')}</span>
                    )}
                    {row.pending && (
                      <div className="sub2">
                        {t('mst.pendingVersion', [row.pending.versionNo, date(row.pending.effectiveFrom)])}
                      </div>
                    )}
                  </td>
                  <td onClick={(e) => e.stopPropagation()}>
                    <RowActs
                      viewName={`${t('common.view')} ${row.code}`}
                      editName={`${t('common.edit')} ${row.code}`}
                      onView={() => setViewing(row)}
                      onEdit={editable ? () => start(row) : undefined}
                      editWhy={editWhy(row)}
                      onApprove={
                        mayApprove
                          ? () =>
                              row.pending
                                ? window.location.assign(withBase('/payroll/?s=W-01'))
                                : toast(t('wf.nothingPending'))
                          : undefined
                      }
                    />
                  </td>
                </tr>
              );
            })
          }
        </ServerPagination>
      </Tbl>
      {historyOf && (
        <Card
          title={t('mst.historyOf', [historyOf])}
          actions={
            <>
              <HistoryButton entity="operation_master" recordKey={historyOf} />
              <button type="button" className="btn sm" onClick={() => setHistoryOf(null)}>
                {t('common.close')}
              </button>
            </>
          }
        >
          <div className="wrap">
            <ServerPagination
              queryKey={mdmKeys.operationHistory(historyOf)}
              fetchPage={mdmPages.operationHistory(historyOf)}
              personal={false}
            >
              {(versions) => (
                <table className="t">
                  <thead>
                    <tr>
                      <th>
                        {t('mst.version')}
                        <Src s="SYS" />
                      </th>
                      <th>
                        {t('mst.from')}
                        <Src s="ENT" />
                      </th>
                      <th>
                        {t('mst.to')}
                        <Src s="SYS" />
                      </th>
                      <th>
                        {t('mst.state')}
                        <Src s="SYS" />
                      </th>
                      <th>
                        {t('m27.payrolls')}
                        <Src s="ENT" />
                      </th>
                      <th>
                        {t('m27.link')}
                        <Src s="RULE" />
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {versions.map((v) => (
                      <tr key={v.id}>
                        <td>v{v.versionNo}</td>
                        <td>{date(v.effectiveFrom)}</td>
                        <td>{v.effectiveTo ? date(v.effectiveTo) : '—'}</td>
                        <td>{t(`status.${v.status}`)}</td>
                        <td>{payrolls(v)}</td>
                        <td>{linkOf(v)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </ServerPagination>
          </div>
        </Card>
      )}
      {viewing && (
        <ViewDialog
          screen={t('screen.M-27')}
          onClose={() => setViewing(null)}
          rows={viewRows(viewing)}
          actions={
            mayEnd &&
            viewing.current &&
            !viewing.current.effectiveTo &&
            (hqBlock ? (
              <Locked label={t('action.endDate')} why={t(hqBlock)} />
            ) : (
              <Act label={t('action.endDate')} onClick={() => startEnd(viewing)} />
            ))
          }
        />
      )}
      {draft && (
        <Card title={isNew ? t('action.newOperation') : t('mst.newVersionOf', [code])}>
          <Problem error={error} />
          <div className="bd rf-grid">
            <div className="rf-field">
              <label htmlFor="op-code">{t('m27.code')}</label>
              <input
                id="op-code"
                maxLength={10}
                disabled={!isNew}
                value={code}
                onChange={(e) => setCode(e.target.value.toUpperCase())}
              />
            </div>
            <div className="rf-field span2">
              <label htmlFor="op-name">{t('m27.name')}</label>
              <input
                id="op-name"
                maxLength={100}
                disabled={!isNew}
                value={draft.name}
                onChange={(e) => set({ name: e.target.value })}
              />
              {!isNew && <span className="hint">{t('m27.nameFixed')}</span>}
            </div>
            <fieldset className="rf-field span2">
              <legend>{t('m27.payrolls')}</legend>
              <label className="fl-inline">
                <input
                  type="checkbox"
                  checked={draft.departmental}
                  onChange={(e) => set({ departmental: e.target.checked })}
                />{' '}
                {t('m14.type.DEPT_LABOUR')}
              </label>
              <label className="fl-inline">
                <input type="checkbox" checked={draft.dps} onChange={(e) => set({ dps: e.target.checked })} />{' '}
                {t('m14.type.DPS_LABOUR')}
              </label>
              <label className="fl-inline">
                <input
                  type="checkbox"
                  checked={draft.nwnp}
                  onChange={(e) => set({ nwnp: e.target.checked })}
                />{' '}
                {t('m14.type.NWNP_LABOUR')}
              </label>
            </fieldset>
            <div className="rf-field">
              <label htmlFor="op-bag">
                <input
                  id="op-bag"
                  type="checkbox"
                  checked={draft.bagBased}
                  onChange={(e) =>
                    set({
                      bagBased: e.target.checked,
                      ...(e.target.checked ? { rateNormLink: 'NORM_SLAB' } : {}),
                    })
                  }
                />{' '}
                {t('m27.bagBased')}
              </label>
              <span className="hint">{t('m27.bagHint')}</span>
            </div>
            <div className="rf-field">
              <label htmlFor="op-link">{t('m27.link')}</label>
              <select
                id="op-link"
                value={draft.rateNormLink}
                disabled={draft.bagBased}
                onChange={(e) => set({ rateNormLink: e.target.value })}
              >
                {RATE_NORM_LINKS.map((l) => (
                  <option key={l} value={l}>
                    {t(`m27.link.${l}`)}
                  </option>
                ))}
              </select>
            </div>
            <div className="rf-field">
              <label htmlFor="op-norm">{t('m27.normType')}</label>
              <select
                id="op-norm"
                value={draft.normType ?? ''}
                onChange={(e) => set({ normType: e.target.value || null })}
              >
                <option value="">{t('common.choose')}</option>
                {NORM_TYPES.map((n) => (
                  <option key={n} value={n}>
                    {t(`m27.norm.${n}`)}
                  </option>
                ))}
              </select>
            </div>
            <div className="rf-field">
              <label htmlFor="op-dust">
                <input
                  id="op-dust"
                  type="checkbox"
                  checked={draft.dusting}
                  onChange={(e) => set({ dusting: e.target.checked, presentButNoWork: false })}
                />{' '}
                {t('m27.dusting')}
              </label>
            </div>
            {draft.dusting && (
              <div className="rf-field">
                <label htmlFor="op-pay">{t('m27.allowancePayCode')}</label>
                <input
                  id="op-pay"
                  type="number"
                  min={1}
                  value={draft.allowancePayCode ?? ''}
                  onChange={(e) =>
                    set({ allowancePayCode: e.target.value === '' ? null : Number(e.target.value) })
                  }
                />
              </div>
            )}
            <div className="rf-field">
              <label htmlFor="op-pbnw">
                <input
                  id="op-pbnw"
                  type="checkbox"
                  checked={draft.presentButNoWork}
                  disabled={draft.dusting}
                  onChange={(e) => set({ presentButNoWork: e.target.checked })}
                />{' '}
                {t('m27.presentButNoWork')}
              </label>
            </div>
            <div className="rf-field span2">
              <label htmlFor="op-circ">{t('m27.circular')}</label>
              <select
                id="op-circ"
                value={draft.circularId ?? ''}
                onChange={(e) => set({ circularId: e.target.value === '' ? null : Number(e.target.value) })}
              >
                <option value="">{t('common.choose')}</option>
                {(circulars.data ?? []).map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.number} · {c.subject}
                  </option>
                ))}
              </select>
              {(circulars.data ?? []).length === 0 && (
                <span className="hint">{t('m27.noActiveCircular')}</span>
              )}
            </div>
            <div className="rf-field">
              <label htmlFor="op-from">{t('mst.from')}</label>
              <input
                id="op-from"
                type="date"
                value={draft.effectiveFrom}
                onChange={(e) => set({ effectiveFrom: e.target.value })}
              />
            </div>
            <div className="rf-field span2">
              <label htmlFor="op-reason">{t('common.reason')}</label>
              <TextAreaField
                id="op-reason"
                value={reason}
                maxLength={500}
                onChange={(e) => setReason(e.target.value)}
              />
            </div>
          </div>
          <p className="fl-hint">{t('mst.checkerNote')}</p>
          <div className="rf-actbar">
            <button
              type="button"
              className="btn"
              onClick={() => {
                setDraft(null);
                setError(null);
              }}
            >
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className="btn pri"
              disabled={!ready || propose.isPending}
              onClick={() => void submit(draft)}
            >
              {t('common.submitForApproval')}
            </button>
          </div>
        </Card>
      )}
      {ending && (
        <Card title={t('m27.endTitle', [ending.code])}>
          <Problem error={error} />
          <div className="bd rf-grid">
            <div className="rf-field">
              <label htmlFor="op-last">{t('m27.lastDay')}</label>
              <input id="op-last" type="date" value={lastDay} onChange={(e) => setLastDay(e.target.value)} />
            </div>
            <div className="rf-field span2">
              <label htmlFor="op-end-reason">{t('common.reason')}</label>
              <TextAreaField
                id="op-end-reason"
                value={reason}
                maxLength={500}
                onChange={(e) => setReason(e.target.value)}
              />
            </div>
          </div>
          <p className="fl-hint">{t('m27.endNote')}</p>
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
              disabled={!reason.trim() || !lastDay || endDate.isPending}
              onClick={() => void submitEnd(ending)}
            >
              {t('common.submitForApproval')}
            </button>
          </div>
        </Card>
      )}
    </div>
  );

  /** The record's fields, each with the source of its column. */
  function viewRows(row: OperationRow) {
    const v = row.current ?? row.pending;
    return [
      { k: t('m27.col.code'), s: 'ENT' as const, v: row.code },
      { k: t('m27.col.name'), s: 'ENT' as const, v: v ? shownName(row, v) : '—' },
      { k: t('m27.col.payrolls'), s: 'ENT' as const, v: v ? payrolls(v) : '—' },
      { k: t('m27.col.bagBased'), s: 'ENT' as const, v: v ? (v.bagBased ? 'Y' : 'N') : '—' },
      { k: t('m27.col.band'), s: 'RULE' as const, v: v?.bagBased ? t('m27.bandFromMaster') : '—' },
      { k: t('m27.col.link'), s: 'RULE' as const, v: v ? linkOf(v) : '—' },
      { k: t('m27.normType'), s: 'RULE' as const, v: v?.normType ? t(`m27.norm.${v.normType}`) : '—' },
      { k: t('m27.allowancePayCode'), s: 'RULE' as const, v: v?.allowancePayCode ?? '—' },
      { k: t('m27.col.circular'), s: 'ENT' as const, v: v?.circularNumber ?? '—' },
      { k: t('mst.from'), s: 'ENT' as const, v: v ? date(v.effectiveFrom) : '—' },
      { k: t('mst.to'), s: 'SYS' as const, v: v?.effectiveTo ? date(v.effectiveTo) : '—' },
      { k: t('mst.version'), s: 'SYS' as const, v: v ? `v${v.versionNo}` : '—' },
    ];
  }
}
