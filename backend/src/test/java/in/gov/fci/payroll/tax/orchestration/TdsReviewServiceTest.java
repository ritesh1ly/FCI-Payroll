package in.gov.fci.payroll.tax.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * FRS V0.5 §14.4 Step 4 (US-GAP-29; SRS-7-14, BR-TAX-001): the flagged TDS cases of the cycle's
 * financial year, each with its member, office, flag and reference; C-01 VIEW is required.
 */
class TdsReviewServiceTest {

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final TdsReviewService service = new TdsReviewService(named, policy);
    private final AccessContext context =
            new AccessContext(
                    1L,
                    "u@x",
                    "U",
                    "s",
                    "PAYROLL_CHECKER",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));

    private static ResultSet row(String flag, boolean full) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("employee_id")).thenReturn(9L);
        when(rs.getString("emp_num")).thenReturn("104882");
        when(rs.getString("first_name")).thenReturn(full ? "Asha" : null);
        when(rs.getString("last_name")).thenReturn(full ? "Rao" : null);
        when(rs.getString("location_code")).thenReturn(full ? "KL001" : null);
        when(rs.getString("location_name")).thenReturn("Kolkata");
        when(rs.getString("flag")).thenReturn(flag);
        when(rs.getLong("reference")).thenReturn(full ? 41L : 0L);
        when(rs.wasNull()).thenReturn(!full);
        when(rs.getString("reason")).thenReturn(full ? "Wrong regime recorded" : null);
        when(rs.getTimestamp("flagged_at"))
                .thenReturn(full ? Timestamp.from(Instant.parse("2026-09-10T06:00:00Z")) : null);
        return rs;
    }

    @Test
    void SRS_7_14_theFlaggedCasesOfTheCyclesYearAreListedWithMemberOfficeAndReference()
            throws Exception {
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(2L);
        when(named.query(
                        contains("ORDER BY emp_num"),
                        argThat(
                                (SqlParameterSource p) ->
                                        "2026-27".equals(p.getValue("year"))
                                                && Long.valueOf(20).equals(p.getValue("offset"))),
                        org.mockito.ArgumentMatchers
                                .<RowMapper<TdsReviewService.FlaggedCase>>any()))
                .thenAnswer(
                        call -> {
                            RowMapper<?> m = call.getArgument(2);
                            return List.of(
                                    m.mapRow(row("REGIME_RESET", true), 0),
                                    m.mapRow(row("PROOF_DEADLINE_REVERSAL", false), 1));
                        });
        var page = service.flagged(context, YearMonth.of(2027, 3), new PageSpec(1, 20));
        assertThat(page.total()).isEqualTo(2L);
        TdsReviewService.FlaggedCase reset = page.items().get(0);
        assertThat(reset.flag()).isEqualTo(TdsReviewService.Flag.REGIME_RESET);
        assertThat(reset.name()).isEqualTo("Asha Rao");
        assertThat(reset.office()).isEqualTo("KL001 - Kolkata");
        assertThat(reset.reference()).isEqualTo(41L);
        assertThat(reset.reason()).isEqualTo("Wrong regime recorded");
        assertThat(reset.at()).isNotNull();
        TdsReviewService.FlaggedCase reversal = page.items().get(1);
        assertThat(reversal.flag()).isEqualTo(TdsReviewService.Flag.PROOF_DEADLINE_REVERSAL);
        assertThat(reversal.name()).isEmpty();
        assertThat(reversal.office()).isNull();
        assertThat(reversal.reference()).isNull();
        assertThat(reversal.at()).isNull();
    }

    @Test
    void BR_TAX_001_aRoleWithoutCycleControlIsRefused() {
        doThrow(new FciException(CommonErrorCode.ACCESS_DENIED))
                .when(policy)
                .require(context, "C-01", "VIEW");
        assertThatThrownBy(
                        () -> service.flagged(context, YearMonth.of(2026, 4), new PageSpec(0, 20)))
                .isInstanceOf(FciException.class);
    }
}
