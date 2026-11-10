package in.gov.fci.payroll.jobs.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.jobs.batch.support.BatchHistory;
import in.gov.fci.payroll.jobs.business.model.BatchJobControl;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.ControlCommand;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.config.BatchProperties;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Plan 05j (SRS-15-27, SRS-G-1, SRS-G-37): abandoning a job is a proposal the Payroll Checker
 * decides, only for a failed, stopped or paused job, with a reason, one at a time; while it waits,
 * and while batch processing is halted, nothing is resumed or restarted, though a running job can
 * still be stopped.
 */
class JobAbandonRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");

    private final JobRepositories.JobRequests requests = mock(JobRepositories.JobRequests.class);
    private final JobRepositories.Controls controls = mock(JobRepositories.Controls.class);
    private final JobRepositories.Halts halts = mock(JobRepositories.Halts.class);
    private final Authorization policy = mock(Authorization.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final ApprovalStatuses approvalStatuses = mock(ApprovalStatuses.class);
    private final BatchProperties properties = mock(BatchProperties.class);
    private final JobJournal journal = mock(JobJournal.class);

    private final JobRequestService service =
            new JobRequestService(
                    requests,
                    controls,
                    mock(JobCatalog.class),
                    journal,
                    mock(BatchHistory.class),
                    policy,
                    mock(OrgDirectory.class),
                    mock(AuditService.class),
                    approvals,
                    properties,
                    mock(ApiProperties.class),
                    List.of(),
                    approvalStatuses,
                    halts,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private final AccessContext maker =
            new AccessContext(
                    1L,
                    "maker@x",
                    "Maker",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));

    private final AccessContext admin =
            new AccessContext(
                    9L,
                    "admin@x",
                    "Admin",
                    "s",
                    "P",
                    1L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of("SYSTEM_ADMINISTRATOR"),
                    Set.of(1L, 10L));

    @BeforeEach
    void wire() {
        when(properties.restartWindow()).thenReturn(Duration.ofHours(72));
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(55L, ApprovalStatus.PENDING_APPROVAL, null));
        when(controls.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static ErrorCode code(Throwable t) {
        return ((FciException) t).getErrorCode();
    }

    private static void refused(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).satisfies(e -> assertThat(code(e)).isEqualTo(expected));
    }

    /** Request 7 of the unit {@code locationId}, started as execution 77, now in {@code status}. */
    private BatchJobRequest job(Long locationId, JobStatus status) {
        BatchJobRequest r =
                BatchJobRequest.submit(
                        JobType.PAYROLL_DRY_RUN,
                        "{}",
                        "hash",
                        5,
                        BatchJobRequest.Source.UI,
                        2L,
                        "maker@x",
                        locationId,
                        "2026-09",
                        NOW.minusSeconds(7_200));
        ReflectionTestUtils.setField(r, "id", 7L);
        if (status != JobStatus.PENDING) {
            r.pick("node-1", NOW.minusSeconds(3_600));
            r.started(77L, NOW.minusSeconds(3_600));
            ReflectionTestUtils.setField(r, "status", status);
        }
        when(requests.findById(7L)).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void OD_130_controllingAJobOfNoUnitWithoutTheAdministratorRoleIsAnAccessOffence() {
        job(null, JobStatus.FAILED);

        assertThatThrownBy(() -> service.requestAbandon(maker, 7L, "Not wanted"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.PERMISSION_DENIED);
                            assertThat(e.isAccessOffence()).isTrue();
                        });
    }

    @Test
    void SRS_G_37_anAbandonNeedsItsReason() {
        job(10L, JobStatus.FAILED);
        for (String missing : Arrays.asList(null, "", " \t")) {
            refused(() -> service.requestAbandon(maker, 7L, missing), ApiErrorCode.REASON_REQUIRED);
        }
        verify(approvals, never()).submit(any(), any(), any());
    }

    @Test
    void SRS_15_27_onlyAFailedStoppedOrPausedJobCanBeAbandoned() {
        for (JobStatus live : List.of(JobStatus.PENDING, JobStatus.RUNNING, JobStatus.COMPLETED)) {
            job(10L, live);
            refused(
                    () -> service.requestAbandon(maker, 7L, "Not wanted"),
                    ApiErrorCode.JOB_INVALID_STATE);
        }
        verify(approvals, never()).submit(any(), any(), any());
    }

    @Test
    void SRS_15_27_anAbandonWaitsForAPendingCommandToBeApplied() {
        job(10L, JobStatus.STOPPED);
        when(controls.existsByJobRequestIdAndStatus(7L, BatchJobControl.Status.PENDING))
                .thenReturn(true);
        refused(
                () -> service.requestAbandon(maker, 7L, "Not wanted"),
                ApiErrorCode.JOB_CONTROL_PENDING);
        verify(approvals, never()).submit(any(), any(), any());
    }

    @Test
    void SRS_G_1_anAbandonIsAProposalOnTheUnitsRouteAndChangesNothingYet() {
        BatchJobRequest failed = job(10L, JobStatus.FAILED);

        JobRequestService.JobView view = service.requestAbandon(maker, 7L, "  Wrong period  ");

        ArgumentCaptor<Submission> submission = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(maker), submission.capture(), eq(null));
        Submission s = submission.getValue();
        assertThat(s.workflowAction()).isEqualTo("C13_ABANDON");
        assertThat(s.moduleCode()).isEqualTo("C-13");
        assertThat(s.actionCode()).isEqualTo("EDIT");
        assertThat(s.entityName()).isEqualTo(JobRequestService.ABANDON_ENTITY);
        assertThat(s.entityKey()).isEqualTo("7");
        assertThat(s.actionType()).isEqualTo(ActionType.UPDATE);
        assertThat(s.locationId()).as("decided at the job's own unit").isEqualTo(10L);
        assertThat(s.reason()).isEqualTo("Wrong period");
        assertThat(s.payload())
                .contains("\"jobType\":\"PAYROLL_DRY_RUN\"", "\"status\":\"FAILED\"");
        verify(journal)
                .record(
                        7L,
                        JobJournal.Event.ABANDON_REQUESTED,
                        "maker@x",
                        JobStatus.FAILED,
                        null,
                        77L,
                        "Approval 55: Wrong period");
        // Nothing changes until the checker decides: the job stays FAILED, no command is queued.
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        verify(requests, never()).save(any());
        verify(controls, never()).save(any());
        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.pendingCommand()).isEqualTo(JobRequestService.ABANDON_AWAITING_APPROVAL);
        assertThat(view.allowedCommands()).isEmpty();
    }

    @Test
    void SRS_G_1_aScheduledJobIsAbandonedOnTheHeadquartersRouteByTheSystemAdministrator() {
        job(null, JobStatus.FAILED);

        service.requestAbandon(admin, 7L, "Interface retired");

        ArgumentCaptor<Submission> submission = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(admin), submission.capture(), eq(null));
        assertThat(submission.getValue().workflowAction()).isEqualTo("C13_ABANDON_SYS");
        assertThat(submission.getValue().locationId())
                .as("decided where the administrator works")
                .isEqualTo(1L);
    }

    @Test
    void SRS_15_27_whileAnAbandonWaitsNoOtherCommandIsTakenAndTheJobSaysSo() {
        job(10L, JobStatus.FAILED);
        when(policy.canAt(maker, "C-13", "EDIT", 10L)).thenReturn(true);
        when(approvalStatuses.pending(JobRequestService.ABANDON_ENTITY))
                .thenReturn(
                        List.of(
                                new ApprovalStatuses.Pending(
                                        55L, "7", "{}", "maker@x", NOW.minusSeconds(60))));

        refused(
                () -> service.requestControl(maker, 7L, ControlCommand.RESTART, "retry"),
                ApiErrorCode.JOB_ABANDON_PENDING);
        verify(controls, never()).save(any());
        JobRequestService.JobView view = service.detail(maker, 7L).job();
        assertThat(view.pendingCommand()).isEqualTo(JobRequestService.ABANDON_AWAITING_APPROVAL);
        assertThat(view.allowedCommands()).isEmpty();
    }

    @Test
    void SRS_15_27_abandonIsNeverRecordedAsADirectCommand() {
        job(10L, JobStatus.FAILED);
        assertThatThrownBy(() -> service.requestControl(maker, 7L, ControlCommand.ABANDON, "r"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(controls, never()).save(any());
    }

    @Test
    void SRS_15_25_aFailedJobOffersRestartAndAbandonWhileBatchProcessingRuns() {
        job(10L, JobStatus.FAILED);
        when(policy.canAt(maker, "C-13", "EDIT", 10L)).thenReturn(true);
        assertThat(service.detail(maker, 7L).job().allowedCommands())
                .containsExactlyInAnyOrder("RESTART", "ABANDON");
    }

    @Test
    void SRS_15_25_whileHaltedNothingIsResumedOrRestartedButARunningJobIsStillStopped() {
        when(halts.existsByReleasedAtIsNull()).thenReturn(true);
        when(policy.canAt(maker, "C-13", "EDIT", 10L)).thenReturn(true);

        job(10L, JobStatus.FAILED);
        refused(
                () -> service.requestControl(maker, 7L, ControlCommand.RESTART, "retry"),
                ApiErrorCode.JOB_HALTED);
        verify(controls, never()).save(any());
        assertThat(service.detail(maker, 7L).job().allowedCommands())
                .as("Restart is withheld, Abandon stays")
                .containsExactly("ABANDON");

        job(10L, JobStatus.RUNNING);
        JobRequestService.ControlView stop =
                service.requestControl(maker, 7L, ControlCommand.STOP, "Halt: stop it now");
        assertThat(stop.command()).isEqualTo("STOP");
        assertThat(stop.status()).isEqualTo("PENDING");
        assertThat(service.detail(maker, 7L).job().allowedCommands())
                .containsExactlyInAnyOrder("STOP", "PAUSE");
    }
}
