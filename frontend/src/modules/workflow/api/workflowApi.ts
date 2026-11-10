import { type PageResult } from '@/shared/ui';
import { API_BASE, api } from '@/shared/api';

/** W-01 / W-02: one approval-queue item as the inbox and the submissions list show it. */
export interface Item {
  approvalId: number;
  moduleCode: string | null;
  entityName: string;
  entityKey: string;
  actionType: string;
  status: string;
  submittedBy: string;
  submittedAt: string;
  reason: string | null;
  assignedRoleCode: string | null;
  locationLabel: string | null;
  slaDueAt: string | null;
  /** Past the target turnaround of the workflow master (a business target, not a service level). */
  slaBreached: boolean;
  /** SRS-22-4: the value or impact the maker's change carries (approval_queue.impact_value), if any. */
  impactValue: number | null;
  /** Whole days since submission. */
  ageDays: number;
  /** The target turnaround of the workflow action in hours (target_tat_hours), null if none is set. */
  targetTatHours: number | null;
  /** The level the item waits at (2 = first checker) and how many it has (2 or 3). */
  level: number;
  levelCount: number;
  /** BR-APPR-007: nobody but the maker can decide it in scope; flagged to the HQ Administrator. */
  noApproverInScope: boolean;
  canDecide: boolean;
  /** SRS-G-13 / UC-07 A2 (US-GAP-21): may be approved in a bulk approval (route allows it, no OTP). */
  bulkApproval: boolean;
  denialKey: string | null;
  /** SRS-G-29 / SRS l.596: the Checker's reason for returning the item (the maker's text is reason). */
  returnReason?: string | null;
  /** Who decided the item and when (null while pending). */
  decidedBy?: string | null;
  decidedAt?: string | null;
}

/** SRS-G-13: what became of a bulk approval; a refused item carries its error code. */
export interface BulkOutcome {
  reference: string;
  approved: number[];
  refused: { approvalId: number; reason: string }[];
}

/** A checker's decision (the standard workflow endpoint, for every module). */
export type Decision = 'APPROVE' | 'REJECT' | 'RETURN';

export interface OtpProof {
  challengeId: number;
  code: string;
}

/** A governed wireframe button (screen_action_inventory) and whether this session holds it. */
export interface ActionView {
  screenCode: string;
  actionCode: string;
  labelKey: string;
  allowed: boolean;
  disabledReasonKey: string | null;
}

/** SRS-G-31 "View order": one document of an item's record. */
export interface ItemDocument {
  id: number;
  fileName: string;
  documentTypeName: string | null;
  referenceNumber: string | null;
  orderDate: string | null;
  issuingAuthority: string | null;
  uploadedBy: string;
  uploadedAt: string;
}

/** W-01 as drawn (OD-121): the four tiles over the whole scope and the modules of the open items. */
export interface InboxSummary {
  pending: number;
  pastTarget: number;
  approvedThisCycle: number;
  returned: number;
  modules: string[];
}

/** The only place the workflow module talks to /api/v1/inbox, /submissions and /approvals. */
export const workflowApi = {
  inbox: (page: number, size: number) => api<PageResult<Item>>(`/v1/inbox?page=${page}&size=${size}`),
  inboxSummary: () => api<InboxSummary>('/v1/inbox/summary'),
  inboxExportUrl: () => `${API_BASE}/v1/inbox/export`,
  submissions: (page: number, size: number) =>
    api<PageResult<Item>>(`/v1/submissions?page=${page}&size=${size}`),
  decide: (approvalId: number, decision: Decision, reason: string, otp?: OtpProof) =>
    api<{ status: string }>(`/v1/approvals/${approvalId}/decision`, {
      method: 'POST',
      body: { decision, reason: reason || null, ...(otp ? { otp } : {}) },
    }),
  bulkApprove: (approvalIds: number[], reason: string) =>
    api<BulkOutcome>('/v1/approvals/bulk-approvals', {
      method: 'POST',
      body: { approvalIds, reason: reason || null },
    }),
  documents: (approvalId: number) => api<ItemDocument[]>(`/v1/approvals/${approvalId}/documents`),
  documentUrl: (approvalId: number, fileId: number) =>
    `${API_BASE}/v1/approvals/${approvalId}/documents/${fileId}`,
  myActions: () => api<ActionView[]>('/v1/me/actions'),
  /** A governed button: recorded at once, or SUBMITTED for checker approval. */
  performAction: (screen: string, action: string, reason: string | null, otp?: OtpProof) =>
    api<{ outcome: string; approvalId: number | null }>('/v1/actions', {
      method: 'POST',
      body: { screen, action, reason, ...(otp ? { otp } : {}) },
    }),
};
