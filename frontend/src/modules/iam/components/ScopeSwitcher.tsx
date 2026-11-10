'use client';

import { useState } from 'react';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { type Me, type ScopeOption, useScopes } from '@/shared/session';
import {
  currentScope,
  isEseva,
  officesByType,
  roleOptions,
  scopeForOffice,
  scopeKey,
} from '../business/scopeRules';
import { useSwitchScope } from '../orchestration/authQueries';

/**
 * The top-bar 🏢 office and 👤 role selectors of wireframe v6.1: the user works in one office and
 * one acting role at a time and switches between those of their OWN RBAC (active access groups,
 * eSeva grant) without signing out. Options come only from /api/auth/scopes; the server re-checks
 * every switch, and the session id rotates.
 */
export function ScopeSwitcher({
  enabled,
  onSwitched,
  onError,
}: {
  enabled: boolean;
  onSwitched: (next: Me) => void;
  onError: (message: string) => void;
}) {
  const { t } = useI18n();
  const scopes = useScopes(enabled);
  const switchScope = useSwitchScope();
  const [busy, setBusy] = useState(false);
  const all = scopes.data ?? [];
  const current = currentScope(all);
  const roles = roleOptions(all);
  const groups = officesByType(all);
  const officeCount = groups.reduce((n, g) => n + g.offices.length, 0);

  async function go(target: ScopeOption | undefined) {
    if (!target || target.current) return;
    setBusy(true);
    try {
      onSwitched(await switchScope.mutateAsync({ role: target.role, location: target.location }));
    } catch (cause) {
      onError(cause instanceof ApiError ? t(cause.messageKey, cause.args) : t('error.unexpected'));
    } finally {
      setBusy(false);
    }
  }

  const roleLabel = (s: ScopeOption) => (isEseva(s) ? t('role.EMPLOYEE_ESEVA') : t(`role.${s.role}`));

  return (
    <>
      <div className={`ctx office${busy ? ' switching' : ''}`}>
        <span aria-hidden="true">🏢</span>
        <select
          aria-label={t('top.office')}
          title={t('top.officeHint')}
          value={current && !isEseva(current) ? (current.location ?? '') : ''}
          disabled={busy || officeCount === 0 || (officeCount === 1 && !!current && !isEseva(current))}
          onChange={(e) => void go(scopeForOffice(all, e.target.value))}
        >
          {(!current || isEseva(current)) && (
            <option value="" disabled>
              {t('scope.noOffice')}
            </option>
          )}
          {groups.map((group) => (
            <optgroup key={group.type} label={t(`scope.office.${group.type}`)}>
              {group.offices.map((office) => (
                <option key={office.code} value={office.code}>
                  {office.label}
                </option>
              ))}
            </optgroup>
          ))}
        </select>
      </div>
      <div className={`ctx role${busy ? ' switching' : ''}`}>
        <span aria-hidden="true">👤</span>
        <select
          aria-label={t('top.role')}
          title={t('top.roleHint')}
          value={current ? scopeKey(current) : ''}
          disabled={busy || roles.length < 2}
          onChange={(e) => void go(roles.find((s) => scopeKey(s) === e.target.value))}
        >
          {!current && <option value="">{t('common.loading')}</option>}
          {roles.map((s) => (
            <option key={scopeKey(s)} value={scopeKey(s)}>
              {roleLabel(s)}
            </option>
          ))}
        </select>
      </div>
    </>
  );
}
