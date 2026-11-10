package in.gov.fci.payroll.workflow.api;

import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.logging.Sensitive;
import jakarta.validation.constraints.Size;

/**
 * An OTP presented on the repeat call after a 428, in the request body of any maker-checker
 * endpoint (every module that submits to the approval queue accepts it).
 */
public record Otp(Long challengeId, @Size(max = 6) @Sensitive String code) {

    public OtpProof proof() {
        return new OtpProof(challengeId, code);
    }

    /** The proof for the approval service, or null when no OTP was sent. */
    public static OtpProof proof(Otp otp) {
        return otp == null ? null : otp.proof();
    }

    @Override
    public String toString() {
        return "Otp{challengeId=" + challengeId + "}";
    }
}
