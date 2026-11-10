'use client';

import { type FormEvent, useCallback, useState } from 'react';
import { useI18n } from '@/shared/i18n';
import { ReferenceOptions, useReference } from '@/shared/reference';
import { can, useMe } from '@/shared/session';
import {
  Card,
  dateTime,
  Empty,
  errorMessage,
  Problem,
  ReasonDialog,
  ServerPagination,
  StatusPill,
  useToast,
  formText,
  Src,
} from '@/shared/ui';
import type { UserCommand, UserRow } from '../api/adminApi';
import {
  adminKeys,
  fetchUserPage,
  useCreateUser,
  loginPages,
  offencePages,
  useSuspendUsers,
  useUserCommand,
} from '../orchestration/adminQueries';
import { ActiveUserReport, ReadinessCheck } from './UserReports';

type Pending =
  | { user: UserRow; kind: 'suspend' | 'deactivate' | 'unlock' | 'password' | 'terminate' | 'reinstate' }
  | { kind: 'suspendGroup'; userIds: number[] };

/**
 * A-01 Users. Access is never granted here: only through access-group assignments (A-04). No
 * password hash, secret or failure detail is ever shown. The list is paged on the server (only the
 * shown page is fetched). SRS-G-49: an administrator ends a user's open sessions, or suspends a
 * group of selected accounts during a security incident, each with a recorded reason; SRS-G-50: the
 * login record of each user (time, address, device and browser).
 */
