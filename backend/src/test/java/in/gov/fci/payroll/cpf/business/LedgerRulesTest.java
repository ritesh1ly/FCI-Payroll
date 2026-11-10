package in.gov.fci.payroll.cpf.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.cpf.business.InterestRateRules.FinYear;
import in.gov.fci.payroll.cpf.business.MemberTransitions.Stop;
import in.gov.fci.payroll.cpf.business.MemberTransitions.Transition;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader.MemberStatus;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The member ledger's rules: SRS-6-57 / BR-CPF-025 (no part negative), SRS-6-9 (the ex-member
 * interest stop) and BR-CPF-016 (deputation a distinct state).
 */
class LedgerRulesTest {

    private static LedgerBalances b(String e, String r, String v) {
        return new LedgerBalances(new BigDecimal(e), new BigDecimal(r), new BigDecimal(v));
    }

    private static FciException refused(Runnable call) {
        try {
            call.run();
        } catch (FciException e) {
            return e;
        }
        throw new AssertionError("not refused");
    }

    @Test
    void SRS_6_57_aChangeIsAppliedPerPartAndMayEmptyAPartExactly() {
        LedgerBalances before = b("100.00", "50.00", "10.00");
        assertThat(before.apply(b("10.50", "5", "0"))).isEqualTo(b("110.50", "55.00", "10.00"));
        LedgerBalances emptied = before.apply(b("-100.00", "-50", "-10.00"));
        assertThat(emptied.total()).isEqualByComparingTo("0");
        assertThat(before.total()).isEqualByComparingTo("160");
    }

    @Test
    void SRS_6_57_aChangeThatTakesAnyPartBelowZeroIsRefusedNamingThePart() {
        LedgerBalances before = b("100", "50", "10");
        assertThat(refused(() -> before.apply(b("-100.01", "0", "0"))).getMessageArgs())
                .containsExactly("EMPLOYEE");
        assertThat(refused(() -> before.apply(b("0", "-50.01", "0"))).getMessageArgs())
                .containsExactly("EMPLOYER");
        FciException vpf = refused(() -> before.apply(b("0", "0", "-10.01")));
        assertThat(vpf.getErrorCode().code()).isEqualTo("CPF-422-NEGATIVE");
        assertThat(vpf.getMessageArgs()).containsExactly("VPF");
        // A rise in one part never pays for a fall below zero in another.
        assertThat(refused(() -> before.apply(b("500", "-60", "0"))).getMessageArgs())
                .containsExactly("EMPLOYER");
    }

