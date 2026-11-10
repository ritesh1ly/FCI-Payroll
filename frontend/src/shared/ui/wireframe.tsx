'use client';

import type { ReactNode } from 'react';
import { useI18n } from '@/shared/i18n';
import { useDialogFocus } from './focus';
import { Src, type Source } from './source';
import { TableColumns } from './tableContext';
import { useToast } from './Toast';

/**
 * The wireframe kit (OD-121: Wireframes v6.1 is a frozen requirement, "100 % no deviation"): one React
 * component per drawing helper of the pack, with the pack's markup and classes (workspace.css carries
 * the pack's styles), so a screen is composed exactly as drawn and the parity suite
 * (frontend/e2e/09-wireframe-parity) reads the same objects on both sides:
 *   periodBand()  -> <PeriodBand>      tiles()  -> <Tiles> / <WfTile>     tbl()    -> <Tbl>
 *   panel()       -> <Panel>           kvcard() -> <KvCard>               toolbar() -> <Toolbar>
 *   B() / BL()    -> <Act> / <Locked>  rowActs() -> <RowActs>
 * Labels come from the message bundle (en + hi) and the data from the API; only the structure is
 * fixed. A disabled action always carries its reason (R-RBAC, SRS-G-54): title and data-why.
 */

/** A toolbar button as drawn ("📋 Cycle Checklist"); kind 'pri' for the primary one. */
export function Act({
  label,
  kind,
  onClick,
  href,
}: {
  label: string;
  kind?: 'pri' | 'sm';
  onClick?: () => void;
  href?: string;
}) {
  const className = `btn${kind ? ` ${kind}` : ''}`;
  return href ? (
    <a className={className} href={href}>
      {label}
    </a>
  ) : (
    <button type="button" className={className} onClick={onClick}>
      {label}
    </button>
  );
}

/** A button the role may not use: drawn, disabled, with the reason (never hidden). */
export function Locked({ label, why, kind }: { label: string; why: string; kind?: 'pri' | 'sm' }) {
  return (
    <button type="button" className={`btn lock${kind ? ` ${kind}` : ''}`} disabled title={why} data-why={why}>
      {label}
    </button>
  );
}

/**
 * An action the pack draws enabled for every role (an unguarded B(), e.g. Export) that this role may not use (OD-125:
 * the pack states no right for it, the SRS role tables do): drawn as drawn, and pressing it says why - nothing is sent.
 */
export function Explained({ label, why, kind }: { label: string; why: string; kind?: 'pri' | 'sm' }) {
  const toast = useToast();
  return (
    <button
      type="button"
      className={`btn${kind ? ` ${kind}` : ''}`}
      data-guard={why}
      onClick={() => toast(why)}
    >
      {label}
    </button>
  );
}

export interface StepState {
  label: string;
  state: 'done' | 'now' | '';
}

/**
 * The period band of D-01 and C-01: the period and its state pill, the fiscal-year line, the three
 * gated cycle actions (each disabled with its reason until its gate opens) and the step track.
 */
export function PeriodBand({
  period,
  statePill,
  line,
  actions,
  steps,
}: {
  period: string;
  statePill?: ReactNode;
  line: string;
  actions: ReactNode;
  steps: StepState[];
}) {
  return (
    <div className="pband">
      <div className="r1">
        <div>
          <div className="per">
            {period} {statePill}
          </div>
          <div className="fy">{line}</div>
        </div>
        <span className="sp" />
        {actions}
      </div>
      <div className="step">
        {steps.map((s, i) => (
          <span key={s.label} className="st-wrap">
            <div className={`st ${s.state}`}>
              <span className="dot">{s.state === 'done' ? '✓' : i + 1}</span>
              <span className="lb">{s.label}</span>
            </div>
            {i < steps.length - 1 && <span className="ln" />}
          </span>
        ))}
      </div>
    </div>
  );
}

/** A row of KPI tiles. */
export function Tiles({ children }: { children: ReactNode }) {
  return <div className="tiles">{children}</div>;
}

/** One tile as drawn: glyph, label with its source badge, the big figure and the sub-line. */
export function WfTile({
  icon,
  label,
  src,
  big,
  sub,
  tone,
  bad,
}: {
  icon: string;
  label: string;
  src?: Source;
  big: ReactNode;
  sub?: ReactNode;
  tone?: 'ok' | 'bad' | 'info' | 'warn' | 'pur';
  bad?: boolean;
}) {
  return (
    <div className="tile">
      <div className="lab">
        <span className={`tico fl-tico-${tone ?? 'info'}`} aria-hidden="true">
          {icon}
        </span>
        {label}
        {src && <Src s={src} />}
      </div>
      <div className={`big${bad ? ' fl-bad' : ''}`}>{big}</div>
      <div className="sub">{sub ?? ''}</div>
    </div>
  );
}

