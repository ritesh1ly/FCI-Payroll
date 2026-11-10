'use client';

import { type FormEvent, memo, useCallback, useEffect, useMemo, useState } from 'react';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions } from '@/shared/reference';
import { can, hqOnlyBlock, useMe } from '@/shared/session';
import {
  Card,
  errorMessage,
  Pager,
  Problem,
  usePageSizes,
  ReasonDialog,
  StatusPill,
  useToast,
  formText,
  Src,
  TextAreaField,
} from '@/shared/ui';
import type { Permission, Role } from '../api/adminApi';
import { useCreateRole, useRoleMatrix, useSetGrant } from '../orchestration/adminQueries';

/**
 * A-02 Roles & Permissions: the effective-dated role × permission matrix (app_role_permission).
 * Every change — a new role, a permission granted or withdrawn — is a proposal with a reason and the
 * maker's OTP that a Payroll Checker at Headquarters approves (SRS-22-8, SRS-22-23); nothing changes
 * before. The System Administrator role is protected.
 */
export function RolesMatrix() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const hqBlock = hqOnlyBlock(me.data, 'A-02');
  const matrix = useRoleMatrix();
  const setGrant = useSetGrant();
  const createRole = useCreateRole();
  const [creating, setCreating] = useState(false);
  const [pending, setPending] = useState<{
    role: Role;
    permissionId: number;
    next: boolean;
    key: string;
  } | null>(null);
  const [error, setError] = useState<unknown>(null);
  const granted = useMemo(
    () => new Set((matrix.data?.grants ?? []).map((g) => `${g.roleId}:${g.permissionId}`)),
    [matrix.data],
  );
  const editable = can(me.data, 'A-02', 'EDIT');
  // X-01 "＋ New Role (OTP)" arrives here with new=role: the form opens for whoever may propose a role.
  const mayPropose = can(me.data, 'A-02', 'CREATE') && hqBlock === null;
  useEffect(() => {
    if (mayPropose && new URLSearchParams(window.location.search).get('new') === 'role') setCreating(true);
  }, [mayPropose]);

  async function toggle(role: Role, permissionId: number, next: boolean, reason: string) {
    setError(null);
    try {
      await setGrant.mutateAsync({ roleId: role.id, permissionId, granted: next, reason });
      toast(t('a02.proposed', [role.code]));
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
      toast(errorMessage(t, cause));
    }
  }

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setError(null);
    try {
      await createRole.mutateAsync({
        code: formText(form, 'code'),
        name: formText(form, 'name'),
        description: formText(form, 'description') || null,
        approver: form.get('approver') === 'on',
        roleType: formText(form, 'roleType', 'MODULE'),
        baseRole: form.get('roleType') === 'READ_ONLY' ? null : formText(form, 'baseRole') || null,
        reason: formText(form, 'reason'),
      });
      setCreating(false);
      toast(t('a02.created'));
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  const roles = matrix.data?.roles ?? [];
  // SRS-NF-3, owner list-paging standard: the matrix is roles x permissions (21 x 491 on the demo
  // data = 10,311 check boxes), too heavy to draw in one go (WebKit took 4 s to paint it, 5-8 s to
  // leave it). It pages by permission row, 20 to start, and a filter finds a permission by its
  // screen code, action or screen name; every box of the shown rows is in the page.
  const { defaultSize } = usePageSizes();
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState<number | null>(null);
  const pageSize = size ?? defaultSize;
  const needle = query.trim().toLowerCase();
  const permissions = useMemo(() => {
    const all = matrix.data?.permissions ?? [];
    return needle === ''
      ? all
      : all.filter((p) =>
          `${p.screenCode}:${p.actionCode} ${t(`screen.${p.screenCode}`)}`.toLowerCase().includes(needle),
        );
  }, [matrix.data, needle, t]);
  const lastPage = Math.max(0, Math.ceil(permissions.length / pageSize) - 1);
  const shown = permissions.slice(
    Math.min(page, lastPage) * pageSize,
    Math.min(page, lastPage) * pageSize + pageSize,
  );
  // A stable handler, so a memoised row is not drawn again when the dialog opens or a toast shows.
  const propose = useCallback(
    (role: Role, permission: Permission, next: boolean) =>
      setPending({
        role,
        permissionId: permission.id,
        next,
        key: `${permission.screenCode}:${permission.actionCode}`,
      }),
    [],
  );
  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        {can(me.data, 'A-02', 'CREATE') && (
          <button
            type="button"
            disabled={hqBlock !== null}
            title={hqBlock ? t(hqBlock) : undefined}
            className="btn pri"
            onClick={() => setCreating(true)}
          >
            {t('action.newRole')}
          </button>
        )}
        <span className="sub2">{t('a02.hint')}</span>
      </div>
      <div className="toolbar">
        <input
          type="search"
          aria-label={t('a02.filter')}
          placeholder={t('a02.filter')}
          value={query}
          maxLength={60}
          onChange={(e) => {
            setQuery(e.target.value);
            setPage(0);
          }}
        />
      </div>
      <Problem error={matrix.error ?? error} />
      {creating && (
        <Card title={t('action.newRole')}>
          <form className="bd" onSubmit={create}>
            <div className="rf-grid">
              <div className="rf-field">
                <label htmlFor="r-code">{t('a02.code')}</label>
                <input id="r-code" name="code" maxLength={30} pattern="[A-Za-z][A-Za-z0-9_]{2,29}" required />
              </div>
              <div className="rf-field">
                <label htmlFor="r-name">{t('a02.name')}</label>
                <input id="r-name" name="name" maxLength={150} required />
              </div>
              <div className="rf-field">
                <label htmlFor="r-desc">{t('a02.description')}</label>
                <TextAreaField id="r-desc" name="description" maxLength={500} />
              </div>
              <div className="rf-field">
                <label htmlFor="r-type">{t('a02.roleType')}</label>
                <select id="r-type" name="roleType" defaultValue="MODULE">
                  <ReferenceOptions list="ROLE_TYPE" />
                </select>
              </div>
              <div className="rf-field">
                <label htmlFor="r-base">{t('a02.baseRole')}</label>
                <select id="r-base" name="baseRole" defaultValue="MAKER">
                  <ReferenceOptions list="BASE_ROLE" />
                </select>
                <span className="hint">{t('a02.baseRoleHint')}</span>
              </div>
              <div className="rf-field">
                <label htmlFor="r-approver">
                  <input id="r-approver" name="approver" type="checkbox" /> {t('a02.approver')}
                </label>
              </div>
              <div className="rf-field span2">
                <label htmlFor="r-reason">{t('common.reason')}</label>
                <TextAreaField id="r-reason" name="reason" maxLength={500} required />
              </div>
            </div>
            <p className="fl-hint">{t('a03.otpNote')}</p>
            <div className="rf-actbar">
              <button type="button" className="btn" onClick={() => setCreating(false)}>
                {t('common.cancel')}
              </button>
              <button type="submit" className="btn pri">
                {t('common.submitForApproval')}
              </button>
            </div>
          </form>
        </Card>
      )}
      <Card title={t('a02.title', [roles.length])}>
        <div className="wrap">
          <table className="t fl-matrix">
            <thead>
              <tr>
                <th>
                  {t('a02.permission')}
                  <Src s="SYS" />
                </th>
                {roles.map((role) => (
                  <th key={role.id} title={role.name}>
                    {role.code}
                    <Src s="ENT" />
                    <div>
                      <StatusPill status={role.status} />
                    </div>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {shown.map((permission) => (
                <MatrixRow
                  key={permission.id}
                  permission={permission}
                  roles={roles}
                  granted={granted}
                  locked={!editable || hqBlock !== null}
                  hqBlockKey={editable ? hqBlock : null}
                  onPropose={propose}
                />
              ))}
            </tbody>
          </table>
        </div>
        <Pager
          page={Math.min(page, lastPage)}
          size={pageSize}
          total={permissions.length}
          onPage={setPage}
          onSize={(n) => {
            setSize(n);
            setPage(0);
          }}
        />
      </Card>
      {pending && (
        <ReasonDialog
          title={t(pending.next ? 'a02.grantTitle' : 'a02.withdrawTitle', [pending.key, pending.role.code])}
          confirmLabel={t('common.submitForApproval')}
          onCancel={() => setPending(null)}
          onConfirm={(reason) => {
            const change = pending;
            setPending(null);
            void toggle(change.role, change.permissionId, change.next, reason);
          }}
        />
      )}
    </div>
  );
}

interface MatrixRowProps {
  permission: Permission;
  roles: Role[];
  granted: Set<string>;
  /** No grant can be changed: no EDIT right, or outside Headquarters. */
  locked: boolean;
  /** The reason key shown on a box that is locked only because of the office (UI audit S13). */
  hqBlockKey: string | null;
  onPropose: (role: Role, permission: Permission, next: boolean) => void;
}

/** One permission row of the matrix; memoised so only a row whose inputs changed is drawn again. */
const MatrixRow = memo(function MatrixRow({
  permission,
  roles,
  granted,
  locked,
  hqBlockKey,
  onPropose,
}: MatrixRowProps) {
  const { t } = useI18n();
  const code = `${permission.screenCode}:${permission.actionCode}`;
  return (
    <tr>
      <td>
        <b>{code}</b>
        <div className="sub2">{t(`screen.${permission.screenCode}`)}</div>
      </td>
      {roles.map((role) => {
        const on = granted.has(`${role.id}:${permission.id}`);
        return (
          <td key={role.id}>
            <input
              type="checkbox"
              checked={on}
              // UI audit S13: a grant is changed at Headquarters only (the server refuses it
              // elsewhere), so outside HQ the box says why before any OTP.
              disabled={locked || role.protectedRole}
              title={
                role.protectedRole ? t('error.ADM-403-PROTECTED') : hqBlockKey ? t(hqBlockKey) : undefined
              }
              aria-label={t('a02.toggle', [role.code, code])}
              onChange={() => onPropose(role, permission, !on)}
            />
          </td>
        );
      })}
    </tr>
  );
});
