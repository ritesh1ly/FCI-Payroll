'use client';

import { type ReactNode, useEffect, useRef, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions, useReference } from '@/shared/reference';
import { can, useMe } from '@/shared/session';
import { date, Empty, ExportButton, Problem, ServerPagination, useToast, TextAreaField } from '@/shared/ui';
import { Act, Explained, Locked, Tbl, Toolbar } from '@/shared/ui/wireframe';
import type { ComponentFilter, ComponentRow, PayrollTypeCode } from '../api/componentsApi';
import {
  componentProblems,
  type ComponentDraft,
  EMPTY_COMPONENT,
  PAYROLL_TYPES,
  removesPayrollType,
} from '../business/componentRules';
import {
  componentExportUrl,
  componentKeys,
  componentPage,
  useComponentActions,
  useComponentUsages,
} from '../orchestration/componentQueries';

/** The sources Source of Value offers as drawn; a seeded HRMS or SYS component shows its own too (M07-09). */
const DRAWN_SOURCES = ['ENT', 'CALC', 'RULE', 'ESS', 'INTF'] as const;
const SEARCH_DELAY_MS = 300;

/** The drawn "All components" select: a class or a component type, through the M-16 list COMPONENT_VIEW. */
function viewFilter(view: string): Pick<ComponentFilter, 'componentClass' | 'type'> {
  if (view === 'STAFF' || view === 'LABOUR') return { componentClass: view };
  if (view === 'EARNING' || view === 'DEDUCTION') return { type: view };
  return {};
}

/**
 * M-07 Component Catalogue - Earnings &amp; Deductions as Wireframes v6.1 draws it (OD-121; SRS l.1880-1935): History,
 * Where used and New Component over the screen, the toolbar, the New Staff Component card, the Staff Components and
 * Labour Components tables. A row is selected by clicking it: History and Where used act on it, and its code opens
 * the record's detail, where it is revised or retired. The Source, Taxable and In CPF base filters of SRS l.1931, and
 * the SRS fields the drawing omits (type, payslip group, natural account, payroll types, LWP, dates, reason), are in
 * closed sections. Rule Administrator proposes, Rule Approver decides in the Approvals Inbox (route M07_CREATE); a pay
 * code is never reused and a retired one is end-dated (SRS-G-42). An action the role lacks is drawn disabled with the
 * reason (SRS-G-25, OD-135). The pack draws Export for every role and the Administrator's form enabled: a role with
 * no right gets them as drawn, the button says why and sends nothing, and the API refuses a forged request (OD-135).
 */
