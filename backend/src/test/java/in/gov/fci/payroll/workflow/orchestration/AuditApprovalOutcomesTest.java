package in.gov.fci.payroll.workflow.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.ApprovalOutcomes.Outcome;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.workflow.business.model.ApprovalItem;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** X-04 shows the approval behind an audit row: its status, maker and checker (SRS-G-2). */
class AuditApprovalOutcomesTest {

    private static final Instant NOW = Instant.parse("2026-10-23T05:00:00Z");

    private final WorkflowRepositories.Approvals approvals =
            mock(WorkflowRepositories.Approvals.class);
    private final AuditApprovalOutcomes outcomes = new AuditApprovalOutcomes(approvals);

    private static ApprovalItem item(long id, ApprovalStatus status) {
        ApprovalItem item =
                ApprovalItem.submit(
                        "I-20",
                        30L,
                        2,
                        "off_cycle_payment",
                        "OC-" + id,
                        ActionType.CREATE,
                        "{}",
                        LocalDate.of(2026, 10, 23),
                        "maker",
                        "Monthly claims",
                        null,
                        null,
                        NOW);
        ReflectionTestUtils.setField(item, "id", id);
        ReflectionTestUtils.setField(item, "status", status);
        return item;
    }

    @Test
    void SRS_G_2_eachAuditedApprovalShowsItsStatusMakerAndChecker() {
        ApprovalItem decided = item(5L, ApprovalStatus.APPROVED);
        ReflectionTestUtils.setField(decided, "decidedBy", "checker");
        ReflectionTestUtils.setField(decided, "decidedAt", NOW.plusSeconds(60));
        ApprovalItem unknownState = item(6L, null);
        when(approvals.findAllById(List.of(5L, 6L))).thenReturn(List.of(decided, unknownState));
        Map<Long, Outcome> result = outcomes.outcomes(List.of(5L, 6L));
        assertThat(result).containsOnlyKeys(5L, 6L);
        Outcome approved = result.get(5L);
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.maker()).isEqualTo("maker");
        assertThat(approved.checker()).isEqualTo("checker");
        assertThat(approved.decidedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(result.get(6L).status()).isNull();
        assertThat(result.get(6L).checker()).isNull();
    }

    @Test
    void SRS_G_2_auditRowsWithoutAnApprovalReadNothing() {
        assertThat(outcomes.outcomes(null)).isEmpty();
        assertThat(outcomes.outcomes(List.of())).isEmpty();
        verify(approvals, never()).findAllById(any());
    }
}
