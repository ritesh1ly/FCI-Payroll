'use client';

import { useEffect, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { can, useMe } from '@/shared/session';
import {
  Card,
  ExportButton,
  type PageResult,
  Problem,
  ReasonDialog,
  ServerPagination,
  useToast,
} from '@/shared/ui';
import { Act, Explained, Locked, RowActs, Tbl, Tiles, WfTile } from '@/shared/ui/wireframe';
import type {
  ExceptionCondition,
  ExceptionConditions,
  ExceptionFilter,
  ExceptionItem,
} from '../api/generalApi';
import { canAccept, conditionKey, EXCEPTION_OWNERS, tone, unsettled } from '../business/generalRules';
import {
  exceptionsExportUrl,
  useAcceptException,
  useAssignException,
  generalKeys,
  generalPages,
  useExceptionOffices,
  useExceptionSummary,
} from '../orchestration/generalQueries';
import { Th } from './Parts';

/**
 * X-05 Exceptions Worklist as Wireframes v6.1 draws it (a frozen requirement per OD-121; SRS-G-9,
 * SRS l.604, l.634, l.1643): "⇩ Export" and "✔ Accept with reason" over the screen, the four tiles
 * over the whole scope, and the Cross-Module Exception Worklist card with Filter and Assign, one
 * row per condition with its module, exception, engine, members affected, whether it blocks gate
 * G2, its owner and the row actions (View opens the condition's items; Edit, for the owner role,
 * assigns an owner or accepts; ✔ for the Checker). A row is selected by clicking it: Assign and
 * Accept with reason act on the selected condition. The Payroll Checker may accept a non-blocking
 * gate-G2 item with a recorded reason; a blocking one must be corrected at its source; an HRMS
 * value is routed upstream and never corrected in Payroll. The conditions and their member counts
 * come from the server over the whole filtered scope, never from one page of rows. The filters
 * (status, severity, class, period, office) open from Filter; status and severity are the
 * EXCEPTION_STATUS / EXCEPTION_SEVERITY reference lists (OD-123). The Engine column reads "All":
 * payroll_exception keeps no payroll type (CLR-72, PENDING F-05-3). C-08 is this worklist filtered
 * to one run (?period=&cycle=, shown as a removable filter, OD-73).
 */
export function ExceptionsWorklist() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const mayAssign = can(me.data, 'X-05', 'EDIT');
  const mayAccept = can(me.data, 'X-05', 'APPROVE');
  const mayExport = can(me.data, 'X-05', 'EXPORT');
  const [filter, setFilterState] = useState<ExceptionFilter>({});
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  // C-08: opened from C-02 with the run's period and cycle (read after mount: the server render has
  // no query string to agree with).
  useEffect(() => {
    const query = new URLSearchParams(window.location.search);
    const period = query.get('period');
    const cycle = query.get('cycle');
    if ((period && /^\d{4}-\d{2}$/.test(period)) || (cycle && /^\d{1,18}$/.test(cycle))) {
      setFilterState({
        period: period && /^\d{4}-\d{2}$/.test(period) ? period : undefined,
        cycle: cycle && /^\d{1,18}$/.test(cycle) ? cycle : undefined,
      });
    }
  }, []);
  const summary = useExceptionSummary(filter);
  const offices = useExceptionOffices();
  const assign = useAssignException();
  const accept = useAcceptException();
  const [rowError, setRowError] = useState<unknown>(null);
  const [acceptFor, setAcceptFor] = useState<ExceptionItem | null>(null);
  const [opened, setOpened] = useState<{ condition: ExceptionCondition; edit: boolean } | null>(null);
  const [shown, setShown] = useState<ExceptionCondition[]>([]);
  const tiles = summary.data?.tiles;
  const group = opened?.condition;
  // The opened condition's items: the same filters plus its module and code (a paged list).
  const itemFilter: ExceptionFilter | null = group
    ? { ...filter, module: group.moduleCode ?? undefined, code: group.exceptionCode }
    : null;

  /** A new filter closes the opened condition: its items may no longer be in the list. */
  function setFilter(next: ExceptionFilter) {
    setOpened(null);
    setSelected(null);
    setFilterState(next);
  }

  async function act(run: () => Promise<unknown>, done: string) {
    setRowError(null);
    try {
      await run();
      toast(done);
    } catch (cause) {
      setRowError(cause);
    }
  }

  /** Why the role may not edit a condition's row (drawn disabled with the reason, never hidden). */
  function editWhy(g: ExceptionCondition): string {
    if (!g.resolvableInPayroll) return t('x05.why.hrmsOwned');
    if (!g.actionable) return t('x05.why.settled');
    return t('x05.why.ownerOnly');
  }

  function openSelected(edit: boolean) {
    const g = shown.find((c) => conditionKey(c) === selected);
    if (!g) {
      toast(t('x05.selectCondition'));
      return;
    }
    setOpened({ condition: g, edit });
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {mayExport ? (
          <ExportButton href={exceptionsExportUrl(filter)} what={t('screen.X-05')} label={t('wf.export')} />
        ) : (
          <Explained label={t('wf.export')} why={t('x05.why.export')} />
        )}
        {mayAccept ? (
          <Act label={t('x05.acceptWithReason')} kind="pri" onClick={() => openSelected(true)} />
        ) : (
          <Locked label={t('x05.acceptWithReason')} why={t('x05.why.acceptWithReason')} />
        )}
      </div>
      <Tiles>
        <WfTile
          icon="⛔"
          label={t('x05.tile.open')}
          src="SYS"
          big={tiles?.open ?? '—'}
          sub={t('x05.tile.openSub', [tiles?.open ?? '—', '—'])}
          tone="bad"
          bad={(tiles?.open ?? 0) > 0}
        />
        <WfTile
          icon="🔒"
          label={t('x05.tile.blockingG2')}
          src="SYS"
          big={tiles?.blockingG2 ?? '—'}
          sub={t('x05.tile.blockingSub')}
          tone="bad"
          bad={(tiles?.blockingG2 ?? 0) > 0}
        />
        <WfTile
          icon="✍️"
          label={t('x05.tile.accepted')}
          src="SYS"
          big={tiles?.acceptedWithReason ?? '—'}
          sub={t('x05.tile.acceptedSub')}
          tone="warn"
        />
        <WfTile
          icon="🔌"
          label={t('x05.tile.upstream')}
          src="INTF"
          big={tiles?.upstream ?? '—'}
          sub={t('x05.tile.upstreamSub')}
          tone="pur"
        />
      </Tiles>
      <Problem error={summary.error ?? offices.error ?? rowError} />
      {filter.cycle && (
        <p className="fl-banner">
          {t('x05.c08', [filter.period ?? ''])}{' '}
          <button type="button" className="btn sm" onClick={() => setFilter({ ...filter, cycle: undefined })}>
            {t('x05.c08.clear')}
          </button>
        </p>
      )}
      <Tbl
        title={t('x05.worklist')}
        acts={
          <>
            <Act label={t('x05.filter')} kind="sm" onClick={() => setFiltersOpen(!filtersOpen)} />
            {mayAssign ? (
              <Act label={t('x05.assign')} kind="sm" onClick={() => openSelected(true)} />
            ) : (
              <Locked label={t('x05.assign')} kind="sm" why={t('x05.why.assign')} />
            )}
          </>
        }
        cols={[
          { t: t('x05.col.module'), s: 'SYS' },
          { t: t('x05.col.exception'), s: 'SYS' },
          { t: t('x05.col.engine'), s: 'SYS' },
          { t: t('x05.col.count'), s: 'SYS', num: true },
          { t: t('x05.col.blocksG2'), s: 'RULE' },
          { t: t('x05.col.owner'), s: 'ENT' },
          { t: t('x05.col.actions'), s: 'SYS' },
        ]}
        after={
          <>
            {filtersOpen && (
              <div className="toolbar fl-filters" role="group" aria-label={t('x05.filter')}>
                <label htmlFor="x05-status" className="sub2">
                  {t('gen.status')}
                </label>
                <select
                  id="x05-status"
                  value={filter.status ?? ''}
                  onChange={(e) => setFilter({ ...filter, status: e.target.value || undefined })}
                >
                  <option value="">{t('gen.all')}</option>
                  <ReferenceOptions list="EXCEPTION_STATUS" />
                </select>
                <label htmlFor="x05-severity" className="sub2">
                  {t('x05.severity')}
                </label>
                <select
                  id="x05-severity"
                  value={filter.severity ?? ''}
                  onChange={(e) => setFilter({ ...filter, severity: e.target.value || undefined })}
                >
                  <option value="">{t('gen.all')}</option>
                  <ReferenceOptions list="EXCEPTION_SEVERITY" />
                </select>
                <label htmlFor="x05-module" className="sub2">
                  {t('x05.class')}
                </label>
                <select
                  id="x05-module"
                  value={filter.module ?? ''}
                  onChange={(e) => setFilter({ ...filter, module: e.target.value || undefined })}
                >
                  <option value="">{t('gen.all')}</option>
                  {(summary.data?.modules ?? []).map((m) => (
                    <option key={m} value={m}>
                      {m}
                    </option>
                  ))}
                </select>
                <label htmlFor="x05-period" className="sub2">
                  {t('x05.period')}
                </label>
                <input
                  id="x05-period"
                  type="month"
                  value={filter.period ?? ''}
                  onChange={(e) => setFilter({ ...filter, period: e.target.value || undefined })}
                />
                <label htmlFor="x05-office" className="sub2">
                  {t('x05.office')}
                </label>
                <select
                  id="x05-office"
                  value={filter.office ?? ''}
                  onChange={(e) => setFilter({ ...filter, office: e.target.value || undefined })}
                >
                  <option value="">{t('gen.all')}</option>
                  {(offices.data ?? []).map((o) => (
                    <option key={o.id} value={String(o.id)}>
                      {o.code} - {o.name}
                    </option>
                  ))}
                </select>
              </div>
            )}
            <div className="bd fl-pad-top-0">
              <div className="oq">{t('x05.c08Note')}</div>
            </div>
          </>
        }
      >
        <ServerPagination<ExceptionConditions>
          queryKey={[...generalKeys.exceptions, 'groups', filter]}
          fetchPage={generalPages.exceptionGroups(filter)}
          empty={<p className="fl-hint">{t('x05.none')}</p>}
          onRows={setShown}
        >
          {(groups) => (
            <>
              {groups.map((g) => {
                const key = conditionKey(g);
                const editable = g.actionable && g.resolvableInPayroll && (mayAssign || mayAccept);
                return (
                  <tr
                    key={key}
                    data-code={g.exceptionCode}
                    aria-selected={selected === key}
                    className={selected === key ? 'fl-row-selected' : undefined}
                    onClick={() => setSelected(selected === key ? null : key)}
                  >
                    <td>{g.moduleCode ?? '—'}</td>
                    <td>
                      <b>{g.exceptionCode}</b>
                      <div className="sub2">{g.detail}</div>
                    </td>
                    <td>{t('x05.engine.all')}</td>
                    <td className="num" data-testid={`x05-count-${g.exceptionCode}`}>
                      {/* SRS l.634: members affected; a cycle-level condition counts its items. */}
                      {g.members > 0 ? g.members : g.items}
                      {g.members > 0 && g.items !== g.members && (
                        <div className="sub2">{t('x05.itemCount', [g.items])}</div>
                      )}
                    </td>
                    <td>
                      <span className={`pill ${g.blocksG2 ? 'p-bad' : 'p-warn'}`}>
                        {g.blocksG2 ? t('x05.yes') : t('x05.no')}
                      </span>
                    </td>
                    <td>
                      {g.owner ? t(`role.${g.owner}`) : !g.resolvableInPayroll ? t('x05.owner.hrms') : '—'}
                      {g.correctionScreen && (
                        <div className="sub2">
                          {t('x05.fixWhere')}: {g.correctionScreen}
                        </div>
                      )}
                      {!g.resolvableInPayroll && <div className="sub2">{t('x05.upstream')}</div>}
                    </td>
                    <td onClick={(e) => e.stopPropagation()}>
                      <RowActs
                        viewName={t('x05.viewOf', [g.exceptionCode])}
                        editName={t('x05.editOf', [g.exceptionCode])}
                        onView={() => setOpened({ condition: g, edit: false })}
                        onEdit={editable ? () => setOpened({ condition: g, edit: true }) : undefined}
                        editWhy={editWhy(g)}
                        onApprove={
                          mayAccept && editable ? () => setOpened({ condition: g, edit: true }) : undefined
                        }
                        cancel
                        cancelWhy={t('x05.why.cancel')}
                      />
                    </td>
                  </tr>
                );
              })}
            </>
          )}
        </ServerPagination>
      </Tbl>
      {group && opened && itemFilter && (
        <Card
          key={conditionKey(group)}
          title={t('x05.itemsOf', [group.exceptionCode])}
          actions={
            <button type="button" className="btn sm" onClick={() => setOpened(null)}>
              {t('common.close')}
            </button>
          }
        >
          <ServerPagination<PageResult<ExceptionItem>>
            queryKey={[...generalKeys.exceptions, 'items', itemFilter]}
            fetchPage={generalPages.exceptions(itemFilter)}
            empty={<p className="fl-hint">{t('x05.none')}</p>}
          >
            {(items) => (
              <div className="wrap">
                <table className="t">
                  <thead>
                    <tr>
                      <Th label={t('gen.member')} s="HRMS" />
                      <Th label={t('x05.severity')} s="RULE" />
                      <Th label={t('x05.field')} s="SYS" />
                      <Th label={t('x05.owner')} s="ENT" />
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
                        <td>
                          {t(`x05.severity.${e.severity}`)}
                          {e.blocksGate && <div className="sub2">{t('x05.gate', [e.blocksGate])}</div>}
                          <div className="sub2">{t('x05.raisedIn', [e.moduleCode, e.ageDays])}</div>
                        </td>
                        <td>
                          {e.fieldName ?? '—'}
                          {e.fieldSource && <div className="sub2">{t('x05.source', [e.fieldSource])}</div>}
                        </td>
                        <td>
                          {opened.edit && mayAssign && unsettled(e) && e.resolvableInPayroll ? (
                            <select
                              aria-label={t('x05.assignTo', [e.exceptionCode])}
                              value={e.ownerRoleCode ?? ''}
                              onChange={(ev) =>
                                void act(
                                  () => assign.mutateAsync({ id: e.id, owner: ev.target.value }),
                                  t('x05.assigned', [e.exceptionCode]),
                                )
                              }
                            >
                              <option value="" disabled>
                                —
                              </option>
                              {EXCEPTION_OWNERS.map((o) => (
                                <option key={o} value={o}>
                                  {t(`role.${o}`)}
                                </option>
                              ))}
                            </select>
                          ) : e.ownerRoleCode ? (
                            t(`role.${e.ownerRoleCode}`)
                          ) : !e.resolvableInPayroll ? (
                            // SRS l.636: an upstream item is owned by HRMS, corrected at source.
                            t('x05.owner.hrms')
                          ) : (
                            '—'
                          )}
                        </td>
                        <td>
                          <span className={`pill ${tone(e.status)}`}>{t(`x05.status.${e.status}`)}</span>
                          {e.acceptedReason && <div className="sub2">{e.acceptedReason}</div>}
                          {!e.resolvableInPayroll && <div className="sub2">{t('x05.upstream')}</div>}
                        </td>
                        <td>
                          {opened.edit && mayAccept && unsettled(e) && (
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
        </Card>
      )}
      {acceptFor && (
        <ReasonDialog
          maxLength={1000}
          title={t('x05.acceptTitle', [acceptFor.exceptionCode])}
          onCancel={() => setAcceptFor(null)}
          onConfirm={(reason) => {
            const target = acceptFor;
            setAcceptFor(null);
            void act(
              () => accept.mutateAsync({ id: target.id, reason }),
              t('x05.accepted', [target.exceptionCode]),
            );
          }}
        />
      )}
    </div>
  );
}
