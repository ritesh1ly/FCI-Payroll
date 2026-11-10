package in.gov.fci.payroll.employee.business;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The M-01 Payroll allowance flags (plan 16 phase 16d-2; SRS-1-4, SRS-1-11; FRS BR-EMPM-004 / 005 /
 * 006; DDL {@code employee_eligibility_flag}), as pure functions. Establishment/HR proposes a flag
 * value from a date with a reason; the Payroll Checker approves; every version is kept.
 */
public final class FlagRules {

    /** One version of a flag as recorded. */
    public record Version(
            String flagCode, String value, LocalDate effectiveFrom, LocalDate effectiveTo) {}

    private static final int REASON = 500;

    private FlagRules() {}

    /** The proposal's defect without an end date (see the full form below). */
    public static Optional<String> defect(
            boolean knownFlag,
            String value,
            LocalDate effectiveFrom,
            LocalDate joiningDate,
            String reason) {
        return defect(knownFlag, value, effectiveFrom, null, joiningDate, reason);
    }

    /**
     * The proposal's defect, if any: UNKNOWN_FLAG (not on the ELIGIBILITY_FLAG list), VALUE (Y or N
     * only), NO_DATE, END_BEFORE_START (US-GAP-39, M-01 Payroll Flags row: the end date, when
     * given, is not before the effective date), BEFORE_JOINING (BR-EMPM-006: never before the
     * joining date), NO_REASON and REASON_TOO_LONG (BR-EMPM-005: a reason of at most 500 characters
     * on every change).
     */
    public static Optional<String> defect(
            boolean knownFlag,
            String value,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            LocalDate joiningDate,
            String reason) {
        if (!knownFlag) {
            return Optional.of("UNKNOWN_FLAG");
        }
        if (!"Y".equals(value) && !"N".equals(value)) {
            return Optional.of("VALUE");
        }
        if (effectiveFrom == null) {
            return Optional.of("NO_DATE");
        }
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            return Optional.of("END_BEFORE_START");
        }
        if (joiningDate != null && effectiveFrom.isBefore(joiningDate)) {
            return Optional.of("BEFORE_JOINING");
        }
        if (reason == null || reason.isBlank()) {
            return Optional.of("NO_REASON");
        }
        if (reason.strip().length() > REASON) {
            return Optional.of("REASON_TOO_LONG");
        }
        return Optional.empty();
    }

    /**
     * The value of a flag on a day: its latest version effective on that day, if that version has
     * not ended before it ("N" when no version applies), as the engine reads it (D16-16).
     */
    public static String valueOn(List<Version> versions, String flagCode, LocalDate day) {
        return versions.stream()
                .filter(v -> v.flagCode().equals(flagCode))
                .filter(v -> !v.effectiveFrom().isAfter(day))
                .max(Comparator.comparing(Version::effectiveFrom))
                .filter(v -> v.effectiveTo() == null || !v.effectiveTo().isBefore(day))
                .map(Version::value)
                .orElse("N");
    }

    /** A proposal that would not change the value on its date is refused (NO_CHANGE). */
    public static boolean changes(
            List<Version> versions, String flagCode, LocalDate from, String value) {
        return !valueOn(versions, flagCode, from).equals(value);
    }
}
