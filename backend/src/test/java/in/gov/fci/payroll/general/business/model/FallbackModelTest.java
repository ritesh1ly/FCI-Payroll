package in.gov.fci.payroll.general.business.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * X-07 HRMS fallback (SRS-G-46, SRS-G-47, SRS-EH-11): an enablement takes uploads while open or
 * uploaded, is approved once and is withdrawn only while it takes uploads; each uploaded record is
 * kept as read, cut to its columns, accepted or rejected, and only accepted records are reconciled.
 */
class FallbackModelTest {

    private static final Instant NOW = Instant.parse("2026-10-21T05:00:00Z");

    private static FallbackEnablement open() {
        return FallbackEnablement.open(
                "FBE-1",
                FallbackEnablement.Type.ATTENDANCE_LEAVE,
                "202610",
                "HRMS down",
                "a".repeat(60),
                NOW,
                4L,
                NOW);
    }

    @Test
    void SRS_G_47_SRS_DM_18_anOpenEnablementTakesUploadsUntilApproved() {
        FallbackEnablement e = open();
        assertThat(e.getStatus()).isEqualTo(FallbackEnablement.Status.OPEN);
        assertThat(e.getEnabledBy()).hasSize(50);
        assertThat(e.getReference()).isEqualTo("FBE-1");
        assertThat(e.getType()).isEqualTo(FallbackEnablement.Type.ATTENDANCE_LEAVE);
        assertThat(e.getPayrollYymm()).isEqualTo("202610");
        assertThat(e.getReason()).isEqualTo("HRMS down");
        assertThat(e.getEnabledAt()).isEqualTo(NOW);
        assertThat(e.getApprovalId()).isEqualTo(4L);
        assertThat(e.acceptsUploads()).isTrue();
        e.uploaded();
        assertThat(e.getStatus()).isEqualTo(FallbackEnablement.Status.UPLOADED);
        assertThat(e.acceptsUploads()).isTrue();
        e.uploaded();
        assertThat(e.getStatus()).as("idempotent").isEqualTo(FallbackEnablement.Status.UPLOADED);
        e.approved();
        assertThat(e.getStatus()).isEqualTo(FallbackEnablement.Status.APPROVED);
        assertThat(e.acceptsUploads()).isFalse();
        e.uploaded();
        e.approved();
        assertThat(e.getStatus()).as("no way back").isEqualTo(FallbackEnablement.Status.APPROVED);
    }

    @Test
    void SRS_G_47_anOpenEnablementIsApprovedDirectly() {
        FallbackEnablement e = open();
        e.approved();
        assertThat(e.getStatus()).isEqualTo(FallbackEnablement.Status.APPROVED);
    }

    @Test
    void SRS_INT_22_anEnablementNamesEachOfficeItCoversWithItsCycleIfAlreadyInitiated() {
        FallbackEnableScope initiated = FallbackEnableScope.of(4L, 300L, 77L);
        assertThat(initiated.getEnablementId()).isEqualTo(4L);
        assertThat(initiated.getOrgUnitId()).isEqualTo(300L);
        assertThat(initiated.getCycleInstanceId()).isEqualTo(77L);
        // An office whose cycle for the month is not yet initiated has no cycle instance.
        assertThat(FallbackEnableScope.of(4L, 301L, null).getCycleInstanceId()).isNull();
    }

