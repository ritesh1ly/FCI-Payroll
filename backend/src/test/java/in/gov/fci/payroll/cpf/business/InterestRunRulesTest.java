package in.gov.fci.payroll.cpf.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.cpf.business.InterestRunRules.Scope;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader.MemberStatus;
import in.gov.fci.payroll.cpf.business.model.PfInterestRate;
import in.gov.fci.payroll.cpf.business.model.PfInterestRate.Source;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SRS-6-45, SRS-6-46, SRS-6-11, SRS-6-9: the rules of a C-12 interest run. */
class InterestRunRulesTest {

    private static final InterestRateRules.FinYear FY = InterestRateRules.finYear("2026-27");

    private static PfInterestRate rate(Source source, boolean declared) {
        return PfInterestRate.approved(
                "2026-27",
                "CPF",
                source,
                InterestRateRules.population(source),
                new BigDecimal("8.25"),
                FY.start(),
                null,
                null,
                null,
                declared,
                1L);
    }

    private static FciException refusal(Runnable call) {
        try {
            call.run();
        } catch (FciException e) {
            return e;
        }
        return null;
    }

    @Test
    void SRS_6_11_theFciRateWhileActiveOrDeputedTheEpfoRateWhileInactive() {
        assertThat(InterestRunRules.sourceOf(MemberStatus.ACTIVE)).contains(Source.FCI_TRUST);
        assertThat(InterestRunRules.sourceOf(MemberStatus.DEPUTATION)).contains(Source.FCI_TRUST);
        assertThat(InterestRunRules.sourceOf(MemberStatus.EX_MEMBER)).contains(Source.RPFC);
        // Settled and zone-closed ledgers take their interest on their own paths, not in the run.
        assertThat(InterestRunRules.sourceOf(MemberStatus.FINALLY_SETTLED)).isEmpty();
        assertThat(InterestRunRules.sourceOf(MemberStatus.CLOSED_ZONE_CHANGE)).isEmpty();
    }

    @Test
    void SRS_6_45_aRunNeedsEveryRateItsMembersNeedApprovedAndDeclared() {
        Map<Source, List<PfInterestRate>> both =
                Map.of(
                        Source.FCI_TRUST, List.of(rate(Source.FCI_TRUST, true)),
                        Source.RPFC, List.of(rate(Source.RPFC, true)));
        InterestRunRules.requireRates(FY, EnumSet.allOf(Source.class), both);
        // A source no member needs is not asked for.
        InterestRunRules.requireRates(
                FY,
                EnumSet.of(Source.FCI_TRUST),
                Map.of(Source.FCI_TRUST, List.of(rate(Source.FCI_TRUST, true))));
        InterestRunRules.requireRates(FY, EnumSet.noneOf(Source.class), Map.of());

        FciException missing =
                refusal(
                        () ->
                                InterestRunRules.requireRates(
                                        FY,
                                        EnumSet.of(Source.RPFC),
                                        Map.of(Source.RPFC, List.of())));
        assertThat(missing.getErrorCode()).isEqualTo(CpfErrorCode.RATE_MISSING);
        assertThat(missing.getMessageArgs()).containsExactly("2026-27", "RPFC");
        assertThat(
                        refusal(
                                        () ->
                                                InterestRunRules.requireRates(
                                                        FY, EnumSet.of(Source.FCI_TRUST), Map.of()))
                                .getErrorCode())
                .isEqualTo(CpfErrorCode.RATE_MISSING);

        // One undeclared rate in the series is enough: never fall back to last year's.
        FciException undeclared =
                refusal(
                        () ->
                                InterestRunRules.requireRates(
                                        FY,
                                        EnumSet.of(Source.FCI_TRUST),
                                        Map.of(
                                                Source.FCI_TRUST,
                                                List.of(
                                                        rate(Source.FCI_TRUST, true),
                                                        rate(Source.FCI_TRUST, false)))));
        assertThat(undeclared.getErrorCode()).isEqualTo(CpfErrorCode.RATE_UNDECLARED);
        assertThat(undeclared.getMessageArgs()).containsExactly("2026-27", "FCI_TRUST");
    }

    @Test
    void SRS_6_9_noInterestBeyondTheEndDateSoAnEndBeforeTheYearEndStopsIt() {
        assertThat(InterestRunRules.stoppedIn(FY, null)).isFalse();
        assertThat(InterestRunRules.stoppedIn(FY, LocalDate.of(2027, 3, 31))).isFalse();
        assertThat(InterestRunRules.stoppedIn(FY, LocalDate.of(2028, 1, 1))).isFalse();
        assertThat(InterestRunRules.stoppedIn(FY, LocalDate.of(2027, 3, 30))).isTrue();
        assertThat(InterestRunRules.stoppedIn(FY, LocalDate.of(2020, 1, 1))).isTrue();
    }

    @Test
    void SRS_6_45_aMemberIsNotCalculatedForTheCpfCodeOrTheOpenMethod() {
        assertThat(InterestRunRules.notCalculatedReason(false)).isEqualTo("CPF_CODE_MISSING");
        assertThat(InterestRunRules.notCalculatedReason(true))
                .isEqualTo("INTEREST_METHOD_NOT_CONFIGURED");
    }

    @Test
    void SRS_6_45_anIndividualRunNamesItsMemberAnAllMembersRunNone() {
        InterestRunRules.requireScope(Scope.ALL_MEMBERS, null);
        InterestRunRules.requireScope(Scope.ALL_MEMBERS, " ");
        InterestRunRules.requireScope(Scope.INDIVIDUAL, "104882");
        for (Runnable bad :
                List.<Runnable>of(
                        () -> InterestRunRules.requireScope(Scope.INDIVIDUAL, null),
                        () -> InterestRunRules.requireScope(Scope.INDIVIDUAL, ""),
                        () -> InterestRunRules.requireScope(Scope.ALL_MEMBERS, "104882"),
                        () -> InterestRunRules.requireScope(null, null))) {
            assertThatThrownBy(bad::run)
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(CpfErrorCode.RUN_SCOPE));
        }
    }

    @Test
    void SRS_6_46_aZonesYearIsKeyedByYearAndZone() {
        assertThat(InterestRunRules.yearKey("2026-27", "NA11")).isEqualTo("2026-27|NA11");
    }
}
