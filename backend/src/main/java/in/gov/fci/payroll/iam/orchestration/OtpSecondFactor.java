package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import in.gov.fci.payroll.workflow.api.SecondFactor;
import org.springframework.stereotype.Component;

/** The workflow's OTP step, served by the iam module's {@link OtpService}. */
@Component
public class OtpSecondFactor implements SecondFactor {

    private final AccessRepositories.Users users;
    private final OtpService otps;

    public OtpSecondFactor(AccessRepositories.Users users, OtpService otps) {
        this.users = users;
        this.otps = otps;
    }

    @Override
    public Long issue(Long userId, OtpGatedAction action, String entityName, String entityKey) {
        return otps.issue(user(userId), action, entityName, entityKey).getId();
    }

    @Override
    public Long verify(Long userId, OtpGatedAction action, Long challengeId, String code) {
        return otps.verify(user(userId), action, challengeId, code).getId();
    }

    @Override
    public Long verify(
            Long userId,
            OtpGatedAction action,
            Long challengeId,
            String code,
            String entityName,
            String entityKey) {
        return otps.verify(user(userId), action, challengeId, code, entityName, entityKey).getId();
    }

    @Override
    public boolean required() {
        return otps.verificationRequired();
    }

    @Override
    public Long confirmWithoutCode(
            Long userId, OtpGatedAction action, String entityName, String entityKey) {
        return otps.confirmWithoutCode(user(userId), action, entityName, entityKey).getId();
    }

    private AppUser user(Long userId) {
        return users.findById(userId)
                .orElseThrow(() -> new FciException(CommonErrorCode.AUTHENTICATION_REQUIRED));
    }
}
