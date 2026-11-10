package in.gov.fci.payroll.platform.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Approval status helpers, advance event and OTP proof (R-MC, R-OTP). */
class ApprovalStatusesMutationGapsTest {

    @Test
    void approvedKeepsOnlyTheItemsThatAreApproved() {
        ApprovalStatuses statuses = mock(ApprovalStatuses.class, CALLS_REAL_METHODS);
        when(statuses.of(List.of(1L, 2L, 3L, 4L)))
                .thenReturn(
                        Map.of(
                                1L, ApprovalStatus.APPROVED,
                                2L, ApprovalStatus.PENDING_APPROVAL,
                                3L, ApprovalStatus.REJECTED,
                                4L, ApprovalStatus.APPROVED));
        assertThat(statuses.approved(List.of(1L, 2L, 3L, 4L))).isEqualTo(Set.of(1L, 4L));
    }

    @Test
    void approvedIsEmptyWhenNothingIsApproved() {
        ApprovalStatuses statuses = mock(ApprovalStatuses.class, CALLS_REAL_METHODS);
        when(statuses.of(List.of(2L))).thenReturn(Map.of(2L, ApprovalStatus.WITHDRAWN));
        assertThat(statuses.approved(List.of(2L))).isEmpty();
    }

    @Test
    void anAdvanceConcernsOnlyItsOwnEntity() {
        ApprovalAdvanced advanced =
                new ApprovalAdvanced(
                        9L,
                        "LOAN",
                        "OPENING_BALANCE",
                        "OB-1",
                        1,
                        "checker1",
                        null,
                        Instant.parse("2026-11-05T05:00:00Z"));
        assertThat(advanced.concerns("OPENING_BALANCE")).isTrue();
        assertThat(advanced.concerns("OTHER")).isFalse();
    }

    @Test
    void anOtpProofPrintsTheChallengeButNeverTheCode() {
        assertThat(new OtpProof(42L, "123456")).hasToString("OtpProof{challengeId=42}");
    }
}
