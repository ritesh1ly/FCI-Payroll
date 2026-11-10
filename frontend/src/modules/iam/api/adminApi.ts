import { API_BASE, api } from '@/shared/api';
import type { OtpProof } from '@/modules/workflow';
import type { PageResult } from '@/shared/ui';

/** A-01 user row (no password hash, secret or failure detail ever). */
export interface UserRow {
  id: number;
  loginId: string;
  displayName: string;
  category: string;
  status: string;
  employeeEis: string | null;
  locked: boolean;
  mustChangePassword: boolean;
  lastLoginAt: string | null;
}

export interface NewUser {
  loginId: string;
  displayName: string;
  category: string;
  employeeEis: string | null;
  mobile: string | null;
  temporaryPassword: string;
}

/** One sign-in of the A-01 login record (SRS-G-50). */
export interface LoginRecord {
  sessionId: number;
  startedAt: string;
  endedAt: string | null;
  endReason: string | null;
  sourceIp: string | null;
  device: string;
  open: boolean;
}

/** A group suspension's outcome: who was suspended, who was already not active (SRS-G-49). */
export interface SuspendOutcome {
  suspended: number[];
  skipped: number[];
}

/** One access a user holds today (F-3): group, role and the offices it reaches ("ALL"). */
export interface AccessRow {
  groupCode: string;
  roleCode: string;
  unit: string | null;
}

/** One row of the Active User Report (SRS-G-50). */
export interface ActiveUserRow {
  userId: number;
  loginId: string;
  displayName: string;
  status: string;
  lastLoginAt: string | null;
  daysSinceLogin: number | null;
  /** null while the threshold is not configured on A-07. */
  inactive: boolean | null;
  access: AccessRow[];
}

/** One page of the Active User Report; inactiveAfterDays is null while not configured. */
export interface ActiveUserReport extends PageResult<ActiveUserRow> {
  inactiveAfterDays: number | null;
}

/** F-7 (SRS-G-50, SRS-22-11): one payroll unit's Maker / Checker readiness. */
export interface UnitReadiness {
  locationId: number;
  code: string;
  label: string;
  type: string;
  readiness: 'READY' | 'COVERED_FROM_ABOVE' | 'NOT_READY';
  makers: string[];
  checkers: string[];
  ownStaff: number;
  meetsMinimum: boolean | null;
}

export interface ReadinessReport {
  doMinimumStaff: number | null;
  units: UnitReadiness[];
}

/** OD-130: one row of the user's offence record ({@code security_event}). */
export interface OffenceRecord {
  type: string;
  target: string | null;
  detail: string | null;
  sourceIp: string | null;
  occurredAt: string;
}

/** A-01 account actions. */
export type UserCommand =
  | { kind: 'status'; userId: number; body: { status: string; reason?: string; note?: string } }
  | { kind: 'unlock'; userId: number; reason: string }
  | { kind: 'password'; userId: number; temporaryPassword: string }
  | { kind: 'terminate'; userId: number; reason: string };

/** A-02 role. */
export interface Role {
  id: number;
  code: string;
  name: string;
  description: string | null;
  status: string;
  approver: boolean;
  /** FUNCTIONAL, MODULE or READ_ONLY (target DDL v4.0 role_type). */
  roleType: string | null;
  /** The functional role a MODULE role acts as (base_role_code); null for READ_ONLY. */
  baseRole: string | null;
  protectedRole: boolean;
}

/** A-02 permission: one screen action. */
export interface Permission {
  id: number;
  screenCode: string;
  actionCode: string;
  name: string;
}

/** A-02 role × permission matrix. */
export interface Matrix {
  roles: Role[];
  permissions: Permission[];
  grants: { roleId: number; permissionId: number }[];
}

export interface NewRole {
  code: string;
  name: string;
  description: string | null;
  approver: boolean;
  roleType: string;
  baseRole: string | null;
  reason: string;
}

/** An A-02 change sent for approval (nothing changes until a Payroll Checker approves it). */
export interface Proposal {
  approvalId: number;
  status: string;
}

/** A-03 access group: role ∩ scope. */
export interface Group {
  id: number;
  code: string;
  name: string;
  roleCode: string | null;
  scopeType: string;
  consolidated: boolean;
  /** FULL, READ_ONLY or REPORTS_ONLY (target DDL v4.0 access_mode). */
  accessMode: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  approvalStatus: string;
  scopes: { type: string; id: number; code: string | null; label: string | null }[];
  /** False: outside this administrator's reach, A-04 does not offer it (OD-130). */
  assignable?: boolean;
}

export type ScopeInput = { zoneId: number } | { locationId: number };

export interface NewGroup {
  code: string;
  name: string;
  roleId: number;
  scopeType: string;
  consolidated: boolean;
  accessMode: string;
  scopes: ScopeInput[];
  reason: string;
}

/** Organisation lookups for the A-03 / A-04 forms. */
export interface OrgView {
  zones: { id: number; code: string; name: string }[];
  locations: { id: number; code: string; name: string; type: string; label: string }[];
}

