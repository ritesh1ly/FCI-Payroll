'use client';

import { useRef, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { can, hqOnlyBlock, useMe } from '@/shared/session';
import { date, Problem, Src, useLocalPage, useToast, TextAreaField } from '@/shared/ui';
import { businessToday } from '@/shared/time';
import { Act, Explained, Locked, Panel, Tiles, WfTile } from '@/shared/ui/wireframe';
import type { ListRow, ValueRow } from '../api/referenceListsApi';
import { useProposeReferenceValue, useReferenceListAdmin } from '../orchestration/referenceListQueries';

/** The eleven lists Wireframes v6.1 draws on the page, in drawn order; the rest open from a closed section. */
const DRAWN_LISTS = [
  'LEAVE_FREQUENCY',
  'LEAVE_CARRY_FORWARD',
  'LEAVE_ENCASHMENT_ON',
  'LEAVE_CALCULATED_ON',
  'LEAVE_ZONE_SCOPE',
  'PENALTY_PAYROLL_SCOPE',
  'PENALTY_EXCLUDED_FROM',
  'CLAIM_TYPE',
  'CEILING_BASIS',
  'ALLOWANCE_PERIODICITY',
  'ELIGIBILITY_CATEGORY',
];

interface Draft {
  list: string;
  code: string;
  labelEn: string;
  labelHi: string;
  sortOrder: string;
  effectiveFrom: string;
  effectiveTo: string;
  reason: string;
}

const EMPTY: Draft = {
  list: '',
  code: '',
  labelEn: '',
  labelHi: '',
  sortOrder: '',
  effectiveFrom: '',
  effectiveTo: '',
  reason: '',
};

/** A code from the drawn New Value: capitals, digits and _ (the code never changes later). */
export function codeOf(text: string): string {
  return text
    .toUpperCase()
    .replace(/[^A-Z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 40);
}

const toNumber = (text: string) => (text === '' ? null : Number(text));
const blankToNull = (text: string) => (text.trim() === '' ? null : text.trim());

/**
 * M-16 Reference & Combo List Administration as Wireframes v6.1 draws it (OD-121; target DDL v4.0 reference_list /
 * reference_value, Hindi labels in label_master): the single source of every dropdown (SRS-G-6, SRS-16-15). New
 * Reference Value over the screen, the three tiles, the always-open New Reference Value card and one panel per drawn
 * list with every value; each panel's value table (code, Hindi, order, dates, History and Change) is a closed
 * section, and every other list opens from a closed section at the end. The HQ System Administrator proposes a new
 * value or a change; the Payroll Checker at HQ approves it in W-01, and only then does it reach every screen
 * (SRS-G-1). A system list keeps its codes, so New Value is drawn disabled with the reason there.
 */
export function ReferenceLists() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const hqBlock = hqOnlyBlock(me.data, 'M-16');
  const lists = useReferenceListAdmin();
  const proposal = useProposeReferenceValue();
  const [selected, setSelected] = useState<string | null>(null);
  const [editing, setEditing] = useState<{ list: ListRow; value: ValueRow | null } | null>(null);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [created, setCreated] = useState<Draft>(() => ({ ...EMPTY, effectiveFrom: businessToday() }));
  const [codeTouched, setCodeTouched] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const form = useRef<HTMLDivElement>(null);
  const mayCreate = can(me.data, 'M-16', 'CREATE');
  const mayEdit = can(me.data, 'M-16', 'EDIT');
  const all = lists.data ?? [];
  const drawn = DRAWN_LISTS.flatMap((code) => all.filter((l) => l.code === code));
  const others = all.filter((l) => !DRAWN_LISTS.includes(l.code));
  const chosen: ListRow | undefined = others.find((l) => l.code === selected) ?? others[0];
  const moduleCount = new Set(all.map((l) => l.module).filter(Boolean)).size;
  const totalValues = all.reduce((n, l) => n + l.values.length, 0);
  const pendingNew = (l: ListRow) => l.pendingNew;

  const set = (patch: Partial<Draft>) => setDraft({ ...draft, ...patch });
  const setNew = (patch: Partial<Draft>) => setCreated({ ...created, ...patch });
  const newDisabled = !mayCreate || hqBlock !== null;
  const drawnAdmin = me.data?.roles.includes('SYSTEM_ADMINISTRATOR') === true;
  const whyNew = hqBlock ? t(hqBlock) : t('m16w.why.admin');
  const newCode = codeTouched ? created.code : codeOf(created.labelEn);
  const newList = all.find((l) => l.code === created.list);
  const newProblem = newList?.system
    ? t('m16.systemHint')
    : created.list === '' ||
        created.labelEn.trim() === '' ||
        created.effectiveFrom === '' ||
        created.reason.trim() === '' ||
        newCode === ''
      ? t('m16w.why.incomplete')
      : null;
  // The pack validates on Save: drawn enabled, it says what is missing and sends nothing.
  const saveWhy = newDisabled ? whyNew : newProblem;

  function open(list: ListRow, value: ValueRow | null) {
    setEditing({ list, value });
    setDraft(
      value
        ? {
            ...EMPTY,
            code: value.code,
            labelEn: value.labelEn,
            labelHi: value.labelHi ?? '',
            sortOrder: value.sortOrder === null ? '' : String(value.sortOrder),
            effectiveTo: value.effectiveTo ?? '',
          }
        : { ...EMPTY, effectiveFrom: businessToday() },
    );
    setError(null);
  }

  async function run(send: () => Promise<unknown>, list: string, done: () => void) {
    setError(null);
    try {
      await send();
      toast(t('m16.submitted', [list]));
      done();
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  function submitNew() {
    return run(
      () =>
        proposal.mutateAsync({
          list: created.list,
          code: null,
          created: {
            code: newCode,
            labelEn: created.labelEn.trim(),
            labelHi: blankToNull(created.labelHi),
            sortOrder: toNumber(created.sortOrder),
            effectiveFrom: created.effectiveFrom,
            effectiveTo: created.effectiveTo || null,
            reason: created.reason.trim(),
          },
        }),
      created.list,
      () => {
        setCreated({ ...EMPTY, effectiveFrom: businessToday() });
        setCodeTouched(false);
      },
    );
  }

  function submit() {
    if (!editing) return Promise.resolve();
    const { list, value } = editing;
    const labelHi = blankToNull(draft.labelHi);
    const sortOrder = toNumber(draft.sortOrder);
    return run(
      () =>
        value
          ? proposal.mutateAsync({
              list: list.code,
              code: value.code,
              changed: {
                labelEn: draft.labelEn.trim(),
                labelHi,
                sortOrder,
                effectiveTo: draft.effectiveTo || null,
                reason: draft.reason.trim(),
              },
            })
          : proposal.mutateAsync({
              list: list.code,
              code: null,
              created: {
                code: draft.code.trim().toUpperCase(),
                labelEn: draft.labelEn.trim(),
                labelHi,
                sortOrder,
                effectiveFrom: draft.effectiveFrom,
                effectiveTo: draft.effectiveTo || null,
                reason: draft.reason.trim(),
              },
            }),
      list.code,
      () => setEditing(null),
    );
  }

  const isNew = editing !== null && editing.value === null;
  const valueTable = (list: ListRow) => (
    <ListValues
      list={list}
      mayCreate={mayCreate}
      mayEdit={mayEdit}
      hqBlock={hqBlock}
      onNew={() => open(list, null)}
      onChange={(value) => open(list, value)}
    />
  );

  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {newDisabled ? (
          <Locked label={t('m16w.new')} kind="pri" why={whyNew} />
        ) : (
          <Act label={t('m16w.new')} kind="pri" onClick={() => form.current?.scrollIntoView?.()} />
        )}
      </div>
      <Tiles>
        <WfTile
          icon="🗃"
          label={t('m16w.tile.lists')}
          src="RULE"
          big={all.length}
          sub={t('m16w.tile.listsSub')}
        />
        <WfTile
          icon="🔢"
          label={t('m16w.tile.values')}
          src="RULE"
          big={totalValues}
          sub={t('m16w.tile.valuesSub')}
          tone="pur"
        />
        <WfTile
          icon="🔗"
          label={t('m16w.tile.screens')}
          src="SYS"
          big={moduleCount}
          sub={t('m16w.tile.screensSub')}
        />
      </Tiles>
      <Problem error={lists.error} />

      <div ref={form} className="card fl-form-card">
        <div className="hd">
          <h3>{t('m16w.card')}</h3>
        </div>
        <div className="bd">
          <div className="rf-grid">
            <div className="rf-field">
              <label htmlFor="m16-new-list">
                {t('m16w.listName')}
                <span className="req"> *</span>
              </label>
              <select
                id="m16-new-list"
                required
                disabled={newDisabled}
                value={created.list}
                onChange={(e) => setNew({ list: e.target.value })}
              >
                <option value="">{t('m07w.select')}</option>
                {drawn.map((l) => (
                  <option key={l.code} value={l.code}>
                    {t(`m16w.list.${l.code}`)}
                  </option>
                ))}
              </select>
              <span className="hint">{t('m16w.listHint')}</span>
            </div>
            <div className="rf-field">
              <label htmlFor="m16-new-value">
                {t('m16w.value')}
                <span className="req"> *</span>
              </label>
              <input
                id="m16-new-value"
                type="text"
                required
                maxLength={200}
                placeholder={t('m16w.ph.value')}
                disabled={newDisabled}
                value={created.labelEn}
                onChange={(e) => setNew({ labelEn: e.target.value })}
              />
              <span className="hint">{t('m16w.valueHint')}</span>
            </div>
            <div className="rf-field">
              <label htmlFor="m16-new-from">
                {t('m16w.from')}
                <span className="req"> *</span>
              </label>
              <input
                id="m16-new-from"
                type="date"
                required
                disabled={newDisabled}
                value={created.effectiveFrom}
                onChange={(e) => setNew({ effectiveFrom: e.target.value })}
              />
            </div>
            <div className="rf-field span2">
              <label htmlFor="m16-new-reason">
                {t('m16w.reason')}
                <span className="req"> *</span>
              </label>
              <input
                id="m16-new-reason"
                type="text"
                required
                maxLength={500}
                placeholder={t('m16w.ph.reason')}
                disabled={newDisabled}
                value={created.reason}
                onChange={(e) => setNew({ reason: e.target.value })}
              />
            </div>
          </div>
          <details>
            <summary className="sub2">{t('m07w.more')}</summary>
            <div className="rf-grid">
              <div className="rf-field">
                <label htmlFor="m16-new-code">{t('m16.code')}</label>
                <input
                  id="m16-new-code"
                  maxLength={40}
                  disabled={newDisabled}
                  value={newCode}
                  onChange={(e) => {
                    setCodeTouched(true);
                    setNew({ code: e.target.value.toUpperCase() });
                  }}
                />
                <span className="hint">{t('m16.codeHint')}</span>
              </div>
              <div className="rf-field">
                <label htmlFor="m16-new-hi">{t('m16.labelHi')}</label>
                <input
                  id="m16-new-hi"
                  maxLength={200}
                  disabled={newDisabled}
                  value={created.labelHi}
                  onChange={(e) => setNew({ labelHi: e.target.value })}
                />
                <span className="hint">{t('m16.hindiHint')}</span>
              </div>
              <div className="rf-field">
                <label htmlFor="m16-new-order">{t('m16.order')}</label>
                <input
                  id="m16-new-order"
                  type="number"
                  min={0}
                  max={99999}
                  disabled={newDisabled}
                  value={created.sortOrder}
                  onChange={(e) => setNew({ sortOrder: e.target.value })}
                />
              </div>
              <div className="rf-field">
                <label htmlFor="m16-new-to">{t('m16.to')}</label>
                <input
                  id="m16-new-to"
                  type="date"
                  disabled={newDisabled}
                  value={created.effectiveTo}
                  onChange={(e) => setNew({ effectiveTo: e.target.value })}
                />
                <span className="hint">{t('m16.toHint')}</span>
              </div>
            </div>
          </details>
          {!editing && <Problem error={error} />}
          {/* The approval note is no drawn object: it stays on the action bar (SRS-16-15, WF-1613). */}
          <div className="rf-actbar" title={t('m16.approvalNote')}>
            {newDisabled && !drawnAdmin ? (
              <Locked label={t('m16w.save')} kind="pri" why={whyNew} />
            ) : saveWhy !== null ? (
              <Explained label={t('m16w.save')} kind="pri" why={saveWhy} />
            ) : (
              <button
                type="button"
                className="btn pri"
                disabled={proposal.isPending}
                onClick={() => void submitNew()}
              >
                {t('m16w.save')}
              </button>
            )}
          </div>
        </div>
      </div>

      {drawn.map((list) => (
        <div key={list.code} data-list={list.code}>
          <Panel
            title={t(`m16w.list.${list.code}`)}
            items={[
              ...list.values.map((v) => ({ icon: '▪️', title: v.labelEn })),
              ...pendingNew(list).map((p) => ({
                icon: '▪️',
                title: p.labelEn,
                right: <span className="pill p-warn">{t('m16.pendingNew', [p.approvalId])}</span>,
              })),
            ]}
            after={
              <details className="fl-pad-top-0">
                <summary className="sub2">{t('m16w.manage')}</summary>
                {valueTable(list)}
              </details>
            }
          />
        </div>
      ))}

      {others.length > 0 && chosen && (
        <details className="card fl-mt" data-testid="m16-other-lists">
          <summary>{t('m16w.others', [others.length])}</summary>
          <div className="toolbar">
            <span className="sub2">{t('m16.hint')}</span>
            <div className="spacer" />
            <label htmlFor="m16-list" className="sub2">
              {t('m16.list')}
            </label>
            <select
              id="m16-list"
              value={chosen.code}
              onChange={(e) => {
                setSelected(e.target.value);
                setEditing(null);
              }}
            >
              {others.map((l) => (
                <option key={l.code} value={l.code}>
                  {l.code} · {l.name}
                </option>
              ))}
            </select>
          </div>
          {valueTable(chosen)}
        </details>
      )}

      {editing && (
        <section
          className="card fl-mt"
          aria-label={isNew ? t('m16.newValueOf', [editing.list.code]) : undefined}
        >
          <div className="hd">
            <h3>
              {isNew
                ? t('m16.newValueOf', [editing.list.code])
                : t('m16.changeOf', [editing.list.code, editing.value?.code ?? ''])}
            </h3>
          </div>
          <div className="bd">
            <Problem error={error} />
            <div className="rf-grid">
              <div className="rf-field">
                <label htmlFor="m16-code">{t('m16.code')}</label>
                <input
                  id="m16-code"
                  value={draft.code}
                  maxLength={40}
                  disabled={!isNew}
                  onChange={(e) => set({ code: e.target.value.toUpperCase() })}
                />
                {isNew && <span className="hint">{t('m16.codeHint')}</span>}
              </div>
              <div className="rf-field">
                <label htmlFor="m16-en">{t('m16.labelEn')}</label>
                <input
                  id="m16-en"
                  value={draft.labelEn}
                  maxLength={200}
                  onChange={(e) => set({ labelEn: e.target.value })}
                />
              </div>
              <div className="rf-field">
                <label htmlFor="m16-hi">{t('m16.labelHi')}</label>
                <input
                  id="m16-hi"
                  lang="hi"
                  value={draft.labelHi}
                  maxLength={200}
                  onChange={(e) => set({ labelHi: e.target.value })}
                />
                <span className="hint">{t('m16.hindiHint')}</span>
              </div>
              <div className="rf-field">
                <label htmlFor="m16-order">{t('m16.order')}</label>
                <input
                  id="m16-order"
                  type="number"
                  min={0}
                  max={99999}
                  value={draft.sortOrder}
                  onChange={(e) => set({ sortOrder: e.target.value })}
                />
              </div>
              {isNew && (
                <div className="rf-field">
                  <label htmlFor="m16-from">{t('m16.from')}</label>
                  <input
                    id="m16-from"
                    type="date"
                    value={draft.effectiveFrom}
                    onChange={(e) => set({ effectiveFrom: e.target.value })}
                  />
                </div>
              )}
              <div className="rf-field">
                <label htmlFor="m16-to">{t('m16.to')}</label>
                <input
                  id="m16-to"
                  type="date"
                  value={draft.effectiveTo}
                  onChange={(e) => set({ effectiveTo: e.target.value })}
                />
                <span className="hint">{t('m16.toHint')}</span>
              </div>
              <div className="rf-field span2">
                <label htmlFor="m16-reason">{t('m16.reason')}</label>
                <TextAreaField
                  id="m16-reason"
                  value={draft.reason}
                  maxLength={500}
                  onChange={(e) => set({ reason: e.target.value })}
                />
              </div>
            </div>
            <p className="fl-hint">{t('m16.approvalNote')}</p>
            <div className="rf-actbar">
              <button type="button" className="btn" onClick={() => setEditing(null)}>
                {t('common.cancel')}
              </button>
              <button
                type="button"
                className="btn pri"
                disabled={
                  proposal.isPending ||
                  draft.labelEn.trim() === '' ||
                  draft.reason.trim() === '' ||
                  (isNew && (draft.code.trim() === '' || draft.effectiveFrom === ''))
                }
                onClick={() => void submit()}
              >
                {t('common.submitForApproval')}
              </button>
            </div>
          </div>
        </section>
      )}
    </div>
  );
}

/** One list's values: code, labels, order, days in use, state, History and Change (paged here). */
function ListValues({
  list,
  mayCreate,
  mayEdit,
  hqBlock,
  onNew,
  onChange,
}: {
  list: ListRow;
  mayCreate: boolean;
  mayEdit: boolean;
  hqBlock: string | null;
  onNew: () => void;
  onChange: (value: ValueRow) => void;
}) {
  const { t } = useI18n();
  const values = useLocalPage(list.values, list.code);
  function state(v: ValueRow): { key: string; tone: string } {
    if (v.active) return { key: 'm16.state.active', tone: 'p-ok' };
    return v.effectiveFrom > businessToday()
      ? { key: 'm16.state.later', tone: 'p-info' }
      : { key: 'm16.state.ended', tone: 'p-gray' };
  }
  const newWhy = hqBlock ? t(hqBlock) : list.system ? t('m16.systemHint') : t('m16w.why.admin');
  return (
    <div className="bd">
      <div className="toolbar">
        {list.system ? (
          <span className="pill p-info">{t('m16.systemList')}</span>
        ) : (
          <span className="pill p-gray">{t('m16.openList')}</span>
        )}
        <span className="sp" />
        {mayCreate && !list.system && hqBlock === null ? (
          <button type="button" className="btn sm" onClick={onNew}>
            {t('m16.newValue')}
          </button>
        ) : (
          <Locked label={t('m16.newValue')} kind="sm" why={newWhy} />
        )}
      </div>
      {list.system && <p className="fl-hint">{t('m16.systemHint')}</p>}
      <div className="wrap">
        <table className="t">
          <thead>
            <tr>
              <th>
                {t('m16.code')}
                <Src s="SYS" />
              </th>
              <th>
                {t('m16.labelEn')}
                <Src s="ENT" />
              </th>
              <th>
                {t('m16.labelHi')}
                <Src s="ENT" />
              </th>
              <th>
                {t('m16.order')}
                <Src s="ENT" />
              </th>
              <th>
                {t('m16.inUse')}
                <Src s="ENT" />
              </th>
              <th>
                {t('m16.state')}
                <Src s="SYS" />
              </th>
              <th aria-label={t('common.actions')} />
            </tr>
          </thead>
          <tbody>
            {values.rows.map((v) => {
              const s = state(v);
              const held = hqBlock !== null || v.pending !== null;
              return (
                <tr key={v.code}>
                  <td>
                    <code>{v.code}</code>
                    <div className="sub2">{t('m16.version', [v.versionNo])}</div>
                  </td>
                  <td>{v.labelEn}</td>
                  <td lang="hi">{v.labelHi ?? <span className="sub2">{t('m16.noHindi')}</span>}</td>
                  <td>{v.sortOrder ?? '—'}</td>
                  <td>
                    {date(v.effectiveFrom)} – {v.effectiveTo ? date(v.effectiveTo) : t('m16.open')}
                  </td>
                  <td>
                    <span className={`pill ${s.tone}`}>{t(s.key)}</span>
                    {v.pending && (
                      <div>
                        <span className="pill p-warn">{t('m16.pending', [v.pending.approvalId])}</span>
                      </div>
                    )}
                  </td>
                  <td>
                    <div className="acts">
                      <HistoryButton
                        entity="reference_value"
                        recordKey={`${list.code}.${v.code}`}
                        label={`${list.code} · ${v.code}`}
                      />
                      {/* SRS-G-4 / SRS-G-25 (BMAD TR-004): a value held by a pending Checker action keeps
                          its Change control, disabled with the reason that names the hold. */}
                      {mayEdit ? (
                        <button
                          type="button"
                          className="btn sm"
                          disabled={held}
                          title={
                            hqBlock
                              ? t(hqBlock)
                              : v.pending
                                ? t('m16.heldByPending', [v.pending.approvalId])
                                : undefined
                          }
                          onClick={() => onChange(v)}
                        >
                          {t('m16.change')}
                        </button>
                      ) : (
                        <Locked label={t('m16.change')} kind="sm" why={t('m16w.why.admin')} />
                      )}
                    </div>
                  </td>
                </tr>
              );
            })}
            {list.pendingNew.map((p) => (
              <tr key={`pending-${p.approvalId}`}>
                <td>
                  <code>{p.code}</code>
                </td>
                <td>{p.labelEn}</td>
                <td lang="hi">{p.labelHi ?? <span className="sub2">{t('m16.noHindi')}</span>}</td>
                <td>{p.sortOrder ?? '—'}</td>
                <td>
                  {p.effectiveFrom ? date(p.effectiveFrom) : '—'} –{' '}
                  {p.effectiveTo ? date(p.effectiveTo) : t('m16.open')}
                </td>
                <td>
                  <span className="pill p-warn">{t('m16.pendingNew', [p.approvalId])}</span>
                </td>
                <td />
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {values.pager}
    </div>
  );
}
