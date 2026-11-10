package in.gov.fci.payroll.general.business.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.general.business.GeneralErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The X-07 upload lifecycle and the DDL rules it mirrors (chk_hrms_fb_upload_count, _otp, _sod,
 * _rsn).
 */
class FallbackUploadTest {

    private static final Instant NOW = Instant.parse("2026-02-27T05:30:00Z");

    private static FallbackUpload upload(int accepted, int rejected) {
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
                "FBU-1", e, 10L, null, "feb.csv", "ab", null, accepted, rejected, "maker", NOW);
    }

    @Test
    void chk_hrms_fb_upload_count_recordsAreAcceptedPlusRejected() {
        FallbackUpload u = upload(7, 3);
        assertThat(u.getRecordCount()).isEqualTo(10);
        assertThat(u.getStatus()).isEqualTo(FallbackUpload.Status.VALIDATED);
        assertThat(u.getReconStatus()).isEqualTo(FallbackUpload.ReconStatus.NOT_DUE);
    }

    @Test
    void chk_hrms_fb_upload_rsn_aDeclineIsAReturnOrARejectionAndAlwaysCarriesAReason() {
        FallbackUpload u = upload(2, 0);
        u.submit(5L);
        assertThatThrownBy(() -> u.decline(FallbackUpload.Status.APPROVED, "checker", "x", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        u.decline(FallbackUpload.Status.RETURNED, "checker", "  ", NOW);
        assertThat(u.getStatus()).isEqualTo(FallbackUpload.Status.RETURNED);
        assertThat(u.getDecisionReason()).isEqualTo("Returned without a stated reason");
        // A rejection without a reason also records that none was given; a stated one is kept.
        FallbackUpload none = upload(2, 0);
        none.submit(6L);
        none.decline(FallbackUpload.Status.REJECTED, "checker", null, NOW);
        assertThat(none.getDecisionReason()).isEqualTo("Returned without a stated reason");
        FallbackUpload stated = upload(2, 0);
        stated.submit(7L);
        stated.decline(FallbackUpload.Status.REJECTED, "checker", "Wrong month", NOW);
        assertThat(stated.getDecisionReason()).isEqualTo("Wrong month");
    }

    @Test
    void D_2_withoutFormatsEveryTypeIsUnconfigured() {
        assertThat(
                        new in.gov.fci.payroll.general.config.FallbackFormatProperties(null)
                                .format(FallbackEnablement.Type.ATTENDANCE_LEAVE)
                                .isConfigured())
                .isFalse();
        assertThat(
                        new in.gov.fci.payroll.general.config.FallbackFormatProperties(
                                        java.util.Map.of())
                                .format(FallbackEnablement.Type.SUSPENSION_ABSCONDING)
                                .isConfigured())
                .isFalse();
    }

    @Test
    void anUploadWithoutAnAcceptedRecordCannotBeSubmitted() {
        assertThatThrownBy(() -> upload(0, 4).submit(5L))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(GeneralErrorCode.FALLBACK_NOTHING_ACCEPTED));
    }

    @Test
    void chk_hrms_fb_upload_otp_and_sod_approvalNeedsTheCheckersOtpAndAnotherPerson() {
        FallbackUpload u = upload(2, 0);
        u.submit(5L);
        assertThatThrownBy(() -> u.approve("checker", null, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> u.approve("maker", 77L, NOW))
                .isInstanceOf(IllegalStateException.class);
        u.approve("checker", 77L, NOW);
        assertThat(u.getStatus()).isEqualTo(FallbackUpload.Status.APPROVED);
        assertThat(u.getOtpChallengeId()).isEqualTo(77L);
        assertThat(u.getReconStatus())
                .as("reconciliation with HRMS is now due")
                .isEqualTo(FallbackUpload.ReconStatus.PENDING);
    }

    @Test
    void chk_hrms_fb_upload_rsn_aReturnCarriesAReasonAndMayBeResubmitted() {
        FallbackUpload u = upload(2, 0);
        u.submit(5L);
        u.decline(FallbackUpload.Status.RETURNED, "checker", "Wrong month for two rows", NOW);
        assertThat(u.getDecisionReason()).isEqualTo("Wrong month for two rows");
        u.submit(6L);
        assertThat(u.getStatus()).isEqualTo(FallbackUpload.Status.PENDING_APPROVAL);
    }

    @Test
    void SRS_G_37_SRS_DM_17_onlyAnUnsubmittedUploadIsCancelledOrSupersededNeverAnApprovedOne() {
        FallbackUpload u = upload(1, 0);
        u.submit(5L);
        u.approve("checker", 77L, NOW);
        assertThatThrownBy(() -> u.cancel("x"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(GeneralErrorCode.FALLBACK_STATUS));
        assertThatThrownBy(u::supersede).isInstanceOf(FciException.class);
        FallbackUpload fresh = upload(1, 0);
        fresh.cancel("Wrong file");
        assertThat(fresh.getStatus()).isEqualTo(FallbackUpload.Status.CANCELLED);
    }
}
