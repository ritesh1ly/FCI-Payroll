package in.gov.fci.payroll.tax.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.tax.business.TaxCalculator;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail;
import in.gov.fci.payroll.tax.orchestration.TaxScreenViews.CertificateLine;
import in.gov.fci.payroll.tax.orchestration.TaxScreenViews.CertificateSummary;
import in.gov.fci.payroll.tax.orchestration.TaxScreenViews.ComputationSummary;
import in.gov.fci.payroll.tax.orchestration.TaxScreenViews.DeclarationLine;
import in.gov.fci.payroll.tax.orchestration.TaxScreenViews.TaxLine;
import in.gov.fci.payroll.tax.orchestration.TaxScreenViews.Unit;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The register lines of I-07, C-07 and C-10 (OD-121; SRS-7-1, SRS-7-9, SRS-7-12, SRS-7-23,
 * SRS-7-26, SRS-9-1, BR-CYC-007, BR-TAX-010): each line carries what the tables hold and nothing
 * assumed (a member with no regime, no round or no last name still has a line; the 80C limit comes
 * from the section master in force or is empty), a forecast that cannot be made shows its first
 * reason, no locked period shows no figure, and the financial year turns in April.
 */
class TaxScreenViewsCoverageGapsTest {

    private static final PageSpec SPEC = new PageSpec(0, 20);
    private static final String YEAR = "2026-27";
    private static final Instant VERIFIED = Instant.parse("2026-10-20T05:30:00Z");

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final Authorization policy = mock(Authorization.class);
    private final TaxComputationService computation = mock(TaxComputationService.class);
    private final AccessContext maker =
            new AccessContext(
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
                    Set.of(300L));

