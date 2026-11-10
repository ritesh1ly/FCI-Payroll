package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** O-01 and the support preview read a member only through the scope or the user's own session. */
class PayslipStaffReadTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final PayslipService service = new PayslipService(jdbc, policy);
    private final AccessContext staff =
            new AccessContext(
                    3L,
                    "maker@x",
                    "Maker",
                    "s",
                    "P",
                    300L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(300L));

    private static Object code(Throwable thrown) {
        return ((FciException) thrown).getErrorCode();
    }

    @Test
    void SRS_G_2_anUnknownMemberIsNotFound() {
        when(jdbc.queryForList(contains("FROM employee e"), eq("999"))).thenReturn(List.of());
        assertThat(code(catchThrowable(() -> service.ofStaff(staff, "999", "202610"))))
                .isEqualTo(CommonErrorCode.NOT_FOUND);
    }

    @Test
    void SRS_G_2_aMemberOutOfScopeReadsAsNotFound() {
        when(jdbc.queryForList(contains("FROM employee e"), eq("104882")))
                .thenReturn(List.of(Map.of("EMPLOYEE_ID", 7L, "LOCATION_ID", 900L)));
        doThrow(new FciException(CommonErrorCode.NOT_FOUND))
                .when(policy)
                .requireRecordAt(staff, "O-01", "VIEW", 900L);
        assertThat(code(catchThrowable(() -> service.ofStaff(staff, "104882", "202610"))))
                .isEqualTo(CommonErrorCode.NOT_FOUND);
    }

    @Test
    void SRS_17_6_anotherUsersOrAClosedSessionReadsAsNotFound() {
        when(jdbc.queryForList(contains("FROM ess_session"), eq(Long.class), anyLong(), any()))
                .thenReturn(List.of());
        assertThat(code(catchThrowable(() -> service.ofPreview(staff, 5L, "202610"))))
                .isEqualTo(CommonErrorCode.NOT_FOUND);
    }
}
