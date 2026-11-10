package in.gov.fci.payroll.platform.approval;

/**
 * Closes the approval item of a record whose owner ended it while it still waited for a Checker
 * (implemented by the workflow module). Wave D review 9: an I-21 task a newer HRMS record
 * superseded, or a CPF withdrawal the member withdrew at tier 3, no longer stays in the Approvals
 * Inbox where an intermediate level could still approve it onward. The item becomes {@code
 * WITHDRAWN} (DDL {@code approval_queue.status}) and its inbox task {@code COMPLETED}, in the
 * caller's transaction and audited (R-AUD); nothing is deleted (R-DEL) and no {@link
 * ApprovalDecided} is published, because the owner has already recorded the outcome.
 */
public interface ApprovalWithdrawals {

    /**
     * Withdraws every item of the record ({@code entityName}, {@code entityKey}) still awaiting a
     * decision; an item already decided is left as it is.
     *
     * @param actor who ended the record (a user or the interface)
     * @param reason why, kept on the item and in the audit trail
     * @return how many items were withdrawn
     */
    int withdraw(String entityName, String entityKey, String actor, String reason);
}