/** A-04 assignment of a user to an access group. */
export interface Assignment {
  id: number;
  userId: number;
  loginId: string | null;
  groupCode: string | null;
  roleCode: string | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  assignedBy: string;
  approvalStatus: string;
  revokedAt: string | null;
  revokeReason: string | null;
  current: boolean;
}

export interface NewAssignment {
  userId: number;
  groupId: number;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  reason: string;
}

const withOtp = <T extends object>(body: T, otp?: OtpProof) => ({ ...body, ...(otp ? { otp } : {}) });

/** The only place the iam module talks to /api/v1/admin (A-01 … A-04) and its lookups. */
export const adminApi = {
  /** One page of A-01 users (server-side paging, SRS-NF-3: only the shown rows are fetched). */
  users: (q: string, status: string, page: number, size: number) =>
    api<PageResult<UserRow>>(
      `/v1/admin/users?page=${page}&size=${size}${q ? `&q=${encodeURIComponent(q)}` : ''}${status ? `&status=${encodeURIComponent(status)}` : ''}`,
    ),
  /** SRS-G-49: suspends a group of accounts with one reason (all in scope, or none). */
  suspend: (userIds: number[], reason: string) =>
    api<SuspendOutcome>('/v1/admin/users/suspend', { method: 'POST', body: { userIds, reason } }),
  /** F-3 (SRS-G-50): one page of the Active User Report. */
  activeUsers: (q: string, inactiveOnly: boolean, page: number, size: number) =>
    api<ActiveUserReport>(
      `/v1/admin/active-users?page=${page}&size=${size}&inactiveOnly=${inactiveOnly}${q ? `&q=${encodeURIComponent(q)}` : ''}`,
    ),
  /** F-3 as CSV (A-01 EXPORT): a link the browser downloads with the session cookie. */
  activeUsersExportUrl: (q: string, inactiveOnly: boolean) =>
    `${API_BASE}/v1/admin/active-users/export?inactiveOnly=${inactiveOnly}${q ? `&q=${encodeURIComponent(q)}` : ''}`,
  /** F-7 (SRS-G-50, SRS-22-11): readiness of every payroll unit in scope. */
  readiness: () => api<ReadinessReport>('/v1/admin/readiness'),
  /** SRS-G-50: the user's latest sign-ins. */
  offences: (userId: number, page: number, size: number) =>
    api<PageResult<OffenceRecord>>(`/v1/admin/users/${userId}/offences?page=${page}&size=${size}`),
  logins: (userId: number, page: number, size: number) =>
    api<PageResult<LoginRecord>>(`/v1/admin/users/${userId}/logins?page=${page}&size=${size}`),
  createUser: (user: NewUser) => api('/v1/admin/users', { method: 'POST', body: user }),
  userCommand: (command: UserCommand) => {
    switch (command.kind) {
      case 'status':
        return api(`/v1/admin/users/${command.userId}/status`, { method: 'POST', body: command.body });
      case 'unlock':
        return api(`/v1/admin/users/${command.userId}/unlock`, {
          method: 'POST',
          body: { reason: command.reason },
        });
      case 'password':
        return api(`/v1/admin/users/${command.userId}/password`, {
          method: 'POST',
          body: { temporaryPassword: command.temporaryPassword },
        });
      case 'terminate':
        return api<{ sessions: number }>(`/v1/admin/users/${command.userId}/terminate-session`, {
          method: 'POST',
          body: { reason: command.reason },
        });
    }
  },
  matrix: () => api<Matrix>('/v1/admin/roles'),
  /** A-02 changes are maker-checker with an OTP (SRS-22-8, SRS-22-23): 428 first, then 202. */
  createRole: (role: NewRole, otp?: OtpProof) =>
    api<Proposal>('/v1/admin/roles', { method: 'POST', body: withOtp(role, otp) }),
  setGrant: (roleId: number, permissionId: number, granted: boolean, reason: string, otp?: OtpProof) =>
    api<Proposal>('/v1/admin/roles/grants', {
      method: 'PUT',
      body: withOtp({ roleId, permissionId, granted, reason }, otp),
    }),
  groups: () => api<Group[]>('/v1/admin/groups'),
  createGroup: (group: NewGroup, otp?: OtpProof) =>
    api<Group>('/v1/admin/groups', { method: 'POST', body: withOtp(group, otp) }),
  endGroup: (groupId: number, reason: string) =>
    api(`/v1/admin/groups/${groupId}/end`, { method: 'POST', body: { reason } }),
  org: () => api<OrgView>('/v1/admin/org'),
  assignments: (page: number, size: number) =>
    api<PageResult<Assignment>>(`/v1/admin/assignments?page=${page}&size=${size}`),
  proposeAssignment: (assignment: NewAssignment, otp?: OtpProof) =>
    api('/v1/admin/assignments', { method: 'POST', body: withOtp(assignment, otp) }),
  revokeAssignment: (assignmentId: number, reason: string) =>
    api(`/v1/admin/assignments/${assignmentId}/revoke`, { method: 'POST', body: { reason } }),
};
