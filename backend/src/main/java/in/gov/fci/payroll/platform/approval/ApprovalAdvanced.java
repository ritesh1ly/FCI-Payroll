package in.gov.fci.payroll.platform.approval;

import java.time.Instant;

/**
 * A checker approved a pending item at a level below its last; it moves on to the next level.
 * Published by the workflow module inside the decision's transaction, for the owners whose rows
 * record each approval tier themselves (I-22 opening-balance correction: the AGM (CPF) and the OTP
 * of tier 2, DDL {@code chk_ob_corr_tier2}). The final decision is {@link ApprovalDecided}.
 *
 * @param levelNo the level just approved
 * @param otpChallengeId the approver's OTP challenge when the level is OTP-gated, else null
 */
public record ApprovalAdvanced(
        Long approvalId,
        String moduleCode,
        String entityName,
        String entityKey,
        int levelNo,
        String approvedBy,
        Long otpChallengeId,
        Instant decidedAt) {

    public boolean concerns(String entity) {
        return entity.equals(entityName);
    }
}
