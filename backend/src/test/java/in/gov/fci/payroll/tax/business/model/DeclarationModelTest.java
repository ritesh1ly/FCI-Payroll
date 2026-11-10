package in.gov.fci.payroll.tax.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.tax.business.model.ItDeclaration.Kind;
import in.gov.fci.payroll.tax.business.model.ItDeclarationSubmission.Status;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail.Regime;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * SRS-7-1, SRS-7-15, SRS-7-24, BR-TAX-001, BR-TAX-014: the states of a regime, a round and a line
 * as the DDL holds them.
 */
class DeclarationModelTest {

    private static final Instant NOW = Instant.parse("2026-10-25T06:00:00Z");

    @Test
    void BR_TAX_001_theRegimeIsHeldAsTheDdlCodeAndFrozenOnlyOnApproval() {
        assertThat(Regime.OLD.code()).isEqualTo("O");
        assertThat(Regime.NEW.code()).isEqualTo("N");
        assertThat(Regime.ofCode("O")).isEqualTo(Regime.OLD);
        assertThat(Regime.ofCode("N")).isEqualTo(Regime.NEW);
        ItRegimeDetail r = ItRegimeDetail.elected(9L, "2026-27", Regime.NEW, NOW);
        assertThat(r.getRegime()).isEqualTo(Regime.NEW);
        assertThat(r.isLocked()).isFalse();
        r.elect(Regime.OLD, NOW);
        assertThat(r.getRegime()).isEqualTo(Regime.OLD);
        r.verified("maker", NOW);
        assertThat(r.getVerifyStatus()).isEqualTo("VERIFIED");
        r.approvedAndFrozen(5L);
        assertThat(r.isLocked()).isTrue();
        assertThat(r.getVerifyStatus()).isEqualTo("APPROVED");
        r.reset(6L, "wrong regime");
        assertThat(r.isLocked()).isFalse();
        assertThat(r.getVerifyStatus()).isEqualTo("SUBMITTED");
        assertThat(r.getResetReason()).isEqualTo("wrong regime");
    }

    @Test
    void BR_TAX_014_aRoundAtTheCheckerIsTheOnlyOneANewRoundCannotFollow() {
        ItDeclarationSubmission s = ItDeclarationSubmission.submitted(9L, "2026-27", 1, 3L, NOW);
        assertThat(s.status()).isEqualTo(Status.SUBMITTED);
        assertThat(s.replaceable()).isTrue();
        s.verified("maker", NOW, 5L);
        assertThat(s.replaceable()).isFalse();
        assertThat(s.getApprovalId()).isEqualTo(5L);
        s.approved();
        assertThat(s.replaceable()).isTrue();
        s.returned("proof unreadable");
        assertThat(s.replaceable()).isTrue();
        assertThat(s.getReturnReason()).isEqualTo("proof unreadable");
        s.supersede();
        assertThat(s.status()).isEqualTo(Status.SUPERSEDED);
        assertThat(s.replaceable()).isFalse();
    }

    @Test
    void SRS_7_15_aLineKnowsItsProofAndOnlyARentLineIsFrozenOnApprovalAndCarriedAsVerified() {
        ItDeclaration noProof =
                ItDeclaration.declared(
                        9L,
                        1L,
                        "2026-27",
                        Kind.INVESTMENT,
                        "80C",
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        null,
                        NOW);
        assertThat(noProof.getProofStatus()).isEqualTo("DECLARED");
        assertThat(noProof.hasProof()).isFalse();
        assertThat(noProof.kind()).isEqualTo(Kind.INVESTMENT);
        noProof.approved(5L);
        assertThat(noProof.isFrozen()).isFalse();
        assertThat(noProof.getProofStatus()).isEqualTo("APPROVED");

        ItDeclaration rent =
                ItDeclaration.declared(
                        9L,
                        1L,
                        "2026-27",
                        Kind.HRA_RENT,
                        "10(13A)",
                        new BigDecimal("120000"),
                        new BigDecimal("120000"),
                        77L,
                        NOW);
        assertThat(rent.getProofStatus()).isEqualTo("PROOF_SUBMITTED");
        assertThat(rent.kind()).isEqualTo(Kind.HRA_RENT);
        rent.verified(new BigDecimal("110000"), "maker", NOW);
        rent.approved(5L);
        assertThat(rent.isFrozen()).isTrue();

        ItDeclaration carried = rent.carriedInto(2L, NOW);
        assertThat(carried.getSubmissionId()).isEqualTo(2L);
        assertThat(carried.isFrozen()).isTrue();
        assertThat(carried.kind()).isEqualTo(Kind.HRA_RENT);
        assertThat(carried.getProofStatus()).isEqualTo("VERIFIED");
        assertThat(carried.getVerifiedAmount()).isEqualByComparingTo("110000");
        assertThat(carried.getAttachmentId()).isEqualTo(77L);
        assertThat(carried.getAllowedAmount()).isEqualByComparingTo("120000");
    }
}
