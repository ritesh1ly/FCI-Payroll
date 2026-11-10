package in.gov.fci.payroll.employee.business;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * The M-01 Control flags Payroll owns (SRS-1-7, SRS-1-11; M-01 field table "Hold Flag" and
 * "Stop-Payment Flag" are Enterable; FRS §7.8.1 Scenario 4): each set or cleared from a date with a
 * reason. The Absconding flag of the same group is HRMS-owned (I-21) and is not here.
 */
public final class ControlFlagRules {

    /** The flag code and the employee column it writes (fixed: no column name from data). */
    public static final Map<String, String> COLUMNS =
            Map.of("HOLD", "HOLD_FLAG", "STOP_PAYMENT", "STOP_PAYMENT_FLAG");

    private static final int REASON = 500;

    private ControlFlagRules() {}

    /**
     * Why the proposal cannot be submitted, or empty. FUTURE_DATE: the DDL holds the flag without a
     * date of its own, so a flag set now for a later date would suppress a payment still due; until
     * FCI answers CLR-17 the change is taken from today or an earlier date only.
     */
    public static Optional<String> defect(
            String flag,
            String value,
            String current,
            LocalDate effectiveFrom,
            LocalDate joiningDate,
            LocalDate today,
            String reason) {
        if (flag == null || !COLUMNS.containsKey(flag)) {
            return Optional.of("UNKNOWN_FLAG");
        }
        if (!"Y".equals(value) && !"N".equals(value)) {
            return Optional.of("VALUE");
        }
        if (effectiveFrom == null) {
            return Optional.of("NO_DATE");
        }
        if (effectiveFrom.isAfter(today)) {
            return Optional.of("FUTURE_DATE");
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
        if (value.equals(current)) {
            return Optional.of("NO_CHANGE");
        }
        return Optional.empty();
    }
}
