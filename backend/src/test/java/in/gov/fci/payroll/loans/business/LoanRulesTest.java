package in.gov.fci.payroll.loans.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.loans.business.LoanRules.Fit;
import in.gov.fci.payroll.loans.business.LoanRules.Line;
import in.gov.fci.payroll.loans.business.LoanRules.Terms;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The recovery schedule (plan 09 phase 09a): SRS-12-1 principal and interest groups, SRS-12-6
 * activation, SRS-G-19 final instalment, SRS-12-13 / 12-14 ceiling, SRS-12-11 combined ceiling,
 * SRS-12-16 foreclosure, SRS-12-5 resume. Amounts are fixtures.
 */
class LoanRulesTest {

    private static BigDecimal d(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    private static Terms terms(
            String sanctioned,
            int n,
            String instalment,
            String first,
            String interest,
            int ni,
            String interestInstalment) {
        return new Terms(
                d(sanctioned),
                n,
                d(instalment),
                d(first),
                d(interest),
                ni,
                d(interestInstalment),
                "202611");
    }

    // BR-LOAN-004: recovery never exceeds the outstanding; the final instalment takes the residual.
    @Test
    void SRS_12_1_BR_LOAN_003_principalFirstThenInterestAndEachFinalInstalmentIsTheResidual() {
        List<Line> s = LoanRules.schedule(terms("10000", 3, "3333", null, "500", 2, "250"));
        assertThat(s)
                .extracting(Line::payrollYymm)
                .containsExactly("202611", "202612", "202701", "202702", "202703");
        assertThat(s)
                .extracting(Line::principal)
                .containsExactly(d("3333.00"), d("3333.00"), d("3334.00"), d("0.00"), d("0.00"));
        assertThat(s)
                .extracting(Line::interest)
                .containsExactly(d("0.00"), d("0.00"), d("0.00"), d("250.00"), d("250.00"));
        assertThat(s.get(2).total()).isEqualByComparingTo("3334");
        assertThat(s.get(4).number()).isEqualTo(5);

        List<Line> first = LoanRules.schedule(terms("10000", 3, "3000", "4000", null, 0, null));
        assertThat(first)
                .extracting(Line::principal)
                .containsExactly(d("4000.00"), d("3000.00"), d("3000.00"));
        assertThat(LoanRules.schedule(terms("5000", 1, "5000", null, null, 0, null)))
                .extracting(Line::principal)
                .containsExactly(d("5000.00"));
    }

    // OPEN-QUESTION: CLR-138 (BMAD TR-026): the sources state that the principal instalments sum
    // to the sanction and the final instalment is the residual (BR-LOAN-001, BR-LOAN-004); the
    // limit of the residual over a regular instalment (one rupee per earlier instalment, D09-9)
    // is not stated. The cases below pin D09-9 until FCI answers.
    @Test
    void SRS_12_6_BR_LOAN_001_onlyTermsThatAddUpToTheSanctionAreActivated() {
        assertThat(LoanRules.defect(terms("10000", 3, "3333", null, null, 0, null))).isEmpty();
        assertThat(LoanRules.defect(terms("10000", 3, "3334", null, null, 0, null)))
                .as("final 3332, smaller")
                .isEmpty();
        assertThat(LoanRules.defect(terms("10001", 3, "3333", null, null, 0, null)))
                .as("final 3335 = 3333 + 2 rupees of rounding")
                .isEmpty();
        assertThat(LoanRules.defect(terms("10000", 3, "3331", null, null, 0, null)))
                .as("final 3338: more than the rounding")
                .contains("NOT_RECONCILED");
        assertThat(LoanRules.defect(terms("10000", 3, "5000", null, null, 0, null)))
                .as("final 0")
                .contains("NOT_RECONCILED");
        assertThat(LoanRules.defect(terms("10000", 3, "3000", "4000", null, 0, null))).isEmpty();
        assertThat(LoanRules.defect(terms("10000", 1, "10000", null, null, 0, null))).isEmpty();
        assertThat(LoanRules.defect(terms("1000", 1, "999", null, null, 0, null)))
                .as("one instalment: no rounding slack")
                .contains("NOT_RECONCILED");
        assertThat(LoanRules.defect(terms("10000", 3, "3333", null, "500", 2, "250"))).isEmpty();
        assertThat(LoanRules.defect(terms("10000", 3, "3333", null, "500", 2, "100")))
                .as("interest final 400")
                .contains("NOT_RECONCILED");
        assertThat(LoanRules.defect(terms("10000", 3, "3333", null, "500", 2, "251")))
                .as("interest final 249, within")
                .isEmpty();
    }

    @Test
    void SRS_12_1_badTermsAreNamed() {
        assertThat(LoanRules.defect(terms("0", 3, "3333", null, null, 0, null))).contains("AMOUNT");
        assertThat(LoanRules.defect(terms(null, 3, "3333", null, null, 0, null)))
                .contains("AMOUNT");
        assertThat(LoanRules.defect(terms("10000.005", 3, "3333", null, null, 0, null)))
                .contains("AMOUNT");
        assertThat(LoanRules.defect(terms("10000", 3, "3333.001", null, null, 0, null)))
                .contains("AMOUNT");
        assertThat(LoanRules.defect(terms("10000", 0, "3333", null, null, 0, null)))
                .contains("INSTALMENTS");
        assertThat(LoanRules.defect(terms("10000", 3, "0", null, null, 0, null)))
                .contains("INSTALMENT");
        assertThat(LoanRules.defect(terms("10000", 3, null, null, null, 0, null)))
                .contains("INSTALMENT");
        assertThat(LoanRules.defect(terms("10000", 3, "3333", "0", null, 0, null)))
                .contains("INSTALMENT");
        assertThat(LoanRules.defect(terms("10000", 3, "3333", null, "500", 0, "250")))
                .contains("INTEREST");
        assertThat(LoanRules.defect(terms("10000", 3, "3333", null, null, 2, "250")))
                .contains("INTEREST");
        assertThat(LoanRules.defect(terms("10000", 3, "3333", null, "500", 2, null)))
                .contains("INTEREST");
        assertThat(LoanRules.defect(terms("10000", 3, "3333", null, "0", 0, "250")))
                .contains("INTEREST");
        Terms badStart = new Terms(d("10000"), 3, d("3333"), null, null, 0, null, "202613");
        assertThat(LoanRules.defect(badStart)).contains("START");
        assertThat(LoanRules.month(null)).isEmpty();
        assertThat(LoanRules.month("2026")).isEmpty();
        assertThat(LoanRules.month("202611")).contains(YearMonth.of(2026, 11));
    }

    /** I-04 field table (L2713): interest instalments are required exactly for an interest type. */
    @Test
    void SRS_12_1_interestInstalmentsAreRequiredForAnInterestBearingTypeAndRefusedOtherwise() {
        Terms none = terms("10000", 4, "2500", null, null, 0, null);
        Terms interest = terms("10000", 4, "2500", null, "500", 1, "500");
        assertThat(LoanRules.interestDefect(true, none)).contains("INTEREST_REQUIRED");
        assertThat(LoanRules.interestDefect(false, interest)).contains("INTEREST_NOT_BORNE");
        assertThat(LoanRules.interestDefect(true, interest)).isEmpty();
        assertThat(LoanRules.interestDefect(false, none)).isEmpty();
        Terms negative = terms("10000", 4, "2500", null, null, -1, null);
        assertThat(LoanRules.interestDefect(true, negative))
                .as("a negative count is no instalment")
                .contains("INTEREST_REQUIRED");
        assertThat(LoanRules.interestDefect(false, negative)).isEmpty();
    }

    @Test
    void SRS_12_14_aBreachingInstalmentIsReducedAndTheTenureExtendedNeverRejected() {
        Fit fits = LoanRules.fit(d("10000"), d("2000"), d("50000"), d("10000"), d("40"));
        assertThat(fits).isEqualTo(new Fit(d("2000.00"), 5, false, true));
        Fit exact = LoanRules.fit(d("10000"), d("10000"), d("50000"), d("10000"), d("40"));
        assertThat(exact.reduced()).as("room 10000 = instalment").isFalse();
        Fit reduced = LoanRules.fit(d("10000"), d("3000"), d("50000"), d("18500"), d("40"));
        assertThat(reduced).isEqualTo(new Fit(d("1500.00"), 7, true, true));
        Fit paise = LoanRules.fit(d("10000"), d("3000"), d("50001"), d("18500"), d("40"));
        assertThat(paise.instalment()).as("whole rupees of the room").isEqualByComparingTo("1500");
        Fit none = LoanRules.fit(d("10000"), d("3000"), d("50000"), d("20000"), d("40"));
        assertThat(none.fits()).as("no room: not activated").isFalse();
        assertThat(none.instalment()).isEqualByComparingTo("3000");
        Fit under = LoanRules.fit(d("10000"), d("3000"), d("50000"), d("19999.50"), d("40"));
        assertThat(under.fits()).as("half a rupee of room").isFalse();
    }

    // SC-09-0015: the maximum deduction from salary is a dated setting.
    @Test
    void SRS_12_11_and_12_16_ceilingsAndForeclosure() {
        assertThat(LoanRules.maxNewSanction(d("100000"), d("40000"))).isEqualByComparingTo("60000");
        assertThat(LoanRules.maxNewSanction(d("100000"), d("100000"))).isEqualByComparingTo("0");
        assertThat(LoanRules.maxNewSanction(d("100000"), d("120000"))).isEqualByComparingTo("0");
        assertThat(LoanRules.foreclosureAmount(d("6667"), d("500"))).isEqualByComparingTo("7167");
        assertThat(LoanRules.foreclosureAmount(d("6667"), null)).isEqualByComparingTo("6667");
        assertThat(LoanRules.foreclosureAmount(null, null)).isEqualByComparingTo("0");
    }

    @Test
    void SRS_12_5_afterAReversedSeparationTheMissedInstalmentsFollowFromTheNextOpenMonth() {
        List<Line> left =
                List.of(
                        new Line(3, "202701", d("3334.00"), d("0.00")),
                        new Line(4, "202702", d("0.00"), d("250.00")));
        List<Line> resumed = LoanRules.resume(left, "202705");
        assertThat(resumed).extracting(Line::payrollYymm).containsExactly("202705", "202706");
        assertThat(resumed).extracting(Line::number).containsExactly(3, 4);
        assertThat(resumed.get(0).principal()).isEqualByComparingTo("3334");
    }

    @Test
    void SRS_12_6_boundaries() {
        assertThat(LoanRules.defect(terms("10000.25", 1, "10000.25", null, null, 0, null)))
                .as("paise are allowed")
                .isEmpty();
        assertThat(LoanRules.defect(terms("10000.255", 1, "10000.255", null, null, 0, null)))
                .contains("AMOUNT");
        assertThat(LoanRules.defect(terms("5000", 1, "5000", "4000", null, 0, null)))
                .as(
                        "a single instalment is the sanction; a first-instalment amount does not"
                                + " change it")
                .isEmpty();
        assertThat(LoanRules.defect(terms("5000", 1, "5000", null, "500", 1, "400")))
                .as("one interest instalment must carry all the interest")
                .contains("NOT_RECONCILED");
        assertThat(LoanRules.defect(terms("5000", 1, "5000", null, "500", 1, "500"))).isEmpty();
        assertThat(LoanRules.maxNewSanction(d("1000"), d("1000"))).isEqualByComparingTo("0");
        assertThat(LoanRules.maxNewSanction(d("1000"), d("999.5"))).isEqualByComparingTo("0.50");
    }

    /**
     * US-GAP-204 (SRS-12-6, SRS-12-3, SRS-G-19, BR-LOAN-004): a first instalment plus n-1 regular
     * ones that miss the sanction by paise still reconcile, the final instalment taking the paise
     * residual so the schedule adds up to the sanction exactly; one that would leave the final
     * instalment at zero or below, or beyond the rounding of the instalments before it, does not.
     */
    @Test
    void SRS_12_6_instalmentsMissingTheSanctionByPaiseLeaveTheResidualToTheFinalOne() {
        Terms short3 = terms("10000.00", 3, "3333.33", null, null, 0, null);
        assertThat(LoanRules.defect(short3)).isEmpty();
        List<Line> s = LoanRules.schedule(short3);
        assertThat(s)
                .extracting(Line::principal)
                .containsExactly(d("3333.33"), d("3333.33"), d("3333.34"));
        assertThat(s.stream().map(Line::principal).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("10000.00");

        Terms first = terms("10000.00", 4, "2999.99", "1000.01", null, 0, null);
        assertThat(LoanRules.defect(first)).isEmpty();
        assertThat(LoanRules.schedule(first))
                .extracting(Line::principal)
                .containsExactly(d("1000.01"), d("2999.99"), d("2999.99"), d("3000.01"));

        assertThat(LoanRules.defect(terms("10000.00", 3, "5000.00", null, null, 0, null)))
                .as("final instalment zero")
                .contains("NOT_RECONCILED");
        assertThat(LoanRules.defect(terms("10000.00", 3, "5000.01", null, null, 0, null)))
                .as("final instalment negative by paise")
                .contains("NOT_RECONCILED");
        assertThat(LoanRules.defect(terms("10001.01", 3, "3333.00", null, null, 0, null)))
                .as("final 3335.01: a paisa beyond 2 rupees of rounding")
                .contains("NOT_RECONCILED");
        assertThat(LoanRules.defect(terms("10001.00", 3, "3333.00", null, null, 0, null)))
                .as("final 3335.00: exactly 2 rupees of rounding")
                .isEmpty();
        Terms interestPaise = terms("10000", 2, "5000", null, "100.01", 3, "33.33");
        assertThat(LoanRules.defect(interestPaise)).isEmpty();
        assertThat(LoanRules.schedule(interestPaise))
                .extracting(Line::interest)
                .containsExactly(d("0.00"), d("0.00"), d("33.33"), d("33.33"), d("33.35"));
    }
}
