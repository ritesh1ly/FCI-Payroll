package in.gov.fci.payroll.leave.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E-01 leave balance (plan 14 phase 14e; SRS-LV-32): only a member reads it, the own record only.
 */
class LeaveBalanceServiceTest {

    private final LeaveBalanceService balances =
            new LeaveBalanceService(mock(JdbcTemplate.class), mock(Authorization.class));

    private static AccessContext member(Long employeeId) {
        return new AccessContext(
                9L,
                "m",
                "M",
                "s",
                "P",
                5L,
                employeeId,
                List.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of());
    }

    @Test
    void SRS_LV_32_theMemberReadsTheOwnLeaveBalanceFromHrms() {
        assertThatThrownBy(() -> balances.mine(member(null)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(ApiErrorCode.PERMISSION_DENIED));
        assertThat(balances.mine(member(7L))).isEmpty();
    }
}