    @Test
    void SRS_EH_11_onlyAnEnablementTakingUploadsIsWithdrawn() {
        FallbackEnablement e = open();
        e.withdraw("HRMS is back");
        assertThat(e.getStatus()).isEqualTo(FallbackEnablement.Status.WITHDRAWN);
        assertThat(e.getWithdrawnReason()).isEqualTo("HRMS is back");
        assertThat(e.acceptsUploads()).isFalse();
        assertThatThrownBy(() -> e.withdraw("again"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FBE-1");
        FallbackEnablement uploaded = open();
        uploaded.uploaded();
        uploaded.withdraw("late");
        assertThat(uploaded.getStatus()).isEqualTo(FallbackEnablement.Status.WITHDRAWN);
        FallbackEnablement approved = open();
        approved.approved();
        assertThatThrownBy(() -> approved.withdraw("x")).isInstanceOf(IllegalStateException.class);
    }

    private static FallbackRecord record(
            String emp, String leave, String order, String authority, String raw) {
        return new FallbackRecord(
                3,
                emp,
                "ATTENDANCE",
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31),
                new BigDecimal("30"),
                BigDecimal.ONE,
                BigDecimal.ZERO,
                leave,
                new BigDecimal("2"),
                LocalDate.of(2026, 10, 10),
                order,
                LocalDate.of(2026, 10, 9),
                authority,
                raw,
                null);
    }

    @Test
    void SRS_EH_11_anAcceptedRecordIsKeptAsReadAndAwaitsReconciliation() {
        FallbackUploadLine l =
                FallbackUploadLine.of(
                        9L, record("106958", "EL", "RO/1", "RO Delhi", "a,b"), 55L, null);
        assertThat(l.getUploadId()).isEqualTo(9L);
        assertThat(l.getLineNo()).isEqualTo(3);
        assertThat(l.getEmployeeId()).isEqualTo(55L);
        assertThat(l.getEmployeeNumberRaw()).isEqualTo("106958");
        assertThat(l.getRecordType()).isEqualTo("ATTENDANCE");
        assertThat(l.getPeriodFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(l.getPeriodTo()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(l.getPayDays()).isEqualByComparingTo("30");
        assertThat(l.getLwpDays()).isEqualByComparingTo("1");
        assertThat(l.getHalfPayDays()).isEqualByComparingTo("0");
        assertThat(l.getOtherLeaveCode()).isEqualTo("EL");
        assertThat(l.getOtherLeaveDays()).isEqualByComparingTo("2");
        assertThat(l.getEventEffectiveDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(l.getOrderNo()).isEqualTo("RO/1");
        assertThat(l.getOrderDate()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(l.getIssuingAuthority()).isEqualTo("RO Delhi");
        assertThat(l.getValidationStatus()).isEqualTo("ACCEPTED");
        assertThat(l.getRejectionReason()).isNull();
        assertThat(l.getReconStatus()).isEqualTo("PENDING");
    }

    @Test
    void SRS_EH_11_aRejectedRecordKeepsItsReasonAndIsNeverReconciled() {
        FallbackUploadLine l =
                FallbackUploadLine.of(
                        9L,
                        record(null, null, null, null, null),
                        null,
                        "EMPLOYEE_NOT_FOUND: " + "x".repeat(600));
        assertThat(l.getEmployeeId()).isNull();
        assertThat(l.getEmployeeNumberRaw()).isEmpty();
        assertThat(l.getOtherLeaveCode()).isNull();
        assertThat(l.getOrderNo()).isNull();
        assertThat(l.getIssuingAuthority()).isNull();
        assertThat(l.getValidationStatus()).isEqualTo("REJECTED");
        assertThat(l.getRejectionReason()).hasSize(500).startsWith("EMPLOYEE_NOT_FOUND");
        assertThat(l.getReconStatus()).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void SRS_EH_11_overlongValuesAreCutToTheirColumns() {
        FallbackUploadLine l =
                FallbackUploadLine.of(
                        1L,
                        record(
                                "9".repeat(40),
                                "L".repeat(30),
                                "O".repeat(80),
                                "I".repeat(250),
                                "R".repeat(5000)),
                        1L,
                        null);
        assertThat(l.getEmployeeNumberRaw()).hasSize(30);
        assertThat(l.getOtherLeaveCode()).hasSize(20);
        assertThat(l.getOrderNo()).hasSize(60);
        assertThat(l.getIssuingAuthority()).hasSize(200);
        assertThat(
                        (String)
                                org.springframework.test.util.ReflectionTestUtils.getField(
                                        l, "rawRecord"))
                .hasSize(4000);
    }
}
