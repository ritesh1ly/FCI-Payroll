package in.gov.fci.payroll.cpf.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.cpf.business.model.CpfDeputationRecovery.EntryType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** I-09 deputation adjustment (plan 07 phase 07g; SRS-6-28 … 6-30, BR-CPF-021 … 023). */
class DeputationRulesTest {

    private static final LocalDate FROM = LocalDate.of(2026, 7, 10);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void SRS_6_28_theOutstandingIsTheSanctionLessTheApprovedInstalments() {
        assertThat(DeputationRules.outstanding(d("60000"), List.of()))
                .isEqualByComparingTo("60000");
        assertThat(DeputationRules.outstanding(d("60000"), List.of(d("5000"), d("2500.50"))))
                .isEqualByComparingTo("52499.50");
    }

    // SC-07-0053: an instalment never takes the outstanding advance below zero.
    @Test
    void SRS_6_29_anInstalmentNeverTakesTheBalanceBelowZero() {
        assertThat(DeputationRules.after(d("5000"), d("5000")))
                .hasValueSatisfying(v -> assertThat(v).isZero());
        assertThat(DeputationRules.after(d("5000"), d("4999.99")))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("0.01"));
        assertThat(DeputationRules.after(d("5000"), d("5000.01"))).isEmpty();
    }

    @Test
    void SRS_6_27_anEntryHasAmountsForItsTypeAndAMonthOfTheDeputation() {
        BigDecimal z = BigDecimal.ZERO;
        assertThat(
                        DeputationRules.defect(
                                EntryType.RECOVERY_INSTALMENT,
                                d("100"),
                                z,
                                z,
                                z,
                                "202607",
                                FROM,
                                TO))
                .as("the month the deputation starts in")
                .isEmpty();
        assertThat(
                        DeputationRules.defect(
                                EntryType.RECOVERY_INSTALMENT,
                                d("100"),
                                z,
                                z,
                                z,
                                "202609",
                                FROM,
                                TO))
                .isEmpty();
        assertThat(
                        DeputationRules.defect(
                                EntryType.EXCESS_REFUND, d("100"), z, z, z, "203012", FROM, null))
                .as("open-ended")
                .isEmpty();
        assertThat(
                        DeputationRules.defect(
                                EntryType.RECOVERY_INSTALMENT,
                                d("100"),
                                z,
                                z,
                                z,
                                "202606",
                                FROM,
                                TO))
                .contains("MONTH");
        assertThat(
                        DeputationRules.defect(
                                EntryType.RECOVERY_INSTALMENT,
                                d("100"),
                                z,
                                z,
                                z,
                                "202610",
                                FROM,
                                TO))
                .contains("MONTH");
        assertThat(
                        DeputationRules.defect(
                                EntryType.RECOVERY_INSTALMENT,
                                d("100"),
                                z,
                                z,
                                z,
                                "202613",
                                FROM,
                                TO))
                .contains("MONTH");
        assertThat(
                        DeputationRules.defect(
                                EntryType.RECOVERY_INSTALMENT, d("100"), z, z, z, null, FROM, TO))
                .contains("MONTH");
        assertThat(
                        DeputationRules.defect(
                                EntryType.RECOVERY_INSTALMENT, z, z, z, z, "202608", FROM, TO))
                .contains("AMOUNT");
        assertThat(
                        DeputationRules.defect(
                                EntryType.RECOVERY_INSTALMENT,
                                d("1.005"),
                                z,
                                z,
                                z,
                                "202608",
                                FROM,
                                TO))
                .contains("AMOUNT");
        assertThat(
                        DeputationRules.defect(
                                EntryType.EXCESS_REFUND, null, z, z, z, "202608", FROM, TO))
                .contains("AMOUNT");
        assertThat(
                        DeputationRules.defect(
                                EntryType.CONTRIBUTION_RECEIPT,
                                z,
                                d("1200"),
                                d("1200"),
                                z,
                                "202608",
                                FROM,
                                TO))
                .isEmpty();
        assertThat(
                        DeputationRules.defect(
                                EntryType.CONTRIBUTION_RECEIPT,
                                z,
                                z,
                                z,
                                d("0.01"),
                                "202608",
                                FROM,
                                TO))
                .isEmpty();
        assertThat(
                        DeputationRules.defect(
                                EntryType.CONTRIBUTION_RECEIPT,
                                d("5"),
                                z,
                                z,
                                z,
                                "202608",
                                FROM,
                                TO))
                .as("nothing received")
                .contains("AMOUNT");
        assertThat(
                        DeputationRules.defect(
                                EntryType.CONTRIBUTION_RECEIPT,
                                z,
                                d("-1"),
                                d("2"),
                                z,
                                "202608",
                                FROM,
                                TO))
                .contains("AMOUNT");
        assertThat(
                        DeputationRules.defect(
                                EntryType.CONTRIBUTION_RECEIPT,
                                z,
                                d("1"),
                                null,
                                z,
                                "202608",
                                FROM,
                                TO))
                .contains("AMOUNT");
        // The VPF part is checked like the other two.
        assertThat(
                        DeputationRules.defect(
                                EntryType.CONTRIBUTION_RECEIPT,
                                z,
                                d("1"),
                                d("1"),
                                d("-0.01"),
                                "202608",
                                FROM,
                                TO))
                .contains("AMOUNT");
        // A month that is not six digits.
        assertThat(
                        DeputationRules.defect(
                                EntryType.RECOVERY_INSTALMENT,
                                d("100"),
                                z,
                                z,
                                z,
                                "2026-08",
                                FROM,
                                TO))
                .contains("MONTH");
        assertThat(DeputationRules.month("20268")).isEmpty();
        assertThat(DeputationRules.month("202608")).contains(java.time.YearMonth.of(2026, 8));
    }

    // SC-07-0054: a month without a contribution received is listed for follow-up.
    @Test
    void SRS_6_30_monthsWithoutAReceiptAreListedUpToTheEndOrToday() {
        assertThat(
                        DeputationRules.monthsWithoutReceipt(
                                FROM, TO, YearMonth.of(2027, 1), Set.of("202608")))
                .containsExactly("202607", "202609");
        assertThat(
                        DeputationRules.monthsWithoutReceipt(
                                FROM, null, YearMonth.of(2026, 8), Set.of()))
                .containsExactly("202607", "202608");
        assertThat(DeputationRules.monthsWithoutReceipt(FROM, TO, YearMonth.of(2026, 6), Set.of()))
                .isEmpty();
    }
}
