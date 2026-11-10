package in.gov.fci.payroll.workflow.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.workflow.business.model.ApprovalItem;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * R-MC mutation gaps of the status port: the status of each existing id, nothing read for no ids,
 * and the pending items of one entity newest first.
 */
class ApprovalStatusReaderMutationGapsTest {

    private static final Instant AT = Instant.parse("2026-11-01T05:00:00Z");

    private final WorkflowRepositories.Approvals approvals =
            mock(WorkflowRepositories.Approvals.class);
    private final ApprovalStatusReader reader = new ApprovalStatusReader(approvals);

    private static ApprovalItem item(long id, ApprovalStatus status, String key) {
        ApprovalItem item =
                ApprovalItem.submit(
                        "O-05",
                        1L,
                        2,
                        "final_settlement",
                        key,
                        ActionType.UPDATE,
                        "{\"k\":" + id + "}",
                        LocalDate.of(2026, 11, 1),
                        "maker@x",
                        "Settlement",
                        null,
                        null,
                        AT);
        ReflectionTestUtils.setField(item, "id", id);
        ReflectionTestUtils.setField(item, "status", status);
        return item;
    }

    @Test
    void R_MC_theStatusOfEachExistingApprovalIsReturned() {
        when(approvals.findAllById(List.of(1L, 2L, 3L)))
                .thenReturn(
                        List.of(
                                item(1L, ApprovalStatus.APPROVED, "F-1"),
                                item(2L, ApprovalStatus.PENDING_APPROVAL, "F-2")));
        Map<Long, ApprovalStatus> result = reader.of(List.of(1L, 2L, 3L));
        assertThat(result)
                .containsOnly(
                        Map.entry(1L, ApprovalStatus.APPROVED),
                        Map.entry(2L, ApprovalStatus.PENDING_APPROVAL));
    }

    @Test
    void R_MC_noIdsReadNothing() {
        assertThat(reader.of(List.of())).isEmpty();
        verify(approvals, never()).findAllById(List.of());
    }

    @Test
    void R_MC_theApprovedOnesAreFilteredFromTheStatuses() {
        when(approvals.findAllById(List.of(1L, 2L)))
                .thenReturn(
                        List.of(
                                item(1L, ApprovalStatus.APPROVED, "F-1"),
                                item(2L, ApprovalStatus.PENDING_APPROVAL, "F-2")));
        assertThat(reader.approved(List.of(1L, 2L))).containsExactly(1L);
    }

    @Test
    void R_MC_thePendingItemsOfAnEntityAreListedWithTheirMaker() {
        ApprovalItem newer = item(62L, ApprovalStatus.PENDING_APPROVAL, "F-2");
        ApprovalItem older = item(61L, ApprovalStatus.PENDING_APPROVAL, "F-1");
        when(approvals.findByEntityNameAndStatusOrderBySubmittedAtDesc(
                        "final_settlement", ApprovalStatus.PENDING_APPROVAL))
                .thenReturn(List.of(newer, older));
        assertThat(reader.pending("final_settlement"))
                .containsExactly(
                        new ApprovalStatuses.Pending(62L, "F-2", "{\"k\":62}", "maker@x", AT),
                        new ApprovalStatuses.Pending(61L, "F-1", "{\"k\":61}", "maker@x", AT));
        assertThat(reader.pending("other_entity")).isEmpty();
    }
}
