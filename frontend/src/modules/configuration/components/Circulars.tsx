'use client';

import { useCallback, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions, useReference } from '@/shared/reference';
import { can, useMe } from '@/shared/session';
import {
  Card,
  date,
  dateTime,
  ExportButton,
  Problem,
  ReasonDialog,
  ServerPagination,
  TextAreaField,
  useToast,
  withBase,
} from '@/shared/ui';
import { Act, Explained, Locked, Panel, RowActs, Tbl, ViewDialog } from '@/shared/ui/wireframe';
import { businessToday } from '@/shared/time';
import type { CircularView, NewCircular, PayrollType, CircularQuery } from '../api/mdmApi';
import { PAYROLL_TYPES } from '../business/mdmRules';
import {
  mdmKeys,
  mdmLinks,
  mdmPages,
  useCircularOffices,
  useRecordCircular,
  useWithdrawCircular,
} from '../orchestration/mdmQueries';

const STATUSES = ['ACTIVE', 'PENDING_APPROVAL', 'SUPERSEDED', 'WITHDRAWN', 'REJECTED'] as const;

const EMPTY: Omit<NewCircular, 'reason'> = {
  number: '',
  date: '',
  issuingAuthority: '',
  subject: '',
  category: '',
  payrollTypes: [],
  appliesAllOffices: true,
  officeIds: [],
  effectiveFrom: '',
  effectiveTo: null,
  supersedesId: null,
};

const STATUS_PILL: Record<string, string> = {
  ACTIVE: 'p-ok',
  PENDING_APPROVAL: 'p-warn',
  SUPERSEDED: 'p-gray',
  WITHDRAWN: 'p-bad',
  REJECTED: 'p-bad',
};

/**
 * M-25 Circular / Order Master as Wireframes v6.1 draws it (a frozen requirement per OD-121;
 * SRS-G-38, SRS l.938): "🕘 History", "🔗 Where used", "＋ New circular", "⤴ Supersede" and
 * "⛔ Withdraw" over the screen (Maker or Administrator; Checker approves), the Circulars and general
 * orders card with Export, and side by side the Where used table of the selected circular and the
 * General vs personal orders panel. A row is selected by clicking it (the first of the page until
 * one is chosen): History, Where used, Supersede and Withdraw act on it. Every general order is
 * recorded once with its copy (PDF, opened from the record's View, WF-085) and linked wherever it is
 * used; new, supersede and withdraw go to the checker of the maker's office. Every user with access
 * searches by number, subject, category, date and status (SRS-G-38): the pack draws no search, so it
 * closes under its disclosure (WF-082). The row's Edit starts a superseding circular: an order is
 * never changed in place (WF-086).
 */
