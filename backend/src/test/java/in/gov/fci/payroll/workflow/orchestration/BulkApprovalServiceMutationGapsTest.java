package in.gov.fci.payroll.workflow.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** SRS-G-13 mutation gap: the limit is inclusive, exactly MAX_ITEMS items are taken. */
class BulkApprovalServiceMutationGapsTest {

    private final WorkflowRepositories.Approvals items = mock(WorkflowRepositories.Approvals.class);
    private final TransactionTemplate each = mock(TransactionTemplate.class);
    private final BulkApprovalService service =
            new BulkApprovalService(
                    mock(ApprovalService.class),
                    items,
                    each,
                    Clock.fixed(Instant.parse("2026-10-31T06:00:00Z"), ZoneOffset.UTC));

    @SuppressWarnings("unchecked")
    BulkApprovalServiceMutationGapsTest() {
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
    void SRS_G_13_exactlyTheMaximumNumberOfItemsIsTakenOneMoreIsRefused() {
        AccessContext checker = mock(AccessContext.class);
        List<Long> exactly =
                LongStream.rangeClosed(1, BulkApprovalService.MAX_ITEMS).boxed().toList();
        BulkApprovalService.Outcome out = service.approve(checker, exactly, "ok");
        assertThat(out.refused()).hasSize(BulkApprovalService.MAX_ITEMS);
        assertThat(out.refused())
                .allMatch(r -> "NOT_FOUND".equals(r.reason()) || r.reason() != null);
        assertThat(out.approved()).isEmpty();

        List<Long> tooMany =
                LongStream.rangeClosed(1, BulkApprovalService.MAX_ITEMS + 1L).boxed().toList();
        assertThatThrownBy(() -> service.approve(checker, tooMany, "ok"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }
}