function UserList() {
  const { t } = useI18n();
  const reference = useReference();
  const toast = useToast();
  const me = useMe();
  const command = useUserCommand();
  const createUser = useCreateUser();
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState('');
  const [creating, setCreating] = useState(false);
  const [pending, setPending] = useState<Pending | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [total, setTotal] = useState(0);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [loginsOf, setLoginsOf] = useState<UserRow | null>(null);
  const suspendUsers = useSuspendUsers();
  const fetchPage = useCallback(
    (page: number, size: number) => fetchUserPage(query, status, page, size),
    [query, status],
  );
  // UI_BMAD_g1 #83: the group suspension takes only users on the page shown; a search, a status
  // or a page change drops the others from the selection.
  const keepShown = useCallback((rows: UserRow[]) => {
    setSelected((current) => {
      const shown = new Set(rows.map((r) => r.id));
      const kept = [...current].filter((id) => shown.has(id));
      return kept.length === current.size ? current : new Set(kept);
    });
  }, []);

  async function act(next: UserCommand, message: (result: unknown) => string) {
    setError(null);
    try {
      toast(message(await command.mutateAsync(next)));
    } catch (cause) {
      setError(cause);
      toast(errorMessage(t, cause));
    }
  }

  async function suspendGroup(userIds: number[], reason: string) {
    setError(null);
    try {
      const outcome = await suspendUsers.mutateAsync({ userIds, reason });
      setSelected(new Set());
      toast(t('a01.suspendedGroup', [outcome.suspended.length, outcome.skipped.length]));
    } catch (cause) {
      setError(cause);
      toast(errorMessage(t, cause));
    }
  }

  function toggle(id: number) {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setError(null);
    try {
      await createUser.mutateAsync({
        loginId: formText(form, 'loginId'),
        displayName: formText(form, 'displayName'),
        category: formText(form, 'category'),
        employeeEis: formText(form, 'employeeEis') || null,
        mobile: formText(form, 'mobile') || null,
        temporaryPassword: formText(form, 'temporaryPassword'),
      });
      setCreating(false);
      toast(t('a01.created'));
    } catch (cause) {
      setError(cause);
    }
  }

  const editable = can(me.data, 'A-01', 'EDIT');
  /** A user the session may act on: not deactivated, not the administrator's own account. */
  const actionable = (user: UserRow) => user.status !== 'DEACTIVATED' && user.id !== me.data?.userId;
  return (
    <div>
      <div className="toolbar">
        <div className="lsearch">
          🔎
          <input
            aria-label={t('a01.search')}
            placeholder={t('a01.search')}
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setSelected(new Set());
            }}
            maxLength={60}
          />
        </div>
        <select
          aria-label={t('m01.status')}
          value={status}
          onChange={(e) => {
            setStatus(e.target.value);
            setSelected(new Set());
          }}
        >
          <option value="">{t('m01.allStatuses')}</option>
          <ReferenceOptions list="USER_STATUS" />
        </select>
        {editable && selected.size > 0 && (
          <button
            type="button"
            className="btn"
            onClick={() => setPending({ kind: 'suspendGroup', userIds: [...selected] })}
          >
            {t('a01.suspendSelected', [selected.size])}
          </button>
        )}
        {can(me.data, 'A-01', 'CREATE') && (
          <button type="button" className="btn pri" onClick={() => setCreating(true)}>
            {t('action.newUser')}
          </button>
        )}
      </div>
      <Problem error={error} />
      {creating && (
        <Card title={t('action.newUser')}>
          <form className="bd" onSubmit={create}>
            <div className="rf-grid">
              <div className="rf-field">
                <label htmlFor="u-login">{t('a01.loginId')}</label>
                {/* SRS l.910: User ID, Alphanumeric (100). */}
                <input id="u-login" name="loginId" type="email" maxLength={100} required />
                <span className="hint">{t('a01.loginHint')}</span>
              </div>
              <div className="rf-field">
                <label htmlFor="u-name">{t('a01.displayName')}</label>
                <input id="u-name" name="displayName" maxLength={150} required />
              </div>
              <div className="rf-field">
                <label htmlFor="u-category">{t('a01.category')}</label>
                <select id="u-category" name="category">
                  <ReferenceOptions list="USER_CATEGORY" />
                </select>
              </div>
              <div className="rf-field">
                <label htmlFor="u-eis">{t('a01.employeeEis')}</label>
                <input id="u-eis" name="employeeEis" maxLength={20} />
              </div>
              <div className="rf-field">
                <label htmlFor="u-mobile">{t('field.mobileNumber')}</label>
                <input id="u-mobile" name="mobile" type="tel" inputMode="numeric" maxLength={20} />
              </div>
              <div className="rf-field">
                <label htmlFor="u-temp">{t('a01.temporaryPassword')}</label>
                <input
                  id="u-temp"
                  name="temporaryPassword"
                  type="password"
                  maxLength={128}
                  autoComplete="new-password"
                  required
                />
                <span className="hint">{t('password.rules')}</span>
              </div>
            </div>
            <div className="rf-actbar">
              <button type="button" className="btn" onClick={() => setCreating(false)}>
                {t('common.cancel')}
              </button>
              <button type="submit" className="btn pri">
                {t('common.create')}
              </button>
            </div>
          </form>
        </Card>
      )}
      <Card title={t('a01.title', [total])}>
        <ServerPagination
          queryKey={[...adminKeys.users, 'page', query, status]}
          fetchPage={fetchPage}
          onTotal={setTotal}
          onRows={keepShown}
          empty={<Empty text={t('a01.none')} />}
        >
          {(rows) => (
            <div className="wrap">
              <table className="t">
                <thead>
                  <tr>
                    {editable && (
                      <th>
                        <input
                          type="checkbox"
                          aria-label={t('a01.selectAll')}
                          checked={
                            rows.some((u) => u.status === 'ACTIVE' && actionable(u)) &&
                            rows
                              .filter((u) => u.status === 'ACTIVE' && actionable(u))
                              .every((u) => selected.has(u.id))
                          }
                          onChange={(e) => {
                            const ids = rows
                              .filter((u) => u.status === 'ACTIVE' && actionable(u))
                              .map((u) => u.id);
                            setSelected((current) => {
                              const next = new Set(current);
                              ids.forEach((id) => (e.target.checked ? next.add(id) : next.delete(id)));
                              return next;
                            });
                          }}
                        />
                      </th>
                    )}
                    <th>
                      {t('a01.loginId')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('a01.displayName')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('a01.category')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('a01.employeeEis')}
                      <Src s="HRMS" />
                    </th>
                    <th>
                      {t('m01.status')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('a01.lastLogin')}
                      <Src s="SYS" />
                    </th>
                    <th aria-label={t('common.actions')} />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((user) => (
                    <tr key={user.id}>
                      {editable && (
                        <td>
                          {user.status === 'ACTIVE' && actionable(user) && (
                            <input
                              type="checkbox"
                              aria-label={t('a01.select', [user.loginId])}
                              checked={selected.has(user.id)}
                              onChange={() => toggle(user.id)}
                            />
                          )}
                        </td>
                      )}
                      <td>
                        <b>{user.loginId}</b>
                      </td>
                      <td>{user.displayName}</td>
                      <td>{reference.label('USER_CATEGORY', user.category)}</td>
                      <td>{user.employeeEis ?? '—'}</td>
                      <td>
                        <StatusPill status={user.status} />
                        {user.locked && <span className="pill p-bad">{t('a01.lockedOut')}</span>}
                        {user.mustChangePassword && (
                          <span className="pill p-info">{t('a01.mustChange')}</span>
                        )}
                      </td>
                      <td>{dateTime(user.lastLoginAt)}</td>
                      <td>
                        <div className="acts">
                          <button
                            type="button"
                            className="ia"
                            onClick={() => setLoginsOf(loginsOf?.id === user.id ? null : user)}
                          >
                            {t('a01.logins')}
                          </button>
                          {editable && actionable(user) && (
                            <>
                              {user.status === 'ACTIVE' ? (
                                <button
                                  type="button"
                                  className="ia"
                                  onClick={() => setPending({ user, kind: 'suspend' })}
                                >
                                  {t('a01.suspend')}
                                </button>
                              ) : (
                                <button
                                  type="button"
                                  className="ia"
                                  onClick={() => setPending({ user, kind: 'reinstate' })}
                                >
                                  {t('a01.reinstate')}
                                </button>
                              )}
                              <button
                                type="button"
                                className="ia"
                                onClick={() => setPending({ user, kind: 'terminate' })}
                              >
                                {t('a01.terminate')}
                              </button>
                              {user.locked && (
                                <button
                                  type="button"
                                  className="ia"
                                  onClick={() => setPending({ user, kind: 'unlock' })}
                                >
                                  {t('a01.unlock')}
                                </button>
                              )}
                              <button
                                type="button"
                                className="ia"
                                onClick={() => setPending({ user, kind: 'password' })}
                              >
                                {t('a01.resetPassword')}
                              </button>
                              {can(me.data, 'A-01', 'DELETE') && (
                                <button
                                  type="button"
                                  className="ia d"
                                  onClick={() => setPending({ user, kind: 'deactivate' })}
                                >
                                  {t('a01.deactivate')}
                                </button>
                              )}
                            </>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </ServerPagination>
      </Card>
      {loginsOf && <LoginRecordCard key={loginsOf.id} user={loginsOf} onClose={() => setLoginsOf(null)} />}
      {pending?.kind === 'terminate' && (
        <ReasonDialog
          title={t('a01.terminateTitle', [pending.user.loginId])}
          confirmLabel={t('a01.terminate')}
          onCancel={() => setPending(null)}
          onConfirm={(reason) => {
            const { user } = pending;
            setPending(null);
            void act({ kind: 'terminate', userId: user.id, reason }, (result) =>
              t('a01.terminated', [(result as { sessions: number }).sessions]),
            );
          }}
        />
      )}
      {pending?.kind === 'suspendGroup' && (
        <ReasonDialog
          title={t('a01.suspendGroupTitle', [pending.userIds.length])}
          confirmLabel={t('a01.suspend')}
          onCancel={() => setPending(null)}
          onConfirm={(reason) => {
            const { userIds } = pending;
            setPending(null);
            void suspendGroup(userIds, reason);
          }}
        />
      )}
      {pending?.kind === 'unlock' && (
        <ReasonDialog
          title={t('a01.unlockTitle', [pending.user.loginId])}
          onCancel={() => setPending(null)}
          onConfirm={(reason) => {
            const { user } = pending;
            setPending(null);
            void act({ kind: 'unlock', userId: user.id, reason }, () => t('a01.unlocked'));
          }}
        />
      )}
      {pending?.kind === 'reinstate' && (
        <ReasonDialog
          title={t('a01.reinstateTitle', [pending.user.loginId])}
          confirmLabel={t('a01.reinstate')}
          onCancel={() => setPending(null)}
          onConfirm={(note) => {
            const { user } = pending;
            setPending(null);
            void act({ kind: 'status', userId: user.id, body: { status: 'ACTIVE', note } }, () =>
              t('a01.reinstated'),
            );
          }}
        />
      )}
      {pending && (pending.kind === 'suspend' || pending.kind === 'deactivate') && (
        <ReasonDialog
          title={t(pending.kind === 'suspend' ? 'a01.suspendTitle' : 'a01.deactivateTitle', [
            pending.user.loginId,
          ])}
          onCancel={() => setPending(null)}
          onConfirm={(note) => {
            const { user, kind } = pending;
            setPending(null);
            void act(
              {
                kind: 'status',
                userId: user.id,
                body:
                  kind === 'suspend'
                    ? { status: 'SUSPENDED', note }
                    : { status: 'DEACTIVATED', reason: 'SEPARATION', note },
              },
              () => t(kind === 'suspend' ? 'a01.suspended' : 'a01.deactivated'),
            );
          }}
        />
      )}
      {pending?.kind === 'password' && (
        <TemporaryPassword
          loginId={pending.user.loginId}
          onCancel={() => setPending(null)}
          onConfirm={(password) => {
            const { user } = pending;
            setPending(null);
            void act({ kind: 'password', userId: user.id, temporaryPassword: password }, () =>
              t('a01.passwordReset'),
            );
          }}
        />
      )}
    </div>
  );
}

type View = 'users' | 'report' | 'readiness';

/**
 * A-01 (X-01 user administration): the users and their accounts, the Active User Report (F-3) and
 * the Maker / Checker readiness of every payroll unit (F-7), as three views of one screen.
 */
export function UsersAdmin() {
  const { t } = useI18n();
  const [view, setView] = useState<View>('users');
  const tab = (key: View) => (
    <button
      type="button"
      role="tab"
      aria-selected={view === key}
      className={`btn${view === key ? ' pri' : ''}`}
      onClick={() => setView(key)}
    >
      {t(`a01.tab.${key}`)}
    </button>
  );
  return (
    <div className="wireframe-body-container">
      <div className="toolbar" role="tablist" aria-label={t('a01.tabs')}>
        {tab('users')}
        {tab('report')}
        {tab('readiness')}
      </div>
      {view === 'users' && <UserList />}
      {view === 'report' && <ActiveUserReport />}
      {view === 'readiness' && <ReadinessCheck />}
    </div>
  );
}

/** OD-130: each refused call of the user, and the suspension with its justification. */
function OffenceRecord({ user }: { user: UserRow }) {
  const { t } = useI18n();
  return (
    <div className="wrap">
      <ServerPagination
        queryKey={offencePages.key(user.id)}
        fetchPage={offencePages.of(user.id)}
        empty={<Empty text={t('a01.offence.none')} />}
      >
        {(rows) => (
          <table className="t">
            <caption>{t('a01.offence.title')}</caption>
            <thead>
              <tr>
                <th>
                  {t('a01.offence.when')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('a01.offence.type')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('a01.offence.what')}
                  <Src s="SYS" />
                </th>
                <th>
                  {t('a01.login.address')}
                  <Src s="SYS" />
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((offence) => (
                <tr key={`${offence.occurredAt}-${offence.type}`}>
                  <td>{dateTime(offence.occurredAt)}</td>
                  <td>{t(`a01.offence.type.${offence.type}`)}</td>
                  <td>{offence.type === 'ACCOUNT_SUSPENDED_OFFENCE' ? offence.detail : offence.target}</td>
                  <td>{offence.sourceIp ?? '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </ServerPagination>
    </div>
  );
}

/** SRS-G-50: the latest sign-ins of one user: time, address, device and browser, how each ended. */
function LoginRecordCard({ user, onClose }: { user: UserRow; onClose: () => void }) {
  const { t } = useI18n();
  return (
    <Card
      title={t('a01.loginsOf', [user.loginId])}
      actions={
        <button type="button" className="btn sm" onClick={onClose}>
          {t('common.close')}
        </button>
      }
    >
      {/* OD-130: what the Administrator decides on: the refused calls and the suspension. */}
      <OffenceRecord user={user} />
      <div className="wrap">
        <ServerPagination
          queryKey={loginPages.key(user.id)}
          fetchPage={loginPages.of(user.id)}
          empty={<Empty text={t('a01.login.none')} />}
        >
          {(rows) => (
            <table className="t">
              <thead>
                <tr>
                  <th>
                    {t('a01.login.started')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('a01.login.address')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('a01.login.device')}
                    <Src s="SYS" />
                  </th>
                  <th>
                    {t('a01.login.ended')}
                    <Src s="SYS" />
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((login) => (
                  <tr key={login.sessionId}>
                    <td>{dateTime(login.startedAt)}</td>
                    <td>{login.sourceIp ?? '—'}</td>
                    <td>{login.device}</td>
                    <td>
                      {login.open ? (
                        <span className="pill p-ok">{t('a01.login.open')}</span>
                      ) : (
                        <>
                          {dateTime(login.endedAt)}
                          {login.endReason && (
                            <div className="sub2">{t(`session.end.${login.endReason}`)}</div>
                          )}
                        </>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </ServerPagination>
      </div>
    </Card>
  );
}

function TemporaryPassword({
  loginId,
  onConfirm,
  onCancel,
}: {
  loginId: string;
  onConfirm: (password: string) => void;
  onCancel: () => void;
}) {
  const { t } = useI18n();
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    onConfirm(String(new FormData(event.currentTarget).get('password') ?? ''));
  }
  return (
    <div className="fl-modal-backdrop" role="presentation">
      <form
        className="fl-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="temp-title"
        onSubmit={submit}
      >
        <h2 id="temp-title">{t('a01.resetTitle', [loginId])}</h2>
        <label className="fl-field">
          {t('a01.temporaryPassword')}
          <input name="password" type="password" maxLength={128} autoComplete="new-password" required />
        </label>
        <p className="fl-hint">{t('password.rules')}</p>
        <div className="fl-actions">
          <button type="button" className="btn" onClick={onCancel}>
            {t('common.cancel')}
          </button>
          <button type="submit" className="btn pri">
            {t('a01.resetPassword')}
          </button>
        </div>
      </form>
    </div>
  );
}
