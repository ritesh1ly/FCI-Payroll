package in.gov.fci.payroll.platform.selfservice;

import java.util.Objects;

/**
 * What a module tells the eSeva Self-Service Submission Log (plan 14 phase 14a; SRS-17-3,
 * SRS-17-18, BR-ESEVA-006; DDL {@code ess_submission}). The module that owns a self-service
 * submission (the CPF withdrawal chain, the tax declarations, the HRMS Update Request ...) keeps
 * its own maker-checker and publishes these events in the transaction of the change; the eSeva
 * module records them, so the owning modules never depend on it. Published synchronously: a failure
 * to log rolls the submission back (all or nothing).
 */
public final class SelfServiceEvents {

    private SelfServiceEvents() {}

    /** DDL {@code ess_submission.submission_type}. */
    public enum Type {
        REIMBURSEMENT_CLAIM,
        LOAN_REQUEST,
        CPF_ADVANCE_REQUEST,
        CPF_WITHDRAWAL_REQUEST,
        CPF_FINAL_PAYMENT_REQUEST,
        TAX_DECLARATION,
        REGIME_CHOICE,
        VPF_SUBSCRIPTION,
        HRMS_UPDATE_REQUEST,
        PAYROLL_QUERY
    }

    /**
     * DDL {@code ess_submission.submission_status}: SUBMITTED (received), ROUTED (in the owning
     * module's maker-checker), ACTIONED (approved, applied or closed with an outcome), REJECTED
     * (rejected, returned for a new round, or withdrawn).
     */
    public enum Status {
        SUBMITTED,
        ROUTED,
        ACTIONED,
        REJECTED
    }

    /**
     * A member's submission from eSeva.
     *
     * @param entityName and {@code entityId}: the owning module's record (one log row per record; a
     *     record without an id, not stored, has none)
     * @param declarationId the {@code it_declaration} line of a TAX_DECLARATION / REGIME_CHOICE
     * @param vpfChangeRequestId the request of a VPF_SUBSCRIPTION
     * @param hrmsUpdateRequestId the request of an HRMS_UPDATE_REQUEST
     * @param value a short printable summary of what was asked (at most 1000 characters)
     * @param approvalId the approval the submission was routed to, if known at once
     */
    public record Submitted(
            long employeeId,
            Type type,
            String entityName,
            Long entityId,
            Long declarationId,
            Long vpfChangeRequestId,
            Long hrmsUpdateRequestId,
            String value,
            Long approvalId,
            String actor) {

        public Submitted {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(entityName, "entityName");
            Objects.requireNonNull(actor, "actor");
        }
    }

    /**
     * The owning module's record moved on; a record with no log row (raised by staff) is ignored.
     *
     * @param entityId the record's id; null (a record not yet stored) has no log row
     * @param approvalId the approval the record was routed to now (its decision then moves the row
     *     too), or null
     */
    public record StatusChanged(
            String entityName, Long entityId, Status status, Long approvalId, String actor) {

        public StatusChanged {
            Objects.requireNonNull(entityName, "entityName");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(actor, "actor");
        }

        /** A change with no new approval. */
        public StatusChanged(String entityName, Long entityId, Status status, String actor) {
            this(entityName, entityId, status, null, actor);
        }
    }
}
