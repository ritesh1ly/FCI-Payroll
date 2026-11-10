package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * The HRMS Update Request refusals and lists (plan 16 phase 16c; SRS-1-70): no employee named, an
 * employee whose office has no cycle unit, no field, an eSeva user without an employee record, an
 * unknown request; the unit's list (every unit at HQ) and a row without a surname or raise time.
 */
class HrmsUpdateRequestRefusalsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final HrmsChangeService changes = mock(HrmsChangeService.class);

    @SuppressWarnings("unchecked")
    private final HrmsUpdateRequestService service =
            new HrmsUpdateRequestService(
                    jdbc,
                    named,
                    org,
                    mock(Authorization.class),
                    mock(AuditService.class),
                    changes,
                    mock(HrmsChangeApplier.class),
                    mock(ObjectProvider.class),
                    mock(ApplicationEventPublisher.class),
                    Clock.fixed(Instant.parse("2026-10-26T06:00:00Z"), ZoneOffset.UTC));

    private static AccessContext user(Long employeeId) {
        return new AccessContext(
                2L,
                "member@x",
                "Member",
                "s",
                "E",
                300L,
                employeeId,
                List.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of());
    }

    private static HrmsUpdateRequestService.NewRequest request(String eis, String field) {
        return new HrmsUpdateRequestService.NewRequest(eis, field, null, LocalDate.of(2026, 10, 1));
    }

    private static void refused(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void SRS_1_70_aRequestNamesAnEmployeeOfAUnitAndAField() {
        refused(
                () -> service.raiseForEmployee(user(null), request(null, "PAN")),
                ApiErrorCode.I21_REQUEST);
        refused(
                () -> service.raiseForEmployee(user(null), request(" ", "PAN")),
                ApiErrorCode.I21_REQUEST);
        when(jdbc.queryForList(anyString(), eq("100041")))
                .thenReturn(List.of(Map.of("EMPLOYEE_ID", 7L, "LOCATION_ID", 500L)));
        when(org.cycleUnitOf(500L)).thenReturn(Optional.empty());
        refused(
                () -> service.raiseForEmployee(user(null), request("100041", "PAN")),
                ApiErrorCode.CYCLE_NO_UNIT);
        refused(() -> service.raiseMine(user(9L), request(null, null)), ApiErrorCode.I21_REQUEST);
        refused(() -> service.raiseMine(user(9L), request(null, " ")), ApiErrorCode.I21_REQUEST);
        refused(
                () -> service.raiseMine(user(null), request(null, "PAN")),
                ApiErrorCode.PERMISSION_DENIED);
        refused(
                () -> service.mine(user(null), new PageSpec(0, 20)),
                ApiErrorCode.PERMISSION_DENIED);
        when(jdbc.queryForList(anyString(), eq(404L))).thenReturn(List.of());
        refused(() -> service.refetch(user(null), 404L), CommonErrorCode.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_1_70_theListCoversEveryUnitAtHqAndShowsARowWithoutSurnameOrTime() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("first_name")).thenReturn("Asha");
        when(rs.getString("last_name")).thenReturn(" ");
        when(rs.getTimestamp("raised_at")).thenReturn(null);
        doAnswer(i -> List.of(((RowMapper<Object>) i.getArgument(2)).mapRow(rs, 0)))
                .when(named)
                .query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
        when(named.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(null);
        when(changes.scopeUnit(any(), eq("VIEW"))).thenReturn(null);
        Paged<HrmsUpdateRequestService.RequestView> hq =
                service.page(user(null), new PageSpec(0, 20));
        assertThat(hq.total()).isZero();
        assertThat(hq.items().get(0).employee()).isEqualTo("Asha");
        assertThat(hq.items().get(0).raisedAt()).isNull();
        when(changes.scopeUnit(any(), eq("VIEW"))).thenReturn(300L);
        when(org.subtree(300L)).thenReturn(Set.of(300L, 301L));
        when(rs.getString("last_name")).thenReturn("Rao");
        assertThat(service.page(user(null), new PageSpec(0, 20)).items().get(0).employee())
                .isEqualTo("Asha Rao");
    }
}
