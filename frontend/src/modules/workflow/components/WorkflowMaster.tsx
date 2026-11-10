'use client';

import { useEffect, useRef, useState } from 'react';
import { HistoryButton } from '@/modules/audit';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { can, hqOnlyBlock, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import {
  Card,
  date,
  dateTime,
  Empty,
  entityLabel,
  Problem,
  ServerPagination,
  Src,
  useToast,
  TextAreaField,
  withBase,
} from '@/shared/ui';
import { Act, Locked, RowActs, Tbl } from '@/shared/ui/wireframe';
import type { ProposedRoute, RouteAction, RouteLevel, RouteVersion } from '../api/workflowMasterApi';
import { OFFICE_LEVELS, OTP_ACTIONS } from '../business/routeRules';
import { X01_PANELS, X01_TABLES } from './x01Content';
import { editBlock } from '../business/x01Rules';
import {
  useProposeRoute,
  routeActionsKey,
  unstaffedKey,
  useUnstaffedCount,
  workflowPages,
} from '../orchestration/workflowQueries';
import { ActiveUsers, ReferencePanel, ReferenceTable, ScreenMatrix, useColumns } from './X01Sections';

const LEVEL_NAMES = ['MAKER', 'CHECKER', 'APPROVER'];

/** The title key of the rules panel item that flags items with no approver in scope (BR-APPR-007). */
const NO_APPROVER_ITEM = X01_PANELS.rules.items[5]?.title ?? '';

/** An empty level of the form. */
const blankLevel = (levelNo: number, officeLevel = 'PAYING_UNIT'): RouteLevel => ({
  levelNo,
  roleCode: '',
  officeLevel,
  otpAtLevel: false,
});

/** The form of a new action, or of a new version of {@code from} (its version in force). */
function draftOf(action: RouteAction | null): ProposedRoute {
  const base = action?.current ?? action?.pending ?? null;
  return {
    actionCode: action?.actionCode ?? '',
    actionName: base?.actionName ?? '',
    moduleCode: base?.moduleCode ?? '',
    screenCode: base?.screenCode ?? null,
    officeLevel: action?.officeLevel ?? 'PAYING_UNIT',
    levelCount: base?.levelCount ?? 2,
    otpAction: base?.otpAction ?? null,
    attachmentRequired: base?.attachmentRequired ?? false,
    bulkApprovalAllowed: base?.bulkApprovalAllowed ?? false,
    targetTatHours: base?.targetTatHours ?? null,
    effectiveFrom: businessToday(),
    levels: base?.levels.map((l) => ({ ...l })) ?? [blankLevel(1), blankLevel(2)],
    reason: '',
  };
}

/**
 * X-01 Approval Workflow Master & RBAC as Wireframes v6.1 draws it (OD-121; built screen A-08, Module 02 F-1; SRS-22-12,
 * 22-15, 22-16, 22-17, 22-19, SRS-G-28, SRS-G-50): the Screen-Level Access Matrix (the grants in force), the action-level
 * matrix and enforcement principles, the office hierarchy, the designations, the delegation, the Maker-Checker-Approver
 * matrix, the approval actions of the Approval Workflow Master with their rules, the authentication and session panel and
 * the Active User Report. Users, New Role and Edit matrix lead to A-01 / A-02, where those changes are made under
 * maker-checker with an OTP (ROLE_ACCESS_GROUP_CHANGE); the rights are those of the target screens, checked again by the
 * API. The HQ Administrator proposes an approval action or a new version; a Payroll Checker who is not the proposer
 * approves it in W-01; only then does it route new submissions, from its date. The items no one in scope can decide are
 * flagged to the HQ Administrator, never auto-approved (SRS-G-28), from the rules panel.
 */
export function WorkflowMaster() {
  const { t } = useI18n();
  const me = useMe();
  const blocked = hqOnlyBlock(me.data, 'A-08');
  const roleBlock = hqOnlyBlock(me.data, 'A-02');
  const mayCreate = can(me.data, 'A-08', 'CREATE');
  const mayEdit = can(me.data, 'A-08', 'EDIT');
  const [editing, setEditing] = useState<{ action: RouteAction | null } | null>(null);
  const [unstaffedOpen, setUnstaffedOpen] = useState(false);
  const count = useUnstaffedCount(true).data ?? 0;
  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        <span className="sp" />
        {can(me.data, 'A-01', 'VIEW') ? (
          <Act label={t('x01.users')} href={withBase('/payroll/?s=A-01')} />
        ) : (
          <Locked label={t('x01.users')} why={t('x01.why.users')} />
        )}
        {can(me.data, 'A-02', 'CREATE') && roleBlock === null ? (
          <Act label={t('x01.newRole')} kind="pri" href={withBase('/payroll/?s=A-02&new=role')} />
        ) : (
          <Locked label={t('x01.newRole')} why={t(roleBlock ?? 'x01.why.admin')} />
        )}
      </div>
      <ScreenMatrix
        acts={
          can(me.data, 'A-02', 'EDIT') && roleBlock === null ? (
            <Act label={t('x01.editMatrix')} kind="sm" href={withBase('/payroll/?s=A-02')} />
          ) : (
            <Locked label={t('x01.editMatrix')} kind="sm" why={t(roleBlock ?? 'x01.why.editMatrix')} />
          )
        }
      />
      <div className="g2 fl-mt">
        <ReferenceTable
          table={X01_TABLES.actionMatrix}
          acts={
            mayEdit && blocked === null ? (
              <Act
                label={t('x01.edit')}
                kind="sm"
                onClick={() => document.getElementById('x01-routes')?.scrollIntoView()}
              />
            ) : (
              <Locked label={t('x01.edit')} kind="sm" why={t(blocked ?? 'x01.why.hqAdmin')} />
            )
          }
        />
        <ReferencePanel panel={X01_PANELS.enforcement} />
      </div>
      <div className="fl-mt">
        <ReferenceTable table={X01_TABLES.orgScope} />
      </div>
      <div className="g2 fl-mt">
        <ReferenceTable table={X01_TABLES.designations} />
        <ReferenceTable table={X01_TABLES.delegation} />
      </div>
      <div className="fl-mt">
        <ReferenceTable table={X01_TABLES.transactionMatrix} />
      </div>
      {editing && (
        <RouteForm
          key={editing.action ? `${editing.action.actionCode}|${editing.action.officeLevel}` : 'new'}
          action={editing.action}
          onClose={() => setEditing(null)}
        />
      )}
      <div className="g2 fl-mt" id="x01-routes">
        <Routes
          mayCreate={mayCreate}
          mayEdit={mayEdit}
          mayApprove={can(me.data, 'A-08', 'APPROVE')}
          blocked={blocked}
          onPropose={(action) => setEditing({ action })}
        />
        <ReferencePanel
          panel={X01_PANELS.rules}
          rightOf={{
            [NO_APPROVER_ITEM]: (
              <a
                href="#x01-unstaffed"
                aria-expanded={unstaffedOpen}
                onClick={(e) => {
                  e.preventDefault();
                  setUnstaffedOpen(!unstaffedOpen);
                }}
              >
                {t('a08.tab.unstaffed', [count])}
              </a>
            ),
          }}
        />
      </div>
      {unstaffedOpen && <Unstaffed />}
      <div className="g2 fl-mt">
        <ReferencePanel panel={X01_PANELS.authentication} />
        <ActiveUsers />
      </div>
    </div>
  );
}

