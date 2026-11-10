package in.gov.fci.payroll.reporting.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.reporting.business.ReportModelRules.Binding;
import in.gov.fci.payroll.reporting.business.ReportModelRules.Control;
import in.gov.fci.payroll.reporting.business.ReportModelRules.Distribution;
import in.gov.fci.payroll.reporting.business.ReportModelRules.Field;
import in.gov.fci.payroll.reporting.business.ReportModelRules.Parameter;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * The Report Definition Model of an M-12 version (SRS-18-9, BR-MIS-001; US-15-02 AC1-AC3;
 * US-GAP-34): parameters each with a validation rule, fields that resolve to the component
 * catalogue or the field-source model, control totals computable from the fields, the routing.
 */
class ReportModelRulesTest {

    private static final Predicate<String> COMPONENTS = Set.of("101", "701")::contains;
    private static final Predicate<String> FIELD_SOURCES = Set.of("employee.pan_number")::contains;
    private static final Predicate<String> FORMULAS = f -> f.startsWith("BASIC");

    private static Parameter parameter(String code, String type, String rule) {
        return new Parameter(code, "Month", type, true, false, null, rule);
    }

    private static Field field(String label, Binding binding, String reference) {
        return new Field(label, binding, reference, null);
    }

    private static java.util.Optional<String> fields(Field... f) {
        return ReportModelRules.fieldDefect(Arrays.asList(f), COMPONENTS, FIELD_SOURCES, FORMULAS);
    }

    @Test
    void SRS_18_9_eachParameterCarriesItsOwnValidationRule() {
        assertThat(ReportModelRules.parameterDefect(null)).isEmpty();
        assertThat(ReportModelRules.parameterDefect(List.of())).isEmpty();
        assertThat(
                        ReportModelRules.parameterDefect(
                                List.of(
                                        parameter("YYMM", "MONTH", "YYYYMM of a locked month"),
                                        new Parameter(
                                                "OFFICE",
                                                null,
                                                "LOCATION",
                                                false,
                                                true,
                                                " ",
                                                "In scope"))))
                .isEmpty();
        assertThat(ReportModelRules.parameterDefect(Arrays.asList((Parameter) null)))
                .contains("PARAMETER_CODE");
        for (String bad : new String[] {null, "yymm", "1A", "A".repeat(41), "A-B"}) {
            assertThat(ReportModelRules.parameterDefect(List.of(parameter(bad, "MONTH", "r"))))
                    .as("code %s", bad)
                    .contains("PARAMETER_CODE");
        }
        assertThat(
                        ReportModelRules.parameterDefect(
                                List.of(parameter("A".repeat(40), "MONTH", "r"))))
                .isEmpty();
        assertThat(
                        ReportModelRules.parameterDefect(
                                List.of(
                                        parameter("YYMM", "MONTH", "r"),
                                        parameter("YYMM", "TEXT", "r"))))
                .as("a code used twice")
                .contains("PARAMETER_CODE");
        for (String bad : new String[] {null, "month", "A".repeat(21)}) {
            assertThat(ReportModelRules.parameterDefect(List.of(parameter("YYMM", bad, "r"))))
                    .contains("PARAMETER_TYPE");
        }
        for (String bad : new String[] {null, " ", "x".repeat(501), "महीना"}) {
            assertThat(ReportModelRules.parameterDefect(List.of(parameter("YYMM", "MONTH", bad))))
                    .as("rule %s", bad)
                    .contains("PARAMETER_RULE");
        }
        assertThat(
                        ReportModelRules.parameterDefect(
                                List.of(parameter("YYMM", "MONTH", "x".repeat(500)))))
                .isEmpty();
        assertThat(
                        ReportModelRules.parameterDefect(
                                List.of(
                                        new Parameter(
                                                "YYMM",
                                                "x".repeat(201),
                                                "MONTH",
                                                true,
                                                false,
                                                null,
                                                "r"))))
                .contains("PARAMETER_TEXT");
        assertThat(
                        ReportModelRules.parameterDefect(
                                List.of(
                                        new Parameter(
                                                "YYMM",
                                                null,
                                                "MONTH",
                                                true,
                                                false,
                                                "x".repeat(301),
                                                "r"))))
                .contains("PARAMETER_TEXT");
    }

