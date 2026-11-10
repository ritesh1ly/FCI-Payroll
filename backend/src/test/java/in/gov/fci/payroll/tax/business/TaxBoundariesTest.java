package in.gov.fci.payroll.tax.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.tax.business.OutputRules.Control;
import in.gov.fci.payroll.tax.business.OutputRules.Field;
import in.gov.fci.payroll.tax.business.model.ExternalIncomeDeclaration;
import in.gov.fci.payroll.tax.business.model.OutputField;
import in.gov.fci.payroll.tax.business.model.OutputField.Binding;
import in.gov.fci.payroll.tax.business.model.OutputVersion;
import in.gov.fci.payroll.tax.business.model.ReturnDeductee;
import in.gov.fci.payroll.tax.business.model.SavingsDeclaration;
import in.gov.fci.payroll.tax.business.model.StatutoryReturn;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The limits of the Module 08 rules at their exact edges (SRS M-17, SRS-7-8, SRS-7-16, SRS-7-21,
 * SRS-7-23): a value at the limit is accepted, one beyond it refused, so a rule cannot drift by one
 * without a test noticing.
 */
class TaxBoundariesTest {

    private static final Instant NOW = Instant.parse("2026-10-25T06:00:00Z");
    private static final Predicate<String> NONE = s -> false;

    private static String code(Runnable call) {
        try {
            call.run();
        } catch (FciException e) {
            return e.getErrorCode().code()
                    + (e.getMessageArgs() == null ? "" : Arrays.toString(e.getMessageArgs()));
        }
        return null;
    }

    private static String literal(String label, String reference) {
        return code(
                () ->
                        OutputRules.requireFields(
                                List.of(new Field(label, Binding.LITERAL, reference, null)),
                                NONE,
                                NONE,
                                NONE));
    }

    private static String challan(String line) {
        return code(() -> StatutoryRules.parseChallan(4, line, "2026-27"));
    }

    @Test
    void BR_MIS_002_aLabelOfTwoHundredAndALiteralOfAThousandCharactersAreTheLimit() {
        assertThat(literal("L".repeat(200), "x")).isNull();
        assertThat(literal("L".repeat(201), "x")).startsWith("TAX-422-OUTPUT-BINDING");
        assertThat(literal("Form", "x".repeat(1000))).isNull();
        assertThat(literal("Form", "x".repeat(1001))).startsWith("TAX-422-OUTPUT-BINDING");
    }

    @Test
    void SRS_7_8_aToleranceOfTwoDecimalsIsAcceptedAndARefusalNamesTheControl() {
        assertThat(
                        code(
                                () ->
                                        OutputRules.requireLayout(
                                                "CSV",
                                                List.of(
                                                        new Control(
                                                                "TDS",
                                                                "CHALLAN",
                                                                new BigDecimal("0.01"),
                                                                false)))))
                .isNull();
        assertThat(
                        code(
                                () ->
                                        OutputRules.requireLayout(
                                                "CSV",
                                                List.of(
                                                        new Control(
                                                                "Total TDS",
                                                                "LEDGER",
                                                                BigDecimal.ZERO,
                                                                true)))))
                .startsWith("TAX-422-OUTPUT-BINDING[Total TDS");
        assertThat(
                        code(
                                () ->
                                        OutputRules.requireLayout(
                                                "CSV",
                                                List.of(
                                                        new Control(
                                                                null,
                                                                "CHALLAN",
                                                                BigDecimal.ZERO,
                                                                true)))))
                .startsWith("TAX-422-OUTPUT-BINDING[control");
    }

    @Test
    void SRS_7_21_aChallanLineAtEachLimitIsAccepted() {
        assertThat(challan("CIN1," + "1".repeat(20) + ",2026-07-07,1,UTR,Q1")).isNull();
        assertThat(challan("CIN1,0510308,2026-07-07,1," + "R".repeat(50) + ",Q1")).isNull();
        // Sixteen digits of rupees with paise is the column's limit; seventeen are refused.
        assertThat(challan("CIN1,0510308,2026-07-07,1234567890123456.25,UTR,Q1")).isNull();
        assertThat(challan("CIN1,0510308,2026-07-07,123456789012345.25,UTR,Q1")).isNull();
        assertThat(challan("CIN1,0510308,2026-07-07,12345678901234567,UTR,Q1"))
                .startsWith("TAX-422-CHALLAN-FILE[4")
                .contains("above zero");
    }

