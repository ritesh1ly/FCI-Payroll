package in.gov.fci.payroll.reporting.business;

import in.gov.fci.payroll.platform.security.InputPatterns;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The Report Definition Model of an M-12 version (SRS-18-9, BR-MIS-001; M-12 field table
 * L3368-L3378; DDL {@code report_parameter}, {@code report_field_binding}, {@code
 * report_control_total}, {@code report_distribution}; US-GAP-34): the parameter set, each parameter
 * with its own validation rule; the field set bound to the component catalogue or the field-source
 * model, never to a physical column (a binding that does not resolve is rejected); the rounding /
 * presentation key; the control totals, computable from the field bindings; and the distribution
 * routing. Pure functions: no state, no I/O.
 */
public final class ReportModelRules {

    /** {@code report_field_binding.binding_type}. */
    public enum Binding {
        COMPONENT,
        FIELD_SOURCE,
        AGGREGATE,
        LITERAL,
        DERIVED
    }

    /** {@code report_control_total.reconciles_against} (DDL comment). */
    public static final Set<String> RECONCILES =
            Set.of("POSTED_PAYROLL", "GL_POSTING_STATEMENT", "BANK_ADVICE", "CHALLAN");

    /** The aggregate functions a field may apply. */
    public static final Set<String> AGGREGATES = Set.of("SUM", "COUNT", "MIN", "MAX");

    /** {@code report_distribution.consumer_type}. */
    public static final Set<String> CONSUMERS =
            Set.of("INTERNAL_ROLE", "ORG_UNIT", "EXTERNAL_AUTHORITY", "MEMBER");

    /** {@code report_distribution.delivery_channel}. */
    public static final Set<String> CHANNELS =
            Set.of("SCREEN", "EMAIL", "FILE_DROP", "PORTAL", "PRINT");

    /** DDL NUMBER(14,2). */
    private static final BigDecimal MAX_TOLERANCE = new BigDecimal("999999999999.99");

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");
    private static final Pattern DATA_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{0,19}$");
    private static final Pattern RULE_KEY = Pattern.compile("^[A-Z][A-Z0-9_.]{0,99}$");
    private static final Pattern PRINTABLE = Pattern.compile("^[\\x20-\\x7E]*$");

    /** One parameter of the set ({@code report_parameter}). */
    public record Parameter(
            String code,
            String label,
            String dataType,
            boolean mandatory,
            boolean roleScoped,
            String defaultExpression,
            String validationRule) {}

    /**
     * One field of the set; {@code reference}: pay code, entity.field, function, text or formula.
     */
    public record Field(String label, Binding binding, String reference, String formatMask) {}

    /** One control total ({@code report_control_total}). */
    public record Control(
            String label, String reconcilesAgainst, BigDecimal tolerance, boolean releaseGate) {}

    /** One consumer of the output ({@code report_distribution}). */
    public record Distribution(
            String consumerName, String consumerType, String channel, String schedule) {}

    private ReportModelRules() {}

    /**
     * SRS-18-9: the first defect of the parameter set, or empty: PARAMETER_CODE (an upper-case code
     * of up to 40 characters, used once), PARAMETER_TYPE (a data type code of up to 20),
     * PARAMETER_RULE ("each parameter carries its own validation rule": 1 to 500 printable
     * characters), PARAMETER_TEXT (a label of up to 200 or a default of up to 300 printable
     * characters).
     */
    public static Optional<String> parameterDefect(List<Parameter> parameters) {
        Set<String> codes = new HashSet<>();
        for (Parameter p : parameters == null ? List.<Parameter>of() : parameters) {
            if (p == null
                    || p.code() == null
                    || !CODE.matcher(p.code()).matches()
                    || !codes.add(p.code())) {
                return Optional.of("PARAMETER_CODE");
            }
            if (p.dataType() == null || !DATA_TYPE.matcher(p.dataType()).matches()) {
                return Optional.of("PARAMETER_TYPE");
            }
            if (!multiLine(p.validationRule(), 500)) {
                return Optional.of("PARAMETER_RULE");
            }
            if (!text(p.label(), 200, false) || !text(p.defaultExpression(), 300, false)) {
                return Optional.of("PARAMETER_TEXT");
            }
        }
        return Optional.empty();
    }

