'use client';

import { PERSONAL_DATA } from '@/shared/api';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useGoverned } from '@/modules/workflow';
import {
  adminApi,
  type NewAssignment,
  type NewGroup,
  type NewRole,
  type NewUser,
  type UserCommand,
} from '../api/adminApi';

/** A-01 … A-04 use cases: cached lookups and the changes that refresh them. */
export const adminKeys = {
  users: ['admin-users'] as const,
  userList: (q: string, status: string) => ['admin-users', q, status] as const,
  matrix: ['matrix'] as const,
  groups: ['groups'] as const,
  org: ['org'] as const,
  assignments: ['assignments'] as const,
};

function useInvalidate(key: readonly unknown[]) {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: key });
}

/** All users matching a search (first 100): the pickers of A-04 (the A-01 list pages itself). */
/**
 * The users matching {@code q} for a picker (A-04): the first 20 matches from the server, never the
 * whole register (SRS-NF-3); the user types more of the login or name to narrow them.
 */
export function useUsers(q: string, status: string, enabled = true) {
  return useQuery({
    ...PERSONAL_DATA,
    queryKey: adminKeys.userList(q, status),
    queryFn: () => adminApi.users(q, status, 0, 20),
    enabled,
  });
}

/** One A-01 page for the server-side pager (personal data: never cached, see ServerPagination). */
export function fetchUserPage(q: string, status: string, page: number, size: number) {
  return adminApi.users(q, status, page, size);
}

/** F-3: one page of the Active User Report for the server-side pager. */
export function fetchActiveUserPage(q: string, inactiveOnly: boolean, page: number, size: number) {
  return adminApi.activeUsers(q, inactiveOnly, page, size);
}

/** F-3 as CSV. */
export function activeUsersExportUrl(q: string, inactiveOnly: boolean) {
  return adminApi.activeUsersExportUrl(q, inactiveOnly);
}

/** F-7: the readiness of every payroll unit in scope (personal data: logins of makers / checkers). */
export function useReadiness(enabled: boolean) {
  return useQuery({
    ...PERSONAL_DATA,
    queryKey: ['admin-users', 'readiness'],
    queryFn: adminApi.readiness,
    enabled,
  });
}

/** SRS-G-49: suspends a group of accounts; the user list refreshes. */
export function useSuspendUsers() {
  const refresh = useInvalidate(adminKeys.users);
  return useMutation({
    mutationFn: (input: { userIds: number[]; reason: string }) =>
      adminApi.suspend(input.userIds, input.reason),
    onSuccess: refresh,
  });
}

/** SRS-G-50: the login record of one user, a page at a time (personal data: not kept in the cache). */
/** OD-130: the A-01 offence record of a user. */
export const offencePages = {
  key: (userId: number) => ['admin-users', 'offences', userId] as const,
  of: (userId: number) => (page: number, size: number) => adminApi.offences(userId, page, size),
};

export const loginPages = {
  key: (userId: number) => ['admin-users', 'logins', userId] as const,
  of: (userId: number) => (page: number, size: number) => adminApi.logins(userId, page, size),
};

export function useCreateUser() {
  const refresh = useInvalidate(adminKeys.users);
  return useMutation({ mutationFn: (user: NewUser) => adminApi.createUser(user), onSuccess: refresh });
}

/** Suspend, deactivate, reinstate, unlock, temporary password. */
export function useUserCommand() {
  const refresh = useInvalidate(adminKeys.users);
  return useMutation({
    mutationFn: (command: UserCommand) => adminApi.userCommand(command),
    onSuccess: refresh,
  });
}

export function useRoleMatrix(enabled = true) {
  return useQuery({ queryKey: adminKeys.matrix, queryFn: adminApi.matrix, enabled });
}

export function useCreateRole() {
  const refresh = useInvalidate(adminKeys.matrix);
  const run = useGoverned();
  return useMutation({
    mutationFn: (role: NewRole) => run((otp) => adminApi.createRole(role, otp)),
    onSuccess: refresh,
  });
}

export function useSetGrant() {
  const refresh = useInvalidate(adminKeys.matrix);
  const run = useGoverned();
  return useMutation({
    mutationFn: (input: { roleId: number; permissionId: number; granted: boolean; reason: string }) =>
      run((otp) => adminApi.setGrant(input.roleId, input.permissionId, input.granted, input.reason, otp)),
    onSuccess: refresh,
  });
}

export function useGroups(enabled = true) {
  return useQuery({ queryKey: adminKeys.groups, queryFn: adminApi.groups, enabled });
}

/** A new group is maker-checker at Headquarters with an OTP. */
export function useCreateGroup() {
  const refresh = useInvalidate(adminKeys.groups);
  const run = useGoverned();
  return useMutation({
    mutationFn: (group: NewGroup) => run((otp) => adminApi.createGroup(group, otp)),
    onSuccess: refresh,
  });
}

export function useEndGroup() {
  const refresh = useInvalidate(adminKeys.groups);
  return useMutation({
    mutationFn: (input: { groupId: number; reason: string }) =>
      adminApi.endGroup(input.groupId, input.reason),
    onSuccess: refresh,
  });
}

export function useOrg() {
  return useQuery({ queryKey: adminKeys.org, queryFn: adminApi.org });
}

/** A-04: one page of the assignments (personal data: never cached, see ServerPagination). */
export const assignmentPages = { list: adminApi.assignments };

/** Inert until a Payroll Checker of the group's location approves it with an OTP. */
export function useProposeAssignment() {
  const refresh = useInvalidate(adminKeys.assignments);
  const run = useGoverned();
  return useMutation({
    mutationFn: (assignment: NewAssignment) => run((otp) => adminApi.proposeAssignment(assignment, otp)),
    onSuccess: refresh,
  });
}

export function useRevokeAssignment() {
  const refresh = useInvalidate(adminKeys.assignments);
  return useMutation({
    mutationFn: (input: { assignmentId: number; reason: string }) =>
      adminApi.revokeAssignment(input.assignmentId, input.reason),
    onSuccess: refresh,
  });
}