    @Test
    void SRS_18_9_aBindingThatDoesNotResolveIsRejected() {
        assertThat(
                        fields(
                                field("Basic", Binding.COMPONENT, " 101 "),
                                field("PAN", Binding.FIELD_SOURCE, "employee.pan_number"),
                                field("Total", Binding.AGGREGATE, "SUM"),
                                field("Note", Binding.LITERAL, "Final"),
                                field("Net", Binding.DERIVED, "BASIC - CPF")))
                .isEmpty();
        assertThat(ReportModelRules.fieldDefect(null, COMPONENTS, FIELD_SOURCES, FORMULAS))
                .isEmpty();
        assertThat(fields(field("Basic", Binding.COMPONENT, "999"))).contains("FIELD_UNRESOLVED");
        assertThat(fields(field("PAN", Binding.FIELD_SOURCE, "employee.pan")))
                .contains("FIELD_UNRESOLVED");
        assertThat(fields(field("Avg", Binding.AGGREGATE, "AVG"))).contains("FIELD_UNRESOLVED");
        assertThat(fields(field("Note", Binding.LITERAL, "x".repeat(1001))))
                .contains("FIELD_UNRESOLVED");
        assertThat(fields(field("Note", Binding.LITERAL, "x".repeat(1000)))).isEmpty();
        assertThat(fields(field("Note", Binding.LITERAL, "सूचना"))).contains("FIELD_UNRESOLVED");
        assertThat(fields(field("Net", Binding.DERIVED, "CPF + 1"))).contains("FIELD_UNRESOLVED");
    }

    @Test
    void SRS_18_9_everyFieldHasOneLabelABindingAndAShortMask() {
        assertThat(fields((Field) null)).contains("FIELD_LABEL");
        assertThat(fields(field(" ", Binding.COMPONENT, "101"))).contains("FIELD_LABEL");
        assertThat(fields(field("x".repeat(201), Binding.COMPONENT, "101")))
                .contains("FIELD_LABEL");
        assertThat(fields(field("x".repeat(200), Binding.COMPONENT, "101"))).isEmpty();
        assertThat(
                        fields(
                                field("Basic", Binding.COMPONENT, "101"),
                                field(" Basic ", Binding.COMPONENT, "701")))
                .contains("FIELD_LABEL");
        assertThat(fields(field("Basic", null, "101"))).contains("FIELD_BINDING");
        assertThat(fields(field("Basic", Binding.COMPONENT, null))).contains("FIELD_BINDING");
        assertThat(fields(field("Basic", Binding.COMPONENT, " "))).contains("FIELD_BINDING");
        assertThat(fields(new Field("Basic", Binding.COMPONENT, "101", "x".repeat(61))))
                .contains("FIELD_MASK");
        assertThat(fields(new Field("Basic", Binding.COMPONENT, "101", "#,##0.00"))).isEmpty();
    }

    /** SRS-18-9, R-ASCII: a label within the length but not printable ASCII is refused. */
    @Test
    void SRS_18_9_aLabelOutsidePrintableAsciiIsRefusedWhateverItsLength() {
        assertThat(fields(field("मूल", Binding.COMPONENT, "101"))).contains("FIELD_LABEL");
        assertThat(fields(new Field("Basic", Binding.COMPONENT, "101", "₹#,##0")))
                .contains("FIELD_MASK");
        assertThat(fields(field(" Basic ", Binding.COMPONENT, "101"))).isEmpty();
    }

    @Test
    void SRS_18_8_aControlTotalIsComputableFromTheFieldBindings() {
        Control posted = new Control("Gross", "POSTED_PAYROLL", BigDecimal.ZERO, true);
        assertThat(ReportModelRules.controlDefect(null, null)).isEmpty();
        assertThat(ReportModelRules.controlDefect(List.of(), List.of())).isEmpty();
        for (Binding amount : List.of(Binding.COMPONENT, Binding.AGGREGATE, Binding.DERIVED)) {
            assertThat(
                            ReportModelRules.controlDefect(
                                    List.of(posted), List.of(field("A", amount, "x"))))
                    .isEmpty();
        }
        assertThat(
                        ReportModelRules.controlDefect(
                                List.of(posted),
                                List.of(
                                        field("PAN", Binding.FIELD_SOURCE, "employee.pan_number"),
                                        field("Note", Binding.LITERAL, "x"))))
                .contains("CONTROL_NOT_COMPUTABLE");
        assertThat(ReportModelRules.controlDefect(List.of(posted), null))
                .contains("CONTROL_NOT_COMPUTABLE");
        assertThat(ReportModelRules.controlDefect(List.of(posted), Arrays.asList((Field) null)))
                .contains("CONTROL_NOT_COMPUTABLE");
    }

