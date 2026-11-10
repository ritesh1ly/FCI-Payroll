package in.gov.fci.payroll.platform.approval;

import in.gov.fci.payroll.platform.logging.Sensitive;

/** An OTP presented with a governed action (the repeat call after a 428). */
public record OtpProof(Long challengeId, @Sensitive String code) {

    @Override
    public String toString() {
        return "OtpProof{challengeId=" + challengeId + "}";
    }
}
