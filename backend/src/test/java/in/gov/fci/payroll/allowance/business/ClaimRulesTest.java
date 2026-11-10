package in.gov.fci.payroll.allowance.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.allowance.business.ClaimRules.Period;
import in.gov.fci.payroll.allowance.business.ClaimRules.Periodicity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** I-03 claim adjudication (plan 10 phase 10a; SRS-11-5, 11-6, 11-9, 11-11, 11-14, 11-16). */
class ClaimRulesTest {

    private static Period p(String from, String to) {
        return new Period(LocalDate.parse(from), LocalDate.parse(to));
    }

    @Test
    void SRS_11_9_theWindowOfEachPeriodicity() {
        Period claim = p("2026-11-10", "2026-11-12");
        assertThat(ClaimRules.window(Periodicity.MONTHLY, claim))
                .isEqualTo(p("2026-11-01", "2026-11-30"));
        assertThat(ClaimRules.window(Periodicity.QUARTERLY, claim))
                .isEqualTo(p("2026-10-01", "2026-12-31"));
        assertThat(ClaimRules.window(Periodicity.QUARTERLY, p("2027-02-01", "2027-02-01")))
                .isEqualTo(p("2027-01-01", "2027-03-31"));
        assertThat(ClaimRules.window(Periodicity.QUARTERLY, p("2026-04-30", "2026-04-30")))
                .isEqualTo(p("2026-04-01", "2026-06-30"));
        assertThat(ClaimRules.window(Periodicity.ANNUAL, claim))
                .isEqualTo(p("2026-04-01", "2027-03-31"));
        assertThat(ClaimRules.window(Periodicity.ANNUAL, p("2027-03-31", "2027-03-31")))
                .isEqualTo(p("2026-04-01", "2027-03-31"));
        assertThat(ClaimRules.window(Periodicity.BLOCK_YEAR_4, claim))
                .isEqualTo(p("2024-04-01", "2028-03-31"));
        assertThat(ClaimRules.window(Periodicity.BLOCK_YEAR_4, p("2028-04-01", "2028-04-01")))
                .isEqualTo(p("2028-04-01", "2032-03-31"));
        assertThat(ClaimRules.window(Periodicity.ONE_TIME_PER_EVENT, claim)).isEqualTo(claim);
    }

    @Test
    void SRS_11_9_BR_ALLOW_001_payableIsTheLesserOfClaimedAndTheEntitlementLeft() {
        ClaimRules.Adjudication under =
                ClaimRules.adjudicate(
                        new BigDecimal("5000"), BigDecimal.ZERO, new BigDecimal("1200.50"));
        assertThat(under.payable()).isEqualByComparingTo("1200.50");
        assertThat(under.entitlement()).isEqualByComparingTo("5000.00");
        assertThat(under.capped()).isFalse();
        ClaimRules.Adjudication exact =
                ClaimRules.adjudicate(new BigDecimal("5000"), null, new BigDecimal("5000"));
        assertThat(exact.capped()).isFalse();
    }

    // SC-04-0055: a claim over the ceiling is capped, not rejected.
    @Test
    void SRS_11_11_aClaimOverTheEntitlementIsCappedNotRejected() {
        ClaimRules.Adjudication over =
                ClaimRules.adjudicate(
                        new BigDecimal("5000"), new BigDecimal("3000"), new BigDecimal("6000"));
        assertThat(over.entitlement()).isEqualByComparingTo("2000");
        assertThat(over.payable()).isEqualByComparingTo("2000");
        assertThat(over.capped()).isTrue();
        ClaimRules.Adjudication exhausted =
                ClaimRules.adjudicate(
                        new BigDecimal("5000"), new BigDecimal("7000"), new BigDecimal("10"));
        assertThat(exhausted.entitlement()).isEqualByComparingTo("0");
        assertThat(exhausted.payable()).isEqualByComparingTo("0");
        assertThat(exhausted.capped()).isTrue();
    }

