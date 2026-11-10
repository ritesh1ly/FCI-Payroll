package in.gov.fci.payroll.leave.business;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The M-20 leave pay parameters and what each value must be (plan 19 phase 19b; SRS-LV-14, SRS M-20
 * field table; DDL {@code leave_pay_parameter.parameter_code}; D19-2). Pure: no framework, no I/O.
 * A value is never defaulted here: the Administrator proposes it from the current system and the
 * Payroll Checker approves it (R-RULE).
 */
public final class LeaveParameterRules {

    /** How a parameter's value is held. */
    public enum Kind {
        /** A positive number (the encashment divisor). */
        POSITIVE,
        /** Days, zero or more (the encashment limits and the ceiling at separation). */
        DAYS,
        /** A positive number, or CALENDAR_DAYS (the days of the payroll month). */
        DIVISOR,
        /** Pay codes of the component catalogue (M-07), separated by "," or "+". */
        PAY_CODES,
        /** One of the DDL {@code wage_effect_code} values, per leave type. */
        PAY_EFFECT
    }

    /** The LWP divisor taken as the calendar days of the payroll month. */
    public static final String CALENDAR_DAYS = "CALENDAR_DAYS";

    /**
     * DDL {@code leave_transaction.wage_effect_code}, without HELD_UNDEFINED (never configured).
     */
    public static final Set<String> PAY_EFFECTS =
            Set.of(
                    "NO_EFFECT",
                    "FULL_DEDUCTION",
                    "HALF_PAY",
                    "INCREMENT_DELAY",
                    "ENCASHMENT_TRIGGER",
                    "ALLOWANCE_RECOVERY");

    /**
     * The parameters of M-20 (SRS field table) and the pay effect of D19-2. The components subject
     * to LWP are an M-07 attribute (SRS-LV-15, BR-LEAVE-007; US-GAP-298), no longer an M-20
     * parameter (D19-3 withdrawn): a proposal of LWP_COMPONENTS is refused as an unknown parameter.
     */
    public static final Map<String, Kind> PARAMETERS =
            Map.of(
                    "LWP_DIVISOR", Kind.DIVISOR,
                    "ENCASH_DIVISOR", Kind.POSITIVE,
                    "ENCASH_PAY_ELEMENTS", Kind.PAY_CODES,
                    "INSERVICE_PER_OCCASION_LIMIT", Kind.DAYS,
                    "INSERVICE_PER_YEAR_LIMIT", Kind.DAYS,
                    "SEPARATION_AGGREGATE_CEILING", Kind.DAYS,
                    "ENCASH_PAY_CODES", Kind.PAY_CODES,
                    "PAY_EFFECT", Kind.PAY_EFFECT);

    /** The parameters that may be set for one leave type (DDL: NULL = every encashable type). */
    private static final Set<Kind> PER_TYPE = Set.of(Kind.DAYS, Kind.PAY_EFFECT);

    private static final Pattern PAY_CODES = Pattern.compile("^\\d{1,4}([,+]\\d{1,4}){0,19}$");
    private static final Pattern REASON = Pattern.compile("^[\\x20-\\x7E\\t\\r\\n]{1,500}$");
    private static final BigDecimal MAX_VALUE = new BigDecimal("99999999999999.9999");

    private LeaveParameterRules() {}

    /** A proposed version. */
    public record Proposal(
            String code, String leaveType, BigDecimal number, String text, String reason) {}

    /**
     * Why the proposal cannot be submitted, or empty.
     *
     * @param knownType whether its leave type (when given) was received from HRMS (M-13)
     */
    public static Optional<String> defect(Proposal p, boolean knownType) {
        Kind kind = p.code() == null ? null : PARAMETERS.get(p.code());
        if (kind == null) {
            return Optional.of("PARAMETER");
        }
        boolean typed = p.leaveType() != null && !p.leaveType().isBlank();
        if (kind == Kind.PAY_EFFECT && !typed) {
            return Optional.of("LEAVE_TYPE_REQUIRED");
        }
        if (typed && !PER_TYPE.contains(kind)) {
            return Optional.of("LEAVE_TYPE_NOT_ALLOWED");
        }
        if (typed && !knownType) {
            return Optional.of("LEAVE_TYPE");
        }
        if (p.reason() == null || !REASON.matcher(p.reason().strip()).matches()) {
            return Optional.of("REASON");
        }
        return valueDefect(kind, p.number(), p.text());
    }

    private static Optional<String> valueDefect(Kind kind, BigDecimal number, String text) {
        String t = text == null || text.isBlank() ? null : text.strip();
        boolean ok =
                switch (kind) {
                    case POSITIVE -> t == null && positive(number);
                    case DAYS ->
                            t == null
                                    && number != null
                                    && number.signum() >= 0
                                    && number.compareTo(MAX_VALUE) <= 0;
                    case DIVISOR ->
                            (t == null && positive(number))
                                    || (number == null && CALENDAR_DAYS.equals(t));
                    case PAY_CODES -> number == null && t != null && PAY_CODES.matcher(t).matches();
                    case PAY_EFFECT -> number == null && t != null && PAY_EFFECTS.contains(t);
                };
        return ok ? Optional.empty() : Optional.of("VALUE");
    }

    private static boolean positive(BigDecimal number) {
        return number != null && number.signum() > 0 && number.compareTo(MAX_VALUE) <= 0;
    }

    /** The pay codes of a PAY_CODES value, in order. */
    public static List<Integer> payCodes(String text) {
        return Arrays.stream(text.strip().split("[,+]")).map(Integer::valueOf).toList();
    }
}
