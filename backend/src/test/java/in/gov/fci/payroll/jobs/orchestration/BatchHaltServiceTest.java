package in.gov.fci.payroll.jobs.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.jobs.business.model.BatchHalt;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
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
 * Plan 05j step j4 (SRS-15-25, SRS-G-1, SRS-G-37): the kill switch is engaged at once by a System
 * Administrator at Headquarters, with a reason, once; its release is a proposal a Payroll Checker
 * at HQ decides, and a refused release leaves the halt in force.
 */
class BatchHaltServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");

    private final JobRepositories.Halts halts = mock(JobRepositories.Halts.class);
    private final Authorization policy = mock(Authorization.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final AuditService audit = mock(AuditService.class);
    private final BatchHaltService service =
            new BatchHaltService(halts, policy, approvals, audit, Clock.fixed(NOW, ZoneOffset.UTC));

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
                    Set.of(1L));

    @BeforeEach
    void wire() {
        when(policy.isAtHeadquarters(admin)).thenReturn(true);
        when(halts.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static ErrorCode code(Throwable t) {
        return ((FciException) t).getErrorCode();
    }

    private static void refused(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).satisfies(e -> assertThat(code(e)).isEqualTo(expected));
    }

    /** A halt engaged an hour ago, id 5, still in force. */
    private BatchHalt engaged() {
        BatchHalt halt = BatchHalt.engage("admin@x", "Wrong rates loaded", NOW.minusSeconds(3_600));
        ReflectionTestUtils.setField(halt, "id", 5L);
        when(halts.existsByReleasedAtIsNull()).thenReturn(true);
        when(halts.findByReleasedAtIsNullOrderByIdDesc()).thenReturn(List.of(halt));
        when(halts.findById(5L)).thenReturn(Optional.of(halt));
        return halt;
    }

    @Test
    void SRS_15_25_theStateSaysWhetherSinceWhenByWhomAndWhyProcessingIsHalted() {
        assertThat(service.state(admin).engaged()).isFalse();
        verify(policy).require(admin, "C-13", "VIEW");

        engaged();
        BatchHaltService.HaltView view = service.state(admin);
        assertThat(view.engaged()).isTrue();
        assertThat(view.haltId()).isEqualTo(5L);
        assertThat(view.haltedBy()).isEqualTo("admin@x");
        assertThat(view.haltedAt()).isEqualTo(NOW.minusSeconds(3_600));
        assertThat(view.reason()).isEqualTo("Wrong rates loaded");
        assertThat(view.releaseApprovalId()).isNull();
    }

    @Test
    void SRS_15_25_aHaltTakesEffectAtOnceWithItsReasonAndIsAudited() {
        BatchHaltService.HaltView view = service.halt(admin, "  Wrong rates loaded ");

        verify(policy).require(admin, "C-13", "HALT");
        ArgumentCaptor<BatchHalt> saved = ArgumentCaptor.forClass(BatchHalt.class);
        verify(halts).save(saved.capture());
        assertThat(saved.getValue().isEngaged()).isTrue();
        assertThat(saved.getValue().getHaltedBy()).isEqualTo("admin@x");
        assertThat(saved.getValue().getHaltedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getHaltReason()).isEqualTo("Wrong rates loaded");
        verify(audit).record(eq("admin@x"), any());
        assertThat(view.engaged()).isTrue();
        assertThat(view.reason()).isEqualTo("Wrong rates loaded");
        // No approval stands between the administrator and the stop.
        verify(approvals, never()).submit(any(), any(), any());
    }

    @Test
    void SRS_G_37_aHaltAndARequestedReleaseNeedTheirReason() {
        for (String missing : Arrays.asList(null, "", "  ")) {
            refused(() -> service.halt(admin, missing), ApiErrorCode.REASON_REQUIRED);
            refused(() -> service.requestRelease(admin, missing), ApiErrorCode.REASON_REQUIRED);
        }
        verify(halts, never()).save(any());
    }

    @Test
    void SRS_15_25_onlyHeadquartersHaltsOrReleases() {
        AccessContext zonal =
                new AccessContext(
                        8L,
                        "admin.zone@x",
                        "Zonal",
                        "s",
                        "P",
                        20L,
                        null,
                        List.of(),
                        Map.of(),
                        Set.of(),
                        Set.of("SYSTEM_ADMINISTRATOR"),
                        Set.of(20L));
        refused(() -> service.halt(zonal, "Stop"), ApiErrorCode.HQ_ONLY);
        refused(() -> service.requestRelease(zonal, "Go"), ApiErrorCode.HQ_ONLY);
        verify(halts, never()).save(any());
    }

    @Test
    void SRS_15_25_oneHaltAtATimeAndNothingToReleaseWithoutOne() {
        refused(() -> service.requestRelease(admin, "Go"), ApiErrorCode.JOB_NOT_HALTED);
        engaged();
        refused(() -> service.halt(admin, "Again"), ApiErrorCode.JOB_ALREADY_HALTED);
        verify(halts, never()).save(any());
    }

    @Test
    void SRS_G_1_aReleaseIsAProposalDecidedAtHeadquartersAndTheHaltStaysMeanwhile() {
        BatchHalt halt = engaged();
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(66L, ApprovalStatus.PENDING_APPROVAL, null));

        BatchHaltService.HaltView view = service.requestRelease(admin, " Rates corrected ");

        ArgumentCaptor<Submission> submission = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(admin), submission.capture(), eq(null));
        Submission s = submission.getValue();
        assertThat(s.workflowAction()).isEqualTo("C13_RELEASE");
        assertThat(s.moduleCode()).isEqualTo("C-13");
        assertThat(s.actionCode()).isEqualTo("HALT");
        assertThat(s.entityName()).isEqualTo(BatchHaltService.ENTITY);
        assertThat(s.entityKey()).isEqualTo("5");
        assertThat(s.locationId()).isEqualTo(1L);
        assertThat(s.reason()).isEqualTo("Rates corrected");
        assertThat(halt.isEngaged()).as("in force until approved").isTrue();
        assertThat(halt.getReleaseApprovalId()).isEqualTo(66L);
        assertThat(halt.getReleaseRequestedBy()).isEqualTo("admin@x");
        assertThat(view.releaseApprovalId()).isEqualTo(66L);
        assertThat(view.releaseReason()).isEqualTo("Rates corrected");
    }

    private ApprovalDecided decision(ApprovalStatus outcome, String reason) {
        return new ApprovalDecided(
                66L, "C-13", BatchHaltService.ENTITY, "5", outcome, reason, "{}", "checker@x", NOW);
    }

    @Test
    void SRS_G_1_anApprovedReleaseEndsTheHaltARefusedOneLeavesItAndAnotherMayBeAsked() {
        BatchHalt halt = engaged();
        halt.releaseRequested(66L, "admin@x", "Rates corrected");

        service.on(decision(ApprovalStatus.REJECTED, "Not yet"));
        assertThat(halt.isEngaged()).isTrue();
        assertThat(halt.getReleaseApprovalId()).as("a new release may be asked").isNull();
        assertThat(halt.getReleaseRequestedBy()).isNull();
        assertThat(halt.getReleaseReason()).isNull();

        halt.releaseRequested(67L, "admin@x", "Rates corrected, checked");
        service.on(decision(ApprovalStatus.APPROVED, null));
        assertThat(halt.isEngaged()).isFalse();
        assertThat(halt.getReleasedBy()).isEqualTo("checker@x");
        assertThat(halt.getReleasedAt()).isEqualTo(NOW);
        assertThat(halt.getReleaseApprovalId()).isEqualTo(67L);
    }

    @Test
    void SRS_G_1_aDecisionOnAnotherEntityOrOnAHaltAlreadyReleasedChangesNothing() {
        service.on(
                new ApprovalDecided(
                        1L,
                        "C-13",
                        "batch_job_request",
                        "5",
                        ApprovalStatus.APPROVED,
                        null,
                        "{}",
                        "checker@x",
                        NOW));
        verifyNoInteractions(halts);

        BatchHalt released = engaged();
        released.releaseRequested(66L, "admin@x", "Done");
        released.released("checker@x", NOW.minusSeconds(60));
        service.on(decision(ApprovalStatus.APPROVED, null));
        verify(halts, never()).save(any());
        assertThat(released.getReleasedAt()).isEqualTo(NOW.minusSeconds(60));
    }
}
