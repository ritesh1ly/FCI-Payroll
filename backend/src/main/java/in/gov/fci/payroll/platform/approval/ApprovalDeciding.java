package in.gov.fci.payroll.platform.approval;

/**
 * A checker is about to give the final approval of a pending item, before any OTP is asked or
 * verified (SRS-G-21). Published by the workflow module inside the decision's transaction, for the
 * owners whose approval has business preconditions: a listener that throws refuses the approval
 * with its own error, the item stays pending and the Checker's OTP is neither issued nor consumed
 * (Wave A review: a refused settlement approval no longer spends the code). The owner still
 * re-checks in its {@link ApprovalDecided} listener, which applies the effect.
 *
 * @param payload the maker's proposed change (JSON), or null
 * @param checkerLoginId who is deciding
 */
public record ApprovalDeciding(
        Long approvalId,
        String moduleCode,
        String entityName,
        String entityKey,
        String payload,
        String checkerLoginId) {

    public boolean concerns(String entity) {
        return entity.equals(entityName);
    }
}
