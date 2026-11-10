'use client';

import { useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { ReferenceOptions } from '@/shared/reference';
import { Card, dateTime, Problem, ServerPagination, useToast } from '@/shared/ui';
import { Act, Locked, RowActs, Tbl, Tiles, WfTile } from '@/shared/ui/wireframe';
import type { PayrollQuery, QueryStatus } from '../api/generalApi';
import { canRespond, isAsciiText, resolveRole, tone } from '../business/generalRules';
import {
  generalKeys,
  generalPages,
  useQueryCounts,
  useRaiseQuery,
  useResolveQuery,
  useRespondQuery,
} from '../orchestration/generalQueries';
import { Controls } from './Parts';

const MAX = 4000;

/** The Payroll ID as the register shows it: PR-<period>-<staff number> (result id below); a dash without one. */
export function payrollIdLabel(q: PayrollQuery): string {
  if (q.payrollId === null) return '—';
  return q.eis && q.payrollYymm ? `PR-${q.payrollYymm}-${q.eis}` : String(q.payrollId);
}

/**
 * X-06 Payroll Query Management (SRS-G-11; layout after Wireframe v6.1 X-06): Open -> In Progress
 * -> Resolved -> Closed. A member (eSeva) or payroll staff raises a query against a Payroll ID; the
 * Maker responds; a query about a computed amount is confirmed by the Payroll Checker who did not
 * respond (chk_query_checker); the system closes it. A correction runs through its own
 * maker-checker flow, never here. As Wireframes v6.1 draws it (OD-121): "＋ Raise Query", the four tiles, the Query
 * Register with its Filter (status from the PAYROLL_QUERY_STATUS list, OD-123) and the row actions (👁 View, ✎ Edit
 * for the next step, ✔ for the Checker's confirmation), the lifecycle and the boundary; no export is drawn.
 */
export function PayrollQueries() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const mayRaise = can(me.data, 'X-06', 'CREATE');
  const mayRespond = can(me.data, 'X-06', 'EDIT');
  const mayConfirm = can(me.data, 'X-06', 'APPROVE');
  const [status, setStatus] = useState<QueryStatus | ''>('');
  const [filtersOpen, setFiltersOpen] = useState(false);
  const counts = useQueryCounts();
  const raise = useRaiseQuery();
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ payrollId: '', queryText: '', computed: false });
  const [error, setError] = useState<unknown>(null);
  const [selected, setSelected] = useState<{ query: PayrollQuery; edit: boolean } | null>(null);
  // The queries of the shown page (the open card is refreshed from them); the status filter and
  // the tiles are the server's (X-pagination).
  const [shown, setShown] = useState<PayrollQuery[]>([]);
  const count = (s: QueryStatus) => counts.data?.[s] ?? 0;

  const valid = /^\d{1,12}$/.test(form.payrollId) && isAsciiText(form.queryText, MAX, true);

  async function submit() {
    setError(null);
    try {
      const q = await raise.mutateAsync({
        payrollId: Number(form.payrollId),
        queryText: form.queryText,
        computed: form.computed,
      });
      toast(t('x06.raised', [q.reference]));
      setForm({ payrollId: '', queryText: '', computed: false });
      setOpen(false);
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {mayRaise ? (
          <Act label={t('x06.raiseAct')} kind="pri" onClick={() => setOpen(true)} />
        ) : (
          <Locked label={t('x06.raiseAct')} kind="pri" why={t('x06.why.raise')} />
        )}
      </div>
      <Tiles>
        <WfTile
          icon="📬"
          label={t('x06.tile.open')}
          src="SYS"
          tone="warn"
          big={count('OPEN')}
          sub={t('x06.tile.openSub')}
        />
        <WfTile
          icon="🔧"
          label={t('x06.tile.progress')}
          src="SYS"
          big={count('IN_PROGRESS')}
          sub={t('x06.tile.progressSub')}
        />
        <WfTile
          icon="✅"
          label={t('x06.tile.resolved')}
          src="SYS"
          tone="ok"
          big={count('RESOLVED')}
          sub={t('x06.tile.resolvedSubDrawn')}
        />
        <WfTile
          icon="🗂"
          label={t('x06.tile.closed')}
          src="SYS"
          big={count('CLOSED')}
          sub={t('x06.tile.closedSub')}
        />
      </Tiles>
      <Problem error={counts.error} />
      {open && (
        <Card title={t('x06.raise')}>
          <Problem error={error} />
          <div className="bd rf-grid">
            <div className="rf-field">
              <label htmlFor="x06-payroll">{t('x06.payrollId')}</label>
              <input
                id="x06-payroll"
                inputMode="numeric"
                maxLength={12}
                value={form.payrollId}
                onChange={(e) => setForm({ ...form, payrollId: e.target.value.trim() })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="x06-computed">
                <input
                  id="x06-computed"
                  type="checkbox"
                  checked={form.computed}
                  onChange={(e) => setForm({ ...form, computed: e.target.checked })}
                />{' '}
                {t('x06.computed')}
              </label>
            </div>
            <div className="rf-field span2">
              <label htmlFor="x06-text">{t('x06.text')}</label>
              <textarea
                id="x06-text"
                rows={4}
                maxLength={MAX}
                value={form.queryText}
                onChange={(e) => setForm({ ...form, queryText: e.target.value })}
              />
              <span className="hint">{t('gen.asciiHint', [MAX])}</span>
            </div>
          </div>
          <div className="rf-actbar">
            <button type="button" className="btn" onClick={() => setOpen(false)}>
              {t('common.cancel')}
            </button>
            <button
              type="button"
              className="btn pri"
              disabled={!valid || raise.isPending}
              onClick={() => void submit()}
            >
              {t('x06.raise')}
            </button>
          </div>
        </Card>
      )}
      {selected && (
        <QueryCard
          key={`${selected.query.id}|${selected.edit}`}
          query={shown.find((q) => q.id === selected.query.id) ?? selected.query}
          edit={selected.edit}
          mayRespond={mayRespond}
          mayConfirm={mayConfirm}
          onClose={() => setSelected(null)}
          onDone={(message) => {
            setSelected(null);
            toast(message);
          }}
        />
      )}
      <div className="fl-mt">
        <Tbl
          title={t('x06.register')}
          acts={<Act label={t('x06.filterAct')} kind="sm" onClick={() => setFiltersOpen(!filtersOpen)} />}
          cols={[
            { t: t('x06.col.payrollId'), s: 'SYS' },
            { t: t('x06.col.raisedBy'), s: 'SYS' },
            { t: t('x06.col.text'), s: 'ENT' },
            { t: t('x06.col.response'), s: 'ENT' },
            { t: t('x06.col.checker'), s: 'SYS' },
            { t: t('x06.col.status'), s: 'SYS' },
            { t: t('x06.col.actions'), s: 'SYS' },
          ]}
          after={
            filtersOpen ? (
              <div className="toolbar fl-filters">
                <label>
                  {t('gen.status')}{' '}
                  <select value={status} onChange={(e) => setStatus(e.target.value as QueryStatus | '')}>
                    <option value="">{t('gen.all')}</option>
                    <ReferenceOptions list="PAYROLL_QUERY_STATUS" />
                  </select>
                </label>
              </div>
            ) : undefined
          }
        >
          <ServerPagination
            queryKey={[...generalKeys.queries, status || 'ALL']}
            fetchPage={generalPages.queries(status || undefined)}
            empty={<p className="fl-hint">{t('x06.none')}</p>}
            onRows={setShown}
          >
            {(page) =>
              page.map((q) => {
                const answer = mayRespond && canRespond(q);
                const confirm = resolvable(q, mayRespond, mayConfirm);
                const done = q.status === 'RESOLVED' || q.status === 'CLOSED';
                return (
                  <tr key={q.id} data-reference={q.reference}>
                    <td>
                      <b>{payrollIdLabel(q)}</b>
                      <div className="sub2">{q.reference}</div>
                    </td>
                    <td>
                      {q.channel === 'ESEVA'
                        ? t('x06.byMember', [q.memberName ?? q.raisedBy])
                        : t('x06.byStaff', [q.raisedBy])}
                    </td>
                    <td className="fl-clip">{q.queryText}</td>
                    <td className="fl-clip">{q.makerResponse ?? '—'}</td>
                    <td>{q.concernsComputedAmount && q.resolvedBy ? q.resolvedBy : '—'}</td>
                    <td>
                      <span className={`pill ${tone(q.status)}`}>{t(`x06.status.${q.status}`)}</span>
                      {q.concernsComputedAmount && <div className="sub2">{t('x06.computedShort')}</div>}
                    </td>
                    <td>
                      <RowActs
                        viewName={t('x06.viewOf', [q.reference])}
                        editName={t('x06.editOf', [q.reference])}
                        onView={() => setSelected({ query: q, edit: false })}
                        onEdit={answer || confirm ? () => setSelected({ query: q, edit: true }) : undefined}
                        editWhy={done ? t(`x06.why.${q.status}`) : t('x06.noStep')}
                        onApprove={
                          mayConfirm && confirm && resolveRole(q) === 'CHECKER'
                            ? () => setSelected({ query: q, edit: true })
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
      </div>
      <div className="g2 fl-mt">
        <Controls
          title={t('x06.lifecycle')}
          refs="SRS-G-11"
          items={[
            { icon: '1', tone: 'info', label: t('x06.life.raise'), s: 'ENT', sub: t('x06.life.raiseSub') },
            {
              icon: '2',
              tone: 'warn',
              label: t('x06.life.respond'),
              s: 'ENT',
              sub: t('x06.life.respondSub'),
            },
            { icon: '3', tone: 'ok', label: t('x06.life.confirm'), s: 'SYS', sub: t('x06.life.confirmSub') },
            { icon: '4', tone: 'info', label: t('x06.life.close'), s: 'SYS', sub: t('x06.life.closeSub') },
          ]}
        />
        <Controls
          title={t('x06.boundary')}
          items={[
            { icon: '🔗', tone: 'gray', label: t('x06.bound.correction'), s: 'SYS' },
            { icon: '🗂', tone: 'gray', label: t('x06.bound.audit'), s: 'SYS' },
            { icon: '🧮', tone: 'pur', label: t('x06.bound.noCompute'), s: 'SYS' },
          ]}
        />
      </div>
    </div>
  );
}

/** Whether the session takes the next resolution step of an In Progress query. */
function resolvable(q: PayrollQuery, mayRespond: boolean, mayConfirm: boolean): boolean {
  const role = resolveRole(q);
  // Computed amount: the Checker confirms. Otherwise the answering Maker (or a Checker) closes it.
  return role === 'CHECKER' ? mayConfirm : role === 'MAKER' && (mayRespond || mayConfirm);
}

/** One query: its history and, in edit mode, the next step the session may take. */
function QueryCard({
  query,
  edit,
  mayRespond,
  mayConfirm,
  onClose,
  onDone,
}: {
  query: PayrollQuery;
  edit: boolean;
  mayRespond: boolean;
  mayConfirm: boolean;
  onClose: () => void;
  onDone: (message: string) => void;
}) {
  const { t } = useI18n();
  const respond = useRespondQuery();
  const resolve = useResolveQuery();
  const [text, setText] = useState('');
  const [computed, setComputed] = useState(query.concernsComputedAmount);
  const [error, setError] = useState<unknown>(null);
  const role = resolveRole(query);
  const mayAnswer = edit && mayRespond && canRespond(query);
  const mayResolve = edit && resolvable(query, mayRespond, mayConfirm);
  const valid = isAsciiText(text, MAX, true);

  async function run(step: () => Promise<PayrollQuery>) {
    setError(null);
    try {
      const q = await step();
      onDone(t('x06.updated', [q.reference, t(`x06.status.${q.status}`)]));
    } catch (cause) {
      setError(cause);
    }
  }

  return (
    <Card title={t('x06.detail', [query.reference])}>
      <Problem error={error} />
      <dl className="fl-dl">
        <dt>{t('x06.text')}</dt>
        <dd className="fl-pre">{query.queryText}</dd>
        <dt>{t('x06.raisedBy')}</dt>
        <dd>
          {query.raisedBy} · {dateTime(query.raisedAt)}
        </dd>
        {query.makerResponse && (
          <>
            <dt>{t('x06.response')}</dt>
            <dd className="fl-pre">{query.makerResponse}</dd>
            <dt>{t('x06.respondedBy')}</dt>
            <dd>
              {query.respondedBy} · {dateTime(query.respondedAt)}
            </dd>
          </>
        )}
        {query.resolutionText && (
          <>
            <dt>{t('x06.resolution')}</dt>
            <dd className="fl-pre">{query.resolutionText}</dd>
            <dt>{t('x06.resolvedBy')}</dt>
            <dd>
              {query.resolvedBy} · {dateTime(query.resolvedAt)}
            </dd>
          </>
        )}
      </dl>
      {(mayAnswer || mayResolve) && (
        <div className="bd rf-grid">
          {mayAnswer && (
            <div className="rf-field">
              <label htmlFor="x06-resp-computed">
                {/* Set once, never cleared: the Checker confirms a computed-amount query (SRS-22-5). */}
                <input
                  id="x06-resp-computed"
                  type="checkbox"
                  checked={computed}
                  disabled={query.concernsComputedAmount}
                  title={query.concernsComputedAmount ? t('error.QRY-422-COMPUTED') : undefined}
                  onChange={(e) => setComputed(e.target.checked)}
                />{' '}
                {t('x06.computed')}
              </label>
            </div>
          )}
          <div className="rf-field span2">
            <label htmlFor="x06-step">{mayAnswer ? t('x06.response') : t('x06.resolution')}</label>
            <textarea
              id="x06-step"
              rows={3}
              maxLength={MAX}
              value={text}
              onChange={(e) => setText(e.target.value)}
            />
          </div>
        </div>
      )}
      {role === 'CHECKER' && !mayConfirm && <p className="fl-hint">{t('x06.checkerConfirms')}</p>}
      <div className="rf-actbar">
        <button type="button" className="btn" onClick={onClose}>
          {t('common.close')}
        </button>
        {mayAnswer && (
          <button
            type="button"
            className="btn pri"
            disabled={!valid || respond.isPending}
            onClick={() => void run(() => respond.mutateAsync({ id: query.id, response: text, computed }))}
          >
            {t('x06.respond')}
          </button>
        )}
        {mayResolve && (
          <button
            type="button"
            className="btn pri"
            disabled={!valid || resolve.isPending}
            onClick={() => void run(() => resolve.mutateAsync({ id: query.id, resolution: text }))}
          >
            {role === 'CHECKER' ? t('x06.confirm') : t('x06.resolve')}
          </button>
        )}
      </div>
    </Card>
  );
}