    @Test
    void SRS_11_9_aNonPositiveCeilingOrClaimIsRefused() {
        assertThatThrownBy(() -> ClaimRules.adjudicate(BigDecimal.ZERO, null, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClaimRules.adjudicate(null, null, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClaimRules.adjudicate(BigDecimal.TEN, null, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClaimRules.adjudicate(BigDecimal.TEN, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void SRS_11_6_BR_ALLOW_007_overlappingPeriodsAreFoundIncludingTheEdgeDays() {
        Period claim = p("2026-11-10", "2026-11-12");
        assertThat(
                        ClaimRules.overlapping(
                                claim,
                                List.of(
                                        p("2026-11-01", "2026-11-09"),
                                        p("2026-11-12", "2026-11-20"))))
                .isEqualTo(1);
        assertThat(ClaimRules.overlapping(claim, List.of(p("2026-11-13", "2026-11-20"))))
                .isEqualTo(-1);
        assertThat(ClaimRules.overlapping(claim, List.of(p("2026-11-01", "2026-11-10")))).isZero();
        assertThat(ClaimRules.overlapping(claim, List.of())).isEqualTo(-1);
    }

    @Test
    void SRS_11_14_theRequiredDocumentsOfTheTypeNotAttachedAreNamed() {
        List<String> required =
                List.of(
                        "LABOUR_TA.OTHER",
                        "LABOUR_TA.CERTIFICATE",
                        "TRANSFER_TA.SANCTION",
                        "LABOUR_TA.");
        assertThat(ClaimRules.missingDocuments("LABOUR_TA", required, Set.of("OTHER")))
                .containsExactly("CERTIFICATE");
        assertThat(
                        ClaimRules.missingDocuments(
                                "LABOUR_TA", required, Set.of("OTHER", "CERTIFICATE")))
                .isEmpty();
        assertThat(ClaimRules.missingDocuments("MEDICAL", required, Set.of())).isEmpty();
        assertThat(ClaimRules.missingDocuments("LABOUR", List.of("LABOUR_TA.OTHER"), Set.of()))
                .isEmpty();
    }

    @Test
    void SRS_11_9_aClaimsPeriodAndAmountAreChecked() {
        Period ok = p("2026-11-01", "2026-11-01");
        assertThat(ClaimRules.defect(ok, new BigDecimal("10.25"))).isEmpty();
        assertThat(ClaimRules.defect(null, BigDecimal.ONE))
                .contains("the claim period is required");
        assertThat(ClaimRules.defect(new Period(null, LocalDate.now()), BigDecimal.ONE))
                .isPresent();
        assertThat(ClaimRules.defect(new Period(LocalDate.now(), null), BigDecimal.ONE))
                .isPresent();
        assertThat(ClaimRules.defect(p("2026-11-02", "2026-11-01"), BigDecimal.ONE))
                .contains("the claim period ends before it starts");
        assertThat(ClaimRules.defect(ok, null)).contains("the claimed amount is a positive amount");
        assertThat(ClaimRules.defect(ok, BigDecimal.ZERO))
                .contains("the claimed amount is a positive amount");
        assertThat(ClaimRules.defect(ok, new BigDecimal("1.255")))
                .contains("the claimed amount has at most two decimals");
        assertThat(ClaimRules.defect(ok, new BigDecimal("10000000000")))
                .contains("the claimed amount is too large");
        assertThat(ClaimRules.defect(ok, new BigDecimal("9999999999.99"))).isEmpty();
    }

    @Test
    void SRS_11_5_theRetirementDateBoundariesOfABulkMedicalClaim() {
        // US-GAP-214: retired the day before the claim starts - no payment; retiring on its first
        // day or within it - the claim is not refused (OPEN-QUESTION CLR-67: a claim spanning the
        // retirement date is paid in full as built).
        Period claim = p("2026-11-01", "2026-11-30");
        assertThat(ClaimRules.retired("IN_SERVICE", LocalDate.parse("2026-10-31"), claim)).isTrue();
        assertThat(ClaimRules.retired("IN_SERVICE", LocalDate.parse("2026-11-01"), claim))
                .isFalse();
        assertThat(ClaimRules.retired("IN_SERVICE", LocalDate.parse("2026-11-15"), claim))
                .isFalse();
        assertThat(ClaimRules.retired("IN_SERVICE", LocalDate.parse("2026-11-30"), claim))
                .isFalse();
        assertThat(ClaimRules.retired("IN_SERVICE", LocalDate.parse("2026-12-01"), claim))
                .isFalse();
    }

    @Test
    // SRS l.2796 (I-03 "Active member within the user's scope"): which statuses are active is
    // OD-62 (Awaiting FCI); BMAD TR-027 rejected: the rule is stated, its reading is OD-62.
    void US_GAP_216_onlyAMemberInServiceIsActive() {
        assertThat(ClaimRules.active("IN_SERVICE")).isTrue();
        for (String out :
                new String[] {
                    "SEPARATED", "DISMISSED", "RETIRED", "SUSPENDED", "ABSCONDING", "ON_DEPUTATION"
                }) {
            assertThat(ClaimRules.active(out)).as(out).isFalse();
        }
        assertThat(ClaimRules.active(null)).isFalse();
    }

    @Test
    void US_GAP_209_theTaxImpactIsWhatThePayableAddsToTheTaxableEarnings() {
        BigDecimal payable = new BigDecimal("2500.00");
        // M-07 Taxable of the claim type's pay code.
        assertThat(ClaimRules.taxImpact(payable, "Y", null))
                .isEqualTo(new ClaimRules.TaxImpact("Y", payable));
        assertThat(ClaimRules.taxImpact(payable, "PARTLY", null))
                .isEqualTo(new ClaimRules.TaxImpact("PARTLY", payable));
        assertThat(ClaimRules.taxImpact(payable, "N", null).taxableAmount())
                .isEqualByComparingTo("0");
        assertThat(ClaimRules.taxImpact(payable, "N", null).treatment()).isEqualTo("N");
        // SRS-11-19: the confirmed taxable flag of a medical claim rules.
        assertThat(ClaimRules.taxImpact(payable, "N", true))
                .isEqualTo(new ClaimRules.TaxImpact("Y", payable));
        assertThat(ClaimRules.taxImpact(payable, "Y", false).taxableAmount())
                .isEqualByComparingTo("0");
        assertThat(ClaimRules.taxImpact(payable, null, false).treatment()).isEqualTo("N");
        // No pay code or an unknown value: not configured, never assumed.
        assertThat(ClaimRules.taxImpact(payable, null, null))
                .isEqualTo(new ClaimRules.TaxImpact(null, null));
        assertThat(ClaimRules.taxImpact(payable, "MAYBE", null))
                .isEqualTo(new ClaimRules.TaxImpact(null, null));
        // No payable yet (not adjudicated): the treatment is known, the amount not.
        assertThat(ClaimRules.taxImpact(null, "Y", null))
                .isEqualTo(new ClaimRules.TaxImpact("Y", null));
    }

    @Test
    void SRS_11_5_BR_ALLOW_006_noMedicalPaymentToARetiredOrSeparatedMember() {
        Period claim = p("2026-11-01", "2026-11-05");
        assertThat(ClaimRules.retired("IN_SERVICE", null, claim)).isFalse();
        assertThat(ClaimRules.retired("SEPARATED", null, claim)).isTrue();
        assertThat(ClaimRules.retired("DISMISSED", null, claim)).isTrue();
        assertThat(ClaimRules.retired("IN_SERVICE", LocalDate.parse("2026-10-31"), claim)).isTrue();
        assertThat(ClaimRules.retired("IN_SERVICE", LocalDate.parse("2026-11-01"), claim))
                .isFalse();
    }

    @Test
    void SRS_11_16_aPartMonthIsPaidProRataRoundedOnceToTheRupee() {
        YearMonth nov = YearMonth.of(2026, 11);
        BigDecimal monthly = new BigDecimal("900");
        assertThat(ClaimRules.proRata(monthly, LocalDate.parse("2026-11-01"), null, nov))
                .isEqualByComparingTo("900");
        assertThat(ClaimRules.proRata(monthly, LocalDate.parse("2026-11-16"), null, nov))
                .isEqualByComparingTo("450");
        assertThat(
                        ClaimRules.proRata(
                                monthly,
                                LocalDate.parse("2026-10-01"),
                                LocalDate.parse("2026-11-10"),
                                nov))
                .isEqualByComparingTo("300");
        assertThat(
                        ClaimRules.proRata(
                                new BigDecimal("100"), LocalDate.parse("2026-11-30"), null, nov))
                .isEqualByComparingTo("3");
        assertThat(ClaimRules.proRata(monthly, LocalDate.parse("2026-12-01"), null, nov))
                .isEqualByComparingTo("0");
        assertThat(
                        ClaimRules.proRata(
                                monthly,
                                LocalDate.parse("2026-09-01"),
                                LocalDate.parse("2026-10-31"),
                                nov))
                .isEqualByComparingTo("0");
        assertThat(
                        ClaimRules.proRata(
                                monthly,
                                LocalDate.parse("2026-09-01"),
                                LocalDate.parse("2026-12-31"),
                                nov))
                .isEqualByComparingTo("900");
    }

    @Test
    void SRS_11_9_theFinancialYearStartsInApril() {
        assertThat(ClaimRules.financialYearStart(LocalDate.parse("2027-03-31")))
                .isEqualTo(LocalDate.parse("2026-04-01"));
        assertThat(ClaimRules.financialYearStart(LocalDate.parse("2026-04-01")))
                .isEqualTo(LocalDate.parse("2026-04-01"));
    }

    @Test
    void SRS_11_16_theDifferentialIsDueLessPaidPayCodeByPayCode() {
        assertThat(
                        ClaimRules.cafeteriaDifferential(
                                java.util.Map.of(
                                        917, new BigDecimal("1000"), 918, new BigDecimal("500")),
                                java.util.Map.of(917, new BigDecimal("1000")),
                                BigDecimal.ZERO))
                .containsOnlyKeys(918);
    }

    @Test
    void SRS_G_33_anUnattributedPaymentCountsAgainstTheCodesInOrderAndNeverBelowZero() {
        var out =
                ClaimRules.cafeteriaDifferential(
                        java.util.Map.of(917, new BigDecimal("300"), 918, new BigDecimal("500")),
                        java.util.Map.of(),
                        new BigDecimal("400"));
        assertThat(out).containsOnlyKeys(918);
        assertThat(out.get(918)).isEqualByComparingTo("400");
    }

    @Test
    void SRS_11_16_aPaidCodeNoLongerDueIsARecovery_itTakesNoUnattributedPayment() {
        var out =
                ClaimRules.cafeteriaDifferential(
                        java.util.Map.of(),
                        java.util.Map.of(917, new BigDecimal("300")),
                        new BigDecimal("100"));
        assertThat(out.get(917)).isEqualByComparingTo("-300");
    }
}
