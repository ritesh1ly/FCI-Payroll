import { type PageResult } from '@/shared/ui';
import { API_BASE, api } from '@/shared/api';

/** One level of a route: 1 MAKER, 2 CHECKER, 3 APPROVER. */
export interface RouteLevel {
  levelNo: number;
  name?: string;
  roleCode: string;
  officeLevel: string;
  otpAtLevel: boolean;
}

/** One version of an approval action (A-08). */
export interface RouteVersion {
  id: number;
  versionNo: number;
  status: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  actionName: string;
  moduleCode: string;
  screenCode: string | null;
  levelCount: number;
  otpAction: string | null;
  attachmentRequired: boolean;
  /** SRS-G-13 / UC-07 A2: the items may be approved together on W-01. */
  bulkApprovalAllowed: boolean;
  targetTatHours: number | null;
  approvalId: number | null;
  levels: RouteLevel[];
}

/** An approval action at one office level: in force, pending, history. */
export interface RouteAction {
  actionCode: string;
  officeLevel: string;
  current: RouteVersion | null;
  pending: RouteVersion | null;
  history: RouteVersion[];
}

/** A proposed new action or new version. */
export interface ProposedRoute {
  actionCode: string;
  actionName: string;
  moduleCode: string;
  screenCode: string | null;
  officeLevel: string;
  levelCount: number;
  otpAction: string | null;
  attachmentRequired: boolean;
  /** SRS-G-13 / UC-07 A2: the items may be approved together on W-01. */
  bulkApprovalAllowed: boolean;
  targetTatHours: number | null;
  effectiveFrom: string;
  levels: RouteLevel[];
  reason: string;
}

/** An item flagged to the HQ Administrator: nobody but its maker holds the level's role (SRS-G-28). */
export interface UnstaffedItem {
  approvalId: number;
  moduleCode: string | null;
  entityName: string;
  entityKey: string;
  submittedBy: string;
  submittedAt: string;
  waitingForRole: string;
  office: string | null;
  level: number;
  levelCount: number;
}

/** One screen of the X-01 Screen-Level Access Matrix: role code -> V, E or A (no entry: no access). */
export interface ScreenAccess {
  screenCode: string;
  access: Record<string, string>;
}

/** One access a user holds today: group, role and the offices it reaches ("ALL"). */
export interface UserAccess {
  groupCode: string;
  roleCode: string;
  unit: string | null;
}

/** One row of the X-01 Active User Report (SRS-G-50). */
export interface ActiveUser {
  userId: number;
  loginId: string;
  status: string;
  lastLoginAt: string | null;
  /** null while the threshold is not configured on A-07. */
  inactive: boolean | null;
  access: UserAccess[];
}

/** One page of the report; inactiveAfterDays is null while not configured. */
export interface ActiveUserPage extends PageResult<ActiveUser> {
  inactiveAfterDays: number | null;
}

/** The only place the workflow module talks to /api/v1/admin/workflow-actions and the reads of X-01. */
export const workflowMasterApi = {
  actions: (page: number, size: number) =>
    api<PageResult<RouteAction>>(`/v1/admin/workflow-actions?page=${page}&size=${size}`),
  propose: (route: ProposedRoute) =>
    api<RouteVersion>('/v1/admin/workflow-actions', { method: 'POST', body: route }),
  unstaffed: (page: number, size: number) =>
    api<PageResult<UnstaffedItem>>(`/v1/admin/workflow-actions/unstaffed?page=${page}&size=${size}`),
  /** X-01: one page of the Screen-Level Access Matrix. */
  accessMatrix: (page: number, size: number) =>
    api<PageResult<ScreenAccess>>(`/v1/admin/access-matrix?page=${page}&size=${size}`),
  /** X-01 (SRS-G-50): one page of the Active User Report, in the session's scope. */
  activeUsers: (page: number, size: number) =>
    api<ActiveUserPage>(`/v1/admin/active-users?page=${page}&size=${size}&inactiveOnly=false`),
  /** The same report as a file (A-01 EXPORT). */
  activeUsersExportUrl: () => `${API_BASE}/v1/admin/active-users/export?inactiveOnly=false`,
};
