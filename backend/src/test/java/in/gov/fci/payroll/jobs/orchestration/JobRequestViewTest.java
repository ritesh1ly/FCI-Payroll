package in.gov.fci.payroll.jobs.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.jobs.batch.support.BatchHistory;
import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.config.BatchProperties;
import in.gov.fci.payroll.jobs.orchestration.JobRequestService.AttemptView;
import in.gov.fci.payroll.jobs.orchestration.JobRequestService.JobDetail;
import in.gov.fci.payroll.jobs.orchestration.JobRequestService.JobQuery;
import in.gov.fci.payroll.jobs.orchestration.JobRequestService.JobView;
import in.gov.fci.payroll.jobs.orchestration.JobRequestService.StepView;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * What C-13 shows of a job from the Spring Batch metadata (SRS-NF-2, SRS-EH-7): a running job's
 * live counts are its workers' and plain steps', so a partitioned step shows progress while it runs
 * and is never counted twice once its manager has summed the workers; a job whose batch history is
 * gone still shows what the queue recorded; an attempt lists its steps in the order they ran, with
 * the items each partition planned and no times for a step not yet started; the list sorts only on
 * its sortable columns and pages within its limits.
 */
class JobRequestViewTest {

    private static final Instant NOW = Instant.parse("2026-10-22T06:00:00Z");
    private static final LocalDateTime STARTED = LocalDateTime.of(2026, 10, 22, 11, 0);
    private static final String MANAGER = "payrollComputationStep";
    private static final String WORKER = "payrollComputationWorker";

    private final JobRepositories.JobRequests requests = mock(JobRepositories.JobRequests.class);
    private final BatchHistory history = mock(BatchHistory.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final Authorization policy = mock(Authorization.class);

    private final JobRequestService service =
            new JobRequestService(
                    requests,
                    mock(JobRepositories.Controls.class),
                    mock(JobCatalog.class),
                    mock(JobJournal.class),
                    history,
                    policy,
                    mock(OrgDirectory.class),
                    mock(AuditService.class),
                    mock(MakerChecker.class),
                    mock(BatchProperties.class),
                    mock(ApiProperties.class),
                    List.of(),
                    statuses,
                    mock(JobRepositories.Halts.class),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private final AccessContext viewer =
            new AccessContext(
                    1L,
                    "viewer@x",
                    "Viewer",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));

    /** A dry run at office 10, queued ten minutes before NOW. */
    private static BatchJobRequest queued() {
        return BatchJobRequest.submit(
                JobType.PAYROLL_DRY_RUN,
                "{\"locationId\":\"10\",\"payPeriod\":\"2026-09\"}",
                "hash",
                5,
                BatchJobRequest.Source.UI,
                2L,
                "maker@x",
                10L,
                "2026-09",
                NOW.minusSeconds(600));
    }

    /** Started five minutes before NOW as the given Spring Batch execution. */
    private static BatchJobRequest running(long executionId) {
        BatchJobRequest request = queued();
        request.pick("node-1", NOW.minusSeconds(300));
        request.started(executionId, NOW.minusSeconds(300));
        return request;
    }

    private static JobExecution execution(long id, JobInstance instance) {
        JobExecution execution = new JobExecution(id, instance, new JobParameters());
        execution.setStatus(BatchStatus.STARTED);
        execution.setExitStatus(ExitStatus.EXECUTING);
        execution.setStartTime(STARTED);
        return execution;
    }

    private static StepExecution step(
            JobExecution job, long id, String name, long read, long written, long skipped) {
        StepExecution step = new StepExecution(id, name, job);
        step.setReadCount(read);
        step.setWriteCount(written);
        step.setProcessSkipCount(skipped);
        step.setStatus(BatchStatus.COMPLETED);
        step.setExitStatus(ExitStatus.COMPLETED);
        step.setStartTime(STARTED);
        step.setEndTime(STARTED.plusMinutes(2));
        return step;
    }

    /** The manager of a partitioned step, as the batch node marked it in the repository. */
    private static StepExecution manager(
            JobExecution job, long id, long read, long written, long skipped) {
        StepExecution manager = step(job, id, MANAGER, read, written, skipped);
        manager.getExecutionContext().putString(BatchKeys.PARTITION_MANAGER, MANAGER);
        return manager;
    }

    private void listed(BatchJobRequest... rows) {
        when(requests.findAll(
                        ArgumentMatchers.<Specification<BatchJobRequest>>any(),
                        any(Pageable.class)))
                .thenAnswer(i -> new PageImpl<>(List.of(rows), i.getArgument(1), rows.length));
    }

    private static JobQuery query(String sort, boolean ascending, int page, int size) {
        return new JobQuery(null, null, null, null, null, null, page, size, sort, ascending);
    }

    private Pageable requested() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(requests)
                .findAll(
                        ArgumentMatchers.<Specification<BatchJobRequest>>any(), pageable.capture());
        return pageable.getValue();
    }

