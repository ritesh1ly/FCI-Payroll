'use client';

import { type FormEvent, useState } from 'react';
import { ApiError } from '@/shared/api';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { businessToday } from '@/shared/time';
import {
  Card,
  date,
  dateTime,
  errorMessage,
  formText,
  Problem,
  ReasonDialog,
  ServerPagination,
  Src,
  StatusPill,
  useToast,
  TextAreaField,
} from '@/shared/ui';
import type { Assignment } from '../api/adminApi';
import { assignableGroups, assignableUsers } from '../business/accessRules';
import {
  adminKeys,
  assignmentPages,
  useGroups,
  useProposeAssignment,
  useRevokeAssignment,
  useUsers,
} from '../orchestration/adminQueries';

/**
 * A-04 Access Assignments: inert until a Payroll Checker of the group's location approves it with
 * an OTP; nobody assigns access to themselves or approves their own; revocation is immediate.
 */
export function Assignments() {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const [total, setTotal] = useState(0);
  const groups = useGroups(can(me.data, 'A-03', 'VIEW'));
  const proposeAssignment = useProposeAssignment();
  const revokeAssignment = useRevokeAssignment();
  const [creating, setCreating] = useState(false);
  const [userQuery, setUserQuery] = useState('');
  const users = useUsers(userQuery.trim(), 'ACTIVE', creating && can(me.data, 'A-01', 'VIEW'));
  const [revoking, setRevoking] = useState<Assignment | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [rangeError, setRangeError] = useState(false);

  async function propose(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setError(null);
    // UI_BMAD_g1 #82: Effective To is not before Effective From (both given; a blank From is today,
    // which the server checks).
    const from = formText(form, 'from');
    const to = formText(form, 'to');
    const reversed = Boolean(from && to && to < from);
    setRangeError(reversed);
    if (reversed) return;
    try {
      await proposeAssignment.mutateAsync({
        userId: Number(form.get('userId')),
        groupId: Number(form.get('groupId')),
        effectiveFrom: formText(form, 'from') || null,
        effectiveTo: formText(form, 'to') || null,
        reason: formText(form, 'reason'),
      });
      setCreating(false);
      toast(t('a04.submitted'));
    } catch (cause) {
      if (cause instanceof ApiError && cause.messageKey === 'otp.cancelled') return;
      setError(cause);
    }
  }

  async function revoke(assignment: Assignment, reason: string) {
    try {
      await revokeAssignment.mutateAsync({ assignmentId: assignment.id, reason });
      toast(t('a04.revoked', [assignment.loginId ?? assignment.userId]));
    } catch (cause) {
      toast(errorMessage(t, cause));
    }
  }

  const approvedGroups = assignableGroups(groups.data ?? [], businessToday());
  const others = assignableUsers(users.data?.items ?? [], me.data?.userId);
  return (
    <div className="wireframe-body-container">
      <div className="toolbar">
        {can(me.data, 'A-04', 'CREATE') && (
          <button type="button" className="btn pri" onClick={() => setCreating(true)}>
            {t('action.newAssignment')}
          </button>
        )}
        <span className="sub2">{t('a04.hint')}</span>
      </div>
      <Problem error={error} />
      {creating && (
        <Card title={t('action.newAssignment')}>
          <form className="bd" onSubmit={propose}>
            <div className="rf-grid">
              <div className="rf-field">
                <label htmlFor="as-find">{t('a04.findUser')}</label>
                <input
                  id="as-find"
                  value={userQuery}
                  maxLength={60}
                  onChange={(e) => setUserQuery(e.target.value)}
                />
                {(users.data?.total ?? 0) > (users.data?.items.length ?? 0) && (
                  <span className="fl-hint">
                    {t('a04.moreUsers', [users.data?.items.length ?? 0, users.data?.total ?? 0])}
                  </span>
                )}
              </div>
              <div className="rf-field">
                <label htmlFor="as-user">{t('a04.user')}</label>
                <select id="as-user" name="userId" required>
                  {others.map((u) => (
                    <option key={u.id} value={u.id}>
                      {u.loginId} — {u.displayName}
                    </option>
                  ))}
                </select>
              </div>
              <div className="rf-field">
                <label htmlFor="as-group">{t('a04.group')}</label>
                <select id="as-group" name="groupId" required>
                  {approvedGroups.map((g) => (
                    <option key={g.id} value={g.id}>
                      {g.code} — {g.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="rf-field">
                <label htmlFor="as-from">{t('a04.from')}</label>
                <input id="as-from" name="from" type="date" />
              </div>
              <div className="rf-field">
                <label htmlFor="as-to">{t('a04.to')}</label>
                <input id="as-to" name="to" type="date" />
                {rangeError && (
                  <span className="fl-error" role="alert">
                    {t('a04.toBeforeFrom')}
                  </span>
                )}
              </div>
              <div className="rf-field span2">
                <label htmlFor="as-reason">{t('common.reason')}</label>
                <TextAreaField id="as-reason" name="reason" maxLength={500} required />
              </div>
            </div>
            <p className="fl-hint">{t('a04.otpNote')}</p>
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
      <Card title={t('a04.title', [total])}>
        <div className="wrap">
          <ServerPagination
            queryKey={adminKeys.assignments}
            fetchPage={assignmentPages.list}
            onTotal={setTotal}
          >
            {(items) => (
              <table className="t">
                <thead>
                  <tr>
                    <th>
                      {t('a04.user')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('a04.group')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('a03.role')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('a03.effective')}
                      <Src s="ENT" />
                    </th>
                    <th>
                      {t('a04.assignedBy')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('a03.approval')}
                      <Src s="SYS" />
                    </th>
                    <th>
                      {t('a04.state')}
                      <Src s="SYS" />
                    </th>
                    <th aria-label={t('common.actions')} />
                  </tr>
                </thead>
                <tbody>
                  {items.map((a) => (
                    <tr key={a.id}>
                      <td>
                        <b>{a.loginId ?? a.userId}</b>
                      </td>
                      <td>{a.groupCode ?? '—'}</td>
                      <td>{a.roleCode ?? '—'}</td>
                      <td>
                        {/* SRS l.1131 (UI_BMAD_g1 #81): dates as DD-MM-YYYY. */}
                        {date(a.effectiveFrom)}
                        {a.effectiveTo ? ` → ${date(a.effectiveTo)}` : ''}
                      </td>
                      <td>{a.assignedBy}</td>
                      <td>
                        <StatusPill status={a.approvalStatus} />
                      </td>
                      <td>
                        {a.revokedAt ? (
                          <span className="pill p-gray" title={a.revokeReason ?? undefined}>
                            {t('a04.revokedAt', [dateTime(a.revokedAt)])}
                          </span>
                        ) : a.current ? (
                          <span className="pill p-ok">{t('a04.current')}</span>
                        ) : (
                          <span className="pill p-warn">{t('a04.inert')}</span>
                        )}
                      </td>
                      <td>
                        {can(me.data, 'A-04', 'DELETE') && !a.revokedAt && a.userId !== me.data?.userId && (
                          <div className="acts">
                            <button type="button" className="ia d" onClick={() => setRevoking(a)}>
                              {t('action.revoke')}
                            </button>
                          </div>
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
      {revoking && (
        <ReasonDialog
          title={t('a04.revokeTitle', [revoking.loginId ?? revoking.userId, revoking.groupCode ?? ''])}
          onCancel={() => setRevoking(null)}
          onConfirm={(reason) => {
            const a = revoking;
            setRevoking(null);
            void revoke(a, reason);
          }}
        />
      )}
    </div>
  );
}