    /**
     * SRS-18-9, M-12 alternate flow (L3351): the first defect of the field set, or empty:
     * FIELD_LABEL (1 to 200 printable characters, used once), FIELD_BINDING (no binding or no
     * reference), FIELD_UNRESOLVED (a component pay code not in the catalogue, an entity.field not
     * in the field-source model, an aggregate not SUM / COUNT / MIN / MAX, a literal over 1000 or
     * not printable, a formula outside the grammar), FIELD_MASK (a format mask over 60).
     */
    public static Optional<String> fieldDefect(
            List<Field> fields,
            Predicate<String> componentExists,
            Predicate<String> fieldSourceExists,
            Predicate<String> formulaValid) {
        Set<String> labels = new HashSet<>();
        for (Field f : fields == null ? List.<Field>of() : fields) {
            if (f == null || !text(f.label(), 200, true) || !labels.add(f.label().strip())) {
                return Optional.of("FIELD_LABEL");
            }
            if (f.binding() == null || f.reference() == null || f.reference().isBlank()) {
                return Optional.of("FIELD_BINDING");
            }
            String ref = f.reference().strip();
            boolean resolves =
                    switch (f.binding()) {
                        case COMPONENT -> componentExists.test(ref);
                        case FIELD_SOURCE -> fieldSourceExists.test(ref);
                        case AGGREGATE -> AGGREGATES.contains(ref);
                        case LITERAL -> multiLine(ref, 1000);
                        case DERIVED -> formulaValid.test(ref);
                    };
            if (!resolves) {
                return Optional.of("FIELD_UNRESOLVED");
            }
            if (!text(f.formatMask(), 60, false)) {
                return Optional.of("FIELD_MASK");
            }
        }
        return Optional.empty();
    }

    /**
     * M-12 Control Totals "must be computable from the field bindings" (L3376): the first defect,
     * or empty: CONTROL (a label of 1 to 200 printable characters, reconciled against posted
     * payroll, the GL posting statement, the bank advice or the challan, a tolerance from 0 with
     * two decimals), CONTROL_NOT_COMPUTABLE (a control total with no amount in the field set: no
     * component, aggregate or derived binding to compute it from).
     */
    public static Optional<String> controlDefect(List<Control> controls, List<Field> fields) {
        List<Control> list = controls == null ? List.of() : controls;
        for (Control c : list) {
            if (c == null
                    || !text(c.label(), 200, true)
                    || c.reconcilesAgainst() == null
                    || !RECONCILES.contains(c.reconcilesAgainst())
                    || c.tolerance() == null
                    || c.tolerance().signum() < 0
                    || c.tolerance().stripTrailingZeros().scale() > 2
                    || c.tolerance().compareTo(MAX_TOLERANCE) > 0) {
                return Optional.of("CONTROL");
            }
        }
        boolean amounts =
                (fields == null ? List.<Field>of() : fields)
                        .stream()
                                .anyMatch(
                                        f ->
                                                f != null
                                                        && (f.binding() == Binding.COMPONENT
                                                                || f.binding() == Binding.AGGREGATE
                                                                || f.binding() == Binding.DERIVED));
        return !list.isEmpty() && !amounts
                ? Optional.of("CONTROL_NOT_COMPUTABLE")
                : Optional.empty();
    }

    /**
     * M-12 Period-Lock / Approval / Distribution: the first defect of the routing, or empty:
     * DISTRIBUTION (a consumer of 1 to 200 printable characters, of a DDL consumer type and
     * channel, a schedule of up to 100 printable characters).
     */
    public static Optional<String> distributionDefect(List<Distribution> routes) {
        for (Distribution d : routes == null ? List.<Distribution>of() : routes) {
            if (d == null
                    || !text(d.consumerName(), 200, true)
                    || d.consumerType() == null
                    || !CONSUMERS.contains(d.consumerType())
                    || d.channel() == null
                    || !CHANNELS.contains(d.channel())
                    || !text(d.schedule(), 100, false)) {
                return Optional.of("DISTRIBUTION");
            }
        }
        return Optional.empty();
    }

    /** M-12 Rounding / Presentation: optional; when given, a rule key of up to 100 characters. */
    public static Optional<String> roundingDefect(String key) {
        return key == null || RULE_KEY.matcher(key).matches()
                ? Optional.empty()
                : Optional.of("ROUNDING");
    }

    private static boolean text(String value, int max, boolean required) {
        if (value == null || value.isBlank()) {
            return !required;
        }
        String s = value.strip();
        return s.length() <= max && PRINTABLE.matcher(s).matches();
    }

    /** A required text entered in a text area (a validation rule, a literal): line breaks kept. */
    private static boolean multiLine(String value, int max) {
        return value != null
                && !value.isBlank()
                && value.strip().length() <= max
                && InputPatterns.isPrintableText(value.strip());
    }
}