/** {@code blocked}: why the session cannot propose here (the A-08 matrix is kept at Headquarters), or null. */
function Routes({
  mayCreate,
  mayEdit,
  mayApprove,
  blocked,
  onPropose,
}: {
  mayCreate: boolean;
  mayEdit: boolean;
  mayApprove: boolean;
  blocked: string | null;
  onPropose: (action: RouteAction | null) => void;
}) {
  const { t } = useI18n();
  const cols = useColumns(X01_TABLES.routes);
  const [allOpen, setAllOpen] = useState(false);
  const [open, setOpen] = useState<string | null>(null);
  const key = (a: RouteAction) => `${a.actionCode}@${a.officeLevel}`;
  return (
    <Tbl
      title={t(X01_TABLES.routes.title)}
      cols={cols}
      acts={
        <>
          {mayCreate && blocked === null ? (
            <Act label={t('x01.addAction')} kind="sm" onClick={() => onPropose(null)} />
          ) : (
            <Locked label={t('x01.addAction')} kind="sm" why={t(blocked ?? 'x01.why.hqAdmin')} />
          )}
          <Act label={t('history.button')} kind="sm" onClick={() => setAllOpen(!allOpen)} />
        </>
      }
    >
      <ServerPagination
        queryKey={routeActionsKey}
        fetchPage={workflowPages.routeActions}
        empty={<Empty text={t('a08.none')} />}
        personal={false}
      >
        {(rows) =>
          rows.map((a) => (
            <RouteRow
              key={key(a)}
              action={a}
              open={allOpen || open === key(a)}
              onView={() => setOpen(open === key(a) ? null : key(a))}
              editWhy={editBlock(a, mayEdit, blocked)}
              onEdit={() => onPropose(a)}
              mayApprove={mayApprove}
            />
          ))
        }
      </ServerPagination>
    </Tbl>
  );
}