    @Test
    void SRS_NF_2_aRunningPartitionedStepShowsItsWorkersProgressBeforeTheManagerSumsThem() {
        JobExecution execution = execution(77L, new JobInstance(3L, "payrollDryRunJob"));
        execution.addStepExecutions(
                List.of(
                        step(execution, 1L, "prepareStep", 1, 1, 0),
                        // Spring Batch adds the workers into the manager only when the step ends.
                        manager(execution, 2L, 0, 0, 0),
                        step(execution, 3L, WORKER + ":partition0", 60, 59, 1),
                        step(execution, 4L, WORKER + ":partition1", 55, 53, 2)));
        when(history.getJobExecution(77L)).thenReturn(execution);
        listed(running(77L));

        JobView view = service.page(viewer, query("requestedAt", false, 0, 20)).items().getFirst();

        assertThat(view.status()).isEqualTo("RUNNING");
        assertThat(view.readCount()).isEqualTo(116);
        assertThat(view.writeCount()).isEqualTo(113);
        assertThat(view.skipCount()).isEqualTo(3);
        assertThat(view.durationSeconds()).as("running for five minutes").isEqualTo(300);
    }

    @Test
    void SRS_NF_2_aPartitionedStepThatHasEndedIsNotCountedTwiceWhileALaterStepRuns() {
        JobExecution execution = execution(76L, new JobInstance(3L, "payrollDryRunJob"));
        execution.addStepExecutions(
                List.of(
                        // The manager has summed its workers: 115 read, 112 written, 3 skipped.
                        manager(execution, 2L, 115, 112, 3),
                        step(execution, 3L, WORKER + ":partition0", 60, 59, 1),
                        step(execution, 4L, WORKER + ":partition1", 55, 53, 2),
                        step(execution, 5L, "summaryStep", 0, 0, 0)));
        when(history.getJobExecution(76L)).thenReturn(execution);
        listed(running(76L));

        JobView view = service.page(viewer, query("requestedAt", false, 0, 20)).items().getFirst();

        assertThat(view.readCount()).isEqualTo(115);
        assertThat(view.writeCount()).isEqualTo(112);
        assertThat(view.skipCount()).isEqualTo(3);
    }

    @Test
    void SRS_EH_7_aJobWithoutLiveBatchHistoryShowsWhatTheQueueRecorded() {
        // The execution of a running job is gone (metadata purged or not yet visible).
        BatchJobRequest purged = running(78L);
        when(history.getJobExecution(78L)).thenReturn(null);
        // A finished job is never read live: its counts are final on the request.
        BatchJobRequest finished = running(79L);
        finished.finished(JobStatus.COMPLETED, 40, 39, 1, "40 employees", null, NOW);
        BatchJobRequest waiting = queued();
        listed(purged, finished, waiting);

        List<JobView> items = service.page(viewer, query("requestedAt", false, 0, 20)).items();

        assertThat(items).extracting(JobView::readCount).containsExactly(0L, 40L, 0L);
        assertThat(items).extracting(JobView::writeCount).containsExactly(0L, 39L, 0L);
        assertThat(items).extracting(JobView::skipCount).containsExactly(0L, 1L, 0L);
        assertThat(items.get(2).durationSeconds()).as("not started").isNull();
        verify(history, never()).getJobExecution(79L);
    }