export interface Col {
  t: string;
  s?: Source;
  num?: boolean;
}

/** A card with a titled table: header actions (small buttons), columns with their badges, rows. */
export function Tbl({
  title,
  label,
  acts,
  cols,
  children,
  foot,
  after,
}: {
  title: string;
  /** The table's accessible name, where it differs from the drawn title. */
  label?: string;
  acts?: ReactNode;
  cols: Col[];
  children: ReactNode;
  foot?: ReactNode;
  after?: ReactNode;
}) {
  return (
    <div className="card">
      <div className="hd">
        <h3>{title}</h3>
        <span className="sp" />
        {acts}
      </div>
      <div className="wrap fl-table-scroll">
        <table className="t" aria-label={label ?? title}>
          <thead>
            <tr>
              {cols.map((c, i) => (
                <th key={`${c.t}-${i}`} className={c.num ? 'num' : undefined}>
                  {c.t}
                  {c.s && <Src s={c.s} />}
                </th>
              ))}
            </tr>
          </thead>
          {/* A paginated list inside the body draws its states and pager as rows spanning the columns. */}
          <tbody>
            <TableColumns.Provider value={cols.length}>{children}</TableColumns.Provider>
          </tbody>
          {foot && <tfoot>{foot}</tfoot>}
        </table>
      </div>
      {after}
    </div>
  );
}

export interface PanelItem {
  icon?: string;
  title: string;
  /** A pill drawn inside the bold title (the pack's "Hard gate"). */
  badge?: ReactNode;
  src?: Source;
  text?: ReactNode;
  right?: ReactNode;
  tone?: 'bad' | 'warn' | 'ok' | 'info' | 'gray' | 'pur';
}

/** A card with a list of items (glyph, bold title with badge, muted line, figure at the right). */
export function Panel({
  title,
  act,
  items,
  after,
}: {
  title: string;
  act?: ReactNode;
  items: PanelItem[];
  after?: ReactNode;
}) {
  return (
    <div className="card">
      <div className="hd">
        <h3>{title}</h3>
        <span className="sp" />
        {act}
      </div>
      <div className="bd fl-panel-bd">
        {items.map((i, n) => (
          <div key={`${i.title}-${n}`} className="lst">
            {i.icon && (
              <span className={`ic2 fl-ic2-${i.tone ?? 'gray'}`} aria-hidden="true">
                {i.icon}
              </span>
            )}
            <div>
              <b>
                {i.title}
                {i.badge && <> {i.badge}</>}
                {i.src && <Src s={i.src} />}
              </b>
              <div className="m">{i.text ?? ''}</div>
            </div>
            {i.right !== undefined && <div className="rt">{i.right}</div>}
          </div>
        ))}
      </div>
      {after}
    </div>
  );
}

export interface KvRow {
  k: string;
  v: ReactNode;
  s?: Source;
  /** A section heading inside the card (the pack's bold rows without a value). */
  section?: boolean;
}

/** A card of key / value rows, each key with its source badge. */
export function KvCard({
  title,
  act,
  rows,
  after,
}: {
  title: string;
  act?: ReactNode;
  rows: KvRow[];
  after?: ReactNode;
}) {
  return (
    <div className="card">
      <div className="hd">
        <h3>{title}</h3>
        <span className="sp" />
        {act}
      </div>
      <div className="bd">
        <div className="kv">
          {rows.map((r) => (
            <KvPair key={r.k} row={r} />
          ))}
        </div>
        {after}
      </div>
    </div>
  );
}

function KvPair({ row }: { row: KvRow }) {
  if (row.section) {
    return (
      <>
        <div className="k">
          <b>{row.k}</b>
        </div>
        <div />
      </>
    );
  }
  return (
    <>
      <div className="k">
        {row.k}
        {row.s && <Src s={row.s} />}
      </div>
      <div>{row.v}</div>
    </>
  );
}

/** The list toolbar as drawn: the filters, the list search and the Export button. */
export function Toolbar({
  children,
  search,
  onSearch,
  onExport,
  exportControl,
}: {
  children?: ReactNode;
  search?: string;
  onSearch?: (value: string) => void;
  onExport?: () => void;
  /** The screen's own export control (an ExportButton with the drawn label) in place of the plain button. */
  exportControl?: ReactNode;
}) {
  const { t } = useI18n();
  return (
    <div className="toolbar">
      {children}
      <div className="lsearch">
        🔎{' '}
        <input
          type="search"
          placeholder={t('wf.searchInList')}
          aria-label={t('wf.searchInList')}
          value={search ?? ''}
          onChange={(e) => onSearch?.(e.target.value)}
        />
      </div>
      {exportControl ?? (
        <button type="button" className="btn" onClick={onExport}>
          {t('wf.export')}
        </button>
      )}
    </div>
  );
}

