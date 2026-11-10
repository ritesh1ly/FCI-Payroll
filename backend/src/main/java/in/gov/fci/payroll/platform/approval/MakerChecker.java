package in.gov.fci.payroll.platform.approval;

import in.gov.fci.payroll.platform.security.AccessContext;

/**
 * Maker-checker: submits a change to the approval queue, routed to the checker of its approval
 * workflow action (implemented by the workflow module). The final decision comes back as an {@link
 * ApprovalDecided} event to the module that owns the entity.
 */
public interface MakerChecker {

    /**
     * Submits {@code submission} as {@code context}'s change, in the caller's transaction. Checks
     * the maker's permission in scope, the rule, a pending duplicate and the reason; a rule with
     * the OTP flag needs {@code otp} (the first call answers 428 with a challenge id).
     */
    SubmittedApproval submit(AccessContext context, Submission submission, OtpProof otp);
}
