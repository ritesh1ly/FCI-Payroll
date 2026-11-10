package in.gov.fci.payroll.iam.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Branch gaps of the IAM entities (SRS-SEC-14 sessions, SRS-G-48 OTP challenges, SRS-SEC-13
 * password history): values read back as the database may hold them.
 */
class IamModelBranchGapsTest {

    private static final Instant NOW = Instant.parse("2026-10-24T05:00:00Z");

    @Test
    void SRS_SEC_14_aSessionWithoutOrWithBlankGroupsActsInNoGroup() {
        AppSession none =
                AppSession.start(
                        "hash", 1L, "PAYROLL_MAKER", 10L, List.of(), null, null, null, NOW);
        assertThat(none.activeGroupIdList()).isEmpty();
        ReflectionTestUtils.setField(none, "activeGroupIds", "   ");
        assertThat(none.activeGroupIdList()).as("blank column").isEmpty();
        AppSession some =
                AppSession.start(
                        "hash", 1L, "PAYROLL_MAKER", 10L, List.of(5L, 3L), null, null, null, NOW);
        assertThat(some.activeGroupIdList()).containsExactly(3L, 5L);
    }

    @Test
    void SRS_G_48_aChallengeBuiltWithoutAnAttemptCountHasMadeNone() {
        assertThat(new OtpChallenge().getAttempts()).isZero();
    }

    @Test
    void SRS_SEC_13_aHistoryKeyIsEqualOnlyForTheSameUserAndInstant() {
        PasswordHistoryEntry.Key key = new PasswordHistoryEntry.Key(1L, NOW);
        assertThat(key).isEqualTo(new PasswordHistoryEntry.Key(1L, NOW));
        assertThat(key).hasSameHashCodeAs(new PasswordHistoryEntry.Key(1L, NOW));
        assertThat(key).isNotEqualTo(new PasswordHistoryEntry.Key(2L, NOW));
        assertThat(key).isNotEqualTo(new PasswordHistoryEntry.Key(1L, NOW.plusSeconds(1)));
        assertThat(key).isNotEqualTo("1|" + NOW);
    }
}
