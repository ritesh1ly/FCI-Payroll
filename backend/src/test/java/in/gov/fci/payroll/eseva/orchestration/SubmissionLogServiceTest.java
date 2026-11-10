package in.gov.fci.payroll.eseva.orchestration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.selfservice.SelfServiceEvents;
import in.gov.fci.payroll.platform.selfservice.SelfServiceEvents.Status;
import in.gov.fci.payroll.platform.selfservice.SelfServiceEvents.Type;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * The Self-Service Submission Log (plan 14 phases 14a / 14e; SRS-17-3, SRS-17-12, SRS-17-18,
 * BR-ESEVA-006): a submission is recorded SUBMITTED, or ROUTED when it names its approval, and the
 * member is told; a record without an id has no row; the owning module's status moves only rows not
 * yet final and may route a row to an approval; the approval's decision then actions or rejects it,
 * and a return leaves it with the owning module; each move is told to the member.
 */
class SubmissionLogServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MemberNotifications notifications = mock(MemberNotifications.class);
    private final SubmissionLogService log =
            new SubmissionLogService(
                    jdbc,
                    mock(NamedParameterJdbcTemplate.class),
                    mock(Authorization.class),
                    notifications,
                    Clock.fixed(Instant.parse("2026-10-28T05:00:00Z"), ZoneOffset.UTC));

    private static SelfServiceEvents.Submitted submitted(Long entity, Long approval) {
        return new SelfServiceEvents.Submitted(
                7L,
                Type.CPF_ADVANCE_REQUEST,
                "pf_sanction",
                entity,
                null,
                null,
                null,
                "CPF ADVANCE é of 50000",
                approval,
                "member");
    }

    private void key(Long id) {
        doAnswer(
                        call -> {
                            if (id != null) {
                                ((GeneratedKeyHolder) call.getArgument(1))
                                        .getKeyList()
                                        .add(Map.of("SUBMISSION_ID", id));
                            }
                            return 1;
                        })
                .when(jdbc)
                .update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }

    @Test
    void SRS_17_12_aSubmissionIsRecordedSubmittedOrRoutedAndTheMemberIsTold() {
        key(31L);
        log.on(submitted(12L, null));
        verify(notifications)
                .statusChanged(
                        7L, 31L, "CPF_ADVANCE_REQUEST", "SUBMITTED", "CPF ADVANCE   of 50000");
        log.on(submitted(12L, 44L));
        verify(notifications)
                .statusChanged(eq(7L), eq(31L), anyString(), eq("ROUTED"), anyString());
        log.on(submitted(null, null));
        verify(jdbc, times(2)).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        key(null);
        assertThatThrownBy(() -> log.on(submitted(12L, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    private void rows(String status) {
        Map<String, Object> r = new HashMap<>();
        r.put("SUBMISSION_ID", 3L);
        r.put("SUBMISSION_STATUS", status);
        r.put("EMPLOYEE_ID", 7L);
        r.put("SUBMISSION_TYPE", "VPF_SUBSCRIPTION");
        r.put("SUBMITTED_VALUE", "VPF 3000");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(r));
    }

    @Test
    void SRS_17_18_theOwningModulesStatusMovesOnlyRowsNotYetFinal() {
        rows("SUBMITTED");
        log.on(new SelfServiceEvents.StatusChanged("pf_sanction", 12L, Status.ROUTED, 44L, "u"));
        verify(jdbc).update(contains("SET routed_to_approval_id"), eq(44L), eq(3L));
        verify(jdbc).update(contains("SET submission_status"), eq("ROUTED"), eq(3L));
        verify(notifications).statusChanged(7L, 3L, "VPF_SUBSCRIPTION", "ROUTED", "VPF 3000");

        rows("ACTIONED");
        log.on(new SelfServiceEvents.StatusChanged("pf_sanction", 12L, Status.REJECTED, 45L, "u"));
        verify(jdbc, never()).update(contains("SET routed_to_approval_id"), eq(45L), any());
        verify(jdbc, never()).update(contains("SET submission_status"), eq("REJECTED"), any());

        rows("ROUTED");
        log.on(new SelfServiceEvents.StatusChanged("pf_sanction", 12L, Status.ACTIONED, "u"));
        verify(jdbc).update(contains("SET submission_status"), eq("ACTIONED"), eq(3L));
        log.on(new SelfServiceEvents.StatusChanged("pf_sanction", null, Status.ACTIONED, "u"));
        verify(notifications, never())
                .statusChanged(anyLong(), anyLong(), anyString(), eq("REJECTED"), anyString());
    }

    private static ApprovalDecided decided(ApprovalStatus outcome) {
        return new ApprovalDecided(
                44L, "W-01", "x", "1", outcome, null, null, "checker", Instant.now());
    }

    @Test
    void BR_ESEVA_006_theApprovalsDecisionActionsOrRejectsAndAReturnLeavesIt() {
        rows("ROUTED");
        log.on(decided(ApprovalStatus.RETURNED));
        verify(jdbc, never())
                .queryForList(contains("routed_to_approval_id = ?"), any(Object[].class));
        log.on(decided(ApprovalStatus.APPROVED));
        verify(jdbc).update(contains("SET submission_status"), eq("ACTIONED"), eq(3L));
        log.on(decided(ApprovalStatus.REJECTED));
        verify(jdbc).update(contains("SET submission_status"), eq("REJECTED"), eq(3L));
    }
}
