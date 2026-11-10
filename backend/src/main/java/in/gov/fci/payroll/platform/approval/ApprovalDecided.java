package in.gov.fci.payroll.platform.approval;

import java.time.Instant;

/**
 * A checker decided an approval-queue item. Published by the workflow module inside the decision's
 * transaction; the module that owns {@code entityName} applies the effect in a synchronous
 * listener, so a failing effect rolls the decision back.
 *
 * @param outcome APPROVED, REJECTED or RETURNED
 * @param reason the deciding Checker's reason (required to return or reject), or null
 * @param payload the maker's proposed change (JSON), or null
 * @param checkerLoginId who decided
 * @param otpChallengeId the deciding checker's OTP challenge when the decision was OTP-gated
 *     (SRS-G-21), else null; owners whose rows record the OTP (e.g. {@code
 *     hrms_fallback_upload.otp_challenge_id}, {@code supplementary_payment.otp_challenge_id}) take
 *     it from here
 */
public record ApprovalDecided(
        Long approvalId,
        String moduleCode,
        String entityName,
        String entityKey,
        ApprovalStatus outcome,
        String reason,
        String payload,
        String checkerLoginId,
        Instant decidedAt,
        Long otpChallengeId) {

    /** A decision without an OTP (the form every non-OTP decision and older caller uses). */
    public ApprovalDecided(
            Long approvalId,
            String moduleCode,
            String entityName,
            String entityKey,
            ApprovalStatus outcome,
            String reason,
            String payload,
            String checkerLoginId,
            Instant decidedAt) {
        this(
                approvalId,
                moduleCode,
                entityName,
                entityKey,
                outcome,
                reason,
                payload,
                checkerLoginId,
                decidedAt,
                null);
    }

    public boolean approved() {
        return outcome == ApprovalStatus.APPROVED;
    }

    public boolean concerns(String entity) {
        return entity.equals(entityName);
    }
}