    @Test
    void SRS_6_57_partsAreReadByNameAndADebitIsTheNegation() {
        LedgerBalances x = b("1", "2", "3");
        assertThat(x.part(LedgerBalances.Part.EMPLOYEE)).isEqualByComparingTo("1");
        assertThat(x.part(LedgerBalances.Part.EMPLOYER)).isEqualByComparingTo("2");
        assertThat(x.part(LedgerBalances.Part.VPF)).isEqualByComparingTo("3");
        assertThat(x.negate()).isEqualTo(b("-1", "-2", "-3"));
        assertThat(LedgerBalances.ZERO.total()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> new LedgerBalances(null, BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LedgerBalances(BigDecimal.ONE, null, BigDecimal.ONE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LedgerBalances(BigDecimal.ONE, BigDecimal.ONE, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void SRS_6_9_theInterestOfAnExMemberStopsTheConfiguredWholeYearsAfterSeparation() {
        LocalDate separation = LocalDate.of(2026, 6, 30);
        assertThat(InterestStop.exMemberEnd(separation, new BigDecimal("3.0000"), false))
                .contains(LocalDate.of(2029, 6, 30));
        assertThat(InterestStop.exMemberEnd(LocalDate.of(2028, 2, 29), BigDecimal.ONE, false))
                .contains(LocalDate.of(2029, 2, 28));
    }

    /**
     * US-GAP-177 (SRS-6-9): a separation on 29 February stops interest three years later on 28
     * February, the last day of that month (OD-54); the end date is a day, so a part month after it
     * earns no interest (the amount waits for the interest method, Q-072); a later deputation of
     * the ex-member clears the stop, as a deputed member never carries it.
     */
    @Test
    void SRS_6_9_aLeapDaySeparationStopsOnTheLastDayOfFebruaryAndALaterDeputationClearsIt() {
        assertThat(
                        InterestStop.exMemberEnd(
                                LocalDate.of(2028, 2, 29), BigDecimal.valueOf(3), false))
                .contains(LocalDate.of(2031, 2, 28));
        assertThat(
                        InterestStop.exMemberEnd(
                                LocalDate.of(2028, 2, 29), BigDecimal.valueOf(4), false))
                .contains(LocalDate.of(2032, 2, 29));
        assertThat(InterestStop.exMemberEnd(LocalDate.of(2026, 3, 1), BigDecimal.valueOf(3), false))
                .contains(LocalDate.of(2029, 3, 1));
        assertThat(MemberTransitions.after("DEPUTATION_OUT", MemberStatus.EX_MEMBER, false))
                .contains(new Transition(MemberStatus.DEPUTATION, true, Stop.CLEAR));
    }

    @Test
    void SRS_6_9_noStopForADeputedMemberNorFromAValueThatIsNotWholeYears() {
        LocalDate separation = LocalDate.of(2026, 6, 30);
        assertThat(InterestStop.exMemberEnd(separation, BigDecimal.valueOf(3), true)).isEmpty();
        assertThat(InterestStop.exMemberEnd(null, BigDecimal.valueOf(3), false)).isEmpty();
        assertThat(InterestStop.exMemberEnd(separation, null, false)).isEmpty();
        assertThat(InterestStop.exMemberEnd(separation, BigDecimal.ZERO, false)).isEmpty();
        assertThat(InterestStop.exMemberEnd(separation, new BigDecimal("-3"), false)).isEmpty();
        assertThat(InterestStop.exMemberEnd(separation, new BigDecimal("2.5"), false)).isEmpty();
    }

    @Test
    void SRS_6_9_separationMakesAnExMemberWithTheStopUnlessOnDeputation() {
        assertThat(MemberTransitions.after("SEPARATION", MemberStatus.ACTIVE, false))
                .contains(new Transition(MemberStatus.EX_MEMBER, false, Stop.SET));
        assertThat(MemberTransitions.after("DISMISSAL", MemberStatus.ACTIVE, false))
                .contains(new Transition(MemberStatus.EX_MEMBER, false, Stop.SET));
        assertThat(MemberTransitions.after("SEPARATION", MemberStatus.DEPUTATION, true))
                .contains(new Transition(MemberStatus.EX_MEMBER, true, Stop.KEEP));
    }

    @Test
    void SRS_6_9_reinstatementClearsTheStopAndDeputationIsADistinctState() {
        assertThat(MemberTransitions.after("REINSTATEMENT", MemberStatus.EX_MEMBER, false))
                .contains(new Transition(MemberStatus.ACTIVE, false, Stop.CLEAR));
        assertThat(MemberTransitions.after("REINSTATEMENT", MemberStatus.EX_MEMBER, true))
                .contains(new Transition(MemberStatus.DEPUTATION, true, Stop.CLEAR));
        assertThat(MemberTransitions.after("DEPUTATION_OUT", MemberStatus.ACTIVE, false))
                .contains(new Transition(MemberStatus.DEPUTATION, true, Stop.CLEAR));
        assertThat(MemberTransitions.after("DEPUTATION_IN", MemberStatus.DEPUTATION, true))
                .contains(new Transition(MemberStatus.ACTIVE, false, Stop.KEEP));
    }

    @Test
    void BR_CPF_016_aClosedLedgerOrAnOtherEventIsNotMoved() {
        for (MemberStatus closed :
                new MemberStatus[] {
                    MemberStatus.FINALLY_SETTLED, MemberStatus.CLOSED_ZONE_CHANGE
                }) {
            assertThat(MemberTransitions.after("SEPARATION", closed, false)).isEmpty();
        }
        assertThat(MemberTransitions.after("SUSPENSION", MemberStatus.ACTIVE, false)).isEmpty();
        assertThat(MemberTransitions.after(null, MemberStatus.ACTIVE, false)).isEmpty();
        assertThat(MemberTransitions.after("", MemberStatus.EX_MEMBER, false))
                .isEqualTo(Optional.empty());
    }

    @Test
    void BR_CPF_033_theFinancialYearOfADayAndTheYearBefore() {
        assertThat(FinYear.containing(LocalDate.of(2026, 3, 31)).code()).isEqualTo("2025-26");
        FinYear y = FinYear.containing(LocalDate.of(2026, 4, 1));
        assertThat(y.code()).isEqualTo("2026-27");
        assertThat(y.start()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(y.end()).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(y.previous().code()).isEqualTo("2025-26");
        assertThat(FinYear.containing(LocalDate.of(2099, 12, 1)).code()).isEqualTo("2099-00");
        assertThat(FinYear.containing(LocalDate.of(2026, 4, 1)))
                .isEqualTo(InterestRateRules.finYear("2026-27"));
    }
}
