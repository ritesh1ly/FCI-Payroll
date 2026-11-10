package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The eSeva payslip (plan 16 phase 16e-3; SRS-17-1, R-PII): a user without an employee record has
 * no payslip; PAN and bank account are masked whatever their length.
 */
class PayslipServiceTest {

    private final PayslipService service =
            new PayslipService(mock(JdbcTemplate.class), mock(Authorization.class));

    private static AccessContext staff() {
        return new AccessContext(
                3L, "maker@x", "Maker", "s", "P", 300L, null, List.of(), Map.of(), Set.of(),
                Set.of(), Set.of());
    }

    @Test
    void SRS_17_1_aUserWithoutAnEmployeeRecordHasNoPayslip() {
        assertThatThrownBy(() -> service.months(staff(), new PageSpec(0, 20)))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.payslip(staff(), "202609"))
                .isInstanceOf(FciException.class);
    }

    @Test
    void R_PII_thePanAndTheBankAccountAreMasked() {
        assertThat(PayslipService.maskPan(null)).isNull();
        assertThat(PayslipService.maskPan("ABCPS1234K")).isEqualTo("******234K");
        assertThat(PayslipService.maskPan("SHORT")).isEqualTo("****");
        assertThat(PayslipService.maskAccount(null)).isNull();
        assertThat(PayslipService.maskAccount("1234")).isEqualTo("****");
        assertThat(PayslipService.maskAccount("30012345678")).isEqualTo("*******5678");
    }

    @Test
    void payslipNameIsTheFirstNameAloneWithoutASurname() {
        assertThat(PayslipService.fullName("Asha", null)).isEqualTo("Asha");
        assertThat(PayslipService.fullName("Asha", " ")).isEqualTo("Asha");
        assertThat(PayslipService.fullName("Asha", "Rao")).isEqualTo("Asha Rao");
    }

    /**
     * SRS-17-7 and Oracle review F8 (1,000 payslips on salary day): the month's partition key is
     * bound for the result and the result's unit for its lines, so neither query probes every month
     * of payroll_result; a month that is not YYYYMM has no payslip.
     */
    @Test
    void SRS_17_7_aPayslipReadsOneMonthsPartitionAndAMonthNotYyyymmHasNone() {
        java.sql.Date october = java.sql.Date.valueOf("2026-10-01");
        assertThat(PayslipService.firstDay("202610")).isEqualTo(october);
        for (String bad : new String[] {"2026-10", "202613", "", null}) {
            assertThatThrownBy(() -> PayslipService.firstDay(bad))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e ->
                                    assertThat(e.getErrorCode())
                                            .isEqualTo(
                                                    in.gov.fci.payroll.platform.error
                                                            .CommonErrorCode.NOT_FOUND));
        }
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PayslipService own = new PayslipService(jdbc, mock(Authorization.class));
        AccessContext member =
                new AccessContext(
                        3L, "ess@x", "Member", "s", "P", null, 9L, List.of(), Map.of(), Set.of(),
                        Set.of(), Set.of());
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("PAYROLL_RESULT_ID", 5L);
        result.put("ORG_UNIT_ID", 300L);
        result.put("GROSS_EARNINGS", java.math.BigDecimal.TEN);
        result.put("TOTAL_DEDUCTIONS", java.math.BigDecimal.ONE);
        result.put("NET_PAY", java.math.BigDecimal.valueOf(9));
        result.put("PAYROLL_TYPE", "STAFF");
        result.put("IS_LOCKED", "Y");
        org.mockito.Mockito.when(
                        jdbc.queryForList(
                                org.mockito.ArgumentMatchers.contains(
                                        "AND r.period_date = ? AND r.payroll_yymm = ?"),
                                org.mockito.ArgumentMatchers.eq(9L),
                                org.mockito.ArgumentMatchers.eq(october),
                                org.mockito.ArgumentMatchers.eq("202610")))
                .thenReturn(List.of(result));
        org.mockito.Mockito.when(
                        jdbc.queryForMap(
                                org.mockito.ArgumentMatchers.anyString(),
                                // SRS-16-7: the month's last day (staff number in force), the month
                                // and the member.
                                org.mockito.ArgumentMatchers.eq(
                                        java.sql.Date.valueOf("2026-10-31")),
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn(
                        new java.util.HashMap<>(Map.of("EMP_NUM", "100041", "FIRST_NAME", "Asha")));
        assertThat(own.payslip(member, "202610").net()).isEqualByComparingTo("9");
        org.mockito.Mockito.verify(jdbc)
                .query(
                        org.mockito.ArgumentMatchers.contains(
                                "rc.payroll_result_id = ? AND rc.period_date = ? AND"
                                        + " rc.org_unit_id = ?"),
                        org.mockito.ArgumentMatchers.any(
                                org.springframework.jdbc.core.RowCallbackHandler.class),
                        org.mockito.ArgumentMatchers.eq(5L),
                        org.mockito.ArgumentMatchers.eq(october),
                        org.mockito.ArgumentMatchers.eq(300L));
    }
}
