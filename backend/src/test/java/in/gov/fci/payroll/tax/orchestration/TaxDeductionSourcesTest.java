package in.gov.fci.payroll.tax.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.RuleSet;
import in.gov.fci.payroll.payroll.api.StaffDeductions;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.tax.business.TaxErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The PT and TDS deductions' edge cases (plan 16 phase 16e-2; SRS-7-4, SRS-7-1, SRS-7-3, D16-27): a
 * negative line is not taxable gross; a PT refusal of module 08 becomes "not computable"; no regime
 * alone gives no TDS while another reason refuses; a zero TDS writes no line.
 */
class TaxDeductionSourcesTest {

    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);

    private final TaxComputationService tax = mock(TaxComputationService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    TaxDeductionSourcesTest() {
        TaxComputationService.Run run = mock(TaxComputationService.Run.class);
        RuleSet set = mock(RuleSet.class);
        when(set.hash()).thenReturn("h");
        when(run.get()).thenReturn(set);
        when(tax.ratesOn(APRIL)).thenReturn(run);
        when(jdbc.queryForList(anyString(), eq(BigDecimal.class), anyString(), any(), any()))
                .thenReturn(List.of(BigDecimal.valueOf(901)));
    }

    private static StaffDeductions.Member member() {
        return new StaffDeductions.Member(
                7L,
                3L,
                "IDA",
                YearMonth.of(2026, 4),
                List.of(
                        new StaffDeductions.Earning(101, new BigDecimal("30000")),
                        new StaffDeductions.Earning(904, new BigDecimal("-3600"))),
                new BigDecimal("26400"));
    }

    @Test
    void SRS_7_4_aPtRefusalBecomesNotComputableAndANegativeLineIsNotGross() {
        when(tax.professionalTax(
                        eq(3L), eq(Optional.of(new BigDecimal("30000"))), eq(APRIL), any()))
                .thenThrow(
                        new FciException(
                                TaxErrorCode.RATE_NOT_CONFIGURED,
                                new Object[] {"PT.SLAB"},
                                List.of(),
                                null));
        ProfessionalTaxDeduction pt = new ProfessionalTaxDeduction(tax, jdbc);
        assertThat(pt.order()).isEqualTo(40);
        StaffDeductions.Run run = pt.open(APRIL);
        assertThat(run.versions()).contains("code=901");
        assertThatThrownBy(() -> run.deductions(member()))
                .isInstanceOf(StaffDeductions.NotComputable.class)
                .hasMessageContaining("Professional Tax")
                .hasMessageContaining(TaxErrorCode.RATE_NOT_CONFIGURED.code());
    }

    @Test
    void SRS_7_1_noRegimeAloneGivesNoTdsAndAnyOtherReasonRefuses() {
        TdsDeduction tds = new TdsDeduction(tax, jdbc);
        assertThat(tds.order()).isEqualTo(30);
        StaffDeductions.Run run = tds.open(APRIL);
        when(tax.engineTds(anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new TaxComputationService.EngineTds(
                                Optional.empty(),
                                true,
                                List.of(TaxErrorCode.REGIME_REQUIRED.code())));
        assertThat(run.deductions(member())).as("SRS-7-1: payroll continues").isEmpty();
        when(tax.engineTds(anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new TaxComputationService.EngineTds(
                                Optional.empty(),
                                true,
                                List.of(
                                        TaxErrorCode.REGIME_REQUIRED.code(),
                                        "TAX-422-RATE-NOT-CONFIGURED")));
        assertThatThrownBy(() -> run.deductions(member()))
                .isInstanceOf(StaffDeductions.NotComputable.class);
        when(tax.engineTds(anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new TaxComputationService.EngineTds(
                                Optional.of(BigDecimal.ZERO), false, List.of()));
        assertThat(run.deductions(member())).as("no tax due").isEmpty();
        when(tax.engineTds(anyLong(), any(), eq(new BigDecimal("30000")), any(), any(), any()))
                .thenReturn(
                        new TaxComputationService.EngineTds(
                                Optional.of(new BigDecimal("1250")), false, List.of()));
        assertThat(run.deductions(member()))
                .extracting(StaffDeductions.Line::amount)
                .containsExactly(new BigDecimal("1250"));
    }

    @Test
    void SRS_9_21_theEmployerNpsIsPassedApartFromTheTaxableGross() {
        when(jdbc.queryForList(
                        anyString(),
                        eq(BigDecimal.class),
                        eq("NPS_EMPLOYER_EARNING"),
                        any(),
                        any()))
                .thenReturn(List.of(BigDecimal.valueOf(908)));
        StaffDeductions.Run run = new TdsDeduction(tax, jdbc).open(APRIL);
        assertThat(run.versions()).contains("nps=908");
        when(tax.engineTds(
                        anyLong(),
                        any(),
                        eq(new BigDecimal("30000")),
                        eq(new BigDecimal("2100")),
                        any(),
                        any()))
                .thenReturn(
                        new TaxComputationService.EngineTds(
                                Optional.of(new BigDecimal("1300")), false, List.of()));
        StaffDeductions.Member withNps =
                new StaffDeductions.Member(
                        7L,
                        3L,
                        "CDA",
                        YearMonth.of(2026, 4),
                        List.of(
                                new StaffDeductions.Earning(101, new BigDecimal("30000")),
                                new StaffDeductions.Earning(908, new BigDecimal("2100"))),
                        new BigDecimal("32100"));
        assertThat(run.deductions(withNps))
                .extracting(StaffDeductions.Line::amount)
                .containsExactly(new BigDecimal("1300"));
    }
}
