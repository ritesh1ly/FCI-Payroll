package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.OtpChallenge;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Mutation gaps of {@link OtpSecondFactor}: the workflow's OTP step returns the challenge id of the
 * OTP service and refuses an unknown user as AUTHENTICATION_REQUIRED (SRS-G-21).
 */
class OtpSecondFactorMutationGapsTest {

    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final OtpService otps = mock(OtpService.class);
    private final OtpSecondFactor factor = new OtpSecondFactor(users, otps);
    private final AppUser user = mock(AppUser.class);
    private final OtpGatedAction action = OtpGatedAction.CYCLE_FREEZE_LOCK;

    private static OtpChallenge challenge(long id) {
        OtpChallenge c = mock(OtpChallenge.class);
        when(c.getId()).thenReturn(id);
        return c;
    }

    @Test
    void SRS_G_21_issueReturnsTheIdOfTheIssuedChallengeForTheUser() {
        when(users.findById(7L)).thenReturn(Optional.of(user));
        OtpChallenge ch501 = challenge(501L);
        when(otps.issue(user, action, "pay_cycle", "2026-11")).thenReturn(ch501);

        assertThat(factor.issue(7L, action, "pay_cycle", "2026-11")).isEqualTo(501L);
    }

    @Test
    void SRS_G_21_verifyReturnsTheIdOfTheVerifiedChallenge() {
        when(users.findById(7L)).thenReturn(Optional.of(user));
        OtpChallenge ch502 = challenge(502L);
        when(otps.verify(user, action, 501L, "123456")).thenReturn(ch502);
        OtpChallenge ch503 = challenge(503L);
        when(otps.verify(user, action, 501L, "123456", "pay_cycle", "2026-11")).thenReturn(ch503);

        assertThat(factor.verify(7L, action, 501L, "123456")).isEqualTo(502L);
        assertThat(factor.verify(7L, action, 501L, "123456", "pay_cycle", "2026-11"))
                .isEqualTo(503L);
    }

    @Test
    void SRS_G_21_requiredFollowsTheOtpServiceBothWays() {
        when(otps.verificationRequired()).thenReturn(true);
        assertThat(factor.required()).isTrue();
        when(otps.verificationRequired()).thenReturn(false);
        assertThat(factor.required()).isFalse();
    }

    @Test
    void SRS_G_21_confirmWithoutCodeReturnsTheRecordedChallengeId() {
        when(users.findById(7L)).thenReturn(Optional.of(user));
        OtpChallenge ch504 = challenge(504L);
        when(otps.confirmWithoutCode(user, action, "pay_cycle", "2026-11")).thenReturn(ch504);

        assertThat(factor.confirmWithoutCode(7L, action, "pay_cycle", "2026-11")).isEqualTo(504L);
    }

    @Test
    void SRS_G_21_anUnknownUserIsAuthenticationRequiredOnEveryOperation() {
        when(users.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> factor.issue(99L, action, "e", "k"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(CommonErrorCode.AUTHENTICATION_REQUIRED));
        assertThatThrownBy(() -> factor.verify(99L, action, 1L, "123456"))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> factor.verify(99L, action, 1L, "123456", "e", "k"))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> factor.confirmWithoutCode(99L, action, "e", "k"))
                .isInstanceOf(FciException.class);
    }
}
