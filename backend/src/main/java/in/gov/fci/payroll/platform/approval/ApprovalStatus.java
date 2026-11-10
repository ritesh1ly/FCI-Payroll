package in.gov.fci.payroll.platform.approval;

/** {@code approval_queue.status}. */
public enum ApprovalStatus {
    DRAFT,
    PENDING_ATTACHMENT,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    RETURNED,
    WITHDRAWN
}
