package in.gov.fci.payroll.workflow.api;

import java.util.Optional;

/**
 * Who a pending change is about, for the rule "the decider is never the subject of the change"
 * (implemented by the modules that own such entities, e.g. iam for access assignments).
 */
public interface ApprovalSubjects {

    /** The user {@code approvalId} (on {@code entityName}) is about, if this module owns it. */
    Optional<Long> subjectUserId(String entityName, Long approvalId);
}