    private TaxScreenViews views(String instant) {
        return new TaxScreenViews(
                db.named,
                policy,
                computation,
                mock(DeclarationService.class),
                mock(OutputDefinitionService.class),
                mock(DocumentStore.class),
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private final TaxScreenViews views = views("2026-11-02T06:00:00Z");

    // ------------------------------------------------------------------ I-07

    private void members() {
        db.value("SELECT COUNT(*) FROM employee e", 2L);
        db.rows(
                "SELECT e.employee_id, e.emp_num, e.first_name, e.last_name, r.regime_code",
                row(
                        "EMPLOYEE_ID",
                        7L,
                        "EMP_NUM",
                        "104882",
                        "FIRST_NAME",
                        "R.",
                        "LAST_NAME",
                        "Sharma",
                        "REGIME_CODE",
                        "O",
                        "LOCKED_FLAG",
                        "Y",
                        "VERIFIED_AT",
                        Timestamp.from(VERIFIED),
                        "ROUND_ID",
                        77L,
                        "ROUND_STATUS",
                        "VERIFIED",
                        "D80C",
                        new BigDecimal("160000"),
                        "D80D",
                        new BigDecimal("25000"),
                        "D24B",
                        null,
                        "ALLOWED",
                        new BigDecimal("175000"),
                        "CAPPED",
                        1L,
                        "UNPROVED",
                        0L),
                row(
                        "EMPLOYEE_ID", 8L,
                        "EMP_NUM", "104883",
                        "FIRST_NAME", "Asha",
                        "LAST_NAME", null,
                        "REGIME_CODE", null,
                        "LOCKED_FLAG", null,
                        "VERIFIED_AT", null,
                        "ROUND_ID", null,
                        "ROUND_STATUS", null,
                        "D80C", null,
                        "D80D", null,
                        "D24B", null,
                        "ALLOWED", null,
                        "CAPPED", 0L,
                        "UNPROVED", 2L));
    }

    private static Map<String, Object> section(String code, String limit) {
        return row(
                "SECTION_CODE",
                code,
                "MAX_DEDUCTION_AMOUNT",
                new BigDecimal(limit),
                "REGIME_APPLICABILITY",
                "OLD",
                "EFFECTIVE_FROM",
                Date.valueOf(LocalDate.of(2026, 4, 1)));
    }

    @Test
    void SRS_7_1_everyMemberHasALineWithTheRoundAndThe80cLimitInForce() {
        members();
        db.rows("FROM it_section_master s", section("80D", "25000"), section("80C", "150000"));

        Paged<DeclarationLine> page = views.declarationMembers(maker, YEAR, SPEC);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items())
                .containsExactly(
                        new DeclarationLine(
                                7L,
                                "104882",
                                "R. Sharma",
                                "O",
                                true,
                                VERIFIED,
                                77L,
                                "VERIFIED",
                                new BigDecimal("160000"),
                                new BigDecimal("150000"),
                                new BigDecimal("25000"),
                                null,
                                new BigDecimal("175000"),
                                true,
                                false),
                        new DeclarationLine(
                                8L,
                                "104883",
                                "Asha",
                                null,
                                false,
                                null,
                                null,
                                null,
                                null,
                                new BigDecimal("150000"),
                                null,
                                null,
                                null,
                                false,
                                true));
        assertThat(db.queries("FROM it_section_master s")).hasSize(1);
    }

    @Test
    void SRS_7_26_withNo80cSectionInForceTheLimitIsEmptyNotAssumed() {
        members();
        db.rows("FROM it_section_master s", section("80D", "25000"));

        Paged<DeclarationLine> page = views.declarationMembers(maker, YEAR, SPEC);

        assertThat(page.items()).extracting(DeclarationLine::limit80c).containsOnlyNulls();
    }

    // ------------------------------------------------------------------ C-07

    @Test
    void BR_CYC_007_withoutALockedPeriodTheTypeTableIsEmptyButTheChecksStillCount() {
        db.value("CPF.RATE.EMPLOYEE", new BigDecimal("12"));
        db.value("e.cpf_code IS NULL", 3L);
        db.value("'AMOUNT_WITHOUT_FLAG')", 2L);
        db.value("NOT EXISTS (SELECT 1 FROM it_regime_detail", 4L);

        ComputationSummary s = views.computationSummary(maker);

        assertThat(s.period()).isNull();
        assertThat(s.cpfRate()).isEqualByComparingTo("12");
        assertThat(s.types()).isEmpty();
        assertThat(s.noCpfCode()).isEqualTo(3);
        assertThat(s.npsMismatch()).isEqualTo(2);
        assertThat(s.regimeMissing()).isEqualTo(4);
        assertThat(db.queries("GROUP BY r.payroll_type")).isEmpty();
    }

    private void taxMembers() {
        db.value("SELECT COUNT(*) FROM employee e", 2L);
        db.rows(
                "SELECT e.employee_id, e.emp_num, e.first_name, e.last_name FROM employee e",
                row("EMPLOYEE_ID", 7L, "EMP_NUM", "104882"),
                row("EMPLOYEE_ID", 8L, "EMP_NUM", "104883"));
    }

    private TaxComputationService.Forecast forecast(
            String eis,
            ItRegimeDetail.Regime regime,
            TaxCalculator.Result result,
            List<String> reasons) {
        TaxComputationService.Inputs inputs = mock(TaxComputationService.Inputs.class);
        when(inputs.regime()).thenReturn(regime);
        return new TaxComputationService.Forecast(
                null,
                eis,
                "Name " + eis,
                "ACTIVE",
                YEAR,
                "PROJECTED",
                inputs,
                Optional.empty(),
                Optional.ofNullable(result),
                Optional.empty(),
                reasons);
    }

    @Test
    void SRS_7_26_aForecastThatCannotBeMadeShowsItsFirstReasonAndNoFigure() {
        taxMembers();
        db.value("SELECT MAX(r.payroll_yymm)", "202610");
        db.value("v.value_code = 'PT'", new BigDecimal("200"));
        TaxCalculator.Result result =
                new TaxCalculator.Result(
                        new BigDecimal("900000"),
                        new BigDecimal("50000"),
                        BigDecimal.ZERO,
                        new BigDecimal("2000"),
                        new BigDecimal("52000"),
                        true,
                        new BigDecimal("4334"));
        TaxComputationService.Forecast computed =
                forecast("104882", ItRegimeDetail.Regime.OLD, result, List.of());
        TaxComputationService.Forecast blocked =
                forecast("104883", null, null, List.of("REGIME_NOT_CHOSEN", "PAN_MISSING"));
        when(computation.forecast(maker, "104882", YEAR)).thenReturn(computed);
        when(computation.forecast(maker, "104883", YEAR)).thenReturn(blocked);

        Paged<TaxLine> page = views.taxLines(maker, SPEC);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items())
                .containsExactly(
                        new TaxLine(
                                7L,
                                "104882",
                                "Name 104882",
                                "OLD",
                                new BigDecimal("900000"),
                                new BigDecimal("52000"),
                                new BigDecimal("4334"),
                                new BigDecimal("200"),
                                true,
                                null),
                        new TaxLine(
                                8L,
                                "104883",
                                "Name 104883",
                                null,
                                null,
                                null,
                                null,
                                new BigDecimal("200"),
                                false,
                                "REGIME_NOT_CHOSEN"));
    }