export function Circulars() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const reference = useReference();
  const [text, setText] = useState('');
  const [category, setCategory] = useState('');
  const [status, setStatus] = useState('');
  // SRS-G-38 (UI_BMAD_g1 #38): search by the circular date too; a reversed range is not sent.
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const rangeReversed = from !== '' && to !== '' && to < from;
  const listQuery: CircularQuery = {
    text: text.trim() || undefined,
    category: category || undefined,
    status: status || undefined,
    from: rangeReversed ? undefined : from || undefined,
    to: rangeReversed ? undefined : to || undefined,
  };
  const record = useRecordCircular();
  const withdraw = useWithdrawCircular();
  const [selected, setSelected] = useState<CircularView | null>(null);
  const [viewing, setViewing] = useState<CircularView | null>(null);
  const [draft, setDraft] = useState<Omit<NewCircular, 'reason'> | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [reason, setReason] = useState('');
  const [withdrawing, setWithdrawing] = useState<CircularView | null>(null);
  const [error, setError] = useState<unknown>(null);
  const mayCreate = can(me.data, 'M-25', 'CREATE');
  const mayWithdraw = can(me.data, 'M-25', 'EDIT');
  const mayApprove = can(me.data, 'M-25', 'APPROVE');
  const mayExport = can(me.data, 'M-25', 'EXPORT');
  const offices = useCircularOffices(draft !== null && !draft.appliesAllOffices);
  const shown = useCallback((page: CircularView[]) => {
    setSelected((prev) => page.find((c) => c.id === prev?.id) ?? page[0] ?? null);
  }, []);

  function startNew(supersedes: CircularView | null) {
    setDraft({
      ...EMPTY,
      date: businessToday(),
      effectiveFrom: businessToday(),
      ...(supersedes
        ? {
            issuingAuthority: supersedes.issuingAuthority,
            category: supersedes.category,
            payrollTypes: supersedes.payrollTypes,
            supersedesId: supersedes.id,
          }
        : {}),
    });
    setFile(null);
    setReason('');
    setError(null);
  }

  /** Runs an action of the toolbar on the selected circular, which must be Active. */
  function onActive(run: (c: CircularView) => void) {
    if (!selected) toast(t('wf.selectRow'));
    else if (selected.status !== 'ACTIVE') toast(t('m25.why.notActive'));
    else run(selected);
  }

  function toggleType(type: PayrollType, on: boolean) {
    if (!draft) return;
    setDraft({
      ...draft,
      payrollTypes: on ? [...draft.payrollTypes, type] : draft.payrollTypes.filter((x) => x !== type),
    });
  }

  async function submit() {
    if (!draft || !file) return;
    setError(null);
    try {
      await record.mutateAsync({ body: { ...draft, reason }, file });
      toast(t('m25.submitted', [draft.number]));
      setDraft(null);
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  async function confirmWithdraw(why: string) {
    if (!withdrawing) return;
    setError(null);
    try {
      await withdraw.mutateAsync({ id: withdrawing.id, reason: why });
      toast(t('m25.withdrawSubmitted', [withdrawing.number]));
    } catch (cause) {
      if (!(cause instanceof ApiError && cause.messageKey === 'otp.cancelled')) setError(cause);
    } finally {
      setWithdrawing(null);
    }
  }

  const payrollsOf = (c: CircularView) => c.payrollTypes.map((p) => t(`m14.type.${p}`)).join(', ');
  const officesOf = (c: CircularView) =>
    c.appliesAllOffices ? t('m25.all') : c.offices.map((o) => o.office ?? String(o.locationId)).join(', ');
  const effectiveOf = (c: CircularView) =>
    `${c.effectiveFrom ? date(c.effectiveFrom) : '—'} – ${c.effectiveTo ? date(c.effectiveTo) : ''}`.trim();

  /** Why the row's Edit is off (drawn disabled with the reason, never hidden). */
  function editWhy(c: CircularView): string {
    if (!mayCreate) return t('wf.viewOnly');
    return c.status === 'ACTIVE' ? t('m25.why.formOpen') : t('m25.why.notActive');
  }

  const ready =
    draft !== null &&
    file !== null &&
    reason.trim() !== '' &&
    draft.number.trim() !== '' &&
    draft.subject.trim() !== '' &&
    draft.issuingAuthority !== '' &&
    draft.category !== '' &&
    draft.payrollTypes.length > 0 &&
    draft.effectiveFrom !== '' &&
    // UI_BMAD_g1 #42: Effective To, when given, is not before Effective From.
    (draft.effectiveTo === null || draft.effectiveTo >= draft.effectiveFrom) &&
    (draft.appliesAllOffices || draft.officeIds.length > 0);

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {selected ? (
          <HistoryButton
            entity="circular_order"
            recordKey={String(selected.id)}
            label={selected.number}
            text={t('wf.historyAct')}
            className="btn"
          />
        ) : (
          <Act label={t('wf.historyAct')} onClick={() => toast(t('wf.selectRow'))} />
        )}
        <Act
          label={t('m25.act.whereUsed')}
          onClick={() =>
            selected
              ? document.getElementById('m25-where-used')?.scrollIntoView({ behavior: 'smooth' })
              : toast(t('wf.selectRow'))
          }
        />
        {mayCreate ? (
          <>
            <Act label={t('m25.act.new')} kind="pri" onClick={() => startNew(null)} />
            <Act label={t('m25.act.supersede')} onClick={() => onActive(startNew)} />
          </>
        ) : (
          <>
            <Locked label={t('m25.act.new')} why={t('m25.why.new')} />
            <Locked label={t('m25.act.supersede')} why={t('m25.why.act')} />
          </>
        )}
        {mayWithdraw ? (
          <Act label={t('m25.act.withdraw')} onClick={() => onActive(setWithdrawing)} />
        ) : (
          <Locked label={t('m25.act.withdraw')} why={t('m25.why.act')} />
        )}
      </div>
      <Problem error={draft ? null : error} />
      <details>
        <summary className="sub2">{t('wf.searchDisclosure')}</summary>
        <div className="toolbar fl-filters">
          <label htmlFor="ci-text" className="sub2">
            {t('m25.search')}
          </label>
          <input id="ci-text" maxLength={64} value={text} onChange={(e) => setText(e.target.value)} />
          <label htmlFor="ci-cat" className="sub2">
            {t('m25.category')}
          </label>
          <select id="ci-cat" value={category} onChange={(e) => setCategory(e.target.value)}>
            <option value="">{t('common.all')}</option>
            <ReferenceOptions list="CIRCULAR_CATEGORY" />
          </select>
          <label htmlFor="ci-from" className="sub2">
            {t('m25.dateFrom')}
          </label>
          <input id="ci-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          <label htmlFor="ci-to" className="sub2">
            {t('m25.dateTo')}
          </label>
          <input id="ci-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
          <label htmlFor="ci-status" className="sub2">
            {t('mst.state')}
          </label>
          <select id="ci-status" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">{t('common.all')}</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {t(`m25.status.${s}`)}
              </option>
            ))}
          </select>
          {rangeReversed && (
            <span className="fl-error" role="alert">
              {t('m25.rangeReversed')}
            </span>
          )}
        </div>
      </details>
      <Tbl
        title={t('m25.table')}
        acts={
          mayExport ? (
            <ExportButton
              href={mdmLinks.circularsExport(listQuery)}
              what={t('screen.M-25')}
              className="btn sm"
            />
          ) : (
            <Explained label={t('action.export')} kind="sm" why={t('m25.why.export')} />
          )
        }
        cols={[
          { t: t('m25.col.number'), s: 'ENT' },
          { t: t('m25.col.date'), s: 'ENT' },
          { t: t('m25.col.authority'), s: 'ENT' },
          { t: t('m25.col.subject'), s: 'ENT' },
          { t: t('m25.col.category'), s: 'ENT' },
          { t: t('m25.col.payrollTypes'), s: 'ENT' },
          { t: t('m25.col.region'), s: 'ENT' },
          { t: t('m25.col.effective'), s: 'ENT' },
          { t: t('m25.col.supersedes'), s: 'ENT' },
          { t: t('m25.col.document'), s: 'ENT' },
          { t: t('m25.col.whereUsed'), s: 'SYS', num: true },
          { t: t('wf.col.status'), s: 'SYS' },
          { t: t('wf.col.actions'), s: 'SYS' },
        ]}
      >
        <ServerPagination
          queryKey={mdmKeys.circulars(listQuery)}
          fetchPage={mdmPages.circulars(listQuery)}
          personal={false}
          empty={<p className="fl-hint">{t('m25.none')}</p>}
          onRows={shown}
        >
          {(rows) =>
            rows.map((c) => (
              <tr
                key={c.id}
                data-number={c.number}
                aria-selected={selected?.id === c.id}
                className={selected?.id === c.id ? 'fl-row-selected' : undefined}
                onClick={() => setSelected(c)}
              >
                <td>
                  <b>{c.number}</b>
                </td>
                <td>{date(c.date)}</td>
                <td>{reference.label('ISSUING_AUTHORITY', c.issuingAuthority)}</td>
                <td>{c.subject}</td>
                <td>{reference.label('CIRCULAR_CATEGORY', c.category)}</td>
                <td>{payrollsOf(c) || '—'}</td>
                <td>{officesOf(c)}</td>
                <td>{effectiveOf(c)}</td>
                <td>{c.supersedesNumber ?? '—'}</td>
                <td>{c.documentId !== null ? t('m25.pdf') : '—'}</td>
                <td className="num">{c.whereUsed}</td>
                <td>
                  <span className={`pill ${STATUS_PILL[c.status] ?? ''}`}>{t(`m25.status.${c.status}`)}</span>
                </td>
                <td onClick={(e) => e.stopPropagation()}>
                  <RowActs
                    viewName={`${t('common.view')} ${c.number}`}
                    editName={`${t('common.edit')} ${c.number}`}
                    onView={() => setViewing(c)}
                    onEdit={mayCreate && c.status === 'ACTIVE' && !draft ? () => startNew(c) : undefined}
                    editWhy={editWhy(c)}
                    onApprove={
                      mayApprove && (c.status === 'ACTIVE' || c.status === 'PENDING_APPROVAL')
                        ? () =>
                            c.status === 'PENDING_APPROVAL'
                              ? window.location.assign(withBase('/payroll/?s=W-01'))
                              : toast(t('wf.nothingPending'))
                        : undefined
                    }
                  />
                </td>
              </tr>
            ))
          }
        </ServerPagination>
      </Tbl>
      <div className="g2 fl-mt">
        <div id="m25-where-used">
          <Tbl
            title={selected ? t('m25.whereUsedOf', [selected.number]) : t('m25.whereUsed')}
            cols={[
              { t: t('m25.col.linkedItem'), s: 'SYS' },
              { t: t('m25.col.type'), s: 'SYS' },
              { t: t('m25.col.screen'), s: 'SYS' },
            ]}
          >
            {selected ? (
              <ServerPagination
                queryKey={mdmKeys.whereUsed(selected.id)}
                fetchPage={mdmPages.whereUsed(selected.id)}
                personal={false}
                empty={<p className="fl-hint">{t('m25.notUsed')}</p>}
              >
                {(uses) =>
                  uses.map((u) => (
                    <tr key={`${u.entity}|${u.key}|${u.linkedAt ?? ''}`}>
                      <td>
                        {u.key}
                        <div className="sub2">
                          {u.entity} · {t(`status.${u.status}`)}
                          {u.linkedAt ? ` · ${dateTime(u.linkedAt)}` : ''}
                        </div>
                      </td>
                      <td>{t(`m25.kind.${u.kind}`)}</td>
                      <td>{u.screen ?? '—'}</td>
                    </tr>
                  ))
                }
              </ServerPagination>
            ) : (
              <tr className="fl-state-row">
                <td colSpan={3}>
                  <p className="fl-hint">{t('m25.notUsed')}</p>
                </td>
              </tr>
            )}
          </Tbl>
        </div>
        <Panel
          title={t('m25.panel.title')}
          items={[
            {
              icon: '📜',
              tone: 'info',
              title: t('m25.panel.general'),
              src: 'ENT',
              text: t('m25.panel.generalSub'),
            },
            {
              icon: '🔗',
              tone: 'warn',
              title: t('m25.panel.required'),
              src: 'RULE',
              text: t('m25.panel.requiredSub'),
            },
            {
              icon: '👤',
              tone: 'info',
              title: t('m25.panel.personal'),
              src: 'ENT',
              text: t('m25.panel.personalSub'),
            },
            {
              icon: '🏛',
              tone: 'gray',
              title: t('m25.panel.hrms'),
              src: 'HRMS',
              text: t('m25.panel.hrmsSub'),
            },
          ]}
        />
      </div>
      {viewing && (
        <ViewDialog
          screen={t('screen.M-25')}
          onClose={() => setViewing(null)}
          rows={[
            { k: t('m25.col.number'), s: 'ENT', v: viewing.number },
            { k: t('m25.col.date'), s: 'ENT', v: date(viewing.date) },
            {
              k: t('m25.col.authority'),
              s: 'ENT',
              v: reference.label('ISSUING_AUTHORITY', viewing.issuingAuthority),
            },
            { k: t('m25.col.subject'), s: 'ENT', v: viewing.subject },
            { k: t('m25.col.category'), s: 'ENT', v: reference.label('CIRCULAR_CATEGORY', viewing.category) },
            { k: t('m25.col.payrollTypes'), s: 'ENT', v: payrollsOf(viewing) || '—' },
            { k: t('m25.col.region'), s: 'ENT', v: officesOf(viewing) },
            { k: t('m25.col.effective'), s: 'ENT', v: effectiveOf(viewing) },
            { k: t('m25.col.supersedes'), s: 'ENT', v: viewing.supersedesNumber ?? '—' },
            { k: t('m25.col.whereUsed'), s: 'SYS', v: viewing.whereUsed },
            { k: t('wf.col.status'), s: 'SYS', v: t(`m25.status.${viewing.status}`) },
            ...(viewing.statusReason
              ? [{ k: t('m25.statusReason'), s: 'SYS' as const, v: viewing.statusReason }]
              : []),
          ]}
          actions={
            viewing.documentId !== null && (
              <Act label={t('m25.document')} href={mdmLinks.circularDocument(viewing.id)} />
            )
          }
        />
      )}
      {draft && (
        <Card title={draft.supersedesId ? t('m25.supersedeTitle') : t('action.newCircular')}>
          <Problem error={error} />
          <div className="bd rf-grid">
            <div className="rf-field">
              <label htmlFor="nc-number">{t('m25.number')}</label>
              <input
                id="nc-number"
                maxLength={60}
                value={draft.number}
                onChange={(e) => setDraft({ ...draft, number: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="nc-date">{t('m25.date')}</label>
              <input
                id="nc-date"
                type="date"
                value={draft.date}
                onChange={(e) => setDraft({ ...draft, date: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="nc-auth">{t('m25.issuingAuthority')}</label>
              <select
                id="nc-auth"
                value={draft.issuingAuthority}
                onChange={(e) => setDraft({ ...draft, issuingAuthority: e.target.value })}
              >
                <option value="">{t('common.choose')}</option>
                <ReferenceOptions list="ISSUING_AUTHORITY" />
              </select>
            </div>
            <div className="rf-field">
              <label htmlFor="nc-cat">{t('m25.category')}</label>
              <select
                id="nc-cat"
                value={draft.category}
                onChange={(e) => setDraft({ ...draft, category: e.target.value })}
              >
                <option value="">{t('common.choose')}</option>
                <ReferenceOptions list="CIRCULAR_CATEGORY" />
              </select>
            </div>
            <div className="rf-field span2">
              <label htmlFor="nc-subject">{t('m25.subject')}</label>
              <TextAreaField
                id="nc-subject"
                maxLength={500}
                value={draft.subject}
                onChange={(e) => setDraft({ ...draft, subject: e.target.value })}
              />
            </div>
            <fieldset className="rf-field span2">
              <legend>{t('m25.payrollTypes')}</legend>
              {PAYROLL_TYPES.map((p) => (
                <label key={p} className="fl-inline">
                  <input
                    type="checkbox"
                    checked={draft.payrollTypes.includes(p)}
                    onChange={(e) => toggleType(p, e.target.checked)}
                  />{' '}
                  {t(`m14.type.${p}`)}
                </label>
              ))}
            </fieldset>
            <div className="rf-field">
              <label htmlFor="nc-all">
                <input
                  id="nc-all"
                  type="checkbox"
                  checked={draft.appliesAllOffices}
                  onChange={(e) => setDraft({ ...draft, appliesAllOffices: e.target.checked, officeIds: [] })}
                />{' '}
                {t('m25.allOffices')}
              </label>
            </div>
            {!draft.appliesAllOffices && (
              <div className="rf-field">
                <label htmlFor="nc-offices">{t('m25.offices')}</label>
                <select
                  id="nc-offices"
                  multiple
                  size={6}
                  value={draft.officeIds.map(String)}
                  onChange={(e) =>
                    setDraft({
                      ...draft,
                      officeIds: Array.from(e.target.selectedOptions).map((o) => Number(o.value)),
                    })
                  }
                >
                  {(offices.data ?? []).map((o) => (
                    <option key={o.id} value={o.id}>
                      {o.code} · {o.name}
                    </option>
                  ))}
                </select>
                <span className="hint">{t('m25.officesHint')}</span>
              </div>
            )}
            <div className="rf-field">
              <label htmlFor="nc-from">{t('mst.from')}</label>
              <input
                id="nc-from"
                type="date"
                value={draft.effectiveFrom}
                onChange={(e) => setDraft({ ...draft, effectiveFrom: e.target.value })}
              />
            </div>
            <div className="rf-field">
              <label htmlFor="nc-to">{t('mst.to')}</label>
              <input
                id="nc-to"
                type="date"
                value={draft.effectiveTo ?? ''}
                min={draft.effectiveFrom || undefined}
                onChange={(e) => setDraft({ ...draft, effectiveTo: e.target.value || null })}
              />
              {draft.effectiveTo !== null &&
                draft.effectiveFrom !== '' &&
                draft.effectiveTo < draft.effectiveFrom && (
                  <span className="fl-error" role="alert">
                    {t('m25.toBeforeFrom')}
                  </span>
                )}
            </div>
            <div className="rf-field span2">
              <label htmlFor="nc-file">{t('m25.document')}</label>
              <input
                id="nc-file"
                type="file"
                accept="application/pdf,.pdf"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              />
              <span className="hint">{t('m25.documentHint')}</span>
            </div>
            <div className="rf-field span2">
              <label htmlFor="nc-reason">{t('common.reason')}</label>
              <TextAreaField
                id="nc-reason"
                value={reason}
                maxLength={500}
                onChange={(e) => setReason(e.target.value)}
              />
            </div>
          </div>
          <p className="fl-hint">{t('m25.checkerNote')}</p>
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
              disabled={!ready || record.isPending}
              onClick={() => void submit()}
            >
              {t('common.submitForApproval')}
            </button>
          </div>
        </Card>
      )}
      {withdrawing && (
        <ReasonDialog
          title={t('m25.withdrawTitle', [withdrawing.number])}
          confirmLabel={t('action.withdraw')}
          onConfirm={(why) => void confirmWithdraw(why)}
          onCancel={() => setWithdrawing(null)}
        />
      )}
    </div>
  );
}
