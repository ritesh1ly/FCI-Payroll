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
 * R-MC (Wave A review): a module asks whether one of its records waits for a Checker through the
 * {@link ApprovalStatuses} port, never by reading {@code approval_queue}: the pending items of that
 * record, newest first, with who submitted them.
 */
class ApprovalStatusReaderTest {

    private final WorkflowRepositories.Approvals approvals =
            mock(WorkflowRepositories.Approvals.class);
    private final ApprovalStatusReader reader = new ApprovalStatusReader(approvals);

    @Test
    void R_MC_thePendingItemsOfOneRecordNewestFirst() {
        Instant at = Instant.parse("2026-11-01T05:00:00Z");
        ApprovalItem item =
                ApprovalItem.submit(
                        "O-05",
                        1L,
                        2,
                        "final_settlement",
                        "FNF-1",
                        ActionType.UPDATE,
                        "{\"netPayable\":\"10\"}",
                        LocalDate.of(2026, 11, 1),
                        "maker@x",
                        "Settlement",
                        null,
                        null,
                        at);
        ReflectionTestUtils.setField(item, "id", 61L);
        when(approvals.findByEntityNameAndEntityKeyAndStatusOrderBySubmittedAtDescIdDesc(
                        "final_settlement", "FNF-1", ApprovalStatus.PENDING_APPROVAL))
                .thenReturn(List.of(item));
        assertThat(reader.pending("final_settlement", "FNF-1"))
                .containsExactly(
                        new ApprovalStatuses.Pending(
                                61L, "FNF-1", "{\"netPayable\":\"10\"}", "maker@x", at));
        assertThat(reader.pending("final_settlement", "FNF-2")).isEmpty();
    }

    private static ApprovalItem item(long id, String decidedBy) {
        ApprovalItem item =
                ApprovalItem.submit(
                        "M-08",
                        1L,
                        2,
                        "rule_version",
                        "K|" + id,
                        ActionType.CREATE,
                        "{}",
                        LocalDate.of(2026, 11, 1),
                        "admin@x",
                        "Rule",
                        null,
                        null,
                        Instant.parse("2026-11-01T05:00:00Z"));
        ReflectionTestUtils.setField(item, "id", id);
        ReflectionTestUtils.setField(item, "decidedBy", decidedBy);
        return item;
    }

    /**
     * US-GAP-157 (FRS 24 audit): who decided each item, for the M-08 "approved by" column; an item
     * still undecided names nobody, and no ids reads nothing.
     */
    @Test
    void US_GAP_157_theDecidersOfDecidedItemsOnly() {
        when(approvals.findAllById(List.of(1L, 2L)))
                .thenReturn(List.of(item(1L, "rule.approver@x"), item(2L, null)));
        assertThat(reader.deciders(List.of(1L, 2L)))
                .containsExactly(Map.entry(1L, "rule.approver@x"));
        assertThat(reader.deciders(List.of())).isEmpty();
        verify(approvals, never()).findAllById(List.of());
    }
}