function levelsText(t: (k: string, a?: readonly unknown[]) => string, v: RouteVersion): string {
  return v.levels
    .map(
      (l) =>
        `${t(`a08.level.${l.name ?? LEVEL_NAMES[l.levelNo - 1]}`)} ${l.roleCode} (${l.officeLevel})${l.otpAtLevel ? ' · OTP' : ''}`,
    )
    .join(' → ');
}

function RouteRow({
  action,
  open,
  onView,
  editWhy,
  onEdit,
  mayApprove,
}: {
  action: RouteAction;
  open: boolean;
  onView: () => void;
  editWhy: string | null;
  onEdit: () => void;
  mayApprove: boolean;
}) {
  const { t } = useI18n();
  const v = action.current;
  const shown = v ?? action.pending ?? action.history[0];
  const versions = [v, action.pending, ...action.history].filter((h): h is RouteVersion => h !== null);
  return (
    <>
      <tr>
        <td>
          {shown?.actionName}
          {shown?.screenCode ? ` (${shown.screenCode})` : ''}
          <div className="sub2">
            <b>{action.actionCode}</b>
          </div>
        </td>
        <td>
          {action.officeLevel} · {t(`a08.officeLevelOf.${action.officeLevel}`)}
        </td>
        <td>{shown?.levelCount}</td>
        <td>{v ? v.levels.map((l) => t(`role.${l.roleCode}`)).join(' → ') : '—'}</td>
        <td>{v?.otpAction ? `${v.otpAction} - ${t(`a08.otp.${v.otpAction}`)}` : '—'}</td>
        <td>{v ? t(v.attachmentRequired ? 'common.yes' : 'common.no') : '—'}</td>
        <td>{v && v.targetTatHours !== null ? t('w01.target', [v.targetTatHours]) : t('w01.noTarget')}</td>
        <td>
          {v ? (
            <>
              v{v.versionNo} · {date(v.effectiveFrom)}
              {v.effectiveTo && <div className="sub2">– {date(v.effectiveTo)}</div>}
            </>
          ) : (
            <span className="pill p-bad">{t('a08.notInForce')}</span>
          )}
          {action.pending && (
            <div>
              <span className="pill p-warn">
                {t('a08.pendingVersion', [action.pending.versionNo, date(action.pending.effectiveFrom)])}
              </span>
            </div>
          )}
        </td>
        <td>
          {v && <span className="pill p-ok">{t('x01.active')}</span>}
          <RowActs
            viewName={`${t('common.view')} ${action.actionCode}`}
            editName={`${t('common.edit')} ${action.actionCode}`}
            onView={onView}
            onEdit={editWhy === null ? onEdit : undefined}
            editWhy={editWhy === null ? undefined : t(editWhy)}
            onApprove={mayApprove ? () => window.location.assign(withBase('/payroll/?s=W-01')) : undefined}
          />
        </td>
      </tr>
      {open &&
        versions.map((h) => (
          <tr key={h.id} className="sub">
            <td />
            <td>
              v{h.versionNo} · {t(`a08.status.${h.status}`)}
              <div className="sub2">
                {date(h.effectiveFrom)} – {h.effectiveTo ? date(h.effectiveTo) : t('a08.open')}
              </div>
            </td>
            <td>{h.levelCount}</td>
            <td>{levelsText(t, h)}</td>
            <td>{h.otpAction ? `${h.otpAction} - ${t(`a08.otp.${h.otpAction}`)}` : '—'}</td>
            <td>{t(h.attachmentRequired ? 'common.yes' : 'common.no')}</td>
            <td>{h.targetTatHours === null ? t('w01.noTarget') : t('w01.target', [h.targetTatHours])}</td>
            <td colSpan={2}>{h.approvalId ? t('a08.approvalNo', [h.approvalId]) : '—'}</td>
          </tr>
        ))}
      {open && (
        <tr className="sub">
          <td colSpan={9}>
            <HistoryButton
              entity="approval_workflow_action"
              recordKey={`${action.actionCode}@${action.officeLevel}`}
            />
          </td>
        </tr>
      )}
    </>
  );
}

