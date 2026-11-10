'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { can, periodLabel, useMe } from '@/shared/session';
import {
  Card,
  ExportButton,
  type PageResult,
  Problem,
  ReasonDialog,
  ServerPagination,
  Src,
  type Source,
  useToast,
  withBase,
} from '@/shared/ui';
import { Explained, Locked, RowActs, Tbl, Tiles, Toolbar, WfTile } from '@/shared/ui/wireframe';
import type { ComputationCondition, ExceptionItem, ReviewFilter } from '../api/generalApi';
import { canAccept, tone, unsettled } from '../business/generalRules';
import {
  computationExportUrl,
  generalKeys,
  generalPages,
  useAcceptException,
  useComputationSummary,
} from '../orchestration/generalQueries';
import { Th } from './Parts';

/** The role a fixable condition belongs to, as the worklist stores it (owner_role_code). */
const LABOUR_DEALING_HAND = 'LABOUR_DEALING_HAND';
const ESTABLISHMENT_HR = 'ESTABLISHMENT_HR';
const PAYROLL_MAKER = 'PAYROLL_MAKER';

/** A correction screen with a screen of its own (not free text from an older row). */
const SCREEN_CODE = /^[A-Z]-\d{2}$/;

/** Why the role may not edit a condition's row, the pack's text per owner (RA_MAKER, RA_LDH, RA_HRM, RO). */
function editWhyKey(c: ComputationCondition): string {
  if (!c.resolvable) {
    return c.fieldSource === 'ESS' ? 'c08.why.member' : 'c08.why.hrmsOwned';
  }
  if (c.owner === LABOUR_DEALING_HAND) return 'c08.why.ldh';
  if (c.owner === ESTABLISHMENT_HR) return 'c08.why.hr';
  return 'c08.why.maker';
}

/**
 * C-08 Payroll Computation - Exceptions Review as Wireframes v6.1 draws it (OD-121; SRS-G-9, SRS
 * l.1641-1678, US-05-08): the X-05 worklist filtered to the current run, not a separate store. The
 * tiles and the list come from the C-08 read, so Establishment / HR reads it without X-05. One row
 * per condition with the eight fields of the SRS. View opens the condition's items; Edit, for the
 * role that fixes the condition, opens the screen where it is fixed (an HRMS-owned field is
 * corrected in HRMS, SRS-G-45, and stays disabled with the reason); ✔ opens the items for the
 * Checker's acceptance under X-05 APPROVE, a blocking condition stays disabled (SRS-G-9).
 *
 * Drawn objects that wait for a source stay drawn with the rule unchanged: the severity grades have
 * no mapping to the stored severities (CLR-46), so the drawn filter says why when used and
 * the stored severity filters in the closed section; Assign is the Checker's and the Administrator's
 * right, kept on X-05; Defer has no source and no status; Re-run affected waits for the scoped
 * recompute (WF-1100, WF-1102).
 */
