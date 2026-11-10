package in.gov.fci.payroll.allowance.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.allowance.business.ClaimRules;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** The claim and cafeteria states of plan 10 (D10-7, D10-8, D10-11; SRS-11-6, 11-11, 11-16). */
class AllowanceModelTest {

    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);
    private static final ClaimRules.Period APRIL = new ClaimRules.Period(FROM, FROM.plusDays(29));
    private static final BigDecimal CLAIMED = new BigDecimal("6000");
    private static final ClaimRules.Adjudication CAPPED =
            new ClaimRules.Adjudication(new BigDecimal("5000"), new BigDecimal("5000"), true);
    private static final ClaimRules.Adjudication FULL =
            new ClaimRules.Adjudication(new BigDecimal("8000"), CLAIMED, false);

    @Test
    void SRS_11_13_aLabourClaimIsEnteredSubmittedAndValidatedAtTheCap() {
        ReimbursementClaim c = ReimbursementClaim.labour(7L, "LABOUR_TA", APRIL, CLAIMED);
        assertThat(c.getMemberType()).isEqualTo("LABOUR");
        assertThat(c.getSourceCode()).isEqualTo("ENT");
        assertThat(c.getStatus()).isEqualTo("SUBMITTED");
        assertThat(c.getInvoiceStatus()).isEqualTo("NOT_RAISED");
        assertThat(c.isCapped()).isFalse();
        assertThat(c.period()).isEqualTo(APRIL);
        c.enteredWith(3L, CAPPED);
        assertThat(c.getStatus()).isEqualTo("SUBMITTED");
        assertThat(c.getCeilingNormId()).isEqualTo(3L);
        assertThat(c.getPayableAmount()).isEqualByComparingTo("5000");
        assertThat(c.isCapped()).isTrue();
        c.validated(3L, CAPPED);
        assertThat(c.getStatus()).isEqualTo("CAPPED");
        c.validated(4L, FULL);
        assertThat(c.getStatus()).isEqualTo("VALIDATED");
        assertThat(c.getEntitlementAmount()).isEqualByComparingTo("8000");
        assertThat(c.getCeilingNormId()).isEqualTo(4L);
        assertThat(c.isCapped()).isFalse();
        c.submittedFor(81L);
        assertThat(c.getApprovalId()).isEqualTo(81L);
        c.returned();
        assertThat(c.getStatus()).isEqualTo("SUBMITTED");
        c.approved();
        c.paid();
        assertThat(c.getStatus()).isEqualTo("PAID");
    }

    @Test
    void SRS_11_6_onlyARejectedOverlappingOrCancelledClaimIsNotLive() {
        ReimbursementClaim c = ReimbursementClaim.labour(7L, "LABOUR_TA", APRIL, CLAIMED);
        assertThat(c.live()).isTrue();
        c.rejected();
        assertThat(c.live()).isFalse();
        c.overlapRejected();
        assertThat(c.live()).isFalse();
        ReflectionTestUtils.setField(c, "status", "CANCELLED");
        assertThat(c.live()).isFalse();
        c.approved();
        assertThat(c.live()).isTrue();
    }

    @Test
    void SRS_11_1_anHrmsClaimIsWrittenApprovedWithItsAdjudication() {
        ReimbursementClaim c =
                ReimbursementClaim.fromHrms(
                        9L,
                        "MEDICAL",
                        APRIL,
                        CLAIMED,
                        2L,
                        CAPPED,
                        "HR-1",
                        40L,
                        Boolean.TRUE,
                        "CGHS",
                        81L);
        assertThat(c.getMemberType()).isEqualTo("EMPLOYEE");
        assertThat(c.getSourceCode()).isEqualTo("HRMS");
        assertThat(c.getStatus()).isEqualTo("APPROVED");
        assertThat(c.getPayableAmount()).isEqualByComparingTo("5000");
        assertThat(c.getCeilingNormId()).isEqualTo(2L);
        assertThat(c.isCapped()).isTrue();
        assertThat(c.getHrmsRequestId()).isEqualTo("HR-1");
        assertThat(c.getHrmsChangeTaskId()).isEqualTo(40L);
        assertThat(c.getTaxable()).isTrue();
        assertThat(c.getSchemeType()).isEqualTo("CGHS");
        assertThat(c.getApprovalId()).isEqualTo(81L);
        Instant at = Instant.parse("2026-05-01T05:00:00Z");
        c.taxableConfirmed(false, at);
        assertThat(c.getTaxable()).isFalse();
        assertThat(c.getTaxableConfirmedAt()).isEqualTo(at);
        c.ioga("IOGA-1", FROM, BigDecimal.TEN);
        assertThat(c.getIogaNumber()).isEqualTo("IOGA-1");
        assertThat(c.getIogaDate()).isEqualTo(FROM);
        assertThat(c.getIogaAmount()).isEqualByComparingTo("10");
    }

    @Test
    void SRS_11_16_aSupersededSelectionEndsTheDayBeforeTheNextButNeverBeforeItStarts() {
        CafeteriaElection e =
                CafeteriaElection.applied(9L, "2026-27", 1, FROM, "HR-2", Instant.EPOCH, 81L);
        assertThat(e.getStatus()).isEqualTo("APPLIED");
        assertThat(e.getVersionNo()).isEqualTo(1);
        e.supersededFrom(FROM.plusDays(10));
        assertThat(e.getStatus()).isEqualTo("SUPERSEDED");
        assertThat(e.getEffectiveTo()).isEqualTo(FROM.plusDays(9));
        CafeteriaElection same =
                CafeteriaElection.applied(9L, "2026-27", 1, FROM, "HR-2", Instant.EPOCH, 81L);
        same.supersededFrom(FROM);
        assertThat(same.getEffectiveTo()).isEqualTo(FROM);
        CafeteriaElection next =
                CafeteriaElection.applied(9L, "2026-27", 1, FROM, "HR-2", Instant.EPOCH, 81L);
        next.supersededFrom(FROM.plusDays(1));
        assertThat(next.getEffectiveTo()).isEqualTo(FROM);
    }
}