    @Test
    void SRS_7_16_aDeclarationAtTheLimitIsAllowedAsDeclared() {
        BigDecimal declared = new BigDecimal("150000.00");
        assertThat(DeclarationRules.allowed(declared, new BigDecimal("150000"))).isSameAs(declared);
        assertThat(DeclarationRules.allowed(new BigDecimal("150001"), new BigDecimal("150000")))
                .isEqualByComparingTo("150000");
    }

    @Test
    void SRS_7_8_aValidationResultOfTwoThousandCharactersIsKeptWhole() {
        StatutoryReturn r =
                StatutoryReturn.generated("FORM_24Q", "2026-27", "Q1", 50L, 7L, null, NOW);
        r.validated(false, null, "x".repeat(2000));
        assertThat(r.getValidationResult()).hasSize(2000);
        assertThat(r.isValidated()).isFalse();
        r.validated(true, "FVU", "ok");
        assertThat(r.isValidated()).isTrue();
        assertThat(r.status()).isEqualTo(StatutoryReturn.Status.VALIDATED);
    }

    @Test
    void SRS_7_23_aPanOfFourCharactersShowsNoneOfItselfAndADeducteeWithoutTdsIsNotInTheReturn() {
        ReturnDeductee d =
                ReturnDeductee.of(
                        1L, 2L, "ABCD", true, false, BigDecimal.ONE, BigDecimal.ZERO, null);
        assertThat(d.maskedPan()).isEqualTo("****");
        assertThat(d.isInReturn()).isFalse();
        assertThat(d.isPanValid()).isTrue();
        assertThat(d.isHigherRateApplied()).isFalse();
        d.refigured("ABC", false, true, BigDecimal.TEN, BigDecimal.ONE, 9L);
        assertThat(d.maskedPan()).isEqualTo("****");
        assertThat(d.isInReturn()).isTrue();
        assertThat(d.isPanValid()).isFalse();
        assertThat(d.isHigherRateApplied()).isTrue();
        assertThat(d.getChallanId()).isEqualTo(9L);
    }

    @Test
    void BR_MIS_002_aVersionSaysWhetherItIsBlockedAndNeedsALockedPeriod() {
        OutputVersion v =
                OutputVersion.approved(
                        4L,
                        1,
                        LocalDate.of(2026, 4, 1),
                        "PDF",
                        null,
                        null,
                        true,
                        null,
                        1L,
                        "a",
                        NOW);
        assertThat(v.isPeriodLockRequired()).isTrue();
        assertThat(v.isBlocked()).isFalse();
        ReflectionTestUtils.setField(v, "blocked", "Y");
        assertThat(v.isBlocked()).isTrue();
        OutputVersion free =
                OutputVersion.approved(
                        4L,
                        2,
                        LocalDate.of(2026, 4, 1),
                        "PDF",
                        null,
                        null,
                        false,
                        null,
                        1L,
                        "a",
                        NOW);
        assertThat(free.isPeriodLockRequired()).isFalse();
        OutputField f =
                OutputField.of(7L, 1, "Form", Binding.LITERAL, null, null, null, "16", null);
        assertThat(f.binding()).isEqualTo(Binding.LITERAL);
    }

    @Test
    void SRS_7_14_anApprovedExternalEntryBecomesAnExternalSaving() {
        ExternalIncomeDeclaration e =
                ExternalIncomeDeclaration.saved(
                        5L,
                        "2026-27",
                        ExternalIncomeDeclaration.Type.EXTERNAL_SAVINGS,
                        "80CCD(1B)",
                        null,
                        null,
                        new BigDecimal("50000"),
                        null,
                        3L,
                        "maker",
                        NOW);
        assertThat(e.type()).isEqualTo(ExternalIncomeDeclaration.Type.EXTERNAL_SAVINGS);
        SavingsDeclaration s = SavingsDeclaration.external(e, 11L);
        assertThat(s.type()).isEqualTo(SavingsDeclaration.Type.EXTERNAL);
        assertThat(s.getEmployeeId()).isEqualTo(5L);
        assertThat(s.getFinYear()).isEqualTo("2026-27");
        assertThat(s.getAnnualAmount()).isEqualByComparingTo("50000");
        assertThat(s.getSectionCode()).isEqualTo("80CCD(1B)");
        assertThat(Set.of(SavingsDeclaration.Type.values()))
                .contains(SavingsDeclaration.Type.EXTERNAL);
    }
}
