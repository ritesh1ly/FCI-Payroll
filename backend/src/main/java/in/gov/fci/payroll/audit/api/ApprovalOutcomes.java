package in.gov.fci.payroll.audit.api;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * The approval behind audit rows (X-04 Status / Outcome, Maker and Checker, Authorisation; SRS-G-2,
 * FAP-13). {@code audit_event} keeps only the approval id: the approval state lives on {@code
 * approval_queue} (DDL: immutable data-change rows, mutable approval state). Implemented by the
 * workflow module.
 */
public interface ApprovalOutcomes {

    /** The outcomes of these approvals, by id; unknown ids are left out. */
    Map<Long, Outcome> outcomes(Collection<Long> approvalIds);

    /**
     * @param status {@code approval_queue.status}: DRAFT, PENDING_ATTACHMENT, PENDING_APPROVAL,
     *     APPROVED, REJECTED, RETURNED or WITHDRAWN
     * @param maker who submitted it
     * @param checker who took the final decision, or null while pending
     */
    record Outcome(
            Long approvalId,
            String status,
            String maker,
            String checker,
            Instant decidedAt,
            String moduleCode) {}
}
