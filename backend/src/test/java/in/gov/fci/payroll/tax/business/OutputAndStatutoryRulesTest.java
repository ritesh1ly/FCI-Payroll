package in.gov.fci.payroll.tax.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.tax.business.OutputRules.Control;
import in.gov.fci.payroll.tax.business.OutputRules.Field;
import in.gov.fci.payroll.tax.business.model.OutputField.Binding;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * SRS M-17, BR-TAX-013, SRS-7-7, SRS-7-8, SRS-7-21: the rules of an output version and of the
 * statutory certificates and returns.
 */
class OutputAndStatutoryRulesTest {

    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);
    private static final Predicate<String> COMPONENTS = Set.of("101", "601")::contains;
    private static final Predicate<String> SOURCES = Set.of("employee.pan_number")::contains;
    private static final Predicate<String> FORMULAS = f -> f.startsWith("BASIC");

    private static String code(Runnable call) {
        try {
            call.run();
        } catch (FciException e) {
            return e.getErrorCode().code()
                    + (e.getMessageArgs() == null ? "" : Arrays.toString(e.getMessageArgs()));
        }
        return null;
    }

    private static String fields(Field... f) {
        return code(
                () -> OutputRules.requireFields(Arrays.asList(f), COMPONENTS, SOURCES, FORMULAS));
    }

    @Test
    void BR_MIS_002_versionsNeverOverlap() {
        assertThat(code(() -> OutputRules.requireAfter("FORM_24Q", APRIL, Optional.empty())))
                .isNull();
        assertThat(
                        code(
                                () ->
                                        OutputRules.requireAfter(
                                                "FORM_24Q",
                                                APRIL,
                                                Optional.of(APRIL.minusDays(1)))))
                .isNull();
        assertThat(code(() -> OutputRules.requireAfter("FORM_24Q", APRIL, Optional.of(APRIL))))
                .isEqualTo("TAX-409-OUTPUT-ORDER[FORM_24Q, 2026-04-01]");
        assertThat(code(() -> OutputRules.requireAfter("FORM_24Q", null, Optional.empty())))
                .isEqualTo("TAX-409-OUTPUT-ORDER[FORM_24Q, -]");
    }

    @Test
    void BR_TAX_013_everyFieldBindsToSomethingThatResolves() {
        assertThat(
                        fields(
                                new Field("Basic", Binding.COMPONENT, "101", null),
                                new Field("PAN", Binding.FIELD_SOURCE, "employee.pan_number", null),
                                new Field("Total", Binding.AGGREGATE, "SUM", "0.00"),
                                new Field("Form", Binding.LITERAL, "Form 16", null),
                                new Field("Gross", Binding.DERIVED, "BASIC + 1", null)))
                .isNull();
        assertThat(fields(new Field("Pay", Binding.COMPONENT, "999", null)))
                .startsWith("TAX-422-OUTPUT-BINDING[Pay");
        assertThat(fields(new Field("PAN", Binding.FIELD_SOURCE, "employee.aadhaar", null)))
                .startsWith("TAX-422-OUTPUT-BINDING[PAN");
        assertThat(fields(new Field("Total", Binding.AGGREGATE, "AVG", null)))
                .startsWith("TAX-422-OUTPUT-BINDING[Total");
        assertThat(fields(new Field("Gross", Binding.DERIVED, "1 +", null)))
                .startsWith("TAX-422-OUTPUT-BINDING[Gross");
        assertThat(fields(new Field("Form", Binding.LITERAL, "फ़ॉर्म", null)))
                .startsWith("TAX-422-OUTPUT-BINDING[Form");
        assertThat(fields(new Field("Form", Binding.LITERAL, "x".repeat(1001), null)))
                .startsWith("TAX-422-OUTPUT-BINDING[Form");
        // Labels: present, printable, at most 200, distinct; a binding and its reference.
        assertThat(
                        fields(
                                new Field("Basic", Binding.COMPONENT, "101", null),
                                new Field(" Basic ", Binding.COMPONENT, "601", null)))
                .contains("the label is used twice");
        assertThat(fields(new Field(" ", Binding.COMPONENT, "101", null)))
                .startsWith("TAX-422-OUTPUT-BINDING");
        assertThat(fields(new Field("x".repeat(201), Binding.COMPONENT, "101", null)))
                .startsWith("TAX-422-OUTPUT-BINDING");
        assertThat(fields(new Field("मूल", Binding.COMPONENT, "101", null)))
                .startsWith("TAX-422-OUTPUT-BINDING");
        assertThat(fields(new Field("Basic", null, "101", null)))
                .contains("a binding and its reference");
        assertThat(fields(new Field("Basic", Binding.COMPONENT, " ", null)))
                .contains("a binding and its reference");
        assertThat(fields(new Field(null, Binding.COMPONENT, "101", null)))
                .startsWith("TAX-422-OUTPUT-BINDING[null");
        assertThat(code(() -> OutputRules.requireFields(List.of(), COMPONENTS, SOURCES, FORMULAS)))
                .contains("at least one field");
        assertThat(code(() -> OutputRules.requireFields(null, COMPONENTS, SOURCES, FORMULAS)))
                .contains("at least one field");
        assertThat(fields((Field) null)).startsWith("TAX-422-OUTPUT-BINDING[null");
    }

    /** BR-TAX-013: a binding without any reference (null, not only blank) does not resolve. */
    @Test
    void BR_TAX_013_aBindingWithoutAReferenceIsRefused() {
        assertThat(fields(new Field("Basic", Binding.COMPONENT, null, null)))
                .contains("a binding and its reference");
        assertThat(fields(new Field("Basic", Binding.COMPONENT, " 101 ", null))).isNull();
    }

    @Test
    void SRS_7_8_theLayoutAndControlTotalsAreTheDdlVocabulary() {
        assertThat(
                        code(
                                () ->
                                        OutputRules.requireLayout(
                                                "PRESCRIBED_FILE",
                                                List.of(
                                                        new Control(
                                                                "TDS",
                                                                "CHALLAN",
                                                                BigDecimal.ZERO,
                                                                true)))))
                .isNull();
        assertThat(code(() -> OutputRules.requireLayout("CSV", null))).isNull();
        assertThat(code(() -> OutputRules.requireLayout("DOCX", List.of())))
                .startsWith("TAX-422-OUTPUT-BINDING[layout");
        assertThat(code(() -> OutputRules.requireLayout(null, List.of())))
                .startsWith("TAX-422-OUTPUT-BINDING[layout");
        for (Control bad :
                Arrays.asList(
                        new Control("TDS", "LEDGER", BigDecimal.ZERO, true),
                        new Control("TDS", "CHALLAN", new BigDecimal("-1"), true),
                        new Control("TDS", "CHALLAN", new BigDecimal("0.001"), true),
                        new Control("TDS", "CHALLAN", null, true),
                        new Control(" ", "CHALLAN", BigDecimal.ZERO, true),
                        new Control(null, "CHALLAN", BigDecimal.ZERO, true))) {
            assertThat(code(() -> OutputRules.requireLayout("CSV", List.of(bad))))
                    .as(String.valueOf(bad))
                    .startsWith("TAX-422-OUTPUT-BINDING");
        }
        assertThat(code(() -> OutputRules.requireLayout("CSV", Arrays.asList((Control) null))))
                .startsWith("TAX-422-OUTPUT-BINDING[control");
    }

    @Test
    void SRS_7_7_theQuartersOfTheYear() {
        assertThat(StatutoryRules.quarterMonths("2026-27", "Q1"))
                .containsExactly("202604", "202605", "202606");
        assertThat(StatutoryRules.quarterMonths("2026-27", "Q4"))
                .containsExactly("202701", "202702", "202703");
        assertThat(code(() -> StatutoryRules.quarterMonths("2026-27", "Q5")))
                .startsWith("TAX-409-DECLARATION-STATE");
        assertThat(code(() -> StatutoryRules.quarterMonths("2026-27", null)))
                .startsWith("TAX-409-DECLARATION-STATE");
        assertThat(StatutoryRules.quarterOf("2026-27", "202612")).isEqualTo("Q3");
        assertThat(StatutoryRules.quarterOf("2026-27", "202604")).isEqualTo("Q1");
        assertThat(StatutoryRules.quarterOf("2026-27", "202704")).isNull();
    }

    // BR-INT-005: no outbound statutory output from an unlocked period.
    // SC-15-0060: a statutory output whose cycle has not reached the required stage is not
    // generated.
    @Test
    void SRS_7_8_BR_TAX_011_noOutputForAnUnlockedPeriod() {
        List<String> q1 = StatutoryRules.quarterMonths("2026-27", "Q1");
        assertThat(code(() -> StatutoryRules.requireLocked(q1, m -> true))).isNull();
        assertThat(code(() -> StatutoryRules.requireLocked(q1, m -> !m.equals("202605"))))
                .isEqualTo("TAX-409-PERIOD-NOT-LOCKED[202605]");
    }

    @Test
    void BR_TAX_012_releaseWaitsForThePostedPayrollAndTheChallans() {
        BigDecimal tds = new BigDecimal("1000.00");
        assertThat(StatutoryRules.differences(tds, Optional.of(tds), tds, BigDecimal.ZERO))
                .isEmpty();
        assertThat(StatutoryRules.differences(tds, Optional.empty(), tds, BigDecimal.ZERO))
                .containsExactly(
                        "the posted payroll is not available (posting interface not yet"
                                + " connected)");
        assertThat(
                        StatutoryRules.differences(
                                tds, Optional.of(new BigDecimal("999.99")), tds, BigDecimal.ZERO))
                .containsExactly("TDS 1000.00 against posted 999.99");
        assertThat(
                        StatutoryRules.differences(
                                tds, Optional.of(tds), new BigDecimal("900"), BigDecimal.ZERO))
                .containsExactly("TDS 1000.00 against challans 900");
        // Within the tolerance of the version's control totals.
        assertThat(
                        StatutoryRules.differences(
                                tds,
                                Optional.of(new BigDecimal("999.99")),
                                new BigDecimal("1000.01"),
                                new BigDecimal("0.01")))
                .isEmpty();
    }

    @Test
    void SRS_7_21_eachLineOfTheChallanFileIsChecked() {
        StatutoryRules.ChallanLine ok =
                StatutoryRules.parseChallan(
                        2, "CIN000123,0510308,2026-07-07,12500.50,UTR123,Q1", "2026-27");
        assertThat(ok.amount()).isEqualByComparingTo("12500.50");
        assertThat(ok.period()).isEqualTo("Q1");
        assertThat(
                        StatutoryRules.parseChallan(
                                        2,
                                        " CIN1 , 0510308 , 2026-07-07 , 1 , , 202606 ",
                                        "2026-27")
                                .reference())
                .isNull();
        String[][] bad = {
            {"CIN1,0510308,2026-07-07,1,UTR", "six columns"},
            {"CIN-1,0510308,2026-07-07,1,UTR,Q1", "letters and digits"},
            {"CIN1,0510308,07-07-2026,1,UTR,Q1", "YYYY-MM-DD"},
            {"CIN1,0510308,2027-04-01,1,UTR,Q1", "a date in 2026-27"},
            {"CIN1,0510308,2026-07-07,abc,UTR,Q1", "rupees and paise"},
            {"CIN1,0510308,2026-07-07,0,UTR,Q1", "above zero"},
            {"CIN1,0510308,2026-07-07,1.005,UTR,Q1", "above zero"},
            {"CIN1,0510308,2026-07-07,1," + "R".repeat(51) + ",Q1", "payment reference"},
            {"CIN1,0510308,2026-07-07,1,UTR,Q5", "the period"},
            {"CIN1,0510308,2026-07-07,1,UTR,202704", "the period"},
            {"CIN1," + "1".repeat(21) + ",2026-07-07,1,UTR,Q1", "letters and digits"},
            {"CIN1,0510-308,2026-07-07,1,UTR,Q1", "letters and digits"},
            {"CIN1,0510308,2026-07-07,1,UTRé,Q1", "payment reference"},
            {"CIN1,0510308,2026-07-07,12345678901234567.00,UTR,Q1", "above zero"},
        };
        // The BSR code at its 20 characters and a reference at its 50 are accepted.
        assertThat(
                        StatutoryRules.parseChallan(
                                        2,
                                        "CIN1,"
                                                + "1".repeat(20)
                                                + ",2026-07-07,1,"
                                                + "R".repeat(50)
                                                + ",Q1",
                                        "2026-27")
                                .reference())
                .hasSize(50);
        for (String[] b : bad) {
            assertThat(code(() -> StatutoryRules.parseChallan(3, b[0], "2026-27")))
                    .as(b[0])
                    .startsWith("TAX-422-CHALLAN-FILE[3")
                    .contains(b[1]);
        }
    }
}
