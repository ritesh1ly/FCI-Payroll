package in.gov.fci.payroll.workflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.security.OtpGatedAction;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SRS-G-21 / SRS-G-48: an OTP confirms one transaction. A second factor that does not bind a code
 * to an item verifies the code of the action alone; the item-bound overload falls back to it, so
 * every implementation stays correct, and a refusal of the plain check is the refusal of both.
 */
class SecondFactorTest {

    private final List<String> verified = new ArrayList<>();

    private final SecondFactor plain =
            new SecondFactor() {
                @Override
                public Long issue(
                        Long userId, OtpGatedAction action, String entityName, String entityKey) {
                    return 1L;
                }

                @Override
                public Long verify(
                        Long userId, OtpGatedAction action, Long challengeId, String code) {
                    verified.add(userId + "/" + action + "/" + challengeId + "/" + code);
                    if ("000000".equals(code)) {
                        throw new IllegalStateException("INVALID_OTP");
                    }
                    return challengeId;
                }

                @Override
                public boolean required() {
                    return true;
                }

                @Override
                public Long confirmWithoutCode(
                        Long userId, OtpGatedAction action, String entityName, String entityKey) {
                    return 2L;
                }
            };

    @Test
    void SRS_G_21_theItemBoundVerifyFallsBackToThePlainVerifyForTheSameChallenge() {
        Long id =
                plain.verify(
                        7L,
                        OtpGatedAction.DISBURSEMENT_RELEASE,
                        501L,
                        "482913",
                        "off_cycle_payment",
                        "OC-1");
        assertThat(id).isEqualTo(501L);
        assertThat(verified).containsExactly("7/DISBURSEMENT_RELEASE/501/482913");
    }

    @Test
    void SRS_G_21_aRefusedCodeIsRefusedThroughTheItemBoundOverloadToo() {
        assertThatThrownBy(
                        () ->
                                plain.verify(
                                        7L,
                                        OtpGatedAction.FNF_APPROVAL,
                                        502L,
                                        "000000",
                                        "fnf_settlement",
                                        "FNF-9"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("INVALID_OTP");
        assertThat(verified).hasSize(1);
    }

    @Test
    void SRS_G_21_theItemNameAndKeyAreNotNeededByTheFallbackAndMayBeNull() {
        assertThat(plain.verify(7L, OtpGatedAction.UNDO_TRANSACTION, 503L, "111111", null, null))
                .isEqualTo(503L);
    }
}
