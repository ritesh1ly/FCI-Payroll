package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * O-01 (SRS l.3381-3392, BR-ESEVA-004, DDL payslip): the register's status, the lock gate of bulk
 * generation, its role, and the idempotent re-run that keeps a payslip reproducible.
 */
class PayslipRegisterServiceTest {

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final AuditService audit = mock(AuditService.class);
    private final PayslipRegisterService service =
            new PayslipRegisterService(
                    named,
                    policy,
                    audit,
                    Clock.fixed(Instant.parse("2026-11-07T06:00:00Z"), ZoneOffset.UTC));
    private final AccessContext maker =
            new AccessContext(
                    1L,
                    "maker@x",
                    "M",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));

    private void locked(long locked, long open) {
        when(named.queryForObject(
                        contains("c.cycle_status = 'LOCKED'"),
                        any(MapSqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(locked);
        when(named.queryForObject(
                        contains("c.cycle_status <> 'LOCKED'"),
                        any(MapSqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(open);
    }

    @Test
    void SRS_17_1_theRegisterStatusIsTheFirstThatApplies() {
        BigDecimal net = BigDecimal.TEN;
        assertThat(PayslipRegisterService.status(net, true, true, true, true))
                .isEqualTo("SUPPRESSED");
        assertThat(PayslipRegisterService.status(net, true, true, false, true)).isEqualTo("HELD");
        assertThat(PayslipRegisterService.status(BigDecimal.ONE.negate(), true, true, false, false))
                .as("no payslip until the negative net is resolved on C-08")
                .isEqualTo("HELD");
        assertThat(PayslipRegisterService.status(net, true, true, false, false)).isEqualTo("READY");
        assertThat(PayslipRegisterService.status(net, true, false, false, false))
                .isEqualTo("AWAITING_GENERATION");
        assertThat(PayslipRegisterService.status(net, false, false, false, false))
                .isEqualTo("PROVISIONAL");
        assertThat(PayslipRegisterService.status(BigDecimal.ZERO, true, true, false, false))
                .as("a zero net is a payslip")
                .isEqualTo("READY");
    }

    @Test
    void BR_ESEVA_004_noCycleOfTheMonthLockedRefusesGenerationAndWritesNothing() {
        locked(0, 2);
        Object thrown =
                catchThrowable(
                        () ->
                                service.generate(
                                        maker, "202611", PayslipRegisterService.Level.STAFF));
        assertThat(((FciException) thrown).getErrorCode())
                .isEqualTo(ApiErrorCode.PAYSLIP_NOT_LOCKED);
        verify(named, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void SRS_G_13_aRoleWithoutGenerateIsRefusedBeforeAnyRead() {
        doThrow(new FciException(ApiErrorCode.PERMISSION_DENIED))
                .when(policy)
                .require(maker, "O-01", "GENERATE");
        Object thrown = catchThrowable(() -> service.generate(maker, "202610", null));
        assertThat(((FciException) thrown).getErrorCode())
                .isEqualTo(ApiErrorCode.PERMISSION_DENIED);
        verify(named, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void SRS_l3392_generationWritesOnlyLockedResultsOnceAndIsAudited() {
        locked(1, 0);
        when(named.queryForObject(
                        contains("FROM payslip s"),
                        any(MapSqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(40L);
        when(named.update(contains("INSERT INTO payslip"), any(MapSqlParameterSource.class)))
                .thenReturn(3);
        PayslipRegisterService.Generated done =
                service.generate(maker, "202610", PayslipRegisterService.Level.LABOUR_DEPOT);
        assertThat(done.written()).isEqualTo(3);
        assertThat(done.alreadyGenerated()).isEqualTo(40);
        verify(named)
                .update(
                        org.mockito.ArgumentMatchers.argThat(
                                s ->
                                        s.contains("r.is_locked = 'Y'")
                                                && s.contains("c.cycle_status = 'LOCKED'")
                                                && s.contains("r.run_type = 'ACTUAL'")
                                                && s.contains("r.result_status = 'CURRENT'")
                                                && s.contains("NOT EXISTS (SELECT 1 FROM payslip")),
                        any(MapSqlParameterSource.class));
        verify(audit).record(eq("maker@x"), any());
    }

    @Test
    void SRS_18_5_theRegisterReadsCommittedResultsInTheOfficeScopeOnly() {
        locked(1, 0);
        when(named.queryForObject(
                        contains("SELECT COUNT(*) FROM payroll_result"),
                        any(MapSqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(0L);
        when(named.query(
                        anyString(),
                        any(MapSqlParameterSource.class),
                        org.mockito.ArgumentMatchers.<RowMapper<PayslipRegisterService.Row>>any()))
                .thenReturn(List.of());
        Paged<PayslipRegisterService.Row> page =
                service.register(
                        maker,
                        "202610",
                        PayslipRegisterService.Level.STAFF,
                        " sharma ",
                        new PageSpec(0, 20));
        assertThat(page.items()).isEmpty();
        verify(named)
                .query(
                        org.mockito.ArgumentMatchers.argThat(
                                s ->
                                        s.contains("r.run_type = 'ACTUAL'")
                                                && s.contains("r.org_unit_id IN (:locations)")
                                                && s.contains("FETCH NEXT :size")),
                        org.mockito.ArgumentMatchers.argThat(
                                (MapSqlParameterSource p) -> "%SHARMA%".equals(p.getValue("like"))),
                        org.mockito.ArgumentMatchers.<RowMapper<PayslipRegisterService.Row>>any());
    }
}
