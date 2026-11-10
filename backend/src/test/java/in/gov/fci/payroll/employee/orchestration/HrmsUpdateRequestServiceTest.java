package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.HrmsRecords;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * SRS-1-70 / plan 16 D16-5: with no HRMS interface yet (module 23 not built, no {@link HrmsRecords}
 * bean) a request is recorded as FETCH_FAILED with "not configured", never as a change; the ITs run
 * with a stand-in HRMS, so this branch is proved here.
 */
class HrmsUpdateRequestServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void SRS_1_70_withoutAnHrmsInterfaceTheFetchIsRecordedAsNotConfigured() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
        HrmsChangeService changes = mock(HrmsChangeService.class);
        ObjectProvider<HrmsRecords> hrms = mock(ObjectProvider.class);
        when(hrms.getIfAvailable()).thenReturn(null);
        when(changes.field("EPS_NUMBER"))
                .thenReturn(
                        Map.of(
                                "PAY_EFFECT_FIELD_ID", 3L,
                                "FIELD_CODE", "EPS_NUMBER",
                                "HAS_PAY_EFFECT", "Y"));
        doAnswer(
                        invocation -> {
                            KeyHolder key = invocation.getArgument(1);
                            ((GeneratedKeyHolder) key)
                                    .getKeyList()
                                    .add(Map.of("hrms_update_request_id", 5L));
                            return 1;
                        })
                .when(jdbc)
                .update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        when(named.queryForList(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(List.of());
        HrmsUpdateRequestService.RequestView view =
                new HrmsUpdateRequestService.RequestView(
                        5L,
                        "HUR-2026-0005",
                        "104882",
                        "R",
                        "EPS_NUMBER",
                        "EPS",
                        "ESEVA",
                        "m",
                        Instant.EPOCH,
                        null,
                        LocalDate.EPOCH,
                        "FETCH_FAILED",
                        "FETCH_FAILED",
                        "HRMS_FETCH_NOT_CONFIGURED",
                        null,
                        null);
        when(named.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(view));
        HrmsUpdateRequestService service =
                new HrmsUpdateRequestService(
                        jdbc,
                        named,
                        mock(OrgDirectory.class),
                        mock(Authorization.class),
                        mock(AuditService.class),
                        changes,
                        mock(HrmsChangeApplier.class),
                        hrms,
                        mock(ApplicationEventPublisher.class),
                        Clock.fixed(Instant.parse("2026-10-26T06:00:00Z"), ZoneOffset.UTC));

        AccessContext member =
                new AccessContext(
                        2L,
                        "member@x",
                        "Member",
                        "s",
                        "E",
                        300L,
                        9L,
                        List.of(),
                        Map.of(),
                        Set.of(),
                        Set.of(),
                        Set.of());
        assertThat(
                        service.raiseMine(
                                        member,
                                        new HrmsUpdateRequestService.NewRequest(
                                                null, "EPS_NUMBER", " ", LocalDate.of(2026, 10, 1)))
                                .status())
                .isEqualTo("FETCH_FAILED");
        verify(jdbc)
                .update(
                        anyString(),
                        eq("FETCH_FAILED"),
                        eq("FETCH_FAILED"),
                        any(),
                        any(),
                        any(),
                        eq("HRMS_FETCH_NOT_CONFIGURED"),
                        any(),
                        eq(5L));
    }
}