    @Test
    void SRS_EH_7_aJobWhoseExecutionIsGoneHasNoAttemptsButKeepsItsRecord() {
        BatchJobRequest done = running(80L);
        done.finished(JobStatus.COMPLETED, 40, 40, 0, "40 employees", null, NOW);
        when(requests.findById(5L)).thenReturn(Optional.of(done));
        when(history.getJobExecution(80L)).thenReturn(null);

        JobDetail detail = service.detail(viewer, 5L);

        assertThat(detail.attempts()).isEmpty();
        assertThat(detail.job().readCount()).isEqualTo(40);
        assertThat(detail.job().resultSummary()).isEqualTo("40 employees");
        assertThat(detail.job().durationSeconds()).isEqualTo(300);
        verify(history, never()).getJobExecutions(any());
    }

    @Test
    void SRS_NF_2_anAttemptListsItsStepsInRunOrderWithPlannedItemsAndNoTimesBeforeAStepStarts() {
        JobInstance instance = new JobInstance(3L, "payrollDryRunJob");
        JobExecution failed = execution(80L, instance);
        failed.setStatus(BatchStatus.FAILED);
        failed.setExitStatus(new ExitStatus("FAILED", "No pay scale"));
        failed.setEndTime(STARTED.plusMinutes(1));
        failed.addStepExecutions(List.of(manager(failed, 5L, 10, 9, 1)));
        JobExecution current = execution(81L, instance);
        StepExecution manager = manager(current, 7L, 0, 0, 0);
        StepExecution worker = step(current, 8L, WORKER + ":partition0", 20, 20, 0);
        worker.getExecutionContext().putLong(BatchKeys.PLANNED, 40L);
        StepExecution pending = new StepExecution(9L, "summaryStep", current);
        // Listed out of order: the view orders by execution id.
        current.addStepExecutions(List.of(pending, worker, manager));
        when(history.getJobExecution(81L)).thenReturn(current);
        when(history.getJobExecutions(instance)).thenReturn(List.of(current, failed));
        when(requests.findById(6L)).thenReturn(Optional.of(running(81L)));

        JobDetail detail = service.detail(viewer, 6L);

        assertThat(detail.attempts())
                .extracting(AttemptView::jobExecutionId)
                .containsExactly(81L, 80L);
        AttemptView first = detail.attempts().get(1);
        assertThat(first.status()).isEqualTo("FAILED");
        assertThat(first.exitDescription()).isEqualTo("No pay scale");
        assertThat(first.endTime())
                .isEqualTo(STARTED.plusMinutes(1).atZone(ZoneId.systemDefault()).toInstant());
        List<StepView> steps = detail.attempts().getFirst().steps();
        assertThat(steps)
                .extracting(StepView::name)
                .containsExactly(MANAGER, WORKER + ":partition0", "summaryStep");
        assertThat(steps).extracting(StepView::partition).containsExactly(false, true, false);
        assertThat(steps).extracting(StepView::plannedItems).containsExactly(null, 40L, null);
        StepView notStarted = steps.get(2);
        assertThat(notStarted.startTime()).isNull();
        assertThat(notStarted.endTime()).isNull();
        assertThat(notStarted.status()).isEqualTo("STARTING");
        assertThat(detail.attempts().getFirst().endTime()).as("still running").isNull();
        // The live counts of the running attempt: its worker's, not the manager's.
        assertThat(detail.job().readCount()).isEqualTo(20);
    }

    @Test
    void SRS_NF_2_theListSortsOnlyOnItsColumnsAndPagesWithinItsLimits() {
        listed();
        service.page(viewer, query("employeeName", true, -3, 500));
        Pageable pageable = requested();
        assertThat(pageable.getSort())
                .as("an unknown column sorts by the request time, newest id last to break ties")
                .containsExactly(Sort.Order.asc("requestedAt"), Sort.Order.desc("id"));
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(100);
    }

    @Test
    void SRS_NF_2_aSortableColumnIsUsedAsAskedAndTheSmallestPageHoldsOneJob() {
        listed();
        service.page(viewer, query("finishedAt", false, 2, 0));
        Pageable pageable = requested();
        assertThat(pageable.getSort())
                .containsExactly(Sort.Order.desc("finishedAt"), Sort.Order.desc("id"));
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(1);
    }

