package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.iam.business.model.OtpChallenge;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import in.gov.fci.payroll.platform.maintenance.Housekeeping;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Expires lapsed OTP challenges. */
@Component
public class OtpHousekeeping implements Housekeeping {

    private final IdentityRepositories.Otps otps;

    public OtpHousekeeping(IdentityRepositories.Otps otps) {
        this.otps = otps;
    }

    @Override
    public String name() {
        return "expiredOtps";
    }

    @Override
    public int run(Instant now) {
        int expired = 0;
        for (OtpChallenge open :
                otps.findByOutcomeAndIssuedAtBefore(OtpChallenge.Outcome.ISSUED, now)) {
            if (!now.isBefore(open.expiresAt())) {
                open.expire(now);
                otps.save(open);
                expired++;
            }
        }
        return expired;
    }
}