function RouteForm({ action, onClose }: { action: RouteAction | null; onClose: () => void }) {
  const { t } = useI18n();
  const toast = useToast();
  const propose = useProposeRoute();
  const [draft, setDraft] = useState<ProposedRoute>(() => draftOf(action));
  const [error, setError] = useState<unknown>(null);
  const isNew = action === null;
  const top = useRef<HTMLDivElement>(null);
  useEffect(() => {
    // Braces: the effect must return nothing. Newer browsers return a promise from scrollIntoView, and
    // React calls whatever an effect returns as its clean-up (X-01 crashed on closing the form).
    top.current?.scrollIntoView();
  }, []);
  const set = (patch: Partial<ProposedRoute>) => setDraft((d) => ({ ...d, ...patch }));
  const setLevel = (i: number, patch: Partial<RouteLevel>) =>
    setDraft((d) => ({ ...d, levels: d.levels.map((l, j) => (j === i ? { ...l, ...patch } : l)) }));
  const setCount = (n: number) =>
    setDraft((d) => ({
      ...d,
      levelCount: n,
      levels: n > d.levels.length ? [...d.levels, blankLevel(3, d.officeLevel)] : d.levels.slice(0, n),
    }));
  // The server's rule violations, each under its field.
  const violations = error instanceof ApiError ? error.violations : [];
  const ruleOf = (field: string) =>
    violations.filter((v) => v.field === field).map((v) => t(`a08.rule.${v.code}`));

  async function submit() {
    setError(null);
    try {
      const result = await propose.mutateAsync({
        ...draft,
        actionCode: draft.actionCode.trim().toUpperCase(),
        moduleCode: draft.moduleCode.trim().toUpperCase(),
        screenCode: draft.screenCode?.trim() ? draft.screenCode.trim().toUpperCase() : null,
        levels: draft.levels.map((l) => ({ ...l, roleCode: l.roleCode.trim().toUpperCase() })),
      });
      toast(t('a08.proposed', [result.approvalId ?? '']));
      onClose();
    } catch (cause) {
      setError(cause);
    }
  }

  const field = (id: string, label: string, input: React.ReactNode, name?: string) => (
    <div className="rf-field">
      <label htmlFor={id}>{label}</label>
      {input}
      {name &&
        ruleOf(name).map((m) => (
          <span key={m} className="fl-error" role="alert">
            {m}
          </span>
        ))}
    </div>
  );

  return (
    <div ref={top} className="fl-mt">
      <Card
        title={isNew ? t('a08.newAction') : t('a08.newVersionOf', [action.actionCode, action.officeLevel])}
      >
        <Problem error={error} />
        <div className="bd rf-grid">
          {field(
            'a08-code',
            t('a08.code'),
            <input
              id="a08-code"
              maxLength={40}
              value={draft.actionCode}
              disabled={!isNew}
              onChange={(e) => set({ actionCode: e.target.value.toUpperCase() })}
            />,
            'actionCode',
          )}
          {field(
            'a08-name',
            t('a08.name'),
            <input
              id="a08-name"
              maxLength={200}
              value={draft.actionName}
              onChange={(e) => set({ actionName: e.target.value })}
            />,
            'actionName',
          )}
          {field(
            'a08-module',
            t('a08.module'),
            <input
              id="a08-module"
              maxLength={40}
              value={draft.moduleCode}
              onChange={(e) => set({ moduleCode: e.target.value.toUpperCase() })}
            />,
            'moduleCode',
          )}
          {field(
            'a08-screen',
            t('a08.screen'),
            <input
              id="a08-screen"
              maxLength={20}
              value={draft.screenCode ?? ''}
              onChange={(e) => set({ screenCode: e.target.value || null })}
            />,
            'screenCode',
          )}
          {field(
            'a08-office',
            t('a08.officeLevel'),
            <select
              id="a08-office"
              value={draft.officeLevel}
              disabled={!isNew}
              onChange={(e) => set({ officeLevel: e.target.value })}
            >
              {OFFICE_LEVELS.map((o) => (
                <option key={o} value={o}>
                  {o} · {t(`a08.officeLevelOf.${o}`)}
                </option>
              ))}
            </select>,
            'officeLevel',
          )}
          {field(
            'a08-count',
            t('a08.levelCount'),
            <select
              id="a08-count"
              value={draft.levelCount}
              onChange={(e) => setCount(Number(e.target.value))}
            >
              <option value={2}>2 — {t('a08.twoLevels')}</option>
              <option value={3}>3 — {t('a08.threeLevels')}</option>
            </select>,
            'levelCount',
          )}
          {field(
            'a08-otp',
            t('a08.otp'),
            <select
              id="a08-otp"
              value={draft.otpAction ?? ''}
              onChange={(e) => set({ otpAction: e.target.value || null })}
            >
              <option value="">{t('a08.noOtp')}</option>
              {OTP_ACTIONS.map((o) => (
                <option key={o} value={o}>
                  {`${o} - ${t(`a08.otp.${o}`)}`}
                </option>
              ))}
            </select>,
          )}
          {field(
            'a08-target',
            t('a08.targetHours'),
            <input
              id="a08-target"
              type="number"
              min={1}
              max={99999}
              value={draft.targetTatHours ?? ''}
              onChange={(e) => set({ targetTatHours: e.target.value === '' ? null : Number(e.target.value) })}
            />,
            'targetTatHours',
          )}
          {field(
            'a08-from',
            t('a08.from'),
            <input
              id="a08-from"
              type="date"
              value={draft.effectiveFrom}
              onChange={(e) => set({ effectiveFrom: e.target.value })}
            />,
            'effectiveFrom',
          )}
          <div className="rf-field">
            <label htmlFor="a08-attach">
              <input
                id="a08-attach"
                type="checkbox"
                checked={draft.attachmentRequired}
                onChange={(e) => set({ attachmentRequired: e.target.checked })}
              />{' '}
              {t('a08.attachment')}
            </label>
          </div>
          <div className="rf-field">
            <label htmlFor="a08-bulk">
              <input
                id="a08-bulk"
                type="checkbox"
                checked={draft.bulkApprovalAllowed}
                onChange={(e) => set({ bulkApprovalAllowed: e.target.checked })}
              />{' '}
              {t('a08.bulk')}
            </label>
          </div>
        </div>
        <div className="wrap">
          <table className="t">
            <thead>
              <tr>
                <th>
                  {t('a08.levelNo')}
                  <Src s="RULE" />
                </th>
                <th>
                  {t('a08.role')}
                  <Src s="RULE" />
                </th>
                <th>
                  {t('a08.officeLevel')}
                  <Src s="RULE" />
                </th>
                <th>
                  {t('a08.otpAtLevel')}
                  <Src s="RULE" />
                </th>
              </tr>
            </thead>
            <tbody>
              {draft.levels.map((l, i) => (
                <tr key={l.levelNo}>
                  <td>
                    {l.levelNo} · {t(`a08.level.${LEVEL_NAMES[i]}`)}
                  </td>
                  <td>
                    <input
                      aria-label={t('a08.roleOf', [l.levelNo])}
                      maxLength={30}
                      value={l.roleCode}
                      onChange={(e) => setLevel(i, { roleCode: e.target.value.toUpperCase() })}
                    />
                    {ruleOf(`levels[${i}]`).map((m) => (
                      <span key={m} className="fl-error" role="alert">
                        {m}
                      </span>
                    ))}
                  </td>
                  <td>
                    <select
                      aria-label={t('a08.officeOf', [l.levelNo])}
                      value={l.officeLevel}
                      onChange={(e) => setLevel(i, { officeLevel: e.target.value })}
                    >
                      {OFFICE_LEVELS.map((o) => (
                        <option key={o} value={o}>
                          {o} · {t(`a08.officeLevelOf.${o}`)}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td>
                    <input
                      type="checkbox"
                      aria-label={t('a08.otpOf', [l.levelNo])}
                      checked={l.otpAtLevel}
                      disabled={l.levelNo === 1 || !draft.otpAction}
                      onChange={(e) => setLevel(i, { otpAtLevel: e.target.checked })}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {ruleOf('levels').map((m) => (
            <p key={m} className="fl-error" role="alert">
              {m}
            </p>
          ))}
        </div>
        <div className="bd rf-grid">
          {field(
            'a08-reason',
            t('common.reason'),
            <TextAreaField
              id="a08-reason"
              maxLength={500}
              value={draft.reason}
              onChange={(e) => set({ reason: e.target.value })}
            />,
          )}
        </div>
        <p className="fl-hint">{t('a08.approvalNote')}</p>
        <div className="rf-actbar">
          <button type="button" className="btn" onClick={onClose}>
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className="btn pri"
            disabled={!draft.reason.trim() || propose.isPending}
            onClick={() => void submit()}
          >
            {t('common.submitForApproval')}
          </button>
        </div>
      </Card>
    </div>
  );
}

function Unstaffed() {
  const { t } = useI18n();
  const [total, setTotal] = useState(0);
  return (
    <div id="x01-unstaffed" className="fl-mt">
      <p className="fl-hint">{t('a08.unstaffedHint')}</p>
      <Card title={t('a08.tab.unstaffed', [total])}>
        <div className="wrap">
          <ServerPagination
            queryKey={unstaffedKey}
            fetchPage={workflowPages.unstaffed}
            empty={<Empty text={t('a08.unstaffedNone')} />}
            onTotal={setTotal}
          >
            {(items) => (
              <table className="t">
                <thead>
                  <tr>
                    <th>
                      {t('w01.id')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('w01.subject')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('w01.submittedBy')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('a08.waitingFor')}
                      <Src s="SYS" />
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((i) => (
                    <tr key={i.approvalId}>
                      <td>
                        <b>{i.approvalId}</b>
                      </td>
                      <td>
                        {i.moduleCode ? `${i.moduleCode} · ` : ''}
                        {entityLabel(t, i.entityName)} <span className="sub2">{i.entityKey}</span>
                      </td>
                      <td>
                        {i.submittedBy}
                        <div className="sub2">{dateTime(i.submittedAt)}</div>
                      </td>
                      <td>
                        {i.waitingForRole} · {t('w01.level', [i.level, i.levelCount])}
                        <div className="sub2">{i.office ?? '—'}</div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </ServerPagination>
        </div>
      </Card>
    </div>
  );
}
