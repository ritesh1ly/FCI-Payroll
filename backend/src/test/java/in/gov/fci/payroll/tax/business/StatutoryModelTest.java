package in.gov.fci.payroll.tax.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.tax.business.model.Form16Certificate;
import in.gov.fci.payroll.tax.business.model.OutputControlTotal;
import in.gov.fci.payroll.tax.business.model.OutputVersion;
import in.gov.fci.payroll.tax.business.model.ReturnDeductee;
import in.gov.fci.payroll.tax.business.model.StatutoryReturn;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The statutory-output entities (SRS M-17, BR-MIS-002, SRS-7-8, SRS-7-9, SRS-7-23): which version
 * governs a day, a control total that is no gate, the masked PAN of a deductee, a long validation
 * result, and a certificate regenerated before and after release.
 */
class StatutoryModelTest {

    private static final Instant NOW = Instant.parse("2026-10-25T06:00:00Z");
    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);

    private static OutputVersion version() {
        return OutputVersion.approved(
                4L, 1, APRIL, "PDF", null, null, true, null, 1L, "admin", NOW);
    }

    @Test
    void BR_MIS_002_aVersionGovernsFromItsStartToItsEndOnlyWhenApprovedAndNotBlocked() {
        OutputVersion v = version();
        assertThat(v.governs(APRIL.minusDays(1))).isFalse();
        assertThat(v.governs(APRIL)).isTrue();
        v.endBefore(APRIL.plusYears(1));
        assertThat(v.governs(APRIL.plusYears(1).minusDays(1))).isTrue();
        assertThat(v.governs(APRIL.plusYears(1))).isFalse();
        OutputVersion blocked = version();
        ReflectionTestUtils.setField(blocked, "blocked", "Y");
        assertThat(blocked.governs(APRIL)).isFalse();
        OutputVersion proposed = version();
        ReflectionTestUtils.setField(proposed, "approvalState", "PROPOSED");
        assertThat(proposed.governs(APRIL)).isFalse();
        assertThat(v.isPeriodLockRequired()).isTrue();
    }

    @Test
    void SRS_7_8_aControlTotalIsAReleaseGateOnlyWhenSoDefined() {
        OutputControlTotal gate = OutputControlTotal.of(7L, "TDS", "CHALLAN", BigDecimal.ONE, true);
        OutputControlTotal info =
                OutputControlTotal.of(7L, "Info", "POSTED_PAYROLL", BigDecimal.ZERO, false);
        assertThat(gate.isReleaseGate()).isTrue();
        assertThat(info.isReleaseGate()).isFalse();
        assertThat(gate.getToleranceAmount()).isEqualByComparingTo("1");
    }

    @Test
    void SRS_7_23_aDeducteesPanIsShownMaskedOrNotAtAll() {
        ReturnDeductee d =
                ReturnDeductee.of(
                        1L, 9L, "KLKPK7001A", true, true, BigDecimal.TEN, BigDecimal.ONE, 3L);
        assertThat(d.maskedPan()).isEqualTo("******001A");
        assertThat(d.isPanValid()).isTrue();
        assertThat(d.isHigherRateApplied()).isTrue();
        assertThat(d.isInReturn()).isTrue();
        d.refigured(null, false, false, BigDecimal.ZERO, BigDecimal.ZERO, null);
        assertThat(d.maskedPan()).isNull();
        assertThat(d.isPanValid()).isFalse();
        assertThat(d.isInReturn()).isFalse();
        d.refigured("ABC", false, false, BigDecimal.ZERO, null, null);
        assertThat(d.maskedPan()).isEqualTo("****");
        assertThat(d.isInReturn()).isFalse();
        d.panRefreshed("KLKPK7001A", true);
        assertThat(d.isPanValid()).isTrue();
    }

    @Test
    void SRS_7_8_aValidationResultIsKeptToTheColumnAndARegeneratedReturnStartsAgain() {
        StatutoryReturn r =
                StatutoryReturn.generated("FORM_24Q", "2026-27", "Q1", 50L, 7L, null, NOW);
        r.validated(false, null, "x".repeat(2500));
        assertThat(r.getValidationResult()).hasSize(2000);
        assertThat(r.status()).isEqualTo(StatutoryReturn.Status.GENERATED);
        r.validated(true, "FVU", null);
        assertThat(r.getValidationResult()).isNull();
        assertThat(r.isRegenerable()).isTrue();
        r.regenerated(8L, NOW);
        assertThat(r.isValidated()).isFalse();
        assertThat(r.getDefinitionVersionId()).isEqualTo(8L);
        r.released("checker", NOW);
        assertThat(r.isRegenerable()).isFalse();
    }

    @Test
    void SRS_NF_7_aReturnTheJobIsWritingIsDraftUntilFinishedAndMayBeGeneratedAgain() {
        StatutoryReturn r =
                StatutoryReturn.generated("FORM_24Q", "2026-27", "Q2", 50L, 7L, null, NOW);
        r.drafting();
        assertThat(r.status()).isEqualTo(StatutoryReturn.Status.DRAFT);
        assertThat(r.isRegenerable()).as("an abandoned run is generated again").isTrue();
        r.generationFinished();
        assertThat(r.status()).isEqualTo(StatutoryReturn.Status.GENERATED);
        r.validated(true, "FVU", null);
        r.generationFinished();
        assertThat(r.status())
                .as("only a draft is moved on")
                .isEqualTo(StatutoryReturn.Status.VALIDATED);
    }

    // SC-08-0050: Part B is held for the merge until Part A is received.
    @Test
    void SRS_7_9_aCertificateRegeneratedBeforeReleaseKeepsItsStateAfterReleaseGoesBackToMerged() {
        Form16Certificate f = Form16Certificate.generated(9L, "2026-27", null, null, null, 7L, NOW);
        assertThat(f.image())
                .containsEntry("gross_salary", null)
                .containsEntry("tax_deducted", null);
        f.regenerate(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, 8L, NOW);
        assertThat(f.status()).isEqualTo(Form16Certificate.Status.PENDING_PART_A);
        f.partAMerged(31L, NOW);
        f.released(NOW);
        f.regenerate(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, 8L, NOW);
        assertThat(f.status()).isEqualTo(Form16Certificate.Status.MERGED);
        assertThat(f.getReleasedAt()).isNull();
        assertThat(f.getMergedAt()).isEqualTo(NOW);
        assertThat(f.image()).containsEntry("gross_salary", "10");
    }
}
