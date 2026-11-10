package in.gov.fci.payroll.cpf.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.cpf.business.model.PfInterestRate;
import in.gov.fci.payroll.cpf.orchestration.InterestRateService.RateRequest;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SRS-6-11, BR-CPF-033: what an M-19 proposal carries and how a rate's status is derived. */
class InterestRateServiceTest {

    private static final Instant AT = Instant.parse("2026-10-23T05:00:00Z");
    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);

    private static RateRequest request(String fund, String source, String note) {
        return new RateRequest(
                "2026-27",
                fund,
                source,
                new BigDecimal("8.25"),
                APRIL,
                null,
                note,
                null,
                true,
                "Declared by the Board");
    }

    private static PfInterestRate rate(String year, String source, LocalDate from) {
        return PfInterestRate.approved(
                year,
                "CPF",
                PfInterestRate.Source.valueOf(source),
                "X",
                BigDecimal.ONE,
                from,
                null,
                null,
                null,
                false,
                1L);
    }

    @Test
    void SRS_6_11_aValidProposalCarriesItsSourceAndAStrippedNote() {
        InterestRateService.RateProposal p =
                InterestRateService.validated(
                        request("VPF", "RPFC", "  up to the retirement month  "), "maker", AT);
        assertThat(p.kind()).isEqualTo(InterestRateService.Kind.RECORD);
        assertThat(p.rateId()).isNull();
        assertThat(p.fundType()).isEqualTo("VPF");
        assertThat(p.rateSource()).isEqualTo("RPFC");
        assertThat(p.applicabilityNote()).isEqualTo("up to the retirement month");
        assertThat(p.declared()).isTrue();
        assertThat(p.proposedBy()).isEqualTo("maker");
        assertThat(p.proposedAt()).isEqualTo(AT);
        assertThat(
                        InterestRateService.validated(request("CPF", "FCI_TRUST", null), "m", AT)
                                .applicabilityNote())
                .isNull();
    }

    @Test
    void BR_CPF_033_aFundOrSourceOutsideTheDdlListIsRefused() {
        for (RateRequest bad :
                List.of(
                        request(null, "RPFC", null),
                        request("NPS", "RPFC", null),
                        request("CPF", "EPFO_FEED", null),
                        request("CPF", null, null))) {
            assertThatThrownBy(() -> InterestRateService.validated(bad, "m", AT))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e ->
                                    assertThat(e.getErrorCode().code())
                                            .isEqualTo("COM-400-VALIDATION"));
        }
    }

    @Test
    void BR_CPF_033_aNoteIsNeitherBlankNorLongerThanItsColumn() {
        assertThatThrownBy(
                        () -> InterestRateService.validated(request("CPF", "RPFC", "   "), "m", AT))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(
                        () ->
                                InterestRateService.validated(
                                        request("CPF", "RPFC", "n".repeat(501)), "m", AT))
                .isInstanceOf(FciException.class);
        assertThat(
                        InterestRateService.validated(
                                        request("CPF", "RPFC", "n".repeat(500)), "m", AT)
                                .applicabilityNote())
                .hasSize(500);
    }

    @Test
    void BR_CPF_033_aRateIsSupersededOnlyByALaterRateOfTheSameYearFundAndSource() {
        PfInterestRate first = rate("2026-27", "FCI_TRUST", APRIL);
        PfInterestRate later = rate("2026-27", "FCI_TRUST", APRIL.plusMonths(6));
        PfInterestRate otherSource = rate("2026-27", "RPFC", APRIL.plusMonths(9));
        PfInterestRate otherYear = rate("2027-28", "FCI_TRUST", APRIL.plusYears(1));
        List<PfInterestRate> all = List.of(first, later, otherSource, otherYear);
        assertThat(InterestRateService.superseded(first, all)).isTrue();
        assertThat(InterestRateService.superseded(later, all)).isFalse();
        assertThat(InterestRateService.superseded(otherSource, all)).isFalse();
        assertThat(InterestRateService.superseded(otherYear, all)).isFalse();
        PfInterestRate otherFund =
                PfInterestRate.approved(
                        "2026-27",
                        "VPF",
                        PfInterestRate.Source.FCI_TRUST,
                        "X",
                        BigDecimal.ONE,
                        APRIL.plusMonths(8),
                        null,
                        null,
                        null,
                        false,
                        2L);
        assertThat(InterestRateService.superseded(later, List.of(later, otherFund))).isFalse();
    }

    @Test
    void SRS_20_2_theAuditImageHoldsEveryFieldM19Shows() {
        PfInterestRate r =
                PfInterestRate.approved(
                        "2026-27",
                        "CPF",
                        PfInterestRate.Source.FCI_TRUST,
                        "ACTIVE_AND_DEPUTED",
                        new BigDecimal("9.5000"),
                        APRIL,
                        APRIL.plusMonths(11).minusDays(1),
                        "note",
                        new BigDecimal("2.5000"),
                        true,
                        7L);
        assertThat(InterestRateService.image(r))
                .containsEntry("rate_percent", "9.5")
                .containsEntry("applies_from", "2026-04-01")
                .containsEntry("applies_to", "2027-02-28")
                .containsEntry("member_population", "ACTIVE_AND_DEPUTED")
                .containsEntry("applicability_note", "note")
                .containsEntry("bonus_interest_pct", "2.5")
                .containsEntry("is_declared", "Y");
        PfInterestRate bare = rate("2026-27", "RPFC", APRIL);
        assertThat(InterestRateService.image(bare))
                .containsEntry("applies_to", null)
                .containsEntry("bonus_interest_pct", null)
                .containsEntry("is_declared", "N");
        bare.declare(new BigDecimal("8.65"));
        assertThat(InterestRateService.image(bare))
                .containsEntry("rate_percent", "8.65")
                .containsEntry("is_declared", "Y");
        bare.endBefore(APRIL.plusMonths(1));
        assertThat(bare.getAppliesTo()).isEqualTo(APRIL.plusMonths(1).minusDays(1));
    }
}
