package in.gov.fci.payroll.cpf.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.configuration.api.RuleSet;
import in.gov.fci.payroll.payroll.api.StaffDeductions;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * The CPF / VPF deduction's edge cases (plan 16 phase 16e-2; SRS-6-1, SRS-1-14, SRS-6-59, R-RULE):
 * a missing employee, an NPS member, a blank CPF code, a zero contribution, a rate that does not
 * apply or is not configured, and an unset pay code. Rates are fixtures.
 */
class CpfContributionDeductionTest {

    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);

    private final RuleEngine rules = mock(RuleEngine.class);
    private final RuleSet set = mock(RuleSet.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CpfContributionDeduction source = new CpfContributionDeduction(rules, jdbc);

    CpfContributionDeductionTest() {
        when(rules.snapshot(APRIL)).thenReturn(set);
        when(set.hash()).thenReturn("h");
    }

    private static RuleEngine.Resolved scalar(String value) {
        return new RuleEngine.Resolved(
                "k",
                "RATE",
                1L,
                1,
                APRIL,
                null,
                value == null ? null : new BigDecimal(value),
                List.of(),
                null,
                null,
                false);
    }

    private void member(String fundType, String code, BigDecimal vpf) {
        Map<String, Object> row = new HashMap<>();
        row.put("PROVIDENT_FUND_TYPE", fundType);
        row.put("CPF_CODE", code);
        row.put("PARENT_ZONE_ID", 11L);
        row.put("VPF", vpf);
        when(jdbc.queryForList(anyString(), any(String.class), eq(7L)))
                .thenReturn(fundType == null ? List.of() : List.of(row));
    }

    private void payCodes(Integer cpf) {
        when(jdbc.queryForList(anyString(), eq(BigDecimal.class), eq("CPF"), any(), any()))
                .thenReturn(cpf == null ? List.of() : List.of(BigDecimal.valueOf(cpf)));
        when(jdbc.queryForList(anyString(), eq(BigDecimal.class), eq("VPF"), any(), any()))
                .thenReturn(List.of());
    }

    private List<StaffDeductions.Line> run(String basic) {
        return source.open(APRIL)
                .deductions(
                        new StaffDeductions.Member(
                                7L,
                                3L,
                                "IDA",
                                YearMonth.of(2026, 4),
                                List.of(new StaffDeductions.Earning(101, new BigDecimal(basic))),
                                new BigDecimal(basic)));
    }

    @Test
    void SRS_6_59_noRecordNpsAndNoCodeAreHandled() {
        payCodes(904);
        member(null, null, null);
        assertThatThrownBy(() -> run("30000"))
                .isInstanceOf(StaffDeductions.NotComputable.class)
                .hasMessageContaining("not found");
        member("NPS", "X", null);
        assertThat(run("30000")).as("no CPF for NPS").isEmpty();
        member("CPF", "  ", null);
        assertThatThrownBy(() -> run("30000")).hasMessageContaining("no CPF code");
        Map<String, Object> noZone = new HashMap<>();
        noZone.put("PROVIDENT_FUND_TYPE", "CPF");
        noZone.put("CPF_CODE", "CPF/1");
        when(jdbc.queryForList(anyString(), any(String.class), eq(7L))).thenReturn(List.of(noZone));
        assertThatThrownBy(() -> run("30000")).hasMessageContaining("no CPF parent zone");
    }

    @Test
    void R_RULE_aRateThatDoesNotApplyOrIsNotConfiguredRefusesAndZeroWritesNothing() {
        payCodes(904);
        member("CPF", "CPF/1", null);
        when(set.resolve(eq("CPF.RATE.EMPLOYEE"), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> run("30000")).hasMessageContaining("does not apply");
        when(set.resolve(eq("CPF.RATE.EMPLOYEE"), any())).thenReturn(Optional.of(scalar(null)));
        assertThatThrownBy(() -> run("30000")).hasMessageContaining("does not apply");
        when(set.resolve(eq("CPF.RATE.EMPLOYEE"), any()))
                .thenThrow(
                        new FciException(
                                ApiErrorCode.RULE_NOT_CONFIGURED,
                                new Object[] {"k", APRIL},
                                List.of(),
                                null));
        assertThatThrownBy(() -> run("30000")).hasMessageContaining("not configured (M-18)");
    }

    @Test
    void SRS_6_1_aZeroContributionWritesNoLineAndAnUnsetCodeRefuses() {
        // Basic (101) is in the CPF base (M-07 in_cpf_base_flag).
        org.mockito.Mockito.doAnswer(
                        i -> {
                            RowCallbackHandler h = i.getArgument(1);
                            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                            when(rs.getInt(1)).thenReturn(101);
                            when(rs.getString(2)).thenReturn("Y");
                            h.processRow(rs);
                            return null;
                        })
                .when(jdbc)
                .query(
                        org.mockito.ArgumentMatchers.startsWith("SELECT legacy_pay_code"),
                        any(RowCallbackHandler.class));
        member("CPF", "CPF/1", null);
        when(set.resolve(eq("CPF.RATE.EMPLOYEE"), any())).thenReturn(Optional.of(scalar("12")));
        payCodes(904);
        assertThat(run("0")).isEmpty();
        assertThatThrownBy(() -> run("30000"))
                .as("07i: the employer share must be computable before the lock")
                .hasMessageContaining("CPF.RATE.EMPLOYER");
        when(set.resolve(eq("CPF.RATE.EMPLOYER"), any())).thenReturn(Optional.of(scalar("12")));
        when(set.resolve(eq("CPF.EPS.RATE"), any())).thenReturn(Optional.of(scalar("10")));
        when(set.resolve(eq("CPF.HIGHERPENSION.RATE"), any())).thenReturn(Optional.of(scalar("5")));
        assertThatThrownBy(() -> run("30000"))
                .as("a pension split above the employer share cannot hold")
                .hasMessageContaining("exceed the employer contribution 3600");
        when(set.resolve(eq("CPF.EPS.RATE"), any())).thenReturn(Optional.of(scalar("8.33")));
        when(set.resolve(eq("CPF.HIGHERPENSION.RATE"), any()))
                .thenReturn(Optional.of(scalar("1.16")));
        assertThat(run("30000"))
                .extracting(StaffDeductions.Line::amount)
                .containsExactly(new BigDecimal("3600"));
        payCodes(null);
        assertThatThrownBy(() -> run("30000")).hasMessageContaining("pay code not configured");
    }

    /**
     * US-GAP-181 (SRS-6-1, M-19 "VPF — maximum": the total member contribution may not exceed the
     * limit): employee + VPF exactly at the CPF.VPF.MAX share of the wage base is deducted; one
     * paisa above refuses the member (not computed, held for the Maker), never capped silently.
     */
    @Test
    void SRS_6_1_vpfExactlyAtTheMaximumIsDeductedAndOnePaisaAboveRefusesTheMember() {
        org.mockito.Mockito.doAnswer(
                        i -> {
                            RowCallbackHandler h = i.getArgument(1);
                            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                            when(rs.getInt(1)).thenReturn(101);
                            when(rs.getString(2)).thenReturn("Y");
                            h.processRow(rs);
                            return null;
                        })
                .when(jdbc)
                .query(
                        org.mockito.ArgumentMatchers.startsWith("SELECT legacy_pay_code"),
                        any(RowCallbackHandler.class));
        when(jdbc.queryForList(anyString(), eq(BigDecimal.class), eq("CPF"), any(), any()))
                .thenReturn(List.of(BigDecimal.valueOf(904)));
        when(jdbc.queryForList(anyString(), eq(BigDecimal.class), eq("VPF"), any(), any()))
                .thenReturn(List.of(BigDecimal.valueOf(905)));
        when(set.resolve(eq("CPF.RATE.EMPLOYEE"), any())).thenReturn(Optional.of(scalar("12")));
        when(set.resolve(eq("CPF.RATE.EMPLOYER"), any())).thenReturn(Optional.of(scalar("12")));
        when(set.resolve(eq("CPF.EPS.RATE"), any())).thenReturn(Optional.of(scalar("8.33")));
        when(set.resolve(eq("CPF.HIGHERPENSION.RATE"), any()))
                .thenReturn(Optional.of(scalar("1.16")));
        when(set.resolve(eq("CPF.VPF.MAX"), any())).thenReturn(Optional.of(scalar("100")));

        // Base 30,000: employee 3,600; the maximum is 30,000, so VPF 26,400 reaches it exactly.
        member("CPF", "CPF/1", new BigDecimal("26400.00"));
        assertThat(run("30000"))
                .extracting(StaffDeductions.Line::amount)
                .containsExactly(new BigDecimal("3600"), new BigDecimal("26400"));
        member("CPF", "CPF/1", new BigDecimal("26400.01"));
        assertThatThrownBy(() -> run("30000"))
                .isInstanceOf(StaffDeductions.NotComputable.class)
                .hasMessageContaining("exceed the VPF maximum of 100 % of the wage base 30000");
    }
}
