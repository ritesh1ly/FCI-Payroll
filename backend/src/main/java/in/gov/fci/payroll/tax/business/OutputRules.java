package in.gov.fci.payroll.tax.business;

import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.tax.business.model.OutputField.Binding;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The rules of a statutory-output version on M-17 (SRS M-17, BR-TAX-013, SRS-7-8; DDL Section 24):
 * a version starts after the one it replaces, every field binds to something that resolves, the
 * layout format and the control totals are the DDL's vocabulary.
 */
public final class OutputRules {

    /** {@code report_definition_version.layout_format}. */
    public static final Set<String> FORMATS =
            Set.of("PDF", "EXCEL", "CSV", "PRESCRIBED_FILE", "HTML");

    /** {@code report_control_total.reconciles_against}. */
    public static final Set<String> RECONCILES =
            Set.of("POSTED_PAYROLL", "GL_POSTING_STATEMENT", "BANK_ADVICE", "CHALLAN");

    /** The aggregate functions a field may apply. */
    public static final Set<String> AGGREGATES = Set.of("SUM", "COUNT", "MIN", "MAX");

    private OutputRules() {}

    /** One field as proposed. {@code reference}: pay code, entity.field, function or expression. */
    public record Field(String label, Binding binding, String reference, String formatMask) {}

    /** One control total as proposed. */
    public record Control(
            String label, String reconcilesAgainst, BigDecimal tolerance, boolean releaseGate) {}

    /** Effective dates never overlap: a new version starts after the latest one. */
    public static void requireAfter(String key, LocalDate from, Optional<LocalDate> latest) {
        if (from == null || latest.map(l -> !from.isAfter(l)).orElse(false)) {
            throw new FciException(
                    TaxErrorCode.OUTPUT_ORDER,
                    new Object[] {key, latest.map(LocalDate::toString).orElse("-")},
                    List.of(),
                    null);
        }
    }

    /**
     * BR-TAX-013: at least one field, labels distinct and printable, each binding resolving: a
     * component by its pay code, a field source by {@code entity.field}, an aggregate by its
     * function, a literal by its text, a derived value by a formula of the grammar.
     */
    public static void requireFields(
            List<Field> fields,
            Predicate<String> componentExists,
            Predicate<String> fieldSourceExists,
            Predicate<String> formulaValid) {
        if (fields == null || fields.isEmpty()) {
            throw binding("-", "a version needs at least one field");
        }
        Set<String> labels = new HashSet<>();
        for (Field f : fields) {
            String label = f == null ? null : f.label();
            if (label == null
                    || label.isBlank()
                    || label.length() > 200
                    || !StandardCharsets.US_ASCII.newEncoder().canEncode(label)) {
                throw binding(String.valueOf(label), "a label of up to 200 printable characters");
            }
            if (!labels.add(label.strip())) {
                throw binding(label, "the label is used twice");
            }
            if (f.binding() == null || f.reference() == null || f.reference().isBlank()) {
                throw binding(label, "a binding and its reference");
            }
            String ref = f.reference().strip();
            boolean ok =
                    switch (f.binding()) {
                        case COMPONENT -> componentExists.test(ref);
                        case FIELD_SOURCE -> fieldSourceExists.test(ref);
                        case AGGREGATE -> AGGREGATES.contains(ref);
                        case LITERAL ->
                                ref.length() <= 1000
                                        && StandardCharsets.US_ASCII.newEncoder().canEncode(ref);
                        case DERIVED -> formulaValid.test(ref);
                    };
            if (!ok) {
                throw binding(
                        label,
                        f.binding()
                                + " "
                                + ref
                                + " is not in the catalogue, the field-source model or the"
                                + " grammar");
            }
        }
    }

    /** The layout format and the control totals in the DDL's vocabulary; tolerances from zero. */
    public static void requireLayout(String format, List<Control> controls) {
        if (format == null || !FORMATS.contains(format)) {
            throw binding("layout", "a format of " + FORMATS);
        }
        for (Control c : controls == null ? List.<Control>of() : controls) {
            if (c == null
                    || c.label() == null
                    || c.label().isBlank()
                    || !RECONCILES.contains(c.reconcilesAgainst())
                    || c.tolerance() == null
                    || c.tolerance().signum() < 0
                    || c.tolerance().scale() > 2) {
                throw binding(
                        c == null || c.label() == null ? "control" : c.label(),
                        "a control total against " + RECONCILES + " with a tolerance from 0");
            }
        }
    }

    private static FciException binding(String label, String why) {
        return new FciException(
                TaxErrorCode.OUTPUT_BINDING, new Object[] {label, why}, List.of(), null);
    }
}