export function ComponentCatalogue() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const reference = useReference();
  const may = (action: string) => can(me.data, 'M-07', action);
  const [view, setView] = useState('');
  const [more, setMore] = useState<ComponentFilter>({});
  const [search, setSearch] = useState('');
  const [q, setQ] = useState('');
  const [selected, setSelected] = useState<ComponentRow | null>(null);
  const [open, setOpen] = useState<ComponentRow | null>(null);
  const [edit, setEdit] = useState<{ draft: ComponentDraft; current: PayrollTypeCode[] | null } | null>(null);
  const form = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const timer = setTimeout(() => setQ(search), SEARCH_DELAY_MS);
    return () => clearTimeout(timer);
  }, [search]);
  const set = (patch: Partial<ComponentFilter>) => setMore({ ...more, ...patch });
  const filter: ComponentFilter = { ...more, ...viewFilter(view), q: q || undefined };
  const mayCreate = may('CREATE');
  // The pack's Administrator is the System Administrator; the right lies with the Rule Administrator (OD-135).
  const drawnAdmin = me.data?.role === 'SYSTEM_ADMINISTRATOR';
  if (!may('VIEW')) return null;

  const needRow = () => toast(t('m07w.pick'));
  const row = (c: ComponentRow, cells: ReactNode) => (
    <tr
      key={c.payCode}
      data-reference={`pay-${c.payCode}`}
      aria-selected={selected?.payCode === c.payCode}
      className={selected?.payCode === c.payCode ? 'fl-row-selected' : undefined}
      onClick={() => setSelected(c)}
    >
      {cells}
    </tr>
  );
  const code = (c: ComponentRow) => (
    <td>
      <button type="button" className="lnk" onClick={() => setOpen(c)}>
        {c.payCode}
      </button>
    </td>
  );
  const source = (c: ComponentRow) => reference.label('FIELD_SOURCE', c.source);
  const status = (c: ComponentRow) => (
    <>
      {t(c.retired ? 'm07.retired' : 'm07.active')}
      {c.pending && <span className="pill p-warn"> {t('m07.pending')}</span>}
    </>
  );

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {selected ? (
          <HistoryButton
            entity="pay_component"
            recordKey={String(selected.payCode)}
            label={`${selected.payCode} · ${selected.name}`}
            text={t('m07w.history')}
            className="btn"
          />
        ) : (
          <Act label={t('m07w.history')} onClick={needRow} />
        )}
        <Act label={t('m07w.whereUsed')} onClick={() => (selected ? setOpen(selected) : needRow())} />
        {mayCreate ? (
          <Act
            label={t('m07w.new')}
            kind="pri"
            onClick={() => {
              setEdit(null);
              form.current?.scrollIntoView?.();
            }}
          />
        ) : drawnAdmin ? (
          <Explained label={t('m07w.new')} kind="pri" why={t('m07w.why.create')} />
        ) : (
          <Locked label={t('m07w.new')} kind="pri" why={t('m07w.why.create')} />
        )}
      </div>

      <Toolbar
        search={search}
        onSearch={setSearch}
        exportControl={
          may('EXPORT') ? (
            <ExportButton href={componentExportUrl(filter)} what={t('screen.M-07')} label={t('wf.export')} />
          ) : (
            <Explained label={t('wf.export')} why={t('m07w.why.export')} />
          )
        }
      >
        <select aria-label={t('m07w.allComponents')} value={view} onChange={(e) => setView(e.target.value)}>
          <option value="">{t('m07w.allComponents')}</option>
          <ReferenceOptions list="COMPONENT_VIEW" />
        </select>
      </Toolbar>
      <details>
        <summary className="sub2">{t('wf.searchDisclosure')}</summary>
        <div className="toolbar fl-filters">
          <label>
            {t('m07.source')}{' '}
            <select
              aria-label={t('m07.source')}
              value={more.source ?? ''}
              onChange={(e) => set({ source: e.target.value || undefined })}
            >
              <option value="">{t('m07.all')}</option>
              <ReferenceOptions list="FIELD_SOURCE" />
            </select>
          </label>
          <label>
            {t('m07.taxable')}{' '}
            <select
              aria-label={t('m07.taxable')}
              value={more.taxable ?? ''}
              onChange={(e) => set({ taxable: e.target.value || undefined })}
            >
              <option value="">{t('m07.all')}</option>
              <ReferenceOptions list="TAXABLE_FLAG" />
            </select>
          </label>
          <label>
            {t('m07.cpfBase')}{' '}
            <select
              aria-label={t('m07.cpfBase')}
              value={more.cpf ?? ''}
              onChange={(e) => set({ cpf: e.target.value || undefined })}
            >
              <option value="">{t('m07.all')}</option>
              <ReferenceOptions list="YES_NO" />
            </select>
          </label>
        </div>
      </details>

      <div ref={form}>
        {mayCreate || drawnAdmin ? (
          <Editor
            key={edit ? `revise-${edit.draft.payCode}` : 'new'}
            initial={edit?.draft ?? EMPTY_COMPONENT}
            current={edit?.current ?? null}
            locked={!mayCreate}
            onDone={() => setEdit(null)}
          />
        ) : (
          <div className="card fl-form-card">
            <div className="hd">
              <h3>{t('m07w.newStaff')}</h3>
            </div>
          </div>
        )}
      </div>

      <Tbl
        title={t('m07w.staff')}
        cols={[
          { t: t('m07w.col.code'), s: 'ENT' },
          { t: t('m07w.col.component'), s: 'ENT' },
          { t: t('m07w.col.basis'), s: 'RULE' },
          { t: t('m07w.col.source'), s: 'SYS' },
          { t: t('m07w.col.elig'), s: 'ENT' },
          { t: t('m07w.col.taxable'), s: 'ENT' },
          { t: t('m07w.col.cpf'), s: 'ENT' },
          { t: t('m07w.col.status'), s: 'SYS' },
        ]}
      >
        {view === 'LABOUR' ? (
          <tr>
            <td colSpan={8} className="sub2">
              {t('m07.none')}
            </td>
          </tr>
        ) : (
          <ServerPagination
            queryKey={componentKeys.list({ ...filter, componentClass: 'STAFF' })}
            fetchPage={componentPage({ ...filter, componentClass: 'STAFF' })}
            personal={false}
            empty={<Empty text={t('m07.none')} />}
          >
            {(rows) => (
              <>
                {rows.map((c) =>
                  row(
                    c,
                    <>
                      {code(c)}
                      <td>{c.name}</td>
                      <td>{c.basisRuleKey ?? '—'}</td>
                      <td>{source(c)}</td>
                      <td>{c.eligibilityRuleKey ?? '—'}</td>
                      <td>{reference.label('TAXABLE_FLAG', c.taxable)}</td>
                      <td>{reference.label('YES_NO', c.inCpfBase ? 'Y' : 'N')}</td>
                      <td>{status(c)}</td>
                    </>,
                  ),
                )}
              </>
            )}
          </ServerPagination>
        )}
      </Tbl>

      <div className="fl-mt">
        <Tbl
          title={t('m07w.labour')}
          cols={[
            { t: t('m07w.col.code'), s: 'ENT' },
            { t: t('m07w.col.component'), s: 'ENT' },
            { t: t('m07w.col.appliesTo'), s: 'RULE' },
            { t: t('m07w.col.labourBasis'), s: 'RULE' },
            { t: t('m07w.col.source'), s: 'SYS' },
          ]}
        >
          {view === 'STAFF' ? (
            <tr>
              <td colSpan={5} className="sub2">
                {t('m07.none')}
              </td>
            </tr>
          ) : (
            <ServerPagination
              queryKey={componentKeys.list({ ...filter, componentClass: 'LABOUR' })}
              fetchPage={componentPage({ ...filter, componentClass: 'LABOUR' })}
              personal={false}
              empty={<Empty text={t('m07.none')} />}
            >
              {(rows) => (
                <>
                  {rows.map((c) =>
                    row(
                      c,
                      <>
                        {code(c)}
                        <td>{c.name}</td>
                        <td>{c.payrollTypes.map((p) => t(`m07.payrollType.${p}`)).join(', ')}</td>
                        <td>{c.basisRuleKey ?? '—'}</td>
                        <td>{source(c)}</td>
                      </>,
                    ),
                  )}
                </>
              )}
            </ServerPagination>
          )}
        </Tbl>
      </div>

      {open && (
        <Detail
          key={open.payCode}
          row={open}
          onClose={() => setOpen(null)}
          onRevise={
            mayCreate && !open.retired && !open.pending
              ? () => {
                  setEdit({
                    current: open.payrollTypes,
                    draft: {
                      payCode: String(open.payCode),
                      name: open.name,
                      type: open.type,
                      payslipGroup: open.payslipGroup ?? '',
                      basisRuleKey: open.basisRuleKey ?? '',
                      eligibilityRuleKey: open.eligibilityRuleKey ?? '',
                      source: open.source,
                      naturalAccount: open.naturalAccount ?? '',
                      taxable: open.taxable,
                      inCpfBase: open.inCpfBase ? 'Y' : 'N',
                      subjectToLwp: open.subjectToLwp,
                      payrollTypes: [...open.payrollTypes],
                      effectiveFrom: '',
                      reason: '',
                    },
                  });
                  form.current?.scrollIntoView?.();
                }
              : undefined
          }
          reviseWhy={
            !mayCreate
              ? t('m07w.why.create')
              : open.pending
                ? t('m07.pending')
                : open.retired
                  ? t('m07.retired')
                  : undefined
          }
          mayRetire={may('EDIT') && !open.retired && !open.pending}
          retireWhy={!may('EDIT') ? t('m07w.why.create') : open.pending ? t('m07.pending') : t('m07.retired')}
        />
      )}
    </div>
  );
}