export function ComputationExceptions() {
  const { t, locale } = useI18n();
  const toast = useToast();
  const me = useMe();
  const period = me.data?.period ?? '';
  const roles = me.data?.roles ?? [];
  const maker = roles.includes(PAYROLL_MAKER);
  const mayExport = can(me.data, 'C-08', 'EXPORT');
  const mayAccept = can(me.data, 'X-05', 'APPROVE');
  const [severity, setSeverity] = useState('');
  const [search, setSearch] = useState('');
  const [opened, setOpened] = useState<ComputationCondition | null>(null);
  const filter: ReviewFilter = { period, severity: severity || undefined, search: search || undefined };
  const summary = useComputationSummary(filter);
  const tiles = summary.data?.tiles;
  const run = summary.data?.period;
  const exportButton = (label: string) =>
    mayExport ? (
      <ExportButton href={computationExportUrl(filter)} what={t('screen.C-08')} label={label} />
    ) : (
      // Drawn enabled for every role; the right is the SRS's, so pressing it says why and sends nothing (OD-125).
      <Explained label={label} why={t('c08.why.export')} />
    );

  /** Edit opens the screen where the condition is fixed; nothing is corrected on this list. */
  function fixOn(c: ComputationCondition): (() => void) | undefined {
    const mine = c.owner !== null && roles.includes(c.owner);
    if (!c.resolvable || !mine) return undefined;
    return () => {
      if (c.correctionScreen && SCREEN_CODE.test(c.correctionScreen)) {
        window.location.assign(withBase(`/payroll/?s=${c.correctionScreen}`));
      } else {
        setOpened(c);
      }
    };
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {exportButton(t('c08.exportWorklist'))}
        {maker ? (
          <Explained label={t('c08.rerun')} kind="pri" why={t('c08.why.rerun')} />
        ) : (
          <Locked label={t('c08.rerun')} kind="pri" why={t('c08.why.rerunMaker')} />
        )}
      </div>
      <Tiles>
        <WfTile
          icon="⛔"
          label={t('c08.tile.total')}
          src="SYS"
          big={tiles?.total ?? '—'}
          sub={t('c08.tile.totalSub', [tiles?.blocking ?? '—'])}
          tone="bad"
          bad={(tiles?.total ?? 0) > 0}
        />
        <WfTile
          icon="✎"
          label={t('c08.tile.fixable')}
          src="ENT"
          big={tiles?.fixable ?? '—'}
          sub={t('c08.tile.fixableSub')}
          tone="ok"
        />
        <WfTile
          icon="🔗"
          label={t('c08.tile.hrms')}
          src="HRMS"
          big={tiles?.hrms ?? '—'}
          sub={t('c08.tile.hrmsSub')}
          tone="pur"
        />
        <WfTile
          icon="⚙️"
          label={t('c08.tile.system')}
          src="SYS"
          big={tiles?.systemOrMember ?? '—'}
          sub={t('c08.tile.systemSub')}
        />
      </Tiles>
      <Toolbar search={search} onSearch={setSearch} exportControl={exportButton(t('wf.export'))}>
        {/*
          Drawn enabled; the grades wait for FCI's mapping to the stored severities (CLR-46), so choosing one
          says why and filters nothing: no severity is guessed. The stored severity filters in the closed section.
        */}
        <select
          aria-label={t('c08.severity')}
          title={t('c08.why.severity')}
          data-why={t('c08.why.severity')}
          value=""
          onChange={() => toast(t('c08.why.severity'))}
        >
          <option value="">{t('c08.allSeverities')}</option>
          <ReferenceOptions list="C08_SEVERITY" />
        </select>
      </Toolbar>
      {severity && (
        <div className="oq">
          <b>{t('c08.filtered')}</b> {t('c08.filteredOn', [t(`x05.severity.${severity}`)])}{' '}
          <button type="button" className="btn sm" onClick={() => setSeverity('')}>
            {t('c08.clearFilter')}
          </button>
        </div>
      )}
      <Problem error={summary.error} />
      <Tbl
        title={t('c08.worklist')}
        acts={
          <>
            {maker ? (
              <Explained label={t('c08.assign')} kind="sm" why={t('c08.why.assignRight')} />
            ) : (
              <Locked label={t('c08.assign')} kind="sm" why={t('c08.why.assign')} />
            )}
            {maker ? (
              <Explained label={t('c08.defer')} kind="sm" why={t('c08.why.deferSource')} />
            ) : (
              <Locked label={t('c08.defer')} kind="sm" why={t('c08.why.defer')} />
            )}
          </>
        }
        cols={[
          { t: t('c08.col.exception'), s: 'SYS' },
          { t: t('c08.col.type'), s: 'SYS' },
          { t: t('c08.col.count'), s: 'SYS', num: true },
          { t: t('c08.col.severity'), s: 'RULE' },
          { t: t('c08.col.field'), s: 'SYS' },
          { t: t('c08.col.source'), s: 'SYS' },
          { t: t('c08.col.fix'), s: 'SYS' },
          { t: t('c08.col.blocks'), s: 'RULE' },
          { t: t('c08.col.actions'), s: 'SYS' },
        ]}
        after={
          <>
            {run && run !== period && (
              <div className="bd fl-pad-top-0">
                <div className="oq">{t('c08.runNote', [periodLabel(run, locale)])}</div>
              </div>
            )}
            <details className="fl-mt" data-testid="c08-more">
              <summary>{t('c08.moreFilters')}</summary>
              <div className="toolbar">
                <label htmlFor="c08-stored" className="sub2">
                  {t('x05.severity')}
                </label>
                <select id="c08-stored" value={severity} onChange={(e) => setSeverity(e.target.value)}>
                  <option value="">{t('gen.all')}</option>
                  <ReferenceOptions list="EXCEPTION_SEVERITY" />
                </select>
              </div>
            </details>
          </>
        }
      >
        <ServerPagination<PageResult<ComputationCondition>>
          queryKey={[...generalKeys.exceptions, 'run', filter]}
          fetchPage={generalPages.computationExceptions(filter)}
          empty={<p className="fl-hint">{t('c08.none')}</p>}
        >
          {(rows) =>
            rows.map((c) => (
              <tr key={`${c.moduleCode}|${c.exceptionCode}`} data-code={c.exceptionCode}>
                <td>
                  <b>{c.detail}</b>
                  <div className="sub2">{c.exceptionCode}</div>
                </td>
                <td>{t(`c08.type.${c.payrollType}`)}</td>
                <td className="num">{c.count}</td>
                <td>
                  <span className={`pill ${c.blocksLock ? 'p-bad' : 'p-warn'}`}>
                    {t(`x05.severity.${c.severity}`)}
                  </span>
                </td>
                <td>{c.fieldName ?? '—'}</td>
                <td>
                  {c.fieldSource ? (
                    <>
                      {t(`c08.source.${c.fieldSource}`)} <Src s={c.fieldSource as Source} />
                    </>
                  ) : (
                    '—'
                  )}
                </td>
                <td>
                  {!c.resolvable
                    ? t('c08.fix.upstream')
                    : c.correctionScreen
                      ? t('c08.fix.screen', [c.correctionScreen])
                      : '—'}
                </td>
                <td>
                  <span className={`pill ${c.blocksLock ? 'p-bad' : 'p-gray'}`}>
                    {c.blocksLock ? t('x05.yes') : t('x05.no')}
                  </span>
                </td>
                <td>
                  <RowActs
                    viewName={t('x05.viewOf', [c.exceptionCode])}
                    editName={t('x05.editOf', [c.exceptionCode])}
                    onView={() => setOpened(c)}
                    onEdit={fixOn(c)}
                    editWhy={t(editWhyKey(c))}
                    onApprove={mayAccept && c.resolvable && !c.blocksLock ? () => setOpened(c) : undefined}
                    approveWhy={mayAccept && c.resolvable && c.blocksLock ? t('c08.why.blocking') : undefined}
                    cancel={c.owner === ESTABLISHMENT_HR}
                    cancelWhy={t('x05.why.cancel')}
                  />
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      {opened && (
        <ConditionItems
          condition={opened}
          filter={filter}
          mayAccept={mayAccept}
          onClose={() => setOpened(null)}
          onAccepted={(code) => toast(t('x05.accepted', [code]))}
        />
      )}
    </div>
  );
}

/** The items of one condition, member by member; the Checker accepts a non-blocking one with a reason. */
function ConditionItems({
  condition,
  filter,
  mayAccept,
  onClose,
  onAccepted,
}: {
  condition: ComputationCondition;
  filter: ReviewFilter;
  mayAccept: boolean;
  onClose: () => void;
  onAccepted: (code: string) => void;
}) {
  const { t } = useI18n();
  const accept = useAcceptException();
  const [acceptFor, setAcceptFor] = useState<ExceptionItem | null>(null);
  const [error, setError] = useState<unknown>(null);

  async function confirm(item: ExceptionItem, reason: string) {
    setAcceptFor(null);
    setError(null);
    try {
      await accept.mutateAsync({ id: item.id, reason });
      onAccepted(item.exceptionCode);
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <Card
      title={t('x05.itemsOf', [condition.exceptionCode])}
      actions={
        <button type="button" className="btn sm" onClick={onClose}>
          {t('common.close')}
        </button>
      }
    >
      <Problem error={error} />
      <ServerPagination<PageResult<ExceptionItem>>
        queryKey={[
          ...generalKeys.exceptions,
          'run-items',
          filter.period,
          condition.moduleCode,
          condition.exceptionCode,
        ]}
        fetchPage={generalPages.computationItems(filter, condition.moduleCode, condition.exceptionCode)}
        empty={<p className="fl-hint">{t('x05.none')}</p>}
      >
        {(items) => (
          <div className="wrap">
            <table className="t">
              <thead>
                <tr>
                  <Th label={t('gen.member')} s="HRMS" />
                  <Th label={t('x05.field')} s="SYS" />
                  <Th label={t('gen.status')} s="SYS" />
                  <th />
                </tr>
              </thead>
              <tbody>
                {items.map((e) => (
                  <tr key={e.id} data-item={e.id}>
                    <td>
                      {e.eis ? (
                        <>
                          <b>{e.eis}</b>
                          <div className="sub2">{e.memberName}</div>
                        </>
                      ) : (
                        <span className="sub2">{t('x05.cycleLevel')}</span>
                      )}
                    </td>
                    <td>{e.fieldName ?? '—'}</td>
                    <td>
                      <span className={`pill ${tone(e.status)}`}>{t(`x05.status.${e.status}`)}</span>
                      {e.acceptedReason && <div className="sub2">{e.acceptedReason}</div>}
                    </td>
                    <td>
                      {mayAccept && unsettled(e) && (
                        <button
                          type="button"
                          className="btn sm"
                          disabled={!canAccept(e)}
                          title={canAccept(e) ? undefined : t('x05.acceptBlocked')}
                          onClick={() => setAcceptFor(e)}
                        >
                          {t('x05.accept')}
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </ServerPagination>
      {acceptFor && (
        <ReasonDialog
          maxLength={1000}
          title={t('x05.acceptTitle', [acceptFor.exceptionCode])}
          onCancel={() => setAcceptFor(null)}
          onConfirm={(reason) => void confirm(acceptFor, reason)}
        />
      )}
    </Card>
  );
}
