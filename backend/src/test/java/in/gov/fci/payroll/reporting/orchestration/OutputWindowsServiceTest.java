package in.gov.fci.payroll.reporting.orchestration;

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

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * O-02 and O-11 read-only windows (SRS-15-34, SRS-15-35, SRS-G-52, SRS-18-19): role first, the lock
 * state from the cycles, the suspended flag, the window by the date sent to APPS.
 */
class OutputWindowsServiceTest {

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final OutputWindowsService service =
            new OutputWindowsService(
                    named,
                    policy,
                    Clock.fixed(Instant.parse("2026-11-07T06:00:00Z"), ZoneOffset.UTC));
    private final AccessContext finance =
            new AccessContext(
                    1L,
                    "finance@x",
                    "F",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));

    private void counts(String sql, long value) {
        when(named.queryForObject(contains(sql), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(value);
    }

    @Test
    void SRS_G_13_aRoleWithoutTheScreenIsRefusedBeforeAnyRead() {
        doThrow(new FciException(ApiErrorCode.PERMISSION_DENIED))
                .when(policy)
                .require(finance, "O-02", "VIEW");
        Object thrown = catchThrowable(() -> service.bank(finance, "202610"));
        assertThat(((FciException) thrown).getErrorCode())
                .isEqualTo(ApiErrorCode.PERMISSION_DENIED);
        verify(named, never())
                .queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class));
    }

    @Test
    void SRS_15_34_noCycleOfTheMonthReadsAsNotLockedAndAnOpenStopReadsSuspended() {
        counts("FROM payroll_cycle_instance", 0L);
        counts("FROM disb_release_suspension", 1L);
        OutputWindowsService.BankOverview o = service.bank(finance, "202610");
        assertThat(o.locked()).isFalse();
        assertThat(o.suspended()).isTrue();
        assertThat(o.ebsStatus()).isEqualTo("IDLE");
        assertThat(o.banks()).isZero();
    }

    @Test
    void SRS_G_52_theWindowCountsByTheDateSentAndAnEmptyYearEndIsNull() {
        counts("FROM payroll_cycle_instance", 2L);
        counts("FROM ap_invoice_staging", 9L);
        counts("FROM pay_component", 3L);
        OutputWindowsService.RemittanceOverview o = service.remittance(finance, "202610");
        assertThat(o.locked()).isTrue();
        assertThat(o.payments()).isEqualTo(9);
        assertThat(o.unmappedPayCodes()).isEqualTo(3);
        assertThat(o.yearEndFinYear()).isNull();
        verify(named, org.mockito.Mockito.atLeastOnce())
                .queryForObject(
                        contains("i.sent_to_apps_at >= :from"),
                        any(MapSqlParameterSource.class),
                        eq(Long.class));
    }

    @Test
    void SRS_G_52_withNoCycleTheMonthDefaultsToToday() {
        when(named.queryForList(
                        contains("MAX(c.payroll_yymm)"),
                        any(MapSqlParameterSource.class),
                        eq(String.class)))
                .thenReturn(Collections.singletonList(null));
        assertThat(service.remittance(finance, null).yymm()).isEqualTo("202611");
    }
}
