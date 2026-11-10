package in.gov.fci.payroll.configuration.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SRS-16-22 / SRS-G-37: the versions of a master never overlap, one is in force on a day, and an
 * approved successor end-dates its predecessor the day before; plus the text and payroll-type
 * checks every Module 04 master shares (R-ASCII).
 */
class VersionChainTest {

    /** A version row as a dated master holds it. */
    static final class Row implements DatedVersion {
        final Long approvalId;
        final LocalDate from;
        LocalDate to;

        Row(Long approvalId, LocalDate from, LocalDate to) {
            this.approvalId = approvalId;
            this.from = from;
            this.to = to;
        }

        @Override
        public Long getApprovalId() {
            return approvalId;
        }

        @Override
        public LocalDate getEffectiveFrom() {
            return from;
        }

        @Override
        public LocalDate getEffectiveTo() {
            return to;
        }

        @Override
        public void endBefore(LocalDate successorFrom) {
            to = successorFrom.minusDays(1);
        }
    }

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    @Test
    void SRS_16_22_theApprovedVersionCoveringADayIsInForceAndAPendingOneIsOnlyProposed() {
        Row old = new Row(1L, d("2026-04-01"), d("2026-12-31"));
        Row current = new Row(2L, d("2027-01-01"), null);
        Row pending = new Row(3L, d("2028-01-01"), null);
        Row rejected = new Row(4L, d("2027-06-01"), null);
        Row unrouted = new Row(null, d("2025-01-01"), null);
        VersionChain<Row> chain =
                new VersionChain<>(
                        List.of(pending, rejected, current, old, unrouted),
                        Map.of(
                                1L, ApprovalStatus.APPROVED,
                                2L, ApprovalStatus.APPROVED,
                                3L, ApprovalStatus.PENDING_APPROVAL,
                                4L, ApprovalStatus.REJECTED));
        assertThat(chain.inForceOn(d("2026-05-01"))).contains(old);
        assertThat(chain.inForceOn(d("2027-07-01"))).contains(current);
        assertThat(chain.inForceOn(d("2025-06-01"))).isEmpty();
        assertThat(chain.pending()).contains(pending);
        assertThat(chain.statusOf(null)).isNull();
        assertThat(chain.isApproved(unrouted)).isFalse();
        assertThat(VersionChain.approvalIds(List.of(old, unrouted))).containsExactly(1L);
        assertThat(VersionChain.nextNumber(List.of(1, 3, 2))).isEqualTo(4);
        assertThat(VersionChain.nextNumber(List.of())).isEqualTo(1);
    }

    @Test
    void SRS_16_22_theVersionAwaitingTheCheckerIsTheOnePendingNotTheNewest() {
        // The newest version was rejected; the one awaiting the checker sits behind it.
        Row rejected = new Row(4L, d("2028-06-01"), null);
        Row pending = new Row(3L, d("2028-01-01"), null);
        Row approved = new Row(2L, d("2027-01-01"), null);
        VersionChain<Row> chain =
                new VersionChain<>(
                        List.of(rejected, pending, approved),
                        Map.of(
                                4L, ApprovalStatus.REJECTED,
                                3L, ApprovalStatus.PENDING_APPROVAL,
                                2L, ApprovalStatus.APPROVED));
        assertThat(chain.pending()).containsSame(pending);
        // Nothing awaits the checker once every version is decided.
        assertThat(
                        new VersionChain<>(
                                        List.of(rejected, approved),
                                        Map.of(
                                                4L, ApprovalStatus.REJECTED,
                                                2L, ApprovalStatus.APPROVED))
                                .pending())
                .isEmpty();
    }

    // SC-16-0080: a rate version on a date another holds, or before an approved one, is rejected.
    @Test
    void SRS_G_37_BR_AUDIT_002_aNewVersionStartsAfterEveryApprovedOneOnADateNoOtherHolds() {
        Row approved = new Row(1L, d("2027-01-01"), null);
        Row pending = new Row(2L, d("2028-01-01"), null);
        VersionChain<Row> chain =
                new VersionChain<>(
                        List.of(pending, approved),
                        Map.of(1L, ApprovalStatus.APPROVED, 2L, ApprovalStatus.PENDING_APPROVAL));
        chain.requireFreeDate(d("2027-01-02"));
        assertThatThrownBy(() -> chain.requireFreeDate(d("2027-01-01")))
                .isInstanceOf(FciException.class)
                .satisfies(
                        e ->
                                assertThat(((FciException) e).getErrorCode())
                                        .isEqualTo(ApiErrorCode.MASTER_DATE_NOT_AFTER));
        assertThatThrownBy(() -> chain.requireFreeDate(d("2028-01-01")))
                .satisfies(
                        e ->
                                assertThat(((FciException) e).getErrorCode())
                                        .isEqualTo(ApiErrorCode.MASTER_DATE_TAKEN));
    }