    @Test
    void SRS_18_8_aControlTotalReconcilesAgainstAKnownSourceWithinItsTolerance() {
        List<Field> amounts = List.of(field("Basic", Binding.COMPONENT, "101"));
        for (String against : ReportModelRules.RECONCILES) {
            assertThat(
                            ReportModelRules.controlDefect(
                                    List.of(
                                            new Control(
                                                    "G", against, new BigDecimal("0.50"), false)),
                                    amounts))
                    .isEmpty();
        }
        assertThat(ReportModelRules.controlDefect(Arrays.asList((Control) null), amounts))
                .contains("CONTROL");
        assertThat(
                        ReportModelRules.controlDefect(
                                List.of(new Control(" ", "POSTED_PAYROLL", BigDecimal.ZERO, true)),
                                amounts))
                .contains("CONTROL");
        assertThat(
                        ReportModelRules.controlDefect(
                                List.of(new Control("G", "LEDGER", BigDecimal.ZERO, true)),
                                amounts))
                .contains("CONTROL");
        assertThat(
                        ReportModelRules.controlDefect(
                                List.of(new Control("G", null, BigDecimal.ZERO, true)), amounts))
                .contains("CONTROL");
        for (String bad : new String[] {"-0.01", "0.001", "1000000000000.00"}) {
            assertThat(
                            ReportModelRules.controlDefect(
                                    List.of(new Control("G", "CHALLAN", new BigDecimal(bad), true)),
                                    amounts))
                    .as("tolerance %s", bad)
                    .contains("CONTROL");
        }
        assertThat(
                        ReportModelRules.controlDefect(
                                List.of(
                                        new Control(
                                                "G",
                                                "CHALLAN",
                                                new BigDecimal("999999999999.99"),
                                                true)),
                                amounts))
                .isEmpty();
        assertThat(
                        ReportModelRules.controlDefect(
                                List.of(new Control("G", "CHALLAN", null, true)), amounts))
                .contains("CONTROL");
    }

    @Test
    void SRS_18_9_theDistributionRoutesToAKnownConsumerAndChannel() {
        assertThat(ReportModelRules.distributionDefect(null)).isEmpty();
        assertThat(
                        ReportModelRules.distributionDefect(
                                List.of(
                                        new Distribution(
                                                "Finance", "INTERNAL_ROLE", "SCREEN", null),
                                        new Distribution(
                                                "Income Tax Dept",
                                                "EXTERNAL_AUTHORITY",
                                                "PORTAL",
                                                "MONTHLY"))))
                .isEmpty();
        assertThat(ReportModelRules.distributionDefect(Arrays.asList((Distribution) null)))
                .contains("DISTRIBUTION");
        assertThat(
                        ReportModelRules.distributionDefect(
                                List.of(new Distribution("x", null, "EMAIL", null))))
                .as("no consumer type")
                .contains("DISTRIBUTION");
        assertThat(
                        ReportModelRules.distributionDefect(
                                List.of(new Distribution("x", "MEMBER", null, null))))
                .as("no channel")
                .contains("DISTRIBUTION");
        assertThat(
                        ReportModelRules.distributionDefect(
                                List.of(new Distribution(" ", "MEMBER", "EMAIL", null))))
                .contains("DISTRIBUTION");
        assertThat(
                        ReportModelRules.distributionDefect(
                                List.of(new Distribution("x", "BANK", "EMAIL", null))))
                .contains("DISTRIBUTION");
        assertThat(
                        ReportModelRules.distributionDefect(
                                List.of(new Distribution("x", "MEMBER", "SMS", null))))
                .contains("DISTRIBUTION");
        assertThat(
                        ReportModelRules.distributionDefect(
                                List.of(
                                        new Distribution(
                                                "x", "ORG_UNIT", "PRINT", "x".repeat(101)))))
                .contains("DISTRIBUTION");
    }

    @Test
    void SRS_18_9_theRoundingKeyIsOptionalAndARuleKeyWhenGiven() {
        assertThat(ReportModelRules.roundingDefect(null)).isEmpty();
        assertThat(ReportModelRules.roundingDefect("ROUND.RUPEE_HALF_UP")).isEmpty();
        assertThat(ReportModelRules.roundingDefect("A".repeat(100))).isEmpty();
        assertThat(ReportModelRules.roundingDefect("A".repeat(101))).contains("ROUNDING");
        assertThat(ReportModelRules.roundingDefect("round")).contains("ROUNDING");
        assertThat(ReportModelRules.roundingDefect("")).contains("ROUNDING");
    }

    /** SRS-18-9 / R-ASCII: a LITERAL field (a text area) keeps its line breaks. */
    @Test
    void SRS_18_9_aLiteralOverSeveralLinesIsAccepted() {
        assertThat(fields(field("Note", Binding.LITERAL, "Line one\r\nline two"))).isEmpty();
        assertThat(fields(field("Note", Binding.LITERAL, "Line\u0007")))
                .contains("FIELD_UNRESOLVED");
    }
}
