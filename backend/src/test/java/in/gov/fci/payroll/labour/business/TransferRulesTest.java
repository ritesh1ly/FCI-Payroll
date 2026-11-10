package in.gov.fci.payroll.labour.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.labour.business.TransferRules.Facts;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Labour transfer and attachment rules (plan 20 phase 20e; SRS-2-6, SRS-2-47; BR-LABM-007 to
 * BR-LABM-009; D20-12).
 */
class TransferRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);

    private static Facts facts(
            LocalDate date, long to, boolean attendance, boolean slip, String cycle) {
        return new Facts(date, TODAY, 5, to, attendance, slip, cycle);
    }

    @Test
    void SRS_2_6_aTransferNeedsCompleteAttendanceNoLaterSlipAndAnOpenReceivingMonth() {
        assertThat(TransferRules.defect(facts(TODAY, 6, true, false, null))).isEmpty();
        assertThat(TransferRules.defect(facts(TODAY.plusMonths(2), 6, true, false, "INITIATED")))
                .as("a future date")
                .isEmpty();
        assertThat(
                        TransferRules.defect(
                                facts(LocalDate.of(2026, 10, 1), 6, true, false, "INITIATED")))
                .isEmpty();
        assertThat(TransferRules.defect(facts(LocalDate.of(2026, 9, 30), 6, true, false, null)))
                .contains("DATE");
        assertThat(TransferRules.defect(facts(null, 6, true, false, null))).contains("DATE");
        assertThat(TransferRules.defect(facts(TODAY, 5, true, false, null))).contains("SAME_GANG");
        assertThat(TransferRules.defect(facts(TODAY, 6, false, false, null)))
                .contains("ATTENDANCE_INCOMPLETE");
        assertThat(TransferRules.defect(facts(TODAY, 6, true, true, null)))
                .contains("WORK_SLIP_AFTER");
        assertThat(TransferRules.defect(facts(TODAY, 6, true, false, "LOCKED")))
                .contains("RECEIVING_CLOSED");
    }

    // US-GAP-325: SRS-2-6 "not started or still open", BR-LABM-007 (FRS l.3394) "normal or
    // before-initialization stage"; OD-86: FROZEN and every later state are past the open one.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"FROZEN", "COMPUTED", "VERIFIED", "LOCKED"})
    void BR_LABM_007_eachCycleStatePastInitiatedRefusesTheTransfer(String state) {
        assertThat(TransferRules.defect(facts(TODAY, 6, true, false, state)))
                .contains("RECEIVING_CLOSED");
        assertThat(TransferRules.OPEN_CYCLE).containsExactly("INITIATED");
    }

    // SC-20-0112: a temporary attachment has its From and To dates.
    @Test
    void SRS_2_47_anAttachmentStartsTodayOrLaterAndEndsAfterItStarts() {
        assertThat(TransferRules.attachmentDefect(TODAY, TODAY, TODAY)).isEmpty();
        assertThat(TransferRules.attachmentDefect(TODAY.plusDays(3), null, TODAY))
                .as("a permanent move")
                .isEmpty();
        assertThat(TransferRules.attachmentDefect(null, null, TODAY)).contains("FROM");
        assertThat(TransferRules.attachmentDefect(TODAY.minusDays(1), null, TODAY))
                .contains("FROM");
        assertThat(TransferRules.attachmentDefect(TODAY.plusDays(3), TODAY.plusDays(2), TODAY))
                .contains("TO");
        assertThat(TransferRules.attachmentRef(2026, 412)).isEqualTo("TA-2026-00412");
        assertThat(TransferRules.attachmentRef(2026, 99_999).length()).isLessThanOrEqualTo(20);
    }
}
