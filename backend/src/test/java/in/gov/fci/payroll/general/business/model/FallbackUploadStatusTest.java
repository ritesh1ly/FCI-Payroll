package in.gov.fci.payroll.general.business.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** SRS-G-46: each X-07 step only from the status it follows. */
class FallbackUploadStatusTest {

    private static final Instant NOW = Instant.parse("2026-02-27T05:30:00Z");

    private static FallbackUpload validated() {
        FallbackEnablement e =
                FallbackEnablement.open(
                        "FBE-2026-001",
                        FallbackEnablement.Type.ATTENDANCE_LEAVE,
                        "202602",
                        "HRMS down at cut-off",
                        "sysadmin",
                        null,
                        1L,
                        NOW);
        ReflectionTestUtils.setField(e, "id", 9L);
        return FallbackUpload.validated(
                "FBU-1", e, 10L, null, "feb.csv", "ab", null, 2, 0, "maker", NOW);
    }

    @Test
    void SRS_G_46_aValidatedUploadIsNeitherApprovedNorDeclinedBeforeItIsSubmitted() {
        FallbackUpload u = validated();
        assertThatThrownBy(() -> u.approve("checker", 4L, NOW))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> u.decline(FallbackUpload.Status.REJECTED, "checker", "x", NOW))
                .isInstanceOf(RuntimeException.class);
        assertThat(u.getStatus()).isEqualTo(FallbackUpload.Status.VALIDATED);
    }

    @Test
    void SRS_G_46_aSubmittedUploadIsNotSubmittedAgain() {
        FallbackUpload u = validated();
        u.submit(5L);
        assertThatThrownBy(() -> u.submit(6L)).isInstanceOf(RuntimeException.class);
        assertThat(u.getStatus()).isEqualTo(FallbackUpload.Status.PENDING_APPROVAL);
    }
}
