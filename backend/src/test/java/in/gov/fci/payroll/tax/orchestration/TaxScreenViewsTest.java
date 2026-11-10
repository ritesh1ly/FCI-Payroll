package in.gov.fci.payroll.tax.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The reads of the drawn M-17, I-07, C-07, C-10 and C-11 (OD-121; SRS-7-1, SRS-7-7, SRS-7-23,
 * SRS-9-1, BR-TAX-010): each is gated on its screen, stays inside the session's offices, invents no
 * figure, and masks the PAN.
 */
class TaxScreenViewsTest {

    private static final PageSpec SPEC = new PageSpec(0, 20);
    private static final String YEAR = "2026-27";

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final Authorization policy = mock(Authorization.class);
    private final TaxComputationService computation = mock(TaxComputationService.class);
    private final DeclarationService declarations = mock(DeclarationService.class);
    private final OutputDefinitionService outputs = mock(OutputDefinitionService.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final TaxScreenViews views =
            new TaxScreenViews(
                    db.named,
                    policy,
                    computation,
                    declarations,
                    outputs,
                    documents,
                    Clock.fixed(Instant.parse("2026-11-02T06:00:00Z"), ZoneOffset.UTC));

    private static AccessContext user(Set<Long> offices) {
        return new AccessContext(
                3L,
                "maker@x",
                "User",
                "s",
                "P",
                300L,
                null,
                List.of(),
                Map.of(),
                Set.of(),
                Set.of("PAYROLL_MAKER"),
                offices);
    }

    private final AccessContext maker = user(Set.of(300L));
    private final AccessContext nowhere = user(Set.of());

    @Test
    void BR_MIS_002_theOutputsCarryTheirFamilyAndTheFourCarriedForwardLayouts() {
        db.rows(
                "FROM report_definition d WHERE",
                output("PT_SCHEDULE", "STATUTORY_OUTPUT"),
                output("CPF_CONTRIBUTION_STATEMENT", "REPORT"),
                output("NPS_DEDUCTION_REPORT", "REPORT"),
                output("FORM_24Q", "STATUTORY_OUTPUT"));
        db.value("FROM report_definition_version v JOIN", 3L);

        TaxScreenViews.OutputSummary s = views.outputSummary(maker);

        assertThat(s.carriedForward()).isEqualTo(3);
        assertThat(s.superseded()).isEqualTo(3);
        assertThat(s.families()).isEqualTo(4);
        assertThat(s.outputs())
                .extracting(TaxScreenViews.OutputLine::family)
                .containsExactly(
                        "PROFESSIONAL_TAX", "PROVIDENT_FUND", "NPS_DEDUCTION", "INCOME_TAX");
        assertThat(s.outputs().get(1).catalogueReport()).isTrue();
        assertThat(s.outputs().get(3).carriedForward()).isFalse();
    }

    private static Map<String, Object> output(String key, String domain) {
        return row(
                "REPORT_KEY",
                key,
                "REPORT_NAME",
                key,
                "PERIODICITY",
                "Monthly",
                "LOCK_REQUIREMENT",
                "POST_LOCK",
                "STATUTORY_REFERENCE",
                null,
                "OUTPUT_DOMAIN",
                domain);
    }

    @Test
    void SRS_G_2_aSessionWithoutAnOfficeReadsNothingAndNoQueryRuns() {
        assertThat(views.declarationMembers(nowhere, YEAR, SPEC).items()).isEmpty();
        assertThat(views.epsLines(nowhere, SPEC).total()).isZero();
        assertThat(views.taxLines(nowhere, SPEC).items()).isEmpty();
        assertThat(views.npsLines(nowhere, SPEC).items()).isEmpty();
        assertThat(views.certificateLines(nowhere, "2025-26", null, null, SPEC).items()).isEmpty();
        assertThat(views.returnLines(nowhere, null, SPEC).items()).isEmpty();
        assertThat(views.certificateSummary(nowhere, "2025-26").due()).isZero();
        assertThat(db.queries("FROM employee e")).isEmpty();
        assertThat(db.queries("FROM form16 f")).isEmpty();
        verifyNoInteractions(computation);
    }

    @Test
    void SRS_G_2_aSummaryWithoutAnOfficeKeepsTheConfigurationAndCountsNothing() {
        db.rows(
                "FROM it_section_master s",
                row(
                        "SECTION_CODE",
                        "80C",
                        "MAX_DEDUCTION_AMOUNT",
                        new BigDecimal("150000"),
                        "REGIME_APPLICABILITY",
                        "OLD",
                        "EFFECTIVE_FROM",
                        Date.valueOf(LocalDate.of(2026, 4, 1))));

        TaxScreenViews.DeclarationSummary s = views.declarationSummary(nowhere, YEAR);

        assertThat(s.members()).isZero();
        assertThat(s.sections()).hasSize(1);
        assertThat(views.computationSummary(nowhere).types()).isEmpty();
        assertThat(views.returnSummary(nowhere, YEAR, "Q1").status()).isNull();
    }

    @Test
    void SRS_7_26_aRuleNotConfiguredIsNoLineAndTheDeclarationCountsAddUp() {
        db.value("SELECT COUNT(*) FROM employee e", 10L);
        db.value("AND r.regime_code = 'N'", 6L);
        db.value("AND r.regime_code = 'O'", 3L);
        db.value("IS NOT NULL", 5L);
        db.value("d.allowed_amount <", 1L);
        db.value("d.proof_status NOT IN", 2L);

        TaxScreenViews.DeclarationSummary s = views.declarationSummary(maker, YEAR);

        assertThat(s.notSelected()).isEqualTo(1);
        assertThat(s.received()).isEqualTo(5);
        assertThat(s.pending()).isEqualTo(5);
        assertThat(s.capped()).isEqualTo(1);
        assertThat(s.unproved()).isEqualTo(2);
        assertThat(s.sections()).isEmpty();
    }

    @Test
    void SRS_G_30_theCardOpensOnTheLatestRoundWithAStoredOrderInScope() {
        db.value("it_declaration_order", 77L);

        assertThat(views.declarationSummary(maker, YEAR).orderRound()).isEqualTo(77L);
        assertThat(views.declarationSummary(nowhere, YEAR).orderRound()).isNull();
    }

    @Test
    void SRS_G_30_noStoredOrderLeavesTheCardOnTheRowSelected() {
        db.value("it_declaration_order", ScriptedJdbc.NULL);

        assertThat(views.declarationSummary(maker, YEAR).orderRound()).isNull();
    }

    @Test
    void SRS_7_23_aPanIsMaskedAndAMissingOneIsNamed() {
        db.value("SELECT COUNT(*) FROM form16 f", 2L);
        db.rows(
                "SELECT f.form16_id",
                certificate(5L, "ABCPS1234K", "RELEASED", 9L),
                certificate(6L, null, "PENDING_PART_A", null));

        var lines = views.certificateLines(maker, "2025-26", "RELEASED", 300L, SPEC);

        assertThat(lines.total()).isEqualTo(2);
        assertThat(lines.items().get(0).panMasked()).doesNotContain("PS1234");
        assertThat(lines.items().get(0).partA()).isTrue();
        assertThat(lines.items().get(0).status()).isEqualTo("RELEASED");
        assertThat(lines.items().get(1).panStatus()).isEqualTo("MISSING");
        assertThat(lines.items().get(1).partA()).isFalse();
        assertThat(db.queries("AND f.release_status = :status AND e.location_id = :office"))
                .isNotEmpty();
    }

    private static Map<String, Object> certificate(Long id, String pan, String status, Long partA) {
        return row(
                "FORM16_ID",
                id,
                "EMP_NUM",
                "104882",
                "FIRST_NAME",
                "R.",
                "LAST_NAME",
                "Sharma",
                "PAN_NUMBER",
                pan,
                "REGIME_CODE",
                "N",
                "TAXABLE_INCOME",
                BigDecimal.TEN,
                "TAX_DEDUCTED",
                BigDecimal.ONE,
                "PART_B_GENERATED_AT",
                null,
                "PART_A_ATTACHMENT_ID",
                partA,
                "SIGNED_AT",
                null,
                "RELEASE_STATUS",
                status);
    }

    @Test
    void SRS_7_7_aQuarterWithoutAReturnSaysSoAndNamesTheDefinitionInForce() {
        db.rows(
                "FROM report_definition_version v JOIN report_definition d",
                row("VERSION_NO", 2, "EFFECTIVE_FROM", Date.valueOf(LocalDate.of(2026, 4, 1))));

        TaxScreenViews.ReturnSummary s = views.returnSummary(maker, YEAR, "Q3");

        assertThat(s.status()).isNull();
        assertThat(s.definitionVersion()).isEqualTo(2);
        assertThat(s.definitionFrom()).isEqualTo(LocalDate.of(2026, 4, 1));
    }

    @Test
    void SRS_7_21_aReturnCountsItsDeducteesChallansAndPanExceptions() {
        db.rows(
                "FROM statutory_return r WHERE r.return_type = 'FORM_24Q'",
                row(
                        "RETURN_ID",
                        7L,
                        "RETURN_STATUS",
                        "GENERATED",
                        "VALIDATED_FLAG",
                        "N",
                        "TOTAL_AMOUNT",
                        new BigDecimal("184500")));
        db.value("SELECT COUNT(*) FROM statutory_return_deductee d", 10L);
        db.value("d.pan_valid_flag = 'N'", 1L);
        db.value("FROM tax_challan c", 3L);
        db.value("COUNT(DISTINCT d.challan_id)", 2L);

        TaxScreenViews.ReturnSummary s = views.returnSummary(maker, YEAR, "Q2");

        assertThat(s.status()).isEqualTo("GENERATED");
        assertThat(s.validated()).isFalse();
        assertThat(s.panExceptions()).isEqualTo(1);
        assertThat(s.challans()).isEqualTo(3);
        assertThat(s.challansMapped()).isEqualTo(2);
    }

    @Test
    void BR_CYC_007_noLockedPeriodMeansNoLineAndNoAssumedPeriod() {
        assertThat(views.epsLines(maker, SPEC).items()).isEmpty();
    }

    @Test
    void SRS_G_30_anOrderIsReadAndStoredOnlyAfterTheRoundIsInScope() {
        doThrow(new FciException(CommonErrorCode.NOT_FOUND)).when(declarations).get(maker, 99L);

        assertThatThrownBy(() -> views.orders(maker, 99L)).isInstanceOf(FciException.class);
        assertThatThrownBy(() -> views.orderFile(maker, "ip", 99L, 1L))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(
                        () ->
                                views.attachOrder(
                                        maker,
                                        99L,
                                        "CIRCULAR",
                                        "R",
                                        LocalDate.now(),
                                        "RO",
                                        "a.pdf",
                                        new byte[1]))
                .isInstanceOf(FciException.class);
        verifyNoInteractions(documents);

        when(documents.active(TaxScreenViews.ORDER_ENTITY, "11")).thenReturn(List.of());
        assertThat(views.orders(maker, 11L)).isEmpty();
        views.attachOrder(maker, 11L, "CIRCULAR", "R", LocalDate.now(), "RO", "a.pdf", new byte[1]);
        verify(documents).attach(any(), any());
        verify(policy, times(2)).require(maker, DeclarationService.SCREEN, "SUBMIT");
        views.orderFile(maker, "ip", 11L, 1L);
        verify(documents).read("maker@x", "ip", TaxScreenViews.ORDER_ENTITY, "11", 1L);
    }

    @Test
    void SRS_G_2_eachReadIsGatedOnItsScreen() {
        doThrow(new FciException(CommonErrorCode.ACCESS_DENIED))
                .when(policy)
                .require(any(), any(), any());

        assertThatThrownBy(() -> views.outputSummary(maker)).isInstanceOf(FciException.class);
        assertThatThrownBy(() -> views.declarationSummary(maker, YEAR))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> views.declarationMembers(maker, YEAR, SPEC))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> views.computationSummary(maker)).isInstanceOf(FciException.class);
        assertThatThrownBy(() -> views.taxLines(maker, SPEC)).isInstanceOf(FciException.class);
        assertThatThrownBy(() -> views.npsLines(maker, SPEC)).isInstanceOf(FciException.class);
        assertThatThrownBy(() -> views.certificateSummary(maker, YEAR))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> views.returnLines(maker, null, SPEC))
                .isInstanceOf(FciException.class);
        assertThat(db.queries("SELECT")).isEmpty();
    }
}
