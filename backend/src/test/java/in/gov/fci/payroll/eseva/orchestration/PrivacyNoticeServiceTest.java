package in.gov.fci.payroll.eseva.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
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
 * The privacy notice of the first eSeva sign-in (plan 14 phase 14a; SRS-SEC-9; D14-3): shown until
 * the member acknowledges the version in force, recorded once in the audit trail; only a member has
 * one.
 */
class PrivacyNoticeServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final PrivacyNoticeService notices =
            new PrivacyNoticeService(jdbc, mock(Authorization.class), audit);

    private static AccessContext member(Long employeeId) {
        return new AccessContext(
                9L,
                "m",
                "Member",
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

    private void acknowledged(Long count) {
        when(jdbc.queryForObject(contains("FROM audit_event"), eq(Long.class), any(Object[].class)))
                .thenReturn(count);
    }

    @Test
    void SRS_SEC_9_theNoticeIsShownUntilAcknowledgedOnce() {
        acknowledged(null);
        assertThat(notices.mine(member(7L)).acknowledged()).isFalse();
        assertThat(notices.acknowledge(member(7L)).acknowledged()).isTrue();
        acknowledged(1L);
        assertThat(notices.mine(member(7L))).isEqualTo(new PrivacyNoticeService.Notice("1", true));
        notices.acknowledge(member(7L));
        verify(audit, times(1)).record(eq("m"), any());
    }

    @Test
    void SRS_17_9_onlyAMemberHasANotice() {
        assertThatThrownBy(() -> notices.mine(member(null)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(ApiErrorCode.PERMISSION_DENIED));
        verify(audit, never()).record(anyString(), any());
    }
}
