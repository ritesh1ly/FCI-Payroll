package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.approval.ApprovalWithdrawals;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Wave C review (SRS-9-23, SRS-1-28): a waiting I-21 task that a newer HRMS record replaces is
 * closed as SUPERSEDED and audited with its reason; a task no longer waiting (decided, or already
 * superseded - a concurrent intake) is left as it is and nothing is audited; an unknown task is not
 * found.
 */
class HrmsChangeSupersedeTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final ApprovalWithdrawals withdrawals = mock(ApprovalWithdrawals.class);
    private final HrmsChangeService service =
            new HrmsChangeService(
                    jdbc,
                    mock(NamedParameterJdbcTemplate.class),
                    mock(OrgDirectory.class),
                    mock(Authorization.class),
                    mock(MakerChecker.class),
                    audit,
                    mock(SystemParameters.class),
                    mock(PayrollPeriods.class),
                    mock(PayrollRecords.class),
                    mock(HrmsChangeApplier.class),
                    Clock.systemUTC(),
                    mock(DocumentStore.class),
                    withdrawals);

    private void task(String status, int updated) {
        when(jdbc.queryForList(contains("FROM hrms_change_task WHERE hrms_change_task_id"), eq(5L)))
                .thenReturn(List.of(Map.of("TASK_NO", "HCA-1", "TASK_STATUS", status)));
        when(jdbc.update(contains("SET task_status = 'SUPERSEDED'"), eq(9L), eq(5L)))
                .thenReturn(updated);
    }

    @Test
    void SRS_9_23_aWaitingTaskIsSupersededAndAuditedWithItsReason() {
        task("PENDING_CHECKER", 1);
        assertThat(service.supersede(5L, 9L, "system.hrms", "Superseded by a newer record"))
                .isTrue();
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("system.hrms"), entry.capture());
        assertThat(entry.getValue().entityKey()).isEqualTo("HCA-1");
        assertThat(entry.getValue().oldValue()).isEqualTo("PENDING_CHECKER");
        assertThat(entry.getValue().newValue()).isEqualTo("SUPERSEDED");
        assertThat(entry.getValue().reason()).isEqualTo("Superseded by a newer record");
        assertThat(entry.getValue().moduleCode()).isEqualTo("I-21");
        // Wave D review 9: its approval item leaves the Checker's inbox in the same transaction.
        verify(withdrawals)
                .withdraw("hrms_change_task", "5", "system.hrms", "Superseded by a newer record");
    }

    @Test
    void SRS_9_23_aTaskNoLongerWaitingIsLeftAsItIsAndNothingIsAudited() {
        for (String decided :
                new String[] {
                    "APPROVED", "REJECTED_RETURNED_TO_HRMS", "SUPERSEDED", "AUTO_APPLIED"
                }) {
            task(decided, 0);
            assertThat(service.supersede(5L, 9L, "system.hrms", "newer")).as(decided).isFalse();
        }
        verify(audit, never()).record(anyString(), any(AuditService.Entry.class));
        // Wave D review 9: a decided task's approval item is never withdrawn.
        verify(withdrawals, never()).withdraw(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void SRS_9_23_anUnknownTaskIsNotFound() {
        when(jdbc.queryForList(contains("FROM hrms_change_task WHERE hrms_change_task_id"), eq(5L)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.supersede(5L, 9L, "system.hrms", "newer"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
    }
}
