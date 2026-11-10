package in.gov.fci.payroll.jobs.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.jobs.batch.queue.JobClaimer;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.orchestration.JobJournal;
import in.gov.fci.payroll.jobs.orchestration.JobRequestService;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.AccessMode;
import in.gov.fci.payroll.platform.security.ActiveGroup;
import in.gov.fci.payroll.platform.security.ScopeType;
import in.gov.fci.payroll.workflow.orchestration.ApprovalService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Maker-checker of a batch job: the maker's submission waits for a Payroll Checker of the unit, is
 * visible to the checker (approval inbox and C-13), is never claimed by a batch node before the
 * approval, cannot be approved by its own maker, and enters the queue only when approved.
 */
class JobMakerCheckerIT extends BatchTestSupport {

    @Autowired private JobRequestService service;
    @Autowired private ApprovalService approvals;
    @Autowired private JobClaimer claimer;
    @Autowired private JobJournal journal;
    @Autowired private AccessRepositories.Users users;
    @Autowired private TransactionTemplate tx;

    /** One poll of a batch node: what it would claim now. */
    private List<Long> claim() {
        List<BatchJobRequest> claimed = tx.execute(s -> claimer.claimPending(10));
        return claimed == null ? List.of() : claimed.stream().map(BatchJobRequest::getId).toList();
    }

    private AccessContext context(
            String login, String role, Set<String> permissions, Long locationId) {
        Long userId = users.findByLoginId(login).orElseThrow().getId();
        ActiveGroup group =
                new ActiveGroup(
                        1L,
                        "AG-T",
                        "Test",
                        1L,
                        role,
                        role,
                        ScopeType.DIVISION,
                        false,
                        Set.of(locationId),
                        Set.of(),
                        AccessMode.FULL);
        return new AccessContext(
                userId,
                login,
                login,
                "s-" + login,
                "X",
                locationId,
                null,
                List.of(group),
                Map.of(1L, permissions),
                Set.of(),
                Set.of(),
                Set.of(locationId));
    }

    @Test
    void aMakersJobWaitsForTheCheckerAndRunsOnlyAfterApproval() {
        Long location = org.byCode("DH001").orElseThrow().getId();
        AccessContext maker =
                context(
                        "maker.delhi@fci-demo.test",
                        "PAYROLL_MAKER",
                        Set.of("C-13:VIEW", "C-13:CREATE", "C-13:EDIT"),
                        location);
        AccessContext checker =
                context(
                        "ddo.delhi@fci-demo.test",
                        "PAYROLL_CHECKER",
                        Set.of("C-13:VIEW", "C-13:APPROVE", "W-01:VIEW", "W-01:APPROVE"),
                        location);
        // Every batch test class may share this context (one test JVM on a small CI runner): close
        // the open requests other classes left for the same job and parameters (duplicate guard).
        jdbc.update(
                "UPDATE batch_job_request SET job_status = 'COMPLETED' WHERE job_status IN"
                        + " ('AWAITING_APPROVAL', 'PENDING', 'PICKED', 'RUNNING', 'PAUSED')");

        JobRequestService.JobView submitted =
                service.submit(
                        maker,
                        "PAYROLL_DRY_RUN",
                        Map.of("locationId", location.toString(), "payPeriod", "2026-07"),
                        null,
                        "July dry run before verification");

        assertThat(submitted.status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(submitted.approvalId()).isNotNull();
        assertThat(submitted.canDecide()).as("the maker may not decide").isFalse();
        // No batch node takes it before the approval.
        assertThat(claim()).doesNotContain(submitted.id());

        // The checker sees it: in the approval inbox and on C-13 with the decision offered.
        assertThat(approvals.inbox(checker, new PageSpec(0, 100)).items())
                .extracting(ApprovalService.ItemView::approvalId)
                .contains(submitted.approvalId());
        assertThat(service.detail(checker, submitted.id()).job().canDecide()).isTrue();

        // The maker cannot approve their own job.
        assertThatThrownBy(
                        () ->
                                approvals.decide(
                                        maker,
                                        submitted.approvalId(),
                                        ApprovalService.Decision.APPROVE,
                                        null,
                                        null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.MAKER_CHECKER));

        approvals.decide(
                checker, submitted.approvalId(), ApprovalService.Decision.APPROVE, null, null);

        BatchJobRequest approved = requests.findById(submitted.id()).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(journal.audit(submitted.id()))
                .extracting(JobJournal.AuditEntry::event)
                .containsSubsequence("SUBMITTED", "APPROVED");
        assertThat(claim()).contains(submitted.id());
    }

    @Test
    void aRejectedJobNeverRuns() {
        Long location = org.byCode("DH001").orElseThrow().getId();
        AccessContext maker =
                context(
                        "maker.delhi@fci-demo.test",
                        "PAYROLL_MAKER",
                        Set.of("C-13:VIEW", "C-13:CREATE"),
                        location);
        AccessContext checker =
                context(
                        "ddo.delhi@fci-demo.test",
                        "PAYROLL_CHECKER",
                        Set.of("C-13:VIEW", "C-13:APPROVE"),
                        location);

        JobRequestService.JobView submitted =
                service.submit(
                        maker,
                        "DA_ARREAR",
                        Map.of(
                                "locationId",
                                location.toString(),
                                "fromPeriod",
                                "2026-01",
                                "toPeriod",
                                "2026-03"),
                        null,
                        "Arrear check");
        approvals.decide(
                checker,
                submitted.approvalId(),
                ApprovalService.Decision.REJECT,
                "Wrong range",
                null);

        BatchJobRequest rejected = requests.findById(submitted.id()).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(JobStatus.REJECTED);
        assertThat(rejected.getErrorMessage()).contains("Wrong range");
        assertThat(claim()).doesNotContain(submitted.id());
    }
}
