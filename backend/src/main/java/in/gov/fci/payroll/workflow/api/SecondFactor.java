package in.gov.fci.payroll.workflow.api;

import in.gov.fci.payroll.platform.security.OtpGatedAction;

/**
 * The acting user's OTP for an approval action or level with the OTP flag (implemented by the iam
 * module, which owns users, their contact details and the OTP challenges). The action is one of the
 * eighteen gated actions ({@code approval_workflow_action.otp_action_code}).
 */
public interface SecondFactor {

    /**
     * Sends a fresh code to {@code userId} for {@code action} on the entity; returns the challenge
     * id.
     */
    Long issue(Long userId, OtpGatedAction action, String entityName, String entityKey);

    /** Verifies {@code code} for the challenge; returns its id, or throws the precise 4xx. */
    Long verify(Long userId, OtpGatedAction action, Long challengeId, String code);

    /**
     * As {@link #verify(Long, OtpGatedAction, Long, String)} for the item the challenge was issued
     * for: a code of another item is refused (security review 2026-10-31: an OTP confirms one
     * transaction, not an action type).
     */
    default Long verify(
            Long userId,
            OtpGatedAction action,
            Long challengeId,
            String code,
            String entityName,
            String entityKey) {
        return verify(userId, action, challengeId, code);
    }

    /**
     * Whether a critical activity asks for an OTP ({@code fci.api.otp.required}: Y in UAT and
     * production, N on development and local machines).
     */
    boolean required();

    /**
     * When {@link #required()} is false: the challenge recorded as confirmed without a code;
     * returns its id, so the record that keeps its OTP still names one.
     */
    Long confirmWithoutCode(
            Long userId, OtpGatedAction action, String entityName, String entityKey);
}
