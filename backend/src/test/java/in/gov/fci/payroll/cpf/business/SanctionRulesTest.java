package in.gov.fci.payroll.cpf.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.cpf.business.SanctionRules.ChecklistItem;
import in.gov.fci.payroll.cpf.business.SanctionRules.Stage;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The CPF sanction chain rules (SRS-6-2, SRS-6-4, SRS-6-5, SRS-6-14, SRS-6-24, SRS-6-26, SRS-6-37,
 * SRS-6-38; BR-CPF-009 to 011, 030, 031, 039). The multipliers, years and counts are inputs here:
 * in the service they come from the rule engine and are never seeded guesses (R-RULE).
 */
class SanctionRulesTest {

    private static final LedgerBalances BALANCE =
            new LedgerBalances(
                    new BigDecimal("1000.00"), new BigDecimal("600.00"), new BigDecimal("200.00"));

    private static ErrorCode code(Runnable call) {
        try {
            call.run();
        } catch (FciException e) {
            return e.getErrorCode();
        }
        return null;
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void
            SRS_6_4_aRequestIsActiveUntilPaidRejectedReturnedOrCancelledAndWithdrawableBeforeTheSanction() {
        assertThat(EnumSet.allOf(Stage.class).stream().filter(Stage::active))
                .containsExactlyInAnyOrder(
                        Stage.UNIT_VERIFICATION,
                        Stage.ZONAL_HQ_VALIDATION,
                        Stage.COMPETENT_AUTHORITY,
                        Stage.SANCTIONED,
                        Stage.PAYMENT_BLOCKED);
        assertThat(EnumSet.allOf(Stage.class).stream().filter(Stage::withdrawable))
                .containsExactlyInAnyOrder(
                        Stage.UNIT_VERIFICATION,
                        Stage.ZONAL_HQ_VALIDATION,
                        Stage.COMPETENT_AUTHORITY,
                        Stage.RETURNED);
    }

    @Test
    void SRS_6_4_theAmountIsMoreThanZeroWithAtMostTwoDecimals() {
        assertThat(code(() -> SanctionRules.requireAmount(d("0.01")))).isNull();
        assertThat(code(() -> SanctionRules.requireAmount(d("1500.50")))).isNull();
        // Trailing zeros beyond two places are still two decimals.
        assertThat(code(() -> SanctionRules.requireAmount(d("1500.5000")))).isNull();
        for (String bad : new String[] {"0", "0.00", "-1", "10.001"}) {
            assertThat(code(() -> SanctionRules.requireAmount(d(bad))))
                    .as(bad)
                    .isEqualTo(CpfErrorCode.REQUEST_AMOUNT);
        }
        assertThat(code(() -> SanctionRules.requireAmount(null)))
                .isEqualTo(CpfErrorCode.REQUEST_AMOUNT);
    }

    @Test
    void BR_CPF_039_aPropertyPurposeNeedsTheAddressAndTheDescription() {
        assertThat(code(() -> SanctionRules.requireProperty(false, null, null))).isNull();
        assertThat(code(() -> SanctionRules.requireProperty(true, "Plot 4, Sector 9", "Two rooms")))
                .isNull();
        for (String[] bad : new String[][] {{null, "x"}, {"x", null}, {" ", "x"}, {"x", ""}}) {
            assertThat(code(() -> SanctionRules.requireProperty(true, bad[0], bad[1])))
                    .isEqualTo(CpfErrorCode.PROPERTY_DETAILS);
        }
    }

    @Test
    void SRS_6_14_theMissingMandatoryDocumentsAreNamedInChecklistOrder() {
        List<ChecklistItem> checklist =
                List.of(
                        new ChecklistItem("CERTIFICATE", true),
                        new ChecklistItem("OTHER", false),
                        new ChecklistItem("SALE_DEED", true));
        assertThat(SanctionRules.missingDocuments(checklist, List.of()))
                .containsExactly("CERTIFICATE", "SALE_DEED");
        assertThat(SanctionRules.missingDocuments(checklist, List.of("SALE_DEED", "OTHER")))
                .containsExactly("CERTIFICATE");
        assertThat(SanctionRules.missingDocuments(checklist, List.of("CERTIFICATE", "SALE_DEED")))
                .isEmpty();
        assertThat(SanctionRules.missingDocuments(List.of(), List.of())).isEmpty();
    }

    @Test
    void BR_CPF_009_membershipCountsWholeYearsOnTheRequestDate() {
        LocalDate join = LocalDate.of(2020, 4, 15);
        // Five years complete on the anniversary, not the day before.
        assertThat(
                        code(
                                () ->
                                        SanctionRules.requireMembership(
                                                join, LocalDate.of(2025, 4, 15), d("5"))))
                .isNull();
        assertThat(
                        code(
                                () ->
                                        SanctionRules.requireMembership(
                                                join, LocalDate.of(2025, 4, 14), d("5"))))
                .isEqualTo(CpfErrorCode.MEMBERSHIP_YEARS);
        // A fractional setting counts its whole years only.
        assertThat(
                        code(
                                () ->
                                        SanctionRules.requireMembership(
                                                join, LocalDate.of(2025, 4, 15), d("5.9"))))
                .isNull();
        // No EPS join date from HRMS: the data is missing, never assumed.
        assertThat(
                        code(
                                () ->
                                        SanctionRules.requireMembership(
                                                null, LocalDate.of(2025, 4, 15), d("5"))))
                .isEqualTo(CpfErrorCode.ELIGIBILITY_DATA);
    }

    @Test
    void BR_CPF_010_partFinalWithdrawalsStopAtTheConfiguredMaximum() {
        assertThat(code(() -> SanctionRules.requireCount(0, d("6")))).isNull();
        assertThat(code(() -> SanctionRules.requireCount(5, d("6")))).isNull();
        assertThat(code(() -> SanctionRules.requireCount(6, d("6"))))
                .isEqualTo(CpfErrorCode.WITHDRAWAL_COUNT);
        assertThat(code(() -> SanctionRules.requireCount(7, d("6"))))
                .isEqualTo(CpfErrorCode.WITHDRAWAL_COUNT);
    }

    @Test
    void BR_CPF_011_theNinetyPercentWithdrawalOnlyWithinTheYearsBeforeRetirement() {
        LocalDate retire = LocalDate.of(2030, 6, 30);
        assertThat(
                        code(
                                () ->
                                        SanctionRules.requireRetirementWindow(
                                                retire, LocalDate.of(2029, 6, 30), d("1"))))
                .isNull();
        assertThat(code(() -> SanctionRules.requireRetirementWindow(retire, retire, d("1"))))
                .isNull();
        assertThat(
                        code(
                                () ->
                                        SanctionRules.requireRetirementWindow(
                                                retire, LocalDate.of(2029, 6, 29), d("1"))))
                .isEqualTo(CpfErrorCode.RETIREMENT_WINDOW);
        assertThat(
                        code(
                                () ->
                                        SanctionRules.requireRetirementWindow(
                                                retire, LocalDate.of(2030, 7, 1), d("1"))))
                .isEqualTo(CpfErrorCode.RETIREMENT_WINDOW);
        assertThat(code(() -> SanctionRules.requireRetirementWindow(null, retire, d("1"))))
                .isEqualTo(CpfErrorCode.ELIGIBILITY_DATA);
    }

    /**
     * SRS-6-5 (l.2083; BMAD TR-017) on 29 February: the source does not say when "5 years" from a
     * leap day ends or which day is "one year before" a 29 February retirement. Pinned as built
     * (OD-94, Awaiting FCI): membership counts whole years by the calendar (Period), so a joiner of
     * 29 Feb 2020 completes five years on 1 Mar 2025, not 28 Feb; the retirement window steps back
     * a year by the calendar (minusYears), so for 29 Feb 2028 it opens on 28 Feb 2027.
     */
    @Test
    void SRS_6_5_aLeapDayJoiningOrRetirementTakesTheCalendarBoundaryAsBuilt() {
        LocalDate leapJoin = LocalDate.of(2020, 2, 29);
        assertThat(
                        code(
                                () ->
                                        SanctionRules.requireMembership(
                                                leapJoin, LocalDate.of(2025, 2, 28), d("5"))))
                .isEqualTo(CpfErrorCode.MEMBERSHIP_YEARS);
        assertThat(
                        code(
                                () ->
                                        SanctionRules.requireMembership(
                                                leapJoin, LocalDate.of(2025, 3, 1), d("5"))))
                .isNull();
        // The next leap anniversary is a whole number of years too.
        assertThat(
                        code(
                                () ->
                                        SanctionRules.requireMembership(
                                                leapJoin, LocalDate.of(2024, 2, 29), d("4"))))
                .isNull();
        LocalDate leapRetire = LocalDate.of(2028, 2, 29);
        assertThat(
                        code(
                                () ->
                                        SanctionRules.requireRetirementWindow(
                                                leapRetire, LocalDate.of(2027, 2, 28), d("1"))))
                .isNull();
        assertThat(
                        code(
                                () ->
                                        SanctionRules.requireRetirementWindow(
                                                leapRetire, LocalDate.of(2027, 2, 27), d("1"))))
                .isEqualTo(CpfErrorCode.RETIREMENT_WINDOW);
    }

    // OPEN-QUESTION: Q-07-4 (BMAD TR-018): only the flag is tested here; what makes an advance
    // "active" (in the chain, until paid, or while still repaid) is FCI's to state.
    @Test
    void SRS_6_37_BR_CPF_030_noNewAdvanceWhileAnotherIsActive() {
        assertThat(code(() -> SanctionRules.requireNoActiveAdvance(false))).isNull();
        assertThat(code(() -> SanctionRules.requireNoActiveAdvance(true)))
                .isEqualTo(CpfErrorCode.ACTIVE_ADVANCE);
    }

    @Test
    void
            SRS_6_2_SRS_6_24_BR_CPF_001_BR_CPF_037_theCeilingFollowsItsBasisAndIsEmptyWhenAnInputIsNotConfigured() {
        // MGW multiple: 12 x MGW; no MGW basis configured (OQ-3) means no ceiling.
        assertThat(
                        SanctionRules.ceiling(
                                WithdrawalLimit.MARRIAGE, d("12"), d("15000.00"), BALANCE, null))
                .contains(d("180000.00"));
        assertThat(SanctionRules.ceiling(WithdrawalLimit.MARRIAGE, d("12"), null, BALANCE, null))
                .isEmpty();
        // Balance fraction: 0.9 x (employee + employer + VPF), rounded down to the paisa.
        assertThat(
                        SanctionRules.ceiling(
                                WithdrawalLimit.BEFORE_RETIREMENT, d("0.9"), null, BALANCE, null))
                .contains(d("1620.00"));
        LedgerBalances odd = new LedgerBalances(d("0.05"), d("0.00"), d("0.00"));
        assertThat(
                        SanctionRules.ceiling(
                                WithdrawalLimit.BEFORE_RETIREMENT, d("0.9"), null, odd, null))
                .contains(d("0.04"));
        // Pay multiple or own share, whichever is less (SRS-6-24).
        assertThat(
                        SanctionRules.ceiling(
                                WithdrawalLimit.RENOVATION, d("12"), null, BALANCE, d("50.00")))
                .contains(d("600.00"));
        assertThat(
                        SanctionRules.ceiling(
                                WithdrawalLimit.RENOVATION, d("12"), null, BALANCE, d("500.00")))
                .contains(d("1200.00"));
        assertThat(SanctionRules.ceiling(WithdrawalLimit.RENOVATION, d("12"), null, BALANCE, null))
                .isEmpty();
        // No multiplier configured: no ceiling, whatever the basis.
        for (WithdrawalLimit limit : WithdrawalLimit.values()) {
            assertThat(SanctionRules.ceiling(limit, null, d("1"), BALANCE, d("1")))
                    .as(limit.name())
                    .isEqualTo(Optional.empty());
        }
    }

    @Test
    void SRS_6_2_BR_CPF_008_anAdvanceIsCappedAtTheFractionOfTheOwnShare() {
        assertThat(SanctionRules.advanceCap(d("0.5"), BALANCE)).isEqualByComparingTo("600.00");
        assertThat(SanctionRules.advanceCap(d("0.333"), BALANCE)).isEqualByComparingTo("399.60");
    }

    @Test
    void SRS_6_26_aRequestAboveTheCeilingIsCappedNeverRejected() {
        assertThat(SanctionRules.cap(d("2000.00"), d("1620.00")))
                .isEqualTo(new SanctionRules.Capped(d("1620.00"), true));
        assertThat(SanctionRules.cap(d("1620.00"), d("1620.00")))
                .isEqualTo(new SanctionRules.Capped(d("1620.00"), false));
        assertThat(SanctionRules.cap(d("100.00"), d("1620.00")))
                .isEqualTo(new SanctionRules.Capped(d("100.00"), false));
    }

    @Test
    void BR_CPF_031_theSanctionNumberIsMonthYearZoneAndSequenceInThatOrder() {
        LocalDate day = LocalDate.of(2026, 3, 9);
        assertThat(SanctionRules.sanctionNumber(day, "NA11", 7, "/", 5))
                .isEqualTo("03/2026/NA11/00007");
        // A sequence already as wide as the setting, or wider, is never cut.
        assertThat(SanctionRules.sanctionNumber(day, "NA11", 12345, "-", 5))
                .isEqualTo("03-2026-NA11-12345");
        assertThat(SanctionRules.sanctionNumber(day, "NA11", 123456, "-", 5))
                .isEqualTo("03-2026-NA11-123456");
        assertThat(SanctionRules.sanctionNumber(LocalDate.of(2026, 12, 1), "SO11", 1, "", 0))
                .isEqualTo("122026SO111");
    }

    @Test
    void SRS_6_4_thePaymentIsBlockedOnlyWhenTheBalanceCannotCarryTheSanction() {
        assertThat(SanctionRules.paymentBlocked(BALANCE, d("1800.00"))).isFalse();
        assertThat(SanctionRules.paymentBlocked(BALANCE, d("1800.01"))).isTrue();
        assertThat(SanctionRules.paymentBlocked(BALANCE, d("0.01"))).isFalse();
    }

    @Test
    void SRS_6_14_aRequestCarriesAtMostFiveSupportingDocuments() {
        assertThat(SanctionRules.MAX_DOCUMENTS).isEqualTo(5);
        assertThat(code(() -> SanctionRules.requireDocumentCount(0))).isNull();
        assertThat(code(() -> SanctionRules.requireDocumentCount(5))).isNull();
        assertThat(code(() -> SanctionRules.requireDocumentCount(6)))
                .isEqualTo(CpfErrorCode.DOCUMENT_COUNT);
    }

    /**
     * R-04 (rounding-money, statutory output): a CPF ceiling is truncated to the paisa, never
     * rounded up, and a configured count or number of years is read as its whole part.
     */
    // SC-01-0018: a rule that prescribes truncation applies it to that output only.
    @Test
    void R_04_SRS_DM_9_ceilingsTruncateToThePaisaAndCountsToTheWholeNumber() {
        assertThat(
                        SanctionRules.ceiling(
                                WithdrawalLimit.MARRIAGE, d("1"), d("1234.567"), BALANCE, null))
                .contains(d("1234.56"));
        assertThat(
                        SanctionRules.ceiling(
                                WithdrawalLimit.BEFORE_RETIREMENT,
                                d("0.5"),
                                null,
                                new LedgerBalances(d("1000.005"), d("0"), d("0")),
                                null))
                .contains(d("500.00"));
        assertThat(
                        SanctionRules.advanceCap(
                                d("0.5"), new LedgerBalances(d("0.03"), d("0"), d("0"))))
                .isEqualByComparingTo("0.01");
        // 2.9 part-finals allowed means two: the third is refused.
        SanctionRules.requireCount(1, d("2.9"));
        assertThatThrownBy(() -> SanctionRules.requireCount(2, d("2.9")))
                .isInstanceOf(FciException.class)
                .satisfies(
                        e ->
                                assertThat(((FciException) e).getErrorCode().code())
                                        .isEqualTo("CPF-422-WITHDRAWAL-COUNT"));
    }

    /**
     * BR-CPF-002 ... 007 with the multipliers seeded from SRS-6-2 / SRS-6-24 (the CPF limit seed):
     * religious advance 3 x MGW, higher education 12 x MGW, part-final house 36 x MGW, plot 18 x
     * MGW, renovation the least of 12 x pay and (employee + VPF), before retirement 0.9 x (employee
     * + employer + VPF). MGW 15,000; pay 40,000; balance 1,000 + 600 + 200.
     */
    @Test
    void BR_CPF_001_to_007_eachPurposeIsCappedOnItsOwnBasis() {
        BigDecimal mgw = d("15000.00");
        assertThat(SanctionRules.ceiling(WithdrawalLimit.RELIGIOUS, d("3"), mgw, BALANCE, null))
                .as("BR-CPF-002")
                .contains(d("45000.00"));
        assertThat(
                        SanctionRules.ceiling(
                                WithdrawalLimit.HIGHER_EDUCATION, d("12"), mgw, BALANCE, null))
                .as("BR-CPF-003")
                .contains(d("180000.00"));
        assertThat(SanctionRules.ceiling(WithdrawalLimit.HOUSE, d("36"), mgw, BALANCE, null))
                .as("BR-CPF-004")
                .contains(d("540000.00"));
        assertThat(SanctionRules.ceiling(WithdrawalLimit.PLOT, d("18"), mgw, BALANCE, null))
                .as("BR-CPF-005")
                .contains(d("270000.00"));
        assertThat(
                        SanctionRules.ceiling(
                                WithdrawalLimit.RENOVATION, d("12"), mgw, BALANCE, d("40000.00")))
                .as("BR-CPF-006: the own share (1,000 + 200) is less than 12 x pay")
                .contains(d("1200.00"));
        assertThat(
                        SanctionRules.ceiling(
                                WithdrawalLimit.BEFORE_RETIREMENT, d("0.9"), mgw, BALANCE, null))
                .as("BR-CPF-007: 0.9 x 1,800")
                .contains(d("1620.00"));
        assertThat(WithdrawalLimit.RELIGIOUS.basis()).isEqualTo(WithdrawalLimit.Basis.MGW_MULTIPLE);
        assertThat(WithdrawalLimit.PLOT.basis()).isEqualTo(WithdrawalLimit.Basis.MGW_MULTIPLE);
        assertThat(WithdrawalLimit.RENOVATION.basis())
                .isEqualTo(WithdrawalLimit.Basis.PAY_MULTIPLE_OR_OWN_SHARE);
        assertThat(WithdrawalLimit.BEFORE_RETIREMENT.basis())
                .isEqualTo(WithdrawalLimit.Basis.BALANCE_FRACTION);
    }
}
