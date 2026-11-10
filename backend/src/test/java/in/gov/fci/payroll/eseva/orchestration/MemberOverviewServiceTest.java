package in.gov.fci.payroll.eseva.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E-01 overview (SRS-17-1, SRS-17-6, SRS-17-17, BR-ESEVA-004): the member comes from the session,
 * never from the request; a preview reads only the user's own open session.
 */
class MemberOverviewServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final MemberOverviewService service =
            new MemberOverviewService(
                    jdbc,
                    policy,
                    Clock.fixed(Instant.parse("2026-11-07T06:00:00Z"), ZoneOffset.UTC));

    private static AccessContext session(Long employee, Long location) {
        return new AccessContext(
                3L,
                "someone@x",
                "S",
                "s",
                "P",
                location,
                employee,
                List.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of());
    }

    @Test
    void BR_ESEVA_004_theFinancialYearRunsFromApril() {
        assertThat(MemberOverviewService.finYear(YearMonth.of(2026, 4))).isEqualTo("2026-27");
        assertThat(MemberOverviewService.finYear(YearMonth.of(2027, 3))).isEqualTo("2026-27");
        assertThat(MemberOverviewService.finYear(YearMonth.of(1999, 12))).isEqualTo("1999-00");
    }

    @Test
    void SRS_17_9_aUserWithoutAnEmployeeRecordHasNoOverview() {
        Object thrown = catchThrowable(() -> service.mine(session(null, null)));
        assertThat(((FciException) thrown).getErrorCode())
                .isEqualTo(ApiErrorCode.PERMISSION_DENIED);
    }

    @Test
    void RBAC_SEC_027_aStaffSessionWithoutSelfIsRefusedBeforeAnyRead() {
        AccessContext staff = session(9L, 10L);
        doThrow(new FciException(ApiErrorCode.PERMISSION_DENIED))
                .when(policy)
                .require(staff, "E-01", "SELF");
        Object thrown = catchThrowable(() -> service.mine(staff));
        assertThat(((FciException) thrown).getErrorCode())
                .isEqualTo(ApiErrorCode.PERMISSION_DENIED);
        verify(jdbc, never()).queryForList(anyString(), eq(String.class), any(Object[].class));
    }

    @Test
    void SRS_17_6_anotherUsersOrAClosedSessionReadsAsNotFound() {
        when(jdbc.queryForList(contains("FROM ess_session"), eq(Long.class), anyLong(), any()))
                .thenReturn(List.of());
        Object thrown = catchThrowable(() -> service.ofPreview(session(null, 10L), 77L));
        assertThat(((FciException) thrown).getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
    }

    @Test
    void SRS_17_6_theSessionsMemberIsTheOneReadAndTheMonthNotLockedIsListedWithItsReason() {
        when(jdbc.queryForList(contains("FROM ess_session"), eq(Long.class), anyLong(), any()))
                .thenReturn(List.of(44L));
        MemberOverviewService.Overview o = service.ofPreview(session(null, 10L), 77L);
        verify(policy).require(any(), eq("E-01"), eq("VIEW"));
        assertThat(o.latest()).isNull();
        assertThat(o.cpf()).isNull();
        assertThat(o.loan()).isNull();
        assertThat(o.tds().amount()).as("the TDS pay code is not configured").isNull();
        assertThat(o.tds().finYear()).isEqualTo("2026-27");
        assertThat(o.requests().open()).isZero();
        assertThat(o.documents())
                .extracting(
                        MemberOverviewService.DocumentRow::kind,
                        MemberOverviewService.DocumentRow::state)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("PAY_SLIP", "NOT_YET_AVAILABLE"),
                        org.assertj.core.groups.Tuple.tuple("FORM_16", "NOT_DUE"),
                        org.assertj.core.groups.Tuple.tuple("FORM_12BA", "NOT_AVAILABLE"),
                        org.assertj.core.groups.Tuple.tuple("CPF_SLIP", "NOT_AVAILABLE"));
    }
}
