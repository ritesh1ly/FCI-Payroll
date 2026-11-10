package in.gov.fci.payroll.workflow.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.workflow.business.model.ApprovalItem;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SRS-G-13, UC-07 A2 (US-GAP-21): bulk approval takes each selected item through the one decision
 * path on its own, with the bulk reference written once on its decision row; an item its action
 * does not allow in bulk, or that needs an OTP, is refused with the reason, as is one the decision
 * path refuses.
 */
class BulkApprovalServiceTest {

    private final ApprovalService approvals = mock(ApprovalService.class);
    private final WorkflowRepositories.Approvals items = mock(WorkflowRepositories.Approvals.class);
    private final TransactionTemplate each = mock(TransactionTemplate.class);
    private final AccessContext checker = mock(AccessContext.class);
    private final BulkApprovalService service =
            new BulkApprovalService(
                    approvals,
                    items,
                    each,
                    Clock.fixed(Instant.parse("2026-10-31T06:00:00Z"), ZoneOffset.UTC));

    @SuppressWarnings("unchecked")
    BulkApprovalServiceTest() {
        when(checker.loginId()).thenReturn("ddo.delhi");
        doAnswer(
                        i -> {
                            ((Consumer<TransactionStatus>) i.getArgument(0))
                                    .accept(mock(TransactionStatus.class));
                            return null;
                        })
                .when(each)
                .executeWithoutResult(any());
    }

    @Test
    void SRS_G_13_eachItemIsApprovedOnItsOwnAndTheRestAreRefusedWithTheReason() {
        ApprovalItem ok = mock(ApprovalItem.class);
        ApprovalItem notBulk = mock(ApprovalItem.class);
        ApprovalItem own = mock(ApprovalItem.class);
        when(items.findById(1L)).thenReturn(Optional.of(ok));
        when(items.findById(2L)).thenReturn(Optional.of(notBulk));
        when(items.findById(3L)).thenReturn(Optional.of(own));
        when(items.findById(4L)).thenReturn(Optional.empty());
        when(approvals.bulkEligible(ok)).thenReturn(true);
        when(approvals.bulkEligible(notBulk)).thenReturn(false);
        when(approvals.bulkEligible(own)).thenReturn(true);
        // UC-07 A2: the identity rule still holds: the Maker's own item is refused.
        when(approvals.decide(
                        eq(checker),
                        eq(3L),
                        eq(ApprovalService.Decision.APPROVE),
                        eq("ok"),
                        isNull(),
                        anyString()))
                .thenThrow(new FciException(ApiErrorCode.SELF_APPROVAL));

        BulkApprovalService.Outcome out =
                service.approve(checker, List.of(1L, 2L, 3L, 4L, 1L), "ok");

        assertThat(out.approved()).containsExactly(1L);
        assertThat(out.refused())
                .containsExactly(
                        new BulkApprovalService.Refused(2L, "BULK_NOT_ALLOWED"),
                        new BulkApprovalService.Refused(3L, ApiErrorCode.SELF_APPROVAL.code()),
                        new BulkApprovalService.Refused(4L, CommonErrorCode.NOT_FOUND.code()));
        assertThat(out.reference()).startsWith("BULK-20261031").hasSizeLessThanOrEqualTo(40);
        // Decided through the one path, no OTP (none is raised for a batch), with the reference.
        verify(approvals)
                .decide(checker, 1L, ApprovalService.Decision.APPROVE, "ok", null, out.reference());
        verify(approvals, never()).decide(any(), eq(2L), any(), any(), any(), any());
    }

    @Test
    void SRS_G_13_anEmptyOrOversizedSelectionIsRefused() {
        assertThatThrownBy(() -> service.approve(checker, List.of(), null))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.approve(checker, null, null))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.approve(checker, Collections.singletonList(null), null))
                .isInstanceOf(FciException.class);
        List<Long> many =
                LongStream.rangeClosed(1, BulkApprovalService.MAX_ITEMS + 1).boxed().toList();
        assertThatThrownBy(() -> service.approve(checker, many, null))
                .isInstanceOf(FciException.class);
    }
}
