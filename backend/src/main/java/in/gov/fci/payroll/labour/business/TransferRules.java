package in.gov.fci.payroll.labour.business;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.Set;

/**
 * Labour transfer and attachment rules (plan 20 phase 20e; SRS-2-6, SRS-2-47, BR-LABM-007 to
 * BR-LABM-009; D20-12). Pure functions: no state, no I/O.
 */
public final class TransferRules {

    /**
     * SRS-2-6 / BR-LABM-007 (FRS V0.5 l.3394 "normal or before-initialization stage"): the C-01
     * cycle state in which the receiving site's month is "still open" (DDL cycle_status). No
     * instance is "not started"; FROZEN and every later state are past it (OD-86).
     */
    public static final Set<String> OPEN_CYCLE = Set.of("INITIATED");

    /** The facts a transfer is checked against. */
    public record Facts(
            LocalDate transferDate,
            LocalDate today,
            long fromGangId,
            long toGangId,
            boolean attendanceComplete,
            boolean workSlipAfter,
            String receivingCycleStatus) {}

    private TransferRules() {}

    /**
     * SRS-2-6 / BR-LABM-007 to BR-LABM-009: what stops the transfer: DATE (before the current
     * month: an earlier month is a back-dated correction, D20-12), SAME_GANG, ATTENDANCE_INCOMPLETE
     * (the sending gang has not marked every day before the transfer date), WORK_SLIP_AFTER (a
     * work-slip of the labour after the transfer date), RECEIVING_CLOSED (the receiving site's
     * payroll for the month is past the open state, OD-86). A future date is allowed.
     */
    public static Optional<String> defect(Facts f) {
        if (f.transferDate() == null
                || f.transferDate().isBefore(YearMonth.from(f.today()).atDay(1))) {
            return Optional.of("DATE");
        }
        if (f.fromGangId() == f.toGangId()) {
            return Optional.of("SAME_GANG");
        }
        if (!f.attendanceComplete()) {
            return Optional.of("ATTENDANCE_INCOMPLETE");
        }
        if (f.workSlipAfter()) {
            return Optional.of("WORK_SLIP_AFTER");
        }
        if (f.receivingCycleStatus() != null && !OPEN_CYCLE.contains(f.receivingCycleStatus())) {
            return Optional.of("RECEIVING_CLOSED");
        }
        return Optional.empty();
    }

    /**
     * SRS-2-47: an attachment's dates: the start is today or later (a move may be future-dated),
     * the end, when given, is not before the start (no end: a permanent move). Answers FROM or TO.
     */
    public static Optional<String> attachmentDefect(LocalDate from, LocalDate to, LocalDate today) {
        if (from == null || from.isBefore(today)) {
            return Optional.of("FROM");
        }
        if (to != null && to.isBefore(from)) {
            return Optional.of("TO");
        }
        return Optional.empty();
    }

    /** D20-12: an attachment reference, TA-year-sequence (at most 20 characters). */
    public static String attachmentRef(int year, long sequence) {
        return String.format("TA-%d-%05d", year, sequence);
    }
}