    /**
     * SRS-15-26: a request whose abandonment awaits the Checker's approval shows that it waits, so
     * nobody asks to abandon it twice.
     */
    @Test
    void SRS_15_26_aRequestWhoseAbandonmentAwaitsApprovalShowsIt() {
        BatchJobRequest request = queued();
        org.springframework.test.util.ReflectionTestUtils.setField(request, "id", 9L);
        listed(request);
        when(statuses.pending(JobRequestService.ABANDON_ENTITY))
                .thenReturn(List.of(new ApprovalStatuses.Pending(61L, "9", "{}", "maker@x", NOW)));
        JobView view = service.page(viewer, query("requestedAt", false, 0, 20)).items().getFirst();
        assertThat(view.pendingCommand()).isEqualTo(JobRequestService.ABANDON_AWAITING_APPROVAL);
    }

    private static BatchJobRequest awaiting(Long approvalId, Long location, long requester) {
        BatchJobRequest request =
                BatchJobRequest.submit(
                        JobType.PAYROLL_DRY_RUN,
                        "{}",
                        "hash",
                        5,
                        BatchJobRequest.Source.UI,
                        requester,
                        "maker@x",
                        location,
                        "2026-09",
                        NOW.minusSeconds(600));
        request.awaitApproval(approvalId);
        return request;
    }

    /**
     * R-MC on C-13 (SRS-15-26): a request awaiting approval is decided by a checker of its office
     * who did not raise it; never one without its approval item or office, never its own requester.
     */
    @Test
    void SRS_15_26_onlyAnotherCheckerOfTheOfficeMayDecideARequestAwaitingApproval() {
        when(policy.canAt(viewer, JobRequestService.SCREEN, "APPROVE", 10L)).thenReturn(true);
        listed(
                awaiting(41L, 10L, 2L),
                awaiting(null, 10L, 2L),
                awaiting(42L, null, 2L),
                awaiting(43L, 10L, 1L));
        assertThat(service.page(viewer, query("requestedAt", false, 0, 20)).items())
                .extracting(JobView::canDecide)
                .containsExactly(true, false, false, false);
    }

    /** SRS-NF-2: a viewer without an office scope sees the national jobs only. */
    @Test
    @SuppressWarnings("unchecked")
    void SRS_NF_2_aViewerWithoutAnOfficeScopeSeesNationalJobsOnly() {
        AccessContext national =
                new AccessContext(
                        3L, "hq@x", "HQ", "s", "P", 1L, null, List.of(), Map.of(), Set.of(),
                        Set.of(), Set.of());
        listed();
        service.page(national, query("requestedAt", false, 0, 20));
        ArgumentCaptor<Specification<BatchJobRequest>> spec = ArgumentCaptor.captor();
        verify(requests).findAll(spec.capture(), any(Pageable.class));
        jakarta.persistence.criteria.Root<BatchJobRequest> root =
                mock(jakarta.persistence.criteria.Root.class);
        jakarta.persistence.criteria.Path<Object> location =
                mock(jakarta.persistence.criteria.Path.class);
        when(root.get("locationId")).thenReturn(location);
        jakarta.persistence.criteria.CriteriaBuilder cb =
                mock(jakarta.persistence.criteria.CriteriaBuilder.class);
        spec.getValue()
                .toPredicate(root, mock(jakarta.persistence.criteria.CriteriaQuery.class), cb);
        verify(cb).isNull(location);
        verify(location, org.mockito.Mockito.never()).in(any(java.util.Collection.class));
    }

    /** R-MC on C-13 (SRS-15-26): a viewer who is not a checker of the office may not decide. */
    @Test
    void SRS_15_26_aViewerWithoutTheApproveRightAtTheOfficeMayNotDecide() {
        when(policy.canAt(viewer, JobRequestService.SCREEN, "APPROVE", 10L)).thenReturn(false);
        listed(awaiting(41L, 10L, 2L));
        assertThat(service.page(viewer, query("requestedAt", false, 0, 20)).items())
                .extracting(JobView::canDecide)
                .containsExactly(false);
    }
}