    @Test
    void SRS_16_22_anApprovedSuccessorEndDatesOnlyTheOpenApprovedVersionsBeforeIt() {
        // BR-RULE-004: a successor version end-dates its predecessor, so no computation gap opens.
        Row closed = new Row(1L, d("2026-04-01"), d("2026-12-31"));
        Row open = new Row(2L, d("2027-01-01"), null);
        Row rejected = new Row(3L, d("2027-03-01"), null);
        Row successor = new Row(4L, d("2027-04-01"), null);
        Row laterApproved = new Row(5L, d("2027-05-01"), null);
        VersionChain<Row> chain =
                new VersionChain<>(
                        List.of(laterApproved, successor, rejected, open, closed),
                        Map.of(
                                1L, ApprovalStatus.APPROVED,
                                2L, ApprovalStatus.APPROVED,
                                3L, ApprovalStatus.REJECTED,
                                4L, ApprovalStatus.APPROVED,
                                5L, ApprovalStatus.APPROVED));
        List<Row> saved = new ArrayList<>();
        List<Row> ended = chain.closeBefore(successor, saved::add);
        assertThat(ended).containsExactly(open);
        assertThat(saved).containsExactly(open);
        assertThat(open.to).isEqualTo(d("2027-03-31"));
        assertThat(closed.to).isEqualTo(d("2026-12-31"));
        assertThat(rejected.to).isNull();
        assertThat(laterApproved.to).isNull();
        assertThat(successor.coversDay(d("2027-04-01"))).isTrue();
        assertThat(successor.coversDay(d("2027-03-31"))).isFalse();
        assertThat(open.coversDay(d("2027-04-01"))).isFalse();
    }

    @Test
    void SRS_G_37_masterTextIsPrintableAsciiWithinItsLength() {
        assertThat(MasterText.required("f", "  No. 12/2026-DA ", 60)).isEqualTo("No. 12/2026-DA");
        assertThat(MasterText.optional("f", "  ", 60)).isNull();
        assertThat(MasterText.optional("f", null, 60)).isNull();
        assertThat(MasterText.optional("f", "x", 1)).isEqualTo("x");
        for (String bad : Arrays.asList(null, " ", "xx", "डीए", "a\tb")) {
            assertThatThrownBy(() -> MasterText.required("f", bad, 1))
                    .satisfies(
                            e ->
                                    assertThat(((FciException) e).getErrorCode())
                                            .isEqualTo(ApiErrorCode.MASTER_INVALID_TEXT));
        }
        assertThat(PayrollType.of("DPS_LABOUR")).isEqualTo(PayrollType.DPS_LABOUR);
        assertThat(PayrollType.of("CONTRACT")).isNull();
        assertThat(PayrollType.of(null)).isNull();
    }

    /**
     * R-ASCII: a master's multi-line text (M-25 subject, M-14 source instrument, M-08 reason) keeps
     * its line breaks; length, blank and non-ASCII are refused as for single-line text.
     */
    @Test
    void R_ASCII_masterTextOfATextAreaKeepsLineBreaksWithinItsLength() {
        assertThat(MasterText.requiredText("f", "  Para 1\r\nPara 2\tend ", 22))
                .isEqualTo("Para 1\r\nPara 2\tend");
        assertThat(MasterText.requiredText("f", "x".repeat(5), 5)).hasSize(5);
        assertThat(MasterText.optionalText("f", "  ", 5)).isNull();
        assertThat(MasterText.optionalText("f", null, 5)).isNull();
        assertThat(MasterText.optionalText("f", "a\nb", 3)).isEqualTo("a\nb");
        for (String bad : Arrays.asList(null, " \n ", "x".repeat(6), "\u0915", "a\u0007b")) {
            assertThatThrownBy(() -> MasterText.requiredText("f", bad, 5))
                    .satisfies(
                            e ->
                                    assertThat(((FciException) e).getErrorCode())
                                            .isEqualTo(ApiErrorCode.MASTER_INVALID_TEXT));
        }
        assertThatThrownBy(() -> MasterText.required("f", "a\nb", 5))
                .as("single-line master text still refuses a line break")
                .isInstanceOf(FciException.class);
    }

    @Test
    void SRS_16_22_theSameApprovedVersionReadTwiceIsNeverEndDatedByItself() {
        // The row being approved and an earlier copy of it (another instance, the same approval)
        // share the approval: the copy is the approved version itself, not its predecessor.
        Row earlier = new Row(1L, d("2026-04-01"), null);
        Row copy = new Row(2L, d("2026-06-01"), null);
        Row approved = new Row(2L, d("2026-07-01"), null);
        VersionChain<Row> chain =
                new VersionChain<>(
                        List.of(approved, copy, earlier),
                        Map.of(1L, ApprovalStatus.APPROVED, 2L, ApprovalStatus.APPROVED));
        List<Row> saved = new java.util.ArrayList<>();
        assertThat(chain.closeBefore(approved, saved::add)).containsExactly(earlier);
        assertThat(copy.to).isNull();
        assertThat(earlier.to).isEqualTo(d("2026-06-30"));
        assertThat(saved).containsExactly(earlier);
    }
}
