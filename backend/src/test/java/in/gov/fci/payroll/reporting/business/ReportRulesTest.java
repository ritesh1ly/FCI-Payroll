package in.gov.fci.payroll.reporting.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.reporting.business.ReportRules.Status;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Report engine rules (plan 15; SRS-18-8, SRS-18-10, SRS-18-23, SRS-18-24): an entry without an
 * approved version or with a blocked one is Blocked, else produced on O-04 or on its own screen; an
 * output is final only when every cycle read is locked; control totals match within the tolerance;
 * a proposed version names a known format, a date from today, a reason when blocked and a store
 * mode.
 */
class ReportRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);

    // SC-08-0033: an output whose definition is not Active is flagged blocked, never generated.
    @Test
    void SRS_18_24_anEntryIsBlockedProducedHereOrOnItsOwnScreen() {
        assertThat(ReportRules.status(false, false, "O-04")).isEqualTo(Status.BLOCKED);
        assertThat(ReportRules.status(true, true, "O-04")).isEqualTo(Status.BLOCKED);
        assertThat(ReportRules.status(true, false, "O-04")).isEqualTo(Status.AVAILABLE);
        assertThat(ReportRules.status(true, false, "I-14")).isEqualTo(Status.PRODUCED_ELSEWHERE);
    }

    /** FRS V0.5 l.7130: every cycle verified (boundary equal), none short. */
    @Test
    void FRS_l7130_aPreLockReportNeedsEveryCycleVerified() {
        assertThat(ReportRules.verified(2, 2)).isTrue();
        assertThat(ReportRules.verified(2, 1)).isFalse();
        assertThat(ReportRules.verified(0, 0)).isTrue();
    }

    // SC-15-0005: no final output from a simulation or an unlocked computation.
    @Test
    void SRS_18_23_BR_MIS_004_anOutputIsFinalOnlyWhenEveryCycleIsLocked() {
        assertThat(ReportRules.provisional(0, 0)).isTrue();
        assertThat(ReportRules.provisional(3, 2)).isTrue();
        assertThat(ReportRules.provisional(3, 3)).isFalse();
    }

    @Test
    void SRS_18_8_controlTotalsMatchWithinTheTolerance() {
        assertThat(ReportRules.matches(new BigDecimal("100.00"), new BigDecimal("100"), null))
                .isTrue();
        assertThat(
                        ReportRules.matches(
                                new BigDecimal("100.01"), new BigDecimal("100"), BigDecimal.ZERO))
                .isFalse();
        assertThat(
                        ReportRules.matches(
                                new BigDecimal("100.01"),
                                new BigDecimal("100"),
                                new BigDecimal("-0.01")))
                .isTrue();
        assertThat(ReportRules.matches(null, null, null)).isTrue();
        assertThat(ReportRules.matches(null, BigDecimal.ONE, BigDecimal.ZERO)).isFalse();
    }

    // SC-15-0007: each catalogue entry is a dated Report Definition version (the layout attributes
    // wait for FCI, PENDING 15-layouts).
    @Test
    void SRS_18_10_aProposedVersionIsComplete() {
        assertThat(ReportRules.versionDefect("WORD", TODAY, TODAY, false, null, "STORE"))
                .contains("FORMAT");
        assertThat(ReportRules.versionDefect(null, null, TODAY, false, null, "STORE"))
                .contains("EFFECTIVE");
        assertThat(
                        ReportRules.versionDefect(
                                "PDF", TODAY.minusDays(1), TODAY, false, null, "STORE"))
                .contains("EFFECTIVE");
        assertThat(ReportRules.versionDefect("PDF", TODAY, TODAY, true, " ", "STORE"))
                .contains("REASON");
        assertThat(ReportRules.versionDefect("PDF", TODAY, TODAY, true, "x".repeat(501), "STORE"))
                .contains("REASON");
        assertThat(ReportRules.versionDefect("PDF", TODAY, TODAY, true, "légacy", "STORE"))
                .contains("REASON");
        assertThat(ReportRules.versionDefect("PDF", TODAY, TODAY, true, null, "STORE"))
                .contains("REASON");
        assertThat(ReportRules.versionDefect("PDF", TODAY, TODAY, false, null, "KEEP"))
                .contains("STORE");
        assertThat(
                        ReportRules.versionDefect(
                                null, TODAY, TODAY, true, "Layout awaited", "REGENERATE"))
                .isEmpty();
        assertThat(
                        ReportRules.versionDefect(
                                "EXCEL", TODAY.plusDays(3), TODAY, false, null, "STORE"))
                .isEmpty();
    }

    @Test
    void SRS_18_12_aMonthIsSixDigitsWithAMonthNumber() {
        assertThat(ReportRules.isYymm("202610")).isTrue();
        assertThat(ReportRules.isYymm("202613")).isFalse();
        assertThat(ReportRules.isYymm("202600")).isFalse();
        assertThat(ReportRules.isYymm("2026-10")).isFalse();
        assertThat(ReportRules.isYymm(null)).isFalse();
    }

    @Test
    void SRS_18_10_aBlockReasonOf500AndTheFirstAndLastMonthAreAccepted() {
        LocalDate today = LocalDate.of(2026, 10, 28);
        assertThat(ReportRules.versionDefect(null, today, today, true, "r".repeat(500), "STORE"))
                .isEmpty();
        assertThat(ReportRules.versionDefect(null, today, today, true, "r".repeat(501), "STORE"))
                .contains("REASON");
        assertThat(ReportRules.isYymm("202601")).isTrue();
        assertThat(ReportRules.isYymm("202612")).isTrue();
        assertThat(ReportRules.isYymm("202600")).isFalse();
        assertThat(ReportRules.isYymm("202613")).isFalse();
    }

    /** SRS-18-10 / R-ASCII: the M-12 blocked reason is a text area: line breaks kept. */
    @Test
    void SRS_18_10_aBlockedReasonOverSeveralLinesIsAccepted() {
        assertThat(ReportRules.versionDefect("PDF", TODAY, TODAY, true, "Under\r\nreview", "STORE"))
                .isEmpty();
        assertThat(ReportRules.versionDefect("PDF", TODAY, TODAY, true, "Under\u0007", "STORE"))
                .contains("REASON");
    }
}
