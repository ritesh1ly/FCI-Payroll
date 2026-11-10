package in.gov.fci.payroll.eseva.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SRS-17-9: a notice that exists and is another member's reads as not found and is logged; a notice
 * that does not exist answers the same and logs nothing, since it is no attempt on anyone.
 */
class ForeignRecordEventTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final MemberNotifications notices =
            new MemberNotifications(
                    jdbc,
                    policy,
                    Clock.fixed(Instant.parse("2026-11-07T06:00:00Z"), ZoneOffset.UTC));
    private final AccessContext member =
            new AccessContext(
                    3L, "ess@x", "Member", "s", "P", null, 7L, List.of(), Map.of(), Set.of(),
                    Set.of(), Set.of());

    private void held(long rows) {
        when(jdbc.queryForObject(contains("COUNT(*)"), eq(Long.class), eq(9L))).thenReturn(rows);
    }

    @Test
    void SRS_17_9_anotherMembersNoticeIsNotFoundAndIsLogged() {
        held(1);
        Object thrown = catchThrowable(() -> notices.read(member, 9L));
        assertThat(((FciException) thrown).getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
        assertThat(((FciException) thrown).isAccessOffence())
                .as("the answer stays plain")
                .isFalse();
        verify(policy).recordForeignAttempt(member, "E-01", "SELF", "notification", 9L);
    }

    @Test
    void SRS_17_9_aNoticeThatDoesNotExistAnswersTheSameAndLogsNothing() {
        held(0);
        Object thrown = catchThrowable(() -> notices.read(member, 9L));
        assertThat(((FciException) thrown).getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
        verify(policy, never())
                .recordForeignAttempt(any(), anyString(), anyString(), anyString(), any());
    }
}