/**
 * The pack's read-only record dialog (openForm(key, 'view')): "👁 View — <screen>", the note, every
 * field with its source badge, the actions of the record and Close.
 */
export function ViewDialog({
  screen,
  rows,
  actions,
  onClose,
}: {
  /** The screen's name, as the pack titles the dialog. */
  screen: string;
  rows: KvRow[];
  actions?: ReactNode;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const dialog = useDialogFocus<HTMLDivElement>();
  const title = t('wf.viewHead', [screen]);
  return (
    <div
      className="fl-modal-backdrop"
      role="presentation"
      onKeyDown={(e) => {
        if (e.key === 'Escape') onClose();
      }}
    >
      <div ref={dialog} className="fl-modal fl-modal-wide" role="dialog" aria-modal="true" aria-label={title}>
        <h2>{title}</h2>
        <p className="sub2">{t('wf.viewNote')}</p>
        <div className="kv">
          {rows.map((r) => (
            <KvPair key={r.k} row={r} />
          ))}
        </div>
        <div className="rf-actbar">
          {actions}
          <button type="button" className="btn" onClick={onClose}>
            {t('common.close')}
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * The row actions as drawn: View always; Edit drawn and disabled with the reason when the role may
 * not edit; Approve only for the approver; Cancel (a new version, never a delete) where the pack
 * draws it, disabled with the reason for the other roles.
 */
export function RowActs({
  onView,
  onEdit,
  editWhy,
  onApprove,
  approveWhy,
  cancel,
  onCancel,
  cancelWhy,
  viewName,
  editName,
}: {
  onView: () => void;
  onEdit?: () => void;
  editWhy?: string;
  onApprove?: () => void;
  /** The drawn ✔ with no decision on this row: disabled, with the reason. */
  approveWhy?: string;
  cancel?: boolean;
  onCancel?: () => void;
  cancelWhy?: string;
  /** Accessible names that say which record ("View MISSING_BANK"); the drawn text stays "👁 View". */
  viewName?: string;
  editName?: string;
}) {
  const { t } = useI18n();
  return (
    <div className="acts">
      <button type="button" className="ia" onClick={onView} title={t('wf.viewTitle')} aria-label={viewName}>
        {t('wf.view')}
      </button>
      {onEdit ? (
        <button type="button" className="ia" onClick={onEdit} title={t('wf.editTitle')} aria-label={editName}>
          {t('wf.edit')}
        </button>
      ) : (
        <button
          type="button"
          className="ia off"
          disabled
          title={t('wf.editOff', [editWhy ?? t('wf.viewOnly')])}
          data-why={editWhy ?? t('wf.viewOnly')}
          aria-label={editName}
        >
          {t('wf.edit')}
        </button>
      )}
      {onApprove && (
        <button type="button" className="ia" onClick={onApprove} title={t('wf.approveTitle')}>
          ✔
        </button>
      )}
      {approveWhy && !onApprove && (
        <button type="button" className="ia off" disabled title={approveWhy} data-why={approveWhy}>
          ✔
        </button>
      )}
      {cancel &&
        (onCancel ? (
          <button type="button" className="ia d" onClick={onCancel} title={t('wf.cancelTitle')}>
            {t('wf.cancel')}
          </button>
        ) : (
          <button
            type="button"
            className="ia off d"
            disabled
            title={cancelWhy ?? t('wf.cancelOff')}
            data-why={cancelWhy ?? t('wf.cancelOff')}
          >
            {t('wf.cancel')}
          </button>
        ))}
    </div>
  );
}

/** The drawn "Order capture" panel, shared with I-06 (the pack draws the same panel on both). */
export function orderCapturePanel(t: (k: string) => string): PanelItem[] {
  return [
    { icon: '🔖', tone: 'bad', title: t('pen.cap.ref'), src: 'ENT', text: t('pen.cap.ref.m') },
    { icon: '💰', tone: 'bad', title: t('pen.cap.amt'), src: 'ENT', text: t('pen.cap.amt.m') },
    { icon: '📎', tone: 'bad', title: t('pen.cap.doc'), src: 'ENT', text: t('pen.cap.doc.m') },
    { icon: '⛔', tone: 'warn', title: t('pen.cap.bar'), src: 'ENT', text: t('pen.cap.bar.m') },
  ];
}