    @Test
    void BR_CYC_007_withoutALockedPeriodNoProfessionalTaxIsShownAndJanuaryIsLastYears() {
        taxMembers();
        TaxScreenViews january = views("2027-01-15T06:00:00Z");
        TaxComputationService.Forecast first =
                forecast("104882", ItRegimeDetail.Regime.NEW, null, List.of());
        TaxComputationService.Forecast second =
                forecast(
                        "104883",
                        ItRegimeDetail.Regime.NEW,
                        new TaxCalculator.Result(
                                new BigDecimal("700000"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                false,
                                BigDecimal.ZERO),
                        List.of());
        when(computation.forecast(maker, "104882", YEAR)).thenReturn(first);
        when(computation.forecast(maker, "104883", YEAR)).thenReturn(second);

        Paged<TaxLine> page = january.taxLines(maker, SPEC);

        assertThat(page.items()).extracting(TaxLine::professionalTax).containsOnlyNulls();
        assertThat(page.items()).extracting(TaxLine::regime).containsOnly("NEW");
        assertThat(page.items()).extracting(TaxLine::reason).containsOnlyNulls();
        assertThat(page.items()).extracting(TaxLine::higherRate).containsOnly(false);
        // January 2027 belongs to the financial year that began in April 2026.
        verify(computation).forecast(maker, "104882", YEAR);
        assertThat(db.queries("v.value_code = 'PT'")).isEmpty();
    }

    // ------------------------------------------------------------------ C-10

    @Test
    void BR_TAX_010_theChainCountsEachStepOfTheYearAndListsTheUnitsHoldingCertificates() {
        db.value("SELECT COUNT(*) FROM form16 f", 10L);
        db.value("f.part_b_generated_at IS NOT NULL", 8L);
        db.value("f.part_a_received_at IS NOT NULL", 6L);
        db.value("f.merged_at IS NOT NULL", 5L);
        db.value("f.release_status = 'RELEASED'", 4L);
        db.value("o.report_key = 'FORM_10E'", 2L);
        db.rows(
                "SELECT l.location_id, l.location_code, l.location_name FROM org_location l",
                row("LOCATION_ID", 300L, "LOCATION_CODE", "DO300", "LOCATION_NAME", "Delhi"));

        CertificateSummary s = views.certificateSummary(maker, "2025-26");

        assertThat(s)
                .isEqualTo(
                        new CertificateSummary(
                                "2025-26",
                                10,
                                8,
                                6,
                                5,
                                4,
                                2,
                                List.of(new Unit(300L, "DO300", "Delhi"))));
    }

    @Test
    void SRS_7_23_withoutStatusOrOfficeEveryCertificateOfTheYearIsListed() {
        db.value("SELECT COUNT(*) FROM form16 f", 1L);
        db.rows(
                "SELECT f.form16_id",
                row(
                        "FORM16_ID",
                        5L,
                        "EMP_NUM",
                        "104882",
                        "FIRST_NAME",
                        "R.",
                        "LAST_NAME",
                        "Sharma",
                        "PAN_NUMBER",
                        "ABCPS1234K",
                        "REGIME_CODE",
                        "N",
                        "TAXABLE_INCOME",
                        BigDecimal.TEN,
                        "TAX_DEDUCTED",
                        BigDecimal.ONE,
                        "PART_B_GENERATED_AT",
                        Timestamp.from(VERIFIED),
                        "PART_A_ATTACHMENT_ID",
                        9L,
                        "SIGNED_AT",
                        Timestamp.from(VERIFIED),
                        "RELEASE_STATUS",
                        "SIGNED"));

        Paged<CertificateLine> page = views.certificateLines(maker, "2025-26", null, null, SPEC);

        CertificateLine line = page.items().get(0);
        assertThat(line.partB()).isTrue();
        assertThat(line.partA()).isTrue();
        assertThat(line.signed()).isTrue();
        assertThat(line.status()).isEqualTo("SIGNED");
        assertThat(line.regime()).isEqualTo("N");
        assertThat(line.taxable()).isEqualByComparingTo("10");
        assertThat(line.tds()).isEqualByComparingTo("1");
        assertThat(page.total()).isEqualTo(1);
        assertThat(db.queries("f.release_status = :status")).isEmpty();
        assertThat(db.queries("e.location_id = :office")).isEmpty();
    }

    @Test
    void SRS_7_26_theCardListsTheSectionsAndTheTaxRulesInForce() {
        db.rows("FROM it_section_master s", section("80C", "150000"));
        db.rows(
                "FROM rule_definition d JOIN rule_version v",
                row(
                        "RULE_KEY",
                        "TAX.SLAB.NEW",
                        "EFFECTIVE_FROM",
                        Date.valueOf(LocalDate.of(2026, 4, 1))));

        List<TaxScreenViews.SectionLine> lines = views.declarationSummary(maker, YEAR).sections();

        assertThat(lines)
                .containsExactly(
                        new TaxScreenViews.SectionLine(
                                "80C",
                                "OLD",
                                new BigDecimal("150000"),
                                LocalDate.of(2026, 4, 1),
                                false),
                        new TaxScreenViews.SectionLine(
                                "TAX.SLAB.NEW", null, null, LocalDate.of(2026, 4, 1), true));
    }
}