function Detail({
  row,
  onClose,
  onRevise,
  reviseWhy,
  mayRetire,
  retireWhy,
}: {
  row: ComponentRow;
  onClose: () => void;
  onRevise?: () => void;
  reviseWhy?: string;
  mayRetire: boolean;
  retireWhy: string;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const usages = useComponentUsages(row.payCode);
  const actions = useComponentActions();
  const [lastDay, setLastDay] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>(null);
  async function retire() {
    setError(null);
    try {
      await actions.retire.mutateAsync({ code: row.payCode, lastDay, reason: reason.trim() });
      toast(t('m07.sent'));
    } catch (cause) {
      setError(cause);
    }
  }
  return (
    <section className="card" aria-label={t('m07.detail', [row.payCode])}>
      <div className="hd">
        <h3>{t('m07.detail', [row.payCode])}</h3>
        <button type="button" className="btn" onClick={onClose}>
          {t('common.close')}
        </button>
      </div>
      <p className="sub2">
        {row.name} · {t('m07.account')} {row.naturalAccount ?? '—'} · {t('m07.from')}{' '}
        {date(row.effectiveFrom)}
        {row.effectiveTo && ` · ${t('m07.to')} ${date(row.effectiveTo)}`}
      </p>
      <p className="sub2">
        {t(`m07.type.${row.type}`)} · {row.payrollTypes.map((p) => t(`m07.payrollType.${p}`)).join(', ')} ·{' '}
        {t('m07.subjectToLwp')}: {row.subjectToLwp ? 'Y' : 'N'}
      </p>
      <h4>{t('m07.whereUsed')}</h4>
      <Problem error={usages.error} />
      {usages.data && usages.data.length === 0 && <Empty text={t('m07.unused')} />}
      {usages.data && usages.data.length > 0 && (
        <ul className="fl-list" aria-label={t('m07.whereUsed')}>
          {usages.data.map((u) => (
            <li key={`${u.consumerType}|${u.consumerKey}|${u.note ?? ''}`}>
              {u.consumerType} · {u.consumerKey}
              {u.note ? ` · ${u.note}` : ''}
            </li>
          ))}
        </ul>
      )}
      <Problem error={error} />
      {onRevise ? (
        <button type="button" className="btn" onClick={onRevise}>
          {t('m07.revise')}
        </button>
      ) : (
        <Locked label={t('m07.revise')} why={reviseWhy ?? ''} />
      )}
      {!mayRetire && <Locked label={t('action.retire')} why={retireWhy} />}
      {mayRetire && (
        <div className="toolbar" aria-label={t('action.retire')}>
          <label>
            {t('m07.lastDay')}{' '}
            <input type="date" value={lastDay} onChange={(e) => setLastDay(e.target.value)} />
          </label>
          <label>
            {t('m07.reason')}{' '}
            <TextAreaField maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
          </label>
          <button
            type="button"
            className="btn"
            disabled={lastDay === '' || reason.trim() === '' || actions.retire.isPending}
            title={lastDay === '' || reason.trim() === '' ? t('m07.bad.retire') : undefined}
            onClick={() => void retire()}
          >
            {t('action.retire')}
          </button>
        </div>
      )}
    </section>
  );
}

/**
 * The drawn New Staff Component card, always on the page: the drawn fields, then the SRS fields the drawing omits in a
 * closed section. It also carries a revision of a code opened from its detail. Sends for approval (Save &amp; Submit
 * for Approval). A role that may not propose sees the form as drawn: Save says why and sends nothing.
 */
function Editor({
  initial,
  current,
  locked,
  onDone,
}: {
  initial: ComponentDraft;
  current: PayrollTypeCode[] | null;
  locked: boolean;
  onDone: () => void;
}) {
  const { t } = useI18n();
  const toast = useToast();
  const actions = useComponentActions();
  const [d, setD] = useState<ComponentDraft>(initial);
  const [error, setError] = useState<unknown>(null);
  const revising = current !== null;
  const problems = [
    ...componentProblems(d),
    ...(revising && removesPayrollType(current, d.payrollTypes) ? ['m07.bad.removeType'] : []),
  ];
  const set = (patch: Partial<ComponentDraft>) => setD({ ...d, ...patch });
  async function send() {
    setError(null);
    const body = {
      payCode: Number(d.payCode),
      name: d.name.trim(),
      type: d.type as 'EARNING' | 'DEDUCTION',
      payslipGroup: d.payslipGroup || null,
      basisRuleKey: d.basisRuleKey,
      eligibilityRuleKey: d.eligibilityRuleKey || null,
      source: d.source,
      naturalAccount: d.naturalAccount,
      taxable: d.taxable as 'Y' | 'N' | 'PARTLY',
      inCpfBase: d.inCpfBase === 'Y',
      subjectToLwp: d.subjectToLwp,
      payrollTypes: d.payrollTypes,
      effectiveFrom: d.effectiveFrom,
      reason: d.reason.trim(),
    };
    try {
      await (revising ? actions.revise : actions.define).mutateAsync(body);
      toast(t('m07.sent'));
      setD(EMPTY_COMPONENT);
      onDone();
    } catch (cause) {
      setError(cause);
    }
  }
  const text = (
    key: 'payCode' | 'name' | 'payslipGroup' | 'basisRuleKey' | 'eligibilityRuleKey' | 'naturalAccount',
    label: string,
    max: number,
    extra: { required?: boolean; placeholder?: string; span2?: boolean } = {},
  ) => (
    <div className={`rf-field${extra.span2 ? ' span2' : ''}`}>
      <label htmlFor={`m07-${key}`}>
        {t(label)}
        {extra.required && <span className="req"> *</span>}
      </label>
      <input
        id={`m07-${key}`}
        type="text"
        maxLength={max}
        required={extra.required}
        placeholder={extra.placeholder}
        inputMode={key === 'payCode' ? 'numeric' : undefined}
        disabled={key === 'payCode' && revising}
        value={d[key]}
        onChange={(e) => set({ [key]: e.target.value })}
      />
    </div>
  );
  const drawnSources: readonly string[] = (DRAWN_SOURCES as readonly string[]).includes(d.source)
    ? DRAWN_SOURCES
    : [...DRAWN_SOURCES, d.source];
  return (
    <div className="card fl-form-card" id="componentForm">
      <div className="hd">
        <h3>{revising ? t('m07.reviseTitle', [d.payCode]) : t('m07w.newStaff')}</h3>
      </div>
      <div className="bd">
        <Problem error={error} />
        <div className="rf-grid">
          {text('payCode', 'm07w.code', 3, { required: true, placeholder: t('m07w.ph.code') })}
          {text('name', 'm07w.name', 100, { required: true, placeholder: t('m07w.ph.name'), span2: true })}
          {text('basisRuleKey', 'm07w.basis', 100, {
            required: true,
            placeholder: t('m07w.ph.basis'),
            span2: true,
          })}
          <div className="rf-field">
            <label htmlFor="m07-source">
              {t('m07w.source')}
              <span className="req"> *</span>
            </label>
            <select
              id="m07-source"
              required
              value={d.source}
              onChange={(e) => set({ source: e.target.value })}
            >
              <option value="">{t('m07w.select')}</option>
              <ReferenceOptions list="FIELD_SOURCE" only={drawnSources} />
            </select>
          </div>
          {text('eligibilityRuleKey', 'm07w.elig', 100, { placeholder: t('m07w.ph.elig') })}
          <div className="rf-field">
            <label htmlFor="m07-taxable">
              {t('m07w.taxable')}
              <span className="req"> *</span>
            </label>
            <select
              id="m07-taxable"
              required
              value={d.taxable}
              onChange={(e) => set({ taxable: e.target.value as ComponentDraft['taxable'] })}
            >
              <option value="">{t('m07w.select')}</option>
              <ReferenceOptions list="TAXABLE_FLAG" />
            </select>
          </div>
          <div className="rf-field">
            <label htmlFor="m07-cpf">
              {t('m07w.cpf')}
              <span className="req"> *</span>
            </label>
            <select
              id="m07-cpf"
              required
              value={d.inCpfBase}
              onChange={(e) => set({ inCpfBase: e.target.value as ComponentDraft['inCpfBase'] })}
            >
              <option value="">{t('m07w.select')}</option>
              <ReferenceOptions list="YES_NO" />
            </select>
          </div>
        </div>
        <details open={revising} data-testid="m07-more-fields">
          <summary className="sub2">{t('m07w.more')}</summary>
          <div className="rf-grid">
            <div className="rf-field">
              <label htmlFor="m07-type">{t('m07.type')}</label>
              <select
                id="m07-type"
                value={d.type}
                onChange={(e) => set({ type: e.target.value as ComponentDraft['type'] })}
              >
                <option value="">{t('m07w.select')}</option>
                <ReferenceOptions list="COMPONENT_TYPE" />
              </select>
            </div>
            {text('payslipGroup', 'm07.group', 40)}
            {text('naturalAccount', 'm07.account', 20)}
            <div className="rf-field">
              <label>
                <input
                  type="checkbox"
                  checked={d.subjectToLwp}
                  onChange={(e) => set({ subjectToLwp: e.target.checked })}
                />{' '}
                {t('m07.subjectToLwp')}
              </label>
            </div>
            <fieldset className="rf-field span2">
              <legend>{t('m07.payrollTypes')}</legend>
              {PAYROLL_TYPES.map((p) => (
                <label key={p}>
                  <input
                    type="checkbox"
                    checked={d.payrollTypes.includes(p)}
                    onChange={(e) =>
                      set({
                        payrollTypes: e.target.checked
                          ? [...d.payrollTypes, p]
                          : d.payrollTypes.filter((x) => x !== p),
                      })
                    }
                  />{' '}
                  {t(`m07.payrollType.${p}`)}
                </label>
              ))}
            </fieldset>
            <div className="rf-field">
              <label htmlFor="m07-from">{t('m07.from')}</label>
              <input
                id="m07-from"
                type="date"
                value={d.effectiveFrom}
                onChange={(e) => set({ effectiveFrom: e.target.value })}
              />
            </div>
            <div className="rf-field span2">
              <label htmlFor="m07-reason">{t('m07.reason')}</label>
              <TextAreaField
                id="m07-reason"
                maxLength={500}
                value={d.reason}
                onChange={(e) => set({ reason: e.target.value })}
              />
            </div>
            <p className="sub2">{t('m07.lwpHint')}</p>
          </div>
        </details>
        {!locked && problems.length > 0 && (
          <ul className="fl-list" aria-label={t('m07.problems')}>
            {problems.map((p) => (
              <li key={p} className="pill p-warn">
                {t(p)}
              </li>
            ))}
          </ul>
        )}
        <div className="rf-actbar">
          {revising && (
            <button type="button" className="btn" onClick={onDone}>
              {t('common.cancel')}
            </button>
          )}
          {locked ? (
            <Explained label={t('m07w.save')} kind="pri" why={t('m07w.why.create')} />
          ) : (
            <button
              type="button"
              className="btn pri"
              disabled={problems.length > 0 || actions.define.isPending || actions.revise.isPending}
              title={problems.length > 0 ? t(problems[0] ?? '') : undefined}
              data-why={problems.length > 0 ? t(problems[0] ?? '') : undefined}
              onClick={() => void send()}
            >
              {t('m07w.save')}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
